package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.evalstate.EvalStateSql;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import com.sitemonitor.service.schema.IndexBloatReadPath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL veri kaynağı (2026-11-13). Her deyim KENDİ zaman aşımlı şablonunda: boyut taraması
 * {@value #SIZE_TIMEOUT_S} sn, diğer okumalar {@value #READ_TIMEOUT_S} sn (paylaşılan şablona dokunulmaz). Hepsi salt okuma
 * — istatistik görünümleri, boyut fonksiyonları, katalog yaş kuralı kolonunda MIN ve kuralın kendi WHERE'iyle COUNT. Tablo /
 * kolon adları yalnız {@code RetentionCatalog}'dan ve sabit listelerden gelir (kullanıcı girdisi SQL'e girmez).
 */
@Component
public class PgDbSizeSource implements DbSizeSource {

    static final int SIZE_TIMEOUT_S = 30;
    static final int READ_TIMEOUT_S = 5;
    /** Şişme kararı = gece bakımının kuralı (2 × beklenen ve en az 64 MB fazla). */
    static final double BLOAT_FACTOR = 2.0;
    static final long BLOAT_MIN_EXCESS = 64L << 20;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String SQL_DB_SIZE = "SELECT pg_database_size(current_database())";
    static final String SQL_TABLES = "SELECT s.relname, pg_total_relation_size(s.relid), pg_relation_size(s.relid), "
            + "pg_indexes_size(s.relid), s.n_live_tup, s.n_dead_tup, s.n_tup_ins, s.n_tup_del "
            + "FROM pg_stat_user_tables s WHERE s.schemaname = current_schema()";
    static final String SQL_HISTOGRAMS = "SELECT tablename, attname, histogram_bounds::text FROM pg_stats "
            + "WHERE schemaname = current_schema() AND NOT inherited AND tablename = ANY (?) AND attname = ANY (?)";
    static final String SQL_VACUUM = "SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_vacuum, last_autoanalyze, "
            + "pg_total_relation_size(relid) FROM pg_stat_user_tables WHERE schemaname = current_schema()";
    static final String SQL_UNUSED_INDEXES = "SELECT s.relname, s.indexrelname, pg_relation_size(s.indexrelid) "
            + "FROM pg_stat_user_indexes s JOIN pg_index i ON i.indexrelid = s.indexrelid "
            + "WHERE s.schemaname = current_schema() AND s.idx_scan = 0 AND NOT i.indisunique AND NOT i.indisprimary "
            + "AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = s.indexrelid) "
            + "AND pg_relation_size(s.indexrelid) >= ? ORDER BY 3 DESC LIMIT 50";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate sizeJdbc;
    private final JdbcTemplate readJdbc;
    private volatile Boolean postgres;

    public PgDbSizeSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.sizeJdbc = EvalStateSql.withQueryTimeout(jdbc, SIZE_TIMEOUT_S);
        this.readJdbc = EvalStateSql.withQueryTimeout(jdbc, READ_TIMEOUT_S);
    }

    @Override
    public Boolean supported() {
        Boolean b = postgres;
        if (b != null) return b;
        b = DeferredIndexBuilder.postgresOrNull(jdbc.getDataSource());
        if (b != null) postgres = b;   // yalnız BİLİNEN tür hatırlanır; okunamadı = sonra yeniden dene
        return b;
    }

    @Override
    public long databaseBytes() {
        Long v = readJdbc.queryForObject(SQL_DB_SIZE, Long.class);
        return v == null ? 0L : v;
    }

    @Override
    public List<TableSize> tables() {
        return sizeJdbc.query(SQL_TABLES, (rs, i) -> new TableSize(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)));
    }

    @Override
    public String oldest(RetentionPolicy p) {
        if (p == null || p.timeColumn() == null || p.mode() != RetentionPolicy.Mode.AGE) return null;
        try {
            Object v = readJdbc.queryForObject("SELECT MIN(" + p.timeColumn() + ") FROM " + p.table(), Object.class);
            return normalize(v);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public Map<String, List<String>> histograms(List<String[]> tableColumns) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (tableColumns == null || tableColumns.isEmpty()) return out;
        Set<String> wanted = new HashSet<>();
        List<String> tables = new ArrayList<>();
        List<String> cols = new ArrayList<>();
        for (String[] tc : tableColumns) {
            wanted.add(tc[0] + "." + tc[1]);
            tables.add(tc[0]);
            cols.add(tc[1]);
        }
        readJdbc.query(con -> {
            var ps = con.prepareStatement(SQL_HISTOGRAMS);
            ps.setArray(1, con.createArrayOf("text", tables.toArray()));
            ps.setArray(2, con.createArrayOf("text", cols.toArray()));
            return ps;
        }, rs -> {
            String key = rs.getString(1) + "." + rs.getString(2);
            String text = rs.getString(3);
            if (wanted.contains(key) && text != null) out.put(key, DbGrowthForecast.parseArray(text, 1001));
        });
        return out;
    }

    @Override
    public Long countOlder(RetentionPolicy p, String cutoff) {
        if (p == null || !p.deletes() || p.paramCount() != 1) return null;
        try {
            return readJdbc.queryForObject(p.countSql(), Long.class, cutoff);
        } catch (RuntimeException e) {
            return null;   // süre doldu (57014) ya da başka hata → çağıran histograma / tekdüze tahmine düşer
        }
    }

    @Override
    public List<VacuumStat> vacuumStats() {
        return readJdbc.query(SQL_VACUUM, (rs, i) -> new VacuumStat(rs.getString(1), rs.getLong(2), rs.getLong(3),
                normalize(rs.getObject(4)), normalize(rs.getObject(5)), normalize(rs.getObject(6)), rs.getLong(7)));
    }

    @Override
    public List<IndexInfo> unusedIndexes(long minBytes) {
        return readJdbc.query(SQL_UNUSED_INDEXES, (rs, i) -> new IndexInfo(rs.getString(1), rs.getString(2), rs.getLong(3)), minBytes);
    }

    @Override
    public List<BloatInfo> bloat(List<String> tables) {
        List<BloatInfo> out = new ArrayList<>();
        for (IndexBloatReadPath.Estimate e : IndexBloatReadPath.estimate(readJdbc, tables, BLOAT_FACTOR, BLOAT_MIN_EXCESS)) {
            out.add(new BloatInfo(e.table(), e.index(), e.bytes(), e.expectedBytes(), e.bloated()));
        }
        return out;
    }

    @Override
    public List<long[]> hotMonitors(String table, String sinceIso, int limit) {
        return readJdbc.query("SELECT monitor_id, COUNT(*) FROM " + table + " WHERE checked_at >= ? AND monitor_id IS NOT NULL "
                + "GROUP BY monitor_id ORDER BY 2 DESC LIMIT " + Math.max(1, Math.min(limit, 20)),
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, sinceIso);
    }

    /** Zaman değeri → UTC ISO 19 karakter (gün / saat kovası tam saate tamamlanır); null → null. */
    static String normalize(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp ts) return ISO.format(ts.toInstant());
        if (v instanceof java.time.OffsetDateTime odt) return ISO.format(odt.toInstant());
        if (v instanceof java.time.LocalDateTime ldt) return ISO.format(ldt.toInstant(ZoneOffset.UTC));
        String s = String.valueOf(v).trim().replace(' ', 'T');
        if (s.length() == 10) return s + "T00:00:00";
        if (s.length() == 13) return s + ":00:00";
        return s.length() >= 19 ? s.substring(0, 19) : s;
    }
}
