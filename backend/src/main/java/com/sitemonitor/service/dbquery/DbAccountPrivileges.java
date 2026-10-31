package com.sitemonitor.service.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bağlantı hesabının yazma / yönetici yetkisi denetimi (r177, Ö-1): kabul edilen bütün artık riskler (Oracle otonom işlem,
 * MSSQL'de salt-okur işlem kavramı olmaması, kural değeri kanalı) "en az ayrıcalıklı salt-okur hesap" varsayımına dayanır —
 * bu denetim varsayımı ÖLÇER. Yalnız sistem kataloğu okunur (salt-okur, aynı salt-okur oturumda, sorgu başına ≤ 10 sn);
 * sorgu okunamazsa {@link #CHECK_FAILED} (engellemez — "bilinmiyor").
 *
 * <ul>
 *   <li>PostgreSQL: {@code pg_roles} (süper kullanıcı, CREATEROLE / CREATEDB, BYPASSRLS), {@code pg_write_all_data} üyeliği,
 *       kullanıcı şemalarında herhangi bir tabloya INSERT / UPDATE / DELETE / TRUNCATE (LIMIT 1), şemada / veritabanında
 *       CREATE.</li>
 *   <li>MSSQL: {@code IS_SRVROLEMEMBER('sysadmin')}, {@code IS_ROLEMEMBER} db_owner / db_datawriter / db_ddladmin,
 *       {@code HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', INSERT | UPDATE | DELETE | ALTER | EXECUTE)}. Tek tek tablo
 *       yetkileri taranmaz (katalog sorgusu pahalı) — belgelenmiş sınır.</li>
 *   <li>Oracle: {@code SESSION_ROLES} (DBA ve tam içe / dışa aktarım rolleri), {@code SESSION_PRIVS} (okuma dışı ANY,
 *       CREATE / ALTER / DROP / GRANT …), alınan nesne yetkileri (INSERT / UPDATE / DELETE / EXECUTE —
 *       {@code USER_TAB_PRIVS_RECD} + rol yetkileri), hesabın kendi tablosu / kodu.</li>
 * </ul>
 * Bulgu kodları KALICIDIR (ön yüz {@code dbq.priv.<KOD>}). {@link #INFO_CODES} (r177 ikinci tur, O-5) yalnız BİLGİdir:
 * hesabı "yazabilir" yapmaz ({@code read_only} true kalır), BLOCK kipinde engellemez.
 */
public final class DbAccountPrivileges {

    public static final String SUPERUSER = "SUPERUSER", CAN_WRITE_TABLES = "CAN_WRITE_TABLES", CAN_CREATE = "CAN_CREATE",
            DB_OWNER = "DB_OWNER", DATA_WRITER = "DATA_WRITER", DDL_ADMIN = "DDL_ADMIN", CAN_EXECUTE = "CAN_EXECUTE",
            DBA_ROLE = "DBA_ROLE", ANY_PRIVILEGE = "ANY_PRIVILEGE", CHECK_FAILED = "CHECK_FAILED",
            /**
             * PostgreSQL {@code public} şemasında CREATE yalnız PUBLIC rolünden geliyor (PG ≤ 14 varsayılanı, PG 15+'a
             * {@code pg_upgrade} ile taşınan veritabanları) — hesaba özel bir yetki değil, bilgi (r177 ikinci tur, O-5).
             * DBA {@code REVOKE CREATE ON SCHEMA public FROM PUBLIC} ile kapatabilir.
             */
            PUBLIC_SCHEMA_CREATE = "PUBLIC_SCHEMA_CREATE";

    /** Tüm bulgu kodları (sıra kalıcı; yenisi sona). */
    public static final List<String> CODES = List.of(SUPERUSER, CAN_WRITE_TABLES, CAN_CREATE, DB_OWNER, DATA_WRITER,
            DDL_ADMIN, CAN_EXECUTE, DBA_ROLE, ANY_PRIVILEGE, CHECK_FAILED, PUBLIC_SCHEMA_CREATE);

    /** Yalnız bilgi bulguları — hesabı yazabilir saymaz. */
    public static final java.util.Set<String> INFO_CODES = java.util.Set.of(PUBLIC_SCHEMA_CREATE);

    static final int QUERY_TIMEOUT_SECONDS = 10;
    static final int MAX_FINDINGS = 20, MAX_DETAIL = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param detail kısa açıklama (rol / yetki adı, örnek tablo) — veri değil, katalog bilgisi */
    public record Finding(String code, String detail) {}

    /**
     * @param checked  denetim yapıldı mı (ayar kapalı / bağlantı kurulamadı → false)
     * @param readOnly true = hiçbir yazma / yönetici yetkisi bulunmadı; false = en az bir bulgu; null = bilinmiyor
     *                 (denetim yapılmadı ya da bir sorgu okunamadı ve bulgu yok)
     */
    public record Result(boolean checked, Boolean readOnly, List<Finding> findings) {
        public static final Result NOT_CHECKED = new Result(false, null, List.of());

        /** API / sağlık biçimi: {@code {checked, read_only, findings: [{code, detail}]}}. */
        public Map<String, Object> toApi() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checked", checked);
            m.put("read_only", readOnly);
            List<Map<String, Object>> f = new ArrayList<>();
            if (findings != null) {
                for (Finding x : findings) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("code", x.code());
                    e.put("detail", x.detail());
                    f.add(e);
                }
            }
            m.put("findings", f);
            return m;
        }

        /** Yazabilen / yönetici hesap kesin mi (bulgu VAR; bilinmiyor değil). */
        public boolean writable() {
            return Boolean.FALSE.equals(readOnly);
        }
    }

    private DbAccountPrivileges() {}

    /** Bağlantı satırına yazılan özet (+ denetim anı, UTC ISO); hiçbir zaman parola / veri taşımaz. */
    public static String toJson(Result r, String checkedAt) {
        if (r == null) return null;
        Map<String, Object> m = r.toApi();
        m.put("checked_at", checkedAt);
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** Saklı özet (gevşek; bozuk → null). */
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null || m.isEmpty() ? null : m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Saklı özette hesap kesin olarak yazabiliyor mu ({@code read_only: false}). */
    public static boolean storedWritable(String json) {
        Map<String, Object> m = fromJson(json);
        return m != null && Boolean.FALSE.equals(m.get("read_only"));
    }

    // ── Denetim ──────────────────────────────────────────────────────────────

    /** Açık (salt-okur) oturumda denetler; asla fırlatmaz. */
    static Result check(Connection c, DbType type) {
        if (c == null || type == null) return Result.NOT_CHECKED;
        Acc a = new Acc();
        a.savepoints = type == DbType.POSTGRESQL;
        switch (type) {
            case POSTGRESQL -> postgres(c, a);
            case MSSQL -> mssql(c, a);
            case ORACLE -> oracle(c, a);
        }
        return a.result();
    }

    private static final class Acc {
        final List<Finding> findings = new ArrayList<>();
        boolean failed;
        /** PostgreSQL: her katalog sorgusu kayıt noktasında (başarısız ifade işlemi bozmasın). */
        boolean savepoints;

        void add(String code, String detail) {
            if (findings.size() >= MAX_FINDINGS) return;
            String d = detail == null ? null : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
            for (Finding f : findings) if (f.code().equals(code) && java.util.Objects.equals(f.detail(), d)) return;
            findings.add(new Finding(code, d));
        }

        Result result() {
            boolean anyWrite = false;
            for (Finding f : findings) if (!INFO_CODES.contains(f.code())) anyWrite = true;
            List<Finding> out = new ArrayList<>(findings);
            if (failed) out.add(new Finding(CHECK_FAILED, null));
            Boolean ro = anyWrite ? Boolean.FALSE : failed ? null : Boolean.TRUE;
            return new Result(true, ro, List.copyOf(out));
        }
    }

    /** Tek katalog sorgusu: {@code row} her satırda çağrılır (≤ 20 satır okunur). Hata → {@code failed}. */
    private interface RowReader {
        void read(ResultSet rs) throws Exception;
    }

    /**
     * PostgreSQL'de başarısız bir ifade işlemi bozar ("current transaction is aborted") — sonraki katalog sorguları ve
     * kullanıcının sorgusu da düşerdi. Her sorgu bir KAYIT NOKTASI içinde koşar (salt-okur işlemde izinli); hata → geri.
     */
    private static void query(Connection c, Acc a, String sql, RowReader row) {
        java.sql.Savepoint sp = null;
        try {
            if (a.savepoints) sp = c.setSavepoint();
        } catch (Throwable e) {
            a.failed = true;
            return;
        }
        boolean ok = false;
        try (Statement st = c.createStatement()) {
            st.setEscapeProcessing(false);
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            st.setMaxRows(MAX_FINDINGS);
            try (ResultSet rs = st.executeQuery(sql)) {
                int n = 0;
                while (rs.next() && n++ < MAX_FINDINGS) row.read(rs);
            }
            ok = true;
        } catch (Throwable e) {
            a.failed = true;
        } finally {
            if (sp != null) {
                try {
                    if (ok) c.releaseSavepoint(sp);
                    else c.rollback(sp);
                } catch (Throwable ignored) {
                    a.failed = true;
                }
            }
        }
    }

    static final String PG_ROLE = "SELECT rolsuper, rolcreaterole, rolcreatedb, rolbypassrls FROM pg_roles WHERE rolname = current_user";
    static final String PG_WRITE_ALL = "SELECT 1 FROM pg_roles r WHERE r.rolname = 'pg_write_all_data' "
            + "AND pg_has_role(current_user, r.oid, 'USAGE')";
    static final String PG_TABLE_WRITE = "SELECT n.nspname || '.' || c.relname FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f') "
            + "AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%' "
            + "AND (has_table_privilege(c.oid, 'INSERT') OR has_table_privilege(c.oid, 'UPDATE') "
            + "OR has_table_privilege(c.oid, 'DELETE') OR has_table_privilege(c.oid, 'TRUNCATE')) LIMIT 1";
    /**
     * CREATE yetkisi olan kullanıcı şemaları ve yetkinin KAYNAĞI (O-5): {@code direct} = hesap şemanın sahibi (ya da sahip
     * rolün üyesi) veya CREATE hesaba / üyesi olduğu bir role verilmiş; {@code via_public} = PUBLIC'e verilmiş.
     * {@code aclexplode(COALESCE(nspacl, acldefault('n', nspowner)))} şema ACL'sini satırlara açar (grantee 0 = PUBLIC).
     */
    static final String PG_SCHEMA_CREATE = "SELECT n.nspname, "
            + "(pg_has_role(n.nspowner, 'USAGE') OR EXISTS (SELECT 1 FROM aclexplode(COALESCE(n.nspacl, "
            + "acldefault('n', n.nspowner))) a WHERE a.privilege_type = 'CREATE' "
            // CASE: PUBLIC (0) için pg_has_role hiç çağrılmaz (AND sırası SQL'de güvence değil; 0 rolü yok → hata)
            + "AND CASE WHEN a.grantee = 0 THEN false ELSE pg_has_role(a.grantee, 'USAGE') END)) AS direct, "
            + "EXISTS (SELECT 1 FROM aclexplode(COALESCE(n.nspacl, acldefault('n', n.nspowner))) a "
            + "WHERE a.privilege_type = 'CREATE' AND a.grantee = 0) AS via_public "
            + "FROM pg_namespace n WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') "
            + "AND n.nspname NOT LIKE 'pg\\_%' AND has_schema_privilege(n.oid, 'CREATE') ORDER BY n.nspname LIMIT 20";
    static final String PG_DB_CREATE = "SELECT has_database_privilege(current_database(), 'CREATE')";

    private static void postgres(Connection c, Acc a) {
        query(c, a, PG_ROLE, rs -> {
            if (rs.getBoolean(1)) a.add(SUPERUSER, "superuser");
            if (rs.getBoolean(2)) a.add(CAN_CREATE, "CREATEROLE");
            if (rs.getBoolean(3)) a.add(CAN_CREATE, "CREATEDB");
            if (rs.getBoolean(4)) a.add(ANY_PRIVILEGE, "BYPASSRLS");
        });
        query(c, a, PG_WRITE_ALL, rs -> a.add(CAN_WRITE_TABLES, "pg_write_all_data"));
        query(c, a, PG_TABLE_WRITE, rs -> a.add(CAN_WRITE_TABLES, rs.getString(1)));
        query(c, a, PG_SCHEMA_CREATE, rs -> {
            String schema = rs.getString(1);
            boolean direct = rs.getBoolean(2);
            boolean viaPublic = rs.getBoolean(3);
            // Yalnız public şemasının PUBLIC kaynaklı CREATE'i bilgi; başka şemada (PUBLIC'e açılmış olsa da) gerçek yetki
            if (!direct && viaPublic && "public".equals(schema)) a.add(PUBLIC_SCHEMA_CREATE, "schema public");
            else a.add(CAN_CREATE, "schema " + schema);
        });
        query(c, a, PG_DB_CREATE, rs -> { if (rs.getBoolean(1)) a.add(CAN_CREATE, "database"); });
    }

    static final String MSSQL_ROLES = "SELECT IS_SRVROLEMEMBER('sysadmin'), IS_ROLEMEMBER('db_owner'), "
            + "IS_ROLEMEMBER('db_datawriter'), IS_ROLEMEMBER('db_ddladmin'), "
            + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'INSERT'), HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'UPDATE'), "
            + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'DELETE'), HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'ALTER'), "
            + "HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'EXECUTE')";

    private static void mssql(Connection c, Acc a) {
        query(c, a, MSSQL_ROLES, rs -> {
            if (rs.getInt(1) == 1) a.add(SUPERUSER, "sysadmin");
            if (rs.getInt(2) == 1) a.add(DB_OWNER, "db_owner");
            if (rs.getInt(3) == 1) a.add(DATA_WRITER, "db_datawriter");
            if (rs.getInt(4) == 1) a.add(DDL_ADMIN, "db_ddladmin");
            if (rs.getInt(5) == 1) a.add(CAN_WRITE_TABLES, "DATABASE INSERT");
            if (rs.getInt(6) == 1) a.add(CAN_WRITE_TABLES, "DATABASE UPDATE");
            if (rs.getInt(7) == 1) a.add(CAN_WRITE_TABLES, "DATABASE DELETE");
            if (rs.getInt(8) == 1) a.add(CAN_CREATE, "DATABASE ALTER");
            if (rs.getInt(9) == 1) a.add(CAN_EXECUTE, "DATABASE EXECUTE");
        });
    }

    static final String ORA_ROLES = "SELECT role FROM session_roles WHERE role IN ('DBA', 'IMP_FULL_DATABASE', "
            + "'EXP_FULL_DATABASE', 'DATAPUMP_IMP_FULL_DATABASE', 'DATAPUMP_EXP_FULL_DATABASE', 'SYSBACKUP', 'SYSDG', 'SYSKM')";
    static final String ORA_PRIVS = "SELECT privilege FROM session_privs WHERE privilege NOT IN ('CREATE SESSION', "
            + "'ALTER SESSION', 'SET CONTAINER') AND privilege NOT LIKE 'SELECT ANY %' AND privilege NOT LIKE 'READ ANY %' "
            + "AND (privilege LIKE '% ANY %' OR privilege LIKE 'CREATE %' OR privilege LIKE 'ALTER %' OR privilege LIKE 'DROP %' "
            + "OR privilege LIKE 'GRANT %' OR privilege IN ('UNLIMITED TABLESPACE', 'BECOME USER', 'EXEMPT ACCESS POLICY'))";
    static final String ORA_OBJ_WRITE = "SELECT privilege FROM user_tab_privs_recd WHERE privilege IN ('INSERT', "
            + "'UPDATE', 'DELETE', 'EXECUTE') AND ROWNUM <= 4";
    static final String ORA_ROLE_WRITE = "SELECT privilege FROM role_tab_privs WHERE role IN (SELECT role FROM session_roles) "
            + "AND privilege IN ('INSERT', 'UPDATE', 'DELETE', 'EXECUTE') AND ROWNUM <= 4";
    static final String ORA_OWN_TABLES = "SELECT 1 FROM user_tables WHERE ROWNUM = 1";
    static final String ORA_OWN_CODE = "SELECT 1 FROM user_objects WHERE object_type IN ('FUNCTION', 'PROCEDURE', 'PACKAGE') "
            + "AND ROWNUM = 1";

    private static void oracle(Connection c, Acc a) {
        query(c, a, ORA_ROLES, rs -> a.add(DBA_ROLE, rs.getString(1)));
        query(c, a, ORA_PRIVS, rs -> {
            String p = rs.getString(1);
            if (p == null) return;
            a.add(p.contains(" ANY ") ? ANY_PRIVILEGE : p.startsWith("CREATE ") ? CAN_CREATE : ANY_PRIVILEGE, p);
        });
        RowReader grant = rs -> {
            String p = rs.getString(1);
            if ("EXECUTE".equals(p)) a.add(CAN_EXECUTE, "EXECUTE grant");
            else if (p != null) a.add(CAN_WRITE_TABLES, p + " grant");
        };
        query(c, a, ORA_OBJ_WRITE, grant);
        query(c, a, ORA_ROLE_WRITE, grant);
        query(c, a, ORA_OWN_TABLES, rs -> a.add(CAN_WRITE_TABLES, "owns tables"));
        query(c, a, ORA_OWN_CODE, rs -> a.add(CAN_EXECUTE, "owns functions / procedures"));
    }
}
