package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.CertificateNote;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.CertificateNoteRepository;
import com.sitemonitor.repository.CertificateNoteRevisionRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Envanter çöp kutusu otomatik boşaltma (2026-09-12, envanter #10).
 *
 * <p>Ayar {@value #KEY} (gün; 0 = kapalı, bugünkü davranış: yalnız elle purge). Yumuşak silinmiş
 * kayıtlar {@code deleted_at} bu eşikten eskiyse kontrol geçmişi, latest_checks satırı ve notlarıyla
 * birlikte KALICI silinir — {@code AdminController.purgeAllDeleted} ile aynı zincir, aktör sistem.
 * Retention kataloğuna ham DELETE politikası olarak eklenmedi: çocuk tablolar (checks/notes) orada
 * öksüz kalırdı; bu servis tek işlemde hepsini kaldırır.
 *
 * <p>Günlük 04:40 Europe/Istanbul (kardeş gece işleri 03:20 auto-pin, 04:15 domain-expiry, gece
 * temizlik); HA kilidi {@link SchedulerService#tryAcquireSchedulerLock} ile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAutoPurgeService {

    public static final String KEY = "site.monitor.inventory.auto-purge-days";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppSettingsService appSettings;
    private final CertificateInventoryRepository inventoryRepo;
    private final CertificateCheckRepository certificateCheckRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final CertificateNoteRepository noteRepo;
    private final CertificateNoteRevisionRepository noteRevisionRepo;
    private final AuditService auditService;
    private final SchedulerService schedulerService;

    public record Result(int days, int purged, int checksDeleted, List<String> domains) {}

    @Scheduled(cron = "${site.monitor.inventory.auto-purge-cron:0 40 4 * * *}", zone = "Europe/Istanbul")
    public void scheduled() {
        int days = appSettings.getInt(KEY, 0);
        if (days <= 0) return;
        if (!schedulerService.tryAcquireSchedulerLock("inventory-auto-purge", 30)) {
            log.debug("Envanter otomatik purge — kilit başka pod'da, atlanıyor");
            return;
        }
        try {
            Result r = purgeOlderThan(days);
            if (r.purged() > 0) log.info("Envanter çöp kutusu boşaltıldı: {} kayıt, {} kontrol satırı (eşik {} gün)", r.purged(), r.checksDeleted(), days);
        } catch (Exception e) {
            log.warn("Envanter otomatik purge başarısız: {}", e.toString());
        }
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @Transactional
    public Result purgeOlderThan(int days) {
        String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
        int purged = 0, checks = 0;
        List<String> domains = new ArrayList<>();
        for (CertificateInventory inv : inventoryRepo.findByDeletedAtIsNotNullOrderByDomainAsc()) {
            if (inv.getDeletedAt() == null || inv.getDeletedAt().compareTo(cutoff) >= 0) continue;
            String domain = inv.getDomain();
            checks += certificateCheckRepo.deleteByDomain(domain);
            latestCheckRepo.findById(domain).ifPresent(latestCheckRepo::delete);
            List<CertificateNote> notes = noteRepo.findByDomainOrderByCreatedAtDesc(domain);
            if (notes != null && !notes.isEmpty()) {
                List<Long> ids = notes.stream().map(CertificateNote::getId).filter(Objects::nonNull).toList();
                if (!ids.isEmpty()) noteRevisionRepo.deleteByNoteIdIn(ids);
                noteRepo.deleteAll(notes);
            }
            inventoryRepo.delete(inv);
            purged++; domains.add(domain);
        }
        if (purged > 0) {
            auditService.recordSystemEvent("DOMAIN_AUTO_PURGE", "CERTIFICATE", purged + " domain",
                    "{\"days\":" + days + ",\"purged\":" + purged + ",\"checksDeleted\":" + checks
                            + ",\"domains\":" + jsonArray(domains) + "}");
        }
        return new Result(days, purged, checks, domains);
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
