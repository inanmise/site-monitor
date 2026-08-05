-- site-monitor — sentetik performans verisi üretici (PostgreSQL)
--
-- NE İŞE YARAR: Büyüyen bir kontrol tablosunu (port_checks) hızlıca milyonlarca satıra doldurur ki
-- optimizasyonların (LATERAL en-güncel, index'ler, batch purge) MİLYONLARCA satırda da hızlı kaldığı
-- EXPLAIN ANALYZE ile kanıtlanabilsin.
--
-- KULLANIM (istediğin satır sayısıyla):
--   psql -h localhost -U certmonitor -d certmonitor -v rows=1500000 -f perf/seed-perf-data.sql
-- Varsayılan 1.5M. Satırlar 2020-2021 tarihli (açıkça sentetik) → app'in güncel-veri sorgularını
-- ETKİLEMEZ ve temizlemesi kolaydır (aşağıdaki CLEANUP).
--
-- TEMİZLEME (dev DB'yi eski haline döndür):
--   DELETE FROM port_checks WHERE error = '__PERFTEST__';

\set rows :rows
\if :{?rows}
\else
  \set rows 1500000
\endif

\echo 'Sentetik port_checks satırı ekleniyor:' :rows
INSERT INTO port_checks (monitor_id, open, response_ms, checked_at, error)
SELECT
    (g % 15) + 1,                                                     -- 15 monitör
    (random() < 0.9),                                                 -- ~%90 up
    CASE WHEN random() < 0.9 THEN (random() * 500)::int ELSE NULL END,
    to_char(timestamp '2020-01-01 00:00:00' + (g * interval '20 seconds'),
            'YYYY-MM-DD"T"HH24:MI:SS'),                               -- eski, sözlüksel-sıralı ISO
    '__PERFTEST__'                                                    -- temizlik işareti
FROM generate_series(1, :rows) g;

ANALYZE port_checks;
\echo 'Bitti. Toplam port_checks:'
SELECT count(*) FROM port_checks;
