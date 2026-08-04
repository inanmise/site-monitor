package com.certmonitor.service;

import com.certmonitor.model.ActivityLog;
import com.certmonitor.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Birleşik aktivite akışına (activity_log) kayıt üreten TEK yazım noktası. Her izleme türünün
 * mevcut persist noktasından ve monitör CRUD'undan çağrılır.
 *
 * <b>Best-effort:</b> tüm gövde try/catch ile sarılır — aktivite yazımı ana kontrol akışını
 * ASLA yavaşlatmaz/kırmaz; hata yutulur (debug log) ve kontrol devam eder. teamId çağıran
 * bağlamda zaten yüklü olan monitör/envanterden geçirilir (per-check EK sorgu yapılmaz).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityLogService {

    public static final String CERT = "CERT", UPTIME = "UPTIME", HTTP = "HTTP", PORT = "PORT",
            PING = "PING", DNS = "DNS", KEYWORD = "KEYWORD", DOMAIN = "DOMAIN", PAGE = "PAGE", SCRIPTED = "SCRIPTED";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ActivityLogRepository repo;

    /** Bir kontrol sonucunu (checker Map'i) aktiviteye çevirip kaydeder. manual=true → MANUAL_CHECK. */
    public void recordCheck(String type, Long monitorId, String monitorName, String target,
                            Long teamId, boolean manual, String actor, Map<String, Object> result) {
        try {
            ActivityLog a = base(type, monitorId, monitorName, target, teamId,
                    manual ? "MANUAL_CHECK" : "SCHEDULED_CHECK", actor);
            summarize(type, result, a);
            repo.save(a);
        } catch (Exception e) {
            log.debug("activity-log kayıt atlandı (type={} target={}): {}", type, target, e.toString());
        }
    }

    /** İzleme yaşam döngüsü olayı (CREATED/UPDATED/DELETED/PAUSED/RESUMED). */
    public void recordLifecycle(String type, Long monitorId, String monitorName, String target,
                                Long teamId, String action, String actor) {
        try {
            ActivityLog a = base(type, monitorId, monitorName, target, teamId, action, actor);
            a.setResultStatus("SUCCESS");
            repo.save(a);
        } catch (Exception e) {
            log.debug("activity-log lifecycle atlandı (type={} action={}): {}", type, action, e.toString());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ActivityLog base(String type, Long monitorId, String monitorName, String target,
                                    Long teamId, String action, String actor) {
        ActivityLog a = new ActivityLog();
        a.setActivityTime(ISO.format(Instant.now()));
        a.setMonitorType(type);
        a.setMonitorId(monitorId);
        a.setMonitorName(monitorName != null ? trim(monitorName, 300) : (target != null ? trim(target, 300) : type));
        a.setTarget(trim(target, 500));
        a.setAction(action);
        a.setActor(actor != null ? trim(actor, 100) : "scheduler");
        a.setTeamId(teamId);
        return a;
    }

    /** Checker Map'ini result_status + result_summary + tipli alanlara eşler. Türe özgü. */
    private static void summarize(String type, Map<String, Object> r, ActivityLog a) {
        if (r == null) { a.setResultStatus("UNKNOWN"); return; }
        String error = str(r.get("error"));
        String errorClass = str(r.get("error_class"));
        a.setErrorMessage(trim(error, 1000));
        a.setErrorClass(trim(errorClass, 40));
        Long ms = asLong(firstNonNull(r.get("response_ms"), r.get("rtt_ms")));
        a.setResponseMs(ms);

        switch (type) {
            case CERT, DOMAIN -> {
                Integer days = asInt(r.get("days_remaining"));
                a.setDaysRemaining(days);
                String status = str(r.get("status"));
                a.setResultStatus(mapCertDomainStatus(type, status, error));
                a.setResultSummary(days != null ? days + "d" : (error != null ? "error" : safe(status)));
            }
            case HTTP, KEYWORD -> {
                Integer code = asInt(r.get("http_status"));
                boolean ok = truthy(r.get("ok")) || (type.equals(KEYWORD) && truthy(r.get("found")) && error == null);
                a.setResultStatus(error != null ? errStatus(errorClass) : (ok ? "SUCCESS" : (code != null ? "WARNING" : "ERROR")));
                a.setResultSummary((code != null ? code : "-") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PORT -> {
                boolean open = truthy(r.get("open"));
                a.setResultStatus(error != null ? errStatus(errorClass) : (open ? "SUCCESS" : "ERROR"));
                a.setResultSummary((open ? "open" : "closed") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PING -> {
                boolean up = truthy(r.get("up"));
                boolean na = truthy(r.get("na"));
                a.setResultStatus(na ? "UNKNOWN" : (up ? "SUCCESS" : (error != null ? errStatus(errorClass) : "ERROR")));
                Object loss = r.get("packet_loss");
                a.setResultSummary((ms != null ? ms + "ms" : (up ? "up" : "down")) + (loss != null ? " · %" + loss : ""));
            }
            case UPTIME -> {
                String status = str(r.get("status"));
                boolean up = "up".equalsIgnoreCase(status);
                a.setResultStatus(up ? "SUCCESS" : (error != null ? errStatus(errorClass) : "ERROR"));
                a.setResultSummary((up ? "up" : "down") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case DNS -> {
                boolean success = truthy(r.get("success"));
                a.setResultStatus(success ? "SUCCESS" : (error != null ? errStatus(errorClass) : "ERROR"));
                a.setResultSummary((success ? "ok" : "fail") + (ms != null ? " · " + ms + "ms" : ""));
            }
            case PAGE -> {
                String status = str(r.get("status"));   // OK | DEGRADED | DOWN | CONFIG_ERROR
                Integer broken = asInt(r.get("broken_resources"));
                Integer timeouts = asInt(r.get("timeout_count"));   // 2026-08-04: kırıktan ayrı sayaç
                Integer mixed = asInt(r.get("mixed_content_count"));
                // CONFIG_ERROR (şemasız/host'suz URL) da hata sayılır — aksi halde "diğer" dalına düşüp
                // sessizce SUCCESS görünürdü; sözlük sabit (SUCCESS|WARNING|ERROR|TIMEOUT|UNKNOWN).
                a.setResultStatus("DOWN".equalsIgnoreCase(status) ? (error != null ? errStatus(errorClass) : "ERROR")
                        : ("CONFIG_ERROR".equalsIgnoreCase(status) ? "ERROR"
                        : ("DEGRADED".equalsIgnoreCase(status) ? "WARNING" : "SUCCESS")));
                String detail = "CONFIG_ERROR".equalsIgnoreCase(status) ? "yapılandırma hatası"
                        : ("DOWN".equalsIgnoreCase(status))
                        ? "yüklenemedi"
                        : ((broken != null ? broken : 0) + " kırık"
                           + (timeouts != null && timeouts > 0 ? " · " + timeouts + " zaman aşımı" : "")
                           + (mixed != null && mixed > 0 ? " · " + mixed + " mixed" : ""));
                a.setResultSummary(detail + (ms != null ? " · " + ms + "ms" : ""));
            }
            case SCRIPTED -> {
                String status = str(r.get("status"));   // PASS | FAIL | ERROR | TIMEOUT
                a.setResultStatus(switch (status == null ? "" : status.toUpperCase()) {
                    case "PASS" -> "SUCCESS";
                    case "FAIL" -> "WARNING";
                    case "TIMEOUT" -> "TIMEOUT";
                    default -> "ERROR";
                });
                Integer cp = asInt(r.get("checks_passed"));
                Integer cf = asInt(r.get("checks_failed"));
                String detail = safe(status) + (cp != null || cf != null ? " · " + (cp != null ? cp : 0) + "✓/" + (cf != null ? cf : 0) + "✗" : "");
                a.setResultSummary(detail + (ms != null ? " · " + ms + "ms" : ""));
            }
            default -> a.setResultStatus(error != null ? "ERROR" : "SUCCESS");
        }
    }

    private static String mapCertDomainStatus(String type, String status, String error) {
        if (status == null) return error != null ? "ERROR" : "UNKNOWN";
        String s = status.toLowerCase();
        return switch (s) {
            case "valid", "ok" -> "SUCCESS";
            case "warning" -> "WARNING";
            case "critical" -> "ERROR";
            case "error" -> "ERROR";
            case "unknown" -> "UNKNOWN";
            default -> error != null ? "ERROR" : "SUCCESS";
        };
    }

    /** Ağ/timeout hatalarını TIMEOUT'a, diğerlerini ERROR'a eşler. */
    private static String errStatus(String errorClass) {
        return errorClass != null && errorClass.toUpperCase().contains("TIMEOUT") ? "TIMEOUT" : "ERROR";
    }

    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }
    private static boolean truthy(Object o) { return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)); }
    private static String safe(String s) { return s != null ? s : ""; }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o != null ? Integer.valueOf(String.valueOf(o)) : null; } catch (Exception e) { return null; }
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try { return o != null ? Long.valueOf(String.valueOf(o)) : null; } catch (Exception e) { return null; }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
