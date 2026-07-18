package com.minipg.common.domain;

import com.minipg.common.fee.FeeResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** 건별 정산 (SM_STMT_TID). 배치 시점에 적용한 요율과 계산 결과를 스냅샷으로 남긴다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmStmtTid {
    private Long stmtTidSeq;
    private LocalDate settleDt;
    private String mchtId;
    private String payMethod;
    private Long trSeq;
    private String tid;
    private String txStatus;
    private long amt;
    private BigDecimal costRate;
    private BigDecimal salesRate;
    private long costFee;
    private long salesFee;
    private long feeVat;
    private long marginAmt;
    private long payoutAmt;

    public static SmStmtTid of(LocalDate settleDt, TrMstr tx, MchtFeeRate rate, FeeResult fee) {
        return SmStmtTid.builder()
                .settleDt(settleDt)
                .mchtId(tx.getMchtId())
                .payMethod(tx.getPayMethod())
                .trSeq(tx.getTrSeq())
                .tid(tx.getTid())
                .txStatus(tx.getTxStatus())
                .amt(tx.getAmt())
                .costRate(rate.getCostRate())
                .salesRate(rate.getSalesRate())
                .costFee(fee.costFee())
                .salesFee(fee.salesFee())
                .feeVat(fee.feeVat())
                .marginAmt(fee.marginAmt())
                .payoutAmt(fee.payoutAmt())
                .build();
    }
}
