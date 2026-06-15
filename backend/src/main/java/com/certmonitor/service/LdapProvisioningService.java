package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the AD attributes of a freshly-authenticated user into our AppUser/Team
 * model (Faz 3a). Called from the login flow after a successful LDAP bind.
 *
 * <p>Phase-3a role rules (safe interim): {@code company} contains "PRODUCT OWNER"
 * → orgRole=PO + systemRole=TEAM_ADMIN; everyone else (managers included) → USER.
 * Manager → scoped ADMIN and PO multi-team visibility land in Faz 3b; this phase
 * only records the relationships (managerId, mudurluk, team) so 3b can use them.
 */
@Slf4j
@Service
public class LdapProvisioningService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final LdapDirectoryService directory;

    public LdapProvisioningService(AppUserRepository userRepo, TeamRepository teamRepo,
                                   LdapDirectoryService directory) {
        this.userRepo = userRepo;
        this.teamRepo = teamRepo;
        this.directory = directory;
    }

    /** Provision/refresh the authenticated user from AD; resolves team + manager. */
    @Transactional
    public AppUser provisionFromAd(String username, String dn, Map<String, Object> attrs) {
        AppUser user = upsert(username, attrs, true);
        return user;
    }

    /**
     * Core mapping. {@code resolveManager=false} when provisioning a manager record
     * recursively (avoids walking the management chain indefinitely).
     */
    private AppUser upsert(String username, Map<String, Object> attrs, boolean resolveManager) {
        String uname = username.trim();
        String now = now();
        AppUser u = userRepo.findByUsername(uname).orElseGet(AppUser::new);
        boolean isNew = (u.getId() == null);
        if (isNew) {
            u.setUsername(uname);
            u.setPasswordHash(null);     // AD users keep no app password
            u.setAuthSource("LDAP");
            u.setActive(true);
            u.setCreatedAt(now);
        }

        // ── Profile fields ──
        u.setEmail(str(attrs, "mail"));
        u.setEmployeeId(str(attrs, "cn"));                 // Sicil No
        u.setFirstName(str(attrs, "givenName"));
        u.setLastName(str(attrs, "sn"));
        u.setDisplayName(orElse(str(attrs, "displayName"), uname));
        u.setTitle(str(attrs, "title"));
        u.setPhone(str(attrs, "mobile"));
        u.setDepartment(str(attrs, "department"));
        u.setCompanyLevel(str(attrs, "description"));
        u.setPhotoBase64(stripBase64Prefix(str(attrs, "thumbnailPhoto")));

        // ── Müdürlük (extensionAttribute5 = "ID;Name") ──
        applyMudurluk(u, str(attrs, "extensionAttribute5"));

        boolean isPo = containsCi(str(attrs, "company"), "PRODUCT OWNER");
        if (isPo) u.setOrgRole("PO");                      // don't clobber existing orgRole with null

        u.setUpdatedAt(now);
        u = userRepo.save(u);                              // ensure id before role/scope checks

        // ── Role (Faz 3b): müdür → ADMIN; PO → TEAM_ADMIN; else USER ──
        // A user is a müdür if provisioned via the recursive manager path (resolveManager=false)
        // OR if someone in the DB reports to them.
        boolean isManager = !resolveManager || userRepo.existsByManagerId(u.getId());
        applyRole(u, isManager ? "ADMIN" : (isPo ? "TEAM_ADMIN" : "USER"));

        // ── Team (memberOf under OU=ScrumGroups) ──
        resolveTeam(u, attrs, isPo);

        // ── Manager (extensionAttribute4 / manager → CN=sicil) ──
        if (resolveManager) {
            resolveManagerLink(u, attrs);
        }

        u.setUpdatedAt(now);
        return userRepo.save(u);
    }

    /** Applies the AD-derived role without downgrading a manually-elevated ADMIN/AUDIT. */
    private void applyRole(AppUser u, String desired) {
        if ("ADMIN".equals(desired)) { u.setSystemRole("ADMIN"); return; }
        String cur = u.getSystemRole();
        if (!"ADMIN".equals(cur) && !"AUDIT".equals(cur)) u.setSystemRole(desired);
    }

    private void resolveTeam(AppUser u, Map<String, Object> attrs, boolean isPo) {
        // A user can be in several ScrumGroups; the team is the group whose CN does NOT
        // end with "Onaycı" (those are approver groups, not teams). When both an
        // "..._Onayci" group and a plain group exist, the plain one is the team. The
        // mail of that exact group DN becomes the team e-mail (read via groupMail).
        String scrumDn = memberOfList(attrs).stream()
                .filter(dn -> containsCi(dn, "OU=ScrumGroups"))
                .filter(dn -> !isApproverCn(cnOf(dn)))
                .findFirst().orElse(null);
        if (scrumDn == null) return;
        String teamName = cnOf(scrumDn);
        if (teamName == null || teamName.isBlank()) return;

        Team team = teamRepo.findByName(teamName).orElseGet(() -> {
            Team t = new Team();
            t.setName(teamName);
            t.setEmail(directory.groupMail(scrumDn).orElse(null));
            t.setActive(true);
            t.setCreatedAt(now());
            t.setUpdatedAt(now());
            return teamRepo.save(t);
        });
        // Backfill team mail if it was created before group lookup worked.
        if ((team.getEmail() == null || team.getEmail().isBlank())) {
            directory.groupMail(scrumDn).ifPresent(m -> { team.setEmail(m); teamRepo.save(team); });
        }
        u.setTeamId(team.getId());
        if (isPo && (team.getLeaderId() == null)) {
            team.setLeaderId(u.getId());
            team.setUpdatedAt(now());
            teamRepo.save(team);
        }
    }

    private void resolveManagerLink(AppUser u, Map<String, Object> attrs) {
        String sicil = cnOf(orElse(str(attrs, "extensionAttribute4"), str(attrs, "manager")));
        if (sicil == null || sicil.isBlank()) return;
        u.setManagerSicil(sicil);
        // Already in our DB?
        Optional<AppUser> mgr = userRepo.findByEmployeeId(sicil);
        if (mgr.isEmpty()) {
            // Look the manager up in AD by cn=sicil and provision a minimal record.
            Optional<Map<String, Object>> mgrAttrs = directory.findOne("cn", sicil);
            if (mgrAttrs.isPresent()) {
                String mgrUsername = str(mgrAttrs.get(), "sAMAccountName");
                if (mgrUsername != null && !mgrUsername.isBlank()) {
                    mgr = Optional.of(upsert(mgrUsername, mgrAttrs.get(), false)); // no deeper recursion
                }
            }
        }
        mgr.filter(m -> !m.getId().equals(u.getId())).ifPresent(m -> u.setManagerId(m.getId()));
    }

    private void applyMudurluk(AppUser u, String ext5) {
        if (ext5 == null || !ext5.contains(";")) return;
        String[] parts = ext5.split(";", 2);
        try {
            u.setMudurlukId(Long.parseLong(parts[0].trim()));
        } catch (NumberFormatException ignored) {
            u.setMudurlukId(null);
        }
        if (parts.length > 1) u.setMudurlukName(parts[1].trim());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Extracts the CN value from a DN, e.g. "CN=63535,OU=..." → "63535". */
    static String cnOf(String dn) {
        if (dn == null) return null;
        int i = indexOfCi(dn, "CN=");
        if (i < 0) return null;
        int start = i + 3;
        int end = dn.indexOf(',', start);
        String cn = (end < 0) ? dn.substring(start) : dn.substring(start, end);
        return cn.trim();
    }

    /** True when a group CN denotes an approver ("Onaycı") group rather than a team,
     *  e.g. "SY-Dijital Bankacilik_Onayci". Such groups are skipped for team naming. */
    static boolean isApproverCn(String cn) {
        if (cn == null) return false;
        String c = cn.trim().toLowerCase(java.util.Locale.ROOT);
        return c.endsWith("onayci") || c.endsWith("onaycı");
    }

    @SuppressWarnings("unchecked")
    static List<String> memberOfList(Map<String, Object> attrs) {
        Object v = caseInsensitiveGet(attrs, "memberOf");
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String one && !one.isBlank()) return List.of(one);
        return List.of();
    }

    static String stripBase64Prefix(String v) {
        if (v == null) return null;
        return v.startsWith("base64:") ? v.substring("base64:".length()) : v;
    }

    /** Case-insensitive single-value attribute read (first element if multi-valued). */
    static String str(Map<String, Object> attrs, String key) {
        Object v = caseInsensitiveGet(attrs, key);
        if (v == null) return null;
        String val = (v instanceof List<?> list)
                ? (list.isEmpty() ? null : String.valueOf(list.get(0)))
                : String.valueOf(v);
        if (val == null || val.isBlank() || "null".equals(val)) return null;
        return val;
    }

    private static Object caseInsensitiveGet(Map<String, Object> attrs, String key) {
        if (attrs == null || key == null) return null;
        Object direct = attrs.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static boolean containsCi(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static int indexOfCi(String s, String sub) {
        return s.toLowerCase().indexOf(sub.toLowerCase());
    }

    private static String orElse(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String now() {
        return ISO.format(Instant.now());
    }
}
