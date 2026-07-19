package com.minipg.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 다날 ONE API(휴대폰 소액결제) 설정. Secret Key는 환경변수로만 주입한다 (커밋 금지).
 *
 * CPID 기본값은 다날이 공개한 휴대폰결제 테스트 상점 코드(A010002002)다.
 * secretKey가 비어 있으면 스텁 모드 — 본인인증창·서버 승인 없이 데모가 돌아간다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "danalpay")
public class DanalpayProperties {
    private String apiBase = "https://one-api.danalpay.com";
    private String cpid = "A010002002";
    private String secretKey = "";
}
