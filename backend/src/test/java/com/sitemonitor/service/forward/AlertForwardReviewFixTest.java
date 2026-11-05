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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Alarm iletimi — bağımsız inceleme düzeltmeleri (2026-11-06), servis düzeyi:
 * <ul>
 *   <li>hedef sahip takım KİMLİKLERİNİ taşır ({@link ForwardTarget#ownerTeamIds} — push kapısı onlara sorar);</li>
 *   <li>{@code anyLive} / {@code anyConfigured} yalnız bellek (sorgudan önce sorulan kapılar);</li>
 *   <li>düzenleme takım satırını varlık DEĞİŞMEDEN önce kilitler (takım taşımasıyla aynı sıra);</li>
 *   <li>bulunamayan üye hata metni iç sayısal kimlik yazmaz;</li>
 *   <li>ilk görüntü hazır olayında EŞZAMANLI kurulur;</li>
 *   <li>taşıma notu 500 sınırına sığar, ikinci taşımada tekrarlanmaz;</li>
 *   <li>aynı alıcıya giden hedefler birleşir (ikincisi düşmez).</li>
 * </ul>
 */
class AlertForwardReviewFixTest {

    static final long SY = 10, UG = 11, RT = 20;

    AlertForwardRepository repo;
    TeamRepository teamRepo;
    AlertForwardService svc;
    final List<AlertForward> rows = new ArrayList<>();
    final List<Object[]> memberRows = new ArrayList<>();
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
        when(repo.findMemberRows(anyCollection())).thenAnswer(i -> new ArrayList<>(memberRows));
        when(repo.findInventoryDomains(anyCollection())).thenReturn(List.of());
        when(teamRepo.findAllById(any())).thenReturn(List.of(team(SY, "Sipariş Sistemleri", "sy@example.com"),
                team(UG, "Ödeme", "ug@example.com"), team(RT, "Ağ Operasyon", "noc@example.com")));
        when(teamRepo.findById(RT)).thenReturn(Optional.of(team(RT, "Ağ Operasyon", "noc@example.com")));
        NotificationGroupService groups = mock(NotificationGroupService.class);
        when(groups.overrideFor(anyLong(), isNull())).thenReturn(NotificationGroupService.Override.NONE);
        svc = new AlertForwardService(repo, teamRepo);
        svc.setGroups(groups);
        svc.setInactiveGuard(mock(InactiveRecipientGuard.class));
        svc.clock = clock::get;
    }

    @Test
    @DisplayName("hedef sahip takım kimliklerini taşır: SY ve UG aynı alıcıya iletirse tek hedef, ownerTeamIds [SY, UG]")
    void targetCarriesOwnerIds() {
        rows.add(fwd(1, SY, "ALL", "TEAM", RT, null));
        rows.add(fwd(2, UG, "ALL", "TEAM", RT, null));
        assertThat(svc.current()).isNotNull();
        List<ForwardTarget> t = svc.targetsFor(new AlertRef(List.of(SY, UG), "EXPIRY", "a.example.com", null));
        assertThat(t).singleElement().satisfies(x -> {
            assertThat(x.ownerTeamIds()).containsExactly(SY, UG);
            assertThat(x.ownerTeamNames()).containsExactly("Sipariş Sistemleri", "Ödeme");
        });
        List<ForwardTarget> onlyUg = svc.targetsFor(new AlertRef(List.of(UG), "EXPIRY", "a.example.com", null));
        assertThat(onlyUg).singleElement().satisfies(x -> assertThat(x.ownerTeamIds()).containsExactly(UG));
    }

    @Test
    @DisplayName("anyLive / anyConfigured: kayıt yok → ikisi false; yalnız duraklatılmış → anyLive false, anyConfigured true")
    void anyLiveAndConfigured() {
        assertThat(svc.current()).isNotNull();
        assertThat(svc.anyLive()).isFalse();
        assertThat(svc.anyConfigured()).isFalse();
        AlertForward paused = fwd(1, SY, "ALL", "TEAM", RT, null);
        paused.setEnabled(false);
        rows.add(paused);
        svc.invalidateLocal();
        assertThat(svc.current()).isNotNull();
        assertThat(svc.anyLive()).isFalse();
        assertThat(svc.anyConfigured()).isTrue();
        rows.add(fwd(2, SY, "TYPES", "TEAM", RT, null));
        svc.invalidateLocal();
        assertThat(svc.current()).isNotNull();
        assertThat(svc.anyLive()).isTrue();
    }

    @Test
    @DisplayName("düzenleme: takım satırı varlık DEĞİŞMEDEN önce kilitlenir (yalnız not değişse de); kilit kaydetmeden önce")
    void updateLocksTeamBeforeMutating() {
        AlertForward f = fwd(5, SY, "ALL", "TEAM", RT, null);
        when(repo.findById(5L)).thenReturn(Optional.of(f));
        List<String> noteAtLock = new CopyOnWriteArrayList<>();
        when(repo.lockTeamRow(SY)).thenAnswer(i -> {
            noteAtLock.add(String.valueOf(f.getNote()));
            return List.of(SY);
        });
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        AlertForwardService.Saved s = svc.update(5L, Map.of("note", "yeni not"), null, "u");
        assertThat(s.changed()).isTrue();
        assertThat(noteAtLock).as("kilit anında varlık henüz değişmemişti").containsExactly("eski not");
        InOrder order = inOrder(repo);
        order.verify(repo).lockTeamRow(SY);
        order.verify(repo).saveAndFlush(f);

        // takım (dolayısıyla iletim) bu arada silindiyse: kilit boş → 404, hiçbir şey yazılmaz
        AlertForward g = fwd(6, UG, "ALL", "TEAM", RT, null);
        when(repo.findById(6L)).thenReturn(Optional.of(g));
        when(repo.lockTeamRow(UG)).thenReturn(List.of());
        assertThatThrownBy(() -> svc.update(6L, Map.of("note", "x"), null, "u"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(g.getNote()).isEqualTo("eski not");
    }

    @Test
    @DisplayName("bulunamayan üye: hata metni iç sayısal kimlik YAZMAZ")
    void missingMemberMessageHasNoInternalId() {
        AlertForward f = fwd(5, SY, "ALL", "MEMBERS", RT, "77");
        when(repo.findById(5L)).thenReturn(Optional.of(f));
        when(repo.lockTeamRow(SY)).thenReturn(List.of(SY));
        assertThatThrownBy(() -> svc.update(5L, Map.of("recipient_user_ids", "x"), List.of(77L, 98765L), "u"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("98765").doesNotContain("#");
                    assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields()).containsKey("recipient_user_ids");
                });
    }

    @Test
    @DisplayName("hazır olay: ilk görüntü ÇAĞIRAN iş parçacığında, olay dönmeden kurulur — ilk alarm iletimsiz kalmaz")
    void firstSnapshotIsSynchronous() {
        rows.add(fwd(1, SY, "ALL", "TEAM", RT, null));
        List<String> threads = new CopyOnWriteArrayList<>();
        when(repo.findAllByOrderByIdAsc()).thenAnswer(i -> {
            threads.add(Thread.currentThread().getName());
            return new ArrayList<>(rows);
        });
        String me = Thread.currentThread().getName();
        try {
            svc.onReady();
            assertThat(threads).as("görüntü olay iş parçacığında kuruldu").isNotEmpty();
            assertThat(threads.get(0)).isEqualTo(me);
            assertThat(svc.anyFor(List.of(SY))).isTrue();
        } finally {
            svc.onClosed();
        }
    }

    @Test
    @DisplayName("taşıma notu: boş not → yalnız metin; uzun not kısaltılır, toplam ≤ 500, metin bütün; ikinci taşımada tekrar yok")
    void moveNoteFits() {
        assertThat(AlertForwardService.withMoveNote(null)).isEqualTo(AlertForwardService.MOVE_PAUSE_NOTE);
        assertThat(AlertForwardService.withMoveNote("Gece nöbeti")).isEqualTo("Gece nöbeti " + AlertForwardService.MOVE_PAUSE_NOTE);
        String longNote = "ş".repeat(499);
        String fitted = AlertForwardService.withMoveNote(longNote);
        assertThat(fitted.length()).isLessThanOrEqualTo(AlertForwardService.NOTE_MAX);
        assertThat(fitted).endsWith(" " + AlertForwardService.MOVE_PAUSE_NOTE).startsWith("şşş").contains("…");
        assertThat(AlertForwardService.withMoveNote(fitted)).isEqualTo(fitted);
        // vekil çifti bölünmez
        String emoji = "😀".repeat(250);
        String cut = AlertForwardService.withMoveNote(emoji);
        assertThat(cut.length()).isLessThanOrEqualTo(AlertForwardService.NOTE_MAX);
        String head = cut.substring(0, cut.indexOf('…'));
        assertThat(head.codePoints().allMatch(c -> c == 0x1F600)).isTrue();
    }

    @Test
    @DisplayName("aynı alıcıya giden hedefler birleşir: üyeler + tüm takım + adresler tekil, sahipler birleşik; tek öğe aynen döner")
    void mergeByRecipient() {
        ForwardTarget a = new ForwardTarget(RT, "Ağ Operasyon", List.of(SY), List.of("Sipariş"), List.of(1L),
                List.of("noc@example.com"), List.of(), true);
        ForwardTarget b = new ForwardTarget(RT, "Ağ Operasyon", List.of(UG), List.of("Ödeme"), List.of(2L),
                List.of("NOC@example.com", "ayse@example.com"), List.of(50L, 51L), false);
        ForwardTarget other = new ForwardTarget(5L, "Başka", List.of(SY), List.of("Sipariş"), List.of(3L), List.of(), List.of(9L), false);
        List<ForwardTarget> merged = ForwardTarget.mergeByRecipient(List.of(a, other, b));
        assertThat(merged).extracting(ForwardTarget::recipientTeamId).containsExactly(5L, RT);
        ForwardTarget m = merged.get(1);
        assertThat(m.wholeTeam()).isTrue();
        assertThat(m.memberUserIds()).containsExactly(50L, 51L);
        assertThat(m.emails()).containsExactly("noc@example.com", "ayse@example.com");
        assertThat(m.ownerTeamIds()).containsExactly(SY, UG);
        assertThat(m.forwardIds()).containsExactly(1L, 2L);
        assertThat(merged.get(0)).isSameAs(other);
        assertThat(ForwardTarget.mergeByRecipient(null)).isEmpty();
    }
}
