package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import com.sitemonitor.service.evalstate.EvalStateSql;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK tatil takvimi tohumu (2026-10-31, veritabanı sorgu izlemesi): {@code holiday_days} BOŞKEN Türkiye resmî
 * tatillerini yazar — "tatillerde çalışma" seçeneği ilk günden anlamlı olsun. Tablo doluysa (yönetici takvimi kendisi
 * kurmuş) hiçbir satıra dokunulmaz, yalnız nişan yazılır. 2026-11-15'ten beri veri kümesi 2026–2035'tir
 * ({@link HolidayCalendarDataset}); nişanı ÖNCEKİ sürümde yazılmış kurulumlara eksik yılları
 * {@link HolidayCalendarTenYearFill} ekler (bu tohumun nişanını bekler — ikisi yarışmaz).
 *
 * <p>CLAUDE.md "One-time DATA fixes" kuralı: açılış yolunda DEĞİL (açılıştan 3 dk sonra, sonra saatte bir, nişan yazılana
 * dek), kendi {@code scheduler_lock} kilidi altında; nişan ({@code schema_patch_markers}) kilit İÇİNDE yeniden okunur ve
 * YALNIZ başarıda yazılır; pod başına en çok {@value #MAX_ATTEMPTS} başarısız deneme.
 *
 * <p><b>Dini bayram tarihleri:</b> Ramazan ve Kurban bayramı günleri Diyanet İşleri Başkanlığı takviminden alınmıştır
 * (kaynak ve erişim tarihi {@link HolidayCalendarDataset}'te); resmî tatil kararnamesi farklı ilan ederse yönetici
 * Ayarlar → Platform → Tatil takviminden düzeltir. Arife yarım günü TAM gün sayılır (adında "arife (yarım gün)").
 */
@Slf4j
@Service
public class HolidayCalendarSeed {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ (değişirse boş tabloya yeniden tohum yazılabilir). */
    static final String KEY = "2026-10-31-holiday-calendar-seed";
    static final String LOCK = "holiday-calendar-seed";
    static final String ACTOR = "SiteMonitor";
    static final int MAX_ATTEMPTS = 24;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Tohum günleri (gün → ad): 2026–2035 resmî tatilleri, {@link HolidayCalendarDataset#DAYS} (kaynak ve erişim tarihi
     * orada). Aynı güne iki tatil düşerse (2027-05-19, 2029-04-23, 2033-01-01) tek satır, iki ad.
     */
    static final List<String[]> DAYS = HolidayCalendarDataset.DAYS;

    /** Her deyim için sorgu zaman aşımı (sn) — kilit bekleyen bir yazma işi süresiz takılmasın. */
    static final int QUERY_TIMEOUT_SECONDS = 15;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final HolidayCalendarService calendar;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean doneSeen;

    public HolidayCalendarSeed(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler, HolidayCalendarService calendar) {
        this.jdbc = EvalStateSql.withQueryTimeout(jdbc, QUERY_TIMEOUT_SECONDS);
        this.scheduler = scheduler;
        this.calendar = calendar;
    }

    @Scheduled(initialDelayString = "${site.monitor.dbquery.holiday-seed.initial-delay-ms:180000}", fixedDelay = 3_600_000L)
    public void run() {
        if (doneSeen || failures.get() >= MAX_ATTEMPTS) return;
        // Okunamayan nişan "bitti" SAYILMAZ (inceleme 2026-10-31, D6): eskiden tek bir geçici DB hatası doneSeen'i kalıcı
        // açıp bu pod'da tohumu bir daha hiç denetmiyordu. Bilinmiyor → bu tur atlanır, saat sonra yeniden bakılır.
        Boolean d = markerState();
        if (d == null) return;
        if (d) { doneSeen = true; return; }
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::seedLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Tatil takvimi tohumu {} denemede yazılamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Tatil takvimi tohumu yazılamadı (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /**
     * Nişan durumu — ÜÇ durumlu: {@code TRUE} yazılmış, {@code FALSE} yazılmamış, {@code null} okunamadı (bilinmiyor; çağıran
     * karar VERMEZ — ne "bitti" der ne tohum yazar).
     */
    Boolean markerState() {
        return markerState(jdbc, KEY);
    }

    /** Bir nişanın üç durumlu okuması ({@link HolidayCalendarTenYearFill} de kullanır): TRUE / FALSE / null = okunamadı. */
    static Boolean markerState(JdbcTemplate jdbc, String key) {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, key);
            return n != null && n > 0;
        } catch (RuntimeException e) {
            log.debug("Tatil takvimi nişanı okunamadı ({}): {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Takvime gün yazıldı — tatil kaydının ({@code HolidayCalendarController.save}) yaptığının aynısı: özet bayatlar (bu pod +
     * önbellek veri yolu {@code holidays}) ve tatile bakan zamanlamalı izlemelerin (veritabanı sorgusu, sentetik, Sayfa Hızı)
     * planı yeniden kurulur. Hiçbir adım fırlatmaz.
     */
    static void announceCalendarChange(HolidayCalendarService calendar, SchedulerService s) {
        try {
            calendar.invalidate();
        } catch (RuntimeException e) {
            log.warn("Tatil takvimi özeti bayatlatılamadı — 60 sn içinde kendiliğinden tazelenir: {}", e.getClass().getSimpleName());
        }
        if (s == null) return;
        try {
            s.dbQueryCalendarChanged();
        } catch (RuntimeException e) {
            log.warn("Tatil takvimi değişikliği sorgu izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                    e.getClass().getSimpleName());
        }
        try {
            s.scriptedCalendarChanged();
        } catch (RuntimeException e) {
            log.warn("Tatil takvimi değişikliği senaryo izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                    e.getClass().getSimpleName());
        }
        try {
            s.pageSpeedCalendarChanged();
        } catch (RuntimeException e) {
            log.warn("Tatil takvimi değişikliği Sayfa Hızı izlemelerinin planına işlenemedi — bir sonraki vade hesabında yenilenir: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Bir yılın günlerini TEK deyimle yazar — yalnız o yılın {@code holiday_days}'te HİÇ satırı yoksa (koşul deyimin içinde:
     * araya giren elle kayıt yılı korur). Yıl ya bütünüyle yazılır ya hiç (yarıda kesilen iş yarım yıl bırakmaz). Dönüş:
     * yazılan satır sayısı.
     */
    static int insertYearIfEmpty(JdbcTemplate jdbc, int year, List<String[]> rows, String now) {
        if (rows == null || rows.isEmpty()) return 0;
        StringBuilder sql = new StringBuilder("INSERT INTO holiday_days (holiday_date, name, created_at, created_by_name) "
                + "SELECT v.d, v.n, CAST(? AS VARCHAR(30)), CAST(? AS VARCHAR(255)) FROM (VALUES ");
        List<Object> args = new ArrayList<>();
        args.add(now);
        args.add(ACTOR);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(CAST(? AS VARCHAR(10)), CAST(? AS VARCHAR(120)))");
            args.add(rows.get(i)[0]);
            args.add(rows.get(i)[1]);
        }
        sql.append(") AS v(d, n) WHERE NOT EXISTS (SELECT 1 FROM holiday_days h WHERE h.holiday_date LIKE ?)");
        args.add(year + "-%");
        return jdbc.update(sql.toString(), args.toArray());
    }

    /** Kilit altında: tablo boşsa tohum günlerini yazar, sonra nişanı. Dönüş: yazılan gün sayısı (0 = tablo doluydu). */
    Integer seedLocked() {
        // Kilit içinde YENİDEN okunur; okunamazsa hata (deneme sayılır, saat sonra yeniden) — ne tohum ne nişan yazılır
        Boolean marker = markerState();
        if (marker == null) throw new IllegalStateException("tatil takvimi tohum nişanı okunamadı");
        if (marker) { doneSeen = true; return 0; }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM holiday_days", Integer.class);
        int written = 0;
        if (existing == null || existing == 0) {
            String now = ISO.format(Instant.now());
            // Yıl yıl, her yıl TEK deyim (yarıda kesilirse yarım yıl kalmaz; eksik yılları HolidayCalendarTenYearFill ekler)
            for (Map.Entry<Integer, List<String[]>> year : HolidayCalendarDataset.byYear().entrySet()) {
                written += insertYearIfEmpty(jdbc, year.getKey(), year.getValue(), now);
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), written);
        doneSeen = true;
        if (written > 0) {
            announceCalendarChange(calendar, scheduler.getIfAvailable());
            log.info("Tatil takvimi tohumlandı: {} gün ({}–{} resmî tatiller, Diyanet takvimi; resmî karar farklıysa "
                    + "Ayarlar → Platform → Tatil takviminden düzeltin)", written, HolidayCalendarDataset.FIRST_YEAR,
                    HolidayCalendarDataset.LAST_YEAR);
        } else {
            log.info("Tatil takvimi tohumu atlandı: takvim zaten dolu (yöneticinin kaydına dokunulmadı)");
        }
        return written;
    }
}
