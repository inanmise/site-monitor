package com.sitemonitor.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sistem Bakım Modu sinyalinin TEK tanımı (2026-10-02, kullanıcı kararı: bakım süresince yalnız global yöneticiler giriş
 * yapar ve içeride kalır). {@link AccountInactive} ile aynı sözleşme — istemci ({@code api/client.js}) gövdeyi
 * {@code code} / {@code error_code} alanından tanır:
 *
 * <ul>
 *   <li>{@code POST /api/login} — kimlik bilgisi DOĞRULANDIKTAN sonra global yönetici değilse <b>403</b> (yanlış parolada
 *       genel 401 değişmez: kullanıcı adı numaralandırması yok).</li>
 *   <li>{@code AuthInterceptor} — bakım başladığında canlı oturumu / remember-me çerezi olan global yönetici olmayan
 *       kullanıcının her {@code /api} isteği <b>401</b> (istemci geri sayım penceresini açar, sonra
 *       {@code /?session=maintenance}).</li>
 * </ul>
 * {@code maintenance} alanı pencere bilgisidir (saatler, mesaj, iletişim — kimlik/sayaç YOK; public uçla aynı içerik).
 */
public final class SystemMaintenanceSignal {

    private SystemMaintenanceSignal() {}

    /** Makine kodu — istemci, denetim nedeni ve giriş yanıtı aynı adı kullanır. */
    public static final String CODE = "MAINTENANCE";

    /** Yanıt gövdesi: {@code {success:false, code, error_code, error, maintenance}}. */
    public static Map<String, Object> body(String message, Map<String, Object> window) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", CODE);
        m.put("error_code", CODE);
        m.put("error", message);
        m.put("maintenance", window == null ? Map.of("state", "active") : window);
        return m;
    }
}
