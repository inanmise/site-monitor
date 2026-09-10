package com.sitemonitor.service;

import com.sitemonitor.repository.AppSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * AppSettingsService — DB override'larının tipli getter'lara/getCatalogForClient'a yansıması,
 * katalog dışı key reddi ve tip doğrulama. @DataJpaTest (H2) + servisi @Import ile yükler.
 */
@DataJpaTest
@Import(AppSettingsService.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "site.monitor.scheduler.stale-minutes=65",
        "logging.level.com.sitemonitor=DEBUG",
        // Görev havuzu çapraz kuralı yalnız ÜÇÜ de çözülünce çalışır; src/test/resources/application.properties
        // ana dosyayı gölgeler ve bu anahtarları taşımaz → burada prod varsayılanlarıyla verilir.
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class AppSettingsServiceTest {

    @Autowired AppSettingsService service;
    @Autowired AppSettingRepository repo;

    private Map<String, Object> values(String key, Object val) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(key, val);
        Map<String, Object> body = new HashMap<>();
        body.put("values", inner);
        return body;
    }

    @Test
    @DisplayName("save → getString override'ı döner; boş değer override'ı kaldırır")
    void save_overridesString() {
        service.save(values("site.monitor.app.base-url", "https://prod.example.com"), "admin");
        assertThat(service.getString("site.monitor.app.base-url", "fallback"))
                .isEqualTo("https://prod.example.com");

        service.save(values("site.monitor.app.base-url", ""), "admin");
        assertThat(service.getString("site.monitor.app.base-url", "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("tipli getter'lar: int/double/bool override")
    void typedGetters() {
        service.save(values("site.monitor.network.min-errors", "7"), "admin");
        service.save(values("site.monitor.network.error-rate-threshold", "0.8"), "admin");
        service.save(values("site.monitor.uptime.alert-enabled", "false"), "admin");
        assertThat(service.getInt("site.monitor.network.min-errors", 3)).isEqualTo(7);
        assertThat(service.getDouble("site.monitor.network.error-rate-threshold", 0.5)).isEqualTo(0.8);
        assertThat(service.getBoolean("site.monitor.uptime.alert-enabled", true)).isFalse();
    }

    @Test
    @DisplayName("override yoksa Environment varsayılanı (stale-minutes) okunur")
    void defaultFromEnvironment() {
        assertThat(service.getInt("site.monitor.scheduler.stale-minutes", 999)).isEqualTo(65);
    }

    @Test
    @DisplayName("katalog dışı key reddedilir")
    void unknownKey_throws() {
        assertThatThrownBy(() -> service.save(values("some.random.key", "x"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tip doğrulama: geçersiz int ve geçersiz enum reddedilir")
    void typeValidation_throws() {
        assertThatThrownBy(() -> service.save(values("site.monitor.network.min-errors", "abc"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(values("logging.level.com.sitemonitor", "VERBOSE"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getCatalogForClient tüm katalog key'lerini + override durumunu döner")
    void catalogForClient() {
        service.save(values("site.monitor.system-admin.email", "ops@example.com"), "admin");
        List<Map<String, Object>> cat = service.getCatalogForClient();
        assertThat(cat).hasSize(AppSettingsCatalog.ALL.size());
        Map<String, Object> email = cat.stream()
                .filter(m -> "site.monitor.system-admin.email".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(email.get("value")).isEqualTo("ops@example.com");
        assertThat(email.get("overridden")).isEqualTo(true);
        // enum girişinde options yer alır
        Map<String, Object> logLevel = cat.stream()
                .filter(m -> "logging.level.com.sitemonitor".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(logLevel.get("options")).isEqualTo(List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));
    }

    // 2026-09-10: uyum onayı anahtarları katalogda YOKTU → gerçek save "Bilinmeyen ayar" atıyordu
    // (controller testi settingsService'i mock'ladığı için görünmüyordu).
    @org.junit.jupiter.api.Test
    void save_retentionApprovalKey_isKnownForEveryPolicy() {
        for (com.sitemonitor.service.retention.RetentionPolicy p : com.sitemonitor.service.retention.RetentionCatalog.ALL) {
            String key = AppSettingsCatalog.RETENTION_APPROVAL_PREFIX + p.id();
            org.assertj.core.api.Assertions.assertThat(AppSettingsCatalog.byKey(key))
                    .as("onay anahtarı katalogda olmalı: " + key).isNotNull();
            org.assertj.core.api.Assertions.assertThatCode(() -> service.save(values(key, "admin|2026-09-10T00:00:00|uygundur"), "admin"))
                    .as("gerçek save bilinmeyen-ayar atmamalı: " + key).doesNotThrowAnyException();
        }
    }

    // ── 2026-09-10: görev havuzu (executor) canlı ayar — alanlar-arası kural + değişiklik olayı ──

    @org.springframework.boot.test.context.TestConfiguration
    static class EventProbe {
        static final java.util.List<AppSettingsChangedEvent> EVENTS =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        @org.springframework.context.event.EventListener
        void on(AppSettingsChangedEvent ev) { EVENTS.add(ev); }
    }

    @Test
    @DisplayName("executor: core > max tek başına tip-geçerli olsa da BİRLİKTE reddedilir; hiçbir satır yazılmaz")
    void executor_crossFieldValidation_rejectsAtomically() {
        // properties: core 20 / max 50 / queue 5000. core=70 → max(50) < core → red
        assertThatThrownBy(() -> service.save(values("site.monitor.executor.core-size", "70"), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-size");
        assertThat(repo.findBySettingKey("site.monitor.executor.core-size")).isEmpty();
        assertThat(service.getInt("site.monitor.executor.core-size", -1)).isEqualTo(20);

        // İkisi birlikte gelirse (70/90) geçerli — tek istekte tutarlı çift kabul edilir
        Map<String, Object> inner = new HashMap<>();
        inner.put("site.monitor.executor.core-size", "70");
        inner.put("site.monitor.executor.max-size", "90");
        Map<String, Object> body = new HashMap<>();
        body.put("values", inner);
        service.save(body, "admin");
        assertThat(service.getInt("site.monitor.executor.core-size", -1)).isEqualTo(70);
        assertThat(service.getInt("site.monitor.executor.max-size", -1)).isEqualTo(90);

        // Kuyruk 0 reddedilir
        assertThatThrownBy(() -> service.save(values("site.monitor.executor.queue-capacity", "0"), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue-capacity");
    }

    @Test
    @DisplayName("save → AppSettingsChangedEvent yayınlanır (değişen anahtarlarla, source=save)")
    void save_publishesChangedEvent() {
        EventProbe.EVENTS.clear();
        service.save(values("site.monitor.executor.queue-capacity", "6000"), "admin");
        assertThat(EventProbe.EVENTS).hasSize(1);
        AppSettingsChangedEvent ev = EventProbe.EVENTS.get(0);
        assertThat(ev.source()).isEqualTo("save");
        assertThat(ev.changedKeys()).containsExactly("site.monitor.executor.queue-capacity");
        assertThat(ev.touches("site.monitor.executor.queue-capacity")).isTrue();
        assertThat(ev.touches("site.monitor.app.base-url")).isFalse();
    }
}
