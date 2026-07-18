package com.minipg.common.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 거래 원장 (TR_MSTR).
 * INSERT-only — 취소는 UPDATE가 아니라 음수 금액의 별도 행(TX_STATUS='2')으로 적재한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrMstr {

    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_CANCELED = "CANCELED";

    public static final String CH_LIVE = "LIVE";
    public static final String CH_SEED = "SEED";

    private Long trSeq;
    private String tid;
    private String orgTid;
    private String mchtId;
    private String payMethod;
    private String txStatus;
    private long amt;
    private LocalDate trDt;
    private LocalDateTime trTm;
    private String channel;
    private String payType;
    private String partnerCd;
    private String orderId;
    private String goodsNm;
    private String cardNoMasked;
    private String usrId;
    private LocalDateTime regDt;

    /** 원거래(승인행)로부터 전액 취소행을 만든다. 금액은 부호 반전, ORG_TID는 원거래 TID 유지. */
    public static TrMstr cancelOf(TrMstr approval, LocalDateTime cancelTm) {
        return TrMstr.builder()
                .tid(approval.getTid())
                .orgTid(approval.getTid())
                .mchtId(approval.getMchtId())
                .payMethod(approval.getPayMethod())
                .txStatus(STATUS_CANCELED)
                .amt(-approval.getAmt())
                .trDt(cancelTm.toLocalDate())
                .trTm(cancelTm)
                .channel(approval.getChannel())
                .payType(approval.getPayType())
                .partnerCd(approval.getPartnerCd())
                .orderId(approval.getOrderId())
                .goodsNm(approval.getGoodsNm())
                .cardNoMasked(approval.getCardNoMasked())
                .usrId(approval.getUsrId())
                .build();
    }
}
