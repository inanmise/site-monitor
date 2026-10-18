package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScriptedCheckerService#secretValues} — teşhis yanıtı ve saklanmış checks_json bu listeyle maskelenir
 * (2026-10-16, takım erişim paylaşımı: paylaşımla gören / çalıştıran kişi gizli env DEĞERİNİ görmesin).
 */
class ScriptedSecretValuesTest {

    @Test
    @DisplayName("Yalnız GİZLİ ve boş olmayan değerler, ÇÖZÜLMÜŞ hâliyle döner; düz değerler ve boşlar dönmez")
    void returnsDecryptedSecretsOnly() {
        SecretCipher cipher = new SecretCipher();
        cipher.init();
        var svc = new ScriptedCheckerService(null, cipher, null, null, new ProxySettings());
        ScriptedMonitor m = new ScriptedMonitor();
        m.setEnvJson("[{\"name\":\"TOKEN\",\"secret\":true,\"value\":\"" + cipher.encrypt("S3cretTokenValue") + "\"},"
                + "{\"name\":\"BASE_URL\",\"secret\":false,\"value\":\"https://acik.example\"},"
                + "{\"name\":\"BOS\",\"secret\":true,\"value\":\"" + cipher.encrypt("") + "\"}]");

        assertThat(svc.secretValues(m)).containsExactly("S3cretTokenValue");
    }

    private static SecretCipher cipherWithKey(String key) {
        SecretCipher c = new SecretCipher();
        org.springframework.test.util.ReflectionTestUtils.setField(c, "configuredKey", key);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "activeProfiles", "");
        c.init();
        return c;
    }

    @Test
    @DisplayName("revealSecret (2026-10-19): yalnız GÜNCEL env'deki gizli + değeri kayıtlı değişken çözülür; ad birebir; "
            + "gizli olmayan / boş / olmayan → NOT_FOUND; değer toString'e düşmez")
    void revealSecretFindsOnlyStoredSecrets() {
        SecretCipher cipher = cipherWithKey("reveal-test-key-1");
        var svc = new ScriptedCheckerService(null, cipher, null, null, new ProxySettings());
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(7L);
        m.setEnvJson("[{\"name\":\"TOKEN\",\"secret\":true,\"value\":\"" + cipher.encrypt("S3cretTokenValue") + "\"},"
                + "{\"name\":\"BASE_URL\",\"secret\":false,\"value\":\"https://acik.example\"},"
                + "{\"name\":\"BOS\",\"secret\":true,\"value\":\"\"}]");

        var ok = svc.revealSecret(m, "TOKEN");
        assertThat(ok.status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.OK);
        assertThat(ok.value()).isEqualTo("S3cretTokenValue");
        assertThat(ok.toString()).doesNotContain("S3cretTokenValue");
        assertThat(svc.revealSecret(m, "token").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
        assertThat(svc.revealSecret(m, "BASE_URL").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
        assertThat(svc.revealSecret(m, "BASE_URL").value()).isNull();
        assertThat(svc.revealSecret(m, "BOS").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
        assertThat(svc.revealSecret(m, "YOK").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
        assertThat(svc.revealSecret(m, " ").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
        assertThat(svc.revealSecret(null, "TOKEN").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("revealSecret: başka anahtarla şifrelenmiş değer (anahtar değişti) ya da bozuk env kaydı → UNREADABLE")
    void revealSecretUnreadable() {
        SecretCipher old = cipherWithKey("eski-anahtar-123");
        SecretCipher current = cipherWithKey("yeni-anahtar-456");
        var svc = new ScriptedCheckerService(null, current, null, null, new ProxySettings());
        ScriptedMonitor m = new ScriptedMonitor();
        m.setEnvJson("[{\"name\":\"TOKEN\",\"secret\":true,\"value\":\"" + old.encrypt("eski-deger") + "\"}]");
        assertThat(svc.revealSecret(m, "TOKEN").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.UNREADABLE);
        assertThat(svc.revealSecret(m, "TOKEN").value()).isNull();
        m.setEnvJson("{bozuk");
        assertThat(svc.revealSecret(m, "TOKEN").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.UNREADABLE);
        m.setEnvJson("{\"name\":\"TOKEN\"}");
        assertThat(svc.revealSecret(m, "TOKEN").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.UNREADABLE);
        m.setEnvJson(null);
        assertThat(svc.revealSecret(m, "TOKEN").status()).isEqualTo(ScriptedCheckerService.SecretLookup.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("Env yok / bozuk JSON / izleme yok → boş liste (maskeleme hiç değişiklik yapmaz, hata fırlatmaz)")
    void emptyOnMissingOrBrokenEnv() {
        var svc = new ScriptedCheckerService(null, null, null, null, new ProxySettings());
        ScriptedMonitor m = new ScriptedMonitor();
        assertThat(svc.secretValues(m)).isEmpty();
        m.setEnvJson("{bozuk");
        assertThat(svc.secretValues(m)).isEmpty();
        assertThat(svc.secretValues(null)).isEmpty();
    }
}
