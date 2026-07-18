package com.minipg.common.service;

import com.minipg.common.mapper.ReconMapper;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래대사 — 내부 원장(TR_MSTR) vs PG 거래내역(IN_PG_TRX)을 (TID, 거래상태) 키로 대조한다.
 *
 * <ul>
 *   <li>ONLY_US: 내부에만 존재 (PG 누락 — 승인 유실/미전송 의심)</li>
 *   <li>ONLY_PG: PG에만 존재 (내부 유실 — 원장 적재 실패 의심)</li>
 *   <li>AMT_MISMATCH: 양쪽 존재하나 금액 불일치</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconService {

    private final ReconMapper reconMapper;

    @Transactional
    public Map<String, Object> run(LocalDate reconDt) {
        reconMapper.deleteResult(reconDt);
        int onlyUs = reconMapper.insertOnlyUs(reconDt);
        int onlyPg = reconMapper.insertOnlyPg(reconDt);
        int amtMismatch = reconMapper.insertAmtMismatch(reconDt);
        int total = onlyUs + onlyPg + amtMismatch;

        log.info("대사 완료: reconDt={}, ONLY_US={}, ONLY_PG={}, AMT_MISMATCH={}", reconDt, onlyUs, onlyPg, amtMismatch);
        return Map.of("reconDt", reconDt.toString(),
                "onlyUs", onlyUs, "onlyPg", onlyPg, "amtMismatch", amtMismatch, "diffTotal", total);
    }

    /**
     * PG 대사 소스가 비어 있으면 내부 원장을 미러링해 모의 소스를 만든다.
     * 실결제(LIVE)만 있던 날도 대사가 ONLY_US로 도배되지 않게 하는 안전장치.
     */
    @Transactional
    public int mockSourceIfEmpty(LocalDate reconDt) {
        if (reconMapper.countPgTrx(reconDt) > 0) {
            return 0;
        }
        int inserted = reconMapper.insertMockFromTrMstr(reconDt);
        log.info("PG 대사 소스 자동 생성(AUTO_MOCK): reconDt={}, {}건", reconDt, inserted);
        return inserted;
    }
}
