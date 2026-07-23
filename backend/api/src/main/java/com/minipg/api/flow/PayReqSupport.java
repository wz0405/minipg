package com.minipg.api.flow;

import com.minipg.common.domain.PayReq;
import com.minipg.common.mapper.PayReqMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 결제요청(사전등록 주문) 공통 검증 — 존재/중복승인/금액 대조. */
@Component
@RequiredArgsConstructor
public class PayReqSupport {

    private final PayReqMapper payReqMapper;

    /** reqId 없는 단독 결제는 null 반환으로 통과시킨다. */
    public PayReq validateForPay(String reqId, long amt) {
        if (reqId == null || reqId.isBlank()) {
            return null;
        }
        PayReq req = payReqMapper.selectById(reqId);
        if (req == null) {
            throw new FlowStop("1003", "결제요청 없음: " + reqId);
        }
        if (PayReq.ST_APPROVED.equals(req.getReqStCd())) {
            throw new FlowStop("1005", "이미 승인된 결제요청: " + reqId);
        }
        if (req.getAmt() != amt) {
            throw new FlowStop("1004", "금액 불일치(변조 의심): 요청=" + req.getAmt() + ", 승인시도=" + amt);
        }
        return req;
    }

    /**
     * reqId 필수 조회 — 인증요청 단계에서 이미 심어둔 PAY_REQ를 실 데이터의 단일 소스로 그대로 쓴다.
     * front→bld 전문엔 reqId(식별값)만 싣고, 금액·가맹점 등은 여기서 DB로 직접 확정한다.
     */
    public PayReq requireForPay(String reqId) {
        if (reqId == null || reqId.isBlank()) {
            throw new FlowStop("1003", "결제요청 없음");
        }
        PayReq req = payReqMapper.selectById(reqId);
        if (req == null) {
            throw new FlowStop("1003", "결제요청 없음: " + reqId);
        }
        if (PayReq.ST_APPROVED.equals(req.getReqStCd())) {
            throw new FlowStop("1005", "이미 승인된 결제요청: " + reqId);
        }
        return req;
    }

    public String resolveMcht(PayReq req, String fallback) {
        return req != null ? req.getMchtId() : fallback;
    }

    public void mark(String reqId, String status, String tid) {
        if (reqId != null && !reqId.isBlank()) {
            payReqMapper.updateStatus(reqId, status, tid);
        }
    }
}
