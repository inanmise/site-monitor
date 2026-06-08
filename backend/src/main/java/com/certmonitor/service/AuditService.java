package com.certmonitor.service;

import com.certmonitor.model.AuditLog;
import com.certmonitor.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepo;
    private final GeoIpService geoIpService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Value("${cert.monitor.audit.brute-force-window-seconds:600}")
    private int bruteForceWindowSeconds;

    @Value("${cert.monitor.audit.geo-velocity-window-seconds:3600}")
    private int geoVelocityWindowSeconds;

    @Value("${cert.monitor.audit.office-start-hour:8}")
    private int officeStartHour;

    @Value("${cert.monitor.audit.office-end-hour:23}")
    private int officeEndHour;

    // ── Login / Logout ─────────────────────────────────────────────────────────

    /**
     * @param countSince     ISO timestamp; when non-null, failure counting starts from
     *                       max(tenMinAgo, countSince) so prior-lockout failures don't carry over.
     * @param failuresNeeded how many failures in the window trigger BRUTE_FORCE (default 5).
     */
    public AuditLog recordLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                String ipAddress, String userAgent, String sessionId,
                                boolean success, String failureReason, String countSince,
                                int failuresNeeded) {
        AuditLog entry = new AuditLog();
        entry.setEventType(success ? "LOGIN" : "LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSessionId(sessionId);
        entry.setOutcome(success ? "SUCCESS" : "FAILURE");
        entry.setFailureReason(failureReason);
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }

        List<String> anomalies = new ArrayList<>();
        if (isOffHours()) anomalies.add("OFF_HOURS");

        if (success && actor != null) {
            if (!geoIpService.isPrivateIp(ipAddress) && !auditLogRepo.existsSuccessfulLoginFromIp(actor, ipAddress))
                anomalies.add("UNUSUAL_IP");

            String oneHourAgo = ISO.format(Instant.now().minusSeconds(geoVelocityWindowSeconds));
            List<AuditLog> recent = auditLogRepo.findRecentSuccessfulLogins(actor, oneHourAgo);
            if (!recent.isEmpty()) {
                AuditLog prev = recent.get(0);
                if (prev.getIpCountry() != null && !ipAddress.equals(prev.getIpAddress())
                        && !"Private".equals(prev.getIpCountry()))
                    anomalies.add("GEO_VELOCITY");
            }

            // Successful login after a brute-force pattern — flag it
            String tenMinAgo = ISO.format(Instant.now().minusSeconds(bruteForceWindowSeconds));
            long prevFails = auditLogRepo.countRecentFailedLogins(actor, tenMinAgo);
            if (prevFails >= 5) anomalies.add("BRUTE_FORCE");
        }

        if (!success && actor != null && !actor.isBlank()) {
            String tenMinAgo = ISO.format(Instant.now().minusSeconds(bruteForceWindowSeconds));
            String since = (countSince != null && countSince.compareTo(tenMinAgo) > 0) ? countSince : tenMinAgo;
            long prevFails = auditLogRepo.countRecentFailedLogins(actor, since);
            int attemptNum = (int) prevFails + 1;
            if (prevFails >= failuresNeeded - 1) {
                anomalies.add("BRUTE_FORCE");
                entry.setFailureReason(
                    "Brute force: attempt #" + attemptNum + "/" + failuresNeeded + " for '" + actor + "'");
                log.warn("Brute force detected: user='{}' attempt={}/{} IP={}", actor, attemptNum, failuresNeeded, ipAddress);
            } else {
                entry.setFailureReason(
                    "Invalid credentials — attempt #" + attemptNum + "/" + failuresNeeded + " for '" + actor + "'");
            }
        } else {
            entry.setFailureReason(failureReason);
        }

        if (!anomalies.isEmpty()) {
            entry.setAnomalyFlags(String.join(",", anomalies));
            if (success) log.warn("Security anomaly on login: user={} flags={} IP={}", actor, anomalies, ipAddress);
        }

        AuditLog saved = auditLogRepo.save(entry);
        enrichGeoAsync(saved.getId(), ipAddress);
        return saved;
    }

    /** Logs a BLOCKED login when the IP is rate-limited before authentication even runs. */
    public void recordRateLimited(String actor, String ipAddress, String userAgent) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setOutcome("BLOCKED");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        entry.setFailureReason("Rate limited: too many login attempts from " + ipAddress
                + (actor != null && !actor.isBlank() ? " (targeting '" + actor + "')" : ""));
        entry.setAnomalyFlags("RATE_LIMITED");
        AuditLog saved = auditLogRepo.save(entry);
        enrichGeoAsync(saved.getId(), ipAddress);
        log.warn("Rate-limited login blocked: IP={} actor={}", ipAddress, actor);
    }

    public void recordLogout(String actor, Long actorId, String ipAddress, String sessionId) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGOUT");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setIpAddress(ipAddress);
        entry.setSessionId(sessionId);
        entry.setOutcome("SUCCESS");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        auditLogRepo.save(entry);
    }

    // ── Admin actions ──────────────────────────────────────────────────────────

    public void recordAction(String eventType, String actor, Long actorId, Long actorTeamId,
                             String actorRole, String resourceType, String resourceId,
                             String detail, String ipAddress, String userAgent, String sessionId) {
        AuditLog entry = new AuditLog();
        entry.setEventType(eventType);
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSessionId(sessionId);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setDetail(detail);
        entry.setOutcome("SUCCESS");
        if (isOffHours()) entry.setAnomalyFlags("OFF_HOURS");
        auditLogRepo.save(entry);
    }

    /** Convenience overload that reads context from HTTP objects. */
    public void recordAction(String eventType, HttpSession session, HttpServletRequest request,
                             String resourceType, String resourceId, String detail) {
        recordAction(eventType,
                strAttr(session, "username"),
                longAttr(session, "userId"),
                longAttr(session, "teamId"),
                strAttr(session, "systemRole"),
                resourceType, resourceId, detail,
                resolveIp(request), resolveUa(request), session.getId());
    }

    // ── Geo enrichment ─────────────────────────────────────────────────────────

    @Async("certCheckExecutor")
    public void enrichGeoAsync(Long auditLogId, String ip) {
        try {
            GeoIpService.GeoInfo geo = geoIpService.lookup(ip);
            auditLogRepo.findById(auditLogId).ifPresent(entry -> {
                entry.setIpCountry(geo.country());
                entry.setIpCity(geo.city());
                entry.setIpOrg(geo.org());
                auditLogRepo.save(entry);
            });
        } catch (Exception e) {
            log.debug("Geo enrichment failed for id={}: {}", auditLogId, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Returns true if current UTC time is outside Mon–Fri officeStartHour–officeEndHour. */
    private boolean isOffHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int dow = now.getDayOfWeek().getValue(); // 1=Mon 7=Sun
        int hour = now.getHour();
        return dow >= 6 || hour < officeStartHour || hour >= officeEndHour;
    }

    public String resolveIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String fwd = request.getHeader("X-Forwarded-For");
        String ip = (fwd != null && !fwd.isBlank()) ? fwd.split(",")[0].trim() : request.getRemoteAddr();
        return normalizeIp(ip);
    }

    public static String normalizeIp(String ip) {
        if (ip == null) return "unknown";
        // IPv6 loopback → canonical IPv4 loopback
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "0000:0000:0000:0000:0000:0000:0000:0001".equals(ip))
            return "127.0.0.1";
        // IPv4-mapped IPv6 ::ffff:x.x.x.x
        if (ip.startsWith("::ffff:") && ip.length() > 7) return ip.substring(7);
        return ip;
    }

    public String resolveUa(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private String strAttr(HttpSession s, String key) {
        Object v = s.getAttribute(key);
        return v != null ? v.toString() : null;
    }

    private Long longAttr(HttpSession s, String key) {
        Object v = s.getAttribute(key);
        if (v == null) return null;
        if (v instanceof Long l) return l;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
