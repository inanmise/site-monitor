package com.sitemonitor.service.keyword;

import com.sitemonitor.util.Msg;

import java.net.URI;
import java.util.Locale;

/**
 * Anahtar kelime izlemesinin GELİŞMİŞ İSTEK kuralları (kw sözleşmesi §5, 2026-11-05) — kayıt anı doğrulaması (400 iletisi istek
 * dilinde, {@link Msg#t}) ve çalışma anı yardımcıları tek yerde. Başlık / gövde / içerik türü / Basic auth doğrulaması HTTP
 * izlemesinin {@link com.sitemonitor.service.http.HttpRequestRules}'ından YENİDEN KULLANILIR (kopya yok); burada yalnız keyword'e
 * özgü olanlar: kabul edilen durum kodları dilbilgisi, son URL kuralı, User-Agent kipi, zaman aşımı aralığı.
 *
 * <p>Düzenli ifade YOK (kullanıcı girdisi): her doğrulama uzunluk sınırından sonra doğrusal tarama.
 */
public final class KeywordRequestRules {

    private KeywordRequestRules() {}

    /** Bugünkü (varsayılan) User-Agent — {@code DEFAULT} kipi. */
    public static final String DEFAULT_USER_AGENT = "SiteMonitor-KeywordMonitor/1.0";
    /**
     * {@code BROWSER} kipi: güncel bir masaüstü Chrome User-Agent dizgesi (sabit; SiteMonitor belirteci YOK). Bot engelleyen WAF'ların
     * arkasındaki sayfalar için — kurum politikasına aykırı olabilir, form bunu söyler.
     */
    public static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public static final String UA_DEFAULT = "DEFAULT";
    public static final String UA_BROWSER = "BROWSER";
    public static final String UA_CUSTOM = "CUSTOM";

    public static final String METHOD_GET = "GET";
    public static final String METHOD_POST = "POST";

    public static final String SCOPE_RAW = KeywordText.SCOPE_RAW;
    public static final String SCOPE_VISIBLE = KeywordText.SCOPE_VISIBLE;

    /** İzleme zaman aşımı aralığı (ms) — kayıtta dışı 400, saklı eski değer çalışmada kırpılır. */
    public static final int MIN_TIMEOUT_MS = 1_000;
    public static final int MAX_TIMEOUT_MS = 60_000;
    /** TÜM kontrolün (her hop + gövde okuması) toplam süre bütçesi — dağıtık işçinin 140 sn sınırının altında kalır. */
    public static final int TOTAL_BUDGET_MS = 120_000;

    public static final int MAX_USER_AGENT = 300;
    public static final int MAX_EXPECTED_STATUS = 100;
    public static final int MAX_STATUS_TOKENS = 20;
    public static final int MAX_FINAL_URL = 2000;
    public static final int MAX_SUBJECT = 200;

    // ── Kanonik değerler (çalışma anı — bilinmeyen değer bugünkü davranışa düşer) ─────────────

    /** Yöntem: {@code POST} ya da {@code GET} (null / bilinmeyen → GET). HEAD YOK — gövde gerekir. */
    public static String method(String raw) {
        return raw != null && METHOD_POST.equalsIgnoreCase(raw.trim()) ? METHOD_POST : METHOD_GET;
    }

    /** UA kipi: {@code BROWSER} | {@code CUSTOM} | {@code DEFAULT} (null / bilinmeyen → DEFAULT). */
    public static String userAgentMode(String raw) {
        if (raw == null) return UA_DEFAULT;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return UA_BROWSER.equals(s) || UA_CUSTOM.equals(s) ? s : UA_DEFAULT;
    }

    /** Kipe göre gönderilecek User-Agent; CUSTOM ama boş / geçersiz değer → varsayılan (kontrol bozulmaz). */
    public static String effectiveUserAgent(String mode, String custom) {
        return switch (userAgentMode(mode)) {
            case UA_BROWSER -> BROWSER_USER_AGENT;
            case UA_CUSTOM -> custom != null && !custom.isBlank() && validateUserAgent(custom) == null ? custom.trim() : DEFAULT_USER_AGENT;
            default -> DEFAULT_USER_AGENT;
        };
    }

