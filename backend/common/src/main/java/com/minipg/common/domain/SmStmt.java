package com.minipg.common.domain;

import java.time.LocalDate;
import lombok.Data;

/** 일 정산 집계 (SM_STMT). 가맹점 × 정산일 단위, 지급예정일 포함. */
@Data
public class SmStmt {
    private LocalDate settleDt;
    private String mchtId;
    private String pmCd;
    private int trxCnt;
    private long trxAmt;
    private long costFee;
    private long salesFee;
    private long feeVat;
    private long marginAmt;
    private long payoutAmt;
    private LocalDate payoutDt;
    private String stmtStCd;
}
