package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.AppSettingsCatalog;
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
 * Çok pod Sürüm 3-4 P0 — anahtar GERÇEK {@link AppSettingsService} (H2) üzerinden: varsayılan kapalı, kaydedilen değer CANLI
 * okunur, mantıksal olmayan değer reddedilir, boş kayıt varsayılana döner; kayıt olayı ({@code AppSettingsChangedEvent}) Spring
 * bean'ini etkinleştirir, gerçek {@code ApplicationReadyEvent} anahtar kapalıyken ETKİNLEŞTİRMEZ (L-1); 3-3 anahtarının kaydı
 * etkinleştirmez (bağımsız).
 */
@DataJpaTest
@Import({AppSettingsService.class, CertChunking.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // src/test/resources/application.properties ana dosyayı gölgeler — görev havuzu çapraz kuralı için (AppSettingsServiceTest)
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class CertChunkingSettingsTest {

    @Autowired AppSettingsService settings;
    @Autowired CertChunking cc;

    /** Ayar servisinin bellek haritası bağlam boyunca yaşar (DB geri alınır) — her test varsayılandan başlasın. */
    @BeforeEach
    void reset() {
        settings.save(values(CertChunking.KEY_ENABLED, ""), "admin");
    }

    private static Map<String, Object> values(String key, Object val) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(key, val);
        Map<String, Object> body = new HashMap<>();
        body.put("values", inner);
        return body;
    }

    @Test
    @DisplayName("katalog: BOOL, grup scheduler, GLOBAL_ONLY; bean açılış ayarlarını okudu (test ortamı → varsayılanlar); varsayılan kapalı")
    void defaults() {
        AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(CertChunking.KEY_ENABLED);
        assertThat(s).isNotNull();
        assertThat(s.group()).isEqualTo("scheduler");
        assertThat(s.type()).isEqualTo(AppSettingsCatalog.Type.BOOL);
        assertThat(AppSettingsCatalog.isGlobalOnly(CertChunking.KEY_ENABLED)).isTrue();
        assertThat(cc.enabled()).isFalse();
        assertThat(cc.tuning()).isEqualTo(CertChunking.Tuning.DEFAULTS);
    }

    @Test
    @DisplayName("canlı: true / false kaydı hemen okunur; true/false dışı reddedilir; boş kayıt varsayılana (kapalı) döner")
    void enabledIsLive() {
        settings.save(values(CertChunking.KEY_ENABLED, "true"), "admin");
        assertThat(cc.enabled()).isTrue();
        settings.save(values(CertChunking.KEY_ENABLED, "false"), "admin");
        assertThat(cc.enabled()).isFalse();
        assertThatThrownBy(() -> settings.save(values(CertChunking.KEY_ENABLED, "evet"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.getOverride(CertChunking.KEY_ENABLED)).as("reddedilen değer yazılmadı").isEqualTo("false");
        settings.save(values(CertChunking.KEY_ENABLED, "true"), "admin");
        settings.save(values(CertChunking.KEY_ENABLED, ""), "admin");
        assertThat(settings.getOverride(CertChunking.KEY_ENABLED)).isNull();
        assertThat(cc.enabled()).as("override kaldırıldı → varsayılan").isFalse();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)   // taze bağlam: başka testin açık kaydı etkinleştirmemiş olsun
    @DisplayName("kayıt olayı: gerçek ApplicationReadyEvent anahtar KAPALIYKEN etkinleştirmez; 3-3 anahtarı tetiklemez; kendi anahtarının kaydı bir kez")
    void saveEventActivates() {
        assertThat(cc.activated()).as("bağlam açıldı (ApplicationReadyEvent), anahtar kapalı → etkinleşme yok").isFalse();
        List<String> ran = new ArrayList<>();
        cc.whenActivated("probe", () -> ran.add("probe"));
        settings.save(values(CertChunking.KEY_ENABLED, "false"), "admin");
        settings.save(values("site.monitor.checks.distributed.enabled", "true"), "admin");
        assertThat(ran).as("kapalı / başka anahtar").isEmpty();
        settings.save(values("site.monitor.checks.distributed.enabled", ""), "admin");
        settings.save(values(CertChunking.KEY_ENABLED, "true"), "admin");
        assertThat(ran).containsExactly("probe");
        settings.save(values(CertChunking.KEY_ENABLED, "false"), "admin");
        settings.save(values(CertChunking.KEY_ENABLED, "true"), "admin");
        assertThat(ran).as("bir kez").containsExactly("probe");
    }
}
