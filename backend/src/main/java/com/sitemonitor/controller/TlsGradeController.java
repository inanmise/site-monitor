package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.InventoryVisibility;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import com.sitemonitor.service.tlsgrade.TlsProfileJobService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * TLS yapılandırma notu uçları (2026-10-10).
 * <ul>
 *   <li>{@code GET  /api/certificates/{domain}/tls-grade} — sertifika penceresinin "TLS notu" bölümü (not, tüm nedenler,
 *       profil, HSTS, not geçmişi). OKUMA kapısı sağlık listesiyle AYNI: kendi takım kapsamı, 7/24 operatörü ya da org
 *       geneli okuma; aksi hâlde 404 + güvenlik olayı.</li>
 *   <li>{@code POST /api/certificates/{domain}/tls-grade/rescan} — TLS profilini ŞİMDİ yeniden tarar. Kapı tanılamalarla
 *       aynı: {@code diagnostics.run/execute} + kaydın takımını işletebilmek ({@link SessionScope#canOperateTeam}). Kullanıcı
 *       ve alan adı başına dakikada {@value #MAX_PER_MINUTE}, aynı anda en çok {@value #MAX_CONCURRENT}; aşımda 429. Elle
 *       yüklenen sertifikada 409 {@code MANUAL_CERT}. Denetim {@code CERT_TLS_PROFILE_RESCAN}. Alarm üretmez.</li>
 *   <li>{@code GET  /api/tls-grade/drops} — son düşüşler + profil kapsaması; kapsam izleme okumasıyla aynı
 *       ({@link SessionScope#monitoringViewTeamIds}).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TlsGradeController {

    static final int MAX_PER_MINUTE = 3;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_CONCURRENT = 2;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final TlsGradeService gradeService;
    private final TlsProfileJobService jobService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CertificateService certificateService;

    /** Org geneli okuma (isteğe bağlı — dilimli test bağlamında yok). */
    @Autowired(required = false)
    private InventoryVisibility inventoryVisibility;

    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT);

    @GetMapping("/certificates/{domain}/tls-grade")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String domain, HttpSession session,
                                                      HttpServletRequest request) {
        CertificateInventory inv = readable(session, domain, request);
        if (inv == null) return notFound();
        LatestCheck lc = latestCheckRepo.findById(inv.getDomain()).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>(gradeService.detail(inv, lc));
        data.put("can_rescan", canRescan(session, inv));
        return ok(data);
    }

    @PostMapping("/certificates/{domain}/tls-grade/rescan")
    public ResponseEntity<Map<String, Object>> rescan(@PathVariable String domain, HttpSession session,
                                                      HttpServletRequest request) {
        requireRescanPermission(session);
        CertificateInventory inv = readable(session, domain, request);
        if (inv == null) return notFound();
        if (!SessionScope.canOperateTeam(session, inv.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Bu alan adının takımında işlem yetkiniz yok; TLS notunu yalnız görüntüleyebilirsiniz.",
                    "You can view this domain's TLS grade but can't rescan it; it belongs to a team outside your scope."));
        }
        if (inv.isManual()) {
            return ResponseEntity.status(409).body(Map.of("success", false, "code", "MANUAL_CERT", "error", Msg.t(
                    "Bu sertifika dosyadan yüklendi; ağ ucu olmadığı için TLS profili taranamaz.",
                    "This certificate was uploaded from a file; it has no network endpoint, so its TLS profile cannot be scanned.")));
        }
        // Önce eşzamanlılık yuvası, sonra pencere: meşgul reddi dakikalık hakkı TÜKETMEZ (tanılama deseni).
        if (!inFlight.tryAcquire()) {
            return tooMany(Msg.t("Şu anda başka TLS taramaları sürüyor; birkaç saniye sonra yeniden deneyin.",
                    "Other TLS scans are running right now; try again in a few seconds."));
        }
        TlsProfile p;
        try {
            if (!tryAcquireRate(userKey(session), "d:" + inv.getDomain().toLowerCase(java.util.Locale.ROOT))) {
                return tooMany(Msg.t("Çok sık TLS taraması — kullanıcı ve alan adı başına dakikada en fazla " + MAX_PER_MINUTE
                                + ". Lütfen biraz bekleyin.",
                        "Too many TLS scans — at most " + MAX_PER_MINUTE + " per minute per user and per domain. Please wait a moment."));
            }
            p = jobService.rescan(inv, actor(session));
        } finally {
            inFlight.release();
        }
        auditService.recordAction("CERT_TLS_PROFILE_RESCAN", session, "CERTIFICATE", inv.getDomain(),
                AuditDetail.of("domain", inv.getDomain(), "status", p.getStatus(), "via", p.getVia(),
                        "tls13", p.getTls13(), "tls12", p.getTls12(), "tls11", p.getTls11(), "tls10", p.getTls10(),
                        "duration_ms", p.getDurationMs()), null);
        LatestCheck lc = latestCheckRepo.findById(inv.getDomain()).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>(gradeService.detail(inv, lc));
        data.put("can_rescan", true);
        return ok(data);
    }

    @GetMapping("/tls-grade/drops")
    public ResponseEntity<Map<String, Object>> drops(@RequestParam(defaultValue = "30") int days,
                                                     @RequestParam(defaultValue = "50") int limit,
                                                     HttpSession session) {
        List<Long> scope = SessionScope.monitoringViewTeamIds(session);
        Map<Long, String> teamNames;
        try {
            teamNames = certificateService.teamNamesById();
        } catch (Exception e) {
            teamNames = Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", Math.max(1, Math.min(days, 180)));
        data.put("rows", gradeService.drops(scope, days, limit, teamNames));
        List<CertificateInventory> inScope = new ArrayList<>();
        if (scope == null || !scope.isEmpty()) {
            for (CertificateInventory inv : inventoryRepo.findByActiveTrueOrderByDomainAsc()) {
                if (scope == null || (inv.getTeamId() != null && scope.contains(inv.getTeamId()))
                        || (inv.getUgTeamId() != null && scope.contains(inv.getUgTeamId()))) inScope.add(inv);
            }
        }
        data.put("coverage", gradeService.coverage(inScope));
        return ok(data);
    }

    // ── Kapılar ─────────────────────────────────────────────────────────────────────────────────

    private boolean canRescan(HttpSession session, CertificateInventory inv) {
        return !inv.isManual() && permissionService.allows(session, "diagnostics.run", "execute")
                && SessionScope.canOperateTeam(session, inv.getTeamId());
    }

    private void requireRescanPermission(HttpSession session) {
        if (!permissionService.allows(session, "diagnostics.run", "execute")) {
            throw new SecurityException(Msg.t("TLS profilini taramak için tanılama izni gerekir (diagnostics.run).",
                    "You need the diagnostics permission (diagnostics.run) to scan the TLS profile."));
        }
    }

    /**
     * Okuma kapısı — sertifika sağlık listesiyle AYNI kural ({@code CertificateController.requireReadableForHealth}):
     * 7/24 operatörü her kaydı okur; kendi kapsamı dışındaki kayıt org geneli okunabiliyorsa okunur; aksi hâlde güvenlik
     * olayı + 404 (403 "var ama giremezsin" bilgisini sızdırırdı).
     */
    private CertificateInventory readable(HttpSession session, String domain, HttpServletRequest request) {
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null) return null;
        if (SessionScope.isNocOperator(session)) return inv;
        if (SessionScope.canView(session, inv.getTeamId())) return inv;
        if (inventoryVisibility != null && inventoryVisibility.readableOrgWide(session, inv)) return inv;
        auditService.recordSecurityEvent("CERT_HEALTH_DENIED", request, session, "CERTIFICATE", domain,
                "Yetkisiz TLS notu erişimi");
        return null;
    }

    boolean tryAcquireRate(String... keys) {
        long now = System.currentTimeMillis();
        synchronized (rate) {
            if (rate.size() > 1_000) {
                rate.entrySet().removeIf(e -> {
                    Deque<Long> dq = e.getValue();
                    while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) dq.pollFirst();
                    return dq.isEmpty();
                });
                if (rate.size() > 10_000) rate.clear();
            }
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

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────

    private static String userKey(HttpSession session) {
        Object uid = session != null ? session.getAttribute("userId") : null;
        if (uid != null) return "u:" + uid;
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? "n:" + u : "s:" + (session != null ? session.getId() : "anon");
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "code", "NOT_FOUND", "error", Msg.t(
                "Sertifika kaydı bulunamadı ya da görüntüleme yetkiniz yok.",
                "The certificate record was not found or you are not allowed to view it.")));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String msg) {
        return ResponseEntity.status(429).body(Map.of("success", false, "code", "RATE_LIMITED", "error", msg));
    }
}
