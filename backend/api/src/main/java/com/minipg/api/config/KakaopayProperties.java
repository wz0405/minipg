package com.minipg.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오페이 직연동 설정. Admin 키는 환경변수로만 주입한다 (커밋 금지).
 * CID는 카카오페이가 공개한 단건결제 테스트 가맹점 코드(TC0ONETIME)가 기본 —
 * 실제 결제창·승인 흐름이 동작하지만 청구는 발생하지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kakaopay")
public class KakaopayProperties {
    private String apiBase = "https://kapi.kakao.com";
    private String adminKey = "";
    private String cid = "TC0ONETIME";
}
