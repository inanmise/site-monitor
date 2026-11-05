package com.sitemonitor.service.forward;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertForwardRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.forward.AlertForwardRouting.AlertRef;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Alarm iletimi servisi — H2'de GERÇEK SQL ve işlemler (2026-11-05): doğrulama tablosu, takım satırı kilidi + tavan +
 * "aynı iletim var", düzenlemede yalnız gönderilen / değişen alanlar, değişmeyen gövde YAZMAZ, sürüm çatışması, takım taşıma /
 * silme, kullanıcı silme ve görüntünün gerçek üye / envanter sorgularıyla kurulması.
 *
 * <p>{@code NOT_SUPPORTED}: servis yöntemleri uygulamadaki gibi kendi işlemlerinde koşar (işlem dışı çağıran); {@code @Modifying}
 * yöntemlerin {@code @Transactional} eksikliği testte gizlenmez.
 */
@DataJpaTest
@Import(AlertForwardService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertForwardServiceH2Test {

    @Autowired AlertForwardService svc;
    @Autowired AlertForwardRepository repo;
    @Autowired TeamRepository teamRepo;
    @Autowired AppUserRepository userRepo;
    @Autowired CertificateInventoryRepository invRepo;

    NocMonitorDirectory directory;
    /** Vekil (işlem) altındaki gerçek nesne — paket görünürlüklü yardımcılar ve alanlar için. */
    AlertForwardService raw;
    long owner, ug, rt, rt2, passive;
    long ayse, mehmet, pasifKisi, baskaTakim;
    long certId;

    Team team(String name, String email, boolean active) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(email);
        t.setActive(active);
        return teamRepo.saveAndFlush(t);
    }

    AppUser user(String username, String name, String email, Long primary, boolean active, Long... extra) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(name);
        u.setEmail(email);
        u.setSystemRole("USER");
        u.setTeamId(primary);
        u.setActive(active);
        u.setTeamIds(new LinkedHashSet<>(List.of(extra)));
        return userRepo.saveAndFlush(u);
    }

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        userRepo.deleteAll();
        invRepo.deleteAll();
        teamRepo.deleteAll();
        owner = team("Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme Müdürlüğü)", "siparis@example.com", true).getId();
        ug = team("Ödeme", "odeme@example.com", true).getId();
        rt = team("Ağ Operasyon", "noc@example.com", true).getId();
        rt2 = team("Güvenlik", "guvenlik@example.com", true).getId();
        passive = team("Eski Takım", "eski@example.com", false).getId();
        ayse = user("ayse", "Ayşe Yılmaz (Ağ Operasyon / Kıdemli Uzman)", "ayse@example.com", rt, true).getId();
        mehmet = user("mehmet", "Mehmet Kaya", "mehmet@example.com", ug, true, rt).getId();   // ek üyelik
        pasifKisi = user("pasif", "Pasif Kişi", "pasif@example.com", rt, false).getId();
        baskaTakim = user("baska", "Başka Takım", "baska@example.com", rt2, true).getId();
        CertificateInventory c = new CertificateInventory();
        c.setDomain("Shop.Example.com");
        c.setTeamId(owner);
        c.setActive(true);
        certId = invRepo.saveAndFlush(c).getId();
        directory = mock(NocMonitorDirectory.class);
        when(directory.ofType(any())).thenReturn(List.of());
        when(directory.ofType(NocType.HTTP)).thenReturn(List.of(
                new NocMonitorDirectory.Row(NocType.HTTP, 5, "Vitrin", "https://shop.example.com", owner, null, true, false, null, false),
                new NocMonitorDirectory.Row(NocType.HTTP, 6, "Başkası", "https://x.example.com", rt2, null, true, false, null, false)));
        when(directory.ofType(NocType.SSL)).thenReturn(List.of(
                new NocMonitorDirectory.Row(NocType.SSL, certId, "shop.example.com", "shop.example.com", rt2, owner, true, false, null, false)));
        raw = org.springframework.test.util.AopTestUtils.getTargetObject(svc);
        raw.setDirectory(directory);
        raw.invalidateLocal();
    }

    Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    Map<String, Object> teamBody(long recipient) {
        return body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", recipient);
    }

    static String fieldOf(Throwable t) {
        return ((GlobalExceptionHandler.FieldValidationException) t).getFields().keySet().iterator().next();
    }

    @Test
    @DisplayName("doğrulama tablosu: her geçersiz alan kendi alan adıyla 400")
    void validationTable() {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("owner_team_id", body("owner_team_id", passive, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", rt));
        cases.put("scope_kind", body("owner_team_id", owner, "scope_kind", "SOME", "recipient_kind", "TEAM", "recipient_team_id", rt));
        cases.put("scope_types", body("owner_team_id", owner, "scope_kind", "TYPES", "scope_types", List.of(), "recipient_kind", "TEAM", "recipient_team_id", rt));
        Map<String, Object> badType = body("owner_team_id", owner, "scope_kind", "TYPES", "scope_types", List.of("http", "ssl"),
                "recipient_kind", "TEAM", "recipient_team_id", rt);
        cases.put("scope_types#bilinmeyen", badType);
        cases.put("scope_monitors", body("owner_team_id", owner, "scope_kind", "MONITORS", "scope_monitors", List.of(),
                "recipient_kind", "TEAM", "recipient_team_id", rt));
        cases.put("scope_monitors#baskasinin", body("owner_team_id", owner, "scope_kind", "MONITORS",
                "scope_monitors", List.of(Map.of("type", "http", "id", 6)), "recipient_kind", "TEAM", "recipient_team_id", rt));
        cases.put("scope_monitors#yok", body("owner_team_id", owner, "scope_kind", "MONITORS",
                "scope_monitors", List.of(Map.of("type", "http", "id", 999)), "recipient_kind", "TEAM", "recipient_team_id", rt));
        cases.put("recipient_kind", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "X", "recipient_team_id", rt));
        cases.put("recipient_team_id", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", passive));
        cases.put("recipient_team_id#kendisi", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", owner));
        cases.put("expires_on", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", rt,
                "expires_on", "2020-01-01"));
        cases.put("expires_on#bicim", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", rt,
                "expires_on", "05.11.2026"));
        cases.put("expires_on#ust", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", rt,
                "expires_on", "2100-01-01"));
        cases.put("note", body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", rt,
                "note", "x".repeat(501)));
        for (Map.Entry<String, Map<String, Object>> e : cases.entrySet()) {
            String expected = e.getKey().contains("#") ? e.getKey().substring(0, e.getKey().indexOf('#')) : e.getKey();
            assertThatThrownBy(() -> svc.create(e.getValue(), null, "admin")).as(e.getKey())
                    .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                    .satisfies(t -> assertThat(fieldOf(t)).as(e.getKey()).isEqualTo(expected));
        }
        // Üyeler: boş, pasif, takımdan değil, çözülemeyen (null), 51 kişi
        Map<String, Object> members = body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "MEMBERS", "recipient_team_id", rt);
        List<List<Long>> bad = new ArrayList<>();
        bad.add(List.of());
        bad.add(List.of(pasifKisi));
        bad.add(List.of(baskaTakim));
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);
        bad.add(withNull);
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 51; i++) many.add(10_000 + i);
        bad.add(many);
        for (List<Long> ids : bad) {
            assertThatThrownBy(() -> svc.create(members, ids, "admin")).as(String.valueOf(ids))
                    .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                    .satisfies(t -> assertThat(fieldOf(t)).isEqualTo("recipient_user_ids"));
        }
        assertThat(repo.count()).isZero();
    }

    @Test
    @DisplayName("oluşturma: kapsam / alıcı CSV'leri kanonik; cert öğesi envanterin UG sahibine de geçerli; yeni alıcılar raporlanır")
    void createCanonical() {
        AlertForwardService.Saved s = svc.create(body("owner_team_id", owner, "scope_kind", "MONITORS",
                "scope_monitors", List.of(Map.of("type", "http", "id", 5), Map.of("type", "cert", "id", certId), Map.of("type", "HTTP", "id", "5")),
                "recipient_kind", "MEMBERS", "recipient_team_id", rt, "note", "  Gece nöbeti  ", "expires_on", "2099-12-31"),
                List.of(mehmet, ayse, ayse), "ayse.admin");
        AlertForward f = repo.findById(s.forward().getId()).orElseThrow();
        assertThat(f.getScopeMonitors()).isEqualTo("cert:" + certId + ",http:5");
        assertThat(f.getRecipientUserIds()).isEqualTo(Math.min(ayse, mehmet) + "," + Math.max(ayse, mehmet));
        assertThat(f.getNote()).isEqualTo("Gece nöbeti");
        assertThat(f.getExpiresAt()).isEqualTo("2099-12-31T21:00:00");
        assertThat(f.getCreatedBy()).isEqualTo("ayse.admin");
        assertThat(f.isEnabled()).isTrue();
        assertThat(s.newWholeTeam()).isFalse();
        assertThat(s.newMemberIds()).containsExactlyInAnyOrder(mehmet, ayse);

        AlertForwardService.Saved t = svc.create(body("owner_team_id", owner, "scope_kind", "TYPES",
                "scope_types", List.of("ping", "CERT", "http"), "recipient_kind", "TEAM", "recipient_team_id", rt2, "enabled", false), null, "a");
        AlertForward g = repo.findById(t.forward().getId()).orElseThrow();
        assertThat(g.getScopeTypes()).isEqualTo("cert,http,ping");
        assertThat(g.isEnabled()).isFalse();
        assertThat(g.getRecipientUserIds()).isNull();
        assertThat(t.newWholeTeam()).isTrue();
    }

    @Test
    @DisplayName("409: aynı sahip + aynı alıcı + aynı kapsam FORWARD_EXISTS; takım başına 100 FORWARD_LIMIT")
    void existsAndLimit() {
        svc.create(teamBody(rt), null, "a");
        assertThatThrownBy(() -> svc.create(teamBody(rt), null, "a"))
                .isInstanceOf(GlobalExceptionHandler.CodedConflictException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.CodedConflictException) e).getCode()).isEqualTo("FORWARD_EXISTS"));
        // Farklı kapsam aynı alıcıya → izinli
        svc.create(body("owner_team_id", owner, "scope_kind", "TYPES", "scope_types", List.of("http"), "recipient_kind", "TEAM",
                "recipient_team_id", rt), null, "a");
        // Tavan: 100'e tamamla
        for (int i = 0; i < 98; i++) {
            AlertForward f = new AlertForward();
            f.setOwnerTeamId(owner);
            f.setScopeKind("MONITORS");
            f.setScopeMonitors("http:" + (1000 + i));
            f.setRecipientKind("TEAM");
            f.setRecipientTeamId(rt2);
            repo.saveAndFlush(f);
        }
        assertThat(repo.countByOwnerTeamId(owner)).isEqualTo(100);
        assertThatThrownBy(() -> svc.create(teamBody(rt2), null, "a"))
                .isInstanceOf(GlobalExceptionHandler.CodedConflictException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.CodedConflictException) e).getCode()).isEqualTo("FORWARD_LIMIT"));
    }

    @Test
    @DisplayName("düzenleme: değişmeyen gövde YAZMAZ; yalnız gönderilen alan; sahip değişmez; sürüm çatışması 409; yeni üyeler raporlanır")
    void update() {
        AlertForward f = svc.create(body("owner_team_id", owner, "scope_kind", "ALL", "recipient_kind", "MEMBERS",
                "recipient_team_id", rt, "note", "not"), List.of(ayse), "a").forward();
        AlertForward before = repo.findById(f.getId()).orElseThrow();

        AlertForwardService.Saved none = svc.update(f.getId(), body("note", "not", "scope_kind", "ALL", "recipient_user_ids", "x"),
                List.of(ayse), "b");
        assertThat(none.changed()).isFalse();
        AlertForward after = repo.findById(f.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedBy()).isEqualTo("a");

        assertThatThrownBy(() -> svc.update(f.getId(), body("owner_team_id", rt2), null, "b"))
                .satisfies(t -> assertThat(fieldOf(t)).isEqualTo("owner_team_id"));
        assertThatThrownBy(() -> svc.update(f.getId(), body("version", 99, "note", "yeni"), null, "b"))
                .isInstanceOf(GlobalExceptionHandler.CodedConflictException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.CodedConflictException) e).getCode()).isEqualTo("CONCURRENT_MODIFICATION"));

        // Üye ekle: yalnız YENİ üye raporlanır; mevcut üye pasifleşse bile yeniden doğrulanmaz
        userRepo.findById(ayse).ifPresent(u -> { u.setActive(false); userRepo.saveAndFlush(u); });
        AlertForwardService.Saved add = svc.update(f.getId(), body("recipient_user_ids", "x", "version", before.getVersion()),
                List.of(ayse, mehmet), "b");
        assertThat(add.changed()).isTrue();
        assertThat(add.newMemberIds()).containsExactly(mehmet);
        assertThat(add.newWholeTeam()).isFalse();
        AlertForward cur = repo.findById(f.getId()).orElseThrow();
        assertThat(cur.getNote()).as("gönderilmeyen alan değişmedi").isEqualTo("not");
        assertThat(cur.getUpdatedBy()).isEqualTo("b");

        // Takım değişti → üyeler yeniden doğrulanır (rt2'de ayse yok → 400); TEAM'e geçiş → tüm takım yeni
        assertThatThrownBy(() -> svc.update(f.getId(), body("recipient_team_id", rt2, "recipient_user_ids", "x"), List.of(mehmet), "b"))
                .satisfies(t -> assertThat(fieldOf(t)).isEqualTo("recipient_user_ids"));
        AlertForwardService.Saved whole = svc.update(f.getId(), body("recipient_kind", "TEAM", "recipient_team_id", rt2), null, "b");
        assertThat(whole.newWholeTeam()).isTrue();
        assertThat(repo.findById(f.getId()).orElseThrow().getRecipientUserIds()).isNull();

        // TYPES'a geçip tür göndermemek → 400; MONITORS'a geçince yeni öğe doğrulanır
        assertThatThrownBy(() -> svc.update(f.getId(), body("scope_kind", "TYPES"), null, "b"))
                .satisfies(t -> assertThat(fieldOf(t)).isEqualTo("scope_types"));
        svc.update(f.getId(), body("scope_kind", "MONITORS", "scope_monitors", List.of(Map.of("type", "http", "id", 5))), null, "b");
        // kayıtlı öğe artık sahip takımın değil (dizin değişti) → DEĞİŞMEYEN öğe yeniden doğrulanmaz
        when(directory.ofType(NocType.HTTP)).thenReturn(List.of());
        AlertForwardService.Saved keep = svc.update(f.getId(), body("scope_monitors", List.of(Map.of("type", "http", "id", 5)),
                "note", "değişti"), null, "b");
        assertThat(keep.changed()).isTrue();
        assertThat(repo.findById(f.getId()).orElseThrow().getScopeMonitors()).isEqualTo("http:5");
        // bitiş değişmeden gönderilirse yeniden doğrulanmaz
        AlertForwardService.Saved same = svc.update(f.getId(), body("expires_on", null), null, "b");
        assertThat(same.changed()).isFalse();
    }

    @Test
    @DisplayName("görüntü gerçek sorgularla: ek üyelik sayılır, pasif üye düşer, cert alan adı envanterden; yazma sonrası tazelenir")
    void snapshotFromRealQueries() {
        svc.create(body("owner_team_id", owner, "scope_kind", "MONITORS", "scope_monitors", List.of(Map.of("type", "cert", "id", certId)),
                "recipient_kind", "MEMBERS", "recipient_team_id", rt), List.of(ayse, mehmet), "a");
        userRepo.findById(ayse).ifPresent(u -> { u.setActive(false); userRepo.saveAndFlush(u); });
        raw.invalidateLocal();
        assertThat(raw.current()).isNotNull();
        List<ForwardTarget> t = svc.targetsFor(new AlertRef(List.of(owner), "EXPIRY", "shop.example.COM", null));
        assertThat(t).hasSize(1);
        assertThat(t.get(0).memberUserIds()).containsExactly(mehmet);
        assertThat(t.get(0).emails()).containsExactly("mehmet@example.com");
        assertThat(svc.addressCount(t.get(0).forwardIds().get(0))).isEqualTo(1);
        // silme → bayat + yeniden kurulum iletimsiz
        svc.delete(t.get(0).forwardIds().get(0));
        assertThat(raw.current()).isNotNull();
        assertThat(svc.anyFor(List.of(owner))).isFalse();
    }

    @Test
    @DisplayName("takım taşıma: sahibi olduğu iletimler hedefe geçer; gelen ve hedefe giden silinir — işlemsiz çağıran")
    void moveTeam() {
        long toRt = svc.create(teamBody(rt), null, "a").forward().getId();
        long toUg = svc.create(teamBody(ug), null, "a").forward().getId();
        long incoming = svc.create(body("owner_team_id", rt2, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", owner),
                null, "a").forward().getId();
        long other = svc.create(body("owner_team_id", rt2, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", rt),
                null, "a").forward().getId();
        Long v = repo.findById(toRt).orElseThrow().getVersion();
        AlertForwardService.Moved m = svc.moveTeam(owner, ug);
        assertThat(m.moved()).isEqualTo(1);
        assertThat(m.removed()).isEqualTo(2);
        AlertForward moved = repo.findById(toRt).orElseThrow();
        assertThat(moved.getOwnerTeamId()).isEqualTo(ug);
        assertThat(moved.getVersion()).isGreaterThan(v);
        assertThat(repo.findById(toUg)).isEmpty();
        assertThat(repo.findById(incoming)).isEmpty();
        assertThat(repo.findById(other)).isPresent();
    }

    @Test
    @DisplayName("takım silme: sahibi ya da alıcısı o takım olanlar silinir; etki sayıları; kullanıcı silme üye listesinden çıkarır")
    void deleteTeamAndUser() {
        svc.create(teamBody(rt), null, "a");
        svc.create(body("owner_team_id", rt2, "scope_kind", "ALL", "recipient_kind", "TEAM", "recipient_team_id", owner), null, "a");
        long members = svc.create(body("owner_team_id", rt2, "scope_kind", "ALL", "recipient_kind", "MEMBERS", "recipient_team_id", rt),
                List.of(ayse, mehmet), "a").forward().getId();
        Map<String, Object> impact = svc.impactOf(owner);
        assertThat(impact).containsEntry("owned", 1L).containsEntry("incoming", 1L);

        assertThat(svc.removeUser(ayse)).isEqualTo(1);
        assertThat(repo.findById(members).orElseThrow().getRecipientUserIds()).isEqualTo(String.valueOf(mehmet));
        assertThat(svc.removeUser(9_999_999L)).isZero();

        assertThat(svc.deleteForTeam(owner)).isEqualTo(2);
        assertThat(repo.findAll()).extracting(AlertForward::getId).containsExactly(members);
    }

    @Test
    @DisplayName("üye projeksiyonu (JPQL): birincil + ek üyelikler tek kayıtta, fotoğraf okunmaz; bulunamayan kimlik yok")
    void memberRows() {
        Map<Long, AlertForwardService.Member> m = svc.members(List.of(ayse, mehmet, 123456L));
        assertThat(m).containsOnlyKeys(ayse, mehmet);
        assertThat(m.get(mehmet).teams()).containsExactlyInAnyOrder(ug, rt);
        assertThat(m.get(mehmet).inTeam(rt)).isTrue();
        assertThat(m.get(ayse).name()).isEqualTo("Ayşe Yılmaz (Ağ Operasyon / Kıdemli Uzman)");
        Set<Long> ids = Set.of(ayse);
        assertThat(svc.memberEmails(rt, ids)).containsExactly("ayse@example.com");
        assertThat(svc.memberEmails(rt2, ids)).isEmpty();
        assertThat(svc.teamAlarmEmails(rt)).containsExactly("noc@example.com");
        assertThat(svc.teamAlarmEmails(passive)).isEmpty();
    }

}
