package com.sitemonitor.controller;

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
 */
@RestController
@RequestMapping("/api/admin/tech-error-reports")
public class TechErrorSettingsController {

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
        Collection<Long> scope = global ? null : TechErrorReportController.visibleTeamIds(session);
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

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpSession session,
                                                    HttpServletRequest request) {
        requireAdmin(session);
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Bu ayarı yalnız global yöneticiler değiştirebilir.",
                    "Only global administrators can change this setting."));
        }
        Object raw = body == null ? null : body.get("team_ids");
        if (raw != null && !(raw instanceof Collection<?>)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_ids",
                    Msg.t("Takım listesi bir dizi olmalı.", "The team list must be an array."));
        }
        Set<Long> ids = new LinkedHashSet<>();
        if (raw instanceof Collection<?> col) {
            for (Object o : col) {
                Long id = o instanceof Number n ? Long.valueOf(n.longValue()) : parseOrNull(String.valueOf(o));
                if (id == null || id <= 0) {
                    throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_ids",
                            Msg.t("Takım kimliği geçersiz.", "A team id is invalid."));
                }
                ids.add(id);
            }
        }
        if (ids.size() > TechErrorReportSettings.MAX_TEAMS) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_ids",
                    Msg.t("En çok " + TechErrorReportSettings.MAX_TEAMS + " takım seçilebilir.",
                            "At most " + TechErrorReportSettings.MAX_TEAMS + " teams can be selected."));
        }
        if (!ids.isEmpty()) {
            Set<Long> known = new HashSet<>();
            for (Team t : teams.findAllById(ids)) known.add(t.getId());
            if (!known.containsAll(ids)) {
                throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("team_ids",
                        Msg.t("Seçilen takımlardan biri bulunamadı; listeyi yenileyin.", "One of the selected teams wasn't found; refresh the list."));
            }
        }
        String csv = ids.isEmpty() ? "" : String.join(",", ids.stream().map(String::valueOf).toList());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(TechErrorReportSettings.KEY_TEAM_IDS, csv);
        settings.save(Map.of("values", values), actor(session));
        auditService.recordAction("TECH_ERROR_SETTINGS_SAVE", session, request, "SETTINGS", "tech-error-report",
                AuditDetail.of("team_ids", new ArrayList<>(ids), "everyone", ids.isEmpty()));
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("team_ids", new ArrayList<>(ids))));
    }

    private static void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session) && !SessionScope.isScopedAdmin(session)) {
            throw new SecurityException(Msg.t("Bu sayfa yöneticilere açıktır.", "This page is for administrators."));
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
