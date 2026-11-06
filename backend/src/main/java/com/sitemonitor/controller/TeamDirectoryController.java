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
 * Kullanıcı kararı 2026-09-28: takım üyeleri penceresinde SİSTEM ROLÜ ve FOTOĞRAF görünür — projeksiyona
 * {@code system_role} + {@code has_photo} (fotoğrafın kendisi değil; olmayan için boşuna istek atılmasın) eklendi.
 * Telefon, sicil no, fotoğraf base64 ve LDAP alanları DÖNMEZ.
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

    /** 7/24 izleme ekibi takımları (2026-10-04) — satırın "7/24" rozeti; isteğe bağlı (birim testte yok → hep false). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocOperatorService nocOperators;

    /** Tüm takımlar: [{id, name, active, email, leader_id, leader_display_name}] — ad→id çözümü için. */
    @GetMapping("/directory")
    public ResponseEntity<Map<String, Object>> directory() {
        List<Team> teams = teamRepo.findAll();
        Set<Long> leaderIds = new HashSet<>();
        for (Team t : teams) if (t.getLeaderId() != null) leaderIds.add(t.getLeaderId());
        Map<Long, AppUser> leaders = new HashMap<>();
        if (!leaderIds.isEmpty()) for (AppUser u : userRepo.findAllById(leaderIds)) leaders.put(u.getId(), u);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> nocTeams = new HashSet<>();
        try { if (nocOperators != null) nocTeams.addAll(nocOperators.teamIds()); } catch (Exception ignored) { /* rozet yok */ }
        for (Team t : teams) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("active", t.getActive());
            m.put("email", t.getEmail());
            m.put("leader_id", t.getLeaderId());
            m.put("manager_id", t.getManagerId());
            m.put("leader_display_name", t.getLeaderId() != null ? displayName(leaders.get(t.getLeaderId())) : null);
            m.put("noc_team", nocTeams.contains(t.getId()));   // 7/24 izleme ekibi takımı mı (rozet)
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("success", true, "data", out));
    }

    /**
     * Takım üyeleri (beyaz-listeli) + takımın eskalasyon kişileri. Üyelik yüklemi
     * AdminController.listTeamUsers ile aynı: birincil takım ya da çoklu-takım üyeliği. Bilinmeyen takım → 404.
     *
     * <p><b>Pasif üyeler (2026-10-02, kullanıcı kararı):</b> artık GİZLENMEZ — her satır {@code active} bayrağı taşır,
     * pasifler aktiflerden SONRA sıralanır ve arayüz onları belirgin "Pasif" rozetiyle gösterir. Bu ucun tüketicileri
     * yalnız GÖRÜNTÜLEME listeleridir (takım üyeleri penceresi; Takım Yönetimi buradan yalnız takım satırını ve kişileri
     * okur) — seçici / atama / alıcı kaynağı DEĞİLDİR. Seçiciler aktif-yalnız kalır (kendi kaynaklarında süzerler).
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<Map<String, Object>> members(@PathVariable Long id) {
        Optional<Team> teamOpt = teamRepo.findById(id);
        if (teamOpt.isEmpty()) return ResponseEntity.notFound().build();
        Team team = teamOpt.get();

        // Performans 2026-11-06 (PF2 #10): yalnız takımın üyeleri, dar sütunlarla (fotoğraf / takım koleksiyonu okunmaz);
        // müdür, lider ve bağlı eskalasyon kişisi adları tek kimlik listesiyle. Eskiden TÜM kullanıcılar tam varlık okunuyordu.
        List<Object[]> memberRows = userRepo.findTeamMemberDirectoryRows(id);
        List<EscalationContact> contactRows = contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(id);
        Set<Long> nameIds = new HashSet<>();
        for (Object[] r : memberRows) if (r[11] != null) nameIds.add(((Number) r[11]).longValue());
        if (team.getLeaderId() != null) nameIds.add(team.getLeaderId());
        for (EscalationContact c : contactRows) if (c.getUserId() != null) nameIds.add(c.getUserId());
        Map<Long, NameRow> byId = new HashMap<>();
        if (!nameIds.isEmpty()) for (Object[] r : userRepo.findNameRowsByIds(nameIds)) {
            NameRow n = NameRow.of(r);
            if (n.id() != null) byId.put(n.id(), n);
        }

        List<Map<String, Object>> members = new ArrayList<>();
        for (Object[] r : memberRows) members.add(project(r, byId));
        // Aktifler önce, pasifler sonda; her grupta ada göre.
        members.sort(Comparator.comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("active")))
                .thenComparing(m -> String.valueOf(m.get("display_name")), String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> contacts = new ArrayList<>();
        for (EscalationContact c : contactRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("email", c.getEmail());
            m.put("role", c.getRole());
            m.put("min_alert_level", c.getMinAlertLevel());
            // Bağlı kullanıcı pasif mi (2026-10-02): pasif kişi BİLDİRİM ALMAZ (InactiveRecipientGuard) — arayüz üye
            // listesindeki gibi belirgin "Pasif" rozeti çizer. Bağsız (serbest metin) kişi → null; kimlik sızdırılmaz.
            NameRow linked = c.getUserId() == null ? null : byId.get(c.getUserId());
            m.put("user_active", linked == null ? null : Boolean.TRUE.equals(linked.active()));
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

    /** Ad çözümü için dar satır ({@code AppUserRepository.findNameRowsByIds}): id, username, displayName, firstName, lastName, active. */
    record NameRow(Long id, String username, String displayName, String firstName, String lastName, Boolean active) {
        static NameRow of(Object[] r) {
            return new NameRow(r[0] instanceof Number n ? n.longValue() : null, (String) r[1], (String) r[2], (String) r[3],
                    (String) r[4], (Boolean) r[5]);
        }
    }

    /**
     * Beyaz-liste projeksiyonu — dar satırdan ({@code AppUserRepository.findTeamMemberDirectoryRows}). Anahtarlar ve sıra eski
     * tam varlık projeksiyonuyla BİREBİR (yanıt sözleşmesi değişmez). Yeni alan eklerken kurum-geneli görünürlüğü düşün
     * (telefon/sicil YOK).
     */
    static Map<String, Object> project(Object[] r, Map<Long, NameRow> byId) {
        Long id = r[0] instanceof Number n ? n.longValue() : null;
        Long managerId = r[11] instanceof Number n ? n.longValue() : null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", com.sitemonitor.service.userref.UserRef.of(id));   // global olmayana opak kimlik (UserRefWire)
        m.put("username", r[1]);
        m.put("display_name", displayName((String) r[2], (String) r[3], (String) r[4], (String) r[1]));
        m.put("first_name", r[3]);
        m.put("last_name", r[4]);
        m.put("org_role", r[5]);
        m.put("title", r[6]);
        m.put("department", r[7]);
        m.put("mudurluk_name", r[8]);
        m.put("company_level", r[9]);
        m.put("email", r[10]);
        m.put("manager_id", managerId);
        m.put("manager_display_name", managerId != null ? displayName(byId.get(managerId)) : null);
        m.put("system_role", r[12]);
        m.put("has_photo", Boolean.TRUE.equals(r[14]));
        m.put("active", Boolean.TRUE.equals(r[13]));   // 2026-10-02: pasif üye listede, belirgin rozetle
        return m;
    }

    private static String displayName(AppUser u) {
        if (u == null) return null;
        return displayName(u.getDisplayName(), u.getFirstName(), u.getLastName(), u.getUsername());
    }

    private static String displayName(NameRow u) {
        if (u == null) return null;
        return displayName(u.displayName(), u.firstName(), u.lastName(), u.username());
    }

    private static String displayName(String display, String first, String last, String username) {
        if (display != null && !display.isBlank()) return display;
        String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        return full.isEmpty() ? username : full;
    }
}
