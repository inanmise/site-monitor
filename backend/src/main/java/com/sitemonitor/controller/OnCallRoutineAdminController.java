package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineCodes;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import com.sitemonitor.service.oncallroutine.OnCallRoutineStore.DayRow;
import com.sitemonitor.service.oncallroutine.OnCallRoutineVisibility;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ayarlar → Platform → Nöbet rutinleri (2026-11-26, §4.3): modülü gören takımlar (boş = herkes), üç yönetici anahtarı
 * (hatırlatma push'u, tamamlama bildirimi, devir özeti) ve takımların son 30 gün özeti. Ayarlar GLOBAL_ONLY: global yönetici
 * yazar; AUDIT ve kapsamlı müdür okur (salt okunur). Genel bakış: kurum geneli görüntüleyici tüm takımları, kapsamlı müdür
 * kendi kapsamını görür.
 */
@RestController
@RequestMapping("/api/admin/oncall-routines")
public class OnCallRoutineAdminController {

    static final int MAX_OVERVIEW = 500;

    private final AppSettingsService settings;
    private final TeamRepository teams;
    private final OnCallRoutineService svc;
    private final AuditService auditService;

    public OnCallRoutineAdminController(AppSettingsService settings, TeamRepository teams, OnCallRoutineService svc,
                                        AuditService auditService) {
        this.settings = settings;
        this.teams = teams;
        this.svc = svc;
        this.auditService = auditService;
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> get(HttpSession session) {
        requireReader(session);
        boolean global = SessionScope.isGlobalViewer(session);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("team_ids", new ArrayList<>(storedIds()));
        values.put("push_reminders", OnCallRoutineVisibility.switchOn(settings, OnCallRoutineVisibility.KEY_PUSH_REMINDERS));
        values.put("completion_notice", OnCallRoutineVisibility.switchOn(settings,
                OnCallRoutineVisibility.KEY_COMPLETION_NOTICE));
        values.put("handover_notice", OnCallRoutineVisibility.switchOn(settings, OnCallRoutineVisibility.KEY_HANDOVER_NOTICE));
        Collection<Long> scope = global ? null : OnCallRoutineVisibility.ownTeamIds(session);
        List<Map<String, Object>> teamRows = new ArrayList<>();
        List<Team> list = new ArrayList<>();
        for (Team t : scope == null ? teams.findAll() : teams.findAllById(scope)) list.add(t);
        Collator coll = Collator.getInstance(Locale.forLanguageTag("tr"));
        list.sort(Comparator.comparing((Team t) -> t.getName() == null ? "" : t.getName(), coll).thenComparing(Team::getId));
        for (Team t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            teamRows.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("values", values);
        data.put("retention_days", retentionDays());
        data.put("read_only", !SessionScope.isGlobalAdmin(session));
        data.put("teams", teamRows);
        return ok(data);
    }

    /** Yalnız gönderilen anahtarlar yazılır; boş gövde hiçbir şey yazmaz ve denetlenmez. */
    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireReader(session);
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Bu ayarı yalnız global yöneticiler değiştirebilir.",
                    "Only global administrators can change this setting."));
        }
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        if (in.containsKey("team_ids")) {
            List<Long> ids = teamIds(in.get("team_ids"), storedIds());
            values.put(OnCallRoutineVisibility.KEY_TEAM_IDS,
                    ids.isEmpty() ? "" : String.join(",", ids.stream().map(String::valueOf).toList()));
            keys.add("team_ids");
        }
        putBool(in, "push_reminders", OnCallRoutineVisibility.KEY_PUSH_REMINDERS, values, keys);
        putBool(in, "completion_notice", OnCallRoutineVisibility.KEY_COMPLETION_NOTICE, values, keys);
        putBool(in, "handover_notice", OnCallRoutineVisibility.KEY_HANDOVER_NOTICE, values, keys);
        if (!values.isEmpty()) {
            settings.save(Map.of("values", values), actor(session));
            auditService.recordAction(OnCallRoutineCodes.AUDIT_ADMIN_SETTINGS_SAVE, session, request, "SETTINGS",
                    "oncall-routine", AuditDetail.of("keys", keys));
        }
        return get(session);
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpSession session) {
        requireReader(session);
        boolean global = SessionScope.isGlobalViewer(session);
        Set<Long> ids = new LinkedHashSet<>();
        if (global) {
            for (Team t : teams.findAll()) ids.add(t.getId());
        } else {
            ids.addAll(OnCallRoutineVisibility.ownTeamIds(session));
        }
        // Ada göre sıralayıp keser, sonra yalnız gösterilecek takımların şablonunu okur
        List<TeamCtx> ctxs = svc.ctxsByName(ids, MAX_OVERVIEW).ctxs();
        LocalDate today = svc.today();
        Map<Long, DayRow> todayRows = new HashMap<>();
        List<Long> teamIds = ctxs.stream().map(TeamCtx::teamId).toList();
        if (!teamIds.isEmpty()) {
            for (DayRow r : svc.store().daysOfTeamsOn(teamIds, today.toString())) todayRows.put(r.teamId(), r);
        }
        Map<Long, Map<String, Integer>> last30 = svc.last30(today);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TeamCtx c : ctxs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", c.teamId());
            m.put("team_name", c.teamName());
            m.put("module_visible", OnCallRoutineVisibility.visible(c.teamId()));
            m.put("configured", c.configured());
            m.put("enabled", c.enabled());
            m.put("tasks_active", c.row() == null ? 0 : c.row().tasksActive());
            m.put("today_status", c.configured() ? svc.wireStatus(c, today, todayRows.get(c.teamId()), today) : null);
            Map<String, Integer> counts = last30.getOrDefault(c.teamId(), Map.of());
            Map<String, Object> l = new LinkedHashMap<>();
            int total = 0;
            for (int v : counts.values()) total += v;
            l.put("workdays", total);
            l.put("completed", counts.getOrDefault(OnCallRoutineCodes.DAY_COMPLETED, 0));
            l.put("partial", counts.getOrDefault(OnCallRoutineCodes.DAY_COMPLETED_PARTIAL, 0));
            l.put("incomplete", counts.getOrDefault(OnCallRoutineCodes.DAY_INCOMPLETE, 0));
            l.put("missed", counts.getOrDefault(OnCallRoutineCodes.DAY_MISSED, 0));
            m.put("last30", l);
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", rows);
        return ResponseEntity.ok(body);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────

    /** Okuyucu: kurum geneli görüntüleyici (global admin, AUDIT) ya da kapsamlı müdür. */
    static void requireReader(HttpSession session) {
        if (!SessionScope.isGlobalViewer(session) && !SessionScope.isScopedAdmin(session)) {
            throw new SecurityException(Msg.t("Bu sayfa yöneticilere açıktır.", "This page is for administrators."));
        }
    }

    private int retentionDays() {
        try {
            return settings.getInt(OnCallRoutineVisibility.KEY_RETENTION_DAYS, 730);
        } catch (RuntimeException e) {
            return 730;
        }
    }

    private Set<Long> storedIds() {
        try {
            Set<Long> s = OnCallRoutineVisibility.parseLenient(settings.getString(OnCallRoutineVisibility.KEY_TEAM_IDS, ""));
            return s == null ? Set.of() : s;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /**
     * Takım kimlik dizisi: sayı ya da sayı metni, pozitif, ≤ {@value OnCallRoutineVisibility#MAX_TEAMS}. YENİ eklenen
     * bilinmeyen kimlik 400; ZATEN kayıtlı olan (takım sonradan silindi) sessizce düşer.
     */
    List<Long> teamIds(Object raw, Set<Long> stored) {
        if (raw != null && !(raw instanceof Collection<?>)) {
            throw new FieldValidationException("team_ids", Msg.t("Takım listesi bir dizi olmalı.", "The team list must be an array."));
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (raw instanceof Collection<?> col) {
            if (col.size() > OnCallRoutineVisibility.MAX_TEAMS * 2) {
                throw new FieldValidationException("team_ids", Msg.t("Takım listesi çok uzun.", "The team list is too long."));
            }
            for (Object o : col) {
                Long id = o instanceof Number n ? Long.valueOf(n.longValue()) : parseOrNull(String.valueOf(o));
                if (id == null || id <= 0) {
                    throw new FieldValidationException("team_ids", Msg.t("Takım kimliği geçersiz.", "A team id is invalid."));
                }
                ids.add(id);
            }
        }
        if (ids.size() > OnCallRoutineVisibility.MAX_TEAMS) {
            throw new FieldValidationException("team_ids", Msg.t("En çok " + OnCallRoutineVisibility.MAX_TEAMS
                    + " takım seçilebilir.", "At most " + OnCallRoutineVisibility.MAX_TEAMS + " teams can be selected."));
        }
        Set<Long> existing = new LinkedHashSet<>();
        if (!ids.isEmpty()) for (Team t : teams.findAllById(ids)) existing.add(t.getId());
        List<Long> out = new ArrayList<>();
        for (Long id : ids) {
            if (existing.contains(id)) {
                out.add(id);
            } else if (!stored.contains(id)) {
                throw new FieldValidationException("team_ids", Msg.t(
                        "Seçilen takımlardan biri bulunamadı (silinmiş olabilir). Listeyi yenileyip yeniden seçin.",
                        "One of the selected teams wasn't found (it may have been deleted). Reload the list and pick again."));
            }
        }
        if (out.isEmpty() && !ids.isEmpty()) {
            // Yalnız silinmiş takımlar kalırsa liste boşalıp "herkes"e dönmesin — saklanan liste korunur
            return new ArrayList<>(ids);
        }
        return out;
    }

    private static void putBool(Map<String, Object> in, String field, String key, Map<String, Object> values, List<String> keys) {
        if (!in.containsKey(field)) return;
        Object v = in.get(field);
        if (!(v instanceof Boolean b)) {
            throw new FieldValidationException(field, Msg.t("Açık / kapalı değeri bekleniyor.", "An on / off value is expected."));
        }
        values.put(key, String.valueOf(b));
        keys.add(field);
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "system" : String.valueOf(u);
    }

    private static Long parseOrNull(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
