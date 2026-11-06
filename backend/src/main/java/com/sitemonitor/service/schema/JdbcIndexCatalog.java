package com.sitemonitor.service.schema;

import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IndexCatalog}'un PostgreSQL gerçeği (2026-11-06, indeks bakımı): {@link DeferredIndexBuilder.JdbcSession} ile açılan
 * havuz DIŞI, otomatik commit oturumu ({@code CONCURRENTLY} işlem bloğunda koşamaz; dakikalar süren DDL havuzdan bağlantı
 * yemesin) — rol düzeyindeki {@code statement_timeout} oturum açılırken 0'lanır, sınırlar her DDL'de ayrıca verilir.
 */
final class JdbcIndexCatalog implements IndexCatalog {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Tablonun indeksleri — anahtar + INCLUDE sütun adları {@code indkey} sırasıyla (ifade konumu boş dize). */
    static final String SQL_INDEXES = """
            SELECT c.relname, quote_ident(n.nspname) || '.' || quote_ident(c.relname) AS qname, am.amname,
                   i.indisunique, i.indisprimary, i.indisexclusion,
                   EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = c.oid) AS constraint_backed,
                   i.indisvalid, i.indisready, i.indislive,
                   i.indpred IS NOT NULL AS partial, i.indexprs IS NOT NULL AS expressions, i.indnkeyatts,
                   ARRAY(SELECT COALESCE(a.attname::text, '') FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                         LEFT JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                         ORDER BY k.ord) AS cols,
                   i.indclass::text AS opclasses, i.indcollation::text AS collations, i.indoption::text AS options,
                   pg_relation_size(c.oid) AS bytes
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace tn ON tn.oid = t.relnamespace
            JOIN pg_am am ON am.oid = c.relam
            WHERE tn.nspname = current_schema() AND t.relname = ?
            ORDER BY c.relname
            """;

    static final String SQL_BUILDING = """
            SELECT EXISTS (SELECT 1 FROM pg_stat_progress_create_index p
                           JOIN pg_class t ON t.oid = p.relid
                           JOIN pg_namespace n ON n.oid = t.relnamespace
                           WHERE n.nspname = current_schema() AND t.relname = ?)
            """;

    /**
     * Şişme tahmini girdileri — (indeks, sütun) başına bir satır. Yalnız sade btree: geçerli, hazır, canlı, benzersiz değil,
     * kısıt desteklemiyor, kısmi / ifade değil, yeniden kurma kalıntısı değil. İstatistik yoksa {@code avg_width} NULL.
     */
    static final String SQL_BLOAT_INPUTS = """
            SELECT ci.relname AS index_name, quote_ident(n.nspname) || '.' || quote_ident(ci.relname) AS qname,
                   ct.relname AS table_name, ci.reltuples AS index_tuples, ct.reltuples AS table_tuples,
                   pg_relation_size(ci.oid) AS bytes, current_setting('block_size')::int AS block_size,
                   COALESCE((SELECT split_part(o, '=', 2)::int FROM unnest(ci.reloptions) o
                             WHERE o LIKE 'fillfactor=%' LIMIT 1), 90) AS fillfactor,
                   a.attname, s.avg_width, s.null_frac
            FROM pg_index i
            JOIN pg_class ci ON ci.oid = i.indexrelid
            JOIN pg_class ct ON ct.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = ct.relnamespace
            JOIN pg_am am ON am.oid = ci.relam
            CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
            LEFT JOIN pg_attribute a ON a.attrelid = ct.oid AND a.attnum = k.attnum
            LEFT JOIN pg_stats s ON s.schemaname = n.nspname AND s.tablename = ct.relname AND s.attname = a.attname
                                 AND NOT s.inherited
            WHERE n.nspname = current_schema() AND ct.relname = ANY (?) AND am.amname = 'btree'
              AND i.indisvalid AND i.indisready AND i.indislive
              AND NOT i.indisunique AND NOT i.indisprimary AND NOT i.indisexclusion
              AND i.indpred IS NULL AND i.indexprs IS NULL
              AND NOT EXISTS (SELECT 1 FROM pg_constraint x WHERE x.conindid = ci.oid)
              AND ci.relname !~ '_cc(new|old)[0-9]*$'
            ORDER BY ci.relname, k.ord
            """;

    /** Yarıda kalmış REINDEX CONCURRENTLY kalıntıları: GEÇERSİZ ve adı {@code _ccnew[n]} / {@code _ccold[n]} ile biten. */
    static final String SQL_LEFTOVERS = """
            SELECT c.relname, quote_ident(n.nspname) || '.' || quote_ident(c.relname) AS qname, t.relname AS table_name,
                   pg_relation_size(c.oid) AS bytes,
                   EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.relid = t.oid) AS building
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace tn ON tn.oid = t.relnamespace
            WHERE tn.nspname = current_schema() AND t.relname = ANY (?) AND NOT i.indisvalid
              AND c.relname ~ '_cc(new|old)[0-9]*$'
            ORDER BY c.relname
            """;

    private final DeferredIndexBuilder.JdbcSession session;
    private final Connection conn;
    /** Süren DDL deyimi — kapanışta {@link #cancel()} başka iş parçacığından iptal eder. */
    private volatile Statement running;

    private JdbcIndexCatalog(DeferredIndexBuilder.JdbcSession session) {
        this.session = session;
        this.conn = session.connection();
    }

    static JdbcIndexCatalog open(DataSource ds) throws SQLException {
        return new JdbcIndexCatalog(DeferredIndexBuilder.JdbcSession.open(ds));
    }

