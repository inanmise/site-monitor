package com.sitemonitor.service;

import com.sitemonitor.model.AppSetting;
import com.sitemonitor.repository.AppSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ortam adı Genel Ayarlar'dan (2026-09-29, kullanıcı isteği): değer PostgreSQL'de (app_settings) saklanır ve
 * kaydedince YENİDEN BAŞLATMASIZ yansır. Öncelik DB ayarı &gt; APP_ENVIRONMENT (burada {@code staging}) &gt;
 * otomatik. Gerçek AppSettingsService + BuildInfo, H2 üzerinde.
 */
@DataJpaTest
@Import({AppSettingsService.class, BuildInfo.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "site.monitor.environment=staging",          // Helm config.environmentName → APP_ENVIRONMENT
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class EnvironmentNameSettingTest {

    @Autowired AppSettingsService settings;
    @Autowired AppSettingRepository repo;
    @Autowired BuildInfo buildInfo;

    private static Map<String, Object> values(String key, Object val) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(key, val);
        return Map.of("values", inner);
    }

    /** Servis bağlamla birlikte ÖNBELLEKLENİR; bellek haritası testler arası sızmasın (DB her test sonunda geri alınır). */
    @org.junit.jupiter.api.BeforeEach
    void resetOverrides() {
        settings.refreshFromDb();
    }

    private Map<String, Object> catalogItem() {
        return settings.getCatalogForClient().stream()
                .filter(m -> BuildInfo.ENV_KEY.equals(m.get("key"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("katalogda: general grubu, STRING, GLOBAL_ONLY; varsayılan = APP_ENVIRONMENT")
    void catalogEntry() {
        AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(BuildInfo.ENV_KEY);
        assertThat(s).isNotNull();
        assertThat(s.group()).isEqualTo("general");
        assertThat(s.type()).isEqualTo(AppSettingsCatalog.Type.STRING);
        assertThat(AppSettingsCatalog.isGlobalOnly(BuildInfo.ENV_KEY)).isTrue();
        assertThat(catalogItem()).containsEntry("default", "staging").containsEntry("global_only", true);
    }

    @Test
    @DisplayName("kaydet → DB satırı + BuildInfo AYNI anda yeni ad (yeniden başlatma yok); boşalt → APP_ENVIRONMENT")
    void save_isLive_andClearFallsBackToEnv() {
        assertThat(buildInfo.get().environment()).isEqualTo("staging");
        assertThat(buildInfo.environmentName().source()).isEqualTo(BuildInfo.EnvSource.ENV);

        settings.save(values(BuildInfo.ENV_KEY, "prod"), "admin");
        assertThat(repo.findBySettingKey(BuildInfo.ENV_KEY)).get()
                .extracting(AppSetting::getValue, AppSetting::getUpdatedBy).containsExactly("prod", "admin");
        assertThat(buildInfo.get().environment()).isEqualTo("prod");
        assertThat(settings.environmentName()).isEqualTo(new BuildInfo.EnvName("prod", BuildInfo.EnvSource.SETTING));

        settings.save(values(BuildInfo.ENV_KEY, ""), "admin");
        assertThat(buildInfo.get().environment()).isEqualTo("staging");
        assertThat(buildInfo.environmentName().source()).isEqualTo(BuildInfo.EnvSource.ENV);
    }

    @Test
    @DisplayName("tel biçimi: katalog kalemi etkin adı ve kaynağını SNAKE_CASE taşır (effective / effective_source)")
    void catalog_effectiveFields_snakeCase() {
        assertThat(catalogItem()).containsEntry("effective", "staging").containsEntry("effective_source", "env")
                .doesNotContainKey("effectiveSource");
        settings.save(values(BuildInfo.ENV_KEY, "prod"), "admin");
        assertThat(catalogItem()).containsEntry("value", "prod").containsEntry("overridden", true)
                .containsEntry("effective", "prod").containsEntry("effective_source", "setting");
        // Diğer kalemler bu alanları taşımaz (genel katalog şekli değişmedi).
        assertThat(settings.getCatalogForClient().stream()
                .filter(m -> !BuildInfo.ENV_KEY.equals(m.get("key")))
                .noneMatch(m -> m.containsKey("effective"))).isTrue();
    }

    @Test
    @DisplayName("doğrulama: büyük harf, alt çizgi, boşluk, 41 karakter → IllegalArgumentException (400); hiçbir şey yazılmaz")
    void invalidNames_rejected() {
        for (String bad : new String[] {"Prod", "prod_eu", "prod eu", "x".repeat(41), "préprod"}) {
            assertThatThrownBy(() -> settings.save(values(BuildInfo.ENV_KEY, bad), "admin"))
                    .as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ortam adı geçersiz");
        }
        assertThat(repo.findBySettingKey(BuildInfo.ENV_KEY)).isEmpty();
        assertThat(buildInfo.get().environment()).isEqualTo("staging");
        // Kenar değerleri geçerli
        settings.save(values(BuildInfo.ENV_KEY, "x".repeat(40)), "admin");
        settings.save(values(BuildInfo.ENV_KEY, "prod-eu-2"), "admin");
        assertThat(buildInfo.get().environment()).isEqualTo("prod-eu-2");
    }

    @Test
    @DisplayName("çok pod: başka pod'un yazdığı ad tazelemeyle (~10 sn) bu pod'a yansır")
    void refreshFromDb_otherInstanceWrite() {
        repo.save(new AppSetting(BuildInfo.ENV_KEY, "dev", "2026-09-29T10:00:00", "admin"));
        assertThat(buildInfo.get().environment()).as("tazeleme öncesi eski değer").isEqualTo("staging");
        settings.refreshFromDb();
        assertThat(buildInfo.get().environment()).isEqualTo("dev");
        assertThat(buildInfo.environmentName().source()).isEqualTo(BuildInfo.EnvSource.SETTING);
    }
}
