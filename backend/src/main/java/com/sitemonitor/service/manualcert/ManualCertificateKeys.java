package com.sitemonitor.service.manualcert;

import com.sitemonitor.model.InventoryDomainKey;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manuel sertifika "takip adı" kuralı (2026-10-06) — {@code DomainNames}'ten AYRI bir doğrulayıcı.
 *
 * <p>Elle yüklenen sertifikanın ağda bir adresi yoktur; envanter anahtarı ({@code certificate_inventory.domain})
 * kullanıcının seçtiği bir addır. Biçim: {@link InventoryDomainKey#MANUAL_PATTERN} (küçük harf; {@code . _ -};
 * boşluk, {@code : / @} yok; en çok 253 karakter). Tekillik (silinmişler dahil, harf duyarsız) çağıranın işidir.
 */
public final class ManualCertificateKeys {

    private ManualCertificateKeys() { }

    /** Öneri çakışınca eklenen sonek: {@code <cn>-manuel}, {@code <cn>-manuel-2} … */
    public static final String SUFFIX = "-manuel";

    /** Öneri tabanının azami uzunluğu — sonek eklenince 253'ü aşmasın. */
    private static final int BASE_MAX = 236;

    /** Kırpar + küçük harfe indirir ve doğrular; geçersizse {@link IllegalArgumentException} (yerelleştirilmiş). */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(Msg.t("Takip adı zorunludur.", "A tracking name is required."));
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!InventoryDomainKey.KeyValidator.isValidManualKey(key)) {
            throw new IllegalArgumentException(Msg.t(
                    "Geçersiz takip adı: yalnız küçük harf, rakam ve . _ - kullanılabilir (boşluk, : / @ olmaz; en çok 253 karakter).",
                    "Invalid tracking name: only lowercase letters, digits and . _ - are allowed (no spaces, : / @; at most 253 characters)."));
        }
        return key;
    }

    /** Biçim geçerli mi (istisnasız). */
    public static boolean isValid(String key) {
        return InventoryDomainKey.KeyValidator.isValidManualKey(key);
    }

    /**
     * Öneri tabanı: CN (yoksa ilk SAN), küçük harf; geçersiz karakterler {@code -} olur, baş/son temizlenir.
     * Hiçbiri kullanılamazsa {@code sertifika}.
     */
    public static String baseSuggestion(String cn, List<String> san) {
        String src = cn;
        if (src == null || src.isBlank() || "Unknown".equals(src)) {
            src = (san != null && !san.isEmpty()) ? san.get(0) : null;
        }
        String s = sanitize(src);
        return s == null ? "sertifika" : s;
    }

    /** Ham metni anahtar biçimine indirger; kullanılamazsa null. */
    static String sanitize(String raw) {
        if (raw == null) return null;
        String l = raw.trim().toLowerCase(Locale.ROOT);
        boolean wildcard = l.startsWith("*.");
        if (wildcard) l = l.substring(2);
        StringBuilder sb = new StringBuilder(l.length());
        char prev = 0;
        for (char c : l.toCharArray()) {
            char out = ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') ? c : '-';
            if (out == '-' && prev == '-') continue;   // ardışık tireleri tek tireye indir
            sb.append(out);
            prev = out;
        }
        String s = sb.toString();
        int start = 0, end = s.length();
        while (start < end && !Character.isLetterOrDigit(s.charAt(start))) start++;
        while (end > start && !Character.isLetterOrDigit(s.charAt(end - 1))) end--;
        s = s.substring(start, end);
        if (s.length() > BASE_MAX) {
            s = s.substring(0, BASE_MAX);
            while (!s.isEmpty() && !Character.isLetterOrDigit(s.charAt(s.length() - 1))) s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return null;
        String key = (wildcard ? "*." : "") + s;
        return isValid(key) ? key : (isValid(s) ? s : null);
    }

    /** Taban + sonekli adaylar, öncelik sırasıyla: taban, -manuel, -manuel-2 … -manuel-{n}. */
    public static List<String> candidates(String base, int n) {
        List<String> out = new ArrayList<>(n + 2);
        out.add(base);
        out.add(base + SUFFIX);
        for (int i = 2; i <= n; i++) out.add(base + SUFFIX + "-" + i);
        return out;
    }
}
