package com.sitemonitor.service.otp;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.repository.LoginOtpChallengeRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.loginpolicy.UsernamePrefixPolicy;
import com.sitemonitor.service.loginpolicy.UsernamePrefixRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kodla giriş + kullanıcı adı ön eki (2026-10-21). Kurala uymayan ad UYGUN DEĞİLDİR ve kullanıcı adı numaralandırması
 * kuralı DEĞİŞMEZ: istek uygun kullanıcıyla AYNI 200 gövdesini alır, kod GİTMEZ, satır tuzaktır
 * ({@code SUPPRESSED_USERNAME_PREFIX}, kullanıcı kimliği yok); tuzağın doğrulaması gerçek yanlış kodla birebir aynıdır.
 * Gerçek tablo ve sorgularla (H2), ortam transaction'ı olmadan.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginOtpUsernamePrefixTest {

    @Autowired LoginOtpChallengeRepository repo;

    LoginMethodsService methods;
    UserService users;
    AuditService audit;
    LoginOtpDeliveryService delivery;
    OtpCodes codes;
    UsernamePrefixPolicy policy;
    LoginOtpService svc;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        methods = mock(LoginMethodsService.class);
        users = mock(UserService.class);
        audit = mock(AuditService.class);
        delivery = mock(LoginOtpDeliveryService.class);
        policy = mock(UsernamePrefixPolicy.class);
        codes = spy(new OtpCodes(null));
        lenient().when(methods.available(any())).thenReturn(true);
        lenient().when(methods.ttlSeconds(any())).thenReturn(45);
        lenient().when(methods.maxAttempts()).thenReturn(3);
        lenient().when(methods.resendCooldownSeconds()).thenReturn(30);
        lenient().when(methods.maxRequestsPerUser()).thenReturn(5);
        lenient().when(methods.maxRequestsPerIp()).thenReturn(20);
        lenient().when(methods.maxFailedVerifications()).thenReturn(5);
        lenient().when(users.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        lenient().when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        AppUser n = user(7L, "N123", "n123@example.com");
        AppUser mp = user(8L, "MP123", "mp123@example.com");
        lenient().when(users.findByUsername("n123")).thenReturn(Optional.of(n));
        lenient().when(users.findByUsername("mp123")).thenReturn(Optional.of(mp));
        UsernamePrefixRule rule = UsernamePrefixRule.of(true, "N", "", "admin");
        lenient().when(policy.allows(any())).thenAnswer(inv -> rule.allows(inv.getArgument(0)));
        svc = new LoginOtpService(repo, methods, users, audit, codes, delivery);
        svc.setClock(Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC));
        svc.setUsernamePrefix(policy);
    }

    static AppUser user(Long id, String name, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(name);
        u.setSystemRole("USER");
        u.setEmail(email);
        u.setActive(true);
        return u;
    }

    @Test
    @DisplayName("MP… adı: uygun N… kullanıcısıyla AYNI 200 gövdesi; kod gitmez; tuzak satırı SUPPRESSED_USERNAME_PREFIX, kimlik yok")
    void prefixDeniedIsDecoy() {
        LoginOtpService.Result real = svc.request("n123", "email", "10.0.0.1", "JUnit", false);
        LoginOtpService.Result decoy = svc.request("mp123", "email", "10.0.0.2", "JUnit", false);
        assertThat(decoy.status()).isEqualTo(real.status()).isEqualTo(200);
        assertThat(decoy.body().keySet()).containsExactlyElementsOf(real.body().keySet());
        Map<String, Object> a = new LinkedHashMap<>(real.body());
        Map<String, Object> b = new LinkedHashMap<>(decoy.body());
        a.remove("challenge_id");
        b.remove("challenge_id");
        assertThat(b).isEqualTo(a);
        verify(delivery, times(1)).dispatch(any(), anyString());   // yalnız N kullanıcısı
        LoginOtpChallenge d = repo.findById((String) decoy.body().get("challenge_id")).orElseThrow();
        assertThat(d.getUserId()).isNull();
        assertThat(d.getDeliveryStatus()).isEqualTo("SUPPRESSED_USERNAME_PREFIX");
        assertThat(d.getUsername()).isEqualTo("MP123");
        // kısa devre yok: kilit sorgusu MP için de koştu
        verify(users).checkLockout("MP123");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordOtp(eq("LOGIN_OTP_REQUESTED"), eq("MP123"), eq(8L), any(), any(), eq("BLOCKED"),
                eq("SUPPRESSED: USERNAME_PREFIX"), detail.capture(), eq("10.0.0.2"), eq("JUnit"));
        assertThat(detail.getValue()).contains("\"reason\":\"USERNAME_PREFIX\"");
    }

    @Test
    @DisplayName("bilinmeyen MP adı da aynı tuzak (ön ek kararı adın varlığına bakmaz)")
    void unknownMpIsDecoyToo() {
        LoginOtpService.Result decoy = svc.request("mp999", "push", "10.0.0.3", "JUnit", false);
        assertThat(decoy.status()).isEqualTo(200);
        LoginOtpChallenge d = repo.findById((String) decoy.body().get("challenge_id")).orElseThrow();
        assertThat(d.getDeliveryStatus()).isEqualTo("SUPPRESSED_USERNAME_PREFIX");
        verify(delivery, times(0)).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("tuzağın doğrulaması gerçek yanlış kodla birebir; tuzağın kendi kodu bile oturum açmaz")
    void decoyVerifyLikeWrongCode() {
        doReturn("424242").when(codes).newCode();
        String decoyId = (String) svc.request("mp123", "push", "10.0.0.1", "JUnit", false).body().get("challenge_id");
        String realId = (String) svc.request("n123", "push", "10.0.0.2", "JUnit", false).body().get("challenge_id");
        LoginOtpService.Result d = svc.verify(decoyId, "424242", "10.0.0.1", "JUnit").failure();
        LoginOtpService.Result g = svc.verify(realId, "111111", "10.0.0.2", "JUnit").failure();
        assertThat(d).isNotNull();
        assertThat(d.status()).isEqualTo(g.status());
        assertThat(d.body()).isEqualTo(g.body());
    }

    @Test
    @DisplayName("kural KAPALI / politika yok → MP kullanıcısına kod gider (bugünkü davranış)")
    void ruleOffSendsCode() {
        when(policy.allows(any())).thenReturn(true);
        svc.request("mp123", "email", "10.0.0.1", "JUnit", false);
        verify(delivery, times(1)).dispatch(any(), anyString());
    }
}
