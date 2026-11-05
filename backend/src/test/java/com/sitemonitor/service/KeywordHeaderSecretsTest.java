package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.KeywordMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Anahtar kelime izlemesinin sırları (kw sözleşmesi §5.1 / B8 / B9): özel başlık biçimi kayıtta doğrulanır (kısıtlı başlık
 * → 400 customHeaders), boş / null = kayıtlı başlıkları kaldır; Basic auth parolası şifreli, write-only, çözülemezse null.
 */
class KeywordHeaderSecretsTest {

    SecretCipher cipher;
    KeywordHeaderSecrets secrets;

    @BeforeEach
    void setUp() {
        cipher = new SecretCipher();
        cipher.init();
        secrets = new KeywordHeaderSecrets(null, cipher);
    }

    @Test
    @DisplayName("başlık: geçerli → şifreli saklanır, düz kopya kalmaz; kısıtlı / bozuk → 400 customHeaders; null / boş → kaldırılır")
    @SuppressWarnings("deprecation")
    void storeValidatesAndClears() {
        KeywordMonitor m = new KeywordMonitor();
        m.setCustomHeaders("Eski: duz");
        secrets.store(m, "X-Api-Key: abc123\nAccept-Language: tr");
        assertThat(m.getCustomHeadersEnc()).isNotBlank().doesNotContain("abc123");
        assertThat(m.getCustomHeaders()).isNull();
        assertThat(secrets.effectiveHeaders(m)).isEqualTo("X-Api-Key: abc123\nAccept-Language: tr");
        assertThat(secrets.headerNames(m)).containsExactly("X-Api-Key", "Accept-Language");
        assertThat(secrets.hasHeaders(m)).isTrue();

        String before = m.getCustomHeadersEnc();
        for (String bad : new String[] { "Host: ic.example.local", "Content-Length: 5", "Bozuk satır", "X-A: b\r\nY" }) {
            assertThatThrownBy(() -> secrets.store(m, bad)).as(bad)
                    .isInstanceOfSatisfying(GlobalExceptionHandler.FieldValidationException.class,
                            e -> assertThat(e.getFields()).containsOnlyKeys("customHeaders"));
        }
        assertThat(m.getCustomHeadersEnc()).as("reddedilen değer kaydı bozmaz").isEqualTo(before);

        secrets.store(m, null);   // B9: null = kayıtlı başlıkları kaldır
        assertThat(m.getCustomHeadersEnc()).isNull();
        assertThat(secrets.hasHeaders(m)).isFalse();
        secrets.store(m, "X-A: b");
        secrets.store(m, "   ");
        assertThat(m.getCustomHeadersEnc()).isNull();
    }

    @Test
    @DisplayName("Basic auth parolası: şifreli yazılır, çözülür; boş yazım dokunmaz; silinir; bozuk şifreli metin → null (kontrol kimliksiz sürer)")
    void basicAuthPass() {
        KeywordMonitor m = new KeywordMonitor();
        assertThat(secrets.hasBasicAuthPass(m)).isFalse();
        assertThat(secrets.basicAuthPass(m)).isNull();
        secrets.storeBasicAuthPass(m, "P@ssw0rd!");
        assertThat(m.getBasicAuthPassEnc()).isNotBlank().doesNotContain("P@ssw0rd!");
        assertThat(secrets.basicAuthPass(m)).isEqualTo("P@ssw0rd!");
        assertThat(secrets.hasBasicAuthPass(m)).isTrue();
        secrets.storeBasicAuthPass(m, "");
        assertThat(secrets.basicAuthPass(m)).isEqualTo("P@ssw0rd!");
        m.setBasicAuthPassEnc("enc:v1:bozuk");
        assertThat(secrets.basicAuthPass(m)).isNull();
        secrets.clearBasicAuthPass(m);
        assertThat(m.getBasicAuthPassEnc()).isNull();
        assertThat(secrets.hasBasicAuthPass(null)).isFalse();
    }
}
