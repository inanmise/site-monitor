package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
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
import java.util.Optional;

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
    /** Canlı monitör değerine güven penceresi: son başarılı kontrol bundan eskiyse (monitör durmuş olabilir)
     *  monitöre güvenme, diagnose (canlı RDAP/WHOIS) yap. Domain monitörleri tipik olarak günlük kontrol eder. */
    private static final Duration MONITOR_TRUST_WINDOW = Duration.ofDays(2);

    private final DomainExpiryDiagnosticsService domainExpiryDiagnosticsService;
    private final CertificateInventoryRepository inventoryRepo;
    private final PublicSuffixService publicSuffixService;
    private final DomainMonitorRepository domainMonitorRepo;
    private final DomainCheckRepository checkRepo;

    /**
     * Tüm aktif envanterin distinct registrable domain'lerini RDAP/WHOIS ile tazeler (son 20 saatte
     * tazelenenler atlanır). Domain başına best-effort — hata/başarısız tanılamada eski değer korunur.
     * Yenilenen registrable domain sayısını döner.
     */
    public int refreshAll() {
        // 1) Envanterden distinct registrable → EN YENİ checkedAt (tazelik guard'ı) + mevcut cache değeri (değişti mi kontrolü).
        Map<String, Instant> latestChecked = new HashMap<>();
        Map<String, String> currentExpiry = new HashMap<>();
        for (CertificateInventory ci : inventoryRepo.findByActiveTrueOrderByDomainAsc()) {
            if (ci.getDeletedAt() != null) continue;
            String reg = publicSuffixService.registrableDomain(ci.getDomain());
            if (reg == null || reg.isBlank()) continue;
            reg = reg.toLowerCase();
            Instant checked = parseIso(ci.getDomainExpiryCheckedAt());
            latestChecked.merge(reg, checked != null ? checked : Instant.EPOCH, (a, b) -> a.isAfter(b) ? a : b);
            if (ci.getDomainExpiry() != null && !ci.getDomainExpiry().isBlank())
                currentExpiry.putIfAbsent(reg, ci.getDomainExpiry());
        }

        // 2) Canlı domain monitörlerinin son BAŞARILI kontrolünden registrable → expiry. Monitörler sık (canlı RDAP)
        //    kontrol ettiğinden envanter cache'i asla monitörden geri kalmaz — ve bu bedavadır (RDAP'ı yeniden çağırmaz).
        Map<String, MonitorExpiry> monitorMap = latestMonitorExpiryByRegistrable();

        Instant now = Instant.now();
        int refreshed = 0, reconciled = 0, skipped = 0, failed = 0;
        for (Map.Entry<String, Instant> e : latestChecked.entrySet()) {
            String reg = e.getKey();

            // 2a) Yeterince taze bir monitör değeri varsa onu kullan (canlı RDAP; envanter monitörle senkron kalır).
            MonitorExpiry mv = monitorMap.get(reg);
            if (mv != null && mv.checkedAt() != null
                    && Duration.between(mv.checkedAt(), now).compareTo(MONITOR_TRUST_WINDOW) < 0) {
                if (!sameDay(mv.expiry(), currentExpiry.get(reg))) {
                    if (persistToInventory(reg, mv.expiry(), mv.registrar()) > 0) reconciled++;
                }
                continue;   // monitör bu registrable'ı kapsıyor → RDAP/WHOIS diagnose'a gerek yok
            }

            // 2b) Monitör yok/bayat → mevcut fresh-window + diagnose (canlı RDAP/WHOIS) yolu.
            if (Duration.between(e.getValue(), now).compareTo(FRESH_WINDOW) < 0) { skipped++; continue; }
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
        log.info("Domain-expiry refresh: {} registrable; monitörden={}, RDAP/WHOIS={}, atlanan(taze)={}, başarısız={}",
                latestChecked.size(), reconciled, refreshed, skipped, failed);
        return refreshed + reconciled;
    }

    /** Aktif domain monitörlerinin son BAŞARILI (source≠NONE, expiry dolu) kontrolünden registrable → {expiry, registrar, checkedAt}.
     *  Aynı registrable'a birden çok monitör düşerse en taze kontrol kazanır. */
    private Map<String, MonitorExpiry> latestMonitorExpiryByRegistrable() {
        Map<String, MonitorExpiry> map = new HashMap<>();
        for (DomainMonitor dm : domainMonitorRepo.findByActiveTrue()) {
            String reg = publicSuffixService.registrableDomain(dm.getDomain());
            if (reg == null || reg.isBlank()) continue;
            reg = reg.toLowerCase();
            Optional<DomainCheck> latest = checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(dm.getId(), "NONE");
            if (latest.isEmpty()) continue;
            DomainCheck c = latest.get();
            if (c.getExpiryDate() == null || c.getExpiryDate().isBlank()) continue;
            Instant checked = parseIso(c.getCheckedAt());
            MonitorExpiry prev = map.get(reg);
            if (prev == null || prev.checkedAt() == null || (checked != null && checked.isAfter(prev.checkedAt())))
                map.put(reg, new MonitorExpiry(c.getExpiryDate(), c.getRegistrar(), checked));
        }
        return map;
    }

    private record MonitorExpiry(String expiry, String registrar, Instant checkedAt) {}

    /** İki tarih string'inin ilk 10 karakteri (yyyy-MM-dd) aynı mı — datetime vs date format farkını yut. */
    private static boolean sameDay(String a, String b) {
        return a != null && b != null && a.length() >= 10 && b.length() >= 10
                && a.substring(0, 10).equals(b.substring(0, 10));
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
