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
    static final String GENESIS = "GENESIS";
    static final char SEP = '';   // unit separator — canonical alan ayırıcı

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

    /** Fallback dosyasına ekleme ile geri aktarımın dosya taşıması aynı JVM kilidiyle sıralanır (H paketi B7). */
    private final Object fallbackFileLock = new Object();

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
            long n = 0;
            for (Path p : fallbackPaths()) n += pendingIn(p);
            return n + droppedFallbackCount.get();   // hiçbir yere yazılamayanlar da "denetim izinde yok"
        } catch (Exception e) {
            log.debug("Denetim fallback dosyası okunamadı ({}): {}", fallbackFile, e.getMessage());
            return -1L;
        }
    }

    /**
     * Bir fallback yolundaki bekleyen kayıtlar (H paketi B7): yazılmakta olan dosya + geri aktarılmakta olan
     * ({@code .replay}; bu pod'un son turda aktardığı satırlar düşülür) + aktarılamayan ({@code .failed}, elle incelenir).
     */
    private long pendingIn(Path p) throws java.io.IOException {
        Path replay = siblingWith(p, REPLAY_SUFFIX);
        long inReplay = Math.max(0L, countLines(replay) - replayDone.getOrDefault(replay.toString(), 0L));
        return countLines(p) + inReplay + countLines(siblingWith(p, FAILED_SUFFIX));
    }

    /** Fallback yolları: birincil + (farklıysa) geçici dizindeki ikinci yer. */
    private List<Path> fallbackPaths() {
        List<Path> out = new ArrayList<>(2);
        Path primary = Path.of(fallbackFile);
        out.add(primary);
        Path second = secondaryFallbackPath();
        if (second != null && !second.equals(primary)) out.add(second);
        return out;
    }

    static Path siblingWith(Path p, String suffix) {
        Path name = p.getFileName();
        return p.resolveSibling((name == null ? "audit-fallback.jsonl" : name.toString()) + suffix);
    }

    /**
     * Kayıt sayısı (B6): boş olmayan satırlar. Yeni satır gerçek JSON'dur (ham satır sonu taşımaz) — her satır bir kayıt.
     * Eski (kaçışsız) satırlarda bir değer içindeki satır sonu satırı bölebiliyordu; o parçalar sayılmasın diye {@code .jsonl}
     * dosyalarında yalnız {@code '{'} ile başlayan satırlar sayılır ({@code .failed} dosyası ham parçaları da içerir — hepsi).
     */
    static long countLines(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return 0L;
        boolean rawFile = p.getFileName() != null && p.getFileName().toString().endsWith(FAILED_SUFFIX);
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank())
                    .filter(l -> rawFile || l.stripLeading().startsWith("{"))
                    .count();
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
     * PostgreSQL NULL'ları DESC'te öne koyar; bkz. {@link AuditLogRepository#findTopBySeqIsNotNullOrderBySeqDesc}).
     */
    private AuditLog appendToChain(AuditLog e, boolean locked, boolean genesis) {
        fitColumns(e);
        e.setSeqLocked(locked ? Boolean.TRUE : null);
        AuditLog last = auditLogRepo.findTopBySeqIsNotNullOrderBySeqDesc().orElse(null);
        long seq = (last != null && last.getSeq() != null) ? last.getSeq() + 1 : 1L;
        String prev = !genesis && last != null && last.getRowHash() != null ? last.getRowHash() : GENESIS;
        e.setSeq(seq);
        e.setPrevHash(prev);
        e.setRowHash(sha256(canonical(e) + SEP + prev));
        return auditLogRepo.save(e);
    }

    /**
     * Metin sütunlarının uzunluğu (H paketi hata avı, 2026-11-26) — {@link AuditLog} {@code @Column(length)} ile AYNI
     * (kapı: {@code AuditFallbackReplayTest.fitLengthsMatchColumns}). Zincire yazılan her satır {@link #fitColumns} ile bu
     * sınırlara sığdırılır: eskiden sığmayan değer (uzun hata nedeni, kaynak kimliği, oturum kimliği) PostgreSQL'de 22001 ile
     * yazımı düşürüp satırı pod'a özgü fallback dosyasına gönderiyordu; geri aktarım da aynı değeri 3 turda .failed dosyasına
     * alıyordu (o dosya pod ölünce gider). Hash sığdırılmış değerden hesaplanır.
     */
    static final Map<String, Integer> FIT_LENGTHS = Map.ofEntries(
            Map.entry("event_type", 50), Map.entry("event_time", 30), Map.entry("actor", 100),
            Map.entry("actor_role", 20), Map.entry("ip_address", 50), Map.entry("ip_country", 100),
            Map.entry("ip_city", 100), Map.entry("ip_org", 200), Map.entry("session_id", 100),
            Map.entry("resource_type", 50), Map.entry("resource_id", 200), Map.entry("correlation_id", 40),
            Map.entry("outcome", 20), Map.entry("failure_reason", 200), Map.entry("anomaly_flags", 200),
            Map.entry("ip_reverse_host", 255));

    /** Metin alanlarını sütun sınırına sığdırır (fazlası "…" ile kesilir; vekil çift bölünmez). */
    static void fitColumns(AuditLog e) {
        e.setEventType(fitCol(e.getEventType(), FIT_LENGTHS.get("event_type")));
        e.setEventTime(fitCol(e.getEventTime(), FIT_LENGTHS.get("event_time")));
        e.setActor(fitCol(e.getActor(), FIT_LENGTHS.get("actor")));
        e.setActorRole(fitCol(e.getActorRole(), FIT_LENGTHS.get("actor_role")));
        e.setIpAddress(fitCol(e.getIpAddress(), FIT_LENGTHS.get("ip_address")));
        e.setIpCountry(fitCol(e.getIpCountry(), FIT_LENGTHS.get("ip_country")));
        e.setIpCity(fitCol(e.getIpCity(), FIT_LENGTHS.get("ip_city")));
        e.setIpOrg(fitCol(e.getIpOrg(), FIT_LENGTHS.get("ip_org")));
        e.setSessionId(fitCol(e.getSessionId(), FIT_LENGTHS.get("session_id")));
        e.setResourceType(fitCol(e.getResourceType(), FIT_LENGTHS.get("resource_type")));
        e.setResourceId(fitCol(e.getResourceId(), FIT_LENGTHS.get("resource_id")));
        e.setCorrelationId(fitCol(e.getCorrelationId(), FIT_LENGTHS.get("correlation_id")));
        e.setOutcome(fitCol(e.getOutcome(), FIT_LENGTHS.get("outcome")));
        e.setFailureReason(fitCol(e.getFailureReason(), FIT_LENGTHS.get("failure_reason")));
        e.setAnomalyFlags(fitCol(e.getAnomalyFlags(), FIT_LENGTHS.get("anomaly_flags")));
        e.setIpReverseHost(fitCol(e.getIpReverseHost(), FIT_LENGTHS.get("ip_reverse_host")));
    }

    static String fitCol(String v, int max) {
        if (v == null || v.length() <= max) return v;
        int cut = max - 1;
        if (cut > 0 && Character.isHighSurrogate(v.charAt(cut - 1))) cut--;
        return v.substring(0, cut) + "…";
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

    /** Hash kapsamı: DEĞİŞMEZ çekirdek alanlar. Geo/PTR (async backfill) ve id/rowHash HARİÇ. Paket içi: kıyas testi. */
    static String canonical(AuditLog e) {
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
                e.getEventType(), oneLine(e.getActor()), cause.toString());
        // "\n" — satır içinde ham satır sonu OLAMAZ (JSON kaçışlı, B6); Windows'ta da tek tür ayırıcı. Satır sütun
        // sınırlarına sığdırılır (geri aktarım doğrulaması bunu bekler) ve HMAC ile imzalanır (geri aktarım yalnız imzalı
        // satırı zincire yazar — dosyaya yazabilen biri sahte denetim kaydı sokamaz).
        fitColumns(e);
        String line;
        try {
            line = fallbackLine(e) + "\n";
        } catch (RuntimeException signFailed) {
            // İmzalanamadı (olmaması gereken yol) — kayıt yine dosyaya yazılır; imzasız satır geri aktarılmaz, .failed'da incelenir.
            log.error("AUDIT fallback satırı imzalanamadı — imzasız yazılıyor: {}", signFailed.toString());
            line = fallbackJson(e) + "\n";
        }
        synchronized (fallbackFileLock) {   // geri aktarımın dosya taşımasıyla sıralı (B7)
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
    }

    private static void appendLine(Path p, String line) throws java.io.IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ── Fallback satırı: gerçek JSON (H paketi B6, 2026-11-26) ───────────────────────────────
    //
    // Eski satır elle birleştiriliyordu ve yalnız " → ' çevriliyordu: ters bölü geçersiz JSON, satır sonu (başarısız girişte
    // kullanıcının yazdığı ad!) satırı ikiye bölüyor (sayaç yanlış, sahte satır), kontrol karakterleri kaçışsızdı; satır
    // ayrıca kayıplıydı (actor_id, IP, ayrıntı yoktu). Şimdi Jackson tam kaçış yapar ve satır kaydı geri aktarmaya yetecek
    // TÜM çekirdek alanları taşır (B7). {@code fid}: satırın kalıcı kimliği (tekrar yazmayı önleyen nişanın anahtarı).

    /** Satır biçimi sürümü. 1 (alan yok) = eski elle yazılmış, kaçışsız ve kayıplı satır. */
    static final int FALLBACK_FORMAT = 2;
    private static final tools.jackson.databind.ObjectMapper FALLBACK_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    static String fallbackJson(AuditLog e) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("v", FALLBACK_FORMAT);
        m.put("fid", java.util.UUID.randomUUID().toString());
        m.put("event_time", e.getEventTime());
        m.put("event_type", e.getEventType());
        m.put("actor", e.getActor());
        m.put("actor_id", e.getActorId());
        m.put("actor_team_id", e.getActorTeamId());
        m.put("actor_role", e.getActorRole());
        m.put("ip_address", e.getIpAddress());
        m.put("user_agent", e.getUserAgent());
        m.put("session_id", e.getSessionId());
        m.put("resource_type", e.getResourceType());
        m.put("resource_id", e.getResourceId());
        m.put("detail", e.getDetail());
        m.put("changes", e.getChanges());
        m.put("outcome", e.getOutcome());
        m.put("failure_reason", e.getFailureReason());
        m.put("anomaly_flags", e.getAnomalyFlags());
        m.put("correlation_id", e.getCorrelationId());
        m.put("pod", fitCol(podName(), FALLBACK_POD_MAX));
        try {
            return FALLBACK_JSON.writeValueAsString(m);
        } catch (RuntimeException ex) {
            // Olmaması gereken yol (düz metin / sayı haritası): yine de geçerli, tek satır JSON yazılır.
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> en : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonString(en.getKey())).append(':');
                Object v = en.getValue();
                sb.append(v == null ? "null" : v instanceof Number ? v.toString() : jsonString(String.valueOf(v)));
            }
            return sb.append('}').toString();
        }
    }

    // ── Fallback satırı imzası (H paketi bağımsız inceleme, 2026-11-26) ──────────────────────
    //
    // Geri aktarım dosyadaki satırı hash zincirine seq_locked = TRUE ile ekler. Dosyaya yazabilen biri (pod içi süreç; REPORT
    // kipinde k6 handleSummary) imzasız satırla doğru zincirlenmiş SAHTE denetim kaydı (ör. kilit / anomali sayaçlarını
    // besleyen LOGIN_FAILED) sokabilirdi. Satır yazılırken gövdesi HMAC-SHA256 ile imzalanır: anahtar
    // SITE_MONITOR_SECRET_KEY'den türetilen alt anahtar ({@link SecretCipher#hmacSubKey}, amaç {@value #FALLBACK_SIG_PURPOSE};
    // tüm pod'larda aynı); döndürmede önceki anahtarın alt anahtarı da kabul edilir. Anahtar ayarlı DEĞİLSE (yalnız yerel / dev;
    // prod profili anahtarsız açılmaz) süreç başına rastgele anahtar + bir WARN — gömülü dev anahtarı açık olduğundan ondan
    // türetilmez; bedeli: yeniden başlatmadan önce yazılmış satırlar geri aktarılamaz (.failed). Biçim:
    // {@code {...gövde...,"sig":"<base64url>"}} — imza, sondaki {@code ,"sig":"…"} üyesi çıkarılmış gövdenin TAM metni
    // üzerindedir (yeniden biçimlendirme yok).

    static final String FALLBACK_SIG_PURPOSE = "audit-fallback";
    private static final String SIG_MEMBER = ",\"sig\":\"";
    /** 32 baytlık HMAC'in dolgusuz base64url uzunluğu. */
    private static final int SIG_B64_LEN = 43;
    private static final java.util.regex.Pattern SIG_B64 = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{43}");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SecretCipher secretCipher;
    private final Object fallbackKeyLock = new Object();
    /** [0] güncel, [1] önceki (döndürme; yoksa null). Tembel türetilir. */
    private volatile byte[][] fallbackKeys;

    /** Test kancası: imza anahtarlarını doğrudan ver (aynı anahtarı paylaşan pod'lar, anahtar döndürme). */
    void setFallbackKeys(byte[] current, byte[] previous) {
        this.fallbackKeys = new byte[][] {current, previous};
    }

    private byte[][] fallbackKeys() {
        byte[][] k = fallbackKeys;
        if (k != null) return k;
        synchronized (fallbackKeyLock) {
            if (fallbackKeys != null) return fallbackKeys;
            SecretCipher c = secretCipher;
            byte[] cur = c == null ? null : c.hmacSubKey(FALLBACK_SIG_PURPOSE);
            byte[] prev = null;
            if (cur == null) {
                cur = new byte[32];
                new java.security.SecureRandom().nextBytes(cur);
                log.warn("Denetim fallback satırları: SITE_MONITOR_SECRET_KEY ayarlı değil — satırlar süreç başına rastgele bir "
                        + "anahtarla imzalanıyor; yeniden başlatmadan önce yazılmış satırlar geri aktarılamaz (.failed). "
                        + "Prod'da anahtar zorunludur.");
            } else {
                prev = c.previousHmacSubKey(FALLBACK_SIG_PURPOSE);
            }
            fallbackKeys = new byte[][] {cur, prev};
            return fallbackKeys;
        }
    }

    /** İmzalı tek satır fallback kaydı (satır sonu YOK). */
    String fallbackLine(AuditLog e) {
        return signFallbackBody(fallbackJson(e));
    }

    /** {@code {...}} gövdesine sondaki imza üyesini ekler. */
    String signFallbackBody(String body) {
        String sig = hmacB64(fallbackKeys()[0], body);
        return body.substring(0, body.length() - 1) + SIG_MEMBER + sig + "\"}";
    }

    private static String hmacB64(byte[] key, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC hesaplanamadı");
        }
    }

    /** İmza denetimi sonucu: {@code body} = doğrulanmış gövde ya da null; {@code problem} = {@code unsigned} | {@code bad_signature}. */
    record SigCheck(String body, String problem) {}

    /**
     * Satırın imzasını JSON AYRIŞTIRILMADAN denetler (sabit zamanlı karşılaştırma; güncel ve — varsa — önceki anahtar HER ZAMAN
     * hesaplanır). İmza üyesi yok → {@code unsigned} (önceki sürümün v1 satırı da); biçim / değer yanlış → {@code bad_signature}.
     */
    SigCheck checkFallbackSignature(String line) {
        if (line == null || line.length() < 2 || line.charAt(0) != '{' || !line.endsWith("\"}")) {
            return new SigCheck(null, "unsigned");
        }
        int at = line.lastIndexOf(SIG_MEMBER);
        int sigStart = at + SIG_MEMBER.length();
        int sigEnd = line.length() - 2;
        if (at <= 0 || sigStart > sigEnd) return new SigCheck(null, "unsigned");
        String sig = line.substring(sigStart, sigEnd);
        if (sig.length() != SIG_B64_LEN || !SIG_B64.matcher(sig).matches()) return new SigCheck(null, "bad_signature");
        String body = line.substring(0, at) + "}";
        byte[] given = sig.getBytes(StandardCharsets.US_ASCII);
        byte[][] k = fallbackKeys();
        boolean ok = MessageDigest.isEqual(hmacB64(k[0], body).getBytes(StandardCharsets.US_ASCII), given);
        if (k[1] != null) ok |= MessageDigest.isEqual(hmacB64(k[1], body).getBytes(StandardCharsets.US_ASCII), given);
        return ok ? new SigCheck(body, null) : new SigCheck(null, "bad_signature");
    }

    /** Doğrulanmış gövdenin izin verilen anahtarları — yazıcının ({@link #fallbackJson}) yazdıklarıyla aynı. */
    static final java.util.Set<String> REPLAY_KEYS = java.util.Set.of(
            "v", "fid", "event_time", "event_type", "actor", "actor_id", "actor_team_id", "actor_role", "ip_address",
            "user_agent", "session_id", "resource_type", "resource_id", "detail", "changes", "outcome", "failure_reason",
            "anomaly_flags", "correlation_id", "pod");
    /** Denetim yazıcılarının kullandığı sonuçlar ({@code null} da geçerli). */
    static final java.util.Set<String> REPLAY_OUTCOMES = java.util.Set.of("SUCCESS", "FAILURE", "BLOCKED");
    /** Fallback satırındaki pod adının üst sınırı (yazıcı sığdırır). */
    static final int FALLBACK_POD_MAX = 255;
    /**
     * Kısa metin alanlarının üst sınırı = sütun uzunluğu ({@link #FIT_LENGTHS}, tek kaynak; yazıcı satırı yazmadan önce
     * {@link #fitColumns} ile sığdırır, yani kendi yazdığımız satır bu denetime takılmaz).
     */
    static final Map<String, Integer> REPLAY_STRING_CAPS = replayStringCaps();

    private static Map<String, Integer> replayStringCaps() {
        Map<String, Integer> m = new java.util.HashMap<>();
        for (String k : List.of("event_time", "actor", "actor_role", "ip_address", "session_id", "resource_type",
                "resource_id", "failure_reason", "anomaly_flags", "correlation_id")) {
            m.put(k, FIT_LENGTHS.get(k));
        }
        m.put("user_agent", AuditLog.USER_AGENT_MAX);   // setter kırpar
        m.put("pod", FALLBACK_POD_MAX);
        return Map.copyOf(m);
    }

    /**
     * Ayrıştırma SINIRLARI (alan uzunluğu, ad uzunluğu, iç içe derinlik, sayı uzunluğu) ayrıştırıcının kendisinde: sınırı
     * aşan satır ayrıştırılırken reddedilir (bellek baştan sınırlı; satır sınırı ayrıca {@value #REPLAY_MAX_LINE_CHARS}).
     */
    private static final tools.jackson.databind.ObjectMapper REPLAY_JSON = tools.jackson.databind.json.JsonMapper.builder(
            tools.jackson.core.json.JsonFactory.builder()
                    .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
                            .maxStringLength(AuditService.REPLAY_MAX_LINE_CHARS)
                            .maxNameLength(64)
                            .maxNestingDepth(2)
                            .maxNumberLength(20)
                            .build())
                    .build())
            .build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseReplayBody(String body) {
        try {
            Object o = REPLAY_JSON.readValue(body, Object.class);
            return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * İmzası doğrulanmış gövdenin alan denetimi: bilinmeyen anahtar yok, sürüm {@value #FALLBACK_FORMAT}, geçerli {@code fid},
     * olay türü {@link AuditEventCatalog}'da, sonuç bilinen kümede (ya da boş), kimlikler tam sayı, metinler metin ve sütun
     * sınırında. Geçerliyse {@code null}, değilse kısa sorun kodu.
     */
    static String validateReplayFields(Map<String, Object> m) {
        for (String k : m.keySet()) if (!REPLAY_KEYS.contains(k)) return "unknown_field";
        if (!(m.get("v") instanceof Integer ver) || ver != FALLBACK_FORMAT) return "version";
        if (!(m.get("fid") instanceof String fid) || !FID.matcher(fid).matches()) return "fid";
        if (!(m.get("event_type") instanceof String type) || !AuditEventCatalog.contains(type)) return "event_type";
        Object outcome = m.get("outcome");
        if (outcome != null && !(outcome instanceof String os && REPLAY_OUTCOMES.contains(os))) return "outcome";
        if (!(m.get("event_time") instanceof String)) return "event_time";
        for (String k : List.of("actor_id", "actor_team_id")) {
            Object v = m.get(k);
            if (v != null && !(v instanceof Integer || v instanceof Long)) return k;
        }
        for (Map.Entry<String, Integer> cap : REPLAY_STRING_CAPS.entrySet()) {
            Object v = m.get(cap.getKey());
            if (v != null && !(v instanceof String s && s.length() <= cap.getValue())) return cap.getKey();
        }
        for (String k : List.of("detail", "changes")) {
            Object v = m.get(k);
            if (v != null && !(v instanceof String)) return k;
        }
        return null;
    }

    /** Doğrusal JSON metin kaçışı (yedek yol): ters bölü, tırnak, tüm kontrol karakterleri, U+2028 / U+2029. */
    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == 0x7F) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Günlük satırı için tek satır (başarısız girişte aktör kullanıcının yazdığıdır — satır sonu günlüğü bölmesin). */
    private static String oneLine(String s) {
        if (s == null) return null;
        String t = s.replace('\r', ' ').replace('\n', ' ');
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** Bu pod'un adı (fallback satırı ve geri aktarım ayrıntısı için); okunamazsa {@code null}. */
    static String podName() {
        try {
            return com.sitemonitor.service.health.ClusterHealthStore.podOf(SchedulerService.instanceId());
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Fallback dosyasının geri aktarımı (H paketi B7, 2026-11-26) ─────────────────────────
    //
    // Dosya pod'a özgüdür (prod: emptyDir log dizini): her pod KENDİ dosyasını, veritabanı okunabilir olunca kendiliğinden
    // denetim tablosuna aktarır — eskiden dosyayı GERİ OKUYAN hiçbir kod yoktu, pod değişince kayıtlar kalıcı kayboluyordu.
    // Akış: yazılmakta olan dosya (önceki tur bittiyse) "<ad>.replay" adına taşınır; taşınan dosya bir daha DEĞİŞMEZ, yeni
    // düşüşler yeni dosyaya yazılır. Her satır kalıcı kimliğiyle ({@code fid}; eski satırda içerik özeti + aynı içerikli
    // satırların sırası) bir nişan ({@code schema_patch_markers}, anahtar {@value #MARKER_PREFIX}…) alır; nişan ve denetim
    // satırı AYNI işlemde, zincir kuralıyla ({@link #SQL_CHAIN_LOCK} / JVM kilidi) yazılır — nişan varsa satır atlanır (tekrar
    // yazım yok, dosya kesme yok). Tüm satırlar nişanlanınca .replay dosyası silinir. Aktarılan satırın zamanı AKTARIM anıdır
    // (zincir sırası zamanla uyumlu; saklama silmesi zincirin ortasında boşluk açmaz); asıl zaman / kimlik / pod ayrıntıdadır.
    // Ayrıştırılamayan, aşırı uzun ya da veritabanı sağlıklıyken {@value #REPLAY_MAX_ATTEMPTS} kez yazılamayan satır
    // "<ad>.failed" dosyasına alınır (elle incelenir, bekleyen sayılır) ve nişanlanır.

    static final String REPLAY_SUFFIX = ".replay";
    static final String FAILED_SUFFIX = ".failed";
    static final String MARKER_PREFIX = "audit-fallback:";
    /** Tur başına en çok yazılan satır. */
    static final int REPLAY_MAX_WRITES = 2000;
    /** Tur başına en çok taranan satır — çok büyük dosyada iş sınırlı kalır (kalanlar bekleyen sayılır). */
    static final int REPLAY_MAX_SCAN = 200_000;
    /** Satır uzunluk sınırı (karakter) — aşan satır aktarılmaz, .failed dosyasına alınır. */
    static final int REPLAY_MAX_LINE_CHARS = 512 * 1024;
    /** Aynı satırın (veritabanı sağlıklıyken) yazılamama sınırı — sonra .failed dosyasına alınır. */
    static final int REPLAY_MAX_ATTEMPTS = 3;
    /** Nişanlar bu süreden sonra silinir (gün). */
    static final int REPLAY_MARKER_KEEP_DAYS = 90;
    private static final int REPLAY_MARKER_CHUNK = 500;
    private static final int FAILED_LINE_MAX = 8000;
    private static final java.util.regex.Pattern FID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final java.util.regex.Pattern LONG_TEXT = java.util.regex.Pattern.compile("-?\\d{1,18}");

    private final ReentrantLock replayLock = new ReentrantLock();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> replayAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    /** .replay dosyasında aktarılmış (nişanlı) satır sayısı — bekleyen sayısından düşülür; dosya silinince kalkar. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> replayDone = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean replayClosing;
    private volatile long lastMarkerPruneMs;

    /** Kapanışta yeni geri aktarım turu başlamaz; süren tur satır aralarında durur (bayrak, bekleme yok). */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void stopFallbackReplay() {
        replayClosing = true;
    }

    /** Her pod kendi dosyasını aktarır — kilit yok (dosya pod'a özgü), çakışma nişanla önlenir. Açılışta 2 dk, sonra 5 dk. */
    @org.springframework.scheduling.annotation.Scheduled(
            initialDelayString = "${site.monitor.audit.fallback-replay.initial-delay-ms:120000}",
            fixedDelayString = "${site.monitor.audit.fallback-replay.interval-ms:300000}")
    void scheduledFallbackReplay() {
        try {
            ReplayResult r = replayFallback();
            if (r.replayed() > 0 || r.parked() > 0) {
                log.warn("Denetim fallback dosyası geri aktarıldı: {} kayıt denetim tablosuna yazıldı, {} kayıt aktarılamadı "
                        + "(.failed dosyasında, elle incelenmeli); bekleyen {}", r.replayed(), r.parked(),
                        pendingFallbackAuditCount());
            }
        } catch (Throwable t) {
            log.warn("Denetim fallback geri aktarımı başarısız — sonraki turda yeniden denenecek: {}", t.toString());
        }
    }

    /**
     * Tur sonucu: yazılan, zaten aktarılmış (atlanan), .failed'a alınan; {@code dbUnavailable} = tur veritabanı yüzünden durdu;
     * {@code rejected} = .failed'a alınanlardan imzasız / imzası geçersiz / alanları geçersiz olanlar (zincire HİÇ yazılmaz).
     */
    public record ReplayResult(int replayed, int skipped, int parked, boolean dbUnavailable, int rejected) {
        static final ReplayResult NONE = new ReplayResult(0, 0, 0, false, 0);
        static final ReplayResult DB_DOWN = new ReplayResult(0, 0, 0, true, 0);

        public ReplayResult(int replayed, int skipped, int parked, boolean dbUnavailable) {
            this(replayed, skipped, parked, dbUnavailable, 0);
        }
    }

    private static final class ReplayCounter {
        int replayed, skipped, parked, rejected;
        boolean stopped, dbFailed;
        final java.util.Map<String, Integer> rejectedBy = new java.util.TreeMap<>();
    }

    /**
     * {@code parsed} = imzası ve alanları doğrulanmış gövde (yoksa null → .failed); {@code problem} = reddin kısa kodu
     * ({@code oversized}, {@code unsigned}, {@code bad_signature}, {@code unparseable}, alan kodu); doğrulanmayan satırın
     * kimliği İÇERİKTEN türetilir ({@code raw-<sha256>-<sıra>}) — satırın kendi beyan ettiği {@code fid} KULLANILMAZ (sahte
     * satır, gerçek bir satırın nişanını önceden yazıp onu "aktarılmış" gösteremesin).
     */
    private record ReplayLine(String fid, String text, Map<String, Object> parsed, String problem) {}

    /**
     * Bir geri aktarım turu (paket içi: testler doğrudan çağırır). Sınırlı: en çok {@value #REPLAY_MAX_WRITES} yazım,
     * {@value #REPLAY_MAX_SCAN} satır tarama; veritabanı okunamazsa ya da sağlıklı veritabanında bir satır yazılamazsa tur
     * durur (sonraki tur yeniden dener). Altyapı yoksa (elle kurulan birim testi) hiçbir şey yapmaz.
     */
    ReplayResult replayFallback() {
        if (replayClosing) return ReplayResult.NONE;
        if (!replayLock.tryLock()) return ReplayResult.NONE;
        try {
            List<Path> paths = fallbackPaths();
            boolean work = false;
            for (Path p : paths) if (hasFallbackWork(p)) { work = true; break; }
            if (!work) return ReplayResult.NONE;   // dosya yok → veritabanına hiç gidilmez
            if (!chainSupported()) return ReplayResult.NONE;
            if (!dbReadable()) return ReplayResult.DB_DOWN;
            boolean pg = isPostgres();
            if (chainPostgres == null) return ReplayResult.DB_DOWN;   // tür bilinmeden kilit seçilemez
            chainJdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            pruneReplayMarkers();
            ReplayCounter c = new ReplayCounter();
            for (Path p : paths) {
                if (replayClosing || c.stopped || c.replayed >= REPLAY_MAX_WRITES) break;
                replayPath(p, pg, c);
            }
            if (c.rejected > 0) {
                log.warn("Denetim fallback dosyasında {} satır doğrulanamadı {} — zincire YAZILMADI, .failed dosyasına alındı "
                        + "(elle incelenmeli; imzasız / imzası geçersiz satır dosyaya dışarıdan yazılmış olabilir)",
                        c.rejected, c.rejectedBy);
            }
            return new ReplayResult(c.replayed, c.skipped, c.parked, c.dbFailed, c.rejected);
        } finally {
            replayLock.unlock();
        }
    }

    private static boolean hasFallbackWork(Path p) {
        try {
            return Files.exists(siblingWith(p, REPLAY_SUFFIX)) || (Files.exists(p) && Files.size(p) > 0);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean dbReadable() {
        try {
            chainJdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Günde en çok bir kez: süresi dolmuş geri aktarım nişanları silinir (en iyi çaba). */
    private void pruneReplayMarkers() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastMarkerPruneMs < 24L * 3600_000L) return;
        lastMarkerPruneMs = nowMs;
        try {
            String cutoff = ISO.format(Instant.ofEpochMilli(nowMs).minus(java.time.Duration.ofDays(REPLAY_MARKER_KEEP_DAYS)));
            chainJdbc.update("DELETE FROM schema_patch_markers WHERE patch_key LIKE 'audit-fallback:%' AND applied_at < ?", cutoff);
        } catch (RuntimeException e) {
            log.debug("Denetim geri aktarım nişanları budanamadı: {}", e.toString());
        }
    }

    private void replayPath(Path p, boolean pg, ReplayCounter c) {
        Path replay = siblingWith(p, REPLAY_SUFFIX);
        String key = replay.toString();
        try {
            synchronized (fallbackFileLock) {
                if (!Files.exists(replay) && Files.exists(p) && Files.size(p) > 0) {
                    try {
                        Files.move(p, replay, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                        Files.move(p, replay);
                    }
                    replayDone.remove(key);
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            log.warn("Denetim fallback dosyası geri aktarım için taşınamadı ({}): {}", p, ex.toString());
            return;
        }
        if (!Files.exists(replay)) return;
        long done = 0;
        boolean allDone = true;
        boolean eof = false;
        int scanned = 0;
        java.util.Map<String, Integer> occurrence = new java.util.HashMap<>();
        List<ReplayLine> batch = new ArrayList<>(REPLAY_MARKER_CHUNK);
        try (BoundedLineReader in = new BoundedLineReader(replay, REPLAY_MAX_LINE_CHARS)) {
            while (true) {
                if (replayClosing) { allDone = false; break; }
                BoundedLineReader.Line line = in.next();
                if (line == null) { eof = true; break; }
                if (!line.oversized() && line.text().isBlank()) continue;
                if (++scanned > REPLAY_MAX_SCAN) { allDone = false; break; }
                batch.add(toReplayLine(line, occurrence));
                if (batch.size() >= REPLAY_MARKER_CHUNK) {
                    long[] o = processReplayBatch(batch, p, pg, c);
                    batch.clear();
                    done += o[0];
                    if (o[1] == 0) { allDone = false; break; }   // durdu (bütçe / hata / kapanış)
                }
            }
            if (!batch.isEmpty()) {
                long[] o = processReplayBatch(batch, p, pg, c);
                done += o[0];
                if (o[1] == 0) allDone = false;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            log.warn("Denetim fallback dosyası okunamadı ({}): {}", replay, ex.toString());
            return;
        }
        replayDone.put(key, done);
        if (eof && allDone) {
            try {
                Files.deleteIfExists(replay);
                replayDone.remove(key);
            } catch (java.io.IOException ex) {
                log.warn("Aktarılmış denetim fallback dosyası silinemedi ({}): {}", replay, ex.toString());
            }
        }
    }

    /** Bu yaştan eski (asıl zamanı) satır aktarılmaz — nişanlar {@value #REPLAY_MARKER_KEEP_DAYS} gün tutulur; saklanmış imzalı
     *  bir satırın kopyası, nişanı budandıktan sonra zincire yeniden giremesin. */
    static final int REPLAY_LINE_MAX_AGE_DAYS = 60;

    /**
     * Sıra: satır sınırı ({@code oversized}) → imza, JSON AYRIŞTIRILMADAN ({@code unsigned} / {@code bad_signature}; önceki
     * sürümün imzasız v1 satırı da — sahtesi yazılabilirdi) → sınırlı ayrıştırma ({@code unparseable}) → alan denetimi
     * ({@link #validateReplayFields}) → yaş ({@code stale}). Doğrulanan satırın kimliği kendi {@code fid}'i, doğrulanmayanınki
     * içerik özeti.
     */
    private ReplayLine toReplayLine(BoundedLineReader.Line line, java.util.Map<String, Integer> occurrence) {
        String text = line.text();
        Map<String, Object> m = null;
        String problem;
        if (line.oversized()) {
            problem = "oversized";
        } else {
            SigCheck sc = checkFallbackSignature(text.strip());
            if (sc.problem() != null) {
                problem = sc.problem();
            } else {
                m = parseReplayBody(sc.body());
                problem = m == null ? "unparseable" : validateReplayFields(m);
                if (problem == null && tooOld((String) m.get("event_time"))) problem = "stale";
                if (problem != null) m = null;
            }
        }
        String fid;
        if (m != null) {
            fid = (String) m.get("fid");
        } else {
            String h = sha256((line.oversized() ? "oversized|" : "") + text);
            int k = occurrence.merge(h, 1, Integer::sum) - 1;
            fid = "raw-" + h + "-" + k;
        }
        return new ReplayLine(fid, text, m, problem);
    }

    /** Asıl zaman {@value #REPLAY_LINE_MAX_AGE_DAYS} günden eskiyse ya da okunamıyorsa {@code true}. */
    private static boolean tooOld(String eventTime) {
        try {
            String t = eventTime.strip();
            java.time.LocalDateTime at = java.time.LocalDateTime.parse(t.length() > 19 ? t.substring(0, 19) : t);
            return at.toInstant(ZoneOffset.UTC).isBefore(Instant.now().minus(java.time.Duration.ofDays(REPLAY_LINE_MAX_AGE_DAYS)));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** {@code [aktarılmış satır, hepsi bitti mi (1/0)]}. */
    private long[] processReplayBatch(List<ReplayLine> batch, Path source, boolean pg, ReplayCounter c) {
        java.util.Set<String> have = existingMarkers(batch);
        if (have == null) { c.stopped = true; c.dbFailed = true; return new long[] {0, 0}; }
        long done = 0;
        boolean all = true;
        for (ReplayLine x : batch) {
            String marker = MARKER_PREFIX + x.fid();
            if (have.contains(marker)) { done++; c.skipped++; continue; }
            if (replayClosing || c.stopped || c.replayed >= REPLAY_MAX_WRITES) { all = false; continue; }
            if (x.parsed() == null) {   // imzasız / imzası geçersiz / ayrıştırılamadı / alanı geçersiz → elle incelemeye
                if (park(x, source, marker, c)) {
                    done++;
                    c.rejected++;
                    if (c.rejectedBy.size() < 32) c.rejectedBy.merge(String.valueOf(x.problem()), 1, Integer::sum);
                } else {
                    all = false;
                }
                continue;
            }
            try {
                boolean wrote = insertReplayed(replayedLog(x), marker, pg);
                done++;
                if (wrote) c.replayed++; else c.skipped++;
                replayAttempts.remove(x.fid());
            } catch (RuntimeException ex) {
                if (!dbReadable()) {   // veritabanı gitti — satırın suçu değil, deneme sayılmaz
                    c.stopped = true;
                    c.dbFailed = true;
                    all = false;
                    continue;
                }
                if (replayAttempts.size() > 10_000) replayAttempts.clear();
                int n = replayAttempts.merge(x.fid(), 1, Integer::sum);
                if (n >= REPLAY_MAX_ATTEMPTS) {
                    replayAttempts.remove(x.fid());
                    log.warn("Denetim fallback satırı {} denemede yazılamadı — .failed dosyasına alınıyor (fid={}): {}",
                            n, x.fid(), ex.toString());
                    if (park(x, source, marker, c)) done++; else all = false;
                } else {
                    log.warn("Denetim fallback satırı yazılamadı (deneme {}/{}, fid={}) — tur durdu: {}",
                            n, REPLAY_MAX_ATTEMPTS, x.fid(), ex.toString());
                    c.stopped = true;
                    all = false;
                }
            }
        }
        return new long[] {done, all ? 1 : 0};
    }

    /** Partideki satırların VAR OLAN nişanları; okunamazsa {@code null}. */
    private java.util.Set<String> existingMarkers(List<ReplayLine> batch) {
        try {
            java.util.Set<String> out = new java.util.HashSet<>();
            List<Object> keys = new ArrayList<>(batch.size());
            for (ReplayLine x : batch) keys.add(MARKER_PREFIX + x.fid());
            for (int i = 0; i < keys.size(); i += REPLAY_MARKER_CHUNK) {
                List<Object> part = keys.subList(i, Math.min(keys.size(), i + REPLAY_MARKER_CHUNK));
                String in = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
                out.addAll(chainJdbc.queryForList(
                        "SELECT patch_key FROM schema_patch_markers WHERE patch_key IN (" + in + ")", String.class, part.toArray()));
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("Denetim geri aktarım nişanları okunamadı: {}", e.toString());
            return null;
        }
    }

    /**
     * Nişan + denetim satırı AYNI işlemde, zincir kuralıyla (yazıcılarla aynı kilit). Nişan zaten varsa (başka tur bu arada
     * yazdı) {@code false}, yazılmaz. Hata yukarı çıkar (çağıran deneme sayar ya da turu durdurur) — fallback'e DÜŞMEZ.
     */
    private boolean insertReplayed(AuditLog e, String marker, boolean pg) {
        java.util.function.Supplier<Boolean> body = () -> chainTx.execute(st -> {
            if (pg) chainJdbc.execute(SQL_CHAIN_LOCK);
            Integer n = chainJdbc.queryForObject(
                    "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, marker);
            if (n != null && n > 0) return Boolean.FALSE;
            chainJdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                    marker, now(), 1);
            appendToChain(e, pg || Boolean.FALSE.equals(chainPostgres));
            return Boolean.TRUE;
        });
        if (pg) return Boolean.TRUE.equals(body.get());
        chainLock.lock();
        try {
            return Boolean.TRUE.equals(body.get());
        } finally {
            chainLock.unlock();
        }
    }

    /** Satırı .failed dosyasına (tek satır, kırpılmış) ekler ve nişanlar; nişan yazılamazsa {@code false} (tur durur). */
    private boolean park(ReplayLine x, Path source, String marker, ReplayCounter c) {
        String raw = x.text().length() > FAILED_LINE_MAX ? x.text().substring(0, FAILED_LINE_MAX) + "…" : x.text();
        try {
            synchronized (fallbackFileLock) {
                appendLine(siblingWith(source, FAILED_SUFFIX), raw.replace('\r', ' ').replace('\n', ' ') + "\n");
            }
        } catch (java.io.IOException | RuntimeException ex) {
            log.warn("Denetim fallback satırı .failed dosyasına yazılamadı (fid={}): {}", x.fid(), ex.toString());
            c.stopped = true;
            return false;
        }
        try {
            Integer n = chainJdbc.queryForObject(
                    "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, marker);
            if (n == null || n == 0) {
                chainJdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                        marker, now(), -1);
            }
            c.parked++;
            return true;
        } catch (org.springframework.dao.DuplicateKeyException already) {
            // Nişan bu arada yazıldı (aynı dosyayı paylaşan başka bir tur) — satır işlenmiş sayılır, veritabanı hatası değil.
            c.skipped++;
            return true;
        } catch (RuntimeException ex) {
            log.warn("Denetim fallback satırı nişanlanamadı (fid={}): {}", x.fid(), ex.toString());
            c.stopped = true;
            c.dbFailed = true;
            return false;
        }
    }

    /** Fallback satırından aktarılacak denetim kaydı: zaman = aktarım anı; asıl zaman / kimlik / pod ayrıntıda. */
    private AuditLog replayedLog(ReplayLine x) {
        Map<String, Object> m = x.parsed();
        AuditLog e = new AuditLog();
        e.setEventTime(now());
        e.setEventType(blankToNull(m.get("event_type")));
        e.setActor(blankToNull(m.get("actor")));
        e.setActorId(longOrNull(m.get("actor_id")));
        e.setActorTeamId(longOrNull(m.get("actor_team_id")));
        e.setActorRole(blankToNull(m.get("actor_role")));
        e.setIpAddress(blankToNull(m.get("ip_address")));
        e.setUserAgent(blankToNull(m.get("user_agent")));
        e.setSessionId(blankToNull(m.get("session_id")));
        e.setResourceType(blankToNull(m.get("resource_type")));
        e.setResourceId(blankToNull(m.get("resource_id")));
        e.setChanges(blankToNull(m.get("changes")));
        e.setOutcome(blankToNull(m.get("outcome")));
        e.setFailureReason(blankToNull(m.get("failure_reason")));
        e.setAnomalyFlags(blankToNull(m.get("anomaly_flags")));
        e.setCorrelationId(blankToNull(m.get("correlation_id")));
        e.setDetail(replayDetail(blankToNull(m.get("detail")), x.fid(), blankToNull(m.get("event_time")),
                blankToNull(m.get("pod"))));
        return e;
    }

    /** Asıl ayrıntı (JSON nesnesiyse anahtarları korunur, değilse {@code original_detail}) + geri aktarım anahtarları. */
    static String replayDetail(String original, String fid, String originalTime, String pod) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (original != null) {
            Map<String, Object> parsed = original.stripLeading().startsWith("{") ? parseObject(original) : null;
            if (parsed != null) m.putAll(parsed);
            else m.put("original_detail", original);
        }
        m.put("replayed_from_fallback", Boolean.TRUE);
        m.put("fallback_id", fid);
        m.put("original_event_time", originalTime);
        if (pod != null) m.put("fallback_pod", pod);
        try {
            return FALLBACK_JSON.writeValueAsString(m);
        } catch (RuntimeException ex) {
            return AuditDetail.of("replayed_from_fallback", true, "fallback_id", fid, "original_event_time", originalTime);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String text) {
        try {
            Object o = FALLBACK_JSON.readValue(text, Object.class);
            return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static Long longOrNull(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && LONG_TEXT.matcher(s.trim()).matches()) return Long.parseLong(s.trim());
        return null;
    }

    /**
     * Satır satır okur; satır sınırı aşılırsa kalanı atlanır ve satır "aşırı uzun" işaretlenir. Geçersiz UTF-8 baytları
     * U+FFFD olur (okuma düşmez). Her çağrı dosyada ilerler → döngü dosya boyutuyla sınırlı.
     */
    static final class BoundedLineReader implements AutoCloseable {
        record Line(String text, boolean oversized) {}

        private final java.io.Reader in;
        private final int max;

        BoundedLineReader(Path p, int max) throws java.io.IOException {
            this.max = max;
            var dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            this.in = new java.io.BufferedReader(new java.io.InputStreamReader(Files.newInputStream(p), dec), 64 * 1024);
        }

        /** Sonraki satır; dosya bittiyse {@code null}. */
        Line next() throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            boolean over = false;
            boolean any = false;
            int ch;
            while ((ch = in.read()) != -1) {
                any = true;
                if (ch == '\n') break;
                if (ch == '\r') continue;
                if (sb.length() < max) sb.append((char) ch);
                else over = true;
            }
            return any ? new Line(sb.toString(), over) : null;
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }

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
    /**
     * Yeniden başlatma satırının ayrıntısı — tek tarama, zincir kilidi ALINMADAN önce (inceleme F5: kilit altında tam tarama
     * her pod'daki her denetim yazıcısını bekletirdi). Bilgi amaçlı: kilit alınana dek yazılan birkaç satır sayılmayabilir.
     * Kullanıcı verisi yok.
     */
    static final String SQL_RESTART_STATS = "SELECT COUNT(*) AS rows_before,"
            + " SUM(CASE WHEN seq = 1 THEN 1 ELSE 0 END) AS seq1_rows,"
            + " MIN(CASE WHEN seq = 1 THEN event_time END) AS period_from,"
            + " MAX(event_time) AS period_to FROM audit_log WHERE seq IS NOT NULL";

    public enum RestartOutcome { WRITTEN, ALREADY_RESTARTED, NOT_DEGENERATE }

    /** Yeniden başlatma denemesinin sonucu; {@code row} yazılan ya da zaten var olan yeniden başlatma satırı. */
    public record ChainRestart(RestartOutcome outcome, AuditLog row) {}

    /**
     * EN ESKİ (hâlâ duran) yeniden başlatma satırı; yoksa {@code null}. Meşru olarak yalnız BİR tane yazılır (nişan + kilit
     * içi denetim); doğrulama bundan başlar — sonradan eklenmiş sahte bir ikinci satır doğrulamada kırık görünür.
     */
    AuditLog firstChainRestart() {
        return auditLogRepo.findTopByEventTypeAndSeqIsNotNullOrderBySeqAsc(EVENT_CHAIN_RESTART).orElse(null);
    }

    private boolean chainDegenerate() {
        return chainJdbc.queryForList(SQL_SEQ_ONE_PROBE, Long.class).size() >= 2;
    }

    /**
     * Zincir bozuksa (birden fazla {@code seq = 1}) ve henüz yeniden başlatılmadıysa YENİ bir zincir dönemi başlatır:
     * {@code seq = uç + 1}, {@code prev_hash = GENESIS}, olay {@value #EVENT_CHAIN_RESTART}. Ön karar ve ayrıntı taraması
     * kilitsiz; yazım yazıcılarla AYNI kilit altında (PostgreSQL: {@link #SQL_CHAIN_LOCK} aynı işlemde; diğer: JVM kilidi) —
     * kilit içinde yalnız "zaten var mı / bozuk mu" yeniden denetimi + uç okuması + INSERT (iki pod iki satır yazamaz,
     * yeniden başlatma ile normal satırlar iç içe geçmez). Fallback dosyasına DÜŞMEZ: her hata yukarı çıkar (çağıran iş
     * deneme sayar). Veritabanı türü okunamazsa hata ("sonra yeniden dene") — tür bilinmeden kilit seçilemez.
     */
    ChainRestart restartChainIfDegenerate() {
        if (!chainSupported()) throw new IllegalStateException("denetim zinciri altyapısı (JdbcTemplate + işlem yöneticisi) yok");
        boolean pg = isPostgres();
        if (chainPostgres == null) throw new IllegalStateException("veritabanı türü okunamadı — sonra yeniden denenecek");
        AuditLog existing = firstChainRestart();
        if (existing != null) return new ChainRestart(RestartOutcome.ALREADY_RESTARTED, existing);
        if (!chainDegenerate()) return new ChainRestart(RestartOutcome.NOT_DEGENERATE, null);
        Map<String, Object> stats = chainJdbc.queryForMap(SQL_RESTART_STATS);   // KİLİTSİZ (F5)
        if (pg) {
            return chainTx.execute(st -> {
                chainJdbc.execute(SQL_CHAIN_LOCK);
                return restartLocked(stats);
            });
        }
        chainLock.lock();
        try {
            return chainTx.execute(st -> restartLocked(stats));
        } finally {
            chainLock.unlock();
        }
    }

    private ChainRestart restartLocked(Map<String, Object> st) {
        AuditLog existing = firstChainRestart();
        if (existing != null) return new ChainRestart(RestartOutcome.ALREADY_RESTARTED, existing);
        if (!chainDegenerate()) return new ChainRestart(RestartOutcome.NOT_DEGENERATE, null);
        AuditLog tail = auditLogRepo.findTopBySeqIsNotNullOrderBySeqDesc().orElse(null);
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

    /**
     * Doğrulanan bölümün başı. {@code kind}: {@link #RESTART} — en eski zincir yeniden başlatma satırı (öncesi onarımdan
     * önce yazıldı, doğrulanamaz); {@link #RETENTION} — yeniden başlatma satırı yok ve en eski kalan zincirli satırın
     * {@code prev_hash}'i GENESIS değil (daha eski satırlar saklama süresiyle silindi; çapa o satırın saklanan prev_hash'i).
     */
    public record VerifiedFrom(Long id, Long seq, String eventTime, String kind) {
        public static final String RESTART = "restart";
        public static final String RETENTION = "retention";
    }

    /**
     * Doğrulama sonucu. {@code verifiedFrom}: doğrulama baştan (GENESIS) başlamadıysa nereden başladığı (yoksa {@code null},
     * bugünkü gibi); {@code unverified}: o bölümün DIŞINDA kalan zincirli satır sayısı (yeniden başlatmada: onarımdan önce
     * yazıldı — doğrulanamaz; saklama çapasında 0; seq'siz eski satırlar sayılmaz).
     */
    public record ChainVerification(boolean ok, Long brokenSeq, Long brokenId, long checked,
                                    VerifiedFrom verifiedFrom, long unverified, boolean incomplete, Long checkedToSeq) {
        public ChainVerification(boolean ok, Long brokenSeq, Long brokenId, long checked) {
            this(ok, brokenSeq, brokenId, checked, null, 0L);
        }
        public ChainVerification(boolean ok, Long brokenSeq, Long brokenId, long checked,
                                 VerifiedFrom verifiedFrom, long unverified) {
            this(ok, brokenSeq, brokenId, checked, verifiedFrom, unverified, false, null);
        }
    }

    /** Doğrulama sayfası (anahtar-küme, H paketi B2): sayfa başına en çok bu kadar YENİ satır (+1 örtüşme satırı okunur). */
    static final int VERIFY_PAGE_SIZE = AuditLogRepository.CHAIN_PAGE_FETCH - 1;
    /** Doğrulamanın 409 kodları (H paketi B2): pod'da başka doğrulama sürüyor / süre sınırında bitmedi. Kalıcı adlar. */
    public static final String VERIFY_RUNNING = "AUDIT_VERIFY_RUNNING";
    public static final String VERIFY_INCOMPLETE = "AUDIT_VERIFY_INCOMPLETE";

    /**
     * Tek doğrulamanın süre sınırı (saniye, H paketi B2): istemcinin GET zaman aşımı (90 sn) dolmadan dönülür; bitmeyen
     * tarama "tamamlanamadı" (ne temiz ne kırık) olarak raporlanır. Açılış anahtarı, katalogda değil.
     */
    @Value("${site.monitor.audit.verify-max-seconds:75}")
    private long verifyMaxSeconds = 75L;

    /** Pod başına tek doğrulama (H paketi B2): ikinci istek beklemez, 409 alır — tam taramalar üst üste binmez. */
    private final ReentrantLock verifyLock = new ReentrantLock();

    /** Test kancası: monoton saat (nanosaniye). */
    java.util.function.LongSupplier verifyClock = System::nanoTime;

    /** Test kancası: süre sınırı (ms); {@code null} = ayar (saniye). */
    private volatile Long verifyMaxMillisOverride;

    void setVerifyMaxMillis(Long ms) { this.verifyMaxMillisOverride = ms; }

    private long verifyBudgetNanos() {
        Long override = verifyMaxMillisOverride;
        if (override != null) return Math.max(0L, override) * 1_000_000L;
        // Geçersiz (≤ 0) değer varsayılana düşer; üst sınır 1 sa (uç asla sınırsız taramaz).
        long s = verifyMaxSeconds <= 0 ? 75L : Math.min(verifyMaxSeconds, 3600L);
        return s * 1_000_000_000L;
    }

    /**
     * Zinciri yeniden hesaplayıp saklanan hash'lerle karşılaştırır; ilk kırık satırı döner. Çapa (inceleme F4):
     * <ul>
     *   <li>bir yeniden başlatma satırı ({@value #EVENT_CHAIN_RESTART}) varsa EN ESKİSİNDEN, {@code GENESIS} ile
     *       ({@code seq >= onun seq'i}); öncesi {@code unverified}. Sonradan eklenmiş ikinci bir yeniden başlatma satırı
     *       ({@code prev_hash = GENESIS}) bölümün ortasında kırık görünür — yazılmışları gizleyemez;</li>
     *   <li>yoksa en düşük seq'li KALAN zincirli satırdan, onun saklanan {@code prev_hash}'i ile: silinmemiş sağlam zincirde
     *       bu GENESIS'tir (bugünkü davranış birebir, {@code verifiedFrom} yok); saklama süresi en eski satırları sildiyse
     *       doğrulama kalan ilk satırdan sürer ({@code verifiedFrom.kind = retention}) — eskiden her silmeden sonra "kırık".</li>
     * </ul>
     */
    public ChainVerification verifyChain() {
        // Pod başına tek doğrulama (H paketi B2): ikinci istek BEKLEMEZ — 409. Tam tarama milyonlarca satırı hash'ler; üst
        // üste binen istekler aynı işi tekrarlayıp havuz bağlantısı ve CPU tüketirdi.
        if (!verifyLock.tryLock()) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException(VERIFY_RUNNING,
                    com.sitemonitor.util.Msg.t(
                            "Denetim zinciri doğrulaması bu sunucuda zaten sürüyor. Bir dakika sonra yeniden deneyin.",
                            "An audit chain verification is already running on this server. Try again in a minute."));
        }
        try {
            return verifyChainLocked();
        } finally {
            verifyLock.unlock();
        }
    }

    /**
     * Doğrulamanın gövdesi — anahtar-küme sayfalama (yalnız {@code seq} sırasıyla, {@link AuditLogRepository#findChainPageFrom};
     * sayfa başına en çok {@value #VERIFY_PAGE_SIZE} yeni satır) ve süre sınırı (H paketi B2). Her sayfa
     * {@code seq >= son denetlenen seq}'ten başlar: son denetlenen satır kimliğiyle atlanır; aynı seq'li BAŞKA bir satır
     * (sayfa içinde ya da sayfa sınırında) çataldır → kırık, o seq'te, iki kimliğin büyüğüyle (eski {@code (seq, id)}
     * sırasının ikinci satırı). Sonuç OFSET sayfalamalı eski yöntemle AYNI (kıyas testi {@code AuditVerifyChainKeysetTest});
     * süre dolarsa kırık bulunmadıysa {@code incomplete = true} + {@code checkedToSeq} (ne temiz ne kırık).
     */
    private ChainVerification verifyChainLocked() {
        long deadline = verifyClock.getAsLong() + verifyBudgetNanos();
        AuditLog restart = firstChainRestart();
        long fromSeq;
        String prev;
        VerifiedFrom from;
        long unverified;
        if (restart != null) {
            fromSeq = restart.getSeq();
            prev = GENESIS;
            from = new VerifiedFrom(restart.getId(), restart.getSeq(), restart.getEventTime(), VerifiedFrom.RESTART);
            unverified = auditLogRepo.countBySeqLessThan(fromSeq);
        } else {
            AuditLog lowest = auditLogRepo.findTopBySeqIsNotNullOrderBySeqAsc().orElse(null);
            if (lowest == null) return new ChainVerification(true, null, null, 0);
            fromSeq = lowest.getSeq();
            prev = lowest.getPrevHash() == null ? GENESIS : lowest.getPrevHash();
            from = GENESIS.equals(prev) ? null
                    : new VerifiedFrom(lowest.getId(), lowest.getSeq(), lowest.getEventTime(), VerifiedFrom.RETENTION);
            unverified = 0L;
        }
        long checked = 0;
        long afterSeq = fromSeq;
        Long seenId = null;    // önceki sayfanın son (denetlenmiş) satırı — sonraki sayfanın başında yeniden gelir
        Long prevSeq = null;   // son denetlenen satırın seq'i / kimliği (çatal denetimi, sayfalar arası da)
        Long prevId = null;
        Long lastSeq = null;
        // Sınırlı: her tam sayfa en az bir YENİ satır denetler (örtüşen tek satır kimliğiyle atlanır; aynı seq'li başka satır
        // çataldır → döner) ya da biter; süre sınırı ayrıca keser.
        while (true) {
            if (verifyClock.getAsLong() - deadline > 0) {
                return new ChainVerification(false, null, null, checked, from, unverified, true, lastSeq);
            }
            List<AuditLog> rows = auditLogRepo.findChainPageFrom(afterSeq);
            if (rows == null || rows.isEmpty()) break;
            int fresh = 0;
            for (AuditLog r : rows) {
                if (seenId != null && seenId.equals(r.getId())) continue;
                if (fresh >= VERIFY_PAGE_SIZE) break;
                if (r.getSeq() == null || r.getId() == null) {   // savunma: anahtarsız satır ilerletilemez
                    return new ChainVerification(false, r.getSeq(), r.getId(), checked, from, unverified);
                }
                if (prevSeq != null && prevSeq.equals(r.getSeq())) {   // aynı seq ikinci kez: çatal
                    return new ChainVerification(false, r.getSeq(), Math.max(prevId, r.getId()), checked, from, unverified);
                }
                String expectHash = sha256(canonical(r) + SEP + prev);
                String storedPrev = r.getPrevHash() == null ? GENESIS : r.getPrevHash();
                if (!prev.equals(storedPrev) || expectHash == null || !expectHash.equals(r.getRowHash())) {
                    return new ChainVerification(false, r.getSeq(), r.getId(), checked, from, unverified);
                }
                prev = r.getRowHash();
                checked++;
                lastSeq = r.getSeq();
                prevSeq = r.getSeq();
                prevId = r.getId();
                fresh++;
            }
            if (fresh == 0) break;
            afterSeq = prevSeq;
            seenId = prevId;
            if (rows.size() < AuditLogRepository.CHAIN_PAGE_FETCH) break;   // getirilenlerin hepsi denetlendi, başka yok
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

    /** AD hesabı devre dışı / dizinde yok — beni-hatırla / kodla giriş reddi (H paketi B4, 2026-11-26). */
    public static final String REASON_AD_ACCOUNT_DISABLED = "AD_ACCOUNT_DISABLED";
    /** AD'ye ulaşılamadı ve hesap son 24 saatte AD ile doğrulanmadı — giriş reddi (H paketi B4). */
    public static final String REASON_AD_UNVERIFIED = "AD_UNVERIFIED";

    /**
     * AD (LDAP) hesabının beni-hatırla ya da kodla girişi AD hesap durumu yüzünden REDDEDİLDİ (H paketi B4) —
     * {@code LOGIN_FAILED}, sonuç {@code BLOCKED}: kaba kuvvet / ilerleyici kilit sayacına GİRMEZ (kimlik doğrulandı; sorun AD
     * hesabının durumu). {@code code}: {@link #REASON_AD_ACCOUNT_DISABLED} ya da {@link #REASON_AD_UNVERIFIED}.
     *
     * @param method {@code REMEMBER_ME}, {@code OTP_PUSH} / {@code OTP_EMAIL}
     */
    public void recordAdAccountRefused(String actor, Long actorId, Long actorTeamId, String actorRole,
                                       String ipAddress, String userAgent, String method, String code) {
        String why = REASON_AD_UNVERIFIED.equals(code)
                ? REASON_AD_UNVERIFIED + ": AD'ye ulaşılamadı ve hesap son 24 saatte AD ile doğrulanmadı — giriş reddedildi"
                : REASON_AD_ACCOUNT_DISABLED + ": AD hesabı devre dışı ya da dizinde yok — giriş reddedildi";
        recordBlockedLogin(actor, actorId, actorTeamId, actorRole, ipAddress, userAgent,
                why + " (" + (method == null ? "?" : method) + ")", method);
        log.info("AD hesap durumu girişi reddetti: actor={} neden={} yol={} IP={}", actor, code, method, ipAddress);
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
