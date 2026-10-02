# SiteMonitor — Kod Bug Denetimi (5. Tur · v20.42.2 + commit edilmemiş iş)

**Tarih:** 2026-08-30 · **Yöntem:** `/bug-denetle` — 6 eksen 3 paralel ajanda (E2+E3 · E5+E1 · E6+E4),
her YÜKSEK/ORTA madde ana ajan tarafından kaynakta açılıp doğrulandı, baseline re-check edildi.
**Baseline:** `BUG_RAPORU_4.md` (N1–N12). Önceki turların Y1–Y9 / O9–O16 serisi kapalı, regresyon yok.

**Ortam notu.** Komutun 1. kuralı (kaynağı `tar` ile konteynere taşı) Claude Desktop + device-mount
kurulumu için yazılmış; bu koşumda ajanlar depo üzerinde doğrudan çalıştı. O kuralın önceki
koşumundan kalan `_review_src.tar.gz` temizlenmemiş ve depoya commit edilmiş — bulgu 22.

**Bu turun farkı.** Önceki dört tur yalnız commit edilmiş kodu denetledi. Bu tur ayrıca **41
dosyalık commit edilmemiş işi** (sertifika güvenlik bayrakları, push mesaj kurgusu, Port/DNS
envanter-bağı, envanter form eylem çubuğu, bildirim geçmişi gruplaması) taradı. **Bulguların
yarısı oradan çıktı** — yayına almadan önce koşmuş olmak turun en değerli tarafı.

## Genel değerlendirme

Sistemik bir açık yok. Kod olgun ve savunma desenleri (SsrfGuard, SafeRedirect,
`denyIfNotViewable`, kimlik sızıntısı kapısı, repository transaction guard) tutarlı biçimde
uygulanmış. Bulgular iki kümede toplanıyor:

1. **Kardeşleri doğru yapan ama bir uçta atlanmış kapılar** — en güçlü kanıt türü budur ve bu
   turda üç kez ortaya çıktı (bulgu 1, 6, 18).
2. **Bu oturumda eklenen kodun kanonik zincirdeki eksik halkaları** — 9 halkanın 8'i doğru
   bağlanmış, biri atlanmış tipi bulgular.

**34 bulgu: 9 YÜKSEK · 13 ORTA · 12 DÜŞÜK.** Kaynak etiketi: **[E]** mevcut kod, **[Y]** bu
oturumda eklendi ya da ağırlaştı.

---

# AÇIK BULGULAR

## YÜKSEK

### 1 · [E] `listPort`/`listDns` envanter satırlarını takım kapsamına HİÇ sokmuyor (IDOR)
`backend/.../controller/MonitoringController.java:1151-1160` ve `:1519-1528`

**Neden bug:** `monitoring.read` yetkisi olan **herhangi bir kullanıcı** (USER dâhil)
`GET /api/monitoring/port` ve `/dns` çağırdığında tüm aktif envanter domainlerini görüyor:
host:port, kayıt tipi, sorumlu takım adı, son kontrol sonucu ve açık alarm rozeti. Kendi takımıyla
hiç ilgisi olmayan alan adları listeleniyor.

**Kanıt:** Aynı metodun **içinde** standalone döngüsü süzüyor —
`if (!SessionScope.canView(session, m.getTeamId())) continue;` (`:1163`, `:1530`) — envanter
döngüsü süzmüyor. Yedi kardeş liste ucu da süzüyor: `listKeyword:1847`, `listHttp:2366`,
`listPage:2653`, `listPageSpeed:2906`, `listScripted:3438`, `listDomain:4336`, `listPing:4638`
(hepsi `.filter(m -> SessionScope.canView(...))` + `// IDOR (H2)` yorumlu). `CertificateController:58`
aynı envanteri `getAllLatestForTeams(SessionScope.viewTeamIds(session))` ile süzüyor.

**Çözüm:** envanter döngüsüne `denyIfDomainNotViewable`'ın liste karşılığını koy —
`if (!canView(inv.getTeamId()) && !(inv.getUgTeamId() != null && canView(inv.getUgTeamId()))) continue;`
`alarmHosts` kümesi de süzülmüş listeden kurulmalı.

### 2 · [Y] Manuel "Çalıştır" kontrolü İKİ KEZ koşturuyor ve iki kez kaydediyor
`MonitoringController.java:2528` (+ `:1337, :2024, :2827, :3064, :4808`) ↔ `SchedulerService.recheck*`

