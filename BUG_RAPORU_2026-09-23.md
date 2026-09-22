# BUG RAPORU 8 — Uçtan uca kod denetimi · 2026-09-23

Kapsam: `backend/src` + `frontend/src` tamamı (1301 kaynak dosya), commit edilmemiş HTTP monitör
değişiklikleri dâhil. Yöntem: altı eksen (eşzamanlılık, güvenlik, veri bütünlüğü, sayısal/zaman,
alarm zinciri, React) bağımsız ajanlarla paralel tarandı; her KRİTİK/YÜKSEK bulgu ve orta bulguların
çoğu kaynak dosya açılarak, çağrı zinciri izlenerek ve mümkün olan her yerde **kardeş yüzeyle
karşılaştırılarak** doğrulandı. Kod değiştirilmedi.

Taban: `BUG_RAPORU_7.md` (2026-09-10, on kullanıcı bildirimi) — tamamı kapalı, hiçbiri yeniden
açılmadı. `BUG_REGRESYON_2026-09-11.md` maddeleri de açık kalmadı. Bu rapordaki **51 bulgunun
tamamı yeni**.

## Genel değerlendirme

Sistemik bir çöküş yok. Bekçi testlerinin kapattığı iki en riskli alt başlık — tx'siz türetilmiş
silme ve `ddl-auto` NOT NULL/UNIQUE sapması — gerçekten kapalı: 80 repository ve 256 `nullable=false`
alanın tamamı tarandı, **ihlal bulunmadı**. SSRF kapısı, gövde tavanları, oturum sabitleme, executor
kapatma, kilit fail-closed davranışı, `PermissionCatalog` VIEW/EDIT ayrımı hep yerinde.

Bulguların ezici çoğunluğu **tek bir kalıbın tekrarı**: bir kural projede doğru yazılmış, bir
yüzeyde uygulanmış, kardeş yüzeye süpürülmemiş. On bir bulgu birebir bu biçimde; her birinde
"doğru" sürüm aynı kod tabanında duruyor ve düzeltme o satırın kopyalanmasından ibaret.

İkinci küme kanal paritesi: e-posta halkası bağlanmış, push halkası atlanmış. Bu kümenin toplam
etkisi ürünün en ciddi olayında (fırtına) yalnız push kullanan nöbetçinin **hiçbir bildirim
almaması**.

| Önem | Adet | Ne anlama geliyor |
|---|---|---|
| KRİTİK | 1 | Nöbetçiye ters bilgi: kapatılan izleme için telefona "düzeldi" gidiyor |
| YÜKSEK | 12 | Kimlik bilgisi sızıntısı, yetki kapısı boşluğu, ölü alarm kuralı, eksik bildirim |
| ORTA | 26 | Yanlış gösterim/hesap, dar yarış, bastırma paritesizliği, tx'siz çok-tablolu silme |
| DÜŞÜK | 12 | Latent, kozmetik, perf, zaman bombası adayı |

---

## KRİTİK

### K1 · `ScriptedAnomalyGuard.java:154-179` — otomatik kapatılan sentetik izleme için telefona "DÜZELDİ" gidiyor, "devre dışı" hiç gitmiyor

`disable()` önce `escalationService.resolveOpenAlertsSilently(...)` çağırıyor. O metot
(`EscalationService.java:768-782`) her açık alarmı kapatırken `enqueueResolvePushQuietly(saved)`
ile **"DÜZELDİ" push'u** yolluyor — bu davranış 2026-09-10 ürün kararıyla bilinçli. Hemen ardından
`notifyOwners` yalnız `emailService.sendAlert(...)` çağırıyor; `userPushService` tetiği yok.

**Neden bug:** yalnız push kullanan nöbetçi, k6 senaryosu anomalisi yüzünden **otomatik
kapatılmış** bir izleme için telefonunda "normale döndü" görür. İzlemenin artık hiç koşmadığını
hiçbir kanaldan öğrenmez. Sessiz kör nokta, üstelik ters yönde bilgi veriyor.

Ek olarak `notifyOwners` alıcıyı `teamAlertEmails(m.getTeamId())` ile çözüyor; bu
`collectTeamEmails(teamId, null, null)` demek, yani monitörün kendi `notificationGroupId`'si
**yok sayılıyor** — bildirim o monitörün normal alarmlarından farklı bir adres kümesine gidiyor.

**Çözüm:** `notifyOwners` içine takım bildirimi push'u ekle (`enqueueTeamNotice` deseni,
`AuditController` WEAK_ALGO örneği), alıcıyı `teamEmailsForMonitor(teamId, notificationGroupId)`
ile çöz. Sırayı da ters çevir: bildirim gitmeden alarm sessizce kapanmasın.

---

## YÜKSEK

### Y1 · `PageFetchCore.java:232` — izlemenin `Authorization` başlığı yönlendirmedeki yabancı host'a aynen gidiyor

`applyExtraHeaders(rb, opts.extraHeaders())` hop döngüsünün **içinde**: her yönlendirmede yeniden
uygulanıyor. `RESERVED_HEADERS` (`:357`) yalnız `user-agent, accept, accept-language, host,
content-length, connection` içeriyor — `authorization` ve `cookie` listede **yok**.
`extraHeaders`'ı `PageSpeedCheckerService.java:277-280` üretiyor ve orada
`h.put("Authorization", basicAuth)` var; değer `decryptSecret(m.getBasicAuthPassEnc())`, yani
veritabanında şifreli saklanan gerçek parolanın çözülmüş hâli.

**Neden bug:** Basic Auth ile kurulmuş bir Sayfa Hızı izlemesinin hedefi (ya da o hedefin DNS'ini
ele geçiren taraf) `302 Location: http://saldirgan.example/` döndürür; zamanlayıcı bir sonraki
turda `Authorization: Basic <base64(kullanıcı:parola)>` başlığını **açık metin HTTP** üzerinden
saldırgana yollar. Kullanıcı etkileşimi gerekmiyor, saldırganın hiçbir yetkisi olması gerekmiyor.

Projenin kendi gerekçesi bu başlıkları zaten sır kabul ediyor (`MonitoringController.java:3572`:
*"Özel başlıklar YALNIZ global admin: serbest başlık iç servislere yetki/SSRF yüzeyi açar"*).

**Çözüm:** `applyExtraHeaders`'ı yalnız ilk hop'ta — ya da `next` host'u ilk istekteki host ile
`equalsIgnoreCase` eşleştiğinde — uygula. Tarayıcıların cross-origin yönlendirmede `Authorization`
düşürmesiyle aynı kural. (Düşürme takibinin kendisi bu sınıfta bilinçli: `SafeRedirect` javadoc'u
*"PageFetchCore bugün düşürmeyi takip ediyor ve o davranış korunuyor"* diyor — bu bulgunun konusu
düşürme değil, host değişince kimlik bilgisinin taşınması.)

