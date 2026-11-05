package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.util.Msg;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Değerlendirme kuralları modeli (sözleşme §3). JSON biçimi ({@code dbquery_monitors.rules_json}, API {@code rules}):
 * <pre>
 * { "id": "r1", "label": "…", "kind": "ROW_COUNT|COLUMN_VALUE|TEXT_MATCH|CHANGE|FRESHNESS|ANOMALY|FROZEN",
 *   "column": "…", "aggregate": "FIRST|MAX|MIN|SUM|AVG|COUNT_NONNULL",
 *   "operator": "GT|GTE|LT|LTE|EQ|NEQ|BETWEEN|OUTSIDE|CONTAINS|NOT_CONTAINS|EQUALS|NOT_EQUALS|EMPTY|NOT_EMPTY",
 *   "scope": "ANY_ROW|ALL_ROWS|FIRST_ROW",
 *   "warning": 10 | [1, 5] | "text", "critical": 100 | [0, 2] | "text",
 *   "change": { "of": "ROW_COUNT|COLUMN", "mode": "PERCENT|ABSOLUTE", "direction": "INCREASE|DECREASE|ANY",
 *               "baseline": "previous|same_time_yesterday|same_time_last_week" } }
 * </pre>
 * Akıllı kurallar (dbq2 D): {@code ANOMALY} — kolonun hesaplanmış değeri geçmiş zamanlanmış ölçülerin olağan aralığının
 * (ortalama ± k·σ) dışında mı ({@code direction}, {@code k_warning}, {@code k_critical}, {@code window}, {@code min_samples},
 * {@code season}); {@code FROZEN} — değer ardışık N zamanlanmış çalıştırmadır aynı mı ({@code runs_warning},
 * {@code runs_critical}). İkisinde de {@code operator} / {@code warning} / {@code critical} yoktur (eşikleri kendi
 * parametreleridir).
 * Tazelik ({@code FRESHNESS}, r177): kolon bir zaman damgasıdır; hesap {@code MAX} (varsayılan; FIRST / MIN de olur), ölçüm =
 * veritabanı saatine göre YAŞ (saniye), operatör yalnız {@code GT} / {@code GTE} ("yaş ≥ X"). Değişim tabanı ({@code baseline},
 * varsayılan {@code previous}) mevsimsel olabilir: dünün / geçen haftanın aynı saatindeki zamanlanmış çalıştırma.
 * Operatör İHLAL koşuludur: {@code GT 10} → değer 10'dan büyükse ihlal; {@code BETWEEN [1, 5]} → aralığın İÇİNDEyse,
 * {@code OUTSIDE [1, 5]} → DIŞINDAysa ihlal. İki seviye (uyarı / kritik) sayısal kurallarda bağımsız eşiklerdir (kritik önce);
 * metin kuralı tek seviyelidir (kritik alanı doluysa kritik, değilse uyarı).
 *
 * <p>{@link #fromApi} KATIDIR (alan hatası {@code rules[N].<alt>}, istek dilinde ileti); {@link #fromJson} GEVŞEKTİR (bozuk
 * kural atlanır, asla fırlatmaz) — saklanan kayıt hiçbir zaman izlemeyi kırmaz.
 */
public final class DbQueryRules {

    public static final int MAX_RULES = 10;
    public static final int MAX_LABEL = 120, MAX_COLUMN = 128, MAX_TEXT = 500;

    public static final String KIND_ROW_COUNT = "ROW_COUNT", KIND_COLUMN_VALUE = "COLUMN_VALUE",
            KIND_TEXT_MATCH = "TEXT_MATCH", KIND_CHANGE = "CHANGE",
            /** Tazelik: zaman damgası kolonunun veritabanı saatine göre yaşı (saniye) — r177. */
            KIND_FRESHNESS = "FRESHNESS",
            /** Olağan dışı değer: kolon değeri geçmişin olağan aralığının (ortalama ± k·σ) dışında — dbq2 D. */
            KIND_ANOMALY = "ANOMALY",
            /** Donmuş değer: kolon değeri ardışık N zamanlanmış çalıştırmadır aynı — dbq2 D. */
            KIND_FROZEN = "FROZEN";

    public static final List<String> KINDS =
            List.of(KIND_ROW_COUNT, KIND_COLUMN_VALUE, KIND_TEXT_MATCH, KIND_CHANGE, KIND_FRESHNESS, KIND_ANOMALY, KIND_FROZEN);

    /** Anomali yönü (API değeri küçük harf, kalıcı): iki yön / yalnız yüksek / yalnız düşük değer alarm. */
    public static final String DIRECTION_BOTH = "both", DIRECTION_UP = "up", DIRECTION_DOWN = "down";
    public static final List<String> ANOMALY_DIRECTIONS = List.of(DIRECTION_BOTH, DIRECTION_UP, DIRECTION_DOWN);
    /** Anomali tabanının mevsimi (API değeri küçük harf, kalıcı): son çalıştırmalar / önceki günlerin / haftaların aynı saati. */
    public static final String SEASON_NONE = "none", SEASON_DAILY = "daily", SEASON_WEEKLY = "weekly";
    public static final List<String> SEASONS = List.of(SEASON_NONE, SEASON_DAILY, SEASON_WEEKLY);
    /** Anomali parametrelerinin sınırları ve varsayılanları (sözleşme D). */
    public static final double K_MIN = 1, K_MAX = 10, K_WARNING_DEFAULT = 2.5, K_CRITICAL_DEFAULT = 3.5;
    public static final int WINDOW_MIN = 10, WINDOW_MAX = 200, WINDOW_DEFAULT = 30, MIN_SAMPLES_MIN = 5,
            MIN_SAMPLES_DEFAULT = 10;
    /** Donma parametrelerinin sınırları ve varsayılanı. */
    public static final int RUNS_MIN = 2, RUNS_MAX = 100, RUNS_WARNING_DEFAULT = 6;
    public static final List<String> AGGREGATES = List.of("FIRST", "MAX", "MIN", "SUM", "AVG", "COUNT_NONNULL");
    public static final List<String> NUMERIC_OPERATORS = List.of("GT", "GTE", "LT", "LTE", "EQ", "NEQ", "BETWEEN", "OUTSIDE");
    public static final List<String> TEXT_OPERATORS =
            List.of("CONTAINS", "NOT_CONTAINS", "EQUALS", "NOT_EQUALS", "EMPTY", "NOT_EMPTY");
    public static final List<String> SCOPES = List.of("ANY_ROW", "ALL_ROWS", "FIRST_ROW");
    public static final List<String> CHANGE_OF = List.of("ROW_COUNT", "COLUMN");
    public static final List<String> CHANGE_MODES = List.of("PERCENT", "ABSOLUTE");
    public static final List<String> CHANGE_DIRECTIONS = List.of("INCREASE", "DECREASE", "ANY");
    /** Değişim tabanı (API değeri küçük harf, kalıcı): önceki çalıştırma / dünün aynı saati / geçen haftanın aynı saati. */
    public static final String BASELINE_PREVIOUS = "previous", BASELINE_YESTERDAY = "same_time_yesterday",
            BASELINE_LAST_WEEK = "same_time_last_week";
    public static final List<String> CHANGE_BASELINES = List.of(BASELINE_PREVIOUS, BASELINE_YESTERDAY, BASELINE_LAST_WEEK);
    /** Tazelik kuralında anlamlı hesaplar (toplam / ortalama zaman damgasında anlamsız). */
    public static final List<String> FRESHNESS_AGGREGATES = List.of("MAX", "MIN", "FIRST");
    /** Tazelik kuralının operatörleri: "yaş &gt; X" / "yaş ≥ X". */
    public static final List<String> FRESHNESS_OPERATORS = List.of("GT", "GTE");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Eşik: sayısal tek değer, aralık [low, high] ya da metin (TEXT_MATCH). Hepsi null = tanımsız. */
    public record Threshold(Double value, Double low, Double high, String text) {
        public boolean isRange() { return low != null && high != null; }
    }

