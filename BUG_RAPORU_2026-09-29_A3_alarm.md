# Bug raporu — EKSEN A3: Alarm yaşam döngüsü ve bildirimler (2026-09-29)

**Kapsam:** `main` @ 20.91.0 (`b48d6146`) + commit edilmemiş "ortam adı ayarı" değişikliği (alarm koduna dokunmuyor).
Açılış → teyit → yeniden uyarı → eskalasyon → kapanış → yeniden açılış; e-posta / push / webhook / 7-24 / fırtına /
haftalık-aylık raporlar; tüm izleme türleri. **Salt okunur:** kod değiştirilmedi, test/derleme/sunucu koşturulmadı,
kilit alınmadı; yalnız bu rapor yazıldı.

**Yöntem:** her aday kaynakta okunarak doğrulandı; çağrı zincirleri uçtan uca izlendi (sweep kalemi → `handleSweepResults`
→ `handleSweepDomain` / `reconcileRecoveries` / `startConfirmation` → `processConfirmedOutage` → `StormService.evaluate`
→ `sendCombinedAlert` → `UserPushService` / `NocNotificationService` / `WebhookService`; kapanış: `recoverDomain` →
`closeAlarm` → `resolveOpenAlertsForDomain` / `closeQuietly` → `sendResolutionNotification`). 20.91.0'ın düzelttiği
maddeler (hayalet kapanış K1/K2/K5, ölçülemedi ≠ sağlıklı, elle kontrol, takım bazlı fırtına, sahip ayrımı,
`resolvedSilently`, seviye sözlüğü) TEKRARLANMADI; bunların BIRAKTIĞI boşluklar ve yeni etkileşimler arandı.
Bağlayıcı kararlar: `cert-monitor-escalation-team-only`, `cert-monitor-manual-check-storm-isolation`,
`cert-monitor-resolve-push-recipients`, `cert-monitor-push-channel-parity`, `cert-monitor-dns-port-dual-source`,
`fix-sweep-sibling-surfaces`, `dogrulamadan-varsayma`.

Önem: **KRİTİK 0 · YÜKSEK 2 · ORTA 6 · DÜŞÜK 9** — artı 29c'den devreden 13 DÜŞÜK (RE-CHECK tablosu).