### Y2 · `KeywordCheckerService.java:189-194` — aynı sınıf: özel başlıklar her hop'ta, düşürme kontrolü yok

`sendFollowingSafely` döngüsünde `applyCustomHeaders(rb, customHeaders)` her turda koşuyor ve
`:194` yalnız `if (next == null) return resp;` diyor.

**Kardeş karşılaştırması bulguyu kesinleştiriyor:** aynı işi yapan `HttpCheckerService.java:346`
`if (next == null || SafeRedirect.isDowngrade(current, next)) return resp;` yazıyor.
`SafeRedirect.isDowngrade` javadoc'u bunu *"elle takibe geçen çağıranlar aynı davranışı korumak
için bunu kullanır"* diye tarif ediyor. Kural var, keyword checker uygulamıyor.

**Neden bug:** `monitoring.crud` varsayılan olarak USER'da açık. Bir kullanıcı keyword izlemesi
kurup `customHeaders` alanına iç servisin `X-Api-Key`'ini koyar; hedef `302 Location: http://...`
dönerse anahtar açık metin HTTP ile dışarı çıkar. Uç yanıttan 200 karakterlik `snippet` de
döndürdüğü için düşürülmüş hop'un içeriği kullanıcıya ulaşır.

**Çözüm:** `HttpCheckerService.java:346` satırını birebir uygula; özel başlıkları host değişince
düşür. İkisini tek bir yönlendirme-politikası kapı testine pinle.

### Y3 · `SystemController.java:209` — herhangi bir USER, başka kullanıcının giriş IP'sini, şehrini ve tarayıcı parmak izini çekebiliyor

`userTimeline` yalnız `permissionService.require(session, "system_health.read", "view")` istiyor.
Bu izin `PermissionCatalog.java`'da hem `teamAdminDefaults()` (:213) hem `userDefaults()` (:264)
içinde **varsayılan açık**. `UserActivityService.userTimeline` satır başına `ip`, `country`,
`city`, `user_agent`, `flags`, `outcome` döndürüyor.

