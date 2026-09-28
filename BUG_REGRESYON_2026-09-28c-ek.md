# Regresyon taraması 2026-09-28c — ek (HTTP istekleri / Veritabanı Analitiği / Kullanıcı Dizini)

Ana taramanın (BUG_REGRESYON_2026-09-28c.md) başlattığı ve tur kapandıktan sonra dönen alt incelemenin bulguları.
Salt okunur; kod değiştirilmedi. Kapsam: `admin/httpmetrics/*`, `HttpMetricsExplorer.jsx`, `health/HttpSection.jsx`,
`SystemHealth.jsx` diff, `admin/dbanalytics/*`, `DbAnalyticsPanel.jsx`, `useractivity/*` yeni/değişen dosyalar,
`api/client.js` diff + karşılık gelen backend (HttpMetricsQueryService, HttpMetricsAggregate, DbAnalyticsService,
SystemController, UserActivityService, HttpMetricsService, HttpMetricsInterceptor, HttpMetricMinuteRepository).

KRİTİK: 0 · YÜKSEK: 1 · ORTA: 2 (+1 ürün kararı notu) · DÜŞÜK: 7

1. **YÜKSEK — SystemController.maskIdentity + UserActivityService — kimlik izi maskesi payload'ın çoğunu kapsamıyor.**
   Yalnız `active_users/login_status/events/anomalies` üst düzey ve yalnız List ise gezilir. `anomalies` bir Map
   (`{counts, recent:[...]}`) → hiç maskelenmez; `top_sources` (ip, reverse_dns, org, users), `details.*` (ip, org,
   user_agent), `heatmaps[].cells` (ip, city) maskelenmez. Ön yüz bunları her kademeye çizer (UserActivityPanel
   top_sources / anomalies.recent, EventListModal details). Çözüm: özyinelemeli maske + top_sources global olmayana
   gitmez / alanları düşer + tüm payload için "IP deseni yok" kapı testi.
2. **ORTA/DÜŞÜK — useHttpOverview.js:38-43, httpMetricsModel.js:129-140, HttpEndpointDetail.jsx:38,
   HttpMetricsQueryService.java:125-139 — uç ayrıntısı kırpılmamış aralığı istiyor.** overview 31 güne kırpıp kırpılmış
   from/to döndürür ama `normOverview` bunları atar; ayrıntı `/series`'i kırpılmamış aralıkla çağırır; `series()`'te
   `Range.of` yok. Saklama > 31 gün ise kutucuk 31, grafik 90 gün; 208+ günde kova tavanı en yeni veriyi keser (`capped`
   gösterilmez). Çözüm: view.params'a sunucunun from/to'su; backend `series()` da `Range.of`. (`series` uç adına
   `safeEndpoint` uygulamıyor — bugün fark yok.)
3. **ORTA (doğrulanmalı) — HttpMetricsQueryService.java:182-193, SystemController.java:112-118 — `topEndpoints` hatayı
   önbelleğe almıyor, kilidi uzun tarama boyunca tutuyor.** İstisnada `topCache` yazılmaz; bekleyen her istek taramayı
   sırayla yeniden dener → yavaş DB + 100 kullanıcı yoklaması → Tomcat iş parçacığı birikmesi (tek pod = kesinti riski).
   Çözüm: hatayı da kısa TTL ile önbelleğe al ya da `tryLock` + bayat/null.
4. DÜŞÜK — HttpMetricsExplorer.jsx:132-135 + TimeRangePicker `applyAbs`: from > to seçilebilir; sunucunun 400 iletisi
   yerine genel hata + işe yaramaz "Tekrar dene". Çözüm: seçicide from ≤ to doğrula ya da `error.message` göster.
5. DÜŞÜK — HttpExplorerToolbar.jsx:159, DbHeader.jsx:59, client.js:86 (`timeoutMs = 0`): asılı istekte Yenile kalıcı
   devre dışı (mutlak aralık ve DB panelinde kurtaran yok). Çözüm: seq koruması var → Yenile'yi kilitleme ya da timeoutMs.
6. DÜŞÜK — UserDirectoryDetail.jsx:153/155/159: maskelenmiş IP ile "kayıt yok" aynı ("—"). Çözüm: anahtar yoksa
   `t('uact.masked')`.
7. DÜŞÜK (i18n) — DbKpiGrid.jsx:92, DbHealthCard.jsx:132, dbColumns.jsx:90/102/106: `formatPercent(99.5)` TR'de
   "%99.5" (HTTP ekranları `fmtPct` ile "%99,5"). Çözüm: yerel biçim.
