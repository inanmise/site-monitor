package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Denetim zincirinin TEK SEFERLİK yeniden başlatılması + {@code ux_audit_seq}'in kurulabilir kılınması (2026-10-23 prod hatası,
 * kullanıcı kararı: "yeni zincir dönemi").
 *
 * <p><b>Hata.</b> Zincirden önce yazılmış (seq NULL) denetim satırlarını hâlâ tutan PostgreSQL veritabanında zincirin ucu
 * {@code ORDER BY seq DESC LIMIT 1} ile okunuyordu; PostgreSQL NULL'ları DESC'te öne koyar → uç hep seq'siz bir satırdı ve
 * 2026-07-28'den beri her yeni satır {@code seq = 1}, {@code prev_hash = GENESIS} aldı. Doğrulama ikinci seq-1 satırında
 * "kırık" diyordu; çok pod Sürüm 2a'nın {@code ux_audit_seq} tekil indeksi (yalnız {@code seq_locked} satırları) yinelenen
 * seq 1'ler yüzünden hiç kurulamadı — çatal koruması kapalıydı. Uç sorgusu artık seq'siz satırları atlar
 * ({@link com.sitemonitor.repository.AuditLogRepository#findTopBySeqIsNotNullOrderBySeqDescIdDesc}).
 *
 * <p><b>Bu iş.</b> (1) Zincir bozuksa (birden fazla {@code seq = 1}) ve yeniden başlatma satırı yoksa yazıcılarla AYNI zincir
 * kilidi altında bir {@value AuditService#EVENT_CHAIN_RESTART} satırı yazar ({@code seq = uç + 1}, {@code prev_hash = GENESIS};
 * {@link AuditService#restartChainIfDegenerate}); doğrulama artık oradan başlar, öncesi "onarımdan önce yazıldı, doğrulanamaz"
 * sayılır. ESKİ SATIRLARIN HİÇBİR hash'li kolonu yeniden yazılmaz. Nişan ({@value #MARKER}) yalnız başarıdan sonra, kilit
 * İÇİNDE yeniden okunarak yazılır. (2) Her koşuda (ucuz): yeniden başlatma satırının seq'inin ALTINDA kalan, kilit damgalı
 * ({@code seq_locked}) satırlar arasında seq'i YİNELENENLERİN damgası koşullu UPDATE ile kaldırılır ({@code seq_locked} hash'e
 * girmez) — doğrulanan bölüme dokunulmaz; seq ölçütü, yeniden başlatmadan SONRA eski bir pod'un yazdığı seq-1 satırlarını da
 * kapsar. (3) PostgreSQL'de indeks geçerli değilse ertelenmiş indeks kurucusu ({@link DeferredIndexBuilder}) bir kez daha
 * başlatılır (kurucu başarısız bir indeksi aynı açılışta yalnız 60 sn sonra BİR kez daha dener, sonra ancak sonraki açılışta —
 * bu iş açılıştan 10 dk sonra koştuğu için o açılışın kurulumu çoktan düşmüş olur). Son 10 dk içinde seq 1 yazan eski bir pod
 * varsa (yayılım sürüyor) kurulum bir saat ertelenir: indeks varken eski pod'un ikinci seq-1 satırı tekil ihlale düşüp
 * fallback dosyasına giderdi. Pod başına en çok {@value #MAX_INDEX_KICKS} kurulum; indeks geçerli olunca bu pod durur.
 *
 * <p><b>Zamanlama.</b> Açılış yolunda değil: açılıştan {@code site.monitor.audit.chain-restart.initial-delay-ms} (10 dk) sonra
 * — yayılım çoğunlukla bitmiştir — sonra saatte bir, iş bitene dek. Kendi {@code scheduler_lock}'u ({@value #LOCK}) ile tek pod.
 * Pod başına en çok {@value #MAX_ATTEMPTS} başarısız deneme (veritabanı türü / nişan okunamaz, yazım hatası …), sonra ERROR.
 *
 * <p><b>Yayılım.</b> 20.168.0 pod'ları yayılım sürerken hâlâ {@code seq = 1 / GENESIS} yazar: o satırlar doğrulanan bölümün
 * dışındadır ve {@code unverified} içinde sayılır. Geri dönüş: yeniden başlatma satırı kalır; eski kodun doğrulaması (bugünkü
 * gibi) kırık der.
 */
@Slf4j
@Service
public class AuditChainRestartJob {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String MARKER = "2026-10-23-audit-chain-restart";
    static final String LOCK = "audit-chain-restart";
    static final int MAX_ATTEMPTS = 24;
    static final int MAX_INDEX_KICKS = 3;
    static final String INDEX = "ux_audit_seq";
    /** Bu kadar süredir seq < yeniden başlatma seq'i yazan yoksa eski pod'lar gitmiş sayılır (indeks kurulabilir). */
    static final long STRAGGLER_QUIET_MS = 10 * 60_000L;

    /**
     * Doğrulanan bölümün ALTINDA (seq &lt; yeniden başlatma seq'i), kilit damgası taşıyan ve seq'i yinelenen satırların
     * damgası kaldırılır — {@code ux_audit_seq} yalnız damgalı satırları kapsar. Koşullu (yalnız hâlâ damgalı); idempotent.
     */
    static final String SQL_RELEASE_DUPLICATE_LOCKS = "UPDATE audit_log SET seq_locked = NULL"
            + " WHERE seq_locked IS NOT NULL AND seq < ?"
            + " AND seq IN (SELECT d.seq FROM audit_log d WHERE d.seq_locked IS NOT NULL AND d.seq < ?"
            + " GROUP BY d.seq HAVING COUNT(*) > 1)";
    /** Yeniden başlatmadan SONRA, bölümün altına (seq 1) yeni satır yazan eski pod var mı (son {@link #STRAGGLER_QUIET_MS}). */
    static final String SQL_STRAGGLERS = "SELECT COUNT(*) FROM audit_log WHERE id > ? AND seq < ? AND event_time >= ?";
    static final String SQL_INDEX_VALID = "SELECT i.indisvalid FROM pg_class c"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_index i ON i.indexrelid = c.oid"
            + " WHERE n.nspname = current_schema() AND c.relname = '" + INDEX + "'";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Kilit altında koşturucu — üretimde {@code SchedulerService.callWithSchedulerLock}; boş = kilit başka pod'da. */
    @FunctionalInterface
    interface LockRunner {
        Optional<Boolean> call(String lock, Supplier<Boolean> task);
    }

    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final LockRunner locks;
    /** Üç durumlu tür: TRUE PostgreSQL, FALSE başka, null OKUNAMADI (sonra yeniden dene). */
    private final Supplier<Boolean> postgres;
    private final BooleanSupplier indexValid;
    private final Supplier<CompletableFuture<?>> indexKicker;
    private final LongSupplier clock;

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger kicks = new AtomicInteger();
    private volatile boolean finished;
    private volatile CompletableFuture<?> lastKick;

    @Autowired
    public AuditChainRestartJob(AuditService audit, JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler) {
        this(audit, jdbc, (name, task) -> {
                    SchedulerService s = scheduler.getIfAvailable();
                    return s == null ? Optional.empty() : s.callWithSchedulerLock(name, task);
                },
                () -> DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource()),
                null,
                () -> new DeferredIndexBuilder(jdbc).startInBackground(),
                System::currentTimeMillis);
    }

    AuditChainRestartJob(AuditService audit, JdbcTemplate jdbc, LockRunner locks, Supplier<Boolean> postgres,
                         BooleanSupplier indexValid, Supplier<CompletableFuture<?>> indexKicker, LongSupplier clock) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.locks = locks;
        this.postgres = postgres;
        this.indexValid = indexValid != null ? indexValid : this::uniqueIndexValid;
        this.indexKicker = indexKicker;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${site.monitor.audit.chain-restart.initial-delay-ms:600000}",
            fixedDelayString = "${site.monitor.audit.chain-restart.interval-ms:3600000}")
    public void run() {
        if (finished || failures.get() >= MAX_ATTEMPTS) return;
        try {
            Optional<Boolean> done = locks.call(LOCK, this::runLocked);   // boş: kilit başka pod'da — sonraki tur
            if (done.isPresent()) {
                failures.set(0);
                if (done.get()) finished = true;
            }
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Denetim zinciri yeniden başlatma işi {} denemede tamamlanamadı — bu pod denemeyi bırakıyor"
                        + " (sonraki açılış yeniden dener): {}", n, e.toString());
            } else {
                log.warn("Denetim zinciri yeniden başlatma işi başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Kilit altında bir tur. Dönüş: bu pod için iş bitti mi (bir daha koşmaz). Hata yukarı çıkar (deneme sayılır). */
    Boolean runLocked() {
        Boolean marker = markerExists();
        if (marker == null) throw new IllegalStateException("nişan okunamadı");
        if (!marker) {
            AuditService.ChainRestart r = audit.restartChainIfDegenerate();
            if (r.outcome() == AuditService.RestartOutcome.WRITTEN) {
                AuditLog row = r.row();
                log.warn("Denetim zinciri YENİDEN BAŞLATILDI (bozuk dönem: birden fazla seq 1): yeniden başlatma satırı id={} seq={}"
                        + " — doğrulama buradan başlar, öncesi onarımdan önce yazıldı, doğrulanamaz. Ayrıntı: {}",
                        row == null ? null : row.getId(), row == null ? null : row.getSeq(), row == null ? null : row.getDetail());
            }
            jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                    MARKER, ISO.format(Instant.ofEpochMilli(clock.getAsLong())),
                    r.outcome() == AuditService.RestartOutcome.WRITTEN ? 1 : 0);
        }
        AuditLog restart = audit.latestChainRestart();
        if (restart == null || restart.getSeq() == null) return true;   // zincir hiç bozulmamış — iş yok
        int released = jdbc.update(SQL_RELEASE_DUPLICATE_LOCKS, restart.getSeq(), restart.getSeq());
        if (released > 0) {
            log.info("Denetim zinciri: yeniden başlatma öncesi {} satırın yinelenen seq kilit damgası kaldırıldı (ux_audit_seq kapsamı)",
                    released);
        }
        return ensureUniqueIndex(restart);
    }

    /** Dönüş: bitti mi. PostgreSQL değilse indeks yok (bitti); geçerliyse bitti; değilse kurucuyu başlatır (sınırlı). */
    private boolean ensureUniqueIndex(AuditLog restart) {
        Boolean pg = postgres.get();
        if (pg == null) throw new IllegalStateException("veritabanı türü okunamadı — sonra yeniden denenecek");
        if (!pg) return true;
        if (indexValid.getAsBoolean()) return true;
        String since = ISO.format(Instant.ofEpochMilli(clock.getAsLong() - STRAGGLER_QUIET_MS));
        Integer recent = jdbc.queryForObject(SQL_STRAGGLERS, Integer.class, restart.getId(), restart.getSeq(), since);
        if (recent != null && recent > 0) {
            log.info("Denetim zinciri: eski sürüm bir pod hâlâ seq 1 yazıyor ({} satır, son {} dk) — {} kurulumu bir saat ertelendi",
                    recent, STRAGGLER_QUIET_MS / 60_000, INDEX);
            return false;
        }
        if (kicks.get() >= MAX_INDEX_KICKS) {
            log.warn("Denetim zinciri: {} bu pod'da {} denemede geçerli olmadı — sonraki açılış yeniden dener", INDEX, MAX_INDEX_KICKS);
            return true;
        }
        kicks.incrementAndGet();
        lastKick = indexKicker.get();
        log.info("Denetim zinciri: {} geçerli değil — ertelenmiş indeks kurulumu yeniden başlatıldı", INDEX);
        return false;
    }

    /** Nişan var mı: true / false; okunamazsa null. */
    private Boolean markerExists() {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, MARKER);
            return n != null && n > 0;
        } catch (RuntimeException e) {
            log.debug("Denetim zinciri nişanı okunamadı: {}", e.getMessage());
            return null;
        }
    }

    private boolean uniqueIndexValid() {
        List<Boolean> v = jdbc.queryForList(SQL_INDEX_VALID, Boolean.class);
        return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
    }

    // ── test görünürlüğü ──
    boolean finished() { return finished; }
    int failures() { return failures.get(); }
    CompletableFuture<?> lastIndexKick() { return lastKick; }
}
