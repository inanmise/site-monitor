package com.sitemonitor.service;

import java.util.Locale;
import java.util.regex.Matcher;
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

    /** Anahtar adına göre değeri maskeler. Hassas+dolu → {@link #MASK}; null/boş → "(ayarsız)"; değilse metin.
     *  Boş hassas değer de "(ayarsız)" döner: boş değerin sızdıracak içeriği yoktur, ama "*****" basmak onu dolu
     *  gösterir — 2026-08 proxy tanısında boş HTTP_PROXY_PASS dökümde ayarlı sanıldı (sessiz düşüş görünür olsun). */
    public static String mask(String key, Object value) {
        if (value == null) return "(ayarsız)";
        String s = String.valueOf(value);
        if (s.isBlank()) return "(ayarsız)";
        return isSensitive(key) ? MASK : s;
    }

    /**
     * Metin GÖVDESİNDE bilinen secret DEĞERLERİNİ maskeler — k6 stdout/stderr için (bu sınıf normalde anahtar-adı
     * maskeler; bu ise değer-tabanlı). Çok kısa değerler (≤3 karakter) atlanır (yanlış-pozitif/gürültü).
     *
     * <p>Değer, çıktıya <b>kodlanmış biçimde</b> de düşebilir; birebir substring araması bunları kaçırırdı:
     * vekil parolası URL'in içine {@code URLEncoder} ile konuyor ({@code ProxySettings.proxyUrl}) ve k6
     * hata metninde vekil URL'ini basıyor ({@code proxyconnect tcp: ...}) — {@code P@ss w0rd} çıktıda
     * {@code P%40ss+w0rd} olarak görünüp maskesiz kalıyor, oradan {@code scripted_checks.output_tail}'e ve
     * alarm e-postasına yazılıyordu. Aynı şekilde k6 logfmt satırlarında değerler JSON-kaçışlı geçer.
     * Bu yüzden her secret için bilinen kodlama VARYANTLARI üretilip hepsi maskelenir (şüphede maskele).
     */
    public static String maskValues(String text, java.util.Collection<String> secretValues) {
        if (text == null || text.isEmpty() || secretValues == null) return text;
        // Uzun varyant önce: kısa bir varyant uzun olanın parçasıysa önce onu bozup eşleşmeyi kaçırmasın.
        java.util.List<String> all = new java.util.ArrayList<>();
        for (String v : secretValues) {
            if (v == null || v.length() < 4) continue;
            all.addAll(encodingVariants(v));
        }
        all.sort(java.util.Comparator.comparingInt(String::length).reversed());
        String out = text;
        for (String v : all) out = out.replace(v, MASK);
        return out;
    }

    /**
     * Bir secret değerin çıktıda görünebileceği kodlama biçimleri (ham dâhil, tekilleştirilmiş).
     *
     * <p>Kapsananlar: ham · {@code URLEncoder} (boşluk {@code +}) · yüzde-kodlama (boşluk {@code %20},
     * URI/RFC 3986 biçimi) · JSON kaçışlı ({@code \} ve {@code "}). Kodlama değeri değiştirmiyorsa
     * (yalın alfasayısal parola) tek eleman döner.
     */
    static java.util.Set<String> encodingVariants(String v) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        out.add(v);
        try {
            String urlEnc = java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
            out.add(urlEnc);
            out.add(urlEnc.replace("+", "%20"));
        } catch (RuntimeException ignored) { /* kodlanamayan değer: ham biçim yine maskeleniyor */ }
        String json = v.replace("\\", "\\\\").replace("\"", "\\\"");
        out.add(json);
        out.removeIf(s -> s == null || s.length() < 4);
        return out;
    }

    // ── Çalışma anında üretilen jetonlar (2026-10-31, k6 inceleme A7) ───────────────────────────────────────
    //
    // maskValues yalnız KAYITLI gizli env değerlerini bilir. Bir betiğin koşum SIRASINDA aldığı / ürettiği jeton (OIDC
    // access_token, Set-Cookie, Bearer başlığı, JWT) console.log ya da k6 hata satırıyla output_tail'e, oradan
    // scripted_checks.error'a ve alarm e-postasına gidiyordu. maskTokens bu biçimleri DEĞER bilinmeden maskeler.
    // DOĞRUSAL tarama (düzenli ifade grup döngüsü yok): her konumda sabit sayıda (anahtar listesi × en uzun anahtar)
    // karşılaştırma; JWT denemesi başarısız olursa parça sonuna atlanır (aynı karakter iki kez taranmaz).

    /** Değeri satır sonuna kadar maskelenen başlık adları (değer boşluk içerir: "Bearer abc"). */
    private static final String[] TOKEN_HEADER_KEYS = {
            "proxy-authorization", "authorization", "set-cookie", "cookie"};
    /** Değeri tek "kelime" olan anahtarlar (JSON / sorgu dizesi / logfmt); uzun ad önce (id_token ⊃ token). */
    private static final String[] TOKEN_VALUE_KEYS = {
            "client_secret", "refresh_token", "access_token", "id_token", "password", "api_key", "apikey",
            "passwd", "secret", "token"};
    /** JWT parçası en az bu kadar karakter (her üç parça). */
    private static final int JWT_MIN_PART = 10;

    /**
     * Metinde çalışma anında üretilmiş kimlik bilgilerini maskeler: {@code Authorization:} / {@code Proxy-Authorization:}
     * ve {@code Cookie:} / {@code Set-Cookie:} değerleri (satır sonuna ya da kapanan tırnağa kadar), JWT
     * ({@code eyJ…} + üç nokta-ayrık base64url parça, her biri ≥ 10) ve
     * {@code access_token|id_token|refresh_token|token|password|passwd|secret|api_key|apikey|client_secret} anahtarlarının
     * {@code =} / {@code :} ile verilen değerleri (JSON, sorgu dizesi, logfmt; kaçışlı tırnak {@code \"} dahil).
     * Anahtar sözcük sınırında başlamalı ({@code mytoken=} maskelenmez, {@code tokens=5} de). Boş değer maskelenmez.
     * Hiçbir koşulda istisna fırlatmaz; {@code null}/boş metin olduğu gibi döner.
     */
    public static String maskTokens(String text) {
        if (text == null || text.isEmpty()) return text;
        int n = text.length();
        char[] low = new char[n];
        for (int i = 0; i < n; i++) low[i] = Character.toLowerCase(text.charAt(i));
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < n) {
            char c = low[i];
            // ── JWT ──
            if (i + 3 <= n && text.charAt(i) == 'e' && text.charAt(i + 1) == 'y' && text.charAt(i + 2) == 'J'
                    && (i == 0 || !isB64Url(text.charAt(i - 1)))) {
                int end = jwtEnd(text, i);
                if (end > i) {
                    if (out == null) out = new StringBuilder(n);
                    out.append(text, copied, i).append(MASK);
                    copied = end;
                    i = end;
                    continue;
                }
                // başarısız deneme: ilk base64url koşusunun sonuna atla (koşu içinde sınır olamaz)
                int j = i;
                while (j < n && isB64Url(text.charAt(j))) j++;
                i = Math.max(j, i + 1);
                continue;
            }
            if (isKeyStart(c) && (i == 0 || !isWordChar(text.charAt(i - 1)))) {
                int[] span = tokenValueSpan(text, low, i);
                if (span != null) {
                    if (out == null) out = new StringBuilder(n);
                    out.append(text, copied, span[0]).append(MASK);
                    copied = span[1];
                    i = span[1];
                    continue;
                }
            }
            i++;
        }
        if (out == null) return text;
        return out.append(text, copied, n).toString();
    }

    private static boolean isKeyStart(char c) {
        return c == 'a' || c == 'c' || c == 'i' || c == 'p' || c == 'r' || c == 's' || c == 't';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isB64Url(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    /** {@code eyJ}'den başlayan geçerli JWT'nin bitişi; değilse -1. */
    private static int jwtEnd(String s, int start) {
        int n = s.length();
        int pos = start;
        for (int part = 0; part < 3; part++) {
            int from = pos;
            while (pos < n && isB64Url(s.charAt(pos))) pos++;
            if (pos - from < JWT_MIN_PART) return -1;
            if (part < 2) {
                if (pos >= n || s.charAt(pos) != '.') return -1;
                pos++;
            }
        }
        return pos;
    }

    /** {@code low[i..]} bir anahtarla başlıyorsa anahtarın SONU, değilse -1 (anahtar sonrası kelime karakteri gelmemeli). */
    private static int matchKey(char[] low, int i, String key) {
        int n = low.length;
        if (i + key.length() > n) return -1;
        for (int k = 0; k < key.length(); k++) if (low[i + k] != key.charAt(k)) return -1;
        int end = i + key.length();
        if (end < n && (Character.isLetterOrDigit(low[end]) || low[end] == '_')) return -1;
        return end;
    }

    /**
     * Konumdaki anahtar + ayırıcı + değer: maskelenecek DEĞERİN [başlangıç, bitiş) aralığı; desen yoksa null.
     * Biçim: {@code anahtar} [kapanan tırnak: {@code "} {@code '} {@code \"}] boşluk {@code :} ya da {@code =} boşluk
     * [açılan tırnak] değer.
     */
    private static int[] tokenValueSpan(String s, char[] low, int i) {
        boolean header = false;
        int keyEnd = -1;
        for (String k : TOKEN_HEADER_KEYS) {
            keyEnd = matchKey(low, i, k);
            if (keyEnd > 0) { header = true; break; }
        }
        if (keyEnd < 0) {
            for (String k : TOKEN_VALUE_KEYS) {
                keyEnd = matchKey(low, i, k);
                if (keyEnd > 0) break;
            }
        }
        if (keyEnd < 0) return null;
        int n = s.length();
        int p = keyEnd;
        // JSON anahtarının kapanan tırnağı (kaçışlı olabilir: logfmt içindeki JSON)
        if (p < n && s.charAt(p) == '\\' && p + 1 < n && s.charAt(p + 1) == '"') p += 2;
        else if (p < n && (s.charAt(p) == '"' || s.charAt(p) == '\'')) p++;
        while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
        if (p >= n || (s.charAt(p) != ':' && s.charAt(p) != '=')) return null;
        p++;
        while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
        if (p >= n) return null;
        // açılan tırnak
        String quote = null;
        if (s.charAt(p) == '\\' && p + 1 < n && s.charAt(p + 1) == '"') { quote = "\\\""; p += 2; }
        else if (s.charAt(p) == '"' || s.charAt(p) == '\'') { quote = String.valueOf(s.charAt(p)); p++; }
        int start = p;
        int end = p;
        if (quote != null) {
            while (end < n) {
                char c = s.charAt(end);
                if (c == '\n' || c == '\r') break;
                if (quote.length() == 2) {                     // \"…\" — kapanış \" (önündeki \\ çift kaçış değilse)
                    if (c == '\\' && end + 1 < n && s.charAt(end + 1) == '"') break;
                    if (c == '\\' && end + 1 < n && s.charAt(end + 1) == '\\') { end += 2; continue; }
                } else {
                    if (c == '\\' && end + 1 < n) { end += 2; continue; }
                    if (c == quote.charAt(0)) break;
                }
                end++;
            }
            end = Math.min(end, n);
        } else if (header) {
            while (end < n && s.charAt(end) != '\n' && s.charAt(end) != '\r') end++;
        } else {
            while (end < n && !isTokenStop(s.charAt(end))) end++;
        }
        if (end <= start) return null;
        return new int[]{start, end};
    }

    /** Tırnaksız tek "kelime" değerin bittiği karakter. */
    private static boolean isTokenStop(char c) {
        return c == '&' || c == ',' || c == ';' || c == '"' || c == '\'' || c == '}' || c == ')' || c == ']' || c == '<'
                || c == '\\' || Character.isWhitespace(c);
    }

    /** URL query-string'i ve serbest metin içindeki hassas parametre DEĞERLERİNİ maskeler —
     *  sorun-bildirimi otomatik bağlamı (URL, hata metni/stack) için. Parametre ADI hassas
     *  kalıba uyarsa (EN+TR: password/parola/sifre/token/secret/otp/pin/key...) değeri {@link #MASK} olur;
     *  yol ve zararsız parametreler görünür kalır (tanı değeri korunur). */
    // Sonsuz bekleme savunması (2026-10-09, ReDoS): eski TEK kalıp adın iki yanında örtüşen [^=&#\s]* taşıyordu ve '?'
    // ad sınıfına dahildi — herkese açık /api/client-error-report'a gönderilen "?pin?pin…" (≈100 KB) kübik geri izlemeyle
    // istek iş parçacığını saatlerce kilitliyordu (uzunluk/hız sınırı maskeden SONRA geliyordu). Şimdi doğrusal: parametre
    // adı (iyelikli, '?' dahil hiçbir ayırıcıyı yutmaz) bir kalıpla, hassaslık ikinci kalıpla, değer elle taranır.
    // Çıktı eskisiyle aynıdır (tek fark: adın İÇİNDE '?' geçen yapay biçimde ad '?'dan sonra başlar — yine maskelenir).
    private static final Pattern QUERY_PARAM = Pattern.compile("[?&#]([^=&#?\\s]*+)=");
    private static final Pattern SENSITIVE_PARAM_NAME = Pattern.compile(
            "(?i)password|passwd|parola|sifre|şifre|secret|token|api_?key|private_?key|"
            + "credential|session_?id|jsessionid|otp|pin|mfa|verification_?code|auth");

    public static String maskUrlQuery(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = QUERY_PARAM.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && m.find(from)) {
            int valueStart = m.end();
            if (!SENSITIVE_PARAM_NAME.matcher(m.group(1)).find()) { from = valueStart; continue; }
            int valueEnd = valueStart;
            while (valueEnd < text.length() && !isValueStop(text.charAt(valueEnd))) valueEnd++;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, copied, valueStart).append(MASK);
            copied = valueEnd;
            from = valueEnd;
        }
        if (out == null) return text;
        return out.append(text, copied, text.length()).toString();
    }

    /** Değerin bittiği karakter: eski kalıbın {@code [^&#\s]} sınıfının tümleyeni ({@code \s} = boşluk, \t \n \x0B \f \r). */
    private static boolean isValueStop(char c) {
        return c == '&' || c == '#' || c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }

    /**
     * E-posta adresini günlük için maskeler: {@code kisi.adi@example.com → k***@example.com}
     * (prod kapısı 2026-09-25, P4-1). Alan adı görünür kalır — "hangi kuruma/listeye gitti" teşhisi
     * için yeterli; kişi adı düşer. Adres zaten {@code notification_log}'da tam hâliyle duruyor, uygulama
     * günlüğü (INFO, 30 gün, {@code kubectl logs}) ise onu tekrar etmemeli. {@code @} yoksa değer
     * adres DEĞİLDİR ama yine de kişi bilgisi olabilir → ilk karakter + yıldız.
     */
    public static String maskEmail(String email) {
        if (email == null) return "-";
        String e = email.trim();
        if (e.isEmpty()) return "-";
        int at = e.lastIndexOf('@');
        if (at <= 0) return e.charAt(0) + "***";
        return e.charAt(0) + "***" + e.substring(at);
    }

    /**
     * Alıcı listesini maskeler — {@code String} (virgül/noktalı virgül ayraçlı), {@code String[]} ya da
     * {@code Collection} kabul eder; günlük satırındaki {@code TO={}}/{@code cc={}} argümanları için.
     */
    public static String maskEmails(Object recipients) {
        if (recipients == null) return "[]";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (recipients instanceof String[] arr) {
            for (String a : arr) if (a != null) parts.addAll(java.util.List.of(a.split("[,;]")));
        } else if (recipients instanceof java.util.Collection<?> c) {
            for (Object o : c) if (o != null) parts.addAll(java.util.List.of(o.toString().split("[,;]")));
        } else {
            parts.addAll(java.util.List.of(recipients.toString().split("[,;]")));
        }
        java.util.StringJoiner j = new java.util.StringJoiner(", ", "[", "]");
        for (String p : parts) if (!p.isBlank()) j.add(maskEmail(p));
        return j.toString();
    }

    /** JDBC URL'e gömülü kimlik bilgilerini maskeler; host/port/db görünür kalır. */
    public static String maskJdbcUrl(String url) {
        if (url == null || url.isBlank()) return "(ayarsız)";
        String out = JDBC_USERINFO.matcher(url).replaceAll("$1" + MASK + ":" + MASK + "@");
        out = JDBC_QUERY_CRED.matcher(out).replaceAll("$1" + MASK);
        return out;
    }
}
