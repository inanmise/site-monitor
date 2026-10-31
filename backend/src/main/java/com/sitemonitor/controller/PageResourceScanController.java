package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.page.scan.PageResourceScanService;
import com.sitemonitor.util.MonitorUrls;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sayfa Bütünlüğü "Kaynak analizi" uçları (2026-10-31, kullanıcı isteği) — yalnız kullanıcı isteyince çalışan nesne taraması.
 * <ul>
 *   <li>{@code POST /api/monitoring/page/{id}/resource-scans} — başlat (arka planda; 202 + tarama özeti)</li>
 *   <li>{@code GET  /api/monitoring/page/{id}/resource-scans?limit=} — geçmiş (özetler, 1 yıl) + çalışan tarama + {@code can_run}</li>
 *   <li>{@code GET  /api/monitoring/page/{id}/resource-scans/{scanId}} — tek tarama: özet + analiz + nesneler</li>
 *   <li>{@code POST /api/monitoring/page/{id}/resource-scans/{scanId}/cancel} — çalışan taramayı iptal (2026-11)</li>
 * </ul>
 *
 * <p><b>Yetki</b> (kullanıcı kararı): çalıştırmak "Şimdi kontrol et" kuralıdır — {@code monitoring.trigger/execute} + izlemenin
 * takımında ÇALIŞTIRMA kapsamı ({@link SessionScope#canExecuteTeam}, EXECUTE+ paylaşım dahil); okumak izlemeyi görebilen
 * herkes ({@code monitoring.read/view} + {@link SessionScope#canViewMonitoring}). Göremediği izleme 404 (varlığı sızmaz).
 * Denetim: {@code PAGE_RESOURCE_SCAN_RUN} (başlatma) ve {@code PAGE_RESOURCE_SCAN_CANCEL} (iptal); URL sorgusuz.
 */
@RestController
@RequestMapping("/api/monitoring/page")
@RequiredArgsConstructor
public class PageResourceScanController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PageMonitorRepository pageMonitorRepo;
    private final PermissionService permissionService;
    private final PageResourceScanService scans;
    private final AuditService auditService;

    @PostMapping("/{id}/resource-scans")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canExecuteTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinde kaynak analizi çalıştıramazsınız.",
                    "You cannot run a resource analysis on this team's monitor."));
        }
        if (!MonitorUrls.isCheckable(m.getUrl())) {
            return error(400, "VALIDATION_FAILED", Msg.t(
                    "İzlemenin adresi geçersiz (şema ya da sunucu adı yok); analiz çalıştırılamaz. Önce izlemeyi düzenleyin.",
                    "The monitor's address is invalid (no scheme or host name); the analysis cannot run. Edit the monitor first."));
        }
        PageResourceScanService.Start r = scans.start(m, userKey(session), displayName(session));
        return switch (r.outcome()) {
            case STARTED -> {
                Object scanId = r.scan() == null ? null : r.scan().get("id");
                auditService.recordAction("PAGE_RESOURCE_SCAN_RUN", session, "PAGE_MONITOR", String.valueOf(m.getId()),
                        AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "scan_id", scanId,
                                "via", r.scan() == null ? null : r.scan().get("via")), null);
                yield ResponseEntity.status(202).body(ok(r.scan()));
            }
            case RUNNING -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("code", "RESOURCE_SCAN_RUNNING");
                body.put("error", Msg.t("Bu izleme için bir kaynak analizi zaten çalışıyor; sonucu birazdan burada görünür.",
                        "A resource analysis is already running for this monitor; its result will appear here shortly."));
                body.put("data", r.scan());
                yield ResponseEntity.status(409).body(body);
            }
            case RATE -> tooMany("RATE_LIMITED", r.retryAfterSeconds(), Msg.t(
                    "Çok sık analiz başlattınız — kişi başına dakikada en çok " + PageResourceScanService.STARTS_PER_MINUTE
                            + " analiz. " + r.retryAfterSeconds() + " sn sonra yeniden deneyin.",
                    "You started analyses too often — at most " + PageResourceScanService.STARTS_PER_MINUTE
                            + " per minute per person. Try again in " + r.retryAfterSeconds() + " s."));
            case BUSY -> tooMany("RESOURCE_SCAN_BUSY", r.retryAfterSeconds(), Msg.t(
                    "Sunucuda şu an en çok " + PageResourceScanService.MAX_PER_POD
                            + " kaynak analizi birlikte çalışabiliyor ve şu an dolu. Birkaç saniye sonra yeniden deneyin.",
                    "At most " + PageResourceScanService.MAX_PER_POD
                            + " resource analyses can run together on the server and it is busy right now. Try again in a few seconds."));
        };
    }

    /**
     * Çalışan taramayı iptal eder (2026-11). İzin = çalıştırma izni ("Şimdi kontrol et" kuralı). Yalnız RUNNING tarama: satır
     * INTERRUPTED / CANCELLED olur; koşan pod (hangisi olursa olsun) kalp atışında görüp durur. Bitmiş tarama → 409
     * {@code RESOURCE_SCAN_NOT_RUNNING} (güncel özetle); bilinmeyen tarama → 404. Denetim {@code PAGE_RESOURCE_SCAN_CANCEL}.
     */
    @PostMapping("/{id}/resource-scans/{scanId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id, @PathVariable Long scanId, HttpSession session) {
        permissionService.require(session, "monitoring.trigger", "execute");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canExecuteTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinde kaynak analizini iptal edemezsiniz.",
                    "You cannot cancel a resource analysis on this team's monitor."));
        }
        PageResourceScanService.Cancel r = scans.cancel(scanId, m.getId());
        return switch (r.outcome()) {
            case CANCELLED -> {
                auditService.recordAction("PAGE_RESOURCE_SCAN_CANCEL", session, "PAGE_MONITOR", String.valueOf(m.getId()),
                        AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "scan_id", scanId), null);
                yield ResponseEntity.ok(ok(r.scan()));
            }
            case NOT_RUNNING -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("code", "RESOURCE_SCAN_NOT_RUNNING");
                body.put("error", Msg.t("Bu kaynak analizi artık çalışmıyor (bitti ya da yarıda kaldı); iptal edilecek bir şey yok.",
                        "This resource analysis is no longer running (it finished or was cut short); there is nothing to cancel."));
                body.put("data", r.scan());
                yield ResponseEntity.status(409).body(body);
            }
            case NOT_FOUND -> error(404, "RESOURCE_SCAN_NOT_FOUND", Msg.t(
                    "Kaynak analizi kaydı bulunamadı; silinmiş ya da saklama süresi dolmuş olabilir. Geçmişi yenileyin.",
                    "Resource analysis run not found; it may have been deleted or aged out. Refresh the history."));
        };
    }

    @GetMapping("/{id}/resource-scans")
    public ResponseEntity<Map<String, Object>> list(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "200") int limit, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        Map<String, Object> data = new LinkedHashMap<>(scans.list(m.getId(), limit));
        data.put("can_run", canRun(session, m));
        return ResponseEntity.ok(ok(data));
    }

    @GetMapping("/{id}/resource-scans/{scanId}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable Long id, @PathVariable Long scanId, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        Map<String, Object> data = scans.one(scanId, m.getId());
        if (data == null) {
            return error(404, "RESOURCE_SCAN_NOT_FOUND", Msg.t(
                    "Kaynak analizi kaydı bulunamadı; silinmiş ya da saklama süresi dolmuş olabilir. Geçmişi yenileyin.",
                    "Resource analysis run not found; it may have been deleted or aged out. Refresh the history."));
        }
        return ResponseEntity.ok(ok(data));
    }

    private boolean canRun(HttpSession session, PageMonitor m) {
        return permissionService.allows(session, "monitoring.trigger", "execute")
                && SessionScope.canExecuteTeam(session, m.getTeamId())
                && MonitorUrls.isCheckable(m.getUrl());
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────

    private static String userKey(HttpSession session) {
        Object uid = session == null ? null : session.getAttribute("userId");
        if (uid != null) return "u:" + uid;
        Object u = session == null ? null : session.getAttribute("username");
        return u != null ? "n:" + u : "s:" + (session == null ? "anon" : session.getId());
    }

    private static String displayName(HttpSession session) {
        if (session == null) return null;
        Object d = session.getAttribute("displayName");
        if (d != null && !d.toString().isBlank()) return d.toString();
        Object u = session.getAttribute("username");
        return u == null ? null : u.toString();
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(Instant.now()));
        return body;
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return error(404, "NOT_FOUND", Msg.t("Sayfa Bütünlüğü izlemesi bulunamadı ya da görüntüleme yetkiniz yok.",
                "Page integrity monitor not found, or you are not allowed to view it."));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String code, long retryAfter, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", msg);
        body.put("retry_after_seconds", retryAfter);
        return ResponseEntity.status(429).header("Retry-After", String.valueOf(Math.max(1, retryAfter))).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", msg);
        return ResponseEntity.status(status).body(body);
    }
}
