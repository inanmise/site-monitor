package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Çok pod R2a (MP-38, 2026-10-22): açık bir işlemin İÇİNDE yazılan denetim kaydı zinciri ÇATALLAMAZ.
 *
 * <p>Eski davranış: işlem içindeki kayıt zincir kilidini atlıyor, son satırı okuyup seq k'yı işlem içinde (henüz görünmez)
 * ekliyordu; aynı anda işlem DIŞINDAN yazan bir başka yazıcı son GÖRÜNEN satırı (k−1) okuyup yine seq k ile ekliyor →
 * aynı seq + aynı prev_hash → {@code verifyChain} sahte bir "kurcalama" bildiriyordu. Yeni davranış: işlem içindeki kayıt
 * COMMIT sonrasına ertelenir ve kendi işleminde zincir kilidiyle eklenir. Gerçek H2 + gerçek işlem yöneticisi
 * ({@code NOT_SUPPORTED}: satırlar gerçekten COMMIT olur).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditChainTransactionForkTest {

    @Autowired AuditLogRepository repo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path tmp;

    private AuditService svc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        svc = new AuditService(repo, mock(GeoIpService.class), mock(NewDeviceNotifier.class),
                mock(ClientIpResolver.class), mock(AuditGeoEnricher.class));
        svc.setChainLockSupport(jdbc, txManager);
        ReflectionTestUtils.setField(svc, "fallbackFile", tmp.resolve("audit-fallback.jsonl").toString());
    }

    /** NOT_SUPPORTED satırları işler — önbellekli @DataJpaTest bağlamını paylaşan sonraki testler temiz başlasın. */
    @AfterEach
    void cleanUpCommittedRows() {
        jdbc.update("DELETE FROM audit_log");
    }

    private List<AuditLog> chain() {
        return repo.findBySeqNotNullOrderBySeqAsc(PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("işlem içindeki denetim + aynı anda işlem dışı denetim → seq'ler farklı, zincir sağlam; dışarıdaki önce")
    void auditInsideOpenTransaction_whileAnotherThreadAudits_chainStaysValid() throws Exception {
        CountDownLatch aRecorded = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        TransactionTemplate tt = new TransactionTemplate(txManager);

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> tt.executeWithoutResult(st -> {
            svc.recordSystemEvent("DOMAIN_EDIT", "INVENTORY", "a.example", "işlem içi");
            aRecorded.countDown();
            try {
                assertThat(bDone.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(aRecorded.await(10, TimeUnit.SECONDS)).isTrue();
        svc.recordSystemEvent("LOGIN", "USER", "b", "işlem dışı");
        bDone.countDown();
        a.get(20, TimeUnit.SECONDS);

        List<AuditLog> rows = chain();
        assertThat(rows).extracting(AuditLog::getSeq).containsExactly(1L, 2L);
        assertThat(rows).extracting(AuditLog::getEventType).containsExactly("LOGIN", "DOMAIN_EDIT");
        // ux_audit_seq kapsamı: yeni yazıcının her satırı (işlem içi → COMMIT sonrası, işlem dışı) kilit damgalı
        assertThat(rows).extracting(AuditLog::getSeqLocked).containsExactly(true, true);
        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).as("zincir çatallanmamalı: %s", v).isTrue();
        assertThat(v.checked()).isEqualTo(2);
    }

    @Test
    @DisplayName("İŞLEM DIŞINDA denetim kaydı EŞZAMANLI — çağrı dönünce satır COMMIT edilmiş ve görünür (R2a-B: k6 gizli değer "
            + "gösterme sınırı audit_log'daki SCRIPTED_SECRET_REVEAL satırlarını bir sonraki istekte sayar); oturum yüklemesi de")
    void outsideTransaction_writtenSynchronously_visibleImmediately() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        svc.recordAction("SCRIPTED_SECRET_REVEAL", "ayse", 7L, 3L, "ADMIN", "SCRIPTED_MONITOR", "12",
                "{\"variable\":\"TOKEN\"}", "10.0.0.1", "ua", "s1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event_type = 'SCRIPTED_SECRET_REVEAL' AND actor_id = 7",
                Integer.class)).as("başka bir bağlantıdan, çağrı döner dönmez").isEqualTo(1);
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("username", "ayse");
        session.setAttribute("userId", 7L);
        session.setAttribute("systemRole", "ADMIN");
        svc.recordAction("SCRIPTED_SECRET_REVEAL", session, new org.springframework.mock.web.MockHttpServletRequest(),
                "SCRIPTED_MONITOR", "12", "{}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event_type = 'SCRIPTED_SECRET_REVEAL' AND actor_id = 7",
                Integer.class)).isEqualTo(2);
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("işlem içinde: COMMIT'ten önce satır YOK, COMMIT'ten sonra var (yanıt dönmeden, aynı iş parçacığında)")
    void deferredUntilCommit() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        AuditLog[] pending = new AuditLog[1];
        tt.executeWithoutResult(st -> {
            pending[0] = svc.persist(event("SETTING_SAVE"));
            assertThat(pending[0]).as("ertelenen kayıt geri döner (henüz id'siz)").isNotNull();
            assertThat(pending[0].getId()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class))
                    .as("COMMIT öncesi hiçbir satır yazılmadı (işlemin kendisi de görmez)").isZero();
        });
        assertThat(chain()).extracting(AuditLog::getEventType).containsExactly("SETTING_SAVE");
        assertThat(pending[0].getId()).as("COMMIT sonrası aynı nesne kaydedildi").isNotNull();
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("dış işlem geri alınırsa denetim satırı YAZILMAZ (bugünkü gibi)")
    void rollback_leavesNoRow() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.executeWithoutResult(st -> {
            svc.recordSystemEvent("DOMAIN_DELETE", "INVENTORY", "x", "geri alınacak");
            st.setRollbackOnly();
        });
        assertThat(chain()).isEmpty();
    }

    @Test
    @DisplayName("iç içe (katılan) işlemlerde birden çok denetim → en dıştaki COMMIT'te çağrı sırasıyla, zincir sağlam")
    void nestedParticipating_writtenInCallOrderAtOutermostCommit() {
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate inner = new TransactionTemplate(txManager);
        outer.executeWithoutResult(st -> {
            svc.recordSystemEvent("E1", "SYSTEM", "r", "1");
            inner.executeWithoutResult(s2 -> svc.recordSystemEvent("E2", "SYSTEM", "r", "2"));
            svc.recordSystemEvent("E3", "SYSTEM", "r", "3");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
        });
        assertThat(chain()).extracting(AuditLog::getEventType).containsExactly("E1", "E2", "E3");
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("tamamlanmakta olan işlemin içinden (COMMIT sonrası geri çağrı) yazılan denetim KAYBOLMAZ")
    void auditFromAfterCommitCallback_isWritten() {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.executeWithoutResult(st -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                svc.recordSystemEvent("AFTER_COMMIT_AUDIT", "SYSTEM", "r", "geri çağrıdan");
            }
        }));
        assertThat(chain()).extracting(AuditLog::getEventType).containsExactly("AFTER_COMMIT_AUDIT");
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("işlem içindeki giriş kaydı: id'ye bağlı takip işleri (coğrafi zenginleştirme) COMMIT sonrası gerçek id ile")
    void loginFollowUpsRunAfterCommitWithRealId() {
        AuditGeoEnricher geo = mock(AuditGeoEnricher.class);
        AuditService s = new AuditService(repo, mock(GeoIpService.class), mock(NewDeviceNotifier.class),
                mock(ClientIpResolver.class), geo);
        s.setChainLockSupport(jdbc, txManager);
        ReflectionTestUtils.setField(s, "fallbackFile", tmp.resolve("audit-fallback.jsonl").toString());
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.executeWithoutResult(st -> {
            s.recordRateLimited("N1", "10.1.1.1", "ua");
            org.mockito.Mockito.verifyNoInteractions(geo);
        });
        Long id = chain().getFirst().getId();
        org.mockito.Mockito.verify(geo).enrichGeoAsync(id, "10.1.1.1");
    }

    private static AuditLog event(String type) {
        AuditLog e = new AuditLog();
        e.setEventType(type);
        e.setEventTime("2026-10-22T10:00:00");
        e.setActor("SYSTEM");
        e.setOutcome("SUCCESS");
        return e;
    }
}
