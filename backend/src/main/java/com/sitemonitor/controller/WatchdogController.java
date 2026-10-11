package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.watchdog.WatchdogNotifier;
import com.sitemonitor.service.watchdog.WatchdogService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kendi kendini izleme uçları (2026-10-11).
 * <ul>
 *   <li>{@code GET /api/public/health/watchdog} — dış izleyici (oturumsuz, {@code AuthInterceptor} PUBLIC): UP / DEGRADED →
 *       200, DOWN (kritik sorun ya da değerlendirme durmuş) → 503, DISABLED / UNKNOWN → 200. Gövdede yalnız durum ve
 *       kontrol anahtarları; {@code no-store}.</li>
 *   <li>{@code GET /api/admin/system/watchdog} — Sistem Sağlığı kartı ({@code system_health.read/view}).</li>
 *   <li>{@code POST /api/admin/system/watchdog/evaluate} — şimdi değerlendir ({@code system_health.actions/execute});
 *       geçiş olursa normal bildirim gider. Denetim {@code WATCHDOG_EVALUATE}.</li>
 *   <li>{@code POST /api/admin/system/watchdog/test} — test bildirimi, yalnız global yönetici, dakikada en çok 3.
 *       Denetim {@code WATCHDOG_TEST} (yalnız kanal sonuçları).</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class WatchdogController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int TEST_PER_MINUTE = 3;

    private final WatchdogService watchdog;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Deque<Long> testCalls = new ArrayDeque<>();

    @GetMapping("/api/public/health/watchdog")
    public ResponseEntity<Map<String, Object>> publicStatus() {
        Map<String, Object> body = watchdog.publicStatus(Instant.now());
        int code = "DOWN".equals(body.get("status")) ? 503 : 200;
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/api/admin/system/watchdog")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of("data", watchdog.adminStatus(Instant.now())));
    }

    @PostMapping("/api/admin/system/watchdog/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "system_health.actions", "execute");
        WatchdogService.Round r = watchdog.evaluate(Instant.now());
        auditService.recordAction("WATCHDOG_EVALUATE", session, request, "SYSTEM", "watchdog",
                "{\"alerts\":" + r.alerts() + ",\"reminders\":" + r.reminders() + ",\"recoveries\":" + r.recoveries() + "}");
        Map<String, Object> data = new LinkedHashMap<>(watchdog.adminStatus(Instant.now()));
        data.put("round", Map.of("alerts", r.alerts(), "reminders", r.reminders(), "recoveries", r.recoveries(), "notification", r.outcome()));
        return ok(Map.of("data", data));
    }

    @PostMapping("/api/admin/system/watchdog/test")
    public ResponseEntity<Map<String, Object>> test(HttpSession session, HttpServletRequest request) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Test bildirimini yalnız global yönetici gönderebilir.",
                    "Only a global administrator can send the test notification."));
        }
        if (!allowTest(System.currentTimeMillis())) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "RATE_LIMITED");
            err.put("error", Msg.t("Dakikada en çok " + TEST_PER_MINUTE + " test bildirimi gönderilebilir; biraz bekleyip yeniden deneyin.",
                    "At most " + TEST_PER_MINUTE + " test notifications per minute; wait a moment and try again."));
            return ResponseEntity.status(429).body(err);
        }
        WatchdogNotifier.Report rep = watchdog.sendTest();
        auditService.recordAction("WATCHDOG_TEST", session, request, "SYSTEM", "watchdog",
                "{\"outcome\":\"" + rep.outcome() + "\",\"recipients\":" + rep.recipients() + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", rep.outcome().name());
        data.put("email", rep.email());
        data.put("webhook", rep.webhook());
        data.put("push", rep.push());
        data.put("recipients", rep.recipients());
        return ok(Map.of("data", data));
    }

    synchronized boolean allowTest(long nowMs) {
        while (!testCalls.isEmpty() && nowMs - testCalls.peekFirst() > 60_000) testCalls.pollFirst();
        if (testCalls.size() >= TEST_PER_MINUTE) return false;
        testCalls.addLast(nowMs);
        return true;
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(r);
    }
}
