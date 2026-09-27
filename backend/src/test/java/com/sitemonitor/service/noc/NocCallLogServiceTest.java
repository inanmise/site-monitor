package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocCallLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 7/24 arama kaydı servisi: doğrulama (sonuç/kişi/zaman/not), yazma yetkisi (kapsamlı müdür tuzağı), okuma kapsamı,
 * silme penceresi, kişi seçicisi sırası ve TELEFONSUZ yanıtlar.
 *
 * <p>Zaman: "şimdi" testte SABİTLENMEZ, gerçek saatten türetilir ve servisin saatine verilir — sabit bir takvim tarihi
 * kayan pencerelere karşı zaman bombası olurdu (proje kuralı).
 */
class NocCallLogServiceTest {

    private static final long TEAM_A = 1L, TEAM_B = 2L;

    private final NocCallLogRepository repo = mock(NocCallLogRepository.class);
    private final AlertEventRepository alertRepo = mock(AlertEventRepository.class);
    private final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final NocCallListService callLists = mock(NocCallListService.class);
    private final PermissionService perms = mock(PermissionService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final NocCallLogService svc =
            new NocCallLogService(repo, alertRepo, inventoryRepo, teamRepo, callLists, perms, activity);

    private Instant now;
    private AlertEvent alertA;
    /** role → noc_calls.write verildi mi (matris). */
    private final Map<String, Boolean> nocGrant = new HashMap<>();

    private static MockHttpSession session(String user, String role, List<Long> view) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", user);
        s.setAttribute("displayName", user.toUpperCase(Locale.ROOT));
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", view);
        return s;
    }

    private MockHttpSession global() { return session("admin", "ADMIN", null); }
    private MockHttpSession scoped() { return session("mudur", "ADMIN", List.of(TEAM_A)); }
    private MockHttpSession memberA() { return session("kisia", "USER", List.of(TEAM_A)); }
    private MockHttpSession memberB() { return session("kisib", "USER", List.of(TEAM_B)); }
    private MockHttpSession nocOp() { return session("noc1", "AUDIT", null); }
    private MockHttpSession nocUserB() { return session("noc2", "TEAM_ADMIN", List.of(TEAM_B)); }

    private static String iso(Instant i) { return NocCallLogService.ISO.format(i); }

