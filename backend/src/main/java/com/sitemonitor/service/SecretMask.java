package com.sitemonitor.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Merkezî hassas-değer maskeleme — log/tanı çıktısı üretiminde parola/secret/token/anahtar türü değerleri gizler.
 * Kara-liste tabanlıdır ve <b>şüphede maskele</b> ilkesiyle çalışır (yanlışlıkla bir secret basmaktansa fazladan
 * maskelemek doğrudur). Projedeki TEK maskeleme doğruluk-kaynağıdır — {@link AuditDiff} da hassaslık kararını
 * buradan alır; başlangıç konfigürasyon logu ve ileride başka loglar da bunu kullanır.
 */
public final class SecretMask {

    private SecretMask() {}

    public static final String MASK = "*****";

    /** Her yerde (substring) maskelenecek net-secret kalıpları. */
    private static final Pattern SUBSTR = Pattern.compile(
            "password|passwd|secret|credential|apikey|api_key|private_key|privatekey|salt|cipher|token",
            Pattern.CASE_INSENSITIVE);

    /** Yalnız TAM SEGMENT olarak maskelenecek kısa/çok-eşleşen kelimeler — "keyword"/"keepalive" yanlış-pozitifini
     *  önler ama "secret-key" / "proxy.pass" / "api.key" / "bindPassword"'ı yakalar. */
    private static final Pattern SEGMENT = Pattern.compile(
            "(?:^|[._-])(key|pass|pwd|private)(?:$|[._-])", Pattern.CASE_INSENSITIVE);

    /** JDBC URL'e gömülü kimlik: {@code //user:pass@} ve {@code ?user=..&password=..}. */
    private static final Pattern JDBC_USERINFO = Pattern.compile("(//)[^/@:]+:[^/@]+@");
    private static final Pattern JDBC_QUERY_CRED =
            Pattern.compile("(?i)([?&](?:password|pwd|pass|user|username)=)[^&]*");

    /** Hassas kelime İÇEREN ama değeri sır OLMAYAN politika anahtarları — sonek beyaz-listesi.
     *  Örn. {@code site.monitor.password.min-length} (12), {@code ...scripted.hardcoded-secret-policy} (WARN):
     *  açılış logunda maskelenince güvenlik denetiminde tam da bakılan değerler görünmez oluyordu. */
    private static final Pattern POLICY_SUFFIX = Pattern.compile(
            "(?:^|[._-])(min_length|max_length|history_count|policy|enabled|count)$",
            Pattern.CASE_INSENSITIVE);

    /** Anahtar adı hassas mı? camelCase → alt-çizgi sınırına normalize edilir; segment-farkında eşleşme. */
    public static boolean isSensitive(String key) {
        if (key == null) return false;
        String norm = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        norm = norm.replace('-', '_');
        if (POLICY_SUFFIX.matcher(norm).find()) return false;   // sır değil: sayısal/enum politika değeri
        return SUBSTR.matcher(norm).find() || SEGMENT.matcher(norm).find();
    }

    /** Anahtar adına göre değeri maskeler. Hassas → {@link #MASK}; değilse metin; null/boş → "(ayarsız)". */
    public static String mask(String key, Object value) {
        if (isSensitive(key)) return MASK;
        if (value == null) return "(ayarsız)";
        String s = String.valueOf(value);
        return s.isBlank() ? "(ayarsız)" : s;
    }

    /**
     * Metin GÖVDESİNDE bilinen secret DEĞERLERİNİ maskeler — k6 stdout/stderr için (bu sınıf normalde anahtar-adı
     * maskeler; bu ise değer-tabanlı). Çok kısa değerler (≤3 karakter) atlanır (yanlış-pozitif/gürültü).
     */
    public static String maskValues(String text, java.util.Collection<String> secretValues) {
        if (text == null || text.isEmpty() || secretValues == null) return text;
        String out = text;
        for (String v : secretValues) {
            if (v == null || v.length() < 4) continue;
            out = out.replace(v, MASK);
        }
        return out;
    }

    /** URL query-string'i ve serbest metin içindeki hassas parametre DEĞERLERİNİ maskeler —
     *  sorun-bildirimi otomatik bağlamı (URL, hata metni/stack) için. Parametre ADI hassas
     *  kalıba uyarsa (EN+TR: password/parola/sifre/token/secret/otp/pin/key...) değeri {@link #MASK} olur;
     *  yol ve zararsız parametreler görünür kalır (tanı değeri korunur). */
    private static final Pattern QUERY_SENSITIVE = Pattern.compile(
            "(?i)([?&#][^=&#\\s]*(?:password|passwd|parola|sifre|şifre|secret|token|api_?key|private_?key|"
            + "credential|session_?id|jsessionid|otp|pin|mfa|verification_?code|auth)[^=&#\\s]*=)[^&#\\s]*");

    public static String maskUrlQuery(String text) {
        if (text == null || text.isEmpty()) return text;
        return QUERY_SENSITIVE.matcher(text).replaceAll("$1" + MASK);
    }

    /** JDBC URL'e gömülü kimlik bilgilerini maskeler; host/port/db görünür kalır. */
    public static String maskJdbcUrl(String url) {
        if (url == null || url.isBlank()) return "(ayarsız)";
        String out = JDBC_USERINFO.matcher(url).replaceAll("$1" + MASK + ":" + MASK + "@");
        out = JDBC_QUERY_CRED.matcher(out).replaceAll("$1" + MASK);
        return out;
    }
}
