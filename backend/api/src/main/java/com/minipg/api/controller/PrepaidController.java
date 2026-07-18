package com.minipg.api.controller;

import com.minipg.api.gate.PayGateClient;
import com.minipg.common.mapper.PpMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 선불 머니/포인트 — 조회는 직접, 잔액 변동(충전/결제)은 게이트웨이 전문으로 위임. */
@RestController
@RequestMapping("/api/pay/pp")
@RequiredArgsConstructor
public class PrepaidController {

    private static final String DEMO_MCHT_ID = "demostore01";

    private final PpMapper ppMapper;
    private final PayGateClient payGateClient;

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return ppMapper.selectUsersWithBalance();
    }

    @PostMapping("/charge")
    public Map<String, Object> charge(@RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("cmd", "PP_CHARGE");
        return payGateClient.call(msg);
    }

    @PostMapping("/pay")
    public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
        Map<String, Object> msg = new HashMap<>(body);
        msg.put("cmd", "PP_PAY");
        msg.putIfAbsent("mchtId", DEMO_MCHT_ID);
        msg.putIfAbsent("goodsNm", "선불결제");
        return payGateClient.call(msg);
    }

    @GetMapping("/hist/{usrId}")
    public List<Map<String, Object>> hist(@PathVariable String usrId) {
        return ppMapper.selectHist(usrId);
    }
}
