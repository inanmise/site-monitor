package com.sitemonitor.service.techerror;

import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK geri doldurma (2026-10-18, D19 — "raporda kopyalanan şablon sürümü → sürüm penceresinde 'bu sürümle N
 * rapor'"): {@code tech_error_reports.template_version} sütunundan ÖNCE açılmış raporların sürümü, raporun KENDİ
 * geçmişinden bulunur — açılış ({@code CREATE}) ve "şablonun son hâlini uygula" ({@code TEMPLATE_REFRESH}) olayları
 * ayrıntılarında kopyalanan kanal şablonu sürümünü ({@code template_version}) 2026-10-15'ten beri taşır. Rapor başına EN
 * YENİ böyle olayın sürümü esas alınır (budama en eskiyi siler: kalan en yeni olay her zaman en son kopyadır). Olayı
 * olmayan ya da en yeni olayının ayrıntısı okunamayan rapor DOKUNULMADAN kalır (boşsa "bilinmiyor") — TAHMİN EDİLMEZ.
 *
 * <p><b>Bayat sürüm de düzeltilir (v2, 2026-10-18 inceleme):</b> yayılım sırasında ESKİ bir pod "şablonun son hâlini
 * uygula"yı çalıştırırsa sütunu tanımaz — rapor yeni şablonu alır, olay yeni sürümü yazar ama sütun eski sürümde kalır (boş
 * değil, v1 geri doldurması onu görmezdi). Yeni podda sütun ve olay AYNI işlemde yazılır, yani en yeni şablon olayı her
 * zaman doğrudur: sütun ondan farklıysa en yeni olayın sürümü yazılır. Yazım koşulludur — boş sütun
 * {@code WHERE template_version IS NULL}, dolu sütun {@code WHERE template_version = <okunan>} (sürüm artmaz): arada yeni
 * bir pod sürüm yazdıysa o kazanır.
 *
 * <p>Açılış yolunda değil: açılıştan 10 dk sonra (yayılım penceresi — ESKİ pod bu arada sürümsüz rapor açabilir ya da
 * şablon uygulayabilir, olayı sürümü taşır, bu iş onu da bulur), kendi {@code scheduler_lock}'u altında; nişan kilit İÇİNDE
 * yeniden okunur ve YALNIZ tüm raporlar tarandıysa yazılır. Süre bütçesi ({@link #BUDGET_MS}) dolarsa nişan yazılmaz, bir
 * saat sonra tarama kaldığı kimlikten sürer (pod başına imleç). Pod başına en çok {@link #MAX_ATTEMPTS} başarısız deneme,
 * sonra tek ERROR ve pod bırakır. Nişan yazıldıktan sonra eski bir podun açtığı / güncellediği rapor düzeltilmez (geri alma
 * notu).
 */
@Slf4j
@Service
public class TechErrorReportTemplateVersionBackfill {

    /**
     * Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. {@code -v2}: bayat sürümleri de düzelten tarama (yayınlanmamış ilk
     * yapının {@code 2026-10-18-tech-error-report-template-version} nişanı yalnız boşları dolduruyordu; o nişanı taşıyan bir
     * geliştirme veritabanı da bu taramayı bir kez koşar).
     */
    static final String KEY = "2026-10-18-tech-error-report-template-version-v2";
    static final String LOCK = "tech-error-report-template-version-backfill";
    static final int MAX_ATTEMPTS = 24;
    static final int BATCH = 200;
    /** Tek koşunun süre bütçesi: kilit süresinin (10 dk) çok altında kalsın. */
    static final long BUDGET_MS = 4 * 60_000L;
    /** Sayfa sayısı tavanı (sonsuz döngü yok — kimlik her sayfada ilerler, bu yalnız güvence). */
    static final int MAX_PAGES = 100_000;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TechErrorReportRepository reports;
    private final TechErrorReportEventRepository events;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();
    /** Bütçe dolunca taramanın kaldığı kimlik (pod başına; nişan yazılınca sıfırlanır). */
    final java.util.concurrent.atomic.AtomicLong resumeAfter = new java.util.concurrent.atomic.AtomicLong();
    /** Testler için saat (bütçe). */
    java.util.function.LongSupplier clock = System::currentTimeMillis;

    public TechErrorReportTemplateVersionBackfill(TechErrorReportRepository reports, TechErrorReportEventRepository events,
                                                   JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler) {
        this.reports = reports;
        this.events = events;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.template-version-backfill.initial-delay-ms:600000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::backfillLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Teknik hata raporu şablon sürümü geri doldurması {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu şablon sürümü geri doldurması başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Nişan var mı: true / false; okunamazsa null ("sonra yeniden dene" — asla "yapıldı" sayılmaz). */
    Boolean marker() {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
            return n != null && n > 0;
        } catch (RuntimeException e) {
            log.debug("Şablon sürümü geri doldurma nişanı okunamadı: {}", e.getMessage());
            return null;
        }
    }

    boolean done() {
        return !Boolean.FALSE.equals(marker());
    }

    /** Tek taramanın sonucu: boş sütunu doldurulan + bayat sürümü düzeltilen rapor sayısı. */
    record Outcome(int filled, int corrected) {
        int total() { return filled + corrected; }
    }

    /**
     * Kilit altında: tüm raporları tarar (boş sürüm → doldur, en yeni şablon olayından farklı sürüm → düzelt); hepsi
     * tarandıysa nişanı yazar. Doldurulan + düzeltilen rapor sayısını döner.
     */
    Integer backfillLocked() {
        return scanLocked().total();
    }

    Outcome scanLocked() {
        if (done()) return new Outcome(0, 0);
        long started = clock.getAsLong();
        long after = resumeAfter.get();
        int filled = 0;
        int corrected = 0;
        int unknown = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Object[]> rows = reports.idsAndTemplateVersions(after, PageRequest.of(0, BATCH));
            if (rows.isEmpty()) {
                jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                        KEY, ISO.format(Instant.now()), filled + corrected);
                resumeAfter.set(0L);
                log.info("Teknik hata raporu şablon sürümü geri doldurması tamamlandı: {} rapor dolduruldu, {} bayat sürüm düzeltildi, {} rapor bilinmiyor (geçmiş olayı yok)",
                        filled, corrected, unknown);
                return new Outcome(filled, corrected);
            }
            List<Long> ids = new ArrayList<>(rows.size());
            for (Object[] row : rows) ids.add(((Number) row[0]).longValue());
            Map<Long, Integer> found = latestVersions(ids);
            for (Object[] row : rows) {
                Long id = ((Number) row[0]).longValue();
                Integer stored = row[1] == null ? null : Integer.valueOf(((Number) row[1]).intValue());
                Integer v = found.get(id);
                if (v == null) {
                    if (stored == null) unknown++;
                    continue;                                   // olay yok / okunamıyor: dokunulmaz (tahmin yok)
                }
                if (stored == null) filled += reports.fillTemplateVersion(id, v);
                else if (!stored.equals(v)) corrected += reports.replaceTemplateVersion(id, stored, v);
            }
            after = ((Number) rows.get(rows.size() - 1)[0]).longValue();
            if (clock.getAsLong() - started > BUDGET_MS) {
                resumeAfter.set(after);
                log.info("Teknik hata raporu şablon sürümü geri doldurması süre bütçesini doldurdu ({} dolduruldu, {} düzeltildi); kalanlar bir saat sonra #{} sonrasından",
                        filled, corrected, after);
                return new Outcome(filled, corrected);
            }
        }
        resumeAfter.set(after);
        log.warn("Teknik hata raporu şablon sürümü geri doldurması sayfa tavanına ulaştı ({}); nişan yazılmadı", MAX_PAGES);
        return new Outcome(filled, corrected);
    }

    /** Rapor → en yeni CREATE / TEMPLATE_REFRESH olayındaki {@code template_version} (yoksa ya da okunamazsa yok). */
    Map<Long, Integer> latestVersions(List<Long> reportIds) {
        Map<Long, Integer> out = new HashMap<>();
        Map<Long, Long> newest = new HashMap<>();
        for (Object[] row : events.templateEventsFor(reportIds)) {
            Long reportId = ((Number) row[0]).longValue();
            Long eventId = ((Number) row[1]).longValue();
            Long seen = newest.get(reportId);
            if (seen != null && seen >= eventId) continue;     // rapor içinde en yeni olay kazanır (sıra sorguda da azalan)
            newest.put(reportId, eventId);
            out.remove(reportId);
            Integer v = versionOf(row[2] == null ? null : String.valueOf(row[2]));
            if (v != null) out.put(reportId, v);
        }
        return out;
    }

    /** Olay ayrıntısından sürüm; pozitif tam sayı değilse null (tahmin yok). */
    static Integer versionOf(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        try {
            Object raw = JSON.readValue(detailJson, Object.class);
            if (raw instanceof Map<?, ?> m && m.get("template_version") instanceof Number n) {
                long v = n.longValue();
                if (v >= 1 && v <= Integer.MAX_VALUE && n.doubleValue() == v) return (int) v;
            }
        } catch (RuntimeException ignored) {
            // okunamayan ayrıntı = bilinmiyor
        }
        return null;
    }
}
