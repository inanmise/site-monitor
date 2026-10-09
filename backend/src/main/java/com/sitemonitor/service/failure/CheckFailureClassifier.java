package com.sitemonitor.service.failure;

import com.sitemonitor.service.HttpFailureDiagnostics;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SsrfGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sitemonitor.service.failure.CheckFailureReason.*;

/**
 * Kontrol başarısızlığını {@link CheckFailureReason} koduna indiren SAF, durumsuz sınıflandırıcı (2026-10-05).
 *
 * <p>İki giriş:
 * <ul>
 *   <li><b>İstisna</b> ({@link #forException}) — en kesin yol: checker istisnayı yakaladığı yerde çağırır. Ağ/TLS/vekil
 *       kuralları {@link HttpFailureDiagnostics#classify} ile AYNI (HTTP izlemesinin hata tanısıyla tek doğruluk kaynağı);
 *       buraya yalnız TCP/vekil/DNS'e özgü ekler (SSRF kalkanının "çözümlenemedi" ayrımı, vekil tüneli reddi, IPv4/IPv6
 *       adres ailesi, ham HTTP durum satırı) konur.</li>
 *   <li><b>Metin / koşul</b> — istisnası olmayan durumlar: DNS rcode'u ({@link #forDnsRcode}), ping çıktısı
 *       ({@link #forPing}), HTTP durum uyuşmazlığı ({@link #forHttpStatus}), sayfa kaynak sayaçları
 *       ({@link #forPageResources}), RDAP/WHOIS sonucu ({@link #forDomain}) ve yalnız hata metni olan eski yollar
 *       ({@link #fromMessage}).</li>
 * </ul>
 *
 * <p><b>Değişmez kural:</b> bu sınıf ÜST VERİ üretir. Hiçbir çağıran ok/up/open kararını, alarmı, DNS değişiklik tespitini
 * ya da bildirimi bu sonuca bağlamaz; tüm yöntemler istisna yutar (en kötü ihtimalle {@link CheckFailureReason#UNKNOWN})
 * ve veritabanına dokunmaz.
 */
public final class CheckFailureClassifier {

    private CheckFailureClassifier() {}

    /** İstisna zincirinde ayrıntıya yazılan en fazla halka. */
    static final int MAX_CAUSES = 4;

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  İstisna
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final Pattern PROXY_STATUS = Pattern.compile("HTTP/1\\.[01]\\s+(\\d{3})");
    private static final Pattern RDAP_HTTP = Pattern.compile("rdap http (\\d{3})");

