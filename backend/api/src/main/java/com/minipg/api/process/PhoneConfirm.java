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
        String authReqKey = ctx.in("authReqKey");
        String authTid = ctx.in("authTid");
        PayReq req;

        if (authReqKey != null && !authReqKey.isBlank()) {
            // 자체 시뮬레이션 경로(PhoneAuthService) — 발급 세션과 authTid가 일치해야 한다.
            // reqId 없는 즉석결제도 허용해야 해서 기존처럼 전문의 amt와 대조한다.
            if (!phoneAuthService.isVerified(authReqKey, authTid, ctx.amt())) {
                throw new FlowStop("1507", "본인인증이 완료되지 않았습니다");
            }
            req = payReqSupport.validateForPay(ctx.in("reqId"), ctx.amt());
        } else {
            // 다날 실제 결제창 경로 — front는 reqId(PAY_REQ pk)와 authTid(다날 콜백 transactionId)만
            // 전문에 싣는다. 금액·가맹점·상품명은 인증요청 단계(danal/start)에서 미리 심어둔 PAY_REQ를
            // bld가 직접 조회해 확정한다 — front→bld 경계엔 결제를 특정할 값만, 실 데이터는 DB가 단일
            // 소스라는 ssolpay와 동일한 경계 설계다.
            if (authTid == null || authTid.isBlank()) {
                throw new FlowStop("1507", "본인인증이 완료되지 않았습니다");
            }
            req = payReqSupport.requireForPay(ctx.in("reqId"));
        }

        ctx.work("payReq", req);
        ctx.work("authTid", authTid);
        ctx.work("mchtId", payReqSupport.resolveMcht(req, ctx.in("mchtId")));
        ctx.work("amt", req != null ? req.getAmt() : ctx.amt());
        ctx.work("goodsNm", req != null ? req.getGoodsNm() : ctx.in("goodsNm"));
        ctx.work("orderId", req != null ? req.getReqId() : ctx.in("orderId"));
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
        trMstrMapper.insert(approvalRow(
                ctx.workStr("tid"), ctx.workStr("mchtId"),
                "PHONE", ctx.workAmt("amt"), "MOBILE", "DANAL",
                ctx.workStr("orderId"), ctx.workStr("goodsNm"), null, null));
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
        afterProcessor.notifyApproved(ctx.in("reqId"), ctx.workStr("tid"), ctx.workAmt("amt"));
        ctx.ok(Map.of("tid", ctx.workStr("tid"), "amt", ctx.workAmt("amt"), "payMethod", "PHONE"));
    }
}