**Neden bug:** `GET /api/system/user-activity/user/<kullanıcı>?limit=100` ile hedefin son 30
günlük giriş IP'leri, coğrafi konumu ve tarayıcı parmak izi çekilir. Kullanıcı adları
`users.list` (USER'da açık) ya da takım dizininden zaten elde edilebiliyor.

**Kardeş karşılaştırması:** aynı veriyi veren `AuditController.java:336 userDeviceLogins`
`requireAuditAccess(session)` + `audit_log.read` istiyor ve `requireAuditAccess` (:351-355)
`isGlobalAdmin || "AUDIT"` şartı koyuyor. Dahası `PermissionCatalog.java:216-218` yorumu bu
politikayı açıkça yazmış: *"audit_log.read: denetim kaydı sistem-geneli → yalnız global
admin/AUDIT erişebilir; TEAM_ADMIN'e verilmez."* `model/AppUser.java:196` da `lastLoginIp` alanını
`@JsonIgnore` ile işaretleyip gerekçesini yazmış: *"işaretlenmezse IP'ler TEAM_ADMIN'e de
açılırdı."* Politika üç yerde yazılmış, bu uçta uygulanmamış.

Aynı sınıf `SystemController.java:156 getUserActivity`'de de var: `maskEmployeeIds` yalnız
`employee_id`'yi siliyor, `active_users[].ip` maskesiz kalıyor.

**Çözüm:** her iki ucu `requireAuditAccess` kapısına bağla; ya da kapsamı koruyup
`maskEmployeeIds`'i `maskIdentity`'ye genişleterek global admin/AUDIT dışındaki oturumlarda
`ip`/`city`/`country`/`user_agent` alanlarını payload'dan düşür (kendi kaydı hariç).

### Y4 · `AppUserRepository.java:65` — "Oturumu Sonlandır" kararı ilk pod yeniden başlatmasında sessizce geri alınıyor

`UserService.terminateActiveSession` gerçek oturum satırını silmiyor; `activeSessionId` alanına
`TERMINATED:<uuid>` **sentinel'i** yazıyor ve kick, kullanıcının bir sonraki isteğinde
`AuthInterceptor.isSessionSuperseded` üzerinden uygulanıyor. Açılışta
`SchedulerService.java:335` → `UserService.clearAllActiveSessions()` →
`AppUserRepository.java:65` şu sorguyu koşuyor:

```
UPDATE AppUser u SET u.activeSessionId = null WHERE u.activeSessionId IS NOT NULL
```

Sentinel'i de siliyor. **Aynı dosyanın :57-58 sorgusu sentinel'i özellikle dışlıyor**
(`AND u.activeSessionId NOT LIKE 'TERMINATED:%'`) — kavram biliniyor, süpürmede atlanmış.

**Neden bug:** `application-prod.properties:11` `spring.session.store-type=jdbc`, timeout 24 saat.
Oturumlar PostgreSQL'de ve pod ölümünden sağ çıkıyor. Hesabı ele geçirilmiş bir kullanıcı
kick'lenir (tarayıcısı kapalı olduğu için oturum sunucuda 24 saat daha canlı). Rutin bir sürüm
pod'u yeniden başlatır → sentinel NULL olur → `isSessionSuperseded` false döner → istek geçer ve
`UserService.java:284 adoptSessionIfNone` oturumu yeniden sahipleniyor. Kick tamamen geri alınmış
olur; `rememberMeService.invalidateAllForUser` de kurtarmaz, çünkü elde geçerli oturum çerezi var.

Süpürmenin kendi yorumu varsayımını açıkça yazıyor: *"in-memory oturumlar restart'ı yaşamaz."*
Prod'da bu varsayım yanlış.

**Çözüm:** süpürme sorgusuna `:57` ile aynı sentinel korumasını ekle. Kalıcı çözüm:
`terminateActiveSession` kullanıcının `SPRING_SESSION` satırlarını da silsin ve JDBC store
aktifken açılış süpürmesi tamamen atlansın.

### Y5 · `CaAutoPinService.java:189` — paylaşılan güven-hatası haritasını yıkıcı boşaltma, eşzamanlı kontrollerin kaydını çalıyor

`TrustEvaluator.java:189` `onTrustFailure.accept(host, port)` çağrısını handshake iş parçacığında
yapıyor ve bu geri-çağrıyı **altı servis** aynı `CaAutoPinService` örneğine bağlıyor:
`HttpCheckerService:121`, `RdapDomainClient:93`, `RdapDomainExpiryService:79`,
`TrWebWhoisClient:74`, `UserPushService:757`, `WebhookService:46`. Hepsi tek
`recentTrustFailures` haritasına yazıyor. Haritayı **yalnız** `HttpCheckerService:235` okuyor ve
`drainRecentTrustFailures()` iterator ile her girdiyi siliyor — `it.remove()` zaman filtresinden
**önce**.

HTTP sweep'i `SchedulerService:2938`'de tüm monitörleri `certCheckExecutor` (core 20 / max 50)
üzerinde paralel başlatıyor.

**Neden bug — senaryo A (eksik alarm):** A ve B monitörleri aynı anda PKIX ile düşer, ikisinin de
yönlendirme hedefi haritaya yazılır. A'nın iş parçacığı drain'i önce çağırır, **ikisini birden**
alıp pinler ve kendi kontrolünü tekrarlar. B'nin drain'i boş döner; B'nin ana host pini zaten
güncelse `pinFromServer` false döner → `pinned=false` → B tekrar denenmez ve o tur HTTP_DOWN
yazar, oysa CA az önce pinlenmiştir.

**Senaryo B (yanlış atıf):** Webhook/user-push/RDAP çıkışında oluşan bir PKIX hatası da aynı
haritaya düşer; ilgisiz bir HTTP monitör kontrolü onu drain edip `reason="http-check"` ile TOFU
pinler. Hiç konuşulmamış bir host için, yanlış gerekçeyle güven kararı üretilir ve `CA_PINNED`
denetim kaydı o kontrole yazılır.

**Çözüm:** (a) drain'i yıkıcı olmaktan çıkar — `recentTrustFailuresSince(watermark)` girdileri
silmeden döndürsün, temizliği `FAILURE_WINDOW_MS` + periyodik prune yapsın (mükerrer pinleme zaten
`PIN_RATE_LIMIT_MS` ile no-op). (b) Kayda kaynak etiketi ekle:
`recordTrustFailure(source, host, port)`; `HttpCheckerService` yalnız `source="http"` girdilerini
okusun. Bir alt sistemin güven hatası başka alt sistem adına pin üretmesin.

### Y6 · `FailedLoginAnomalyService.java:173` — tamsayı bölmesi R6 görece-sıçrama kuralını tamamen öldürüyor

```java
long buckets = Math.max(1L, (baselineHours * 60L) / windowMinutes);
return baselineTotal / buckets;   // dönüş tipi long
```

Varsayılanlar `window-minutes=10`, `baseline-hours=24` → `buckets = 1440/10 = 144`.

**Neden bug:** son 24 saatte 143 başarısız giriş varsa `143/144 = 0` → `:150`'deki
`baselineAvg > 0` koşulu false → **R6 hiç tetiklenmez**. Kural ancak günde ≥144 başarısız giriş
varken canlanıyor. 1000/gün tabanda `1000/144 = 6` (gerçek 6,94) → efektif eşik `3×6 = 18`, doğrusu
`3×6,94 ≈ 20,8` → %14 erken tetikler. Ayrıca alarm e-postası
(`EmailNotificationService.java:719`) "Önceki dönem ort. **0** / 10 dk pencere" yazarak yöneticiyi
yanıltıyor.

**Çözüm:** `double avg = (double) baselineTotal / buckets;`, `AnomalyReport.baselineAvgPerWindow`
alanını `double`e çevir, kapıyı `baselineTotal > 0 && total >= relMul * avg` yap, rapor metninde
bir ondalıkla göster.

### Y7 · `MonitoringWeeklyStatsService.java:290/306` — ağırlıklı ortalama paydası ölçümsüz kontrolleri sayıyor: ne kadar çok monitör düşerse yanıt süresi o kadar iyi görünüyor

`:288` `total += t;` koşulsuz; `:290` `weightedMs += avg * t` yalnız `r[3] != null` iken;
`:306` `round1(weightedMs / total)`.

Besleyen sorgular (`HttpCheckRepository:61`, `PingCheckRepository:65`, `PageCheckRepository:61`,
`PageSpeedCheckRepository:79`) `COUNT(r)` ile `AVG(r.responseMs)` döndürüyor. `AVG` NULL'ları
atlıyor, `COUNT` saymıyor.

**Neden bug — sayısal örnek:** haftada 1000'er kontrol yapan 3 HTTP monitörü. İkisi sağlıklı
(AVG=400 ms), biri hafta boyu down (tüm `response_ms` NULL → AVG=NULL, COUNT=1000).
`weightedMs = 400×1000 + 400×1000 = 800.000`, `total = 3000` → rapor **266,7 ms** yazıyor, doğrusu
**400 ms**. Haftalık rapor, kesinti arttıkça performansı iyileşiyor gösteriyor.

**Karşı emsal:** `ActivityLogRepository:103` aynı hesabı `a.responseMs IS NOT NULL` filtresiyle
doğru yapıyor.

**Çözüm:** sorgulara ölçümlü satır sayacı ekle
(`SUM(CASE WHEN r.responseMs IS NOT NULL THEN 1L ELSE 0L END)`) ve ağırlık/payda olarak onu kullan.

### Y8 · `EscalationService.java:2388-2404` — sentetik çözüm mailinin ölçü alanları whitelist'te yok, şablon bloğu ölü kod

`EmailTemplateBuilder:460-473` (HTML) ve `:537-549` (düz metin) SCRIPTED çözüm mailinde
`duration_ms` ve `failed_checks` anahtarlarını okuyor. Çözüm yolu ctx'i
`deserializeContext(event.getContextJson())` ile okuyor, o JSON ise `snapshotContext`
whitelist'inden üretiliyor — whitelist'te bu iki anahtar **yok**.

Üretici tarafta ikisi de yazılıyor (`SchedulerService:4066`, `:4089`).

**Neden bug:** `SCRIPTED_SLOW` çözüm mailinde "Süre (alarm anı)" satırı asla çıkmıyor,
`SCRIPTED_FAIL` çözüm mailinde "Düşen Doğrulamalar" asla çıkmıyor. Kodun kendi yorumunun
(*"nöbetçi 'ne düzeldi' bilgisini alamıyor"*) düzeltmek istediği durum hâlâ geçerli.
(`error` anahtarı da whitelist'te değil ama `firstNonNull(error, last_error)` ile `last_error`'a
düşüyor, dolayısıyla "Hata (alarm anı)" satırı çalışıyor.)

**Çözüm:** whitelist'e `"duration_ms", "failed_checks"` ekle. Kapıyı sözleşme testiyle sabitle:
şablonun okuduğu her anahtar whitelist'te olmalı.

### Y9 · `StormService.java` — fırtına yolunda PUSH kanalı hiç yok

`StormService` bağımlılık listesinde (`:63-80`) `UserPushService` **yok**; dosyada
`userPushService` geçişi **sıfır**. `sendStormAlert`/`sendStormRecovery` yalnız e-posta + kontak
webhook'u gönderiyor. `EscalationService.processConfirmedOutage:1013-1018` fırtına üyesinde
`sendCombinedAlert`'i (dolayısıyla `triggerUserPush`'u) atlıyor.

