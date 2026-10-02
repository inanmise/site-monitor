# Bug regresyon taraması (b) — 20.91.0 öncesi SON delta (2026-09-29)

**Kapsam:** `BUG_REGRESYON_2026-09-29.md` bulgularını (K-1, Y-1, O-1…O-5, D-1…D-15) kapatan son düzeltme turu ve ek
düzeltmeler — `main` üzerindeki commit edilmemiş değişiklikler (`git status`: 66 değişmiş + 15 takipsiz). Salt okunur: kod
değiştirilmedi, test/derleme/sunucu koşturulmadı, kilit alınmadı. Yalnız bu rapor dosyası yazıldı.
**Yöntem:** her aday kaynakta okunarak doğrulandı; çağrı zincirleri uçtan uca izlendi (tetik ucu → `evaluate*Now` →
`handleSweepResults` → `reconcileRecoveries`/`handleSweepDomain` → `recoverDomain`/`closeAlarm` → `EscalationService` /
`StormService` / `UserPushService` / `NocNotificationService`). Bağlayıcı kararlar: `cert-monitor-manual-check-storm-isolation`,
`cert-monitor-escalation-team-only`, `cert-monitor-resolve-push-recipients`.

Önem: **KRİTİK 0 · YÜKSEK 0 · ORTA 4 · DÜŞÜK 20**. "(önceden var)" etiketli bulgular bu turun getirdiği regresyon değildir;
kontrol listesinin sorduğu davranışı doğrudan etkiledikleri için listelenmiştir.

---

## ORTA

### O-b1 — D-2 düzeltmesi eksik: izleme alarm e-postalarının en yaygın altı türünde rozet ve "Seviye" satırı hâlâ SABİT "KRİTİK" (gövde artık "UYARI:")

- `backend/src/main/java/com/sitemonitor/service/EmailNotificationService.java:1245-1251` (`alertMail`: ACCESSIBILITY /
  PORT_DOWN / DNS_FAILURE / KEYWORD / PING_DOWN / DNS_CHANGED `level`'ı YOK SAYIP tür-özel belgeye gider), `:1394-1397`
  (`outageAlertDoc`: `Badge.solid("KRİTİK", DESTRUCTIVE)`), `:1408` (`Row("Seviye", "KRİTİK")`), `:1700`/`:1721`
  (`keywordAlertDoc`), `:1747`/`:1765` (`pingAlertDoc`), `:1857` (`dnsChangedDoc` "YÜKSEK"), çözüm belgeleri `:1484`
  (`monitoringResolvedDoc` `levelTrLabel = dnsChanged ? "YÜKSEK" : "KRİTİK"`) ve `:1797` (`typedResolvedDoc`) | **Neden bug:**
  izleme alarmları 2026-09-19'dan beri varsayılan WARNING açılıyor. Bu turda konu `levelWordTr` ile "[Site Monitor] UYARI · …",
  ileti gövdesi `withLevelWord` ile "UYARI: … portuna erişilemiyor" oldu; ama aynı e-postanın kırmızı rozeti ve anahtar-değer
  tablosundaki "Seviye" satırı "KRİTİK" (DNS değişikliğinde "YÜKSEK") yazıyor, ton DESTRUCTIVE. Tur öncesinde gövde ile rozet
  en azından birbirini tutuyordu ("KRİTİK:" + KRİTİK); düzeltme gövdenin İÇİNDE çelişki yarattı. Tam da prod olayının teması
  (UYARI rozeti ↔ KRİTİK metin). CHANGELOG'daki "WARNING seviyesi e-posta konusu, rozeti … aynı sözcükle ('UYARI') yazılır"
  cümlesi bu altı türde yanlış. `EmailResponsiveContractTest` / galeri örnekleri seviye tutarlılığını sınamıyor.
  | **Çözüm:** `outageAlertDoc` / `keywordAlertDoc` / `pingAlertDoc` / `dnsChangedDoc` ve iki çözüm belgesine `level`
  parametresini geçir; rozet metni `EmailTemplateBuilder.severityLabel(level)`, tonu `severityTone(level)` (KRİTİK → solid
  kırmızı, diğerleri tonlu), "Seviye" satırı aynı sözlükten. Kapı: `MonitoringAlertLevelWordTest`'e "WARNING Port e-postasının
  HTML'inde 'KRİTİK' geçmez, 'UYARI' rozeti var" + her tür için döngü; galeriye WARNING örneği.

### O-b2 — D-1/D-11 "sessiz kapanış" fırtına üyesinde sessiz DEĞİL: toplu "✅ Alarm fırtınası sona erdi — N monitör kurtarıldı" e-postası, push'u, webhook'u ve 7/24 çözümü gidiyor

- `MonitoringOutageService.java:1175-1194` (`closeAlarmsOfDisabledTypes` — tür bildirimleri kapatılınca TÜM açık alarmlar
  `resolveOpenAlertsSilently`), `:695-702` (`closeAlarm` sessiz dalı), `StormService.java:312-324` (`lifecycleOne`:
  `activeDown` yalnız çözülmemişleri sayar), `:349-366` (`resolveStorm`: `recovered` = `resolved=true` olan HER üye),
  `:771-843` (`sendStormRecovery`: e-posta + `enqueueStormPush("RESOLVE")` + webhook + `nocNotifications.onStormRecovered`)
  | **Neden bug:** senaryo — bir takımın 15 sentetik alarmı fırtına üyesi (prod olayındaki tablo); yönetici gürültüyü
  kesmek için `site.monitor.scripted.alert-enabled=false` yapar. D-11 tüm üyeleri "Sistem (bu türün alarm bildirimleri
  kapatıldı)" ile sessizce kapatır; ≤30 sn sonra `lifecycleSweep` fırtınada düşük üye kalmadığını görür ve `resolveStorm`
  sessizce kapatılan üyeleri "kurtarıldı" sayarak takıma "✅ ÇÖZÜLDÜ — 15 monitör kurtarıldı" e-postası, açılış push'unu almış
  herkese çözüm push'u, Teams/Slack mesajı ve 7/24 "ÇÖZÜLDÜ" postası gönderir. İki kusur: (1) ürün kuralı "bildirimleri kapalı
  türün açık alarmları sessizce kapanır; kapanış bildirimi gönderilmez" delinir; (2) içerik yanlıştır — hiçbir şey kurtulmadı,
  alarmlar susturuldu. Aynı sınıf duraklatma/silme/öksüz temizliğinde de var (önceden var); D-11 bunu tek tıkla TOPLU tetikleyen
  yolu ekledi. | **Çözüm:** sessiz kapanışı ayırt edilebilir kıl (ör. `alert_events.resolved_silently BOOLEAN` ya da
  `resolvedBy` yerine ayrı bir `resolution_kind` kolonu — metin eşleştirme değil); `resolveStorm`/`disband`/`retireLegacy`
  `recovered` listesini yalnız GERÇEK çözümlerle kur, üyelerin tamamı sessiz kapandıysa fırtınayı bildirimsiz kapat. Kapı:
  `StormTeamIsolationTest`'e "tür kapatılınca üyeler sessiz kapanır → fırtına çözümü e-posta/push/webhook/NOC üretmez".

