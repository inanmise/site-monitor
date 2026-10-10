package com.sitemonitor.controller;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.report.ExecutiveSummaryPdfWriter;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummaryDeliveryService;
import com.sitemonitor.service.report.executive.ExecutiveSummaryService;
import com.sitemonitor.service.report.executive.ExecutiveSummarySettings;
import com.sitemonitor.service.report.executive.ExecutiveSummaryTeamService;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.text.Collator;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * AYLIK YÖNETİCİ ÖZETİ uçları (2026-10-10) — {@code /api/executive-summary}.
 *
 * <h2>Kapsam ve yetki</h2>
 * <ul>
 *   <li><b>Kurum geneli özet</b> (okuma, PDF): kurum geneli görüntüleyici ({@link SessionScope#isGlobalViewer} — global
 *       yönetici ya da AUDIT) VE matris izni {@code executive_summary.view} (varsayılan ADMIN + AUDIT). Takım kapsamlı
 *       kullanıcılar (kapsamlı müdür dahil) kurum özetini ASLA görmez.</li>
 *   <li><b>Takım özeti</b> ({@code ?team=<id>}, 2026-10-10, kullanıcı isteği): kurum geneli görüntüleyici her takımı;
 *       kapsamlı müdür (rol ADMIN + takım kapsamı) yalnız görüş kapsamındaki takımları görür — yine matris izniyle.
 *       USER / TEAM_ADMIN izin taşısa da göremez (yönetici raporu). Parametresiz istek: kurumu görebilen → kurum; müdür →
 *       kapsamındaki ilk takım (A→Z).</li>
 *   <li><b>Kurum ayarları, kurum testi / elle gönderimi</b>: yalnız GLOBAL yönetici (anahtarlar ayrıca
 *       {@code GLOBAL_ONLY}).</li>
 *   <li><b>Takım ayarları, takım testi / elle gönderimi</b>: global yönetici her takımı; kapsamlı müdür yalnız YÖNETİM
 *       kapsamındaki ({@link SessionScope#canManage}) takımları. Test postası her durumda yalnız isteyenin kendi adresine.</li>
 * </ul>
 * Kişi alanları {@code user_id} anahtarıyla döner ({@code UserRefWire}: global olmayana opak kimlik); gövdedeki kullanıcı
 * kimlikleri {@link UserPublicIds#resolve} ile çözülür.
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

    /** Takım ayarları (bean'siz birim testlerinde null → takım uçları 404, kurum uçları birebir aynı). */
    @Autowired(required = false)
    private ExecutiveSummaryTeamService teamService;

    /** Opak kullanıcı kimliği çözücüsü — bean'siz birim testinde null → sayısal ayrıştırma. */
    @Autowired(required = false)
    private UserPublicIds userPublicIds;

    void setTeamService(ExecutiveSummaryTeamService t) { this.teamService = t; }

    /**
     * Özet: {@code ?month=YYYY-MM} (boş = son tamamlanan ay), {@code team=<id>} (boş = varsayılan kapsam), {@code live=1}
     * kaydı değil canlı hesabı, {@code fresh=1} belleği atlar. Yanıt ayrıca seçilebilir kapsamları ({@code scopes}) taşır.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(value = "month", required = false) String month,
                                                       @RequestParam(value = "team", required = false) String team,
                                                       @RequestParam(value = "live", required = false) String live,
                                                       @RequestParam(value = "fresh", required = false) String fresh,
                                                       HttpSession session) {
        Access a = access(session);
        Long teamId = resolveScope(a, team);
        YearMonth m = service.parseMonth(month);
        // Kurum yolu eski imzayla (davranış birebir aynı); takım yolu kapsamlı imzayla.
        ExecutiveSummary s = teamId == null ? service.get(m, isOn(live), isOn(fresh))
                : service.get(m, teamId, isOn(live), isOn(fresh));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", s);
        out.put("months", teamId == null ? service.months() : service.months(teamId));
        out.put("default_month", service.defaultMonth().toString());
        out.put("can_configure", SessionScope.isGlobalAdmin(session));
        out.put("can_configure_team", teamId != null && canConfigureTeam(session, teamId));
        out.put("can_configure_any_team", SessionScope.isGlobalAdmin(session) || SessionScope.isScopedAdmin(session));
        out.put("scopes", scopes(a, session));
        return ResponseEntity.ok(out);
    }

    /** Eski imza (kurum kapsamı) — birim testleri. */
    public ResponseEntity<Map<String, Object>> summary(String month, String live, String fresh, HttpSession session) {
        return summary(month, null, live, fresh, session);
    }

    /** PDF (e-posta ekinin aynısı). Üretilemezse 503 {@code PDF_GENERATION_FAILED}. Denetim {@code EXECUTIVE_SUMMARY_EXPORT}. */
    @GetMapping("/pdf")
    public ResponseEntity<?> pdf(@RequestParam(value = "month", required = false) String month,
                                 @RequestParam(value = "team", required = false) String team,
                                 @RequestParam(value = "live", required = false) String live,
                                 HttpSession session) {
        Access a = access(session);
        Long teamId = resolveScope(a, team);
        YearMonth m = service.parseMonth(month);
        ExecutiveSummary s = teamId == null ? service.get(m, isOn(live), false) : service.get(m, teamId, isOn(live), false);
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
                teamId == null ? AuditDetail.of("month", m.toString(), "source", s.source())
                        : AuditDetail.of("month", m.toString(), "source", s.source(), "team", teamId), null);
        String name = ExecutiveSummaryPdfWriter.fileName(s);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(bytes);
    }

    /** Eski imza (kurum kapsamı) — birim testleri. */
    public ResponseEntity<?> pdf(String month, String live, HttpSession session) {
        return pdf(month, null, live, session);
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
        return doSendTest(body, session, null);
    }

    /**
     * Elle gönderim ("Şimdi gönder"): seçilen ayın özetini tanımlı alıcılara gönderir — kayıt durumundan bağımsız (yeniden
     * gönderim dahil), yalnız süren bir gönderim engeller. Denetim {@code EXECUTIVE_SUMMARY_RUN}.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body,
                                                   HttpSession session) {
        requireGlobalAdmin(session);
        YearMonth m = service.parseMonth(monthOf(body));
        ExecutiveSummaryDeliveryService.Result r = delivery.sendNow(m, actor(session));
        auditService.recordAction("EXECUTIVE_SUMMARY_RUN", session, "REPORT", "executive-summary",
                AuditDetail.of("month", m.toString(), "status", r.status(), "recipients", r.recipients(), "chunks", r.chunks()), null);
        return runResponse(m, r, false);
    }

    // ── Takım ayarları (2026-10-10) ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Takım listesi: çağıranın yapılandırabildiği takımlar (global yönetici → hepsi; kapsamlı müdür → yönetim kapsamı),
     * A→Z; satır başına açık mı, alıcı sayısı, uyarılar, son tamamlanan ayın gönderim durumu. Ayrıca ortak zamanlama.
     */
    @GetMapping("/teams")
    public ResponseEntity<Map<String, Object>> teams(HttpSession session) {
        requireTeamConfigurer(session);
        Collection<Long> ids = configurableTeamIds(session);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teams", teamService.overview(ids, service.defaultMonth()));
        m.put("report_month", service.defaultMonth().toString());
        m.put("next_runs", delivery.nextRuns());
        m.put("org_enabled", settings.enabled());
        m.put("bcc_chunk", ExecutiveSummaryDeliveryService.BCC_CHUNK);
        return ok(m);
    }

    /** Takımın ayar ekranı (ayar, müdür, yöneten müdürler, seçilebilir üyeler, alıcı önizlemesi, geçmiş). */
    @GetMapping("/teams/{teamId}")
    public ResponseEntity<Map<String, Object>> team(@PathVariable("teamId") String teamId, HttpSession session) {
        Long id = requireConfigurableTeam(teamId, session);
        Map<String, Object> d = teamService.detail(id);
        if (d == null) throw notFound();
        d.put("next_runs", delivery.nextRuns());
        return ok(d);
    }

    /** Takım ayarını kaydeder; denetim {@code EXECUTIVE_SUMMARY_TEAM_SETTINGS}. */
    @PutMapping("/teams/{teamId}")
    public ResponseEntity<Map<String, Object>> saveTeam(@PathVariable("teamId") String teamId,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session) {
        Long id = requireConfigurableTeam(teamId, session);
        List<Long> userIds = null;
        int unresolved = 0;
        if (body != null && body.containsKey("user_ids")) {
            userIds = new ArrayList<>();
            Object raw = body.get("user_ids");
            if (raw != null && !(raw instanceof List<?>)) {
                throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("user_ids", Msg.t(
                        "Seçilen üyeler bir liste olmalı.", "The selected members must be a list."));
            }
            if (raw instanceof List<?> list) {
                if (list.size() > ExecutiveSummaryTeamService.MAX_SELECTED_USERS * 2) {
                    throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("user_ids", Msg.t(
                            "En fazla " + ExecutiveSummaryTeamService.MAX_SELECTED_USERS + " üye seçilebilir.",
                            "You can select at most " + ExecutiveSummaryTeamService.MAX_SELECTED_USERS + " members."));
                }
                for (Object o : list) {
                    Long uid = UserPublicIds.resolve(userPublicIds, o, session);
                    if (uid == null) unresolved++;
                    else userIds.add(uid);
                }
            }
        }
        Set<String> changed = teamService.save(id, body, userIds, unresolved, actor(session));
        ExecutiveSummaryTeamService.Settings now = teamService.settingsOf(id);
        auditService.recordAction("EXECUTIVE_SUMMARY_TEAM_SETTINGS", session, "REPORT", "executive-summary",
                AuditDetail.of("team", id, "fields", String.join(",", changed), "enabled", now.enabled(),
                        "members", now.userIds().size(), "extra", now.extraEmails().size()), null);
        Map<String, Object> d = teamService.detail(id);
        if (d == null) throw notFound();
        d.put("next_runs", delivery.nextRuns());
        return ok(d);
    }

    /** Takım test postası — yalnız isteyenin kendi adresine; sınır kurum testiyle ortak. */
    @PostMapping("/teams/{teamId}/send-test")
    public ResponseEntity<Map<String, Object>> sendTeamTest(@PathVariable("teamId") String teamId,
                                                            @RequestBody(required = false) Map<String, Object> body,
                                                            HttpSession session) {
        Long id = requireConfigurableTeam(teamId, session);
        return doSendTest(body, session, id);
    }

    /** Takımın elle gönderimi; denetim {@code EXECUTIVE_SUMMARY_RUN} ({@code team} ayrıntısıyla). */
    @PostMapping("/teams/{teamId}/run")
    public ResponseEntity<Map<String, Object>> runTeam(@PathVariable("teamId") String teamId,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpSession session) {
        Long id = requireConfigurableTeam(teamId, session);
        YearMonth m = service.parseMonth(monthOf(body));
        ExecutiveSummaryDeliveryService.Result r = delivery.sendTeamNow(id, m, actor(session));
        auditService.recordAction("EXECUTIVE_SUMMARY_RUN", session, "REPORT", "executive-summary",
                AuditDetail.of("month", m.toString(), "team", id, "status", r.status(), "recipients", r.recipients(),
                        "chunks", r.chunks()), null);
        return runResponse(m, r, true);
    }

    // ── Ortak gövdeler ──────────────────────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> doSendTest(Map<String, Object> body, HttpSession session, Long teamId) {
        String username = actor(session);
        YearMonth m = service.parseMonth(monthOf(body));
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
        ExecutiveSummaryDeliveryService.TestResult r = teamId == null
                ? delivery.sendTest(m, email) : delivery.sendTeamTest(teamId, m, email);
        auditService.recordAction("EXECUTIVE_SUMMARY_TEST", session, "REPORT", "executive-summary",
                teamId == null ? AuditDetail.of("month", m.toString(), "result", r.status())
                        : AuditDetail.of("month", m.toString(), "team", teamId, "result", r.status()), null);
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

    private static ResponseEntity<Map<String, Object>> runResponse(YearMonth m, ExecutiveSummaryDeliveryService.Result r,
                                                                   boolean team) {
        boolean ok = "SENT".equals(r.status()) || "PARTIAL".equals(r.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("code", r.status());
        out.put("data", r);
        out.put(ok ? "message" : "error", ok
                ? Msg.t(m + " özeti " + r.recipients() + " alıcıya gönderildi (" + r.detail() + ").",
                        "The " + m + " summary was sent to " + r.recipients() + " recipients (" + r.detail() + ").")
                : runError(r, team));
        return ResponseEntity.ok(out);
    }

    private static String runError(ExecutiveSummaryDeliveryService.Result r, boolean team) {
        return switch (r.status()) {
            case "NO_RECIPIENT" -> team
                    ? Msg.t("Gönderilmedi: alıcı yok. Takım müdürünü ya da takımı yöneten müdürleri dahil edin, üye seçin veya ek adres yazın.",
                            "Not sent: there are no recipients. Include the team manager or the managers who run the team, pick members or add an address.")
                    : Msg.t("Gönderilmedi: alıcı yok. Alıcı adresi ekleyin ya da global yöneticileri dahil edin.",
                            "Not sent: there are no recipients. Add a recipient address or include the global administrators.");
            case "IN_PROGRESS" -> Msg.t("Bu ayın özeti şu anda gönderiliyor. Birkaç dakika sonra geçmişi yenileyin.",
                    "This month's summary is being sent right now. Refresh the history in a few minutes.");
            case "SKIPPED_MAIL_OFF" -> Msg.t("Gönderilmedi: e-posta kanalı kapalı (SMTP ayarlarında). Kanalı açıp tekrar deneyin.",
                    "Not sent: the e-mail channel is off (SMTP settings). Turn it on and try again.");
            case "TEAM_INACTIVE" -> Msg.t("Gönderilmedi: takım pasif. Takımı Takım Yönetimi'nden etkinleştirip tekrar deneyin.",
                    "Not sent: the team is inactive. Activate the team in Team Management and try again.");
            default -> Msg.t("Gönderim başarısız (" + r.status() + "). SMTP ayarlarını kontrol edip tekrar deneyin; ayrıntı gönderim geçmişinde.",
                    "Sending failed (" + r.status() + "). Check the SMTP settings and try again; details are in the delivery history.");
        };
    }

    // ── Kapılar ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Oturumun özet erişimi: kurumu görebilir mi + görebildiği takımlar (A→Z; kurum görüntüleyici → hepsi). */
    record Access(boolean org, List<Long> teams, Map<Long, String> names) { }

    private Access access(HttpSession session) {
        boolean globalViewer = SessionScope.isGlobalViewer(session);
        boolean scoped = SessionScope.isScopedAdmin(session);
        if (!globalViewer && !scoped) throw denied();
        permissionService.require(session, RESOURCE, "view");
        Map<Long, String> names = teamService == null ? Map.of() : service.teamNames();
        List<Long> teams = new ArrayList<>();
        if (teamService != null) {
            List<Long> scope = globalViewer ? null : SessionScope.viewTeamIds(session);
            for (Long id : names.keySet()) if (scope == null || scope.contains(id)) teams.add(id);
            Collator c = Collator.getInstance(Locale.forLanguageTag("tr"));
            c.setStrength(Collator.PRIMARY);
            teams.sort(Comparator.comparing((Long id) -> names.get(id), Comparator.nullsLast(c)));
        }
        if (!globalViewer && teams.isEmpty()) throw denied();
        return new Access(globalViewer, teams, names);
    }

    /** {@code team} parametresi → kapsam (null = kurum). Görülemeyen takım 403, bilinmeyen 404. */
    private Long resolveScope(Access a, String team) {
        if (team == null || team.isBlank()) return a.org() ? null : a.teams().get(0);
        if ("org".equalsIgnoreCase(team.trim())) {
            if (!a.org()) throw denied();
            return null;
        }
        Long id = parseTeamId(team);
        if (!a.names().containsKey(id)) throw notFound();
        if (!a.teams().contains(id)) throw denied();
        return id;
    }

    /** Seçilebilir kapsamlar: kurum (görebiliyorsa) + takımlar (A→Z, yapılandırabilir mi). */
    private Map<String, Object> scopes(Access a, HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("org", a.org());
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Long id : a.teams()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", id);
            t.put("name", a.names().get(id));
            t.put("can_configure", canConfigureTeam(session, id));
            teams.add(t);
        }
        m.put("teams", teams);
        return m;
    }

    /** Takım ayarı yazabilir mi: global yönetici her takım; kapsamlı müdür yönetim kapsamındaki takım. */
    static boolean canConfigureTeam(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalAdmin(session)) return true;
        return SessionScope.isScopedAdmin(session) && SessionScope.canManage(session, teamId);
    }

    private void requireTeamConfigurer(HttpSession session) {
        if (teamService == null) throw notFound();
        if (!SessionScope.isGlobalAdmin(session) && !SessionScope.isScopedAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Takım yönetici özeti ayarlarını yalnız global yöneticiler ve takımın müdürleri değiştirebilir.",
                    "Only global administrators and the team's managers can change team executive summary settings."));
        }
        permissionService.require(session, RESOURCE, "view");
    }

    private Long requireConfigurableTeam(String raw, HttpSession session) {
        requireTeamConfigurer(session);
        Long id = parseTeamId(raw);
        if (teamService.teamName(id) == null) throw notFound();
        if (!canConfigureTeam(session, id)) {
            throw new SecurityException(Msg.t(
                    "Bu takımın yönetici özeti ayarlarını değiştirme yetkiniz yok. Yalnız yönettiğiniz takımları yapılandırabilirsiniz.",
                    "You cannot change this team's executive summary settings. You can only configure teams you manage."));
        }
        return id;
    }

    private Collection<Long> configurableTeamIds(HttpSession session) {
        if (SessionScope.isGlobalAdmin(session)) return null;           // hepsi
        List<Long> m = SessionScope.manageTeamIds(session);
        return m == null ? List.of() : m;
    }

    private static Long parseTeamId(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.length() > 18 || !s.chars().allMatch(Character::isDigit)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team", Msg.t(
                    "Takım kimliği geçersiz. Listeden bir takım seçin.", "The team id is invalid. Pick a team from the list."));
        }
        return Long.valueOf(s);
    }

    private static SecurityException denied() {
        return new SecurityException(Msg.t(
                "Yönetici özetini yalnız global yöneticiler, denetçiler (AUDIT) ve takım müdürleri (kendi takımları için) açabilir.",
                "Only global administrators, auditors (AUDIT) and team managers (for their own teams) can open the executive summary."));
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t("Takım bulunamadı. Takım silinmiş olabilir; listeyi yenileyin.",
                "The team was not found. It may have been deleted; refresh the list."));
    }

    private void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalViewer(session)) {
            throw new SecurityException(Msg.t(
                    "Yönetici özeti kurum geneli bir rapordur; yalnız global yöneticiler ve denetçiler (AUDIT) açabilir.",
                    "The executive summary is an organisation-wide report; only global administrators and auditors (AUDIT) can open it."));
        }
        permissionService.require(session, RESOURCE, "view");
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Yönetici özeti ayarlarını yalnız global yöneticiler değiştirebilir ve gönderebilir.",
                    "Only global administrators can change and send the executive summary."));
        }
    }

    private static String monthOf(Map<String, Object> body) {
        return body == null || body.get("month") == null ? null : String.valueOf(body.get("month"));
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