**Neden bug:** Uç kendi kontrolünü koşup satırı kaydediyor (`httpCheckRepo.save(res)`), ardından
bu turda eklenen `schedulerService.evaluate*Now(m)` çağrısı `recheck*`'i çalıştırıyor — o da
**kendi satırını kalıcılaştırıyor** (`recheckHttp` javadoc'u: *"HTTP uptime check + http_checks
persist'i"*). Tek tık → hedefe iki gerçek istek, geçmişe iki satır, **uptime yüzdesi / histogram /
yanıt-süresi grafiği manuel kontrolü çift sayıyor**, `activity_log`'a iki kayıt.

En ağırı sayfa izleme: `triggerPage` → `triggerPageCheck(m)` = `recheckPage(m, true, "SINGLE_PAGE")`
(`:3223-3225`), hemen ardından `evaluatePageNow` → `recheckPage(m, true, m.getMode())` — SITE_CRAWL
modunda **ikinci tam site taraması** (120 sn tavan) ve `page_checks` + `page_resource_issues`
ikinci kez yazılıyor. `triggerPageSpeedCheck` de aynı.

Tek doğru örnek: `evaluateDnsNow` — `recheckDns` bilerek persist etmiyor (`:4766`).

**Çözüm:** ucun senkron sonucunu `evaluate*Now`'a geçir
(`evaluateHttpNow(m, r)` → yalnız `handleSweepResults(type, List.of(httpSweepItem(m, r)), true)`),
ikinci `recheck*` çağrısını kaldır. Sayfa/hız için `triggerPageCheck` dönüşünü `addPageSweepItems`'a besle.

### 3 · [Y] Güvenlik alarmı, süre-bitişi alarmını KALICI olarak maskeliyor
`EscalationService.java:1442-1450` — *üç ajanın üçü de bağımsız buldu*

**Neden bug:** `determineAlertType` tek tip döndürüyor ve güvenlik dalı `EXPIRY`'nin **önünde**.
`HOSTNAME_MISMATCH` varsayılan AÇIK ve — REVOKED/CHAIN_BROKEN'ın aksine — **kalıcı** bir durum
olabilir (sertifika yalnız `www.x.com` kapsıyor ama izleme `x.com`; ya da paylaşımlı iç
sertifika). Böyle bir domain için her taramada güvenlik tipi dönüyor → sertifika 3 güne düşse de,
**süresi dolsa da EXPIRY alarmı hiç açılmıyor**.

Dahası: önceden açılmış bir EXPIRY olayı `openAlertByKey.get(domain + "|" + alertType)` ile
eşleşmediği için re-alert/eskalasyon almıyor; `resolveOpenAlertsForDomain` yalnız
`alertType == null` dalında (`:247`) çağrıldığından **hiç kapanmıyor** — sonsuza dek "açık ama
sessiz". Bir sertifika izleme aracının birincil vaadini sessizce kapatıyor.

**Çözüm:** `determineAlertTypes(result) → List<String>` ile domain başına birden fazla tip üret ve
döngüyü tip başına koştur (auto-resolve zaten `CERT_ALERT_TYPES`'ın tamamını kapsıyor).
Asgari düzeltme: güvenlik dalını `EXPIRY` kontrolünden **sonraya** al.

### 4 · [E · N12 ağırlaştı] "Sır" kutusunu kaldırmak kayıtlı token'ı `*****` ile KALICI eziyor
`UserPushController.java:271` (maskeleme) + `:299-307` (`encryptHeaders`)

**Neden bug:** Sunucu sır başlıklarını `MASKED = "*****"` olarak döndürüyor. Kullanıcı kayıtlı bir
`Authorization` başlığının "sır" onay kutusunu **kaldırıp** kaydederse istemci
`{name:"Authorization", value:"*****", secret:false}` gönderir; `encryptHeaders` `else { stored = val; }`
dalına düşer → şifreli token **geri alınamaz biçimde silinir**, yerine `*****` yazılır. Sonraki her
push isteği `Authorization: *****` ile gider ve tüm teslimatlar FAILED olur. `existingSecrets`
yalnız `secret=true` satırlardan doldurulduğu için kurtarma yolu da yok.

Baseline N12 "bozabilir · DÜŞÜK · doğrulanmalı" idi; bu turda **kalıcı veri kaybı** olarak
doğrulandı.

**Çözüm:** `MASKED.equals(val)` kontrolünü `secret` dalının **dışına** al — maskeli değer geldiyse
bayraktan bağımsız olarak mevcut değer korunsun; `existingSecrets`'i tüm satırlardan doldur.

### 5 · [E] Push teslimat damgaları arayüzde +3 saat görünüyor
`UserPushService.java:224, 429` ↔ `frontend/src/api/client.js:1085-1087`

**Neden bug:** Kod tabanında **iki çelişen damga geleneği** var:
`AlertEvent.createdAt` ve `MonitoringOutageService` **UTC** yazıyor
(`ISO.withZone(ZoneOffset.UTC)`), `UserPushDelivery.createdAt/sentAt` ise **İstanbul yerel**
yazıyor (`Instant.now().atZone(ZONE).toLocalDateTime()`). Arayüzdeki `toUtc` zone taşımayan her
damgaya `'Z'` ekliyor → 14:03'te giden bir push teslimat günlüğünde **17:03** görünüyor; aynı
ekrandaki alarm saati doğru olduğu için ikisi yan yana 3 saat kayık duruyor. Aynı asimetri CSV
export'ta da var (`UserPushController.java:213-222`).

Bu turda push **süre hesabı** düzeltildi (`PushText.parseStoredUtc` doğrulandı); **gösterim** hâlâ bozuk.

**Çözüm (kök):** `UserPushService` damgalarını UTC'ye çevir (`EscalationService` ile simetrik).
Tek düzeltme bulgu 5'i, 11'i ve CSV'yi birden kapatır.

### 6 · [E · N10'un ikizi] Çözüm yolu push tetiğini düşürüyor
`EscalationService.java:1323-1326` ↔ `:1426`

**Neden bug:** `sendCombinedAlert`'te N10 kapatıldı (`:1607-1616` artık `allEmails.isEmpty()` olsa
bile `triggerUserPush` çağırıyor). Aynı kusur `sendResolutionNotification`'da **duruyor**:
`if (allEmails.isEmpty()) { log.warn(...); return; }` erken dönüşü, `:1426`'daki
`userPushService.enqueueResolve(...)` çağrısından önce. E-posta alıcısı olmayan takım açılış
push'unu **alıyor** ama "DÜZELDİ" push'unu **asla** almıyor → telefonda alarm sonsuza dek açık kalıyor.

**Çözüm:** erken dönüşten önce `enqueueResolve` çağır — `sendCombinedAlert`'teki desenin aynısı.

### 7 · [E, Y ile ağırlaştı] PORT/DNS alarmlarında açılış ile çözüm FARKLI takım çözüyor
`EscalationService.java:2118-2123` (`isStandaloneMon`) + `:1293` + `:370`

**Neden bug:** `isStandaloneMon` KEYWORD/PING_DOWN/HTTP_DOWN/HTTP_SSL/DOMAIN_EXPIRY/domainmon/
page/scripted/pagespeed içeriyor ama **`TYPE_PORT_DOWN`, `TYPE_PORT_SLOW`, `TYPE_DNS_*`
içermiyor**. Açılışta takım ctx'ten alınıp `event.setTeamId(...)` ile damgalanıyor; çözüm
(`:1293`) ve "Tekrar Bildir" (`:370`) ise envanter koluna düşüp `inventoryRepo.findByDomain(...)`
yapıyor. Envanterde **olmayan** bir host için `domainTeamId = null` → alıcı listesi boş → çözüm
bildirimi kimseye gitmiyor (bulgu 6 yüzünden push de gitmiyor), "Tekrar Bildir" önizlemesi boş geliyor.

**Bu turla etkileşim:** kusur zaten kullanıcı-eklediği standalone Port/DNS monitörlerinde
ulaşılabilirdi; ancak bu turda eklenen `MonitoringController.detachIfIdentityChanged` tam olarak
"envanterde bulunmayan Port/DNS monitörü" üretiyor — host düzenleyen her kullanıcı bu duruma
giriyor. Latent kusur **rutin** hâle geldi.

**Çözüm:** `isStandaloneMon`'a PORT/DNS tiplerini ekle; ya da üç yol da önce `event.getTeamId()`'e
baksın, yalnız null ise envantere düşsün (tek doğruluk kaynağı).

### 8 · [Y] `MON_FIELDS`'e eklenen `standalone`, `restoreChange` ile yazılabilir hâle geldi
`MonitoringController.java:453` + `:116-124` + `:471-472`

**Neden bug:** `updateDns` envanter-türevi (`standalone != true`) bir monitör için
`requireAdmin(session)` istiyor (`:1599-1603`), `deleteDns` de öyle. `restoreChange` ise yalnız
`SessionScope.canManage(session, row.getTeamId())` istiyor ve `applySnapshot`'ın skip kümesi
`Set.of("teamId", "groupName")` — bu turda `MON_FIELDS`'e eklenen `"standalone"` dahil değil.

Sonuç: bir **TEAM_ADMIN**, `PUT /api/monitoring/dns/{id}`'nin reddettiği alanları
`POST /api/monitoring/changes/dns/{id}/{seq}/restore` ile değiştirebiliyor ve monitörün **yetki
sınıfını** çevirebiliyor (standalone → yalnız takım yönetimi + gerçek silme kapsamı). Ayrıca
`standalone=false`'a dönüş, bu arada `listDns`'in ürettiği envanter-türevi satırla
`uq_dnsm_domain … WHERE standalone IS NOT TRUE` kısıtını çakıştırıp 500 üretir; restore domain'i
geri alırken `resolveOpenAlertsSilently` çağrılmadığı için öksüz/asla kapanmayan alarm bırakır.

**Çözüm:** skip kümesine `"standalone"` ekle (kimlik/yetki alanı — `teamId` ile aynı sınıf);
`restoreChange`'e DNS/Port için `updateDns` ile aynı kapıyı koy; domain değiştiyse
`resolveOpenAlertsSilently` çağır.

### 9 · [E · N1 AÇIK] SQL Oyun Alanı `SELECT … INTO` ile salt-okunur güvencesini deliyor
`SqlPlaygroundService.java:31-36`, `:72-110`

**Neden bug:** `FORBIDDEN` kalıbında hâlâ `into` yok
(`insert|update|delete|drop|alter|truncate|create|…`). `SELECT * INTO yeni_tablo FROM app_users`
üç kontrolü de geçer (SELECT ile başlar, tek statement, kara-listeye takılmaz) ve yeni tablo
oluşturup veri kopyalar. `execute()` üzerinde ne `@Transactional(readOnly=true)` ne
`connection.setReadOnly(true)` var — DB katmanı da durdurmuyor. Baseline'dan **değişmeden** taşındı.

**Çözüm:** sorguyu salt-okunur bağlantıda çalıştır (kesin çözüm) + `\binto\b` kara-listesi
(savunma derinliği). Tek başına regex yetmez.

---

## ORTA

### 10 · [Y] İki yeni güvenlik ayarı Genel Ayarlar'da KAPALI görünüyor (biri AÇIK çalışıyor)
`AppSettingsCatalog.java:233-234` ↔ `application.properties` (satır YOK) ↔ `GeneralSettings.jsx:31, 50`

Bu turda eklenen `site.monitor.trust.alert-hostname-mismatch` (kod varsayılanı **true**) ve
`alert-untrusted` (**false**) katalog'a girdi ama `application.properties`'e girmedi. Zincir:
`getCatalogForClient():125` → `default = environment.getProperty(key)` = **null** →
`valueOf` → `''` → `const on = String('') === 'true'` = **false** → anahtar KAPALI çiziliyor.

Davranış bugün doğru (kod varsayılanı kullanılıyor), ama **ekran sistemin gerçeğini yanlış
gösteriyor**: admin, açık olan bir güvenlik alarmını kapalı sanıyor; "açmak için" tıklayıp
vazgeçerse geriye açık bir `false` yazıp alarmı **sessizce kapatır**.

Karşılaştırma: katalogdaki her BOOL'un properties satırı var — `:282` (`auto-pin.enabled`),
`:415` (`allow-internal-targets`). Sapma yalnız bu iki yeni anahtarda.

**Çözüm:** `site.monitor.trust.alert-hostname-mismatch=${TRUST_ALERT_HOSTNAME_MISMATCH:true}` ve
`site.monitor.trust.alert-untrusted=${TRUST_ALERT_UNTRUSTED:false}`. Davranış değişmez, ekran
doğruyu söyler, Helm env ile ezebilir hâle gelir.

### 11 · [Y] Yeni `PushDeliveryGroup` ham ISO damgası basıyor
`frontend/src/components/admin/AlertHistory.jsx:127`

`{head.sent_at || head.created_at}` hiç biçimlendirilmeden ekrana yazılıyor; kullanıcı
`2026-08-30T14:03:22` görüyor. Hemen üstündeki e-posta kartı aynı bilgiyi `fmtDateTime` ile
locale'e göre basıyor — aynı modalda iki farklı zaman dili. **Çözüm:** bulgu 5 kökten
düzeltilirse `fmtDateTime` paylaşılabilir; o zamana kadar zone eklemeyen bir yardımcı.

### 12 · [Y] Güvenlik alarmının e-posta konusu ve hero'su "1775 GÜN KALDI" diyor
`EscalationService.java:1716-1723` + `EmailTemplateBuilder.java:215-224`

`daysSeg = daysRemaining == null ? levelTr : (days + " GÜN KALDI")`; `processResults` bu alanı her
zaman dolduruyor ve güvenlik alarmları tanımı gereği "süresi uzak ama kabul edilemez"
sertifikada çıkıyor. Konu: `[Site Monitor] 1775 GÜN KALDI · host · Alan Adı Uyuşmazlığı` — KRİTİK
etiketi kayboluyor, konu özelliğin gerekçesinin **tam tersini** söylüyor. Gövdede de 72 px'lik gün
sayacı + progress bar basılıyor; bu turda eklenen `heroLabel` satırları
(`EmailTemplateBuilder:167-168`) **ulaşılamaz ölü kod**.

**Çözüm:** `isDurationAlert(alertType)` yüklemi — REVOKED/MISMATCH/CHAIN_BROKEN + iki yeni tip
`false` dönsün; `daysSeg` ve hero dalı bu tek yüklemden karar versin.

### 13 · [Y] `notifyEmail=false` izleme çözüm mailini YİNE alıyor (bastırma paritesi kırık)
`EscalationService.java:1289-1433` + `snapshotContext:2097-2103`

`sendCombinedAlert` `mail_disabled` damgasını okuyup maili atlıyor (`:1604-1616`).
`sendResolutionNotification` bu bayrağı **hiç kontrol etmiyor** — edemezdi de: `snapshotContext`'in
kalıcılaştırdığı anahtar listesinde `mail_disabled` **yok**, `MONITORING_ALERT_TYPES` dalında ctx
zaten `null`'a çekiliyor. Sonuç: kullanıcı e-postayı kapattığı monitör için alarm maili almıyor
ama her kapanışta "ÇÖZÜLDÜ" maili alıyor. Push tarafı `enqueueResolve`'daki "önce SENT olmalı"
kuralıyla korunuyor — iki kanal ayrışmış.

**Çözüm:** `snapshotContext`'e `mail_disabled` (ve `push_disabled`) ekle; çözüm yolunda
`allEmails` kontrolünden önce aynı dalı uygula.

### 14 · [Y] `DOMAINMON_UNKNOWN` kurtarma aralığı ayarı ÖLÜ
`SchedulerService.java:4425-4429` ↔ `MonitoringOutageService.recoveryIntervalMsFor:462-472`

`confirmCtx` sarmalayıcısı `domainItem`'dan kaldırılırken (bilinçliydi — eşik alarmlarını
geciktiriyordu) `monitor_recovery_interval_ms` anahtarı da düşmüş — **git diff ile teyitli**.
`recoveryIntervalMsFor` null görüp **aktif kurtarma yerine pasif sayaca** düşüyor; Domain izleme
formundaki "kurtarma aralığı" alanı DOMAINMON_UNKNOWN için hiçbir şey yapmıyor. Kodun kendi
yorumunun uyardığı *"notify_email hatasının aynısı"* durumu.

