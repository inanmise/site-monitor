package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 P0 — yerel yetenek sözleşmeleri (tasarım R3-3 §2.5 / §6): dağıtık kontrol işçisi senaryo işlerini en
 * çok {@link ScriptedCheckerService#freePermits()} kadar, ping işlerini yalnız {@link PingCheckerService#icmpAvailable()}
 * iken sahiplenir. İkisi de yalnız bellek okumasıdır; kontrol sonuçları değişmez.
 */
class CheckCapabilityContractsTest {

    // ── ICMP ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ICMP: hiç ping koşmamış pod'da var; 'na' sonrası 5 dk yok, 5 dk dolunca yeniden var; yeni 'na' süreyi yeniler")
    void icmpHoldsFiveMinutesAfterNa() {
        PingCheckerService p = new PingCheckerService();
        AtomicLong nanos = new AtomicLong(1_000L);
        p.icmpClock = nanos::get;
        assertThat(p.icmpAvailable()).isTrue();

        p.noteIcmpUnavailable();
        assertThat(p.icmpAvailable()).isFalse();
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(PingCheckerService.ICMP_NA_HOLD_MS - 1));
        assertThat(p.icmpAvailable()).isFalse();
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        assertThat(p.icmpAvailable()).isTrue();

        p.noteIcmpUnavailable();
        assertThat(p.icmpAvailable()).isFalse();
        assertThat(PingCheckerService.ICMP_NA_HOLD_MS).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("ICMP sondası: geri döngüye 1 paket; 'na' → yok, yanıt → var, kontrol istisnası → mevcut durum (fırlatmaz)")
    void icmpProbe() {
        Map<String, Object> na = new LinkedHashMap<>();
        na.put("up", false);
        na.put("na", true);
        AtomicInteger calls = new AtomicInteger();
        PingCheckerService naPod = new PingCheckerService() {
            @Override
            public Map<String, Object> check(String host, String ipVersion, int count, int timeoutMs) {
                calls.incrementAndGet();
                assertThat(host).isEqualTo(PingCheckerService.ICMP_PROBE_HOST);
                assertThat(count).isEqualTo(1);
                return na;
            }
        };
        assertThat(naPod.probeIcmp()).isFalse();
        assertThat(naPod.icmpAvailable()).isFalse();
        assertThat(calls.get()).isEqualTo(1);

        PingCheckerService ok = new PingCheckerService() {
            @Override
            public Map<String, Object> check(String host, String ipVersion, int count, int timeoutMs) {
                return new LinkedHashMap<>(Map.of("up", true, "rtt_ms", 0L));
            }
        };
        assertThat(ok.probeIcmp()).isTrue();

        PingCheckerService broken = new PingCheckerService() {
            @Override
            public Map<String, Object> check(String host, String ipVersion, int count, int timeoutMs) {
                throw new IllegalStateException("alt süreç başlatılamadı");
            }
        };
        assertThat(broken.probeIcmp()).isTrue();
    }

    @Test
    @DisplayName("ICMP: check()'in 'ICMP kullanılamıyor' dalı damgayı koyar (kaynak) ve sonuç anahtarları değişmez")
    void naBranchStamps() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/sitemonitor/service/PingCheckerService.java"),
                StandardCharsets.UTF_8);
        int branch = src.indexOf("low.contains(\"socket operation\")");
        int put = src.indexOf("result.put(\"na\", true);", branch);
        assertThat(branch).isPositive();
        assertThat(src.substring(branch, put)).contains("noteIcmpUnavailable();");
    }

    // ── k6 izinleri ────────────────────────────────────────────────────────────────────────────────────────────────────

    private static ScriptedCheckerService scripted() {
        var appSettings = mock(AppSettingsService.class);
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        return new ScriptedCheckerService(null, null, appSettings,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new ProxySettings());
    }

    @Test
    @DisplayName("k6 izinleri: kurulmadan / k6 yokken 0; havuz 2 → 2; alınan izin ve sırada bekleyen koşum düşülür; asla negatif değil")
    void scriptedFreePermits() {
        ScriptedCheckerService svc = scripted();
        assertThat(svc.freePermits()).as("init öncesi").isZero();
        svc.init();
        ReflectionTestUtils.setField(svc, "k6Available", false);
        assertThat(svc.freePermits()).as("k6 yok").isZero();

        ReflectionTestUtils.setField(svc, "k6Available", true);
        assertThat(svc.freePermits()).as("varsayılan pool-size 2").isEqualTo(2);
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(svc, "permits");
        assertThat(permits).isNotNull();
        assertThat(permits.tryAcquire()).isTrue();
        assertThat(svc.freePermits()).isEqualTo(1);
        AtomicInteger queued = (AtomicInteger) ReflectionTestUtils.getField(svc, "queued");
        assertThat(queued).isNotNull();
        queued.addAndGet(3);
        assertThat(svc.freePermits()).as("tutucu: sıra düşülür, negatif değil").isZero();
        queued.addAndGet(-3);
        permits.release();
        assertThat(svc.freePermits()).isEqualTo(2);
        svc.shutdown();
    }
}
