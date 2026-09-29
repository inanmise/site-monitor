# Bug regresyon taraması — 20.91.0 öncesi (2026-09-29)

**Kapsam:** `main` (son yayın 20.90.0 = `49091633`) üzerindeki commit edilmemiş TÜM değişiklikler — `git status --short`
51 değişmiş + 10 takipsiz dosya. Salt okunur: kod değiştirilmedi, test/derleme/sunucu koşturulmadı.
**Yöntem:** her aday kaynakta okunarak doğrulandı (grep eşleşmesi bulgu sayılmadı); çağrı zincirleri (tetik ucu →
`evaluate*Now` → `handleSweepResults` → `EscalationService` / `StormService`) uçtan uca izlendi. Rehber:
`D:\site-monitor-shadcn\.migration\port-alarm-rca\PROD_DIAGNOSIS.md`; bağlayıcı kararlar:
`cert-monitor-manual-check-storm-isolation`, `cert-monitor-escalation-team-only`, `CLAUDE.md` "Escalation & notifications".

Önem: **KRİTİK 1 · YÜKSEK 1 · ORTA 5 · DÜŞÜK 15**. "Önceden var" etiketli bulgular bu diff'in getirdiği regresyon
değildir; kontrol listesinin sorduğu davranışı etkiledikleri için listelenmiştir.

---

## KRİTİK

### K-1 — Alan adı elle kontrolü / Kayıt sekmesi DOMAINMON_CHANGED (ele geçirme sinyali) alarmını KALICI olarak yutuyor (REGRESYON)

