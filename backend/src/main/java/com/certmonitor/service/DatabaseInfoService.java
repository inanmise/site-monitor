package com.certmonitor.service;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings → Veritabanı Bilgileri (yalnız admin). Bağlı PostgreSQL örneğine ait temel,
 * salt-okunur meta verileri toplar: veritabanı adı, bağlantı kullanıcısı, host/port, sürüm,
 * boyut, çalışma süresi + HikariCP havuz istatistikleri ve JDBC sürücü bilgisi. Her sorgu
 * tek tek korunur (biri başarısız olsa diğerleri yine gösterilir). Hassas veri (parola)
 * döndürülmez; JDBC URL'inde parola varsa maskelenir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseInfoService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public Map<String, Object> getInfo() {
        Map<String, Object> out = new LinkedHashMap<>();

        // ── Bağlantı / sunucu (PostgreSQL'den canlı) ──────────────────────────
        out.put("database",          scalar("SELECT current_database()", String.class));
        out.put("user",              scalar("SELECT current_user", String.class));
        out.put("session_user",      scalar("SELECT session_user", String.class));
        // Unix socket bağlantısında inet_server_addr NULL döner — TCP'de sunucu IP'si.
        out.put("server_addr",       scalar("SELECT host(inet_server_addr())", String.class));
        out.put("server_port",       scalar("SELECT inet_server_port()", Integer.class));
        String fullVersion = scalar("SELECT version()", String.class);
        out.put("version",           shortVersion(fullVersion));
        out.put("version_full",      fullVersion);
        out.put("encoding",          scalar("SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database()", String.class));
        out.put("collation",         scalar("SELECT datcollate FROM pg_database WHERE datname = current_database()", String.class));
        out.put("size",              scalar("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class));
        // Sunucu yerel saatleri — uygulama UTC zaman damgaları değil; olduğu gibi gösterilir.
        out.put("start_time",        scalar("SELECT to_char(pg_postmaster_start_time(), 'YYYY-MM-DD HH24:MI:SS')", String.class));
        out.put("uptime",            scalar("SELECT date_trunc('second', now() - pg_postmaster_start_time())::text", String.class));
        out.put("server_time",       scalar("SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS')", String.class));
        out.put("max_connections",   scalar("SELECT setting FROM pg_settings WHERE name = 'max_connections'", String.class));
        out.put("active_connections",scalar("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()", Integer.class));

        // ── JDBC / sürücü meta (DataSource bağlantısından) ────────────────────
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            out.put("jdbc_url",       sanitizeUrl(md.getURL()));
            out.put("driver_name",    md.getDriverName());
            out.put("driver_version", md.getDriverVersion());
        } catch (Exception e) {
            log.debug("DB metadata okunamadı: {}", e.getMessage());
        }

        // ── HikariCP havuz istatistikleri ─────────────────────────────────────
        Map<String, Object> pool = new LinkedHashMap<>();
        try {
            if (dataSource instanceof HikariDataSource hds) {
                var mx = hds.getHikariPoolMXBean();
                pool.put("name",     hds.getPoolName());
                pool.put("active",   mx.getActiveConnections());
                pool.put("idle",     mx.getIdleConnections());
                pool.put("total",    mx.getTotalConnections());
                pool.put("waiting",  mx.getThreadsAwaitingConnection());
                pool.put("max_size", hds.getMaximumPoolSize());
                pool.put("min_idle", hds.getMinimumIdle());
            }
        } catch (Exception e) {
            log.debug("Hikari havuz istatistikleri okunamadı: {}", e.getMessage());
        }
        out.put("pool", pool);

        return out;
    }

    private <T> T scalar(String sql, Class<T> type) {
        try {
            return jdbcTemplate.queryForObject(sql, type);
        } catch (Exception e) {
            return null; // erişilemeyen/desteklenmeyen alan → "—" olarak gösterilir
        }
    }

    /** "PostgreSQL 16.2 on x86_64-pc-linux-gnu ..." → "PostgreSQL 16.2". */
    private String shortVersion(String full) {
        if (full == null) return null;
        Matcher m = Pattern.compile("PostgreSQL\\s+\\S+").matcher(full);
        return m.find() ? m.group() : full;
    }

    /** JDBC URL'inde parola taşınıyorsa maskele (genelde ayrı tutulur, yine de garanti). */
    private String sanitizeUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("(?i)(password=)[^&;]*", "$1***");
    }
}
