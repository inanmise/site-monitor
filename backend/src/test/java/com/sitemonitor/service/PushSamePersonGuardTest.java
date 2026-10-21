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
 * {@code UserPushSamePersonTest}'te; burada seçim sırası ve gruplama: e-posta (kırpılmış, harf duyarsız) ve kişi adı aynı aktif
 * hesaplar tek kişidir; boş adres asla birleşmez; seçim = bu bildirimi almış hesap → yoksa GÖNDERİLEBİLİR hesaplar (tavan altı →
 * bu olayda SENT → PENDING → son giriş → en küçük kimlik); hiçbiri gönderilebilir değilse birleştirme yok. Fixture kimlikleri
 * SAHTE sicillerdir.
 */
class PushSamePersonGuardTest {

    private final AppUserRepository repo = mock(AppUserRepository.class);
    private final PushSamePersonGuard guard = new PushSamePersonGuard(repo);

    private static Object[] acc(long id, String username, String email, String lastLogin) {
        return new Object[]{id, username, email, lastLogin};
    }

    private static Object[] acc(long id, String username, String email, String lastLogin, String displayName) {
        return new Object[]{id, username, email, lastLogin, displayName};
    }

    private static PushSamePersonGuard.Account a(long id, String username, String email, String displayName) {
        return new PushSamePersonGuard.Account(id, username, email, null, displayName);
    }

    /** Kişi adı katlama doğruluk tablosu: girdi → beklenen anahtar ({@code null} = boş ad). */
    private static final String[][] PERSON_NAME_TABLE = {
            {"Emre Gökoğlu MP60146", "emre gokoglu"},
            {"Emre Gökoğlu N60146", "emre gokoglu"},
            {"EMRE GÖKOĞLU", "emre gokoglu"},
            {"  emre   gokoglu  ", "emre gokoglu"},
            {"İsmail Işık (Dijital Kanallar / Uzman)", "ismail isik"},
            {"ISMAIL IŞIK", "ismail isik"},
            {"Şükrü Çağlar Öztürk", "sukru caglar ozturk"},
            {"Ali (Müdür (Vekil)) Veli", "ali veli"},
            {"Ünal, Öz.", "unal oz"},
            {"N60146", null},
            {"(Dijital Kanallar)", null},
            {"   ", null},
            {"- 12 -", null},
            {"Ayşe Yılmaz", "ayse yilmaz"},
            {"Mehmet Demir", "mehmet demir"},
    };

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

    @Test
    @DisplayName("kişi adı: Türkçe harf / aksan katlanır, parantezli kısım ve sicil (rakamlı sözcük) atılır, boşluklar teke iner")
    void personNameTruthTable() {
        for (String[] row : PERSON_NAME_TABLE) {
            assertThat(PushSamePersonGuard.personName(row[0])).as(row[0]).isEqualTo(row[1]);
        }
        assertThat(PushSamePersonGuard.personName(null)).isNull();
        assertThat(PushSamePersonGuard.personName("Emre Gökoğlu " + "x".repeat(10_000))).as("uzun girdi sınırlı").isNotNull();
    }

    @Test
    @DisplayName("gruplama: aynı e-posta + aynı ad birleşir; ortak kutudaki FARKLI kişiler birleşmez; boş ad tek ada katılır, iki ad varsa katılmaz")
    void groupPersonsTruthTable() {
        // #628: aynı kişi, iki sicil
        assertThat(PushSamePersonGuard.groupPersons(List.of(
                a(1, "MP60146", "Emre.Gokoglu@Example.com ", "Emre Gökoğlu MP60146"),
                a(2, "N60146", "emre.gokoglu@example.com", "EMRE GÖKOĞLU N60146"))))
                .singleElement().satisfies(g -> assertThat(g).extracting(PushSamePersonGuard.Account::username)
                        .containsExactly("MP60146", "N60146"));
        // Ortak posta kutusu, farklı kişiler
        assertThat(PushSamePersonGuard.groupPersons(List.of(
                a(1, "A1", "kutu@x.com", "Ayşe Yılmaz"), a(2, "A2", "kutu@x.com", "Mehmet Demir")))).isEmpty();
        // Adı boş hesap tek ada katılır
        assertThat(PushSamePersonGuard.groupPersons(List.of(
                a(1, "A1", "p@x.com", "Ayşe Yılmaz"), a(2, "A2", "p@x.com", "  "))))
                .singleElement().satisfies(g -> assertThat(g).hasSize(2));
        // Adı boş hesap, iki farklı ad varken hiçbirine katılmaz; aynı adlı iki hesap yine birleşir
        assertThat(PushSamePersonGuard.groupPersons(List.of(
                a(1, "A1", "p@x.com", "Ayşe Yılmaz"), a(2, "A2", "p@x.com", "Mehmet Demir"),
                a(3, "A3", "p@x.com", null), a(4, "A4", "p@x.com", "AYŞE YILMAZ (Uzman)"))))
                .singleElement().satisfies(g -> assertThat(g).extracting(PushSamePersonGuard.Account::username)
                        .containsExactly("A1", "A4"));
        // İki ad da boş → yalnız e-posta
        assertThat(PushSamePersonGuard.groupPersons(List.of(a(1, "A1", "p@x.com", null), a(2, "A2", "P@x.com", ""))))
                .singleElement().satisfies(g -> assertThat(g).hasSize(2));
        // Farklı e-posta asla
        assertThat(PushSamePersonGuard.groupPersons(List.of(
                a(1, "A1", "p@x.com", "Ayşe Yılmaz"), a(2, "A2", "q@x.com", "Ayşe Yılmaz")))).isEmpty();
    }

