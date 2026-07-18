package com.minipg.batch.job;

import com.minipg.common.service.SettlementService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 일정산 — 매일 04:00 KST, 전일 거래 마감분을 정산한다. 실패해도 수동 재실행(멱등)으로 복구 가능. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySettlementJob {

    private final SettlementService settlementService;

    @Scheduled(cron = "${minipg.cron.settle:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        LocalDate settleDt = LocalDate.now().minusDays(1);
        try {
            settlementService.run(settleDt);
        } catch (Exception e) {
            log.error("일정산 실패: settleDt={} — 수동 재실행 필요 (/api/admin/settle/run)", settleDt, e);
        }
    }
}
