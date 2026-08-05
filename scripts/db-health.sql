-- site-monitor — PostgreSQL teşhis script'i (işletme/ops)
--
-- NE İŞE YARAR: DB büyümesini ve yavaşlığı hızlı teşhis — en büyük tablolar, satır sayıları, ölü-tuple
-- (bloat), kullanılmayan index'ler, en yavaş sorgular (pg_stat_statements) ve bağlantı doygunluğu.
--
-- NASIL ÇALIŞTIRILIR:
--   psql -h <host> -U certmonitor -d certmonitor -f scripts/db-health.sql
-- Tek bir bölümü çalıştırmak için ilgili SELECT'i kopyalayın.
-- pg_stat_statements bölümü yalnız 'shared_preload_libraries = pg_stat_statements' ise çalışır
-- (uygulama başlangıçta CREATE EXTENSION dener; k8s/postgres.yaml bunu preload eder).

\echo '========== 1) EN BÜYÜK TABLOLAR (toplam boyut = tablo + index + toast) =========='
SELECT
    c.relname                                   AS table,
    to_char(s.n_live_tup, 'FM999,999,999')      AS est_rows,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
    pg_size_pretty(pg_relation_size(c.oid))       AS table_size,
    pg_size_pretty(pg_total_relation_size(c.oid) - pg_relation_size(c.oid)) AS index_toast_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
WHERE c.relkind = 'r' AND n.nspname = 'public'
ORDER BY pg_total_relation_size(c.oid) DESC
LIMIT 25;

\echo '========== 2) ÖLÜ-TUPLE / BLOAT + son (auto)vacuum =========='
-- dead_ratio yüksek + eski last_autovacuum → autovacuum yetişemiyor; per-table scale_factor'ı düşürün.
SELECT
    relname                                              AS table,
    n_live_tup                                           AS live,
    n_dead_tup                                           AS dead,
    CASE WHEN n_live_tup > 0
         THEN round(100.0 * n_dead_tup / n_live_tup, 1) ELSE 0 END AS dead_pct,
    last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC
LIMIT 25;

\echo '========== 3) KULLANILMAYAN / az kullanılan INDEX''ler (yazma maliyeti > okuma faydası) =========='
-- idx_scan=0 → hiç kullanılmamış (silmeyi değerlendirin; sürekli yazan tabloda her index INSERT''i yavaşlatır).
SELECT
    s.relname AS table, s.indexrelname AS index,
    s.idx_scan AS scans,
    pg_size_pretty(pg_relation_size(s.indexrelid)) AS index_size
FROM pg_stat_user_indexes s
JOIN pg_index i ON i.indexrelid = s.indexrelid
WHERE NOT i.indisprimary AND NOT i.indisunique
ORDER BY s.idx_scan ASC, pg_relation_size(s.indexrelid) DESC
LIMIT 25;

\echo '========== 4) EN YAVAŞ SORGULAR (pg_stat_statements — preload gerektirir) =========='
SELECT
    round(total_exec_time)::bigint      AS total_ms,
    calls,
    round(mean_exec_time, 2)            AS mean_ms,
    round(100 * total_exec_time / NULLIF(sum(total_exec_time) OVER (), 0), 1) AS pct,
    left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;

\echo '========== 5) BAĞLANTI DOYGUNLUĞU (pool × replica ≤ max_connections olmalı) =========='
SELECT
    (SELECT setting::int FROM pg_settings WHERE name = 'max_connections')      AS max_connections,
    count(*)                                                                    AS total,
    count(*) FILTER (WHERE state = 'active')                                    AS active,
    count(*) FILTER (WHERE state = 'idle')                                      AS idle,
    count(*) FILTER (WHERE state = 'idle in transaction')                       AS idle_in_txn
FROM pg_stat_activity;

\echo '========== 6) TEMEL SUNUCU AYARLARI (dış prod DB''de bunları ops uygular) =========='
SELECT name, setting, unit FROM pg_settings
WHERE name IN ('shared_buffers','work_mem','effective_cache_size','maintenance_work_mem',
               'max_connections','autovacuum','autovacuum_max_workers')
ORDER BY name;
