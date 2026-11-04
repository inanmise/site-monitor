package com.sitemonitor.service.dbquery;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Akıllı kuralların (olağan dışı değer {@code ANOMALY}, donmuş değer {@code FROZEN} — dbq2 D) geçmiş ölçüleri. Çalıştırma
 * başına, bu türden kural varsa TEK sınırlı sorgu ({@value #MAX_ROWS} satır): zamanlanmış ve sorgusu çalışmış satırlar (elle /
 * teyit / kurtarma satırları bant dışıdır — elle kontrol tabanı bozmaz), yalnız {@code id, checked_at, sql_hash,
 * rule_results_json} (satır verisi kontrol satırında hiç yok).
 *
 * <ul>
 *   <li><b>Yakın geçmiş</b> (mevsimsiz anomali, donmuş değer): başvuru anından önceki en yeni N satır (N = en büyük pencere /
 *       gereken dizi, ≤ {@value #MAX_RECENT}).</li>
 *   <li><b>Mevsimsel</b> (günlük / haftalık anomali): önceki günlerin / haftaların AYNI saatine ± tolerans (min(aralığın
 *       yarısı, 30 dk) — değişim kuralının mevsimsel tabanıyla aynı kural) aralıkları; her aralık {@code (monitor_id,
 *       checked_at)} dizininde bir aralık taraması.</li>
 * </ul>
 * İkisi birlikte gerekiyorsa tek ifadede {@code UNION ALL} (her dal kendi sınırıyla). Başvuru anı: zamanlanmış çalıştırmada
 * şimdi (sınır yok — bu çalıştırmanın satırı henüz yazılmadı), teyit / kurtarmada zinciri başlatan satır, elle kontrolde son
 * zamanlanmış satır — o satır ve sonrası tabana GİRMEZ (açık alarmı tetikleyen değer kendi tabanını şişirip alarmı sahte
 * kapatmasın; teyit zinciri boyunca taban sabit).
 *
 * <p>Taban sürekliliği: geçmiş yeniden eskiye yürünürken sorgu özeti farklı ({@code sql_hash}) ya da kuralın saklı sonucunun
 * ölçüm tanımı farklı ({@link DbQueryEvaluator#sameMeasureDefinition}) ilk satırda DURULUR (taban sıfırlandı); kuralın saklı
 * sonucu olmayan ilk satırda da durulur (kural o tarihten sonra eklendi). Saf hesap ({@link #plan}, {@link #histories})
 * veritabanısız sınanır; {@link #read} JDBC.
 */
public final class DbQueryHistory {

    /** Tek okumanın satır üst sınırı (sözleşme D). */
    public static final int MAX_ROWS = 500;
    /** Yakın geçmiş dalının üst sınırı (pencere ≤ 200, donma dizisi ≤ 99). */
    public static final int MAX_RECENT = 200;
    static final long DAY_MS = 86_400_000L, WEEK_MS = 7 * DAY_MS;
    /** Mevsimsel tolerans üst sınırı (değişim kuralının mevsimsel tabanıyla aynı). */
    static final long SEASONAL_TOLERANCE_MAX_MS = 30L * 60 * 1000;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String COLUMNS = "id, checked_at, sql_hash, rule_results_json";

    private DbQueryHistory() {}

    /**
     * Okuma planı.
     *
     * @param recentLimit   yakın geçmiş dalının satır sayısı (0 = yok)
     * @param ranges        mevsimsel hedef aralıkları {@code [lo, hi]} (epoch ms; yeniden eskiye) — boş = yok
     * @param seasonalLimit mevsimsel dalın satır sınırı
     * @param ref           başvuru anı (mevsimsel hedefler buna göre)
     * @param toleranceMs   mevsimsel tolerans
     */
    public record Plan(int recentLimit, List<long[]> ranges, int seasonalLimit, Instant ref, long toleranceMs) {
        public boolean empty() { return recentLimit <= 0 && (ranges == null || ranges.isEmpty()); }
    }

    /** Okunan bir kontrol satırının izdüşümü: kural kimliği → saklı sonuç. */
    public record Row(long id, Instant at, String sqlHash, Map<String, DbQueryEvaluator.RuleResult> results) {}

    /** Mevsimsel tolerans: min(aralığın yarısı, 30 dk); aralık en az 60 sn, bilinmiyorsa 300 sn. */
    public static long tolerance(Integer intervalSec) {
        long intervalMs = Math.max(60_000L, (intervalSec == null || intervalSec <= 0 ? 300L : intervalSec) * 1000L);
        return Math.min(SEASONAL_TOLERANCE_MAX_MS, intervalMs / 2);
    }

    /** Mevsimsel hedefin gün sayısı ({@code k}. örnek): günlükte k gün, haftalıkta 7k gün önce; mevsimsizse 0. */
    static int seasonDays(String season, int k) {
        if (DbQueryRules.SEASON_DAILY.equals(season)) return k;
        if (DbQueryRules.SEASON_WEEKLY.equals(season)) return 7 * k;
        return 0;
    }

    /** Kuralların okuma planı; akıllı kural yoksa boş plan. */
    public static Plan plan(List<DbQueryRules.Rule> rules, Instant ref, Integer intervalSec) {
        Instant at = ref == null ? Instant.now() : ref;
        long tol = tolerance(intervalSec);
        int recent = 0;
        TreeSet<Integer> days = new TreeSet<>();
        for (DbQueryRules.Rule r : rules == null ? List.<DbQueryRules.Rule>of() : rules) {
            if (r == null) continue;
            if (DbQueryRules.KIND_FROZEN.equals(r.kind())) {
                recent = Math.max(recent, DbQueryRules.frozenOf(r).runsMax() - 1);
            } else if (DbQueryRules.KIND_ANOMALY.equals(r.kind())) {
                DbQueryRules.Anomaly a = DbQueryRules.anomalyOf(r);
                if (DbQueryRules.SEASON_NONE.equals(a.season())) recent = Math.max(recent, a.window());
                else for (int k = 1; k <= a.window(); k++) days.add(seasonDays(a.season(), k));
            }
        }
        recent = Math.min(MAX_RECENT, recent);
        List<long[]> ranges = new ArrayList<>(days.size());
        for (int d : days) {   // artan gün = yeniden eskiye
            long target = at.toEpochMilli() - d * DAY_MS;
            // SQL ön süzgeci saniye kesinliğindedir — sınırlar 1 sn geniş; asıl tolerans Java'da (histories) uygulanır
            ranges.add(new long[]{target - tol - 1_000, target + tol + 1_000});
        }
        return new Plan(recent, List.copyOf(ranges), ranges.isEmpty() ? 0 : MAX_ROWS - recent, at, tol);
    }

    /**
     * Planın TEK sorgusu (JDBC). {@code beforeAt} null = üst sınır yok (zamanlanmış çalıştırma); değilse yalnız o satırdan
     * ({@code beforeAt} + {@code beforeId}) ÖNCEKİ satırlar. Sonuç kimliğe göre tekil, yeniden eskiye sıralı.
     */
    public static List<Row> read(JdbcTemplate jdbc, long monitorId, String beforeAt, Long beforeId, Plan plan) {
        if (plan == null || plan.empty()) return List.of();
        Objects.requireNonNull(jdbc, "jdbc");
        List<Object> args = new ArrayList<>();
        String recentSql = null, seasonalSql = null;
        if (plan.recentLimit() > 0) {
            recentSql = "SELECT " + COLUMNS + " " + where(monitorId, beforeAt, beforeId, args)
                    + " ORDER BY checked_at DESC, id DESC LIMIT ?";
            args.add(plan.recentLimit());
        }
        if (!plan.ranges().isEmpty()) {
            StringBuilder sb = new StringBuilder("SELECT ").append(COLUMNS).append(' ')
                    .append(where(monitorId, beforeAt, beforeId, args)).append(" AND (");
            for (int i = 0; i < plan.ranges().size(); i++) {
                long[] rg = plan.ranges().get(i);
                if (i > 0) sb.append(" OR ");
                sb.append("(checked_at >= ? AND checked_at <= ?)");
                args.add(ISO.format(Instant.ofEpochMilli(rg[0])));
                args.add(ISO.format(Instant.ofEpochMilli(rg[1])));
            }
            sb.append(") ORDER BY checked_at DESC, id DESC LIMIT ?");
            args.add(plan.seasonalLimit());
            seasonalSql = sb.toString();
        }
        String sql = recentSql != null && seasonalSql != null
                ? "SELECT * FROM (" + recentSql + ") r UNION ALL SELECT * FROM (" + seasonalSql + ") s"
                : recentSql != null ? recentSql : seasonalSql;
        List<Row> raw = jdbc.query(sql, (rs, n) -> new Row(rs.getLong(1), DbQueryCheckerService.parseIso(rs.getString(2)),
                rs.getString(3), byId(rs.getString(4))), args.toArray());
        Map<Long, Row> unique = new LinkedHashMap<>();
        for (Row r : raw) if (r != null && r.at() != null) unique.putIfAbsent(r.id(), r);
        List<Row> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(Row::at).thenComparingLong(Row::id).reversed());
        return out;
    }

    private static String where(long monitorId, String beforeAt, Long beforeId, List<Object> args) {
        args.add(monitorId);
        String w = "FROM dbquery_checks WHERE monitor_id = ? AND ok = TRUE AND (manual IS NULL OR manual = FALSE) "
                + "AND (recheck IS NULL OR recheck = FALSE)";
        if (beforeAt != null) {
            w += " AND (checked_at < ? OR (checked_at = ? AND id < ?))";
            args.add(beforeAt);
            args.add(beforeAt);
            args.add(beforeId == null ? -1L : beforeId);
        }
        return w;
    }

    private static Map<String, DbQueryEvaluator.RuleResult> byId(String json) {
        Map<String, DbQueryEvaluator.RuleResult> out = new HashMap<>();
        for (DbQueryEvaluator.RuleResult r : DbQueryEvaluator.fromJson(json)) if (r.id() != null) out.putIfAbsent(r.id(), r);
        return out;
    }

    // ── Kural başına geçmiş (saf) ────────────────────────────────────────────

    /** Saklı satırın bu kural için durumu. */
    private enum Slot { VALUE, NO_VALUE, MISSING, RESET }

    private static Slot slot(Row row, DbQueryRules.Rule r, String sqlHash) {
        if (row.sqlHash() != null && sqlHash != null && !row.sqlHash().equals(sqlHash)) return Slot.RESET;
        DbQueryEvaluator.RuleResult sr = row.results() == null ? null : row.results().get(r.id());
        if (sr == null) return Slot.MISSING;
        if (!DbQueryEvaluator.sameMeasureDefinition(sr, r)) return Slot.RESET;
        return sr.measure() == null || !Double.isFinite(sr.measure()) ? Slot.NO_VALUE : Slot.VALUE;
    }

    /**
     * Akıllı kuralların geçmişi ({@code rowsDesc}: {@link #read} sonucu, yeniden eskiye). Mevsimsiz anomali: en yeni
     * {@code window} satırın değerleri (değeri olmayan satır örnek değildir, pencereyi yine doldurur); donmuş değer: en yeni
     * {@code runsMax − 1} satırın ARDIŞIK değerleri (değeri olmayan satır diziyi kesin kırar — {@code ended}); mevsimsel
     * anomali: her hedefin (k = 1…window) tolerans içindeki EN YAKIN satırı (hedefte satır yoksa atlanır). Akıllı olmayan
     * kurallar sonuçta yoktur.
     */
    public static Map<String, DbQueryEvaluator.History> histories(List<DbQueryRules.Rule> rules, List<Row> rowsDesc,
                                                                  Plan plan, String sqlHash) {
        Map<String, DbQueryEvaluator.History> out = new HashMap<>();
        List<Row> rows = rowsDesc == null ? List.of() : rowsDesc;
        for (DbQueryRules.Rule r : rules == null ? List.<DbQueryRules.Rule>of() : rules) {
            if (r == null || r.id() == null || !DbQueryRules.isSmart(r.kind())) continue;
            if (DbQueryRules.KIND_FROZEN.equals(r.kind())) {
                out.put(r.id(), frozen(r, rows, sqlHash));
                continue;
            }
            DbQueryRules.Anomaly a = DbQueryRules.anomalyOf(r);
            out.put(r.id(), DbQueryRules.SEASON_NONE.equals(a.season())
                    ? recentAnomaly(r, a.window(), rows, sqlHash)
                    : seasonalAnomaly(r, a, rows, plan, sqlHash));
        }
        return out;
    }

    private static DbQueryEvaluator.History recentAnomaly(DbQueryRules.Rule r, int window, List<Row> rows, String sqlHash) {
        List<Double> values = new ArrayList<>();
        boolean reset = false;
        for (int i = 0; i < rows.size() && i < window; i++) {
            Row row = rows.get(i);
            Slot s = slot(row, r, sqlHash);
            if (s == Slot.RESET) { reset = true; break; }
            if (s == Slot.MISSING) break;
            if (s == Slot.VALUE) values.add(row.results().get(r.id()).measure());
        }
        return new DbQueryEvaluator.History(values, false, reset);
    }

    private static DbQueryEvaluator.History frozen(DbQueryRules.Rule r, List<Row> rows, String sqlHash) {
        int needed = DbQueryRules.frozenOf(r).runsMax() - 1;
        List<Double> values = new ArrayList<>();
        boolean ended = false, reset = false;
        for (int i = 0; i < rows.size() && values.size() < needed; i++) {
            Row row = rows.get(i);
            Slot s = slot(row, r, sqlHash);
            if (s == Slot.RESET) { reset = true; break; }
            if (s == Slot.MISSING) break;
            if (s == Slot.NO_VALUE) { ended = true; break; }
            values.add(row.results().get(r.id()).measure());
        }
        return new DbQueryEvaluator.History(values, ended, reset);
    }

    private static DbQueryEvaluator.History seasonalAnomaly(DbQueryRules.Rule r, DbQueryRules.Anomaly a, List<Row> rows,
                                                            Plan plan, String sqlHash) {
        List<Double> values = new ArrayList<>();
        boolean reset = false;
        if (plan == null || plan.ref() == null) return new DbQueryEvaluator.History(values, false, false);
        long ref = plan.ref().toEpochMilli(), tol = plan.toleranceMs();
        for (int k = 1; k <= a.window(); k++) {
            long target = ref - seasonDays(a.season(), k) * DAY_MS;
            Row best = null;
            long bestGap = Long.MAX_VALUE;
            for (Row row : rows) {
                long gap = Math.abs(row.at().toEpochMilli() - target);
                if (gap <= tol && gap < bestGap) { best = row; bestGap = gap; }
            }
            if (best == null) continue;   // o gün / hafta çalıştırma yok (ör. tatil, hafta sonu) — atlanır
            Slot s = slot(best, r, sqlHash);
            if (s == Slot.RESET) { reset = true; break; }
            if (s == Slot.MISSING) break;
            if (s == Slot.VALUE) values.add(best.results().get(r.id()).measure());
        }
        return new DbQueryEvaluator.History(values, false, reset);
    }
}
