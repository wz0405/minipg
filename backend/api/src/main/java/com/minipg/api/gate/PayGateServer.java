package com.minipg.api.gate;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결제 전문 게이트웨이 (Netty).
 *
 * 결제 명령은 HTTP 컨트롤러가 직접 처리하지 않고 루프백으로 이 서버에 전문을 던진다
 * — 대외계 수신부와 동일한 계층 구조를 유지하기 위함.
 * 전문 프레이밍은 개행 구분 JSON 한 줄(라인 프로토콜)로 단순화했다.
 *
 * 이벤트루프는 프레이밍/디코딩까지만 담당하고, DB·제휴사 통신이 섞인 비즈니스 처리는
 * {@link PayGateHandler}가 가상스레드 executor로 격리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayGateServer {

    private final PayGateHandler payGateHandler;

    @Value("${minipg.paygate.port:9090}")
    private int port;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new LineBasedFrameDecoder(64 * 1024))
                                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                                .addLast(payGateHandler);
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("PayGate 기동: port={}", port);
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("PayGate 종료");
    }
}
