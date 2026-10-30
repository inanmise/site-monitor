package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merkezi izleme alarm akışının ayarları (2026-10-30, kullanıcı isteği). Hepsi canlı ({@code AppSettingsService}, 10 sn
 * yenileme), grup {@code alarm-feed}, GLOBAL_ONLY. Varsayılanlar {@code application.properties}'te; okunamayan / geçersiz
 * değer varsayılana düşer (akış asla bozuk ayarla çalışmaz).
 *
 * <p>Kullanıcı kararları: kaynak = alarmlar; çözülen alarm SEVERITY'si aynı kalır + STATUS / RESOLVEDTIME kolonları (ayarla
 * "Clear" kipine geçilebilir); RCI = hedef + alarm türü (ayarla yalnız hedef); her çağrı ayrı çağrı günlüğünde.
 */
public final class AlarmFeedSettings {

    private AlarmFeedSettings() {}

    public static final String PREFIX = "site.monitor.alarm-feed.";
    public static final String KEY_ENABLED = PREFIX + "enabled";
    public static final String KEY_MIN_LEVEL = PREFIX + "min-level";
    public static final String KEY_TEAM_IDS = PREFIX + "team-ids";
    public static final String KEY_TYPES = PREFIX + "types";
    public static final String KEY_RESOLVED_MINUTES = PREFIX + "resolved-minutes";
    public static final String KEY_RESOLVED_MODE = PREFIX + "resolved-mode";
    public static final String KEY_CLEAR_TEXT = PREFIX + "clear-text";
    public static final String KEY_SEV_CRITICAL = PREFIX + "severity-critical";
    public static final String KEY_SEV_HIGH = PREFIX + "severity-high";
    public static final String KEY_SEV_WARNING = PREFIX + "severity-warning";
    public static final String KEY_RCI_MODE = PREFIX + "rci-mode";
    public static final String KEY_SOURCE = PREFIX + "source";
    public static final String KEY_RCITYPE = PREFIX + "rcitype";
    public static final String KEY_MAIL_SEPARATOR = PREFIX + "mail-separator";
    public static final String KEY_ALLOWED_IPS = PREFIX + "allowed-ips";
    public static final String KEY_RATE = PREFIX + "rate-per-minute";
    public static final String KEY_MAX_ROWS = PREFIX + "max-rows";
    public static final String KEY_STORE_BODY = PREFIX + "store-body";
    /** Çağrı günlüğü saklama süresi — RetentionCatalog'dan üretilir (Veri saklama sayfası). */
    public static final String KEY_LOG_RETENTION = PREFIX + "log-retention-days";
    /** Yanıt gövdesi kopyalarının saklama süresi (son kullanımdan) — RetentionCatalog "alarm-feed-responses". */
    public static final String KEY_BODY_RETENTION = PREFIX + "body-retention-days";

    public static final List<String> LEVELS = List.of("WARNING", "HIGH", "CRITICAL");
    public static final List<String> RESOLVED_MODES = List.of("status", "clear");
    public static final List<String> RCI_MODES = List.of("target_type", "target");
    public static final List<String> SEPARATORS = List.of("semicolon", "comma");

    public static final int MINUTES_MIN = 1, MINUTES_MAX = 1440;
    public static final int RATE_MIN = 1, RATE_MAX = 600;
    public static final int ROWS_MIN = 100, ROWS_MAX = 10_000;
    public static final int TEXT_MAX = 60;
    public static final int IPS_MAX = 100;
    /** Açıkça boş izin listesi (= herkes) — kayıtlı değer; okumada boş listeye döner. */
    public static final String EMPTY_LIST = "none";

