package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Başlangıç etkin-konfigürasyon logunun bütünlüğü + kalıcı GÜVENLİK EMNİYET KEMERİ: test yapılandırmasına bilerek
 * konan sahte secret değerlerin ("test-password-123" gibi) log çıktısında HİÇBİR yerde geçmediğini doğrular —
 * ileride eklenecek yeni ayarların yanlışlıkla secret sızdırmasına karşı koruma.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "site.monitor.startup.config-log.enabled=true",
        "site.monitor.proxy.pass=test-password-123",
        "site.monitor.secret-key=super-secret-key-material-xyz"
})
class StartupConfigLoggerTest {

    @Autowired StartupLogger startupLogger;

    @Test
    @DisplayName("Etkin konfig logu: kategori başlıkları + örnek ayarlar VAR; sahte secret'lar HİÇ geçmez")
    void configLog_hasCategoriesAndMasksSecrets() {
        String out = startupLogger.renderConfig();

        // Beklenen kategori başlıkları
        assertThat(out)
                .contains("ETKİN KONFİGÜRASYON")
                .contains("Uygulama").contains("Sunucu").contains("Veritabanı")
                .contains("Güvenlik").contains("SMTP").contains("LDAP").contains("Ortam")
                .contains("Katalog ·");
        // Örnek ayarlar görünür
        assertThat(out).contains("server.port").contains("spring.datasource.url").contains("java.version");
        // Secret maskesi kullanılmış
        assertThat(out).contains(SecretMask.MASK);

        // EMNİYET KEMERİ: bilerek konan sahte secret'lar çıktının HİÇBİR yerinde geçmemeli
        assertThat(out)
                .doesNotContain("test-password-123")            // site.monitor.proxy.pass
                .doesNotContain("super-secret-key-material-xyz") // site.monitor.secret-key (yalnız durum loglanır)
                .doesNotContain("testpass");                     // test profili site.monitor.password
    }

    @Test
    @DisplayName("logEffectiveConfig() istisna fırlatmaz")
    void listener_doesNotThrow() {
        startupLogger.logEffectiveConfig();
    }
}
