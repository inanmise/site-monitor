# DB Ölçeklenebilirlik & İşletme Rehberi

cert-monitor sürekli kayıt üreten bir izleme sistemidir; kontrol sonucu / log / audit / alarm
tabloları append-only ve zamanla milyonlarca satıra çıkar. Bu doküman büyümeyi yönetmek için
uygulanan mekanizmaları ve işletme (ops) adımlarını özetler.

## 1. En hızlı büyüyen tablolar (büyüme envanteri)

| Tablo | Yazım | ~100 monitör → 1 yıl | Kontrol |
|---|---|---|---|
| `activity_log` | her kontrol tipi için +1 satır | ~30–100M (baskın) | retention 90g (config) + rollup |
| `port_checks`/`ping_checks`/`keyword_results` | 30sn sweep, 60sn/monitör | ~26M/tablo | retention 180g + rollup + batch purge |
| `http_checks`/`uptime_checks`/`dns_records` | 5dk | ~5M | aynı |
| `audit_log` | auth/güvenlik olayları | trafik | retention 365g + JSONL arşiv |

Uzun-dönem trend `monitor_check_daily` rollup tablosunda korunur → ham veri kısa retention'la silinebilir.

## 2. Uygulamadaki mekanizmalar (kod)

- **Sorgu hızı:** monitör-liste "en güncel kontrol" sorguları (`findLatestPer*`) full-table `GROUP BY`
  yerine küçük monitör tablosuna **LATERAL join** ile index-seek yapar (~3.5sn → ~0.2ms). Dashboard
  özetleri (`/audit/stats`) 60sn Caffeine cache'li.
- **Batch'li purge:** gece temizlik (`SchedulerService.cleanupOldLogs`, cron `0 30 3`) yüksek-hacimli
  tabloları tek dev DELETE yerine 10k'lık dilimlerle siler + `ANALYZE` (bloat + uzun-tx önleme).
