package com.sitemonitor.service;

import com.sitemonitor.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E2 (2026-09-28): denetim geo/PTR zenginleştirmesi. Ters-DNS işletim sistemi çözücüsünde zaman aşımsız
 * asılabiliyordu ve (sınıf-içi @Async yüzünden) giriş iş parçacığında koşuyordu. Burada tavan pinlenir.
 */
class AuditGeoEnricherTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final GeoIpService geo = mock(GeoIpService.class);
    private AuditGeoEnricher enricher;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        enricher = new AuditGeoEnricher(repo, geo);
        when(geo.isPrivateIp(any())).thenReturn(false);
        when(geo.lookup(any())).thenReturn(new GeoIpService.GeoInfo("TR", "Istanbul", "Org"));
    }

    @AfterEach
    void tearDown() {
        release.countDown();   // asılı sahte sorgular serbest kalsın
        enricher.shutdown();
    }

    private void usePtrLookup(UnaryOperator<String> lookup) {
        ReflectionTestUtils.setField(enricher, "ptrLookup", lookup);
    }

    @Test
    @DisplayName("Asılı ters-DNS sorgusu tavanla kesilir → host null, çağıran en fazla ~REVERSE_DNS_TIMEOUT_MS bekler")
    void reverseDns_hangingLookup_isBoundedByTimeout() {
        usePtrLookup(ip -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "late.example.com";
        });

        long t0 = System.nanoTime();
        String host = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> enricher.reverseDns("203.0.113.7"));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(host).isNull();
        assertThat(waitedMs).isGreaterThanOrEqualTo(AuditGeoEnricher.REVERSE_DNS_TIMEOUT_MS - 50);
    }

    @Test
    @DisplayName("Asılı ters-DNS olsa da enrichGeoAsync geo'yu YAZAR (host boş) — satır zenginleştirmesiz kalmaz")
    void enrich_hangingPtr_stillWritesGeo() {
        usePtrLookup(ip -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "late.example.com";
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> enricher.enrichGeoAsync(5L, "203.0.113.7"));

        verify(repo).updateGeo(5L, "TR", "Istanbul", "Org", null);
    }

    @Test
    @DisplayName("Çözülen PTR host olarak yazılır; ad IP'nin kendisiyse (ileri doğrulama düştü) null")
    void reverseDns_resolvedName_isReturned_ipEchoIsNull() {
        usePtrLookup(ip -> "host.example.com");
        assertThat(enricher.reverseDns("203.0.113.7")).isEqualTo("host.example.com");

        usePtrLookup(ip -> ip);
        assertThat(enricher.reverseDns("203.0.113.7")).isNull();
    }

    @Test
    @DisplayName("Özel/boş IP'de ters-DNS hiç denenmez")
    void reverseDns_privateOrBlank_skipsLookup() {
        AtomicInteger calls = new AtomicInteger();
        usePtrLookup(ip -> { calls.incrementAndGet(); return "x.example.com"; });
        when(geo.isPrivateIp("10.0.0.5")).thenReturn(true);

        assertThat(enricher.reverseDns("10.0.0.5")).isNull();
        assertThat(enricher.reverseDns("")).isNull();
        assertThat(enricher.reverseDns(null)).isNull();
        assertThat(calls.get()).isZero();
    }
}
