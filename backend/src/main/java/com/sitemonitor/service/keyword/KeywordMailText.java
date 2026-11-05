package com.sitemonitor.service.keyword;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anahtar kelime ALARM POSTASI / İLETİSİ metinleri (kw sözleşmesi §8.1, E1 + B1, 2026-11-05) — saf, Türkçe. Posta gövdesi ve
 * webhook iletisi sunucuda kurulur; arayüzün dinamik i18n anahtarlarına (kwhint.* / kwfail.*) erişimi yoktur. Başlıklar
 * arayüzdekilerle BİREBİR aynı olmalı: {@code KeywordMailTextTest} {@code frontend/src/i18n/tr.js}'deki
 * {@code kwhint.<KOD>.title} ve {@code kwfail.<KOD>.short} değerlerini okuyup karşılaştırır.
 *
 * <p><b>B1 — başlık GÖZLEMDEN.</b> Eskiden posta başlığı yalnız operatör + eşikten kuruluyordu: "içermemeli" kuralında sayfa hiç
 * açılamasa da (DNS / zaman aşımı) "İstenmeyen İfade Bulundu" ve "0 kez bulundu" yazıyordu. Şimdi başlık kontrolün nedeninden
 * ({@code failure_reason}) gelir; istek tamamlanmadıysa "Sayfa Açılamadı".
 */
public final class KeywordMailText {

    private KeywordMailText() {}

    /** İpucu kodu → TR başlık ({@code tr.js kwhint.<KOD>.title} ile AYNI). */
    public static final Map<String, String> HINT_TITLES = Map.ofEntries(
            Map.entry(KeywordBodyAnalyzer.CASE_MISMATCH, "Büyük / küçük harf farkı"),
            Map.entry(KeywordBodyAnalyzer.WHITESPACE_OR_ENTITY, "Boşluk ya da HTML kodlaması farkı"),
            Map.entry(KeywordBodyAnalyzer.CHARSET, "Karakter kümesi farkı"),
            Map.entry(KeywordBodyAnalyzer.WAF_OR_BLOCK_PAGE, "WAF / engelleme sayfası geldi"),
            Map.entry(KeywordBodyAnalyzer.LOGIN_PAGE, "Giriş sayfası geldi"),
            Map.entry(KeywordBodyAnalyzer.JS_RENDERED, "İçerik JavaScript ile çiziliyor"),
            Map.entry(KeywordBodyAnalyzer.REDIRECTED_ELSEWHERE, "Başka bir siteye yönlendirildi"),
            Map.entry(KeywordBodyAnalyzer.MAINTENANCE_PAGE, "Bakım sayfası geldi"),
            Map.entry(KeywordBodyAnalyzer.ERROR_PAGE, "Hata sayfası (başarılı durum koduyla)"),
            Map.entry(KeywordBodyAnalyzer.NON_TEXT_CONTENT, "Metin olmayan içerik"));

    /** İpucu kodu → tek satırlık TR neden (yer tutucusuz; postada başlığın yanında). */
    public static final Map<String, String> HINT_CAUSES = Map.ofEntries(
            Map.entry(KeywordBodyAnalyzer.CASE_MISMATCH, "metin sayfada yalnız farklı büyük / küçük harfle geçiyor; izleme harf duyarlı arıyor"),
            Map.entry(KeywordBodyAnalyzer.WHITESPACE_OR_ENTITY, "metin sayfada farklı boşluklarla ya da HTML varlığıyla (&uuml;, &nbsp;) yazılmış"),
            Map.entry(KeywordBodyAnalyzer.CHARSET, "sayfanın karakter kümesi bildirimi ile içeriği uyuşmuyor; başka bir okumayla metin bulunuyor"),
            Map.entry(KeywordBodyAnalyzer.WAF_OR_BLOCK_PAGE, "gelen sayfa bir güvenlik duvarının (WAF / bot koruması) engelleme sayfasına benziyor"),
            Map.entry(KeywordBodyAnalyzer.LOGIN_PAGE, "gelen sayfa bir oturum açma sayfasına benziyor (parola alanı ya da /login adresi)"),
            Map.entry(KeywordBodyAnalyzer.JS_RENDERED, "ham HTML'de neredeyse hiç metin yok; içerik tarayıcıda JavaScript ile oluşturuluyor"),
            Map.entry(KeywordBodyAnalyzer.REDIRECTED_ELSEWHERE, "istek yönlendirmelerden sonra başka bir siteye ulaştı"),
            Map.entry(KeywordBodyAnalyzer.MAINTENANCE_PAGE, "gelen sayfa bir bakım / geçici olarak hizmet dışı sayfasına benziyor"),
            Map.entry(KeywordBodyAnalyzer.ERROR_PAGE, "durum kodu başarılı ama sayfa başlığı bir hata sayfasını gösteriyor"),
            Map.entry(KeywordBodyAnalyzer.NON_TEXT_CONTENT, "yanıtın içerik türü metin değil (PDF, resim, ikili dosya)"));