    /** Çalışma anı zaman aşımı: null → 10 sn; [1, 60] sn'ye kırpılır (saklı eski 120 sn değer de 60 sn olur). */
    public static int effectiveTimeoutMs(Integer timeoutMs) {
        int t = timeoutMs != null ? timeoutMs : 10_000;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, t));
    }

    // ── Kayıt anı doğrulaması (null = geçerli; aksi hâlde istek dilinde ileti) ─────────────────

    /** Zaman aşımı 1–60 sn. */
    public static String validateTimeoutMs(int ms) {
        if (ms < MIN_TIMEOUT_MS || ms > MAX_TIMEOUT_MS) {
            return Msg.t("Zaman aşımı 1–60 saniye (1000–60000 ms) arasında olmalı.",
                    "The timeout must be between 1 and 60 seconds (1000–60000 ms).");
        }
        return null;
    }

    /** Yöntem yalnız GET ya da POST. */
    public static String validateMethod(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!METHOD_GET.equals(s) && !METHOD_POST.equals(s)) {
            return Msg.t("İstek yöntemi GET ya da POST olmalı (anahtar kelime izlemesi yanıt gövdesine bakar; HEAD kullanılamaz).",
                    "The request method must be GET or POST (keyword monitoring reads the response body; HEAD can't be used).");
        }
        return null;
    }

    /** Eşleşme kapsamı yalnız RAW ya da VISIBLE. */
    public static String validateMatchScope(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!SCOPE_RAW.equals(s) && !SCOPE_VISIBLE.equals(s)) {
            return Msg.t("Eşleşme kapsamı RAW (ham HTML) ya da VISIBLE (görünür metin) olmalı.",
                    "The match scope must be RAW (raw HTML) or VISIBLE (visible text).");
        }
        return null;
    }

    /** UA kipi yalnız DEFAULT / BROWSER / CUSTOM. */
    public static String validateUserAgentMode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (!UA_DEFAULT.equals(s) && !UA_BROWSER.equals(s) && !UA_CUSTOM.equals(s)) {
            return Msg.t("User-Agent kipi DEFAULT, BROWSER ya da CUSTOM olmalı.",
                    "The User-Agent mode must be DEFAULT, BROWSER or CUSTOM.");
        }
        return null;
    }

    /** Özel User-Agent: yazdırılabilir ASCII (0x20–0x7E), CR/LF / kontrol karakteri yok, ≤ {@value #MAX_USER_AGENT}. */
    public static String validateUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return null;
        if (ua.length() > MAX_USER_AGENT) {
            return Msg.t("User-Agent en fazla " + MAX_USER_AGENT + " karakter olabilir.",
                    "The User-Agent can be at most " + MAX_USER_AGENT + " characters.");
        }
        for (int i = 0; i < ua.length(); i++) {
            char c = ua.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return Msg.t("User-Agent yalnız yazdırılabilir ASCII karakterler içerebilir (satır sonu, Türkçe harf ya da kontrol "
                                + "karakteri olamaz).",
                        "The User-Agent may only contain printable ASCII characters (no line breaks, non-ASCII letters or "
                                + "control characters).");
            }
        }
        return null;
    }

    /**
     * Kabul edilen durum kodları ({@code HttpCheckerService.matchesStatus} dilbilgisi): virgülle ayrılmış ≤ {@value
     * #MAX_STATUS_TOKENS} öğe, her biri {@code [1-5]xx} | {@code NNN} | {@code NNN-NNN} (100–599), toplam ≤ {@value
     * #MAX_EXPECTED_STATUS} karakter. Boş → kapalı (geçerli).
     */
    public static String validateExpectedStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        String bad = Msg.t("Kabul edilen durum kodları virgülle ayrılmış öğelerden oluşmalı: 2xx, 200 ya da 200-399 gibi (100–599 "
                        + "arası, en çok " + MAX_STATUS_TOKENS + " öğe, " + MAX_EXPECTED_STATUS + " karakter).",
                "Accepted status codes must be comma-separated items such as 2xx, 200 or 200-399 (100–599, at most "
                        + MAX_STATUS_TOKENS + " items, " + MAX_EXPECTED_STATUS + " characters).");
        if (s.length() > MAX_EXPECTED_STATUS) return bad;
        String[] parts = s.split(",", -1);
        if (parts.length > MAX_STATUS_TOKENS) return bad;
        for (String p : parts) {
            if (!statusToken(p.trim().toLowerCase(Locale.ROOT))) return bad;
        }
        return null;
    }

    private static boolean statusToken(String t) {
        if (t.length() == 3 && t.charAt(0) >= '1' && t.charAt(0) <= '5' && t.charAt(1) == 'x' && t.charAt(2) == 'x') return true;
        int dash = t.indexOf('-');
        if (dash < 0) return code(t) > 0;
        int lo = code(t.substring(0, dash).trim());
        int hi = code(t.substring(dash + 1).trim());
        return lo > 0 && hi > 0 && lo <= hi;
    }

    /** Üç basamaklı 100–599 kodu; değilse -1. */
    private static int code(String t) {
        if (t.length() != 3) return -1;
        for (int i = 0; i < 3; i++) if (t.charAt(i) < '0' || t.charAt(i) > '9') return -1;
        int v = Integer.parseInt(t);
        return v >= 100 && v <= 599 ? v : -1;
    }

    /** Saklama biçimi: boşluklar atılır, küçük harf ({@code 2XX} → {@code 2xx}); boş → null. */
    public static String canonicalExpectedStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        StringBuilder b = new StringBuilder();
        for (String p : raw.trim().split(",", -1)) {
            if (b.length() > 0) b.append(',');
            b.append(p.replace(" ", "").toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    /** Son URL kuralı: mutlak {@code http(s)://…} ya da {@code /} ile başlayan yol; ≤ {@value #MAX_FINAL_URL}, CR/LF yok. */
    public static String validateExpectedFinalUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        String bad = Msg.t("Beklenen son adres mutlak bir http(s) adresi (https://ornek.com/panel) ya da / ile başlayan bir yol "
                        + "(/panel) olmalı, en fazla " + MAX_FINAL_URL + " karakter.",
                "The expected final address must be an absolute http(s) address (https://example.com/panel) or a path starting "
                        + "with / (/panel), at most " + MAX_FINAL_URL + " characters.");
        if (s.length() > MAX_FINAL_URL) return bad;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ' ') return bad;
        }
        if (s.startsWith("/")) {
            if (s.startsWith("//")) return bad;   // protokol-göreli = başka host — açık mutlak adres istenir
            try { URI.create("http://h" + s); } catch (Exception e) { return bad; }
            return null;
        }
        try {
            URI u = URI.create(s);
            String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(sch) && !"https".equals(sch)) || u.getHost() == null || u.getHost().isBlank()) return bad;
        } catch (Exception e) {
            return bad;
        }
        return null;
    }

    /** Posta konusu şablonu: ≤ {@value #MAX_SUBJECT}, CR/LF / kontrol karakteri yok. */
    public static String validateMailSubjectTemplate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > MAX_SUBJECT) {
            return Msg.t("Posta konusu şablonu en fazla " + MAX_SUBJECT + " karakter olabilir.",
                    "The mail subject template can be at most " + MAX_SUBJECT + " characters.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c)) {
                return Msg.t("Posta konusu şablonu satır sonu ya da kontrol karakteri içeremez.",
                        "The mail subject template must not contain line breaks or control characters.");
            }
        }
        return null;
    }

    // ── Son URL karşılaştırması (çalışma anı) ───────────────────────────────────────────────

    /**
     * Gerçek son istek URI'si beklenenle BAŞLIYOR mu (önek). İki taraf da şema + host küçük harf, varsayılan port düşer, parça
     * ({@code #}) düşer. {@code /} ile başlayan beklenen değer izlemenin URL'sinin kaynağına (şema + host + port) göre çözülür.
     * Çözülemeyen / boş değer → {@code true} (kural uygulanamaz → kontrol bugünkü gibi; kayıtta zaten doğrulandı).
     */
    public static boolean finalUrlMatches(URI actual, String expected, String monitorUrl) {
        if (expected == null || expected.isBlank()) return true;
        if (actual == null) return false;
        String exp = expected.trim();
        String expNorm;
        if (exp.startsWith("/") && !exp.startsWith("//")) {
            URI base = parse(monitorUrl);
            if (base == null || base.getHost() == null) return true;
            expNorm = origin(base) + stripFragment(exp);
        } else {
            URI e = parse(exp);
            if (e == null || e.getHost() == null) return true;
            expNorm = canonical(e);
        }
        return canonical(actual).startsWith(expNorm);
    }

    /** Karşılaştırma biçimi: {@code şema://host[:port]yol[?sorgu]} — şema / host küçük, varsayılan port yok, parça yok. */
    static String canonical(URI u) {
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return origin(u) + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
    }

    /**
     * İzleme / test URL'sinin kaynağı (şema + host + port; şema / host küçük harf, varsayılan port düşer — {@code https://a} =
     * {@code https://a:443}). Çözülemeyen / host'suz değer → {@code null}. Kayıtlı sırların (Basic auth parolası, özel başlıklar)
     * yalnız izlemenin kendi kaynağına gönderilmesi kuralı bunu kullanır (kw son inceleme Y-1 / O-1 / D-8).
     */
    public static String originOf(String url) {
        URI u = parse(url);
        return u == null || u.getHost() == null || u.getHost().isBlank() ? null : origin(u);
    }

    /** İki URL aynı kaynakta mı ({@link #originOf}); biri çözülemezse {@code false} (güvenli taraf). */
    public static boolean sameOrigin(String a, String b) {
        String oa = originOf(a);
        return oa != null && oa.equals(originOf(b));
    }

    /** URI sürümü — yönlendirme hop'u ile ilk istek aynı kaynakta mı (D-8: kimlik bilgisi yalnız aynı kaynağa). */
    public static boolean sameOrigin(URI a, URI b) {
        return a != null && b != null && a.getHost() != null && b.getHost() != null && origin(a).equals(origin(b));
    }

    private static String origin(URI u) {
        String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        int port = u.getPort();
        boolean dflt = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (dflt ? "" : ":" + port);
    }

    private static String stripFragment(String s) {
        int h = s.indexOf('#');
        return h >= 0 ? s.substring(0, h) : s;
    }

    private static URI parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return URI.create(s.trim().replace("{timestamp}", "0"));
        } catch (Exception e) {
            return null;
        }
    }
}
