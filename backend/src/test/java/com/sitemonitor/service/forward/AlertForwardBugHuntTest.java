package com.sitemonitor.service.forward;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertForwardRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.NotificationGroupService;
import com.sitemonitor.service.forward.AlertForwardRouting.AlertRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alarm iletimi — hata avı düzeltmeleri (2026-11-06), servis düzeyi:
 * <ul>
 *   <li>H2#2: tüm üyeleri silinmiş MEMBERS iletimi duraklatılabilir / notu değiştirilebilir (alıcı alanları gönderilmedikçe boş
 *       üye listesi yeniden doğrulanmaz);</li>
 *   <li>H2#3: takım silme önce takım satırını kilitler, sonra iletimleri siler;</li>
 *   <li>H2#4: taşıma sonrası hedefin iletim sayısı ({@code countAfterMove}) — taşımanın kendi kuralıyla;</li>
 *   <li>H2#6: süresi dolmuş iletim etkinleşince bilgi bildirimi İSTENMEZ;</li>
 *   <li>H2#8: görüntü kurulumunda tek takımın adres çözümü hatası tüm görüntüyü düşürmez.</li>
 * </ul>
 */
class AlertForwardBugHuntTest {

    static final long SY = 10, UG = 11, RT = 20, RT2 = 21;

    AlertForwardRepository repo;
    TeamRepository teamRepo;
    NotificationGroupService groups;
    AlertForwardService svc;
    final List<AlertForward> rows = new ArrayList<>();
    final AtomicLong clock = new AtomicLong(java.time.Instant.parse("2026-11-06T10:00:00Z").toEpochMilli());

