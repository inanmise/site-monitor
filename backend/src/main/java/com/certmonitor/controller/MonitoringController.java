package com.certmonitor.controller;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.CertificateService;
import com.certmonitor.service.DnsCheckerService;
import com.certmonitor.service.PortCheckerService;
import com.certmonitor.service.KeywordCheckerService;
import com.certmonitor.service.PingCheckerService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final LatestCheckRepository latestCheckRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final CertificateCheckRepository certCheckRepo;
    private final UptimeCheckRepository uptimeCheckRepo;

    private final PortMonitorRepository portMonitorRepo;
    private final PortCheckRepository portCheckRepo;
    private final PortCheckerService portChecker;

    private final DnsMonitorRepository dnsMonitorRepo;
    private final DnsRecordRepository dnsRecordRepo;
    private final DnsCheckerService dnsChecker;

    private final KeywordMonitorRepository keywordMonitorRepo;
    private final KeywordResultRepository keywordResultRepo;
    private final KeywordCheckerService keywordChecker;

    private final PingMonitorRepository pingMonitorRepo;
    private final PingCheckRepository pingCheckRepo;
    private final PingCheckerService pingChecker;

    private final TeamRepository teamRepo;

    /** domain/host → sorumlu takım adı (izleme ekranlarında takım gösterimi/filtresi). */
    private final CertificateService certificateService;
    private final com.certmonitor.service.PermissionService permissionService;
    private final com.certmonitor.service.EscalationService escalationService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    /** Write endpoints are admin-only — USER role gets a 403 via GlobalExceptionHandler. */
    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException("Admin access required");
        }
    }

    /** Oturum sahibinin kendi takımı (session "teamId"). */
    private static Long sessionTeamId(HttpSession session) {
        Object v = session != null ? session.getAttribute("teamId") : null;
        if (v instanceof Number n) return n.longValue();
        if (v != null) { try { return Long.valueOf(v.toString().trim()); } catch (Exception ignored) {} }
        return null;
    }

    /** Serbest-form izleme (keyword/ping) üzerinde yazma/çalıştırma kapsamı:
     *  global admin → her takım; TEAM_ADMIN → yönetim kapsamındaki takımlar; USER → kendi takımı. */
    private boolean canOperateTeam(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalAdmin(session)) return true;
        if (teamId == null) return false;
        if (SessionScope.canManage(session, teamId)) return true;   // TEAM_ADMIN yönetim kapsamı
        return teamId.equals(sessionTeamId(session));               // USER kendi takımı
    }

    /** Oluştururken hedef takımı çözer: global admin istediğini (veya takımsız) atar; diğerleri
     *  yalnız iş görebildikleri bir takıma — değilse kendi takımlarına düşer. */
    private Long resolveWriteTeam(HttpSession session, Map<String, Object> body) {
        Long requested = body.get("teamId") instanceof Number n ? n.longValue() : null;
        if (SessionScope.isGlobalAdmin(session)) return requested;
        if (requested != null && canOperateTeam(session, requested)) return requested;
        return sessionTeamId(session);
    }

    /** Güncellemede takım değişimini çözer: admin serbest; diğerleri yalnız iş görebildikleri
     *  bir takıma taşıyabilir — yetkisiz/null hedef yok sayılır (mevcut takım korunur). */
    private Long resolveTeamChange(HttpSession session, Long current, Object requestedRaw) {
        Long requested = requestedRaw instanceof Number n ? n.longValue() : null;
        if (SessionScope.isGlobalAdmin(session)) return requested;
        if (requested != null && canOperateTeam(session, requested)) return requested;
        return current;
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", msg));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", msg));
    }

    private static boolean blank(Object o) {
        return o == null || o.toString().isBlank();
    }

    private static final java.util.Set<String> KW_OPERATORS = java.util.Set.of("GTE", "LTE", "EQ", "GT", "LT");

    /** Keyword adet koşulunu (operator + matchCount) body'den uygular; legacy 'condition' desteklenir;
     *  alertCondition (NOT NULL) operatörden türetilir. */
    private void applyKeywordCondition(KeywordMonitor m, Map<String, Object> body) {
        if (body.get("operator") != null) {
            String op = body.get("operator").toString().toUpperCase();
            m.setMatchOperator(KW_OPERATORS.contains(op) ? op : "GTE");
        }
        if (body.get("matchCount") instanceof Number n) m.setMatchCount(Math.max(0, n.intValue()));
        else if (body.get("operator") == null && body.get("condition") != null) {
            if ("CONTAINS".equals(body.get("condition").toString())) { m.setMatchOperator("LTE"); m.setMatchCount(0); }
            else { m.setMatchOperator("GTE"); m.setMatchCount(1); }
        }
        String op = m.getMatchOperator() != null ? m.getMatchOperator() : "GTE";
        int n = m.getMatchCount() != null ? m.getMatchCount() : 1;
        boolean absent = ("LTE".equals(op) || "EQ".equals(op) || "LT".equals(op)) && n == 0;
        m.setAlertCondition(absent ? "CONTAINS" : "NOT_CONTAINS");
    }

    // ── Uptime Overview ───────────────────────────────────────────────────────

    @GetMapping("/uptime/overview")
    public ResponseEntity<Map<String, Object>> uptimeOverview(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<LatestCheck> latestChecks = latestCheckRepo.findAllByOrderByDomainAsc();
        Map<String, LatestCheck> checkMap = latestChecks.stream()
                .collect(Collectors.toMap(LatestCheck::getDomain, lc -> lc));

        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        Map<String, String> teamMap = certificateService.domainTeamNameMap();

        String cutoff30d = ISO.format(Instant.now().minus(30, ChronoUnit.DAYS));
        String cutoff7d  = ISO.format(Instant.now().minus(7,  ChronoUnit.DAYS));
        String cutoff24h = ISO.format(Instant.now().minus(24, ChronoUnit.HOURS));

        // Son 24 saatteki tüm HTTP (uptime) kontrolleri — tek toplu sorgu, domaine göre grupla (N sorgu yok).
        Map<String, List<UptimeCheck>> http24hByDomain = uptimeCheckRepo.findByCheckedAtGreaterThanEqual(cutoff24h).stream()
                .collect(Collectors.groupingBy(UptimeCheck::getDomain));

        // En güncel uptime kontrolü (domain:port) — tek sorgu (eski: domain başına findTop... → N+1).
        Map<String, UptimeCheck> latestUptime = uptimeCheckRepo.findLatestPerDomainPort().stream()
                .collect(Collectors.toMap(u -> u.getDomain() + ":" + u.getPort(), u -> u, (a, b) -> a));

        // Uptime % / incident özetleri — domain başına tüm-tablo taraması yerine iki gruplu DB sorgusu.
        Map<String, long[]> agg30 = aggregateByDomain(certCheckRepo.aggregateStatusCountsSince(cutoff30d));
        Map<String, long[]> agg7  = aggregateByDomain(certCheckRepo.aggregateStatusCountsSince(cutoff7d));

        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            String domain = inv.getDomain();
            LatestCheck lc = checkMap.get(domain);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain);
            item.put("port",   inv.getPort());
            item.put("team_name", teamMap.get(domain));

            int port = inv.getPort() != null ? inv.getPort() : 443;
            Optional<UptimeCheck> uc = Optional.ofNullable(latestUptime.get(domain + ":" + port));

            // HTTP-OK: son 24h kontrolleri varsa hepsi "up" mı? (kayıt yoksa null → gösterme)
            List<UptimeCheck> http24h = http24hByDomain.get(domain);
            Boolean httpOk = (http24h == null || http24h.isEmpty())
                    ? null
                    : http24h.stream().allMatch(c -> "up".equals(c.getStatus()));
            item.put("http_ok", httpOk);

            if (lc == null) {
                item.put("status",           uc.map(UptimeCheck::getStatus).orElse("unknown"));
                item.put("response_ms",      uc.map(UptimeCheck::getResponseMs).orElse(null));
                item.put("uptime_checked_at",uc.map(UptimeCheck::getCheckedAt).orElse(null));
                item.put("ssl_checked_at",   null);
                item.put("ssl_valid_days",   null);
                item.put("ssl_not_after",    null);
                item.put("uptime_7d",        null);
                item.put("uptime_30d",       null);
                item.put("incidents_30d",    0);
                result.add(item);
                continue;
            }

            String sslStatus = "error".equals(lc.getStatus()) ? "down" : "up";
            item.put("status",           uc.map(UptimeCheck::getStatus).orElse(sslStatus));
            item.put("response_ms",      uc.map(UptimeCheck::getResponseMs).orElse(null));
            item.put("uptime_checked_at",uc.map(UptimeCheck::getCheckedAt).orElse(null));
            item.put("ssl_checked_at",   lc.getCheckedAt());
            item.put("ssl_valid_days",   lc.getDaysRemaining());
            item.put("ssl_not_after",    lc.getNotAfter());

            // Uptime % — önceden hesaplanan domain-bazlı toplam/hata sayılarından (kayıt yoksa 100% / 0 olay).
            long[] s30 = agg30.get(domain);
            long[] s7  = agg7.get(domain);
            item.put("uptime_7d",  s7  == null ? 100.0 : uptimePct(s7[0],  s7[1]));
            item.put("uptime_30d", s30 == null ? 100.0 : uptimePct(s30[0], s30[1]));
            item.put("incidents_30d", s30 == null ? 0L : s30[1]);

            result.add(item);
        }

        return ok(result);
    }

    /** aggregateStatusCountsSince satırlarını domain → [toplam, hata] map'ine indeksle. */
    private static Map<String, long[]> aggregateByDomain(List<Object[]> rows) {
        Map<String, long[]> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((String) r[0], new long[]{ ((Number) r[1]).longValue(), ((Number) r[2]).longValue() });
        }
        return m;
    }

    /** toplam/hata → uptime yüzdesi (eski calcUptime ile aynı yuvarlama; "error" dışı = up). */
    private static double uptimePct(long total, long errors) {
        if (total == 0) return 100.0;
        long up = total - errors;
        return Math.round((up * 1000.0 / total)) / 10.0;
    }

    /** Normalize date/datetime strings to full ISO-8601 (19 chars) for "from" range end. */
    private static String normalizeFrom(String s) {
        if (s == null) return s;
        if (s.length() == 10) return s + "T00:00:00";   // date only  → start of day
        if (s.length() == 16) return s + ":00";          // HH:MM      → :00 seconds
        return s;
    }

    /** Normalize date/datetime strings to full ISO-8601 (19 chars) for "to" range end. */
    private static String normalizeTo(String s) {
        if (s == null) return s;
        if (s.length() == 10) return s + "T23:59:59";   // date only  → end of day
        if (s.length() == 16) return s + ":59";          // HH:MM      → :59 seconds
        return s;
    }

    @GetMapping("/uptime/{domain}/history")
    public ResponseEntity<Map<String, Object>> uptimeHistory(
            @PathVariable String domain,
            @RequestParam(defaultValue = "24") int hours) {

        String cutoff = ISO.format(Instant.now().minus(hours, ChronoUnit.HOURS));
        String nowIso = ISO.format(Instant.now());
        // Domain-kapsamlı sorgu (idx_cc_domain_ts) — tüm cert_checks tablosunu yükleyip
        // in-memory domain filtrelemek yerine; tek domain'in geçmişi.
        List<CertificateCheck> checks = certCheckRepo.findByDomainAndDateRange(domain, cutoff, nowIso, 5000).stream()
                .sorted(Comparator.comparing(CertificateCheck::getCheckedAt))
                .toList();

        // Build hourly bars
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = hours - 1; i >= 0; i--) {
            LocalDateTime hourStart = now.minusHours(i).truncatedTo(ChronoUnit.HOURS);
            LocalDateTime hourEnd   = hourStart.plusHours(1);
            String startStr = hourStart.atZone(ZoneOffset.UTC).format(ISO);
            String endStr   = hourEnd.atZone(ZoneOffset.UTC).format(ISO);

            List<CertificateCheck> hourChecks = checks.stream()
                    .filter(c -> c.getCheckedAt() != null
                            && c.getCheckedAt().compareTo(startStr) >= 0
                            && c.getCheckedAt().compareTo(endStr) < 0)
                    .toList();

            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("hour", startStr);
            if (hourChecks.isEmpty()) {
                bar.put("status", "unknown");
                bar.put("response_ms", null);
            } else {
                boolean anyError = hourChecks.stream().anyMatch(c -> "error".equals(c.getStatus()));
                bar.put("status", anyError ? "down" : "up");
                bar.put("response_ms", null);
            }
            bars.add(bar);
        }

        // Response time series (individual check points)
        List<Map<String, Object>> responseTimes = checks.stream()
                .map(c -> {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("ts", c.getCheckedAt());
                    pt.put("status", c.getStatus());
                    return pt;
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", domain);
        data.put("hours",  hours);
        data.put("bars",   bars);
        data.put("response_times", responseTimes);

        return ok(data);
    }

    // ── HTTP Uptime History ───────────────────────────────────────────────────

    @GetMapping("/uptime/{domain}/http-history")
    public ResponseEntity<Map<String, Object>> uptimeHttpHistory(
            @PathVariable String domain,
            @RequestParam(defaultValue = "443") int port,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {

        String fromStr = normalizeFrom(from != null ? from : ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        String toStr   = normalizeTo  (to   != null ? to   : ISO.format(Instant.now()));

        int cap = Math.max(1, Math.min(limit, 10_000));
        List<UptimeCheck> checks = uptimeCheckRepo.findByDomainAndPortAndDateRange(domain, port, fromStr, toStr, cap);
        List<Map<String, Object>> result = checks.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("checked_at",  c.getCheckedAt());
            item.put("status",      c.getStatus());
            item.put("response_ms", c.getResponseMs());
            item.put("error",       c.getError());
            return item;
        }).toList();
        return ok(result);
    }

    // ── SSL Certificate History ───────────────────────────────────────────────

    @GetMapping("/uptime/{domain}/ssl-history")
    public ResponseEntity<Map<String, Object>> uptimeSslHistory(
            @PathVariable String domain,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {

        String fromStr = normalizeFrom(from != null ? from : ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)));
        String toStr   = normalizeTo  (to   != null ? to   : ISO.format(Instant.now()));

        int cap = Math.max(1, Math.min(limit, 10_000));
        List<CertificateCheck> checks = certCheckRepo.findByDomainAndDateRange(domain, fromStr, toStr, cap);
        List<Map<String, Object>> result = checks.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("checked_at",     c.getCheckedAt());
            item.put("status",         c.getStatus());
            item.put("days_remaining", c.getDaysRemaining());
            item.put("error",          c.getError());
            return item;
        }).toList();
        return ok(result);
    }

    // ── Port Monitors ─────────────────────────────────────────────────────────

    @GetMapping("/port")
    public ResponseEntity<Map<String, Object>> listPort(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        String now = ISO.format(Instant.now());

        // Tüm monitörleri tek sorguda yükle, host:port ile indeksle (en küçük id = findFirst...OrderByIdAsc).
        Map<String, PortMonitor> monitorByKey = new HashMap<>();
        for (PortMonitor m : portMonitorRepo.findAll()) {
            monitorByKey.merge(m.getHost() + ":" + m.getPort(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
        }

        // Eksik domainler için monitörleri tek batch insert'te lazy-provision et (eski: döngüde tek tek save).
        List<PortMonitor> toCreate = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            int invPort = inv.getPort() != null ? inv.getPort() : 443;
            String key = inv.getDomain() + ":" + invPort;
            if (!monitorByKey.containsKey(key)) {
                PortMonitor m = new PortMonitor();
                m.setName(inv.getDomain());
                m.setHost(inv.getDomain());
                m.setPort(invPort);
                m.setProtocol("TCP");
                m.setActive(true);
                m.setIntervalSeconds(60);
                m.setTimeoutMs(5000);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                monitorByKey.put(key, m);
                toCreate.add(m);
            }
        }
        if (!toCreate.isEmpty()) portMonitorRepo.saveAll(toCreate);

        // Her monitör için en güncel kontrol — tek toplu sorgu (eski: monitör başına findTop... → N+1).
        Map<Long, PortCheck> latestByMonitor = portCheckRepo.findLatestPerMonitor().stream()
                .filter(pc -> pc.getMonitorId() != null)
                .collect(Collectors.toMap(PortCheck::getMonitorId, pc -> pc, (a, b) -> a));

        Map<String, String> teamMap = certificateService.domainTeamNameMap();
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            int invPort = inv.getPort() != null ? inv.getPort() : 443;
            PortMonitor monitor = monitorByKey.get(inv.getDomain() + ":" + invPort);
            PortCheck latest = monitor.getId() != null ? latestByMonitor.get(monitor.getId()) : null;
            result.add(enrichPort(monitor, latest, teamMap));
        }
        return ok(result);
    }

    @PostMapping("/port")
    public ResponseEntity<Map<String, Object>> createPort(@RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.crud", "edit");
        String now = ISO.format(Instant.now());
        PortMonitor m = new PortMonitor();
        m.setName((String) body.get("name"));
        m.setHost((String) body.get("host"));
        m.setPort(((Number) body.get("port")).intValue());
        m.setProtocol(body.getOrDefault("protocol", "TCP").toString());
        m.setActive(true);
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        PortMonitor saved = portMonitorRepo.save(m);
        return ok(enrichPort(saved, null, certificateService.domainTeamNameMap()));
    }

    @PutMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> updatePort(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.crud", "edit");
        return portMonitorRepo.findById(id).map(m -> {
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("host")            != null) m.setHost((String) body.get("host"));
            if (body.get("port")            != null) m.setPort(((Number) body.get("port")).intValue());
            if (body.get("protocol")        != null) m.setProtocol((String) body.get("protocol"));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            m.setUpdatedAt(ISO.format(Instant.now()));
            PortMonitor saved = portMonitorRepo.save(m);
            return ok(enrichPort(saved, portCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), certificateService.domainTeamNameMap()));
        }).orElse(notFound("Port monitor not found"));
    }

    @DeleteMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> deletePort(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.crud", "edit");
        return portMonitorRepo.findById(id).map(m -> {
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            portMonitorRepo.save(m);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Port monitor not found"));
    }

    @GetMapping("/port/{id}/history")
    public ResponseEntity<Map<String, Object>> portHistory(@PathVariable Long id,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        List<PortCheck> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = portCheckRepo.findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(id, cutoff)
                    .stream().limit(500).toList();           // liste için kapak; özet DB count'tan
            total = portCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = portCheckRepo.countByMonitorIdAndOpenFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = portCheckRepo.findByMonitorIdOrderByCheckedAtDesc(id).stream().limit(cap).toList();
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getOpen())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    @PostMapping("/port/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPort(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.trigger", "execute");
        return portMonitorRepo.findById(id).map(m -> {
            Map<String, Object> r = portChecker.check(m.getHost(), m.getPort(), m.getTimeoutMs());
            String now = ISO.format(Instant.now());
            PortCheck check = new PortCheck();
            check.setMonitorId(m.getId());
            check.setOpen((Boolean) r.getOrDefault("open", false));
            check.setResponseMs(r.get("response_ms") != null ? ((Number) r.get("response_ms")).longValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(now);
            portCheckRepo.save(check);
            return ok(enrichPort(m, check, certificateService.domainTeamNameMap()));
        }).orElse(notFound("Port monitor not found"));
    }

    private Map<String, Object> enrichPort(PortMonitor m, PortCheck latest, Map<String, String> teamMap) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",              m.getId());
        item.put("name",            m.getName());
        item.put("host",            m.getHost());
        item.put("team_name",       teamMap.get(m.getHost()));
        item.put("port",            m.getPort());
        item.put("protocol",        m.getProtocol());
        item.put("active",          m.getActive());
        item.put("interval_seconds",m.getIntervalSeconds());
        item.put("timeout_ms",      m.getTimeoutMs());
        item.put("created_at",      m.getCreatedAt());
        item.put("updated_at",      m.getUpdatedAt());
        if (latest != null) {
            item.put("status",      latest.getOpen() ? "open" : "closed");
            item.put("response_ms", latest.getResponseMs());
            item.put("checked_at",  latest.getCheckedAt());
            item.put("error",       latest.getError());
        } else {
            item.put("status",      "unknown");
            item.put("response_ms", null);
            item.put("checked_at",  null);
            item.put("error",       null);
        }
        return item;
    }

    // ── DNS Monitors ──────────────────────────────────────────────────────────

    @GetMapping("/dns")
    public ResponseEntity<Map<String, Object>> listDns(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        String now = ISO.format(Instant.now());

        // Tüm monitörleri tek sorguda yükle, domain ile indeksle (en küçük id = findFirst...OrderByIdAsc).
        Map<String, DnsMonitor> monitorByDomain = new HashMap<>();
        for (DnsMonitor m : dnsMonitorRepo.findAll()) {
            monitorByDomain.merge(m.getDomain(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
        }

        // Eksik domainler için monitörleri tek batch insert'te lazy-provision et (eski: döngüde tek tek save).
        List<DnsMonitor> toCreate = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            if (!monitorByDomain.containsKey(inv.getDomain())) {
                DnsMonitor m = new DnsMonitor();
                m.setName(inv.getDomain());
                m.setDomain(inv.getDomain());
                m.setRecordType("A");
                m.setActive(true);
                m.setIntervalSeconds(300);
                m.setCreatedAt(now);
                m.setUpdatedAt(now);
                monitorByDomain.put(inv.getDomain(), m);
                toCreate.add(m);
            }
        }
        if (!toCreate.isEmpty()) dnsMonitorRepo.saveAll(toCreate);

        // Her monitör için en güncel kayıt — tek toplu sorgu (eski: monitör başına findTop... → N+1).
        Map<Long, DnsRecord> latestByMonitor = dnsRecordRepo.findLatestPerMonitor().stream()
                .filter(r -> r.getMonitorId() != null)
                .collect(Collectors.toMap(DnsRecord::getMonitorId, r -> r, (a, b) -> a));

        Map<String, String> teamMap = certificateService.domainTeamNameMap();
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            DnsMonitor monitor = monitorByDomain.get(inv.getDomain());
            DnsRecord latest = monitor.getId() != null ? latestByMonitor.get(monitor.getId()) : null;
            result.add(enrichDns(monitor, latest, teamMap));
        }
        return ok(result);
    }

    @PostMapping("/dns")
    public ResponseEntity<Map<String, Object>> createDns(@RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.crud", "edit");
        String now = ISO.format(Instant.now());
        DnsMonitor m = new DnsMonitor();
        m.setName((String) body.get("name"));
        m.setDomain((String) body.get("domain"));
        m.setRecordType(((String) body.get("recordType")).toUpperCase());
        m.setActive(true);
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        DnsMonitor saved = dnsMonitorRepo.save(m);
        return ok(enrichDns(saved, null, certificateService.domainTeamNameMap()));
    }

    @PutMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> updateDns(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.crud", "edit");
        return dnsMonitorRepo.findById(id).map(m -> {
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("domain")          != null) m.setDomain((String) body.get("domain"));
            if (body.get("recordType")      != null) m.setRecordType(((String) body.get("recordType")).toUpperCase());
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            m.setUpdatedAt(ISO.format(Instant.now()));
            DnsMonitor saved = dnsMonitorRepo.save(m);
            return ok(enrichDns(saved, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), certificateService.domainTeamNameMap()));
        }).orElse(notFound("DNS monitor not found"));
    }

    @DeleteMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> deleteDns(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.crud", "edit");
        return dnsMonitorRepo.findById(id).map(m -> {
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            dnsMonitorRepo.save(m);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("DNS monitor not found"));
    }

    @GetMapping("/dns/{id}/history")
    public ResponseEntity<Map<String, Object>> dnsHistory(@PathVariable Long id,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "5000") int limit) {
        List<DnsRecord> records;
        if (days != null && days > 0) {
            int d = Math.min(days, 90);
            String cutoff = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
            records = dnsRecordRepo.findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(id, cutoff);
        } else {
            records = dnsRecordRepo.findByMonitorIdOrderByCheckedAtDesc(id);
        }
        int cap = Math.max(1, Math.min(limit, 10_000));
        return ok(records.stream().limit(cap).toList());
    }

    @PostMapping("/dns/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerDns(@PathVariable Long id, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "monitoring.trigger", "execute");
        return dnsMonitorRepo.findById(id).map(m -> {
            Map<String, Object> r = dnsChecker.check(m.getDomain(), m.getRecordType());
            String now = ISO.format(Instant.now());

            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) r.getOrDefault("values", List.of());
            String valueStr = String.join("\n", values);

            // Smart change detection: distinguishes rotation (round-robin) from real changes.
            DnsRecord prev = dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null);
            String prevValue = prev != null ? prev.getValue() : null;
            DnsCheckerService.ChangeKind kind = DnsCheckerService.detectChange(prevValue, valueStr);

            DnsRecord record = new DnsRecord();
            record.setMonitorId(m.getId());
            record.setRecordType(m.getRecordType());
            record.setValue(valueStr);
            record.setChanged(kind == DnsCheckerService.ChangeKind.CHANGED);
            record.setRotated(kind == DnsCheckerService.ChangeKind.ROTATED);
            record.setPreviousValue(prevValue);
            record.setCheckedAt(now);
            record.setTtl(r.get("ttl") instanceof Number n ? n.longValue() : null);
            record.setResponseMs(r.get("response_ms") instanceof Number rn ? rn.longValue() : null);
            dnsRecordRepo.save(record);

            return ok(enrichDns(m, record, certificateService.domainTeamNameMap()));
        }).orElse(notFound("DNS monitor not found"));
    }

    /** Domain için tüm temel kayıt tipleri + SOA + authoritative NS — detail modal'da kullanılır. */
    @GetMapping("/dns/{id}/details")
    public ResponseEntity<Map<String, Object>> dnsDetails(@PathVariable Long id) {
        return dnsMonitorRepo.findById(id).map(m -> {
            Map<String, Object> data = new LinkedHashMap<>(dnsChecker.enrichedQuery(m.getDomain()));
            data.put("monitor", enrichDns(m, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null), certificateService.domainTeamNameMap()));
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        }).orElse(notFound("DNS monitor not found"));
    }

    private Map<String, Object> enrichDns(DnsMonitor m, DnsRecord latest, Map<String, String> teamMap) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",              m.getId());
        item.put("name",            m.getName());
        item.put("domain",          m.getDomain());
        item.put("team_name",       teamMap.get(m.getDomain()));
        item.put("record_type",     m.getRecordType());
        item.put("active",          m.getActive());
        item.put("interval_seconds",m.getIntervalSeconds());
        item.put("created_at",      m.getCreatedAt());
        item.put("updated_at",      m.getUpdatedAt());
        if (latest != null) {
            item.put("value",        latest.getValue());
            item.put("changed",      latest.getChanged());
            item.put("rotated",      Boolean.TRUE.equals(latest.getRotated()));
            item.put("checked_at",   latest.getCheckedAt());
            item.put("ttl",          latest.getTtl());
            item.put("response_ms",  latest.getResponseMs());
        } else {
            item.put("value",        null);
            item.put("changed",      false);
            item.put("rotated",      false);
            item.put("checked_at",   null);
            item.put("ttl",          null);
            item.put("response_ms",  null);
        }
        return item;
    }

    // ── Keyword Monitors (serbest-form) ───────────────────────────────────────

    @GetMapping("/keyword")
    public ResponseEntity<Map<String, Object>> listKeyword(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, KeywordResult> latest = keywordResultRepo.findLatestPerMonitor().stream()
                .filter(r -> r.getMonitorId() != null)
                .collect(Collectors.toMap(KeywordResult::getMonitorId, r -> r, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        List<Map<String, Object>> result = keywordMonitorRepo.findAllByOrderByNameAsc().stream()
                .map(m -> enrichKeyword(m, latest.get(m.getId()), teams)).toList();
        return ok(result);
    }

    @PostMapping("/keyword")
    public ResponseEntity<Map<String, Object>> createKeyword(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (blank(body.get("url")) || blank(body.get("keyword"))) return badRequest("url ve keyword zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null && !SessionScope.isGlobalAdmin(session)) return badRequest("Bir takıma atanmamışsınız; izleme oluşturulamıyor");
        String now = ISO.format(Instant.now());
        KeywordMonitor m = new KeywordMonitor();
        m.setName((String) body.get("name"));
        m.setUrl((String) body.get("url"));
        m.setKeyword((String) body.get("keyword"));
        applyKeywordCondition(m, body);
        if (body.containsKey("groupName")) m.setGroupName(blank(body.get("groupName")) ? null : body.get("groupName").toString().trim());
        m.setTeamId(teamId);
        m.setActive(true);
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        KeywordMonitor saved = keywordMonitorRepo.save(m);
        return ok(enrichKeyword(saved, null, teamNameMap()));
    }

    @PutMapping("/keyword/{id}")
    public ResponseEntity<Map<String, Object>> updateKeyword(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("url")             != null) m.setUrl((String) body.get("url"));
            if (body.get("keyword")         != null) m.setKeyword((String) body.get("keyword"));
            if (body.get("operator") != null || body.get("matchCount") != null || body.get("condition") != null) applyKeywordCondition(m, body);
            if (body.containsKey("groupName"))       m.setGroupName(blank(body.get("groupName")) ? null : body.get("groupName").toString().trim());
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            m.setUpdatedAt(ISO.format(Instant.now()));
            KeywordMonitor saved = keywordMonitorRepo.save(m);
            return ok(enrichKeyword(saved, keywordResultRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap()));
        }).orElse(notFound("Keyword monitor not found"));
    }

    @DeleteMapping("/keyword/{id}")
    public ResponseEntity<Map<String, Object>> deleteKeyword(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            // Silme kaynaklı kapanma: açık alarmı sessizce resolved'a geçir (çözüldü maili YOK).
            escalationService.resolveOpenAlertsSilently(m.getUrl(),
                    java.util.Set.of(com.certmonitor.service.EscalationService.TYPE_KEYWORD), "Sistem (izleme silindi)");
            keywordMonitorRepo.delete(m);   // hard delete — "Sil" listeden kaldırır ("Aktif" toggle ayrı)
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Keyword monitor not found"));
    }

    @GetMapping("/keyword/{id}/history")
    public ResponseEntity<Map<String, Object>> keywordHistory(@PathVariable Long id,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        List<KeywordResult> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = keywordResultRepo.findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(id, cutoff)
                    .stream().limit(500).toList();
            total = keywordResultRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = keywordResultRepo.countByMonitorIdAndOkFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = keywordResultRepo.findByMonitorIdOrderByCheckedAtDesc(id).stream().limit(cap).toList();
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getOk())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    @PostMapping("/keyword/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerKeyword(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return keywordMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = keywordChecker.check(m.getUrl(), m.getKeyword(),
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 10000);
            boolean found = Boolean.TRUE.equals(r.getOrDefault("found", false));
            int count = r.get("count") instanceof Number cn ? cn.intValue() : (found ? 1 : 0);
            int threshold = m.getMatchCount() != null ? m.getMatchCount() : 1;
            boolean ok = r.get("error") == null && com.certmonitor.service.KeywordCheckerService.evaluate(count, m.getMatchOperator(), threshold);
            KeywordResult res = new KeywordResult();
            res.setMonitorId(m.getId());
            res.setFound(found);
            res.setOccurrences(count);
            res.setOk(ok);
            res.setHttpStatus(r.get("http_status") instanceof Number n ? n.intValue() : null);
            res.setResponseMs(r.get("response_ms") instanceof Number n ? n.longValue() : null);
            res.setSnippet((String) r.get("snippet"));
            res.setError((String) r.get("error"));
            res.setCheckedAt(ISO.format(Instant.now()));
            keywordResultRepo.save(res);
            return ok(enrichKeyword(m, res, teamNameMap()));
        }).orElse(notFound("Keyword monitor not found"));
    }

    /**
     * Canlı koşul testi — kaydetmeden, formdaki değerlerle (url/keyword/operator/matchCount)
     * URL'yi çekip koşulun sağlanıp sağlanmadığını döndürür. "Test" butonu kullanır.
     */
    @PostMapping("/keyword/test")
    public ResponseEntity<Map<String, Object>> testKeyword(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        String url = body.get("url") != null ? body.get("url").toString().trim() : "";
        String keyword = body.get("keyword") != null ? body.get("keyword").toString() : "";
        if (url.isEmpty() || keyword.isEmpty()) return badRequest("url ve keyword zorunlu");
        String op = body.get("operator") != null ? body.get("operator").toString() : "GTE";
        if (!KW_OPERATORS.contains(op)) op = "GTE";
        int threshold = body.get("matchCount") instanceof Number mn ? mn.intValue() : 1;
        int timeoutMs = body.get("timeoutMs") instanceof Number tn ? tn.intValue() : 10000;
        Map<String, Object> r = keywordChecker.check(url, keyword, timeoutMs);
        int count = r.get("count") instanceof Number cn ? cn.intValue() : 0;
        boolean met = r.get("error") == null && KeywordCheckerService.evaluate(count, op, threshold);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("occurrences",   count);
        out.put("condition_met", met);
        out.put("http_status",   r.get("http_status"));
        out.put("response_ms",   r.get("response_ms"));
        out.put("snippet",       r.get("snippet"));
        out.put("error",         r.get("error"));
        out.put("phrase",        KeywordCheckerService.opPhrase(op, threshold));
        return ok(out);
    }

    private Map<String, Object> enrichKeyword(KeywordMonitor m, KeywordResult latest, Map<Long, String> teams) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("url",              m.getUrl());
        item.put("keyword",          m.getKeyword());
        item.put("condition",        m.getAlertCondition());
        item.put("operator",         m.getMatchOperator());
        item.put("match_count",      m.getMatchCount());
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        if (latest != null) {
            item.put("status",      latest.getError() != null ? "error" : (Boolean.TRUE.equals(latest.getOk()) ? "up" : "down"));
            item.put("found",       latest.getFound());
            item.put("occurrences", latest.getOccurrences());
            item.put("ok",          latest.getOk());
            item.put("http_status", latest.getHttpStatus());
            item.put("response_ms", latest.getResponseMs());
            item.put("snippet",     latest.getSnippet());
            item.put("error",       latest.getError());
            item.put("checked_at",  latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("found", null); item.put("occurrences", null); item.put("ok", null); item.put("http_status", null);
            item.put("response_ms", null); item.put("snippet", null); item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    // ── Ping Monitors (serbest-form) ──────────────────────────────────────────

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> listPing(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<Long, PingCheck> latest = pingCheckRepo.findLatestPerMonitor().stream()
                .filter(c -> c.getMonitorId() != null)
                .collect(Collectors.toMap(PingCheck::getMonitorId, c -> c, (a, b) -> a));
        Map<Long, String> teams = teamNameMap();
        List<Map<String, Object>> result = pingMonitorRepo.findAllByOrderByNameAsc().stream()
                .map(m -> enrichPing(m, latest.get(m.getId()), teams)).toList();
        return ok(result);
    }

    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> createPing(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        if (blank(body.get("host"))) return badRequest("host zorunlu");
        Long teamId = resolveWriteTeam(session, body);
        if (teamId == null && !SessionScope.isGlobalAdmin(session)) return badRequest("Bir takıma atanmamışsınız; izleme oluşturulamıyor");
        String now = ISO.format(Instant.now());
        PingMonitor m = new PingMonitor();
        m.setName((String) body.get("name"));
        m.setHost((String) body.get("host"));
        String ipv = body.get("ipVersion") != null ? body.get("ipVersion").toString() : "auto";
        m.setIpVersion(Set.of("v4", "v6", "auto").contains(ipv) ? ipv : "auto");
        if (body.containsKey("groupName")) m.setGroupName(blank(body.get("groupName")) ? null : body.get("groupName").toString().trim());
        m.setTeamId(teamId);
        m.setActive(true);
        if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
        if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
        if (body.get("packetCount")     != null) m.setPacketCount(((Number) body.get("packetCount")).intValue());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        PingMonitor saved = pingMonitorRepo.save(m);
        return ok(enrichPing(saved, null, teamNameMap()));
    }

    @PutMapping("/ping/{id}")
    public ResponseEntity<Map<String, Object>> updatePing(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return pingMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini düzenleyemezsiniz");
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("host")            != null) m.setHost((String) body.get("host"));
            if (body.get("ipVersion")       != null) { String v = body.get("ipVersion").toString(); m.setIpVersion(Set.of("v4","v6","auto").contains(v) ? v : "auto"); }
            if (body.containsKey("groupName"))       m.setGroupName(blank(body.get("groupName")) ? null : body.get("groupName").toString().trim());
            if (body.containsKey("teamId"))          m.setTeamId(resolveTeamChange(session, m.getTeamId(), body.get("teamId")));
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            if (body.get("timeoutMs")       != null) m.setTimeoutMs(((Number) body.get("timeoutMs")).intValue());
            if (body.get("packetCount")     != null) m.setPacketCount(((Number) body.get("packetCount")).intValue());
            m.setUpdatedAt(ISO.format(Instant.now()));
            PingMonitor saved = pingMonitorRepo.save(m);
            return ok(enrichPing(saved, pingCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null), teamNameMap()));
        }).orElse(notFound("Ping monitor not found"));
    }

    @DeleteMapping("/ping/{id}")
    public ResponseEntity<Map<String, Object>> deletePing(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        return pingMonitorRepo.findById(id).map(m -> {
            if (!SessionScope.canManage(session, m.getTeamId())) throw new SecurityException("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)");
            // Silme kaynaklı kapanma: açık alarmı sessizce resolved'a geçir (çözüldü maili YOK).
            escalationService.resolveOpenAlertsSilently(m.getHost(),
                    java.util.Set.of(com.certmonitor.service.EscalationService.TYPE_PING_DOWN), "Sistem (izleme silindi)");
            pingMonitorRepo.delete(m);   // hard delete — "Sil" listeden kaldırır ("Aktif" toggle ayrı)
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Ping monitor not found"));
    }

    @GetMapping("/ping/{id}/history")
    public ResponseEntity<Map<String, Object>> pingHistory(@PathVariable Long id,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "100") int limit) {
        List<PingCheck> checks;
        long total, down;
        if (days != null && days > 0) {
            String cutoff = ISO.format(Instant.now().minus(days, ChronoUnit.DAYS));
            checks = pingCheckRepo.findByMonitorIdAndCheckedAtGreaterThanEqualOrderByCheckedAtDesc(id, cutoff)
                    .stream().limit(500).toList();
            total = pingCheckRepo.countByMonitorIdAndCheckedAtGreaterThanEqual(id, cutoff);
            down  = pingCheckRepo.countByMonitorIdAndUpFalseAndCheckedAtGreaterThanEqual(id, cutoff);
        } else {
            int cap = Math.max(1, Math.min(limit, 10_000));
            checks = pingCheckRepo.findByMonitorIdOrderByCheckedAtDesc(id).stream().limit(cap).toList();
            total = checks.size();
            down  = checks.stream().filter(c -> !Boolean.TRUE.equals(c.getUp())).count();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", checks);
        out.put("total", total);
        out.put("down", down);
        return ok(out);
    }

    @PostMapping("/ping/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPing(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        return pingMonitorRepo.findById(id).map(m -> {
            if (!canOperateTeam(session, m.getTeamId())) throw new SecurityException("Bu takımın izlemesini çalıştıramazsınız");
            Map<String, Object> r = pingChecker.check(m.getHost(), m.getIpVersion(),
                    m.getPacketCount() != null ? m.getPacketCount() : 4,
                    m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000);
            PingCheck check = new PingCheck();
            check.setMonitorId(m.getId());
            check.setUp(Boolean.TRUE.equals(r.getOrDefault("up", false)));
            check.setRttMs(r.get("rtt_ms") instanceof Number n ? n.longValue() : null);
            check.setPacketLoss(r.get("packet_loss") instanceof Number n ? n.intValue() : null);
            check.setError((String) r.get("error"));
            check.setCheckedAt(ISO.format(Instant.now()));
            pingCheckRepo.save(check);
            return ok(enrichPing(m, check, teamNameMap()));
        }).orElse(notFound("Ping monitor not found"));
    }

    private Map<String, Object> enrichPing(PingMonitor m, PingCheck latest, Map<Long, String> teams) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",               m.getId());
        item.put("name",             m.getName());
        item.put("host",             m.getHost());
        item.put("ip_version",       m.getIpVersion());
        item.put("group_name",       m.getGroupName());
        item.put("team_id",          m.getTeamId());
        item.put("team_name",        m.getTeamId() != null ? teams.get(m.getTeamId()) : null);
        item.put("active",           m.getActive());
        item.put("interval_seconds", m.getIntervalSeconds());
        item.put("timeout_ms",       m.getTimeoutMs());
        item.put("packet_count",     m.getPacketCount());
        if (latest != null) {
            boolean up = Boolean.TRUE.equals(latest.getUp());
            boolean na = !up && latest.getError() != null && latest.getError().contains("ICMP bu ortamda");
            item.put("status",      up ? "up" : (na ? "na" : "down"));
            item.put("up",          latest.getUp());
            item.put("rtt_ms",      latest.getRttMs());
            item.put("packet_loss", latest.getPacketLoss());
            item.put("error",       latest.getError());
            item.put("checked_at",  latest.getCheckedAt());
        } else {
            item.put("status", "unknown");
            item.put("up", null); item.put("rtt_ms", null); item.put("packet_loss", null);
            item.put("error", null); item.put("checked_at", null);
        }
        return item;
    }

    private Map<Long, String> teamNameMap() {
        // 60s cache'li (CertificateService.teamNamesById) — her liste isteğinde teamRepo.findAll() yok.
        return certificateService.teamNamesById();
    }
}
