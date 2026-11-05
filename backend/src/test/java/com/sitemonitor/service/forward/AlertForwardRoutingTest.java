package com.sitemonitor.service.forward;

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Alarm iletimi yönlendirmesi (2026-11-05) — {@link AlertForwardService#targetsFor} / {@link AlertForwardService#anyFor}.
 *
 * <p>Değişmezler: kapsam eşleşmesi (ALL / TYPES / MONITORS; cert alan adıyla; ACCESSIBILITY → cert; bilinmeyen tür YALNIZ
 * ALL); aynı alıcı takıma giden iletimler TEK hedefte birleşir; pasif / takımdan ayrılmış üye, pasif alıcı takım, sahiplerden
 * biri olan alıcı, süresi dolmuş ve duraklatılmış iletim atlanır; iki okuma da veritabanına GİTMEZ (yalnız bellek).
 */
class AlertForwardRoutingTest {

    static final long SY = 10, UG = 11, RT = 20, RT2 = 21, PASSIVE = 22;
    static final String NOW = "2026-11-05T10:00:00";

    AlertForwardRepository repo;
    TeamRepository teamRepo;
    NotificationGroupService groups;
    InactiveRecipientGuard guard;
    AlertForwardService svc;
    final List<AlertForward> rows = new ArrayList<>();
    final List<Object[]> memberRows = new ArrayList<>();
    final List<Object[]> invRows = new ArrayList<>();
    final AtomicLong clock = new AtomicLong(java.time.Instant.parse("2026-11-05T10:00:00Z").toEpochMilli());

    static Team team(long id, String name, String email, boolean active) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setEmail(email);
        t.setActive(active);
        return t;
    }

    static AlertForward fwd(long id, long owner, String scope, String types, String monitors, String kind, long rt, String users) {
        AlertForward f = new AlertForward();
        f.setId(id);
        f.setVersion(0L);
        f.setOwnerTeamId(owner);
        f.setScopeKind(scope);
        f.setScopeTypes(types);
        f.setScopeMonitors(monitors);
        f.setRecipientKind(kind);
        f.setRecipientTeamId(rt);
        f.setRecipientUserIds(users);
        f.setEnabled(true);
        return f;
    }

    @BeforeEach
    void setUp() {
        repo = mock(AlertForwardRepository.class);
        teamRepo = mock(TeamRepository.class);
        groups = mock(NotificationGroupService.class);
        guard = mock(InactiveRecipientGuard.class);
        when(repo.findAllByOrderByIdAsc()).thenAnswer(i -> new ArrayList<>(rows));
        when(repo.findMemberRows(anyCollection())).thenAnswer(i -> new ArrayList<>(memberRows));
        when(repo.findInventoryDomains(anyCollection())).thenAnswer(i -> new ArrayList<>(invRows));
        when(teamRepo.findAllById(any())).thenReturn(List.of(
                team(SY, "Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme)", "sy@example.com", true),
                team(UG, "Ödeme", "ug@example.com", true),
                team(RT, "Ağ Operasyon", "noc@example.com; noc2@example.com", true),
                team(RT2, "Güvenlik", null, true),
                team(PASSIVE, "Eski Takım", "old@example.com", false)));
        when(groups.overrideFor(anyLong(), isNull())).thenReturn(NotificationGroupService.Override.NONE);
        svc = new AlertForwardService(repo, teamRepo);
        svc.setGroups(groups);
        svc.setInactiveGuard(guard);
        svc.clock = clock::get;
    }

    void warm() {
        assertThat(svc.current()).isNotNull();
        clearInvocations(repo, teamRepo, groups, guard);
    }

    List<ForwardTarget> targets(String alertType, String domain, Long monitorId, Long... owners) {
        return svc.targetsFor(new AlertRef(List.of(owners), alertType, domain, monitorId));
    }

    @Test
    @DisplayName("kapsam: ALL her alarm; TYPES yalnız seçilen tür; bilinmeyen alarm tipi YALNIZ ALL'a eşleşir")
    void scopeAllAndTypes() {
        rows.add(fwd(1, SY, "ALL", null, null, "TEAM", RT, null));
        rows.add(fwd(2, SY, "TYPES", "http,ping", null, "TEAM", RT2, null));
        warm();
        assertThat(targets("HTTP_DOWN", "https://a.example.com", 5L, SY)).extracting(ForwardTarget::recipientTeamId)
                .containsExactly(RT, RT2);
        assertThat(targets("PORT_DOWN", "a.example.com", 5L, SY)).extracting(ForwardTarget::recipientTeamId)
                .containsExactly(RT);
        assertThat(targets("DOMAIN_EXPIRY", "a.example.com", 5L, SY)).extracting(ForwardTarget::recipientTeamId)
                .as("HTTP izlemesinin alan adı alarmı → http").containsExactly(RT, RT2);
        assertThat(targets("TOTALLY_NEW_TYPE", "x", 5L, SY)).extracting(ForwardTarget::recipientTeamId)
                .as("bilinmeyen tür yalnız ALL").containsExactly(RT);
    }

    @Test
    @DisplayName("kapsam: MONITORS (tür, kimlik) eşleşir; cert alan adıyla (küçük harf); ACCESSIBILITY → cert; kimliksiz alarm eşleşmez")
    void scopeMonitors() {
        rows.add(fwd(1, SY, "MONITORS", null, "http:5,cert:40", "TEAM", RT, null));
        invRows.add(new Object[]{40L, "Shop.Example.COM"});
        warm();
        assertThat(targets("HTTP_DOWN", "https://a", 5L, SY)).hasSize(1);
        assertThat(targets("HTTP_DOWN", "https://a", 6L, SY)).isEmpty();
        assertThat(targets("PING_DOWN", "a", 5L, SY)).as("aynı kimlik, başka tür").isEmpty();
        assertThat(targets("EXPIRY", "shop.example.com", null, SY)).hasSize(1);
        assertThat(targets("ACCESSIBILITY", "SHOP.example.com", null, SY)).as("erişilebilirlik → envanter kaydı").hasSize(1);
        assertThat(targets("EXPIRY", "other.example.com", null, SY)).isEmpty();
        assertThat(targets("HTTP_DOWN", "https://a", null, SY)).as("bağlamında monitor_id yok (eski kayıt)").isEmpty();
    }

    @Test
    @DisplayName("birleştirme: aynı alıcıya iki iletim (SY tüm takım + UG seçilen üyeler) → TEK hedef, adresler / üyeler tekil")
    void mergesPerRecipient() {
        rows.add(fwd(2, UG, "ALL", null, null, "MEMBERS", RT, "100,101"));
        rows.add(fwd(1, SY, "TYPES", "http", null, "TEAM", RT, null));
        memberRows.add(new Object[]{100L, "NOC@example.com", true, RT, null, "Ayşe Yılmaz (Ağ / Uzman)", "ayse"});
        memberRows.add(new Object[]{101L, "mehmet@example.com", true, 5L, RT, "Mehmet", "mehmet"});
        warm();
        List<ForwardTarget> t = targets("HTTP_DOWN", "https://a", 9L, SY, UG);
        assertThat(t).hasSize(1);
        ForwardTarget x = t.get(0);
        assertThat(x.recipientTeamId()).isEqualTo(RT);
        assertThat(x.recipientTeamName()).isEqualTo("Ağ Operasyon");
        assertThat(x.wholeTeam()).isTrue();
        assertThat(x.forwardIds()).containsExactly(1L, 2L);
        assertThat(x.ownerTeamNames()).containsExactlyInAnyOrder("Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme)", "Ödeme");
        assertThat(x.memberUserIds()).containsExactly(100L, 101L);
        // takım adresi noc@ + noc2@; üye NOC@ (harf farkı) tekrar etmez, mehmet@ eklenir
        assertThat(x.emails()).containsExactlyInAnyOrder("noc@example.com", "noc2@example.com", "mehmet@example.com");
    }

    @Test
    @DisplayName("üyeler: pasif ya da alıcı takımdan ayrılmış üye düşer; hiç aktif üye kalmazsa hedef dönmez")
    void inactiveAndLeftMembersDrop() {
        rows.add(fwd(1, SY, "ALL", null, null, "MEMBERS", RT, "100,101,102"));
        memberRows.add(new Object[]{100L, "a@example.com", false, RT, null, "Pasif", "p"});
        memberRows.add(new Object[]{101L, "b@example.com", true, 5L, null, "Ayrıldı", "l"});
        memberRows.add(new Object[]{102L, "c@example.com", true, RT, null, "Aktif", "a"});
        warm();
        ForwardTarget x = targets("PING_DOWN", "h", 1L, SY).get(0);
        assertThat(x.memberUserIds()).containsExactly(102L);
        assertThat(x.emails()).containsExactly("c@example.com");
        assertThat(x.wholeTeam()).isFalse();

        rows.clear();
        memberRows.clear();
        rows.add(fwd(1, SY, "ALL", null, null, "MEMBERS", RT, "100"));
        memberRows.add(new Object[]{100L, "a@example.com", false, RT, null, "Pasif", "p"});
        svc.invalidateLocal();
        warm();
        assertThat(targets("PING_DOWN", "h", 1L, SY)).as("aktif üye yok → hedef yok").isEmpty();
        assertThat(svc.anyFor(List.of(SY))).as("kaba ön kontrol iletimi görür; targetsFor boş döner").isTrue();
    }

    @Test
    @DisplayName("atlananlar: pasif alıcı takım, sahiplerden biri olan alıcı, süresi dolmuş ve duraklatılmış iletim")
    void skipped() {
        rows.add(fwd(1, SY, "ALL", null, null, "TEAM", PASSIVE, null));
        rows.add(fwd(2, SY, "ALL", null, null, "TEAM", UG, null));   // UG da sahip → zaten alıyor
        AlertForward expired = fwd(3, SY, "ALL", null, null, "TEAM", RT, null);
        expired.setExpiresAt("2026-11-05T10:00:00");   // tam şimdi biter
        rows.add(expired);
        AlertForward paused = fwd(4, SY, "ALL", null, null, "TEAM", RT2, null);
        paused.setEnabled(false);
        rows.add(paused);
        warm();
        assertThat(targets("HTTP_DOWN", "u", 1L, SY, UG)).isEmpty();
        assertThat(svc.anyFor(List.of(SY, UG))).isFalse();
        // yalnız SY sahipken UG alıcıdır
        assertThat(targets("HTTP_DOWN", "u", 1L, SY)).extracting(ForwardTarget::recipientTeamId).containsExactly(UG);
        assertThat(svc.anyFor(List.of(SY))).isTrue();
        // süre gelecekte → canlı
        clock.set(java.time.Instant.parse("2026-11-05T09:00:00Z").toEpochMilli());
        assertThat(targets("HTTP_DOWN", "u", 1L, SY, UG)).extracting(ForwardTarget::recipientTeamId).containsExactly(RT);
    }

    @Test
    @DisplayName("adres: TEAM türünde bildirim grubu takım adresinden önce; pasif-yalnız adres düşer; adresi yoksa da (push) hedef döner")
    void teamEmails() {
        rows.add(fwd(1, SY, "ALL", null, null, "TEAM", RT, null));
        rows.add(fwd(2, SY, "ALL", null, null, "TEAM", RT2, null));   // takım adresi yok
        when(groups.overrideFor(eq(RT), isNull())).thenReturn(new NotificationGroupService.Override(
                List.of("grup@example.com", "eski.kisi@example.com", "bozuk-adres"), NotificationGroupService.Source.TEAM_DEFAULT_GROUP, 3L, "Nöbet"));
        when(guard.isInactiveOnlyEmail(anyString())).thenAnswer(i -> "eski.kisi@example.com".equals(i.getArgument(0)));
        warm();
        List<ForwardTarget> t = targets("PING_DOWN", "h", 1L, SY);
        assertThat(t).extracting(ForwardTarget::recipientTeamId).containsExactly(RT, RT2);
        assertThat(t.get(0).emails()).containsExactly("grup@example.com");
        assertThat(t.get(1).emails()).isEmpty();
        assertThat(t.get(1).wholeTeam()).as("adres yok ama takımın push'u var").isTrue();
    }

    @Test
    @DisplayName("anyFor / targetsFor YALNIZ bellek: ısınmadan sonra hiçbir depo çağrısı yok; hiç kurulmamışsa boş (sorgu yok)")
    void memoryOnly() {
        rows.add(fwd(1, SY, "MONITORS", null, "http:5,cert:40", "MEMBERS", RT, "100"));
        memberRows.add(new Object[]{100L, "a@example.com", true, RT, null, "A", "a"});
        invRows.add(new Object[]{40L, "shop.example.com"});
        // Hiç kurulmamış görüntü: sorgu YOK, iletim yok (arka plan tazeleyicisi yokken tetik de yok)
        assertThat(svc.anyFor(List.of(SY))).isFalse();
        assertThat(targets("HTTP_DOWN", "u", 5L, SY)).isEmpty();
        verifyNoInteractions(repo, teamRepo, groups);

        warm();
        for (int i = 0; i < 50; i++) {
            assertThat(svc.anyFor(List.of(SY, UG))).isTrue();
            assertThat(targets("HTTP_DOWN", "u", 5L, SY, UG)).hasSize(1);
            assertThat(targets("EXPIRY", "shop.example.com", null, SY)).hasSize(1);
        }
        // TTL geçse bile bellek okuması DB'ye gitmez (bayat görüntü kullanılır, tazeleme arka plana bırakılır)
        clock.addAndGet(10 * 60_000L);
        assertThat(svc.anyFor(List.of(SY))).isTrue();
        assertThat(targets("HTTP_DOWN", "u", 5L, SY)).hasSize(1);
        verifyNoInteractions(repo, teamRepo, groups, guard);
    }

    @Test
    @DisplayName("okuma hatası önceki görüntüyü korur; bayat işaretleme veriyi silmez")
    void readFailureKeepsPrevious() {
        rows.add(fwd(1, SY, "ALL", null, null, "TEAM", RT, null));
        warm();
        when(repo.findAllByOrderByIdAsc()).thenThrow(new RuntimeException("db down"));
        svc.invalidateLocal();
        assertThat(svc.current()).isNotNull();
        assertThat(targets("HTTP_DOWN", "u", 1L, SY)).hasSize(1);
        verify(repo, times(1)).findAllByOrderByIdAsc();
    }

    @Test
    @DisplayName("sıralama: hedefler alıcı takım kimliğine göre artan; sahip adı takım adı")
    void sortedByRecipient() {
        rows.add(fwd(1, SY, "ALL", null, null, "TEAM", RT2, null));
        rows.add(fwd(2, SY, "ALL", null, null, "TEAM", RT, null));
        warm();
        List<ForwardTarget> t = targets("PING_DOWN", "h", 1L, SY);
        assertThat(t).extracting(ForwardTarget::recipientTeamId).containsExactly(RT, RT2);
        assertThat(t.get(0).ownerTeamNames()).containsExactly("Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme)");
    }

    @Test
    @DisplayName("boş / null girdi: sorgu yok, boş sonuç")
    void nullInputs() {
        assertThat(svc.anyFor(null)).isFalse();
        assertThat(svc.anyFor(List.of())).isFalse();
        assertThat(svc.targetsFor(null)).isEmpty();
        assertThat(svc.targetsFor(new AlertRef(List.of(), "HTTP_DOWN", "x", 1L))).isEmpty();
        Collection<Long> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(svc.targetsFor(new AlertRef(withNull, "HTTP_DOWN", "x", 1L))).isEmpty();
        verifyNoInteractions(repo, teamRepo);
    }

    @Test
    @DisplayName("tür eşlemesi: SSL → cert, DBQUERY → dbquery, bilinmeyen → null; tür anahtarı → NocType")
    void typeMapping() {
        assertThat(AlertForwardService.typeKeyOfAlert("EXPIRY")).isEqualTo("cert");
        assertThat(AlertForwardService.typeKeyOfAlert("ACCESSIBILITY")).isEqualTo("cert");
        assertThat(AlertForwardService.typeKeyOfAlert("HTTP_SSL")).isEqualTo("http");
        assertThat(AlertForwardService.typeKeyOfAlert("KEYWORD_CHANGED")).isEqualTo("keyword");
        assertThat(AlertForwardService.typeKeyOfAlert("PAGESPEED_SLOW")).isEqualTo("pagespeed");
        assertThat(AlertForwardService.typeKeyOfAlert("DBQUERY_THRESHOLD")).isEqualTo("dbquery");
        assertThat(AlertForwardService.typeKeyOfAlert("DOMAINMON_EXPIRY")).isEqualTo("domain");
        assertThat(AlertForwardService.typeKeyOfAlert("NOPE")).isNull();
        for (String k : AlertForwardService.TYPE_KEYS) {
            assertThat(AlertForwardService.keyOf(AlertForwardService.nocTypeOf(k))).as(k).isEqualTo(k);
        }
        assertThat(AlertForwardService.nocTypeOf("ssl")).as("tür anahtarı 'cert' — 'ssl' değil").isNull();
    }

    @Test
    @DisplayName("CSV ayrıştırma sınırlı ve hoşgörülü: bozuk öğe atlanır, yinelenen tekilleşir, tür sırası katalog sırası")
    void csvParsing() {
        assertThat(AlertForwardService.parseIds("3, 1,x,3,,-2")).containsExactly(3L, 1L);
        assertThat(AlertForwardService.parseTypes("ping,CERT,bogus,http,ping")).containsExactly("cert", "http", "ping");
        assertThat(AlertForwardService.parseMonitors("http:5,cert:40,nope:1,http:x,http:5,ping:-1"))
                .containsExactly(new AlertForwardService.MonitorRef("http", 5), new AlertForwardService.MonitorRef("cert", 40));
        assertThat(AlertForwardService.monitorsCsv(List.of(new AlertForwardService.MonitorRef("ping", 2),
                new AlertForwardService.MonitorRef("cert", 9), new AlertForwardService.MonitorRef("ping", 1))))
                .isEqualTo("cert:9,ping:1,ping:2");
        assertThat(AlertForwardService.splitEmails("a@x.com; b@y.com,c@z.com  d@w.org")).containsExactly("a@x.com", "b@y.com", "c@z.com", "d@w.org");
        assertThat(AlertForwardService.deliverable("a@x.com")).isTrue();
        assertThat(AlertForwardService.deliverable("a@x")).isFalse();
        assertThat(AlertForwardService.deliverable("a@@x.com")).isFalse();
        assertThat(AlertForwardService.deliverable("bozuk")).isFalse();
        // çok uzun CSV: en çok CSV_MAX_ITEMS öğe okunur
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 5000; i++) big.append(i).append(',');
        assertThat(AlertForwardService.parseIds(big.toString())).hasSize(AlertForwardService.CSV_MAX_ITEMS);
    }
}
