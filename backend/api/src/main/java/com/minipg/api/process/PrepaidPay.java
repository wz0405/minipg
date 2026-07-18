package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.common.mapper.PpMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 선불(머니/포인트) 결제 — 자체 원장 수단.
 * 잔액 차감은 조건부 UPDATE(잔액+Δ≥0)로 원자적으로 끝내며, 0행이면 잔액부족이다.
 * 차감·원장·이력이 한 트랜잭션이라 부분 반영이 없다.
 */
@Service("process|PP_PAY")
public class PrepaidPay extends AbstractPayProcess {

    @Autowired
    private PpMapper ppMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        if (ctx.amt() <= 0) {
            throw new FlowStop("1100", "결제금액은 양수여야 합니다");
        }
        String prefix = "MONEY".equals(ctx.in("ppType")) ? "PPM" : "PPP";
        ctx.work("tid", prefix + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        String usrId = ctx.in("usrId");
        String ppType = ctx.in("ppType");
        if (ppMapper.addBalance(usrId, ppType, -ctx.amt()) == 0) {
            throw new FlowStop("1102", "잔액 부족 또는 매체 없음: " + usrId + "/" + ppType);
        }
        trMstrMapper.insert(approvalRow(ctx.workStr("tid"), ctx.in("mchtId"), ppType, ctx.amt(),
                "PP", null, null, ctx.in("goodsNm"), null, usrId));
        long blnc = ppMapper.selectBalance(usrId, ppType);
        ppMapper.insertHist(usrId, ppType, "PAY", -ctx.amt(), blnc, ctx.workStr("tid"));
        ctx.work("blnc", blnc);
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("tid", ctx.workStr("tid"), "amt", ctx.amt(),
                "payMethod", ctx.in("ppType"), "blnc", ctx.workAmt("blnc")));
    }
}
