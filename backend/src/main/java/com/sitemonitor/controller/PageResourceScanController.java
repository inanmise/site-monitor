package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.page.scan.PageResourceScanService;
import com.sitemonitor.service.page.scan.budget.ResourceBudget;
import com.sitemonitor.service.page.scan.budget.ResourceBudgetService;
import com.sitemonitor.service.page.scan.budget.ResourceTrendService;
import com.sitemonitor.util.MonitorUrls;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sayfa Bütünlüğü "Kaynak analizi" uçları (2026-10-31, kullanıcı isteği) — yalnız kullanıcı isteyince çalışan nesne taraması.
 * <ul>
 *   <li>{@code POST /api/monitoring/page/{id}/resource-scans} — başlat (arka planda; 202 + tarama özeti)</li>
 *   <li>{@code GET  /api/monitoring/page/{id}/resource-scans?limit=} — geçmiş (özetler, 1 yıl) + çalışan tarama + {@code can_run}</li>
 *   <li>{@code GET  /api/monitoring/page/{id}/resource-scans/{scanId}} — tek tarama: özet + analiz + nesneler</li>
 *   <li>{@code POST /api/monitoring/page/{id}/resource-scans/{scanId}/cancel} — çalışan taramayı iptal (2026-11)</li>
 *   <li>{@code GET  /api/monitoring/page/{id}/resource-scans/trend?days=30|90|365} — eğilim: noktalar + en çok büyüyen nesneler (F2)</li>
 *   <li>{@code GET / PUT /api/monitoring/page/{id}/resource-budget} — kaynak analizi bütçesi (F1)</li>
 * </ul>
 *
 * <p><b>Bütçe</b> (2026-11, F1): okuma = analiz listesiyle aynı kapı; yazma = izlemeyi düzenleme kapısı
 * ({@code monitoring.crud/edit} + {@link SessionScope#canOperateTeam}, EDIT paylaşımı dahil). Değerlendirme OKUMA anındadır,
 * GÜNCEL bütçeyle: liste satırları {@code budget_status} / {@code budget_exceeded}, tek analiz {@code budget} taşır — geçmiş
 * analizler de bugünkü sınırlarla işaretlenir. Alarm / bildirim yok. Denetim {@code PAGE_RESOURCE_BUDGET_SAVE} (fark ile).
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
    private final ResourceBudgetService budgets;
    private final ResourceTrendService trends;

    /**
     * Başlatır. Gövde isteğe bağlı (prs3 G, çok sayfalı): {@code {scope:"site", max_pages:N}} — izlemenin sayfası + aynı
     * host'taki iç sayfalar, en çok N sayfa (ana sayfa dahil; {@code max_pages} yoksa ayar, varsa
     * [{@value PageResourceScanService#MAX_PAGES_MIN}, ayarın tavanı]'na kırpılır). Gövdesiz / {@code scope:"page"} = bugünkü tek
     * sayfa analizi. {@code scope} başka değer, {@code max_pages} tam sayı değil → 400 {@code VALIDATION_FAILED} + {@code field}.
     */
    @PostMapping("/{id}/resource-scans")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, Object> reqBody,
                                                     HttpSession session) {
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
        PageResourceScanService.SiteOptions site = siteOptions(reqBody);
        PageResourceScanService.Start r = site == null ? scans.start(m, userKey(session), displayName(session))
                : scans.start(m, userKey(session), displayName(session), site);
        return switch (r.outcome()) {
            case STARTED -> {
                Object scanId = r.scan() == null ? null : r.scan().get("id");
                String detail = site == null
                        ? AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "scan_id", scanId,
                                "via", r.scan() == null ? null : r.scan().get("via"))
                        : AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl()), "scan_id", scanId,
                                "via", r.scan() == null ? null : r.scan().get("via"), "scope", PageResourceScanService.SCOPE_SITE,
                                "max_pages", site.maxPages());
                auditService.recordAction("PAGE_RESOURCE_SCAN_RUN", session, "PAGE_MONITOR", String.valueOf(m.getId()),
                        detail, null);
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
        // Bütçe işaretleri (F1): GÜNCEL bütçeyle, okuma anında; okunamazsa satırlar "bilinmiyor" (asla "geçti")
        ResourceBudget budget = ResourceBudgetService.budgetOf(m);
        if (data.get("items") instanceof List<?> items) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) items;
            if (!budgets.annotate(m.getId(), budget, rows)) data.put("budget_unreadable", true);
        }
        data.put("budget", budgetView(budget));
        return ResponseEntity.ok(ok(data));
    }

    /**
     * Eğilim (F2): pencerenin bitmiş analizleri (≤ 200, eskiden yeniye) + en çok büyüyen ≤ 20 nesne. {@code days} 30 | 90 | 365;
     * başka değer 90. Okuma kapısı listeyle aynı.
     */
    @GetMapping("/{id}/resource-scans/trend")
    public ResponseEntity<Map<String, Object>> trend(@PathVariable Long id, @RequestParam(required = false) String days,
                                                     HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        return ResponseEntity.ok(ok(trends.trend(m.getId(), ResourceTrendService.days(days), ResourceBudgetService.budgetOf(m))));
    }

    /** Bütçe (F1): yedi sınır (tanımsız = null) + {@code defined} + {@code can_edit} + alan aralıkları. */
    @GetMapping("/{id}/resource-budget")
    public ResponseEntity<Map<String, Object>> getBudget(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        return ResponseEntity.ok(ok(budgetBody(ResourceBudgetService.budgetOf(m), canEditBudget(session, m), null)));
    }

    /**
     * Bütçeyi kaydeder (F1). Gövde düz: {@code max_page_kb}, {@code max_requests}, {@code max_js_kb}, {@code max_image_kb},
     * {@code max_load_ms}, {@code min_grade}, {@code min_security_score} — hepsi isteğe bağlı; YALNIZ gönderilen anahtar değişir
     * (null = sınırı kaldır), bilinmeyen anahtar yok sayılır. Geçersiz değer 400 {@code VALIDATION_FAILED} + {@code field}
     * (hiçbir şey yazılmaz); değişiklik yoksa yazılmaz ve denetlenmez ({@code changed:false}).
     */
    @PutMapping("/{id}/resource-budget")
    public ResponseEntity<Map<String, Object>> putBudget(@PathVariable Long id,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canOperateTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinin kaynak analizi bütçesini değiştiremezsiniz.",
                    "You cannot change the resource analysis budget of this team's monitor."));
        }
        ResourceBudgetService.Save r = budgets.save(m.getId(), m.getResourceBudgetJson(), body == null ? Map.of() : body);
        return switch (r.outcome()) {
            case SAVED -> {
                auditService.recordAction("PAGE_RESOURCE_BUDGET_SAVE", session, "PAGE_MONITOR", String.valueOf(m.getId()),
                        AuditDetail.of("name", m.getName(), "url", AuditDetail.safeTarget(m.getUrl())),
                        AuditDiff.diff(r.before().toMap(), r.after().toMap()));
                yield ResponseEntity.ok(ok(budgetBody(r.after(), true, true)));
            }
            case UNCHANGED -> ResponseEntity.ok(ok(budgetBody(r.after(), true, false)));
            case NOT_FOUND -> notFound();
            case CONFLICT -> error(409, "CONCURRENT_MODIFICATION", Msg.t(
                    "Bütçe aynı anda başka biri tarafından değiştiriliyor; kaydedilemedi. Sayfayı yenileyip yeniden deneyin.",
                    "Someone else is changing the budget at the same time; it was not saved. Refresh the page and try again."));
        };
    }

    private boolean canEditBudget(HttpSession session, PageMonitor m) {
        return permissionService.allows(session, "monitoring.crud", "edit") && SessionScope.canOperateTeam(session, m.getTeamId());
    }

    /** Bütçe yanıtı: sınırlar + tanımlı mı + düzenlenebilir mi + aralıklar (+ {@code changed} yalnız kaydetmede). */
    static Map<String, Object> budgetBody(ResourceBudget b, boolean canEdit, Boolean changed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("budget", b.toMap());
        out.put("defined", b.defined());
        out.put("can_edit", canEdit);
        if (changed != null) out.put("changed", changed);
        Map<String, Object> ranges = new LinkedHashMap<>();
        for (ResourceBudget.Field f : ResourceBudget.FIELDS) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("min", f.min());
            r.put("max", f.max());
            r.put("unit", f.unit());
            r.put("kind", f.kind().name().toLowerCase(Locale.ROOT));
            ranges.put(f.key(), r);
        }
        out.put("ranges", ranges);
        return out;
    }

    /** Liste yanıtındaki bütçe özeti: yedi sınır + {@code defined}. */
    private static Map<String, Object> budgetView(ResourceBudget b) {
        Map<String, Object> m = b.toMap();
        m.put("defined", b.defined());
        return m;
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
        data = new LinkedHashMap<>(data);
        // Bütçe (F1): GÜNCEL bütçeyle okuma anında değerlendirilir
        data.put("budget", budgets.evaluateOne(m.getId(), ResourceBudgetService.budgetOf(m), scanId));
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

    /**
     * Başlatma gövdesi → çok sayfalı seçenek (null = tek sayfa). {@code max_pages}: tam sayı (sayı ya da rakam metni), yoksa
     * ayarın değeri; [taban, ayarın tavanı]'na kırpılır.
     */
    PageResourceScanService.SiteOptions siteOptions(Map<String, Object> body) {
        if (body == null || body.isEmpty()) return null;
        Object rawScope = body.get("scope");
        String scope = rawScope == null ? null : String.valueOf(rawScope).trim().toLowerCase(Locale.ROOT);
        if (scope == null || scope.isEmpty() || PageResourceScanService.SCOPE_PAGE.equals(scope)) return null;
        if (!PageResourceScanService.SCOPE_SITE.equals(scope)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("scope", Msg.t(
                    "Kapsam yalnız \"page\" (tek sayfa) ya da \"site\" (çok sayfalı) olabilir.",
                    "The scope can only be \"page\" (single page) or \"site\" (several pages)."));
        }
        int cap = scans.siteLimits().maxPages();
        Object raw = body.get("max_pages");
        int n = cap;
        if (raw != null) {
            Integer parsed = wholeNumber(raw);
            if (parsed == null) {
                throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("max_pages", Msg.t(
                        "En çok sayfa sayısı tam sayı olmalı (" + PageResourceScanService.MAX_PAGES_MIN + "–" + cap + ").",
                        "The page count must be a whole number (" + PageResourceScanService.MAX_PAGES_MIN + "–" + cap + ")."));
            }
            n = Math.max(PageResourceScanService.MAX_PAGES_MIN, Math.min(cap, parsed));
        }
        return new PageResourceScanService.SiteOptions(n);
    }

    /** Tam sayı mı (JSON sayısı ya da en çok 6 haneli rakam metni); değilse null. */
    static Integer wholeNumber(Object raw) {
        if (raw instanceof Number num) {
            double d = num.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) return null;
            if (d > 1_000_000 || d < -1_000_000) return d > 0 ? 1_000_000 : -1_000_000;
            return (int) d;
        }
        if (raw instanceof String s) {
            String t = s.trim();
            if (t.isEmpty() || t.length() > 6) return null;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) < '0' || t.charAt(i) > '9') return null;
            return Integer.parseInt(t);
        }
        return null;
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
