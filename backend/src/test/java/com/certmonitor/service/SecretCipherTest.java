package com.certmonitor.service;

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
}
