package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.flow.PayReqSupport;
import com.minipg.api.partner.PartnerResult;
import com.minipg.api.service.AfterProcessor;
import com.minipg.common.domain.PayReq;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 카드 계열 승인 공통 — 결제창(AUTH)과 수기(KEYIN)는 검증·원장·뒷수습이 같고
 * 제휴사 요청 구성만 다르다. 그 차이는 어댑터가 payType을 보고 흡수한다.
 */
@Slf4j
public abstract class CardApprovalBase extends AbstractPayProcess {

    @Autowired
    protected PayReqSupport payReqSupport;

    @Autowired
    protected AfterProcessor afterProcessor;

    protected abstract String payType();

    @Override
    protected void beforeProcess(PayContext ctx) {
        PayReq req = payReqSupport.validateForPay(ctx.in("reqId"), ctx.amt());
        ctx.work("payReq", req);
        ctx.work("payType", payType());
    }

    @Override
    protected void doProcess(PayContext ctx) {
        PartnerResult r = partner("NICEPAY").approve(ctx);
        if (!r.success()) {
            payReqSupport.mark(ctx.in("reqId"), PayReq.ST_FAILED, null);
            throw new FlowStop(r.code(), r.msg());
        }
        ctx.work("tid", r.tid());
        ctx.work("maskedCard", r.maskedCard());
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        PayReq req = ctx.work("payReq");
        trMstrMapper.insert(approvalRow(
                ctx.workStr("tid"),
                payReqSupport.resolveMcht(req, ctx.in("mchtId")),
                "CARD", ctx.amt(), payType(), "NICEPAY",
                ctx.in("orderId"), ctx.in("goodsNm"), ctx.workStr("maskedCard"), null));
        payReqSupport.mark(ctx.in("reqId"), PayReq.ST_APPROVED, ctx.workStr("tid"));
    }

    @Override
    protected Map<String, Object> recover(PayContext ctx, Exception cause) {
        log.error("승인 후 원장 반영 실패 → 망취소: tid={}", ctx.workStr("tid"), cause);
        PartnerResult nc = partner("NICEPAY").netCancel(ctx);
        if (nc.success()) {
            return Map.of("rsltCd", "9995", "rsltMsg", "내부 오류로 승인 원복(망취소) 완료 — 재시도 필요");
        }
        log.error("망취소 실패 — 수동 정리 필요: tid={}, {} {}", ctx.workStr("tid"), nc.code(), nc.msg());
        return Map.of("rsltCd", "9994", "rsltMsg", "내부 오류 + 망취소 실패 — 관리자 확인 필요: " + ctx.workStr("tid"));
    }

    @Override
    protected void postCommit(PayContext ctx) {
        afterProcessor.notifyApproved(ctx.in("reqId"), ctx.workStr("tid"), ctx.amt());
        ctx.ok(Map.of("tid", ctx.workStr("tid"), "amt", ctx.amt(), "payMethod", "CARD"));
    }
}