    @Test
    @DisplayName("plan: ortak posta kutusundaki farklı kişiler (sorgu ad döndürür) birleşmez")
    void planHonoursNames() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(1, "A1", "kutu@x.com", "2026-10-19T08:00:00", "Ayşe Yılmaz"),
                acc(2, "A2", "kutu@x.com", "2026-10-19T09:00:00", "Mehmet Demir")));
        assertThat(guard.plan(List.of("A1", "A2"), null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("seçim yalnız GÖNDERİLEBİLİR hesaplar arasında: engelli hesap seçilmez, tavan altı tavandakini yener, hiçbiri yoksa birleştirme yok")
    void choiceFollowsReadiness() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(10, "MP60146", "p@x.com", "2026-10-18T08:00:00"),
                acc(11, "N60146", "p@x.com", "2026-10-19T20:00:00")));
        List<String> both = List.of("MP60146", "N60146");
        // En son giriş N, ama N engelli (opt-out) → MP
        PushSamePersonGuard.Plan blocked = guard.plan(both,
                u -> "N60146".equals(u) ? PushSamePersonGuard.Readiness.BLOCKED : PushSamePersonGuard.Readiness.DELIVERABLE, null);
        assertThat(blocked.chosenFor("N60146")).isEqualTo("MP60146");
        assertThat(blocked.chosenFor("MP60146")).isNull();
        // N tavanda → MP (kanıt N lehine olsa da tavan önce gelir)
        PushSamePersonGuard.Plan cap = guard.plan(both,
                u -> "N60146".equals(u) ? PushSamePersonGuard.Readiness.OVER_CAP : PushSamePersonGuard.Readiness.DELIVERABLE,
                names -> Map.of("N60146", new PushSamePersonGuard.Evidence(false, true, true)));
        assertThat(cap.chosenFor("N60146")).isEqualTo("MP60146");
        // İkisi de tavanda → yine tek hesap (en son giriş); satırı RATE_LIMITED olur, özet kişi başına
        PushSamePersonGuard.Plan allCap = guard.plan(both, u -> PushSamePersonGuard.Readiness.OVER_CAP, null);
        assertThat(allCap.chosenFor("MP60146")).isEqualTo("N60146");
        // Hiçbiri gönderilebilir değil → birleştirme YOK (her hesap kendi nedeni)
        assertThat(guard.plan(both, u -> PushSamePersonGuard.Readiness.BLOCKED, null).isEmpty()).isTrue();
        // Durum okunamazsa hesap engelli sayılır (yanlış hesaba "karar verildi" yazılmasın)
        PushSamePersonGuard.Plan failing = guard.plan(both, u -> {
            if ("N60146".equals(u)) throw new IllegalStateException("db");
            return PushSamePersonGuard.Readiness.DELIVERABLE;
        }, null);
        assertThat(failing.chosenFor("N60146")).isEqualTo("MP60146");
    }

    @Test
    @DisplayName("bu bildirimin aynısını ALMIŞ hesap (kesin kanıt) her zaman kazanır — o an engelli olsa da kişi bildirimi almıştır")
    void exactEvidenceWins() {
        when(repo.findSamePersonAccounts(anyCollection())).thenReturn(List.of(
                acc(10, "MP60146", "p@x.com", "2026-10-18T08:00:00"),
                acc(11, "N60146", "p@x.com", "2026-10-19T20:00:00")));
        PushSamePersonGuard.Plan p = guard.plan(List.of("MP60146", "N60146"),
                u -> "MP60146".equals(u) ? PushSamePersonGuard.Readiness.OVER_CAP : PushSamePersonGuard.Readiness.DELIVERABLE,
                names -> Map.of("MP60146", new PushSamePersonGuard.Evidence(true, true, true)));
        assertThat(p.chosenFor("N60146")).isEqualTo("MP60146");
        assertThat(PushSamePersonGuard.delivering("SENT")).isTrue();
        assertThat(PushSamePersonGuard.delivering("PENDING")).isTrue();
        assertThat(PushSamePersonGuard.delivering("QUEUED_RETRY")).isTrue();
        assertThat(PushSamePersonGuard.delivering("RATE_LIMITED")).isFalse();
        assertThat(PushSamePersonGuard.delivering(PushSamePersonGuard.SKIPPED_SAME_PERSON)).isFalse();
        assertThat(PushSamePersonGuard.delivering(null)).isFalse();
    }
}
