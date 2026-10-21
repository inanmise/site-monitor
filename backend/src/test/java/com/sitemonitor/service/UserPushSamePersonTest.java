package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * Aynı kişi kuralı uçtan uca (2026-10-21, prod alarm #628 "OCPAA - Servis Yanıt Süresi Anomalisi"): GERÇEK SQL (H2,
 * PostgreSQL kipi) + GERÇEK alıcı çözümü + gerçek ağ geçidi isteği (JDK HttpServer). Sekiz kişilik takımda aynı kişinin iki
 * hesabı ("MP60146" / "N60146", e-posta farklı harf ve boşlukla) → TEK push; diğer hesap {@code SKIPPED_SAME_PERSON} satırı.
 *
 * <p>Yazan sorgular işlem dışında koşar ({@code NOT_SUPPORTED}): outbox işçisi ayrı iş parçacığında satırları okur.
 * Fixture kimlikleri SAHTE sicillerdir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:pushsameperson;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class UserPushSamePersonTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TEAM = 7L;
    private static final String SAME = PushSamePersonGuard.SKIPPED_SAME_PERSON;

    @Autowired AppUserRepository userRepo;
    @Autowired UserPushDeliveryRepository deliveryRepo;
    @Autowired UserPushScopeRepository scopeRepo;
    @Autowired AlertEventRepository alertRepo;

    private final AppSettingsService settings = mock(AppSettingsService.class);
    private UserPushRecipientResolver resolver;
    private PushSamePersonGuard guard;
    private UserPushService push;
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        deliveryRepo.deleteAll();
        alertRepo.deleteAll();
        userRepo.deleteAll();
        bodies.clear();

        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));
        when(settings.getString(eq("site.monitor.userpush.role-groups"), any())).thenReturn(
                "{\"uzman\":{\"enabled\":true,\"minLevel\":\"WARNING\"},\"po\":{\"enabled\":true,\"minLevel\":\"WARNING\"}}");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", ex -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(buf);
            bodies.add(buf.toString(StandardCharsets.UTF_8));
            byte[] resp = "{\"notificationId\": 628}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        when(settings.getString(eq("site.monitor.userpush.url"), any()))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/notify");

        TrustEvaluator trust = mock(TrustEvaluator.class);
        when(trust.pinAwareOutboundSslContext(any(), any())).thenReturn(null);
        resolver = new UserPushRecipientResolver(userRepo, settings);
        guard = new PushSamePersonGuard(userRepo);
        push = new UserPushService(settings, deliveryRepo, scopeRepo, resolver, alertRepo,
                mock(SecretCipher.class), trust, mock(CaAutoPinService.class));
        push.setSamePerson(guard);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (push != null) {
            push.shutdown();
            // İşçi bitmeden sonraki testin deleteAll'u koşmasın (uçuştaki gönderim sonraki testin satırlarına dokunmasın)
            java.lang.reflect.Field f = UserPushService.class.getDeclaredField("worker");
            f.setAccessible(true);
            ((java.util.concurrent.ExecutorService) f.get(push)).awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (server != null) server.stop(0);
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────────

    private AppUser user(String username, String email, String lastLogin, Long... teams) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName("Ad " + username);
        u.setEmail(email);
        u.setOrgRole("TECH");
        u.setActive(true);
        u.setLastLoginAt(lastLogin);
        u.setTeamIds(new LinkedHashSet<>(List.of(teams)));
        if (teams.length > 0) u.setTeamId(teams[0]);
        return userRepo.save(u);
    }

    /** #628 takımı: altı ayrı kişi + aynı kişinin iki hesabı (e-posta farklı harf / boşlukla). */
    private void team628(String mpLogin, String nLogin) {
        for (int i = 1; i <= 6; i++) user("N0000" + i, "kisi" + i + "@example.com", "2026-10-19T08:00:00", TEAM);
        user("MP60146", "  Emre.Gokoglu@Example.com ", mpLogin, TEAM);
        user("N60146", "emre.gokoglu@example.com", nLogin, TEAM);
    }

    private AlertEvent alarm(String level) {
        AlertEvent e = new AlertEvent();
        e.setDomain("OCPAA - Servis Yanıt Süresi Anomalisi");
        e.setAlertLevel(level);
        e.setAlertType("HTTP_SLOW");
        e.setMessage("Yanıt süresi anomalisi");
        e.setTeamId(TEAM);
        e.setCreatedAt(ISO.format(Instant.now().minusSeconds(120)));
        return alertRepo.save(e);
    }

    private List<UserPushDelivery> rows(Long eventId) {
        return deliveryRepo.findByAlertEventIdOrderByIdAsc(eventId);
    }

    private UserPushDelivery row(List<UserPushDelivery> rows, String username, String trigger) {
        return rows.stream().filter(d -> username.equals(d.getUsername()) && trigger.equals(d.getTrigger())).findFirst()
                .orElseThrow(() -> new AssertionError("satır yok: " + username + " " + trigger));
    }

    /** Ağ geçidine giden tüm isteklerin userIds'i (istek başına liste). */
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

    private void awaitSent(Long eventId, String username, String trigger) {
        await().atMost(Duration.ofSeconds(15)).until(() -> "SENT".equals(row(rows(eventId), username, trigger).getStatus()));
    }

    // ── #628 ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#628: 8 kişilik takımda aynı kişinin iki hesabı → en son giriş yapana TEK push; öbürü SKIPPED_SAME_PERSON; ağ geçidine sicil bir kez")
    void incident628_onePushPerPerson() throws Exception {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        AlertEvent e = alarm("HIGH");

        push.enqueueAlert(e.getId(), "INITIAL", TEAM, null);

        List<UserPushDelivery> rs = rows(e.getId());
        assertThat(rs).hasSize(8);
        UserPushDelivery mp = row(rs, "MP60146", "OPEN");
        assertThat(mp.getStatus()).isEqualTo(SAME);
        assertThat(mp.getSamePersonUsername()).isEqualTo("N60146");
        assertThat(row(rs, "N60146", "OPEN").getSamePersonUsername()).isNull();

        awaitSent(e.getId(), "N60146", "OPEN");
        List<String> all = gatewayUserIds().stream().flatMap(List::stream).toList();
        assertThat(all).containsOnlyOnce("N60146").doesNotContain("MP60146").hasSize(7);
        // Aynı kişi satırı saat tavanına SAYILMAZ (yalnız PENDING / SENT / FAILED sayılır)
        assertThat(deliveryRepo.countRecentForUser("MP60146", ISO.format(Instant.now().minusSeconds(3600)))).isZero();
        assertThat(deliveryRepo.countRecentForUser("N60146", ISO.format(Instant.now().minusSeconds(3600)))).isEqualTo(1);
    }

    @Test
    @DisplayName("açılıştan sonra öbür hesap daha yeni giriş yapsa da ESKALASYON ve ÇÖZÜM açılışı alan hesaba gider (enqueueResolve simetrisi)")
    void laterPhasesFollowTheOpenAccount() throws Exception {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        AlertEvent e = alarm("HIGH");
        push.enqueueAlert(e.getId(), "INITIAL", TEAM, null);
        awaitSent(e.getId(), "N60146", "OPEN");

        AppUser mp = userRepo.findByUsername("MP60146").orElseThrow();
        mp.setLastLoginAt(ISO.format(Instant.now()));   // MP artık en son giriş yapan
        userRepo.save(mp);
        e.setAlertLevel("CRITICAL");
        alertRepo.save(e);

        push.enqueueAlert(e.getId(), "ESCALATION", TEAM, null);
        List<UserPushDelivery> rs = rows(e.getId());
        assertThat(row(rs, "N60146", "ESCALATION").getStatus()).isIn("PENDING", "SENT");
        assertThat(row(rs, "MP60146", "ESCALATION").getStatus()).isEqualTo(SAME);
        assertThat(row(rs, "MP60146", "ESCALATION").getSamePersonUsername()).isEqualTo("N60146");

        push.enqueueResolve(alertRepo.findById(e.getId()).orElseThrow(), null, TEAM);
        rs = rows(e.getId());
        assertThat(row(rs, "N60146", "RESOLVE").getStatus()).isIn("PENDING", "SENT");
        assertThat(rs.stream().filter(d -> "MP60146".equals(d.getUsername()) && "RESOLVE".equals(d.getTrigger())))
                .as("çözüm yalnız açılışta push ALANLARA gider (resolvePrior) — öbür hesaba satır bile yazılmaz").isEmpty();
    }

    @Test
    @DisplayName("boş / null e-postalı hesaplar asla birleşmez")
    void blankEmailsNeverMerged() {
        user("B00001", "   ", "2026-10-19T08:00:00", TEAM);
        user("B00002", "", "2026-10-19T08:00:00", TEAM);
        user("B00003", null, "2026-10-19T08:00:00", TEAM);
        AlertEvent e = alarm("HIGH");
        push.enqueueAlert(e.getId(), "INITIAL", TEAM, null);
        assertThat(rows(e.getId())).extracting(UserPushDelivery::getStatus).doesNotContain(SAME).hasSize(3);
    }

    @Test
    @DisplayName("seçilen hesap push istemiyorsa (opt-out) bugünkü opt-out satırı yazılır, öbür hesap YİNE SKIPPED_SAME_PERSON (kişi başına tek karar)")
    void optedOutChosenAccount() {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        AppUser n = userRepo.findByUsername("N60146").orElseThrow();
        n.setPushOptOut(true);
        userRepo.save(n);
        AlertEvent e = alarm("HIGH");
        push.enqueueAlert(e.getId(), "INITIAL", TEAM, null);
        List<UserPushDelivery> rs = rows(e.getId());
        assertThat(row(rs, "N60146", "OPEN").getStatus()).isEqualTo("SKIPPED_USER_OPT_OUT");
        assertThat(row(rs, "MP60146", "OPEN").getStatus()).isEqualTo(SAME);
    }

    @Test
    @DisplayName("önizleme gönderimle aynı: elle gönderimin önizlemesi ve gerçek satırları aynı hesabı atlar")
    void previewEqualsSent() {
        team628("2026-10-19T21:00:00", "2026-10-18T09:00:00");   // bu kez MP daha yeni
        AlertEvent e = alarm("HIGH");
        UserPushService.PushPreview p = push.preview(e.getId(), TEAM);
        Map<String, String> preview = new java.util.HashMap<>();
        for (var r : p.recipients()) preview.put(r.username(), r.status());
        assertThat(preview.get("N60146")).isEqualTo(SAME);
        assertThat(p.recipients().stream().filter(r -> "N60146".equals(r.username())).findFirst().orElseThrow().samePersonOf())
                .isEqualTo("MP60146");

        push.enqueueAlert(e.getId(), "MANUAL", TEAM, null);
        for (UserPushDelivery d : rows(e.getId())) {
            String expected = preview.get(d.getUsername());
            assertThat(d.getStatus()).as(d.getUsername()).isIn(expected, "SENT");   // PENDING satır işçi tarafından gönderilmiş olabilir
            if (SAME.equals(expected)) assertThat(d.getStatus()).isEqualTo(SAME);
        }
    }

    @Test
    @DisplayName("elle gönderim: operatör push'u alacak hesabı listeden çıkardıysa kişiyi çıkarmıştır — öbür hesaba da push / satır YOK (nöbetçi dahil)")
    void resendExclusionRemovesThePerson() {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        AppUser oncall = user("N60999", "EMRE.gokoglu@example.com", "2026-10-01T09:00:00", 99L);   // aynı kişinin üçüncü hesabı
        AlertEvent e = alarm("HIGH");
        push.enqueueAlert(e.getId(), "MANUAL", TEAM, null, Set.of("N60146"));
        assertThat(rows(e.getId())).extracting(UserPushDelivery::getUsername)
                .doesNotContain("N60146", "MP60146").hasSize(6);
        assertThat(push.enqueueOnCall(e.getId(), "MANUAL", TEAM, oncall.getId(), Set.of("N60146"))).isNull();
        assertThat(rows(e.getId())).extracting(UserPushDelivery::getUsername).doesNotContain("N60999");
    }

    @Test
    @DisplayName("Kim alır? (explain): aynı kişinin ikinci hesabı SKIPPED_SAME_PERSON kararı + push'u alacak hesap")
    void explainShowsMergedAccount() {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        List<UserPushRecipientResolver.Explanation> ex = resolver.explain(TEAM, "HIGH");
        UserPushRecipientResolver.Explanation mp = ex.stream().filter(x -> "MP60146".equals(x.username())).findFirst().orElseThrow();
        assertThat(mp.decision()).isEqualTo(SAME);
        assertThat(mp.samePersonOf()).isEqualTo("N60146");
        assertThat(ex.stream().filter(x -> "N60146".equals(x.username())).findFirst().orElseThrow().decision()).isEqualTo("RECIPIENT");
    }

    // ── Öbür yollar ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fırtına: açılış push'u kişiye bir kez; günlük tekrar (öbür hesap daha yeni giriş yapsa da) açılışı alan hesaba gider")
    void stormNotice() {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        AlertEvent e = alarm("HIGH");
        push.enqueueStormNoticeLocalized(55L, null, TEAM, "INITIAL", "HIGH", List.of(e),
                UserPushService.LocalizedText.of("3 monitör düştü"));
        List<UserPushDelivery> init = deliveryRepo.findPersonRowsByKey("storm:55:INITIAL", List.of("MP60146", "N60146"));
        assertThat(init).extracting(UserPushDelivery::getUsername, UserPushDelivery::getStatus)
                .contains(org.assertj.core.groups.Tuple.tuple("MP60146", SAME));
        assertThat(init.stream().filter(d -> "N60146".equals(d.getUsername())).findFirst().orElseThrow().getStatus())
                .isIn("PENDING", "SENT");

        AppUser mp = userRepo.findByUsername("MP60146").orElseThrow();
        mp.setLastLoginAt(ISO.format(Instant.now()));
        userRepo.save(mp);
        push.enqueueStormNoticeLocalized(55L, null, TEAM, "DAILY_REALERT", "HIGH", List.of(e),
                UserPushService.LocalizedText.of("hâlâ düşük"));
        String daily = UserPushService.stormDedupeKey(55L, "DAILY_REALERT");
        List<UserPushDelivery> rs = deliveryRepo.findPersonRowsByKey(daily, List.of("MP60146", "N60146"));
        assertThat(rs.stream().filter(d -> "MP60146".equals(d.getUsername())).findFirst().orElseThrow().getStatus()).isEqualTo(SAME);
        assertThat(rs.stream().filter(d -> "N60146".equals(d.getUsername())).findFirst().orElseThrow().getStatus()).isIn("PENDING", "SENT");
    }

    @Test
    @DisplayName("nöbetçi: kişinin takım hesabı açılış push'unu aldıysa nöbetçi hesabına ikinci push gitmez (SKIPPED_SAME_PERSON izi)")
    void onCallSiblingAlreadyGotTeamPush() {
        user("MP60146", "emre.gokoglu@example.com", "2026-10-18T09:00:00", TEAM);   // takım üyesi
        AppUser oncall = user("N60146", "Emre.Gokoglu@example.com", "2026-10-19T21:00:00", 99L);   // başka takımın üyesi, nöbetçi
        AlertEvent e = alarm("HIGH");
        push.enqueueAlert(e.getId(), "INITIAL", TEAM, null);
        assertThat(push.enqueueOnCall(e.getId(), "INITIAL", TEAM, oncall.getId(), Set.of())).isEqualTo(SAME);
        UserPushDelivery d = row(rows(e.getId()), "N60146", "OPEN");
        assertThat(d.getDedupeKey()).isEqualTo("ONCALL:OPEN");
        assertThat(d.getSamePersonUsername()).isEqualTo("MP60146");
        assertThat(row(rows(e.getId()), "MP60146", "OPEN").getStatus()).isIn("PENDING", "SENT");
    }

    @Test
    @DisplayName("eskalasyon adımı: kişinin e-postası iki aktif hesaba eşleşir → artık 'belirsiz' değil; açılışı alan hesap alır, öbürü SKIPPED_SAME_PERSON")
    void escalationStepSameEmail() {
        user("MP60146", "emre.gokoglu@example.com", "2026-10-18T09:00:00", TEAM);
        user("N60146", "Emre.Gokoglu@Example.com", "2026-10-19T21:00:00");   // takımda değil, daha yeni giriş
        AlertEvent e = alarm("HIGH");
        push.enqueueAlert(e.getId(), "INITIAL", TEAM, null);   // açılış MP'ye

        EscalationContact c = new EscalationContact();
        c.setId(77L);
        c.setEmail(" EMRE.GOKOGLU@example.com ");
        c.setTeamId(TEAM);
        assertThat(push.enqueueEscalationStep(alertRepo.findById(e.getId()).orElseThrow(), c, 15)).isIn("PENDING");
        List<UserPushDelivery> step = rows(e.getId()).stream()
                .filter(d -> UserPushService.TRIGGER_ESCALATION_STEP.equals(d.getTrigger())).toList();
        assertThat(step).hasSize(2);
        assertThat(step.stream().filter(d -> "MP60146".equals(d.getUsername())).findFirst().orElseThrow().getStatus())
                .isIn("PENDING", "SENT");
        UserPushDelivery other = step.stream().filter(d -> "N60146".equals(d.getUsername())).findFirst().orElseThrow();
        assertThat(other.getStatus()).isEqualTo(SAME);
        assertThat(other.getSamePersonUsername()).isEqualTo("MP60146");
    }

    @Test
    @DisplayName("eskalasyon adımı: koruyucu yoksa / karar veremezse de e-postası aynı hesaplardan yalnız biri (çözümün ilk hesabı) push alır")
    void escalationStepFallbackWithoutGuard() throws Exception {
        user("MP60146", "emre.gokoglu@example.com", "2026-10-18T09:00:00", TEAM);
        user("N60146", "emre.gokoglu@example.com", "2026-10-19T21:00:00");
        AlertEvent e = alarm("HIGH");
        TrustEvaluator trust = mock(TrustEvaluator.class);
        UserPushService noGuard = new UserPushService(settings, deliveryRepo, scopeRepo, resolver, alertRepo,
                mock(SecretCipher.class), trust, mock(CaAutoPinService.class));
        try {
            EscalationContact c = new EscalationContact();
            c.setId(78L);
            c.setEmail("emre.gokoglu@example.com");
            c.setTeamId(TEAM);
            assertThat(noGuard.enqueueEscalationStep(e, c, 30)).isEqualTo("PENDING");
            List<UserPushDelivery> step = rows(e.getId());
            assertThat(step).hasSize(2);
            UserPushDelivery mp = step.stream().filter(d -> "MP60146".equals(d.getUsername())).findFirst().orElseThrow();
            assertThat(mp.getStatus()).isEqualTo(SAME);
            assertThat(mp.getSamePersonUsername()).isEqualTo("N60146");   // en son giriş yapan
        } finally {
            noGuard.shutdown();
        }
    }

    @Test
    @DisplayName("haftalık rapor: müdüre doğrudan push + takım bildiriminde müdürün öbür hesabı ikinci kez almaz")
    void weeklyManagerSibling() {
        AppUser mgr = user("MGR001", "mudur@example.com", "2026-10-19T08:00:00");
        user("N00777", "Mudur@Example.com ", "2026-10-19T09:00:00", TEAM);   // müdürün takımdaki öbür hesabı
        user("N00001", "kisi1@example.com", "2026-10-19T08:00:00", TEAM);
        push.enqueueDirect(List.of(new UserPushService.DirectRecipient(mgr.getUsername(), "Müdür", false)), TEAM,
                "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "Takım", "rapor onaylandı", "weekly-mgr:1");
        Map<String, Object> team = push.enqueueTeamNotice(TEAM, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "Takım",
                "rapor onaylandı", "weekly:1", Set.of("MGR001"));
        assertThat(team.get("queued")).isEqualTo(1);
        UserPushDelivery sib = deliveryRepo.findPersonRowsByKey("weekly:1", List.of("N00777")).get(0);
        assertThat(sib.getStatus()).isEqualTo(SAME);
        assertThat(sib.getSamePersonUsername()).isEqualTo("MGR001");
    }

    @Test
    @DisplayName("teknik hata raporu doğrudan push'u (aynı anahtar iki çağrı da olsa): kişiye tek push; sonuç 'same_person' haritasıyla")
    @SuppressWarnings("unchecked")
    void directPairAndSecondCallSameKey() {
        user("MP60146", "po@example.com", "2026-10-18T09:00:00", TEAM);
        user("N60146", "PO@example.com", "2026-10-19T21:00:00", TEAM);
        Map<String, Object> res = push.enqueueDirectLocalized(List.of(
                        new UserPushService.DirectRecipient("MP60146", "PO A", false),
                        new UserPushService.DirectRecipient("N60146", "PO B", false)), TEAM, "TECH_ERROR_APPROVAL", "WARNING",
                "TECH_ERROR_REPORT", "Kanal", UserPushService.LocalizedText.of("onay bekliyor"), "te-appr:1:1");
        assertThat((List<String>) res.get("queued_usernames")).containsExactly("N60146");
        assertThat((Map<String, String>) res.get("same_person")).containsExactly(Map.entry("MP60146", "N60146"));

        // Aynı anahtarla ikinci çağrı (onay isteği iki listeye bölünür): öbür hesap ayrı çağrıda gelse de push almaz
        deliveryRepo.deleteAll();
        push.enqueueDirectLocalized(List.of(new UserPushService.DirectRecipient("N60146", "PO B", false)), TEAM,
                "TECH_ERROR_APPROVAL", "WARNING", "TECH_ERROR_REPORT", "Kanal", UserPushService.LocalizedText.of("x"), "te-appr:1:2");
        Map<String, Object> second = push.enqueueDirectLocalized(List.of(new UserPushService.DirectRecipient("MP60146", "PO A", false)),
                TEAM, "TECH_ERROR_APPROVAL", "WARNING", "TECH_ERROR_REPORT", "Kanal", UserPushService.LocalizedText.of("x"), "te-appr:1:2");
        assertThat(second.get("queued")).isEqualTo(0);
        assertThat(deliveryRepo.findPersonRowsByKey("te-appr:1:2", List.of("MP60146")).get(0).getStatus()).isEqualTo(SAME);
    }

    @Test
    @DisplayName("kodla giriş push'u (sendDirect) kurala TABİ DEĞİL: kodu isteyen hesaba gider, satır yazılmaz")
    void otpUntouched() throws Exception {
        team628("2026-10-18T09:00:00", "2026-10-19T21:00:00");
        assertThat(push.sendDirect("MP60146", "Giriş", "Kodunuz 123456").ok()).isTrue();
        assertThat(gatewayUserIds()).containsExactly(List.of("MP60146"));
        assertThat(deliveryRepo.count()).isZero();
    }

    @Test
    @DisplayName("saat tavanı özeti: aynı kişinin iki hesabındaki taşmalar TEK özette, en son giriş yapan hesaba")
    void overflowOneSummaryPerPerson() {
        user("MP60146", "emre@example.com", "2026-10-18T09:00:00", TEAM);
        user("N60146", "Emre@Example.com", "2026-10-19T21:00:00", TEAM);
        String old = ISO.format(Instant.now().minus(Duration.ofMinutes(30)));
        for (String u : List.of("MP60146", "N60146", "MP60146")) {
            UserPushDelivery d = new UserPushDelivery();
            d.setTrigger("OPEN");
            d.setDedupeKey("OPEN");
            d.setAlertEventId(1000L + deliveryRepo.count());
            d.setUsername(u);
            d.setStatus("RATE_LIMITED");
            d.setAlertLevel("HIGH");
            d.setMonitorName("x.example.com");
            d.setCreatedAt(old);
            deliveryRepo.save(d);
        }
        UserPushOverflowService ovf = new UserPushOverflowService(push, deliveryRepo, userRepo, null);
        ovf.setSamePerson(guard);
        UserPushOverflowService.SweepResult r = ovf.sweep(Instant.now());
        assertThat(r.summaries()).isEqualTo(1);
        List<UserPushDelivery> summaries = deliveryRepo.findAll().stream()
                .filter(d -> UserPushOverflowService.TRIGGER.equals(d.getTrigger())).toList();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getUsername()).isEqualTo("N60146");
        assertThat(deliveryRepo.findByOverflowSummaryIdOrderByIdAsc(summaries.get(0).getId())).hasSize(3);
    }
}
