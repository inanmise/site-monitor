package com.certmonitor.controller;

import com.certmonitor.model.AuditLog;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AuditLogRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.TeamRepository;
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

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> listAudit(
            @RequestParam(defaultValue = "0")     int     page,
            @RequestParam(defaultValue = "50")    int     size,
            @RequestParam(required = false)       String  actor,
            @RequestParam(required = false)       String  eventType,
            @RequestParam(required = false)       String  outcome,
            @RequestParam(required = false)       String  since,
            @RequestParam(required = false)       String  until,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session) {
        requireAuditAccess(session);

        size = Math.min(size, 200);
        String actorParam = (actor == null || actor.isBlank()) ? null : "%" + actor.toLowerCase() + "%";
        Page<AuditLog> result = auditLogRepo.findFiltered(
                actorParam, nil(eventType), nil(outcome), nil(since), nil(until), anomalyOnly,
                PageRequest.of(page, size));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", result.getContent());
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("total_pages", result.getTotalPages());
        resp.put("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/audit/stats")
    public ResponseEntity<Map<String, Object>> auditStats(HttpSession session) {
        requireAuditAccess(session);

        String last24h = ISO.format(Instant.now().minusSeconds(86_400));
        String last7d  = ISO.format(Instant.now().minusSeconds(7 * 86_400L));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_24h",        auditLogRepo.countEventsSince(last24h));
        stats.put("anomalies_24h",    auditLogRepo.countAnomaliesSince(last24h));
        stats.put("failed_logins_24h",auditLogRepo.countFailedLoginsSince(last24h));
        stats.put("total_7d",         auditLogRepo.countEventsSince(last7d));
        stats.put("anomalies_7d",     auditLogRepo.countAnomaliesSince(last7d));
        stats.put("failed_logins_7d", auditLogRepo.countFailedLoginsSince(last7d));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", stats);
        resp.put("timestamp", now());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/audit/weak-algorithms")
    public ResponseEntity<Map<String, Object>> weakAlgorithmReport(HttpSession session) {
        // Tüm tabloyu çekmek yerine zayıf-algoritma adaylarını DB'de filtrele
        List<LatestCheck> weakCandidates = latestCheckRepo.findWeakAlgorithmCandidates();

        // Envanteri yalnız aday domain'ler için yükle (tüm envanter taranmaz)
        List<String> weakDomains = weakCandidates.stream()
                .map(LatestCheck::getDomain).filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, CertificateInventory> invMap = weakDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(weakDomains).stream()
                        .collect(Collectors.toMap(CertificateInventory::getDomain, i -> i, (a, b) -> a));

        Map<Long, Team> teamMap = teamRepo.findAll().stream()
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

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success",   true);
        resp.put("data",      rows);
        resp.put("total",     rows.size());
        resp.put("critical",  critical);
        resp.put("high",      high);
        resp.put("timestamp", now());
        return ResponseEntity.ok(resp);
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
        if (!"ADMIN".equals(role) && !"AUDIT".equals(role))
            throw new SecurityException("Audit access required");
    }

    private String nil(String v) { return (v == null || v.isBlank()) ? null : v; }
    private String now()         { return ISO.format(Instant.now()); }
}