Kardeş kurucuların **hepsi** iki anahtarı da set ediyor: `:2717/2719`, `:2803/2805`, `:2839/2841`,
`:2878/2880`, `:3035/3037`, `:3286/3288`, `:3592/3594`.

**Çözüm:** transient dalda
`if (m.getRecoveryIntervalSeconds() != null) ctx.put("monitor_recovery_interval_ms", m.getRecoveryIntervalSeconds() * 1000L);`

### 15 · [Y] Push şablonu güvenlik alarmına "yanıt vermiyor" diyor; kanıt bloğu hiç ulaşmıyor
`UserPushService.java:651, 672-679` + `PushText.reasonOf` (120 karakter kırpması)

`templateKeyFor` yalnız EXPIRY/CHANGED/SLOW ailelerini tanıyor; sertifika tipleri
`default -> "down"` dalına düşüyor → *"KRİTİK: host **yanıt vermiyor**. Başlangıç …"*. Site pekâlâ
yanıt veriyor; sorun sunulan sertifika. Push, alarmın kendi metniyle çelişiyor ve operatörü yanlış
teşhise yönlendiriyor.

Ayrıca `securityEvidence`'ın ürettiği `[çözümlenen IP: …; sunulan CN: …]` bloğu — özelliğin ana
kanıtı — mesajın **sonunda** olduğu için `reasonOf`'un 120 karakter kırpması onu **her zaman**
düşürüyor; 200 karakter tavanı devreye bile girmiyor.

