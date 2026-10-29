-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Çok pod çift-iş dedektörü (2026-10-21, çok pod sürüm 1 — PLAN §2.6 / §2.7)
--
-- Ne yapar: iki (ya da daha çok) pod aynı işi iki kez yaptıysa geride kalan izi arar — aynı izlemenin
-- aynı anda iki kontrolü, aynı alarm bildiriminin aynı alıcıya iki kez gitmesi, aynı push'un iki kez
-- gönderilmesi, birden çok "sürüyor" (ONGOING) ağ kesintisi satırı, aynı hedef + tür için birden çok
-- açık alarm, aynı kapsamda birden çok açık fırtına. Ayrıca kaybolan bildirim izi: 25 dk'dan eski
-- QUEUED_RETRY satırları (gönderen pod kapandı, sonuç hiç yazılmadı).
--
-- YALNIZ OKUR (oturum default_transaction_read_only = on). Prod'da güvenle koşulabilir; büyük tablolarda
-- yalnız son :hours saati tarar (checked_at / sent_at / created_at indeksleri, ISO "yyyy-MM-ddTHH:mm:ss"
-- UTC metin sütunları — sözlüksel aralık karşılaştırması geçerlidir).
--
-- Kullanım:
--   psql -h <host> -U <kullanıcı> -d <db> -X -f scripts/multipod-dup-check.sql
--   psql … -v hours=48 -v dup_gap_s=5 -v notify_gap_s=600 -f scripts/multipod-dup-check.sql
--
-- Son satırlar makinece okunur (scripts/multipod-smoke.sh bunları ayrıştırır):
--   DUP_TOTAL=<n>     çift iş adayı sayısı (0 beklenir)
--   STUCK_TOTAL=<n>   kaybolmuş bildirim adayı (0 beklenir)
-- Bir sorgu hata verirse (eski şema, eksik tablo) psql ERROR yazar ve o denetim 0 sayılır — çıktıda ERROR
-- satırı varsa sonuç eksiktir.
--
-- Yanlış pozitifler (her satırı TEK TEK inceleyin, otomatik "hata" saymayın):
--   * Kontrol çiftleri: bir hatadan 30 sn sonraki doğrulama (ya da kurtarma) yeniden kontrolü zamanlanmış kontrolle
--     aynı saniyeye düşebilir; "Şimdi kontrol et" de öyle (manuel sütunu olan scripted_checks / dns_records /
--     domain_checks'te manuel satırlar dışlanır, diğerlerinde dışlanamaz). Tek pod'da da birkaç tane görülür. İki
--     lider HER izlemenin HER kontrolünü ikiler: ayrıntıdaki cift / toplam_satir oranı yüksekse sorun, tek tük çift değil.
--   * Bildirim çiftleri: MANUAL yeniden gönderim ve *_TEST tetikleri dışlanır; aynı alarmın 10 dk içinde iki
--     seviye yükselmesi (iki ESCALATION) meşrudur.
--   * Açık alarm çiftleri: aynı (hedef, alarm türü) anahtarı tek alarm taşır; birden çok satır = yarış.
--   * Bölüm 9 (çok pod Sürüm 3-3, dağıtık kontroller — monitor_check_jobs tablosu yoksa atlanır): (b) dönem çiftleri
--     yalnız SAĞLIKLI izlemeleri sayar; "Şimdi kontrol et", YAVAŞ yanıt alarmı doğrulaması (HTTP_SLOW / KEYWORD_SLOW /
--     PING_SLOW) ve işçisi ölen işin kira dolunca (≤ 150 sn) yeniden koşması yanlış pozitif verebilir — ayrıntı bölümde.
--
-- Bölüm 9 değişkenleri: dist_minutes (60: dönem çiftleri penceresi, dk), period_frac (0.5: aralığın bu katından yakın iki
-- zamanlanmış satır = çift), done_stale_s (300: bu kadar eski DONE satırı, anahtar AÇIKKEN = STUCK), period_strict (auto:
-- dönem çiftleri yalnız anahtar açıkken DUP sayılır; 1: her zaman; 0: hiç — yalnız gösterilir).
-- ─────────────────────────────────────────────────────────────────────────────────────────────

\set QUIET on
\pset footer off
SET default_transaction_read_only = on;
SET statement_timeout = '120s';

\if :{?hours}
\else
  \set hours 24
\endif
\if :{?dup_gap_s}
\else
  \set dup_gap_s 5
\endif
\if :{?notify_gap_s}
\else
  \set notify_gap_s 600
\endif
\if :{?detail_limit}
\else
  \set detail_limit 20
\endif
\if :{?dist_minutes}
\else
  \set dist_minutes 60
\endif
\if :{?period_frac}
\else
  \set period_frac 0.5
\endif
\if :{?done_stale_s}
\else
  \set done_stale_s 300
\endif
\if :{?period_strict}
\else
  \set period_strict auto
\endif

SELECT to_char((now() AT TIME ZONE 'UTC') - make_interval(hours => :hours), 'YYYY-MM-DD"T"HH24:MI:SS') AS since,
       to_char((now() AT TIME ZONE 'UTC') - interval '25 minutes', 'YYYY-MM-DD"T"HH24:MI:SS') AS stuck_before,
       to_char((now() AT TIME ZONE 'UTC') - make_interval(mins => :dist_minutes), 'YYYY-MM-DD"T"HH24:MI:SS') AS dist_since
\gset

\echo '══ Çok pod çift-iş dedektörü — son' :hours 'saat (UTC >=' :since ')'

-- ── 0. Bilgi: tarama liderliği kirası ve tutulan iş kilitleri ────────────────────────────────
\echo
\echo '── 0. Tarama liderliği kirası (tek sahip beklenir) ve tutulan scheduler_lock satırları'
SELECT claimed_by AS lider,
       to_char(to_timestamp(next_due_at / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS') AS kira_bitisi_utc,
       CASE WHEN next_due_at < (EXTRACT(EPOCH FROM now()) * 1000) THEN 'SÜRESİ DOLMUŞ' ELSE 'geçerli' END AS durum
FROM monitor_check_schedule WHERE monitor_key = 'lease:sweep-leader';
SELECT name AS kilit, locked_by AS sahip, locked_until AS bitis_utc
FROM scheduler_lock ORDER BY name;

-- ── 0b. Bilgi (sayılmaz): küme turlarının son sahiplenen pod'u — çok pod Sürüm 3-1 yan işleri ───
-- Sürüm 3-1'den beri tarama lideri, başka canlı pod varken cron yan işlerini 20 sn erteler ve tur tabanlı ağır işleri
-- (cert-stale, tls-profile, executive-summary-catchup) bırakır: iki pod'da bu turların claimed_by'ı çoğunlukla TAKİPÇİ
-- olmalı (lider = section 0'daki kira sahibi). Liderin kendi tarama turları (uptime, page-crawl …) liderde kalır.
-- "TLS profili turu dönem başına bir kez" buradan SAYILAMAZ: monitor_check_schedule satırı yalnız SON sahiplenmeyi tutar
-- (geçmiş yok) ve tls_profiles yalnız her ucun son yoklamasını; teklik turun koşullu sahiplenmesi (CAS) + tls-profile
-- kilidiyle sağlanır, ikinci bir tık da vadesi gelmemiş ucu yeniden yoklamaz (vade kümesi probed_at'ten).
-- round:<tur>:done = turu son BAŞARIYLA bitiren pod ve an (Sürüm 3-1 FX-SIDE; cert-stale, tls-profile,
-- executive-summary-catchup): lider bir turu yalnız buna bakarak bırakır — yalnız sahiplenip hata veren takipçi kanıt
-- değildir. Bu satırlarda claimed_at = bitiş anı ve next_due_at aynı an (vade DEĞİL); "satir" sütunu ikisini ayırır.
\echo
\echo '── 0b. Küme turlarının son sahibi (bilgi, sayılmaz — Sürüm 3-1: yan iş turları çoğunlukla takipçide)'
SELECT r.monitor_key AS tur,
       CASE WHEN r.monitor_key LIKE 'round:%:done' THEN 'son TAMAMLAYAN (vade değil)' ELSE 'son sahiplenen' END AS satir,
       r.claimed_by AS son_sahiplenen,
       CASE WHEN l.claimed_by IS NULL OR r.claimed_by IS NULL THEN '—'
            WHEN r.claimed_by = l.claimed_by THEN 'lider' ELSE 'takipçi' END AS kim,
       to_char(to_timestamp(r.claimed_at / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS') AS sahiplenme_utc,
       to_char(to_timestamp(r.next_due_at / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS') AS sonraki_vade_utc
FROM monitor_check_schedule r
LEFT JOIN monitor_check_schedule l ON l.monitor_key = 'lease:sweep-leader'
WHERE r.monitor_key LIKE 'round:%'
ORDER BY r.monitor_key;

-- ── 0c. Bilgi (sayılmaz): kalıcı değerlendirme durumu — çok pod Sürüm 3-1 ─────────────────────
-- Kuşak satırını ('*') yazan = tarama lideri, nabız en çok ~60 sn eski beklenir (eski — 20.167.0 — bir lider yazmaz; o
-- sırada nabız eskir, hata değildir). Tablo yoksa (Sürüm 3-1 öncesi) bölüm atlanır.
SELECT to_regclass('monitor_eval_type_state') IS NOT NULL
   AND to_regclass('monitor_eval_state') IS NOT NULL
   AND to_regclass('monitor_last_status') IS NOT NULL
   AND to_regclass('monitor_confirmations') IS NOT NULL AS has_eval_state \gset
\if :has_eval_state
\echo
\echo '── 0c. Kalıcı değerlendirme durumu (bilgi, sayılmaz): kuşak satırını yazan = tarama lideri, nabız en çok ~60 sn'
SELECT t.gen AS kusak, t.writer AS yazan,
       CASE WHEN l.claimed_by IS NULL THEN 'kira yok' WHEN l.claimed_by = t.writer THEN 'lider' ELSE 'LİDER DEĞİL' END AS yazan_kim,
       ROUND(((EXTRACT(EPOCH FROM now()) * 1000) - t.updated_ms) / 1000.0) AS nabiz_yasi_sn
FROM monitor_eval_type_state t
LEFT JOIN monitor_check_schedule l ON l.monitor_key = 'lease:sweep-leader'
WHERE t.alert_type = '*';
WITH g AS (SELECT COALESCE(MAX(gen), 0) AS gen FROM monitor_eval_type_state WHERE alert_type = '*')
SELECT 'monitor_eval_state' AS tablo, COUNT(*) AS satir, COUNT(*) FILTER (WHERE s.gen < g.gen) AS onceki_kusak
  FROM monitor_eval_state s, g
UNION ALL
SELECT 'monitor_last_status', COUNT(*), COUNT(*) FILTER (WHERE s.gen < g.gen) FROM monitor_last_status s, g
UNION ALL
SELECT 'monitor_eval_type_state', COUNT(*), COUNT(*) FILTER (WHERE s.gen < g.gen)
  FROM monitor_eval_type_state s, g WHERE s.alert_type <> '*'
UNION ALL
SELECT 'monitor_confirmations (RUNNING/FINISHING)', COUNT(*), COUNT(*) FILTER (WHERE s.gen < g.gen)
  FROM monitor_confirmations s, g WHERE s.state <> 'ABANDONED';
\endif

-- ── 1. Aynı izlemenin aynı anda iki kontrolü (son :hours saat, ardışık satır aralığı < :dup_gap_s sn) ──
\echo
\echo '── 1. İzleme kontrol çiftleri (aynı izleme, ardışık iki satır <' :dup_gap_s 'sn arayla)'
\set c_checks 0
WITH rows_ AS (
  SELECT 'http_checks' AS tbl, monitor_id::text AS anahtar, checked_at FROM http_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'ping_checks', monitor_id::text, checked_at FROM ping_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'port_checks', monitor_id::text, checked_at FROM port_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'keyword_results', monitor_id::text, checked_at FROM keyword_results WHERE checked_at >= :'since'
  UNION ALL SELECT 'page_checks', monitor_id::text, checked_at FROM page_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'pagespeed_checks', monitor_id::text, checked_at FROM pagespeed_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'scripted_checks', monitor_id::text, checked_at FROM scripted_checks
            WHERE checked_at >= :'since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'dns_records', monitor_id::text || '/' || COALESCE(record_type, ''), checked_at FROM dns_records
            WHERE checked_at >= :'since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'domain_checks', monitor_id::text, checked_at FROM domain_checks
            WHERE checked_at >= :'since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'uptime_checks', domain || ':' || COALESCE(port::text, ''), checked_at FROM uptime_checks
            WHERE checked_at >= :'since'
), gaps AS (
  SELECT tbl, anahtar, checked_at,
         EXTRACT(EPOCH FROM (CAST(LEFT(checked_at, 19) AS timestamp)
              - LAG(CAST(LEFT(checked_at, 19) AS timestamp)) OVER (PARTITION BY tbl, anahtar ORDER BY checked_at))) AS ara_sn
  FROM rows_
)
SELECT COUNT(*) AS c_checks FROM gaps WHERE ara_sn IS NOT NULL AND ara_sn < :dup_gap_s
\gset
\echo '   aday çift:' :c_checks
SELECT (:c_checks > 0) AS show_checks \gset
\if :show_checks
WITH rows_ AS (
  SELECT 'http_checks' AS tbl, monitor_id::text AS anahtar, checked_at FROM http_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'ping_checks', monitor_id::text, checked_at FROM ping_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'port_checks', monitor_id::text, checked_at FROM port_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'keyword_results', monitor_id::text, checked_at FROM keyword_results WHERE checked_at >= :'since'
  UNION ALL SELECT 'page_checks', monitor_id::text, checked_at FROM page_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'pagespeed_checks', monitor_id::text, checked_at FROM pagespeed_checks WHERE checked_at >= :'since'
  UNION ALL SELECT 'scripted_checks', monitor_id::text, checked_at FROM scripted_checks
            WHERE checked_at >= :'since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'dns_records', monitor_id::text || '/' || COALESCE(record_type, ''), checked_at FROM dns_records
            WHERE checked_at >= :'since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'domain_checks', monitor_id::text, checked_at FROM domain_checks
            WHERE checked_at >= :'since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'uptime_checks', domain || ':' || COALESCE(port::text, ''), checked_at FROM uptime_checks
            WHERE checked_at >= :'since'
), gaps AS (
  SELECT tbl, anahtar, checked_at,
         EXTRACT(EPOCH FROM (CAST(LEFT(checked_at, 19) AS timestamp)
              - LAG(CAST(LEFT(checked_at, 19) AS timestamp)) OVER (PARTITION BY tbl, anahtar ORDER BY checked_at))) AS ara_sn
  FROM rows_
)
SELECT tbl AS tablo, anahtar AS izleme,
       COUNT(*) FILTER (WHERE ara_sn IS NOT NULL AND ara_sn < :dup_gap_s) AS cift,
       COUNT(*) AS toplam_satir,
       MAX(checked_at) FILTER (WHERE ara_sn IS NOT NULL AND ara_sn < :dup_gap_s) AS son_cift_utc
FROM gaps
GROUP BY tbl, anahtar
HAVING COUNT(*) FILTER (WHERE ara_sn IS NOT NULL AND ara_sn < :dup_gap_s) > 0
ORDER BY cift DESC, son_cift_utc DESC LIMIT :detail_limit;
\endif

-- ── 2. Sertifika: aynı alan adını kontrol eden iki taramanın ZAMANDA ÇAKIŞMASI ─────────────────
-- Her tarama bir run_id taşır (zamanlanmış / bayat / tam tarama 12 hex, yüklenen sertifika "upload-…"; tek alan adı
-- "manual" / "health-refresh" hariç). Bir pod'un ardışık taramaları (açılış + saatlik) çakışmaz; çakışan iki tarama
-- ortak alan adı taşıyorsa iki pod aynı anda tarama yaptı demektir (cert-check kilidi yenilenmedi).
\echo
\echo '── 2. Sertifika taraması çakışmaları (aynı alan adlarını kontrol eden iki run_id zamanda çakışıyor)'
\set c_cert 0
WITH r AS (
  SELECT run_id, domain, checked_at FROM certificate_checks
  WHERE checked_at >= :'since' AND run_id IS NOT NULL AND run_id NOT IN ('manual', 'health-refresh')
), runs AS (
  SELECT run_id, MIN(checked_at) AS basla, MAX(checked_at) AS bitis, COUNT(*) AS satir FROM r GROUP BY run_id
), pairs AS (
  SELECT x.run_id AS run_a, y.run_id AS run_b, x.basla AS a_basla, x.bitis AS a_bitis, y.basla AS b_basla, y.bitis AS b_bitis,
         (SELECT COUNT(DISTINCT r1.domain) FROM r r1 JOIN r r2 ON r2.domain = r1.domain
           WHERE r1.run_id = x.run_id AND r2.run_id = y.run_id) AS ortak_alan_adi
  FROM runs x JOIN runs y ON x.run_id < y.run_id AND x.basla <= y.bitis AND y.basla <= x.bitis
)
SELECT COUNT(*) AS c_cert FROM pairs WHERE ortak_alan_adi > 0
\gset
\echo '   çakışan tarama çifti:' :c_cert
SELECT (:c_cert > 0) AS show_cert \gset
\if :show_cert
WITH r AS (
  SELECT run_id, domain, checked_at FROM certificate_checks
  WHERE checked_at >= :'since' AND run_id IS NOT NULL AND run_id NOT IN ('manual', 'health-refresh')
), runs AS (
  SELECT run_id, MIN(checked_at) AS basla, MAX(checked_at) AS bitis, COUNT(*) AS satir FROM r GROUP BY run_id
), pairs AS (
  SELECT x.run_id AS run_a, y.run_id AS run_b, x.basla AS a_basla, x.bitis AS a_bitis, y.basla AS b_basla, y.bitis AS b_bitis,
         (SELECT COUNT(DISTINCT r1.domain) FROM r r1 JOIN r r2 ON r2.domain = r1.domain
           WHERE r1.run_id = x.run_id AND r2.run_id = y.run_id) AS ortak_alan_adi
  FROM runs x JOIN runs y ON x.run_id < y.run_id AND x.basla <= y.bitis AND y.basla <= x.bitis
)
SELECT run_a, a_basla, a_bitis, run_b, b_basla, b_bitis, ortak_alan_adi
FROM pairs WHERE ortak_alan_adi > 0 ORDER BY a_basla DESC LIMIT :detail_limit;
\endif

-- ── 3. Aynı alarm bildirimi aynı alıcıya kısa arayla iki kez (GÖNDERİLDİ satırları) ───────────
\echo
\echo '── 3. Bildirim çiftleri (aynı alarm + tetik + alıcı, iki SENT satırı <' :notify_gap_s 'sn arayla)'
\set c_notify 0
WITH n AS (
  SELECT alert_event_id, trigger, LOWER(COALESCE(recipient_email, '')) AS alici, sent_at,
         EXTRACT(EPOCH FROM (CAST(LEFT(sent_at, 19) AS timestamp)
              - LAG(CAST(LEFT(sent_at, 19) AS timestamp)) OVER (
                    PARTITION BY alert_event_id, trigger, LOWER(COALESCE(recipient_email, '')) ORDER BY sent_at))) AS ara_sn
  FROM notification_logs
  WHERE sent_at >= :'since' AND email_status LIKE 'SENT%'
    AND COALESCE(trigger, '') <> 'MANUAL' AND COALESCE(trigger, '') <> 'TEST' AND COALESCE(trigger, '') NOT LIKE '%\_TEST'
)
SELECT COUNT(*) AS c_notify FROM n WHERE ara_sn IS NOT NULL AND ara_sn < :notify_gap_s
\gset
\echo '   aday çift:' :c_notify
SELECT (:c_notify > 0) AS show_notify \gset
\if :show_notify
WITH n AS (
  SELECT alert_event_id, trigger, LOWER(COALESCE(recipient_email, '')) AS alici, sent_at,
         EXTRACT(EPOCH FROM (CAST(LEFT(sent_at, 19) AS timestamp)
              - LAG(CAST(LEFT(sent_at, 19) AS timestamp)) OVER (
                    PARTITION BY alert_event_id, trigger, LOWER(COALESCE(recipient_email, '')) ORDER BY sent_at))) AS ara_sn
  FROM notification_logs
  WHERE sent_at >= :'since' AND email_status LIKE 'SENT%'
    AND COALESCE(trigger, '') <> 'MANUAL' AND COALESCE(trigger, '') <> 'TEST' AND COALESCE(trigger, '') NOT LIKE '%\_TEST'
)
SELECT alert_event_id AS alarm, trigger AS tetik, LEFT(alici, 80) AS alici, sent_at AS ikinci_utc, ara_sn
FROM n WHERE ara_sn IS NOT NULL AND ara_sn < :notify_gap_s
ORDER BY sent_at DESC LIMIT :detail_limit;
\endif

-- ── 4. Aynı push iki kez GÖNDERİLDİ (aynı alarm + tekilleştirme anahtarı + kullanıcı) ─────────
\echo
\echo '── 4. Push çiftleri (aynı alarm / anahtar / kullanıcı için birden çok SENT satırı)'
\set c_push 0
SELECT COUNT(*) AS c_push FROM (
  SELECT 1 FROM user_push_deliveries
  WHERE created_at >= :'since' AND status = 'SENT' AND COALESCE(push_trigger, '') <> 'TEST'
  GROUP BY COALESCE(alert_event_id, 0), dedupe_key, username, COALESCE(team_id, 0)
  HAVING COUNT(*) > 1
) d
\gset
\echo '   aday çift:' :c_push
SELECT (:c_push > 0) AS show_push \gset
\if :show_push
SELECT COALESCE(alert_event_id, 0) AS alarm, dedupe_key AS anahtar, username AS kullanici, COALESCE(team_id, 0) AS takim,
       COUNT(*) AS adet, MIN(created_at) AS ilk_utc, MAX(created_at) AS son_utc
FROM user_push_deliveries
WHERE created_at >= :'since' AND status = 'SENT' AND COALESCE(push_trigger, '') <> 'TEST'
GROUP BY COALESCE(alert_event_id, 0), dedupe_key, username, COALESCE(team_id, 0)
HAVING COUNT(*) > 1
ORDER BY son_utc DESC LIMIT :detail_limit;
\endif

-- ── 5. Birden çok ONGOING ağ kesintisi / izleme bastırma satırı (kaynak başına en çok 1) ──────
\echo
\echo '── 5. Kaynak başına birden çok ONGOING network_outage_events satırı (tüm zamanlar)'
\set c_outage 0
SELECT COALESCE(SUM(adet - 1), 0) AS c_outage FROM (
  SELECT COUNT(*) AS adet FROM network_outage_events WHERE status = 'ONGOING'
  GROUP BY COALESCE(source, 'CERT') HAVING COUNT(*) > 1
) d
\gset
\echo '   fazla satır:' :c_outage
SELECT (:c_outage > 0) AS show_outage \gset
\if :show_outage
SELECT COALESCE(source, 'CERT') AS kaynak, COUNT(*) AS ongoing, MIN(detected_at) AS ilk_utc, MAX(detected_at) AS son_utc
FROM network_outage_events WHERE status = 'ONGOING'
GROUP BY COALESCE(source, 'CERT') HAVING COUNT(*) > 1;
\endif

-- ── 6. Aynı (hedef, alarm türü) için birden çok AÇIK alarm ───────────────────────────────────
\echo
\echo '── 6. Aynı (hedef, alarm türü) için birden çok açık alarm'
\set c_alert 0
SELECT COALESCE(SUM(adet - 1), 0) AS c_alert FROM (
  SELECT COUNT(*) AS adet FROM alert_events WHERE COALESCE(resolved, false) = false
  GROUP BY domain, alert_type HAVING COUNT(*) > 1
) d
\gset
\echo '   fazla açık alarm:' :c_alert
SELECT (:c_alert > 0) AS show_alert \gset
\if :show_alert
SELECT domain AS hedef, alert_type AS tur, COUNT(*) AS acik, STRING_AGG(id::text, ',' ORDER BY id) AS alarm_idleri,
       MIN(created_at) AS ilk_utc, MAX(created_at) AS son_utc
FROM alert_events WHERE COALESCE(resolved, false) = false
GROUP BY domain, alert_type HAVING COUNT(*) > 1
ORDER BY son_utc DESC LIMIT :detail_limit;
\endif

-- ── 7. Aynı kapsamda birden çok açık fırtına ─────────────────────────────────────────────────
\echo
\echo '── 7. Aynı kapsamda (scope_key) birden çok açık alarm fırtınası'
\set c_storm 0
SELECT COALESCE(SUM(adet - 1), 0) AS c_storm FROM (
  SELECT COUNT(*) AS adet FROM alert_storms WHERE COALESCE(resolved, false) = false
  GROUP BY scope_key HAVING COUNT(*) > 1
) d
\gset
\echo '   fazla açık fırtına:' :c_storm
SELECT (:c_storm > 0) AS show_storm \gset
\if :show_storm
SELECT scope_key AS kapsam, COUNT(*) AS acik, STRING_AGG(id::text, ',' ORDER BY id) AS firtina_idleri
FROM alert_storms WHERE COALESCE(resolved, false) = false
GROUP BY scope_key HAVING COUNT(*) > 1;
\endif

-- ── 8. Kaybolmuş bildirim izi: 25 dk'dan eski QUEUED_RETRY (SMTP 421 yeniden denemesi sonuçsuz) ─
\echo
\echo '── 8. 25 dk dan eski QUEUED_RETRY satırları (gönderen pod kapandı, sonuç yazılmadı)'
\set c_stuck 0
SELECT COUNT(*) AS c_stuck FROM notification_logs
WHERE sent_at >= :'since' AND sent_at < :'stuck_before' AND email_status LIKE 'QUEUED\_RETRY%'
\gset
\echo '   sonuçsuz satır:' :c_stuck
SELECT (:c_stuck > 0) AS show_stuck \gset
\if :show_stuck
SELECT id, alert_event_id AS alarm, trigger AS tetik, sent_at AS kuyruga_utc, LEFT(email_status, 80) AS durum
FROM notification_logs
WHERE sent_at >= :'since' AND sent_at < :'stuck_before' AND email_status LIKE 'QUEUED\_RETRY%'
ORDER BY sent_at DESC LIMIT :detail_limit;
\endif

-- ── 9. Çok pod Sürüm 3-3: dağıtık kontroller (monitor_check_jobs yoksa — 3-3 öncesi şema — bölüm atlanır) ─────
-- (a) izleme başına birden çok AÇIK iş (PENDING / CLAIMED): PostgreSQL'de ux_mcj_open tekil dizini bunu engeller; satır
--     görülürse dizin yok (ya da INVALID) ve taşınabilir "açık işi yoksa ekle" yolu yarışmış demektir → DUP.
-- (b) dönem çiftleri: dağıtılabilir sekiz türün (anahtar açık ya da kapalı) kontrol geçmişinde, son :dist_minutes dakikada
--     aynı izlemenin ardışık iki ZAMANLANMIŞ satırı izleme aralığının :period_frac katından (0,5) daha yakın → DUP. Gönderim
--     klasik tıktan sık olamaz (vade = max(aralık, klasik tık)) ve vade CAS'ı klasikle ortaktır: bir dönem tek iş, tek kontrol.
--     YALNIZ SAĞLIKLI izlemeler: pencerede tek bir başarısız satırı olan ya da o türden açık alarmı olan izleme dışlanır
--     (doğrulama / kurtarma yeniden kontrolleri tasarım gereği satır yazar). Tasarımın "aralık kovası başına satır" kuralı
--     yerine ardışık satır aralığı kullanılır: gönderim + sahiplenme gecikmesi ızgarayı birkaç saniye kaydırır, kova
--     sınırında iki satır tek kovaya düşer (yanlış pozitif). Kalan yanlış pozitifler: "Şimdi kontrol et" (port / ping /
--     http / keyword / page / pagespeed tablolarında manuel sütunu yok — scripted / dns'te manuel satırlar dışlanır),
--     YAVAŞ yanıt alarmının doğrulaması (HTTP_SLOW / KEYWORD_SLOW / PING_SLOW: satır başarılı ama yavaş, alarm açılmadıysa
--     dışlanmaz) ve işçisi ölen işin kira dolunca (≤ 150 sn, en az bir kez) yeniden koşup sonraki dönemin kontrolüne
--     yaklaşması. Tek tük satır değil, oran önemli: iki gönderen HER izlemenin HER dönemini ikiler.
--     DUP_TOTAL'a yalnız ana anahtar AÇIKKEN girer (period_strict=auto, varsayılan); anahtar kapalıyken — "Şimdi kontrol et"
--     yanlış pozitifleri yüzünden — bilgi olarak gösterilir. period_strict=1 her durumda sayar (duman testi), 0 hiç saymaz.
-- (c) :done_stale_s (300) sn'den eski DONE satırı = lider sonuçları boşaltmıyor → STUCK, YALNIZ ana anahtar açıkken
--     (app_settings; satır yoksa varsayılan kapalı). Anahtar kapalıyken boşaltıcı durur: kalan satırlar bilgi olarak
--     gösterilir, sayılmaz (yeniden açılışta eski sonuçlar uygulanmadan atılır — RUNBOOK "Dağıtık kontroller").
\set c_cjopen 0
\set c_cjperiod 0
\set c_cjdone 0
SELECT to_regclass('monitor_check_jobs') IS NOT NULL AND to_regclass('monitor_check_workers') IS NOT NULL AS has_check_jobs \gset
\if :has_check_jobs
\echo
\echo '── 9. Dağıtık kontroller (çok pod Sürüm 3-3)'
SELECT COALESCE((SELECT LOWER(TRIM(setting_value)) FROM app_settings
                  WHERE setting_key = 'site.monitor.checks.distributed.enabled' LIMIT 1), 'false') = 'true' AS dist_on \gset
SELECT CASE WHEN :'period_strict' = '1' THEN true WHEN :'period_strict' = '0' THEN false
            ELSE :'dist_on' = 't' END AS period_counts \gset
SELECT CASE WHEN :'dist_on' = 't' THEN 'AÇIK' ELSE 'kapalı (app_settings satırı yok ya da false)' END AS ana_anahtar,
       (SELECT setting_value FROM app_settings WHERE setting_key = 'site.monitor.checks.distributed.types' LIMIT 1) AS turler;
\echo '   kuyruk (bilgi): durum × tür, en eski satırın yaşı'
SELECT state AS durum, job_type AS tur, COUNT(*) AS adet,
       ROUND(((EXTRACT(EPOCH FROM now()) * 1000) - MIN(CASE WHEN state = 'DONE' THEN finished_ms ELSE created_ms END)) / 1000.0)
         AS en_eski_sn,
       COUNT(*) FILTER (WHERE state = 'CLAIMED' AND lease_until_ms < EXTRACT(EPOCH FROM now()) * 1000) AS suresi_dolmus_sahiplenme
FROM monitor_check_jobs GROUP BY state, job_type ORDER BY state, job_type;
\echo '   işçiler (bilgi): son 10 dk içinde nabız atan süreçler'
SELECT pod, instance_id AS surec, caps AS turler, slots AS yuva, busy AS dolu, completed AS tamamlanan, late AS gec,
       claim_lag_p95_ms AS sahiplenme_p95_ms, version AS surum, COALESCE(stopping, false) AS duruyor,
       ROUND(((EXTRACT(EPOCH FROM now()) * 1000) - heartbeat_ms) / 1000.0) AS nabiz_yasi_sn
FROM monitor_check_workers
WHERE heartbeat_ms >= (EXTRACT(EPOCH FROM now()) * 1000) - 600000
ORDER BY pod, heartbeat_ms DESC LIMIT :detail_limit;

\echo '   (a) izleme başına birden çok açık iş (PENDING / CLAIMED)'
SELECT COALESCE(SUM(adet - 1), 0) AS c_cjopen FROM (
  SELECT COUNT(*) AS adet FROM monitor_check_jobs WHERE state IN ('PENDING', 'CLAIMED')
  GROUP BY job_type, monitor_id HAVING COUNT(*) > 1
) d
\gset
\echo '   fazla açık iş:' :c_cjopen
SELECT (:c_cjopen > 0) AS show_cjopen \gset
\if :show_cjopen
SELECT job_type AS tur, monitor_id AS izleme, COUNT(*) AS acik_is, STRING_AGG(id::text || ':' || state, ',' ORDER BY id) AS isler,
       STRING_AGG(DISTINCT dispatcher, ',') AS gonderenler
FROM monitor_check_jobs WHERE state IN ('PENDING', 'CLAIMED')
GROUP BY job_type, monitor_id HAVING COUNT(*) > 1
ORDER BY acik_is DESC, tur, izleme LIMIT :detail_limit;
\endif

\echo '   (b) dönem çiftleri — sağlıklı izleme, son' :dist_minutes 'dk, ardışık iki zamanlanmış satır < aralık ×' :period_frac
WITH h AS (
  SELECT 'port' AS tur, monitor_id, '' AS alt, checked_at,
         CASE WHEN open = FALSE OR (open IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END AS fail
    FROM port_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'ping', monitor_id, '', checked_at,
         CASE WHEN error IS NOT NULL OR packet_loss >= 100 THEN 1 ELSE 0 END
    FROM ping_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'http', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END
    FROM http_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'keyword', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END
    FROM keyword_results WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'page', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END
    FROM page_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'pagespeed', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR error_message IS NOT NULL OR COALESCE(breached_metrics, '') <> '' THEN 1 ELSE 0 END
    FROM pagespeed_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'scripted', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND (error IS NOT NULL OR exit_code <> 0 OR checks_failed > 0)) THEN 1 ELSE 0 END
    FROM scripted_checks WHERE checked_at >= :'dist_since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'dns', monitor_id, COALESCE(record_type, ''), checked_at,
         CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END
    FROM dns_records WHERE checked_at >= :'dist_since' AND COALESCE(manual, false) = false
), iv AS (
  SELECT 'port' AS tur, id AS monitor_id, interval_seconds FROM port_monitors
  UNION ALL SELECT 'ping', id, interval_seconds FROM ping_monitors
  UNION ALL SELECT 'http', id, interval_seconds FROM http_monitors
  UNION ALL SELECT 'keyword', id, interval_seconds FROM keyword_monitors
  UNION ALL SELECT 'page', id, interval_seconds FROM page_monitors
  UNION ALL SELECT 'pagespeed', id, interval_seconds FROM pagespeed_monitors
  UNION ALL SELECT 'scripted', id, interval_seconds FROM scripted_monitors
  UNION ALL SELECT 'dns', id, interval_seconds FROM dns_monitors
), alarm AS (
  SELECT CASE WHEN alert_type LIKE 'PORT\_%' THEN 'port'
              WHEN alert_type LIKE 'PING\_%' THEN 'ping'
              WHEN alert_type LIKE 'HTTP\_%' OR alert_type = 'DOMAIN_EXPIRY' THEN 'http'
              WHEN alert_type LIKE 'KEYWORD%' THEN 'keyword'
              WHEN alert_type LIKE 'PAGESPEED\_%' THEN 'pagespeed'
              WHEN alert_type LIKE 'PAGE\_%' THEN 'page'
              WHEN alert_type LIKE 'DNS\_%' THEN 'dns'
              WHEN alert_type LIKE 'SCRIPTED\_%' THEN 'scripted' END AS tur,
         CAST(substring(context_json from '"monitor_id"[[:space:]]*:[[:space:]]*"?([0-9]{1,18})') AS BIGINT) AS monitor_id
    FROM alert_events
   WHERE COALESCE(resolved, false) = false AND context_json LIKE '%monitor\_id%'
), g AS (
  SELECT h.tur, h.monitor_id, h.alt, h.checked_at,
         MAX(h.fail) OVER (PARTITION BY h.tur, h.monitor_id) AS fail_any,
         EXTRACT(EPOCH FROM (CAST(LEFT(h.checked_at, 19) AS timestamp)
              - LAG(CAST(LEFT(h.checked_at, 19) AS timestamp)) OVER (
                    PARTITION BY h.tur, h.monitor_id, h.alt ORDER BY h.checked_at))) AS ara_sn
    FROM h
), pairs AS (
  SELECT g.tur, g.monitor_id, g.alt, g.checked_at, g.ara_sn, iv.interval_seconds
    FROM g JOIN iv ON iv.tur = g.tur AND iv.monitor_id = g.monitor_id
   WHERE g.ara_sn IS NOT NULL AND g.fail_any = 0 AND iv.interval_seconds > 0
     AND g.ara_sn < iv.interval_seconds * :period_frac
     AND NOT EXISTS (SELECT 1 FROM alarm a WHERE a.tur = g.tur AND a.monitor_id = g.monitor_id)
)
SELECT COUNT(*) AS c_cjperiod_all FROM pairs
\gset
\if :period_counts
\set c_cjperiod :c_cjperiod_all
\echo '   aday çift:' :c_cjperiod
\else
\echo '   aday çift:' :c_cjperiod_all '(bilgi, sayılmaz — anahtar kapalı ya da period_strict=0; "Şimdi kontrol et" de satır yazar)'
\endif
SELECT (:c_cjperiod_all > 0) AS show_cjperiod \gset
\if :show_cjperiod
WITH h AS (
  SELECT 'port' AS tur, monitor_id, '' AS alt, checked_at,
         CASE WHEN open = FALSE OR (open IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END AS fail
    FROM port_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'ping', monitor_id, '', checked_at,
         CASE WHEN error IS NOT NULL OR packet_loss >= 100 THEN 1 ELSE 0 END
    FROM ping_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'http', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END
    FROM http_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'keyword', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END
    FROM keyword_results WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'page', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND error IS NOT NULL) THEN 1 ELSE 0 END
    FROM page_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'pagespeed', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR error_message IS NOT NULL OR COALESCE(breached_metrics, '') <> '' THEN 1 ELSE 0 END
    FROM pagespeed_checks WHERE checked_at >= :'dist_since'
  UNION ALL SELECT 'scripted', monitor_id, '', checked_at,
         CASE WHEN ok = FALSE OR (ok IS NULL AND (error IS NOT NULL OR exit_code <> 0 OR checks_failed > 0)) THEN 1 ELSE 0 END
    FROM scripted_checks WHERE checked_at >= :'dist_since' AND COALESCE(manual, false) = false
  UNION ALL SELECT 'dns', monitor_id, COALESCE(record_type, ''), checked_at,
         CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END
    FROM dns_records WHERE checked_at >= :'dist_since' AND COALESCE(manual, false) = false
), iv AS (
  SELECT 'port' AS tur, id AS monitor_id, interval_seconds FROM port_monitors
  UNION ALL SELECT 'ping', id, interval_seconds FROM ping_monitors
  UNION ALL SELECT 'http', id, interval_seconds FROM http_monitors
  UNION ALL SELECT 'keyword', id, interval_seconds FROM keyword_monitors
  UNION ALL SELECT 'page', id, interval_seconds FROM page_monitors
  UNION ALL SELECT 'pagespeed', id, interval_seconds FROM pagespeed_monitors
  UNION ALL SELECT 'scripted', id, interval_seconds FROM scripted_monitors
  UNION ALL SELECT 'dns', id, interval_seconds FROM dns_monitors
), alarm AS (
  SELECT CASE WHEN alert_type LIKE 'PORT\_%' THEN 'port'
              WHEN alert_type LIKE 'PING\_%' THEN 'ping'
              WHEN alert_type LIKE 'HTTP\_%' OR alert_type = 'DOMAIN_EXPIRY' THEN 'http'
              WHEN alert_type LIKE 'KEYWORD%' THEN 'keyword'
              WHEN alert_type LIKE 'PAGESPEED\_%' THEN 'pagespeed'
              WHEN alert_type LIKE 'PAGE\_%' THEN 'page'
              WHEN alert_type LIKE 'DNS\_%' THEN 'dns'
              WHEN alert_type LIKE 'SCRIPTED\_%' THEN 'scripted' END AS tur,
         CAST(substring(context_json from '"monitor_id"[[:space:]]*:[[:space:]]*"?([0-9]{1,18})') AS BIGINT) AS monitor_id
    FROM alert_events
   WHERE COALESCE(resolved, false) = false AND context_json LIKE '%monitor\_id%'
), g AS (
  SELECT h.tur, h.monitor_id, h.alt, h.checked_at,
         MAX(h.fail) OVER (PARTITION BY h.tur, h.monitor_id) AS fail_any,
         EXTRACT(EPOCH FROM (CAST(LEFT(h.checked_at, 19) AS timestamp)
              - LAG(CAST(LEFT(h.checked_at, 19) AS timestamp)) OVER (
                    PARTITION BY h.tur, h.monitor_id, h.alt ORDER BY h.checked_at))) AS ara_sn
    FROM h
), pairs AS (
  SELECT g.tur, g.monitor_id, g.alt, g.checked_at, g.ara_sn, iv.interval_seconds
    FROM g JOIN iv ON iv.tur = g.tur AND iv.monitor_id = g.monitor_id
   WHERE g.ara_sn IS NOT NULL AND g.fail_any = 0 AND iv.interval_seconds > 0
     AND g.ara_sn < iv.interval_seconds * :period_frac
     AND NOT EXISTS (SELECT 1 FROM alarm a WHERE a.tur = g.tur AND a.monitor_id = g.monitor_id)
)
SELECT tur, monitor_id AS izleme, NULLIF(alt, '') AS kayit_turu, interval_seconds AS aralik_sn, COUNT(*) AS cift,
       ROUND(MIN(ara_sn)::numeric, 1) AS en_kisa_ara_sn, MAX(checked_at) AS son_cift_utc
FROM pairs
GROUP BY tur, monitor_id, alt, interval_seconds
ORDER BY cift DESC, son_cift_utc DESC LIMIT :detail_limit;
\endif

\echo '   (c) boşaltılmamış sonuçlar: ' :done_stale_s 'sn den eski DONE satırları'
SELECT COUNT(*) AS c_cjdone_all FROM monitor_check_jobs
WHERE state = 'DONE' AND finished_ms < (EXTRACT(EPOCH FROM now()) * 1000) - :done_stale_s * 1000
\gset
\if :dist_on
\set c_cjdone :c_cjdone_all
\echo '   boşaltılmamış eski sonuç (anahtar açık — STUCK):' :c_cjdone
\else
\echo '   anahtar kapalı: kalan eski DONE satırı' :c_cjdone_all '(bilgi, sayılmaz — boşaltıcı kapalı; yeniden açılışta atılır)'
\endif
SELECT (:c_cjdone > 0) AS show_cjdone \gset
\if :show_cjdone
SELECT id, job_type AS tur, monitor_id AS izleme, worker AS isci, attempts AS deneme,
       ROUND(((EXTRACT(EPOCH FROM now()) * 1000) - finished_ms) / 1000.0) AS yas_sn
FROM monitor_check_jobs
WHERE state = 'DONE' AND finished_ms < (EXTRACT(EPOCH FROM now()) * 1000) - :done_stale_s * 1000
ORDER BY finished_ms LIMIT :detail_limit;
\endif
\endif

-- ── Özet (makinece okunur) ───────────────────────────────────────────────────────────────────
\echo
SELECT :c_checks + :c_cert + :c_notify + :c_push + :c_outage + :c_alert + :c_storm + :c_cjopen + :c_cjperiod AS dup_total,
       :c_stuck + :c_cjdone AS stuck_total \gset
\echo 'DUP_TOTAL=':dup_total
\echo 'STUCK_TOTAL=':stuck_total
