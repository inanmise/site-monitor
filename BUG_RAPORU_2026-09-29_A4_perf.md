# BUG RAPORU — Eksen A4 · Eşzamanlılık · Zamanlayıcı · Kaynak Sızıntısı · Veri Katmanı · Sorgu/Algoritma Performansı · 2026-09-29

**Sürüm:** 20.91.0 (main) + commit edilmemiş "ortam adı ayarı" değişikliği · **Yöntem:** salt-okunur kaynak denetimi (E1 + E3 +
performans; kod değiştirilmedi, test/derleme/sunucu koşturulmadı). Her YÜKSEK/ORTA bulgu kaynakta dosya:satır ile okunarak
doğrulandı; baseline (BUG_RAPORU_2026-09-23, _7, _6, _5, BUG_REGRESYON_2026-09-29c) E1/E3/perf maddeleri tek tek yeniden
kontrol edildi. **Kısıt varsayımı:** tek pod (2 çekirdek / ~1,1 GiB heap), 100 eşzamanlı kullanıcı, 200–1000+ alan adı/izleme.

## Genel değerlendirme

Kod tabanı bu eksende **olgun**: 2026-07 sızıntı denetiminin ve 09-23 turunun bulguları kaynakta gerçekten kapanmış
(N+1'ler toplu sorguya, ham liste sorguları tavanlı/akışlı, tüm önbellekler boyut+TTL sınırlı, her elle kurulan yürütücü
`@PreDestroy`'lu, dağıtık kilitler geçici DB hatasında turu ATLIYOR, retention batch'li + 10 dk tavanlı, k6 süreçleri zaman
aşımında `destroyForcibly`). **KRİTİK/YÜKSEK bulgu yok.** Bulunanlar dört ORTA (biri kök-neden niteliğinde: paylaşılan
yürütücünün etkin paralelliği 20 — "max 50" kâğıt üstünde) ve on iki DÜŞÜK; çoğu "tek pod + 100 kullanıcı" kısıtında
ölçülebilir yük üreten, ucuz düzeltmeli verimsizlikler. Bugün eklenen kolonlar (`resolved_silently`, `*.manual`,
`legacy_storm_id`, `status_codes`) yama + NULL semantiği açısından temiz; commit edilmemiş "ortam adı" diff'i tx/olay
sınırları bakımından doğru.

| Önem | Adet | Not |
|---|---|---|
| KRİTİK | 0 | — |
| YÜKSEK | 0 | — |
| ORTA | 4 | O-1 yürütücü etkin paralelliği · O-2 `activity_log` yazım katlaması · O-3 kapsam-süzgeçli önbelleksiz uçlar · O-4 izleme sayfalarında saniyelik sayfa-geneli yeniden çizim |
| DÜŞÜK | 12 | indeks, tavan, hizalama, bilinçli-açık maddeler |

---

## AÇIK BULGULAR

### ORTA

#### O-1 · `config/WebConfig.java:224-241` + `config/TunableThreadPoolTaskExecutor.java:10-16` + `config/ResizableCapacityQueue.java:23,43` — `certCheckExecutor` "core 20 / max 50 / kuyruk 5000" ama etkin paralellik **20**; saat başı 2000 görevlik patlama 30 sn'lik izlemeleri dakikalarca kuyrukta bekletiyor

**Kanıt.** `ResizableCapacityQueue extends LinkedBlockingQueue` (`:23`); `offer` kapasite (5000) dolmadan hiç `false`
dönmez (`:43`). `java.util.concurrent.ThreadPoolExecutor.execute` core'un (20) üstüne yalnız `offer` `false` dönünce
iş parçacığı açar → **5000 görev kuyruğa girmeden 21. iş parçacığı hiç doğmaz.** Aynı yürütücüyü kullananlar:
`CertificateCheckerService.checkAsync` (`:139-157`, `@Async("certCheckExecutor")`), tüm izleme sweep'leri
`SchedulerService.startNetworkCheck` (`:2622-2629`), uptime sweep'i (`:2749-2752`), `@Async evaluate*Now`,
`AuditGeoEnricher`, tanılama servisleri. Kuyruk FIFO.

