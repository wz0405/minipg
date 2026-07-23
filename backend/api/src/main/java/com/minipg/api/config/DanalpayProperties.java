package com.minipg.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 다날 ONE API(휴대폰 소액결제) 설정. Secret Key는 환경변수로만 주입한다 (커밋 금지).
 *
 * cpid·clientKey 기본값은 다날 개발자센터 샌드박스(developers.danalpay.com/sandbox)가
 * 문서에 공개해 둔 테스트용 값이다 — 계약 없이 결제창(SDK)까지는 실제로 뜬다.
 * (구 테크사이트 V1 계정 A010002002/CPPWD는 별개 프로토콜이라 이 클라이언트와 호환되지 않는다.)
 * secretKey는 계약 완료 후에만 발급되며, 비어 있으면 서버 승인(confirm)만 스텁 — 결제창 인증 자체는 실제로 동작한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "danalpay")
public class DanalpayProperties {
    private String apiBase = "https://one-api.danalpay.com";
    private String cpid = "A010084434";
    private String clientKey = "CL_TEST_I4d8FWYSSKl-42F7y3o9g_7iexSCyHbL8qthpZxPnpY=";
    private String secretKey = "";
}
