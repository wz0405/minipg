package com.minipg.api.gate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** VAN 통보 시뮬레이터 — 데모에서 단말기 역할을 대신해 길이 프리픽스 전문을 쏜다. */
@Component
public class VanTestClient {

    private static final Charset EUC_KR = Charset.forName("euc-kr");
    private static final DateTimeFormatter DNT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Value("${minipg.vangate.port:9095}")
    private int port;

    public Map<String, Object> send(String msgType, String termNo, String tid, long amt) {
        if (tid == null || tid.isBlank()) {
            tid = "VANOFF" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                    + ThreadLocalRandom.current().nextInt(10, 99);
        }
        String body = msgType
                + padRight(termNo, 12)
                + LocalDateTime.now().format(DNT)
                + padRight(tid, 20)
                + padZero(amt, 12)
                + ("0200".equals(msgType) ? padRight("552712******3456", 16) : "");
        String frame = String.format("%04d", body.getBytes(EUC_KR).length) + body;

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(frame.getBytes(EUC_KR));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] resp = in.readNBytes(12);
            String r = new String(resp, EUC_KR);
            return Map.of("rsltCd", r.substring(8, 12).trim(), "respType", r.substring(4, 8),
                    "tid", tid, "sentFrame", frame);
        } catch (Exception e) {
            return Map.of("rsltCd", "9997", "rsltMsg", "VAN 게이트 통신 실패: " + e.getMessage());
        }
    }

    private String padRight(String s, int len) {
        return s.length() >= len ? s.substring(0, len) : s + " ".repeat(len - s.length());
    }

    private String padZero(long v, int len) {
        String s = String.valueOf(v);
        return "0".repeat(Math.max(0, len - s.length())) + s;
    }
}