    /**
     * Neden kodu → kısa TR etiket ({@code tr.js kwfail.<KOD>.short} ile AYNI; {@code {status}} yer tutucusu çağıranda doldurulur).
     * Konu şablonunun {@code {neden}} değeri ve posta "Neden" satırının başlığı.
     */
    public static final Map<String, String> SHORT_REASONS = shortReasons();

    private static Map<String, String> shortReasons() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(KeywordFailureClassifier.KEYWORD_NOT_FOUND, "Kelime bulunamadı");
        m.put(KeywordFailureClassifier.KEYWORD_FOUND_FORBIDDEN, "Yasak kelime bulundu");
        m.put(KeywordFailureClassifier.KEYWORD_COUNT_MISMATCH, "Adet koşulu sağlanmadı");
        m.put(KeywordFailureClassifier.HTTP_STATUS, "HTTP {status} döndü");
        m.put(KeywordFailureClassifier.EMPTY_BODY, "Boş yanıt");
        m.put(KeywordFailureClassifier.BODY_TRUNCATED, "Okuma sınırı aşıldı");
        m.put(KeywordFailureClassifier.REDIRECT_BLOCKED, "Yönlendirme izlenemedi");
        m.put(KeywordFailureClassifier.FINAL_URL_MISMATCH, "Beklenmeyen adrese yönlendirildi");
        m.put(KeywordFailureClassifier.REDIRECT_NOT_FOLLOWED, "Yönlendirme takip edilmedi");
        m.put(KeywordFailureClassifier.RULES_NOT_MET, "İçerik kuralları sağlanmadı");
        m.put(KeywordFailureClassifier.SCOPE_NOT_FOUND, "İçerik bölgesi bulunamadı");
        m.put(KeywordFailureClassifier.SCOPE_INVALID, "İçerik bölgesi okunamadı");
        m.put(KeywordFailureClassifier.BODY_TOO_SMALL, "Sayfa beklenenden küçük");
        m.put(KeywordFailureClassifier.BODY_TOO_LARGE, "Sayfa beklenenden büyük");
        m.put(KeywordFailureClassifier.TIMEOUT_CONNECT, "Bağlantı zaman aşımı");
        m.put(KeywordFailureClassifier.TIMEOUT_READ, "Yanıt zaman aşımı");
        m.put(KeywordFailureClassifier.DNS, "DNS çözümlenemedi");
        m.put(KeywordFailureClassifier.TLS_HANDSHAKE, "TLS el sıkışması başarısız");
        m.put(KeywordFailureClassifier.TLS_CERT, "Sertifika hatası");
        m.put(KeywordFailureClassifier.CONNECTION_REFUSED, "Bağlantı reddedildi");
        m.put(KeywordFailureClassifier.CONNECTION_RESET, "Bağlantı kesildi");
        m.put(KeywordFailureClassifier.HOST_UNREACHABLE, "Sunucuya ulaşılamıyor");
        m.put(KeywordFailureClassifier.PROXY, "Vekil hatası");
        m.put(KeywordFailureClassifier.SSRF_BLOCKED, "Güvenlik kuralı engelledi");
        m.put(KeywordFailureClassifier.CONFIG_ERROR, "URL geçersiz");
        m.put(KeywordFailureClassifier.REDIRECT_LIMIT, "Yönlendirme döngüsü");
        m.put(KeywordFailureClassifier.PROTOCOL_ERROR, "Geçersiz HTTP yanıtı");
        m.put(KeywordFailureClassifier.UNKNOWN, "İstek tamamlanamadı");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Postada en çok kaç ipucu satırı. */
    public static final int MAX_HINT_LINES = 5;
    /** Postadaki sayfa alıntısı tavanı (karakter) — kayıttaki 600'den kısa. */
    public static final int MAIL_EXCERPT_MAX = 300;

