package com.sitemonitor.service;

import com.sitemonitor.config.TunableThreadPoolTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Ayar olayı → executor canlı yeniden boyutlanır; geçersiz kombinasyon UYGULANMAZ (DB'ye
 * elle yazılmış bozuk değere karşı savunma); ilgisiz anahtar olayı executor'a dokunmaz.
 */
class ExecutorTuningServiceTest {

    private AppSettingsService settings;
    private TunableThreadPoolTaskExecutor ex;
    private ExecutorTuningService svc;

    @BeforeEach
    void setUp() {
        settings = Mockito.mock(AppSettingsService.class);
        // Varsayılan: ayar yok → fallback (executor'ın mevcut değeri) döner
        when(settings.getInt(Mockito.anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        ex = new TunableThreadPoolTaskExecutor();
        ex.setCorePoolSize(20); ex.setMaxPoolSize(50); ex.setQueueCapacity(5000);
        ex.initialize();
        svc = new ExecutorTuningService(settings, ex);
    }

    @AfterEach
    void tearDown() { ex.shutdown(); }

    @Test
    @DisplayName("save olayı: core/max/kuyruk executor'a canlı yansır ve alt JDK havuzu da değişir")
    void settingsChanged_appliesLive() {
        when(settings.getInt(eq(ExecutorTuningService.CORE_KEY), anyInt())).thenReturn(30);
        when(settings.getInt(eq(ExecutorTuningService.MAX_KEY), anyInt())).thenReturn(60);
        when(settings.getInt(eq(ExecutorTuningService.QUEUE_KEY), anyInt())).thenReturn(8000);

        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(ExecutorTuningService.MAX_KEY), "save"));

        assertThat(ex.getThreadPoolExecutor().getCorePoolSize()).isEqualTo(30);
        assertThat(ex.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(60);
        assertThat(ex.getQueueCapacity()).isEqualTo(8000);
        assertThat(ex.getLiveQueueCapacity()).isEqualTo(8000);
    }

    @Test
    @DisplayName("override kaldırılınca (boş ayar) env/boot değerine döner — fallback executor'ın mevcut değeri DEĞİL, getInt'in verdiği")
    void removedOverride_fallsBackToEnvironment() {
        // Önce 30/60 uygula
        when(settings.getInt(eq(ExecutorTuningService.CORE_KEY), anyInt())).thenReturn(30);
        when(settings.getInt(eq(ExecutorTuningService.MAX_KEY), anyInt())).thenReturn(60);
        svc.apply("save");
        assertThat(ex.getCorePoolSize()).isEqualTo(30);
        // Override silindi: gerçek AppSettingsService Environment'taki property'yi döner (20/50)
        when(settings.getInt(eq(ExecutorTuningService.CORE_KEY), anyInt())).thenReturn(20);
        when(settings.getInt(eq(ExecutorTuningService.MAX_KEY), anyInt())).thenReturn(50);
        assertThat(svc.apply("save")).isTrue();
        assertThat(ex.getThreadPoolExecutor().getCorePoolSize()).isEqualTo(20);
        assertThat(ex.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("geçersiz kombinasyon (core > max) UYGULANMAZ, mevcut değer korunur, false döner")
    void invalidCombination_skipped() {
        when(settings.getInt(eq(ExecutorTuningService.CORE_KEY), anyInt())).thenReturn(70);
        when(settings.getInt(eq(ExecutorTuningService.MAX_KEY), anyInt())).thenReturn(50);
        assertThat(svc.apply("refresh")).isFalse();
        assertThat(ex.getThreadPoolExecutor().getCorePoolSize()).isEqualTo(20);
        assertThat(ex.getThreadPoolExecutor().getMaximumPoolSize()).isEqualTo(50);
        assertThat(ex.getQueueCapacity()).isEqualTo(5000);
    }

    @Test
    @DisplayName("ilgisiz anahtar olayı executor'a dokunmaz; boş küme ('hepsi olabilir') dokunur")
    void unrelatedKey_ignored_emptySet_applies() {
        when(settings.getInt(eq(ExecutorTuningService.CORE_KEY), anyInt())).thenReturn(25);
        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.app.base-url"), "save"));
        assertThat(ex.getCorePoolSize()).as("ilgisiz olay").isEqualTo(20);
        svc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        assertThat(ex.getCorePoolSize()).as("boş küme = yeniden oku").isEqualTo(25);
    }

    @Test
    @DisplayName("boot (ApplicationReady): DB'de saklı override env'in üstüne uygulanır")
    void onReady_appliesStoredOverride() {
        when(settings.getInt(eq(ExecutorTuningService.QUEUE_KEY), anyInt())).thenReturn(12000);
        svc.onReady();
        assertThat(ex.getQueueCapacity()).isEqualTo(12000);
        assertThat(ex.getLiveQueueCapacity()).isEqualTo(12000);
    }

    @Test
    @DisplayName("validate: ortak kural (save ve apply aynı mesajı üretir)")
    void validate_rules() {
        assertThat(ExecutorTuningService.validate(20, 50, 5000)).isNull();
        assertThat(ExecutorTuningService.validate(0, 50, 5000)).contains("core-size");
        assertThat(ExecutorTuningService.validate(60, 50, 5000)).contains("max-size");
        assertThat(ExecutorTuningService.validate(20, 50, 0)).contains("queue-capacity");
    }
}
