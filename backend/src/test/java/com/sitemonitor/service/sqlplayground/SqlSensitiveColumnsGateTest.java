package com.sitemonitor.service.sqlplayground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: kalıcı şemada adı hassas desene uyan ({@code password|passwd|secret|token|hmac|api_key|private_key|session_id}
 * ya da {@code _enc} soneki) HER kolon için bilinçli karar verilmiş olmalı — {@link SqlSensitiveColumns#PAIRS}'te (maskelenir)
 * ya da {@link SqlSensitiveColumns#EXEMPT}'te gerekçesiyle. Tarama: {@code @Entity} sınıfları + kaynaktaki ham DDL.
 * Ayrıca katalogdaki her çift / muafiyet gerçekten var olan bir kolonu işaret eder (yalnız Spring Session tabloları dış şema).
 */
class SqlSensitiveColumnsGateTest {

    /** Uygulamanın kodunda DDL'i olmayan, kütüphanenin kurduğu tablolar. */
    private static final Set<String> EXTERNAL_TABLES = Set.of("spring_session", "spring_session_attributes",
            "pg_authid", "pg_shadow", "pg_user_mapping", "pg_user_mappings");

    @Test
    @DisplayName("Desene uyan her kolon katalogda (PAIRS) ya da gerekçeli muaf listede (EXEMPT)")
    void everyPatternColumnIsDecided() throws Exception {
        Map<String, Set<String>> schema = SchemaSources.allColumns();
        List<String> undecided = new ArrayList<>();
        schema.forEach((table, cols) -> {
            for (String col : cols) {
                if (!SqlSensitiveColumns.matchesNamePattern(col)) continue;
                boolean inPairs = SqlSensitiveColumns.PAIRS.getOrDefault(table, Set.of()).contains(col);
                boolean exempt = SqlSensitiveColumns.isExempt(table, col);
                if (!inPairs && !exempt) undecided.add(table + "." + col);
            }
        });
        assertThat(undecided)
                .as("Hassas desene uyan kolonlar için karar verin: SqlSensitiveColumns.PAIRS'e ekleyin (maskelenir) ya da "
                        + "EXEMPT'e gerekçesiyle yazın (sır değil): %s", undecided)
                .isEmpty();
    }

    @Test
    @DisplayName("Katalogdaki her çift ve muafiyet gerçek bir kolonu işaret eder; her muafiyetin gerekçesi var")
    void catalogPointsToRealColumns() throws Exception {
        Map<String, Set<String>> schema = SchemaSources.allColumns();
        List<String> stale = new ArrayList<>();
        SqlSensitiveColumns.PAIRS.forEach((table, cols) -> {
            if (EXTERNAL_TABLES.contains(table)) return;
            for (String c : cols) if (!schema.getOrDefault(table, Set.of()).contains(c)) stale.add(table + "." + c);
        });
        SqlSensitiveColumns.EXEMPT.forEach((key, why) -> {
            int dot = key.indexOf('.');
            String table = key.substring(0, dot), col = key.substring(dot + 1);
            if (!schema.getOrDefault(table, Set.of()).contains(col)) stale.add(key);
            assertThat(why).as("%s: gerekçe zorunlu", key).isNotBlank();
        });
        assertThat(stale).as("Katalogda var olmayan kolonlar: %s", stale).isEmpty();
    }

    @Test
    @DisplayName("Kural: açık çift + desen hassastır, muafiyet değildir; şifreli değer biçimi her kolonda maskelenir")
    void rules() {
        assertThat(SqlSensitiveColumns.isSensitive("app_users", "password_hash")).isTrue();
        assertThat(SqlSensitiveColumns.isSensitive("APP_USERS", "PASSWORD_HASH")).isTrue();
        assertThat(SqlSensitiveColumns.isSensitive("app_users", "must_change_password")).isFalse();
        assertThat(SqlSensitiveColumns.isSensitive("some_new_table", "client_secret")).isTrue();
        assertThat(SqlSensitiveColumns.isSensitive("some_new_table", "foo_enc")).isTrue();
        assertThat(SqlSensitiveColumns.isSensitive("db_connection_profiles", "mssql_encrypt")).isFalse();
        assertThat(SqlSensitiveColumns.isSensitive("scripted_monitors", "env_json")).isTrue();
        assertThat(SqlSensitiveColumns.isSensitive("teams", "name")).isFalse();
        assertThat(SqlSensitiveColumns.isEncryptedValue("enc:v1:abcd")).isTrue();
        assertThat(SqlSensitiveColumns.isEncryptedValue("{\"h\":\"enc:v1:xx\"}")).isTrue();
        assertThat(SqlSensitiveColumns.isEncryptedValue("plain")).isFalse();
        assertThat(SqlSensitiveColumns.isEncryptedValue(42)).isFalse();
    }
}
