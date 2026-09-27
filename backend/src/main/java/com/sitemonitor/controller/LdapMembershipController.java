package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.LdapMembershipService;
import com.sitemonitor.service.LdapProvisioningService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takım üyeliği kaynağı + AD ile karşılaştırma/yeniden eşitleme (prod hatası 2026-09-26).
 *
 * <ul>
 *   <li>{@code GET  /api/admin/users/{id}/team-membership} — üyelikler + kaynak izi (yalnız veritabanı).
 *       Global görücü ya da kullanıcının takımlarından birini GÖREBİLEN kapsamlı rol.</li>
 *   <li>{@code GET  /api/admin/users/{id}/ldap-check} — canlı AD karşılaştırması (salt okunur).</li>
 *   <li>{@code POST /api/admin/users/{id}/ldap-resync} — AD'den yeniden eşitle; {@code USER_LDAP_RESYNC}.</li>
 *   <li>{@code GET  /api/admin/teams/{id}/ldap-check} — takımın her üyesi için kaynak + AD desteği.</li>
 *   <li>{@code POST /api/admin/teams/{id}/ldap-resync} — takımın kilitsiz LDAP üyelerini eşitle;
 *       üye başına {@code USER_LDAP_RESYNC} + özet {@code TEAM_LDAP_RESYNC}.</li>
 * </ul>
 * AD'ye giden dört uç YALNIZ global yöneticiye açık ({@link SessionScope#isGlobalAdmin}): AD niteliklerini
 * (DN, company, grup DN'leri) gösterir ve üyelik/rol değiştirir — kapsamlı müdür (AD ADMIN) geçemez.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class LdapMembershipController {

    private final LdapMembershipService membershipService;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @GetMapping("/users/{id}/team-membership")
    public ResponseEntity<Map<String, Object>> membership(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "users.list", "view");
        AppUser u = membershipService.requireUser(id);
        requireCanView(session, u);
        return ok(membershipService.membership(u));
    }

    @GetMapping("/users/{id}/ldap-check")
    public ResponseEntity<Map<String, Object>> userCheck(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.list", "view");
        return ok(membershipService.check(membershipService.requireUser(id)));
    }

    @PostMapping("/users/{id}/ldap-resync")
    public ResponseEntity<Map<String, Object>> userResync(@PathVariable Long id, HttpSession session,
                                                          HttpServletRequest request) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.crud", "edit");
        AppUser u = membershipService.requireUser(id);
        LdapProvisioningService.SyncResult r = membershipService.resync(u);
        auditService.recordAction("USER_LDAP_RESYNC", session, request, "USER", String.valueOf(id), detailOf(r, null));
        return ok(summary(r));
    }

    @GetMapping("/teams/{id}/ldap-check")
    public ResponseEntity<Map<String, Object>> teamCheck(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.list", "view");
        return ok(membershipService.checkTeam(id));
    }

    @PostMapping("/teams/{id}/ldap-resync")
    public ResponseEntity<Map<String, Object>> teamResync(@PathVariable Long id, HttpSession session,
                                                          HttpServletRequest request) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.crud", "edit");
        List<Map<String, Object>> results = membershipService.resyncTeam(id);
        int ok = 0, failed = 0, skipped = 0, removedFromTeam = 0;
        for (Map<String, Object> r : results) {
            String st = String.valueOf(r.get("status"));
            if ("OK".equals(st)) {
                ok++;
                if (Boolean.FALSE.equals(r.get("still_member"))) removedFromTeam++;
                auditService.recordAction("USER_LDAP_RESYNC", session, request, "USER", String.valueOf(r.get("user_id")),
                        AuditDetail.of("username", r.get("username"), "via_team", id,
                                "teams_before", String.valueOf(r.get("teams_before")),
                                "teams_after", String.valueOf(r.get("teams_after")),
                                "manager_sicil_before", r.get("manager_sicil_before"),
                                "manager_sicil_after", r.get("manager_sicil_after"),
                                "manager_id_before", r.get("manager_id_before"),
                                "manager_id_after", r.get("manager_id_after")));
            } else if ("FAILED".equals(st)) failed++;
            else skipped++;
        }
        auditService.recordAction("TEAM_LDAP_RESYNC", session, request, "TEAM", String.valueOf(id),
                AuditDetail.of("members", results.size(), "ok", ok, "failed", failed, "skipped", skipped,
                        "removed_from_team", removedFromTeam));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", ok);
        data.put("failed", failed);
        data.put("skipped", skipped);
        data.put("removed_from_team", removedFromTeam);
        data.put("results", results);
        return ok(data);
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────

    private static String detailOf(LdapProvisioningService.SyncResult r, Long viaTeam) {
        AppUser u = r.user();
        return AuditDetail.of("username", u.getUsername(), "via_team", viaTeam,
                "team_locked", Boolean.TRUE.equals(u.getTeamLocked()),
                "teams_before", String.valueOf(r.teamsBefore()),
                "teams_after", String.valueOf(r.teamsAfter()),
                "manager_sicil_before", r.managerSicilBefore(),
                "manager_sicil_after", u.getManagerSicil(),
                "manager_id_before", r.managerIdBefore(),
                "manager_id_after", u.getManagerId());
    }

    private static Map<String, Object> summary(LdapProvisioningService.SyncResult r) {
        AppUser u = r.user();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getId());
        m.put("username", u.getUsername());
        m.put("team_locked", Boolean.TRUE.equals(u.getTeamLocked()));
        m.put("teams_before", r.teamsBefore());
        m.put("teams_after", r.teamsAfter());
        m.put("manager_sicil_before", r.managerSicilBefore());
        m.put("manager_sicil_after", u.getManagerSicil());
        m.put("manager_id_before", r.managerIdBefore());
        m.put("manager_id_after", u.getManagerId());
        return m;
    }

    /** AD'ye giden uçlar: yalnız GLOBAL yönetici (kapsamlı müdür ADMIN rolüyle gelse de geçemez). */
    private static void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException("Global admin required");
        }
    }

    /** Global görücü her kullanıcıyı; kapsamlı rol yalnız görüş kapsamındaki bir takımın üyesini görür (dışı 404). */
    private static void requireCanView(HttpSession session, AppUser u) {
        if (SessionScope.isGlobalViewer(session)) return;
        List<Long> scope = SessionScope.viewTeamIds(session);
        boolean visible = scope != null && (
                (u.getTeamId() != null && scope.contains(u.getTeamId()))
                || (u.getTeamIds() != null && u.getTeamIds().stream().anyMatch(scope::contains)));
        if (!visible) throw new java.util.NoSuchElementException("User not found: " + u.getId());
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
