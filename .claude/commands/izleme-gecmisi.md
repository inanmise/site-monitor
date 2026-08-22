---
description: Tüm izleme tiplerinde (port/dns/keyword/http/page/scripted/domain/ping + opsiyonel sertifika envanteri) tam değişiklik geçmişi — kim, ne zaman, hangi IP'den oluşturdu; ilk değerler neydi; sonra kim neyi nasıl değiştirdi. Takım-kapsamlı zaman çizelgesi UI, alan-bazlı eski→yeni diff, geriye dönük doldurma, retention, testler ve zenginleştirme önerileri.
argument-hint: [tasarim|backend|frontend|hizli] (opsiyonel — boş bırakılırsa TAM kapsam koşulur)
---

# /izleme-gecmisi — İzleme Değişiklik Geçmişi (Kim, Ne Zaman, Neyi, Hangi IP'den)

Görevin: her izleme kaydının **tam yaşam öyküsünü** ürünleştirmek. Ürün kararı netleşmiş çekirdek:

- **Oluşturma anı:** izlemeyi KİM, NE ZAMAN, HANGI IP'den oluşturdu ve **hangi ilk değerlerle**
  (initial snapshot) — sonradan her an geri bakılabilir.
- **Her güncelleme:** kim, ne zaman, hangi IP'den; **alan bazında eski → yeni** (yalnız değişenler).
- **Yaşam döngüsü:** silme (pasifleştirme), geri alma, takım değişimi, grup değişimi de aynı
  zaman çizelgesinde okunur.
- **Takım kapsamı:** her kullanıcı YALNIZ görebildiği takımların izlemelerinin geçmişini görür
  (SessionScope.canView) — admin/AUDIT her şeyi görür. Denetim ekranına gitmeye gerek kalmadan,
  izlemenin kendi detay modalında "Değişiklikler" sekmesi.
- **UI:** mevcut proje CSS'i ve `ui/` primitifleriyle uyumlu, estetik açıdan özenli bir zaman
  çizelgesi — scripted sürüm geçmişindeki `VersionTimeline` görsel dili temel alınır.

Yüzeysel iş KABUL EDİLMEZ: her faz kanıtla (dosya yolu, test çıktısı, ekran davranışı) raporlanır;
ürün kararı gerektiren her boşluk tahmin edilmez, seçeneklerle kullanıcıya sorulur.