    private static AppUser user(long id, String name, String phone) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername("u" + id); u.setDisplayName(name); u.setTitle("Uzman"); u.setPhone(phone);
        u.setActive(true);
        return u;
    }

    private static Map<String, Object> person(long id, String name, boolean member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", id); m.put("display_name", name); m.put("title", "Uzman"); m.put("has_phone", true);
        m.put("is_member", member);
        return m;
    }

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        svc.clock = () -> now;
        alertA = new AlertEvent();
        alertA.setId(50L); alertA.setDomain("a.example.com"); alertA.setAlertType("PING_DOWN");
        alertA.setAlertLevel("CRITICAL"); alertA.setTeamId(TEAM_A);
        alertA.setCreatedAt(iso(now.minus(Duration.ofHours(1))));
        when(alertRepo.findById(50L)).thenReturn(Optional.of(alertA));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());

        // Matris: noc_calls.write → role tablosu; alerts.read → herkes (AUDIT dahil).
        nocGrant.put("ADMIN", true);      // ADMIN satırı kısılamaz — kapsamlı müdür de "true" görür
        nocGrant.put("AUDIT", true);      // önerilen kurulum: 7/24 operatörleri AUDIT + izin
        nocGrant.put("TEAM_ADMIN", true); // bu testte TEAM_ADMIN de 7/24 operatörü (başka takımdan)
        nocGrant.put("USER", false);
        when(perms.allows(any(HttpSession.class), eq(NocCallLogService.PERMISSION), eq("edit")))
                .thenAnswer(i -> nocGrant.getOrDefault(((HttpSession) i.getArgument(0)).getAttribute("systemRole"), false));
        when(perms.allows(any(HttpSession.class), eq("alerts.read"), eq("view"))).thenReturn(true);

        Team a = new Team(); a.setId(TEAM_A); a.setName("Takım A");
        when(teamRepo.findById(TEAM_A)).thenReturn(Optional.of(a));
        // Arama listesi: 11 (1.), 12 (2.) — 13 artık üye değil; müdür 20; diğer üyeler 11, 12, 30.
        when(callLists.callListDto(TEAM_A)).thenReturn(List.of(person(11, "Kişi A", true), person(12, "Kişi B", true),
                person(13, "Kişi C (ayrıldı)", false)));
        when(callLists.resolveManager(a)).thenReturn(Optional.of(user(20, "Kişi M", null)));
        when(callLists.activeMembers(TEAM_A)).thenReturn(List.of(user(11, "Kişi A", "0555"), user(12, "Kişi B", "0555"),
                user(30, "Kişi D", null)));
        when(repo.save(any(NocCallLog.class))).thenAnswer(i -> { NocCallLog c = i.getArgument(0); c.setId(900L); return c; });
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    private NocCallLogService.Input parse(Map<String, Object> body) {
        return NocCallLogService.parse(body, alertA.getCreatedAt(), now);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("sonuç zorunlu ve listeden; kanal varsayılanı PHONE, geçersiz kanal 400")
    void outcomeAndChannel() {
        assertThatThrownBy(() -> parse(body("contactedName", "Kişi A"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(body("contactedName", "Kişi A", "outcome", "MAYBE")))
                .isInstanceOf(IllegalArgumentException.class);
        NocCallLogService.Input in = parse(body("contactedName", "Kişi A", "outcome", "reached"));
        assertThat(in.outcome()).isEqualTo("REACHED");
        assertThat(in.channel()).isEqualTo("PHONE");
        assertThat(in.contactedAt()).isEqualTo(now);   // varsayılan: şimdi
        assertThatThrownBy(() -> parse(body("contactedName", "Kişi A", "outcome", "BUSY", "channel", "FAX")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(parse(body("contactedName", "Kişi A", "outcome", "BUSY", "channel", "teams")).channel()).isEqualTo("TEAMS");
    }

    @Test
    @DisplayName("kişi zorunlu: kullanıcı ya da ad; ad ≤200, telefon numarası ad alanına yazılamaz")
    void personRequired() {
        assertThatThrownBy(() -> parse(body("outcome", "REACHED"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "   "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "0555 000 00 00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("telefon");
        assertThat(parse(body("outcome", "REACHED", "contactedUserId", 11)).contactedUserId()).isEqualTo(11L);
        assertThat(parse(body("outcome", "REACHED", "contactedUserId", "12")).contactedUserId()).isEqualTo(12L);
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedUserId", "abc")))
                .isInstanceOf(IllegalArgumentException.class);
        // "Kişi A (2. vardiya)" tek rakam — ad olarak geçerli
        assertThat(parse(body("outcome", "REACHED", "contactedName", "Kişi A (2. vardiya)")).contactedName())
                .isEqualTo("Kişi A (2. vardiya)");
    }

    @Test
    @DisplayName("zaman: gelecek olamaz (2 dk kayma payı şimdiye çekilir), açılıştan 10 dk'dan eski olamaz")
    void contactedAtBounds() {
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "K", "contactedAt", iso(now.plus(Duration.ofMinutes(5))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gelecek");
        // kayma payı içinde → şimdi
        assertThat(parse(body("outcome", "REACHED", "contactedName", "K", "contactedAt", iso(now.plusSeconds(60)))).contactedAt())
                .isEqualTo(now);
        Instant opened = now.minus(Duration.ofHours(1));
        assertThat(parse(body("outcome", "REACHED", "contactedName", "K", "contactedAt", iso(opened.minus(Duration.ofMinutes(9))))).contactedAt())
                .isEqualTo(opened.minus(Duration.ofMinutes(9)));
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "K", "contactedAt", iso(opened.minus(Duration.ofMinutes(11))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("açılış");
        // ofsetli ve Z'li biçimler
        Instant fiveAgo = now.minus(Duration.ofMinutes(5));
        assertThat(parse(body("outcome", "REACHED", "contactedName", "K", "contactedAt", fiveAgo.toString())).contactedAt()).isEqualTo(fiveAgo);
        assertThat(parse(body("outcome", "REACHED", "contactedName", "K",
                "contactedAt", fiveAgo.atOffset(java.time.ZoneOffset.ofHours(3)).toString())).contactedAt()).isEqualTo(fiveAgo);
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "K", "contactedAt", "dün")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("not: ≤1000, kontrol karakterleri ve yön denetim karakterleri ayıklanır, satır sonları korunur")
    void noteCleaning() {
        String bell = Character.toString(7);
        String rlo = Character.toString(0x202E);
        String crlf = Character.toString(13) + Character.toString(10);
        String lf = Character.toString(10);
        NocCallLogService.Input in = parse(body("outcome", "REACHED", "contactedName", "  Kişi" + bell + "   A ",
                "note", "  ilk satır" + bell + crlf + "ikinci" + rlo + " satır  "));
        assertThat(in.note()).isEqualTo("ilk satır" + lf + "ikinci satır");
        assertThat(in.contactedName()).isEqualTo("Kişi A");
        assertThat(parse(body("outcome", "REACHED", "contactedName", "K", "note", "   ")).note()).isNull();
        assertThat(parse(body("outcome", "REACHED", "contactedName", "K", "note", "x".repeat(1000))).note()).hasSize(1000);
        assertThatThrownBy(() -> parse(body("outcome", "REACHED", "contactedName", "K", "note", "x".repeat(1001))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Yetki ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("yazma: global yönetici evet, kapsamlı müdür HAYIR (ADMIN satırı true olsa da), matris izni evet, izinsiz hayır")
    void canWrite() {
        nocGrant.put("ADMIN", false);   // global yönetici matristen bağımsız geçer
        assertThat(svc.canWrite(global())).isTrue();
        nocGrant.put("ADMIN", true);
        assertThat(svc.canWrite(scoped())).isFalse();
        assertThat(svc.canWrite(nocOp())).isTrue();
        assertThat(svc.canWrite(nocUserB())).isTrue();
        assertThat(svc.canWrite(memberA())).isFalse();
        assertThat(svc.canWrite(null)).isFalse();
    }

    @Test
    @DisplayName("görünürlük: 7/24 operatörü (başka takımdan) tüm uyarıları görür; kapsamlı müdür ve üye yalnız kendi takımını")
    void readScope() {
        assertThat(svc.seesAllAlerts(nocUserB())).isTrue();
        assertThat(svc.seesAllAlerts(scoped())).isFalse();
        assertThat(svc.seesAllAlerts(memberA())).isFalse();
        assertThat(svc.canRead(nocUserB(), alertA)).isTrue();
        assertThat(svc.canRead(memberA(), alertA)).isTrue();
        assertThat(svc.canRead(scoped(), alertA)).isTrue();
        assertThat(svc.canRead(memberB(), alertA)).isFalse();
        // alerts.read yoksa (izinsiz) kendi takımında da okuyamaz
        when(perms.allows(any(HttpSession.class), eq("alerts.read"), eq("view"))).thenReturn(false);
        assertThat(svc.canRead(memberA(), alertA)).isFalse();
    }

    @Test
    @DisplayName("sertifika uyarısı (teamId yok): takım envanterden — SY de UG de okur; sahip takım SY, yoksa UG")
    void certAlertTeamFromInventory() {
        AlertEvent cert = new AlertEvent();
        cert.setId(60L); cert.setDomain("cert.example.com"); cert.setAlertType("EXPIRY");
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("cert.example.com"); inv.setTeamId(TEAM_B); inv.setUgTeamId(TEAM_A);
        when(inventoryRepo.findByDomain("cert.example.com")).thenReturn(Optional.of(inv));
        assertThat(svc.canRead(memberA(), cert)).isTrue();   // UG
        assertThat(svc.canRead(memberB(), cert)).isTrue();   // SY
        assertThat(svc.owningTeam(cert)).isEqualTo(TEAM_B);
        inv.setTeamId(null);
        assertThat(svc.owningTeam(cert)).isEqualTo(TEAM_A);
    }

    // ── Yazma / silme ────────────────────────────────────────────────────────

    @Test
    @DisplayName("kayıt: listeden seçilen kişinin adı sunucudan, takım uyarıdan; etkinlik akışına NOC_CALL_LOGGED")
    void createFromPicker() {
        Map<String, Object> dto = svc.create(alertA, body("outcome", "REACHED", "contactedUserId", 12,
                "contactedName", "istemcinin yazdığı yok sayılır", "note", "Kişi B bakıyor"), nocUserB());
        ArgumentCaptor<NocCallLog> saved = ArgumentCaptor.forClass(NocCallLog.class);
        verify(repo).save(saved.capture());
        NocCallLog c = saved.getValue();
        assertThat(c.getContactedUserId()).isEqualTo(12L);
        assertThat(c.getContactedName()).isEqualTo("Kişi B");
        assertThat(c.getTeamId()).isEqualTo(TEAM_A);
        assertThat(c.getChannel()).isEqualTo("PHONE");
        assertThat(c.getCreatedBy()).isEqualTo("noc2");
        assertThat(c.getCreatedByName()).isEqualTo("NOC2");
        assertThat(c.getCreatedAt()).isEqualTo(iso(now));
        assertThat(dto).containsEntry("can_delete", true).containsEntry("delete_until", iso(now.plus(Duration.ofMinutes(15))));
        assertThat(dto.keySet()).noneMatch(k -> k.contains("phone"));
        verify(activity).recordLifecycle(eq("PING"), isNull(), eq("a.example.com"), eq("a.example.com"), eq(TEAM_A),
                eq("NOC_CALL_LOGGED"), eq("noc2"), contains("Kişi B"));
    }

    @Test
    @DisplayName("kayıt: takımda olmayan kullanıcı 400; ad serbest metinle; izinsiz ve kapsamlı müdür 403")
    void createGuards() {
        assertThatThrownBy(() -> svc.create(alertA, body("outcome", "REACHED", "contactedUserId", 999), nocOp()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.create(alertA, body("outcome", "REACHED", "contactedUserId", 13), nocOp()))
                .as("listede ama artık üye değil").isInstanceOf(IllegalArgumentException.class);
        svc.create(alertA, body("outcome", "ESCALATED", "contactedName", "Kişi E (vardiya amiri)"), nocOp());
        assertThatThrownBy(() -> svc.create(alertA, body("outcome", "REACHED", "contactedName", "K"), memberA()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> svc.create(alertA, body("outcome", "REACHED", "contactedName", "K"), scoped()))
                .isInstanceOf(SecurityException.class);
        verify(repo, times(1)).save(any());
    }

    private NocCallLog stored(long id, String createdBy, Instant createdAt) {
        NocCallLog c = new NocCallLog();
        c.setId(id); c.setAlertId(50L); c.setTeamId(TEAM_A); c.setContactedName("Kişi A"); c.setOutcome("NO_ANSWER");
        c.setChannel("PHONE"); c.setContactedAt(iso(createdAt)); c.setCreatedBy(createdBy); c.setCreatedByName(createdBy);
        c.setCreatedAt(iso(createdAt));
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("silme: giren 15 dk içinde; süre dolunca / başkası 403; global yönetici her zaman; başka uyarının kaydı 404")
    void deleteWindow() {
        NocCallLog fresh = stored(1, "noc2", now.minus(Duration.ofMinutes(14)));
        stored(2, "noc2", now.minus(Duration.ofMinutes(16)));
        assertThatThrownBy(() -> svc.delete(alertA, 1L, nocOp())).isInstanceOf(SecurityException.class);   // başkası
        assertThat(svc.delete(alertA, 1L, session("NOC2", "TEAM_ADMIN", List.of(TEAM_B)))).isSameAs(fresh); // harf duyarsız sahip
        assertThatThrownBy(() -> svc.delete(alertA, 2L, nocUserB())).isInstanceOf(SecurityException.class)
                .hasMessageContaining("15");
        svc.delete(alertA, 2L, global());
        verify(repo, times(2)).delete(any(NocCallLog.class));
        verify(activity, times(2)).recordLifecycle(anyString(), isNull(), anyString(), anyString(), eq(TEAM_A),
                eq("NOC_CALL_DELETED"), anyString(), anyString());

        NocCallLog other = stored(3, "noc2", now);
        other.setAlertId(51L);
        assertThatThrownBy(() -> svc.delete(alertA, 3L, global())).isInstanceOf(NoSuchElementException.class);
        // izni geri alınmış giren kişi kendi kaydını da silemez
        stored(4, "kisia", now);
        assertThatThrownBy(() -> svc.delete(alertA, 4L, memberA())).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("liste: en yeni önce (depo sırası), can_delete yalnız kendi taze kaydında; yöneticide süresiz")
    void listFlags() {
        NocCallLog mine = stored(1, "noc2", now.minus(Duration.ofMinutes(3)));
        NocCallLog old = stored(2, "noc2", now.minus(Duration.ofMinutes(30)));
        NocCallLog theirs = stored(3, "noc1", now.minus(Duration.ofMinutes(1)));
        when(repo.findByAlertIdOrderByContactedAtDescIdDesc(50L)).thenReturn(List.of(theirs, mine, old));
        List<Map<String, Object>> rows = svc.list(alertA, nocUserB());
        assertThat(rows).extracting(r -> r.get("id")).containsExactly(3L, 1L, 2L);
        assertThat(rows).extracting(r -> r.get("can_delete")).containsExactly(false, true, false);
        assertThat(rows.get(0).get("delete_until")).isNull();
        assertThat(rows.get(1).get("delete_until")).isEqualTo(iso(now.plus(Duration.ofMinutes(12))));
        assertThat(rows.get(0).keySet()).containsExactly("id", "contacted_user_id", "contacted_name", "contacted_at",
                "channel", "outcome", "note", "created_by_name", "created_at", "can_delete", "delete_until");
        assertThat(svc.list(alertA, global())).extracting(r -> r.get("can_delete")).containsOnly(true);
        assertThat(svc.list(alertA, memberA())).extracting(r -> r.get("can_delete")).containsOnly(false);
    }

    // ── Kişi seçicisi ────────────────────────────────────────────────────────

    @Test
    @DisplayName("seçici: arama listesi (sıralı, üye olmayan düşer) → müdür → diğer üyeler; tekrar yok; telefon YOK")
    void contactsOrder() {
        List<Map<String, Object>> c = svc.contacts(alertA);
        assertThat(c).extracting(m -> m.get("user_id")).containsExactly(11L, 12L, 20L, 30L);
        assertThat(c).extracting(m -> m.get("source")).containsExactly("CALL_LIST", "CALL_LIST", "MANAGER", "MEMBER");
        assertThat(c.get(2)).containsEntry("has_phone", false).containsEntry("is_manager", true);
        assertThat(c.get(3)).containsEntry("has_phone", false).containsEntry("display_name", "Kişi D");
        assertThat(c).allSatisfy(m -> assertThat(m.keySet()).noneMatch(k -> k.contains("phone") && !k.equals("has_phone")));

        // müdür listede ise listede kalır, is_manager işaretlenir
        when(callLists.callListDto(TEAM_A)).thenReturn(List.of(person(20, "Kişi M", true), person(11, "Kişi A", true)));
        List<Map<String, Object>> c2 = svc.contacts(alertA);
        assertThat(c2).extracting(m -> m.get("source")).containsExactly("CALL_LIST", "CALL_LIST", "MEMBER", "MEMBER");
        assertThat(c2.get(0)).containsEntry("is_manager", true);

        AlertEvent teamless = new AlertEvent();
        teamless.setId(70L); teamless.setDomain("x.example.com");
        assertThat(svc.contacts(teamless)).isEmpty();
    }

    // ── Liste özeti ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("özet: TEK sorgu; sayfadaki her uyarıya noc_call_count + noc_last_call, kaydı olmayana 0")
    void decorate() {
        AlertEvent b = new AlertEvent();
        b.setId(51L);
        when(repo.summarizeByAlertIds(any())).thenReturn(List.<Object[]>of(
                new Object[]{50L, "Kişi A", "REACHED", "2026-01-01T03:12:00", 2L}));
        svc.decorate(List.of(alertA, b));
        verify(repo, times(1)).summarizeByAlertIds(any());
        assertThat(alertA.getNocCallCount()).isEqualTo(2L);
        assertThat(alertA.getNocLastCall()).containsEntry("contacted_name", "Kişi A").containsEntry("outcome", "REACHED")
                .containsEntry("contacted_at", "2026-01-01T03:12:00");
        assertThat(b.getNocCallCount()).isZero();
        assertThat(b.getNocLastCall()).isNull();

        // sorgu düşerse liste yine döner (alan yok)
        AlertEvent c = new AlertEvent();
        c.setId(52L);
        when(repo.summarizeByAlertIds(any())).thenThrow(new RuntimeException("db"));
        svc.decorate(List.of(c));
        assertThat(c.getNocCallCount()).isNull();
    }

    @Test
    @DisplayName("etkinlik türü: uyarı tipinin izleme türü (MonitorTypeCatalog)")
    void activityType() {
        assertThat(NocCallLogService.activityType("PING_DOWN")).isEqualTo("PING");
        assertThat(NocCallLogService.activityType("EXPIRY")).isEqualTo("CERT");
        assertThat(NocCallLogService.activityType("HTTP_DOWN")).isEqualTo("HTTP");
    }
}
