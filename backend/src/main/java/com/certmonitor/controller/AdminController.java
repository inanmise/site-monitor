package com.certmonitor.controller;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.EscalationService;
import com.certmonitor.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.NoSuchElementException;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuditService auditService;
    private final CertificateInventoryRepository inventoryRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final EscalationContactRepository contactRepo;
    private final AlertEventRepository alertEventRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final EscalationService escalationService;
    private final LatestCheckRepository latestCheckRepo;
    private final CertificateNoteRepository noteRepo;
    private final UserService userService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Inventory ─────────────────────────────────────────────────────────────

    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Object>> listInventory(HttpSession session) {
        List<CertificateInventory> items = isAdmin(session)
                ? inventoryRepo.findAll().stream()
                        .sorted((a, b) -> a.getDomain().compareToIgnoreCase(b.getDomain())).toList()
                : inventoryRepo.findByTeamIdOrderByDomainAsc(teamId(session));
        return ok(Map.of("data", items));
    }

    @PostMapping("/inventory")
    public ResponseEntity<Map<String, Object>> addInventory(
            @RequestBody CertificateInventory item, HttpSession session, HttpServletRequest request) {
        validateDomain(item.getDomain());
        if (!isAdmin(session)) {
            item.setTeamId(teamId(session));
        }
        if (item.getTeamId() == null) {
            throw new IllegalArgumentException("A team must be selected for the certificate");
        }
        String now = now();
        item.setId(null);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        if (item.getPort() == null) item.setPort(443);
        if (item.getPort() < 1 || item.getPort() > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (item.getActive() == null) item.setActive(true);
        CertificateInventory saved = inventoryRepo.save(item);
        auditService.recordAction("DOMAIN_ADD", session, request,
                "CERTIFICATE", saved.getDomain(),
                "{\"port\":" + saved.getPort() + ",\"teamId\":" + saved.getTeamId() + "}");
        return ok(Map.of("data", saved, "message", "Domain added to inventory"));
    }

    @PutMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> updateInventory(
            @PathVariable Long id, @RequestBody CertificateInventory item, HttpSession session) {
        CertificateInventory existing = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        if (!isAdmin(session)) checkOwnership(existing.getTeamId(), session);
        existing.setDomain(item.getDomain());
        existing.setPort(item.getPort() != null ? item.getPort() : 443);
        existing.setDescription(item.getDescription());
        existing.setOwner(item.getOwner());
        existing.setTags(item.getTags());
        existing.setActive(item.getActive() != null ? item.getActive() : true);
        existing.setExpectedFingerprint(item.getExpectedFingerprint());
        existing.setExpectedSubject(item.getExpectedSubject());
        if (isAdmin(session) && item.getTeamId() != null) existing.setTeamId(item.getTeamId());
        existing.setUpdatedAt(now());
        return ok(Map.of("data", inventoryRepo.save(existing)));
    }

    @DeleteMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> deleteInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        inventoryRepo.findById(id).ifPresent(inv -> {
            if (!isAdmin(session)) checkOwnership(inv.getTeamId(), session);
            inventoryRepo.deleteById(id);
            latestCheckRepo.deleteById(inv.getDomain());
            auditService.recordAction("DOMAIN_DELETE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + "}");
        });
        return ok(Map.of("message", "Deleted"));
    }

    @PostMapping("/inventory/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transferInventory(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        Long newTeamId = toLong(body.get("team_id"));
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        inv.setTeamId(newTeamId);
        inv.setUpdatedAt(now());
        return ok(Map.of("data", inventoryRepo.save(inv), "message", "Transferred"));
    }

    // ── Alert Thresholds (ADMIN only) ─────────────────────────────────────────

    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholds(HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", thresholdRepo.findAll()));
    }

    @PostMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> createThreshold(
            @RequestBody AlertThreshold t, HttpSession session) {
        requireAdmin(session);
        t.setId(null);
        return ok(Map.of("data", thresholdRepo.save(t)));
    }

    @PutMapping("/thresholds/{id}")
    public ResponseEntity<Map<String, Object>> updateThreshold(
            @PathVariable Long id, @RequestBody AlertThreshold t, HttpSession session) {
        requireAdmin(session);
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
    public ResponseEntity<Map<String, Object>> listContacts(HttpSession session) {
        List<EscalationContact> contacts = isAdmin(session)
                ? contactRepo.findByActiveTrueOrderByRoleAsc()
                : contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(teamId(session));
        return ok(Map.of("data", contacts));
    }

    @GetMapping("/contacts/all")
    public ResponseEntity<Map<String, Object>> listAllContacts(HttpSession session) {
        List<EscalationContact> contacts = isAdmin(session)
                ? contactRepo.findAll()
                : contactRepo.findByTeamIdOrderByRoleAsc(teamId(session));
        return ok(Map.of("data", contacts));
    }

    @PostMapping("/contacts")
    public ResponseEntity<Map<String, Object>> addContact(
            @RequestBody EscalationContact contact, HttpSession session) {
        if (!isAdmin(session)) contact.setTeamId(teamId(session));
        if (contact.getTeamId() == null) {
            userService.listTeams().stream().findFirst().ifPresent(t -> contact.setTeamId(t.getId()));
        }
        contact.setId(null);
        contact.setCreatedAt(now());
        if (contact.getActive() == null) contact.setActive(true);
        if (contact.getMinAlertLevel() == null) contact.setMinAlertLevel("WARNING");
        return ok(Map.of("data", contactRepo.save(contact)));
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> updateContact(
            @PathVariable Long id, @RequestBody EscalationContact contact, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        if (!isAdmin(session)) checkOwnership(existing.getTeamId(), session);
        existing.setName(contact.getName());
        existing.setEmail(contact.getEmail());
        existing.setRole(contact.getRole());
        existing.setMinAlertLevel(contact.getMinAlertLevel() != null ? contact.getMinAlertLevel() : "WARNING");
        existing.setWebhookUrl(contact.getWebhookUrl());
        existing.setWebhookType(contact.getWebhookType());
        existing.setActive(contact.getActive() != null ? contact.getActive() : true);
        if (isAdmin(session) && contact.getTeamId() != null) existing.setTeamId(contact.getTeamId());
        return ok(Map.of("data", contactRepo.save(existing)));
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> deleteContact(
            @PathVariable Long id, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        if (!isAdmin(session)) checkOwnership(existing.getTeamId(), session);
        contactRepo.deleteById(id);
        return ok(Map.of("message", "Deleted"));
    }

    // ── Alert Events (ADMIN only) ─────────────────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> listAlerts(
            @RequestParam(defaultValue = "false") boolean onlyOpen, HttpSession session) {
        requireAdmin(session);
        List<AlertEvent> events = onlyOpen
                ? alertEventRepo.findAllOpenOrderBySeverity()
                : alertEventRepo.findAllByOrderByCreatedAtDesc();
        return ok(Map.of("data", events));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
            HttpSession session) {
        requireAdmin(session);
        String by = body != null ? body.getOrDefault("acknowledged_by", "admin") : "admin";
        return ok(Map.of("data", escalationService.acknowledge(id, by), "message", "Alert acknowledged"));
    }

    @PostMapping("/alerts/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
            HttpSession session) {
        requireAdmin(session);
        String resolvedBy = body != null ? body.getOrDefault("resolved_by", "admin") : "admin";
        return ok(Map.of("data", escalationService.resolve(id, resolvedBy), "message", "Alert resolved"));
    }

    @PostMapping("/alerts/{id}/re-notify")
    public ResponseEntity<Map<String, Object>> reNotifyAlert(
            @PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", escalationService.reNotify(id), "message", "Notification triggered"));
    }

    @GetMapping("/alerts/{id}/notifications")
    public ResponseEntity<Map<String, Object>> getAlertNotifications(
            @PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(id)));
    }

    // ── Teams (ADMIN only) ────────────────────────────────────────────────────

    @GetMapping("/teams")
    public ResponseEntity<Map<String, Object>> listTeams(HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", userService.listTeams()));
    }

    @PostMapping("/teams")
    public ResponseEntity<Map<String, Object>> createTeam(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        Team team = userService.createTeam(
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("description"),
                toLong(body.get("leader_id")));
        auditService.recordAction("TEAM_CREATE", session, request,
                "TEAM", team.getId().toString(),
                "{\"name\":\"" + team.getName() + "\",\"leaderId\":" + team.getLeaderId() + "}");
        return ok(Map.of("data", team, "message", "Team created"));
    }

    @PutMapping("/teams/{id}")
    public ResponseEntity<Map<String, Object>> updateTeam(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        Team team = userService.updateTeam(id,
                (String) body.get("name"),
                (String) body.get("email"),
                (String) body.get("description"),
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null,
                toLong(body.get("leader_id")));
        return ok(Map.of("data", team));
    }

    @GetMapping("/teams/{id}/users")
    public ResponseEntity<Map<String, Object>> listTeamUsers(
            @PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", userService.listUsers().stream()
                .filter(u -> id.equals(u.getTeamId())).toList()));
    }

    @DeleteMapping("/teams/{id}")
    public ResponseEntity<Map<String, Object>> deleteTeam(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        userService.deleteTeam(id);
        auditService.recordAction("TEAM_DELETE", session, request, "TEAM", id.toString(), null);
        return ok(Map.of("message", "Team deleted"));
    }

    // ── Users (ADMIN only) ────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(HttpSession session) {
        requireAdmin(session);
        return ok(Map.of("data", userService.listUsers()));
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        AppUser user = userService.createUser(
                (String) body.get("username"),
                (String) body.get("password"),
                (String) body.get("display_name"),
                (String) body.get("email"),
                (String) body.get("employee_id"),
                (String) body.get("system_role"),
                toLong(body.get("team_id")));
        auditService.recordAction("USER_CREATE", session, request,
                "USER", user.getUsername(),
                "{\"role\":\"" + user.getSystemRole() + "\",\"teamId\":" + user.getTeamId() + "}");
        return ok(Map.of("data", user, "message", "User created"));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        AppUser user = userService.updateUser(id,
                (String) body.get("display_name"),
                (String) body.get("email"),
                (String) body.get("employee_id"),
                (String) body.get("system_role"),
                toLong(body.get("team_id")),
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null);
        return ok(Map.of("data", user));
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable Long id, @RequestBody Map<String, String> body, HttpSession session) {
        requireAdmin(session);
        userService.changePassword(id, body.get("password"));
        return ok(Map.of("message", "Password updated"));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        userService.deleteUser(id);
        auditService.recordAction("USER_DELETE", session, request, "USER", id.toString(), null);
        return ok(Map.of("message", "User deleted"));
    }

    // ── Certificate Notes ──────────────────────────────────────────────────────

    @GetMapping("/notes/{domain}")
    public ResponseEntity<Map<String, Object>> getNotes(
            @PathVariable String domain, HttpSession session) {
        List<CertificateNote> notes = isAdmin(session)
                ? noteRepo.findByDomainOrderByCreatedAtDesc(domain)
                : noteRepo.findByDomainAndTeamIdOrderByCreatedAtDesc(domain, teamId(session));
        return ok(Map.of("data", notes));
    }

    @PostMapping("/notes/{domain}")
    public ResponseEntity<Map<String, Object>> addNote(
            @PathVariable String domain,
            @RequestBody Map<String, String> body, HttpSession session) {
        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        CertificateNote note = new CertificateNote();
        note.setDomain(domain);
        note.setTeamId(isAdmin(session) ? null : teamId(session));
        note.setAuthorUsername((String) session.getAttribute("username"));
        note.setAuthorName((String) session.getAttribute("displayName"));
        note.setNote(text.trim());
        note.setCreatedAt(now());
        return ok(Map.of("data", noteRepo.save(note), "message", "Note added"));
    }

    @DeleteMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> deleteNote(
            @PathVariable String domain, @PathVariable Long noteId, HttpSession session) {
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);
        noteRepo.deleteById(noteId);
        return ok(Map.of("message", "Note deleted"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAdmin(HttpSession session) {
        return "ADMIN".equals(session.getAttribute("systemRole"));
    }

    private void requireAdmin(HttpSession session) {
        if (!isAdmin(session)) {
            log.warn("Unauthorized admin access attempt by user={}", actor(session));
            throw new SecurityException("Admin access required");
        }
    }

    /** Username extracted from session — used in audit log entries. */
    private String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private Long teamId(HttpSession session) {
        Object raw = session.getAttribute("teamId");
        if (raw == null) return null;
        return raw instanceof Long ? (Long) raw : Long.valueOf(raw.toString());
    }

    private void checkOwnership(Long resourceTeamId, HttpSession session) {
        Long userTeamId = teamId(session);
        if (!Objects.equals(resourceTeamId, userTeamId)) {
            throw new SecurityException("Access denied: resource belongs to a different team");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new java.util.LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private void validateDomain(String domain) {
        if (domain == null || domain.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        if (domain.length() > 253)
            throw new IllegalArgumentException("Domain name too long");
        if (!domain.matches("^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$")
                && !domain.matches("^[a-zA-Z0-9\\-]{1,63}$")) {
            throw new IllegalArgumentException("Invalid domain format: " + domain);
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }
}
