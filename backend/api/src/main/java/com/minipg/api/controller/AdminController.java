package com.minipg.api.controller;

import com.minipg.common.mapper.StmtMapper;
import com.minipg.common.service.ReconService;
import com.minipg.common.service.SeedService;
import com.minipg.common.service.SettlementService;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 배치 수동 트리거 + 시딩. 배치 모듈의 스케줄과 동일한 서비스를 호출한다 (재실행 데모용). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SettlementService settlementService;
    private final ReconService reconService;
    private final SeedService seedService;
    private final StmtMapper stmtMapper;

    /** 지급 처리 수동 트리거 — 지급예정일 도래분을 지급완료로 전환. */
    @PostMapping("/payout/run")
    public Map<String, Object> runPayout() {
        int paid = stmtMapper.markPaidDue(LocalDate.now());
        return Map.of("rsltCd", "0000", "paidCnt", paid);
    }

    @PostMapping("/settle/run")
    public Map<String, Object> runSettle(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dt) {
        return settlementService.run(dt);
    }

    @PostMapping("/recon/run")
    public Map<String, Object> runRecon(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dt,
            @RequestParam(defaultValue = "false") boolean mockIfEmpty) {
        if (mockIfEmpty) {
            reconService.mockSourceIfEmpty(dt);
        }
        return reconService.run(dt);
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody SeedRequest req) {
        return seedService.seed(req.fromDt(), req.toDt(),
                req.perDay() > 0 ? req.perDay() : 100,
                req.cancelRate() != null ? req.cancelRate() : 0.1,
                req.mismatchRate() != null ? req.mismatchRate() : 0.02,
                req.rndSeed());
    }

    public record SeedRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDt,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDt,
            int perDay, Double cancelRate, Double mismatchRate, Long rndSeed) {
    }
}
