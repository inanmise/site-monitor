package com.sitemonitor.service;

import com.sitemonitor.repository.HttpMetricMinuteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HttpMetricsQueryService#topEndpoints()} — hata da önbelleğe alınır, bekleyenler kilitte birikmez
 * (2026-09-28c, ek-3). Sistem Sağlığı 30 sn'de bir yoklanır ve 100 eşzamanlı kullanıcı olabilir: yavaş / düşen DB'de
 * her isteğin taramayı sırayla yeniden denemesi Tomcat iş parçacıklarını kilitte biriktiriyordu (tek pod).
 */
class HttpMetricsTopCacheTest {

    private static final Instant NOW = Instant.parse("2026-06-18T12:00:30Z");

    private HttpMetricMinuteRepository repo;
    private HttpMetricsQueryService service;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

    @BeforeEach
    void setUp() {
        repo = mock(HttpMetricMinuteRepository.class);
        // TransactionTemplate'in yöneticisi taklit: getTransaction null döner, geri çağrı yine koşar (commit/rollback no-op).
        service = new HttpMetricsQueryService(repo, mock(PlatformTransactionManager.class));
        service.setClock(new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        });
    }

    @Test
    @DisplayName("istisna sonrası ikinci çağrı taramayı YENİDEN KOŞMAZ (hata kısa TTL ile önbellekte); TTL dolunca yeniden dener")
    void failureIsCachedBriefly() {
        when(repo.streamRange(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> service.topEndpoints()).isInstanceOf(DataAccessResourceFailureException.class);

        now.set(NOW.plusSeconds(5));
        assertThat(service.topEndpoints()).isNull();          // önbellekteki hata: tarama yok, bölüm listeleri gizlenir
        verify(repo, times(1)).streamRange(anyString(), anyString());

        now.set(NOW.plusMillis(HttpMetricsQueryService.TOP_FAIL_TTL_MS + 1));
        assertThatThrownBy(() -> service.topEndpoints()).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repo, times(2)).streamRange(anyString(), anyString());
    }

    @Test
    @DisplayName("tazeleme hata verirse son iyi sonuç korunur (bayat ama var) — hata TTL'i boyunca o döner")
    void failureKeepsLastGoodValue() {
        when(repo.streamRange(anyString(), anyString())).thenReturn(Stream.empty());
        Map<String, Object> first = service.topEndpoints();
        assertThat(first).containsEntry("endpoint_count", 0);

        when(repo.streamRange(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        now.set(NOW.plusMillis(HttpMetricsQueryService.TOP_TTL_MS + 1));
        assertThatThrownBy(() -> service.topEndpoints()).isInstanceOf(DataAccessResourceFailureException.class);
        now.set(NOW.plusMillis(HttpMetricsQueryService.TOP_TTL_MS + 2));
        assertThat(service.topEndpoints()).isSameAs(first);
        verify(repo, times(2)).streamRange(anyString(), anyString());
    }

    @Test
    @DisplayName("hesap sürerken bayat sonuç varsa beklenmez: ikinci iş parçacığı kilidi beklemeden bayat değeri alır")
    void staleValueServedWhileRecomputing() throws Exception {
        when(repo.streamRange(anyString(), anyString())).thenReturn(Stream.empty());
        Map<String, Object> first = service.topEndpoints();

        CountDownLatch inScan = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repo.streamRange(anyString(), anyString())).thenAnswer(inv -> {
            inScan.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Stream.empty();
        });
        now.set(NOW.plusMillis(HttpMetricsQueryService.TOP_TTL_MS + 1));
        Thread slow = new Thread(service::topEndpoints);
        slow.start();
        assertThat(inScan.await(5, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        Map<String, Object> during = service.topEndpoints();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        release.countDown();
        slow.join(5_000);

        assertThat(during).isSameAs(first);
        assertThat(waitedMs).isLessThan(1_000);
    }
}
