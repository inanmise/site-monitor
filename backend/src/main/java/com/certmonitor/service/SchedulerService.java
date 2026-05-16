package com.certmonitor.service;

import com.certmonitor.model.AlertThreshold;
import com.certmonitor.model.CertificateInventory;
import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final CertificateCheckerService checkerService;
    private final CertificateService certService;
    private final EmailNotificationService emailService;
    private final EscalationService escalationService;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final JdbcTemplate jdbcTemplate;

    @Value("${cert.monitor.cert-list-file:../sertifikaListesi.txt}")
    private String certListFile;

    /** Stale threshold: a domain not checked within this many minutes is considered stale */
    private static final int STALE_MINUTES = 65;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AtomicReference<LocalDateTime> lastRun    = new AtomicReference<>();
    private final AtomicBoolean                  running    = new AtomicBoolean(false);
    private final AtomicReference<String>        currentRunId = new AtomicReference<>("");

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        log.info("Application started — bootstrapping inventory and running initial check...");
        applySchemaPatches();
        ensureDefaultThreshold();
        importTxtFileIfInventoryEmpty();
        runCheck();  // syncLatestChecksToInventory() is called inside runCheck()
    }

    /** Idempotent DDL patches for columns that ddl-auto=update may miss on existing SQLite tables. */
    private void applySchemaPatches() {
        patch("ALTER TABLE certificate_checks ADD COLUMN run_id TEXT");
        patch("ALTER TABLE alert_events ADD COLUMN resolved_by TEXT");
        patch("ALTER TABLE notification_logs ADD COLUMN message TEXT");
    }

    private void patch(String ddl) {
        try {
            jdbcTemplate.execute(ddl);
            log.info("Schema patch applied: {}", ddl);
        } catch (Exception e) {
            log.debug("Schema patch skipped ({}): {}", ddl, e.getMessage());
        }
    }

    /** Full sweep: runs at the top of every hour (e.g. 10:00, 11:00, 12:00 ...) */
    @Scheduled(cron = "0 0 * * * *")
    public void scheduledHourlyCheck() {
        log.info("Hourly scheduled check triggered");
        runCheck();
    }

    /**
     * Stale sweep: runs every 5 minutes (starting 5 min after startup).
     * Checks only domains that have not been checked in the last {@value #STALE_MINUTES} minutes.
     * Acts as a safety net for gaps caused by errors, new inventory entries, or missed hourly runs.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void checkStaleInventory() {
        // Keep inventory in sync before evaluating staleness
        syncLatestChecksToInventory();

        String cutoff = ISO.format(Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES));

        Set<String> freshDomains = latestCheckRepo.findByCheckedAtGreaterThanEqual(cutoff).stream()
                .map(lc -> lc.getDomain())
                .collect(Collectors.toSet());

        List<Map<String, Object>> staleDomains = inventoryRepo.findByActiveTrueOrderByDomainAsc().stream()
                .filter(item -> !freshDomains.contains(item.getDomain()))
                .map(item -> Map.<String, Object>of("domain", item.getDomain(), "port", item.getPort()))
                .toList();

        if (staleDomains.isEmpty()) {
            log.debug("Stale sweep: all active domains are fresh (checked within {} min)", STALE_MINUTES);
            return;
        }

        log.info("Stale sweep: {} domain(s) not checked in {} min — checking now", staleDomains.size(), STALE_MINUTES);
        runCheckForDomains(staleDomains);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void runCheck() {
        if (running.get()) {
            log.warn("Check already in progress (runId={}) — skipping duplicate trigger", currentRunId.get());
            return;
        }
        // Ensure every domain visible on the dashboard is represented in inventory
        syncLatestChecksToInventory();

        List<Map<String, Object>> domains = loadDomainsFromInventory();
        if (domains.isEmpty()) {
            log.warn("No active domains in inventory!");
            return;
        }
        runCheckForDomains(domains);
    }

    /**
     * Adds any domain present in latest_checks (dashboard) but missing from inventory.
     * Called before every check run so no restart is needed to pick up manually-added domains.
     */
    private void syncLatestChecksToInventory() {
        String now = ISO.format(Instant.now());
        latestCheckRepo.findAll().forEach(lc -> {
            if (!inventoryRepo.existsByDomain(lc.getDomain())) {
                CertificateInventory inv = new CertificateInventory();
                inv.setDomain(lc.getDomain());
                inv.setPort(443);
                inv.setActive(true);
                inv.setCreatedAt(now);
                inv.setUpdatedAt(now);
                inventoryRepo.save(inv);
                log.info("Synced {} → inventory (was in latest_checks but missing from inventory)", lc.getDomain());
            }
        });
    }

    private void runCheckForDomains(List<Map<String, Object>> domains) {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        running.set(true);
        currentRunId.set(runId);
        log.info("Certificate check started — runId={}, {} domain(s) — {}", runId, domains.size(), LocalDateTime.now());

        List<CompletableFuture<Map<String, Object>>> futures = domains.stream()
                .map(d -> checkerService.checkAsync((String) d.get("domain"), (int) d.get("port")))
                .toList();

        List<Map<String, Object>> results = futures.stream()
                .map(CompletableFuture::join)
                .map(r -> { Map<String, Object> m = new LinkedHashMap<>(r); m.put("run_id", runId); return m; })
                .toList();

        try {
            results.forEach(certService::saveResult);

            long errors   = results.stream().filter(r -> "error".equals(r.get("status"))).count();
            long warnings = results.stream().filter(r -> Boolean.TRUE.equals(r.get("warning"))).count();
            log.info("Check complete — runId={}, Total: {}, Warning: {}, Error: {}", runId, results.size(), warnings, errors);

            lastRun.set(LocalDateTime.now());

            if (warnings > 0) {
                emailService.sendWarningEmailIfEnabled(certService.getWarnings());
            }
            escalationService.processResults(results);
        } finally {
            running.set(false);
            currentRunId.set("");
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last_run",       lastRun.get() != null ? lastRun.get().toString() : "Not yet run");
        m.put("active_domains", inventoryRepo.findByActiveTrueOrderByDomainAsc().size());
        m.put("cert_list_file", certListFile);
        m.put("schedule",       "Hourly (top of every hour) + stale sweep every 5 minutes");
        m.put("running",        running.get());
        m.put("current_run_id", currentRunId.get());
        return m;
    }

    private List<Map<String, Object>> loadDomainsFromInventory() {
        List<CertificateInventory> items = inventoryRepo.findByActiveTrueOrderByDomainAsc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (CertificateInventory item : items) {
            result.add(Map.of("domain", item.getDomain(), "port", item.getPort()));
        }
        log.info("Loaded {} active domains from inventory", result.size());
        return result;
    }

    private void importTxtFileIfInventoryEmpty() {
        if (inventoryRepo.count() > 0) return;
        log.info("Inventory empty — importing from {}", certListFile);
        String now = ISO.format(Instant.now());
        try (BufferedReader reader = new BufferedReader(new FileReader(certListFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int port = 443;
                String domain = line;
                int colonIdx = line.lastIndexOf(':');
                if (colonIdx > 0) {
                    try {
                        port = Integer.parseInt(line.substring(colonIdx + 1));
                        domain = line.substring(0, colonIdx);
                    } catch (NumberFormatException ignored) {}
                }

                if (!inventoryRepo.existsByDomain(domain)) {
                    CertificateInventory inv = new CertificateInventory();
                    inv.setDomain(domain);
                    inv.setPort(port);
                    inv.setActive(true);
                    inv.setCreatedAt(now);
                    inv.setUpdatedAt(now);
                    inventoryRepo.save(inv);
                }
            }
            log.info("Imported {} domains into inventory", inventoryRepo.count());
        } catch (Exception e) {
            log.warn("Could not import {}: {}", certListFile, e.getMessage());
        }
    }

    private void ensureDefaultThreshold() {
        if (thresholdRepo.count() == 0) {
            AlertThreshold t = new AlertThreshold();
            t.setName("default");
            t.setWarningDays(30);
            t.setHighDays(15);
            t.setCriticalDays(7);
            t.setReAlertIntervalHours(24);
            t.setActive(true);
            thresholdRepo.save(t);
            log.info("Default alert threshold created");
        }
    }
}
