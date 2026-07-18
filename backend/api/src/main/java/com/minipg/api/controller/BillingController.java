package com.minipg.api.controller;

import com.minipg.api.gate.PayGateClient;
import com.minipg.api.pg.NicepayClient;
import com.minipg.api.pg.NicepayResult;
import com.minipg.common.mapper.BillKeyMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정기결제(빌링). 빌키 발급/삭제는 원장을 건드리지 않으므로 직접 처리하고,
 * 회차 승인(원장 적재)만 게이트웨이 전문으로 위임한다.
 */
@RestController
@RequestMapping("/api/pay/billing")
@RequiredArgsConstructor
public class BillingController {

    private static final String DEMO_MCHT_ID = "demostore01";

    private final NicepayClient nicepayClient;
    private final BillKeyMapper billKeyMapper;
    private final PayGateClient payGateClient;

    /** 빌키 발급 — 카유생비를 PG로 넘겨 BID를 받고, 카드 원본은 버린다. */
    @PostMapping("/regist")
    public Map<String, Object> regist(@RequestBody Map<String, Object> body) {
        String moid = "BREG" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        NicepayResult r = nicepayClient.billingRegist(moid,
                str(body, "cardNo"), str(body, "expYear"), str(body, "expMonth"),
                str(body, "idNo"), str(body, "cardPw"));
        if (!r.success("F100")) {
            return Map.of("rsltCd", r.resultCode(), "rsltMsg", r.resultMsg());
        }
        String bid = r.get("BID");
        billKeyMapper.insert(bid, str(body).getOrDefault("mchtId", DEMO_MCHT_ID).toString(),
                mask(nvlOr(r.get("CardNo"), str(body, "cardNo"))), moid);
        return Map.of("rsltCd", "0000", "rsltMsg", "빌키 발급 완료", "bid", bid);
    }

    /** 회차 승인 — 등록된 빌키로 결제, 원장(TR_MSTR) 적재. */
    @PostMapping("/{bid}/approve")
    public Map<String, Object> approve(@PathVariable String bid, @RequestBody Map<String, Object> body) {
        Map<String, Object> billKey = billKeyMapper.selectActive(bid);
        if (billKey == null) {
            return Map.of("rsltCd", "1301", "rsltMsg", "유효한 빌키 없음: " + bid);
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("cmd", "BILL_APPROVE");
        msg.put("bid", bid);
        msg.put("mchtId", billKey.get("MCHT_ID"));
        msg.put("amt", body.get("amt"));
        msg.put("goodsNm", body.getOrDefault("goodsNm", "정기결제"));
        return payGateClient.call(msg);
    }

    /** 빌키 삭제(해지). */
    @DeleteMapping("/{bid}")
    public Map<String, Object> remove(@PathVariable String bid) {
        NicepayResult r = nicepayClient.billingRemove(bid, "BDEL" + System.currentTimeMillis());
        if (!r.success("F101")) {
            return Map.of("rsltCd", r.resultCode(), "rsltMsg", r.resultMsg());
        }
        billKeyMapper.deactivate(bid);
        return Map.of("rsltCd", "0000", "rsltMsg", "빌키 삭제 완료");
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return billKeyMapper.selectRecent();
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> str(Map<String, Object> m) {
        return m;
    }

    private String mask(String cardNo) {
        String digits = cardNo == null ? "" : cardNo.replaceAll("\\D", "");
        if (digits.length() < 10) {
            return "****";
        }
        return digits.substring(0, 6) + "******" + digits.substring(digits.length() - 4);
    }

    private String nvlOr(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
