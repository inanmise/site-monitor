package com.sitemonitor.service.dbquery;

/**
 * Çalıştırıcıya giden bağlantı tanımı (düz parola YALNIZ bellekte, çağrı süresince). {@code toString} parolayı yazmaz.
 *
 * @param oracleMode    yalnız Oracle: "SERVICE" | "SID"
 * @param mssqlInstance yalnız MSSQL: adlandırılmış örnek ya da null
 * @param sslMode       yalnız PostgreSQL: disable | prefer | require | verify-full (null = prefer)
 */
public record DbConnectionSpec(DbType type, String host, int port, String database, String oracleMode,
                               String mssqlInstance, String username, String password, String sslMode,
                               boolean mssqlEncrypt, boolean mssqlTrustServerCertificate, int connectTimeoutMs) {

    @Override
    public String toString() {
        return "DbConnectionSpec[" + type + " " + host + ":" + port + "/" + database + " user=" + username + "]";
    }
}
