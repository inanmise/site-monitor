package com.certmonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdminController}#validateDomain private-static girdi doğrulaması — envanter/monitör
 * ekleme akışının ilk savunma hattı (URL→host normalizasyonu + biçim/uzunluk sınırları).
 * Web-slice bootlamadan reflection'la doğrudan çağrılır; ağ yok, saf mantık.
 */
class AdminControllerValidateDomainTest {

    /** private static String validateDomain(String) → çağır, IllegalArgumentException'ı sar. */
    private static String validate(String domain) {
        try {
            Method m = AdminController.class.getDeclaredMethod("validateDomain", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, domain);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("validateDomain: null → IllegalArgumentException")
    void validateDomain_null_throws() {
        assertThatThrownBy(() -> validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("validateDomain: boşluk → IllegalArgumentException")
    void validateDomain_blank_throws() {
        assertThatThrownBy(() -> validate("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("validateDomain: 253 karakterden uzun host → 'too long'")
    void validateDomain_overlong_throws() {
        String overlong = "a".repeat(250) + ".com";   // 254 > 253
        assertThatThrownBy(() -> validate(overlong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("validateDomain: geçerli FQDN → host döner")
    void validateDomain_validFqdn_returnsHost() {
        assertThat(validate("www.example.com")).isEqualTo("www.example.com");
    }

    @Test
    @DisplayName("validateDomain: tam URL → şema/port/yol atılıp host'a normalize edilir")
    void validateDomain_fullUrl_normalizedToHost() {
        assertThat(validate("https://example.com:443/path?q=1")).isEqualTo("example.com");
    }

    @Test
    @DisplayName("validateDomain: tek etiket (localhost) → izinli (SSRF ayrı katmanda değerlendirilir)")
    void validateDomain_singleLabel_allowed() {
        assertThat(validate("localhost")).isEqualTo("localhost");
    }

    @Test
    @DisplayName("validateDomain: çift nokta (a..b.com) → 'Invalid domain format'")
    void validateDomain_doubleDot_throwsInvalidFormat() {
        assertThatThrownBy(() -> validate("a..b.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid domain format");
    }

    @Test
    @DisplayName("validateDomain: baştaki nokta (.example.com) → 'Invalid domain format'")
    void validateDomain_leadingDot_throwsInvalidFormat() {
        assertThatThrownBy(() -> validate(".example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid domain format");
    }

    @Test
    @DisplayName("validateDomain: alt çizgili etiket (under_score.com) → 'Invalid domain format' (DNS etiketi izin vermez)")
    void validateDomain_underscoreLabel_throwsInvalidFormat() {
        assertThatThrownBy(() -> validate("under_score.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid domain format");
    }
}
