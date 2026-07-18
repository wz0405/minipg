package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.partner.PartnerResult;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.PpMapper;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 전액 취소 — 원거래 존재/중복취소 검증 후,
 * 제휴사 경유 거래(PARTNER_CD 보유)는 해당 어댑터로 취소하고
 * 자체 수단(가상계좌/선불)은 원장 상쇄·잔액 복원으로 끝낸다.
 */
@Slf4j
@Service("process|CANCEL")
public class PayCancel extends AbstractPayProcess {

    @Autowired
    private PpMapper ppMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        TrMstr approval = trMstrMapper.selectApproval(ctx.in("tid"));
        if (approval == null) {
            throw new FlowStop("1001", "원거래 없음: " + ctx.in("tid"));
        }
        if (trMstrMapper.countCancel(ctx.in("tid")) > 0) {
            throw new FlowStop("1002", "이미 취소된 거래: " + ctx.in("tid"));
        }
        ctx.work("approval", approval);
    }

    @Override
    protected void doProcess(PayContext ctx) {
        TrMstr approval = ctx.work("approval");
        if (!TrMstr.CH_LIVE.equals(approval.getChannel()) || approval.getPartnerCd() == null) {
            return;
        }
        PartnerResult r = partner(approval.getPartnerCd())
                .cancel(ctx, approval, ctx.in("reason") == null ? "고객 요청" : ctx.in("reason"));
        if (!r.success()) {
            throw new FlowStop(r.code(), r.msg());
        }
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        TrMstr approval = ctx.work("approval");
        trMstrMapper.insert(TrMstr.cancelOf(approval, LocalDateTime.now()));
        restorePrepaid(approval);
    }

    /** 선불 취소는 원거래 회원 기준으로 잔액을 복원한다 — 취소행과 같은 트랜잭션. */
    private void restorePrepaid(TrMstr approval) {
        String method = approval.getPayMethod();
        if (!"MONEY".equals(method) && !"POINT".equals(method)) {
            return;
        }
        if (approval.getUsrId() == null) {
            log.warn("선불 취소인데 회원 정보 없음 (시딩 거래로 추정): tid={}", approval.getTid());
            return;
        }
        ppMapper.addBalance(approval.getUsrId(), method, approval.getAmt());
        long blnc = ppMapper.selectBalance(approval.getUsrId(), method);
        ppMapper.insertHist(approval.getUsrId(), method, "CANCEL", approval.getAmt(), blnc, approval.getTid());
    }

    @Override
    protected void postCommit(PayContext ctx) {
        TrMstr approval = ctx.work("approval");
        ctx.ok(Map.of("tid", approval.getTid(), "amt", -approval.getAmt()));
    }
}
