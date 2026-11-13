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
            // gerçek kolonu satır alan bir fonksiyonla aynı adlı taban tablo (u.mode → kolon, fonksiyon değil)
            "jobs", List.of("id", "mode", "team_id"),
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
        // ── satır karşılaştırma kâhini (inceleme 2026-11-13, YÜKSEK 1): ifade konumunda (SELECT * …) / (TABLE …) ───────
        {"SELECT 1 WHERE ROW(1, 'admin', '$2a$10$a', 's', 1, 'e', true) < (SELECT * FROM app_users WHERE id = 1)",
                SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 FROM teams WHERE ROW(1, 'x') < ANY (SELECT * FROM app_users)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 FROM teams WHERE ROW(1, 'x') <= ALL (SELECT * FROM app_users)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 FROM teams WHERE ('a', 'b') IN (SELECT * FROM spring_session)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 WHERE ROW('a', 'b') < (TABLE spring_session)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 WHERE ROW('a', 'b') < ANY (TABLE spring_session)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT ARRAY(SELECT * FROM app_users)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT GREATEST(ROW(1, 'a'), (SELECT * FROM app_users LIMIT 1))", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT (SELECT DISTINCT * FROM app_users LIMIT 1) > ROW(1)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 FROM teams t WHERE ROW(1) < (SELECT ALL * FROM app_users)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 WHERE ROW(1) < (SELECT id, * FROM app_users LIMIT 1)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"WITH c AS (SELECT * FROM app_users) SELECT 1 WHERE ROW(1) < (SELECT * FROM c)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"WITH c AS (SELECT * FROM app_users) SELECT 1 WHERE ROW(1) < (TABLE c)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT 1 WHERE ROW(1) < (SELECT * FROM (SELECT * FROM app_users) x)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT username FROM app_users WHERE id IN (SELECT * FROM teams)", SqlSensitiveGuard.R_WHOLE_ROW},
        // çıktısı kökenle izlenen yerde * / TABLE serbest; EXISTS yalnız var / yok döner; count(*) seçim öğesi değil
        {"SELECT * FROM (SELECT * FROM app_users) x", "ok"},
        {"SELECT * FROM app_users JOIN LATERAL (SELECT * FROM teams t WHERE t.id = app_users.team_id) z ON true", "ok"},
        {"WITH c AS (SELECT * FROM app_users) SELECT * FROM c", "ok"},
        {"SELECT * FROM (TABLE app_users) x", "ok"},
        {"WITH c AS (TABLE app_users) SELECT * FROM c", "ok"},
        {"SELECT count(*) FROM app_users", "ok"},
        {"SELECT 1 WHERE EXISTS (SELECT * FROM app_users)", "ok"},
        {"SELECT username FROM app_users u WHERE NOT EXISTS (SELECT * FROM teams t WHERE t.id = u.team_id)", "ok"},
        {"SELECT 1 FROM teams WHERE id IN (SELECT id * 2 FROM app_users)", "ok"},
        {"SELECT (SELECT count(*) FROM app_users) AS n", "ok"},
        // alt sorguda / DISTINCT ON'da konum numarası her zaman (sıralama kâhini)
        {"SELECT * FROM (SELECT * FROM app_users ORDER BY 3) x LIMIT 1", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT x.username FROM (SELECT username, id FROM app_users GROUP BY 1, 2) x", SqlSensitiveGuard.R_ORDINAL},
        {"WITH c AS (SELECT * FROM app_users ORDER BY 3 LIMIT 1) SELECT * FROM c", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT DISTINCT ON (2) username, password_hash FROM app_users", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, row_number() OVER (ORDER BY 1) FROM app_users", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username FROM app_users ORDER BY 1", "ok"},
        {"SELECT DISTINCT ON (username) username FROM app_users ORDER BY username", "ok"},
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

        // ── 3. tur (2026-11-13) YÜKSEK 1: FROM öğesi takma adı — KAPALI BAŞARISIZLIK ────────────────────────────
        // miras yıldızı / ONLY / TABLESAMPLE / ROWS FROM / WITH ORDINALITY → takma adı belirsizleşir: hep ret
        {"SELECT length(to_jsonb(u)->>'password_hash'), left(to_jsonb(u)->>'password_hash', 4) FROM app_users * u",
                SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT to_jsonb(u) FROM app_users * AS u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT count(*) FROM teams t JOIN app_users * u ON u.team_id = t.id WHERE length(to_jsonb(u)->>'password_hash') = 60",
                SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT length(row_to_json(u.*)::text) FROM app_users * u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT c FROM app_users * u(a, b, c)", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT count(*), max(length(to_jsonb(r)->>'token')) FROM remember_me_tokens * r", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT to_jsonb(u) FROM public.app_users * u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT username FROM app_users *", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT to_jsonb(u) FROM ONLY app_users u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT to_jsonb(u) FROM ONLY (app_users) u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT u.username FROM teams t, ONLY app_users u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT to_jsonb(u) FROM app_users u TABLESAMPLE BERNOULLI (100)", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT username FROM app_users TABLESAMPLE SYSTEM (50) REPEATABLE (1)", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT * FROM ROWS FROM (generate_series(1, 2)) x, app_users u", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT u.username FROM app_users u, generate_series(1, 2) WITH ORDINALITY g", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT 1 FROM app_users u u2", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT 1 FROM app_users u 'x'", SqlSensitiveGuard.R_TABLE_REF},
        {"SELECT 1 FROM app_users AS (a int)", SqlSensitiveGuard.R_TABLE_REF},
        // ayrılmamış sözcük / tırnaklı takma ad da takma addır → satır değer olarak kullanılırsa ret
        {"SELECT length(to_jsonb(rows)->>'password_hash') FROM app_users rows", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(\"ROWS\") FROM app_users \"ROWS\"", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(\"u\") FROM app_users \"u\"", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(exists) ->> 'username' FROM app_users exists", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(by) FROM app_users by", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(ordinality) FROM app_users ordinality", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(minus) FROM app_users minus", SqlSensitiveGuard.R_SET_OPERATION},
        {"SELECT to_jsonb(j) FROM app_users JOIN app_users a2 USING (id) AS j", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(x) FROM app_users u CROSS JOIN LATERAL (SELECT u.*) x", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(unnamed_subquery) FROM (SELECT * FROM app_users)", SqlSensitiveGuard.R_WHOLE_ROW},
        {"SELECT to_jsonb(u) FROM app_users AS u(a, b)", SqlSensitiveGuard.R_ALIAS_LIST},
        // doğru yazımlar çalışmaya devam eder
        {"SELECT u.username FROM app_users AS \"u\"", "ok"},
        {"SELECT r.username FROM app_users rows, app_users r WHERE r.id = rows.id", "ok"},
        {"SELECT u.username FROM app_users u INNER JOIN teams t ON left(t.name, 1) = left(u.username, 1)", "ok"},
        {"SELECT u.username FROM app_users u LEFT OUTER JOIN teams t ON t.id = u.team_id, teams t2 WHERE t2.id = 1", "ok"},
        {"SELECT u.username FROM app_users u FULL JOIN teams t USING (id) CROSS JOIN teams t3", "ok"},
        {"SELECT u.username FROM app_users u;", "ok"},
        {"SELECT n FROM app_users u, generate_series(1, 2) AS g(n)", "ok"},
        {"SELECT u.username FROM app_users u JOIN teams t ON t.id = u.team_id AND ARRAY[t.name, 'x'] <> ARRAY['a', 'b']", "ok"},
        {"SELECT 1 FROM app_users u JOIN teams t ON ARRAY[1, u.id] IS NOT NULL, app_users * v", SqlSensitiveGuard.R_TABLE_REF},

        // ── 3. tur: alan biçiminde fonksiyon çağrısı u.f ≡ f(u) (PostgreSQL 16'da doğrulandı) ──────────────────
        {"SELECT u.to_jsonb FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT u.row_to_json FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT u.concat FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT u.json_agg FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT u.quote_literal FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT u.record_send FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT app_users.json_build_array FROM app_users", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT public.app_users.to_jsonb FROM public.app_users", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT x.to_jsonb FROM (SELECT * FROM app_users) x", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"WITH c AS (SELECT * FROM app_users) SELECT c.record_out FROM c", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT \"u\".\"to_jsonb\" FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT u . to_jsonb FROM app_users u", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT username FROM app_users u WHERE u.to_jsonb::text LIKE '%$2a%'", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT t.name FROM teams t JOIN app_users u ON u.team_id = t.id ORDER BY u.concat", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT j.mode FROM jobs j JOIN app_users u ON u.id = j.id WHERE u.to_jsonb IS NOT NULL", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"WITH app_users AS (SELECT 1 AS mode) SELECT app_users.mode FROM app_users", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT (u).to_jsonb FROM app_users u", SqlSensitiveGuard.R_WHOLE_ROW},
        // bilinçli temkinli ret: türetilmiş tablonun gerçek "count" kolonu da niteleyiciyle reddedilir (niteleyicisiz serbest)
        {"SELECT c.count FROM (SELECT count(*) FROM app_users) c", SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION},
        {"SELECT count FROM (SELECT count(*) FROM app_users) c", "ok"},
        // taban tablonun GERÇEK kolonu → serbest; şema nitelikli fonksiyon çağrısı → argümanlar denetlenir
        {"SELECT j.mode, u.username FROM jobs j JOIN app_users u ON u.id = j.id", "ok"},
        {"SELECT public.jobs.mode FROM public.jobs, app_users", "ok"},
        {"SELECT pg_catalog.to_jsonb(1), username FROM app_users", "ok"},

        // ── 3. tur ORTA 2: konum numarası parantez / PG16 tamsayı biçimleriyle ──────────────────────────────────
        {"SELECT username FROM (SELECT * FROM app_users ORDER BY (6) LIMIT 100) d", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username FROM (SELECT * FROM app_users ORDER BY (31) DESC LIMIT 100) d", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username FROM (SELECT * FROM app_users ORDER BY 0x6 LIMIT 1) d", SqlSensitiveGuard.R_ORDINAL},
        {"WITH c AS (SELECT * FROM app_users ORDER BY (31) DESC LIMIT 1) SELECT username FROM c", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT count(*) AS c FROM (SELECT DISTINCT ON ((6)) * FROM app_users) d", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT count(*) AS c FROM (SELECT * FROM app_users GROUP BY id, ROLLUP(6)) d", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash, username FROM app_users ORDER BY 0x1", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash, username FROM app_users ORDER BY 0x1 DESC", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 0X2", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 0_2", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 0b10", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 0o2", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 0x_2", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY (2)", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY ((2)) DESC", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY - -2", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY -(-2)", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 1, (2) NULLS LAST", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 0x", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users ORDER BY 2x", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash FROM app_users GROUP BY (1)", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash FROM app_users GROUP BY DISTINCT 1", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash FROM app_users GROUP BY ALL (1)", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash, count(*) FROM app_users GROUP BY ROLLUP (1)", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash, count(*) FROM app_users GROUP BY CUBE ((1))", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT password_hash, count(*) FROM app_users GROUP BY GROUPING SETS ((1), ())", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT username, password_hash FROM app_users GROUP BY (username, 2)", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT DISTINCT ON ((1)) username, email FROM app_users", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT DISTINCT ON (- -0x1) username FROM app_users", SqlSensitiveGuard.R_ORDINAL},
        {"SELECT * FROM app_users ORDER BY (6)", SqlSensitiveGuard.R_ORDINAL},
        // konum hassas olmayan öğeyse ya da sayı ondalıksa / int4 dışıysa (PostgreSQL'de sabit) dışta serbest
        {"SELECT username, password_hash FROM app_users ORDER BY 0x1", "ok"},
        {"SELECT username, password_hash FROM app_users ORDER BY (1) DESC", "ok"},
        {"SELECT username, password_hash FROM app_users ORDER BY 2.0", "ok"},
        {"SELECT username, password_hash FROM app_users ORDER BY 2e0", "ok"},
        {"SELECT username, password_hash FROM app_users ORDER BY 4294967298", "ok"},
        {"SELECT username, password_hash FROM app_users ORDER BY coalesce(username, '2')", "ok"},
    };

    @Test
    @DisplayName("PostgreSQL 16 tamsayı sabiti çözümleyicisi: 0x / 0o / 0b / '_' konum; ondalık / int4 dışı konum değil")
    void pgIntegerLiteral() {
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("2")).isEqualTo(2);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0x1F")).isEqualTo(31);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0X_1f")).isEqualTo(31);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0o17")).isEqualTo(15);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0b101")).isEqualTo(5);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("1_000")).isEqualTo(1000);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0_2")).isEqualTo(2);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("2147483647")).isEqualTo(Integer.MAX_VALUE);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("2147483648")).isEqualTo(SqlSensitiveGuard.NOT_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("2.0")).isEqualTo(SqlSensitiveGuard.NOT_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("1e3")).isEqualTo(SqlSensitiveGuard.NOT_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0x")).isEqualTo(SqlSensitiveGuard.UNKNOWN_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("0b12")).isEqualTo(SqlSensitiveGuard.UNKNOWN_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("1__0")).isEqualTo(SqlSensitiveGuard.UNKNOWN_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("1_")).isEqualTo(SqlSensitiveGuard.UNKNOWN_ORDINAL);
        assertThat(SqlSensitiveGuard.pgIntegerLiteral("2x")).isEqualTo(SqlSensitiveGuard.UNKNOWN_ORDINAL);
    }

    @Test
    @DisplayName("Canlı pg_proc listesi tabana EKLENİR (asla daralmaz): yeni bir satır fonksiyonu da reddedilir")
    void liveRowFunctionsExtendTheFallback() {
        SqlSensitiveColumns.Schema s = SqlSensitiveColumns.schemaOf(Map.of("app_users", List.of("id", "password_hash")),
                List.of("my_row_dump"));
        assertThat(s.isRowFunction("to_jsonb")).isTrue();
        assertThat(s.isRowFunction("MY_ROW_DUMP")).isTrue();
        assertThat(SqlSensitiveGuard.check("SELECT u.my_row_dump FROM app_users u", s).reason())
                .isEqualTo(SqlSensitiveGuard.R_ATTRIBUTE_FUNCTION);
        assertThat(SqlSensitiveGuard.check("SELECT u.my_row_dump FROM app_users u", SCHEMA).ok()).isTrue();
    }

    @Test
    @DisplayName("Yeni ret nedenlerinin iletisi nedeni söyler (TR)")
    void newReasonMessages() {
        SqlSensitiveGuard.Verdict star = SqlSensitiveGuard.check("SELECT to_jsonb(u) FROM app_users * u", SCHEMA);
        assertThat(SensitiveColumnException.message(star)).contains("FROM").contains("takma ad");
        SqlSensitiveGuard.Verdict attr = SqlSensitiveGuard.check("SELECT u.to_jsonb FROM app_users u", SCHEMA);
        assertThat(attr.column()).isEqualTo("u.to_jsonb");
        assertThat(SensitiveColumnException.message(attr)).contains("u.to_jsonb").contains("to_jsonb(u)");
    }

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
        // İç içe ORDER BY / seçim listesindeki alt sorgular: her tarama yalnız kendi derinliğini ziyaret eder
        StringBuilder ord = new StringBuilder("SELECT username FROM app_users ORDER BY ");
        for (int i = 0; i < 5_000; i++) ord.append("(SELECT 1 ORDER BY ");
        ord.append("username");
        for (int i = 0; i < 5_000; i++) ord.append(')');
        StringBuilder sel = new StringBuilder("SELECT ");
        for (int i = 0; i < 5_000; i++) sel.append("(SELECT ");
        sel.append("1");
        for (int i = 0; i < 5_000; i++) sel.append(" FROM app_users LIMIT 1)");
        sel.append(" FROM app_users");
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThat(SqlSensitiveGuard.check(big, SCHEMA).ok()).isTrue();
            SqlSensitiveGuard.check(nest.toString(), SCHEMA);
            SqlSensitiveGuard.check(ord.toString(), SCHEMA);
            SqlSensitiveGuard.check(sel.toString(), SCHEMA);
        });
    }
}
