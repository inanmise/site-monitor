package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.techerror.TechErrorApprovalService;
import com.sitemonitor.service.techerror.TechErrorApprovalService.TokenStatus;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ConcurrentHashMap;

/**
 * PO onayı e-posta bağlantısı (2026-10-17, S6) — girişsiz, haftalık rapor onay bağlantısıyla aynı desen: sunucu tarafı
 * HTML sayfa, betik yok (CSP), POST formları. Belirteç = yetki (onaylayıcıya özel, 14 gün, tek kullanımlık, özetli).
 * Yollar {@code AuthInterceptor.PUBLIC} ve {@code OriginCheckFilter.EXEMPT_PATHS} listesindedir. Geçersiz / reddedilen
 * bağlantı güvenlik olayı yazar ({@code TECH_ERROR_APPROVAL_LINK_DENIED}) — zararsız "onay akışı kaldırıldı" hariç
 * ({@link #NEUTRAL_REASONS}: nötr bilgi kaydı {@code TECH_ERROR_APPROVAL_LINK_NOT_NEEDED} + nötr sayfa); karar {@code TECH_ERROR_REPORT_APPROVE |
 * REJECT} ({@code via=email_token}). IP başına dakikada {@link #PER_MINUTE} istek (belirteç 256 bit rastgele — tahmin
 * edilemez; sınır yalnız gürültüye karşı).
 */
@Slf4j
@RestController
@RequestMapping("/api/tech-error-reports/approve-link")
public class TechErrorApprovalLinkController {

    static final int PER_MINUTE = 30;
    private static final int MAX_IPS = 10_000;

    private final TechErrorApprovalService approvals;
    private final TeamRepository teams;
    private final AuditService auditService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.SystemMaintenanceService systemMaintenance;

    private final ConcurrentHashMap<String, long[]> hits = new ConcurrentHashMap<>();

    public TechErrorApprovalLinkController(TechErrorApprovalService approvals, TeamRepository teams, AuditService auditService) {
        this.approvals = approvals;
        this.teams = teams;
        this.auditService = auditService;
    }

