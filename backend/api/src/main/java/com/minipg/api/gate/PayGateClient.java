package com.minipg.api.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 루프백 클라이언트. HTTP 어댑터(컨트롤러)가 결제 명령을 전문으로 변환해 던진다.
 * 요청당 단발 커넥션 — 데모 트래픽 수준에서는 풀링보다 단순함이 낫다.
 */
@Component
@RequiredArgsConstructor
public class PayGateClient {

    private final ObjectMapper objectMapper;

    @Value("${minipg.paygate.host:127.0.0.1}")
    private String host;

    @Value("${minipg.paygate.port:9090}")
    private int port;

    public Map<String, Object> call(Map<String, Object> msg) {
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(30_000);
            out.println(objectMapper.writeValueAsString(msg));
            String line = in.readLine();
            if (line == null) {
                return Map.of("rsltCd", "9998", "rsltMsg", "게이트웨이 무응답");
            }
            return objectMapper.readValue(line, Map.class);
        } catch (Exception e) {
            return Map.of("rsltCd", "9997", "rsltMsg", "게이트웨이 통신 실패: " + e.getMessage());
        }
    }
}
