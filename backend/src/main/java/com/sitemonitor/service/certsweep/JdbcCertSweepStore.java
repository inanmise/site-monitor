package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.checks.DbClock;
import com.sitemonitor.service.evalstate.EvalStateSql;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * {@link CertSweepStore} — {@code cert_sweep_runs} + {@code cert_sweep_chunks} üzerinde JDBC (2026-10-29, çok pod Sürüm 3-4
 * paket A; tasarım R3-4 §4.1 / §4.2). Her ifade otomatik commit, KENDİ sorgu zaman aşımlı şablonunda
 * ({@value CertSweepStore#QUERY_TIMEOUT_SECONDS} sn; kapanış yazımları {@value CertSweepStore#SHUTDOWN_QUERY_TIMEOUT_SECONDS} sn);
 * paylaşılan şablona dokunulmaz.
 *
 * <p><b>Tür:</b> {@link DeferredIndexBuilder#postgresOrNull} — yalnız BİLİNEN cevap ezberlenir. PostgreSQL'de sahiplenme tek
 * ifade ({@code WITH … FOR UPDATE OF k SKIP LOCKED … UPDATE … RETURNING}); H2 / bilinmeyen türde taşınabilir yol (aday okuması +
 * {@code attempts}'e bağlı CAS — PostgreSQL'de de doğru). Diğer tüm deyimler iki türde aynıdır.
 *
 * <p><b>Tablolar:</b> {@link #tablesReady()} BELLEKTİR — açılış yaması ({@link CertSweepSchemaPatch}) bildirir
 * ({@link #markTablesReady}); başarılı bir deyim de "hazır" yapar. Tablo yokken ({@link BadSqlGrammarException} + tablo
 * okunamıyor): {@link #claim} boş, {@link #renew} / {@link #release} 0, {@link #complete} false döner (gürültü yok); yokluk
 * ezberlenir ve sahiplenme en çok {@value #TABLE_RECHECK_MS} ms'de bir yeniden dener (öteki pod kurmuş olabilir).
 * {@link #createRun} / {@link #status} ve koordinatörün diğer yöntemleri fırlatır; {@link #finish} asla fırlatmaz.
 *
 * <p><b>Çağıranın işlemine katılmaz</b> (3-3 QW-L3): sınıf düzeyinde {@code @Transactional(propagation = NOT_SUPPORTED)} —
 * çağıran bir işlem içindeyse (ör. salt okunur System Health) o askıya alınır, her deyim kendi bağlantısında otomatik commit
 * koşar. {@code results_json} / {@code domains_json} hiçbir günlük satırına yazılmaz.
 *
 * <p><b>Kapı (L-1):</b> hiçbir yöntem anahtar açılıştan beri kapalıyken çağrılmaz — kurucu sorgu çalıştırmaz, saat
 * ({@code DbClock}) ilk {@link #nowMs()} çağrısında tembel çapalanır.
 */
@Slf4j
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class JdbcCertSweepStore implements CertSweepStore {

    /** Tablo yokluğunun sahiplenmede yeniden denetim aralığı (ms). */
    public static final long TABLE_RECHECK_MS = 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() { };

    private static final int TABLES_UNKNOWN = 0;
    private static final int TABLES_READY = 1;
    private static final int TABLES_MISSING = 2;

    private final JdbcTemplate jdbc;
    private final JdbcTemplate shutdownJdbc;
    private final DbClock clock;
    private final Boolean forcedDialect;
    private final LongSupplier nanoClock;

    /** Tür: TRUE PostgreSQL, FALSE başka, null henüz bilinmiyor (bilinmeyen = taşınabilir yol). */
    private volatile Boolean postgres;
    private volatile int tables = TABLES_UNKNOWN;
    private volatile long tablesCheckedNano = Long.MIN_VALUE;
    private final AtomicLong decodeWarnAt = new AtomicLong(Long.MIN_VALUE);

    /** Spring kurucusu: kendi zaman aşımlı şablonları (10 sn / kapanış 3 sn), 3-3'ün veritabanı-çapalı saati. */
    @Autowired
    public JdbcCertSweepStore(JdbcTemplate jdbc, DbClock clock) {
        this(EvalStateSql.withQueryTimeout(jdbc, QUERY_TIMEOUT_SECONDS),
                EvalStateSql.withQueryTimeout(jdbc, SHUTDOWN_QUERY_TIMEOUT_SECONDS), clock, null, System::nanoTime);
    }

    /** Test: şablon olduğu gibi (kapanış yazımları da aynı şablonla), saat ve isteğe bağlı zorla tür. */
    public JdbcCertSweepStore(JdbcTemplate jdbc, DbClock clock, Boolean forcedDialect) {
        this(jdbc, jdbc, clock, forcedDialect, System::nanoTime);
    }

    JdbcCertSweepStore(JdbcTemplate jdbc, JdbcTemplate shutdownJdbc, DbClock clock, Boolean forcedDialect, LongSupplier nanoClock) {
        this.jdbc = jdbc;
        this.shutdownJdbc = shutdownJdbc == null ? jdbc : shutdownJdbc;
        this.clock = clock == null ? DbClock.SYSTEM : clock;
        this.forcedDialect = forcedDialect;
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Ortak
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Override
    public long nowMs() {
        try {
            return clock.nowMs();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    @Override
    public boolean clockStale() {
        try {
            return clock.stale();
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public boolean tablesReady() {
        return tables == TABLES_READY;
    }

    /**
     * Açılış yamasının bildirimi (bellek): tablolar okunabiliyor mu. {@code false} → yokluk ezberlenir, sahiplenme
     * {@value #TABLE_RECHECK_MS} ms sonra yeniden dener.
     */
    public void markTablesReady(boolean ready) {
        tables = ready ? TABLES_READY : TABLES_MISSING;
        tablesCheckedNano = nanoClock.getAsLong();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Koordinatör
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Override
    public List<CertSweepStatus.Run> settleOrphans() {
        try {
            List<CertSweepStatus.Run> open = jdbc.query(CertSweepSql.SQL_OPEN_RUNS, RUN_ROW, CertSweepSql.OPEN_RUNS_MAX);
            List<CertSweepStatus.Run> settled = new ArrayList<>(open.size());
            long now = nowMs();
            for (CertSweepStatus.Run r : open) {
                if (jdbc.update(CertSweepSql.SQL_SETTLE_ONE, now, r.runId()) > 0) settled.add(r);
            }
            jdbc.update(CertSweepSql.SQL_DELETE_DEAD_CHUNKS);
            ready();
            return settled;
        } catch (BadSqlGrammarException e) {
            missingIfUnreadable();
            throw e;
        }
    }

    @Override
    public int pruneRuns(long olderThanMs, int limit) {
        if (limit <= 0) return 0;
        List<String> ids = jdbc.queryForList(CertSweepSql.SQL_PRUNE_CANDIDATES, String.class, olderThanMs, limit);
        int n = 0;
        for (int from = 0; from < ids.size(); from += CertSweepSql.IN_BATCH) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + CertSweepSql.IN_BATCH));
            Object[] args = new Object[part.size() + 1];
            args[0] = olderThanMs;
            for (int i = 0; i < part.size(); i++) args[i + 1] = part.get(i);
            n += jdbc.update(CertSweepSql.pruneRuns(part.size()), args);
        }
        return n;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Önce bu kimlikte koşu ya da parça olmadığı denetlenir (yarım kurulum temizliği ASLA başkasının satırını silmesin);
     * sonra parçalar (çok satırlı, {@value CertSweepSql#INSERT_ROWS}'lük deyimler), EN SON koşu satırı. Hata olursa YALNIZ bu
     * çağrının yazdığı parçalar silinir (koşu satırı yazılamadıysa başkasınındır — dokunulmaz) ve istisna fırlar.
     */
    @Override
    public void createRun(CertRunSpec spec) {
        Objects.requireNonNull(spec, "spec");
        String runId = spec.runId();
        try {
            Long taken = jdbc.queryForObject(CertSweepSql.SQL_RUN_ID_TAKEN, Long.class, runId, runId);
            if (taken != null && taken > 0) {
                throw new DataIntegrityViolationException("Parçalı tarama koşu kimliği zaten kullanımda");
            }
        } catch (BadSqlGrammarException e) {
            missingIfUnreadable();
            throw e;
        }
        long now = nowMs();
        try {
            insertChunks(runId, spec.chunks(), now);
            jdbc.update(CertSweepSql.SQL_INSERT_RUN, runId, spec.kind(), clip(spec.coordinator()), spec.evictCaches(),
                    spec.domainsTotal(), spec.chunksTotal(), spec.chunkSize(), now, now);
            ready();
        } catch (RuntimeException e) {
            // Parça yazımı yarıda kalmış ya da koşu satırı yazılamamış olabilir — ön denetim bu kimlikte satır olmadığını
            // gösterdi, yani bu kimliğin parçaları YALNIZ bu çağrınındır; koşu satırı yazılamadıysa yoktur.
            deleteQuietly(runId);
            if (e instanceof BadSqlGrammarException) missingIfUnreadable();
            throw e;
        }
    }

    @Override
    public int addChunks(String runId, List<CertChunkSpec> chunks) {
        if (runId == null || chunks == null || chunks.isEmpty()) return 0;
        return insertChunks(runId, chunks, nowMs());
    }

    private int insertChunks(String runId, List<CertChunkSpec> chunks, long now) {
        int n = 0;
        for (int from = 0; from < chunks.size(); from += CertSweepSql.INSERT_ROWS) {
            List<CertChunkSpec> part = chunks.subList(from, Math.min(chunks.size(), from + CertSweepSql.INSERT_ROWS));
            Object[] args = new Object[part.size() * 6];
            int i = 0;
            for (CertChunkSpec c : part) {
                args[i++] = runId;
                args[i++] = c.chunkNo();
                args[i++] = new SqlParameterValue(Types.VARCHAR, encodeDomains(c.entries()));
                args[i++] = c.domainCount();
                args[i++] = new SqlParameterValue(Types.VARCHAR, c.avoidWorker() == null ? null : clip(c.avoidWorker()));
                args[i++] = now;
            }
            n += jdbc.update(CertSweepSql.insertChunks(part.size()), args);
        }
        return n;
    }

    private void deleteQuietly(String runId) {
        try {
            jdbc.update(CertSweepSql.SQL_DELETE_RUN_CHUNKS, runId);
        } catch (RuntimeException ignore) {
            log.debug("Yarım parçalı koşunun parçaları silinemedi (sonraki koordinatör siler): {}", ignore.getClass().getSimpleName());
        }
    }

    @Override
    public boolean heartbeat(String runId) {
        return jdbc.update(CertSweepSql.SQL_HEARTBEAT, nowMs(), runId) > 0;
    }

    @Override
    public List<ChunkState> chunkStates(String runId) {
        return jdbc.query(CertSweepSql.SQL_CHUNK_STATES, CHUNK_STATE_ROW, runId, MAX_CHUNK_ROWS);
    }

    @Override
    public Map<Long, String> readResults(String runId, Collection<Long> chunkIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (runId == null || chunkIds == null || chunkIds.isEmpty()) return out;
        List<Long> ids = distinct(chunkIds);
        for (int from = 0; from < ids.size(); from += CertSweepSql.IN_BATCH) {
            List<Long> part = ids.subList(from, Math.min(ids.size(), from + CertSweepSql.IN_BATCH));
            Object[] args = new Object[part.size() + 1];
            args[0] = runId;
            for (int i = 0; i < part.size(); i++) args[i + 1] = part.get(i);
            jdbc.query(CertSweepSql.readResults(part.size()), (ResultSet rs) -> {
                while (rs.next()) out.put(rs.getLong("id"), rs.getString("results_json"));
                return null;
            }, args);
        }
        return out;
    }

    @Override
    public boolean cancel(String runId, long chunkId) {
        return jdbc.update(CertSweepSql.SQL_CANCEL, chunkId, runId) > 0;
    }

    @Override
    public boolean markDeciding(String runId, String workersJson, int takeovers, int reclaims, int secondOpinions) {
        String w = workersJson;
        if (w != null && w.length() > CertSweepStatus.MAX_WORKERS_JSON_CHARS) w = null;   // sütun sınırı — bilgi, karar değil
        return jdbc.update(CertSweepSql.SQL_MARK_DECIDING, new SqlParameterValue(Types.VARCHAR, w), takeovers, reclaims,
                secondOpinions, runId) > 0;
    }

    @Override
    public boolean finish(String runId, String state, String outcome, boolean atShutdown) {
        if (runId == null || state == null) return false;
        JdbcTemplate t = atShutdown ? shutdownJdbc : jdbc;
        boolean updated;
        try {
            updated = t.update(CertSweepSql.SQL_FINISH, state, new SqlParameterValue(Types.VARCHAR, outcome), nowMs(), runId) > 0;
        } catch (RuntimeException e) {
            log.debug("Parçalı tarama koşusu kapatılamadı (sonraki koordinatör öksüz kapatır): {}", e.getClass().getSimpleName());
            return false;
        }
        if (!atShutdown) {
            try {
                jdbc.update(CertSweepSql.SQL_DELETE_RUN_CHUNKS, runId);
            } catch (RuntimeException e) {
                log.debug("Parçalı tarama parçaları silinemedi (sonraki koordinatör siler): {}", e.getClass().getSimpleName());
            }
        }
        return updated;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İşçi
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Override
    public Optional<ClaimedChunk> claim(String owner, long leaseMs, long createdBeforeMs) {
        if (owner == null || owner.isBlank()) return Optional.empty();
        if (!tablesMayExist()) return Optional.empty();
        String who = clip(owner);
        long now = nowMs();
        long lease = Math.max(1_000L, leaseMs);
        long leaseUntil = now + lease;
        long aliveSince = now - CertChunking.COORDINATOR_ALIVE_MS;
        String token = newToken();
        try {
            Optional<ClaimedChunk> out = Boolean.TRUE.equals(postgres())
                    ? claimPg(who, token, now, leaseUntil, createdBeforeMs, aliveSince)
                    : claimPortable(who, token, now, leaseUntil, createdBeforeMs, aliveSince);
            ready();
            return out;
        } catch (BadSqlGrammarException e) {
            if (missingIfUnreadable()) return Optional.empty();
            throw e;
        }
    }

    private Optional<ClaimedChunk> claimPg(String who, String token, long now, long leaseUntil, long createdBefore,
                                           long aliveSince) {
        List<ClaimedChunk> rows = jdbc.query(CertSweepSql.SQL_CLAIM_PG, this::claimedRow,
                now, CertChunking.MAX_ATTEMPTS, createdBefore, who, aliveSince, who, token, now, leaseUntil);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<ClaimedChunk> claimPortable(String who, String token, long now, long leaseUntil, long createdBefore,
                                                 long aliveSince) {
        List<long[]> candidates = jdbc.query(CertSweepSql.SQL_CLAIM_CANDIDATES,
                (rs, i) -> new long[] {rs.getLong("id"), rs.getInt("attempts")},
                now, CertChunking.MAX_ATTEMPTS, createdBefore, who, aliveSince, CertSweepSql.CLAIM_CANDIDATES);
        for (long[] c : candidates) {
            int won = jdbc.update(CertSweepSql.SQL_CLAIM_CAS, who, token, now, leaseUntil, c[0], (int) c[1], now, aliveSince);
            if (won <= 0 && won != Statement.SUCCESS_NO_INFO) continue;   // başka pod aldı — sıradaki aday
            List<ClaimedChunk> rows = jdbc.query(CertSweepSql.SQL_CLAIMED_ROW, this::claimedRow, c[0], token);
            if (!rows.isEmpty()) return Optional.of(rows.get(0));
        }
        return Optional.empty();
    }

    @Override
    public int renew(String owner, Collection<Long> chunkIds, long leaseMs) {
        if (owner == null || chunkIds == null || chunkIds.isEmpty()) return 0;
        List<Long> ids = distinct(chunkIds);
        if (ids.isEmpty()) return 0;
        long until = nowMs() + Math.max(1_000L, leaseMs);
        try {
            int n = 0;
            for (int from = 0; from < ids.size(); from += CertSweepSql.IN_BATCH) {
                List<Long> part = ids.subList(from, Math.min(ids.size(), from + CertSweepSql.IN_BATCH));
                Object[] args = new Object[part.size() + 2];
                args[0] = until;
                args[1] = clip(owner);
                for (int i = 0; i < part.size(); i++) args[i + 2] = part.get(i);
                n += jdbc.update(CertSweepSql.renew(part.size()), args);
            }
            return n;
        } catch (BadSqlGrammarException e) {
            if (missingIfUnreadable()) return 0;
            throw e;
        }
    }

    @Override
    public boolean complete(ChunkCompletion c) {
        if (c == null) return false;
        try {
            int n = jdbc.update(CertSweepSql.SQL_COMPLETE, new SqlParameterValue(Types.VARCHAR, c.resultsJson()), c.checked(),
                    c.networkErrors(), c.errors(), c.warnings(), nowMs(),
                    new SqlParameterValue(Types.VARCHAR, c.worker() == null ? null : clip(c.worker())), c.id(),
                    new SqlParameterValue(Types.VARCHAR, c.claimToken()));
            return n > 0 || n == Statement.SUCCESS_NO_INFO;
        } catch (BadSqlGrammarException e) {
            if (missingIfUnreadable()) return false;
            throw e;
        }
    }

    @Override
    public int release(String owner, Collection<Long> chunkIds) {
        if (owner == null || chunkIds == null || chunkIds.isEmpty()) return 0;
        List<Long> ids = distinct(chunkIds);
        if (ids.isEmpty()) return 0;
        try {
            int n = 0;
            for (int from = 0; from < ids.size(); from += CertSweepSql.IN_BATCH) {
                List<Long> part = ids.subList(from, Math.min(ids.size(), from + CertSweepSql.IN_BATCH));
                Object[] args = new Object[part.size() + 1];
                args[0] = clip(owner);
                for (int i = 0; i < part.size(); i++) args[i + 1] = part.get(i);
                n += shutdownJdbc.update(CertSweepSql.release(part.size()), args);
            }
            return n;
        } catch (BadSqlGrammarException e) {
            if (missingIfUnreadable()) return 0;
            throw e;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Durum
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Override
    public CertSweepStatus status(int lastRuns) {
        int n = Math.max(1, Math.min(20, lastRuns));
        long now = nowMs();
        List<CertSweepStatus.Run> runs = jdbc.query(CertSweepSql.SQL_LAST_RUNS, RUN_ROW, n);
        CertSweepStatus.Run open = null;
        for (CertSweepStatus.Run r : runs) {
            if (r.open()) {
                open = r;
                break;
            }
        }
        List<CertSweepStatus.ChunkGroup> groups = open == null ? List.of()
                : jdbc.query(CertSweepSql.SQL_CHUNK_GROUPS, GROUP_ROW, open.runId());
        ready();
        return new CertSweepStatus(now, clockStale(), runs, open, groups);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Yardımcılar
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Veritabanı türü; yalnız BİLİNEN cevap ezberlenir. */
    Boolean postgres() {
        if (forcedDialect != null) return forcedDialect;
        Boolean p = postgres;
        if (p != null) return p;
        p = jdbc == null ? null : DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (p != null) postgres = p;
        return p;
    }

    /** Test: şablonların sorgu zaman aşımları (sn) — {@code [işlem, kapanış]}. */
    int[] queryTimeouts() {
        return new int[] {jdbc.getQueryTimeout(), shutdownJdbc.getQueryTimeout()};
    }

    private void ready() {
        if (tables != TABLES_READY) tables = TABLES_READY;
    }

    /** Yokluk ezberliyse {@value #TABLE_RECHECK_MS} ms dolmadan sorgu atılmaz (sahiplenme sessizce boş döner). */
    private boolean tablesMayExist() {
        if (tables != TABLES_MISSING) return true;
        long last = tablesCheckedNano;
        long now = nanoClock.getAsLong();
        if (last != Long.MIN_VALUE && TimeUnit.NANOSECONDS.toMillis(now - last) < TABLE_RECHECK_MS) return false;
        tablesCheckedNano = now;
        return true;
    }

    /** Hata dalında: tablolar okunamıyorsa yokluğu ezberler ve true döner (başka hata → false, asıl istisna fırlar). */
    private boolean missingIfUnreadable() {
        if (tableReadable(CertSweepSql.RUNS) && tableReadable(CertSweepSql.CHUNKS)) return false;
        tables = TABLES_MISSING;
        tablesCheckedNano = nanoClock.getAsLong();
        return true;
    }

    boolean tableReadable(String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (BadSqlGrammarException missing) {
            return false;
        } catch (DataAccessException other) {
            return true;   // başka hata: tablonun var olduğu varsayılır → asıl hata fırlar
        }
    }

    /** Sahiplenme başına rastgele belirteç (32 onaltılık karakter ≤ {@link CertSweepSql#TOKEN_MAX}). */
    static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= CertSweepSql.INSTANCE_MAX ? s : s.substring(0, CertSweepSql.INSTANCE_MAX);
    }

    private static List<Long> distinct(Collection<Long> ids) {
        List<Long> out = new ArrayList<>(new LinkedHashSet<>(ids));
        out.removeIf(Objects::isNull);
        return out;
    }

    // ── domains_json (deponun iç biçimi; kalıcı) ────────────────────────────────────────────────────────────────────

    /**
     * {@code [{"i":<sıra>,"domain":…,"port":…,"use_proxy":…,"tls_mode":…[,"timeout_seconds":…]}, …]} — değerler satırdaki gibi
     * HAM (port null olabilir, tls_mode boş metin olabilir); {@code timeout_seconds} yalnız doluysa.
     */
    static String encodeDomains(List<CertChunkSpec.Entry> entries) {
        List<Map<String, Object>> list = new ArrayList<>(entries.size());
        for (CertChunkSpec.Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("i", e.index());
            m.put("domain", e.domain());
            m.put("port", e.port());
            m.put("use_proxy", e.useProxy());
            m.put("tls_mode", e.tlsMode());
            if (e.timeoutSeconds() != null) m.put("timeout_seconds", e.timeoutSeconds());
            list.add(m);
        }
        return JSON.writeValueAsString(list);
    }

    /**
     * {@link #encodeDomains} çözümü. Bozuk JSON → boş liste; biçimi bozuk öğe atlanır (koordinatör eksik sıra numaralarını
     * kendisi ölçer). Asla fırlatmaz; JSON günlüğe yazılmaz.
     */
    List<CertChunkSpec.Entry> decodeDomains(String json) {
        List<CertChunkSpec.Entry> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        List<Object> items;
        try {
            items = JSON.readValue(json, LIST_TYPE);
        } catch (RuntimeException e) {
            warnDecode();
            return out;
        }
        if (items == null) return out;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            try {
                Object i = m.get("i");
                Object d = m.get("domain");
                if (!(i instanceof Number n) || !(d instanceof String domain)) continue;
                out.add(new CertChunkSpec.Entry(n.intValue(), domain, intOrNull(m.get("port")),
                        Boolean.TRUE.equals(m.get("use_proxy")), m.get("tls_mode") instanceof String s ? s : null,
                        intOrNull(m.get("timeout_seconds"))));
            } catch (RuntimeException bad) {
                warnDecode();
            }
        }
        return out;
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    private void warnDecode() {
        long now = System.currentTimeMillis();
        long last = decodeWarnAt.get();
        if ((last == Long.MIN_VALUE || now - last >= 60_000L) && decodeWarnAt.compareAndSet(last, now)) {
            log.warn("Parçalı tarama parçasının alan adı listesi çözülemedi — eksik alan adlarını koordinatör kendisi ölçer");
        }
    }

    // ── Satır eşleyiciler ───────────────────────────────────────────────────────────────────────────────────────────

    private ClaimedChunk claimedRow(ResultSet rs, int i) throws SQLException {
        return new ClaimedChunk(rs.getLong("id"), rs.getString("run_id"), rs.getInt("chunk_no"),
                decodeDomains(rs.getString("domains_json")), rs.getInt("attempts"), rs.getString("claim_token"),
                rs.getLong("claimed_ms"), rs.getLong("lease_until_ms"));
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static final RowMapper<CertSweepStatus.Run> RUN_ROW = (rs, i) -> new CertSweepStatus.Run(rs.getString("run_id"),
            rs.getString("kind"), rs.getString("coordinator"), rs.getString("state"), rs.getBoolean("evict_caches"),
            rs.getInt("domains_total"), rs.getInt("chunks_total"), rs.getInt("chunk_size"), rs.getLong("created_ms"),
            rs.getLong("heartbeat_ms"), longOrNull(rs, "finished_ms"), rs.getString("outcome"), intOrNull(rs, "takeovers"),
            intOrNull(rs, "reclaims"), intOrNull(rs, "second_opinions"), rs.getString("workers_json"));

    static final RowMapper<ChunkState> CHUNK_STATE_ROW = (rs, i) -> new ChunkState(rs.getLong("id"), rs.getInt("chunk_no"),
            rs.getString("state"), rs.getInt("attempts"), rs.getInt("domain_count"), rs.getLong("created_ms"),
            rs.getString("claimed_by"), longOrNull(rs, "claimed_ms"), longOrNull(rs, "lease_until_ms"), rs.getString("worker"),
            longOrNull(rs, "finished_ms"), intOrNull(rs, "checked"), intOrNull(rs, "network_errors"),
            rs.getString("avoid_worker"));

    static final RowMapper<CertSweepStatus.ChunkGroup> GROUP_ROW = (rs, i) -> new CertSweepStatus.ChunkGroup(
            rs.getString("state"), rs.getString("w"), rs.getLong("n"), rs.getLong("d"), rs.getLong("ne"),
            longOrNull(rs, "mc"));
}
