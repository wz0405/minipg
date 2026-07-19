package com.minipg.api.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 휴대폰 본인인증(통신사 SMS) — 결제 전 반드시 거쳐야 하는 단계.
 *
 * 실서비스에서는 다날 인증창이 통신사로 SMS 승인번호를 발송하고 결과를 돌려준다.
 * 데모에서는 실제 SMS 발송·통신사 통신만 스텁이고, 그 외의 흐름과 검증은 동일하게 재현한다:
 * 번호·통신사 입력 → 승인번호 발급(3분 만료) → 승인번호 검증 → 성공 시에만 거래번호(authTid) 발급.
 *
 * 승인번호가 맞아야만 authTid가 나오고, 결제 승인은 그 authTid로만 가능하다 —
 * 즉 본인인증을 통과하지 못하면 결제 자체가 성립하지 않는다.
 */
@Slf4j
@Service
public class PhoneAuthService {

    private static final long EXPIRE_MIN = 3;

    /** 진행 중 인증 세션. 데모라 인메모리 — 실서비스는 다날 서버가 상태를 들고 있다. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String phoneNo, String carrier, String authCode, long amount,
            LocalDateTime issuedAt, boolean verified, String authTid) {
    }

    /** 인증 요청 — 승인번호를 발급한다. 데모라 코드를 응답에 담아 화면에 표시(실서비스=SMS). */
    public Map<String, Object> request(String phoneNo, String carrier, long amount) {
        if (phoneNo == null || !phoneNo.replaceAll("\\D", "").matches("01[016789]\\d{7,8}")) {
            return Map.of("rsltCd", "1501", "rsltMsg", "휴대폰번호 형식이 올바르지 않습니다");
        }
        if (carrier == null || carrier.isBlank()) {
            return Map.of("rsltCd", "1502", "rsltMsg", "통신사를 선택해 주세요");
        }
        String reqKey = "PA" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmssSSS"));
        String authCode = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        sessions.put(reqKey, new Session(phoneNo, carrier, authCode, amount, LocalDateTime.now(), false, null));
        log.info("휴대폰 본인인증 요청: {} {} (데모 승인번호={})", carrier, mask(phoneNo), authCode);
        // demoAuthCode는 데모 편의용 — 실서비스에서는 응답에 포함하지 않고 SMS로만 전달한다.
        return Map.of("rsltCd", "0000", "authReqKey", reqKey, "demoAuthCode", authCode,
                "rsltMsg", "인증번호를 발송했어요 (데모라 화면에 표시됩니다)");
    }

    /** 인증 확인 — 승인번호가 맞고 만료 전이면 거래번호(authTid)를 발급한다. */
    public Map<String, Object> confirm(String authReqKey, String authCode, long amount) {
        Session s = sessions.get(authReqKey);
        if (s == null) {
            return Map.of("rsltCd", "1503", "rsltMsg", "인증 요청이 없습니다. 다시 시도해 주세요");
        }
        if (ChronoUnit.MINUTES.between(s.issuedAt(), LocalDateTime.now()) >= EXPIRE_MIN) {
            sessions.remove(authReqKey);
            return Map.of("rsltCd", "1504", "rsltMsg", "인증 시간이 만료됐어요. 다시 요청해 주세요");
        }
        if (!s.authCode().equals(authCode == null ? "" : authCode.trim())) {
            return Map.of("rsltCd", "1505", "rsltMsg", "인증번호가 일치하지 않습니다");
        }
        if (s.amount() != amount) {
            return Map.of("rsltCd", "1506", "rsltMsg", "인증한 금액과 결제 금액이 다릅니다");
        }
        String authTid = "DNAL" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        sessions.put(authReqKey, new Session(s.phoneNo(), s.carrier(), s.authCode(), s.amount(),
                s.issuedAt(), true, authTid));
        return Map.of("rsltCd", "0000", "authTid", authTid, "phoneNo", mask(s.phoneNo()),
                "carrier", s.carrier(), "rsltMsg", "본인인증이 완료됐어요");
    }

    /**
     * 결제 승인 직전 검증 — authTid가 실제로 인증을 통과해 발급된 것인지,
     * 그 인증의 금액이 결제 금액과 맞는지 확인한다. 통과 못 하면 결제 불가.
     */
    public boolean isVerified(String authReqKey, String authTid, long amount) {
        Session s = sessions.get(authReqKey);
        return s != null && s.verified() && s.authTid() != null
                && s.authTid().equals(authTid) && s.amount() == amount;
    }

    /** 결제 완료·실패 후 세션 소진 — 재사용 방지. */
    public void consume(String authReqKey) {
        sessions.remove(authReqKey);
    }

    private String mask(String phoneNo) {
        String d = phoneNo.replaceAll("\\D", "");
        if (d.length() < 10) {
            return "***";
        }
        return d.substring(0, 3) + "****" + d.substring(d.length() - 4);
    }
}
