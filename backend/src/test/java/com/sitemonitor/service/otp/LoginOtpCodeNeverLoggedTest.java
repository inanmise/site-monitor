package com.sitemonitor.service.otp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.config.RequestLoggingFilter;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.LoginOtpChallengeRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EmailTemplateBuilder;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SmtpMailService;
import com.sitemonitor.service.SmtpSettingsService;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.UserService;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KAPI (2026-10-02, kodla giriş): tek kullanımlık kod HİÇBİR log satırına, DB alanına, push teslimat satırına,
 * notification_logs'a, denetim ayrıntısına ya da hata metnine yazılmaz.
 *
 * <p>Gerçek parçalarla uçtan uca: H2 üstünde gerçek challenge tablosu, gerçek gönderim işi (eşzamansız havuz), GERÇEK
 * push istemcisi (yerel HTTP sunucusu — önce 500 sonra 200) ve GERÇEK e-posta hunisi (SMTP önce reddediyor, sonra kabul
 * ediyor). {@code com.sitemonitor} ve {@code com.sitemonitor.mail} TRACE'e çekilir, istek loglama filtresi TRACE'te
 * doğrulama gövdesini görür; KÖK logger'a bağlı bir dinleyici her olayın biçimlenmiş metnini VE istisna yığınını tarar.
 * Push yükünün sözleşmesi de burada: alarm push'uyla AYNI biçim {@code {title, message, pipeline, userIds}}, kod
 * mesajda, teslimat günlüğüne satır YOK.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginOtpCodeNeverLoggedTest {

    static final String CODE = "583920";
    /** Yöneticinin özel EN push şablonu (2026-10-03) — yer tutucuların hepsi + kanalın taşıyamadığı tipografi. */
    static final String CUSTOM_EN = "SiteMonitor code {kod} (valid {sure} s, requested {saat}) – not you? Ignore ✓";

    @Autowired LoginOtpChallengeRepository repo;

    private HttpServer server;
    private final List<String> pushBodies = new CopyOnWriteArrayList<>();
    private final List<String> pushHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger pushStatus = new AtomicInteger(500);

    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Logger app;
    private Logger mail;
    private Level appLevel;
    private Level mailLevel;

    private UserPushDeliveryRepository pushDeliveryRepo;
    private NotificationLogRepository notificationLogRepo;
    private AuditService audit;
    private JavaMailSenderImpl sender;
    private LoginOtpService svc;
    private LoginOtpServiceFlowTest.MutableClock clock;
    private LoginMethodsService methods;
    private UserService users;
    private AppUser alice;

    @BeforeEach
    void setUp() throws Exception {
        repo.deleteAll();
        // ── log yakalama: kök dinleyici + uygulama logları TRACE ──
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        app = (Logger) LoggerFactory.getLogger("com.sitemonitor");
        mail = (Logger) LoggerFactory.getLogger("com.sitemonitor.mail");
        appLevel = app.getLevel();
        mailLevel = mail.getLevel();
        app.setLevel(Level.TRACE);
        mail.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        // ── yerel push ağ geçidi ──
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", ex -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(b);
            pushBodies.add(b.toString(StandardCharsets.UTF_8));
            pushHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Gateway-Key")));
            byte[] out = ("{\"echo\":\"" + b.toString(StandardCharsets.UTF_8).replace("\"", "'") + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(pushStatus.get(), out.length);   // gövde isteği YANKILAR — okunursa log'a sızardı
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";

        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(appSettings.getString(eq("site.monitor.userpush.url"), any())).thenReturn(url);
        lenient().when(appSettings.getString(eq("site.monitor.userpush.pipeline"), any())).thenReturn("sitemonitor-pipeline");
        lenient().when(appSettings.getString(eq("site.monitor.userpush.headers"), any()))
                .thenReturn("[{\"name\":\"X-Gateway-Key\",\"value\":\"k-123\",\"secret\":false}]");
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        TrustEvaluator trust = mock(TrustEvaluator.class);
        lenient().when(trust.pinAwareOutboundSslContext(any(), any())).thenReturn(null);
        pushDeliveryRepo = mock(UserPushDeliveryRepository.class);
        UserPushService push = new UserPushService(appSettings, pushDeliveryRepo, mock(UserPushScopeRepository.class),
                mock(UserPushRecipientResolver.class), mock(AlertEventRepository.class), mock(SecretCipher.class), trust,
                mock(CaAutoPinService.class));

        // ── gerçek e-posta hunisi, SMTP taklidi ──
        SmtpSettingsService smtpSettings = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(false);   // alarm e-postası susturulmuş — giriş kodu yine gider (force)
        s.setFromAddress("noreply@example.com");
        lenient().when(smtpSettings.getOrDefaults()).thenReturn(s);
        SmtpMailService smtpMail = mock(SmtpMailService.class);
        sender = mock(JavaMailSenderImpl.class);
        lenient().when(smtpMail.currentSender()).thenReturn(sender);
        lenient().when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", "https://sitemonitor.example.com");
        notificationLogRepo = mock(NotificationLogRepository.class);
        EmailNotificationService email = new EmailNotificationService(smtpSettings, smtpMail, notificationLogRepo, appSettings, tb);

        audit = mock(AuditService.class);

        methods = mock(LoginMethodsService.class);
        // 2026-10-03: yönetici push metni — TR yerleşik varsayılan (boş kayıt), EN ÖZEL şablon ({kod} {sure} {saat} +
        // kanalın taşıyamadığı tipografi): kod özel şablonla da hiçbir log'a / satıra / denetime sızmamalı.
        lenient().when(methods.pushTitleTemplate(false)).thenReturn("");
        lenient().when(methods.pushMessageTemplate(false)).thenReturn("");
        lenient().when(methods.pushTitleTemplate(true)).thenReturn("Sign-in ✓ SiteMonitor");
        lenient().when(methods.pushMessageTemplate(true)).thenReturn(CUSTOM_EN);
        lenient().when(methods.pushMessageLimit()).thenReturn(200);
        LoginOtpDeliveryService delivery = new LoginOtpDeliveryService(repo, push, email, audit, methods);

        lenient().when(methods.available(any())).thenReturn(true);
        lenient().when(methods.ttlSeconds(any())).thenReturn(45);
        lenient().when(methods.maxAttempts()).thenReturn(3);
        lenient().when(methods.resendCooldownSeconds()).thenReturn(30);
        lenient().when(methods.maxRequestsPerUser()).thenReturn(5);
        lenient().when(methods.maxRequestsPerIp()).thenReturn(20);
        lenient().when(methods.maxFailedVerifications()).thenReturn(5);
        users = mock(UserService.class);
        alice = LoginOtpServiceFlowTest.user(7L, "ALICE", "USER", "alice@example.com");
        lenient().when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        lenient().when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        lenient().when(users.findByUsername("ALICE")).thenReturn(Optional.of(alice));
        lenient().when(users.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        OtpCodes codes = spy(new OtpCodes(null));
        doReturn(CODE).when(codes).newCode();
        svc = new LoginOtpService(repo, methods, users, audit, codes, delivery);
        clock = new LoginOtpServiceFlowTest.MutableClock();
        clock.now = java.time.Instant.now();
        svc.setClock(clock);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        app.setLevel(appLevel);
        mail.setLevel(mailLevel);
        if (server != null) server.stop(0);
        repo.deleteAll();
    }

    private String awaitDelivery(String id) {
        await().atMost(Duration.ofSeconds(10)).until(() -> repo.findById(id)
                .map(c -> !"QUEUED".equals(c.getDeliveryStatus())).orElse(false));
        return repo.findById(id).orElseThrow().getDeliveryStatus();
    }

    @Test
    @DisplayName("KOD hiçbir log'a (TRACE dahil, istisna yığınları dahil), DB alanına, push teslimat / notification_logs satırına, denetime yazılmaz")
    void codeNeverLeaks() throws Exception {
        // 1) push — ağ geçidi 500 (yanıt gövdesi isteği yankılar) → FAILED, denetim LOGIN_OTP_DELIVERY_FAILED
        String id1 = (String) svc.request("alice", "push", "10.0.0.1", "Mozilla/5.0 JUnit", false).body().get("challenge_id");
        assertThat(awaitDelivery(id1)).isEqualTo("FAILED: HTTP 500");
        // 2) push — ağ geçidi 200 → SENT
        pushStatus.set(200);
        clock.plus(31);
        String id2 = (String) svc.request("alice", "push", "10.0.0.1", "Mozilla/5.0 JUnit", true).body().get("challenge_id");
        assertThat(awaitDelivery(id2)).isEqualTo("SENT");
        // 3) e-posta — SMTP reddediyor (istisna + yığın loglanır) → FAILED
        doThrow(new MailSendException("554 5.7.1 Message rejected by policy")).when(sender).send(any(MimeMessage.class));
        String id3 = (String) svc.request("alice", "email", "10.0.0.1", "Mozilla/5.0 JUnit", false).body().get("challenge_id");
        assertThat(awaitDelivery(id3)).startsWith("FAILED");
        // 4) e-posta — kabul → SENT
        org.mockito.Mockito.reset(sender);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        clock.plus(31);
        String id4 = (String) svc.request("alice", "email", "10.0.0.1", "Mozilla/5.0 JUnit", false).body().get("challenge_id");
        assertThat(awaitDelivery(id4)).isEqualTo("SENT");
        // 5) doğrulama: yanlış, sonra doğru kod (+ tüketim)
        svc.verify(id2, "000001", "10.0.0.1", "JUnit");
        LoginOtpService.Verified v = svc.verify(id2, CODE, "10.0.0.1", "JUnit").verified();
        assertThat(v).isNotNull();
        assertThat(svc.consume(v.challenge())).isTrue();
        // 6) istek loglama filtresi TRACE'te: kod uçlarının gövdeleri ATLANIR
        RequestLoggingFilter filter = new RequestLoggingFilter();
        for (String uri : new String[] { "/api/login/otp/verify", "/api/login/otp/request", "/api/login//otp/verify;jsessionid=x" }) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
            req.setContentType("application/json");
            req.setContent(("{\"challenge_id\":\"" + id2 + "\",\"code\":\"" + CODE + "\"}").getBytes(StandardCharsets.UTF_8));
            filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> rq.getInputStream().readAllBytes());
        }

        // ── Push yükü: alarm push'uyla AYNI sözleşme; kod mesajda; başlıklar gönderildi ──
        assertThat(pushBodies).hasSize(2);
        String body = pushBodies.get(1);
        assertThat(body).startsWith("{\"title\":").contains("\"pipeline\":\"sitemonitor-pipeline\"")
                .contains("\"userIds\":[\"ALICE\"]").contains(CODE).contains("45 s");
        // İkinci istek İngilizce: ÖZEL şablon dolu ve kanal-süzgeçli gider (– → -, ✓ → OK), kod tam
        assertThat(body).contains("\"title\":\"Sign-in OK SiteMonitor\"")
                .contains("SiteMonitor code " + CODE + " (valid 45 s, requested ")
                .contains(") - not you? Ignore OK").doesNotContain("–").doesNotContain("✓").doesNotContain("{kod}");
        // Kanal ISO-8859-9: em-dash ağ geçidine "-" olarak gider (2026-10-03, "koddan sonra ?" hatası — LoginOtpPushSafeTextTest)
        assertThat(pushBodies.get(0)).contains("SiteMonitor giriş kodunuz: " + CODE + " - 45 sn geçerli").doesNotContain("—");
        assertThat(pushHeaders).containsOnly("k-123");
        // teslimat günlüğüne / bildirim günlüğüne HİÇ dokunulmadı
        verifyNoInteractions(pushDeliveryRepo);
        verifyNoInteractions(notificationLogRepo);

        // ── Log taraması ──
        List<String> leaks = new ArrayList<>();
        int traceCount = 0;
        for (ILoggingEvent e : appender.list) {
            if (e.getLevel() == Level.TRACE) traceCount++;
            String text = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            if (text.contains(CODE)) leaks.add(e.getLoggerName() + " " + e.getLevel() + ": " + text);
        }
        assertThat(traceCount).as("TRACE gerçekten açıktı (filtre + SMTP TRACE)").isGreaterThan(0);
        assertThat(appender.list).as("loglar yakalandı").isNotEmpty();
        assertThat(leaks).as("KOD log'a sızdı").isEmpty();

        // ── DB: hiçbir satırın hiçbir alanı kodu taşımıyor ──
        for (LoginOtpChallenge c : repo.findAll()) {
            for (Field f : LoginOtpChallenge.class.getDeclaredFields()) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equals("id") || f.getName().equals("codeHmac")) continue;
                f.setAccessible(true);
                assertThat(String.valueOf(f.get(c))).as("alan " + f.getName()).doesNotContain(CODE);
            }
            assertThat(c.getCodeHmac()).isNotEqualTo(CODE).hasSize(64);
        }

        // ── Denetim: her çağrının bütün metin argümanları kodsuz ──
        ArgumentCaptor<String> a1 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).recordOtp(a1.capture(), any(), any(), any(), any(), any(),
                reason.capture(), detail.capture(), any(), any());
        assertThat(a1.getAllValues()).contains("LOGIN_OTP_REQUESTED", "LOGIN_OTP_DELIVERY_FAILED", "LOGIN_OTP_VERIFY_FAILED");
        for (String x : reason.getAllValues()) assertThat(String.valueOf(x)).doesNotContain(CODE);
        for (String x : detail.getAllValues()) assertThat(String.valueOf(x)).doesNotContain(CODE);
        verify(audit, never()).recordAction(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Kullanıcının GİRDİĞİ kişi bilgisinin ayırt edici yazımları (2026-10-03) — kayıtlı değerlerden farklı biçimde yazıldı
     * ki kayıtlı adres / numara bir yerde görünse bile tarama yanlış alarm vermesin. Yer tutucu değerler (gerçek veri yok).
     */
    static final List<String> ENTERED = List.of("0 (500) 000-00-00", "0500 999 99 99", "5009999999", "0500 111 11 11",
            "5001111111", "ALICE@Example.COM", "mallory@example.org");

    @Test
    @DisplayName("2026-10-03: GİRİLEN telefon / e-posta hiçbir log'a (TRACE dahil), DB alanına, denetim argümanına, push gövdesine yazılmaz — yalnız sonuç")
    void enteredContactNeverLeaks() throws Exception {
        lenient().when(methods.requiresContact(any())).thenReturn(true);
        lenient().when(methods.maxContactMismatches()).thenReturn(5);
        alice.setPhone("+90 500 000 00 00");
        pushStatus.set(200);

        // 1) push — kayıtlı numara, başka biçimde → gönderildi
        String id1 = (String) svc.request("alice", "push", "0 (500) 000-00-00", null, "10.0.0.1", "JUnit", false)
                .body().get("challenge_id");
        assertThat(awaitDelivery(id1)).isEqualTo("SENT");
        // 2) push — eşleşmeyen numara → tuzak
        String id2 = (String) svc.request("alice", "push", "0500 999 99 99", null, "10.0.0.1", "JUnit", false)
                .body().get("challenge_id");
        assertThat(repo.findById(id2).orElseThrow().getDeliveryStatus()).isEqualTo("SUPPRESSED_CONTACT_MISMATCH");
        // 3) e-posta — kayıtlı adres (büyük harf + boşluk) → gönderildi
        String id3 = (String) svc.request("alice", "email", null, "  ALICE@Example.COM ", "10.0.0.1", "JUnit", false)
                .body().get("challenge_id");
        assertThat(awaitDelivery(id3)).isEqualTo("SENT");
        // 4) e-posta — başka adres → tuzak
        String id4 = (String) svc.request("alice", "email", null, "mallory@example.org", "10.0.0.1", "JUnit", false)
                .body().get("challenge_id");
        assertThat(repo.findById(id4).orElseThrow().getDeliveryStatus()).isEqualTo("SUPPRESSED_CONTACT_MISMATCH");
        // 5) bilinmeyen kullanıcı → tuzak
        String id5 = (String) svc.request("ghost", "push", "0500 111 11 11", null, "10.0.0.1", "JUnit", false)
                .body().get("challenge_id");
        assertThat(repo.findById(id5).orElseThrow().getDeliveryStatus()).isEqualTo("SUPPRESSED_UNKNOWN_USER");
        // 6) istek loglama filtresi TRACE'te: istek ucunun gövdesi (telefon / e-posta) ATLANIR
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login/otp/request");
        req.setContentType("application/json");
        req.setContent("{\"username\":\"alice\",\"channel\":\"push\",\"phone\":\"0500 999 99 99\",\"email\":\"mallory@example.org\"}"
                .getBytes(StandardCharsets.UTF_8));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> rq.getInputStream().readAllBytes());

        // ── Log taraması ──
        List<String> leaks = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            String text = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            for (String v : ENTERED) if (text.contains(v)) leaks.add(e.getLoggerName() + " " + e.getLevel() + ": " + text);
        }
        assertThat(appender.list).as("loglar yakalandı").isNotEmpty();
        assertThat(leaks).as("GİRİLEN kişi bilgisi log'a sızdı").isEmpty();

        // ── DB: hiçbir satırın hiçbir metin alanı girilen değeri taşımıyor ──
        assertThat(repo.count()).isEqualTo(5);
        for (LoginOtpChallenge c : repo.findAll()) {
            for (Field f : LoginOtpChallenge.class.getDeclaredFields()) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                String v = String.valueOf(f.get(c));
                for (String x : ENTERED) assertThat(v).as("alan " + f.getName()).doesNotContain(x);
            }
        }

        // ── Denetim: her çağrının BÜTÜN metin argümanları — yalnız sonuç (contact=MATCHED / MISMATCH) ──
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> role = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ua = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.atLeast(5)).recordOtp(type.capture(), actor.capture(), any(), any(), role.capture(),
                outcome.capture(), reason.capture(), detail.capture(), ip.capture(), ua.capture());
        List<String> all = new ArrayList<>();
        for (ArgumentCaptor<String> cap : List.of(type, actor, role, outcome, reason, detail, ip, ua)) {
            cap.getAllValues().forEach(s -> all.add(String.valueOf(s)));
        }
        for (String x : ENTERED) assertThat(all).as("denetim: " + x).noneMatch(s -> s.contains(x));
        assertThat(detail.getAllValues()).anyMatch(s -> s.contains("\"contact\":\"MATCHED\""))
                .anyMatch(s -> s.contains("\"contact\":\"MISMATCH\""));
        verify(audit, never()).recordAction(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // ── Push gövdesi ve bildirim günlükleri ──
        assertThat(pushBodies).hasSize(1);
        for (String x : ENTERED) assertThat(pushBodies.get(0)).doesNotContain(x);
        verifyNoInteractions(pushDeliveryRepo);
        verifyNoInteractions(notificationLogRepo);
    }
}
