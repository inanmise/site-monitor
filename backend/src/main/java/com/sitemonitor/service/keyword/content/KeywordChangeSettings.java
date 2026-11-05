package com.sitemonitor.service.keyword.content;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.keyword.rules.KeywordScopes;

/**
 * İçerik değişimi ayarlarının istek doğrulaması (kw sözleşmesi §10.1: {@code changeDetect}, {@code changeMask},
 * {@code changeSelector}) — saf. Alan hatası anahtarı istek anahtarıdır.
 */
public final class KeywordChangeSettings {

    private KeywordChangeSettings() {}

    /** {@code changeSelector}: boş → {@code null} (tüm görünür metin); dolu → CSS seçici doğrulaması (kural bölgesiyle aynı). */
    public static String selector(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        String why = KeywordScopes.validateCss(s);
        if (why != null) throw new FieldValidationException("changeSelector", why);
        return s;
    }

    /** {@code changeMask}: liste ya da CSV → kanonik CSV ya da {@code null}. */
    public static String mask(Object raw) {
        return ContentMasker.parseRequest(raw);
    }

    /**
     * Değişim ayarı değişti mi (temel silinmeli + açık KEYWORD_CHANGED sessizce kapanmalı — §7.2 güncelleme yan etkileri):
     * değişim kapatıldı, ya da maske / seçici farklı.
     */
    public static boolean settingsChanged(Boolean oldDetect, String oldMask, String oldSelector,
                                          Boolean newDetect, String newMask, String newSelector) {
        boolean wasOn = Boolean.TRUE.equals(oldDetect);
        boolean isOn = Boolean.TRUE.equals(newDetect);
        if (wasOn && !isOn) return true;
        if (!wasOn) return false;   // kapalıydı: temel zaten yok
        return !KeywordChangeDetector.specHash(oldMask, oldSelector).equals(KeywordChangeDetector.specHash(newMask, newSelector));
    }
}
