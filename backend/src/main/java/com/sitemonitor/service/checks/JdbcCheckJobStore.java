package com.sitemonitor.service.checks;

import com.sitemonitor.service.evalstate.EvalStateSql;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * {@link CheckJobStore} — {@code monitor_check_jobs} üzerinde JDBC (2026-10-29, çok pod Sürüm 3-3 paket A; tasarım R3-3
 * §3.2). Her ifade otomatik commit, KENDİ sorgu zaman aşımlı şablonunda ({@link CheckJobSql#QUERY_TIMEOUT_SECONDS}); paylaşılan
 * şablona dokunulmaz.
 *
 * <p><b>Tür:</b> {@link DeferredIndexBuilder#postgresOrNull} — yalnız BİLİNEN cevap ezberlenir. PostgreSQL'de tek ifadeli yollar
 * ({@code FOR UPDATE SKIP LOCKED}, {@code DELETE … RETURNING}, çok satırlı {@code ON CONFLICT DO NOTHING}); H2 / bilinmeyen
 * türde taşınabilir yol (aday okuması + satır başına CAS — tek değerlendirici / tek gönderen için doğru, PostgreSQL'de de doğru).
 * PostgreSQL'de çok satırlı gönderme yalnız tekil açık iş dizini ({@code ux_mcj_open}) GERÇEKTEN varken seçilir; dizin yoksa
 * (kurulamadıysa) taşınabilir "açık işi yoksa ekle" toplu deyimi koşar, dizin 60 sn'de bir yeniden denetlenir.
 *
 * <p><b>Tablo yok</b> (ilk açılışın ilk saniyeleri, yama düştü): {@link BadSqlGrammarException} + tablo okunamıyor → sahiplenme /
 * alma / tamamlama / bırakma / biçme / özet boş / 0 döner, fırlatmaz. <b>Gönderme fırlatır</b> (inceleme QW-L9 / DA-M1):
 * {@link DataAccessResourceFailureException} (Türkçe ileti, SQL metni yok) — çağıranın {@code checkDue}'su dönemi zaten
 * ilerletmiştir; sessiz 0 "gönderildi" sanılıp tür dağıtık kipte hiç denetlenmeden kalırdı, şimdi gönderen başarısızlık sayar.
 * Tablo VARKEN bir sözdizimi hatası ya da başka veritabanı hatası {@link DataAccessException} olarak fırlar (çağıran yakalar,
 * sonraki tıkta yeniden dener). {@code result_json} hiçbir günlük satırına yazılmaz.
 *
 * <p><b>Çağıranın işlemine katılmaz</b> (inceleme QW-L3): sınıf düzeyinde {@code @Transactional(propagation = NOT_SUPPORTED)}
 * — Spring bean'i vekil (CGLIB) üzerinden çağrılır; çağıran bir işlem içindeyse (ör. salt okunur System Health) o askıya alınır,
 * her deyim kendi bağlantısında otomatik commit koşar. Bir PostgreSQL hatası çağıranın işlemini bozamaz (25P02), salt okunur
 * işlem bir göndermeyi reddettirmez.
 *
 * <p><b>Eski DONE satırları</b> (inceleme DH-M2 c): {@link #reap} kirası dolmuş iki kez sahiplenilmiş işlere ek olarak bitişi
 * {@code 2 × result-stale-ms}'ten (varsayılan 20 dk) eski DONE satırlarını da siler (çağrı başına ≤
 * {@value #STALE_DONE_REAP_LIMIT}; dönen listeye girmez — {@link #staleDoneReaped()} sayar, dakikada en çok bir WARN) — kimse
 * boşaltmasa da tablo sınırsız büyümez. Değerlendirici bu satırları zaten bayat diye atardı (≥ 1 × result-stale-ms).
 *
 * <p><b>Kapı:</b> hiçbir yöntem ana anahtar kapalıyken çağrılmaz (K-1) — çağıranlar önce
 * {@link DistributedChecks#anyDistributed()} okur; bu sınıf açılışta hiçbir sorgu çalıştırmaz.
 */
@Slf4j
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class JdbcCheckJobStore implements CheckJobStore {

    /** Tekil dizin yokluğunun yeniden denetim aralığı. */
    static final long OPEN_INDEX_RECHECK_MS = 60_000L;
    /** Bir biçme çağrısında en çok silinen eski DONE satırı. */
    public static final int STALE_DONE_REAP_LIMIT = 200;
    /** Eski DONE eşiği = bu kat × {@code result-stale-ms}. */
    public static final int STALE_DONE_FACTOR = 2;
    /** Gönderme tablo okunamıyorken fırlatılan istisnanın iletisi (SQL metni yok). */
    static final String DISPATCH_TABLE_MISSING = "Dağıtık kontrol iş tablosu okunamıyor — işler gönderilemedi";
    static final String DISPATCH_PARTIAL = "Dağıtık kontrol işleri kısmen gönderildi — kalan parçalar yazılamadı";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final DbClock clock;
    private final LongSupplier nanoClock;

    /** Tür: TRUE PostgreSQL, FALSE başka, null henüz bilinmiyor (bilinmeyen = taşınabilir yol). */
    private volatile Boolean postgres;
    /** Test: türü zorla (null = gerçek türü oku). */
    private final Boolean forcedDialect;
    /** PostgreSQL tekil açık iş dizini: TRUE varsa ezberlenir; aksi hâlde {@link #OPEN_INDEX_RECHECK_MS}'de bir yeniden bakılır. */
    private volatile boolean openIndexPresent;
    private volatile long openIndexCheckedNano = Long.MIN_VALUE;
    /** {@code result-stale-ms} (açılışta okunan pod ayarı; bean yoksa varsayılan). */
    private volatile long resultStaleMs = DistributedChecks.Tuning.DEFAULTS.resultStaleMs();
    private final AtomicLong staleDoneReaped = new AtomicLong();
    private final AtomicLong staleDoneReapFailures = new AtomicLong();
    private final AtomicLong staleDoneWarnAt = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public JdbcCheckJobStore(JdbcTemplate jdbc, DbClock clock) {
        this(EvalStateSql.withQueryTimeout(jdbc, CheckJobSql.QUERY_TIMEOUT_SECONDS), clock, null, System::nanoTime);
    }

    /** Pod ayarları ({@code result-stale-ms}) — isteğe bağlı; yoksa {@link DistributedChecks.Tuning#DEFAULTS}. Sorgu yok. */
    @Autowired(required = false)
    public void setDistributedChecks(DistributedChecks distributedChecks) {
        if (distributedChecks != null) this.resultStaleMs = distributedChecks.tuning().resultStaleMs();
    }

    /** Test: şablon olduğu gibi, saat ve isteğe bağlı zorla tür. */
    public JdbcCheckJobStore(JdbcTemplate jdbc, DbClock clock, Boolean forcedDialect, LongSupplier nanoClock) {
        this.jdbc = jdbc;
        this.clock = clock == null ? DbClock.SYSTEM : clock;
        this.forcedDialect = forcedDialect;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
    }

    // ── Gönderme ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int dispatch(String type, Collection<Long> monitorIds, long leaseMs, String dispatcher) {
        if (!CheckJobTypes.isKnown(type) || monitorIds == null || monitorIds.isEmpty()) return 0;
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(monitorIds));
        ids.removeIf(java.util.Objects::isNull);
        if (ids.isEmpty()) return 0;
        long lease = Math.max(1_000L, leaseMs);
        String who = clip(dispatcher == null ? "?" : dispatcher, CheckJobSql.INSTANCE_MAX);
        long now = clock.nowMs();
        try {
            int inserted = 0;
            boolean pg = Boolean.TRUE.equals(postgres());
            boolean multiRow = pg && openIndexPresent();
            for (int from = 0; from < ids.size(); from += CheckJobSql.DISPATCH_CHUNK) {
                List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CheckJobSql.DISPATCH_CHUNK));
                try {
                    inserted += multiRow ? dispatchMultiRow(type, chunk, lease, now, who)
                            : dispatchPortable(pg ? CheckJobSql.SQL_DISPATCH_ONE_PG : CheckJobSql.SQL_DISPATCH_ONE, type, chunk,
                                    lease, now, who);
                } catch (RuntimeException e) {
                    if (from == 0) throw e;
                    // F1: önceki parçalar yazıldı — gönderen yalnız yazılmayanları yeniden sunsun
                    throw new PartialDispatchException(DISPATCH_PARTIAL, e, ids.subList(0, from));
                }
            }
            return inserted;
        } catch (BadSqlGrammarException e) {
            // Tablo yok / okunamıyor: sessiz 0 YOK — gönderen (dönemi checkDue ile zaten ilerletti) başarısızlık saysın.
            if (!tableReadable()) throw new DataAccessResourceFailureException(DISPATCH_TABLE_MISSING);
            throw e;
        }
    }

    private int dispatchMultiRow(String type, List<Long> chunk, long lease, long now, String who) {
        Object[] args = new Object[chunk.size() * 5];
        int i = 0;
        for (Long id : chunk) {
            args[i++] = type;
            args[i++] = id;
            args[i++] = lease;
            args[i++] = now;
            args[i++] = who;
        }
        return jdbc.update(CheckJobSql.dispatchMultiRowPg(chunk.size()), args);
    }

    private int dispatchPortable(String sql, String type, List<Long> chunk, long lease, long now, String who) {
        List<Object[]> batch = new ArrayList<>(chunk.size());
        for (Long id : chunk) batch.add(new Object[] {type, id, lease, now, who, type, id});
        int n = 0;
        for (int c : jdbc.batchUpdate(sql, batch)) {
            if (c > 0) n += c;
            else if (c == Statement.SUCCESS_NO_INFO) n++;
        }
        return n;
    }

    // ── Sahiplenme ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Arayüzün varsayılan yöntemi burada da bildirilir — sınıf düzeyi {@code NOT_SUPPORTED} vekil üzerinden ona da uygulansın. */
    @Override
    public List<CheckJobRow> claim(Collection<String> types, int limit, String owner) {
        return claim(types, limit, owner, ANY_AGE);
    }

    @Override
    public List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs) {
        if (types == null || types.isEmpty() || limit <= 0) return List.of();
        List<String> known = new ArrayList<>(CheckJobTypes.canonical(types));
        if (known.isEmpty()) return List.of();
        int n = Math.min(limit, CheckJobSql.MAX_BATCH);
        String who = clip(owner == null ? "?" : owner, CheckJobSql.INSTANCE_MAX);
        boolean createdFilter = createdBeforeMs != ANY_AGE;
        String token = newToken();
        long now = clock.nowMs();
        try {
            List<CheckJobRow> out = Boolean.TRUE.equals(postgres())
                    ? claimPg(known, n, who, token, now, createdFilter, createdBeforeMs)
                    : claimPortable(known, n, who, token, now, createdFilter, createdBeforeMs);
            out.sort(Comparator.comparingLong(CheckJobRow::id));
            return out;
        } catch (BadSqlGrammarException e) {
            if (!tableReadable()) return List.of();
            throw e;
        }
    }

    private List<CheckJobRow> claimPg(List<String> types, int n, String who, String token, long now, boolean createdFilter,
                                      long createdBefore) {
        // CTE biçimi: önce aday koşulu + limit (CTE), sonra SET değerleri — parametre sırası CheckJobSql.claimPg ile aynı.
        List<Object> args = new ArrayList<>();
        addCandidateArgs(args, types, now, createdFilter, createdBefore);
        args.add(n);
        args.add(who);
        args.add(token);
        args.add(now);
        args.add(now);
        List<CheckJobRow> rows = jdbc.query(CheckJobSql.claimPg(types.size(), createdFilter), CLAIMED_ROW, args.toArray());
        return new ArrayList<>(rows);
    }

    private List<CheckJobRow> claimPortable(List<String> types, int n, String who, String token, long now, boolean createdFilter,
                                            long createdBefore) {
        List<Object> args = new ArrayList<>();
        addCandidateArgs(args, types, now, createdFilter, createdBefore);
        args.add(n);
        List<Candidate> candidates = jdbc.query(CheckJobSql.claimCandidates(types.size(), createdFilter), CANDIDATE, args.toArray());
        if (candidates.isEmpty()) return new ArrayList<>();
        List<Object[]> batch = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) batch.add(new Object[] {who, token, now, now, c.id(), c.attempts(), now});
        int[] counts = jdbc.batchUpdate(CheckJobSql.SQL_CLAIM_CAS, batch);
        List<CheckJobRow> out = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            int c = i < counts.length ? counts[i] : 0;
            if (c <= 0 && c != Statement.SUCCESS_NO_INFO) continue;
            Candidate k = candidates.get(i);
            out.add(new CheckJobRow(k.id(), k.type(), k.monitorId(), k.createdMs(), k.attempts() + 1, k.leaseMs(), token, now,
                    now + k.leaseMs()));
        }
        return out;
    }

    private static void addCandidateArgs(List<Object> args, List<String> types, long now, boolean createdFilter,
                                         long createdBefore) {
        args.addAll(types);
        args.add(now);
        args.add(CheckJobTypes.MAX_ATTEMPTS);
        if (createdFilter) args.add(createdBefore);
    }

    // ── Tamamlama / bırakma ─────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean[] complete(List<Completion> completions) {
        if (completions == null || completions.isEmpty()) return new boolean[0];
        long now = clock.nowMs();
        List<Object[]> batch = new ArrayList<>(completions.size());
        for (Completion c : completions) {
            batch.add(new Object[] {c.resultJson(), c.truncated(), now, clip(c.worker(), CheckJobSql.INSTANCE_MAX), c.id(),
                    c.claimToken()});
        }
        boolean[] out = new boolean[completions.size()];
        try {
            int[] counts = jdbc.batchUpdate(CheckJobSql.SQL_COMPLETE, batch);
            for (int i = 0; i < out.length; i++) {
                int c = i < counts.length ? counts[i] : 0;
                out[i] = c > 0 || c == Statement.SUCCESS_NO_INFO;
            }
            return out;
        } catch (BadSqlGrammarException e) {
            if (!tableReadable()) return out;
            throw e;
        }
    }

    @Override
    public int release(String owner, Collection<Long> ids) {
        if (owner == null || ids == null || ids.isEmpty()) return 0;
        List<Long> list = new ArrayList<>(new LinkedHashSet<>(ids));
        list.removeIf(java.util.Objects::isNull);
        if (list.isEmpty()) return 0;
        int n = 0;
        try {
            for (int from = 0; from < list.size(); from += CheckJobSql.MAX_BATCH) {
                List<Long> chunk = list.subList(from, Math.min(list.size(), from + CheckJobSql.MAX_BATCH));
                Object[] args = new Object[chunk.size() + 1];
                args[0] = clip(owner, CheckJobSql.INSTANCE_MAX);
                for (int i = 0; i < chunk.size(); i++) args[i + 1] = chunk.get(i);
                n += jdbc.update(CheckJobSql.release(chunk.size()), args);
            }
            return n;
        } catch (BadSqlGrammarException e) {
            if (!tableReadable()) return n;
            throw e;
        }
    }

    // ── Alma ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<CheckResultRow> take(int limit, String leaseOwner) {
        if (limit <= 0 || leaseOwner == null || leaseOwner.isBlank()) return List.of();
        int n = Math.min(limit, CheckJobSql.MAX_BATCH);
        try {
            List<CheckResultRow> out;
            if (Boolean.TRUE.equals(postgres())) {
                out = new ArrayList<>(jdbc.query(CheckJobSql.SQL_TAKE_PG, RESULT_ROW, n, leaseOwner));
            } else {
                List<CheckResultRow> candidates = jdbc.query(CheckJobSql.SQL_TAKE_CANDIDATES, RESULT_ROW, n);
                out = new ArrayList<>(candidates.size());
                if (!candidates.isEmpty()) {
                    List<Object[]> batch = new ArrayList<>(candidates.size());
                    for (CheckResultRow r : candidates) batch.add(new Object[] {r.id(), leaseOwner});
                    int[] counts = jdbc.batchUpdate(CheckJobSql.SQL_TAKE_ONE, batch);
                    for (int i = 0; i < candidates.size(); i++) {
                        int c = i < counts.length ? counts[i] : 0;
                        if (c > 0 || c == Statement.SUCCESS_NO_INFO) out.add(candidates.get(i));
                    }
                }
            }
            out.sort(Comparator.comparingLong(CheckResultRow::id));
            return out;
        } catch (BadSqlGrammarException e) {
            if (!tableReadable()) return List.of();
            throw e;
        }
    }

    // ── Biçme / özet ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Ayrıca (inceleme DH-M2 c) bitişi {@code STALE_DONE_FACTOR × result-stale-ms}'ten eski DONE satırları silinir (≤
     * {@value #STALE_DONE_REAP_LIMIT}); bunlar dönen listeye GİRMEZ (çağıranın "N kez sahiplenildi" uyarısı yalnız kirası dolmuş
     * işler içindir) — {@link #staleDoneReaped()} sayar, dakikada en çok bir WARN. Bu ikinci adımın hatası ilk adımın sonucunu
     * düşürmez (yutulur, {@link #staleDoneReapFailures()}).
     */
    @Override
    public List<CheckJobRow> reap(int limit) {
        if (limit <= 0) return List.of();
        int n = Math.min(limit, CheckJobSql.MAX_BATCH);
        long now = clock.nowMs();
        List<CheckJobRow> out = new ArrayList<>();
        try {
            List<Candidate> candidates = jdbc.query(CheckJobSql.SQL_REAP_CANDIDATES, CANDIDATE, now, CheckJobTypes.MAX_ATTEMPTS, n);
            if (!candidates.isEmpty()) {
                List<Object[]> batch = new ArrayList<>(candidates.size());
                for (Candidate c : candidates) batch.add(new Object[] {c.id(), now, CheckJobTypes.MAX_ATTEMPTS});
                int[] counts = jdbc.batchUpdate(CheckJobSql.SQL_REAP_ONE, batch);
                for (int i = 0; i < candidates.size(); i++) {
                    int c = i < counts.length ? counts[i] : 0;
                    if (c <= 0 && c != Statement.SUCCESS_NO_INFO) continue;
                    Candidate k = candidates.get(i);
                    out.add(new CheckJobRow(k.id(), k.type(), k.monitorId(), k.createdMs(), k.attempts(), k.leaseMs(), null, 0L,
                            0L));
                }
            }
        } catch (BadSqlGrammarException e) {
            if (!tableReadable()) return List.of();
            throw e;
        }
        reapStaleDone(now);
        return out;
    }

    /** Eski DONE satırları (bitişi {@code STALE_DONE_FACTOR × result-stale-ms}'ten eski) — fırlatmaz. */
    private void reapStaleDone(long now) {
        long cutoff = now - (long) STALE_DONE_FACTOR * resultStaleMs;
        try {
            List<Long> ids = jdbc.queryForList(CheckJobSql.SQL_REAP_DONE_CANDIDATES, Long.class, cutoff, STALE_DONE_REAP_LIMIT);
            if (ids.isEmpty()) return;
            List<Object[]> batch = new ArrayList<>(ids.size());
            for (Long id : ids) batch.add(new Object[] {id, cutoff});
            int deleted = 0;
            for (int c : jdbc.batchUpdate(CheckJobSql.SQL_REAP_DONE_ONE, batch)) {
                if (c > 0) deleted += c;
                else if (c == Statement.SUCCESS_NO_INFO) deleted++;
            }
            if (deleted <= 0) return;
            staleDoneReaped.addAndGet(deleted);
            long t = System.currentTimeMillis();
            long last = staleDoneWarnAt.get();
            if ((last == Long.MIN_VALUE || t - last >= 60_000L) && staleDoneWarnAt.compareAndSet(last, t)) {
                log.warn("Dağıtık kontrol sonuçları değerlendirilmeden silindi: {} sonuç {} dakikadan uzun süredir bekliyordu "
                        + "(sonuç boşaltıcısı çalışmıyor olabilir)", deleted, TimeUnit.MILLISECONDS.toMinutes(now - cutoff));
            }
        } catch (DataAccessException e) {
            staleDoneReapFailures.incrementAndGet();
            log.debug("Dağıtık kontrol eski sonuç biçmesi başarısız (sonraki tıkta yeniden): {}", e.getClass().getSimpleName());
        }
    }

    /** Açılıştan beri değerlendirilmeden silinen eski DONE satırı (bellek; System Health / ölçümler — paket D). */
    public long staleDoneReaped() {
        return staleDoneReaped.get();
    }

    /** Açılıştan beri eski DONE biçmesinin başarısız denemesi (bellek). */
    public long staleDoneReapFailures() {
        return staleDoneReapFailures.get();
    }

    @Override
    public QueueStats stats() {
        long now = clock.nowMs();
        try {
            List<QueueStat> rows = new ArrayList<>();
            long[] expired = {0L};
            jdbc.query(CheckJobSql.SQL_STATS, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                long minCreated = rs.getLong("min_created");
                Long created = rs.wasNull() ? null : minCreated;
                long minFinished = rs.getLong("min_finished");
                Long finished = rs.wasNull() ? null : minFinished;
                expired[0] += rs.getLong("expired");
                rows.add(new QueueStat(rs.getString("state"), rs.getString("job_type"), rs.getLong("n"), created, finished));
            }, now);
            Long oldestPending = null;
            Long oldestDone = null;
            for (QueueStat r : rows) {
                if (STATE_PENDING.equals(r.state()) && r.minCreatedMs() != null) {
                    long age = Math.max(0L, now - r.minCreatedMs());
                    oldestPending = oldestPending == null ? age : Math.max(oldestPending, age);
                } else if (STATE_DONE.equals(r.state())) {
                    Long base = r.minFinishedMs() != null ? r.minFinishedMs() : r.minCreatedMs();
                    if (base != null) {
                        long age = Math.max(0L, now - base);
                        oldestDone = oldestDone == null ? age : Math.max(oldestDone, age);
                    }
                }
            }
            rows.sort(Comparator.comparing(QueueStat::state).thenComparing(QueueStat::type));
            return new QueueStats(now, rows, oldestPending, oldestDone, expired[0]);
        } catch (BadSqlGrammarException e) {
            if (!tableReadable()) return new QueueStats(now, List.of(), null, null, 0L);
            throw e;
        }
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** Kuyruk tablosu okunabiliyor mu (veri yolunun şema çözümüyle) — yalnız hata dalında çağrılır. */
    boolean tableReadable() {
        try {
            jdbc.queryForList("SELECT 1 FROM " + CheckJobSql.JOBS + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        } catch (DataAccessException other) {
            return true;   // başka hata: tablonun var olduğu varsayılır → asıl hata fırlar
        }
    }

    /** Veritabanı türü; yalnız BİLİNEN cevap ezberlenir. */
    Boolean postgres() {
        if (forcedDialect != null) return forcedDialect;
        Boolean p = postgres;
        if (p != null) return p;
        p = jdbc == null ? null : DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return p;
    }

    /** PostgreSQL tekil açık iş dizini var mı — TRUE ezberlenir, FALSE / okunamadı {@link #OPEN_INDEX_RECHECK_MS}'de bir. */
    boolean openIndexPresent() {
        if (openIndexPresent) return true;
        long now = nanoClock.getAsLong();
        long last = openIndexCheckedNano;
        if (last != Long.MIN_VALUE && TimeUnit.NANOSECONDS.toMillis(now - last) < OPEN_INDEX_RECHECK_MS) return false;
        openIndexCheckedNano = now;
        try {
            Boolean present = jdbc.queryForObject(CheckJobSql.SQL_OPEN_INDEX_PRESENT_PG, Boolean.class);
            if (Boolean.TRUE.equals(present)) {
                openIndexPresent = true;
                return true;
            }
            log.debug("Dağıtık kontrol kuyruğu: {} dizini yok — taşınabilir gönderme kullanılıyor", CheckJobSql.OPEN_UNIQUE_INDEX);
        } catch (DataAccessException e) {
            log.debug("Dağıtık kontrol kuyruğu: {} dizini denetlenemedi: {}", CheckJobSql.OPEN_UNIQUE_INDEX,
                    e.getClass().getSimpleName());
        }
        return false;
    }

    /** Sahiplenme başına rastgele belirteç (32 onaltılık karakter ≤ {@link CheckJobSql#TOKEN_MAX}). */
    static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record Candidate(long id, String type, long monitorId, long createdMs, int attempts, long leaseMs) {}

    private static final RowMapper<Candidate> CANDIDATE = (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("job_type"),
            rs.getLong("monitor_id"), rs.getLong("created_ms"), rs.getInt("attempts"), rs.getLong("lease_ms"));

    private static final RowMapper<CheckJobRow> CLAIMED_ROW = (rs, i) -> new CheckJobRow(rs.getLong("id"),
            rs.getString("job_type"), rs.getLong("monitor_id"), rs.getLong("created_ms"), rs.getInt("attempts"),
            rs.getLong("lease_ms"), rs.getString("claim_token"), rs.getLong("claimed_ms"), rs.getLong("lease_until_ms"));

    private static final RowMapper<CheckResultRow> RESULT_ROW = JdbcCheckJobStore::resultRow;

    private static CheckResultRow resultRow(ResultSet rs, int i) throws SQLException {
        long claimed = rs.getLong("claimed_ms");
        Long claimedMs = rs.wasNull() ? null : claimed;
        long finished = rs.getLong("finished_ms");
        Long finishedMs = rs.wasNull() ? null : finished;
        return new CheckResultRow(rs.getLong("id"), rs.getString("job_type"), rs.getLong("monitor_id"), rs.getLong("created_ms"),
                claimedMs, finishedMs, rs.getString("worker"), rs.getString("result_json"), rs.getBoolean("result_truncated"));
    }
}
