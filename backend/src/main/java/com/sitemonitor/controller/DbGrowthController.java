package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.dbgrowth.DbGrowthService;
import com.sitemonitor.service.dbgrowth.DbHealthAnalysisService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sistem Sağlığı → Veritabanı büyümesi (2026-11-13).
 *
 * <ul>
 *   <li>{@code GET /api/admin/system/db-growth?range=30|90|365&fresh=} — görünüm ({@code system_health.read/view}).</li>
 *   <li>{@code GET …/what-if?policy=&days=} — kısaltmanın ilk gece sileceği satır tahmini (GLOBAL yönetici; zaman aşımlı
 *       COUNT → kişi başına dakikada {@value #WHAT_IF_PER_MINUTE}).</li>
 *   <li>{@code PUT …/capacity} — kapasite (GB) — GLOBAL yönetici; {@code AppSettingsService.save} üzerinden (GLOBAL_ONLY
 *       kapısı + aynı doğrulama), denetim {@code DB_CAPACITY_SETTINGS_SAVE}.</li>
 *   <li>{@code POST …/analysis} — salt okunur sağlık analizi ({@code system_health.actions/execute}); kişi başına dakikada
 *       {@value #ANALYSIS_PER_MINUTE} (429), pod'da süren analiz varken 409 {@code DB_ANALYSIS_BUSY}; denetim
 *       {@code DB_HEALTH_ANALYSIS_RUN}.</li>
 *   <li>{@code GET …/analysis/latest} — en yeni analiz ({@code system_health.read/view}).</li>
 * </ul>
 * Saklama kısaltmasının kendisi bu denetleyicide YOK: "Uygula" Ayarlar → Veri Saklama'nın kayıt ucunu
 * ({@code PUT /api/admin/retention/settings}) çağırır — aynı doğrulama, taban ve denetim.
 */
@RestController
@RequestMapping("/api/admin/system/db-growth")
@RequiredArgsConstructor
public class DbGrowthController {

    static final int ANALYSIS_PER_MINUTE = 3;
    static final int WHAT_IF_PER_MINUTE = 6;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DbGrowthService growth;
    private final DbHealthAnalysisService analysis;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final AppSettingsService settingsService;

    private final Map<String, Deque<Long>> analysisCalls = new HashMap<>();
    private final Map<String, Deque<Long>> whatIfCalls = new HashMap<>();

    @GetMapping
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(defaultValue = "90") int range,
                                                        @RequestParam(defaultValue = "false") boolean fresh, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        Map<String, Object> data = new LinkedHashMap<>(growth.overview(range, fresh));
        boolean global = SessionScope.isGlobalAdmin(session);
        data.put("can_edit_capacity", global);
        data.put("can_apply", global);
        data.put("can_open_retention", canOpenRetention(session));
        data.put("can_analyze", permissionService.allows(session, "system_health.actions", "execute"));
        data.put("capacity_key", DbGrowthService.CAPACITY_KEY);
        data.put("max_capacity_gb", DbGrowthService.MAX_CAPACITY_GB);
        data.put("last_analysis", analysis.latestSummary());
        data.put("analysis_running", analysis.busy());
        return ok(Map.of("data", data));
    }

    @GetMapping("/what-if")
    public ResponseEntity<Map<String, Object>> whatIf(@RequestParam String policy, @RequestParam int days, HttpSession session) {
        requireGlobalAdmin(session);
        if (!allow(whatIfCalls, actor(session), WHAT_IF_PER_MINUTE, System.currentTimeMillis())) {
            return rateLimited(Msg.t("Dakikada en çok " + WHAT_IF_PER_MINUTE + " tahmin istenebilir; biraz bekleyip yeniden deneyin.",
                    "At most " + WHAT_IF_PER_MINUTE + " estimates per minute; wait a moment and try again."));
        }
        return ok(Map.of("data", growth.whatIf(policy, days)));
    }

    @PutMapping("/capacity")
    public ResponseEntity<Map<String, Object>> saveCapacity(@RequestBody Map<String, Object> body, HttpSession session,
                                                            HttpServletRequest request) {
        requireGlobalAdmin(session);
        Object raw = body == null ? null : body.get("capacity_gb");
        int v;
        if (raw == null || String.valueOf(raw).isBlank()) {
            v = 0;
        } else {
            try {
                v = raw instanceof Number n ? (int) Math.round(n.doubleValue()) : Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new FieldValidationException("capacity_gb", Msg.t("Kapasite tam sayı (GB) olmalı.",
                        "Capacity must be a whole number of GB."));
            }
        }
        if (v < 0 || v > DbGrowthService.MAX_CAPACITY_GB) {
            throw new FieldValidationException("capacity_gb", Msg.t(
                    "Kapasite 0 ile " + DbGrowthService.MAX_CAPACITY_GB + " GB arasında olmalı (0 = bilinmiyor).",
                    "Capacity must be between 0 and " + DbGrowthService.MAX_CAPACITY_GB + " GB (0 = unknown)."));
        }
        int before = growth.capacityGb();
        if (before != v) {
            settingsService.save(Map.of("values", Map.of(DbGrowthService.CAPACITY_KEY, String.valueOf(v))), actor(session));
            growth.invalidate();
            auditService.recordAction("DB_CAPACITY_SETTINGS_SAVE", session, request, "SETTINGS", DbGrowthService.CAPACITY_KEY,
                    "{\"capacity_gb\":" + v + "}", AuditDiff.diff(Map.of("capacity_gb", before), Map.of("capacity_gb", v)));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capacity_gb", v > 0 ? v : null);
        data.put("changed", before != v);
        return ok(Map.of("data", data, "message", Msg.t("Kapasite kaydedildi", "Capacity saved")));
    }

    @PostMapping("/analysis")
    public ResponseEntity<Map<String, Object>> runAnalysis(HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "system_health.actions", "execute");
        if (!allow(analysisCalls, actor(session), ANALYSIS_PER_MINUTE, System.currentTimeMillis())) {
            return rateLimited(Msg.t("Dakikada en çok " + ANALYSIS_PER_MINUTE + " analiz çalıştırılabilir; biraz bekleyip yeniden deneyin.",
                    "At most " + ANALYSIS_PER_MINUTE + " analyses per minute; wait a moment and try again."));
        }
        Optional<Map<String, Object>> run = analysis.run(actor(session));
        if (run.isEmpty()) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException("DB_ANALYSIS_BUSY", Msg.t(
                    "Bu pod'da şu an başka bir veritabanı analizi sürüyor; birkaç saniye sonra yeniden deneyin. Sonuç bitince "
                            + "\"Son analiz\" olarak görünür.",
                    "Another database analysis is running on this pod right now; try again in a few seconds. Its result "
                            + "appears as the latest analysis when it finishes."));
        }
        Map<String, Object> r = run.get();
        Object findings = r.get("findings");
        auditService.recordAction("DB_HEALTH_ANALYSIS_RUN", session, request, "SYSTEM", "db-growth",
                "{\"status\":\"" + r.get("status") + "\",\"duration_ms\":" + r.get("duration_ms")
                        + ",\"findings\":" + (findings instanceof java.util.List<?> l ? l.size() : 0) + "}");
        return ok(Map.of("data", r));
    }

    @GetMapping("/analysis/latest")
    public ResponseEntity<Map<String, Object>> latestAnalysis(HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analysis", analysis.latest());
        data.put("running", analysis.busy());
        return ok(Map.of("data", data));
    }

    /** Ayarlar → Veri Saklama'yı görebiliyor mu ({@code RetentionAdminController.requireAccess} ile aynı kural). */
    private boolean canOpenRetention(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return true;
        return permissionService.allows(session, "settings.retention", "edit");
    }

    private static void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Bu işlemi yalnız global yönetici yapabilir.",
                    "Only a global administrator can do this."));
        }
    }

    synchronized boolean allow(Map<String, Deque<Long>> calls, String who, int perMinute, long nowMs) {
        Deque<Long> d = calls.computeIfAbsent(who, k -> new ArrayDeque<>());
        while (!d.isEmpty() && nowMs - d.peekFirst() > 60_000) d.pollFirst();
        if (d.size() >= perMinute) return false;
        d.addLast(nowMs);
        if (calls.size() > 1000) calls.entrySet().removeIf(e -> e.getValue().isEmpty());
        return true;
    }

    private static ResponseEntity<Map<String, Object>> rateLimited(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("code", "RATE_LIMITED");
        err.put("error", message);
        err.put("retry_after_seconds", 60);
        return ResponseEntity.status(429).header("Retry-After", "60").body(err);
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(r);
    }
}
