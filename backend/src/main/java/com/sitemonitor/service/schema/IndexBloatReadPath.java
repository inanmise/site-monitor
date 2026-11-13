package com.sitemonitor.service.schema;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * İndeks şişme tahmininin SALT OKUNUR dış yolu (2026-11-13, Veritabanı büyümesi analizi). {@link IndexCatalog} paket içine
 * kapalı (havuz dışı oturum + DDL bekçisi — bakım işlerinin yüzü); bu sınıf aynı katalog sorgusunu
 * ({@link JdbcIndexCatalog#SQL_BLOAT_INPUTS}) çağıranın zaman aşımlı şablonuyla koşar ve aynı hesabı
 * ({@link IndexBloatEstimator}) uygular. Hiçbir şey yazmaz, kilit almaz, DDL koşmaz. Yalnız PostgreSQL.
 */
public final class IndexBloatReadPath {

    private IndexBloatReadPath() {}

    /** Bir dizinin tahmini: gerçek ve beklenen bayt (beklenen bilinmiyorsa -1), şişmiş mi. */
    public record Estimate(String table, String index, long bytes, long expectedBytes, boolean bloated) {}

    /**
     * Verilen tabloların sade btree dizinleri için tahmin. {@code factor} / {@code minExcessBytes} = gece bakımının kuralı
     * ({@link IndexBloatEstimator#bloated}).
     */
    public static List<Estimate> estimate(JdbcTemplate jdbc, List<String> tables, double factor, long minExcessBytes) {
        if (jdbc == null || tables == null || tables.isEmpty()) return List.of();
        Map<String, IndexCatalog.IndexStats> byName = new LinkedHashMap<>();
        Map<String, List<IndexCatalog.ColumnStat>> cols = new LinkedHashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(JdbcIndexCatalog.SQL_BLOAT_INPUTS);
            ps.setArray(1, con.createArrayOf("text", tables.toArray()));
            return ps;
        }, rs -> {
            String name = rs.getString("index_name");
            if (!byName.containsKey(name)) {
                byName.put(name, new IndexCatalog.IndexStats(name, rs.getString("qname"), rs.getString("table_name"),
                        rs.getDouble("index_tuples"), rs.getDouble("table_tuples"), rs.getLong("bytes"),
                        rs.getInt("block_size"), rs.getInt("fillfactor"), List.of()));
                cols.put(name, new ArrayList<>());
            }
            double w = rs.getDouble("avg_width");
            Double avg = rs.wasNull() ? null : w;
            double f = rs.getDouble("null_frac");
            Double nf = rs.wasNull() ? null : f;
            cols.get(name).add(new IndexCatalog.ColumnStat(rs.getString("attname"), avg, nf));
        });
        List<Estimate> out = new ArrayList<>(byName.size());
        for (IndexCatalog.IndexStats s : byName.values()) {
            IndexCatalog.IndexStats full = new IndexCatalog.IndexStats(s.name(), s.qualifiedName(), s.table(), s.indexTuples(),
                    s.tableTuples(), s.bytes(), s.blockSize(), s.fillfactor(), cols.get(s.name()));
            long expected = IndexBloatEstimator.expectedBytes(full);
            out.add(new Estimate(s.table(), s.name(), s.bytes(), expected,
                    IndexBloatEstimator.bloated(s.bytes(), expected, factor, minExcessBytes)));
        }
        return out;
    }

    /** Tablonun şişmiş dizinlerini gece bakımı ({@link IndexBloatMaintenance}) yeniden kuruyor mu. */
    public static boolean maintainedNightly(String table) {
        return table != null && IndexBloatMaintenance.ALLOWED_TABLES.contains(table);
    }
}
