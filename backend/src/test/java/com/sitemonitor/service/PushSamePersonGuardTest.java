package com.sitemonitor.service;

import com.sitemonitor.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aynı kişi kuralının karar çekirdeği (2026-10-21, prod alarm #628) — sorgunun kendisi H2 üstünde
 * {@code UserPushSamePersonTest}'te; burada seçim sırası ve gruplama: e-posta (kırpılmış, harf duyarsız) aynı aktif hesaplar
 * tek kişidir; boş adres asla birleşmez; seçim = bu bildirimi almış hesap → bu olayda SENT → herhangi bir satır → son giriş →
 * en küçük kimlik. Fixture kimlikleri SAHTE sicillerdir.
 */
class PushSamePersonGuardTest {

    private final AppUserRepository repo = mock(AppUserRepository.class);
    private final PushSamePersonGuard guard = new PushSamePersonGuard(repo);

    private static Object[] acc(long id, String username, String email, String lastLogin) {
        return new Object[]{id, username, email, lastLogin};
    }

    @Test
    @DisplayName("#628: aynı e-posta (farklı harf / boşluk) iki hesap → en son giriş yapan seçilir, öbürü atlanır; tekil hesap dokunulmaz")
    void sameEmailDifferentCase_newestLoginWins() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(10, "MP60146", "  Emre.Gokoglu@Example.COM ", "2026-10-18T08:00:00"),
                acc(11, "N60146", "emre.gokoglu@example.com", "2026-10-19T20:00:00")));
        PushSamePersonGuard.Plan plan = guard.plan(List.of("N00001", "MP60146", "N00002", "N60146"), PushSamePersonGuard.Prior.NONE);
        assertThat(plan.chosenFor("MP60146")).isEqualTo("N60146");
        assertThat(plan.chosenFor("mp60146")).as("anahtar harf duyarsız").isEqualTo("N60146");
        assertThat(plan.chosenFor("N60146")).isNull();
        assertThat(plan.chosenFor("N00001")).isNull();
        assertThat(plan.skips()).hasSize(1);
    }

    @Test
    @DisplayName("giriş zamanı eşit / yok → en küçük kimlik; kanıt (SENT > herhangi satır) girişten önce gelir")
    void tieBreakAndEvidenceOrder() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(21, "A2", "p@x.com", null),
                acc(20, "A1", "p@x.com", null)));
        assertThat(guard.plan(List.of("A2", "A1"), null).chosenFor("A2")).isEqualTo("A1");

        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(20, "A1", "p@x.com", "2026-10-20T10:00:00"),
                acc(21, "A2", "p@x.com", "2026-10-01T10:00:00")));
        // A2 bu olayda bir satıra sahip (ör. açılış PENDING) → giriş daha eski olsa da A2
        PushSamePersonGuard.Plan any = guard.plan(List.of("A1", "A2"),
                names -> Map.of("A2", new PushSamePersonGuard.Evidence(false, false, true)));
        assertThat(any.chosenFor("A1")).isEqualTo("A2");
        // SENT satırı herhangi bir satırı yener
        PushSamePersonGuard.Plan sent = guard.plan(List.of("A1", "A2"), names -> Map.of(
                "A2", new PushSamePersonGuard.Evidence(false, false, true),
                "A1", new PushSamePersonGuard.Evidence(false, true, true)));
        assertThat(sent.chosenFor("A2")).isEqualTo("A1");
    }

    @Test
    @DisplayName("teslimat dışındaki kardeş hesap bu bildirimi zaten aldıysa (aynı anahtar) teslimattaki hesap atlanır; yalnız olay satırı yetmez")
    void coveredBySiblingOutsideDelivery() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(1, "TEAMACC", "p@x.com", "2026-10-01T10:00:00"),
                acc(2, "ONCALL", "p@x.com", "2026-10-20T10:00:00")));
        PushSamePersonGuard.Plan covered = guard.plan(List.of("ONCALL"),
                names -> Map.of("TEAMACC", new PushSamePersonGuard.Evidence(true, true, true)));
        assertThat(covered.chosenFor("ONCALL")).isEqualTo("TEAMACC");

        // Kardeş yalnız aynı olayın BAŞKA bir bildirimini aldıysa (ör. açılış; bu adım push'u değil) tek aday kendisi alır
        PushSamePersonGuard.Plan other = guard.plan(List.of("ONCALL"),
                names -> Map.of("TEAMACC", new PushSamePersonGuard.Evidence(false, true, true)));
        assertThat(other.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("boş / null e-posta asla birleşmez; sistem satırı ve boş ad yok sayılır; sorgu düşerse birleştirme yok")
    void blankNeverMerged_andFailureIsSafe() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(1, "B1", "  ", null), acc(2, "B2", "  ", null), acc(3, "B3", null, null)));
        assertThat(guard.plan(List.of("B1", "B2", "B3"), null).isEmpty()).isTrue();

        assertThat(guard.plan(List.of("-", " "), null).isEmpty()).isTrue();
        verify(repo, times(1)).findSamePersonAccounts(anyCollection());   // sistem satırı / boş ad için sorgu YOK

        when(repo.findSamePersonAccounts(anyCollection())).thenThrow(new RuntimeException("db down"));
        assertThat(guard.plan(List.of("X1", "X2"), null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("sorgu verilen hesapları BÜYÜK harfle alır; kardeş hesaplar (siblingsOf) düşülen hesabı gösterir")
    void siblingsOfAndUppercaseQuery() {
        List<Collection<String>> seen = new ArrayList<>();
        when(repo.findSamePersonAccounts(anyCollection())).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return List.of(acc(1, "mgr1", "m@x.com", null), acc(2, "N7", "M@X.com", null), acc(3, "OTHER", "o@x.com", null));
        });
        Map<String, String> sib = guard.siblingsOf(List.of("mgr1"));
        assertThat(seen.get(0)).containsExactly("MGR1");
        assertThat(sib).containsExactly(Map.entry("N7", "mgr1"));
    }

    @Test
    @DisplayName("chooseByLogin: en son giriş, sonra en küçük kimlik (senaryo / özet seçimi)")
    void chooseByLogin() {
        var a = new PushSamePersonGuard.Account(5L, "A", "p@x.com", PushText.parseStoredUtc("2026-10-01T00:00:00"));
        var b = new PushSamePersonGuard.Account(4L, "B", "p@x.com", PushText.parseStoredUtc("2026-10-02T00:00:00"));
        var c = new PushSamePersonGuard.Account(3L, "C", "p@x.com", null);
        assertThat(PushSamePersonGuard.chooseByLogin(List.of(a, b, c)).username()).isEqualTo("B");
        var d = new PushSamePersonGuard.Account(2L, "D", "p@x.com", null);
        assertThat(PushSamePersonGuard.chooseByLogin(List.of(c, d)).username()).isEqualTo("D");
    }
}