**Çözüm:** `templateKeyFor`'a `cert` ailesi ekle (`{seviye}: {ad} sertifikası kabul edilemez - {neden}`)
ve kanıtı `ctx`'ten ayrı bir yer tutucuyla taşı.

### 16 · [Y] Manuel senaryo/sayfa tetiği çift k6 koşumu → teyit zinciri sessizce iptal olabiliyor
`MonitoringController.java:3856-3870` + `SchedulerService.java:2780-2789`

Bulgu 2'nin en zararlı özel hâli. `triggerScripted` önce `evaluateScriptedNow(m)`
(içinde `recheckScripted(m, true)` = gerçek k6 koşumu), hemen ardından
`triggerScriptedCheckAsync(m)` ikinci koşumu başlatıyor. Tek tıkla iki k6 süreci, iki
`ScriptedCheck` satırı, **iki permit**. Permit havuzu sınırlı; ikisi yarışınca `recheckScripted`
`skipped` dönebiliyor ve `runConfirmAttempt` "kanıt yok ⇒ zinciri iptal et" diyerek manuel
değerlendirmenin **tek amacını** (alarm açmak) sessizce düşürüyor.

### 17 · [Y] `detachIfIdentityChanged` büyük/küçük harfe duyarlı
`MonitoringController.java:1442, 1450` ↔ `:1608`