    /**
     * @param baseline {@link #CHANGE_BASELINES} — {@code previous} (varsayılan, bugüne dek tek davranış) ya da mevsimsel taban
     */
    public record Change(String of, String mode, String direction, String baseline) {
        /** Eski üç alanlı biçim (taban = önceki çalıştırma). */
        public Change(String of, String mode, String direction) {
            this(of, mode, direction, BASELINE_PREVIOUS);
        }

        /** Taban null ise önceki çalıştırma. */
        public String baselineOrPrevious() {
            return baseline == null ? BASELINE_PREVIOUS : baseline;
        }
    }

    /**
     * Anomali kuralının parametreleri (dbq2 D). {@code kWarning} ≤ {@code kCritical}; {@code minSamples} ≤ {@code window}.
     *
     * @param direction {@link #ANOMALY_DIRECTIONS}
     * @param season    {@link #SEASONS}
     */
    public record Anomaly(String direction, double kWarning, double kCritical, int window, int minSamples, String season) {
        /** Varsayılanlar: iki yön, 2,5σ / 3,5σ, 30 örnek, en az 10, mevsimsiz. */
        public static Anomaly defaults() {
            return new Anomaly(DIRECTION_BOTH, K_WARNING_DEFAULT, K_CRITICAL_DEFAULT, WINDOW_DEFAULT, MIN_SAMPLES_DEFAULT,
                    SEASON_NONE);
        }
    }

    /**
     * Donma kuralının parametreleri (dbq2 D): değer bu çalıştırma DAHİL en az {@code runsWarning} ardışık zamanlanmış
     * çalıştırmada aynıysa uyarı, {@code runsCritical} (isteğe bağlı, &gt; runsWarning) ise kritik.
     */
    public record Frozen(int runsWarning, Integer runsCritical) {
        /** Kararı vermek için gereken en uzun dizi (kritik varsa o). */
        public int runsMax() { return runsCritical != null ? runsCritical : runsWarning; }
    }

    /**
     * @param timezone yalnız tazelik kuralında: saat dilimi OLMAYAN zaman damgalarının yorumlandığı IANA dilimi (ör.
     *                 {@code Europe/Istanbul}); null = ayar {@code site.monitor.dbquery.local-timezone} (r177 ikinci tur, O-1)
     */
    public record Rule(String id, String label, String kind, String column, String aggregate, String operator,
                       String scope, Threshold warning, Threshold critical, Change change, String timezone,
                       Anomaly anomaly, Frozen frozen) {
        /** Eski on alanlı biçim (dilim yok). */
        public Rule(String id, String label, String kind, String column, String aggregate, String operator,
                    String scope, Threshold warning, Threshold critical, Change change) {
            this(id, label, kind, column, aggregate, operator, scope, warning, critical, change, null, null, null);
        }

        /** On bir alanlı biçim (akıllı kural parametresi yok). */
        public Rule(String id, String label, String kind, String column, String aggregate, String operator,
                    String scope, Threshold warning, Threshold critical, Change change, String timezone) {
            this(id, label, kind, column, aggregate, operator, scope, warning, critical, change, timezone, null, null);
        }
    }

    /**
     * Geçmiş ölçülerle değerlendirilen "akıllı" kural mı (ANOMALY / FROZEN) — çalıştırma başına geçmiş okunur
     * ({@code DbQueryHistory}).
     */
    public static boolean isSmart(String kind) {
        return KIND_ANOMALY.equals(kind) || KIND_FROZEN.equals(kind);
    }

    /** Kuralın anomali parametreleri (yoksa varsayılanlar). */
    public static Anomaly anomalyOf(Rule r) {
        return r == null || r.anomaly() == null ? Anomaly.defaults() : r.anomaly();
    }

    /** Kuralın donma parametreleri (yoksa varsayılan: 6 çalıştırma uyarı, kritik yok). */
    public static Frozen frozenOf(Rule r) {
        return r == null || r.frozen() == null ? new Frozen(RUNS_WARNING_DEFAULT, null) : r.frozen();
    }

    /** Dilim adının üst sınırı (IANA adları ≤ 32). */
    static final int MAX_TIMEZONE = 64;

    /**
     * Geçerli IANA dilim adı mı (ör. {@code Europe/Istanbul}, {@code UTC}); sabit fark ({@code +03:00}) ve tanınmayan ad
     * kabul edilmez. Doğrusal, düzenli ifade yok.
     */
    public static boolean validTimezone(String s) {
        if (s == null || s.isBlank() || s.length() > MAX_TIMEZONE) return false;
        return java.time.ZoneId.getAvailableZoneIds().contains(s.trim());
    }

    /** Alan hatası: {@code field} = "rules[2].warning" gibi; {@code message} istek dilinde. */
    public record FieldError(String field, String code, String message) {}

    // ── Akıllı kural tabanı ve saklama / zamanlama (dbq2 inceleme O1) ────────

    /** Kayıt uyarısı kodu (engellemez, kalıcı — ön yüz {@code dbq.warn.<KOD>}): mevsimsel eşleşme nadir. */
    public static final String WARN_ANOMALY_SEASON_RARE = "ANOMALY_SEASON_RARE";

    private static final int DAY_SECONDS = 86_400, WEEK_SECONDS = 7 * DAY_SECONDS;

