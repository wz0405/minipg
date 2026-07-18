package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.common.mapper.PpMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 선불 충전 — 잔액 증가와 이력이 한 트랜잭션. */
@Service("process|PP_CHARGE")
public class PrepaidCharge extends AbstractPayProcess {

    @Autowired
    private PpMapper ppMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        if (ctx.amt() <= 0) {
            throw new FlowStop("1100", "충전금액은 양수여야 합니다");
        }
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        String usrId = ctx.in("usrId");
        String ppType = ctx.in("ppType");
        if (ppMapper.addBalance(usrId, ppType, ctx.amt()) == 0) {
            throw new FlowStop("1101", "선불 매체 없음: " + usrId + "/" + ppType);
        }
        long blnc = ppMapper.selectBalance(usrId, ppType);
        ppMapper.insertHist(usrId, ppType, "CHARGE", ctx.amt(), blnc, null);
        ctx.work("blnc", blnc);
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("blnc", ctx.workAmt("blnc"), "amt", ctx.amt()));
    }
}
