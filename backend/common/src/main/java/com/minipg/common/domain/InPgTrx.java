package com.minipg.common.domain;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** PG 거래내역 수신 (IN_PG_TRX). 거래대사의 PG측 소스. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InPgTrx {
    private Long srcSeq;
    private LocalDate reconDt;
    private String tid;
    private String txStCd;
    private long amt;
    private String srcNm;
}
