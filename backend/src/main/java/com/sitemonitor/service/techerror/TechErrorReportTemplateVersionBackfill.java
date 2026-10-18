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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK geri doldurma (2026-10-18, D19 — "raporda kopyalanan şablon sürümü → sürüm penceresinde 'bu sürümle N
 * rapor'"): {@code tech_error_reports.template_version} sütunundan ÖNCE açılmış raporların sürümü, raporun KENDİ
 * geçmişinden bulunur — açılış ({@code CREATE}) ve "şablonun son hâlini uygula" ({@code TEMPLATE_REFRESH}) olayları
 * ayrıntılarında kopyalanan kanal şablonu sürümünü ({@code template_version}) 2026-10-15'ten beri taşır. Rapor başına EN
 * YENİ böyle olayın sürümü yazılır (budama en eskiyi siler: kalan en yeni olay her zaman en son kopyadır). Olayı olmayan
 * rapor (geçmiş özelliğinden önce açılmış, hiç güncellenmemiş) {@code null} = "bilinmiyor" kalır — TAHMİN EDİLMEZ.
 *
 * <p>Yazım koşulludur ({@code WHERE template_version IS NULL}, sürüm artmaz): arada "şablonun son hâlini uygula" ya da yeni
 * bir pod sürümü yazdıysa o kazanır. Açılış yolunda değil: açılıştan 10 dk sonra (yayılım penceresi — ESKİ pod bu arada
 * sürümsüz rapor açabilir, olayı sürümü taşır, bu iş onu da bulur), kendi {@code scheduler_lock}'u altında; nişan kilit
 * İÇİNDE yeniden okunur ve YALNIZ tüm raporlar tarandıysa yazılır. Süre bütçesi ({@link #BUDGET_MS}) dolarsa nişan
 * yazılmaz, bir saat sonra kalan (hâlâ boş) satırlar yeniden denenir. Pod başına en çok {@link #MAX_ATTEMPTS} başarısız
 * deneme, sonra tek ERROR ve pod bırakır. Nişan yazıldıktan sonra eski bir podun açtığı sürümsüz rapor "bilinmiyor" kalır.
 */
@Slf4j
@Service
public class TechErrorReportTemplateVersionBackfill {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-10-18-tech-error-report-template-version";
    static final String LOCK = "tech-error-report-template-version-backfill";
    static final int MAX_ATTEMPTS = 24;
    static final int BATCH = 200;
    /** Tek koşunun süre bütçesi: kilit süresinin (10 dk) çok altında kalsın. */
    static final long BUDGET_MS = 4 * 60_000L;
    /** Sayfa sayısı tavanı (sonsuz döngü yok — kimlik kümesi her sayfada ilerler, bu yalnız güvence). */
    static final int MAX_PAGES = 100_000;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TechErrorReportRepository reports;
    private final TechErrorReportEventRepository events;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();
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

    /** Kilit altında: tüm sürümsüz raporları tarar; hepsi tarandıysa nişanı yazar. Doldurulan rapor sayısını döner. */
    Integer backfillLocked() {
        if (done()) return 0;
        long started = clock.getAsLong();
        long after = 0L;
        int filled = 0;
        int unknown = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Long> ids = reports.idsWithoutTemplateVersion(after, PageRequest.of(0, BATCH));
            if (ids.isEmpty()) {
                jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                        KEY, ISO.format(Instant.now()), filled);
                log.info("Teknik hata raporu şablon sürümü geri doldurması tamamlandı: {} rapor dolduruldu, {} rapor bilinmiyor (geçmiş olayı yok)",
                        filled, unknown);
                return filled;
            }
            Map<Long, Integer> found = latestVersions(ids);
            for (Long id : ids) {
                Integer v = found.get(id);
                if (v == null) { unknown++; continue; }
                filled += reports.fillTemplateVersion(id, v);
            }
            after = ids.get(ids.size() - 1);
            if (clock.getAsLong() - started > BUDGET_MS) {
                log.info("Teknik hata raporu şablon sürümü geri doldurması süre bütçesini doldurdu ({} rapor dolduruldu); kalanlar bir saat sonra",
                        filled);
                return filled;
            }
        }
        log.warn("Teknik hata raporu şablon sürümü geri doldurması sayfa tavanına ulaştı ({}); nişan yazılmadı", MAX_PAGES);
        return filled;
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
