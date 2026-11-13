package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Çalışan SQL Playground sorgularının kaydı ve durdurma (2026-11-13, A2).
 *
 * <p><b>Aynı pod.</b> Çalışan sorgunun {@link Statement}'ı bellekte tutulur; durdurma {@link Statement#cancel()}.
 *
 * <p><b>Başka pod.</b> Sorgu başlarken {@code sql_running_queries} tablosuna bir satır yazılır (run_id, sahibi, pod örneği,
 * PostgreSQL arka uç pid'i ve işlem başına {@code SET LOCAL application_name} etiketi). Başka bir pod'a düşen durdurma isteği
 * satırı okur, {@code cancel_requested} işaretler ve {@code pg_cancel_backend(pid)}'i YALNIZ {@code pg_stat_activity}'de o
 * pid'in uygulama adı kayıttaki etiketle AYNIYSA çağırır — havuza dönüp başka bir işe verilmiş bağlantı asla durdurulmaz
 * (etiket {@code SET LOCAL}: işlem bitince kendiliğinden düşer). Satır sorgu bitince silinir; {@link #STALE_MS}'den eski
 * kalıntıyı (pod öldü) bir sonraki başlangıç temizler.
 *
 * <p>Yalnız sorguyu başlatan kullanıcı durdurabilir; başkasının run_id'si "bulunamadı" ile aynı yanıtı alır. Tablo yoksa ya da
 * okunamıyorsa yalnız aynı pod durdurması çalışır (sorgu yine koşar). Her deyim kendi 3 sn zaman aşımlı şablonunda.
 */
@Slf4j
@Component
public class SqlRunRegistry {

    /** Bayat kayıt eşiği: son tazelemeden ({@code heartbeat_ms}, yoksa başlangıç) bu yana — ölen pod'un kalıntısı. */
    public static final long STALE_MS = 180_000L;
    /** Bayat temizliği en çok bu sıklıkta. */
    static final long STALE_SWEEP_EVERY_MS = 60_000L;
    /** Biten çalıştırma belleği (aynı pod "bitti" yanıtı için). */
    static final int FINISHED_MEMORY = 1000;
    /** Çalışan sorgunun satırı bu aralıkla tazelenir ({@code heartbeat_ms}) — uzun dışa aktarım bayat sayılmaz. */
    static final long HEARTBEAT_MS = 30_000L;
    /** Okuma döngüsünde başka pod'un durdurma işareti en çok bu aralıkla okunur. */
    static final long DB_CANCEL_CHECK_MS = 2_000L;
    /** Süre sınırı bekçisi: sürücünün kendi zaman aşımına (yalnız ilk çalıştırma) pay. */
    static final long WATCHDOG_GRACE_MS = 1_000L;

    /**
     * Süre sınırı ve canlılık bekçisi — tek, daemon iş parçacığı. pgjdbc {@code setQueryTimeout}'u yalnız ilk
     * {@code execute}'ta kurar; imleçli okumada sonraki getirmeler ({@code ResultSet.next()}) sayaçsızdır ve
     * {@code PgStatement.cancel()} ifade boşta iken hiçbir şey yapmaz. Bekçi süre dolunca çalıştırmayı "zaman aşımı" işaretler,
     * ifadeyi iptal eder ve PostgreSQL'de arka ucu etiket eşleşmesiyle durdurur.
     */
    private static volatile java.util.concurrent.ScheduledThreadPoolExecutor watchdog;

    public static final String REASON_NOT_FOUND = "not_found";
    public static final String REASON_FINISHED = "finished";
    public static final String REASON_UNREACHABLE = "other_pod_unreachable";

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, Run> local = new ConcurrentHashMap<>();
    private final Map<String, String> finished = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > FINISHED_MEMORY; }
    };
    private volatile long lastStaleSweep;
    private volatile Boolean postgres;
    private final String instanceId;
    private final String pod;
    private LongSupplier clock = System::currentTimeMillis;

    @Autowired
    public SqlRunRegistry(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, SchedulerService.instanceId(), SchedulerService.hostname());
    }

    /** Testler: başka bir pod örneğini taklit eder. */
    public SqlRunRegistry(JdbcTemplate jdbcTemplate, String instanceId, String pod) {
        JdbcTemplate t = jdbcTemplate;
        if (jdbcTemplate != null && jdbcTemplate.getDataSource() != null) {
            t = new JdbcTemplate(jdbcTemplate.getDataSource());
            t.setQueryTimeout(3);
        }
        this.jdbc = t;
        this.instanceId = instanceId;
        this.pod = pod;
    }

    void setClock(LongSupplier clock) { this.clock = clock; }

    /** Bir çalıştırma. Durdurma bayrağı ve çalışan ifade aynı kilit altında (yarış: ifade bağlanmadan durdurma). */
    public static final class Run {
        private final String runId;
        private final String owner;
        private final String kind;
        private final String fingerprint;
        private final long startedMs;
        private final String appName;
        private Statement statement;
        private boolean cancelRequested;
        private boolean dbRow;
        private volatile Integer pid;
        private volatile boolean timedOut;
        private volatile long lastDbCheckMs;
        private java.util.concurrent.ScheduledFuture<?> timer;
        private java.util.concurrent.ScheduledFuture<?> beat;

        Run(String runId, String owner, String kind, String fingerprint, long startedMs) {
            this.runId = runId;
            this.owner = owner;
            this.kind = kind;
            this.fingerprint = fingerprint;
            this.startedMs = startedMs;
            this.appName = appNameOf(runId);
        }

        public String runId() { return runId; }
        public String owner() { return owner; }
        public String appName() { return appName; }
        public synchronized boolean cancelRequested() { return cancelRequested; }
        /** Süre sınırı aşıldı (bekçi ya da okuma döngüsü). */
        public boolean timedOut() { return timedOut; }

        synchronized void requestCancel() {
            cancelRequested = true;
            if (statement != null) {
                try {
                    statement.cancel();
                } catch (Exception e) {
                    log.debug("SQL Playground: ifade durdurulamadı ({}): {}", runId, e.toString());
                }
            }
        }

        /** İfadeyi bağlar; durdurma zaten istendiyse {@link CancelledException}. */
        synchronized void bind(Statement st) {
            if (cancelRequested) throw new CancelledException();
            statement = st;
        }

        synchronized void unbind() { statement = null; }

        /** Bekçi: hâlâ bağlıysa zaman aşımı işaretler ve ifadeyi iptal eder; bağlı değilse (bitti) false. */
        synchronized boolean expire() {
            if (statement == null) return false;
            timedOut = true;
            try {
                statement.cancel();
            } catch (Exception e) {
                log.debug("SQL Playground: ifade süre sınırında durdurulamadı ({}): {}", runId, e.toString());
            }
            return true;
        }

        synchronized boolean bound() { return statement != null; }
    }

    /** Süre sınırı aşıldı — sorgu durduruldu (zaman aşımı yanıtı). */
    public static final class TimedOutException extends RuntimeException {
        public TimedOutException() { super("timed out", null, false, false); }
    }

    /** Durdurma istendiği için sorgu başlamadı. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() { super("cancelled", null, false, false); }
    }

    /** Durdurma sonucu. */
    public record CancelResult(boolean cancelled, String reason) {}

    // ── Yaşam döngüsü ─────────────────────────────────────────────────────────────────────────────────────────

    /** Kararlı, güvenli çalıştırma kimliği: {@code [A-Za-z0-9-]{8,64}}. */
    public static boolean validRunId(String id) {
        if (id == null) return false;
        int n = id.length();
        if (n < 8 || n > 64) return false;
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Çalıştırmayı başlatır (bellek). İstemci kimliği yoksa üretilir; geçersizse 400 alan {@code run_id}; aynı kimlik
     * zaten çalışıyorsa 409 {@code SQL_RUN_ID_IN_USE}.
     */
    public Run start(String requestedId, String owner, String kind, String fingerprint) {
        String id = requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId.trim();
        if (!validRunId(id)) {
            throw new FieldValidationException("run_id", Msg.t(
                    "Çalıştırma kimliği 8–64 karakter olmalı ve yalnız harf, rakam ve tire içermeli.",
                    "The run id must be 8–64 characters of letters, digits and dashes."));
        }
        Run r = new Run(id, owner, kind, fingerprint, clock.getAsLong());
        if (local.putIfAbsent(id, r) != null) {
            throw new CodedConflictException("SQL_RUN_ID_IN_USE", Msg.t(
                    "Bu çalıştırma kimliğiyle bir sorgu zaten çalışıyor. Yeni bir kimlikle yeniden deneyin.",
                    "A query with this run id is already running. Try again with a new id."));
        }
        sweepStale();
        return r;
    }

    /**
     * İfade hazır: belleğe bağlar ve (PostgreSQL pid'i biliniyorsa) kaydı tabloya yazar — başka pod durdurabilsin.
     * Durdurma zaten istendiyse {@link CancelledException}.
     */
    public void attach(Run r, Statement st, Integer pid) {
        attach(r, st, pid, 0);
    }

    /**
     * {@link #attach(Run, Statement, Integer)} + süre sınırı bekçisi ({@code timeoutSec} > 0: başlangıçtan
     * {@code timeoutSec} sn + pay sonra zaman aşımı) + satır tazeleme ({@link #HEARTBEAT_MS}).
     */
    public void attach(Run r, Statement st, Integer pid, int timeoutSec) {
        r.bind(st);
        r.pid = pid;
        if (timeoutSec > 0) {
            long delay = Math.max(0L, r.startedMs + timeoutSec * 1000L + WATCHDOG_GRACE_MS - clock.getAsLong());
            try {
                synchronized (r) {
                    r.timer = watchdog().schedule(() -> onTimeout(r), delay, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                log.debug("SQL Playground: süre bekçisi kurulamadı: {}", e.toString());
            }
        }
        if (jdbc == null) return;
        try {
            jdbc.update("INSERT INTO sql_running_queries (run_id, owner_username, instance_id, pod, backend_pid, app_name, "
                            + "kind, sql_fingerprint, started_ms, started_at, cancel_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    r.runId, r.owner, instanceId, pod, pid, r.appName, r.kind, r.fingerprint, r.startedMs,
                    Instant.ofEpochMilli(r.startedMs).toString(), Boolean.FALSE);
            r.dbRow = true;
            try {
                synchronized (r) {
                    r.beat = watchdog().scheduleWithFixedDelay(() -> heartbeat(r), HEARTBEAT_MS, HEARTBEAT_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                log.debug("SQL Playground: satır tazeleme kurulamadı: {}", e.toString());
            }
        } catch (DuplicateKeyException e) {
            // Aynı kimlik başka bir pod'da koşuyor — kayıt sahibi o; bu çalıştırma yine koşar (yalnız aynı pod durdurur)
            log.debug("SQL Playground: run_id başka pod'da kayıtlı: {}", r.runId);
        } catch (Exception e) {
            log.debug("SQL Playground: çalışan sorgu kaydı yazılamadı: {}", e.toString());
        }
    }

    /** Durdurma istendi mi (bellek bayrağı ya da başka pod'un tablodaki işareti)? Yalnız iptal hatasında sorulur. */
    public boolean cancelRequested(Run r) {
        if (r.cancelRequested()) return true;
        if (!r.dbRow || jdbc == null) return false;
        try {
            List<Boolean> v = jdbc.queryForList("SELECT cancel_requested FROM sql_running_queries WHERE run_id = ?",
                    Boolean.class, r.runId);
            return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Okuma döngüsünden her satırda çağrılır: durdurma istendiyse {@link CancelledException}, süre dolduysa
     * {@link TimedOutException}; başka pod'un tablodaki durdurma işareti en çok {@link #DB_CANCEL_CHECK_MS}'de bir okunur.
     * Bellek denetimi ucuzdur (satır başına iki uçucu okuma).
     */
    public void checkContinue(Run r, long deadlineMs) {
        if (r.cancelRequested()) throw new CancelledException();
        long now = clock.getAsLong();
        if (r.timedOut || now > deadlineMs) {
            r.timedOut = true;
            throw new TimedOutException();
        }
        if (r.dbRow && jdbc != null && now - r.lastDbCheckMs >= DB_CANCEL_CHECK_MS) {
            r.lastDbCheckMs = now;
            try {
                List<Boolean> v = jdbc.queryForList("SELECT cancel_requested FROM sql_running_queries WHERE run_id = ?",
                        Boolean.class, r.runId);
                if (!v.isEmpty() && Boolean.TRUE.equals(v.get(0))) {
                    r.requestCancel();
                    throw new CancelledException();
                }
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                log.debug("SQL Playground: durdurma işareti okunamadı: {}", e.toString());
            }
        }
    }

    /**
     * İfade kapatılmadan / bağlantı bırakılmadan ÖNCE: bekçi ve tazeleme durur, ifade çözülür — bırakılan bağlantıda
     * hiçbir iptal gönderilmez.
     */
    public void detach(Run r) {
        synchronized (r) {
            if (r.timer != null) r.timer.cancel(false);
            if (r.beat != null) r.beat.cancel(false);
            r.timer = null;
            r.beat = null;
        }
        r.unbind();
    }

    /** Çalıştırma bitti: bellek + tablo satırı silinir (finally). */
    public void finish(Run r) {
        detach(r);
        local.remove(r.runId, r);
        synchronized (finished) { finished.put(r.runId, r.owner); }
        if (r.dbRow && jdbc != null) {
            try {
                jdbc.update("DELETE FROM sql_running_queries WHERE run_id = ? AND instance_id = ?", r.runId, instanceId);
            } catch (Exception e) {
                log.debug("SQL Playground: çalışan sorgu kaydı silinemedi: {}", e.toString());
            }
        }
    }

    // ── Durdurma ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Yalnız sahibi durdurabilir; başkasının kimliği "bulunamadı" ile aynı yanıtı alır. */
    public CancelResult cancel(String runId, String owner) {
        if (!validRunId(runId) || owner == null) return new CancelResult(false, REASON_NOT_FOUND);
        Run r = local.get(runId);
        if (r != null) {
            if (!owner.equals(r.owner)) return new CancelResult(false, REASON_NOT_FOUND);
            boolean bound = r.bound();
            r.requestCancel();
            // PostgreSQL: imleçli okumada ifade boşta görünür ve Statement.cancel() bir şey yapmaz — sürmekte olan
            // getirmeyi arka uç iptaliyle kes (etiket eşleşmesi: bırakılmış bağlantıya iptal gitmez)
            if (bound) pgCancelOwn(r);
            return new CancelResult(true, null);
        }
        synchronized (finished) {
            String o = finished.get(runId);
            if (o != null) return new CancelResult(false, owner.equals(o) ? REASON_FINISHED : REASON_NOT_FOUND);
        }
        if (jdbc == null) return new CancelResult(false, REASON_NOT_FOUND);
        Map<String, Object> row;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT owner_username, instance_id, backend_pid, app_name FROM sql_running_queries WHERE run_id = ?", runId);
            if (rows.isEmpty()) return new CancelResult(false, REASON_NOT_FOUND);
            row = rows.get(0);
        } catch (Exception e) {
            log.debug("SQL Playground: çalışan sorgu kaydı okunamadı: {}", e.toString());
            return new CancelResult(false, REASON_NOT_FOUND);
        }
        if (!owner.equals(String.valueOf(row.get("owner_username")))) return new CancelResult(false, REASON_NOT_FOUND);
        if (instanceId != null && instanceId.equals(String.valueOf(row.get("instance_id")))) {
            return new CancelResult(false, REASON_FINISHED);   // bu pod'un kaydı ama bellekte yok → az önce bitti
        }
        Object pidObj = row.get("backend_pid");
        String app = row.get("app_name") == null ? null : String.valueOf(row.get("app_name"));
        try {
            jdbc.update("UPDATE sql_running_queries SET cancel_requested = ? WHERE run_id = ?", Boolean.TRUE, runId);
        } catch (Exception e) {
            log.debug("SQL Playground: durdurma işareti yazılamadı: {}", e.toString());
        }
        if (!(pidObj instanceof Number pid) || app == null || !isPostgres()) return new CancelResult(false, REASON_UNREACHABLE);
        try {
            List<Boolean> res = jdbc.queryForList("SELECT pg_cancel_backend(pid) FROM pg_stat_activity "
                    + "WHERE pid = ? AND application_name = ?", Boolean.class, pid.intValue(), app);
            if (res.isEmpty()) return new CancelResult(false, REASON_FINISHED);
            return Boolean.TRUE.equals(res.get(0)) ? new CancelResult(true, null) : new CancelResult(false, REASON_UNREACHABLE);
        } catch (Exception e) {
            log.debug("SQL Playground: başka pod'daki sorgu durdurulamadı: {}", e.toString());
            return new CancelResult(false, REASON_UNREACHABLE);
        }
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────

    /** Bekçi: süre doldu — zaman aşımı işareti, ifade iptali, PostgreSQL'de arka uç iptali. */
    private void onTimeout(Run r) {
        if (!r.expire()) return;
        log.info("SQL Playground: sorgu süre sınırında durduruluyor (run={}, tür={})", r.runId, r.kind);
        pgCancelOwn(r);
    }

    /** Bu pod'un çalıştırmasının arka ucunu durdurur — yalnız {@code application_name} etiketi hâlâ eşleşiyorsa. */
    private void pgCancelOwn(Run r) {
        Integer pid = r.pid;
        if (pid == null || jdbc == null || !isPostgres()) return;
        try {
            jdbc.queryForList("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE pid = ? AND application_name = ?",
                    Boolean.class, pid, r.appName);
        } catch (Exception e) {
            log.debug("SQL Playground: arka uç durdurulamadı ({}): {}", r.runId, e.toString());
        }
    }

    /** Çalışan sorgunun satırını tazeler — bayat temizliği uzun süren dışa aktarımı silmez. */
    private void heartbeat(Run r) {
        if (!r.dbRow || jdbc == null || !r.bound()) return;
        try {
            jdbc.update("UPDATE sql_running_queries SET heartbeat_ms = ? WHERE run_id = ? AND instance_id = ?",
                    clock.getAsLong(), r.runId, instanceId);
        } catch (Exception e) {
            log.debug("SQL Playground: çalışan sorgu satırı tazelenemedi: {}", e.toString());
        }
    }

    private static java.util.concurrent.ScheduledThreadPoolExecutor watchdog() {
        java.util.concurrent.ScheduledThreadPoolExecutor w = watchdog;
        if (w != null) return w;
        synchronized (SqlRunRegistry.class) {
            if (watchdog == null) {
                java.util.concurrent.ScheduledThreadPoolExecutor x = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
                    Thread t = new Thread(task, "sqlpg-watchdog");
                    t.setDaemon(true);
                    return t;
                });
                x.setRemoveOnCancelPolicy(true);
                watchdog = x;
            }
            return watchdog;
        }
    }

    private void sweepStale() {
        long now = clock.getAsLong();
        if (jdbc == null || now - lastStaleSweep < STALE_SWEEP_EVERY_MS) return;
        lastStaleSweep = now;
        try {
            jdbc.update("DELETE FROM sql_running_queries WHERE COALESCE(heartbeat_ms, started_ms) < ?", now - STALE_MS);
        } catch (Exception e) {
            log.debug("SQL Playground: bayat çalışan sorgu kayıtları silinemedi: {}", e.toString());
        }
    }

    private boolean isPostgres() {
        Boolean p = postgres;
        if (p != null) return p;
        try {
            p = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) con ->
                    con.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres"));
            postgres = p;
            return Boolean.TRUE.equals(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code SET LOCAL application_name} etiketi — kısa (PostgreSQL 63 bayt), kimliğe bağlı, tahmin edilemez değil ama benzersiz. */
    public static String appNameOf(String runId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(runId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sm-sqlpg-");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "sm-sqlpg-" + Integer.toHexString(runId.hashCode());
        }
    }

    /** Test / tanılama: bu pod'da çalışan çalıştırma sayısı. */
    int localCount() { return local.size(); }
}
