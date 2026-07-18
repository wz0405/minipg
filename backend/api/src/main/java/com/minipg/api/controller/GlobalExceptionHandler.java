package com.minipg.api.controller;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 전역 예외 → 결제 응답 규격(rsltCd/rsltMsg)으로 통일.
 * 데모 클라이언트가 HTTP 500 본문 대신 항상 같은 형태를 받도록 한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public Map<String, Object> business(IllegalStateException e) {
        log.warn("업무 오류: {}", e.getMessage());
        return Map.of("rsltCd", "9001", "rsltMsg", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Map<String, Object> unexpected(Exception e) {
        log.error("처리되지 않은 오류", e);
        return Map.of("rsltCd", "9000", "rsltMsg", "내부 오류: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " — " + e.getMessage()));
    }
}
