package com.minipg.common.service;

import com.minipg.common.domain.InPgTrx;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.MchtMapper;
import com.minipg.common.mapper.ReconMapper;
import com.minipg.common.mapper.TrMstrMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시딩 — 정산·대사 파이프라인에 태울 대량 거래를 생성한다 (CHANNEL='SEED').
 *
 * <ul>
 *   <li>일자별 승인 거래 + cancelRate 비율의 취소행 (절반은 당일취소, 절반은 익일취소)</li>
 *   <li>PG 대사 소스(IN_PG_TRX)를 함께 생성하되 mismatchRate 비율로 불일치를 주입
 *       — 누락(ONLY_US), 유령거래(ONLY_PG), 금액변조(AMT_MISMATCH)</li>
 * </ul>
 *
 * rndSeed를 지정하면 동일 데이터가 재현된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedService {

    private static final int CHUNK_SIZE = 500;

    private final TrMstrMapper trMstrMapper;
    private final ReconMapper reconMapper;
    private final MchtMapper mchtMapper;

    @Transactional
    public Map<String, Object> seed(LocalDate fromDt, LocalDate toDt, int perDay,
            double cancelRate, double mismatchRate, Long rndSeed) {

        Random rnd = rndSeed != null ? new Random(rndSeed) : new Random();
        List<String> mchtIds = mchtMapper.selectMchtList().stream()
                .map(m -> String.valueOf(m.get("MCHT_ID")))
                .toList();
        if (mchtIds.isEmpty()) {
            throw new IllegalStateException("시딩할 가맹점이 없습니다 (SI_MCHT 비어 있음)");
        }

        String fromYmd = fromDt.toString().replace("-", "");
        String toYmd = toDt.toString().replace("-", "");
        trMstrMapper.deleteSeedByOriginDate(fromYmd, toYmd);
        reconMapper.deleteSeedSrc(fromDt, toDt.plusDays(1));

        List<TrMstr> ledger = new ArrayList<>();
        List<InPgTrx> pgSrc = new ArrayList<>();
        int ghostSeq = 0;

        for (LocalDate dt = fromDt; !dt.isAfter(toDt); dt = dt.plusDays(1)) {
            for (int i = 0; i < perDay; i++) {
                String tid = "SEED%s%06d".formatted(dt.toString().replace("-", ""), i);
                long amt = (1 + rnd.nextInt(200)) * 1000L;
                LocalDateTime tm = dt.atTime(9 + rnd.nextInt(13), rnd.nextInt(60), rnd.nextInt(60));

                TrMstr approval = TrMstr.builder()
                        .tid(tid).orgTid(tid)
                        .mchtId(mchtIds.get(rnd.nextInt(mchtIds.size())))
                        .pmCd(randomPmCd(rnd)).txStCd(TrMstr.ST_APPROVAL)
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
                        .txStCd(TrMstr.ST_APPROVAL)
                        .amt((1 + rnd.nextInt(100)) * 1000L)
                        .srcNm("SEED_GEN").build());
            }
        }

        for (int i = 0; i < ledger.size(); i += CHUNK_SIZE) {
            trMstrMapper.insertBatch(ledger.subList(i, Math.min(i + CHUNK_SIZE, ledger.size())));
        }
        for (int i = 0; i < pgSrc.size(); i += CHUNK_SIZE) {
            reconMapper.insertPgTrxBatch(pgSrc.subList(i, Math.min(i + CHUNK_SIZE, pgSrc.size())));
        }

        log.info("시딩 완료: {}~{}, 원장 {}행, PG소스 {}행", fromDt, toDt, ledger.size(), pgSrc.size());
        return Map.of("fromDt", fromDt.toString(), "toDt", toDt.toString(),
                "ledgerRows", ledger.size(), "pgSrcRows", pgSrc.size());
    }

    /** 결제수단 분포 — 카드 70%, 가상계좌 15%, 머니 10%, 포인트 5%. */
    private String randomPmCd(Random rnd) {
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
     * 원장 행의 PG측 미러를 만든다. mismatchRate 확률로 누락 또는 금액변조를 주입한다.
     * 대사 대상은 PG 경유 거래(카드/가상계좌)뿐 — 선불(MONEY/POINT)은 자체 원장이라 미러하지 않는다.
     */
    private void addPgMirror(List<InPgTrx> pgSrc, TrMstr tr, Random rnd, double mismatchRate) {
        if (!"CARD".equals(tr.getPmCd()) && !"VACNT".equals(tr.getPmCd())) {
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
                .txStCd(tr.getTxStCd())
                .amt(amt)
                .srcNm("SEED_GEN").build());
    }
}
