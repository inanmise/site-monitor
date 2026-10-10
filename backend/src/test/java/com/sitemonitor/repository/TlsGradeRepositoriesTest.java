package com.sitemonitor.repository;

import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.model.TlsGradeStatus;
import com.sitemonitor.model.TlsProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TLS notu tabloları (2026-10-10) — gerçek eşleme ve sorgular H2 üstünde (servis testleri depoları mock'luyor): profil
 * alan adı anahtarıyla, durum envanter kimliğiyle saklanır; değişim günlüğü pencere + yön + sayfa sınırıyla, yeniden
 * eskiye okunur; kayıt başına son 20 değişim.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class TlsGradeRepositoriesTest {

    @Autowired TlsProfileRepository profiles;
    @Autowired TlsGradeStatusRepository statuses;
    @Autowired TlsGradeChangeRepository changes;

    @Test
    @DisplayName("profil: alan adı anahtarı, IN sorgusu, üç değerli alanlar ve uzun ayrıntı saklanır")
    void profileRoundTrip() {
        TlsProfile p = new TlsProfile();
        p.setDomain("www.example.com");
        p.setPort(443);
        p.setVia("direct");
        p.setTls10(TlsProfile.NO);
        p.setTls11(TlsProfile.NO);
        p.setTls12(TlsProfile.YES);
        p.setTls13(TlsProfile.YES);
        p.setOcspStapling(TlsProfile.UNKNOWN);
        p.setWeakCipher(TlsProfile.NO);
        p.setStatus(TlsProfile.STATUS_OK);
        p.setDetail("x".repeat(2000));
        p.setProbedAt("2026-10-10T06:00:00");
        p.setProbeTrigger(TlsProfile.TRIGGER_SCHEDULED);
        profiles.save(p);
        List<TlsProfile> got = profiles.findByDomainIn(Set.of("www.example.com", "other.example.com"));
        assertThat(got).hasSize(1);
        assertThat(got.get(0).getTls13()).isEqualTo("YES");
        assertThat(got.get(0).getDetail()).hasSize(2000);
    }

    @Test
    @DisplayName("durum: envanter kimliği anahtarı; findAllById toplu okuma")
    void statusRoundTrip() {
        TlsGradeStatus s = new TlsGradeStatus();
        s.setInventoryId(42L);
        s.setDomain("www.example.com");
        s.setGrade("A+");
        s.setReasons("TLS10_ENABLED,NO_TLS13");
        s.setDroppedFrom("A");
        s.setDroppedAt("2026-10-10T06:00:00");
        statuses.save(s);
        assertThat(statuses.findAllById(List.of(42L, 43L))).extracting(TlsGradeStatus::getGrade).containsExactly("A+");
    }

    @Test
    @DisplayName("değişimler: pencere + yön süzgeci, yeniden eskiye, sayfa sınırı; kayıt başına son değişimler")
    void changesQueries() {
        change(1L, "a.example.com", "A", "B", "DROP", "2026-10-01T10:00:00");
        change(1L, "a.example.com", "B", "A", "RISE", "2026-10-02T10:00:00");
        change(2L, "b.example.com", "A+", "C", "DROP", "2026-10-03T10:00:00");
        change(3L, "c.example.com", "A", "F", "DROP", "2026-09-01T10:00:00");   // pencere dışı
        List<TlsGradeChange> recent = changes.findRecent("2026-09-20T00:00:00", "DROP", PageRequest.of(0, 10));
        assertThat(recent).extracting(TlsGradeChange::getDomain).containsExactly("b.example.com", "a.example.com");
        assertThat(changes.findRecent("2026-09-20T00:00:00", "DROP", PageRequest.of(0, 1))).hasSize(1);
        assertThat(changes.findTop20ByInventoryIdOrderByChangedAtDescIdDesc(1L))
                .extracting(TlsGradeChange::getDirection).containsExactly("RISE", "DROP");
    }

    private void change(Long inv, String domain, String from, String to, String dir, String at) {
        TlsGradeChange c = new TlsGradeChange();
        c.setInventoryId(inv);
        c.setDomain(domain);
        c.setTeamId(1L);
        c.setFromGrade(from);
        c.setToGrade(to);
        c.setDirection(dir);
        c.setReasons("NO_TLS12");
        c.setChangedAt(at);
        changes.save(c);
    }
}
