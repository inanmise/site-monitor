package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Kurum-geneli TAKIM rehberi — UserDirectoryController'ın takım eşleniği. Oturum açmış herkes
 * (rol/kapsam fark etmez) takım adını tıklayıp üyelerini görebilir; bu yüzden projeksiyon
 * BEYAZ-LİSTELİDİR (kullanıcı kararı 2026-09-10): ad, unvan, birim, müdürlük, org rolü, e-posta.
 * Telefon, sicil no, sistem rolü, fotoğraf base64 ve LDAP alanları DÖNMEZ.
 *
 * <p>/api/admin/teams ve /teams/{id}/users görüş-kapsamlı kalır (yönetim ekranı, tam entity);
 * bu uç yalnız "kim bu takımda?" sorusuna cevap verir. Fotoğraf mevcut /api/users/{id}/photo'dan.
 */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamDirectoryController {

    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final EscalationContactRepository contactRepo;

    /** Tüm takımlar: [{id, name, active, email, leader_id, leader_display_name}] — ad→id çözümü için. */
    @GetMapping("/directory")
    public ResponseEntity<Map<String, Object>> directory() {
        List<Team> teams = teamRepo.findAll();
        Set<Long> leaderIds = new HashSet<>();
        for (Team t : teams) if (t.getLeaderId() != null) leaderIds.add(t.getLeaderId());
        Map<Long, AppUser> leaders = new HashMap<>();
        if (!leaderIds.isEmpty()) for (AppUser u : userRepo.findAllById(leaderIds)) leaders.put(u.getId(), u);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Team t : teams) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("active", t.getActive());
            m.put("email", t.getEmail());
            m.put("leader_id", t.getLeaderId());
            m.put("leader_display_name", t.getLeaderId() != null ? displayName(leaders.get(t.getLeaderId())) : null);
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", out));
    }

    /**
     * Takım üyeleri (beyaz-listeli) + takımın eskalasyon kişileri. Üyelik yüklemi
     * AdminController.listTeamUsers ile aynı: birincil takım ya da çoklu-takım üyeliği.
     * Yalnız AKTİF hesaplar. Bilinmeyen takım → 404.
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<Map<String, Object>> members(@PathVariable Long id) {
        Optional<Team> teamOpt = teamRepo.findById(id);
        if (teamOpt.isEmpty()) return ResponseEntity.notFound().build();
        Team team = teamOpt.get();

        List<AppUser> all = userRepo.findAll();
        Map<Long, AppUser> byId = new HashMap<>();
        for (AppUser u : all) if (u.getId() != null) byId.put(u.getId(), u);

        List<Map<String, Object>> members = new ArrayList<>();
        for (AppUser u : all) {
            if (!Boolean.TRUE.equals(u.getActive())) continue;
            boolean member = id.equals(u.getTeamId()) || (u.getTeamIds() != null && u.getTeamIds().contains(id));
            if (!member) continue;
            members.add(project(u, byId));
        }
        members.sort(Comparator.comparing(m -> String.valueOf(m.get("display_name")), String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> contacts = new ArrayList<>();
        for (EscalationContact c : contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("email", c.getEmail());
            m.put("role", c.getRole());
            m.put("min_alert_level", c.getMinAlertLevel());
            contacts.add(m);
        }

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", team.getId());
        t.put("name", team.getName());
        t.put("email", team.getEmail());
        t.put("active", team.getActive());
        t.put("leader_id", team.getLeaderId());
        t.put("leader_display_name", team.getLeaderId() != null ? displayName(byId.get(team.getLeaderId())) : null);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", t);
        data.put("members", members);
        data.put("escalation_contacts", contacts);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** Beyaz-liste projeksiyonu — yeni alan eklerken kurum-geneli görünürlüğü düşün (telefon/sicil YOK). */
    static Map<String, Object> project(AppUser u, Map<Long, AppUser> byId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("display_name", displayName(u));
        m.put("first_name", u.getFirstName());
        m.put("last_name", u.getLastName());
        m.put("org_role", u.getOrgRole());
        m.put("title", u.getTitle());
        m.put("department", u.getDepartment());
        m.put("mudurluk_name", u.getMudurlukName());
        m.put("company_level", u.getCompanyLevel());
        m.put("email", u.getEmail());
        m.put("manager_id", u.getManagerId());
        m.put("manager_display_name", u.getManagerId() != null ? displayName(byId.get(u.getManagerId())) : null);
        return m;
    }

    private static String displayName(AppUser u) {
        if (u == null) return null;
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                     + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return full.isEmpty() ? u.getUsername() : full;
    }
}
