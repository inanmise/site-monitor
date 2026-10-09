package com.sitemonitor.service;

import com.sitemonitor.service.schema.SchemaPatchRunner;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
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
    /** Şema yaması özeti kaynağı — testte değiştirilebilsin diye alan (final değil; kurucuya girmez). */
    Supplier<SchemaPatchRunner.Summary> schemaSummary = SchemaPatchRunner::last;

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
        // 2026-10-09 (Ayarlar → Veritabanı yeniden tasarımı): sunucu saatlerinin hangi dilimde olduğu, uygulama
        // tablolarının sayısı ve bu bağlantının şifreli olup olmadığı. Hepsi tek satırlık katalog okuması; erişilemezse null.
        out.put("timezone",          scalar("SELECT current_setting('TimeZone')", String.class));
        out.put("table_count",       scalar("SELECT count(*) FROM pg_stat_user_tables", Integer.class));
        out.put("ssl",               scalar("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()", Boolean.class));
        out.put("ssl_version",       scalar("SELECT version FROM pg_stat_ssl WHERE pid = pg_backend_pid()", String.class));

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
                // Yapılandırma (canlı sayaç değil) — havuz sorunlarında ilk bakılan üç süre.
                pool.put("connection_timeout_ms", hds.getConnectionTimeout());
                pool.put("idle_timeout_ms",       hds.getIdleTimeout());
                pool.put("max_lifetime_ms",       hds.getMaxLifetime());
            }
        } catch (Exception e) {
            log.debug("Hikari havuz istatistikleri okunamadı: {}", e.getMessage());
        }
        out.put("pool", pool);

        out.put("schema_patches", schemaPatches());

        return out;
    }

    /**
     * Açılıştaki şema yamalarının özeti (yalnız SAYILAR): uygulanan, zaten var olan, başarısız, kilitli koşu ve bitiş anı.
     * Başarısız yamaların SQL/hata metinleri ({@code failures}) bilinçli olarak döndürülmez — hata sözleşmesi: istemciye
     * istisna metni gitmez; ayrıntı uygulama günlüğündedir. Yamalar henüz koşmadıysa {@code pending=true}.
     */
    Map<String, Object> schemaPatches() {
        Map<String, Object> m = new LinkedHashMap<>();
        SchemaPatchRunner.Summary s;
        try {
            s = schemaSummary.get();
        } catch (Exception e) {
            s = null;
        }
        if (s == null) {
            m.put("pending", true);
            return m;
        }
        m.put("applied", s.applied());
        m.put("noop", s.noop());
        m.put("failed", s.failed());
        m.put("locked", s.locked());
        m.put("finished_at", s.finishedAtMs() > 0
                ? Instant.ofEpochMilli(s.finishedAtMs()).truncatedTo(ChronoUnit.SECONDS).toString() : null);
        return m;
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

    /** Gizli değer taşıyan JDBC parametreleri: password, sslpassword, passwd, pwd, secret, token, *_key (sslkey vb. dosya yolu da). */
    private static final Pattern SECRET_PARAM =
            Pattern.compile("(?i)([?&;](?:ssl)?password=|[?&;]passwd=|[?&;]pwd=|[?&;]secret=|[?&;]token=|[?&;]\\w*key=)[^&;]*");
    /** URL içindeki kullanıcı bilgisi: {@code //kullanici:parola@sunucu} → {@code //kullanici:***@sunucu}. */
    private static final Pattern USERINFO_SECRET = Pattern.compile("(//[^/@:?#;]*:)[^/@?#;]*@");

    /**
     * JDBC URL'inde parola taşınıyorsa maskele (genelde ayrı tutulur, yine de garanti). 2026-10-09: yalnız
     * {@code password=} değil, {@code sslpassword=}, {@code passwd=}, {@code pwd=}, {@code secret=}, {@code token=},
     * {@code *key=} parametreleri ve {@code //kullanıcı:parola@} biçimi de maskelenir.
     */
    static String sanitizeUrl(String url) {
        if (url == null) return null;
        String masked = SECRET_PARAM.matcher(url).replaceAll("$1***");
        return USERINFO_SECRET.matcher(masked).replaceAll("$1***@");
    }
}
