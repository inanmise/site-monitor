package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.techerror.TechErrorTeamTemplateService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorVisibilityService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Takım ŞABLON KÜTÜPHANESİ uçları (2026-10-18, kullanıcı kararı D17). Kütüphane takımın KENDİ işidir: okuma takımın kendi
 * kapsamı (kurum geneli görüntüleyici, görüş / yönetim kapsamı ya da üyelik — erişim paylaşımı AÇMAZ; paylaşımla gören
 * 404 alır), yazma kanal açma / şablon kaydıyla AYNI kural ({@link TechErrorReportController#canWriteOwn}: global yönetici,
 * takımı yöneten ya da üye — AUDIT okur, yazamaz → 403). Görülemeyen takım 404 {@code TEAM_NOT_FOUND}; başka takımın
 * kaydı 404 (varlığı sızmaz). İzin matrisi kanal raporlarıyla aynı: okuma {@code tech_error_reports.read/view}, yazma
 * {@code tech_error_reports.crud/edit}; modül görünürlüğü (S6) uygulanır.
 *
 * <p>Liste satırı şablon gövdesini taşımaz; tek kayıt okuması taşır. Güncelleme yalnız gönderilen alanları değiştirir,
 * okunan sürümle ({@code version} — eskiyse 409 {@code CONCURRENT_MODIFICATION}); değişiklik yoksa hiçbir şey yazılmaz
 * ve denetlenmez. Kullanıcı kimliği verilmez — yalnız ad.
 */
@RestController
@RequestMapping("/api/tech-error-reports")
public class TechErrorTeamTemplateController {

    private final TechErrorTeamTemplateService library;
    private final TeamRepository teams;
    private final PermissionService permissions;
    private final AuditService auditService;

    public TechErrorTeamTemplateController(TechErrorTeamTemplateService library, TeamRepository teams,
                                           PermissionService permissions, AuditService auditService) {
        this.library = library;
        this.teams = teams;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    @GetMapping("/teams/{teamId}/templates")
    public ResponseEntity<Map<String, Object>> list(@PathVariable Long teamId, HttpSession session) {
        requireRead(session);
        Team team = readableTeam(teamId, session);
        if (team == null) return teamNotFound();
        boolean canEdit = canEdit(session, teamId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TechErrorTeamTemplateRepository.Meta m : library.list(teamId)) rows.add(rowDto(m, canEdit));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team_id", team.getId());
        data.put("team_name", team.getName());
        data.put("templates", rows);
        data.put("max", TechErrorTeamTemplateService.MAX_PER_TEAM);
        data.put("can_edit", canEdit);
        return ok(data);
    }

    @GetMapping("/teams/{teamId}/templates/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long teamId, @PathVariable Long id, HttpSession session) {
        requireRead(session);
        if (readableTeam(teamId, session) == null) return teamNotFound();
        TechErrorTeamTemplate e = library.get(teamId, id);
        Map<String, Object> m = entryDto(e, canEdit(session, teamId));
        m.put("template", TechErrorTemplate.toMap(TechErrorTeamTemplateService.templateOf(e)));
        return ok(m);
    }

    @PostMapping("/teams/{teamId}/templates")
    public ResponseEntity<Map<String, Object>> create(@PathVariable Long teamId, @RequestBody(required = false) Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        if (readableTeam(teamId, session) == null) return teamNotFound();
        requireOwnWrite(session, teamId);
        Map<String, Object> b = body == null ? Map.of() : body;
        Template t = TechErrorTemplate.normalize(b.get("template"));
        boolean makeDefault = boolOf(b.get("default"));
        TechErrorTeamTemplate e = library.create(teamId, str(b.get("name")), t, makeDefault, userId(session));
        auditService.recordAction("TECH_ERROR_TEAM_TEMPLATE_CREATE", session, request, "TEAM", String.valueOf(teamId),
                AuditDetail.of("team_id", teamId, "template_id", e.getId(), "name", e.getName(), "default", e.isDefaultEntry(),
                        "sections", e.getSectionCount(), "fields", e.getFieldCount(), "slots", e.getSlotCount()));
        return ok(entryDto(e, true));
    }

    @PutMapping("/teams/{teamId}/templates/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long teamId, @PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        if (readableTeam(teamId, session) == null) return teamNotFound();
        requireOwnWrite(session, teamId);
        Map<String, Object> b = body == null ? Map.of() : body;
        Long version = b.get("version") instanceof Number n ? Long.valueOf(n.longValue()) : null;
        boolean nameSent = b.containsKey("name");
        Template t = b.containsKey("template") ? TechErrorTemplate.normalize(b.get("template")) : null;
        Boolean makeDefault = b.containsKey("default") ? Boolean.valueOf(boolOf(b.get("default"))) : null;
        String nameBefore = nameSent ? library.get(teamId, id).getName() : null;
        TechErrorTeamTemplateService.UpdateResult res = library.update(teamId, id, version, nameSent, str(b.get("name")), t,
                makeDefault, userId(session));
        TechErrorTeamTemplate e = res.entry();
        if (res.isChanged()) {
            auditService.recordAction("TECH_ERROR_TEAM_TEMPLATE_UPDATE", session, request, "TEAM", String.valueOf(teamId),
                    AuditDetail.of("team_id", teamId, "template_id", e.getId(), "name", e.getName(),
                            "name_before", res.changed().contains("name") ? nameBefore : null,
                            "default", e.isDefaultEntry(), "fields", String.join(",", res.changed())));
        }
        Map<String, Object> m = entryDto(e, true);
        m.put("changed", res.isChanged());
        return ok(m);
    }

    @DeleteMapping("/teams/{teamId}/templates/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long teamId, @PathVariable Long id, HttpSession session,
                                                      HttpServletRequest request) {
        requireWrite(session);
        if (readableTeam(teamId, session) == null) return teamNotFound();
        requireOwnWrite(session, teamId);
        TechErrorTeamTemplate e = library.delete(teamId, id);
        auditService.recordAction("TECH_ERROR_TEAM_TEMPLATE_DELETE", session, request, "TEAM", String.valueOf(teamId),
                AuditDetail.of("team_id", teamId, "template_id", e.getId(), "name", e.getName(), "was_default", e.isDefaultEntry()));
        return ok(Map.of("deleted", true));
    }

    // ── Kapsam ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Kütüphaneyi OKUYABİLİR mi: kurum geneli görüntüleyici ya da takımın KENDİ kapsamı (görüş / yönetim / üyelik; modül
     * görünürlüğü dahil). Erişim paylaşımı sayılmaz (kütüphane takımın işi — 9. sürüm kuralı: yönetim alanları paylaşımla
     * açılmaz).
     */
    static boolean canReadLibrary(HttpSession session, Long teamId) {
        if (teamId == null) return false;
        Set<Long> own = TechErrorReportController.ownVisibleTeamIds(session);
        return own == null || own.contains(teamId);
    }

    private boolean canEdit(HttpSession session, Long teamId) {
        return permissions.allows(session, TechErrorReportController.CRUD, "edit") && TechErrorReportController.canWriteOwn(session, teamId);
    }

    private Team readableTeam(Long teamId, HttpSession session) {
        if (teamId == null || !canReadLibrary(session, teamId)) return null;
        return teams.findById(teamId).orElse(null);
    }

    private static void requireOwnWrite(HttpSession session, Long teamId) {
        if (!TechErrorReportController.canWriteOwn(session, teamId)) {
            throw new SecurityException(Msg.t(
                    "Takımın şablon kütüphanesini yalnız takımın üyeleri ve yöneticileri değiştirebilir.",
                    "Only the team's members and managers can change the team's template library."));
        }
    }

    private void requireRead(HttpSession session) {
        if (!permissions.allows(session, TechErrorReportController.READ, "view")) {
            throw new SecurityException(TechErrorReportController.READ + " yetkisi gerekli");
        }
        requireModuleVisible(session);
    }

    private void requireWrite(HttpSession session) {
        if (!permissions.allows(session, TechErrorReportController.CRUD, "edit")) {
            throw new SecurityException(TechErrorReportController.CRUD + " yetkisi gerekli");
        }
        requireModuleVisible(session);
    }

    private static void requireModuleVisible(HttpSession session) {
        TechErrorVisibilityService v = TechErrorVisibilityService.current();
        if (v != null && !v.sessionSees(session)) {
            throw new SecurityException(Msg.t(
                    "Kanal teknik hata raporu modülü takımınız için açık değil. Gerekiyorsa sistem yöneticisi Ayarlar → Teknik hata raporu'ndan açar.",
                    "The channel technical error report module is not enabled for your team. A system administrator can enable it under Settings → Technical error report."));
        }
    }

    // ── DTO ──────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> rowDto(TechErrorTeamTemplateRepository.Meta m, boolean canEdit) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("team_id", m.getTeamId());
        r.put("name", m.getName());
        r.put("is_default", Boolean.TRUE.equals(m.getDefaultEntry()));
        r.put("lang", m.getTemplateLang());
        r.put("section_count", m.getSectionCount());
        r.put("field_count", m.getFieldCount());
        r.put("slot_count", m.getSlotCount());
        r.put("created_at", iso(m.getCreatedAt()));
        r.put("created_by_name", m.getCreatedByName());
        r.put("updated_at", iso(m.getUpdatedAt()));
        r.put("updated_by_name", m.getUpdatedByName());
        r.put("version", m.getVersion());
        r.put("can_edit", canEdit);
        return r;
    }

    private static Map<String, Object> entryDto(TechErrorTeamTemplate e, boolean canEdit) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", e.getId());
        r.put("team_id", e.getTeamId());
        r.put("name", e.getName());
        r.put("is_default", e.isDefaultEntry());
        r.put("lang", e.getTemplateLang());
        r.put("section_count", e.getSectionCount());
        r.put("field_count", e.getFieldCount());
        r.put("slot_count", e.getSlotCount());
        r.put("created_at", iso(e.getCreatedAt()));
        r.put("created_by_name", e.getCreatedByName());
        r.put("updated_at", iso(e.getUpdatedAt()));
        r.put("updated_by_name", e.getUpdatedByName());
        r.put("version", e.getVersion());
        r.put("can_edit", canEdit);
        return r;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private static boolean boolOf(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean bo) return bo;
        throw new FieldValidationException("default", Msg.t(
                "“Takım varsayılanı” açık ya da kapalı olmalı.", "“Team default” must be on or off."));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Long userId(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v instanceof Number n) return Long.valueOf(n.longValue());
        try { return v == null ? null : Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static ResponseEntity<Map<String, Object>> teamNotFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "TEAM_NOT_FOUND");
        body.put("error", Msg.t(
                "Takım bulunamadı; silinmiş olabilir ya da bu takımın şablon kütüphanesini görme yetkiniz yok. Listeyi yenileyin.",
                "The team wasn't found; it may have been deleted or you can't see this team's template library. Refresh the list."));
        String rid = MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        return ResponseEntity.status(404).body(body);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
