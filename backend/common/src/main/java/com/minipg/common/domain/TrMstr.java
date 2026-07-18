package com.minipg.common.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 거래 원장 (TR_MSTR).
 * INSERT-only — 취소는 UPDATE가 아니라 음수 금액의 별도 행(TX_ST_CD='2')으로 적재한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrMstr {

    public static final String ST_APPROVAL = "0";
    public static final String ST_CANCEL = "2";

    public static final String CH_LIVE = "LIVE";
    public static final String CH_SEED = "SEED";

    private Long trSeq;
    private String tid;
    private String orgTid;
    private String mchtId;
    private String pmCd;
    private String txStCd;
    private long amt;
    private LocalDate trDt;
    private LocalDateTime trTm;
    private String channel;
    private String payType;
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
                .pmCd(approval.getPmCd())
                .txStCd(ST_CANCEL)
                .amt(-approval.getAmt())
                .trDt(cancelTm.toLocalDate())
                .trTm(cancelTm)
                .channel(approval.getChannel())
                .payType(approval.getPayType())
                .orderId(approval.getOrderId())
                .goodsNm(approval.getGoodsNm())
                .cardNoMasked(approval.getCardNoMasked())
                .usrId(approval.getUsrId())
                .build();
    }
}