    /** Kontrol isteğinin TAMAMLANMADIĞI (sayfa okunamadı) neden kodları. */
    public static final Set<String> REQUEST_FAILED = KeywordFailureClassifier.REQUEST_FAILURE_CODES;

    /** Kısa TR neden ({@code {status}} doldurulur); bilinmeyen / null kod → null. */
    public static String shortReason(String code, Object httpStatus) {
        if (code == null) return null;
        String s = SHORT_REASONS.get(code);
        if (s == null) return null;
        return s.replace("{status}", httpStatus == null ? "?" : String.valueOf(httpStatus));
    }

    /**
     * İstek tamamlandı mı? Neden kodu istek ailesindeyse ya da bağlamda hata metni varsa HAYIR. Nedeni olmayan eski bağlamda
     * yalnız hata metnine bakılır.
     */
    public static boolean requestFailed(String failureReason, Object error) {
        if (failureReason != null && REQUEST_FAILED.contains(failureReason)) return true;
        return error != null && !String.valueOf(error).isBlank() && !"null".equalsIgnoreCase(String.valueOf(error));
    }

    /**
     * Posta / ileti BAŞLIĞI (B1 — gözlemden): istek tamamlanmadı → "Sayfa Açılamadı"; diğerleri nedene göre. Nedeni olmayan eski
     * bağlamda (yeniden gönderim, eski anlık görüntü): hata varsa "Sayfa Açılamadı", "hiç geçmemeli" kuralında kelime gerçekten
     * bulunduysa "İstenmeyen İfade Bulundu", değilse "Koşul Sağlanmadı".
     */
    public static String headline(String failureReason, Object error, String operator, int threshold, Integer occurrences) {
        if (requestFailed(failureReason, error)) return "Sayfa Açılamadı";
        if (failureReason != null) {
            return switch (failureReason) {
                case KeywordFailureClassifier.HTTP_STATUS -> "Beklenmeyen HTTP Durumu";
                case KeywordFailureClassifier.FINAL_URL_MISMATCH -> "Beklenmeyen Adrese Yönlendirildi";
                case KeywordFailureClassifier.BODY_TOO_SMALL, KeywordFailureClassifier.BODY_TOO_LARGE -> "Sayfa Boyutu Beklenen Aralıkta Değil";
                case KeywordFailureClassifier.KEYWORD_FOUND_FORBIDDEN -> "İstenmeyen İfade Bulundu";
                case KeywordFailureClassifier.RULES_NOT_MET -> "İçerik Kuralları Sağlanmadı";
                case KeywordFailureClassifier.SCOPE_NOT_FOUND, KeywordFailureClassifier.SCOPE_INVALID -> "İçerik Bölgesi Bulunamadı";
                default -> "Koşul Sağlanmadı";
            };
        }
        boolean absent = KeywordFailureClassifier.isAbsenceRule(operator, threshold);
        return absent && occurrences != null && occurrences > 0 ? "İstenmeyen İfade Bulundu" : "Koşul Sağlanmadı";
    }

    /** İpucu satırları ("Başlık — neden"), bilinmeyen kod atlanır, en çok {@value #MAX_HINT_LINES}. */
    public static List<String> hintLines(Collection<?> codes) {
        List<String> out = new ArrayList<>();
        if (codes == null) return out;
        for (Object o : codes) {
            if (o == null) continue;
            String c = String.valueOf(o);
            String title = HINT_TITLES.get(c);
            if (title == null) continue;
            String cause = HINT_CAUSES.get(c);
            out.add(cause == null ? title : title + " — " + cause);
            if (out.size() >= MAX_HINT_LINES) break;
        }
        return out;
    }

    /** Bağlamdaki ipuçları (liste ya da JSON dizi metni) → kod listesi. */
    public static List<String> hintCodes(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) if (o != null) out.add(String.valueOf(o));
        } else if (raw instanceof CharSequence cs) {
            out.addAll(com.sitemonitor.model.KeywordResult.parseHints(cs.toString()));
        }
        return out;
    }

    /** Postadaki alıntı: ≤ {@value #MAIL_EXCERPT_MAX} karakter (kayıttaki alıntı zaten maskeli). */
    public static String mailExcerpt(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return s.length() <= MAIL_EXCERPT_MAX ? s : s.substring(0, MAIL_EXCERPT_MAX - 1) + "…";
    }
}
