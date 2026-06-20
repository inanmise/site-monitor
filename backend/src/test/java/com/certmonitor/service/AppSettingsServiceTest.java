package com.certmonitor.service;

import com.certmonitor.repository.AppSettingRepository;
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
        "cert.monitor.scheduler.stale-minutes=65",
        "logging.level.com.certmonitor=DEBUG"
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
        service.save(values("cert.monitor.app.base-url", "https://prod.example.com"), "admin");
        assertThat(service.getString("cert.monitor.app.base-url", "fallback"))
                .isEqualTo("https://prod.example.com");

        service.save(values("cert.monitor.app.base-url", ""), "admin");
        assertThat(service.getString("cert.monitor.app.base-url", "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("tipli getter'lar: int/double/bool override")
    void typedGetters() {
        service.save(values("cert.monitor.network.min-errors", "7"), "admin");
        service.save(values("cert.monitor.network.error-rate-threshold", "0.8"), "admin");
        service.save(values("cert.monitor.uptime.alert-enabled", "false"), "admin");
        assertThat(service.getInt("cert.monitor.network.min-errors", 3)).isEqualTo(7);
        assertThat(service.getDouble("cert.monitor.network.error-rate-threshold", 0.5)).isEqualTo(0.8);
        assertThat(service.getBoolean("cert.monitor.uptime.alert-enabled", true)).isFalse();
    }

    @Test
    @DisplayName("override yoksa Environment varsayılanı (stale-minutes) okunur")
    void defaultFromEnvironment() {
        assertThat(service.getInt("cert.monitor.scheduler.stale-minutes", 999)).isEqualTo(65);
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
        assertThatThrownBy(() -> service.save(values("cert.monitor.network.min-errors", "abc"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(values("logging.level.com.certmonitor", "VERBOSE"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getCatalogForClient tüm katalog key'lerini + override durumunu döner")
    void catalogForClient() {
        service.save(values("cert.monitor.system-admin.email", "ops@example.com"), "admin");
        List<Map<String, Object>> cat = service.getCatalogForClient();
        assertThat(cat).hasSize(AppSettingsCatalog.ALL.size());
        Map<String, Object> email = cat.stream()
                .filter(m -> "cert.monitor.system-admin.email".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(email.get("value")).isEqualTo("ops@example.com");
        assertThat(email.get("overridden")).isEqualTo(true);
        // enum girişinde options yer alır
        Map<String, Object> logLevel = cat.stream()
                .filter(m -> "logging.level.com.certmonitor".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(logLevel.get("options")).isEqualTo(List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));
    }
}
