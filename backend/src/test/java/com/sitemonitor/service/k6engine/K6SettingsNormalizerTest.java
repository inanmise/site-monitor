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
import org.springframework.dao.DataAccessResourceFailureException;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tek seferlik k6 ayar normalleştirmesi — iki geçiş, iki nişan:
 * <ul>
 *   <li>1. geçiş (2026-10-30, Yayın B + C): Yayın A'nın kayıtlı ikili yolları varsayılana döner;</li>
 *   <li>2. geçiş (2026-11-20, k6 2.3 → 2.4 terfisi): 1. adımın (20.212 – 20.217) aday yolu {@code /opt/k6-candidate/k6} artık
 *       etkin ikilinin takma adı — yedek / aday olarak kayıtlıysa varsayılana döner.</li>
 * </ul>
 * İkisi de yalnız bütün pod'lar bu sürümdeyken koşar; başka değerlere ve ETKİN ikili ayarına dokunulmaz; koşullu silme arada
 * yapılan bir kaydı ezmez; her nişan yalnız o geçiş bitince yazılır. Gerçek {@link AppSettingsService} + H2 deposu,
 * işlemsiz ({@code NOT_SUPPORTED} — üretimdeki gibi; bkz. RepositoryWriteTransactionGuardTest gerekçesi).
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

    private static final String ACTIVE = ScriptedCheckerService.K6_BIN_KEY;
    private static final String ROLLBACK = ScriptedCheckerService.K6_BIN_ROLLBACK_KEY;
    private static final String CANDIDATE = ScriptedCheckerService.K6_BIN_CANDIDATE_KEY;

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
        job = newJob(jdbc);
    }

    @AfterEach
    void clean() {
        repo.deleteAll();
        jdbc.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        deleteMarkers();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(settings, "refreshFromDb");
        if (events != null) events.seen.clear();
    }

    private K6SettingsNormalizer newJob(JdbcTemplate template) {
        return new K6SettingsNormalizer(settings, template, provider(scheduler), provider(history), provider(audit));
    }

    /** İki geçişin nişanını da siler. */
    private void deleteMarkers() {
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key IN (?, ?)",
                K6SettingsNormalizer.KEY, K6SettingsNormalizer.KEY_PROMOTION_24);
    }

    /** Bir geçişin nişanını önceden yazar (o geçiş bu veritabanında daha önce koşmuş gibi). */
    private void premark(String key) {
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                key, "2026-10-30T12:00:00", 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private void store(String key, String value) {
        jdbc.update("DELETE FROM app_settings WHERE setting_key = ?", key);   // aynı anahtarı ikinci kez yazmak = güncelleme
        repo.save(new AppSetting(key, value, "2026-10-30T15:47:28", "root"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(settings, "refreshFromDb");
    }

    private int markerRows(String key) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, key);
        return n == null ? 0 : n;
    }

    /** 1. geçişin nişanı yazıldı mı. */
    private boolean marked() {
        return markerRows(K6SettingsNormalizer.KEY) > 0;
    }

    /** 2. geçişin (2.4 terfisi) nişanı yazıldı mı. */
    private boolean marked24() {
        return markerRows(K6SettingsNormalizer.KEY_PROMOTION_24) > 0;
    }

    /** Etkin ikili ayarına dokunulmadı: değer aynı, satırı son yazan hâlâ "root", hiçbir ayar olayı bu anahtara değmedi. */
    private void assertActiveUntouched(String expected) {
        assertThat(settings.getOverride(ACTIVE)).isEqualTo(expected);
        assertThat(repo.findBySettingKey(ACTIVE)).get().satisfies(r -> {
            assertThat(r.getValue()).isEqualTo(expected);
            assertThat(r.getUpdatedBy()).isEqualTo("root");
        });
        assertThat(events.seen).noneMatch(e -> e.touches(ACTIVE));
    }

    // ── 1. geçiş (2026-10-30) ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Prod'un Yayın A durumu (etkin = /opt/k6-next/k6, yedek = k6, aday = /opt/k6-next/k6) → yedek ve aday "
            + "varsayılana döner, ETKİN ikili /opt/k6-next/k6 KALIR (20.172.x'e geri dönüşte gerçek 2.3); iki nişan da yazılır")
    void releaseAStateIsNormalized() {
        store(ACTIVE, "/opt/k6-next/k6");
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-next/k6");
        events.seen.clear();   // store() kendi yenileme olaylarını yayınlar

        job.run();

        // İnceleme 2026-10-30 (M1): etkin ayara dokunulmaz — varsayılana dönseydi helm rollback senaryoları 0.49'a düşürürdü.
        assertActiveUntouched("/opt/k6-next/k6");
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(settings.getOverride(CANDIDATE)).isNull();
        assertThat(repo.findBySettingKey(ROLLBACK)).get()
                .satisfies(r -> {
                    assertThat(r.getValue()).isNull();
                    assertThat(r.getUpdatedBy()).isEqualTo("SiteMonitor");
                });
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        assertThat(events.seen).isNotEmpty();
        // Değişikliği 1. geçiş yaptı: tek denetim kaydı, 1. geçişin nişanıyla (2. geçişe değişecek bir şey kalmadı).
        verify(audit, times(1)).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
        verify(audit).recordSystemEvent(eq("K6_ENGINE_SETTINGS_NORMALIZE"), eq("SETTINGS"), eq("k6-engine"),
                contains("\"marker\":\"" + K6SettingsNormalizer.KEY + "\""));
    }

    @Test
    @DisplayName("2026-11-16 inceleme — 1. geçiş TEK BAŞINA (2. geçişin nişanı önceden yazılı): geçiş yapıldıktan sonra (etkin = "
            + "/opt/k6-candidate/k6, yedek = k6) yedeği SİLMEZ; eski aday temizlenir, etkin ayara dokunulmaz, nişan yazılır")
    void pass1AloneKeepsRollbackAfterSwitchToCandidate() {
        premark(K6SettingsNormalizer.KEY_PROMOTION_24);
        store(ACTIVE, "/opt/k6-candidate/k6");
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-next/k6");
        events.seen.clear();

        job.run();

        assertActiveUntouched("/opt/k6-candidate/k6");
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(settings.getOverride(CANDIDATE)).isNull();   // eski aday yine temizlenir
        assertThat(marked()).isTrue();
        assertThat(markerRows(K6SettingsNormalizer.KEY_PROMOTION_24)).isEqualTo(1);
    }

    @Test
    @DisplayName("Aynı durumda TAM koşu: 1. geçişin koruduğu yedeği (k6) 2. geçiş sıfırlar — terfi imajında k6 ile "
            + "/opt/k6-candidate/k6 aynı 2.4.0 ikilisi; ETKİN değer /opt/k6-candidate/k6 KALIR")
    void promotionResetsRollbackThatPass1Kept() {
        store(ACTIVE, "/opt/k6-candidate/k6");
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-next/k6");
        events.seen.clear();

        job.run();

        assertActiveUntouched("/opt/k6-candidate/k6");
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(settings.getOverride(CANDIDATE)).isNull();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        // İki geçiş de bir şey değiştirdi: 1. geçiş eski adayı, 2. geçiş yedeği — her biri kendi nişanıyla denetime yazılır.
        verify(audit, times(2)).recordSystemEvent(eq("K6_ENGINE_SETTINGS_NORMALIZE"), eq("SETTINGS"), eq("k6-engine"), anyString());
        verify(audit).recordSystemEvent(anyString(), anyString(), anyString(),
                contains("\"marker\":\"" + K6SettingsNormalizer.KEY + "\""));
        verify(audit).recordSystemEvent(anyString(), anyString(), anyString(),
                contains("\"marker\":\"" + K6SettingsNormalizer.KEY_PROMOTION_24 + "\""));
    }

    @Test
    @DisplayName("activeSwitchedAway: kayıt yok / boş / eski 2.3 takma adları → hayır; başka bir yol → evet. Takma ad kümeli "
            + "biçim: 2. geçişte /opt/k6-candidate/k6 de takma addır, özel yol yine 'başka'")
    void activeSwitchedAwayRule() {
        Set<String> aliases24 = K6SettingsNormalizer.RESET_PROMOTION_24.get(ROLLBACK);
        assertThat(job.activeSwitchedAway()).isFalse();
        assertThat(job.activeSwitchedAway(aliases24)).isFalse();
        store(ACTIVE, "k6");
        assertThat(job.activeSwitchedAway()).isFalse();
        store(ACTIVE, " /usr/bin/k6 ");
        assertThat(job.activeSwitchedAway()).isFalse();
        assertThat(job.activeSwitchedAway(aliases24)).isFalse();
        store(ACTIVE, "/opt/k6-next/k6");
        assertThat(job.activeSwitchedAway()).isFalse();
        assertThat(job.activeSwitchedAway(aliases24)).isFalse();
        store(ACTIVE, "/opt/k6-candidate/k6");
        assertThat(job.activeSwitchedAway()).isTrue();
        assertThat(job.activeSwitchedAway(aliases24)).isFalse();
        store(ACTIVE, "/usr/local/bin/k6-custom");
        assertThat(job.activeSwitchedAway()).isTrue();
        assertThat(job.activeSwitchedAway(aliases24)).isTrue();
    }

    @Test
    @DisplayName("Elle girilmiş özel yol ve 'none' işareti olduğu gibi kalır; değişecek bir şey yoksa yalnız nişanlar yazılır")
    void otherValuesUntouched() {
        store(ACTIVE, "/usr/local/bin/k6-custom");
        store(ROLLBACK, "none");
        store(CANDIDATE, "/opt/k6-3/k6");

        job.run();

        assertThat(settings.getOverride(ACTIVE)).isEqualTo("/usr/local/bin/k6-custom");
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("none");
        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-3/k6");
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        verify(audit, never()).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
    }

    // ── 2. geçiş (2026-11-20, k6 2.3 → 2.4 terfisi) ─────────────────────────────────────────────────

    @Test
    @DisplayName("Terfi — 1. adımda 'Adayı etkin yap' yapılmış ortam (etkin = /opt/k6-candidate/k6, yedek = k6, aday = "
            + "/opt/k6-candidate/k6): yedek ve aday varsayılana döner, ETKİN değer KALIR (20.212 – 20.217'ye geri dönüşte gerçek "
            + "2.4.0), iki nişan yazılır, tek denetim kaydı 2. geçişin nişanıyla")
    void promotionStateAfterSwitch() {
        store(ACTIVE, "/opt/k6-candidate/k6");
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-candidate/k6");
        events.seen.clear();

        job.run();

        assertActiveUntouched("/opt/k6-candidate/k6");
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(settings.getOverride(CANDIDATE)).isNull();
        assertThat(repo.findBySettingKey(ROLLBACK)).get()
                .satisfies(r -> {
                    assertThat(r.getValue()).isNull();
                    assertThat(r.getUpdatedBy()).isEqualTo("SiteMonitor");
                });
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        assertThat(events.seen).anyMatch(e -> e.touches(ROLLBACK));
        assertThat(events.seen).anyMatch(e -> e.touches(CANDIDATE));
        verify(audit, times(1)).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
        verify(audit).recordSystemEvent(eq("K6_ENGINE_SETTINGS_NORMALIZE"), eq("SETTINGS"), eq("k6-engine"),
                contains("\"marker\":\"" + K6SettingsNormalizer.KEY_PROMOTION_24 + "\""));
    }

    @Test
    @DisplayName("Terfi — hiç geçiş yapılmamış ortam (etkin kayıt yok = varsayılan k6, aday /opt/k6-candidate/k6 kayıtlı, yedek "
            + "'none'): aday varsayılana döner (o yol artık etkin ikilinin kendisi), 'none' yedek kalır")
    void promotionDefaultStateNeverSwitched() {
        store(ROLLBACK, "none");
        store(CANDIDATE, "/opt/k6-candidate/k6");
        events.seen.clear();

        job.run();

        assertThat(settings.getOverride(ACTIVE)).isNull();
        assertThat(repo.findBySettingKey(ACTIVE)).isEmpty();
        assertThat(events.seen).noneMatch(e -> e.touches(ACTIVE));
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("none");
        assertThat(settings.getOverride(CANDIDATE)).isNull();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        verify(audit, times(1)).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
        verify(audit).recordSystemEvent(eq("K6_ENGINE_SETTINGS_NORMALIZE"), eq("SETTINGS"), eq("k6-engine"),
                contains("\"marker\":\"" + K6SettingsNormalizer.KEY_PROMOTION_24 + "\""));
    }

    @Test
    @DisplayName("Terfi — etkin ikili elle girilmiş ÖZEL bir yol (/usr/local/bin/k6-custom) ve yedek = k6: yedek gerçekten "
            + "BAŞKA bir ikili — iki geçiş de yedeği KORUR ('Yedeğe geri dön' açık kalır); aday sıfırlaması atlanmaz")
    void customActivePathKeepsRollback() {
        store(ACTIVE, "/usr/local/bin/k6-custom");
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-candidate/k6");
        events.seen.clear();

        job.run();

        assertActiveUntouched("/usr/local/bin/k6-custom");
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(repo.findBySettingKey(ROLLBACK)).get().satisfies(r -> assertThat(r.getUpdatedBy()).isEqualTo("root"));
        assertThat(settings.getOverride(CANDIDATE)).isNull();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
    }

    @Test
    @DisplayName("Terfi — olağan prod durumu: 1. geçişin nişanı ZATEN yazılı. Yalnız 2. geçiş koşar: yedek (k6) sıfırlanır; "
            + "1. geçişten SONRA kaydedilmiş /opt/k6-next/k6 adayına bir daha dokunulmaz (1. geçiş yeniden koşmaz)")
    void pass2RunsWhenPass1MarkerAlreadyExists() {
        premark(K6SettingsNormalizer.KEY);
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-next/k6");   // 1. geçişin sıfırlayacağı değer — nişanı yazılı olduğu için kalmalı
        events.seen.clear();

        job.run();

        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-next/k6");
        assertThat(repo.findBySettingKey(CANDIDATE)).get().satisfies(r -> assertThat(r.getUpdatedBy()).isEqualTo("root"));
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(markerRows(K6SettingsNormalizer.KEY)).isEqualTo(1);
        assertThat(marked24()).isTrue();
        verify(audit, times(1)).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
        verify(audit).recordSystemEvent(eq("K6_ENGINE_SETTINGS_NORMALIZE"), eq("SETTINGS"), eq("k6-engine"),
                contains("\"marker\":\"" + K6SettingsNormalizer.KEY_PROMOTION_24 + "\""));
    }

    @Test
    @DisplayName("Terfi — yedek /opt/k6-candidate/k6 ya da /opt/k6-next/k6 ise (takma adlar) sıfırlanır; etkin = /opt/k6-next/k6 "
            + "(Yayın A'dan kalan) dokunulmadan kalır")
    void promotionResetsAliasRollback() {
        premark(K6SettingsNormalizer.KEY);
        store(ACTIVE, "/opt/k6-next/k6");
        store(ROLLBACK, "/opt/k6-candidate/k6");
        events.seen.clear();

        job.run();

        assertActiveUntouched("/opt/k6-next/k6");
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(marked24()).isTrue();
    }

    @Test
    @DisplayName("2. geçişin nişanı yazılamazsa (DB hatası) 1. geçişin nişanı durur; sonraki tur 1. geçişi yeniden koşmaz, "
            + "yalnız 2. geçişi tamamlar")
    void pass2FailureKeepsPass1Marker() {
        AtomicBoolean fail = new AtomicBoolean(true);
        JdbcTemplate flaky = new JdbcTemplate(dataSource) {
            @Override
            public int update(String sql, Object... args) {
                if (fail.get() && sql.startsWith("INSERT INTO schema_patch_markers") && args != null && args.length > 0
                        && K6SettingsNormalizer.KEY_PROMOTION_24.equals(args[0])) {
                    throw new DataAccessResourceFailureException("db down");
                }
                return super.update(sql, args);
            }
        };
        K6SettingsNormalizer flakyJob = newJob(flaky);
        store(CANDIDATE, "/opt/k6-next/k6");            // yalnız 1. geçiş sıfırlar
        store(ROLLBACK, "/opt/k6-candidate/k6");        // yalnız 2. geçiş sıfırlar

        flakyJob.run();                                  // 1. geçiş biter; 2. geçişin nişanı yazılamaz

        assertThat(marked()).isTrue();
        assertThat(marked24()).isFalse();
        assertThat(settings.getOverride(CANDIDATE)).isNull();

        // Arada yönetici 1. geçişin değerini yeniden yazarsa kalmalı: 1. geçiş bir daha koşmaz.
        store(CANDIDATE, "/opt/k6-next/k6");
        fail.set(false);
        flakyJob.run();

        assertThat(marked24()).isTrue();
        assertThat(markerRows(K6SettingsNormalizer.KEY)).isEqualTo(1);
        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-next/k6");
        assertThat(settings.getOverride(ROLLBACK)).isNull();
    }

    // ── Ortak kurallar ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Başka sürüm koşan canlı pod varken ya da pod sürümleri okunamazken HİÇBİR ŞEY yapılmaz (eski pod'da k6 başka "
            + "bir sürüm) — iki geçiş için de")
    void waitsWhileVersionsMixed() {
        store(ROLLBACK, "k6");
        store(CANDIDATE, "/opt/k6-candidate/k6");
        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("pod", "old-1", "version", "20.217.1")));
        job.run();
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-candidate/k6");
        assertThat(marked()).isFalse();
        assertThat(marked24()).isFalse();

        when(history.liveOtherVersions()).thenReturn(null);
        job.run();
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-candidate/k6");
        assertThat(marked()).isFalse();
        assertThat(marked24()).isFalse();

        when(history.liveOtherVersions()).thenReturn(List.of());
        job.run();
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(settings.getOverride(CANDIDATE)).isNull();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
    }

    @Test
    @DisplayName("Nişanlar yazıldıktan sonra iş bir daha dokunmaz: yönetici sonradan /opt/k6-next/k6 adayı ya da (20.212 – "
            + "20.217'ye helm rollback'ten sonra) yedek = k6 yazarsa kalır — aynı pod'da da, yeni bir pod'da da")
    void runsOnce() {
        job.run();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        store(CANDIDATE, "/opt/k6-next/k6");
        store(ROLLBACK, "k6");
        job.run();
        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-next/k6");
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");

        // Yeni bir pod (bellekte nişan yok) iki nişanı da veritabanından okur — yine dokunmaz.
        store(CANDIDATE, "/opt/k6-candidate/k6");
        newJob(jdbc).run();
        assertThat(settings.getOverride(CANDIDATE)).isEqualTo("/opt/k6-candidate/k6");
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(markerRows(K6SettingsNormalizer.KEY)).isEqualTo(1);
        assertThat(markerRows(K6SettingsNormalizer.KEY_PROMOTION_24)).isEqualTo(1);
        verify(audit, never()).recordSystemEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Koşullu silme: okunduktan sonra satır değiştiyse (başka pod / yönetici kaydı) dokunulmaz")
    void conditionalClearRespectsConcurrentWrite() {
        store(ROLLBACK, "k6");
        // Arada başka bir kayıt: damga değişti (değer aynı kalsa bile koşul tutmaz).
        assertThat(repo.clearIfUnchanged(ROLLBACK, "2026-10-30T00:00:00", "x", "y"))
                .isZero();
        assertThat(repo.findBySettingKey(ROLLBACK)).get()
                .extracting(AppSetting::getValue).isEqualTo("k6");
        // Beklenen değer kümesi dışında: silinmez.
        assertThat(settings.systemClearOverrideIf(ROLLBACK, Set.of("/usr/bin/k6"), "SiteMonitor"))
                .isFalse();
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        // Kümede ve damga aynı: silinir; satır yoksa false.
        assertThat(settings.systemClearOverrideIf(ROLLBACK, Set.of("k6"), "SiteMonitor"))
                .isTrue();
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(settings.systemClearOverrideIf(CANDIDATE, Set.of("k6"), "SiteMonitor"))
                .isFalse();
    }

    @Test
    @DisplayName("Kilit başka pod'dayken iş bekler (nişan yok); kilit yoksa (zamanlayıcı yok) hiçbir şey yapılmaz")
    void lockElsewhere() {
        store(ROLLBACK, "k6");
        when(scheduler.callWithSchedulerLock(eq(K6SettingsNormalizer.LOCK), any(java.util.function.Supplier.class)))
                .thenReturn(Optional.empty());
        job.run();
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(marked()).isFalse();
        assertThat(marked24()).isFalse();

        K6SettingsNormalizer noScheduler = new K6SettingsNormalizer(settings, jdbc, provider(null), provider(history),
                provider(audit));
        noScheduler.run();
        assertThat(marked()).isFalse();
        assertThat(marked24()).isFalse();
    }

    @Test
    @DisplayName("Nişanlar bir kez görülünce bellekte kalır: sonraki 10 dk'lık turlar ne pod sürümlerini ne nişanları okur")
    void markerRememberedInMemory() {
        job.run();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
        // İki nişan satırı elle silinse ve eski bir değer yazılsa bile bu pod iş bitti sayar (DB'ye gitmez).
        deleteMarkers();
        store(ROLLBACK, "k6");
        org.mockito.Mockito.clearInvocations(history, scheduler);
        job.run();
        job.run();
        verify(history, never()).liveOtherVersions();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(java.util.function.Supplier.class));
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
        assertThat(marked()).isFalse();
        assertThat(marked24()).isFalse();
        // Yeni bir pod (yeni süreç) nişanları yeniden okur.
        K6SettingsNormalizer fresh = newJob(jdbc);
        fresh.run();
        assertThat(settings.getOverride(ROLLBACK)).isNull();
        assertThat(marked()).isTrue();
        assertThat(marked24()).isTrue();
    }

    @Test
    @DisplayName("Bellek nişan BAŞINADIR: yalnız 1. geçişin nişanını görmüş bir pod 2. geçişi bitmiş saymaz — her turda kilide "
            + "gider, 2. geçiş bitince durur")
    void memoIsPerMarker() {
        premark(K6SettingsNormalizer.KEY);
        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("pod", "old-1", "version", "20.217.1")));
        job.run();                                       // 1. geçişin nişanı görüldü; 2. geçiş karışık sürüm yüzünden bekliyor
        assertThat(marked24()).isFalse();
        verify(history, times(1)).liveOtherVersions();

        when(history.liveOtherVersions()).thenReturn(List.of());
        store(ROLLBACK, "/usr/bin/k6");
        job.run();
        assertThat(marked24()).isTrue();
        assertThat(settings.getOverride(ROLLBACK)).isNull();

        org.mockito.Mockito.clearInvocations(history, scheduler);
        job.run();
        verify(history, never()).liveOtherVersions();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(java.util.function.Supplier.class));
    }

    @Test
    @DisplayName("Hata sınırı: kilit altındaki iş 36 kez düşerse bu pod denemeyi bırakır (nişan yok); bekleme turları sayılmaz")
    void givesUpAfterMaxAttempts() {
        store(ROLLBACK, "k6");
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
        assertThat(marked24()).isFalse();
        assertThat(settings.getOverride(ROLLBACK)).isEqualTo("k6");
    }

    @Test
    @DisplayName("Sıfırlanan değer kümeleri — 1. geçiş: yedek {k6, /usr/bin/k6, /opt/k6-next/k6}, aday yalnız /opt/k6-next/k6; "
            + "2. geçiş (terfi): yedek + /opt/k6-candidate/k6, aday yalnız /opt/k6-candidate/k6; ETKİN ikili hiçbirinde YOK")
    void resetSets() {
        assertThat(K6SettingsNormalizer.RESET.keySet()).containsExactly(ROLLBACK, CANDIDATE);
        assertThat(K6SettingsNormalizer.RESET.get(ROLLBACK))
                .containsExactlyInAnyOrder("k6", "/usr/bin/k6", "/opt/k6-next/k6");
        assertThat(K6SettingsNormalizer.RESET.get(CANDIDATE)).containsExactly("/opt/k6-next/k6");
        assertThat(K6SettingsNormalizer.RESET).doesNotContainKey(ACTIVE);

        assertThat(K6SettingsNormalizer.LEGACY_CANDIDATE_PATH_24).isEqualTo("/opt/k6-candidate/k6");
        assertThat(K6SettingsNormalizer.RESET_PROMOTION_24.keySet()).containsExactly(ROLLBACK, CANDIDATE);
        assertThat(K6SettingsNormalizer.RESET_PROMOTION_24.get(ROLLBACK))
                .containsExactlyInAnyOrder("k6", "/usr/bin/k6", "/opt/k6-next/k6", "/opt/k6-candidate/k6");
        assertThat(K6SettingsNormalizer.RESET_PROMOTION_24.get(CANDIDATE)).containsExactly("/opt/k6-candidate/k6");
        assertThat(K6SettingsNormalizer.RESET_PROMOTION_24).doesNotContainKey(ACTIVE);

        // Geçişler: sırayla, nişanları KALICI ve ayrı; her geçişin takma adları = o geçişte yedekte sıfırlanan değerler.
        assertThat(K6SettingsNormalizer.KEY).isEqualTo("2026-10-30-k6-settings-normalize");
        assertThat(K6SettingsNormalizer.KEY_PROMOTION_24).isEqualTo("2026-11-20-k6-24-promotion-normalize");
        assertThat(K6SettingsNormalizer.PASSES).extracting(K6SettingsNormalizer.Pass::key)
                .containsExactly(K6SettingsNormalizer.KEY, K6SettingsNormalizer.KEY_PROMOTION_24);
        assertThat(K6SettingsNormalizer.PASSES.get(0).reset()).isSameAs(K6SettingsNormalizer.RESET);
        assertThat(K6SettingsNormalizer.PASSES.get(0).activeAliases())
                .containsExactlyInAnyOrder("k6", "/usr/bin/k6", "/opt/k6-next/k6");
        assertThat(K6SettingsNormalizer.PASSES.get(1).reset()).isSameAs(K6SettingsNormalizer.RESET_PROMOTION_24);
        assertThat(K6SettingsNormalizer.PASSES.get(1).activeAliases())
                .containsExactlyInAnyOrder("k6", "/usr/bin/k6", "/opt/k6-next/k6", "/opt/k6-candidate/k6");
        for (K6SettingsNormalizer.Pass p : K6SettingsNormalizer.PASSES) {
            assertThat(p.reset()).doesNotContainKey(ACTIVE);
            assertThat(p.key().length()).isLessThanOrEqualTo(120);   // schema_patch_markers.patch_key VARCHAR(120)
        }
    }
}
