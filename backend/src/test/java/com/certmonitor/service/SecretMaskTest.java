package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "password", "spring.datasource.password", "spring.mail.password", "cert.monitor.password",
            "cert.monitor.proxy.pass", "cert.monitor.secret-key", "api-key", "apiKey", "x.api_key",
            "bindPasswordEnc", "passwordEnc", "auth.token", "x.salt", "tls.cipher", "db.credential",
            "server.private-key", "user.pwd", "PRIVATE" })
    @DisplayName("isSensitive: bilinen hassas kalıplar (substring + segment + camelCase) MASKELENİR")
    void sensitive_keysMasked(String key) {
        assertThat(SecretMask.isSensitive(key)).as(key).isTrue();
        assertThat(SecretMask.mask(key, "gizli-deger-123")).isEqualTo(SecretMask.MASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "cert.monitor.keyword.interval-ms", "cert.monitor.keyword.alert-enabled",
            "spring.datasource.hikari.keepalive-time", "server.port", "spring.datasource.hikari.maximum-pool-size",
            "cert.monitor.warning-days", "spring.datasource.username", "cert.monitor.username",
            "spring.datasource.url", "host", "cert.monitor.log.timezone",
            // Hassas kelime İÇEREN ama sır OLMAYAN politika değerleri (2026-08-05): denetimde tam da
            // bakılan alanlar loglarda ***** görünüyordu.
            "cert.monitor.password.min-length", "cert.monitor.password.max-length",
            "cert.monitor.password.history-count", "cert.monitor.scripted.hardcoded-secret-policy" })
    @DisplayName("isSensitive: benign anahtarlar (keyword/keepalive/user/port/politika…) MASKELENMEZ")
    void benign_keysOpen(String key) {
        assertThat(SecretMask.isSensitive(key)).as(key).isFalse();
        assertThat(SecretMask.mask(key, "acik-deger")).isEqualTo("acik-deger");
    }

    @Test
    @DisplayName("mask: null/boş değer → (ayarsız); hassas anahtar değeri ne olursa olsun maskeli")
    void mask_nullAndBlank() {
        assertThat(SecretMask.mask("host", null)).isEqualTo("(ayarsız)");
        assertThat(SecretMask.mask("host", "  ")).isEqualTo("(ayarsız)");
        assertThat(SecretMask.mask("password", null)).isEqualTo(SecretMask.MASK);
    }

    @Test
    @DisplayName("maskValues: bilinen secret DEĞERLERİ gövdede maskelenir; kısa değerler atlanır")
    void maskValues_body() {
        String out = "login OK user=svc-mon token=s3cr3t-value-xyz done";
        String masked = SecretMask.maskValues(out, java.util.List.of("s3cr3t-value-xyz", "ab"));
        assertThat(masked).contains(SecretMask.MASK).doesNotContain("s3cr3t-value-xyz");
        assertThat(SecretMask.maskValues(out, null)).isEqualTo(out);   // secretValues null → değişmez
        assertThat(SecretMask.maskValues("x ab y", java.util.List.of("ab"))).isEqualTo("x ab y"); // ≤3 krk atlanır
    }

    @Test
    @DisplayName("maskJdbcUrl: gömülü user:pass ve query kimlik ayıklanır; host/port/db görünür kalır")
    void maskJdbcUrl_extractsCredentials() {
        String a = SecretMask.maskJdbcUrl("jdbc:postgresql://certuser:s3cr3t@db-host:5432/certmonitor");
        assertThat(a).contains("db-host:5432/certmonitor")
                     .contains(SecretMask.MASK + ":" + SecretMask.MASK + "@")
                     .doesNotContain("certuser").doesNotContain("s3cr3t");

        String b = SecretMask.maskJdbcUrl("jdbc:postgresql://db-host:5432/certmonitor?user=certuser&password=s3cr3t&ssl=true");
        assertThat(b).contains("db-host:5432/certmonitor").contains("ssl=true")
                     .doesNotContain("s3cr3t").doesNotContain("certuser");

        assertThat(SecretMask.maskJdbcUrl(null)).isEqualTo("(ayarsız)");
    }
}
