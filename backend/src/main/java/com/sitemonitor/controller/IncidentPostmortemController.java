package com.sitemonitor.controller;

import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.incident.IncidentPostmortemService;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Postmortem şablonu + aksiyon takibi (2026-10-11). Kapılar olay kaydının kendisiyle AYNI:
 * <ul>
 *   <li>okuma = {@code incidents.view} + olay okuma kapsamı ({@link IncidentController#canReadIncident});</li>
 *   <li>yazma = {@code incidents.manage/edit} + KENDİ takım sınırı ({@link IncidentController#inOwnIncidentScope}) —
 *       7/24 operatörünün okuma genişlemesi yazmaya girmez;</li>
 *   <li>istisna: aksiyonun SAHİBİ, olayı düzenleyemese de kendi aksiyonunun YALNIZ durumunu değiştirebilir ("Bitti").</li>
 * </ul>
 * Zaman çizelgesinin alarm kısmı ayrıca {@code alerts.read} ve alarmın takım görüşü ister.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentPostmortemController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final IncidentService incidents;
    private final IncidentPostmortemService pm;
    private final PermissionService permissions;
    private final AuditService auditService;

    /** Opak kullanıcı kimliği çözümü (yoksa sayısal — dilim testleri). */
    @Autowired(required = false) private UserPublicIds userPublicIds;

    public IncidentPostmortemController(IncidentService incidents, IncidentPostmortemService pm, PermissionService permissions,
                                        AuditService audit) {
        this.incidents = incidents;
        this.pm = pm;
        this.permissions = permissions;
        this.auditService = audit;
    }

    // ── Rapor + aksiyonlar + zaman çizelgesi (tek okuma) ───────────────────────────────────────────────

    @GetMapping("/{id}/postmortem")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        requireView(session);
        IncidentRecord inc = incidents.get(id);
        requireRead(session, inc);
        boolean alerts = permissions.allows(session, "alerts.read", "view");
        String today = IncidentPostmortemService.today();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incident_id", inc.getId());
        data.put("postmortem", pm.postmortemView(inc));
        Long me = sessionUserId(session);
        data.put("actions", pm.actions(inc.getId()).stream().map(a -> withMine(IncidentPostmortemService.actionDto(a, today), a, me)).toList());
        data.put("timeline", pm.timeline(inc, alerts, team -> SessionScope.canViewMonitoring(session, team)));
        // Yazma uçlarıyla AYNI kural (2026-10-12): incidents.manage/edit + kendi takım kapsamı — yalnız okuyabilen (ör. AUDIT)
        // düzenleme düğmeleri görüp her birinde 403 almasın.
        data.put("can_edit", permissions.allows(session, "incidents.manage", "edit") && canWrite(session, inc));
        data.put("today", today);
        return ok(Map.of("data", data));
    }

    @PutMapping("/{id}/postmortem")
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireManage(session);
        IncidentRecord inc = incidents.get(id);
        requireWrite(session, inc);
        IncidentPostmortem saved = pm.save(inc, body, actor(session));
        auditService.recordAction("INCIDENT_POSTMORTEM_SAVE", session, request, "INCIDENT", String.valueOf(id),
                "{\"status\":\"" + saved.getStatus() + "\",\"version\":" + saved.getVersion() + "}");
        return ok(Map.of("data", pm.postmortemView(inc)));
    }

    // ── Aksiyonlar ──────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/actions")
    public ResponseEntity<Map<String, Object>> addAction(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                         HttpSession session, HttpServletRequest request) {
        requireManage(session);
        IncidentRecord inc = incidents.get(id);
        requireWrite(session, inc);
        Long owner = body != null && body.get("owner_user_id") != null ? resolveOwner(body.get("owner_user_id"), session) : null;
        IncidentActionItem a = pm.createAction(inc, body, owner, actor(session));
        auditService.recordAction("INCIDENT_ACTION_ADD", session, request, "INCIDENT", String.valueOf(id),
                "{\"action_id\":" + a.getId() + ",\"priority\":\"" + a.getPriority() + "\",\"kind\":\"" + a.getKind() + "\"}");
        return ok(Map.of("data", withMine(IncidentPostmortemService.actionDto(a, IncidentPostmortemService.today()), a, sessionUserId(session))));
    }

    @PutMapping("/actions/{actionId}")
    public ResponseEntity<Map<String, Object>> updateAction(@PathVariable Long actionId, @RequestBody Map<String, Object> body,
                                                            HttpSession session, HttpServletRequest request) {
        requireView(session);
        IncidentActionItem a = pm.getAction(actionId);
        IncidentRecord inc = incidents.get(a.getIncidentId());
        boolean writer = permissions.allows(session, "incidents.manage", "edit") && canWrite(session, inc);
        Long me = sessionUserId(session);
        boolean owner = me != null && me.equals(a.getOwnerUserId());
        if (!writer && !owner) {
            throw new SecurityException(Msg.t(
                    "Bu aksiyonu değiştirme yetkiniz yok: olayı düzenleyebilen ya da aksiyonun sahibi olan kişi değiştirebilir.",
                    "You cannot change this action: only someone who can edit the incident or the action's owner can."));
        }
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean ownerSent = writer && b.containsKey("owner_user_id");
        Long ownerId = ownerSent && b.get("owner_user_id") != null ? resolveOwner(b.get("owner_user_id"), session) : null;
        String before = a.getStatus();
        IncidentActionItem saved = pm.updateAction(a, b, ownerId, ownerSent, !writer, actor(session));
        auditService.recordAction("INCIDENT_ACTION_UPDATE", session, request, "INCIDENT", String.valueOf(saved.getIncidentId()),
                "{\"action_id\":" + saved.getId() + ",\"status_before\":\"" + before + "\",\"status\":\"" + saved.getStatus()
                        + "\",\"by_owner\":" + (!writer) + "}");
        return ok(Map.of("data", withMine(IncidentPostmortemService.actionDto(saved, IncidentPostmortemService.today()), saved, me)));
    }

    @DeleteMapping("/actions/{actionId}")
    public ResponseEntity<Map<String, Object>> deleteAction(@PathVariable Long actionId, HttpSession session,
                                                            HttpServletRequest request) {
        requireManage(session);
        IncidentActionItem a = pm.getAction(actionId);
        IncidentRecord inc = incidents.get(a.getIncidentId());
        requireWrite(session, inc);
        pm.deleteAction(a);
        auditService.recordAction("INCIDENT_ACTION_DELETE", session, request, "INCIDENT", String.valueOf(inc.getId()),
                "{\"action_id\":" + a.getId() + ",\"title\":\"" + safe(a.getTitle()) + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    /**
     * Olaylar arası aksiyon listesi — "Aksiyonlar" görünümü. Kapsam: olay okuma kapsamı VEYA aksiyonun sahibi olmak.
     * {@code status}: open (varsayılan) | done | all; {@code mine}: yalnız bana atananlar; {@code overdue}: gecikmişler.
     */
    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> listActions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false, defaultValue = "false") boolean overdue,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpSession session) {
        requireView(session);
        boolean all = SessionScope.seesAllMonitoring(session) || SessionScope.isGlobalViewer(session);
        List<Long> scope = all ? null : SessionScope.viewTeamIds(session);
        String query = q == null ? null : (q.length() > 200 ? q.substring(0, 200) : q);
        return ok(Map.of("data", pm.search(!all, scope, sessionUserId(session), mine, status, overdue, query, page, size,
                (team, createdBy) -> IncidentController.canReadIncident(session, team, createdBy))));
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────

    /** "Bana atanmış mı" — istemci opak kimlikleri karşılaştırmasın diye sunucuda (kendi kimliği yanıta yazılmaz). */
    private static Map<String, Object> withMine(Map<String, Object> dto, IncidentActionItem a, Long me) {
        dto.put("mine", me != null && me.equals(a.getOwnerUserId()));
        return dto;
    }

    private Long resolveOwner(Object raw, HttpSession session) {
        Long id = UserPublicIds.resolve(userPublicIds, raw, session);
        if (id == null) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("owner_user_id", Msg.t(
                    "Seçilen kişi bulunamadı. Listeden yeniden seçin.", "The selected person was not found. Pick them again from the list."));
        }
        return id;
    }

    private void requireView(HttpSession session) {
        if (!permissions.allows(session, "incidents.view", "view")) throw new SecurityException("incidents.view yetkisi gerekli");
    }

    private void requireManage(HttpSession session) {
        if (!permissions.allows(session, "incidents.manage", "edit")) throw new SecurityException("incidents.manage yetkisi gerekli");
    }

    private static void requireRead(HttpSession session, IncidentRecord e) {
        if (SessionScope.isGlobalViewer(session)) return;
        if (IncidentController.canReadIncident(session, e.getTeamId(), e.getCreatedByTeamId())) return;
        throw new SecurityException("Bu olay kaydı sizin takım(lar)ınıza ait değil");
    }

    private static boolean canWrite(HttpSession session, IncidentRecord e) {
        return IncidentController.inOwnIncidentScope(session, e.getTeamId(), e.getCreatedByTeamId());
    }

    private static void requireWrite(HttpSession session, IncidentRecord e) {
        if (!canWrite(session, e)) throw new SecurityException("Bu olay kaydı sizin takım(lar)ınıza ait değil");
    }

    private static String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u == null ? null : String.valueOf(u);
    }

    static Long sessionUserId(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v instanceof Number n) return Long.valueOf(n.longValue());
        try { return v == null ? null : Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static String safe(String s) {
        if (s == null) return "";
        String t = s.length() > 120 ? s.substring(0, 120) : s;
        return t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
