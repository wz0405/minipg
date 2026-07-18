package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.partner.PartnerResult;
import com.minipg.common.mapper.BillKeyMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 빌키 삭제(해지). */
@Service("process|BILL_KEY_REMOVE")
public class BillKeyRemove extends AbstractPayProcess {

    @Autowired
    private BillKeyMapper billKeyMapper;

    @Override
    protected void doProcess(PayContext ctx) {
        PartnerResult r = partner("NICEPAY").removeBillKey(ctx.in("bid"));
        if (!r.success()) {
            throw new FlowStop(r.code(), r.msg());
        }
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        billKeyMapper.deactivate(ctx.in("bid"));
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("bid", ctx.in("bid")));
    }
}
