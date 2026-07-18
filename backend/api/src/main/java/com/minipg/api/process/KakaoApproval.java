package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.flow.PayReqSupport;
import com.minipg.api.partner.PartnerResult;
import com.minipg.api.service.AfterProcessor;
import com.minipg.common.domain.PayReq;
import com.minipg.common.mapper.PayReqMapper;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 카카오페이 직연동 승인 — 결제창 복귀(pg_token) 후 최종 승인.
 * ready 단계에서 결제요청에 박아 둔 카카오 tid로 승인하고, 정산은 카드로 흡수한다.
 */
@Slf4j
@Service("process|KAKAO_APPROVE")
public class KakaoApproval extends AbstractPayProcess {

    @Autowired
    private PayReqMapper payReqMapper;

    @Autowired
    private PayReqSupport payReqSupport;

    @Autowired
    private AfterProcessor afterProcessor;

    @Override
    protected void beforeProcess(PayContext ctx) {
        PayReq req = payReqMapper.selectById(ctx.in("reqId"));
        if (req == null) {
            throw new FlowStop("1003", "결제요청 없음: " + ctx.in("reqId"));
        }
        if (PayReq.ST_APPROVED.equals(req.getReqStCd())) {
            throw new FlowStop("1005", "이미 승인된 결제요청: " + ctx.in("reqId"));
        }
        ctx.work("payReq", req);
        ctx.work("tid", req.getTid());
        ctx.work("netCancelAmt", req.getAmt());
    }

    @Override
    protected void doProcess(PayContext ctx) {
        PartnerResult r = partner("KAKAOPAY").approve(ctx);
        if (!r.success()) {
            payReqSupport.mark(ctx.in("reqId"), PayReq.ST_FAILED, ctx.workStr("tid"));
            throw new FlowStop(r.code(), r.msg());
        }
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        PayReq req = ctx.work("payReq");
        trMstrMapper.insert(approvalRow(
                req.getTid(), req.getMchtId(), "CARD", req.getAmt(),
                "KAKAO", "KAKAOPAY", req.getReqId(), req.getGoodsNm(), null, null));
        payReqSupport.mark(req.getReqId(), PayReq.ST_APPROVED, req.getTid());
    }

    @Override
    protected Map<String, Object> recover(PayContext ctx, Exception cause) {
        log.error("카카오 승인 후 원장 반영 실패 → 취소 원복: tid={}", ctx.workStr("tid"), cause);
        partner("KAKAOPAY").netCancel(ctx);
        return Map.of("rsltCd", "9995", "rsltMsg", "내부 오류로 승인 원복 완료 — 재시도 필요");
    }

    @Override
    protected void postCommit(PayContext ctx) {
        PayReq req = ctx.work("payReq");
        afterProcessor.notifyApproved(req.getReqId(), req.getTid(), req.getAmt());
        ctx.ok(Map.of("tid", req.getTid(), "amt", req.getAmt(), "payMethod", "CARD"));
    }
}