**Genel değerlendirme:** yaşam döngüsünün omurgası (uzlaştırmalı kapanış, sahip ayrımı, takım bazlı fırtına, kilitli tek-yazar,
simetrik çözüm push'u, tek seviye sözlüğü) sağlam ve kapı testleriyle pinli. Kalan kusurlar **kardeş yüzeylere taşınmamış
düzeltmeler** (D-b1'in envanter yolu, O-5/D-7'nin HTTP/İçerik alt alarmları, Y-1'in kurtarma yönü) ve **iki DNS bağlamının
seviye damgası** gibi tekil sapmalardır — sistemik bir açık yok.

---

## YÜKSEK

### Y-A3-1 — Envanter silme / pasifleştirme, aynı anahtardaki BAŞKA takımın bağımsız izleme alarmlarını da sessizce kapatıyor (D-b1'in envanter yolu — kardeş yüzey)

- `backend/src/main/java/com/sitemonitor/service/EscalationService.java:1756-1768` (`closeOpenAlerts` →
  `alertEventRepo.findByDomainAndResolvedFalse(domain)`: tür ve SAHİP süzgeci yok), `:1742-1754`
  (`closeAlertsOnInventoryDelete` / `closeAlertsOnDeactivate`), `:1776-1792` + `repository/AlertEventRepository.java:144-153`
  (`catchUpAlertsOnDeletedDomains` → `findOpenAlertsOnSoftDeletedDomains`, yalnız `a.domain = i.domain` ile eşleşir);
  çağıranlar `controller/AdminController.java:604, 1030, 1162, 1194`.
  | **Neden bug:** alarm anahtarı (alan adı|tür) takımlar arasında paylaşılır — bağımsız Port/DNS satırı envanter host'uyla
  (çift kaynak, `uq_pm_host_port … WHERE standalone IS NOT TRUE`, `SchedulerService.java:1466-1471`), Ping izlemesi host'la,
  Alan Adı izlemesi (DOMAINMON_*) envanter alan adıyla aynı anahtarı taşır. Senaryo: host H Takım A'nın envanterinde; Takım B'nin
  bağımsız Port/Ping izlemesi H'de DOWN, olay B damgasıyla açık (`ownerKeyOf = T:B`). A envanter satırını pasife alır
  (yaygın: hizmet dışı bırakma) → `closeAlertsOnDeactivate(H)` B'nin PORT_DOWN / PING_DOWN / PORT_SLOW / DOMAINMON_*
  olaylarını `inventory_deactivate` + `resolvedSilently=true` ile kapatır ve `enqueueResolvePushQuietly` ile B'nin nöbetçisine
  **"DÜZELDİ" push'u gider** (hedef hâlâ düşük). B'nin sonraki turu olayı bulamaz → yeni teyit zinciri → **yeni INITIAL e-posta
  + push**; B'nin onayı/geçmişi kopar. D-b1 (2026-09-29) `ownerCtx`'i MonitoringController'daki 18 çağrı yerine taşıdı, envanter
  yolu (AdminController → EscalationService) atlandı — `fix-sweep-sibling-surfaces` sınıfı. Kural "başka sahibin izlemesi bu
  olayı etkilemez" (Y-1 / D-b1) ihlal ediliyor; başka takıma yanlış "düzeldi" bildirimi gidiyor.
  | **Çözüm:** `closeOpenAlerts` ve `findOpenAlertsOnSoftDeletedDomains` yalnız envanter sahipli olayları kapatsın:
  `!EscalationService.isStandaloneEvent(e)` (sahip anahtarı `INV` — sertifika türleri + türev Port/DNS + ACCESSIBILITY);
  bağımsız (damgalı / `standalone`) olaylara dokunulmasın. Kapı: `SharedKeyOwnerGuardTest`'e "envanter pasifleşince aynı
  host'taki bağımsız Port/Ping olayı açık kalır, push gitmez" + `catchUpAlertsOnDeletedDomains` için aynı iddia.

### Y-A3-2 — DNS_FAILURE ve DNS_CHANGED bağlamına izlemenin seçili alarm SEVİYESİ damgalanmıyor → her zaman WARNING açılıyor; eskalasyon kişileri ve yönetici push'u sessizce atlanıyor

- `backend/src/main/java/com/sitemonitor/service/SchedulerService.java:3146-3160` (`dnsFailureSweepItem` → 3-argümanlı
  `chanCtx(confirmCtx(...), m.getNotifyEmail(), m.getNotifyWebhook())`, `alert_level` YOK),
  `service/MonitoringOutageService.java:1360-1381` (`changeCtx` → `SchedulerService.chanCtx(ctx, c.notifyEmail(), c.notifyWebhook())`,
  3-arg), `:905-914` (günlük DNS_CHANGED yeniden uyarısı `withDnsChannelFlags` → 3-arg); karşılaştırma: DNS_SLOW / UNEXPECTED /
  INCONSISTENT `chanCtx(ctx, m)` (`SchedulerService.java:5612, 5629, 5679`) ve `:545-549` (`putIfAbsent("alert_level", …)` yalnız
  2-arg sürümde); tüketici `MonitoringOutageService.java:1241` (`alert_level` yoksa `levelFor` = WARNING, `:1329-1331`);
  seviye formda ve kayıtta VAR: `frontend/src/components/DnsMonitorPage.jsx:88, 584`, `controller/MonitoringController.java:2263, 2354`.
  | **Neden bug:** 2026-09-19 ürün kararı "kullanıcı seviyeyi izleme formundan yükseltir; HIGH/CRITICAL eskalasyon kişilerini
  ekler" — DNS izlemesinin BİRİNCİL alarmı (çözümleme hatası) ve ele geçirme sinyali (kayıt değişikliği) bu ayarı yok sayıyor.
  Senaryo: takım DNS izlemesini CRITICAL yapar; NS kaydı değişir → DNS_CHANGED **WARNING** açılır → `teamOnly(standalone,
  WARNING)=true` → eskalasyon kişisi yok, push `resolve(team, WARNING)` → yönetici kademesi (min HIGH) dışarıda, 7/24 asgari
  seviye kapısı (`meetsMinLevel`) DNS_CHANGED'i WARNING görür, e-posta rozeti "UYARI". Aynı izlemenin DNS_SLOW alarmı ise
  CRITICAL açılır — tek izlemede iki farklı seviye politikası. Yeniden uyarı da aynı bağlamdan gelir (kalıcı). Kullanıcı
  ekranda "CRITICAL" seçtiğini görür, korunduğunu sanır.
  | **Çözüm:** `dnsFailureSweepItem` ve `changeCtx` 2-arg `chanCtx(ctx, m)` kullansın (`DnsChange`'e `alertLevel` alanı ya da
  `changeCtx`'te `ctx.putIfAbsent("alert_level", effectiveLevel(level))`); `withDnsChannelFlags` de `chanCtx(ctx, mon)`.
  Kapı: `SchedulerServiceTest` — "CRITICAL seviyeli DNS izlemesinin DNS_FAILURE ve DNS_CHANGED kalemi `alert_level=CRITICAL`
  taşır" (dokuz türü gezen tablo testi; bugün DNS_FAILURE/DNS_CHANGED ve envanter-türevi ACCESSIBILITY hariç hepsi taşıyor).

---

## ORTA

### O-A3-1 — Paylaşılan anahtarda YABANCI sahibin sağlıklı (UP) kalemi kurtarma kanıtı sayılıyor (Y-1'in kurtarma yönü eksik)

- `service/MonitoringOutageService.java:522-528, 550` (`handleSweepDomain`: `if (it.up()) up.add(it)` — sahip süzgeci yalnız DOWN
  dalında), `:594-606` (`reconcileRecoveries` aynı desen), `:641-697` (`recoverDomain`: `required = recoveryChecksFor(upItems)`
  yabancı kalemin ayarını okuyabilir, `:660`), koruma yalnız `downSibling` (`:652`, bellek-içi `downObserved` `:741`) ve D-6
  bekçisi yalnız elle yolda (`:643-651`).
  | **Neden bug:** Y-1 "başka sahibin DOWN kalemi olayı açık tutmaz / yeniden uyarı üretmez" dedi; simetriği ("başka sahibin UP
  kalemi olayı KAPATMAZ") kurulmadı. Kardeş DOWN gözlemi bellek-içi olduğundan yeniden başlatma sonrası (her sürüm dağıtımı)
  ilk aralıkta boştur. Senaryo: Takım A'nın HTTP izlemesi (URL X, aralık 300 sn) DOWN, olay A damgasıyla açık; Takım B'nin aynı
  URL'deki izlemesi (aralık 30 sn, farklı vekil/beklenen durum) UP. Pod yeniden başlar → 75. sn'de HTTP turu yalnız B'nin kalemini
  taşır → `openEvent` A'nın, `ownerDown` boş, `up=[B]` → `downSibling` boş harita → null → `required` B'nin ayarından (1 ise
  ANINDA) → `closeAlarm` → A'ya "✅ ÇÖZÜLDÜ" e-posta + push. A'nın turu (≤300 sn) DOWN görür → yeni teyit → yeni INITIAL. Her
  dağıtımda paylaşılan anahtar başına kapan/aç + iki sahte bildirim. İkinci yol: A'nın izlemesi envanter türevi ve envanter
  pasifleşince gözlemi `pruneObservations` ile düşer (`stillMonitored` false) ama olay B'nin host'u `checkableHosts`'ta
  olduğundan sessiz kapatılmaz (`SchedulerService.java:2867`) → B'nin UP kalemi olayı NORMAL çözüm yolundan ("sorun giderildi")
  kapatır.
  | **Çözüm:** iki yerde de `if (it.up()) { if (isOwnerItem(it, openEvent)) up.add(it); }` — yabancı UP kalemi ne sayaç artırır ne
  zincir başlatır (kimliksiz kalem sahip sayıldığından ACCESSIBILITY etkilenmez). Kapı: `MonitoringOutageOwnershipRegressionTest`'e
  "yabancı sahibin UP kalemi açık olayı kapatmaz (gözlem haritası boşken de)".

### O-A3-2 — HTTP / İçerik izlemesinin SSL-bitişi ve alan adı-bitişi alt alarmlarında "veri yok = sağlıklı" (O-5 / D-7'nin kardeş yüzeyi): geçici RDAP/TLS hatası açık alarmı "ÇÖZÜLDÜ" diye kapatıyor, ertesi gün yeniden açılıyor

- `service/SchedulerService.java:4896-4909` (`evalHttpDomain`: `days == null → "up"`, yorum "days null (unknown) → up (alarm yok)"),
  `:5063-5076` (`evalKeywordDomain` aynı), `:4862-4892` (`evalHttpSsl`: yalnız `sslExpiryReminders` açıkken `status=error` →
  `days=null` → `problem=false` → "up"; `checkSslErrors` kapalıysa TLS hatası da "up"), `:5030-5060` (`evalKeywordSsl` aynı);
  bağlam `monitor_recovery_checks=1` (`:4804, 5017`) → tek tur kapatır; `RdapDomainExpiryService.java:281` (`days_remaining=null`
  hata yolunda); kalemler `TYPE_DOMAIN_EXPIRY` / `TYPE_HTTP_SSL` / `TYPE_KEYWORD_*` `handleSweepResults`'a "up" olarak girer.
  | **Neden bug:** 20.91.0 aynı sınıfı alan adı ailesinde (O-5), yavaşlık/bütünlük türlerinde (D-7) ve zincir yeniden ölçümlerinde
  (D-b4) kapattı; HTTP/İçerik izlemesinin dört alt alarmı (DOMAIN_EXPIRY, KEYWORD_DOMAIN_EXPIRY, HTTP_SSL, KEYWORD_SSL) atlandı.
  Senaryo: HTTP izlemesi `domainExpiryReminders` açık, DOMAIN_EXPIRY açık (12 gün kaldı, HIGH → eskalasyon kişisi de aldı). Ertesi
  günkü günlük turda RDAP zaman aşımı → `days=null` → kalem "up" → `recoverDomain` `required=1` → "✅ ÇÖZÜLDÜ — Domain Süre Bitişi
  sorunu giderildi" e-postası + push (kayıt yenilenmedi). Ertesi gün RDAP çalışır → yeni INITIAL. Her geçici RDAP/TLS hatasında
  gün bazında kapan/aç + 2 sahte bildirim; yeniden uyarı sayacı sıfırlanır. `prod-log-as-defect-source` notundaki "RDAP düşüyor →
  days null" imzası bu yolu üretimde tetikler.
  | **Çözüm:** `days == null` (alan adı) / `status=error` + yalnız hatırlatma açık (SSL) → hüküm `"skipped"`; sweep'te kalem üretme
  (alan adı ailesindeki `hasData && days != null` deseni, `SchedulerService.java:5215-5219`). Kapı: `SweepNoDataIsNotHealthyTest`'e
  HTTP/İçerik SSL+alan adı dalları.

### O-A3-3 — Bakım penceresinde GERÇEKTEN düzelen alarmın 7/24 (NOC) "ÇÖZÜLDÜ" postası hiç gitmiyor (takım e-postası olmayan / e-postası kapalı izlemelerde)

- `service/EscalationService.java:896-916` (bakım dalı: bildirim günlüğünde NOC dışı satırı OLMAYAN türler
  `resolveOpenAlertsQuietlyRecovered`), `:993-1019` (`closeQuietly` — `notifyNocResolved` ÇAĞRILMAZ; yalnız push simetrisi),
  `:1804-1807` (NOC çözümü YALNIZ `sendResolutionNotification` başında), `:2241-2256` (`skipMail` + kontak webhook'u yok →
  `saveLog` YAZILMADAN döner → olayın NOC dışı satırı hiç oluşmaz), `noc/NocNotificationService.java:619-640` (NOC satırları
  `recipientRole=NOC`), `:209-247` (`onAlertResolved`: açılış SENT ise çözüm gönderir — ama çağrılmıyor). Satır 900-902'deki
  yorum "NOC çözümü kendi yolundan gider" der; sessiz yolda o yol yok.
  | **Neden bug:** push-tek kanallı takım (takım adresi tanımsız — desteklenen kurulum, `:2246` yorumu) ya da izlemede
  "E-posta" kapalı (`mail_disabled`). PORT_DOWN 02:00'de açılır → 7/24 OPEN postası gider (nöbetçi arar) → ekip 02:30'da hedefe bakım
  penceresi açıp müdahale eder → 03:00'te port açılır → `recoverDomain` → bakım dalı → NOC dışı satır yok → `closeQuietly`
  (işaretsiz — O-c1 doğru) → **NOC'a ÇÖZÜLDÜ gitmez**; `noc_deliveries` OPEN satırı kalır, 7/24 ekibi kesintiyi sürüyor sanır.
  Aynı yol takım e-postası olup da SMTP'nin FAILED döndüğü olayda da geçerli DEĞİL (FAILED satır "bildirildi" sayılır) — kusur
  yalnız satırsız olaylarda. O-c1 fırtına çözümünü kurtardı, bireysel NOC çözümü açık kaldı.
  | **Çözüm:** `closeQuietly(..., markSilenced=false)` dalında (bakım kurtarması) `notifyNocResolved(saved)` çağrılsın — servis
  zaten "açılış NOC'a gitmediyse çözüm de gitmez" kuralını uygular, çift gönderim riski yok. Alternatif: bakım dalının
  "bildirildi" kararına `noc_deliveries` OPEN satırını da katmak. Kapı: `NocNotificationServiceTest` — "push-tek takım, bakımda
  kurtarma → NOC RESOLVE gider".

### O-A3-4 — Çift kaynaklı DNS izlemede aynı değişiklik aynı turda görülünce ikinci sahibin DNS_CHANGED teyit zinciri `inFlight` anahtarında düşüyor → değişiklik o takım için KALICI yutuluyor (O-c2'nin "olay henüz açılmamış" penceresi)

- `service/MonitoringOutageService.java:989-1000` (`confirmKey` = `tür:alan adı:detay`, detay = kayıt tipi; izleme kimliği YOK),
  `:1002-1010` (`inFlight.putIfAbsent` → "Teyit zaten devam ediyor, atlanıyor"), `:836-845` (`handleDnsSweep` her `DnsChange`
  için `startConfirmation`), `service/SchedulerService.java:5568-5588` (her izlemenin turu `changed=true` + yeni değerle
  kaydedilir → `findLatestScheduledSuccessful` tabanı ilerler → bir daha algılanmaz), `:1466-1471` (`uq_dnsm_domain … WHERE
  standalone IS NOT TRUE` → envanter türevi A kaydı + bağımsız A kaydı aynı alan adında birlikte var olabilir), O-c2 yolu
  `EscalationService.java:1312-1320` YALNIZ açık olay varken.
  | **Neden bug:** senaryo — alan adı D için envanter türevi DNS A izlemesi (sahip `INV` → SY Takım X) ve Takım Y'nin bağımsız DNS A
  izlemesi (ele geçirme tespiti için eklenmiş). A kaydı değişir; iki izleme de aynı 5 dk'lık turda vadeli → iki `DnsChange` →
  ikinci `startConfirmation` düşer → 90 sn sonra TEK olay (listede önce gelen izlemenin sahibiyle) → yalnız o takıma bildirim.
  Diğer takımın turu değişikliği zaten kaydetti (taban yeni değer) → sonraki turlarda `changed=false`; olay açıkken O-c2'nin
  "başka izlemenin değişikliği" dalı da tetiklenmez (ikinci izleme bir daha değişiklik ÜRETMEZ). Sonuç: Y ele geçirme sinyalini
  hiç almaz (ya da X almaz). Aynı turda görülme koşulu dar değil: iki izleme de varsayılan 300 sn aralıkla aynı sweep'te koşar.
  | **Çözüm:** DNS_CHANGED teyit anahtarına izleme kimliğini kat (`confirmKey`: `DNS_CHANGED:<domain>:<recordType>:<monitor_id>`)
  — zincirler paralel koşar; teyit sonunda `processConfirmedOutage` açık olayı bulursa O-c2 dalı sahibine olaysız bildirim
  gönderir (mevcut kod). Kapı: `StormTeamIsolation`/`SharedKeyOwnerGuardTest` deseninde "aynı turda iki sahibin değişikliği →
  iki bildirim".

### O-A3-5 — Bakım penceresi, kenar tetikli değişiklik alarmlarını (DNS_CHANGED / DOMAINMON_CHANGED) KALICI olarak yutuyor (ürün kararı gerektirir)

- `service/EscalationService.java:1236-1239` (`processConfirmedOutage` bakımda hiçbir şey kaydetmeden döner — olay yok),
  `service/SchedulerService.java:5568-5588` (DNS tur satırı `changed=true` + yeni değer → taban ilerler),
  `DomainCheckerService` zamanlanmış satırı `changed=true` ile kalıcılaştırır (taban `findLatestScheduledWithData`),
  `service/MaintenanceService.java:56-58` (hedef anahtarı eşleşmesi; `all_monitors` penceresi hepsini kapsar).
  | **Neden bug:** durum tabanlı türlerde bakım biter bitmez alarm açılır (DOWN sürer → yeni teyit); kenar tetikli iki türde
  değişiklik yalnız görüldüğü turda vardır. Senaryo: takımın her Pazar 02:00–06:00 tekrarlı bakım penceresi (host yamaları);
  o pencerede registrar/NS değişikliği (ele geçirme ya da yanlış migrasyon) → tur görür, teyit eder, `processConfirmedOutage`
  bakım nedeniyle döner → satır kaydedildi, taban yeni değer → **hiçbir zaman alarm, hiçbir yerde iz** (yalnız DEBUG log).
  Bakım penceresi "bu hedefin erişim kesintisi planlı" demektir; alan adı kaydının değişmesi bakım kapsamı değildir. Haftada
  4 saatlik tekrarlı pencere = sinyalin %2,4'ünün kalıcı kaybı. (D-c1 ile aynı sonuç, farklı kapı.)
  | **Çözüm:** bakım bastırmasını kenar tetikli türlerde (`MANUAL_CLOSE_TYPES`) BİLDİRİMSİZ OLAY AÇMA olarak uygula: olay
  `resolved=false`, `lastReAlertAt=null` (yarım ilk bildirim dalı) ile kaydedilsin; bakım bitince ilk turda "yarım kalmış ilk
  bildirim" yolu (`:1398-1409`) bildirimi gönderir. Ya da ürün kararıyla CHANGELOG'a "bakım penceresinde değişiklik alarmı
  üretilmez" yazılsın. Kapı: "bakımda görülen DNS değişikliği bakım sonrası bildirilir".

### O-A3-6 — Yalnız Teams/Slack webhook'uyla teslim edilen alarm Alarm Geçmişi ve Olaylar'da "kimseye ulaşmadı — alıcı kuralını kontrol edin" (kırmızı) görünüyor

- `repository/NotificationLogRepository.java:43-51` (`countByAlertIds` yalnız `emailStatus='SENT'` sayar; webhook satırlarında
  `emailStatus` "SKIPPED: alıcı yok", `webhookStatus` SENT — `EscalationService.java:2377-2384, 2392-2406`),
  `frontend/src/components/admin/alerts/AlertBadges.jsx:177-180, 204` (`nobody = mailSent===0 && sent===0 && !stormMember`),
  `controller/IncidentsController.java:406-419` (`email_sent_count` aynı sorgu), `AlertLists.jsx:230`, `AlertDetail.jsx:105`.
  | **Neden bug:** `sendCombinedAlert` webhook-tek kurulumu açıkça destekler (`:2246-2250`: "takımın e-postası tanımsız + kontaklar
  webhook-only → alarm gitmeli"). Alarm Teams kanalına gider, satır `webhook_status=SENT` ile yazılır; ekran "e-posta: gönderilmedi
  · push: kayıt yok · kimseye ulaşmadı" der. Operatör "alıcı kuralı bozuk" sanıp Tekrar Bildir'e basar (ikinci Teams mesajı)
  ya da elle arar. Prod teşhis rehberi §6'daki "kimseye ulaşmadı yanıltıcı" listesi bu durumu içermiyor.
  | **Çözüm:** sayım sorgusuna `webhookSentCount` (`webhookStatus='SENT'`) ekle; `nobody` ve `alh.whyEmail*` çipleri webhook
  teslimatını ayrı çip olarak göstersin ("webhook: 1 gönderildi"). Kapı: `AlertBadges.test.jsx` — webhook-tek teslimatta kırmızı
  çip yok.

---

## DÜŞÜK

- **D-A3-1** `EscalationService.java:1354-1379` (yalnız TERFİ ele alınır), `:1262` (`teamOnly` bağlam seviyesiyle), `:1410-1415`
  (yeniden uyarı `alertLevel` = bağlamın seviyesi) — izlemenin seviyesi alarm açıkken DÜŞÜRÜLÜRSE yeniden uyarı e-postası yeni
  (düşük) seviyeyle ve kişisiz gider, olay ve push eski (yüksek) seviyede kalır; sertifika yolundaki E11 koruması
  (`:410-413`, "alıcı seviyesi olayınkinin altına inmez") izleme yolunda yok. | **Çözüm:** `sendLevel = max(bağlam, olay)`.
- **D-A3-2** `frontend/src/utils/resolvedBy.js:12-16` (3 jeton) ve `components/incidents/incidentsModel.js:160-163`
  (`inventory_deactivate` bile yok) — arka uç ~20 farklı `"Sistem (…)"` metni yazıyor (`EscalationService.java:1000, 1091, 1110,
  1129, 1147, 1165, 1183, 1202, 1220`, `MonitoringController.java:1035`, `SchedulerService.java:2873, 5548`,
  `MonitoringOutageService.java:709, 1258`, `ScriptedAnomalyGuard.java:52`); `AlertBadges.jsx:149-161` bilinmeyen jetonu
  `UserBadge` ile KİŞİ olarak çizer (avatar + "Sistem (izleme duraklatıldı)" adlı kullanıcı). `IncidentsController.java:377-381`
  başka ekibin satırında bu jetonları bilinçli geçiriyor — arayüz tanımıyor. | **Çözüm:** `isSystemResolver` → `system` /
  `inventory_*` / `^Sistem\b` öneki; `Bot` ikonlu düz metin.
- **D-A3-3** `EscalationService.java:863-885` (`resolve` fırtına üyeliğine bakmaz) + `StormService.java:349-366` — elle çözülen
  fırtına üyesi için bireysel "ÇÖZÜLDÜ" e-postası/push'u gider, sonra `resolveStorm` aynı üyeyi "kurtarıldı" sayıp toplu çözümde
  ikinci kez bildirir. | **Çözüm:** elle çözümde de `stormId != null && isActive` ise bireysel maili bastır (push simetrisi kalır).
- **D-A3-4** `EscalationService.java:868-882` (elle çözüm: oku-kontrol-yaz, atomik kapı yok) vs `:926-931` (`markResolvedIfOpen`
  yalnız otomatik yolda) — otomatik kapanışla aynı saniyede elle "Çöz" → `resolvedBy/At` ezilir, ikinci çözüm e-postası
  (MANUAL_RESOLVE). Dar yarış. | **Çözüm:** elle yol da `markResolvedIfOpen(id, now, by)` ile kapatsın; 0 dönerse 409.
- **D-A3-5** `SchedulerService.java:3193-3203` (`pingOutcome`: `na → "up"`) — ortam ICMP yetkisini kaybederse (securityContext
  değişikliği) açık tüm PING_DOWN alarmları "✅ ÇÖZÜLDÜ" diye kapanır (D-7 sınıfı: ölçülemedi = sağlıklı). Nadir. | **Çözüm:**
  `na` → kalem üretme ("skipped").
- **D-A3-6** `SchedulerService.java:3170-3181` (`httpOutcome` `cfgError → up`), `:3206-3221`, `:3726-3731`, `:4097-4102` — yorumlar
  "askıda alarm SESSİZCE kapanır" der ama kalem `closeAlarm` → normal çözüm yolu → "✅ ÇÖZÜLDÜ — sorun giderildi" e-postası +
  push; oysa izleme bozuk yapılandırıldı (URL'de boşluk). | **Çözüm:** `config_error` kaleminde `resolveOpenAlertsSilently`
  (resolvedBy "Sistem (yapılandırma hatası)").
- **D-A3-7** `noc/NocNotificationService.java:76` (`OPEN_TRIGGERS` = INITIAL/ESCALATION/DAILY_REALERT) + `EscalationService.java:2220`
  — elle "Tekrar bildir" (MANUAL) 7/24'e HİÇ gitmez; NOC açılışı FAILED (SMTP) kaldıysa operatörün doğal tekrar yolu çalışmaz,
  yeniden deneme ancak ertesi günkü DAILY_REALERT'te. | **Çözüm:** MANUAL'i de açılış tetiği say (satır bazlı `blocking` zaten çift
  gönderimi önler).
- **D-A3-8** `UserPushService.java:1154` (RE_ALERT dedupe = takvim GÜNÜ) vs `EscalationService.java:3154-3163, 3192-3196` (e-posta
  yeniden uyarısı `reAlertIntervalHours` kayan pencere) — aralık 24 saatten kısa ayarlanırsa aynı gün ikinci yeniden uyarıda
  e-posta gider, push sessizce tekilleşir (`existsBy…DedupeKey`). Ayar bağımlı. | **Çözüm:** dedupe anahtarına `lastReAlertAt`
  damgasını kat.
- **D-A3-9** `noc/NocMonitorDirectory.java:105-121` (`forAlert`: `monitor_id` yoksa alan adıyla İLK `noc_notify` satırı — `pick`) +
  `MonitoringOutageService.java:869-870` (açanı bilinmeyen DNS_CHANGED yeniden uyarısında `monitor_id` düşürülür) — çift kaynaklı
  alan adında başka sahibin izlemesinin 7/24 onayı/grubu bu olayın NOC kararını belirleyebilir (arama listesi doğru takımın).
  Yalnız eski kimliksiz olaylar. | **Çözüm:** olayın sahip anahtarıyla aynı sahibin satırını seç.

---

## Tür × yaşam döngüsü tablosu (20.91.0 sonrası — bu turda doğrulanan)

Sütunlar: **Açılış** (teyit zinciri / seviye kaynağı), **Kapanış kanıtı** (kalem "up"), **Ölçülemediğinde**, **Kapanış yolu
olmayan durum**, **Bu turun bulgusu**.

| Tür | Açılış | Kapanış kanıtı | Ölçülemediğinde | Kapanış yolu yok → | Bulgu |
|---|---|---|---|---|---|
| ACCESSIBILITY | 3×30 sn (global), seviye WARNING (izleme yok) | uptime `up` | her sonuç up/down | — | Y-A3-1 (envanter pasif → paylaşılan anahtar) |
| PORT_DOWN | izleme ayarı, seviye izlemeden | port açık | — | — | Y-A3-1, O-A3-1 |
| PORT_SLOW | izleme ayarı | özellik kapalı ya da ölçülü ≤ eşik | kalem yok ✓ | port kalıcı kapalı → elle | — |
| DNS_FAILURE | izleme ayarı, **seviye WARNING sabit** | çözümleme başarılı | — | — | **Y-A3-2** |
| DNS_SLOW / UNEXPECTED / INCONSISTENT | izleme ayarı, seviye izlemeden | başarı + ölçüt | kalem yok / zincirde "skipped" ✓ | beklenen değer kaldırılırsa elle | — |
| DNS_CHANGED | 3×30 sn (global), **seviye WARNING sabit** | yok (elle) | teyitte çözülemezse "down" ✓ (D-c1) | elle | Y-A3-2, O-A3-4, O-A3-5 |
| KEYWORD | izleme ayarı | koşul + HTTP hatasız (cfgError=up) | — | — | O-A3-1, D-A3-6 |
| KEYWORD_SLOW | izleme ayarı | özellik kapalı / ölçülü ≤ eşik | kalem yok ✓ | — | — |
| KEYWORD_SSL / KEYWORD_DOMAIN_EXPIRY | günlük, anında (0 teyit), kurtarma 1 | günlük kontrol temiz / anahtar kapalı | **"up" (veri yok = sağlıklı)** | — | **O-A3-2** |
| HTTP_SSL / DOMAIN_EXPIRY | günlük, anında, kurtarma 1 | aynı | **"up"** | — | **O-A3-2** |
| PING_DOWN | izleme ayarı | `up` ya da `na` | `na` → up | — | D-A3-5, Y-A3-1 |
| PING_SLOW | izleme ayarı | özellik kapalı / taban ≥3 + eşik altı | kalem yok ✓ | taban kalıcı yetersiz → elle (D-b bilinen) | — |
| HTTP_DOWN | izleme ayarı | erişildi / cfgError | — | — | O-A3-1, D-A3-6 |
| PAGE_DOWN / PAGE_INTEGRITY | izleme ayarı; INTEGRITY kaynak-duyarlı (HOME/CRAWL) | ana sayfa alındı + temiz / temiz crawl | kalem yok / "skipped" ✓ | crawl kaynak sayfası kalıcı ölçülemez (D-c2) | — |
| PAGESPEED_DOWN / SLOW | izleme ayarı | erişildi / eşik içi | kalem yok ✓ | — | — |
| SCRIPTED_FAIL / SLOW | izleme ayarı; SKIPPED kaleme girmez ✓ | koşum geçti / süre ≤ eşik | "skipped" ✓ | k6 kalıcı yok → tür kapatma sessiz kapanış ✓ | — |
| DOMAINMON_UNKNOWN | izleme ayarı (teyitli) | veri geldi | her tur kalem | — | — |
| DOMAINMON_EXPIRY / STATUS | anında, seviye gün/EPP kademesi | veri + eşik üstü | kalem yok ✓ | RDAP kalıcı kapalı → donar (UNKNOWN anlatır) | — |
| DOMAINMON_TRANSFER_LOCK / BLACKLIST | anında | kesin kilit var / LISTED değil | UNKNOWN → kalem yok ✓ | kaynak WHOIS'e döndü → elle | — |
| DOMAINMON_CHANGED | anında | yok (elle) | — | elle; bakım penceresinde KALICI kayıp | O-A3-5 |
| Sertifika (EXPIRY, REVOKED, MISMATCH, CHAIN_BROKEN, HOSTNAME_MISMATCH, UNTRUSTED_CA) | saatlik sweep, tier eşiği, kusur=CRITICAL | tür üretilmiyor + `status≠error` | `status=error` → dokunulmaz ✓ | host kalıcı erişilemez → envanter pasif (Y-A3-1) | Y-A3-1 |

Yeniden açılış (kapandıktan sonra aynı sorun): tüm türlerde açık olay bulunmaz → yeni teyit zinciri → yeni olay + INITIAL (dedupe
yok, kasıtlı); günlük yeniden uyarı yalnız açık olayda (`reAlertDue` kayan 24 sa) ✓. Flap koruması: teyit N×aralık + kurtarma N
ardışık (+ aktif zincir) ✓; `AlertNoiseService` flap adaylarını raporlar (D-c11 sessiz kapanışları sayar).

---

## Alıcı yolu × takım yalıtımı

| Yol | Alıcı kaynağı (kaynakta) | Başka takıma gidebilir mi? | Hiç kimseye gitmez (sessiz kayıp)? |
|---|---|---|---|
| İlk alarm e-postası | `collectTeamEmails(SY, UG, damgalı grup)` — bağımsızda yalnız SY (`processConfirmedOutage :1244-1259`) | Hayır ✓ | Takım adresi yoksa WARN + push/webhook (`:2251-2256`) ✓ |
| Eskalasyon kişileri | `EscalationContactScope.forOwners` — yalnız sahip takım(lar), yedek yok | Hayır ✓ | WARNING bağımsızda kişisiz (karar) ✓ — **Y-A3-2**: DNS_FAILURE/CHANGED hep WARNING |
| Günlük yeniden uyarı / terfi | `event.getTeamId()` damgası + `sameOwner` kapısı | Hayır ✓ | Fırtına üyesinde bireysel yok (toplu gider) ✓ |
| Çözüm e-postası | damga / envanter (bağımsız değilse); sahipsiz → yok | Hayır ✓ (bkz. **Y-A3-1**: yanlış takımın olayı kapanır) | Bakımda bildirimsiz olay sessiz ✓ |
| Kişi push (OPEN/RE_ALERT/ESC) | `resolver.resolve(event.teamId ?: SY yedek, seviye)` — üyelik + org rolü + seviye | Hayır ✓ | Grup kapalı / seviye altı → `SKIPPED_*` satırı ✓ |
| Kişi push (RESOLVE) | açılışta SENT olanlar (`resolvePrior`); fırtına: `priorStormRecipients(storm, team)` | Hayır ✓ | Önce SENT yoksa `SKIPPED_NO_PRIOR` ✓ (**Y-A3-1**: yanlış takıma "düzeldi") |
| Kontak webhook | sahip takımın kişileri; URL'ye göre tekil | Hayır ✓ | Ekranda "kimseye ulaşmadı" görünür (**O-A3-6**) |
| 7/24 (NOC) | izleme `noc_notify` + gruplar (kurum 7/24 ekibi); arama listesi = sahip takım | Hayır ✓ (D-A3-9 eski olay kenarı) | Bakımda kurtarma → çözüm gitmez (**O-A3-3**); MANUAL tetik gitmez (D-A3-7) |
| Fırtına (e-posta/push/webhook/NOC) | `TEAM:<id>` kapsamı; dağıtım takım başına; push yalnız SY | Hayır ✓ | Tümü sessiz kapanınca push yok (D-c9) |
| Haftalık erişilebilirlik raporu | takım adresi + takımın PO/TECH/MANAGER kişileri (`WeeklyAvailabilityReportService :984-1003`) | Hayır ✓ | Alıcısız takım `NO_RECIPIENT` kaydı ✓ |
| Haftalık rapor onayı | takımın MANAGER kişileri, yoksa üyelerin AD müdürleri (`WeeklyReportService :903-924`) | Hayır ✓ (müdür = takımın) | `MANAGER_CONTACT_MISSING` 409 ✓ |
| Alan adı bitiş hatırlatması | izlemenin grubu → takım grubu → takım adresi; takım push'u (`DomainExpiryReminderService :103, 123`) | Hayır ✓ | Kanal kapalı → `SKIPPED_*` kaydı ✓ |
| Sertifika envanter raporu (aylık) | TÜM sahip takımların adresleri, içerik envanterin TAMAMI (`CertificateInventoryReportService :96-126`) | **Evet — tasarım** (belgeli: "eksik kayıtlar sahiplerince görülsün") | — |
| Anomali guard bildirimi | izlemenin grubu/takımı + takım push'u | Hayır ✓ | — |
| Envanter silme/pasif kapanışı | `findByDomainAndResolvedFalse(domain)` — sahip süzgeçsiz | **Evet — Y-A3-1** | — |

---

## 29c DÜŞÜK RE-CHECK (D-c1 dâhil)

| Kod | Durum | Kanıt (bu tur) | Yeni önem |
|---|---|---|---|
| D-c1 | **ÇÖZÜLMÜŞ ✓** | `SchedulerService.java:5721-5737` `!success → "down"` | — |
| D-c2 | AÇIK | `recheckIntegrityPages :3994-4017` ölçülemeyen kaynak → "skipped"; sayaç bellekte (`MonitoringOutageService :196`); `alh.whyAutoTip` genel | DÜŞÜK |
| D-c3 | AÇIK | `INTEGRITY_RECHECK_MAX_PAGES=5` × `max-check-seconds` (120) — tavan yok | DÜŞÜK |
| D-c4 | AÇIK | yeniden uyarı dalı `event.setMessage` yazar, `contextJson` güncellemez (`EscalationService :1410-1420`) | DÜŞÜK |
| D-c5 | AÇIK | `closableBy :1039-1044` açan varsa yalnız açan; öksüz temizliği `findAll` (duraklatılmış var sayılır) | DÜŞÜK |
| D-c6 | AÇIK | `retireLegacy :437` `legacyStormId` yalnız `created` ise | DÜŞÜK |
| D-c7 | **ÇÖZÜLMÜŞ ✓** | `sendStormAlert :708-727` seviye `stormPushLevel`; NOC `variantForLevel(level)`; kapı `AlertLevelWordConsistencyGateTest :149-164` | — |
| D-c8 | **ÇÖZÜLMÜŞ ✓** | `EmailNotificationService :1399, 1730, 1777` `severityTone(level)` | — |
| D-c9 | AÇIK (tasarım gerilimi) | `sendStormRecovery :801-806` hepsi sessizse push yok | DÜŞÜK |
| D-c10 | AÇIK (tek seferlik geçiş) | `resolved_silently` NULL eski satırlar | DÜŞÜK |
| D-c11 | AÇIK | `WeeklyAvailabilityReportService :478` `!stillOpen` → çözülmüş; `AlertNoiseService :91` — `resolvedSilently` süzgeci yok | DÜŞÜK |
| D-c12 | AÇIK | `onReadyCloseDisabledTypeAlarms :1242-1245` eşzamanlı | DÜŞÜK |
| D-c13 | AÇIK | `monitorCheckColumns.jsx :22-25` `scripted: 1` sabit | DÜŞÜK |
| D-c14 | AÇIK | `retireLegacy :446-447` `released++` dönüş değerine bakmaz | DÜŞÜK (yalnız log sayısı) |
| D-c15 | **KISMEN ÇÖZÜLMÜŞ** | bakım dalı testi VAR (`StormSilentMemberRecoveryTest :101 maintenanceRecovery_isNotSilenced_stormRecoveryGoes`, `:123 normalRecovery_doesNotMark`); seviye kapısı fırtına kanalını kapsıyor (`:149-164`); 7/24 fırtına GÜNCELLEME postası kapıda değil | DÜŞÜK |
| D-c16 | **ÇÖZÜLMÜŞ ✓** | `MonitoringControllerTest.java` yer tutucu dışı `.com.tr` sayısı 0 | — |

---

## Doğru bulunan (tarandı, temiz)

- **Kapanış uzlaştırması** (K1/K2/K5): bastırma ve kapalı bildirim yalnız yeni alarmı durduruyor; ardışık sayaç + aktif zincir
  + takılı zincir bekçisi (`STALE_CHAIN_GRACE_MS`); kuşak numarası global, `markResolvedIfOpen` atomik; çift kapanış yok ✓.
- **Elle kontrol**: 9 türün tüm `evaluate*Now` yolları `manual=true` (yalnız `reconcileRecoveries`); kritik alan adı kontrolü
  `evaluateDomainAlarms(m, r, false)`; SKIPPED elle senaryo değerlendirmeye girmez; O-1 aralık kapısı; D-6 ilk tur bekçisi ✓.
- **Dağıtık kilitler**: `withLock` / `tryLock` / `tryAcquireSchedulerLock` geçici DB hatasında turu ATLAR (fail-open değil);
  `DuplicateKey` tipli; sweep kilitleri `-sweep` + açılışta bu host'un eskileri temizlenir ✓. Aynı olay iki pod'da iki kez
  açılmaz.
- **Sahip ayrımı** (Y-1 / D-b1): `sameOwner` / `ownerKeyOf` / `contextMonitorId` tek sözlük; `handleSweepDomain` DOWN dalı,
  `downSibling`, `processConfirmedOutage`, `closableBy` ve 18 `ownerCtx` çağrı yeri tutarlı ✓ (istisna: Y-A3-1 envanter yolu,
  O-A3-1 UP yönü).
- **Eskalasyon kişileri**: `EscalationContactScope` tek kaynak, global yedek yok; takımsız kontak alıcı değil; fırtına
  `contactsForLevel` aynı kapsam; simülatör aynı kod ✓. Takım süzgeçsiz `contactRepo` sorgusu yalnız haftalık raporun kendi
  takımı için `findByTeamIdAndRoleAndActiveTrue` ✓.
- **Sahipsiz kayıt**: `sendCombinedAlert :2203-2207`, `sendResolutionNotification :1845-1849`, `reNotify requireOwned` (409),
  `StormService.evaluate` (teamId null → bireysel), NOC `eligibleStormMembers` — hiçbir kanaldan gitmiyor ✓.
- **Fırtına**: `TEAM:<id>` kapsamı; pencere sorgusu takıma süzülüyor; farklı hedef sayısı + `PERCENT_MIN_TARGETS=3`; atomik
  terfi `ON CONFLICT`; `bumpMemberCount` koşullu UPDATE (D-14 kapandı); histerezis; `unlinkFromStorm` → INITIAL (kayıp yok);
  eski fırtına emekliliği (`moveToStormIfOpen` / `releaseFromStormAsNotified` koşullu, D-b7 kapandı); push yalnız SY, seviye =
  en yüksek üye; çözüm push'u `priorStormRecipients` (+`legacyStormId`); sessiz üyeler kurtarılmış sayılmaz, bakım işaretsiz
  (O-c1 kapandı) ✓.
- **Push**: OPEN/RE_ALERT/ESC takım+seviye+org rolü+opt-out; RESOLVE sessiz saat ve tavan muaf, yalnız açılışta SENT olanlara;
  `SKIPPED_MONITOR_OFF` kalıcı; devre kesici PENDING bırakır; fırtına push'u aynı kanal kapıları; `stormDedupeKey` günlük ✓.
  Şablon seviye sözcüğü `levelWordTr` (D-b14 kapandı) ✓.
- **7/24**: yalnız INITIAL/ESCALATION/DAILY_REALERT açılış; tekil anahtar `claim` yarış güvenli; bayat SENDING 10 dk; çözüm
  yalnız açılış SENT ise; fırtına açılış/tik/çözüm tekilleştirmeli; `noc_deliveries` açık alarmda silinmez; günlük MASKELİ ✓.
- **Bakım penceresi**: `processConfirmedOutage` bastırır (açılış + yeniden uyarı + DNS_CHANGED); NOC açılışı da bastırır;
  kurtarma: bildirim gittiyse normal, gitmediyse sessiz-işaretsiz ✓ (istisna O-A3-3 NOC çözümü).
- **`resolvedSilently`**: yazan yollar (duraklat/sil/anahtar değişti/öksüz/envanter pasif-silindi/tür kapalı/anomali guard)
  ve tek tüketici `sendStormRecovery` ✓; normal ve bakım kapanışı yazmaz ✓.
- **E-posta içeriği**: MailDoc `alert/title/pre/bullets/note` `MailKit.esc/escBr` ile kaçırılıyor (`MailDoc.java:114-266`);
  DNS fark tablosu `esc(truncValue)`; `esc()` tek kaynak (`EmailNotificationService :3044`); elle HTML yalnız `raw` ile ve
  kaçırılmış; seviye rozeti/konu/"Seviye" satırı/gövde/push/NOC tek sözlük (`levelWordTr`; O-b1 kapandı, D-c8 kapandı);
  tarihler UTC → Europe/Istanbul (`formatIstanbul/formatIsoFull`); CTA'lar `liveBaseUrl()` canlı; `subjectDisplayName` çıplak
  URL'yi konudan soyar; "null" yazımı `ctxStr` null-toleranslı ✓. HTML+düz metin aynı MailDoc'tan ✓.
- **Webhook**: durum kodu kontrolü, SSRF `validate`, tavanlı gövde, URL maskeli log, adres bazlı tekilleştirme (açılış +
  çözüm + fırtına) ✓.
- **Raporlar**: haftalık erişilebilirlik / kesinti raporu takım kapsamlı (`loadWeekAlarms scope=List.of(teamId)`); CC yalnız
  takımın kişileri; haftalık rapor onayı takımın müdürüne; alan adı hatırlatması ve anomali bildirimi izlemenin kendi
  grubu/takımı ✓. Sertifika envanter raporunun kurum geneli olması belgeli tasarım.
- **Kanonik zincir** (SCRIPTED_FAIL listesi): `IncidentsController.rootCause/family/tabFor` tüm türleri kapsıyor
  (`:447-552`, kapı testi var); `StormService.rootCauseLabel` DOWN kümesini; `alertEnabled` switch'i 29 türü;
  `isStillMonitored` 10 öneki; `MonitorTypeCatalog` kapısı; her `recheck*` `activityLog.recordCheck` ✓.
- **Kullanıcıya görünen durum**: Alarm Geçmişi "Neden hâlâ açık?" — kapanış kuralı çipi (otomatik/elle), fırtına üyesi çipi
  (`data-why-storm`), `alh.whyAutoTip` kardeş + tür-kapalı istisnalarını söylüyor; Olaylar `resolvedByFor` başka ekipte
  kişiyi gizliyor; alarm eylem uçları `requireAlertScope` (damga ya da envanter SY/UG); toplu işlem kapsam dışını atlıyor ✓.
- **Yeniden başlatma**: teyit/kurtarma durumu bellek-içi (belgeli, güvenli taraf); `catchUpMissedDailyAlerts` yalnız sertifika;
  `catchUpAlertsOnDeletedDomains`; `onReadyCloseDisabledTypeAlarms`; `DerivedMonitorAlertRouting` geçişi; `clearStaleLocksForThisHost`
  ✓ (istisna: O-A3-1 pencere).
- **Retention**: `noc_deliveries` açık alarm/fırtına satırını korur; `user_push_deliveries` 3 yıl (çözüm push simetrisi için yeter);
  `notification_logs` 1 yıl ✓.

---

## Okundu ✓

Kaynak belgeler: `.claude/commands/bug-denetle.md` · `CLAUDE.md` "Escalation & notifications" · `BUG_REGRESYON_2026-09-29.md`,
`-29b.md`, `-29c.md` · `D:\site-monitor-shadcn\.migration\port-alarm-rca\PROD_DIAGNOSIS.md` · 13 bellek notu · `CHANGELOG.md`
20.91.0 başlığı · `git status` / `git log`.

Backend (tamamı ya da belirtilen bölümler): `service/MonitoringOutageService.java` (tamamı) · `service/EscalationService.java`
(tamamı) · `service/StormService.java` (tamamı) · `service/SchedulerService.java` (:330-550 açılış/ctx yardımcıları/`isStillMonitored`,
:2040-2260 sertifika sweep'i + kilit, :2596-3625 uptime/port/keyword/http + kalem kurucuları + `evaluate*Now`, :3626-4335
sayfa/crawl/sayfa hızı, :4334-4730 sentetik, :4744-5080 HTTP/İçerik SSL-alan adı, :5080-5390 alan adı, :5383-5500 ping,
:5497-5770 DNS + yeniden ölçümler; `applySchemaPatches` yalnız tekillik indeksleri) · `service/MaintenanceService.java` (tamamı) ·
`service/UserPushService.java` (:200-760 tetikler/kanal kapıları/fırtına push'u, :1085-1375 sessiz saat/dedupe/şablon) ·
`service/UserPushRecipientResolver.java` (tamamı) · `service/noc/NocNotificationService.java` (:100-660) ·
`service/noc/NocMonitorDirectory.java` (`forAlert`, `keyOf`) · `service/noc/NocMailComposer.java` (`levelTr`, konu satırları) ·
`service/noc/NocCallListService.java` (imzalar) · `service/WebhookService.java` (tamamı) · `service/ScriptedAnomalyGuard.java`
(tamamı) · `service/DomainExpiryReminderService.java` (tamamı) · `service/AlertNoiseService.java` (tamamı) ·
`service/DerivedMonitorAlertRouting.java` (tamamı) · `service/EscalationContactScope.java` (tamamı) ·
`service/EmailNotificationService.java` (:1236-2035 alarm/çözüm/fırtına/keyword/ping/DNS belgeleri + tarih biçimleri, :3040-3047) ·
`service/EmailTemplateBuilder.java` (`severityTone/Label/Badge`, imzalar) · `service/mail/MailDoc.java` + `MailKit.java` (kaçış
sözleşmesi) · `service/DnsCheckerService.java` (`changeCtxOf`, `lastChangedRecord`) · `service/RdapDomainExpiryService.java`
(`days_remaining` null yolu) · `service/WeeklyAvailabilityReportService.java` (:150-210, :460-503, :975-1010) ·
`service/WeeklyReportService.java` (:895-951, alıcı grep'i) · `service/report/WeeklyOutageReportService.java` (:172-226) ·
`service/report/CertificateInventoryReportService.java` (:96-170) · `service/retention/RetentionCatalog.java` (:105-135) ·
`model/AlertEvent.java` · `model/MonitorAlertPrefs.java` · `repository/AlertEventRepository.java` (tamamı) ·
`repository/NotificationLogRepository.java` (:38-60) · `repository/UserPushDeliveryRepository.java` (:51-74) ·
`controller/IncidentsController.java` (tamamı) · `controller/AdminController.java` (:596-608, :1717-1742, :2300-2577) ·
`controller/MonitoringController.java` (:1032-1056, :1836-1900, :2330-2410, `setAlertLevel` çağrı yerleri).

Frontend: `components/admin/alerts/alertHistoryModel.js` (tamamı) · `AlertBadges.jsx` (tamamı) · `AlertDetail.jsx` (:95-160) ·
`AlertNotifications.jsx` / `AlertLists.jsx` (durum grep'i) · `components/incidents/incidentsModel.js` (tamamı) ·
`IncidentBadges.jsx` (:105-122) · `components/admin/RecipientSimulator.jsx` (API bağlantısı) · `components/ui/UserBadge.jsx`
(:35-60) · `utils/resolvedBy.js` (tamamı) · `DnsMonitorPage.jsx` (:88, 246, 290, 584) · `check/monitorCheckColumns.jsx` (:18-28) ·
`i18n/index.jsx` (`alh.why*`, `alh.resolvedBy.*`, `incov.resolvedBy.*`).

Testler (adlar/kapsam, RE-CHECK için): `StormSilentMemberRecoveryTest`, `AlertLevelWordConsistencyGateTest`,
`MonitoringControllerTest` (kimlik taraması maskeli).

Okunmayan (kapsam dışı bırakıldı, gerekçeli): `EmailTemplateBuilder.buildHtml/buildText` gövdesi (sertifika/alan adı belgeleri —
`EmailResponsiveContractTest`/`EmailTypeContentTest` kapılı; kaçış `esc` tek kaynak), `UserPushService.sendBatch/drainOutbox`
(teslim altyapısı, yaşam döngüsü kararı yok), `PushText.java`, `WeeklyReportService` iş akışı (onay/iade — alıcı çözümü okundu),
`CertInventoryMail.java`, `NocCallLogService.java`, `AlertHistory.jsx` bileşen gövdesi (model ve rozetler okundu),
`whonotified/*` (dizin yok — `RecipientSimulator.jsx` + `PushDecisionAccess` ucu okundu).

---

## Önerilen düzeltme sırası

1. **Y-A3-1** — `closeOpenAlerts` / `findOpenAlertsOnSoftDeletedDomains` sahip süzgeci (2 satır + 1 JPQL koşulu + kapı testi). Ucuz,
   takım yalıtımı.
2. **Y-A3-2** — DNS_FAILURE / DNS_CHANGED bağlamına `alert_level` (3 çağrı yeri `chanCtx(ctx, m)`; `DnsChange`'e seviye). Ucuz,
   kullanıcı ayarını canlandırır.
3. **O-A3-2** — HTTP/İçerik SSL-alan adı alt alarmlarında "veri yok" → kalem yok / "skipped" (4 metot). Günlük flap'i keser.
4. **O-A3-1** — `up` listesine sahip süzgeci (2 satır). Dağıtım sonrası sahte ÇÖZÜLDÜ'yü keser.
5. **O-A3-3** — bakım kurtarmasında `notifyNocResolved` (1 satır).
6. **O-A3-4** — DNS_CHANGED teyit anahtarına `monitor_id` (1 satır + test).
7. **O-A3-6** — webhook teslimat sayacı + çip (sorgu + 2 bileşen).
8. **O-A3-5** — ürün kararı: bakımda değişiklik alarmı (bildirimsiz olay) — CHANGELOG'a yazılsın ya da uygulansın.
9. DÜŞÜK'ler: D-A3-2 (`resolvedBy.js` "Sistem" öneki — kullanıcıya görünür, 3 satır), D-A3-6 (cfgError sessiz kapanış), D-A3-4
   (elle çözümde atomik kapı), sonra kalanlar; 29c devredenlerden D-c11 (rapor MTTR'de sessiz kapanış) ve D-c5 (hayalet yol).

## Özet tablo

| Kod | Önem | Dosya:satır | Kısa |
|---|---|---|---|
| Y-A3-1 | YÜKSEK | `EscalationService.java:1756-1768, 1776-1792`, `AlertEventRepository.java:144-153` | Envanter silme/pasif, aynı anahtardaki başka takımın bağımsız alarmını sessizce kapatıyor + yanlış "düzeldi" push'u |
| Y-A3-2 | YÜKSEK | `SchedulerService.java:3156-3158`, `MonitoringOutageService.java:1380, 910` | DNS_FAILURE / DNS_CHANGED hep WARNING — izleme seviyesi (eskalasyon kişileri, yönetici push'u) yok sayılıyor |
| O-A3-1 | ORTA | `MonitoringOutageService.java:522-528, 550, 594-606` | Yabancı sahibin UP kalemi kurtarma kanıtı (yeniden başlatma sonrası yanlış kapanış) |
| O-A3-2 | ORTA | `SchedulerService.java:4896-4909, 5063-5076, 4883-4887, 5051-5054` | HTTP/İçerik SSL-bitişi & alan adı-bitişi: veri yok = sağlıklı (O-5 kardeşi) |
| O-A3-3 | ORTA | `EscalationService.java:896-916, 997-1019` | Bakımda düzelen push-tek alarmın NOC ÇÖZÜLDÜ postası gitmiyor |
| O-A3-4 | ORTA | `MonitoringOutageService.java:989-1010`, `SchedulerService.java:1466-1471` | Çift kaynaklı DNS'te aynı tur değişikliği: ikinci sahibin DNS_CHANGED'i kalıcı yutuluyor |
| O-A3-5 | ORTA (ürün kararı) | `EscalationService.java:1236-1239` | Bakım penceresi DNS_CHANGED / DOMAINMON_CHANGED'i kalıcı yutuyor |
| O-A3-6 | ORTA (gösterim) | `NotificationLogRepository.java:43-51`, `AlertBadges.jsx:177-204` | Webhook-tek teslimat "kimseye ulaşmadı" görünüyor |
| D-A3-1…9 | DÜŞÜK | yukarıda | seviye düşürme asimetrisi, "Sistem (…)" jetonu kişi rozeti, fırtına üyesi elle çözüm çifti, elle/otomatik çözüm yarışı, ICMP `na`, cfgError çözüm maili, MANUAL→NOC, push RE_ALERT gün dedupe, NOC dizin yedeği |
| 29c devreden | DÜŞÜK | RE-CHECK tablosu | D-c2..c6, c9..c15 açık; c1, c7, c8, c16 çözülmüş; c15 kısmen |
