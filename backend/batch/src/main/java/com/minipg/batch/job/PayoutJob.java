package com.minipg.batch.job;

import com.minipg.common.mapper.StmtMapper;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 지급 배치 — 매일 06:00 KST, 지급예정일이 도래한 정산 건을 지급완료로 처리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutJob {

    private final StmtMapper stmtMapper;

    @Scheduled(cron = "${minipg.cron.payout:0 0 6 * * *}", zone = "Asia/Seoul")
    public void run() {
        try {
            int paid = stmtMapper.markPaidDue(LocalDate.now());
            if (paid > 0) {
                log.info("지급 처리: {}건 지급완료 전환", paid);
            }
        } catch (Exception e) {
            log.error("지급 배치 실패", e);
        }
    }
}
