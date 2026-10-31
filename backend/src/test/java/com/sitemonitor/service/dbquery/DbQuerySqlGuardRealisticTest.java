package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yanlış pozitif kapısı: izleme ekiplerinin gerçekte yazdığı türden sorgular (sayım, süre farkı, pencere fonksiyonları,
 * lehçeye özgü tarih / metin işlevleri) KABUL edilmeli — katı denetim kullanılmaz hale gelmesin.
 */
class DbQuerySqlGuardRealisticTest {

    @BeforeEach
    void reset() {
        DbQuerySqlGuard.clearCache();
    }

    private static void ok(String sql, DbType type) {
        DbQuerySqlGuard.Result r = DbQuerySqlGuard.check(sql, type);
        assertThat(r.violations()).as(type + ": " + sql).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT count(*) FROM orders WHERE status = 'FAILED' AND created_at > now() - interval '15 minutes'",
            "SELECT date_trunc('hour', created_at) AS h, count(*) FILTER (WHERE ok = false) AS fails FROM checks GROUP BY 1 ORDER BY 1 DESC LIMIT 24",
            "SELECT o.id, o.total::numeric(12,2) FROM public.orders o JOIN customers c ON c.id = o.customer_id WHERE c.name ILIKE '%ltd%'",
            "SELECT payload->>'type' AS t, count(*) FROM events WHERE payload ? 'type' AND ts >= current_date GROUP BY 1",
            "WITH last AS (SELECT max(ts) AS m FROM heartbeats) SELECT extract(epoch FROM now() - m) AS lag_seconds FROM last",
            "SELECT id, row_number() OVER (PARTITION BY account ORDER BY ts DESC) AS rn FROM tx WHERE ts > now() - '1 day'::interval",
            "SELECT coalesce(sum(amount), 0) AS total FROM payments WHERE paid_at::date = current_date",
            "SELECT * FROM jobs WHERE state IN ('stuck', 'failed') AND updated_at < now() - interval '1 hour' ORDER BY updated_at",
            "SELECT count(*) AS n FROM pg_stat_activity WHERE state = 'idle in transaction' AND xact_start < now() - interval '5 min'",
            "SELECT relname, n_dead_tup FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 5",
            "SELECT a.id FROM a LEFT JOIN LATERAL (SELECT b.v FROM b WHERE b.a_id = a.id ORDER BY b.ts DESC LIMIT 1) last ON true",
            "SELECT CASE WHEN count(*) > 0 THEN 'HATA' ELSE 'OK' END AS durum FROM logs WHERE level = 'ERROR'",
            "SELECT 'a' || 'b', length('x'), upper(name), to_char(ts, 'YYYY-MM-DD HH24:MI') FROM t",
            "SELECT * FROM generate_series(now() - interval '1 day', now(), interval '1 hour') g",
            "select distinct on (customer_id) customer_id, created_at from orders order by customer_id, created_at desc",
    })
    void postgres(String sql) {
        ok(sql, DbType.POSTGRESQL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) AS n FROM dbo.Orders AS o WITH (NOLOCK) WHERE o.Status = 'FAILED' AND o.CreatedAt > DATEADD(MINUTE, -15, GETDATE())",
            "SELECT TOP (10) o.Id, DATEDIFF(SECOND, o.CreatedAt, GETUTCDATE()) AS age FROM [dbo].[Orders] o WITH (NOLOCK) ORDER BY o.CreatedAt",
            "SELECT ISNULL(SUM(p.Amount), 0) AS total FROM dbo.Payments p WITH (NOLOCK) WHERE CAST(p.PaidAt AS date) = CAST(GETDATE() AS date)",
            "SELECT c.Name, COUNT(*) AS cnt FROM dbo.Customers c WITH (NOLOCK) INNER JOIN dbo.Orders o WITH (NOLOCK) ON o.CustomerId = c.Id GROUP BY c.Name HAVING COUNT(*) > 5",
            "WITH last AS (SELECT MAX(ts) AS m FROM dbo.Heartbeats WITH (NOLOCK)) SELECT DATEDIFF(SECOND, m, GETDATE()) AS lag FROM last",
            "SELECT id, ROW_NUMBER() OVER (PARTITION BY account ORDER BY ts DESC) AS rn FROM dbo.Tx WITH (NOLOCK)",
            "SELECT IIF(COUNT(*) > 0, 'HATA', 'OK') AS durum FROM dbo.Logs WITH (NOLOCK) WHERE Level = N'ERROR'",
            "SELECT CONVERT(VARCHAR(19), o.CreatedAt, 120) AS ts FROM dbo.Orders o WITH (NOLOCK)",
            "SELECT * FROM dbo.Orders o WITH (NOLOCK) CROSS APPLY (SELECT TOP 1 i.Sku FROM dbo.Items i WITH (NOLOCK) WHERE i.OrderId = o.Id) x",
            "SELECT COUNT(*) FROM sys.dm_exec_requests WITH (NOLOCK) WHERE blocking_session_id <> 0",
            "SELECT a FROM t1 WITH (NOLOCK) WHERE NOT EXISTS (SELECT 1 FROM t2 WITH (NOLOCK) WHERE t2.a = t1.a)",
            "SELECT STRING_AGG(Name, ', ') AS names FROM dbo.Users WITH (NOLOCK) WHERE Active = 1",
            "SELECT COUNT(*) FROM dbo.Orders WITH(NOLOCK) WHERE Status IN (1, 2, 3)",
    })
    void mssql(String sql) {
        ok(sql, DbType.MSSQL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) AS n FROM orders WHERE status = 'FAILED' AND created_at > SYSDATE - INTERVAL '15' MINUTE",
            "SELECT TRUNC(created_at, 'HH24') AS h, COUNT(*) FROM orders GROUP BY TRUNC(created_at, 'HH24') ORDER BY 1",
            "SELECT NVL(SUM(amount), 0) AS total FROM payments WHERE TRUNC(paid_at) = TRUNC(SYSDATE)",
            "SELECT * FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY ts DESC) rn FROM tx) WHERE rn <= 10",
            "SELECT id FROM orders WHERE ROWNUM <= 10",
            "SELECT id FROM orders ORDER BY created_at DESC FETCH FIRST 10 ROWS ONLY",
            "SELECT DECODE(status, 'A', 'Aktif', 'Pasif') AS s FROM accounts",
            "SELECT CASE WHEN COUNT(*) > 0 THEN 'HATA' ELSE 'OK' END AS durum FROM app_log WHERE log_level = 'ERROR'",
            "SELECT owner, COUNT(*) FROM all_objects WHERE status = 'INVALID' GROUP BY owner",
            "WITH last AS (SELECT MAX(ts) m FROM heartbeats) SELECT (SYSDATE - CAST(m AS DATE)) * 86400 AS lag FROM last",
            "SELECT TO_CHAR(SYSDATE, 'YYYY-MM-DD') FROM dual",
            "SELECT e.id, d.name FROM hr.employees e, hr.departments d WHERE e.dept_id = d.id(+)",
            "SELECT a FROM t MINUS SELECT a FROM u",
    })
    void oracle(String sql) {
        ok(sql, DbType.ORACLE);
    }
}
