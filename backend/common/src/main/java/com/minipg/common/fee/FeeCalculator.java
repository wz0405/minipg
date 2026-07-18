package com.minipg.common.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 원가/판가 수수료 산식.
 *
 * <pre>
 * 수수료      = floor(|거래액| × 요율% / 100)   — 원단위 절사
 * 수수료 VAT  = floor(|판가수수료| × 10%)
 * 마진        = 판가수수료 - 원가수수료
 * 지급액      = 거래액 - 판가수수료 - VAT
 * </pre>
 *
 * 취소행(음수 금액)은 절대값으로 계산한 뒤 부호를 붙인다.
 * 음수에 절사를 직접 적용하면 원거래와 1원이 어긋나 승인·취소 합이 0이 되지 않기 때문.
 */
@Component
public class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal VAT_RATE = new BigDecimal("0.1");

    public FeeResult calculate(long amt, BigDecimal costRatePct, BigDecimal salesRatePct) {
        long abs = Math.abs(amt);
        long sign = amt < 0 ? -1 : 1;

        long costFee = feeOf(abs, costRatePct) * sign;
        long salesFee = feeOf(abs, salesRatePct) * sign;
        long feeVat = BigDecimal.valueOf(Math.abs(salesFee))
                .multiply(VAT_RATE)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact() * sign;

        long marginAmt = salesFee - costFee;
        long payoutAmt = amt - salesFee - feeVat;
        return new FeeResult(costFee, salesFee, feeVat, marginAmt, payoutAmt);
    }

    private long feeOf(long absAmt, BigDecimal ratePct) {
        return BigDecimal.valueOf(absAmt)
                .multiply(ratePct)
                .divide(HUNDRED)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
    }
}
