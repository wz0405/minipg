package com.minipg.batch.job;

import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.TrMstrMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG 자동취소 동기화 — 매일 23:30 KST.
 *
 * 나이스페이 데모 MID는 23:00에 당일 실결제를 PG측에서 전건 자동취소한다.
 * 이 잡은 그 사실을 로컬 원장에 반영한다: 당일 LIVE 승인 중 취소행이 없는 건에
 * 음수 취소행을 적재한다 (PG 호출 없음 — PG는 이미 취소 완료 상태).
 * 다음날 일정산에서 승인·취소가 상쇄되어 "취소 반영 정산"이 매일 시연된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgAutoCancelSyncJob {

    private final TrMstrMapper trMstrMapper;

    @Scheduled(cron = "${minipg.cron.auto-cancel-sync:0 30 23 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now();
        List<TrMstr> targets = trMstrMapper.selectLiveUncancelled(today);
        for (TrMstr approval : targets) {
            trMstrMapper.insert(TrMstr.cancelOf(approval, LocalDateTime.now()));
        }
        if (!targets.isEmpty()) {
            log.info("PG 자동취소 동기화: {}건 취소행 적재 (trDt={})", targets.size(), today);
        }
    }
}
