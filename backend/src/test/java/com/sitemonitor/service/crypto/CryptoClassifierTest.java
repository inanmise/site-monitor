package com.sitemonitor.service.crypto;

import com.sitemonitor.service.CertificateHealthRules;
import com.sitemonitor.service.crypto.CryptoClassifier.Category;
import com.sitemonitor.service.crypto.CryptoClassifier.Family;
import com.sitemonitor.service.crypto.CryptoClassifier.KeyBucket;
import com.sitemonitor.service.crypto.CryptoClassifier.PqcStatus;
import com.sitemonitor.service.crypto.CryptoClassifier.SigHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kripto envanteri sınıflandırıcısı (2026-10-10) — doğruluk tabloları. Kural değişirse burası ve ön yüz çevirileri
 * birlikte değişir; arayüz kuralı kopyalamaz.
 */
class CryptoClassifierTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "RSA, 512, RSA_1024",
            "RSA, 1024, RSA_1024",
            "RSA, 1536, RSA_LT2048",
            "RSA, 2048, RSA_2048",
            "RSA, 3071, RSA_2048",
            "RSA, 3072, RSA_3072",
            "RSA, 4096, RSA_4096",
            "RSA, 8192, RSA_4096",
            "RSA, null, RSA_OTHER",
            "RSASSA-PSS, 2048, RSA_2048",
            "EC, 256, EC_P256",
            "EC, 384, EC_P384",
            "EC, 521, EC_P521",
            "EC, 224, EC_OTHER",
            "ECDSA, 256, EC_P256",
            "EdDSA, -1, ED25519",
            "Ed25519, null, ED25519",
            "Ed448, null, ED448",
            "DSA, 2048, DSA",
            "ML-DSA-65, null, PQC",
            "SLH-DSA-SHA2-128s, null, PQC",
            "id-MLDSA65-ECDSA-P256-SHA512, null, HYBRID",
            "MLDSA44-RSA2048-PSS-SHA256, null, HYBRID",
            "GOST3410, 256, OTHER",
            "null, null, UNKNOWN",
            "'  ', 2048, UNKNOWN",
    })
    void keyBucket(String alg, Integer size, KeyBucket expected) {
        assertThat(CryptoClassifier.keyBucket(alg, size)).isEqualTo(expected);
    }

    @Test
    @DisplayName("kova aileleri: ML-DSA 'DSA' içerir ama PQC; ECDSA 'DSA' içerir ama EC")
    void families() {
        assertThat(KeyBucket.RSA_2048.family).isEqualTo(Family.RSA);
        assertThat(CryptoClassifier.keyBucket("ML-DSA-87", null).family).isEqualTo(Family.PQC);
        assertThat(CryptoClassifier.keyBucket("ECDSA", 384).family).isEqualTo(Family.EC);
        assertThat(CryptoClassifier.keyBucket("EdDSA", -1).family).isEqualTo(Family.EDDSA);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(nullValues = "null", value = {
            "MD5withRSA, MD5",
            "MD2withRSA, MD5",
            "SHA1withRSA, SHA1",
            "SHA-1withRSA, SHA1",
            "SHA1withECDSA, SHA1",
            "SHA224withRSA, SHA224",
            "SHA256withRSA, SHA256",
            "SHA3-256withRSA, SHA256",
            "SHA384withECDSA, SHA384",
            "SHA512withRSA, SHA512",
            "Ed25519, EDDSA",
            "EdDSA, EDDSA",
            "RSASSA-PSS, OTHER",
            "ML-DSA-65, PQC",
            "MLDSA65-ECDSA-P256-SHA512, HYBRID",
            "null, UNKNOWN",
    })
    void sigHash(String sig, SigHash expected) {
        assertThat(CryptoClassifier.sigHash(sig)).isEqualTo(expected);
    }

    @Test
    @DisplayName("zayıf özet yalnız MD5 / SHA-1")
    void weakHashes() {
        for (SigHash h : SigHash.values()) assertThat(h.weak()).as(h.name()).isEqualTo(h == SigHash.MD5 || h == SigHash.SHA1);
    }

    @ParameterizedTest(name = "{0} + {1} → {2}")
    @CsvSource({
            "RSA_2048, SHA256, VULNERABLE",
            "EC_P256, SHA256, VULNERABLE",
            "ED25519, EDDSA, VULNERABLE",
            "DSA, SHA256, VULNERABLE",
            "PQC, PQC, PQC",
            "HYBRID, HYBRID, HYBRID",
            "RSA_3072, HYBRID, HYBRID",
            "OTHER, OTHER, UNKNOWN",
            "UNKNOWN, UNKNOWN, UNKNOWN",
    })
    void pqcStatus(KeyBucket key, SigHash sig, PqcStatus expected) {
        assertThat(CryptoClassifier.pqcStatus(key, sig)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1} {2} weakInt={3} → {4}")
    @CsvSource(nullValues = "null", value = {
            // bugün zayıf
            "RSA, 1024, SHA256withRSA, false, BROKEN",
            "RSA, 1536, SHA256withRSA, false, BROKEN",
            "RSA, 2048, SHA1withRSA, false, BROKEN",
            "RSA, 4096, MD5withRSA, false, BROKEN",
            "EC, 224, SHA256withECDSA, false, BROKEN",
            "RSA, 2048, SHA256withRSA, true, BROKEN",
            "EC, 256, SHA256withECDSA, true, BROKEN",
            "DSA, 1024, SHA256withDSA, false, BROKEN",
            // 2030 eşiğinin altı
            "RSA, 2048, SHA256withRSA, false, LEGACY",
            "RSA, 3071, SHA256withRSA, false, LEGACY",
            "DSA, 2048, SHA256withDSA, false, LEGACY",
            // klasik olarak güçlü
            "RSA, 3072, SHA256withRSA, false, MODERN",
            "RSA, 4096, SHA384withRSA, false, MODERN",
            "EC, 256, SHA256withECDSA, false, MODERN",
            "EC, 384, SHA384withECDSA, false, MODERN",
            "EdDSA, -1, Ed25519, false, MODERN",
            // PQC / hibrit
            "ML-DSA-65, null, ML-DSA-65, false, PQC_READY",
            "id-MLDSA65-ECDSA-P256-SHA512, null, id-MLDSA65-ECDSA-P256-SHA512, false, PQC_READY",
            // bilinmiyor
            "null, null, null, false, UNKNOWN",
            "RSA, null, SHA256withRSA, false, UNKNOWN",
            "GOST3410, 256, GOST3411withGOST3410, false, UNKNOWN",
    })
    void category(String alg, Integer size, String sig, boolean weakIntermediate, Category expected) {
        KeyBucket b = CryptoClassifier.keyBucket(alg, size);
        assertThat(CryptoClassifier.category(b, size, CryptoClassifier.sigHash(sig), weakIntermediate)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "Zayıf Algoritma raporuyla tutarlı: {0} {1} {2}")
    @CsvSource({
            "RSA, 1024, SHA256withRSA", "RSA, 2047, SHA256withRSA", "RSA, 2048, SHA256withRSA", "RSA, 4096, SHA1withRSA",
            "RSA, 2048, MD5withRSA", "EC, 191, SHA256withECDSA", "EC, 224, SHA256withECDSA", "EC, 256, SHA256withECDSA",
            "EC, 384, SHA1withECDSA", "RSA, 3072, SHA384withRSA", "DSA, 1024, SHA1withDSA",
    })
    @DisplayName("yaprak verisinde: classifyWeakness bir bulgu veriyorsa kategori BROKEN, vermiyorsa BROKEN değil")
    void consistentWithWeakAlgorithmReport(String alg, int size, String sig) {
        boolean weak = CertificateHealthRules.classifyWeakness(sig, alg, size, new ArrayList<>()) != null;
        Category c = CryptoClassifier.category(CryptoClassifier.keyBucket(alg, size), size, CryptoClassifier.sigHash(sig), false);
        assertThat(c == Category.BROKEN).as(alg + " " + size + " " + sig + " → " + c).isEqualTo(weak);
    }
}
