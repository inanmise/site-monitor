package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UptimeHttpCheckerService {

    private final SsrfGuard ssrfGuard;

    public Map<String, Object> check(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        // SSRF: hedefi bağlanmadan ÖNCE doğrula (cloud-metadata/loopback/link-local blok; iç ağ ayara
        // bağlı) — PortCheckerService ile AYNI desen. Bu checker tek başına doğrulama yapmıyordu:
        // hedef host'u tanımlayabilen kullanıcı, pod'un ulaşabildiği herhangi bir iç adrese TCP
        // yoklaması yaptırıp "up/down" cevabından varlık haritası çıkarabiliyordu.
        List<InetAddress> vetted;
        try {
            vetted = ssrfGuard.validate(host);
        } catch (SsrfGuard.BlockedException be) {
            result.put("status", "down");
            result.put("response_ms", null);
            result.put("error", be.getMessage());
            return result;
        }
        // Çok-A: DOĞRULANMIŞ IP'leri sırayla dene, ilk erişilebilende "up" (split-VIP host'ta yanlış
        // IP'ye düşüp Connection refused ile flapping olmasın). Host'u yeniden ÇÖZMEYİZ → DNS-rebind kapanır.
        try (Socket socket = NetworkResolver.connectFirstReachable(vetted, port, timeoutMs)) {
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
