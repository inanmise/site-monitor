# BUG REGRESYON + BENZER-BUG TARAMASI — 2026-09-28 (20.88.0 öncesi)

Kapsam: `main` @ `cb6fe53a` (etiket `v20.87.0`) ÜZERİNDE duran commit'lenmemiş diff (`git diff v20.87.0 --stat` =
155 dosya, +6.102/−1.799; ayrıca izlenmeyen yeni dosyalar). Yeni yüzeyler: Olaylar org geneli salt okunur görünüm
(IncidentsController + AlertEventRepository `INCIDENTS_FILTER`), mükerrer alan adı 409 `DOMAIN_EXISTS` (AdminController,
GlobalExceptionHandler, InventoryImportService), sorun bildirimi `impacts` + `DOMAIN_TRANSFER`, PushDecisionAccess +
`/explain` kapısı + `scenarioChannel`, AUDIT için `noc_calls.write`, `card-extras` önbellek boşaltma, e-posta `&open=cert`,
ön uç derin bağlantılar (`useMonitorDeepLink`, `useCertDeepLink`, `open` parametresi), ActionNoteDialog, yeni alan adı
ısınma döngüsü (App.jsx).

Yöntem: `/bug-regresyon` TAM kapsam; konteyner adımı yerine çalışma ağacı (`D:\site-monitor`) doğrudan Grep/Read/git ile
tarandı. Kod DEĞİŞTİRİLMEDİ; Maven/vitest/Playwright çalıştırılmadı. Grep eşleşmesi tek başına bulgu sayılmadı.

> **Kapsam dürüstlüğü.** Bu turda üç paralel imza ajanı (eski baseline re-check, arka uç tüm-kod S1–S17 süpürmesi, ön
> uç S6/S7/S11/S12 + async tuzak + tel biçimi süpürmesi) başlatıldı ama rapor teslim edilmeden DÖNMEDİ. Aşağıdaki her şey
> ANA akışın kaynak okuyarak doğruladığı yüzeylere dayanır. Tam taranamayan alanlar "Doğrulanamadı" bölümündedir.

---

## (A) Baseline re-check — REGRESYON YOK (doğrulanan kapsamda)

09-27 raporunun maddeleri v20.87.0'da kapatılmış; çalışma ağacında imzaları yerinde:

| Madde | Durum | Kanıt (çalışma ağacı) |
|---|---|---|
| BK1 (matris-param yolu auth/CSRF atlatma) | KAPALI ✓ | `config/AuthInterceptor.java:69-74` ve `config/OriginCheckFilter.java:73-78` normalize yol; ek `config/RequestPathFirewallFilter.java:55-83` `;`/`%3B`/kodlu ayraçları reddediyor |
| BO0/O12 (kurtarma kuşağı canlılık) | KAPALI ✓ | `MonitoringOutageService.java:218-237` `beginRecoveryGeneration` = `putIfAbsent`; `endRecovery` kesinti dallarında (:463, :475) |
| BO4/O1 (self-invocation tx) | KAPALI ✓ (farklı yoldan) | `SchedulerService.java:4071-4099` `@Transactional` kaldırıldı, atomiklik `PageSpeedResourceRepository.replaceLatest` içinde |
| BO5/O15 (haftalık rapor N+1) | **HÂLÂ AÇIK** | `WeeklyAvailabilityReportService.java:343-347` domain başına uptime sorgusu; `:429` domain başına `latestCheckRepo.findById` |
| BO6 (`ldap.*` GLOBAL_ONLY) | KAPALI ✓ | `AppSettingsCatalog.java` GLOBAL_ONLY listesinde üç `ldap.*` anahtarı |
| BO7 (IdentityLeakGuard harf) | KAPALI ✓ | `IdentityLeakGuardTest.java:180-183` terimler ve metin küçük harfe çevriliyor |
| BO8 (OCSP/CRL süre tavanı) | KAPALI ✓ | `ChainValidationService.java:264, :400` `HttpBodies.deadline(...)` + `disconnect` |
| BO9 (`ug_team_id` mass-assignment) | KAPALI ✓ | `AdminController.java:404, :471` `requireUgUnchangedOrCleared` (tanım :3460) |
| BF1 (UserManager yarışı) | KAPALI ✓ | `admin/UserManager.jsx:191-210` `loadSeq` |
| BF2 (DbAnalytics yarışı) | KAPALI ✓ | `admin/SystemHealth.jsx:124, :190-198` `dbSeq` |
| BF3 (MonitorNotes Escape) | KAPALI ✓ | `MonitorNotes.jsx:108-127` window capture dinleyicisi |
| BD1 (kılavuz bağlantı şeması) | KAPALI ✓ | `renewal/guideSteps.js:181-188` `safeHref(..., GUIDE_LINK_SCHEMES)` |
| TSV formül enjeksiyonu | KAPALI ✓ | `admin/sql/sqlUtils.js:121-126` `neutraliseFormula` |
| localStorage PII (palet/taslak) | KAPALI ✓ | `utils/personalStorage.js:34-46` `clearPersonalStorage`, çıkışta çağrılıyor (App.jsx:411, :606) |
| Login.jsx localStorage | KAPALI ✓ | `pages/Login.jsx:4-5` try/catch |

