package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.service.http.HttpRequestRules;
import com.sitemonitor.util.Msg;
import org.jsoup.select.QueryParser;

import java.util.Locale;

/**
 * Kural bölgesi (CSS seçici / JSON yolu) KAYIT doğrulaması (kw sözleşmesi §6.2) — saf. Çalışma anı çıkarımı
 * {@link KeywordContent}'te.
 *
 * <p><b>CSS güvenliği.</b> jsoup'un bazı seçicileri içeride {@code java.util.regex} çalıştırır: {@code :matches(…)},
 * {@code :matchesOwn(…)}, {@code :matchesWholeText(…)}, {@code :matchesWholeOwnText(…)} ve öznitelik {@code [a~=…]}
 * (jsoup'ta {@code ~=} DÜZENLİ İFADEDİR, CSS'teki "kelime listesi" değil). Kullanıcının seçicisi sayfa metni üzerinde geri
 * izlemeli motor koşturamasın diye bunlar reddedilir (ReDoS — kesilemez). İç içe {@code :has(…)} her öğe için alt ağacı
 * yeniden tarar; en çok BİR {@code :has(} izinli.
 */
public final class KeywordScopes {

    private KeywordScopes() {}

    /** Seçici / yol uzunluk üst sınırı. */
    public static final int MAX_VALUE = 300;

    /** CSS seçicisi geçerli mi: {@code null} = geçerli, değilse alan hatası iletisi (istek dilinde). */
    public static String validateCss(String selector) {
        if (selector == null || selector.isBlank()) {
            return Msg.t("CSS seçici boş olamaz (ör. #fiyat, .durum, main h1).",
                    "The CSS selector cannot be empty (e.g. #price, .status, main h1).");
        }
        String s = selector.trim();
        if (s.length() > MAX_VALUE) {
            return Msg.t("CSS seçici en fazla " + MAX_VALUE + " karakter olabilir.",
                    "The CSS selector can be at most " + MAX_VALUE + " characters.");
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return Msg.t("CSS seçici satır sonu ya da denetim karakteri içeremez.",
                        "The CSS selector cannot contain a line break or control character.");
            }
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains(":matches") || s.contains("~=")) {
            return Msg.t("Bu seçici düzenli ifade kullanıyor (:matches… ya da ~=) — güvenlik nedeniyle desteklenmez. Metni bir "
                            + "DÜZENLİ İFADE kuralıyla arayın, bölgeyi düz bir seçiciyle (#kimlik, .sınıf, etiket) seçin.",
                    "This selector uses a regular expression (:matches… or ~=) — not supported for security reasons. Search "
                            + "the text with a REGEX rule and pick the region with a plain selector (#id, .class, tag).");
        }
        if (occurrences(lower, ":has(") > 1) {
            return Msg.t("Seçicide en çok bir :has(…) kullanılabilir.", "At most one :has(…) can be used in the selector.");
        }
        try {
            QueryParser.parse(s);
            return null;
        } catch (RuntimeException e) {
            String why = e.getMessage() == null ? "" : e.getMessage();
            if (why.length() > 120) why = why.substring(0, 120) + "…";
            return Msg.t("Geçersiz CSS seçici: " + why, "Invalid CSS selector: " + why);
        }
    }

    /** JSON yolu geçerli mi: {@code null} = geçerli, değilse alan hatası iletisi (HTTP izlemesinin yol dilbilgisi). */
    public static String validateJsonPath(String path) {
        if (path == null || path.isBlank()) {
            return Msg.t("JSON yolu boş olamaz (ör. $.status ya da $.items[0].ad).",
                    "The JSON path cannot be empty (e.g. $.status or $.items[0].name).");
        }
        return HttpRequestRules.validateJsonPath(path);
    }

    private static int occurrences(String hay, String needle) {
        int n = 0;
        int from = 0;
        int idx;
        while (n < 10 && (idx = hay.indexOf(needle, from)) >= 0) {
            n++;
            from = idx + needle.length();
        }
        return n;
    }
}
