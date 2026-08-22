package com.sitemonitor.service;

import java.util.List;
import java.util.Set;

/**
 * Şablon kütüphanesinin KATEGORİ anahtarları — ağaç görünümünün dalları.
 *
 * <p><b>Neden sabit küme, serbest metin değil.</b> Dal başlıkları iki dilde gösteriliyor ve
 * etiketler i18n'den geliyor. Serbest metin olsaydı her kullanıcı kendi yazımıyla yeni bir dal
 * açardı ("Ödeme", "odeme", "Payment", "ödeme ") ve ağaç birkaç hafta içinde kullanılamaz hâle
 * gelirdi — gruplamanın tek işi olan "aradığını daralt" faydası da kaybolurdu.
 *
 * <p><b>Sıra ANLAMLIDIR</b> ve arayüzdeki dal sırasıdır: bir siteyi izlemeye baştan başlayan
 * birinin ilerleyeceği yol — önce ayakta mı, sonra girilebiliyor mu, sonra iş akışları, en sonda
 * uçtan uca yolculuklar. Alfabetik sıralamak bu bilgiyi yok ederdi.
 *
 * <p>Bilinmeyen/boş kategori REDDEDİLMEZ: arayüz onları "Diğer" dalında toplar. Kategori bir
 * düzenleme kolaylığıdır, bir doğrulama kuralı değil — kullanıcıyı kaydettiği script'ten
 * kategori seçmediği için mahrum bırakmak orantısız olurdu.
 */
public final class ScriptedTemplateCategories {

    private ScriptedTemplateCategories() {}

    public static final String AVAILABILITY = "availability";
    public static final String IDENTITY     = "identity";
    public static final String SEARCH       = "search";
    public static final String CHECKOUT     = "checkout";
    public static final String FORMS        = "forms";
    public static final String API          = "api";
    public static final String CONTENT_SEO  = "content-seo";
    public static final String PERFORMANCE  = "performance";
    public static final String SECURITY     = "security";
    public static final String JOURNEY      = "journey";

    /** Arayüzdeki dal sırası (bkz. sınıf javadoc'u — alfabetik DEĞİL, öğrenme sırası). */
    public static final List<String> ORDER = List.of(
            AVAILABILITY, IDENTITY, SEARCH, CHECKOUT, FORMS,
            API, CONTENT_SEO, PERFORMANCE, SECURITY, JOURNEY);

    private static final Set<String> KNOWN = Set.copyOf(ORDER);

    public static boolean isKnown(String key) {
        return key != null && KNOWN.contains(key);
    }

    /** Kaydedilecek biçim: bilinen anahtar aynen, gerisi {@code null} (arayüzde "Diğer"). */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String k = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return isKnown(k) ? k : null;
    }
}
