package com.sitemonitor.service.sqlplayground;

import org.springframework.jdbc.support.JdbcUtils;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sonuç kolonlarının maskeleme planı (2026-11-13, A1) — KÖKENE göre.
 *
 * <p>PostgreSQL'de her sonuç kolonunun taban tablosu / kolonu {@code PGResultSetMetaData.getBaseTableName /
 * getBaseColumnName} ile okunur (sürücü çalışma zamanı bağımlılığı → yansıma). PostgreSQL kökeni alt sorgu, CTE ve
 * birleşim üzerinden taşır; takma ad ({@code password_hash AS x}) kökeni değiştirmez — maske atlatılamaz. İfadelerin kökeni
 * yoktur; onlar {@link SqlSensitiveGuard} tarafından zaten reddedilir.
 *
 * <p>Diğer sürücüler (H2 — testler): {@code getTableName / getColumnName}. Her durumda ek olarak AD yedeği uygulanır:
 * kolonun etiketi ya da kök adı en az bir tabloda hassas bir adsa maskelenir (zararsız fazla maske tercih edilir).
 *
 * <p><b>Kapalı başarısızlık.</b> PostgreSQL kök bilgisi okunamazsa ve sorgu hassas bir tablo adı taşıyorsa
 * ({@code failClosed}) {@link OriginUnavailableException} — sonuç GÖSTERİLMEZ.
 */
public final class SqlResultColumns {

    private static final Class<?> PG_MD;
    private static final Method BASE_TABLE;
    private static final Method BASE_COLUMN;

    static {
        Class<?> c = null;
        Method bt = null, bc = null;
        try {
            c = Class.forName("org.postgresql.PGResultSetMetaData");
            bt = c.getMethod("getBaseTableName", int.class);
            bc = c.getMethod("getBaseColumnName", int.class);
        } catch (Throwable ignored) {
            c = null;
        }
        PG_MD = c;
        BASE_TABLE = bt;
        BASE_COLUMN = bc;
    }

    /** Köken okunamadı ve sorgu hassas tablo taşıyor — sonuç gösterilmez. */
    public static final class OriginUnavailableException extends RuntimeException {
        public OriginUnavailableException(Throwable cause) { super("origin unavailable", cause, false, false); }
    }

    private final String[] labels;
    private final boolean[] masked;
    private final Set<String> maskedLabels = new LinkedHashSet<>();

    private SqlResultColumns(String[] labels, boolean[] masked) {
        this.labels = labels;
        this.masked = masked;
        for (int i = 0; i < labels.length; i++) if (masked[i]) maskedLabels.add(labels[i]);
    }

    public static SqlResultColumns of(ResultSetMetaData md, SqlSensitiveColumns.Schema schema, boolean failClosed)
            throws SQLException {
        int n = md.getColumnCount();
        String[] labels = new String[n];
        boolean[] masked = new boolean[n];
        Object pg = null;
        if (PG_MD != null) {
            try {
                if (md.isWrapperFor(PG_MD)) pg = md.unwrap(PG_MD);
            } catch (SQLException e) {
                pg = null;
            }
        }
        for (int i = 0; i < n; i++) {
            int col = i + 1;
            labels[i] = JdbcUtils.lookupColumnName(md, col);
            String table = null, column = null;
            if (pg != null) {
                try {
                    table = (String) BASE_TABLE.invoke(pg, col);
                    column = (String) BASE_COLUMN.invoke(pg, col);
                } catch (Exception e) {
                    if (failClosed) throw new OriginUnavailableException(e);
                }
            } else {
                try {
                    table = md.getTableName(col);
                    column = md.getColumnName(col);
                } catch (SQLException e) {
                    if (failClosed) throw new OriginUnavailableException(e);
                }
            }
            masked[i] = schema.isSensitive(table, column)
                    || schema.isSensitiveName(column)
                    || schema.isSensitiveName(labels[i]);
        }
        return new SqlResultColumns(labels, masked);
    }

    public int size() { return labels.length; }

    public String label(int i) { return labels[i]; }

    public String[] labels() { return labels.clone(); }

    public boolean masked(int i) { return masked[i]; }

    /** Kökene / ada göre maskelenen kolonlar + okuma sırasında değer kuralıyla (şifreli değer) maskelenenler. */
    public List<String> maskedLabels() { return new ArrayList<>(maskedLabels); }

    /**
     * Satırın i. hücresi — maskeli kolon ya da şifreli değer biçimi {@link SqlSensitiveColumns#MASK} olur (null null kalır).
     */
    public Object cell(ResultSet rs, int i) throws SQLException {
        Object v = JdbcUtils.getResultSetValue(rs, i + 1);
        if (v == null) return null;
        if (masked[i]) return SqlSensitiveColumns.MASK;
        if (SqlSensitiveColumns.isEncryptedValue(v)) {
            maskedLabels.add(labels[i]);
            return SqlSensitiveColumns.MASK;
        }
        return v;
    }
}
