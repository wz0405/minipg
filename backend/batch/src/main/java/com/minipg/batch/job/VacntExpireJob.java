package com.minipg.batch.job;

import com.minipg.common.mapper.VacntMapper;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가상계좌 만료 회수 — 매일 00:10 KST.
 * 입금기한이 지난 채번을 만료 처리하고 계좌를 풀로 되돌린다.
 * 풀이 유한하므로 회수가 멈추면 채번이 고갈된다 (1203).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VacntExpireJob {

    private final VacntMapper vacntMapper;

    @Scheduled(cron = "${minipg.cron.vacnt-expire:0 10 0 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void run() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<Map<String, Object>> expired = vacntMapper.selectExpired(today);
        for (Map<String, Object> v : expired) {
            String tid = (String) v.get("TID");
            if (vacntMapper.markExpired(tid) > 0 && v.get("POOL_SEQ") != null) {
                vacntMapper.releasePool(((Number) v.get("POOL_SEQ")).longValue());
            }
        }
        if (!expired.isEmpty()) {
            log.info("가상계좌 만료 회수: {}건", expired.size());
        }
    }
}
