package com.minipg.api.pg;

import java.util.List;
import java.util.Map;

/**
 * 나이스페이 응답 요약. 성공 코드는 API별로 다르다:
 * 카드승인/빌링승인 3001, 가상계좌 채번 4100, 취소 2001, 빌키발급 F100, 빌키삭제 F101.
 */
public record NicepayResult(String resultCode, String resultMsg, String tid, Map<String, String> raw) {

    public boolean success(String... okCodes) {
        return List.of(okCodes).contains(resultCode);
    }

    public String get(String key) {
        return raw == null ? null : raw.get(key);
    }
}
