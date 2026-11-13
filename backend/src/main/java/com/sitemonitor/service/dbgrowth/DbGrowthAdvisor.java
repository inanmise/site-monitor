package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Veritabanı büyümesi önerileri — SAF kurallar (2026-11-13). Kod listesi {@link #CODES} kalıcıdır: arayüz metni
 * {@code dbg.adv.<KOD>.title|body} (TR + EN, kapı {@code DbGrowthAdvisorI18nGateTest}). Eşikler bu sınıfın sabitleri — tek
 * yer.
 *
 * <p><b>Dürüst metin:</b> saklama kısaltması büyümeyi DURDURUR ve boşalan yer tablonun içinde yeniden kullanılır; dosya
 * ancak {@code VACUUM FULL} / {@code pg_repack} ile küçülür (DBA işi — uygulama asla yapmaz). Tahmini satır / bayt
 * {@code est_*} alanları "tahmin" olarak gösterilir.
 */
public final class DbGrowthAdvisor {

    private DbGrowthAdvisor() {}

    /** Kalıcı kod listesi (sıra = ekran sırası değil; ekran önem sırasıyla dizer). */
    public static final List<String> CODES = List.of(
            "CAPACITY", "LEGAL_HOLD", "SHORTEN_CANDIDATE", "NOT_SATURATED", "NO_RULE", "BOUNDED_GROWING",
            "CLEANUP_BEHIND", "DEAD_TUPLES", "TOAST_HEAVY", "INDEX_BLOAT", "UNUSED_INDEX", "HOT_MONITOR");

    /** Önem sırası (büyük = önce). */
    public static final List<String> SEVERITIES = List.of("info", "warn", "critical");

    /** Eylem kodları: ayar uygula, saklama sayfasını aç, DBA işi, kod değişikliği, yok. */
    public static final List<String> ACTIONS = List.of("apply_retention", "open_retention", "dba", "code", "none");

    // ── Eşikler ─────────────────────────────────────────────────────────────────────────────────────────
    static final double SHORTEN_SHARE = 0.10;
    static final double SHORTEN_WARN_SHARE = 0.25;
    static final long SHORTEN_MIN_BYTES = 64L << 20;
    static final long NOT_SATURATED_MIN_BYTES = 16L << 20;
    static final long GROWTH_MIN_TABLE_BYTES = 64L << 20;
    static final long GROWTH_MIN_30D_BYTES = 32L << 20;
    static final double NO_RULE_GROWTH_RATIO = 0.05;
    static final double BOUNDED_GROWTH_RATIO = 0.10;
    static final double BEHIND_SLACK_DAYS = 1.5;
    static final long DEAD_MIN_ROWS = 10_000;
    static final double DEAD_PCT = 20.0;
    static final double DEAD_WARN_PCT = 50.0;
    static final long TOAST_MIN_BYTES = 100L << 20;
    static final double TOAST_SHARE = 0.5;
    public static final double CAPACITY_CRITICAL_PCT = 85.0;
    public static final int CAPACITY_CRITICAL_DAYS = 30;
    static final double CAPACITY_WARN_PCT = 70.0;
    static final int CAPACITY_WARN_DAYS = 90;
    /** Önerilen gün değerleri (hızlı seçim çipleriyle uyumlu). */
    static final int[] NICE_DAYS = {7, 14, 30, 60, 90, 180, 365, 730, 1095};

    /** Bir tablonun ölçümleri ve saklama bağlamı. {@code primary} = tablonun yaş kuralı (yoksa ilk kuralı; katalog dışı → null). */
    public record TableFacts(String name, long totalBytes, Long tableBytes, Long indexBytes, Long liveRows, Long deadRows,
                             Long growth30Bytes, RetentionPolicy primary, List<RetentionPolicy> policies, Integer effectiveDays,
                             Double oldestAgeDays, Double equilibriumBytes, boolean lastRunFailed,
                             Double purgeFraction, String purgeMethod) {

        public TableFacts {
            policies = policies == null ? List.of() : List.copyOf(policies);
        }

        Long toastBytes() {
            if (tableBytes == null || indexBytes == null) return null;
            return Math.max(0, totalBytes - tableBytes - indexBytes);
        }
    }

    /** Veritabanı geneli bağlam. {@code capacityBytes} ≤ 0 = bilinmiyor. */
    public record Context(long dbBytes, long capacityBytes, Double usedPct, Integer daysUntilFull, boolean forecastFromHistory,
                          boolean holdActive) {}

    /** Bir bulgu. {@code params} kodun ek sayıları (yüzde, gün, dizin adı …). */
    public record Finding(String code, String severity, String table, String policyId, String settingKey, Integer currentDays,
                          Integer suggestedDays, Integer minDays, Long estRows, Long estBytes, String estMethod, String dataClass,
                          String action, Map<String, Object> params) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("severity", severity);
            m.put("table", table);
            m.put("policy_id", policyId);
            m.put("setting_key", settingKey);
            m.put("current_days", currentDays);
            m.put("suggested_days", suggestedDays);
            m.put("min_days", minDays);
            m.put("est_rows", estRows);
            m.put("est_bytes", estBytes);
            m.put("est_method", estMethod);
            m.put("data_class", dataClass);
            m.put("action", action);
            m.put("params", params == null ? Map.of() : params);
            return m;
        }
    }

    /** Tablo + genel bulgular, önem sırasıyla (aynı önemde büyük tablo önce). */
    public static List<Finding> advise(List<TableFacts> tables, Context ctx) {
        List<Finding> out = new ArrayList<>();
        Finding cap = capacity(ctx);
        if (cap != null) out.add(cap);
        if (ctx != null && ctx.holdActive()) {
            out.add(new Finding("LEGAL_HOLD", "warn", null, null, null, null, null, null, null, null, null, null,
                    "open_retention", Map.of()));
        }
        long db = ctx == null ? 0 : ctx.dbBytes();
        for (TableFacts t : tables == null ? List.<TableFacts>of() : tables) {
            out.addAll(forTable(t, ctx, db));
        }
        sort(out);
        return out;
    }

    /** Önem (critical → info), sonra tahmini bayt / tablo boyutu (büyük önce), sonra kod. */
    public static void sort(List<Finding> list) {
        list.sort(Comparator.comparingInt((Finding f) -> -SEVERITIES.indexOf(f.severity()))
                .thenComparing(f -> -sizeOf(f))
                .thenComparing(Finding::code)
                .thenComparing(f -> f.table() == null ? "" : f.table()));
    }

    private static long sizeOf(Finding f) {
        if (f.estBytes() != null) return f.estBytes();
        Object b = f.params() == null ? null : f.params().get("table_bytes");
        return b instanceof Number n ? n.longValue() : 0L;
    }

    static Finding capacity(Context ctx) {
        if (ctx == null || ctx.capacityBytes() <= 0) return null;
        Double pct = ctx.usedPct();
        Integer days = ctx.forecastFromHistory() ? ctx.daysUntilFull() : null;
        String sev = null;
        if ((pct != null && pct >= CAPACITY_CRITICAL_PCT) || (days != null && days <= CAPACITY_CRITICAL_DAYS)) sev = "critical";
        else if ((pct != null && pct >= CAPACITY_WARN_PCT) || (days != null && days <= CAPACITY_WARN_DAYS)) sev = "warn";
        if (sev == null) return null;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("used_pct", pct == null ? null : round1(pct));
        p.put("days_until_full", days);
        p.put("capacity_bytes", ctx.capacityBytes());
        p.put("db_bytes", ctx.dbBytes());
        return new Finding("CAPACITY", sev, null, null, null, null, null, null, null, null, null, null, "none", p);
    }

    /**
     * Aynı ayar anahtarını paylaşan ayarlanabilir kurallar — bir anahtarı kısaltmak HEPSİNİ birden kısaltır (ör.
     * {@code site.monitor.notification.retention-days} beş tabloyu yönetir; 2026-11-13 inceleme). Anahtarsız kural → yalnız kendisi.
     */
    public static List<RetentionPolicy> keySiblings(RetentionPolicy p) {
        if (p == null) return List.of();
        if (p.settingKey() == null) return List.of(p);
        List<RetentionPolicy> out = new ArrayList<>();
        for (RetentionPolicy x : RetentionCatalog.ALL) {
            if (x.configurable() && p.settingKey().equals(x.settingKey())) out.add(x);
        }
        if (out.isEmpty()) out.add(p);
        return out;
    }

    /** Anahtarın ortak tabanı: paylaşan kuralların EN BÜYÜK alt sınırı (hiçbiri tabanının altına inmesin). */
    public static int keyFloor(RetentionPolicy p) {
        int floor = p == null ? 0 : p.minDays();
        for (RetentionPolicy x : keySiblings(p)) floor = Math.max(floor, x.minDays());
        return floor;
    }

    /** Paylaşan kurallardan biri kişisel / güvenlik denetimi verisi mi (uyum uyarısı). */
    public static boolean keyNeedsCompliance(RetentionPolicy p) {
        for (RetentionPolicy x : keySiblings(p)) {
            if (x.dataClass() == RetentionPolicy.DataClass.PERSONAL || x.dataClass() == RetentionPolicy.DataClass.SECURITY_AUDIT) return true;
        }
        return false;
    }

    static List<Finding> forTable(TableFacts t, Context ctx, long dbBytes) {
        List<Finding> out = new ArrayList<>();
        RetentionPolicy p = t.primary();
        Integer eff = t.effectiveDays();
        boolean ageRule = p != null && p.mode() == RetentionPolicy.Mode.AGE && p.timeColumn() != null;
        double share = dbBytes > 0 ? (double) t.totalBytes() / dbBytes : 0;
        boolean hold = ctx != null && ctx.holdActive();

        // ── Kısaltma adayı ──
        int floor = p == null ? 0 : keyFloor(p);
        if (ageRule && p.configurable() && eff != null && eff > floor && t.totalBytes() >= SHORTEN_MIN_BYTES) {
            double eqShare = t.equilibriumBytes() != null && dbBytes > 0 ? t.equilibriumBytes() / dbBytes : 0;
            if (share > SHORTEN_SHARE || eqShare > SHORTEN_SHARE) {
                Integer suggested = suggestDays(eff, floor);
                if (suggested != null) {
                    double frac = t.purgeFraction() != null ? clamp01(t.purgeFraction())
                            : uniformPurgeFraction(t.oldestAgeDays(), eff, suggested);
                    String method = t.purgeFraction() != null ? (t.purgeMethod() == null ? "histogram" : t.purgeMethod()) : "uniform";
                    Long rows = t.liveRows() == null ? null : Math.round(t.liveRows() * frac);
                    long bytes = Math.round(t.totalBytes() * frac);
                    boolean pressure = ctx != null && ((ctx.usedPct() != null && ctx.usedPct() >= CAPACITY_WARN_PCT)
                            || (ctx.forecastFromHistory() && ctx.daysUntilFull() != null && ctx.daysUntilFull() <= CAPACITY_WARN_DAYS));
                    // Yasal tutmada kısaltma hold kalkana dek hiçbir satır silmez → yalnız bilgi
                    String sev = hold ? "info" : share >= SHORTEN_WARN_SHARE || pressure ? "warn" : "info";
                    Map<String, Object> prm = new LinkedHashMap<>();
                    prm.put("share_pct", round1(share * 100));
                    prm.put("table_bytes", t.totalBytes());
                    if (t.equilibriumBytes() != null) {
                        prm.put("equilibrium_bytes", Math.round(t.equilibriumBytes()));
                        prm.put("equilibrium_after_bytes", Math.round(t.equilibriumBytes() * suggested / eff));
                    }
                    prm.put("compliance", keyNeedsCompliance(p));
                    List<String> affected = new ArrayList<>();
                    for (RetentionPolicy x : keySiblings(p)) if (!affected.contains(x.table())) affected.add(x.table());
                    prm.put("affected_count", affected.size());
                    prm.put("affected_tables", affected.size() > 20 ? affected.subList(0, 20) : affected);
                    prm.put("hold", hold);
                    out.add(new Finding("SHORTEN_CANDIDATE", sev, t.name(), p.id(), p.settingKey(), eff, suggested, floor,
                            rows, bytes, method, p.dataClass().name(), "apply_retention", prm));
                }
            }
        }

        // ── Henüz dolmadı ──
        if (ageRule && eff != null && eff > 0 && t.oldestAgeDays() != null && t.equilibriumBytes() != null
                && !DbGrowthForecast.saturated(t.oldestAgeDays(), eff) && t.totalBytes() >= NOT_SATURATED_MIN_BYTES) {
            Map<String, Object> prm = new LinkedHashMap<>();
            prm.put("oldest_age_days", round1(t.oldestAgeDays()));
            prm.put("retention_days", eff);
            prm.put("saturation_pct", round1(Math.min(100, t.oldestAgeDays() / eff * 100)));
            prm.put("equilibrium_bytes", Math.round(t.equilibriumBytes()));
            prm.put("days_to_saturate", Math.max(0, (int) Math.ceil(eff - t.oldestAgeDays())));
            prm.put("table_bytes", t.totalBytes());
            boolean overCapacity = ctx != null && ctx.capacityBytes() > 0
                    && ctx.dbBytes() - t.totalBytes() + t.equilibriumBytes() >= ctx.capacityBytes() * CAPACITY_CRITICAL_PCT / 100;
            out.add(new Finding("NOT_SATURATED", overCapacity ? "warn" : "info", t.name(), p.id(), p.settingKey(), eff, null,
                    p.minDays(), null, null, null, p.dataClass().name(), p.configurable() ? "open_retention" : "none", prm));
        }

        // ── Kural dışı büyüme ──
        boolean grew = t.growth30Bytes() != null && t.growth30Bytes() >= GROWTH_MIN_30D_BYTES && t.totalBytes() >= GROWTH_MIN_TABLE_BYTES;
        if (t.policies().isEmpty()) {
            if (grew && t.growth30Bytes() >= NO_RULE_GROWTH_RATIO * t.totalBytes()) {
                out.add(new Finding("NO_RULE", "warn", t.name(), null, null, null, null, null, null, null, null, null, "code",
                        Map.of("growth_30d_bytes", t.growth30Bytes(), "table_bytes", t.totalBytes())));
            }
        } else if (t.policies().stream().noneMatch(RetentionPolicy::deletes)) {
            if (grew && t.growth30Bytes() >= BOUNDED_GROWTH_RATIO * t.totalBytes()) {
                RetentionPolicy first = t.policies().get(0);
                out.add(new Finding("BOUNDED_GROWING", "warn", t.name(), first.id(), null, null, null, null, null, null, null,
                        first.dataClass().name(), "code", Map.of("growth_30d_bytes", t.growth30Bytes(), "table_bytes", t.totalBytes(),
                        "mode", first.mode().name())));
            }
        }

        // ── Temizlik geride ── (yalnız düz yaş kuralı — koruyucu WHERE'li kurallar eski satırı BİLEREK tutar)
        if (ageRule && !hold && eff != null && eff > 0 && "{t}".equals(p.where())) {
            boolean behind = t.oldestAgeDays() != null && t.oldestAgeDays() > eff + BEHIND_SLACK_DAYS;
            if (behind || t.lastRunFailed()) {
                Map<String, Object> prm = new LinkedHashMap<>();
                prm.put("oldest_age_days", t.oldestAgeDays() == null ? null : round1(t.oldestAgeDays()));
                prm.put("retention_days", eff);
                prm.put("last_run_failed", t.lastRunFailed());
                prm.put("table_bytes", t.totalBytes());
                out.add(new Finding("CLEANUP_BEHIND", "warn", t.name(), p.id(), p.settingKey(), eff, null, p.minDays(), null, null,
                        null, p.dataClass().name(), "open_retention", prm));
            }
        }

        // ── Ölü satır ──
        if (t.deadRows() != null && t.liveRows() != null && t.deadRows() >= DEAD_MIN_ROWS) {
            double pct = 100.0 * t.deadRows() / Math.max(1, t.deadRows() + t.liveRows());
            if (pct >= DEAD_PCT) {
                out.add(new Finding("DEAD_TUPLES", pct >= DEAD_WARN_PCT ? "warn" : "info", t.name(), null, null, null, null, null,
                        null, null, null, null, "dba", Map.of("dead_pct", round1(pct), "dead_rows", t.deadRows(),
                        "table_bytes", t.totalBytes())));
            }
        }

        // ── TOAST ağırlıklı ──
        Long toast = t.toastBytes();
        if (toast != null && toast >= TOAST_MIN_BYTES && t.totalBytes() > 0 && (double) toast / t.totalBytes() >= TOAST_SHARE) {
            boolean configurable = p != null && p.configurable();
            out.add(new Finding("TOAST_HEAVY", "info", t.name(), p == null ? null : p.id(), configurable ? p.settingKey() : null,
                    null, null, null, null, null, null, p == null ? null : p.dataClass().name(),
                    configurable ? "open_retention" : "code",
                    Map.of("toast_bytes", toast, "toast_pct", round1(100.0 * toast / t.totalBytes()), "table_bytes", t.totalBytes())));
        }
        return out;
    }

    /**
     * Önerilen gün: {@link #NICE_DAYS} içinden tabanın üstünde, mevcudun yarısına eşit ya da küçük EN BÜYÜK değer; yoksa
     * mevcudun altındaki en büyük değer; o da yoksa null (taban zaten çok yakın).
     */
    public static Integer suggestDays(int current, int minDays) {
        Integer half = null, below = null;
        for (int d : NICE_DAYS) {
            if (d < minDays || d >= current) continue;
            below = d;
            if (d <= current / 2.0) half = d;
        }
        return half != null ? half : below;
    }

    /**
     * Tekdüze varsayımla yeni kesimin silerek götüreceği oran: kapsanan süre = min(en eski yaş, mevcut gün); yeni süreden
     * eski kısım / kapsanan süre. En eski bilinmiyorsa tablo dolmuş sayılır.
     */
    public static double uniformPurgeFraction(Double oldestAgeDays, int currentDays, int suggestedDays) {
        double covered = oldestAgeDays == null ? currentDays : Math.min(oldestAgeDays, currentDays);
        if (covered <= 0 || covered <= suggestedDays) return 0;
        return clamp01((covered - suggestedDays) / covered);
    }

    // ── Analiz bulguları (DbHealthAnalysisService) ──────────────────────────────────────────────────────

    /** Şişmiş dizin: gece bakımının kapsadığı tabloda bilgi, değilse DBA işi. */
    public static Finding indexBloat(String table, String index, long bytes, long expectedBytes, boolean maintainedNightly) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("index", index);
        p.put("index_bytes", bytes);
        p.put("expected_bytes", expectedBytes);
        p.put("maintained", maintainedNightly);
        p.put("table_bytes", bytes);
        return new Finding("INDEX_BLOAT", maintainedNightly ? "info" : "warn", table, null, null, null, null, null, null,
                Math.max(0, bytes - expectedBytes), "estimate", null, maintainedNightly ? "none" : "dba", p);
    }

    /** Hiç taranmamış büyük dizin (istatistik sıfırlamasından beri). */
    public static Finding unusedIndex(String table, String index, long bytes) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("index", index);
        p.put("index_bytes", bytes);
        p.put("table_bytes", bytes);
        return new Finding("UNUSED_INDEX", "info", table, null, null, null, null, null, null, bytes, "estimate", null, "dba", p);
    }

    /** Son 24 saatte en çok satır üreten izleme. */
    public static Finding hotMonitor(String table, String type, long monitorId, long rows24h) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("monitor_id", monitorId);
        p.put("rows_24h", rows24h);
        return new Finding("HOT_MONITOR", "info", table, null, null, null, null, null, rows24h, null, null, null, "none", p);
    }

    /** Ölü satır bulgusunun analiz sürümü — son otomatik vacuum zamanıyla. */
    public static Finding deadTuples(String table, long live, long dead, String lastAutovacuum, long tableBytes) {
        if (dead < DEAD_MIN_ROWS) return null;
        double pct = 100.0 * dead / Math.max(1, dead + live);
        if (pct < DEAD_PCT) return null;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dead_pct", round1(pct));
        p.put("dead_rows", dead);
        p.put("last_autovacuum", lastAutovacuum);
        p.put("table_bytes", tableBytes);
        return new Finding("DEAD_TUPLES", pct >= DEAD_WARN_PCT ? "warn" : "info", table, null, null, null, null, null, null, null,
                null, null, "dba", p);
    }

    static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
