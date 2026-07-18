package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.TerminalMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * VAN 오프라인 통보 처리 — 단말기에서 이미 승인/취소된 거래의 사후 통보라
 * 제휴사 호출(do)이 없고, 우리는 원장 반영만 책임진다.
 *
 * <ul>
 *   <li>가맹점은 전문에 없다 — 단말번호로 레지스트리(SI_TERMINAL)에서 역조회</li>
 *   <li>거래 시각은 통보 시각이 아니라 전문의 거래일시로 스탬핑 (지연·재전송 대비)</li>
 *   <li>재전송에 멱등 — 이미 반영된 통보는 다시 적재하지 않고 성공으로 응답한다.
 *       실패로 응답하면 VAN이 재전송을 반복한다</li>
 * </ul>
 */
@Slf4j
@Service("process|VAN_NOTIFY")
public class VanNotify extends AbstractPayProcess {

    private static final DateTimeFormatter DNT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Autowired
    private TerminalMapper terminalMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        String mchtId = terminalMapper.selectMchtId(ctx.in("termNo"));
        if (mchtId == null) {
            throw new FlowStop("7001", "미등록 단말기: " + ctx.in("termNo"));
        }
        ctx.work("mchtId", mchtId);
        ctx.work("trTm", LocalDateTime.parse(ctx.in("trDnt"), DNT));
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        if ("0420".equals(ctx.in("msgType"))) {
            cancelNotify(ctx);
            return;
        }
        TrMstr exists = trMstrMapper.selectApproval(ctx.in("tid"));
        if (exists != null) {
            log.info("VAN 승인통보 재전송 — 멱등 응답: tid={}", ctx.in("tid"));
            return;
        }
        LocalDateTime trTm = ctx.work("trTm");
        trMstrMapper.insert(TrMstr.builder()
                .tid(ctx.in("tid")).orgTid(ctx.in("tid"))
                .mchtId(ctx.workStr("mchtId")).payMethod("CARD")
                .txStatus(TrMstr.STATUS_APPROVED)
                .amt(ctx.amt()).trDt(trTm.toLocalDate()).trTm(trTm)
                .channel(TrMstr.CH_LIVE).payType("OFFLINE")
                .goodsNm("오프라인 단말 결제")
                .cardNoMasked(ctx.in("cardNoMasked"))
                .build());
    }

    /** 취소통보(0420) — 원거래를 찾아 상쇄 취소행을 적재한다. 원거래가 없으면 거절. */
    private void cancelNotify(PayContext ctx) {
        TrMstr approval = trMstrMapper.selectApproval(ctx.in("tid"));
        if (approval == null) {
            throw new FlowStop("7003", "취소통보인데 원거래 없음: " + ctx.in("tid"));
        }
        if (trMstrMapper.countCancel(ctx.in("tid")) > 0) {
            log.info("VAN 취소통보 재전송 — 멱등 응답: tid={}", ctx.in("tid"));
            return;
        }
        LocalDateTime trTm = ctx.work("trTm");
        trMstrMapper.insert(TrMstr.cancelOf(approval, trTm));
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("tid", ctx.in("tid"), "amt", ctx.amt()));
    }
}
