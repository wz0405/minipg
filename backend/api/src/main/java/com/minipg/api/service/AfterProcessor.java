package com.minipg.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipg.common.domain.PayReq;
import com.minipg.common.mapper.PayReqMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 승인 후처리 — 결과통보·전표 발송류는 승인 응답을 붙잡으면 안 되므로 별도 스레드로 뺀다.
 *
 * 승인 트랜잭션은 원장 적재까지가 책임이고, 가맹점 서버통보(notifyUrl)나 매출전표 메일은
 * 실패해도 승인 자체를 되돌릴 일이 아니다 — 재통보로 복구할 영역.
 * 그래서 커밋 후 fire-and-forget으로 넘기고, 응답 지연·통보 실패가 결제 응답 시간에
 * 전이되지 않게 한다. 통보는 3회 재시도 후 로그로 남긴다 (재통보 배치는 확장 포인트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AfterProcessor {

    private final PayReqMapper payReqMapper;
    private final ObjectMapper objectMapper;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** 승인 완료 후 가맹점 서버통보. notifyUrl 미등록 주문은 조용히 스킵. */
    public void notifyApproved(String reqId, String tid, long amt) {
        if (reqId == null || reqId.isBlank()) {
            return;
        }
        worker.submit(() -> {
            PayReq req = payReqMapper.selectById(reqId);
            if (req == null || req.getNotifyUrl() == null || req.getNotifyUrl().isBlank()) {
                return;
            }
            String body;
            try {
                body = objectMapper.writeValueAsString(Map.of(
                        "reqId", reqId, "tid", tid, "amt", amt, "rsltCd", "0000"));
            } catch (Exception e) {
                log.error("통보 본문 생성 실패: reqId={}", reqId, e);
                return;
            }
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    HttpRequest post = HttpRequest.newBuilder(URI.create(req.getNotifyUrl()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> res = http.send(post, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        log.info("가맹점 통보 성공: reqId={}, {}회차", reqId, attempt);
                        return;
                    }
                    log.warn("가맹점 통보 응답 {}: reqId={}, {}회차", res.statusCode(), reqId, attempt);
                } catch (Exception e) {
                    log.warn("가맹점 통보 실패: reqId={}, {}회차, {}", reqId, attempt, e.getMessage());
                }
            }
            log.error("가맹점 통보 최종 실패 — 재통보 대상: reqId={}, url={}", reqId, req.getNotifyUrl());
        });
    }

    @PreDestroy
    public void shutdown() {
        worker.shutdown();
    }
}
