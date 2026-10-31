package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.util.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JDBC çalıştırıcı (sözleşme §2b, §2c): bağlantı alanlardan kurulur (URL kullanıcıdan alınmaz), salt-okur oturum, zaman aşımı,
 * satır sınırı, iptal bekçisi, her durumda rollback + kapat. Havuz YOK. Asla fırlatmaz (hata {@code ok=false} + kod).
 *
 * <p>Katmanlar: (1) ana makine {@link SsrfGuard} ile doğrulanır, doğrulanan IP'ye bağlanılır; (2) sürücü özellikleri
 * ({@link DbJdbcUrls}) bağlantı / okuma zaman aşımı ve salt-okur niyet taşır; (3) oturum SİSTEMİN ifadeleriyle salt-okur
 * kurulur — PostgreSQL {@code SET TRANSACTION READ ONLY} + {@code SET LOCAL statement_timeout / lock_timeout} +
 * {@code standard_conforming_strings = on} (güvenlik denetimi düz metni böyle okur); MSSQL {@code READ UNCOMMITTED},
 * {@code SET LOCK_TIMEOUT 2000}, {@code SET DEADLOCK_PRIORITY LOW}; Oracle {@code SET TRANSACTION READ ONLY};
 * (4) ifade ileri-yalnız + salt-okur, JDBC kaçış işleme KAPALI, {@code setQueryTimeout}, {@code setMaxRows(max + 1)};
 * (5) bekçi: zaman aşımı + {@value #CANCEL_GRACE_SECONDS} sn'de {@code cancel}, + {@value #ABORT_GRACE_SECONDS} sn'de
 * {@code abort}; (6) en çok {@value #MAX_COLUMNS} kolon, hücre ≤ {@value #MAX_CELL_CHARS} karakter, toplam bellek bütçesi
 * {@value #MEMORY_BUDGET_CHARS} karakter (aşılınca satırlar sayılır ama saklanmaz).
 *
 * <p>Hata metni maskelenir (parola, kullanıcı adı, {@code password=}) ve ≤ 500 karakterdir. Sorgu hatalarında ayrıca
 * VERİ temizlenir (bağımsız inceleme Y1): sürücü iletisinin yalnız ilk satırı, PostgreSQL {@code DETAIL / HINT / Where /
 * Position} satırları atılır, kullanıcının SQL'inde geçmeyen her tırnaklı parça {@code …} olur (ör. {@code CAST((SELECT
 * gizli) AS integer)} değeri hata metnine taşınamaz), ≤ {@value DbQueryErrors#MAX_QUERY_ERROR_CHARS} karakter; SQLSTATE
 * korunur.
 *
 * <p>PostgreSQL {@code verify-full}: sertifika ana makine ADIYLA doğrulanır ama soket {@link PinnedAddressSocketFactory} ile
 * SSRF'den geçmiş IP'ye kilitlenir — sürücü adı yeniden çözse de başka adrese gidemez (DNS yeniden bağlama, inceleme O2).
 *
 * <p><b>Bilinen sınır (inceleme O4 / O5):</b> salt-okur oturum ve güvenlik denetimi, hesabın EXECUTE edebildiği bir
 * fonksiyonun kendi (otonom) işlemiyle yazmasını ya da sayısal ölçümün dar bir veri kanalı olarak kullanılmasını tümüyle
 * engelleyemez. İzleme hesabı EN AZ AYRICALIKLI, EXECUTE yetkisi OLMAYAN salt-okur bir hesap OLMALIDIR.
 */
@Service
public class DbQueryRunner {

    private static final Logger log = LoggerFactory.getLogger(DbQueryRunner.class);

    static final int MAX_COLUMNS = 100, MAX_CELL_CHARS = 4_000;
    static final long MEMORY_BUDGET_CHARS = 8_000_000L;
    static final long CANCEL_GRACE_SECONDS = 5, ABORT_GRACE_SECONDS = 15;
    static final long BINARY_COUNT_CAP = 16L << 20;
    static final int TEST_TIMEOUT_SECONDS = 10, SETUP_TIMEOUT_SECONDS = 10;
    static final int MAX_PLAN_CHARS = 4_000_000;

    /** Bağlanıcı tohumu — üretimde {@link DriverManager} sürücüsü, testlerde sahte. */
    @FunctionalInterface
    interface Connector {
        Connection connect(String url, Properties props) throws SQLException;
    }

    /** Sürücü bulunamadı / bağlantıyı kabul etmedi — SQL hatası değil (sınıflandırma DB_DRIVER). */
    static final class DriverUnavailableException extends RuntimeException {
        DriverUnavailableException(String m) { super(m); }
    }

    /** Bağlantı tanımı geçersiz (ad, port, ssl kipi) — sınıflandırma DB_DRIVER. */
    static final class InvalidSpecException extends RuntimeException {
        InvalidSpecException(String m) { super(m); }
    }

    private static final ScheduledExecutorService WATCHDOG = watchdog();
    private static final ExecutorService ABORTER =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dbquery-abort-", 0).factory());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param type JDBC tür adı (VARCHAR, NUMERIC, TIMESTAMP …). */
    public record Column(String name, String type) {}

    /**
     * @param rows             okunan satırlar (≤ maxRows; hücre: sayı → BigDecimal, mantıksal → Boolean, diğer → String
     *                         (tarih / saat ISO-8601), ikili → "&lt;binary N bytes&gt;", null); hücre ≤ 4000 karakter
     * @param rowCount         okunan satır sayısı (≤ maxRows)
     * @param rowCountCapped   sorgu maxRows'tan fazla satır döndürdü ("≥ rowCount")
     * @param valuesTruncated  bellek bütçesi doldu: sayılan ama saklanmayan satır var / hücre kısaltıldı / 100'den çok kolon
     * @param failureReason    DB_UNREACHABLE | DB_DNS | DB_TIMEOUT | DB_AUTH | DB_SQL_ERROR | DB_DRIVER | DB_BLOCKED | null
     * @param error            maskeli kısa hata metni (≤ 500) ya da null
     */
    public record QueryResult(boolean ok, long durationMs, int rowCount, boolean rowCountCapped, List<Column> columns,
                              List<List<Object>> rows, boolean valuesTruncated, String error, String failureReason,
                              String serverVersion) {}

    public record ConnectionTest(boolean ok, long durationMs, String serverVersion, String error, String failureReason) {}

    /** @param supported false = bu tür için plan okunamaz (Oracle); cost / rows planın tahmini. */
    public record PlanResult(boolean supported, boolean ok, Double cost, Double rows, String error) {}

    private final SsrfGuard ssrf;
    private final Connector connector;
    /** Bekçi payları (ms) — testler kısaltır. */
    private volatile long cancelGraceMs = CANCEL_GRACE_SECONDS * 1_000L, abortGraceMs = ABORT_GRACE_SECONDS * 1_000L;

    @Autowired
    public DbQueryRunner(SsrfGuard ssrf) {
        this(ssrf, DbQueryRunner::driverConnect);
    }

    DbQueryRunner(SsrfGuard ssrf, Connector connector) {
        this.ssrf = ssrf;
        this.connector = connector;
    }

    /** Testler: bekçi paylarını kısaltır. */
    void setWatchdogGraceForTests(long cancelMs, long abortMs) {
        this.cancelGraceMs = cancelMs;
        this.abortGraceMs = abortMs;
    }

    /** Tek daemon iş parçacığı; iptal edilen (zamanında biten) görevler kuyruktan hemen düşer — birikmez. */
    private static ScheduledExecutorService watchdog() {
        java.util.concurrent.ScheduledThreadPoolExecutor ex = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "dbquery-watchdog");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /** Üretim bağlanıcısı: küresel {@code DriverManager.setLoginTimeout} KULLANILMAZ — zaman aşımları özelliklerde. */
    static Connection driverConnect(String url, Properties props) throws SQLException {
        Driver d;
        try {
            d = DriverManager.getDriver(url);
        } catch (SQLException e) {
            throw new DriverUnavailableException("JDBC sürücüsü bulunamadı");
        }
        Connection c = d.connect(url, props);
        if (c == null) throw new DriverUnavailableException("JDBC sürücüsü bağlantı adresini kabul etmedi");
        return c;
    }

    // ── Çalıştırma ───────────────────────────────────────────────────────────

    public QueryResult run(DbConnectionSpec spec, String sql, int timeoutSeconds, int maxRows) {
        return run(spec, sql, timeoutSeconds, maxRows, Set.of());
    }

    /**
     * @param discardColumns değeri hiç OKUNMAYACAK kolonlar (küçük harf) — maskeli olup hiçbir kuralın kullanmadığı kolonlar;
     *                       değer belleğe bile alınmaz, hücre null kalır
     */
    public QueryResult run(DbConnectionSpec spec, String sql, int timeoutSeconds, int maxRows, Set<String> discardColumns) {
        int timeout = Math.max(1, timeoutSeconds);
        int limit = Math.max(1, maxRows);
        Session s;
        try {
            s = open(spec, timeout);
        } catch (Throwable e) {
            return failed(spec, e, DbQueryErrors.Phase.CONNECT, false, 0, sql);
        }
        long t0 = System.nanoTime();
        try {
            String exec = DbQuerySqlGuard.executableSql(sql, spec.type());
            try (Statement st = s.conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                s.stmt = st;
                st.setEscapeProcessing(false);
                st.setQueryTimeout(timeout);
                st.setMaxRows(limit + 1);
                st.setFetchSize(Math.min(limit + 1, 500));
                try (ResultSet rs = st.executeQuery(exec)) {
                    Read r = read(rs, limit, discardColumns == null ? Set.of() : discardColumns);
                    long ms = elapsedMs(t0);
                    return new QueryResult(true, ms, r.count, r.capped, List.copyOf(r.columns), r.rows, r.truncated,
                            null, null, s.version);
                }
            }
        } catch (Throwable e) {
            return failed(spec, e, DbQueryErrors.Phase.QUERY, s.fired, elapsedMs(t0), sql);
        } finally {
            s.close();
        }
    }

    /** Bağlantı denemesi: bağlan + {@code SELECT 1} (Oracle {@code FROM dual}) + sunucu sürümü. */
    public ConnectionTest test(DbConnectionSpec spec) {
        long t0 = System.nanoTime();
        Session s;
        try {
            s = open(spec, TEST_TIMEOUT_SECONDS);
        } catch (Throwable e) {
            QueryResult f = failed(spec, e, DbQueryErrors.Phase.CONNECT, false, 0, null);
            return new ConnectionTest(false, elapsedMs(t0), null, f.error(), f.failureReason());
        }
        try (Statement st = s.conn.createStatement()) {
            s.stmt = st;
            st.setEscapeProcessing(false);
            st.setQueryTimeout(TEST_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(spec.type() == DbType.ORACLE ? "SELECT 1 FROM dual" : "SELECT 1")) {
                rs.next();
            }
            return new ConnectionTest(true, elapsedMs(t0), s.version, null, null);
        } catch (Throwable e) {
            QueryResult f = failed(spec, e, DbQueryErrors.Phase.QUERY, s.fired, 0, null);
            return new ConnectionTest(false, elapsedMs(t0), s.version, f.error(), f.failureReason());
        } finally {
            s.close();
        }
    }

    /**
     * Maliyet ön kontrolü (sorgu ÇALIŞTIRILMAZ): PostgreSQL {@code EXPLAIN (FORMAT JSON)} → Total Cost / Plan Rows; MSSQL
     * {@code SET SHOWPLAN_XML ON} → StatementSubTreeCost / StatementEstRows (en büyük ifade); Oracle desteklenmez.
     * Çağıran sorguyu önce güvenlik denetiminden geçirir.
     */
    public PlanResult plan(DbConnectionSpec spec, String sql, int timeoutSeconds) {
        if (spec == null || spec.type() == null) return new PlanResult(false, false, null, null, null);
        if (spec.type() == DbType.ORACLE) return new PlanResult(false, false, null, null, null);
        int timeout = Math.max(1, timeoutSeconds);
        Session s;
        try {
            s = open(spec, timeout);
        } catch (Throwable e) {
            return new PlanResult(true, false, null, null, failed(spec, e, DbQueryErrors.Phase.CONNECT, false, 0, sql).error());
        }
        try {
            String exec = DbQuerySqlGuard.executableSql(sql, spec.type());
            if (spec.type() == DbType.POSTGRESQL) {
                try (Statement st = s.conn.createStatement()) {
                    s.stmt = st;
                    st.setEscapeProcessing(false);
                    st.setQueryTimeout(timeout);
                    try (ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + exec)) {
                        if (!rs.next()) return new PlanResult(true, false, null, null, null);
                        return parsePgPlan(clip(rs.getString(1), MAX_PLAN_CHARS));
                    }
                }
            }
            try (Statement on = s.conn.createStatement()) {
                s.stmt = on;
                on.setEscapeProcessing(false);
                on.setQueryTimeout(timeout);
                on.execute("SET SHOWPLAN_XML ON");
            }
            try (Statement st = s.conn.createStatement()) {
                s.stmt = st;
                st.setEscapeProcessing(false);
                st.setQueryTimeout(timeout);
                try (ResultSet rs = st.executeQuery(exec)) {
                    if (!rs.next()) return new PlanResult(true, false, null, null, null);
                    return parseShowplan(clip(rs.getString(1), MAX_PLAN_CHARS));
                }
            } finally {
                try (Statement off = s.conn.createStatement()) {
                    off.setQueryTimeout(SETUP_TIMEOUT_SECONDS);
                    off.execute("SET SHOWPLAN_XML OFF");
                } catch (Throwable ignored) {
                    // bağlantı zaten kapanacak — oturum ayarı bağlantıyla ölür
                }
            }
        } catch (Throwable e) {
            return new PlanResult(true, false, null, null, failed(spec, e, DbQueryErrors.Phase.QUERY, s.fired, 0, sql).error());
        } finally {
            s.close();
        }
    }

    // ── Oturum ───────────────────────────────────────────────────────────────

    /** Açık bağlantı + bekçi. {@link #close} her durumda rollback + kapat; bekçiyi durdurur. */
    private static final class Session {
        final Connection conn;
        volatile Statement stmt;
        volatile boolean fired;
        String version;
        ScheduledFuture<?> cancelTask, abortTask;

        Session(Connection conn) { this.conn = conn; }

        void arm(int timeoutSeconds, long cancelGraceMs, long abortGraceMs) {
            long base = timeoutSeconds * 1_000L;
            cancelTask = WATCHDOG.schedule(() -> {
                fired = true;
                Statement st = stmt;
                if (st != null) {
                    try { st.cancel(); } catch (Throwable ignored) { /* bekçi asla fırlatmaz */ }
                }
            }, base + cancelGraceMs, TimeUnit.MILLISECONDS);
            abortTask = WATCHDOG.schedule(() -> {
                fired = true;
                try { conn.abort(ABORTER); } catch (Throwable ignored) { /* bekçi asla fırlatmaz */ }
            }, base + abortGraceMs, TimeUnit.MILLISECONDS);
        }

        void close() {
            if (cancelTask != null) cancelTask.cancel(false);
            if (abortTask != null) abortTask.cancel(false);
            try { conn.rollback(); } catch (Throwable ignored) { /* kapalı / iptal edilmiş bağlantı */ }
            try { conn.close(); } catch (Throwable ignored) { /* kapalı / iptal edilmiş bağlantı */ }
        }
    }

    private Session open(DbConnectionSpec spec, int timeoutSeconds) throws SQLException {
        if (spec == null || spec.type() == null) throw new InvalidSpecException("bağlantı türü yok");
        List<InetAddress> addrs = ssrf.validate(spec.host());
        if (addrs == null || addrs.isEmpty()) throw new SsrfGuard.UnresolvableHostException(SsrfGuard.UNRESOLVABLE_PREFIX + spec.host());
        DbJdbcUrls.Target target;
        try {
            target = DbJdbcUrls.build(spec, addrs.get(0), timeoutSeconds);
        } catch (IllegalArgumentException e) {
            throw new InvalidSpecException(e.getMessage());
        }
        Connection c = connector.connect(target.url(), target.props());
        if (c == null) throw new DriverUnavailableException("JDBC sürücüsü bağlantı adresini kabul etmedi");
        Session s = new Session(c);
        s.arm(timeoutSeconds, cancelGraceMs, abortGraceMs);
        try {
            s.version = serverVersion(c);
            setupSession(s, spec.type(), timeoutSeconds);
            return s;
        } catch (Throwable e) {
            s.close();
            throw e;
        }
    }

    /** SİSTEMİN oturum ifadeleri (kullanıcı SQL'i değil): salt-okur, kısa kilit bekleme, zaman aşımı. */
    private static void setupSession(Session s, DbType type, int timeoutSeconds) throws SQLException {
        Connection c = s.conn;
        // Oracle'da setReadOnly kendi SET TRANSACTION'ını açabilir; aşağıdaki açık ifade "ilk ifade" olmazdı (ORA-01453).
        // Orada salt-okurluğu YALNIZ açık ifade kurar; diğer türlerde sürücü niyeti de verilir (PostgreSQL BEGIN READ ONLY).
        if (type != DbType.ORACLE) c.setReadOnly(true);
        c.setAutoCommit(false);
        switch (type) {
            case POSTGRESQL -> {
                exec(s, "SET TRANSACTION READ ONLY");
                exec(s, "SET LOCAL statement_timeout = " + (long) timeoutSeconds * 1_000L);
                exec(s, "SET LOCAL lock_timeout = '2s'");
                exec(s, "SET LOCAL standard_conforming_strings = on");
            }
            case MSSQL -> {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
                exec(s, "SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED");
                exec(s, "SET LOCK_TIMEOUT 2000");
                exec(s, "SET DEADLOCK_PRIORITY LOW");
            }
            case ORACLE -> exec(s, "SET TRANSACTION READ ONLY");
        }
    }

    private static void exec(Session s, String sql) throws SQLException {
        try (Statement st = s.conn.createStatement()) {
            s.stmt = st;
            st.setEscapeProcessing(false);
            st.setQueryTimeout(SETUP_TIMEOUT_SECONDS);
            st.execute(sql);
        }
    }

    private static String serverVersion(Connection c) {
        try {
            DatabaseMetaData md = c.getMetaData();
            String v = (md.getDatabaseProductName() + " " + md.getDatabaseProductVersion()).trim();
            return clip(DbQueryErrors.collapse(v), 200);
        } catch (Throwable e) {
            return null;
        }
    }

    // ── Sonuç okuma ──────────────────────────────────────────────────────────

    private static final class Read {
        final List<Column> columns = new ArrayList<>();
        final List<List<Object>> rows = new ArrayList<>();
        int count;
        boolean capped, truncated;
    }

    private static Read read(ResultSet rs, int limit, Set<String> discard) throws SQLException {
        Read r = new Read();
        ResultSetMetaData md = rs.getMetaData();
        int total = md.getColumnCount();
        int cols = Math.min(total, MAX_COLUMNS);
        if (total > MAX_COLUMNS) r.truncated = true;
        int[] types = new int[cols];
        int[] precision = new int[cols];
        boolean[] skip = new boolean[cols];
        Set<String> drop = new HashSet<>();
        for (String d : discard) if (d != null) drop.add(d.trim().toLowerCase(Locale.ROOT));
        for (int i = 0; i < cols; i++) {
            String label = md.getColumnLabel(i + 1);
            if (label == null || label.isBlank()) label = md.getColumnName(i + 1);
            String name = label == null ? "" : label;
            r.columns.add(new Column(name, md.getColumnTypeName(i + 1)));
            types[i] = md.getColumnType(i + 1);
            try { precision[i] = md.getPrecision(i + 1); } catch (Throwable e) { precision[i] = 0; }
            skip[i] = drop.contains(name.toLowerCase(Locale.ROOT));
        }
        long budget = MEMORY_BUDGET_CHARS;
        boolean exhausted = false;
        while (rs.next()) {
            if (r.count >= limit) {
                r.capped = true;
                break;
            }
            r.count++;
            if (exhausted) continue;
            List<Object> row = new ArrayList<>(cols);
            for (int i = 0; i < cols; i++) {
                if (skip[i]) {
                    row.add(null);
                    continue;
                }
                Cell cell = readCell(rs, i + 1, types[i], precision[i]);
                if (cell.truncated) r.truncated = true;
                budget -= cell.chars;
                row.add(cell.value);
            }
            r.rows.add(java.util.Collections.unmodifiableList(row));
            if (budget <= 0) {
                exhausted = true;
                r.truncated = true;
            }
        }
        if (exhausted && r.rows.size() < r.count) r.truncated = true;
        return r;
    }

    private record Cell(Object value, long chars, boolean truncated) {
        static final Cell NULL = new Cell(null, 1, false);
    }

    private static Cell readCell(ResultSet rs, int i, int type, int precision) throws SQLException {
        switch (type) {
            case Types.BIT, Types.BOOLEAN -> {
                boolean b = rs.getBoolean(i);
                return rs.wasNull() ? Cell.NULL : new Cell(b, 5, false);
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                long v = rs.getLong(i);
                return rs.wasNull() ? Cell.NULL : new Cell(BigDecimal.valueOf(v), 20, false);
            }
            case Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal v = rs.getBigDecimal(i);
                return v == null ? Cell.NULL : new Cell(v, 40, false);
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                double d = rs.getDouble(i);
                if (rs.wasNull()) return Cell.NULL;
                return Double.isFinite(d) ? new Cell(BigDecimal.valueOf(d), 30, false) : new Cell(String.valueOf(d), 10, false);
            }
            case Types.DATE -> {
                java.sql.Date d = rs.getDate(i);
                return d == null ? Cell.NULL : new Cell(d.toLocalDate().toString(), 10, false);
            }
            case Types.TIME -> {
                java.sql.Time t = rs.getTime(i);
                return t == null ? Cell.NULL : new Cell(t.toLocalTime().toString(), 12, false);
            }
            case Types.TIMESTAMP -> {
                java.sql.Timestamp t = rs.getTimestamp(i);
                return t == null ? Cell.NULL : new Cell(t.toLocalDateTime().toString(), 26, false);
            }
            case Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> {
                String s;
                try {
                    OffsetDateTime o = rs.getObject(i, OffsetDateTime.class);
                    s = o == null ? null : o.toString();
                } catch (Throwable e) {
                    s = rs.getString(i);
                }
                return s == null ? Cell.NULL : text(s);
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                try (InputStream in = rs.getBinaryStream(i)) {
                    if (in == null) return Cell.NULL;
                    long n = countBytes(in);
                    return new Cell(binaryText(n), 30, false);
                } catch (java.io.IOException e) {
                    return new Cell("<binary>", 10, false);
                }
            }
            case Types.BLOB -> {
                Blob b = rs.getBlob(i);
                if (b == null) return Cell.NULL;
                try {
                    return new Cell(binaryText(b.length()), 30, false);
                } finally {
                    try { b.free(); } catch (Throwable ignored) { /* bazı sürücüler desteklemez */ }
                }
            }
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.SQLXML -> {
                return stream(rs, i);
            }
            default -> {
                if (precision <= 0 || precision > 8_000) return stream(rs, i);
                String s = rs.getString(i);
                return s == null ? Cell.NULL : text(s);
            }
        }
    }

    /** Büyük metin: en çok {@value #MAX_CELL_CHARS} + 1 karakter okunur (tamamı belleğe alınmaz). */
    private static Cell stream(ResultSet rs, int i) throws SQLException {
        Reader r;
        try {
            r = rs.getCharacterStream(i);
        } catch (SQLException | RuntimeException e) {
            String s = rs.getString(i);
            return s == null ? Cell.NULL : text(s);
        }
        if (r == null) return Cell.NULL;
        try (Reader reader = r) {
            char[] buf = new char[MAX_CELL_CHARS + 1];
            int n = 0;
            while (n < buf.length) {
                int k = reader.read(buf, n, buf.length - n);
                if (k < 0) break;
                n += k;
            }
            return text(new String(buf, 0, n));
        } catch (java.io.IOException e) {
            return new Cell(null, 1, true);
        }
    }

    private static Cell text(String s) {
        if (s.length() <= MAX_CELL_CHARS) return new Cell(s, s.length(), false);
        return new Cell(s.substring(0, MAX_CELL_CHARS - 1) + "…", MAX_CELL_CHARS, true);
    }

    private static long countBytes(InputStream in) throws java.io.IOException {
        byte[] buf = new byte[8_192];
        long n = 0;
        while (n <= BINARY_COUNT_CAP) {
            int k = in.read(buf);
            if (k < 0) break;
            n += k;
        }
        return n;
    }

    static String binaryText(long n) {
        return n > BINARY_COUNT_CAP ? "<binary > " + BINARY_COUNT_CAP + " bytes>" : "<binary " + n + " bytes>";
    }

    // ── Plan okuma ───────────────────────────────────────────────────────────

    static PlanResult parsePgPlan(String json) {
        if (json == null) return new PlanResult(true, false, null, null, null);
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode plan = root.isArray() && !root.isEmpty() ? root.get(0).path("Plan") : root.path("Plan");
            if (plan.isMissingNode()) return new PlanResult(true, false, null, null, null);
            Double cost = plan.path("Total Cost").isNumber() ? plan.path("Total Cost").asDouble() : null;
            Double rows = plan.path("Plan Rows").isNumber() ? plan.path("Plan Rows").asDouble() : null;
            return new PlanResult(true, cost != null, cost, rows, null);
        } catch (Exception e) {
            return new PlanResult(true, false, null, null, null);
        }
    }

    /** SHOWPLAN XML'inden en büyük StatementSubTreeCost / StatementEstRows (doğrusal; XML ayrıştırıcısı yok → XXE yok). */
    static PlanResult parseShowplan(String xml) {
        if (xml == null) return new PlanResult(true, false, null, null, null);
        Double cost = maxAttr(xml, "StatementSubTreeCost=\"");
        Double rows = maxAttr(xml, "StatementEstRows=\"");
        return new PlanResult(true, cost != null, cost, rows, null);
    }

    private static Double maxAttr(String xml, String key) {
        Double best = null;
        int from = 0;
        while (true) {
            int i = xml.indexOf(key, from);
            if (i < 0) break;
            int v = i + key.length();
            int e = xml.indexOf('"', v);
            if (e < 0 || e - v > 40) break;
            try {
                double d = Double.parseDouble(xml.substring(v, e));
                if (Double.isFinite(d) && (best == null || d > best)) best = d;
            } catch (NumberFormatException ignored) {
                // bozuk öznitelik atlanır
            }
            from = e + 1;
        }
        return best;
    }

    // ── Hata ─────────────────────────────────────────────────────────────────

    /**
     * Hata sonucu. Sürücü / veritabanı iletisi her yolda {@link DbQueryErrors#scrub} ile değerlerinden ayıklanır
     * (kullanıcının SQL'i + bağlantı tanımı izinli belirteçlerdir) ve SQLSTATE eklenir; sonra parola / kullanıcı maskelenir.
     */
    private static QueryResult failed(DbConnectionSpec spec, Throwable e, DbQueryErrors.Phase phase, boolean fired,
                                      long ms, String sql) {
        DbType type = spec == null ? null : spec.type();
        String raw = DbQueryErrors.scrub(DbQueryErrors.describe(e), DbQueryErrors.allowTokens(sql, spec),
                DbQueryErrors.valueBearing(e, type));
        String code = e instanceof DriverUnavailableException || e instanceof InvalidSpecException
                ? DbQueryErrors.DB_DRIVER : DbQueryErrors.classify(e, phase, fired, type);
        String text = switch (code) {
            case DbQueryErrors.DB_DNS -> Msg.t("Veritabanı sunucusunun adı çözülemedi (DNS) · Ad yanlış ya da DNS kaydı yok · "
                    + "Bağlantıdaki sunucu adını denetleyin.", "The database server name could not be resolved (DNS) · The name "
                    + "is wrong or there is no DNS record · Check the server name of the connection.");
            case DbQueryErrors.DB_BLOCKED -> Msg.t("Bu sunucuya bağlanmaya izin verilmiyor · Ağ güvenlik kuralı (iç ağ / "
                    + "yerel adres / bulut üst verisi) hedefi engelliyor · Yöneticinizle izinli hedefleri gözden geçirin.",
                    "Connecting to this server is not allowed · A network safety rule (internal network / local address / "
                    + "cloud metadata) blocks the target · Review the allowed targets with your administrator.");
            case DbQueryErrors.DB_DRIVER -> e instanceof InvalidSpecException
                    ? Msg.t("Bağlantı tanımı geçersiz (" + e.getMessage() + ") · Veritabanı / servis adı yalnız harf, rakam, "
                    + "'_', '.', '$', '#', '-' içerebilir · Bağlantıyı düzeltin.", "The connection definition is invalid ("
                    + e.getMessage() + ") · Database / service names may contain only letters, digits, '_', '.', '$', '#', "
                    + "'-' · Fix the connection.")
                    : Msg.t("Veritabanı sürücüsü çalışmadı (" + raw + ") · Sürücü eksik ya da bağlantı "
                    + "biçimini kabul etmedi · Yöneticinize bildirin.", "The database driver failed ("
                    + raw + ") · The driver is missing or rejected the connection format · Tell your "
                    + "administrator.");
            default -> raw + DbQueryErrors.sqlStateSuffix(e, type);
        };
        if (DbQueryErrors.DB_TIMEOUT.equals(code) && fired) {
            text = Msg.t("Sorgu süre sınırını aştı ve iptal edildi · Veritabanı yanıt vermedi · Sorguyu hızlandırın ya da zaman "
                    + "aşımını artırın.", "The query exceeded the time limit and was cancelled · The database did not answer "
                    + "· Speed up the query or raise the timeout.");
        }
        if (log.isDebugEnabled()) log.debug("Veritabanı sorgusu başarısız ({} {}): {}", code, phase, e.getClass().getSimpleName());
        return new QueryResult(false, ms, 0, false, List.of(), List.of(), false, DbQueryErrors.mask(text, spec), code, null);
    }

    private static long elapsedMs(long t0) {
        return Math.max(0, (System.nanoTime() - t0) / 1_000_000L);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Hücrenin metin biçimi (mail / alarm satırları için) — sayı düz yazımla. */
    static String cellToText(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        if (v instanceof BigInteger bi) return bi.toString();
        return String.valueOf(v);
    }
}
