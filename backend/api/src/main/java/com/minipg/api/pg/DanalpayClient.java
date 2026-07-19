package com.minipg.api.pg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipg.api.config.DanalpayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 다날 ONE API(휴대폰 소액결제) 클라이언트.
 *
 * 휴대폰결제는 카드와 흐름이 다르다 — 브라우저에서 본인인증(통신사 SMS)을 먼저 거치고,
 * 그 결과로 받은 거래번호를 서버가 승인 확정(confirm)한다. 취소는 거래번호로 되돌린다.
 *
 * <ul>
 *   <li>승인 확정: POST /payments/confirm — 성공 code "SUCCESS"</li>
 *   <li>취소: POST /payments/cancel — 성공 code "SUCCESS"</li>
 * </ul>
 *
 * 인증은 Secret Key 뒤에 콜론을 붙여 Base64로 감싼 Basic 인증. Secret Key 미설정 시 스텁.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DanalpayClient {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final DanalpayProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public boolean stubMode() {
        return props.getSecretKey() == null || props.getSecretKey().isBlank();
    }

    public String cpid() {
        return props.getCpid();
    }

    /** 승인 확정 — 본인인증에서 받은 거래번호로 결제를 확정한다. */
    public DanalResult confirm(String transactionId, long amount, String orderId) {
        if (stubMode()) {
            return DanalResult.ok(stubTid());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", "MOBILE");
        body.put("transactionId", transactionId);
        body.put("merchantId", props.getCpid());
        body.put("amount", String.valueOf(amount));
        body.put("orderId", orderId);
        return post("/payments/confirm", body, transactionId);
    }

    /** 전액 취소. */
    public DanalResult cancel(String transactionId, long amount) {
        if (stubMode()) {
            return DanalResult.ok(transactionId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", "MOBILE");
        body.put("transactionId", transactionId);
        body.put("merchantId", props.getCpid());
        body.put("amount", String.valueOf(amount));
        body.put("cancelType", "C");
        return post("/payments/cancel", body, transactionId);
    }

    private DanalResult post(String path, Map<String, Object> body, String tid) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((props.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getApiBase() + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> parsed = objectMapper.readValue(res.body(), new TypeReference<>() {});
            String code = String.valueOf(parsed.getOrDefault("code", "FAIL"));
            log.info("danalpay {} -> {}", path, code);
            if (!"SUCCESS".equals(code)) {
                return DanalResult.fail(code, String.valueOf(parsed.getOrDefault("message", "다날 오류")));
            }
            return DanalResult.ok(String.valueOf(parsed.getOrDefault("transactionId", tid)));
        } catch (Exception e) {
            log.error("danalpay 통신 실패: {}", path, e);
            return DanalResult.fail("9997", "다날 통신 실패: " + e.getMessage());
        }
    }

    /** 스텁 본인인증 거래번호 — 실모드에서는 본인인증창이 발급한다. */
    public String stubTid() {
        return "DNAL" + LocalDateTime.now().format(TS) + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    /** 다날 응답 요약. */
    public record DanalResult(boolean success, String code, String msg, String tid) {
        static DanalResult ok(String tid) {
            return new DanalResult(true, "SUCCESS", "성공", tid);
        }

        static DanalResult fail(String code, String msg) {
            return new DanalResult(false, code, msg, null);
        }
    }
}
