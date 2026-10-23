package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Test KAYDEDİCİSİ (çok pod Sürüm 3-1 A2): kancaların aldığı her işlemi AYNI iş parçacığında, eşzamanlı olarak H2
 * tablolarına yazan, sözleşmeye sadık en basit ayna. Gerçek kaydedici ({@code EvalStateRecorder}, A1) birleştirir, iş
 * parçacığında boşaltır, no-op süzer — doğrulama sonucu açısından eşdeğerdir: aynı (anahtar → son değer) aynası. Bu yüzden
 * "kancalar eksiksiz mi" sorusunun kahinidir: MOS senaryosundan sonra doğrulama 0 uyuşmazlık vermeli.
 *
 * <p>DDL = A1 {@link EvalStateSql} sabitleri; betimleyici = {@link RecheckRefs}. Gerçek kaydediciyle uçtan uca sürüm:
 * {@code EvalStateMultiPodTest}, {@code EvalStateVerifierTest.realRecorder_zeroMismatches}.
 * Ayrıca {@link EvalStateVerification.Context}: sıra numarası, anahtar başına son seq, kirli anahtarlar.
 */
public final class MirrorRecorder implements EvalStateSink, EvalStateVerification.Context {

    /** Dört tablo — A1'in DDL'i (doğrulayıcının okuduğu şema ile aynı kaynak). */
    public static final List<String> DDL = List.of(EvalStateSql.DDL_EVAL_STATE, EvalStateSql.DDL_LAST_STATUS,
            EvalStateSql.DDL_LAST_STATUS_INDEX, EvalStateSql.DDL_TYPE_STATE, EvalStateSql.DDL_CONFIRMATIONS);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final String writer;
    private final LongSupplier clock;
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Long> lastSeq = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private volatile EvalStateSource source;
    private volatile long generation;
    private volatile long generationStartMs;
    private volatile boolean recording = true;
    /** Son toplu düşüşün sırası (kip kapandı): bu ana kadarki ya da sonra hiç kuyruğa alınmamış anahtar atlanır. */
    private volatile long gapSeq;
    private volatile boolean flushed = true;
    private final AtomicLong ops = new AtomicLong();

