package com.sitemonitor.service.diagnose;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ping / Port / DNS UÇTAN UCA TANILAMASININ ortak BULGU KATALOĞU ve hüküm kuralı (2026-10-05, kullanıcı isteği:
 * "tanılama ve teşhisi eksik olan … izlemeler için tanılama ekleyelim"). Sunucu ile arayüzün PAYLAŞTIĞI tek liste:
 * arayüz kodu i18n anahtarına dinamik çevirir ({@code ndx.finding.<KOD>.title|body}, ADLI parametrelerle); bu yüzden kod
 * eklemek = TR + EN metnini AYNI değişiklikte eklemek ({@code NetDiagFindingsI18nGateTest} kırılır).
 *
 * <p>Bulgular düzyazı DEĞİL koddur. Önem: {@code fail} = izlemenin kontrolü bu nedenle düşer; {@code warn} = kontrol
 * geçse de dikkat ister (ya da ortam kısıtı); {@code info} = bilgi.
 *
 * <p>Hüküm: {@code PATH_DIFFERS} → ilk fail → ilk warn → türün OK kodu ({@link #PING_OK} / {@link #PORT_OK} /
 * {@link #DNS_OK}); hiçbiri yoksa {@link #INCONCLUSIVE}. Bulgular önem sırasına dizilir (en önemli ilk) — dizilim kararlı:
 * aynı önemdekiler üretildikleri sırada kalır (servisler en açıklayıcı bulguyu önce üretir).
 */
public final class NetDiagFindings {

    private NetDiagFindings() {}

    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String FAIL = "fail";

    // ── Ortak ──
    public static final String POLICY_BLOCKED = "POLICY_BLOCKED";
    public static final String DNS_FAILED = "DNS_FAILED";
    public static final String NO_ADDRESS_FOR_IP_VERSION = "NO_ADDRESS_FOR_IP_VERSION";
    public static final String PROXY_NOT_APPLICABLE = "PROXY_NOT_APPLICABLE";
    public static final String CLIENT_MISMATCH = "CLIENT_MISMATCH";
    public static final String RUN_TIME_LIMIT = "RUN_TIME_LIMIT";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";

    // ── Ping ──
    public static final String PING_OK = "PING_OK";
    public static final String ICMP_UNAVAILABLE_HERE = "ICMP_UNAVAILABLE_HERE";
    public static final String ICMP_FILTERED_HOST_ALIVE = "ICMP_FILTERED_HOST_ALIVE";
    public static final String HOST_UNREACHABLE = "HOST_UNREACHABLE";
    public static final String ALL_PACKETS_LOST = "ALL_PACKETS_LOST";
    public static final String PARTIAL_LOSS = "PARTIAL_LOSS";
    public static final String HIGH_RTT = "HIGH_RTT";
    public static final String TRACEROUTE_UNAVAILABLE = "TRACEROUTE_UNAVAILABLE";

    // ── Port ──
    public static final String PORT_OK = "PORT_OK";
    public static final String CONNECT_REFUSED = "CONNECT_REFUSED";
    public static final String CONNECT_TIMEOUT_FILTERED = "CONNECT_TIMEOUT_FILTERED";
    public static final String NETWORK_UNREACHABLE = "NETWORK_UNREACHABLE";
    public static final String PROXY_UNREACHABLE = "PROXY_UNREACHABLE";
    public static final String PROXY_PORT_NOT_ALLOWED = "PROXY_PORT_NOT_ALLOWED";
    public static final String PROXY_REFUSED = "PROXY_REFUSED";
    public static final String TLS_HANDSHAKE_FAILED = "TLS_HANDSHAKE_FAILED";
    public static final String TLS_UNTRUSTED = "TLS_UNTRUSTED";
    public static final String TLS_HOSTNAME_MISMATCH = "TLS_HOSTNAME_MISMATCH";
    public static final String CERT_EXPIRED = "CERT_EXPIRED";
    public static final String CERT_EXPIRES_SOON = "CERT_EXPIRES_SOON";
    public static final String HTTP_STATUS_MISMATCH = "HTTP_STATUS_MISMATCH";
    public static final String HTTP_BAD_RESPONSE = "HTTP_BAD_RESPONSE";
    public static final String BANNER_MISMATCH = "BANNER_MISMATCH";
    public static final String BANNER_EMPTY = "BANNER_EMPTY";
    public static final String UDP_NO_REPLY = "UDP_NO_REPLY";
    public static final String UDP_PORT_UNREACHABLE = "UDP_PORT_UNREACHABLE";
    public static final String PATH_DIFFERS = "PATH_DIFFERS";
    public static final String SOME_IPS_DOWN = "SOME_IPS_DOWN";
    public static final String SLOW_RESPONSE = "SLOW_RESPONSE";

    // ── DNS ──
    public static final String DNS_OK = "DNS_OK";
    public static final String NXDOMAIN_AUTHORITATIVE = "NXDOMAIN_AUTHORITATIVE";
    public static final String NXDOMAIN_RESOLVER_ONLY = "NXDOMAIN_RESOLVER_ONLY";
    public static final String SERVFAIL_DNSSEC = "SERVFAIL_DNSSEC";
    public static final String SERVFAIL = "SERVFAIL";
    public static final String RESOLVER_REFUSED = "RESOLVER_REFUSED";
    public static final String RESOLVER_TIMEOUT = "RESOLVER_TIMEOUT";
    public static final String RESOLVERS_DISAGREE = "RESOLVERS_DISAGREE";
    public static final String AUTH_RESOLVER_MISMATCH = "AUTH_RESOLVER_MISMATCH";
    public static final String LAME_DELEGATION = "LAME_DELEGATION";
    public static final String AUTH_UNREACHABLE = "AUTH_UNREACHABLE";
    public static final String ZONE_NOT_FOUND = "ZONE_NOT_FOUND";
    public static final String NO_RECORD_OF_TYPE = "NO_RECORD_OF_TYPE";
    public static final String EXPECTED_MISMATCH = "EXPECTED_MISMATCH";
    public static final String TRUNCATED_UDP = "TRUNCATED_UDP";
    public static final String SLOW_RESOLVER = "SLOW_RESOLVER";

    /** TAM liste — i18n kapısı bunu okur (arayüz {@code netDiagnoseModel.FINDING_CODES} ile aynı). */
    public static final List<String> CODES = List.of(
            POLICY_BLOCKED, DNS_FAILED, NO_ADDRESS_FOR_IP_VERSION, PROXY_NOT_APPLICABLE, CLIENT_MISMATCH, RUN_TIME_LIMIT,
            INCONCLUSIVE,
            PING_OK, ICMP_UNAVAILABLE_HERE, ICMP_FILTERED_HOST_ALIVE, HOST_UNREACHABLE, ALL_PACKETS_LOST, PARTIAL_LOSS,
            HIGH_RTT, TRACEROUTE_UNAVAILABLE,
            PORT_OK, CONNECT_REFUSED, CONNECT_TIMEOUT_FILTERED, NETWORK_UNREACHABLE, PROXY_UNREACHABLE,
            PROXY_PORT_NOT_ALLOWED, PROXY_REFUSED, TLS_HANDSHAKE_FAILED, TLS_UNTRUSTED, TLS_HOSTNAME_MISMATCH,
            CERT_EXPIRED, CERT_EXPIRES_SOON, HTTP_STATUS_MISMATCH, HTTP_BAD_RESPONSE, BANNER_MISMATCH, BANNER_EMPTY,
            UDP_NO_REPLY, UDP_PORT_UNREACHABLE, PATH_DIFFERS, SOME_IPS_DOWN, SLOW_RESPONSE,
            DNS_OK, NXDOMAIN_AUTHORITATIVE, NXDOMAIN_RESOLVER_ONLY, SERVFAIL_DNSSEC, SERVFAIL, RESOLVER_REFUSED,
            RESOLVER_TIMEOUT, RESOLVERS_DISAGREE, AUTH_RESOLVER_MISMATCH, LAME_DELEGATION, AUTH_UNREACHABLE,
            ZONE_NOT_FOUND, NO_RECORD_OF_TYPE, EXPECTED_MISMATCH, TRUNCATED_UDP, SLOW_RESOLVER);

    /** Adım anahtarları — arayüz {@code ndx.step.<anahtar>} çevirir (i18n kapısı bunu da okur). */
    public static final List<String> STEP_KEYS = List.of(
            "policy", "dns", "icmp", "tcp_alive", "traceroute",
            "connect", "proxy_tunnel", "tls", "http", "banner", "udp",
            "resolvers", "zone", "authoritative", "dnssec", "compare");

    /** {@code {code, severity, path, params}} — params ADLI (arayüz {@code {ms}} gibi yer tutucularla doldurur). */
    public static Map<String, Object> finding(String code, String severity, Map<String, Object> params) {
        return finding(code, severity, null, params);
    }

    public static Map<String, Object> finding(String code, String severity, String path, Map<String, Object> params) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("code", code);
        f.put("severity", severity);
        f.put("path", path);
        f.put("params", params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params));
        return f;
    }

    /** Adlı parametre haritası: {@code params("ms", 12, "host", "a.example.test")} — null değerler de korunur. */
    public static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static int rank(Object severity) {
        if (FAIL.equals(severity)) return 0;
        if (WARN.equals(severity)) return 1;
        return 2;
    }

    /** Önem sırasına göre kararlı dizilim (fail → warn → info); aynı (kod, yol) ikinci kez eklenmez. */
    public static List<Map<String, Object>> ordered(List<Map<String, Object>> findings) {
        List<Map<String, Object>> uniq = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> f : findings) {
            if (f == null) continue;
            String k = f.get("code") + "|" + f.get("path");
            if (seen.add(k)) uniq.add(f);
        }
        List<Map<String, Object>> out = new ArrayList<>(uniq);
        out.sort((a, b) -> Integer.compare(rank(a.get("severity")), rank(b.get("severity"))));
        return out;
    }

    /**
     * Hüküm {@code {code, status, params, path}}: {@code PATH_DIFFERS} → ilk fail → ilk warn → OK kodu → {@link #INCONCLUSIVE}.
     *
     * @param findings önem sırasına DİZİLMİŞ bulgular ({@link #ordered})
     * @param okCode   türün başarı kodu
     */
    public static Map<String, Object> verdict(List<Map<String, Object>> findings, String okCode) {
        Map<String, Object> pick = null;
        String status = null;
        for (Map<String, Object> f : findings) {
            if (PATH_DIFFERS.equals(f.get("code"))) { pick = f; status = FAIL; break; }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if (FAIL.equals(f.get("severity"))) { pick = f; status = FAIL; break; }
            }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if (WARN.equals(f.get("severity"))) { pick = f; status = WARN; break; }
            }
        }
        if (pick == null) {
            for (Map<String, Object> f : findings) {
                if (okCode.equals(f.get("code"))) { pick = f; status = "ok"; break; }
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        if (pick == null) {
            v.put("code", INCONCLUSIVE);
            v.put("status", WARN);
            v.put("params", new LinkedHashMap<>());
            v.put("path", null);
            return v;
        }
        v.put("code", pick.get("code"));
        v.put("status", status);
        v.put("params", pick.get("params"));
        v.put("path", pick.get("path"));
        return v;
    }
}
