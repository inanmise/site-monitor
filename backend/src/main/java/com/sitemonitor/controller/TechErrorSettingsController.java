package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.techerror.TechErrorApprovalService;
import com.sitemonitor.service.techerror.TechErrorReportSender;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorReportSettings;
import com.sitemonitor.service.techerror.TechErrorReportSettings.IntRange;
import com.sitemonitor.service.techerror.TechErrorVisibilityService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.*;

/**
 * Ayarlar → Teknik hata raporu (2026-10-17, S6): modülü gören takımlar (boş = herkes) ve kanalların onay akışı özeti
 * (yönetici alıcı sayısı, PO onaylayıcı sayısı, dönem raporunun durumu). Takım listesi GLOBAL_ONLY bir ayar: global
 * yönetici yazar, kapsamlı müdür salt okunur görür (kendi kapsamındaki takımların kanallarıyla). Yönetici alıcıları
 * kanalın kendi ayarındadır ({@code PUT /api/tech-error-reports/channels/{id}}, takımı yönetenler).
 *
 * <p>{@code GET/PUT /settings} 11. sürümle BİREBİR aynıdır (yalnız görünürlük; gövdede {@code team_ids} yoksa liste boş =
 * herkes — 11. sürüm sözleşmesi). Hatırlatma ve posta sınırı ayarları (12. sürüm, C9 / C10 / E21) AYRI uçtadır:
 * {@code GET/PUT /settings/reminders} (inceleme 2026-10-18: yuvarlanan dağıtımda yeni kart eski pod'a
 * {@code PUT /settings {reminder_day}} gönderince eski pod eksik {@code team_ids}'i boş okuyup modül görünürlük kısıtını
 * SİLİYORDU; ayrı yol eski pod'da 404 / 405 alır ve hiçbir şey yazmaz). Hatırlatma PUT'u YALNIZ gönderilen alanları yazar
 * (gönderilmeyen alana dokunulmaz; {@code team_ids} bu uçta yok sayılır; boş gövde hiçbir şey yazmaz ve denetlenmez); alan
 * hataları 400 {@code VALIDATION_FAILED} + {@code field} (gövdedeki anahtar adıyla). Hepsi GLOBAL_ONLY: kapsamlı müdür
 * okur (salt okunur), yazamaz (403).
 */
@RestController
@RequestMapping("/api/admin/tech-error-reports")
public class TechErrorSettingsController {

    /** Hatırlatma PUT gövdesindeki tam sayı alanları → ayar aralığı (sıra ön yüzle aynı). */
    static final Map<String, IntRange> INT_FIELDS = new LinkedHashMap<>();
    static {
        INT_FIELDS.put("reminder_day", TechErrorReportSettings.REMINDER_DAY);
        INT_FIELDS.put("reminder_repeat_days", TechErrorReportSettings.REMINDER_REPEAT_DAYS);
        INT_FIELDS.put("approval_reminder_days", TechErrorReportSettings.APPROVAL_REMINDER_DAYS);
        INT_FIELDS.put("max_mail_mb", TechErrorReportSettings.MAX_MAIL_MB);
        INT_FIELDS.put("mail_inline_mb", TechErrorReportSettings.MAIL_INLINE_MB);
    }

    private final AppSettingsService settings;
    private final TeamRepository teams;
    private final TechErrorReportService service;
    private final TechErrorApprovalService approvals;
    private final TechErrorVisibilityService visibility;
    private final AuditService auditService;

