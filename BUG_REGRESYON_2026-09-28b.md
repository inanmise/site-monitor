# BUG REGRESYON + BENZER-BUG TARAMASI — 2026-09-28b (20.89.0 öncesi)

Kapsam: `main` @ `8273ecaa` (etiket `v20.88.0`) üzerinde duran commit'lenmemiş diff (`git diff v20.88.0 --stat` = 98 dosya,
+6.966/−3.691) ve 60 izlenmeyen dosya. Yeni yüzeyler: takım üyeleri penceresinde sistem rolü + fotoğraf
(`TeamDirectoryController` `system_role`/`has_photo`), parola autocomplete kapısı + Ayarlar menü araması, Olay & Hata Geçmişi
yeniden tasarımı (`components/incidenthistory/*`) + `IncidentService` MTTR, İstatistikler yeniden tasarımı (`components/stats/*`,
`ExecutiveSummary`), süre grafiği yeniden tasarımı (`components/responsechart/*`), her kartta 7/24 durumu (`noc/NocStatus`,
`useNocState`, `nocStatusModel`, `/noc/groups/options` ekleri, `CertificateDto.noc_notify`, 7/24 aç/kapada sertifika önbelleği
boşaltma), Haftalık Erişilebilirlik e-postası (`WeeklyAvailabilityMail`, MailKit/MailDoc blokları, `darkCanvas`, toplu uptime
okuma), aylık envanter raporu (`CertInventoryMail`, `CertInventorySummary`, PDF özet), Giriş Anomalisi ayarları yeniden tasarımı,
Anahtar Çözümleme + `RequestLoggingFilter` gövde kapatma, İzleme Değişiklikleri sayfası (`/changes/summary`, `/changes/recent`
yeni parametreler, yeni repository sorguları), yeni URL önekleri `ih_`, `st_`, `ch_`.

