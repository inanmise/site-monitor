package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sürüm çözümü ve KAYNAĞI. Prod pod'unda VERSION dosyası imaja kopyalanmadığı için sürüm sessizce jar
 * manifest'ine (pom {@code <version>} — release CI bump'lamıyor, bayat) düşüyor ve açılış logu yanlış
 * sürüm raporluyordu; üstelik etiket sabit "file" yazdığı için sorun maskeleniyordu (2026-08-05).
 */
class AppVersionTest {

    @Test
    @DisplayName("site.monitor.version property'si varsa kazanır ve kaynak 'env' olur")
    void propertyWins_sourceEnv() {
        MockEnvironment env = new MockEnvironment().withProperty("site.monitor.version", " 19.93.1 ");
        AppVersion.Resolved r = AppVersion.resolveWithSource(env);
        assertThat(r.version()).isEqualTo("19.93.1");
        assertThat(r.source()).isEqualTo("env");
    }

    @Test
    @DisplayName("property boşsa dosya adaylarına düşer; hiçbiri yoksa manifest/none — kaynak asla uydurulmaz")
    void fallsBackAndReportsRealSource() {
        AppVersion.Resolved r = AppVersion.resolveWithSource(new MockEnvironment());
        // Testler backend/ dizininden koşar → ../VERSION bulunur; CI/jar bağlamında manifest ya da none.
        assertThat(r.source()).isIn("file", "manifest", "none");
        assertThat(r.version()).isNotBlank();
        if ("none".equals(r.source())) assertThat(r.version()).isEqualTo("unknown");
        assertThat(AppVersion.resolve(new MockEnvironment())).isEqualTo(r.version());   // eski API aynı sonucu verir
    }
}
