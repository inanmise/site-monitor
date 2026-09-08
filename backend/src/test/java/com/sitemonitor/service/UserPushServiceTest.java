package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kişi-webhook gönderim servisi — karar matrisi, anti-loop, gövde sözleşmesi ve yanıt işleme.
 *
 * <p>HTTP tarafı GERÇEK yerel sunucuyla sınanır (JDK HttpServer): gövdenin alan adları ve sırası
 * API sözleşmesinin kendisidir; mock'lanmış bir istemciyle "gönderdik" demek sözleşmeyi sınamaz.
 * Fixture kimlikleri Kural 0'a uygun SAHTE sicillerdir (N00001…).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPushServiceTest {

    @Mock AppSettingsService appSettings;
    @Mock UserPushDeliveryRepository deliveryRepo;
    @Mock UserPushScopeRepository scopeRepo;
    @Mock UserPushRecipientResolver resolver;
    @Mock AlertEventRepository alertEventRepo;
    @Mock SecretCipher secretCipher;
    @Mock TrustEvaluator trustEvaluator;
    @Mock CaAutoPinService caAutoPinService;

    private UserPushService service;
    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<UserPushDelivery> store = new CopyOnWriteArrayList<>();
    private final AtomicInteger respStatus = new AtomicInteger(200);
    private volatile String respBody = "{\"notificationId\": 1897198}";

    @BeforeEach
    void setUp() {
        service = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver,
                alertEventRepo, secretCipher, trustEvaluator, caAutoPinService);
        // Kurumsal güven zinciri: mock null döner → istemci VARSAYILAN güvene düşer, yerel
        // test sunucusu (düz HTTP) etkilenmez. Null dalı ayrıca aşağıda ayrı testle pinli.
        when(trustEvaluator.pinAwareOutboundSslContext(any(), any())).thenReturn(null);

        // Varsayılan ayar seti: kanal AÇIK, tavanlar bol — testler daraltmak istediğini kendisi daraltır.
        when(appSettings.getBoolean(anyString(), any(Boolean.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(appSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));   // hızlı backoff

        when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(anyLong(), anyString(), anyString()))
                .thenReturn(false);
        when(deliveryRepo.countRecentForUser(anyString(), anyString())).thenReturn(0L);
        // Kayıt deposu taklidi: worker PENDING satırları findTop50 ile okur — save edilenler
        // oradan dönmezse outbox hiç boşalmaz ve HTTP testleri sonsuza dek bekler.
        when(deliveryRepo.save(any())).thenAnswer(inv -> {
            UserPushDelivery d = inv.getArgument(0);
            store.add(d);
            return d;
        });
        when(deliveryRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryRepo.findTop50ByStatusOrderByIdAsc(anyString())).thenAnswer(inv ->
                store.stream().filter(d -> inv.getArgument(0).equals(d.getStatus())).toList());
        when(deliveryRepo.existsByAlertEventIdAndStatus(anyLong(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", ex -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(buf);
            receivedBodies.add(buf.toString(StandardCharsets.UTF_8));
            byte[] resp = respBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(respStatus.get(), resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        when(appSettings.getString(eq("site.monitor.userpush.url"), any()))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/notify");
    }

    private AlertEvent event(Long id, String level, String type) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("example.com");
        e.setAlertLevel(level);
        e.setAlertType(type);
        e.setMessage("test mesajı");
        e.setTeamId(5L);
        e.setCreatedAt("2026-08-27T10:00:00");
        return e;
    }

    private void recipients(String... usernames) {
        when(resolver.resolve(anyLong(), anyString())).thenReturn(
                java.util.Arrays.stream(usernames)
                        .map(u -> new UserPushRecipientResolver.Recipient(u, u, null))
                        .toList());
    }

    private List<UserPushDelivery> savedRows() {
        ArgumentCaptor<UserPushDelivery> cap = ArgumentCaptor.forClass(UserPushDelivery.class);
        verify(deliveryRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    // ── Karar matrisi (K5) ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Global anahtar KAPALI → hiçbir şey olmaz, satır bile yazılmaz (bugünün davranışı)")
    void globalOff_noRows() {
        when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(false);

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        verify(alertEventRepo, never()).findById(anyLong());
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    @DisplayName("TİP kapalı → SKIPPED_TYPE_OFF karar satırı, alıcı çözümü hiç koşmaz")
    void typeOff_writesDecisionRow() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        UserPushScope off = new UserPushScope();
        off.setEnabled(false);
        when(scopeRepo.findByScopeTypeAndScopeKey("TYPE", "http")).thenReturn(Optional.of(off));

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        verify(resolver, never()).resolve(any(), any());
        assertThat(savedRows()).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo("SKIPPED_TYPE_OFF");
            assertThat(d.getUsername()).isEqualTo("-");
        });
    }

    @Test
    @DisplayName("TAKIM kapalı → SKIPPED_TEAM_OFF")
    void teamOff_writesDecisionRow() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        UserPushScope off = new UserPushScope();
        off.setEnabled(false);
        when(scopeRepo.findByScopeTypeAndScopeKey("TEAM", "5")).thenReturn(Optional.of(off));

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        assertThat(savedRows()).singleElement()
                .extracting(UserPushDelivery::getStatus).isEqualTo("SKIPPED_TEAM_OFF");
    }

    @Test
    @DisplayName("İzleme bayrağı kapalı (ctx push_disabled) → SKIPPED_MONITOR_OFF")
    void monitorOff_writesDecisionRow() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of("push_disabled", true));

        assertThat(savedRows()).singleElement()
                .extracting(UserPushDelivery::getStatus).isEqualTo("SKIPPED_MONITOR_OFF");
    }

    @Test
    @DisplayName("Re-alert AYARLA kapatılabilir → SKIPPED_REALERT_OFF (K8 varsayılanı AÇIK)")
    void realertOff_writesDecisionRow() {
        when(appSettings.getBoolean(eq("site.monitor.userpush.realert-enabled"), any(Boolean.class)))
                .thenReturn(false);
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));

        service.enqueueAlert(1L, "DAILY_REALERT", 5L, Map.of());

        assertThat(savedRows()).singleElement()
                .extracting(UserPushDelivery::getStatus).isEqualTo("SKIPPED_REALERT_OFF");
    }

    @Test
    @DisplayName("Saat tavanı aşan kullanıcı RATE_LIMITED yazılır, diğerleri gönderilir")
    void hourlyCap_marksRateLimited() {
        // Bu test KUYRUĞA YAZILAN durumu sınar; worker'ın sonradan değiştirmesi konu dışı —
        // outbox okuması kapatılır ki PENDING satır elde kalsın (yarış değil, kasıtlı izolasyon).
        when(deliveryRepo.findTop50ByStatusOrderByIdAsc(anyString())).thenReturn(java.util.List.of());
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001", "N00002");
        when(deliveryRepo.countRecentForUser(eq("N00001"), anyString())).thenReturn(999L);

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        List<UserPushDelivery> rows = savedRows();
        assertThat(rows).extracting(UserPushDelivery::getUsername, UserPushDelivery::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("N00001", "RATE_LIMITED"),
                        org.assertj.core.groups.Tuple.tuple("N00002", "PENDING"));
    }

    @Test
    @DisplayName("Aynı olay+faz+kişi zaten kayıtlıysa İKİNCİ satır yazılmaz (anti-loop)")
    void dedupe_existingRowShortCircuits() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001");
        when(deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(eq(1L), eq("OPEN"), eq("N00001")))
                .thenReturn(true);

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        verify(deliveryRepo, never()).save(any());
    }

    @Test
    @DisplayName("Sessiz saat penceresinde seviye eşiğin altındaysa SKIPPED_QUIET_HOURS (E2)")
    void quietHours_blocksBelowMinLevel() {
        when(appSettings.getString(eq("site.monitor.userpush.quiet-start"), any())).thenReturn("00:00");
        when(appSettings.getString(eq("site.monitor.userpush.quiet-end"), any())).thenReturn("23:59");
        when(appSettings.getString(eq("site.monitor.userpush.quiet-min-level"), any())).thenReturn("CRITICAL");
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        assertThat(savedRows()).singleElement()
                .extracting(UserPushDelivery::getStatus).isEqualTo("SKIPPED_QUIET_HOURS");
    }

    @Test
    @DisplayName("Sessiz saatte KRİTİK yine geçer — pencere yalnız eşiğin altını susturur")
    void quietHours_criticalPasses() {
        when(appSettings.getString(eq("site.monitor.userpush.quiet-start"), any())).thenReturn("00:00");
        when(appSettings.getString(eq("site.monitor.userpush.quiet-end"), any())).thenReturn("23:59");
        when(appSettings.getString(eq("site.monitor.userpush.quiet-min-level"), any())).thenReturn("CRITICAL");
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "CRITICAL", "HTTP_DOWN")));
        recipients("N00001");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        // Satır KUYRUĞA girdi mi ona bakılır (worker durumu sonra değiştirebilir) —
        // pencere onu SUSTURMADIYSA kazanılmıştır.
        assertThat(store).extracting(UserPushDelivery::getUsername).contains("N00001");
        assertThat(store).extracting(UserPushDelivery::getStatus)
                .doesNotContain("SKIPPED_QUIET_HOURS");
    }

    /**
     * İzleme bayrağı kararı KALICI: OPEN'da SKIPPED_MONITOR_OFF yazıldıysa RESEND/RE_ALERT de
     * gitmez — manuel "yeniden gönder" yolu ctx'i taşımaz (izleme tiplerinde certContext=null),
     * satır olmasaydı kapalı izlemeye push sızardı.
     */
    @Test
    @DisplayName("MONITOR_OFF kararı sonraki fazları da bağlar — RESEND ctx'siz gelse bile gitmez")
    void monitorOffDecision_persistsAcrossPhases() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001");
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(true);

        service.enqueueAlert(1L, "MANUAL", 5L, Map.of());   // ctx'te push_disabled YOK

        verify(resolver, never()).resolve(any(), any());
        assertThat(savedRows()).singleElement()
                .extracting(UserPushDelivery::getStatus).isEqualTo("SKIPPED_MONITOR_OFF");
    }

    @Test
    @DisplayName("Çözüm SİMETRİSİ: açılışı kimseye gitmemiş olayın çözümü de gitmez")
    void resolve_withoutPriorSent_isSkipped() {
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SENT")).thenReturn(false);

        service.enqueueResolve(event(1L, "HIGH", "HTTP_DOWN"), Map.of());

        verify(resolver, never()).resolve(any(), any());
        assertThat(savedRows()).singleElement()
                .extracting(UserPushDelivery::getStatus).isEqualTo("SKIPPED_NO_PRIOR");
    }

    // ── Gövde sözleşmesi + yanıt işleme (gerçek HTTP) ──────────────────────────────────────

    @Test
    @DisplayName("Gövde SÖZLEŞMEYE birebir: {title, message, pipeline, userIds} — tek toplu istek")
    void bodyContract_singleBatchRequest() throws Exception {
        startServer();
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001", "N00002");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !receivedBodies.isEmpty());

        assertThat(receivedBodies).hasSize(1);   // K9: tek toplu istek, kişi başına DEĞİL
        String body = receivedBodies.get(0);
        // Alan adları VE sırası — API sözleşmesinin kendisi.
        assertThat(body).matches("\\{\"title\":.*\"message\":.*\"pipeline\":.*\"userIds\":.*}");
        assertThat(body).contains("\"title\":\"Site Monitor\"");
        assertThat(body).contains("\"userIds\":[\"N00001\",\"N00002\"]");
    }

    @Test
    @DisplayName("2xx yanıttaki notificationId TÜM alt satırlara yazılır (kanıt zinciri)")
    void notificationId_parsedIntoAllRows() throws Exception {
        startServer();
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001", "N00002");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> store.stream().allMatch(d -> "SENT".equals(d.getStatus())));

        assertThat(store).hasSize(2);
        assertThat(store).allSatisfy(d -> {
            assertThat(d.getNotificationId()).isEqualTo("1897198");
            assertThat(d.getHttpStatus()).isEqualTo(200);
        });
    }

    @Test
    @DisplayName("Yanıt gövdesi BOZUKSA gönderim yine SENT — notificationId null kalır, FAILED yazılmaz")
    void malformedResponse_stillSent() throws Exception {
        respBody = "tesekkurler";   // JSON değil
        startServer();
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> store.stream().allMatch(d -> "SENT".equals(d.getStatus())));

        assertThat(store).allSatisfy(d -> {
            assertThat(d.getNotificationId()).isNull();
            assertThat(d.getRawResponse()).contains("tesekkurler");
        });
    }

    @Test
    @DisplayName("5xx yanıt → FAILED yolu (retry tavanı ve hata mesajı satırda)")
    void serverError_marksFailed() throws Exception {
        respStatus.set(503);
        respBody = "unavailable";
        startServer();
        when(appSettings.getInt(eq("site.monitor.userpush.retry-max"), any(Integer.class))).thenReturn(0);
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> store.stream().allMatch(d -> "FAILED".equals(d.getStatus())));

        assertThat(store).allSatisfy(d -> assertThat(d.getError()).contains("503"));
    }

    // ── Kurumsal TLS güveni (2026-08-28 prod hatası) ───────────────────────────────────────

    /**
     * PROD HATASI: düz HttpClient yalnız JVM cacerts'e bakıyordu; bildirim API'si kurumsal CA
     * ile imzalı olduğundan HER gönderim "PKIX path building failed" ile düştü. İstemci artık
     * projenin ortak güven zincirini (cacerts → kurumsal CA paketi → TOFU auto-pin) kullanmalı.
     */
    @Test
    @DisplayName("TLS: giden istemci kurumsal güven zincirini KULLANIR (PKIX prod hatası)")
    void outboundClientUsesCorporateTrustChain() throws Exception {
        startServer();
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !receivedBodies.isEmpty());

        // Güven zinciri auto-pin geri çağrılarıyla birlikte istenmiş olmalı (RDAP/.tr-whois deseni).
        verify(trustEvaluator, org.mockito.Mockito.atLeastOnce())
                .pinAwareOutboundSslContext(any(), any());
    }

    @Test
    @DisplayName("TLS: bağlam kurulamazsa (null) gönderim yine yapılır — varsayılan güvene düşer")
    void nullSslContext_stillSends() throws Exception {
        startServer();
        when(trustEvaluator.pinAwareOutboundSslContext(any(), any())).thenReturn(null);
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        recipients("N00001");

        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());

        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> store.stream().allMatch(d -> "SENT".equals(d.getStatus())));
    }

    /**
     * Ham istisna operatöre ne yapacağını söylemiyordu: teslimat günlüğünde yalnız
     * "SSLHandshakeException ... PKIX path building failed" görünüyordu. Tanıdık arızalarda
     * satır artık YÖN veriyor; ham istisna da korunuyor (teşhis kaybolmasın).
     */
    @Test
    @DisplayName("Hata metni tanıdık arızada YÖN verir, ham istisnayı da korur")
    void errorMessagesAreActionable() {
        String pkix = UserPushService.explain(new javax.net.ssl.SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path"));
        assertThat(pkix).contains("kurumsal CA paketine");
        assertThat(pkix).contains("PKIX path building failed");   // ham teşhis korunur

        assertThat(UserPushService.explain(new java.net.ConnectException("conn refused")))
                .contains("Bağlantı kurulamadı");
        assertThat(UserPushService.explain(new java.net.http.HttpTimeoutException("timeout")))
                .contains("zaman aşımına");

        // Tanınmayan arızada metin DEĞİŞMEZ — uydurma yön verilmez.
        String odd = UserPushService.explain(new IllegalStateException("beklenmedik"));
        assertThat(odd).isEqualTo("java.lang.IllegalStateException: beklenmedik");
    }

    // ── Bağımsızlık sözleşmesi (kural 1) ───────────────────────────────────────────────────

    @Test
    @DisplayName("Servis içi HER istisna yutulur — çağıran (mail yolu) asla etkilenmez")
    void enqueueSwallowsAllExceptions() {
        when(alertEventRepo.findById(anyLong())).thenThrow(new RuntimeException("db down"));

        // İstisna yayılırsa test patlar — yayılMAMASI sözleşmenin kendisi.
        service.enqueueAlert(1L, "INITIAL", 5L, Map.of());
        service.enqueueResolve(event(1L, "HIGH", "HTTP_DOWN"), Map.of());
    }

    // ── Şablonlar (K6) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Şablon ailesi olay tipinden seçilir; mesaj 200 karakterde kırpılır")
    void templates_familyAndTruncation() {
        assertThat(UserPushService.templateKeyFor("HTTP_DOWN", "OPEN")).isEqualTo("down");
        assertThat(UserPushService.templateKeyFor("PORT_SLOW", "OPEN")).isEqualTo("slow");
        assertThat(UserPushService.templateKeyFor("EXPIRY", "OPEN")).isEqualTo("expiry");
        assertThat(UserPushService.templateKeyFor("DOMAINMON_EXPIRY", "OPEN")).isEqualTo("expiry");
        assertThat(UserPushService.templateKeyFor("DNS_CHANGED", "OPEN")).isEqualTo("changed");
        assertThat(UserPushService.templateKeyFor("HTTP_DOWN", "RESOLVE")).isEqualTo("resolved");

        AlertEvent e = event(1L, "CRITICAL", "HTTP_DOWN");
        // {neden} kendi tavanında kırpılır; 200 tavanını AD/HEDEF uzunluğuyla zorla.
        e.setDomain("cok-uzun-alt-alan-adi-".repeat(8) + "example.com");
        e.setMessage("x".repeat(500));
        String msg = service.buildMessage(e, "OPEN", Map.of());
        assertThat(msg).hasSizeLessThanOrEqualTo(200);
        // Kirpma isareti ARTIK "..." — tek karakterli "…" alici kanalda (ISO-8859-9) soru
        // isaretine donuyordu; kullanicinin telefonunda gordugu "?" dizisinin bir parcasiydi.
        assertThat(msg).endsWith("...");
        assertThat(msg).startsWith("KRİTİK");
    }

    @Test
    @DisplayName("Şablon değişikliği ANINDA etkir — ayar canlı okunur, önbellek yok")
    void templates_readLive() {
        when(appSettings.getString(eq("site.monitor.userpush.template.down"), any()))
                .thenReturn("YENİ ŞABLON {ad}");
        AlertEvent e = event(1L, "HIGH", "HTTP_DOWN");

        assertThat(service.buildMessage(e, "OPEN", Map.of())).isEqualTo("YENİ ŞABLON example.com");
    }

    // -- Giden istemci omru (O14) --------------------------------------------

    @Test
    @DisplayName("Istemci gonderim basina DEGIL bir kez kurulur - selector-thread/FD sizintisi kapisi")
    void client_isReusedAcrossCalls() {
        // Onceden her sendBatch yeni bir HttpClient kuruyor ve HIC kapatmiyordu: her JDK istemcisi
        // kendi selector-thread'ini, baglanti havuzunu ve FD'lerini tutar.
        Object a = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "client");
        Object b = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "client");
        assertThat(a).isNotNull();
        assertThat(a).isSameAs(b);
        // Guven baglami da her cagrida yeniden kurulmaz
        verify(trustEvaluator, org.mockito.Mockito.times(1)).pinAwareOutboundSslContext(any(), any());
    }

    @Test
    @DisplayName("connectTimeout ayari DEGISIRSE istemci yeniden kurulur (ayar canli okunur)")
    void client_rebuiltWhenConnectTimeoutChanges() {
        Object a = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "client");
        when(appSettings.getInt(eq("site.monitor.userpush.timeout-connect-seconds"), any(Integer.class)))
                .thenReturn(9);
        Object b = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "client");
        assertThat(b).isNotSameAs(a);
    }

    // -- A2: onay pop-up'i (kanal kanal haric tutma + onizleme) ---------------

    @Test
    @DisplayName("A2: onay ekraninda CIKARILAN sicile satir YAZILMAZ, digerleri gider")
    void excludeUsernames_noRowForExcluded() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(false);
        recipients("N00001", "N00002", "N00003");

        service.enqueueAlert(1L, "MANUAL", 5L, null, java.util.Set.of("N00002"));

        var users = savedRows().stream().map(UserPushDelivery::getUsername).toList();
        assertThat(users).contains("N00001", "N00003");
        assertThat(users).doesNotContain("N00002");
    }

    @Test
    @DisplayName("A2: TUM sicilller cikarilirsa alici kalmaz -> SKIPPED_NO_RECIPIENTS karar satiri")
    void excludeUsernames_allExcluded_writesDecisionRow() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(false);
        recipients("N00001", "N00002");

        service.enqueueAlert(1L, "MANUAL", 5L, null, java.util.Set.of("N00001", "N00002"));

        assertThat(savedRows()).allSatisfy(r ->
                assertThat(r.getStatus()).isEqualTo("SKIPPED_NO_RECIPIENTS"));
    }

    @Test
    @DisplayName("A2: haric tutma VERILMEYEN eski imza davranisi DEGISMEZ (geriye uyum)")
    void enqueueAlert_legacySignature_unchanged() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(false);
        recipients("N00001", "N00002");

        service.enqueueAlert(1L, "MANUAL", 5L, null);

        assertThat(savedRows().stream().map(UserPushDelivery::getUsername).toList())
                .contains("N00001", "N00002");
    }

    @Test
    @DisplayName("A2: onizleme alicilari ve durumlarini doner, HICBIR satir YAZMAZ")
    void preview_listsRecipients_writesNothing() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(false);
        recipients("N00001", "N00002");

        var p = service.preview(1L, 5L);

        assertThat(p.channelEnabled()).isTrue();
        assertThat(p.blockReason()).isNull();
        assertThat(p.recipients()).extracting(UserPushService.PushPreviewRow::username)
                .containsExactly("N00001", "N00002");
        assertThat(p.recipients()).allSatisfy(r -> assertThat(r.status()).isEqualTo("PENDING"));
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    @DisplayName("A2: onizleme kanal KAPALI oldugunu SEBEBIYLE soyler (sessiz 'gitmedi' yok)")
    void preview_reportsBlockReason() {
        when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(false);

        var p = service.preview(1L, 5L);

        assertThat(p.channelEnabled()).isFalse();
        assertThat(p.blockReason()).isEqualTo("CHANNEL_DISABLED");
        assertThat(p.recipients()).isEmpty();
    }

    @Test
    @DisplayName("A2: alici hic yoksa onizleme SKIPPED_NO_RECIPIENTS sebebini doner")
    void preview_noRecipients_reportsReason() {
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(false);
        recipients();   // bos

        var p = service.preview(1L, 5L);

        assertThat(p.blockReason()).isEqualTo("SKIPPED_NO_RECIPIENTS");
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    @DisplayName("A2: onizleme KANAL duzeyi reddi de bildirir (TAKIM kapali) - alici cozumune hic gitmez")
    void preview_channelLevelBlock_reported() {
        // Mutasyon turunda cikan bosluk: onceki testler yalnizca CHANNEL_DISABLED (erken donus) ve
        // SKIPPED_NO_RECIPIENTS (gec donus) dallarini tutuyordu; ARADAKI katman matrisi kararini
        // (tip/takim/izleme/sessiz saat) hicbir test pinlemiyordu. channelBlockReason cagrisi
        // silinse bile suit yesil kaliyordu.
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event(1L, "HIGH", "HTTP_DOWN")));
        when(deliveryRepo.existsByAlertEventIdAndStatus(1L, "SKIPPED_MONITOR_OFF")).thenReturn(false);
        UserPushScope off = new UserPushScope();
        off.setEnabled(false);
        when(scopeRepo.findByScopeTypeAndScopeKey("TEAM", "5")).thenReturn(Optional.of(off));

        var p = service.preview(1L, 5L);

        assertThat(p.blockReason()).isEqualTo("SKIPPED_TEAM_OFF");
        assertThat(p.recipients()).isEmpty();
        verify(resolver, never()).resolve(any(), any());
        verify(deliveryRepo, never()).save(any());
    }
    // ── Ayar kırpması: arayüz aralığı dayatıyor, sunucu da dayatmalı ────────────────────────
    //
    // AppSettingsService.validate yalnız TİP doğruluyor, ARALIK doğrulamıyor. API'den 0
    // gönderilirse her push mesajı "..." olurdu. Aynı sınıf bulgu DNS/Domain teyit-kurtarma
    // alanlarında da çıkmış ve orada da sunucu tarafı kırpmayla çözülmüştü.

    private AlertEvent longEvent() {
        AlertEvent e = event(1L, "CRITICAL", "HTTP_DOWN");
        e.setDomain("a.example.com");
        e.setMessage("KRİTİK: a.example.com " + "uzun sebep metni ".repeat(40));
        return e;
    }

    @Test
    @DisplayName("Ayar kırpması: max-message-chars=0 mesajı '...' yapmaz, tabana çekilir")
    void maxMessageChars_zero_isClampedToFloor() {
        when(appSettings.getInt(eq("site.monitor.userpush.max-message-chars"), any(Integer.class)))
                .thenReturn(0);

        String msg = service.buildMessage(longEvent(), "OPEN", Map.of());

        assertThat(msg).isNotEqualTo("...");
        assertThat(msg.length()).isGreaterThanOrEqualTo(40).isLessThanOrEqualTo(80);
    }

    @Test
    @DisplayName("Ayar kırpması: max-message-chars çok büyükse tavana çekilir (kanal sözleşmesi)")
    void maxMessageChars_huge_isClampedToCeiling() {
        when(appSettings.getInt(eq("site.monitor.userpush.max-message-chars"), any(Integer.class)))
                .thenReturn(99999);

        String msg = service.buildMessage(longEvent(), "OPEN", Map.of());

        assertThat(msg.length()).isLessThanOrEqualTo(320);
    }

    @Test
    @DisplayName("Ayar kırpması: reason-max-chars=0 bilinçli 'tavan yok' — dış tavan yine korur")
    void reasonMaxChars_zero_meansNoReasonCap() {
        when(appSettings.getInt(eq("site.monitor.userpush.reason-max-chars"), any(Integer.class)))
                .thenReturn(0);

        String msg = service.buildMessage(longEvent(), "OPEN", Map.of());

        assertThat(msg.length()).isLessThanOrEqualTo(200);   // dış tavan devrede
    }

    @Test
    @DisplayName("Ayar kırpması: reason-max-chars aralık dışı değerler tabana/tavana çekilir")
    void reasonMaxChars_outOfRange_isClamped() {
        // Çok küçük: sebep tamamen yok olmamalı, tabana (40) çekilmeli.
        when(appSettings.getInt(eq("site.monitor.userpush.reason-max-chars"), any(Integer.class)))
                .thenReturn(5);
        String kucuk = service.buildMessage(longEvent(), "OPEN", Map.of());

        // Çok büyük: sebep tavanı 280'i aşmamalı.
        when(appSettings.getInt(eq("site.monitor.userpush.reason-max-chars"), any(Integer.class)))
                .thenReturn(99999);
        String buyuk = service.buildMessage(longEvent(), "OPEN", Map.of());

        assertThat(kucuk).as("5 tabana çekilmeli — sebep okunur kalmalı").contains("uzun sebep");
        assertThat(kucuk.length()).isLessThan(buyuk.length());
        assertThat(buyuk.length()).isLessThanOrEqualTo(200);
    }


    // ── Süre-bitişi metni: gün ve tarih ────────────────────────────────────

    /**
     * KULLANICI BULGUSU: aynı olay, aynı saniye — push "15 gün", e-posta "14 gün" dedi.
     *
     * <p>Kök neden: push {@code {gun}} yer tutucusunu YALNIZ {@code event.getDaysRemaining()}
     * ile dolduruyordu. O değer alarm açılırken/tırmanırken yazılır; e-posta ise gönderim anında
     * {@code latest_check}'ten TAZE değeri kullanır. Bir alarm ürününde iki kanalın aynı olay
     * için farklı sayı söylemesi, operatörün hangisine inanacağını belirsizleştirir.
     *
     * <p>Kural artık dosyanın geri kalanıyla aynı: ÖNCE ctx, sonra event (bkz. metrik/deger/esik).
     */
    @Test
    @DisplayName("gun: ctx TAZE degeri kazanir, event'teki bayat deger degil")
    void expiryDays_prefersFreshCtxOverStaleEvent() {
        AlertEvent e = event(1L, "HIGH", "EXPIRY");
        e.setDaysRemaining(15);                       // alarm açılırken yazılmış BAYAT değer

        String text = service.buildMessage(e, "DAILY_REALERT",
                java.util.Map.of("days_remaining", 14));

        assertThat(text).as("bayat gun sayisi push'a sizdi").contains("14");
        assertThat(text).doesNotContain("15 gün");
    }

    @Test
    @DisplayName("gun: ctx yoksa event degeri yedek kalir (davranis kaybolmaz)")
    void expiryDays_fallsBackToEventWhenCtxMissing() {
        AlertEvent e = event(2L, "HIGH", "EXPIRY");
        e.setDaysRemaining(9);

        assertThat(service.buildMessage(e, "DAILY_REALERT", java.util.Map.of())).contains("9");
    }

    /**
     * Sertifika bağlamı {@code not_after} taşır, {@code expiry_date} TAŞIMAZ — o anahtar yalnız
     * domain/whois tarafında var. Şablon yalnız {@code expiry_date} aradığı için sertifika
     * süre-bitişi push'larında tarih HER ZAMAN "-" çıkıyordu: "… 14 gün içinde doluyor (-)".
     */
    @Test
    @DisplayName("tarih: sertifika baglaminda not_after'a duser, '-' kalmaz")
    void expiryDate_fallsBackToNotAfterForCertificates() {
        AlertEvent e = event(3L, "HIGH", "EXPIRY");

        String text = service.buildMessage(e, "DAILY_REALERT",
                java.util.Map.of("days_remaining", 14, "not_after", "2026-09-22T23:59:59"));

        assertThat(text).as("sertifika tarihi hala bos").doesNotContain("(-)");
        assertThat(text).contains("23.09.2026");   // UTC damga → İstanbul takvimi
    }

    @Test
    @DisplayName("tarih: domain baglaminda expiry_date ONCELIKLI kalir")
    void expiryDate_keepsExplicitExpiryDate() {
        AlertEvent e = event(4L, "HIGH", "DOMAINMON_EXPIRY");

        String text = service.buildMessage(e, "DAILY_REALERT",
                java.util.Map.of("days_remaining", 30, "expiry_date", "2027-01-01",
                                 "not_after", "2026-09-22T23:59:59"));

        assertThat(text).contains("2027-01-01");
    }
}
