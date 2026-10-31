package com.sitemonitor.service.dbquery;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * JDBC çalıştırıcı (sözleşme §2b, §2c): bağlantı alanlardan kurulur (URL kullanıcıdan alınmaz), salt-okur oturum, zaman aşımı,
 * satır sınırı, iptal bekçisi, her durumda rollback + kapat. Havuz YOK. Asla fırlatmaz (hata {@code ok=false} + kod).
 * İSKELET — gövde motor paketinde yazılır.
 */
@Service
public class DbQueryRunner {

    /** @param type JDBC tür adı (VARCHAR, NUMERIC, TIMESTAMP …). */
    public record Column(String name, String type) {}

    /**
     * @param rows             okunan satırlar (≤ maxRows; hücre: sayı → BigDecimal, mantıksal → Boolean, diğer → String
     *                         (tarih / saat ISO-8601), ikili → "&lt;binary N bytes&gt;", null); hücre ≤ 4000 karakter
     * @param rowCount         okunan satır sayısı (≤ maxRows)
     * @param rowCountCapped   sorgu maxRows'tan fazla satır döndürdü ("≥ rowCount")
     * @param valuesTruncated  bellek bütçesi doldu: sayılan ama saklanmayan satır var / hücre kısaltıldı
     * @param failureReason    DB_UNREACHABLE | DB_DNS | DB_TIMEOUT | DB_AUTH | DB_SQL_ERROR | DB_DRIVER | DB_BLOCKED | null
     * @param error            maskeli kısa hata metni (≤ 500) ya da null
     */
    public record QueryResult(boolean ok, long durationMs, int rowCount, boolean rowCountCapped, List<Column> columns,
                              List<List<Object>> rows, boolean valuesTruncated, String error, String failureReason,
                              String serverVersion) {}

    public record ConnectionTest(boolean ok, long durationMs, String serverVersion, String error, String failureReason) {}

    /** @param supported false = bu tür için plan okunamaz (Oracle); cost / rows planın tahmini. */
    public record PlanResult(boolean supported, boolean ok, Double cost, Double rows, String error) {}

    public QueryResult run(DbConnectionSpec spec, String sql, int timeoutSeconds, int maxRows) {
        throw new UnsupportedOperationException("DbQueryRunner.run");
    }

    public ConnectionTest test(DbConnectionSpec spec) {
        throw new UnsupportedOperationException("DbQueryRunner.test");
    }

    public PlanResult plan(DbConnectionSpec spec, String sql, int timeoutSeconds) {
        throw new UnsupportedOperationException("DbQueryRunner.plan");
    }
}