Kapsam argümanı: `$ARGUMENTS`
- Boş → tüm fazlar (0–7) sırayla.
- `tasarim` → yalnız Faz 0–1 (keşif + karar dosyası) — kod yazılmaz, onay beklenir.
- `backend` → Faz 0, 1, 2, 3, 6, 7.
- `frontend` → Faz 0, 4, 5, 6, 7 (API hazır varsayılır; değilse önce raporla).
- `hizli` → Faz 0–5 (testler asgari, Faz 6'nın smoke bölümü atlanır) — yalnız hızlı prototip.

## Değişmez kurallar (her fazda geçerli)

1. **Mevcut desenleri yeniden icat etme — aynala.** Diff üretimi `AuditDiff.snapshot/diff`
   (`service/AuditDiff.java`; çıktı `{"alan":{"from":x,"to":y}}`, hassas alanlar `SecretMask` ile
   `***`) ile yapılır — İKİNCİ bir diff mekanizması yazılmaz. Zaman çizelgesi UI'ı
   `components/scripted/VersionTimeline.jsx` (saf sunum, i18n'siz, `sc-vt` CSS) üzerine kurulur.
   Yetki: `permissionService.require(session, key, action)` + `SessionScope.canView/canManage` +
   `canOperateTeam` (MonitoringController:251). Denetim `auditService.recordAction(...)` AYNEN kalır —
   bu geliştirme denetimin YERİNE geçmez, ürün-görünür bir katman EKLER.
2. **PermissionCatalog kuralı (CLAUDE.md):** koda giren her yeni `resource_key` AYNI değişiklikte
   `PermissionCatalog.ALL`'a eklenir. (Öneri: yeni key AÇMA — geçmiş okuma `monitoring.read` +
   takım kapsamıyla yeterli; ayrı key ancak K3'te "IP'yi yalnız ayrı yetkiyle göster" seçilirse gerekir.)
3. **Şema kuralı (CLAUDE.md):** yeni tablo/kolonlar `ddl-auto=update` ile doğar AMA eski DB'ler için
   `SchedulerService.applySchemaPatches()`'e (satır ~357; `patch()` yardımcısı ~934) idempotent
   `CREATE TABLE` / `ALTER TABLE` satırları da eklenir.
4. **i18n:** her yeni anahtar TR ve EN'e AYNI değişiklikte girer (`i18n-parity.test.jsx` kapısı).
   `.properties` değerlerinde ham Türkçe karakter YAZILMAZ (`\uXXXX`; `PropertiesEncodingTest`).
5. **Gizlilik pazarlık dışı:** diff ve snapshot'larda hassas alan değerleri ASLA düz metin
   taşınmaz — `AuditDiff`/`SecretMask` maskesi (`***`) korunur; UI maskeli değeri kilit ikonu ile
   gösterir. IDOR: başka takımın izleme geçmişine erişim → 403/404, testle pinlenir.
6. **Tasarım dili:** yeni yüzeyler mevcut `App.css` değişkenleri/sınıflarıyla ve `ui/`
   primitifleriyle kurulur (`UserBadge`, `SegmentedControl`, `PaginationBar`, `DateTimeRangePicker`,
   `Toast`, `Dialog`, `KebabMenu`). Yeni CSS yazmadan önce eşdeğer sınıf var mı bak (`sc-vt`,
   `sc-diff`, `modal-tab`, `audit-*`). Desen adları için namethatui.com sözlüğü (timeline,
   badge-chip-pill, empty-state, description-list). İkon = yalnız `lucide-react`; dark theme'de
   (`[data-theme="dark"]`) her yeni yüzey elle doğrulanır.
7. **Repo yazma tuzağı (CLAUDE.md 2026-08-21):** retention/temizlik için yazılacak her türetilmiş
   `deleteBy…` repo metodu `@Transactional` ister — `RepositoryWriteTransactionGuardTest` kapısına
   takılma; allow-list'e satır eklemek yerine anotasyonu koy. Yazan metodlar `int` dönsün.
8. **Geçmiş yazımı kullanıcı kaydını ASLA düşürmez:** history satırı yazılamazsa `log.warn` + devam
   (ActivityLogService'in "best-effort" sözleşmesiyle aynı); ama sessiz de kalmaz.
9. Ortam Windows (`JAVA_HOME=C:\Program Files\Zulu\zulu-25`, Maven
   `D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`, Node 24). `start-local.ps1` paketlenmiş jar
   çalıştırır → smoke öncesi `mvn package -DskipTests` + `npm run build`.
10. Hiçbir şey commit'lenmez; coverage floor'ları düşürülmez; `TESTING.md` etkilenirse aynı anda
    güncellenir. Davranış değiştiren her ürün kararı (K1–K9) uygulanmadan ÖNCE kullanıcıya sorulur.

## Keşifte doğrulanmış altyapı gerçekleri (2026-08-21 — yeniden keşfetme, DOĞRULA ve kullan)

Satır numaraları o günkü çalışma kopyasına aittir; kod kaymış olabilir — deseni adıyla ara.

- **8 tekil izleme tipi, tek controller:** `MonitoringController` — PORT (create 799 / update 845 /
  delete 884), DNS (1106/1147/1200), KEYWORD (1374/1416/1455), HTTP (1861/1899/1934), PAGE
  (2124/2158/2190), SCRIPTED (2441/2487/2728), DOMAIN (3285/3313/3349), PING (3540/3575/3617).
  Ayrıca gruplar: `PUT /groups/{id}` rename (318, `monitoringGroupService.rename`).
- **Alan-bazlı diff ZATEN VAR (update yollarında):** her 8 tipin update'i
  `AuditDiff.snapshot(x, MON_FIELDS)` (öncesi) + `AuditDiff.diff(before, after)` üretip
  `auditService.recordAction("MONITOR_UPDATE", session, "<TIP>_MONITOR", id, name, changes)` yazıyor.
  `MON_FIELDS` tip-üstü superset (satır ~80: name/host/port/url/domain/recordType/keyword/…/active/
  teamId/groupName/interval/timeout/…); scripted kendi `SCRIPTED_FIELDS`'ini kullanıyor.
- **BOŞLUK 1 — create'te ilk değerler yok:** yalnız SCRIPTED create ilk snapshot'ı yazıyor
  (2478: `AuditDiff.diff(null, AuditDiff.snapshot(saved, SCRIPTED_FIELDS))`); diğer 7 tip
  `MONITOR_CREATE`'e `changes=null` geçiyor. Delete'te de hepsinde null.
- **BOŞLUK 2 — entity'lerde kimlik yok:** monitör entity'lerinde `createdAt/updatedAt` var
  (ISO-UTC string, formatter `yyyy-MM-dd'T'HH:mm:ss`) ama `createdBy/updatedBy` YOK
  (örn. PortMonitor:99–102).
- **BOŞLUK 3 — takım kullanıcısı geçmişi GÖREMİYOR:** `GET /api/admin/audit/resource/{type}/{id}`
  (AuditController:76) tam da istenen kaynak-zaman-çizelgesi ama `requireAuditAccess` +
  `audit_log.read` (admin/AUDIT) kapısında. `/api/me/audit` (AuthController:457) yalnız kişinin
  KENDİ eylemleri. `ActivityLog` takım-scoped (ActivityController, `SessionScope.canView`, IDOR
  yorumları) ama yalnız CREATED/DELETED yaşam döngüsü — actor var, IP ve diff YOK.
- **AuditService.recordAction(eventType, session, resourceType, resourceId, detail, changes)**
  request'i `RequestContextHolder`'dan çözer → **IP + User-Agent + sessionId + correlationId
  OTOMATİK** gelir. `AuditLog` zengin: hash-zinciri (seq/row_hash/prev_hash — kurcalanamaz),
  actor/actorId/actorTeamId/actorRole, ipAddress + geo (country/city/org/reverse-host), userAgent,
  outcome, anomalyFlags (OFF_HOURS otomatik damgalanır).
- **audit_log retention'a tabidir:** `RetentionCatalog` ~87: `site.monitor.audit.retention-days` —
  audit'ten beslenen bir geçmiş zamanla KIRPILIR (K1'in ana gerekçesi).
- **Silme semantiği tip-bazlı:** 7 tip soft-delete (`active=false` + `MONITOR_DELETE`); SCRIPTED
  hard-delete (2736: `repo.delete`; sürüm geçmişi BİLİNÇLİ tutulur, öksüzler retention'la temizlenir).
- **Sürüm zaman çizelgesi UI hazır:** `scripted/VersionTimeline.jsx` — saf sunum (i18n prop'la),
  `EVENT_STYLE` ikon+ton haritası (CREATE/EDIT/DELETE/RESTORE/… hazır), `sc-vt` CSS (App.css ~7495),
  satırlar `role="button"` + klavye. `ScriptedVersionsTab.jsx` diff/script SegmentedControl görünümü +
  `sc-diff` satır stilleri. `ScriptedScriptVersion` append-only desen (monitorId, sequenceNo,
  eventType, createdAt/createdBy/createdByName).
- **Detay modalları:** her `*MonitorPage.jsx` kendi modalında `modal-tab` çubuğu taşır (örn. Port:
  control/alerts/chart/notes, 505–539); `history/CheckHistoryTab.jsx` ortak gövde, `MonitorNotes`
  lazy import. Yeni sekme buraya eklenir.
- **Aktör gösterimi:** `ui/UserBadge.jsx` — username/email → AD fotoğrafı + ad-soyad
  (UserDirectory'den çözer), `systemLabel` ile sistem aktörü. IP/tarih için `formatDateSec`
  (api/client), date-fns Europe/Istanbul.
- **Takım çözümü:** `resolveWriteTeam` (260), `resolveTeamChange` (269), `canOperateTeam` (251);
  görünürlük `SessionScope.viewTeamIds/canView`, yönetim `canManage`.
- **Envanter (uptime/SSL kartlarının kaynağı) ayrı ailede:** `AdminController` — `DOMAIN_ADD` (206),
  `DOMAIN_EDIT` (300, `buildInventoryDiff` 306), soft-delete/restore/purge/transfer olayları
  (648/754/781/828/847) zaten diff'li audit yazıyor. K4 kapsam kararına girdi.
- **İzin katalogu:** `monitoring.read/crud/trigger/scripted/group/scripted_templates`,
  `audit_log.read`. i18n tek dosya `i18n/index.jsx` (~9k satır, TR+EN).

## Faz 0 — Keşif doğrulaması + karar noktaları

Önce yukarıdaki gerçekleri koddan DOĞRULA (satırlar kaymış olabilir). Sonra şu kararları
seçeneklerle ve önerinle kullanıcıya sun (`tasarim` modunda çıktı `docs/` altına karar notu yazılır):

- **K1 — Gerçeğin kaynağı:** (a) **Yeni `monitor_change_log` tablosu + çift yazım (ÖNERİLEN):**
  audit_log güvenlik kaydı olarak AYNEN kalır (hash zinciri, admin kapısı, kendi retention'ı);
  ürün-görünür geçmiş ayrı append-only tabloya yazılır — takım-scope sorgusu doğal (tabloda
  monitörün teamId'si var; audit'te yalnız AKTÖRÜN takımı var), kendi retention'ı uzun tutulur,
  create-snapshot'ı taşır. (b) audit_log'dan takım-scope okuma endpoint'i: tablo eklenmez ama
  audit retention'ı geçmişi kırpar, teamId eşlemesi her okumada monitör tablosuna join ister,
  audit okuma politikası (bilinçli admin/AUDIT kapısı) delinmiş olur. (a) seçilirse audit yazımı
  DEĞİŞMEZ; history yazımı `MonitorHistoryService` üzerinden aynı noktalara eklenir.
- **K2 — Snapshot politikası:** (a) **her olayda tam snapshot + diff (ÖNERİLEN):** "şu tarihte bu
  izleme nasıldı" tek satırdan okunur, K6 restore ve E6 karşılaştırma bedavaya çıkar; maliyet satır
  başı ~1-2 KB JSON. (b) yalnız CREATE'te snapshot, sonrası diff zinciri: küçük ama nokta-zaman
  görünümü diff'leri baştan oynatmayı gerektirir.
- **K3 — IP/User-Agent görünürlüğü:** kullanıcı isteği net ("hangi IP'den") → (a) **takım üyesi
  IP'yi görür (ÖNERİLEN):** aynı takımın operasyon bilgisi; UA kısaltılmış (tarayıcı+OS), tam UA
  tooltip'te. (b) IP yalnız admin/AUDIT'e, takım üyesi yalnız kim+ne zaman+ne görür. Karar
  PermissionCatalog'a yeni key gerektirirse kural 2 uygulanır.
- **K4 — Kapsam genişliği:** 8 tekil tip ZORUNLU çekirdek. Ek olarak: (a) **sertifika envanteri
  dahil (ÖNERİLEN** — kullanıcı "bütün monitör tipleri" dedi; Uptime/SSL kartları envanterden gelir;
  `buildInventoryDiff` zaten diff üretiyor, aynı history hunisine bağlamak ucuz; UI'da
  CertificateModal + InventoryManager'a sekme), (b) grup yeniden adlandırma (GROUP_RENAME olayı —
  grup başına TEK satır, monitör başına değil), (c) eşikler/bakım pencereleri (ayrı faza bırakılabilir).
- **K5 — Geriye dönük doldurma (backfill):** (a) **tek seferlik idempotent taşıma (ÖNERİLEN):**
  audit_log'da retention'a takılmamış `MONITOR_CREATE/UPDATE/DELETE` (+ envanter `DOMAIN_ADD/EDIT`)
  satırları `monitor_change_log`'a kopyalanır (kaynak=`AUDIT_BACKFILL` işaretli, IP/aktör/changes
  taşınır); ayrıca `createdBy` kolonları audit'teki en eski CREATE satırından doldurulur. Boş geçmişle
  açılan ekran "bu özellik eklendiğinden beri" notu gösterir. (b) backfill yok — geçmiş bugünden başlar.
- **K6 — Geri döndürme (restore):** (a) bu fazda: snapshot'tan "bu hâle geri döndür" düğmesi
  (yalnız `monitoring.crud` edit + canManage; RESTORE olayı üretir — scripted'daki RESTORE deseniyle
  aynı). (b) **sonraki faza bırak (ÖNERİLEN** — çekirdek gözlemlenebilirlik önce otursun; K2=a
  seçildiyse kapı açık kalır).
- **K7 — Retention:** `monitor_change_log` için ayrı anahtar
  (örn. `site.monitor.monitoring.change-retention-days`), RetentionCatalog'a audit-log satırı (~87)
  aynalanarak eklenir. Varsayılan öneri: **730 gün** (denetim değeri yüksek, hacim düşük — config
  değişiklikleri check kayıtları gibi akmaz). 0/boş = süresiz seçeneği de tanımlansın mı, sor.
- **K8 — Değişiklik nedeni notu:** düzenleme modallarına opsiyonel "değişiklik nedeni" alanı
  (timeline'da notuyla görünür — değişiklik yönetimi kültürü; scripted'ın sürüm `note`'u zaten var).
  Öneri: EKLE ama zorunlu tutma; alan boşsa UI hiç göstermez.
- **K9 — Takım geneli "Son Değişiklikler" akışı:** tüm izlemelerdeki config değişikliklerini tek
  listede gösteren takım-scope panel. (a) bu fazda Activity sekmesine `CONFIG_CHANGED` yaşam döngüsü
  olayı + özet diff eklemek (ucuz), (b) ayrı ekran (sonraya). Öneri: (a).

## Faz 1 — Veri modeli ve şema (K1=a varsayımıyla)

- **`MonitorChangeLog`** entity (`monitor_change_log`): `id`; `monitorType` (PORT/DNS/KEYWORD/HTTP/
  PAGE/SCRIPTED/DOMAIN/PING + K4'e göre CERT_INVENTORY/GROUP); `monitorId` (envanterde domain
  string'i için ayrı `resourceKey` kolonu değerlendir — envanter id'si de var, onu kullan);
  `monitorName` (silinen monitörün geçmişi ad ile okunabilsin); `seq` (monitör başına 0'dan artan —
  0 = CREATE); `eventType` (CREATE/UPDATE/DELETE/RESTORE/GROUP_RENAME/AUDIT_BACKFILL…);
  `teamId` (olay ANINDAKİ takım — takım değişiminde yeni değer, diff'te eskisi görünür);
  `actor`, `actorId`, `actorName`; `ipAddress`, `userAgent` (kısaltılmış saklama değil — ham sakla,
  UI kısaltır); `changes` TEXT (AuditDiff JSON — CREATE'te null olabilir); `snapshot` TEXT (K2);
  `note` (K8); `createdAt` (ISO-UTC, mevcut formatter). İndeksler: (`monitorType`,`monitorId`),
  `teamId`, `createdAt`.
- Repository: `findByMonitorTypeAndMonitorIdOrderBySeqDesc(Pageable)`, takım akışı için
  `findByTeamIdInOrderByCreatedAtDesc(Pageable)` (K9), retention için `deleteByCreatedAtBefore`
  (**kural 7: `@Transactional` + `int` dönüş**).
- **Monitör entity'lerine kimlik kolonları:** 8 entity + (K4) `CertificateInventory`'ye
  `createdBy`, `createdByName`, `updatedBy`, `updatedByName` (+ öneri: `createdIp`) — `@Column` ile.
- **`applySchemaPatches()`:** `monitor_change_log` için idempotent `CREATE TABLE` + indeksler;
  her monitör tablosuna `ADD COLUMN` patch'leri; K5=a ise tek seferlik backfill bloğu (bir bayrak
  satırı/`app_setting` ile "bir kez koştu" korunur — pod restartında tekrarlamaz, çok-replika
  güvenliği için scheduler_lock deseniyle sarmayı değerlendir).
- **RetentionCatalog:** K7 kaydı + `RetentionSettings` UI'ının yeni anahtarı otomatik göstermediği
  durumda admin ekran güncellemesi (katalog-sürümlü ekransa kendiliğinden gelir — doğrula).

## Faz 2 — Backend: yazım noktaları (`MonitorHistoryService`)

- Tek giriş: `monitorHistory.record(type, monitor, eventType, before, after, note, session)` —
  içeride `AuditDiff.diff` + (K2) snapshot + session'dan aktör + `RequestContextHolder`'dan IP/UA
  (AuditService.currentRequest deseni AYNEN); `seq` = son+1 (monitör kilidi gerekmez — çakışmada
  unique(monitorType,monitorId,seq) yerine seq'i best-effort tut, sıralama createdAt+id ile).
  Kural 8: hata yutulur, `log.warn`.
- **8 tipin create'ine:** ilk snapshot'lı CREATE satırı + entity `createdBy/…/createdIp` set.
  Scripted'ın audit'teki mevcut ilk-snapshot davranışı örnek alınır ama artık HER tip history'ye yazar.
- **8 tipin update'ine:** mevcut `_before` snapshot'ı zaten alınıyor — AYNI before/after çiftini
  history'ye de geçir (audit çağrısına dokunma). Diff null (değişiklik yok) ise history satırı YAZMA
  (gürültü olmaz) — ama `updatedBy` yine güncellenir.
- **8 tipin delete'ine:** DELETE satırı (soft'larda `active {true→false}` diff'i; scripted
  hard-delete'te son snapshot'la — geçmiş satırları monitör silinse de KALIR, scripted sürüm
  politikasıyla aynı; öksüzler K7 retention'ıyla gider).
- (K4) Envanter: `AdminController`'ın add/edit/soft-delete/restore/transfer noktalarına aynı çağrı
  (`buildInventoryDiff` çıktısı `changes` olarak). (K4b) `MonitoringGroupService.rename` →
  GROUP_RENAME satırı. (K9a) update sonrası `activityLog.recordLifecycle(..., "CONFIG_CHANGED", ...)`
  + `resultSummary`'ye değişen alan adları (örn. "intervalSeconds, timeoutMs").
- Manuel "Şimdi Kontrol Et" tetikleri, check sonuçları, alarm olayları history'ye YAZILMAZ —
  onların yeri ActivityLog/CheckHistory; bu tablo yalnız KONFİGÜRASYON yaşam döngüsüdür (karışırsa
  sinyal boğulur). Bu sınır koda yorum olarak yazılır.

## Faz 3 — Backend: okuma API'si

`MonitoringController`'a (aynı stil — `ok()`/`badRequest()`, `Map` gövdeler):

- `GET /api/monitoring/changes/{kind}/{id}?page&size&eventType&actor&from&to` —
  `permissionService.require(session, "monitoring.read", "view")` + monitörün `teamId`'sine
  `SessionScope.canView` (monitör silinmişse son history satırının teamId'si). Dönen satır:
  seq, eventType, at, actor/actorId/actorName, ip (+K3), userAgent, changes (parse edilmiş obje),
  note, snapshot İSTEĞE BAĞLI (`?withSnapshot=` veya tekil uç) — liste yanıtını şişirme.
- `GET /api/monitoring/changes/{kind}/{id}/{seq}` — tek olayın tam detayı (snapshot dahil).
- (K9) `GET /api/monitoring/changes/recent?types&days` — `viewTeamIds` kesişimli takım akışı.
- `kind` → tip eşlemesi tek `Map`'te tutulur; **sözleşme testi** 8 (+K4) kind'ın tamamını
  reflektif sayar (responseSeries_contract deseni — 9. tip eklenince kayıt unutulursa build düşer).
- IDOR: yabancı takım → 404/403 + `auditService.recordSecurityEvent` (mevcut desen); testle pinlenir.

## Faz 4 — Frontend: ortak "Değişiklikler" sekmesi

- **`components/history/ChangeHistoryTab.jsx`** (CheckHistoryTab'ın kardeşi; `kind` + `monitorId`
  prop'ları): veriyi `api.monitoring.getChanges(kind, id, params)` ile çeker (client.js'e eklenir).
- **Zaman çizelgesi:** `VersionTimeline` yeniden kullanılır (saf sunum — rows/eventLabel/renderExtra
  prop'ları yeterli; EVENT_STYLE'a eksik olay tonları eklenir). Her satır: olay ikonu + tonu,
  `UserBadge` (ad-soyad+foto), `formatDateSec` zaman, IP (K3; `CopyButton` ile), kısaltılmış UA
  (tam hali `title`), K8 notu kendi satırında.
- **Diff sunumu:** değişen alanlar chip/pill dizisi — `alanAdı: eski → yeni` (ok ikonu
  `MoveRight`); uzun değerler kırpılır, tıklayınca satır genişler (accordion). Maskeli (`***`)
  değer kilit ikonuyla. **Alan adları i18n:** `chg.field.<key>` sözlüğü — `MON_FIELDS` ∪
  `SCRIPTED_FIELDS` (+K4 envanter alanları) anahtarlarının TAMAMI TR+EN etiketlenir ve bir **sync
  testi** (inventory-flags-sync deseni) Java taraflarıyla kilitler — yeni alan eklenince etiketsiz
  kalamaz. **Değer insancıllaştırma:** boolean → Aktif/Pasif rozeti; `teamId` → takım adı (sayfadaki
  mevcut takım haritası prop'la geçilir); `intervalSeconds/timeoutMs` → "5 dk / 30 sn"; null → "—".
- **İlk değerler paneli:** seq=0 (CREATE) satırı seçilince snapshot iki-sütunlu tanım listesi
  (description-list deseni) olarak açılır — başlık "İlk değerler" + oluşturan/zaman/IP künyesi.
- **Filtreler + boş durum:** olay tipi `SegmentedControl`, aktör `SearchableSelect` (satırlardan
  türetilir), tarih `DateTimeRangePicker`; `PaginationBar`; boş durumda empty-state deseni
  ("Henüz değişiklik kaydı yok" + K5b ise "geçmiş bu özellik eklendiğinden beri tutulur" notu).
- **Savunmacılık:** `changes` parse edilemeyen/`ts`'siz satır ÇÖKERTMEZ, düşürülür (ResponseTimeChart
  kuralı); ErrorBoundary'ye ulaşan hata kabul edilmez.

## Faz 5 — Entegrasyon: 8 detay modalı + kart meta (+K4 envanter)

- 8 `*MonitorPage.jsx` detay modalına `modal-tab` düğmesi: `t('chg.tab')` ("Değişiklikler") —
  mevcut sekmelerin (control/alerts/chart/notes) yanına, hepsinde AYNI konumda. Lazy import
  (MonitorNotes deseni). URL sekme senkronu olan sayfalarda (`mtab`) yeni anahtar whitelist'e girer.
- **Kart künyesi:** `MonitorCardMeta`'ya "Oluşturan: <UserBadge inline> · <tarih>" satırı
  (`createdByName` boşsa gösterme — grandfathered kayıtlar); detay modal başlığına "Son değişiklik:
  X, 2 gün önce" mikro-satırı.
- (K4) `CertificateModal` + `InventoryManager`'a aynı sekme (kind=`inventory`).
- (K9a) Activity sekmesi `CONFIG_CHANGED` olayını rozet + özet alan listesiyle gösterir.
- Dark theme elle doğrulanır; i18n TR+EN aynı değişiklikte.

## Faz 6 — Testler

- **Backend:** `MonitorHistoryServiceTest` (CREATE snapshot'lı, UPDATE yalnız-değişince, DELETE,
  maskeleme, hata-yutma kural 8); kind-eşleme **sözleşme testi** (reflektif sayım); IDOR testi
  (yabancı takım 403/404 + security event); backfill idempotency (iki kez koş → tek set);
  `deleteByCreatedAtBefore` `@Transactional` (`RepositoryWriteTransactionGuardTest` yeşil);
  entity kolonlarının patch satırları için `applySchemaPatches` kaynak-tarama kontrolü.
  MockMvc yerine mevcut birim-test üslubu (`@ExtendWith(MockitoExtension.class)`).
- **Frontend:** `ChangeHistoryTab.test.jsx` (timeline render, diff chip eski→yeni, maskeli değer,
  bozuk satır düşürme, boş durum, filtre); `chg.field.*` **sync testi** (MON_FIELDS/SCRIPTED_FIELDS
  ile parite); `i18n-parity` yeşil; bir monitör sayfasında sekme entegrasyon testi (mock api).
- Coverage floor'lar yalnız yukarı.

## Faz 7 — Doğrulama, smoke ve rapor

1. `mvn -B clean verify` → yeşil değilse rapor et, geçme.
2. `npm run test` + `npm run build`.
3. `mvn package -DskipTests` → `start-local.ps1` → `/health` UP → smoke: bir port monitörü OLUŞTUR
   (history'de seq=0 CREATE + ilk değerler + IP), iki alanını DEĞİŞTİR (diff chip'leri eski→yeni),
   SİL (DELETE satırı), başka takım kullanıcısıyla 403/404, admin AuditLog'da eşdeğer kayıtların
   hâlâ yazıldığını doğrula (çift yazım). Scripted'da sürüm sekmesiyle yeni sekmenin ÇAKIŞMADIĞINI
   (script sürümleri ayrı, config geçmişi ayrı — ikisi birbirine link verebilir) gözle.
4. Rapor: dosya-dosya değişiklik listesi, test çıktıları, karar noktalarının seçilen değerleri,
   ekran davranış özeti, bilinen sınırlar (örn. backfill audit-retention ufkuyla sınırlı).

## Zenginleştirme önerileri (kullanıcıya sun — hangileri şimdi, hangileri sonra)

- **E1 — Grafik anotasyonu:** ResponseTimeChart / CheckHistory üzerine config-değişiklik işaretçileri
  ("bu değişiklikten sonra yavaşladı/alarmlar başladı" korelasyonu — SRE değeri en yüksek ekstra).
- **E2 — Değişiklik bildirimi:** takım opt-in — izlemesi düzenlenince e-posta/webhook özeti
  (EscalationService takım yolu + notification_logs mevcut altyapısıyla).
- **E3 — Haftalık rapora bölüm:** "Bu hafta yapılan izleme değişiklikleri" (WeeklyReportService).
- **E4 — CSV/PDF dışa aktarım:** geçmiş sekmesinden (audit export deseni; pdfBrand.js markalı).
- **E5 — Mesai-dışı rozeti:** audit'teki OFF_HOURS damgası history satırında "mesai dışı" chip'i.
- **E6 — İki nokta karşılaştırma:** timeline'da iki olay seç → alan-bazlı yan yana fark (K2=a ister).
- **E7 — Admin köprüsü:** admin/AUDIT kullanıcısına satırda correlation-id ile tam denetim kaydına
  derin bağlantı (AuditLogViewer filtreli açılış).
- **E8 — Kopya kökeni:** "Kopyala" ile doğan monitörün CREATE satırına "X'ten kopyalandı" izi.
- **E9 — Sayaç rozeti:** detay modal başlığında "12 değişiklik" mini rozeti — sekmeye davet eder.
- **E10 — Sahiplik devri görünümü:** teamId değişimlerini timeline'da özel "Takım değişti" olayı
  olarak vurgula (diff'ten türetilir, ayrı olay tipi gerekmez).
