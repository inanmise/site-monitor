package com.sitemonitor.controller;

import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsHistory;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsService;
import com.sitemonitor.util.MonitorUrls;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * HTTP uçtan uca tanılama uçları (2026-10-02, kullanıcı onaylı):
 * <ul>
 *   <li>{@code POST /api/monitoring/http/{id}/diagnose} — gövde {@code {"compare": true}} (varsayılan true)</li>
 *   <li>{@code GET  /api/monitoring/http/{id}/diagnose/history} — bu izlemenin son 20 çalıştırmasının özeti</li>
 *   <li>{@code GET  /api/monitoring/http/{id}/diagnose/history/{runId}} — saklanan tam sonuç (gövde önizlemesiz)</li>
 * </ul>
 *
 * <p><b>Yetki</b> (ürün kararı): {@code diagnostics.run/execute} + izlemenin takımını İŞLETEBİLMEK
 * ({@link SessionScope#canOperateTeam} — "Şimdi Kontrol Et" ile, yani liste satırındaki {@code can_check} ile AYNI kural;
 * global yönetici her izlemede). Liste satırındaki {@code can_diagnose} ({@code MonitoringController.listHttp}) bu
 * kuralın aynısıdır. Geçmiş uçları da aynı kapıdan geçer.
 *
 * <p><b>Hız sınırı:</b> kullanıcı başına dakikada {@value #MAX_PER_MINUTE} VE izleme başına dakikada
 * {@value #MAX_PER_MINUTE} (kayan pencere, {@code AdminController.checkDomainDiagRate} deseni + budama); ayrıca aynı anda
 * en çok {@value #MAX_CONCURRENT} tanılama. Aşımda 429 (istek dilinde ileti). SSRF ile engelli hedef 200 döner
 * (bulgu {@code SSRF_BLOCKED}); 400 yalnız izlemenin URL'si bozuksa.
 *
 * <p>Tanılama izlemeye DOKUNMAZ: kontrol kaydı yazmaz, alarm değerlendirmesine girmez (bkz. {@link HttpDiagnosticsService}).
 * Denetim: {@code HTTP_DIAGNOSTICS_RUN}.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class HttpDiagnosticsController {

    static final int MAX_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_CONCURRENT = 4;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HttpMonitorRepository httpMonitorRepo;
    private final PermissionService permissionService;
    private final HttpDiagnosticsService diagnosticsService;
    private final HttpDiagnosticsHistory diagnosticsHistory;
    private final AuditService auditService;

    @Autowired(required = false)
    private ClientIpResolver clientIpResolver;

    /** Kayan pencere damgaları — anahtar {@code u:<kullanıcı>} / {@code m:<izleme>}. */
    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT);

    @PostMapping("/http/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(@PathVariable Long id,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        HttpMonitor m = httpMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound();
        requireOperate(session, m);
        if (!MonitorUrls.isCheckable(m.getUrl())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", Msg.t(
                    "İzlemenin URL'si geçersiz (şema ya da host yok); tanılama çalıştırılamaz.",
                    "The monitor's URL is invalid (no scheme or host); the diagnostic cannot run.")));
        }
        // Önce eşzamanlılık yuvası, sonra pencere: meşgul reddi kullanıcının/izlemenin dakikalık hakkını TÜKETMEZ.
        if (!inFlight.tryAcquire()) {
            return tooMany(Msg.t("Şu anda çok sayıda tanılama çalışıyor; birkaç saniye sonra yeniden deneyin.",
                    "Too many diagnostics are running right now; try again in a few seconds."));
        }
        boolean compare = body == null || !Boolean.FALSE.equals(body.get("compare"));
        Map<String, Object> data;
        try {
            if (!tryAcquireRate(userKey(session), "m:" + id)) {
                return tooMany(Msg.t("Çok fazla tanılama isteği — kullanıcı ve izleme başına dakikada en fazla " + MAX_PER_MINUTE
                                + ". Lütfen biraz bekleyin.",
                        "Too many diagnostic requests — at most " + MAX_PER_MINUTE
                                + " per minute per user and per monitor. Please wait a moment."));
            }
            data = diagnosticsService.diagnose(m, compare);
        } finally {
            inFlight.release();
        }
        Long runId = diagnosticsHistory.save(m, data, actor(session), longAttr(session, "userId"),
                longAttr(session, "teamId"), clientIp(request));
        data.put("run_id", runId);
        Map<?, ?> verdict = data.get("verdict") instanceof Map<?, ?> v ? v : Map.of();
        auditService.recordAction("HTTP_DIAGNOSTICS_RUN", session, "HTTP_MONITOR", String.valueOf(m.getId()),
                AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()),
                        "verdict", verdict.get("code"), "status", verdict.get("status"),
                        "compare", compare, "run_id", runId), null);
        return ok(data);
    }

    @GetMapping("/http/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> history(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        HttpMonitor m = httpMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound();
        requireOperate(session, m);
        List<Map<String, Object>> rows = diagnosticsHistory.list(id);
        return ok(rows);
    }

    @GetMapping("/http/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> historyRun(@PathVariable Long id, @PathVariable Long runId, HttpSession session) {
        requireDiagnosePermission(session);
        HttpMonitor m = httpMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound();
        requireOperate(session, m);
        Map<String, Object> run = diagnosticsHistory.get(id, runId);
        if (run == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error",
                    Msg.t("Tanılama kaydı bulunamadı.", "Diagnostic run not found.")));
        }
        return ok(run);
    }

    // ── Kapılar ─────────────────────────────────────────────────────────────────────────────────

    private void requireDiagnosePermission(HttpSession session) {
        if (!permissionService.allows(session, "diagnostics.run", "execute")) {
            throw new SecurityException(Msg.t("Tanılama çalıştırma izniniz yok (diagnostics.run).",
                    "You are not allowed to run diagnostics (diagnostics.run)."));
        }
    }

    private static void requireOperate(HttpSession session, HttpMonitor m) {
        if (!SessionScope.canOperateTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinde tanılama çalıştıramazsınız.",
                    "You cannot run diagnostics on this team's monitor."));
        }
    }

    /**
     * İki pencere birlikte: ikisinden biri doluysa hiçbiri tüketilmez. Eşzamanlılık düşük (dakikada birkaç istek) —
     * tek kilit yeterli; uzun çalışmada boşalan anahtarlar budanır (AdminController deseni).
     */
    boolean tryAcquireRate(String... keys) {
        long now = System.currentTimeMillis();
        synchronized (rate) {
            prune(now);
            for (String k : keys) {
                Deque<Long> dq = rate.get(k);
                if (dq == null) continue;
                while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) dq.pollFirst();
                if (dq.size() >= MAX_PER_MINUTE) return false;
            }
            for (String k : keys) rate.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(now);
            return true;
        }
    }

    private void prune(long now) {
        if (rate.size() <= 1_000) return;
        rate.entrySet().removeIf(e -> {
            Deque<Long> dq = e.getValue();
            while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) dq.pollFirst();
            return dq.isEmpty();
        });
        if (rate.size() > 10_000) rate.clear();
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────

    private static String userKey(HttpSession session) {
        Long uid = longAttr(session, "userId");
        if (uid != null) return "u:" + uid;
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? "n:" + u : "s:" + (session != null ? session.getId() : "anon");
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static Long longAttr(HttpSession session, String name) {
        Object v = session != null ? session.getAttribute(name) : null;
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try { return Long.valueOf(v.toString().trim()); } catch (Exception ignored) { return null; }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        return clientIpResolver != null ? clientIpResolver.resolve(request) : request.getRemoteAddr();
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error",
                Msg.t("HTTP izlemesi bulunamadı.", "HTTP monitor not found.")));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String msg) {
        return ResponseEntity.status(429).body(Map.of("success", false, "error", msg));
    }
}
