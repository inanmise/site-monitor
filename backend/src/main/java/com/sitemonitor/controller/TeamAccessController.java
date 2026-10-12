package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TeamAccessGrant;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.access.TeamAccessGrantService;
import com.sitemonitor.service.access.TeamAccessLevel;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Takım erişim paylaşımı uçları (2026-10-12, kullanıcı isteği). Yönetim → Organizasyon → "Erişim paylaşımı".
 * <ul>
 *   <li>{@code GET /api/admin/team-access[?team=]} — paylaşımlar: global görüntüleyici (admin / AUDIT) hepsini, diğerleri
 *       YÖNETTİKLERİ takımların paylaşımlarını görür ({@code teams.sharing/view}); satırda {@code can_edit}.
 *       {@code manageable_teams} = paylaşım ekleyebileceği takımlar.</li>
 *   <li>{@code POST /api/admin/team-access}, {@code PUT …/{id}}, {@code DELETE …/{id}} — {@code teams.sharing/edit} +
 *       paylaşan takımı yönetmek ({@link SessionScope#canManage}). Denetim {@code TEAM_ACCESS_GRANT_CREATE|UPDATE|DELETE}.
 *       PUT yalnız gönderilen alanları değiştirir (düzey / bitiş / not); sahip ve alıcı değişmez.</li>
 *   <li>{@code GET /api/me/shared-with-me} — oturum sahibine açılmış takımlar (kişiye ya da üyesi olduğu takıma),
 *       etkin düzeyle; her oturum açmış kullanıcı.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TeamAccessController {

    private final TeamAccessGrantService grants;
    private final TeamAccessGrantRepository repo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @Autowired(required = false)
    private UserPublicIds userPublicIds;

    @GetMapping("/admin/team-access")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(name = "team", required = false) Long team,
                                                    HttpSession session) {
        permissionService.require(session, "teams.sharing", "view");
        boolean global = SessionScope.isGlobalViewer(session);
        List<Long> manage = SessionScope.manageTeamIds(session);
        List<TeamAccessGrant> rows;
        if (global) rows = repo.findAllByOrderByIdAsc();
        else if (manage == null || manage.isEmpty()) rows = List.of();
        else rows = repo.findByOwnerTeamIdInOrderByIdAsc(manage);
        if (team != null) rows = rows.stream().filter(g -> team.equals(g.getOwnerTeamId())).toList();
        boolean canEdit = permissionService.allows(session, "teams.sharing", "edit");
        Names names = names(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TeamAccessGrant g : rows) {
            Map<String, Object> m = grants.row(g, names.team(g.getOwnerTeamId()), names.grantee(g));
            m.put("grantee_active", names.granteeActive(g));
            m.put("owner_active", names.teamActive(g.getOwnerTeamId()));
            m.put("can_edit", canEdit && SessionScope.canManage(session, g.getOwnerTeamId()));
            out.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("grants", out);
        data.put("manageable_teams", canEdit ? manageableTeams(session) : List.of());
        data.put("max_per_team", TeamAccessGrantService.MAX_PER_OWNER);
        return ok(data);
    }

    @PostMapping("/admin/team-access")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session,
                                                      HttpServletRequest request) {
        permissionService.require(session, "teams.sharing", "edit");
        Long owner = longOf(body.get("owner_team_id"));
        requireManage(session, owner);
        Long granteeUser = body.get("grantee_user_id") == null ? null
                : UserPublicIds.resolve(userPublicIds, body.get("grantee_user_id"), session);
        TeamAccessGrantService.Input in = grants.validate(owner, str(body.get("grantee_type")), longOf(body.get("grantee_team_id")),
                granteeUser, str(body.get("level")), str(body.get("expires_on")), str(body.get("note")));
        TeamAccessGrant saved = grants.create(in, actor(session));
        auditService.recordAction("TEAM_ACCESS_GRANT_CREATE", session, request, "TEAM", String.valueOf(owner),
                AuditDetail.of("grant_id", saved.getId(), "grantee_type", saved.getGranteeType(),
                        "grantee_team_id", saved.getGranteeTeamId(), "grantee_user_id", saved.getGranteeUserId(),
                        "level", saved.getLevel(), "expires_at", saved.getExpiresAt()));
        return ok(view(saved, session));
    }

    @PutMapping("/admin/team-access/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "teams.sharing", "edit");
        TeamAccessGrant g = findVisible(id, session);
        requireManage(session, g.getOwnerTeamId());
        String beforeLevel = g.getLevel();
        String beforeExp = g.getExpiresAt();
        // Yalnız gönderilen alanlar değişir ve doğrulanır (eşzamanlı düzenleme diğer alanı ezmesin; alıcı yeniden
        // doğrulanmaz — pasifleşmiş kişinin paylaşımı da düzenlenebilir / kaldırılabilir).
        TeamAccessLevel level = body.containsKey("level") ? grants.level(str(body.get("level")))
                : TeamAccessLevel.parse(g.getLevel());
        if (level == null) level = grants.level(null);
        String expiresAt = body.containsKey("expires_on") ? grants.expiresAt(str(body.get("expires_on"))) : g.getExpiresAt();
        String note = body.containsKey("note") ? grants.note(str(body.get("note"))) : g.getNote();
        TeamAccessGrant saved = grants.update(g, level, expiresAt, note, actor(session));
        auditService.recordAction("TEAM_ACCESS_GRANT_UPDATE", session, request, "TEAM", String.valueOf(g.getOwnerTeamId()),
                AuditDetail.of("grant_id", saved.getId(), "grantee_type", saved.getGranteeType(),
                        "grantee_team_id", saved.getGranteeTeamId(), "grantee_user_id", saved.getGranteeUserId(),
                        "level_before", beforeLevel, "level", saved.getLevel(),
                        "expires_before", beforeExp, "expires_at", saved.getExpiresAt()));
        return ok(view(saved, session));
    }

    @DeleteMapping("/admin/team-access/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "teams.sharing", "edit");
        TeamAccessGrant g = findVisible(id, session);
        requireManage(session, g.getOwnerTeamId());
        grants.delete(g);
        auditService.recordAction("TEAM_ACCESS_GRANT_DELETE", session, request, "TEAM", String.valueOf(g.getOwnerTeamId()),
                AuditDetail.of("grant_id", g.getId(), "grantee_type", g.getGranteeType(),
                        "grantee_team_id", g.getGranteeTeamId(), "grantee_user_id", g.getGranteeUserId(), "level", g.getLevel()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", g.getId());
        data.put("deleted", true);
        return ok(data);
    }

    /** Oturum sahibine paylaşılmış takımlar — kişiye ya da üyesi olduğu takıma; süresi dolmuş / pasif sahip hariç. */
    @GetMapping("/me/shared-with-me")
    public ResponseEntity<Map<String, Object>> sharedWithMe(HttpSession session) {
        Object uid = session.getAttribute("userId");
        Long userId = uid instanceof Number n ? Long.valueOf(n.longValue()) : null;
        Map<Long, TeamAccessLevel> effective = grants.levelsFor(userId);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!effective.isEmpty()) {
            Set<Long> myTeams = new LinkedHashSet<>(SessionScope.memberTeamIds(session));
            List<TeamAccessGrant> mine = new ArrayList<>();
            for (TeamAccessGrant g : repo.findByOwnerTeamIdInOrderByIdAsc(effective.keySet())) {
                if (grants.expired(g)) continue;
                boolean toMe = TeamAccessGrant.TYPE_USER.equals(g.getGranteeType()) && userId.equals(g.getGranteeUserId());
                boolean toMyTeam = TeamAccessGrant.TYPE_TEAM.equals(g.getGranteeType()) && myTeams.contains(g.getGranteeTeamId());
                if (toMe || toMyTeam) mine.add(g);
            }
            Names names = names(mine);
            for (TeamAccessGrant g : mine) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("owner_team_id", g.getOwnerTeamId());
                m.put("owner_team_name", names.team(g.getOwnerTeamId()));
                m.put("via", g.getGranteeType());
                m.put("via_team_name", TeamAccessGrant.TYPE_TEAM.equals(g.getGranteeType()) ? names.team(g.getGranteeTeamId()) : null);
                m.put("level", g.getLevel());
                m.put("effective_level", effective.get(g.getOwnerTeamId()) == null ? null : effective.get(g.getOwnerTeamId()).name());
                m.put("expires_on", TeamAccessGrantService.expiresOn(g.getExpiresAt()));
                m.put("note", g.getNote());
                rows.add(m);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("grants", rows);
        return ok(data);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private Map<String, Object> view(TeamAccessGrant g, HttpSession session) {
        Names names = names(List.of(g));
        Map<String, Object> m = grants.row(g, names.team(g.getOwnerTeamId()), names.grantee(g));
        m.put("grantee_active", names.granteeActive(g));
        m.put("owner_active", names.teamActive(g.getOwnerTeamId()));
        m.put("can_edit", true);
        return m;
    }

    /**
     * Paylaşımı yükler; çağıranın LİSTEDE göremeyeceği satır (global görüntüleyici değil ve sahip takımı yönetmiyor) yok
     * sayılır → 404. Böylece başka takımların paylaşım kimlikleri 403 / 404 farkıyla taranamaz; listede görünen ama
     * yönetilemeyen satır (AUDIT) 403 alır.
     */
    private TeamAccessGrant findVisible(Long id, HttpSession session) {
        TeamAccessGrant g = repo.findById(id).orElseThrow(TeamAccessController::notFound);
        if (SessionScope.isGlobalViewer(session)) return g;
        List<Long> manage = SessionScope.manageTeamIds(session);
        if (manage == null || !manage.contains(g.getOwnerTeamId())) throw notFound();
        return g;
    }

    private void requireManage(HttpSession session, Long ownerTeamId) {
        if (ownerTeamId == null || !SessionScope.canManage(session, ownerTeamId)) {
            throw new SecurityException(Msg.t(
                    "Bu takımın paylaşımlarını yalnız takımı yöneten kişi (takım yöneticisi, müdür ya da global yönetici) değiştirebilir.",
                    "Only someone who manages this team (team admin, manager or global admin) can change its shares."));
        }
    }

    /** Paylaşım ekleyebileceği takımlar: global admin → tüm aktif takımlar; diğerleri yönettikleri aktif takımlar. */
    private List<Map<String, Object>> manageableTeams(HttpSession session) {
        List<Team> teams;
        if (SessionScope.isGlobalAdmin(session)) teams = teamRepo.findAll();
        else {
            List<Long> m = SessionScope.manageTeamIds(session);
            teams = m == null || m.isEmpty() ? List.of() : teamRepo.findAllById(m);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Team t : teams) {
            if (t == null || Boolean.FALSE.equals(t.getActive())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            out.add(m);
        }
        out.sort((a, b) -> TR.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
        return out;
    }

    private static final java.text.Collator TR = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"));

    /** Satırlardaki takım / kişi adları — iki toplu sorgu. */
    private record Names(Map<Long, Team> teams, Map<Long, AppUser> users) {
        String team(Long id) {
            Team t = id == null ? null : teams.get(id);
            return t == null ? null : t.getName();
        }
        /** Takım etkin mi — bulunamayan (silinmekte) takım {@code false}: paylaşım uygulanmaz. */
        boolean teamActive(Long id) {
            Team t = id == null ? null : teams.get(id);
            return t != null && !Boolean.FALSE.equals(t.getActive());
        }
        String grantee(TeamAccessGrant g) {
            if (TeamAccessGrant.TYPE_TEAM.equals(g.getGranteeType())) return team(g.getGranteeTeamId());
            AppUser u = g.getGranteeUserId() == null ? null : users.get(g.getGranteeUserId());
            if (u == null) return null;
            String dn = u.getDisplayName();
            return dn != null && !dn.isBlank() ? dn : u.getUsername();
        }
        /** Alıcı etkin mi (kişi aktif / takım aktif) — her satırda dolu; bulunamayan alıcı {@code false}. */
        boolean granteeActive(TeamAccessGrant g) {
            if (!TeamAccessGrant.TYPE_USER.equals(g.getGranteeType())) return teamActive(g.getGranteeTeamId());
            AppUser u = g.getGranteeUserId() == null ? null : users.get(g.getGranteeUserId());
            return u != null && !Boolean.FALSE.equals(u.getActive());
        }
    }

    private Names names(Collection<TeamAccessGrant> rows) {
        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (TeamAccessGrant g : rows) {
            teamIds.add(g.getOwnerTeamId());
            if (g.getGranteeTeamId() != null) teamIds.add(g.getGranteeTeamId());
            if (g.getGranteeUserId() != null) userIds.add(g.getGranteeUserId());
        }
        Map<Long, Team> t = new HashMap<>();
        if (!teamIds.isEmpty()) for (Team x : teamRepo.findAllById(teamIds)) t.put(x.getId(), x);
        Map<Long, AppUser> u = new HashMap<>();
        if (!userIds.isEmpty()) for (AppUser x : userRepo.findAllById(userIds)) u.put(x.getId(), x);
        return new Names(t, u);
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t("Paylaşım bulunamadı; silinmiş olabilir. Listeyi yenileyip yeniden deneyin.",
                "The share wasn't found; it may have been deleted. Refresh the list and try again."));
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long longOf(Object o) {
        if (o instanceof Number n) return Long.valueOf(n.longValue());
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
