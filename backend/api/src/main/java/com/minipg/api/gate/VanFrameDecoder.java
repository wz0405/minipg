package com.minipg.api.gate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.Charset;
import java.util.List;

/**
 * VAN 전문 프레이머 — 연결형 스트림에서 "전문길이(4, ASCII) + 헤더 + 바디" 단위로 자른다.
 *
 * TCP는 경계가 없는 바이트 스트림이라 한 번의 read에 전문이 쪼개져 오거나 여러 건이
 * 붙어 올 수 있다. 길이필드만큼 쌓일 때까지 기다렸다가(reset) 정확히 한 전문씩 내보낸다.
 * 길이필드 값은 길이필드 자신을 제외한 나머지 바이트 수다.
 */
public class VanFrameDecoder extends ByteToMessageDecoder {

    private static final Charset EUC_KR = Charset.forName("euc-kr");
    private static final int LEN_FIELD = 4;
    private static final int MAX_FRAME = 4096;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < LEN_FIELD) {
            return;
        }
        in.markReaderIndex();
        byte[] lenBytes = new byte[LEN_FIELD];
        in.readBytes(lenBytes);

        int bodyLen;
        try {
            bodyLen = Integer.parseInt(new String(lenBytes, EUC_KR).trim());
        } catch (NumberFormatException e) {
            ctx.close();
            return;
        }
        if (bodyLen <= 0 || bodyLen > MAX_FRAME) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < bodyLen) {
            in.resetReaderIndex();
            return;
        }
        out.add(in.readSlice(bodyLen).toString(EUC_KR));
    }
}
