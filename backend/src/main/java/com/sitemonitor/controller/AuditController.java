package com.sitemonitor.controller;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository           auditLogRepo;
    private final LatestCheckRepository        latestCheckRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository               teamRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> listAudit(
            @RequestParam(defaultValue = "0")     int     page,
            @RequestParam(defaultValue = "50")    int     size,
            @RequestParam(required = false)       String  actor,
            @RequestParam(required = false)       Long    actorId,
            @RequestParam(required = false)       String  eventType,   // CSV: çoklu tür
            @RequestParam(required = false)       String  resourceType,
            @RequestParam(required = false)       String  resourceId,
            @RequestParam(required = false)       String  outcome,
            @RequestParam(required = false)       String  ip,
            @RequestParam(required = false)       String  since,
            @RequestParam(required = false)       String  until,
            @RequestParam(required = false)       String  q,           // serbest metin
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        Page<AuditLog> result = auditLogRepo.findAdvanced(
                like(actor), actorId, !csv(eventType).isEmpty(), typesOrDummy(eventType),
                nil(resourceType), nil(resourceId), nil(outcome), nil(ip),
                nil(since), nil(until), anomalyOnly, like(q),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", result.getContent());
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("total_pages", result.getTotalPages());
        return ok(body);
    }

    /** Bir kaynağın tüm değişiklik geçmişi ("bu izlemeye/kullanıcıya kim ne yaptı"). */
    @GetMapping("/audit/resource/{type}/{id}")
    public ResponseEntity<Map<String, Object>> resourceHistory(
            @PathVariable String type, @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        var rows = auditLogRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                type, id, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
        return ok(Map.of("data", rows, "total", rows.size()));
    }

    /** Bir kullanıcının tüm eylemleri. */
    @GetMapping("/audit/actor/{actorId}")
    public ResponseEntity<Map<String, Object>> actorHistory(
            @PathVariable Long actorId,
            @RequestParam(defaultValue = "100") int limit, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        var rows = auditLogRepo.findByActorIdOrderByEventTimeDesc(
                actorId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
        return ok(Map.of("data", rows, "total", rows.size()));
    }

    /** Aynı correlation ID'den doğan ilişkili olaylar (detay panelinde). */
    @GetMapping("/audit/correlation/{cid}")
    public ResponseEntity<Map<String, Object>> correlated(@PathVariable String cid, HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        return ok(Map.of("data", auditLogRepo.findByCorrelationIdOrderBySeqAsc(cid)));
    }

    /** Hash zinciri bütünlük doğrulaması — kurcalama tespiti. */
    @GetMapping("/audit/integrity")
    public ResponseEntity<Map<String, Object>> integrity(HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");
        AuditService.ChainVerification v = auditService.verifyChain();
        return ok(Map.of("data", Map.of("ok", v.ok(), "checked", v.checked(),
                "broken_seq", v.brokenSeq() == null ? "" : v.brokenSeq(),
                "broken_id", v.brokenId() == null ? "" : v.brokenId())));
    }

    /** Filtreli sonucun CSV/JSON dışa aktarımı — DIŞA AKTARMA İŞLEMİ KENDİSİ DE denetlenir (AUDIT_EXPORT). */
    @GetMapping("/audit/export")
    public ResponseEntity<String> export(
            @RequestParam(defaultValue = "csv")   String  format,
            @RequestParam(required = false)       String  actor,
            @RequestParam(required = false)       Long    actorId,
            @RequestParam(required = false)       String  eventType,
            @RequestParam(required = false)       String  resourceType,
            @RequestParam(required = false)       String  resourceId,
            @RequestParam(required = false)       String  outcome,
            @RequestParam(required = false)       String  ip,
            @RequestParam(required = false)       String  since,
            @RequestParam(required = false)       String  until,
            @RequestParam(required = false)       String  q,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session, HttpServletRequest request) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        int cap = 50_000;
        List<AuditLog> rows = auditLogRepo.findAdvanced(
                like(actor), actorId, !csv(eventType).isEmpty(), typesOrDummy(eventType),
                nil(resourceType), nil(resourceId), nil(outcome), nil(ip),
                nil(since), nil(until), anomalyOnly, like(q),
                PageRequest.of(0, cap)).getContent();

        boolean json = "json".equalsIgnoreCase(format);
        String body = json ? toJson(rows) : toCsv(rows);
        // Denetimin denetimi: kim, hangi filtreyle, kaç kayıt dışa aktardı.
        auditService.recordAction("AUDIT_EXPORT", session, request, "AUDIT_LOG", "export",
                "{\"format\":\"" + (json ? "json" : "csv") + "\",\"rows\":" + rows.size() + "}");

        String fname = "audit-" + now().substring(0, 10) + (json ? ".json" : ".csv");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fname + "\"")
                .header("Content-Type", (json ? "application/json" : "text/csv") + "; charset=utf-8")
                .body(body);
    }

    @GetMapping("/audit/stats")
    public ResponseEntity<Map<String, Object>> auditStats(HttpSession session) {
        requireAuditAccess(session);
        permissionService.require(session, "audit_log.read", "view");

        // Stat üretimi AuditService.buildStats()'a taşındı (60 sn Caffeine cache — audit_log
        // ölçeklenince her açılışta 10 aggregate çalıştırmamak için). Auth burada kalır, cache'lenmez.
        return ok(Map.of("data", auditService.buildStats()));
    }

    @GetMapping("/audit/weak-algorithms")
    public ResponseEntity<Map<String, Object>> weakAlgorithmReport(HttpSession session) {
        permissionService.require(session, "weak_algo.read", "view");
        // Tüm tabloyu çekmek yerine zayıf-algoritma adaylarını DB'de filtrele
        List<LatestCheck> weakCandidates = latestCheckRepo.findWeakAlgorithmCandidates();

        // Envanteri yalnız aday domain'ler için yükle (tüm envanter taranmaz)
        List<String> weakDomains = weakCandidates.stream()
                .map(LatestCheck::getDomain).filter(Objects::nonNull).distinct().toList();
        Map<String, CertificateInventory> invMap = weakDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(weakDomains).stream()
                        .collect(Collectors.toMap(CertificateInventory::getDomain, i -> i, (a, b) -> a));

        // Tüm takım tablosunu çekmek yerine yalnız zayıf-domain envanterindeki takımları yükle
        Set<Long> teamIds = invMap.values().stream()
                .map(CertificateInventory::getTeamId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Team> teamMap = teamIds.isEmpty()
                ? Map.of()
                : teamRepo.findAllById(teamIds).stream()
                        .collect(Collectors.toMap(Team::getId, t -> t, (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (LatestCheck lc : weakCandidates) {
            List<String> weaknesses = new ArrayList<>();
            String severity = classifyWeakness(lc, weaknesses);
            if (severity == null) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain",               lc.getDomain());
            row.put("subject",              lc.getSubject());
            row.put("issuer",               lc.getIssuer());
            row.put("signature_algorithm",  lc.getSignatureAlgorithm());
            row.put("public_key_algorithm", lc.getPublicKeyAlgorithm());
            row.put("public_key_size",      lc.getPublicKeySize());
            row.put("not_after",            lc.getNotAfter());
            row.put("days_remaining",       lc.getDaysRemaining());
            row.put("status",               lc.getStatus());
            row.put("checked_at",           lc.getCheckedAt());
            row.put("weaknesses",           weaknesses);
            row.put("severity",             severity);

            CertificateInventory inv = invMap.get(lc.getDomain());
            if (inv != null) {
                row.put("owner",       inv.getOwner());
                row.put("description", inv.getDescription());
                Long tid = inv.getTeamId();
                row.put("team_id",     tid);
                if (tid != null && teamMap.containsKey(tid)) {
                    row.put("team_name",  teamMap.get(tid).getName());
                    row.put("team_email", teamMap.get(tid).getEmail());
                }
            }
            rows.add(row);
        }

        rows.sort((a, b) -> severityRank(b.get("severity").toString()) - severityRank(a.get("severity").toString()));

        long critical = rows.stream().filter(r -> "CRITICAL".equals(r.get("severity"))).count();
        long high     = rows.stream().filter(r -> "HIGH".equals(r.get("severity"))).count();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data",     rows);
        body.put("total",    rows.size());
        body.put("critical", critical);
        body.put("high",     high);
        return ok(body);
    }

    private String classifyWeakness(LatestCheck lc, List<String> weaknesses) {
        String sig     = lc.getSignatureAlgorithm();
        String keyAlgo = lc.getPublicKeyAlgorithm();
        Integer keySize = lc.getPublicKeySize();
        String maxSev  = null;

        if (sig != null) {
            String up = sig.toUpperCase();
            if (up.contains("MD2") || up.contains("MD5")) {
                weaknesses.add("Deprecated hash: " + sig);
                maxSev = "CRITICAL";
            } else if (up.contains("SHA1") || up.contains("SHA-1")) {
                weaknesses.add("Weak hash: " + sig);
                maxSev = worst(maxSev, "HIGH");
            }
        }

        if (keyAlgo != null && keySize != null) {
            String up = keyAlgo.toUpperCase();
            if (up.contains("RSA") || up.contains("DSA")) {
                if (keySize < 2048) {
                    weaknesses.add("Short key: " + keyAlgo + " " + keySize + "-bit");
                    maxSev = worst(maxSev, keySize <= 1024 ? "CRITICAL" : "HIGH");
                }
            } else if (up.contains("EC")) {
                if (keySize < 256) {
                    weaknesses.add("Short EC key: " + keySize + "-bit");
                    maxSev = worst(maxSev, keySize < 192 ? "CRITICAL" : "HIGH");
                }
            }
        }
        return maxSev;
    }

    private String worst(String cur, String cand) {
        if ("CRITICAL".equals(cur)) return cur;
        if ("CRITICAL".equals(cand)) return cand;
        if ("HIGH".equals(cur)) return cur;
        return cand;
    }

    private int severityRank(String s) {
        if ("CRITICAL".equals(s)) return 2;
        if ("HIGH".equals(s))     return 1;
        return 0;
    }

    private void requireAuditAccess(HttpSession session) {
        String role = (String) session.getAttribute("systemRole");
        if (!SessionScope.isGlobalAdmin(session) && !"AUDIT".equals(role))
            throw new SecurityException("Audit access required");
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", now());
        return ResponseEntity.ok(response);
    }

    private String nil(String v) { return (v == null || v.isBlank()) ? null : v; }
    private String now()         { return ISO.format(Instant.now()); }

    private String like(String s) {
        String v = nil(s);
        return v == null ? null : "%" + v.toLowerCase() + "%";
    }

    private List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /** Boş liste yerine dummy (typeFilter=false kısa-devre ederken IN () çalışmasın). */
    private List<String> typesOrDummy(String s) {
        List<String> c = csv(s);
        return c.isEmpty() ? List.of("") : c;
    }

    private String toCsv(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("seq,event_time,event_type,actor,actor_role,ip_address,resource_type,resource_id,outcome,failure_reason,changes,correlation_id\n");
        for (AuditLog a : rows) {
            sb.append(csvCell(a.getSeq())).append(',').append(csvCell(a.getEventTime())).append(',')
              .append(csvCell(a.getEventType())).append(',').append(csvCell(a.getActor())).append(',')
              .append(csvCell(a.getActorRole())).append(',').append(csvCell(a.getIpAddress())).append(',')
              .append(csvCell(a.getResourceType())).append(',').append(csvCell(a.getResourceId())).append(',')
              .append(csvCell(a.getOutcome())).append(',').append(csvCell(a.getFailureReason())).append(',')
              .append(csvCell(a.getChanges())).append(',').append(csvCell(a.getCorrelationId())).append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(Object o) {
        if (o == null) return "";
        String s = o.toString();
        return (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
                ? "\"" + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\"" : s;
    }

    private String toJson(List<AuditLog> rows) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (AuditLog a : rows) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"seq\":").append(a.getSeq())
              .append(",\"event_time\":").append(js(a.getEventTime()))
              .append(",\"event_type\":").append(js(a.getEventType()))
              .append(",\"actor\":").append(js(a.getActor()))
              .append(",\"actor_role\":").append(js(a.getActorRole()))
              .append(",\"ip_address\":").append(js(a.getIpAddress()))
              .append(",\"resource_type\":").append(js(a.getResourceType()))
              .append(",\"resource_id\":").append(js(a.getResourceId()))
              .append(",\"outcome\":").append(js(a.getOutcome()))
              .append(",\"failure_reason\":").append(js(a.getFailureReason()))
              .append(",\"changes\":").append(js(a.getChanges()))
              .append(",\"row_hash\":").append(js(a.getRowHash()))
              .append(",\"correlation_id\":").append(js(a.getCorrelationId()))
              .append('}');
        }
        return sb.append(']').toString();
    }

    private static String js(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }
}
