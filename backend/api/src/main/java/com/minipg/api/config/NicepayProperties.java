package com.minipg.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 나이스페이먼츠 구 API(webapi) 연동 설정. 키는 환경변수로만 주입한다 (커밋 금지).
 *
 * <ul>
 *   <li>auth: 결제창 인증결제 + 가상계좌 (테스트 MID 예: nicepay00m)</li>
 *   <li>keyin: 수기(카유생비) + 빌링 (테스트 MID 예: nictest03m)</li>
 * </ul>
 *
 * MID가 비어 있으면 해당 경로는 스텁 모드 — 키 없이도 전체 파이프라인이 동작한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "nicepay")
public class NicepayProperties {
    private String webBase = "https://web.nicepay.co.kr";
    private String apiBase = "https://webapi.nicepay.co.kr";
    private String authMid = "";
    private String authKey = "";
    private String keyinMid = "";
    private String keyinKey = "";
}
