package com.minipg.api.pg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipg.api.config.KakaopayProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 카카오페이 단건결제 직연동 (제휴사 어댑터 2호).
 *
 * PG 경유 간편결제와 달리 제휴사 API를 직접 탄다:
 * ready(결제준비) → 사용자 결제창 리다이렉트 → approval_url?pg_token 복귀 → approve(승인).
 * 승인 실패·내부 적재 실패 시 cancel로 되돌린다.
 * 인증은 KakaoAK Admin 키, 본문은 form-encoded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaopayClient {

    private final KakaopayProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public boolean enabled() {
        return props.getAdminKey() != null && !props.getAdminKey().isBlank();
    }

    /** 결제준비 — 성공 시 tid + 결제창 리다이렉트 URL(pc/mobile). */
    public Map<String, Object> ready(String orderId, String userId, String itemName, long amount,
            String approvalUrl, String cancelUrl, String failUrl) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("cid", props.getCid());
        form.put("partner_order_id", orderId);
        form.put("partner_user_id", userId);
        form.put("item_name", itemName);
        form.put("quantity", "1");
        form.put("total_amount", String.valueOf(amount));
        form.put("tax_free_amount", "0");
        form.put("approval_url", approvalUrl);
        form.put("cancel_url", cancelUrl);
        form.put("fail_url", failUrl);
        return post("/v1/payment/ready", form);
    }

    /** 결제승인 — pg_token으로 최종 승인. 성공 응답에 aid가 있다. */
    public Map<String, Object> approve(String tid, String orderId, String userId, String pgToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("cid", props.getCid());
        form.put("tid", tid);
        form.put("partner_order_id", orderId);
        form.put("partner_user_id", userId);
        form.put("pg_token", pgToken);
        return post("/v1/payment/approve", form);
    }

    /** 전액 취소. */
    public Map<String, Object> cancel(String tid, long amount) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("cid", props.getCid());
        form.put("tid", tid);
        form.put("cancel_amount", String.valueOf(amount));
        form.put("cancel_tax_free_amount", "0");
        return post("/v1/payment/cancel", form);
    }

    private Map<String, Object> post(String path, Map<String, String> form) {
        try {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(e.getKey()).append('=')
                        .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getApiBase() + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "KakaoAK " + props.getAdminKey())
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> parsed = objectMapper.readValue(res.body(), new TypeReference<>() {});
            if (res.statusCode() != 200) {
                log.warn("kakaopay {} -> {} {}", path, res.statusCode(), res.body());
            } else {
                log.info("kakaopay {} -> OK", path);
            }
            return parsed;
        } catch (Exception e) {
            log.error("kakaopay 통신 실패: {}", path, e);
            return Map.of("code", -9999, "msg", "카카오페이 통신 실패: " + e.getMessage());
        }
    }
}