### O-b3 — D-10 elle k6 kotası: varsayılan havuzda (2) "elle kontrol payı dolu" iletisi HİÇ gösterilmiyor; toplu sentetik kontrolde koşumların çoğu "başlatıldı" dedikten sonra sessizce yürütülmüyor

- `ScriptedCheckerService.java:613` (`manualSlots.tryAcquire(timeoutSec + PERMIT_WAIT_MARGIN_SEC)` — `clampTimeout` tabanı
  5 sn, pay 30 sn → en az 35 sn, varsayılan 60+30 = 90 sn bekleme), `MonitoringController.java:4833-4861` (uç en çok
  `site.monitor.scripted.manual-wait-seconds` = 25 sn bekler, sonra `queued=true`), `frontend/src/components/ScriptedMonitorPage.jsx:337`
  (`skippedReasonOf` yalnız ilk 25 sn içinde gelen `skipped_code`'u görür) | **Neden bug:** kotadaki bekleme (≥35 sn) ucun
  beklemesinden (25 sn) HER ZAMAN uzun; kota doluyken kullanıcı "Çalıştırma başlatıldı — sonuç listeye kendiliğinden düşecek"
  görür, ardından koşum `MANUAL_POOL_BUSY` ile SKIPPED olur, kayıt yazılmaz, hiçbir yere bildirilmez (yalnız WARN log). Toplu
  "Şimdi Kontrol Et (39)" (arayüz eşzamanlılığı 2, kota 1): her istek 25 sn sonra "kuyrukta" döner, arka planda bekleyen
  sanal iş parçacıkları birikir ve kota bekleme süresini aşanlar sessizce düşer — onlarca izleme için ekranda ESKİ sonuç
  "yeni" kontrol sanılır. CHANGELOG'daki "Elle kontrol payı doluysa kontrol yürütülmez ve 'birazdan yeniden deneyin' iletisi
  gösterilir" yalnız tek izinli havuzda (2 sn bekleme) doğru. (Aynı desen önceden de `runGuarded` izin beklemesinde vardı;
  kota elle eşzamanlılığı yarıya indirdiği için etkisi büyüdü.) | **Çözüm:** `runManual`'da kota beklemesini ucun beklemesinin
  altına çek (ör. `min(manual-wait-seconds − 5, timeout + pay)`), dolu kotayı HEMEN `MANUAL_POOL_BUSY` ile döndür; ya da
  toplu kuyruk sentetikte elle kotaya eşit eşzamanlılıkla (pool − 1) ve her koşumun SONUCUNU bekleyerek ilerlesin; arka planda
  sonradan SKIPPED olan koşum için etkinlik günlüğüne/tosta "yürütülmedi" kaydı düşülsün. Kapı: "kota dolu → uç 25 sn içinde
  `skipped_code=MANUAL_POOL_BUSY` döner".

### O-b4 — (önceden var, D-7 ile görünür oldu) SITE_CRAWL derin bulguları HİÇ alarm açamıyor; D-7(b) sonrası ana sayfa kaynaklı bütünlük alarmı yalnız temiz günlük crawl ile kapanıyor — derin kırık linki olan sitede alarm KALICI

- `SchedulerService.java:3675-3686` (günlük crawl kalemleri), `:3739-3753` (kalemin yeniden ölçümü `recheckPage(m, false,
  "SINGLE_PAGE")`; crawl kaleminde `fullScope=true` yakalandığı için temiz ana sayfa → "up"), `MonitoringOutageService.java:1039-1043`
  (teyitte "up" = "Geçici dalgalanma" → zincir iptal), `model/PageMonitor.java:99` (`confirmAttempts = 3` varsayılan)
  | **Neden bug:** (a) crawl derin bir sayfada kırık kaynak bulur → DOWN kalemi → teyit zinciri 30 sn sonra YALNIZ ana sayfayı
  ölçer → ana sayfa temiz → "geçici dalgalanma" → alarm açılmaz; SITE_CRAWL'ın asıl işi (derin bulgu alarmı) varsayılan
  ayarda hiç çalışmıyor (sessiz yanlış-negatif; log'da günde bir "Geçici dalgalanma: PAGE_INTEGRITY:…" ile doğrulanabilir).
  (b) D-7(b) ile crawl izlemesinde 60 sn'lik ana sayfa turu temizken kalem üretmiyor; ana sayfadaki geçici bir sorunla açılan
  bütünlük alarmı ancak ertesi TEMİZ crawl ile kapanabiliyor. Sitede kalıcı tek bir derin kırık link varsa crawl her gün sahip
  DOWN kalemi üretir → alarm hiç kapanmaz ve her gün yeniden uyarı gönderir: kendi başına alarm AÇAMAYAN bulgu, başka sebeple
  açılmış alarmı süresiz AÇIK tutuyor; kart ana sayfayı sağlıklı gösterirken "Neden hâlâ açık?" "kontroller düzelince
  kendiliğinden kapanır" diyor. | **Çözüm:** crawl kökenli bütünlük kaleminde teyit anında olsun (`monitor_confirm_attempts=0`
  — crawl zaten ağır ve deterministik tam taramadır) ya da yeniden ölçüm SITE_CRAWL kipinde yapılsın; bütünlük anahtarını kipe
  göre ayır (ana sayfa / site — önceki raporun D-7 önerisi) ki ana sayfa alarmı ana sayfa kanıtıyla kapansın. Kapı: "crawl derin
  bulgu → alarm açılır" + "ana sayfa kaynaklı alarm, derin bulgu sürerken ana sayfa temizse kapanır".

---

## DÜŞÜK

