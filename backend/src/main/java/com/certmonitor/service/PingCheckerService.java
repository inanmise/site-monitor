package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class PingCheckerService {

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String host, int timeoutMs) {
        return CompletableFuture.completedFuture(check(host, timeoutMs));
    }

    public Map<String, Object> check(String host, int timeoutMs) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            InetAddress addr = InetAddress.getByName(host);
            boolean reachable = addr.isReachable(timeoutMs);
            long ms = System.currentTimeMillis() - start;
            result.put("reachable", reachable);
            result.put("response_ms", reachable ? ms : null);
            if (!reachable) result.put("error", "Host unreachable (ICMP timeout)");
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("response_ms", null);
            result.put("error", e.getMessage());
            log.debug("Ping check failed for {}: {}", host, e.getMessage());
        }
        return result;
    }
}
