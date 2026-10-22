package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateCheck;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.service.CertificateHealthRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATA-13 (2026-10-22): zayıf algoritma ADAY sorguları (SQL) ile {@link CertificateHealthRules#classifyWeakness} (Java)
 * aynı satırları zayıf saymalı. Eskiden Ed25519 / ML-DSA anahtarı ("EdDSA" / "ML-DSA" adı "DSA" içerir, boyu -1)
 * "Short key -1-bit" KRİTİK sayılıyordu; "ECDSA" adı da RSA/DSA dalına düşüyordu. Her satır için SQL kararı = Java kararı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WeakKeySqlParityTest {

    @Autowired LatestCheckRepository latestRepo;
    @Autowired CertificateCheckRepository checkRepo;

    private record Row(String domain, String alg, Integer size) { }

    private static final List<Row> ROWS = List.of(
            new Row("ed25519.test", "EdDSA", -1),
            new Row("ed25519b.test", "Ed25519", -1),
            new Row("mldsa.test", "ML-DSA", -1),
            new Row("mldsa-sized.test", "ML-DSA-65", 1952),
            new Row("ecdsa.test", "ECDSA", 256),
            new Row("ec256.test", "EC", 256),
            new Row("ec192.test", "EC", 192),
            new Row("rsa1024.test", "RSA", 1024),
            new Row("rsa2048.test", "RSA", 2048),
            new Row("rsa-unknown.test", "RSA", -1),
            new Row("dsa1024.test", "DSA", 1024),
            new Row("nullsize.test", "RSA", null));

    @Test
    @DisplayName("findWeakAlgorithmCandidates (SQL) = classifyWeakness (Java) — EdDSA / ML-DSA / boyu okunamayan ve ECDSA zayıf DEĞİL")
    void latestCandidatesMatchJava() {
        for (Row r : ROWS) {
            LatestCheck c = new LatestCheck();
            c.setDomain(r.domain());
            c.setStatus("valid");
            c.setSignatureAlgorithm("SHA256withECDSA");
            c.setPublicKeyAlgorithm(r.alg());
            c.setPublicKeySize(r.size());
            c.setCheckedAt("2026-10-22T00:00:00");
            latestRepo.save(c);
        }
        Set<String> sql = latestRepo.findWeakAlgorithmCandidates().stream().map(LatestCheck::getDomain).collect(Collectors.toSet());

        assertThat(sql).isEqualTo(javaWeak());
        assertThat(sql).containsExactlyInAnyOrder("ec192.test", "rsa1024.test", "dsa1024.test");
    }

    @Test
    @DisplayName("weakObservationsSince (SQL) = classifyWeakness (Java)")
    void observationsMatchJava() {
        for (Row r : ROWS) {
            CertificateCheck c = new CertificateCheck();
            c.setDomain(r.domain());
            c.setStatus("valid");
            c.setSignatureAlgorithm("SHA256withRSA");
            c.setPublicKeyAlgorithm(r.alg());
            c.setPublicKeySize(r.size());
            c.setCheckedAt("2026-10-22T01:00:00");
            checkRepo.save(c);
        }
        Set<String> sql = new HashSet<>();
        for (Object[] row : checkRepo.weakObservationsSince("2026-10-21T00:00:00")) sql.add((String) row[0]);

        assertThat(sql).isEqualTo(javaWeak());
    }

    @Test
    @DisplayName("Java kuralı: EdDSA / ML-DSA / -1 boy → kısa anahtar YOK; keySizeStatus UNKNOWN; ECDSA 256 OK")
    void javaRules() {
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "EdDSA", -1, null)).isNull();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "ML-DSA", 2592, null)).isNull();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "ECDSA", 256, null)).isNull();
        assertThat(CertificateHealthRules.classifyWeakness("SHA256withRSA", "RSA", 1024, null)).isEqualTo("CRITICAL");
        assertThat(CertificateHealthRules.keySizeStatus("EdDSA", 255)).isEqualTo(CertificateHealthRules.Status.UNKNOWN);
        assertThat(CertificateHealthRules.keySizeStatus("ML-DSA", -1)).isEqualTo(CertificateHealthRules.Status.UNKNOWN);
        assertThat(CertificateHealthRules.keySizeStatus("ECDSA", 256)).isEqualTo(CertificateHealthRules.Status.OK);
        assertThat(CertificateHealthRules.keySizeStatus("EC", 192)).isEqualTo(CertificateHealthRules.Status.FAIL);
    }

    private static Set<String> javaWeak() {
        Set<String> out = new HashSet<>();
        for (Row r : ROWS) {
            // İmza güçlü — yalnız anahtar kuralı karar verir
            if (CertificateHealthRules.classifyWeakness("SHA256withRSA", r.alg(), r.size(), null) != null) out.add(r.domain());
        }
        return out;
    }
}
