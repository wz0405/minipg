package com.minipg.common.domain;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 결제요청 (MB_PAY_REQ). 가맹점 주문과 1:1로 사전등록되며,
 * 승인 시 요청 금액과 대조해 금액 변조를 차단한다.
 * 원장과 달리 상태 테이블이므로 UPDATE를 허용한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayReq {

    public static final String ST_REQUESTED = "0";
    public static final String ST_APPROVED = "1";
    public static final String ST_FAILED = "2";

    private String reqId;
    private String mchtId;
    private long amt;
    private String goodsNm;
    private String returnUrl;
    private String notifyUrl;
    private String reqStCd;
    private String tid;
    private LocalDateTime regDt;
    private LocalDateTime updDt;
}
