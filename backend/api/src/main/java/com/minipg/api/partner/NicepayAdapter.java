package com.minipg.api.partner;

import com.minipg.api.flow.PayContext;
import com.minipg.api.pg.NicepayClient;
import com.minipg.api.pg.NicepayResult;
import com.minipg.common.domain.TrMstr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 나이스페이먼츠 어댑터 — 결제창(AUTH)/수기(KEYIN)/빌링(BILLING) 승인을
 * 결제유형에 따라 스스로 분기한다. 전문 스펙(서명·AES·성공코드)은 NicepayClient에 있다.
 */
@Service("partner|NICEPAY")
@RequiredArgsConstructor
public class NicepayAdapter implements PartnerAdapter {

    private final NicepayClient nicepayClient;

    @Override
    public PartnerResult approve(PayContext ctx) {
        String payType = ctx.workStr("payType");
        NicepayResult r = switch (payType) {
            case "AUTH" -> nicepayClient.approveAuth(
                    ctx.in("authToken"), ctx.in("tid"), ctx.in("nextAppUrl"), ctx.amt());
            case "KEYIN" -> nicepayClient.keyin(
                    ctx.in("orderId"), ctx.amt(), ctx.in("goodsNm"),
                    ctx.in("cardNo"), ctx.in("expYear"), ctx.in("expMonth"),
                    ctx.in("idNo"), ctx.in("cardPw"));
            case "BILLING" -> nicepayClient.billingApprove(
                    ctx.in("bid"), ctx.workStr("moid"), ctx.amt(), ctx.in("goodsNm"));
            default -> throw new IllegalStateException("지원하지 않는 결제유형: " + payType);
        };
        if (!r.success("3001")) {
            return PartnerResult.fail(r.resultCode(), r.resultMsg());
        }
        return PartnerResult.ok(r.tid(), mask(nvlOr(r.get("CardNo"), ctx.in("cardNo"))));
    }

    @Override
    public PartnerResult cancel(PayContext ctx, TrMstr approval, String reason) {
        boolean useAuthMid = "AUTH".equals(approval.getPayType());
        NicepayResult r = nicepayClient.cancel(
                approval.getTid(), approval.getOrderId(), approval.getAmt(), reason, useAuthMid);
        return r.success("2001")
                ? PartnerResult.ok(approval.getTid(), null)
                : PartnerResult.fail(r.resultCode(), r.resultMsg());
    }

    @Override
    public PartnerResult netCancel(PayContext ctx) {
        boolean useAuthMid = "AUTH".equals(ctx.workStr("payType"));
        NicepayResult r = nicepayClient.cancel(
                ctx.workStr("tid"), ctx.in("orderId"), ctx.amt(), "NET CANCEL", useAuthMid);
        return r.success("2001")
                ? PartnerResult.ok(ctx.workStr("tid"), null)
                : PartnerResult.fail(r.resultCode(), r.resultMsg());
    }

    @Override
    public PartnerResult issueBillKey(PayContext ctx) {
        NicepayResult r = nicepayClient.billingRegist(ctx.workStr("moid"),
                ctx.in("cardNo"), ctx.in("expYear"), ctx.in("expMonth"),
                ctx.in("idNo"), ctx.in("cardPw"));
        if (!r.success("F100")) {
            return PartnerResult.fail(r.resultCode(), r.resultMsg());
        }
        return new PartnerResult(true, "0000", "성공", r.get("BID"),
                mask(nvlOr(r.get("CardNo"), ctx.in("cardNo"))));
    }

    @Override
    public PartnerResult removeBillKey(String bid) {
        NicepayResult r = nicepayClient.billingRemove(bid, "BDEL" + System.currentTimeMillis());
        return r.success("F101")
                ? PartnerResult.ok(bid, null)
                : PartnerResult.fail(r.resultCode(), r.resultMsg());
    }

    private String mask(String cardNo) {
        String digits = cardNo == null ? "" : cardNo.replaceAll("\\D", "");
        if (digits.length() < 10) {
            return cardNo;
        }
        return digits.substring(0, 6) + "******" + digits.substring(digits.length() - 4);
    }

    private String nvlOr(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
