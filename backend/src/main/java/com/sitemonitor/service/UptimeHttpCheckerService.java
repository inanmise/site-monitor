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
    /** Kurumsal vekil (2026-09-21) — isteğe bağlı: bean yoksa (eski test kurulumları) her zaman doğrudan. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProxySettings proxySettings;

    /** Geriye uyum: doğrudan yol (bugünkü davranış). */
    public Map<String, Object> check(String host, int port, int timeoutMs) {
        return check(host, port, timeoutMs, false);
    }

    /**
     * @param viaProxy envanter kaydı "Proxy üzerinden kontrol et = Evet" ise (ve vekil tanımlı, hedef NO_PROXY'de değilse)
     *                 TCP yoklaması vekilde {@code CONNECT host:port} tüneliyle yapılır — sertifika kontrolüyle aynı yol.
     *                 Eskiden Durum izlemesi aynı kayıt için pod'dan doğrudan çıkıyor, vekil-zorunlu alan adları
     *                 sertifikada "geçerli" iken durumda hep "down" kalıyordu (2026-09-21).
     */
    public Map<String, Object> check(String host, int port, int timeoutMs, boolean viaProxy) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("via", viaProxy ? "proxy" : "direct");
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
            classify(result, be, false, host, port, timeoutMs, null);
            return result;
        }
        // Çok-A: DOĞRULANMIŞ IP'leri sırayla dene, ilk erişilebilende "up" (split-VIP host'ta yanlış
        // IP'ye düşüp Connection refused ile flapping olmasın). Host'u yeniden ÇÖZMEYİZ → DNS-rebind kapanır.
        boolean proxied = viaProxy && proxySettings != null;
        try (Socket socket = proxied
                ? proxySettings.openConnectTunnel(host, port, timeoutMs)
                : NetworkResolver.connectFirstReachable(vetted, port, timeoutMs)) {
            long ms = System.currentTimeMillis() - start;
            result.put("status", "up");
            result.put("response_ms", ms);
        } catch (Exception e) {
            result.put("status", "down");
            result.put("response_ms", null);
            result.put("error", e.getMessage());
            classify(result, e, proxied, host, port, timeoutMs, vetted);
            log.debug("Uptime check failed for {}:{}: {}", host, port, e.getMessage());
        }
        return result;
    }

    /**
     * Hata teşhisi (2026-10-05): "down" sonucun NEDENİ ({@code failure_reason} + {@code failure_detail}) — istisnanın
     * kendisinden sınıflandırılır; ayrıntı eskiden atılan yolu (direct/proxy) ve çözümlenen IP'leri de taşır. Yalnız ÜST
     * VERİ: status/error/response_ms değişmez; asla fırlatmaz.
     */
    private static void classify(Map<String, Object> result, Throwable t, boolean proxied, String host, int port,
                                 int timeoutMs, List<InetAddress> vetted) {
        try {
            com.sitemonitor.service.failure.CheckFailure f =
                    com.sitemonitor.service.failure.CheckFailureClassifier.forException(t, proxied)
                            .with("target", host + ":" + port)
                            .withIfAbsent("via", proxied ? "proxy" : "direct")
                            .with("timeout_ms", timeoutMs);
            if (vetted != null && !proxied) {
                f.with("resolved_ips", vetted.stream().map(InetAddress::getHostAddress).toList());
            }
            f.applyTo(result);
        } catch (Exception ignore) { /* üst veri — kontrol sonucu olduğu gibi kalır */ }
    }
}
