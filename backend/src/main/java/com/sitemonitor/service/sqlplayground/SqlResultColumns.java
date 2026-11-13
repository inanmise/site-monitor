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

    /** Zaman türü: değer biçimi (2026-11-13). */
    static final int K_OTHER = 0, K_DATE = 1, K_TIMESTAMP = 2, K_TIMESTAMPTZ = 3;

    private static final java.time.format.DateTimeFormatter LOCAL_SECONDS =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final java.time.format.DateTimeFormatter UTC_MILLIS =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private final String[] labels;
    private final boolean[] masked;
    private final int[] kinds;
    private final Set<String> maskedLabels = new LinkedHashSet<>();

    /** Tekilleştirmede yeniden adlandırılan kolonlar: {@code {original, name}} (yoksa boş). */
    private final List<java.util.Map<String, String>> renamed;

    private SqlResultColumns(String[] labels, boolean[] masked, int[] kinds, List<java.util.Map<String, String>> renamed) {
        this.labels = labels;
        this.renamed = renamed;
        this.masked = masked;
        this.kinds = kinds;
        for (int i = 0; i < labels.length; i++) if (masked[i]) maskedLabels.add(labels[i]);
    }

    public static SqlResultColumns of(ResultSetMetaData md, SqlSensitiveColumns.Schema schema, boolean failClosed)
            throws SQLException {
        int n = md.getColumnCount();
        String[] labels = new String[n];
        boolean[] masked = new boolean[n];
        int[] kinds = new int[n];
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
            kinds[i] = timeKind(md, col);
        }
        List<java.util.Map<String, String>> renamed = new ArrayList<>();
        return new SqlResultColumns(uniqueLabels(labels, renamed), masked, kinds, java.util.Collections.unmodifiableList(renamed));
    }

    public int size() { return labels.length; }

    public String label(int i) { return labels[i]; }

    public String[] labels() { return labels.clone(); }

    /** Tekilleştirmede yeniden adlandırılan kolonlar ({@code [{original, name}]}, yoksa boş). */
    public List<java.util.Map<String, String>> renamed() { return renamed; }

    /**
     * Sonuç kolon adlarını tekilleştirir (2026-11-13): {@code SELECT a.id, b.id} iki kolonu aynı adla döndürür ve satır
     * nesnesinde ikinci değer birincinin üstüne yazılırdı (sessiz veri kaybı). İlk geçiş aynen kalır, sonrakiler
     * {@code ad_2}, {@code ad_3} … — büyük/küçük harf duyarsız (satır haritası öyle), sonuçta ZATEN var olan bir adla ya da
     * daha önce verilmiş bir adla çakışmaz. Döngü sınırlı: n kolonda en çok 2n + 1 aday denenir (engel ≤ 2n ad).
     * Benzersiz kolonlu sorguda dizi birebir aynı döner.
     */
    static String[] uniqueLabels(String[] raw, List<java.util.Map<String, String>> renamedOut) {
        int n = raw.length;
        String[] out = new String[n];
        Set<String> original = new java.util.HashSet<>();
        for (String l : raw) original.add(key(l));
        Set<String> used = new java.util.HashSet<>();
        java.util.Map<String, Integer> nextSuffix = new java.util.HashMap<>();   // aynı adın sonraki geçişi kaldığı yerden
        for (int i = 0; i < n; i++) {
            String l = raw[i] == null ? "" : raw[i];
            if (used.add(key(l))) {
                out[i] = l;
                continue;
            }
            String name = null;
            int k = nextSuffix.getOrDefault(key(l), 2);
            for (; k <= 2 * n + 2 && name == null; k++) {
                String cand = l + "_" + k;
                String ck = key(cand);
                if (!original.contains(ck) && !used.contains(ck)) name = cand;
            }
            nextSuffix.put(key(l), k);
            if (name == null) name = l + "_" + (i + 1) + "_" + n;   // erişilemez — yine de tekil bir ad
            used.add(key(name));
            out[i] = name;
            renamedOut.add(java.util.Map.of("original", l, "name", name));
        }
        return out;
    }

    private static String key(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean masked(int i) { return masked[i]; }

    /** Kökene / ada göre maskelenen kolonlar + okuma sırasında değer kuralıyla (şifreli değer) maskelenenler. */
    public List<String> maskedLabels() { return new ArrayList<>(maskedLabels); }

    /**
     * Satırın i. hücresi — maskeli kolon ya da şifreli değer biçimi {@link SqlSensitiveColumns#MASK} olur (null null kalır).
     */
    public Object cell(ResultSet rs, int i) throws SQLException {
        Object v = read(rs, i);
        if (v == null) return null;
        if (masked[i]) return SqlSensitiveColumns.MASK;
        if (SqlSensitiveColumns.isEncryptedValue(v)) {
            maskedLabels.add(labels[i]);
            return SqlSensitiveColumns.MASK;
        }
        return v;
    }

    /**
     * Hücre değeri — zaman türleri metne çevrilir (JVM saat dilimine göre kaydırılmaz):
     * {@code date} → {@code yyyy-MM-dd}; zamansız {@code timestamp} → yerel {@code yyyy-MM-ddTHH:mm:ss[.SSS]} (milisaniye
     * altı varsa mikro / nano basamakları); {@code timestamptz} → UTC {@code yyyy-MM-ddTHH:mm:ss.SSSZ} (eski biçim).
     * Önceden {@code java.sql.Date} JSON'da UTC'ye kaymış gece yarısı ({@code …T21:00:00.000Z}, bir gün geride) olarak dönüyordu.
     */
    private Object read(ResultSet rs, int i) throws SQLException {
        int col = i + 1;
        try {
            switch (kinds[i]) {
                case K_DATE -> {
                    java.time.LocalDate d = rs.getObject(col, java.time.LocalDate.class);
                    return d == null ? null : formatDate(d);
                }
                case K_TIMESTAMP -> {
                    java.time.LocalDateTime t = rs.getObject(col, java.time.LocalDateTime.class);
                    return t == null ? null : formatLocal(t);
                }
                case K_TIMESTAMPTZ -> {
                    java.time.OffsetDateTime t = rs.getObject(col, java.time.OffsetDateTime.class);
                    return t == null ? null : formatInstant(t.toInstant());
                }
                default -> { }
            }
        } catch (SQLException | RuntimeException e) {
            // Sürücü türü çeviremedi (ör. sonsuz tarih) — metin karşılığı
            String s = rs.getString(col);
            return s;
        }
        return JdbcUtils.getResultSetValue(rs, col);
    }

    static int timeKind(ResultSetMetaData md, int col) {
        int type;
        String name;
        try {
            type = md.getColumnType(col);
            name = md.getColumnTypeName(col);
        } catch (SQLException e) {
            return K_OTHER;
        }
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (type == java.sql.Types.TIMESTAMP_WITH_TIMEZONE || n.equals("timestamptz") || n.contains("with time zone")) {
            return n.startsWith("time") && !n.startsWith("timestamp") ? K_OTHER : K_TIMESTAMPTZ;
        }
        if (type == java.sql.Types.DATE || n.equals("date")) return K_DATE;
        if (type == java.sql.Types.TIMESTAMP || n.equals("timestamp") || n.startsWith("timestamp")) return K_TIMESTAMP;
        return K_OTHER;
    }

    static String formatDate(java.time.LocalDate d) {
        return d.toString();
    }

    static String formatLocal(java.time.LocalDateTime t) {
        String base = LOCAL_SECONDS.format(t);
        int nano = t.getNano();
        if (nano == 0) return base;
        if (nano % 1_000_000 == 0) return base + String.format(".%03d", nano / 1_000_000);
        if (nano % 1_000 == 0) return base + String.format(".%06d", nano / 1_000);
        return base + String.format(".%09d", nano);
    }

    static String formatInstant(java.time.Instant t) {
        return UTC_MILLIS.format(t);
    }
}
