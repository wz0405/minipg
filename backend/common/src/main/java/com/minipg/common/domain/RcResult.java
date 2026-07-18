package com.minipg.common.domain;

import java.time.LocalDate;
import lombok.Data;

/** 거래대사 불일치 결과 (RC_RESULT). */
@Data
public class RcResult {
    private Long resultSeq;
    private LocalDate reconDt;
    private String tid;
    private String txStCd;
    private String diffType;
    private Long usAmt;
    private Long pgAmt;
}
