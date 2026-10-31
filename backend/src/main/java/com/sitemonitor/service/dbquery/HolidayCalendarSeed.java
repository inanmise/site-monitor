package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK tatil takvimi tohumu (2026-10-31, veritabanı sorgu izlemesi): {@code holiday_days} BOŞKEN 2026 ve 2027
 * Türkiye resmî tatillerini yazar — "tatillerde çalışma" seçeneği ilk günden anlamlı olsun. Tablo doluysa (yönetici takvimi
 * kendisi kurmuş) hiçbir satıra dokunulmaz, yalnız nişan yazılır.
 *
 * <p>CLAUDE.md "One-time DATA fixes" kuralı: açılış yolunda DEĞİL (açılıştan 3 dk sonra, sonra saatte bir, nişan yazılana
 * dek), kendi {@code scheduler_lock} kilidi altında; nişan ({@code schema_patch_markers}) kilit İÇİNDE yeniden okunur ve
 * YALNIZ başarıda yazılır; pod başına en çok {@value #MAX_ATTEMPTS} başarısız deneme.
 *
 * <p><b>Dini bayram tarihleri doğrulanmalı:</b> Ramazan ve Kurban bayramı günleri Diyanet İşleri Başkanlığı takviminden
 * alınmıştır (2026: Ramazan 20–22 Mart, Kurban 27–30 Mayıs; 2027: Ramazan 9–11 Mart, Kurban 16–19 Mayıs); resmî tatil
 * kararnamesi farklı ilan ederse yönetici Ayarlar → Platform → Tatil takviminden düzeltir. Arife yarım günü TAM gün sayılır
 * (adında "arife (yarım gün)").
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

    /** Tohum günleri (gün → ad). Aynı güne iki tatil düşerse (2027-05-19) tek satır, iki ad. */
    static final List<String[]> DAYS = List.of(
            new String[]{"2026-01-01", "Yılbaşı"},
            new String[]{"2026-03-19", "Ramazan Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2026-03-20", "Ramazan Bayramı 1. gün"},
            new String[]{"2026-03-21", "Ramazan Bayramı 2. gün"},
            new String[]{"2026-03-22", "Ramazan Bayramı 3. gün"},
            new String[]{"2026-04-23", "Ulusal Egemenlik ve Çocuk Bayramı"},
            new String[]{"2026-05-01", "Emek ve Dayanışma Günü"},
            new String[]{"2026-05-19", "Atatürk'ü Anma, Gençlik ve Spor Bayramı"},
            new String[]{"2026-05-26", "Kurban Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2026-05-27", "Kurban Bayramı 1. gün"},
            new String[]{"2026-05-28", "Kurban Bayramı 2. gün"},
            new String[]{"2026-05-29", "Kurban Bayramı 3. gün"},
            new String[]{"2026-05-30", "Kurban Bayramı 4. gün"},
            new String[]{"2026-07-15", "Demokrasi ve Milli Birlik Günü"},
            new String[]{"2026-08-30", "Zafer Bayramı"},
            new String[]{"2026-10-28", "Cumhuriyet Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2026-10-29", "Cumhuriyet Bayramı"},
            new String[]{"2027-01-01", "Yılbaşı"},
            new String[]{"2027-03-08", "Ramazan Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2027-03-09", "Ramazan Bayramı 1. gün"},
            new String[]{"2027-03-10", "Ramazan Bayramı 2. gün"},
            new String[]{"2027-03-11", "Ramazan Bayramı 3. gün"},
            new String[]{"2027-04-23", "Ulusal Egemenlik ve Çocuk Bayramı"},
            new String[]{"2027-05-01", "Emek ve Dayanışma Günü"},
            new String[]{"2027-05-15", "Kurban Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2027-05-16", "Kurban Bayramı 1. gün"},
            new String[]{"2027-05-17", "Kurban Bayramı 2. gün"},
            new String[]{"2027-05-18", "Kurban Bayramı 3. gün"},
            new String[]{"2027-05-19", "Kurban Bayramı 4. gün · Atatürk'ü Anma, Gençlik ve Spor Bayramı"},
            new String[]{"2027-07-15", "Demokrasi ve Milli Birlik Günü"},
            new String[]{"2027-08-30", "Zafer Bayramı"},
            new String[]{"2027-10-28", "Cumhuriyet Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2027-10-29", "Cumhuriyet Bayramı"});

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final HolidayCalendarService calendar;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean doneSeen;

    public HolidayCalendarSeed(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler, HolidayCalendarService calendar) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.calendar = calendar;
    }

    @Scheduled(initialDelayString = "${site.monitor.dbquery.holiday-seed.initial-delay-ms:180000}", fixedDelay = 3_600_000L)
    public void run() {
        if (doneSeen || failures.get() >= MAX_ATTEMPTS) return;
        if (done()) { doneSeen = true; return; }
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

    /** Nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar). */
    boolean done() {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
            return n != null && n > 0;
        } catch (RuntimeException e) {
            log.debug("Tatil takvimi tohum nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /** Kilit altında: tablo boşsa tohum günlerini yazar, sonra nişanı. Dönüş: yazılan gün sayısı (0 = tablo doluydu). */
    Integer seedLocked() {
        if (done()) return 0;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM holiday_days", Integer.class);
        int written = 0;
        if (existing == null || existing == 0) {
            String now = ISO.format(Instant.now());
            for (String[] d : DAYS) {
                // Koşullu ekleme: yarışta (elle kayıt) aynı gün çift satır üretmez — gün birincil anahtardır.
                written += jdbc.update("INSERT INTO holiday_days (holiday_date, name, created_at, created_by_name) "
                        + "SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM holiday_days WHERE holiday_date = ?)",
                        d[0], d[1], now, ACTOR, d[0]);
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), written);
        doneSeen = true;
        if (written > 0) {
            calendar.invalidate();
            log.info("Tatil takvimi tohumlandı: {} gün (2026–2027 resmî tatiller; dini bayram tarihleri Diyanet takvimiyle "
                    + "doğrulanmalı — Ayarlar → Platform → Tatil takvimi)", written);
        } else {
            log.info("Tatil takvimi tohumu atlandı: takvim zaten dolu (yöneticinin kaydına dokunulmadı)");
        }
        return written;
    }
}
