package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H paketi B2 (2026-11-26): {@code verifyChain} anahtar-küme sayfalar (yalnız {@code seq} sırasıyla, sayfa başına 1000 yeni
 * satır + önceki sayfanın son satırı örtüşme; bağımsız inceleme: eşitlik kırıcı {@code id} yok — dizin onu taşımaz), pod
 * başına tek doğrulama çalışır (ikincisi 409 {@code AUDIT_VERIFY_RUNNING}) ve süre sınırında biten tarama "tamamlanamadı"
 * döner. Sonuç ESKİ OFSET yöntemiyle AYNI — kıyas (oracle) bu testteki {@link #oldOffsetVerify} (H paketi öncesi gövdenin
 * birebir kopyası); tek bilinçli fark: aynı seq'li iki satır (birbirine bağlansa bile) çataldır. Gerçek H2 + gerçek işlem
 * yöneticisi; satırlar gerçekten COMMIT olur, her test sonunda silinir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditverifykeyset;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditVerifyChainKeysetTest {

    /** 3 sayfa (1000 + 1000 + 50) — sayfa sınırları ve son kısmi sayfa denenir. */
    private static final int ROWS = 2050;

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

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log");
    }

    private void chain(int n) {
        for (int i = 1; i <= n; i++) svc.recordSystemEvent("E" + i, "SYSTEM", "r" + i, "d" + i);
    }

    @Test
    @DisplayName("temiz zincir (3 sayfa): yeni yöntem = eski OFSET yöntemi (ok, denetlenen sayı)")
    void cleanChain_sameAsOffset() {
        chain(ROWS);
        AuditService.ChainVerification now = svc.verifyChain();
        AuditService.ChainVerification old = oldOffsetVerify();
        assertThat(now.ok()).isTrue();
        assertThat(now.incomplete()).isFalse();
        assertThat(now.checked()).isEqualTo(ROWS).isEqualTo(old.checked());
        assertThat(old.ok()).isTrue();
    }

    @Test
    @DisplayName("kurcalanmış satır (2. sayfada): yeni = eski — aynı kırık seq / id / denetlenen sayı")
    void tampered_sameAsOffset() {
        chain(ROWS);
        jdbc.update("UPDATE audit_log SET detail = 'TAMPERED' WHERE seq = 1500");
        AuditService.ChainVerification now = svc.verifyChain();
        AuditService.ChainVerification old = oldOffsetVerify();
        assertThat(now.ok()).isFalse();
        assertThat(now.brokenSeq()).isEqualTo(1500L).isEqualTo(old.brokenSeq());
        assertThat(now.brokenId()).isEqualTo(old.brokenId());
        assertThat(now.checked()).isEqualTo(1499L).isEqualTo(old.checked());
    }

    @Test
    @DisplayName("saklama silmesi (ilk 1200 satır yok): yeni = eski — kalan ilk satırdan, verifiedFrom retention")
    void retentionAnchor_sameAsOffset() {
        chain(ROWS);
        jdbc.update("DELETE FROM audit_log WHERE seq <= 1200");
        AuditService.ChainVerification now = svc.verifyChain();
        AuditService.ChainVerification old = oldOffsetVerify();
        assertThat(now.ok()).isTrue().isEqualTo(old.ok());
        assertThat(now.checked()).isEqualTo(ROWS - 1200L).isEqualTo(old.checked());
        assertThat(now.verifiedFrom()).isNotNull();
        assertThat(now.verifiedFrom().kind()).isEqualTo(AuditService.VerifiedFrom.RETENTION);
        assertThat(now.verifiedFrom().seq()).isEqualTo(1201L);
    }

    @Test
    @DisplayName("çatal (aynı seq iki satır, sayfa sınırında): ikisi de kırık bulur — aynı seq ve denetlenen sayı (eşit seq'te satır atlanmaz)")
    void fork_atPageBoundary_detected() {
        chain(1200);
        // seq 1000 (ilk sayfanın SON satırı) için ikinci bir satır: aynı prev_hash, kendi doğru hash'i — çatallanmış yazıcı
        AuditLog orig = repo.findBySeqGreaterThanEqualOrderBySeqAsc(1000L, PageRequest.of(0, 1)).get(0);
        AuditLog fork = new AuditLog();
        fork.setSeq(1000L);
        fork.setPrevHash(orig.getPrevHash());
        fork.setEventType("FORK");
        fork.setEventTime(orig.getEventTime());
        fork.setActor("SYSTEM");
        fork.setOutcome("SUCCESS");
        fork.setRowHash(AuditService.sha256(AuditService.canonical(fork) + AuditService.SEP + orig.getPrevHash()));
        repo.save(fork);

        AuditService.ChainVerification now = svc.verifyChain();
        AuditService.ChainVerification old = oldOffsetVerify();
        assertThat(now.ok()).isFalse();
        assertThat(old.ok()).isFalse();
        assertThat(now.brokenSeq()).isEqualTo(1000L).isEqualTo(old.brokenSeq());
        assertThat(now.checked()).isEqualTo(1000L).isEqualTo(old.checked());
    }

    /** seq'i verilen satıra çatal: aynı seq, aynı prev_hash, kendi doğru hash'i (çatallanmış yazıcının yazdığı gibi). */
    private AuditLog forkOf(long seq) {
        AuditLog orig = repo.findBySeqGreaterThanEqualOrderBySeqAsc(seq, PageRequest.of(0, 1)).get(0);
        AuditLog fork = new AuditLog();
        fork.setSeq(seq);
        fork.setPrevHash(orig.getPrevHash());
        fork.setEventType("FORK");
        fork.setEventTime(orig.getEventTime());
        fork.setActor("SYSTEM");
        fork.setOutcome("SUCCESS");
        fork.setRowHash(AuditService.sha256(AuditService.canonical(fork) + AuditService.SEP + orig.getPrevHash()));
        return repo.save(fork);
    }

    @Test
    @DisplayName("çatal sayfa İÇİNDE (seq 500) ve ikinci sayfanın ortasında (seq 1500): yeni = eski — aynı seq, aynı kimlik (büyük olan), aynı sayı")
    void fork_insidePage_sameAsOffset() {
        chain(ROWS);
        for (long seq : new long[] {500L, 1500L}) {
            AuditLog fork = forkOf(seq);
            AuditService.ChainVerification now = svc.verifyChain();
            AuditService.ChainVerification old = oldOffsetVerify();
            assertThat(now.ok()).isFalse();
            assertThat(now.brokenSeq()).isEqualTo(seq).isEqualTo(old.brokenSeq());
            assertThat(now.brokenId()).isEqualTo(fork.getId()).isEqualTo(old.brokenId());
            assertThat(now.checked()).isEqualTo(seq).isEqualTo(old.checked());
            jdbc.update("DELETE FROM audit_log WHERE id = ?", fork.getId());
        }
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("bilinçli fark: aynı seq'li ikinci satır öncekine doğru BAĞLANSA bile çataldır (eski yöntem kaçırırdı); zincirin ucunda da")
    void linkedDuplicateSeq_isBroken() {
        chain(10);
        AuditLog tip = repo.findTopBySeqIsNotNullOrderBySeqDesc().orElseThrow();
        AuditLog dup = new AuditLog();
        dup.setSeq(tip.getSeq());   // aynı seq, ama öncekine (tip) bağlı — hash kontrolü tek başına geçerdi
        dup.setPrevHash(tip.getRowHash());
        dup.setEventType("DUP");
        dup.setEventTime(tip.getEventTime());
        dup.setActor("SYSTEM");
        dup.setOutcome("SUCCESS");
        dup.setRowHash(AuditService.sha256(AuditService.canonical(dup) + AuditService.SEP + tip.getRowHash()));
        dup = repo.save(dup);
        assertThat(oldOffsetVerify().ok()).as("eski yöntem bağlı kopyayı görmezdi").isTrue();
        AuditService.ChainVerification now = svc.verifyChain();
        assertThat(now.ok()).isFalse();
        assertThat(now.brokenSeq()).isEqualTo(10L);
        assertThat(now.brokenId()).isEqualTo(dup.getId());
        assertThat(now.checked()).isEqualTo(10L);
    }

    @Test
    @DisplayName("kapı: doğrulama sayfası yalnız seq sırasıyla, SABİT sınırla — eşitlik kırıcı (id) yok (dizin onu taşımaz)")
    void pageQueryShape() throws Exception {
        String sql = AuditLogRepository.class.getMethod("findChainPageFrom", Long.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        assertThat(sql).isEqualTo("SELECT * FROM audit_log WHERE seq >= :seq ORDER BY seq ASC LIMIT "
                + AuditLogRepository.CHAIN_PAGE_FETCH);
        assertThat(AuditService.VERIFY_PAGE_SIZE).isEqualTo(AuditLogRepository.CHAIN_PAGE_FETCH - 1);
    }

    @Test
    @DisplayName("süre sınırı: bitmeyen tarama incomplete + son denetlenen seq — ok DEĞİL, kırık da değil")
    void timeLimit_incomplete() {
        chain(ROWS);
        AtomicLong clock = new AtomicLong();
        svc.verifyClock = () -> clock.getAndAdd(1_000_000_000L);   // her okuma +1 sn
        svc.setVerifyMaxMillis(1500L);                              // ilk sayfa sığar, ikincisi değil
        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.incomplete()).isTrue();
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenSeq()).isNull();
        assertThat(v.checked()).isEqualTo(1000L);
        assertThat(v.checkedToSeq()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("pod başına tek doğrulama: biri sürerken ikinci istek BEKLEMEZ — 409 AUDIT_VERIFY_RUNNING; biten doğrulama kilidi bırakır")
    void secondVerification_conflicts() throws Exception {
        AuditLogRepository mockRepo = mock(AuditLogRepository.class);
        when(mockRepo.findTopByEventTypeAndSeqIsNotNullOrderBySeqAsc(AuditService.EVENT_CHAIN_RESTART)).thenReturn(Optional.empty());
        when(mockRepo.findTopBySeqIsNotNullOrderBySeqAsc()).thenReturn(Optional.empty());
        AuditService s = new AuditService(mockRepo, mock(GeoIpService.class), mock(NewDeviceNotifier.class),
                mock(ClientIpResolver.class), mock(AuditGeoEnricher.class));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        s.verifyClock = () -> {
            inside.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("bekleme süresi doldu");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0L;
        };
        Thread first = new Thread(s::verifyChain, "verify-first");
        first.start();
        try {
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(s::verifyChain)
                    .isInstanceOf(GlobalExceptionHandler.CodedConflictException.class)
                    .extracting(e -> ((GlobalExceptionHandler.CodedConflictException) e).getCode())
                    .isEqualTo(AuditService.VERIFY_RUNNING);
        } finally {
            release.countDown();
            first.join(10_000);
        }
        s.verifyClock = () -> 0L;
        assertThat(s.verifyChain().ok()).isTrue();   // kilit bırakıldı
    }

    /** H paketi ÖNCESİ gövdenin birebir kopyası (OFSET sayfalama) — kıyas için. */
    private AuditService.ChainVerification oldOffsetVerify() {
        AuditLog restart = repo.findTopByEventTypeAndSeqIsNotNullOrderBySeqAsc(AuditService.EVENT_CHAIN_RESTART).orElse(null);
        long fromSeq;
        String prev;
        AuditService.VerifiedFrom from;
        long unverified;
        if (restart != null) {
            fromSeq = restart.getSeq();
            prev = AuditService.GENESIS;
            from = new AuditService.VerifiedFrom(restart.getId(), restart.getSeq(), restart.getEventTime(), AuditService.VerifiedFrom.RESTART);
            unverified = repo.countBySeqLessThan(fromSeq);
        } else {
            AuditLog lowest = repo.findTopBySeqIsNotNullOrderBySeqAsc().orElse(null);
            if (lowest == null) return new AuditService.ChainVerification(true, null, null, 0);
            fromSeq = lowest.getSeq();
            prev = lowest.getPrevHash() == null ? AuditService.GENESIS : lowest.getPrevHash();
            from = AuditService.GENESIS.equals(prev) ? null
                    : new AuditService.VerifiedFrom(lowest.getId(), lowest.getSeq(), lowest.getEventTime(), AuditService.VerifiedFrom.RETENTION);
            unverified = 0L;
        }
        long checked = 0;
        int page = 0, size = 1000;
        while (true) {
            List<AuditLog> rows = repo.findBySeqGreaterThanEqualOrderBySeqAsc(fromSeq, PageRequest.of(page, size));
            if (rows.isEmpty()) break;
            for (AuditLog r : rows) {
                String expectHash = AuditService.sha256(AuditService.canonical(r) + AuditService.SEP + prev);
                String storedPrev = r.getPrevHash() == null ? AuditService.GENESIS : r.getPrevHash();
                if (!prev.equals(storedPrev) || expectHash == null || !expectHash.equals(r.getRowHash())) {
                    return new AuditService.ChainVerification(false, r.getSeq(), r.getId(), checked, from, unverified);
                }
                prev = r.getRowHash();
                checked++;
            }
            page++;
        }
        return new AuditService.ChainVerification(true, null, null, checked, from, unverified);
    }
}
