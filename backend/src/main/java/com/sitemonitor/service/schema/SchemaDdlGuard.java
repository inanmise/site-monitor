package com.sitemonitor.service.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Açılış şema yamalarının KİLİT güvenliği (2026-10-09, kullanıcı kararı: "koşulsuz yamalar gerekiyorsa çalışsın, kilit
 * beklemesi 20 sn ile sınırlı olsun").
 *
 * <p><b>Neden.</b> Bazı yamalar her açılışta koşulsuz çalışıyordu (kolon tipini TEXT / VARCHAR(n) yap, NOT NULL kaldır,
 * kısıt düşür, autovacuum ayarı, {@code CREATE INDEX IF NOT EXISTS}). Değişecek bir şey olmasa bile PostgreSQL tabloya
 * kilit alır (ALTER için ACCESS EXCLUSIVE). Dağıtım anında o tabloda uzun bir sorgu ya da açık kalmış bir işlem varsa yama
 * süresiz bekler; bekleyen özel kilit arkasından gelen TÜM sorguları da kuyruğa sokar — {@code app_users} her istekte
 * okunduğu için hizmet veren eski pod da donar, yeni pod 300 sn'lik açılış bütçesini aşıp yeniden başlatılır.
 *
 * <ol>
 *   <li>{@link #alreadyInPlace}: değişiklik katalogda ZATEN varsa ifade hiç çalıştırılmaz → normal açılışta kilit yok.
 *       Tanınmayan biçim ya da katalog sorgusu hatası → {@code false} (eski davranış: ifade çalışır).</li>
 *   <li>{@link #executeBounded}: çalışan ifade {@code lock_timeout} = {@link #LOCK_TIMEOUT_SECONDS} sn ile koşar; kilit
 *       alınamazsa SQLSTATE 55P03 ({@link #isLockTimeout}) — çağıran uyarı yazar, yama sonraki açılışta yeniden denenir.
 *       {@code CONCURRENTLY} ifadeleri sınırsız kalır: süre dolunca geride GEÇERSİZ bir indeks kalır ve
 *       {@code IF NOT EXISTS} onu sonsuza dek atlardı.</li>
 * </ol>
 * Yalnız PostgreSQL; H2 (testler) eski yoldan geçer.
 */
public final class SchemaDdlGuard {

    private static final Logger log = LoggerFactory.getLogger(SchemaDdlGuard.class);

    /** Bir yama ifadesinin kilit için en çok bekleyeceği süre (kullanıcı kararı 2026-10-09: 20 sn). */
    public static final int LOCK_TIMEOUT_SECONDS = 20;
    /** PostgreSQL lock_not_available. */
    public static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";

    private static final Pattern ALTER_TYPE = Pattern.compile(
            "(?i)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+ALTER\\s+COLUMN\\s+(\\w+)\\s+TYPE\\s+(TEXT|VARCHAR\\s*\\(\\s*(\\d{1,5})\\s*\\))\\s*$");
    private static final Pattern DROP_NOT_NULL = Pattern.compile(
            "(?i)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+ALTER\\s+COLUMN\\s+(\\w+)\\s+DROP\\s+NOT\\s+NULL\\s*$");
    private static final Pattern DROP_CONSTRAINT = Pattern.compile(
            "(?i)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+DROP\\s+CONSTRAINT\\s+IF\\s+EXISTS\\s+(\\w+)\\s*$");
    private static final Pattern SET_RELOPTIONS = Pattern.compile(
            "(?i)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+SET\\s*\\(([\\w\\s.=,]{1,1000})\\)\\s*$");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?i)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?IF\\s+NOT\\s+EXISTS\\s+(\\w+)\\s+ON\\s");
    private static final Pattern CONCURRENTLY = Pattern.compile("(?i)\\bCONCURRENTLY\\b");

    private final JdbcTemplate jdbc;
    private final int lockTimeoutSeconds;
    private Boolean postgres;

    public SchemaDdlGuard(JdbcTemplate jdbc) {
        this(jdbc, LOCK_TIMEOUT_SECONDS);
    }

    /** Test: kilit bekleme süresi kısaltılabilir. */
    SchemaDdlGuard(JdbcTemplate jdbc, int lockTimeoutSeconds) {
        this.jdbc = jdbc;
        this.lockTimeoutSeconds = Math.max(1, lockTimeoutSeconds);
    }

    /**
     * İfadenin istediği değişiklik katalogda zaten var mı (PostgreSQL)? {@code true} → ifade çalıştırılmamalı (kilit yok).
     * Tablo / kolon / kısıt hiç yoksa da {@code true}: eski yolda ifade "yok" hatasıyla düşer ve sessizce sayılırdı —
     * sonuç aynı, kilitsiz.
     */
    public boolean alreadyInPlace(String ddl) {
        if (ddl == null || !isPostgres()) return false;
        try {
            Matcher m = ALTER_TYPE.matcher(ddl);
            if (m.matches()) {
                String current = columnType(m.group(1), m.group(2));
                if (current == null) return true;
                String target = m.group(4) != null ? "character varying(" + Integer.parseInt(m.group(4)) + ")" : "text";
                return target.equals(current);
            }
            m = DROP_NOT_NULL.matcher(ddl);
            if (m.matches()) {
                Boolean notNull = columnNotNull(m.group(1), m.group(2));
                return notNull == null || !notNull;
            }
            m = DROP_CONSTRAINT.matcher(ddl);
            if (m.matches()) {
                Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass(?::text) AND conname = ?",
                        Integer.class, lower(m.group(1)), lower(m.group(2)));
                return n == null || n == 0;
            }
            m = SET_RELOPTIONS.matcher(ddl);
            if (m.matches()) return reloptionsPresent(m.group(1), m.group(2));
            m = CREATE_INDEX.matcher(ddl);
            if (m.find()) {
                Boolean exists = jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class, lower(m.group(1)));
                return Boolean.TRUE.equals(exists);
            }
        } catch (Exception e) {
            log.debug("Şema yaması ön denetimi yapılamadı, ifade çalıştırılacak: {} — {}", abbreviate(ddl), e.toString());
        }
        return false;
    }

    /**
     * İfadeyi çalıştırır; PostgreSQL'de (CONCURRENTLY hariç) kilit beklemesi {@code lock_timeout} ile sınırlı. Aynı bağlantıda
     * {@code SET} → ifade → {@code RESET}: oturum ayarı havuza sızmaz.
     *
     * @param dml {@code true} = UPDATE/INSERT/DELETE (etkilenen satır sayısı döner); {@code false} = DDL (0 döner)
     */
    public int executeBounded(String ddl, boolean dml) {
        if (!isPostgres() || CONCURRENTLY.matcher(ddl).find()) {
            if (dml) return jdbc.update(ddl);
            jdbc.execute(ddl);
            return 0;
        }
        Integer rows = jdbc.execute((ConnectionCallback<Integer>) conn -> runWithLockTimeout(conn, ddl, dml));
        return rows == null ? 0 : rows;
    }

    private int runWithLockTimeout(Connection conn, String ddl, boolean dml) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET lock_timeout = '" + lockTimeoutSeconds + "s'");
            try {
                if (dml) return st.executeUpdate(ddl);
                st.execute(ddl);
                return 0;
            } finally {
                try {
                    st.execute("RESET lock_timeout");
                } catch (SQLException e) {
                    log.debug("lock_timeout sıfırlanamadı (bağlantı havuzdan düşecek): {}", e.getMessage());
                }
            }
        }
    }

    /** Kilit {@link #LOCK_TIMEOUT_SECONDS} sn içinde alınamadı mı (SQLSTATE 55P03)? */
    public static boolean isLockTimeout(Throwable e) {
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable t = e; t != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; t = t.getCause()) {
            if (t instanceof SQLException se && SQLSTATE_LOCK_NOT_AVAILABLE.equals(se.getSQLState())) return true;
        }
        return false;
    }

    public int lockTimeoutSeconds() { return lockTimeoutSeconds; }

    private String columnType(String table, String column) {
        List<String> r = jdbc.queryForList(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
              + "WHERE attrelid = to_regclass(?::text) AND attname = ? AND attnum > 0 AND NOT attisdropped",
                String.class, lower(table), lower(column));
        return r.isEmpty() ? null : r.get(0);
    }

    private Boolean columnNotNull(String table, String column) {
        List<Boolean> r = jdbc.queryForList(
                "SELECT attnotnull FROM pg_attribute "
              + "WHERE attrelid = to_regclass(?::text) AND attname = ? AND attnum > 0 AND NOT attisdropped",
                Boolean.class, lower(table), lower(column));
        return r.isEmpty() ? null : r.get(0);
    }

    /** {@code SET (a = 1, b = 2)} seçeneklerinin hepsi tabloda aynı değerle duruyor mu? Tablo yoksa da true. */
    private boolean reloptionsPresent(String table, String options) {
        Set<String> wanted = new HashSet<>();
        for (String part : options.split(",")) {
            String p = part.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (!p.isEmpty()) wanted.add(p);
        }
        if (wanted.isEmpty()) return false;
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class, lower(table));
        if (!Boolean.TRUE.equals(exists)) return true;
        Set<String> present = jdbc.query("SELECT reloptions FROM pg_class WHERE oid = to_regclass(?::text)", rs -> {
            Set<String> out = new HashSet<>();
            if (rs.next()) {
                Array arr = rs.getArray(1);
                if (arr != null) {
                    for (Object o : (Object[]) arr.getArray()) {
                        if (o != null) out.add(o.toString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
                    }
                }
            }
            return out;
        }, lower(table));
        return present != null && present.containsAll(wanted);
    }

    private boolean isPostgres() {
        if (postgres == null) {
            try {
                DataSource ds = jdbc.getDataSource();
                if (ds == null) { postgres = false; return false; }
                try (Connection c = ds.getConnection()) {
                    postgres = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
                }
            } catch (Exception e) {
                postgres = false;
            }
        }
        return postgres;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    static String abbreviate(String ddl) {
        return ddl == null ? "" : ddl.length() > 60 ? ddl.substring(0, 60) + "…" : ddl;
    }
}
