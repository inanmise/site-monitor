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
        "logging.level.com.sitemonitor=DEBUG"
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
}