**Neden bug (senaryo, 1000 alan adı).** Saat başı cert sweep'i 1000 görev (`runCheckForDomains:2098-2105`, `join`
`:2108`) + uptime sweep'i 1000 görev (`:2749-2757`; `fixedDelay 3600 s`, initialDelay 60 s → cert cron'uyla çakışabilir)
= 2000 görev / **20** iş parçacığı × ~3 sn (6 sn zaman aşımı, NETWORK'te bir tekrar) ≈ **5 dk doygunluk**. Bu pencerede
30/60 sn'lik HTTP/port/ping/keyword sweep'lerinin görevleri FIFO'da 2000 görevin arkasına düşer:
(a) `startNetworkCheck` `orTimeout(180 s)` **kuyruk süresini de sayar** (`:2627-2629`) → 180 sn'yi aşan bekleyiş
`TimeoutException` → izleme başına "check failed" log'u, kalem üretilmez (`:2911-2913` deseni) → o tur için DOWN
tespiti YOK; görev iptal edilmediği için yine de sonra koşup satır yazar (boşa iş + geç damgalı geçmiş satırı).
(b) Sweep iş parçacığı `get()`'te bloke → 8'lik scheduling havuzu (D-3) dolar.
(c) `checkDue` ızgarası (`:2705-2720`, `nextDueAfter` catch-up clamp) kaçan vadeleri telafi etmez → HTTP/port/ping
geçmişinde her saat başı 2–5 dk'lık boşluk, DOWN tespiti o kadar gecikir.
(d) Ayarlar → Görev Havuzu'ndaki "max" (`ExecutorTuningService.applyPoolSizes`) ve Sistem Sağlığı kartındaki
"min 20 · max 50" **yanıltıcı**: max'ı 100 yapmak hiçbir şey değiştirmez, core'u düşürmek doğrudan paralelliği düşürür.
Helm/docs'taki "20/50" da aynı yanılgıyı taşır.

**Maliyet tahmini.** Saat başı ~5 dk (1000 alan adı) / ~2,5 dk (500) izleme kör noktası; 100 eşzamanlı kullanıcı bu
pencerede "son kontrol 4 dk önce" görür. CPU/heap etkisi yok (aksine paralellik düşük) — sorun gecikme ve yanlış vaat.

**Çözüm (ucuz, iki seçenekten biri).**
1. `core = max` + `executor.setAllowCoreThreadTimeOut(true)` (boşta 60 sn sonra iş parçacığı ölür → "max" gerçek
   olur, boşta 20'ye iner). `ExecutorTuningService` ve health kartında "core/max" yerine tek "paralellik" alanı.
2. Ya da "eager" kuyruk: `offer` havuz `< max` iken `false` dönsün (ThreadPoolExecutor iş parçacığı açar), reject
   handler'da `queue.put(r)` ile tekrar kuyruğa koy (Tomcat `TaskQueue` deseni).
Ek: saat başı patlamayı yaymak için cert sweep'ini alan başına ızgaraya (`check_interval_hours` zaten var) ya da
uptime sweep'ini cert cron'undan 30 dk kaydırmak (`initialDelay`) — 2000 → 1000 görev.
**Doğrulama:** `/metrics` `executor_pool_size` / Sistem Sağlığı "havuz N/20" — yük altında 20'yi hiç aşmadığı görülmeli.

#### O-2 · `service/ActivityLogService.java:37-47` (+ 11 çağıran: `SchedulerService.java:2758, 2945, 3084, 3612, 3919, 4214, 4560, 5472, 5589`, `CertificateService.java:192`, `DomainCheckerService.java:84`) — her zamanlanmış kontrol `*_checks` satırına ek olarak `activity_log`'a da bir satır yazıyor; 365 gün saklanıyor (seri tabloları 180 gün) → tek Postgres'te en hızlı büyüyen tablo ve sweep yazma yükünün yarısı

**Kanıt.** `recordCheck` her çağrıda `repo.save(a)` (`:43`); çağrı `recheckPort/Http/Keyword/Ping/…` içinde, yani teyit
ve kurtarma yeniden ölçümleri dâhil **her ağ kontrolünde**. `RetentionCatalog.java:255-259`: `age("activity-log", …,
365, 1, true, PERSONAL, "… her kontrol +1 satır (en hızlı büyüyen seri) … DİKKAT: satır sayısı ~4 katına çıkar")`.
Satır: ~14 kolon, `result_summary ≤300`, `error_message ≤1000`, `result_detail` boş (`summarize` yalnız özet yazar);
4 indeks (`ActivityLog.java:20-25`).

**Neden bug (maliyet, 1000 izleme, ort. 60–90 sn).** ≈ **1M `activity_log` satırı/gün** (port/ping/keyword 60 sn
varsayılan, HTTP 300 sn) → 365 gün ≈ 365M satır ≈ 200 B/satır + ~100 B indeks ≈ **100+ GB**, seri tablolarının
(180 gün, ~50 GB) iki katı; içerik seri tablolarının **kopyası** (durum, süre, hata). Her kontrol = 2 INSERT + 4 ek
indeks güncellemesi. Gece batch DELETE 1M satır (100 × 10k, `RetentionService.java:183-205`) → günlük bloat +
autovacuum baskısı; tek Postgres'in disk/IO tavanı (uygulama OOM'u değil, veritabanı kesintisi riski).
Kullanıcı kararı (2026-08, 90→365 gün) bilinçli ve "ham detay" kuralı seri tablolarında zaten korunuyor —
`activity_log`'daki kopya satırlar o kuralın kapsamı değil.

**Çözüm (ürün onayı ister; pencereyi KORUR, kopyayı azaltır).** `SCHEDULED_CHECK` için yalnız *bilgi taşıyan* satırı
yaz: durum değişimi (up↔down), başarısız/hatalı, yavaş (eşik aşımı), elle kontrol, teyit/kurtarma adımı; ardışık
"ok→ok" satırını atla (`recordCheck`'e önceki durumu `lastMonitorCheckAt` benzeri küçük bir haritadan ver). Beklenen:
20–50× az satır, akışta hiçbir olay kaybolmaz (ham ölçümler seri tablolarında). Alternatif/asgari: `activity`
retention'ını serilerle hizala (365→180). Her durumda Ayarlar → Veri Saklama'da tablo boyutu izlensin (DbGrowthMetrics
zaten örnekliyor).

#### O-3 · Kapsam-süzgeçli, önbelleksiz dört uç — 100 kullanıcının dakikada ~190 çağrısı her seferinde 4–5 bin varlık hidrasyonu + 2–4 `alert_events` tam taraması

| Uç | Sunucu | Frontend kadansı | İstek başına yük |
|---|---|---|---|
| `GET /api/me/inbox` | `TodayPanelController.java:46-67` → `InboxService.build` (`:230-300`) | `inbox/useInbox.js:36` **60 s**, `InboxBell` tüm kullanıcıların nav'ında (`Nav.jsx:219`) | `inventoryRepo.findByActiveTrue…` (1000) + `findAllOpenOrderBySeverity` + `findByCreatedAtGreaterThanEqual…(30 g)` + `MonitorRefResolver.resolve` (`:50-66`: **9 izleme tablosu `findAll()`**) + `maintenanceRepo.findByActiveTrue` + `exceptionRepo.findAll` + `teamRepo.findAll` + takım başına `weeklyReportRepo` |
| `GET /api/me/today` | `TodayPanelService.build` (`:93-109, 303, 323`) | `TodayPanel.jsx:46` **120 s**, dashboard'da herkese (`App.jsx:1236`) | `teamRepo.findAll` + `latestCheckRepo.findAll` (1000) + `findAllOpenOrderBySeverity` + … |
| executive stats | `ExecutiveStatsService.build` (`:5-18, 96, 181`) | `ExecutiveSummary.jsx:41` 300 s | teams + envanter + `latestCheckRepo.findAll` + 60 g alarm ×2 |
| team stats | `AlertTeamStatsService.build` (`:13-32`) | `App.jsx:496` loadData 300 s (`getTeamStats`) | envanter + açık + 30 g alarm |

**Neden bug.** Kardeşleri (`cert-latest`, `card-extras`, `today-monitors`, `monitor-sla`, `failure-domains`) global
anlık görüntüyü Caffeine'de tutup kullanıcıya göre süzüyor; bu dördü kullanıcı başına sıfırdan yüklüyor. 100 kullanıcı:
inbox 100/dk + today 50/dk + team 20/dk + executive 20/dk ≈ **190 çağrı/dk** × ~4–5k varlık ≈ 0,5–1 GB/dk tahsis
(1,1 GiB heap'te sürekli genç-nesil GC) + ~3–8 s CPU/dk (2 çekirdeğin %3–7'si) + `alert_events` üzerinde dakikada
~400 `created_at >=` taraması (indeks yok — bkz. D-1). Sweep'lerle üst üste binince gecikme belirginleşir.

**Çözüm.** (1) Ham listeleri global anlık görüntü olarak önbelleğe al (Caffeine 30–60 s, `sync=true`, `today-monitors`
deseni): `inbox-raw` {envanter, açık alarmlar, 30 g alarmlar, izleme indeksi}, `today-raw`, `exec-raw`; süzme
`canViewTeam` ile önbellek DIŞINDA kalsın (kullanıcıya özel veri önbelleklenmez). (2) `MonitorRefResolver.resolve`
9×`findAll` yerine tek önbellekli indeks (60 s; izleme CRUD'unda evict). (3) `InboxService.history` için
`findByCreatedAtGreaterThanEqual…(60 g)` yerine `resolved_at >= :since` sorgusu (indeks `idx_ae_resolved_at` var).
(4) D-1 indeksleri.

#### O-4 · `HttpMonitorPage.jsx:159, 211, 679` (+ 8 kardeş: Port/Ping/Keyword/Dns/Page/PageSpeed/Scripted/DomainMonitorPage — hepsi aynı üç satır) ↔ `monitoring/MonitorPageHeader.jsx:41-42, 58-66, 108-112` — sayfa-geneli `secondsSince` saniyede bir `setState` → tüm izleme listesi (1000 karta kadar) her saniye yeniden çiziliyor; başlık zaten kendi sayacını taşıyor (29c RE-CHECK: AÇIK)

**Kanıt.** Her sayfada `const [secondsSince, setSecondsSince] = useState(0)` (`:159`),
`useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)` (`:211`) ve başlığa
`refreshIn={REFRESH_INTERVAL - secondsSince}` (`:679`). `MonitorPageHeader` ise "Ek 3/10, 2026-09-28" ile
`RefreshCountdown` (`:58-66`: "saniyelik state YALNIZ burada — başlık ve sayfa her saniye çizilmez") ekledi ve
`refreshEvery/refreshResetKey` prop'unu sunuyor; ama `refreshIn != null` iken o yol seçilmiyor (`:108-109`) → 9
sayfanın hiçbiri optimizasyonu kullanmıyor. `MonitorCard` (`MonitorCard.jsx:76`) `React.memo` değil;
`displayMonitors` `useMemo`'lu olsa da her tick'te 1000 kart fonksiyonu + alt ağaç yeniden koşuyor.

**Neden bug.** 1000 kart × 1 render/s = sürekli ana-iş-parçacığı yükü; mweb kuralı (390×844 telefon) altında
kaydırma takılması ve pil; sunucu etkilenmez. Kadans 60 s'lik yenilemeye eşlik eden **saf kozmetik** sayaç için ödeniyor.

**Çözüm.** 9 sayfada `secondsSince` state'i + `:211` tick'i kaldır; başlığa `refreshEvery={REFRESH_INTERVAL}
refreshResetKey={lastLoadedAt}` geç (`load` bittiğinde damga güncelle). Ek: `export const MonitorCard = memo(…)`.
Kapı: `MonitorPageHeader` testine "refreshEvery verilen sayfa 1 s'de bir render olmaz" (render sayacı) iddiası.

### DÜŞÜK

- **D-1 · `model/AlertEvent.java:8-15` + `SchedulerService.java:1088-1092`** — `alert_events`'te `team_id` ve tek başına
  `created_at` indeksi yok. Sorgular: `findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc` (`AlertEventRepository:37`;
  çağıranlar `InboxService:128/240`, `AlertTeamStatsService:32`, `ExecutiveStatsService:96/181`,
  `TodayMonitorInsightsService:507`, `AlertNoiseService:51`), `findFiltered`/`countFilteredByType`/`INCIDENTS_FILTER`
  (`:167-235, 310-330`: `createdAt` aralığı + `teamId IN :scope`). Mevcut `idx_ae_storm_scan(resolved, alert_type,
  created_at)` öncü kolonu yüzünden `created_at`-tek aralığa yaramaz → 365 günlük tabloda her çağrı seq scan.
  **Çözüm:** `patch("CREATE INDEX IF NOT EXISTS idx_ae_created_at ON alert_events(created_at)")`,
  `patch("CREATE INDEX IF NOT EXISTS idx_ae_team_id ON alert_events(team_id)")`.
- **D-2 · `SchedulerService.java:2107-2108`** (E1#7, AÇIK) — cert sweep'i `CompletableFuture::join` tavansız; kardeş
  `startNetworkCheck:2627-2629` `orTimeout(180 s)` taşıyor. Soket/OCSP/CRL zaman aşımları var
  (`CertificateCheckerService:389, 619, 705-706, 725, 756-765`; `ChainValidationService:268-269, 401-402`) ama
  `InetAddress.getAllByName` (`:657`, `NetworkResolver:37`) yalnız OS çözümleyici zaman aşımıyla sınırlı; takılan tek bir
  join `running` bayrağını (`:2074`) tutar → saatlik + stale sweep süresiz "already in progress". **Çözüm:** aynı
  `orTimeout(NETWORK_CHECK_MAX_WAIT_MS)` + `exceptionally` ile `status=error/error_class=TIMEOUT` sonucu.
- **D-3 · `application.properties:23`** `spring.task.scheduling.pool.size=8` — 14 uzun süreli `@Scheduled` (cert, uptime,
  http, port, keyword, ping, dns, page, pagespeed, scripted, domain, crawl, http-ssl, keyword-ssl) sweep iş parçacığını
  `get()`'te bloke ediyor (F1 yalnız ağ I/O'sunu havuza taşıdı, join sweep thread'inde). Hepsi çakışınca (O-1
  penceresi) heartbeat (`ExtendedHealthService:87`), `HttpMetricsService.rotate`, 4 ayar tazeleme, push outbox
  süpürmesi gecikir → Sistem Sağlığı `minutes_since > 3` yalancı alarmı. **Çözüm:** havuzu 16'ya çıkar (bellek
  maliyeti ~16 boş thread) ya da hafif görevleri ayrı `TaskScheduler` bean'ine ayır. *(doğrulanmalı: prod'da heartbeat
  boşluğu var mı — `system_heartbeat` aralık dağılımı.)*
- **D-4 · `CertificateService.java:183-192`** — `saveResult` sonuç başına `inventoryRepo.findByDomain` (`:190`, yalnız
  `teamId` için) + `latestRepo.save` + `checkRepo.save` + `activityLog` ayrı ayrı tx → saatlik sweep'te 1000 × 4 tekil
  round-trip; `processResults:278-284` aynı envanteri zaten toplu yüklüyor. **Çözüm:** `runCheckForDomains` toplu
  envanter haritasını `saveResult`'a geçir; 1000 kaydı `@Transactional` tek batch (`saveAll`, `jdbc.batch_size=50` zaten
  açık).
