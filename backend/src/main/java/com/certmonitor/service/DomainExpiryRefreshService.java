package com.certmonitor.service;

import com.certmonitor.model.CertificateInventory;
import com.certmonitor.repository.CertificateInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code certificate_inventory.domain_expiry} (registrar/domain süre bitişi) cache'ini RDAP/WHOIS'ten TAZELER.
 *
 * <p>Bu kolon eskiden yalnız elle "Alan Adı Tanılama" çalıştırılınca yazılıyordu; haftalık rapor
 * ({@code WeeklyReportKpiService}) domain gün sayısını bu kolondan hesapladığı için domain yenilenince
 * rapor bayat kalıyordu (ör. kartfree yenilendi → 378 gün, ama rapor eski 06.08.2026'dan 13 gün). Bu servis
 * ({@link SchedulerService} günlük tetikler) aynı tanılama yolunu ({@link DomainExpiryDiagnosticsService})
 * tüm aktif envanter registrable domain'leri için çalıştırıp cache'i günceller → rapor taze okur.
 *
 * <p>Registrable-eşleşmeli yazma ({@link #persistToInventory}) burada tek kaynaktır; AdminController'daki
 * elle tanılama da buraya delege eder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainExpiryRefreshService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Bu süre içinde tazelenmiş registrable domain'i tekrar sorgulama (günlük iş + WHOIS yükünü sınırla). */
    private static final Duration FRESH_WINDOW = Duration.ofHours(20);

    private final DomainExpiryDiagnosticsService domainExpiryDiagnosticsService;
    private final CertificateInventoryRepository inventoryRepo;
    private final PublicSuffixService publicSuffixService;

    /**
     * Tüm aktif envanterin distinct registrable domain'lerini RDAP/WHOIS ile tazeler (son 20 saatte
     * tazelenenler atlanır). Domain başına best-effort — hata/başarısız tanılamada eski değer korunur.
     * Yenilenen registrable domain sayısını döner.
     */
    public int refreshAll() {
        // Tek geçişte distinct registrable → o registrable'ın EN YENİ checkedAt'i (tazelik guard'ı için).
        Map<String, Instant> latestChecked = new HashMap<>();
        for (CertificateInventory ci : inventoryRepo.findByActiveTrueOrderByDomainAsc()) {
            if (ci.getDeletedAt() != null) continue;
            String reg = publicSuffixService.registrableDomain(ci.getDomain());
            if (reg == null || reg.isBlank()) continue;
            Instant checked = parseIso(ci.getDomainExpiryCheckedAt());
            latestChecked.merge(reg.toLowerCase(), checked != null ? checked : Instant.EPOCH,
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        Instant now = Instant.now();
        int refreshed = 0, skipped = 0, failed = 0;
        for (Map.Entry<String, Instant> e : latestChecked.entrySet()) {
            if (Duration.between(e.getValue(), now).compareTo(FRESH_WINDOW) < 0) { skipped++; continue; }
            String reg = e.getKey();
            try {
                Map<String, Object> data = domainExpiryDiagnosticsService.diagnose(reg);
                String source = String.valueOf(data.get("source"));
                Object expiry = data.get("expiry_date");
                if (!"FAILED".equals(source) && expiry != null) {
                    persistToInventory(objStr(data.get("registrable")), String.valueOf(expiry), objStr(data.get("registrar")));
                    refreshed++;
                } else {
                    failed++;   // taze değer alınamadı → mevcut cache korunur
                }
            } catch (Exception ex) {
                failed++;
                log.debug("Domain-expiry tazeleme başarısız {}: {}", reg, ex.getMessage());
            }
        }
        log.info("Domain-expiry refresh: {} registrable; yenilenen={}, atlanan(taze)={}, başarısız={}",
                latestChecked.size(), refreshed, skipped, failed);
        return refreshed;
    }

    /**
     * Süre bitişini registrable'ı eşleşen aktif envanter satır(lar)ına yazar; güncellenen satır sayısını döner.
     * (Elle "Alan Adı Tanılama" da buraya delege eder — tek kaynak; eskiden AdminController'da private'dı.)
     */
    @Transactional
    public int persistToInventory(String registrable, String expiry, String registrar) {
        if (registrable == null || registrable.isBlank() || expiry == null) return 0;
        try {
            String now = ISO.format(Instant.now());
            int updated = 0;
            for (CertificateInventory ci : inventoryRepo.findByActiveTrueOrderByDomainAsc()) {
                if (ci.getDeletedAt() != null) continue;
                String rowReg = publicSuffixService.registrableDomain(ci.getDomain());
                if (rowReg != null && rowReg.equalsIgnoreCase(registrable)) {
                    ci.setDomainExpiry(expiry);
                    ci.setDomainRegistrar(registrar);
                    ci.setDomainExpiryCheckedAt(now);
                    inventoryRepo.save(ci);
                    updated++;
                }
            }
            if (updated > 0) log.info("Domain-expiry envantere yazıldı: {} → {} satır (expiry={})", registrable, updated, expiry);
            return updated;
        } catch (Exception e) {
            log.warn("Domain-expiry envantere yazılamadı ({}): {}", registrable, e.getMessage());
            return 0;
        }
    }

    private static Instant parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s).atZone(ZoneOffset.UTC).toInstant(); }
        catch (Exception e) {
            try { return Instant.parse(s); } catch (Exception ignore) { return null; }
        }
    }

    private static String objStr(Object o) { return o == null ? null : String.valueOf(o); }
}
