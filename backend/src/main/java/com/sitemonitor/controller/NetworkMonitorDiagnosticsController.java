package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.diagnose.DnsDiagnosticsService;
import com.sitemonitor.service.diagnose.NetDiagnosticsHistory;
import com.sitemonitor.service.diagnose.NetDiagnosticsHistory.Kind;
import com.sitemonitor.service.diagnose.PingDiagnosticsService;
import com.sitemonitor.service.diagnose.PortDiagnosticsService;
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
 * Ping / Port / DNS uçtan uca tanılama uçları (2026-10-05, kullanıcı isteği) — HTTP ve keyword tanılama uçlarının aynası:
 * <ul>
 *   <li>{@code POST /api/monitoring/{ping|port|dns}/{id}/diagnose} — ping gövdesi {@code {"traceroute": true}} (isteğe bağlı)</li>
 *   <li>{@code GET  /api/monitoring/{ping|port|dns}/{id}/diagnose/history} — son 20 çalıştırmanın özeti</li>
 *   <li>{@code GET  /api/monitoring/{ping|port|dns}/{id}/diagnose/history/{runId}} — saklanan tam sonuç</li>
 * </ul>
 *
 * <p><b>Yetki:</b> {@code diagnostics.run/execute} + izlemenin ETKİN takımını İŞLETEBİLMEK
 * ({@link SessionScope#canOperateTeam}). Envanter-türevi Port/DNS satırının takımı ENVANTERİN takımıdır — liste satırının
 * {@code can_check}'i ve "Şimdi kontrol et" ucuyla AYNI kural ({@code MonitoringController.effectiveTeam}); liste satırındaki
 * {@code can_diagnose} = {@code can_check} + izin. <b>Hız sınırı</b> (kendi penceresi; HTTP/keyword'ün hakkını yemez):
 * kullanıcı başına VE izleme başına dakikada {@value #MAX_PER_MINUTE}, aynı anda en çok {@value #MAX_CONCURRENT}.
 * Tanılama izlemeye DOKUNMAZ (kontrol kaydı yok, alarm değerlendirmesi yok, CA pinlenmez). Denetim:
 * {@code PING_DIAGNOSTICS_RUN} / {@code PORT_DIAGNOSTICS_RUN} / {@code DNS_DIAGNOSTICS_RUN} — sır yazılmaz.
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class NetworkMonitorDiagnosticsController {

    static final int MAX_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_CONCURRENT = 4;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PingMonitorRepository pingMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final PermissionService permissionService;
    private final PingDiagnosticsService pingDiagnostics;
    private final PortDiagnosticsService portDiagnostics;
    private final DnsDiagnosticsService dnsDiagnostics;
    private final NetDiagnosticsHistory history;
    private final AuditService auditService;

    @Autowired(required = false)
    private ClientIpResolver clientIpResolver;

    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT);

    /** Bir izlemenin tanılamaya gerekenleri (tür bağımsız). */
    private record Target(Kind kind, Long id, Long teamId, String name, String host, Integer port, String extraKey, Object extra) {}

    // ── Ping ────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/ping/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnosePing(@PathVariable Long id,
                                                            @RequestBody(required = false) Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        PingMonitor m = pingMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PING);
        boolean traceroute = body != null && Boolean.TRUE.equals(body.get("traceroute"));
        Target t = new Target(Kind.PING, m.getId(), m.getTeamId(), m.getName(), m.getHost(), null, "traceroute", traceroute);
        requireOperate(session, t.teamId());
        if (blank(m.getHost())) return badTarget();
        return execute(t, () -> pingDiagnostics.diagnose(m, traceroute), session, request);
    }

    @GetMapping("/ping/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> pingHistory(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        PingMonitor m = pingMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PING);
        requireOperate(session, m.getTeamId());
        return ok(history.list(Kind.PING, id));
    }

    @GetMapping("/ping/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> pingHistoryRun(@PathVariable Long id, @PathVariable Long runId, HttpSession session) {
        requireDiagnosePermission(session);
        PingMonitor m = pingMonitorRepo.findById(id).orElse(null);
        if (m == null) return notFound(Kind.PING);
        requireOperate(session, m.getTeamId());
        return storedRun(Kind.PING, id, runId);
    }

    // ── Port ────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/port/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnosePort(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        PortMonitor m = livePort(id);
        if (m == null) return notFound(Kind.PORT);
        Long team = effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId());
        requireOperate(session, team);
        if (blank(m.getHost()) || m.getPort() == null || m.getPort() < 1 || m.getPort() > 65535) return badTarget();
        Target t = new Target(Kind.PORT, m.getId(), team, m.getName(), m.getHost(), m.getPort(), "protocol",
                m.getProtocol() == null ? "TCP" : m.getProtocol());
        return execute(t, () -> portDiagnostics.diagnose(m), session, request);
    }

    @GetMapping("/port/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> portHistory(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        PortMonitor m = livePort(id);
        if (m == null) return notFound(Kind.PORT);
        requireOperate(session, effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId()));
        return ok(history.list(Kind.PORT, id));
    }

    @GetMapping("/port/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> portHistoryRun(@PathVariable Long id, @PathVariable Long runId, HttpSession session) {
        requireDiagnosePermission(session);
        PortMonitor m = livePort(id);
        if (m == null) return notFound(Kind.PORT);
        requireOperate(session, effectiveTeam(m.getHost(), m.getStandalone(), m.getTeamId()));
        return storedRun(Kind.PORT, id, runId);
    }

    // ── DNS ─────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/dns/{id}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnoseDns(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireDiagnosePermission(session);
        DnsMonitor m = liveDns(id);
        if (m == null) return notFound(Kind.DNS);
        Long team = effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId());
        requireOperate(session, team);
        if (blank(m.getDomain())) return badTarget();
        Target t = new Target(Kind.DNS, m.getId(), team, m.getName(), m.getDomain(), null, "record_type",
                m.getRecordType() == null ? "A" : m.getRecordType());
        return execute(t, () -> dnsDiagnostics.diagnose(m), session, request);
    }

    @GetMapping("/dns/{id}/diagnose/history")
    public ResponseEntity<Map<String, Object>> dnsHistory(@PathVariable Long id, HttpSession session) {
        requireDiagnosePermission(session);
        DnsMonitor m = liveDns(id);
        if (m == null) return notFound(Kind.DNS);
        requireOperate(session, effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId()));
        return ok(history.list(Kind.DNS, id));
    }

    @GetMapping("/dns/{id}/diagnose/history/{runId}")
    public ResponseEntity<Map<String, Object>> dnsHistoryRun(@PathVariable Long id, @PathVariable Long runId, HttpSession session) {
        requireDiagnosePermission(session);
        DnsMonitor m = liveDns(id);
        if (m == null) return notFound(Kind.DNS);
        requireOperate(session, effectiveTeam(m.getDomain(), m.getStandalone(), m.getTeamId()));
        return storedRun(Kind.DNS, id, runId);
    }

    // ── Ortak akış ──────────────────────────────────────────────────────────────────────────

    /** Sınırlar → tanılama → geçmiş → denetim. Kapılar çağırandan ÖNCE geçmiş olmalı. */
    private ResponseEntity<Map<String, Object>> execute(Target t, Supplier<Map<String, Object>> diagnose,
                                                        HttpSession session, HttpServletRequest request) {
        if (!inFlight.tryAcquire()) {
            return tooMany(Msg.t("Şu anda çok sayıda tanılama çalışıyor; birkaç saniye sonra yeniden deneyin.",
                    "Too many diagnostics are running right now; try again in a few seconds."));
        }
        Map<String, Object> data;
        try {
            if (!tryAcquireRate(userKey(session), t.kind().prefix + t.id())) {
                return tooMany(Msg.t("Çok fazla tanılama isteği — kullanıcı ve izleme başına dakikada en fazla " + MAX_PER_MINUTE
                                + ". Lütfen biraz bekleyin.",
                        "Too many diagnostic requests — at most " + MAX_PER_MINUTE
                                + " per minute per user and per monitor. Please wait a moment."));
            }
            data = new LinkedHashMap<>(diagnose.get());
        } finally {
            inFlight.release();
        }
        Long runId = history.save(t.kind(), t.id(), t.port(), data, actor(session), longAttr(session, "userId"),
                longAttr(session, "teamId"), clientIp(request));
        data.put("run_id", runId);
        Map<?, ?> verdict = data.get("verdict") instanceof Map<?, ?> v ? v : Map.of();
        String resource = switch (t.kind()) {
            case PING -> "PING_MONITOR";
            case PORT -> "PORT_MONITOR";
            case DNS -> "DNS_MONITOR";
        };
        String event = switch (t.kind()) {
            case PING -> "PING_DIAGNOSTICS_RUN";
            case PORT -> "PORT_DIAGNOSTICS_RUN";
            case DNS -> "DNS_DIAGNOSTICS_RUN";
        };
        // Denetim: hedef + hüküm; gönderilen veri / vekil kimliği / bulgu ayrıntısı YAZILMAZ.
        auditService.recordAction(event, session, resource, String.valueOf(t.id()),
                AuditDetail.of("name", t.name(), "host", t.host(), "port", t.port(), t.extraKey(), t.extra(),
                        "verdict", verdict.get("code"), "status", verdict.get("status"), "run_id", runId), null);
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

    private PortMonitor livePort(Long id) {
        return portMonitorRepo.findById(id).filter(m -> m.getDeletedAt() == null).orElse(null);
    }

    private DnsMonitor liveDns(Long id) {
        return dnsMonitorRepo.findById(id).filter(m -> m.getDeletedAt() == null).orElse(null);
    }

    /** {@code MonitoringController.effectiveTeam} ile AYNI kural: envanter-türevi satırın takımı envanterin takımıdır. */
    Long effectiveTeam(String domain, Boolean standalone, Long storedTeamId) {
        if (Boolean.TRUE.equals(standalone) || domain == null) return storedTeamId;
        return inventoryRepo.findByDomain(domain)
                .map(CertificateInventory::getTeamId)
                .orElse(storedTeamId);
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

    private static boolean blank(String s) { return s == null || s.isBlank(); }

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
        String tr = switch (kind) {
            case PING -> "Ping izlemesi bulunamadı.";
            case PORT -> "Port izlemesi bulunamadı.";
            case DNS -> "DNS izlemesi bulunamadı.";
        };
        String en = switch (kind) {
            case PING -> "Ping monitor not found.";
            case PORT -> "Port monitor not found.";
            case DNS -> "DNS monitor not found.";
        };
        return ResponseEntity.status(404).body(Map.of("success", false, "error", Msg.t(tr, en)));
    }

    private static ResponseEntity<Map<String, Object>> badTarget() {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", Msg.t(
                "İzlemenin hedefi geçersiz (host / port / alan adı yok); tanılama çalıştırılamaz.",
                "The monitor's target is invalid (no host / port / domain); the diagnostic cannot run.")));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String msg) {
        return ResponseEntity.status(429).body(Map.of("success", false, "error", msg));
    }

}
