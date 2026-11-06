package com.sitemonitor.service;

import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
        //    Envanter TUR BAŞINA BİR KEZ okunur (2026-11-06, performans PF3 #4): eskiden yazılan her registrable için tüm aktif
        //    envanter yeniden okunuyordu. Aynı okumadan "satırın registrable'ı → kimlikler" haritası da kurulur (yazma hedefi).
        Map<String, Instant> latestChecked = new HashMap<>();
        Map<String, String> currentExpiry = new HashMap<>();
        Map<String, List<Long>> idsByRowRegistrable = new LinkedHashMap<>();
        for (Object[] row : inventoryRepo.findDomainExpiryRows()) {   // aktif, silinmemiş, elle yüklenen sertifika HARİÇ (sorguda)
            Long id = row[0] == null ? null : ((Number) row[0]).longValue();
            String domain = (String) row[1];
            String expiryNow = (String) row[2];
            String checkedAtNow = (String) row[3];
            String rowReg = publicSuffixService.registrableDomain(domain);
            if (rowReg == null || rowReg.isBlank()) continue;
            if (id != null) idsByRowRegistrable.computeIfAbsent(rowReg, k -> new ArrayList<>()).add(id);
            String reg = rowReg.toLowerCase();
            Instant checked = parseIso(checkedAtNow);
            latestChecked.merge(reg, checked != null ? checked : Instant.EPOCH, (a, b) -> a.isAfter(b) ? a : b);
            if (expiryNow != null && !expiryNow.isBlank())
                currentExpiry.putIfAbsent(reg, expiryNow);
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
                    if (writeExpiry(reg, mv.expiry(), mv.registrar(), idsByRowRegistrable) > 0) reconciled++;
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
                    writeExpiry(objStr(data.get("registrable")), String.valueOf(expiry), objStr(data.get("registrar")),
                            idsByRowRegistrable);
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
     *
     * <p>2026-11-06 (performans PF3 #4): satırlar tek kolon projeksiyonla okunur ve YALNIZ süre bitişi kolonları hedefli
     * {@code UPDATE} ile yazılır ({@link CertificateInventoryRepository#updateDomainExpiry}, kendi işlemi). Eskiden tam
     * varlık okunup {@code save} ediliyordu: aradaki eşzamanlı envanter düzenlemesi bayat değerle geri yazılıyordu.
     * Hata yutulur (0 döner) — tazeleme turu bir alan adı yüzünden düşmez.
     */
    public int persistToInventory(String registrable, String expiry, String registrar) {
        if (registrable == null || registrable.isBlank() || expiry == null) return 0;
        try {
            Map<String, List<Long>> idsByRowRegistrable = new LinkedHashMap<>();
            for (Object[] row : inventoryRepo.findDomainExpiryRows()) {   // aktif, silinmemiş, elle yüklenen sertifika HARİÇ
                if (row[0] == null) continue;
                String rowReg = publicSuffixService.registrableDomain((String) row[1]);
                if (rowReg == null || rowReg.isBlank()) continue;
                idsByRowRegistrable.computeIfAbsent(rowReg, k -> new ArrayList<>()).add(((Number) row[0]).longValue());
            }
            return writeExpiry(registrable, expiry, registrar, idsByRowRegistrable);
        } catch (Exception e) {
            log.warn("Domain-expiry envantere yazılamadı ({}): {}", registrable, e.getMessage());
            return 0;
        }
    }

    /**
     * {@code registrable} ile eşleşen (büyük/küçük harf duyarsız — eski {@code equalsIgnoreCase} kuralı) satır kimliklerine
     * süre bitişini TEK hedefli {@code UPDATE} ile yazar. Hiç fırlatmaz; güncellenen satır sayısını döner.
     */
    private int writeExpiry(String registrable, String expiry, String registrar, Map<String, List<Long>> idsByRowRegistrable) {
        if (registrable == null || registrable.isBlank() || expiry == null) return 0;
        try {
            List<Long> ids = new ArrayList<>();
            for (Map.Entry<String, List<Long>> e : idsByRowRegistrable.entrySet()) {
                if (e.getKey().equalsIgnoreCase(registrable)) ids.addAll(e.getValue());
            }
            if (ids.isEmpty()) return 0;
            int updated = inventoryRepo.updateDomainExpiry(ids, expiry, registrar, ISO.format(Instant.now()));
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
