package com.sitemonitor.service.keyword;

import com.sitemonitor.service.HttpFailureDiagnostics;
import com.sitemonitor.util.HttpBodies;

import java.util.List;
import java.util.Locale;

/**
 * Keyword kontrolünün BAŞARISIZLIK NEDENİ — saf, durumsuz (2026-10-04, kullanıcı isteği: "kontrol geçmişinde hata
 * olduğunda herhangi bir detay bulunmuyor … neden hata aldı net göremiyorum").
 *
 * <p>İki kaynak:
 * <ul>
 *   <li><b>İstisna</b> (istek hiç tamamlanmadı): sınıflandırma {@link HttpFailureDiagnostics#classify} ile AYNI kural —
 *       HTTP izlemesinin hata tanısıyla tek doğruluk kaynağı (DNS / bağlantı / vekil / TLS / zaman aşımı …). Keyword'e
 *       özgü tek ek: gövde okuma süresi ({@link HttpBodies.BodyDeadlineException}) "yanıt okunamadı" sayılır.</li>
 *   <li><b>Koşul</b> (sayfa geldi ama adet kuralı sağlanmadı): durum kodu, boş gövde, okuma tavanı ve kuralın yönüne göre
 *       {@link #forCondition}.</li>
 * </ul>
 *
 * <p>Kodlar arayüzde i18n anahtarına dinamik çevrilir ({@code kwfail.<KOD>.short|why|effect|fix}); kod eklemek = TR + EN
 * metnini aynı değişiklikte eklemek ({@code KeywordDiagFindingsI18nGateTest} kırılır). Ayrıntı metni ({@link #detail})
 * TÜRKÇE ve kayda yazılır (≤ 500) — {@code error} kolonu gibi; arayüz kendi dilindeki metni koddan kurar.
 */
public final class KeywordFailureClassifier {

    private KeywordFailureClassifier() {}

    // ── Koşul (sayfa geldi) ──
    public static final String KEYWORD_NOT_FOUND = "KEYWORD_NOT_FOUND";
    public static final String KEYWORD_FOUND_FORBIDDEN = "KEYWORD_FOUND_FORBIDDEN";
    public static final String KEYWORD_COUNT_MISMATCH = "KEYWORD_COUNT_MISMATCH";
    public static final String HTTP_STATUS = "HTTP_STATUS";
    public static final String EMPTY_BODY = "EMPTY_BODY";
    public static final String BODY_TRUNCATED = "BODY_TRUNCATED";
    public static final String REDIRECT_BLOCKED = "REDIRECT_BLOCKED";
    // ── İstisna (istek tamamlanmadı) ──
    public static final String TIMEOUT_CONNECT = "TIMEOUT_CONNECT";
    public static final String TIMEOUT_READ = "TIMEOUT_READ";
    public static final String DNS = "DNS";
    public static final String TLS_HANDSHAKE = "TLS_HANDSHAKE";
    public static final String TLS_CERT = "TLS_CERT";
    public static final String CONNECTION_REFUSED = "CONNECTION_REFUSED";
    public static final String CONNECTION_RESET = "CONNECTION_RESET";
    public static final String HOST_UNREACHABLE = "HOST_UNREACHABLE";
    public static final String PROXY = "PROXY";
    public static final String SSRF_BLOCKED = "SSRF_BLOCKED";
    public static final String CONFIG_ERROR = "CONFIG_ERROR";
    public static final String REDIRECT_LIMIT = "REDIRECT_LIMIT";
    public static final String PROTOCOL_ERROR = "PROTOCOL_ERROR";
    public static final String UNKNOWN = "UNKNOWN";

    /** TAM liste — i18n kapısı bunu okur. */
    public static final List<String> CODES = List.of(
            KEYWORD_NOT_FOUND, KEYWORD_FOUND_FORBIDDEN, KEYWORD_COUNT_MISMATCH, HTTP_STATUS, EMPTY_BODY, BODY_TRUNCATED,
            REDIRECT_BLOCKED, TIMEOUT_CONNECT, TIMEOUT_READ, DNS, TLS_HANDSHAKE, TLS_CERT, CONNECTION_REFUSED,
            CONNECTION_RESET, HOST_UNREACHABLE, PROXY, SSRF_BLOCKED, CONFIG_ERROR, REDIRECT_LIMIT, PROTOCOL_ERROR, UNKNOWN);

