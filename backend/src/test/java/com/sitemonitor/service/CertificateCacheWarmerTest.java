package com.sitemonitor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Genel Bakış önbellek ısıtıcısı (2026-10-09): açılışta ve her boşaltmadan sonra ARKA PLANDA bir kez ısıtır; arka arkaya
 * gelen istekler tek turda birleşir; bir adımın hatası diğerlerini durdurmaz; kapatılabilir.
 */
class CertificateCacheWarmerTest {

    CertificateService certs;
    CertificateCardExtrasService extras;
    CertificateCacheWarmer warmer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        certs = mock(CertificateService.class);
        extras = mock(CertificateCardExtrasService.class);
        ObjectProvider<CertificateService> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(certs);
        ObjectProvider<CertificateCardExtrasService> ep = mock(ObjectProvider.class);
        when(ep.getIfAvailable()).thenReturn(extras);
        warmer = new CertificateCacheWarmer(cp, ep);
        warmer.debounceMs = 50;
    }

    @AfterEach
    void tearDown() {
        warmer.shutdown();
    }

    @Test
    @DisplayName("Arka arkaya gelen boşaltmalar TEK ısıtmada birleşir; tur bittikten sonraki boşaltma yeni tur kurar")
    void requestsCoalesce_thenNextEvictionWarmsAgain() {
        assertThat(warmer.request()).isTrue();
        assertThat(warmer.request()).as("bekleyen varken yeni tur kurulmaz").isFalse();
        warmer.onEvicted(new CertificateService.CachesEvictedEvent());
        verify(certs, timeout(5_000).times(1)).getAllLatest();
        verify(certs, timeout(5_000).times(1)).getStats();
        verify(extras, timeout(5_000).times(1)).all();

        warmer.onEvicted(new CertificateService.CachesEvictedEvent());
        verify(certs, timeout(5_000).times(2)).getAllLatest();
    }

    @Test
    @DisplayName("Bir adımın hatası diğer adımları durdurmaz (ısıtma olmasa da ilk istek hesaplar)")
    void failingStep_doesNotStopOthers() {
        when(certs.getAllLatest()).thenThrow(new RuntimeException("db yok"));
        warmer.onReady();
        verify(certs, timeout(5_000)).getStats();
        verify(extras, timeout(5_000)).all();
    }

    @Test
    @DisplayName("site.monitor.cache.warm-enabled=false → hiç ısıtma yok")
    void disabled_noWarm() throws Exception {
        ReflectionTestUtils.setField(warmer, "enabled", false);
        assertThat(warmer.request()).isFalse();
        Thread.sleep(Duration.ofMillis(200));
        verifyNoInteractions(certs, extras);
    }

    @Test
    @DisplayName("evictAllCaches ısıtıcıya olay yayımlar (yayımcı yoksa sessiz)")
    void evictAllCaches_publishesEvent() {
        CertificateService svc = mock(CertificateService.class, CALLS_REAL_METHODS);
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(svc, "cacheEvents", pub);
        svc.evictAllCaches();
        verify(pub).publishEvent(any(CertificateService.CachesEvictedEvent.class));

        ReflectionTestUtils.setField(svc, "cacheEvents", null);
        svc.evictAllCaches();   // istisna yok
    }
}
