package com.sitemonitor.service.grafana;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Grafana panosu ayarları (2026-10-18, Ayarlar → Platform → Grafana panosu). Anahtarlar {@code AppSettingsCatalog}'da
 * grup {@code grafana}, hepsi GLOBAL_ONLY (Prometheus etiket eşleyicisi ad alanı / iş adı gibi altyapı bilgisi taşır);
 * varsayılanlar {@code application.properties}'te. Doğrulama TEK yerde: {@code AppSettingsService.validate} buradan
 * çağırır, denetleyici alan adıyla (400 {@code VALIDATION_FAILED} + {@code field}) aynı kuralları uygular.
 */
public final class GrafanaDashboardSettings {

    private GrafanaDashboardSettings() {}

    public static final String KEY_TITLE = "site.monitor.grafana.title";
    public static final String KEY_UID = "site.monitor.grafana.uid";
    public static final String KEY_LINK_BASE_URL = "site.monitor.grafana.link-base-url";
    public static final String KEY_SELECTOR = "site.monitor.grafana.selector";
    public static final String KEY_TIME_FROM = "site.monitor.grafana.time-from";
    public static final String KEY_REFRESH = "site.monitor.grafana.refresh";
    public static final String KEY_LANGUAGE = "site.monitor.grafana.language";
    public static final String KEY_SECTIONS = "site.monitor.grafana.sections";
    public static final String KEY_PER_MONITOR = "site.monitor.grafana.per-monitor";
    public static final String KEY_DEFAULT_TEAM = "site.monitor.grafana.default-team";
    public static final String KEY_TOP_N = "site.monitor.grafana.top-n";
    /** Bağlantı kökü boşken kullanılan genel ayar (Genel Ayarlar → uygulama adresi). */
    public static final String KEY_APP_BASE_URL = "site.monitor.app.base-url";

    public static final List<String> KEYS = List.of(KEY_TITLE, KEY_UID, KEY_LINK_BASE_URL, KEY_SELECTOR, KEY_TIME_FROM,
            KEY_REFRESH, KEY_LANGUAGE, KEY_SECTIONS, KEY_PER_MONITOR, KEY_DEFAULT_TEAM, KEY_TOP_N);

    /** Varsayılan zaman aralığı seçenekleri (Grafana {@code time.from}; {@code to} hep {@code now}). */
    public static final List<String> TIME_FROM_OPTIONS = List.of("now-1h", "now-6h", "now-12h", "now-24h", "now-2d", "now-7d", "now-30d");
    /** Otomatik yenileme seçenekleri. */
    public static final List<String> REFRESH_OPTIONS = List.of("30s", "1m", "5m", "15m", "30m", "1h");
    public static final List<String> LANGUAGES = List.of("tr", "en");

    public static final String DEFAULT_UID = "sitemonitor-overview";
    public static final String DEFAULT_TIME_FROM = "now-24h";
    public static final String DEFAULT_REFRESH = "1m";
    public static final String DEFAULT_LANGUAGE = "tr";
    public static final int DEFAULT_TOP_N = 10;
    public static final int TOP_N_MIN = 3;
    public static final int TOP_N_MAX = 100;
    public static final int TITLE_MAX = 120;
    public static final int URL_MAX = 300;
    public static final int SELECTOR_MAX = 300;
    public static final int TEAM_MAX = 200;
    /** Grafana uid kuralı: harf, rakam, '-', '_'; en çok 40. */
    public static final Pattern UID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public static boolean isKey(String key) {
        return key != null && KEYS.contains(key);
    }

    // ── Kayıtlı ayarlardan seçenekler ────────────────────────────────────────────────────────────────────

