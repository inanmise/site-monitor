package com.sitemonitor.service;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Denetim (audit) yazım servisi — kullanıcı/sistem EYLEMLERİ (izleme-check sonuçları DEĞİL; o activity_log).
 *
 * <b>Kurcalanamazlık:</b> tüm yazımlar tek {@link #persist} hunisinden geçer; JVM-içi kilit altında bir monoton
 * {@code seq} + zincir hash'i ({@code row_hash = SHA-256(değişmez alanlar + prev_hash)}) üretilir. Geo/PTR
 * alanları hash'e girmez (async backfill zinciri kırmaz). DB yazımı hata verirse kayıt bir fallback JSONL
 * dosyaya yazılır + ERROR loglanır ve <b>çağrıya istisna fırlatılmaz</b> → asıl işlem asla düşmez, kayıt kaybolmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepo;
    private final GeoIpService geoIpService;
    @org.springframework.context.annotation.Lazy
    private final NewDeviceNotifier newDeviceNotifier;
    private final ClientIpResolver clientIpResolver;
    /** E2: geo/PTR zenginleştirmesi AYRI bean'de — proxy'den geçmezse @Async çalışmaz (bkz. AuditGeoEnricher). */
    private final AuditGeoEnricher geoEnricher;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String GENESIS = "GENESIS";
    private static final char SEP = '';   // unit separator — canonical alan ayırıcı

    /** Zincir sırası + son-hash okuması yarış olmadan yürüsün diye tek JVM kilidi (tek pod → maliyet önemsiz). */
    private final ReentrantLock chainLock = new ReentrantLock();

    @Value("${site.monitor.audit.brute-force-window-seconds:600}")
    private int bruteForceWindowSeconds;

    @Value("${site.monitor.audit.geo-velocity-window-seconds:3600}")
    private int geoVelocityWindowSeconds;

    @Value("${site.monitor.audit.office-start-hour:8}")
    private int officeStartHour;

    @Value("${site.monitor.audit.office-end-hour:20}")
    private int officeEndHour;

    /** Mesai penceresinin hesaplandığı saat dilimi — kurumun dilimi (crontab'larla aynı). */
    @Value("${site.monitor.audit.timezone:Europe/Istanbul}")
    private String auditTimezone;

    @Value("${site.monitor.audit.fallback-file:logs/audit-fallback.jsonl}")
    private String fallbackFile;

    /**
     * Birincil fallback yazılamazsa kullanılan ikinci yer (prod kapısı 2026-09-25, P3-4): prod kök dosya
     * sistemi salt-okunur (helm {@code readOnlyRootFilesystem: true}) ve eski göreli varsayılan
     * {@code /app/logs}'a çözülüyordu — DB hatasında kayıt dosyaya da YAZILAMIYOR, dosya hiç oluşmadığı için
     * "bekleyen 0" görünüyordu. Varsayılan artık log dizini (application.properties); o da yazılamazsa
     * sistem geçici dizini ({@code /tmp} prod'da yazılabilir emptyDir). Testler kendi dizinini verir.
     */
    private String fallbackSecondaryDir = System.getProperty("java.io.tmpdir");

    /** İki yere de yazılamayan (TAMAMEN kaybolan) kayıt sayısı — bekleyen sayısına eklenir: "temiz" yalanı olmasın. */
    private final java.util.concurrent.atomic.AtomicLong droppedFallbackCount = new java.util.concurrent.atomic.AtomicLong();

    private Path secondaryFallbackPath() {
        String dir = fallbackSecondaryDir;
        if (dir == null || dir.isBlank()) return null;
        Path name = Path.of(fallbackFile).getFileName();
        return Path.of(dir, "site-monitor", name == null ? "audit-fallback.jsonl" : name.toString());
    }

    /**
     * Fallback dosyasındaki (DB'ye yazılamamış) denetim kaydı sayısı — 0 ise sorun yok.
     *
     * <p>Neden gerekiyor: bu dosya denetim izinin son çaresi ama <b>geri okuyan hiçbir kod yoktu</b>.
     * DB yazımı başarısız olduğunda kayıt sessizce dosyaya düşüyor, kimse haberdar olmuyor ve o
     * kayıtlar denetim zincirine hiç girmiyordu. Sayaç en azından "kaybımız var" demeyi mümkün
     * kılar; açılışta bir kez WARN'lanır ve sistem durumundan okunabilir.
     *
     * <p>Dosya okunamıyorsa {@code -1} döner (bilinmiyor ≠ temiz).
     */
    public long pendingFallbackAuditCount() {
        try {
            long n = countLines(Path.of(fallbackFile));
            Path second = secondaryFallbackPath();
            if (second != null && !second.equals(Path.of(fallbackFile))) n += countLines(second);
            return n + droppedFallbackCount.get();   // hiçbir yere yazılamayanlar da "denetim izinde yok"
        } catch (Exception e) {
            log.debug("Denetim fallback dosyası okunamadı ({}): {}", fallbackFile, e.getMessage());
            return -1L;
        }
    }

    private static long countLines(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return 0L;
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        }
    }

    /** Açılışta bir kez uyar — dosyada kayıt varsa denetim izinde BOŞLUK var demektir. */
    @jakarta.annotation.PostConstruct
    void warnIfFallbackPending() {
        long n = pendingFallbackAuditCount();
        if (n > 0) {
            log.warn("⚠ Denetim izinde BOŞLUK: {} kayıt DB'ye yazılamamış ve {} dosyasında bekliyor. "
                    + "Bu kayıtlar denetim zincirinde YOK; dosya elle incelenmeli.", n, fallbackFile);
        }
    }

    // ── Merkezi persist (kilit + hash zinciri + fallback) ────────────────────────

    /** TÜM denetim yazımlarının tek hunisi. Başarısızsa fallback dosyaya yazar, istisna fırlatmaz. */
    AuditLog persist(AuditLog e) {
        return persist(e, null);
    }

    /**
     * {@link #persist(AuditLog)} + kayıt yazıldıktan SONRA koşacak takip işi ({@code afterSave}; yazılamazsa çağrılmaz).
     *
     * <p><b>Açık bir işlemin içinde (çok pod R2a, MP-38, 2026-10-22):</b> kayıt şimdi YAZILMAZ — dış işlem COMMIT olunca
     * kendi işleminde ({@code REQUIRES_NEW}) zincir kilidiyle eklenir; dış işlem geri alınırsa satır hiç yazılmaz (bugünkü
     * gibi: geri alınan işlemin denetimi yok). Eskiden işlem içindeki kayıt zincir kilidini ATLIYORDU (kilit dış COMMIT'e
     * dek tutulurdu): işlemdeki satır henüz görünmezken başka bir yazıcı (başka pod ya da aynı pod'da işlem dışı yazım) aynı
     * son satırı okuyup AYNI {@code seq} + {@code prev_hash} ile ekliyor, zincir çatallanıyor ve bütünlük denetimi sahte bir
     * "kurcalama" gösteriyordu. COMMIT, {@code @Transactional} metottan dönmeden (aynı istek iş parçacığında) olur — kayıt
     * yanıttan önce yazılmış olur. Bu durumda dönen değer HENÜZ YAZILMAMIŞ kayıttır ({@code id} boş; anomali bayrakları
     * hesaplanmış); {@code afterSave} yazımdan sonra çağrılır.
     */
    AuditLog persist(AuditLog e, java.util.function.Consumer<AuditLog> afterSave) {
        if (deferToCommit(e, afterSave)) return e;
        boolean completing = transactionCompleting() && chainSupported();
        AuditLog saved = persistNow(e, completing);
        runAfterSave(saved, afterSave);
        return saved;
    }

    /**
     * Kaydı ŞİMDİ yazar. {@code ownTransaction}: kendi ({@code REQUIRES_NEW}) işleminde, zincir kilidiyle — dış işlemin
     * COMMIT sonrası geri çağrısından ya da tamamlanmakta olan bir işlemden çağrılır (orada veri erişimi hâlâ dış işleme
     * KATILIR ve hiçbir COMMIT izlemez; Spring belgesi: "Use PROPAGATION_REQUIRES_NEW for any transactional operation that
     * is called from here").
     */
    private AuditLog persistNow(AuditLog e, boolean ownTransaction) {
        try {
            boolean pg = ownTransaction ? chainSupported() && isPostgres() : useDbChainLock();
            if (pg) {
                // Çok pod (2026-10-09): son satırı okuma + ekleme TEK işlemde, PostgreSQL işlem-düzeyi advisory lock
                // altında — kilit COMMIT'te bırakılır, sıradaki yazıcı (bu ya da başka pod) yeni satırı görür. JVM kilidi
                // BURADA ALINMAZ (R2a, MP-38): advisory lock zaten her yazıcıyı sıralar; JVM kilidini tutarken havuzdan bağlantı
                // beklemek, COMMIT sonrası yazımda dış bağlantısını tutan iş parçacıklarıyla çapraz beklemeye yol açardı
                // (bağlantı önce, kilit sonra — kilidi tutan hiçbir zaman bağlantı beklemez).
                return appendLocked(e, true, true);
            }
            chainLock.lock();
            try {
                // H2 / diğer: JVM kilidi COMMIT'i de kapsar (advisory lock yok) — kendi işleminde ya da deponun işleminde.
                // Dış işlemin içindeki eski yol (ertelenemedi: senkronizasyon / işlem altyapısı yok) kilit damgası YAZMAZ —
                // tekil indeks ihlali çağıranın işlemini zehirlemesin.
                // Kilit damgası (seq_locked) YALNIZ veritabanının PostgreSQL OLMADIĞI BİLİNİYORSA (R2a inceleme REP-4): tür
                // okunamadıysa (havuz dolu, bağlantı hatası) bu yol PostgreSQL'de advisory lock'suz yazıyor olabilir — TRUE
                // basılırsa başka pod'un kilitli satırıyla aynı seq'i alıp ux_audit_seq'i bozabilirdi (indeks henüz yoksa
                // CONCURRENTLY kurulumu her açılışta düşerdi). Tür bilinmiyor → NULL (indeksin dışında, bugünkü eski satırlar gibi).
                boolean lockedRow = Boolean.FALSE.equals(chainPostgres);
                boolean outer = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                return ownTransaction ? appendLocked(e, false, lockedRow) : appendToChain(e, !outer && lockedRow);
            } finally {
                chainLock.unlock();
            }
        } catch (Exception ex) {
            writeFallback(e, ex);
            return null;
        }
    }

    /**
     * Kilitli ekleme (kendi işleminde). {@code locked}: satır {@code seq_locked = TRUE} ile yazılır (advisory lock altında
     * ya da PostgreSQL olmadığı BİLİNEN veritabanında JVM kilidiyle). Hata (bütünlük ihlali dahil) yukarı çıkar →
     * {@link #persistNow} kaydı fallback dosyasına yazar. YENİDEN DENEME YOK (R2a inceleme REP-5): {@code ux_audit_seq}
     * yalnız kilitli satırları kapsar ve kilitli satırlar advisory lock ile sıralanır — eski pod'ların satırları NULL
     * (indeksin dışında), yani bir seq çakışması burada OLAMAZ; yakalanan bir ihlal başka bir kısıttır (ör. çok uzun değer)
     * ve aynı kayıtla yeniden denemek yine düşerdi.
     */
    private AuditLog appendLocked(AuditLog e, boolean pg, boolean locked) {
        return chainTx.execute(st -> {
            if (pg) chainJdbc.execute(SQL_CHAIN_LOCK);
            return appendToChain(e, locked);
        });
    }

    /**
     * Açık işlemin içindeyse yazımı COMMIT sonrasına erteler (MP-38). Ertelenmez (bugünkü yol): işlem yok; senkronizasyon
     * yok (Spring dışı); işlem altyapısı bağlı değil (elle kurulan birim testleri — orada bugünkü davranış birebir); işlem
     * zaten tamamlanıyor (bir COMMIT sonrası dinleyicisinin içi — orada kayıt hemen kendi işleminde yazılır,
     * {@link #transactionCompleting()}).
     */
    private boolean deferToCommit(AuditLog e, java.util.function.Consumer<AuditLog> afterSave) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return false;
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return false;
        if (!chainSupported()) return false;
        if (transactionCompleting()) return false;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        // COMMIT sonrası istisna çağırana (COMMIT eden koda) yayılırdı — her şey yutulur; yazım hatası
                        // persistNow içinde zaten fallback dosyasına düşer.
                        try {
                            runAfterSave(persistNow(e, true), afterSave);
                        } catch (Throwable t) {
                            log.error("Denetim kaydı COMMIT sonrası yazılamadı (event={}): {}", e.getEventType(), t.toString());
                        }
                    }
                });
        return true;
    }

    private static void runAfterSave(AuditLog saved, java.util.function.Consumer<AuditLog> afterSave) {
        if (saved == null || afterSave == null) return;
        try {
            afterSave.accept(saved);
        } catch (RuntimeException ex) {
            log.warn("Denetim kaydı sonrası takip işi başarısız (id={}): {}", saved.getId(), ex.toString());
        }
    }

    /**
     * Dış işlem açık görünüyor ama ZATEN COMMIT / ROLLBACK oldu mu (COMMIT sonrası geri çağrı ya da
     * {@code @TransactionalEventListener(AFTER_COMMIT)} — Spring onları tamamlanma aşamasında koşar; burada kaydedilen bir
     * senkronizasyon bir daha çağrılmaz ve veri erişimi bitmiş işleme katılır, hiçbir COMMIT izlemez: kayıt sessizce
     * kaybolurdu). JPA: bağlı {@code EntityManager}'ın işlemi artık etkin değil. Okunamıyorsa {@code false} (açık sayılır).
     */
    private static boolean transactionCompleting() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return false;
        try {
            for (Object res : org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap().values()) {
                if (res instanceof org.springframework.orm.jpa.EntityManagerHolder h && h.getEntityManager() != null) {
                    return !h.getEntityManager().getTransaction().isActive();
                }
            }
        } catch (RuntimeException ignored) {
            // JTA EntityManager'da getTransaction() desteklenmez — işlem açık sayılır
        }
        return false;
    }

    /**
     * Zincire ekleme: son satırdan seq + prev_hash, kendi hash'i, INSERT (bugünkü gövde birebir). {@code locked}: satır zincir
     * kilidi altında yazılıyor — {@code seq_locked = TRUE} (tekil {@code ux_audit_seq} indeksinin kapsamı, MP-38).
     */
    private AuditLog appendToChain(AuditLog e, boolean locked) {
        return appendToChain(e, locked, false);
    }

    /**
     * {@code genesis}: satır YENİ bir zincir dönemi başlatır ({@link #restartChainIfDegenerate}) — seq yine ucun bir
     * fazlası, ama {@code prev_hash = GENESIS}. Uç = en yüksek seq'li ZİNCİRLİ satır (seq NULL eski satırlar hariç —
     * PostgreSQL NULL'ları DESC'te öne koyar; bkz. {@link AuditLogRepository#findTopBySeqIsNotNullOrderBySeqDescIdDesc}).
     */
    private AuditLog appendToChain(AuditLog e, boolean locked, boolean genesis) {
        e.setSeqLocked(locked ? Boolean.TRUE : null);
        AuditLog last = auditLogRepo.findTopBySeqIsNotNullOrderBySeqDescIdDesc().orElse(null);
        long seq = (last != null && last.getSeq() != null) ? last.getSeq() + 1 : 1L;
        String prev = !genesis && last != null && last.getRowHash() != null ? last.getRowHash() : GENESIS;
        e.setSeq(seq);
        e.setPrevHash(prev);
        e.setRowHash(sha256(canonical(e) + SEP + prev));
        return auditLogRepo.save(e);
    }

    // ── Çok pod'lu zincir kilidi (2026-10-09) ────────────────────────────────────
    //
    // seq / prev_hash yalnız JVM kilidi altında hesaplanıyordu: iki pod aynı anda yazınca ikisi de aynı "son satırı"
    // okuyup aynı seq + prev_hash ile ekliyor, zincir çatallanıyor ve verifyChain sahte bir KURCALAMA alarmı veriyordu.

    /** PostgreSQL işlem-düzeyi advisory lock anahtarı ("SMAUDCHN") — tüm pod'lar aynı anahtarı kilitler. */
    static final long CHAIN_LOCK_KEY = 0x534D_4155_4443_484EL;
    static final String SQL_CHAIN_LOCK = "SELECT pg_advisory_xact_lock(" + CHAIN_LOCK_KEY + ")";

    /** İsteğe bağlı (yapıcıyla kurulan birim testlerinde yok → bugünkü davranış); ilk yazımda tembel çözülür. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> chainJdbcProvider;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> chainTxProvider;

    private volatile org.springframework.jdbc.core.JdbcTemplate chainJdbc;
    private volatile org.springframework.transaction.support.TransactionTemplate chainTx;
    /** Veritabanı PostgreSQL mi — ilk başarılı okumada bir kez belirlenir (null = henüz bilinmiyor). */
    private volatile Boolean chainPostgres;

    /** Test kancası: kilit altyapısını elle bağlar (veritabanı türü yeniden belirlenir). */
    void setChainLockSupport(org.springframework.jdbc.core.JdbcTemplate jdbc,
                             org.springframework.transaction.PlatformTransactionManager tx) {
        this.chainJdbc = jdbc;
        this.chainTx = tx == null ? null : newChainTx(tx);
        this.chainPostgres = null;
    }

    /**
     * Zincir işlemi her zaman YENİ işlem ({@code REQUIRES_NEW}): işlem dışında REQUIRED ile aynıdır; COMMIT sonrası
     * geri çağrıda (MP-38) dış işleme katılıp kaybolmasın diye şart.
     */
    private static org.springframework.transaction.support.TransactionTemplate newChainTx(
            org.springframework.transaction.PlatformTransactionManager tx) {
        var t = new org.springframework.transaction.support.TransactionTemplate(tx);
        t.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** İşlem altyapısı (JdbcTemplate + işlem yöneticisi) bağlı mı — ilk çağrıda tembel çözülür. */
    private boolean chainSupported() {
        if (chainJdbc == null || chainTx == null) {
            var j = chainJdbcProvider == null ? null : chainJdbcProvider.getIfUnique();
            var t = chainTxProvider == null ? null : chainTxProvider.getIfUnique();
            if (j == null || t == null) return false;
            chainJdbc = j;
            chainTx = newChainTx(t);
        }
        return true;
    }

    /**
     * Veritabanı kilidi kullanılsın mı (hemen yazım yolu): yalnız PostgreSQL'de ve AÇIK BİR İŞLEMİN DIŞINDA. İşlem içindeki
     * yazım artık COMMIT sonrasına ertelenir ({@link #deferToCommit}); buraya işlem içinden yalnız işlem altyapısı yokken
     * (elle kurulan birim testleri) ya da Spring senkronizasyonu kapalıyken gelinir — orada bugünkü davranış birebir.
     * H2 / diğer veritabanlarında da bugünkü davranış birebir.
     */
    private boolean useDbChainLock() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return false;
        if (!chainSupported()) return false;
        return isPostgres();
    }

    /** Veritabanı PostgreSQL mi — ilk başarılı okumada bir kez belirlenir; okunamazsa {@code false} (bu yazımda kilitsiz). */
    private boolean isPostgres() {
        Boolean pg = chainPostgres;
        if (pg == null) {
            try {
                String product = chainJdbc.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
                pg = product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgres");
                chainPostgres = pg;
            } catch (RuntimeException ex) {
                return false;   // veritabanına ulaşılamadı — tür bir sonraki yazımda yeniden belirlenir
            }
        }
        return pg.booleanValue();
    }

    /** Hash kapsamı: DEĞİŞMEZ çekirdek alanlar. Geo/PTR (async backfill) ve id/rowHash HARİÇ. */
    private static String canonical(AuditLog e) {
        StringBuilder b = new StringBuilder(256);
        append(b, e.getSeq()); append(b, e.getEventTime()); append(b, e.getEventType());
        append(b, e.getActor()); append(b, e.getActorId()); append(b, e.getActorTeamId());
        append(b, e.getActorRole()); append(b, e.getIpAddress()); append(b, e.getUserAgent());
        append(b, e.getSessionId()); append(b, e.getResourceType()); append(b, e.getResourceId());
        append(b, e.getDetail()); append(b, e.getChanges()); append(b, e.getOutcome());
        append(b, e.getFailureReason()); append(b, e.getAnomalyFlags()); append(b, e.getCorrelationId());
        return b.toString();
    }
    private static void append(StringBuilder b, Object o) { b.append(o == null ? "" : o.toString()).append(SEP); }

    static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : h) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFallback(AuditLog e, Exception cause) {
        log.error("AUDIT DB yazımı BAŞARISIZ (event={} actor={}) — fallback dosyaya yazılıyor: {}",
                e.getEventType(), e.getActor(), cause.toString());
        String line = fallbackJson(e) + System.lineSeparator();
        try {
            appendLine(Path.of(fallbackFile), line);
            return;
        } catch (Exception fe) {
            log.error("AUDIT fallback dosyası yazılamadı ({}): {} — geçici dizin deneniyor", fallbackFile, fe.toString());
        }
        Path second = secondaryFallbackPath();
        try {
            if (second == null) throw new java.io.IOException("geçici dizin tanımsız");
            appendLine(second, line);
            log.error("AUDIT kaydı geçici fallback dosyasına yazıldı: {} (kalıcı DEĞİL — incelenmeli)", second);
        } catch (Exception fe2) {
            droppedFallbackCount.incrementAndGet();
            log.error("AUDIT fallback dosya yazımı DA başarısız — kayıt kaybı: {}", fe2.toString());
        }
    }

    private static void appendLine(Path p, String line) throws java.io.IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String fallbackJson(AuditLog e) {
        return "{\"event_time\":\"" + n(e.getEventTime()) + "\",\"event_type\":\"" + n(e.getEventType())
                + "\",\"actor\":\"" + n(e.getActor()) + "\",\"resource_type\":\"" + n(e.getResourceType())
                + "\",\"resource_id\":\"" + n(e.getResourceId()) + "\",\"outcome\":\"" + n(e.getOutcome()) + "\"}";
    }
    private static String n(String s) { return s == null ? "" : s.replace("\"", "'"); }

    // ── Zincirin yeniden başlatılması (2026-10-23, kullanıcı kararı: "yeni zincir dönemi") ─────────────
    //
    // Zincirden önce yazılmış (seq NULL) satırları tutan PostgreSQL veritabanında eski uç sorgusu NULL satırı "son" sanıyordu
    // (NULLS FIRST): 2026-07-28'den beri her satır seq = 1 + prev_hash = GENESIS aldı. Eski satırlar YAZILDIĞI GİBİ kalır (hiçbir
    // hash'li kolon yeniden yazılmaz); işaretli bir yeniden başlatma satırı doğru zinciri onarımdan itibaren başlatır ve
    // doğrulama oradan yapılır — öncesi "onarımdan önce yazıldı, doğrulanamaz" diye sayılır.

    /** Yeni zincir döneminin ilk satırının olay türü (katalogda; arayüz etiketi {@code audit.ev.AUDIT_CHAIN_RESTART}). */
    public static final String EVENT_CHAIN_RESTART = "AUDIT_CHAIN_RESTART";

    /** Bozuk zincir: birden fazla {@code seq = 1} satırı (her bozuk satır seq 1 aldı). İki satır okunur — ucuz. */
    static final String SQL_SEQ_ONE_PROBE = "SELECT id FROM audit_log WHERE seq = 1 FETCH FIRST 2 ROWS ONLY";
    /** Yeniden başlatma satırının ayrıntısı — kilit içinde, bir kez (tek tarama). Kullanıcı verisi yok. */
    static final String SQL_RESTART_STATS = "SELECT COUNT(*) AS rows_before,"
            + " SUM(CASE WHEN seq = 1 THEN 1 ELSE 0 END) AS seq1_rows,"
            + " MIN(CASE WHEN seq = 1 THEN event_time END) AS period_from,"
            + " MAX(event_time) AS period_to FROM audit_log WHERE seq IS NOT NULL";

    public enum RestartOutcome { WRITTEN, ALREADY_RESTARTED, NOT_DEGENERATE }

    /** Yeniden başlatma denemesinin sonucu; {@code row} yazılan ya da zaten var olan yeniden başlatma satırı. */
    public record ChainRestart(RestartOutcome outcome, AuditLog row) {}

    /** En yeni yeniden başlatma satırı; yoksa {@code null}. */
    AuditLog latestChainRestart() {
        return auditLogRepo.findTopByEventTypeAndSeqIsNotNullOrderBySeqDescIdDesc(EVENT_CHAIN_RESTART).orElse(null);
    }

    /**
     * Zincir bozuksa (birden fazla {@code seq = 1}) ve henüz yeniden başlatılmadıysa YENİ bir zincir dönemi başlatır:
     * {@code seq = uç + 1}, {@code prev_hash = GENESIS}, olay {@value #EVENT_CHAIN_RESTART}. Yazıcılarla AYNI kilit altında
     * (PostgreSQL: {@link #SQL_CHAIN_LOCK} aynı işlemde; diğer: JVM kilidi) — yeniden başlatma satırı ile normal satırlar
     * iç içe geçmez; "zaten var mı / bozuk mu" kararı kilidin İÇİNDE yeniden verilir (iki pod iki satır yazamaz).
     * Fallback dosyasına DÜŞMEZ: her hata yukarı çıkar (çağıran iş deneme sayar). Veritabanı türü okunamazsa hata
     * ("sonra yeniden dene") — tür bilinmeden kilit seçilemez.
     */
    ChainRestart restartChainIfDegenerate() {
        if (!chainSupported()) throw new IllegalStateException("denetim zinciri altyapısı (JdbcTemplate + işlem yöneticisi) yok");
        boolean pg = isPostgres();
        if (chainPostgres == null) throw new IllegalStateException("veritabanı türü okunamadı — sonra yeniden denenecek");
        if (pg) {
            return chainTx.execute(st -> {
                chainJdbc.execute(SQL_CHAIN_LOCK);
                return restartLocked();
            });
        }
        chainLock.lock();
        try {
            return chainTx.execute(st -> restartLocked());
        } finally {
            chainLock.unlock();
        }
    }

    private ChainRestart restartLocked() {
        AuditLog existing = latestChainRestart();
        if (existing != null) return new ChainRestart(RestartOutcome.ALREADY_RESTARTED, existing);
        if (chainJdbc.queryForList(SQL_SEQ_ONE_PROBE, Long.class).size() < 2) {
            return new ChainRestart(RestartOutcome.NOT_DEGENERATE, null);
        }
        Map<String, Object> st = chainJdbc.queryForMap(SQL_RESTART_STATS);
        AuditLog tail = auditLogRepo.findTopBySeqIsNotNullOrderBySeqDescIdDesc().orElse(null);
        AuditLog e = new AuditLog();
        e.setEventType(EVENT_CHAIN_RESTART);
        e.setEventTime(now());
        e.setActor("SYSTEM");
        e.setActorRole("SYSTEM");
        e.setResourceType("SYSTEM");
        e.setResourceId("audit_log");
        e.setDetail(AuditDetail.of(
                "reason", "degenerate_chain",
                "rows_before", num(st.get("rows_before")),
                "max_seq_before", tail == null ? null : tail.getSeq(),
                "seq1_rows", num(st.get("seq1_rows")),
                "period_from", st.get("period_from"),
                "period_to", st.get("period_to")));
        e.setOutcome("SUCCESS");
        return new ChainRestart(RestartOutcome.WRITTEN, appendToChain(e, true, true));
    }

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    // ── Bütünlük doğrulama ───────────────────────────────────────────────────────

    /** Doğrulanan bölümün başı: en yeni zincir yeniden başlatma satırı. */
    public record VerifiedFrom(Long id, Long seq, String eventTime) {}

    /**
     * Doğrulama sonucu. {@code verifiedFrom}: yeniden başlatma satırı varsa doğrulama ORADAN başladı (yoksa {@code null},
     * bugünkü gibi baştan); {@code unverified}: o bölümün DIŞINDA kalan zincirli satır sayısı (onarımdan önce yazıldı —
     * doğrulanamaz; seq'siz eski satırlar sayılmaz).
     */
    public record ChainVerification(boolean ok, Long brokenSeq, Long brokenId, long checked,
                                    VerifiedFrom verifiedFrom, long unverified) {
        public ChainVerification(boolean ok, Long brokenSeq, Long brokenId, long checked) {
            this(ok, brokenSeq, brokenId, checked, null, 0L);
        }
    }

    /**
     * Zinciri baştan yeniden hesaplayıp saklanan hash'lerle karşılaştırır; ilk kırık satırı döner. Bir yeniden başlatma
     * satırı ({@value #EVENT_CHAIN_RESTART}) varsa doğrulama EN YENİSİNDEN başlar ({@code seq >= onun seq'i}, yeni
     * {@code GENESIS}); öncesi {@code unverified} olarak sayılır. Satır yoksa davranış bugünküyle birebir.
     */
    public ChainVerification verifyChain() {
        AuditLog restart = latestChainRestart();
        VerifiedFrom from = restart == null ? null : new VerifiedFrom(restart.getId(), restart.getSeq(), restart.getEventTime());
        long unverified = restart == null ? 0L : auditLogRepo.countBySeqLessThan(restart.getSeq());
        String prev = GENESIS;
        long checked = 0;
        int page = 0, size = 1000;
        while (true) {
            List<AuditLog> rows = restart == null
                    ? auditLogRepo.findBySeqNotNullOrderBySeqAsc(PageRequest.of(page, size))
                    : auditLogRepo.findBySeqGreaterThanEqualOrderBySeqAscIdAsc(restart.getSeq(), PageRequest.of(page, size));
            if (rows.isEmpty()) break;
            for (AuditLog r : rows) {
                String expectHash = sha256(canonical(r) + SEP + prev);
                String storedPrev = r.getPrevHash() == null ? GENESIS : r.getPrevHash();
                if (!prev.equals(storedPrev) || expectHash == null || !expectHash.equals(r.getRowHash())) {
                    return new ChainVerification(false, r.getSeq(), r.getId(), checked, from, unverified);
                }
                prev = r.getRowHash();
                checked++;
            }
            page++;
        }
        return new ChainVerification(true, null, null, checked, from, unverified);
    }

    // ── Login / Logout ─────────────────────────────────────────────────────────

    /**
     * @param countSince     ISO timestamp; non-null iken sayım max(tenMinAgo, countSince)'dan başlar.
     * @param failuresNeeded pencerede kaç hatanın BRUTE_FORCE tetiklediği (varsayılan 5).
     */
    public AuditLog recordLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                String ipAddress, String userAgent, String sessionId,
                                boolean success, String failureReason, String countSince,
                                int failuresNeeded) {
        return recordLogin(actor, actorId, actorTeamId, actorRole, ipAddress, userAgent, sessionId, success,
                failureReason, countSince, failuresNeeded, null);
    }

    /**
     * {@link #recordLogin(String, Long, Long, String, String, String, String, boolean, String, String, int)} + giriş
     * YÖNTEMİ (2026-10-02, kodla giriş): {@code method} doluysa ayrıntıya {@code {"method":"OTP_PUSH"}} yazılır (denetçi
     * kodla yapılan girişleri parola girişinden ayırabilsin). 2026-10-03'ten beri parola girişi de kanalını yazar:
     * {@code LOCAL} (yerel BCrypt) / {@code LDAP} (AD bind), çerezle dönüş {@code REMEMBER_ME} — başarısız parola girişinde
     * de (Giriş Yöntemleri → İstatistikler). null = ayrıntı yok (eski kayıt biçimi).
     */
    public AuditLog recordLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                String ipAddress, String userAgent, String sessionId,
                                boolean success, String failureReason, String countSince,
                                int failuresNeeded, String method) {
        AuditLog entry = new AuditLog();
        entry.setDetail(methodDetail(method));   // null yöntem → ayrıntı yok (bugünkü kayıt birebir)
        entry.setEventType(success ? "LOGIN" : "LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSessionId(sessionId);
        entry.setOutcome(success ? "SUCCESS" : "FAILURE");
        entry.setFailureReason(failureReason);
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }

        List<String> anomalies = new ArrayList<>();
        if (isOffHours()) anomalies.add("OFF_HOURS");

        if (success && actor != null) {
            if (!geoIpService.isPrivateIp(ipAddress) && !auditLogRepo.existsSuccessfulLoginFromIp(actor, ipAddress))
                anomalies.add("UNUSUAL_IP");

            String oneHourAgo = ISO.format(Instant.now().minusSeconds(geoVelocityWindowSeconds));
            List<AuditLog> recent = auditLogRepo.findRecentSuccessfulLogins(actor, oneHourAgo);
            if (!recent.isEmpty()) {
                AuditLog prev = recent.get(0);
                if (prev.getIpCountry() != null && !ipAddress.equals(prev.getIpAddress())
                        && !"Private".equals(prev.getIpCountry()))
                    anomalies.add("GEO_VELOCITY");
            }

            String tenMinAgo = ISO.format(Instant.now().minusSeconds(bruteForceWindowSeconds));
            long prevFails = auditLogRepo.countRecentFailedLogins(actor, tenMinAgo);
            if (prevFails >= 5) anomalies.add("BRUTE_FORCE");
        }

        if (!success && actor != null && !actor.isBlank()) {
            // failureReason burada MAKİNE-OKUR kod önekidir (BAD_PASSWORD / UNKNOWN_USER /
            // TEMP_PASSWORD_EXPIRED …). Depolanan failure_reason "<KOD>: <insan metni>" olur →
            // anomali detektörü öneki parse eder (failure-reason dağılımı + enumeration sinyali).
            String code = (failureReason != null && !failureReason.isBlank()) ? failureReason : "LOGIN_FAILED";
            String tenMinAgo = ISO.format(Instant.now().minusSeconds(bruteForceWindowSeconds));
            String since = (countSince != null && countSince.compareTo(tenMinAgo) > 0) ? countSince : tenMinAgo;
            long prevFails = auditLogRepo.countRecentFailedLogins(actor, since);
            int attemptNum = (int) prevFails + 1;
            if (prevFails >= failuresNeeded - 1) {
                anomalies.add("BRUTE_FORCE");
                entry.setFailureReason(
                    code + ": brute-force attempt #" + attemptNum + "/" + failuresNeeded + " for '" + actor + "'");
                log.warn("Brute force detected: user='{}' attempt={}/{} IP={}", actor, attemptNum, failuresNeeded, ipAddress);
            } else {
                entry.setFailureReason(
                    code + ": attempt #" + attemptNum + "/" + failuresNeeded + " for '" + actor + "'");
            }
        } else {
            entry.setFailureReason(failureReason);
        }

        if (!anomalies.isEmpty()) {
            entry.setAnomalyFlags(String.join(",", anomalies));
            if (success) log.warn("Security anomaly on login: user={} flags={} IP={}", actor, anomalies, ipAddress);
        }

        // Takip işleri kaydın id'sine bağlı: işlem içinde yazım COMMIT sonrasına ertelenirse (MP-38) onlar da ertelenir.
        return persist(entry, saved -> {
            geoEnricher.enrichGeoAsync(saved.getId(), ipAddress);
            // E1: daha önce görülmemiş bir cihazdan giriş → kullanıcıya bilgi maili.
            // Best-effort ve @Async: bildirim GİRİŞİ engellemez, hatası yutulur.
            if (success) {
                newDeviceNotifier.notifyIfNewDevice(saved.getId(), actor, userAgent,
                        ipAddress, entry.getEventTime());
            }
        });
    }

    /**
     * Giriş KANALI (2026-10-03, Giriş Yöntemleri → İstatistikler): {@code LOGIN} / {@code LOGIN_FAILED} ayrıntısına
     * yazılan {@code method} değeri. Giriş yollarının eski adı {@code PASSWORD} (yerel BCrypt yolu) {@code LOCAL}'a
     * normalleşir; {@code LDAP}, {@code REMEMBER_ME}, {@code OTP_PUSH}, {@code OTP_EMAIL} aynen. {@code null} = yöntem bilinmiyor
     * (ayrıntı yazılmaz — bugünkü kayıt birebir). Olay türü, sonuç, neden ve sayaçlar DEĞİŞMEZ; yalnız ayrıntı eklenir.
     */
    public static String loginChannel(String method) {
        if (method == null || method.isBlank()) return null;
        String m = method.trim().toUpperCase(java.util.Locale.ROOT);
        return "PASSWORD".equals(m) ? "LOCAL" : m;
    }

    private static String methodDetail(String method) {
        String ch = loginChannel(method);
        return ch == null ? null : AuditDetail.of("method", ch);
    }

    /** Kimlik doğrulamadan önce IP rate-limit'e takılan giriş — BLOCKED. */
    public void recordRateLimited(String actor, String ipAddress, String userAgent) {
        recordRateLimited(actor, ipAddress, userAgent, null);
    }

    /**
     * {@link #recordRateLimited(String, String, String)} + giriş kanalı (2026-10-03): hedeflenen hesap biliniyorsa
     * ({@code LOCAL} / {@code LDAP}) ayrıntıya yazılır; bilinmeyen adda {@code null} (ayrıntı yok). Neden metni, sonuç
     * (BLOCKED) ve RATE_LIMITED bayrağı aynen.
     */
    public void recordRateLimited(String actor, String ipAddress, String userAgent, String method) {
        AuditLog entry = new AuditLog();
        entry.setDetail(methodDetail(method));
        entry.setEventType("LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setOutcome("BLOCKED");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        entry.setFailureReason("Rate limited: too many login attempts from " + ipAddress
                + (actor != null && !actor.isBlank() ? " (targeting '" + actor + "')" : ""));
        entry.setAnomalyFlags("RATE_LIMITED");
        persist(entry, saved -> geoEnricher.enrichGeoAsync(saved.getId(), ipAddress));
        log.warn("Rate-limited login blocked: IP={} actor={}", ipAddress, actor);
    }

    /** Pasif hesap girişinin denetim nedeni (makine kodu, {@code failure_reason} öneki). */
    public static final String REASON_ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";

    /**
     * PASİF hesabın kimlik bilgisi DOĞRU ama giriş REDDEDİLDİ (2026-10-02, kullanıcı kararı) — {@code LOGIN_FAILED},
     * neden {@code ACCOUNT_INACTIVE}. Sonuç {@code BLOCKED}: kaba kuvvet sayacı ({@code countRecentFailedLogins})
     * BLOCKED satırları SAYMAZ — parolasını bilen pasif kişinin denemeleri ilerleyici kilidi tetiklemez (kilit yanlış
     * parolaya karşıdır). Anomali bayrağı yalnız mesai dışı; RATE_LIMITED basılmaz (oran sınırı değil, hesap durumu).
     *
     * @param method giriş yolu: {@code PASSWORD} (yerel), {@code LDAP}, {@code REMEMBER_ME}
     */
    public void recordInactiveLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                    String ipAddress, String userAgent, String method) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setOutcome("BLOCKED");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        entry.setFailureReason(REASON_ACCOUNT_INACTIVE + ": pasif hesap — kimlik doğrulandı, giriş reddedildi ("
                + (method == null ? "?" : method) + ")");
        entry.setDetail(methodDetail(method));   // 2026-10-03: giriş kanalı (PASSWORD → LOCAL)
        if (isOffHours()) entry.setAnomalyFlags("OFF_HOURS");
        persist(entry, saved -> geoEnricher.enrichGeoAsync(saved.getId(), ipAddress));
        log.warn("Pasif hesap girişi reddedildi: actor={} yol={} IP={}", actor, method, ipAddress);
    }

    /** Sistem bakımında reddedilen girişin denetim nedeni (makine kodu, {@code failure_reason} öneki). */
    public static final String REASON_MAINTENANCE = "MAINTENANCE";

    /**
     * SİSTEM BAKIMI sürerken global yönetici olmayan hesabın girişi REDDEDİLDİ (2026-10-02, kullanıcı kararı) —
     * {@code LOGIN_FAILED}, neden {@code MAINTENANCE}, sonuç {@code BLOCKED}: kaba kuvvet sayacı BLOCKED satırları SAYMAZ
     * (bakımda tekrar tekrar giriş denemek kilit tetiklemez; kilit yanlış parolaya karşıdır). Kimlik bilgisi doğrulanmıştır
     * (yerel / LDAP); remember-me yolunda çerez geçerliydi. Anomali bayrağı yalnız mesai dışı.
     *
     * @param method giriş yolu: {@code PASSWORD} (yerel), {@code LDAP}, {@code REMEMBER_ME}
     */
    public void recordMaintenanceLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                       String ipAddress, String userAgent, String method) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setOutcome("BLOCKED");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        entry.setFailureReason(REASON_MAINTENANCE + ": sistem bakımı — yalnız global yöneticiler giriş yapabilir ("
                + (method == null ? "?" : method) + ")");
        entry.setDetail(methodDetail(method));   // 2026-10-03: giriş kanalı (PASSWORD → LOCAL)
        if (isOffHours()) entry.setAnomalyFlags("OFF_HOURS");
        persist(entry, saved -> geoEnricher.enrichGeoAsync(saved.getId(), ipAddress));
        log.info("Sistem bakımında giriş reddedildi: actor={} yol={} IP={}", actor, method, ipAddress);
    }

    /** Kullanıcı adı ön eki kuralına uymayan girişin denetim nedeni (2026-10-21). */
    public static final String REASON_USERNAME_PREFIX = "USERNAME_PREFIX";

    /**
     * KULLANICI ADI ÖN EKİ kuralı (Ayarlar → Giriş Yöntemleri, 2026-10-21): ad izinli bir ön ekle başlamıyor — giriş
     * reddedildi. {@code LOGIN_FAILED}, sonuç {@code BLOCKED}: kaba kuvvet / ilerleyici kilit sayacına GİRMEZ (parola ya hiç
     * denenmedi ya da doğruydu; kural yalnız yazılan / kayıtlı ada bakar). Parola girişinde ad DOĞRULAMADAN önce
     * reddedilir (aktör büyük harfe çevrilmiş yazılan ad, kimlik bilgisi yok); LDAP / kodla giriş / remember-me yolunda
     * kimlik doğrulandıktan sonra kayıtlı ad.
     *
     * @param method giriş yolu: {@code null} (doğrulama öncesi), {@code PASSWORD}, {@code LDAP}, {@code REMEMBER_ME},
     *               {@code OTP_PUSH} / {@code OTP_EMAIL}
     */
    public void recordUsernamePrefixLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                          String ipAddress, String userAgent, String method) {
        recordBlockedLogin(actor, actorId, actorTeamId, actorRole, ipAddress, userAgent,
                REASON_USERNAME_PREFIX + ": kullanıcı adı izinli bir ön ekle başlamıyor — giriş reddedildi"
                        + (method == null ? "" : " (" + method + ")"),
                method);
        log.info("Kullanıcı adı ön eki kuralı girişi reddetti: actor={} yol={} IP={}", actor, method, ipAddress);
    }

    /** LDAP girişi kapalıyken reddedilen parola girişinin denetim nedeni (2026-10-02). */
    public static final String REASON_LDAP_LOGIN_DISABLED = "LDAP_LOGIN_DISABLED";
    /** Kimlik doğrulandı (kodla giriş) ama hesap kilitli — denetim nedeni (2026-10-02). */
    public static final String REASON_ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    /**
     * LDAP İLE GİRİŞ KAPALI (Ayarlar → Giriş Yöntemleri, 2026-10-02): yerel olmayan ad AD'ye gitmeden reddedildi —
     * {@code LOGIN_FAILED}, sonuç {@code BLOCKED} (parola hiç denenmedi → kaba kuvvet / ilerleyici kilit sayacına GİRMEZ;
     * kimse LDAP kapalıyken başkasının hesabını kilitleyemez). Aktör kanonik ad ya da büyük harfe çevrilmiş yazılan ad.
     */
    public void recordLdapDisabledLogin(String actor, String ipAddress, String userAgent) {
        // Kanal LDAP (2026-10-03): ad LDAP girişi olarak yönlendirildi; bilinmeyen ad istatistikte yine "bilinmeyen kullanıcı".
        recordBlockedLogin(actor, null, null, null, ipAddress, userAgent,
                REASON_LDAP_LOGIN_DISABLED + ": LDAP ile giriş kapalı — AD'ye gidilmedi", "LDAP");
    }

    /**
     * Kodla giriş DOĞRU kodla tamamlandı ama hesap kilitli (geçici / kalıcı) — {@code LOGIN_FAILED}, {@code BLOCKED}
     * (2026-10-02). Kilit sayacına girmez (kilit yanlış parolaya karşıdır).
     *
     * @param method {@code OTP_PUSH} / {@code OTP_EMAIL}
     */
    public void recordLockedLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                  String ipAddress, String userAgent, String method) {
        recordBlockedLogin(actor, actorId, actorTeamId, actorRole, ipAddress, userAgent,
                REASON_ACCOUNT_LOCKED + ": hesap kilitli — kimlik doğrulandı, giriş reddedildi (" + (method == null ? "?" : method) + ")",
                method);
    }

    private void recordBlockedLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                    String ipAddress, String userAgent, String reason, String method) {
        AuditLog entry = new AuditLog();
        entry.setDetail(methodDetail(method));   // 2026-10-03: giriş kanalı
        entry.setEventType("LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setOutcome("BLOCKED");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        entry.setFailureReason(reason);
        if (isOffHours()) entry.setAnomalyFlags("OFF_HOURS");
        persist(entry, ipAddress == null ? null : saved -> geoEnricher.enrichGeoAsync(saved.getId(), ipAddress));
    }

    /**
     * Kodla giriş olayları (2026-10-02, kullanıcı isteği) — {@code LOGIN_OTP_REQUESTED}, {@code LOGIN_OTP_DELIVERY_FAILED},
     * {@code LOGIN_OTP_VERIFY_FAILED}, {@code LOGIN_OTP_EXPIRED}, {@code LOGIN_OTP_LOCKED}. Oturumsuz (kimlik henüz
     * kanıtlanmadı): aktör kanonik kullanıcı adı (bilinmeyen adda büyük harfe çevrilmiş yazılan ad — LOGIN_FAILED ile aynı
     * kural), kaynak {@code USER:<ad>}. {@code outcome}: SUCCESS / FAILURE / BLOCKED. Ayrıntıda ve nedende KOD ASLA yer
     * almaz (çağıran yalnız kanal / sonuç / iç neden / kalan deneme yazar).
     *
     * <p>Bu türler {@code LOGIN_FAILED} DEĞİLDİR: parola kaba kuvvet sayacına ({@code countRecentFailedLogins}) ve
     * ilerleyici hesap kilidine GİRMEZ — başkası kod deneyerek kimsenin parola girişini kilitleyemez.
     */
    public void recordOtp(String eventType, String actor, Long actorId, Long actorTeamId, String actorRole,
                          String outcome, String failureReason, String detail, String ipAddress, String userAgent) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventTime(now());
        e.setActor(actor);
        e.setActorId(actorId);
        e.setActorTeamId(actorTeamId);
        e.setActorRole(actorRole);
        e.setIpAddress(ipAddress);
        e.setUserAgent(userAgent);
        if (actor != null && !actor.isBlank()) {
            e.setResourceType("USER");
            e.setResourceId(actor);
        }
        e.setOutcome(outcome == null ? "SUCCESS" : outcome);
        e.setFailureReason(failureReason);
        e.setDetail(detail);
        if (isOffHours()) e.setAnomalyFlags("OFF_HOURS");
        persist(e, ipAddress == null ? null : saved -> geoEnricher.enrichGeoAsync(saved.getId(), ipAddress));
    }

    public void recordLogout(String actor, Long actorId, String ipAddress, String sessionId) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGOUT");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setIpAddress(ipAddress);
        entry.setSessionId(sessionId);
        entry.setOutcome("SUCCESS");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        persist(entry);
    }

    // ── Yönetim eylemleri ────────────────────────────────────────────────────────

    public void recordAction(String eventType, String actor, Long actorId, Long actorTeamId,
                             String actorRole, String resourceType, String resourceId,
                             String detail, String ipAddress, String userAgent, String sessionId) {
        recordActionFull(eventType, actor, actorId, actorTeamId, actorRole, resourceType, resourceId,
                detail, null, ipAddress, userAgent, sessionId, null);
    }

    /** HTTP bağlamından okuyan kolay overload. */
    public void recordAction(String eventType, HttpSession session, HttpServletRequest request,
                             String resourceType, String resourceId, String detail) {
        recordAction(eventType, session, request, resourceType, resourceId, detail, null);
    }

    /** Request'i thread'den çözer (controller imzalarına HttpServletRequest eklemeye gerek kalmaz).
     *  before/after diff (changes) opsiyonel. */
    public void recordAction(String eventType, HttpSession session, String resourceType,
                             String resourceId, String detail, String changes) {
        recordAction(eventType, session, currentRequest(), resourceType, resourceId, detail, changes);
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) return sra.getRequest();
        } catch (Exception ignored) { }
        return null;
    }

    /** before/after diff (changes) ile — güncelleme eylemleri için (Batch B). */
    public void recordAction(String eventType, HttpSession session, HttpServletRequest request,
                             String resourceType, String resourceId, String detail, String changes) {
        recordActionFull(eventType,
                strAttr(session, "username"), longAttr(session, "userId"), longAttr(session, "teamId"),
                strAttr(session, "systemRole"), resourceType, resourceId, detail, changes,
                resolveIp(request), resolveUa(request), session != null ? session.getId() : null,
                CorrelationIdFilter.get(request));
    }

    private void recordActionFull(String eventType, String actor, Long actorId, Long actorTeamId,
                                  String actorRole, String resourceType, String resourceId, String detail,
                                  String changes, String ipAddress, String userAgent, String sessionId,
                                  String correlationId) {
        AuditLog entry = new AuditLog();
        entry.setEventType(eventType);
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSessionId(sessionId);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setDetail(detail);
        entry.setChanges(changes);
        entry.setCorrelationId(correlationId);
        entry.setOutcome("SUCCESS");
        if (isOffHours()) entry.setAnomalyFlags("OFF_HOURS");
        persist(entry);
    }

    // ── Güvenlik olayları (403 / IDOR / auth-required / expired-token) ────────────

    public void recordSecurityEvent(String eventType, HttpServletRequest request, HttpSession session,
                                    String resourceType, String resourceId, String reason) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventTime(now());
        if (session != null) {
            e.setActor(strAttr(session, "username"));
            e.setActorId(longAttr(session, "userId"));
            e.setActorTeamId(longAttr(session, "teamId"));
            e.setActorRole(strAttr(session, "systemRole"));
            e.setSessionId(session.getId());
        }
        if (e.getActor() == null) e.setActor("anonymous");
        e.setIpAddress(resolveIp(request));
        e.setUserAgent(resolveUa(request));
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setOutcome("BLOCKED");
        e.setFailureReason(reason != null && reason.length() > 200 ? reason.substring(0, 200) : reason);
        e.setCorrelationId(CorrelationIdFilter.get(request));
        e.setAnomalyFlags(isOffHours() ? "OFF_HOURS" : null);
        persist(e);
    }

    // ── Taşıyıcı-belirteçle (e-posta magic-link) yapılan eylemler ────────────────

    /**
     * Oturumsuz ama YETKİLİ eylem: kimlik oturumdan değil, e-postayla gönderilen tek kullanımlık
     * belirteçten gelir (haftalık rapor onay/red bağlantıları).
     *
     * <p><b>Actor bir KİMLİK değil, bir KANAL adıdır.</b> Belirteç yalnız bir posta kutusuna
     * erişimi kanıtlar, tuşa basan insanın kim olduğunu değil. Çözülen kişi adını {@code actor}
     * sütununa yazmak, kanıtlanmamış bir kimliği kanıtlanmış gibi göstermek olurdu — denetim
     * kaydının varlık sebebine aykırı. O ad {@code detail} içinde taşınır.
     *
     * <p>{@code actor_role = "TOKEN"}: "yetki oturumdan değil belirteçten geldi". {@code "SYSTEM"}
     * yalan olurdu (bunu bir insan tetikledi), {@code "anonymous"} yanlış olurdu (o,
     * {@link #recordSecurityEvent}'in kimliksiz BAŞARISIZLIK değeri).
     *
     * <p>IP ve User-Agent ZORUNLU taşınır: bunlar gerçek HTTP istekleridir ve elimizdeki tek
     * somut kanıttır — bu yüzden {@link #recordSystemEvent} (IP taşımaz) kullanılamaz.
     */
    public void recordTokenAction(String eventType, HttpServletRequest request, String tokenActor,
                                  Long actorTeamId, String resourceType, String resourceId, String detail) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventTime(now());
        e.setActor(tokenActor == null || tokenActor.isBlank() ? "token" : tokenActor);
        e.setActorRole("TOKEN");
        e.setActorTeamId(actorTeamId);
        e.setIpAddress(resolveIp(request));
        e.setUserAgent(resolveUa(request));
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setDetail(detail);
        e.setOutcome("SUCCESS");
        e.setCorrelationId(CorrelationIdFilter.get(request));
        e.setAnomalyFlags(isOffHours() ? "OFF_HOURS" : null);
        persist(e);
    }

    // ── Sistem olayları (startup/shutdown/schema-patch/retention) ────────────────

    public void recordSystemEvent(String eventType, String resourceType, String resourceId, String detail) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventTime(now());
        e.setActor("SYSTEM");
        e.setActorRole("SYSTEM");
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setDetail(detail);
        e.setOutcome("SUCCESS");
        persist(e);
    }

    /**
     * Olay türü → son 90 gündeki sayı (katalog ucu için).
     *
     * <p>Sayıya ihtiyaç var çünkü katalog 162 tür içeriyor: hiç kaydı olmayan bir türü seçmek
     * kullanıcıya boş liste döndürüyor ve "veri kaybolmuş" izlenimi veriyor. Sayıyla birlikte
     * arayüz onları soluk gösterebiliyor.
     *
     * <p>Pencere 90 gün: istatistik kartlarının 7 günlük penceresi filtre listesi için fazla dar
     * (ayda bir koşan bir bakım işi "hiç olmamış" gibi görünürdü), 365 gün ise tam tarama.
     *
     * <p><b>Cache adı AYRI olmak zorunda.</b> Aynı cache'te iki parametresiz metot aynı anahtarı
     * ({@code SimpleKey.EMPTY}) paylaşır: önce hangisi çağrılırsa diğerinin sonucu olarak dönerdi.
     * Varsayılan 60 sn TTL {@code buildStats} ile aynı, cache yalnız isim olarak ayrıdır.
     */
    @Cacheable("audit-event-types")
    public Map<String, Long> eventTypeCounts() {
        String since = ISO.format(Instant.now().minusSeconds(90 * 86_400L));
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Object[] row : auditLogRepo.countByEventTypeSince(since)) {
            if (row != null && row.length > 1 && row[0] != null && row[1] instanceof Number n) {
                out.put(String.valueOf(row[0]), n.longValue());
            }
        }
        return out;
    }

    // ── Denetim özet istatistikleri (dashboard) — CACHE'li ───────────────────────
    // /audit/stats her istekte 10 aggregate sorgu (2× count + dağılımlar + SUBSTRING günlük
    // histogram) çalıştırıyordu; audit_log milyonlarca satıra çıkınca her açılış pahalı. 60 sn
    // Caffeine cache ile pencere başına 1 hesap (auth controller'da kalır, cache'lenmez).
    @Cacheable("audit-stats")
    public Map<String, Object> buildStats() {
        String last24h = ISO.format(Instant.now().minusSeconds(86_400));
        String last7d  = ISO.format(Instant.now().minusSeconds(7 * 86_400L));
        String last14d = ISO.format(Instant.now().minusSeconds(14 * 86_400L));

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("total_24h",         auditLogRepo.countEventsSince(last24h));
        stats.put("anomalies_24h",     auditLogRepo.countAnomaliesSince(last24h));
        stats.put("failed_logins_24h", auditLogRepo.countFailedLoginsSince(last24h));
        stats.put("total_7d",          auditLogRepo.countEventsSince(last7d));
        stats.put("anomalies_7d",      auditLogRepo.countAnomaliesSince(last7d));
        stats.put("failed_logins_7d",  auditLogRepo.countFailedLoginsSince(last7d));
        stats.put("by_event_type_7d",  toKvList(auditLogRepo.countByEventTypeSince(last7d)));
        stats.put("by_outcome_7d",     toKvList(auditLogRepo.countByOutcomeSince(last7d)));
        stats.put("top_actors_7d",     toKvList(auditLogRepo.topActorsSince(last7d, PageRequest.of(0, 10))));
        stats.put("by_day_14d",        toKvList(auditLogRepo.countByDaySince(last14d)));
        return stats;
    }

    private static List<Map<String, Object>> toKvList(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("key", r[0] == null ? "" : r[0].toString());
            m.put("count", ((Number) r[1]).longValue());
            out.add(m);
        }
        return out;
    }

    // Geo zenginleştirme (hash'e girmeyen kolonlar; async) → AuditGeoEnricher (E2: sınıf-içi @Async çağrısı
    // proxy'yi atlıyor, geo HTTP + ters-DNS giriş iş parçacığında koşuyordu).

    // ── Yardımcılar ──────────────────────────────────────────────────────────────

    /**
     * "Mesai dışı" (OFF_HOURS) anomalisi — hafta içi {@code [start, end)} saatleri MESAİ,
     * geri kalan her şey mesai dışıdır (hafta sonunun tamamı dahil).
     *
     * <p><b>SAAT DİLİMİ KURUMUN DİLİMİDİR, UTC DEĞİL.</b> Eskiden hesap {@code ZoneOffset.UTC}
     * ile yapılıyordu ve kurum Europe/Istanbul (UTC+3) olduğu için pencere 3 saat kayıyordu:
     * "08:00–20:00" ayarı fiilen 11:00–23:00 İstanbul demek oluyordu. Sonuçları GERÇEKTİ —
     * sabah 08:00'deki normal giriş "mesai dışı" damgalanıyor, akşam 22:00'deki giriş
     * damgalanmıyordu. Hafta günü de kayıyordu: cumartesi 01:00 İstanbul, UTC'de hâlâ cuma.
     *
     * <p>Saf ve parametreli tutulması bilinçli: {@code now()} çağıran bir mantık ancak süitin
     * KOŞTUĞU saate göre test edilebilirdi (CI runner UTC, geliştirici makinesi Istanbul —
     * projede yaşanmış tuzak). Sınırlar burada tablo testiyle pinleniyor.
     */
    static boolean isOffHours(ZonedDateTime local, int startHour, int endHour) {
        int dow = local.getDayOfWeek().getValue();      // 1=Pazartesi … 6=Cumartesi, 7=Pazar
        if (dow >= 6) return true;                      // hafta sonunun TAMAMI mesai dışı
        int hour = local.getHour();
        return hour < startHour || hour >= endHour;     // end HARİÇ: 20:00 artık mesai dışıdır
    }

    private boolean isOffHours() {
        return isOffHours(ZonedDateTime.now(auditZone()), officeStartHour, officeEndHour);
    }

    /** Geçersiz/eksik ayarda UTC'ye düşmek sessiz bir 3 saatlik kayma olurdu — İstanbul'a düşülür. */
    private java.time.ZoneId auditZone() {
        try {
            return java.time.ZoneId.of(auditTimezone);
        } catch (Exception e) {
            log.warn("Geçersiz denetim saat dilimi '{}' — Europe/Istanbul kullanılıyor", auditTimezone);
            return java.time.ZoneId.of("Europe/Istanbul");
        }
    }

    public String resolveIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    public static String normalizeIp(String ip) {
        if (ip == null) return "unknown";
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "0000:0000:0000:0000:0000:0000:0000:0001".equals(ip))
            return "127.0.0.1";
        if (ip.startsWith("::ffff:") && ip.length() > 7) return ip.substring(7);
        return ip;
    }

    public String resolveUa(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private String strAttr(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        return v != null ? v.toString() : null;
    }

    private Long longAttr(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        if (v == null) return null;
        if (v instanceof Long l) return l;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
