package com.sitemonitor.service;

import com.sitemonitor.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Denetim kurcalanamazlığı (H2): hash zinciri kurulur, doğrulanır, kurcalama TESPİT edilir; ve
 * append-only — {@link AuditLogRepository} uygulamaya HİÇBİR delete/update yüzeyi açmaz.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AuditIntegrityTest {

    @Autowired AuditLogRepository repo;
    @PersistenceContext EntityManager em;

    private AuditService svc;

    @BeforeEach
    void setUp() {
        svc = new AuditService(repo, mock(GeoIpService.class), mock(ClientIpResolver.class));
    }

    @Test
    @DisplayName("persist → monoton seq + zincir hash; verifyChain temiz zinciri onaylar")
    void chain_buildsAndVerifiesClean() {
        svc.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot");
        svc.recordSystemEvent("SCHEMA_PATCH", "SYSTEM", "db", "patch-1");
        svc.recordSystemEvent("AUDIT_RETENTION_PURGE", "SYSTEM", "audit_log", "purged=0");
        em.flush(); em.clear();

        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.checked()).isEqualTo(3);
        assertThat(v.brokenSeq()).isNull();
    }

    @Test
    @DisplayName("bir satır DB'de elle değiştirilince verifyChain kırığı doğru seq ile bildirir")
    void chain_detectsTampering() {
        svc.recordSystemEvent("E1", "SYSTEM", "r1", "a");
        svc.recordSystemEvent("E2", "SYSTEM", "r2", "b");
        svc.recordSystemEvent("E3", "SYSTEM", "r3", "c");
        em.flush();

        // Kurcala: 2. kaydın detail'ini doğrudan SQL ile değiştir (hash artık uyuşmaz).
        em.createNativeQuery("UPDATE audit_log SET detail = 'TAMPERED' WHERE seq = 2").executeUpdate();
        em.flush(); em.clear();

        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenSeq()).isEqualTo(2L);
    }

    @Test
    @DisplayName("append-only: AuditLogRepository JpaRepository DEĞİL ve hiçbir delete metodu YOK")
    void repository_isAppendOnly_noDeleteSurface() {
        assertThat(JpaRepository.class.isAssignableFrom(AuditLogRepository.class))
                .as("audit repo JpaRepository olmamalı (delete/update yüzeyi açılmasın)").isFalse();
        for (Method m : AuditLogRepository.class.getMethods()) {
            assertThat(m.getName().toLowerCase())
                    .as("delete metodu bulunmamalı: %s", m.getName())
                    .doesNotStartWith("delete");
        }
    }

    @Test
    @DisplayName("geo backfill (updateGeo) çekirdek alanlara dokunmaz → zincir kırılmaz")
    void geoUpdate_doesNotBreakChain() {
        svc.recordSystemEvent("E1", "SYSTEM", "r1", "a");
        svc.recordSystemEvent("E2", "SYSTEM", "r2", "b");
        em.flush();
        Long id = repo.findTopByOrderBySeqDesc().orElseThrow().getId();

        repo.updateGeo(id, "Türkiye", "İstanbul", "Akbank", "host.example");
        em.flush(); em.clear();

        assertThat(svc.verifyChain().ok()).isTrue();   // geo alanları hash kapsamı dışında
    }
}
