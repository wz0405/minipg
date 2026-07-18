package com.minipg.api.process;

import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 입금통보 웹훅 처리 — 제휴사(은행)는 계좌번호+금액으로 통보하므로
 * 입금대기 채번을 계좌번호로 찾고 금액을 대조한다(상이 시 과오입금 처리 대상).
 */
@Service("process|VACNT_DEPOSIT_ACCT")
public class VacntDepositByAcct extends VacntDeposit {

    @Override
    protected void beforeProcess(PayContext ctx) {
        Map<String, Object> vacnt = vacntMapper.selectActiveByVacntNo(ctx.in("vacntNo"));
        if (vacnt == null) {
            throw new FlowStop("1201", "입금대기 채번 없음: " + ctx.in("vacntNo"));
        }
        long expected = ((Number) vacnt.get("AMT")).longValue();
        if (expected != ctx.amt()) {
            throw new FlowStop("1204",
                    "입금액 상이: 채번=" + expected + ", 입금=" + ctx.amt() + " (과오입금 처리 대상)");
        }
        ctx.work("vacnt", vacnt);
    }
}
