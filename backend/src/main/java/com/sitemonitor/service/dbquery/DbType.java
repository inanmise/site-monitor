package com.sitemonitor.service.dbquery;

import java.util.Locale;

/** Desteklenen veritabanı türleri (2026-10-31). Adlar kalıcıdır ({@code db_connection_profiles.db_type}). */
public enum DbType {
    ORACLE(1521), MSSQL(1433), POSTGRESQL(5432);

    private final int defaultPort;

    DbType(int defaultPort) { this.defaultPort = defaultPort; }

    public int defaultPort() { return defaultPort; }

    /** Büyük-küçük harf duyarsız; bilinmeyen / boş → null. */
    public static DbType parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
