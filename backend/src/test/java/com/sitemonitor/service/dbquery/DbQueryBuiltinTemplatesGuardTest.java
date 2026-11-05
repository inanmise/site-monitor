package com.sitemonitor.service.dbquery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Yerleşik başlangıç ve hazır DBA şablonları (dbq2 B5, 2026-11-05) GERÇEK güvenlik denetiminden geçer — ön yüzün
 * sunduğu her şablon kayıtta / denemede reddedilemez. Kaynak: {@code frontend/src/components/dbquery/builtinTemplatesSql.json}
 * (şablon × tür → satırlar; ön yüz aynı dosyayı içe aktarır). Her şablon her türde (PostgreSQL / SQL Server / Oracle)
 * {@link DbQuerySqlGuard#check} ile denetlenir; SQL Server'da her gerçek tablo / sistem görünümü {@code WITH (NOLOCK)}
 * taşımalı (guard zaten ister). Yeni şablon eklenince bu test kendiliğinden kapsar.
 */
class DbQueryBuiltinTemplatesGuardTest {

    private static final Path SQL_JSON = Path.of("../frontend/src/components/dbquery/builtinTemplatesSql.json");

    @Test
    void everyBuiltinTemplatePassesTheGuardForItsType() throws Exception {
        assertThat(SQL_JSON).as("şablon SQL dosyası").exists();
        JsonNode root = new ObjectMapper().readTree(Files.readString(SQL_JSON));
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> tpl = it.next();
            for (DbType type : DbType.values()) {
                JsonNode lines = tpl.getValue().get(type.name());
                if (lines == null || !lines.isArray() || lines.isEmpty()) {
                    problems.add(tpl.getKey() + " / " + type + ": SQL yok");
                    continue;
                }
                List<String> parts = new ArrayList<>();
                lines.forEach(l -> parts.add(l.asText()));
                String sql = String.join("\n", parts);
                DbQuerySqlGuard.Result r = DbQuerySqlGuard.check(sql, type);
                checked++;
                if (!r.ok()) {
                    problems.add(tpl.getKey() + " / " + type + ": " + r.violations().stream()
                            .map(v -> v.code() + " " + v.token()).toList());
                }
            }
        }
        assertThat(problems).as("güvenlik denetiminden geçmeyen yerleşik şablonlar").isEmpty();
        // 4 uygulama + 5 DBA şablonu × 3 tür
        assertThat(checked).isGreaterThanOrEqualTo(27);
    }
}
