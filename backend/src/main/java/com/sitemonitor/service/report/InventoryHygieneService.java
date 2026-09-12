package com.sitemonitor.service.report;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.CertificateInventoryContacts;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Envanter HİJYENİ — aylık raporun "eksik / hatalı / güncel olmayan" bölümünü besler.
 *
 * <p>Dört bulgu grubu, hepsi MEVCUT veriden türetilir (yeni tablo yok):
 * <ol>
 *   <li><b>Envanter eksikleri</b> — takım veya kritiklik (tier) atanmamış kayıtlar. Takımsız kayıt
 *       alarm yönlendirmesini bozar; tier'sız kayıt önceliklendirmeyi.</li>
 *   <li><b>Kontrol edilmemiş / bayat</b> — aktif envanterde olup {@code latest_checks}'te karşılığı
 *       olmayan domainler, ve son kontrolü stale eşiğini aşanlar. İkisi de "izleniyor sanılıyor
 *       ama izlenmiyor" durumudur — sessiz kör nokta.</li>
 *   <li><b>Kontrol hatası</b> — {@code status=error}; hata metniyle birlikte.</li>
 *   <li><b>Sertifika sağlığı</b> — süresi dolmuş, iptal edilmiş (REVOKED), zincir kırık,
 *       dağıtım eksik (INCOMPLETE), zayıf imza/anahtar.</li>
 * </ol>
 *
 * <p>Rapor okuru için sıralama önemlidir: her grup kendi içinde en kritikten başlar ve mailde
 * grup başına yalnız ilk {@value #MAX_PER_GROUP} satır listelenir (tamamı CSV/PDF ekinde).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryHygieneService {

    /** Mailde grup başına gösterilecek en fazla satır — gerisi "+N daha" olarak özetlenir. */
    public static final int MAX_PER_GROUP = 10;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Rapor satırlarında gösterim saat dilimi — proje geneli kural (bkz. shortTime). */
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter SHORT_LOCAL = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final CertificateService certificateService;
    private final LatestCheckRepository latestRepo;
    private final AppSettingsService appSettings;

    /**
     * Tek bulgu: hangi domain, ne sorun. {@code codes} makine-okur kodlar (no_team, no_tier, no_contacts,
     * never_checked, stale, error, expired, revoked, chain, deployment, weak) — Envanter sayfası bunlarla
     * arayüz dilinde yazar; {@code detail} e-posta metni (Türkçe) olarak kalır.
     */
    public record Finding(String domain, String detail, List<String> codes) {
        public Finding(String domain, String detail) { this(domain, detail, List.of()); }
    }

    /** Bir grup bulgusu — başlık, toplam sayı ve kırpılmış örnekler. */
    public record Group(String key, String title, int total, List<Finding> samples) {
        public boolean isEmpty() { return total == 0; }
        public int hidden() { return Math.max(0, total - samples.size()); }
    }

    /** Rapor gövdesi için tüm hijyen sonucu. */
    public record Result(List<Group> groups, int totalFindings) {
        public boolean clean() { return totalFindings == 0; }
    }

    /**
     * @param rows silinmemiş envanter kayıtları (aktif + pasif) — sayımlar buradan
     */
    public Result analyze(List<CertificateInventory> rows) { return analyze(rows, MAX_PER_GROUP); }

    /**
     * @param cap grup başına örnek satır tavanı — e-posta {@link #MAX_PER_GROUP}, Envanter sayfası
     *            ({@code /admin/inventory/hygiene}) tamamını ister ({@code Integer.MAX_VALUE})
     */
    public Result analyze(List<CertificateInventory> rows, int cap) {
        List<CertificateInventory> active = rows.stream()
                .filter(r -> !Boolean.FALSE.equals(r.getActive()))
                .toList();

        // Canlı kontrol sonuçları: envanter × latest_checks join'i hazır servisten gelir.
        Map<String, CertificateDto> latest;
        try {
            latest = certificateService.getAllLatest().stream()
                    .filter(d -> d.getDomain() != null)
                    .collect(Collectors.toMap(CertificateDto::getDomain, d -> d, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Hijyen analizi: canlı kontrol sonuçları okunamadı: {}", e.getMessage());
            latest = Map.of();
        }

        List<Group> groups = new ArrayList<>();
        groups.add(missingInventoryFields(active, cap));
        groups.add(missingContacts(active, cap));
        groups.add(uncheckedOrStale(active, latest, cap));
        groups.add(checkErrors(latest, cap));
        groups.add(certificateHealth(latest, cap));

        int total = groups.stream().mapToInt(Group::total).sum();
        return new Result(groups.stream().filter(g -> !g.isEmpty()).toList(), total);
    }

    // ── 1) Envanter eksikleri ────────────────────────────────────────────────
    private Group missingInventoryFields(List<CertificateInventory> active, int cap) {
        List<Finding> f = new ArrayList<>();
        for (CertificateInventory r : active) {
            List<String> missing = new ArrayList<>(); List<String> codes = new ArrayList<>();
            if (r.getTeamId() == null) { missing.add("takım atanmamış"); codes.add("no_team"); }
            if (r.getTier() == null) { missing.add("kritiklik (tier) atanmamış"); codes.add("no_tier"); }
            if (!missing.isEmpty()) f.add(new Finding(r.getDomain(), String.join(", ", missing), codes));
        }
        return group("missing", "Envanter bilgisi eksik", f, cap);
    }

    /**
     * Sorumlu ekip bilgisi hiç girilmemiş kayıtlar — AYRI grup, bilinçli olarak.
     *
     * <p>{@code missing} grubuna eklenseydi, alan yeni geldiği için İLK GÜN envanterin TAMAMI
     * o gruba düşer ve gerçekten aksiyon isteyen "takım atanmamış / tier atanmamış" bulguları
     * gürültü içinde kaybolurdu. Ayrı grup, doldurma kampanyası ilerledikçe kendiliğinden küçülür
     * ve komşu sinyali hiç kirletmez.
     *
     * <p>DÖRDÜ birden boşsa bulgu; biri bile doluysa eksik saymayız — her sertifikanın dört ekiple
     * ilişkisi yok (WAF'ta durmayan, IIS'te çalışmayan kayıtlar var).
     */
    private Group missingContacts(List<CertificateInventory> active, int cap) {
        List<Finding> f = new ArrayList<>();
        for (CertificateInventory r : active) {
            if (CertificateInventoryContacts.filled(r).isEmpty()) {
                f.add(new Finding(r.getDomain(), "sorumlu ekip bilgisi girilmemiş", List.of("no_contacts")));
            }
        }
        return group("contacts", "Sorumlu ekip bilgisi eksik", f, cap);
    }

    // ── 2) Hiç kontrol edilmemiş / bayat kontrol ─────────────────────────────
    private Group uncheckedOrStale(List<CertificateInventory> active, Map<String, CertificateDto> latest, int cap) {
        int staleMinutes = Math.max(5, appSettings.getInt("site.monitor.scheduler.stale-minutes", 65));
        String cutoff = ISO.format(Instant.now().minus(staleMinutes, ChronoUnit.MINUTES));

        List<Finding> f = new ArrayList<>();
        for (CertificateInventory r : active) {
            CertificateDto d = latest.get(r.getDomain());
            if (d == null) {
                f.add(new Finding(r.getDomain(), "hiç kontrol edilmemiş — izleme sonucu yok", List.of("never_checked")));
            } else if (d.getCheckedAt() != null && d.getCheckedAt().compareTo(cutoff) < 0) {
                f.add(new Finding(r.getDomain(), "son kontrol " + shortTime(d.getCheckedAt())
                        + " (eşik: " + staleMinutes + " dk)", List.of("stale")));
            }
        }
        return group("stale", "Kontrol edilmemiş veya güncel olmayan", f, cap);
    }

    // ── 3) Kontrol hatası ────────────────────────────────────────────────────
    private Group checkErrors(Map<String, CertificateDto> latest, int cap) {
        List<Finding> f = latest.values().stream()
                .filter(d -> "error".equalsIgnoreCase(d.getStatus()))
                .sorted(java.util.Comparator.comparing(CertificateDto::getDomain,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .map(d -> new Finding(d.getDomain(),
                        d.getError() != null && !d.getError().isBlank() ? d.getError() : "kontrol edilemedi", List.of("error")))
                .collect(Collectors.toList());
        return group("error", "Kontrol hatası — erişilemeyen sertifika", f, cap);
    }

    // ── 4) Sertifika sağlığı ─────────────────────────────────────────────────
    private Group certificateHealth(Map<String, CertificateDto> latest, int cap) {
        Set<String> weak;
        try {
            weak = latestRepo.findWeakAlgorithmCandidates().stream()
                    .map(c -> c.getDomain()).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("Zayıf algoritma sorgusu başarısız: {}", e.getMessage());
            weak = Set.of();
        }

        List<Finding> f = new ArrayList<>();
        for (CertificateDto d : latest.values()) {
            List<String> issues = new ArrayList<>(); List<String> codes = new ArrayList<>();
            if (d.getDaysRemaining() != null && d.getDaysRemaining() < 0) {
                issues.add("SÜRESİ DOLMUŞ (" + Math.abs(d.getDaysRemaining()) + " gün önce)"); codes.add("expired");
            }
            if ("REVOKED".equalsIgnoreCase(d.getRevocationStatus())) { issues.add("iptal edilmiş (REVOKED)"); codes.add("revoked"); }
            if ("BROKEN".equalsIgnoreCase(d.getChainStatus())) { issues.add("sertifika zinciri kırık"); codes.add("chain"); }
            if ("INCOMPLETE".equalsIgnoreCase(d.getDeploymentStatus())) { issues.add("dağıtım eksik"); codes.add("deployment"); }
            if (weak.contains(d.getDomain())) { issues.add("zayıf imza/anahtar"); codes.add("weak"); }
            if (!issues.isEmpty()) f.add(new Finding(d.getDomain(), String.join(", ", issues), codes));
        }
        // Süresi dolmuşlar en üstte
        f.sort((a, b) -> Boolean.compare(b.detail().startsWith("SÜRESİ"), a.detail().startsWith("SÜRESİ")));
        return group("health", "Sertifika sağlığı sorunları", f, cap);
    }

    private static Group group(String key, String title, List<Finding> all, int cap) {
        return new Group(key, title, all.size(),
                all.size() > cap ? List.copyOf(all.subList(0, cap)) : List.copyOf(all));
    }

    /**
     * ISO (UTC) → "09.08 18:00" YEREL saatte (mail satırı kısa kalsın).
     *
     * <p><b>Saat dilimi çevrilmeli.</b> Zaman damgaları UTC saklanıyor; proje kuralı e-postalarda
     * DAİMA Europe/Istanbul göstermek ({@code EmailNotificationService.formatIso},
     * {@code EmailTemplateBuilder.formatHuman} aynı şeyi yapıyor). İlk sürüm UTC dizesini
     * dilimleyip olduğu gibi basıyordu: rapor "son kontrol 15:00" diyor, gerçekte 18:00'di.
     * Bu satırın tek işi "izleme bayat mı" sorusunu cevaplamak olduğu için üç saatlik kayma
     * doğrudan yanlış karara götürür.
     *
     * <p>Ayrıştırılamayan girdide ham değer döner — rapor bir biçim hatası yüzünden düşmez.
     */
    static String shortTime(String iso) {
        if (iso == null || iso.isBlank()) return String.valueOf(iso);
        try {
            String s = iso.trim();
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(
                    s.endsWith("Z") || s.matches(".*T.*[+-]\\d\\d:\\d\\d") ? s : s + "Z");
            return odt.atZoneSameInstant(IST).format(SHORT_LOCAL);
        } catch (Exception e) {
            return iso;
        }
    }

    /**
     * Aktif / pasif / toplam sayıları — mail KPI bandı.
     * Silinmiş kayıtlar KAPSAM DIŞI: sahibinden aksiyon beklenmeyen satırlar raporu gürültülendirir.
     */
    public Map<String, Integer> counts(List<CertificateInventory> notDeleted) {
        int active = (int) notDeleted.stream().filter(r -> !Boolean.FALSE.equals(r.getActive())).count();
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("passive", notDeleted.size() - active);
        m.put("total", notDeleted.size());
        return m;
    }
}
