package com.minipg.common.service;

import com.minipg.common.domain.InPgTrx;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.MchtMapper;
import com.minipg.common.mapper.ReconMapper;
import com.minipg.common.mapper.TrMstrMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 시딩 — 정산 파이프라인에 태울 대량 거래(CHANNEL='SEED')를 백그라운드로 생성한다.
 *
 * <ul>
 *   <li>일 단위로 적재하며 진행률을 노출한다 (동시 1건만 실행)</li>
 *   <li>중단 요청 시 그때까지 적재분은 유지한다 — 재시딩이 멱등(기간 삭제 후 재생성)이라
 *       다시 돌리면 깨끗해지므로, 중단 시점의 대량 삭제보다 안전하다</li>
 *   <li>PG 대사 소스도 함께 생성하며 mismatchRate 비율로 불일치를 주입한다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedService {

    private static final int CHUNK_SIZE = 500;

    private final TrMstrMapper trMstrMapper;
    private final ReconMapper reconMapper;
    private final MchtMapper mchtMapper;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Map<String, Object> progress = Map.of("state", "IDLE");

    /** 비동기 시작 — 이미 실행 중이면 false. */
    public boolean start(LocalDate fromDt, LocalDate toDt, int perDay,
            double cancelRate, double mismatchRate, Long rndSeed) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        stopRequested.set(false);
        setProgress("RUNNING", fromDt, toDt, 0,
                (int) (toDt.toEpochDay() - fromDt.toEpochDay() + 1), 0, null);
        worker.submit(() -> {
            try {
                seedInternal(fromDt, toDt, perDay, cancelRate, mismatchRate, rndSeed);
            } catch (Exception e) {
                log.error("시딩 실패", e);
                setProgress("ERROR", fromDt, toDt, 0, 0, 0, e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    public Map<String, Object> status() {
        return progress;
    }

    private void seedInternal(LocalDate fromDt, LocalDate toDt, int perDay,
            double cancelRate, double mismatchRate, Long rndSeed) {
        Random rnd = rndSeed != null ? new Random(rndSeed) : new Random();
        List<String> mchtIds = mchtMapper.selectMchtList().stream()
                .map(m -> String.valueOf(m.get("MCHT_ID")))
                .toList();
        if (mchtIds.isEmpty()) {
            setProgress("ERROR", fromDt, toDt, 0, 0, 0, "시딩할 가맹점이 없습니다");
            return;
        }

        trMstrMapper.deleteSeedByOriginDate(
                fromDt.toString().replace("-", ""), toDt.toString().replace("-", ""));
        reconMapper.deleteSeedSrc(fromDt, toDt.plusDays(1));

        int totalDays = (int) (toDt.toEpochDay() - fromDt.toEpochDay() + 1);
        int doneDays = 0;
        long totalRows = 0;
        int ghostSeq = 0;

        for (LocalDate dt = fromDt; !dt.isAfter(toDt); dt = dt.plusDays(1)) {
            if (stopRequested.get()) {
                log.info("시딩 중단 요청 — {}일차까지 적재 유지", doneDays);
                setProgress("STOPPED", fromDt, toDt, doneDays, totalDays, totalRows,
                        "중단됨 — 적재분은 유지 (재시딩 시 자동 정리)");
                return;
            }
            List<TrMstr> ledger = new ArrayList<>();
            List<InPgTrx> pgSrc = new ArrayList<>();
            for (int i = 0; i < perDay; i++) {
                String tid = "SEED%s%06d".formatted(dt.toString().replace("-", ""), i);
                long amt = (1 + rnd.nextInt(200)) * 1000L;
                LocalDateTime tm = dt.atTime(9 + rnd.nextInt(13), rnd.nextInt(60), rnd.nextInt(60));

                TrMstr approval = TrMstr.builder()
                        .tid(tid).orgTid(tid)
                        .mchtId(mchtIds.get(rnd.nextInt(mchtIds.size())))
                        .payMethod(randomPayMethod(rnd)).txStatus(TrMstr.STATUS_APPROVED)
                        .amt(amt).trDt(dt).trTm(tm)
                        .channel(TrMstr.CH_SEED).payType("AUTH")
                        .goodsNm("시딩상품").build();
                ledger.add(approval);
                addPgMirror(pgSrc, approval, rnd, mismatchRate);

                if (rnd.nextDouble() < cancelRate) {
                    LocalDate ccDt = rnd.nextBoolean() || dt.equals(toDt) ? dt : dt.plusDays(1);
                    TrMstr cancel = TrMstr.cancelOf(approval, ccDt.atTime(tm.toLocalTime()));
                    ledger.add(cancel);
                    addPgMirror(pgSrc, cancel, rnd, mismatchRate);
                }
            }
            if (rnd.nextDouble() < mismatchRate * perDay) {
                pgSrc.add(InPgTrx.builder()
                        .reconDt(dt)
                        .tid("GHOST%s%04d".formatted(dt.toString().replace("-", ""), ghostSeq++))
                        .txStatus(TrMstr.STATUS_APPROVED)
                        .amt((1 + rnd.nextInt(100)) * 1000L)
                        .srcNm("SEED_GEN").build());
            }
            for (int i = 0; i < ledger.size(); i += CHUNK_SIZE) {
                trMstrMapper.insertBatch(ledger.subList(i, Math.min(i + CHUNK_SIZE, ledger.size())));
            }
            for (int i = 0; i < pgSrc.size(); i += CHUNK_SIZE) {
                reconMapper.insertPgTrxBatch(pgSrc.subList(i, Math.min(i + CHUNK_SIZE, pgSrc.size())));
            }
            doneDays++;
            totalRows += ledger.size();
            setProgress("RUNNING", fromDt, toDt, doneDays, totalDays, totalRows, null);
        }
        log.info("시딩 완료: {}~{}, 원장 {}행", fromDt, toDt, totalRows);
        setProgress("DONE", fromDt, toDt, doneDays, totalDays, totalRows, null);
    }

    private void setProgress(String state, LocalDate fromDt, LocalDate toDt,
            int doneDays, int totalDays, long rows, String msg) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("state", state);
        p.put("fromDt", fromDt.toString());
        p.put("toDt", toDt.toString());
        p.put("doneDays", doneDays);
        p.put("totalDays", totalDays);
        p.put("rows", rows);
        if (msg != null) {
            p.put("msg", msg);
        }
        progress = p;
    }

    /** 결제수단 분포 — 카드 70%, 가상계좌 15%, 머니 10%, 포인트 5%. */
    private String randomPayMethod(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.70) {
            return "CARD";
        }
        if (r < 0.85) {
            return "VACNT";
        }
        return r < 0.95 ? "MONEY" : "POINT";
    }

    /**
     * 원장 행의 PG측 미러 — mismatchRate 확률로 누락/금액변조를 주입한다.
     * 대사 대상은 PG 경유 거래(카드/가상계좌)뿐이라 선불은 미러하지 않는다.
     */
    private void addPgMirror(List<InPgTrx> pgSrc, TrMstr tr, Random rnd, double mismatchRate) {
        if (!"CARD".equals(tr.getPayMethod()) && !"VACNT".equals(tr.getPayMethod())) {
            return;
        }
        double roll = rnd.nextDouble();
        if (roll < mismatchRate / 2) {
            return;
        }
        long amt = tr.getAmt();
        if (roll < mismatchRate) {
            amt += tr.getAmt() >= 0 ? 100 : -100;
        }
        pgSrc.add(InPgTrx.builder()
                .reconDt(tr.getTrDt())
                .tid(tr.getTid())
                .txStatus(tr.getTxStatus())
                .amt(amt)
                .srcNm("SEED_GEN").build());
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
