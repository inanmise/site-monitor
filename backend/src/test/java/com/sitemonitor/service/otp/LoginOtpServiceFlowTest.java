package com.sitemonitor.service.otp;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.repository.LoginOtpChallengeRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kodla giriş durum makinesi — GERÇEK tablo ve sorgularla (H2), üretimdeki gibi ORTAM TRANSACTION'I OLMADAN
 * ({@code NOT_SUPPORTED}: repository'deki koşullu UPDATE'ler kendi transaction'ını açmak zorunda — açmazsa test kırılır).
 *
 * <p>Kapsam: üretim/özet (düz kod hiçbir alanda yok), süre dolması, deneme kilidi, tek kullanım, bekleme süresi, IP ve
 * kullanıcı sınırları, kullanıcı başına başarısız doğrulama askısı (şifre kilidine dokunmaz), TUZAK isteğin yanıtı gerçek
 * istekle birebir (durum + gövde anahtarları + değerler), uygunluk (pasif / kilit / global yönetici anahtarı / kanal yok).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginOtpServiceFlowTest {

    @Autowired LoginOtpChallengeRepository repo;

    LoginMethodsService methods;
    UserService users;
    AuditService audit;
    LoginOtpDeliveryService delivery;
    OtpCodes codes;
    LoginOtpService svc;
    MutableClock clock;

    AppUser alice;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plus(long seconds) { now = now.plusSeconds(seconds); }
    }

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        methods = mock(LoginMethodsService.class);
        users = mock(UserService.class);
        audit = mock(AuditService.class);
        delivery = mock(LoginOtpDeliveryService.class);
        codes = spy(new OtpCodes(null));
        clock = new MutableClock();
        lenient().when(methods.available(any())).thenReturn(true);
        lenient().when(methods.ttlSeconds(any())).thenReturn(45);
        lenient().when(methods.maxAttempts()).thenReturn(3);
        lenient().when(methods.resendCooldownSeconds()).thenReturn(30);
        lenient().when(methods.maxRequestsPerUser()).thenReturn(5);
        lenient().when(methods.maxRequestsPerIp()).thenReturn(20);
        lenient().when(methods.maxFailedVerifications()).thenReturn(5);
        lenient().when(methods.allowGlobalAdmins()).thenReturn(false);
        lenient().when(users.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        lenient().when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        alice = user(7L, "ALICE", "USER", "alice@example.com");
        lenient().when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        lenient().when(users.findByUsername("ALICE")).thenReturn(Optional.of(alice));
        svc = new LoginOtpService(repo, methods, users, audit, codes, delivery);
        svc.setClock(clock);
    }

    static AppUser user(Long id, String name, String role, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(name);
        u.setSystemRole(role);
        u.setEmail(email);
        u.setActive(true);
        return u;
    }

    /** İstek + gönderilen kod (gönderim işi yakalanır). */
    private String[] requestCode(String username, String channel, String ip) {
        LoginOtpService.Result r = svc.request(username, channel, ip, "JUnit", false);
        assertThat(r.status()).isEqualTo(200);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(delivery, atLeastOnce()).dispatch(any(), code.capture());
        return new String[] { (String) r.body().get("challenge_id"), code.getValue() };
    }

    // ── İstek ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("uygun kullanıcı: 200 genel gövde; kod 6 hane; satırda yalnız HMAC — düz kod HİÇBİR alanda yok")
    void request_eligible_storesOnlyHmac() throws Exception {
        String[] r = requestCode("alice", "push", "10.0.0.1");
        String id = r[0], code = r[1];
        assertThat(code).matches("\\d{6}");
        LoginOtpChallenge c = repo.findById(id).orElseThrow();
        assertThat(c.getUsername()).isEqualTo("ALICE");
        assertThat(c.getUserId()).isEqualTo(7L);
        assertThat(c.getChannel()).isEqualTo("PUSH");
        assertThat(c.getStatus()).isEqualTo("PENDING");
        assertThat(c.getDeliveryStatus()).isEqualTo("QUEUED");
        assertThat(c.getCodeHmac()).isEqualTo(codes.hmac(id, code)).isNotEqualTo(code);
        assertThat(c.getExpiresAt()).isEqualTo("2026-10-02T10:00:45");
        for (Field f : LoginOtpChallenge.class.getDeclaredFields()) {
            if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getName().equals("id") || f.getName().equals("codeHmac")) continue;   // rastgele hex — rakam dizisi tesadüfi
            f.setAccessible(true);
            Object v = f.get(c);
            assertThat(v == null ? "" : v.toString()).as("alan %s kodu taşımamalı", f.getName()).doesNotContain(code);
        }
        // Gönderim işi kullanıcı anlık görüntüsünü taşır; denetim ayrıntısı kodsuz
        ArgumentCaptor<LoginOtpDeliveryService.Job> job = ArgumentCaptor.forClass(LoginOtpDeliveryService.Job.class);
        verify(delivery).dispatch(job.capture(), eq(code));
        assertThat(job.getValue().username()).isEqualTo("ALICE");
        assertThat(job.getValue().ttlSeconds()).isEqualTo(45);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordOtp(eq("LOGIN_OTP_REQUESTED"), eq("ALICE"), eq(7L), any(), eq("USER"), eq("SUCCESS"), any(),
                detail.capture(), eq("10.0.0.1"), eq("JUnit"));
        assertThat(detail.getValue()).contains("\"result\":\"SENT\"").doesNotContain(code);
    }

    @Test
    @DisplayName("bilinmeyen kullanıcı: yanıt DURUMU, gövde ANAHTARLARI ve challenge_id dışındaki DEĞERLER uygun kullanıcıyla birebir; kod gönderilmez")
    void request_unknownUser_identicalToEligible() {
        LoginOtpService.Result real = svc.request("alice", "email", "10.0.0.1", "JUnit", false);
        LoginOtpService.Result decoy = svc.request("nobody", "email", "10.0.0.1", "JUnit", false);
        assertThat(decoy.status()).isEqualTo(real.status()).isEqualTo(200);
        assertThat(decoy.body().keySet()).containsExactlyElementsOf(real.body().keySet())
                .containsExactly("success", "challenge_id", "channel", "expires_in", "resend_in");
        Map<String, Object> a = new java.util.LinkedHashMap<>(real.body());
        Map<String, Object> b = new java.util.LinkedHashMap<>(decoy.body());
        assertThat((String) b.remove("challenge_id")).matches("[0-9a-f-]{36}");
        a.remove("challenge_id");
        assertThat(b).isEqualTo(a);
        verify(delivery, times(1)).dispatch(any(), anyString());   // yalnız gerçek istek
        LoginOtpChallenge d = repo.findById((String) decoy.body().get("challenge_id")).orElseThrow();
        assertThat(d.getUserId()).isNull();
        assertThat(d.getDeliveryStatus()).isEqualTo("SUPPRESSED_UNKNOWN_USER");
        assertThat(d.getUsername()).isEqualTo("NOBODY");
        // Kısa devre yok: her iki istek de kilit sorgusunu çalıştırdı (süre ayırt ettirmesin)
        verify(users).checkLockout("ALICE");
        verify(users).checkLockout("NOBODY");
    }

    @Test
    @DisplayName("uygunluk: pasif / geçici kilit / kalıcı kilit / global yönetici (ayar kapalı) / e-postasız → sessizce bastırılır; ayar açıkken global yönetici alır")
    void request_eligibilityMatrix() {
        AppUser passive = user(8L, "PASSIVE", "USER", "p@example.com");
        passive.setActive(false);
        AppUser locked = user(9L, "LOCKED", "USER", "l@example.com");
        AppUser perm = user(10L, "PERM", "USER", "m@example.com");
        perm.setPermanentLock(true);
        AppUser admin = user(11L, "ROOT", "ADMIN", "r@example.com");
        AppUser noMail = user(12L, "NOMAIL", "USER", null);
        AppUser mustChange = user(13L, "MUSTCHG", "USER", "c@example.com");   // yönetici sıfırladı (2026-10-03)
        mustChange.setMustChangePassword(true);
        for (AppUser u : List.of(passive, locked, perm, admin, noMail, mustChange)) {
            when(users.findByUsername(u.getUsername())).thenReturn(Optional.of(u));
        }
        when(users.checkLockout("LOCKED")).thenReturn(new UserService.LockoutStatus(false, 120));
        when(users.computeViewTeamIds(admin)).thenReturn(null);   // global yönetici

        Map<String, String> expect = Map.of("PASSIVE", "SUPPRESSED_INACTIVE", "LOCKED", "SUPPRESSED_LOCKED",
                "PERM", "SUPPRESSED_LOCKED", "ROOT", "SUPPRESSED_GLOBAL_ADMIN_NOT_ALLOWED", "NOMAIL", "SUPPRESSED_NO_TARGET",
                "MUSTCHG", "SUPPRESSED_MUST_CHANGE_PASSWORD");
        for (Map.Entry<String, String> e : expect.entrySet()) {
            LoginOtpService.Result r = svc.request(e.getKey(), "email", "10.0.0.2", "JUnit", false);
            assertThat(r.status()).isEqualTo(200);
            assertThat(repo.findById((String) r.body().get("challenge_id")).orElseThrow().getDeliveryStatus())
                    .as(e.getKey()).isEqualTo(e.getValue());
        }
        verify(delivery, never()).dispatch(any(), anyString());
        // push kanalı e-postasız kullanıcıya gider (hedef = kullanıcı adı / sicil)
        svc.request("NOMAIL", "push", "10.0.0.2", "JUnit", false);
        verify(delivery, times(1)).dispatch(any(), anyString());
        // ayar açıkken global yönetici kod alır
        when(methods.allowGlobalAdmins()).thenReturn(true);
        svc.request("ROOT", "email", "10.0.0.2", "JUnit", false);
        verify(delivery, times(2)).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("yöntem kapalı → 400 OTP_METHOD_DISABLED; geçersiz kanal / boş ad → 400; satır yazılmaz")
    void request_disabledOrInvalid() {
        when(methods.available(LoginOtpService.Channel.PUSH)).thenReturn(false);
        assertThat(svc.request("alice", "push", "10.0.0.1", "JUnit", false).body().get("error_code")).isEqualTo("OTP_METHOD_DISABLED");
        assertThat(svc.request("alice", "sms", "10.0.0.1", "JUnit", false).status()).isEqualTo(400);
        assertThat(svc.request("  ", "email", "10.0.0.1", "JUnit", false).body().get("error_code")).isEqualTo("USERNAME_REQUIRED");
        assertThat(repo.count()).isZero();
    }

    @Test
    @DisplayName("bekleme süresi: 30 sn içinde ikinci istek sessizce bastırılır (COOLDOWN); süre geçince yeniden gönderilir")
    void request_resendCooldown() {
        svc.request("alice", "push", "10.0.0.1", "JUnit", false);
        clock.plus(10);
        LoginOtpService.Result second = svc.request("alice", "push", "10.0.0.1", "JUnit", false);
        assertThat(second.status()).isEqualTo(200);
        assertThat(repo.findById((String) second.body().get("challenge_id")).orElseThrow().getDeliveryStatus()).isEqualTo("SUPPRESSED_COOLDOWN");
        // Kanal başına: aynı anda e-posta istenebilir
        svc.request("alice", "email", "10.0.0.1", "JUnit", false);
        clock.plus(21);
        svc.request("alice", "push", "10.0.0.1", "JUnit", false);
        verify(delivery, times(3)).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("kullanıcı başına istek sınırı (15 dk'da 5): 6. istek sessizce bastırılır, IP'ye 429 DEĞİL")
    void request_perUserLimit_silent() {
        for (int i = 0; i < 5; i++) {
            svc.request("alice", "push", "10.0.0." + i, "JUnit", false);
            clock.plus(31);
        }
        LoginOtpService.Result sixth = svc.request("alice", "push", "10.0.0.9", "JUnit", false);
        assertThat(sixth.status()).isEqualTo(200);
        assertThat(repo.findById((String) sixth.body().get("challenge_id")).orElseThrow().getDeliveryStatus())
                .isEqualTo("SUPPRESSED_USER_RATE_LIMITED");
        verify(delivery, times(5)).dispatch(any(), anyString());
        clock.plus(15 * 60);
        svc.request("alice", "push", "10.0.0.9", "JUnit", false);
        verify(delivery, times(6)).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("IP başına istek sınırı (15 dk'da 20): 21. istek açıkça 429 OTP_RATE_LIMITED — ad ne olursa olsun; başka IP etkilenmez")
    void request_perIpLimit_429() {
        for (int i = 0; i < 20; i++) svc.request("user" + i, "email", "203.0.113.5", "JUnit", false);
        LoginOtpService.Result r = svc.request("alice", "email", "203.0.113.5", "JUnit", false);
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.body()).containsEntry("error_code", "OTP_RATE_LIMITED").containsKey("retry_after");
        assertThat(svc.request("alice", "email", "203.0.113.6", "JUnit", false).status()).isEqualTo(200);
    }

    // ── Doğrulama ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("doğru kod: doğrulanır (henüz tüketilmez) → consume TEK kez kazanır → aynı kod ikinci kez OTP_EXPIRED (tek kullanım)")
    void verify_success_singleUse() {
        String[] r = requestCode("alice", "push", "10.0.0.1");
        LoginOtpService.VerifyOutcome ok = svc.verify(r[0], r[1], "10.0.0.1", "JUnit");
        assertThat(ok.failure()).isNull();
        assertThat(ok.verified().user()).isSameAs(alice);
        assertThat(ok.verified().channel()).isEqualTo(LoginOtpService.Channel.PUSH);
        assertThat(svc.consume(ok.verified().challenge())).isTrue();
        assertThat(svc.consume(ok.verified().challenge())).as("eşzamanlı ikinci tüketim").isFalse();
        LoginOtpService.VerifyOutcome again = svc.verify(r[0], r[1], "10.0.0.1", "JUnit");
        assertThat(again.failure().status()).isEqualTo(401);
        assertThat(again.failure().body()).containsEntry("code", "OTP_EXPIRED");
        assertThat(repo.findById(r[0]).orElseThrow().getConsumedAt()).isNotNull();
    }

    @Test
    @DisplayName("yanlış kod: kalan deneme 2 → 1 → kilit (OTP_LOCKED, attempts_left 0); sonra DOĞRU kod da reddedilir; şifre kilidine dokunulmaz")
    void verify_wrongCode_locksChallenge() {
        String[] r = requestCode("alice", "email", "10.0.0.1");
        String wrong = r[1].equals("000000") ? "000001" : "000000";
        LoginOtpService.Result f1 = svc.verify(r[0], wrong, "10.0.0.1", "JUnit").failure();
        assertThat(f1.status()).isEqualTo(401);
        assertThat(f1.body()).containsEntry("code", "OTP_INVALID").containsEntry("attempts_left", 2);
        assertThat(svc.verify(r[0], "12ab", "10.0.0.1", "JUnit").failure().body()).containsEntry("attempts_left", 1);
        LoginOtpService.Result f3 = svc.verify(r[0], wrong, "10.0.0.1", "JUnit").failure();
        assertThat(f3.body()).containsEntry("code", "OTP_LOCKED").containsEntry("attempts_left", 0);
        assertThat(svc.verify(r[0], r[1], "10.0.0.1", "JUnit").failure().body()).containsEntry("code", "OTP_LOCKED");
        assertThat(repo.findById(r[0]).orElseThrow().getStatus()).isEqualTo("LOCKED");
        verify(users, never()).applyProgressiveLockout(anyString());
        verify(audit, never()).recordLogin(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(audit, times(2)).recordOtp(eq("LOGIN_OTP_VERIFY_FAILED"), eq("ALICE"), any(), any(), any(), eq("FAILURE"), any(), any(), any(), any());
        verify(audit).recordOtp(eq("LOGIN_OTP_LOCKED"), eq("ALICE"), any(), any(), any(), eq("BLOCKED"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("süre dolması: 45 sn sonra doğru kod OTP_EXPIRED; satır EXPIRED, denetim LOGIN_OTP_EXPIRED")
    void verify_expired() {
        String[] r = requestCode("alice", "push", "10.0.0.1");
        clock.plus(46);
        LoginOtpService.Result f = svc.verify(r[0], r[1], "10.0.0.1", "JUnit").failure();
        assertThat(f.status()).isEqualTo(401);
        assertThat(f.body()).containsEntry("code", "OTP_EXPIRED");
        assertThat(repo.findById(r[0]).orElseThrow().getStatus()).isEqualTo("EXPIRED");
        verify(audit).recordOtp(eq("LOGIN_OTP_EXPIRED"), eq("ALICE"), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TUZAK: bilinmeyen kullanıcının isteği gerçek yanlış kodla birebir aynı yanıtlar; isteğin GERÇEK kodu bile oturum açmaz")
    void verify_decoy_behavesLikeWrongCode() {
        doReturn("424242").when(codes).newCode();   // tuzak satırının (gönderilmeyen) kodu bilinsin
        String decoyId = (String) svc.request("ghost", "push", "10.0.0.1", "JUnit", false).body().get("challenge_id");
        String realId = (String) svc.request("alice", "push", "10.0.0.2", "JUnit", false).body().get("challenge_id");
        for (int i = 0; i < 3; i++) {
            LoginOtpService.Result d = svc.verify(decoyId, "424242", "10.0.0.1", "JUnit").failure();
            LoginOtpService.Result g = svc.verify(realId, "111111", "10.0.0.2", "JUnit").failure();
            assertThat(d).as("deneme %d", i + 1).isNotNull();
            assertThat(d.status()).isEqualTo(g.status());
            assertThat(d.body()).isEqualTo(g.body());
        }
        assertThat(svc.verify(decoyId, "424242", "10.0.0.1", "JUnit").failure().body()).containsEntry("code", "OTP_LOCKED");
    }

    @Test
    @DisplayName("kullanıcı başına başarısız doğrulama (15 dk'da 5): kodla giriş ASKIDA — yeni istek bastırılır, doğru kod OTP_LOCKED; şifre kilidi yok")
    void verify_userSuspension() {
        // iki istek × 3 yanlış = 6 başarısız doğrulama (≥ 5)
        for (int i = 0; i < 2; i++) {
            String[] r = requestCode("alice", "push", "10.0.0.1");
            String wrong = "999999".equals(r[1]) ? "888888" : "999999";
            for (int k = 0; k < 3; k++) svc.verify(r[0], wrong, "198.51.100.1", "JUnit");
            clock.plus(31);
        }
        // Askıdayken istek sessizce bastırılır (aynı 200 gövdesi)
        LoginOtpService.Result req = svc.request("alice", "push", "10.0.0.1", "JUnit", false);
        assertThat(req.status()).isEqualTo(200);
        assertThat(repo.findById((String) req.body().get("challenge_id")).orElseThrow().getDeliveryStatus()).isEqualTo("SUPPRESSED_SUSPENDED");
        // 15 dk geçince yeniden açılır
        clock.plus(16 * 60);
        String[] fresh = requestCode("alice", "push", "10.0.0.1");
        assertThat(svc.verify(fresh[0], fresh[1], "10.0.0.1", "JUnit").verified()).isNotNull();
        verify(users, never()).applyProgressiveLockout(anyString());
        verify(users, never()).recordFailedLogin(anyString(), any(), any());
    }

    @Test
    @DisplayName("askıdaki kullanıcının ÖNCEDEN alınmış geçerli kodu da OTP_LOCKED (başkası deneyerek askıya alabilir ama kilitleyemez)")
    void verify_suspendedBlocksEvenCorrectCode() {
        when(methods.ttlSeconds(LoginOtpService.Channel.EMAIL)).thenReturn(300);   // kod askı anında hâlâ süresi içinde
        String[] keep = requestCode("alice", "email", "10.0.0.1");
        clock.plus(31);
        String other = requestCode("alice", "push", "10.0.0.1")[0];
        for (int k = 0; k < 3; k++) svc.verify(other, "abcdef", "198.51.100.1", "JUnit");
        clock.plus(31);
        String other2 = requestCode("alice", "push", "10.0.0.1")[0];
        for (int k = 0; k < 2; k++) svc.verify(other2, "abcdef", "198.51.100.1", "JUnit");
        LoginOtpService.Result f = svc.verify(keep[0], keep[1], "10.0.0.1", "JUnit").failure();
        assertThat(f).isNotNull();
        assertThat(f.body()).containsEntry("code", "OTP_LOCKED");
    }

    @Test
    @DisplayName("bilinmeyen / bozuk challenge kimliği → OTP_EXPIRED (denetlenecek kişi yok); yöntem kapandıysa 400")
    void verify_unknownChallenge() {
        assertThat(svc.verify("not-a-uuid", "123456", "10.0.0.1", "JUnit").failure().body()).containsEntry("code", "OTP_EXPIRED");
        assertThat(svc.verify("11111111-2222-3333-4444-555555555555", "123456", "10.0.0.1", "JUnit").failure().body())
                .containsEntry("code", "OTP_EXPIRED");
        String[] r = requestCode("alice", "push", "10.0.0.1");
        when(methods.available(LoginOtpService.Channel.PUSH)).thenReturn(false);
        assertThat(svc.verify(r[0], r[1], "10.0.0.1", "JUnit").failure().status()).isEqualTo(400);
    }

    @Test
    @DisplayName("409 onayı: doğru kod tüketilmez, onay süresince (120 sn) canlı kalır; block() sonrası kod yeniden kullanılamaz")
    void holdForConfirmation_andBlock() {
        String[] r = requestCode("alice", "push", "10.0.0.1");
        LoginOtpService.Verified v = svc.verify(r[0], r[1], "10.0.0.1", "JUnit").verified();
        svc.holdForConfirmation(v.challenge());
        clock.plus(100);   // asıl süre (45 sn) geçti, onay penceresi içinde
        LoginOtpService.Verified again = svc.verify(r[0], r[1], "10.0.0.1", "JUnit").verified();
        assertThat(again).isNotNull();
        svc.block(again.challenge());
        assertThat(svc.verify(r[0], r[1], "10.0.0.1", "JUnit").failure().body()).containsEntry("code", "OTP_EXPIRED");
        assertThat(repo.findById(r[0]).orElseThrow().getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("kullanıcı istekten sonra silindi / ad başka kayda geçti → doğru kod da genel OTP_EXPIRED")
    void verify_userGone() {
        String[] r = requestCode("alice", "push", "10.0.0.1");
        when(users.findByUsername("ALICE")).thenReturn(Optional.of(user(99L, "ALICE", "USER", "x@example.com")));
        assertThat(svc.verify(r[0], r[1], "10.0.0.1", "JUnit").failure().body()).containsEntry("code", "OTP_EXPIRED");
    }

    @Test
    @DisplayName("denetim ayrıntıları ve nedenleri HİÇBİR durumda kodu içermez")
    void auditNeverContainsCode() {
        String[] r = requestCode("alice", "push", "10.0.0.1");
        svc.verify(r[0], "000000".equals(r[1]) ? "000001" : "000000", "10.0.0.1", "JUnit");
        clock.plus(50);
        svc.verify(r[0], r[1], "10.0.0.1", "JUnit");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).recordOtp(any(), any(), any(), any(), any(), any(), reason.capture(), detail.capture(), any(), any());
        List<String> all = new ArrayList<>(detail.getAllValues());
        reason.getAllValues().forEach(s -> all.add(s == null ? "" : s));
        assertThat(all).isNotEmpty().allSatisfy(s -> assertThat(String.valueOf(s)).doesNotContain(r[1]));
    }
}
