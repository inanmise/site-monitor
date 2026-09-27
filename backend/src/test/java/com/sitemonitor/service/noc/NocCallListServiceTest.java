package com.sitemonitor.service.noc;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NocTeamCallEntry;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NocTeamCallEntryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Arama listesi: yalnız takım ÜYELERİ, sıra korunur, telefon ARAYÜZE DÖNMEZ ({@code has_phone}); e-posta için
 * telefon canlı okunur; liste yokken Takım Müdürü üç halkayla çözülür.
 */
class NocCallListServiceTest {

    private static final long TEAM = 1L;

    private final NocTeamCallEntryRepository callRepo = mock(NocTeamCallEntryRepository.class);
    private final AppUserRepository userRepo = mock(AppUserRepository.class);
    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final EscalationContactRepository contactRepo = mock(EscalationContactRepository.class);
    private final NocCallListService svc = new NocCallListService(callRepo, userRepo, teamRepo, contactRepo);

    private final Map<Long, AppUser> users = new HashMap<>();
    private Team team;

    private AppUser user(long id, String name, Long primaryTeam, Set<Long> extra, String phone, Long managerId, boolean active) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername("u" + id); u.setDisplayName(name); u.setTitle("Uzman");
        u.setTeamId(primaryTeam); u.setTeamIds(new LinkedHashSet<>(extra)); u.setPhone(phone);
        u.setManagerId(managerId); u.setActive(active);
        users.put(id, u);
        return u;
    }

    private static NocTeamCallEntry entry(long userId, int pos) {
        NocTeamCallEntry e = new NocTeamCallEntry();
        e.setTeamId(TEAM); e.setUserId(userId); e.setPosition(pos);
        return e;
    }

    @BeforeEach
    void setUp() {
        team = new Team();
        team.setId(TEAM); team.setName("Takım A");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        when(userRepo.findAllById(anyIterable())).thenAnswer(i -> {
            List<AppUser> out = new ArrayList<>();
            for (Object id : i.<Iterable<?>>getArgument(0)) if (users.containsKey(id)) out.add(users.get(id));
            return out;
        });
        when(userRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(users.get(i.<Long>getArgument(0))));
        when(userRepo.findMembersOfTeams(anyCollection())).thenAnswer(i -> users.values().stream()
                .filter(u -> NocCallListService.isActiveMember(u, TEAM) || Objects.equals(u.getTeamId(), TEAM)).toList());
        user(10, "Kişi A", TEAM, Set.of(), "+90 555 000 00 00", 90L, true);
        user(11, "Kişi B", 2L, Set.of(TEAM), "0555 000 00 01", 90L, true);   // çoklu üyelik
        user(12, "Kişi C", TEAM, Set.of(), null, 90L, true);
        user(13, "Kişi D (başka takım)", 2L, Set.of(), "0555 000 00 03", null, true);
        user(14, "Kişi E (pasif)", TEAM, Set.of(), "0555 000 00 04", 90L, false);
        user(90, "Kişi M", 5L, Set.of(), "+90 555 000 00 09", null, true);   // AD müdürü, takım üyesi DEĞİL
    }

    @Test
    @DisplayName("API biçimi telefon TAŞIMAZ — yalnız has_phone")
    void dtoHasNoPhone() {
        Map<String, Object> m = NocCallListService.personDto(users.get(10L), true);
        assertThat(m).containsOnlyKeys("user_id", "display_name", "title", "has_phone", "is_member");
        assertThat(m.get("has_phone")).isEqualTo(true);
        assertThat(NocCallListService.personDto(users.get(12L), true).get("has_phone")).isEqualTo(false);
        assertThat(m.values()).doesNotContain("+90 555 000 00 00");
    }

    @Test
    @DisplayName("yazma: sıra korunur, tekrar atılır; üye olmayan ya da pasif kişi 400")
    void replaceValidatesMembership() {
        List<Map<String, Object>> saved = svc.replace(TEAM, List.of(11L, 10L, 11L), "po");
        assertThat(saved).extracting(m -> m.get("user_id")).containsExactly(11L, 10L);
        verify(callRepo).deleteByTeamId(TEAM);
        assertThatThrownBy(() -> svc.replace(TEAM, List.of(10L, 13L), "po")).hasMessageContaining("aktif üyesi değil");
        assertThatThrownBy(() -> svc.replace(TEAM, List.of(14L), "po")).hasMessageContaining("aktif üyesi değil");
    }

    @Test
    @DisplayName("e-posta: arama listesi SIRAYLA, telefon DAHİL; takımdan ayrılan kişi atlanır")
    void forMailOrdered() {
        when(callRepo.findByTeamIdOrderByPositionAsc(TEAM)).thenReturn(List.of(entry(12, 0), entry(13, 1), entry(10, 2)));
        NocMailComposer.TeamBlock b = svc.forMail(TEAM);
        assertThat(b.teamName()).isEqualTo("Takım A");
        assertThat(b.callListDefined()).isTrue();
        assertThat(b.callList()).extracting(NocMailComposer.Person::name).containsExactly("Kişi C", "Kişi A");
        assertThat(b.callList().get(1).phone()).isEqualTo("+90 555 000 00 00");
    }

    @Test
    @DisplayName("Takım Müdürü: (1) elle atanmış müdür önce gelir")
    void managerExplicit() {
        team.setManagerId(13L);
        when(callRepo.findByTeamIdOrderByPositionAsc(TEAM)).thenReturn(List.of());
        NocMailComposer.TeamBlock b = svc.forMail(TEAM);
        assertThat(b.callListDefined()).isFalse();
        assertThat(b.manager().name()).isEqualTo("Kişi D (başka takım)");
    }

    @Test
    @DisplayName("Takım Müdürü: (2) MANAGER eskalasyon kişisinin e-postasıyla eşleşen kullanıcı")
    void managerFromContact() {
        EscalationContact c = new EscalationContact();
        c.setRole("MANAGER"); c.setEmail("mudur@example.com");
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(TEAM, "MANAGER")).thenReturn(List.of(c));
        when(userRepo.findActiveByEmailsLower(Set.of("mudur@example.com"))).thenReturn(List.of(users.get(90L)));
        assertThat(svc.forMail(TEAM).manager().name()).isEqualTo("Kişi M");
    }

    @Test
    @DisplayName("Takım Müdürü: (3) AD zinciri — üyelerin bağlı olduğu, takım dışı kişi; üstü olan aday düşer")
    void managerFromAdChain() {
        user(91, "Bölüm Başkanı", 6L, Set.of(), null, null, true);
        users.get(90L).setManagerId(91L);
        user(15, "Kişi F", TEAM, Set.of(), null, 91L, true);   // doğrudan bölüm başkanına bağlı
        assertThat(svc.forMail(TEAM).manager().name()).isEqualTo("Kişi M");
    }

    @Test
    @DisplayName("düzenleme yetkisi: elle atanmış müdür ya da lider")
    void managerOrLeader() {
        team.setManagerId(90L);
        team.setLeaderId(10L);
        assertThat(svc.isTeamManagerOrLeader(TEAM, 90L)).isTrue();
        assertThat(svc.isTeamManagerOrLeader(TEAM, 10L)).isTrue();
        assertThat(svc.isTeamManagerOrLeader(TEAM, 11L)).isFalse();
        assertThat(svc.isTeamManagerOrLeader(TEAM, null)).isFalse();
    }
}
