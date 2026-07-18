package com.minipg.api.partner;

import com.minipg.api.flow.PayContext;
import com.minipg.api.pg.KakaopayClient;
import com.minipg.common.domain.TrMstr;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 카카오페이 직연동 어댑터. ready(결제준비)는 리다이렉트 URL이 필요한 프로토콜 준비 단계라
 * HTTP 계층에 있고, 승인/취소만 이 어댑터가 담당한다.
 */
@Service("partner|KAKAOPAY")
@RequiredArgsConstructor
public class KakaopayAdapter implements PartnerAdapter {

    private final KakaopayClient kakaopayClient;

    @Override
    public PartnerResult approve(PayContext ctx) {
        Map<String, Object> r = kakaopayClient.approve(
                ctx.workStr("tid"), ctx.in("reqId"), "minipg-demo", ctx.in("pgToken"));
        if (r.get("aid") == null) {
            return PartnerResult.fail("1401", "카카오페이 승인 실패: "
                    + r.getOrDefault("msg", r.getOrDefault("error_message", "")));
        }
        return PartnerResult.ok(ctx.workStr("tid"), null);
    }

    @Override
    public PartnerResult cancel(PayContext ctx, TrMstr approval, String reason) {
        Map<String, Object> r = kakaopayClient.cancel(approval.getTid(), approval.getAmt());
        if (r.get("aid") == null && r.get("status") == null) {
            return PartnerResult.fail("1402", "카카오페이 취소 실패: " + r.getOrDefault("msg", ""));
        }
        return PartnerResult.ok(approval.getTid(), null);
    }

    @Override
    public PartnerResult netCancel(PayContext ctx) {
        Map<String, Object> r = kakaopayClient.cancel(ctx.workStr("tid"), ctx.workAmt("netCancelAmt"));
        if (r.get("aid") == null && r.get("status") == null) {
            return PartnerResult.fail("1402", "카카오페이 망취소 실패: " + r.getOrDefault("msg", ""));
        }
        return PartnerResult.ok(ctx.workStr("tid"), null);
    }
}
