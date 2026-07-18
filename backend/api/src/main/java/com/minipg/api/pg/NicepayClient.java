package com.minipg.api.pg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipg.api.config.NicepayProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 나이스페이먼츠 구 API(webapi JSP) 클라이언트.
 *
 * <ul>
 *   <li>결제창 승인: 인증응답의 NextAppURL로 POST — SignData=SHA256(AuthToken+MID+Amt+EdiDate+Key)</li>
 *   <li>수기(키인): /webapi/card_keyin.jsp — EncData=AES128/ECB(CardNo&amp;CardExpire&amp;BuyerAuthNum&amp;CardPwd)</li>
 *   <li>취소: /webapi/cancel_process.jsp — SignData=SHA256(MID+CancelAmt+EdiDate+Key)</li>
 *   <li>빌키발급: /webapi/billing/billing_regist.jsp — EncData=AES(CardNo&amp;ExpYear&amp;ExpMonth&amp;IDNo&amp;CardPw), 성공 F100</li>
 *   <li>빌링승인: /webapi/billing/billing_approve.jsp — SignData=SHA256(MID+EdiDate+Moid+Amt+BID+Key), 성공 3001</li>
 * </ul>
 *
 * AES 키는 상점키 앞 16자리, EncData는 HEX 대문자. 요청 본문은 euc-kr 폼 인코딩, 응답은 utf-8 JSON.
 * MID 미설정 경로는 스텁 모드로 성공 응답을 합성한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NicepayClient {

    private static final Charset EUC_KR = Charset.forName("euc-kr");
    private static final DateTimeFormatter EDI_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TID_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final NicepayProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public boolean authStub() {
        return props.getAuthMid() == null || props.getAuthMid().isBlank();
    }

    public boolean keyinStub() {
        return props.getKeyinMid() == null || props.getKeyinMid().isBlank();
    }

    public String authMid() {
        return props.getAuthMid();
    }

    /** 결제창 요청용 서버 서명 — SignData=SHA256(EdiDate+MID+Amt+Key). 상점키는 절대 클라이언트로 내리지 않는다. */
    public Map<String, String> checkoutSign(long amt) {
        String ediDate = LocalDateTime.now().format(EDI_FMT);
        String sign = sha256(ediDate + props.getAuthMid() + amt + props.getAuthKey());
        return Map.of("mid", props.getAuthMid(), "ediDate", ediDate, "signData", sign,
                "webBase", props.getWebBase());
    }

    /** 결제창 인증 완료 건 승인 — NextAppURL로 POST. 카드 성공 3001, 가상계좌 채번 성공 4100. */
    public NicepayResult approveAuth(String authToken, String txTid, String nextAppUrl, long amt) {
        if (authStub()) {
            return stubApproval("STUBAUTH");
        }
        String ediDate = LocalDateTime.now().format(EDI_FMT);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("TID", txTid);
        form.put("AuthToken", authToken);
        form.put("MID", props.getAuthMid());
        form.put("Amt", String.valueOf(amt));
        form.put("EdiDate", ediDate);
        form.put("SignData", sha256(authToken + props.getAuthMid() + amt + ediDate + props.getAuthKey()));
        form.put("CharSet", "utf-8");
        form.put("EdiType", "JSON");
        return post(nextAppUrl, form);
    }

    /** 수기(키인) 승인 — 카드번호/유효기간(YYMM)/생년월일/비밀번호 앞 2자리. 성공 3001. */
    public NicepayResult keyin(String moid, long amt, String goodsName,
            String cardNo, String expYear, String expMonth, String idNo, String cardPw) {
        if (keyinStub()) {
            return stubApproval("STUBKEYIN");
        }
        String ediDate = LocalDateTime.now().format(EDI_FMT);
        String tid = makeTid(props.getKeyinMid(), "01", "01");
        String plain = "CardNo=" + cardNo + "&CardExpire=" + expYear + expMonth
                + "&BuyerAuthNum=" + idNo + "&CardPwd=" + cardPw;

        Map<String, String> form = new LinkedHashMap<>();
        form.put("TID", tid);
        form.put("MID", props.getKeyinMid());
        form.put("Moid", moid);
        form.put("Amt", String.valueOf(amt));
        form.put("GoodsName", goodsName);
        form.put("CardInterest", "0");
        form.put("CardQuota", "00");
        form.put("CardPoint", "0");
        form.put("EdiDate", ediDate);
        form.put("SignData", sha256(props.getKeyinMid() + amt + ediDate + moid + props.getKeyinKey()));
        form.put("EncData", aesHex(plain, props.getKeyinKey()));
        form.put("CharSet", "utf-8");
        form.put("EdiType", "JSON");
        return post(props.getApiBase() + "/webapi/card_keyin.jsp", form);
    }

    /** 전액 취소 — 성공 2001. useAuthMid: 원거래가 결제창(AUTH/VBANK) 경유였는지에 따라 MID 선택. */
    public NicepayResult cancel(String pgTid, String moid, long amt, String reason, boolean useAuthMid) {
        if (useAuthMid ? authStub() : keyinStub()) {
            return new NicepayResult("2001", "스텁 취소", pgTid, Map.of());
        }
        String mid = useAuthMid ? props.getAuthMid() : props.getKeyinMid();
        String key = useAuthMid ? props.getAuthKey() : props.getKeyinKey();
        String ediDate = LocalDateTime.now().format(EDI_FMT);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("TID", pgTid);
        form.put("MID", mid);
        form.put("Moid", moid == null ? pgTid : moid);
        form.put("CancelAmt", String.valueOf(amt));
        form.put("CancelMsg", reason == null ? "cancel" : reason);
        form.put("PartialCancelCode", "0");
        form.put("EdiDate", ediDate);
        form.put("SignData", sha256(mid + amt + ediDate + key));
        form.put("CharSet", "utf-8");
        form.put("EdiType", "JSON");
        return post(props.getApiBase() + "/webapi/cancel_process.jsp", form);
    }

    /** 빌키 발급 — 성공 F100, 응답 BID. */
    public NicepayResult billingRegist(String moid, String cardNo, String expYear, String expMonth,
            String idNo, String cardPw) {
        if (keyinStub()) {
            String bid = "BIKYSTUB" + LocalDateTime.now().format(TID_FMT)
                    + ThreadLocalRandom.current().nextInt(1000, 9999);
            return new NicepayResult("F100", "스텁 빌키발급", null,
                    Map.of("BID", bid, "CardNo", mask(cardNo)));
        }
        String ediDate = LocalDateTime.now().format(EDI_FMT);
        String plain = "CardNo=" + cardNo + "&ExpYear=" + expYear + "&ExpMonth=" + expMonth
                + "&IDNo=" + idNo + "&CardPw=" + cardPw;

        Map<String, String> form = new LinkedHashMap<>();
        form.put("MID", props.getKeyinMid());
        form.put("EdiDate", ediDate);
        form.put("Moid", moid);
        form.put("EncData", aesHex(plain, props.getKeyinKey()));
        form.put("SignData", sha256(props.getKeyinMid() + ediDate + moid + props.getKeyinKey()));
        form.put("CharSet", "utf-8");
        form.put("EdiType", "JSON");
        return post(props.getApiBase() + "/webapi/billing/billing_regist.jsp", form);
    }

    /** 빌키 승인(정기결제 회차 결제) — 성공 3001. */
    public NicepayResult billingApprove(String bid, String moid, long amt, String goodsName) {
        if (keyinStub()) {
            return stubApproval("STUBBILL");
        }
        String ediDate = LocalDateTime.now().format(EDI_FMT);
        String tid = makeTid(props.getKeyinMid(), "01", "16");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("BID", bid);
        form.put("MID", props.getKeyinMid());
        form.put("TID", tid);
        form.put("EdiDate", ediDate);
        form.put("Moid", moid);
        form.put("Amt", String.valueOf(amt));
        form.put("GoodsName", goodsName);
        form.put("SignData", sha256(props.getKeyinMid() + ediDate + moid + amt + bid + props.getKeyinKey()));
        form.put("CardInterest", "0");
        form.put("CardQuota", "00");
        form.put("CharSet", "utf-8");
        form.put("EdiType", "JSON");
        return post(props.getApiBase() + "/webapi/billing/billing_approve.jsp", form);
    }

    /** 빌키 삭제 — 성공 F101. */
    public NicepayResult billingRemove(String bid, String moid) {
        if (keyinStub()) {
            return new NicepayResult("F101", "스텁 빌키삭제", null, Map.of());
        }
        String ediDate = LocalDateTime.now().format(EDI_FMT);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("BID", bid);
        form.put("MID", props.getKeyinMid());
        form.put("EdiDate", ediDate);
        form.put("Moid", moid);
        form.put("SignData", sha256(props.getKeyinMid() + ediDate + moid + bid + props.getKeyinKey()));
        form.put("CharSet", "utf-8");
        form.put("EdiType", "JSON");
        return post(props.getApiBase() + "/webapi/billing/billkey_remove.jsp", form);
    }

    private NicepayResult post(String url, Map<String, String> form) {
        try {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), EUC_KR));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.ISO_8859_1))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.error("nicepay http {}: {}", res.statusCode(), res.body());
                return new NicepayResult("9998", "PG HTTP " + res.statusCode(), null, Map.of());
            }
            Map<String, String> parsed = objectMapper.readValue(res.body(), new TypeReference<>() {});
            log.info("nicepay {} -> {} {}", url, parsed.get("ResultCode"), parsed.get("ResultMsg"));
            return new NicepayResult(parsed.get("ResultCode"), parsed.get("ResultMsg"), parsed.get("TID"), parsed);
        } catch (Exception e) {
            log.error("nicepay 통신 실패: {}", url, e);
            return new NicepayResult("9997", "PG 통신 실패: " + e.getMessage(), null, Map.of());
        }
    }

    /** TID = MID + svcCd + prdtCd + yyMMddHHmmss + 랜덤4자리. */
    private String makeTid(String mid, String svcCd, String prdtCd) {
        return mid + svcCd + prdtCd + LocalDateTime.now().format(TID_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private NicepayResult stubApproval(String prefix) {
        String tid = prefix + LocalDateTime.now().format(TID_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        String now = LocalDateTime.now().format(TID_FMT);
        return new NicepayResult("3001", "스텁 승인", tid,
                Map.of("AuthCode", "000000", "AuthDate", now, "CardNo", "552712******1234"));
    }

    private String sha256(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 실패", e);
        }
    }

    /** AES128/ECB/PKCS5, 키=상점키 앞 16자리, 결과 HEX 대문자. */
    private String aesHex(String plain, String merchantKey) {
        try {
            SecretKeySpec key = new SecretKeySpec(merchantKey.substring(0, 16).getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : enc) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("AES 암호화 실패", e);
        }
    }

    private String mask(String cardNo) {
        String digits = cardNo == null ? "" : cardNo.replaceAll("\\D", "");
        if (digits.length() < 10) {
            return "****";
        }
        return digits.substring(0, 6) + "******" + digits.substring(digits.length() - 4);
    }
}
