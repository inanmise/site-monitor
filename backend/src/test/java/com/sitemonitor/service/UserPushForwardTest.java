package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sitemonitor.service.forward.AlertForwardRouting;
import com.sitemonitor.service.forward.ForwardTarget;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Alarm iletiminin push ayağı uçtan uca (2026-11-05): GERÇEK SQL (H2, PostgreSQL kipi) + gerçek alıcı çözümü + gerçek aynı
 * kişi koruyucusu + gerçek ağ geçidi isteği (JDK HttpServer). Kullanıcı kararı: alıcı "sahip takım ne alıyorsa" alır — sahip
 * takımın kanal kararları (bakım, günlük tekrar, tür / takım kapsamı, izleme push'u, sessiz saat) iletimi de durdurur; alıcı
 * takımın KENDİ push kapsamı ve kişisel tercihleri uygulanır; aynı kişi aynı alarm + aynı aşamada iki push almaz. Satırın
 * {@code team_id}'si ALICI takımdır (alıcının push geçmişinde tam görünür).
 *
 * <p>Yazan sorgular işlem dışında koşar ({@code NOT_SUPPORTED}): outbox işçisi ayrı iş parçacığında satırları okur. Fixture
 * kimlikleri SAHTE sicillerdir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:pushforward;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class UserPushForwardTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OWNER_NAME = "Ödeme Sistemleri";
    /** Gerçek uzunlukta AD takım adı (60+ karakter). */
    private static final String RECIPIENT_NAME = "Çağrı Merkezi Uygulama Destek Ekibi (Müşteri Deneyimi / Dijital Kanallar)";
    private static final String SAME = PushSamePersonGuard.SKIPPED_SAME_PERSON;

    @Autowired AppUserRepository userRepo;
    @Autowired UserPushDeliveryRepository deliveryRepo;
    @Autowired UserPushScopeRepository scopeRepo;
    @Autowired AlertEventRepository alertRepo;
    @Autowired TeamRepository teamRepo;

    private final AppSettingsService settings = mock(AppSettingsService.class);
    private UserPushService push;
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private long owner, recipient, recipient2;

    @BeforeEach
    void setUp() throws Exception {
        deliveryRepo.deleteAll();
        alertRepo.deleteAll();
        userRepo.deleteAll();
        scopeRepo.deleteAll();
        teamRepo.deleteAll();
        bodies.clear();

        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));
        when(settings.getString(eq("site.monitor.userpush.role-groups"), any())).thenReturn(
                "{\"uzman\":{\"enabled\":true,\"minLevel\":\"WARNING\"}}");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", ex -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(buf);
            bodies.add(buf.toString(StandardCharsets.UTF_8));
            byte[] resp = "{\"notificationId\": 1105}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        when(settings.getString(eq("site.monitor.userpush.url"), any()))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/notify");

        owner = team(OWNER_NAME);
        recipient = team(RECIPIENT_NAME);
        recipient2 = team("Altyapı Nöbet Ekibi");

        TrustEvaluator trust = mock(TrustEvaluator.class);
        when(trust.pinAwareOutboundSslContext(any(), any())).thenReturn(null);
        UserPushRecipientResolver resolver = new UserPushRecipientResolver(userRepo, settings);
        push = new UserPushService(settings, deliveryRepo, scopeRepo, resolver, alertRepo,
                mock(SecretCipher.class), trust, mock(CaAutoPinService.class));
        push.setSamePerson(new PushSamePersonGuard(userRepo));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (push != null) {
            push.shutdown();
            java.lang.reflect.Field f = UserPushService.class.getDeclaredField("worker");
            f.setAccessible(true);
            ((java.util.concurrent.ExecutorService) f.get(push)).awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (server != null) server.stop(0);
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────────

    private long team(String name) {
        Team t = new Team();
        t.setName(name);
        t.setActive(true);
        return teamRepo.save(t).getId();
    }

    private AppUser user(String username, String orgRole, Long... teams) {
        return named(username, "Ad " + username + " (Bilgi Teknolojileri / Uzman)", username.toLowerCase() + "@example.com",
                orgRole, teams);
    }

    private AppUser named(String username, String display, String email, String orgRole, Long... teams) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(display);
        u.setEmail(email);
        u.setOrgRole(orgRole);
        u.setActive(true);
        u.setLastLoginAt("2026-11-04T08:00:00");
        u.setTeamIds(new LinkedHashSet<>(List.of(teams)));
        if (teams.length > 0) u.setTeamId(teams[0]);
        return userRepo.save(u);
    }

    private AlertEvent alarm(Long teamId, String type, String level) {
        AlertEvent e = new AlertEvent();
        e.setDomain("odeme.example.com");
        e.setAlertLevel(level);
        e.setAlertType(type);
        e.setMessage("Bağlantı zaman aşımına uğradı");
        e.setTeamId(teamId);
        e.setCreatedAt(ISO.format(Instant.now().minusSeconds(120)));
        return alertRepo.save(e);
    }

    private ForwardTarget whole(long rt, String name) {
        return new ForwardTarget(rt, name, List.of(OWNER_NAME), List.of(1L), List.of("destek@example.com"), List.of(), true);
    }

    private ForwardTarget members(long rt, String name, Long... userIds) {
        return new ForwardTarget(rt, name, List.of(OWNER_NAME), List.of(2L), List.of(), List.of(userIds), false);
    }

    private void scopeOff(String type, String key) {
        UserPushScope s = new UserPushScope();
        s.setScopeType(type);
        s.setScopeKey(key);
        s.setEnabled(false);
        scopeRepo.save(s);
    }

    private List<UserPushDelivery> rows(Long eventId) {
        return deliveryRepo.findByAlertEventIdOrderByIdAsc(eventId);
    }

    private List<UserPushDelivery> forwardRows(Long eventId) {
        return rows(eventId).stream().filter(d -> d.getDedupeKey() != null && d.getDedupeKey().startsWith("FWD:")).toList();
    }

    private UserPushDelivery rowOf(List<UserPushDelivery> rs, String username) {
        return rs.stream().filter(d -> username.equals(d.getUsername())).findFirst()
                .orElseThrow(() -> new AssertionError("satır yok: " + username));
    }

    private List<UserPushDelivery> keyRows(String key) {
        return deliveryRepo.findAll().stream().filter(d -> key.equals(d.getDedupeKey())).toList();
    }

    private UserPushDelivery sentRow(Long eventId, String key, String trigger, Long teamId, String username, String message) {
        UserPushDelivery d = new UserPushDelivery();
        d.setAlertEventId(eventId);
        d.setTrigger(trigger);
        d.setDedupeKey(key);
        d.setTeamId(teamId);
        d.setUsername(username);
        d.setDisplayName(username);
        d.setMessage(message);
        d.setAlertLevel("HIGH");
        d.setStatus("SENT");
        d.setCreatedAt(ISO.format(Instant.now().minusSeconds(30)));
        return deliveryRepo.save(d);
    }

    private static boolean delivering(UserPushDelivery d) {
        return PushSamePersonGuard.delivering(d.getStatus());
    }

    private List<List<String>> gatewayUserIds() throws Exception {
        List<List<String>> out = new ArrayList<>();
        for (String b : bodies) {
            JsonNode n = MAPPER.readTree(b);
            List<String> ids = new ArrayList<>();
            n.path("userIds").forEach(x -> ids.add(x.asText()));
            out.add(ids);
        }
        return out;
    }

    /** Sabit hedef döndüren yönlendirme (BE1'in AlertForwardService'i yerine — iskelet arayüz). */
    private static AlertForwardRouting routing(long ownerTeam, ForwardTarget... targets) {
        return new AlertForwardRouting() {
            @Override public boolean anyFor(Collection<Long> owners) { return owners != null && owners.contains(ownerTeam); }
            @Override public List<ForwardTarget> targetsFor(AlertRef ref) {
                return ref.ownerTeamIds().contains(ownerTeam) ? List.of(targets) : List.of();
            }
        };
    }

    // ── Temel akış ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tüm takım: sahip push'undan sonra alıcı takım üyelerine iletim satırı — FWD:<alıcı>:OPEN, team_id alıcı takım, sahip adı önekli; ikinci çağrı yazmaz")
    void wholeTeamForward() throws Exception {
        user("N10001", "TECH", owner);
        user("N20001", "TECH", recipient);
        user("N20002", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueAlert(e.getId(), "INITIAL", owner, null);
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());

        UserPushDelivery own = rowOf(rows(e.getId()), "N10001");
        assertThat(own.getDedupeKey()).isEqualTo("OPEN");
        assertThat(own.getTeamId()).isEqualTo(owner);
        assertThat(own.getMessage()).doesNotContain("takımından");

        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).extracting(UserPushDelivery::getUsername).containsExactly("N20001", "N20002");
        for (UserPushDelivery d : fwd) {
            assertThat(d.getDedupeKey()).isEqualTo("FWD:" + recipient + ":OPEN");
            assertThat(d.getTrigger()).isEqualTo("OPEN");
            assertThat(d.getTeamId()).isEqualTo(recipient);
            assertThat(d.getMonitorName()).isEqualTo("odeme.example.com");
            assertThat(d.getMessage()).startsWith("Ödeme Sistemleri takımından: YÜKSEK: odeme.example.com yanıt vermiyor");
            assertThat(delivering(d)).isTrue();
        }

        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).hasSize(2);

        await().atMost(Duration.ofSeconds(15)).until(() -> forwardRows(e.getId()).stream().allMatch(d -> "SENT".equals(d.getStatus())));
        assertThat(gatewayUserIds()).anySatisfy(ids -> assertThat(ids).containsExactlyInAnyOrder("N20001", "N20002"));
    }

    @Test
    @DisplayName("seçilen üyeler: rol grubu SORULMAZ (org rolü olmayan üye de alır); seçilmeyen takım üyesi almaz; pasif üye satır almaz")
    void selectedMembersOnly() {
        AppUser chosen = user("N20003", null, recipient);         // rol grubu yok — takım çözümünde aday olmazdı
        user("N20004", "TECH", recipient);                         // seçilmedi
        AppUser passive = user("N20005", "TECH", recipient);
        passive.setActive(false);
        userRepo.save(passive);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueForwards(e.getId(), "INITIAL", List.of(members(recipient, RECIPIENT_NAME, chosen.getId(), passive.getId(), 999_999L)),
                null, Set.of());

        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).extracting(UserPushDelivery::getUsername).containsExactly("N20003");
        assertThat(delivering(fwd.get(0))).isTrue();
        assertThat(fwd.get(0).getTeamId()).isEqualTo(recipient);
    }

    @Test
    @DisplayName("seçilen üyenin kişisel tercihleri aynen: vazgeçme → SKIPPED_USER_OPT_OUT, aile süzgeci → SKIPPED_USER_TYPE (satır kalır)")
    void selectedMemberPersonalPreferences() {
        AppUser opt = user("N20006", "TECH", recipient);
        opt.setPushOptOut(true);
        userRepo.save(opt);
        AppUser certOnly = user("N20007", "TECH", recipient);
        certOnly.setPushFamilies("cert");
        userRepo.save(certOnly);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueForwards(e.getId(), "INITIAL", List.of(members(recipient, RECIPIENT_NAME, opt.getId(), certOnly.getId())),
                null, Set.of());

        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(rowOf(fwd, "N20006").getStatus()).isEqualTo("SKIPPED_USER_OPT_OUT");
        assertThat(rowOf(fwd, "N20007").getStatus()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_TYPE);
    }

    @Test
    @DisplayName("hedefler alıcı takım sırasıyla; iki alıcı takımda olan kişiye TEK satır (küçük kimlikli takımın)")
    void personInTwoRecipientTeamsGetsOneRow() {
        user("N20008", "TECH", recipient, recipient2);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueForwards(e.getId(), "INITIAL",
                List.of(whole(recipient2, "Altyapı Nöbet Ekibi"), whole(recipient, RECIPIENT_NAME)), null, Set.of());

        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).hasSize(1);
        assertThat(fwd.get(0).getTeamId()).isEqualTo(Math.min(recipient, recipient2));
    }

    @Test
    @DisplayName("iletim yokken hiçbir şey: hedef listesi boş / null → satır yok, kanal kapalıysa satır yok")
    void noTargetsNoRows() {
        user("N20009", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(), null, Set.of());
        push.enqueueForwards(e.getId(), "INITIAL", null, null, Set.of());
        assertThat(rows(e.getId())).isEmpty();

        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(false);
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(rows(e.getId())).isEmpty();
    }

    // ── Sahip kararları iletimi de durdurur ────────────────────────────────────────────────────

    @Test
    @DisplayName("sahip takımın tür kapsamı kapalı (TYPE http) → iletim satırı yok (karar sahip satırında)")
    void ownerTypeOff() {
        user("N20010", "TECH", recipient);
        scopeOff("TYPE", "http");
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();
    }

    @Test
    @DisplayName("sahip takımın push'u kapalı (TEAM) → iletim satırı yok; damgasız sertifika alarmında sahip = envanterin SY takımı")
    void ownerTeamOff() {
        user("N20011", "TECH", recipient);
        scopeOff("TEAM", String.valueOf(owner));
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();

        // Damgasız sertifika alarmı: sahip push takımı envanterden
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        CertificateInventory row = new CertificateInventory();
        row.setDomain("odeme.example.com");
        row.setTeamId(owner);
        when(inv.findByDomain("odeme.example.com")).thenReturn(Optional.of(row));
        push.setForwardInventoryRepo(inv);
        AlertEvent cert = alarm(null, "EXPIRY", "HIGH");
        push.enqueueForwards(cert.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(cert.getId())).as("sahip SY takımının push'u kapalı").isEmpty();

        scopeRepo.deleteAll();
        push.enqueueForwards(cert.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(cert.getId())).extracting(UserPushDelivery::getUsername).containsExactly("N20011");
    }

    @Test
    @DisplayName("ALICI takımın push'u kapalı → o takımın karar satırı SKIPPED_TEAM_OFF (sistem sicili, team_id alıcı); diğer alıcı takım alır")
    void recipientTeamOff() {
        user("N20012", "TECH", recipient);
        user("N20013", "TECH", recipient2);
        scopeOff("TEAM", String.valueOf(recipient));
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueForwards(e.getId(), "INITIAL",
                List.of(whole(recipient, RECIPIENT_NAME), whole(recipient2, "Altyapı Nöbet Ekibi")), null, Set.of());

        List<UserPushDelivery> fwd = forwardRows(e.getId());
        UserPushDelivery decision = rowOf(fwd, UserPushService.SYSTEM_USER);
        assertThat(decision.getStatus()).isEqualTo("SKIPPED_TEAM_OFF");
        assertThat(decision.getTeamId()).isEqualTo(recipient);
        assertThat(decision.getDedupeKey()).isEqualTo("FWD:" + recipient + ":OPEN");
        assertThat(fwd).extracting(UserPushDelivery::getUsername).containsExactlyInAnyOrder("-", "N20013");
        assertThat(rowOf(fwd, "N20013").getTeamId()).isEqualTo(recipient2);
    }

    @Test
    @DisplayName("izleme push'u kapalı (bağlam push_disabled ya da kalıcı SKIPPED_MONITOR_OFF kararı) → iletim satırı yok")
    void monitorPushOff() {
        user("N20014", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), Map.of("push_disabled", true), Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();

        // Açılışta yazılmış kalıcı karar sonraki aşamaları da bağlar (sahip yolu gibi)
        UserPushDelivery off = new UserPushDelivery();
        off.setAlertEventId(e.getId()); off.setTrigger("OPEN"); off.setDedupeKey("OPEN"); off.setUsername("-");
        off.setStatus("SKIPPED_MONITOR_OFF"); off.setCreatedAt(ISO.format(Instant.now()));
        deliveryRepo.save(off);
        e.setAlertLevel("CRITICAL");
        alertRepo.save(e);
        push.enqueueForwards(e.getId(), "ESCALATION", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();
    }

    @Test
    @DisplayName("sistem bakımı susturması → açılış iletimi yok; elle gönderim (RESEND) susmaz — anahtar FWD:<alıcı>:RESEND:<8 hane>")
    void maintenance() {
        user("N20015", "TECH", recipient);
        SystemMaintenanceService sm = mock(SystemMaintenanceService.class);
        when(sm.notificationsMuted()).thenReturn(true);
        push.setSystemMaintenance(sm);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();

        push.enqueueForwards(e.getId(), "MANUAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).hasSize(1);
        assertThat(fwd.get(0).getTrigger()).isEqualTo("RESEND");
        assertThat(fwd.get(0).getDedupeKey()).matches("FWD:" + recipient + ":RESEND:[0-9a-f]{8}");
        assertThat(UserPushService.forwardPhaseOf(fwd.get(0).getDedupeKey())).startsWith("RESEND:");
    }

    @Test
    @DisplayName("günlük tekrar push'u kapalı → RE_ALERT iletimi yok; açıkken anahtar FWD:<alıcı>:RE_ALERT:<gün>")
    void realert() {
        user("N20016", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        when(settings.getBoolean(eq("site.monitor.userpush.realert-enabled"), any(Boolean.class))).thenReturn(false);
        push.enqueueForwards(e.getId(), "DAILY_REALERT", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();

        when(settings.getBoolean(eq("site.monitor.userpush.realert-enabled"), any(Boolean.class))).thenReturn(true);
        push.enqueueForwards(e.getId(), "DAILY_REALERT", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        String day = Instant.now().atZone(ZoneId.of("Europe/Istanbul")).toLocalDate().toString();
        assertThat(forwardRows(e.getId())).extracting(UserPushDelivery::getDedupeKey)
                .containsExactly("FWD:" + recipient + ":RE_ALERT:" + day);
    }

    @Test
    @DisplayName("küresel push sessiz saati → iletim yok (sahip push'uyla aynı); çözüm yolu bu metottan geçmez")
    void globalQuietHours() {
        user("N20017", "TECH", recipient);
        LocalTime now = LocalTime.now(ZoneId.of("Europe/Istanbul"));
        when(settings.getString(eq("site.monitor.userpush.quiet-start"), any())).thenReturn(now.minusHours(1).withSecond(0).withNano(0).toString());
        when(settings.getString(eq("site.monitor.userpush.quiet-end"), any())).thenReturn(now.plusHours(1).withSecond(0).withNano(0).toString());
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();

        // RESOLUTION tetiği bu yoldan açılış push'u ÜRETMEZ
        when(settings.getString(eq("site.monitor.userpush.quiet-start"), any())).thenReturn("");
        push.enqueueForwards(e.getId(), "RESOLUTION", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(forwardRows(e.getId())).isEmpty();
    }

    // ── Aynı kişi / aynı aşama ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sahip push'unu ALAN kişi (iki takımın da üyesi) ikinci push almaz — iletim satırı yazılmaz")
    void ownerRecipientNotTwice() {
        user("N20018", "TECH", owner, recipient);
        user("N20019", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueAlert(e.getId(), "INITIAL", owner, null);
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());

        assertThat(rows(e.getId()).stream().filter(d -> "N20018".equals(d.getUsername()))).hasSize(1)
                .allMatch(d -> "OPEN".equals(d.getDedupeKey()));
        assertThat(forwardRows(e.getId())).extracting(UserPushDelivery::getUsername).containsExactly("N20019");
    }

    @Test
    @DisplayName("aynı kişinin iki hesabı alıcı takımda → tek push, öbürü SKIPPED_SAME_PERSON; kardeş hesap sahip push'unu aldıysa iletim hesabı SKIPPED_SAME_PERSON")
    void samePersonRules() {
        named("MP70001", "Selin Aydın MP70001", "selin.aydin@example.com", "TECH", recipient);
        named("N70001", "SELİN AYDIN N70001", " Selin.Aydin@Example.com ", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).hasSize(2);
        assertThat(fwd.stream().filter(UserPushForwardTest::delivering)).hasSize(1);
        assertThat(fwd.stream().filter(d -> SAME.equals(d.getStatus()))).hasSize(1);

        // Kardeş hesap sahip takımda push'u aldı → iletimdeki hesap SKIPPED_SAME_PERSON (kişiye ikinci push yok)
        named("MP70002", "Burak Er MP70002", "burak.er@example.com", "TECH", owner);
        named("N70002", "Burak Er N70002", "burak.er@example.com", "TECH", recipient);
        AlertEvent e2 = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueAlert(e2.getId(), "INITIAL", owner, null);
        push.enqueueForwards(e2.getId(), "INITIAL", List.of(members(recipient, RECIPIENT_NAME,
                userRepo.findByUsername("N70002").orElseThrow().getId())), null, Set.of());
        UserPushDelivery sib = rowOf(forwardRows(e2.getId()), "N70002");
        assertThat(sib.getStatus()).isEqualTo(SAME);
        assertThat(sib.getSamePersonUsername()).isEqualTo("MP70002");
    }

    @Test
    @DisplayName("saat tavanı alıcının kendi sayısıyla: tavandaki kişi RATE_LIMITED; elle çıkarılan kişiye satır yok")
    void capAndManualExclusion() {
        user("N20020", "TECH", recipient);
        user("N20021", "TECH", recipient);
        when(settings.getInt(eq("site.monitor.userpush.hourly-cap"), any(Integer.class))).thenReturn(1);
        sentRow(4242L, "OPEN", "OPEN", owner, "N20020", "önceki");
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        push.enqueueForwards(e.getId(), "MANUAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of("N20021"));
        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).extracting(UserPushDelivery::getUsername).containsExactly("N20020");
        assertThat(fwd.get(0).getStatus()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("nöbetçi push'uyla çakışma: önce nöbetçi aldıysa iletim yazmaz; önce iletim aldıysa nöbetçi push'u yazmaz")
    void onCallConflictBothOrders() {
        AppUser c = user("N20022", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        assertThat(push.enqueueOnCall(e.getId(), "INITIAL", owner, c.getId(), Set.of())).isIn("PENDING", "SENT");
        push.enqueueForwards(e.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(rows(e.getId())).extracting(UserPushDelivery::getDedupeKey).containsExactly("ONCALL:OPEN");

        AlertEvent e2 = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueForwards(e2.getId(), "INITIAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(push.enqueueOnCall(e2.getId(), "INITIAL", owner, c.getId(), Set.of())).isNull();
        assertThat(rows(e2.getId())).extracting(UserPushDelivery::getDedupeKey).containsExactly("FWD:" + recipient + ":OPEN");
    }

    @Test
    @DisplayName("elle gönderim: sahip RESEND satırını alan kişi (son 2 dk) iletim RESEND'i almaz")
    void manualResendSameNotice() {
        user("N20023", "TECH", owner, recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        push.enqueueAlert(e.getId(), "MANUAL", owner, null);
        push.enqueueForwards(e.getId(), "MANUAL", List.of(whole(recipient, RECIPIENT_NAME)), null, Set.of());
        assertThat(rows(e.getId())).hasSize(1);
        assertThat(rows(e.getId()).get(0).getDedupeKey()).startsWith("RESEND:");
    }

    // ── Çözüm ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("çözüm: iletimle almış kişinin RESOLVE satırı alıcı takımın (team_id) ve önekli; sahip alıcısınınki bugünkü gibi (sahip takım, öneksiz); dil güncel")
    void resolveFollowsForwardRow() {
        user("N10002", "TECH", owner);
        AppUser en = user("N20024", "TECH", recipient);
        en.setPushLang("en");
        userRepo.save(en);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        sentRow(e.getId(), "OPEN", "OPEN", owner, "N10002", "YÜKSEK: odeme.example.com yanıt vermiyor.");
        sentRow(e.getId(), "FWD:" + recipient + ":OPEN", "OPEN", recipient, "N20024",
                "Ödeme Sistemleri takımından: YÜKSEK: odeme.example.com yanıt vermiyor.");
        e.setResolved(true);
        alertRepo.save(e);

        push.enqueueResolve(alertRepo.findById(e.getId()).orElseThrow(), null, owner);

        List<UserPushDelivery> res = rows(e.getId()).stream().filter(d -> "RESOLVE".equals(d.getTrigger())).toList();
        UserPushDelivery own = rowOf(res, "N10002");
        assertThat(own.getTeamId()).isEqualTo(owner);
        assertThat(own.getMessage()).startsWith("DÜZELDİ: odeme.example.com");
        UserPushDelivery fwd = rowOf(res, "N20024");
        assertThat(fwd.getTeamId()).isEqualTo(recipient);
        assertThat(fwd.getDedupeKey()).isEqualTo("RESOLVE");
        assertThat(fwd.getMessage()).startsWith("From team Ödeme Sistemleri: RESOLVED: odeme.example.com");
        assertThat(delivering(fwd)).isTrue();
        assertThat(fwd.getBatchId()).isNotEqualTo(own.getBatchId());
    }

    // ── Fırtına ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("iletilen fırtına: tek toplu push, olaysız satır, anahtar storm:<id>:FWD:<alıcı>:INITIAL, tetik STORM, team_id alıcı; tekrar yazmaz; sahip fırtına push'unu alan kişi almaz")
    void forwardStormNotice() {
        user("N20025", "TECH", recipient);
        user("N20026", "TECH", owner, recipient);
        sentRow(null, "storm:77:INITIAL", "STORM", owner, "N20026", "sahip fırtınası");

        push.enqueueForwardStormNotice(77L, "INITIAL", whole(recipient, RECIPIENT_NAME), 5, "CRITICAL", OWNER_NAME);
        push.enqueueForwardStormNotice(77L, "INITIAL", whole(recipient, RECIPIENT_NAME), 5, "CRITICAL", OWNER_NAME);

        List<UserPushDelivery> rs = keyRows("storm:77:FWD:" + recipient + ":INITIAL");
        assertThat(rs).extracting(UserPushDelivery::getUsername).containsExactly("N20025");
        UserPushDelivery d = rs.get(0);
        assertThat(d.getAlertEventId()).isNull();
        assertThat(d.getTrigger()).isEqualTo("STORM");
        assertThat(d.getTeamId()).isEqualTo(recipient);
        assertThat(d.getMonitorType()).isEqualTo(UserPushService.STORM_MONITOR_TYPE);
        assertThat(d.getMessage()).isEqualTo("Ödeme Sistemleri takımının fırtınası: size iletilen 5 alarm (KRİTİK).");
        assertThat(delivering(d)).isTrue();
    }

    @Test
    @DisplayName("iletilen fırtınanın çözümü: yalnız açılışı ALANLARA (STORM_RESOLVED); kimse almadıysa SKIPPED_NO_PRIOR; sahip fırtına push'u kanal kararıyla durduysa iletim de yok")
    void forwardStormResolveAndOwnerBlock() {
        user("N20027", "TECH", recipient);
        user("N20028", "TECH", recipient);
        sentRow(null, "storm:77:FWD:" + recipient + ":INITIAL", "STORM", recipient, "N20027", "x");

        push.enqueueForwardStormNotice(77L, "RESOLVE", whole(recipient, RECIPIENT_NAME), 3, "CRITICAL", OWNER_NAME);
        List<UserPushDelivery> rs = keyRows("storm:77:FWD:" + recipient + ":RESOLVE");
        assertThat(rs).extracting(UserPushDelivery::getUsername).containsExactly("N20027");
        assertThat(rs.get(0).getTrigger()).isEqualTo("STORM_RESOLVED");
        assertThat(rs.get(0).getMessage()).isEqualTo("Ödeme Sistemleri takımının fırtınası sona erdi.");

        push.enqueueForwardStormNotice(77L, "RESOLVE", whole(recipient2, "Altyapı Nöbet Ekibi"), 3, "CRITICAL", OWNER_NAME);
        List<UserPushDelivery> none = keyRows("storm:77:FWD:" + recipient2 + ":RESOLVE");
        assertThat(none).extracting(UserPushDelivery::getStatus).containsExactly("SKIPPED_NO_PRIOR");
        assertThat(none.get(0).getTeamId()).isEqualTo(recipient2);

        UserPushDelivery block = new UserPushDelivery();
        block.setTrigger("STORM"); block.setDedupeKey("storm:78:INITIAL"); block.setUsername("-"); block.setTeamId(owner);
        block.setStatus("SKIPPED_TEAM_OFF"); block.setCreatedAt(ISO.format(Instant.now()));
        deliveryRepo.save(block);
        push.enqueueForwardStormNotice(78L, "INITIAL", whole(recipient, RECIPIENT_NAME), 2, "HIGH", OWNER_NAME);
        assertThat(keyRows("storm:78:FWD:" + recipient + ":INITIAL")).isEmpty();
    }

    @Test
    @DisplayName("iletilen fırtına: alıcı takımın push'u kapalı → karar satırı SKIPPED_TEAM_OFF")
    void forwardStormRecipientTeamOff() {
        user("N20029", "TECH", recipient);
        scopeOff("TEAM", String.valueOf(recipient));
        push.enqueueForwardStormNotice(79L, "INITIAL", whole(recipient, RECIPIENT_NAME), 2, "HIGH", OWNER_NAME);
        assertThat(keyRows("storm:79:FWD:" + recipient + ":INITIAL")).extracting(UserPushDelivery::getUsername,
                UserPushDelivery::getStatus).containsExactly(org.assertj.core.groups.Tuple.tuple("-", "SKIPPED_TEAM_OFF"));
    }

    // ── Önizleme = gönderim ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("yeniden gönderim önizlemesi: iletim alıcıları kind=FORWARD + alıcı takım adı; iki takımda olan kişi bir kez; gönderim AYNI kişilere AYNI durumla yazar")
    void previewEqualsSend() {
        user("N10003", "TECH", owner);
        user("N20030", "TECH", owner, recipient);   // sahip satırında listelenir, iletimde tekrar etmez
        user("N20031", "TECH", recipient);
        AppUser opt = user("N20032", "TECH", recipient);
        opt.setPushOptOut(true);
        userRepo.save(opt);
        ForwardTarget t = whole(recipient, RECIPIENT_NAME);
        push.setForwardRouting(routing(owner, t));
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");

        UserPushService.PushPreview p = push.preview(e.getId(), owner, List.of());
        assertThat(p.blockReason()).isNull();
        List<UserPushService.PushPreviewRow> fwdPreview = p.recipients().stream()
                .filter(r -> UserPushService.PREVIEW_KIND_FORWARD.equals(r.kind())).toList();
        assertThat(fwdPreview).extracting(UserPushService.PushPreviewRow::username).containsExactly("N20031", "N20032");
        assertThat(fwdPreview).extracting(UserPushService.PushPreviewRow::teamName).containsOnly(RECIPIENT_NAME);
        assertThat(p.recipients().stream().filter(r -> "N20030".equals(r.username()))).hasSize(1)
                .allMatch(r -> r.kind() == null);

        push.enqueueAlert(e.getId(), "MANUAL", owner, null);
        push.enqueueForwards(e.getId(), "MANUAL", List.of(t), null, Set.of());
        List<UserPushDelivery> fwd = forwardRows(e.getId());
        assertThat(fwd).extracting(UserPushDelivery::getUsername)
                .containsExactlyElementsOf(fwdPreview.stream().map(UserPushService.PushPreviewRow::username).toList());
        assertThat(rowOf(fwd, "N20032").getStatus()).isEqualTo(fwdPreview.get(1).status()).isEqualTo("SKIPPED_USER_OPT_OUT");
        assertThat(delivering(rowOf(fwd, "N20031"))).isTrue();
        assertThat(fwdPreview.get(0).status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("önizleme: yönlendirme yoksa / alıcı takımın push'u kapalıysa / sahibin kanalı kapalıysa iletim satırı yok (gönderimle aynı)")
    void previewWithoutForwardsIsUnchanged() {
        user("N10004", "TECH", owner);
        user("N20033", "TECH", recipient);
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        UserPushService.PushPreview before = push.preview(e.getId(), owner, List.of());

        push.setForwardRouting(routing(owner, whole(recipient, RECIPIENT_NAME)));
        scopeOff("TEAM", String.valueOf(recipient));
        assertThat(push.preview(e.getId(), owner, List.of())).isEqualTo(before);

        scopeRepo.deleteAll();
        scopeOff("TYPE", "http");
        UserPushService.PushPreview blocked = push.preview(e.getId(), owner, List.of());
        assertThat(blocked.blockReason()).isEqualTo("SKIPPED_TYPE_OFF");
        assertThat(blocked.recipients()).isEmpty();
    }

    // ── Push geçmişim ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("push geçmişim: iletim satırı alıcının takımında TAM görünür (hedef, metin, takım adı); sahip takımın karar satırı ('-') alıcıya görünmez")
    void myHistoryShowsForwardRow() {
        AlertEvent e = alarm(owner, "HTTP_DOWN", "HIGH");
        UserPushDelivery fwd = sentRow(e.getId(), "FWD:" + recipient + ":OPEN", "OPEN", recipient, "N20034",
                "Ödeme Sistemleri takımından: YÜKSEK: odeme.example.com yanıt vermiyor.");
        fwd.setMonitorName("odeme.example.com");
        deliveryRepo.save(fwd);
        UserPushDelivery decision = new UserPushDelivery();
        decision.setAlertEventId(e.getId()); decision.setTrigger("ESCALATION"); decision.setDedupeKey("ESC:CRITICAL");
        decision.setUsername("-"); decision.setTeamId(owner); decision.setStatus("SKIPPED_QUIET_HOURS");
        decision.setCreatedAt(ISO.format(Instant.now().minusSeconds(10)));
        deliveryRepo.save(decision);

        MyPushHistoryService history = new MyPushHistoryService(deliveryRepo, teamRepo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) history.history("N20034", List.of(recipient), t -> false,
                7, "all", 0, 25, Instant.now()).get("data");
        assertThat(data).hasSize(1);
        Map<String, Object> row = data.get(0);
        assertThat(row).containsEntry("visible", true).containsEntry("scope", "own")
                .containsEntry("target", "odeme.example.com").containsEntry("team_name", RECIPIENT_NAME)
                .containsEntry("alert_event_id", e.getId());
        assertThat((String) row.get("message")).startsWith("Ödeme Sistemleri takımından:");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ownerSide = (List<Map<String, Object>>) history.history("N10005", List.of(owner), t -> false,
                7, "all", 0, 25, Instant.now()).get("data");
        assertThat(ownerSide).extracting(m -> m.get("team_decision")).containsExactly(true);
    }

    // ── Anahtar biçimi ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("iletim anahtarı 60 karakterlik sütuna sığar (en uzun aşama + 19 haneli takım) ve aşaması geri okunur")
    void dedupeKeyFits() {
        for (String phase : List.of("OPEN", "ESC:CRITICAL", "RE_ALERT:2026-11-05", "RESEND:abcdef12")) {
            String key = UserPushService.forwardDedupeKey(Long.MAX_VALUE, phase);
            assertThat(key).hasSizeLessThanOrEqualTo(60).doesNotContain("#");
            assertThat(UserPushService.forwardPhaseOf(key)).isEqualTo(phase);
        }
        assertThat(UserPushService.forwardPhaseOf("FWD:")).isNull();
        assertThat(UserPushService.forwardPhaseOf("FWD:x:OPEN")).isNull();
        assertThat(UserPushService.forwardPhaseOf("ONCALL:OPEN")).isNull();
        assertThat(UserPushService.forwardPhaseOf("FWD:12:")).isNull();
        String storm = UserPushDelivery.fitDedupeKey(UserPushService.forwardStormKey(Long.MAX_VALUE, Long.MAX_VALUE, "DAILY_REALERT"));
        assertThat(storm).hasSizeLessThanOrEqualTo(60).startsWith("storm:" + Long.MAX_VALUE + ":");
    }
}
