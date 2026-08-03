package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class LdapProvisioningService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final LdapDirectoryService directory;
    private final EscalationContactRepository contactRepo;
    private final AppSettingsService appSettings;

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
        String uname = com.certmonitor.service.UserService.normalizeUsername(username);  // hep BÜYÜK harf
        String now = now();
        AppUser u = userRepo.findByUsername(uname).orElseGet(AppUser::new);   // case-insensitive → eski satırı bulur
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
        applyOrgRole(u, isPo);                             // PO > D6->MANAGER > D7->BOLUM_BASKANI > TECH (kilitliyse dokunmaz)

        u.setUpdatedAt(now);
        u = userRepo.save(u);                              // ensure id before role/scope checks

        // ── Role (Faz 3b): müdür → ADMIN; PO → TEAM_ADMIN; else USER ──
        // A user is a müdür if provisioned via the recursive manager path (resolveManager=false)
        // OR if someone in the DB reports to them.
        boolean isManager = !resolveManager || userRepo.existsByManagerId(u.getId());
        applyRole(u, isManager ? "ADMIN" : (isPo ? "TEAM_ADMIN" : "USER"));

        // ── Takım(lar): memberOf (OU=ScrumGroups) → boşsa company fallback ──
        resolveTeams(u, attrs, isPo);

        // ── Manager (extensionAttribute4 / manager → CN=sicil) ──
        if (resolveManager) {
            resolveManagerLink(u, attrs);
            // Takım↔müdür ilişkisi kurulduysa, müdürü otomatik MANAGER eskalasyon
            // kontağı yap (min seviye HIGH). Varsayılan KAPALI — yalnız
            // cert.monitor.escalation.auto-add-managers açıksa (ensure içinde canlı okunur).
            ensureManagerEscalationContact(u.getTeamId(), u.getManagerId());
        }

        u.setUpdatedAt(now);
        return userRepo.save(u);
    }

    /**
     * AD'den türetilen rolü uygular. Rol admin tarafından KİLİTLENMİŞSE (role_locked) hiç dokunma —
     * manuel atanan rol (örn. USER→TEAM_ADMIN) her girişte ezilmez. Kilitsiz kullanıcılarda eski
     * davranış korunur: elle yükseltilmiş ADMIN/AUDIT düşürülmez.
     */
    private void applyRole(AppUser u, String desired) {
        if (Boolean.TRUE.equals(u.getRoleLocked())) return;   // admin manuel kilitledi → LDAP dokunmaz
        if ("ADMIN".equals(desired)) { u.setSystemRole("ADMIN"); return; }
        String cur = u.getSystemRole();
        if (!"ADMIN".equals(cur) && !"AUDIT".equals(cur)) u.setSystemRole(desired);
    }

    /**
     * AD'den organizasyonel rolü türetir: PO > seviye D6 → MANAGER > seviye D7 → BOLUM_BASKANI > TECH.
     * Admin manuel değiştirmişse (orgRoleLocked) HİÇ dokunma — manuel org rol her girişte ezilmez.
     * companyLevel = AD `description` (ör. "D6"/"D7"); word-boundary + case-insensitive eşleşme.
     */
    private void applyOrgRole(AppUser u, boolean isPo) {
        if (Boolean.TRUE.equals(u.getOrgRoleLocked())) return;   // admin manuel kilitledi → LDAP dokunmaz
        String desired = isPo ? "PO"
                : levelIs(u.getCompanyLevel(), "D6") ? "MANAGER"
                : levelIs(u.getCompanyLevel(), "D7") ? "BOLUM_BASKANI"
                : "TECH";
        u.setOrgRole(desired);
    }

    /** companyLevel içinde {@code code} (ör. "D6") tam kelime olarak (case-insensitive) geçiyor mu. */
    private static boolean levelIs(String companyLevel, String code) {
        return companyLevel != null && java.util.regex.Pattern
                .compile("\\b" + code + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(companyLevel).find();
    }

    private void resolveTeams(AppUser u, Map<String, Object> attrs, boolean isPo) {
        // Bir kullanıcı birden çok ScrumGroup'ta olabilir; takım, CN'i "Onaycı" ile bitMEYEN
        // gruplardır (onaycı grupları takım değil). TÜM uygun gruplar üyelik olur (sıra korunur);
        // her grubun mail'i (groupMail) o takımın e-postasıdır. Hiç grup yoksa company fallback.
        List<String> scrumDns = memberOfList(attrs).stream()
                .filter(dn -> containsCi(dn, "OU=ScrumGroups"))
                .filter(dn -> !isApproverCn(cnOf(dn)))
                .toList();

        java.util.LinkedHashSet<Long> teamIds = new java.util.LinkedHashSet<>();
        for (String dn : scrumDns) {
            String teamName = cnOf(dn);
            if (teamName == null || teamName.isBlank()) continue;
            Team team = findOrCreateTeam(teamName, dn);
            teamIds.add(team.getId());
            if (isPo) assignLeaderIfVacant(team, u);
        }

        // Fallback: grup üyeliğinden HİÇ takım çıkmadıysa company attribute'undan çıkar.
        if (teamIds.isEmpty()) {
            for (String teamName : companyTeamNames(str(attrs, "company"))) {
                Team team = findOrCreateTeam(teamName, null);   // DN yok → e-posta null
                teamIds.add(team.getId());
                if (isPo) assignLeaderIfVacant(team, u);
            }
        }

        if (teamIds.isEmpty()) return;                 // takımsız kal
        u.setTeamIds(teamIds);
        u.setTeamId(teamIds.iterator().next());        // birincil = ilk çözülen
    }

    /** Takımı adıyla bul, yoksa oluştur. {@code dnForMail} verilirse grubun AD mail'i takım e-postası olur. */
    private Team findOrCreateTeam(String teamName, String dnForMail) {
        Team team = teamRepo.findByName(teamName).orElseGet(() -> {
            Team t = new Team();
            t.setName(teamName);
            t.setEmail(dnForMail != null ? directory.groupMail(dnForMail).orElse(null) : null);
            t.setActive(true);
            t.setCreatedAt(now());
            t.setUpdatedAt(now());
            return teamRepo.save(t);
        });
        // E-postası boşsa ve bir grup DN'imiz varsa sonradan doldur.
        if (dnForMail != null && (team.getEmail() == null || team.getEmail().isBlank())) {
            directory.groupMail(dnForMail).ifPresent(m -> { team.setEmail(m); teamRepo.save(team); });
        }
        return team;
    }

    private void assignLeaderIfVacant(Team team, AppUser po) {
        if (team.getLeaderId() == null) {
            team.setLeaderId(po.getId());
            team.setUpdatedAt(now());
            teamRepo.save(team);
        }
    }

    /**
     * AD {@code company} attribute'undan takım adlarını çıkarır (grup üyeliği boşken fallback).
     * Format: {@code "<ROL>-<TAKIM1>[,<TAKIM2>...]"}. Takım adları iç tire içerir
     * (ör. "SY-MevduatMuhasebeSigorta"), bu yüzden yalnız İLK tireden böl (rol önekini at), kalanı
     * virgülle ayır. Örnekler:
     * <pre>
     *   "PRODUCT OWNER-SY-MevduatMuhasebeSigorta,SY-Dijital Mobil Servis"
     *        → [SY-MevduatMuhasebeSigorta, SY-Dijital Mobil Servis]
     *   "YAZILIM UZMANI-SY-MevduatMuhasebeSigorta" → [SY-MevduatMuhasebeSigorta]
     * </pre>
     */
    static List<String> companyTeamNames(String company) {
        if (company == null) return List.of();
        int dash = company.indexOf('-');
        if (dash < 0 || dash + 1 >= company.length()) return List.of();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String part : company.substring(dash + 1).split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
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

    /**
     * Takımın müdürünü otomatik olarak MANAGER eskalasyon kontağı yapar (min seviye HIGH).
     * VARSAYILAN KAPALI (cert.monitor.escalation.auto-add-managers=false): D7+ müdürler
     * eskalasyona OTOMATİK eklenmez; Admin/Takım PO'su isterse manuel ekler. Admin ayarı
     * açarsa eski davranış döner. Aynı takım+MANAGER+e-posta kontağı zaten varsa
     * (aktif/pasif) tekrar eklenmez (idempotent). Mevcut kayıtlar ayar kapansa da silinmez.
     */
    private void ensureManagerEscalationContact(Long teamId, Long managerId) {
        if (!appSettings.getBoolean("cert.monitor.escalation.auto-add-managers", false)) return;
        if (teamId == null || managerId == null) return;
        AppUser mgr = userRepo.findById(managerId).orElse(null);
        if (mgr == null || mgr.getEmail() == null || mgr.getEmail().isBlank()) return;
        String email = mgr.getEmail().trim();
        boolean exists = contactRepo.findByTeamIdOrderByRoleAsc(teamId).stream()
                .anyMatch(c -> "MANAGER".equals(c.getRole()) && email.equalsIgnoreCase(c.getEmail()));
        if (exists) return;
        EscalationContact c = new EscalationContact();
        c.setTeamId(teamId);
        c.setRole("MANAGER");
        c.setUserId(mgr.getId());
        c.setName((mgr.getDisplayName() != null && !mgr.getDisplayName().isBlank())
                ? mgr.getDisplayName() : mgr.getUsername());
        c.setEmail(email);
        c.setMinAlertLevel("HIGH");
        c.setActive(true);
        c.setCreatedAt(now());
        contactRepo.save(c);
        log.info("Takım müdürü otomatik MANAGER eskalasyon kontağı eklendi: team={} manager='{}' <{}>",
                teamId, c.getName(), email);
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
