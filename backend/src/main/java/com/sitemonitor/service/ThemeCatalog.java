package com.sitemonitor.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Arayüz temaları (2026-10-05, kullanıcı isteği: "blueprint, parchment, Alloy, Obsidian, slag, crucible gibi temalar
 * seçilebilir olsun … bazı temaları listeden çıkarabilmeliyim … dark ve light da ekstra temalar olsun").
 *
 * <p><b>Tek kaynak.</b> Tema listesi ve her temanın renk şeması ({@code light}/{@code dark}) burada ve ön yüzde
 * {@code frontend/src/theme/themes.js} {@code THEMES} dizisinde AYNI SIRAYLA durur — {@code ThemeCatalogSyncTest}
 * ikisini karşılaştırır. Renk jetonları yalnız CSS'tedir ({@code styles/globals.css} şema blokları +
 * {@code styles/themes.css} tema blokları); sunucu yalnız kimlik + şema + yönetici politikası bilir.
 *
 * <p><b>Ayarlar</b> (AppSettingsCatalog grubu {@code appearance}, GLOBAL_ONLY — Ayarlar → Görünüm → Temalar):
 * <ul>
 *   <li>{@link #KEY_ENABLED} — kullanıcı seçicisinde listelenen temalar (CSV; varsayılan sekizi de).</li>
 *   <li>{@link #KEY_DEFAULT} — seçimi olmayan / seçtiği tema kapatılan kullanıcının teması: {@link #SYSTEM}
 *       (işletim sistemine göre Açık/Koyu) ya da açık bir tema kimliği.</li>
 * </ul>
 * Kurallar {@link #validate}: kimlikler bilinmeli, en az bir tema açık, varsayılan açık olmalı, {@code system}
 * Açık + Koyu'nun ikisini de ister. Kayıt yolu fark etmeksizin ({@code ThemeSettingsController} ya da Genel Ayarlar ucu)
 * {@code AppSettingsService} aynı kuralı uygular.
 *
 * <p><b>Açık uç.</b> {@code GET /api/branding} {@link #publicView} döner — giriş sayfası ve ilk boyama hangi temaların
 * serbest olduğunu bilir. Görünüm HOŞGÖRÜLÜDÜR: depoda geçersiz bir değer kalsa bile (elle DB düzenlemesi) istemciye
 * her zaman tutarlı bir politika gider, uç hata vermez.
 */
public final class ThemeCatalog {

    private ThemeCatalog() {}

    public static final String KEY_ENABLED = "site.monitor.theme.enabled";
    public static final String KEY_DEFAULT = "site.monitor.theme.default";
    /** Varsayılan değer: işletim sisteminin açık/koyu tercihine göre {@code light} ya da {@code dark}. */
    public static final String SYSTEM = "system";

    public record Theme(String id, String scheme) {}

    /** SIRA ÖNEMLİ — ön yüz {@code THEMES} ile aynı (ThemeCatalogSyncTest). Açık ve Koyu temel temalardır. */
    public static final List<Theme> ALL = List.of(
            new Theme("light", "light"),
            new Theme("dark", "dark"),
            new Theme("blueprint", "dark"),
            new Theme("parchment", "light"),
            new Theme("alloy", "light"),
            new Theme("obsidian", "dark"),
            new Theme("slag", "dark"),
            new Theme("crucible", "dark"));

    public static final List<String> IDS = ALL.stream().map(Theme::id).toList();

    /** {@link #KEY_ENABLED} varsayılanı: sekiz temanın tamamı (application.properties ile aynı). */
    public static final String DEFAULT_ENABLED_CSV = String.join(",", IDS);

    /** {@link #KEY_DEFAULT} ENUM seçenekleri: {@code system} + her tema. */
    public static final List<String> DEFAULT_OPTIONS = Stream.concat(Stream.of(SYSTEM), IDS.stream()).toList();

    /** Alan adlı doğrulama sorunu ({@code enabled} / {@code default}) + iki dilde ileti. */
    public record Problem(String field, String tr, String en) {}

    public static boolean isKnown(String id) {
        return id != null && IDS.contains(id);
    }

    /** Temanın renk şeması; bilinmeyen kimlik → {@code null}. */
    public static String schemeOf(String id) {
        for (Theme t : ALL) if (t.id().equals(id)) return t.scheme();
        return null;
    }

    /** CSV → kırpılmış, boşsuz, tekrarsız kimlik listesi (girdi sırası korunur; doğrulama YAPILMAZ). */
    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String p : csv.split(",")) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }

    /** Bilinen kimlikleri KATALOG sırasına dizer (kayıtta ve görünümde tek biçim). */
    public static List<String> canonical(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : IDS) if (ids != null && ids.contains(id)) out.add(id);
        return out;
    }

    /**
     * Politika doğrulaması. Sorun yoksa {@code null}. Sıra: liste (boş / bilinmeyen) → varsayılan.
     *
     * @param enabled açık tema kimlikleri (null/boş = hiç açık tema yok)
     * @param def     varsayılan ({@code system} ya da tema kimliği; boş = {@code system})
     */
    public static Problem validate(List<String> enabled, String def) {
        if (enabled == null || enabled.isEmpty()) {
            return new Problem("enabled", "En az bir tema listede açık olmalı.", "At least one theme must be enabled.");
        }
        for (String id : enabled) {
            if (!isKnown(id)) {
                return new Problem("enabled", "Bilinmeyen tema: " + id, "Unknown theme: " + id);
            }
        }
        String d = def == null || def.isBlank() ? SYSTEM : def.trim();
        if (SYSTEM.equals(d)) {
            if (!enabled.contains("light") || !enabled.contains("dark")) {
                return new Problem("default",
                        "\"Sistem\" varsayılanı için Açık ve Koyu temaların ikisi de açık olmalı.",
                        "The \"System\" default needs both the Light and the Dark theme enabled.");
            }
            return null;
        }
        if (!isKnown(d)) {
            return new Problem("default", "Bilinmeyen varsayılan tema: " + d, "Unknown default theme: " + d);
        }
        if (!enabled.contains(d)) {
            return new Problem("default", "Varsayılan tema listede açık olmalı.", "The default theme must be enabled.");
        }
        return null;
    }

    /**
     * Hoşgörülü etkin politika — istemciye giden biçim {@code {enabled:[...], default:"..."}}. Bilinmeyen kimlikler
     * atılır; liste boş kalırsa sekizi de açık sayılır; varsayılan geçersizse {@code system} (Açık + Koyu açıksa) ya da
     * listedeki ilk tema. Hiçbir zaman fırlatmaz.
     */
    public static Map<String, Object> effective(String enabledCsv, String def) {
        List<String> en = canonical(parseCsv(enabledCsv));
        if (en.isEmpty()) en = IDS;
        String d = def == null ? SYSTEM : def.trim();
        boolean systemOk = en.contains("light") && en.contains("dark");
        String eff;
        if ((SYSTEM.equals(d) || d.isEmpty()) && systemOk) eff = SYSTEM;
        else if (isKnown(d) && en.contains(d)) eff = d;
        else eff = systemOk ? SYSTEM : en.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", en);
        m.put("default", eff);
        return m;
    }

    /** Depodaki (yoksa varsayılan) değerlerden etkin politika — {@code GET /api/branding} {@code themes} alanı. */
    public static Map<String, Object> publicView(AppSettingsService settings) {
        return effective(settings.getString(KEY_ENABLED, DEFAULT_ENABLED_CSV), settings.getString(KEY_DEFAULT, SYSTEM));
    }
}
