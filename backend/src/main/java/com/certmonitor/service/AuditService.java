package com.certmonitor.service;

import com.certmonitor.model.AuditLog;
import com.certmonitor.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // ── Login / Logout ─────────────────────────────────────────────────────────

    public AuditLog recordLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                String ipAddress, String userAgent, String sessionId,
                                boolean success, String failureReason) {
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

        List<String> anomalies = new ArrayList<>();
        if (isOffHours()) anomalies.add("OFF_HOURS");

        if (success && actor != null) {
            if (!geoIpService.isPrivateIp(ipAddress) && !auditLogRepo.existsSuccessfulLoginFromIp(actor, ipAddress)) {
                anomalies.add("UNUSUAL_IP");
            }
            String oneHourAgo = ISO.format(Instant.now().minusSeconds(3600));
            List<AuditLog> recent = auditLogRepo.findRecentSuccessfulLogins(actor, oneHourAgo);
            if (!recent.isEmpty()) {
                AuditLog prev = recent.get(0);
                if (prev.getIpCountry() != null
                        && !ipAddress.equals(prev.getIpAddress())
                        && prev.getIpCountry().equals("Private") == false) {
                    anomalies.add("GEO_VELOCITY");
                }
            }
        }

        if (actor != null) {
            String tenMinAgo = ISO.format(Instant.now().minusSeconds(600));
            long fails = auditLogRepo.countRecentFailedLogins(actor, tenMinAgo);
            if (fails >= 5) anomalies.add("BRUTE_FORCE");
        }

        if (!anomalies.isEmpty()) {
            entry.setAnomalyFlags(String.join(",", anomalies));
            log.warn("Security anomaly detected: user={} flags={} IP={}", actor, anomalies, ipAddress);
        }

        AuditLog saved = auditLogRepo.save(entry);
        enrichGeoAsync(saved.getId(), ipAddress);
        return saved;
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

    /** Returns true if current UTC time is outside Mon–Fri 08:00–18:00. */
    private boolean isOffHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int dow = now.getDayOfWeek().getValue(); // 1=Mon 7=Sun
        int hour = now.getHour();
        return dow >= 6 || hour < 8 || hour >= 18;
    }

    public String resolveIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr();
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