    public MirrorRecorder(JdbcTemplate jdbc, String writer, long generation, LongSupplier clock) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.clock = clock;
        this.generation = generation;
        this.generationStartMs = clock.getAsLong();
        for (String ddl : DDL) jdbc.execute(ddl);
    }

    /**
     * Kip kapalı benzetimi: false iken işlemler sıra numarası almaz, yazılmaz; kapanışta TOPLU DÜŞÜŞ (gerçek kaydedicinin
     * {@code applyGap} kuralı): o ana kadarki anahtarlar, yeniden kuyruğa alınana dek doğrulamada atlanır.
     */
    public void setRecording(boolean on) {
        if (!on && recording) {
            gapSeq = seq.get();
            lastSeq.clear();
        }
        recording = on;
    }

    /** Boşaltma gecikmesi benzetimi: false iken {@link #awaitFlushed} false döner. */
    public void setFlushed(boolean on) {
        flushed = on;
    }

    /** Yeni kuşak (liderlik yeniden kazanıldı): toplu düşüş sıfırlanır, anahtar sıraları unutulur (gerçek kaydedicinin GAINED'i). */
    public void newGeneration(long gen) {
        generation = gen;
        generationStartMs = clock.getAsLong();
        gapSeq = 0L;
        lastSeq.clear();
        dirty.clear();
    }

    /** Anahtarı kirli işaretle (gerçek kaydedicide üst sınırda düşen işlem). */
    public void markDirty(EvalStateKeys.Table table, String key) {
        dirty.add(table.name() + "|" + key);
    }

    /** Kaydedilen işlem sayısı. */
    public long ops() {
        return ops.get();
    }

    public EvalStateSource source() {
        return source;
    }

    private long next(EvalStateKeys.Table table, String key) {
        long s = seq.incrementAndGet();
        lastSeq.merge(table.name() + "|" + key, s, Math::max);
        ops.incrementAndGet();
        return s;
    }

    private String iso(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    // ── EvalStateSink ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void recovery(String rkey, String alertType, String domain) {
        if (!recording || source == null) return;
        long s = next(EvalStateKeys.Table.EVAL_STATE, rkey);
        EvalStateSource.RecoveryValues v = source.recoveryValues(rkey);
        long now = clock.getAsLong();
        int n = jdbc.update("UPDATE monitor_eval_state SET recovery_up_count = ?, recovery_last_counted_ms = ?, "
                        + "recovery_chain_gen = ?, recovery_chain_started_ms = ?, writer = ?, gen = ?, seq = ?, updated_ms = ?, "
                        + "updated_at = ? WHERE alert_type = ? AND domain = ?",
                v.upCount(), v.lastCountedMs(), v.chainGen(), v.chainStartedMs(), writer, generation, s, now, iso(now),
                alertType, domain);
        if (n == 0) {
            jdbc.update("INSERT INTO monitor_eval_state (alert_type, domain, recovery_up_count, recovery_last_counted_ms, "
                            + "recovery_chain_gen, recovery_chain_started_ms, writer, gen, seq, updated_ms, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    alertType, domain, v.upCount(), v.lastCountedMs(), v.chainGen(), v.chainStartedMs(), writer, generation,
                    s, now, iso(now));
        }
    }

    @Override
    public void observation(String alertType, SweepItem item, boolean manual, long atMs) {
        if (!recording || source == null) return;
        String itemKey = EvalStateKeys.itemKey(item);
        long s = next(EvalStateKeys.Table.LAST_STATUS, EvalStateKeys.lastStatusKey(alertType, itemKey));
        String cls = source.errorClass(item.error(), item.domain());
        boolean netDown = !item.up() && (EvalStateSource.ERROR_NETWORK.equals(cls) || EvalStateSource.ERROR_DNS.equals(cls));
        Map<String, Object> ctx = item.ctxExtra() == null ? Map.of() : item.ctxExtra();
        Long monitorId = ctx.get("monitor_id") instanceof Number n ? n.longValue() : null;
        Long teamId = ctx.get("team_id") instanceof Number n ? n.longValue() : null;
        Boolean standalone = ctx.get("standalone") instanceof Boolean b ? b : null;
        long now = clock.getAsLong();
        jdbc.update("DELETE FROM monitor_last_status WHERE alert_type = ? AND item_key = ?", alertType, itemKey);
        jdbc.update("INSERT INTO monitor_last_status (alert_type, item_key, domain, detail, monitor_id, team_id, standalone, up, "
                        + "network_down, error_class, source, observed_ms, changed_ms, writer, gen, seq, updated_ms, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                alertType, itemKey, item.domain(), item.detail(), monitorId, teamId, standalone, item.up(), netDown, cls,
                manual ? "M" : "S", atMs, atMs, writer, generation, s, now, iso(now));
    }

    @Override
    public void observationPruned(String alertType, SweepItem item) {
        if (!recording) return;
        String itemKey = EvalStateKeys.itemKey(item);
        next(EvalStateKeys.Table.LAST_STATUS, EvalStateKeys.lastStatusKey(alertType, itemKey));
        jdbc.update("DELETE FROM monitor_last_status WHERE alert_type = ? AND item_key = ?", alertType, itemKey);
    }

    @Override
    public void typeState(String alertType) {
        if (!recording || source == null) return;
        long s = next(EvalStateKeys.Table.TYPE_STATE, alertType);
        EvalStateSource.TypeValues v = source.typeValues(alertType);
        long now = clock.getAsLong();
        Long round = v.scheduledRoundSeen() ? Long.valueOf(Math.max(now, generationStartMs)) : null;
        jdbc.update("DELETE FROM monitor_eval_type_state WHERE alert_type = ?", alertType);
        jdbc.update("INSERT INTO monitor_eval_type_state (alert_type, suppression_active, suppression_since_ms, "
                        + "last_scheduled_round_ms, round_interval_ms, writer, gen, seq, updated_ms, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                alertType, v.suppressionActive(), Boolean.TRUE.equals(v.suppressionActive()) ? now : null, round,
                v.roundIntervalMs(), writer, generation, s, now, iso(now));
    }

    @Override
    public void chainStart(String confirmKey, long chainId, SweepItem item, ConfirmState state, String firstFailureAt,
                           long delayMs) {
        if (!recording) return;
        long s = next(EvalStateKeys.Table.CONFIRMATIONS, confirmKey);
        Long monitorId = item.ctxExtra() != null && item.ctxExtra().get("monitor_id") instanceof Number n
                ? n.longValue() : null;
        long now = clock.getAsLong();
        String hash = EvalStateKeys.keyHash(confirmKey);
        jdbc.update("DELETE FROM monitor_confirmations WHERE key_hash = ?", hash);
        jdbc.update("INSERT INTO monitor_confirmations (key_hash, confirm_key, chain_id, alert_type, domain, detail, monitor_type, "
                        + "monitor_id, attempt, total_attempts, delay_ms, first_failure_at, next_attempt_ms, attempts_json, ctx_json, "
                        + "ctx_truncated, state, created_ms, writer, gen, seq, updated_ms, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                hash, confirmKey, chainId, item.alertType(), item.domain(), item.detail(), monitorType(item.alertType()),
                monitorId, state.attempt(), state.totalAttempts(), delayMs, firstFailureAt, state.nextAttemptAtMs(), null,
                null, false, "RUNNING", now, writer, generation, s, now, iso(now));
    }

    @Override
    public void chainProgress(String confirmKey, long chainId, ConfirmState state, List<Map<String, Object>> attempts) {
        if (!recording) return;
        long s = next(EvalStateKeys.Table.CONFIRMATIONS, confirmKey);
        long now = clock.getAsLong();
        jdbc.update("UPDATE monitor_confirmations SET attempt = ?, next_attempt_ms = ?, attempts_json = ?, seq = ?, "
                        + "updated_ms = ?, updated_at = ? WHERE key_hash = ? AND chain_id = ?",
                state.attempt(), state.nextAttemptAtMs(), "n=" + attempts.size(), s, now, iso(now),
                EvalStateKeys.keyHash(confirmKey), chainId);
    }

    @Override
    public void chainFinishing(String confirmKey, long chainId, int lockTry) {
        if (!recording) return;
        long s = next(EvalStateKeys.Table.CONFIRMATIONS, confirmKey);
        long now = clock.getAsLong();
        jdbc.update("UPDATE monitor_confirmations SET state = 'FINISHING', seq = ?, updated_ms = ?, updated_at = ? "
                + "WHERE key_hash = ? AND chain_id = ?", s, now, iso(now), EvalStateKeys.keyHash(confirmKey), chainId);
    }

    @Override
    public void chainEnd(String confirmKey, long chainId) {
        if (!recording) return;
        next(EvalStateKeys.Table.CONFIRMATIONS, confirmKey);
        jdbc.update("DELETE FROM monitor_confirmations WHERE key_hash = ? AND chain_id = ? AND writer = ? AND gen = ?",
                EvalStateKeys.keyHash(confirmKey), chainId, writer, generation);
    }

    @Override
    public void sweepEnd(String alertType) {
        // eşzamanlı yazım — boşaltacak bir şey yok
    }

    @Override
    public void bindSource(EvalStateSource source) {
        this.source = source;
    }

    @Override
    public Status status() {
        return null;
    }

    /** Betimleyici sütunu — gerçek kaydedicinin eşlemesi. */
    static String monitorType(String alertType) {
        return RecheckRefs.monitorType(alertType);
    }

    // ── EvalStateVerification.Context ─────────────────────────────────────────────────────────────────────────────

    @Override
    public String writer() {
        return writer;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public long generationStartMs() {
        return generationStartMs;
    }

    @Override
    public long nowMs() {
        return clock.getAsLong();
    }

    @Override
    public long seq() {
        return seq.get();
    }

    @Override
    public boolean awaitFlushed(long s, long timeoutMs) {
        return flushed;
    }

    @Override
    public boolean changedSince(EvalStateKeys.Table table, String key, long s) {
        String k = table.name() + "|" + key;
        if (dirty.contains(k)) return true;
        Long last = lastSeq.get(k);
        long gap = gapSeq;
        if (last == null) return gap > 0;
        if (last > s) return true;
        return gap > 0 && last <= gap;
    }

    @Override
    public long lastStatusRefreshMs() {
        return 120_000L;
    }
}