    /** Kayıttaki ayrıntı metninin tavanı (kolon VARCHAR(500)). */
    public static final int DETAIL_MAX = 500;

    /** Sınıflandırma sonucu: kod + TR ayrıntı. */
    public record Reason(String code, String detail) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  İstisna
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** İstisna → kod. HTTP tanısının sınıflandırıcısı + gövde süre sınırı. Bilinmeyen → UNKNOWN. */
    public static String codeForException(Throwable t) {
        if (t == null) return UNKNOWN;
        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            if (cur instanceof HttpBodies.BodyDeadlineException) return TIMEOUT_READ;
        }
        HttpFailureDiagnostics.Kind k = HttpFailureDiagnostics.classify(t);
        return switch (k) {
            case SSRF_BLOCKED -> SSRF_BLOCKED;
            case CONFIG_ERROR -> CONFIG_ERROR;
            case DNS_UNRESOLVED -> DNS;
            case CONNECT_TIMEOUT -> TIMEOUT_CONNECT;
            case CONNECT_REFUSED -> CONNECTION_REFUSED;
            case HOST_UNREACHABLE -> HOST_UNREACHABLE;
            case PROXY_CONNECT, PROXY_AUTH -> PROXY;
            case TLS_CERT_UNTRUSTED, TLS_HOSTNAME_MISMATCH -> TLS_CERT;
            case TLS_HANDSHAKE -> TLS_HANDSHAKE;
            case RESPONSE_TIMEOUT -> TIMEOUT_READ;
            case CONNECTION_RESET, CONNECTION_CLOSED -> CONNECTION_RESET;
            case PROTOCOL_ERROR -> PROTOCOL_ERROR;
            case TOO_MANY_REDIRECTS -> REDIRECT_LIMIT;
            default -> UNKNOWN;
        };
    }

    /**
     * İstisna → neden (kod + TR ayrıntı).
     *
     * @param host      hedef host (ayrıntı metni için; null olabilir)
     * @param timeoutMs izlemenin zaman aşımı
     * @param viaProxy  kontrol vekil üzerinden mi çıktı
     */
    public static Reason forException(Throwable t, String host, int timeoutMs, boolean viaProxy) {
        String code = codeForException(t);
        String h = host == null || host.isBlank() ? "hedef" : host;
        String raw = rawMessage(t);
        boolean bodyDeadline = false;
        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            if (cur instanceof HttpBodies.BodyDeadlineException) { bodyDeadline = true; break; }
        }
        String detail = switch (code) {
            case SSRF_BLOCKED -> "Giden istek güvenlik kuralı (SSRF kalkanı) " + h + " hedefini engelledi; istek hiç gönderilmedi.";
            case CONFIG_ERROR -> "İzlemenin URL'si geçersiz (şema ya da host yok); istek gönderilmedi.";
            case DNS -> h + " adı DNS'te çözümlenemedi; sunucuya hiç bağlanılmadı.";
            case TIMEOUT_CONNECT -> (viaProxy ? "Vekile" : h + " sunucusuna") + " " + timeoutMs
                    + " ms içinde bağlantı kurulamadı (TCP ya da TLS el sıkışması zaman aşımı).";
            case TIMEOUT_READ -> bodyDeadline
                    ? "Yanıt başlıkları geldi ama sayfa gövdesi " + timeoutMs + " ms içinde tamamen okunamadı; anahtar kelime aranamadı."
                    : "Bağlantı kuruldu ama " + timeoutMs + " ms içinde yanıt gelmedi (zaman aşımı).";
            case TLS_HANDSHAKE -> h + " ile TLS el sıkışması başarısız: " + raw;
            case TLS_CERT -> h + " sunucusunun sertifikası doğrulanamadı: " + raw;
            case CONNECTION_REFUSED -> (viaProxy ? "Vekil" : h + " sunucusu") + " bağlantıyı reddetti (port kapalı ya da servis çalışmıyor).";
            case CONNECTION_RESET -> "Bağlantı yanıt tamamlanmadan karşı taraf ya da aradaki cihaz tarafından kapatıldı/sıfırlandı.";
            case HOST_UNREACHABLE -> h + " adresine ağ yolu yok (host ya da ağ erişilemez).";
            case PROXY -> "Kurumsal vekil (proxy) üzerinden hedefe ulaşılamadı: " + raw;
            case REDIRECT_LIMIT -> "Çok fazla yönlendirme — sayfa sonsuz yönlendirme döngüsünde olabilir.";
            case PROTOCOL_ERROR -> "Sunucunun yanıtı geçerli bir HTTP yanıtı değil: " + raw;
            default -> "İstek tamamlanamadı: " + raw;
        };
        return new Reason(code, cap(detail));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Koşul (sayfa geldi, kural sağlanmadı)
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Kural "hiç geçmemeli" mi (LTE 0 / EQ 0 / LT 1) — arayüzdeki {@code ruleOf} ile aynı sınıflandırma.
     */
    public static boolean isAbsenceRule(String op, int threshold) {
        String o = op == null ? "GTE" : op;
        return ((o.equals("LTE") || o.equals("EQ")) && threshold == 0) || (o.equals("LT") && threshold == 1);
    }

    /**
     * Koşul sağlanmadığında neden. Öncelik: (kelime YOKSA) yönlendirme izlenemedi → hata durum kodu → boş gövde → okuma
     * tavanı → bulunamadı; (kelime VARSA) "olmamalı" kuralı → yasak bulundu, değilse adet uymadı.
     *
     * @param status       son yanıtın durum kodu
     * @param count        bulunan adet
     * @param op           izlemenin operatörü
     * @param threshold    izlemenin eşiği
     * @param bodyBytes    okunan gövde boyutu
     * @param truncated    gövde tavana takıldı
     * @param keyword      aranan metin (ayrıntı metni için)
     */
    public static Reason forCondition(int status, int count, String op, int threshold, long bodyBytes,
                                      boolean truncated, String keyword) {
        String kw = "« " + abbreviate(keyword == null ? "" : keyword, 60) + " »";
        String rule = com.sitemonitor.service.KeywordCheckerService.opPhrase(op, threshold);
        String code;
        String detail;
        if (count <= 0) {
            if (status >= 300 && status < 400) {
                code = REDIRECT_BLOCKED;
                detail = "Sunucu HTTP " + status + " ile yönlendirdi ama yönlendirme izlenemedi (https→http düşürmesi ya da "
                        + "geçersiz Location); " + kw + " aranacak sayfaya ulaşılamadı. Koşul: " + rule + ".";
            } else if (status >= 400) {
                code = HTTP_STATUS;
                detail = "Sunucu HTTP " + status + " döndürdü; gelen (büyük olasılıkla hata) sayfada " + kw
                        + " bulunamadı. Koşul: " + rule + ".";
            } else if (bodyBytes <= 0) {
                code = EMPTY_BODY;
                detail = "Sunucu HTTP " + status + " ile BOŞ bir gövde döndürdü; " + kw + " aranacak içerik yoktu. Koşul: " + rule + ".";
            } else if (truncated) {
                code = BODY_TRUNCATED;
                detail = "Sayfanın yalnız ilk " + (bodyBytes / 1024) + " KB'ı okundu (okuma tavanı); " + kw
                        + " bu bölümde yok — sayfanın devamında olabilir. Koşul: " + rule + ".";
            } else {
                code = KEYWORD_NOT_FOUND;
                detail = "Sayfa yüklendi (HTTP " + status + ", " + human(bodyBytes) + ") ama " + kw
                        + " hiç bulunamadı. Koşul: " + rule + ".";
            }
        } else if (isAbsenceRule(op, threshold)) {
            code = KEYWORD_FOUND_FORBIDDEN;
            detail = kw + " sayfada " + count + " kez bulundu; bu kelimenin sayfada hiç OLMAMASI gerekiyor (HTTP " + status + ").";
        } else {
            code = KEYWORD_COUNT_MISMATCH;
            detail = kw + " sayfada " + count + " kez bulundu; koşul " + rule + " (HTTP " + status + ").";
        }
        return new Reason(code, cap(detail));
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────

    /** İstisnanın ilk anlamlı mesajı (zincirde boş olmayan ilk mesaj), tek satır, ≤ 200. */
    static String rawMessage(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) return abbreviate(m.replaceAll("\\s+", " ").trim(), 200);
        }
        return t == null ? "bilinmeyen hata" : t.getClass().getSimpleName();
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    public static String cap(String s) {
        if (s == null) return null;
        return s.length() <= DETAIL_MAX ? s : s.substring(0, DETAIL_MAX - 1) + "…";
    }
}
