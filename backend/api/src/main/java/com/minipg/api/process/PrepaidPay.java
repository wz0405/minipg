package com.minipg.api.process;

import com.minipg.api.flow.AbstractPayProcess;
import com.minipg.api.flow.FlowStop;
import com.minipg.api.flow.PayContext;
import com.minipg.common.config.PrepaidPolicy;
import com.minipg.common.domain.TrMstr;
import com.minipg.common.mapper.PpMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 선불(머니/포인트) 복합결제 — 자체 원장 수단.
 *
 * 한 건의 결제가 소진순서(머니→포인트)로 여러 매체에 걸쳐 차감될 수 있다.
 * 그래서 읽기 시점에 대상 매체 행을 {@code SELECT ... FOR UPDATE}로 (PP_TYPE 순서 고정)
 * 잠그고, 그 잠금 안에서 총 잔액 검증 → 순회 차감을 한 트랜잭션으로 끝낸다.
 *
 * <ul>
 *   <li>다중 행 잠금이라 잠금 순서를 PP_TYPE으로 고정해 교차 데드락을 막는다</li>
 *   <li>검증(총잔액 ≥ 결제액)을 잠금 안에서 수행 — 검사와 차감 사이 값이 바뀌는 TOCTOU 방지</li>
 *   <li>매체별로 원장 sub-거래를 남기되 같은 원거래번호(OTID=그룹ID)로 묶어,
 *       취소 시 그룹 단위로 되돌린다. 정산은 매체별 결제수단으로 각각 집계된다</li>
 * </ul>
 *
 * 참고: 단건 평문 잔액이면 {@code SET BLNC = BLNC + Δ WHERE BLNC + Δ >= 0} 조건부 UPDATE
 * 한 방이 더 단순하다. 여기선 다중 매체·순서 의존 구조라 잠금 후 판정이 정석이다.
 */
@Service("process|PP_PAY")
public class PrepaidPay extends AbstractPayProcess {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    @Autowired
    private PpMapper ppMapper;

    @Override
    protected void beforeProcess(PayContext ctx) {
        if (ctx.amt() <= 0) {
            throw new FlowStop("1100", "결제금액은 양수여야 합니다");
        }
        ctx.work("groupTid", "PPW" + LocalDateTime.now().format(TS)
                + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    @Override
    protected void afterProcess(PayContext ctx) {
        String usrId = ctx.in("usrId");
        String groupTid = ctx.workStr("groupTid");
        long amt = ctx.amt();

        // 소진순서 매체를 잠금 순서 고정으로 잠근다 (데드락 방지).
        List<Map<String, Object>> locked = ppMapper.selectMediaForUpdate(usrId, PrepaidPolicy.CONSUME_ORDER);
        Map<String, Long> balByType = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : locked) {
            long bal = ((Number) row.get("BLNC")).longValue();
            balByType.put((String) row.get("PP_TYPE"), bal);
            total += bal;
        }
        if (total < amt) {
            throw new FlowStop("1102", "선불 잔액 부족: 보유 " + total + " < 결제 " + amt);
        }

        // 소진순서로 순회하며 차감 — 매체별 sub-거래 원장 + 이력.
        long remaining = amt;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (String type : PrepaidPolicy.CONSUME_ORDER) {
            if (remaining <= 0) {
                break;
            }
            long bal = balByType.getOrDefault(type, 0L);
            long take = Math.min(bal, remaining);
            if (take <= 0) {
                continue;
            }
            if (ppMapper.addBalance(usrId, type, -take) == 0) {
                // 잠금 안이라 여기 도달할 일이 없지만, 정합성 방어.
                throw new FlowStop("1102", "선불 차감 실패: " + usrId + "/" + type);
            }
            String subTid = groupTid + "_" + type;
            TrMstr row = approvalRow(subTid, ctx.in("mchtId"), type, take,
                    "PP", null, null, ctx.in("goodsNm"), null, usrId);
            row.setOrgTid(groupTid);          // 그룹 묶기 — 취소는 OTID로 일괄 처리
            trMstrMapper.insert(row);
            long blncAfter = ppMapper.selectBalance(usrId, type);
            ppMapper.insertHist(usrId, type, "PAY", -take, blncAfter, subTid);
            remaining -= take;
            breakdown.add(Map.of("payMethod", type, "amt", take, "tid", subTid));
        }
        ctx.work("breakdown", breakdown);
    }

    @Override
    protected void postCommit(PayContext ctx) {
        ctx.ok(Map.of("tid", ctx.workStr("groupTid"), "amt", ctx.amt(),
                "payMethod", "PREPAID", "breakdown", ctx.work("breakdown")));
    }
}
