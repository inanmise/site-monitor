package com.certmonitor.service;

import com.certmonitor.dto.CertificateDto;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateCheckRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.certmonitor.model.CertificateCheck;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceTest {

    @Mock CertificateCheckRepository checkRepo;
    @Mock LatestCheckRepository latestRepo;
    @Mock CertificateCheckerService checkerService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock AlertThresholdRepository alertThresholdRepo;

    private CertificateService service;

    @BeforeEach
    void setUp() {
        service = new CertificateService(checkRepo, latestRepo, checkerService,
                inventoryRepo, new ObjectMapper(), teamRepo, alertThresholdRepo);
        when(checkerService.serializeSan(any())).thenReturn("[]");
        when(checkerService.deserializeSan(any())).thenReturn(Collections.emptyList());
        when(alertThresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        // Default: active inventory mirrors whatever latestRepo returns in each test,
        // so the getAllLatest() active-domain filter doesn't discard test data.
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenAnswer(inv ->
                latestRepo.findAllByOrderByDomainAsc().stream()
                        .map(lc -> { CertificateInventory ci = new CertificateInventory();
                                     ci.setDomain(lc.getDomain()); ci.setActive(true); return ci; })
                        .collect(Collectors.toList()));
        // getAllLatest() artık findByDomainIn(activeDomains) çağırıyor;
        // eski testlerde findAllByOrderByDomainAsc mock'u veri verir,
        // burada findByDomainIn de aynı veriyi yansıtsın.
        when(latestRepo.findByDomainIn(anyCollection())).thenAnswer(inv ->
                latestRepo.findAllByOrderByDomainAsc());
    }

    // ── Deployment Status ─────────────────────────────────────────────────────

    @Test
    @DisplayName("saveResult: fingerprint matches expectedFingerprint → deployment_status=OK")
    void saveResult_fingerprintMatch_deploymentOk() {
        String domain = "match.example.com";
        String fingerprint = "ABCDEF1234";
        CertificateInventory inv = inventory(domain, fingerprint);
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.of(inv));
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveResult(certResult(domain, fingerprint));

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        assertThat(captor.getValue().getDeploymentStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("saveResult: fingerprint differs from expectedFingerprint → deployment_status=INCOMPLETE")
    void saveResult_fingerprintMismatch_deploymentIncomplete() {
        String domain = "mismatch.example.com";
        CertificateInventory inv = inventory(domain, "EXPECTED_FINGERPRINT");
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.of(inv));
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveResult(certResult(domain, "ACTUAL_DIFFERENT_FINGERPRINT"));

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        assertThat(captor.getValue().getDeploymentStatus()).isEqualTo("INCOMPLETE");
    }

    @Test
    @DisplayName("saveResult: no expectedFingerprint set → deployment_status=OK")
    void saveResult_noExpectedFingerprint_deploymentOk() {
        String domain = "no-expected.example.com";
        CertificateInventory inv = inventory(domain, null); // no expected fingerprint
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.of(inv));
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveResult(certResult(domain, "ANY_FINGERPRINT"));

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        assertThat(captor.getValue().getDeploymentStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("saveResult: domain not in inventory → deployment_status=OK")
    void saveResult_domainNotInInventory_deploymentOk() {
        String domain = "unknown.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.saveResult(certResult(domain, "SOME_FINGERPRINT"));

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        assertThat(captor.getValue().getDeploymentStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("saveResult: null fingerprint → deployment_status=UNKNOWN")
    void saveResult_nullFingerprint_deploymentUnknown() {
        String domain = "no-fingerprint.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> result = certResult(domain, null);
        result.put("fingerprint", null);
        service.saveResult(result);

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        assertThat(captor.getValue().getDeploymentStatus()).isEqualTo("UNKNOWN");
    }

    // ── saveResult: field mapping ─────────────────────────────────────────────

    @Test
    @DisplayName("saveResult stores all new chain fields in LatestCheck")
    void saveResult_storesChainFields() {
        String domain = "chain.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> result = certResult(domain, "FP123");
        result.put("chain_status", "VALID");
        result.put("revocation_status", "VALID");
        result.put("intermediate_expiry", "2027-01-01T00:00:00");
        result.put("intermediate_days_remaining", 300);

        service.saveResult(result);

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        LatestCheck saved = captor.getValue();
        assertThat(saved.getFingerprint()).isEqualTo("FP123");
        assertThat(saved.getChainStatus()).isEqualTo("VALID");
        assertThat(saved.getRevocationStatus()).isEqualTo("VALID");
        assertThat(saved.getIntermediateExpiry()).isEqualTo("2027-01-01T00:00:00");
        assertThat(saved.getIntermediateDaysRemaining()).isEqualTo(300);
    }

    @Test
    @DisplayName("saveResult stores via and tls_mode_used transport metadata in LatestCheck")
    void saveResult_storesViaAndTlsMode() {
        String domain = "via.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(latestRepo.findById(domain)).thenReturn(Optional.empty());
        when(checkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(latestRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> result = certResult(domain, "FP456");
        result.put("via", "proxy");
        result.put("tls_mode_used", "browser");

        service.saveResult(result);

        ArgumentCaptor<LatestCheck> captor = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(captor.capture());
        assertThat(captor.getValue().getVia()).isEqualTo("proxy");
        assertThat(captor.getValue().getTlsModeUsed()).isEqualTo("browser");
    }

    @Test
    @DisplayName("CertificateDto.from maps via and tls_mode_used")
    void certificateDto_from_mapsViaFields() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("via.example.com");
        lc.setVia("direct");
        lc.setTlsModeUsed("default");

        CertificateDto dto = CertificateDto.from(lc,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        assertThat(dto.getVia()).isEqualTo("direct");
        assertThat(dto.getTlsModeUsed()).isEqualTo("default");
    }

    // ── getStats ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getStats returns correct totals for mixed certificate states")
    void getStats_mixedStates_correctCounts() {
        List<LatestCheck> allChecks = List.of(
                latestCheck("valid1.com",    "valid",   false, 90,   "VALID",   "OK"),
                latestCheck("warning1.com",  "warning", true,  25,   "VALID",   "OK"), // 25 > highDays(15) → warning
                latestCheck("high1.com",     "warning", true,  12,   "VALID",   "OK"), // critDays(7) < 12 ≤ highDays(15) → high
                latestCheck("critical1.com", "warning", true,  5,    "VALID",   "OK"), // 5 ≤ critDays(7) → critical
                latestCheck("error1.com",    "error",   true,  null, "UNKNOWN", "UNKNOWN"),
                latestCheck("revoked1.com",  "valid",   false, 90,   "REVOKED", "OK"),
                latestCheck("mismatch1.com", "valid",   false, 90,   "VALID",   "INCOMPLETE")
        );
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(allChecks);
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(
                allChecks.stream().filter(c -> Boolean.TRUE.equals(c.getWarning()) || "error".equals(c.getStatus()))
                        .collect(Collectors.toList()));

        Map<String, Object> stats = service.getStats();

        assertThat(stats.get("total_certificates")).isEqualTo(7);
        assertThat(stats.get("valid_count")).isEqualTo(3L);       // valid1, revoked1, mismatch1
        assertThat(stats.get("critical_count")).isEqualTo(1L);    // critical1.com (5 ≤ 7)
        assertThat(stats.get("high_count")).isEqualTo(1L);        // high1.com (7 < 12 ≤ 15)
        assertThat(stats.get("warning_count")).isEqualTo(1L);     // warning1.com (25 > 15)
        assertThat(stats.get("error_count")).isEqualTo(1L);       // error1.com
        assertThat(stats.get("revoked")).isEqualTo(1L);
        assertThat(stats.get("deployment_mismatch")).isEqualTo(1L);
    }

    @Test
    @DisplayName("getStats empty DB returns all zeros")
    void getStats_emptyDb_allZeros() {
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(Collections.emptyList());
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(Collections.emptyList());

        Map<String, Object> stats = service.getStats();

        assertThat(stats.get("total_certificates")).isEqualTo(0);
        assertThat(stats.get("valid_count")).isEqualTo(0L);
        assertThat(stats.get("critical_count")).isEqualTo(0L);
        assertThat(stats.get("high_count")).isEqualTo(0L);
        assertThat(stats.get("warning_count")).isEqualTo(0L);
    }

    @Test
    @DisplayName("getStats: warnings query returns stale domains absent from inventory — valid_count stays non-negative")
    void getStats_warningsDriftPastAll_validNonNegative() {
        // Active inventory has only 2 domains
        List<LatestCheck> activeChecks = List.of(
                latestCheck("active-warning.com", "warning", true,  5,    "VALID",   "OK"),
                latestCheck("active-valid.com",   "valid",   false, 90,   "VALID",   "OK")
        );
        // But the warnings query returns 4 entries (3 stale domains no longer in active inventory)
        // — simulates cache drift / soft-delete latency edge case
        List<LatestCheck> warningsRaw = List.of(
                latestCheck("active-warning.com", "warning", true,  5,    "VALID",   "OK"),
                latestCheck("stale1.com",         "error",   true,  null, "UNKNOWN", "UNKNOWN"),
                latestCheck("stale2.com",         "warning", true,  10,   "VALID",   "OK"),
                latestCheck("stale3.com",         "warning", true,  20,   "VALID",   "OK")
        );
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(activeChecks);
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(warningsRaw);

        Map<String, Object> stats = service.getStats();

        // Defense layer 1: getWarnings filters by active domains → only active-warning.com survives
        // Defense layer 2: valid_count counted directly via warnDomainSet exclusion, never via subtraction
        assertThat((Long) stats.get("valid_count")).isGreaterThanOrEqualTo(0L);
        assertThat(stats.get("valid_count")).isEqualTo(1L);   // only active-valid.com is valid
        assertThat(stats.get("total_certificates")).isEqualTo(2);
        assertThat(stats.get("error_count")).isEqualTo(0L);   // stale1.com filtered out
        assertThat(stats.get("critical_count")).isEqualTo(1L); // active-warning.com (5 ≤ 7)
    }

    // ── getRenewalAdvice ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getRenewalAdvice sorts critical before warning before info")
    void getRenewalAdvice_sortsByPriority() {
        List<LatestCheck> checks = List.of(
                latestCheck("info.com", "valid", false, 50, "VALID", "OK"),
                latestCheck("warning.com", "warning", true, 20, "VALID", "OK"),
                latestCheck("critical.com", "warning", true, 3, "VALID", "OK")
        );
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(checks);

        List<Map<String, Object>> advice = service.getRenewalAdvice();

        assertThat(advice).hasSize(3);
        assertThat(advice.get(0).get("priority")).isEqualTo("critical");
        assertThat(advice.get(1).get("priority")).isEqualTo("warning");
        assertThat(advice.get(2).get("priority")).isEqualTo("info");
    }

    @Test
    @DisplayName("getRenewalAdvice: REVOKED cert is always critical")
    void getRenewalAdvice_revokedCert_isCritical() {
        List<LatestCheck> checks = List.of(
                latestCheck("revoked.com", "valid", false, 90, "REVOKED", "OK")
        );
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(checks);

        List<Map<String, Object>> advice = service.getRenewalAdvice();

        assertThat(advice).hasSize(1);
        assertThat(advice.get(0).get("priority")).isEqualTo("critical");
        assertThat((String) advice.get(0).get("message")).contains("İPTAL");
    }

    @Test
    @DisplayName("getRenewalAdvice: healthy cert (>60 days) not included")
    void getRenewalAdvice_healthyCert_notIncluded() {
        List<LatestCheck> checks = List.of(
                latestCheck("healthy.com", "valid", false, 120, "VALID", "OK")
        );
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(checks);

        assertThat(service.getRenewalAdvice()).isEmpty();
    }

    // ── getPaginated: status filter ───────────────────────────────────────────

    @Test
    @DisplayName("getPaginated filterStatus=valid excludes warnings and errors")
    void getPaginated_filterValid_excludesWarningsAndErrors() {
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("valid.com",    "valid",   false, 90,   "VALID", "OK"),
                latestCheck("warning.com",  "warning", true,  25,   "VALID", "OK"),
                latestCheck("critical.com", "warning", true,  5,    "VALID", "OK"),
                latestCheck("error.com",    "error",   true,  null, "VALID", "OK")
        ));
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(List.of(
                latestCheck("warning.com",  "warning", true,  25,   "VALID", "OK"),
                latestCheck("critical.com", "warning", true,  5,    "VALID", "OK"),
                latestCheck("error.com",    "error",   true,  null, "VALID", "OK")
        ));

        @SuppressWarnings("unchecked")
        List<CertificateDto> data =
                (List<CertificateDto>) service.getPaginated(1, 20, "domain", "asc", "", "", "valid", null).get("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).getDomain()).isEqualTo("valid.com");
    }

    @Test
    @DisplayName("getPaginated filterStatus=warning returns only pure-warning (>highDays) certs")
    void getPaginated_filterWarning_returnsOnlyWarnings() {
        // "warning" filter = warning=true AND days>highDays(15); "high" = critDays<days≤highDays; "critical" = days≤critDays(7)
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("valid.com",    "valid",   false, 90,   "VALID", "OK"),
                latestCheck("warning.com",  "warning", true,  35,   "VALID", "OK"), // 35 days > 15 → warning
                latestCheck("critical.com", "warning", true,  5,    "VALID", "OK"), // 5 days ≤ 7 → critical
                latestCheck("error.com",    "error",   true,  null, "VALID", "OK")
        ));
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        List<CertificateDto> data =
                (List<CertificateDto>) service.getPaginated(1, 20, "domain", "asc", "", "", "warning", null).get("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).getDomain()).isEqualTo("warning.com");
    }

    @Test
    @DisplayName("getPaginated filterStatus=critical returns warnings with ≤7 days remaining (default critDays)")
    void getPaginated_filterCritical_returnsWarningsWithDays7OrLess() {
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("valid.com",    "valid",   false, 90, "VALID", "OK"),
                latestCheck("warning.com",  "warning", true,  8,  "VALID", "OK"), // 8 days → warning only
                latestCheck("critical.com", "warning", true,  7,  "VALID", "OK"), // 7 days → critical (boundary)
                latestCheck("urgent.com",   "warning", true,  3,  "VALID", "OK")  // 3 days → critical
        ));
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        List<CertificateDto> data =
                (List<CertificateDto>) service.getPaginated(1, 20, "domain", "asc", "", "", "critical", null).get("data");

        assertThat(data).hasSize(2);
        List<String> domains = data.stream().map(CertificateDto::getDomain).toList();
        assertThat(domains).containsExactlyInAnyOrder("critical.com", "urgent.com");
    }

    @Test
    @DisplayName("getPaginated filterStatus=error returns only error-status certs")
    void getPaginated_filterError_returnsOnlyErrors() {
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("valid.com",   "valid",   false, 90,   "VALID", "OK"),
                latestCheck("warning.com", "warning", true,  25,   "VALID", "OK"),
                latestCheck("error1.com",  "error",   true,  null, "VALID", "OK"),
                latestCheck("error2.com",  "error",   false, null, "VALID", "OK")
        ));
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        List<CertificateDto> data =
                (List<CertificateDto>) service.getPaginated(1, 20, "domain", "asc", "", "", "error", null).get("data");

        assertThat(data).hasSize(2);
        data.forEach(d -> assertThat(d.getStatus()).isEqualTo("error"));
    }

    @Test
    @DisplayName("getPaginated sortBy=priority puts errors first then by days ascending")
    void getPaginated_prioritySort_errorsFirst() {
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("valid.com",    "valid",   false, 90,   "VALID", "OK"),
                latestCheck("warning.com",  "warning", true,  25,   "VALID", "OK"),
                latestCheck("error.com",    "error",   true,  null, "VALID", "OK"),
                latestCheck("critical.com", "warning", true,  5,    "VALID", "OK")
        ));
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        List<CertificateDto> data =
                (List<CertificateDto>) service.getPaginated(1, 20, "priority", "asc", "", "", "", null).get("data");

        assertThat(data).hasSize(4);
        assertThat(data.get(0).getDomain()).isEqualTo("error.com");    // error first
        assertThat(data.get(1).getDomain()).isEqualTo("critical.com"); // 5 days
        assertThat(data.get(2).getDomain()).isEqualTo("warning.com");  // 25 days
        assertThat(data.get(3).getDomain()).isEqualTo("valid.com");    // 90 days
    }

    // ── getAllLatest ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAllLatest returns all records from repo")
    void getAllLatest_returnsAllFromRepo() {
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("a.com", "valid", false, 90, "VALID", "OK"),
                latestCheck("b.com", "valid", false, 60, "VALID", "OK")
        ));
        when(inventoryRepo.findAll()).thenReturn(Collections.emptyList());

        List<CertificateDto> result = service.getAllLatest();
        assertThat(result).hasSize(2);
    }

    // ── getHistory ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHistory delegates to checkRepo.findTopByDomainOrderByCheckedAtDesc")
    void getHistory_delegatesToRepo() {
        when(checkRepo.findTopByDomainOrderByCheckedAtDesc("example.com", 30))
                .thenReturn(Collections.emptyList());

        service.getHistory("example.com", 30);

        verify(checkRepo).findTopByDomainOrderByCheckedAtDesc("example.com", 30);
    }

    // ── getActivityLog ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getActivityLog groups checks by runId")
    void getActivityLog_groupsByRunId() {
        CertificateCheck c1 = check("run-1", "a.com", "valid");
        CertificateCheck c2 = check("run-1", "b.com", "valid");
        CertificateCheck c3 = check("run-2", "c.com", "error");
        CertificateCheck c4 = check("run-2", "d.com", "valid");
        when(checkRepo.findByCheckedAtAfterLimited(any(), anyInt())).thenReturn(List.of(c1, c2, c3, c4));

        List<Map<String, Object>> runs = service.getActivityLog(24, null);

        assertThat(runs).hasSize(2);
        // Each run group has its runId
        List<String> runIds = runs.stream().map(r -> (String) r.get("run_id")).toList();
        assertThat(runIds).containsExactlyInAnyOrder("run-1", "run-2");
    }

    // ── getRenewalAdviceForTeam ───────────────────────────────────────────────

    @Test
    @DisplayName("getRenewalAdviceForTeam filters to team domains only")
    void getRenewalAdviceForTeam_filtersToTeam() {
        // Team 5 only has "team.domain.com"
        CertificateInventory teamInv = new CertificateInventory();
        teamInv.setDomain("team.domain.com");
        teamInv.setActive(true);
        teamInv.setTeamId(5L);

        when(inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(5L))).thenReturn(List.of(teamInv));
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(
                latestCheck("team.domain.com", "warning", true, 10, "VALID", "OK"),
                latestCheck("other.domain.com", "warning", true, 5, "VALID", "OK")
        ));
        when(inventoryRepo.findAll()).thenReturn(Collections.emptyList());

        List<Map<String, Object>> advice = service.getRenewalAdviceForTeams(List.of(5L));

        List<String> domains = advice.stream().map(a -> (String) a.get("domain")).toList();
        assertThat(domains).containsOnly("team.domain.com");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CertificateCheck check(String runId, String domain, String status) {
        CertificateCheck c = new CertificateCheck();
        c.setRunId(runId);
        c.setDomain(domain);
        c.setStatus(status);
        c.setWarning("error".equals(status) || "warning".equals(status));
        c.setCheckedAt("2026-01-01T00:00:00");
        return c;
    }

    private CertificateInventory inventory(String domain, String expectedFingerprint) {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(domain);
        inv.setPort(443);
        inv.setActive(true);
        inv.setExpectedFingerprint(expectedFingerprint);
        return inv;
    }

    private Map<String, Object> certResult(String domain, String fingerprint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("subject", "CN=" + domain);
        m.put("issuer", "Test CA");
        m.put("issuer_cn", "Test CA");
        m.put("not_before", "2024-01-01T00:00:00");
        m.put("not_after", "2025-01-01T00:00:00");
        m.put("days_remaining", 90);
        m.put("warning", false);
        m.put("status", "valid");
        m.put("error", null);
        m.put("san", List.of());
        m.put("fingerprint", fingerprint);
        m.put("chain_status", "VALID");
        m.put("revocation_status", "VALID");
        m.put("deployment_status", "UNKNOWN");
        m.put("chain", List.of());
        m.put("checked_at", "2024-06-01T00:00:00");
        m.put("intermediate_expiry", null);
        m.put("intermediate_days_remaining", null);
        return m;
    }

    private LatestCheck latestCheck(String domain, String status, boolean warning,
                                     Integer days, String chainStatus, String deploymentStatus) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain);
        c.setStatus(status);
        c.setWarning(warning);
        c.setDaysRemaining(days);
        // "REVOKED" passed as chainStatus means revocationStatus=REVOKED, chainStatus=VALID
        c.setChainStatus("REVOKED".equals(chainStatus) ? "VALID" : chainStatus);
        c.setDeploymentStatus(deploymentStatus);
        c.setRevocationStatus("REVOKED".equals(chainStatus) ? "REVOKED"
                : "VALID".equals(chainStatus) ? "VALID" : "UNKNOWN");
        c.setNotAfter("2025-01-01T00:00:00");
        return c;
    }
}
