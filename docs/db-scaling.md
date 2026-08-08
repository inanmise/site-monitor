# DB Ölçeklenebilirlik & İşletme Rehberi

site-monitor sürekli kayıt üreten bir izleme sistemidir; kontrol sonucu / log / audit / alarm
tabloları append-only ve zamanla milyonlarca satıra çıkar. Bu doküman büyümeyi yönetmek için
uygulanan mekanizmaları ve işletme (ops) adımlarını özetler.

## 1. En hızlı büyüyen tablolar (büyüme envanteri)

| Tablo | Yazım | ~100 monitör → 1 yıl | Kontrol |
|---|---|---|---|
| `activity_log` | her kontrol tipi için +1 satır | ~30–100M (baskın) | retention 90g + rollup + batch purge |
| `port_checks`/`ping_checks`/`keyword_results` | 30sn sweep, 60sn/monitör | ~26M/tablo | retention 180g + rollup + batch purge |
| `http_checks` | 30sn sweep (monitör aralığına göre) | ~26M | retention 180g + rollup + batch purge |
| `uptime_checks`/`dns_records`/`domain_checks` | 5dk–saatlik | ~5M | retention 180g (dns/domain baseline korunur) |
| `scripted_checks` | k6 senaryo çıktısı (büyük TEXT) | monitör×sıklık | retention 180g + batch purge |
| `page_checks` | sayfa-bütünlüğü kontrol özeti | monitör×sıklık | retention 180g + rollup + batch purge |
| `page_resource_issues` | yalnız SORUNLU kaynaklar (kontrol başına 0–N) | değişken (bozuk sitede yüksek) | retention 90g + batch purge (önce çocuk) |
| `system_heartbeat` | dakikada 1 (pod başına) | ~0.5M/pod | retention 30g |
| `audit_log` | auth/güvenlik olayları | trafik | retention 365g + JSONL arşiv + batch purge |
| `notification_logs` | gönderilen her bildirim | alarm hacmi | retention 90g + batch purge |
| `incident_images`/`weekly_report_images` | BYTEA (satır başına MB'lar) | kullanım | **satır sayısı değil BOYUT** riski: 730g |

> Not: FK sırası ("önce çocuk") gerçek bir veritabanı kısıtı değil, kod konvansiyonudur —
> `RetentionCatalog.ALL` liste sırası bu sırayı taşır ve `RetentionSqlIdentityTest` doğrular.

Uzun-dönem trend `monitor_check_daily` (gün) ve `monitor_check_hourly` (saat) rollup tablolarında
korunur → ham veri kısa retention'la silinebilir. Saatlik katman 2026-08'de eklendi: günlük özet
"o gün %97,4" der ama kesintinin **saatini** kaybeder; olay incelemesi için saat çözünürlüğü şarttır.

## 2. Uygulamadaki mekanizmalar (kod)

- **Sorgu hızı:** monitör-liste "en güncel kontrol" sorguları (`findLatestPer*`) full-table `GROUP BY`
  yerine küçük monitör tablosuna **LATERAL join** ile index-seek yapar (~3.5sn → ~0.2ms). Dashboard
  özetleri (`/audit/stats`) 60sn Caffeine cache'li.
- **Batch'li purge:** gece temizlik (`SchedulerService.cleanupOldLogs`, cron `0 30 3`) yüksek-hacimli
  tabloları tek dev DELETE yerine 10k'lık dilimlerle siler + `ANALYZE` (bloat + uzun-tx önleme).
- **Rollup:** purge'den ÖNCE son N günü `monitor_check_daily` (gün kovası) ve `monitor_check_hourly`
  (saat kovası) tablolarına aggregate eder — aynı huni, tek fark kova genişliği (10 / 13 karakter;
  `RollupSqlShapeTest` iki yolun ayrışmasını engeller). Upsert idempotenttir.
- **Geriye doldurma:** `POST /api/admin/retention/backfill-hourly` (ekranda "Saatlik özeti doldur")
  saatlik kovaları ham serilerden geriye dönük hesaplar. Böylece "rollup birikene kadar bekle"
  ön koşulu ortadan kalkar: ham veri hâlâ eldeyken tüm pencere tek seferde kurtarılır.
- **Per-table autovacuum:** yüksek-yazımlı tablolarda `autovacuum_vacuum_scale_factor=0.02` (varsayılan
  %20 yerine %2 ölü-tuple'da vacuum) — `applySchemaPatches`'te `ALTER TABLE` ile (dış DB'de de geçerli).

### Yapılandırılabilir anahtarlar

> **Saklama süreleri artık burada listelenmiyor.** Tam politika matrisi (tablo × süre × taban ×
> ayar anahtarı × silme kuralı × gerekçe) **katalogdan üretilen**
> [`RETENTION_POLITIKASI.md`](RETENTION_POLITIKASI.md) dosyasındadır. Bu tablo bilinçli olarak
> kaldırıldı: elle tutulan kopya koddan sapıyordu — 2026-08'e kadar burada **yeniden adlandırma
> öncesindeki eski anahtar öneki** yazıyordu, oysa kod uzun süredir `site.monitor.*` okuyor;
> dokümanı izleyen bir ops mühendisi ayarı set eder, hiçbir şey değişmez, sessizce varsayılana düşerdi.

Süreleri değiştirmek için: **Ayarlar → Veri Saklama**. Değişiklik anında geçerli olur; her politikanın
kodda tanımlı bir **taban (minDays)** değeri vardır ve altına inilemez. Kısaltma ayrıca onay diyaloğu
ister ve `RETENTION_SETTINGS_SHORTENED` denetim olayı yazar.

Retention dışında kalan ölçek anahtarları:

| Anahtar | Varsayılan | Açıklama |
|---|---|---|
| `site.monitor.retention.purge-batch-size` | 10000 | Batch silme dilim boyutu (taban 1000) |
| `site.monitor.rollup.lookback-days` | 3 | Gece kaç tam günü rollup'la (günlük + saatlik) |
| `site.monitor.rollup.retention-days` | 730 | Günlük özet saklama (taban 90) |
| `site.monitor.rollup.hourly-retention-days` | 365 | Saatlik özet saklama (taban 60) |
| `site.monitor.db.metrics-refresh-ms` | 300000 | Büyüme metriği örnekleme aralığı |
| `site.monitor.db.growth-warn-rows` | 5000000 | Tablo satır eşiği (aşınca WARN) |
| `site.monitor.retention.hold-enabled` | false | **Legal hold** — açıkken HİÇBİR silme yapılmaz |

**Ham kontrol serisi retention'ını kısaltma:** ham seriler artık **tür bazında** ayarlanabilir
(`site.monitor.series.<tür>.retention-days`, hepsi 180 gün varsayılan, taban 30). Kısaltmadan önce
rollup katmanının ilgili pencereyi kapsıyor olması gerekir — trend orada kalır, ham veri gider.
Beklemeye gerek yok: **"Saatlik özeti doldur"** (backfill) ham veriden geçmişi tek seferde üretir,
sonra kısaltma yapılır. Kısaltma geri alınamaz; önce dry-run ile kaç satırın gideceği görülmelidir.

### Temizliğin kendi izlenmesi

Gece temizliği her çalışmasını `retention_run` / `retention_run_item` tablolarına yazar
(tablo başına silinen satır, süre, hata). Bundan beslenenler:
`Sistem Sağlığı → cleanup` sinyali ("N saattir çalışmadı" / hatalı politika),
Prometheus metrikleri (`retention_rows_deleted_total{policy,table}`, `retention_run_duration_seconds`,
`retention_last_success_epoch`) ve Ayarlar → Veri Saklama'daki çalışma geçmişi.

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
  psql -h <host> -U sitemonitor -d sitemonitor -f scripts/db-health.sql
  ```
- **`scripts/perf-indexes.sql`** — büyüme/performans index'leri. **Büyük prod tablolarında deploy
  ÖNCESİ `CREATE INDEX CONCURRENTLY` ile çalıştırın** (startup'ta kilit olmasın; app'in
  `IF NOT EXISTS` patch'i no-op olur):
  ```
  psql -h <host> -U sitemonitor -d sitemonitor -v ON_ERROR_STOP=1 -f scripts/perf-indexes.sql
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
   psql -h <prod-host> -U sitemonitor -d sitemonitor -v ON_ERROR_STOP=1 -f scripts/perf-indexes.sql
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
  "lock başka pod'da, atlanıyor"); rollup satırları `monitor_check_daily` ve `monitor_check_hourly`
  tablolarını dolduruyor ("Daily rollup: ..." + "Saatlik rollup: ... kova").
- `psql -f scripts/db-health.sql` ile tablo boyutları/bloat/en yavaş sorgular gözden geçir.

### ROLLUP DOĞRULANDIKTAN SONRA (opsiyonel, ops kararı)
Sıra: **(1)** Veri Saklama ekranından "Saatlik özeti doldur" → saatlik kovalar ham veriden geriye
doldurulur (silme yok, tekrarı güvenli). **(2)** `monitor_check_hourly` satır sayısı/en eski kaydı
aynı ekrandan doğrulanır. **(3)** Dry-run ile kaç ham satırın gideceği görülür. **(4)** Ham
retention kademeli kısaltılır (ör. 180 → 90, gözlemden sonra 90 → 45).

Kısaltmanın ne KORUDUĞU ve ne KAYBETTİĞİ:
- **Korunur:** günlük/saatlik uptime oranı, ortalama ve maksimum yanıt süresi, kesintinin saati.
- **Kaybolur:** satır düzeyinde ham kayıtlar — tekil kontrolün hata metni, HTTP kodu, CSV dışa
  aktarımı ve Kontrol Geçmişi listesi yeni pencerenin gerisi için boş kalır.
Bu nedenle kısaltma, olay incelemesinin pratikte ne kadar geriye gittiğine göre seçilmelidir.

## 7. Sayfa Bütünlüğü (Page Integrity) — güvenlik/perf notları (2026-07 inceleme)

**Sorgu planı kanıtı (sentetik 1M `page_checks` + 1.5M `page_resource_issues`, EXPLAIN ANALYZE):**
tüm sıcak sorgular **index-scan** (fact tablolarda seq-scan YOK):
- `findLatestPerMonitor` (LATERAL, monitör-başı en güncel) → ~**0.2 ms**
- `pageIssues`/`findFiltered` (monitor_id + checked_at DESC, LIMIT 500) → ~**1.3 ms**
- `responseSeriesRaw` (page_checks aralık, LIMIT 5000) → ~**14 ms**
Composite index'ler (`idx_pc_monitor_checked`, `idx_pri_monitor_checked`) + tek-kolon `checked_at`
index'leri birlikte kullanılıyor; az monitör (2) senaryosunda planner tek-kolon checked_at'ı seçse de
gerçek çok-monitörlü kardinalitede composite seçilir. Sentetik veri sonrası temizlendi + VACUUM.

**Yük sınırları (kod):** kontrol başına wall-clock deadline (`cert.monitor.page.max-check-seconds`, vars.
120sn), tek sayfa ≤500 kaynak, crawl geneli ≤1500 kaynak (bellek + INSERT patlaması önleme), manuel tetik
per-monitör cooldown (`page.manual-cooldown-seconds`, vars. 20sn) + DAİMA SINGLE_PAGE (inline crawl yok).
Kaynak doğrulama sanal-thread executor + Semaphore ile sınırlı → 400-kaynak × tekrarlı kontrolde platform
thread stabil (E20 testi). `page_*` yazımları IDENTITY id kullanır → Hibernate JDBC batch kapalı; global
kaynak capi bunu telafi eder.

**Güvenlik residual — DNS-rebinding (Medium, bilinçli kabul):** `SsrfGuard.validate()` her hop'ta çözülen
IP'leri döndürür ama `PageCheckerService` istekleri host ADIYLA atar → HttpClient bağımsız yeniden çözer
(TOCTOU penceresi). Metadata/loopback/link-local HER ZAMAN bloklu + JVM pozitif-DNS cache pratik riski
azaltır. IP-pinning (NetworkResolver/rewriteHostToIp) HTTPS SNI karmaşası nedeniyle uygulanmadı.
**Ops:** JVM `networkaddress.cache.ttl`'i **0'a çekmeyin** (varsayılan pozitif-cache rebinding penceresini kapatır).

**IDOR düzeltmesi (2026-07):** serbest-form monitör listeleri (page/http/keyword/domain/ping) + response-series
uçları artık `SessionScope.canView` ile takım-kapsamlı. Port/DNS dual-source (teamId=null envanter-türevi)
olduğundan `canView(null)` kırılganlığıyla o iki tür KAPSAM DIŞI bırakıldı — ayrı bir dual-source-farkında
düzeltme gerektirir (açık kalan iş).