    /** Test kancası. */
    void setSystemMaintenance(com.sitemonitor.service.SystemMaintenanceService s) { this.systemMaintenance = s; }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@RequestParam(required = false) String token, HttpServletRequest request) {
        if (limited(request)) return tooMany();
        if (maintenanceActive()) return maintenancePage(request, "link opened");
        TokenStatus st = approvals.status(token);
        if (!st.valid()) return denied(st, request, "link opened");
        TechErrorReport r = st.report();
        TechErrorChannel ch = st.channel();
        Team team = teams.findById(r.getTeamId()).orElse(null);
        TechErrorTemplate.Template t = TechErrorTemplate.fromStored(r.getTemplateJson());
        String title = TechErrorTemplate.resolve(t.title(), ch.getName(), r.getReportYear(), r.getReportMonth(), t.lang());
        String period = TechErrorTemplate.monthName(r.getReportMonth(), t.lang()) + " " + r.getReportYear();
        String requester = r.getApprovalRequestedById() == null ? null : approvals.displayNameOf(r.getApprovalRequestedById());
        String body = "<div class='wk'>" + esc(title) + "</div>"
                + "<p class='msg'>" + esc(Msg.t("Takım", "Team")) + ": <b>" + esc(team == null ? "—" : team.getName()) + "</b> · "
                + esc(Msg.t("Kanal", "Channel")) + ": <b>" + esc(ch.getName()) + "</b> · " + esc(Msg.t("Dönem", "Period")) + ": <b>" + esc(period) + "</b></p>"
                + "<p class='msg'>" + esc(Msg.t("İsteyen", "Requested by")) + ": <b>" + esc(requester == null ? "—" : requester) + "</b> · "
                + esc(Msg.t("Onaylayıcı", "Approver")) + ": <b>" + esc(name(st.user())) + "</b></p>"
                + "<p class='msg'>" + esc(st.stale()
                        ? Msg.t("Rapor onay isteğinden sonra değişti; bu bağlantıyla ONAYLANAMAZ (ekip yeniden onaya göndermeli). Yine de reddedebilirsiniz: ekip notunuzu alır ve rapor taslağa döner.",
                                "The report changed after the approval request, so it CAN'T be approved with this link (the team must submit it again). You can still reject it: the team gets your note and the report goes back to draft.")
                        : Msg.t("Onaylarsanız rapor kanalın yönetici (executive) alıcılarına hemen gönderilir. Reddederseniz ekip notunuzu alır ve rapor taslağa döner.",
                                "If you approve, the report is sent to the channel's executive recipients at once. If you reject, the team gets your note and the report goes back to draft.")) + "</p>"
                + (st.stale() ? "" : "<form method='post' action='/api/tech-error-reports/approve-link/confirm'>"
                + "<input type='hidden' name='token' value='" + esc(token) + "'>"
                + "<label class='alt'>" + esc(Msg.t("Not (isteğe bağlı)", "Note (optional)")) + "</label>"
                + "<textarea class='ta' name='note' rows='2' maxlength='1000'></textarea>"
                + "<button class='btn' type='submit'>" + esc(Msg.t("Onayla ve yöneticilere gönder", "Approve and send to executives")) + "</button></form>"
                + "<div class='sep'>" + esc(Msg.t("YA DA", "OR")) + "</div>")
                + "<form method='post' action='/api/tech-error-reports/approve-link/reject'>"
                + "<input type='hidden' name='token' value='" + esc(token) + "'>"
                + "<label class='alt'>" + esc(Msg.t("Red gerekçesi (zorunlu)", "Reason for rejecting (required)")) + "</label>"
                + "<textarea class='ta' name='note' rows='3' maxlength='1000' required></textarea>"
                + "<button class='btn2' type='submit'>" + esc(Msg.t("Reddet ve taslağa döndür", "Reject and return to draft")) + "</button></form>";
        return htmlPage(st.stale() ? Msg.t("Rapor değişti — yalnız reddedilebilir", "The report changed — reject only")
                : Msg.t("Onayınız bekleniyor", "Your approval is needed"), body, st.stale() ? "#d97706" : "#2563eb");
    }

    @PostMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(@RequestParam String token, @RequestParam(required = false) String note,
                                          HttpServletRequest request) {
        return decide(token, note, true, request);
    }

    @PostMapping(value = "/reject", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> reject(@RequestParam String token, @RequestParam(required = false) String note,
                                         HttpServletRequest request) {
        return decide(token, note, false, request);
    }

    private ResponseEntity<String> decide(String token, String note, boolean approve, HttpServletRequest request) {
        if (limited(request)) return tooMany();
        String action = approve ? "approve" : "reject";
        if (maintenanceActive()) return maintenancePage(request, action);
        TokenStatus st = approvals.status(token);
        if (!st.valid()) return denied(st, request, action);
        if (approve && st.stale()) return denied(new TokenStatus(false, "stale", st.token(), st.report(), st.channel(), st.user()), request, action);
        if (!approve && (note == null || note.isBlank())) {
            return htmlPage(Msg.t("Gerekçe gerekli", "A reason is required"), "<p class='msg'>" + esc(Msg.t(
                    "Reddetmek için gerekçe yazın; bağlantıya geri dönüp notu doldurun.",
                    "Write a reason to reject; go back to the link and fill in the note.")) + "</p>", "#d97706");
        }
        Team team = teams.findById(st.report().getTeamId()).orElse(null);
        if (team == null) return denied(new TokenStatus(false, "not_found", st.token(), st.report(), st.channel(), st.user()), request, action);
        TechErrorApprovalService.Decision d;
        try {
            d = approvals.decideViaToken(token, team, approve, note);
        } catch (com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException e) {
            // Olağan yarış (başka PO önce karar verdi / istek eskidi): güvenlik olayı değil, sonucu olan bir işlem
            AppUser u0 = st.user();
            auditService.recordAction(approve ? "TECH_ERROR_REPORT_APPROVE" : "TECH_ERROR_REPORT_REJECT", u0.getUsername(), u0.getId(),
                    u0.getTeamId(), u0.getSystemRole(), "TECH_ERROR_REPORT", String.valueOf(st.report().getId()),
                    AuditDetail.of("via", "email_token", "outcome", "FAILED", "reason", e.getCode()),
                    request.getRemoteAddr(), request.getHeader("User-Agent"), null);
            return htmlPage(Msg.t("Karar kaydedilemedi", "The decision wasn't recorded"), "<p class='msg'>" + esc(e.getMessage()) + "</p>", "#d97706");
        } catch (IllegalArgumentException e) {
            // Not kuralı (ör. 1000 karakterden uzun): bağlantı tüketilmedi, geri dönüp düzeltilebilir
            return htmlPage(Msg.t("Karar kaydedilemedi", "The decision wasn't recorded"), "<p class='msg'>" + esc(e.getMessage()) + "</p>", "#d97706");
        } catch (IllegalStateException e) {
            return denied(new TokenStatus(false, e.getMessage(), st.token(), st.report(), st.channel(), st.user()), request, action);
        } catch (RuntimeException e) {
            log.warn("Onay bağlantısı kararı yazılamadı (rapor {}): {}", st.report().getId(), e.toString());
            return htmlPage(Msg.t("Beklenmeyen hata", "Unexpected error"), "<p class='msg'>" + esc(Msg.t(
                    "Karar kaydedilemedi. Biraz sonra bağlantıyı yeniden açın; sorun sürerse raporu Site Monitor'de açıp oradan karar verin.",
                    "The decision couldn't be recorded. Open the link again in a moment; if it keeps failing, open the report in Site Monitor and decide there.")) + "</p>", "#dc2626");
        }
        AppUser u = st.user();
        auditService.recordAction(approve ? "TECH_ERROR_REPORT_APPROVE" : "TECH_ERROR_REPORT_REJECT", u.getUsername(), u.getId(),
                u.getTeamId(), u.getSystemRole(), "TECH_ERROR_REPORT", String.valueOf(st.report().getId()),
                AuditDetail.of("via", "email_token", "channel", st.channel().getName(), "send_state",
                        d.send() == null ? d.sendError() : d.send().state()),
                request.getRemoteAddr(), request.getHeader("User-Agent"), null);
        String body;
        if (approve) {
            boolean ok = d.send() != null && ("SENT".equals(d.send().state()) || "QUEUED".equals(d.send().state()));
            body = "<p class='msg'>" + esc(ok
                    ? Msg.t("Rapor onaylandı ve yönetici alıcılara gönderildi.", "The report was approved and sent to the executive recipients.")
                    : Msg.t("Rapor onaylandı; ancak yönetici alıcılara posta gönderilemedi. Ekip raporu Site Monitor'den yeniden gönderebilir.",
                            "The report was approved, but the mail to the executive recipients couldn't be sent. The team can resend it from Site Monitor."))
                    + "</p>";
            return htmlPage(Msg.t("Onaylandı", "Approved"), body, ok ? "#16a34a" : "#d97706");
        }
        body = "<p class='msg'>" + esc(Msg.t("Rapor reddedildi ve taslağa döndü; ekip notunuzu e-postayla aldı.",
                "The report was rejected and is back in draft; the team received your note by e-mail.")) + "</p>";
        return htmlPage(Msg.t("Reddedildi", "Rejected"), body, "#dc2626");
    }

    private ResponseEntity<String> denied(TokenStatus st, HttpServletRequest request, String action) {
        String reason = st.reason() == null ? "not_found" : st.reason();
        if (NEUTRAL_REASONS.contains(reason)) return neutral(st, reason, request, action);
        auditService.recordSecurityEvent("TECH_ERROR_APPROVAL_LINK_DENIED", request, null, "TECH_ERROR_REPORT",
                st.report() == null ? "email-token" : String.valueOf(st.report().getId()), reason + " (" + action + ")");
        String title;
        String msg;
        switch (reason) {
            case "used" -> { title = Msg.t("Bağlantı kullanıldı", "Link already used"); msg = Msg.t(
                    "Bu bağlantı daha önce kullanıldı ya da rapor için karar verildi. Güncel durumu Site Monitor'de görebilirsiniz.",
                    "This link was already used or the report has been decided. You can see the current state in Site Monitor."); }
            case "expired" -> { title = Msg.t("Bağlantının süresi doldu", "Link expired"); msg = Msg.t(
                    "Bu bağlantının süresi doldu. Ekip raporu yeniden onaya gönderirse yeni bir bağlantı alırsınız; şimdi Site Monitor'de karar verebilirsiniz.",
                    "This link has expired. You'll get a new one if the team submits the report again; you can decide in Site Monitor now."); }
            case "not_pending" -> { title = Msg.t("Bekleyen istek yok", "Nothing pending"); msg = Msg.t(
                    "Bu rapor için bekleyen bir onay isteği yok: karar verilmiş ya da yeni bir istek gönderilmiş olabilir.",
                    "There is no pending approval request for this report: it may have been decided, or a newer request was sent."); }
            case "stale" -> { title = Msg.t("Rapor değişti", "The report changed"); msg = Msg.t(
                    "Rapor onay isteğinden sonra değişti; bu bağlantıyla onaylanamaz. Ekip raporu yeniden onaya göndermeli.",
                    "The report changed after the approval request, so it can't be approved with this link. The team must submit it again."); }
            case "approver_inactive" -> { title = Msg.t("Onay yetkiniz yok", "You can't approve this"); msg = Msg.t(
                    "Hesabınız pasif ya da artık bu takımın PO rolündeki bir üyesi değilsiniz; bu bağlantı kullanılamaz.",
                    "Your account is inactive or you are no longer a PO member of this team, so this link can't be used."); }
            // Kullanıcı adı ön eki (2026-10-21 inceleme): giriş kuralı bu hesabı kapatıyor — belirteç tüketilmez; kural
            // kapatılırsa bağlantı süresi içinde yine çalışır
            case "username_prefix" -> { title = Msg.t("Bu hesapla karar verilemez", "This account can't decide"); msg = Msg.t(
                    "Bu hesabın kullanıcı adıyla Site Monitor'e giriş şu an kapalı (yalnız izinli ön ekle başlayan hesaplar girebilir), bu yüzden bu bağlantıyla karar verilemez. Girişe açık hesabınızın PO rolü varsa kararı Site Monitor'de verin ya da yöneticinize başvurun.",
                    "Signing in to Site Monitor with this account's username is currently turned off (only accounts that start with an allowed prefix can sign in), so this link can't be used to decide. If your account that can sign in has the PO role, decide in Site Monitor, or contact your administrator."); }
            // A1 (inceleme 2026-10-17): uygulama içi uçla aynı kapılar (okuma + onay izni, modül görünürlüğü); belirteç
            // tüketilmez, karar yazılmaz — metin üç durumda da doğru
            case "no_permission" -> { title = Msg.t("Onay yetkiniz yok", "You can't approve this"); msg = Msg.t(
                    "Bu rapor için onay yetkiniz yok: hesabınızın rolü için teknik hata raporu okuma ya da onay izni kapalı, ya da modül takımınıza kapatılmış. Yöneticinize başvurun.",
                    "You can't approve this report: the technical error report read or approval permission is switched off for your account's role, or the module is turned off for your team. Contact your administrator."); }
            // A2: yeni istek eski bağlantının yerine geçti — "geçersiz" değil, "en son postayı kullanın"; yeni isteğin postası
            // ulaşmamış olabilir (inceleme 2026-10-17) → uygulama içi yol da söylenir
            case "superseded" -> { title = Msg.t("Daha yeni bir istek var", "A newer request exists"); msg = Msg.t(
                    "Bu rapor için daha yeni bir onay isteği gönderildi; lütfen en son e-postadaki bağlantıyı kullanın. Postayı bulamıyorsanız raporu Site Monitor'de açıp kararınızı oradan verebilirsiniz.",
                    "A newer approval request was sent for this report; please use the link in the latest e-mail. If you can't find that e-mail, open the report in Site Monitor and make your decision there."); }
            default -> { title = Msg.t("Bağlantı geçersiz", "Invalid link"); msg = Msg.t(
                    "Bu onay bağlantısı geçersiz. Postadaki bağlantının tamamını kopyaladığınızdan emin olun ya da raporu Site Monitor'de açın.",
                    "This approval link is invalid. Make sure you copied the whole link from the e-mail, or open the report in Site Monitor."); }
        }
        return htmlPage(title, "<p class='msg'>" + esc(msg) + "</p>", "#dc2626");
    }

    /**
     * Güvenlik olayı OLMAYAN sebepler (inceleme 2026-10-18): kanalın onay akışı kaldırıldı ({@code flow_removed}) — bağlantı
     * geçerli, etkin bir onaylayıcıya ait ve zararsız; reddedilmiş bağlantı gibi kırmızı sayfa + {@code *_LINK_DENIED} güvenlik
     * olayı yanıltıcıydı. Nötr bilgi kaydı ({@code TECH_ERROR_APPROVAL_LINK_NOT_NEEDED}, sonuç SUCCESS) + nötr tonlu sayfa.
     */
    static final java.util.Set<String> NEUTRAL_REASONS = java.util.Set.of("flow_removed");
    /** Nötr sayfa vurgusu (gri-mavi: hata / uyarı tonu değil). */
    static final String NEUTRAL_ACCENT = "#64748b";

    private ResponseEntity<String> neutral(TokenStatus st, String reason, HttpServletRequest request, String action) {
        auditService.recordTokenAction("TECH_ERROR_APPROVAL_LINK_NOT_NEEDED", request, "email-approval",
                st.report() == null ? null : st.report().getTeamId(), "TECH_ERROR_REPORT",
                st.report() == null ? "email-token" : String.valueOf(st.report().getId()),
                AuditDetail.of("via", "email_token", "reason", reason, "action", action,
                        "approver", st.user() == null ? null : name(st.user())));
        // Kanalın yönetici alıcıları kaldırıldı: onay akışı yok, karar yazılmaz, belirteç tüketilmez
        String title = Msg.t("Onay gerekmiyor", "No approval needed");
        String msg = Msg.t(
                "Bu kanalın yönetici (executive) alıcıları kaldırıldı; raporun onaya ihtiyacı kalmadı ve ekip onu takım adresine doğrudan gönderebilir. Sizin bir şey yapmanız gerekmiyor. Alıcılar yeniden eklenirse bu bağlantı süresi içinde yine çalışır.",
                "This channel's executive recipients were removed, so the report no longer needs approval and the team can send it straight to the team address. You don't need to do anything. If recipients are added again, this link works again until it expires.");
        return htmlPage(title, "<p class='msg'>" + esc(msg) + "</p>", NEUTRAL_ACCENT);
    }

    // ── Bakım, sınır, sayfa ──────────────────────────────────────────────────────────────────────────

    private boolean maintenanceActive() {
        try {
            return systemMaintenance != null && systemMaintenance.isActive();
        } catch (Exception e) {
            return false;
        }
    }

    private ResponseEntity<String> maintenancePage(HttpServletRequest request, String action) {
        auditService.recordSecurityEvent("TECH_ERROR_APPROVAL_LINK_DENIED", request, null, "TECH_ERROR_REPORT", "email-token",
                "system_maintenance (" + action + ")");
        String msg;
        try {
            msg = systemMaintenance.signalBody().get("error").toString();
        } catch (Exception e) {
            msg = Msg.t("Sistem bakımda.", "The system is under maintenance.");
        }
        return htmlPage(Msg.t("Sistem Bakımda", "System Maintenance"),
                "<p class='msg'>" + esc(msg) + "</p><p class='msg'>" + esc(Msg.t(
                        "Rapor durumu değişmedi. Bakım bittikten sonra aynı bağlantıyı yeniden açabilirsiniz.",
                        "The report has not changed. You can open the same link again once maintenance is over.")) + "</p>",
                "#d97706");
    }

    /** IP başına dakikalık sabit pencere; harita sınırı aşılınca temizlenir. */
    boolean limited(HttpServletRequest request) {
        String ip = request == null || request.getRemoteAddr() == null ? "?" : request.getRemoteAddr();
        long minute = System.currentTimeMillis() / 60_000L;
        if (hits.size() > MAX_IPS) hits.clear();
        long[] slot = hits.compute(ip, (k, v) -> {
            if (v == null || v[0] != minute) return new long[] { minute, 1 };
            v[1]++;
            return v;
        });
        return slot[1] > PER_MINUTE;
    }

    private ResponseEntity<String> tooMany() {
        return ResponseEntity.status(429).contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(htmlPage(Msg.t("Çok fazla istek", "Too many requests"), "<p class='msg'>" + esc(Msg.t(
                        "Kısa sürede çok istek geldi. Bir dakika sonra bağlantıyı yeniden açın.",
                        "Too many requests in a short time. Open the link again in a minute.")) + "</p>", "#d97706").getBody());
    }

    private static String name(AppUser u) {
        if (u == null) return "—";
        return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
    }

    static ResponseEntity<String> htmlPage(String title, String bodyHtml, String accent) {
        String html = "<!DOCTYPE html><html lang='" + Msg.t("tr", "en") + "'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>" + esc(title) + " — Site Monitor</title>"
            + "<meta name='description' content='" + Msg.t("Site Monitor teknik hata raporu onayı: e-postadaki bağlantıyla raporu"
            + " giriş yapmadan onaylayın ya da gerekçesiyle reddedin.",
            "Site Monitor technical error report approval: approve the report from the e-mail link without signing in, or reject it with a reason.") + "'>"
            + "<link rel='icon' href='/favicon.ico' sizes='32x32'><link rel='icon' href='/favicon.svg' type='image/svg+xml'>"
            + "<style>"
            + "body{margin:0;background:#eef1f4;font-family:'Segoe UI',-apple-system,Arial,sans-serif;color:#1F2937;-webkit-font-smoothing:antialiased}"
            + ".card{max-width:520px;margin:40px auto;background:#fff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 10px 40px rgba(15,27,45,.14);border:1px solid #E5E8EC}"
            + "@media (max-width:600px){.card{margin:12px 8px}}"
            + ".hd{background:#0f172a;color:#fff;padding:22px 28px}"
            + ".wm{font-size:16px;font-weight:800;letter-spacing:-.01em}"
            + ".kick{font-size:11px;font-weight:700;letter-spacing:.1em;color:#93a4bd;margin-top:14px;text-transform:uppercase}"
            + ".t{font-size:21px;font-weight:800;margin-top:3px;overflow-wrap:anywhere}"
            + ".strip{height:4px;background:" + accent + "}"
            + ".bd{padding:26px 28px}.wk{font-weight:700;font-size:15px;color:#0f172a;margin-bottom:8px;overflow-wrap:anywhere}"
            + ".msg{font-size:14px;line-height:1.7;color:#475569;overflow-wrap:anywhere}"
            + ".btn{display:inline-block;min-height:44px;margin-top:14px;background:" + accent + ";color:#fff;border:none;"
            + "border-radius:8px;padding:12px 26px;font-size:15px;font-weight:800;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.12)}"
            + ".sep{margin:22px 0 4px;text-align:center;font-size:12px;font-weight:700;letter-spacing:.06em;color:#94a3b8}"
            + ".alt{display:block;margin-top:12px;font-size:12px;font-weight:700;color:#64748b}"
            + ".ta{display:block;width:100%;box-sizing:border-box;margin-top:6px;border:1px solid #E5E8EC;border-radius:8px;"
            + "padding:10px 12px;font-size:14px;font-family:inherit;color:#1F2937;resize:vertical}"
            + ".ta:focus{outline:none;border-color:#2563eb}"
            + ".btn2{display:inline-block;min-height:44px;margin-top:12px;background:#fff;color:#b91c1c;border:1px solid #ef4444;"
            + "border-radius:8px;padding:11px 26px;font-size:14px;font-weight:800;cursor:pointer}"
            + ".ft{padding:14px 28px;border-top:1px solid #E5E8EC;font-size:11px;color:#94a3b8;text-align:center}"
            + "</style></head><body><div class='card'>"
            + "<div class='hd'><span class='wm'>Site Monitor</span>"
            + "<div class='kick'>" + esc(Msg.t("Teknik hata raporu onayı", "Technical error report approval")) + "</div><div class='t'>" + esc(title) + "</div></div>"
            + "<div class='strip'></div>"
            + "<div class='bd'>" + bodyHtml + "</div>"
            + "<div class='ft'>Site Monitor</div>"
            + "</div></body></html>";
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .header("Cache-Control", "no-store").body(html);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
