package com.sitemonitor.service.sqlplayground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Hassas kolon reddi — GÜVENLİK tablosu. Her satır {@code {sql, beklenen}}: {@code ok} (çalışır; sonuç kökene göre maskelenir)
 * ya da ret nedeni ({@link SqlSensitiveGuard#REASONS}). Kapsam: takma ad, alt sorgu, CTE, UNION, WHERE kestirmesi (kâhin),
 * fonksiyon argümanı, dönüşüm, tüm satır ({@code to_jsonb(u)}, {@code u::text}), büyük/küçük harf, tırnaklı ad, kolon takma
 * ad listesi, konum numarası, istatistik görünümü, metinden sorgu, U&amp; tanımlayıcı.
 */
class SqlSensitiveGuardTest {

    private static final SqlSensitiveColumns.Schema SCHEMA = SqlSensitiveColumns.schemaOf(Map.of(
            "app_users", List.of("id", "username", "password_hash", "active_session_id", "team_id", "email", "active"),
            "teams", List.of("id", "name"),
            "audit_log", List.of("id", "session_id", "actor")));

    static final String[][] CASES = {
        // ── çalışır (maskeyle) ──────────────────────────────────────────────────────────────────────────────────
        {"SELECT username, password_hash FROM app_users", "ok"},
        {"SELECT u.username, u.password_hash AS h FROM app_users u", "ok"},
        {"SELECT u.password_hash h FROM app_users AS u", "ok"},
        {"SELECT * FROM app_users", "ok"},
        {"SELECT u.* FROM app_users u", "ok"},
        {"SELECT public.app_users.password_hash FROM public.app_users", "ok"},
        {"SELECT \"password_hash\" FROM app_users", "ok"},
        {"SELECT DISTINCT password_hash FROM app_users", "ok"},
        {"SELECT username FROM app_users WHERE active = true ORDER BY username", "ok"},
        {"SELECT password_hash FROM app_users ORDER BY username", "ok"},
        {"SELECT t.name, u.username FROM teams t JOIN app_users u ON u.team_id = t.id", "ok"},
        {"SELECT t.name, u.username FROM teams t LEFT JOIN app_users u ON u.team_id = t.id WHERE t.id > 1", "ok"},
        {"SELECT count(*) FROM app_users", "ok"},
        {"WITH x AS (SELECT username FROM app_users) SELECT username FROM x", "ok"},
        {"SELECT * FROM teams WHERE name LIKE '%password_hash%'", "ok"},
        {"SELECT (SELECT COUNT(*) FROM app_users WHERE active = false) AS passive", "ok"},
        {"SELECT token FROM remember_me_tokens", "ok"},
        {"SELECT * FROM app_users u ORDER BY u.username LIMIT 5", "ok"},
        {"SELECT username, password_hash FROM app_users ORDER BY 1", "ok"},
        {"SELECT username, email FROM app_users ORDER BY 2 DESC", "ok"},
        {"SELECT extract(year FROM now()) AS y, username FROM app_users", "ok"},
        {"SELECT username FROM app_users WHERE username IS DISTINCT FROM 'x'", "ok"},
        {"SELECT u.username FROM (app_users u JOIN teams t ON t.id = u.team_id)", "ok"},
        {"SELECT g, username FROM app_users, generate_series(1, 3) g", "ok"},
        {"SELECT 1 UNION SELECT 2", "ok"},
        // ── ifade içinde hassas kolon ───────────────────────────────────────────────────────────────────────────
        {"SELECT username FROM app_users WHERE password_hash LIKE '$2a%'", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT md5(password_hash) FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT password_hash::text FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT upper(PASSWORD_HASH) FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT \"Password_Hash\" || 'x' FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT username FROM app_users ORDER BY password_hash", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT username FROM app_users GROUP BY username, password_hash", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT u.username FROM app_users u JOIN password_history p ON p.password_hash = u.password_hash", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT username FROM app_users WHERE username IN (SELECT username FROM app_users WHERE password_hash = 'x')", SqlSensitiveGuard.R_EXPRESSION},
        {"WITH h AS (SELECT password_hash FROM app_users) SELECT * FROM h", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT CASE WHEN password_hash LIKE 'a%' THEN 1 END FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT password_hash AS p FROM app_users ORDER BY p", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT * FROM (SELECT password_hash FROM app_users) s", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT * FROM (SELECT * FROM app_users) s WHERE s.password_hash LIKE 'a%'", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT username FROM app_users u WHERE EXISTS (SELECT 1 FROM remember_me_tokens r WHERE r.token = 'x')", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT session_id FROM audit_log WHERE session_id = 'abc'", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT username FROM app_users WHERE length(password_hash) > 10", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT password_hash(u) FROM app_users u", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT (u).password_hash FROM app_users u", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT password_hash, row_number() OVER (ORDER BY password_hash) FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT DISTINCT ON (password_hash) username FROM app_users", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT u.username FROM app_users u JOIN password_history p USING (password_hash)", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT username FROM app_users WHERE (username, password_hash) = ('a', 'b')", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT env_json::jsonb FROM scripted_monitors", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT name FROM http_monitors WHERE basic_auth_pass_enc IS NOT NULL", SqlSensitiveGuard.R_EXPRESSION},
        // ── tüm satır değer olarak ──────────────────────────────────────────────────────────────────────────────
        {"SELECT to_jsonb(u) FROM app_users u", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT u::text FROM app_users u", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT row_to_json(app_users) FROM app_users", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT app_users FROM app_users", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(u.*) FROM app_users u", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT jsonb_agg(x) FROM (SELECT * FROM app_users) x", SqlSensitiveGuard.R_WHOLE_ROW},
        {"WITH x AS (TABLE app_users) SELECT to_jsonb(x) FROM x", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT * FROM app_users u, LATERAL to_jsonb(u) j", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT username FROM app_users u WHERE u IS NOT NULL", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT username FROM app_users ORDER BY app_users", SqlSensitiveGuard.R_WHOLE_ROW},
        // ── küme işlemi / doğal birleşim / kolon takma ad listesi ───────────────────────────────────────────────
        {"SELECT username FROM app_users UNION SELECT 'x'", SqlSensitiveGuard.R_SET_OPERATION},
        {"SELECT * FROM (SELECT * FROM app_users UNION ALL SELECT * FROM app_users) s", SqlSensitiveGuard.R_SET_OPERATION},
        {"SELECT username FROM app_users EXCEPT SELECT name FROM teams", SqlSensitiveGuard.R_SET_OPERATION},
        {"SELECT u.username FROM app_users u NATURAL JOIN password_history p", SqlSensitiveGuard.R_NATURAL},
        {"SELECT c FROM app_users AS u(a, b, c)", SqlSensitiveGuard.R_ALIAS_LIST},
        {"WITH x(a, b) AS (SELECT * FROM app_users) SELECT a FROM x", SqlSensitiveGuard.R_ALIAS_LIST},
        {"SELECT * FROM (SELECT * FROM app_users) AS s(a, b)", SqlSensitiveGuard.R_ALIAS_LIST},
        // ── konum numarası ──────────────────────────────────────────────────────────────────────────────────────
        {"SELECT * FROM app_users ORDER BY 3", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 2", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash, count(*) FROM app_users GROUP BY 1", SqlSensitiveGuard.R_ORDINAL},
        // ── her zaman ───────────────────────────────────────────────────────────────────────────────────────────
        {"SELECT histogram_bounds FROM pg_stats WHERE tablename = 'app_users'", SqlSensitiveGuard.R_STATS},
        {"SELECT * FROM pg_catalog.pg_statistic", SqlSensitiveGuard.R_STATS},
        // PostgreSQL rol parola özeti: tek başına seçilebilir (köken maskesi), ifadede reddedilir
        {"SELECT rolname, rolpassword FROM pg_catalog.pg_authid", "ok"},
        {"SELECT md5(rolpassword) FROM pg_authid", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT usename FROM pg_shadow WHERE passwd LIKE 'SCRAM%'", SqlSensitiveGuard.R_EXPRESSION},
        {"SELECT query_to_xml('select password_hash from app_users', true, true, '')", SqlSensitiveGuard.R_DYNAMIC},
        {"SELECT * FROM ts_stat('SELECT to_tsvector(password_hash) FROM app_users')", SqlSensitiveGuard.R_DYNAMIC},
        {"SELECT table_to_xml('app_users', true, true, '')", SqlSensitiveGuard.R_DYNAMIC},
        {"SELECT U&\"pass\\0077ord_hash\" FROM app_users", SqlSensitiveGuard.R_UNICODE},
    };

    @Test
    @DisplayName("Güvenlik tablosu: her hassas kullanım ya maskelenir (ok) ya reddedilir")
    void securityTable() {
        List<String> failures = new ArrayList<>();
        for (String[] c : CASES) {
            SqlSensitiveGuard.Verdict v = SqlSensitiveGuard.check(c[0], SCHEMA);
            String got = v.ok() ? "ok" : v.reason();
            if (!got.equals(c[1])) failures.add(c[0] + " → beklenen " + c[1] + ", bulunan " + got + " (" + v.column() + ")");
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("Hassas tablo yoksa denetim hiçbir şeyi reddetmez ve mentionsSensitive=false")
    void noSensitiveTable() {
        SqlSensitiveGuard.Verdict v = SqlSensitiveGuard.check(
                "SELECT to_jsonb(t), t::text FROM teams t UNION SELECT 1, 'x' ORDER BY 1", SCHEMA);
        assertThat(v.ok()).isTrue();
        assertThat(v.mentionsSensitive()).isFalse();
        assertThat(SqlSensitiveGuard.check("SELECT username FROM app_users", SCHEMA).mentionsSensitive()).isTrue();
    }

    @Test
    @DisplayName("Ret, kolonun / adın kendisini bildirir")
    void rejectNamesTheColumn() {
        SqlSensitiveGuard.Verdict v = SqlSensitiveGuard.check("SELECT username FROM app_users WHERE password_hash = 'x'", SCHEMA);
        assertThat(v.column()).isEqualTo("password_hash");
        assertThat(SensitiveColumnException.message(v)).contains("password_hash");
    }

    @Test
    @DisplayName("Desene uyan canlı kolon (katalogda yok) da korunur; muaf kolon korunmaz")
    void liveSchemaPattern() {
        SqlSensitiveColumns.Schema s = SqlSensitiveColumns.schemaOf(Map.of(
                "new_table", List.of("id", "client_secret", "api_key_hint"),
                "monitor_check_jobs", List.of("id", "claim_token")));
        assertThat(SqlSensitiveGuard.check("SELECT id FROM new_table WHERE client_secret = 'x'", s).ok()).isFalse();
        assertThat(SqlSensitiveGuard.check("SELECT id FROM monitor_check_jobs WHERE claim_token = 'x'", s).ok()).isTrue();
    }

    @Test
    @DisplayName("Doğrusal: çok büyük sorgu sınırlı sürede biter")
    void linear() {
        StringBuilder sb = new StringBuilder("SELECT username FROM app_users u WHERE ");
        for (int i = 0; i < 20_000; i++) sb.append("u.id = ").append(i).append(" OR ");
        sb.append("u.id = -1 ORDER BY u.username");
        String big = sb.toString();
        StringBuilder nest = new StringBuilder("SELECT username FROM app_users WHERE id IN ");
        for (int i = 0; i < 5_000; i++) nest.append("(SELECT id FROM app_users WHERE id IN ");
        nest.append("(1)");
        for (int i = 0; i < 5_000; i++) nest.append(')');
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThat(SqlSensitiveGuard.check(big, SCHEMA).ok()).isTrue();
            SqlSensitiveGuard.check(nest.toString(), SCHEMA);
        });
    }
}
