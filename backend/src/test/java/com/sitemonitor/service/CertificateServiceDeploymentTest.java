package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Dağıtım hükmü ve beklenen parmak izi yaşam döngüsü (H paketi 2026-11-26, sertifika B2) + plan kapanışı (B5) —
 * {@link CertificateService#saveResult}.
 */
class CertificateServiceDeploymentTest {

    private static final String FP_X = "AB".repeat(32);
    private static final String FP_Z = "CD".repeat(32);

    private final CertificateCheckRepository checkRepo = mock(CertificateCheckRepository.class);
    private final LatestCheckRepository latestRepo = mock(LatestCheckRepository.class);
    private final CertificateCheckerService checker = mock(CertificateCheckerService.class);
    private final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final MonitorHistoryService history = mock(MonitorHistoryService.class);
    private final CertRenewalPlanService plans = mock(CertRenewalPlanService.class);
    private CertificateService service;

    @BeforeEach
    void setUp() {
        service = new CertificateService(checkRepo, latestRepo, checker, mock(MaintenanceService.class), inventoryRepo,
                new ObjectMapper(), mock(TeamRepository.class), mock(AlertThresholdRepository.class), activity);
        service.setMonitorHistory(history);
        service.setRenewalPlans(plans);
        when(checker.serializeSan(any())).thenReturn("[]");
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.findById(anyString())).thenReturn(Optional.empty());
        when(checkRepo.firstSightingOf(anyString(), anyCollection(), anyString())).thenReturn(List.of());
    }

    private CertificateInventory inv(String domain, String expected) {
        CertificateInventory i = new CertificateInventory();
        i.setId(11L);
        i.setDomain(domain);
        i.setTeamId(4L);
        i.setExpectedFingerprint(expected);
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.of(i));
        return i;
    }

    private static Map<String, Object> result(String domain, String fp, String notBefore, String notAfter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("fingerprint", fp);
        m.put("not_before", notBefore);
        m.put("not_after", notAfter);
        m.put("status", "valid");
        m.put("warning", false);
        m.put("san", List.of());
        m.put("run_id", "run-1");
        m.put("checked_at", "2026-11-26T09:00:00");
        return m;
    }

    private String savedDeployment() {
        ArgumentCaptor<LatestCheck> cap = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(cap.capture());
        return cap.getValue().getDeploymentStatus();
    }

    @Test
    @DisplayName("sunulan = beklenen (ayraçlı / küçük harf yazım) → OK + beklenen koşullu temizlenir + geçmiş + aktivite; tek envanter okuması")
    void completedDeploymentClearsExpected() {
        String typed = "ab:".repeat(31) + "ab";
        inv("d.example.com", typed);
        when(inventoryRepo.clearExpectedFingerprintIfUnchanged(11L, typed)).thenReturn(1);
        Map<String, Object> r = result("d.example.com", FP_X, "2026-11-01T00:00:00", "2027-11-01T00:00:00");
        service.saveResult(r);
        assertThat(r.get("deployment_status")).isEqualTo("OK");
        assertThat(savedDeployment()).isEqualTo("OK");
        verify(inventoryRepo).clearExpectedFingerprintIfUnchanged(11L, typed);
        verify(history).record(eq(MonitorHistoryService.INVENTORY), eq(11L), eq("d.example.com"), eq(4L),
                eq(MonitorHistoryService.UPDATE), anyMap(), anyMap(), contains("dağıtım tamamlandı"), isNull());
        verify(activity).recordLifecycle(eq(ActivityLogService.CERT), eq(11L), eq("d.example.com"), eq("d.example.com"), eq(4L),
                eq(ActivityLogService.ACTION_CONFIG_CHANGED), eq("scheduler"), contains("dağıtım tamamlandı"));
        verify(inventoryRepo, times(1)).findByDomain("d.example.com");
        verify(checkRepo, never()).firstSightingOf(anyString(), anyCollection(), anyString());
    }

    @Test
    @DisplayName("beklenen arada formdan değiştirildi: koşullu temizlik 0 satır → geçmiş / aktivite yazılmaz")
    void clearLostToConcurrentEditWritesNothing() {
        inv("d.example.com", FP_X);
        when(inventoryRepo.clearExpectedFingerprintIfUnchanged(anyLong(), anyString())).thenReturn(0);
        service.saveResult(result("d.example.com", FP_X, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        verify(history, never()).record(anyString(), any(), any(), any(), anyString(), any(), any(), any(), any());
        verify(activity, never()).recordLifecycle(anyString(), any(), any(), any(), any(),
                eq(ActivityLogService.ACTION_CONFIG_CHANGED), anyString(), anyString());
    }

    @Test
    @DisplayName("sunulan ≠ beklenen, kanıt yok → INCOMPLETE (temkinli), temizlenmez")
    void noEvidenceStaysIncomplete() {
        inv("d.example.com", FP_X);
        Map<String, Object> r = result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00");
        service.saveResult(r);
        assertThat(r.get("deployment_status")).isEqualTo("INCOMPLETE");
        verify(inventoryRepo, never()).clearExpectedFingerprintIfUnchanged(anyLong(), anyString());
    }

    @Test
    @DisplayName("inceleme: ilk görülme taraması son 400 günle sınırlı; 'hiç görülmedi' cevabı 6 sa bellekte — her kontrol aynı "
            + "boş taramayı yapmaz; süre dolunca / başka beklenen parmak izinde yeniden sorulur")
    void neverSeenAnswerIsMemoisedAndWindowed() {
        long[] now = {1_000_000_000L};
        service.neverSeenClock = () -> now[0];
        inv("d.example.com", FP_X);
        service.saveResult(result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        service.saveResult(result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(checkRepo, times(1)).firstSightingOf(eq("d.example.com"), anyCollection(), from.capture());
        String expectedFrom = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now().minus(CertificateService.FIRST_SIGHTING_WINDOW_DAYS, java.time.temporal.ChronoUnit.DAYS));
        assertThat(from.getValue()).startsWith(expectedFrom.substring(0, 7));   // ay düzeyi: gün sınırı yarışı olmasın

        now[0] += CertificateService.NEVER_SEEN_TTL_MS;   // süre doldu → yeniden sorulur
        service.saveResult(result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        verify(checkRepo, times(2)).firstSightingOf(eq("d.example.com"), anyCollection(), anyString());

        inv("d.example.com", "CC".repeat(32));            // beklenen değişti → ayrı anahtar, hemen sorulur
        service.saveResult(result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        verify(checkRepo, times(3)).firstSightingOf(eq("d.example.com"), anyCollection(), anyString());
    }

    @Test
    @DisplayName("takılı eski beklenen: beklenen bu uçta görülmüş, sunulan ondan SONRA verilmiş ve daha uzun geçerli → OK + temizle")
    void staleExpectedFromHistoryIsCleared() {
        inv("d.example.com", FP_X);
        when(checkRepo.firstSightingOf(eq("d.example.com"), argThat((Collection<String> c) -> c.contains(FP_X)), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"2025-11-01T00:00:00", "2026-11-30T00:00:00"}));
        when(inventoryRepo.clearExpectedFingerprintIfUnchanged(11L, FP_X)).thenReturn(1);
        Map<String, Object> r = result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-30T00:00:00");
        service.saveResult(r);
        assertThat(r.get("deployment_status")).isEqualTo("OK");
        verify(history).record(eq(MonitorHistoryService.INVENTORY), eq(11L), any(), any(), any(), anyMap(), anyMap(),
                contains("daha yenisiyle"), isNull());
    }

    @Test
    @DisplayName("beklenen görülmüş ama sunulan ondan ÖNCE verilmiş (geri dönüş) ya da daha kısa geçerli → INCOMPLETE")
    void olderOrShorterServedStaysIncomplete() {
        inv("d.example.com", FP_X);
        when(checkRepo.firstSightingOf(anyString(), anyCollection(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"2026-10-01T00:00:00", "2027-10-01T00:00:00"}));
        Map<String, Object> rollback = result("d.example.com", FP_Z, "2026-01-01T00:00:00", "2027-12-01T00:00:00");
        service.saveResult(rollback);
        assertThat(rollback.get("deployment_status")).isEqualTo("INCOMPLETE");
        Map<String, Object> shorter = result("d.example.com", FP_Z, "2026-11-01T00:00:00", "2027-01-01T00:00:00");
        service.saveResult(shorter);
        assertThat(shorter.get("deployment_status")).isEqualTo("INCOMPLETE");
        verify(inventoryRepo, never()).clearExpectedFingerprintIfUnchanged(anyLong(), anyString());
    }

    @Test
    @DisplayName("geçmiş budanmış: beklenen hâlâ pinli ve sunulan pinlemeden sonra verilmiş → OK; beklenen önceki pin + sunulan yeni pin → OK")
    void pinEvidenceWhenHistoryPruned() {
        inv("p.example.com", FP_X);
        LatestCheck lc = new LatestCheck();
        lc.setDomain("p.example.com");
        lc.setPinnedFingerprint(FP_X);
        lc.setPinnedAt("2025-06-01T00:00:00");
        when(latestRepo.findById("p.example.com")).thenReturn(Optional.of(lc));
        Map<String, Object> r = result("p.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00");
        service.saveResult(r);
        assertThat(r.get("deployment_status")).isEqualTo("OK");

        inv("q.example.com", FP_X);
        LatestCheck lq = new LatestCheck();
        lq.setDomain("q.example.com");
        lq.setPreviousFingerprint(FP_X);
        lq.setPinnedFingerprint(FP_Z);
        when(latestRepo.findById("q.example.com")).thenReturn(Optional.of(lq));
        Map<String, Object> q = result("q.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00");
        service.saveResult(q);
        assertThat(q.get("deployment_status")).isEqualTo("OK");

        // Beklenen önceki pin ama sunulan pinlenmemiş (güven kapısından geçmemiş) → INCOMPLETE
        inv("r.example.com", FP_X);
        LatestCheck lr = new LatestCheck();
        lr.setDomain("r.example.com");
        lr.setPreviousFingerprint(FP_X);
        lr.setPinnedFingerprint("EE".repeat(32));
        when(latestRepo.findById("r.example.com")).thenReturn(Optional.of(lr));
        Map<String, Object> rr = result("r.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00");
        service.saveResult(rr);
        assertThat(rr.get("deployment_status")).isEqualTo("INCOMPLETE");
    }

    @Test
    @DisplayName("elle yüklenen sertifika → UNKNOWN; kanıt sorgusu yok; parmak izi yoksa UNKNOWN")
    void manualAndMissingFingerprint() {
        inv("m.example.com", FP_X);
        Map<String, Object> m = result("m.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00");
        m.put("manual", true);
        service.saveResult(m);
        assertThat(m.get("deployment_status")).isEqualTo("UNKNOWN");
        Map<String, Object> none = result("m.example.com", null, null, null);
        service.saveResult(none);
        assertThat(none.get("deployment_status")).isEqualTo("UNKNOWN");
        verify(checkRepo, never()).firstSightingOf(anyString(), anyCollection(), anyString());
        verify(inventoryRepo, never()).clearExpectedFingerprintIfUnchanged(anyLong(), anyString());
    }

    @Test
    @DisplayName("B5: planlı kayıtta sunulan sertifika plan servisine verilir; plansız / parmak izsiz kayıtta çağrılmaz")
    void planAutoCloseIsWired() {
        CertificateInventory i = inv("plan.example.com", null);
        i.setRenewalPlannedAt("2026-12-01");
        service.saveResult(result("plan.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        verify(plans).onCheckResult(same(i), eq(FP_Z), eq("2026-11-01T00:00:00"), eq("2027-11-01T00:00:00"));

        inv("noplan.example.com", null);
        service.saveResult(result("noplan.example.com", FP_Z, "2026-11-01T00:00:00", "2027-11-01T00:00:00"));
        CertificateInventory k = inv("nofp.example.com", null);
        k.setRenewalPlannedAt("2026-12-01");
        service.saveResult(result("nofp.example.com", null, null, null));
        verify(plans, times(1)).onCheckResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("normalizeFingerprint: büyük harf, iki nokta / boşluk / tire atılır; null → boş")
    void normalize() {
        assertThat(CertificateService.normalizeFingerprint("ab:cd ef-01")).isEqualTo("ABCDEF01");
        assertThat(CertificateService.normalizeFingerprint(null)).isEmpty();
        assertThat(CertificateService.normalizeFingerprint("a".repeat(5000))).hasSize(256);
    }
}
