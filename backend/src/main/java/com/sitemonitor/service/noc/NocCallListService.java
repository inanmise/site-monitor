package com.sitemonitor.service.noc;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NocTeamCallEntry;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NocTeamCallEntryRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Takımın 7/24 ARAMA LİSTESİ — NOC bir sorun görünce takımdan kimi, hangi sırayla arar.
 *
 * <p><b>Telefon gizliliği:</b> {@code AppUser.phone} (AD {@code mobile}) YALNIZ NOC e-postasına girer
 * ({@link #forMail}). Arayüze dönen hiçbir biçimde telefon yoktur — yalnız {@code has_phone}. Kurum-geneli
 * takım rehberinin ({@code /api/teams/{id}/members}) beyaz listesi bu özellikle DEĞİŞMEDİ.
 *
 * <p><b>Liste boşsa</b> e-postada Takım Müdürü (+ telefonu) ve "arama listesi tanımlanmamış" notu yer alır.
 * Müdür çözümü: (1) takıma elle atanmış müdür ({@code Team.managerId}); (2) takımın MANAGER rollü eskalasyon
 * kişisinin e-postasıyla eşleşen aktif kullanıcı; (3) üyelerin AD müdür zinciri — {@code utils/teamManager.js}
 * kuralının sade hâli: üyelerin bağlı olduğu, kendisi takım üyesi/lideri OLMAYAN adaylardan, başka bir adayın
 * zincirinde ÜSTÜ olanlar düşer; en yakın kademe, sonra en çok doğrudan bağlı, sonra ad sırası.
 */
@Service
@RequiredArgsConstructor
public class NocCallListService {

    /** Arama listesi tavanı — e-postada okunur kalsın. */
    public static final int MAX_ENTRIES = 25;
    private static final int MAX_CHAIN = 8;
    private static final Map<String, Integer> RANK = Map.of("MANAGER", 0, "BOLUM_BASKANI", 2, "CLEVEL", 3);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocTeamCallEntryRepository callRepo;
    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final EscalationContactRepository contactRepo;

    // ── Üyelik ───────────────────────────────────────────────────────────────

    /** Aktif ÜYE mi — birincil takım ya da çoklu üyelik ({@code app_user_teams}); TeamDirectory ile aynı yüklem. */
    public static boolean isActiveMember(AppUser u, Long teamId) {
        if (u == null || teamId == null || !Boolean.TRUE.equals(u.getActive())) return false;
        return teamId.equals(u.getTeamId()) || (u.getTeamIds() != null && u.getTeamIds().contains(teamId));
    }

    /** Takımın aktif üyeleri (ada göre sıralı). */
    public List<AppUser> activeMembers(Long teamId) {
        List<AppUser> out = new ArrayList<>();
        for (AppUser u : userRepo.findMembersOfTeams(List.of(teamId))) if (isActiveMember(u, teamId)) out.add(u);
        out.sort(Comparator.comparing(NocCallListService::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ── API biçimleri (telefon YOK) ──────────────────────────────────────────

    /** Arama listesi seçicisi: [{user_id, display_name, title, has_phone}]. */
    public List<Map<String, Object>> membersDto(Long teamId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppUser u : activeMembers(teamId)) out.add(personDto(u, true));
        return out;
    }

    /** Kayıtlı sıra: [{user_id, display_name, title, has_phone, is_member}]. Üyeliği düşen kişi {@code is_member=false}. */
    public List<Map<String, Object>> callListDto(Long teamId) {
        List<NocTeamCallEntry> entries = callRepo.findByTeamIdOrderByPositionAsc(teamId);
        Map<Long, AppUser> users = usersById(entries.stream().map(NocTeamCallEntry::getUserId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (NocTeamCallEntry e : entries) {
            AppUser u = users.get(e.getUserId());
            if (u == null) continue;   // kullanıcı silinmiş → sessizce atla (gönderim de atlar)
            out.add(personDto(u, isActiveMember(u, teamId)));
        }
        return out;
    }

    static Map<String, Object> personDto(AppUser u, boolean member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getId());
        m.put("display_name", displayName(u));
        m.put("title", u.getTitle());
        m.put("has_phone", u.getPhone() != null && !u.getPhone().isBlank());
        m.put("is_member", member);
        return m;
    }

    // ── Yazma ────────────────────────────────────────────────────────────────

    /**
     * Listeyi SIRASIYLA baştan yazar. Kişiler takımın AKTİF üyesi olmalı (değilse 400 — başka takımdan birinin
     * numarası NOC e-postasına sızmasın). Tekrarlar ilk konumunda kalır.
     */
    @Transactional
    public List<Map<String, Object>> replace(Long teamId, List<Long> userIds, String actor) {
        if (teamRepo.findById(teamId).isEmpty()) throw new java.util.NoSuchElementException("Takım bulunamadı");
        List<Long> ordered = new ArrayList<>(new LinkedHashSet<>(userIds == null ? List.<Long>of() : userIds));
        ordered.removeIf(java.util.Objects::isNull);
        if (ordered.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Arama listesinde en fazla " + MAX_ENTRIES + " kişi olabilir");
        Map<Long, AppUser> users = usersById(ordered);
        for (Long id : ordered) {
            if (!isActiveMember(users.get(id), teamId))
                throw new IllegalArgumentException("Kişi bu takımın aktif üyesi değil: " + id);
        }
        callRepo.deleteByTeamId(teamId);
        callRepo.flush();
        String now = ISO.format(Instant.now());
        int pos = 0;
        List<NocTeamCallEntry> rows = new ArrayList<>();
        for (Long id : ordered) {
            NocTeamCallEntry e = new NocTeamCallEntry();
            e.setTeamId(teamId);
            e.setUserId(id);
            e.setPosition(pos++);
            e.setUpdatedAt(now);
            e.setUpdatedBy(actor);
            rows.add(e);
        }
        callRepo.saveAll(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ordered) out.add(personDto(users.get(id), true));
        return out;
    }

    /** Arama listesini düzenleyebilir mi: takım yöneticisi (yönetim kapsamı), elle atanmış müdür ya da lider. */
    public boolean isTeamManagerOrLeader(Long teamId, Long userId) {
        if (teamId == null || userId == null) return false;
        return teamRepo.findById(teamId)
                .map(t -> userId.equals(t.getManagerId()) || userId.equals(t.getLeaderId()))
                .orElse(false);
    }

    // ── E-posta için (telefon DAHİL) ─────────────────────────────────────────

    /**
     * NOC e-postasının takım bölümü: sıralı arama listesi (yalnız hâlâ aktif üye olanlar), Takım Müdürü ve
     * eskalasyon kişileri. Telefon canlı okunur.
     */
    public NocMailComposer.TeamBlock forMail(Long teamId) {
        if (teamId == null) return NocMailComposer.TeamBlock.none();
        Team team = teamRepo.findById(teamId).orElse(null);
        if (team == null) return NocMailComposer.TeamBlock.none();
        List<NocTeamCallEntry> entries = callRepo.findByTeamIdOrderByPositionAsc(teamId);
        Map<Long, AppUser> users = usersById(entries.stream().map(NocTeamCallEntry::getUserId).toList());
        List<NocMailComposer.Person> calls = new ArrayList<>();
        for (NocTeamCallEntry e : entries) {
            AppUser u = users.get(e.getUserId());
            if (isActiveMember(u, teamId)) calls.add(person(u));
        }
        NocMailComposer.Person manager = resolveManager(team).map(NocCallListService::person).orElse(null);
        List<NocMailComposer.Contact> esc = new ArrayList<>();
        for (EscalationContact c : contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(teamId)) {
            esc.add(new NocMailComposer.Contact(c.getName(), roleLabel(c.getRole()), c.getEmail()));
        }
        return new NocMailComposer.TeamBlock(team.getName(), calls, !entries.isEmpty() && !calls.isEmpty(), manager, esc);
    }

    static NocMailComposer.Person person(AppUser u) {
        return new NocMailComposer.Person(displayName(u), u.getTitle(), u.getPhone());
    }

    static String roleLabel(String role) {
        if (role == null) return null;
        return switch (role.toUpperCase(Locale.ROOT)) {
            case "PO" -> "Ürün Sahibi (PO)";
            case "TECH" -> "Teknik Sorumlu";
            case "MANAGER" -> "Müdür";
            case "CLEVEL" -> "Üst Yönetim";
            default -> role;
        };
    }

    /** Takım Müdürü — sınıf javadoc'undaki üç halka. */
    public Optional<AppUser> resolveManager(Team team) {
        if (team.getManagerId() != null) {
            Optional<AppUser> m = userRepo.findById(team.getManagerId()).filter(u -> Boolean.TRUE.equals(u.getActive()));
            if (m.isPresent()) return m;
        }
        Set<String> emails = new HashSet<>();
        for (EscalationContact c : contactRepo.findByTeamIdAndRoleAndActiveTrue(team.getId(), "MANAGER"))
            if (c.getEmail() != null && !c.getEmail().isBlank()) emails.add(c.getEmail().trim().toLowerCase(Locale.ROOT));
        if (!emails.isEmpty()) {
            List<AppUser> found = userRepo.findActiveByEmailsLower(emails);
            if (!found.isEmpty()) return Optional.of(found.get(0));
        }
        return adChainManager(team);
    }

    private Optional<AppUser> adChainManager(Team team) {
        List<AppUser> members = activeMembers(team.getId());
        Set<Long> memberIds = new HashSet<>();
        for (AppUser u : members) memberIds.add(u.getId());
        Map<Long, Integer> directReports = new HashMap<>();
        for (AppUser u : members) {
            Long mid = u.getManagerId();
            if (mid == null || memberIds.contains(mid) || mid.equals(team.getLeaderId())) continue;
            directReports.merge(mid, 1, Integer::sum);
        }
        if (directReports.isEmpty()) return Optional.empty();
        Map<Long, AppUser> cands = usersById(new ArrayList<>(directReports.keySet()));
        cands.values().removeIf(u -> !Boolean.TRUE.equals(u.getActive()));
        // Başka bir adayın zincirinde ÜSTÜ olan aday düşer (bölüm başkanı, müdürün üstü).
        Set<Long> ancestors = new HashSet<>();
        for (AppUser c : cands.values()) {
            Long up = c.getManagerId();
            for (int i = 0; i < MAX_CHAIN && up != null; i++) {
                if (cands.containsKey(up)) ancestors.add(up);
                AppUser next = cands.containsKey(up) ? cands.get(up) : userRepo.findById(up).orElse(null);
                up = next == null ? null : next.getManagerId();
            }
        }
        return cands.values().stream()
                .filter(u -> !ancestors.contains(u.getId()))
                .min(Comparator.<AppUser>comparingInt(u -> RANK.getOrDefault(
                                u.getOrgRole() == null ? "" : u.getOrgRole().toUpperCase(Locale.ROOT), 1))
                        .thenComparing(u -> -directReports.getOrDefault(u.getId(), 0))
                        .thenComparing(NocCallListService::displayName, String.CASE_INSENSITIVE_ORDER));
    }

    private Map<Long, AppUser> usersById(List<Long> ids) {
        Map<Long, AppUser> m = new HashMap<>();
        if (ids == null || ids.isEmpty()) return m;
        for (AppUser u : userRepo.findAllById(ids)) if (u.getId() != null) m.put(u.getId(), u);
        return m;
    }

    public static String displayName(AppUser u) {
        if (u == null) return "";
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return full.isEmpty() ? (u.getUsername() == null ? "" : u.getUsername()) : full;
    }
}
