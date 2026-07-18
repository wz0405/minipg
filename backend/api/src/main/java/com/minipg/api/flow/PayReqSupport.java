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

    public String resolveMcht(PayReq req, String fallback) {
        return req != null ? req.getMchtId() : fallback;
    }

    public void mark(String reqId, String status, String tid) {
        if (reqId != null && !reqId.isBlank()) {
            payReqMapper.updateStatus(reqId, status, tid);
        }
    }
}
