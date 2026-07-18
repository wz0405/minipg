package com.minipg.api.flow;

import com.minipg.api.partner.PartnerAdapter;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.TrMstrMapper;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 업무 프로세스 골격 — 모든 전문(승인/취소/채번/입금통보/빌키/선불)이 같은 틀을 탄다.
 *
 * <pre>
 * run()
 *  ├ beforeProcess : 검증 — 결제요청 대조, 금액, 잔액, 원거래 존재 등 (DB 조회까지만)
 *  ├ doProcess     : 제휴사 통신 — 트랜잭션 밖. 어댑터는 빈 별명("partner|코드")으로 찾는다
 *  ├ afterProcess  : 원장 반영 — 트랜잭션 안. 실패하면 recover()가 뒷수습(망취소)
 *  └ postCommit    : 커밋 이후 — 서버통보 등 별도 스레드로 넘길 일들
 * </pre>
 *
 * 제휴사 통신을 트랜잭션 밖에 두는 이유: 외부 API 지연이 DB 커넥션 점유로 전이되면
 * 결제 전체가 같이 밀린다. 대신 "PG 승인 후 원장 실패" 구간이 생기고, 그 구간의
 * 뒷수습(망취소)을 프레임이 보장한다.
 *
 * 새 프로세스 추가 = 이 클래스를 상속한 빈 하나("process|전문코드" 별명) 등록이 전부다.
 */
@Slf4j
public abstract class AbstractPayProcess {

    @Autowired
    protected ApplicationContext beanFinder;

    @Autowired
    protected TrMstrMapper trMstrMapper;

    @Autowired
    private TransactionTemplate txTemplate;

    public final Map<String, Object> run(Map<String, Object> msg) {
        PayContext ctx = new PayContext(msg);
        try {
            beforeProcess(ctx);
            doProcess(ctx);
        } catch (FlowStop stop) {
            return fail(stop);
        } catch (Exception e) {
            log.error("{} 처리 오류", getClass().getSimpleName(), e);
            return Map.of("rsltCd", "9000", "rsltMsg", "내부 오류: " + e.getMessage());
        }

        try {
            txTemplate.executeWithoutResult(status -> afterProcess(ctx));
        } catch (Exception e) {
            Map<String, Object> recovered = recover(ctx, e);
            if (recovered != null) {
                return recovered;
            }
            if (e instanceof FlowStop stop) {
                return fail(stop);
            }
            log.error("{} after 단계 오류", getClass().getSimpleName(), e);
            return Map.of("rsltCd", "9000", "rsltMsg", "내부 오류: " + e.getMessage());
        }

        postCommit(ctx);
        if (!ctx.result().containsKey("rsltCd")) {
            ctx.ok(Map.of());
        }
        return ctx.result();
    }

    protected void beforeProcess(PayContext ctx) {
    }

    protected void doProcess(PayContext ctx) {
    }

    protected void afterProcess(PayContext ctx) {
    }

    /** after 실패 뒷수습 훅 — 제휴사 승인이 이미 난 프로세스는 여기서 망취소한다. null이면 기본 오류 응답. */
    protected Map<String, Object> recover(PayContext ctx, Exception cause) {
        return null;
    }

    protected void postCommit(PayContext ctx) {
    }

    /** 제휴사 어댑터를 빈 별명으로 찾는다 — 제휴사가 늘어도 이 룩업은 그대로다. */
    protected PartnerAdapter partner(String partnerCd) {
        return beanFinder.getBean("partner|" + partnerCd, PartnerAdapter.class);
    }

    /** 승인행 원장 빌더 — 원장은 INSERT-only, ORG_TID는 승인 시 자기 자신. */
    protected TrMstr approvalRow(String tid, String mchtId, String payMethod, long amt,
            String payType, String partnerCd, String orderId, String goodsNm,
            String maskedCard, String usrId) {
        LocalDateTime now = LocalDateTime.now();
        return TrMstr.builder()
                .tid(tid).orgTid(tid)
                .mchtId(mchtId).payMethod(payMethod)
                .txStatus(TrMstr.STATUS_APPROVED)
                .amt(amt).trDt(now.toLocalDate()).trTm(now)
                .channel(TrMstr.CH_LIVE).payType(payType).partnerCd(partnerCd)
                .orderId(orderId).goodsNm(goodsNm)
                .cardNoMasked(maskedCard).usrId(usrId)
                .build();
    }

    private Map<String, Object> fail(FlowStop stop) {
        return Map.of("rsltCd", stop.getCode(), "rsltMsg", stop.getMessage());
    }
}