**Neden bug:** ürünün en ciddi olayında (12 monitör birden düştü) yalnız kişi-push'u kullanan kişi
hiçbir bildirim almıyor. `EscalationService:735-746` yorumu durumu zaten kabul ediyor: *"push'un
toplu karşılığı YOK."* E-posta ↔ push paritesinin en büyük açığı.

**Çözüm:** `enqueueTeamNotice` tabanlı `storm` / `storm_resolved` push şablonu ekle, alıcıyı
`resolveRecipients` takımlarından çöz, `PushMessageContractTest`'e iki şablon anahtarını da pinle.

### Y10 · `StormService.java:517-545` — fırtına maili `mail_disabled` bastırmasını tanımıyor

Bireysel yolda `EscalationService.java:1795` olayın ctx'indeki `mail_disabled` damgasını okuyup
maili atlıyor (kullanıcı izleme formunda "E-posta"yı kapattığında). Fırtına yolu yalnız
`m.getTeamId()` + envanteri okuyor; `contextJson`'a hiç bakmıyor.

**Neden bug:** e-posta bildirimini bilinçli kapatmış bir takım, monitörü fırtınaya terfi ettiği an
toplu alarm + toplu "çözüldü" maili almaya başlıyor. Ayarın etkisi kalmıyor. Bastırma paritesi
kırık.

**Çözüm:** `resolveRecipients` içinde üyenin `contextJson`'ını deserialize edip `mail_disabled`
olanın takımını `addTeam`'e sokma (kontak webhook'u için `push_disabled` ile aynı ayrım).

### Y11 · `StormService.java:517-545` + `:603-611` — ACCOUNT kapsamlı fırtına maili takımlar arası hedef sızdırıyor

Varsayılan kapsam `SCOPE_ACCOUNT` (`:145`, `KEY_PER_GROUP` varsayılanı `false`).
`resolveRecipients` tüm üyelerin takım adreslerini birleştiriyor, `sendStormAlert:434` ise
`sampleTargets(downMembers)` ile üyelerin host/URL adlarını tek maile listeliyor.

**Neden bug:** Takım A'nın izlediği host adları, aynı fırtınadaki tüm diğer takımların adreslerine
gidiyor — ürünün geri kalanı takım-izole (`viewTeamIds`, `requireIncidentScope`) olduğu hâlde.

**Çözüm:** fırtına mailini takım başına parçala (aynı `AlertStorm`, alıcı takımına göre
filtrelenmiş üye listesi); ya da ACCOUNT kapsamında hedef listesini bastırıp yalnız sayı +
kök-neden gönder.

### Y12 · `EscalationService.java:985-992` ↔ `:1487-1489` — üç yoldan ikisi envanterin UG takımını ekliyor, açılış yolu eklemiyor

Açılışta ctx'te `team_id` varsa `ugTeamId = null` yapılıyor. `PORT_SLOW`, `PORT_DOWN`, `DNS_*`,
`PING_SLOW` standalone monitörlerinin hepsi ctx'e `team_id` yazıyor
(`SchedulerService:2720`, `:3269`). Çözüm yolunda (`sendResolutionNotification`) ve manuel
"Tekrar Bildir" yolunda bu tipler `isStandaloneMon` listesinde olmadığı için `else` dalına düşüyor
ve `ugTeamId = inventory.getUgTeamId()` okunuyor.

**Neden bug:** host'u cert envanterinde de bulunan bağımsız bir Port izlemesi düştüğünde alarm
maili yalnız Port izlemesinin takımına gidiyor; "✅ ÇÖZÜLDÜ" maili ise **alarmı hiç görmemiş UG
takımına da** gidiyor. "Tekrar Bildir" önizlemesi de onu gösteriyor.

**Çözüm:** çözüm/re-notify dallarında da ctx-damgalı takım varsa `ugTeamId`'yi null bırak; ya da
`isStandaloneMon`'u "ctx team_id damgası taşıyan tipler" sorusuna çevirip üç yolu tek kaynağa bağla.

---

## ORTA

### Kök neden kümesi: çok-tablolu kalıcı silme `@Transactional` taşımıyor

Üçü de aynı düzeltmeyle kapanıyor; desen `AdminController.purgeInventory`'de zaten mevcut.

- **O1 · `SchedulerService.java:3923-3943`** (`writeResourceBreakdown`) — `deleteByMonitorIdAndKeepReason` kendi tx'inde commit ediyor, `saveAll` ayrı tx'te. `saveAll` düşerse LATEST kırılımı silinmiş olur ve çağıran (`:3860`) istisnayı `log.warn` ile yutuyor → "Kaynak Kırılımı" ekranı bir sonraki başarılı kontrole kadar boş, kullanıcıya hata yok.
- **O2 · `MonitoringController.java:3305-3325`** (`deletePageSpeed`) — üç tablo, üç ayrı tx. `pagespeed_checks` 180 gün saklandığı için ikinci silme timeout'a düşerse kaynak kırılımı zaten kalıcı gitmiştir, monitör durur, kullanıcı 500 alır. Ters sırada öksüz `keep_reason='LATEST'` satırları retention'ın yaş kuralı dışında kalıp sonsuza kadar birikiyor.
- **O3 · `ScriptedTemplateController.java:335-356`** (`deleteTemplate`, `permanent=true`) — sürümler silinip şablon silme düşerse şablon **denetim izi olmadan** ayakta kalıyor; sürüm geçmişi `RetentionCoverageTest.EXEMPT` olduğu için başka hiçbir kural o kalıntıyı toplamıyor.

### Kök neden kümesi: kural bir yüzeyde uygulanmış, kardeşinde değil