    /**
     * Saklama süresi içinde dolabilecek en çok örnek (mevsimsel: hedef gün / hafta; mevsimsiz: çalıştırma) — taban bu sayıda
     * geçmiş satırla kurulabilir. Mevsimsel hedefler canlıda en çok {@value DbQueryHistory#MAX_SEASONAL_DAYS} gün geriye
     * gider; mevsimsiz yakın geçmiş pencereyle sınırlıdır (pencere ≤ 200). Aralık bilinmiyorsa mevsimsizde {@code -1}
     * (denetlenmez).
     */
    static long fillableSamples(Anomaly a, Integer intervalSec, int retentionDays) {
        int days = Math.min(Math.max(1, retentionDays), DbQueryHistory.MAX_SEASONAL_DAYS);
        if (SEASON_DAILY.equals(a.season())) return days;
        if (SEASON_WEEKLY.equals(a.season())) return days / 7;
        if (intervalSec == null || intervalSec <= 0) return -1;
        return (long) Math.max(1, retentionDays) * DAY_SECONDS / intervalSec;
    }

    /**
     * Kayıt denetimi (dbq2 inceleme O1): taban saklama süresi içinde HİÇ dolamayacaksa {@code rules[N].min_samples} alan
     * hatası (kod {@code DBQUERY_RULE_ANOMALY}) — haftalık mevsimde saklamaya sığan hafta sayısı, günlükte gün sayısı,
     * mevsimsizde saklamadaki çalıştırma sayısı ({@code retention-days × gün / aralık}) en az örnek sayısından küçükse. Aksi
     * hâlde kural sonsuza dek "taban kuruluyor" kalır ve hiçbir şey denetlemeden "geçti" sayılırdı.
     *
     * @param intervalSec   izlemenin ETKİN aralığı (CRON: tetiklerin ortancası); null = mevsimsiz kural denetlenmez
     * @param retentionDays {@code site.monitor.metrics.dbquery.retention-days}
     */
    public static List<FieldError> retentionErrors(List<Rule> rules, Integer intervalSec, int retentionDays) {
        List<FieldError> out = new ArrayList<>();
        if (rules == null) return out;
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r == null || !KIND_ANOMALY.equals(r.kind())) continue;
            Anomaly a = anomalyOf(r);
            long can = fillableSamples(a, intervalSec, retentionDays);
            if (can < 0 || a.minSamples() <= can) continue;
            String unitTr, unitEn;
            if (SEASON_WEEKLY.equals(a.season())) { unitTr = "hafta"; unitEn = "weeks"; }
            else if (SEASON_DAILY.equals(a.season())) { unitTr = "gün"; unitEn = "days"; }
            else { unitTr = "çalıştırma"; unitEn = "runs"; }
            boolean lowerable = can >= MIN_SAMPLES_MIN;
            out.add(err("rules[" + i + "].min_samples", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " tabanı saklama süresi içinde hiç dolmaz · Kontrol geçmişi " + retentionDays
                            + " gün saklanıyor; bu süreye en çok " + can + " " + unitTr + " sığar, en az örnek sayısı "
                            + a.minSamples() + " · " + (lowerable
                            ? "En az örnek sayısını " + can + " ya da altına indirin (ya da mevsimi / aralığı değiştirin)."
                            : "Mevsimi ya da aralığı değiştirin (ya da bir yönetici saklama süresini uzatsın)."),
                    "Rule " + (i + 1) + " baseline can never fill within the retention period · Check history is kept for "
                            + retentionDays + " days; at most " + can + " " + unitEn + " fit in it, the minimum sample count "
                            + "is " + a.minSamples() + " · " + (lowerable
                            ? "Lower the minimum sample count to " + can + " or less (or change the season / interval)."
                            : "Change the season or the interval (or have an administrator extend the retention period)."))));
        }
        return out;
    }

    /**
     * Kayıt uyarısı (engellemez, dbq2 inceleme O1): mevsimsel anomali kuralında izleme aralığı günü (günlük) / haftayı
     * (haftalık) TAM bölmüyorsa ve mevsimsel tolerans aralığın yarısından dar ise (aralık &gt; 1 sa — tolerans en çok 30 dk)
     * önceki günlerin / haftaların aynı saatinde çalıştırma çoğu kez bulunmaz → taban çok geç dolar. Aralık bilinmiyorsa
     * uyarı yok. Biçim: {@code {code, rule_ids, interval_seconds, message}}.
     */
    public static List<Map<String, Object>> smartWarnings(List<Rule> rules, Integer intervalSec) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null || intervalSec == null || intervalSec <= 2 * 30 * 60) return out;
        List<String> ids = new ArrayList<>();
        for (Rule r : rules) {
            if (r == null || !KIND_ANOMALY.equals(r.kind())) continue;
            Anomaly a = anomalyOf(r);
            int period = SEASON_DAILY.equals(a.season()) ? DAY_SECONDS : SEASON_WEEKLY.equals(a.season()) ? WEEK_SECONDS : 0;
            if (period > 0 && period % intervalSec != 0) ids.add(r.id());
        }
        if (ids.isEmpty()) return out;
        String every = intervalText(intervalSec, false), everyEn = intervalText(intervalSec, true);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", WARN_ANOMALY_SEASON_RARE);
        w.put("rule_ids", ids);
        w.put("interval_seconds", intervalSec);
        w.put("message", Msg.t(
                "İzleme aralığı (" + every + ") günü / haftayı tam bölmüyor — mevsimsel olağan dışı değer kuralı önceki günlerin "
                        + "/ haftaların aynı saatinde çoğu kez çalıştırma bulamaz, taban çok geç dolar · Günü bölen bir aralık "
                        + "(ör. 1, 2, 3, 4, 6, 8, 12 ya da 24 sa) seçin ya da mevsimi kaldırın.",
                "The monitor interval (" + everyEn + ") does not divide the day / week evenly — a seasonal unusual value rule "
                        + "rarely finds a run at the same time on previous days / weeks, so its baseline fills very late · "
                        + "Choose an interval that divides the day (e.g. 1, 2, 3, 4, 6, 8, 12 or 24 h) or remove the season."));
        out.add(w);
        return out;
    }

    /** "5 sa" / "90 dk" / "5 h" / "90 min". */
    private static String intervalText(int sec, boolean en) {
        if (sec % 3600 == 0) return (sec / 3600) + (en ? " h" : " sa");
        if (sec % 60 == 0) return (sec / 60) + (en ? " min" : " dk");
        return sec + (en ? " s" : " sn");
    }

    private DbQueryRules() {}

    public record Parsed(List<Rule> rules, List<FieldError> errors) {}

    // ── API (katı) ───────────────────────────────────────────────────────────

    /** API gövdesindeki listeyi (List of Map) kurallara çevirir; biçim hatası {@link FieldError} olarak döner, fırlatmaz. */
    public static Parsed fromApi(Object raw) {
        List<Rule> rules = new ArrayList<>();
        List<FieldError> errors = new ArrayList<>();
        if (raw == null) return new Parsed(List.of(), List.of());
        if (!(raw instanceof List<?> list)) {
            errors.add(new FieldError("rules", "DBQUERY_RULES_INVALID", Msg.t(
                    "Kurallar bir liste olmalı · Gönderilen değer okunamadı · Kuralları yeniden ekleyin.",
                    "Rules must be a list · The value sent could not be read · Add the rules again.")));
            return new Parsed(List.of(), errors);
        }
        if (list.size() > MAX_RULES) {
            errors.add(new FieldError("rules", "DBQUERY_RULES_TOO_MANY", Msg.t(
                    "En çok " + MAX_RULES + " kural tanımlanabilir (" + list.size() + " gönderildi) · Çok sayıda kural "
                            + "alarmı anlaşılmaz yapar · Fazla kuralları silin ya da birleştirin.",
                    "At most " + MAX_RULES + " rules can be defined (" + list.size() + " sent) · Many rules make the "
                            + "alarm hard to understand · Delete or merge the extra rules.")));
        }
        Set<String> usedIds = new HashSet<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                if (id instanceof String s && !s.isBlank()) usedIds.add(s.trim());
            }
        }
        int n = Math.min(list.size(), MAX_RULES);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Rule r = parseRule(list.get(i), i, usedIds, seen, errors, false);
            if (r != null) rules.add(r);
        }
        return new Parsed(List.copyOf(rules), List.copyOf(errors));
    }

    // ── Saklanan JSON (gevşek) ───────────────────────────────────────────────

    /** Saklanan JSON'u gevşek okur (bozuk kural atlanır, asla fırlatmaz). */
    public static List<Rule> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Object> list = JSON.readValue(json, new TypeReference<List<Object>>() {});
            if (list == null) return List.of();
            Set<String> usedIds = new HashSet<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("id") instanceof String s && !s.isBlank()) usedIds.add(s.trim());
            }
            List<Rule> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < list.size() && out.size() < MAX_RULES; i++) {
                List<FieldError> errs = new ArrayList<>();
                Rule r = parseRule(list.get(i), i, usedIds, seen, errs, true);
                if (r != null && errs.isEmpty()) out.add(r);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String toJson(List<Rule> rules) {
        try {
            return JSON.writeValueAsString(toApi(rules));
        } catch (Exception e) {
            return "[]";
        }
    }

    /** API'ye yazılan biçim (snake_case, null alanlar yok). */
    public static List<Map<String, Object>> toApi(List<Rule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null) return out;
        for (Rule r : rules) {
            if (r == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "id", r.id());
            put(m, "label", r.label());
            put(m, "kind", r.kind());
            put(m, "column", r.column());
            put(m, "aggregate", r.aggregate());
            put(m, "operator", r.operator());
            put(m, "scope", r.scope());
            put(m, "warning", thresholdToApi(r.warning()));
            put(m, "critical", thresholdToApi(r.critical()));
            put(m, "timezone", r.timezone());
            if (KIND_ANOMALY.equals(r.kind())) {
                Anomaly a = anomalyOf(r);
                put(m, "direction", a.direction());
                put(m, "k_warning", number(a.kWarning()));
                put(m, "k_critical", number(a.kCritical()));
                put(m, "window", a.window());
                put(m, "min_samples", a.minSamples());
                put(m, "season", a.season());
            } else if (KIND_FROZEN.equals(r.kind())) {
                Frozen f = frozenOf(r);
                put(m, "runs_warning", f.runsWarning());
                put(m, "runs_critical", f.runsCritical());
            }
            if (r.change() != null) {
                Map<String, Object> c = new LinkedHashMap<>();
                put(c, "of", r.change().of());
                put(c, "mode", r.change().mode());
                put(c, "direction", r.change().direction());
                // Önceki çalıştırma tabanı yazılmaz (saklı JSON ve eski istemciler bugünkü gibi kalsın)
                if (!BASELINE_PREVIOUS.equals(r.change().baselineOrPrevious())) put(c, "baseline", r.change().baseline());
                m.put("change", c);
            }
            out.add(m);
        }
        return out;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static Object thresholdToApi(Threshold t) {
        if (t == null) return null;
        if (t.text() != null) return t.text();
        if (t.isRange()) return List.of(number(t.low()), number(t.high()));
        return t.value() == null ? null : number(t.value());
    }

    /** Tam sayıysa uzun tam sayı (JSON'da "10", "10.0" değil). */
    private static Object number(Double d) {
        if (d == null) return null;
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue();
        return d;
    }

    // ── Tek kural ────────────────────────────────────────────────────────────

    /**
     * @param lenient saklı kayıt okuması: akıllı kuralın sınır dışı / bozuk parametresi kuralı DÜŞÜRMEZ, sınıra / varsayılana
     *                çekilir (başka sürümün yazdığı değer izlemeyi sessizce kuralsız bırakmasın — dbq2 D)
     */
    private static Rule parseRule(Object raw, int i, Set<String> usedIds, Set<String> seen, List<FieldError> errors,
                                  boolean lenient) {
        String base = "rules[" + i + "]";
        if (!(raw instanceof Map<?, ?> m)) {
            errors.add(err(base, "DBQUERY_RULE_INVALID", Msg.t(
                    "Kural " + (i + 1) + " okunamadı · Kural bir nesne olmalı · Kuralı silip yeniden ekleyin.",
                    "Rule " + (i + 1) + " could not be read · A rule must be an object · Delete it and add it again.")));
            return null;
        }
        int before = errors.size();
        String id = str(m.get("id"));
        if (id == null) {
            int k = i + 1;
            while (usedIds.contains("r" + k) || seen.contains("r" + k)) k++;
            id = "r" + k;
        } else if (!validId(id)) {
            errors.add(err(base + ".id", "DBQUERY_RULE_ID", Msg.t(
                    "Kural " + (i + 1) + " kimliği geçersiz · Kimlik harf, rakam, '-' ya da '_' içermeli (en çok 40) · "
                            + "Kuralı silip yeniden ekleyin.",
                    "Rule " + (i + 1) + " has an invalid id · The id must use letters, digits, '-' or '_' (at most 40) · "
                            + "Delete the rule and add it again.")));
        }
        if (!seen.add(id)) {
            errors.add(err(base + ".id", "DBQUERY_RULE_ID", Msg.t(
                    "Kural " + (i + 1) + " kimliği başka bir kuralla aynı · Her kuralın kimliği tekil olmalı · "
                            + "Kuralı silip yeniden ekleyin.",
                    "Rule " + (i + 1) + " has the same id as another rule · Each rule needs a unique id · "
                            + "Delete the rule and add it again.")));
        }
        String label = str(m.get("label"));
        if (label != null && label.length() > MAX_LABEL) {
            errors.add(err(base + ".label", "DBQUERY_RULE_LABEL", Msg.t(
                    "Kural " + (i + 1) + " adı çok uzun (en çok " + MAX_LABEL + " karakter) · Kısa ad mailde ve "
                            + "ekranda okunur kalır · Adı kısaltın.",
                    "Rule " + (i + 1) + " name is too long (at most " + MAX_LABEL + " characters) · A short name stays "
                            + "readable in mails and on screen · Shorten it.")));
        }
        String kind = upper(m.get("kind"));
        if (kind == null || !KINDS.contains(kind)) {
            errors.add(err(base + ".kind", "DBQUERY_RULE_KIND", Msg.t(
                    "Kural " + (i + 1) + " türü seçilmedi · Tür: satır sayısı, kolon değeri, metin eşleşmesi, değişim, "
                            + "tazelik, olağan dışı değer ya da donmuş değer · Bir tür seçin.",
                    "Rule " + (i + 1) + " has no type · Type: row count, column value, text match, change, freshness, "
                            + "unusual value or frozen value · Choose a type.")));
            return null;
        }
        String column = str(m.get("column"));
        String aggregate = upper(m.get("aggregate"));
        String operator = upper(m.get("operator"));
        String scope = upper(m.get("scope"));
        Change change = null;

        boolean freshness = kind.equals(KIND_FRESHNESS);
        boolean smart = isSmart(kind);
        boolean needsColumn = kind.equals(KIND_COLUMN_VALUE) || kind.equals(KIND_TEXT_MATCH) || freshness || smart;
        if (kind.equals(KIND_CHANGE)) {
            change = parseChange(m.get("change"), base, i, errors);
            needsColumn = change != null && "COLUMN".equals(change.of());
        }
        if (needsColumn) {
            if (column == null) {
                errors.add(err(base + ".column", "DBQUERY_RULE_COLUMN", Msg.t(
                        "Kural " + (i + 1) + " için kolon adı gerekli · Değer, sorgu sonucundaki bir kolondan okunur · "
                                + "Sorgunun döndürdüğü kolonlardan birini yazın.",
                        "Rule " + (i + 1) + " needs a column name · The value is read from a column of the query result · "
                                + "Enter one of the columns the query returns.")));
            } else if (column.length() > MAX_COLUMN) {
                errors.add(err(base + ".column", "DBQUERY_RULE_COLUMN", Msg.t(
                        "Kural " + (i + 1) + " kolon adı çok uzun (en çok " + MAX_COLUMN + ") · Kolon adı sorgu sonucuyla "
                                + "eşleşmeli · Kolon adını düzeltin.",
                        "Rule " + (i + 1) + " column name is too long (at most " + MAX_COLUMN + ") · It must match the "
                                + "query result · Fix the column name.")));
            }
        } else {
            column = null;
        }
        boolean usesAggregate = kind.equals(KIND_COLUMN_VALUE) || smart || (change != null && "COLUMN".equals(change.of()));
        if (freshness) {
            // Tazelik: en yeni kayıt (MAX, varsayılan), en eski (MIN — ör. en eski bekleyen kaydın yaşı) ya da ilk satır
            if (aggregate == null) aggregate = "MAX";
            else if (!FRESHNESS_AGGREGATES.contains(aggregate)) {
                errors.add(err(base + ".aggregate", "DBQUERY_RULE_AGGREGATE", Msg.t(
                        "Kural " + (i + 1) + " için geçersiz hesap · Tazelik kuralında seçenekler: en yeni (en büyük), en eski "
                                + "(en küçük) ya da ilk satır · Bir seçenek seçin.",
                        "Rule " + (i + 1) + " has an invalid calculation · A freshness rule can use the newest (max), the "
                                + "oldest (min) or the first row · Choose one.")));
            }
        } else if (usesAggregate) {
            if (aggregate == null) aggregate = "FIRST";
            else if (!AGGREGATES.contains(aggregate)) {
                errors.add(err(base + ".aggregate", "DBQUERY_RULE_AGGREGATE", Msg.t(
                        "Kural " + (i + 1) + " için geçersiz hesap · Seçenekler: ilk satır, en büyük, en küçük, toplam, "
                                + "ortalama, dolu değer sayısı · Bir seçenek seçin.",
                        "Rule " + (i + 1) + " has an invalid calculation · Options: first row, max, min, sum, average, "
                                + "count of non-empty values · Choose one.")));
            }
        } else {
            aggregate = null;
        }
        if (smart) {
            // Akıllı kurallarda karşılaştırma / eşik yoktur — parametreleri eşiktir; gönderilen operatör / eşik yok sayılır
            Anomaly anomaly = !kind.equals(KIND_ANOMALY) ? null : lenient ? coerceAnomaly(m) : parseAnomaly(m, base, i, errors);
            Frozen frozen = !kind.equals(KIND_FROZEN) ? null : lenient ? coerceFrozen(m) : parseFrozen(m, base, i, errors);
            if (errors.size() > before) return null;
            return new Rule(id, label, kind, column, aggregate, null, null, null, null, null, null, anomaly, frozen);
        }
        boolean text = kind.equals(KIND_TEXT_MATCH);
        List<String> ops = text ? TEXT_OPERATORS : freshness ? FRESHNESS_OPERATORS : NUMERIC_OPERATORS;
        if (freshness && operator != null && NUMERIC_OPERATORS.contains(operator) && !ops.contains(operator)) {
            errors.add(err(base + ".operator", "DBQUERY_RULE_OPERATOR", Msg.t(
                    "Kural " + (i + 1) + " için karşılaştırma geçersiz · Tazelik kuralı yalnız yaş büyükse (> ya da ≥) "
                            + "alarm verir · Büyüktür ya da büyük-eşittir seçin.",
                    "Rule " + (i + 1) + " has an invalid comparison · A freshness rule only alarms when the age is greater "
                            + "(> or ≥) · Choose greater than or greater than or equal.")));
            return null;
        }
        if (operator == null || !ops.contains(operator)) {
            errors.add(err(base + ".operator", "DBQUERY_RULE_OPERATOR", Msg.t(
                    "Kural " + (i + 1) + " için karşılaştırma seçilmedi · Kural hangi durumda alarm vereceğini bilmeli · "
                            + "Bir karşılaştırma seçin.",
                    "Rule " + (i + 1) + " has no comparison · The rule must know when to alarm · Choose a comparison.")));
            return null;
        }
        if (text) {
            if (scope == null) scope = "ANY_ROW";
            else if (!SCOPES.contains(scope)) {
                errors.add(err(base + ".scope", "DBQUERY_RULE_SCOPE", Msg.t(
                        "Kural " + (i + 1) + " için geçersiz satır kapsamı · Seçenekler: herhangi bir satır, tüm satırlar, "
                                + "ilk satır · Bir seçenek seçin.",
                        "Rule " + (i + 1) + " has an invalid row scope · Options: any row, all rows, first row · "
                                + "Choose one.")));
            }
        } else {
            scope = null;
        }
        boolean range = operator.equals("BETWEEN") || operator.equals("OUTSIDE");
        boolean noText = operator.equals("EMPTY") || operator.equals("NOT_EMPTY");
        Threshold warning = parseThreshold(m.get("warning"), text, range, noText, base + ".warning", i, errors);
        Threshold critical = parseThreshold(m.get("critical"), text, range, noText, base + ".critical", i, errors);
        if (m.get("warning") == null && m.get("critical") == null) {
            errors.add(err(base + ".warning", "DBQUERY_RULE_THRESHOLD", Msg.t(
                    "Kural " + (i + 1) + " için eşik gerekli · Uyarı ya da kritik eşiğinden en az biri dolu olmalı · "
                            + "Bir eşik değeri girin.",
                    "Rule " + (i + 1) + " needs a threshold · At least one of the warning or critical thresholds is "
                            + "required · Enter a threshold value.")));
        }
        // Tazelik kuralının saat dilimi (r177 ikinci tur, O-1): yalnız tazelikte; boş = ayardaki yerel dilim
        String timezone = null;
        if (freshness) {
            String tz = str(m.get("timezone"));
            if (tz != null) {
                if (!validTimezone(tz)) {
                    errors.add(err(base + ".timezone", "DBQUERY_RULE_TIMEZONE", Msg.t(
                            "Kural " + (i + 1) + " için saat dilimi tanınmadı · Saat dilimi olmayan zaman damgaları bu "
                                    + "dilimde yorumlanır · Europe/Istanbul ya da UTC gibi bir IANA dilim adı yazın; boş "
                                    + "bırakırsanız sistem ayarı kullanılır.",
                            "Rule " + (i + 1) + " has an unknown time zone · Timestamps without a time zone are read in "
                                    + "this zone · Enter an IANA zone name such as Europe/Istanbul or UTC, or leave it "
                                    + "empty to use the system setting.")));
                } else {
                    timezone = tz.trim();
                }
            }
        }
        if (errors.size() > before) return null;
        return new Rule(id, label, kind, column, aggregate, operator, scope, warning, critical, change, timezone);
    }

    private static Change parseChange(Object raw, String base, int i, List<FieldError> errors) {
        if (!(raw instanceof Map<?, ?> c)) {
            errors.add(err(base + ".change", "DBQUERY_RULE_CHANGE", Msg.t(
                    "Kural " + (i + 1) + " için değişim ayarı gerekli · Neyin, nasıl (yüzde / adet) ve hangi yönde "
                            + "değiştiği seçilmeli · Değişim ayarlarını doldurun.",
                    "Rule " + (i + 1) + " needs change settings · Choose what changes, how (percent / absolute) and in "
                            + "which direction · Fill in the change settings.")));
            return null;
        }
        String of = upper(c.get("of"));
        String mode = upper(c.get("mode"));
        String dir = upper(c.get("direction"));
        String baseline = str(c.get("baseline"));
        if (of == null) of = "ROW_COUNT";
        if (mode == null) mode = "PERCENT";
        if (dir == null) dir = "ANY";
        baseline = baseline == null ? BASELINE_PREVIOUS : baseline.toLowerCase(Locale.ROOT);
        if (!CHANGE_BASELINES.contains(baseline)) {
            errors.add(err(base + ".change.baseline", "DBQUERY_RULE_CHANGE", Msg.t(
                    "Kural " + (i + 1) + " değişim tabanı geçersiz · Taban önceki çalıştırma, dünün aynı saati ya da geçen "
                            + "haftanın aynı saati olabilir · Bir taban seçin.",
                    "Rule " + (i + 1) + " change baseline is invalid · The baseline can be the previous run, the same time "
                            + "yesterday or the same time last week · Choose a baseline.")));
            return null;
        }
        if (!CHANGE_OF.contains(of) || !CHANGE_MODES.contains(mode) || !CHANGE_DIRECTIONS.contains(dir)) {
            errors.add(err(base + ".change", "DBQUERY_RULE_CHANGE", Msg.t(
                    "Kural " + (i + 1) + " değişim ayarı geçersiz · Değişim satır sayısında ya da bir kolonda; yüzde ya da "
                            + "adet olarak; artış, azalış ya da her iki yönde ölçülür · Seçenekleri yeniden seçin.",
                    "Rule " + (i + 1) + " change settings are invalid · Change is measured on the row count or a column; "
                            + "as percent or absolute; as increase, decrease or either · Choose the options again.")));
            return null;
        }
        return new Change(of, mode, dir, baseline);
    }

    // ── Akıllı kural parametreleri (dbq2 D) ──────────────────────────────────

    private static Anomaly parseAnomaly(Map<?, ?> m, String base, int i, List<FieldError> errors) {
        int before = errors.size();
        String dir = lower(m.get("direction"));
        if (dir == null) dir = DIRECTION_BOTH;
        else if (!ANOMALY_DIRECTIONS.contains(dir)) {
            errors.add(err(base + ".direction", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " için yön geçersiz · Olağan dışı değer iki yönde, yalnız yüksek ya da yalnız düşük "
                            + "değerde alarm verebilir · Bir yön seçin.",
                    "Rule " + (i + 1) + " has an invalid direction · An unusual value can alarm in both directions, only on "
                            + "high or only on low values · Choose a direction.")));
        }
        Double kw = m.get("k_warning") == null ? Double.valueOf(K_WARNING_DEFAULT) : toDouble(m.get("k_warning"));
        if (kw == null || kw < K_MIN || kw > K_MAX) {
            errors.add(err(base + ".k_warning", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " uyarı katsayısı 1 ile 10 arasında bir sayı olmalı · Katsayı olağan aralığın "
                            + "genişliğidir (kaç standart sapma) · 2.5 gibi bir değer girin.",
                    "Rule " + (i + 1) + " warning factor must be a number from 1 to 10 · The factor is the width of the usual "
                            + "range (how many standard deviations) · Enter a value such as 2.5.")));
            kw = null;
        }
        // Kritik katsayı gönderilmediyse varsayılan; uyarı katsayısı varsayılanı aşıyorsa ona eşitlenir (kritik ≥ uyarı)
        Double kc = m.get("k_critical") == null
                ? Double.valueOf(Math.max(K_CRITICAL_DEFAULT, kw == null ? K_WARNING_DEFAULT : kw))
                : toDouble(m.get("k_critical"));
        if (kc == null || kc < K_MIN || kc > K_MAX) {
            errors.add(err(base + ".k_critical", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " kritik katsayısı 1 ile 10 arasında bir sayı olmalı · Katsayı olağan aralığın "
                            + "genişliğidir (kaç standart sapma) · 3.5 gibi bir değer girin.",
                    "Rule " + (i + 1) + " critical factor must be a number from 1 to 10 · The factor is the width of the "
                            + "usual range (how many standard deviations) · Enter a value such as 3.5.")));
        } else if (kw != null && kc < kw) {
            errors.add(err(base + ".k_critical", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " kritik katsayısı uyarı katsayısından küçük olamaz · Kritik, uyarıdan daha büyük "
                            + "bir sapmadır · Kritik katsayıyı uyarıdakine eşit ya da ondan büyük girin.",
                    "Rule " + (i + 1) + " critical factor cannot be smaller than the warning factor · Critical is a larger "
                            + "deviation than warning · Enter a critical factor equal to or larger than the warning one.")));
        }
        Integer window = m.get("window") == null ? Integer.valueOf(WINDOW_DEFAULT) : intOf(m.get("window"));
        if (window == null || window < WINDOW_MIN || window > WINDOW_MAX) {
            errors.add(err(base + ".window", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " penceresi " + WINDOW_MIN + " ile " + WINDOW_MAX + " arasında bir tam sayı olmalı · "
                            + "Pencere olağan aralığın hesaplandığı geçmiş örnek sayısıdır · 30 gibi bir değer girin.",
                    "Rule " + (i + 1) + " window must be a whole number from " + WINDOW_MIN + " to " + WINDOW_MAX + " · The "
                            + "window is the number of past samples the usual range is computed from · Enter a value "
                            + "such as 30.")));
            window = null;
        }
        int maxMin = window == null ? WINDOW_MAX : window;
        Integer minSamples = m.get("min_samples") == null ? Integer.valueOf(Math.min(MIN_SAMPLES_DEFAULT, maxMin))
                : intOf(m.get("min_samples"));
        if (minSamples == null || minSamples < MIN_SAMPLES_MIN || minSamples > maxMin) {
            errors.add(err(base + ".min_samples", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " en az örnek sayısı " + MIN_SAMPLES_MIN + " ile pencere (" + maxMin + ") arasında "
                            + "bir tam sayı olmalı · Daha az örnekle olağan aralık güvenilmez, pencereden fazlası hiç "
                            + "dolmaz · 10 gibi bir değer girin.",
                    "Rule " + (i + 1) + " minimum sample count must be a whole number from " + MIN_SAMPLES_MIN + " to the "
                            + "window (" + maxMin + ") · Fewer samples make the usual range unreliable, more than the "
                            + "window never fills · Enter a value such as 10.")));
        }
        String season = lower(m.get("season"));
        if (season == null) season = SEASON_NONE;
        else if (!SEASONS.contains(season)) {
            errors.add(err(base + ".season", "DBQUERY_RULE_ANOMALY", Msg.t(
                    "Kural " + (i + 1) + " mevsim seçeneği geçersiz · Seçenekler: yok (son çalıştırmalar), günlük (önceki "
                            + "günlerin aynı saati), haftalık (önceki haftaların aynı gün ve saati) · Bir seçenek seçin.",
                    "Rule " + (i + 1) + " season option is invalid · Options: none (recent runs), daily (the same time on "
                            + "previous days), weekly (the same day and time in previous weeks) · Choose one.")));
        }
        if (errors.size() > before) return null;
        return new Anomaly(dir, kw, kc, window, minSamples, season);
    }

    private static Frozen parseFrozen(Map<?, ?> m, String base, int i, List<FieldError> errors) {
        int before = errors.size();
        Integer rw = m.get("runs_warning") == null ? Integer.valueOf(RUNS_WARNING_DEFAULT) : intOf(m.get("runs_warning"));
        if (rw == null || rw < RUNS_MIN || rw > RUNS_MAX) {
            errors.add(err(base + ".runs_warning", "DBQUERY_RULE_FROZEN", Msg.t(
                    "Kural " + (i + 1) + " uyarı için çalıştırma sayısı " + RUNS_MIN + " ile " + RUNS_MAX + " arasında bir tam "
                            + "sayı olmalı · Değer bu kadar ardışık çalıştırmada aynı kalırsa uyarı verilir · 6 gibi bir "
                            + "değer girin.",
                    "Rule " + (i + 1) + " warning run count must be a whole number from " + RUNS_MIN + " to " + RUNS_MAX
                            + " · A warning is raised when the value stays the same for this many runs in a row · "
                            + "Enter a value such as 6.")));
            rw = null;
        }
        Integer rc = null;
        if (m.get("runs_critical") != null) {
            rc = intOf(m.get("runs_critical"));
            if (rc == null || rc > RUNS_MAX || (rw != null && rc <= rw) || rc < RUNS_MIN) {
                errors.add(err(base + ".runs_critical", "DBQUERY_RULE_FROZEN", Msg.t(
                        "Kural " + (i + 1) + " kritik için çalıştırma sayısı uyarıdakinden büyük ve en çok " + RUNS_MAX
                                + " olmalı · Kritik, değerin uyarıdan daha uzun süre aynı kalmasıdır · Boş bırakın ya da "
                                + "uyarıdakinden büyük bir tam sayı girin.",
                        "Rule " + (i + 1) + " critical run count must be larger than the warning one and at most " + RUNS_MAX
                                + " · Critical means the value stays the same longer than for a warning · Leave it empty "
                                + "or enter a whole number larger than the warning one.")));
            }
        }
        if (errors.size() > before) return null;
        return new Frozen(rw, rc);
    }

    /** Saklı anomali parametreleri (gevşek): geçersiz seçenek → varsayılan, sınır dışı sayı → sınıra çekilir. */
    static Anomaly coerceAnomaly(Map<?, ?> m) {
        String dir = lower(m.get("direction"));
        if (dir == null || !ANOMALY_DIRECTIONS.contains(dir)) dir = DIRECTION_BOTH;   // List.of null kabul etmez
        Double kwRaw = toDouble(m.get("k_warning"));
        double kw = kwRaw == null ? K_WARNING_DEFAULT : Math.max(K_MIN, Math.min(K_MAX, kwRaw));
        Double kcRaw = toDouble(m.get("k_critical"));
        double kc = kcRaw == null ? Math.max(K_CRITICAL_DEFAULT, kw) : Math.max(K_MIN, Math.min(K_MAX, kcRaw));
        if (kc < kw) kc = kw;
        Double wRaw = toDouble(m.get("window"));
        int window = wRaw == null ? WINDOW_DEFAULT : (int) Math.max(WINDOW_MIN, Math.min(WINDOW_MAX, Math.round(wRaw)));
        Double minRaw = toDouble(m.get("min_samples"));
        int min = minRaw == null ? Math.min(MIN_SAMPLES_DEFAULT, window)
                : (int) Math.max(MIN_SAMPLES_MIN, Math.min(window, Math.round(minRaw)));
        String season = lower(m.get("season"));
        if (season == null || !SEASONS.contains(season)) season = SEASON_NONE;
        return new Anomaly(dir, kw, kc, window, min, season);
    }

    /** Saklı donma parametreleri (gevşek): sınıra çekilir; uyarıdan büyük olamayan kritik düşer. */
    static Frozen coerceFrozen(Map<?, ?> m) {
        Double rwRaw = toDouble(m.get("runs_warning"));
        int rw = rwRaw == null ? RUNS_WARNING_DEFAULT : (int) Math.max(RUNS_MIN, Math.min(RUNS_MAX, Math.round(rwRaw)));
        Double rcRaw = toDouble(m.get("runs_critical"));
        Integer rc = rcRaw == null ? null : (int) Math.min(RUNS_MAX, Math.round(rcRaw));
        if (rc != null && rc <= rw) rc = null;
        return new Frozen(rw, rc);
    }

    /** Tam sayı (ondalıksız sayı ya da sayı metni); değilse null. */
    static Integer intOf(Object o) {
        Double d = toDouble(o);
        if (d == null || d != Math.rint(d) || Math.abs(d) > 1_000_000_000d) return null;
        return d.intValue();
    }

    private static Threshold parseThreshold(Object raw, boolean text, boolean range, boolean noText, String field, int i,
                                            List<FieldError> errors) {
        if (raw == null) return null;
        if (text) {
            String s = raw instanceof String str ? str : (raw instanceof Number || raw instanceof Boolean ? String.valueOf(raw) : null);
            if (s == null) {
                errors.add(thresholdError(field, i));
                return null;
            }
            if (s.length() > MAX_TEXT) {
                errors.add(err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                        "Kural " + (i + 1) + " aranan metni çok uzun (en çok " + MAX_TEXT + " karakter) · Kısa ve ayırt "
                                + "edici bir metin daha güvenilir eşleşir · Metni kısaltın.",
                        "Rule " + (i + 1) + " search text is too long (at most " + MAX_TEXT + " characters) · A short, "
                                + "distinctive text matches more reliably · Shorten it.")));
                return null;
            }
            if (s.isEmpty() && !noText) {
                errors.add(err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                        "Kural " + (i + 1) + " için aranan metin boş · Metin eşleşmesi bir metne göre yapılır · "
                                + "Aranacak metni yazın.",
                        "Rule " + (i + 1) + " search text is empty · Text matching needs a text · Enter the text to look for.")));
                return null;
            }
            return new Threshold(null, null, null, s);
        }
        if (range) {
            if (raw instanceof List<?> l && l.size() == 2) {
                Double lo = toDouble(l.get(0)), hi = toDouble(l.get(1));
                if (lo != null && hi != null && lo <= hi) return new Threshold(null, lo, hi, null);
            }
            errors.add(err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                    "Kural " + (i + 1) + " aralığı geçersiz · Aralık [alt, üst] biçiminde iki sayı ve alt ≤ üst olmalı · "
                            + "Alt ve üst sınırı girin.",
                    "Rule " + (i + 1) + " range is invalid · A range is two numbers [low, high] with low ≤ high · "
                            + "Enter the low and high limits.")));
            return null;
        }
        Double v = toDouble(raw);
        if (v == null) {
            errors.add(thresholdError(field, i));
            return null;
        }
        return new Threshold(v, null, null, null);
    }

    private static FieldError thresholdError(String field, int i) {
        return err(field, "DBQUERY_RULE_THRESHOLD", Msg.t(
                "Kural " + (i + 1) + " eşiği bir sayı olmalı · Karşılaştırma sayısal bir değerle yapılır · "
                        + "Eşiği sayı olarak girin (ondalık ayırıcı nokta).",
                "Rule " + (i + 1) + " threshold must be a number · The comparison uses a numeric value · "
                        + "Enter the threshold as a number."));
    }

    // ── Kural parmak izi (r177) ─────────────────────────────────────────────

    /**
     * Kuralın ÖLÇÜMÜNÜ belirleyen tanımın kısa özeti (16 hex): değişim kuralının tabanı ve geriye dönük deneme yalnız aynı
     * kimlik + aynı parmak izi taşıyan saklı ölçümü kullanır (H-1: kolon / hesap / değişimin konusu değişince eski ölçü
     * başka bir şeyi ölçmüş olur). Eşik, seviye, ad ve değişimin kipi / yönü / tabanı ölçümü değiştirmez — parmak izine
     * GİRMEZ (eşik değiştirmek tabanı sıfırlamaz). Metin eşleşmesinde ölçü (eşleşen satır sayısı) aranan metne, operatöre ve
     * kapsama bağlıdır, onlar girer. Biçim kalıcıdır (saklı satırlar onunla eşlenir).
     */
    public static String fingerprint(Rule r) {
        if (r == null || r.kind() == null) return null;
        String col = r.column() == null ? "" : r.column().trim().toLowerCase(Locale.ROOT);
        String agg = r.aggregate() == null ? "" : r.aggregate();
        String basis = switch (r.kind()) {
            case KIND_ROW_COUNT -> KIND_ROW_COUNT;
            case KIND_COLUMN_VALUE -> r.kind() + "|" + col + "|" + agg;
            // Akıllı kurallar kolonun hesaplanmış DEĞERİNİ ölçer — kolon değeri kuralıyla AYNI ölçü (dbq2 D): aynı kimlikli bir
            // kolon değeri kuralını olağan dışı / donmuş değer kuralına çevirmek geçmişi (tabanı) korur. Katsayı, yön, pencere,
            // mevsim ve çalıştırma sayısı ölçümü değiştirmez (eşik gibi) — girmez.
            case KIND_ANOMALY, KIND_FROZEN -> KIND_COLUMN_VALUE + "|" + col + "|" + agg;
            // Tazelikte dilim ölçümü (yaşı) değiştirir; dilimsiz kural bugünkü özetini korur
            case KIND_FRESHNESS -> r.kind() + "|" + col + "|" + agg + (r.timezone() == null ? "" : "|" + r.timezone());
            case KIND_TEXT_MATCH -> {
                Threshold t = r.critical() != null ? r.critical() : r.warning();
                String needle = t == null || t.text() == null ? "" : DbQueryEvaluator.TextMatcher.fold(t.text());
                yield KIND_TEXT_MATCH + "|" + col + "|" + r.operator() + "|" + (r.scope() == null ? "ANY_ROW" : r.scope())
                        + "|" + needle;
            }
            case KIND_CHANGE -> {
                String of = r.change() == null ? "ROW_COUNT" : r.change().of();
                yield KIND_CHANGE + "|" + of + ("COLUMN".equals(of) ? "|" + col + "|" + agg : "");
            }
            default -> r.kind();
        };
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    static Double toDouble(Object o) {
        if (o == null || o instanceof Boolean) return null;
        double d;
        if (o instanceof Number n) d = n instanceof BigDecimal bd ? bd.doubleValue() : n.doubleValue();
        else if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty() || t.length() > 40) return null;
            try { d = Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
        } else return null;
        return Double.isFinite(d) ? d : null;
    }

    private static boolean validId(String id) {
        if (id.length() > 40) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return !id.isEmpty();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(Object o) {
        String s = str(o);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String upper(Object o) {
        String s = str(o);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static FieldError err(String field, String code, String message) {
        return new FieldError(field, code, message);
    }
}