Karşılaştırma `Objects.equals(prevDomain, m.getDomain())`; oysa **aynı metottaki** `updateDns`
guard'ı aynı soruyu `equalsIgnoreCase` ile soruyor. `Example.com` → `example.com` düzenlemesi
(aynı hedef) envanter bağını koparıp mükerrer satır doğuruyor — düzeltmenin engellemeyi amaçladığı
sessiz-mükerrer sınıfının ta kendisi, yalnız tetikleyicisi değişmiş.

**Çözüm:** `equalsIgnoreCase`; daha iyisi host/domain'i yazma yolunda
`toLowerCase(Locale.ROOT)` ile normalize et (dedupe, alarm anahtarı ve envanter eşleşmesi de faydalanır).

### 18 · [E] `updatePort` mükerrer `host:port` kontrolü yapmıyor
`MonitoringController.java:1223-1270`

`createPort` `findFirstByHostAndPortOrderByIdAsc(...)` ile reddediyor (`:1180-1182`), `updateDns`
`(domain, recordType)` için aynı guard'ı taşıyor (`:1612-1615`); `updatePort`'ta hiçbir kontrol
yok. Kullanıcı mevcut bir standalone monitörün host/port'unu başkasınınkiyle aynı yapabiliyor →
aynı hedefi iki kez izleyen, iki kez alarm/geçmiş üreten mükerrer satır. DB kısıtı da yakalamıyor
(`uq_pm_host_port … WHERE standalone IS NOT TRUE`).

### 19 · [E] Port kartından silme BAŞARISIZ olursa kullanıcı hiçbir şey görmüyor
`frontend/src/components/PortMonitorPage.jsx:245` ↔ `:732`

Kart silme `deleteMonitor(m)`'ı çağırıyor ama hata yolu `setSaveError(...)` yazıyor; `saveError`
**yalnız düzenleme modalinin içinde** render ediliyor. Karttan silerken modal kapalı olduğu için
DELETE 403/409 dönse bile **hiçbir toast/banner çıkmıyor**, satır listede kalıyor, kullanıcı
silindi sanıp tekrar deniyor. DNS ikizi (`DnsMonitorPage.jsx:252`) aynı senaryoda `toast.error`
atıyor. (Faz C kart dönüşümünde `del()` → `deleteMonitor(m)` refaktörünün açığı.)

### 20 · [E] Sertifika tablosu ile kart AYRI eşik kullanıyor; süresi DOLMUŞ sertifika "Uyarı" görünüyor
`CertificatesTable.jsx:179` ↔ `CertificateCard.jsx:23-25` / `CertificateService.java:298-307`

Tablo: `isCritical = days >= 0 && days <= 30` sabit; sunucudan gelen `alert_level` hiç okunmuyor.
Kart: `alert_level` öncelikli, `critical <= 7` / `high <= 15` (eşikler ayarlanabilir).
Sonuç (a) `days = 20` → tabloda **Kritik**, kartta Yüksek/Geçerli. (b) `days < 0` (**süresi
dolmuş**) → `days >= 0` tutmadığı için `isCritical = false` → satır "Uyarı" görünüyor; tabloda
"Süresi doldu" durumu **yok**.