    /** Kayıtlı (ve doğrulanmış) ayarlar → üretim girdileri; geçersiz kalmış eski değer varsayılana düşer. */
    public static GrafanaDashboardOptions current(AppSettingsService s, String appVersion) {
        String title = s.getString(KEY_TITLE, "");
        if (title == null || titleProblem(title) != null) title = "";
        String uid = s.getString(KEY_UID, DEFAULT_UID);
        if (uid == null || uid.isBlank() || !UID.matcher(uid.trim()).matches()) uid = DEFAULT_UID;
        String link = s.getString(KEY_LINK_BASE_URL, "");
        if (link == null || link.isBlank() || urlProblem(link) != null) link = s.getString(KEY_APP_BASE_URL, "");
        if (link == null || urlProblem(link) != null) link = "";
        String selector = s.getString(KEY_SELECTOR, "");
        if (selector == null || selectorProblem(selector) != null) selector = "";
        String timeFrom = pick(s.getString(KEY_TIME_FROM, DEFAULT_TIME_FROM), TIME_FROM_OPTIONS, DEFAULT_TIME_FROM);
        String refresh = pick(s.getString(KEY_REFRESH, DEFAULT_REFRESH), REFRESH_OPTIONS, DEFAULT_REFRESH);
        String language = pick(s.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE), LANGUAGES, DEFAULT_LANGUAGE);
        List<String> sections = parseSections(s.getString(KEY_SECTIONS, String.join(",", GrafanaDashboardGenerator.SECTIONS)));
        if (sections.isEmpty()) sections = GrafanaDashboardGenerator.SECTIONS;
        boolean perMonitor = s.getBoolean(KEY_PER_MONITOR, false);
        String team = s.getString(KEY_DEFAULT_TEAM, "");
        if (team == null || team.length() > TEAM_MAX) team = "";
        int topN = Math.max(TOP_N_MIN, Math.min(TOP_N_MAX, s.getInt(KEY_TOP_N, DEFAULT_TOP_N)));
        return new GrafanaDashboardOptions(title.trim(), uid.trim(), trimUrl(link), selector.trim(), timeFrom, refresh, language,
                sections, perMonitor, team.trim(), topN, appVersion);
    }

    private static String pick(String v, List<String> allowed, String fallback) {
        return v != null && allowed.contains(v.trim()) ? v.trim() : fallback;
    }

    /** Bağlantı kökünün sonundaki {@code /} atılır ({@code <kök>/?tab=…} birleşimi tek biçim olsun). */
    public static String trimUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** CSV → bilinen bölüm kimlikleri (katalog sırasıyla, tekrar yok); bilinmeyen yok sayılır. */
    public static List<String> parseSections(String csv) {
        Set<String> in = new LinkedHashSet<>();
        if (csv != null) for (String p : csv.split(",")) if (!p.isBlank()) in.add(p.trim().toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        for (String s : GrafanaDashboardGenerator.SECTIONS) if (in.contains(s)) out.add(s);
        return out;
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────────────────────────────────

    /** {@code AppSettingsService.validate} kancası (boş değer = varsayılana dön, buraya gelmez). */
    public static void validate(String key, String val) {
        String p = problem(key, val);
        if (p != null) throw new IllegalArgumentException(p);
    }

    /** Anahtar + değer → sorun cümlesi (Msg.t) ya da null. */
    public static String problem(String key, String val) {
        if (val == null || val.isEmpty()) return null;
        return switch (key) {
            case KEY_TITLE -> titleProblem(val);
            case KEY_UID -> UID.matcher(val.trim()).matches() ? null : Msg.t(
                    "Pano kimliği (UID) yalnız harf, rakam, '-' ve '_' içerebilir; en çok 40 karakter.",
                    "The dashboard UID may only contain letters, digits, '-' and '_'; 40 characters at most.");
            case KEY_LINK_BASE_URL -> urlProblem(val);
            case KEY_SELECTOR -> selectorProblem(val);
            case KEY_TIME_FROM -> TIME_FROM_OPTIONS.contains(val.trim()) ? null : Msg.t(
                    "Zaman aralığı listedeki seçeneklerden biri olmalı.", "The time range must be one of the listed options.");
            case KEY_REFRESH -> REFRESH_OPTIONS.contains(val.trim()) ? null : Msg.t(
                    "Yenileme aralığı listedeki seçeneklerden biri olmalı.", "The refresh interval must be one of the listed options.");
            case KEY_LANGUAGE -> LANGUAGES.contains(val.trim()) ? null : Msg.t(
                    "Pano dili Türkçe (tr) ya da İngilizce (en) olmalı.", "The dashboard language must be Turkish (tr) or English (en).");
            case KEY_SECTIONS -> sectionsProblem(val);
            case KEY_PER_MONITOR -> "true".equalsIgnoreCase(val.trim()) || "false".equalsIgnoreCase(val.trim()) ? null
                    : Msg.t("Açık ya da kapalı olmalı.", "Must be on or off.");
            case KEY_DEFAULT_TEAM -> val.length() > TEAM_MAX ? Msg.t("Takım adı en çok " + TEAM_MAX + " karakter olabilir.",
                    "A team name can be at most " + TEAM_MAX + " characters.") : null;
            case KEY_TOP_N -> topNProblem(val);
            default -> null;
        };
    }

    public static String titleProblem(String v) {
        if (v.length() > TITLE_MAX) return Msg.t("Başlık en çok " + TITLE_MAX + " karakter olabilir.",
                "The title can be at most " + TITLE_MAX + " characters.");
        for (int i = 0; i < v.length(); i++) {
            if (Character.isISOControl(v.charAt(i))) return Msg.t("Başlıkta satır sonu ya da denetim karakteri olamaz.",
                    "The title cannot contain line breaks or control characters.");
        }
        return null;
    }

    /** http(s) kök adresi: boşluk / tırnak / açı ayracı yok, ≤ 300; sorgu ve parça YOK ({@code ?tab=…} üretici ekler). */
    public static String urlProblem(String v) {
        String u = v.trim();
        String bad = Msg.t("Geçerli bir http:// ya da https:// adresi girin (ör. https://sitemonitor.kurum.local); "
                + "sorgu (?) ve parça (#) eklemeyin.",
                "Enter a valid http:// or https:// address (e.g. https://sitemonitor.example.org); don't add a query (?) or fragment (#).");
        if (u.length() > URL_MAX) return Msg.t("Adres en çok " + URL_MAX + " karakter olabilir.", "The address can be at most " + URL_MAX + " characters.");
        String lower = u.toLowerCase(Locale.ROOT);
        int start;
        if (lower.startsWith("https://")) start = 8;
        else if (lower.startsWith("http://")) start = 7;
        else return bad;
        if (u.length() <= start) return bad;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c <= ' ' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`' || c == '\\' || c == '?' || c == '#'
                    || c == '{' || c == '}' || c == '|' || c == '^' || Character.isISOControl(c)) return bad;
        }
        char first = u.charAt(start);
        if (first == '/' || first == '.' || first == ':' || first == '@') return bad;
        return null;
    }

    public static String sectionsProblem(String csv) {
        List<String> known = parseSections(csv);
        for (String p : csv.split(",")) {
            if (p.isBlank()) continue;
            if (!GrafanaDashboardGenerator.SECTIONS.contains(p.trim().toLowerCase(Locale.ROOT))) {
                return Msg.t("Bilinmeyen pano bölümü: " + p.trim(), "Unknown dashboard section: " + p.trim());
            }
        }
        return known.isEmpty() ? Msg.t("En az bir pano bölümü seçin.", "Select at least one dashboard section.") : null;
    }

    public static String topNProblem(String v) {
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= TOP_N_MIN && n <= TOP_N_MAX) return null;
        } catch (Exception ignored) { /* aşağıda */ }
        return Msg.t("Satır sayısı " + TOP_N_MIN + " ile " + TOP_N_MAX + " arasında olmalı.",
                "The row count must be between " + TOP_N_MIN + " and " + TOP_N_MAX + ".");
    }

    /**
     * Prometheus etiket eşleyicisi: virgülle ayrılmış {@code ad işleç "değer"} öğeleri; işleç {@code = != =~ !~}. Süslü ayraç
     * YOK (üretici kendisi ekler). Doğrusal tarama — düzenli ifade geri izlemesi yok; önce uzunluk sınırı.
     */
    public static String selectorProblem(String v) {
        String bad = Msg.t("Etiket eşleyicisi ad=\"değer\" biçiminde olmalı (birden çoksa virgülle ayırın; işleçler = != =~ !~). "
                + "Örnek: namespace=\"site-monitor-prod\", job=\"site-monitor\"",
                "The label matcher must look like name=\"value\" (separate several with commas; operators = != =~ !~). "
                + "Example: namespace=\"site-monitor-prod\", job=\"site-monitor\"");
        if (v.length() > SELECTOR_MAX) return Msg.t("Etiket eşleyicisi en çok " + SELECTOR_MAX + " karakter olabilir.",
                "The label matcher can be at most " + SELECTOR_MAX + " characters.");
        String s = v.trim();
        if (s.isEmpty()) return null;
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && s.charAt(i) == ' ') i++;
            int nameStart = i;
            if (i >= n || !(Character.isLetter(s.charAt(i)) && s.charAt(i) < 128 || s.charAt(i) == '_')) return bad;
            while (i < n && (s.charAt(i) < 128 && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'))) i++;
            if (i == nameStart) return bad;
            while (i < n && s.charAt(i) == ' ') i++;
            if (i >= n) return bad;
            if (s.startsWith("=~", i) || s.startsWith("!=", i) || s.startsWith("!~", i)) i += 2;
            else if (s.charAt(i) == '=') i += 1;
            else return bad;
            while (i < n && s.charAt(i) == ' ') i++;
            if (i >= n || s.charAt(i) != '"') return bad;
            i++;
            boolean closed = false;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\') { i += 2; continue; }
                if (c == '"') { closed = true; i++; break; }
                if (Character.isISOControl(c)) return bad;
                i++;
            }
            if (!closed) return bad;
            while (i < n && s.charAt(i) == ' ') i++;
            if (i < n) {
                if (s.charAt(i) != ',') return bad;
                i++;
                if (i >= n || s.substring(i).isBlank()) return bad;   // sonda virgül
            }
        }
        return null;
    }
}
