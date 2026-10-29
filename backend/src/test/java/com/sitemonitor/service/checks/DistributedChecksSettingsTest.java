package com.sitemonitor.service.checks;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Çok pod Sürüm 3-3 P0 — iki ayar GERÇEK {@link AppSettingsService} (H2) üzerinden: varsayılan kapalı / sekiz tür, kaydedilen
 * değer CANLI okunur, tür listesi kayıtta doğrulanır (400 {@code VALIDATION_FAILED} alan {@code distributed_types}, hiçbir
 * şey yazılmaz), boş kayıt varsayılana döner; kayıt olayı ({@code AppSettingsChangedEvent}) Spring bean'ini etkinleştirir.
 */
@DataJpaTest
@Import({AppSettingsService.class, DistributedChecks.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // src/test/resources/application.properties ana dosyayı gölgeler — görev havuzu çapraz kuralı için (AppSettingsServiceTest)
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class DistributedChecksSettingsTest {

    @Autowired AppSettingsService settings;
    @Autowired DistributedChecks dc;

    /** Ayar servisinin bellek haritası bağlam boyunca yaşar (DB geri alınır) — her test varsayılandan başlasın. */
    @BeforeEach
    void reset() {
        settings.save(values(DistributedChecks.KEY_ENABLED, ""), "admin");
        settings.save(values(DistributedChecks.KEY_TYPES, ""), "admin");
    }

    private static Map<String, Object> values(String key, Object val) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(key, val);
        Map<String, Object> body = new HashMap<>();
        body.put("values", inner);
        return body;
    }

    @Test
    @DisplayName("varsayılan: ayar kaydedilmemişken kapalı, türler sekizi; bean açılış ayarlarını okudu (test ortamı → varsayılanlar)")
    void defaults() {
        assertThat(dc.enabled()).isFalse();
        assertThat(dc.types()).containsExactlyElementsOf(CheckJobTypes.ALL);
        assertThat(dc.anyDistributed()).isFalse();
        assertThat(dc.tuning()).isEqualTo(DistributedChecks.Tuning.DEFAULTS);
    }

    @Test
    @DisplayName("canlı: true / false kaydı hemen okunur; true/false dışı reddedilir; boş kayıt varsayılana (kapalı) döner")
    void enabledIsLive() {
        settings.save(values(DistributedChecks.KEY_ENABLED, "true"), "admin");
        assertThat(dc.enabled()).isTrue();
        assertThat(dc.anyDistributed()).isTrue();
        settings.save(values(DistributedChecks.KEY_ENABLED, "false"), "admin");
        assertThat(dc.enabled()).isFalse();
        assertThatThrownBy(() -> settings.save(values(DistributedChecks.KEY_ENABLED, "evet"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        settings.save(values(DistributedChecks.KEY_ENABLED, "true"), "admin");
        settings.save(values(DistributedChecks.KEY_ENABLED, ""), "admin");
        assertThat(dc.enabled()).as("override kaldırıldı → varsayılan").isFalse();
    }

    @Test
    @DisplayName("tür listesi: bilinen adlar kaydedilir ve canlı okunur; bilinmeyen ad → alan distributed_types, kayıt YOK; boş → sekiz tür")
    void typesValidatedOnSave() {
        settings.save(values(DistributedChecks.KEY_TYPES, "Ping, port"), "admin");
        assertThat(dc.types()).containsExactly("port", "ping");

        assertThatThrownBy(() -> settings.save(values(DistributedChecks.KEY_TYPES, "port,domain"), "admin"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields())
                        .containsOnlyKeys(CheckJobTypes.FIELD));
        assertThat(settings.getOverride(DistributedChecks.KEY_TYPES)).as("reddedilen değer yazılmadı").isEqualTo("Ping, port");
        assertThatThrownBy(() -> settings.save(values(DistributedChecks.KEY_TYPES, ", ,"), "admin"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);

        settings.save(values(DistributedChecks.KEY_TYPES, ""), "admin");
        assertThat(settings.getOverride(DistributedChecks.KEY_TYPES)).isNull();
        assertThat(dc.types()).containsExactlyElementsOf(CheckJobTypes.ALL);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)   // taze bağlam: başka testin açık kaydı etkinleştirmemiş olsun
    @DisplayName("kayıt olayı: gerçek ApplicationReadyEvent anahtar KAPALIYKEN etkinleştirmez; ana anahtarın kaydı (AppSettingsChangedEvent) başlatıcıları bir kez çalıştırır")
    void saveEventActivates() {
        assertThat(dc.activated()).as("bağlam açıldı (ApplicationReadyEvent), anahtar kapalı → etkinleşme yok").isFalse();
        List<String> ran = new ArrayList<>();
        dc.whenActivated("probe", () -> ran.add("probe"));
        settings.save(values(DistributedChecks.KEY_TYPES, "port"), "admin");
        settings.save(values(DistributedChecks.KEY_ENABLED, "false"), "admin");
        assertThat(ran).as("kapalı").isEmpty();
        settings.save(values(DistributedChecks.KEY_ENABLED, "true"), "admin");
        assertThat(ran).containsExactly("probe");
        settings.save(values(DistributedChecks.KEY_TYPES, "port,ping"), "admin");
        assertThat(ran).as("bir kez").containsExactly("probe");
    }
}
