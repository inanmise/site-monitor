package com.sitemonitor.controller;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.report.ExecutiveSummaryPdfWriter;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummaryDeliveryService;
import com.sitemonitor.service.report.executive.ExecutiveSummaryService;
import com.sitemonitor.service.report.executive.ExecutiveSummarySettings;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AYLIK YÖNETİCİ ÖZETİ uçları (2026-10-10) — {@code /api/executive-summary}.
 *
 * <h2>Yetki</h2>
 * <ul>
 *   <li><b>Okuma</b> (özet, PDF): kurum geneli görüntüleyici ({@link SessionScope#isGlobalViewer} — global yönetici ya da
 *       AUDIT) VE matris izni {@code executive_summary.view} (varsayılan ADMIN + AUDIT). Özet TÜM takımların sayılarını
 *       taşır; takım kapsamlı kullanıcılar (kapsamlı müdür dahil) için ayrı/süzülmüş bir sürüm YOK — sayılar e-postadaki
 *       raporla aynı kalsın ve diğer takımların verisi takım kapsamlı kullanıcıya açılmasın diye bilinçli karar.</li>
 *   <li><b>Ayarlar, test, elle gönderim</b>: yalnız GLOBAL yönetici (ayar anahtarları ayrıca {@code GLOBAL_ONLY}).</li>
 * </ul>
 * Kullanıcı kimliği taşınmaz (yanıtta kişi alanı yok; alıcı önizlemesi yalnız adres — global yöneticiye).
 */
@RestController
@RequestMapping("/api/executive-summary")
@RequiredArgsConstructor
public class ExecutiveSummaryController {

    static final String RESOURCE = "executive_summary.view";

    private final ExecutiveSummaryService service;
    private final ExecutiveSummaryDeliveryService delivery;
    private final ExecutiveSummarySettings settings;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** Özet: {@code ?month=YYYY-MM} (boş = son tamamlanan ay), {@code live=1} kaydı değil canlı hesabı, {@code fresh=1} belleği atlar. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(value = "month", required = false) String month,
                                                       @RequestParam(value = "live", required = false) String live,
                                                       @RequestParam(value = "fresh", required = false) String fresh,
                                                       HttpSession session) {
        requireRead(session);
        YearMonth m = service.parseMonth(month);
        ExecutiveSummary s = service.get(m, isOn(live), isOn(fresh));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", s);
        out.put("months", service.months());
        out.put("default_month", service.defaultMonth().toString());
        out.put("can_configure", SessionScope.isGlobalAdmin(session));
        return ResponseEntity.ok(out);
    }

    /** PDF (e-posta ekinin aynısı). Üretilemezse 503 {@code PDF_GENERATION_FAILED}. Denetim {@code EXECUTIVE_SUMMARY_EXPORT}. */
    @GetMapping("/pdf")
    public ResponseEntity<?> pdf(@RequestParam(value = "month", required = false) String month,
                                 @RequestParam(value = "live", required = false) String live,
                                 HttpSession session) {
        requireRead(session);
        YearMonth m = service.parseMonth(month);
        ExecutiveSummary s = service.get(m, isOn(live), false);
        byte[] bytes = ExecutiveSummaryPdfWriter.render(s);
        if (bytes.length == 0) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "PDF_GENERATION_FAILED");
            err.put("error", Msg.t("PDF üretilemedi. Özet ekranda görüntülenebilir; birkaç dakika sonra tekrar deneyin, sorun sürerse sistem yöneticisine bildirin.",
                    "The PDF could not be generated. The summary is still shown on screen; try again in a few minutes and tell a system administrator if it keeps happening."));
            return ResponseEntity.status(503).body(err);
        }
        auditService.recordAction("EXECUTIVE_SUMMARY_EXPORT", session, "REPORT", "executive-summary",
                AuditDetail.of("month", m.toString(), "source", s.source()), null);
        String name = ExecutiveSummaryPdfWriter.fileName(m.toString());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(bytes);
    }

    /** Ayarlar + alıcı önizlemesi + sonraki çalışmalar + gönderim geçmişi — yalnız global yönetici. */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpSession session) {
        requireGlobalAdmin(session);
        return ok(delivery.status());
    }

    /** Ayarları kaydeder (alan doğrulaması 400 + {@code field}); denetim {@code EXECUTIVE_SUMMARY_SETTINGS}. */
    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody(required = false) Map<String, Object> body,
                                                            HttpSession session) {
        requireGlobalAdmin(session);
        Set<String> changed = settings.save(body, actor(session));
        auditService.recordAction("EXECUTIVE_SUMMARY_SETTINGS", session, "REPORT", "executive-summary",
                AuditDetail.of("keys", String.join(",", changed), "enabled", settings.enabled()), null);
        return ok(delivery.status());
    }

    /**
     * Test postası — YALNIZ isteyen global yöneticinin KENDİ adresine (gövdedeki adres yok sayılır; başkasına gönderme
     * yolu yok). Yönetici başına 10 dakikada {@value ExecutiveSummaryDeliveryService#TEST_LIMIT} (429). Ay kaydı yazılmaz.
     */
    @PostMapping("/send-test")
    public ResponseEntity<Map<String, Object>> sendTest(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session) {
        requireGlobalAdmin(session);
        String username = actor(session);
        YearMonth m = service.parseMonth(body == null || body.get("month") == null ? null : String.valueOf(body.get("month")));
        String email = delivery.emailOf(username);
        if (email == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "NO_EMAIL");
            err.put("error", Msg.t("Hesabınızda e-posta adresi yok; test e-postası yalnız sizin adresinize gönderilebilir. Profilinize bir e-posta adresi ekleyip tekrar deneyin.",
                    "Your account has no e-mail address; the test e-mail can only be sent to you. Add an e-mail address to your profile and try again."));
            return ResponseEntity.badRequest().body(err);
        }
        if (!delivery.allowTest(username)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "RATE_LIMITED");
            err.put("retry_after", ExecutiveSummaryDeliveryService.TEST_WINDOW_MS / 1000);
            err.put("error", Msg.t("10 dakikada en fazla " + ExecutiveSummaryDeliveryService.TEST_LIMIT + " test e-postası gönderilebilir. Birkaç dakika sonra tekrar deneyin.",
                    "At most " + ExecutiveSummaryDeliveryService.TEST_LIMIT + " test e-mails can be sent in 10 minutes. Try again in a few minutes."));
            return ResponseEntity.status(429).body(err);
        }
        ExecutiveSummaryDeliveryService.TestResult r = delivery.sendTest(m, email);
        auditService.recordAction("EXECUTIVE_SUMMARY_TEST", session, "REPORT", "executive-summary",
                AuditDetail.of("month", m.toString(), "result", r.status()), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", r.ok());
        out.put("code", r.ok() ? "OK" : "SEND_FAILED");
        out.put("data", Map.of("status", r.status() == null ? "" : r.status(), "email", email, "month", m.toString()));
        out.put(r.ok() ? "message" : "error", r.ok()
                ? Msg.t("Test e-postası " + email + " adresine gönderildi.", "Test e-mail sent to " + email + ".")
                : Msg.t("Test e-postası gönderilemedi (" + r.status() + "). SMTP ayarlarını ve e-posta kanalının açık olduğunu kontrol edin.",
                        "The test e-mail could not be sent (" + r.status() + "). Check the SMTP settings and that the e-mail channel is on."));
        return ResponseEntity.ok(out);
    }

    /**
     * Elle gönderim ("Şimdi gönder"): seçilen ayın özetini tanımlı alıcılara gönderir — kayıt durumundan bağımsız (yeniden
     * gönderim dahil), yalnız süren bir gönderim engeller. Denetim {@code EXECUTIVE_SUMMARY_RUN}.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpSession session) {
        requireGlobalAdmin(session);
        YearMonth m = service.parseMonth(body == null || body.get("month") == null ? null : String.valueOf(body.get("month")));
        ExecutiveSummaryDeliveryService.Result r = delivery.sendNow(m, actor(session));
        auditService.recordAction("EXECUTIVE_SUMMARY_RUN", session, "REPORT", "executive-summary",
                AuditDetail.of("month", m.toString(), "status", r.status(), "recipients", r.recipients(), "chunks", r.chunks()), null);
        boolean ok = "SENT".equals(r.status()) || "PARTIAL".equals(r.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("code", r.status());
        out.put("data", r);
        out.put(ok ? "message" : "error", ok
                ? Msg.t(m + " özeti " + r.recipients() + " alıcıya gönderildi (" + r.detail() + ").",
                        "The " + m + " summary was sent to " + r.recipients() + " recipients (" + r.detail() + ").")
                : runError(r));
        return ResponseEntity.ok(out);
    }

    private static String runError(ExecutiveSummaryDeliveryService.Result r) {
        return switch (r.status()) {
            case "NO_RECIPIENT" -> Msg.t("Gönderilmedi: alıcı yok. Alıcı adresi ekleyin ya da global yöneticileri dahil edin.",
                    "Not sent: there are no recipients. Add a recipient address or include the global administrators.");
            case "IN_PROGRESS" -> Msg.t("Bu ayın özeti şu anda gönderiliyor. Birkaç dakika sonra geçmişi yenileyin.",
                    "This month's summary is being sent right now. Refresh the history in a few minutes.");
            case "SKIPPED_MAIL_OFF" -> Msg.t("Gönderilmedi: e-posta kanalı kapalı (SMTP ayarlarında). Kanalı açıp tekrar deneyin.",
                    "Not sent: the e-mail channel is off (SMTP settings). Turn it on and try again.");
            default -> Msg.t("Gönderim başarısız (" + r.status() + "). SMTP ayarlarını kontrol edip tekrar deneyin; ayrıntı gönderim geçmişinde.",
                    "Sending failed (" + r.status() + "). Check the SMTP settings and try again; details are in the delivery history.");
        };
    }

    // ── Kapılar ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private void requireRead(HttpSession session) {
        if (!SessionScope.isGlobalViewer(session)) {
            throw new SecurityException(Msg.t(
                    "Yönetici özeti kurum geneli bir rapordur; yalnız global yöneticiler ve denetçiler (AUDIT) açabilir.",
                    "The executive summary is an organisation-wide report; only global administrators and auditors (AUDIT) can open it."));
        }
        permissionService.require(session, RESOURCE, "view");
    }

    private void requireGlobalAdmin(HttpSession session) {
        requireRead(session);
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Yönetici özeti ayarlarını yalnız global yöneticiler değiştirebilir ve gönderebilir.",
                    "Only global administrators can change and send the executive summary."));
        }
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "admin" : String.valueOf(u);
    }

    private static boolean isOn(String v) {
        return v != null && ("1".equals(v) || "true".equalsIgnoreCase(v));
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        return ResponseEntity.ok(out);
    }
}
