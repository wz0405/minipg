package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.partner.PartnerResult;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.PpMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 전액 취소 — 원거래 존재/중복취소 검증 후,
 * 제휴사 경유 거래(PARTNER_CD 보유)는 해당 어댑터로 취소하고
 * 자체 수단(가상계좌/선불)은 원장 상쇄·잔액 복원으로 끝낸다.
 *
 * 선불 복합결제는 여러 매체 sub-거래가 같은 원거래번호(OTID)로 묶여 있어,
 * 어느 sub-tid로 취소가 들어오든 그룹 전체를 일괄 취소한다.
 */
@Slf4j
@Service("process|CANCEL")
public class PayCancel extends AbstractPayProcess {

    @Autowired
    private PpMapper ppMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        String tid = ctx.in("tid");
        TrMstr approval = trMstrMapper.selectApproval(tid);
        if (approval == null) {
            // 선불 복합결제는 그룹ID(OTID)로 취소가 들어올 수 있다 — 그룹의 대표 sub-거래로 해석.
            List<TrMstr> group = trMstrMapper.selectApprovalsByOtid(tid);
            if (group.isEmpty()) {
                throw new FlowStop("1001", "원거래 없음: " + tid);
            }
            approval = group.get(0);
        }
        if (trMstrMapper.countCancel(approval.getTid()) > 0) {
            throw new FlowStop("1002", "이미 취소된 거래: " + tid);
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
        // 선불 복합결제(매체별 sub-거래)는 OTID 그룹 전체를 일괄 취소·복원한다.
        boolean prepaidGroup = ("MONEY".equals(approval.getPayMethod()) || "POINT".equals(approval.getPayMethod()))
                && !approval.getOrgTid().equals(approval.getTid());
        if (prepaidGroup) {
            List<TrMstr> group = trMstrMapper.selectApprovalsByOtid(approval.getOrgTid());
            for (TrMstr row : group) {
                if (trMstrMapper.countCancel(row.getTid()) > 0) {
                    continue;
                }
                trMstrMapper.insert(TrMstr.cancelOf(row, LocalDateTime.now()));
                restorePrepaid(row);
            }
            return;
        }
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
