package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.api.flow.PayReqSupport;
import com.minipg.common.domain.PayReq;
import com.minipg.common.mapper.VacntMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 가상계좌 채번 — 제휴사 통신이 없는 자체 수단이라 do 단계가 비어 있다.
 * 계좌 풀 할당은 FOR UPDATE 비관락과 채번 기록이 한 트랜잭션이어야 하므로 after에서 처리한다.
 */
@Service("process|VACNT_ISSUE")
public class VacntIssue extends AbstractPayProcess {

    @Autowired
    private VacntMapper vacntMapper;

    @Autowired
    private PayReqSupport payReqSupport;

    @Override
    protected void beforeProcess(PayContext ctx) {
        // reqId가 있으면 금액·가맹점은 전문이 아니라 DB(PAY_REQ)를 단일 소스로 쓴다.
        PayReq req = payReqSupport.validateForPay(ctx.in("reqId"), ctx.amt());
        ctx.work("payReq", req);
        ctx.work("mchtId", payReqSupport.resolveMcht(req, ctx.in("mchtId")));
        ctx.work("amt", req != null ? req.getAmt() : ctx.amt());
        ctx.work("tid", "VA" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        Map<String, Object> pool = vacntMapper.selectAvailablePoolForUpdate();
        if (pool == null) {
            throw new FlowStop("1203", "가용 가상계좌 풀 소진 — 만료 회수 대기");
        }
        long poolSeq = ((Number) pool.get("POOL_SEQ")).longValue();
        vacntMapper.assignPool(poolSeq);

        String expDt = LocalDateTime.now().plusDays(3).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        vacntMapper.insert(ctx.workStr("tid"), poolSeq, ctx.in("reqId"),
                ctx.workStr("mchtId"), ctx.workAmt("amt"),
                (String) pool.get("BANK_CD"), (String) pool.get("BANK_NM"),
                (String) pool.get("VACNT_NO"), expDt);
        ctx.work("bankNm", pool.get("BANK_NM"));
        ctx.work("vacntNo", pool.get("VACNT_NO"));
        ctx.work("expDt", expDt);
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("tid", ctx.workStr("tid"), "amt", ctx.workAmt("amt"), "payMethod", "VACNT",
                "bankNm", ctx.workStr("bankNm"), "vacntNo", ctx.workStr("vacntNo"),
                "expDt", ctx.workStr("expDt")));
    }
}
