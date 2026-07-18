package com.minipg.api.flow;

import java.util.HashMap;
import java.util.Map;

/**
 * 결제 처리 문맥 — 전문 한 건이 프로세스를 통과하는 동안의 상태.
 *
 * <ul>
 *   <li>in: 게이트웨이로 들어온 요청 전문 (읽기 전용으로 취급)</li>
 *   <li>work: 단계 사이에 넘기는 중간값 (제휴사 응답, 원거래, 채번 결과 등)</li>
 *   <li>result: 응답 전문</li>
 * </ul>
 */
public class PayContext {

    private final Map<String, Object> in;
    private final Map<String, Object> work = new HashMap<>();
    private final Map<String, Object> result = new HashMap<>();

    public PayContext(Map<String, Object> in) {
        this.in = in;
    }

    public String in(String key) {
        Object v = in.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public long amt() {
        String v = in("amt");
        return v == null || v.isBlank() ? 0L : Long.parseLong(v);
    }

    public void work(String key, Object value) {
        work.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T work(String key) {
        return (T) work.get(key);
    }

    public String workStr(String key) {
        Object v = work.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public long workAmt(String key) {
        Object v = work.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }

    public void ok(Map<String, Object> data) {
        result.putAll(data);
        result.put("rsltCd", "0000");
        result.put("rsltMsg", "성공");
    }

    public void putResult(String key, Object value) {
        result.put(key, value);
    }

    public Map<String, Object> result() {
        return result;
    }
}