**Diff kaynaklı geri alma yok.** Arka uç diff'inin (1.868 satır) tamamı okundu: silinen satırların hepsi ya daha güçlü bir
sürümle değiştirildi (`existsByDomainIgnoreCase` → çakışan kaydı getiren `findFirstByDomainIgnoreCaseOrderByIdAsc`;
`ownScope` artık görüş kapsamı yoksa BOŞ liste = kapalı düşer, eski kod `null`'ı kapsamsız sorgu sayıyordu) ya da
bilinçli bir okuma genişletmesi (Olaylar `get`/`listComments` → `requireIncidentReadable`; yazma kapıları
`addComment`/`deleteComment`/`deleteIncident` değişmedi). Ön uçta okunan diff'lerde (App.jsx, api/client.js,
useUrlQuerySync, useMonitorDeepLink, IncidentsPage) `reqIdRef`/`summarySeqRef`/`finally` bayrak temizlikleri korunmuş.

09-25 / 09-23 / 09-11 / 09-10 maddeleri bu turda tek tek yeniden okunmadı (baseline ajanı dönmedi) — 09-27 turu hepsini
KAPALI doğrulamıştı; o günden beri yalnız bu diff değişti ve diff'in okunan kısmı onlara dokunmuyor. Tam re-check ayrı
geçişte yapılmalı.

---

## (B) Yeni bulgular — 0 KRİTİK · 0 YÜKSEK · 1 ORTA · 3 DÜŞÜK

### ORTA

**B1 · `UserPushController.java:203-227` (`GET /deliveries`) + `:230-267` (`GET /deliveries/export`) — kapsamlı müdür
TÜM takımların kişi bazlı push teslim satırlarını okuyabiliyor; yeni `/explain` kapısının kardeşi süpürülmemiş** (S1 +
müdür tuzağı; önceden vardı, bu diff kardeşini kapattığı için görünür oldu — ÜRÜN KARARIYLA TEYİT EDİLMELİ)

- **Bug:** İki uç yalnız `requireAdmin` (:490-497) ister; bu kapı `isGlobalAdmin || isScopedAdmin` ile müdürü geçirir.
  `deliveryRepo.search(...)` çağrısına `teamId` isteğe bağlı parametre olarak gider, müdürün yönetim kapsamıyla
  sınırlanmaz. Dönen satırlar kullanıcı adı, görünen ad, durum (`SKIPPED_USER_OPT_OUT` dâhil) ve bildirim metnini taşır.
- **Neden bug:** Bu diff aynı veri sınıfı için `/explain`'i kapattı (`:276-282`): "kapsamlı müdür yalnız YÖNETTİĞİ takımı
  sorar; eskiden requireAdmin'den geçen müdür API'den herhangi bir takımın üyelerini okuyabiliyordu". Aynı müdür
  `/deliveries?username=…` ya da CSV dışa aktarımıyla başka bir takımdaki herhangi bir kişinin push'u kapatıp
  kapatmadığını, hangi izlemelerden bildirim aldığını 10.000 satıra kadar okuyabiliyor. Tek kural (`PushDecisionAccess`)
  bir uçta uygulanıyor, kardeşinde uygulanmıyor.
