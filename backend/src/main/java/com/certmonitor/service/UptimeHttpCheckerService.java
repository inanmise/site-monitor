package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class UptimeHttpCheckerService {

    public Map<String, Object> check(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            long ms = System.currentTimeMillis() - start;
            result.put("status", "up");
            result.put("response_ms", ms);
        } catch (Exception e) {
            result.put("status", "down");
            result.put("response_ms", null);
            result.put("error", e.getMessage());
            log.debug("Uptime check failed for {}:{}: {}", host, port, e.getMessage());
        }
        return result;
    }
}
