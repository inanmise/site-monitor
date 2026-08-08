package com.sitemonitor.controller;

import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Incidents Overview — tüm monitörlerin makine-üretimi olaylarını (AlertEvent) tek tabloda toplar.
 * Olay = teyitli hatada AÇILAN / recovery'de KAPANAN alarm; yaşam döngüsü EscalationService+MonitoringOutageService'te.
 * Bu controller yalnız SUNUM + yorum dizisi + (admin) silme sağlar; alarm üretimi/çözümü buraya AİT DEĞİLDİR.
 * Yetki: görüntüleme alerts.read, yorum alerts.actions, incident silme yalnız global ADMIN. Takım-kapsamı (IDOR) uygulanır.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/incidents")
@RequiredArgsConstructor
public class IncidentsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int MAX_COMMENT = 5000;

    private final AlertEventRepository alertEventRepo;
    private final AlertCommentRepository commentRepo;
    private final HttpMonitorRepository httpMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PingMonitorRepository pingMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final DomainMonitorRepository domainMonitorRepo;
    private final PageMonitorRepository pageMonitorRepo;
    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Liste ──────────────────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,      // all | ongoing | resolved
            @RequestParam(required = false) String rootCause,   // alertType pill
            @RequestParam(required = false) String q,           // monitör adı/host araması (LIKE domain)
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        int sz = Math.max(1, Math.min(size, 200));
        Boolean resolved = "ongoing".equalsIgnoreCase(status) ? Boolean.FALSE
                : "resolved".equalsIgnoreCase(status) ? Boolean.TRUE : null;
        String type = (rootCause != null && !rootCause.isBlank()) ? rootCause.trim() : null;
        String qEff = (q != null && !q.isBlank()) ? q.trim() : null;

        // Takım kapsamı (IDOR): global viewer tümünü; aksi halde yalnız kapsamdaki takımlar
        List<Long> scope = SessionScope.isGlobalViewer(session) ? null : SessionScope.viewTeamIds(session);
        boolean scoped = scope != null;
        if (scoped && scope.isEmpty())
            return ok(Map.of("data", List.of(), "total", 0L, "page", 0, "size", sz, "type_counts", Map.of()));
        List<Long> scopeList = scoped ? scope : List.of(-1L);

        Page<AlertEvent> result = alertEventRepo.findIncidents(
                resolved, since, until, type, qEff, scoped, scopeList,
                PageRequest.of(Math.max(0, page), sz, sortFor(sort, dir)));
        List<AlertEvent> events = result.getContent();

        Map<Long, Map<String, Object>> monitors = resolveMonitors(events);
        Map<Long, Long> commentCounts = commentCounts(events);
        List<Map<String, Object>> data = events.stream().map(e -> toDto(e, monitors, commentCounts)).toList();

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        for (Object[] row : alertEventRepo.countIncidentsByType(resolved, since, until, qEff, scoped, scopeList))
            typeCounts.put(String.valueOf(row[0]), (Long) row[1]);

        return ok(Map.of(
                "data",        data,
                "total",       result.getTotalElements(),
                "page",        result.getNumber(),
                "size",        result.getSize(),
                "type_counts", typeCounts));
    }

    /** Sıralama: varsayılan ongoing-first + en yeni; kolon seçilirse o alan + createdAt tie-breaker. */
    private Sort sortFor(String sort, String dir) {
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return switch (sort == null ? "started" : sort) {
            case "status"   -> Sort.by(d, "resolved").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "severity" -> Sort.by(d, "alertLevel").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "type"     -> Sort.by(d, "alertType").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "started"  -> Sort.by(d, "createdAt");
            // varsayılan (istek dışı): ongoing-first + en yeni
            default         -> Sort.by(Sort.Direction.ASC, "resolved").and(Sort.by(Sort.Direction.DESC, "createdAt"));
        };
    }

    private Map<String, Object> toDto(AlertEvent e, Map<Long, Map<String, Object>> monitors, Map<Long, Long> counts) {
        Map<String, Object> ctx = deserialize(e.getContextJson());
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",            e.getId());
        dto.put("status",        Boolean.TRUE.equals(e.getResolved()) ? "resolved" : "ongoing");
        Map<String, Object> monitor = monitors.get(e.getId());
        if (monitor == null) {
            // Map.of null değer kabul etmez → domain null olan olaylarda NPE olmasın diye null-güvenli varsayılan.
            monitor = new LinkedHashMap<>();
            monitor.put("name", e.getDomain());
            monitor.put("type", "cert");
            monitor.put("tab", "dashboard");
        }
        dto.put("monitor",       monitor);
        dto.put("root_cause",    rootCause(e.getAlertType(), ctx));
        dto.put("comment_count", counts.getOrDefault(e.getId(), 0L));
        dto.put("alert_type",    e.getAlertType());
        dto.put("alert_level",   e.getAlertLevel());
        dto.put("started_at",    e.getCreatedAt());
        dto.put("resolved_at",   e.getResolvedAt());
        dto.put("acknowledged",  e.getAcknowledged());
        dto.put("domain",        e.getDomain());
        dto.put("message",       e.getMessage());
        return dto;
    }

    // ── Root-cause türetimi (kod + kategori; etiket/renk frontend'de i18n'lenir) ──
    private Map<String, String> rootCause(String type, Map<String, Object> ctx) {
        Integer http = ctx != null && ctx.get("http_status") instanceof Number n ? n.intValue() : null;
        String err = ctx != null && ctx.get("last_error") != null ? ctx.get("last_error").toString().toLowerCase(Locale.ROOT) : null;
        String code, cat;
        if (type == null) { code = "?"; cat = "unknown"; }
        else if (type.endsWith("_SLOW"))              { code = "SLOW"; cat = "slow"; }
        else if (type.endsWith("_SSL"))               { code = "SSL";  cat = "ssl"; }
        else if (type.endsWith("DOMAIN_EXPIRY") || "DOMAINMON_EXPIRY".equals(type) || "EXPIRY".equals(type)) { code = "EXPIRY"; cat = "expiry"; }
        else if ("HTTP_DOWN".equals(type)) {
            if (http != null) { code = String.valueOf(http); cat = http >= 500 ? "server_error" : http == 404 ? "not_found" : http >= 400 ? "client_error" : "down"; }
            else { code = "DOWN"; cat = "down"; }
        }
        else if ("PORT_DOWN".equals(type)) {
            if (err != null && err.contains("refused"))                              { code = "REFUSED";     cat = "refused"; }
            else if (err != null && (err.contains("timed out") || err.contains("timeout"))) { code = "TIMEOUT"; cat = "timeout"; }
            else if (err != null && err.contains("unreachable"))                     { code = "UNREACHABLE"; cat = "unreachable"; }
            else { code = "DOWN"; cat = "down"; }
        }
        else if ("PING_DOWN".equals(type) || "ACCESSIBILITY".equals(type)) { code = "DOWN"; cat = "down"; }
        else if ("KEYWORD".equals(type))          { code = "CONTENT"; cat = "content"; }
        else if ("DNS_FAILURE".equals(type))      { code = "DNS";  cat = "dns_failure"; }
        else if (type.startsWith("DNS_"))         { code = "DNS";  cat = "dns"; }
        else if (type.startsWith("DOMAINMON_"))   { code = "DOMAIN"; cat = "domain"; }
        else if ("REVOKED".equals(type) || "MISMATCH".equals(type) || "CHAIN_BROKEN".equals(type)) { code = type; cat = "cert"; }
        else if ("SCRIPTED_FAIL".equals(type))    { code = "SYNTHETIC"; cat = "down"; }
        else if ("PAGE_DOWN".equals(type))        { code = "DOWN";      cat = "down"; }
        else if ("PAGE_INTEGRITY".equals(type))   { code = "INTEGRITY"; cat = "content"; }
        else { code = type; cat = "unknown"; }
        return Map.of("code", code, "category", cat);
    }

    // ── Monitör çözümleme (AlertEvent.domain → ad + tip + tab + monitor_id) — N+1'siz ──
    private Map<Long, Map<String, Object>> resolveMonitors(List<AlertEvent> events) {
        Map<Long, Map<String, Object>> byEvent = new HashMap<>();
        if (events.isEmpty()) return byEvent;
        Set<String> fams = events.stream().map(e -> family(e.getAlertType())).collect(Collectors.toSet());
        Map<String, Map<String, Object[]>> idx = new HashMap<>();   // family → (key → [name, id])
        if (fams.contains("http"))    idx.put("http",    index(httpMonitorRepo.findAll(),    m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("port"))    idx.put("port",    index(portMonitorRepo.findAll(),    m -> m.getHost(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("keyword")) idx.put("keyword", index(keywordMonitorRepo.findAll(), m -> m.getUrl(),    m -> m.getName(), m -> m.getId()));
        if (fams.contains("ping"))    idx.put("ping",    index(pingMonitorRepo.findAll(),    m -> m.getHost(),   m -> m.getName(), m -> m.getId()));
        if (fams.contains("dns"))     idx.put("dns",     index(dnsMonitorRepo.findAll(),     m -> m.getDomain(), m -> m.getName(), m -> m.getId()));
        if (fams.contains("domain"))  idx.put("domain",  index(domainMonitorRepo.findAll(),  m -> m.getDomain(), m -> m.getName(), m -> m.getId()));
        if (fams.contains("page"))     idx.put("page",     index(pageMonitorRepo.findAll(),     m -> m.getUrl(),  m -> m.getName(), m -> m.getId()));
        if (fams.contains("scripted")) idx.put("scripted", index(scriptedMonitorRepo.findAll(), m -> m.getName(), m -> m.getName(), m -> m.getId()));
        for (AlertEvent e : events) {
            String fam = family(e.getAlertType());
            Object[] ref = idx.containsKey(fam) ? idx.get(fam).get(e.getDomain()) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ref != null ? ref[0] : e.getDomain());
            m.put("type", fam);
            m.put("tab",  tabFor(fam));
            m.put("monitor_id", ref != null ? ref[1] : null);
            byEvent.put(e.getId(), m);
        }
        return byEvent;
    }

    private static <T> Map<String, Object[]> index(List<T> rows, Function<T, String> key, Function<T, String> name, Function<T, Long> id) {
        Map<String, Object[]> m = new HashMap<>();
        for (T r : rows) {
            String k = key.apply(r);
            if (k != null) m.putIfAbsent(k, new Object[]{ name.apply(r), id.apply(r) });
        }
        return m;
    }

    private static String family(String type) {
        if (type == null) return "cert";
        if (type.startsWith("KEYWORD"))   return "keyword";
        if (type.startsWith("PORT_"))     return "port";
        if (type.startsWith("PING"))      return "ping";
        if (type.startsWith("DNS_"))      return "dns";
        if (type.startsWith("DOMAINMON_"))return "domain";
        if (type.startsWith("PAGE_"))     return "page";
        if (type.startsWith("SCRIPTED_")) return "scripted";
        if ("HTTP_DOWN".equals(type) || "HTTP_SSL".equals(type) || "DOMAIN_EXPIRY".equals(type)) return "http";
        return "cert";   // EXPIRY / CHAIN_BROKEN / REVOKED / MISMATCH / ACCESSIBILITY
    }

    private static String tabFor(String fam) {
        return switch (fam) {
            case "http" -> "http"; case "port" -> "port"; case "keyword" -> "keyword";
            case "ping" -> "ping"; case "dns" -> "dns";  case "domain" -> "domain";
            case "page" -> "page"; case "scripted" -> "scripted";
            default -> "dashboard";
        };
    }

    private Map<Long, Long> commentCounts(List<AlertEvent> events) {
        List<Long> ids = events.stream().map(AlertEvent::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] row : commentRepo.countByAlertIds(ids))
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        return out;
    }

    /**
     * Tek olay — e-postadaki "Olay detayını görüntüle / Olaya yorum yap" derin linkleri için.
     * Sayfalı listede olay 1. sayfada olmayabilir; bu uç doğrudan getirir. Yetki + takım
     * izolasyonu listeyle aynı ({@code alerts.read} + {@code requireIncidentScope}).
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        AlertEvent ev = requireAlert(id);
        requireIncidentScope(session, ev);
        List<AlertEvent> one = List.of(ev);
        return ok(Map.of("data", toDto(ev, resolveMonitors(one), commentCounts(one))));
    }

    // ── Yorumlar ─────────────────────────────────────────────────────────────────
    @GetMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> listComments(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        requireIncidentScope(session, requireAlert(id));
        return ok(Map.of("data", commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(id)));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "alerts.actions", "execute");
        requireIncidentScope(session, requireAlert(id));
        String text = body.get("body") != null ? body.get("body").toString().trim() : "";
        if (text.isEmpty()) throw new IllegalArgumentException("Yorum boş olamaz");
        if (text.length() > MAX_COMMENT) throw new IllegalArgumentException("Yorum " + MAX_COMMENT + " karakteri aşamaz");
        String user = (String) session.getAttribute("username");
        String name = (String) session.getAttribute("displayName");
        if (name == null || name.isBlank()) name = user;
        AlertComment c = new AlertComment();
        c.setAlertEventId(id);
        c.setBody(text);
        c.setTeamId(sessionTeamId(session));
        c.setAuthorUsername(user);
        c.setAuthorName(name);
        c.setCreatedAt(now());
        AlertComment saved = commentRepo.save(c);
        auditService.recordAction("INCIDENT_COMMENT_ADD", session, request, "ALERT_EVENT", String.valueOf(id), "{}");
        return ok(Map.of("data", saved, "message", "Comment added"));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Map<String, Object>> deleteComment(
            @PathVariable Long commentId, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "alerts.actions", "execute");
        AlertComment c = commentRepo.findById(commentId)
                .orElseThrow(() -> new NoSuchElementException("Yorum bulunamadı: " + commentId));
        if (c.getDeletedAt() != null) return ok(Map.of("message", "Zaten silinmiş"));
        String user = (String) session.getAttribute("username");
        String role = (String) session.getAttribute("systemRole");
        boolean elevated = SessionScope.isGlobalAdmin(session) || "ADMIN".equals(role) || "TEAM_ADMIN".equals(role);
        if (!elevated && (user == null || !user.equals(c.getAuthorUsername())))
            throw new SecurityException("Yalnız yorumu ekleyen veya takım yöneticisi silebilir");
        c.setDeletedAt(now());
        c.setDeletedBy(user != null ? user : "anonymous");
        commentRepo.save(c);
        auditService.recordAction("INCIDENT_COMMENT_DELETE", session, request, "ALERT_EVENT",
                String.valueOf(c.getAlertEventId()), "{}");
        return ok(Map.of("message", "Comment deleted"));
    }

    // ── Incident silme (yalnız global ADMIN) — yıkıcı: alarm kaydı + yorumları gider ──
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteIncident(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "alerts.actions", "execute");
        if (!SessionScope.isGlobalAdmin(session))
            throw new SecurityException("Incident silme yalnız yöneticiye açıktır");
        AlertEvent ev = requireAlert(id);
        for (AlertComment c : commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(id))
            commentRepo.deleteById(c.getId());
        alertEventRepo.deleteById(id);
        auditService.recordAction("ALERT_DELETE", session, request, "ALERT_EVENT", String.valueOf(id),
                "{\"domain\":\"" + (ev.getDomain() == null ? "" : ev.getDomain().replace("\"", "\\\"")) + "\"}");
        return ok(Map.of("message", "Incident deleted"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private AlertEvent requireAlert(Long id) {
        return alertEventRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Incident bulunamadı: " + id));
    }

    /** Takım kapsamı (IDOR): global viewer serbest; aksi halde alarmın takımı (teamId veya domain→envanter SY/UG) kapsamda olmalı. */
    private void requireIncidentScope(HttpSession session, AlertEvent ev) {
        if (SessionScope.isGlobalViewer(session)) return;
        List<Long> scope = SessionScope.viewTeamIds(session);
        boolean ok = scope != null && !scope.isEmpty() && (
                (ev.getTeamId() != null && scope.contains(ev.getTeamId()))
                || (ev.getDomain() != null && inventoryRepo.findByDomain(ev.getDomain())
                        .map(inv -> (inv.getTeamId() != null && scope.contains(inv.getTeamId()))
                                 || (inv.getUgTeamId() != null && scope.contains(inv.getUgTeamId())))
                        .orElse(false)));
        if (!ok) throw new SecurityException("Bu incident üzerinde yetkiniz yok");
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, Map.class); } catch (Exception e) { return null; }
    }

    private Long sessionTeamId(HttpSession session) {
        Object v = session.getAttribute("teamId");
        return v instanceof Number num ? num.longValue() : null;
    }

    private static String now() { return ISO.format(Instant.now()); }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(r);
    }
}
