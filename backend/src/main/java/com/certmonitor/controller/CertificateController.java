package com.certmonitor.controller;

import com.certmonitor.dto.CertificateDto;
import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.NetworkOutageEvent;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.NetworkOutageEventRepository;
import org.springframework.data.domain.PageRequest;
import com.certmonitor.service.CertificateCheckerService;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.SchedulerService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CertificateController {

    private final CertificateService certService;
    private final CertificateCheckerService checkerService;
    private final SchedulerService schedulerService;
    private final AlertEventRepository alertEventRepository;
    private final CertificateInventoryRepository inventoryRepo;
    private final NetworkOutageEventRepository networkOutageRepo;
    private final com.certmonitor.service.ExtendedHealthService extendedHealthService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/certificates")
    public ResponseEntity<Map<String, Object>> getCertificates(HttpSession session) {
        List<CertificateDto> data = certService.getAllLatestForTeam(teamId(session));
        return ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    @GetMapping("/certificates/list")
    public ResponseEntity<Map<String, Object>> getCertificatesPaginated(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int per_page,
            @RequestParam(defaultValue = "domain") String sort_by,
            @RequestParam(defaultValue = "asc") String sort_dir,
            @RequestParam(defaultValue = "") String filter_domain,
            @RequestParam(defaultValue = "") String filter_issuer,
            @RequestParam(defaultValue = "") String filter_status,
            HttpSession session) {

        Map<String, Object> result = certService.getPaginated(page, per_page, sort_by, sort_dir,
                filter_domain, filter_issuer, filter_status, teamId(session));
        return ok(Map.of("success", true,
                "data", result.get("data"),
                "pagination", result.get("pagination"),
                "timestamp", now()));
    }

    @GetMapping("/warnings")
    public ResponseEntity<Map<String, Object>> getWarnings(HttpSession session) {
        List<CertificateDto> warnings = certService.getWarningsForTeam(teamId(session));
        return ok(Map.of("success", true, "data", warnings, "count", warnings.size(), "timestamp", now()));
    }

    @GetMapping("/history/{domain}")
    public ResponseEntity<Map<String, Object>> getHistory(@PathVariable String domain) {
        List<CertificateDto> history = certService.getHistory(domain, 30);
        return ok(Map.of("success", true, "domain", domain, "data", history, "timestamp", now()));
    }

    @GetMapping("/history/{domain}/alerts")
    public ResponseEntity<Map<String, Object>> getDomainAlerts(@PathVariable String domain) {
        List<AlertEvent> alerts = alertEventRepository.findByDomainOrderByCreatedAtDesc(domain);
        return ok(Map.of("success", true, "domain", domain, "data", alerts, "timestamp", now()));
    }

    @GetMapping("/check/{domain}")
    public ResponseEntity<Map<String, Object>> checkDomain(@PathVariable String domain, HttpSession session) {
        boolean forceProxy = inventoryRepo.findByDomain(domain)
                .map(ci -> Boolean.TRUE.equals(ci.getUseProxy()))
                .orElse(false);
        Map<String, Object> result = new java.util.LinkedHashMap<>(checkerService.check(domain, 443, forceProxy));
        result.put("run_id", "manual");
        certService.saveResult(result);
        certService.ensureInInventory(domain, 443, teamId(session));
        // Manuel tetiklemede de cache evict gerekiyor (saveResult'tan kaldırıldı)
        certService.evictAllCaches();
        return ok(Map.of("success", true, "data", result, "timestamp", now()));
    }

    @GetMapping("/check-preview/{domain}")
    public ResponseEntity<Map<String, Object>> previewDomain(@PathVariable String domain) {
        boolean forceProxy = inventoryRepo.findByDomain(domain)
                .map(ci -> Boolean.TRUE.equals(ci.getUseProxy()))
                .orElse(false);
        Map<String, Object> result = new java.util.LinkedHashMap<>(checkerService.check(domain, 443, forceProxy));
        return ok(Map.of("success", true, "data", result, "timestamp", now()));
    }

    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> getActivityLog(
            @RequestParam(defaultValue = "24") int hours, HttpSession session) {
        return ok(Map.of("success", true, "data", certService.getActivityLog(hours, teamId(session)), "timestamp", now()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(HttpSession session) {
        return ok(Map.of("success", true, "data", certService.getStatsForTeam(teamId(session)), "timestamp", now()));
    }

    @GetMapping("/stats/teams")
    public ResponseEntity<Map<String, Object>> getTeamStats(HttpSession session) {
        String role = (String) session.getAttribute("systemRole");
        if ("ADMIN".equals(role)) {
            return ok(Map.of("success", true, "data", certService.getAllTeamsBreakdownStats(), "timestamp", now()));
        }
        Long teamId = teamId(session);
        if (teamId == null) return ok(Map.of("success", true, "data", Map.of(), "timestamp", now()));
        String teamName = (String) session.getAttribute("teamName");
        return ok(Map.of("success", true, "data", certService.getTeamBreakdownStats(teamId, teamName), "timestamp", now()));
    }

    @PostMapping("/scheduler/run")
    public ResponseEntity<Map<String, Object>> runScheduler(HttpSession session) {
        requireAdmin(session);
        new Thread(schedulerService::runCheck).start();
        return ok(Map.of("success", true, "message", "Check started", "timestamp", now()));
    }

    @GetMapping("/scheduler/status")
    public ResponseEntity<Map<String, Object>> schedulerStatus() {
        return ok(Map.of("success", true, "data", schedulerService.getStatus(), "timestamp", now()));
    }

    /** Public (authenticated) network status — minimal alarm flag + detected timestamp.
     *  Used by Dashboard banner so all logged-in users see an outage notice. */
    @GetMapping("/system/network-status")
    public ResponseEntity<Map<String, Object>> publicNetworkStatus() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("alarm",       schedulerService.isNetworkOutageActive());
        data.put("detected_at", schedulerService.getNetworkOutageDetectedAt());
        return ok(Map.of("success", true, "data", data, "timestamp", now()));
    }

    /** Past network outage events (most recent first). Used by Warnings page history section. */
    @GetMapping("/system/network-outage-history")
    public ResponseEntity<Map<String, Object>> networkOutageHistory(
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> events = networkOutageRepo.findRecent(PageRequest.of(0, n))
                .stream().map(this::outageEventToMap).toList();
        return ok(Map.of("success", true, "events", events, "count", events.size(), "timestamp", now()));
    }

    private Map<String, Object> outageEventToMap(NetworkOutageEvent e) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("detected_at", e.getDetectedAt());
        m.put("resolved_at", e.getResolvedAt());
        m.put("duration_ms", e.getDurationMs());
        m.put("network_errors", e.getNetworkErrors());
        m.put("total_checks", e.getTotalChecks());
        m.put("error_rate", e.getErrorRate());
        m.put("threshold", e.getThreshold());
        m.put("status", e.getStatus());
        return m;
    }

    @GetMapping("/alerts/silent-domains")
    public ResponseEntity<Map<String, Object>> getSilentAlertDomains() {
        List<String> domains = alertEventRepository.findDomainsWithUnnotifiedOpenAlerts();
        return ok(Map.of("success", true, "data", domains, "timestamp", now()));
    }

    /** Domains whose last N consecutive mail delivery attempts (within {days}d) have all failed.
     *  Surfaced as a warning badge on the certificate card. */
    @GetMapping("/notifications/failure-domains")
    public ResponseEntity<Map<String, Object>> getMailFailureDomains(
            @RequestParam(defaultValue = "3") int consecutive,
            @RequestParam(defaultValue = "7") int days) {
        int c = Math.max(2, Math.min(consecutive, 10));
        int d = Math.max(1, Math.min(days, 90));
        List<String> domains = extendedHealthService.findDomainsWithConsecutiveMailFailures(c, d);
        return ok(Map.of("success", true, "data", domains, "count", domains.size(), "timestamp", now()));
    }

    @GetMapping("/renewal-advice")
    public ResponseEntity<Map<String, Object>> getRenewalAdvice(HttpSession session) {
        List<Map<String, Object>> advice = certService.getRenewalAdviceForTeam(teamId(session));
        return ok(Map.of("success", true, "data", advice, "count", advice.size(), "timestamp", now()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns null (ADMIN sees all) or the user's teamId. */
    private Long teamId(HttpSession session) {
        if ("ADMIN".equals(session.getAttribute("systemRole"))) return null;
        Object raw = session.getAttribute("teamId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    private void requireAdmin(HttpSession session) {
        if (!"ADMIN".equals(session.getAttribute("systemRole"))) {
            throw new SecurityException("Admin access required");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