8. DÜŞÜK (i18n) — HttpSparkCharts.jsx:26: `toLocaleTimeString([], …)` tarayıcı yereli (EN'de 12 saat); `dateLocale()`.
9. DÜŞÜK — HttpTrafficChart.jsx:95-102: `status_other` hacim yığınında ve ipucunda yok.
10. DÜŞÜK — UserDirectoryModal.jsx:71-78: tek `busyKey` — iki satırda eşzamanlı işlemde önce biten diğerinin
    göstergesini kaldırır.

Not (ORTA, ürün kararı — koordinatör kararı verildi: yalnız global admin + AUDIT): SystemController.java:268-274 +
DbAnalyticsService — SQL Playground ham SQL (240 kr., sabit değerlerle), kullanıcı adları, hata iletileri her kademeye.

## Ek 2 — zaman / tel biçimi / i18n / CSS / hijyen alt incelemesi (agentC)

1. YÜKSEK — yukarıdaki 1. madde ile aynı (bağımsız teyit): `top_sources` (ip, city, country, org, reverse_dns, users) ve
   `details.{logins,failed,anomalies}` (ip, country, city, org, user_agent) maskelenmiyor; yeni
   `SystemControllerTest.userActivity_masksLoginStampIpsForNonGlobalAdmin` fixture'ında bu yapılar YOK → test sınıfı
   yakalamıyor.
2. DÜŞÜK — `EscalationContactScopeTest.java:80`: `if (n.contains("TeamId")) continue;` adında "TeamId" geçen her sorguyu
   süzülmüş sayar → ileride `findByTeamIdIsNullAnd…ActiveTrue` (takımsız = global) kapıdan geçer. Çözüm: `IsNull` içerenleri
   dışla / adı `ByTeamId(In)?(And|OrderBy|$)` desenine sabitle.
3. DÜŞÜK — `EscalationContactRepository.java:13` javadoc'u var olmayan `unscopedQueries_onlyInAdminListing` testini anıyor
   (gerçek: `repository_unscopedReadsAreWhitelisted`, `source_recipientQueriesOnlyThroughScope`).
4. DÜŞÜK — `warningsNocStatus.test.jsx:36` `revocation_status: 'GOOD'` — sunucu değerleri VALID/REVOKED/UNKNOWN ("GERÇEK tel
   biçimi" yorumuna aykırı). Çözüm: `'VALID'`.
5. DÜŞÜK — `UptimePage.sslHistory.test.jsx:34/110/148`: `todayStart()` çizimden sonra testte yeniden hesaplanıyor → yerel gece
   yarısı sınırında olası flake. Çözüm: `vi.setSystemTime` ya da beklenen değeri istekten oku.
6. DÜŞÜK/hijyen — kökte izlenmeyen `ekran-yenile.komut.md` (komut tanımı, hassas içerik yok) — kökte komut olarak kaydolmaz;
   yayın commit'ine GİRMEMELİ (`git add -A` kullanma) — yeri kullanıcı kararı.
Bilgi: yeni test satırlarında `@test.com` e-postaları (mevcut gelenek, 51 eski kullanım; proje kuralı `example.com`).
Temiz: zaman/saat dilimi (çıplak now yok, sabit tarihler parametreli pencerelere karşı, `.only/.skip/@Disabled` yok), tel
biçimi, i18n (TR=EN=12910 anahtar, 583 yeni ikisinde de, yer tutucular eşit, British English), CSS (sol şerit / tanımsız var /
uydurma sınıf yok; silinen App.css sınıfları kullanılmıyor), hijyen (`.tmp.`/`zz-` yok, IdentityLeakGuard terimleri 0).

## Ek 3 — sertifika penceresi ve bağlı yüzeyler alt incelemesi (KRİTİK/YÜKSEK yok)

1. **ORTA — `inventory/inventoryDetailModel.js:159-165` `daysFromNow` yalnız tarih değerlerde bir gün eksik:** `YYYY-MM-DD`
   → UTC gece yarısı, sonra `Math.floor((d-now)/gün)`. Bugüne planlanan yenileme "1 gün önce", yarın "bugün", bitiş günü
   kırmızı "1 gün önce doldu" (`InventoryDetails.jsx:77-82`, `:273-275`); ≤30 eşiği de kayar. Test hatayı sabitlemiş
   (`inventoryDetailModel.test.js:93` 9 bekliyor, doğrusu 10). Çözüm: yalnız tarihse yerel takvim günü farkı
   (`localDayKey`). Ek (EN): `inv.det.daysAgo` / `inv.expiredAgo` 1 için "1 days ago".
2. **ORTA/DÜŞÜK — `CertificateModal.jsx:308-311` + `noc/NocStatus.jsx:98-106`:** salt okunur / düzenleme yetkisiz
   kullanıcıda "7/24 Kapsamı'nda gör" `navigateTo('noc')` sekmeyi pencerenin ARKASINDA değiştirir, pencere açık kalır. Aynı
   sınıf `OutageTimeline.jsx:85` dokunmatik listesinde (`navigateTo('alerthistory')`). "Envanterde aç" (`onLeave`) doğru
   çözmüş. Çözüm: pencere açıkken `sm:navigate` olayında `onClose()` ya da NocStatus'a pencereyi kapatan `onNavigate`.
3. DÜŞÜK — `CertificateModal.jsx:474` `CertCheckHistory` `runInHeader` varsayılan true; boş aralık metni var olmayan
   "Çalıştır"a yönlendirir (salt okunur / DomainConflictBanner). Çözüm: `runInHeader={!readOnly && !!onCheckNow}`.
4. DÜŞÜK (doğrulanmalı) — `InventoryDetails.jsx:378-380` "Envanterde aç": (a) `App.jsx:395` `params.domain` →
   `setSearch(domain)` → Genel Bakış süzülü kalır; (b) Envanter sayfasındaki DomainConflictBanner → pencere → "Envanterde
   aç" aynı sekmeye gider, `InventoryManager.jsx:171` `domain`'i yalnız mount'ta okur, `sm:tab-params` dinlemez → yalnız
   pencere kapanır. Çözüm: envantere özgü anahtar / setSearch yalnız dashboard hedefinde; InventoryManager `sm:tab-params`.
5. DÜŞÜK — `AdminController.java:1413-1434` `transfer-ug` `requireExistingTeam` yok; `transfer` + `transfer-ug` silinmiş
   (`deleted_at`) kaydı reddetmiyor → DELETE sonrası UPDATE satırı `findDeletedAmong`'u "canlı"ya çevirir (yalnız API).
   Çözüm: silinmişte 400/409; `transfer-ug`'de `newUgTeamId != null` iken `requireExistingTeam`.
6. DÜŞÜK — `CertHistoryInsights.jsx:92` + `useCertHistoryData.js:56`: "Özel" seçilip uygulanmadan `rangeParams` null →
   anahtar null → `stale` true kalır → istek yokken sonsuz dönen spinner + %60 opaklık. Çözüm:
   `stale: !!key && !!shown && shown.key !== key`.
7. DÜŞÜK (doğrulanmalı) — `CertHistoryInsights.jsx:102` ve `CheckHistoryTab.jsx:518` aynı ad ("Yeniden dene",
   `hist.retry`) — liste + seri birlikte düşerse iki aynı adlı düğme. Çözüm: eğilim düğmesine ayırt edici ad.
8. DÜŞÜK (doğrulanmalı) — `DashboardFilters.jsx:324-325` Sheet içeriği `z-[1001]`, örtü varsayılan `z-50` → z 50–1000
   öğeler (`.help-fab` 900) karartılmadan üstte. Çözüm: `overlayClassName`.
9. DÜŞÜK — `e2e/support/certMocks.js:146` `/api/history` için liste satırı dönüyor (gerçek geçmiş satırında `alert_level`,
   `tier`, `noc_notify`, `noc_group_ids` yok; `tls_assessment` var) → zayıf protokol rozeti ve `statusKey` düşüş yolu e2e'de
   sınanmıyor.
10. Performans notu — `UptimePage` saniyede bir yeniden çizilir (`secondsSince`); CertCheckHistory → CertHistoryInsights
    → recharts CertDaysTrend de her saniye (memo yok, `onJump` her çizimde yeni). İstemci CPU'su.
Temiz: Rules of Hooks, i18n (~680 statik + dinamik aileler TR+EN), tel biçimi, URL ad alanı, sayfalama, CheckHistoryTab
yuvaları geriye uyumlu (10 çağıran), async guard'lar, sayısal korumalar, UI kuralları, arka uç tutarlılığı (moveAll/impact,
toplu geçmiş, grup adı gizleme, cert-warnings), ortak UI bileşenleri yalnız ekleme.

Temiz: tel biçimi (tüm yeni alanlar snake_case, fixture'lar gerçek biçimde), async (seq/alive korumaları,
useVisibleInterval, yenilemede durum korunumu, memo'suz bağımlılık yok), Rules of Hooks, URL ad alanı (`g_` kayıtlı),
sayfalama (1-tabanlı), sıfıra bölme korumaları, i18n (563 anahtar TR/EN + yer tutucu eşit; silinen anahtar kullanılmıyor),
UI kuralları (sol şerit yok, legacy sınıf yok, token'lar tanımlı, katman sırası doğru), HTTP uç adları (safeEndpoint).
