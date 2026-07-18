package com.minipg.api.partner;

/** 제휴사 통신 결과 — 성공 여부는 어댑터가 제휴사별 성공코드를 해석해 판정한다. */
public record PartnerResult(boolean success, String code, String msg, String tid, String maskedCard) {

    public static PartnerResult ok(String tid, String maskedCard) {
        return new PartnerResult(true, "0000", "성공", tid, maskedCard);
    }

    public static PartnerResult fail(String code, String msg) {
        return new PartnerResult(false, code, msg, null, null);
    }
}
