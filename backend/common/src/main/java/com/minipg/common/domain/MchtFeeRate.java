package com.minipg.common.domain;

import java.math.BigDecimal;
import lombok.Data;

/** (가맹점 × 결제수단)별 적용 수수료율 — 정산 기준일 시점의 최신 원가/판가 정책. */
@Data
public class MchtFeeRate {
    private String mchtId;
    private String mchtNm;
    private int settleCycle;
    private String pmCd;
    private BigDecimal costRate;
    private BigDecimal salesRate;

    public String rateKey() {
        return mchtId + "|" + pmCd;
    }
}
