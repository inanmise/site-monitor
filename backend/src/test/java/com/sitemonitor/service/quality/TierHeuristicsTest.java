package com.sitemonitor.service.quality;

import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** "Katman sinyallerle çelişiyor" sezgileri — muhafazakâr: yalnız güçlü çelişki işaretlenir. */
class TierHeuristicsTest {

    private static CertificateInventory inv(String domain, Integer tier, Boolean ev, Boolean internal) {
        CertificateInventory r = new CertificateInventory();
        r.setDomain(domain);
        r.setTier(tier);
        r.setEvCertificate(ev);
        r.setInternalCert(internal);
        return r;
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "uat-api.example.com,uat", "api.test.example.com,test", "app-dev01.example.com,dev",
            "pre-prod.portal.example.com,preprod", "portal-preprod.example.com,preprod", "qa.example.com,qa",
            "STAGING.Example.com,staging", "svc_sbx.example.com,sbx", "test2.example.com:8443,test",
            "*.uat.example.com,uat"})
    @DisplayName("test ortamı belirteci TAM sözcük olarak bulunur (sondaki rakam, -, _, ., * ayraç)")
    void nonProdTokenFound(String name, String token) {
        assertThat(TierHeuristics.nonProdToken(name)).isEqualTo(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"developer.example.com", "contest.example.com", "devops.example.com", "latest.example.com",
            "prod.example.com", "www.example.com", "qatar.example.com", "", "   "})
    @DisplayName("sözcük İÇİNDEKİ benzerlik eşleşmez (developer, contest, devops, qatar…)")
    void nonProdTokenNotFound(String name) {
        assertThat(TierHeuristics.nonProdToken(name)).isNull();
    }

    @Test
    @DisplayName("aşırı uzun ad taranmaz (sınır)")
    void longNameSkipped() {
        assertThat(TierHeuristics.nonProdToken("test." + "a".repeat(400))).isNull();
    }

    @Test
    @DisplayName("katman 1–2 + test adı → NONPROD_NAME_ON_PROD_TIER (belirteçle)")
    void prodTierWithTestName() {
        TierHeuristics.Finding f = TierHeuristics.evaluate(inv("uat-portal.example.com", 2, null, null));
        assertThat(f).isNotNull();
        assertThat(f.reason()).isEqualTo(TierHeuristics.NONPROD_NAME_ON_PROD_TIER);
        assertThat(f.token()).isEqualTo("uat");
        assertThat(TierHeuristics.evaluate(inv("uat-portal.example.com", 3, null, null))).as("katman 3 tutarlı").isNull();
    }

    @Test
    @DisplayName("katman 3–4 + EV sertifikası + test adı YOK → EV_ON_LOW_TIER; test adı varsa işaretlenmez")
    void evOnLowTier() {
        assertThat(TierHeuristics.evaluate(inv("shop.example.com", 4, true, null)).reason())
                .isEqualTo(TierHeuristics.EV_ON_LOW_TIER);
        assertThat(TierHeuristics.evaluate(inv("shop.uat.example.com", 3, true, null))).isNull();
        assertThat(TierHeuristics.evaluate(inv("shop.example.com", 4, false, null))).isNull();
        assertThat(TierHeuristics.evaluate(inv("shop.example.com", 1, true, null))).isNull();
    }

    @Test
    @DisplayName("katman 1 + iç sertifika → INTERNAL_CERT_ON_CUSTOMER_TIER; katman 2 (iç üretim) tutarlı")
    void internalCertOnCustomerTier() {
        assertThat(TierHeuristics.evaluate(inv("portal.example.com", 1, null, true)).reason())
                .isEqualTo(TierHeuristics.INTERNAL_CERT_ON_CUSTOMER_TIER);
        assertThat(TierHeuristics.evaluate(inv("portal.example.com", 2, null, true))).isNull();
    }

    @Test
    @DisplayName("katmansız kayıt bu kurala girmez; zayıf sinyal (WAF, dış tedarikçi) tek başına işaretlemez")
    void noTierOrWeakSignals() {
        assertThat(TierHeuristics.evaluate(inv("uat.example.com", null, true, true))).isNull();
        CertificateInventory r = inv("portal.example.com", 4, null, null);
        r.setWafEnabled(true);
        r.setExternalVendor(true);
        assertThat(TierHeuristics.evaluate(r)).isNull();
        assertThat(TierHeuristics.evaluate(null)).isNull();
    }
}