- **D-5 · `SchedulerService.java:2846, 5501`** — port (30 s) ve DNS (5 dk) sweep'leri her turda
  `inventoryRepo.findByActiveTrueOrderByDomainAsc()` (1000 varlık) yalnız `domain` kümesi için. **Çözüm:**
  `certificateService.domainTeamNameMap()` gibi 60–300 s önbellekli `activeDomainSet()` (envanter CRUD'unda evict
  zaten var: `AdminController` `@CacheEvict` listesi).
- **D-6 · `service/retention/RetentionCatalog.java:169-171`** — `http-metric-minute` `batched=false`: günlük ~1440 ×
  endpoint (≤500) ≈ 200k+ satır tek DELETE, `status_codes TEXT` ile satır büyüdü. **Çözüm:** `batched=true` (tablo `id`
  taşıyor).
- **D-7 · `App.jsx:496, 530`** (E6#8, AÇIK) — 5 dk `loadData` (6 uç) ve 60 s ağ durumu ham `setInterval`; gizli
  sekmede sürüyor. 100 kullanıcı × arka plan sekme → dakikada ~120 gereksiz istek. **Çözüm:** `useVisibleInterval`
  (proje hook'u; 34 yerde kullanılıyor).
- **D-8 · `MonitoringOutageService.java:1242-1245`** (29c D-c12, AÇIK) — `onReadyCloseDisabledTypeAlarms`
  `ApplicationReadyEvent`'te eşzamanlı; ayar yolu (`:1213-1225`) `settingsReconcileExecutor`'da. **Çözüm:** açılışı da
  aynı yürütücüye ver.
- **D-9 · 29c D-c3** (`SchedulerService:3994-4017`, `MonitoringOutageService:178-200`) — crawl yeniden ölçümü
  5 sayfa × 120 s ile 4'lük teyit havuzunu dakikalarca tutabilir. Bu turda yeniden okunmadı; 29c değerlendirmesi
  geçerli, **AÇIK**.
- **D-10 · `NotificationGroupService.java:229` (`existsByTeamAndName`)** (rapor 6 E3#4, AÇIK) — takım-içi ad
  benzersizliği yalnız uygulama katmanında; eşzamanlı çift POST ikiz üretir. `monitoring_groups` için kardeş çözüm
  `ux_mon_groups_team_type_lname` (`SchedulerService:1296`) var. **Çözüm:** `CREATE UNIQUE INDEX IF NOT EXISTS
  ux_ng_team_lname ON notification_groups(team_id, LOWER(name))` + `DataIntegrityViolationException` → 409.
- **D-11 · `TeamAdminService.java:87-102, 133, 254`** — `stats()`/`impact()`/`moveMonitors()` 9 izleme tablosunu
  `findAll()` ile çekip Java'da `teamId` süzüyor (admin-yalnız, yoklanmıyor). **Çözüm:** `countByTeamIdAndActiveTrue` /
  `findByTeamId` türetilmiş sorgular (`MonitorSchedule` arayüzüne eklenir).
- **D-12 · `MonitoringController.java:1284-1287` + `CertificateCheckRepository.java:70-72`** — `uptime/overview`
  30/15/7/1 günlük pencere için **dört ayrı** `GROUP BY` taraması (30 g = 1000 × 24 × 30 ≈ 720k satır); `UptimePage.jsx:104`
  60 s'de bir, önbellek yok. 10 görüntüleyici → dakikada ~40 tarama ≈ 3M satır agregasyonu. **Çözüm:** tek sorgu +
  koşullu toplam (`SUM(CASE WHEN c.checkedAt >= :c7 THEN 1 ELSE 0 END)` × 4 pencere) ve `uptime-overview-raw` 60 s
  global anlık görüntü + kullanıcı süzgeci (O-3 deseni).

---

## BASELINE RE-CHECK

| Kaynak | Madde | Durum | Kanıt |
|---|---|---|---|
| 09-23 | O1 `writeResourceBreakdown` tx | **ÇÖZÜLMÜŞ ✓** | Atomiklik `PageSpeedResourceRepository.replaceLatest` (repo proxy) — `SchedulerService:4262-4265` gerekçesi |
| 09-23 | O2 `deletePageSpeed` üç tx | **ÇÖZÜLMÜŞ ✓** | `MonitoringController` metodu `@Transactional` |
| 09-23 | O3 `deleteTemplate` | **ÇÖZÜLMÜŞ ✓** | `ScriptedTemplateController` `@Transactional` |
| 09-23 | O10 `series-domain` öksüz | **ÇÖZÜLMÜŞ ✓** | `RetentionCatalog:218 domain-checks-orphan` |
| 09-23 | O11 push backoff etkisiz | **ÇÖZÜLMÜŞ ✓** | `UserPushService:836-855 findDuePending` + `:828` periyodik süpürme |
| 09-23 | O12 `recoveryInFlight` iptalsiz | **ÇÖZÜLMÜŞ ✓** | `MonitoringOutageService:235 recoveryGeneration` + `isCurrentGeneration` |
| 09-23 | O13 SystemHealth watchdog | **ÇÖZÜLMÜŞ ✓** | `SystemHealth.jsx:288-290 fastPollRef/watchdogRef` |
| 09-23 | O14 `dueForScheduledSweep` N+1 | **ÇÖZÜLMÜŞ ✓** | `SchedulerService:2534-2547 findAllById` |
| 09-23 / R6 E3#7 | O15 haftalık rapor N+1 | **ÇÖZÜLMÜŞ ✓** | `WeeklyAvailabilityReportService:388-403 findAllById`, `:306-336` parçalı uptime |
| 09-23 | O22 `guard<5000` sessiz kırpma | **ÇÖZÜLMÜŞ ✓** | `HttpMetricsQueryService:151/180 capped`, `:497 MAX_BUCKETS`, `Range.of` 31 g kırpma |
| 09-23 | D5 `PageUsageService` yarışı | **ÇÖZÜLMÜŞ ✓** | `:94-104 computeIfPresent` |
| 09-23 | D10 `login-issue-images timeColumn` | **ÇÖZÜLMÜŞ ✓** | `RetentionCatalog:231-236` + `RetentionColumnExistsTest` |
| 09-23 | D11 standalone UNIQUE yok | **ÇÖZÜLMÜŞ ✓** | `SchedulerService:1487-1492 uniqueMonitorTarget` |
| 09-23 | D12 fırtına üye başına sorgu | **ÇÖZÜLMÜŞ ✓** | `StormService:881-923` üç önbellek |
| R6 | E3#4 NotificationGroup UNIQUE | **AÇIK** → D-10 | |
| R6 | E1#7 `join()` tavansız | **AÇIK** → D-2 | |
| R6 | E6#8 App.jsx ham poll | **AÇIK** → D-7 | |
| R5 | #28 WebhookService `@PreDestroy` | **ÇÖZÜLMÜŞ ✓** | `WebhookService:57-60` |
| R5 | #29 CallerRuns "uç hemen döner" | **AÇIK (bilinçli, DÜŞÜK)** | kuyruk 5000 + sayaç `WebConfig:236-240`; O-1 ile birlikte okunmalı |
| R5 | #33 SQL oyun alanı iç LIMIT | **ÇÖZÜLMÜŞ ✓** | `SqlPlaygroundService:346-349` dış tavan her koşulda |
| R5 | N2/N3/N4 push outbox dayanıklılığı | **ÇÖZÜLMÜŞ ✓** | `UserPushService:809-855` |
| 29c | D-c3 crawl havuzları | **AÇIK** → D-9 | |
| 29c | D-c12 açılış uzlaştırması eşzamanlı | **AÇIK** → D-8 | |
| 2026-07 sızıntı | TrWebWhois paylaşılan client, useVisibleInterval, PortChecker factory | **ÇÖZÜLMÜŞ ✓ (regresyon yok)** | `TrWebWhoisClient:215-235`, 34 `useVisibleInterval` kullanımı |
| **YENİ** | O-1, O-2, O-3, O-4, D-1, D-3, D-4, D-5, D-6, D-11, D-12 | | |

---

## DOĞRU BULUNAN (tarandı, temiz)

**Eşzamanlılık / yürütücüler.** 25 `@Scheduled`'ın tamamı `fixedDelay`/`cron`. Elle kurulan her yürütücü kapanıyor:
`AuditGeoEnricher` (2/32 + AbortPolicy, `:67-70`), `EmailNotificationService.mailRetryExecutor` (`:109-116`, retry
`MAX_PENDING_RETRIES` tavanlı `:277-286`), `IncidentNotificationService.exec` (`:58-64`), `MonitoringOutageService`
üç havuz (`:278-283`), `ScriptedCheckerService.execPool` (`:131-134`), `PageFetchCore.resourceExecutor` (`:184-187`),
`UserPushService.worker` + client (`:154-159`), `WebhookService` client. `ProcessProbe.READER_POOL` cached-daemon
(60 s'de iş parçacığı bırakır; k6 havuzu 2 + validate 2 + diag 1 ile sınırlı). Tüm `@Async`'ler nitelikli
(`certCheckExecutor` / `loginIssueMailExecutor`) — `SimpleAsyncTaskExecutor` sızıntısı yok; `@Async` proxy tuzağı
için ayrı bean deseni (`AuditGeoEnricher`, `SecurityMailDispatcher`).

**Dağıtık kilitler.** `tryAcquireSchedulerLock:2218-2246`, `MonitoringOutageService.withLock`, `StormService.tryLock:1139-1145`
üçü de `DuplicateKeyException` → false, `BadSqlGrammarException` (tablo yok) → bilinçli fail-open, diğer istisna → **tur
atlanır**.

**HttpClient yeniden kullanımı.** Per-call `newBuilder()` yok: `GeoIp`, `HttpChecker` (paylaşılan 4 + `pinnedClients`
LRU + kilit dışında `close` + `@PreDestroy`), `KeywordChecker`/`PageFetchCore` (`@PostConstruct`), `RdapDomainClient`,
`RdapDomainExpiryService`, `TrWebWhoisClient`, `UserPushService.client()` (ayar değişince yeniden kurar, eskiyi kilit
dışında kapatır), `WebhookService`. LDAP bağlamları `try-with-resources`/`finally close` (`LdapDirectoryService:95-357, 678-679`).

**Bellek tavanları.** `CaAutoPinService` (`MAX_MAP_ENTRIES 10k`, `MAX_FAILURE_ENTRIES 1k`, LRU), `ChainValidationService`
CRL Caffeine 200/1 sa + OCSP 256 KB / CRL 5 MB tavanı, `GeoIpService` 10k, `RdapDomainExpiryService` 5k, `HttpMetricsService`
sabit 1440 dakika + 500 endpoint/dk + `hist24` ≈ 230 KB, `UserService.activeSessionCache` `SESSION_MAP_MAX`,
`SchedulerService.lastMonitorCheckAt` gecelik `retainAll(liveKeys)` (`:2593-2612`), `MonitoringOutageService.downObserved`
10 dk'da bir TTL 26 sa + `stillMonitored` budaması (`:731-785`) — bugün eklenen harita **budamalı**; `inFlight` her
dalda `remove` (`:1038-1117`); `recoveryUpCount/recoveryLastCountedAt/recoveryGeneration/recoveryStartedAt` çözümde
silinir (`:268-269, 715-716`). `PageFetchCore` gövde 2 MB + açılmış 20 MB + kaynak 500/1500 tavanı; `KeywordChecker` 2 MB;
`ProcessProbe` halka tampon; `SERIES_RAW_CAP 50k` (`MonitoringController:2937`); `HttpMetricsQueryService` akış
(`fetchSize 1000`) + 31 g kırpma + `topEndpoints` 60 s önbellek + `tryLock` (bekleyen yığılmaz `:197-233`);
`ScriptedCheckerService` `manualSlots`/`permits` semaforları, `MANUAL_QUOTA` ThreadLocal `finally` ile geri alınır
(`:667-675`), sweep `get(maxWait+15)` + iptal, `runGuardedAfterQueue` `tryAcquire(timeout+margin)` → SKIPPED (kuyruk
sınırsız büyümez); k6 zaman aşımında `destroy → destroyForcibly` + okuyucu iptali (`ProcessProbe:145-180`).

**Veri katmanı.** 89 repository; `@Modifying` metotlarının tamamı `@Transactional` ya da `CALLER_MANAGED_TX`
(`RepositoryWriteTransactionGuardTest` kapısı; bugünkü `relabelEnvironment` `REQUIRES_NEW` + `@Modifying` doğru).
Bugün eklenen kolonlar: `resolved_silently BOOLEAN`, `scripted_checks/dns_records/domain_checks.manual BOOLEAN`,
`http_metric_minute.status_codes TEXT`, `alert_storms.legacy_storm_id BIGINT` — hepsi nullable + `patch()`
(`SchedulerService:561, 729-731, 1039, 1086`), okumalar `manual IS NULL OR manual = false` / `Boolean.TRUE.equals` — ddl-auto
NOT NULL tuzağı yok; `legacy_storm_id`/`resolved_silently` üzerinde süzen sorgu yok (indeks gerekmez); `manual` süzgeci
mevcut `(monitor_id, checked_at)` indeksleriyle karşılanır. 7 seri tablosunun `(monitor_id, checked_at)` bileşik
indeksleri yamada; `remember_me_tokens.token` UNIQUE; `app_settings.setting_key` UNIQUE; `alert_storms(scope_key) WHERE
resolved=false` kısmi UNIQUE; `ux_push_event_phase_user` yamada. Sınırsız `findByMonitorIdOrderByCheckedAtDesc` (7 repo)
**hiçbir üretim çağıranı yok** (yalnız test); liste uçları `findLatestPerMonitor()` toplu, geçmiş uçları sayfalı/aralıklı.
Retention: 43 politika, büyük seriler `batched=true` + `ANALYZE` + 10 dk tavan, `alert_events` yalnız `resolved=true`,
`deployment_history` BOUNDED. `EscalationService.processResults` 2 toplu sorgu (`:278-293`), `resolveVerifiedStaleCertAlerts`
toplu (`:240`), `MonitoringOutageService.handleSweepResults` sweep başına tek `findOpenByDomainIn` (`:561, 851`).

**Sweep maliyeti (1000 alan adı, saatlik tur).** cert: 1000 ağ görevi + ~4000 küçük yazma (D-4) + 2 toplu okuma;
uptime: 1000 görev + 2000 yazma; 30 s sweep'leri: tur başına 1 `findByActiveTrue` + (port/dns) envanter kümesi (D-5) +
izleme başına 2 INSERT + 1 toplu alarm okuma; öksüz temizliği 5 dk'da bir `findAllOpenOrderBySeverity` (açık alarmlar,
küçük). `checkDue` ızgarası catch-up clamp'li (kapanış sonrası patlama yok). Haftalık rapor (`WeeklyReportKpiService`)
sayfalı `findFiltered(…, 1000)` + `weekly-kpis` 300 s önbellek; `WeeklyAvailabilityReportService` parçalı (`UPTIME_CHUNK`).
`DerivedMonitorAlertRouting` açılışta yalnız açık alarmlar (`:70`). Sparkline/SLA SQL `GROUP BY` + 60/300 s önbellek.
`uptime/overview` 24 sa HTTP-OK ve son kontrol toplu (eski 144k satır yükü giderilmiş) — kalan 4× tarama D-12.

**Frontend yoklama.** 34 yerde `useVisibleInterval`; 9 izleme sayfası + Uptime 60 s, `checkRun.running` iken durur;
Inbox 60 s, Today 120 s, Executive/RecentChanges/ConfigHealth 300 s, `useSparklines` 60 s, `useSla` 300 s, `useCheckHistory`
30 s yalnız 1. sayfa + canlı aralık, `useResponseSeries`/`useHttpOverview` ≤24 sa'da 60 s. Saniyelik tick'ler durum-kapılı:
`CheckRunShell:112` (koşum sürerken), `IncidentsPage:177`/`IncidentDetailSheet:107` (süren olay), `AlertHistory:207` 10 s
(açık alarm varken). `WeeklyReportsPage` 60/45 s yalnız `dirty`/`lockHeld` iken. Oturum ping 15 s hafif
(`isSessionSuperseded` 5 s önbellek, `touchLastSeen` 60 s debounce, `PageUsageService` bellek-içi + 60 s flush).
100 kullanıcı bütçesi ≈ 9–10 istek/dk/kullanıcı ≈ 15–17 istek/s; ağır olanlar O-3'te.

**Commit edilmemiş "ortam adı" diff'i.** `DeploymentHistoryRepository.relabelEnvironment` `REQUIRES_NEW` (AFTER_COMMIT
dinleyicisinden çağrıldığı için doğru); `BuildInfoMetrics.onSettingsChanged` / `DeploymentHistoryService.onSettingsChanged`
`@TransactionalEventListener(fallbackExecution=true)`; `syncEnvironment` `synchronized` + erken çıkış (`to.equals(from)`) →
dakikalık heartbeat'te maliyet sıfır; `MultiGauge` `overwrite=true` kardinalite 1; ENV adı regex ile doğrulanıyor.
Model değişikliği yok → ddl-auto tuzağı yok.

---

## OKUNDU ✓

`.claude/commands/bug-denetle.md`, `CLAUDE.md`, 8 bellek notu, `BUG_RAPORU_2026-09-23.md` (ORTA/DÜŞÜK + kapanış),
`BUG_RAPORU_7.md`, `BUG_RAPORU_6.md`, `BUG_RAPORU_5.md` (DÜŞÜK + çözülmüş), `BUG_REGRESYON_2026-09-29c.md` (D-c1…D-c16, soru 1–4).
Backend: `application.properties` (havuz/zaman aşımı/aralık/retention anahtarları), `config/WebConfig.java` (yürütücüler),
`config/TunableThreadPoolTaskExecutor.java`, `config/ResizableCapacityQueue.java`, `config/CacheConfig.java`,
`config/AuthInterceptor.java` (DB çağrıları), `SchedulerService.java` (applySchemaPatches indeks listesi 551-1553, 1601-1760,
2072-2270, 2525-2660, 2705-2830, 2813-2970, 3090-3145, 4262-4270, 4330-4420, 5079-5098, 5481-5530 + tüm `@Scheduled`),
`MonitoringOutageService.java` (170-285, 720-830, 1195-1269 + repo çağrıları), `EscalationService.java` (234-340, 887-960,
1082-1140 + repo çağrıları), `StormService.java` (279-300, 412-450, 881-923, 1139-1145), `UserPushService.java` (drain/outbox,
1040-1075), `ScriptedCheckerService.java` (54-142, 600-700, 905-925), `ProcessProbe.java`, `PageFetchCore.java` (kaynak
tavanları), `PageSpeedCheckerService.java`, `CertificateCheckerService.java` (zaman aşımları), `ChainValidationService.java`,
`CertificateService.java` (saveResult, cache), `CertificateCardExtrasService.java`, `HttpMetricsService.java`,
`HttpMetricsQueryService.java` (120-260), `InboxService.java`, `MonitorRefResolver.java`, `TodayPanelService.java`,
`ExecutiveStatsService.java`, `AlertTeamStatsService.java`, `TeamAdminService.java`, `PermissionService.java`,
`AppSettingsService.java`, `PageUsageService.java`, `ExtendedHealthService.java`, `DbGrowthMetrics.java`,
`ActivityLogService.java`, `WeeklyAvailabilityReportService.java`, `WeeklyReportKpiService.java`, `DerivedMonitorAlertRouting.java`,
`DomainCheckerService.java`, `LdapDirectoryService.java`, `AuditGeoEnricher.java`, `IncidentNotificationService.java`,
`WebhookService.java`, `EmailNotificationService.java` (retry), `SqlPlaygroundService.java` (enforceLimit), tüm HttpClient
kurulum noktaları, `retention/RetentionCatalog.java` + `RetentionService.java` + `RetentionPolicy.java`, `repository/AlertEventRepository.java`
(tam), `AlertStormRepository`, `HttpMetricMinuteRepository`, `CertificateCheckRepository` (aggregate), `NotificationGroupRepository`,
`ScriptedCheck/DnsRecord/DomainCheck` manual sorguları, tüm repository'lerde sınırsız `OrderBy…Desc` taraması ve `@Modifying`
taraması, `model/*` `@Table/@Index` dökümü + `AlertEvent`, `ActivityLog`, `RememberMeToken`, `MonitoringController.java`
(1246-1350, 2937, liste/geçmiş sorgu deseni), `MonitorSparklineController/Service`, `TodayPanelController`, `CertificateController`
(silent/mail-failure), `git diff` (backend service/controller/repository). Frontend: `App.jsx` (420-570, 1236), `Nav.jsx`,
`hooks/useVisibleInterval.js`, `useSparklines.js`, `inbox/useInbox.js`, `history/useCheckHistory.js`, `responsechart/useResponseSeries.js`,
`admin/httpmetrics/useHttpOverview.js`, `TodayPanel.jsx`, `ExecutiveSummary.jsx`, `RecentChangesLine.jsx`, `admin/ConfigHealthCard.jsx`,
`contexts/PermissionsProvider.jsx`, 9 izleme sayfası + `UptimePage.jsx` (aralık/tick satırları), `monitoring/MonitorPageHeader.jsx`,
`monitoring/MonitorCard.jsx`, `check/CheckRunShell.jsx`, `IncidentsPage.jsx`, `incidents/IncidentDetailSheet.jsx`,
`admin/AlertHistory.jsx`, `admin/SystemHealth.jsx`, `WeeklyReportsPage.jsx`, tüm ham `setInterval` çağrıları. Helm `values.yaml`
(replica 1, dbPoolMax 25, kaynak sınırları).

---

## ÖNERİLEN DÜZELTME SIRASI (ucuz / yüksek etki önce)

**Tur 1 — kod değişikliği 1–2 satır, etkisi ölçülebilir.**
1. **O-1** `core = max` + `allowCoreThreadTimeOut(true)` (veya eager kuyruk); Sistem Sağlığı/ayar etiketini düzelt;
   uptime sweep'ini cert cron'undan kaydır. Doğrulama: yük altında `executor_pool_size` 20'yi aşmalı, saat başı
   HTTP geçmişi boşluğu kapanmalı.
2. **D-1** iki `CREATE INDEX IF NOT EXISTS` yaması. **D-6** `batched=true`. **D-2** cert join'ine `orTimeout`.
3. **D-8** açılış uzlaştırmasını yürütücüye ver. **D-7** App.jsx iki interval → `useVisibleInterval`.

**Tur 2 — önbellek deseni (mevcut kalıp kopyalanır).**
4. **O-3** `inbox-raw` / `today-raw` / `exec-raw` / `team-stats-raw` 30–60 s global anlık görüntü + kullanıcı süzgeci;
   `MonitorRefResolver` indeksi önbellekli; inbox geçmişi `resolved_at` sorgusuna. **D-12** tek koşullu-toplam sorgu +
   60 s anlık görüntü. **D-5** aktif alan adı kümesi önbelleği.
5. **O-4** 9 sayfada sayfa-geneli `secondsSince` kaldır → `refreshEvery/refreshResetKey`; `memo(MonitorCard)`;
   render-sayacı kapısı. Telefonda (390×844, 1000 kart) doğrulat — jsdom düzen/CPU kanıtı değildir.

**Tur 3 — ürün kararı gerektirenler.**
6. **O-2** `activity_log` için "yalnız bilgi taşıyan zamanlanmış kontrol" kuralı (ya da retention 365→180 hizası) —
   kullanıcıya ham-detay kuralıyla çelişmediği (seri tabloları dokunulmuyor) açıklanarak sorulmalı.
7. **D-3** scheduling havuzu 8→16 (prod heartbeat boşluğu ölçüldükten sonra). **D-4** saveResult toplu tx.
   **D-10** notification_groups kısmi UNIQUE + 409. **D-11** türetilmiş takım sorguları. **D-9** crawl yeniden ölçüm
   tavanı (29c'deki öneri).

## BİRLEŞİK ÖNEM TABLOSU

| # | Önem | Yer | Tek satır |
|---|---|---|---|
| O-1 | ORTA | `WebConfig:224-241`, `ResizableCapacityQueue:23,43` | Yürütücü kuyruk dolmadan core'u (20) aşmaz; saat başı 2000 görev 30 sn'lik izlemeleri 2–5 dk kör bırakır, "max 50" ayarı etkisiz |
| O-2 | ORTA | `ActivityLogService:37-47` + 11 çağıran, `RetentionCatalog:255` | Her kontrol 2 INSERT; `activity_log` seri kopyası 365 gün ≈ 100+ GB, günde ~1M satır silme |
| O-3 | ORTA | `InboxService`, `TodayPanelService`, `ExecutiveStatsService`, `AlertTeamStatsService` | 4 kapsam-süzgeçli uç önbelleksiz; 100 kullanıcıda dakikada ~190 × 4–5k varlık + `alert_events` seq scan |
| O-4 | ORTA | 9 izleme sayfası `:159/:211/:679`, `MonitorPageHeader:108-112` | Sayfa-geneli 1 Hz tick 1000 kartı her saniye yeniden çiziyor; başlığın kendi sayacı kullanılmıyor (29c AÇIK) |
| D-1 | DÜŞÜK | `AlertEvent.java:8-15` | `alert_events(created_at)`, `(team_id)` indeksi yok |
| D-2 | DÜŞÜK | `SchedulerService:2108` | Cert join tavansız (E1#7) |
| D-3 | DÜŞÜK | `application.properties:23` | Scheduling havuzu 8 < 14 bloklayan sweep |
| D-4 | DÜŞÜK | `CertificateService:183-192` | Sonuç başına `findByDomain` + 3 tx |
| D-5 | DÜŞÜK | `SchedulerService:2846, 5501` | Port/DNS sweep'i her turda envanteri yüklüyor |
| D-6 | DÜŞÜK | `RetentionCatalog:169` | `http_metric_minute` batch'siz DELETE |
| D-7 | DÜŞÜK | `App.jsx:496, 530` | Ham `setInterval`, gizli sekmede sürüyor (E6#8) |
| D-8 | DÜŞÜK | `MonitoringOutageService:1242` | Açılış uzlaştırması eşzamanlı (D-c12) |
| D-9 | DÜŞÜK | `SchedulerService:3994-4017` | Crawl yeniden ölçümü teyit havuzunu tutar (D-c3) |
| D-10 | DÜŞÜK | `NotificationGroupService:229` | Takım-içi ad benzersizliği DB'de yok (E3#4) |
| D-11 | DÜŞÜK | `TeamAdminService:87-102, 133, 254` | 9 × `findAll` + Java süzme (admin) |
| D-12 | DÜŞÜK | `MonitoringController:1284-1287` | `uptime/overview` 4 × 720k satır agregasyonu, önbelleksiz, 60 s yoklama |