- **Çözüm:** Kapsamlı müdür için `teamId`'yi `manageTeamIds` ile zorla (boşsa yönetilen takımlarla sınırla, dışındaysa
  403) ya da satırları yönetilen takımların üyeleriyle süz; aynısını `/deliveries/export` ve gerekirse `/stats`'a uygula.
  Kapı testi: müdür oturumu (`systemRole=ADMIN`, `viewTeamIds=[2]`, `manageTeamIds=[2]`) başka takımın satırını göremez.
  (2026-09-10 kararı Webhook bölümünü müdüre açmıştı; kişi satırlarının org geneli görünmesi o kararın kapsamında mı, ürün
  sahibi teyit etsin.)

### DÜŞÜK

**B2 · `IncidentsController.java:347` — başka ekibin olayında `resolved_by` dönüyor, `acknowledged_by` ise "o ekibin iç
bilgisi" diye gizleniyor** (S1/gizlilik tutarlılığı; yeni yüzey — ürün kararı)

- **Bug:** `toDto` `resolved_by`'ı (sicil ya da sistem jetonu) HER satıra koyuyor; `ownerFacts` (:374-376) ise
  `acknowledged_by/_at`'ı yalnız kendi satırda yazıyor ve gerekçesi "başka ekibin olayında kimin sahiplendiği o ekibin iç
  bilgisidir".
- **Neden bug:** Org geneli görünümde kullanıcı başka ekibin olayını kimin elle çözdüğünü (kişi kimliği) görüyor, kimin
  sahiplendiğini görmüyor; aynı sınıf bilgi için iki farklı kural var.
- **Çözüm:** `resolved_by`'ı da yalnız `access.owned()` iken yaz (sistem jetonlarını yabancı satırda göstermek isteniyorsa
  yalnız onları geçir) ya da ürün kararını javadoc'a yaz ve `ownerFacts` gerekçesini ona göre düzelt.

**B3 · `App.jsx:500-531` — yeni alan adı ısınma döngüsü oturuma bağlı değil: çıkışta durmuyor, bir sonraki kullanıcıya
taşınıyor** (S11/T4 yaşam bayrağı sınıfı; yeni kod)

- **Bug:** Döngünün tek durdurucuları deneme sayısı, `ready` ve bileşen sökülmesi. `handleLogout` (:594-617) ve
  `doAutoLogout` (:406-419) zamanlayıcıları temizlemiyor, `warmingDomains`'i sıfırlamıyor; `loadAliveRef` yalnız
  sökülmede `false` oluyor (:459-460), çıkışta değil. Ayrıca yorumdaki "en çok ~60 sn" yanlış: `DELAYS` toplamı 175,5 sn,
  yedi turun her biri tam sertifika listesi + kart ekleri istiyor.
- **Neden bug:** Alan adı ekledikten sonraki ~3 dakika içinde çıkış yapılırsa istekler oturumsuz sürer (401 → sessiz
  `null`). Aynı sekmede başka biri girerse döngü yeni oturumla devam eder ve önceki kullanıcının alan adı `warmingDomains`'te
  kalır: kart görünürse "hesaplanıyor" durumunda asılı görünür. Tek kullanıcıda bile 1.000 alan adlı kurulumda her ekleme
  7 tam liste çekimi demek.
- **Çözüm:** Etkiye `user` bağımlılığı ekle (kullanıcı değişince/çıkışta tüm zamanlayıcıları temizle ve
  `setWarmingDomains(new Set())`), yorumu gerçek süreye göre düzelt; mümkünse yalnız ilgili alan adını soran hafif bir uç
  kullan.

**B4 · Yayın hijyeni — `frontend/e2e/domain-detail.tmp.spec.js`, `frontend/e2e/domain-detail-preview.tmp.spec.js`,
`frontend/e2e/harness/domain-detail*.tmp.*`, `frontend/e2e/support/domainDetailFixtures.tmp.js`,
`frontend/playwright.domain-detail.tmp.config.js`, `frontend/playwright.domdet-gates.tmp.config.js` izlenmiyor ama
`.gitignore`'da da yok** (**devam eden alan: `domain/detail/*`**)

