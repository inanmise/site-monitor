package com.sitemonitor.service.dbquery;

import java.util.List;

/**
 * Sorgu güvenlik denetimi (KATI; sözleşme dbquery-contract.md §2a). Saf, yan etkisiz, sınırlı süreli; kayıtta, denemede ve
 * HER çalıştırmadan önce çağrılır. {@code ok == violations.isEmpty()}.
 *
 * <p>İSKELET — gövde motor paketinde yazılır; imzalar sözleşmedir (eklenebilir, değiştirilemez).
 */
public final class DbQuerySqlGuard {

    public static final int MAX_SQL_CHARS = 10_000;

    /**
     * @param code    kalıcı kod (SQL_EMPTY, SQL_TOO_LONG, SQL_CONTROL_CHAR, SQL_UNTERMINATED, SQL_MULTIPLE_STATEMENTS,
     *                SQL_NOT_SELECT, SQL_FORBIDDEN_KEYWORD, SQL_LOCKING_CLAUSE, SQL_INTO, SQL_FORBIDDEN_FUNCTION,
     *                SQL_LOCK_HINT, SQL_MSSQL_NOLOCK_REQUIRED, SQL_UNPARSABLE)
     * @param token   ihlale yol açan sözcük / fonksiyon / tablo (yoksa null)
     * @param message kullanıcıya gösterilecek açıklama (Msg.t — istek dilinde)
     * @param line    1 tabanlı satır (bilinmiyorsa null)
     * @param column  1 tabanlı sütun (bilinmiyorsa null)
     */
    public record Violation(String code, String token, String message, Integer line, Integer column) {}

    /** @param tables sorgunun okuduğu tablolar (şema dahil, küçük harf; ayrıştırılamazsa boş). */
    public record Result(boolean ok, List<Violation> violations, List<String> tables) {}

    private DbQuerySqlGuard() {}

    public static Result check(String sql, DbType type) {
        throw new UnsupportedOperationException("DbQuerySqlGuard.check — motor paketinde yazılacak");
    }
}
