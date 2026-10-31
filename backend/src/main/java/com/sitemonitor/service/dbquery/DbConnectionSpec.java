package com.sitemonitor.service.dbquery;

/**
 * Çalıştırıcıya giden bağlantı tanımı (düz parola YALNIZ bellekte, çağrı süresince). {@code toString} parolayı yazmaz.
 *
 * @param oracleMode    yalnız Oracle: "SERVICE" | "SID"
 * @param mssqlInstance yalnız MSSQL: adlandırılmış örnek ya da null
 * @param sslMode       yalnız PostgreSQL: disable | prefer | require | verify-full (null = prefer)
 * @param appName       veritabanı oturumunun uygulama adı / programı ve sorgu başı yorumu (r177, DBA izlenebilirliği):
 *                      {@code SiteMonitor dbq#<id>} ya da {@code SiteMonitor dbq-test}; null = {@code SiteMonitor}
 * @param oracleTcps    yalnız Oracle: TLS (TCPS) ile bağlan (r177) — sunucu sertifikası ve adı doğrulanır
 */
public record DbConnectionSpec(DbType type, String host, int port, String database, String oracleMode,
                               String mssqlInstance, String username, String password, String sslMode,
                               boolean mssqlEncrypt, boolean mssqlTrustServerCertificate, int connectTimeoutMs,
                               String appName, boolean oracleTcps) {

    /** Uygulama adı yoksa kullanılan ad (eski davranış). */
    public static final String DEFAULT_APP_NAME = "SiteMonitor";

    /** Eski on iki alanlı biçim (uygulama adı = {@value #DEFAULT_APP_NAME}, Oracle TCPS kapalı). */
    public DbConnectionSpec(DbType type, String host, int port, String database, String oracleMode,
                            String mssqlInstance, String username, String password, String sslMode,
                            boolean mssqlEncrypt, boolean mssqlTrustServerCertificate, int connectTimeoutMs) {
        this(type, host, port, database, oracleMode, mssqlInstance, username, password, sslMode, mssqlEncrypt,
                mssqlTrustServerCertificate, connectTimeoutMs, null, false);
    }

    /** Aynı tanım, başka uygulama adıyla. */
    public DbConnectionSpec withAppName(String name) {
        return new DbConnectionSpec(type, host, port, database, oracleMode, mssqlInstance, username, password, sslMode,
                mssqlEncrypt, mssqlTrustServerCertificate, connectTimeoutMs, name, oracleTcps);
    }

    /**
     * Güvenli uygulama adı: yalnız harf, rakam, boşluk ve {@code # . _ -} (en çok 60) — sürücü özelliğine ve SQL yorumuna
     * girer; yorumu kapatabilecek {@code * /} karakterleri hiç geçmez. Boş kalırsa {@value #DEFAULT_APP_NAME}.
     */
    public String safeAppName() {
        String raw = appName == null ? DEFAULT_APP_NAME : appName;
        StringBuilder b = new StringBuilder(Math.min(60, raw.length()));
        for (int i = 0; i < raw.length() && b.length() < 60; i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ' ' || c == '#' || c == '.' || c == '_' || c == '-';
            if (ok) b.append(c);
        }
        String s = b.toString().trim();
        return s.isEmpty() ? DEFAULT_APP_NAME : s;
    }

    @Override
    public String toString() {
        return "DbConnectionSpec[" + type + " " + host + ":" + port + "/" + database + " user=" + username + "]";
    }
}
