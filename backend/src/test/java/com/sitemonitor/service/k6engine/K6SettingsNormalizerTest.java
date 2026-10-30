package com.sitemonitor.service.k6engine;

import com.sitemonitor.model.AppSetting;
import com.sitemonitor.repository.AppSettingRepository;
import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.DeploymentHistoryService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.ScriptedCheckerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tek seferlik k6 ayar normalleştirmesi (2026-10-30, Yayın B + C): Yayın A'nın kayıtlı ikili yolları varsayılana döner;
 * yalnız bütün pod'lar bu sürümdeyken; başka değerlere dokunulmaz; koşullu silme arada yapılan bir kaydı ezmez; nişan
 * yalnız iş bitince yazılır. Gerçek {@link AppSettingsService} + H2 deposu, işlemsiz ({@code NOT_SUPPORTED} — üretimdeki
 * gibi; bkz. RepositoryWriteTransactionGuardTest gerekçesi).
 */
@DataJpaTest
@Import({AppSettingsService.class, K6SettingsNormalizerTest.Events.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class K6SettingsNormalizerTest {

    @Component
    static class Events {
        final List<AppSettingsChangedEvent> seen = new ArrayList<>();
        @EventListener void on(AppSettingsChangedEvent e) { seen.add(e); }
    }

    @Autowired AppSettingsService settings;
    @Autowired AppSettingRepository repo;
    @Autowired DataSource dataSource;
    @Autowired Events events;

    private JdbcTemplate jdbc;
    private DeploymentHistoryService history;
    private AuditService audit;
    private SchedulerService scheduler;
    private K6SettingsNormalizer job;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        clean();
        history = mock(DeploymentHistoryService.class);
        when(history.liveOtherVersions()).thenReturn(List.of());
        audit = mock(AuditService.class);
        scheduler = mock(SchedulerService.class);
        when(scheduler.callWithSchedulerLock(eq(K6SettingsNormalizer.LOCK), any(java.util.function.Supplier.class)))
                .thenAnswer(i -> Optional.ofNullable(((java.util.function.Supplier<?>) i.getArgument(1)).get()));
        job = new K6SettingsNormalizer(settings, jdbc, provider(scheduler), provider(history), provider(audit));
    }

    @AfterEach
    void clean() {
        repo.deleteAll();
        jdbc.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", K6SettingsNormalizer.KEY);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(settings, "refreshFromDb");
        if (events != null) events.seen.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private void store(String key, String value) {
        repo.save(new AppSetting(key, value, "2026-10-30T15:47:28", "root"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(settings, "refreshFromDb");
    }

    private boolean marked() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                K6SettingsNormalizer.KEY);
        return n != null && n > 0;
    }

    @Test
    @DisplayName("Prod'un Yayın A durumu (etkin = /opt/k6-next/k6, yedek = k6, aday = /opt/k6-next/k6) → üçü de varsayılana döner")
    void releaseAStateIsNormalized() {
        store(ScriptedCheckerService.K6_BIN_KEY, "/opt/k6-next/k6");
        store(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "k6");
        store(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "/opt/k6-next/k6");

        job.run();

        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isNull();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)).isNull();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY)).isNull();
        assertThat(repo.findBySettingKey(ScriptedCheckerService.K6_BIN_KEY)).get()
                .satisfies(r -> {
                    assertThat(r.getValue()).isNull();
                    assertThat(r.getUpdatedBy()).isEqualTo("SiteMonitor");
                });
        assertThat(marked()).isTrue();
        assertThat(events.seen).isNotEmpty();
        verify(audit).recordSystemEvent(eq("K6_ENGINE_SETTINGS_NORMALIZE"), eq("SETTINGS"), eq("k6-engine"), anyString());
    }

    @Test
    @DisplayName("Elle girilmiş özel yol ve 'none' işareti olduğu gibi kalır; değişecek bir şey yoksa yalnız nişan yazılır")
    void otherValuesUntouched() {
        store(ScriptedCheckerService.K6_BIN_KEY, "/usr/local/bin/k6-custom");
        store(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "none");
        store(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "/opt/k6-3/k6");

        job.run();

        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isEqualTo("/usr/local/bin/k6-custom");
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)).isEqualTo("none");
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY)).isEqualTo("/opt/k6-3/k6");
        assertThat(marked()).isTrue();
        verify(audit, never()).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Başka sürüm koşan canlı pod varken ya da pod sürümleri okunamazken HİÇBİR ŞEY yapılmaz (eski pod'da k6 = 0.49)")
    void waitsWhileVersionsMixed() {
        store(ScriptedCheckerService.K6_BIN_KEY, "/opt/k6-next/k6");
        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("pod", "old-1", "version", "20.172.1")));
        job.run();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isEqualTo("/opt/k6-next/k6");
        assertThat(marked()).isFalse();

        when(history.liveOtherVersions()).thenReturn(null);
        job.run();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isEqualTo("/opt/k6-next/k6");
        assertThat(marked()).isFalse();

        when(history.liveOtherVersions()).thenReturn(List.of());
        job.run();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isNull();
        assertThat(marked()).isTrue();
    }

    @Test
    @DisplayName("Nişan yazıldıktan sonra iş bir daha dokunmaz (yönetici sonradan /opt/k6-next/k6 yazarsa kalır)")
    void runsOnce() {
        job.run();
        assertThat(marked()).isTrue();
        store(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "/opt/k6-next/k6");
        job.run();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY)).isEqualTo("/opt/k6-next/k6");
    }

    @Test
    @DisplayName("Koşullu silme: okunduktan sonra satır değiştiyse (başka pod / yönetici kaydı) dokunulmaz")
    void conditionalClearRespectsConcurrentWrite() {
        store(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "k6");
        // Arada başka bir kayıt: damga değişti (değer aynı kalsa bile koşul tutmaz).
        assertThat(repo.clearIfUnchanged(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "2026-10-30T00:00:00", "x", "y"))
                .isZero();
        assertThat(repo.findBySettingKey(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)).get()
                .extracting(AppSetting::getValue).isEqualTo("k6");
        // Beklenen değer kümesi dışında: silinmez.
        assertThat(settings.systemClearOverrideIf(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, Set.of("/usr/bin/k6"), "SiteMonitor"))
                .isFalse();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)).isEqualTo("k6");
        // Kümede ve damga aynı: silinir; satır yoksa false.
        assertThat(settings.systemClearOverrideIf(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, Set.of("k6"), "SiteMonitor"))
                .isTrue();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)).isNull();
        assertThat(settings.systemClearOverrideIf(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, Set.of("k6"), "SiteMonitor"))
                .isFalse();
    }

    @Test
    @DisplayName("Kilit başka pod'dayken iş bekler (nişan yok); kilit yoksa (zamanlayıcı yok) hiçbir şey yapılmaz")
    void lockElsewhere() {
        store(ScriptedCheckerService.K6_BIN_KEY, "/opt/k6-next/k6");
        when(scheduler.callWithSchedulerLock(eq(K6SettingsNormalizer.LOCK), any(java.util.function.Supplier.class)))
                .thenReturn(Optional.empty());
        job.run();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isEqualTo("/opt/k6-next/k6");
        assertThat(marked()).isFalse();

        K6SettingsNormalizer noScheduler = new K6SettingsNormalizer(settings, jdbc, provider(null), provider(history),
                provider(audit));
        noScheduler.run();
        assertThat(marked()).isFalse();
    }

    @Test
    @DisplayName("Nişan bir kez görülünce bellekte kalır: sonraki 10 dk'lık turlar ne pod sürümlerini ne nişanı okur")
    void markerRememberedInMemory() {
        job.run();
        assertThat(marked()).isTrue();
        // Nişan satırı elle silinse ve eski bir değer yazılsa bile bu pod iş bitti sayar (DB'ye gitmez).
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", K6SettingsNormalizer.KEY);
        store(ScriptedCheckerService.K6_BIN_KEY, "/opt/k6-next/k6");
        org.mockito.Mockito.clearInvocations(history, scheduler);
        job.run();
        job.run();
        verify(history, never()).liveOtherVersions();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(java.util.function.Supplier.class));
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isEqualTo("/opt/k6-next/k6");
        assertThat(marked()).isFalse();
        // Yeni bir pod (yeni süreç) nişanı yeniden okur.
        K6SettingsNormalizer fresh = new K6SettingsNormalizer(settings, jdbc, provider(scheduler), provider(history), provider(audit));
        fresh.run();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isNull();
        assertThat(marked()).isTrue();
    }

    @Test
    @DisplayName("Hata sınırı: kilit altındaki iş 36 kez düşerse bu pod denemeyi bırakır (nişan yok); bekleme turları sayılmaz")
    void givesUpAfterMaxAttempts() {
        store(ScriptedCheckerService.K6_BIN_KEY, "/opt/k6-next/k6");
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(scheduler)
                .callWithSchedulerLock(eq(K6SettingsNormalizer.LOCK), any(java.util.function.Supplier.class));
        // Karışık sürüm turları sayılmaz.
        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("pod", "old-1", "version", "20.172.1")));
        for (int i = 0; i < 50; i++) job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(java.util.function.Supplier.class));

        when(history.liveOtherVersions()).thenReturn(List.of());
        for (int i = 0; i < K6SettingsNormalizer.MAX_ATTEMPTS + 10; i++) job.run();
        verify(scheduler, org.mockito.Mockito.times(K6SettingsNormalizer.MAX_ATTEMPTS))
                .callWithSchedulerLock(eq(K6SettingsNormalizer.LOCK), any(java.util.function.Supplier.class));
        assertThat(marked()).isFalse();
        assertThat(settings.getOverride(ScriptedCheckerService.K6_BIN_KEY)).isEqualTo("/opt/k6-next/k6");
    }

    @Test
    @DisplayName("Sıfırlanan değer kümesi: yedek {k6, /usr/bin/k6, /opt/k6-next/k6}, aday ve etkin yalnız /opt/k6-next/k6")
    void resetSets() {
        assertThat(K6SettingsNormalizer.RESET.get(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY))
                .containsExactlyInAnyOrder("k6", "/usr/bin/k6", "/opt/k6-next/k6");
        assertThat(K6SettingsNormalizer.RESET.get(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY)).containsExactly("/opt/k6-next/k6");
        assertThat(K6SettingsNormalizer.RESET.get(ScriptedCheckerService.K6_BIN_KEY)).containsExactly("/opt/k6-next/k6");
    }
}
