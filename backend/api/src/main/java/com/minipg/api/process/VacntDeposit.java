package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.flow.PayReqSupport;
import com.minipg.api.service.AfterProcessor;
import com.minipg.common.domain.PayReq;
import com.minipg.common.mapper.VacntMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 가상계좌 입금 처리(모의입금 트리거, TID 기준) — 입금 확인 시점에 비로소 원장에 승인행이 적재된다. */
@Service("process|VACNT_DEPOSIT")
public class VacntDeposit extends AbstractPayProcess {

    @Autowired
    protected VacntMapper vacntMapper;

    @Autowired
    protected PayReqSupport payReqSupport;

    @Autowired
    protected AfterProcessor afterProcessor;

    @Override
    protected void beforeProcess(PayContext ctx) {
        Map<String, Object> vacnt = vacntMapper.selectByTid(ctx.in("tid"));
        if (vacnt == null) {
            throw new FlowStop("1201", "채번 내역 없음: " + ctx.in("tid"));
        }
        ctx.work("vacnt", vacnt);
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        Map<String, Object> vacnt = ctx.work("vacnt");
        String tid = (String) vacnt.get("TID");
        if (vacntMapper.markDeposited(tid) == 0) {
            throw new FlowStop("1202", "이미 입금되었거나 만료된 채번: " + tid);
        }
        long amt = ((Number) vacnt.get("AMT")).longValue();
        String reqId = (String) vacnt.get("REQ_ID");
        trMstrMapper.insert(approvalRow(tid, (String) vacnt.get("MCHT_ID"), "VACNT", amt,
                "VBANK", null, reqId, "가상계좌입금", null, null));
        payReqSupport.mark(reqId, PayReq.ST_APPROVED, tid);
        ctx.work("depositAmt", amt);
        ctx.work("depositTid", tid);
        ctx.work("depositReqId", reqId);
    }

    @Override
    protected void postCommit(PayContext ctx) {
        afterProcessor.notifyApproved(ctx.workStr("depositReqId"),
                ctx.workStr("depositTid"), ctx.workAmt("depositAmt"));
        ctx.ok(Map.of("tid", ctx.workStr("depositTid"),
                "amt", ctx.workAmt("depositAmt"), "payMethod", "VACNT"));
    }
}
