package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsHistory;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsHistory.Kind;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsService;
import com.sitemonitor.service.page.diagnose.PageSpeedDiagnosticsService;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Sayfa Bütünlüğü + Sayfa Hızı uçtan uca tanılama uçları (2026-10-05, kullanıcı isteği) — keyword/HTTP tanılama uçlarının
 * aynası:
 * <ul>
 *   <li>{@code POST /api/monitoring/{page|pagespeed}/{id}/diagnose} — gövde {@code {"compare": true}} (varsayılan true)</li>
 *   <li>{@code GET  /api/monitoring/{page|pagespeed}/{id}/diagnose/history} — son 20 çalıştırmanın özeti</li>
 *   <li>{@code GET  /api/monitoring/{page|pagespeed}/{id}/diagnose/history/{runId}} — saklanan tam sonuç (önizlemesiz)</li>
 * </ul>
 *
 * <p><b>Yetki:</b> {@code diagnostics.run/execute} + izlemenin takımını İŞLETEBİLMEK ({@link SessionScope#canOperateTeam}
 * — "Şimdi kontrol et" kuralı); liste satırındaki {@code can_diagnose} ({@code MonitoringController.withDiagnoseFlag}) aynı
 * kural. <b>Hız sınırı</b> (kendi penceresi; HTTP / keyword / ağ tanılamasının hakkını yemez): kullanıcı başına VE izleme
 * başına dakikada {@value #MAX_PER_MINUTE}, aynı anda en çok {@value #MAX_CONCURRENT}. Tanılama izlemeye DOKUNMAZ (kontrol
 * kaydı yok, alarm değerlendirmesi yok, CA pinlenmez). Denetim: {@code PAGE_DIAGNOSTICS_RUN} /
 * {@code PAGESPEED_DIAGNOSTICS_RUN} — özel başlık / parola / URL sorgusu yazılmaz.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class PageDiagnosticsController {

    static final int MAX_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_CONCURRENT = 4;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PageMonitorRepository pageMonitorRepo;
    private final PageSpeedMonitorRepository pageSpeedMonitorRepo;
    private final PermissionService permissionService;
    private final PageDiagnosticsService pageDiagnostics;
    private final PageSpeedDiagnosticsService pageSpeedDiagnostics;
    private final PageDiagnosticsHistory history;
    private final AuditService auditService;

    @Autowired(required = false)
    private ClientIpResolver clientIpResolver;

    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT);

    /** Bir izlemenin tanılamaya gerekenleri (tür bağımsız). */
    private record Target(Kind kind, Long id, Long teamId, String name, String url) {}

    // ── Sayfa Bütünlüğü ─────────────────────────────────────────────────────────────────────

    @PostMapping("/page/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnosePage(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PAGE);
        requireOperate(session, m.getTeamId());
        if (!MonitorUrls.isCheckable(m.getUrl())) return badUrl();
        boolean compare = compareOf(body);
        Target t = new Target(Kind.PAGE, m.getId(), m.getTeamId(), m.getName(), m.getUrl());
        return execute(t, compare, () -> pageDiagnostics.diagnose(m, compare), session, request);
    }

    @GetMapping("/page/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> pageHistory(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PAGE);
        requireOperate(session, m.getTeamId());
        return ok(history.list(Kind.PAGE, id));
    }

    @GetMapping("/page/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> pageHistoryRun(@PathVariable Long id, @PathVariable Long runId, HttpSession session) {
        requireDiagnosePermission(session);
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PAGE);
        requireOperate(session, m.getTeamId());
        return storedRun(Kind.PAGE, id, runId);
    }

    // ── Sayfa Hızı ──────────────────────────────────────────────────────────────────────────

    @PostMapping("/pagespeed/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnosePageSpeed(@PathVariable Long id,
                                                                 @RequestBody(required = false) Map<String, Object> body,
                                                                 HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        PageSpeedMonitor m = pageSpeedMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PAGESPEED);
        requireOperate(session, m.getTeamId());
        if (!MonitorUrls.isCheckable(m.getUrl())) return badUrl();
        boolean compare = compareOf(body);
        Target t = new Target(Kind.PAGESPEED, m.getId(), m.getTeamId(), m.getName(), m.getUrl());
        return execute(t, compare, () -> pageSpeedDiagnostics.diagnose(m, compare), session, request);
    }

    @GetMapping("/pagespeed/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> pageSpeedHistory(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        PageSpeedMonitor m = pageSpeedMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PAGESPEED);
        requireOperate(session, m.getTeamId());
        return ok(history.list(Kind.PAGESPEED, id));
    }

    @GetMapping("/pagespeed/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> pageSpeedHistoryRun(@PathVariable Long id, @PathVariable Long runId,
                                                                   HttpSession session) {
        requireDiagnosePermission(session);
        PageSpeedMonitor m = pageSpeedMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PAGESPEED);
        requireOperate(session, m.getTeamId());
        return storedRun(Kind.PAGESPEED, id, runId);
    }

    // ── Ortak akış ──────────────────────────────────────────────────────────────────────────

    /** Sınırlar → tanılama → geçmiş → denetim. Kapılar çağırandan ÖNCE geçmiş olmalı. */
    private ResponseEntity<Map<String, Object>> execute(Target t, boolean compare, Supplier<Map<String, Object>> diagnose,
                                                        HttpSession session, HttpServletRequest request) {
        if (!inFlight.tryAcquire()) {
            return tooMany(Msg.t("Şu anda çok sayıda tanılama çalışıyor; birkaç saniye sonra yeniden deneyin.",
                    "Too many diagnostics are running right now; try again in a few seconds."));
        }
        Map<String, Object> data;
        try {
            if (!tryAcquireRate(userKey(session), "m:" + t.kind().prefix + t.id())) {
                return tooMany(Msg.t("Çok fazla tanılama isteği — kullanıcı ve izleme başına dakikada en fazla " + MAX_PER_MINUTE
                                + ". Lütfen biraz bekleyin.",
                        "Too many diagnostic requests — at most " + MAX_PER_MINUTE
                                + " per minute per user and per monitor. Please wait a moment."));
            }
            data = new LinkedHashMap<>(diagnose.get());
        } finally {
            inFlight.release();
        }
        Long runId = history.save(t.kind(), t.id(), t.url(), data, actor(session), longAttr(session, "userId"),
                longAttr(session, "teamId"), clientIp(request));
        data.put("run_id", runId);
        Map<?, ?> verdict = data.get("verdict") instanceof Map<?, ?> v ? v : Map.of();
        String resource = t.kind() == Kind.PAGE ? "PAGE_MONITOR" : "PAGESPEED_MONITOR";
        String event = t.kind() == Kind.PAGE ? "PAGE_DIAGNOSTICS_RUN" : "PAGESPEED_DIAGNOSTICS_RUN";
        // Denetim: hedef (sorgusuz) + hüküm; özel başlık / parola / bulgu ayrıntısı YAZILMAZ.
        auditService.recordAction(event, session, resource, String.valueOf(t.id()),
                AuditDetail.of("name", t.name(), "url", AuditDetail.safeTarget(t.url()),
                        "verdict", verdict.get("code"), "status", verdict.get("status"),
                        "compare", compare, "run_id", runId), null);
        return ok(data);
    }

    private ResponseEntity<Map<String, Object>> storedRun(Kind kind, Long id, Long runId) {
        Map<String, Object> run = history.get(kind, id, runId);
        if (run == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error",
                    Msg.t("Tanılama kaydı bulunamadı.", "Diagnostic run not found.")));
        }
        return ok(run);
    }

    private static boolean compareOf(Map<String, Object> body) {
        return body == null || !Boolean.FALSE.equals(body.get("compare"));
    }

    // ── Kapılar ─────────────────────────────────────────────────────────────────────────────

    private void requireDiagnosePermission(HttpSession session) {
        if (!permissionService.allows(session, "diagnostics.run", "execute")) {
            throw new SecurityException(Msg.t("Tanılama çalıştırma izniniz yok (diagnostics.run).",
                    "You are not allowed to run diagnostics (diagnostics.run)."));
        }
    }

    private static void requireOperate(HttpSession session, Long teamId) {
        if (!SessionScope.canOperateTeam(session, teamId)) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinde tanılama çalıştıramazsınız.",
                    "You cannot run diagnostics on this team's monitor."));
        }
    }

    /** İki pencere birlikte: ikisinden biri doluysa hiçbiri tüketilmez (HTTP/keyword denetleyicisiyle aynı kural). */
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

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────

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

    private static ResponseEntity<Map<String, Object>> notFound(Kind kind) {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", kind == Kind.PAGE
                ? Msg.t("Sayfa Bütünlüğü izlemesi bulunamadı.", "Page integrity monitor not found.")
                : Msg.t("Sayfa Hızı izlemesi bulunamadı.", "Page speed monitor not found.")));
    }

    private static ResponseEntity<Map<String, Object>> badUrl() {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", Msg.t(
                "İzlemenin URL'si geçersiz (şema ya da host yok); tanılama çalıştırılamaz.",
                "The monitor's URL is invalid (no scheme or host); the diagnostic cannot run.")));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String msg) {
        return ResponseEntity.status(429).body(Map.of("success", false, "error", msg));
    }
}
