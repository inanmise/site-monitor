package com.sitemonitor.controller;

import com.sitemonitor.util.Msg;
import com.sitemonitor.model.LoginAnomalyIncident;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.LoginAnomalyIncidentRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.FailedLoginAnomalyService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Başarısız-login anomali uyarısı yönetimi — eşikler/alıcılar/cooldown okur-yazar (canlı, AppSettings),
 * "test maili gönder" ve son tetiklenen incident'ları listeler. İzin: settings.general/edit
 * (bootstrap admin bypass). Kalıcılık aynı {@code app_settings} tablosuna {@code site.monitor.failed-login.*}.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/login-anomaly")
@RequiredArgsConstructor
public class LoginAnomalyController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String P = "site.monitor.failed-login.";

    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final EmailNotificationService emailService;
    private final LoginAnomalyIncidentRepository incidentRepo;
    private final NotificationLogRepository notificationLogRepo;

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireSettingsAccess(session);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled",                          settingsService.getBoolean(P + "enabled", true));
        d.put("window_minutes",                   settingsService.getInt(P + "window-minutes", 10));
        d.put("threshold_total",                  settingsService.getInt(P + "threshold-total", 20));
        d.put("threshold_per_account",            settingsService.getInt(P + "threshold-per-account", 5));
        d.put("threshold_per_ip",                 settingsService.getInt(P + "threshold-per-ip", 15));
        d.put("threshold_distinct_users_per_ip",  settingsService.getInt(P + "threshold-distinct-users-per-ip", 5));
        d.put("threshold_distinct_ips_per_account", settingsService.getInt(P + "threshold-distinct-ips-per-account", 5));
        d.put("relative_multiplier",              settingsService.getDouble(P + "relative-multiplier", 3.0));
        d.put("baseline_hours",                   settingsService.getInt(P + "baseline-hours", 24));
        d.put("relative_floor",                   settingsService.getInt(P + "relative-floor", 8));
        d.put("catchup_cap_minutes",              settingsService.getInt(P + "catchup-cap-minutes", 60));
        d.put("cooldown_minutes",                 settingsService.getInt(P + "cooldown-minutes", 60));
        d.put("resolved_email_enabled",           settingsService.getBoolean(P + "resolved-email-enabled", true));
        d.put("retention_days",                   settingsService.getInt(P + "retention-days", 90));
        d.put("alert_recipients",                 String.join(",", settingsService.getCsv(P + "alert-recipients", "")));
        d.put("system_admin_email",               settingsService.getString("site.monitor.system-admin.email", ""));
        return ok(Map.of("data", d));
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session);

        boolean enabled = asBool(body.get("enabled"), true);
        int windowMinutes = req(asInt(body.get("window_minutes"), 10), 1, 120, "Pencere (dk)");
        int thTotal      = req(asInt(body.get("threshold_total"), 20), 1, 100000, Msg.t("Genel hacim eşiği", "Overall volume threshold"));
        int thAccount    = req(asInt(body.get("threshold_per_account"), 5), 1, 100000, Msg.t("Hesap eşiği", "Per-account threshold"));
        int thIp         = req(asInt(body.get("threshold_per_ip"), 15), 1, 100000, Msg.t("IP eşiği", "Per-IP threshold"));
        int thUsersPerIp = req(asInt(body.get("threshold_distinct_users_per_ip"), 5), 1, 100000, Msg.t("IP→kullanıcı eşiği", "Distinct users per IP threshold"));
        int thIpsPerAcc  = req(asInt(body.get("threshold_distinct_ips_per_account"), 5), 1, 100000, Msg.t("Hesap→IP eşiği", "Distinct IPs per account threshold"));
        double relMul    = asDbl(body.get("relative_multiplier"), 3.0);
        int baselineH    = req(asInt(body.get("baseline_hours"), 24), 1, 168, "Taban (saat)");
        int relFloor     = req(asInt(body.get("relative_floor"), 8), 0, 100000, Msg.t("Görece zemin", "Relative floor"));
        int catchupCap   = req(asInt(body.get("catchup_cap_minutes"), 60), 1, 1440, Msg.t("Catch-up sınırı (dk)", "Catch-up cap (min)"));
        int cooldown     = req(asInt(body.get("cooldown_minutes"), 60), 1, 10080, "Cooldown (dk)");
        boolean resolvedMail = asBool(body.get("resolved_email_enabled"), true);
        int retention    = req(asInt(body.get("retention_days"), 90), 7, 3650, Msg.t("Saklama (gün)", "Retention (days)"));
        if (relMul < 1.0 || relMul > 100.0) throw new IllegalArgumentException(Msg.t("Görece çarpan 1–100 aralığında olmalı", "Relative multiplier must be between 1 and 100"));
        String recipients = normalizeCsv(asStr(body.get("alert_recipients"), ""));

        Map<String, Object> v = new LinkedHashMap<>();
        v.put(P + "enabled", String.valueOf(enabled));
        v.put(P + "window-minutes", String.valueOf(windowMinutes));
        v.put(P + "threshold-total", String.valueOf(thTotal));
        v.put(P + "threshold-per-account", String.valueOf(thAccount));
        v.put(P + "threshold-per-ip", String.valueOf(thIp));
        v.put(P + "threshold-distinct-users-per-ip", String.valueOf(thUsersPerIp));
        v.put(P + "threshold-distinct-ips-per-account", String.valueOf(thIpsPerAcc));
        v.put(P + "relative-multiplier", String.valueOf(relMul));
        v.put(P + "baseline-hours", String.valueOf(baselineH));
        v.put(P + "relative-floor", String.valueOf(relFloor));
        v.put(P + "catchup-cap-minutes", String.valueOf(catchupCap));
        v.put(P + "cooldown-minutes", String.valueOf(cooldown));
        v.put(P + "resolved-email-enabled", String.valueOf(resolvedMail));
        v.put(P + "retention-days", String.valueOf(retention));
        v.put(P + "alert-recipients", recipients);
        settingsService.save(Map.of("values", v), actor(session));

        auditService.recordAction("LOGIN_ANOMALY_SETTINGS_SAVE", session, request, "SETTINGS", "login-anomaly",
                "{\"enabled\":" + enabled + ",\"total\":" + thTotal + ",\"cooldown\":" + cooldown + "}");
        return getSettings(session);
    }

    /** Test maili — örnek bir anomali raporuyla verilen adrese gerçek mail gönderir + MANUAL notiflog. */
    @PostMapping("/test-email")
    public ResponseEntity<Map<String, Object>> testEmail(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettingsAccess(session);
        String recipient = asStr(body.get("recipient"), "").trim();
        if (recipient.isEmpty()) throw new IllegalArgumentException(Msg.t("Alıcı e-posta adresi gerekli", "Recipient e-mail address is required"));

        String status;
        try {
            status = emailService.sendSystemAdminLoginAnomalyAlert(
                    new String[]{recipient}, sampleReport(), "TEST");
        } catch (Exception e) {
            status = "FAILED: " + e.getMessage();
        }
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientEmail(recipient);
            n.setRecipientRole("SYSTEM_ADMIN");
            n.setSubject("[Site Monitor] Login anomali TEST maili");
            n.setEmailStatus(status);
            n.setTrigger("LOGIN_ANOMALY_TEST");
            n.setEmailFrom(emailService.senderAddress());
            notificationLogRepo.save(n);
        } catch (Exception ignore) { /* log kaydı best-effort */ }

        auditService.recordAction("LOGIN_ANOMALY_TEST_EMAIL", session, request, "SETTINGS", "login-anomaly",
                "{\"recipient\":\"" + recipient + "\",\"status\":\"" + status + "\"}");
        boolean okStatus = status != null && (status.equals("SENT") || status.startsWith("QUEUED"));
        return ok(Map.of("data", Map.of("status", status, "sent", okStatus)));
    }

    /** Son tetiklenen anomali incident'ları (en yeni üstte). */
    @GetMapping("/incidents")
    public ResponseEntity<Map<String, Object>> incidents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, HttpSession session) {
        requireSettingsAccess(session);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<LoginAnomalyIncident> p = incidentRepo.findAllByOrderByOpenedAtDesc(PageRequest.of(Math.max(0, page), safeSize));
        return ok(Map.of("data", p.getContent(), "total", p.getTotalElements(), "page", p.getNumber(), "size", safeSize));
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private FailedLoginAnomalyService.AnomalyReport sampleReport() {
        String end = ISO.format(Instant.now());
        String start = ISO.format(Instant.now().minusSeconds(600));
        var hits = List.of(
                new FailedLoginAnomalyService.RuleHit("GLOBAL_VOLUME", 47, 20, "47 başarısız login"),
                new FailedLoginAnomalyService.RuleHit("IP_CREDENTIAL_STUFFING", 9, 5, "203.0.113.7 → 9 farklı kullanıcı"));
        var accounts = List.of(new FailedLoginAnomalyService.KV("admin", 12),
                new FailedLoginAnomalyService.KV("test.user", 8));
        var ips = List.of(new FailedLoginAnomalyService.KV("203.0.113.7", 30),
                new FailedLoginAnomalyService.KV("198.51.100.4", 17));
        var stuffing = List.of(new FailedLoginAnomalyService.KV("203.0.113.7", 9));
        var reasons = new LinkedHashMap<String, Long>();
        reasons.put("BAD_PASSWORD", 30L);
        reasons.put("UNKNOWN_USER", 17L);
        return new FailedLoginAnomalyService.AnomalyReport(start, end, 10, 47, 4,
                hits, accounts, ips, stuffing, List.of(), reasons);
    }

    private void requireSettingsAccess(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        // 2026-09-10: kapsamlı müdür (AD ADMIN) giriş-anomalisi ayarlarını düzenleyebilir (sır taşımaz).
        permissionService.require(session, "settings.general", "edit");
    }
    private String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }
    private static int req(int v, int min, int max, String label) {
        if (v < min || v > max) throw new IllegalArgumentException(Msg.t(label + " " + min + "–" + max + " aralığında olmalı", label + " must be between " + min + " and " + max));
        return v;
    }
    private static String normalizeCsv(String csv) {
        if (csv == null) return "";
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).distinct()
                .reduce((a, b) -> a + "," + b).orElse("");
    }
    private static boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(v.toString().trim());
    }
    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try { return Integer.parseInt(v.toString().trim()); }
        catch (Exception e) { throw new IllegalArgumentException(Msg.t("Sayısal değer bekleniyor: ", "A numeric value is expected: ") + v); }
    }
    private static double asDbl(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return def;
        try { return Double.parseDouble(v.toString().trim()); }
        catch (Exception e) { throw new IllegalArgumentException(Msg.t("Ondalık değer bekleniyor: ", "A decimal value is expected: ") + v); }
    }
    private static String asStr(Object v, String def) {
        return v != null ? v.toString().trim() : def;
    }
    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
