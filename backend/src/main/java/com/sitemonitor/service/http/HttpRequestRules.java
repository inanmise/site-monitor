package com.sitemonitor.service.http;

import com.sitemonitor.util.Msg;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP izlemesinin gelişmiş istek alanları için KURALLAR — kayıt anı doğrulaması (400 iletisi istek dilinde,
 * {@link Msg#t}) ve çalışma anı ayrıştırması tek yerde (2026-10-01, onaylı öneri 9).
 *
 * <p>Başlık biçimi Anahtar Kelime / Sayfa Hızı izlemeleriyle aynıdır: satır başına {@code Ad: değer}, boş satır ve
 * {@code #} yorumu atlanır. Fark: o türler bozuk satırı SESSİZCE atlıyor; burada kayıt anında açık bir 400 döner
 * (kullanıcı neyin gitmeyeceğini kaydederken öğrenir). Çalışma anı ayrıştırması yine savunmacıdır — doğrulamadan
 * geçmemiş eski/elle yazılmış bir değer kontrolü patlatmaz, satır atlanır.
 */
public final class HttpRequestRules {

    private HttpRequestRules() {}

    /** POST gövdesi tavanı (UTF-8 bayt). */
    public static final int MAX_BODY_BYTES = 64 * 1024;
    /** Özel başlık sayısı tavanı (Sayfa Hızı ile aynı). */
    public static final int MAX_HEADERS = 20;
    /** Tek başlık değerinin tavanı (karakter). */
    public static final int MAX_HEADER_VALUE = 4096;
    public static final int MAX_CONTENT_TYPE = 100;
    public static final int MAX_JSON_PATH = 300;
    public static final int MAX_JSON_EXPECTED = 500;
    public static final int MAX_BASIC_USER = 255;
    public static final int MAX_BASIC_PASS = 1024;
    /** Yavaş yanıt eşiği aralığı (ms) — form da aynı aralığı uygular. Varsayılan 3000 (Anahtar Kelime ile aynı). */
    public static final int MIN_SLOW_MS = 100;
    public static final int MAX_SLOW_MS = 300_000;
    public static final int DEFAULT_SLOW_MS = 3000;
    /** JSON doğrulamasında okunacak yanıt gövdesi tavanı — Anahtar Kelime denetleyicisiyle aynı (2 MB). */
    public static final int MAX_RESPONSE_BYTES = 2_000_000;
    /** Gövde gönderilip içerik türü boş bırakıldıysa. */
    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    /**
     * İstemcinin (java.net.http) kendisinin yönettiği ya da isteği bozacak başlıklar — elle ayarlanamaz.
     * {@code Host}/{@code Content-Length}/{@code Connection}/{@code Expect}/{@code Upgrade} JDK'nın kısıtlı listesi;
     * kalanlar çerçeveleme/bağlantı başlıkları (istek kaçakçılığı yüzeyi).
     */
    static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade",
            "transfer-encoding", "te", "trailer", "keep-alive", "proxy-connection", "http2-settings");

    /** RFC 9110 token. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    // ── Çalışma anı ──────────────────────────────────────────────────────────────────────────

    /** {@code Ad: değer} satırları → sıralı harita; geçersiz satır atlanır (savunma — doğrulama kayıtta yapıldı). */
    public static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int c = s.indexOf(':');
            if (c <= 0) continue;
            String name = s.substring(0, c).trim();
            String value = s.substring(c + 1).trim();
            if (!TOKEN.matcher(name).matches() || hasCtl(value) || RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) continue;
            out.put(name, value);
            if (out.size() >= MAX_HEADERS) break;
        }
        return out;
    }

    /** HTTP Basic auth başlık değeri; kullanıcı adı boşsa {@code null} (parola tek başına anlamsız). */
    public static String basicAuthHeader(String user, String plainPassword) {
        if (user == null || user.isBlank()) return null;
        String raw = user + ":" + (plainPassword == null ? "" : plainPassword);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ── Kayıt anı doğrulaması (null = geçerli; aksi hâlde istek dilinde ileti) ─────────────────

    /** Özel başlık metni. */
    public static String validateHeaders(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] lines = raw.split("\\r?\\n", -1);
        int count = 0;
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            String s = lines[i].trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int c = s.indexOf(':');
            if (c <= 0) {
                return Msg.t("Özel başlıklar, satır " + no + ": \"Ad: değer\" biçiminde olmalı.",
                        "Custom headers, line " + no + ": must be in the form \"Name: value\".");
            }
            String name = s.substring(0, c).trim();
            String value = s.substring(c + 1).trim();
            if (hasCtl(name) || hasCtl(value)) {
                return Msg.t("Özel başlıklar, satır " + no + ": satır sonu (CR/LF) ya da kontrol karakteri içeremez.",
                        "Custom headers, line " + no + ": must not contain line breaks (CR/LF) or control characters.");
            }
            if (!TOKEN.matcher(name).matches()) {
                return Msg.t("Özel başlıklar, satır " + no + ": \"" + name + "\" geçerli bir başlık adı değil.",
                        "Custom headers, line " + no + ": \"" + name + "\" is not a valid header name.");
            }
            if (RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                return Msg.t("Özel başlıklar, satır " + no + ": " + name + " başlığı elle ayarlanamaz (istemci yönetir).",
                        "Custom headers, line " + no + ": the " + name + " header cannot be set manually (the client manages it).");
            }
            if (value.length() > MAX_HEADER_VALUE) {
                return Msg.t("Özel başlıklar, satır " + no + ": değer en fazla " + MAX_HEADER_VALUE + " karakter olabilir.",
                        "Custom headers, line " + no + ": the value can be at most " + MAX_HEADER_VALUE + " characters.");
            }
            if (++count > MAX_HEADERS) {
                return Msg.t("En fazla " + MAX_HEADERS + " özel başlık tanımlanabilir.",
                        "At most " + MAX_HEADERS + " custom headers can be defined.");
            }
        }
        return null;
    }

    /** POST gövdesi boyutu. */
    public static String validateBody(String body) {
        if (body == null || body.isEmpty()) return null;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return Msg.t("İstek gövdesi en fazla " + (MAX_BODY_BYTES / 1024) + " KB olabilir.",
                    "The request body can be at most " + (MAX_BODY_BYTES / 1024) + " KB.");
        }
        return null;
    }

    /** İçerik türü: {@code tür/alt-tür[; parametre]}, kontrol karakteri yok. */
    public static String validateContentType(String ct) {
        if (ct == null || ct.isBlank()) return null;
        String s = ct.trim();
        if (s.length() > MAX_CONTENT_TYPE || hasCtl(s) || !s.matches("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+/[!#$%&'*+\\-.^_`|~0-9A-Za-z]+(\\s*;.*)?")) {
            return Msg.t("İçerik türü \"tür/alt-tür\" biçiminde olmalı (ör. application/json), en fazla " + MAX_CONTENT_TYPE + " karakter.",
                    "The content type must look like \"type/subtype\" (e.g. application/json), at most " + MAX_CONTENT_TYPE + " characters.");
        }
        return null;
    }

    /** Basic auth kullanıcı adı: iki nokta (RFC 7617) ve kontrol karakteri yok. */
    public static String validateBasicAuthUser(String user) {
        if (user == null || user.isBlank()) return null;
        if (user.length() > MAX_BASIC_USER || hasCtl(user) || user.indexOf(':') >= 0) {
            return Msg.t("Basic auth kullanıcı adı iki nokta (:) ya da kontrol karakteri içeremez, en fazla " + MAX_BASIC_USER + " karakter.",
                    "The Basic auth username must not contain a colon (:) or control characters, at most " + MAX_BASIC_USER + " characters.");
        }
        return null;
    }

    /** Basic auth parolası: satır sonu yok, uzunluk tavanı. */
    public static String validateBasicAuthPass(String pass) {
        if (pass == null || pass.isEmpty()) return null;
        if (pass.length() > MAX_BASIC_PASS || pass.indexOf('\r') >= 0 || pass.indexOf('\n') >= 0) {
            return Msg.t("Basic auth parolası satır sonu içeremez, en fazla " + MAX_BASIC_PASS + " karakter.",
                    "The Basic auth password must not contain line breaks, at most " + MAX_BASIC_PASS + " characters.");
        }
        return null;
    }

    /** JSON yolu sözdizimi + uzunluk. */
    public static String validateJsonPath(String path) {
        if (path == null || path.isBlank()) return null;
        if (path.trim().length() > MAX_JSON_PATH) {
            return Msg.t("JSON yolu en fazla " + MAX_JSON_PATH + " karakter olabilir.",
                    "The JSON path can be at most " + MAX_JSON_PATH + " characters.");
        }
        try {
            JsonAssertion.parse(path);
            return null;
        } catch (JsonAssertion.JsonPathSyntaxException e) {
            int pos = e.position() + 1;
            return Msg.t("Geçersiz JSON yolu (" + pos + ". karakter): " + e.reasonTr() + ". Örnek: $.status ya da $.items[0].id",
                    "Invalid JSON path (character " + pos + "): " + e.reasonEn() + ". Example: $.status or $.items[0].id");
        }
    }

    /** Beklenen değer uzunluğu. */
    public static String validateJsonExpected(String expected) {
        if (expected == null || expected.isEmpty()) return null;
        if (expected.length() > MAX_JSON_EXPECTED) {
            return Msg.t("Beklenen değer en fazla " + MAX_JSON_EXPECTED + " karakter olabilir.",
                    "The expected value can be at most " + MAX_JSON_EXPECTED + " characters.");
        }
        return null;
    }

    /** Yavaş yanıt eşiği aralığı. */
    public static String validateSlowThreshold(int ms) {
        if (ms < MIN_SLOW_MS || ms > MAX_SLOW_MS) {
            return Msg.t("Yavaş yanıt eşiği " + MIN_SLOW_MS + "–" + MAX_SLOW_MS + " ms arasında olmalı.",
                    "The slow response threshold must be between " + MIN_SLOW_MS + " and " + MAX_SLOW_MS + " ms.");
        }
        return null;
    }

    /** HEAD yanıtında gövde yoktur — JSON doğrulaması tanımlanamaz. */
    public static String validateJsonWithMethod(String method, String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return null;
        if ("HEAD".equals(method)) {
            return Msg.t("JSON doğrulaması HEAD yönteminde kullanılamaz (yanıtta gövde yoktur); GET ya da POST seçin.",
                    "JSON validation cannot be used with the HEAD method (the response has no body); choose GET or POST.");
        }
        return null;
    }

    /** Sekme dışındaki kontrol karakterleri (CR/LF dahil) — başlık enjeksiyonu buradan başlar. */
    static boolean hasCtl(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch < 0x20 && ch != '\t') || ch == 0x7f) return true;
        }
        return false;
    }
}
