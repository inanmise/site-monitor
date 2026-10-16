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
