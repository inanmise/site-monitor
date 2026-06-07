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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final CertificateNoteRevisionRepository noteRevisionRepo;
    private final UserService userService;
    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final com.certmonitor.service.EmailNotificationService emailNotificationService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Inventory ─────────────────────────────────────────────────────────────

    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Object>> listInventory(
            @RequestParam(defaultValue = "false") boolean showDeleted,
            HttpSession session) {
        List<CertificateInventory> items;
        if (isAdmin(session)) {
            items = showDeleted
                    ? inventoryRepo.findAllByOrderByDomainAsc()
                    : inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc();
        } else {
            items = inventoryRepo.findByTeamIdAndDeletedAtIsNullOrderByDomainAsc(teamId(session));
        }
        return ok(Map.of("data", items));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
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

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @PutMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> updateInventory(
            @PathVariable Long id, @RequestBody CertificateInventory item,
            HttpSession session, HttpServletRequest request) {
        CertificateInventory existing = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        if (!isAdmin(session)) checkOwnership(existing.getTeamId(), session);

        // Build diff BEFORE applying changes
        String diffJson = buildInventoryDiff(existing, item, isAdmin(session));

        existing.setDomain(item.getDomain());
        existing.setPort(item.getPort() != null ? item.getPort() : 443);
        existing.setDescription(item.getDescription());
        existing.setOwner(item.getOwner());
        existing.setTags(item.getTags());
        existing.setActive(item.getActive() != null ? item.getActive() : true);
        existing.setExpectedFingerprint(item.getExpectedFingerprint());
        existing.setExpectedSubject(item.getExpectedSubject());
        if (isAdmin(session) && item.getTeamId() != null) existing.setTeamId(item.getTeamId());
        existing.setUgTeamId(item.getUgTeamId());
        existing.setExternalVendor(item.getExternalVendor());
        existing.setActionRequired(item.getActionRequired());
        existing.setOpenshift(item.getOpenshift());
        existing.setSslPinning(item.getSslPinning());
        existing.setInternalCert(item.getInternalCert());
        existing.setJksKeystore(item.getJksKeystore());
        existing.setServerUpdate(item.getServerUpdate());
        existing.setNetscaler(item.getNetscaler());
        existing.setWafEnabled(item.getWafEnabled());
        existing.setInUse(item.getInUse());
        existing.setEvCertificate(item.getEvCertificate());
        existing.setTransferredToSy(item.getTransferredToSy());
        existing.setPurchasedBy(item.getPurchasedBy());
        existing.setChangeDescription(item.getChangeDescription());
        existing.setTier(item.getTier());
        existing.setUpdatedAt(now());
        CertificateInventory saved = inventoryRepo.save(existing);

        if (!diffJson.equals("{}")) {
            auditService.recordAction("DOMAIN_EDIT", session, request,
                    "CERTIFICATE", saved.getDomain(), diffJson);
        }
        return ok(Map.of("data", saved));
    }

    private String buildInventoryDiff(CertificateInventory o, CertificateInventory n, boolean isAdmin) {
        StringBuilder sb = new StringBuilder("{");
        fieldDiff(sb, "domain",             o.getDomain(),               n.getDomain());
        fieldDiff(sb, "port",               o.getPort(),                 n.getPort() != null ? n.getPort() : 443);
        fieldDiff(sb, "active",             o.getActive(),               n.getActive() != null ? n.getActive() : true);
        fieldDiff(sb, "tier",               o.getTier(),                 n.getTier());
        fieldDiff(sb, "description",        o.getDescription(),          n.getDescription());
        fieldDiff(sb, "owner",              o.getOwner(),                n.getOwner());
        fieldDiff(sb, "tags",               o.getTags(),                 n.getTags());
        fieldDiff(sb, "externalVendor",     o.getExternalVendor(),       n.getExternalVendor());
        fieldDiff(sb, "actionRequired",     o.getActionRequired(),       n.getActionRequired());
        fieldDiff(sb, "openshift",          o.getOpenshift(),            n.getOpenshift());
        fieldDiff(sb, "sslPinning",         o.getSslPinning(),           n.getSslPinning());
        fieldDiff(sb, "internalCert",       o.getInternalCert(),         n.getInternalCert());
        fieldDiff(sb, "jksKeystore",        o.getJksKeystore(),          n.getJksKeystore());
        fieldDiff(sb, "serverUpdate",       o.getServerUpdate(),         n.getServerUpdate());
        fieldDiff(sb, "netscaler",          o.getNetscaler(),            n.getNetscaler());
        fieldDiff(sb, "wafEnabled",         o.getWafEnabled(),           n.getWafEnabled());
        fieldDiff(sb, "inUse",              o.getInUse(),                n.getInUse());
        fieldDiff(sb, "evCertificate",      o.getEvCertificate(),        n.getEvCertificate());
        fieldDiff(sb, "transferredToSy",    o.getTransferredToSy(),      n.getTransferredToSy());
        fieldDiff(sb, "purchasedBy",        o.getPurchasedBy(),          n.getPurchasedBy());
        fieldDiff(sb, "changeDescription",  o.getChangeDescription(),    n.getChangeDescription());
        fieldDiff(sb, "expectedFingerprint",o.getExpectedFingerprint(),  n.getExpectedFingerprint());
        fieldDiff(sb, "expectedSubject",    o.getExpectedSubject(),      n.getExpectedSubject());
        if (isAdmin && n.getTeamId() != null)
            fieldDiff(sb, "teamId",         o.getTeamId(),               n.getTeamId());
        if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    private void fieldDiff(StringBuilder sb, String field, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            sb.append('"').append(field).append("\":{\"from\":")
              .append(toJsonVal(oldVal)).append(",\"to\":")
              .append(toJsonVal(newVal)).append("},");
        }
    }

    private String toJsonVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        String s = v.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                               .replace("\n", "\\n").replace("\r", "");
        return '"' + s + '"';
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @DeleteMapping("/inventory/{id}")
    public ResponseEntity<Map<String, Object>> deleteInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        return inventoryRepo.findById(id).map(inv -> {
            if (!isAdmin(session)) checkOwnership(inv.getTeamId(), session);
            inv.setDeletedAt(now());
            inv.setActive(false);
            inventoryRepo.save(inv);
            int alertsClosed = escalationService.closeAlertsOnInventoryDelete(inv.getDomain());
            auditService.recordAction("DOMAIN_SOFT_DELETE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + ",\"alertsClosed\":" + alertsClosed + "}");
            return ok(Map.of("message", "Deleted", "alertsClosed", alertsClosed));
        }).orElse(ResponseEntity.notFound().build());
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @DeleteMapping("/certificates/{domain}")
    public ResponseEntity<Map<String, Object>> deleteCertificateCheck(
            @PathVariable String domain, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        latestCheckRepo.deleteById(domain);
        auditService.recordAction("DOMAIN_DELETE_CHECK", session, request, "CERTIFICATE", domain, null);
        return ok(Map.of("message", "Deleted"));
    }

    @PostMapping("/inventory/{id}/restore")
    public ResponseEntity<Map<String, Object>> restoreInventory(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        return inventoryRepo.findById(id).map(inv -> {
            inv.setDeletedAt(null);
            inv.setActive(true);
            inv.setUpdatedAt(now());
            inventoryRepo.save(inv);
            auditService.recordAction("DOMAIN_RESTORE", session, request,
                    "CERTIFICATE", inv.getDomain(),
                    "{\"teamId\":" + inv.getTeamId() + "}");
            return ok(Map.of("data", inv, "message", "Restored"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/inventory/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transferInventory(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        Long newTeamId = toLong(body.get("team_id"));
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        Long oldTeamId = inv.getTeamId();
        inv.setTeamId(newTeamId);
        inv.setUpdatedAt(now());
        inventoryRepo.save(inv);
        auditService.recordAction("DOMAIN_TRANSFER_SY", session, request,
                "CERTIFICATE", inv.getDomain(),
                "{\"from\":" + oldTeamId + ",\"to\":" + newTeamId + "}");
        return ok(Map.of("data", inv, "message", "Transferred"));
    }

    @PostMapping("/inventory/{id}/transfer-ug")
    public ResponseEntity<Map<String, Object>> transferInventoryUg(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        Long newUgTeamId = toLong(body.get("ug_team_id"));
        CertificateInventory inv = inventoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
        Long oldUgTeamId = inv.getUgTeamId();
        inv.setUgTeamId(newUgTeamId);
        inv.setUpdatedAt(now());
        inventoryRepo.save(inv);
        auditService.recordAction("DOMAIN_TRANSFER_UG", session, request,
                "CERTIFICATE", inv.getDomain(),
                "{\"from\":" + oldUgTeamId + ",\"to\":" + newUgTeamId + "}");
        return ok(Map.of("data", inv, "message", "UG team transferred"));
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
            @RequestBody Map<String, Object> body, HttpSession session) {
        EscalationContact contact = new EscalationContact();
        applyContactFields(contact, body, session);
        contact.setId(null);
        contact.setCreatedAt(now());
        if (contact.getActive() == null) contact.setActive(true);
        if (contact.getMinAlertLevel() == null) contact.setMinAlertLevel("WARNING");
        if (!isAdmin(session)) contact.setTeamId(teamId(session));
        if (contact.getTeamId() == null) {
            userService.listTeams().stream().findFirst().ifPresent(t -> contact.setTeamId(t.getId()));
        }
        return ok(Map.of("data", contactRepo.save(contact)));
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<Map<String, Object>> updateContact(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        EscalationContact existing = contactRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contact not found: " + id));
        if (!isAdmin(session)) checkOwnership(existing.getTeamId(), session);
        applyContactFields(existing, body, session);
        return ok(Map.of("data", contactRepo.save(existing)));
    }

    private void applyContactFields(EscalationContact c, Map<String, Object> body, HttpSession session) {
        Long userId = toLong(body.get("user_id"));
        if (userId != null) {
            userRepo.findById(userId).ifPresent(u -> {
                c.setUserId(userId);
                c.setName(u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName() : u.getUsername());
                c.setEmail(u.getEmail());
            });
        } else {
            String name = (String) body.get("name");
            String email = (String) body.get("email");
            if (name != null) c.setName(name);
            if (email != null) c.setEmail(email);
            c.setUserId(null);
        }
        String role = (String) body.get("role");
        if (role != null) c.setRole(role);
        String minAlertLevel = (String) body.get("min_alert_level");
        c.setMinAlertLevel(minAlertLevel != null ? minAlertLevel : (c.getMinAlertLevel() != null ? c.getMinAlertLevel() : "WARNING"));
        c.setWebhookUrl((String) body.get("webhook_url"));
        c.setWebhookType((String) body.get("webhook_type"));
        Object active = body.get("active");
        c.setActive(active instanceof Boolean ? (Boolean) active : (c.getActive() != null ? c.getActive() : true));
        if (isAdmin(session)) {
            Long teamId = toLong(body.get("team_id"));
            if (teamId != null) c.setTeamId(teamId);
        }
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
            @RequestParam(defaultValue = "false") boolean onlyOpen,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String resolvedSince,
            @RequestParam(required = false) String resolvedUntil,
            @RequestParam(required = false) String domain,
            HttpSession session) {
        requireAdmin(session);
        int sz = Math.max(1, Math.min(size, 200));
        Boolean resolvedEffective = resolved != null ? resolved : (onlyOpen ? Boolean.FALSE : null);
        // Default sort: en yeniden en eskiye (newest → oldest) — hem açık hem kapalı için.
        Sort sort = Boolean.TRUE.equals(resolvedEffective)
                ? Sort.by(Sort.Direction.DESC, "resolvedAt").and(Sort.by(Sort.Direction.DESC, "createdAt"))
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Page<AlertEvent> result = alertEventRepo.findFiltered(
                resolvedEffective, since, until, resolvedSince, resolvedUntil, domain,
                PageRequest.of(Math.max(0, page), sz, sort));
        enrichAlerts(result.getContent());
        return ok(Map.of(
                "data",  result.getContent(),
                "total", result.getTotalElements(),
                "page",  result.getNumber(),
                "size",  result.getSize()));
    }

    /**
     * Bulk-populates @Transient fields on AlertEvent: SY/UG team names, tier,
     * and per-event email sent/failed counts. Single round-trip per related
     * table — no N+1.
     */
    private void enrichAlerts(List<AlertEvent> events) {
        if (events.isEmpty()) return;

        java.util.Set<String> domains = new java.util.HashSet<>();
        java.util.Set<Long> alertIds  = new java.util.HashSet<>();
        for (AlertEvent ev : events) {
            if (ev.getDomain() != null) domains.add(ev.getDomain());
            if (ev.getId() != null)     alertIds.add(ev.getId());
        }

        Map<String, CertificateInventory> invByDomain = new java.util.HashMap<>();
        java.util.Set<Long> teamIds = new java.util.HashSet<>();
        if (!domains.isEmpty()) {
            for (CertificateInventory inv : inventoryRepo.findByDomainIn(domains)) {
                invByDomain.putIfAbsent(inv.getDomain(), inv);
                if (inv.getTeamId()   != null) teamIds.add(inv.getTeamId());
                if (inv.getUgTeamId() != null) teamIds.add(inv.getUgTeamId());
            }
        }

        Map<Long, String> teamNames = new java.util.HashMap<>();
        if (!teamIds.isEmpty()) {
            for (Team t : teamRepo.findAllById(teamIds)) {
                teamNames.put(t.getId(), t.getName());
            }
        }

        Map<Long, long[]> mailCounts = new java.util.HashMap<>();
        if (!alertIds.isEmpty()) {
            for (Object[] row : notificationLogRepo.countByAlertIds(alertIds)) {
                Long alertId = ((Number) row[0]).longValue();
                long sent    = row[1] == null ? 0 : ((Number) row[1]).longValue();
                long failed  = row[2] == null ? 0 : ((Number) row[2]).longValue();
                mailCounts.put(alertId, new long[]{ sent, failed });
            }
        }

        for (AlertEvent ev : events) {
            CertificateInventory inv = invByDomain.get(ev.getDomain());
            if (inv != null) {
                ev.setSyTeamName(inv.getTeamId()   != null ? teamNames.get(inv.getTeamId())   : null);
                ev.setUgTeamName(inv.getUgTeamId() != null ? teamNames.get(inv.getUgTeamId()) : null);
                ev.setCertTier(inv.getTier());
            }
            long[] counts = mailCounts.getOrDefault(ev.getId(), new long[]{0, 0});
            ev.setEmailSentCount(counts[0]);
            ev.setEmailFailedCount(counts[1]);
        }
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable Long id,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        String by = resolveDisplayName(session);
        AlertEvent event = escalationService.acknowledge(id, by);
        auditService.recordAction("ALERT_ACKNOWLEDGE", session, request,
                "ALERT_EVENT", id.toString(),
                "{\"domain\":\"" + event.getDomain() + "\"}");
        return ok(Map.of("data", event, "message", "Alert acknowledged"));
    }

    @PostMapping("/alerts/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(
            @PathVariable Long id,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        String by = resolveDisplayName(session);
        AlertEvent event = escalationService.resolve(id, by);
        auditService.recordAction("ALERT_RESOLVE", session, request,
                "ALERT_EVENT", id.toString(),
                "{\"domain\":\"" + event.getDomain() + "\"}");
        return ok(Map.of("data", event, "message", "Alert resolved"));
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
                toLong(body.get("leader_id")),
                (String) body.get("team_type"));
        auditService.recordAction("TEAM_CREATE", session, request,
                "TEAM", team.getId().toString(),
                "{\"name\":\"" + team.getName() + "\",\"leaderId\":" + team.getLeaderId()
                + ",\"teamType\":\"" + team.getTeamType() + "\"}");
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
                toLong(body.get("leader_id")),
                (String) body.get("team_type"));
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
                toLong(body.get("team_id")),
                (String) body.get("org_role"));
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
                body.get("active") instanceof Boolean ? (Boolean) body.get("active") : null,
                (String) body.get("org_role"));
        return ok(Map.of("data", user));
    }

    @PostMapping("/users/{id}/auto-reset-password")
    public ResponseEntity<Map<String, Object>> autoResetPassword(
            @PathVariable Long id, @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        String adminPwd = body.get("admin_password");
        if (adminPwd == null || adminPwd.isBlank()) {
            throw new IllegalArgumentException("Admin password required");
        }
        String adminUsername = actor(session);

        String tempPwd = userService.adminAutoResetPassword(id, adminUsername, adminPwd);

        AppUser target = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        String emailStatus = emailNotificationService.sendPasswordResetEmail(
                target.getEmail(), target.getUsername(), target.getDisplayName(), tempPwd);

        auditService.recordAction("USER_PASSWORD_AUTO_RESET", session, request,
                "USER", id.toString(),
                "{\"email_status\":\"" + emailStatus.replace("\"", "\\\"") + "\"}");

        return ok(Map.of(
                "message", "Temporary password generated and emailed",
                "email_status", emailStatus));
    }

    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlockUser(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        userService.unlockUser(id);
        auditService.recordAction("USER_UNLOCK", session, request, "USER", id.toString(), null);
        return ok(Map.of("message", "User unlocked"));
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

    private static final java.util.Set<String> NOTE_CATEGORIES =
            java.util.Set.of("NOTE", "DEPLOYMENT", "INCIDENT", "RENEWAL");
    private static final int NOTE_MAX_LENGTH = 5000;
    private static final long NOTE_EDIT_WINDOW_HOURS = 24L;

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
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        if (text.length() > NOTE_MAX_LENGTH)
            throw new IllegalArgumentException("Note exceeds " + NOTE_MAX_LENGTH + " characters");

        String category = body.getOrDefault("category", "NOTE");
        if (!NOTE_CATEGORIES.contains(category))
            throw new IllegalArgumentException("Invalid category: " + category);

        String currentUser = (String) session.getAttribute("username");
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        String createdAt = now();

        CertificateNote note = new CertificateNote();
        note.setDomain(domain);
        note.setTeamId(isAdmin(session) ? null : teamId(session));
        note.setAuthorUsername(currentUser);
        note.setAuthorName(currentName);
        note.setNote(text.trim());
        note.setCategory(category);
        note.setCreatedAt(createdAt);
        CertificateNote saved = noteRepo.save(note);

        writeRevision(saved.getId(), 0, "CREATE", text.trim(), category,
                createdAt, currentUser, currentName, null);
        auditService.recordAction("CERT_NOTE_ADD", session, request,
                "CERT_NOTE", String.valueOf(saved.getId()),
                "{\"domain\":\"" + domain + "\",\"category\":\"" + category + "\"}");
        return ok(Map.of("data", saved, "message", "Note added"));
    }

    @PutMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> updateNote(
            @PathVariable String domain, @PathVariable Long noteId,
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (note.getDeletedAt() != null)
            throw new NoSuchElementException("Note has been deleted");
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);

        String currentUser = (String) session.getAttribute("username");
        if (!java.util.Objects.equals(currentUser, note.getAuthorUsername()))
            throw new SecurityException("NOT_AUTHOR");

        try {
            Instant created = Instant.from(ISO.parse(note.getCreatedAt()));
            if (Instant.now().minusSeconds(NOTE_EDIT_WINDOW_HOURS * 3600L).isAfter(created))
                throw new IllegalStateException("EDIT_WINDOW_EXPIRED");
        } catch (java.time.format.DateTimeParseException ignored) { /* fallback: allow */ }

        String text = body.get("note");
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Note text cannot be blank");
        if (text.length() > NOTE_MAX_LENGTH)
            throw new IllegalArgumentException("Note exceeds " + NOTE_MAX_LENGTH + " characters");

        // Snapshot the PRIOR body+category into a revision before mutating the note.
        String editedAt = now();
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeq = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        writeRevision(noteId, nextSeq, "EDIT", note.getNote(), note.getCategory(),
                editedAt, currentUser, currentName, null);

        note.setNote(text.trim());
        note.setUpdatedAt(editedAt);
        note.setUpdatedBy(currentUser);
        CertificateNote saved = noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_EDIT", session, request,
                "CERT_NOTE", String.valueOf(saved.getId()),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("data", saved, "message", "Note updated"));
    }

    @DeleteMapping("/notes/{domain}/{noteId}")
    public ResponseEntity<Map<String, Object>> deleteNote(
            @PathVariable String domain, @PathVariable Long noteId,
            HttpSession session, HttpServletRequest request) {
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (note.getDeletedAt() != null)
            return ok(Map.of("message", "Note already deleted"));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);

        String currentUser = (String) session.getAttribute("username");
        if (!isAdmin(session) && !java.util.Objects.equals(currentUser, note.getAuthorUsername()))
            throw new SecurityException("Only the author or an admin can delete a note");

        String deletedAt = now();
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeqDel = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeqDel = (maxSeqDel == null ? 0 : maxSeqDel) + 1;
        writeRevision(noteId, nextSeqDel, "DELETE", null, note.getCategory(),
                deletedAt, currentUser, currentName, null);

        note.setDeletedAt(deletedAt);
        note.setDeletedBy(currentUser);
        noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_DELETE", session, request,
                "CERT_NOTE", String.valueOf(noteId),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("message", "Note deleted"));
    }

    @GetMapping("/notes/{domain}/{noteId}/revisions")
    public ResponseEntity<Map<String, Object>> getNoteRevisions(
            @PathVariable String domain, @PathVariable Long noteId, HttpSession session) {
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (!isAdmin(session)) checkOwnership(note.getTeamId(), session);
        return ok(Map.of("data", noteRevisionRepo.findByNoteIdOrderBySequenceNoAsc(noteId)));
    }

    @PostMapping("/notes/{domain}/{noteId}/restore")
    public ResponseEntity<Map<String, Object>> restoreNote(
            @PathVariable String domain, @PathVariable Long noteId,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        CertificateNote note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + noteId));
        if (!domain.equals(note.getDomain()))
            throw new IllegalArgumentException("Note does not belong to domain: " + domain);
        if (note.getDeletedAt() == null)
            return ok(Map.of("data", note, "message", "Note was not deleted"));

        String restoredAt = now();
        String currentUser = (String) session.getAttribute("username");
        String currentName = (String) session.getAttribute("displayName");
        if (currentName == null || currentName.isBlank()) currentName = currentUser;
        Integer maxSeqRest = noteRevisionRepo.findMaxSequenceNo(noteId);
        int nextSeqRest = (maxSeqRest == null ? 0 : maxSeqRest) + 1;
        writeRevision(noteId, nextSeqRest, "RESTORE", null, note.getCategory(),
                restoredAt, currentUser, currentName, null);

        note.setDeletedAt(null);
        note.setDeletedBy(null);
        CertificateNote saved = noteRepo.save(note);
        auditService.recordAction("CERT_NOTE_RESTORE", session, request,
                "CERT_NOTE", String.valueOf(noteId),
                "{\"domain\":\"" + domain + "\"}");
        return ok(Map.of("data", saved, "message", "Note restored"));
    }

    private void writeRevision(Long noteId, int sequenceNo, String eventType,
                               String body, String category,
                               String editedAt, String editedBy, String editedByName,
                               String reason) {
        CertificateNoteRevision rev = new CertificateNoteRevision();
        rev.setNoteId(noteId);
        rev.setSequenceNo(sequenceNo);
        rev.setEventType(eventType);
        rev.setBody(body);
        rev.setCategory(category);
        rev.setEditedAt(editedAt);
        rev.setEditedBy(editedBy);
        rev.setEditedByName(editedByName);
        rev.setReason(reason);
        try { noteRevisionRepo.save(rev); }
        catch (Exception e) { log.warn("Failed to persist note revision: {}", e.getMessage()); }
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

    private String resolveDisplayName(HttpSession session) {
        String dn = (String) session.getAttribute("displayName");
        return (dn != null && !dn.isBlank()) ? dn : (String) session.getAttribute("username");
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
