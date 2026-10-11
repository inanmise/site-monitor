package com.sitemonitor.service;

import com.sitemonitor.service.otp.OtpCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gizli anahtar döndürme (2026-10-11): önceki anahtar YALNIZ çözer, şifreleme her zaman güncel anahtarla; biçim
 * değişmez ({@code enc:v1:}); yuva sınıflandırması; yeniden şifreleme yalnız önceki anahtardaki değeri değiştirir;
 * giriş kodu özeti önceki alt anahtarla da doğrulanır.
 */
class SecretCipherRotationTest {

    static SecretCipher cipher(String current, String previous) {
        SecretCipher c = new SecretCipher();
        try {
            var f = SecretCipher.class.getDeclaredField("configuredKey");
            f.setAccessible(true);
            f.set(c, current);
            var p = SecretCipher.class.getDeclaredField("previousKeyMaterial");
            p.setAccessible(true);
            p.set(c, previous);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        c.init();
        return c;
    }

    @Test
    @DisplayName("önceki anahtarla şifreli değer yeni anahtarlı uygulamada çözülür; yeni değer güncel anahtarla, aynı biçimde yazılır")
    void previousDecryptsCurrentEncrypts() {
        String old = cipher("eski-anahtar-111", null).encrypt("parola");
        SecretCipher rotated = cipher("yeni-anahtar-222", "eski-anahtar-111");
        assertThat(rotated.isPreviousKeyConfigured()).isTrue();
        assertThat(rotated.decrypt(old)).isEqualTo("parola");
        String fresh = rotated.encrypt("parola");
        assertThat(fresh).startsWith("enc:v1:");
        // Yeni değer önceki anahtarı bilmeyen (yalnız yeni anahtarlı) uygulamada da çözülür → güncel anahtarla şifreli
        assertThat(cipher("yeni-anahtar-222", null).decrypt(fresh)).isEqualTo("parola");
        assertThat(cipher("eski-anahtar-111", null).decrypt(fresh)).isNull();
    }

    @Test
    @DisplayName("önceki anahtar olmadan eski değer çözülmez (geriye uyum: tek anahtarda davranış aynı)")
    void withoutPreviousOldValueUnreadable() {
        String old = cipher("eski-anahtar-111", null).encrypt("parola");
        SecretCipher c = cipher("yeni-anahtar-222", "");
        assertThat(c.isPreviousKeyConfigured()).isFalse();
        assertThat(c.decrypt(old)).isNull();
        assertThat(c.classify(old)).isEqualTo(SecretCipher.KeySlot.UNREADABLE);
        assertThat(c.reencryptIfPrevious(old)).isNull();
    }

    @Test
    @DisplayName("classify: güncel / önceki / okunamayan; şifresiz değer null")
    void classify() {
        SecretCipher c = cipher("yeni-anahtar-222", "eski-anahtar-111");
        assertThat(c.classify(c.encrypt("a"))).isEqualTo(SecretCipher.KeySlot.CURRENT);
        assertThat(c.classify(cipher("eski-anahtar-111", null).encrypt("a"))).isEqualTo(SecretCipher.KeySlot.PREVIOUS);
        assertThat(c.classify(cipher("ucuncu-anahtar-333", null).encrypt("a"))).isEqualTo(SecretCipher.KeySlot.UNREADABLE);
        assertThat(c.classify("enc:v1:bozuk!!")).isEqualTo(SecretCipher.KeySlot.UNREADABLE);
        assertThat(c.classify("enc:v1:")).isEqualTo(SecretCipher.KeySlot.UNREADABLE);
        assertThat(c.classify("duz-metin")).isNull();
        assertThat(c.classify(null)).isNull();
        assertThat(SecretCipher.isEncrypted("enc:v1:x")).isTrue();
        assertThat(SecretCipher.isEncrypted("x")).isFalse();
    }

    @Test
    @DisplayName("reencryptIfPrevious: yalnız önceki anahtardaki değer yeniden yazılır; güncel / okunamayan / düz → null")
    void reencryptOnlyPrevious() {
        SecretCipher c = cipher("yeni-anahtar-222", "eski-anahtar-111");
        String old = cipher("eski-anahtar-111", null).encrypt("token-123");
        String next = c.reencryptIfPrevious(old);
        assertThat(next).startsWith("enc:v1:").isNotEqualTo(old);
        assertThat(c.classify(next)).isEqualTo(SecretCipher.KeySlot.CURRENT);
        assertThat(c.decrypt(next)).isEqualTo("token-123");
        assertThat(c.reencryptIfPrevious(next)).isNull();
        assertThat(c.reencryptIfPrevious(cipher("ucuncu-anahtar-333", null).encrypt("x"))).isNull();
        assertThat(c.reencryptIfPrevious("duz")).isNull();
    }

    @Test
    @DisplayName("önceki = güncel verilirse yok sayılır ve işaretlenir")
    void previousSameAsCurrentIgnored() {
        SecretCipher c = cipher("ayni-anahtar-999", "ayni-anahtar-999");
        assertThat(c.isPreviousKeyConfigured()).isFalse();
        assertThat(c.isPreviousSameAsCurrent()).isTrue();
    }

    @Test
    @DisplayName("giriş kodu: eski anahtarlı pod'un özeti döndürme sırasında doğrulanır; önceki anahtar kalkınca geçmez")
    void otpAcceptsPreviousSubKeyDuringRotation() {
        OtpCodes oldPod = new OtpCodes(cipher("eski-anahtar-111", null));
        String stored = oldPod.hmac("ch-1", "123456");
        OtpCodes rotating = new OtpCodes(cipher("yeni-anahtar-222", "eski-anahtar-111"));
        assertThat(rotating.matches("ch-1", "123456", stored)).isTrue();
        assertThat(rotating.matches("ch-1", "654321", stored)).isFalse();
        // Yeni özet her zaman güncel anahtarla alınır
        OtpCodes newOnly = new OtpCodes(cipher("yeni-anahtar-222", null));
        assertThat(newOnly.matches("ch-1", "123456", rotating.hmac("ch-1", "123456"))).isTrue();
        assertThat(newOnly.matches("ch-1", "123456", stored)).isFalse();
    }

    @Test
    @DisplayName("gömülü DEV anahtarı önceki anahtar olarak verilirse değerleri çözer ama ondan HMAC türetilmez")
    void devDefaultAsPrevious() {
        SecretCipher dev = new SecretCipher();
        dev.init();
        String old = dev.encrypt("x");
        SecretCipher c = cipher("gercek-anahtar-555", dev.devDefaultKey());
        assertThat(c.decrypt(old)).isEqualTo("x");
        assertThat(c.previousHmacSubKey("login-otp")).isNull();
        assertThat(c.hmacSubKey("login-otp")).isNotNull();
    }
}
