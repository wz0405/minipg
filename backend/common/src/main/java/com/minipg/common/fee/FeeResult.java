package com.minipg.common.fee;

/**
 * 건별 수수료 계산 결과.
 *
 * @param costFee   원가 수수료 (PG -> 당사)
 * @param salesFee  판가 수수료 (당사 -> 가맹점)
 * @param feeVat    판가 수수료 부가세
 * @param marginAmt 판가 - 원가
 * @param payoutAmt 가맹점 지급액 = 거래액 - 판가수수료 - 부가세
 */
public record FeeResult(long costFee, long salesFee, long feeVat, long marginAmt, long payoutAmt) {
}
