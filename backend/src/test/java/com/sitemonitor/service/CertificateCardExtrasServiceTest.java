package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Genel Bakış kart zenginleştirmeleri (2026-09-19): 8 blok, tek geçiş; {@code now} enjekte (kayan pencere). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class CertificateCardExtrasServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-09-19T12:30:00Z");

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock UptimeCheckRepository uptimeCheckRepo;
    @Mock MaintenanceService maintenanceService;
    @Mock com.sitemonitor.repository.TeamRepository teamRepo;   // takım adı çözümü (2026-09-22, paylaşılan sertifika)
    @Mock CertificateHealthService healthService;
    CertificateCardExtrasService svc;

    private static String at(long minutesAgo) { return ISO.format(NOW.minus(Duration.ofMinutes(minutesAgo))); }
    private static CertificateInventory inv(String d, String plannedAt, String appDev) {
        CertificateInventory i = new CertificateInventory(); i.setDomain(d); i.setTeamId(1L); i.setActive(true);
        i.setRenewalPlannedAt(plannedAt); i.setRenewalPlannedByName("Ali"); i.setAppDevContact(appDev); return i;
    }
    private static LatestCheck lc(String d, String fp, String pinned, String san, String notBefore) {
        LatestCheck c = new LatestCheck(); c.setDomain(d); c.setFingerprint(fp); c.setPinnedFingerprint(pinned); c.setSan(san); c.setNotBefore(notBefore); return c;
    }
    private static CertificateHealthService.HealthRow row(String key, CertificateHealthRules.Status st) {
        return new CertificateHealthService.HealthRow(key, "certificate", st, "x", List.of(), "none", List.of(), Map.of());
    }

    @BeforeEach
    void setUp() {
        svc = new CertificateCardExtrasService(inventoryRepo, latestCheckRepo, alertEventRepo, uptimeCheckRepo, maintenanceService, teamRepo, healthService, null);
        when(healthService.thresholdResolution()).thenReturn(ThresholdResolution.fixed(null));   // tier bazlı çözüm (2026-09-20): 30/15/7
        when(healthService.evaluate(any(), any(), eq(false), anyInt(), anyInt())).thenReturn(new CertificateHealthService.HealthResult(List.of(), 0, 0));
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of());
        when(alertEventRepo.findLatestPerDomain()).thenReturn(List.of());
        when(uptimeCheckRepo.hourlyHttpOkSince(anyString())).thenReturn(List.of());
        when(uptimeCheckRepo.findLatestPerDomainPort()).thenReturn(List.of());
        when(maintenanceService.windowInfoByTarget(any())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("sağlık: FAIL satırlar (expiry hariç) + ok/evaluated; açık alarm: sayı, en yüksek seviye, hepsi onaylı mı, ilk olay")
    void healthAndAlerts() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.example.com", null, "dev@example.com")));
        LatestCheck a = lc("a.example.com", "AA", "AA", null, null);
        when(latestCheckRepo.findAll()).thenReturn(List.of(a));
        when(healthService.evaluate(eq(a), any(), eq(false), anyInt(), anyInt())).thenReturn(new CertificateHealthService.HealthResult(List.of(
                row("expiry", CertificateHealthRules.Status.FAIL), row("chain", CertificateHealthRules.Status.FAIL),
                row("trust", CertificateHealthRules.Status.OK), row("protocol", CertificateHealthRules.Status.UNKNOWN)), 1, 3));
        AlertEvent w = new AlertEvent(); w.setId(5L); w.setDomain("a.example.com"); w.setAlertLevel("WARNING"); w.setAlertType("EXPIRY"); w.setAcknowledged(true);
        AlertEvent c = new AlertEvent(); c.setId(6L); c.setDomain("a.example.com"); c.setAlertLevel("CRITICAL"); c.setAlertType("ACCESSIBILITY"); c.setAcknowledged(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(w, c));
        AlertEvent last = new AlertEvent(); last.setId(9L); last.setDomain("a.example.com"); last.setAlertLevel("HIGH"); last.setAlertType("HTTP_DOWN"); last.setResolved(true); last.setCreatedAt(at(300)); last.setResolvedAt(at(200));
        when(alertEventRepo.findLatestPerDomain()).thenReturn(List.of(last));

        Map<String, Object> x = svc.compute(NOW).get("a.example.com");
        assertThat((Map<String, Object>) x.get("last_alert")).containsEntry("id", 9L).containsEntry("resolved", true).containsEntry("type", "HTTP_DOWN").containsEntry("resolved_at", at(200));
        assertThat((Map<String, Object>) x.get("health")).containsEntry("ok", 1).containsEntry("evaluated", 3).containsEntry("failed", List.of("chain"));
        Map<String, Object> al = (Map<String, Object>) x.get("alerts");
        assertThat(al).containsEntry("count", 2).containsEntry("level", "CRITICAL").containsEntry("all_acked", false).containsEntry("first_id", 6L);
        assertThat((List<String>) al.get("types")).containsExactly("EXPIRY", "ACCESSIBILITY");
        assertThat((Map<String, Object>) x.get("contacts")).containsEntry("app_dev", "dev@example.com").containsEntry("missing", false);
    }

    @Test
    @DisplayName("erişilebilirlik: 24 sa yüzde, son kontrol, 24 saatlik kova dizisi (boş saat null)")
    void uptime() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.example.com", null, null)));
        when(latestCheckRepo.findAll()).thenReturn(List.of());
        String h0 = at(0).substring(0, 13), h1 = at(60).substring(0, 13);
        when(uptimeCheckRepo.hourlyHttpOkSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"a.example.com", h0, 4L, 4L}, new Object[]{"a.example.com", h1, 4L, 2L}));
        UptimeCheck u = new UptimeCheck(); u.setDomain("a.example.com"); u.setStatus("up"); u.setResponseMs(123L); u.setCheckedAt(at(2));
        when(uptimeCheckRepo.findLatestPerDomainPort()).thenReturn(List.of(u));

        Map<String, Object> up = (Map<String, Object>) svc.compute(NOW).get("a.example.com").get("uptime");
        assertThat(up).containsEntry("pct24", 75.0).containsEntry("checks24", 8L).containsEntry("last_status", "up").containsEntry("last_ms", 123L);
        List<Object> pts = (List<Object>) up.get("points");
        assertThat(pts).hasSize(24);
        assertThat(pts.get(23)).isEqualTo(100L); assertThat(pts.get(22)).isEqualTo(50L); assertThat(pts.get(0)).isNull();
        assertThat((Map<String, Object>) svc.compute(NOW).get("a.example.com").get("contacts")).containsEntry("missing", true);
    }

    @Test
    @DisplayName("değişim: pin uyuşmazlığı → mismatch; 7 gün içinde değişmiş ve onaysız → changed; onaylıysa/eski ise null. Yenileme planı: gecikmiş / tamamlanmış / bekliyor. Paylaşılan: aynı parmak izi + SAN")
    void changeRenewalShared() {
        String today = LocalDate.now(ZoneId.of("Europe/Istanbul")).toString();
        CertificateInventory a = inv("a.example.com", LocalDate.parse(today).minusDays(3).toString(), null);   // plan geçti, sertifika eski → gecikmiş
        CertificateInventory b = inv("b.example.com", LocalDate.parse(today).minusDays(3).toString(), null);   // plan geçti, sertifika plandan sonra → tamamlandı
        CertificateInventory c = inv("c.example.com", LocalDate.parse(today).plusDays(5).toString(), null);    // plan ileride
        CertificateInventory d = inv("d.example.com", null, null);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, b, c, d));
        LatestCheck la = lc("a.example.com", "AA", "BB", "[\"a.example.com\",\"www.a.example.com\",\"api.a.example.com\"]", "2026-01-01T00:00:00");   // mismatch
        LatestCheck lb = lc("b.example.com", "CC", "CC", null, LocalDate.parse(today).minusDays(1) + "T00:00:00");   // plandan sonra verildi
        lb.setFingerprintChangedAt(at(60 * 24 * 2)); lb.setFingerprintAckFingerprint("CC");                    // değişti ama onaylı → null
        LatestCheck lcc = lc("c.example.com", "DD", "DD", null, null);
        lcc.setFingerprintChangedAt(at(60 * 24 * 2));                                                          // değişti, onaysız → changed
        LatestCheck ld = lc("d.example.com", "DD", "DD", null, null);                                          // c ile aynı parmak izi
        ld.setFingerprintChangedAt(at(60 * 24 * 20));                                                          // 20 gün önce → eski, null
        when(latestCheckRepo.findAll()).thenReturn(List.of(la, lb, lcc, ld));

        Map<String, Map<String, Object>> all = svc.compute(NOW);
        assertThat((Map<String, Object>) all.get("a.example.com").get("change")).containsEntry("mismatch", true);
        assertThat(all.get("b.example.com").get("change")).isNull();
        assertThat((Map<String, Object>) all.get("c.example.com").get("change")).containsEntry("mismatch", false).containsEntry("acked", false);
        assertThat(all.get("d.example.com").get("change")).isNull();
        assertThat((Map<String, Object>) all.get("a.example.com").get("renewal")).containsEntry("overdue", true).containsEntry("done", false).containsEntry("by", "Ali");
        assertThat((Map<String, Object>) all.get("b.example.com").get("renewal")).containsEntry("overdue", false).containsEntry("done", true);
        assertThat((Map<String, Object>) all.get("c.example.com").get("renewal")).containsEntry("overdue", false).containsEntry("done", false);
        assertThat(all.get("d.example.com").get("renewal")).isNull();
        assertThat((Map<String, Object>) all.get("a.example.com").get("shared")).containsEntry("count", 0).containsEntry("san_count", 3);
        assertThat((Map<String, Object>) all.get("c.example.com").get("shared")).containsEntry("count", 1).containsEntry("domains", List.of("d.example.com"));
        assertThat(all.get("b.example.com").get("shared")).isNull();
    }

    @Test
    @DisplayName("bakım: hedefe özel pencere önce, yoksa 'tüm izlemeler' (*) penceresi; kapsam süzgeci forDomains")
    void maintenanceAndScope() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.example.com", null, null), inv("b.example.com", null, null)));
        when(latestCheckRepo.findAll()).thenReturn(List.of());
        when(maintenanceService.windowInfoByTarget(any())).thenReturn(Map.of(
                "a.example.com", Map.of("active", true, "until", at(-30), "name", "Gece"),
                "*", Map.of("active", false, "next_start", at(-120), "name", "Genel")));
        Map<String, Map<String, Object>> all = svc.compute(NOW);
        assertThat((Map<String, Object>) all.get("a.example.com").get("maintenance")).containsEntry("active", true).containsEntry("name", "Gece");
        assertThat((Map<String, Object>) all.get("b.example.com").get("maintenance")).containsEntry("active", false).containsEntry("name", "Genel");
        assertThat(svc.forDomains(Set.of("b.example.com"))).containsOnlyKeys("b.example.com");
        assertThat(svc.forDomains(null)).containsKeys("a.example.com", "b.example.com");
    }

    @Test
    @DisplayName("SAN sayımı: JSON listesi ve düz virgüllü metin")
    void sanCount() {
        assertThat(CertificateCardExtrasService.sanCount("[\"a\",\"b\"]")).isEqualTo(2);
        assertThat(CertificateCardExtrasService.sanCount("a.example.com, b.example.com c.example.com")).isEqualTo(3);
        assertThat(CertificateCardExtrasService.sanCount(null)).isZero();
    }

    @Test
    @DisplayName("kapsam: paylaşılan sertifika çipi kapsam DIŞI takımın alan adını sızdırmaz (varlık/sayı kalır)")
    void sharedNamesAreScoped() {
        // Aynı parmak izi iki farklı takımda: a=Takım 1 (kullanıcının kapsamı), z=Takım 2 (kapsam dışı).
        CertificateInventory a = inv("a.example.com", null, null);
        CertificateInventory z = inv("z.example.com", null, null); z.setTeamId(2L);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, z));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("a.example.com", "FP1", null, null, null),
                lc("z.example.com", "FP1", null, null, null)));

        // Global görüş: eş adı görünür — fixture'ın gerçekten eş ürettiğini kanıtlar.
        Map<String, Object> global = (Map<String, Object>) svc.forDomains(null).get("a.example.com").get("shared");
        assertThat(global).containsEntry("count", 1).containsEntry("domains", List.of("z.example.com"));

        // Kapsamlı görüş: sayı (varlık) korunur, AD sızmaz.
        Map<String, Object> scoped = (Map<String, Object>) svc.forDomains(Set.of("a.example.com")).get("a.example.com").get("shared");
        assertThat(scoped).containsEntry("count", 1);
        assertThat((List<String>) scoped.get("domains")).isEmpty();

        // Önbellekteki blok kirlenmemeli: kapsamlı çağrıdan SONRA global yine tam listeyi vermeli.
        Map<String, Object> againGlobal = (Map<String, Object>) svc.forDomains(null).get("a.example.com").get("shared");
        assertThat(againGlobal).containsEntry("domains", List.of("z.example.com"));
    }
}
