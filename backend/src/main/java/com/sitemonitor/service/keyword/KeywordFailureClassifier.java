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
    // ── kw sözleşmesi §11.2 (2026-11-05) — gelişmiş istek (be1) ──
    /** Son (yönlendirmeler sonrası) URL beklenen adresle başlamıyor (örn. oturum düştü → giriş sayfası). */
    public static final String FINAL_URL_MISMATCH = "FINAL_URL_MISMATCH";
    /** Yönlendirme takibi KAPALI; 3xx yanıtın kendisi değerlendirildi ve koşul tutmadı. */
    public static final String REDIRECT_NOT_FOLLOWED = "REDIRECT_NOT_FOLLOWED";
    // ── kw sözleşmesi §11.2 — çoklu kural / kapsam / boyut (be2 Aşama 2 besler; metin burada) ──
    public static final String RULES_NOT_MET = "RULES_NOT_MET";
    public static final String SCOPE_NOT_FOUND = "SCOPE_NOT_FOUND";
    public static final String SCOPE_INVALID = "SCOPE_INVALID";
    public static final String BODY_TOO_SMALL = "BODY_TOO_SMALL";
    public static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";
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
            REDIRECT_BLOCKED, FINAL_URL_MISMATCH, REDIRECT_NOT_FOLLOWED, RULES_NOT_MET, SCOPE_NOT_FOUND, SCOPE_INVALID,
            BODY_TOO_SMALL, BODY_TOO_LARGE, TIMEOUT_CONNECT, TIMEOUT_READ, DNS, TLS_HANDSHAKE, TLS_CERT, CONNECTION_REFUSED,
            CONNECTION_RESET, HOST_UNREACHABLE, PROXY, SSRF_BLOCKED, CONFIG_ERROR, REDIRECT_LIMIT, PROTOCOL_ERROR, UNKNOWN);

    /**
     * İsteğin TAMAMLANMADIĞI (sayfa okunamadı) neden kodları — alarm postası / iletisi "Sayfa Açılamadı" der, "0 kez bulundu"
     * demez (kw sözleşmesi §8.1, B1). Ön yüz {@code keywordFailureModel.REQUEST_CODES} ile aynı aile.
     */
    public static final java.util.Set<String> REQUEST_FAILURE_CODES = java.util.Set.of(
            TIMEOUT_CONNECT, TIMEOUT_READ, DNS, TLS_HANDSHAKE, TLS_CERT, CONNECTION_REFUSED, CONNECTION_RESET,
            HOST_UNREACHABLE, PROXY, SSRF_BLOCKED, CONFIG_ERROR, REDIRECT_LIMIT, PROTOCOL_ERROR, UNKNOWN);

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
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable cur = t; cur != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; cur = cur.getCause() == cur ? null : cur.getCause()) {
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
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable cur = t; cur != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; cur = cur.getCause() == cur ? null : cur.getCause()) {
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
        return forCheck(new Facts(status, count, op, threshold, bodyBytes, truncated, keyword,
                null, null, null, null, null, true));
    }

    /**
     * Sayfa geldi ama kontrol sağlıksız — tüm gözlemler (kw sözleşmesi §6.3, 2026-11-05).
     *
     * @param statusAccepted   kabul edilen durum kuralı: null = KAPALI (bugünkü), true / false = sonuç
     * @param expectedStatus   kuralın metni (ayrıntı için)
     * @param finalUrlOk       son URL kuralı: null = yok, true / false = sonuç
     * @param expectedFinalUrl kuralın metni (ayrıntı için)
     * @param finalUrl         gözlenen son URL (MASKELİ, ayrıntı için)
     * @param followRedirects  yönlendirmeler takip ediliyor mu (false + 3xx → {@link #REDIRECT_NOT_FOLLOWED})
     */
    public record Facts(int status, int count, String op, int threshold, long bodyBytes, boolean truncated, String keyword,
                        Boolean statusAccepted, String expectedStatus, Boolean finalUrlOk, String expectedFinalUrl,
                        String finalUrl, boolean followRedirects) {}

    /**
     * Kontrol sağlıksızken TEK neden (öncelik §6.3): yönlendirme izlenemedi / takip kapalı (kelime yokken) → durum kodu (kural
     * açık ve kabul dışı; ya da kural kapalı, ≥ 400 ve kelime yok — bugünkü) → son URL uymadı → boş gövde → (kelime yoksa okuma
     * tavanı, değilse) kural kodları. İstisna kodları burada değil ({@link #forException}); be2 Aşama 2 boyut / kural kodlarını
     * bu sıranın kendi yerlerine ekler.
     */
    public static Reason forCheck(Facts f) {
        return forCheck(f, null);
    }

    /**
     * Kural motoru / boyut gözlemleri (kw sözleşmesi §6.3, be2 Aşama 2). {@code null} = eski tek kelime yolu (bugünkü sıra).
     *
     * @param rulesMet     kural birleşimi sağlandı mı
     * @param multi        birden çok kural mı (ihlalde neden {@link #RULES_NOT_MET})
     * @param rulesSummary TR kural özeti (çok kuralda ayrıntı metni)
     * @param scopeNote    tek kuralın bölge notu ({@code SCOPE_NOT_FOUND} / {@code SCOPE_INVALID}) ya da {@code null}
     * @param sizeOk       boyut kuralı: {@code null} = kapalı, {@code false} = sınır dışı
     * @param sizeReason   {@link #BODY_TOO_SMALL} / {@link #BODY_TOO_LARGE}
     * @param sizeDetail   boyut ayrıntısı (TR)
     */
    public record RuleFacts(boolean rulesMet, boolean multi, String rulesSummary, String scopeNote, Boolean sizeOk,
                            String sizeReason, String sizeDetail) {}

    /**
     * Kontrol sağlıksızken TEK neden — kural / boyut gözlemleriyle (kw sözleşmesi §6.3): yönlendirme (kural tutmadı, kelime yok)
     * &gt; durum kodu &gt; son URL &gt; boş gövde &gt; boyut &gt; kural kodları (çok kural {@link #RULES_NOT_MET}; tek kuralda bölge notu
     * {@link #SCOPE_NOT_FOUND} / {@link #SCOPE_INVALID}; değilse bugünkü kelime kodları ve {@link #BODY_TRUNCATED} bugünkü yerinde).
     * {@code r == null} → bugünkü sıra AYNEN.
     */
    public static Reason forCheck(Facts f, RuleFacts r) {
        String kw = "« " + abbreviate(f.keyword() == null ? "" : f.keyword(), 60) + " »";
        String rule = com.sitemonitor.service.KeywordCheckerService.opPhrase(f.op(), f.threshold());
        int status = f.status();
        int count = f.count();
        boolean redirect = status >= 300 && status < 400;
        boolean rulesHeld = r != null && r.rulesMet();
        if (count <= 0 && redirect && !rulesHeld) {
            if (!f.followRedirects()) {
                return new Reason(REDIRECT_NOT_FOLLOWED, cap("Yönlendirme takibi kapalı: sunucu HTTP " + status
                        + " (yönlendirme) döndürdü ve bu yanıtın kendisi değerlendirildi; " + kw + " bulunamadı. Koşul: " + rule
                        + ". Hedef sayfayı izlemek için \"Yönlendirmeleri takip et\" seçeneğini açın."));
            }
            return new Reason(REDIRECT_BLOCKED, cap("Sunucu HTTP " + status + " ile yönlendirdi ama yönlendirme izlenemedi "
                    + "(https→http düşürmesi ya da geçersiz Location); " + kw + " aranacak sayfaya ulaşılamadı. Koşul: " + rule + "."));
        }
        if (Boolean.FALSE.equals(f.statusAccepted())) {
            boolean holds = com.sitemonitor.service.KeywordCheckerService.evaluate(count, f.op(), f.threshold());
            return new Reason(HTTP_STATUS, cap("Kabul edilen: " + nz(f.expectedStatus()) + "; gelen: " + status
                    + ". Sayfa kabul edilen bir durum koduyla dönmedi (kelime koşulu " + (holds ? "sağlansa bile" : "da sağlanmadı")
                    + "). Koşul: " + rule + "."));
        }
        if (f.statusAccepted() == null && count <= 0 && status >= 400 && !rulesHeld) {
            return new Reason(HTTP_STATUS, cap("Sunucu HTTP " + status + " döndürdü; gelen (büyük olasılıkla hata) sayfada " + kw
                    + " bulunamadı. Koşul: " + rule + "."));
        }
        if (Boolean.FALSE.equals(f.finalUrlOk())) {
            return new Reason(FINAL_URL_MISMATCH, cap("Son adres beklenenle başlamıyor — beklenen: " + nz(f.expectedFinalUrl())
                    + "; gelinen: " + nz(f.finalUrl()) + " (oturum düşüp giriş sayfasına yönlendirilmiş olabilir)."));
        }
        if (r == null) return forConditionOnly(status, count, f.op(), f.threshold(), f.bodyBytes(), f.truncated(), kw, rule);
        // ── kw-be2 Aşama 2 (§6.3): boş gövde > boyut > kural kodları ──
        if (f.bodyBytes() <= 0 && (!r.rulesMet() || Boolean.FALSE.equals(r.sizeOk()))) {
            return new Reason(EMPTY_BODY, cap("Sunucu HTTP " + status + " ile BOŞ bir gövde döndürdü; " + kw
                    + " aranacak içerik yoktu. Koşul: " + rule + "."));
        }
        if (Boolean.FALSE.equals(r.sizeOk())) {
            String code = BODY_TOO_SMALL.equals(r.sizeReason()) ? BODY_TOO_SMALL : BODY_TOO_LARGE;
            return new Reason(code, cap(r.sizeDetail() == null || r.sizeDetail().isBlank()
                    ? "Sayfa boyutu beklenen aralıkta değil." : r.sizeDetail()));
        }
        if (!r.rulesMet() && r.multi()) {
            String sum = r.rulesSummary() == null || r.rulesSummary().isBlank() ? "kurallar sağlanmadı" : r.rulesSummary();
            return new Reason(RULES_NOT_MET, cap("İçerik kuralları sağlanmadı (HTTP " + status + "): " + sum));
        }
        if (!r.rulesMet() && SCOPE_NOT_FOUND.equals(r.scopeNote())) {
            return new Reason(SCOPE_NOT_FOUND, cap("Kuralın aradığı bölge sayfada bulunamadı (CSS seçicisi hiçbir öğe seçmedi ya da JSON "
                    + "yolu yok); " + kw + " değerlendirilemedi, kural sağlanmadı sayıldı (HTTP " + status + ")."));
        }
        if (!r.rulesMet() && SCOPE_INVALID.equals(r.scopeNote())) {
            return new Reason(SCOPE_INVALID, cap("Kural JSON bölgesinde arıyor ama yanıt gövdesi geçerli JSON değil (HTTP " + status
                    + "); kural sağlanmadı sayıldı."));
        }
        return forConditionOnly(status, count, f.op(), f.threshold(), f.bodyBytes(), f.truncated(), kw, rule);
    }

    /** Kuralın kendisi (eski {@code forCondition} gövdesinin boş gövde / tavan / kelime dalları — metinler AYNEN). */
    private static Reason forConditionOnly(int status, int count, String op, int threshold, long bodyBytes, boolean truncated,
                                           String kw, String rule) {
        String code;
        String detail;
        if (count <= 0) {
            if (bodyBytes <= 0) {
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
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable cur = t; cur != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; cur = cur.getCause() == cur ? null : cur.getCause()) {
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

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
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