- `backend/src/main/java/com/sitemonitor/service/DomainCheckerService.java:145` — değişiklik tabanı
  `findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(monitorId, "NONE")` elle yazılan `domain_checks` satırlarını da
  taban alıyor | **Neden bug:** `POST /domain/{id}/check` (`MonitoringController.java:5566`) ve Kayıt sekmesinin canlı
  sorgusu `GET /domain/{id}/registration?live=true` (`MonitoringController.java:5596`) `domainChecker.check(m)` ile
  YENİ `DomainCheck` satırını (`changed=true`) kalıcılaştırıyor; ardından `evaluateDomainAlarmsNow` artık
  `manual=true` (`SchedulerService.java:5046-5047`) → yalnız kapanış uzlaştırması, DOMAINMON_CHANGED açılmıyor.
  Sonraki zamanlanmış günlük kontrol, elle satırı taban aldığı için `changed=false` görüyor
  (`SchedulerService.java:5024-5025` yalnız değişim anında kalem üretir) → nameserver / registrar / EPP / **DNSSEC
  kapatılması** değişikliği HİÇBİR ZAMAN alarm olmuyor. Diff öncesi elle yol işaretsiz `handleDomainSweep` ile alarmı
  ANINDA açıyordu; DNS için aynı sınıf (`dns_records.manual` + `findLatestScheduledSuccessful`) düzeltildi ama kardeş
  yüzey alan adı atlandı. Tetik çok olası: `frontend/src/components/DomainRegistrationTab.jsx:61`
  (`useEffect(() => { load(true) })`) — Kayıt sekmesini AÇMAK bile canlı sorgu yapıyor (çalıştırma yetkisi olan her
  kullanıcıda). Günlük kontrol aralığı (jitter'lı, ≤24 sa) içinde sekmeyi açan tek kişi sinyali siler. Ek (önceden var):
  `SchedulerService.java:5129-5130` `recheckDomainFor` teyit/kurtarma yeniden kontrolleri de satır yazıp tabanı
  ilerletiyor. | **Çözüm:** `domain_checks.manual` (NULL = zamanlanmış) kolonu + idempotent `patch(...)`; tetik ucu ve
  `live=true` yolu `manual=true` yazsın (tercihen recheck satırları da ayrı işaretlensin); zamanlanmış değişiklik tabanı
  yalnız zamanlanmış + `source<>'NONE'` satırlardan okunsun (DNS'teki `findLatestScheduledSuccessful` eşleniği). Kayıt
  sekmesi açılışta `live=false` okusun, canlı sorgu düğmeyle yapılsın. Kapı: `ManualCheckMarkerQueriesTest`'e alan adı
  tabanı + `SchedulerServiceTest`'e "elle kontrol değişikliği gördükten sonra sweep DOMAINMON_CHANGED üretir" testi.
  CLAUDE.md / beyaz kâğıt "DNS değişiklik algısını tüketmez" cümlesi alan adını da kapsayacak şekilde güncellenmeli
  (PDF'ler `npm run gen:guide-pdf`).

---

## YÜKSEK

### Y-1 — D2 "kardeş hâlâ DOWN" kuralı takım-kör: başka takımın izlemesi alarmı açık tutuyor, bildirimi yanlış takıma yönlendiriyor (REGRESYON — bölünmüş turlarda)

- `backend/src/main/java/com/sitemonitor/service/MonitoringOutageService.java:647-665` (`downSibling`) ve `:576-585`
  (`recoverDomain` bekletme dalı) — kardeş eşleşmesi yalnız `tür + alan adı`; izlemenin takımına bakılmıyor | **Neden
  bug:** anahtar (alan adı + tür) takımlar arasında paylaşılabilir: bağımsız Port/DNS + envanter-türevi aynı host
  (dual-source), iki takımın aynı URL'li HTTP/İçerik izlemesi (`HttpMonitorRepository.findByUrl` yorumu: "takımlar arası
  aynı URL olabilir"), iki takımda aynı adlı senaryo. Senaryo: Takım A'nın izlemesi düşer → alarm A damgasıyla açılır;
  A düzelir, Takım B'nin aynı anahtardaki izlemesi DOWN. Diff öncesi (ayrı turlarda) A'nın sağlıklı turu alarmı kapatıyor,
  B'nin sonraki turu B bağlamıyla YENİ alarm açıyordu (doğru takım). Şimdi D2 alarmı A adına açık tutuyor; B'nin DOWN
  turu `processConfirmedOutage` → mevcut olay → `EscalationService.java:1235-1237` damga = A → **günlük yeniden uyarı A'ya
  gidiyor, B hiç bildirim almıyor**; ileti B'nin bağlamından kuruluyor (`EscalationService.java:1195`, port/izleme adı
  A'ya sızar). "Her takıma yalnız KENDİ izlemelerinin alarmları gider" kararına aykırı. (Aynı turdaki karışım önceden
  de vardı; D2 bunu bölünmüş turlarda kalıcı hâle getirdi.) | **Çözüm:** `recoverDomain`'e açık olayın `teamId`'sini
  geçip `downSibling`'de yalnız aynı etkin takımdaki kardeşleri say (ctx `team_id`; türev satırda null → envanter SY).
  Kalıcı çözüm: alarm anahtarına takımı katmak (alan adı|tür|takım) — alarm→olay→sunum zincirinin her halkası
  (`findOpenAlert`, öksüz temizlikleri, Olaylar/Outage, incidentMeta) birlikte. Kapı testi: "A'nın alarmı, B'nin DOWN
  kardeşiyle açık tutulmaz; B kendi alarmını açar".

---

## ORTA

### O-1 — Elle "Şimdi kontrol et" tıklamaları kurtarma aralığını atlıyor: N hızlı tıklama alarmı saniyeler içinde kapatıyor

- `MonitoringOutageService.java:586-598` (her `recoverDomain` çağrısı `recoveryUpCount.merge` yapar) + `:436-439`
  (elle yol da `recoverDomain`'e girer) | **Neden bug:** `recoveryChecks=3 / recoveryIntervalSeconds=30` (varsayılan)
  "30 sn arayla 3 ardışık sağlıklı" demek; elle yol sayacı zaman aralığı gözetmeden artırıyor. Port/HTTP/İçerik/Ping/
  DNS/Alan adı tetik uçlarında bekleme yok (`MonitoringController.java:293-295` yalnız sayfa/sentetik/sayfa hızı);
  3 tık ≈ 2 sn'de kapanış + "✅ ÇÖZÜLDÜ" e-postası. Dalgalı hedefte kapan/aç döngüsü (her açılışta yeni INITIAL). PROD
  teşhis §10'daki "~1 dk içinde (3 ardışık)" beklentisiyle de çelişir. | **Çözüm:** elle yolda sayacı artırma — yalnız
  aktif zinciri başlat (zincir aralıklı yeniden kontrolle kapatır), ya da anahtar başına "son sayılan an" tutup
  `recoveryInterval` içinde gelen artışları yok say. Test: 3 ardışık elle UP, zincir koşmadan kapanmamalı.

### O-2 — Yürütülemeyen (SKIPPED) elle sentetik koşum SCRIPTED_SLOW'u "sağlıklı" sayıp kanıtsız kapatabiliyor

- `SchedulerService.java:4519-4528` (`triggerScriptedCheckAsync` → `evaluateScriptedNow(m, r)` sonuç SKIPPED olsa da
  çağrılıyor), `:4316-4323` (SKIPPED çıktısı `up=null`), `:4266-4270` (`slowDown = enabled && up && …` → `false` →
  SLOW kalemi "up") | **Neden bug:** toplu elle kontrolde k6 havuzu (varsayılan 2 permit) doluyor → koşumlar SKIPPED;
  açık SCRIPTED_SLOW alarmı olan izlemede kurtarma sayacı artıyor (izleme `recoveryChecks=1` ise ANINDA kapanış +
  bildirim). Zamanlanmış sweep SKIPPED'i hiç kaleme koymuyor (`:4197`) — elle yol bu kuralı ihlal ediyor. Ayrıca FAIL
  kalemi DOWN sayılıp sahte D2 "DOWN gözlemi" yazılıyor. | **Çözüm:** `evaluateScriptedNow` (ya da
  `triggerScriptedCheckAsync`) başında `Boolean.TRUE.equals(r.get("skipped"))` ise değerlendirmeyi atla.

### O-3 — Eski ACCOUNT fırtınasının dağıtılması ilk uzlaştırmadan ÖNCE koşuyor: üyeler takım fırtınasına toplanmadan tek tek bildirim üretiyor

- `StormService.java:280` + `:347-370` (`disband` → `unlinkFromStorm`), `AlertEventRepository.java:118`
  (`lastReAlertAt = null`), `EscalationService.java:1303-1313` ("yarım kalmış ilk bildirim" dalı) ve `:944` | **Neden
  bug:** yükseltme sonrası `lifecycleSweep` 45. sn'de (`StormService.java:261`) eski fırtınayı dağıtıyor; izleme
  sweep'leri 45–95. sn'de başlıyor. Hâlâ DOWN üyeler `lastReAlertAt=null` ile bırakıldığı için ilk sweep'te HER biri için
  bireysel INITIAL e-posta+push gidiyor — yeni TAKIM fırtınası değerlendirmesinden geçmeden (bir takımda 10 üye = 10 ayrı
  alarm). Sağlıklı ("hayalet") üyeler ise fırtına artık aktif olmadığı için tek tek "✅ ÇÖZÜLDÜ" e-postasıyla kapanıyor.
  `PROD_DIAGNOSIS.md` §11'deki "fırtına üyelerinde toplu" beyanı bu yüzden tutmuyor. | **Çözüm:** eski kapsamın hâlâ
  açık üyelerini takım başına yeni `TEAM:<id>` fırtınalarına TAŞI (INITIAL göndermeden, `lastReAlertAt` korunarak) ya
  da dağıtmayı uygulama açılışından sonra ilk uzlaştırma turlarına (≥ birkaç dk) ertele; §11 ve sürüm notu düzeltilsin.

### O-4 — YÜZDE eşiği takım paydasıyla küçük takımlarda tabana (2) iniyor: tek host arızası "fırtına" oluyor (doğrulanmalı: prod birimi)

- `StormService.java:385-394` + `:436-458` + `:144` (`MIN_THRESHOLD = 2`) | **Neden bug:** `PERCENT` biriminde eşik
  artık takımın kendi aktif izleme sayısından; 20 izlemeli takımda %10 → 2. Tek bir host düşünce aynı pencerede
  ACCESSIBILITY + PORT_DOWN (+ DNS_FAILURE) açılır → 2 üye → fırtına → bireysel alarmlar bastırılır, "2 monitör birden
  erişilemez" toplu postası gider. Kuruluş paydasında (ör. 1000 × %10 = 100) bu olmuyordu; davranış sessizce değişti.
  (`COUNT` varsayılanında etkisiz.) | **Çözüm:** PERCENT için takım başına taban (ör. `max(MIN_THRESHOLD, COUNT değeri)`)
  ya da eşiği DISTINCT alan adı üzerinden say; ayar ekranı önizlemesi takım örneğiyle gösterilsin.

### O-5 — Alan adı ailesinde "doğrulanamadı = düzeldi": RDAP/DNSBL hatası açık alarmı kapatıyor (önceden var)

- `SchedulerService.java:5017-5019` (EXPIRY: `days == null` → up), `:5021-5022` (STATUS), `:5028-5033` (TRANSFER_LOCK /
  BLACKLIST: UNKNOWN → up); `DomainCheckerService.java:113` (veri yok → UNKNOWN, gün null) | **Neden bug:** geçici bir
  RDAP zaman aşımı KRİTİK DOMAINMON_EXPIRY / STATUS / BLACKLIST alarmını `recoveryChecks=1` ile anında "✅ ÇÖZÜLDÜ"
  kapatıyor, sonraki başarılı sorgu yeni INITIAL açıyor. Sertifika yolundaki `unverified` (status=error hiçbir şeyi
  doğrulamaz) ilkesinin alan adı eşleniği yok. Elle yol ve açılışta canlı sorgu yapan Kayıt sekmesi (K-1) bu yolu çok sık
  tetikliyor. | **Çözüm:** `status == UNKNOWN` (hasData=false) iken EXPIRY/STATUS/TRANSFER_LOCK/BLACKLIST için kalem
  üretme (ne up ne down).

---

## DÜŞÜK

- **D-1** `MonitoringOutageService.java:436-447`, `EscalationService.java:944` ve `:1707-1792`,
  `SchedulerService.java:2172-2174` — `alert-enabled=false` iken kapanış bildirimi gidiyor | **Neden bug:** K5
  uzlaştırması ve (tür kapalıyken de koşan) elle yol alarmı normal çözüm hattından kapatıyor → "✅ ÇÖZÜLDÜ" e-posta,
  kontak webhook'u, 7/24 ve push. Yönetici "bu türün bildirimleri sussun" demişken çözüm postası gider; diff öncesi elle
  yol tür kapalıyken hiç işlem yapmıyordu. | **Çözüm:** ürün kararı gerekir; susturulacaksa `resolveOpenAlertsSilently`
  (push simetrisi korunur) kullan.
- **D-2** `EscalationService.java:2158-2163` (konu), `EmailTemplateBuilder.java:93-100` (rozet),
  `noc/NocMailComposer.java:107-113` — WARNING konu/rozette "ORTA", gövdede artık "UYARI:" (`withLevelWord`,
  `EscalationService.java:1336-1361`) | **Neden bug:** aynı e-postada iki farklı seviye sözcüğü; eskiden "ORTA:" diyen
  DOMAINMON_EXPIRY de artık rozetle çelişiyor. Arayüz ve push "UYARI" diyor. | **Çözüm:** üç yerde de tek kaynak
  `levelWordTr` (WARNING → "UYARI").
- **D-3** `frontend/src/i18n/index.jsx:3353` / `:16347` (`check.manualNote`), `:10155` / `:23135` (`alh.whyAutoTip`) —
  metin eksik/yanlış | **Neden bug:** not "bildirim tetiklemez" diyor ama sağlıklı elle kontrol açık alarmı kapatıp
  çözüm e-postası/push gönderebilir; "kendiliğinden kapanır" çipi, D2 kardeşi DOWN iken ya da tür kapalıyken alarmın açık
  kalacağını söylemiyor. | **Çözüm:** "…yeni alarm açmaz; sonuç sağlıklıysa açık alarm kapanabilir (çözüm bildirimi
  gider)" ve ipucuna kardeş/tür-kapalı istisnası.
- **D-4** `frontend/src/components/PortMonitorPage.jsx:770-773, 797` (+ 8 kardeş sayfada aynı desen) — kart ▶ ve detay
  "Kontrol et" hâlâ `canManageRow` | **Neden bug:** yalnız toplu sayaç `can_check`'e bağlandı; kapsamlı müdür başka
  takımın satırında ▶ görüp 403 alıyor (her tık ACCESS_DENIED denetim kaydı). | **Çözüm:** tekil düğmeleri de
  `canCheckRow` ile kapıla.
- **D-5** `MonitoringOutageService.java:615-634, 653-661` — `downObserved` yalnız tembel temizleniyor | **Neden bug:**
  DOWN iken duraklatılan/silinen izlemenin kaydı, aynı anahtarda kurtarma değerlendirilmedikçe sonsuza dek kalır; kayıt
  tüm `SweepItem`'ı (izleme varlığını yakalayan recheck lambdası dâhil — sentetikte script metni) tutuyor; tek pod'da
  sınırsız büyüme + her `downSibling` taramasında maliyet. Elle/K1 yolunda açık alarmı olmayan sağlıklı alan adının
  `recoveryUpCount` girdisi de silinmiyor. | **Çözüm:** TTL'li periyodik budama (gecelik `pruneMonitorCheckState`
  yanına); gözlemde yalnız `(alertType, domain, monitorId, detail, atMs)` tut.
- **D-6** `MonitoringOutageService.java:618` — D2 gözlemi bellek-içi | **Neden bug:** yeniden başlatmadan sonra ilk
  zamanlanmış tura kadar (45–95 sn) paylaşılan anahtardaki sağlıklı kardeşin elle kontrolü alarmı kapatabilir, DOWN kardeş
  sonra yeniden açar (kapan/aç + iki bildirim). Pencere küçük. | **Çözüm:** boş haritada ilk kararda kardeşlerin son
  kontrol satırını DB'den oku ya da ilk tur tamamlanana dek elle kapanışı ertele.
- **D-7** (önceden var) `SchedulerService.java:2893-2898` PORT_SLOW, `:3013-3018` KEYWORD_SLOW, `:3426-3443` PING_SLOW,
  `:3935, 3950-3952` PAGESPEED_SLOW, `:4266-4270` SCRIPTED_SLOW; `:3606, 3687` PAGE_INTEGRITY; `EscalationService.java:1947-1954`
  — "sağlıklı" tanımı sorunu kanıtlamıyor | **Neden bug:** *_SLOW kalemi hedef DOWN / ölçülemedi iken "up" → yavaşlık
  alarmı "ÇÖZÜLDÜ" diye kapanıyor (elle ping'de DOWN host PING_SLOW'u kapatır, PING_DOWN ise elle açılmaz); günlük
  SITE_CRAWL'ın açtığı PAGE_INTEGRITY alarmını 60 sn'lik SINGLE_PAGE sweep'i kapatıyor (doğrulanmalı); HOSTNAME_MISMATCH /
  UNTRUSTED_CA ayarı kapatılınca açık alarm "sorun giderildi" diye kapanıyor. | **Çözüm:** ölçülemeyen SLOW için kalem
  üretme; bütünlük alarmını modla (crawl/single) ayrı anahtarla; ayar kaynaklı kapanışı sessiz yap.
- **D-8** `ScriptedAnomalyGuard.java:90-91` — `manual` iken ağır ihlal (istek tavanı aşımı) da atlanıyor | **Neden bug:**
  karar zaman aşımı serisi (havuz sıkışması) içindi; üretime yük bindiren script elle 20 sn'de bir koşturulabilir, yalnız
  sonraki zamanlanmış koşum kapatır. | **Çözüm:** elle yolda yalnız `timeoutStreak`'i atla, `severeViolation` sürsün (en
  azından bildirimsiz kapat).
