package com.sitemonitor.service.otp;

import com.sitemonitor.service.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kodla giriş kriptografisi (2026-10-02): 6 haneli kod (baştaki sıfırlar korunur), HMAC challenge kimliğine bağlı,
 * sabit-zamanlı eşleşme, anahtar SITE_MONITOR_SECRET_KEY'den türetilir (pod'lar arası aynı), anahtar yoksa süreç başına
 * rastgele (gömülü DEV anahtarına düşmez).
 */
class OtpCodesTest {

    private static SecretCipher cipher(String key) {
        SecretCipher c = new SecretCipher();
        ReflectionTestUtils.setField(c, "configuredKey", key);
        ReflectionTestUtils.setField(c, "activeProfiles", "");
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    @Test
    @DisplayName("kod: daima 6 rakam, 000000–999999 aralığından (baştaki sıfırlar korunur), dağılım tek değere çökmez")
    void codeFormat() {
        OtpCodes codes = new OtpCodes(null);
        Set<String> seen = new HashSet<>();
        boolean leadingZero = false;
        for (int i = 0; i < 5000; i++) {
            String c = codes.newCode();
            assertThat(c).matches("\\d{6}");
            if (c.startsWith("0")) leadingZero = true;
            seen.add(c);
        }
        assertThat(seen.size()).isGreaterThan(4900);
        assertThat(leadingZero).as("baştaki sıfır korunmalı (%06d)").isTrue();
    }

    @Test
    @DisplayName("HMAC: 64 hex, kodu İÇERMEZ; aynı kod başka challenge kimliğiyle EŞLEŞMEZ; yanlış/bozuk kod eşleşmez")
    void hmacBindsChallenge() {
        OtpCodes codes = new OtpCodes(cipher("prod-secret-ABC"));
        String id = "11111111-2222-3333-4444-555555555555";
        String h = codes.hmac(id, "123456");
        assertThat(h).matches("[0-9a-f]{64}").doesNotContain("123456");
        assertThat(codes.matches(id, "123456", h)).isTrue();
        assertThat(codes.matches("11111111-2222-3333-4444-555555555556", "123456", h)).isFalse();
        assertThat(codes.matches(id, "123457", h)).isFalse();
        assertThat(codes.matches(id, "", h)).isFalse();
        assertThat(codes.matches(id, null, h)).isFalse();
        assertThat(codes.matches(id, "123456", null)).isFalse();
    }

    @Test
    @DisplayName("anahtar SITE_MONITOR_SECRET_KEY'den türetilir: aynı anahtarlı iki pod aynı özeti üretir, farklı anahtar farklı")
    void derivedKeyIsStableAcrossPods() {
        OtpCodes podA = new OtpCodes(cipher("prod-secret-ABC"));
        OtpCodes podB = new OtpCodes(cipher("prod-secret-ABC"));
        OtpCodes other = new OtpCodes(cipher("another-secret"));
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertThat(podA.ephemeralKey()).isFalse();
        assertThat(podA.hmac(id, "000042")).isEqualTo(podB.hmac(id, "000042"));
        assertThat(podB.matches(id, "000042", podA.hmac(id, "000042"))).isTrue();
        assertThat(other.hmac(id, "000042")).isNotEqualTo(podA.hmac(id, "000042"));
    }

    @Test
    @DisplayName("anahtar YOKSA gömülü DEV anahtarına düşülmez: süreç başına rastgele anahtar (iki örnek farklı özet üretir)")
    void noKey_ephemeralRandomKey() {
        SecretCipher noKey = cipher("");
        assertThat(noKey.hmacSubKey("login-otp")).isNull();
        OtpCodes a = new OtpCodes(noKey);
        OtpCodes b = new OtpCodes(noKey);
        assertThat(a.ephemeralKey()).isTrue();
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertThat(a.hmac(id, "123456")).isNotEqualTo(b.hmac(id, "123456"));
        // DEV anahtarı açıkça ayarlansa da (güvensiz) türetme yapılmaz
        assertThat(cipher("certmonitor-dev-secret-change-me").hmacSubKey("login-otp")).isNull();
    }

    @Test
    @DisplayName("alt anahtar AES anahtarından ve amaçtan ayrışır (alan ayrımı)")
    void subKeyDomainSeparation() {
        SecretCipher c = cipher("prod-secret-ABC");
        assertThat(c.hmacSubKey("login-otp")).isNotEqualTo(c.hmacSubKey("other-purpose")).hasSize(32);
    }
}