- **D-b1** (önceden var, Y-1 kardeş yüzeyi) `MonitoringController.java:1032-1035` (`closeAlertsOnPause`), `:2724`, `:3277`,
  `:3641`, `:3894`, `:4747`, `:5435`, `:5891` (silme yolları) — duraklatma/silme açık alarmı anahtara (URL/host/ad) göre sahip
  gözetmeden kapatıyor | **Neden bug:** A takımının izlemesi olayın sahibi ve hâlâ DOWN; aynı URL'yi izleyen B takımı KENDİ
  izlemesini duraklatır/siler → A'nın alarmı "Sistem (izleme silindi)" ile sessizce kapanır, A'nın sonraki DOWN turu yeni
  INITIAL açar (kapan/aç + ikinci ilk bildirim; A'nın onayı/geçmişi kopar). Y-1'in "başka sahibin izlemesi bu olayı
  etkilemez" kuralının yaşam döngüsü eşleniği yok. | **Çözüm:** duraklatma/silmede yalnız olayı açan izleme bu izlemeyse
  (`EscalationService.contextMonitorId(e) == m.id`) ya da `sameOwner` doğruysa ve aynı sahibin başka etkin izlemesi kalmadıysa kapat.
- **D-b2** (önceden var) `SchedulerService.java:5463-5474` (DNS_SLOW), `:5480-5489` (DNS_UNEXPECTED), `:5530-5537`
  (DNS_INCONSISTENT) bağlamlarında `monitor_id` yok; `MonitoringOutageService.java:1286-1304` (`changeCtx`) ve
  `DnsCheckerService.java:390-398` (`changeCtxOf`) de yok — Y-1 bu dört türde hiç devreye girmiyor | **Neden bug:** alan adı
  aynı, kayıt tipi farklı iki bağımsız DNS izlemesi (A: takım X, MX: takım Y) aynı anahtarı paylaşır; `sameOwner` kimliksiz
  bağlamda "true" döner → Y'nin MX değişikliği X'in açık DNS_CHANGED olayına yeniden uyarı olarak X'e gider, olayın iletisini Y'nin
  değerleriyle ezer; günlük yeniden uyarı `lastChangedRecord(domain)` ile alan adı düzeyinde en son değişen kaydı (başka
  izlemenin) ve onun bastırma/kanal ayarlarını kullanır; Y hiç bildirim almaz. | **Çözüm:** bu dört bağlama `monitor_id` ekle
  (DnsChange'e izleme kimliği); yeniden uyarıda son değişen kaydı olayın `contextMonitorId`'sinden oku.
- **D-b3** (önceden var — Y-1'in kalıcı olmayan kısmı) `MonitoringOutageService.java:513-541`, `EscalationService.java:1241-1246`
  — anahtar (alan adı|tür) takım içermediği için iki sahibin izlemesi AYNI ANDA DOWN iken yalnız olayı açan tarafa bildirim
  gider | **Neden bug:** A'nın olayı açıkken B'nin DOWN'u "yabancı" sayılır: ne teyit zinciri ne alarm; B'nin alarmı ancak A'nın
  olayı kapandıktan sonra açılır. A'nın izlemesi uzun süre düşük kalırsa (onaylanmış/ihmal edilmiş) B'nin gerçek kesintisi
  bildirimsiz kalır (kart kırmızı ve anahtar düzeyi alarmı gösterir). Tur öncesinde B'nin arızası yanlış takıma (A'ya)
  gidiyordu; şimdi kimseye gitmiyor. CHANGELOG "o izlemenin alarmı kendi takımına açılır" koşulunu (A kapandıktan sonra)
  söylemiyor. | **Çözüm:** önceki raporun kalıcı önerisi — alarm anahtarına sahibi kat (alan adı|tür|sahip) ve
  `findOpenAlert`/öksüz temizlik/Olaylar/incidentMeta zincirini birlikte güncelle; o zamana dek CHANGELOG ve "Neden hâlâ
  açık?" ipucunda koşulu yaz.
- **D-b4** (D-7 kardeş yüzeyi) `SchedulerService.java:5564-5573` (`recheckDnsSlow`: "çözülemiyorsa up"), `:5594-5603`
  (`recheckDnsUnexpected`: "çözülemiyorsa up"), `:5220-5240` (`recheckDomainFor`: EXPIRY `days==null`, STATUS veri yok,
  TRANSFER_LOCK/BLACKLIST UNKNOWN → "up") | **Neden bug:** "ölçülemedi ≠ sağlıklı" kuralı kalemlere uygulandı, bu türlerin
  aktif kurtarma zinciri yeniden ölçümlerine uygulanmadı. DNS_UNEXPECTED (ele geçirme odaklı) ilk gerçek sağlıklı turdan sonra
  zincirde çözümleme hataları "up" sayılıp `recoveryChecks` şartını kanıtsız tamamlayabilir. Alan adı yeniden ölçümü bugün
  `confirm=0 / recovery=1` nedeniyle yalnız UNKNOWN türünde kullanılıyor (diğerleri gizli tuzak — biri ayarı açarsa O-5 geri
  gelir). | **Çözüm:** çözümleme başarısız / veri yok → `"skipped"`; kapı: SlowAndIntegrityNoDataTest'e DNS eşlenikleri.
- **D-b5** (O-5 kalıntısı, doğrulanmalı) `SchedulerService.java:5093-5101`, `DomainCheckerService.java:109-126` — RDAP düşüp
  WHOIS yedeği bitiş tarihi döndürünce `hasData=true`; EPP durum listesi boş/biçimsizse DOMAINMON_STATUS kalemi "up" | **Neden
  bug:** RDAP'la açılmış clientHold/serverHold alarmı, WHOIS'in durum kodu vermediği TLD'de ya da ayrıştırılamayan biçimde
  "✅ ÇÖZÜLDÜ" diye kapanır, sonraki RDAP turu yeniden açar (O-5'in kaynak düzeyindeki eşi). TRANSFER_LOCK bu yüzden yalnız
  RDAP'ta kesin sayılıyor; STATUS sayılmıyor. | **Çözüm:** STATUS için de "EPP listesi yalnız RDAP'ta kesin" kuralı: RDAP
  dışı kaynak + boş liste → kalem üretme.
- **D-b6** `StormService.java:434-446` (takım fırtınası SESSİZ kuruluyor), `UserPushService.java:687-695`
  (`priorStormRecipients` yalnız AYNI fırtına kimliğinin push'larına bakar), `noc/NocNotificationService.java:342-348`
  (`onStormTick` açılışı olmayan fırtınaya `onStormDispatched` çağırır) — O-3 taşımasının iki yan etkisi | **Neden bug:** (1)
  eski fırtınadan yeni takım fırtınasına taşınan üyeler için takımın kişileri açılış push'unu ESKİ fırtına kimliğiyle almıştı;
  yeni fırtına ilk günlük toplu tekrarından (eski postadan +24 sa) önce çözülürse çözüm push'u `SKIPPED_NO_PRIOR` olur —
  "düştü" push'unu alan "düzeldi"yi almaz (e-posta gider). (2) Taşımadan ≤30 sn sonra `lifecycleOne` → `onStormTick` yeni
  fırtınanın NOC açılışı olmadığını görüp 7/24'e YENİ bir "FIRTINA" açılış postası gönderir — "sessiz taşıma" NOC'ta sessiz
  değil. Tek seferlik geçiş etkisi. | **Çözüm:** taşıma sırasında yeni fırtınaya `legacy_storm_id` yaz; `priorStormRecipients`
  ve NOC açılış anahtarı eski kimliği de saysın (ya da taşınan üyelerin NOC `alert:<id>:OPEN` satırlarına bakılsın).
- **D-b7** `AlertEventRepository.java:135-138` (`releaseFromStormAsNotified` — `stormId = :fromStormId` koşulu yok),
  `StormService.java:412-457` (`retireLegacy` işlemsiz; üye listesi okunduktan sonra döngü) | **Neden bug:** (a) üye listesi
  okunduktan sonra ama taşıma/serbest bırakmadan önce izleme turu bir üyeyi çözerse: bireysel çözüm e-postası "fırtına aktif"
  diye bastırılmıştır, üye ise `stillDown` listesinde okunduğu için `recovered`'a girmez → o üye için hiçbir çözüm bildirimi
  gitmez (dar pencere). (b) Kilit TTL'i (90 sn) aşılıp iki çalıştırma üst üste binerse ikinci çalıştırmanın `release…` çağrısı
  birincinin takım fırtınasına taşıdığı üyeyi fırtınadan koparır. | **Çözüm:** `release…`'e `AND e.stormId = :fromStormId`
  ekle; `recovered` listesini işlemin SONUNDA `findByStormId(legacy)` + `resolved=true` ile yeniden oku.
- **D-b8** `AppSettingsService.java:160-212` (`@Transactional save` → `publish` aynı işlem içinde),
  `MonitoringOutageService.java:1161-1194` (`@EventListener onSettingsChanged` senkron) | **Neden bug:** D-11 toplu kapanışı
  (onlarca `save` + çözüm push'u kuyruğa alma) yöneticinin ayar kaydı isteğinin ve işleminin İÇİNDE koşar: istek uzar; iç
  depolardan birinde fırlayan çalışma zamanı istisnası (dinleyicide yutulsa da) işlemi rollback-only işaretleyip ayar kaydını
  `UnexpectedRollbackException` ile düşürebilir ("dinleyici hatası kaydı geri almamalı" sözleşmesi delinir). | **Çözüm:**
  `@TransactionalEventListener(phase = AFTER_COMMIT)` + ayrı yürütücü (ya da `@Async`); kapanışlar kendi işleminde.
- **D-b9** (ürün kararı doğrulanmalı) `MonitoringOutageService.java:1178` — D-11 "yalnız elle kapanır" türlerini de
  (DNS_CHANGED, DOMAINMON_CHANGED) kapatıyor | **Neden bug:** DNS/alan adı bildirimlerini geçici olarak kapatan yönetici, kimsenin
  doğrulamadığı ele geçirme sinyallerini (nameserver/DNSSEC değişikliği) de sessizce siler; bildirimler geri açıldığında
  değişiklik tabanı ilerlemiş olduğundan alarm geri gelmez. Arayüz bu türler için "yalnız elle kapanır" diyor. | **Çözüm:**
  değişiklik türlerini toplu sessiz kapanıştan hariç tut (ya da ürün kararı olarak CHANGELOG'a yaz).
- **D-b10** (D-9'un alan adı eşi) `DomainCheckerService.java:173-190`, `:231` (`persist(…, changed, manual)`),
  `MonitoringController.java:5512-5519` — elle/Kayıt sekmesi/yeniden ölçüm satırı `changed=true` kalıcılaştırıyor | **Neden bug:**
  aynı değişiklik önce elle satırda, sonra zamanlanmış satırda "değişti" olur; farklı günlere düşerse alan adı geçmişi grafiğinde
  iki gün "değişti", etkinlik günlüğünde iki kayıt görünür. DNS'te bu sınıf bu turda kapatıldı (D-9), alan adında kapatılmadı.
  | **Çözüm:** elle/taban dışı satırda `changed=false` kalıcılaştır, gördüğü farkı yalnız yanıtta döndür (DNS deseni).
- **D-b11** `ScriptedCheckerService.java:606-631`, `MonitoringOutageService.java:191-196` — D-10 kenar durumları | **Neden bug:**
  (a) tek izinli havuzda elle koşum izni aldıktan sonra gelen zamanlanmış koşum onun bitmesini bekler (timeout + 30 sn'den uzun
  sürerse SKIPPED) — CHANGELOG'daki "zamanlanmış kontrollere her zaman yer kalır" pool=1'de doğru değil; (b) elle kökenli kurtarma
  zincirinin yeniden ölçümü 2 iş parçacıklı `recoveryExecutor`'da önce kota için (≥35 sn), sonra izin için bekleyebilir → tüm
  türlerin aktif kurtarma zincirleri bu sürece takılabilir (önceden de izin beklemesi vardı; kota ikinci bir bekleme ekledi).
  | **Çözüm:** (a) CHANGELOG'u düzelt ya da pool=1'de elle koşumu tümden kapat; (b) zincir yeniden ölçümlerinde kota beklemesini
  kısa tut (dolu → "skipped", sayaç korunur zaten).
- **D-b12** `SchedulerService.java:4373-4375` + `:4444-4446` — elle kontrolün başlattığı kurtarma zinciri koşumları etkinlik
  günlüğüne `manual=true, "manual"` diye yazılıyor | **Neden bug:** sistemin 30 sn'lik otomatik yeniden ölçümleri kullanıcı
  tetiği gibi görünür (kim/ne zaman okuması yanıltıcı). | **Çözüm:** kayıt işareti (anomali serisi dışı) ile etkinlik kaynağını
  ayır: satır `manual=true`, etkinlik `"recheck"`/`"scheduler"`.
- **D-b13** `frontend/src/i18n/index.jsx:1666` / `:14658` (`storm.unitCount` "Monitör sayısı" / "Number of monitors"),
  `components/admin/StormSettings.jsx:126` — birim etiketi ölçüyle çelişiyor | **Neden bug:** eşik artık FARKLI HEDEF (host)
  sayısı; açılır liste hâlâ "Monitör sayısı" diyor, altındaki ipucu "farklı hedef". | **Çözüm:** "Farklı hedef (host) sayısı" /
  "Number of different targets (hosts)".
- **D-b14** `UserPushService.java:1252-1257` — push'un ayrı seviye sözlüğü | **Neden bug:** "tek sözlük" kuralına rağmen push
  INFO/LOW'u "UYARI" yazar, `levelWordTr` "BİLGİ" der (sertifika/INFO alarmında push ile e-posta ayrışır). | **Çözüm:**
  `EscalationService.levelWordTr` kullan.
- **D-b15** `SchedulerService.java:4309-4313` — SCRIPTED_SLOW bloğunun üst yorumu "Kapalıysa/ölçülemediyse/koşum DÜŞTÜYSE
  sentetik 'up' üretilir" diyor; O-2 sonrası düşen koşumda kalem ÜRETİLMİYOR | **Neden bug:** yanlış yorum bir sonraki düzenlemede
  O-2'yi geri getirmeye davet eder. | **Çözüm:** yorumu güncelle.
- **D-b16** (önceden var, D-7 sınıfı) `EscalationService.java:1992-2008` (`securityAlertType` tek tür döner),
  `:1960-1970` (`closeStaleCertTypes`) | **Neden bug:** HOSTNAME_MISMATCH ve UNTRUSTED_CA bayraklarının ikisi de duruyor ve
  ikisinin alarmı da açıkken yalnız HOSTNAME_MISMATCH "üretilir"; açık UNTRUSTED_CA alarmı "✅ sorun giderildi" diye (sessiz
  değil) kapanır, bayrak hâlâ durur. | **Çözüm:** `determineAlertTypes` iki güvenlik türünü de döndürsün.
- **D-b17** (tek pod maliyeti) `EscalationService.java:308` + `:1960-1970` — `processResults` her sertifika turunda alan adı
  başına ikinci bir `findByDomainAndAlertTypeInAndResolvedFalse` | **Neden bug:** güvenilmeyen-CA alarmı varsayılan KAPALI;
  kurumsal CA paketi boşsa iç host'ların tamamı bayraklı → her turda yüzlerce ek sorgu (öncesinde de alan adı başına bir sorgu
  vardı; "sessiz" küme ikinciyi ekledi). | **Çözüm:** turun başındaki toplu `openAlertByKey` haritasına bak; açık alarmı olmayan
  tür için kapanış sorgusu atma.
- **D-b18** (tasarım değişikliği — ürün onayı) `StormService.java:236-237`, `:464-484` — COUNT kipinde de eşik artık farklı host
  | **Neden bug:** tek host'un tam kesintisi (ACCESSIBILITY + PORT + DNS + HTTP + İçerik + Sayfa + Sayfa Hızı = 6–7 alarm) artık
  fırtına değil, takıma 6–7 ayrı bildirim; aynı host'ta çok sayıda URL izlemesi olan takımda (API geçidi vb.) "sel" geri gelir.
  CHANGELOG'da yazılı, ama O-4'ün kaynağı yalnız PERCENT kipiydi. | **Çözüm:** ürün onayı; istenmiyorsa COUNT'ta alarm sayısı,
  PERCENT'te farklı hedef + taban 3.
- **D-b19** (test) `backend/src/test/java/com/sitemonitor/service/ScriptedManualQuotaTest.java:79, 111, 132` — gerçek iş
  parçacığı + `Thread.sleep`/yoklama + "< 3,5 sn" süre iddiası | **Neden bug:** `mvn verify` ile vitest paralel koşarsa CPU açlığında
  "ZAMANLANMIS başlamadı" ya da süre iddiası kızarabilir (bellek: kapılar SERİ). | **Çözüm:** mandal/`CountDownLatch` ile başlama
  bildirimi, süre iddiasında geniş pay.
- **D-b20** (test hijyeni, doğrulanmalı) `frontend/src/test/DomainRegistrationTab.test.jsx` (yeni "runs the live query ONLY when
  the user presses Refresh" testi) — dosyadaki gerçek görünümlü alan adı fixture'ını yeni satırda tekrar kullanıyor | **Neden
  bug:** `no-real-identifiers-in-code` kuralı; terim IdentityLeakGuard listesinde olmadığı için kapı yakalamıyor, git geçmişi
  kalıcı. | **Çözüm:** dosyadaki tüm kullanımları `example.com` yer tutucusuna çevir.

---

## Kontrol listesi 1–10

**1. Y-1 sahip ayrımı — TEMİZ (çekirdek) / BULGU (kardeş yüzeyler D-b1, D-b2, D-b3).**
- Sahip anahtarı tutarlı: bağlam `ownerKeyOf(tür, ctx)` = bağımsızsa `T:<ctx.team_id>`, değilse `INV`; olay `ownerKeyOf(e)` =
  bağımsız olayda `T:<e.teamId>`, değilse `INV`. Açılışta `e.teamId` = bağlamdaki `team_id` (bağımsız, takımsızsa null) →
  iki taraf aynı sözlükten. `monitor_id` / `team_id` / `standalone` `RESOLVED_CONTEXT_KEYS`'te → olay anlık görüntüsünde kalıcı ✓.
  Kimliksiz bağlam (ACCESSIBILITY, DNS_CHANGED, DNS_SLOW/UNEXPECTED/INCONSISTENT) → "true" (eski davranış; D-b2).
- Üç yol aynı yardımcıyı kullanıyor: `handleSweepDomain` (sahip DOWN → yeniden uyarı; yabancı DOWN yok sayılır),
  `reconcileRecoveries` (bastırma / kapalı bildirim / elle), `downSibling` (D2 engeli yalnız aynı sahibe) ve
  `processConfirmedOutage` (yabancı bağlam yeniden uyarı/terfi/yarım ilk bildirim ÜRETMEZ) ✓.
- İzleme başka takıma taşınınca: olayı açan izleme `contextMonitorId` eşleşmesiyle sahip kalır, bildirim O5 damgasıyla eski
  takıma (öncekiyle aynı) ✓. Eski (Y-1 öncesi) bağımsız olaylar `DerivedMonitorAlertRouting` ile işaretli; taşınmış eski olayda
  aynı takımın başka izlemesi "yabancı" görünebilir → fazladan bir kapan/aç (geçiş, ihmal edilebilir).
- Silinme/duraklatma: kardeş gözlemi `stillMonitored` + budama ile düşüyor ✓; ama yaşam döngüsü kapanışı sahip gözetmiyor (D-b1).
- Yeniden başlatma: `checkDue` bellek-içi → ilk zamanlanmış turda o türün TÜM izlemeleri vadeli, gözlemler `recoverDomain`'den
  önce doluyor ✓; elle yol ilk tura kadar olayı açan izleme dışında kapatmıyor (D-6) ✓. Çift alarm / iki takıma çift bildirim
  yolu bulunmadı: yabancı teyit zinciri olay açıkken `processConfirmedOutage`'da düşüyor.
- DNS_CHANGED eski davranışta: sızıntı VAR ama önceden var (D-b2).

**2. O-1 elle aralık — TEMİZ.** Zamanlanmış tur her zaman sayar ve `recoveryLastCountedAt`'i ilerletir; elle sonuç yalnız
önceki SAYILMIŞ sonuçtan ≥ aralık sonra sayılır (sayılmayan tık sayacı sıfırlamaz). Aralık kaynağı: `monitor_recovery_interval_ms`
(Port/HTTP/İçerik/Ping/Sayfa/Sayfa Hızı/Sentetik/DNS_FAILURE/UNEXPECTED/INCONSISTENT), ACCESSIBILITY için
`uptime.recovery-interval-ms`; yoksa 30 sn (alan adı, DNS_SLOW). `required ≤ 1` kurulumda tek sağlıklı sonuç yine kapatır
(tasarım). Sayılmayan elle tık aktif zinciri başlatabilir; zincir yeniden ölçümleri aralıkla yaptığı için kanıt eşdeğer ✓.
Elle DOWN sayacı ve zinciri sıfırlıyor (muhafazakâr) ✓. Test `rapidManualClicks_doNotBypassRecoveryInterval` davranışı sınıyor.

**3. O-2 / O-5 / D-7 "ölçülemedi ≠ sağlıklı" — BULGU** (O-b4; D-b4, D-b5). "Hayalet alarm" geniş çapta geri gelmiyor: kapanış
kanıtı olmayan durumların çoğu hedefin gerçekten ölçülemediği (kart da sağlıklı değil) durumlar; istisnalar aşağıda "donma" sütununda.

| Tür | Kapanış kanıtı (kalem "up") | Ölçülemediğinde | Otomatik kapanamadığı durum → kapanış yolu |
|---|---|---|---|
| ACCESSIBILITY | uptime `up` | her sonuç up/down | — |
| PORT_DOWN | port açık | — | — |
| PORT_SLOW | özellik kapalı (sentetik) ya da açık + ölçülü ≤ eşik | port kapalı/hata → kalem yok | port kalıcı kapalı → özelliği kapat / duraklat / sil / elle çöz (PORT_DOWN anlatır) |
| DNS_FAILURE | çözümleme başarılı | — | — |
| DNS_SLOW | başarı + ≤ eşik | başarısızlıkta kalem yok; zincirde "up" (D-b4) | — |
| DNS_UNEXPECTED | başarı + beklenmeyen değer yok | başarısızlıkta kalem yok; zincirde "up" (D-b4) | beklenen değer kaldırılırsa kalem hiç üretilmez → elle çöz (önceden var) |
| DNS_INCONSISTENT | resolver'lar tutarlı | — | yayılım kontrolü kapatılırsa kalem yok → elle çöz (önceden var) |
| DNS_CHANGED / DOMAINMON_CHANGED | yok (tasarım) | — | elle çöz; D-11 tür kapatınca sessiz kapatıyor (D-b9) |
| KEYWORD | koşul sağlanıyor, HTTP hatası yok (cfgError = up) | — | — |
| KEYWORD_SLOW | özellik kapalı ya da ölçülü ≤ eşik | HTTP hatası → kalem yok | hedef kalıcı hatalı → KEYWORD anlatır; özelliği kapat / elle çöz |
| KEYWORD_SSL / KEYWORD_DOMAIN_EXPIRY / HTTP_SSL / DOMAIN_EXPIRY | günlük kontrol temiz ya da anahtar kapalı | — | — |
| PING_DOWN | `up` ya da `na` (ICMP yok = up — önceden var) | — | — |
| PING_SLOW | özellik kapalı ya da ölçüm + taban ≥ 3 örnek + eşik altı | ölçüm/taban yok → kalem yok | taban kalıcı yetersiz (aralık büyütüldü / pencere kısaldı) → donar; özelliği kapat / elle çöz (kart sağlıklı — tek "hayalet" adayı) |
| HTTP_DOWN / PAGE_DOWN / PAGESPEED_DOWN | erişildi ya da cfgError | — | — |
| PAGE_INTEGRITY (SINGLE_PAGE) | ana sayfa alındı + temiz | ana sayfa alınamadı → kalem yok | — |
| PAGE_INTEGRITY (SITE_CRAWL) | yalnız TEMİZ günlük crawl (+ zincirde temiz ana sayfa) | ana sayfa turu temizse kalem yok | crawl hiç tamamlanmaz / her gün derin bulgu → donar ya da süresiz yeniden uyarı (O-b4); kip değiştir / duraklat / elle çöz |
| PAGESPEED_SLOW | erişildi + eşik içi ya da cfgError | erişilemedi → kalem yok | — |
| SCRIPTED_FAIL | koşum geçti (NO_CHECKS politikası) | SKIPPED → kalem yok | k6 kalıcı yok → `scripted.enabled=false` D-11 ile sessiz kapatır |
| SCRIPTED_SLOW | özellik kapalı ya da koşum geçti + ≤ eşik | koşum düştü/SKIPPED → kalem yok | senaryo sürekli düşük → FAIL anlatır; özelliği kapat / elle çöz |
| DOMAINMON_UNKNOWN | veri geldi | her turda kalem | — |
| DOMAINMON_EXPIRY | veri + gün > uyarı eşiği | veri yok → kalem yok | RDAP/WHOIS kalıcı kapalı → donar (UNKNOWN alarmı körlüğü anlatır, terfi de yok); elle çöz |
| DOMAINMON_STATUS | veri + EPP kritik/uyarı yok | veri yok → kalem yok; WHOIS'te boş EPP = up (D-b5) | — |
| DOMAINMON_TRANSFER_LOCK | RDAP + kilit var ya da anahtar kapalı | WHOIS/.tr (UNKNOWN) → kalem yok | kaynak kalıcı WHOIS'e döndü → donar; anahtarı kapat / elle çöz |
| DOMAINMON_BLACKLIST | LISTED değil (SKIPPED = özellik kapalı → up) | UNKNOWN → kalem yok | DNSBL kalıcı erişilemez → donar; kara listeyi kapat / elle çöz |
| Sertifika türleri | `status≠error` ve tür üretilmiyor (ayar kapalı + bayrak duruyor → sessiz) | `status=error` → dokunulmaz | host kalıcı erişilemez → envanteri pasifle/sil (önceden var); D-b16 |

Elle çözüm her durumda mümkün (`POST /api/admin/alerts/{id}/resolve`); "Neden hâlâ açık?" ipucu bunu söylüyor ✓.

**4. D-1 / D-11 sessiz kapanış — BULGU** (O-b2; D-b8, D-b9). Tür eşlemesi temiz: `alertEnabled` tek kaynak (alan adı ailesi
`isDomainMon`, sentetikte `alert-enabled && enabled`), `MONITORING_ALERT_TYPES`'in her üyesi doğru anahtara bağlı ✓. Sertifika
türleri bilinçli kapsam dışı; `expiry.alert-enabled=false` iken sertifika kapanışı `resolveVerifiedStaleCertAlerts` ile sessiz ✓.
Sessiz kapanışta push çözümü simetrik (`enqueueResolvePushQuietly`), e-posta/webhook/7-24 yok — kuralla tutarlı ✓; fırtına
çözümü bu kuralı deliyor (O-b2). Hiçbir tür kapalı değilse sorgu yok ✓.

**5. K-1 + recheck — TEMİZ** (+ D-b10). Tetik ucu ve `live=true` `checkManual` (`manual=true`, etkinlik "manual"); teyit/kurtarma
`checkRecheck` (`manual=true`, etkinlik "scheduler"); günlük ve 16:00 kritik tur `check` (zamanlanmış) ✓. Zamanlanmış taban
`findLatestScheduledWithData` = `source<>'NONE'` + `(manual IS NULL OR manual=false)` → eski NULL satırlar zamanlanmış sayılır,
veri taşımayan satır taban olmaz ✓. İzlemenin ilk zamanlanmış satırı (yalnız elle satır varken) taban bulamaz → `changed=false`,
sahte alarm yok ✓. Elle kontrolün gördüğü değişikliği sonraki zamanlanmış tur yine görür → DOMAINMON_CHANGED açılır ✓. Kayıt
sekmesi açılışta `live=false`, "Yenile" `live=true`; "Yeniden dene" `live=false` ✓. İdempotent `patch` + nullable Boolean ✓.

**6. O-3 eski fırtına emekliye ayırma — BULGU (DÜŞÜK: D-b6, D-b7).** Bekleme süresinde eski fırtına etkisiz (toplu tekrar / 7-24
tiki yok) ama `isActive` doğru → üyelerin bireysel yeniden uyarı ve çözüm e-postaları bastırılır; emeklilikte kurtulanlar takım
bazlı TEK toplu çözümle, düşük kalanlar ya takım fırtınasına (lastReAlertAt korunur) ya "bildirildi" damgasıyla (`notifiedAt`)
çıkar → bastırılan bildirim kalıcı KAYBOLMUYOR; istisna taşınan üyelerin çözüm push'u (D-b6). Çok pod: `storm-sweep` kilidi +
`ON CONFLICT` + koşullu `moveToStormIfOpen` ile ikinci çalıştırma zararsız; `release…` koşulu eksik (D-b7). Tekrarlı yeniden
başlatmada (15 dk'dan sık) eski fırtına hiç emekliye ayrılmaz ve üyelerin bireysel bildirimleri o süre bastırılı kalır (uç durum).

**7. O-4 farklı hedef — TEMİZ** (+ D-b18 tasarım notu). `targetKey`: şemalı değerde host (kullanıcı bilgisi, port, yol, sorgu,
parça atılır; IPv6 köşeli parantez korunur), küçük harf; şemasız değer olduğu gibi küçük harf (envanterde port ayrı kolonda →
`host:port` sorunu yok) ✓. Sentetik = senaryo adı başına bir hedef ✓. `PERCENT_MIN_TARGETS=3` hem `computeThreshold` hem ayar
önizlemesinde (`Math.max(3, …)`) ✓; histerezis de farklı hedefle ✓.

**8. D-10 elle k6 kotası — BULGU** (O-b3; D-b11, D-b12). `withManualQuota` ThreadLocal'i `try/finally` ile önceki değere döner →
havuz iş parçacığında sızıntı yok; karar `run()` içinde çağıran iş parçacığında verildiği için `execPool`'a yayılma gerekmiyor ✓.
`manualSlots` ve tek izinli havuzda `permits` `finally`'de bırakılıyor (istisna / kesinti yolları dâhil) ✓. `MANUAL_POOL_BUSY`
zincirde "skipped" → zincir biter, ardışık sayaç korunur, sonraki zamanlanmış tur normal kotayla sürer → kalıcı engel YOK ✓.
Elle kökenli zincir satırları `manual=true` (anomali serisi dışı) ✓.

**9. Metin / i18n / e-posta — BULGU** (O-b1; D-b13, D-b14). `levelWordTr` konu, `EmailTemplateBuilder.severityLabel`, NOC
`levelTr` ve gövde `withLevelWord`'de tek kaynak ✓; tür-özel izleme belgeleri kapsam dışı kaldı (O-b1). `EmailSamples`
güncellendi, `EmailResponsiveContractTest` yeşil (seviye tutarlılığını sınamıyor). Yeni 12 anahtarın TR/EN eşliği tam ✓; silinen
`storm.unitPercent` / `storm.pctPreview` / `storm.perGroupHint` için dinamik kullanım (`t('storm.' + …)`, şablon dizesi) yok ✓.
EN metinler doğal British English ("data centre", "organisation-wide") ✓; öneri: `alh.whyStorm` "Handed over to the storm
notification" → "Covered by the storm notification".

**10. Testler — BULGU (DÜŞÜK: D-b19, D-b20).**
- Yeni testler çoğunlukla DAVRANIŞ sınıyor: `MonitoringOutage*RegressionTest` gerçek `MonitoringOutageService` + statik gerçek
  `EscalationService.sameOwner` (Mockito statik çağrıyı taklit etmez) ile; `SharedKeyOwnerGuardTest` gerçek `EscalationService`
  ile; `StormTeamIsolationTest` alıcı/push çağrılarını sınıyor ✓. `ManualCheckWiringTest` ve `ManualEvaluationWiringTest` bağlantı
  (uygulama) kapısı — bilinçli ✓.
- Sabit tarih / saat dilimi: tarih literalleri yalnız sıralama verisi (`ManualCheckMarkerQueriesTest`) ve olay `createdAt`
  eşitlik bozucusu; pencereli testler `Instant.now()` göreli; `LocalDate.now()` yok ✓.
- Mockito yeniden-stub / worker yarışı: zincirler aynı iş parçacığında koşan (ya da elle sürülen) zamanlayıcılarla; yeniden
  stub'lar iş parçacığı başlamadan ✓. Gerçek iş parçacığı kullanan tek test `ScriptedManualQuotaTest` (D-b19).
- "K2 mutasyonunda `recheckScripted_marksManual_andPassesFlagToGuard` kırmızı": gizli kod bağımlılığı YOK. `bite3` turundaki
  "K2" kurtarma K2'si değil, KEYWORD_SLOW `evalKeywordSlow` mutasyonu; `SchedulerServiceTest`'te `MonitoringOutageService`
  `@Mock`. Hata `NullPointerException: res is null @ persistScripted ← recheckScripted(m, true)` (`bite3-K2.log:266-269`):
  15:18–15:19 arasında paralel D-10 değişikliği `recheckScripted`'i `runManual`'a yönlendirdi, testin `runManual` stub'ı sonra
  eklendi (P1/P2/K1 turlarında aynı test yeşil, PG1'den itibaren yine yeşil). Son hâl tutarlı (stub ve çağrı eşleşiyor). Harness
  tek parça oku-değiştir-yaz yaptığı için (tam dosya geri yükleme değil) kayıp güncelleme riski dar; `SchedulerService` diff'inde
  tüm beklenen parçalar mevcut (K-1 `checkRecheck`, O-2 SKIPPED atlama, O-5 alan adı kalemleri, D-7 altı tür, D-10
  `runManual`/`onManualK6Quota`, sertifika K1/K5 uzlaştırması, üç `manual` kolon yaması) ✓.
- Not (doğrulanmalı): `SchedulerService.java` ve `ScriptedCheckerService.java` son değişiklik 15:32 — son hedefli yeşil koşumlardan
  (`final3` 15:27, `r7` 15:29) SONRA. Şu an koşan sürüm kapılarının bu hâli kapsadığı (kapı başlangıcı > 15:32) teyit edilmeli.

---

## Özet tablo

| Kod | Önem | Dosya:satır | Kısa |
|---|---|---|---|
| O-b1 | ORTA | `EmailNotificationService.java:1245-1251, 1397, 1408, 1484, 1700, 1747, 1857` | Altı izleme türünün e-postasında rozet/Seviye sabit "KRİTİK", gövde "UYARI:" |
| O-b2 | ORTA | `MonitoringOutageService.java:1175-1194`, `StormService.java:349-366` | Tür kapatılınca sessiz kapanan fırtına üyeleri "N monitör kurtarıldı" toplu çözümü üretiyor |
| O-b3 | ORTA | `ScriptedCheckerService.java:613`, `MonitoringController.java:4833-4861` | Elle kota iletisi varsayılan havuzda hiç görünmüyor; toplu kontrolde sessiz yürütülmeme |
| O-b4 | ORTA | `SchedulerService.java:3739-3753`, `MonitoringOutageService.java:1039-1043` | SITE_CRAWL derin bulgu alarm açamıyor (önceden var); D-7 ile ana sayfa alarmı kalıcılaşabiliyor |
| D-b1…D-b20 | DÜŞÜK | yukarıda | Y-1 kardeş yüzeyleri, D-7 zincir yeniden ölçümleri, O-3 push/NOC, D-11 işlem sınırı, metin, test |

---

## Okundu ✓

Kaynak raporlar ve bağlam: `BUG_REGRESYON_2026-09-29.md` ✓ · `CHANGELOG.md` `[Unreleased]` ✓ · `CLAUDE.md` diff ✓ ·
`frontend/src/assets/whitepaper.md` diff ✓ · `D:\site-monitor-shadcn\.migration\port-alarm-rca\PROD_DIAGNOSIS.md` (K2 bölümü) ✓ ·
mutasyon günlükleri `scratchpad/port-alarm-rca/bite3.py`, `bite3.sh`, `bite3.result`, `bite3-K2.log`, `final3.summary`,
`r7.summary` ✓ · bellek notları (manual-check-storm-isolation, escalation-team-only, resolve-push-recipients, prod-log-as-defect-source,
dogrulamadan-varsayma, fix-sweep-sibling-surfaces, single-pod-perf, mockito-restub-worker-race, test-fixed-date-time-bomb) ✓.

Değişmiş (satır satır diff): `service/MonitoringOutageService.java` ✓ (tamamı) · `service/EscalationService.java` ✓ (diff +
`processConfirmedOutage`, sahiplik yardımcıları, `snapshotContext`/`RESOLVED_CONTEXT_KEYS`, `resolveOpenAlertsSilently`,
`determineAlertTypes`, `securityAlertType`, konu kurucusu) · `service/SchedulerService.java` ✓ (diff + sweep kalem kurucuları,
`isStillMonitored`, `checkDue`, sayfa/crawl turları, DNS turu ve yeniden ölçümleri, `recheckDomainFor`, `triggerScriptedCheckAsync`,
`persistScripted`) · `service/StormService.java` ✓ (tamamı) · `service/ScriptedCheckerService.java` ✓ (diff + `clampTimeout`) ·
`service/ScriptedAnomalyGuard.java` ✓ · `service/DomainCheckerService.java` ✓ (diff + `evaluate`, `setChanged`) ·
`service/EmailTemplateBuilder.java` ✓ · `service/noc/NocMailComposer.java` ✓ · `controller/MonitoringController.java` ✓ (diff +
tetik uçları, silme/duraklatma, Kayıt ucu, alan adı geçmişi, `enrichPort`/`enrichDns` takım kaynağı) · `model/AlertStorm.java` ✓ ·
`model/DnsRecord.java` ✓ · `model/DomainCheck.java` ✓ · `model/ScriptedCheck.java` ✓ · `repository/AlertEventRepository.java` ✓ ·
`repository/DnsRecordRepository.java` ✓ · `repository/DomainCheckRepository.java` ✓ · `repository/ScriptedCheckRepository.java` ✓ ·
diğer sayım depoları (`CertificateInventory…`, `DnsMonitor…`, `DomainMonitor…`, `HttpMonitor…`, `KeywordMonitor…`, `PageMonitor…`,
`PageSpeedMonitor…`, `PingMonitor…`, `PortMonitor…`, `ScriptedMonitor…Repository`) ✓ (yalnız `countBy…TeamId` eklemeleri).
Frontend: `DomainRegistrationTab.jsx` ✓ · `DomainMonitorPage.jsx` ✓ · `PortMonitorPage.jsx` ✓ · `ScriptedMonitorPage.jsx` ✓ ·
`MonitorCardActions.jsx` ✓ · `check/CheckTeamPicker.jsx` ✓ · `admin/StormSettings.jsx` ✓ · `admin/alerts/AlertBadges.jsx` ✓ ·
`admin/alerts/alertHistoryModel.js` ✓ · `i18n/index.jsx` ✓ (diff) · Dns/Http/Keyword/Page/PageSpeed/Ping sayfaları (tek tip
`canCheckRow` değişikliği — tüm `onCheck` bağları grep ile doğrulandı) ✓.
Testler (diff): `SchedulerServiceTest` ✓ · `MonitoringControllerTest` ✓ · `ManualEvaluationWiringTest` ✓ · `ScriptedAnomalyGuardTest` ✓ ·
`EscalationServiceTest` ✓ · `EmailTemplateBuilderTest` ✓ · `EmailSamples` ✓ · `StormServiceTest` ✓ (başlıklar) ·
`DomainRegistrationTab/Panel`, `DomainMonitorPage.detail` ✓. `DomainCheckerServiceTest`, `AlertHistory.test.jsx` — yalnız diff başlıkları.
Takipsiz: `MonitoringOutageOwnershipRegressionTest` ✓ · `SharedKeyOwnerGuardTest` ✓ · `ScriptedManualQuotaTest` ✓ ·
`ManualCheckWiringTest` ✓ · `MonitoringOutageStuckAlarmRegressionTest` ✓ · `StormTeamIsolationTest` ✓ (O-3/O-4 bölümü) ·
`SweepNoDataIsNotHealthyTest`, `SlowAndIntegrityNoDataTest`, `CertAlertOutageReconcileTest`, `MonitoringAlertLevelWordTest`,
`ManualCheckNoAlarmTest` ✓ (test adları + kapsam) · `ManualCheckMarkerQueriesTest` ✓ (tarih literalleri) ·
`ScopedAdminManualCheckGateTest`, `ScriptedMonitorPage.checkScope.test.jsx`, `checkAllServerScope.test.jsx` — okunmadı (önceki
turda okunmuş; bu turda değişmediler).
Bağlam için ayrıca (değişmemiş): `EmailNotificationService.java` (alarm/çözüm belgeleri), `UserPushService.java`
(`enqueueStormNotice`, `priorStormRecipients`, `buildMessage`), `noc/NocNotificationService.java` (fırtına açılış/tik/çözüm),
`DerivedMonitorAlertRouting.java`, `AppSettingsService.java` (işlem + olay yayını), `DnsCheckerService.changeCtxOf`,
`model/PageMonitor.java` varsayılanları, `AdminController.listAlerts`.