- **D-9** `MonitoringController.java:2457-2471`, `SchedulerService.java:5336-5352` — tek DNS değişikliği geçmişte iki
  "DEĞİŞTİ" satırı | **Neden bug:** elle satır tabanı "herhangi son başarılı", zamanlanmış satır tabanı "son
  zamanlanmış"; `countChangedByMonitorIdBetween` / değişiklik süzgeci ikisini de sayar. | **Çözüm:** elle satırları
  değişiklik sayımından hariç tut ya da arayüzde "elle" rozetiyle göster.
- **D-10** (doğrulanmalı) `SchedulerService.java:4519-4528, 4197`, `ScriptedCheckerService.java:812` — toplu elle
  sentetik kontrol k6 permit'lerini tüketip zamanlanmış koşumları SKIPPED'e düşürüyor | **Neden bug:** elle koşumlar artık
  alarm açmadığından, havuz doluyken gerçek kesintinin alarmı dakikalarca gecikir; elle sağlıklı sonucun başlattığı
  kurtarma zincirleri ek k6 koşumu ekler. | **Çözüm:** elle koşumlara ayrı/küçük kota ya da zamanlanmış koşumlara öncelik.
- **D-11** `SchedulerService.java:2723, 2809, 2958, 3074, 3578, 3863, 4887, 5155, 5251`; `alertHistoryModel.js:231-232`,
  `AlertBadges.jsx:181` — K5 düzeltmesi yalnız sentetikte etkili | **Neden bug:** diğer türlerde `alert-enabled=false`
  sweep'i tümden durduruyor; açık alarmlar donuk kalıyor ama çip "kontroller düzelince kendiliğinden kapanır" diyor.
  | **Çözüm:** tür kapatılınca açık alarmları sessiz kapat (tekil duraklatma deseni) ya da çipte istisnayı göster.