    public TechErrorSettingsController(AppSettingsService settings, TeamRepository teams, TechErrorReportService service,
                                       TechErrorApprovalService approvals, TechErrorVisibilityService visibility,
                                       AuditService auditService) {
        this.settings = settings;
        this.teams = teams;
        this.service = service;
        this.approvals = approvals;
        this.visibility = visibility;
        this.auditService = auditService;
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        requireAdmin(session);
        boolean global = SessionScope.isGlobalAdmin(session);
        Set<Long> enabled = visibility.enabledTeamIds();
        Map<Long, Team> teamMap = new LinkedHashMap<>();
        Collection<Long> scope = global ? null : TechErrorReportController.ownVisibleTeamIds(session);   // yönetim yüzeyi: paylaşım açmaz
        for (Team t : scope == null ? teams.findAll() : teams.findAllById(scope)) teamMap.put(t.getId(), t);
        List<TechErrorChannel> chs = service.channels(scope);
        YearMonth period = service.defaultPeriod();
        List<Long> ids = chs.stream().map(TechErrorChannel::getId).toList();
        Map<Long, TechErrorReportRepository.Row> periodRows = service.periodRows(ids, period);
        Map<Long, Integer> approverCounts = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TechErrorChannel ch : chs) {
            Team t = teamMap.get(ch.getTeamId());
            if (t == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ch.getId());
            m.put("name", ch.getName());
            m.put("active", ch.isActive());
            m.put("team_id", ch.getTeamId());
            m.put("team_name", t.getName());
            m.put("team_visible", enabled == null || enabled.contains(ch.getTeamId()));
            m.put("executive_count", TechErrorReportSender.executiveRecipients(ch).size());
            m.put("approver_count", approverCounts.computeIfAbsent(ch.getTeamId(), id -> approvals.approvers(id).size()));
            m.put("team_email_set", !TechErrorReportSender.recipients(t).isEmpty());
            TechErrorReportRepository.Row pr = periodRows.get(ch.getId());
            m.put("period_report", pr == null ? null : TechErrorReportController.statusDto(pr, service.now()));
            rows.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team_ids", enabled == null ? List.of() : new ArrayList<>(enabled));
        data.put("read_only", !global);
        data.put("period", Map.of("year", period.getYear(), "month", period.getMonthValue()));
        data.put("channels", rows);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** 11. sürüm sözleşmesi BİREBİR: yalnız {@code team_ids}; gövdede yoksa boş liste (= herkes) yazılır. */
    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpSession session,
                                                    HttpServletRequest request) {
        requireAdmin(session);
        requireGlobalWriter(session);
        Object raw = body == null ? null : body.get("team_ids");
        List<Long> ids = teamIds(raw, "team_ids", true);
        String csv = csv(ids);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(TechErrorReportSettings.KEY_TEAM_IDS, csv);
        settings.save(Map.of("values", values), actor(session));
        auditService.recordAction("TECH_ERROR_SETTINGS_SAVE", session, request, "SETTINGS", "tech-error-report",
                AuditDetail.of("team_ids", new ArrayList<>(ids), "everyone", ids.isEmpty()));
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("team_ids", new ArrayList<>(ids))));
    }

    // ── Hatırlatmalar ve posta sınırları (12. sürüm) ─────────────────────────────────────────────────────

    @GetMapping("/settings/reminders")
    public ResponseEntity<Map<String, Object>> getReminders(HttpSession session) {
        requireAdmin(session);
        Map<String, Object> data = reminders();
        data.put("read_only", !SessionScope.isGlobalAdmin(session));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @PutMapping("/settings/reminders")
    public ResponseEntity<Map<String, Object>> saveReminders(@RequestBody(required = false) Map<String, Object> body,
                                                             HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        requireGlobalWriter(session);
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> audit = new LinkedHashMap<>();
        if (in.containsKey("reminder_team_ids")) {
            List<Long> ids = teamIds(in.get("reminder_team_ids"), "reminder_team_ids", true);
            values.put(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, csv(ids));
            audit.put("reminder_team_ids", ids);
        }
        Map<String, Integer> ints = new LinkedHashMap<>();
        for (Map.Entry<String, IntRange> e : INT_FIELDS.entrySet()) {
            if (!in.containsKey(e.getKey())) continue;
            int v = intField(in.get(e.getKey()), e.getKey(), e.getValue());
            ints.put(e.getKey(), v);
            values.put(e.getValue().key(), String.valueOf(v));
            audit.put(e.getKey(), v);
        }
        if (in.containsKey("reminder_push")) {
            Object raw = in.get("reminder_push");
            if (!(raw instanceof Boolean b)) {
                throw new FieldValidationException("reminder_push", Msg.t("Push seçeneği açık ya da kapalı olmalı.",
                        "The push option must be on or off."));
            }
            values.put(TechErrorReportSettings.KEY_REMINDER_PUSH, String.valueOf(b));
            audit.put("reminder_push", b);
        }
        // Alanlar-arası kural: gövde görsel bütçesi < posta sınırı (gönderilmeyen taraf etkin değerden)
        if (ints.containsKey("max_mail_mb") || ints.containsKey("mail_inline_mb")) {
            int max = ints.getOrDefault("max_mail_mb", TechErrorReportSettings.effectiveInt(settings, TechErrorReportSettings.MAX_MAIL_MB));
            int inline = ints.getOrDefault("mail_inline_mb", TechErrorReportSettings.effectiveInt(settings, TechErrorReportSettings.MAIL_INLINE_MB));
            String problem = TechErrorReportSettings.mailLimitsProblem(max, inline);
            if (problem != null) {
                throw new FieldValidationException(ints.containsKey("mail_inline_mb") ? "mail_inline_mb" : "max_mail_mb", problem);
            }
        }
        if (values.isEmpty()) {
            // Değişiklik yok: hiçbir şey yazılmaz, denetlenmez (boş PUT; bu uçta görünürlük anahtarı yok sayılır)
            return ResponseEntity.ok(Map.of("success", true, "data", remindersResponse()));
        }
        settings.save(Map.of("values", values), actor(session));
        audit.put("keys", new ArrayList<>(values.keySet()));
        auditService.recordAction("TECH_ERROR_SETTINGS_SAVE", session, request, "SETTINGS", "tech-error-report",
                AuditDetail.ofMap(audit));
        return ResponseEntity.ok(Map.of("success", true, "data", remindersResponse()));
    }

    /** Hatırlatma ve posta sınırı ayarlarının ETKİN değerleri + aralıkları (ön yüz alan sınırlarını buradan alır). */
    Map<String, Object> reminders() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("team_ids", new ArrayList<>(TechErrorReportSettings.reminderTeamIds(settings)));
        for (Map.Entry<String, IntRange> e : INT_FIELDS.entrySet()) {
            r.put(e.getKey(), TechErrorReportSettings.effectiveInt(settings, e.getValue()));
        }
        r.put("reminder_push", TechErrorReportSettings.reminderPush(settings));
        Map<String, Object> limits = new LinkedHashMap<>();
        for (Map.Entry<String, IntRange> e : INT_FIELDS.entrySet()) {
            limits.put(e.getKey(), Map.of("min", e.getValue().min(), "max", e.getValue().max(), "default", e.getValue().def()));
        }
        r.put("limits", limits);
        return r;
    }

    /** Kayıt yanıtı: yazan her zaman global yönetici → salt okunur değil. */
    private Map<String, Object> remindersResponse() {
        Map<String, Object> data = reminders();
        data.put("read_only", false);
        return data;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    /** Takım kimlik dizisi: sayı ya da sayı metni, pozitif, ≤ {@value TechErrorReportSettings#MAX_TEAMS}, hepsi var olmalı. */
    private List<Long> teamIds(Object raw, String field, boolean checkExists) {
        if (raw != null && !(raw instanceof Collection<?>)) {
            throw new FieldValidationException(field, Msg.t("Takım listesi bir dizi olmalı.", "The team list must be an array."));
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (raw instanceof Collection<?> col) {
            for (Object o : col) {
                Long id = o instanceof Number n ? Long.valueOf(n.longValue()) : parseOrNull(String.valueOf(o));
                if (id == null || id <= 0) {
                    throw new FieldValidationException(field, Msg.t("Takım kimliği geçersiz.", "A team id is invalid."));
                }
                ids.add(id);
            }
        }
        if (ids.size() > TechErrorReportSettings.MAX_TEAMS) {
            throw new FieldValidationException(field,
                    Msg.t("En çok " + TechErrorReportSettings.MAX_TEAMS + " takım seçilebilir.",
                            "At most " + TechErrorReportSettings.MAX_TEAMS + " teams can be selected."));
        }
        if (checkExists && !ids.isEmpty()) {
            Set<Long> known = new HashSet<>();
            for (Team t : teams.findAllById(ids)) known.add(t.getId());
            if (!known.containsAll(ids)) {
                throw new FieldValidationException(field,
                        Msg.t("Seçilen takımlardan biri bulunamadı; listeyi yenileyin.", "One of the selected teams wasn't found; refresh the list."));
            }
        }
        return new ArrayList<>(ids);
    }

    /** Tam sayı alanı: sayı (kesirsiz) ya da sayı metni, aralık içinde; aksi hâlde alan hatası. */
    private static int intField(Object raw, String field, IntRange r) {
        Integer v = null;
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1_000_000) v = (int) d;
        } else if (raw instanceof String s) {
            try { v = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { v = null; }
        }
        if (v == null || v < r.min() || v > r.max()) throw new FieldValidationException(field, TechErrorReportSettings.rangeMessage(r));
        return v;
    }

    private static String csv(List<Long> ids) {
        return ids.isEmpty() ? "" : String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private static void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session) && !SessionScope.isScopedAdmin(session)) {
            throw new SecurityException(Msg.t("Bu sayfa yöneticilere açıktır.", "This page is for administrators."));
        }
    }

    private static void requireGlobalWriter(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Bu ayarı yalnız global yöneticiler değiştirebilir.",
                    "Only global administrators can change this setting."));
        }
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "system" : String.valueOf(u);
    }

    private static Long parseOrNull(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
