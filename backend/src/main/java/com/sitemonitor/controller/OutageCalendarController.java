package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.OutageCalendar;
import com.sitemonitor.service.OutageCalendarService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Takım kesinti takvimi (2026-11-19): {@code GET /api/admin/alerts/outage-calendar?team=<id>&month=yyyy-MM} — Alarm Geçmişi
 * "Takvim" görünümü. Ayrı denetleyici (AdminController'ın dilim testlerinin yapıcısı değişmesin — {@code AlertNoiseController}
 * ve {@code ContactBulkController} deseni); yol {@code /api/admin/alerts/{id}}'den özgül olduğu için çakışmaz.
 *
 * <p><b>Kapı.</b> {@code alerts.read/view} (Alarm Geçmişi listesiyle aynı). Takım görünürlüğü listenin kuralı: tümünü gören
 * (global görüntüleyici / 7-24 konsolu — {@code AdminController.seesAllAlerts}) her takımı; diğerleri görüş kapsamı +
 * erişim paylaşımı ({@link SessionScope#canViewWithGrants}). Görülemeyen ya da bilinmeyen takım → 404 (varlık sızmaz).
 *
 * <p><b>Doğrulama.</b> {@code team} eksik / sayı değil → 400 {@code team}; {@code month} eksik / {@code yyyy-MM} değil, 24
 * aydan eski ya da gelecekte → 400 {@code month} (İstanbul takvimine göre). Okuma hatası → 503 {@code DB_UNAVAILABLE} (asla
 * "kesinti yok").
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class OutageCalendarController {

    /** Geriye en çok kaç ay (bu ay dahil değil) — sorgu maliyeti ve saklama süresiyle sınırlı. */
    static final int MAX_MONTHS_BACK = 24;
    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private final OutageCalendarService calendarService;
    private final TeamRepository teamRepo;
    private final PermissionService permissionService;

    /** 7/24 operatörü tüm alarmları görür (AdminController.seesAllAlerts ile aynı) — alan enjeksiyonu, null-güvenli. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    /** Test kancası: "şimdi" (epoch ms). */
    LongSupplier clock = System::currentTimeMillis;

    @GetMapping("/alerts/outage-calendar")
    public ResponseEntity<Map<String, Object>> outageCalendar(@RequestParam(required = false) String team,
                                                              @RequestParam(required = false) String month,
                                                              HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        long teamId = parseTeam(team);
        long now = clock.getAsLong();
        YearMonth ym = parseMonth(month, now);

        boolean seesAll = SessionScope.isGlobalViewer(session) || (nocCallLog != null && nocCallLog.seesAllAlerts(session));
        if (!seesAll && !SessionScope.canViewWithGrants(session, teamId)) throw notFound();
        Team t = teamRepo.findById(teamId).orElseThrow(OutageCalendarController::notFound);

        OutageCalendarService.TeamMonth tm;
        try {
            tm = calendarService.forTeam(ym, teamId, now);
        } catch (OutageCalendarService.UnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("success", false, "code", "DB_UNAVAILABLE",
                    "error", Msg.t("Kesinti takvimi şu an okunamıyor; veritabanına geçici olarak ulaşılamıyor olabilir. Birkaç saniye sonra yeniden deneyin.",
                            "The outage calendar can’t be read right now; the database may be briefly unreachable. Try again in a few seconds.")));
        }

        List<Map<String, Object>> days = new ArrayList<>(tm.result().days().size());
        for (OutageCalendar.Day d : tm.result().days()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d.day().toString());
            m.put("outage_minutes", d.outageMinutes());
            m.put("alarms", d.alarms());
            m.put("worst_level", d.worstLevel());
            days.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", ym.toString());
        data.put("team_id", t.getId());
        data.put("team_name", t.getName());
        data.put("days", days);
        data.put("total_minutes", tm.result().totalMinutes());
        data.put("truncated", tm.truncated());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static long parseTeam(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            throw new FieldValidationException("team", Msg.t("Takvim için bir takım seçin.", "Choose a team for the calendar."));
        }
        try {
            long v = Long.parseLong(s);
            if (v <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new FieldValidationException("team", Msg.t("Geçersiz takım: takım listesinden bir takım seçin.",
                    "Invalid team: choose a team from the team list."));
        }
    }

    /** {@code yyyy-MM}; bu ay ile 24 ay öncesi arasında (İstanbul takvimi). */
    static YearMonth parseMonth(String raw, long nowMs) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            throw new FieldValidationException("month", Msg.t("Takvim için bir ay seçin (yyyy-AA).", "Choose a month for the calendar (yyyy-MM)."));
        }
        YearMonth ym;
        try {
            if (!MONTH.matcher(s).matches()) throw new DateTimeParseException("biçim", s, 0);
            ym = YearMonth.parse(s, DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (DateTimeParseException e) {
            throw new FieldValidationException("month", Msg.t("Geçersiz ay: yyyy-AA biçiminde yazın (ör. 2026-11).",
                    "Invalid month: use the yyyy-MM format (for example 2026-11)."));
        }
        YearMonth current = YearMonth.from(Instant.ofEpochMilli(nowMs).atOffset(OutageCalendar.ISTANBUL));
        if (ym.isAfter(current)) {
            throw new FieldValidationException("month", Msg.t("Gelecek bir ayın kesinti takvimi yok; bu ayı ya da geçmiş bir ayı seçin.",
                    "There is no outage calendar for a future month; choose this month or an earlier one."));
        }
        if (ym.isBefore(current.minusMonths(MAX_MONTHS_BACK))) {
            throw new FieldValidationException("month", Msg.t("Kesinti takvimi en çok " + MAX_MONTHS_BACK + " ay geriye gösterilir; daha yakın bir ay seçin.",
                    "The outage calendar goes back at most " + MAX_MONTHS_BACK + " months; choose a more recent month."));
        }
        return ym;
    }

    private static CodedNotFoundException notFound() {
        return new CodedNotFoundException("TEAM_NOT_FOUND", Msg.t("Takım bulunamadı ya da bu takımın alarmlarını görme yetkiniz yok.",
                "The team wasn’t found, or you aren’t allowed to see its alerts."));
    }
}
