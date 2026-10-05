package com.sitemonitor.service;

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
 * Tema politikası (2026-10-05) GENEL kayıt yolunda da korunur: {@code AppSettingsService.save} hangi denetleyiciden
 * çağrılırsa çağrılsın (Genel Ayarlar ucu dahil) bilinmeyen kimliği, boş listeyi, kapalı varsayılanı ve Açık/Koyu'suz
 * {@code system}'i reddeder; tema anahtarına dokunmayan kayıt bu kurala takılmaz.
 */
@DataJpaTest
@Import(AppSettingsService.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class AppSettingsThemeValidationTest {

    @Autowired AppSettingsService service;

    /** Servis örneği (ve bellek içi override haritası) testler arasında paylaşılır → her test varsayılan politikadan başlar. */
    @org.junit.jupiter.api.BeforeEach
    void reset() {
        service.save(values(ThemeCatalog.KEY_ENABLED, "", ThemeCatalog.KEY_DEFAULT, ""), "admin");
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> inner = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) inner.put((String) kv[i], kv[i + 1]);
        return Map.of("values", inner);
    }

    @Test
    @DisplayName("geçerli politika kaydedilir ve publicView'e yansır")
    void validPolicy_saved() {
        service.save(values(ThemeCatalog.KEY_ENABLED, "light,dark,crucible", ThemeCatalog.KEY_DEFAULT, "crucible"), "admin");
        assertThat(ThemeCatalog.publicView(service))
                .containsEntry("default", "crucible")
                .containsEntry("enabled", java.util.List.of("light", "dark", "crucible"));
    }

    @Test
    @DisplayName("bilinmeyen tema kimliği → 400")
    void unknownId_rejected() {
        assertThatThrownBy(() -> service.save(values(ThemeCatalog.KEY_ENABLED, "light,dark,sepia"), "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sepia");
    }

    @Test
    @DisplayName("varsayılan ENUM dışı → 400; listede kapalı varsayılan → 400")
    void defaultRules() {
        assertThatThrownBy(() -> service.save(values(ThemeCatalog.KEY_DEFAULT, "sepia"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(
                values(ThemeCatalog.KEY_ENABLED, "light,dark", ThemeCatalog.KEY_DEFAULT, "slag"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // Yalnız listeyi daraltmak da mevcut varsayılanı (ek tema) kapatıyorsa reddedilir.
        service.save(values(ThemeCatalog.KEY_DEFAULT, "slag"), "admin");
        assertThatThrownBy(() -> service.save(values(ThemeCatalog.KEY_ENABLED, "light,dark"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("system varsayılanı Açık ve Koyu olmadan → 400")
    void systemNeedsLightAndDark() {
        assertThatThrownBy(() -> service.save(
                values(ThemeCatalog.KEY_ENABLED, "light,blueprint", ThemeCatalog.KEY_DEFAULT, "system"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("boş liste = override kaldırma (sekizine dönüş) — reddedilmez")
    void emptyList_revertsToDefault() {
        service.save(values(ThemeCatalog.KEY_ENABLED, "light,dark,obsidian"), "admin");
        service.save(values(ThemeCatalog.KEY_ENABLED, ""), "admin");
        assertThat(ThemeCatalog.publicView(service).get("enabled")).isEqualTo(ThemeCatalog.IDS);
    }

    @Test
    @DisplayName("tema anahtarına dokunmayan kayıt tema kuralına takılmaz")
    void unrelatedSave_notBlocked() {
        service.save(values("site.monitor.network.min-errors", "4"), "admin");
        assertThat(service.getInt("site.monitor.network.min-errors", 3)).isEqualTo(4);
    }
}
