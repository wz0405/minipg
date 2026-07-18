package com.minipg.api.service;

import com.minipg.api.pg.KakaopayClient;
import com.minipg.api.pg.NicepayClient;
import com.minipg.api.pg.NicepayResult;
import com.minipg.common.domain.PayReq;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.PayReqMapper;
import com.minipg.common.mapper.TrMstrMapper;
import com.minipg.common.mapper.VacntMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인/취소의 단일 진입점 — Netty 게이트웨이(PayGateHandler)가 호출한다.
 *
 * 승인의 원칙:
 * <ul>
 *   <li>PG 승인 성공 후 원장(TR_MSTR) 적재까지가 한 단위. 적재에 실패하면 고객 카드만
 *       긁힌 유령거래가 남으므로 즉시 망취소를 던져 PG측 승인을 되돌린다.</li>
 *   <li>reqId(결제요청)가 있으면 사전등록 금액과 대조해 변조를 차단하고,
 *       승인 후 가맹점 서버통보는 별도 스레드(AfterProcessor)로 넘긴다.</li>
 *   <li>카드 원본 정보는 어디에도 저장하지 않는다 — PG 전달 즉시 버리고 마스킹본만 남긴다.</li>
 * </ul>
 *
 * 가상계좌는 PG를 타지 않는 자체 채번이다: 제휴사에서 벌크로 받은 계좌 풀에서
 * 비관락으로 하나를 할당하고, 입금은 외부 입금통보 웹훅이 도착했을 때 원장에 적재한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final DateTimeFormatter TID_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NicepayClient nicepayClient;
    private final KakaopayClient kakaopayClient;
    private final TrMstrMapper trMstrMapper;
    private final PayReqMapper payReqMapper;
    private final VacntMapper vacntMapper;
    private final PrepaidService prepaidService;
    private final AfterProcessor afterProcessor;

    /** 결제창 인증 완료 건 서버 승인 (성공 3001). */
    @Transactional
    public Map<String, Object> approveAuth(String authToken, String txTid, String nextAppUrl,
            long amt, String orderId, String mchtId, String goodsNm, String reqId) {
        Map<String, Object> reqError = validatePayReq(reqId, amt);
        if (reqError != null) {
            return reqError;
        }
        NicepayResult r = nicepayClient.approveAuth(authToken, txTid, nextAppUrl, amt);
        if (!r.success("3001")) {
            markReq(reqId, PayReq.ST_FAILED, null);
            return fail(r);
        }
        try {
            insertApproval(r.tid(), resolveMchtId(reqId, mchtId), "CARD", amt, "AUTH",
                    orderId, goodsNm, mask(r.get("CardNo")), null);
        } catch (Exception e) {
            return netCancel(r.tid(), orderId, amt, true, e);
        }
        markReq(reqId, PayReq.ST_APPROVED, r.tid());
        afterProcessor.notifyApproved(reqId, r.tid(), amt);
        return ok(Map.of("tid", r.tid(), "amt", amt, "pmCd", "CARD"));
    }

    /** 수기(키인) 결제. */
    @Transactional
    public Map<String, Object> keyin(String mchtId, long amt, String goodsNm, String orderId,
            String cardNo, String expYear, String expMonth, String idNo, String cardPw, String reqId) {
        Map<String, Object> reqError = validatePayReq(reqId, amt);
        if (reqError != null) {
            return reqError;
        }
        NicepayResult r = nicepayClient.keyin(orderId, amt, goodsNm, cardNo, expYear, expMonth, idNo, cardPw);
        if (!r.success("3001")) {
            markReq(reqId, PayReq.ST_FAILED, null);
            return fail(r);
        }
        try {
            insertApproval(r.tid(), resolveMchtId(reqId, mchtId), "CARD", amt, "KEYIN",
                    orderId, goodsNm, mask(nvlOr(r.get("CardNo"), cardNo)), null);
        } catch (Exception e) {
            return netCancel(r.tid(), orderId, amt, false, e);
        }
        markReq(reqId, PayReq.ST_APPROVED, r.tid());
        afterProcessor.notifyApproved(reqId, r.tid(), amt);
        return ok(Map.of("tid", r.tid(), "amt", amt, "pmCd", "CARD"));
    }

    /** 가상계좌 채번 — 계좌 풀에서 비관락으로 할당. PG 통신 없음. */
    @Transactional
    public Map<String, Object> vacntIssue(String mchtId, long amt, String goodsNm, String reqId) {
        Map<String, Object> reqError = validatePayReq(reqId, amt);
        if (reqError != null) {
            return reqError;
        }
        Map<String, Object> pool = vacntMapper.selectAvailablePoolForUpdate();
        if (pool == null) {
            return Map.of("rsltCd", "1203", "rsltMsg", "가용 가상계좌 풀 소진 — 만료 회수 대기");
        }
        long poolSeq = ((Number) pool.get("POOL_SEQ")).longValue();
        vacntMapper.assignPool(poolSeq);

        String tid = "VA" + LocalDateTime.now().format(TID_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        String expDt = LocalDateTime.now().plusDays(3).format(YMD);
        vacntMapper.insert(tid, poolSeq, reqId, resolveMchtId(reqId, mchtId), amt,
                (String) pool.get("BANK_CD"), (String) pool.get("BANK_NM"),
                (String) pool.get("VACNT_NO"), expDt);
        return ok(Map.of("tid", tid, "amt", amt, "pmCd", "VACNT",
                "bankNm", String.valueOf(pool.get("BANK_NM")),
                "vacntNo", String.valueOf(pool.get("VACNT_NO")), "expDt", expDt));
    }

    /** 입금통보 웹훅 — 계좌번호+금액으로 채번 건을 찾아 검증 후 원장에 적재한다. */
    @Transactional
    public Map<String, Object> vacntDepositByAcct(String vacntNo, long amt) {
        Map<String, Object> vacnt = vacntMapper.selectActiveByVacntNo(vacntNo);
        if (vacnt == null) {
            return Map.of("rsltCd", "1201", "rsltMsg", "입금대기 채번 없음: " + vacntNo);
        }
        long expected = ((Number) vacnt.get("AMT")).longValue();
        if (expected != amt) {
            return Map.of("rsltCd", "1204",
                    "rsltMsg", "입금액 상이: 채번=" + expected + ", 입금=" + amt + " (과오입금 처리 대상)");
        }
        return deposit((String) vacnt.get("TID"), vacnt);
    }

    /** 모의입금 트리거 (TID 기준) — 데모에서 입금통보를 시뮬레이션한다. */
    @Transactional
    public Map<String, Object> vacntDeposit(String tid) {
        Map<String, Object> vacnt = vacntMapper.selectByTid(tid);
        if (vacnt == null) {
            return Map.of("rsltCd", "1201", "rsltMsg", "채번 내역 없음: " + tid);
        }
        return deposit(tid, vacnt);
    }

    private Map<String, Object> deposit(String tid, Map<String, Object> vacnt) {
        if (vacntMapper.markDeposited(tid) == 0) {
            return Map.of("rsltCd", "1202", "rsltMsg", "이미 입금되었거나 만료된 채번: " + tid);
        }
        long amt = ((Number) vacnt.get("AMT")).longValue();
        String reqId = (String) vacnt.get("REQ_ID");
        insertApproval(tid, (String) vacnt.get("MCHT_ID"), "VACNT", amt, "VBANK",
                reqId, "가상계좌입금", null, null);
        markReq(reqId, PayReq.ST_APPROVED, tid);
        afterProcessor.notifyApproved(reqId, tid, amt);
        return ok(Map.of("tid", tid, "amt", amt, "pmCd", "VACNT"));
    }

    /**
     * 카카오페이 직연동 승인 — 결제창 복귀(pg_token) 후 최종 승인.
     * ready 시점에 결제요청(reqId)에 카카오 tid를 박아 두었으므로 그걸로 승인한다.
     * 정산은 카드에 흡수(PM_CD=CARD, PAY_TYPE=KAKAO)하고, 취소는 payType 분기로 카카오 API를 탄다.
     */
    @Transactional
    public Map<String, Object> kakaoApprove(String reqId, String pgToken) {
        PayReq req = payReqMapper.selectById(reqId);
        if (req == null) {
            return Map.of("rsltCd", "1003", "rsltMsg", "결제요청 없음: " + reqId);
        }
        if (PayReq.ST_APPROVED.equals(req.getReqStCd())) {
            return Map.of("rsltCd", "1005", "rsltMsg", "이미 승인된 결제요청: " + reqId);
        }
        String tid = req.getTid();
        Map<String, Object> r = kakaopayClient.approve(tid, reqId, "minipg-demo", pgToken);
        if (r.get("aid") == null) {
            markReq(reqId, PayReq.ST_FAILED, tid);
            return Map.of("rsltCd", "1401",
                    "rsltMsg", "카카오페이 승인 실패: " + r.getOrDefault("msg", r.getOrDefault("error_message", "")));
        }
        try {
            insertApproval(tid, req.getMchtId(), "CARD", req.getAmt(), "KAKAO",
                    reqId, req.getGoodsNm(), null, null);
        } catch (Exception e) {
            log.error("카카오 승인 후 원장 적재 실패 → 취소 원복: tid={}", tid, e);
            kakaopayClient.cancel(tid, req.getAmt());
            return Map.of("rsltCd", "9995", "rsltMsg", "내부 오류로 승인 원복 완료 — 재시도 필요");
        }
        markReq(reqId, PayReq.ST_APPROVED, tid);
        afterProcessor.notifyApproved(reqId, tid, req.getAmt());
        return ok(Map.of("tid", tid, "amt", req.getAmt(), "pmCd", "CARD"));
    }

    /** 빌키 승인(정기결제 회차). */
    @Transactional
    public Map<String, Object> billingApprove(String bid, String mchtId, long amt, String goodsNm) {
        String moid = "BILL" + LocalDateTime.now().format(TID_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        NicepayResult r = nicepayClient.billingApprove(bid, moid, amt, goodsNm);
        if (!r.success("3001")) {
            return fail(r);
        }
        try {
            insertApproval(r.tid(), mchtId, "CARD", amt, "BILLING", moid, goodsNm, mask(r.get("CardNo")), null);
        } catch (Exception e) {
            return netCancel(r.tid(), moid, amt, false, e);
        }
        return ok(Map.of("tid", r.tid(), "amt", amt, "pmCd", "CARD", "moid", moid));
    }

    /** 전액 취소 — 원거래 존재/중복취소 검증 후 수단별 분기. */
    @Transactional
    public Map<String, Object> cancel(String tid, String reason) {
        TrMstr approval = trMstrMapper.selectApproval(tid);
        if (approval == null) {
            return Map.of("rsltCd", "1001", "rsltMsg", "원거래 없음: " + tid);
        }
        if (trMstrMapper.countCancel(tid) > 0) {
            return Map.of("rsltCd", "1002", "rsltMsg", "이미 취소된 거래: " + tid);
        }

        switch (approval.getPmCd()) {
            case "MONEY", "POINT" -> prepaidService.restoreForCancel(approval);
            case "VACNT" -> log.info("가상계좌 취소: 원장 상쇄만 수행 (환불이체는 확장 포인트), tid={}", tid);
            default -> {
                if (TrMstr.CH_LIVE.equals(approval.getChannel())) {
                    if ("KAKAO".equals(approval.getPayType())) {
                        Map<String, Object> kr = kakaopayClient.cancel(tid, approval.getAmt());
                        if (kr.get("aid") == null && kr.get("status") == null) {
                            return Map.of("rsltCd", "1402", "rsltMsg",
                                    "카카오페이 취소 실패: " + kr.getOrDefault("msg", ""));
                        }
                    } else {
                        boolean useAuthMid = "AUTH".equals(approval.getPayType());
                        NicepayResult r = nicepayClient.cancel(
                                tid, approval.getOrderId(), approval.getAmt(), reason, useAuthMid);
                        if (!r.success("2001")) {
                            return fail(r);
                        }
                    }
                }
            }
        }
        trMstrMapper.insert(TrMstr.cancelOf(approval, LocalDateTime.now()));
        return ok(Map.of("tid", tid, "amt", -approval.getAmt()));
    }

    /**
     * 망취소 — PG 승인은 떨어졌는데 내부 원장 적재가 실패한 경우.
     * 이대로 두면 고객 카드만 결제된 유령거래가 되므로 PG측 승인을 즉시 되돌린다.
     */
    private Map<String, Object> netCancel(String pgTid, String moid, long amt, boolean useAuthMid, Exception cause) {
        log.error("원장 적재 실패 → 망취소 시도: tid={}", pgTid, cause);
        NicepayResult nc = nicepayClient.cancel(pgTid, moid, amt, "NET CANCEL", useAuthMid);
        if (nc.success("2001")) {
            return Map.of("rsltCd", "9995", "rsltMsg", "내부 오류로 승인 원복(망취소) 완료 — 재시도 필요");
        }
        log.error("망취소도 실패 — 수동 대사 필요: tid={}, pg={} {}", pgTid, nc.resultCode(), nc.resultMsg());
        return Map.of("rsltCd", "9994", "rsltMsg", "내부 오류 + 망취소 실패 — 관리자 확인 필요: " + pgTid);
    }

    /** 결제요청 검증 — 존재/중복승인/금액 대조. reqId 없는 단독 결제는 통과. */
    private Map<String, Object> validatePayReq(String reqId, long amt) {
        if (reqId == null || reqId.isBlank()) {
            return null;
        }
        PayReq req = payReqMapper.selectById(reqId);
        if (req == null) {
            return Map.of("rsltCd", "1003", "rsltMsg", "결제요청 없음: " + reqId);
        }
        if (PayReq.ST_APPROVED.equals(req.getReqStCd())) {
            return Map.of("rsltCd", "1005", "rsltMsg", "이미 승인된 결제요청: " + reqId);
        }
        if (req.getAmt() != amt) {
            return Map.of("rsltCd", "1004",
                    "rsltMsg", "금액 불일치(변조 의심): 요청=" + req.getAmt() + ", 승인시도=" + amt);
        }
        return null;
    }

    private String resolveMchtId(String reqId, String mchtId) {
        if (reqId == null || reqId.isBlank()) {
            return mchtId;
        }
        PayReq req = payReqMapper.selectById(reqId);
        return req != null ? req.getMchtId() : mchtId;
    }

    private void markReq(String reqId, String stCd, String tid) {
        if (reqId != null && !reqId.isBlank()) {
            payReqMapper.updateStatus(reqId, stCd, tid);
        }
    }

    private void insertApproval(String tid, String mchtId, String pmCd, long amt, String payType,
            String orderId, String goodsNm, String cardNoMasked, String usrId) {
        LocalDateTime now = LocalDateTime.now();
        trMstrMapper.insert(TrMstr.builder()
                .tid(tid).orgTid(tid)
                .mchtId(mchtId).pmCd(pmCd)
                .txStCd(TrMstr.ST_APPROVAL)
                .amt(amt).trDt(now.toLocalDate()).trTm(now)
                .channel(TrMstr.CH_LIVE).payType(payType)
                .orderId(orderId).goodsNm(goodsNm)
                .cardNoMasked(cardNoMasked).usrId(usrId)
                .build());
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> res = new HashMap<>(data);
        res.put("rsltCd", "0000");
        res.put("rsltMsg", "성공");
        return res;
    }

    private Map<String, Object> fail(NicepayResult r) {
        return Map.of("rsltCd", nvlOr(r.resultCode(), "9999"), "rsltMsg", nvlOr(r.resultMsg(), "PG 오류"));
    }

    private String mask(String cardNo) {
        String digits = cardNo == null ? "" : cardNo.replaceAll("\\D", "");
        if (digits.length() < 10) {
            return cardNo;
        }
        return digits.substring(0, 6) + "******" + digits.substring(digits.length() - 4);
    }

    private String nvlOr(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
