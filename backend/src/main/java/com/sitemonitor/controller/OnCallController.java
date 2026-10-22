package com.sitemonitor.controller;

import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.oncall.OnCallService;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Nöbet çizelgesi uçları (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de").
 * <ul>
 *   <li>{@code GET /api/oncall/teams/{teamId}} — çizelge + şu anki nöbetçi + yaklaşan dilimler + yer değiştirmeler.
 *       Okuma: {@code oncall.read/view} ve takımın izleme okuma kapsamı ({@link SessionScope#canViewMonitoringOwn} —
 *       takım erişim paylaşımı nöbet çizelgesini / nöbetçiyi AÇMAZ, 2026-10-12).</li>
 *   <li>{@code PUT /api/oncall/teams/{teamId}} — kaydet. Yazma: {@code oncall.manage/edit} ve takımı yönetme
 *       ({@link SessionScope#canManage}). Denetim {@code ONCALL_SCHEDULE_SAVE}.</li>
 *   <li>{@code POST /api/oncall/teams/{teamId}/overrides}, {@code DELETE /api/oncall/overrides/{id}} — yer değiştirme
 *       (aynı yazma kapısı). Denetim {@code ONCALL_OVERRIDE_ADD} / {@code ONCALL_OVERRIDE_DELETE}.</li>
 *   <li>{@code GET /api/oncall/current?teamIds=…} — takımların şu anki nöbetçisi (takımlar sayfası, alarm ayrıntısı);
 *       kapsam dışı takımlar sessizce düşer.</li>
 * </ul>
 * Kullanıcı kimlikleri yanıtta {@code user_id} anahtarında — global olmayan görüntüleyiciye opak kimlik
 * (UserRefResponseAdvice); girdi {@link UserPublicIds#resolve}.
 */
@RestController
@RequestMapping("/api/oncall")
@RequiredArgsConstructor
public class OnCallController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int MAX_CURRENT_TEAMS = 200;

    private final OnCallService onCall;
    private final TeamRepository teams;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @Autowired(required = false)
    private UserPublicIds userPublicIds;

    @GetMapping("/teams/{teamId}")
    public ResponseEntity<Map<String, Object>> view(@PathVariable Long teamId, HttpSession session) {
        permissionService.require(session, "oncall.read", "view");
        requireTeam(teamId);
        if (!SessionScope.canViewMonitoringOwn(session, teamId)) throw new NoSuchElementException("team");
        Map<String, Object> data = new LinkedHashMap<>(onCall.view(teamId, Instant.now()));
        data.put("can_edit", canEdit(session, teamId));
        return ok(Map.of("data", data));
    }

    @PutMapping("/teams/{teamId}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireWrite(session, teamId);
        List<Long> members = new ArrayList<>();
        if (body.get("members") instanceof List<?> list) {
            if (list.size() > OnCallService.MAX_MEMBERS) {
                throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("members", Msg.t(
                        "Bir çizelgede en çok " + OnCallService.MAX_MEMBERS + " kişi olabilir.",
                        "A schedule can have at most " + OnCallService.MAX_MEMBERS + " people."));
            }
            for (Object o : list) {
                // Silinmiş hesabın görünümdeki kimliği null'dır (K11, 2026-10-22) — eski istemci onu geri yollar; servis
                // silinmiş üyeleri zaten düşürür. Boş olmayan ama bilinmeyen kimlik 400 kalır.
                if (o == null) continue;
                Long id = UserPublicIds.resolve(userPublicIds, o, session);
                if (id == null) {
                    throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("members", Msg.t(
                            "Listedeki bir kişi bulunamadı; sayfayı yenileyip yeniden seçin.",
                            "A person in the list was not found; refresh the page and pick again."));
                }
                members.add(id);
            }
        }
        OnCallSchedule saved = onCall.save(teamId, bool(body.get("enabled")), str(body.get("rotation")), str(body.get("anchor")),
                members, bool(body.get("notify_oncall")), username(session));
        auditService.recordAction("ONCALL_SCHEDULE_SAVE", session, request, "TEAM", String.valueOf(teamId),
                "{\"enabled\":" + saved.getEnabled() + ",\"rotation\":\"" + saved.getRotation() + "\",\"members\":" + members.size()
                        + ",\"notify_oncall\":" + saved.getNotifyOncall() + "}");
        Map<String, Object> data = new LinkedHashMap<>(onCall.view(teamId, Instant.now()));
        data.put("can_edit", true);
        return ok(Map.of("data", data));
    }

    @PostMapping("/teams/{teamId}/overrides")
    public ResponseEntity<Map<String, Object>> addOverride(@PathVariable Long teamId, @RequestBody Map<String, Object> body,
                                                           HttpSession session, HttpServletRequest request) {
        requireWrite(session, teamId);
        Long userId = UserPublicIds.resolve(userPublicIds, body.get("user_id"), session);
        OnCallOverride o = onCall.addOverride(teamId, userId, str(body.get("starts_at")), str(body.get("ends_at")),
                str(body.get("note")), username(session));
        auditService.recordAction("ONCALL_OVERRIDE_ADD", session, request, "TEAM", String.valueOf(teamId),
                "{\"override\":" + o.getId() + ",\"starts_at\":\"" + o.getStartsAt() + "\",\"ends_at\":\"" + o.getEndsAt() + "\"}");
        Map<String, Object> data = new LinkedHashMap<>(onCall.view(teamId, Instant.now()));
        data.put("can_edit", true);
        return ok(Map.of("data", data));
    }

    @DeleteMapping("/overrides/{id}")
    public ResponseEntity<Map<String, Object>> deleteOverride(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        OnCallOverride o = onCall.findOverride(id);
        if (o == null) throw new NoSuchElementException("override");
        requireWrite(session, o.getTeamId());
        onCall.deleteOverride(o);
        auditService.recordAction("ONCALL_OVERRIDE_DELETE", session, request, "TEAM", String.valueOf(o.getTeamId()),
                "{\"override\":" + id + "}");
        Map<String, Object> data = new LinkedHashMap<>(onCall.view(o.getTeamId(), Instant.now()));
        data.put("can_edit", true);
        return ok(Map.of("data", data));
    }

    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> current(@RequestParam(required = false) String teamIds, HttpSession session) {
        permissionService.require(session, "oncall.read", "view");
        List<Long> ids = new ArrayList<>();
        if (teamIds != null && teamIds.length() <= 4000) for (String p : teamIds.split(",")) {
            try {
                long id = Long.parseLong(p.trim());
                if (SessionScope.canViewMonitoringOwn(session, id) && !ids.contains(id)) ids.add(id);
            } catch (NumberFormatException ignored) { /* atla */ }
            if (ids.size() >= MAX_CURRENT_TEAMS) break;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (OnCallService.OnCallPerson p : onCall.currentFor(ids).values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", p.teamId());
            m.put("user_id", p.userId());
            m.put("name", p.displayName() == null || p.displayName().isBlank() ? p.username() : p.displayName());
            m.put("active", p.active());
            m.put("override", p.override());
            m.put("until", p.until());
            out.add(m);
        }
        return ok(Map.of("data", out));
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────

    private boolean canEdit(HttpSession session, Long teamId) {
        try {
            permissionService.require(session, "oncall.manage", "edit");
        } catch (SecurityException e) {
            return false;
        }
        return SessionScope.canManage(session, teamId);
    }

    private void requireWrite(HttpSession session, Long teamId) {
        permissionService.require(session, "oncall.manage", "edit");
        requireTeam(teamId);
        if (!SessionScope.canManage(session, teamId)) {
            throw new SecurityException(Msg.t("Bu takımın nöbet çizelgesini yalnız takımı yöneten kişiler değiştirebilir.",
                    "Only people who manage this team can change its on-call schedule."));
        }
    }

    private void requireTeam(Long teamId) {
        if (teamId == null || !teams.existsById(teamId)) throw new NoSuchElementException("team");
    }

    private static String username(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? null : u.toString();
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(r);
    }
}
