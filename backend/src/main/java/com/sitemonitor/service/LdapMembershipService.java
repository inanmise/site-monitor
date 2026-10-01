package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * "Bu kişi bu takıma NEDEN üye, AD hâlâ destekliyor mu?" — tanılama + onarım (prod hatası 2026-09-26).
 *
 * <ul>
 *   <li>{@link #membership} — YALNIZ veritabanı: her üyeliğin kaynağı (AD grubu / company / elle / taşıma /
 *       kaynak kaydı yok). LDAP'a gitmez; kullanıcı detay kartı açılırken çağrılır.</li>
 *   <li>{@link #check} — canlı AD karşılaştırması, SALT OKUNUR: AD'nin bugün türettiği takımlar, sayılmayan
 *       gruplar ve sebebi, her mevcut üyeliğin AD desteği ve yeniden eşitlemede / girişte ne olacağı,
 *       müdür niteliklerinin (ea4 / manager) ayrı ayrı gösterdiği sicil.</li>
 *   <li>{@link #resync} / {@link #resyncTeam} — yöneticinin onayladığı düzeltme: giriş provizyonunun aynısı +
 *       takım kilidi yoksa üyelik AD ile BİREBİR eşitlenir. Kilitli (elle düzenlenmiş) üyeliklere dokunulmaz.</li>
 * </ul>
 * Veritabanı elle düzenlenmez; her düzeltme denetime yazılır (controller).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LdapMembershipService {

    /** Üyeliğin kaynak izi yoksa (bu özellikten önce oluşmuş) gösterilen kod. */
    public static final String LEGACY = "LEGACY";

    /** Takım denetiminde tek istekte AD'ye sorulacak üye tavanı. */
    static final int TEAM_CHECK_MAX = 200;

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final LdapDirectoryService directory;
    private final LdapProvisioningService provisioning;
    private final TeamMembershipSourceService teamSources;

    public AppUser requireUser(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
    }

    /** Veritabanındaki üyelikler + kaynak izleri (LDAP'a gitmez). */
    public Map<String, Object> membership(AppUser u) {
        Map<Long, UserTeamSource> src = teamSources.sourcesOf(u.getId());
        Map<Long, String> names = teamNames(LdapProvisioningService.ownTeams(u));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long t : LdapProvisioningService.ownTeams(u)) {
            UserTeamSource s = src.get(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", t);
            m.put("team_name", names.get(t));
            m.put("primary", Objects.equals(u.getTeamId(), t));
            // Birincil takım üyelik tablosunda yoksa (eski tek-takım kaydı) bunu ayrıca söyle.
            m.put("legacy_primary_only", Objects.equals(u.getTeamId(), t)
                    && (u.getTeamIds() == null || !u.getTeamIds().contains(t)));
            m.put("source", s != null ? s.getSource() : LEGACY);
            m.put("detail", s != null ? s.getDetail() : null);
            m.put("updated_at", s != null ? s.getUpdatedAt() : null);
            m.put("updated_by", s != null ? s.getUpdatedBy() : null);
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", u.getId());
        out.put("username", u.getUsername());
        out.put("auth_source", u.getAuthSource());
        out.put("team_locked", Boolean.TRUE.equals(u.getTeamLocked()));
        out.put("memberships", rows);
        return out;
    }

    /** Canlı AD karşılaştırması — HİÇBİR ŞEY YAZMAZ. */
    public Map<String, Object> check(AppUser u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", u.getId());
        out.put("username", u.getUsername());
        out.put("team_locked", Boolean.TRUE.equals(u.getTeamLocked()));
        boolean ldap = "LDAP".equalsIgnoreCase(u.getAuthSource());
        out.put("ldap_user", ldap);
        if (!ldap) {
            out.put("found", false);
            out.put("reason", "LOCAL_ACCOUNT");
            out.put("memberships", membership(u).get("memberships"));
            return out;
        }
        Optional<Map<String, Object>> attrs = directory.findUser(u.getUsername());
        out.put("found", attrs.isPresent());
        if (attrs.isEmpty()) {
            out.put("reason", "NOT_FOUND_OR_AMBIGUOUS");
            out.put("memberships", membership(u).get("memberships"));
            return out;
        }
        Map<String, Object> a = attrs.get();
        out.put("dn", a.get("_dn"));
        out.put("company", LdapProvisioningService.str(a, "company"));

        // AD'nin bugün türettiği takımlar (DB'de olmayan ad → yeniden eşitlemede OLUŞTURULUR).
        List<LdapProvisioningService.DerivedTeam> derived = LdapProvisioningService.deriveTeams(a);
        LinkedHashMap<Long, LdapProvisioningService.DerivedTeam> derivedIds = new LinkedHashMap<>();
        List<Map<String, Object>> derivedRows = new ArrayList<>();
        for (LdapProvisioningService.DerivedTeam d : derived) {
            Long id = teamRepo.findByName(d.name()).map(Team::getId).orElse(null);
            if (id != null) derivedIds.putIfAbsent(id, d);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_name", d.name());
            m.put("team_id", id);
            m.put("source", d.source());
            m.put("detail", d.detail());
            derivedRows.add(m);
        }
        out.put("derived", derivedRows);
        List<Map<String, Object>> ignored = new ArrayList<>();
        for (LdapProvisioningService.IgnoredGroup g : LdapProvisioningService.ignoredGroups(a)) {
            ignored.add(Map.of("dn", g.dn(), "reason", g.reason()));
        }
        out.put("ignored_groups", ignored);

        boolean locked = Boolean.TRUE.equals(u.getTeamLocked());
        Map<Long, UserTeamSource> src = teamSources.sourcesOf(u.getId());
        List<Long> current = LdapProvisioningService.ownTeams(u);
        Map<Long, String> names = teamNames(current);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long t : current) {
            UserTeamSource s = src.get(t);
            String source = s != null ? s.getSource() : LEGACY;
            boolean supported = derivedIds.containsKey(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", t);
            m.put("team_name", names.get(t));
            m.put("primary", Objects.equals(u.getTeamId(), t));
            m.put("source", source);
            m.put("detail", s != null ? s.getDetail() : null);
            m.put("supported_by_ad", supported);
            m.put("on_resync", locked ? "KEEP_LOCKED" : supported ? "KEEP" : "REMOVE");
            // Girişte budama AYARA bağlı (A1-D1): ayar kapalıyken LDAP kaynaklı üyelik de KEEP — gerçek girişle
            // (LdapProvisioningService.applyTeams) aynı dal; eskiden tahmin ayarı okumuyordu.
            m.put("on_login", locked ? "KEEP_LOCKED" : supported ? "KEEP"
                    : !derived.isEmpty() ? "REMOVE"
                    : UserTeamSource.LDAP_SOURCES.contains(source) && provisioning.pruneUnsupportedTeams() ? "REMOVE" : "KEEP");
            rows.add(m);
        }
        out.put("memberships", rows);
        // Elle kilitli AD alanları (2026-09-29): "AD ile karşılaştır" bu alanların eşitlemede ATLANACAĞINI söyler.
        out.put("locked_fields", com.sitemonitor.model.LdapFieldLocks.keys(u.getLockedFields()));
        List<Map<String, Object>> toAdd = new ArrayList<>();
        for (Map<String, Object> d : derivedRows) {
            Object id = d.get("team_id");
            if (id == null || !current.contains((Long) id)) {
                Map<String, Object> m = new LinkedHashMap<>(d);
                m.put("on_resync", locked ? "NOT_APPLIED_LOCKED" : "ADD");
                toAdd.add(m);
            }
        }
        out.put("to_add", toAdd);
        Map<String, Object> manager = managerCheck(u, a);
        // Alan başına AD ↔ uygulama karşılaştırması (2026-09-30): kilitli alan eşitlemede atlanır, "farklı" olan
        // kilitsiz alan bir sonraki girişte AD değerine döner. Gizli değer yok (parola LDAP hesabında tutulmaz).
        out.put("fields", fieldRows(u, a, manager));
        out.put("manager", manager);
        return out;
    }

    /**
     * {@link com.sitemonitor.model.LdapFieldLocks#FIELDS} kanonik sırasıyla {@code {key, ad, local, locked, differs}}.
     * AD tarafı {@link LdapProvisioningService#upsert} ile AYNI nitelikten okunur (mail/cn/givenName/sn/displayName/
     * title/mobile/department/description/extensionAttribute5 ad kısmı/müdür sicili); müdür satırı managerCheck'in
     * çözdüğü sicilleri kullanır.
     */
    private List<Map<String, Object>> fieldRows(AppUser u, Map<String, Object> a, Map<String, Object> manager) {
        Set<String> locked = com.sitemonitor.model.LdapFieldLocks.of(u);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : com.sitemonitor.model.LdapFieldLocks.FIELDS) {
            String ad;
            String local;
            switch (key) {
                case com.sitemonitor.model.LdapFieldLocks.DISPLAY_NAME  -> { ad = LdapProvisioningService.str(a, "displayName"); local = u.getDisplayName(); }
                case com.sitemonitor.model.LdapFieldLocks.EMAIL         -> { ad = LdapProvisioningService.str(a, "mail"); local = u.getEmail(); }
                case com.sitemonitor.model.LdapFieldLocks.EMPLOYEE_ID   -> { ad = LdapProvisioningService.str(a, "cn"); local = u.getEmployeeId(); }
                case com.sitemonitor.model.LdapFieldLocks.FIRST_NAME    -> { ad = LdapProvisioningService.str(a, "givenName"); local = u.getFirstName(); }
                case com.sitemonitor.model.LdapFieldLocks.LAST_NAME     -> { ad = LdapProvisioningService.str(a, "sn"); local = u.getLastName(); }
                case com.sitemonitor.model.LdapFieldLocks.TITLE         -> { ad = LdapProvisioningService.str(a, "title"); local = u.getTitle(); }
                case com.sitemonitor.model.LdapFieldLocks.PHONE         -> { ad = LdapProvisioningService.str(a, "mobile"); local = u.getPhone(); }
                case com.sitemonitor.model.LdapFieldLocks.DEPARTMENT    -> { ad = LdapProvisioningService.str(a, "department"); local = u.getDepartment(); }
                case com.sitemonitor.model.LdapFieldLocks.COMPANY_LEVEL -> { ad = LdapProvisioningService.str(a, "description"); local = u.getCompanyLevel(); }
                case com.sitemonitor.model.LdapFieldLocks.MUDURLUK      -> { ad = mudurlukNameOf(LdapProvisioningService.str(a, "extensionAttribute5")); local = u.getMudurlukName(); }
                case com.sitemonitor.model.LdapFieldLocks.MANAGER       -> { ad = (String) manager.get("ad_sicil"); local = (String) manager.get("db_sicil"); }
                default -> { continue; }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("ad", ad);
            m.put("local", local);
            m.put("locked", locked.contains(key));
            m.put("differs", !Objects.equals(norm(ad), norm(local)));
            rows.add(m);
        }
        return rows;
    }

    /** {@code "ID;Ad"} → {@code "Ad"} (LdapProvisioningService.applyMudurluk'un yazdığı kısım); ayraç yoksa ham değer. */
    private static String mudurlukNameOf(String ext5) {
        if (ext5 == null) return null;
        int i = ext5.indexOf(';');
        String name = i < 0 ? ext5 : ext5.substring(i + 1);
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    /** Müdür bağının AD'ye göre durumu: nitelik başına sicil, çözülen kişi, DB'deki ile fark. */
    private Map<String, Object> managerCheck(AppUser u, Map<String, Object> attrs) {
        List<String> order = provisioning.managerAttributeOrder();
        Map<String, String> cands = LdapProvisioningService.managerCandidates(attrs, order);
        String resolved = LdapProvisioningService.managerSicilOf(attrs, order);
        Optional<AppUser> resolvedUser = resolved == null ? Optional.empty() : ManagerLookup.resolve(userRepo, resolved, u.getId());
        AppUser current = u.getManagerId() == null ? null : userRepo.findById(u.getManagerId()).orElse(null);
        Set<String> distinct = new LinkedHashSet<>();
        for (String v : cands.values()) if (v != null) distinct.add(v.trim().toUpperCase(java.util.Locale.ROOT));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attribute_order", order);
        m.put("candidates", cands);
        m.put("attributes_disagree", distinct.size() > 1);
        m.put("ad_sicil", resolved);
        m.put("ad_manager_id", resolvedUser.map(AppUser::getId).orElse(null));
        m.put("ad_manager_name", resolvedUser.map(LdapMembershipService::nameOf).orElse(null));
        m.put("db_sicil", u.getManagerSicil());
        m.put("db_manager_id", u.getManagerId());
        m.put("db_manager_name", current != null ? nameOf(current) : null);
        m.put("db_manager_employee_id", current != null ? current.getEmployeeId() : null);
        // manager_id'nin gösterdiği kişinin sicili manager_sicil ile aynı mı (eski kodda ayrışabiliyordu).
        m.put("db_consistent", current == null ? u.getManagerSicil() == null || resolvedUserless(u)
                : current.getEmployeeId() != null && u.getManagerSicil() != null
                    && current.getEmployeeId().trim().equalsIgnoreCase(u.getManagerSicil().trim()));
        m.put("matches_ad", Objects.equals(norm(resolved), norm(u.getManagerSicil()))
                && Objects.equals(resolvedUser.map(AppUser::getId).orElse(null), u.getManagerId()));
        return m;
    }

    /** manager_id boşken sicil DB'de hiç kimseye çözülmüyorsa bu tutarlıdır (müdür uygulamada yok). */
    private boolean resolvedUserless(AppUser u) {
        return ManagerLookup.resolve(userRepo, u.getManagerSicil(), u.getId()).isEmpty();
    }

    /** Tek kullanıcıyı AD'den yeniden eşitle (yönetici onaylı). */
    public LdapProvisioningService.SyncResult resync(AppUser u) {
        if (!"LDAP".equalsIgnoreCase(u.getAuthSource())) {
            throw new IllegalArgumentException(com.sitemonitor.util.Msg.t(
                    "Yerel hesap AD'den eşitlenemez.", "A local account cannot be re-synced from AD."));
        }
        Map<String, Object> attrs = directory.findUser(u.getUsername()).orElseThrow(() -> new IllegalStateException(
                com.sitemonitor.util.Msg.t("Kullanıcı AD'de bulunamadı ya da birden çok kayıt eşleşti.",
                        "The user was not found in AD, or more than one entry matched.")));
        return provisioning.resyncFromAd(u.getUsername(), attrs);
    }

    /** Takımın üyeleri (birincil ya da ek üyelik), aktif/pasif hepsi. */
    public List<AppUser> members(Long teamId) {
        return userRepo.findMembersOfTeams(List.of(teamId));
    }

    /** Takım denetimi: her üyenin bu takımdaki üyeliğinin kaynağı + AD desteği (salt okunur). */
    public Map<String, Object> checkTeam(Long teamId) {
        Team team = teamRepo.findById(teamId).orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));
        List<AppUser> members = members(teamId);
        Map<Long, Map<Long, UserTeamSource>> src = teamSources.sourcesOfUsers(members.stream().map(AppUser::getId).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        int checked = 0;
        for (AppUser u : members) {
            UserTeamSource s = src.getOrDefault(u.getId(), Map.of()).get(teamId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", u.getId());
            m.put("username", u.getUsername());
            m.put("display_name", nameOf(u));
            m.put("active", Boolean.TRUE.equals(u.getActive()));
            m.put("auth_source", u.getAuthSource());
            m.put("team_locked", Boolean.TRUE.equals(u.getTeamLocked()));
            m.put("primary", Objects.equals(u.getTeamId(), teamId));
            m.put("source", s != null ? s.getSource() : LEGACY);
            m.put("detail", s != null ? s.getDetail() : null);
            m.put("manager_id", u.getManagerId());
            m.put("manager_sicil", u.getManagerSicil());
            if ("LDAP".equalsIgnoreCase(u.getAuthSource()) && checked < TEAM_CHECK_MAX) {
                checked++;
                Map<String, Object> c = check(u);
                m.put("ldap_found", c.get("found"));
                Object mem = c.get("memberships");
                if (mem instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> row && Objects.equals(row.get("team_id"), teamId)) {
                            m.put("supported_by_ad", row.get("supported_by_ad"));
                            m.put("on_resync", row.get("on_resync"));
                            m.put("on_login", row.get("on_login"));
                        }
                    }
                }
            } else {
                m.put("ldap_found", null);
                m.put("on_resync", "KEEP");
            }
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team_id", team.getId());
        out.put("team_name", team.getName());
        out.put("members", rows);
        out.put("checked", checked);
        out.put("truncated", members.stream().filter(x -> "LDAP".equalsIgnoreCase(x.getAuthSource())).count() > checked);
        return out;
    }

    /** Takımın kilitsiz LDAP üyelerini tek tek yeniden eşitle; biri düşerse diğerleri sürer. */
    public List<Map<String, Object>> resyncTeam(Long teamId) {
        teamRepo.findById(teamId).orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));
        List<Map<String, Object>> results = new ArrayList<>();
        for (AppUser u : members(teamId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("user_id", u.getId());
            r.put("username", u.getUsername());
            if (!"LDAP".equalsIgnoreCase(u.getAuthSource())) { r.put("status", "SKIPPED_LOCAL"); results.add(r); continue; }
            if (Boolean.TRUE.equals(u.getTeamLocked())) { r.put("status", "SKIPPED_LOCKED"); results.add(r); continue; }
            try {
                LdapProvisioningService.SyncResult s = resync(u);
                r.put("status", "OK");
                r.put("teams_before", s.teamsBefore());
                r.put("teams_after", s.teamsAfter());
                r.put("manager_sicil_before", s.managerSicilBefore());
                r.put("manager_sicil_after", s.user().getManagerSicil());
                r.put("manager_id_before", s.managerIdBefore());
                r.put("manager_id_after", s.user().getManagerId());
                r.put("still_member", s.teamsAfter().contains(teamId));
            } catch (RuntimeException e) {
                r.put("status", "FAILED");
                r.put("error", e.getMessage());
            }
            results.add(r);
        }
        return results;
    }

    private Map<Long, String> teamNames(List<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (Team t : teamRepo.findAllById(ids)) out.put(t.getId(), t.getName());
        return out;
    }

    static String nameOf(AppUser u) {
        if (u == null) return null;
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        return u.getUsername();
    }

    private static String norm(String s) {
        return s == null ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