- **Rollup:** purge'den ÖNCE son N günü `monitor_check_daily`'ye aggregate eder (idempotent upsert).
- **Per-table autovacuum:** yüksek-yazımlı tablolarda `autovacuum_vacuum_scale_factor=0.02` (varsayılan
  %20 yerine %2 ölü-tuple'da vacuum) — `applySchemaPatches`'te `ALTER TABLE` ile (dış DB'de de geçerli).

### Yapılandırılabilir anahtarlar (Admin → Ayarlar veya env)
| Anahtar | Varsayılan | Açıklama |
|---|---|---|
| `cert.monitor.retention.purge-batch-size` | 10000 | Batch silme dilim boyutu |
| `cert.monitor.rollup.lookback-days` | 3 | Gece kaç tam günü rollup'la |
| `cert.monitor.rollup.retention-days` | 730 | Rollup (trend) saklama |
| `cert.monitor.network-outage.retention-days` | 365 | |
| `cert.monitor.incident.retention-days` | 0 | 0 = olay kayıtları HİÇ silinmez (opt-in) |
| `cert.monitor.audit.archive-retention-days` | 365 | JSONL arşiv dosyası rotasyonu |
| `cert.monitor.activity.retention-days` | 90 | En hızlı seri (activity_log) |
| `cert.monitor.{audit}.retention-days` | 365 | |

**Ham kontrol serisi retention'ını kısaltma (rollup doğrulandıktan SONRA):** rollup birkaç gün üretim
verisi biriktirdikten sonra, ham 180g retention `tsCutoff` config'iyle (ör. 30–45g) düşürülebilir —
trend `monitor_check_daily`'de kalır. Sıra önemli: önce rollup birikir, sonra ham kısaltılır.

## 3. PostgreSQL sunucu ayarları

### Self-hosted / k8s StatefulSet (`k8s/postgres.yaml`)
Tune edildi: `shared_buffers=512MB`, `effective_cache_size=1536MB`, `work_mem=16MB`,
`maintenance_work_mem=128MB`, `shared_preload_libraries=pg_stat_statements`, `max_connections=150`;
kaynak limitleri 2Gi/1CPU, PVC 20Gi. (shared_buffers=512MB için ≥2Gi bellek gerekir.)

### Dış / yönetilen prod DB (Helm `postgresql.enabled=false` — varsayılan)
Sunucu-global ayarları **ops uygular** (uygulama değiştiremez). Önerilen başlangıç (RAM'e göre ölçekle):
- `shared_buffers` ≈ RAM'in %25'i
- `effective_cache_size` ≈ RAM'in %75'i
- `work_mem` = 16–32MB (bağlantı × work_mem toplamına dikkat)
- `maintenance_work_mem` = 128–256MB
- `shared_preload_libraries = 'pg_stat_statements'` (en yavaş sorgu teşhisi + `db-health.sql` bölüm 4)
- `max_connections` ≥ (Hikari pool × replica) + admin/monitoring headroom

**Bağlantı matematiği (kritik):** Hikari `DB_POOL_MAX=25`. App tek-pod tasarımı (base `replicaCount=1`)
ama prod overlay (`helm/.../environments/master.yaml`) `replicaCount: 3` → 3×25 = 75 bağlantı.
`max_connections` ≥ 75 + headroom (≥100, tercihen 150) olmalı; aksi halde pod'lar bağlantı bulamaz.
Çok-pod'a çıkarken pool veya max_connections'ı buna göre boyutlandırın.

## 4. Teşhis & migration script'leri (`scripts/`)

- **`scripts/db-health.sql`** — en büyük tablolar, satır sayıları, ölü-tuple/bloat, kullanılmayan
  index'ler, en yavaş sorgular (pg_stat_statements), bağlantı doygunluğu, temel sunucu ayarları.
  ```
  psql -h <host> -U certmonitor -d certmonitor -f scripts/db-health.sql
  ```
- **`scripts/perf-indexes.sql`** — büyüme/performans index'leri. **Büyük prod tablolarında deploy
  ÖNCESİ `CREATE INDEX CONCURRENTLY` ile çalıştırın** (startup'ta kilit olmasın; app'in
  `IF NOT EXISTS` patch'i no-op olur):
  ```
  psql -h <host> -U certmonitor -d certmonitor -v ON_ERROR_STOP=1 -f scripts/perf-indexes.sql
  ```
  Yarıda kalan index INVALID kalabilir: `SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;`
  → `DROP INDEX CONCURRENTLY` ile atıp tekrar çalıştırın.

## 5. Gözlemlenebilirlik
`/metrics` (Prometheus) üzerinde `db_table_rows{table=...}` ve `db_table_bytes{table=...}` gauge'ları
tablo büyümesini izler; büyüme projeksiyon eşiğini aşarsa log WARN üretilir (bkz. Micrometer metrikleri).
`Sistem Sağlığı → Veritabanı` ekranı (DbAnalyticsService) tablo boyutlarını + en yavaş sorguları gösterir.

## 6. Deployment — prod'a çıkış checklist

Kod tarafı geriye-uyumlu/eklemelidir (yeni tablolar idempotent patch'le oluşur, retention kısaltma
opt-in'dir, gece temizlik/rollup dağıtık-kilitlidir → çok-pod'da tek pod çalışır). Yine de aşağıdaki
**ops adımları OTOMATİK UYGULANMAZ** — dış/yönetilen prod DB'de elle yapılmalıdır.

### DEPLOY ÖNCESİ (zorunlu)
1. **Index'leri CONCURRENTLY oluştur.** Yeni index'ler startup'ta `applySchemaPatches` ile düz
   (tabloyu KİLİTLEYEN) `CREATE INDEX` çalıştırır; büyük `audit_log`/`alert_events`'te pod açılışını
   kilitler/geciktirir. Deploy'dan önce:
   ```
   psql -h <prod-host> -U certmonitor -d certmonitor -v ON_ERROR_STOP=1 -f scripts/perf-indexes.sql
   ```
   Böylece startup patch'i no-op olur. (Taze/küçük DB'de gerek yok.)
2. **Bağlantı matematiğini doğrula.** Prod 3 replika × Hikari pool 25 = **75 bağlantı**.
   `max_connections ≥ 75 + admin/monitoring headroom` (≥100, tercihen 150) olduğunu teyit et; değilse
   pod'lar bağlantı bulamaz. (Bkz. §3 bağlantı matematiği.)
3. **(Önerilen) Dış DB sunucu tuning'i** uygula (§3): `shared_buffers` ~RAM %25, `effective_cache_size`
   ~RAM %75, `work_mem` 16–32MB, `maintenance_work_mem` 128–256MB.
4. **(Opsiyonel) `pg_stat_statements` preload** (`shared_preload_libraries`) — `db-health.sql` bölüm 4
   (en yavaş sorgular) için. Uygulama başlangıçta `CREATE EXTENSION`'ı best-effort dener.

> Not: `k8s/postgres.yaml` tuning'i yalnız **self-hosted** k8s Postgres içindir; prod Helm dış-DB
> kullanır (`postgresql.enabled=false`), oraya gitmez.

### DEPLOY SONRASI (smoke doğrulama)
- Monitör-liste sayfaları açılıyor (LATERAL en-güncel sorguları) + Denetim/Aktivite ekranları.
- `/metrics` içinde `db_table_rows{table=...}` gauge'ları görünüyor.
- İlk gece (03:30) `cleanupOldLogs` logu: yalnız **tek pod** "Nightly cleanup" yazar (diğerleri
  "lock başka pod'da, atlanıyor"); rollup satırı `monitor_check_daily`'yi dolduruyor.
- `psql -f scripts/db-health.sql` ile tablo boyutları/bloat/en yavaş sorgular gözden geçir.

### ROLLUP DOĞRULANDIKTAN SONRA (opsiyonel, ops kararı)
Rollup birkaç gün prod'da doğru veri ürettikten sonra, ham kontrol serisi retention'ı config'ten
kısaltılabilir (ör. 180g → 30–45g) — uzun-dönem trend `monitor_check_daily`'de kalır. Sıra:
önce rollup birikir, **sonra** ham kısaltılır (trend kaybı olmaz).
