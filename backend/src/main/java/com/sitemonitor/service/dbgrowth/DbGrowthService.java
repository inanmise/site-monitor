package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.RetentionRun;
import com.sitemonitor.model.RetentionRunItem;
import com.sitemonitor.repository.RetentionRunItemRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.dbgrowth.DbGrowthAdvisor.TableFacts;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.Fit;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.Point;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.Projection;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.TableInput;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Veritabanı büyümesi (2026-11-13, kullanıcı isteği: "veritabanı gün gün ne kadar büyüyor, geçmişe göre nereye gider, hızlı
 * büyüyorsa hangi tabloda hangi saklama ayarını yapayım"). Günlük görüntüyü yazar ({@link DbSizeSnapshotJob}), görünümü
 * hesaplar (KPI, seri, tahmin, tablo listesi, öneriler), "kısaltırsam ne silinir" tahminini ve kendi kendini izleme / Grafana
 * için kapasite olgularını verir.
 *
 * <p>Maliyet: görünüm kapsam başına 60 sn bellekte ({@code fresh} en çok 5 sn'de bir yeniden hesaplar); arka plan okuyucular
 * (kendi kendini izleme, aktarıcı) 5 dakikalık hesabı kabul eder. Bir hesap ≤ 4 deyim (geçmiş özeti, son 45 gün, grafik serisi,
 * canlı boyut — 5 dk bellek) + son temizlik koşusu + saklama ayarlarının değişme anları (tek deyim) + soğuk başlangıçta bir
 * histogram okuması. Bellek, kapasite / yasal tutma ayarı (bellek okuması) değişince düşer. Kapsam süzgeci yok: tablo adları
 * sistem bilgisidir, kişi / takım verisi taşımaz.
 */
@Slf4j
@Service
public class DbGrowthService {

    public static final String CAPACITY_KEY = "site.monitor.db.capacity-gb";
    public static final String HISTORY_KEY = "site.monitor.db.size-history.retention-days";
    /** Veritabanı toplamının satır adı. */
    public static final String ALL = "*";
    public static final List<Integer> RANGES = List.of(30, 90, 365);
    public static final int DEFAULT_RANGE = 90;
    /** Grafikte ayrı çizilen en büyük tablo sayısı. */
    public static final int TOP_TABLES = 8;
    /** Kapasite üst sınırı (GiB) — ayar doğrulaması. */
    public static final int MAX_CAPACITY_GB = 1_000_000;

    static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long MEMO_MS = 60_000;
    static final long FRESH_MIN_MS = 5_000;
    /** Arka plan okuyucuların (kendi kendini izleme, aktarıcı) kabul ettiği en eski hesap. */
    public static final long BACKGROUND_MAX_AGE_MS = 300_000;
    static final long LIVE_SIZE_MS = 300_000;
    /** Görüntü işinde en eski kayıt okumalarının toplam bütçesi. */
    static final long OLDEST_BUDGET_MS = 90_000;
    static final long GB = 1024L * 1024L * 1024L;
    /**
     * Son günlük görüntü bu kadar günden eskiyse (İstanbul günü) tahmin BAYAT sayılır: dolma günü hesaplanmaz, kendi kendini
     * izleme yalnız canlı kullanıma bakar, Grafana dolma günü serisi yazılmaz (2026-11-13 inceleme — durmuş görüntü işi bayat
     * çapadan geri sayıp sonunda sahte "≈0 günde dolar" üretiyordu).
     */
    public static final int STALE_SNAPSHOT_DAYS = 3;
    /**
     * 30 günlük büyümenin tabanı için okunan geçmiş (gün): tam −30. / −31. günün görüntüsü yoksa 30 güne en yakın görüntü
     * ({@value #GROWTH_MIN_GAP_DAYS} gün ve üstü) kullanılır, fark 30 güne ölçeklenir (2026-11-13 hata taraması).
     */
    static final int GROWTH_LOOKBACK_DAYS = 45;
    static final int GROWTH_MIN_GAP_DAYS = 28;
    /**
     * Satır başı bayta (toplam ÷ canlı satır) güvenmek için en az canlı satır: çok az satırlı tabloda sayfa / dizin yükü satır
     * başına bölünür (scheduler_lock: 1 satır, 48 KiB) ve brüt ekleme hızıyla çarpılınca yüzlerce MB/gün sahte büyüme çıkıyordu.
     */
    static final long MIN_ROWS_FOR_ROW_BYTES = 1_000;
    static final String SQL_SETTING_CHANGES = "SELECT setting_key, updated_at FROM app_settings WHERE setting_key IN (%s)";

    static final String SQL_EXISTS = "SELECT COUNT(*) FROM db_size_daily WHERE snap_day = ? AND table_name = '*'";
    static final String SQL_INSERT = "INSERT INTO db_size_daily(snap_day, table_name, total_bytes, table_bytes, index_bytes, "
            + "live_rows, dead_rows, ins_total, del_total, oldest_at, writer, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    static final String SQL_HISTORY = "SELECT COUNT(*), MIN(snap_day), MAX(snap_day) FROM db_size_daily WHERE table_name = '*'";
    static final String SQL_RECENT = "SELECT snap_day, table_name, total_bytes, table_bytes, index_bytes, live_rows, dead_rows, "
            + "ins_total, del_total, oldest_at FROM db_size_daily WHERE snap_day >= ? AND snap_day <= ?";
    static final String SQL_SERIES = "SELECT snap_day, table_name, total_bytes FROM db_size_daily WHERE snap_day >= ? AND snap_day <= ? "
            + "AND table_name IN (%s)";
    static final String SQL_LATEST_TABLE = "SELECT total_bytes, live_rows, oldest_at FROM db_size_daily WHERE table_name = ? "
            + "ORDER BY snap_day DESC LIMIT 1";

    private final JdbcTemplate jdbc;
    private final DbSizeSource source;
    private final AppSettingsService settings;
    private final RetentionService retention;
    private RetentionRunRepository runRepo;
    private RetentionRunItemRepository itemRepo;
    private Supplier<Instant> clock = Instant::now;

    /** Görünüm: kapsam → hesap. */
    private final Map<Integer, Computed> memo = new ConcurrentHashMap<>();
    private final Object computeLock = new Object();
    private volatile long lastFreshMs;
    private volatile long liveAtMs = -1;
    private volatile Long liveBytes;

    public DbGrowthService(JdbcTemplate jdbc, DbSizeSource source, AppSettingsService settings, RetentionService retention) {
        this.jdbc = jdbc;
        this.source = source;
        this.settings = settings;
        this.retention = retention;
    }

    @Autowired(required = false) public void setRunRepo(RetentionRunRepository v) { this.runRepo = v; }
    @Autowired(required = false) public void setItemRepo(RetentionRunItemRepository v) { this.itemRepo = v; }

    /** Test: saat. */
    void setClock(Supplier<Instant> c) { this.clock = c; }

    /**
     * Hesap: arayüz görünümü + arka plan olguları + öneri girdileri (analiz yeniden kullanır). {@code capacityGb} / {@code hold}
     * = hesaplandığı andaki ayar değerleri — bellek yalnız bunlar CANLI ayarla aynıyken geçerlidir (ayarı başka bir pod
     * kaydettiyse bu pod'un belleği 10 sn'lik ayar tazelemesiyle düşer; 2026-11-13 hata taraması).
     */
    record Computed(long atMs, Map<String, Object> view, CapacityFacts facts, List<TableFacts> tableFacts,
                    DbGrowthAdvisor.Context ctx, int capacityGb, boolean hold) {}

    /**
     * Kapasite olguları (kendi kendini izleme, aktarıcı). {@code supported} FALSE = PostgreSQL değil. {@code capacityBytes} ≤ 0
     * = kapasite bilinmiyor. {@code daysUntilFull} yalnız geçmişe dayalı tahminde ({@code historyPoints ≥ 7}) dolu; null =
     * bilinmiyor ya da 10 yıl içinde dolmuyor ({@code fillsWithinHorizon} ayırır).
     */
    public record CapacityFacts(Boolean supported, long capacityBytes, Long usedBytes, Double usedPct, Integer daysUntilFull,
                                boolean fillsWithinHorizon, int historyPoints, String lastSnapshotDay, Integer snapshotAgeDays) {
        /** Eski biçim (görüntü yaşı bilinmiyor). */
        public CapacityFacts(Boolean supported, long capacityBytes, Long usedBytes, Double usedPct, Integer daysUntilFull,
                             boolean fillsWithinHorizon, int historyPoints, String lastSnapshotDay) {
            this(supported, capacityBytes, usedBytes, usedPct, daysUntilFull, fillsWithinHorizon, historyPoints, lastSnapshotDay, null);
        }

        /** Son görüntü {@link #STALE_SNAPSHOT_DAYS} günden eski mi (tahmin kullanılmaz). */
        public boolean forecastStale() {
            return snapshotAgeDays != null && snapshotAgeDays > STALE_SNAPSHOT_DAYS;
        }
    }

    // ── Ayar ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Kapasite (GiB, 1024³ bayt); 0 / geçersiz = bilinmiyor. */
    public int capacityGb() {
        int v = settings.getInt(CAPACITY_KEY, 0);
        return v > 0 && v <= MAX_CAPACITY_GB ? v : 0;
    }

    /** Görünümü ve olguları düşürür (kapasite kaydı, yeni görüntü). */
    public void invalidate() {
        memo.clear();
    }

    // ── Günlük görüntü ────────────────────────────────────────────────────────────────────────────────────

    /** Bugünün (İstanbul) veritabanı satırı yazılmış mı — tek küçük sorgu. */
    public boolean snapshotWritten(Instant now) {
        Integer n = jdbc.queryForObject(SQL_EXISTS, Integer.class, LocalDate.ofInstant(now, IST).toString());
        return n != null && n > 0;
    }

    /** PostgreSQL mi (TRUE / FALSE; okunamadı → null). */
    public Boolean supported() {
        return source.supported();
    }

    /**
     * Bugünün görüntüsünü yazar: tablo satırları ÖNCE, veritabanı ({@code '*'}) satırı EN SONDA — yarıda kalırsa gün "yazıldı"
     * sayılmaz, sonraki tık eksikleri {@code ON CONFLICT DO NOTHING} ile tamamlar. Yaş kuralı olan tablolarda en eski kayıt
     * zaman aşımlı MIN ile (toplam {@value #OLDEST_BUDGET_MS} ms bütçe; bütçe biterse kalanlar null). Dönüş: satır sayısı.
     */
    public int writeSnapshot(Instant now) {
        return writeSnapshot(now, () -> false);
    }

    /**
     * {@link #writeSnapshot(Instant)} + kapanış denetimi: {@code stopping} her tablodan önce sorulur; kapanış başladıysa HİÇBİR
     * satır yazılmadan çıkılır (0) — sonraki tık günü yeniden dener (2026-11-13 inceleme).
     */
    public int writeSnapshot(Instant now, java.util.function.BooleanSupplier stopping) {
        if (!Boolean.TRUE.equals(source.supported())) return 0;
        String day = LocalDate.ofInstant(now, IST).toString();
        String created = ISO.format(now);
        String writer = SchedulerService.instanceId();
        long dbBytes = source.databaseBytes();
        List<DbSizeSource.TableSize> tables = source.tables();
        long deadline = System.nanoTime() + OLDEST_BUDGET_MS * 1_000_000L;
        List<Object[]> rows = new ArrayList<>();
        for (DbSizeSource.TableSize t : tables) {
            if (stopping != null && stopping.getAsBoolean()) {
                log.info("Veritabanı büyümesi görüntüsü kapanış nedeniyle yarıda bırakıldı ({}); sonraki tık yeniden dener", day);
                return 0;
            }
            if (t == null || t.name() == null || t.name().length() > 128) continue;
            String oldest = null;
            RetentionPolicy p = agePolicy(t.name());
            if (p != null && System.nanoTime() < deadline) {
                try {
                    oldest = source.oldest(p);
                } catch (RuntimeException e) {
                    log.debug("Veritabanı büyümesi: en eski kayıt okunamadı ({}): {}", p.table(), e.toString());
                }
            }
            rows.add(new Object[]{day, t.name(), t.totalBytes(), t.tableBytes(), t.indexBytes(), t.liveRows(), t.deadRows(),
                    t.insTotal(), t.delTotal(), oldest, writer, created});
        }
        if (stopping != null && stopping.getAsBoolean()) return 0;
        if (!rows.isEmpty()) jdbc.batchUpdate(SQL_INSERT, rows);
        jdbc.update(SQL_INSERT, day, ALL, dbBytes, null, null, null, null, null, null, null, writer, created);
        liveBytes = dbBytes;
        liveAtMs = now.toEpochMilli();
        invalidate();
        log.info("Veritabanı büyümesi günlük görüntüsü: {} tablo + toplam yazıldı ({}), veritabanı {} bayt", rows.size(), day, dbBytes);
        return rows.size() + 1;
    }

    // ── Görünüm ───────────────────────────────────────────────────────────────────────────────────────────

    /** Arayüz görünümü (60 sn bellek; {@code fresh} en çok 5 sn'de bir yeniden hesaplar). Geçersiz kapsam → varsayılan. */
    public Map<String, Object> overview(int range, boolean fresh) {
        int r = RANGES.contains(range) ? range : DEFAULT_RANGE;
        long now = clock.get().toEpochMilli();
        long maxAge = MEMO_MS;
        if (fresh && now - lastFreshMs >= FRESH_MIN_MS) {
            lastFreshMs = now;
            maxAge = 0;
        }
        return computed(r, maxAge).view();
    }

    /** Kapasite olguları (arka plan — en çok 5 dk eski hesap). PostgreSQL değilse {@code supported = FALSE}. */
    public CapacityFacts capacityFacts() {
        return computed(30, BACKGROUND_MAX_AGE_MS).facts();
    }

    /** Analizin öneri girdileri (en çok 60 sn eski hesap). */
    Computed adviceInputs() {
        return computed(30, MEMO_MS);
    }

    private Computed computed(int range, long maxAgeMs) {
        long now = clock.get().toEpochMilli();
        Computed c = memo.get(range);
        if (usable(c, now, maxAgeMs)) return c;
        synchronized (computeLock) {
            c = memo.get(range);
            if (usable(c, now, maxAgeMs)) return c;
            c = compute(range, clock.get());
            memo.put(range, c);
            return c;
        }
    }

    /** Bellek geçerli mi: yeterince taze VE kapasite / yasal tutma ayarı hesaplandığından beri değişmemiş (bellek okuması). */
    private boolean usable(Computed c, long nowMs, long maxAgeMs) {
        return c != null && nowMs - c.atMs() <= maxAgeMs && c.capacityGb() == capacityGb() && c.hold() == retention.holdActive();
    }

    /** Bir tablo satırı (görüntüden). */
    record Row(long day, Long total, Long table, Long index, Long live, Long dead, Long ins, Long del, String oldestAt) {}

    Computed compute(int range, Instant now) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("range_days", range);
        view.put("generated_at", ISO.format(now));
        int capGb = capacityGb();
        long capBytes = capGb * GB;
        view.put("capacity_gb", capGb > 0 ? capGb : null);
        boolean hold = retention.holdActive();
        view.put("hold_active", hold);
        Boolean pg = source.supported();
        if (pg == null) throw new org.springframework.dao.DataAccessResourceFailureException("veritabanı türü okunamadı");
        view.put("supported", pg);
        if (!pg) {
            view.put("reason", "not_postgres");
            return new Computed(now.toEpochMilli(), view, new CapacityFacts(false, capBytes, null, null, null, false, 0, null),
                    List.of(), null, capGb, hold);
        }
        LocalDate today = LocalDate.ofInstant(now, IST);

        Object[] hist = jdbc.queryForObject(SQL_HISTORY, (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)});
        long historyDays = hist == null ? 0 : (Long) hist[0];
        String firstDay = hist == null ? null : (String) hist[1];
        String lastDay = hist == null ? null : (String) hist[2];
        view.put("history_days", historyDays);
        view.put("first_day", firstDay);
        view.put("last_day", lastDay);
        view.put("snapshot_today", today.toString().equals(lastDay));
        Integer snapshotAge = lastDay == null ? null : (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(lastDay), today));
        boolean stale = snapshotAge != null && snapshotAge > STALE_SNAPSHOT_DAYS;
        view.put("snapshot_age_days", snapshotAge);
        view.put("forecast_stale", stale);
        view.put("stale_after_days", STALE_SNAPSHOT_DAYS);

        boolean liveOnly = lastDay == null;
        LocalDate anchorDay = liveOnly ? today : LocalDate.parse(lastDay);
        long anchorEpoch = anchorDay.toEpochDay();
        Map<String, List<Row>> byTable = new LinkedHashMap<>();
        List<Point> dbPoints = new ArrayList<>();
        Double dbAnchor = null;
        if (!liveOnly) {
            // Eğim penceresi (30 gün) + 30 günlük büyümenin tabanı için biraz daha geri (eğim kendi penceresini süzer)
            String from = anchorDay.minusDays(Math.max(DbGrowthForecast.WINDOW_DAYS + 1L, GROWTH_LOOKBACK_DAYS)).toString();
            jdbc.query(SQL_RECENT, rs -> {
                long day = LocalDate.parse(rs.getString(1)).toEpochDay();
                Row row = new Row(day, num(rs.getObject(3)), num(rs.getObject(4)), num(rs.getObject(5)), num(rs.getObject(6)),
                        num(rs.getObject(7)), num(rs.getObject(8)), num(rs.getObject(9)), rs.getString(10));
                byTable.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(row);
            }, from, lastDay);
            for (List<Row> l : byTable.values()) l.sort(Comparator.comparingLong(Row::day));
            for (Row r : byTable.getOrDefault(ALL, List.of())) {
                if (r.total() != null && r.day() >= anchorEpoch - (DbGrowthForecast.WINDOW_DAYS + 1L)) {
                    dbPoints.add(new Point(r.day(), r.total()));
                }
            }
            List<Row> all = byTable.get(ALL);
            if (all != null && !all.isEmpty() && all.get(all.size() - 1).day() == anchorEpoch) dbAnchor = (double) all.get(all.size() - 1).total();
        }
        view.put("live_only", liveOnly);

        // Tablo satırları: son görüntünün günü; görüntü yoksa canlı ölçüm (soğuk başlangıç)
        Map<String, Row> latest = new LinkedHashMap<>();
        if (liveOnly) {
            for (DbSizeSource.TableSize t : source.tables()) {
                latest.put(t.name(), new Row(anchorEpoch, t.totalBytes(), t.tableBytes(), t.indexBytes(), t.liveRows(), t.deadRows(),
                        t.insTotal(), t.delTotal(), null));
                byTable.put(t.name(), new ArrayList<>(List.of(latest.get(t.name()))));
            }
        } else {
            for (Map.Entry<String, List<Row>> e : byTable.entrySet()) {
                if (ALL.equals(e.getKey())) continue;
                Row last = e.getValue().get(e.getValue().size() - 1);
                if (last.day() == anchorEpoch && last.total() != null) latest.put(e.getKey(), last);
            }
        }

        Long live = liveDbBytes(now);
        // Boyut hiç okunamadıysa doluluk BİLİNMİYOR (sahte %0 değil — 2026-11-13 inceleme)
        boolean currentKnown = live != null || dbAnchor != null;
        double current = live != null ? live : dbAnchor != null ? dbAnchor : 0;
        if (dbAnchor == null) dbAnchor = live == null ? null : live.doubleValue();
        view.put("current_bytes", live != null ? live : dbAnchor == null ? null : Math.round(dbAnchor));
        view.put("current_source", live != null ? "live" : dbAnchor != null ? "snapshot" : null);

        Fit dbFit = DbGrowthForecast.fit(dbPoints);
        // Soğuk başlangıç: geçmiş yetmiyorsa yaş kurallarının zaman kolonlarında histogram (tek deyim)
        Map<String, List<String>> histograms = Map.of();
        if (dbFit == null) {
            List<String[]> pairs = new ArrayList<>();
            for (String name : latest.keySet()) {
                RetentionPolicy p = agePolicy(name);
                if (p != null) pairs.add(new String[]{p.table(), p.timeColumn()});
            }
            try {
                histograms = source.histograms(pairs);
            } catch (RuntimeException e) {
                log.debug("Veritabanı büyümesi: histogram okunamadı: {}", e.toString());
            }
        }
        LastRun lastRun = lastRun();
        Set<String> failedPolicies = lastRun.failures();
        Map<String, String> settingChanges = settingChanges(latest.keySet());

        List<TableInput> inputs = new ArrayList<>();
        List<TableFacts> facts = new ArrayList<>();
        List<Map<String, Object>> tableViews = new ArrayList<>();
        Map<String, Double> rateByTable = new HashMap<>();
        double coldSum = 0;
        boolean anyCold = false;
        for (Map.Entry<String, Row> e : latest.entrySet()) {
            String name = e.getKey();
            Row last = e.getValue();
            List<Row> rows = byTable.getOrDefault(name, List.of());
            List<RetentionPolicy> policies = RetentionCatalog.byTable(name);
            RetentionPolicy primary = primaryPolicy(policies);
            RetentionPolicy age = agePolicy(name);
            Integer eff = primary == null ? null : primary.settingKey() == null && !primary.deletes() ? null : retention.effectiveDays(primary);
            int ageDays = age == null ? 0 : retention.effectiveDays(age);

            String oldestAt = last.oldestAt();
            String oldestSource = oldestAt == null ? null : "min";
            List<String> bounds = age == null ? null : histograms.get(age.table() + "." + age.timeColumn());
            // Boş tabloda MIN null'dır ve doğrudur: pg_stats histogramı silinen satırlardan kalır (bayat) — ona düşmek
            // boş tabloya "temizlik geride" / doyma yazıyordu (canlı veride scripted_drafts, 2026-11-13). Dolu tabloda da
            // histogramın ilk sınırı yalnız TAHMİNDİR (MIN zaman aşımı / bütçe dışı) → "temizlik geride" kararına girmez.
            if (oldestAt == null && bounds != null && !bounds.isEmpty() && last.live() != null && last.live() > 0) {
                oldestAt = normalizeBound(bounds.get(0));
                oldestSource = "histogram";
            }
            Double ageOld = ageDaysOf(oldestAt, now);
            boolean saturated = age != null && DbGrowthForecast.saturated(ageOld, ageDays);

            List<Point> pts = new ArrayList<>();
            for (Row r : rows) if (r.total() != null) pts.add(new Point(r.day(), r.total()));
            Fit fit = DbGrowthForecast.fit(pts);
            long total = last.total();
            Long liveRows = last.live();

            String rateMethod = null;
            Double rowsPerDay = insertRate(rows);
            if (rowsPerDay != null) rateMethod = "stats";
            if (rowsPerDay == null && bounds != null && liveRows != null) {
                Double hr = histogramRowsPerDay(bounds, liveRows, now);
                if (hr != null) {
                    rowsPerDay = hr;
                    rateMethod = "histogram";
                }
            }
            if (rowsPerDay == null && ageOld != null && ageOld >= 1 && liveRows != null) {
                rowsPerDay = liveRows / ageOld;
                rateMethod = "uniform";
            }
            // Brüt ekleme hızı × satır başı bayt — yalnız satır başı bayta güvenilebiliyorsa. Brüt ekleme silmeleri saymaz:
            // büyüme olarak YALNIZ silme olmayan durumda (tek görüntü, dolmamış yaş tablosu) ya da yasal tutmada kullanılır.
            Double grossRate = rowsPerDay != null && liveRows != null && liveRows >= MIN_ROWS_FOR_ROW_BYTES
                    ? rowsPerDay * total / liveRows : null;
            // En az iki görüntü: toplam bayt farkı / gün — silmeler dahil gerçek ölçüm (ekle-sil döngüsündeki tablo ≈ 0)
            Double netRate = sizeRate(rows, last);
            // Yasal tutmada dolmuş yaş tablosu: gece temizliği silmez → brüt eklemeyle büyür (geçmiş eğim ~0 kalsa da)
            boolean holdGrows = hold && saturated;
            Fit tableFit = fit;
            Double coldRate = null;
            if (fit == null) {
                if (holdGrows) coldRate = grossRate != null ? Double.valueOf(Math.max(grossRate, netRate == null ? 0 : netRate)) : netRate;
                else if (netRate != null) coldRate = netRate;
                else if (saturated) coldRate = 0.0;
                else coldRate = grossRate;
            } else if (holdGrows && grossRate != null && grossRate > fit.slope()) {
                tableFit = null;          // tutma yeni açıldı: geçmiş eğim ekleme ≈ silmeyi gösterir, tahmin brüt eklemeyle
                coldRate = grossRate;
            }
            if (tableFit == null && coldRate != null) {
                coldSum += coldRate;
                anyCold = true;
            }
            // Yasal tutma açıkken denge sınırı YOK: gece temizliği satır silmez, tablo doğrusal büyür (iyimser tahmin olmasın)
            Double eq = hold || age == null || ageDays <= 0 ? null : DbGrowthForecast.equilibrium(total, ageOld, ageDays);
            TableInput in = new TableInput(name, total, tableFit, coldRate, eq);
            inputs.add(in);
            rateByTable.put(name, tableFit != null ? Double.valueOf(tableFit.slope()) : coldRate);   // Double: düz double + null kutusu açılıp NPE verirdi

            Long growth30 = growthOver(rows, last, 30);
            String changedAt = primary == null || primary.settingKey() == null ? null : settingChanges.get(primary.settingKey());
            boolean cleanupPending = changedAt != null && lastRun.known()
                    && (lastRun.startedAt() == null || changedAt.compareTo(lastRun.startedAt()) >= 0);
            Double changedAgo = ageDaysOf(changedAt, now);
            boolean changedRecently = changedAgo != null && changedAgo < DbGrowthAdvisor.SHORTEN_COOLDOWN_DAYS;
            facts.add(new TableFacts(name, total, last.table(), last.index(), liveRows, last.dead(), growth30, primary, policies,
                    age != null ? Integer.valueOf(ageDays) : eff, ageOld, eq, primary != null && failedPolicies.contains(primary.id()), null, null,
                    "histogram".equals(oldestSource), cleanupPending, changedRecently));

            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("name", name);
            tv.put("total_bytes", total);
            tv.put("table_bytes", last.table());
            tv.put("index_bytes", last.index());
            tv.put("toast_bytes", last.table() == null || last.index() == null ? null : Math.max(0, total - last.table() - last.index()));
            tv.put("live_rows", liveRows);
            tv.put("dead_rows", last.dead());
            tv.put("growth_30d_bytes", growth30);
            tv.put("daily_growth_bytes", tableFit != null ? Long.valueOf(Math.round(tableFit.slope())) : coldRate == null ? null : Long.valueOf(Math.round(coldRate)));
            tv.put("daily_growth_estimate", tableFit == null && coldRate != null);
            tv.put("rows_per_day", rowsPerDay == null ? null : Math.round(rowsPerDay * 10.0) / 10.0);
            tv.put("rows_per_day_method", rateMethod);
            tv.put("oldest_at", oldestAt);
            tv.put("oldest_source", oldestSource);
            tv.put("retention", retentionView(primary, age, policies, eff, ageDays));
            tv.put("saturation_pct", age == null || ageOld == null || ageDays <= 0 ? null
                    : Math.round(Math.min(100, ageOld / ageDays * 100) * 10.0) / 10.0);
            tv.put("saturated", age == null || ageOld == null ? null : saturated);
            tv.put("equilibrium_bytes", eq == null ? null : Math.round(eq));
            tv.put("projection_90d_bytes", Math.round(DbGrowthForecast.project(in, 90).value()));
            tableViews.add(tv);
        }
        tableViews.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("total_bytes")).longValue())
                .thenComparing(m -> String.valueOf(m.get("name"))));

        // ── Tahmin ──
        String basis = dbFit != null ? "history" : (anyCold && dbAnchor != null ? "cold_start" : "none");
        view.put("forecast_basis", basis);
        view.put("history_points", dbFit == null ? dbPoints.size() : dbFit.points());
        view.put("min_points", DbGrowthForecast.MIN_POINTS);
        Fit usedFit = dbFit;
        List<TableInput> usedInputs = inputs;
        List<Map<String, Object>> curve = new ArrayList<>();
        List<Map<String, Object>> horizons = new ArrayList<>();
        Integer daysFull = null;
        boolean fillsWithin = false;
        Double usedPct = null;
        if (capBytes > 0 && currentKnown) usedPct = Math.round(current / capBytes * 1000.0) / 10.0;
        if (!"none".equals(basis)) {
            double anchor = dbAnchor;
            for (int d = 1; d <= DbGrowthForecast.HORIZONS[DbGrowthForecast.HORIZONS.length - 1]; d++) {
                Projection p = DbGrowthForecast.projectDatabase(anchor, usedInputs, usedFit, d);
                if (p == null) break;
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("day", anchorDay.plusDays(d).toString());
                pt.put("value", Math.round(p.value()));
                pt.put("low", Math.round(p.low()));
                pt.put("high", Math.round(p.high()));
                curve.add(pt);
            }
            for (int h : DbGrowthForecast.HORIZONS) {
                Projection p = DbGrowthForecast.projectDatabase(anchor, usedInputs, usedFit, h);
                if (p == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("days", h);
                m.put("value", Math.round(p.value()));
                m.put("low", Math.round(p.low()));
                m.put("high", Math.round(p.high()));
                horizons.add(m);
            }
            if (capBytes > 0 && currentKnown && !stale) {
                if (current >= capBytes) {
                    daysFull = 0;
                    fillsWithin = true;
                } else {
                    Integer d = DbGrowthForecast.daysUntilFull(capBytes, anchor, usedInputs, usedFit);
                    if (d != null) {
                        long since = Math.max(0, ChronoUnit.DAYS.between(anchorDay, today));
                        daysFull = (int) Math.max(0, d - since);
                        fillsWithin = true;
                    }
                }
            }
        }
        view.put("forecast", curve);
        view.put("horizons", horizons);

        // ── KPI ──
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("current_bytes", view.get("current_bytes"));
        kpis.put("daily_growth_bytes", dbFit != null ? Long.valueOf(Math.round(dbFit.slope())) : anyCold ? Long.valueOf(Math.round(coldSum)) : null);
        kpis.put("daily_growth_estimate", dbFit == null && anyCold);
        kpis.put("growth_30d_bytes", byTable.containsKey(ALL) ? growthOver(byTable.get(ALL), byTable.get(ALL).get(byTable.get(ALL).size() - 1), 30) : null);
        Map<String, Object> h90 = horizons.stream().filter(m -> Integer.valueOf(90).equals(m.get("days"))).findFirst().orElse(null);
        kpis.put("projection_90d_bytes", h90 == null ? null : h90.get("value"));
        kpis.put("projection_90d_estimate", !"history".equals(basis));
        kpis.put("used_pct", usedPct);
        kpis.put("days_until_full", daysFull);
        kpis.put("fills_within_horizon", capBytes > 0 && currentKnown && !stale && !"none".equals(basis) ? fillsWithin : null);
        kpis.put("days_until_full_estimate", !"history".equals(basis));
        view.put("kpis", kpis);

        // ── Grafik serisi ──
        view.put("series", seriesView(range, anchorDay, liveOnly, dbAnchor, tableViews));
        view.put("tables", tableViews);

        // ── Öneriler ──
        DbGrowthAdvisor.Context ctx = new DbGrowthAdvisor.Context(Math.round(current), capBytes, usedPct, daysFull,
                dbFit != null && !stale, hold);
        List<DbGrowthAdvisor.Finding> findings = DbGrowthAdvisor.advise(facts, ctx);
        List<Map<String, Object>> fv = new ArrayList<>();
        Map<String, List<String>> codesByTable = new HashMap<>();
        for (DbGrowthAdvisor.Finding f : findings) {
            fv.add(f.toMap());
            if (f.table() != null) codesByTable.computeIfAbsent(f.table(), k -> new ArrayList<>()).add(f.code());
        }
        for (Map<String, Object> tv : tableViews) tv.put("findings", codesByTable.getOrDefault(String.valueOf(tv.get("name")), List.of()));
        view.put("findings", fv);

        CapacityFacts cf = new CapacityFacts(true, capBytes, live != null ? live : dbAnchor == null ? null : Math.round(dbAnchor),
                usedPct, dbFit != null ? daysFull : null, dbFit != null && fillsWithin, dbFit == null ? dbPoints.size() : dbFit.points(),
                lastDay, snapshotAge);
        return new Computed(now.toEpochMilli(), view, cf, facts, ctx, capGb, hold);
    }

    /** Grafik: veritabanı toplamı + en büyük {@value #TOP_TABLES} tablo, seçilen aralık (tek sorgu). */
    private Map<String, Object> seriesView(int range, LocalDate anchorDay, boolean liveOnly, Double dbAnchor,
                                           List<Map<String, Object>> tableViews) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> top = new ArrayList<>();
        for (Map<String, Object> tv : tableViews) {
            if (top.size() >= TOP_TABLES) break;
            top.add(String.valueOf(tv.get("name")));
        }
        out.put("top_tables", top);
        List<Map<String, Object>> db = new ArrayList<>();
        Map<String, List<Map<String, Object>>> perTable = new LinkedHashMap<>();
        for (String t : top) perTable.put(t, new ArrayList<>());
        if (liveOnly) {
            if (dbAnchor != null) db.add(point(anchorDay.toString(), Math.round(dbAnchor)));
            for (Map<String, Object> tv : tableViews) {
                List<Map<String, Object>> l = perTable.get(String.valueOf(tv.get("name")));
                if (l != null) l.add(point(anchorDay.toString(), ((Number) tv.get("total_bytes")).longValue()));
            }
        } else {
            List<String> names = new ArrayList<>(top);
            names.add(0, ALL);
            String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(anchorDay.minusDays(range).toString());
            args.add(anchorDay.toString());
            args.addAll(names);
            jdbc.query(String.format(SQL_SERIES, placeholders), rs -> {
                String name = rs.getString(2);
                Long v = num(rs.getObject(3));
                if (v == null) return;
                Map<String, Object> p = point(rs.getString(1), v);
                if (ALL.equals(name)) db.add(p);
                else if (perTable.containsKey(name)) perTable.get(name).add(p);
            }, args.toArray());
            Comparator<Map<String, Object>> byDay = Comparator.comparing(m -> String.valueOf(m.get("day")));
            db.sort(byDay);
            for (List<Map<String, Object>> l : perTable.values()) l.sort(byDay);
        }
        out.put("database", db);
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : perTable.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("points", e.getValue());
            tables.add(m);
        }
        out.put("tables", tables);
        return out;
    }

    private static Map<String, Object> point(String day, long bytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("bytes", bytes);
        return m;
    }

    private Map<String, Object> retentionView(RetentionPolicy primary, RetentionPolicy age, List<RetentionPolicy> policies,
                                              Integer eff, int ageDays) {
        if (primary == null) return null;
        RetentionPolicy p = age != null ? age : primary;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy_id", p.id());
        m.put("mode", p.mode().name());
        m.put("days", age != null ? Integer.valueOf(ageDays) : p.deletes() && p.timeColumn() != null ? eff : null);
        m.put("min_days", p.configurable() ? p.minDays() : null);
        m.put("configurable", p.configurable());
        m.put("setting_key", p.configurable() ? p.settingKey() : null);
        m.put("data_class", p.dataClass().name());
        m.put("time_column", p.timeColumn());
        m.put("policies", policies.size());
        return m;
    }

    // ── "Kısaltırsam ne silinir" ──────────────────────────────────────────────────────────────────────────

    /** Kısaltma tahmininde zaman aşımlı COUNT'ların toplam bütçesi (ayarı paylaşan kurallar sırayla sayılır). */
    static final long WHAT_IF_COUNT_BUDGET_MS = 15_000;

    /**
     * Kuralın AYAR ANAHTARINI {@code days} güne kısaltmanın İLK gece silinecek satır tahmini. Anahtar birden çok kuralı
     * yönetiyorsa (ör. bildirim günlükleri beş tablo) HEPSİ listelenir — kısaltma hepsini birden etkiler (2026-11-13 inceleme).
     * Kural başına: önce kuralın kendi WHERE'iyle zaman aşımlı COUNT (5 sn; toplam {@value #WHAT_IF_COUNT_BUDGET_MS} ms), süre
     * dolarsa histogram, o da yoksa tekdüze varsayım. Taban = paylaşan kuralların EN BÜYÜK alt sınırı. Değişiklik YAPMAZ.
     * Yasal tutma açıksa {@code hold_active}: hold kalkana dek hiçbir satır silinmez (sayılar "hold kalkınca" anlamındadır).
     */
    public Map<String, Object> whatIf(String policyId, int days) {
        RetentionPolicy p = policyId == null ? null : RetentionCatalog.byId(policyId.trim()).orElse(null);
        if (p == null || !p.configurable()) {
            throw new FieldValidationException("policy", Msg.t("Bu saklama kuralı ayarlanabilir değil ya da bilinmiyor: " + policyId,
                    "This retention rule is unknown or cannot be changed: " + policyId));
        }
        List<RetentionPolicy> siblings = DbGrowthAdvisor.keySiblings(p);
        int floor = DbGrowthAdvisor.keyFloor(p);
        if (days < floor) {
            throw new FieldValidationException("days", Msg.t(p.settingKey() + " için en az " + floor + " gün girilmelidir (girilen: " + days + ")",
                    p.settingKey() + " requires at least " + floor + " days (entered: " + days + ")"));
        }
        Instant now = clock.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy_id", p.id());
        out.put("table", p.table());
        out.put("setting_key", p.settingKey());
        out.put("current_days", retention.effectiveDays(p));
        out.put("days", days);
        out.put("min_days", floor);
        out.put("data_class", p.dataClass().name());
        out.put("compliance", DbGrowthAdvisor.keyNeedsCompliance(p));
        out.put("hold_active", retention.holdActive());
        out.put("affected_count", siblings.size());
        Boolean pg = source.supported();
        out.put("supported", Boolean.TRUE.equals(pg));
        List<Map<String, Object>> rowsOut = new ArrayList<>();
        long deadline = System.nanoTime() + WHAT_IF_COUNT_BUDGET_MS * 1_000_000L;
        Long sumRows = 0L, sumBytes = 0L;
        for (RetentionPolicy x : siblings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policy_id", x.id());
            m.put("table", x.table());
            m.put("data_class", x.dataClass().name());
            m.put("min_days", x.minDays());
            m.put("current_days", retention.effectiveDays(x));
            m.put("compliance", x.dataClass() == RetentionPolicy.DataClass.PERSONAL || x.dataClass() == RetentionPolicy.DataClass.SECURITY_AUDIT);
            if (Boolean.TRUE.equals(pg)) {
                estimateInto(m, x, days, now, System.nanoTime() < deadline);
                Long r = m.get("rows") instanceof Number n ? n.longValue() : null;
                Long b = m.get("bytes_estimate") instanceof Number n ? n.longValue() : null;
                sumRows = sumRows == null || r == null ? null : sumRows + r;
                sumBytes = sumBytes == null || b == null ? null : sumBytes + b;
            }
            if (x.id().equals(p.id())) {
                // İstenen kuralın değerleri üst düzeyde de (eski arayüz sözleşmesi)
                for (String k : List.of("cutoff", "rows", "rows_method", "table_bytes", "live_rows", "bytes_estimate")) out.put(k, m.get(k));
            }
            rowsOut.add(m);
        }
        out.put("policies", rowsOut);
        out.put("total_rows", Boolean.TRUE.equals(pg) ? sumRows : null);
        out.put("total_bytes_estimate", Boolean.TRUE.equals(pg) ? sumBytes : null);
        return out;
    }

    /** Bir kuralın ilk gece silinecek satır tahmini (COUNT → histogram → tekdüze) — {@code m}'ye yazar. */
    private void estimateInto(Map<String, Object> m, RetentionPolicy p, int days, Instant now, boolean countAllowed) {
        String cutoff = cutoffFor(p, days, now);
        m.put("cutoff", cutoff);
        Long totalBytes = null, live = null;
        String oldest = null;
        try {
            List<Object[]> r = jdbc.query(SQL_LATEST_TABLE, (rs, i) -> new Object[]{num(rs.getObject(1)), num(rs.getObject(2)), rs.getString(3)},
                    p.table());
            if (!r.isEmpty()) {
                totalBytes = (Long) r.get(0)[0];
                live = (Long) r.get(0)[1];
                oldest = (String) r.get(0)[2];
            }
        } catch (RuntimeException e) {
            log.debug("Veritabanı büyümesi: tablo görüntüsü okunamadı: {}", e.toString());
        }
        Long rows = countAllowed ? source.countOlder(p, cutoff) : null;
        String method = rows == null ? null : "count";
        if (rows == null && live != null && p.timeColumn() != null) {
            try {
                List<String> bounds = source.histograms(List.<String[]>of(new String[]{p.table(), p.timeColumn()}))
                        .get(p.table() + "." + p.timeColumn());
                Double frac = DbGrowthForecast.histogramFractionBefore(bounds, cutoffInstant(cutoff));
                if (frac != null) {
                    rows = Math.round(live * frac);
                    method = "histogram";
                }
            } catch (RuntimeException e) {
                log.debug("Veritabanı büyümesi: histogram okunamadı: {}", e.toString());
            }
        }
        if (rows == null && live != null) {
            Double age = ageDaysOf(oldest, now);
            if (age != null) {
                rows = Math.round(live * DbGrowthAdvisor.uniformPurgeFraction(age, Math.max(days, (int) Math.ceil(age)), days));
                method = "uniform";
            }
        }
        m.put("rows", rows);
        m.put("rows_method", method);
        m.put("table_bytes", totalBytes);
        m.put("live_rows", live);
        m.put("bytes_estimate", rows == null || totalBytes == null || live == null || live <= 0 ? null
                : Math.round((double) totalBytes * Math.min(rows, live) / live));
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────

    /** Canlı veritabanı boyutu (5 dk bellek); okunamazsa son bilinen ya da null. */
    Long liveDbBytes(Instant now) {
        long ms = now.toEpochMilli();
        if (liveBytes != null && ms - liveAtMs <= LIVE_SIZE_MS) return liveBytes;
        try {
            liveBytes = source.databaseBytes();
            liveAtMs = ms;
        } catch (RuntimeException e) {
            log.debug("Veritabanı büyümesi: canlı boyut okunamadı: {}", e.toString());
        }
        return liveBytes;
    }

    /** Tablonun düz yaş kuralı (AGE, zaman kolonlu) — yoksa null. */
    static RetentionPolicy agePolicy(String table) {
        for (RetentionPolicy p : RetentionCatalog.byTable(table)) {
            if (p.mode() == RetentionPolicy.Mode.AGE && p.timeColumn() != null) return p;
        }
        return null;
    }

    /** Ekranda gösterilen kural: yaş kuralı, yoksa silen ilk kural, yoksa ilk kural; katalog dışı → null. */
    static RetentionPolicy primaryPolicy(List<RetentionPolicy> policies) {
        if (policies == null || policies.isEmpty()) return null;
        for (RetentionPolicy p : policies) if (p.mode() == RetentionPolicy.Mode.AGE && p.timeColumn() != null) return p;
        for (RetentionPolicy p : policies) if (p.deletes()) return p;
        return policies.get(0);
    }

    /**
     * Son gerçek (deneme olmayan) temizlik koşusu: başlangıcı ({@code null} = hiç koşmadı), hata veren kurallar ve okumanın
     * başarılı olup olmadığı ({@code known} FALSE → "ayar temizlikten sonra mı değişti" bilinmez, karar değişmez).
     */
    record LastRun(boolean known, String startedAt, Set<String> failures) {}

    private LastRun lastRun() {
        Set<String> out = new HashSet<>();
        if (runRepo == null || itemRepo == null) return new LastRun(false, null, out);
        try {
            RetentionRun run = runRepo.findFirstByDryRunFalseOrderByStartedAtDesc().orElse(null);
            if (run == null) return new LastRun(true, null, out);
            for (RetentionRunItem it : itemRepo.findByRunIdInOrderByIdAsc(List.of(run.getId()))) {
                if (it.getError() != null && it.getPolicyId() != null) out.add(it.getPolicyId());
            }
            return new LastRun(true, run.getStartedAt(), out);
        } catch (RuntimeException e) {
            log.debug("Veritabanı büyümesi: son temizlik koşusu okunamadı: {}", e.toString());
            return new LastRun(false, null, out);
        }
    }

    /**
     * Tabloların saklama ayar anahtarlarının son değişme anı ({@code app_settings.updated_at}, UTC ISO) — tek deyim; ayar
     * hiç kaydedilmediyse anahtar yok. Okunamazsa boş (karar değişmez).
     */
    private Map<String, String> settingChanges(Set<String> tables) {
        Set<String> keys = new java.util.TreeSet<>();
        for (String t : tables) {
            RetentionPolicy p = primaryPolicy(RetentionCatalog.byTable(t));
            if (p != null && p.settingKey() != null) keys.add(p.settingKey());
        }
        Map<String, String> out = new HashMap<>();
        if (keys.isEmpty()) return out;
        try {
            jdbc.query(String.format(SQL_SETTING_CHANGES, String.join(",", java.util.Collections.nCopies(keys.size(), "?"))), rs -> {
                String k = rs.getString(1), at = rs.getString(2);
                if (k != null && at != null) out.put(k, at);
            }, keys.toArray());
        } catch (RuntimeException e) {
            log.debug("Veritabanı büyümesi: saklama ayarı değişiklik anları okunamadı: {}", e.toString());
            out.clear();
        }
        return out;
    }

    /**
     * Histogramdan günlük satır: son 7 gündeki satır payı × canlı satır ÷ 7. Pencere histogramın KENDİ son sınırında biter —
     * istatistik 7 günden eskiyse "şimdi − 7 gün" bütün sınırların sağında kalır ve oran sahte 0 çıkardı (2026-11-13 hata
     * taraması: bugün yeni satırı olan tablolar "0 satır/gün").
     */
    static Double histogramRowsPerDay(List<String> bounds, long liveRows, Instant now) {
        if (bounds == null || bounds.size() < 2) return null;
        Long lastSec = DbGrowthForecast.epochSeconds(bounds.get(bounds.size() - 1));
        if (lastSec == null) return null;
        Instant end = Instant.ofEpochSecond(Math.min(lastSec, now.getEpochSecond()));
        Double frac = DbGrowthForecast.histogramFractionBefore(bounds, end.minus(7, ChronoUnit.DAYS));
        return frac == null ? null : liveRows * (1 - frac) / 7.0;
    }

    /**
     * En az iki görüntüden günlük bayt artışı: eğim penceresindeki en eski görüntüden son görüntüye toplam bayt farkı ÷ gün
     * (silmeler dahil gerçek ölçüm; küçülme 0 sayılır). Tek görüntü → null.
     */
    static Double sizeRate(List<Row> rows, Row last) {
        if (rows == null || last == null || last.total() == null) return null;
        Row first = null;
        for (Row r : rows) {
            if (r.total() == null || r.day() >= last.day() || r.day() < last.day() - DbGrowthForecast.WINDOW_DAYS) continue;
            if (first == null || r.day() < first.day()) first = r;
        }
        if (first == null) return null;
        return Math.max(0.0, (double) (last.total() - first.total()) / (last.day() - first.day()));
    }

    /** Ardışık görüntülerin birikimli ekleme sayacı farklarından günlük satır (negatif fark = sıfırlama, atlanır). */
    static Double insertRate(List<Row> rows) {
        if (rows == null || rows.size() < 2) return null;
        long sum = 0, days = 0;
        int from = Math.max(1, rows.size() - 8);
        for (int i = from; i < rows.size(); i++) {
            Row a = rows.get(i - 1), b = rows.get(i);
            if (a.ins() == null || b.ins() == null) continue;
            long delta = b.ins() - a.ins();
            long gap = b.day() - a.day();
            if (delta < 0 || gap <= 0) continue;
            sum += delta;
            days += gap;
        }
        return days == 0 ? null : (double) sum / days;
    }

    /**
     * {@code days} günlük büyüme: taban = gün farkı {@code days}'e EN YAKIN görüntü (fark {@value #GROWTH_MIN_GAP_DAYS} …
     * {@value #GROWTH_LOOKBACK_DAYS} gün; eşitlikte uzun olan), fark gün sayısına göre {@code days} güne ölçeklenir. Tam −30.
     * / −31. günün görüntüsü eksik diye 30 günlük büyüme kaybolmaz (2026-11-13 hata taraması). Uygun taban yoksa null.
     */
    static Long growthOver(List<Row> rows, Row last, int days) {
        if (rows == null || last == null || last.total() == null) return null;
        long minGap = Math.min(days, GROWTH_MIN_GAP_DAYS), maxGap = Math.max(days, GROWTH_LOOKBACK_DAYS);
        Row base = null;
        long baseGap = 0;
        for (Row r : rows) {
            if (r.total() == null) continue;
            long gap = last.day() - r.day();
            if (gap < minGap || gap > maxGap) continue;
            if (base == null || Math.abs(gap - days) < Math.abs(baseGap - days)
                    || (Math.abs(gap - days) == Math.abs(baseGap - days) && gap > baseGap)) {
                base = r;
                baseGap = gap;
            }
        }
        if (base == null) return null;
        long diff = last.total() - base.total();
        return baseGap == days ? diff : Math.round((double) diff * days / baseGap);
    }

    static Double ageDaysOf(String iso, Instant now) {
        if (iso == null) return null;
        try {
            Instant at = LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC);
            double d = Duration.between(at, now).toMinutes() / 1440.0;
            return Math.max(0, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String normalizeBound(String b) {
        Long sec = DbGrowthForecast.epochSeconds(b);
        return sec == null ? null : ISO.format(Instant.ofEpochSecond(sec));
    }

    /** Kuralın {@code days} günlük kesimi — {@code RetentionService.cutoffFor} ile aynı biçim. */
    static String cutoffFor(RetentionPolicy p, int days, Instant now) {
        String iso = ISO.format(now.minus(days, ChronoUnit.DAYS));
        return switch (p.timeKind()) {
            case DATE10 -> iso.substring(0, 10);
            case DATE13 -> iso.substring(0, 13);
            default -> iso;
        };
    }

    static Instant cutoffInstant(String cutoff) {
        Long s = DbGrowthForecast.epochSeconds(cutoff);
        return s == null ? null : Instant.ofEpochSecond(s);
    }

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
