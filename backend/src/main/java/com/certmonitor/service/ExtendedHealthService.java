package com.certmonitor.service;

import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.NotificationLog;
import com.certmonitor.model.SystemHeartbeat;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.NotificationLogRepository;
import com.certmonitor.repository.SystemHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtendedHealthService {

    private final NotificationLogRepository notificationLogRepo;
    private final SystemHeartbeatRepository heartbeatRepo;
    private final AlertEventRepository alertEventRepo;
    private final JdbcTemplate jdbcTemplate;
    private final RdapDomainClient rdapDomainClient;
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SchedulerService schedulerService;

    /** Domain-expiry veri kaynağı (RDAP) durumu — Sistem Sağlığı kartı için. */
    public Map<String, Object> getDomainExpirySourceStatus() {
        try {
            return rdapDomainClient.getSourceStatus();
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", "NONE");
            m.put("alarm", true);
            m.put("reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return m;
        }
    }

    public Map<String, Object> getNetworkStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (schedulerService == null) {
            m.put("alarm", false);
            return m;
        }
        m.put("alarm",               schedulerService.isNetworkOutageActive());
        m.put("detected_at",         schedulerService.getNetworkOutageDetectedAt());
        m.put("resolved_at",         schedulerService.getNetworkOutageResolvedAt());
        m.put("last_error_rate",     schedulerService.getNetworkLastErrorRate());
        m.put("last_network_errors", schedulerService.getNetworkLastNetworkErrors());
        m.put("last_total",          schedulerService.getNetworkLastTotal());
        m.put("threshold",           schedulerService.getErrorRateThreshold());
        m.put("min_errors",          schedulerService.getMinNetworkErrors());
        m.put("pending_alert_email", schedulerService.isPendingAdminAlertEmail());
        m.put("pending_resolved_email", schedulerService.isPendingAdminResolvedEmail());
        return m;
    }

    @Value("${cert.monitor.email.from:noreply@certmonitor}")
    private String emailFrom;

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    @Scheduled(fixedRate = 60_000, initialDelay = 5_000)
    public void recordHeartbeat() {
        try {
            SystemHeartbeat hb = new SystemHeartbeat();
            hb.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC));
            heartbeatRepo.save(hb);
            log.debug("Heartbeat recorded at {}", hb.getRecordedAt());
        } catch (Exception e) {
            log.warn("Failed to record heartbeat: {}", e.getMessage());
        }
    }

    public Map<String, Object> getHeartbeatStatus() {
        try {
            List<SystemHeartbeat> recent5 = heartbeatRepo.findTop5ByOrderByRecordedAtDesc();
            if (recent5.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("last_heartbeat", null);
                m.put("minutes_since", -1);
                m.put("alarm", true);
                m.put("recent", List.of());
                return m;
            }
            LocalDateTime lastTs = recent5.get(0).getRecordedAt();
            long minutes = ChronoUnit.MINUTES.between(lastTs, LocalDateTime.now(ZoneOffset.UTC));
            List<String> recentList = recent5.stream()
                    .map(hb -> hb.getRecordedAt().toString())
                    .collect(java.util.stream.Collectors.toList());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("last_heartbeat", lastTs.toString());
            m.put("minutes_since", minutes);
            m.put("alarm", minutes > 3);
            m.put("recent", recentList);
            return m;
        } catch (Exception e) {
            log.warn("getHeartbeatStatus failed: {}", e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("last_heartbeat", null);
            m.put("minutes_since", -1);
            m.put("alarm", true);
            m.put("recent", List.of());
            m.put("error", e.getMessage());
            return m;
        }
    }

    public Map<String, Object> getHeartbeatTimeline(int days) {
        int d = Math.max(1, Math.min(days, 30));
        int bucketMinutes = (d == 1) ? 10 : 60;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        LocalDateTime cutoff = now.minusDays(d);
        int alignMin = cutoff.getMinute() % bucketMinutes;
        cutoff = cutoff.minusMinutes(alignMin);

        long totalMinutes = ChronoUnit.MINUTES.between(cutoff, now);
        int totalBuckets = (int)(totalMinutes / bucketMinutes);
        if (totalBuckets <= 0) totalBuckets = 1;
        long[] counts = new long[totalBuckets];

        List<SystemHeartbeat> rows = heartbeatRepo.findByRecordedAtAfterOrderByRecordedAtAsc(cutoff);
        for (SystemHeartbeat hb : rows) {
            long minutesFromCutoff = ChronoUnit.MINUTES.between(cutoff, hb.getRecordedAt());
            int idx = (int)(minutesFromCutoff / bucketMinutes);
            if (idx >= 0 && idx < totalBuckets) counts[idx]++;
        }

        List<Map<String, Object>> buckets = new ArrayList<>(totalBuckets);
        int expected = bucketMinutes;
        for (int i = 0; i < totalBuckets; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("start",    cutoff.plusMinutes((long)i * bucketMinutes).toString());
            b.put("expected", expected);
            b.put("received", (int) counts[i]);
            buckets.add(b);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days",           d);
        out.put("bucket_minutes", bucketMinutes);
        out.put("buckets",        buckets);
        return out;
    }

    // ── SMTP stats ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getSmtpFailures() {
        return getSmtpFailures(30);
    }

    public List<Map<String, Object>> getSmtpFailures(int days) {
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<NotificationLog> logs = notificationLogRepo.findAllSince(cutoff);
        Map<Long, String> aeToDomain = aeIdToDomainMap(logs);
        return logs.stream()
                .map(n -> {
                    String status = n.getEmailStatus() != null ? n.getEmailStatus() : "";
                    String kind   = status.equals("SENT") ? "SENT"
                                  : status.startsWith("FAILED") ? "FAILED"
                                  : status.startsWith("SKIPPED") ? "SKIPPED" : "UNKNOWN";
                    String error  = (status.startsWith("FAILED") || status.startsWith("SKIPPED"))
                                  ? status.replaceFirst("^(FAILED|SKIPPED)[_:]?\\s*", "").trim() : "";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",              n.getId());
                    m.put("alert_event_id",  n.getAlertEventId());
                    m.put("domain",          aeToDomain.get(n.getAlertEventId()));
                    m.put("sent_at",         n.getSentAt());
                    m.put("sender_email",    emailFrom);
                    m.put("recipient_name",  n.getRecipientName());
                    m.put("recipient_email", n.getRecipientEmail());
                    m.put("subject",         n.getSubject());
                    m.put("message",         n.getMessage());
                    m.put("kind",            kind);
                    m.put("error",           error);
                    m.put("trigger",         n.getTrigger());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private Map<Long, String> aeIdToDomainMap(List<NotificationLog> logs) {
        java.util.Set<Long> ids = logs.stream()
                .map(NotificationLog::getAlertEventId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return java.util.Collections.emptyMap();
        return alertEventRepo.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(AlertEvent::getId, AlertEvent::getDomain));
    }

    /** Domains whose last N consecutive non-SKIPPED mail attempts (within the last
     *  {@code days} days) are all FAILED. SKIPPED_DISABLED is excluded from the
     *  "last N" window — it represents an admin choice, not a delivery failure. */
    // Global veri (kullanıcıya özel değil), her /api/notifications/failure-domains isteğinde 7 günlük
    // notification_logs taraması. 100 kullanıcı × 5 dk dashboard fan-out'unda 100× tekrar ediyordu →
    // cache'le (sync: eşzamanlı çağrılar tek hesaba iner). TTL 300 sn CacheConfig'te; anahtar (consecutive,days).
    @Cacheable(value = "failure-domains", sync = true)
    public List<String> findDomainsWithConsecutiveMailFailures(int consecutive, int days) {
        if (consecutive < 1) return List.of();
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<NotificationLog> recent = notificationLogRepo.findAllSince(cutoff).stream()
                .filter(n -> n.getEmailStatus() != null && !n.getEmailStatus().startsWith("SKIPPED"))
                .filter(n -> n.getAlertEventId() != null)
                .collect(java.util.stream.Collectors.toList());
        if (recent.isEmpty()) return List.of();

        Map<Long, String> aeToDomain = aeIdToDomainMap(recent);
        Map<String, List<NotificationLog>> byDomain = recent.stream()
                .filter(n -> aeToDomain.containsKey(n.getAlertEventId()))
                .collect(java.util.stream.Collectors.groupingBy(
                        n -> aeToDomain.get(n.getAlertEventId())));

        java.util.Comparator<NotificationLog> bySentAtDesc = (a, b) -> {
            String sa = a.getSentAt();
            String sb = b.getSentAt();
            if (sa == null && sb == null) return 0;
            if (sa == null) return 1;   // nulls last
            if (sb == null) return -1;
            return sb.compareTo(sa);    // DESC
        };

        List<String> failing = new java.util.ArrayList<>();
        for (Map.Entry<String, List<NotificationLog>> e : byDomain.entrySet()) {
            List<NotificationLog> top = e.getValue().stream()
                    .sorted(bySentAtDesc)
                    .limit(consecutive)
                    .collect(java.util.stream.Collectors.toList());
            if (top.size() < consecutive) continue;
            boolean allFailed = top.stream()
                    .allMatch(n -> n.getEmailStatus() != null && n.getEmailStatus().startsWith("FAILED"));
            if (allFailed) failing.add(e.getKey());
        }
        java.util.Collections.sort(failing);
        return failing;
    }

    public Map<String, Object> getSmtpStats() {
        try {
            int[] days = { 1, 7, 15, 30 };
            Map<String, Map<String, Object>> periods = new LinkedHashMap<>();
            for (int d : days) {
                periods.put(d + "d", computeSmtpPeriod(d));
            }
            // Top-level fields mirror 30d defaults for backward compatibility (existing clients/tests).
            Map<String, Object> m = new LinkedHashMap<>(periods.get("30d"));
            m.put("periods", periods);
            return m;
        } catch (Exception e) {
            log.warn("getSmtpStats failed: {}", e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", 0);
            m.put("attempted", 0);
            m.put("sent", 0);
            m.put("rate", 100);
            m.put("alarm", false);
            m.put("error", e.getMessage());
            return m;
        }
    }

    private Map<String, Object> computeSmtpPeriod(int days) {
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        // attempted = SENT + FAILED (excludes intentional SKIPPED_DISABLED)
        long attempted = notificationLogRepo.countAttemptedSince(cutoff);
        long sent      = notificationLogRepo.countSentSince(cutoff);
        long total     = notificationLogRepo.countAllSince(cutoff);
        double rate    = attempted == 0 ? 100.0 : (sent * 100.0 / attempted);
        long rateRounded = Math.round(rate * 10) / 10L;
        boolean alarm  = attempted > 0 && rate < 95.0;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("total",     total);
        p.put("attempted", attempted);
        p.put("sent",      sent);
        p.put("rate",      rateRounded);
        p.put("alarm",     alarm);
        return p;
    }

    // ── Database ──────────────────────────────────────────────────────────────

    public long measureDbResponseMs() {
        try {
            long t0 = System.currentTimeMillis();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            log.warn("measureDbResponseMs failed: {}", e.getMessage());
            return -1;
        }
    }

    public List<Map<String, Object>> getTableStats() {
        String sql = """
            SELECT relname                                          AS table_name,
                   n_live_tup                                      AS row_count,
                   pg_size_pretty(pg_relation_size(relid))         AS table_size,
                   pg_size_pretty(pg_total_relation_size(relid))   AS total_size,
                   pg_relation_size(relid)                         AS table_size_bytes,
                   pg_total_relation_size(relid)                   AS total_size_bytes
            FROM pg_stat_user_tables
            ORDER BY pg_total_relation_size(relid) DESC
            """;
        return jdbcTemplate.queryForList(sql);
    }
}
