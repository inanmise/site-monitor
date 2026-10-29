package com.sitemonitor.service;

import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckWorkerRegistryImpl;
import com.sitemonitor.service.checks.DistributedChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gerçek {@link PingCheckerService} ile işçi kaydının ping yeteneği (2026-10-29, çok pod Sürüm 3-3 FX-QUEUE, inceleme QW-L1 —
 * incelemecinin {@code R33ReviewIcmpWindowProbeTest} sondası kalıcı bekçi oldu): ICMP'siz pod "na" tutma süresi dolduğu an
 * ping'i yeniden ilan ETMEZ (sondanın "yok"u sonraki başarılı sondaya dek kalır) ve hiç sonda dönmeden ping ilan edilmez.
 * Sonda burada alt süreç başlatmadan {@code probeIcmp()}'in ICMP'siz podda yaptığını yapar ("na" damgası + {@code icmpAvailable()}).
 */
class CheckWorkerRegistryIcmpWindowTest {

    @Test
    @DisplayName("ICMP'siz pod: tutma süresi dolup sonraki sonda gelmeden ping ilan edilmez / sahiplenilmez; açılışta sonda öncesi de yok")
    void icmpLessPodNeverReadvertisesPingBetweenHoldExpiryAndNextProbe() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        PingCheckerService ping = new PingCheckerService();
        ping.icmpClock = nanos::get;
        DistributedChecks dc = new DistributedChecks(null, DistributedChecks.Tuning.DEFAULTS);
        CheckWorkerRegistryImpl reg = new CheckWorkerRegistryImpl(null, null, dc, "podx-00000001",
                () -> false, ping::icmpAvailable, () -> {
                    ping.noteIcmpUnavailable();   // ICMP'siz podda probeIcmp(): "na" → damga
                    return ping.icmpAvailable();
                }, () -> false, () -> false, () -> true, null, () -> "v", nanos::get);

        assertThat(ping.icmpAvailable()).as("hiç ping koşmadı — servis iyimser").isTrue();
        assertThat(reg.localCapabilities()).as("ama ilk sonda dönmeden ping ilan edilmez").doesNotContain(CheckJobTypes.PING);

        assertThat(reg.probeIcmpNow()).isFalse();
        assertThat(reg.localCapabilities()).doesNotContain(CheckJobTypes.PING);

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(PingCheckerService.ICMP_NA_HOLD_MS));
        assertThat(ping.icmpAvailable()).as("tutma süresi doldu").isTrue();
        assertThat(reg.localCapabilities()).as("sonraki sonda gelmeden pencere yok").doesNotContain(CheckJobTypes.PING);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(4));
        assertThat(reg.localCapabilities()).doesNotContain(CheckJobTypes.PING);
        assertThat(CheckWorkerRegistryImpl.ICMP_PROBE_EVERY_MS).isLessThan(PingCheckerService.ICMP_NA_HOLD_MS);
    }
}