> **Tarama sırasında çalışma ağacı değişti:** 11:28'de dört dosya eşzamanlı güncellendi (`MonitorChangeLogRepository`,
> `MonitoringController`, `MonitoringControllerTest`, `HistoryQueryGrammarTest`): `deletedAmong` → `findDeletedAmong` yeniden
> adlandırması (eski ad `delete…` önekiyle `RepositoryWriteTransactionGuardTest`'e yazma metodu gibi görünürdü). Diff yeniden
> alınıp karşılaştırıldı: başka bir fark yok (98 dosya, satır toplamları aynı; servis/e-posta diff'leri birebir). Rapor güncel
> ağaca göredir.

Yöntem: `/bug-regresyon` TAM kapsam; konteyner yerine çalışma ağacı (`D:\site-monitor`) Grep/Read/git ile, TEK akışta (alt ajan
yok) tarandı. Kod DEĞİŞTİRİLMEDİ; Maven/vitest/Playwright çalıştırılmadı (paralel `mvn clean verify` sürüyordu). Yalnız
salt-okur yardımcı betikler (scratchpad'de, Node): ön uç göreli import çözümü, Java `com.sitemonitor.*` sınıf başvuruları,
i18n anahtar varlığı. Grep eşleşmesi tek başına bulgu sayılmadı; her bulgu kaynak okunarak doğrulandı.

---

## (A) 20.88.0 düzeltmeleri — REGRESYON YOK

Diff'in dokunduğu ortak dosyalar hunk hunk okundu: `MonitoringController` (yalnız `/changes/recent` bölgesi + yeni
`/changes/summary`), `CertificateService` (+4 satır `nocMap`), `EmailNotificationService` (yalnız haftalık/aylık rapor
kurucuları), `WeeklyAvailabilityReportService` (toplu okuma), `App.jsx` (2 hunk: arama genişliği, Stats `loading`),
`api/client.js` (yalnız `getChangeSummary`), `useUrlQuerySync.js` (yalnız önek listesi), `UserManager`/`UserPushSettings`
(yalnız `autoComplete`). Silinen satırların hiçbiri bir düzeltme imzası değil. `UserPushController`, `AppSettings*`,
`EscalationService`, `EmailTemplateBuilder`, `MaintenanceController`, `IncidentsController`, `AlertHistory.jsx`,
`IncidentsPage.jsx`, `useCertDeepLink.js` bu diff'te HİÇ değişmedi.

| Madde | Durum | Kanıt (çalışma ağacı) |
|---|---|---|
| A1 bakım kapsamı | KAPALI ✓ | `MaintenanceController.java:390-418` `requireWindowScope` (allMonitors yalnız global, hedef sahipliği `targetOwnership.ownerTeams`, süre tavanı) |
| F1 kişiler (müdür eskalasyon kişisi) | KAPALI ✓ | `AdminController.java:1711` global olmayan yazar kuralı |
| A3 smtp-logs | KAPALI ✓ | `SystemController.java:71-82` `isGlobalViewer` değilse 403 |
| A2 olay `team_id` | KAPALI ✓ | `IncidentController.java:136, 157-160, 207, 314` `requireTransferTarget` |
| F2 webhook-status | KAPALI ✓ | `AdminController.java:1668-1674` görünür kişi süzgeci |
| F8 webhook testi | KAPALI ✓ | `AdminController.java:1623` yönetim kapsamı |
| F3 önbellek boşaltma | KAPALI ✓ | `AdminController.java:1176-1178` (restore), `:1304-1305` (SY/UG), `:2682-2684` (moveAll); `MonitoringController.java:1092-1093` (renameGroup); `CertificateService.java:273-286` (`card-extras` + `domain-team-names`) |
| A5 rehber | KAPALI ✓ | `MonitorNotesController.java:219` + `MonitorTargetTeams` |
| A7 sorun bildirimleri | KAPALI ✓ | `LoginIssueController.java:248, 263` `requireNotScopedAdmin` |
| A8 veri saklama | KAPALI ✓ | `RetentionAdminController.java:516-517`; `AppSettingsCatalog.java:438, 467-483`; zorlama `AppSettingsService.java:184-194` |
| A9 `/scopes` + `/test` | KAPALI ✓ | `UserPushController.java:146-150`, `:219-231`, `:247-251` |
| A4 confirmations | KAPALI ✓ | `MonitoringController.java:3436-3442` |
| A11 sağlık POST | KAPALI ✓ | `CertificateController.java:639-646` |
| P4 backoff | KAPALI ✓ | `UserPushDeliveryRepository.java:24, 37`; `UserPushDelivery.java:110`; `UserPushService.java:824` |
| P5 zehirli satır | KAPALI ✓ | `UserPushService.java:94` (`MAX_NOTIFICATION_ID = 60`), `:874` |
| P12 / P13 fırtına push | KAPALI ✓ | `UserPushService.java:592-599`; `StormService.java:544` |
| E2 geo öz-çağrı | KAPALI ✓ | `AuditService.java:48, 317, 345` → `AuditGeoEnricher` |
| E9 yarım INITIAL | KAPALI ✓ | `EscalationService.java:2797` |
| E10 damgalı takım | KAPALI ✓ | `EscalationService.java:315, 724` |
| E11 DAILY_REALERT | KAPALI ✓ | `EscalationService.java:352, 367` |
| B1 `/deliveries` + CSV + `/stats` | KAPALI ✓ | `UserPushController.java:276, 297, 319, 335, 399` (`scopedSearch` / `statsScope`) |
| B2 `resolved_by` | KAPALI ✓ | `IncidentsController.java:349, 377` (`resolvedByFor`) |
| B3 ısınma döngüsü oturumu | KAPALI ✓ | `App.jsx:508` `useNewDomainWarmup(Boolean(user), …)`; `hooks/useNewDomainWarmup.js:30-65` (active=false → temizlik, `isAlive`, nesil) |
| B4 `.tmp.` iskeleleri | KAPALI ✓ | `.gitignore:75-76` (`frontend/**/*.tmp.*`, `frontend/zz-*/`); diskte eşleşen dosya yok |
| F4 HTTP uyarı bağlantısı | KAPALI ✓ | `EmailTemplateBuilder.java:1035` (`open=cert` yalnız `CERT_ALERT_TYPES`) |
| F5 (= B2) | KAPALI ✓ | yukarıdaki B2 |
| F6 düz metin bağlantısı | KAPALI ✓ | `EmailTemplateBuilder.java:499` |
| FE1 `useCertDeepLink` sahiplik | KAPALI ✓ | `hooks/useCertDeepLink.js:66-104` (`owned.seen`) |
| FE2 toplu işlem kimlikleri | KAPALI ✓ | `admin/AlertHistory.jsx:392-393` (`shownIds`) |
| FE3 Olaylar hatalı yükleme | KAPALI ✓ | `IncidentsPage.jsx:123, 196-198` |
| FE4 sentetik 429 | KAPALI ✓ | `ScriptedMonitorPage.jsx:950` |
| CertNotesTab sıra koruması | KAPALI ✓ | `certmodal/CertNotesTab.jsx:70` |
| **O15 / BO5 haftalık rapor N+1** (09-28'de "kısmi açık") | **KAPANDI (bu diff)** | `WeeklyAvailabilityReportService.java:290` `UPTIME_CHUNK=50`, `:301` `rowsFor` (parça başına tek sorgu), `:327` `previousWeekAvg` (sayım sorgusu), `:398` `latestByDomain` (tek `findAllById`); `:361-365` yalnız tekil kurtarma e-postası |

Yeniden yazılan dosyalara taşınan eski düzeltmeler de yerinde: İzleme Değişiklikleri yarış (`listSeq`/`sumSeq`) + kararlı
özel aralık (`MonitorChangesConsole.jsx:240-246`, 09-27 B1); süre grafiği `ts` koruması + ham `Object[]` eleme
(`responsechart/responseChartModel.js:138`); Olay & Hata Geçmişi `loadSeq`/`trendsSeq`/`trendDailySeq`, `slaBreached`/`open`
bağımlılıkta, seçim kutusu adı (R5) (`incidenthistory/IncidentHistoryPage.jsx:93-96, 111-180`; `HistoryList.jsx:73`).

---

## (B) Yeni yüzey bulguları — 0 KRİTİK · 0 YÜKSEK · 0 ORTA · 5 DÜŞÜK

**B1 · DÜŞÜK · `repository/MonitorChangeLogRepository.java:293-297` (`findDeletedAmong`) + `controller/MonitoringController.java:669-673, 764-776, 840-845` — geri yüklenen envanter kaydı İzleme Değişiklikleri'nde "silinmiş" görünüyor**
(S8/tutarlılık sınıfı; yeni kod)
- **Bug:** `findDeletedAmong` bir kaynağın geçmişinde HERHANGİ bir `DELETE` olayı varsa onu silinmiş sayıyor. Envanter yumuşak
  silinip (`AdminController.java:997-998` → `DELETE`) sonra geri yüklenince (`AdminController.java:1193-1194` → `RESTORE`,
  aynı kimlik) kayıt yaşıyor ama `resource_deleted` / `top_resources[].deleted` yine `true`.
- **Neden bug:** Kullanıcı bir alan adını çöp kutusundan geri yüklüyor; Değişiklikler zaman çizelgesindeki tüm satırlarında
  (`ChangeTimeline.jsx:39, 60`), "En çok değişen izlemeler" kartında (`ChangeKpis.jsx:185`) "silinmiş" rozeti çıkıyor ve CSV
  dışa aktarımında "Silinmiş: Evet" yazıyor (`changeModel.js:337`) — denetim çıktısı canlı bir kaydı silinmiş gösteriyor.
  (b) Aynı sınıfın kardeşi: `ch_id` derin bağlantısıyla açılan ayrıntı tekil uçtan (`/changes/{kind}/{id}/{seq}`) gelir ve
  `resource_deleted` taşımaz; olay DELETE değilse `linkFor` (`changeModel.js:66-75`) silinmiş izlemeye ölü bağlantı çizer.
- **Çözüm:** "Silinmiş" = kaynağın EN SON yaşam döngüsü olayı `DELETE` olsun (ör. `(kind,id)` başına `MAX(id)` satırının
  `eventType`'ı; ya da `RESTORE` sonrası gelen `DELETE` yoksa silinmiş değil). Tekil ayrıntı yanıtına da aynı
  `resource_deleted` alanını ekleyin. Kapı: sil → geri yükle → `resource_deleted=false` testi.

**B2 · DÜŞÜK · `config/RequestLoggingFilter.java:162-166` — "gövde hiç loglanmaz" kuralı yüzde-kodlu yolla atlanabiliyor**
(savunma derinliği; yeni kod)
- **Bug:** `bodyNeverLogged` HAM `getRequestURI()`'yi (`;` ve çift `/` temizlenmiş, küçük harf) `/api/admin/secret-tools/`
  önekiyle karşılaştırıyor; yüzde kodlamayı çözmüyor. `RequestPathFirewallFilter.java:70-89` yalnız kontrol karakteri ve
  kodlu `; / \ .`'yı reddediyor — `%2D` (`-`) gibi ayrılmamış karakter kodlaması geçer, Spring MVC ise eşleştirmeyi çözülmüş
  yol bölütüyle yapar.
- **Neden bug:** `POST /api/admin/secret%2Dtools/decrypt` aynı uca yönlenir ama önek eşleşmez; TRACE açıkken istek
  gövdesindeki aday anahtar ve yanıttaki ÇÖZÜLMÜŞ parolalar log'a yazılır. Yorum ve ekrandaki "loglanmaz" sözü "atlatamasın"
  diyor. Pratik risk düşük (çağıran zaten yönetici, TRACE nadiren açık, istemci bu biçimi üretmiyor); kaynak okumasıyla
  çıkarıldı, istekle denenmedi.
- **Çözüm:** Karşılaştırmayı çözülmüş yolla yapın (`UrlPathHelper.getPathWithinApplication` / `ServletRequestPathUtils`
  ya da `URLDecoder` + normalize), ya da kararı denetleyiciye bağlayın (uç bir istek özniteliği işaretler, filtre onu okur).
  Test: `%2D`'li yol için `BODY_OMITTED`.

**B3 · DÜŞÜK · `service/mail/WeeklyAvailabilityMail.java:170, 420-426, 595-598` — "Haftanın alarmları" sayısı devreden alarmları içeriyor, bağlantı içermiyor**
(e-posta içerik tutarlılığı; yeni kod)
- **Bug:** `Alarms.total` önceki haftadan devredenleri de sayar (`:71-75` javadoc). "+N alarm daha — tamamı ekteki PDF'te ve
  Alarm Geçmişi'nde" notu ve "Haftanın alarmlarını aç" düğmesi `?tab=alerthistory&view=all&from=<Pzt>&to=<Paz>` açıyor; Alarm
  Geçmişi "tümü" görünümünde tarih süzgeci AÇILIŞ anına uygulanır (`alerts/alertHistoryModel.js:70-72`).
- **Neden bug:** Pazartesiden önce açılıp hafta boyunca açık kalan alarmlar e-postada sayılıyor ama bağlantının açtığı
  listede yok; "tamamı Alarm Geçmişi'nde" iddiası o alarmlar için yanlış, alıcı eksik liste görüp sayıların tutmadığını düşünür.
- **Çözüm:** Notu "bu hafta açılanlar Alarm Geçmişi'nde, devredenler dahil tamamı ekteki PDF'te" diye düzeltin ya da
  devreden varsa ikinci bir bağlantı (`view=open` / açık kalanlar) verin.

**B4 · DÜŞÜK · `service/report/InventoryPdfWriter.java:264-283` (`drawSegmentBar`) — PDF özetindeki dağılım çubuğunda son dilim negatif genişliğe düşebiliyor**
(S5 sayısal sınır sınıfı; yeni kod)
- **Bug:** Küçük dilimler en az 3 pt'ye şişiriliyor (`Math.max(3f, …)`) ama şişme telafi edilmiyor; son dilim "kalan"
  (`MARGIN + CONTENT_W - x`) alındığı için negatife inebiliyor. E-postadaki kardeş (`MailKit.segmentWidths`) toplamı 100'e
  normalize ediyor; PDF etmiyor.
- **Neden bug:** A4'te (CONTENT_W ≈ 515 pt) 0–7 gün: 1, 8–30 gün: 1, 90 gün üstü: 400, tarih yok: 1 → son dilim ≈ −2,2 pt:
  "Tarih yok" dilimi görünmez / yeşilin üstüne sola doğru çizilir; lejant onu yine listeler. Gerçekçi bir envanter dağılımı.
- **Çözüm:** `MailKit.segmentWidths` ile aynı yöntemi kullanın (tam sayı yüzde, toplam 100, en az pay, taşmayı en geniş
  dilimden düş) ve pt'ye çevirin; ya da son dilimi de `max(3f, …)` yapıp taşmayı en geniş dilimden kesin.

**B5 · DÜŞÜK · `controller/NocController.java:198-219` (`bulk`) — kısmi hatada sertifika önbelleği boşaltılmıyor**
(“hata yolu taşınmadı” sınıfı; yeni kod)
- **Bug:** SSL satırları döngü içinde tek tek kaydedilir (`persist`), önbellek boşaltma döngüden SONRA tek kez yapılır
  (`:219`). Döngü ortasında bir `persist` istisna fırlatırsa (DB hatası) önceki SSL kayıtları yazılmış olur ama `evictAllCaches`
  hiç çağrılmaz; yöntem `@Transactional` değil.
- **Neden bug:** Toplu 7/24 açma/kapama kısmen başarısız olursa Genel Bakış sertifika kartları `cert-latest` TTL'i (300 sn)
  boyunca eski 7/24 durumunu gösterir — bu diff'in kapatmak istediği tam durum.
- **Çözüm:** `if (sslChanged) certService.evictAllCaches();` çağrısını `try { … } finally { … }` içine alın.

---

## (C) İzlenmeyen dosya bütünlüğü — 60 izlenmeyen dosya, KIRIK İMPORT YOK · 3 DÜŞÜK

Liste (`git status --porcelain -uall`): 3 `.pptx` (`Claude outputs/`), 3 yeni arka uç üretim sınıfı (`CertInventoryMail`,
`WeeklyAvailabilityMail`, `CertInventorySummary`), 9 arka uç test/örnek dosyası, 37 ön uç bileşen/model dosyası
(`admin/loginanomaly/*` 4, `admin/monitorchanges/*` 5, `incidenthistory/*` 10, `noc/*` 3, `responsechart/*` 7, `stats/*` 6),
10 ön uç test dosyası. Hepsi diskte.

Doğrulama: `frontend/src` + `frontend/e2e` altındaki 7.385 göreli / `@/` import'un tamamı bir dosyaya çözülüyor (istisnalar:
`?raw` sorgulu whitepaper import'ları — dosyalar mevcut; `lazy-tabs-smoke.test.jsx`'teki `./x/Y` yorum içi örnek; ve C3). Arka
uçta 358 farklı `com.sitemonitor.*` sınıf başvurusu diskteki bir `.java`'ya çözülüyor. Silinen `noc/forms/NocBadge.jsx` ve
`admin/monitorchanges/ChangeList.jsx`'e kalan import yok. Değişen/yeni 84 ön uç dosyasındaki sabit i18n anahtarlarının hepsi
`i18n/index.jsx`'te (eksik görünenler yalnız dinamik önekler: `chg.kind.` + tür vb.).

**C1 · DÜŞÜK (yayın hijyeni) · `Claude outputs/SiteMonitor_Sunum_18Eylul2026*.pptx` (3 dosya, ~3,4 MB) — izlenmiyor ve `.gitignore`'da yok**
- **Bug:** Klasör 18 Eylül'den beri çalışma ağacında; `git check-ignore` eşleşme vermiyor.
- **Neden bug:** Sürüm makrosunda `git add -A` / `git add .` kullanılırsa sunumlar (kurum içeriği ve kimlik bilgisi taşıması
  olası ikili dosyalar) kalıcı git geçmişine girer; IdentityLeakGuard metin tarar, `.pptx` içini göremez.
- **Çözüm:** Dosyaları repo dışına taşıyın ya da `.gitignore`'a `Claude outputs/` ekleyin; sürüm commit'inde dosyaları
  açıkça listeleyin.

**C2 · DÜŞÜK (bütünlük izi) · `service/mail/MailKit.java:1121` — javadoc diskte olmayan `WeeklyAvailabilityMailTest`'i kapı olarak gösteriyor**
- **Bug:** Haftalık rapor blokları için "kapı: WeeklyAvailabilityMailTest" yazıyor; böyle bir sınıf yok. İçerik kapıları
  `WeeklyAvailabilityEmailTest.java` (izlenmeyen; kaçış, düz metin paritesi, 102 KB bütçesi, canlı taban adres) ve
  `MailKitReportBlocksTest.java`'da.
- **Neden bug:** "Oturum ortasında izlenmeyen bir test kayboldu" bildirimiyle uyumlu bir iz — ya dosya yeniden adlandırıldı ya da
  kayboldu. Kırık import yok, kapsam kardeş testte mevcut; ama kapının adı yanlış yönlendiriyor.
- **Çözüm:** Javadoc'u `WeeklyAvailabilityEmailTest` olarak düzeltin; commit öncesi bu dosyanın ve diğer 8 izlenmeyen arka uç
  test/örnek dosyasının `git add`'e girdiğini doğrulayın.

**C3 · DÜŞÜK (ÖNCEDEN VAR — bu sürümün değil) · `frontend/src/test/CertificateCardExtras.test.jsx:5`, `CertificateCardExtras.regression-1.test.jsx`, `SmtpLogView.test.jsx:7` — `vi.mock('../contexts/TeamDirectoryProvider.jsx')` var olmayan bir modülü taklit ediyor**
- **Bug:** `contexts/` altında yalnız `BrandingProvider.jsx` ve `PermissionsProvider.jsx` var; `useTeamDirectory` gerçekte
  `components/ui/TeamDirectory.jsx`'te.
- **Neden bug:** Taklit etkisiz; testler gerçek takım rehberiyle koşuyor (bugün yeşil olabilir, ama testin varsaydığı yalıtım
  yok — rehber davranışı değişince ilgisiz testler kırılır).
- **Çözüm:** Yolu `../components/ui/TeamDirectory.jsx` yapın (ya da taklidi kaldırın).

---

## Temiz sınıflar (tarandı, örnek yok)

- **S1 / müdür tuzağı (yeni uçlar):** `/changes/summary` `/changes/recent` ile AYNI `changeScope` (`MonitoringController.java:649`
  liste, `:721` özet, `:802-805` ortak kapsam): boş görüş kapsamı → boş özet (sorgu açılmaz), kapsam dışı `teamId` → 403, `teamIds`/`actorIds`
  kapsam listeleri tüm özet sorgularına geçiyor. `top_resources` ad/takımı KAPSAMDAKİ en yeni satırdan (`MAX(c.id)` kapsamlı
  sorguda), takım adı yalnız bu satırlar için; `actors` yalnız kapsamlı satırlardan (liste zaten aynı aktörü gösteriyor).
  `findDeletedAmong` kapsamsız ama yalnız kapsam içi satırlara boolean ekliyor. `/noc/groups/options` e-posta/adres taşımıyor,
  `has_active_group`/`min_level` hassas değil, `monitoring.read` istiyor. `TeamDirectoryController.java:112-130` beyaz liste:
  telefon, sicil, fotoğraf base64, LDAP alanı YOK; `has_photo` yalnız boolean; fotoğraf mevcut `/api/users/{id}/photo`
  (1 sa önbellek). MTTR `resolvedDurationStats` kardeş sayaçlarla aynı `scoped/scopeList` (`IncidentService.java:95-96, 164`).
  Diff'te yeni `"ADMIN".equals`/`isTeamAdmin`/rol dizesi kapısı yok; yeni ön uç bileşenlerinde `systemRole` kapısı yok.
- **Nullable CAST / LIKE:** `resourceId` Long karşılaştırması tipli, `activeLike` `CAST(:activeLike AS string)`;
  `countActiveToggles` deseni hiç null değil. LIKE enjeksiyonu yok: `activeLike` yalnız sabitlerden (`ACTIVE_PAUSED/RESUMED`),
  kullanıcı yalnız `eventType=PAUSE|RESUME` seçiyor. `countByHour` SUBSTRING uzunluğu sabit (42803 dersi);
  `HistoryQueryGrammarTest` yeni sorguları H2-PostgreSQL kipinde koşturuyor.
- **S9:** Yeni repository metotlarının hepsi okuma; `@Modifying` yok.
- **N+1 / tek pod:** Özet ucu sabit ~12 sorgu (sayfa çevirmede koşmaz, `counts=false`); haftalık rapor 50'lik parçalar; aylık
  rapor özeti tek geçiş, yeni sorgu yalnız geçen ayın tek log satırı; `useNocState` sayfa başına TEK istek (300+ kart).
- **E-posta:** Her dinamik değer kaçırılıyor (`heading`/`title`/`alert`/`kpis`/`bars`/`link`/`quietLink`/`pill`/`listCard`
  çağıranlar `esc`); taban adres CANLI (`liveBaseUrl()` → `Input.baseUrl`), bağlantı/düğmeler `safeHref`'ten geçiyor (aylık
  raporda ek şema kontrolü); sol şerit yok (`border-left` taraması boş); her blok için düz metin (`MailDoc.txt`); `darkCanvas`
  yalnız iki rapor e-postasında, alt bilgi kartın içinde. Deep link'ler uygulamayla eşleşiyor: `w_year/w_week/w_team`
  (`WeeklyReportsPage.jsx:103-107`), `view/from/to/team` (`alertHistoryModel.js:18`), `incident` (`IncidentsPage.jsx:153-157`),
  `g_tab=teams`, `settings&sec=certinvreport`, `dashboard&domain&open=cert`.
- **Ön uç yarış / bayrak:** `MonitorChangesConsole` `listSeq`/`sumSeq`, `IncidentHistoryPage` `loadSeq`/`trendsSeq`/
  `trendDailySeq`, `useResponseSeries` `seqRef`+`alive`, `LaIncidents` `seq`, `SecretTools` `seq`, `IncidentFormModal`
  `previewSeq`; meşgul bayrakları `finally`'de (yalnız son tur düşürüyor). Hook'lar erken dönüşlerin üstünde
  (`IncidentHistoryPage.jsx:248`, `StatsView.jsx:159-160`, `ExecutiveSummary`). Eskiyi-göster-yeniyi-bekle: grafik hedef
  değişince eski izlemenin verisini göstermiyor, aralık değişince soluk.
- **URL ad alanı:** `ih_`/`st_`/`ch_` önekleri başka bir uygulama anahtarıyla çakışmıyor; `tab/domain/monitor/incident`
  yazılmıyor, `incident` Olay & Hata Geçmişi'nde yalnız okunup siliniyor (e-posta uyumu `IncidentNotificationService.java:123`).
- **`useNocState`:** modül düzeyinde tek depo, uçuştaki isteğe katılım, `NOC_COVERAGE_EVENT` ile nesil artırarak geçersizleme,
  5 dk taze / 60 sn hata yeniden denemesi, "iletilmiyor" iddiası yalnız gerçek yanıttan. DNS/Port türevleri 7/24'ü kendi
  `noc_notify` kolonundan okuyor (`NocMonitorDirectory.java:241-260`) — kart göstergesi dağıtımla aynı kaynak.
- **Sertifika önbelleği:** `noc_notify` yazan her yol boşaltıyor: envanter formu (`AdminController` `@CacheEvict`), içe
  aktarma (`InventoryInsightController.java:76`), 7/24 tekil/toplu (`NocController.java:175, 219`; B5 dışında).
- **S7:** `ts` koruması `responseChartModel.js:138`. **S6:** yeni listeler `sp.bar` / `usePagination` (taban dönüşümü kancada).
- **Parola autocomplete:** 12 parola alanının tamamı `new-password`/`current-password` taşıyor; kapı
  (`passwordAutocomplete.test.js`) tek/çift tırnak dallarını doğru yakalıyor.
- **Tel biçimi:** `slow_threshold_ms`/`slow_response_enabled`/`noc_notify`/`noc_group_ids`/`has_active_group`/`min_level`
  ön uçta aynı snake_case adla okunuyor (global `SNAKE_CASE`, elle `Map` anahtarları).
- **Test zaman bombası:** Yeni/değişen arka uç testlerinde çıplak `LocalDate.now()` yok (hepsi `ZoneOffset.UTC`/`Europe/Istanbul`
  ile, göreli); ön uç model testleri `now`'u enjekte ediyor, sabit tarihler yalnız görüntülenen fixture. `.only`/`@Disabled` yok
  (`InventoryPdfPreviewDumpTest` bilinçli `@EnabledIfSystemProperty`).
- **MUTASYON işareti:** yalnız bilinen yanlış-pozitif (`frontend/src/test/apiTime.test.js:35`).
- **Geçici dosyalar:** `frontend/zz-*` yok, `*.tmp.*` yok; `frontend/test-results/` `.gitignore:66`'da, içinde yalnız
  `.last-run.json`.
- **CSS:** `App.css`'ten silinen `sv-root`, `sv-widgets`, `exs*` sınıflarına kalan başvuru yok; `.ts-root` hâlâ tanımlı.
- **Kimlik sızıntısı:** eklenen satırlarda ve izlenmeyen metin dosyalarında yer tutucu dışı alan adı / e-posta yok.

---

## Doğrulanamadı

- **Derleme + testler:** paralel `mvn clean verify` sonucu bu taramaya dahil değil; vitest/Playwright koşturulmadı. Özellikle
  `resolvedDurationStats` grammar testinin dışında (`findDeletedAmong` artık `HistoryQueryGrammarTest.java:451`'de); yeni JPQL'lerin GERÇEK PostgreSQL davranışı yalnız H2
  PostgreSQL kipiyle sınanıyor.
- **B2**'nin `%2D` yönlendirmesi Spring'in çözülmüş-bölüt eşleştirmesinden çıkarıldı; istekle denenmedi.
- **Görsel:** e-postaların Outlook/Gmail/Apple Mail (koyu tuval dahil) görünümü, PDF özet sayfası, 390×844 / 768×1024 yerleşimi.
- **C2:** `WeeklyAvailabilityMailTest`'in kaybolan dosya mı, yeniden adlandırma mı olduğu (izlenmeyen dosya; git izi yok).
- `ChangeKpis`, `ChangeTimeline`, `HistoryToolbar`, `TeamTierMatrix`, `CertList` yalnız veri akışı / yarış / URL açısından
  okundu; a11y ve etkileşim ayrıntısı taranmadı.

## Not (kod değil, bellek)

`cert-monitor-team-badge-pattern.md` notu "`/api/teams/{id}/members` sistem rolü/foto ASLA dönmez" diyor; 2026-09-28 kullanıcı
kararıyla `system_role` + `has_photo` dönüyor (telefon/sicil hâlâ dönmüyor). Not güncellenmeli.

---

## Önerilen sıra

1. **Yayın öncesi hijyen:** C1 (`Claude outputs/` → `.gitignore` ya da taşı), C2 (javadoc + izlenmeyen 12 arka uç (3 üretim,
   9 test) ve 47 ön uç dosyasının (37 bileşen/model, 10 test) commit'e girdiğini doğrula).
2. **Küçük, yayına alınabilir:** B5 (`finally` içinde boşaltma — tek satır), B3 (not metni), B4 (PDF dilim genişliği).
3. **Sonraki sürüm:** B1 (silinmiş hükmü = son yaşam döngüsü olayı + tekil ayrıntıya alan), B2 (çözülmüş yolla karşılaştırma),
   C3 (önceden var — taklit yolu).

**Özet:** 0 REGRESYON (20.88.0'ın 33 düzeltmesinin imzası yerinde; O15 bu diff'le kapandı). Yeni yüzeyde 0 KRİTİK · 0 YÜKSEK ·
0 ORTA · 5 DÜŞÜK; izlenmeyen dosya bütünlüğünde kırık import yok, 3 DÜŞÜK (biri önceden var).

---

## (D) DÜZELTME DURUMU (aynı gün, 20.89.0'a girdi)

| Kod | Önem | Konu | Durum |
|---|---|---|---|
| B2 | DÜŞÜK (güvenlik) | `RequestLoggingFilter` yüzde kodlu yol (`secret%2Dtools`) gövde atlamasını atlatıyordu | **DÜZELTİLDİ** — ham + iki tur çözülmüş yol denenir; `RequestLoggingFilterTest` 3 yeni URI (ısırma doğrulandı) |
| B4 | DÜŞÜK | PDF dağılım çubuğunda asgari genişlik telafisiz → son dilim eksi | **DÜZELTİLDİ** — küçük dilime 3 pt, kalan büyüklere orantılı |
| B5 | DÜŞÜK | Toplu 7/24'te istisnada sertifika önbelleği boşalmıyordu | **DÜZELTİLDİ** — `finally`; `NocControllerTest.bulk_failureMidway_stillEvictsCertificateCaches` (ısırma doğrulandı) |
| C1 | DÜŞÜK | `Claude outputs/` izlenmiyor ama `.gitignore`'da değil | **DÜZELTİLDİ** — `.gitignore` |
| C2 | DÜŞÜK | `MailKit` yorumu olmayan `WeeklyAvailabilityMailTest`'i gösteriyordu | **DÜZELTİLDİ** — `WeeklyAvailabilityEmailTest` + `MailKitReportBlocksTest` |
| B1 | DÜŞÜK | Geri yüklenen izleme "silinmiş" rozeti taşıyor; `ch_id` ayrıntısında `resource_deleted` yok | **AÇIK** — sonraki sürüm (son yaşam döngüsü olayı hükmü) |
| B3 | DÜŞÜK | Haftalık e-postada önceki haftalardan devreden alarmlar Alarm Geçmişi bağlantısında yok | **AÇIK** — sonraki sürüm (not metni / süzgeç) |
| C3 | DÜŞÜK (önceden var) | 3 test olmayan `contexts/TeamDirectoryProvider.jsx`'i taklit ediyor | **AÇIK** |

Ek karar (kullanıcı, aynı gün): aylık rapor PDF eki YALNIZ özet — kayıt bazında detay sayfaları çıkarıldı (tam liste CSV'de).
