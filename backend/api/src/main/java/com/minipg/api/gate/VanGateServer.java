package com.minipg.api.gate;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * VAN 오프라인 통보 게이트웨이 — 단말망에서 오는 연결형 TCP 바이너리 전문 수신부.
 *
 * PayGate(개행 구분 JSON) 옆에 나란히 뜨는 두 번째 수신 채널이다.
 * 프로토콜(전문길이+헤더+바디, EUC-KR)만 다르고, 디코딩 이후는 같은 프로세스
 * 프레임(process|VAN_NOTIFY)으로 합류한다 — 수신 채널이 늘어도 업무 코드는 하나.
 *
 * 전문 규격 (LENGTH는 자기 자신 제외 바이트수)
 * <pre>
 * 요청 = LENGTH(4) + 헤더(30) + 바디
 *   헤더        : MSG_TYPE(4: 0200승인/0420취소) + TERM_NO(12) + TR_DNT(14)
 *   바디(0200)  : TID(20) + AMT(12, 좌측0) + CARD_MASK(16)   → LENGTH="0078"
 *   바디(0420)  : TID(20) + AMT(12)                          → LENGTH="0062"
 * 응답 = LENGTH(4)="0008" + MSG_TYPE(4: 0210/0430) + RSLT_CD(4)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VanGateServer {

    private final VanGateHandler vanGateHandler;

    @Value("${minipg.vangate.port:9095}")
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
                                .addLast(new VanFrameDecoder())
                                .addLast(vanGateHandler);
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("VanGate 기동: port={} (길이+헤더+바디 전문)", port);
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
    }
}
