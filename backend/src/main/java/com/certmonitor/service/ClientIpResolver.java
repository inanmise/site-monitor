package com.certmonitor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gerçek client IP'sini tek noktadan çözer. Uygulama OpenShift router + kurumsal LB
 * arkasında olduğundan {@code getRemoteAddr()} proxy IP'sini verir; gerçek IP forwarding
 * başlıklarında taşınır. Hangi başlığın gerçek client'ı taşıdığı ortama göre değişir →
 * sıralı aday başlık listesi YAPILANDIRILABİLİR (cert.monitor.client-ip.headers). Böylece
 * doğru başlık koddan değil config'ten seçilir.
 */
@Component
public class ClientIpResolver {

    /** Sıralı aday başlıklar; ilk DOLU olanın değeri kullanılır. Virgülle ayrılır. */
    @Value("${cert.monitor.client-ip.headers:X-Forwarded-For}")
    private String[] headers;

    /** Çok-değerli başlıkta (ör. XFF "client, proxy1, proxy2") hangi parça; 0 = ilk (client). */
    @Value("${cert.monitor.client-ip.index:0}")
    private int index;

    /** Tanı ucunda gösterilecek bilinen forwarding başlıkları. */
    private static final String[] KNOWN_HEADERS = {
        "X-Forwarded-For", "X-Real-IP", "True-Client-IP", "X-Original-Forwarded-For",
        "Forwarded", "X-Client-IP", "CF-Connecting-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"
    };

    public String resolve(HttpServletRequest request) {
        if (request == null) return "unknown";
        if (headers != null) {
            for (String h : headers) {
                if (h == null || h.isBlank()) continue;
                String raw = request.getHeader(h.trim());
                if (raw == null || raw.isBlank()) continue;
                String[] parts = raw.split(",");
                int i = (index >= 0 && index < parts.length) ? index : 0;
                String ip = parts[i].trim();
                if (!ip.isEmpty()) return AuditService.normalizeIp(ip);
            }
        }
        return AuditService.normalizeIp(request.getRemoteAddr());
    }

    /** Tanı: tüm bilinen forwarding başlıkları (ham) + remoteAddr + yapılandırma + çözülen IP. */
    public Map<String, Object> debugInfo(HttpServletRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("remote_addr", request != null ? request.getRemoteAddr() : null);
        Map<String, String> hs = new LinkedHashMap<>();
        if (request != null) {
            for (String h : KNOWN_HEADERS) hs.put(h, request.getHeader(h));
        }
        m.put("headers", hs);
        m.put("configured_headers", headers);
        m.put("configured_index", index);
        m.put("resolved", resolve(request));
        return m;
    }
}