- **Bug:** Ana `frontend/playwright.config.js` `testDir: './e2e'` ve `testMatch`/`testIgnore` tanımlamıyor; varsayılan
  desen `*.tmp.spec.js`'i de yakalar. Geçici betikler mutlak bir Windows yoluna (`D:/site-monitor-shadcn/...`) ekran
  görüntüsü yazıyor, harness sayfasına gidiyor; tmp config yerel bir scratchpad yolunu gömülü taşıyor.
- **Neden bug:** Yayın sırasında `git add -A` bu dosyaları commit ederse CI'daki E2E işi (sürüm makrosunun yeşil
  olmasını şart koştuğu dört işten biri) bu ağır ölçüm betiklerini koşar → kırmızı ya da zaman aşımı.
- **Çözüm:** İş bitince dosyaları sil (betiklerin kendi notu da bunu söylüyor); kalıcı güvence için `.gitignore`'a
  `*.tmp.*` ve ana config'e `testIgnore: /\.tmp\.spec\.js$/`.

---

## Temiz sınıflar (tarandı, örnek yok — doğrulanan kapsamda)

- **S1 / müdür tuzağı (yeni arka uç yüzeyleri):** Olaylar org geneli okuma YALNIZ `list`/`get`/`listComments`'ta;
  `orgWideReader` ayarı + global görüntüleyici olmamayı ister, yazma kapıları ayarı hiç okumuyor. Liste sorgusu
  (`AlertEventRepository` `INCIDENTS_FILTER`) ile satır sahipliği (`ownedIds`) aynı kural (damgalı takım YA DA envanter
  SY/UG), tamamlayıcı kümede NULL `teamId` açıkça ele alınıyor. Teslim/sahip bilgileri ve `monitor_id` yalnız kendi
  satırda. `PushDecisionAccess` rol dizesine bakmıyor (`isGlobalAdmin` / `canManage` = `manageTeamIds`), SELF satırı
  kullanıcı adıyla süzülüyor; simülatör görüş kapsamını ayrıca soruyor. `DOMAIN_EXISTS` 409'u `requirePerm` +
  `requireInventoryWriter`'dan SONRA atılıyor, `existing` beyaz listeli, `can_transfer` = global admin (`isAdmin` →
  `SessionScope.isGlobalAdmin`), `can_restore` restore ucunun kapısının aynası. `incidents.visible-to-all` GLOBAL_ONLY.
- **S8:** `login_issue_reports.impacts` / `impact_other` nullable TEXT + iki açık `ADD COLUMN` yaması
  (`SchedulerService.java:964-966`, tablo adı birebir). Port/DNS `deleted_at` yaması + tek seferlik geri doldurma v20.87.0'da.
- **S9:** Yeni repository metotlarının hepsi okuma (`findFirstByDomainIgnoreCaseOrderByIdAsc`, `findByHostAndPort…`,
  `countIncidents`).
- **S10:** Yeni kodda nullable Boolean'lar `Boolean.FALSE.equals`/`Boolean.TRUE.equals` ya da null kontrolüyle.
- **S13:** `noc_calls.write` tek eylem (EDIT); AUDIT istisnası açık `AUDIT_WRITE_GRANTS` listesinde; `PolicyUpgrade`
  yalnız `updated_by='system'` satırı çeviriyor; `NocCallLogService.canWrite` kapsamlı müdürü yine dışlıyor.
- **S17:** "Yaşanan Sorunlar" satırı `Row.of` ile (kaçış + düz metin paritesi); `alertQuery` HTML ve düz metin
  bağlantılarında ortak, alan adı `URLEncoder` ile; 7/24 bağlantısı aynı kural.
- **Önbellek:** `cert-latest`'i boşaltan dokuz noktanın hepsinde artık `card-extras` da var; `CacheConfig` dinamik önbellek
  üretiyor; `CertificateCardExtrasService` gerçekten Spring `card-extras` önbelleğini kullanıyor; yeni envanter kontrolü
  (`checkSingleDomainAsync`) bitince `evictAllCaches` çağrılıyor.
- **URL ad alanı:** `open` `PAGE_STATE_PARAMS`'ta; iki kanca da tüketince adresten siliyor; `useCertDeepLink` auth
  kapısının üstünde (App.jsx:755 < :1127), seq/mounted koruması var, Pano'dan ayrılınca açtığı pencereyi kapatıyor.