    static Team team(long id, String name, String email) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setEmail(email);
        t.setActive(true);
        return t;
    }

    static AlertForward fwd(long id, long owner, String scope, String kind, long rt, String users) {
        AlertForward f = new AlertForward();
        f.setId(id);
        f.setVersion(3L);
        f.setOwnerTeamId(owner);
        f.setScopeKind(scope);
        f.setRecipientKind(kind);
        f.setRecipientTeamId(rt);
        f.setRecipientUserIds(users);
        f.setEnabled(true);
        f.setNote("eski not");
        return f;
    }

    @BeforeEach
    void setUp() {
        repo = mock(AlertForwardRepository.class);
        teamRepo = mock(TeamRepository.class);
        when(repo.findAllByOrderByIdAsc()).thenAnswer(i -> new ArrayList<>(rows));
        when(repo.findMemberRows(anyCollection())).thenReturn(List.of());
        when(repo.findInventoryDomains(anyCollection())).thenReturn(List.of());
        when(repo.lockTeamRow(anyLong())).thenAnswer(i -> List.of((Long) i.getArgument(0)));
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(teamRepo.findAllById(any())).thenReturn(List.of(team(SY, "Sipariş Sistemleri", "sy@example.com"),
                team(UG, "Ödeme", "ug@example.com"), team(RT, "Ağ Operasyon", "noc@example.com"),
                team(RT2, "Güvenlik Operasyon Merkezi (Bilgi Güvenliği / Siber Olay Müdahale)", "soc@example.com")));
        groups = mock(NotificationGroupService.class);
        when(groups.overrideFor(anyLong(), isNull())).thenReturn(NotificationGroupService.Override.NONE);
        svc = new AlertForwardService(repo, teamRepo);
        svc.setGroups(groups);
        svc.setInactiveGuard(mock(InactiveRecipientGuard.class));
        svc.clock = clock::get;
    }

    @Test
    @DisplayName("H2#2: üyeleri silinmiş MEMBERS iletimi duraklatılır / notu değişir (400 değil); üyelere geçiş ya da takım değişimi üye ister")
    void emptyMembersForwardStaysEditable() {
        AlertForward f = fwd(5, SY, "ALL", "MEMBERS", RT, "");
        when(repo.findById(5L)).thenReturn(Optional.of(f));
        AlertForwardService.Saved paused = svc.update(5L, Map.of("enabled", false), null, "u");
        assertThat(paused.changed()).isTrue();
        assertThat(paused.forward().isEnabled()).isFalse();
        AlertForwardService.Saved noted = svc.update(5L, Map.of("note", "takım dağıldı"), null, "u");
        assertThat(noted.forward().getNote()).isEqualTo("takım dağıldı");
        assertThat(noted.infoRequested()).isFalse();

        // alıcı alanı gönderildiyse doğrulama yine çalışır: başka takıma geçiş üye ister
        when(teamRepo.findById(RT2)).thenReturn(Optional.of(team(RT2, "Güvenlik", "soc@example.com")));
        assertThatThrownBy(() -> svc.update(5L, Map.of("recipient_team_id", RT2), null, "u"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        // TEAM'den MEMBERS'a geçiş üye ister
        AlertForward g = fwd(6, SY, "ALL", "TEAM", RT, null);
        when(repo.findById(6L)).thenReturn(Optional.of(g));
        assertThatThrownBy(() -> svc.update(6L, Map.of("recipient_kind", "MEMBERS"), null, "u"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
    }

    @Test
    @DisplayName("H2#3: takım silme ÖNCE takım satırını kilitler, sonra iletimleri siler (teams → alert_forwards sırası)")
    void deleteForTeamLocksTeamFirst() {
        when(repo.deleteByTeam(SY)).thenReturn(2);
        assertThat(svc.deleteForTeam(SY)).isEqualTo(2);
        InOrder order = inOrder(repo);
        order.verify(repo).lockTeamRow(SY);
        order.verify(repo).deleteByTeam(SY);
        svc.deleteForTeam(null);
        verify(repo, never()).lockTeamRow(isNull());
    }

    @Test
    @DisplayName("H2#4: taşıma sonrası hedefin iletim sayısı — hedefin kendi (from'a gidenler hariç) + taşınacaklar (hedefe gidenler ve kopyalar hariç)")
    void countAfterMoveMirrorsMove() {
        // hedef (UG) kendi iletimleri: RT'ye ALL (kalır), SY'ye (from'a) ALL (taşımayla silinir)
        AlertForward ugToRt = fwd(1, UG, "ALL", "TEAM", RT, null);
        AlertForward ugToSy = fwd(2, UG, "ALL", "TEAM", SY, null);
        // kaynak (SY): RT'ye ALL (hedefte aynısı var → silinir), UG'ye (hedefe) ALL (silinir), RT2'ye ALL (taşınır)
        AlertForward syToRt = fwd(3, SY, "ALL", "TEAM", RT, null);
        AlertForward syToUg = fwd(4, SY, "ALL", "TEAM", UG, null);
        AlertForward syToRt2 = fwd(5, SY, "ALL", "TEAM", RT2, null);
        when(repo.findByOwnerTeamIdInOrderByIdAsc(List.of(UG))).thenReturn(List.of(ugToRt, ugToSy));
        when(repo.findByOwnerTeamIdInOrderByIdAsc(List.of(SY))).thenReturn(List.of(syToRt, syToUg, syToRt2));
        assertThat(svc.countAfterMove(SY, UG)).isEqualTo(2);   // ugToRt + syToRt2
        assertThat(syToRt.getOwnerTeamId()).as("salt okuma — varlık değişmez").isEqualTo(SY);
        assertThat(svc.countAfterMove(SY, SY)).isZero();
        assertThat(svc.countAfterMove(null, UG)).isZero();
    }

    @Test
    @DisplayName("H2#6: süresi dolmuş iletim etkinleştirilince bilgi İSTENMEZ (hiçbir alarm iletmez); süresi dolmamışta istenir")
    void expiredActivationRequestsNoInfo() {
        AlertForward f = fwd(7, SY, "ALL", "TEAM", RT, null);
        f.setEnabled(false);
        f.setExpiresAt("2026-11-01T20:59:59");   // geçmiş (saat 2026-11-06)
        when(repo.findById(7L)).thenReturn(Optional.of(f));
        AlertForwardService.Saved on = svc.update(7L, Map.of("enabled", true), null, "u");
        assertThat(on.activated()).isTrue();
        assertThat(on.expired()).isTrue();
        assertThat(on.infoWholeTeam()).isFalse();
        assertThat(on.infoRequested()).isFalse();

        AlertForward g = fwd(8, SY, "ALL", "TEAM", RT2, null);
        g.setEnabled(false);
        g.setExpiresAt("2026-12-01T20:59:59");
        when(repo.findById(8L)).thenReturn(Optional.of(g));
        AlertForwardService.Saved live = svc.update(8L, Map.of("enabled", true), null, "u");
        assertThat(live.expired()).isFalse();
        assertThat(live.infoRequested()).isTrue();
    }

    @Test
    @DisplayName("H2#8: bir alıcı takımın adres çözümü hata verirse görüntü YİNE kurulur — o hedef yalnız push (adres yok), öbürü aynen")
    void oneTeamAddressFailureDoesNotDropSnapshot() {
        rows.add(fwd(1, SY, "ALL", "TEAM", RT, null));
        rows.add(fwd(2, SY, "ALL", "TEAM", RT2, null));
        when(groups.overrideFor(eq(RT2), isNull())).thenThrow(new RuntimeException("grup tablosu okunamadı"));
        assertThat(svc.current()).isNotNull();
        List<ForwardTarget> t = svc.targetsFor(new AlertRef(List.of(SY), "EXPIRY", "a.example.com", null));
        assertThat(t).extracting(ForwardTarget::recipientTeamId).containsExactly(RT, RT2);
        assertThat(t.get(0).emails()).containsExactly("noc@example.com");
        assertThat(t.get(1).emails()).as("adresler bilinmiyor — takım adresine geri düşülmez").isEmpty();
        assertThat(t.get(1).wholeTeam()).as("push yine takımın kurallarıyla").isTrue();
    }
}
