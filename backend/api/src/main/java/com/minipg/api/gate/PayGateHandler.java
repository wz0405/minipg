package com.minipg.api.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipg.api.flow.AbstractPayProcess;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 전문 디스패처 — 전문의 cmd를 "process|{cmd}" 빈 별명으로 해석해 프로세스를 찾는다.
 * 새 업무 추가에 이 클래스는 손대지 않는다 (빈 등록이 곧 라우팅).
 *
 * 이벤트루프 스레드에서는 파싱 없이 바로 비즈니스 executor(가상스레드)로 넘긴다
 * — DB 대기·제휴사 지연이 이벤트루프를 막으면 게이트웨이 전체가 죽기 때문.
 */
@Slf4j
@Sharable
@Component
@RequiredArgsConstructor
public class PayGateHandler extends SimpleChannelInboundHandler<String> {

    private final ApplicationContext beanFinder;
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
            AbstractPayProcess process;
            try {
                process = beanFinder.getBean("process|" + cmd, AbstractPayProcess.class);
            } catch (NoSuchBeanDefinitionException e) {
                return objectMapper.writeValueAsString(
                        Map.of("rsltCd", "1000", "rsltMsg", "알 수 없는 전문: " + cmd));
            }
            return objectMapper.writeValueAsString(process.run(req));
        } catch (Exception e) {
            log.error("전문 처리 실패: {}", msg, e);
            return "{\"rsltCd\":\"9999\",\"rsltMsg\":\"내부 오류\"}";
        }
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