- **S11 (okunan dosyalar):** IncidentsPage `reqIdRef` + `summarySeqRef` korunmuş, kapsam özet bağımlılığında;
  ActionNoteDialog meşgul bayrağı `finally`'de; satır eylemleri (`canActOn`) sunucu yazma kapılarıyla aynı.
- **Tel biçimi (okunan alanlar):** içe aktarma satırı `team_id`/`team_name` (record → snake_case) ön uçta aynı adla;
  sorun bildirimi `impactOther` (Map anahtarı, camelCase) ön uçta aynı adla; `checkAfterSave.CHECK_FIELDS` anahtarlarının
  hepsi enrich çıktılarında var.
- **Zaman bombası:** `UserPushScenarioChannelTest` sessiz saat penceresini `LocalTime.now(IST)`'den ±1 saat türetiyor;
  gece yarısı dahil sabit tarih/pencere sorunu yok.
- **Üretilmiş belgeler:** kök `WHITEPAPER*.md` kaynakla eşit (yalnız üretim başlığı farkı).

---

## Doğrulanamadı (ayrı geçiş gerekir)

- **Eski baseline tam re-check:** 09-25 (R1–R18), 09-23 (B1–B7, F1–F11, 51 madde), 09-11, 09-10 maddelerinin tek tek
  yeniden okunması; 09-27'nin DÜŞÜK kardeş listesi (e-posta `linkLine` şeması, alıcı log maskesi, CardExtras:227, status_counts,
  RDAP hop süresi, O11 backoff, a11y kardeşleri).
- **Arka uç tüm-kod S1–S17 süpürmesi** (diff dışı dosyalar) ve yeni arka uç testlerinin (`IncidentsOrgVisibilityTest`,
  `IncidentScopeQueryTest`, `LoginIssueReportRepositoryTest`, `IncidentsActionContextTest`, `PushDecisionAccessTest`,
  `CertificateServiceCacheEvictionTest`) saat dilimi / sabit tarih taraması.
- **Ön uç S6/S7/S11/S12 + async tuzak + tel biçimi:** `ui/TeamMembersModal.jsx` + `teamMembersModel.js` sayfalaması,
  `inventory/DomainConflictBanner.jsx` akışları (aktar → geri yükle), `admin/whonotified/PushDecisions.jsx`/`NocNote.jsx`,
  `noc/*`, `IssueReportModal.jsx`, dokuz izleme sayfasının `loaded` bayrağı (ilk render'da `loading=false` ise istek boş
  listede "bulunamadı" diye harcanır mı), yeni ön uç testlerinde sabit tarih.
- **Devam eden alanlar (başka ajanlar düzenliyor):** `components/certmodal/*`, `SslCheckerPanel.jsx`, `CertificateModal.jsx`,
  `components/domain/detail/*`, `DomainMonitorPage.jsx` — bu turda okunmadı; B4 dışında bulgu bu dosyalara dokunmuyor.

---

## Önerilen sıra (ilk geçiş — durum aşağıda (C)'de)

1. **Yayın öncesi (ORTA, ürün teyidiyle):** B1 — `/deliveries` + CSV'yi `PushDecisionAccess` kuralıyla hizala.
2. **Yayın öncesi hijyen:** B4 — `.tmp.` dosyalarını sil / yok say (devam eden domain-detail işi bitince).
3. **Küçük:** B3 (ısınma döngüsünü oturuma bağla), B2 (`resolved_by` kuralı).
4. **Taşınan açık:** BO5/O15 haftalık rapor N+1.
5. **Ayrı geçiş:** yukarıdaki "Doğrulanamadı" listesi (özellikle ön uç S11/S6 ve eski baseline tam re-check).

---

## (C) Tamamlayıcı geçişler (aynı gece) + DÜZELTME DURUMU

