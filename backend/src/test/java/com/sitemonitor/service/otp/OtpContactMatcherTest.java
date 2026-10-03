package com.sitemonitor.service.otp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kodla giriş kişi bilgisi eşleştirmesi (2026-10-03, kullanıcı isteği) — telefon normalleştirme tablosu (biçimler, ülke
 * kodu / trunk 0, kısa numara, Unicode rakamları, yabancı numara) ve e-posta (kırpma + büyük/küçük harf). Örnek numaralar
 * yer tutucudur (0500 000 00 00 ailesi) — gerçek veri YOK.
 */
class OtpContactMatcherTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "0500 000 00 00        | 5000000000",
            "05000000000           | 5000000000",
            "5000000000            | 5000000000",
            "+90 (500) 000-00-00   | 5000000000",
            "+90 500 000 00 00     | 5000000000",
            "90 500 000 00 00      | 5000000000",
            "905000000000          | 5000000000",
            "0090 500 000 00 00    | 5000000000",
            "00905000000000        | 5000000000",
            "+90 0500 000 00 00    | 5000000000",
            "0 (500) 000-00-00     | 5000000000",
            "0500.000.00.00        | 5000000000",
            "tel: 0500 000 00 00   | 5000000000",
            "+44 7700 900000       | 7700900000",
            "0500 000 00           | NULL",
            "500 000 00 0          | NULL",
            "05000000              | NULL",
            "0500000000            | NULL",
            "abc                   | NULL",
            "''                    | NULL",
    })
    @DisplayName("telefon normalleştirme: yalnız rakam, 0090 / 90 / trunk 0 düşer, SON 10 hane; < 10 hane → null")
    void normalizePhone_table(String raw, String expected) {
        assertThat(OtpContactMatcher.normalizePhone(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Unicode rakamları (tam genişlik, Arap-Hint) ASCII'ye çevrilir; null ve aşırı uzun girdi eşleşmez")
    void normalizePhone_unicodeAndLimits() {
        assertThat(OtpContactMatcher.normalizePhone("０５００ ０００ ００ ００"))
                .isEqualTo("5000000000");
        assertThat(OtpContactMatcher.normalizePhone("٠٥٠٠٠٠٠٠٠٠١"))
                .isEqualTo("5000000001");
        assertThat(OtpContactMatcher.normalizePhone(null)).isNull();
        assertThat(OtpContactMatcher.normalizePhone("0500 000 00 00" + " ".repeat(60))).isNull();
    }

    @Test
    @DisplayName("telefon eşleşmesi: her biçim aynı numarayı bulur; farklı numara, kısa numara ve kayıtsız telefon eşleşmez")
    void phoneMatches() {
        String registered = "+90 500 000 00 00";
        for (String typed : new String[] { "0500 000 00 00", "5000000000", "+90 (500) 000-00-00", "0090 500 000 00 00" }) {
            assertThat(OtpContactMatcher.phoneMatches(typed, registered)).as(typed).isTrue();
        }
        assertThat(OtpContactMatcher.phoneMatches("0500 000 00 01", registered)).isFalse();
        assertThat(OtpContactMatcher.phoneMatches("000 00 00", registered)).as("son 7 hane yetmez").isFalse();
        assertThat(OtpContactMatcher.phoneMatches("0500 000 00 00", null)).isFalse();
        assertThat(OtpContactMatcher.phoneMatches("0500 000 00 00", "1234")).as("kayıtlı numara kısa").isFalse();
        assertThat(OtpContactMatcher.phoneMatches(null, registered)).isFalse();
        assertThat(OtpContactMatcher.usablePhone(registered)).isTrue();
        assertThat(OtpContactMatcher.usablePhone("2345")).isFalse();
        assertThat(OtpContactMatcher.usablePhone("  ")).isFalse();
    }

    @Test
    @DisplayName("e-posta eşleşmesi: kırpma + büyük/küçük harf duyarsız TAM eşleşme; alt dize / başka adres / boş eşleşmez")
    void emailMatches() {
        String registered = "alice@example.com";
        assertThat(OtpContactMatcher.emailMatches("alice@example.com", registered)).isTrue();
        assertThat(OtpContactMatcher.emailMatches("  ALICE@Example.COM  ", registered)).isTrue();
        assertThat(OtpContactMatcher.emailMatches("alice@example.com", "  Alice@EXAMPLE.com ")).isTrue();
        assertThat(OtpContactMatcher.emailMatches("alice@example.co", registered)).isFalse();
        assertThat(OtpContactMatcher.emailMatches("lice@example.com", registered)).isFalse();
        assertThat(OtpContactMatcher.emailMatches("bob@example.com", registered)).isFalse();
        assertThat(OtpContactMatcher.emailMatches("", registered)).isFalse();
        assertThat(OtpContactMatcher.emailMatches(" ", " ")).isFalse();
        assertThat(OtpContactMatcher.emailMatches(null, registered)).isFalse();
        assertThat(OtpContactMatcher.emailMatches("alice@example.com", null)).isFalse();
        assertThat(OtpContactMatcher.emailMatches("a".repeat(300) + "@example.com", "a".repeat(300) + "@example.com")).isFalse();
    }
}
