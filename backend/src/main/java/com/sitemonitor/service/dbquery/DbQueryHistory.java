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
 * başına, bu türden kural varsa TEK sınırlı sorgu (en çok {@value #MAX_ROWS} satır, kendi {@value #QUERY_TIMEOUT_SECONDS} sn
 * sorgu zaman aşımıyla — {@link #timed}): zamanlanmış ve sorgusu çalışmış satırlar (elle / teyit / kurtarma satırları bant
 * dışıdır — elle kontrol tabanı bozmaz), yalnız {@code id, checked_at, sql_hash, rule_results_json} (satır verisi kontrol
 * satırında hiç yok).
 *
 * <ul>
 *   <li><b>Yakın geçmiş</b> (mevsimsiz anomali, donmuş değer): başvuru anından önceki en yeni N satır (anomali: pencerenin
 *       İKİ katı — ihlal satırları tabandan dışlanır, Y1; donma: gereken dizi; ≤ {@value #MAX_RECENT}).</li>
 *   <li><b>Mevsimsel</b> (günlük / haftalık anomali): önceki günlerin / haftaların AYNI saatine ± tolerans (min(aralığın
 *       yarısı, 30 dk) — değişim kuralının mevsimsel tabanıyla aynı kural) aralıkları (pencerenin iki katı hedef, en çok
 *       {@value #MAX_SEASONAL_DAYS} gün geriye); her aralık {@code (monitor_id, checked_at)} dizininde bir aralık taraması. Dal
 *       tavanı ({@value #MAX_SEASONAL} satır) yeniden eskiye uygulanır: kesilen EN ESKİ hedeflerdir; tavana değildiyse en eski
 *       okunan satırdan eski hedefler "okunmadı" sayılır ({@link Read#seasonalCutoff}).</li>
 * </ul>
 * İkisi birlikte gerekiyorsa tek ifadede {@code UNION ALL} (her dal kendi sınırıyla). Başvuru anı: zamanlanmış çalıştırmada
 * şimdi (sınır yok — bu çalıştırmanın satırı henüz yazılmadı), teyit / kurtarmada zinciri başlatan satır, elle kontrolde son
 * zamanlanmış satır — o satır ve sonrası tabana GİRMEZ (açık alarmı tetikleyen değer kendi tabanını şişirip alarmı sahte
 * kapatmasın; teyit zinciri boyunca taban sabit).
 *
 * <p>Taban sürekliliği: geçmiş yeniden eskiye yürünürken sorgu özeti farklı ({@code sql_hash}) ya da kuralın saklı sonucunun
 * ölçüm tanımı farklı ({@link DbQueryEvaluator#sameMeasureDefinition}) ilk satırda DURULUR (taban sıfırlandı). Kuralın saklı
 * sonucu olmayan satır (eski sürüm pod'unun satırı ya da kural o tarihten sonra eklendi) ATLANIR — tabanı sıfırlamaz (D3);
 * mevsimsiz anomalide yürüyüşün ESKİ ucundaki kesintisiz bu tür satırlar kuralın geçmişinin başlangıcıdır — geçmiş tam
 * sayılır, "taban kuruluyor" (2026-11-05 hata avı Y1; {@code recentAnomaly}). Olağan dışı değerde her satır ihlal bayrağını
 * taşır (saklı sonuç: tür ANOMALY, değerlendirilmiş, geçmemiş — Y1). Saf hesap
 * ({@link #plan}, {@link #histories}) veritabanısız sınanır; {@link #read} JDBC.
 */
public final class DbQueryHistory {

    /** Yakın geçmiş dalının üst sınırı (pencere ≤ 200 → iki katı 400; donma dizisi ≤ 99). */
    public static final int MAX_RECENT = 400;
    /** Mevsimsel dalın satır üst sınırı (hedef başına ~1–2 satır; yeniden eskiye — en eski hedefler kesilir). */
    public static final int MAX_SEASONAL = 400;
    /** Tek okumanın satır üst sınırı (iki dalın toplamı). */
    public static final int MAX_ROWS = MAX_RECENT + MAX_SEASONAL;
    /** Mevsimsel hedeflerin en çok geriye gittiği gün (saklama süresinden eski hedef zaten boştur). */
    public static final int MAX_SEASONAL_DAYS = 800;
    /** Geçmiş okumasının sorgu zaman aşımı (sn) — paylaşılan şablonun 30 dk soket süresini beklemez (D6). */
    public static final int QUERY_TIMEOUT_SECONDS = 15;
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

    /**
     * Okuma sonucu.
     *
     * @param rows           kimliğe göre tekil, yeniden eskiye satırlar
     * @param seasonalCutoff mevsimsel dal tavana değdiyse o dalın en eski satırının anı — bundan eski (ya da bu anı içeren)
     *                       hedefler okunmamış sayılır; tavana değmediyse null
     */
    public record Read(List<Row> rows, Instant seasonalCutoff) {}

    /** Sorgu zaman aşımlı şablon (aynı veri kaynağı; çeviri ayarı korunur) — {@value #QUERY_TIMEOUT_SECONDS} sn. */
    public static JdbcTemplate timed(JdbcTemplate base) {
        if (base == null || base.getDataSource() == null || base.getQueryTimeout() > 0) return base;
        JdbcTemplate t = new JdbcTemplate(base.getDataSource());
        t.setExceptionTranslator(base.getExceptionTranslator());
        t.setFetchSize(base.getFetchSize());
        t.setMaxRows(base.getMaxRows());
        t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return t;
    }

    /** Olağan dışı değerin yakın geçmiş satır ihtiyacı: pencerenin iki katı (ihlal dizisi + taban — Y1). */
    static int anomalyRecentNeed(DbQueryRules.Anomaly a) {
        return Math.min(MAX_RECENT, 2 * a.window());
    }

    /** Mevsimsel hedef sayısı: pencerenin iki katı, {@value #MAX_SEASONAL_DAYS} günü aşmayan. */
    static int seasonalTargets(DbQueryRules.Anomaly a) {
        int k = 2 * a.window();
        while (k > 0 && seasonDays(a.season(), k) > MAX_SEASONAL_DAYS) k--;
        return k;
    }

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
                if (DbQueryRules.SEASON_NONE.equals(a.season())) recent = Math.max(recent, anomalyRecentNeed(a));
                else for (int k = 1, t = seasonalTargets(a); k <= t; k++) days.add(seasonDays(a.season(), k));
            }
        }
        recent = Math.min(MAX_RECENT, recent);
        List<long[]> ranges = new ArrayList<>(days.size());
        for (int d : days) {   // artan gün = yeniden eskiye
            long target = at.toEpochMilli() - d * DAY_MS;
            // SQL ön süzgeci saniye kesinliğindedir — sınırlar 1 sn geniş; asıl tolerans Java'da (histories) uygulanır
            ranges.add(new long[]{target - tol - 1_000, target + tol + 1_000});
        }
        return new Plan(recent, List.copyOf(ranges), ranges.isEmpty() ? 0 : MAX_SEASONAL, at, tol);
    }

    /** Planın TEK sorgusu — yalnız satırlar ({@link #readAll}). */
    public static List<Row> read(JdbcTemplate jdbc, long monitorId, String beforeAt, Long beforeId, Plan plan) {
        return readAll(jdbc, monitorId, beforeAt, beforeId, plan).rows();
    }

    /**
     * Planın TEK sorgusu (JDBC, {@link #timed} ile zaman aşımlı). {@code beforeAt} null = üst sınır yok (zamanlanmış
     * çalıştırma); değilse yalnız o satırdan ({@code beforeAt} + {@code beforeId}) ÖNCEKİ satırlar. Sonuç kimliğe göre tekil,
     * yeniden eskiye sıralı; mevsimsel dal tavana değdiyse kesim anı ({@link Read#seasonalCutoff}).
     */
    public static Read readAll(JdbcTemplate jdbc, long monitorId, String beforeAt, Long beforeId, Plan plan) {
        if (plan == null || plan.empty()) return new Read(List.of(), null);
        Objects.requireNonNull(jdbc, "jdbc");
        List<Object> args = new ArrayList<>();
        String recentSql = null, seasonalSql = null;
        if (plan.recentLimit() > 0) {
            recentSql = "SELECT " + COLUMNS + ", 0 AS src " + where(monitorId, beforeAt, beforeId, args)
                    + " ORDER BY checked_at DESC, id DESC LIMIT ?";
            args.add(plan.recentLimit());
        }
        if (!plan.ranges().isEmpty()) {
            StringBuilder sb = new StringBuilder("SELECT ").append(COLUMNS).append(", 1 AS src ")
                    .append(where(monitorId, beforeAt, beforeId, args)).append(" AND (");
            for (int i = 0; i < plan.ranges().size(); i++) {
                long[] rg = plan.ranges().get(i);
                if (i > 0) sb.append(" OR ");
                sb.append("(checked_at >= ? AND checked_at <= ?)");
                args.add(ISO.format(Instant.ofEpochMilli(rg[0])));
                args.add(ISO.format(Instant.ofEpochMilli(rg[1])));
            }
            // Yeniden eskiye + sınır: tavan EN ESKİ hedefleri keser (en yeni hedefler eksiksiz kalır — D6)
            sb.append(") ORDER BY checked_at DESC, id DESC LIMIT ?");
            args.add(plan.seasonalLimit());
            seasonalSql = sb.toString();
        }
        String sql = recentSql != null && seasonalSql != null
                ? "SELECT * FROM (" + recentSql + ") r UNION ALL SELECT * FROM (" + seasonalSql + ") s"
                : recentSql != null ? recentSql : seasonalSql;
        int[] seasonalCount = {0};
        Instant[] oldestSeasonal = {null};
        List<Row> raw = timed(jdbc).query(sql, (rs, n) -> {
            Row row = new Row(rs.getLong(1), DbQueryCheckerService.parseIso(rs.getString(2)), rs.getString(3),
                    byId(rs.getString(4)));
            if (rs.getInt(5) == 1) {
                seasonalCount[0]++;
                if (row.at() != null && (oldestSeasonal[0] == null || row.at().isBefore(oldestSeasonal[0])))
                    oldestSeasonal[0] = row.at();
            }
            return row;
        }, args.toArray());
        Map<Long, Row> unique = new LinkedHashMap<>();
        for (Row r : raw) if (r != null && r.at() != null) unique.putIfAbsent(r.id(), r);
        List<Row> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(Row::at).thenComparingLong(Row::id).reversed());
        Instant cutoff = seasonalSql != null && seasonalCount[0] >= plan.seasonalLimit() ? oldestSeasonal[0] : null;
        return new Read(out, cutoff);
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
     * Satırın bu kuraldaki noktası: değer + ihlal bayrağı. İhlal = saklı sonuç bir OLAĞAN DIŞI DEĞER sonucu ve
     * değerlendirilmiş + geçmemiş (aynı parmak izli kolon değeri / donma kuralının eşik kararı bu kuralın ihlali değildir).
     */
    private static DbQueryEvaluator.Point point(Row row, DbQueryRules.Rule r, Slot s) {
        if (s == Slot.NO_VALUE) return new DbQueryEvaluator.Point(null, false);
        DbQueryEvaluator.RuleResult sr = row.results().get(r.id());
        boolean breach = DbQueryRules.KIND_ANOMALY.equals(sr.kind()) && sr.evaluated() && !sr.passed();
        return new DbQueryEvaluator.Point(sr.measure(), breach);
    }

    /** {@link #histories} — mevsimsel kesim anı yok. */
    public static Map<String, DbQueryEvaluator.History> histories(List<DbQueryRules.Rule> rules, List<Row> rowsDesc,
                                                                  Plan plan, String sqlHash) {
        return histories(rules, rowsDesc, plan, sqlHash, null);
    }

    /** {@link #histories} — okuma sonucundan ({@link #readAll}). */
    public static Map<String, DbQueryEvaluator.History> histories(List<DbQueryRules.Rule> rules, Read read, Plan plan,
                                                                  String sqlHash) {
        return histories(rules, read == null ? List.of() : read.rows(), plan, sqlHash,
                read == null ? null : read.seasonalCutoff());
    }

    /**
     * Akıllı kuralların geçmişi ({@code rowsDesc}: {@link #read} sonucu, yeniden eskiye). Mevsimsiz anomali: en yeni satırlar
     * (kuralın ihtiyacı kadar — pencerenin iki katı), her biri değer + ihlal bayrağıyla (taban seçimi
     * {@link DbQueryEvaluator#anomalyBase}); donmuş değer: en yeni {@code runsMax − 1} değerli satırın ARDIŞIK değerleri
     * (değeri olmayan satır diziyi kesin kırar — {@code ended}); mevsimsel anomali: her hedefin (k = 1…2·window) tolerans
     * içindeki EN YAKIN satırı (hedefte satır yoksa atlanır; {@code seasonalCutoff} varsa ondan eski hedefler okunmamış —
     * geçmiş tam değil). Kuralın saklı sonucu olmayan satır atlanır (D3). Akıllı olmayan kurallar sonuçta yoktur.
     */
    public static Map<String, DbQueryEvaluator.History> histories(List<DbQueryRules.Rule> rules, List<Row> rowsDesc,
                                                                  Plan plan, String sqlHash, Instant seasonalCutoff) {
        Map<String, DbQueryEvaluator.History> out = new HashMap<>();
        List<Row> rows = rowsDesc == null ? List.of() : rowsDesc;
        int recentLimit = plan == null ? rows.size() + 1 : plan.recentLimit();
        for (DbQueryRules.Rule r : rules == null ? List.<DbQueryRules.Rule>of() : rules) {
            if (r == null || r.id() == null || !DbQueryRules.isSmart(r.kind())) continue;
            if (DbQueryRules.KIND_FROZEN.equals(r.kind())) {
                out.put(r.id(), frozen(r, rows, recentLimit, sqlHash));
                continue;
            }
            DbQueryRules.Anomaly a = DbQueryRules.anomalyOf(r);
            out.put(r.id(), DbQueryRules.SEASON_NONE.equals(a.season())
                    ? recentAnomaly(r, a, rows, recentLimit, sqlHash)
                    : seasonalAnomaly(r, a, rows, plan, sqlHash, seasonalCutoff));
        }
        return out;
    }

    /**
     * Mevsimsiz anomali: en yeni {@code need} satır (kuralın ihtiyacı). Geçmiş TAM = okuma sınırına değilmedi (bütün geçmiş
     * okundu) ve kuralın yürüdüğü kısım hepsini kapsıyor; sıfırlanan geçmiş de tamdır (karşılaştırılabilir geçmiş orada biter).
     *
     * <p>Kuralın geçmişinin BAŞLANGICI (2026-11-05 hata avı Y1): yürüyüşün ESKİ ucu kesintisiz kural-sonucu-olmayan satırlarla
     * (MISSING) bitiyorsa — son değerli / değersiz noktadan sonra yalnız MISSING satırlar kaldıysa ya da hiç nokta yoksa — kural
     * o satırlardan sonra eklendi (ya da uzun bir eski pod / geri alma penceresi): bu kuralın karşılaştırılabilir geçmişi orada
     * başlar, geçmiş TAMDIR. Aksi hâlde var olan bir izlemeye yeni eklenen kural (≥ okuma sınırı kadar eski satır) "örnek
     * yetersiz" (kanıt eksikliği) sayılıp birkaç turda sahte {@code DB_RULE_UNEVALUABLE} alarmı açardı; doğrusu "taban
     * kuruluyor". ORTADAKİ MISSING satırlar (iki değerli nokta arasında eski pod boşluğu) atlanmaya ve yürüyüş bütçesini
     * harcamaya devam eder (D3) — kural orada vardı, örnek azsa "yetersiz" doğrudur. Eşik denemesi aynı kuralı izler: MISSING
     * satır kayan geçmişe hiç girmez, kuyruk dolmadıkça geçmiş tamdır ({@code DbQueryBacktest}).</p>
     */
    private static DbQueryEvaluator.History recentAnomaly(DbQueryRules.Rule r, DbQueryRules.Anomaly a, List<Row> rows,
                                                          int recentLimit, String sqlHash) {
        int need = anomalyRecentNeed(a);
        List<DbQueryEvaluator.Point> points = new ArrayList<>();
        boolean reset = false;
        int walked = 0, missingTail = 0;   // missingTail: son noktadan sonra (eskiye doğru) art arda MISSING satır sayısı
        for (int i = 0; i < rows.size() && i < need && i < recentLimit; i++) {
            Row row = rows.get(i);
            Slot s = slot(row, r, sqlHash);
            if (s == Slot.RESET) { reset = true; break; }
            walked++;
            if (s == Slot.MISSING) { missingTail++; continue; }   // eski pod satırı / kural sonradan eklendi — atla (D3)
            missingTail = 0;
            points.add(point(row, r, s));
        }
        // Yürüyüşün eski ucu MISSING ile bitti → kuralın geçmişi burada başlıyor (Y1)
        boolean startsHere = !reset && missingTail > 0;
        boolean complete = reset || startsHere || (rows.size() < recentLimit && walked >= rows.size());
        return new DbQueryEvaluator.History(points, false, reset, complete);
    }

    private static DbQueryEvaluator.History frozen(DbQueryRules.Rule r, List<Row> rows, int recentLimit, String sqlHash) {
        int needed = DbQueryRules.frozenOf(r).runsMax() - 1;
        List<DbQueryEvaluator.Point> points = new ArrayList<>();
        boolean ended = false, reset = false;
        for (int i = 0; i < rows.size() && i < recentLimit && points.size() < needed; i++) {
            Row row = rows.get(i);
            Slot s = slot(row, r, sqlHash);
            if (s == Slot.RESET) { reset = true; break; }
            if (s == Slot.MISSING) continue;   // eski pod satırı — diziyi kırmaz, atla (D3)
            if (s == Slot.NO_VALUE) { ended = true; break; }
            points.add(new DbQueryEvaluator.Point(row.results().get(r.id()).measure(), false));
        }
        return new DbQueryEvaluator.History(points, ended, reset, true);
    }

    private static DbQueryEvaluator.History seasonalAnomaly(DbQueryRules.Rule r, DbQueryRules.Anomaly a, List<Row> rows,
                                                            Plan plan, String sqlHash, Instant seasonalCutoff) {
        List<DbQueryEvaluator.Point> points = new ArrayList<>();
        boolean reset = false, complete = true;
        if (plan == null || plan.ref() == null) return new DbQueryEvaluator.History(points, false, false, true);
        long ref = plan.ref().toEpochMilli(), tol = plan.toleranceMs();
        for (int k = 1, t = seasonalTargets(a); k <= t; k++) {
            long target = ref - seasonDays(a.season(), k) * DAY_MS;
            if (seasonalCutoff != null && target - tol <= seasonalCutoff.toEpochMilli()) {
                complete = false;   // tavan bu hedefi (ve eskilerini) kesti — okunmadı
                break;
            }
            Row best = null;
            long bestGap = Long.MAX_VALUE;
            for (Row row : rows) {
                long gap = Math.abs(row.at().toEpochMilli() - target);
                if (gap <= tol && gap < bestGap) { best = row; bestGap = gap; }
            }
            if (best == null) continue;   // o gün / hafta çalıştırma yok (ör. tatil, hafta sonu) — atlanır
            Slot s = slot(best, r, sqlHash);
            if (s == Slot.RESET) { reset = true; break; }
            if (s == Slot.MISSING) continue;   // eski pod satırı — atla (D3)
            points.add(point(best, r, s));
        }
        return new DbQueryEvaluator.History(points, false, reset, complete);
    }
}