**Çözüm:** `TableRow`'u `cert.alert_level`'a bağla (`expired/critical/high/warning/valid` zaten
DTO ile geliyor); sabit 30'u ve yerel merdiveni kaldır.

### 21 · [E] Kimlik sızıntısı kapısı gerçek TAKIM ve KİŞİ adlarını taramıyor
`backend/src/test/java/com/sitemonitor/IdentityLeakGuardTest.java:48-51`

**Önce bir düzeltme (yanlış-pozitif eleme):** ön taramada "kurumsal alan adı kaynakta geçiyor"
diye sayılan 10 satır **bulgu değil** — kapı o deseni tarıyor ve satırlar gerekçeli muafiyet
listesinde (süit yeşil). Kapı iyi tasarlanmış: desenler parçalı yazılmış ki kapının kendisi
sızıntı olmasın, muafiyet listesi "yalnız küçülür" kuralına bağlı, ölü-kayıt testi var.

**Gerçek boşluk kapsamda.** `FORBIDDEN` yalnız üç desen arıyor: kurum adı / iç ağ soneki / iç
platform adı. Kural 0 ise açıkça *"takım isimleri mail adresleri vs"* diyor. Taranmayanlar:

| Tarama dışı | Ölçüm | Örnek konum |
|---|---|---|
| Gerçek **takım adları** | 45 satır / 10+ dosya | çoğu test fixture; **üretim javadoc'u**: `LdapProvisioningService.java:216-217` |
| Gerçek **kişi adı** ve kurum içi posta kutusu yerel-adı | 3 dosya | iki frontend test dosyası + bir backend test dosyası |

