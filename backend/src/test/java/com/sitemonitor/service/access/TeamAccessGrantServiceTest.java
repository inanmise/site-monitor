package com.sitemonitor.service.access;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TeamAccessGrant;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Takım erişim paylaşımı (2026-10-12): anlık görüntü (takım paylaşımı AKTİF üyelere, kişi paylaşımı kişiye; süresi dolmuş /
 * pasif sahip takım / pasif kişi UYGULANMAZ; aynı takım için en yüksek düzey), önbellek TTL'i + kayıtta düşürme, okuma
 * hatasında önceki görüntü, oturum öznitelikleri kümülatif ve yalnız değişince, doğrulama (aynı takım, pasif kişi, düzey,
 * geçmiş tarih, not uzunluğu), mükerrer / tavan 409, İstanbul gün sonu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamAccessGrantServiceTest {

    private static final long A = 1L, B = 2L, C = 3L, PASSIVE_TEAM = 4L;
    private static final long ALICE = 100L, BOB = 101L, PASSIVE_USER = 102L, CAROL = 103L;

    @Mock TeamAccessGrantRepository repo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;

    private TeamAccessGrantService svc;
    private final List<TeamAccessGrant> rows = new ArrayList<>();
    private final List<Object[]> members = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(Instant.parse("2026-10-12T09:00:00Z").toEpochMilli());

    private static Team team(long id, boolean active) {
        Team t = new Team();
        t.setId(id);
        t.setName("Takım " + id);
        t.setActive(active);
        return t;
    }

    private static AppUser user(long id, boolean active) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setActive(active);
        return u;
    }

    private TeamAccessGrant grant(long id, long owner, String type, Long gTeam, Long gUser, String level, String expiresAt) {
        TeamAccessGrant g = new TeamAccessGrant();
        g.setId(id);
        g.setOwnerTeamId(owner);
        g.setGranteeType(type);
        g.setGranteeTeamId(gTeam);
        g.setGranteeUserId(gUser);
        g.setGranteeKey(TeamAccessGrant.keyOf(type, gTeam, gUser));
        g.setLevel(level);
        g.setExpiresAt(expiresAt);
        rows.add(g);
        return g;
    }

    @BeforeEach
    void setUp() {
        svc = new TeamAccessGrantService(repo, teamRepo, userRepo);
        svc.clock = now::get;
        when(repo.findAllByOrderByIdAsc()).thenAnswer(inv -> List.copyOf(rows));
        when(teamRepo.findAllById(any())).thenAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                long id = (Long) o;
                if (id == A || id == B || id == C) out.add(team(id, true));
                if (id == PASSIVE_TEAM) out.add(team(id, false));
            }
            return out;
        });
        when(teamRepo.findById(any())).thenAnswer(inv -> {
            long id = (Long) inv.getArgument(0);
            if (id == A || id == B || id == C) return Optional.of(team(id, true));
            if (id == PASSIVE_TEAM) return Optional.of(team(id, false));
            return Optional.empty();
        });
        when(userRepo.findAllById(any())).thenAnswer(inv -> {
            List<AppUser> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                long id = (Long) o;
                out.add(user(id, id != PASSIVE_USER));
            }
            return out;
        });
        when(userRepo.findById(any())).thenAnswer(inv -> {
            long id = (Long) inv.getArgument(0);
            return id == 999L ? Optional.empty() : Optional.of(user(id, id != PASSIVE_USER));
        });
        // B takımının aktif üyeleri: BOB (birincil), CAROL (çoklu üyelik)
        members.add(new Object[]{BOB, "bob", null, null, null, B, null, "USER"});
        members.add(new Object[]{CAROL, "carol", null, null, null, C, B, "USER"});
        when(userRepo.findActiveMemberRowsOfTeams(anyCollection())).thenAnswer(inv -> {
            Collection<?> wanted = inv.getArgument(0);
            return members.stream().filter(r -> wanted.contains(r[5]) || (r[6] != null && wanted.contains(r[6]))).toList();
        });
    }

    @Test
    @DisplayName("takım paylaşımı AKTİF üyelere (birincil + çoklu üyelik), kişi paylaşımı kişiye; aynı takımda en yüksek düzey")
    void snapshotExpandsTeamsAndKeepsHighestLevel() {
        grant(1, A, TeamAccessGrant.TYPE_TEAM, B, null, "VIEW", null);
        grant(2, A, TeamAccessGrant.TYPE_USER, null, BOB, "EDIT", null);
        grant(3, C, TeamAccessGrant.TYPE_USER, null, ALICE, "EXECUTE", null);
        assertThat(svc.levelsFor(BOB)).containsExactlyInAnyOrderEntriesOf(Map.of(A, TeamAccessLevel.EDIT));
        assertThat(svc.levelsFor(CAROL)).containsExactlyInAnyOrderEntriesOf(Map.of(A, TeamAccessLevel.VIEW));
        assertThat(svc.levelsFor(ALICE)).containsExactlyInAnyOrderEntriesOf(Map.of(C, TeamAccessLevel.EXECUTE));
        assertThat(svc.levelsFor(999L)).isEmpty();
    }

    @Test
    @DisplayName("süresi dolmuş paylaşım, pasif sahip takım ve pasif kişi UYGULANMAZ")
    void expiredPassiveTeamAndPassiveUserAreIgnored() {
        grant(1, A, TeamAccessGrant.TYPE_USER, null, ALICE, "VIEW", "2026-10-12T08:59:59");   // az önce bitti
        grant(2, PASSIVE_TEAM, TeamAccessGrant.TYPE_USER, null, ALICE, "DELETE", null);
        grant(3, A, TeamAccessGrant.TYPE_USER, null, PASSIVE_USER, "DELETE", null);
        grant(4, C, TeamAccessGrant.TYPE_USER, null, ALICE, "VIEW", "2026-10-12T21:00:00");   // bugün sonuna kadar
        assertThat(svc.levelsFor(ALICE)).containsExactlyInAnyOrderEntriesOf(Map.of(C, TeamAccessLevel.VIEW));
        assertThat(svc.levelsFor(PASSIVE_USER)).isEmpty();
    }

    @Test
    @DisplayName("önbellek: TTL içinde yeniden okunmaz; invalidate bayat işaretler; okuma hatası önceki görüntüyü korur")
    void cacheTtlInvalidateAndFailure() {
        grant(1, A, TeamAccessGrant.TYPE_USER, null, ALICE, "VIEW", null);
        svc.levelsFor(ALICE);
        svc.levelsFor(ALICE);
        verify(repo, times(1)).findAllByOrderByIdAsc();
        svc.invalidate();
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(repo).findAllByOrderByIdAsc();
        // invalidate görüntüyü SİLMEZ, bayat işaretler (2026-10-12): yeniden kurulum hata verirse herkesin paylaşımı
        // 30 sn boşalmaz — önceki görüntü kullanılır (TTL dolumundaki hata yoluyla aynı kural)
        assertThat(svc.levelsFor(ALICE)).containsKey(A);
        verify(repo, times(2)).findAllByOrderByIdAsc();   // bayat → yeniden denendi
        now.addAndGet(31_000);
        org.mockito.Mockito.doAnswer(inv -> List.copyOf(rows)).when(repo).findAllByOrderByIdAsc();
        assertThat(svc.levelsFor(ALICE)).containsKey(A);
        now.addAndGet(31_000);
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(repo).findAllByOrderByIdAsc();
        assertThat(svc.levelsFor(ALICE)).containsKey(A);   // hata → önceki görüntü korunur
    }

    @Test
    @DisplayName("oturum: kümülatif listeler (VIEW ⊇ EXECUTE ⊇ EDIT ⊇ DELETE); paylaşım kalkınca öznitelikler silinir; oturumsuz → yok")
    void syncWritesCumulativeListsAndRemoves() {
        grant(1, A, TeamAccessGrant.TYPE_USER, null, ALICE, "EDIT", null);
        grant(2, C, TeamAccessGrant.TYPE_USER, null, ALICE, "VIEW", null);
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("userId", ALICE);
        svc.sync(s);
        assertThat(s.getAttribute(SessionScope.ATTR_GRANT_VIEW)).isEqualTo(List.of(A, C));
        assertThat(s.getAttribute(SessionScope.ATTR_GRANT_EXECUTE)).isEqualTo(List.of(A));
        assertThat(s.getAttribute(SessionScope.ATTR_GRANT_EDIT)).isEqualTo(List.of(A));
        assertThat(s.getAttribute(SessionScope.ATTR_GRANT_DELETE)).isNull();
        rows.clear();
        svc.invalidate();
        svc.sync(s);
        assertThat(s.getAttribute(SessionScope.ATTR_GRANT_VIEW)).isNull();
        assertThat(s.getAttribute(SessionScope.ATTR_GRANT_EDIT)).isNull();
        MockHttpSession anon = new MockHttpSession();
        anon.setAttribute("userId", ALICE);   // authenticated yok
        grant(3, A, TeamAccessGrant.TYPE_USER, null, ALICE, "DELETE", null);
        svc.invalidate();
        svc.sync(anon);
        assertThat(anon.getAttribute(SessionScope.ATTR_GRANT_VIEW)).isNull();
    }

    @Test
    @DisplayName("doğrulama: aynı takım, pasif / yok kişi, tanınmayan düzey, geçmiş tarih, uzun not → alan hatası")
    void validationErrors() {
        assertThatThrownBy(() -> svc.validate(A, "TEAM", A, null, "VIEW", null, null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class).hasMessageContaining("kendi");
        assertThatThrownBy(() -> svc.validate(A, "USER", null, PASSIVE_USER, "VIEW", null, null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.validate(A, "USER", null, 999L, "VIEW", null, null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.validate(A, "TEAM", B, null, "ROOT", null, null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.validate(A, "TEAM", B, null, "VIEW", "2026-10-11", null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.validate(A, "TEAM", B, null, "VIEW", null, "x".repeat(501)))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.validate(PASSIVE_TEAM, "TEAM", B, null, "VIEW", null, null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThatThrownBy(() -> svc.validate(A, "GROUP", B, null, "VIEW", null, null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
    }

    @Test
    @DisplayName("bitiş: İstanbul günü o günün SONUNA kadar (21:00Z); bugün seçilebilir; geri dönüşte aynı gün")
    void expiresOnIstanbulEndOfDay() {
        TeamAccessGrantService.Input in = svc.validate(A, "TEAM", B, null, "edit", "2026-10-12", "  neden  ");
        assertThat(in.expiresAt()).isEqualTo("2026-10-12T21:00:00");
        assertThat(in.level()).isEqualTo(TeamAccessLevel.EDIT);
        assertThat(in.note()).isEqualTo("neden");
        assertThat(TeamAccessGrantService.expiresOn(in.expiresAt())).isEqualTo("2026-10-12");
        assertThat(svc.validate(A, "TEAM", B, null, "VIEW", "", "  ").expiresAt()).isNull();
    }

    @Test
    @DisplayName("oluşturma: mükerrer alıcı 409 GRANT_EXISTS, takım tavanı 409 GRANT_LIMIT; kayıt görüntüyü düşürür")
    void createDuplicateLimitAndInvalidate() {
        TeamAccessGrantService.Input in = svc.validate(A, "TEAM", B, null, "VIEW", null, null);
        when(repo.findByOwnerTeamIdAndGranteeKey(A, "T:" + B)).thenReturn(Optional.of(new TeamAccessGrant()));
        assertThatThrownBy(() -> svc.create(in, "admin"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.CodedConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("GRANT_EXISTS"));
        when(repo.findByOwnerTeamIdAndGranteeKey(A, "T:" + B)).thenReturn(Optional.empty());
        when(repo.countLiveByOwnerTeamId(org.mockito.ArgumentMatchers.eq(A), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn((long) TeamAccessGrantService.MAX_PER_OWNER);
        assertThatThrownBy(() -> svc.create(in, "admin"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.CodedConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("GRANT_LIMIT"));
        verify(repo, never()).saveAndFlush(any());
        // tavan YALNIZ süresi dolmamış paylaşımları sayar: sorgunun "şimdi"si saatten
        verify(repo, org.mockito.Mockito.atLeastOnce()).countLiveByOwnerTeamId(A, "2026-10-12T09:00:00");
        when(repo.countLiveByOwnerTeamId(org.mockito.ArgumentMatchers.eq(A), org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        svc.levelsFor(ALICE);   // görüntü kuruldu
        TeamAccessGrant saved = svc.create(in, "admin");
        assertThat(saved.getGranteeKey()).isEqualTo("T:" + B);
        assertThat(saved.getCreatedBy()).isEqualTo("admin");
        svc.levelsFor(ALICE);
        verify(repo, times(2)).findAllByOrderByIdAsc();   // kayıt sonrası yeniden kuruldu
    }

    @Test
    @DisplayName("inceleme 4: yapım sırasında kayıt değişirse o yapım saklanmaz — sonraki okuma yeniden kurar")
    void buildRacingInvalidateIsNotStored() {
        grant(1, A, TeamAccessGrant.TYPE_USER, null, ALICE, "VIEW", null);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(inv -> {
            List<TeamAccessGrant> snap = List.copyOf(rows);
            // okuma bitti, görüntü henüz saklanmadı: bu arada paylaşım silindi (başka istek)
            if (first.getAndSet(false)) { rows.clear(); svc.invalidate(); }
            return snap;
        }).when(repo).findAllByOrderByIdAsc();
        assertThat(svc.levelsFor(ALICE)).containsKey(A);   // bu istek kendi sonucunu kullanır
        assertThat(svc.levelsFor(ALICE)).isEmpty();          // ama saklanmadı: silinmiş paylaşım 30 sn yaşamaz
        verify(repo, times(2)).findAllByOrderByIdAsc();
    }

    @Test
    @DisplayName("inceleme 5: süresi DOLMUŞ paylaşımı canlandırmak takım tavanına takılır (GRANT_LIMIT); canlı paylaşımın "
            + "düzenlenmesi ve süresi dolmuşun süresiz kalmadan düzenlenmesi tavanı sormaz")
    void reviveExpiredRespectsLimit() {
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.countLiveByOwnerTeamId(org.mockito.ArgumentMatchers.eq(A), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn((long) TeamAccessGrantService.MAX_PER_OWNER);
        TeamAccessGrant expired = new TeamAccessGrant();
        expired.setOwnerTeamId(A); expired.setLevel("VIEW"); expired.setExpiresAt("2026-10-01T20:59:59");
        // süresiz canlandırma → tavan
        assertThatThrownBy(() -> svc.update(expired, TeamAccessLevel.EDIT, null, null, "admin"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.CodedConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("GRANT_LIMIT"));
        // ileri tarihli canlandırma → tavan
        assertThatThrownBy(() -> svc.update(expired, TeamAccessLevel.EDIT, "2026-12-31T20:59:59", null, "admin"))
                .isInstanceOf(GlobalExceptionHandler.CodedConflictException.class);
        assertThat(expired.getLevel()).isEqualTo("VIEW");   // reddedilen istek kaydı değiştirmedi
        verify(repo, never()).saveAndFlush(any());
        // süresi dolmuş kalacak düzenleme (yalnız not) tavanı sormaz
        svc.update(expired, TeamAccessLevel.VIEW, "2026-10-01T20:59:59", "not", "admin");
        // canlı paylaşım düzenlemesi tavanı sormaz (zaten sayılıyor)
        TeamAccessGrant live = new TeamAccessGrant();
        live.setOwnerTeamId(A); live.setLevel("VIEW"); live.setExpiresAt(null);
        svc.update(live, TeamAccessLevel.DELETE, null, null, "admin");
        assertThat(live.getLevel()).isEqualTo("DELETE");
        verify(repo, times(2)).countLiveByOwnerTeamId(org.mockito.ArgumentMatchers.eq(A), org.mockito.ArgumentMatchers.anyString());
        // tavan altında canlandırma geçer
        when(repo.countLiveByOwnerTeamId(org.mockito.ArgumentMatchers.eq(A), org.mockito.ArgumentMatchers.anyString())).thenReturn(3L);
        svc.update(expired, TeamAccessLevel.EDIT, null, null, "admin");
        assertThat(expired.getExpiresAt()).isNull();
        assertThat(expired.getLevel()).isEqualTo("EDIT");
    }

    @Test
    @DisplayName("inceleme 4: bitiş en geç 2099-12-31; eşzamanlı aynı alıcı (benzersiz kısıt) GRANT_EXISTS")
    void expiryCapAndUniqueRace() {
        assertThat(svc.validate(A, "TEAM", B, null, "VIEW", "2099-12-31", null).expiresAt()).isNotNull();
        assertThatThrownBy(() -> svc.validate(A, "TEAM", B, null, "VIEW", "2100-01-01", null))
                .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class,
                        e -> assertThat(e.getMessage()).contains("2099"));
        TeamAccessGrantService.Input in = svc.validate(A, "TEAM", B, null, "VIEW", null, null);
        when(repo.findByOwnerTeamIdAndGranteeKey(A, "T:" + B)).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_owner_grantee"));
        assertThatThrownBy(() -> svc.create(in, "admin"))
                .isInstanceOfSatisfying(GlobalExceptionHandler.CodedConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("GRANT_EXISTS"));
    }

    @Test
    @DisplayName("takım / kişi silinince paylaşımları silinir ve görüntü düşer")
    void deleteForTeamAndUser() {
        when(repo.deleteByTeam(A)).thenReturn(2);
        when(repo.deleteByGranteeUser(ALICE)).thenReturn(1);
        assertThat(svc.deleteForTeam(A)).isEqualTo(2);
        assertThat(svc.deleteForUser(ALICE)).isEqualTo(1);
        assertThat(svc.deleteForTeam(null)).isZero();
    }
}
