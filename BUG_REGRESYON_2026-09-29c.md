# Bug regresyon taraması (c) — 20.91.0 öncesi SON delta (2026-09-29)

**Kapsam:** `BUG_REGRESYON_2026-09-29b.md` bulgularını kapatan son düzeltme turu (O-b1…O-b4, D-b1, D-b2, D-b4…D-b10, D-b13,
D-b15, D-b17, D-b19, D-b20) — `main` üzerindeki commit edilmemiş değişikliklerin bu tura ait parçaları. Salt okunur: kod
değiştirilmedi, test/derleme/sunucu koşturulmadı, kilit alınmadı; yalnız bu rapor yazıldı.
**Yöntem:** her aday kaynakta okunarak doğrulandı; çağrı zincirleri uçtan uca izlendi (tetik → `handleSweepResults` /
`handleDnsSweep` → `reconcileRecoveries` / `runConfirmAttempt` → `processConfirmedOutage` / `closeAlarm` →
`resolveOpenAlertsForDomain` / `resolveOpenAlertsSilently` → `StormService.lifecycleOne` / `retireLegacy` →
`sendStormRecovery` → `UserPushService.enqueueStormNotice` / `NocNotificationService`). Bağlayıcı kararlar:
`cert-monitor-resolve-push-recipients` (çözüm push'u = açılışta SENT olanlara), `cert-monitor-escalation-team-only`,
`cert-monitor-manual-check-storm-isolation`, `prod-log-as-defect-source`, `fix-sweep-sibling-surfaces`.

Önem: **KRİTİK 0 · YÜKSEK 0 · ORTA 2 · DÜŞÜK 16.** İki ORTA bulgunun ikisi de bu turun düzeltmelerinin (O-b2, D-b2)
getirdiği regresyondur. "(önceden var)" etiketli bulgular regresyon değildir; soru listesinin sorduğu davranışı doğrudan
etkiledikleri için listelenmiştir.

---

## ORTA

### O-c1 — O-b2 düzeltmesi bakım penceresindeki GERÇEK kurtarmayı da "sessiz" işaretliyor: fırtına üyeleri bakımda düzelirse toplu "Alarm fırtınası sona erdi" e-postası, push'u, webhook'u ve 7/24 ÇÖZÜLDÜ postası HİÇ gitmiyor

- `backend/src/main/java/com/sitemonitor/service/EscalationService.java:896-916` (`resolveOpenAlertsForDomain` bakım dalı:
  bildirim günlüğünde NOC dışı satırı olmayan türler `resolveOpenAlertsSilently(…, "Sistem (bakım penceresi — sessiz kapanış)")`),
  `:995` (`resolveOpenAlertsSilently` işareti KOŞULSUZ yazar: `event.setResolvedSilently(true)`),
  `StormService.java:793-803` (`sendStormRecovery` sessiz üyeleri eler; hepsi sessizse erken döner — e-posta, push, webhook
  ve `nocNotifications.onStormRecovered` çağrılmaz)
  | **Neden bug:** senaryo — bir takımın 6 host'u düşer, fırtına açılır; takım "N monitör birden erişilemez" e-postasını ve
  push'unu, 7/24 "FIRTINA" postasını alır. Ekip sorunu gidermek için bu host'lara bakım penceresi açar (yaygın işletim
  deseni), host'lar pencere içinde düzelir. Fırtına üyelerinin bireysel bildirimi fırtına tarafından bastırıldığı için
  (`SUPPRESSED`) olaylarında bildirim günlüğü satırı yoktur (fırtına postası olay kimliğiyle günlüğe yazılmaz) → bakım dalı
  hepsini `resolveOpenAlertsSilently` ile kapatır → `resolvedSilently=true`. ≤30 sn sonra `lifecycleOne` → `resolveStorm` →
  `sendStormRecovery` bütün kurtulanları "sessiz" sayıp **hiçbir kanaldan** çözüm göndermez. Takım "düştü"yü aldı,
  "düzeldi"yi hiç almaz; telefonlardaki fırtına bildirimi açık kalır; 7/24 ekibi fırtınayı hâlâ sürüyor sanır (ÇÖZÜLDÜ
  postası yalnız bu yoldan gider). Tur öncesinde bu üyeler "kurtarıldı" sayılıyor ve toplu çözüm gidiyordu. Aynı metodun
  yorumundaki kural açık: "açılış bildirimi GİTTİYSE çözüm de gider" (`EscalationService.java:890-895`). Bakımda kapanan
  alarm SUSTURULMADI, gerçekten KURTULDU; O-b2'nin hedefi yalnız "susturulan" (silindi / duraklatıldı / tür kapatıldı)
  üyelerdi.
  | **Çözüm:** `resolvedSilently` yalnız "susturuldu" nedenlerinde yazılsın: `resolveOpenAlertsSilently`'ye işaret parametresi
  ekle (ör. `boolean markSilent`, varsayılan true) ve bakım dalı (`:911`, `:914`) `false` ile çağırsın — ya da bakım için
  ayrı bir `resolveOpenAlertsQuietly`. Kapı: `StormSilentMemberRecoveryTest`'e "bakım penceresinde kurtulan üye kurtarıldı
  sayılır → fırtına çözüm e-postası + push + `onStormRecovered` gider" ve bakım dalının işareti YAZMADIĞINI sınayan test.

### O-c2 — D-b2 düzeltmesi (DNS değişiklik bağlamına `monitor_id`) ile, açık DNS_CHANGED olayı olan alan adında BAŞKA bir DNS izlemesinin kayıt değişikliği HİÇBİR YERE bildirilmiyor — değişiklik kalıcı olarak yutuluyor

- `MonitoringOutageService.java:1342` (`changeCtx` artık `monitor_id` taşır), `EscalationService.java:1278-1283`
  (`processConfirmedOutage`: `!sameOwner` → sessiz `return`), `MonitoringOutageService.java:855-866` (günlük yeniden uyarı
  artık olayı AÇAN izlemenin kaydını kullanır), `SchedulerService.java:5568-5588` (değişikliği gören tur satırı
  `changed=true` + yeni değerle kaydeder → sonraki turun tabanı yeni değerdir, değişiklik bir daha ALGILANMAZ)
  | **Neden bug:** DNS_CHANGED kenar tetiklidir (yalnız değişikliğin görüldüğü turda bir kez teyit zinciri başlar) ve elle
  kapanır (açık olay günler/haftalarca durabilir). Olay anahtarı alan adı|tür olduğundan aynı alan adında tek açık olay
  vardır. Senaryo (a) — farklı sahip: alan adı D'nin envanter türevi A kaydı izlemesi (sahip `INV`) DNS_CHANGED olayını
  açtı ve olay açık duruyor; takım aynı alan adına bağımsız bir NS/MX izlemesi (sahip `T:<takım>`) eklemiş. NS kaydı
  değişir (ele geçirme sinyali) → 3× teyit → `processConfirmedOutage` → açık olay var, bağlam `monitor_id`'si açanınki
  değil, sahiplik anahtarları farklı (`INV` ≠ `T:x`; aynı takım olsa bile) → sessiz `return`: e-posta yok, push yok, 7/24
  yok, olay iletisi de güncellenmez. Tur tabanı ilerlediği için değişiklik sonraki turlarda yeniden görülmez; açık olay
  kapandıktan sonra da alarm AÇILMAZ. Tur öncesinde bağlamda `monitor_id` yoktu → `sameOwner` "true" → değişiklik açık
  olayın yeniden uyarısına giriyor, en geç günlük yeniden uyarıda (alan adı düzeyindeki EN YENİ değişiklik kaydıyla)
  bildiriliyordu — yanlış takıma gitme riski (D-b2) vardı ama sinyal kaybolmuyordu. (b) — aynı sahip: iki izleme de aynı
  sahipteyse teyit anında yeniden uyarı ancak 24 sa dolmuşsa gider; dolmamışsa düşer, ve günlük yeniden uyarı artık
  AÇANIN kaydını anlattığı için ikinci izlemenin değişikliği hiçbir zaman bildirilmez (tur öncesinde alan adı düzeyindeki
  son kayıt kullanıldığından bildiriliyordu). İkincil etki (D-b3 sınıfı, durum tabanlı olduğu için kalıcı kayıp değil):
  DNS_UNEXPECTED / SLOW / INCONSISTENT de artık sahip ayrımına girdiğinden, envanter türevi ile aynı takımın bağımsız
  izlemesi "farklı sahip" sayılır; birinin açık alarmı diğerinin (ör. ele geçirme odaklı DNS_UNEXPECTED) alarmını o olay
  kapanana dek erteler.
  | **Çözüm:** sürüm için asgari — DNS_CHANGED'de sahiplik kapısı sinyal YUTMASIN: `processConfirmedOutage`'da
  `!sameOwner && DNS_CHANGED` ise ya (tercih) olaysız tekil bildirim bağlamın sahibine gönderilsin
  (`sendCombinedAlert(bağlam takımı, …, alertEventId=null)` + WARN günlüğü), ya da tur öncesi davranışa dönülsün (açık
  olaya yeniden uyarı; yanlış takım riski sinyal kaybından iyidir). Günlük yeniden uyarıda açanın kaydı ile alan adının en
  yeni değişiklik kaydından YENİ olanı kullanılsın (aynı sahipte). Kalıcı — DNS_CHANGED anahtarını izleme düzeyine indir
  (alan adı|tür|`monitor_id`; D-b3'ün önerisiyle birlikte). Kapı: "X'in açık DNS_CHANGED olayı varken Y'nin (envanter
  türevi / başka takım) değişikliği bir bildirim üretir" ve "aynı sahipte ikinci izlemenin değişikliği günlük yeniden
  uyarıda görünür".

---

## DÜŞÜK

- **D-c1** (önceden var — soru 8'in açık notu, DOĞRULANDI) `SchedulerService.java:5718-5733` (`recheckDnsChanged`:
  `success=false` → `stillChanged=false` → "up"), `MonitoringOutageService.java:1055-1059` (teyitte "up" = "Geçici
  dalgalanma" → zincir iptal), `SchedulerService.java:5568-5588` (taban yeni değere geçti) | **Neden bug:** değişiklik
  başarılı bir sorguyla GÖZLEMLENDİKTEN sonra 3 teyit yeniden ölçümünden (varsayılan 30 sn arayla) herhangi birinde ad
  çözülemezse değişiklik "geçici" sayılır ve alarm açılmaz; tur satırı tabanı ilerlettiği için değişiklik bir daha
  algılanmaz → DNS_CHANGED KALICI yutulur. Ad sunucusu geçişlerinde (NS değişikliği) geçici SERVFAIL/zaman aşımı olasılığı
  tam da bu anlarda yüksektir. Bu turun D-b4'ü kardeşleri (SLOW/UNEXPECTED) "skipped" yaptı, CHANGED atlandı. Olasılık
  düşük (denetleyicinin yedek çözümleyicisi var) ve bu turun regresyonu değil; etki kalıcı olduğu için sonraki sürümün ilk
  işi olmalı. **Dikkat:** yalnız "skipped" döndürmek YETMEZ — teyitte "skipped" de zinciri iptal eder
  (`MonitoringOutageService.java:1064-1068`) ve taban zaten ilerlemiştir; sonuç yine kalıcı kayıp olur. | **Çözüm:**
  `!success` → "down" (gözlem başarılı sorgudan geldi; çözülememek geri dönüş kanıtı değil) ya da DNS_CHANGED gibi kenar
  tetikli türlerde "skipped" zinciri iptal etmek yerine aynı denemeyi (tavanlı) yeniden planlasın; tavan aşılırsa alarm
  açılsın. Kapı: "değişiklik sonrası teyitte çözümleme hatası → alarm yine açılır".
- **D-c2** (O-b4 kenarı, doğrulanmalı) `SchedulerService.java:3775-3782` (crawl izlemesinde temiz ana sayfa turu CRAWL
  kaynaklı alarm için kalem üretmez), `:3994-4017` (`recheckIntegrityPages`: 404/410 dışı erişilemeyen kaynak sayfa →
  "skipped"), `MonitoringOutageService.java:196` (ardışık sağlıklı sayacı bellekte), `SchedulerService.java:3668`
  (günlük crawl her yeniden başlatmadan 150 sn sonra da koşar) | **Neden bug:** soru 1a'nın cevabı "evet": derin bulgunun
  kaynak sayfası kalıcı olarak ölçülemezse (403 WAF, 5xx, zaman aşımı — crawl'ın kendisi de o sayfanın kaynaklarını
  taramadığı için "temiz" der) CRAWL kaynaklı alarm yalnız ardışık 3 temiz günlük crawl ile kapanabilir (kurtarma zinciri
  her seferinde "skipped" ile biter, sayaç korunur). Sayaç bellekte olduğundan yeniden başlatma onu sıfırlar; neredeyse
  günlük sürüm temposunda 3'e hiç ulaşmayabilir → alarm fiilen süresiz açık, "Neden hâlâ açık?" ise "kontroller düzelince
  kendiliğinden kapanır" der. "Ölçülemedi ≠ sağlıklı" ilkesine uygun, ama ipucu ve kapanış yolu (elle çöz / kipi değiştir)
  kullanıcıya söylenmiyor. Ayrıca teyitte "skipped" zinciri iptal eder, yeniden deneme ancak ertesi günkü crawl'da olur
  (dalgalı kaynak sayfadaki derin bulgu hiç alarm açamayabilir). | **Çözüm:** "Neden hâlâ açık?" ipucunda CRAWL kaynaklı
  alarm için "kaynak sayfa ölçülemiyor — elle çözün ya da kipi değiştirin" satırı; kurtarma sayacını olay düzeyinde
  kalıcılaştırmak (ya da crawl kaynaklı alarmda yeniden başlatma sonrası ilk crawl'ı sayaç sıfırlamadan saymak).
- **D-c3** (O-b4 tek pod maliyeti, doğrulanmalı) `SchedulerService.java:3994-4017`, `:3968`, `MonitoringOutageService.java:178-200`
  (teyit yürütücüsü 4, kurtarma yürütücüsü 2 iş parçacığı) | **Neden bug:** bir yeniden ölçüm en çok 5 sayfa × her biri
  `site.monitor.page.max-check-seconds` (120 sn) → deneme başına 10 dk'ya kadar; eskiden tek sayfaydı. Günlük crawl tüm
  crawl izlemelerinin kalemlerini TEK seferde işler → bulgulu birkaç izleme aynı anda teyit zinciri açar ve zaman aşımı
  sınıfı bulgularda (TIMEOUT varsayılan alarm-uygun) 4 teyit iş parçacığını dakikalarca tutabilir; bu sürede Port/HTTP/Ping
  teyitleri (ve kurtarma tarafında 2 iş parçacığı) gecikir. | **Çözüm:** yeniden ölçüme toplam wall-clock tavanı (ör. 120 sn,
  sayfalar arasında paylaşılan son tarih) ya da crawl yeniden ölçümlerini ayrı, küçük bir yürütücüde koşturmak.
- **D-c4** (O-b4 kenarı) `SchedulerService.java:3775-3782`, `EscalationService.java:1270-1383` (yeniden uyarı dalı olayın
  bağlamını/kaynağını güncellemez) | **Neden bug:** crawl izlemesinde ana sayfa kaynaklı (HOME) alarm açıkken günlük crawl
  derin bir bulgu bildirirse kalem açık olayın yeniden uyarısına girer ama olayın `integrity_scope`'u HOME kalır; dakikalar
  içinde temiz ana sayfa turları olayı "ÇÖZÜLDÜ" diye kapatır, derin bulgu ancak ertesi günkü crawl + teyitle yeni INITIAL
  olarak açılır (kapan/aç + 24 sa gecikme). | **Çözüm:** CRAWL kaynaklı DOWN kalemi HOME olayına düştüğünde olay bağlamını
  (`integrity_scope`, `problem_pages`) güncelle.
- **D-c5** (D-b1 kenarı) `EscalationService.java:1010-1015` (`closableBy`: açan biliniyorsa YALNIZ açan kapatır),
  `SchedulerService.java:3631-3635` / `:2991` / `:3110` (öksüz temizliği `findAll` — duraklatılmış izleme de "var" sayılır)
  | **Neden bug:** soru 1b: HTTP / İçerik / Sayfa / Sayfa Hızı izlemesinin URL'si düzenlenince eski anahtardaki açık olay
  kapatılmaz (bu türlerde URL değişikliği kapanışı yok, öksüz temizliğine bırakılır). Açan izleme X anahtardan ayrılmışken
  aynı eski URL'yi izleyen Y duraklatılırsa: `closableBy` "açan X, Y değil" der ve atlar; X artık o URL'de değil, Y
  duraklatıldığı için tur yok; öksüz temizliği Y'yi var saydığından dokunmaz → olay kimsenin kapatamadığı hayalet olur (Y
  silinene ya da elle çözülene dek). Tur öncesinde Y'nin duraklatılması anahtardaki tüm olayları kapatıyordu. Açanı
  bilinmeyen eski olaylar yalnız ACCESSIBILITY ve DNS_* türlerindedir (diğer türlerde `monitor_id` tur öncesinden beri anlık
  görüntüde) → bu yol geniş değil. | **Çözüm:** `closableBy`'de açan izleme artık o anahtarda aktif değilse (silinmiş /
  anahtarı değişmiş) sahiplik anahtarı eşleşmesine düş; ya da URL düzenlemesinde eski anahtarın o izlemeye ait olayını
  `ownerCtx` ile kapat (Ping/DNS'teki desen).
- **D-c6** (D-b6 kısmi) `StormService.java:433-443` | **Neden bug:** `legacyStormId` yalnız takım fırtınası emeklilik sırasında
  YENİ oluşturulduysa yazılır. Yükseltmeden sonraki 15 dk içinde aynı takımın yeni alarmları zaten bir takım fırtınası
  kurmuşsa üyeler ona taşınır ama eski kimlik yazılmaz; çözüm push'u yalnız yeni fırtınanın açılışını alanlara gider. Eski
  fırtınanın açılışı sabit CRITICAL seviyeyle (yöneticiler dâhil) gittiği, yeni fırtına ise üyelerin seviyesiyle (ör.
  WARNING → yalnız takım) açıldığı için yöneticiler "düştü"yü alıp "düzeldi"yi almaz. Tek seferlik geçiş etkisi. | **Çözüm:**
  hedef fırtına zaten varsa da `legacyStormId` boşsa yaz (ya da taşınan üyeler için çözüm alıcılarına eski fırtınanın
  takım süzgeçli alıcılarını ekle).
- **D-c7** (seviye kapısının dışında kalan kanal) `EmailNotificationService.java:1618` (fırtına e-postası rozeti sabit
  `Badge.solid("KRİTİK")`), `StormService.java:724` (webhook seviyesi sabit "CRITICAL"), `noc/NocNotificationService.java:310`
  (7/24 fırtına postası `variantForLevel("CRITICAL")`) | **Neden bug:** bu sürümde fırtına push'u üyelerin en yüksek
  seviyesiyle gider (`stormPushLevel`: WARNING üyeli fırtına "UYARI"); aynı fırtınanın takım e-postası "KRİTİK" rozeti taşır
  — prod olayının teması (UYARI ↔ KRİTİK) kanallar arasında sürüyor. `AlertLevelWordConsistencyGateTest` fırtına kanalını
  sınamıyor. (önceden var; yeni push seviyesiyle görünür oldu.) | **Çözüm:** `buildStormAlertHtml`'e seviye geçir, rozet
  `EmailTemplateBuilder.severityBadge(stormPushLevel(üyeler))`; webhook ve 7/24 varyantı aynı seviyeden; kapıya fırtına dalı.
- **D-c8** (kozmetik) `EmailNotificationService.java:1399`, `:1711`, `:1758` | **Neden bug:** O-b1 sonrası WARNING izleme
  e-postasında rozet ve "Seviye" satırı "UYARI" (mavi/tonlu), ama hemen altındaki uyarı kutusu hâlâ `Tone.DESTRUCTIVE`
  (kırmızı). | **Çözüm:** `d.alert(EmailTemplateBuilder.severityTone(level), …)`.
- **D-c9** (O-b2 tasarım tutarsızlığı) `StormService.java:798-802`, `EscalationService.java:997-1000` | **Neden bug:**
  bireysel sessiz kapanışta çözüm push'u açılış push'unu almış kişilere YİNE gider (telefonda "düştü" açık kalmasın — push
  simetri kuralı); fırtınada ise üyelerin tamamı sessiz kapanınca fırtına açılış push'unu alanlara hiçbir şey gitmez →
  telefondaki fırtına bildirimi süresiz açık kalır. CHANGELOG'da yazılı bilinçli karar, ama simetri kuralıyla çelişiyor.
  | **Çözüm:** hepsi sessizse "kurtarıldı" demeyen nötr bir kapanış push'u ("Fırtına kapandı — izlemeler durduruldu /
  bildirimleri kapatıldı") yalnız açılışı alanlara.
- **D-c10** (geçiş) `StormService.java:461-471` | **Neden bug:** yükseltme öncesi sessiz kapanan olaylarda
  `resolved_silently` NULL'dır → eski fırtına emekliye ayrılırken önceden silinmiş/duraklatılmış üyeler "kurtarıldı" sayılır
  (tek seferlik). | **Çözüm:** kabul edilebilir; istenirse emeklilikte `resolvedBy` "Sistem (izleme silindi / duraklatıldı /
  … kapatıldı)" önekli üyeleri de ele (yalnız geçiş için metin eşleştirmesi).
- **D-c11** (önceden var) `WeeklyAvailabilityReportService.java:467-482`, `AlertNoiseService.java:159`,
  `IncidentsController`/Olaylar | **Neden bug:** soru 2: yeni bayrak YALNIZ fırtına çözümünde okunuyor; haftalık rapor,
  MTTR ve alarm gürültüsü sessiz kapanışları (silindi/duraklatıldı/tür kapatıldı) gerçek çözüm gibi sayar — MTTR ve "çözülen"
  sayısı şişer. Bu tur bozmadı, ama bayrak artık bunu ayırt etmeye izin veriyor. | **Çözüm:** MTTR/çözülen sayımında
  `resolved_silently=true` satırları ayrı kovaya al.
- **D-c12** (D-b8 kardeşi) `MonitoringOutageService.java:1211-1214` | **Neden bug:** ayar kaydı yolu arka plana alındı; açılış
  yolu (`onReadyCloseDisabledTypeAlarms`) hâlâ `ApplicationReadyEvent`'te ana iş parçacığında EŞZAMANLI — Spring Boot
  hazır olma durumunu (ACCEPTING_TRAFFIC) bu dinleyici bitince yayınlar; kapalı türde yüzlerce açık alarm varsa açılış
  hazırlık süresi uzar. | **Çözüm:** açılış uzlaştırmasını da `settingsReconcileExecutor`'a ver.
- **D-c13** (D-10 kullanılabilirlik) `frontend/src/components/check/monitorCheckColumns.jsx:22-25` | **Neden bug:** sentetik toplu
  kontrol eşzamanlılığı sabit 1; havuz büyütülse de (ör. 4 → elle kota 3) toplu kontrol tek tek koşar, 25 sn'yi aşan her
  koşumdan sonra sıradaki "havuz dolu → atlandı" olabilir. | **Çözüm:** eşzamanlılığı sunucudan (`pool − 1`) al.
- **D-c14** (retireLegacy kenarı) `StormService.java:445-448` | **Neden bug:** `moveToStormIfOpen` 0 döndüğünde (üye bu arada
  çözüldü) `releaseFromStormAsNotified` da 0 döner ama `released++` sayılır — yalnız günlük sayısı yanlış. | **Çözüm:**
  dönüş değerine göre say.
- **D-c15** (test) `backend/src/test/java/com/sitemonitor/service/StormSilentMemberRecoveryTest.java:98-112`,
  `AlertLevelWordConsistencyGateTest.java:113-146` | **Neden bug:** üçüncü testin adı "normal çözüm yazmaz" diyor ama normal
  yolu sınamıyor; bakım dalı hiç sınanmıyor (O-c1'i yakalardı). Seviye kapısı NOC ve fırtına kanallarını kapsamıyor (NOC
  sözlüğü `EmailTemplateBuilderTest`'te tek satırla pinli). | **Çözüm:** bakım dalı + normal çözüm için işaret testleri;
  kapıya fırtına e-postası/webhook dalı.
- **D-c16** (önceden var — kimlik hijyeni) `backend/src/test/java/com/sitemonitor/controller/MonitoringControllerTest.java:1583-1584`,
  `:1792-1888` | **Neden bug:** iki gerçek görünümlü kurumsal `.com.tr` alan adı fixture olarak kullanılıyor (adlar bu rapora
  yazılmadı); `no-real-identifiers-in-code` kuralı, IdentityLeakGuard listesinde olmadıkları için kapı yakalamıyor. Bu turda
  EKLENMEDİ (turun eklenen test satırlarında `.com.tr` sayısı 0). | **Çözüm:** `example.com` yer tutucusuna çevir.

---

## Soru listesi 1–8

**1. Yanlış kapanış / hayalet geri dönüşü — BULGU (DÜŞÜK: D-c2, D-c4, D-c5).**
- Sayfa bütünlüğü kaynak kanıtı: teyit/kurtarma yeniden ölçümü kaynağa bakıyor (derin → sorunlu sayfalar, en çok 5; ana
  sayfa → ana sayfa); 404/410 = kaynak sayfa kaldırıldı → temiz; ölçülemeyen → "skipped" (teyitte zincir iptal, kurtarmada
  zincir biter, sayaç korunur) ✓. Bağlantı (LINK) bulgusu SINGLE_PAGE yeniden ölçümünde de yeniden doğrulanıyor (tek sayfa
  kontrolü a[href]'leri de doğruluyor) ✓. Kısmi crawl (deadline) "temiz" dese de kurtarma yeniden ölçümü sorunlu sayfaya
  baktığı için yanlış kapanış yok ✓. Crawl kaynaklı alarmda ana sayfa temizliği kanıt sayılmıyor; başka takımın aynı URL'deki
  tek sayfa izlemesinin temiz turu da olayın sahibinin son DOWN gözlemi (26 sa TTL) nedeniyle kapatamıyor ✓.
- Kalıcı ölçülemeyen kaynak sayfa → alarm fiilen süresiz açık kalabilir (D-c2). HOME olayına düşen derin bulgu → kapan/aç
  (D-c4).
- `closableBy`: açan biliniyorsa yalnız açan kapatır, bilinmiyorsa sahiplik anahtarı. `monitor_id` HTTP / İçerik / Ping /
  Port / Sayfa / Sayfa Hızı / Sentetik / Alan adı bağlamlarında tur öncesinden beri anlık görüntüde → "açanı bilinmeyen"
  olay yalnız ACCESSIBILITY (bu uçlardan kapanmaz) ve eski DNS_* olayları. Silinen izlemenin olayı: açan o ise kapanır;
  değilse açan izleme hâlâ o anahtardadır ve kendi kurtarmasıyla kapatır; izleme kalmadıysa öksüz temizliği (3 argümanlı
  çağrı, sahip süzgeçsiz) kapatır ✓. Tek hayalet yolu: açan anahtardan ayrılmış + kalan kardeş DURAKLATILMIŞ (D-c5). DNS'te
  öksüz temizliği yok ama Port/DNS silme uçları zaten alarm kapatmıyor (önceden var, bu tur değiştirmedi).

**2. Sessiz kapanış bayrağı — BULGU (ORTA: O-c1; DÜŞÜK: D-c9, D-c10, D-c11).**
- Yazan yollar: `resolveOpenAlertsSilently` (duraklatma, silme, host/alan adı değişikliği, öksüz temizliği, envanterde aktif
  değil, tür bildirimleri kapatıldı — toplu ve `closeAlarm` sessiz dalı, sertifika sessiz kümesi, anomali koruması, BAKIM
  PENCERESİ) + envanter pasifleşmesi / silinmesi (`EscalationService.java:1719`, `:1739`) ✓ — tüm sessiz yollar yazıyor.
- Normal kapanış yazmıyor: `resolveOpenAlertsForDomain` taze varlığı `markResolvedIfOpen` + `save` ile kapatır (alan NULL);
  elle çözüm (`resolve`) yazmaz; olay yeniden açılmadığı için bayrak taşınmaz ✓. Yanlış yazılan tek yol bakım penceresi
  (O-c1).
- Tüketiciler: yalnız `StormService.sendStormRecovery` (resolveStorm / disband / retireLegacy hepsi oradan) ✓. Bireysel
  çözüm e-postası sessiz yolda zaten çağrılmıyor; çözüm push'u simetri kuralıyla açılışı alanlara gidiyor (fırtınayla
  tutarsızlık D-c9). Raporlar bayrağı okumuyor (D-c11). NULL eski satırlar = normal kapanış (D-c10, tek seferlik).

**3. AFTER_COMMIT toplu kapanış — TEMİZ (+ D-c12).** `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` →
işlem yoksa dinleyici HEMEN koşar; işlem geri alınırsa kapanış da koşmaz (doğru). Özel `applicationEventMulticaster` yok
(yayın eşzamanlı; AFTER_COMMIT anlamı bozulmaz). Tek iş parçacıklı yürütücü `@PreDestroy`'da `shutdownNow`; yarıda kalan iş
kaybolur ama açılıştaki `onReadyCloseDisabledTypeAlarms` DB'den yeniden uzlaştırdığı için kalıcı kayıp yok ✓. Çok pod: olay
yalnız ayarı kaydeden podda koşar; uzlaştırma DB genelinde tek sefer yeterli, diğer podların sweep'i kapalı türde yalnız
kapanış uzlaştırması yapar ✓. Ayar önbelleği `save` içinde, olaydan önce tazeleniyor → dinleyici yeni değeri görür ✓.
DNS_CHANGED / DOMAINMON_CHANGED hariç tutuluyor (D-b9) ✓.

**4. Seviye sözcüğü kapısı — BULGU (DÜŞÜK: D-c7, D-c8, D-c15).** Kapı `MONITORING_ALERT_TYPES` × {WARNING, HIGH, CRITICAL} ×
{ileti, e-posta metin + HTML, çözüm e-postası (seviye sözcüğü içeriyorsa), push} geziyor; "öteki iki sözcük geçmesin"
iddiasıyla ✓. Kapsam dışı: INFO/LOW (izleme alarmları kullanmıyor), 7/24 (sözlük aynı fonksiyon — `NocMailComposer.levelTr`
→ `levelWordTr`, pinli), fırtına kanalları (D-c7). Galeriye WARNING Port örneği eklendi; `EmailResponsiveContractTest` ve
`e2e/email-gallery.spec.js` yalnız yerleşim sınıyor, anlık görüntü tabanı yok → yeni örnek kapı kırmaz ✓. Sertifika
e-postaları ETKİLENDİ (bilinçli, CHANGELOG'da): WARNING sertifika alarmının konusu ve rozeti "ORTA" yerine "UYARI"
(`EscalationService.java:2256`, `EmailTemplateBuilder.java:98-104`); `severityLabel(null)` artık "UYARI". İşletim notu:
posta kutusu kuralları konu satırındaki "ORTA"ya bağlıysa kırılır. Olay (incident) şiddet sözlüğü (MEDIUM → "ORTA") ayrı ve
değişmedi ✓.

**5. Fırtına geçişi — TEMİZ (+ D-c6).** `priorStormRecipients(legacyId, teamId)` teslimat satırlarını `teamId` ile süzer; eski
ACCOUNT fırtınası 20.89'dan beri push'u takım başına (`storm:<id>:…`, SY takımı) yazdığı için yalnız o takımın açılışını
almış kişiler eklenir — başka takımın alıcısı girmez ✓ (takımın rol grupları, ör. yöneticiler, açılışı o takım satırıyla
aldıysa çözümü de alır — simetri kuralı). Eski fırtınanın kendi toplu çözümü de takım başına, yalnız o takımın önceki
alıcılarına ✓. `legacyOpen` yalnız `legacyStormId` dolu (yalnız `retireLegacy`'nin oluşturduğu) fırtınalarda devreye girer;
sonraki yeni fırtınalar bu alanı taşımaz → 7/24 açılışı yanlışlıkla bastırılmaz ✓. Eski açılış FAILED ise yeni fırtına
açılışı gönderilir; taşınmadan sonra katılan üyeler güncelleme postasıyla gider; 7/24 çözümü üye bazlı
`alert:<id>:OPEN` izine baktığı için taşınan üyelerde de doğru ✓. `releaseFromStormAsNotified` / `moveToStormIfOpen`
koşullu ve atomik; kurtulanlar kapanıştan sonra yeniden okunuyor (D-b7) ✓.

**6. Elle kota bütçesi — TEMİZ (+ D-c13).** `max(0, min(uç − 5, timeout + 30))` ve uç ≥ 1 → bütçe negatif OLAMAZ; uç ≤ 5 iken
bütçe 0 = hiç beklemeden dene (kota ve izin o an boşsa koşar, değilse hemen "yürütülmedi") — makul. Elle kökenli kurtarma
zinciri bütçe 0 ile koşar (kurtarma yürütücüsünü tıkamaz) ✓. Sıra sayacı ve izinler tüm yollarda `finally`'de iade ✓. Uç ile
denetleyici aynı anahtarı okuyor; sanal iş parçacığı havuzu görevi hemen başlattığı için "yürütülmedi" her zaman uca yetişir
✓. Toplu penceredeki "atlandı" sayısı yalnız `skipped_code=MANUAL_POOL_BUSY` satırları; "kuyrukta" dönen satırlar gerçekten
koşan koşumlar → sayı doğru ✓.

**7. Testler — TEMİZ (+ D-c15, D-c16).** Bu turun eklenen test satırlarında `LocalDate.now()` yok; tarih literalleri yalnız
sıralama/veri ve sabit `Clock` ✓. `ScriptedManualQuotaTest` mandal tabanlı, uyku/süre iddiası yok (D-b19 kapandı); yeniden
stub (`when(settings.getInt…)`) yapılırken çalışan iş parçacığı mock'a dokunmuyor (koşum içinde mandal bekliyor) →
Mockito yarışı yok ✓. Tel biçimi snake_case (`skipped_code`, `can_check`, `integrity_scope`, `problem_pages`) ✓. Kimlik:
yeni testler `example.com` / "Takım A" / "İzleme A" kullanıyor; D-b20 kapandı (`DomainRegistrationTab.test.jsx`'te yer tutucu
dışı alan adı 0) ✓; önceden var olan kurumsal alan adı D-c16.

**8. Açık not (`recheckDnsChanged` çözülemezse "up") — DOĞRULANDI, önem DÜŞÜK (önceden var) → D-c1.** Değişiklik kalıcı
yutulur (taban ilerlediği için yeniden algılanmaz); yalnız "skipped" yapmak çözmez.

---

## Önceki raporun (b) bulgularının kapanış doğrulaması

| Kod | Durum | Not |
|---|---|---|
| O-b1 | kapandı | altı tür + iki çözüm belgesi seviyeden; kapı eklendi (fırtına kanalı hariç — D-c7) |
| O-b2 | kapandı, regresyonla | bakım penceresi de sessiz sayılıyor (O-c1) |
| O-b3 | kapandı | bütçe uçtan 5 sn kısa, "atlandı" sayısı ilerleme penceresinde |
| O-b4 | kapandı | kenarlar D-c2, D-c3, D-c4 |
| D-b1 | kapandı | 18 çağrı yeri `ownerCtx` taşıyor; kenar D-c5 |
| D-b2 | kapandı, regresyonla | DNS_CHANGED sinyal kaybı (O-c2) |
| D-b4, D-b5, D-b10, D-b13, D-b14, D-b15, D-b17, D-b19, D-b20 | kapandı | — |
| D-b6 | kısmen | hedef fırtına önceden varsa eski kimlik yazılmıyor (D-c6) |
| D-b7 | kapandı | koşullu serbest bırakma + sonda yeniden okuma |
| D-b8 | kapandı | açılış yolu hâlâ eşzamanlı (D-c12) |
| D-b9 | kapandı | değişiklik türleri toplu sessiz kapanıştan hariç |

---

## Özet tablo

| Kod | Önem | Dosya:satır | Kısa |
|---|---|---|---|
| O-c1 | ORTA | `EscalationService.java:896-916, 995`, `StormService.java:793-803` | Bakımda düzelen fırtına üyeleri "sessiz" sayılıyor → fırtına çözümü hiçbir kanaldan gitmiyor |
| O-c2 | ORTA | `MonitoringOutageService.java:1342, 855-866`, `EscalationService.java:1278-1283` | Açık DNS_CHANGED olayı olan alan adında başka izlemenin değişikliği kalıcı yutuluyor |
| D-c1 | DÜŞÜK | `SchedulerService.java:5718-5733` | (önceden var) teyitte çözümleme hatası DNS değişikliğini kalıcı yutuyor |
| D-c2…D-c16 | DÜŞÜK | yukarıda | O-b4 kenarları/maliyeti, D-b1 hayalet yolu, geçiş, kanal tutarlılığı, raporlar, test/kimlik hijyeni |

---

## Okundu ✓

Kaynak raporlar: `BUG_REGRESYON_2026-09-29b.md` ✓ · `CHANGELOG.md` `[Unreleased]` ✓ · bellek notları
(cert-monitor-resolve-push-recipients, cert-monitor-escalation-team-only, prod-log-as-defect-source, dogrulamadan-varsayma,
fix-sweep-sibling-surfaces, cert-monitor-single-pod-perf, mockito-restub-worker-race, test-fixed-date-time-bomb,
no-real-identifiers-in-code — MEMORY dizini üzerinden) ✓.

Değişmiş (bu turun parçaları, satır satır): `service/EmailNotificationService.java` ✓ (diff + `levelRow`, alarm/çözüm
belgeleri, fırtına e-postası) · `service/EmailTemplateBuilder.java` ✓ · `service/UserPushService.java` ✓ (diff +
`enqueueStormNotice` iki imza, `priorStormRecipients`, `stormBlockReason`, `buildMessage`) · `model/AlertEvent.java` ✓ ·
`model/AlertStorm.java` ✓ · `repository/AlertEventRepository.java` ✓ · `service/StormService.java` ✓ (tamamı) ·
`service/noc/NocNotificationService.java` ✓ (fırtına açılış/tik/çözüm + `legacyOpen`, `blocking`) ·
`service/EscalationService.java` ✓ (diff + `resolveOpenAlertsForDomain` bakım dalı, `resolveOpenAlertsSilently`, `closableBy`,
`sameOwner`/`ownerKeyOf`/`contextMonitorId`, `processConfirmedOutage` yeniden uyarı dalı, öksüz temizlikleri,
`levelWordTr`/`withLevelWord`, `closeStaleCertTypes`, `resolveVerifiedStaleCertAlerts`, `processResults` ön yükleme,
`snapshotContext`/`RESOLVED_CONTEXT_KEYS`, `openAlertContext`) · `service/MonitoringOutageService.java` ✓ (diff +
`runConfirmAttempt`, `startRecovery`/`runRecoveryAttempt`, `reconcileRecoveries`, `recoverDomain`, `closeAlarm`,
`handleDnsSweep`, `changeCtx`, `onSettingsChanged`, `closeAlarmsOfDisabledTypes`, `alertEnabled`, yürütücüler) ·
`service/SchedulerService.java` ✓ (diff + `isStillMonitored`, `addPageSweepItems`, `recheckIntegrityPages`,
`openIntegrity*`, `runPageChecks`/`runPageCrawls`, DNS turu ve `recheckDnsSlow`/`Unexpected`/`Changed`, `addDomainSweepItems`,
`eppStatusMeasured`, `recheckDomainFor`, `triggerScriptedCheckAsync`, `recheckScripted`, port/senaryo kalemleri, yamalar) ·
`service/ScriptedCheckerService.java` ✓ (diff: kota, bütçe, `runManual`, `withManualQuota`) · `service/DnsCheckerService.java` ✓ ·
`service/DomainCheckerService.java` ✓ (diff + değişiklik tespiti) · `repository/DnsRecordRepository.java` ✓ ·
`repository/DomainCheckRepository.java` ✓ · `model/DnsRecord.java` ✓ · `model/DomainCheck.java` ✓ ·
`controller/MonitoringController.java` ✓ (diff: 18 `ownerCtx` çağrı yeri, `closeAlertsOnPause`, `ownerCtx`/`ownerCtxDual`,
`withCheckFlag`, sentetik tetik ucu, Port/DNS silme uçları) · `service/AppSettingsService.java` (`save`/`publish`) ✓ ·
`service/PageCheckerService.java` (tek sayfa/crawl, `countsForAlarm`, `verifyOne`) ✓ · `model/PageMonitor.java`
(teyit/kurtarma varsayılanları) ✓.
Frontend: `components/check/MonitorCheckRunModal.jsx` ✓ · `components/check/CheckRunShell.jsx` ✓ ·
`components/check/monitorCheckColumns.jsx` ✓ · `hooks/useCheckRun.js` (satır biçimi) ✓ · `i18n/index.jsx` (yeni anahtarlar
TR/EN) ✓ · `ScriptedMonitorPage.jsx` (`skippedReasonOf`) ✓.
Testler: `AlertLevelWordConsistencyGateTest` ✓ · `StormSilentMemberRecoveryTest` ✓ · `PageIntegritySourceRecheckTest` ✓ ·
`repository/LegacyStormMoveQueriesTest` ✓ · `ScriptedManualQuotaTest` ✓ · `StormTeamIsolationTest` (test başlıkları, O-3/D-b7
bölümü) ✓ · `SharedKeyOwnerGuardTest` (başlıklar) ✓ · diff'ler: `MonitoringOutageServiceTest`, `UserPushServiceTest`,
`NocNotificationServiceTest`, `StormServiceTest`, `EmailSamples`, `EmailTemplateBuilderTest`, `MonitoringControllerTest`
(başlıklar) ✓ · `frontend/src/test/ScriptedMonitorPage.checkScope.test.jsx` (vakalar) ✓ · `frontend/e2e/email-gallery.spec.js`
(iddialar) ✓. Eklenen test satırlarında saat/tarih ve yer tutucu dışı alan adı taraması (maskeli) ✓.
