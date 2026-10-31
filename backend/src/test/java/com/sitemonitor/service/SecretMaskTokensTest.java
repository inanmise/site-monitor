package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link SecretMask#maskTokens} (2026-10-31, k6 inceleme A7): koşum sırasında üretilen jetonlar DEĞERİ bilinmeden
 * maskelenir — doğrusal tarama, ReDoS yok.
 */
class SecretMaskTokensTest {

    private static final String M = SecretMask.MASK;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            // başlıklar: değer satır sonuna kadar
            "Authorization: Bearer abc.def.ghi|Authorization: " + M,
            "proxy-authorization: Basic dXNlcjpwYXNz|proxy-authorization: " + M,
            "Set-Cookie: SID=31d4d96e407aad42; Path=/; Secure|Set-Cookie: " + M,
            "Cookie: a=b; c=d|Cookie: " + M,
            // JSON (düz ve logfmt içinde kaçışlı)
            "{\"access_token\":\"abcdef12345\",\"x\":1}|{\"access_token\":\"" + M + "\",\"x\":1}",
            "{\\\"password\\\":\\\"P@ss w0rd\\\"}|{\\\"password\\\":\\\"" + M + "\\\"}",
            "{\"Authorization\": \"Bearer zzz\"}|{\"Authorization\": \"" + M + "\"}",
            // sorgu dizesi
            "https://x/cb?code=1&token=abc123&next=/|https://x/cb?code=1&token=" + M + "&next=/",
            "client_secret=s3cr3t&grant_type=client_credentials|client_secret=" + M + "&grant_type=client_credentials",
            "id_token=xyz refresh_token=qq|id_token=" + M + " refresh_token=" + M,
            "api_key: 12345678 apikey=9|api_key: " + M + " apikey=" + M,
            "passwd='hunter2' secret = \"s\"|passwd='" + M + "' secret = \"" + M + "\"",
    })
    @DisplayName("Bilinen biçimler maskelenir")
    void masksKnownForms(String in, String expected) {
        assertThat(SecretMask.maskTokens(in)).isEqualTo(expected);
    }

    @Test
    @DisplayName("JWT: üç parça (her biri ≥ 10) maskelenir; kısa parça / nokta eksik maskelenmez")
    void jwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        assertThat(SecretMask.maskTokens("tok " + jwt + " son")).isEqualTo("tok " + M + " son");
        assertThat(SecretMask.maskTokens("x=" + jwt)).isEqualTo("x=" + M);
        assertThat(SecretMask.maskTokens("eyJhbGciOiJIUzI1NiJ9.kisa.dozjgNryP4J3jVmN"))
                .isEqualTo("eyJhbGciOiJIUzI1NiJ9.kisa.dozjgNryP4J3jVmN");
        assertThat(SecretMask.maskTokens("eyJhbGciOiJIUzI1NiJ9")).isEqualTo("eyJhbGciOiJIUzI1NiJ9");
        // ortada başlamaz (önceki karakter base64url)
        assertThat(SecretMask.maskTokens("aeyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w"))
                .startsWith("aeyJ");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "tokens=5",
            "mytoken=abc",
            "token expired at 12:00",
            "password_hint: x",
            "secretariat: y",
            "level=info msg=\"durum 200\" source=console",
            "Get \"https://example.com/a\": dial tcp: connection refused",
            "token=",
    })
    @DisplayName("Yanlış pozitif yok: sınırsız anahtar, ayırıcısız sözcük, boş değer")
    void noFalsePositives(String in) {
        assertThat(SecretMask.maskTokens(in)).isEqualTo(in);
    }

    @Test
    @DisplayName("null / boş olduğu gibi döner; maskeValues SONRASI uygulanınca ikisi birlikte çalışır")
    void nullAndChaining() {
        assertThat(SecretMask.maskTokens(null)).isNull();
        assertThat(SecretMask.maskTokens("")).isEmpty();
        String raw = "kayıtlı=SuperGizliDeger1 token=calisma-aninda";
        String out = SecretMask.maskTokens(SecretMask.maskValues(raw, List.of("SuperGizliDeger1")));
        assertThat(out).isEqualTo("kayıtlı=" + M + " token=" + M);
    }

    @Test
    @DisplayName("Doğrusal: 1 MB kötücül girdi (eyJ yığını, anahtar yığını, kapanmayan tırnak) < 2 sn")
    void linear() {
        String jwtSpam = "eyJ".repeat(350_000);
        String keySpam = "token=\"".repeat(150_000);
        String authSpam = "authorization authorization ".repeat(40_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            SecretMask.maskTokens(jwtSpam);
            SecretMask.maskTokens(keySpam);
            SecretMask.maskTokens(authSpam);
        });
    }
}
