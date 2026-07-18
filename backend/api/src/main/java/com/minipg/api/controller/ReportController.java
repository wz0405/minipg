package com.minipg.api.controller;

import com.minipg.common.domain.RcResult;
import com.minipg.common.domain.SmStmt;
import com.minipg.common.domain.SmStmtTid;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.MchtMapper;
import com.minipg.common.mapper.ReconMapper;
import com.minipg.common.mapper.StmtMapper;
import com.minipg.common.mapper.TrMstrMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 정산 리포트 조회 — 일정산, 건별 상세, 월 마진, 대사 결과, 거래내역. */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final StmtMapper stmtMapper;
    private final ReconMapper reconMapper;
    private final TrMstrMapper trMstrMapper;
    private final MchtMapper mchtMapper;

    @GetMapping("/stmt")
    public List<SmStmt> stmt(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDt,
            @RequestParam(required = false) String mchtId) {
        return stmtMapper.selectStmt(fromDt, toDt, mchtId);
    }

    @GetMapping("/stmt/detail")
    public List<SmStmtTid> stmtDetail(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settleDt,
            @RequestParam(required = false) String mchtId) {
        return stmtMapper.selectStmtTid(settleDt, mchtId);
    }

    @GetMapping("/margin")
    public List<Map<String, Object>> margin(@RequestParam String month) {
        return stmtMapper.selectMarginMonthly(month);
    }

    /**
     * 가맹점 정산 안내 — 가맹점 눈높이 뷰.
     * 수수료는 판가만 노출한다 (원가·마진은 PG 내부 정보).
     */
    @GetMapping("/merchant/{mchtId}/settle-info")
    public Map<String, Object> merchantSettleInfo(@PathVariable String mchtId) {
        List<Map<String, Object>> fees = mchtMapper.selectFeeRates(LocalDate.now()).stream()
                .filter(r -> mchtId.equals(r.getMchtId()))
                .map(r -> Map.<String, Object>of(
                        "pmCd", r.getPmCd(),
                        "feeRate", r.getSalesRate(),
                        "settleCycle", r.getSettleCycle()))
                .toList();
        return Map.of(
                "mchtId", mchtId,
                "fees", fees,
                "upcoming", stmtMapper.selectByMchtAndStatus(mchtId, "0"),
                "paid", stmtMapper.selectByMchtAndStatus(mchtId, "1"));
    }

    @GetMapping("/recon")
    public Map<String, Object> recon(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dt) {
        List<Map<String, Object>> summary = reconMapper.selectSummary(dt);
        List<RcResult> diffs = reconMapper.selectResult(dt);
        return Map.of("reconDt", dt.toString(), "summary", summary, "diffs", diffs);
    }

    @GetMapping("/tr")
    public List<TrMstr> tr(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dt,
            @RequestParam(required = false) String mchtId) {
        return trMstrMapper.selectByTrDtAndMcht(dt, mchtId);
    }
}
