package com.sitemonitor.service;

import com.sitemonitor.service.schedule.SideJob;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * TEK SEFERLİK yeniden hesap (H paketi 2026-11-26, rapor B1 / B2): saatlik / günlük erişilebilirlik özetleri artık yalnız
 * SAYILAN satırları özetler ({@link AvailabilityRows}: elle tetik, teyit / kurtarma yeniden kontrolü ve ICMP'nin ölçemediği
 * ping satırı çıkar). Gece rollup'ı yalnız son birkaç günü yeniler; bu iş ham serinin hâlâ elde olduğu TÜM dönemi yeni
 * kuralla yeniden özetler — eski kovalarda kalan hatalı paydalar (ICMP yetkisiz pod'da %0'a yakın ping, teyit zincirinin
 * şişirdiği hata sayısı) düzelir.
 *
 * <p><b>Nasıl:</b> tür başına (sekiz izleme-kimlikli tür + Durum İzleme) ilk TAM ham günden bir gün sonrasından (saklama
 * budaması işle yarışmasın) içinde bulunulan saatin başına (canlı saatlik kova saatlik rollup'ın) kadar, haftalık dilimlerle:
 * aynı bağlantıda, TEK işlemde dilimin HÂLÂ HAM SATIRI OLAN özet satırları SİLİNİR (silinmiş izlemenin kovaları kalır) ve
 * üretim SQL'iyle ({@code SchedulerService.upsertSql} / {@code uptimeUpsertSql}) yeniden yazılır — okuyan boşluk görmez,
 * yarıda kalan dilim geri alınır. Silme gerekir: kovanın bütün satırları artık sayılmıyorsa (yalnız ölçülemeyen ping) eski
 * kova üzerine yazılarak düzelmez. Ham verisi saklamayla silinmiş eski kovalar olduğu gibi kalır (yeniden hesaplanamaz).
 *
 * <p><b>Ne zaman:</b> açılış yolunda değil — açılıştan 15 dk sonra, saatte bir, yan iş koşucusunda; kendi yenilenen
 * {@code scheduler_lock} kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız TÜM türler bitince yazılır. Başka
 * sürüm koşan canlı pod varken (eski pod gece rollup'ında eski kuralla yazar) ya da sürümler okunamazken tur atlanır.
 * Tur başına {@value #RUN_BUDGET_MS} ms bütçe (yenilenen kilidin 3 saatlik tavanının altında); bütçe dolarsa, kapanış
 * başlarsa ya da kilit kaybolursa iş pod başına imlecini saklayıp bir sonraki tura bırakır (hata sayılmaz). Kalıcı hatada
 * pod başına {@value #MAX_ATTEMPTS} denemeden sonra bir kez ERROR yazılır ve bu pod bırakır. Her cümle
 * {@value #STATEMENT_TIMEOUT_S} sn sorgu zaman aşımıyla sınırlıdır. PostgreSQL dışında (H2) özet işi yok: nişan yazılır;
 * veritabanı türü OKUNAMAZSA karar verilmez.
 */
@Slf4j
@Service
public class AvailabilityRollupRecompute {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-11-26-availability-rollup-recompute";
    /** Kilit ve yan iş adı (kalıcı). */
    static final String LOCK = "availability-rollup-recompute";
    static final int MAX_ATTEMPTS = 24;
    static final long TICK_MS = 3_600_000L;
    static final Duration LOCK_TTL = Duration.ofMinutes(120);
    /**
     * Tur bütçesi: yenilenen kilidin 3 saatlik tavanının altında kalır. Bütçe yalnız dilim BAŞLAMADAN denetlenir; bir dilim
     * en kötü 4 cümle × {@value #STATEMENT_TIMEOUT_S} sn = 40 dk sürebilir → 120 + 40 &lt; 180 dk (kilit tavanında pay kalır).
     */
    static final long RUN_BUDGET_MS = 120L * 60_000L;
    static final int STATEMENT_TIMEOUT_S = 600;
    static final int SLICE_DAYS = 7;
    /** İlk TAM ham günden sonra bırakılan güvenlik günü (gece saklama budaması işle yarışmasın). */
    static final int SAFETY_DAYS = 1;

    static final String DAILY_TABLE = "monitor_check_daily";
    static final String DAILY_COL = "day";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Yeniden hesaplanan birim: tür, ham tablo ve iki özet SQL'i. */
    record Unit(String type, String table, String dailySql, String hourlySql) {
        /** Özet satırının {@code monitor_key}'ini ham satırdan üreten ifade (özet SQL'iyle aynı; yalnız KOD sabiti). */
        String keyExpr() { return "UPTIME".equals(type) ? "domain" : "CAST(monitor_id AS varchar)"; }
    }

    /** Bu pod'un kaldığı yer: birim sırası + sonraki dilimin başı. */
    record Cursor(int unit, Instant next) {}

    enum Outcome { DONE, PROGRESS, FAILED }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final ObjectProvider<DeploymentHistoryService> history;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean markerSeen;
    private volatile Cursor cursor;
    /** Test edilebilir saat (ms). */
    LongSupplier clock = System::currentTimeMillis;

    public AvailabilityRollupRecompute(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                       ObjectProvider<DeploymentHistoryService> history) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.history = history;
    }

    /** Sekiz tür + Durum İzleme — üretim SQL üreticileri (gece rollup'ıyla aynı cümleler). */
    static List<Unit> units() {
        List<Unit> out = new ArrayList<>();
        for (SchedulerService.RollupType t : SchedulerService.ROLLUP_TYPES) {
            out.add(new Unit(t.type(), t.table(),
                    SchedulerService.upsertSql(DAILY_TABLE, DAILY_COL, 10, t.type(), t.table(), t.upCol(), t.respCol()),
                    SchedulerService.upsertSql(SchedulerService.HOURLY_TABLE, SchedulerService.HOURLY_COL, 13,
                            t.type(), t.table(), t.upCol(), t.respCol())));
        }
        out.add(new Unit("UPTIME", "uptime_checks",
                SchedulerService.uptimeUpsertSql(DAILY_TABLE, DAILY_COL, 10),
                SchedulerService.uptimeUpsertSql(SchedulerService.HOURLY_TABLE, SchedulerService.HOURLY_COL, 13)));
        return List.copyOf(out);
    }

    @Scheduled(initialDelayString = "${site.monitor.availability-rollup-recompute.initial-delay-ms:900000}", fixedDelay = TICK_MS)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;          // bu pod denemeyi bıraktı (bir kez ERROR yazıldı)
        if (done()) return;
        if (!singleVersion()) return;                        // dağıtım sürüyor ya da okunamadı: sonraki tur
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        Runnable body = this::runUnderLock;
        if (!s.offloadSideJob(SideJob.fixedDelay(LOCK, TICK_MS, body))) body.run();
    }

    /** Kilit altında bir tur; kilit başka pod'daysa sessizce atlanır. */
    void runUnderLock() {
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            Optional<Outcome> r = s.callWithSchedulerLock(LOCK, LOCK_TTL, this::recomputeLocked);
            if (r.isEmpty()) return;                          // kilit başka pod'da
            if (r.get() == Outcome.FAILED) noteFailure(null);
            else failures.set(0);
        } catch (RuntimeException e) {
            noteFailure(e);
        }
    }

    private void noteFailure(RuntimeException e) {
        int n = failures.incrementAndGet();
        if (n >= MAX_ATTEMPTS) {
            log.error("Erişilebilirlik özeti yeniden hesabı {} denemede tamamlanamadı — bu pod yeniden denemeyi bırakıyor "
                    + "(nişan yazılmadı; pod yeniden başlayınca ya da başka pod yeniden dener)", n, e);
        } else if (e != null) {
            log.warn("Erişilebilirlik özeti yeniden hesabı başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
        }
    }

    /** Başka sürüm koşan canlı pod yok mu; okunamazsa "hayır" (bekle). Geçmiş servisi yoksa (dar bağlam) "evet". */
    boolean singleVersion() {
        DeploymentHistoryService h = history.getIfAvailable();
        if (h == null) return true;
        List<Map<String, String>> others = h.liveOtherVersions();
        if (others == null) {
            log.debug("Erişilebilirlik özeti yeniden hesabı bekliyor: pod sürümleri okunamadı");
            return false;
        }
        if (!others.isEmpty()) {
            log.debug("Erişilebilirlik özeti yeniden hesabı bekliyor: başka sürüm koşan pod var {}", others);
            return false;
        }
        return true;
    }

    /** Nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar). */
    boolean done() {
        if (markerSeen) return true;
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
            boolean seen = n != null && n > 0;
            if (seen) markerSeen = true;
            return seen;
        } catch (RuntimeException e) {
            log.debug("Erişilebilirlik özeti yeniden hesabı nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /** Kilit altında: kaldığı yerden sürdürür; tümü bitince nişanı yazar. */
    Outcome recomputeLocked() {
        if (done()) return Outcome.DONE;
        Boolean pg = postgres();
        if (pg == null) {
            log.warn("Erişilebilirlik özeti yeniden hesabı: veritabanı türü okunamadı — bir saat sonra yeniden denenecek");
            return Outcome.FAILED;
        }
        if (!pg) {
            writeMarker(0);   // H2 / bilinmeyen tür: özet tabloları PostgreSQL'e özgü yazılır — iş yok
            return Outcome.DONE;
        }
        long started = clock.getAsLong();
        Instant todayStart = Instant.ofEpochMilli(started).truncatedTo(ChronoUnit.DAYS);
        // Bağımsız inceleme (H paketi): son dilim CANLI saatlik kovayla çakışmasın — iş içinde bulunulan saatin başında
        // durur (o saat ve sonrası saatlik rollup'ın; günlük kovalar zaten yalnız tamamlanmış günler).
        Instant end = Instant.ofEpochMilli(started).truncatedTo(ChronoUnit.HOURS);
        List<Unit> units = units();
        Cursor c = cursor;
        int startUnit = c == null ? 0 : Math.min(Math.max(0, c.unit()), units.size());
        long written = 0;
        for (int u = startUnit; u < units.size(); u++) {
            Unit unit = units.get(u);
            Instant from = firstSafeDay(unit.table());
            if (from == null) continue;                       // ham satır yok — eski kovalar olduğu gibi kalır
            if (c != null && c.unit() == u && c.next() != null && c.next().isAfter(from)) from = c.next();
            for (Instant s = from; s.isBefore(end); s = s.plus(SLICE_DAYS, ChronoUnit.DAYS)) {
                Outcome pause = pauseReason(started);
                if (pause != null) {
                    cursor = new Cursor(u, s);
                    log.info("Erişilebilirlik özeti yeniden hesabı yarıda bırakıldı ({} {}'ten sürecek; bu turda {} kova)",
                            unit.type(), ISO.format(s), written);
                    return pause;
                }
                Instant e = s.plus(SLICE_DAYS, ChronoUnit.DAYS);
                written += recomputeSlice(unit, s, e.isAfter(end) ? end : e, todayStart);
                cursor = new Cursor(u, e);
            }
        }
        writeMarker(written);
        cursor = null;
        log.info("Erişilebilirlik özetleri yeni sayım kuralıyla yeniden hesaplandı (elle / teyit satırı ve ICMP'nin ölçemediği "
                + "ping satırı paydada değil; bu turda {} kova) — tek seferlik iş tamam", written);
        return Outcome.DONE;
    }

    /** Veritabanı türü: TRUE PostgreSQL, FALSE başka, null okunamadı (test kancası). */
    Boolean postgres() {
        return DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
    }

    /** Bu tur bırakılmalı mı: kapanış, kilit kaybı ya da bütçe → PROGRESS (hata sayılmaz); null = sürdür. */
    private Outcome pauseReason(long started) {
        SchedulerService s = scheduler.getIfAvailable();
        if (s != null && s.sideJobsStopping()) return Outcome.PROGRESS;
        if (s != null && !s.schedulerLockHeld(LOCK)) return Outcome.PROGRESS;
        if (clock.getAsLong() - started > RUN_BUDGET_MS) return Outcome.PROGRESS;
        return null;
    }

    /** İlk TAM ham gün + güvenlik günü (UTC gün başı); ham satır yoksa null. */
    Instant firstSafeDay(String table) {
        String oldest = jdbc.execute((ConnectionCallback<String>) con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT MIN(checked_at) FROM " + table)) {
                ps.setQueryTimeout(STATEMENT_TIMEOUT_S);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        return firstSafeDayOf(oldest);
    }

    /** Saf kural: en eski ham damganın gününden {@code 1 + SAFETY_DAYS} gün sonrası; bozuk / boş damga null. */
    static Instant firstSafeDayOf(String oldest) {
        if (oldest == null || oldest.length() < 10) return null;
        try {
            return LocalDate.parse(oldest.substring(0, 10)).plusDays(1L + SAFETY_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Bir dilim: TEK işlemde günlük (yalnız tamamlanmış günler) ve saatlik özet satırlarını siler, üretim SQL'iyle yeniden
     * yazar. Hata → geri alınır ve istisna çağırana gider (tur başarısız sayılır, dilim bir saat sonra yeniden denenir).
     */
    long recomputeSlice(Unit unit, Instant s, Instant e, Instant todayStart) {
        String f = ISO.format(s);
        String t = ISO.format(e);
        boolean daily = s.isBefore(todayStart);
        String dailyTo = ISO.format(e.isAfter(todayStart) ? todayStart : e);
        Long n = jdbc.execute((ConnectionCallback<Long>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                long w = 0;
                if (daily) {
                    update(con, deleteSql(unit, DAILY_TABLE, DAILY_COL, 10),
                            unit.type(), f.substring(0, 10), dailyTo.substring(0, 10), f, dailyTo);
                    w += update(con, unit.dailySql(), f, dailyTo);
                }
                update(con, deleteSql(unit, SchedulerService.HOURLY_TABLE, SchedulerService.HOURLY_COL, 13),
                        unit.type(), f.substring(0, 13), t.substring(0, 13), f, t);
                w += update(con, unit.hourlySql(), f, t);
                con.commit();
                return w;
            } catch (SQLException | RuntimeException ex) {
                try { con.rollback(); } catch (SQLException ignore) { /* bağlantı zaten kopmuş olabilir */ }
                throw ex;
            } finally {
                try { con.setAutoCommit(auto); } catch (SQLException ignore) { /* havuz bağlantıyı sıfırlar */ }
            }
        });
        return n == null ? 0 : n;
    }

    /**
     * Dilimin özet satırlarını silen cümle — YALNIZ hâlâ ham satırı olan kovalar (bağımsız inceleme, H paketi): silinmiş
     * izlemenin (ham satırları da silinmiş) eski kovaları yeniden hesaplanamaz, dokunulmaz — geçmiş SLA sayısı kaymaz. Ham
     * satırı olup artık hiçbiri sayılmayan kova (yalnız ölçülemeyen ping) silinir ve yeniden yazılmaz (işin amacı). Parametre
     * sırası: tür, kova alt / üst sınırı, ham aralık alt / üst sınırı.
     */
    static String deleteSql(Unit unit, String table, String col, int len) {
        return "DELETE FROM " + table + " WHERE monitor_type = ? AND " + col + " >= ? AND " + col + " < ? AND (monitor_key, "
                + col + ") IN (SELECT " + unit.keyExpr() + ", substr(checked_at,1," + len + ") FROM " + unit.table()
                + " WHERE checked_at >= ? AND checked_at < ?)";
    }

    private static int update(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setQueryTimeout(STATEMENT_TIMEOUT_S);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    private void writeMarker(long written) {
        try {
            jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                    KEY, ISO.format(Instant.ofEpochMilli(clock.getAsLong())), (int) Math.min(written, Integer.MAX_VALUE));
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            log.debug("Erişilebilirlik özeti yeniden hesabı nişanı başka pod tarafından yazılmış");
        }
        markerSeen = true;
    }

    /** Test kancası: pod başına imleç. */
    Cursor cursor() { return cursor; }
}
