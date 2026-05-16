package com.certmonitor.controller;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.EscalationService;
import com.certmonitor.model.NotificationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CertificateInventoryRepository inventoryRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final EscalationContactRepository contactRepo;
    private final AlertEventRepository alertEventRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final EscalationService escalationService;
    private final com.certmonitor.repository.LatestCheckRepository latestCheckRepo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Inventory ─────────────────────────────────────────────────────────────

    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Object>> listInventory() {
        return ok(Map.of("data", inventoryRepo.findAll().stream()
                .sorted((a, b) -> a.getDomain().compareToIgnoreCase(b.getDomain()))
                .toList()));
    }

    @PostMapping("/inventory")
    public ResponseEntity<Map<String, Object>> addInventory(@RequestBody CertificateInventory item) {
        String now = now();
        item.setId(null);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        if (item.getPort() == null) item.setPort(443);
        if (item.getActive() == null) item.setActive(true);
        CertificateInventory saved = inventoryRepo.save(item);
        return ok(Map.of("data", saved, "message", "Domain added to inventory"));
    }

    @PutMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> updateInventory(@PathVariable Long id,
                                                                @RequestBody CertificateInventory item) {
        CertificateInventory existing = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        existing.setDomain(item.getDomain());
        existing.setPort(item.getPort() != null ? item.getPort() : 443);
        existing.setDescription(item.getDescription());
        existing.setOwner(item.getOwner());
        existing.setTags(item.getTags());
        existing.setActive(item.getActive() != null ? item.getActive() : true);
        existing.setExpectedFingerprint(item.getExpectedFingerprint());
        existing.setExpectedSubject(item.getExpectedSubject());
        existing.setUpdatedAt(now());
        return ok(Map.of("data", inventoryRepo.save(existing)));
    }

    @DeleteMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> deleteInventory(@PathVariable Long id) {
        inventoryRepo.findById(id).ifPresent(inv -> {
            inventoryRepo.deleteById(id);
            // Remove from dashboard (latest_checks) while keeping full history in certificate_checks
            latestCheckRepo.deleteById(inv.getDomain());
        });
        return ok(Map.of("message", "Deleted"));
    }

    // ── Alert Thresholds ──────────────────────────────────────────────────────

    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholds() {
        return ok(Map.of("data", thresholdRepo.findAll()));
    }

    @PostMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> createThreshold(@RequestBody AlertThreshold t) {
        t.setId(null);
        return ok(Map.of("data", thresholdRepo.save(t)));
    }

    @PutMapping("/thresholds/{id}")
    public ResponseEntity<Map<String, Object>> updateThreshold(@PathVariable Long id,
                                                                @RequestBody AlertThreshold t) {
        AlertThreshold existing = thresholdRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Threshold not found: " + id));
        existing.setName(t.getName() != null ? t.getName() : existing.getName());
        existing.setWarningDays(t.getWarningDays() != null ? t.getWarningDays() : existing.getWarningDays());
        existing.setHighDays(t.getHighDays() != null ? t.getHighDays() : existing.getHighDays());
        existing.setCriticalDays(t.getCriticalDays() != null ? t.getCriticalDays() : existing.getCriticalDays());
        existing.setReAlertIntervalHours(t.getReAlertIntervalHours() != null
                ? t.getReAlertIntervalHours() : existing.getReAlertIntervalHours());
        existing.setActive(t.getActive() != null ? t.getActive() : existing.getActive());
        return ok(Map.of("data", thresholdRepo.save(existing)));
    }

    // ── Escalation Contacts ───────────────────────────────────────────────────

    @GetMapping("/contacts")
    public ResponseEntity<Map<String, Object>> listContacts() {
        return ok(Map.of("data", contactRepo.findByActiveTrueOrderByRoleAsc()));
    }

    @GetMapping("/contacts/all")
    public ResponseEntity<Map<String, Object>> listAllContacts() {
        return ok(Map.of("data", contactRepo.findAll()));
    }

    @PostMapping("/contacts")
    public ResponseEntity<Map<String, Object>> addContact(@RequestBody EscalationContact contact) {
        contact.setId(null);
        contact.setCreatedAt(now());
        if (contact.getActive() == null) contact.setActive(true);
        if (contact.getMinAlertLevel() == null) contact.setMinAlertLevel("WARNING");
        return ok(Map.of("data", contactRepo.save(contact)));
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> updateContact(@PathVariable Long id,
                                                              @RequestBody EscalationContact contact) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        existing.setName(contact.getName());
        existing.setEmail(contact.getEmail());
        existing.setRole(contact.getRole());
        existing.setMinAlertLevel(contact.getMinAlertLevel() != null ? contact.getMinAlertLevel() : "WARNING");
        existing.setWebhookUrl(contact.getWebhookUrl());
        existing.setWebhookType(contact.getWebhookType());
        existing.setActive(contact.getActive() != null ? contact.getActive() : true);
        return ok(Map.of("data", contactRepo.save(existing)));
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> deleteContact(@PathVariable Long id) {
        contactRepo.deleteById(id);
        return ok(Map.of("message", "Deleted"));
    }

    // ── Alert Events ──────────────────────────────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> listAlerts(
            @RequestParam(defaultValue = "false") boolean onlyOpen) {
        List<AlertEvent> events = onlyOpen
                ? alertEventRepo.findAllOpenOrderBySeverity()
                : alertEventRepo.findAllByOrderByCreatedAtDesc();
        return ok(Map.of("data", events));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String by = body != null ? body.getOrDefault("acknowledged_by", "admin") : "admin";
        AlertEvent event = escalationService.acknowledge(id, by);
        return ok(Map.of("data", event, "message", "Alert acknowledged"));
    }

    @PostMapping("/alerts/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String resolvedBy = body != null ? body.getOrDefault("resolved_by", "admin") : "admin";
        AlertEvent event = escalationService.resolve(id, resolvedBy);
        return ok(Map.of("data", event, "message", "Alert resolved"));
    }

    @PostMapping("/alerts/{id}/re-notify")
    public ResponseEntity<Map<String, Object>> reNotifyAlert(@PathVariable Long id) {
        Map<String, Object> result = escalationService.reNotify(id);
        return ok(Map.of("data", result, "message", "Bildirim tetiklendi"));
    }

    @GetMapping("/alerts/{id}/notifications")
    public ResponseEntity<Map<String, Object>> getAlertNotifications(@PathVariable Long id) {
        List<NotificationLog> logs = notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(id);
        return ok(Map.of("data", logs));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new java.util.LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
