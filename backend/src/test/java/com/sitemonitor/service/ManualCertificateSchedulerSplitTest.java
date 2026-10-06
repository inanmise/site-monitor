package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.service.manualcert.ManualCertificateEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zamanlayıcı ayrımı (2026-10-06): elle yüklenen sertifikalar ağ süpürmesine HİÇ girmez (ağ turu, kesinti aritmetiği ve
 * metrikleri bugünküyle aynı); kendi kilitli ({@code manual-cert-sweep}) çevrim-dışı adımında değerlendirilir.
 */
class ManualCertificateSchedulerSplitTest {

    private CertificateInventoryRepository inventoryRepo;
    private CertificateCheckerService checker;
    private CertificateService certService;
    private EscalationService escalation;
    private JdbcTemplate jdbc;
    private LatestCheckRepository latestRepo;
    private ManualCertificateEvaluationService manualEval;
    private SchedulerService scheduler;

    private final CertificateInventory network = row(1L, "net.example.test", null);
    private final CertificateInventory manual = row(2L, "api-takip", CertificateInventory.SOURCE_MANUAL);

    private static CertificateInventory row(Long id, String domain, String source) {
        CertificateInventory inv = new CertificateInventory();
        inv.setId(id);
        inv.setDomain(domain);
        inv.setPort(443);
        inv.setActive(true);
        inv.setCertSource(source);
        return inv;
    }

    @BeforeEach
    void setUp() {
        inventoryRepo = mock(CertificateInventoryRepository.class);
        checker = mock(CertificateCheckerService.class);
        certService = mock(CertificateService.class);
        escalation = mock(EscalationService.class);
        jdbc = mock(JdbcTemplate.class);
        latestRepo = mock(LatestCheckRepository.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(CertificateInventoryRepository.class, inventoryRepo);
        provided.put(CertificateCheckerService.class, checker);
        provided.put(CertificateService.class, certService);
        provided.put(EscalationService.class, escalation);
        provided.put(JdbcTemplate.class, jdbc);
        provided.put(LatestCheckRepository.class, latestRepo);
        provided.put(AppSettingsService.class, settings);
        scheduler = ManualCheckNoAlarmTest.build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "lockTtlMinutes", 10);
        ReflectionTestUtils.setField(scheduler, "staleMinutes", 65);
        ReflectionTestUtils.setField(scheduler, "networkMinErrors", 3);              // @Value varsayılanları (birim testte enjekte edilmez)
        ReflectionTestUtils.setField(scheduler, "networkErrorRateThreshold", 0.5);
        manualEval = mock(ManualCertificateEvaluationService.class);
        ReflectionTestUtils.setField(scheduler, "manualCertEvaluation", manualEval);

        lenient().when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(network, manual));
        lenient().when(manualEval.activeManualRows()).thenReturn(List.of(manual));
        lenient().when(checker.checkAsync(anyString(), anyInt(), anyBoolean(), any(), any())).thenAnswer(i -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("domain", i.getArgument(0));
            r.put("status", "valid");
            r.put("warning", false);
            return CompletableFuture.completedFuture(r);
        });
    }

    @Test
    @DisplayName("ağ listesi manuel satırları İÇERMEZ")
    void networkListExcludesManualRows() {
        List<Map<String, Object>> rows = scheduler.loadDomainsFromInventory();
        assertThat(rows).extracting(m -> m.get("domain")).containsExactly("net.example.test");
    }

    @Test
    @DisplayName("tam tur: manuel satır CertificateCheckerService'e ASLA ulaşmaz; manuel adım kendi kilidiyle koşar")
    void runCheck_splitsNetworkAndManual() {
        scheduler.runCheck();

        verify(checker).checkAsync(eq("net.example.test"), anyInt(), anyBoolean(), any(), any());
        verify(checker, never()).checkAsync(eq("api-takip"), anyInt(), anyBoolean(), any(), any());
        verify(checker, never()).check(eq("api-takip"), anyInt(), anyBoolean(), any(), any());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Map<String, Object>>> netResults = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(escalation).processResults(netResults.capture());
        assertThat(netResults.getValue()).extracting(m -> m.get("domain")).containsExactly("net.example.test");

        verify(jdbc).update(eq("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)"),
                eq(SchedulerService.MANUAL_CERT_LOCK), any(), any());
        verify(manualEval).evaluateScheduled(eq(List.of(manual)), startsWith("upload-"));
        verify(jdbc).update(eq("DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?"),
                eq(SchedulerService.MANUAL_CERT_LOCK), any());
    }

    @Test
    @DisplayName("manuel kayıt yoksa adım KİLİT BİLE ALMAZ")
    void noManualRows_noLock() {
        when(manualEval.activeManualRows()).thenReturn(List.of());
        scheduler.runCheck();
        verify(jdbc, never()).update(anyString(), eq(SchedulerService.MANUAL_CERT_LOCK), any(), any());
        verify(manualEval, never()).evaluateScheduled(anyList(), anyString());
    }

    @Test
    @DisplayName("kilit başka pod'daysa manuel adım atlanır (çift değerlendirme yok)")
    void lockHeldElsewhere_skips() {
        when(jdbc.update(eq("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)"),
                eq(SchedulerService.MANUAL_CERT_LOCK), any(), any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("held"));
        scheduler.runCheck();
        verify(manualEval, never()).evaluateScheduled(anyList(), anyString());
    }

    @Test
    @DisplayName("saatlik tur: ağ listesi boş olsa da (yalnız manuel kayıt) manuel adım koşar")
    void hourly_onlyManualRows_stillEvaluates() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(manual));
        scheduler.scheduledHourlyCheck();
        verify(checker, never()).checkAsync(anyString(), anyInt(), anyBoolean(), any(), any());
        verify(manualEval).evaluateScheduled(eq(List.of(manual)), anyString());
    }

    @Test
    @DisplayName("bayat süpürme: taze manuel kayıt yeniden değerlendirilmez")
    void stale_freshManualSkipped() {
        LatestCheck fresh = new LatestCheck();
        fresh.setDomain("api-takip");
        when(latestRepo.findByCheckedAtGreaterThanEqual(anyString())).thenReturn(Set.of(fresh));
        scheduler.checkStaleInventory();
        verify(manualEval, never()).evaluateScheduled(anyList(), anyString());
        verify(checker).checkAsync(eq("net.example.test"), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("teyit zinciri: manuel kayıt erişilebilirlik için 'canlı hedef' DEĞİL")
    void inventoryLive_falseForManual() {
        when(inventoryRepo.findByDomain("api-takip")).thenReturn(java.util.Optional.of(manual));
        when(inventoryRepo.findByDomain("net.example.test")).thenReturn(java.util.Optional.of(network));
        assertThat(scheduler.isStillMonitored(new MonitoringOutageService.SweepItem(EscalationService.TYPE_ACCESSIBILITY,
                "api-takip", null, false, "x", Map.of(), null))).isFalse();
        assertThat(scheduler.isStillMonitored(new MonitoringOutageService.SweepItem(EscalationService.TYPE_ACCESSIBILITY,
                "net.example.test", null, false, "x", Map.of(), null))).isTrue();
    }
}
