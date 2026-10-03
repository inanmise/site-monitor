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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kodla giriş — KİŞİ BİLGİSİ doğrulaması (2026-10-03, kullanıcı isteği: "push ile loginde telefon no, mail ile loginde
 * mail adresi de girilsin; kullanıcı adıyla eşleşirse kod gönderilsin"). GERÇEK tablo ve sorgularla (H2), ortam
 * transaction'ı olmadan ({@code NOT_SUPPORTED}).
 *
 * <p>Kapsam: eşleşen telefon (her biçim) / e-posta (kırpma, büyük-küçük harf) → kod gider; eşleşmeme, kayıtlı bilgi yok,
 * bilinmeyen kullanıcı → AYNI 200 (durum + anahtarlar + challenge_id dışındaki değerler) + tuzak satırı; zorunlu ama boş →
 * 400 alan kodu (satır yok); kullanıcı başına eşleşmeme sınırı → sessiz CONTACT_LOCK (şifre kilidine dokunmaz, pencere
 * dolunca açılır); kısa devre yok (her istekte aynı sorgular); anahtar KAPALIYKEN birebir önceki davranış.
 * Örnek telefonlar yer tutucudur (0500 000 00 00 ailesi), adresler example.com.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginOtpContactVerificationTest {

    static final String PHONE = "+90 500 000 00 00";

    @Autowired LoginOtpChallengeRepository realRepo;

    LoginOtpChallengeRepository repo;   // gerçek depoya yönlendiren gözlemci (sorgu sayımı için)
    LoginMethodsService methods;
    UserService users;
    AuditService audit;
    LoginOtpDeliveryService delivery;
    OtpCodes codes;
    LoginOtpService svc;
    LoginOtpServiceFlowTest.MutableClock clock;
    AppUser alice;

    @BeforeEach
    void setUp() {
        realRepo.deleteAll();
        repo = mock(LoginOtpChallengeRepository.class, delegatesTo(realRepo));
        methods = mock(LoginMethodsService.class);
        users = mock(UserService.class);
        audit = mock(AuditService.class);
        delivery = mock(LoginOtpDeliveryService.class);
        codes = spy(new OtpCodes(null));
        clock = new LoginOtpServiceFlowTest.MutableClock();
        lenient().when(methods.available(any())).thenReturn(true);
        lenient().when(methods.ttlSeconds(any())).thenReturn(45);
        lenient().when(methods.maxAttempts()).thenReturn(3);
        lenient().when(methods.resendCooldownSeconds()).thenReturn(30);
        lenient().when(methods.maxRequestsPerUser()).thenReturn(5);
        lenient().when(methods.maxRequestsPerIp()).thenReturn(20);
        lenient().when(methods.maxFailedVerifications()).thenReturn(5);
        lenient().when(methods.requiresContact(any())).thenReturn(true);
        lenient().when(methods.maxContactMismatches()).thenReturn(5);
        lenient().when(users.checkLockout(anyString())).thenReturn(new UserService.LockoutStatus(false, 0));
        lenient().when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        alice = LoginOtpServiceFlowTest.user(7L, "ALICE", "USER", "alice@example.com");
        alice.setPhone(PHONE);
        lenient().when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        lenient().when(users.findByUsername("ALICE")).thenReturn(Optional.of(alice));
        svc = new LoginOtpService(repo, methods, users, audit, codes, delivery);
        svc.setClock(clock);
    }

    private LoginOtpService.Result push(String user, String phone, String ip) {
        return svc.request(user, "push", phone, null, ip, "JUnit", false);
    }

    private LoginOtpService.Result email(String user, String mail, String ip) {
        return svc.request(user, "email", null, mail, ip, "JUnit", false);
    }

    private String status(LoginOtpService.Result r) {
        return realRepo.findById((String) r.body().get("challenge_id")).orElseThrow().getDeliveryStatus();
    }

    /** challenge_id dışındaki gövde — tuzak ile gerçek istek bu haritada BİREBİR aynı olmalı. */
    private static Map<String, Object> withoutId(LoginOtpService.Result r) {
        Map<String, Object> m = new LinkedHashMap<>(r.body());
        assertThat((String) m.remove("challenge_id")).matches("[0-9a-f-]{36}");
        return m;
    }

    @Test
    @DisplayName("telefon: kayıtlı numara HER biçimde eşleşir → kod gider; eşleşmeyen numara → tuzak, yanıt durumu + gövdesi birebir aynı")
    void phone_matchAndMismatch_identicalResponses() {
        LoginOtpService.Result real = push("alice", "0500 000 00 00", "10.0.0.1");
        assertThat(status(real)).isEqualTo("QUEUED");
        clock.plus(31);
        assertThat(status(push("alice", "+90 (500) 000-00-00", "10.0.0.2"))).isEqualTo("QUEUED");
        clock.plus(31);
        assertThat(status(push("alice", "5000000000", "10.0.0.3"))).isEqualTo("QUEUED");
        clock.plus(31);
        LoginOtpService.Result decoy = push("alice", "0500 000 00 01", "10.0.0.4");
        assertThat(decoy.status()).isEqualTo(real.status()).isEqualTo(200);
        assertThat(decoy.body().keySet()).containsExactlyElementsOf(real.body().keySet());
        assertThat(withoutId(decoy)).isEqualTo(withoutId(real));
        LoginOtpChallenge d = realRepo.findById((String) decoy.body().get("challenge_id")).orElseThrow();
        assertThat(d.getDeliveryStatus()).isEqualTo("SUPPRESSED_CONTACT_MISMATCH");
        assertThat(d.getUserId()).isNull();
        assertThat(d.decoy()).isTrue();
        verify(delivery, times(3)).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("e-posta: kırpma + büyük/küçük harf duyarsız → kod gider; başka adres → tuzak; yanıtlar birebir")
    void email_matchAndMismatch() {
        LoginOtpService.Result real = email("alice", "  ALICE@Example.COM ", "10.0.0.1");
        assertThat(status(real)).isEqualTo("QUEUED");
        LoginOtpService.Result decoy = email("alice", "bob@example.com", "10.0.0.1");
        assertThat(status(decoy)).isEqualTo("SUPPRESSED_CONTACT_MISMATCH");
        assertThat(decoy.status()).isEqualTo(real.status());
        assertThat(withoutId(decoy)).isEqualTo(withoutId(real));
        verify(delivery, times(1)).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("kayıtlı telefonu olmayan / kısa numaralı kullanıcı → NO_PHONE; bilinmeyen kullanıcı → UNKNOWN_USER; e-postasız → NO_TARGET — hepsi aynı 200")
    void noRegisteredContact_andUnknownUser_areDecoys() {
        AppUser noPhone = LoginOtpServiceFlowTest.user(8L, "NOPHONE", "USER", "np@example.com");
        AppUser shortPhone = LoginOtpServiceFlowTest.user(9L, "SHORTPH", "USER", "sp@example.com");
        shortPhone.setPhone("1234");
        AppUser noMail = LoginOtpServiceFlowTest.user(10L, "NOMAIL", "USER", null);
        for (AppUser u : List.of(noPhone, shortPhone, noMail)) {
            when(users.findByUsername(u.getUsername())).thenReturn(Optional.of(u));
        }
        LoginOtpService.Result ref = push("alice", "0500 000 00 00", "10.0.0.9");
        Map<String, Object> refBody = withoutId(ref);
        Map<String, LoginOtpService.Result> results = new LinkedHashMap<>();
        results.put("SUPPRESSED_NO_PHONE#1", push("NOPHONE", "0500 000 00 00", "10.0.0.1"));
        results.put("SUPPRESSED_NO_PHONE#2", push("SHORTPH", "0500 000 00 00", "10.0.0.1"));
        results.put("SUPPRESSED_UNKNOWN_USER", push("ghost", "0500 000 00 00", "10.0.0.1"));
        results.put("SUPPRESSED_NO_TARGET", email("NOMAIL", "nm@example.com", "10.0.0.1"));
        for (Map.Entry<String, LoginOtpService.Result> e : results.entrySet()) {
            LoginOtpService.Result r = e.getValue();
            assertThat(r.status()).as(e.getKey()).isEqualTo(200);
            assertThat(status(r)).as(e.getKey()).isEqualTo(e.getKey().replaceAll("#\\d$", ""));
            Map<String, Object> body = withoutId(r);
            if ("push".equals(body.get("channel"))) assertThat(body).as(e.getKey()).isEqualTo(refBody);
        }
        verify(delivery, times(1)).dispatch(any(), anyString());   // yalnız alice
    }

    @Test
    @DisplayName("zorunlu ama boş → 400 PHONE_REQUIRED / EMAIL_REQUIRED + field; satır yazılmaz (kota tüketmez); kişi bilgisi yoksa da aynı")
    void requiredButBlank_400() {
        LoginOtpService.Result p = push("alice", "   ", "10.0.0.1");
        assertThat(p.status()).isEqualTo(400);
        assertThat(p.body()).containsEntry("error_code", "PHONE_REQUIRED").containsEntry("code", "PHONE_REQUIRED")
                .containsEntry("field", "phone").containsEntry("success", false);
        LoginOtpService.Result e = email("ghost", null, "10.0.0.1");
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.body()).containsEntry("error_code", "EMAIL_REQUIRED").containsEntry("field", "email");
        // bilinmeyen kullanıcı da AYNI 400'ü alır (yalnız yapılandırma — hesap bilgisi değil)
        assertThat(push("ghost", "", "10.0.0.1").body()).isEqualTo(p.body());
        // Push kanalına e-posta, e-posta kanalına telefon gelmesi işe yaramaz
        assertThat(svc.request("alice", "push", null, "alice@example.com", "10.0.0.1", "JUnit", false).status()).isEqualTo(400);
        assertThat(realRepo.count()).isZero();
        verify(delivery, never()).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("eşleşmeme sınırı (15 dk'da 5): 6. istek DOĞRU telefonla bile sessizce bastırılır (CONTACT_LOCK, aynı 200); kilitliyken istek sayılmaz; pencere dolunca açılır; şifre kilidi yok")
    void mismatchLimit_silentLock() {
        LoginOtpService.Result ok = push("alice", "0500 000 00 00", "10.0.0.50");
        for (int i = 0; i < 5; i++) {
            clock.plus(10);
            assertThat(status(push("alice", "0500 000 00 0" + (i + 1), "10.0.1." + i))).isEqualTo("SUPPRESSED_CONTACT_MISMATCH");
        }
        clock.plus(40);
        LoginOtpService.Result locked = push("alice", "0500 000 00 00", "10.0.2.1");
        assertThat(locked.status()).isEqualTo(200);
        assertThat(withoutId(locked)).isEqualTo(withoutId(ok));
        assertThat(status(locked)).isEqualTo("SUPPRESSED_CONTACT_LOCK");
        // e-posta kanalı da aynı kullanıcı sayacını paylaşır
        assertThat(status(email("alice", "alice@example.com", "10.0.2.2"))).isEqualTo("SUPPRESSED_CONTACT_LOCK");
        // kilitliyken yapılan eşleşmeyen istekler sayaca EKLENMEZ (kilit sonsuza uzamaz)
        assertThat(status(push("alice", "0500 000 00 09", "10.0.2.3"))).isEqualTo("SUPPRESSED_CONTACT_LOCK");
        assertThat(realRepo.findAll().stream().filter(c -> "SUPPRESSED_CONTACT_MISMATCH".equals(c.getDeliveryStatus())).count())
                .isEqualTo(5);
        verify(delivery, times(1)).dispatch(any(), anyString());
        // ilk eşleşmeyen denemenin üstünden 15 dk geçince yeniden açılır
        clock.plus(15 * 60);
        assertThat(status(push("alice", "0500 000 00 00", "10.0.3.1"))).isEqualTo("QUEUED");
        verify(delivery, times(2)).dispatch(any(), anyString());
        // şifre / LDAP girişi ve ilerleyici hesap kilidi ETKİLENMEZ
        verify(users, never()).applyProgressiveLockout(anyString());
        verify(users, never()).recordFailedLogin(anyString(), any(), any());
    }

    @Test
    @DisplayName("sınır kullanıcı başına: alice'in kilidi bob'u etkilemez; bilinmeyen adla eşleşmeme sayılmaz (sayılacak hesap yok)")
    void mismatchLimit_perUser() {
        AppUser bob = LoginOtpServiceFlowTest.user(11L, "BOB", "USER", "bob@example.com");
        bob.setPhone("0500 000 00 99");
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        for (int i = 0; i < 5; i++) push("alice", "0500 000 00 0" + (i + 1), "10.0.4." + i);
        assertThat(status(push("alice", "0500 000 00 00", "10.0.4.9"))).isEqualTo("SUPPRESSED_CONTACT_LOCK");
        assertThat(status(push("bob", "0500 000 00 99", "10.0.4.9"))).isEqualTo("QUEUED");
        for (int i = 0; i < 6; i++) assertThat(status(push("ghost", "0500 000 00 00", "10.0.5." + i))).isEqualTo("SUPPRESSED_UNKNOWN_USER");
    }

    @Test
    @DisplayName("kısa devre yok: eşleşen, eşleşmeyen ve bilinmeyen kullanıcı isteği AYNI sorguları koşar (sayaç + kilit + sınırlar)")
    void noShortCircuit_sameQueries() {
        push("alice", "0500 000 00 00", "10.0.0.1");
        push("alice", "0500 000 00 01", "10.0.0.2");
        push("ghost", "0500 000 00 00", "10.0.0.3");
        verify(repo, times(3)).countByUsernameAndDeliveryStatusAndCreatedAtGreaterThanEqual(anyString(),
                eq("SUPPRESSED_CONTACT_MISMATCH"), anyString());
        verify(repo, times(3)).sumFailuresSince(anyString(), anyString());
        verify(repo, times(3)).countByUsernameAndUserIdIsNotNullAndCreatedAtGreaterThanEqual(anyString(), anyString());
        verify(repo, times(3)).findTopByUsernameAndChannelAndUserIdIsNotNullOrderByCreatedAtDesc(anyString(), anyString());
        verify(users, times(2)).checkLockout("ALICE");
        verify(users).checkLockout("GHOST");
    }

    @Test
    @DisplayName("denetim: yalnız SONUÇ (contact=MATCHED / MISMATCH / MISSING / LOCKED / NOT_CHECKED) — girilen değer hiçbir argümanda yok")
    void audit_outcomeOnly() {
        AppUser passive = LoginOtpServiceFlowTest.user(12L, "PASSIVE", "USER", "p@example.com");
        passive.setActive(false);
        passive.setPhone(PHONE);
        when(users.findByUsername("PASSIVE")).thenReturn(Optional.of(passive));
        push("alice", "0 (500) 000-00-00", "10.0.0.1");
        push("alice", "0500 999 99 99", "10.0.0.1");
        push("PASSIVE", "0500 888 88 88", "10.0.0.1");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, times(3)).recordOtp(eq("LOGIN_OTP_REQUESTED"), any(), any(), any(), any(), any(), reason.capture(),
                detail.capture(), any(), any());
        assertThat(detail.getAllValues().get(0)).contains("\"contact\":\"MATCHED\"").contains("\"result\":\"SENT\"");
        assertThat(detail.getAllValues().get(1)).contains("\"contact\":\"MISMATCH\"").contains("\"reason\":\"CONTACT_MISMATCH\"");
        assertThat(detail.getAllValues().get(2)).contains("\"contact\":\"NOT_CHECKED\"").contains("\"reason\":\"INACTIVE\"");
        assertThat(reason.getAllValues().get(1)).isEqualTo("SUPPRESSED: CONTACT_MISMATCH");
        for (String s : detail.getAllValues()) {
            assertThat(s).doesNotContain("(500)", "999 99", "9999999", "888 88", "8888888", "5000000000");
        }
    }

    @Test
    @DisplayName("tuzak (eşleşmeyen telefon) isteğinin doğrulaması gerçek yanlış kodla birebir; isteğin kendi kodu bile oturum açmaz")
    void mismatchDecoy_verifyLikeWrongCode() {
        doReturn("424242").when(codes).newCode();
        String decoyId = (String) push("alice", "0500 000 00 01", "10.0.0.1").body().get("challenge_id");
        clock.plus(31);
        String realId = (String) push("alice", "0500 000 00 00", "10.0.0.2").body().get("challenge_id");
        for (int i = 0; i < 3; i++) {
            LoginOtpService.Result d = svc.verify(decoyId, "424242", "10.0.0.1", "JUnit").failure();
            LoginOtpService.Result g = svc.verify(realId, "111111", "10.0.0.2", "JUnit").failure();
            assertThat(d).isNotNull();
            assertThat(d.status()).isEqualTo(g.status());
            assertThat(d.body()).isEqualTo(g.body());
        }
    }

    @Test
    @DisplayName("REGRESYON — anahtar KAPALI: telefon / e-posta sorulmaz, gelse de yok sayılır; sayaç sorgusu KOŞMAZ; denetimde contact yok (önceki davranış)")
    void requireOff_behavesExactlyAsBefore() {
        when(methods.requiresContact(any())).thenReturn(false);
        AppUser noPhone = LoginOtpServiceFlowTest.user(8L, "NOPHONE", "USER", "np@example.com");
        when(users.findByUsername("NOPHONE")).thenReturn(Optional.of(noPhone));
        assertThat(status(svc.request("alice", "push", "10.0.0.1", "JUnit", false))).isEqualTo("QUEUED");
        assertThat(status(svc.request("NOPHONE", "push", "0500 000 00 77", null, "10.0.0.1", "JUnit", false))).isEqualTo("QUEUED");
        assertThat(status(svc.request("alice", "email", null, "", "10.0.0.1", "JUnit", false))).isEqualTo("QUEUED");
        clock.plus(31);
        assertThat(status(svc.request("alice", "email", null, "someone.else@example.com", "10.0.0.1", "JUnit", false)))
                .isEqualTo("QUEUED");
        verify(delivery, times(4)).dispatch(any(), anyString());
        verify(repo, never()).countByUsernameAndDeliveryStatusAndCreatedAtGreaterThanEqual(anyString(), anyString(), anyString());
        verify(methods, never()).maxContactMismatches();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit, times(4)).recordOtp(eq("LOGIN_OTP_REQUESTED"), any(), any(), any(), any(), eq("SUCCESS"), any(),
                detail.capture(), any(), any());
        assertThat(detail.getAllValues()).allSatisfy(s -> assertThat(s).doesNotContain("contact"));
    }

    @Test
    @DisplayName("anahtar kanal başına: push açık / e-posta kapalı → push telefonsuz 400, e-posta adressiz gider")
    void requirePerChannel() {
        when(methods.requiresContact(LoginOtpService.Channel.EMAIL)).thenReturn(false);
        assertThat(push("alice", null, "10.0.0.1").status()).isEqualTo(400);
        assertThat(status(email("alice", null, "10.0.0.1"))).isEqualTo("QUEUED");
    }
}
