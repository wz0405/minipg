package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.partner.PartnerResult;
import com.minipg.common.mapper.BillKeyMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 빌키 회차 승인 — 유효한 빌키 확인 후 제휴사 승인, 카드 거래로 원장 적재. */
@Slf4j
@Service("process|BILL_APPROVE")
public class BillingApproval extends AbstractPayProcess {

    @Autowired
    private BillKeyMapper billKeyMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        Map<String, Object> billKey = billKeyMapper.selectActive(ctx.in("bid"));
        if (billKey == null) {
            throw new FlowStop("1301", "유효한 빌키 없음: " + ctx.in("bid"));
        }
        ctx.work("mchtId", billKey.get("MCHT_ID"));
        ctx.work("payType", "BILLING");
        ctx.work("moid", "BILL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    @Override
    protected void doProcess(PayContext ctx) {
        PartnerResult r = partner("NICEPAY").approve(ctx);
        if (!r.success()) {
            throw new FlowStop(r.code(), r.msg());
        }
        ctx.work("tid", r.tid());
        ctx.work("maskedCard", r.maskedCard());
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        trMstrMapper.insert(approvalRow(
                ctx.workStr("tid"), ctx.workStr("mchtId"), "CARD", ctx.amt(),
                "BILLING", "NICEPAY", ctx.workStr("moid"), ctx.in("goodsNm"),
                ctx.workStr("maskedCard"), null));
    }

    @Override
    protected Map<String, Object> recover(PayContext ctx, Exception cause) {
        log.error("빌링 승인 후 원장 반영 실패 → 망취소: tid={}", ctx.workStr("tid"), cause);
        PartnerResult nc = partner("NICEPAY").netCancel(ctx);
        return Map.of("rsltCd", nc.success() ? "9995" : "9994",
                "rsltMsg", nc.success() ? "내부 오류로 승인 원복(망취소) 완료" : "내부 오류 + 망취소 실패 — 관리자 확인 필요");
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("tid", ctx.workStr("tid"), "amt", ctx.amt(),
                "payMethod", "CARD", "moid", ctx.workStr("moid")));
    }
}