- **O4 · `UserService.java:821`** — `manager_sicil` profil alanı yetki kapısı olmadan `managerId` yazıyor. Bu alan doğrudan yetkilendirme girdisi: `computeViewTeamIds:382` / `computeManageTeamIds:424` LDAP ADMIN için `ownPlusSubordinateTeamIds` döndürüyor, o da astların **tüm** `teamIds`'ini kapsama ekliyor. Kapsamlı müdür, yönetim kapsamındaki çok-takımlı bir kullanıcıyı `{"manager_sicil":"<kendi sicili>"}` ile kendine ast yapıp o kullanıcının diğer takımlarını da kapsamına alabiliyor. `requireTeamScopedAdmin` yalnız hedefin **birincil** takımına bakıyor. Çözüm: `managerId` yazımını ayır, yalnız global admin/LDAP senkronu yazsın (`customHeaders` deseni).
- **O5 · `MonitoringController.java:488`** (`restoreChange`) — kapı canlı kaydın değil, **tarihsel** geçmiş satırının takımından kuruluyor. İzleme B takımına aktarıldıktan sonra A takımının yöneticisi eski `seq` ile restore çağırıp B'nin canlı monitörünün `url`/`intervalSeconds`/`expectedStatus`/`active` alanlarını geri sarabiliyor. Çözüm: önce `findRestorable`, sonra canlı entity'nin `teamId`'siyle `canManage`.
- **O6 · `AppSettingsService.java:168`** — `GLOBAL_ONLY` anahtarları yalnız `isScopedAdminInRequest()` ile kapalı; TEAM_ADMIN/USER için bu **false** döner. Matristen `settings.general/edit` verilen bir role `allow-loopback-targets`, `trust.ca-bundle-pem`, `userpush.url` yazma yolu açılıyor. Katalog yorumu "yalnız global yönetici" diyor, kontrol bunu uygulamıyor. Çözüm: şartı tersine çevir — `!isGlobalAdmin` ise reddet.
- **O7 · `KeywordMonitor.java:83`** — `custom_headers` düz metin saklanıyor ve `MonitoringController.java:2573` liste DTO'sunda aynen dönüyor. Kardeşi `PageSpeedMonitor.java:124` `customHeadersEnc` (şifreli), yazımı global-admin kapılı, gösterimi yalnız `has_custom_headers` boolean'ı. Proje bu alanı sır kabul etmiş, keyword tarafı süpürmede atlanmış. Çözüm: `customHeadersEnc` geçişi + idempotent migrasyon + DTO'da boolean.
- **O8 · `MonitoringController.java:5078`** (+ `WeeklyAvailabilityReportService:377,387`) — `renewal_overdue` UTC ile hesaplanıyor; kardeş yüzeyler (`RenewalForecastService:198/219`, `CertificateCardExtrasService:124/255`) Europe/Istanbul kullanıyor. Prod'da her gece 00:00–03:00 arasında Vade Takvimi "gecikmiş", İzleme listesi "gecikmiş değil" diyor. Projede 24 kullanımdan 21'i IST. Çözüm: iki UTC çağrısını konvansiyona çek, kapı testiyle pinle.
- **O9 · `EscalationService.java:245-262`** (`processResults`) — bakım penceresi bastırması sertifika yolunda **yok**, ama çözüm mailini yine de susturuyor. Bakım penceresinde `CHAIN_BROKEN` alarmı açılıp mail + push gidiyor; zincir düzelince `resolveOpenAlertsForDomain:713` bakım açık olduğu için sessiz kapanışa düşüyor ve "✅ ÇÖZÜLDÜ" maili **gitmiyor**. Takım açılışı alıyor, kapanışı almıyor. Asimetrik bastırma.
- **O10 · `RetentionCatalog.java:195-201`** (`series-domain`) — baseline koruması `GROUP BY monitor_id`'yi yaşayan monitörlerle sınırlamıyor ve `domain_checks` için `…-orphan` kuralı yok. `MonitoringController.deleteDomain` monitörü kalıcı siliyor, satırlara dokunmuyor → silinen her monitör için 2 satır sonsuza kadar korunuyor. Kardeşi `series-dns` (:143-152) bu hatayı açıkça tespit edip düzeltmiş; süpürme bu yüzeye ulaşmamış.

### Kök neden kümesi: paylaşılan durum yarışı

- **O11 · `UserPushService.java:602-604`** — `drainOutbox` gönderim turundan hemen sonra `findTop50ByStatusOrderByIdAsc("PENDING")` sorgusunu tekrarlayıp boş değilse `worker.execute(this::drainOutbox)` ile **gecikmesiz** yeni tur kuyruklıyor. `fail()` retry satırlarını PENDING bıraktığı için tam da bu sorguya düşüyorlar. Tek-thread worker'da gecikmesiz görev planlanmış görevden önce koştuğundan 30/120 sn backoff **hiçbir zaman uygulanmıyor**; `retry-max` yükseltilirse tek arıza penceresinde push API'sine ardışık burst gidiyor. Çözüm: kuyruk-sonu tetiğini yalnız `sendBatch` başarılı olduğunda çalıştır (`anySent` bayrağı) ya da retry satırlarını `RETRY_AT` ile ayır.
- **O12 · `MonitoringOutageService.java:688/701`** (iptal `:403`) — `recoveryInFlight` hem "zincir sürüyor" guard'ı hem "dışarıdan iptal" sinyali, ama `ScheduledFuture` tutulmuyor: gerçek iptal yok. Sweep DOWN görüp anahtarı kaldırır, sonraki sweep UP görüp **yeniden ekler**; 1. zincirin kuyrukta bekleyen görevi ateşlenip `contains(key)` true bulduğu için çalışır ve kendi sayacıyla alarmı kapatır — arada gerçek bir DOWN görülmüş olmasına rağmen. İkinci yüz: `contains` yalnız girişte bakılıyor, `:703-707` `recheck().get()` döngüsü saniyeler sürüyor ve o pencerede gelen iptali görmüyor. Çözüm: `Set` yerine kuşak numarası (`ConcurrentHashMap<String,Long>`), `resolve` çağrısından hemen önce `gen == generation.get(key)` doğrulaması.
- **O13 · `SystemHealth.jsx:366-368`** — 5 dakikalık watchdog `setTimeout`'u ref'te tutulmuyor, iptal edilmiyor, unmount'ta temizlenmiyor ve kuşak bilgisi olmadan `fastPollRef.current`'i `clearInterval` ediyor. Tarama #1'in watchdog'u, t=290s'te başlayan tarama #2'nin canlı poll'ünü öldürüyor → panel "tarama sürüyor" görünümünde donuyor, elle yenileme gerekiyor.

### Kök neden kümesi: döngü içi tekil sorgu (tek pod / 100 eşzamanlı kullanıcı kısıtında)

- **O14 · `SchedulerService.java:2380-2393`** (`dueForScheduledSweep`) — `check_interval_hours` dolu her alan için ayrı `latestCheckRepo.findById(domain)`. Metot **5 dakikada bir** koşan stale süpürmesinden de çağrılıyor (:1493) ve o yol `findByCheckedAtGreaterThanEqual(cutoff)` ile zaten toplu okuma yapmış durumda. 1000 domain → 5 dakikada bir ~1000 tekil SELECT.
- **O15 · `WeeklyAvailabilityReportService.java:283-290/326-332`** — domain başına `latestCheckRepo.findById` + ayrı uptime sorgusu. 200 domain'li takımın haftalık raporu ~400 sorgu ve `WeeklyReportKpiService:235,260` bunu **ekran isteği içinde** çağırıyor.

### Frontend

