# BUG REGRESYON + BENZER-BUG TARAMASI — 2026-09-28c (20.90.0 öncesi)

Kapsam: `main` @ `bb0d42c8` (etiket `v20.89.0`, HEAD = etiket) üzerinde duran commit'lenmemiş diff (`git diff v20.89.0 --stat` =
124 dosya, ~+7.400/−2.330) + 76 izlenmeyen dosya (`git status --short -uall` = 173 satır). Yeni yüzeyler: HTTP istekleri /
İstek Gezgini (`/admin/system/http-metrics/overview`, `HttpMetricsAggregate`, `http_metric_minute.status_codes`, `top_endpoints`),
Veritabanı Analitiği (`DbAnalyticsService` yeni pg_* okumaları), Kullanıcı Dizini (`has_photo`, `IDENTITY_FIELDS` genişlemesi),
Alarm Geçmişi `range=active` (+ haftalık e-posta bağlantısı), İzleme Değişiklikleri silinmiş hükmü (`findDeletedAmong` =
son olay DELETE), envanter toplu işlem geçmişi, `notification_group_name`, `tls_version/cipher_suite/tls_assessment` +
`trust_status`, Uyarılar/sertifika penceresi 7/24 göstergesi (`noc_group_ids`), Genel Bakış süzgeçleri (`DashboardFilters`),
sertifika penceresi Detaylar / Kontrol Geçmişi / Envanter sekmeleri, `CertCheckHistory`'nin çekmece + Uptime'da yeniden
kullanımı, dokunma hedefleri (PaginationBar, calendar, DensityStrip, OutageTimeline, CardDensityToggle, sheet `overlayClassName`).

Yöntem: `/bug-regresyon` TAM kapsam; konteyner adımı yerine çalışma ağacı yerinde, SALT OKUNUR (Grep/Read/git) tarandı.
Kod DEĞİŞTİRİLMEDİ; Maven/vitest/Playwright/sunucu koşturulmadı. Grep eşleşmesi tek başına bulgu sayılmadı; aşağıdaki her
bulgu kaynak okunarak doğrulandı.

