package com.certmonitor.controller;

import com.certmonitor.dto.CertificateDto;
import com.certmonitor.model.AlertEvent;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.service.CertificateCheckerService;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.SchedulerService;
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

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/certificates")
    public ResponseEntity<Map<String, Object>> getCertificates() {
        List<CertificateDto> data = certService.getAllLatest();
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
            @RequestParam(defaultValue = "") String filter_status) {

        Map<String, Object> result = certService.getPaginated(page, per_page, sort_by, sort_dir,
                filter_domain, filter_issuer, filter_status);
        return ok(Map.of("success", true,
                "data", result.get("data"),
                "pagination", result.get("pagination"),
                "timestamp", now()));
    }

    @GetMapping("/warnings")
    public ResponseEntity<Map<String, Object>> getWarnings() {
        List<CertificateDto> warnings = certService.getWarnings();
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
    public ResponseEntity<Map<String, Object>> checkDomain(@PathVariable String domain) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(checkerService.check(domain, 443));
        result.put("run_id", "manual");
        certService.saveResult(result);
        certService.ensureInInventory(domain, 443);  // so next "run all" includes this domain
        return ok(Map.of("success", true, "data", result, "timestamp", now()));
    }

    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> getActivityLog(
            @RequestParam(defaultValue = "24") int hours) {
        return ok(Map.of("success", true, "data", certService.getActivityLog(hours), "timestamp", now()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ok(Map.of("success", true, "data", certService.getStats(), "timestamp", now()));
    }

    @PostMapping("/scheduler/run")
    public ResponseEntity<Map<String, Object>> runScheduler() {
        new Thread(schedulerService::runCheck).start();
        return ok(Map.of("success", true, "message", "Kontrol başlatıldı", "timestamp", now()));
    }

    @GetMapping("/scheduler/status")
    public ResponseEntity<Map<String, Object>> schedulerStatus() {
        return ok(Map.of("success", true, "data", schedulerService.getStatus(), "timestamp", now()));
    }

    @GetMapping("/renewal-advice")
    public ResponseEntity<Map<String, Object>> getRenewalAdvice() {
        List<Map<String, Object>> advice = certService.getRenewalAdvice();
        return ok(Map.of("success", true, "data", advice, "count", advice.size(), "timestamp", now()));
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok(body);
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