- **O16 · `HttpMonitorPage.jsx:708`** *(commit edilmemiş diffle geldi)* — `ModalShell` koşullu render'ın dışında ve sekme düğmeleri `selCheck`'i temizlemiyor. Kullanıcı tanı penceresi açıkken "Alarmlar"a geçiyor (pencere kapanmış görünüyor), "Geçmiş"e dönünce **kendiliğinden yeniden açılıyor** — üstelik 30 sn'lik canlı yenileme listeyi tazelediyse artık listede olmayan eski bir satırın tanısıyla. Çözüm: `useEffect(() => setSelCheck(null), [detailTab])`.
- **O17 · `HttpMonitorPage.jsx:697`** *(commit edilmemiş diffle geldi)* — düğmeye sabit `aria-label={t('httpdiag.rowOpenAria')}` veriliyor; ARIA'da `aria-label` iç metni **eziyor**. Eskiden hücre içeriği ham hata metniydi; şimdi geçmişteki bütün başarısız satırlar ekran okuyucuda birebir aynı okunuyor. A11y regresyonu. Çözüm: `aria-label`'ı kaldır (iç metin zaten anlamlı) ya da `${detailText} — ${t('httpdiag.rowOpenAria')}` yap.
- **O18 · `App.css:1089` vs `:2632`** — `.modal-shell-overlay { z-index: var(--modal-z, 2000) }` ile `.modal-overlay { … z-index: 2000 }` aynı özgüllükte (0-1-0) ve `ModalShell` overlay'i **ikisini birden** taşıyor → kaynakta sonra gelen kazanıyor, `--modal-z` **ölü token**. `style={{'--modal-z': 2000 + parentDepth*10}}` hiçbir işe yaramıyor; iç içe modal'lar yalnız DOM sırası sayesinde tesadüfen doğru çiziliyor. Diffteki yorum ("ModalShell derinliğe göre katmanlıyor") yanlış. Çözüm: `.modal-shell-overlay` kuralını `.modal-overlay`'den sonraya taşı ya da `.modal-overlay.modal-shell-overlay` ile özgüllüğü artır.
- **O19 · `CheckHistoryTab.jsx:216`** — `onClick={h.reload}`; `h.reload` = `load(silent = false)`. React SyntheticEvent argüman olarak gidiyor → `silent` truthy → `if (!silent) setLoading(true)` atlanıyor. Backend hâlâ 500 veriyorsa kullanıcı "Yeniden dene"ye basıyor, ekranda hiçbir şey değişmiyor. Çözüm: `onClick={() => h.reload()}`.
- **O20 · `CheckHistoryTab.jsx:220`** — "Kayıt yok" boş durumu `LoadingBlock` ile çiziliyor; `Progress.jsx:129-137` koşulsuz `.pg-spinner` + `role="status"` basıyor. Gerçekten veri olmayan monitörde sonsuza kadar dönen spinner + "Kayıt yok"; ekran okuyucu bunu yükleniyor durumu gibi duyuruyor. **9 izleme türünü birden** etkiliyor (ortak bileşen). Çözüm: `StatusBlock tone="neutral" icon={Inbox}` (proje standardı, `HttpMonitorPage:560` uyguluyor).

### Diğer

- **O21 · `AlertNoiseService.java:59/87/157`** — olaylar UTC kayan pencereden (`Instant.now().minus(d, DAYS)`) çekiliyor, seri kovaları `LocalDate.now(IST)` ile kuruluyor ve `:87` `computeIfPresent` kullanıyor → haritada olmayan gün **sessizce düşüyor**. d=7, 2026-09-23 13:00 IST: `since = 09-16T10:00Z`, kovalar 09-17…09-23. 09-16 15:00 IST'te açılan alarm `total`a giriyor, `series`ten düşüyor. Her turda ~11 saatlik olay kümesi kayboluyor: KPI 120 diyor, kıvılcım çizgisinin toplamı 97. Çözüm: pencereyi de IST gününe hizala ya da `merge` kullan.
- **O22 · `HttpMetricsQueryService.java:127`** — `guard < 5000` zaman eksenini sessizce kırpıyor; `SystemController:112-120` `from/to/granularity`'yi hiç doğrulamıyor. `granularity=minute` + 7 gün = 10.080 kova → grafik ~3,5 gün çiziyor, aynı yanıtın `summary.total/p95` alanları 7 günün tamamını kapsıyor. Kırpıldığına dair bayrak yok. (Arayüz şu an `granularity` göndermediği için yalnız doğrudan API çağrısıyla tetikleniyor.) Çözüm: uçta aralığı granülariteye göre kırp + `"capped": true` (desen `MonitoringController.buildResponseSeries`'te var).
- **O23 · `UserPushService.java:914-928`** (`templateKeyFor`) — `PAGE_INTEGRITY`, `DNS_UNEXPECTED`, `DNS_INCONSISTENT`, `DOMAINMON_STATUS`, `DOMAINMON_UNKNOWN` hiçbir dala uymayıp `"down"` şablonuna düşüyor → telefona **"{seviye}: {ad} yanıt vermiyor"** gidiyor. E-posta aynı olay için "Sayfa Bütünlüğü Sorunu" / "DNS Beklenmeyen Değer" diyor. Sayfa/DNS ayakta, push "erişilemiyor" diyor → nöbetçi yanlış teşhise yönlendiriliyor. Bu, `HTTP_SSL`/`KEYWORD_SSL` için `:924-928`'de düzeltilen hatanın kalan örnekleri. Çözüm: eksik tipleri `changed`/yeni `degraded` şablonuna bağla, kapıyı `MonitorTypeCatalog.allAlertTypes()` üzerinden pinle.
- **O24 · `EmailNotificationService.java:4214-4217`** (`escHtml`) — yalnız `& < > "` kaçırıyor; kardeşleri `EmailTemplateBuilder.esc:1175` ve `MailCta.esc:116` `'` de kaçırıyor ve gerekçesini yazıyor ("href'ler hem tek hem çift tırnak içinde geçiyor"). `endpointLink` sonucu `"<a href='" + esc + "'…"` içine konuyor ve değer kullanıcının girdiği monitör URL'i. İçinde tek tırnak geçen URL attribute'ü kapatıyor: alarm mailinin başlığı bozuluyor, link kırılıyor. Çözüm: `escHtml`'e `.replace("'", "&#39;")` ekle.
- **O25 · `StormService.java:432-446/478-497`** — fırtına mailleri düz-metin alternatifi olmadan gidiyor (`sendHtml` → `helper.setText(html, true)`, tek part). Bireysel alarm/çözüm mailleri `multipart/alternative`. Düz metine düşen istemci/gateway'de en kritik bildirim boş görünüyor.
- **O26 · `StormService.java:287-298`** (`disband`) — fırtına aktifken kurtulan üyelerin bireysel çözüm maili bilinçli bastırılıyor ("toplu recovery fırtına dağılınca gider"). Admin `storm.enabled`'ı kapattığında `disband` yalnız hâlâ down olanları geri bağlıyor ve `sendStormRecovery` **çağırmadan** kapatıyor → zaten çözülmüş üyeler için toplu recovery asla gönderilmiyor. O takımlar "düştü" mailini aldı, "düzeldi"yi hiç almayacak.

