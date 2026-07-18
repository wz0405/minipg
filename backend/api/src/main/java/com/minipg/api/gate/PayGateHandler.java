package com.minipg.api.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipg.api.service.PaymentService;
import com.minipg.api.service.PrepaidService;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 전문 디스패처.
 *
 * 이벤트루프 스레드에서는 JSON 파싱 없이 바로 비즈니스 executor(가상스레드)로 넘긴다
 * — DB 커넥션 대기, 제휴사 API 지연이 이벤트루프를 막으면 게이트웨이 전체가 죽기 때문.
 * 응답 writeAndFlush는 Netty가 스레드 안전을 보장한다.
 */
@Slf4j
@Sharable
@Component
@RequiredArgsConstructor
public class PayGateHandler extends SimpleChannelInboundHandler<String> {

    private final PaymentService paymentService;
    private final PrepaidService prepaidService;
    private final ObjectMapper objectMapper;
    private final ExecutorService bizExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        bizExecutor.submit(() -> ctx.writeAndFlush(dispatch(msg) + "\n"));
    }

    private String dispatch(String msg) {
        try {
            Map<String, Object> req = objectMapper.readValue(msg, Map.class);
            String cmd = String.valueOf(req.get("cmd"));
            Map<String, Object> res = switch (cmd) {
                case "APPROVE" -> paymentService.approveAuth(
                        str(req, "authToken"), str(req, "tid"), str(req, "nextAppUrl"), amt(req),
                        str(req, "orderId"), str(req, "mchtId"), str(req, "goodsNm"), str(req, "reqId"));
                case "KEYIN" -> paymentService.keyin(
                        str(req, "mchtId"), amt(req), str(req, "goodsNm"), str(req, "orderId"),
                        str(req, "cardNo"), str(req, "expYear"), str(req, "expMonth"),
                        str(req, "idNo"), str(req, "cardPw"), str(req, "reqId"));
                case "CANCEL" -> paymentService.cancel(str(req, "tid"), str(req, "reason"));
                case "KAKAO_APPROVE" -> paymentService.kakaoApprove(str(req, "reqId"), str(req, "pgToken"));
                case "VACNT_ISSUE" -> paymentService.vacntIssue(
                        str(req, "mchtId"), amt(req), str(req, "goodsNm"), str(req, "reqId"));
                case "VACNT_DEPOSIT" -> paymentService.vacntDeposit(str(req, "tid"));
                case "VACNT_DEPOSIT_ACCT" -> paymentService.vacntDepositByAcct(str(req, "vacntNo"), amt(req));
                case "BILL_APPROVE" -> paymentService.billingApprove(
                        str(req, "bid"), str(req, "mchtId"), amt(req), str(req, "goodsNm"));
                case "PP_CHARGE" -> prepaidService.charge(
                        str(req, "usrId"), str(req, "ppType"), amt(req));
                case "PP_PAY" -> prepaidService.pay(
                        str(req, "usrId"), str(req, "ppType"), str(req, "mchtId"), amt(req), str(req, "goodsNm"));
                default -> Map.of("rsltCd", "1000", "rsltMsg", "알 수 없는 전문: " + cmd);
            };
            return objectMapper.writeValueAsString(res);
        } catch (Exception e) {
            log.error("전문 처리 실패: {}", msg, e);
            return "{\"rsltCd\":\"9999\",\"rsltMsg\":\"내부 오류\"}";
        }
    }

    private String str(Map<String, Object> req, String key) {
        Object v = req.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private long amt(Map<String, Object> req) {
        Object v = req.get("amt");
        return v == null ? 0L : Long.parseLong(String.valueOf(v));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("PayGate 채널 오류", cause);
        ctx.close();
    }

    @PreDestroy
    public void shutdown() {
        bizExecutor.shutdown();
    }
}
