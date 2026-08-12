package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Giriş damgaları — kullanıcının kendi güvenlik özetini besleyen kaydırma (shift) mantığı.
 *
 * <p>Sözleşme: kullanıcıya gösterilen "önceki giriş" ASLA içinde bulunduğu oturum değildir;
 * giriş anında {@code last_* → prev_*} kaydırılır ve başarısız deneme sayacı sıfırlanmadan
 * önce anlık görüntüsü saklanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceLoginStampTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;

    private UserService service;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        ReflectionTestUtils.setField(service, "stampDedupeSeconds", 30L);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppUser user() {
        AppUser u = new AppUser();
        u.setUsername("TESTUSER");
        return u;
    }

    private void stored(AppUser u) {
        when(userRepo.findByUsername(anyString())).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("İlk giriş: önceki kayıt yok → firstLogin=true, last_* dolar")
    void firstLogin() {
        AppUser u = user();
        stored(u);

        UserService.LoginStamp s = service.recordSuccessfulLogin(
                "TESTUSER", "sid-1", "10.0.0.1", UserService.LoginMethod.PASSWORD);

        assertThat(s.firstLogin()).isTrue();
        assertThat(s.prevLoginAt()).isNull();
        assertThat(u.getLastLoginAt()).isNotNull();
        assertThat(u.getLastLoginIp()).isEqualTo("10.0.0.1");
        assertThat(u.getLastLoginMethod()).isEqualTo("PASSWORD");
        assertThat(u.getActiveSessionId()).isEqualTo("sid-1");
    }

    @Test
    @DisplayName("İkinci giriş: kullanıcıya gösterilen değer BİR ÖNCEKİ giriş olur (bu oturum değil)")
    void secondLoginShiftsPrevious() {
        AppUser u = user();
        u.setLastLoginAt("2026-08-10T09:00:00");
        u.setLastLoginIp("10.0.0.9");
        u.setLastLoginMethod("PASSWORD");
        stored(u);

        UserService.LoginStamp s = service.recordSuccessfulLogin(
                "TESTUSER", "sid-2", "10.0.0.2", UserService.LoginMethod.PASSWORD);

        // Gösterilen "önceki giriş" = kaydırmadan önceki last_login (dünkü giriş), bugünkü DEĞİL.
        assertThat(s.prevLoginAt()).isEqualTo("2026-08-10T09:00:00");
        assertThat(s.prevLoginIp()).isEqualTo("10.0.0.9");
        assertThat(s.firstLogin()).isFalse();
        assertThat(u.getPrevLoginAt()).isEqualTo("2026-08-10T09:00:00");
        assertThat(u.getLastLoginAt()).isNotEqualTo("2026-08-10T09:00:00");
        assertThat(u.getLastLoginIp()).isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("Sayaç: sıfırlanmadan ÖNCE anlık görüntü alınır — kullanıcı 'bu yana N deneme'yi görebilsin")
    void failedCounterSnapshotThenReset() {
        AppUser u = user();
        u.setLastLoginAt("2026-08-10T09:00:00");
        u.setFailedSinceLogin(3);
        stored(u);

        UserService.LoginStamp s = service.recordSuccessfulLogin(
                "TESTUSER", "sid-3", "10.0.0.3", UserService.LoginMethod.PASSWORD);

        assertThat(s.failedBeforeLogin()).isEqualTo(3);      // ekranda gösterilecek sayı
        assertThat(u.getFailedBeforeLogin()).isEqualTo(3);   // /me aynı değeri döndürsün
        assertThat(u.getFailedSinceLogin()).isZero();        // yeni pencere sıfırdan başlar
    }

    @Test
    @DisplayName("Oturum kaydı ve damga TEK save() ile yazılır (ikinci UPDATE açılmaz)")
    void singleSave() {
        AppUser u = user();
        stored(u);

        service.recordSuccessfulLogin("TESTUSER", "sid-4", "10.0.0.4", UserService.LoginMethod.PASSWORD);

        verify(userRepo, times(1)).save(any(AppUser.class));
    }

    @Test
    @DisplayName("İkizlenme kalkanı: dedupe penceresi içindeki ikinci giriş kaydırmayı ATLAR, oturumu yine tazeler")
    void dedupeWindowSkipsShift() {
        // Gerçek senaryo: oturum düşünce tarayıcı remember-me çereziyle aynı anda birkaç istek atar.
        AppUser u = user();
        u.setPrevLoginAt("2026-08-09T08:00:00");
        u.setLastLoginAt(ISO.format(Instant.now().minusSeconds(5)));   // 5 sn önce giriş yapılmış
        stored(u);

        UserService.LoginStamp s = service.recordSuccessfulLogin(
                "TESTUSER", "sid-5", "10.0.0.5", UserService.LoginMethod.REMEMBER_ME);

        // Kaydırma olsaydı prev "5 saniye önce"ye düşer ve kullanıcının göreceği değer yok olurdu.
        assertThat(u.getPrevLoginAt()).isEqualTo("2026-08-09T08:00:00");
        assertThat(s.prevLoginAt()).isEqualTo("2026-08-09T08:00:00");
        assertThat(u.getActiveSessionId()).isEqualTo("sid-5");   // oturum kaydı yine güncellenir
    }

    @Test
    @DisplayName("Dedupe penceresi DIŞINDA kaydırma yapılır")
    void outsideDedupeWindowShifts() {
        AppUser u = user();
        u.setPrevLoginAt("2026-08-09T08:00:00");
        u.setLastLoginAt(ISO.format(Instant.now().minusSeconds(600)));
        stored(u);

        service.recordSuccessfulLogin("TESTUSER", "sid-6", "10.0.0.6", UserService.LoginMethod.PASSWORD);

        assertThat(u.getPrevLoginAt()).isNotEqualTo("2026-08-09T08:00:00");
    }

    @Test
    @DisplayName("Kullanıcı yok / sessionId yok → yazma yok, boş damga")
    void missingUserIsSafe() {
        when(userRepo.findByUsername(anyString())).thenReturn(Optional.empty());

        UserService.LoginStamp s = service.recordSuccessfulLogin(
                "YOK", "sid-7", "10.0.0.7", UserService.LoginMethod.PASSWORD);

        assertThat(s.firstLogin()).isTrue();
        assertThat(s.prevLoginAt()).isNull();
        verify(userRepo, never()).save(any());

        assertThat(service.recordSuccessfulLogin("TESTUSER", null, null, null).prevLoginAt()).isNull();
    }

    @Test
    @DisplayName("Başarısız giriş: tek atomik UPDATE (oku-artır-kaydet DEĞİL — paralel denemede sayaç kaybolmasın)")
    void failedLoginBumpsAtomically() {
        service.recordFailedLogin("TESTUSER", "10.0.0.8", "BAD_PASSWORD");

        verify(userRepo, times(1))
                .bumpFailedLogin(eq("TESTUSER"), anyString(), eq("10.0.0.8"), eq("BAD_PASSWORD"));
        verify(userRepo, never()).save(any());
    }

    @Test
    @DisplayName("Boş kullanıcı adında başarısız damga yazılmaz (güncellenecek satır yok)")
    void failedLoginIgnoresBlankUser() {
        service.recordFailedLogin("", "10.0.0.9", "BAD_PASSWORD");
        service.recordFailedLogin(null, "10.0.0.9", "BAD_PASSWORD");

        verify(userRepo, never()).bumpFailedLogin(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("stampOf: /api/me kaydırma SONRASI değerleri okur — giriş yanıtıyla birebir aynı")
    void stampOfReadsCurrentRow() {
        AppUser u = user();
        u.setPrevLoginAt("2026-08-10T09:00:00");
        u.setPrevLoginIp("10.0.0.9");
        u.setFailedBeforeLogin(2);
        u.setLastFailedLoginAt("2026-08-11T10:00:00");

        UserService.LoginStamp s = UserService.stampOf(u);

        assertThat(s.prevLoginAt()).isEqualTo("2026-08-10T09:00:00");
        assertThat(s.prevLoginIp()).isEqualTo("10.0.0.9");
        assertThat(s.failedBeforeLogin()).isEqualTo(2);
        assertThat(s.lastFailedAt()).isEqualTo("2026-08-11T10:00:00");
        assertThat(s.firstLogin()).isFalse();
        assertThat(UserService.stampOf(null).firstLogin()).isTrue();
    }
}
