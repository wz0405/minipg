package com.minipg.batch.job;

import com.minipg.common.service.ReconService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 거래대사 — 매일 05:00 KST (일정산 이후), 전일 원장 vs PG 거래내역 대조.
 * PG 소스가 비어 있는 날은 AUTO_MOCK 미러를 만들어 대사가 성립하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconJob {

    private final ReconService reconService;

    @Scheduled(cron = "${minipg.cron.recon:0 0 5 * * *}", zone = "Asia/Seoul")
    public void run() {
        LocalDate reconDt = LocalDate.now().minusDays(1);
        try {
            reconService.mockSourceIfEmpty(reconDt);
            reconService.run(reconDt);
        } catch (Exception e) {
            log.error("거래대사 실패: reconDt={} — 수동 재실행 필요 (/api/admin/recon/run)", reconDt, e);
        }
    }
}