> **Kapsam dürüstlüğü.** Ön uç yeni modüllerin satır satır incelemesi (httpmetrics/*, dbanalytics/*, useractivity/Directory*,
> certmodal/* yeni 11 dosya, InventoryDetails*, DashboardFilters, Warnings/AttentionList) ile test zaman bombası / fixture tel
> biçimi / i18n TR-EN / CSS süpürmesi üç paralel ajana dağıtıldı; ajanlar rapor teslim edilmeden bu tur kapandı. O alanlarda
> yalnız ANA akışın okuduğu kısımlar raporlanıyor (ayrıntı: "Doğrulanamadı").
>
> **Tarama sırasında çalışma ağacı değişti:** `AdminController`, `MonitoringController`, `SchedulerService`, `EscalationService`,
> `EscalationContactScope`, `InventoryImportService`, `NocNotificationService`, `StormService`, `TeamAdminService` son 30 dk içinde
> değişti (paralel ajan). Satır numaraları okuma anınındır.

---

## (A) Baseline re-check — REGRESYON YOK

20.89.0'ın düzeltmeleri (09-28b (D)) ve 20.88.0'ın düzeltmeleri (09-28 (C)) çalışma ağacında yerinde:

| Madde | Durum | Kanıt (çalışma ağacı) |
|---|---|---|
| 28b-B2 `RequestLoggingFilter` yüzde kodlu yol | KAPALI ✓ | `config/RequestLoggingFilter.java:166-180` çözülmüş yol (`URLDecoder`, `+` koruması) |
| 28b-B4 PDF dilim genişliği | KAPALI ✓ | `report/InventoryPdfWriter.java` `drawSegmentBar` `minW=3f` + `rest = max(0, usable − fixed)` |
| 28b-B5 toplu 7/24 önbellek `finally` | KAPALI ✓ | `controller/NocController.java:220-223` |
| 28b-C1 `Claude outputs/` | KAPALI ✓ | `.gitignore:79` |
| 28b-C2 MailKit test adı | KAPALI ✓ | `mail/MailKit.java:1121` `WeeklyAvailabilityEmailTest + MailKitReportBlocksTest` |
| **28b-B1** geri yüklenen izleme "silinmiş" | **KAPANDI (bu diff)** | `repository/MonitorChangeLogRepository.java:288-316` (son olay DELETE, `(createdAt,id)` sırası, `kinds` eşlemesi); tekil ayrıntı `resource_deleted` (`MonitoringController.java:456-462`) |
| **28b-B3** haftalık e-posta devreden alarmlar | **KAPANDI (bu diff)** | `mail/WeeklyAvailabilityMail.java:617-619` `&range=active`; `moreAlarmsNote` (`:441-446`, `MailKit.esc`); `AdminController.AlertRange` + `AlertEventRepository` `activeFrom` yüklemi 3 sorguda |
| 28b-C3 olmayan `contexts/TeamDirectoryProvider.jsx` taklidi | **HÂLÂ AÇIK** (önceden var) | `test/CertificateCardExtras.test.jsx:5`, `CertificateCardExtras.regression-1.test.jsx:5`, `SmtpLogView.test.jsx:7` |
| A1 bakım kapsamı | KAPALI ✓ | `MaintenanceController.java:170, 195` `requireWindowScope` |
| A3 smtp-logs | KAPALI ✓ | `SystemController.java:81` |
| A2 olay `team_id` | KAPALI ✓ | `IncidentController.java:136, 160, 207` |
| A5 rehber | KAPALI ✓ | `MonitorNotesController.java:63` `MonitorTargetTeams` |
| A7 sorun bildirimleri | KAPALI ✓ | `LoginIssueController.java:248, 263` |
| A8 veri saklama | KAPALI ✓ | `RetentionAdminController.java:232, 243, 265` |
| A9 + B1 (UserPush) | KAPALI ✓ | `UserPushController.java:197, 249, 276, 297-321` |
| A4 confirmations | KAPALI ✓ | `MonitoringController.java:3471` |
| A11 sağlık POST | KAPALI ✓ | `CertificateController.java:710` |
| P4 / P5 | KAPALI ✓ | `UserPushDeliveryRepository.java:24, 37`; `UserPushService.java:94, 916` |
| P12 (UserPushService tarafı) | KAPALI ✓ | `UserPushService.java:592-` fırtına push'u kanal kapılarıyla |
| E2 geo öz-çağrı | KAPALI ✓ | `AuditService.java:48` `AuditGeoEnricher` |
| F4 / F6 e-posta bağlantısı | KAPALI ✓ | `EmailTemplateBuilder.java:1035` |
| B2 `resolved_by` | KAPALI ✓ | `IncidentsController.java:349, 377` |
| B3 ısınma döngüsü | KAPALI ✓ | `App.jsx:35, 495` `useNewDomainWarmup` (auth kapısının üstünde) |
| FE1 / FE2 / FE4 / CertNotesTab | KAPALI ✓ | `useCertDeepLink.js:66-71`; `AlertHistory.jsx:393-394`; `ScriptedMonitorPage.jsx:950`; `CertNotesTab.jsx:74-85` |
| BF1 / BF2 yarış | KAPALI ✓ | `UserManager.jsx:192`; `SystemHealth.jsx:123, 189-198` (`dbSeq`, `finally`'de yalnız son tur) |
| BD1 / TSV | KAPALI ✓ | `renewal/guideSteps.js:188`; `admin/sql/sqlUtils.js:2` |
| F1 / F2 / F8 (AdminController kişi bölgesi), E9 / E10 / E11 (EscalationService), P13 (StormService) | **ayrı turda** | dosyalar şu an düzenleniyor — imzalar bu turda yeniden okunmadı |

**Diff kaynaklı geri alma yok.** Arka uç diff'inin kapsam içi kısmı (SystemController, CertificateController, CertificateDto,
modeller, AlertEvent/HttpMetric/MonitorChangeLog/CertificateInventory repository'leri, CertificateService, DbAnalyticsService,
HttpMetrics*, UserActivityService, WeeklyAvailabilityMail, WeeklyOutageReportService, AdminController'ın envanter listesi /
by-domain / toplu işlem / alarm listesi + CSV bölgeleri) hunk hunk okundu. Silinen satırların hiçbiri bir düzeltme imzası değil:
`findByBucketMinuteBetween…` kaldırıldı ama çağıranı kalmadı (akış sorgusuna geçildi), `topTables()` ayrı sorgusu tablo
satırlarından türetmeye çevrildi (sözleşme alanları korunmuş), `nocMap` → `applyNoc` aynı "null kolon = kapalı" kuralıyla.

---

## (B) Yeni bulgular — 0 KRİTİK · 1 YÜKSEK · 1 ORTA · 3 DÜŞÜK

### YÜKSEK

**B1 · `controller/SystemController.java:221-238` (`maskIdentity`) + `service/UserActivityService.java:96-100, 267-294, 511-560, 590-593` — giriş IP'si / konum / kuruluş / tarayıcı ("kimlik izi") `top_sources`, `details.*` ve `anomalies.recent` üzerinden her kademeye açık gidiyor; bu diff'in "giriş IP'leri yetkisiz kullanıcıya açıktı" düzeltmesi kardeş yüzeyleri süpürmemiş**
(S1/sızıntı sınıfı + fix-sweep-sibling-surfaces; kod önceden var — 2026-09-19/20 — ama bu diff aynı veri sınıfını kapattığını iddia ediyor)

- **Bug:** `maskIdentity` yalnız ÜST DÜZEY ve `List` olan dört anahtarı süzer: `active_users`, `login_status`, `events`,
  `anomalies`. Oysa `getOverview` (a) `top_sources` → satır başına `ip`, `country`, `city`, `reverse_dns`, `org`, `users`
  (o IP'nin arkasındaki kullanıcı adları) (`UserActivityService.java:541-555`); (b) `details` bir `Map` → içindeki
  `logins` / `failed` / `anomalies` listeleri `eventRows` ile `ip`, `country`, `city`, `org`, `user_agent` taşır (`:282-293`);
  (c) `anomalies` bir `List` DEĞİL `Map`'tir (`{counts, unacked_recent, total, recent}`, `:610-613`) → `instanceof List`
  koşulu tutmaz, maske sessizce no-op; `recent[]` satırları `ip/country/city` taşır (`:590-593`). Uç (`/user-activity`)
  yalnız `system_health.read` ister (USER varsayılanında var; `SystemController.java:195-201`), `identityVisible` yalnız global
  yönetici + AUDIT.
- **Neden bug:** Sıradan bir USER ya da kapsamlı müdür (AD ADMIN, `isGlobalAdmin=false`) Sistem Sağlığı → Kullanıcı/Oturum
  bölümünü açınca "En çok giriş yapılan kaynaklar" tablosunda tüm kurumun giriş IP'lerini, ters DNS adlarını ve o IP'den
  giren kullanıcı adlarını; KPI ayrıntı pencerelerinde (`UactModals.jsx:72, 143-144`) son 24 saatin tüm giriş / başarısız
  giriş satırlarını IP + şehir + tarayıcıyla görür (`UserActivityPanel.jsx:497-504, 543`). Aynı veri `AuditController.
  userDeviceLogins`'te `requireAuditAccess` ile kapalı ve bu diff'in CHANGELOG'u "yalnız global yönetici ve AUDIT'e döner"
  diyor — iddia yanlış. `SystemControllerTest` yalnız `login_status/active_users[].last_login_ip`'i pinliyor (`:175-194`),
  kardeşleri değil.
- **Çözüm:** Maskeyi alan-adı listesiyle değil YAPI-BAĞIMSIZ yapın: yükü özyinelemeli gezip her `Map` satırından
  `IDENTITY_FIELDS` + `reverse_dns` (+ `users` listesini `top_sources`'ta) düşüren bir yardımcı (kendi satırı istisnası
  `username`/`actor` ile). En azından `top_sources`, `details.{logins,failed,anomalies}`, `anomalies.recent` yollarını ekleyin.
  Kapı testi: USER oturumunda yanıt JSON'unun HİÇBİR yerinde `"ip"`, `"user_agent"`, `"reverse_dns"`, `"last_login_ip"` anahtarı
  yok (JSON ağacını gezen tek assert — gelecekte eklenen yüzeyi de yakalar); ADMIN/AUDIT'te var.

### ORTA

**B2 · `service/DbAnalyticsService.java:113-117, 264-295` + `controller/SystemController.java:268-274` — SQL Oyun Alanı geçmişi (tam SQL önizlemesi, hata iletisi, çalıştıran kullanıcı adı) her kademeye açık; aynı verinin kendi ucu yalnız global yöneticiye ve yalnız kendi geçmişine**
(S1/sızıntı sınıfı; önceden var — 2026-09-19 "her bölüm her kademeye" kararından beri — bu diff ekranı yeniden tasarlayıp veriyi `DbDetails`/`DbDataList`'te göstermeye devam ediyor; ürün kararıyla teyit edilmeli)

- **Bug:** `/admin/system/db-analytics` yalnız `system_health.read` ister. Yanıttaki `recent_queries`, `failed`, `top_sql`/
  `slowest_sql` (pgss yokken) `sql_query_history`'den `sql` (önizleme), `error`, `username` alanlarını döndürür
  (`DbAnalyticsService.java:254-258, 271-274, 286-291`).
- **Neden bug:** `SqlPlaygroundController.history` (`:111-116`) aynı tabloyu `requireAdmin` (global) + `sql_playground.execute`
  arkasında ve yalnız çağıranın KENDİ satırlarıyla verir; denetim kaydına bile yalnız 200 karakterlik alıntı yazılır, gerekçe
  "gövde kişisel veri içerebilir" (`:103-104`). Yöneticinin `SELECT … WHERE email = '…'` gibi sorgusu, hata iletisindeki değerler
  ve yöneticinin kullanıcı adı USER kademesindeki herkese Veritabanı Analitiği → Sorgular / Başarısız sekmelerinde görünür.
  09-19 turu (`BUG_REGRESYON_2026-09-11.md` "kırk sekizinci tur") yalnız `employee_id` sızıntısını ele almıştı; SQL metni
  tartışılmamış.
- **Çözüm:** Global görüntüleyici (admin/AUDIT) değilse `recent_queries`/`failed`/`top_users` ve geçmiş kaynaklı `top_sql`/
  `slowest_sql`'de `sql`, `error`, `username` alanlarını düşürün (sayılar/süreler kalsın — KPI'lar çalışır); ya da bu listeleri
  yalnız global görüntüleyiciye döndürün. pgss'in normalize SQL'i (`$1` parametreli) ayrıca değerlendirilsin. Kapı testi: USER
  oturumunda `$.data.recent_queries[*].sql` / `.error` yok.

### DÜŞÜK

**B3 · `model/HttpMetricMinute.java:50-57` — `status_codes` kolonu için `applySchemaPatches()` satırı yok (S8 kuralı)**
- **Bug:** Yeni nullable `TEXT` kolon yalnız `ddl-auto=update`'e bırakılmış; `SchedulerService.applySchemaPatches()`'te
  `ALTER TABLE http_metric_minute ADD COLUMN status_codes TEXT` yok. CLAUDE.md (§ şema evrimi): "Don't trust ddl-auto alone".
- **Neden bug:** Nullable kolon olduğu için ddl-auto normalde ekler (not-null tuzağı YOK). Ama ALTER herhangi bir nedenle
  düşerse (kilit zaman aşımı, yetki) Hibernate uyarıyla açılışa devam eder; `saveAll` her dakika kolonsuz tabloya yazmaya
  çalışır, `flushPending` istisnayı sessizce yutar ("bu dakikayı atla") → İstek Gezgini ve `top_endpoints` KALICI olarak boş,
  hiçbir sinyal yok.
- **Çözüm:** Idempotent `patch("ALTER TABLE http_metric_minute ADD COLUMN status_codes TEXT")` ekleyin; `flushPending`'in yuttuğu
  istisnayı en azından saatte bir WARN'la loglayın.

**B4 · `service/HttpMetricsQueryService.java:149-156, 394-397` — `granularity=minute` açıkça verilirse 31 günlük aralık dakika kovasıyla toplanıyor**
- **Bug:** `resolveGranularity` açık `minute`'ü aralıktan bağımsız kabul eder; `Scan` kovaları hedef granülaritenin
  anahtarıyla tutar → 31 gün × 1440 = 44.640 `Agg` (her biri ~20 elemanlı `long[]` + sınıf dizisi), eksen 5000'de kesilse de
  toplayıcılar bellekte. Sınıfın kendi javadoc'u bunu "≈ 11 MB olurdu" diye kaçınılacak durum olarak anlatıyor.
- **Neden bug:** Arayüz bu parametreyi hiç göndermiyor (`useHttpOverview.js:39`), ama `system_health.read` taşıyan her
  oturum API'yi doğrudan çağırabilir; tek pod'da eşzamanlı birkaç istek geçici yığını belirgin şişirir. Pratik tavan
  saklama süresi (varsayılan 7 gün → ~10 bin kova) — etkisi sınırlı.
- **Çözüm:** Aralık > 24 saat ise `minute`'ü `hour`'a çevirin (ya da kova sayısını `MAX_BUCKETS`'la sınırlayıp aşımda 400);
  aynı kuralı eski `/http-metrics/series`'e de uygulayın (o uçta 31 gün kırpması da yok).

**B5 · Yayın hijyeni — kök dizinde izlenmeyen `ekran-yenile.komut.md` (311 satır, komut/süreç tanımı)**
- **Bug:** `.gitignore`'da değil; kardeşi `sre-slo-sekmesi.komut.md` depoya girmiş. İçerik kişisel/kimlik verisi değil (okunan
  başlık bir `/ekran-yenile` komut tanımı).
- **Neden:** `git add -A` ile sürüm commit'ine girer; bilinçli mi (komut `.claude/commands/`'a mı ait) karar verilmeli.
- **Çözüm:** Kalıcı olacaksa `.claude/commands/ekran-yenile.md`'ye taşıyın; değilse commit dışında tutun.

---

## Temiz sınıflar (tarandı, örnek yok — doğrulanan kapsamda)

- **S1 / müdür tuzağı (yeni/değişen uçlar):** `/http-metrics/overview` diğer HTTP metrik uçlarıyla aynı `system_health.read`
  (ürün kararı 2026-09-19); uç adları route şablonu (`HttpMetricsInterceptor` `BEST_MATCHING_PATTERN`, şablonsuz → `(unmatched)`)
  + `HttpMetricsAggregate.safeEndpoint` derinlemesine savunma (sorgu dizesi/parça atılır, sayısal/UUID → `{id}`, belirteç →
  `{token}`, `@` → `{value}`). `notification_group_name` yalnız `notification.groups/view` + grup takımı `canView` + grup aktif ve
  kaydın takımına ait iken yazılır; yabancı kayıtta ad sızmıyor; entity istek başına taze (paylaşılan önbellek nesnesi değil).
  `/history/{domain}` zarfındaki `noc_notify`/`noc_group_ids` mevcut `requireReadableDomain` kapısından sonra. Alarm listesi +
  CSV `range=active`'te kapsam / 7/24 görünürlük kuralı değişmedi (`AlertListNocCallTest.activeRangeKeepsVisibilityRules`).
  Diff'te yeni `"ADMIN".equals` / `isTeamAdmin` / rol dizesi kapısı yok. `/db-analytics`'in YENİ alanları (bağlantı durum
  dağılımı, kilit/uzun sorgu sayıları, pg_stat_database) başka oturumun SQL metnini / istemci adresini / rol adını taşımıyor.
- **Tel biçimi (arka uç tarafı):** `has_photo`, `notification_group_name` (`@Transient`, global SNAKE_CASE), `tls_version`,
  `cipher_suite`, `tls_assessment{protocol, protocol_latest, cipher}` (elle Map), `noc_group_ids`, `top_endpoints{window_hours,
  generated_at, endpoint_count, slowest, errors}`, `overview{clamped, capped, endpoints_total, endpoints_truncated, status_codes[
  {code,count}]}`, `db_stats`, `connections.states` hepsi snake_case; e2e `certMocks.js` `/history` zarfı gerçek biçimde.
- **Nullable CAST / JPQL:** `activeFrom` yalnız karşılaştırma (fonksiyon yok); 4 sorguda (`findFiltered`, `countFilteredByType`,
  `countFacets` ×2 aşırı yükleme) konumsal `null` doğru sırada — tüm `default` aşırı yüklemeler `resolvedUntil`'den sonra `null`
  geçiyor, arity çakışması yok. `findDeletedAmong(kinds, ids)` çağıran boş kümede sorgu açmıyor. Yeni pg_* sorguları parametresiz,
  her biri ayrı try/catch → `null` ("bilinmiyor"); `MATERIALIZED` CTE PG12+ (prod PG16/17). `DbAnalyticsServiceH2Test` H2 düşüşünü
  sınıyor.
- **S8 (not-null tuzağı):** `status_codes` NULL'lanabilir (B3 yalnız patch satırı eksikliği). `notificationGroupName` `@Transient`.
- **S9:** Yeni repository metotlarının hepsi okuma (`streamRange*`, `findByUgTeamIdOrderByDomainAsc`, `findDeletedAmong`);
  `@Modifying` yok. `HttpMetricsQueryService` akışı salt-okunur `TransactionTemplate` içinde ve try-with-resources ile kapanıyor.
- **S5 sayısal:** `pct()` payda 0 → `null`; `req_per_min` `max(1, minutes)`; `percentile` boş histogramda 0 / DB p95 boşta `null`;
  `error_rate_pct` `/10.0`.
- **Önbellek:** `getWarnings` artık envanterden 7/24 alanı taşıyor → `evictAllCaches` `cert-warnings`'i boşaltıyor ve envanter
  yazan tüm `@CacheEvict` listelerinde `cert-warnings` var (`CertificateServiceCacheEvictionTest` genişletildi).
- **Toplu envanter geçmişi:** yalnız gerçekten işlenen kayıt için satır; değişmeyen kayıtta yazılmıyor; `@Transactional` bulk ile
  aynı işlem; silmede `stampUpdated` tekil yolla aynı. `set-team` için `requireExistingTeam`.
- **İzleme Değişiklikleri hükmü:** DELETE sonrası satır yazan yollar (RESTORE, CREATE, UPDATE) gerçekten canlanma; `PURGE`
  `monitor_change_log`'a yazmıyor; geri doldurma `(createdAt, id)` sırasıyla doğru konumlanıyor.
- **E-posta:** `moreAlarmsNote` `MailKit.esc` ile, HTML/düz metin paritesi korunuyor; iki bağlantı (`Btn` ve not) aynı
  `alarmsUrl`'den; `range`, `from`, `to`, `team` `PAGE_STATE_PARAMS`'ta; ön uç `filtersFromUrl` yalnız `range=active`'i kabul ediyor.
- **URL ad alanı:** `range` zaten `PAGE_STATE_PARAMS`'ta; Alarm Geçmişi `tab` yerine `view` kullanmaya devam ediyor.
- **Paylaşılan bileşen geriye uyumu (okunanlar):** `CheckHistoryTab` yeni yuvalar isteğe bağlı; `renderAbove(ctx)` eski
  `{preset, range}` alanlarını taşıyor, `renderRow`'un ikinci argümanı `{index, …}` (eski çağıranlar yalnız `index` okuyor),
  `Fragment` içe aktarılmış; `PaginationBar` / `calendar` / `CardDensityToggle` yalnız `pointer-coarse:` sınıfları + isteğe bağlı
  prop; `sheet` `overlayClassName` isteğe bağlı. Ürün turu çapaları (`data-tour="dash-filters"`, `"add-domain"`) `DashboardFilters`'a
  taşınmış; e2e'de kaldırılan Genel Bakış seçicilerine (`dash-f-*`, `.sort-bar`, `.dashboard-header`) başvuru yok;
  `KNOWN_OVERFLOW` boş.
- **Async (okunanlar):** `useCertDaysSeries` / `useLastFailure` `seq` + `alive`, yükleme bayrağı yalnız son turda `finally`'de;
  `SystemHealth.loadDbAnalytics` `dbSeq`.
- **UI kuralları (otomatik süpürme):** değişen/yeni ön uç dosyalarında sol renkli şerit (`border-l-*`, `border-left`) yok; tanımsız
  görünen `var(--color-*)`'lerin hepsi ilgili `ChartContainer` `config` anahtarları (`ok/failed/avg/threshold`, `2xx…/avg/p95/p99`,
  `days/renew/fail`).
- **Üretilmiş belgeler:** kök `WHITEPAPER.md` ile `frontend/src/assets/whitepaper.md` aynı değişikliği taşıyor; TR PDF yeniden üretilmiş.
- **Zaman bombası (arka uç, okunanlar):** `AlertActiveRangeQueryTest` pencereyi `now`'a değil sabit `FROM/TO`'ya kuruyor (kayan
  pencere yok); `HttpMetricsAggregateTest` sabit damgalar yalnız sıralama için; `HttpMetricsQueryService` `setClock` kancası var.

---

## Ayrı turda taranacak (kapsam dışı — başka ajan şu an düzenliyor)

- `EscalationService.java`, `EscalationContactScope.java`, `EscalationContactRepository.java`, `StormService.java`,
  `service/noc/NocNotificationService.java`, `TeamAdminService.java`, `InventoryImportService.java`, `MonitoringController.java`,
  `AdminController.java`'nın kişi / transfer / transfer-ug / deleteTeam / moveAll / `recipients/simulate` bölgeleri,
  `components/admin/whonotified/*`, `EscalationContacts.jsx` (+ ilişkili `RecipientSimulator.jsx`, `TeamDeleteImpactModal.jsx`).
- **`SchedulerService.alarmTeamOf` (Port/DNS türev alarmlarında `team_id` damgasının kaldırılması)** — dosya tarama sırasında
  değişti (`standalone` bağlam anahtarı eklendi) ve doğruluğu EscalationService'in alan adı → envanter çözümüne bağlı. Kapsam
  içi tüketicilerde (`AlertEventRepository` `findFiltered`/`countFacets`/Olaylar kapsam yüklemleri, `IncidentsController.
  incidentTeamInScope`) damgasız olayın envanter SY/UG kolu var — bu yönden kırılma görülmedi. Yalnız
  `AlertEventRepository.renameGroupForTeamAndTypes` (`:282-286`) damgasız türev alarmı takım yeniden adlandırmasında atlar
  (kozmetik; ayrı turda teyit).
- Baseline'da F1/F2/F8, E9/E10/E11, P13 imzaları.

## Doğrulanamadı

- **Ön uç yeni modüller (delege edilen süpürme dönmedi):** `admin/httpmetrics/*` (11), `admin/dbanalytics/*` (10),
  `useractivity/Directory*`/`UserDirectoryDetail`/`directoryModel`, `certmodal/` yeni dosyaların `CertCheckHistory`/
  `useCertHistoryData` DIŞINDAKİLER (Details*, SanList, HistoryInsights/Row, DaysTrend modeli), `InventoryDetails*` +
  `inventoryDetailModel`, `DashboardFilters` iç mantığı, `AttentionList`, `CertificateModal` diff'i — yarış/bayrak, tel biçimi
  okuması (ön ucun okuduğu alan adları), sayfalama tabanı, i18n anahtar varlığı açısından satır satır okunmadı.
- **Test zaman bombası / fixture tel biçimi:** yeni ön uç testleri (17 dosya) ve değişen arka uç testlerinin çoğu taranmadı.
- **i18n:** +1.176 satırlık sözlük farkında TR/EN eşliği ve British English doğallığı yalnız Alarm Geçmişi anahtarlarında
  (`alh.range.*`, `alh.chip.active`, `alh.facet.activeRange`) doğrulandı — ikisi de var.
- Derleme / vitest / Playwright / mweb ölçümü koşturulmadı (paralel ajan kilitleri).

---

## Önerilen sıra

1. **Yayın öncesi (YÜKSEK):** B1 — `maskIdentity`'yi yapı-bağımsız yapın, `top_sources` / `details.*` / `anomalies.recent`'i kapatın;
   JSON ağacını gezen kapı testi. CHANGELOG'daki "yalnız global yönetici ve AUDIT'e döner" iddiası ancak bundan sonra doğru.
2. **Yayın öncesi ya da ürün teyidiyle (ORTA):** B2 — SQL Oyun Alanı metni/hatası/kullanıcı adı global görüntüleyici dışına gitmesin.
3. **Küçük:** B3 (patch satırı + yutulan istisnaya log), B4 (dakika granülaritesini >24 sa'te reddet/çevir), B5 (komut dosyasının yeri).
4. **Taşınan açık:** C3 (test taklidi yolu).
5. **Ayrı geçiş:** "Doğrulanamadı" listesindeki ön uç modüller + test/i18n süpürmesi; "Ayrı turda" listesi (eskalasyon işi bitince).

**Özet:** 0 REGRESYON (20.88.0 + 20.89.0 düzeltmelerinin kapsam içi imzaları yerinde; 28b-B1 ve 28b-B3 bu diff'le kapandı, C3
açık). Yeni: 0 KRİTİK · 1 YÜKSEK · 1 ORTA · 3 DÜŞÜK — YÜKSEK ve ORTA bulgu önceden var olan koddadır ama bu diff'in yeniden
tasarladığı / "kapattım" dediği yüzeylerin kardeşleridir.
