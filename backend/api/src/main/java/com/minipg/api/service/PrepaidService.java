package com.minipg.api.service;

import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.PpMapper;
import com.minipg.common.mapper.TrMstrMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선불 머니/포인트 — PG 무관 자체 원장.
 *
 * 잔액(MB_PP_MTHD)만 UPDATE를 허용하고, 모든 변동은 MB_PP_HIST에 INSERT-only로 남긴다.
 * 결제는 조건부 UPDATE(잔액부족 원자 차단) 성공 후에만 거래 원장에 승인행을 적재하고,
 * 취소 시 원거래(ORG_TID)의 USR_ID 기준으로 잔액을 복원한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepaidService {

    private static final DateTimeFormatter TID_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final PpMapper ppMapper;
    private final TrMstrMapper trMstrMapper;

    @Transactional
    public Map<String, Object> charge(String usrId, String ppType, long amt) {
        if (amt <= 0) {
            return Map.of("rsltCd", "1100", "rsltMsg", "충전금액은 양수여야 합니다");
        }
        if (ppMapper.addBalance(usrId, ppType, amt) == 0) {
            return Map.of("rsltCd", "1101", "rsltMsg", "선불 매체 없음: " + usrId + "/" + ppType);
        }
        long blnc = ppMapper.selectBalance(usrId, ppType);
        ppMapper.insertHist(usrId, ppType, "CHARGE", amt, blnc, null);
        return Map.of("rsltCd", "0000", "rsltMsg", "충전 완료", "blnc", blnc);
    }

    @Transactional
    public Map<String, Object> pay(String usrId, String ppType, String mchtId, long amt, String goodsNm) {
        if (amt <= 0) {
            return Map.of("rsltCd", "1100", "rsltMsg", "결제금액은 양수여야 합니다");
        }
        if (ppMapper.addBalance(usrId, ppType, -amt) == 0) {
            return Map.of("rsltCd", "1102", "rsltMsg", "잔액 부족 또는 매체 없음: " + usrId + "/" + ppType);
        }
        String tid = ("MONEY".equals(ppType) ? "PPM" : "PPP")
                + LocalDateTime.now().format(TID_FMT) + ThreadLocalRandom.current().nextInt(1000, 9999);
        LocalDateTime now = LocalDateTime.now();
        trMstrMapper.insert(TrMstr.builder()
                .tid(tid).orgTid(tid)
                .mchtId(mchtId).pmCd(ppType)
                .txStCd(TrMstr.ST_APPROVAL)
                .amt(amt).trDt(now.toLocalDate()).trTm(now)
                .channel(TrMstr.CH_LIVE).payType("PP")
                .goodsNm(goodsNm).usrId(usrId)
                .build());
        long blnc = ppMapper.selectBalance(usrId, ppType);
        ppMapper.insertHist(usrId, ppType, "PAY", -amt, blnc, tid);
        return Map.of("rsltCd", "0000", "rsltMsg", "선불 결제 완료", "tid", tid, "amt", amt, "blnc", blnc);
    }

    /** 선불 거래 취소 시 잔액 복원. PaymentService.cancel 트랜잭션 안에서 호출된다. */
    public void restoreForCancel(TrMstr approval) {
        String usrId = approval.getUsrId();
        String ppType = approval.getPmCd();
        if (usrId == null) {
            log.warn("선불 취소인데 USR_ID 없음 (시딩 거래로 추정): tid={}", approval.getTid());
            return;
        }
        ppMapper.addBalance(usrId, ppType, approval.getAmt());
        long blnc = ppMapper.selectBalance(usrId, ppType);
        ppMapper.insertHist(usrId, ppType, "CANCEL", approval.getAmt(), blnc, approval.getTid());
    }
}
