package com.minipg.api.gate;

import com.minipg.api.flow.AbstractPayProcess;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import jakarta.annotation.PreDestroy;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * VAN 전문 처리기 — 프레이머가 잘라준 한 전문(헤더+바디)을 고정 오프셋으로 해석해
 * 내부 전문(Map)으로 바꾸고, 결과를 길이 프리픽스 응답 전문으로 되돌린다.
 * 이벤트루프는 절단·해석까지만, 업무는 가상스레드 executor에서 프로세스 프레임을 태운다.
 */
@Slf4j
@Sharable
@Component
@RequiredArgsConstructor
public class VanGateHandler extends SimpleChannelInboundHandler<String> {

    private static final Charset EUC_KR = Charset.forName("euc-kr");

    private final ApplicationContext beanFinder;
    private final ExecutorService bizExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String frame) {
        bizExecutor.submit(() -> ctx.writeAndFlush(
                Unpooled.copiedBuffer(dispatch(frame), EUC_KR)));
    }

    private String dispatch(String frame) {
        String msgType = cut(frame, 0, 4);
        String respType = "0200".equals(msgType) ? "0210" : "0430";
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("msgType", msgType);
            msg.put("termNo", cut(frame, 4, 16));
            msg.put("trDnt", cut(frame, 16, 30));
            msg.put("tid", cut(frame, 30, 50));
            msg.put("amt", stripZero(cut(frame, 50, 62)));
            if ("0200".equals(msgType)) {
                msg.put("cardNoMasked", cut(frame, 62, 78));
            }

            AbstractPayProcess process = beanFinder.getBean("process|VAN_NOTIFY", AbstractPayProcess.class);
            Map<String, Object> res = process.run(msg);
            String rsltCd = String.valueOf(res.getOrDefault("rsltCd", "9999"));
            log.info("VAN {} term={} tid={} -> {}", msgType, msg.get("termNo"), msg.get("tid"), rsltCd);
            return respFrame(respType, rsltCd);
        } catch (Exception e) {
            log.error("VAN 전문 처리 실패: {}", frame, e);
            return respFrame(respType, "9999");
        }
    }

    /** 응답 = LENGTH("0008") + MSG_TYPE(4) + RSLT_CD(4). */
    private String respFrame(String respType, String rsltCd) {
        String body = respType + pad(rsltCd, 4);
        return String.format("%04d", body.getBytes(EUC_KR).length) + body;
    }

    private String cut(String frame, int from, int to) {
        if (from >= frame.length()) {
            return "";
        }
        return frame.substring(from, Math.min(to, frame.length())).trim();
    }

    private String stripZero(String s) {
        String v = s.replaceFirst("^0+", "");
        return v.isEmpty() ? "0" : v;
    }

    private String pad(String s, int len) {
        return s.length() >= len ? s.substring(0, len) : s + " ".repeat(len - s.length());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("VanGate 채널 오류", cause);
        ctx.close();
    }

    @PreDestroy
    public void shutdown() {
        bizExecutor.shutdown();
    }
}