    /** Arayüz alanı → ayar anahtarı (sıra = ekrandaki sıra). */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("enabled", KEY_ENABLED);
        FIELDS.put("min_level", KEY_MIN_LEVEL);
        FIELDS.put("team_ids", KEY_TEAM_IDS);
        FIELDS.put("types", KEY_TYPES);
        FIELDS.put("resolved_minutes", KEY_RESOLVED_MINUTES);
        FIELDS.put("resolved_mode", KEY_RESOLVED_MODE);
        FIELDS.put("clear_text", KEY_CLEAR_TEXT);
        FIELDS.put("severity_critical", KEY_SEV_CRITICAL);
        FIELDS.put("severity_high", KEY_SEV_HIGH);
        FIELDS.put("severity_warning", KEY_SEV_WARNING);
        FIELDS.put("rci_mode", KEY_RCI_MODE);
        FIELDS.put("source", KEY_SOURCE);
        FIELDS.put("rcitype", KEY_RCITYPE);
        FIELDS.put("mail_separator", KEY_MAIL_SEPARATOR);
        FIELDS.put("allowed_ips", KEY_ALLOWED_IPS);
        FIELDS.put("rate_per_minute", KEY_RATE);
        FIELDS.put("max_rows", KEY_MAX_ROWS);
        FIELDS.put("store_body", KEY_STORE_BODY);
    }

    /** Akışın o anki ayarı (değişmez anlık görüntü). */
    public record Config(boolean enabled, String minLevel, Set<Long> teamIds, Set<String> types, int resolvedMinutes,
                         String resolvedMode, String clearText, String sevCritical, String sevHigh, String sevWarning,
                         String rciMode, String source, String rcitype, String mailSeparator, List<String> allowedIps,
                         int ratePerMinute, int maxRows, boolean storeBody) {

        public boolean clearMode() { return "clear".equals(resolvedMode); }

        public boolean targetOnlyRci() { return "target".equals(rciMode); }

        public String separator() { return "comma".equals(mailSeparator) ? ", " : "; "; }

        /** SiteMonitor seviyesi → merkezi sistemin seviye metni. */
        public String severityOf(String level) {
            return switch (canonicalLevel(level)) {
                case "CRITICAL" -> sevCritical;
                case "HIGH" -> sevHigh;
                default -> sevWarning;
            };
        }

        /** Seviye sırası: WARNING 0, HIGH 1, CRITICAL 2. */
        public boolean levelIncluded(String level) {
            return rank(canonicalLevel(level)) >= rank(minLevel);
        }

        /** Önbellek anahtarı için kararlı özet. */
        public String fingerprint() {
            return String.join("|", String.valueOf(enabled), minLevel, String.valueOf(teamIds), String.valueOf(types),
                    String.valueOf(resolvedMinutes), resolvedMode, clearText, sevCritical, sevHigh, sevWarning, rciMode,
                    source, rcitype, mailSeparator, String.valueOf(maxRows));
        }
    }

    /** CRITICAL | HIGH | WARNING (MEDIUM / LOW / bilinmeyen → WARNING). */
    public static String canonicalLevel(String level) {
        String l = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return switch (l) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            default -> "WARNING";
        };
    }

    static int rank(String canonical) {
        return switch (canonical == null ? "" : canonical) {
            case "CRITICAL" -> 2;
            case "HIGH" -> 1;
            default -> 0;
        };
    }

    /** Canlı ayarları okur; geçersiz değer varsayılana düşer. {@code settings == null} → varsayılanlar (testler). */
    public static Config read(AppSettingsService settings) {
        if (settings == null) return defaults();
        Config d = defaults();
        return new Config(
                settings.getBoolean(KEY_ENABLED, d.enabled()),
                oneOf(settings.getString(KEY_MIN_LEVEL, d.minLevel()), LEVELS, d.minLevel()),
                ids(settings.getString(KEY_TEAM_IDS, "")),
                types(settings.getString(KEY_TYPES, "")),
                clamp(settings.getInt(KEY_RESOLVED_MINUTES, d.resolvedMinutes()), MINUTES_MIN, MINUTES_MAX, d.resolvedMinutes()),
                oneOf(settings.getString(KEY_RESOLVED_MODE, d.resolvedMode()), RESOLVED_MODES, d.resolvedMode()),
                text(settings.getString(KEY_CLEAR_TEXT, d.clearText()), d.clearText()),
                text(settings.getString(KEY_SEV_CRITICAL, d.sevCritical()), d.sevCritical()),
                text(settings.getString(KEY_SEV_HIGH, d.sevHigh()), d.sevHigh()),
                text(settings.getString(KEY_SEV_WARNING, d.sevWarning()), d.sevWarning()),
                oneOf(settings.getString(KEY_RCI_MODE, d.rciMode()), RCI_MODES, d.rciMode()),
                text(settings.getString(KEY_SOURCE, d.source()), d.source()),
                optionalText(settings.getString(KEY_RCITYPE, "")),
                oneOf(settings.getString(KEY_MAIL_SEPARATOR, d.mailSeparator()), SEPARATORS, d.mailSeparator()),
                ipsLenient(settings.getString(KEY_ALLOWED_IPS, "")),
                clamp(settings.getInt(KEY_RATE, d.ratePerMinute()), RATE_MIN, RATE_MAX, d.ratePerMinute()),
                clamp(settings.getInt(KEY_MAX_ROWS, d.maxRows()), ROWS_MIN, ROWS_MAX, d.maxRows()),
                settings.getBoolean(KEY_STORE_BODY, d.storeBody()));
    }

    public static Config defaults() {
        return new Config(false, "WARNING", Set.of(), Set.of(), 15, "status", "Clear", "Critical", "Major", "Warning",
                "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, 2000, true);
    }

    /** Ayar ekranına giden değerler (alan adları {@link #FIELDS} ile aynı). */
    public static Map<String, Object> view(Config c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.enabled());
        m.put("min_level", c.minLevel());
        m.put("team_ids", new ArrayList<>(c.teamIds()));
        m.put("types", new ArrayList<>(c.types()));
        m.put("resolved_minutes", c.resolvedMinutes());
        m.put("resolved_mode", c.resolvedMode());
        m.put("clear_text", c.clearText());
        m.put("severity_critical", c.sevCritical());
        m.put("severity_high", c.sevHigh());
        m.put("severity_warning", c.sevWarning());
        m.put("rci_mode", c.rciMode());
        m.put("source", c.source());
        m.put("rcitype", c.rcitype());
        m.put("mail_separator", c.mailSeparator());
        m.put("allowed_ips", new ArrayList<>(c.allowedIps()));
        m.put("rate_per_minute", c.ratePerMinute());
        m.put("max_rows", c.maxRows());
        m.put("store_body", c.storeBody());
        return m;
    }

    // ── Kayıt doğrulaması (katı; hata → 400 VALIDATION_FAILED + alan) ─────────────────────────────

    /**
     * Ekrandan gelen bir alanı ayar metnine çevirir ve doğrular. Geçersizse {@link FieldValidationException}.
     *
     * @param field arayüz alanı ({@link #FIELDS} anahtarı)
     */
    public static String normalize(String field, Object raw) {
        switch (field) {
            case "enabled", "store_body" -> {
                if (raw instanceof Boolean b) return b.toString();
                String s = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true") || s.equals("false")) return s;
                throw invalid(field, "Açık / kapalı değeri bekleniyor.", "An on / off value is expected.");
            }
            case "min_level" -> {
                String v = raw == null ? "" : raw.toString().trim().toUpperCase(Locale.ROOT);
                if (LEVELS.contains(v)) return v;
                throw invalid(field, "En düşük seviye WARNING, HIGH ya da CRITICAL olmalı.",
                        "The minimum level must be WARNING, HIGH or CRITICAL.");
            }
            case "resolved_mode" -> { return enumValue(field, raw, RESOLVED_MODES); }
            case "rci_mode" -> { return enumValue(field, raw, RCI_MODES); }
            case "mail_separator" -> { return enumValue(field, raw, SEPARATORS); }
            case "resolved_minutes" -> { return Integer.toString(intValue(field, raw, MINUTES_MIN, MINUTES_MAX)); }
            case "rate_per_minute" -> { return Integer.toString(intValue(field, raw, RATE_MIN, RATE_MAX)); }
            case "max_rows" -> { return Integer.toString(intValue(field, raw, ROWS_MIN, ROWS_MAX)); }
            case "clear_text", "severity_critical", "severity_high", "severity_warning", "source" -> {
                String v = raw == null ? "" : raw.toString().strip();
                if (v.isEmpty()) {
                    throw invalid(field, "Bu alan boş bırakılamaz.", "This field can't be empty.");
                }
                return checkedText(field, v);
            }
            case "rcitype" -> {
                String v = raw == null ? "" : raw.toString().strip();
                return v.isEmpty() ? "" : checkedText(field, v);
            }
            case "team_ids" -> {
                List<String> parts = listOf(raw);
                Set<Long> out = new LinkedHashSet<>();
                for (String p : parts) {
                    try {
                        long id = Long.parseLong(p);
                        if (id <= 0) throw new NumberFormatException();
                        out.add(id);
                    } catch (NumberFormatException e) {
                        throw invalid(field, "Takım listesi yalnız takım kimlikleri içerebilir.",
                                "The team list may contain team ids only.");
                    }
                }
                return joinLongs(out);
            }
            case "types" -> {
                Set<String> out = new LinkedHashSet<>();
                for (String p : listOf(raw)) {
                    String t = p.toLowerCase(Locale.ROOT);
                    if (!MonitorTypeCatalog.ORDER.contains(t)) {
                        throw invalid(field, "Bilinmeyen izleme türü: " + clip(p, 40),
                                "Unknown monitor type: " + clip(p, 40));
                    }
                    out.add(t);
                }
                // Katalog sırasıyla yaz (aynı küme = aynı metin, değişmeyen kayıt yazılmaz).
                List<String> ordered = new ArrayList<>();
                for (String t : MonitorTypeCatalog.ORDER) if (out.contains(t)) ordered.add(t);
                return String.join(",", ordered);
            }
            case "allowed_ips" -> {
                List<String> parts = listOf(raw);
                if (parts.size() > IPS_MAX) {
                    throw invalid(field, "En çok " + IPS_MAX + " adres / aralık girilebilir.",
                            "At most " + IPS_MAX + " addresses / ranges can be entered.");
                }
                Set<String> out = new LinkedHashSet<>();
                for (String p : parts) {
                    String norm = IpAllowList.normalizeEntry(p);
                    if (norm == null) {
                        throw invalid(field, "Geçersiz IP adresi ya da aralık: " + clip(p, 60)
                                        + " — örnek: 10.20.30.40, 10.0.0.0/8, 2001:db8::/32.",
                                "Invalid IP address or range: " + clip(p, 60)
                                        + " — e.g. 10.20.30.40, 10.0.0.0/8, 2001:db8::/32.");
                    }
                    out.add(norm);
                }
                // Boş liste AÇIKÇA "herkes" olarak yazılır: boş değer geçersiz kılmayı siler ve ortam değişkeni
                // (ALARM_FEED_ALLOWED_IPS) geri gelirdi.
                return out.isEmpty() ? EMPTY_LIST : String.join(",", out);
            }
            default -> throw invalid(field, "Bilinmeyen alan.", "Unknown field.");
        }
    }

    /** Saklanan metin değerini ekrandaki karşılaştırma biçimine çevirir (değişmeyen alan yazılmasın diye). */
    public static String stored(Config c, String field) {
        if ("allowed_ips".equals(field) && c.allowedIps().isEmpty()) return EMPTY_LIST;
        Object v = view(c).get(field);
        if (v instanceof Collection<?> col) {
            List<String> s = new ArrayList<>();
            for (Object o : col) s.add(String.valueOf(o));
            return String.join(",", s);
        }
        return v == null ? "" : v.toString();
    }

    // ── yardımcılar ────────────────────────────────────────────────────────────────────────────

    private static String enumValue(String field, Object raw, List<String> allowed) {
        String v = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(v)) return v;
        throw invalid(field, "Geçersiz seçim: " + clip(v, 40) + " (izinli: " + String.join(", ", allowed) + ").",
                "Invalid choice: " + clip(v, 40) + " (allowed: " + String.join(", ", allowed) + ").");
    }

    private static int intValue(String field, Object raw, int min, int max) {
        long v;
        try {
            if (raw instanceof Number n) {
                double d = n.doubleValue();
                if (d != Math.rint(d)) throw new NumberFormatException();
                v = (long) d;
            } else {
                v = Long.parseLong(raw == null ? "" : raw.toString().trim());
            }
        } catch (NumberFormatException e) {
            throw invalid(field, "Tam sayı bekleniyor (" + min + "–" + max + ").",
                    "A whole number is expected (" + min + "–" + max + ").");
        }
        if (v < min || v > max) {
            throw invalid(field, "Değer " + min + " ile " + max + " arasında olmalı.",
                    "The value must be between " + min + " and " + max + ".");
        }
        return (int) v;
    }

    private static String checkedText(String field, String v) {
        if (v.length() > TEXT_MAX) {
            throw invalid(field, "En çok " + TEXT_MAX + " karakter girilebilir.", "At most " + TEXT_MAX + " characters.");
        }
        for (int i = 0; i < v.length(); i++) {
            if (Character.isISOControl(v.charAt(i))) {
                throw invalid(field, "Kontrol karakteri (satır sonu, sekme) kullanılamaz.",
                        "Control characters (line breaks, tabs) can't be used.");
            }
        }
        return v;
    }

    private static List<String> listOf(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                if (o == null) continue;
                String s = o.toString().trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        String s = raw.toString();
        if (s.length() > 20_000) {
            throw new FieldValidationException("value", Msg.t("Değer çok uzun.", "The value is too long."));
        }
        for (String p : s.split("[,;\\s]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static FieldValidationException invalid(String field, String tr, String en) {
        return new FieldValidationException(field, Msg.t(tr, en));
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String joinLongs(Collection<Long> ids) {
        List<String> s = new ArrayList<>();
        for (Long id : ids) s.add(Long.toString(id));
        return String.join(",", s);
    }

    private static String oneOf(String v, List<String> allowed, String def) {
        if (v == null) return def;
        String t = v.trim();
        for (String a : allowed) if (a.equalsIgnoreCase(t)) return a;
        return def;
    }

    private static int clamp(int v, int min, int max, int def) {
        return v < min || v > max ? def : v;
    }

    private static String text(String v, String def) {
        if (v == null) return def;
        String t = v.strip();
        if (t.isEmpty() || t.length() > TEXT_MAX || t.chars().anyMatch(Character::isISOControl)) return def;
        return t;
    }

    private static String optionalText(String v) {
        if (v == null) return "";
        String t = v.strip();
        if (t.length() > TEXT_MAX || t.chars().anyMatch(Character::isISOControl)) return "";
        return t;
    }

    /** Kayıtlı takım süzgeci; DOLU ama okunamayan değer = hiçbir takım ({@code -1}) — kapsam "hepsi"ne genişlemez. */
    static Set<Long> ids(String csv) {
        Set<Long> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split("[,;\\s]+")) {
            try {
                long id = Long.parseLong(p.trim());
                if (id > 0) out.add(id);
            } catch (NumberFormatException ignored) {
                // geçersiz parça yok sayılır (kayıt katı doğrular)
            }
        }
        if (out.isEmpty()) out.add(-1L);
        return out;
    }

    /** Kayıtlı tür süzgeci; DOLU ama okunamayan değer = hiçbir tür ({@code !none}) — kapsam "hepsi"ne genişlemez. */
    static Set<String> types(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split("[,;\\s]+")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (MonitorTypeCatalog.ORDER.contains(t)) out.add(t);
        }
        if (out.isEmpty()) out.add(IpAllowList.NONE);
        return out;
    }

    /**
     * Kayıtlı izin listesini okur. Geçersiz parçalar atılır; ama kayıt DOLU iken hiçbir parça okunamıyorsa liste
     * {@link IpAllowList#NONE} olur (kimse geçemez) — okunamayan bir izin listesi "herkese açık"a dönüşmesin.
     */
    static List<String> ipsLenient(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank() || EMPTY_LIST.equalsIgnoreCase(csv.trim())) return out;
        for (String p : csv.split("[,;\\s]+")) {
            if (p.isBlank()) continue;
            String n = IpAllowList.normalizeEntry(p);
            if (n != null && !out.contains(n)) out.add(n);
        }
        if (out.isEmpty()) out.add(IpAllowList.NONE);
        return out;
    }
}
