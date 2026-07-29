-- cert-monitor — büyüme/performans index'leri (PostgreSQL)
--
-- NE İŞE YARAR: Büyüyen tablolarda (audit_log, alert_events, *_checks …) sık kullanılan
-- WHERE/ORDER BY kolonlarına btree index'ler. Milyonlarca satırda seq-scan yerine index-scan.
--
-- NASIL ÇALIŞTIRILIR (BÜYÜK/PROD DB):
--   Uygulamanın startup'ında applySchemaPatches bu index'leri "CREATE INDEX IF NOT EXISTS" ile
--   oluşturur — AMA bu, büyük bir tabloda tabloyu KİLİTLER (yazma durur, startup uzar). Bu yüzden
--   PROD'da DEPLOY'DAN ÖNCE bu script'i CONCURRENTLY ile elle çalıştırın; index'ler önceden var
--   olduğundan startup patch'i no-op olur ve kilit olmaz. (Taze/dev DB'de patch anında oluşturur;
--   küçük tabloda kilit önemsiz — script'i çalıştırmaya gerek yok.)
--
--   CONCURRENTLY tek başına (transaction bloğu içinde DEĞİL) çalışmalı; psql'de \set ON_ERROR_STOP:
--     psql -h <host> -U certmonitor -d certmonitor -v ON_ERROR_STOP=1 -f scripts/perf-indexes.sql
--   Bir index oluşturma yarıda kesilirse INVALID kalabilir; kontrol:
--     SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
--   INVALID olanı DROP INDEX CONCURRENTLY ile atıp yeniden çalıştırın.

-- Denetim konsolu: event_type filtresi + event_time range/sıralama (findAdvanced).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_type_time
    ON audit_log (event_type, event_time);

-- Haftalık KPI + incident kapanış aralığı (countByLevelResolvedBetween).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ae_resolved_at
    ON alert_events (resolved_at);

-- NOT: Aşağıdaki bileşik index'ler applySchemaPatches ile ZATEN oluşturuluyor (mevcut şemada varlar);
-- taze olmayan bir DB'de eksikse burada CONCURRENTLY ile eklenebilir:
--   idx_portc_monitor_checked  (port_checks: monitor_id, checked_at)
--   idx_pingc_monitor_checked  (ping_checks)
--   idx_hc_monitor_checked     (http_checks)
--   idx_kwr_monitor_checked    (keyword_results)
--   idx_dnsr_monitor_checked   (dns_records)
--   idx_dc_monitor_checked     (domain_checks)
--   idx_uc_domain_port_checked (uptime_checks: domain, port, checked_at)
--   idx_cc_domain_ts           (certificate_checks: domain, checked_at)
--   idx_nl_alert_event_id / idx_nl_sent_at (notification_logs)
--   idx_ae_storm_scan          (alert_events: resolved, alert_type, created_at)
