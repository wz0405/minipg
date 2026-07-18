package com.minipg.api.controller;

import com.minipg.api.gate.PayGateClient;
import com.minipg.common.mapper.BillKeyMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정기결제(빌링) HTTP 어댑터 — 발급/회차승인/해지 모두 게이트웨이 전문으로 위임한다. */
@RestController
@RequestMapping("/api/pay/billing")
@RequiredArgsConstructor
public class BillingController {

    private static final String DEMO_MCHT_ID = "demostore01";

    private final PayGateClient payGateClient;
    private final BillKeyMapper billKeyMapper;

    @PostMapping("/regist")
    public Map<String, Object> regist(@RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("cmd", "BILL_KEY_ISSUE");
        msg.putIfAbsent("mchtId", DEMO_MCHT_ID);
        return payGateClient.call(msg);
    }

    @PostMapping("/{bid}/approve")
    public Map<String, Object> approve(@PathVariable String bid, @RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("cmd", "BILL_APPROVE");
        msg.put("bid", bid);
        msg.put("amt", body.get("amt"));
        msg.put("goodsNm", body.getOrDefault("goodsNm", "정기결제"));
        return payGateClient.call(msg);
    }

    @DeleteMapping("/{bid}")
    public Map<String, Object> remove(@PathVariable String bid) {
        return payGateClient.call(Map.of("cmd", "BILL_KEY_REMOVE", "bid", bid));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return billKeyMapper.selectRecent();
    }
}