---

## DÜŞÜK

- **D1 · `MonitoringControllerTest.java:3847,3856`** — **zaman bombası.** Fixture `"2026-10-15"`, iddia `renewal_overdue = false`. Üretim tarafı canlı `now()`den hesapladığı için **2026-10-16 00:00 UTC'den itibaren** iddia düşer; üretim koduna tek satır dokunulmadan CI kırmızıya döner. Doğru yazım emsali `WeeklyAvailabilityReportServiceTest:968` (`"2000-01-01"` — hep geçmiş). Çözüm: fixture'ı göreceli üret veya uzak gelecek seç.
- **D2 · `DomainCheckerServiceTest.java:75,209,236,243,250,257,267`** — çıplak `LocalDate.now()` (7 kullanım, süitteki tek dosya). Servis `atStartOfDay(ZoneOffset.UTC)` ile hesaplıyor, fixture zone'suz → CI'da (UTC) N-1, yerelde gece 00:00–03:00 arası N. Bugünkü iddialar eşiklerden uzak olduğu için ısırmıyor; bir adım kayınca "yerelde yeşil, CI'da kırmızı" üretir.
- **D3 · `ForecastDomainsPanel.jsx:62`** — `Date.parse(today + 'T00:00:00')` (Z yok) yerel okunuyor, `.toISOString()` UTC'ye kaydırıyor → İstanbul'da "bugün" çubuğunun ipucu **dünü** gösteriyor. Aynı sınıf hata `forecastModel.js:18`'de zaten düzeltilmiş ("yerel gün — toISOString UTC'ye kayardı"); bu dosya süpürmeden kaçmış. Çözüm: kardeşin `addDays`'ini kullan.
- **D4 · `CertificateService.java:906,917`** — `warning_count` ile `warning_domains` listesi uyuşmuyor: `daysRemaining == null` satırlar `warnings.size()` içinde ama hiçbir alt sayacı artırmıyor, çıkarma sonrası `warningOnly`ye düşüyor, `warningDomains`e eklenmiyor. Panoda "uyarı: 3", tıklanınca 2 alan adı. Çözüm: `warningOnly`yi çıkarmayla değil `warningDomains.size()` ile türet.
- **D5 · `PageUsageService.java:60 ↔ :94-98`** — `computeIfAbsent` ile `synchronized (a)` arası kayıp-güncelleme: kilit `Acc` nesnesinde, haritadaki varlıkta değil. Flush aynı `Acc`'i sıfırlayıp haritadan düşürürse sonraki `record()` yetim nesneye yazıyor, o ping DB'ye hiç gitmiyor. Çözüm: `computeIfPresent` ile atomik-koşullu kaldırma.
- **D6 · `HttpErrorDetail.jsx:31`** *(commit edilmemiş diffle geldi)* — `onClose` prop'u imzada ve düğme `{onClose && …}` ile çiziliyor ama **hiçbir çağrı yeri artık geçmiyor** (`HttpMonitorPage:710` ve `:874`). `App.css:8493` `.hdiag-close` kuralı da ölü. `test/HttpErrorDetail.test.jsx:23` hâlâ `onClose={onClose}` geçerek var olmayan yüzeyi test ediyor → yeşil test yanlış güven veriyor.
- **D7 · `HttpMonitorPage.test.jsx:48-84`** *(commit edilmemiş diffle geldi)* — CRLF→LF dönüşümünden 74 satırlık hayalet diff. `git diff --stat` 104 satır diyor, `git diff --ignore-cr-at-eol --stat` **30 ekleme**. Gerçek değişiklik gürültüde kayboluyor, `git blame` bozuluyor (bilinen proje tuzağı). Commit öncesi düzeltilmeli.
- **D8 · `CheckHistoryTab.jsx:214`** — başarısız canlı yenileme sessizce yutuluyor: hata dalı `h.error && h.items.length === 0` koşuluna bağlı, `useCheckHistory:75-77` hatayı yazarken eski `data`'yı koruyor. 30 sn'lik yenileme 500/403 almaya başladığında eski kayıtlar ekranda duruyor ve `hist-live` "Canlı" rozeti yanıp sönmeye devam ediyor. Kullanıcı bayat veriye canlı diye bakıyor ("bilinmiyor ≠ sorun yok").
- **D9 · `EscalationService.java:314`** — sertifika yolunda eskalasyon `acknowledged`'ı düşürüyor ama `acknowledgedAt`/`acknowledgedBy` bayat kalıyor. İzleme yolunun aynı dalı (`:1056-1058`) üçünü birden temizliyor. WARNING'de onaylanıp KRİTİK'e tırmanan olay "X tarafından <eski tarih> onaylandı" taşımaya devam ediyor.
- **D10 · `RetentionCatalog.java:210-215`** (`login-issue-images`) — `timeColumn="resolved_at"` tabloda **olmayan** bir kolonu gösteriyor. Silme doğru (`{t}` kullanılmıyor) ama `RetentionService.bounds()` sorgusu `catch (Exception)` ile yutuluyor → Ayarlar → Veri Saklama ekranında bu kural için "en eski/en yeni" kalıcı boş, operatör "veri yok" sanıyor. Yanlış kolonun sessizce yutulması `{t}`'li bir kurala kopyalanırsa gerçek veri kaybı üretir. Kapı yok. Çözüm: `AGE_VIA_PARENT` kurallarında `timeColumn`'u null bırak + `RetentionCoverageTest`'e (tablo, kolon) varlık kapısı ekle.
- **D11 · `MonitoringController` create uçları (7 tür)** — mükerrer koruması yalnız `existsDuplicate` + `save`, tx yok, DB UNIQUE yok. Çift tıklama / ağ tekrarında iki istek `false` okuyup ikisi de yazıyor → aynı takımda aynı URL için iki monitör, iki alarm. Kıyas: `certificate_inventory.domain` DB'de unique olduğu için envanter bu yarışa kapalı; `uq_pm_host_port`/`uq_dnsm_domain` kısmi indeksleri `WHERE standalone IS NOT TRUE` ile elle eklenen monitörleri kapsam dışı bırakıyor. Çözüm: tür başına idempotent kısmi UNIQUE indeks + önce `mergeDuplicates`.
- **D12 · `StormService.java:524-547`** — fırtına anında üye başına `inventoryRepo.findByDomain` + `contactsForLevel`. Takımlar `teamCache`'li, envanter/kontak değil. DB'nin en yüklü olduğu dakikada üye sayısı kadar tekil SELECT.

---

## Doğru bulunan (tarandı, temiz)

Kapsamın görünmesi için:

