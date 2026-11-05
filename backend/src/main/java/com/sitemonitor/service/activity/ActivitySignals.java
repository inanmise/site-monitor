package com.sitemonitor.service.activity;

import com.sitemonitor.service.failure.CheckFailureReason;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Aktivite satırından türetilen SİNYALLER (2026-11-05, aktivite zenginleştirmesi D + F): hata nedeni anahtarı, nedenin
 * kaba sınıfı (DNS / ağ / TLS / vekil) ve hedefin host'u. Saf, durumsuz; zaman çizelgesi ve "aynı anda ne oldu?" ucunun
 * TEK kuralı.
 *
 * <p><b>Neden anahtarı:</b> satırın {@code failure_reason} kodu (kontrol satırındaki kodun aynısı); bu kolon 2026-11-05'te
 * eklendi — daha eski satırlarda boştur, onlarda hata sınıfı {@code class:<ERROR_CLASS>} biçiminde kullanılır (liste
 * süzgecinin {@code reason=class:…} değeriyle aynı biçim). İkisi de yoksa {@code null}.
 */
public final class ActivitySignals {

    private ActivitySignals() {}

    /** Kaba neden sınıfları — şüpheli neden kodlarının ({@code *_WIDE}) temeli. */
    public static final String DNS = "DNS", NETWORK = "NETWORK", TLS = "TLS", PROXY = "PROXY", OTHER = "OTHER";

    /** Keyword sınıflandırıcısının ağ kökenli kodları ({@code KeywordFailureClassifier}). */
    private static final Set<String> KW_NETWORK = Set.of("TIMEOUT_CONNECT", "TIMEOUT_READ", "CONNECTION_REFUSED",
            "CONNECTION_RESET", "HOST_UNREACHABLE");
    private static final Set<String> KW_TLS = Set.of("TLS_HANDSHAKE", "TLS_CERT");
    /** Veritabanı sorgu izlemesinin ağ kökenli kodları ({@code DbQueryErrors.NETWORK_CLASS}; DNS ayrı). */
    private static final Set<String> DB_NETWORK = Set.of("DB_UNREACHABLE", "DB_TIMEOUT");

    /** Neden anahtarı: kod; yoksa {@code class:<ERROR_CLASS>}; ikisi de yoksa {@code null}. */
    public static String reasonKey(String failureReason, String errorClass) {
        if (failureReason != null && !failureReason.isBlank()) return failureReason.trim();
        if (errorClass != null && !errorClass.isBlank()) return ActivityFilter.CLASS_PREFIX + errorClass.trim().toUpperCase(Locale.ROOT);
        return null;
    }

    /**
     * Neden → kaba sınıf. Önce kod (izleme kontrolü, keyword, veritabanı sorgusu kataloglarının ortak kodları), yoksa eski
     * satırın hata sınıfı (sertifika denetimi: NETWORK / DNS / SSL / BLOCKED). Bilinmeyen / boş → {@link #OTHER}.
     */
    public static String classOf(String failureReason, String errorClass) {
        String r = failureReason == null ? null : failureReason.trim().toUpperCase(Locale.ROOT);
        if (r != null && !r.isEmpty()) {
            if (r.equals("PROXY_REFUSED") || r.equals("PROXY_ERROR") || r.equals("PROXY")) return PROXY;
            if (r.equals("DNS") || r.equals("DB_DNS")) return DNS;
            if (KW_NETWORK.contains(r) || DB_NETWORK.contains(r)) return NETWORK;
            if (KW_TLS.contains(r)) return TLS;
            CheckFailureReason known = null;
            for (CheckFailureReason c : CheckFailureReason.values()) if (c.name().equals(r)) { known = c; break; }
            if (known != null) {
                switch (known.phase) {
                    case DNS: return DNS;
                    case TLS: return TLS;
                    case CONNECT: return NETWORK;   // vekil kodları yukarıda
                    case ICMP: return NETWORK;
                    default: break;
                }
                if (known == CheckFailureReason.READ_TIMEOUT || known == CheckFailureReason.CONNECTION_RESET
                        || known == CheckFailureReason.UDP_NO_REPLY) return NETWORK;
            }
            return OTHER;
        }
        String c = errorClass == null ? "" : errorClass.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "DNS" -> DNS;
            case "NETWORK", "TIMEOUT" -> NETWORK;
            case "SSL", "TLS" -> TLS;
            case "PROXY" -> PROXY;
            default -> OTHER;
        };
    }

    /**
     * Hedefin host'u (küçük harf) — aynı host'ta düşen izlemeleri bulmak için. Biçimler (yazıcıların hedefleri): URL
     * (HTTP / keyword / sayfa / sayfa hızı), {@code host:port} (port, erişilebilirlik), {@code alan_adı TİP} (DNS), düz
     * host / alan adı (ping, sertifika, alan adı). Ad anahtarlı türler (sentetik, veritabanı sorgusu) host taşımaz → null.
     */
    public static String hostOf(String type, String target) {
        if (target == null || target.isBlank()) return null;
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (t.equals("SCRIPTED") || t.equals("DBQUERY")) return null;
        String v = target.trim();
        if (v.length() > 500) v = v.substring(0, 500);
        if (v.contains("://")) {
            try {
                String h = URI.create(v.replace(" ", "%20")).getHost();
                if (h != null && !h.isBlank()) return strip(h);
            } catch (Exception ignored) { /* aşağıdaki el ile ayrıştırmaya düş */ }
            v = v.substring(v.indexOf("://") + 3);
            int slash = indexOfAny(v, "/?#");
            if (slash >= 0) v = v.substring(0, slash);
            int at = v.lastIndexOf('@');
            if (at >= 0) v = v.substring(at + 1);
        }
        int sp = v.indexOf(' ');
        if (sp > 0) v = v.substring(0, sp);                      // DNS: "alan_adı A"
        if (v.startsWith("[")) {                                   // [IPv6]:port
            int close = v.indexOf(']');
            return close > 1 ? strip(v.substring(1, close)) : null;
        }
        int colon = v.indexOf(':');
        if (colon > 0 && colon == v.lastIndexOf(':')) v = v.substring(0, colon);   // host:port (tek iki nokta — IPv6 değil)
        return v.isBlank() ? null : strip(v);
    }

    private static String strip(String h) {
        String x = h.toLowerCase(Locale.ROOT);
        if (x.startsWith("[") && x.endsWith("]")) x = x.substring(1, x.length() - 1);
        if (x.endsWith(".")) x = x.substring(0, x.length() - 1);
        return x.isEmpty() ? null : x;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }
}
