package com.minipg.common.service;

import com.minipg.common.domain.MchtFeeRate;
import com.minipg.common.domain.SmStmt;
import com.minipg.common.domain.SmStmtTid;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.fee.BusinessDayCalculator;
import com.minipg.common.fee.FeeCalculator;
import com.minipg.common.fee.FeeResult;
import com.minipg.common.mapper.HolidayMapper;
import com.minipg.common.mapper.MchtMapper;
import com.minipg.common.mapper.StmtMapper;
import com.minipg.common.mapper.TrMstrMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일정산 배치.
 *
 * <ol>
 *   <li>해당 정산일 기존 산출물 삭제 (멱등 재실행)</li>
 *   <li>거래 원장 조회 — 승인·취소행 모두 포함, 취소는 음수라 자연 상쇄</li>
 *   <li>건별 수수료 계산 후 SM_STMT_TID 스냅샷 적재</li>
 *   <li>가맹점별 집계 + 지급예정일(N영업일) 산출 후 SM_STMT 적재</li>
 * </ol>
 *
 * 수수료 정책이 없는 가맹점 거래가 발견되면 전체 롤백한다 (정산 마감은 all-or-nothing).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final int CHUNK_SIZE = 500;

    private final TrMstrMapper trMstrMapper;
    private final StmtMapper stmtMapper;
    private final MchtMapper mchtMapper;
    private final HolidayMapper holidayMapper;
    private final FeeCalculator feeCalculator;
    private final BusinessDayCalculator businessDayCalculator;

    @Transactional
    public Map<String, Object> run(LocalDate settleDt) {
        stmtMapper.deleteStmt(settleDt);
        stmtMapper.deleteStmtTid(settleDt);

        List<TrMstr> txs = trMstrMapper.selectByTrDt(settleDt);
        if (txs.isEmpty()) {
            log.info("정산 대상 거래 없음: {}", settleDt);
            return Map.of("settleDt", settleDt.toString(), "txCnt", 0, "mchtCnt", 0);
        }

        Map<String, MchtFeeRate> rates = mchtMapper.selectFeeRates(settleDt).stream()
                .collect(Collectors.toMap(MchtFeeRate::rateKey, Function.identity()));

        List<SmStmtTid> details = new ArrayList<>(txs.size());
        for (TrMstr tx : txs) {
            MchtFeeRate rate = rates.get(tx.getMchtId() + "|" + tx.getPayMethod());
            if (rate == null) {
                throw new IllegalStateException(
                        "수수료 정책 없음: " + tx.getMchtId() + "/" + tx.getPayMethod() + ", tid=" + tx.getTid());
            }
            FeeResult fee = feeCalculator.calculate(tx.getAmt(), rate.getCostRate(), rate.getSalesRate());
            details.add(SmStmtTid.of(settleDt, tx, rate, fee));
        }
        for (int i = 0; i < details.size(); i += CHUNK_SIZE) {
            stmtMapper.insertStmtTidBatch(details.subList(i, Math.min(i + CHUNK_SIZE, details.size())));
        }

        Set<LocalDate> holidays = new HashSet<>(holidayMapper.selectAll());
        List<SmStmt> aggs = stmtMapper.selectAggregate(settleDt);
        for (SmStmt agg : aggs) {
            int cycle = rates.get(agg.getMchtId() + "|" + agg.getPayMethod()).getSettleCycle();
            agg.setPayoutDt(businessDayCalculator.addBusinessDays(settleDt, cycle, holidays));
            stmtMapper.insertStmt(agg);
        }

        log.info("정산 완료: settleDt={}, 거래 {}건, 가맹점 {}곳", settleDt, txs.size(), aggs.size());
        return Map.of("settleDt", settleDt.toString(), "txCnt", txs.size(), "mchtCnt", aggs.size());
    }
}
