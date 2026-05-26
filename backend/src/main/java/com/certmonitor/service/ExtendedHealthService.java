package com.certmonitor.service;

import com.certmonitor.model.SystemHeartbeat;
import com.certmonitor.repository.NotificationLogRepository;
import com.certmonitor.repository.SystemHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
    private final JdbcTemplate jdbcTemplate;

    @Value("${cert.monitor.email.from:noreply@certmonitor}")
    private String emailFrom;

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
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

    // ── SMTP stats ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getSmtpFailures() {
        return getSmtpFailures(30);
    }

    public List<Map<String, Object>> getSmtpFailures(int days) {
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return notificationLogRepo.findAllSince(cutoff).stream()
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
        boolean alarm  = attempted > 0 && rate < 99.0;
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
