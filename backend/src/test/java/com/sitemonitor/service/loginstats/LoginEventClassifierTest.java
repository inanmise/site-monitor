package com.sitemonitor.service.loginstats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giriş olayı sınıflandırması (2026-10-03, Giriş Yöntemleri → İstatistikler) — kanal / tahmin / bilinmeyen kullanıcı /
 * neden TABLOSU. Hesaplar: USER-A yerel, USER-B LDAP; GHOST hiçbir hesaba çözülmez.
 */
class LoginEventClassifierTest {

    private static final Map<String, String> ACCOUNTS = Map.of("user-a", "LOCAL", "user-b", "LDAP");
    private static final Function<String, String> SOURCE = actor -> ACCOUNTS.get(actor.toLowerCase(java.util.Locale.ROOT));

    @ParameterizedTest(name = "[{index}] {0} {1} {2} {3} → {4} {5} est={6} unknown={7} reason={8} otp={9}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            // ── başarılı giriş: yöntem ayrıntıdan ──
            "LOGIN        | USER-A | -                                           | {\"method\":\"LOCAL\"}       | SUCCESS | LOCAL       | false | false | -            | -",
            "LOGIN        | USER-B | -                                           | {\"method\":\"LDAP\"}        | SUCCESS | LDAP        | false | false | -            | -",
            "LOGIN        | USER-A | -                                           | {\"method\":\"REMEMBER_ME\"} | SUCCESS | REMEMBER_ME | false | false | -            | -",
            "LOGIN        | USER-A | -                                           | {\"method\":\"OTP_PUSH\"}    | SUCCESS | OTP_PUSH    | false | false | -            | OTP_PUSH",
            "LOGIN        | user-b | -                                           | {\"method\":\"OTP_EMAIL\"}   | SUCCESS | OTP_EMAIL   | false | false | -            | OTP_EMAIL",
            // ── eski satır (yöntemsiz): hesap kaynağına göre TAHMİN; hesap yoksa kanalsız ──
            "LOGIN        | USER-B | -                                           | -                          | SUCCESS | LDAP        | true  | false | -            | -",
            "LOGIN        | user-a | -                                           | -                          | SUCCESS | LOCAL       | true  | false | -            | -",
            "LOGIN        | GHOST  | -                                           | -                          | SUCCESS | -           | false | true  | -            | -",
            // ── başarısız parola girişi ──
            "LOGIN_FAILED | USER-A | BAD_PASSWORD: attempt #1/5 for x             | {\"method\":\"LOCAL\"}       | FAILURE | LOCAL       | false | false | BAD_PASSWORD | -",
            "LOGIN_FAILED | USER-B | BAD_PASSWORD: brute-force attempt #5/5       | -                          | FAILURE | LDAP        | true  | false | BAD_PASSWORD | -",
            "LOGIN_FAILED | GHOST  | UNKNOWN_USER: attempt #1/5 for x             | {\"method\":\"LDAP\"}        | FAILURE | -           | false | true  | UNKNOWN_USER | -",
            "LOGIN_FAILED | USER-A | UNKNOWN_USER: attempt #1/5 for x             | -                          | FAILURE | -           | false | true  | UNKNOWN_USER | -",
            "LOGIN_FAILED | USER-A | TEMP_PASSWORD_EXPIRED: attempt #1/5          | {\"method\":\"LOCAL\"}       | FAILURE | LOCAL       | false | false | TEMP_PASSWORD_EXPIRED | -",
            // eski ret satırları: neden metnindeki "(YOL)" eki — PASSWORD = LOCAL
            "LOGIN_FAILED | USER-A | ACCOUNT_INACTIVE: pasif hesap (PASSWORD)     | -                          | FAILURE | LOCAL       | false | false | ACCOUNT_INACTIVE | -",
            "LOGIN_FAILED | USER-B | MAINTENANCE: sistem bakımı (REMEMBER_ME)     | -                          | FAILURE | REMEMBER_ME | false | false | MAINTENANCE  | -",
            "LOGIN_FAILED | USER-A | ACCOUNT_LOCKED: hesap kilitli (OTP_EMAIL)    | {\"method\":\"OTP_EMAIL\"}   | FAILURE | OTP_EMAIL   | false | false | ACCOUNT_LOCKED | -",
            "LOGIN_FAILED | USER-B | LDAP_LOGIN_DISABLED: LDAP ile giriş kapalı   | {\"method\":\"LDAP\"}        | FAILURE | LDAP        | false | false | LDAP_LOGIN_DISABLED | -",
            "LOGIN_FAILED | GHOST  | LDAP_LOGIN_DISABLED: LDAP ile giriş kapalı   | {\"method\":\"LDAP\"}        | FAILURE | -           | false | true  | UNKNOWN_USER | -",
            // oran sınırı / kilit reddi
            "LOGIN_FAILED | user-a | Rate limited: too many login attempts       | {\"method\":\"LOCAL\"}       | FAILURE | LOCAL       | false | false | RATE_LIMITED | -",
            "LOGIN_FAILED | user-b | Rate limited: too many login attempts       | -                          | FAILURE | LDAP        | true  | false | RATE_LIMITED | -",
            "LOGIN_FAILED | -      | Rate limited: too many login attempts       | -                          | FAILURE | -           | false | true  | UNKNOWN_USER | -",
            "LOGIN_FAILED | USER-A | -                                           | -                          | FAILURE | LOCAL       | true  | false | OTHER        | -",
            // ── kodla giriş doğrulaması: kanal ayrıntıdaki channel; bilinmeyen adın tuzak satırı kanala yazılmaz ama huniye girer ──
            "LOGIN_OTP_VERIFY_FAILED | USER-A | OTP_INVALID: yanlış kod       | {\"channel\":\"PUSH\",\"attempts_left\":2} | FAILURE | OTP_PUSH | false | false | OTP_INVALID | OTP_PUSH",
            "LOGIN_OTP_EXPIRED       | USER-B | OTP_EXPIRED: süre doldu       | {\"channel\":\"EMAIL\",\"reason\":\"TTL\"}  | FAILURE | OTP_EMAIL | false | false | OTP_EXPIRED | OTP_EMAIL",
            "LOGIN_OTP_LOCKED        | GHOST  | OTP_LOCKED: deneme hakkı bitti | {\"channel\":\"EMAIL\"}                  | FAILURE | -         | false | true  | UNKNOWN_USER | OTP_EMAIL",
            "LOGIN_OTP_VERIFY_FAILED | USER-A | -                             | {\"channel\":\"PUSH\"}                   | FAILURE | OTP_PUSH | false | false | OTP_INVALID | OTP_PUSH",
            // ── huni olayları (deneme değil) ──
            "LOGIN_OTP_REQUESTED       | GHOST  | SUPPRESSED: UNKNOWN_USER | {\"channel\":\"PUSH\",\"result\":\"SUPPRESSED\",\"reason\":\"UNKNOWN_USER\"} | OTP_REQUEST | - | false | false | - | OTP_PUSH",
            "LOGIN_OTP_DELIVERY_FAILED | USER-A | DELIVERY_FAILED: SMTP     | {\"channel\":\"EMAIL\",\"reason\":\"SMTP\"}                          | OTP_DELIVERY_FAILED | - | false | false | - | OTP_EMAIL",
            "LOGOUT                    | USER-A | -                         | -                                                                    | OTHER | - | false | false | - | -",
    })
    @DisplayName("sınıflandırma tablosu")
    void classify(String type, String actor, String reason, String detail, String kind, String channel, boolean estimated,
                  boolean unknown, String expReason, String otp) {
        LoginEventClassifier.Result r = LoginEventClassifier.classify(type.trim(), actor == null ? null : actor.trim(),
                reason == null ? null : reason.trim(), detail == null ? null : detail.trim(), SOURCE);
        assertThat(r.kind()).hasToString(kind.trim());
        assertThat(r.channel() == null ? null : r.channel().name()).isEqualTo(channel == null ? null : channel.trim());
        assertThat(r.estimated()).isEqualTo(estimated);
        assertThat(r.unknownUser()).isEqualTo(unknown);
        assertThat(r.reason()).isEqualTo(expReason == null ? null : expReason.trim());
        assertThat(r.otpChannel() == null ? null : r.otpChannel().name()).isEqualTo(otp == null ? null : otp.trim());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("istek huni ayrıntısı: sonuç ve bastırma nedeni; ayrıntı alanı ayrıştırıcısı boşluk / eksik alan / dize olmayan değer")
    void funnelDetailAndFieldParser() {
        LoginEventClassifier.Result r = LoginEventClassifier.classify("LOGIN_OTP_REQUESTED", "USER-A", null,
                "{\"channel\":\"EMAIL\",\"result\":\"sent\"}", SOURCE);
        assertThat(r.otpResult()).isEqualTo("SENT");
        assertThat(r.otpReason()).isNull();
        assertThat(LoginEventClassifier.field("{ \"method\" : \"LDAP\" }", "method")).isEqualTo("LDAP");
        assertThat(LoginEventClassifier.field("{\"attempts\":3}", "attempts")).isNull();
        assertThat(LoginEventClassifier.field("{\"x\":\"method\",\"method\":\"LOCAL\"}", "method")).isEqualTo("LOCAL");
        assertThat(LoginEventClassifier.field(null, "method")).isNull();
        assertThat(LoginChannel.fromMethod("password")).isEqualTo(LoginChannel.LOCAL);
        assertThat(LoginChannel.fromMethod("X")).isNull();
        assertThat(LoginChannel.ofAccountSource(null)).isEqualTo(LoginChannel.LOCAL);
        assertThat(LoginChannel.ofAccountSource("LDAP")).isEqualTo(LoginChannel.LDAP);
    }
}
