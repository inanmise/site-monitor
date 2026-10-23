package com.sitemonitor.service.evalstate;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.evalstate.EvalStateSql.ChainDelete;
import com.sitemonitor.service.evalstate.EvalStateSql.ChainRow;
import com.sitemonitor.service.evalstate.EvalStateSql.LastStatusRow;
import com.sitemonitor.service.evalstate.EvalStateSql.RecoveryRow;
import com.sitemonitor.service.evalstate.EvalStateSql.TypeRow;
import com.sitemonitor.service.evalstate.EvalStateSource.RecoveryValues;
import com.sitemonitor.service.evalstate.EvalStateSource.TypeValues;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Kalıcı değerlendirme durumu kaydedicisi — İKİLİ YAZIM, davranış DEĞİŞMEZ (2026-10-23, çok pod Sürüm 3-1, paket A1;
 * tasarım R3-1 §2.3 / §2.4). Pod başına bir tane; {@link EvalStateSink} uygular. {@code MonitoringOutageService}
 * (paket A2) her bellek değişiminin yanında bir kuyruğa alma çağrısı yapar; bu sınıf bellekteki DEĞERİN aynasını dört
 * tabloya ({@link EvalStateSql}) yazar. 3-1'de hiçbir karar bu tabloları OKUMAZ (I-1).
 *
 * <p><b>Kuyruğa alma (çağıranın iş parçacığı — tarama / istek / teyit):</b> veritabanı YOK, fırlatma YOK, bekleme yok
 * (I-2). Kapı: kapanış yok, çit dışı değil, kip {@code off} değil ({@code site.monitor.eval-state.mode}, canlı) ve
 * {@code SchedulerService.sweepLeaderHint()} true (takipçinin yerel elle değerlendirmesi yazmaz). İşlem pod'a özgü artan bir
 * {@code seq} alır — kurtarma / tür değeri {@link EvalStateSource}'tan seq ALINDIKTAN SONRA okunur ve anahtar başına
 * EN BÜYÜK seq'li işlem kalır (birleştirme). Değer, son kuyruğa alınan değere eşitse işlem düşer (her turdaki "sağlıklı
 * alan adı sayacını sıfırla" çağrıları sıfır yazım üretir). Son gözlem yalnız durum değişince ya da son yazılan gözlem
 * {@code last-status-refresh-ms}'den (120 sn) eskiyse yazılır. Bekleyen işlem sayısı {@link #MAX_PENDING}'i aşarsa yeni
 * anahtar düşer, sayılır ve "kirli" işaretlenir (doğrulama o anahtarı yeniden yazılana dek atlar).
 *
 * <p><b>Yazıcı:</b> daemon iş parçacığı {@value #THREAD_NAME}, {@code site.monitor.eval-state.flush-ms}'de (1 sn) bir ya
 * da {@code sweepEnd} ipucunda; tarama çağrısı başına pratikte TEK toplu yazım. Her boşaltma çit nabzıyla başlar
 * ({@code '*'} satırı hâlâ bu kuşak + bu yazan mı); 0 satır → daha yeni bir lider kuşak aldı: bekleyenler düşer, bu pod
 * yeniden lider olana (GAINED) dek yazmaz. Kuşak DB sayacından CAS ile alınır (saat değil — saat kayması sıralamayı
 * bozamaz). Hata → sayaç + dakikada bir WARN, işlemler beklemede kalır (birleşik), 1 → 30 sn geri çekilme. Tablolar
 * yoksa (ilk açılışın ilk saniyeleri) sessizce 60 sn'de bir yeniden denenir; bekleyenler korunur (üst sınırla).
 *
 * <p><b>Liderlik olayları</b> (her neden — GAINED / LOST / RELEASED): bellek ({@code resetVolatileState}) ile aynı anda
 * kaydedicinin bekleyenleri, son değerleri ve kuşağı sıfırlanır. GAINED'den sonraki ilk kuşakta önceki kuşakların zincirleri
 * ABANDONED işaretlenir ve devralınan satırlar bir kez sayılır ({@link Status#inherited()}) — 3-2'nin devirde taşıyacağının
 * kanıtı.
 *
 * <p><b>Doğrulama:</b> kip {@code verify} iken yazıcı iş parçacığında {@code verify-ms}'de (5 dk) bir
 * {@link EvalStateVerification} (A2, tembel çözülür) çağrılır; sonuç {@link Status#lastVerify()}'da saklanır.
 *
 * <p><b>Budama:</b> günde bir (kuşak sahibi, yazıcı iş parçacığı, kilitsiz, idempotent): son gözlemi 7 günden eski kalemler,
 * sıfır ve zincirsiz 7 günlük kurtarma satırları, ABANDONED ya da 1 saattir güncellenmeyen zincirler.
 *
 * <p><b>Kapanış:</b> {@code ContextClosedEvent} yalnız bayrak + uyarı (bekleme / DB yok, kapanış bütçesi değişmez);
 * {@code @PreDestroy} iş parçacığını keser ve {@link ShutdownDeadline#waitMs} (tavan {@value #SHUTDOWN_JOIN_CAP_MS} ms) kadar
 * bekler. 3-1'de son saniyenin ayna yazımının kaybı zararsızdır (okuma yok).
 */
@Slf4j
@Component
public class EvalStateRecorder implements EvalStateSink, ApplicationContextAware {

    /** Yazıcı iş parçacığının adı. */
    public static final String THREAD_NAME = "eval-state-writer";
    /** Bekleyen (birleştirilmiş) işlem üst sınırı — aşan YENİ anahtar düşer ve kirli işaretlenir. */
    public static final int MAX_PENDING = 50_000;
    /** Kirli anahtar kümesi üst sınırı — aşılırsa doğrulama tüm anahtarları atlar (liderlik değişene dek). */
    static final int MAX_DIRTY = 100_000;
    /** Tablo başına "son kuyruğa alınan değer" haritası üst sınırı — aşılırsa boşluk (gap) uygulanır. */
    static final int MAX_SEEN = 200_000;
    /** {@code MonitoringOutageService.SIBLING_TTL_MS}'in aynası (paket-özel; test ikisini karşılaştırır). */
    public static final long SIBLING_TTL_MS = 26L * 60 * 60 * 1000;
    /** Boşta bile çit nabzı aralığı — {@code '*'.updated_ms} 3-2'nin "durum güncel mi" sorusunun girdisi. */
    static final long HEARTBEAT_MS = 60_000L;
    static final long TABLE_RECHECK_MS = 60_000L;
    static final long BACKOFF_MIN_MS = 1_000L;
    static final long BACKOFF_MAX_MS = 30_000L;
    static final long WARN_EVERY_MS = 60_000L;
    /** İlk-tur damgası tür başına en çok bu sıklıkla yeniden yazılır (bellek yalnız "GAINED'den beri görüldü" bilir). */
    static final long ROUND_STAMP_THROTTLE_MS = 60_000L;
    /** Tur aralığı bu oranı aşmadan değişirse yazılmaz. */
    static final double INTERVAL_CHANGE_RATIO = 0.10;
    static final long PRUNE_FIRST_DELAY_MS = 10L * 60 * 1000;
    static final long PRUNE_EVERY_MS = 24L * 60 * 60 * 1000;
    static final long LAST_STATUS_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    static final long EVAL_STATE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    static final long CONFIRMATION_RETENTION_MS = 60L * 60 * 1000;
    /** Yok etme anında yazıcının birleşmesi için tavan (ortak kapanış bütçesinden okunur). */
    public static final long SHUTDOWN_JOIN_CAP_MS = 1_000L;
    /** Doğrulayıcı istisna fırlatırsa / null dönerse bilinmeyen raporun nedeni. */
    public static final String REASON_VERIFIER_ERROR = "verifier_error";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    // ── Yapılandırma ────────────────────────────────────────────────────────────────────────────────────────────────

    private final EvalStateSql sql;
    private final String writer;
    private final BooleanSupplier leaderHint;
    private final Supplier<EvalStateMode> modeSupplier;
    private final LongSupplier clock;
    private final Supplier<EvalStateVerification> verifier;
    private final long flushMs;
    private final long verifyMs;
    private final long refreshMs;
    private final int maxPending;

    // ── Kuyruk durumu ───────────────────────────────────────────────────────────────────────────────────────────────

    private volatile EvalStateSource source;
    private final AtomicLong seqGen = new AtomicLong();
    /** Kuyruğa alanlar okuma kilidi (paylaşılan, nanosaniyelik); temizleme ve "güvenli seq" okuması yazma kilidi. */
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    /** Boşaltmalar tek tek (yazıcı + testlerin {@link #flushNow()}'u aynı anda kuşak almasın). Kuyruğa alanlar ALMAZ. */
    private final ReentrantLock flushLock = new ReentrantLock();

    private final Lane recoveryLane = new Lane(EvalStateKeys.Table.EVAL_STATE);
    private final Lane lastStatusLane = new Lane(EvalStateKeys.Table.LAST_STATUS);
    private final Lane typeLane = new Lane(EvalStateKeys.Table.TYPE_STATE);
    private final Lane chainLane = new Lane(EvalStateKeys.Table.CONFIRMATIONS);
    private final List<Lane> lanes = List.of(recoveryLane, lastStatusLane, typeLane, chainLane);

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong flushes = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong unrecordable = new AtomicLong();
    private final AtomicLong leadershipEpoch = new AtomicLong();

    private volatile boolean dirtyOverflow;
    /** > 0: bu seq'e kadar olan durum bir toplu düşüşte (kip off / harita taşması / çit) kaybolmuş olabilir. */
    private volatile long gapSeq;
    private volatile boolean gapRequested;
    private volatile long flushedThrough;
    private final Object flushedMonitor = new Object();

    private volatile Long lastFlushMs;
    private volatile Long lastFailureMs;
    private volatile String lastFailure;
    private volatile boolean tableMissing;

    private volatile long myGen;
    private volatile long genAcquiredMs;
    /** Bu kaydın (liderlik dönemi) başladığı an — {@link EvalStateVerification.Context#generationStartMs()}. */
    private volatile long coverageStartMs;
    private volatile boolean fencedOut;
    private volatile boolean inheritPending = true;
    private volatile Inherited inherited;
    private volatile EvalVerifyReport lastVerify;

    // ── İş parçacığı ────────────────────────────────────────────────────────────────────────────────────────────────

    private volatile Thread worker;
    private volatile boolean stopped;
    private volatile boolean closing;
    private final Object signal = new Object();
    private boolean signalled;
    private ApplicationContext context;

    /** Yalnız yazıcı iş parçacığında (ya da {@link #flushLock} altında) okunur / yazılır. */
    private long nextHeartbeatNs;
    private long backoffUntilNs;
    private long backoffMs;
    private long nextTableCheckNs;
    private long nextVerifyNs;
    private long nextPruneNs;
    private final AtomicLong lastWarnNs = new AtomicLong(Long.MIN_VALUE);

    // ── Kurulum ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Spring: ayarlar ve zamanlayıcı TEMBEL çözülür (MOS → kaydedici → zamanlayıcı → MOS döngüsü kurulmaz). */
    @Autowired
    public EvalStateRecorder(JdbcTemplate jdbc,
                             ObjectProvider<AppSettingsService> settings,
                             ObjectProvider<SchedulerService> scheduler,
                             ObjectProvider<EvalStateVerification> verifiers,
                             @Value("${site.monitor.eval-state.flush-ms:1000}") long flushMs,
                             @Value("${site.monitor.eval-state.verify-ms:300000}") long verifyMs,
                             @Value("${site.monitor.eval-state.last-status-refresh-ms:120000}") long refreshMs) {
        this(builder(jdbc)
                .writer(SchedulerService.instanceId())
                .leaderHint(lazyLeaderHint(scheduler))
                .mode(lazyMode(settings))
                .verifier(lazy(verifiers))
                .flushMs(flushMs)
                .verifyMs(verifyMs)
                .lastStatusRefreshMs(refreshMs));
    }

    private EvalStateRecorder(Builder b) {
        this.sql = new EvalStateSql(b.jdbc);
        this.writer = EvalStateSql.clip(Objects.requireNonNullElse(b.writer, SchedulerService.instanceId()),
                EvalStateSql.WRITER_MAX);
        this.leaderHint = b.leaderHint;
        this.modeSupplier = b.mode;
        this.clock = b.clock;
        this.verifier = b.verifier;
        this.flushMs = Math.max(50L, b.flushMs);
        this.verifyMs = Math.max(1_000L, b.verifyMs);
        this.refreshMs = Math.max(0L, b.lastStatusRefreshMs);
        this.maxPending = Math.max(1, b.maxPending);
        this.coverageStartMs = safeClock();
        // nanoTime negatif olabilir: zamanlar "şimdi"den başlar (0'dan değil)
        long n = System.nanoTime();
        this.nextHeartbeatNs = n;
        this.backoffUntilNs = n;
        this.nextTableCheckNs = n;
        this.nextVerifyNs = n + TimeUnit.MILLISECONDS.toNanos(this.verifyMs);
        this.nextPruneNs = n + TimeUnit.MILLISECONDS.toNanos(PRUNE_FIRST_DELAY_MS);
    }

    /**
     * Elle kurulum (testler, çok pod düzenekleri): her pod kendi yazan kimliği, lider ipucu ve saatiyle. Varsayılanlar:
     * yazan = {@code SchedulerService.instanceId()}, lider ipucu = true, kip = {@link EvalStateMode#DEFAULT}, saat = sistem,
     * doğrulayıcı yok. İş parçacığı başlatılmaz — {@link #start()} ya da {@link #flushNow()}.
     */
    public static Builder builder(JdbcTemplate jdbc) {
        return new Builder(jdbc);
    }

    /** {@link #builder(JdbcTemplate)} için akıcı yapılandırıcı. */
    public static final class Builder {
        private final JdbcTemplate jdbc;
        private String writer;
        private BooleanSupplier leaderHint = () -> true;
        private Supplier<EvalStateMode> mode = () -> EvalStateMode.DEFAULT;
        private LongSupplier clock = System::currentTimeMillis;
        private Supplier<EvalStateVerification> verifier = () -> null;
        private long flushMs = 1_000L;
        private long verifyMs = 300_000L;
        private long lastStatusRefreshMs = 120_000L;
        private int maxPending = MAX_PENDING;

        private Builder(JdbcTemplate jdbc) {
            this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        }

        public Builder writer(String writer) { this.writer = writer; return this; }
        public Builder leaderHint(BooleanSupplier hint) { this.leaderHint = Objects.requireNonNull(hint); return this; }
        public Builder mode(Supplier<EvalStateMode> mode) { this.mode = Objects.requireNonNull(mode); return this; }
        public Builder clock(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); return this; }
        public Builder verifier(Supplier<EvalStateVerification> v) { this.verifier = Objects.requireNonNull(v); return this; }
        public Builder flushMs(long ms) { this.flushMs = ms; return this; }
        public Builder verifyMs(long ms) { this.verifyMs = ms; return this; }
        public Builder lastStatusRefreshMs(long ms) { this.lastStatusRefreshMs = ms; return this; }
        public Builder maxPending(int n) { this.maxPending = n; return this; }

        public EvalStateRecorder build() {
            return new EvalStateRecorder(this);
        }
    }

    private static BooleanSupplier lazyLeaderHint(ObjectProvider<SchedulerService> provider) {
        AtomicReference<SchedulerService> ref = new AtomicReference<>();
        return () -> {
            SchedulerService s = ref.get();
            if (s == null) {
                try {
                    s = provider.getIfAvailable();
                } catch (BeansException e) {
                    return false;   // henüz oluşturuluyor — yazma yok (bugünkü davranış)
                }
                if (s == null) return false;
                ref.set(s);
            }
            return s.sweepLeaderHint();
        };
    }

    private static Supplier<EvalStateMode> lazyMode(ObjectProvider<AppSettingsService> provider) {
        AtomicReference<AppSettingsService> ref = new AtomicReference<>();
        return () -> {
            AppSettingsService s = ref.get();
            if (s == null) {
                try {
                    s = provider.getIfAvailable();
                } catch (BeansException e) {
                    return EvalStateMode.DEFAULT;
                }
                if (s == null) return EvalStateMode.DEFAULT;
                ref.set(s);
            }
            return EvalStateMode.parse(s.getString(EvalStateMode.SETTING_KEY, null));
        };
    }

    private static <T> Supplier<T> lazy(ObjectProvider<T> provider) {
        return () -> {
            try {
                return provider.getIfAvailable();
            } catch (BeansException e) {
                return null;
            }
        };
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = applicationContext;
    }

    // ── Kuyruğa alma (EvalStateSink) ────────────────────────────────────────────────────────────────────────────────

    @Override
    public void bindSource(EvalStateSource source) {
        this.source = source;
    }

    @Override
    public void recovery(String rkey, String alertType, String domain) {
        try {
            if (rkey == null || !gate()) return;
            EvalStateSource src = source;
            if (src == null) return;
            if (!recordable(alertType, domain)) {
                markUnrecordable(recoveryLane, rkey);
                return;
            }
            rw.readLock().lock();
            try {
                recoveryLane.seen.compute(rkey, (k, old) -> {
                    long s = seqGen.incrementAndGet();
                    RecoveryValues v = recoveryOf(src, k);
                    RecoveryValues last = old == null ? RecoveryValues.NONE : (RecoveryValues) old.value();
                    if (v.equals(last)) return noOp(recoveryLane, k, s, last);
                    RecoveryRow row = new RecoveryRow(alertType, domain, v.upCount(), v.lastCountedMs(), v.chainGen(),
                            v.chainStartedMs(), s);
                    return offer(recoveryLane, k, s, row) ? new Seen(s, v) : old;
                });
            } finally {
                rw.readLock().unlock();
            }
            trimSeen(recoveryLane);
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: kurtarma kaydı kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void observation(String alertType, SweepItem item, boolean manual, long atMs) {
        try {
            if (item == null || alertType == null || !gate()) return;
            EvalStateSource src = source;
            if (src == null) return;
            String itemKey = EvalStateKeys.itemKey(item);
            String key = EvalStateKeys.lastStatusKey(alertType, itemKey);
            String domain = item.domain();
            if (!recordable(alertType, domain) || itemKey.length() > EvalStateSql.ITEM_KEY_MAX) {
                markUnrecordable(lastStatusLane, key);
                return;
            }
            boolean up = item.up();
            String cls = up ? null : EvalStateSql.clip(errorClassOf(src, item.error(), domain), EvalStateSql.ERROR_CLASS_MAX);
            boolean networkDown = !up && (EvalStateSource.ERROR_NETWORK.equals(cls) || EvalStateSource.ERROR_DNS.equals(cls));
            Long monitorId = ctxLong(item, "monitor_id");
            Long teamId = ctxLong(item, "team_id");
            Boolean standalone = ctxBool(item, "standalone");
            String detail = EvalStateSql.clip(item.detail(), EvalStateSql.DETAIL_MAX);
            String src0 = manual ? EvalStateSql.SOURCE_MANUAL : EvalStateSql.SOURCE_SCHEDULED;
            rw.readLock().lock();
            try {
                lastStatusLane.seen.compute(key, (k, old) -> {
                    long s = seqGen.incrementAndGet();
                    LastSeen prev = old == null ? null : (LastSeen) old.value();
                    boolean same = prev != null && prev.sameState(up, networkDown, cls, monitorId, teamId, standalone);
                    if (same && atMs - prev.observedMs() < refreshMs) return noOp(lastStatusLane, k, s, prev);
                    long changed = prev == null || prev.up() != up || prev.networkDown() != networkDown ? atMs : prev.changedMs();
                    LastStatusRow row = new LastStatusRow(alertType, itemKey, domain, detail, monitorId, teamId, standalone,
                            up, networkDown, cls, src0, atMs, changed, s);
                    return offer(lastStatusLane, k, s, row)
                            ? new Seen(s, new LastSeen(up, networkDown, cls, monitorId, teamId, standalone, atMs, changed))
                            : old;
                });
            } finally {
                rw.readLock().unlock();
            }
            trimSeen(lastStatusLane);
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: gözlem kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void typeState(String alertType) {
        try {
            if (alertType == null || EvalStateKeys.GENERATION_ROW.equals(alertType) || !gate()) return;
            EvalStateSource src = source;
            if (src == null) return;
            if (alertType.length() > EvalStateSql.ALERT_TYPE_MAX) {
                markUnrecordable(typeLane, alertType);
                return;
            }
            rw.readLock().lock();
            try {
                typeLane.seen.compute(alertType, (k, old) -> {
                    long s = seqGen.incrementAndGet();
                    TypeValues tv = typeOf(src, k);
                    long now = safeClock();
                    TypeSeen prev = old == null ? TypeSeen.NONE : (TypeSeen) old.value();
                    TypeSeen next = prev.next(tv, now);
                    if (next.equals(prev)) return noOp(typeLane, k, s, prev);
                    TypeRow row = new TypeRow(k, next.active(), next.sinceMs(), next.roundMs(), next.intervalMs(), s);
                    return offer(typeLane, k, s, row) ? new Seen(s, next) : old;
                });
            } finally {
                rw.readLock().unlock();
            }
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: tür durumu kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void chainStart(String confirmKey, long chainId, SweepItem item, ConfirmState state, String firstFailureAt,
                           long delayMs) {
        try {
            if (confirmKey == null || item == null || !gate()) return;
            String alertType = item.alertType();
            String domain = item.domain();
            if (!recordable(alertType, domain)) {
                markUnrecordable(chainLane, confirmKey);
                return;
            }
            long now = safeClock();
            String ctxJson = null;
            boolean truncated = false;
            Map<String, Object> ctx = item.ctxExtra();
            if (ctx != null && !ctx.isEmpty()) {
                ctxJson = jsonOrNull(ctx);
                if (ctxJson == null) truncated = true;
            }
            String first = firstFailureAt != null ? firstFailureAt : state != null ? state.startedAt() : null;
            if (first == null) first = EvalStateSql.iso(now);
            int attempt = state == null ? 0 : state.attempt();
            int total = state == null ? 0 : state.totalAttempts();
            Long next = state == null || state.nextAttemptAtMs() <= 0 ? null : state.nextAttemptAtMs();
            String keyHash = EvalStateKeys.keyHash(confirmKey);
            String monitorType = EvalStateSql.clip(RecheckRefs.monitorType(alertType), EvalStateSql.MONITOR_TYPE_MAX);
            Long monitorId = ctxLong(item, "monitor_id");
            String detail = EvalStateSql.clip(item.detail(), EvalStateSql.DETAIL_MAX);
            String firstAt = EvalStateSql.clip(first, EvalStateSql.ISO_MAX);
            String json = ctxJson;
            boolean trunc = truncated;
            rw.readLock().lock();
            try {
                chainLane.seen.compute(confirmKey, (k, old) -> {
                    long s = seqGen.incrementAndGet();
                    ChainRow row = new ChainRow(keyHash, k, chainId, alertType, domain, detail, monitorType, monitorId,
                            attempt, total, Math.max(0L, delayMs), firstAt, next, null, json, trunc,
                            EvalStateSql.STATE_RUNNING, now, s);
                    return offer(chainLane, k, s, row) ? new Seen(s, row) : old;
                });
            } finally {
                rw.readLock().unlock();
            }
            trimSeen(chainLane);
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: zincir başlangıcı kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void chainProgress(String confirmKey, long chainId, ConfirmState state, List<Map<String, Object>> attempts) {
        try {
            if (confirmKey == null || !gate()) return;
            String attemptsJson = attempts == null ? null : jsonOrNull(attempts);
            int attempt = state == null ? -1 : state.attempt();
            int total = state == null ? -1 : state.totalAttempts();
            Long next = state == null || state.nextAttemptAtMs() <= 0 ? null : state.nextAttemptAtMs();
            rw.readLock().lock();
            try {
                chainLane.seen.compute(confirmKey, (k, old) -> {
                    if (old == null || !(old.value() instanceof ChainRow r) || r.chainId() != chainId) {
                        if (old == null) markDirty(chainLane, k);   // başlangıcı görülmemiş zincir: doğrulama atlasın
                        return old;
                    }
                    long s = seqGen.incrementAndGet();
                    ChainRow nextRow = r.progressed(attempt < 0 ? r.attempt() : attempt, total < 0 ? r.totalAttempts() : total,
                            state == null ? r.nextAttemptMs() : next, attempts == null ? r.attemptsJson() : attemptsJson,
                            EvalStateSql.STATE_RUNNING, s);
                    return offer(chainLane, k, s, nextRow) ? new Seen(s, nextRow) : old;
                });
            } finally {
                rw.readLock().unlock();
            }
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: zincir ilerlemesi kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void chainFinishing(String confirmKey, long chainId, int lockTry) {
        try {
            if (confirmKey == null || !gate()) return;
            rw.readLock().lock();
            try {
                chainLane.seen.compute(confirmKey, (k, old) -> {
                    if (old == null || !(old.value() instanceof ChainRow r) || r.chainId() != chainId) {
                        if (old == null) markDirty(chainLane, k);
                        return old;
                    }
                    long s = seqGen.incrementAndGet();
                    ChainRow nextRow = r.withState(EvalStateSql.STATE_FINISHING, s);
                    return offer(chainLane, k, s, nextRow) ? new Seen(s, nextRow) : old;
                });
            } finally {
                rw.readLock().unlock();
            }
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: zincir son adımı kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void chainEnd(String confirmKey, long chainId) {
        try {
            if (confirmKey == null || !gate()) return;
            String keyHash = EvalStateKeys.keyHash(confirmKey);
            rw.readLock().lock();
            try {
                chainLane.seen.compute(confirmKey, (k, old) -> {
                    // Aynı anahtarda daha yeni bir zincir başladıysa eski zincirin geç bitişi onun satırına dokunmaz.
                    if (old != null && old.value() instanceof ChainRow r && r.chainId() != chainId) return old;
                    long s = seqGen.incrementAndGet();
                    ChainDelete d = new ChainDelete(keyHash, chainId, s);
                    return offer(chainLane, k, s, d) ? new Seen(s, d) : old;
                });
            } finally {
                rw.readLock().unlock();
            }
            trimSeen(chainLane);
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: zincir bitişi kuyruğa alınamadı (karar etkilenmez): {}", e.toString());
        }
    }

    @Override
    public void sweepEnd(String alertType) {
        try {
            if (pendingSize() > 0) signal();
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu: boşaltma ipucu verilemedi: {}", e.toString());
        }
    }

    // ── Kuyruk yardımcıları ─────────────────────────────────────────────────────────────────────────────────────────

    /** Kuyruğa alma kapısı — yalnız bellek / uçucu okuma (VERİTABANI YOK). */
    private boolean gate() {
        if (closing || stopped || fencedOut) return false;
        if (!mode().writes()) {
            if (!gapRequested && hasAnyState()) gapRequested = true;   // kip kapandı: yazıcı bir kez temizler
            return false;
        }
        return leaderHint();
    }

    private boolean recordable(String alertType, String domain) {
        return alertType != null && !alertType.isEmpty() && alertType.length() <= EvalStateSql.ALERT_TYPE_MAX
                && !EvalStateKeys.GENERATION_ROW.equals(alertType)
                && domain != null && domain.length() <= EvalStateSql.DOMAIN_MAX;
    }

    private void markUnrecordable(Lane lane, String key) {
        unrecordable.incrementAndGet();
        if (key != null) markDirty(lane, key);
    }

    /** İşlemi birleştirerek bekletir (anahtar başına en büyük seq). Üst sınırda YENİ anahtar düşer + kirli. */
    private boolean offer(Lane lane, String key, long seq, Object payload) {
        Op op = new Op(seq, payload);
        if (!lane.pending.containsKey(key) && pendingSize() >= maxPending) {
            dropped.incrementAndGet();
            markDirty(lane, key);
            return false;
        }
        lane.pending.merge(key, op, (cur, neu) -> neu.seq() >= cur.seq() ? neu : cur);
        lane.dirty.remove(key);
        return true;
    }

    /** Değer değişmedi: seq kaydedilir (doğrulama penceresi), kirli işaret kalkar (bellek = son kuyruğa alınan = tablo). */
    private Seen noOp(Lane lane, String key, long seq, Object value) {
        lane.dirty.remove(key);
        return new Seen(seq, value);
    }

    private void markDirty(Lane lane, String key) {
        if (dirtyOverflow) return;
        if (dirtySize() >= MAX_DIRTY) {
            dirtyOverflow = true;
            return;
        }
        lane.dirty.add(key);
    }

    private void trimSeen(Lane lane) {
        if (lane.seen.size() > MAX_SEEN && !gapRequested) gapRequested = true;
    }

    private long pendingSize() {
        long n = 0;
        for (Lane l : lanes) n += l.pending.mappingCount();
        return n;
    }

    private long dirtySize() {
        long n = 0;
        for (Lane l : lanes) n += l.dirty.size();
        return n;
    }

    private boolean hasAnyState() {
        for (Lane l : lanes) {
            if (!l.pending.isEmpty() || !l.seen.isEmpty() || !l.dirty.isEmpty()) return true;
        }
        return false;
    }

    private EvalStateMode mode() {
        try {
            EvalStateMode m = modeSupplier.get();
            return m == null ? EvalStateMode.DEFAULT : m;
        } catch (RuntimeException e) {
            return EvalStateMode.DEFAULT;
        }
    }

    private boolean leaderHint() {
        try {
            return leaderHint.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private long safeClock() {
        try {
            return clock.getAsLong();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    private static RecoveryValues recoveryOf(EvalStateSource src, String rkey) {
        try {
            RecoveryValues v = src.recoveryValues(rkey);
            return v == null ? RecoveryValues.NONE : v;
        } catch (RuntimeException e) {
            return RecoveryValues.NONE;
        }
    }

    private static TypeValues typeOf(EvalStateSource src, String type) {
        try {
            TypeValues v = src.typeValues(type);
            return v == null ? TypeValues.NONE : v;
        } catch (RuntimeException e) {
            return TypeValues.NONE;
        }
    }

    private static String errorClassOf(EvalStateSource src, String error, String domain) {
        try {
            return src.errorClass(error, domain);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long ctxLong(SweepItem item, String key) {
        Object v = item.ctxExtra() == null ? null : item.ctxExtra().get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean ctxBool(SweepItem item, String key) {
        Object v = item.ctxExtra() == null ? null : item.ctxExtra().get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return null;
    }

    /** JSON (üst sınır {@link EvalStateSql#JSON_MAX_BYTES}); aşan ya da yazılamayan değer null. Değer asla günlüğe yazılmaz. */
    static String jsonOrNull(Object value) {
        String s;
        try {
            s = JSON.writeValueAsString(value);
        } catch (RuntimeException e) {
            try {
                s = value instanceof Map<?, ?> m ? ManualEvalInbox.toJson(castMap(m)) : null;
            } catch (RuntimeException e2) {
                return null;
            }
        }
        if (s == null) return null;
        if (s.length() > EvalStateSql.JSON_MAX_BYTES / 4 && s.getBytes(StandardCharsets.UTF_8).length > EvalStateSql.JSON_MAX_BYTES) {
            return null;
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    // ── Liderlik olayları ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Her geçişte (GAINED / LOST / RELEASED) bekleyenler, son değerler ve kuşak sıfırlanır — bellek de aynı olayla
     * sıfırlanır ("liderlik değişimi = yeniden başlatma"). GAINED'de çit kalkar, ilk kuşakta devralma sayılır. Hızlı,
     * veritabanısız (olay liderlik kilidi tutulurken eşzamanlı dağıtılır).
     */
    @EventListener
    public void onSweepLeadershipChanged(SweepLeadershipChangedEvent event) {
        if (event == null) return;
        rw.writeLock().lock();
        try {
            leadershipEpoch.incrementAndGet();
            clearLanes();
            gapSeq = 0L;
            gapRequested = false;
            dirtyOverflow = false;
            myGen = 0L;
            coverageStartMs = safeClock();
            if (SweepLeadershipChangedEvent.GAINED.equals(event.reason())) {
                fencedOut = false;
                inheritPending = true;
                inherited = null;
            }
        } finally {
            rw.writeLock().unlock();
        }
        markFlushed(seqGen.get());
        signal();
    }

    /** Yazma kilidi altında çağrılır. */
    private void clearLanes() {
        for (Lane l : lanes) {
            l.pending.clear();
            l.seen.clear();
            l.dirty.clear();
        }
    }

    /** Toplu düşüş (kip off, harita taşması, çit): durum temizlenir, bu ana kadarki anahtarlar doğrulamada atlanır. */
    private void applyGap() {
        long through;
        rw.writeLock().lock();
        try {
            gapRequested = false;
            clearLanes();
            dirtyOverflow = false;
            through = seqGen.get();
            gapSeq = through;
        } finally {
            rw.writeLock().unlock();
        }
        markFlushed(through);
    }

    // ── Yazıcı ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady(ApplicationReadyEvent event) {
        if (context != null && event != null && event.getApplicationContext() != context) return;
        start();
    }

    /** Yazıcı iş parçacığını başlatır (idempotent; durdurulduysa / kapanıyorsa başlamaz). */
    public synchronized void start() {
        if (stopped || closing || (worker != null && worker.isAlive())) return;
        Thread t = new Thread(this::runLoop, THREAD_NAME);
        t.setDaemon(true);
        worker = t;
        t.start();
        log.info("Kalıcı değerlendirme durumu kaydedicisi çalışıyor (kip {}, boşaltma {} ms, doğrulama {} ms)",
                mode().key(), flushMs, verifyMs);
    }

    private void runLoop() {
        while (!stopped) {
            try {
                synchronized (signal) {
                    if (!signalled && !stopped) signal.wait(flushMs);
                    signalled = false;
                }
                if (stopped) break;
                tick();
            } catch (InterruptedException e) {
                if (stopped) break;
            } catch (Throwable t) {
                warnRateLimited("Kalıcı değerlendirme durumu yazıcısı hata verdi — karar etkilenmez: {}", shortText(t));
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException ie) {
                    if (stopped) break;
                }
            }
        }
    }

    private void signal() {
        synchronized (signal) {
            signalled = true;
            signal.notifyAll();
        }
    }

    /** Yazıcı turunun tamamı (yazıcı iş parçacığında). */
    void tick() {
        EvalStateMode m = mode();
        if (gapRequested || (!m.writes() && hasAnyState())) applyGap();
        if (!m.writes() || closing) return;
        if (!leaderHint() || fencedOut) return;
        long nowNs = System.nanoTime();
        if (tableMissing) {
            if (nowNs - nextTableCheckNs < 0) return;
            if (!tablesPresent()) {
                nextTableCheckNs = nowNs + TimeUnit.MILLISECONDS.toNanos(TABLE_RECHECK_MS);
                return;
            }
            tableMissing = false;
            log.info("Kalıcı değerlendirme durumu tabloları hazır — yazım başlıyor");
        }
        if (nowNs - backoffUntilNs < 0) return;
        boolean due = pendingSize() > 0 || myGen == 0 || nowNs - nextHeartbeatNs >= 0;
        if (due) flushOnce();
        if (myGen > 0 && m.verifies() && System.nanoTime() - nextVerifyNs >= 0) runVerify();
        if (myGen > 0 && System.nanoTime() - nextPruneNs >= 0) prune();
    }

    /**
     * Elle boşaltma (testler / elle kurulum) — çağıranın iş parçacığında tek boşaltma; yazıcı iş parçacığıyla aynı anda
     * koşmaz. Kip / lider / tablo kapıları geçerlidir. @return yazım (ve çit nabzı) başarılı olduysa true.
     */
    public boolean flushNow() {
        EvalStateMode m = mode();
        if (gapRequested || (!m.writes() && hasAnyState())) applyGap();
        if (!m.writes() || !leaderHint() || fencedOut) return false;
        if (tableMissing && !tablesPresent()) return false;
        tableMissing = false;
        return flushOnce();
    }

    private boolean tablesPresent() {
        try {
            return sql.tablesExist();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tek boşaltma: kuşak (gerekirse) → çit nabzı → güvenli seq → dört tablo. */
    boolean flushOnce() {
        flushLock.lock();
        try {
            return flushLocked();
        } finally {
            flushLock.unlock();
        }
    }

    private boolean flushLocked() {
        long epoch = leadershipEpoch.get();
        long now = safeClock();
        try {
            if (myGen == 0 && !acquire(now, epoch)) return false;
            long gen = myGen;
            if (gen == 0) return false;
            if (!sql.heartbeat(gen, writer, now)) {
                fenced(gen);
                return false;
            }
            nextHeartbeatNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MS);
            long safe;
            rw.writeLock().lock();
            try {
                if (epoch != leadershipEpoch.get()) return false;
                safe = seqGen.get();
            } finally {
                rw.writeLock().unlock();
            }
            List<Map.Entry<String, Op>> rec = snapshot(recoveryLane);
            List<Map.Entry<String, Op>> last = snapshot(lastStatusLane);
            List<Map.Entry<String, Op>> types = snapshot(typeLane);
            List<Map.Entry<String, Op>> chains = snapshot(chainLane);
            sql.writeRecovery(payloads(rec, RecoveryRow.class), writer, gen, now);
            sql.writeLastStatus(payloads(last, LastStatusRow.class), writer, gen, now);
            sql.writeTypeState(payloads(types, TypeRow.class), writer, gen, now);
            sql.writeChains(payloads(chains, ChainRow.class), writer, gen, now);
            sql.deleteChains(payloads(chains, ChainDelete.class), writer, gen);
            if (epoch != leadershipEpoch.get()) return false;   // olay araya girdi: temizlenmiş kuyruğa dokunma
            removeWritten(recoveryLane, rec);
            removeWritten(lastStatusLane, last);
            removeWritten(typeLane, types);
            removeWritten(chainLane, chains);
            markFlushed(safe);
            flushes.incrementAndGet();
            lastFlushMs = now;
            backoffMs = 0L;
            return true;
        } catch (BadSqlGrammarException e) {
            if (!tablesPresent()) {
                if (!tableMissing) log.debug("Kalıcı değerlendirme durumu tabloları henüz yok — 60 sn'de bir yeniden denenecek");
                tableMissing = true;
                nextTableCheckNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TABLE_RECHECK_MS);
                return false;
            }
            failure(e, now);
            return false;
        } catch (RuntimeException e) {
            failure(e, now);
            return false;
        }
    }

    private boolean acquire(long now, long epoch) {
        long g = sql.acquireGeneration(writer, now);
        if (g <= 0) {
            backoff();
            return false;
        }
        if (epoch != leadershipEpoch.get()) return false;   // olay araya girdi: bu kuşak kullanılmaz (zararsız boşluk)
        myGen = g;
        genAcquiredMs = now;
        long nowNs = System.nanoTime();
        nextHeartbeatNs = nowNs;
        nextVerifyNs = nowNs + TimeUnit.MILLISECONDS.toNanos(verifyMs);
        nextPruneNs = nowNs + TimeUnit.MILLISECONDS.toNanos(PRUNE_FIRST_DELAY_MS);
        if (inheritPending) {
            inheritPending = false;
            countInherited(g, now);
        } else {
            log.info("Kalıcı değerlendirme durumu: kuşak {} alındı (yazan {})", g, writer);
        }
        return true;
    }

    /** GAINED'den sonraki ilk kuşak: önceki kuşakların zincirleri ABANDONED + devralınan satırlar bir kez sayılır. */
    private void countInherited(long g, long now) {
        try {
            int abandoned = sql.markAbandoned(g, now);
            Inherited inh = new Inherited(now, sql.previousGeneration(g), sql.countInheritedRecovery(g),
                    sql.countInheritedSiblings(g, now - SIBLING_TTL_MS), sql.countInheritedSuppressions(g), abandoned);
            inherited = inh;
            log.info("Kalıcı değerlendirme durumu: kuşak {} alındı (yazan {}) — devralınan (karara girmez): önceki kuşak {}, "
                    + "kurtarma sayacı {}, DOWN kardeş {}, bastırma {}, terk edilen zincir {}", g, writer,
                    inh.previousGeneration(), inh.recoveryCounters(), inh.downSiblings(), inh.activeSuppressions(),
                    inh.abandonedChains());
        } catch (RuntimeException e) {
            inherited = null;
            log.info("Kalıcı değerlendirme durumu: kuşak {} alındı (yazan {}); devralınan satırlar sayılamadı: {}", g, writer,
                    shortText(e));
        }
    }

    /** Çit nabzı 0 satır: daha yeni bir lider kuşak aldı — bu pod yeniden lider olana dek yazmaz. */
    private void fenced(long gen) {
        long through;
        rw.writeLock().lock();
        try {
            if (myGen != gen) return;
            myGen = 0L;
            fencedOut = true;
            clearLanes();
            through = seqGen.get();
            gapSeq = through;
        } finally {
            rw.writeLock().unlock();
        }
        markFlushed(through);
        log.info("Kalıcı değerlendirme durumu: daha yeni bir lider kuşak aldı (bu pod'un kuşağı {}) — bu pod yeniden lider "
                + "olana dek yazmıyor; karar etkilenmez", gen);
    }

    private void failure(RuntimeException e, long now) {
        writeFailures.incrementAndGet();
        lastFailureMs = now;
        lastFailure = e.getClass().getSimpleName();
        backoff();
        warnRateLimited("Kalıcı değerlendirme durumu yazılamadı — karar etkilenmez (bellek kaynak): {}", shortText(e));
    }

    private void backoff() {
        backoffMs = backoffMs == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoffMs * 2);
        backoffUntilNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs);
    }

    private void warnRateLimited(String fmt, String text) {
        long now = System.nanoTime();
        long last = lastWarnNs.get();
        if (last != Long.MIN_VALUE && now - last < TimeUnit.MILLISECONDS.toNanos(WARN_EVERY_MS)) {
            log.debug(fmt, text);
            return;
        }
        if (lastWarnNs.compareAndSet(last, now)) log.warn(fmt, text);
        else log.debug(fmt, text);
    }

    /** İstisnanın sınıfı + kök nedenin kısa iletisi (değer / bağlam metni değil; en çok 200 karakter). */
    private static String shortText(Throwable t) {
        Throwable root = t;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg != null && msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return t.getClass().getSimpleName() + (root != t ? " / " + root.getClass().getSimpleName() : "")
                + (msg == null ? "" : ": " + msg);
    }

    private static List<Map.Entry<String, Op>> snapshot(Lane lane) {
        List<Map.Entry<String, Op>> out = new ArrayList<>(lane.pending.size());
        for (Map.Entry<String, Op> e : lane.pending.entrySet()) out.add(Map.entry(e.getKey(), e.getValue()));
        return out;
    }

    private static <T> List<T> payloads(List<Map.Entry<String, Op>> entries, Class<T> type) {
        List<T> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, Op> e : entries) {
            if (type.isInstance(e.getValue().payload())) out.add(type.cast(e.getValue().payload()));
        }
        return out;
    }

    private static void removeWritten(Lane lane, List<Map.Entry<String, Op>> written) {
        for (Map.Entry<String, Op> e : written) lane.pending.remove(e.getKey(), e.getValue());
    }

    private void markFlushed(long through) {
        synchronized (flushedMonitor) {
            if (through > flushedThrough) flushedThrough = through;
            flushedMonitor.notifyAll();
        }
    }

    // ── Doğrulama ve budama ─────────────────────────────────────────────────────────────────────────────────────────

    private void runVerify() {
        nextVerifyNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(verifyMs);
        verifyRound();
    }

    /**
     * Bir doğrulama turunu çağıranın iş parçacığında koşar (testler; üretimde yazıcı iş parçacığı). Kuşak yoksa
     * {@link EvalVerifyReport#REASON_NO_GENERATION}; doğrulayıcı yoksa null (rapor saklanmaz).
     */
    public EvalVerifyReport verifyNow() {
        return verifyRound();
    }

    private EvalVerifyReport verifyRound() {
        EvalStateVerification v;
        try {
            v = verifier.get();
        } catch (RuntimeException e) {
            v = null;
        }
        if (v == null) return null;
        long gen = myGen;
        EvalVerifyReport r;
        if (gen <= 0) {
            r = EvalVerifyReport.unknown(safeClock(), writer, 0L, EvalVerifyReport.REASON_NO_GENERATION);
        } else {
            try {
                r = v.verify(new Ctx(gen));
            } catch (RuntimeException e) {
                log.debug("Kalıcı değerlendirme doğrulaması hata verdi (karar etkilenmez): {}", shortText(e));
                r = null;
            }
            if (r == null) r = EvalVerifyReport.unknown(safeClock(), writer, gen, REASON_VERIFIER_ERROR);
        }
        lastVerify = r;
        return r;
    }

    private void prune() {
        nextPruneNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PRUNE_EVERY_MS);
        long now = safeClock();
        try {
            int[] n = sql.prune(now - LAST_STATUS_RETENTION_MS, now - EVAL_STATE_RETENTION_MS, now - CONFIRMATION_RETENTION_MS);
            if (n[0] + n[1] + n[2] > 0) {
                log.info("Kalıcı değerlendirme durumu budandı: son durum {}, kurtarma {}, zincir {} satır", n[0], n[1], n[2]);
            }
        } catch (RuntimeException e) {
            log.debug("Kalıcı değerlendirme durumu budanamadı (yarın yeniden): {}", shortText(e));
        }
    }

    /** Doğrulayıcıya açılan yüz — yalnız kuşak sahibiyken. */
    private final class Ctx implements EvalStateVerification.Context {
        private final long gen;

        Ctx(long gen) {
            this.gen = gen;
        }

        @Override public String writer() { return writer; }
        @Override public long generation() { return gen; }
        @Override public long generationStartMs() { return coverageStartMs; }
        @Override public long nowMs() { return safeClock(); }
        @Override public long seq() { return seqGen.get(); }
        @Override public long lastStatusRefreshMs() { return refreshMs; }

        @Override
        public boolean awaitFlushed(long seq, long timeoutMs) {
            return EvalStateRecorder.this.awaitFlushed(seq, timeoutMs);
        }

        @Override
        public boolean changedSince(EvalStateKeys.Table table, String key, long seq) {
            return EvalStateRecorder.this.changedSince(table, key, seq);
        }
    }

    /**
     * {@code seq}'e kadar her işlem yazıldı (ya da düştü / temizlendi) mi — en çok {@code timeoutMs}. Yazıcı iş
     * parçacığında (ya da yazıcı yokken) boşaltmayı KENDİSİ yapar; başka iş parçacığında yazıcıyı uyarır ve bekler.
     */
    public boolean awaitFlushed(long seq, long timeoutMs) {
        if (flushedThrough >= seq) return true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        Thread w = worker;
        if (w == null || !w.isAlive() || Thread.currentThread() == w) {
            while (flushedThrough < seq) {
                if (System.nanoTime() - deadline > 0) return false;
                if (!flushOnce()) return flushedThrough >= seq;
            }
            return true;
        }
        synchronized (flushedMonitor) {
            while (flushedThrough < seq) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                signal();
                try {
                    flushedMonitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Anahtar {@code seq}'ten SONRA kuyruğa alındı mı, kirli mi, ya da son toplu düşüşten (kip off / çit / taşma) beri hiç
     * kuyruğa alınmadı mı — true ise doğrulama karşılaştırmaz. Anahtar biçimleri {@link EvalStateKeys.Table}'daki gibi.
     */
    public boolean changedSince(EvalStateKeys.Table table, String key, long seq) {
        Lane lane = lane(table);
        if (lane == null || key == null) return true;
        if (dirtyOverflow || lane.dirty.contains(key)) return true;
        Seen sn = lane.seen.get(key);
        long gap = gapSeq;
        if (sn == null) return gap > 0;
        if (sn.seq() > seq) return true;
        return gap > 0 && sn.seq() <= gap;
    }

    private Lane lane(EvalStateKeys.Table table) {
        if (table == null) return null;
        return switch (table) {
            case EVAL_STATE -> recoveryLane;
            case LAST_STATUS -> lastStatusLane;
            case TYPE_STATE -> typeLane;
            case CONFIRMATIONS -> chainLane;
        };
    }

    // ── Durum ve erişim ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Status status() {
        try {
            EvalStateMode m = mode();
            long gen = myGen;
            boolean active = m.writes() && !closing && !fencedOut && !tableMissing && gen > 0 && leaderHint();
            return new Status(m.key(), active, writer, gen > 0 ? gen : null, pendingSize(), dropped.get(), flushes.get(),
                    writeFailures.get(), lastFlushMs, lastFailureMs, lastFailure, tableMissing, inherited, lastVerify);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Son ayrılan işlem sırası. */
    public long seq() {
        return seqGen.get();
    }

    /** Yazıldığı (ya da düştüğü / temizlendiği) bilinen en büyük seq. */
    public long flushedSeq() {
        return flushedThrough;
    }

    /** Tutulan kuşak (0 = yok). */
    public long generation() {
        return myGen;
    }

    /** Bu pod'un yazan kimliği ({@code writer} sütunu). */
    public String writer() {
        return writer;
    }

    /** SQL katmanı (testler / doğrulayıcı okuyucuları). */
    public EvalStateSql sql() {
        return sql;
    }

    /** Kuşak sahibiyken doğrulayıcıya verilen yüz; kuşak yoksa null. */
    public EvalStateVerification.Context verificationContext() {
        long gen = myGen;
        return gen > 0 ? new Ctx(gen) : null;
    }

    /** Yazıcı iş parçacığı (testler; başlatılmadıysa null). */
    Thread worker() {
        return worker;
    }

    /** Kaydedilemeyen (alan adı / anahtar çok uzun) işlem sayısı. */
    public long unrecordableCount() {
        return unrecordable.get();
    }

    // ── Kapanış ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Kapanış başladı: yeni kuyruğa alma yok; yazıcı uyarılır (bekleme YOK — ortak kapanış bütçesini tüketmez). Lider
     * ipucu kapanışta false olduğu için 3-1'de son boşaltma yapılmaz (okuma yok, zararsız; 3-2 kira bırakılmadan önce
     * boşaltmalı).
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        if (context != null && event != null && event.getApplicationContext() != context) return;
        closing = true;
        signal();
    }

    @PreDestroy
    void destroy() {
        stop();
    }

    /** İş parçacığını durdurur: keser ve {@link ShutdownDeadline#waitMs}(tavan {@value #SHUTDOWN_JOIN_CAP_MS} ms) kadar bekler. */
    public void stop() {
        stopped = true;
        closing = true;
        Thread w = worker;
        signal();
        synchronized (flushedMonitor) {
            flushedMonitor.notifyAll();
        }
        if (w == null || w == Thread.currentThread()) return;
        w.interrupt();
        long budget = ShutdownDeadline.waitMs(SHUTDOWN_JOIN_CAP_MS);
        if (budget <= 0) return;
        try {
            w.join(budget);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── İç tipler ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** Tablo başına kuyruk: bekleyen işlemler, son kuyruğa alınan değerler, kirli anahtarlar. */
    private static final class Lane {
        final EvalStateKeys.Table table;
        final ConcurrentHashMap<String, Op> pending = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Seen> seen = new ConcurrentHashMap<>();
        final Set<String> dirty = ConcurrentHashMap.newKeySet();

        Lane(EvalStateKeys.Table table) {
            this.table = table;
        }

        @Override
        public String toString() {
            return "Lane[" + table + ", pending=" + pending.size() + "]";
        }
    }

    /** Bekleyen işlem: seq + yük ({@code RecoveryRow} | {@code LastStatusRow} | {@code TypeRow} | {@code ChainRow} | {@code ChainDelete}). */
    record Op(long seq, Object payload) {}

    /** Anahtarın son kuyruğa alınan değeri ve seq'i. */
    record Seen(long seq, Object value) {}

    /** Son gözlem için karşılaştırma demeti (yazım değişince ya da tazeleme süresi dolunca). */
    record LastSeen(boolean up, boolean networkDown, String errorClass, Long monitorId, Long teamId, Boolean standalone,
                    long observedMs, long changedMs) {
        boolean sameState(boolean u, boolean nd, String cls, Long mid, Long tid, Boolean sa) {
            return up == u && networkDown == nd && Objects.equals(errorClass, cls) && Objects.equals(monitorId, mid)
                    && Objects.equals(teamId, tid) && Objects.equals(standalone, sa);
        }
    }

    /** Tür bayraklarının son kuyruğa alınan hâli. */
    record TypeSeen(boolean active, Long sinceMs, Long roundMs, Long intervalMs) {
        static final TypeSeen NONE = new TypeSeen(false, null, null, null);

        TypeSeen next(TypeValues tv, long now) {
            boolean a = Boolean.TRUE.equals(tv.suppressionActive());
            Long since = a ? (active && sinceMs != null ? sinceMs : Long.valueOf(now)) : null;
            Long round = null;
            if (tv.scheduledRoundSeen()) {
                round = roundMs != null && now - roundMs < ROUND_STAMP_THROTTLE_MS && now >= roundMs ? roundMs : Long.valueOf(now);
            }
            Long interval = tv.roundIntervalMs();
            if (interval != null && intervalMs != null && intervalMs > 0
                    && Math.abs(interval - intervalMs) <= INTERVAL_CHANGE_RATIO * intervalMs) {
                interval = intervalMs;
            }
            return new TypeSeen(a, since, round, interval);
        }
    }
}
