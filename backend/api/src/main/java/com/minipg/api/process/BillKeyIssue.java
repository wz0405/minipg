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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 빌키 발급 — 0원 인증(청구 없음). 카드 원본은 제휴사로 넘긴 뒤 버리고 빌키·마스킹본만 보관한다. */
@Service("process|BILL_KEY_ISSUE")
public class BillKeyIssue extends AbstractPayProcess {

    @Autowired
    private BillKeyMapper billKeyMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        ctx.work("moid", "BREG" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    @Override
    protected void doProcess(PayContext ctx) {
        PartnerResult r = partner("NICEPAY").issueBillKey(ctx);
        if (!r.success()) {
            throw new FlowStop(r.code(), r.msg());
        }
        ctx.work("bid", r.tid());
        ctx.work("maskedCard", r.maskedCard());
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        billKeyMapper.insert(ctx.workStr("bid"), ctx.in("mchtId"),
                ctx.workStr("maskedCard"), ctx.workStr("moid"));
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("bid", ctx.workStr("bid")));
    }
}