**Veri bütünlüğü.** 80 repository'de tx'siz türetilmiş silme **yok** (`RepositoryWriteTransactionGuardTest` kuralı elle yeniden koşuldu; allow-list dışı iki yeni çağıran da transactional). 256 `nullable=false` alanın hiçbiri dolu tabloya sonradan eklenmemiş. 11 `@UniqueConstraint`'in tamamı tablonun doğduğu commit'te eklenmiş. `AdminController.updateInventory` (25 alan) ve 9 türün `PUT` ucunda eksik setter yok. 43 retention politikasının (tablo, kolon) çifti şemayla uyumlu — tek sapma D10. Bileşik anahtarların hiçbirinde ayırıcı çakışması üretilemiyor. 8 türün `existsDuplicate` sorgusu `LOWER(...)`.

**Güvenlik.** `Redirect.NORMAL` üretimde hiç kalmamış. Dokuz izleme ucunda `denyIfNotViewable`/`canOperateTeam`/`canManage` zinciri eksiksiz. Toplu uçların (inventory/alerts/users/teams/incidents/weekly-reports/import) hepsi kayıt başına yetki çalıştırıyor. DNS/Port çift kaynak yetkilendirmesi `effectiveTeam` ile okuma/yazma/geçmiş uçlarında tutarlı. `PermissionCatalog` çok-eylemli satırları bilinçli ikiye bölmüş, AUDIT'e EDIT sızmıyor. `mustChangePassword` beyaz listesi dört uçla sınırlı ve remember-me oturumuna da uygulanıyor. Oturum sabitleme login'de `oldSession.invalidate()` ile kapalı. Gövde tavanları (sıkıştırma bombası dâhil) yerinde. `existsById` hiçbir yerde yetki kararı için kullanılmıyor.

**Eşzamanlılık.** 25 `@Scheduled`'ın tamamı `fixedDelay`/`cron` — `fixedRate` üst üste binmesi yok. Tüm dağıtık kilitler geçici DB hatasında `false` dönüyor (fail-open kapalı; yalnız "tablo yok" bilinçli fail-open). `certCheckExecutor` sınırlı kuyruk + sayaçlı CallerRuns → `RejectedExecutionException` imkânsız, havuz açlığı deadlock'u yok. `NetworkResolver`, `CertificateCheckerService`, `HttpPhaseProbe`, `ProcessProbe` hata dallarında `close()` simetrisi tam. `pinnedClients` LRU + kilit dışında `close()` + `@PreDestroy`. Rate-limit ve cache haritalarının hepsi tavanlı.

**Alarm zinciri.** `teamOnlyRecipients` tek doğruluk kaynağı olarak hem bireysel hem fırtına yolunda kullanılıyor — müdür/takım ayrışması kapalı. Eskalasyon seviyesi iki yolda da gönderimden **önce** kalıcılaşıyor. `SCRIPTED_FAIL` kanonik listesinde eksik halka yok: `MonitorTypeCatalog.ALERT_TYPES`, `IncidentsController.rootCause/family/tabFor`, `resolvedTypeLabel`, subject `typeTr`, `incidentMeta.js RC_META` — 10 türün tamamı bağlı. `PushText.slowFields` alias zinciri tüm yavaşlık üreticilerini karşılıyor. Outlook güvenliği: `rgba()` yok, `LIGHT_SCHEME_META` tüm gövdelerde, CTA'lar VML, arka planlar `td bgcolor` + solid hex.

**Sayısal.** `MonitoringController.uptimePct` (2 ondalık + `up<total → 99.99` koruması), `WeeklyAvailabilityReportService.percentile` (nearest-rank), `ExtendedHealthService.computeSmtpPeriod` (eski `/10L` kusuru düzeltilmiş), `StormService.computeThreshold` (`(t+1)/2` kasıtlı histerezis), `UserPushService.quietHoursBlock` (gece devrilen pencere + `s.equals(e)` koruması), `nextCertificateSweepAt` (cron ile aynı zone), tüm frontend yüzde hesapları (`Math.max(1,…)` / `total==0` korumalı).

**Frontend.** `useCheckHistory` `seqRef` fetch-yarışı koruması `.then/.catch/.finally` üçünde de doğru, sızan yükleme bayrağı yok. İç içe `ModalShell` Escape/odak/scroll-kilidi doğru (`useEscapeKey` `.modal-overlay` görünce dokunmuyor, scroll kilidi sayaçlı, odak iadesi `document.contains` korumalı). Scrim tıklaması iki modalı birden kapatmıyor. `Toast.jsx:97` context değeri `useMemo`'lu → `toast` dependency tuzağı yok. Satır anahtarları çakışmasız. 0↔1 tabanlı sayfalama tutarlı. Yeni test fixture'ı gerçek snake_case tel biçiminde. `.hdiag--modal`/`.hdiag-open*` sınıfları ve token'ları iki temada da tanımlı (`cssTokens` + `cssClasses` yeşil). `httpdiag.rowHide` silinmiş ve **kalan referans yok** (`i18n-parity` yeşil).

## Bilgi amaçlı (bulgu sayılmadı)

`ConfigHealthService.cronCoversDay:135` sarmalı gün aralığını (`FRI-SUN` → a=5, b=0) kapsayamıyor
ve yanlış `cron_misses_deadline` uyarısı üretebilir. Spring cron'da sarmalı DOW aralığı çok nadir.

---

## Önerilen düzeltme sırası

**Tur 1 — yayın öncesi kapatılmalı (kimlik bilgisi + yetki + ters bilgi).**
K1, Y1, Y2, Y3, Y4. Beşi de dar, yerel düzeltme; üçünde doğru sürüm kod tabanında zaten duruyor.

**Tur 2 — sessiz yanlış sonuç.**
Y5 (paylaşılan drain), Y6 (tamsayı bölmesi), Y7 (ağırlıklı ortalama paydası), Y8 (whitelist).
Dördü de "yeşil testle birlikte yanlış cevap veren" sınıfı.

**Tur 3 — kanal paritesi.**
Y9 (fırtına push), Y10 (`mail_disabled`), Y11 (takım sızıntısı), Y12 (`ugTeamId`), O23, O25, O26.
Y9 tasarım kararı gerektiriyor (şablon + alıcı kuralı), diğerleri mekanik.

**Tur 4 — tx + yarış.**
O1, O2, O3 tek commit'te (`@Transactional`). O11, O12, O13 ayrı ayrı.

**Tur 5 — commit edilmemiş diff.**
O16, O17, O18, D6, D7. Bunlar zaten çalışma kopyasında; commit'ten önce düzeltilmeli.

**Tur 6 — kardeş süpürmesi + kapılar.**
O4–O10, O19–O22, O24, D1–D5, D8–D12. D1 (zaman bombası) tarih geçmeden kapatılmalı.
