package com.sitemonitor.service;

import com.sitemonitor.model.HttpCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATA-14 (2026-10-22): {@code http_checks.error} VARCHAR(255) — uzun JSON doğrulama iletisi INSERT'i PostgreSQL'de
 * düşürüyor, çağıranın try/catch'i başarısız kontrolü geçmişten SESSİZCE siliyordu. Setter sığdırır.
 */
class HttpCheckErrorClipTest {

    @Test
    @DisplayName("255'ten uzun hata '…' ile 255'e sığar; kısa / null aynen kalır")
    void errorIsClipped() {
        HttpCheck c = new HttpCheck();
        c.setError("JSON doğrulaması başarısız: $." + "a".repeat(300) + " beklenen 'x' bulunan 'y'");
        assertThat(c.getError()).hasSize(HttpCheck.ERROR_MAX).endsWith("…");
        c.setError("HTTP 503");
        assertThat(c.getError()).isEqualTo("HTTP 503");
        c.setError(null);
        assertThat(c.getError()).isNull();
        c.setError("x".repeat(255));
        assertThat(c.getError()).hasSize(255).doesNotEndWith("…");
    }

    @Test
    @DisplayName("Kırpma vekil çifti ortadan bölmez")
    void clipKeepsSurrogatePairs() {
        String s = "a".repeat(253) + "😀" + "tail";
        String clipped = HttpCheck.clip(s, 255);
        assertThat(clipped).endsWith("a…");
        assertThat(clipped.length()).isLessThanOrEqualTo(255);
    }
}
