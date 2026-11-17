package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Son N günde yenilenen" sertifikalar (2026-11-17, performans): {@code HAVING COUNT(DISTINCT fingerprint) > 1} yerine
 * {@code HAVING MIN(fingerprint) <> MAX(fingerprint)} — sonuç kümesi AYNI. Kahin: aynı satırlar üzerinde eski kuralın Java
 * karşılığı (pencere içinde, parmak izi NULL olmayan satırların farklı değer sayısı &gt; 1).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CertificateCheckFingerprintChangeTest {

    private static final String CUTOFF = "2026-11-10T00:00:00";

    @Autowired CertificateCheckRepository repo;

    private void save(String domain, String at, String fp) {
        CertificateCheck c = new CertificateCheck();
        c.setDomain(domain);
        c.setCheckedAt(at);
        c.setFingerprint(fp);
        c.setStatus("valid");
        repo.save(c);
    }

    @Test
    @DisplayName("MIN <> MAX = COUNT(DISTINCT) > 1: iki parmak izi; tek parmak izi (yinelenen); NULL'lar sayılmaz; pencere dışı sayılmaz; harf duyarlı")
    void sameDomainSetAsCountDistinct() {
        save("renewed.example.com", "2026-11-11T10:00:00", "AA");
        save("renewed.example.com", "2026-11-12T10:00:00", "BB");
        save("same.example.com", "2026-11-11T10:00:00", "AA");
        save("same.example.com", "2026-11-12T10:00:00", "AA");
        save("same.example.com", "2026-11-13T10:00:00", "AA");
        save("nullmix.example.com", "2026-11-11T10:00:00", "CC");
        save("nullmix.example.com", "2026-11-12T10:00:00", null);
        save("nullmix.example.com", "2026-11-13T10:00:00", null);
        save("onlynull.example.com", "2026-11-11T10:00:00", null);
        save("onlynull.example.com", "2026-11-12T10:00:00", null);
        save("old.example.com", "2026-11-01T10:00:00", "OLD");          // pencere dışı (yenileme pencereden önce)
        save("old.example.com", "2026-11-11T10:00:00", "NEW");
        save("three.example.com", "2026-11-11T10:00:00", "X1");
        save("three.example.com", "2026-11-12T10:00:00", "X2");
        save("three.example.com", "2026-11-13T10:00:00", "X1");
        save("case.example.com", "2026-11-11T10:00:00", "ab");
        save("case.example.com", "2026-11-12T10:00:00", "AB");
        save("edge.example.com", CUTOFF, "E1");                          // tam sınır: dahil
        save("edge.example.com", "2026-11-14T10:00:00", "E2");

        List<String> sql = repo.domainsWithFingerprintChangeSince(CUTOFF);

        // Kahin: eski COUNT(DISTINCT) kuralı Java'da
        Map<String, Set<String>> distinct = new HashMap<>();
        for (CertificateCheck c : repo.findAll()) {
            if (c.getCheckedAt().compareTo(CUTOFF) < 0 || c.getFingerprint() == null) continue;
            distinct.computeIfAbsent(c.getDomain(), k -> new HashSet<>()).add(c.getFingerprint());
        }
        Set<String> oracle = new HashSet<>();
        distinct.forEach((d, fps) -> { if (fps.size() > 1) oracle.add(d); });

        assertThat(sql).doesNotHaveDuplicates();
        assertThat(new HashSet<>(sql)).isEqualTo(oracle)
                .containsExactlyInAnyOrder("renewed.example.com", "three.example.com", "case.example.com", "edge.example.com");
        assertThat(repo.domainsWithFingerprintChangeSince("2026-12-01T00:00:00")).isEmpty();
    }
}
