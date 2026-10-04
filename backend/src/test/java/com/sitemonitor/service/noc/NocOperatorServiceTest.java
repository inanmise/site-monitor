package com.sitemonitor.service.noc;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.NocSettings;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NocSettingsRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * 7/24 izleme ekibi takımları → operatör çözümü (2026-10-04): işaretli takımın AKTİF üyesi operatördür (birincil ya da
 * çoklu üyelik); takım listeden çıkınca / silinince / pasifleşince operatörlük KALKAR; önbellek TTL'i ve kayıt sonrası
 * düşürme; oturum öznitelikleri yalnız değişince yazılır; kayıt doğrulaması.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NocOperatorServiceTest {

    private static final long NOC = 10L, OTHER = 20L, GONE = 30L, PASSIVE_TEAM = 40L;

    @Mock NocSettingsRepository settingsRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;

    private NocOperatorService svc;
    private NocSettings row;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    /** Repo'nun döndürdüğü üyelik satırları — yalnız AKTİF kullanıcılar (sorgu pasifleri zaten dışlar). */
    private final List<Object[]> memberRows = new ArrayList<>();

    private static Team team(long id, String name, boolean active) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setActive(active);
        return t;
    }

    /** [id, username, displayName, first, last, primaryTeamId, membershipTeamId, role] */
    private static Object[] member(long id, String username, Long primary, Long tid) {
        return new Object[]{id, username, "Kişi " + id, null, null, primary, tid, "USER"};
    }

    @BeforeEach
    void setUp() {
        svc = new NocOperatorService(settingsRepo, teamRepo, userRepo);
        svc.clock = now::get;
        row = new NocSettings();
        row.setId(NocSettings.SINGLETON_ID);
        when(settingsRepo.findById(NocSettings.SINGLETON_ID)).thenAnswer(i -> Optional.of(row));
        when(settingsRepo.save(any(NocSettings.class))).thenAnswer(i -> i.getArgument(0));
        Map<Long, Team> teams = Map.of(
                NOC, team(NOC, "Takım NOC", true),
                OTHER, team(OTHER, "Takım B", true),
                PASSIVE_TEAM, team(PASSIVE_TEAM, "Takım Pasif", false));
        when(teamRepo.findAllById(any())).thenAnswer(i -> {
            List<Team> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) if (teams.containsKey(id)) out.add(teams.get(id));
            return out;
        });
        when(userRepo.findActiveMemberRowsOfTeams(anyCollection())).thenAnswer(i -> {
            Collection<?> wanted = i.getArgument(0);
            List<Object[]> out = new ArrayList<>();
            for (Object[] r : memberRows)
                if (wanted.contains(r[5]) || (r[6] != null && wanted.contains(r[6]))) out.add(r);
            return out;
        });
        // 1: birincil takımı NOC · 2: çoklu üyelikle NOC (birincil başka) · 3: yalnız OTHER
        memberRows.add(member(1, "op1", NOC, null));
        memberRows.add(member(2, "op2", OTHER, NOC));
        memberRows.add(member(2, "op2", OTHER, OTHER));
        memberRows.add(member(3, "kisi3", OTHER, null));
    }

    private static MockHttpSession session(long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("userId", userId);
        s.setAttribute("username", "u" + userId);
        return s;
    }

    @Test
    @DisplayName("işaretli takımın aktif üyesi (birincil VEYA çoklu üyelik) operatör; başka takım değil; seçim yoksa kimse")
    void membershipMakesOperator() {
        assertThat(svc.isOperator(1L)).isFalse();   // henüz takım seçilmedi
        row.setOperatorTeamIds(String.valueOf(NOC));
        svc.invalidate();
        assertThat(svc.isOperator(1L)).isTrue();
        assertThat(svc.isOperator(2L)).isTrue();
        assertThat(svc.operatorTeamsOf(2L)).containsExactly(NOC);   // OTHER üyeliği 7/24 takımı değil
        assertThat(svc.isOperator(3L)).isFalse();
        assertThat(svc.isOperator(null)).isFalse();
        assertThat(svc.teamIds()).containsExactly(NOC);
        assertThat(svc.operatorCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("silinen ve pasif takımlar etkin kümeden düşer; pasif kullanıcı (sorgu dışında) operatör değil")
    void deletedAndPassiveTeamsDropOut() {
        row.setOperatorTeamIds(GONE + "," + PASSIVE_TEAM);
        svc.invalidate();
        assertThat(svc.teamIds()).isEmpty();
        assertThat(svc.isOperator(1L)).isFalse();
        verify(userRepo, never()).findActiveMemberRowsOfTeams(anyCollection());   // etkin takım yoksa üye sorgusu yok

        row.setOperatorTeamIds(NOC + "," + GONE);
        svc.invalidate();
        assertThat(svc.teamIds()).containsExactly(NOC);
        // Pasif kullanıcı: projeksiyon sorgusu yalnız active=true döndürür → listede yoksa operatör değil
        memberRows.removeIf(r -> Long.valueOf(1L).equals(r[0]));
        svc.invalidate();
        assertThat(svc.isOperator(1L)).isFalse();
    }

    @Test
    @DisplayName("önbellek: TTL içinde DB'ye gitmez; takım listeden çıkarılınca TTL dolunca operatörlük kalkar; kayıt anında düşürür")
    void cacheAndRevocation() {
        row.setOperatorTeamIds(String.valueOf(NOC));
        svc.invalidate();
        assertThat(svc.isOperator(1L)).isTrue();
        assertThat(svc.isOperator(2L)).isTrue();
        verify(settingsRepo, times(1)).findById(NocSettings.SINGLETON_ID);

        row.setOperatorTeamIds(String.valueOf(OTHER));   // başka pod'dan kayıt: bu pod TTL boyunca eski görüntüde
        now.addAndGet(svc.cacheMs - 1);
        assertThat(svc.isOperator(1L)).isTrue();
        now.addAndGet(2);
        assertThat(svc.isOperator(1L)).isFalse();         // TTL doldu → tazelendi
        assertThat(svc.isOperator(3L)).isTrue();          // OTHER artık 7/24 takımı

        svc.save(List.of(NOC), "admin", "Yönetici");      // bu pod: kayıt anında düşer
        assertThat(svc.isOperator(1L)).isTrue();
        assertThat(svc.isOperator(3L)).isFalse();
    }

    @Test
    @DisplayName("okuma hatası: önceki görüntü korunur; hiç görüntü yoksa kimse operatör değil (kapalı düşer)")
    void failClosed() {
        when(settingsRepo.findById(NocSettings.SINGLETON_ID)).thenThrow(new RuntimeException("db down"));
        assertThat(svc.isOperator(1L)).isFalse();
    }

    @Test
    @DisplayName("sync: oturuma bayrak + takımlar yazılır; değişmediyse yeniden yazılmaz; kalkınca öznitelikler silinir")
    void syncSessionAttributes() {
        row.setOperatorTeamIds(String.valueOf(NOC));
        svc.invalidate();
        MockHttpSession s = spy(session(1L));
        svc.sync(s);
        assertThat(SessionScope.isNocOperator(s)).isTrue();
        assertThat(SessionScope.nocTeamIds(s)).containsExactly(NOC);
        verify(s, times(1)).setAttribute(eq(SessionScope.ATTR_NOC_OPERATOR), any());
        svc.sync(s);
        verify(s, times(1)).setAttribute(eq(SessionScope.ATTR_NOC_OPERATOR), any());   // değişiklik yok → yazma yok
        verify(s, times(1)).setAttribute(eq(SessionScope.ATTR_NOC_TEAM_IDS), any());

        svc.save(List.of(), "admin", "Yönetici");   // takım listeden çıkarıldı
        svc.sync(s);
        assertThat(SessionScope.isNocOperator(s)).isFalse();
        assertThat(s.getAttribute(SessionScope.ATTR_NOC_TEAM_IDS)).isNull();

        MockHttpSession anon = new MockHttpSession();
        anon.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);   // kimliksiz oturumda bayrak kalmaz
        svc.sync(anon);
        assertThat(SessionScope.isNocOperator(anon)).isFalse();
    }

    @Test
    @DisplayName("kayıt: bilinmeyen takım 400, tavan aşımı 400, sayı olmayan kimlik 400; tekil + sıralı CSV yazılır; künye")
    void saveValidation() {
        assertThatThrownBy(() -> svc.save(List.of(NOC, GONE), "a", "A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.save(List.of("abc"), "a", "A")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.save(List.of(-3), "a", "A")).isInstanceOf(IllegalArgumentException.class);
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= NocOperatorService.MAX_TEAMS + 1; i++) many.add(i);
        assertThatThrownBy(() -> svc.save(many, "a", "A")).isInstanceOf(IllegalArgumentException.class);
        verify(settingsRepo, never()).save(any());

        row.setOperatorTeamIds(String.valueOf(OTHER));
        NocOperatorService.SaveResult r = svc.save(List.of(NOC, NOC, OTHER), "admin", "Yönetici Kişi");
        ArgumentCaptor<NocSettings> cap = ArgumentCaptor.forClass(NocSettings.class);
        verify(settingsRepo).save(cap.capture());
        assertThat(cap.getValue().getOperatorTeamIds()).isEqualTo(NOC + "," + OTHER);
        assertThat(cap.getValue().getOperatorTeamsUpdatedBy()).isEqualTo("admin");
        assertThat(cap.getValue().getOperatorTeamsUpdatedByName()).isEqualTo("Yönetici Kişi");
        assertThat(cap.getValue().getOperatorTeamsUpdatedAt()).isNotBlank();
        assertThat(r.before()).containsExactly(OTHER);
        assertThat(r.after()).containsExactly(NOC, OTHER);
        assertThat(r.names()).containsEntry(NOC, "Takım NOC");
        // Virgüllü metin ve boş liste de kabul edilir
        assertThat(NocOperatorService.parseIds(NOC + ", " + OTHER)).containsExactly(NOC, OTHER);
        assertThat(NocOperatorService.parseIds(null)).isEmpty();
    }

    @Test
    @DisplayName("önizleme: takım başına aktif üye sayısı, tekil kişi listesi (ad + 7/24 takımları); pasif takım 0")
    void preview() {
        Map<String, Object> p = svc.preview(List.of(NOC, OTHER, PASSIVE_TEAM, GONE));
        assertThat(p.get("user_count")).isEqualTo(3);
        @SuppressWarnings("unchecked") List<Map<String, Object>> teams = (List<Map<String, Object>>) p.get("teams");
        assertThat(teams).extracting(t -> t.get("id")).containsExactly(NOC, OTHER, PASSIVE_TEAM);   // silinmiş düşer
        assertThat(teams.get(0).get("member_count")).isEqualTo(2);
        assertThat(teams.get(1).get("member_count")).isEqualTo(2);
        assertThat(teams.get(2).get("member_count")).isEqualTo(0);
        assertThat(teams.get(2).get("active")).isEqualTo(false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> users = (List<Map<String, Object>>) p.get("users");
        Map<String, Object> u2 = users.stream().filter(u -> Long.valueOf(2L).equals(u.get("user_id"))).findFirst().orElseThrow();
        assertThat((List<Object>) u2.get("team_names")).containsExactly("Takım NOC", "Takım B");
        assertThat(u2).doesNotContainKeys("phone", "email");
        assertThat(p.get("truncated")).isEqualTo(false);
    }

    @Test
    @DisplayName("ayar görüntüsü: kayıtlı ve var olan takımlar + künye + etkin operatör sayısı")
    void settingsDto() {
        row.setOperatorTeamIds(NOC + "," + GONE + "," + PASSIVE_TEAM);
        row.setOperatorTeamsUpdatedByName("Yönetici");
        svc.invalidate();
        Map<String, Object> d = svc.settingsDto();
        assertThat(d.get("team_ids")).isEqualTo(List.of(NOC, PASSIVE_TEAM));
        assertThat(d.get("operator_count")).isEqualTo(2);
        assertThat(d.get("updated_by_name")).isEqualTo("Yönetici");
        assertThat(d.get("max_teams")).isEqualTo(NocOperatorService.MAX_TEAMS);
    }
}