    /**
     * İstisna → kod. {@code viaProxy}: kontrol kurumsal vekil üzerinden mi çıktı — vekil yolunda TCP bağlantı hatası
     * hedefin değil VEKİLİN hatasıdır ({@link HttpFailureDiagnostics} ile aynı kural).
     */
    public static CheckFailureReason codeForException(Throwable t, boolean viaProxy) {
        try {
            if (t == null) return UNKNOWN;
            int guard = 0;
            for (Throwable cur = t; cur != null && guard++ < 10; cur = cur.getCause() == cur ? null : cur.getCause()) {
                String msg = cur.getMessage() == null ? "" : cur.getMessage();
                String low = msg.toLowerCase(Locale.ROOT);
                if (cur instanceof SsrfGuard.BlockedException) {
                    if (cur instanceof SsrfGuard.UnresolvableHostException || SsrfGuard.isUnresolvableMessage(msg)) return DNS_RESOLVE;
                    if (low.startsWith("boş hedef host") || low.startsWith("geçersiz url")) return CONFIG_ERROR;
                    return SSRF_BLOCKED;
                }
                if (low.startsWith("vekil tüneli reddetti")) return PROXY_REFUSED;
                if (low.startsWith("vekil tanımlı değil") || low.startsWith("vekil tünel yanıtı")) return PROXY_ERROR;
                if (cur instanceof com.sitemonitor.util.HttpBodies.BodyDeadlineException) return READ_TIMEOUT;
                if (cur instanceof java.net.PortUnreachableException) return CONNECT_REFUSED;
                if (cur instanceof java.net.UnknownHostException) return DNS_RESOLVE;
                if (low.startsWith("geçersiz http yanıtı") || low.startsWith("http durum satırı çok uzun")) return PROTOCOL_ERROR;
            }
            HttpFailureDiagnostics.Kind k = HttpFailureDiagnostics.classify(t);
            CheckFailureReason r = switch (k) {
                case SSRF_BLOCKED -> SSRF_BLOCKED;
                case CONFIG_ERROR -> CONFIG_ERROR;
                case DNS_UNRESOLVED -> DNS_RESOLVE;
                case CONNECT_TIMEOUT -> CONNECT_TIMEOUT;
                case CONNECT_REFUSED -> CONNECT_REFUSED;
                case HOST_UNREACHABLE -> HOST_UNREACHABLE;
                case PROXY_CONNECT, PROXY_AUTH -> PROXY_ERROR;
                case TLS_CERT_UNTRUSTED -> TLS_TRUST;
                case TLS_HOSTNAME_MISMATCH -> TLS_HOSTNAME;
                case TLS_HANDSHAKE -> TLS_HANDSHAKE;
                case RESPONSE_TIMEOUT -> READ_TIMEOUT;
                case CONNECTION_RESET, CONNECTION_CLOSED -> CONNECTION_RESET;
                case PROTOCOL_ERROR -> PROTOCOL_ERROR;
                case TOO_MANY_REDIRECTS -> REDIRECT_LIMIT;
                case STATUS_MISMATCH -> HTTP_STATUS;
                default -> UNKNOWN;
            };
            // Vekil yolunda "bağlanılamadı" vekile aittir (tünel kurulmadan önceki adım).
            if (viaProxy && (r == CONNECT_TIMEOUT || r == CONNECT_REFUSED || r == HOST_UNREACHABLE)) return PROXY_ERROR;
            return r;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /**
     * İstisna → neden + ayrıntı (evre, istisna sınıfı, temizlenmiş ileti, kısa neden zinciri; vekil reddinde vekilin
     * durum kodu). Hedef/yol/zaman aşımı gibi bağlamı çağıran {@link CheckFailure#with} ile ekler.
     */
    public static CheckFailure forException(Throwable t, boolean viaProxy) {
        CheckFailure f = CheckFailure.of(codeForException(t, viaProxy));
        try {
            if (viaProxy) f.with("via", "proxy");
            if (t != null) {
                f.with("exception", t.getClass().getSimpleName());
                f.with("message", firstMessage(t));
                List<String> chain = causeChain(t);
                if (chain.size() > 1) f.with("cause_chain", chain);
                if (f.reason() == PROXY_REFUSED) {
                    Matcher m = PROXY_STATUS.matcher(firstMessage(t));
                    if (m.find()) f.with("proxy_status", Integer.parseInt(m.group(1)));
                }
                String low = String.valueOf(firstMessage(t)).toLowerCase(Locale.ROOT);
                if (low.contains("no ipv6 address")) f.with("family", "v6");
                else if (low.contains("no ipv4 address")) f.with("family", "v4");
            }
        } catch (Exception ignore) { /* ayrıntı en iyi çaba */ }
        return f;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Koşullar (istisnasız)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Yanıt geldi ama durum kodu beklenen kalıbı tutmadı. */
    public static CheckFailure forHttpStatus(int status, String expected) {
        CheckFailure f = CheckFailure.of(HTTP_STATUS);
        if (status > 0) f.with("http_status", status);
        if (expected != null && !expected.isBlank()) f.with("expected", expected.trim());
        return f;
    }

    /**
     * DNS yanıt kodu (rcode) → neden. NOERROR + boş cevap = "kayıt yok"; NXDOMAIN = "ad yok"; REFUSED = çözümleyici
     * sorguyu reddetti; SERVFAIL ve diğer hata kodları = "sunucu yanıtlayamadı".
     *
     * @param rcode     dnsjava {@code Rcode} sayısal kodu (0 NOERROR, 2 SERVFAIL, 3 NXDOMAIN, 5 REFUSED)
     * @param rcodeName okunur ad ({@code Rcode.string})
     */
    public static CheckFailure forDnsRcode(int rcode, String rcodeName) {
        CheckFailureReason r = switch (rcode) {
            case 0 -> DNS_NO_ANSWER;
            case 3 -> DNS_NXDOMAIN;
            case 5 -> DNS_REFUSED;
            default -> DNS_SERVFAIL;
        };
        return CheckFailure.of(r).with("rcode", rcodeName == null ? String.valueOf(rcode) : rcodeName);
    }

    /** DNS sorgusu istisnayla bitti: zaman aşımı → DNS_TIMEOUT; geçersiz ad → CONFIG_ERROR; diğerleri DNS_RESOLVE. */
    public static CheckFailure forDnsException(Throwable t) {
        CheckFailureReason r = DNS_RESOLVE;
        try {
            int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
            for (Throwable cur = t; cur != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; cur = cur.getCause() == cur ? null : cur.getCause()) {
                String low = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(Locale.ROOT);
                String cls = cur.getClass().getName();
                if (cls.endsWith("TextParseException") || cls.endsWith("RelativeNameException")) { r = CONFIG_ERROR; break; }
                if (cur instanceof java.net.SocketTimeoutException || cls.endsWith("TimeoutException")
                        || low.contains("timed out") || low.contains("timeout")) { r = DNS_TIMEOUT; break; }
            }
        } catch (Exception ignore) { /* DNS_RESOLVE */ }
        CheckFailure f = CheckFailure.of(r);
        if (t != null) {
            f.with("exception", t.getClass().getSimpleName());
            f.with("message", firstMessage(t));
        }
        return f;
    }

    // ping çıktısındaki ad çözümleme / yol hataları (iputils · busybox · Windows · macOS) — arayüz pingCardModel ile aynı
    private static final Pattern PING_DNS = Pattern.compile(
            "unknown host|name or service not known|could not find host|temporary failure in name resolution|bad address"
                    + "|no address associated|nodename nor servname|cannot resolve", Pattern.CASE_INSENSITIVE);
    private static final Pattern PING_UNREACHABLE = Pattern.compile(
            "destination (?:host|net) unreachable|network is unreachable|no route to host|network is down|host unreachable",
            Pattern.CASE_INSENSITIVE);

    /**
     * Ping sonucu → neden. {@code na}: ICMP bu pod'da kullanılamıyor (yetki / ping ikili dosyası yok) — hedefle ilgisi yok.
     *
     * @param loss   paket kaybı yüzdesi (ayrıştırılabildiyse)
     * @param output ping çıktısı (ilk anlamlı satırı ayrıntıya yazılır)
     */
    public static CheckFailure forPing(boolean na, Integer loss, String output) {
        String out = output == null ? "" : output;
        CheckFailureReason r;
        if (na) r = ICMP_UNAVAILABLE;
        else if (PING_DNS.matcher(out).find()) r = DNS_RESOLVE;
        else if (PING_UNREACHABLE.matcher(out).find()) r = HOST_UNREACHABLE;
        else if (loss != null && loss >= 100) r = ICMP_NO_REPLY;
        else r = UNKNOWN;
        CheckFailure f = CheckFailure.of(r);
        if (loss != null) f.with("packet_loss", loss);
        String line = firstLine(out);
        if (line != null) f.with("output", line);
        return f;
    }

    /**
     * Sayfa geldi ama kaynaklar sorunlu (Sayfa Bütünlüğü DEGRADED): kırık → RESOURCES_BROKEN, yalnız zaman aşımı →
     * RESOURCES_TIMEOUT, yalnız güvensiz (http) kaynak → MIXED_CONTENT. Sorun yoksa null.
     */
    public static CheckFailure forPageResources(int broken, int timeouts, int mixed, Integer total) {
        CheckFailureReason r = broken > 0 ? RESOURCES_BROKEN : timeouts > 0 ? RESOURCES_TIMEOUT : mixed > 0 ? MIXED_CONTENT : null;
        if (r == null) return null;
        CheckFailure f = CheckFailure.of(r).with("broken", broken).with("timeouts", timeouts).with("mixed", mixed);
        if (total != null) f.with("total_resources", total);
        return f;
    }

    /**
     * Alan adı kaydı sorgusu veri getirmedi (UNKNOWN) → neden.
     *
     * @param error          kaynağın hata metni ({@code rdap http 404}, {@code whois: …}, istisna iletisi) — null olabilir
     * @param registryKnown  TLD'nin IANA bootstrap'ta herkese açık RDAP sunucusu var mı (null = bilinmiyor)
     * @param whoisTried     WHOIS yedeği denendi mi (herhangi bir WHOIS kaynağı açık)
     * @param whoisError     WHOIS denemesinin hatası (varsa)
     */
    public static CheckFailure forDomain(String error, Boolean registryKnown, boolean whoisTried, String whoisError) {
        String e = error == null ? "" : error.trim();
        String low = e.toLowerCase(Locale.ROOT);
        CheckFailureReason r;
        Integer http = null;
        Matcher hm = RDAP_HTTP.matcher(low);
        if (hm.find()) http = Integer.parseInt(hm.group(1));
        if (low.startsWith("geçersiz/çözümlenemeyen domain") || low.startsWith("invalid domain") || low.startsWith("bad url")) {
            r = CONFIG_ERROR;
        } else if (Boolean.FALSE.equals(registryKnown)) {
            // Herkese açık RDAP yok (ör. .tr): sonuç WHOIS'e bağlı
            r = whoisTried ? WHOIS_UNAVAILABLE : NO_PUBLIC_REGISTRY;
        } else if (e.isEmpty()) {
            r = REGISTRY_NO_EXPIRY;                       // kaynak yanıt verdi ama bitiş tarihi yok
        } else if (low.contains("no expiry parsed")) {
            r = REGISTRY_NO_EXPIRY;
        } else if (http != null && http == 404) {
            r = RDAP_NOT_FOUND;
        } else if (http != null && http == 429) {
            r = RDAP_RATE_LIMITED;
        } else if (http != null || low.startsWith("rdap parse") || low.startsWith("no rdap")) {
            r = RDAP_UNAVAILABLE;
        } else if (low.startsWith("whois")) {
            r = WHOIS_UNAVAILABLE;
        } else {
            CheckFailureReason net = fromMessage(e);
            r = net == UNKNOWN ? RDAP_UNAVAILABLE : net;
        }
        CheckFailure f = CheckFailure.of(r);
        if (http != null) f.with("http_status", http);
        if (!e.isEmpty()) f.with("message", e);
        if (registryKnown != null) f.with("registry_rdap", registryKnown);
        if (whoisTried) {
            f.with("whois_tried", true);
            if (whoisError != null && !whoisError.isBlank()) f.with("whois_error", whoisError);
        }
        return f;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Yalnız metin (istisnası elde olmayan yollar)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final Pattern HTTP_STATUS_TEXT = Pattern.compile("^(?:ana sayfa |sayfa )?HTTP (\\d{3})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Hata METNİNDEN en yakın kod — istisnanın kendisi elde değilken (ör. alan adı kaynağının iletisi). Sırası
     * {@link HttpFailureDiagnostics#classify} kurallarına paralel; eşleşme yoksa UNKNOWN.
     */
    public static CheckFailureReason fromMessage(String message) {
        try {
            if (message == null || message.isBlank()) return UNKNOWN;
            String s = message.toLowerCase(Locale.ROOT);
            if (SsrfGuard.isUnresolvableMessage(message)) return DNS_RESOLVE;
            if (s.startsWith("izin verilmeyen hedef")) return SSRF_BLOCKED;
            if (s.startsWith("boş hedef host") || s.startsWith("yapılandırma hatası")) return CONFIG_ERROR;
            if (s.startsWith("vekil tüneli reddetti")) return PROXY_REFUSED;
            if (s.contains("çok fazla yönlendirme") || s.contains("too many redirects")) return REDIRECT_LIMIT;
            if ((s.contains("hostname") && s.contains("match")) || s.contains("no name matching") || s.contains("subject alternative")) return TLS_HOSTNAME;
            if (s.contains("pkix") || s.contains("unable to find valid certification path") || s.contains("certificate_unknown")
                    || s.contains("self signed") || s.contains("self-signed")) return TLS_TRUST;
            if (s.contains("handshake") || s.contains("ssl") || s.contains("tls")) return TLS_HANDSHAKE;
            if (s.contains("proxy") || s.contains("vekil") || s.contains("tunnel")) return PROXY_ERROR;
            if (s.contains("unknownhost") || s.contains("unknown host") || s.contains("name or service not known")
                    || s.contains("no such host") || s.contains("nodename nor servname")) return DNS_RESOLVE;
            if (s.contains("connect timed out") || s.contains("connecttimeout") || s.contains("http connect timed out")) return CONNECT_TIMEOUT;
            if (s.contains("timed out") || s.contains("timeout") || s.contains("zaman aşımı")) return READ_TIMEOUT;
            if (s.contains("connection refused") || s.contains("connectexception")) return CONNECT_REFUSED;
            if (s.contains("no route to host") || s.contains("unreachable")) return HOST_UNREACHABLE;
            if (s.contains("connection reset") || s.contains("broken pipe") || s.contains("received no bytes")) return CONNECTION_RESET;
            if (HTTP_STATUS_TEXT.matcher(message.trim()).find()) return HTTP_STATUS;
            return UNKNOWN;
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Yardımcılar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final Pattern URL_USERINFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s@]+@");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(basic|bearer|digest|negotiate|ntlm)\\s+[A-Za-z0-9+/=._~-]{6,}");
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)\\b(proxy-authorization|authorization|set-cookie|cookie|x-api-key)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|parola|şifre|sifre|secret|token|api_?key)\\s*[:=]\\s*[^\\s&,;]+");

    /**
     * Ayrıntıya yazılacak metni temizler: tek satır, boşluklar sıkıştırılmış, {@code max} karakterle kırpılmış; URL kullanıcı
     * bilgisi, hassas sorgu parametreleri ({@link SecretMask#maskUrlQuery}), Basic/Bearer değerleri, Authorization/Cookie
     * başlıkları ve parola/belirteç çiftleri maskelenir. null → null.
     */
    public static String sanitize(String s, int max) {
        if (s == null) return null;
        try {
            String v = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
            v = URL_USERINFO.matcher(v).replaceAll("$1***@");
            v = SecretMask.maskUrlQuery(v);
            v = AUTH_SCHEME.matcher(v).replaceAll("$1 ***");
            v = AUTH_HEADER.matcher(v).replaceAll("$1: ***");
            v = SECRET_PAIR.matcher(v).replaceAll("$1=***");
            int cap = Math.max(16, max);
            return v.length() <= cap ? v : v.substring(0, cap - 1) + "…";
        } catch (Exception e) {
            return null;
        }
    }

    /** Zincirdeki ilk anlamlı (boş olmayan) ileti; hiç yoksa istisna sınıfının adı. */
    static String firstMessage(Throwable t) {
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable cur = t; cur != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; cur = cur.getCause() == cur ? null : cur.getCause()) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) return m;
        }
        return t == null ? null : t.getClass().getSimpleName();
    }

    /** "Sınıf: ileti" halkaları (en fazla {@link #MAX_CAUSES}); sınıf adı kısa. */
    static List<String> causeChain(Throwable t) {
        List<String> out = new ArrayList<>();
        Throwable cur = t;
        while (cur != null && out.size() < MAX_CAUSES) {
            String m = cur.getMessage();
            out.add(cur.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m));
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return out;
    }

    /** Metnin ilk boş olmayan satırı (≤ 160); yoksa null. */
    static String firstLine(String s) {
        if (s == null) return null;
        for (String line : s.split("\\R")) {
            String v = line.trim();
            if (!v.isEmpty()) return v.length() > 160 ? v.substring(0, 160) : v;
        }
        return null;
    }
}
