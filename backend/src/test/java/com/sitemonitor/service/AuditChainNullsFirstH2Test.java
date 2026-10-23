package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Denetim zincirinin 2026-10-23 prod hatası H2'de, PostgreSQL'in NULL sıralamasıyla ({@code DEFAULT_NULL_ORDERING=HIGH}:
 * NULL en büyük → {@code ORDER BY seq DESC}'te ÖNDE — PostgreSQL'in varsayılanı). Varsayılan H2 NULL'ları sona koyduğu
 * için zincirden önce yazılmış (seq NULL) satırlar hiçbir testte "son satır" olmamıştı; prod'da 2026-07-28'den beri her
 * satır seq 1 + GENESIS aldı. Burada: uç sorgusu seq'siz satırları atlar; tek seferlik yeniden başlatma işi bozuk zincire
 * işaretli bir yeniden başlatma satırı yazar, doğrulama oradan başlar, öncesi "doğrulanamaz" sayılır; {@code ux_audit_seq}
 * kapsamındaki yinelenen kilit damgaları yalnız yeniden başlatmanın ALTINDA kaldırılır. Gerçek H2 + gerçek işlem yöneticisi
 * ({@code NOT_SUPPORTED}: satırlar gerçekten COMMIT olur). PostgreSQL'in kendisi: {@code AuditChainPostgresTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditnullsfirst;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditChainNullsFirstH2Test {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired AuditLogRepository repo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path tmp;

    private AuditService svc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", AuditChainRestartJob.MARKER);
        svc = pod();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", AuditChainRestartJob.MARKER);
    }

    private AuditService pod() {
        AuditService s = new AuditService(repo, mock(GeoIpService.class), mock(NewDeviceNotifier.class),
                mock(ClientIpResolver.class), mock(AuditGeoEnricher.class));
        s.setChainLockSupport(jdbc, txManager);
        ReflectionTestUtils.setField(s, "fallbackFile", tmp.resolve("audit-fallback.jsonl").toString());
        return s;
    }

    /** H2 için gerçek kurulum: tür H2 (indeks yok), kilit doğrudan koşar. */
    private AuditChainRestartJob job(AuditService s) {
        return job(s, (name, task) -> Optional.ofNullable(task.get()),
                () -> DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()), () -> false, () -> null);
    }

    private AuditChainRestartJob job(AuditService s, AuditChainRestartJob.LockRunner locks, Supplier<Boolean> pg,
                                     BooleanSupplier indexValid, Supplier<CompletableFuture<?>> kicker) {
        return new AuditChainRestartJob(s, jdbc, locks, pg, indexValid, kicker, System::currentTimeMillis);
    }

    /** Zincirden önce yazılmış satır: seq / hash YOK (2026-07 öncesi kod). */
    private void insertLegacy(String time) {
        jdbc.update("INSERT INTO audit_log (event_type, event_time, actor, outcome) VALUES ('LEGACY_EVENT', ?, 'old', 'SUCCESS')", time);
    }

    /** Hatalı uç sorgusunun yazdığı satır: seq 1 + GENESIS (2a sonrası kod kilit damgası da basar). */
    private void insertDegenerate(String time, boolean locked) {
        jdbc.update("INSERT INTO audit_log (seq, prev_hash, row_hash, seq_locked, event_type, event_time, actor, outcome)"
                + " VALUES (1, 'GENESIS', ?, ?, 'LOGIN', ?, 'alice', 'SUCCESS')",
                Integer.toHexString(time.hashCode()), locked ? Boolean.TRUE : null, time);
    }

    private List<AuditLog> rowsBySeqThenId() {
        return jdbc.queryForList("SELECT id FROM audit_log WHERE seq IS NOT NULL ORDER BY seq, id", Long.class).stream()
                .map(id -> repo.findById(id).orElseThrow()).toList();
    }

    private AuditLog restartRow() {
        return repo.findTopByEventTypeAndSeqIsNotNullOrderBySeqDescIdDesc(AuditService.EVENT_CHAIN_RESTART).orElse(null);
    }

    private Integer marker() {
        List<Integer> r = jdbc.queryForList("SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?",
                Integer.class, AuditChainRestartJob.MARKER);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Bozuk dönem: 2 eski seq'siz satır + 4 seq-1 satırı (kilit damgalı). */
    private void degenerateChain() {
        insertLegacy("2026-06-01T08:00:00");
        insertLegacy("2026-06-02T08:00:00");
        insertDegenerate("2026-07-28T10:00:00", true);
        insertDegenerate("2026-08-15T10:00:00", true);
        insertDegenerate("2026-09-20T10:00:00", true);
        insertDegenerate("2026-10-22T10:00:00", true);
    }

    @Test
    @DisplayName("NULLS FIRST (PostgreSQL gibi): seq'siz eski satırlar varken yeni satırlar artan seq alır, zincir sağlam")
    void legacyNullRows_newRowsChainWithIncreasingSeq() {
        insertLegacy("2026-06-01T08:00:00");
        insertLegacy("2026-06-02T08:00:00");
        // Ortam gerçekten PostgreSQL gibi sıralıyor: DESC'te NULL önde (eski uç sorgusunun "son satırı")
        List<Long> top = jdbc.queryForList("SELECT seq FROM audit_log ORDER BY seq DESC FETCH FIRST 1 ROWS ONLY", Long.class);
        assertThat(top).containsExactly((Long) null);

        svc.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot");
        svc.recordSystemEvent("SCHEMA_PATCH", "SYSTEM", "db", "patch-1");
        svc.recordSystemEvent("LOGIN", "USER", "alice", "login");

        List<AuditLog> rows = rowsBySeqThenId();
        assertThat(rows).extracting(AuditLog::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(rows.get(0).getPrevHash()).isEqualTo("GENESIS");
        assertThat(rows.get(1).getPrevHash()).isEqualTo(rows.get(0).getRowHash());
        assertThat(rows.get(2).getPrevHash()).isEqualTo(rows.get(1).getRowHash());
        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).as("zincir sağlam: %s", v).isTrue();
        assertThat(v.checked()).isEqualTo(3);
        assertThat(v.verifiedFrom()).isNull();
        assertThat(v.unverified()).isZero();
    }

    @Test
    @DisplayName("bozuk zincir → iş yeniden başlatma satırı yazar (seq = uç + 1, GENESIS); doğrulama oradan, öncesi doğrulanamaz")
    void restartJob_degenerateChain_restartRowAndVerifiedSegment() {
        degenerateChain();
        AuditService.ChainVerification before = svc.verifyChain();
        assertThat(before.ok()).as("bugünkü davranış: ikinci seq-1 satırında kırık").isFalse();
        assertThat(before.brokenSeq()).isEqualTo(1L);

        AuditChainRestartJob j = job(svc);
        j.run();

        AuditLog r = restartRow();
        assertThat(r).isNotNull();
        assertThat(r.getSeq()).as("uç (seq 1) + 1").isEqualTo(2L);
        assertThat(r.getPrevHash()).isEqualTo("GENESIS");
        assertThat(r.getSeqLocked()).isTrue();
        assertThat(r.getActor()).isEqualTo("SYSTEM");
        assertThat(r.getDetail()).contains("\"rows_before\":4", "\"max_seq_before\":1", "\"seq1_rows\":4",
                "\"period_from\":\"2026-07-28T10:00:00\"", "\"period_to\":\"2026-10-22T10:00:00\"");
        assertThat(marker()).isEqualTo(1);
        assertThat(j.finished()).as("H2: indeks yok — iş bu pod'da bitti").isTrue();

        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).as("yeniden başlatmadan itibaren sağlam: %s", v).isTrue();
        assertThat(v.verifiedFrom()).isNotNull();
        assertThat(v.verifiedFrom().id()).isEqualTo(r.getId());
        assertThat(v.verifiedFrom().seq()).isEqualTo(2L);
        assertThat(v.verifiedFrom().eventTime()).isEqualTo(r.getEventTime());
        assertThat(v.unverified()).as("4 bozuk satır (seq'siz eski satırlar sayılmaz)").isEqualTo(4);
        assertThat(v.checked()).isEqualTo(1);

        svc.recordSystemEvent("LOGIN", "USER", "bob", "after");
        svc.recordSystemEvent("LOGOUT", "USER", "bob", "after");
        List<AuditLog> rows = rowsBySeqThenId();
        assertThat(rows.subList(rows.size() - 2, rows.size())).extracting(AuditLog::getSeq).containsExactly(3L, 4L);
        assertThat(rows.get(rows.size() - 2).getPrevHash()).isEqualTo(r.getRowHash());
        v = svc.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.checked()).isEqualTo(3);
        assertThat(v.unverified()).isEqualTo(4);

        // Eski satırlar YAZILDIĞI GİBİ: seq / prev_hash / row_hash değişmedi
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq = 1 AND prev_hash = 'GENESIS'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq IS NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("yeniden başlatmadan sonra bir satır kurcalanırsa doğrulama o satırda kırık der (doğrulanan bölüm gerçekten doğrulanır)")
    void verifiedSegment_detectsTampering() {
        degenerateChain();
        job(svc).run();
        svc.recordSystemEvent("E1", "SYSTEM", "r1", "a");
        svc.recordSystemEvent("E2", "SYSTEM", "r2", "b");
        jdbc.update("UPDATE audit_log SET detail = 'TAMPERED' WHERE seq = 3");

        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenSeq()).isEqualTo(3L);
        assertThat(v.verifiedFrom()).isNotNull();
        assertThat(v.unverified()).isEqualTo(4);
    }

    @Test
    @DisplayName("iş idempotent: ikinci pod / açılış ikinci satır yazmaz; nişan silinse bile kilit içindeki denetim yeniden yazmaz")
    void restartJob_isIdempotent() {
        degenerateChain();
        job(svc).run();
        job(pod()).run();                                                   // başka pod (nişan var)
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", AuditChainRestartJob.MARKER);
        AuditChainRestartJob third = job(pod());
        third.run();                                                        // nişan yok ama satır var → yazılmaz

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE event_type = ?", Integer.class,
                AuditService.EVENT_CHAIN_RESTART)).isEqualTo(1);
        assertThat(marker()).as("var olan satır → nişan 0 ile yeniden yazıldı").isZero();
        assertThat(third.finished()).isTrue();
        assertThat(svc.verifyChain().ok()).isTrue();
    }

    @Test
    @DisplayName("nişan kilit İÇİNDE yeniden okunur: kilidi beklerken başka pod bitirdiyse yeniden başlatma yazılmaz")
    void markerReReadInsideLock() {
        degenerateChain();
        AuditChainRestartJob j = job(svc, (name, task) -> {
            // kilidi beklerken başka pod işi bitirip nişanı yazdı (satırı yazmamış olsa bile — nişan karar verir)
            jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, '2026-10-23T00:00:00', 0)",
                    AuditChainRestartJob.MARKER);
            return Optional.ofNullable(task.get());
        }, () -> Boolean.FALSE, () -> false, () -> null);
        j.run();

        assertThat(restartRow()).isNull();
        assertThat(j.finished()).isTrue();
    }

    @Test
    @DisplayName("sağlam zincir: yeniden başlatma YOK, nişan 0, doğrulama bugünkü gibi (verified_from yok)")
    void healthyChain_noRestart() {
        svc.recordSystemEvent("E1", "SYSTEM", "r1", "a");
        svc.recordSystemEvent("E2", "SYSTEM", "r2", "b");
        AuditChainRestartJob j = job(svc);
        j.run();

        assertThat(restartRow()).isNull();
        assertThat(marker()).isZero();
        assertThat(j.finished()).isTrue();
        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.verifiedFrom()).isNull();
        assertThat(v.checked()).isEqualTo(2);
    }

    @Test
    @DisplayName("ux_audit_seq kapsamı: yalnız yeniden başlatmanın ALTINDA yinelenen kilit damgaları kalkar; tekil olanlar ve bölüm dokunulmaz")
    void releaseDuplicateLocks_onlyBelowRestart() {
        insertLegacy("2026-06-01T08:00:00");
        insertDegenerate("2026-07-28T10:00:00", true);
        insertDegenerate("2026-08-15T10:00:00", true);
        insertDegenerate("2026-09-01T10:00:00", false);                     // 2a öncesi: damgasız
        svc.recordSystemEvent("FIXED_BEFORE_RESTART", "SYSTEM", "x", "seq 2, tekil, damgalı");
        AuditChainRestartJob first = job(svc);
        first.run();
        AuditLog r = restartRow();
        assertThat(r.getSeq()).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq = 1 AND seq_locked IS NOT NULL", Integer.class))
                .as("yinelenen seq-1 damgaları kalktı").isZero();
        assertThat(jdbc.queryForObject("SELECT seq_locked FROM audit_log WHERE seq = 2", Boolean.class))
                .as("tekil (yinelenmeyen) seq 2 dokunulmadı").isTrue();

        // Yeniden başlatmadan SONRA eski bir pod iki seq-1 satırı daha yazar (yayılım); yeni pod bölüme yazar
        insertDegenerate(ISO.format(Instant.now()), true);
        insertDegenerate(ISO.format(Instant.now()), true);
        svc.recordSystemEvent("AFTER", "SYSTEM", "y", "bölüm");
        job(pod()).run();                                                   // sonraki açılış: nişan var, temizlik koşar

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq = 1 AND seq_locked IS NOT NULL", Integer.class))
                .as("geç kalan eski pod'un yinelenen satırları da (seq ölçütü)").isZero();
        assertThat(jdbc.queryForObject("SELECT seq_locked FROM audit_log WHERE seq = 2", Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT seq_locked FROM audit_log WHERE seq >= 3 ORDER BY seq", Boolean.class))
                .as("doğrulanan bölüm damgalı kalır").containsExactly(true, true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE seq = 1", Integer.class))
                .as("satırlar silinmez").isEqualTo(5);
        AuditService.ChainVerification v = svc.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.unverified()).as("seq 1 ×5 + seq 2").isEqualTo(6);
    }

    @Test
    @DisplayName("PostgreSQL yolu (sahte tür): indeks geçersizse kurucu yeniden başlatılır; eski pod son 10 dk yazdıysa ertelenir; pod başına en çok 3")
    void indexKick_rules() {
        degenerateChain();
        AtomicInteger kicks = new AtomicInteger();
        boolean[] valid = {false};
        AuditChainRestartJob j = job(svc, (name, task) -> Optional.ofNullable(task.get()), () -> Boolean.TRUE,
                () -> valid[0], () -> { kicks.incrementAndGet(); return CompletableFuture.completedFuture(null); });

        // restart yazılır (H2 kilidi: tür AuditService'te gerçek H2), sonra eski pod ŞİMDİ bir seq-1 satırı yazar
        j.run();
        assertThat(restartRow()).isNotNull();
        assertThat(kicks.get()).as("ilk tur: eski pod yazmadı → kurulum başlatıldı").isEqualTo(1);
        insertDegenerate(ISO.format(Instant.now()), true);
        j.run();
        assertThat(kicks.get()).as("eski pod son 10 dk'da yazdı → ertelendi").isEqualTo(1);
        assertThat(j.finished()).isFalse();

        jdbc.update("UPDATE audit_log SET event_time = '2026-10-23T00:00:00' WHERE seq = 1 AND id > ?", restartRow().getId());
        j.run();
        j.run();
        assertThat(kicks.get()).isEqualTo(3);
        j.run();
        assertThat(kicks.get()).as("pod başına en çok 3").isEqualTo(3);
        assertThat(j.finished()).as("3 denemede olmadı → sonraki açılış").isTrue();

        AuditChainRestartJob ok = job(pod(), (name, task) -> Optional.ofNullable(task.get()), () -> Boolean.TRUE,
                () -> true, () -> { kicks.incrementAndGet(); return null; });
        ok.run();
        assertThat(ok.finished()).as("indeks geçerli → bitti, kurulum yok").isTrue();
        assertThat(kicks.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("kilit başka pod'da → hiçbir şey yapılmaz, deneme sayılmaz; hata 24 denemede durur; tür okunamazsa yeniden dene")
    void lockElsewhere_andFailuresCapped() {
        degenerateChain();
        AuditChainRestartJob busy = job(svc, (name, task) -> Optional.empty(), () -> Boolean.FALSE, () -> false, () -> null);
        busy.run();
        assertThat(restartRow()).isNull();
        assertThat(busy.failures()).isZero();
        assertThat(busy.finished()).isFalse();

        // Veritabanı türü okunamıyor: yeniden başlatma yazılmaz, deneme sayılır
        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.execute(any(ConnectionCallback.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        AuditService noDialect = pod();
        noDialect.setChainLockSupport(broken, txManager);
        AtomicInteger calls = new AtomicInteger();
        AuditChainRestartJob failing = job(noDialect, (name, task) -> { calls.incrementAndGet(); return Optional.ofNullable(task.get()); },
                () -> Boolean.FALSE, () -> false, () -> null);
        for (int i = 0; i < 30; i++) failing.run();
        assertThat(calls.get()).as("pod başına en çok 24 başarısız deneme").isEqualTo(AuditChainRestartJob.MAX_ATTEMPTS);
        assertThat(failing.failures()).isEqualTo(AuditChainRestartJob.MAX_ATTEMPTS);
        assertThat(restartRow()).isNull();
        assertThat(marker()).as("başarısızlıkta nişan yazılmaz").isNull();
    }
}
