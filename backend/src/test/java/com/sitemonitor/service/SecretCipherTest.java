package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherTest {

    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new SecretCipher();
        cipher.init(); // no configured key → uses dev default
    }

    @Test
    @DisplayName("encrypt → decrypt round-trips and ciphertext differs from plaintext")
    void roundTrip() {
        String secret = "S3rvic3-Acc0unt-P@ss";
        String enc = cipher.encrypt(secret);
        assertThat(enc).startsWith("enc:v1:").isNotEqualTo(secret);
        assertThat(cipher.decrypt(enc)).isEqualTo(secret);
    }

    @Test
    @DisplayName("encrypt produces a fresh IV each call (different ciphertext, same plaintext)")
    void freshIvPerCall() {
        String a = cipher.encrypt("same");
        String b = cipher.encrypt("same");
        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("same");
        assertThat(cipher.decrypt(b)).isEqualTo("same");
    }

    @Test
    @DisplayName("null/blank pass through; non-prefixed decrypt returns input unchanged")
    void edgeCases() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("   ")).isNull();
        assertThat(cipher.decrypt("plain-not-prefixed")).isEqualTo("plain-not-prefixed");
    }

    @Test
    @DisplayName("decrypt with a different key fails closed (returns null, no throw)")
    void wrongKeyFailsClosed() {
        String enc = cipher.encrypt("topsecret");
        SecretCipher other = new SecretCipher();
        try {
            var f = SecretCipher.class.getDeclaredField("configuredKey");
            f.setAccessible(true);
            f.set(other, "a-completely-different-key");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        other.init();
        assertThat(other.decrypt(enc)).isNull();
    }

    @Test
    @DisplayName("decryptWith: doğru materyalle çözer, yanlış/boş/prefixsizde null")
    void decryptWith_rightAndWrong() {
        String enc = cipher.encrypt("creds");                      // dev default ile şifreli
        assertThat(cipher.decryptWith(enc, cipher.devDefaultKey())).isEqualTo("creds");
        assertThat(cipher.decryptWith(enc, "baska-anahtar")).isNull();
        assertThat(cipher.decryptWith(null, "k")).isNull();
        assertThat(cipher.decryptWith("plain-not-prefixed", "k")).isNull();
        assertThat(cipher.decryptWith(enc, "   ")).isNull();
    }

    @Test
    @DisplayName("isKeyConfigured: anahtar yokken false, ayarlıyken true")
    void isKeyConfigured_flag() {
        assertThat(cipher.isKeyConfigured()).isFalse();            // setUp: configuredKey yok
        SecretCipher configured = new SecretCipher();
        try {
            var f = SecretCipher.class.getDeclaredField("configuredKey");
            f.setAccessible(true);
            f.set(configured, "real-strong-key");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        configured.init();
        assertThat(configured.isKeyConfigured()).isTrue();
    }

    @Test
    @DisplayName("devDefaultKey boş değil ve onunla şifrelenen çözülebilir")
    void devDefaultKey_present() {
        assertThat(cipher.devDefaultKey()).isNotBlank();
        assertThat(cipher.decryptWith(cipher.encrypt("x"), cipher.devDefaultKey())).isEqualTo("x");
    }
}
