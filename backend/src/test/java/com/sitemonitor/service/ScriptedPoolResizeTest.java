package com.sitemonitor.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code site.monitor.scripted.pool-size} CANLI uygulanır (2026-10-31, k6 inceleme A5) — yeniden başlatma gerekmez. */
class ScriptedPoolResizeTest {

    private static Semaphore field(ScriptedCheckerService svc, String name) throws Exception {
        var f = ScriptedCheckerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Semaphore) f.get(svc);
    }

    @Test
    void poolSizeChangeAppliesLive_runningRunsKeepTheirPermits() throws Exception {
        AtomicInteger pool = new AtomicInteger(2);
        var settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(eq(ScriptedCheckerService.POOL_SIZE_KEY), anyInt())).thenAnswer(i -> pool.get());
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        var svc = new ScriptedCheckerService(null, null, settings,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), new ProxySettings());
        svc.init();
        Semaphore permits = field(svc, "permits");
        Semaphore manual = field(svc, "manualSlots");
        assertThat(permits.availablePermits()).isEqualTo(2);
        assertThat(manual.availablePermits()).isEqualTo(1);

        // ilgisiz anahtar: dokunmaz
        pool.set(5);
        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.scripted.max-rps"), "test"));
        assertThat(permits.availablePermits()).isEqualTo(2);

        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(ScriptedCheckerService.POOL_SIZE_KEY), "test"));
        assertThat(permits.availablePermits()).isEqualTo(5);
        assertThat(manual.availablePermits()).isEqualTo(4);

        // azaltma: çalışan koşum iznini tutar; bitince havuz yeni boyuta oturur
        assertThat(permits.tryAcquire()).isTrue();
        pool.set(1);
        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(ScriptedCheckerService.POOL_SIZE_KEY), "test"));
        assertThat(permits.availablePermits()).isZero();
        assertThat(permits.tryAcquire()).isFalse();
        permits.release();
        assertThat(permits.availablePermits()).isEqualTo(1);
        assertThat(manual.availablePermits()).isEqualTo(1);       // pool=1 → elle pay yine 1

        // üst sınır
        pool.set(10_000);
        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(ScriptedCheckerService.POOL_SIZE_KEY), "test"));
        assertThat(permits.availablePermits()).isEqualTo(ScriptedCheckerService.MAX_POOL_SIZE);
    }
}
