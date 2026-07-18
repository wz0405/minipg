package com.minipg.api.controller;

import com.minipg.api.gate.PayGateClient;
import com.minipg.api.pg.KakaopayClient;
import com.minipg.api.pg.NicepayClient;
import jakarta.servlet.http.HttpServletRequest;
import com.minipg.common.domain.PayReq;
import com.minipg.common.mapper.PayReqMapper;
import com.minipg.common.mapper.VacntMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 결제 HTTP 어댑터 — 결제 명령은 전문으로 변환해 Netty 게이트웨이(PayGate)로 위임한다.
 * 결제창 서명 등 상점키가 필요한 연산은 전부 서버에서 수행하며 키는 클라이언트로 내리지 않는다.
 */
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController {

    private static final String DEMO_MCHT_ID = "demostore01";

    private final PayGateClient payGateClient;
    private final NicepayClient nicepayClient;
    private final KakaopayClient kakaopayClient;
    private final PayReqMapper payReqMapper;
    private final VacntMapper vacntMapper;

    /** 결제 페이지 초기화 — 경로별 스텁 여부 + 카카오 직연동 가능 여부. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("authStub", nicepayClient.authStub(), "keyinStub", nicepayClient.keyinStub(),
                "kakaoDirect", kakaopayClient.enabled());
    }

    /**
     * 카카오페이 직연동 결제준비 — tid를 결제요청에 박아 두고 결제창 리다이렉트 URL을 돌려준다.
     * 승인은 approval_url 복귀 시 게이트웨이 전문(KAKAO_APPROVE)으로 처리.
     */
    @PostMapping("/kakao/ready")
    public Map<String, Object> kakaoReady(@RequestBody Map<String, Object> body, HttpServletRequest http) {
        String reqId = body.get("reqId") == null || String.valueOf(body.get("reqId")).isBlank()
                ? null : String.valueOf(body.get("reqId"));
        long amt = Long.parseLong(String.valueOf(body.get("amt")));
        String goodsNm = String.valueOf(body.getOrDefault("goodsNm", "상품"));
        if (reqId == null) {
            reqId = "KRQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            payReqMapper.insert(PayReq.builder()
                    .reqId(reqId)
                    .mchtId(String.valueOf(body.getOrDefault("mchtId", DEMO_MCHT_ID)))
                    .amt(amt).goodsNm(goodsNm)
                    .returnUrl(body.get("returnUrl") == null ? null : String.valueOf(body.get("returnUrl")))
                    .build());
        }
        String base = baseUrl(http);
        Map<String, Object> r = kakaopayClient.ready(reqId, "minipg-demo", goodsNm, amt,
                base + "/api/pay/kakao/approve?reqId=" + reqId,
                base + "/checkout-result.html?rsltCd=1403&rsltMsg=" + enc("카카오페이 결제를 취소했어요"),
                base + "/checkout-result.html?rsltCd=1404&rsltMsg=" + enc("카카오페이 결제에 실패했어요"));
        if (r.get("tid") == null) {
            return Map.of("rsltCd", "1400",
                    "rsltMsg", "카카오페이 결제준비 실패: " + r.getOrDefault("msg", r.getOrDefault("error_message", "")));
        }
        payReqMapper.updateStatus(reqId, PayReq.ST_REQUESTED, String.valueOf(r.get("tid")));
        return Map.of("rsltCd", "0000", "reqId", reqId,
                "redirectPc", String.valueOf(r.get("next_redirect_pc_url")),
                "redirectMobile", String.valueOf(r.get("next_redirect_mobile_url")));
    }

    /** 카카오페이 결제창 복귀(approval_url) — 승인 후 결과 페이지로 이동. */
    @GetMapping("/kakao/approve")
    public ResponseEntity<Void> kakaoApprove(@RequestParam String reqId,
            @RequestParam("pg_token") String pgToken) {
        Map<String, Object> res = payGateClient.call(Map.of(
                "cmd", "KAKAO_APPROVE", "reqId", reqId, "pgToken", pgToken));
        PayReq req = payReqMapper.selectById(reqId);
        String target = req != null && req.getReturnUrl() != null && !req.getReturnUrl().isBlank()
                ? req.getReturnUrl() : "/checkout-result.html";
        String location = UriComponentsBuilder.fromUriString(target)
                .queryParam("rsltCd", nvl(res.get("rsltCd")))
                .queryParam("rsltMsg", nvl(res.get("rsltMsg")))
                .queryParam("tid", nvl(res.get("tid")))
                .queryParam("amt", nvl(res.get("amt")))
                .queryParam("pmCd", nvl(res.get("pmCd")))
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    private String baseUrl(HttpServletRequest http) {
        String proto = http.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = http.getScheme();
        }
        return proto + "://" + http.getHeader("Host");
    }

    private String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 결제창(v3) 요청 서명 — MID/EdiDate/SignData를 서버에서 만들어 내린다. */
    @GetMapping("/checkout-sign")
    public Map<String, String> checkoutSign(@RequestParam long amt) {
        return nicepayClient.checkoutSign(amt);
    }

    /** 결제요청 사전등록 — 가맹점(주문 시스템)이 결제 전 호출. checkoutUrl로 사용자를 보낸다. */
    @PostMapping("/order")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        String reqId = "REQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        payReqMapper.insert(PayReq.builder()
                .reqId(reqId)
                .mchtId(String.valueOf(body.getOrDefault("mchtId", DEMO_MCHT_ID)))
                .amt(Long.parseLong(String.valueOf(body.get("amt"))))
                .goodsNm(String.valueOf(body.getOrDefault("goodsNm", "상품")))
                .returnUrl(body.get("returnUrl") == null ? null : String.valueOf(body.get("returnUrl")))
                .notifyUrl(body.get("notifyUrl") == null ? null : String.valueOf(body.get("notifyUrl")))
                .build());
        return Map.of("reqId", reqId, "checkoutUrl", "/checkout.html?reqId=" + reqId);
    }

    /** 결제요청 조회 — 호스티드 결제 페이지가 주문 정보를 채울 때 사용. */
    @GetMapping("/order/{reqId}")
    public Map<String, Object> getOrder(@PathVariable String reqId) {
        PayReq req = payReqMapper.selectById(reqId);
        if (req == null) {
            return Map.of("rsltCd", "1003", "rsltMsg", "결제요청 없음");
        }
        Map<String, Object> res = new HashMap<>();
        res.put("rsltCd", "0000");
        res.put("reqId", req.getReqId());
        res.put("mchtId", req.getMchtId());
        res.put("amt", req.getAmt());
        res.put("goodsNm", req.getGoodsNm());
        res.put("returnUrl", req.getReturnUrl());
        res.put("reqStCd", req.getReqStCd());
        return res;
    }

    /** 나이스 결제창 인증 완료 수신(ReturnURL) — 승인 전문을 게이트웨이로 위임 후 결과 페이지로 복귀. */
    @PostMapping(value = "/auth-callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> authCallback(@RequestParam Map<String, String> form) {
        Map<String, Object> res;
        if (!"0000".equals(form.get("AuthResultCode"))) {
            res = Map.of("rsltCd", form.getOrDefault("AuthResultCode", "9996"),
                    "rsltMsg", form.getOrDefault("AuthResultMsg", "인증 실패"));
        } else {
            Map<String, Object> msg = new HashMap<>();
            msg.put("cmd", "APPROVE");
            msg.put("authToken", form.get("AuthToken"));
            msg.put("tid", form.get("TxTid"));
            msg.put("nextAppUrl", form.get("NextAppURL"));
            msg.put("amt", form.getOrDefault("Amt", form.get("amt")));
            msg.put("orderId", form.get("Moid"));
            msg.put("mchtId", form.getOrDefault("mchtId", DEMO_MCHT_ID));
            msg.put("goodsNm", form.get("GoodsName"));
            msg.put("reqId", form.get("reqId"));
            res = payGateClient.call(msg);
        }
        String location = UriComponentsBuilder.fromPath("/checkout-result.html")
                .queryParam("rsltCd", nvl(res.get("rsltCd")))
                .queryParam("rsltMsg", nvl(res.get("rsltMsg")))
                .queryParam("tid", nvl(res.get("tid")))
                .queryParam("amt", nvl(res.get("amt")))
                .queryParam("pmCd", nvl(res.get("pmCd")))
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location).build();
    }

    /** 스텁 모드 전용 — 키 없이 결제창 흐름을 흉내 낸 즉시 승인 (clone-and-run 데모용). */
    @PostMapping("/stub-pay")
    public Map<String, Object> stubPay(@RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("cmd", "KEYIN");
        msg.put("mchtId", body.getOrDefault("mchtId", DEMO_MCHT_ID));
        msg.put("amt", body.get("amt"));
        msg.put("reqId", body.get("reqId"));
        msg.put("goodsNm", body.getOrDefault("goodsNm", "데모상품"));
        msg.put("orderId", newOrderId());
        msg.put("cardNo", "5527120000001234");
        msg.put("expYear", "28");
        msg.put("expMonth", "12");
        msg.put("idNo", "900101");
        msg.put("cardPw", "00");
        return payGateClient.call(msg);
    }

    /** 수기(키인) 결제 — 카드번호/유효기간/생년월일/비밀번호 앞 2자리. */
    @PostMapping("/keyin")
    public Map<String, Object> keyin(@RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("cmd", "KEYIN");
        msg.putIfAbsent("mchtId", DEMO_MCHT_ID);
        msg.putIfAbsent("orderId", newOrderId());
        return payGateClient.call(msg);
    }

    /** 전액 취소. */
    @PostMapping("/{tid}/cancel")
    public Map<String, Object> cancel(@PathVariable String tid,
            @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "고객 요청" : String.valueOf(body.getOrDefault("reason", "고객 요청"));
        return payGateClient.call(Map.of("cmd", "CANCEL", "tid", tid, "reason", reason));
    }

    /** 가상계좌 채번 — 제휴사 벌크 계좌풀에서 할당 (PG 무관 자체 처리). */
    @PostMapping("/vacnt/issue")
    public Map<String, Object> vacntIssue(@RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("cmd", "VACNT_ISSUE");
        msg.putIfAbsent("mchtId", DEMO_MCHT_ID);
        return payGateClient.call(msg);
    }

    /** 입금통보 웹훅 — 제휴사(은행)가 계좌번호+금액으로 통보하는 실운영 형태. */
    @PostMapping("/vacnt/webhook")
    public Map<String, Object> vacntWebhook(@RequestBody Map<String, Object> body) {
        return payGateClient.call(Map.of("cmd", "VACNT_DEPOSIT_ACCT",
                "vacntNo", String.valueOf(body.get("vacntNo")), "amt", body.get("amt")));
    }

    /** 모의입금 트리거 (데모) — TID 기준으로 입금통보를 시뮬레이션. */
    @PostMapping("/vacnt/{tid}/deposit")
    public Map<String, Object> vacntDeposit(@PathVariable String tid) {
        return payGateClient.call(Map.of("cmd", "VACNT_DEPOSIT", "tid", tid));
    }

    /** 가상계좌 채번/입금 현황. */
    @GetMapping("/vacnt")
    public List<Map<String, Object>> vacntList() {
        return vacntMapper.selectRecent();
    }

    private String newOrderId() {
        return "ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String nvl(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