- **D-12** `i18n/index.jsx:1667-1674`, `:14661-14668` — `storm.unitPercent`, `storm.pctPreview`, `storm.perGroupHint`
  artık kullanılmıyor ve "account-wide / Tüm monitörlerin" diyor; `:3356` TR "%{0}’i" eki sayıya göre yanlış (%10'u,
  %50'si). | **Çözüm:** eski anahtarları sil; eki "%{0} oranı" gibi eksiz kur.
- **D-13** `MonitoringController.java:932-933` — `canOperateTeam`'in Javadoc'u yeni `withCheckFlag` Javadoc'unun üstünde
  asılı kaldı (sahipsiz doc yorumu). | **Çözüm:** eski yorumu `canOperateTeam`'in üstüne taşı.
- **D-14** (önceden var, doğrulanmalı) `StormService.java:193-199, 487-491` — `bumpMemberCount` okunan varlığı tam
  kaydediyor | **Neden bug:** kilitsiz `evaluate` ile kilitli `lifecycleSweep` yarışırsa çözülmüş fırtına
  `resolved=false` ile geri yazılabilir (ikinci toplu çözüm postası). | **Çözüm:** koşullu `UPDATE … SET member_count =
  member_count + 1 WHERE id = ? AND resolved = false`.
- **D-15** testler — K-1, Y-1, O-1, O-2, O-3 için kapı testi yok; `MonitoringOutageStuckAlarmRegressionTest`
  `everySweepType_recoversUnderSuppression` sentetik "sağlıklı" kalemle koşuyor, türe özgü sağlıklı tanımını (D-7)
  sınamıyor. | **Çözüm:** yukarıdaki bulguların her biri için davranış testi.

---

## Tür bazında "sağlıklı" (up=true) koşulu

| Tür | Kalem "sağlıklı" iken | Yanlış kapanış riski |
|---|---|---|
| ACCESSIBILITY | uptime `status=up` | yok (kimliksiz, D2 dışı; K1 altında da kapanır) |
| PORT_DOWN | port açık | Y-1 (dual-source anahtar) |
| PORT_SLOW | `!(slowEnabled && error==null && resp>eşik)` — port kapalı/ölçüm yok = up | D-7 |
| DNS_FAILURE | çözümleme başarılı | Y-1 (dual-source) |
| DNS_SLOW | kalem yalnız başarıda; `resp ≤ eşik` | yok (başarısızlıkta kalem yok) |
| DNS_UNEXPECTED | kalem yalnız başarı + beklenen değer varken; beklenmeyen yok | yok |
| DNS_INCONSISTENT | kalem yalnız propagation açıkken; tutarlı | yok |
| DNS_CHANGED | otomatik kapanmaz | yok (elle taban düzeltildi) |
| KEYWORD | koşul sağlanıyor ve HTTP hatası yok (cfgError = up, bilinçli) | Y-1 (aynı URL) |
| KEYWORD_SLOW | PORT_SLOW ile aynı mantık | D-7 |
| KEYWORD_SSL / KEYWORD_DOMAIN_EXPIRY | günlük; toggle kapalı = up (bilinçli) | yok |
| PING_DOWN | up | Y-1 |
| PING_SLOW | ölçüm yok / taban yetersiz / eşik altı = up | D-7 (elle DOWN host'ta kapanır) |
| HTTP_DOWN | `ok || cfgError` | Y-1 (aynı URL) |
| HTTP_SSL / DOMAIN_EXPIRY | günlük; toggle kapalı = up | yok |
| PAGE_DOWN | `main_up || cfgError` | yok |
| PAGE_INTEGRITY | `integrity_up` (60 sn sweep hep SINGLE_PAGE) | D-7 (crawl alarmı) |
| PAGESPEED_DOWN | `reachable || cfgError` | yok |
| PAGESPEED_SLOW | `withinThresholds || !reachable || cfgError` | D-7 |
| SCRIPTED_FAIL | `ok || (NO_CHECKS && !noChecksAlarms)`; sweep'te SKIPPED kalem yok, elle SKIPPED = DOWN | yok (Y-1: aynı ad) |
| SCRIPTED_SLOW | `!(enabled && up && süre>eşik)` — FAIL/SKIPPED = up | **O-2**, D-7 |
| DOMAINMON_UNKNOWN | `status != UNKNOWN` | yok |
| DOMAINMON_EXPIRY | `!(gün != null && gün ≤ uyarı)` — veri yok = up | **O-5** |
| DOMAINMON_STATUS | `!(eppCritical || eppWarn)` — veri yok = up | **O-5** |
| DOMAINMON_TRANSFER_LOCK / BLACKLIST | UNKNOWN / SKIPPED = up | **O-5** |
| DOMAINMON_CHANGED | otomatik kapanmaz; yalnız değişimde DOWN kalemi | **K-1** (açılış yutuluyor) |
| Sertifika (EXPIRY, CHAIN_BROKEN, REVOKED, MISMATCH, HOSTNAME_MISMATCH, UNTRUSTED_CA) | tür `determineAlertTypes`'ta üretilmiyor ve `status≠error` | D-7 (ayar kapatılınca kapanış); süresi hâlâ uyarı penceresindeki EXPIRY kapanmaz ✓ |

---

## Kontrol listesi A–G

**A. Yanlış kapanış — BULGU** (O-1, O-2, O-5, D-6, D-7).
- Kardeş izleme (D2) yeniden başlatma sonrası: bellek boşken ilk zamanlanmış tura kadar açık (D-6); ilk tur tüm
  izlemeleri aynı partide işlediği için pencere küçük. Takım-körlüğü ayrı bulgu (Y-1).
- "skipped/yürütülemedi": zamanlanmış sweep, teyit ve kurtarma zinciri doğru (kanıt sayılmıyor, sayaç korunuyor) ✓; elle
  sentetik yolda ihlal (O-2).
- Bakım penceresi: `resolveOpenAlertsForDomain` bakım kuralı (açılış bildirimi gitmediyse sessiz) korunuyor ✓.
- Duraklatılmış izleme: `stillMonitored` D2 engelini düşürüyor ✓; duraklatılmış izlemenin elle kontrolü kardeş D2 ile
  korunuyor ✓.
- *_SLOW / DNS_* / ACCESSIBILITY / sertifika: tablo yukarıda. Sertifika süresi hâlâ uyarı penceresindeyken kapanmıyor
  (`resolveVerifiedStaleCertAlerts` EXPIRY'yi "stale" saymaz; status=error dokunulmaz) ✓.
- Elle + zamanlanmış sayaç karışımı: sayaç ortak; elle tıklamalar aralığı atlıyor (O-1). Aktif zincir + sayaç çift
  kapanışı `endRecovery` + `markResolvedIfOpen` ile tek ✓. Takılı zincir bekçisi eski kuşağın geç görevini düşürüyor ✓.

**B. Yanlış açılış / sessiz susma — BULGU** (K-1, Y-1, D-1, D-10).
- Zamanlanmış sweep tüm türlerde hâlâ açıyor: tüm `run*Checks`, `handleDnsSweep`, işaretsiz `handleDomainSweep`,
  `runCriticalDomainChecks` → `evaluateDomainAlarms(m, r, false)`, HTTP/İçerik SSL-alan adı günlük sweep'leri ✓.
- Elle kontrol teyit zincirine (`inFlight`) ve `checkDue` ızgarasına dokunmuyor → zamanlanmış alarm gecikmiyor ✓
  (istisna: k6 permit rekabeti, D-10). Elle DOWN yalnız kurtarma sayacını/zincirini sıfırlıyor (muhafazakâr) ✓.
- `alert-enabled=false` iken kapanış bildirim gönderiyor (D-1).
- Toplu bastırma altında kapanış: normal çözüm hattı; fırtına üyesinde e-posta bastırılır, push simetrik ✓.
- Alan adı elle kontrolü DOMAINMON_CHANGED'i kalıcı yutuyor (K-1).

**C. Fırtına yalıtımı — BULGU** (O-3, O-4, D-14).
- Açılış / üye bağlama / günlük toplu tekrar / bitiş / `lifecycleSweep` / push / e-posta / webhook / 7-24: kapsam
  `TEAM:<id>`, akran listesi `teamId` ile süzülüyor, her dağıtım yalnız kendi üyelerini sayıyor ✓.
- SY+UG ortak kayıt: iki dağıtım (UG'ye e-posta+webhook, push yalnız SY) ✓. Sahipsiz olay fırtınaya girmiyor ✓ (yalnız
  UG'li kayıt da `teamId=null` olduğu için fırtınaya girmez — güvenli taraf).
- Eski ACCOUNT dağıtımı üyelerin bireysel bildirim durumunu değiştiriyor (O-3).
- Yüzde eşiği: payda 0 → taban 2; küçük takımlarda davranış değişikliği (O-4).
- Eşzamanlılık: iki takım ayrı anahtar; aynı takımın iki sweep'i `ux_alert_storms_active` + `ON CONFLICT` ile tek
  fırtına ✓; `bumpMemberCount` yarışı (D-14).

**D. Veri/şema — TEMİZ** (K-1'in kök nedeni: `domain_checks`'te elle işareti yok; D-9 sayım).
- `scripted_checks.manual`, `dns_records.manual`: nullable Boolean, ddl-auto + `columnExists` korumalı idempotent
  `patch` (`SchedulerService.java:726-727`); NULL = zamanlanmış; sorgular `(manual IS NULL OR manual = false)` ✓.
  `http_metric_minute.status_codes` yaması (`:1035`) aynı desen ✓.
- Yeni depo sorgularında nullable parametre yok (CAST gerekmiyor); `countBy…TeamId` türetilmiş sorgular `teamId != null`
  korumasıyla çağrılıyor ✓.
- N+1 / maliyet: takım sayımları yalnız fırtına değerlendirmesinde (yeni DOWN alarmı, aktif fırtına yokken) ve
  `lifecycleSweep`'te, 60 sn önbellekli — her sweep'te DEĞİL ✓. `reconcileRecoveries` tek sorgu ✓;
  `resolveVerifiedStaleCertAlerts` alan adı başına sorgu yalnız açık sertifika alarmı olanlarda ✓.

**E. Kalıcılık / yeniden başlatma — BULGU** (D-5, D-6).
- Ardışık sayaç, zincir kuşağı, bekçi zaman damgası bellek-içi; yeniden başlatmada sıfırlanır → kapanış için N taze tur
  gerekir (güvenli taraf) ✓; DB'deki açık alarm + sağlıklı tur hayaleti kapatır (H1) ✓.

**F. Metin — BULGU** (D-2, D-3, D-12).
- `withLevelWord` tüm izleme türlerinde zengin ve yedek iletide uygulanıyor (test kapsıyor); sertifika etiketleri
  korunuyor ✓. Push `PushText.LEVEL_PREFIX` "UYARI:"yı soyuyor (tekrar yok) ✓. TR/EN anahtar eşliği tam ✓; EN metinler
  doğal British English ✓.

**G. Testler — BULGU (kapsam boşlukları, D-15).**
- Sabit tarih / saat dilimi tuzağı yok: tarih literalleri yalnız sıralama verisi (`ManualCheckMarkerQueriesTest`) ya da
  geçmişteki sabit (`storm(id)`); pencereli testler `Instant.now()` göreli ✓. `LocalDate.now()` yok ✓.
- Mockito yeniden-stub / worker yarışı yok: zincirler aynı iş parçacığında koşan zamanlayıcıyla, fırtına testleri
  senkron ✓. Tel biçimi snake_case (`can_check`, `storm_id`) ✓. Varsayılan stub'lar davranışı maskelemiyor
  (`openAlarm` tür+alan adıyla süzülüyor) ✓.

---

## Özet tablo

| Kod | Önem | Dosya:satır | Kısa |
|---|---|---|---|
| K-1 | KRİTİK | `DomainCheckerService.java:145`, `MonitoringController.java:5566/5596`, `DomainRegistrationTab.jsx:61` | Elle/canlı alan adı kontrolü DOMAINMON_CHANGED'i kalıcı yutuyor |
| Y-1 | YÜKSEK | `MonitoringOutageService.java:647-665` | D2 takım-kör: başka takımın izlemesi alarmı açık tutuyor, bildirim yanlış takıma |
| O-1 | ORTA | `MonitoringOutageService.java:586-598` | Hızlı elle tıklamalar kurtarma aralığını atlıyor |
| O-2 | ORTA | `SchedulerService.java:4519-4528, 4266` | SKIPPED elle sentetik koşum SCRIPTED_SLOW'u kanıtsız kapatabiliyor |
| O-3 | ORTA | `StormService.java:280, 347-370` | Eski ACCOUNT fırtınası dağıtılınca üyeler tek tek bildirim üretiyor |
| O-4 | ORTA | `StormService.java:385-394` | Takım paydalı YÜZDE eşiği küçük takımda 2'ye iniyor (doğrulanmalı) |
| O-5 | ORTA | `SchedulerService.java:5017-5033` | Alan adı: RDAP/DNSBL hatası açık alarmı kapatıyor (önceden var) |
| D-1…D-15 | DÜŞÜK | yukarıda | bildirim/metin/UI kardeş yüzey/bellek/test boşlukları |

---

## Okundu listesi (kapsamın tamamı)

Değişmiş (51):
- `CHANGELOG.md` ✓ · `CLAUDE.md` ✓ · `WHITEPAPER.md` ✓ · `WHITEPAPER.en.md` ✓ (kök = kaynakla birebir, doğrulandı)
- `backend/src/main/java/com/sitemonitor/controller/MonitoringController.java` ✓ (diff + tetik uçları, liste uçları,
  DNS/alan adı/Kayıt yolları)
- `backend/src/main/java/com/sitemonitor/model/AlertStorm.java` ✓ · `model/DnsRecord.java` ✓ · `model/ScriptedCheck.java` ✓
- `backend/src/main/java/com/sitemonitor/repository/`: `AlertEventRepository.java` ✓, `CertificateInventoryRepository.java` ✓,
  `DnsMonitorRepository.java` ✓, `DnsRecordRepository.java` ✓, `DomainMonitorRepository.java` ✓, `HttpMonitorRepository.java` ✓,
  `KeywordMonitorRepository.java` ✓, `PageMonitorRepository.java` ✓, `PageSpeedMonitorRepository.java` ✓,
  `PingMonitorRepository.java` ✓, `PortMonitorRepository.java` ✓, `ScriptedCheckRepository.java` ✓,
  `ScriptedMonitorRepository.java` ✓
- `backend/src/main/java/com/sitemonitor/service/EscalationService.java` ✓ (diff + kapanış/çözüm/açılış yolları)
- `backend/src/main/java/com/sitemonitor/service/MonitoringOutageService.java` ✓ (tamamı)
- `backend/src/main/java/com/sitemonitor/service/SchedulerService.java` ✓ (diff + tüm sweep ve `evaluate*Now` gövdeleri,
  `checkDue`, `isStillMonitored`, sertifika sweep'i, sentetik kalıcılaştırma)
- `backend/src/main/java/com/sitemonitor/service/ScriptedAnomalyGuard.java` ✓ · `service/StormService.java` ✓ (tamamı)
- Testler: `controller/MonitoringControllerTest.java` ✓, `service/ManualEvaluationWiringTest.java` ✓,
  `service/SchedulerServiceTest.java` ✓, `service/ScriptedAnomalyGuardTest.java` ✓, `service/StormServiceTest.java` ✓ (diff'ler)
- `frontend/public/whitepaper.en.pdf`, `frontend/public/whitepaper.tr.pdf` — **ikili, içerik okunmadı**; kaynak md'ler
  okundu, SHA-256'lar `whitepaper.manifest.json` ile eşleşiyor (PDF'ler güncel)
- `frontend/scripts/whitepaper.manifest.json` ✓ · `frontend/src/assets/whitepaper.md` ✓ · `frontend/src/assets/whitepaper.en.md` ✓
- `frontend/src/components/`: `DnsMonitorPage.jsx` ✓, `DomainMonitorPage.jsx` ✓, `HttpMonitorPage.jsx` ✓,
  `KeywordMonitorPage.jsx` ✓, `PageMonitorPage.jsx` ✓, `PageSpeedMonitorPage.jsx` ✓, `PingMonitorPage.jsx` ✓,
  `PortMonitorPage.jsx` ✓, `ScriptedMonitorPage.jsx` ✓ (diff + düğme kapıları), `admin/StormSettings.jsx` ✓,
  `admin/alerts/AlertBadges.jsx` ✓, `admin/alerts/alertHistoryModel.js` ✓, `check/CheckTeamPicker.jsx` ✓
- `frontend/src/i18n/index.jsx` ✓ (diff) · `frontend/src/test/AlertHistory.test.jsx` ✓ (diff)

Takipsiz (10):
- `backend/src/test/java/com/sitemonitor/controller/ScopedAdminManualCheckGateTest.java` ✓
- `backend/src/test/java/com/sitemonitor/repository/ManualCheckMarkerQueriesTest.java` ✓
- `backend/src/test/java/com/sitemonitor/service/CertAlertOutageReconcileTest.java` ✓
- `backend/src/test/java/com/sitemonitor/service/ManualCheckNoAlarmTest.java` ✓
- `backend/src/test/java/com/sitemonitor/service/ManualCheckWiringTest.java` ✓
- `backend/src/test/java/com/sitemonitor/service/MonitoringAlertLevelWordTest.java` ✓
- `backend/src/test/java/com/sitemonitor/service/MonitoringOutageStuckAlarmRegressionTest.java` ✓
- `backend/src/test/java/com/sitemonitor/service/StormTeamIsolationTest.java` ✓
- `frontend/src/test/ScriptedMonitorPage.checkScope.test.jsx` ✓ · `frontend/src/test/checkAllServerScope.test.jsx` ✓

Bağlam için ayrıca okunan (değişmemiş): `DomainCheckerService.java`, `UserPushService.java` (fırtına push'u, ileti
kurucusu), `PushText.java`, `EmailTemplateBuilder.java`, `EmailNotificationService.java` (seviye etiketi),
`noc/NocMailComposer.java`, `CertificateController.java` (elle sertifika uçları), `SessionScope.canOperateTeam`,
`AlertEventRepository` (bağlama/çözme sorguları), `DomainRegistrationTab.jsx`, `i18n-used-keys.test.jsx`,
`PROD_DIAGNOSIS.md`, bağlayıcı bellek notları.
