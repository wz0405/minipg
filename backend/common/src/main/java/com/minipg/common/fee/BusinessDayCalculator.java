package com.minipg.common.fee;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 지급예정일 계산 — 주말과 AD_HOLIDAY에 등록된 휴일을 건너뛴 N영업일. */
@Component
public class BusinessDayCalculator {

    public LocalDate addBusinessDays(LocalDate base, int days, Set<LocalDate> holidays) {
        LocalDate d = base;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (isBusinessDay(d, holidays)) {
                added++;
            }
        }
        return d;
    }

    public boolean isBusinessDay(LocalDate d, Set<LocalDate> holidays) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d);
    }
}