"Doğrulanamadı" listesi dört ayrı geçişle kapatıldı: **eski baseline tam re-check** (09-10/09-11/09-23/09-25/09-27 — her
düzeltme yerinde, **REGRESYON YOK**; O1 ve O12 v20.87.0'da zaten kapanmış, O15 kısmen açık), **arka uç S1 denetleyici
süpürmesi**, **arka uç S5/S9/S14/S15/S16**, **ön uç S6/S7/S11/S12 + async + tel biçimi**. Bulguların çoğu v20.87.0'dan
ESKİ koddadır (bu sürümün getirdiği hata değil). Düzeltmeler testleriyle aynı sürüme (20.88.0) girdi; her düzeltmenin
testi üretim kodu bozulunca KIRMIZIYA döndü (ısırma), geri alma yalnız değiştirilen parçayla yapıldı.

### İlk geçiş bulguları
| Kod | Önem | Konu | Durum |
|---|---|---|---|
| B1 | ORTA | UserPush `/deliveries` + CSV + `/stats` kapsamlı müdüre tüm takımlar | **DÜZELTİLDİ** (`scopedSearch`/`statsScope`, 3 test) |
| B2 | DÜŞÜK | Olaylar: başka ekip satırında `resolved_by` kişi adı | **DÜZELTİLDİ** (`resolvedByFor`; sistem jetonu kalır) |
| B3 | DÜŞÜK | Yeni alan adı ısınma döngüsü oturuma bağlı değil | **DÜZELTİLDİ** (`hooks/useNewDomainWarmup.js`, 4 test) |
| B4 | DÜŞÜK | `.tmp.` Playwright iskeleleri commit riski | **DÜZELTİLDİ** (`.gitignore`: `frontend/**/*.tmp.*`, `frontend/zz-*/`) |

### Arka uç S1 (yetki / takım yalıtımı)
| Kod | Önem | Konu | Durum |
|---|---|---|---|
| A1 | YÜKSEK | Bakım penceresi: takım yöneticisi `allMonitors` ile tüm kurumu susturabiliyor | **DÜZELTİLDİ** (yalnız global; hedef sahipliği `AlertKeyOwnershipService`; süre tavanı 7/30 gün) |
| F1 | YÜKSEK | Kapsamlı müdür eskalasyon kişisi → alfabetik ilk takıma | **DÜZELTİLDİ** |
| A3 | ORTA-YÜKSEK | `/api/admin/system/smtp-logs` tüm takımların posta günlüğü | **DÜZELTİLDİ** (yalnız global görüntüleyici + NOC maskesi) |
| A2 | ORTA | Olay `team_id` doğrulanmıyor | **DÜZELTİLDİ** (`requireTransferTarget`) |
| F2 | ORTA | `/contacts/webhook-status` sızıntısı | **DÜZELTİLDİ** |
| F3 | ORTA | Envanter restore/transfer/team-move/renameGroup önbellek boşaltmıyor | **DÜZELTİLDİ** (+ `domain-team-names`; kapı genişletildi) |
| A5 | ORTA | Rehber (runbook) takımlar arası üzerine yazılabiliyor | **DÜZELTİLDİ** (`MonitorTargetTeams`; rehber hâlâ hedef başına tek — şema) |
| A7 | ORTA | Sorun Bildirimleri yönetimi kapsamlı müdüre açık | **DÜZELTİLDİ** |
| A8 | ORTA | Veri Saklama kapsamlı müdüre yazılabilir | **DÜZELTİLDİ** (GLOBAL_ONLY + `requireWrite`; ekran salt okunur) |
| A9 | ORTA | UserPush `/scopes` + `/test` | **DÜZELTİLDİ** |
| A4 | DÜŞÜK-ORTA | `/monitoring/confirmations` tüm takımlar | **DÜZELTİLDİ** |
| F4 | ORTA | HTTP uyarı e-postası panoya + `open=cert` | **DÜZELTİLDİ** (`?tab=http&monitor=<id>`; `open=cert` yalnız `CERT_ALERT_TYPES`) |
| F5 | DÜŞÜK-ORTA | = B2 | **DÜZELTİLDİ** |
| F6 | DÜŞÜK | Düz metin e-posta bağlantısı ham taban; çözüm metninde bağlantı yok | **DÜZELTİLDİ** |
| F8 | DÜŞÜK | Webhook testi görüntüleme kapsamıyla | **DÜZELTİLDİ** |
| A11 | DÜŞÜK | Sertifika sağlığı POST uçları `canView` | **DÜZELTİLDİ** (`canOperateTeam`; onay `inventory.crud/edit`) |
| A6 | ORTA | Hedef-anahtarlı alarmlar takımlar arası kapatılabilir / yeniden anahtarlanabilir | **AÇIK** — yapısal (alarm anahtarına takım girmeli); sonraki sürüm |
| A10 | DÜŞÜK-ORTA | Türetilmiş DNS/Port okuma yollarında eski `team_id` | **AÇIK** — sonraki sürüm |
| F7 | DÜŞÜK | Sayfa düzeyi `can_act` (yalnız UI, veri sızıntısı yok) | **AÇIK** |

### Arka uç S5/S9/S14/S15/S16
| Kod | Önem | Konu | Durum |
|---|---|---|---|
| P4 | YÜKSEK | Push outbox yeniden denemesi backoff'u yok sayıyor | **DÜZELTİLDİ** (`next_attempt_at`, boş olabilir kolon) |
| P12 | YÜKSEK | Fırtına push'u kanal kapılarını atlıyor (+ UG'ye gidiyor) | **DÜZELTİLDİ** |
| P13 | YÜKSEK | Fırtına çözüm push'u seviye / tavan / günlük dedupe asimetrisi | **DÜZELTİLDİ** |
| P5 | ORTA | Uzun `notificationId` → zehirli satır dakikada bir yeniden gönderim | **DÜZELTİLDİ** |
| E10 | YÜKSEK | Sertifika süpürmesi canlı envanter takımı (event takımı yerine) | **DÜZELTİLDİ** (damgalı takım; catch-up dâhil) |
| E11 | ORTA-YÜKSEK | DAILY_REALERT düşük seviyeden alıcı | **DÜZELTİLDİ** (doğrulanmamış tur ertelenir; alıcı seviyesi olayın altına inmez) |
| E9 | ORTA-YÜKSEK | Yarım kalan INITIAL → ~24 sa sessiz | **DÜZELTİLDİ** (5 dk tolerans, çift gönderim yok) |
| E2 | ORTA-YÜKSEK | `enrichGeoAsync` öz-çağrı → giriş iş parçacığında ağ + ters DNS | **DÜZELTİLDİ** (`AuditGeoEnricher` ayrı bean; PTR 1,5 sn) |
| S16 | YÜKSEK | SQL Playground salt okunur kip etkisiz + `U&"…"` / `setval` / `lo_*` süzgeç açıkları | **AÇIK** — yalnız global yönetici; bilinen ops maddesi (salt okunur DB rolü) |
| P6 | YÜKSEK | 421 e-posta yeniden deneme kuyruğu bellekte; `QUEUED_RETRY` uzlaştırılmıyor | **AÇIK** — kalıcı posta kuyruğu tasarımı |
| P7 | YÜKSEK | NOC teslim durumu geri yazılmıyor | **AÇIK** |
| P8 | ORTA | NOC SENDING/FAILED satırları yalnız sonraki tetikte | **AÇIK** |
| S9#3 | DÜŞÜK | `evaluateScriptedNow` öz-çağrı (yalnız gecikme) | **AÇIK** |
| S5 | DÜŞÜK | `/users/search` `page` kıskaçsız (400) | **AÇIK** |

