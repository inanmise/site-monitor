package com.sitemonitor.service.otp;

import com.sitemonitor.repository.LoginOtpChallengeRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kodla giriş teslimi kapanışı (2026-10-21, çok pod R1 / MP-07): eskiden {@code shutdownNow()} kuyruktaki kodları SESSİZCE
 * düşürüyordu (satır {@code QUEUED} kalıyor, denetim izi yok). Artık süren işler beklenir; sürede başlayamayan her iş
 * {@code FAILED: SHUTDOWN} olur ve {@code LOGIN_OTP_DELIVERY_FAILED} denetimi düşer — kod hiçbir yere yazılmaz.
 */
class LoginOtpDeliveryShutdownTest {

    static LoginOtpDeliveryService.Job job(String id) {
        return new LoginOtpDeliveryService.Job(id, LoginOtpService.Channel.PUSH, "ali", 1L, 2L, "USER", "Ali", "ali@x",
                45, Instant.parse("2026-10-21T06:00:00Z"), "10.0.0.1", "ua", false);
    }

    /** İki iş parçacığını {@code release} açılana dek tutan push sahtesi. */
    static UserPushService blockingPush(CountDownLatch started, CountDownLatch release) {
        UserPushService push = mock(UserPushService.class);
        when(push.sendDirect(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new UserPushService.DirectResult(true, 200, null);
        });
        return push;
    }

    @Test
    @DisplayName("kapanış süresi içinde: süren iki teslim biter, kuyruktaki üç kod da GÖNDERİLİR (sessiz düşme yok)")
    void gracefulShutdownDeliversQueuedCodes() throws Exception {
        LoginOtpChallengeRepository repo = mock(LoginOtpChallengeRepository.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        UserPushService push = blockingPush(started, release);
        LoginOtpDeliveryService delivery = new LoginOtpDeliveryService(repo, push, mock(EmailNotificationService.class),
                mock(AuditService.class));
        for (int i = 0; i < 5; i++) delivery.dispatch(job("challenge-" + i + "-xxxxxxxx"), "123456");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread opener = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignore) { }
            release.countDown();
        });
        opener.start();

        assertThat(delivery.shutdown(5_000)).isZero();

        verify(push, times(5)).sendDirect(anyString(), anyString(), anyString());
        verify(repo, times(5)).setDeliveryStatus(anyString(), eq("SENT"));
        opener.join(1_000);
    }

    @Test
    @DisplayName("kapanış süresi doldu: kuyrukta başlayamayan üç kod FAILED: SHUTDOWN + LOGIN_OTP_DELIVERY_FAILED denetimi; kod iz bırakmaz")
    void timedOutShutdownFailsQueuedCodes() throws Exception {
        LoginOtpChallengeRepository repo = mock(LoginOtpChallengeRepository.class);
        AuditService audit = mock(AuditService.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        UserPushService push = blockingPush(started, release);
        LoginOtpDeliveryService delivery = new LoginOtpDeliveryService(repo, push, mock(EmailNotificationService.class), audit);
        try {
            for (int i = 0; i < 5; i++) delivery.dispatch(job("challenge-" + i + "-xxxxxxxx"), "123456");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(delivery.shutdown(100)).isEqualTo(3);

            verify(repo, times(3)).setDeliveryStatus(anyString(), eq("FAILED: SHUTDOWN"));
            verify(audit, times(3)).recordOtp(eq("LOGIN_OTP_DELIVERY_FAILED"), eq("ali"), eq(1L), eq(2L), eq("USER"),
                    eq("FAILURE"), startsWith("DELIVERY_FAILED: SHUTDOWN"), any(), eq("10.0.0.1"), eq("ua"));
            verify(repo, never()).setDeliveryStatus(anyString(), org.mockito.ArgumentMatchers.contains("123456"));
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("boş kuyrukta kapanış hemen döner, hiçbir satıra dokunmaz")
    void emptyShutdownIsNoop() {
        LoginOtpChallengeRepository repo = mock(LoginOtpChallengeRepository.class);
        LoginOtpDeliveryService delivery = new LoginOtpDeliveryService(repo, mock(UserPushService.class),
                mock(EmailNotificationService.class), mock(AuditService.class));
        assertThat(delivery.shutdown(1_000)).isZero();
        verify(repo, never()).setDeliveryStatus(anyString(), anyString());
    }
}
