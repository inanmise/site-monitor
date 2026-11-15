package com.sitemonitor.repository;

import com.sitemonitor.model.PageSpeedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * {@link PageSpeedResourceBatchWriter} — Spring Data parça uygulaması (ad kuralı: arayüz + {@code Impl}). Kendi
 * {@link JdbcTemplate}'i aynı {@link DataSource} üzerinden açılır; {@code DataSourceUtils} sayesinde etkin JPA
 * transaction'ının bağlantısını kullanır (silme ile aynı transaction). Her ifade {@value #QUERY_TIMEOUT_SECONDS} sn ile
 * sınırlı — kilit beklemesi ölçüm iş parçacığını havuzun soket zaman aşımına dek tutmaz.
 */
public class PageSpeedResourceBatchWriterImpl implements PageSpeedResourceBatchWriter {

    /** Öbek boyu: sürücüye tek seferde giden satır sayısı. */
    static final int BATCH_SIZE = 500;
    /** İfade başına süre sınırı (sn). */
    static final int QUERY_TIMEOUT_SECONDS = 30;

    static final String INSERT_SQL = "INSERT INTO pagespeed_resources (monitor_id, check_id, checked_at, url, type, bytes, "
            + "truncated, duration_ms, start_ms, found_in, status_code, third_party, keep_reason) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public PageSpeedResourceBatchWriterImpl(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    @Override
    @Transactional
    public int insertAll(List<PageSpeedResource> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        int[][] done = jdbc.batchUpdate(INSERT_SQL, rows, BATCH_SIZE, PageSpeedResourceBatchWriterImpl::bind);
        int n = 0;
        for (int[] chunk : done) n += chunk.length;
        return n;
    }

    /** Varlıktaki alan varsayılanlarıyla aynı: kırpılmadı / birinci taraf / LATEST (null gelirse). */
    static void bind(PreparedStatement ps, PageSpeedResource r) throws SQLException {
        ps.setLong(1, r.getMonitorId());
        setLong(ps, 2, r.getCheckId());
        ps.setString(3, r.getCheckedAt());
        ps.setString(4, r.getUrl());
        setString(ps, 5, r.getType());
        setLong(ps, 6, r.getBytes());
        ps.setBoolean(7, Boolean.TRUE.equals(r.getTruncated()));
        setInt(ps, 8, r.getDurationMs());
        setInt(ps, 9, r.getStartMs());
        setString(ps, 10, r.getFoundIn());
        setInt(ps, 11, r.getStatusCode());
        ps.setBoolean(12, Boolean.TRUE.equals(r.getThirdParty()));
        ps.setString(13, r.getKeepReason() == null ? PageSpeedResource.KEEP_LATEST : r.getKeepReason());
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }

    private static void setString(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) ps.setNull(i, Types.VARCHAR); else ps.setString(i, v);
    }
}