### Ön uç
| Kod | Önem | Konu | Durum |
|---|---|---|---|
| FE1 | DÜŞÜK | `useCertDeepLink` sahiplik bayrağı elle açılan formu kapatıyor | **DÜZELTİLDİ** |
| FE2 | DÜŞÜK | Alarm Geçmişi toplu işlem gösterilmeyen kimlikleri gönderiyor | **DÜZELTİLDİ** |
| FE3 | DÜŞÜK | Olaylar hatalı yüklemeden sonra istemci süzgeçleri donuyor | **DÜZELTİLDİ** |
| FE4 | DÜŞÜK | Sentetik 429 bekleme dalı hiç çalışmıyor | **DÜZELTİLDİ** |
| — | DÜŞÜK | `CertNotesTab` bayat yanıt `loading`'i kapatıyor | **DÜZELTİLDİ** (sıra koruması) |


**Özet:** 0 REGRESYON. Düzeltilen: 4 YÜKSEK (A1, F1, P4, P12/P13 grubu, E10) dâhil 33 bulgu, hepsi testli ve ısırma doğrulamalı.
Açık kalan (tasarım / ops, sonraki sürüm): S16, P6, P7, P8, A6, A10, F7, S9#3, S5, O15 (kısmi), R18 (tarayıcı doğrulaması).
