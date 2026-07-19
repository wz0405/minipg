package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.flow.PayReqSupport;
import com.minipg.api.partner.PartnerResult;
import com.minipg.api.service.AfterProcessor;
import com.minipg.api.service.PhoneAuthService;
import com.minipg.common.domain.PayReq;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 휴대폰 소액결제 승인 확정 — 본인인증(통신사 SMS)이 끝난 뒤 거래번호로 결제를 확정한다.
 *
 * 카드와 골격은 같고, do 단계에서 다날 어댑터의 승인 확정을 부른다는 점만 다르다.
 * 정산은 휴대폰결제 수단으로 집계된다.
 */
@Slf4j
@Service("process|PHONE_CONFIRM")
public class PhoneConfirm extends AbstractPayProcess {

    @Autowired
    private PayReqSupport payReqSupport;

    @Autowired
    private AfterProcessor afterProcessor;

    @Autowired
    private PhoneAuthService phoneAuthService;

    @Override
    protected void beforeProcess(PayContext ctx) {
        // 본인인증을 통과해 발급된 거래번호가 아니면 결제 자체를 거부한다.
        if (!phoneAuthService.isVerified(ctx.in("authReqKey"), ctx.in("authTid"), ctx.amt())) {
            throw new FlowStop("1507", "본인인증이 완료되지 않았습니다");
        }
        PayReq req = payReqSupport.validateForPay(ctx.in("reqId"), ctx.amt());
        ctx.work("payReq", req);
        ctx.work("authTid", ctx.in("authTid"));   // 본인인증에서 발급된 거래번호
    }

    @Override
    protected void doProcess(PayContext ctx) {
        PartnerResult r = partner("DANAL").approve(ctx);
        if (!r.success()) {
            payReqSupport.mark(ctx.in("reqId"), PayReq.ST_FAILED, null);
            throw new FlowStop(r.code(), r.msg());
        }
        ctx.work("tid", r.tid());
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        PayReq req = ctx.work("payReq");
        trMstrMapper.insert(approvalRow(
                ctx.workStr("tid"),
                payReqSupport.resolveMcht(req, ctx.in("mchtId")),
                "PHONE", ctx.amt(), "MOBILE", "DANAL",
                ctx.in("orderId"), ctx.in("goodsNm"), null, null));
        payReqSupport.mark(ctx.in("reqId"), PayReq.ST_APPROVED, ctx.workStr("tid"));
    }

    @Override
    protected Map<String, Object> recover(PayContext ctx, Exception cause) {
        log.error("휴대폰결제 승인 후 원장 반영 실패 → 망취소: tid={}", ctx.workStr("tid"), cause);
        PartnerResult nc = partner("DANAL").netCancel(ctx);
        return Map.of("rsltCd", nc.success() ? "9995" : "9994",
                "rsltMsg", nc.success() ? "내부 오류로 승인 원복(망취소) 완료" : "내부 오류 + 망취소 실패 — 관리자 확인 필요");
    }

    @Override
    protected void postCommit(PayContext ctx) {
        phoneAuthService.consume(ctx.in("authReqKey"));   // 인증 재사용 방지
        afterProcessor.notifyApproved(ctx.in("reqId"), ctx.workStr("tid"), ctx.amt());
        ctx.ok(Map.of("tid", ctx.workStr("tid"), "amt", ctx.amt(), "payMethod", "PHONE"));
    }
}
