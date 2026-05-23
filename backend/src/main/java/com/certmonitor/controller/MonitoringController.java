package com.certmonitor.controller;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.DnsCheckerService;
import com.certmonitor.service.PortCheckerService;
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

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }

    private ResponseEntity<Map<String, Object>> notFound(String msg) {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", msg));
    }

    // ── Uptime Overview ───────────────────────────────────────────────────────

    @GetMapping("/uptime/overview")
    public ResponseEntity<Map<String, Object>> uptimeOverview() {
        List<LatestCheck> latestChecks = latestCheckRepo.findAllByOrderByDomainAsc();
        Map<String, LatestCheck> checkMap = latestChecks.stream()
                .collect(Collectors.toMap(LatestCheck::getDomain, lc -> lc));

        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();

        String cutoff30d = ISO.format(Instant.now().minus(30, ChronoUnit.DAYS));
        String cutoff7d  = ISO.format(Instant.now().minus(7,  ChronoUnit.DAYS));

        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            String domain = inv.getDomain();
            LatestCheck lc = checkMap.get(domain);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain);
            item.put("port",   inv.getPort());

            int port = inv.getPort() != null ? inv.getPort() : 443;
            Optional<UptimeCheck> uc = uptimeCheckRepo.findTopByDomainAndPortOrderByIdDesc(domain, port);

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

            // Uptime % from certificate_checks history
            List<CertificateCheck> history30d = certCheckRepo.findByCheckedAtAfter(cutoff30d).stream()
                    .filter(c -> domain.equals(c.getDomain()))
                    .toList();
            List<CertificateCheck> history7d = history30d.stream()
                    .filter(c -> c.getCheckedAt() != null && c.getCheckedAt().compareTo(cutoff7d) >= 0)
                    .toList();

            item.put("uptime_7d",  calcUptime(history7d));
            item.put("uptime_30d", calcUptime(history30d));
            item.put("incidents_30d", history30d.stream()
                    .filter(c -> "error".equals(c.getStatus())).count());

            result.add(item);
        }

        return ok(result);
    }

    private double calcUptime(List<CertificateCheck> checks) {
        if (checks.isEmpty()) return 100.0;
        long total = checks.size();
        long up    = checks.stream().filter(c -> !"error".equals(c.getStatus())).count();
        return Math.round((up * 1000.0 / total)) / 10.0;
    }

    @GetMapping("/uptime/{domain}/history")
    public ResponseEntity<Map<String, Object>> uptimeHistory(
            @PathVariable String domain,
            @RequestParam(defaultValue = "24") int hours) {

        String cutoff = ISO.format(Instant.now().minus(hours, ChronoUnit.HOURS));
        List<CertificateCheck> checks = certCheckRepo.findByCheckedAtAfter(cutoff).stream()
                .filter(c -> domain.equals(c.getDomain()))
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

        String fromStr = from != null ? from : ISO.format(Instant.now().minus(1, ChronoUnit.DAYS));
        String toStr   = to   != null ? to   : ISO.format(Instant.now());
        if (fromStr.length() == 10) fromStr = fromStr + "T00:00:00";
        if (toStr.length()   == 10) toStr   = toStr   + "T23:59:59";

        List<UptimeCheck> checks = uptimeCheckRepo.findByDomainAndPortAndDateRange(domain, port, fromStr, toStr, limit);
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

        String fromStr = from != null ? from : ISO.format(Instant.now().minus(1, ChronoUnit.DAYS));
        String toStr   = to   != null ? to   : ISO.format(Instant.now());
        if (fromStr.length() == 10) fromStr = fromStr + "T00:00:00";
        if (toStr.length()   == 10) toStr   = toStr   + "T23:59:59";

        List<CertificateCheck> checks = certCheckRepo.findByDomainAndDateRange(domain, fromStr, toStr, limit);
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
    public ResponseEntity<Map<String, Object>> listPort() {
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        String now = ISO.format(Instant.now());
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            int invPort = inv.getPort() != null ? inv.getPort() : 443;
            PortMonitor monitor = portMonitorRepo.findFirstByHostAndPortOrderByIdAsc(inv.getDomain(), invPort)
                    .orElseGet(() -> {
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
                        return portMonitorRepo.save(m);
                    });
            result.add(enrichPort(monitor, portCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(monitor.getId()).orElse(null)));
        }
        return ok(result);
    }

    @PostMapping("/port")
    public ResponseEntity<Map<String, Object>> createPort(@RequestBody Map<String, Object> body) {
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
        return ok(enrichPort(saved, null));
    }

    @PutMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> updatePort(@PathVariable Long id, @RequestBody Map<String, Object> body) {
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
            return ok(enrichPort(saved, portCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null)));
        }).orElse(notFound("Port monitor not found"));
    }

    @DeleteMapping("/port/{id}")
    public ResponseEntity<Map<String, Object>> deletePort(@PathVariable Long id) {
        return portMonitorRepo.findById(id).map(m -> {
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            portMonitorRepo.save(m);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("Port monitor not found"));
    }

    @GetMapping("/port/{id}/history")
    public ResponseEntity<Map<String, Object>> portHistory(@PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        List<PortCheck> checks = portCheckRepo.findByMonitorIdOrderByCheckedAtDesc(id)
                .stream().limit(limit).toList();
        return ok(checks);
    }

    @PostMapping("/port/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerPort(@PathVariable Long id) {
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
            return ok(enrichPort(m, check));
        }).orElse(notFound("Port monitor not found"));
    }

    private Map<String, Object> enrichPort(PortMonitor m, PortCheck latest) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",              m.getId());
        item.put("name",            m.getName());
        item.put("host",            m.getHost());
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
    public ResponseEntity<Map<String, Object>> listDns() {
        List<CertificateInventory> inventory = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        String now = ISO.format(Instant.now());
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory inv : inventory) {
            DnsMonitor monitor = dnsMonitorRepo.findFirstByDomainOrderByIdAsc(inv.getDomain())
                    .orElseGet(() -> {
                        DnsMonitor m = new DnsMonitor();
                        m.setName(inv.getDomain());
                        m.setDomain(inv.getDomain());
                        m.setRecordType("A");
                        m.setActive(true);
                        m.setIntervalSeconds(300);
                        m.setCreatedAt(now);
                        m.setUpdatedAt(now);
                        return dnsMonitorRepo.save(m);
                    });
            result.add(enrichDns(monitor, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(monitor.getId()).orElse(null)));
        }
        return ok(result);
    }

    @PostMapping("/dns")
    public ResponseEntity<Map<String, Object>> createDns(@RequestBody Map<String, Object> body) {
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
        return ok(enrichDns(saved, null));
    }

    @PutMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> updateDns(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return dnsMonitorRepo.findById(id).map(m -> {
            if (body.get("name")            != null) m.setName((String) body.get("name"));
            if (body.get("domain")          != null) m.setDomain((String) body.get("domain"));
            if (body.get("recordType")      != null) m.setRecordType(((String) body.get("recordType")).toUpperCase());
            if (body.get("active")          != null) m.setActive((Boolean) body.get("active"));
            if (body.get("intervalSeconds") != null) m.setIntervalSeconds(((Number) body.get("intervalSeconds")).intValue());
            m.setUpdatedAt(ISO.format(Instant.now()));
            DnsMonitor saved = dnsMonitorRepo.save(m);
            return ok(enrichDns(saved, dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(id).orElse(null)));
        }).orElse(notFound("DNS monitor not found"));
    }

    @DeleteMapping("/dns/{id}")
    public ResponseEntity<Map<String, Object>> deleteDns(@PathVariable Long id) {
        return dnsMonitorRepo.findById(id).map(m -> {
            m.setActive(false);
            m.setUpdatedAt(ISO.format(Instant.now()));
            dnsMonitorRepo.save(m);
            return ok(Map.of("deleted", true));
        }).orElse(notFound("DNS monitor not found"));
    }

    @GetMapping("/dns/{id}/history")
    public ResponseEntity<Map<String, Object>> dnsHistory(@PathVariable Long id,
            @RequestParam(defaultValue = "100") int limit) {
        List<DnsRecord> records = dnsRecordRepo.findByMonitorIdOrderByCheckedAtDesc(id)
                .stream().limit(limit).toList();
        return ok(records);
    }

    @PostMapping("/dns/{id}/check")
    public ResponseEntity<Map<String, Object>> triggerDns(@PathVariable Long id) {
        return dnsMonitorRepo.findById(id).map(m -> {
            Map<String, Object> r = dnsChecker.check(m.getDomain(), m.getRecordType());
            String now = ISO.format(Instant.now());

            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) r.getOrDefault("values", List.of());
            String valueStr = String.join("\n", values);

            // Change detection
            DnsRecord prev = dnsRecordRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null);
            String prevValue = prev != null ? prev.getValue() : null;
            boolean changed = prevValue != null && !prevValue.equals(valueStr);

            DnsRecord record = new DnsRecord();
            record.setMonitorId(m.getId());
            record.setRecordType(m.getRecordType());
            record.setValue(valueStr);
            record.setChanged(changed);
            record.setPreviousValue(prevValue);
            record.setCheckedAt(now);
            dnsRecordRepo.save(record);

            return ok(enrichDns(m, record));
        }).orElse(notFound("DNS monitor not found"));
    }

    private Map<String, Object> enrichDns(DnsMonitor m, DnsRecord latest) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",              m.getId());
        item.put("name",            m.getName());
        item.put("domain",          m.getDomain());
        item.put("record_type",     m.getRecordType());
        item.put("active",          m.getActive());
        item.put("interval_seconds",m.getIntervalSeconds());
        item.put("created_at",      m.getCreatedAt());
        item.put("updated_at",      m.getUpdatedAt());
        if (latest != null) {
            item.put("value",        latest.getValue());
            item.put("changed",      latest.getChanged());
            item.put("checked_at",   latest.getCheckedAt());
        } else {
            item.put("value",        null);
            item.put("changed",      false);
            item.put("checked_at",   null);
        }
        return item;
    }
}