Git geçmişi kalıcı olduğu için (kapının kendi javadoc'unun gerekçesi) sayı sessizce büyüyor —
bu oturumda dokunulan test dosyalarından ikisi zaten örnek.

**Çözüm:** `FORBIDDEN`'a takım öneki ve kişi-adı fixture desenleri ekle; hepsini yer tutucuya
çevir (`Takım A`, `N00001`, `example.com`). Muafiyet mekanizması hazır — bu, kapıyı
**genişletmek**tir, yeni bir kapı kurmak değil.

### 22 · [E] Depodaki kaynak arşivi kimlik kapısının kör noktası
`_review_src.tar.gz` (depo kökü, `90c07ae2` ile commit edilmiş)

988 dosyalık tam kaynak anlık görüntüsü; `/bug-denetle` komutunun 1. kuralının temizlenmemiş
artığı, `.gitignore`'da yok. `IdentityLeakGuardTest.SCAN_EXT` listesi
`.java .jsx .js .json .md .yaml .yml .properties .css .sql` — **`.gz` yok**, yani kapı arşivin
içine bakamıyor. Arşivde bugün **18 kimlik izi satırı** var (çalışma ağacındaki sayıyla aynı —
muafiyetli kümenin donmuş kopyası).

Sorun bugünkü içerik değil, **yapısal körlük**: arşiv bir kez daha üretilirse o anki sızıntıyı da
içine alır ve kapı hiçbir şey göremez; muafiyet listesinin "yalnız küçülür" cırcırı arşiv için
işlemez. Ek olarak her klonda 3.3 MB ölü ağırlık ve kaynağın **bayat ikinci kopyası** — denetim
ajanları aynı kodu iki sürümde görüp yanlış satır numarası raporlayabilir.

Aynı sınıftan daha hafif: kökte 6 `BUG_RAPORU*.md` + `FAZ_A_SONUC.md` + `FAZ_B_SONUC.md` izleniyor.

**Çözüm:** `git rm --cached _review_src.tar.gz` + `.gitignore`; **komutun 1. kuralına "arşivi
depoya alma / bitince sil" adımını yaz** (kök neden orası). Geçmişten silmek ayrı bir ops kararı.

---

## DÜŞÜK

- **23 · [Y]** `{degisen}` yer tutucusu hâlâ ham `event.getMessage()` kullanıyor —
  `UserPushService.java:700` ↔ `:693`. `{neden}` bu turda düzeltildi, ikizi atlandı → telefona
  *"KRİTİK: example.com - UYARI: example.com DNS kaydı değişti değişti."* düşüyor.
  **Çözüm:** `degisen`'e de `PushText.reasonOf` uygula.
- **24 · [Y]** `groupPushRows` anahtarında alıcı kimliği yok — `AlertHistory.jsx:82-90`. Aynı
  alarma iki kez "Tekrar Bildir" basılırsa tek gruba düşüp "2 alıcı" yazıyor.
  **Çözüm:** `new Set(rows.map(r => r.username)).size` ya da anahtara `batch_id`.
- **25 · [Y]** Test push'u 200 karakter tavanına kırpılmıyor — `UserPushService.java:338` ↔ `:709`.
  Admin uzun şablonu testte tam görüyor, gerçek alarmda sessizce kesiliyor.
- **26 · [Y]** Port kart silme düğmesinde çift-tık koruması yok; DNS ikizinde `disabled={deleting === m.id}` var.
- **27 · [Y]** İç içe `mon-actions` sarmalayıcısı (`DnsMonitorPage.jsx:503`, `PortMonitorPage.jsx:472`)
  — `MonitorCardActions` kendi kökünü de `mon-actions` yapıyor; `ScriptedMonitorPage:1015`'te dış
  sarmalayıcı yok → üç sayfa arasında `gap`/`margin` sapması.
- **28 · [E]** `WebhookService.java:36-49` — `@PostConstruct` ile kurulan `HttpClient`'ta
  `@PreDestroy` kapatması yok (`UserPushService.shutdown():124-129` deseniyle asimetrik).
- **29 · [E]** `evaluate*Now` + `CallerRunsPolicy` (`WebConfig.java:202`): "uç hemen döner"
  sözleşmesi kodla değil kapasiteyle korunuyor; havuz+kuyruk dolarsa uzun koşum request
  thread'inde çalışır. *(doğrulanmalı)*
- **30 · [E]** Standalone monitörlerde mükerrer koruması yalnız uygulama katmanında; kısıt yorumu
  (*"standalone monitörler KAPSAM DIŞI: aynı hedefi bilinçli olarak iki kez izlemek meşru"*) ile
  `createPort:1182`/`createDns:1550`'nin 400'ü **birbiriyle çelişiyor** — ürün kararı netleşmeli. *(doğrulanmalı)*
- **31 · [E · N9]** Seviye terfisi "re-alert due değil" dalında kaydedilmiyor
  (`EscalationService:917-919` ↔ `:957-959`); sınıfta `@Transactional` da yok → dirty-checking kurtarmıyor.
- **32 · [E · N6/N7/N8]** `UserPushSettings.jsx`: teslimat günlüğü fetch yarışı (`:249`,
  debounce/seq/Abort yok — kardeş `AlertHistory.jsx:632` 300 ms debounce uyguluyor); "sayfa
  başına" seçicisi ölü kontrol (`:611`, `onPageSizeChange` verilmemiş); şablon alanı boşaltılamıyor
  (`:491` `||` yerine `??` — aynı dosyada `val()` zaten `??` kullanıyor).
- **33 · [E · N5]** `SqlPlaygroundService.enforceLimit:143-156` — iç `LIMIT ≤ 1000` varsa dış tavan
  eklenmiyor; kartezyen çarpımı yalnız 30 sn timeout sınırlıyor, satırlar o sürede heap'e birikiyor.
- **34 · [E · N11]** LDAP `role_mappings`/`default_role` ölü konfigürasyon —
  `LdapProvisioningService.upsert:100` rolü sabit mantıkla belirliyor
  (`(isPo || isManager) ? "TEAM_ADMIN" : "USER"`); ayar ekranı yanlış güven veriyor.

---

# ÇÖZÜLMÜŞ (baseline re-check)

| # | Durum | Kanıt |
|---|---|---|
| **N10** push tetiği e-postasız takımda | **ÇÖZÜLMÜŞ ✓** | `EscalationService:1607-1616` artık `mailDisabled \|\| allEmails.isEmpty()` dalında `triggerUserPush(...)` çağırıp dönüyor. *(Ancak çözüm yolundaki ikizi açık — bulgu 6.)* |
| Y1–Y9 · O9–O16 | **ÇÖZÜLMÜŞ ✓ (regresyonsuz)** | 4. turda kapatılmıştı; bu turda regresyon görülmedi. |

**Hâlâ AÇIK:** N1 (→9), N2, N3, N4, N5 (→33), N6/N7/N8 (→32), N9 (→31), N11 (→34),
N12 (**→4, ağırlaştı: DÜŞÜK → YÜKSEK**).

N2/N3/N4 push outbox dayanıklılığı üçlüsü kaynakta yeniden doğrulandı:
`CIRCUIT_OPEN→PENDING` dönüşümü kod tabanında **yok**; `@PostConstruct`/`ApplicationReadyEvent`/
`@Scheduled` drain **yok**; `drainOutbox:382-383` kuyruğu gecikmesiz yeniden tarayıp `fail`'in
planladığı backoff'u **etkisiz kılıyor**.

---

# DOĞRU BULUNAN (kapsam görünürlüğü)

**Güvenlik.** `PermissionCatalog` `auditDefaults` sızıntısı YOK — çok-eylemli tek `Resource`
`monitoring.scripted` (`List.of(EDIT, EXECUTE)`, VIEW içermiyor); `notification.groups`,
`monitoring.group`, `scripted_templates`, `issues.login-reports` ayrı satırlara bölünmüş.
`AuthInterceptor.FORCED_CHANGE_WHITELIST` tam eşleşme, genişleme yok. `systemRole` string `==`
kullanımı yok.

**SSRF.** Kullanıcı-kontrollü URL alan **sekiz yolun tamamı** `Redirect.NEVER` + hop başına
`ssrfGuard.validate`: `HttpCheckerService:243-265`, `KeywordCheckerService:144-160`,
`PageFetchCore:184-196`, `HttpPhaseProbe:98`, `PortCheckerService:69`,
`CertificateCheckerService:185`, `HstsDiagnosticsService:244`, `WebhookService:109`.
`Redirect.NORMAL` yalnız sabit dış uçlarda (RDAP/WHOIS). `securityEvidence`'ın taşıdığı
saldırgan-kontrollü CN e-postaya `esc()` üzerinden giriyor — enjeksiyon yok.

**Veri katmanı.** `deleteBy*`/`@Modifying` metotlarının hepsinde `@Transactional` (tek istisna
allow-list'te ve javadoc'lu). 9 `*/history` ucu `runHistory` üzerinden `denyIfNotViewable`;
`pageIssues`, `pageSpeedResources` ve tüm `*/response-series` uçları da. Uptime domain uçları
`denyIfDomainNotViewable` (teamId **ve** ugTeamId). Retention yeni alarm tiplerini kendiliğinden
kapsıyor (`resolved_at` üzerinden tip-bağımsız). `MON_FIELDS`'e `standalone` eklenmesi `standalone`
alanı olmayan 7 monitör türünde patlamıyor (`invokeGetter` null döner, diff'e gürültü girmez).

**Bu turun yeni kodu.** İki yeni alarm tipinin kanonik zinciri **9 halkada tam**:
`CERT_ALERT_TYPES`, `MonitorTypeCatalog:49`, `MonitoringGroupService:59`,
`IncidentsController:177-179`, `EmailTemplateBuilder:167-168`, iki `typeTr` switch,
`AppSettingsCatalog`, `alertTypeMeta.js`, `monitorAlertTypes.js`, i18n TR+EN (29 anahtarın tamamı
iki sözlükte) — eksik tek halka push şablonu (bulgu 15). `EscalationService`'e
`AppSettingsService` eklenmesi **bean döngüsü yaratmıyor** (yaprak bağımlılık:
`AppSettingRepository` + `Environment`; `@PostConstruct load()` DB hatasını yutuyor).
`PushText.compactDuration` ↔ `incidentMeta.formatDuration` sınır sınır birebir (59/60 sn,
59/60 dk, 23/24 sa, gün sınırında saat korunuyor). `PushText.parseStoredUtc` doğru — push süre
hesabındaki +3 saat sapması gerçekten kapanmış. `PushText.truncate` `Math.max(0, max-3)` ile
negatif indeks üretmiyor. `CertificateHealthRules.sanCoverage` boş SAN'da UNKNOWN dönüyor →
`CertificateCheckerService.error()` yolundaki boş liste **yanlış `HOSTNAME_MISMATCH` üretmiyor**.
`InventoryFormModal` hook kuralı temiz (9 `useState` + 3 `useEffect`, hepsi `return`'lerden önce).
DNS/Port kartlarında `key={m.id}` (çakışma yok) ve silme çağrıları açık argümanlı
(`onClick={deleteMonitor}` olay-nesnesi tuzağı yok). `certSecurity.js` ikinci bir hüküm mantığı
içermiyor.

**Dayanıklılık.** `tryAcquireSchedulerLock` fail-**closed** (genel `Exception` → `false`; yalnız
"tablo yok" fail-open). `MonitoringOutageService.startConfirmation` `inFlight.putIfAbsent` guard'ı
tekrarlı manuel tıklamada paralel teyit zinciri açmıyor; `finally` anahtarı kalıcı ölü bırakmıyor.
`UserPushService.client()` volatile singleton + kilit dışında `stale.close()` + `@PreDestroy`
(O14 regresyonsuz). Yanıt gövdeleri `readNBytes(64KB)` tavanlı (`sendBatch`, `WebhookService.post`).
`AlertHistory` sunucu-sayfalaması `page+1`/`p-1` doğru.

---

# ÖNERİLEN DÜZELTME SIRASI

| Sıra | Bulgular | Gerekçe |
|---|---|---|
| 1 | **1** | Güvenlik (IDOR); tek satırlık kapı, kardeş deseni hazır. |
| 2 | **3 · 12 · 15 · 10** | Yeni güvenlik alarmının tamamlanmamış halkaları. **Yayından önce** — aksi halde özellik hem süre alarmını kapatır hem yanlış metinle çıkar. |
| 3 | **2 · 16** | Manuel çalıştırmanın çift koşumu; uptime istatistiklerini bozuyor, k6 permitlerini yiyor. |
| 4 | **4 · 5** | Push kanalının kalıcı veri kaybı ve saat sapması (5'in düzeltmesi 11'i ve CSV'yi de kapatır). |
| 5 | **6 · 7 · 13** | Bildirim paritesi üçlüsü; aynı makinede, birlikte ele alınmalı. |
| 6 | **8 · 17 · 18 · 19** | Bu turun kendi kodundaki kalan sapmalar; ucuz, yüksek etki. |
| 7 | **9 · 33 · 20 · 21 · 22** | SQL sandbox güvencesi, tablo eşiği, kimlik kapısı kapsamı ve depo hijyeni. |
| 8 | **23–34** | DÜŞÜK'ler; **29** ve **30** önce ürün kararı ister. |

## Birleşik önem tablosu

| Önem | Adet | Mevcut kod [E] | Bu tur [Y] |
|---|---|---|---|
| KRİTİK | 0 | — | — |
| YÜKSEK | 9 | 5 (1, 4, 5, 6, 7, 9 → 6'sı) | 3 (2, 3, 8) |
| ORTA | 13 | 5 (18, 19, 20, 21, 22) | 8 (10–17) |
| DÜŞÜK | 12 | 7 (28–34) | 5 (23–27) |
| **Toplam** | **34** | **17** | **17** |

## Test notu

Her düzeltme **mevcut testleri değiştirmeden yeni testle** kapatılmalı. Öneriler:
bulgu 1 için başka takımın envanter satırının listede görünmediği; bulgu 2 için tek tıkta
`http_checks`'e **tek** satır düştüğü; bulgu 3 için hostname uyuşmazlığı varken süresi dolan
sertifikanın EXPIRY alarmı **da** açtığı; bulgu 4 için maskeli değerin `secret=false` ile
gelmesinde kayıtlı sırrın korunduğu; bulgu 5 için damganın UTC yazıldığı; bulgu 7 için envanterde
olmayan Port monitörünün çözüm bildiriminin doğru takıma gittiği.
