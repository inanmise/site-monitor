package com.sitemonitor.service.http.diagnose;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP uçtan uca tanılamasının BULGU KATALOĞU ve hüküm (verdict) kuralı (2026-10-02) — sunucu ile arayüzün PAYLAŞTIĞI
 * tek liste. Arayüz kodu i18n anahtarına dinamik çevirir ({@code httpdx.finding.<KOD>.title|body}); bu yüzden kod
 * eklemek = TR + EN metnini AYNI değişiklikte eklemek ({@code HttpDiagFindingsI18nGateTest} kırılır).
 *
 * <p>Önem: {@code OK} = info; {@code SLOW}, {@code CLIENT_MISMATCH} = warn; {@code AUTH_REQUIRED} beklenen durum
 * kodunun DIŞINDAYSA fail, içindeyse info; {@code BODY_TIMEOUT} izlemenin kararı yine durum koduysa (JSON doğrulaması
 * yok, yol "ok") warn — izlemenin kendisi o durumda düşmez; diğer hatalar fail.
 *
 * <p>Hüküm önceliği: {@code PATH_DIFFERS} / {@code BOTH_PATHS_FAIL} → izlemenin yolundaki ilk fail → ilk warn
 * (önce izlemenin yolu) → {@code OK}.
 */
public final class HttpDiagFindings {

    private HttpDiagFindings() {}

    public static final String OK = "OK";
    public static final String SLOW = "SLOW";
    public static final String DNS_FAIL = "DNS_FAIL";
    public static final String TCP_REFUSED = "TCP_REFUSED";
    public static final String TCP_TIMEOUT = "TCP_TIMEOUT";
    public static final String PROXY_CONNECT_FAIL = "PROXY_CONNECT_FAIL";
    public static final String PROXY_AUTH_REQUIRED = "PROXY_AUTH_REQUIRED";
    public static final String PROXY_TUNNEL_REFUSED = "PROXY_TUNNEL_REFUSED";
    public static final String TLS_HANDSHAKE_FAIL = "TLS_HANDSHAKE_FAIL";
    public static final String TLS_UNTRUSTED = "TLS_UNTRUSTED";
    public static final String TLS_HOSTNAME_MISMATCH = "TLS_HOSTNAME_MISMATCH";
    public static final String TLS_EXPIRED = "TLS_EXPIRED";
    public static final String RESPONSE_TIMEOUT = "RESPONSE_TIMEOUT";
    public static final String BODY_TIMEOUT = "BODY_TIMEOUT";
    public static final String STATUS_MISMATCH = "STATUS_MISMATCH";
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String REDIRECT_LOOP = "REDIRECT_LOOP";
    public static final String SSRF_BLOCKED = "SSRF_BLOCKED";
    public static final String JSON_ASSERTION_FAIL = "JSON_ASSERTION_FAIL";
    public static final String PATH_DIFFERS = "PATH_DIFFERS";
    public static final String BOTH_PATHS_FAIL = "BOTH_PATHS_FAIL";
    public static final String CLIENT_MISMATCH = "CLIENT_MISMATCH";

    /** Sözleşmedeki TAM liste — i18n kapısı bunu okur. */
    public static final List<String> CODES = List.of(
            OK, SLOW, DNS_FAIL, TCP_REFUSED, TCP_TIMEOUT, PROXY_CONNECT_FAIL, PROXY_AUTH_REQUIRED,
            PROXY_TUNNEL_REFUSED, TLS_HANDSHAKE_FAIL, TLS_UNTRUSTED, TLS_HOSTNAME_MISMATCH, TLS_EXPIRED,
            RESPONSE_TIMEOUT, BODY_TIMEOUT, STATUS_MISMATCH, AUTH_REQUIRED, REDIRECT_LOOP, SSRF_BLOCKED,
            JSON_ASSERTION_FAIL, PATH_DIFFERS, BOTH_PATHS_FAIL, CLIENT_MISMATCH);

    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String FAIL = "fail";

    /** {@code {code, severity, path, params}} — params ADLI (ön yüz {@code {status}} gibi yer tutucularla doldurur).
     *  Keyword uçtan uca tanılaması da (2026-10-04) aynı biçimi kullanır — bu yüzden public. */
    public static Map<String, Object> finding(String code, String severity, String path, Map<String, Object> params) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("code", code);
        f.put("severity", severity);
        f.put("path", path);
        f.put("params", params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params));
        return f;
    }

    /**
     * Hüküm: {@code {status, code, params, failed_step, path}}.
     *
     * @param findings       tüm bulgular (karşılaştırma bulguları başta)
     * @param monitorOutcome izlemenin yolunun sonucu ({@code ok|fail})
     * @param monitorFailedStep izlemenin yolunun düştüğü adım (fail hükümlerinde döner)
     */
    static Map<String, Object> verdict(List<Map<String, Object>> findings, String monitorOutcome, String monitorFailedStep) {
        Map<String, Object> pick = null;
        String status = null;
        for (Map<String, Object> f : findings) {
            if (PATH_DIFFERS.equals(f.get("code")) || BOTH_PATHS_FAIL.equals(f.get("code"))) { pick = f; status = FAIL; break; }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if ("monitor".equals(f.get("path")) && FAIL.equals(f.get("severity"))) { pick = f; status = FAIL; break; }
            }
        }
        if (pick == null) {
            List<Map<String, Object>> ordered = new ArrayList<>();
            for (Map<String, Object> f : findings) if ("monitor".equals(f.get("path"))) ordered.add(f);
            for (Map<String, Object> f : findings) if (!"monitor".equals(f.get("path"))) ordered.add(f);
            for (Map<String, Object> f : ordered) {
                if (WARN.equals(f.get("severity"))) { pick = f; status = WARN; break; }
            }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if (OK.equals(f.get("code")) && "monitor".equals(f.get("path"))) { pick = f; status = "ok"; break; }
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        if (pick == null) {
            // Savunma: yolun sonucu var ama bulgu üretilmemiş (beklenmez) — sonuca göre en yakın kod.
            boolean ok = "ok".equals(monitorOutcome);
            v.put("status", ok ? "ok" : FAIL);
            v.put("code", ok ? OK : RESPONSE_TIMEOUT);
            v.put("params", new LinkedHashMap<>());
            v.put("failed_step", ok ? null : monitorFailedStep);
            v.put("path", "monitor");
            return v;
        }
        v.put("status", status);
        v.put("code", pick.get("code"));
        v.put("params", pick.get("params"));
        v.put("failed_step", FAIL.equals(status) ? monitorFailedStep : null);
        v.put("path", pick.get("path") != null ? pick.get("path") : "monitor");
        return v;
    }
}