    @Override
    public boolean tryLock(long key) throws SQLException {
        return session.tryLock(key);
    }

    @Override
    public void unlock(long key) {
        session.unlock(key);
    }

    @Override
    public boolean markerExists(String key) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL); }
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getLong(1) > 0; }
        }
    }

    @Override
    public void writeMarker(String key, int rowsAffected) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected)"
                + " VALUES (?, ?, ?) ON CONFLICT (patch_key) DO NOTHING")) {
            ps.setString(1, key);
            ps.setString(2, ISO.format(Instant.now()));
            ps.setInt(3, rowsAffected);
            ps.executeUpdate();
        }
    }

    @Override
    public List<IndexFacts> indexes(String table) throws SQLException {
        List<IndexFacts> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_INDEXES)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new IndexFacts(rs.getString("relname"), rs.getString("qname"), table, rs.getString("amname"),
                            rs.getBoolean("indisunique"), rs.getBoolean("indisprimary"), rs.getBoolean("indisexclusion"),
                            rs.getBoolean("constraint_backed"), rs.getBoolean("indisvalid"), rs.getBoolean("indisready"),
                            rs.getBoolean("indislive"), rs.getBoolean("partial"), rs.getBoolean("expressions"),
                            rs.getInt("indnkeyatts"), textArray(rs.getArray("cols")), words(rs.getString("opclasses")),
                            words(rs.getString("collations")), words(rs.getString("options")), rs.getLong("bytes")));
                }
            }
        }
        return out;
    }

    @Override
    public boolean building(String table) {
        try (PreparedStatement ps = conn.prepareStatement(SQL_BUILDING)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        } catch (SQLException e) {
            return false;   // görünüm okunamadı (yetki / eski sürüm) — en kötü bugünkü gibi
        }
    }

    @Override
    public List<IndexStats> bloatInputs(List<String> tables) throws SQLException {
        Map<String, IndexStats> byName = new LinkedHashMap<>();
        Map<String, List<ColumnStat>> cols = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_BLOAT_INPUTS)) {
            ps.setArray(1, conn.createArrayOf("text", tables.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("index_name");
                    if (!byName.containsKey(name)) {
                        byName.put(name, new IndexStats(name, rs.getString("qname"), rs.getString("table_name"),
                                rs.getDouble("index_tuples"), rs.getDouble("table_tuples"), rs.getLong("bytes"),
                                rs.getInt("block_size"), rs.getInt("fillfactor"), List.of()));
                        cols.put(name, new ArrayList<>());
                    }
                    double w = rs.getDouble("avg_width");
                    Double avg = rs.wasNull() ? null : w;
                    double f = rs.getDouble("null_frac");
                    Double nf = rs.wasNull() ? null : f;
                    cols.get(name).add(new ColumnStat(rs.getString("attname"), avg, nf));
                }
            }
        }
        List<IndexStats> out = new ArrayList<>(byName.size());
        for (IndexStats s : byName.values()) {
            out.add(new IndexStats(s.name(), s.qualifiedName(), s.table(), s.indexTuples(), s.tableTuples(), s.bytes(),
                    s.blockSize(), s.fillfactor(), cols.get(s.name())));
        }
        return out;
    }

    @Override
    public List<Leftover> leftovers(List<String> tables) throws SQLException {
        List<Leftover> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LEFTOVERS)) {
            ps.setArray(1, conn.createArrayOf("text", tables.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Leftover(rs.getString("relname"), rs.getString("qname"), rs.getString("table_name"),
                            rs.getLong("bytes"), rs.getBoolean("building")));
                }
            }
        }
        return out;
    }

    @Override
    public long indexBytes(String qualifiedName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CASE WHEN to_regclass(?) IS NULL THEN -1 ELSE pg_relation_size(to_regclass(?)) END")) {
            ps.setString(1, qualifiedName);
            ps.setString(2, qualifiedName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : -1L; }
        }
    }

    @Override
    public void executeBounded(String ddl, long lockTimeoutMs, long statementTimeoutMs) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET lock_timeout = " + Math.max(1L, lockTimeoutMs));
            st.execute("SET statement_timeout = " + Math.max(1L, statementTimeoutMs));
            running = st;
            try {
                st.execute(ddl);
            } finally {
                running = null;
            }
        } finally {
            try (Statement st = conn.createStatement()) {
                st.execute("RESET lock_timeout");
                st.execute("SET statement_timeout = 0");   // JdbcSession'ın açılış değeri (rol varsayılanı geri gelmesin)
            } catch (SQLException ignore) {
                // bağlantı koptuysa oturum zaten kapanıyor
            }
        }
    }

    @Override
    public void cancel() {
        Statement st = running;
        if (st == null) return;
        try {
            st.cancel();
        } catch (SQLException ignore) {
            // en kötü: deyim kendi statement_timeout'unda biter
        }
    }

    @Override
    public void close() {
        session.close();
    }

    private static List<String> textArray(Array a) throws SQLException {
        if (a == null) return List.of();
        Object o = a.getArray();
        if (!(o instanceof Object[] arr)) return List.of();
        List<String> out = new ArrayList<>(arr.length);
        for (Object x : arr) out.add(x == null ? "" : x.toString());
        return out;
    }

    /** {@code oidvector} / {@code int2vector} metni ("1978 3126") → öğeler. */
    static List<String> words(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.asList(s.trim().split("\\s+"));
    }
}
