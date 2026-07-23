package com.minipg.api.partner;

import com.minipg.api.flow.PayContext;
import com.minipg.api.pg.DanalpayClient;
import com.minipg.api.pg.DanalpayClient.DanalResult;
import com.minipg.common.domain.TrMstr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 다날 휴대폰결제 어댑터 (제휴사 3호).
 *
 * 본인인증(통신사 SMS)은 브라우저에서 끝난 뒤 거래번호로 도착하므로, 서버 어댑터는
 * 승인 확정(confirm)과 취소만 담당한다 — 카드 승인과 계약은 같고 프로토콜만 다르다.
 */
@Service("partner|DANAL")
@RequiredArgsConstructor
public class DanalAdapter implements PartnerAdapter {

    private final DanalpayClient danalpayClient;

    @Override
    public PartnerResult approve(PayContext ctx) {
        // 금액·주문번호는 전문이 아니라 PhoneConfirm이 PAY_REQ 조회로 work에 심어둔 값을 쓴다.
        DanalResult r = danalpayClient.confirm(ctx.workStr("authTid"), ctx.workAmt("amt"), ctx.workStr("orderId"));
        return r.success()
                ? PartnerResult.ok(r.tid(), null)
                : PartnerResult.fail(r.code(), r.msg());
    }

    @Override
    public PartnerResult cancel(PayContext ctx, TrMstr approval, String reason) {
        DanalResult r = danalpayClient.cancel(approval.getTid(), approval.getAmt());
        return r.success()
                ? PartnerResult.ok(approval.getTid(), null)
                : PartnerResult.fail(r.code(), r.msg());
    }

    @Override
    public PartnerResult netCancel(PayContext ctx) {
        DanalResult r = danalpayClient.cancel(ctx.workStr("tid"), ctx.workAmt("amt"));
        return r.success()
                ? PartnerResult.ok(ctx.workStr("tid"), null)
                : PartnerResult.fail(r.code(), r.msg());
    }
}
