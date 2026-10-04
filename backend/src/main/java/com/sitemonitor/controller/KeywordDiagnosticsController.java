package com.sitemonitor.controller;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.keyword.diagnose.KeywordDiagnosticsHistory;
import com.sitemonitor.service.keyword.diagnose.KeywordDiagnosticsService;
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
 * Keyword uçtan uca tanılama uçları (2026-10-04, kullanıcı isteği) — HTTP tanılamasının ({@link HttpDiagnosticsController})
 * birebir aynası:
 * <ul>
 *   <li>{@code POST /api/monitoring/keyword/{id}/diagnose} — gövde {@code {"compare": true}} (varsayılan true)</li>
 *   <li>{@code GET  /api/monitoring/keyword/{id}/diagnose/history} — son 20 çalıştırmanın özeti</li>
 *   <li>{@code GET  /api/monitoring/keyword/{id}/diagnose/history/{runId}} — saklanan tam sonuç (önizlemesiz)</li>
 * </ul>
 *
 * <p><b>Yetki:</b> {@code diagnostics.run/execute} + izlemenin takımını İŞLETEBİLMEK ({@link SessionScope#canOperateTeam}
 * — "Şimdi kontrol et" kuralı); liste satırındaki {@code can_diagnose} ({@code MonitoringController.listKeyword}) aynı
 * kural. <b>Hız sınırı:</b> kullanıcı başına VE izleme başına dakikada {@value #MAX_PER_MINUTE}, aynı anda en çok
 * {@value #MAX_CONCURRENT} (HTTP ile aynı değerler; ayrı pencere — iki tanılama birbirinin hakkını yemez). Tanılama
 * izlemeye DOKUNMAZ (kontrol kaydı yok, alarm değerlendirmesi yok). Denetim: {@code KEYWORD_DIAGNOSTICS_RUN}.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class KeywordDiagnosticsController {

    static final int MAX_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_CONCURRENT = 4;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PermissionService permissionService;
    private final KeywordDiagnosticsService diagnosticsService;
    private final KeywordDiagnosticsHistory diagnosticsHistory;
    private final AuditService auditService;

    @Autowired(required = false)
    private ClientIpResolver clientIpResolver;

    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT);

    @PostMapping("/keyword/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(@PathVariable Long id,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound();
        requireOperate(session, m);
        if (!MonitorUrls.isCheckable(m.getUrl())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", Msg.t(
                    "İzlemenin URL'si geçersiz (şema ya da host yok); tanılama çalıştırılamaz.",
                    "The monitor's URL is invalid (no scheme or host); the diagnostic cannot run.")));
        }
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
        // Anahtar kelimenin KENDİSİ ve özel başlıklar denetime yazılmaz; URL'nin sorgusu düşer (safeTarget).
        auditService.recordAction("KEYWORD_DIAGNOSTICS_RUN", session, "KEYWORD_MONITOR", String.valueOf(m.getId()),
                AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()),
                        "verdict", verdict.get("code"), "status", verdict.get("status"),
                        "compare", compare, "run_id", runId), null);
        return ok(data);
    }

    @GetMapping("/keyword/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> history(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound();
        requireOperate(session, m);
        List<Map<String, Object>> rows = diagnosticsHistory.list(id);
        return ok(rows);
    }

    @GetMapping("/keyword/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> historyRun(@PathVariable Long id, @PathVariable Long runId, HttpSession session) {
        requireDiagnosePermission(session);
        KeywordMonitor m = keywordMonitorRepo.findById(id).orElse(null);
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

    private static void requireOperate(HttpSession session, KeywordMonitor m) {
        if (!SessionScope.canOperateTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinde tanılama çalıştıramazsınız.",
                    "You cannot run diagnostics on this team's monitor."));
        }
    }

    /** İki pencere birlikte: ikisinden biri doluysa hiçbiri tüketilmez (HTTP denetleyicisiyle aynı kural). */
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
        if (v instanceof Number n) return Long.valueOf(n.longValue());
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
                Msg.t("Keyword izlemesi bulunamadı.", "Keyword monitor not found.")));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String msg) {
        return ResponseEntity.status(429).body(Map.of("success", false, "error", msg));
    }
}
