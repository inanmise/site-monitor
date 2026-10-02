# Bug regresyon taraması — Eskalasyon / alıcı yönlendirme (2026-09-28d)

Kapsam: 20.90.0 öncesi, commit edilmemiş "eskalasyon kişisi yalnız sahip takımdan" değişikliği (EscalationService,
EscalationContactScope, StormService, SchedulerService.alarmTeamOf, MonitoringOutageService, NocNotificationService,
TeamAdminService, InventoryImportService, AdminController, MonitoringController, simülatör ön yüzü + testler).
Salt okunur tarama: kod değiştirilmedi, test/derleme koşulmadı. Her bulgu kaynakta okunarak doğrulandı. Etkisi prod
verisine bağlı olanlar "doğrulanmalı" diye işaretlendi.

Biçim: `dosya:satır — bug | Neden bug: senaryo | Çözüm: düzeltme`

---

## YÜKSEK

### Y1 — "Taşı ve sil" sonrası açık alarmlar sessizce kimseye gitmiyor (yedek yolun kalkmasıyla ortaya çıktı)

`backend/src/main/java/com/sitemonitor/service/TeamAdminService.java:168-235` (moveAll açık alarmları taşımıyor) +
`TeamAdminService.java:150` (`empty` açık alarmları saymıyor) + `controller/AdminController.java:2865-2870`
(deleteTeam 409 kapısı) + `service/EscalationService.java:319-321, 1198-1200, 1663` (damga önceliği) — **moveAll,
`alert_events.team_id` damgasını `from`'dan `to`'ya çevirmiyor. Silme kapısı da açık alarmları "bağlı kayıt"
saymıyor.**

**Neden bug:** Etki modalindeki tek düğme ("Taşı ve sil") önce `moveAll(from→to)`, sonra `deleteTeam(from)` çağırıyor.
Envanter, izlemeler, kişiler ve gruplar `to`'ya geçiyor. Ancak açık alarmlar `teamId = from` damgasıyla kalıyor.
İ18n metni de bunu söylüyor: "taşıma açık alarmlara dokunmaz". `impact.empty` açık alarmları hesaba katmadığı için
silme 409'a takılmıyor. Sonrasında bütün yollar (sertifika `processResults` :319, izleme `processConfirmedOutage`
:1198, çözüm :1663, tekrar bildir, fırtına `m.getTeamId()`) damgayı öncelikli okuyor ve SİLİNMİŞ takıma gönderim
yapıyor:
- `collectTeamEmails` takımı bulamıyor, adres çıkmıyor.
- `EscalationContactScope.forOwners(silinmiş)` boş dönüyor (kişiler `to`'ya taşındı).
- Push için silinmiş takımın üyesi yok.

Sahipsiz kapısı da tetiklenmiyor (teamId dolu). Geriye yalnız "E-posta alıcısı yok" WARN satırı ve (açıksa) 7/24
kalıyor. Örnek: açık EXPIRY UYARI alarmı haftalar içinde KRİTİK'e tırmanıyor, ama ESCALATION e-postası hiçbir takıma
ve kişiye gitmiyor. Eski sürümde aynı durumda (sızıntılı) global yedek birilerine ulaşıyordu. Yedeğin kalkmasıyla
sessiz susmaya dönüştü. Silme yapılmasa bile yalnız taşıma sonrası: `from`'un kişileri taşındığı için açık alarmların
KRİTİK eskalasyonu kişilere gitmiyor, yalnız `from`'un çıplak takım adresine gidiyor. Damgalı grup artık `to`'nun;
`overrideFor` sahiplik uyuşmazlığıyla onu yok sayıyor. Kapsamlı `to` kullanıcıları bağımsız izleme alarmlarını da
göremiyor/çözemiyor (damga `from`).

**Çözüm:** `moveAll` içinde açık (ve istenirse tüm) olayların damgasını taşı. Örnek:
`UPDATE AlertEvent e SET e.teamId=:to WHERE e.teamId=:from AND e.resolved=false`, `@Modifying` + çağıran
`@Transactional` (moveAll zaten öyle; `RepositoryWriteTransactionGuardTest` listesine ekle). `notificationGroupId`
damgası taşınan gruba işaret ettiği için o da tutarlı kalır. Ek olarak `impact.empty` açık damgalı alarmları da
saymalı (ya da deleteTeam açık alarm varken 409 vermeli). Savunma katmanı: gönderim anında damgalı takım yoksa
(`teamRepo.findById` boş) sertifika/türev olaylarda canlı envanter takımına düş ve WARN yaz. Test: "moveAll + açık
alarm → sonraki re-alert `to`'ya gider; `from` silinince alarm susmaz".

---

## ORTA

### O1 — Takımı boş bağımsız DNS izlemesinin DNS_CHANGED alarmı host'un envanterindeki BAŞKA takıma gidiyor

`service/SchedulerService.java:5363` + `service/MonitoringOutageService.java:898-915` (`changeCtx`) +
`service/EscalationService.java:1139-1153` — **DNS_CHANGED bağlamı, diğer altı Port/DNS bağlamının aksine
`standalone: true` işaretini taşımıyor.**

**Neden bug:** `SchedulerService` diğer sweep kalemlerinde `if (standalone) ctx.put("standalone", true)` yazıyor
(:2863, :3109, :3304, :5327, :5343, :5390). DNS değişikliği ise `DnsChange` kaydıyla geçiyor
(`teamId = alarmTeamOf(...)`) ve `changeCtx` yalnız `team_id != null` iken damga koyuyor. `DnsChange` kaydında
standalone alanı hiç yok. Takımı NULL olan bağımsız DNS satırında (veri anomalisi; PROD_DIAGNOSIS §8 bunu sayıyor)
bağlam boş geliyor. `isStandalone(...)` false dönüyor ve `inventoryRepo.findByDomain(domain)` çalışıyor. Host başka
bir takımın envanterindeyse alarm o takımın SY + UG adreslerine ve KENDİ eskalasyon kişilerine (müdürü dâhil)
gidiyor. Olay o takımla damgalanıyor. Bağlam anlık görüntüsünde de işaret olmadığından günlük yeniden uyarı
(`reconstructChangeCtx`), çözüm, tekrar bildir ve 7/24 aynı yabancı takıma gidiyor. DNS_CHANGED kendiliğinden
kapanmadığı için bu süresiz sürebiliyor. Bu, kural (2)'nin ("sahipsiz → bildirim yok; başka takımın kişisi ASLA")
doğrudan ihlali. `EscalationContactLeakTest.java:378` açılış bağlamına `"standalone": true` koyuyor, ama üretici
taraf bunu hiç yazmıyor. Fikstür gerçek tel biçiminde olmadığı için test açığı gizliyor.

**Çözüm:** `DnsChange`'e `Boolean standalone` alanı ekle (SchedulerService :5362'de `m.getStandalone()`) ve
`changeCtx` içinde `if (TRUE.equals(c.standalone())) ctx.put("standalone", true)` yaz. Testte bağlamı gerçek
`changeCtx` çıktısıyla kur. Takımı null, standalone=true DNS değişikliği için "bildirim yok" testi ekle.

### O2 — Bu sürümden ÖNCE açılmış envanter türevli Port/DNS alarmları eski ("bağımsız") yönlendirmede kalıyor

`service/EscalationService.java:1198-1208, 1655-1666, 483-490` + `:2740-2755` (`isStandaloneEvent` anlık
görüntüden okuyor) + `StormService.java:653-666` — **Geçiş (migration) boşluğu.**

**Neden bug:** ≤20.89.0'da türev Port/DNS satırı takımı bağlama `team_id` olarak damgalıyordu (satırdaki kopya). Bu
damga `context_json` anlık görüntüsünde duruyor ve `event.teamId` = o kopya. Yükseltmeden sonra açık kalan bu
olaylar:
- `isStandaloneEvent == true` sayılıyor: UG hiç eklenmiyor, UYARI'da kişi yok.
- Takım damgadan okunuyor (O5/E10).

Kopya envanterden sapmışsa (PROD_DIAGNOSIS §8, "içe aktarmada türev satır eşitlenmiyordu") açık alarmın yeniden
uyarısı, eskalasyonu, çözümü, tekrar bildirimi ve fırtınası ESKİ/yabancı takıma gitmeye devam ediyor. DNS_CHANGED
kendiliğinden kapanmadığı için bu süresiz. Yeni kural ("türev = envanter gibi SY+UG") yalnız yeni açılan alarmlarda
geçerli. PROD_DIAGNOSIS §11 bunu belirtmiyor.

**Çözüm:** Tek seferlik idempotent yama (`StandaloneMonitorDeletionBackfill` deseni). Açık PORT_*/DNS_* olaylarından
`standalone=true` bir satıra ait olmayanlar için `team_id`'yi canlı envanter SY'ye çek ve anlık görüntüden
`team_id`'yi çıkar. Ya da bu olayları "yeniden açılacak" diye sessizce kapat. Doğrulama sorgusu:
`SELECT ae.id, ae.alert_type, ae.team_id, ci.team_id inv_team FROM alert_events ae JOIN certificate_inventory ci ON lower(ci.domain)=lower(ae.domain) WHERE NOT ae.resolved AND (ae.alert_type LIKE 'PORT_%' OR ae.alert_type LIKE 'DNS_%') AND ae.context_json LIKE '%"team_id"%' AND NOT EXISTS (SELECT 1 FROM port_monitors p WHERE p.host=ae.domain AND p.standalone) AND NOT EXISTS (SELECT 1 FROM dns_monitors d WHERE d.domain=ae.domain AND d.standalone)`.
Etki prod verisine bağlı, doğrulanmalı.

### O3 — Kural (4) "olmayan takıma yazma 400" kardeş uçlarda eksik

`controller/AdminController.java:1413-1435` (transfer-ug), `:399-412` (addInventory), `:549-554` (PUT
updateInventory takım değişimi), `controller/MonitoringController.java:1024-1026` (`resolveWriteTeam`, dokuz
izleme türünün OLUŞTURMA yolu) — **`requireExistingTeam` yalnız transfer, toplu set-team, kişi yazma ve izleme
GÜNCELLEME yolunda var.**

**Neden bug:** Global yönetici bu dört uçta var olmayan (silinmiş/uydurma) takım kimliğiyle kayıt yazabiliyor
(doğrudan API ya da bayat form; hedef takım modal açıkken silinmiş olabilir). Kayıt "sahipli" görünüyor, çünkü
sahipsiz kapısı `teamId != null` diye geçiyor. Ama ne takım adresi ne kişi var, alarm sessizce kayboluyor ve
"Sahipsiz kayıt" WARN'ı da yazılmıyor. En sık düğmelerden biri olan PUT düzenlemesinde de global yönetici takımı
değiştirebiliyor ("transferInventory ile AYNI senkron" yorumu), ama varlık denetimi orada yok. `transfer-ug` UG'yi
kontrolsüz yazıyor. `AdminControllerTest.nonexistentTeam_rejectedEverywhere` adı kapsamı olduğundan geniş
gösteriyor.

**Çözüm:** Aynı yardımcıyı dört uca ekle: `transfer-ug`'de `newUgTeamId != null`, add/PUT'ta `item.getTeamId()`,
`resolveWriteTeam`'de global yönetici dalında `requested != null`. Hepsi yetki kapısından sonra kalsın. Kapsamlı
kullanıcı için bu uçlar zaten 403, keşif sızıntısı yok. Testi dört uçla genişlet.

---

## DÜŞÜK

### D1 — 2026-07-07 öncesi elle eklenmiş Port izlemeleri (standalone=NULL) artık envanter takımına yönleniyor (doğrulanmalı)

`service/SchedulerService.java:4606-4608` (`alarmTeamOf`), `:3304`, `:2863` — `Boolean.TRUE.equals(null)` false
olduğu için damga yazılmıyor.

**Neden bug:** `standalone` kolonu 2026-07-07'de ddl-auto ile, backfill yapılmadan eklendi. Öncesinde
`POST /port` ile takımlı, elle eklenmiş satırlar NULL kaldı. Bu satırların host'u envanterdeyse sweep'e giriyor
(:2832). Dün alarmları satırın takımına gidiyordu. Artık host'un envanter SY+UG'sine ve onların kişilerine gidiyor.
Takım farklıysa bu, bir takımın izlemesinin başka takıma yönlenmesi demek.

**Çözüm:** Yayın öncesi sayım:
`SELECT p.id,p.host,p.team_id,ci.team_id FROM port_monitors p LEFT JOIN certificate_inventory ci ON lower(ci.domain)=lower(p.host) WHERE p.standalone IS NULL AND p.team_id IS NOT NULL AND p.team_id IS DISTINCT FROM ci.team_id`
(DNS için de aynısı). Satır varsa elle `standalone=true` yap ya da tek seferlik yama yaz.

### D2 — Aynı (alan adı, tür) için bağımsız ve türev satır aynı olayı paylaşıyor (önceden var)

`service/EscalationService.java:1159, 1198-1208` + `controller/MonitoringController.java:2182` (bağımsız DNS,
türev satır varken de oluşturulabiliyor; tekil indeks `standalone IS NOT TRUE`).

**Neden bug:** Olay anahtarı `domain|alertType`. Önce bağımsız X açarsa envanter sahibi Y'nin türev alarmı yalnız
X'e gidiyor (Y susuyor). Önce türev açarsa X'in izlemesi Y + UG + Y'nin kişilerine gidiyor. Hangisinin önce açtığı
yönlendirmeyi belirliyor.

**Çözüm:** Olay anahtarına izleme kimliği ya da standalone ayrımı eklenmeli. Kısa vadede bağımsız + türev aynı hedef
için belgelenmeli.

### D3 — SY + UG birleşiminde aynı webhook URL'sine iki mesaj

`service/EscalationService.java:2182-2192`.

**Neden bug:** Açılış/eskalasyon/yeniden uyarı webhook döngüsü URL'ye göre tekilleştirmiyor. Çözüm yolu
(`sendResolutionWebhooks` :1814) ve fırtına (`putIfAbsent`) ise tekilleştiriyor. SY ve UG kişileri aynı Teams
kanalını gösteriyorsa (ya da aynı kişi iki takımda aynı webhook'la kayıtlıysa) açılış iki kez, çözüm bir kez
gidiyor. Ayrıca `notified_contacts` ve `contacts_queued` aynı kişiyi iki kez sayıyor.

**Çözüm:** Döngüde `Set<String> sentUrls` kullan. Tekrar eden URL'de `saveLog`'u "SKIPPED: aynı webhook" olarak yaz.

### D4 — SY aktarımından sonra UG yeniden uyarıyı alıyor, ÇÖZÜLDÜ'yü almıyor (önceden var; UG'nin kişileriyle görünür oldu)

`service/EscalationService.java:2705-2710` (`includeInventoryUgTeam`: `stamped == invTeamId` sezgisi) karşısında
`:279`, `:1152` (yeniden uyarı canlı UG kullanıyor).

**Neden bug:** Alarm açıkken SY aktarılırsa damga eski SY'de kalıyor (E10/O5). Yeniden uyarı ve eskalasyon canlı
UG'yi ve artık UG'nin KENDİ kişilerini ekliyor. Çözüm ve tekrar bildir ise damga ≠ envanter SY olduğu için olayı
"bağımsız" sayıp UG'yi düşürüyor.

**Çözüm:** Artık açık `standalone` işareti var. Sezgiyi (`stamped.equals(invTeamId)`) kaldır ya da yalnız işaretsiz
ESKİ olaylarda uygula. UG kararı iki yolda da aynı olmalı.

### D5 — Eski kuralı anlatan i18n anahtarları duruyor

`frontend/src/i18n/index.jsx:3866, 3915, 3925, 3929` (EN `16833, 16882, 16892, 16896`): `sim.fallbackGlobal`,
`wn.how3`, `wn.src.globalContact`, `wn.why.globalContact`.

**Neden bug:** Kodda artık kullanılmıyorlar. `wn.src.${source}` dinamik, ama `globalContact` kaynağı artık
üretilmiyor. Metinleri ise "takımsız (global) kişilere düşülür" diyor. Biri geri bağlarsa eski kural ekrana döner.
Kullanılmayan anahtar kapısı yok.

**Çözüm:** TR ve EN'den birlikte sil (parite testi korunur).

### D6 — Yeni simülatör metinlerinde doğal olmayan dil

`frontend/src/i18n/index.jsx:3940` / `16907` (`wn.ownerContactsNoneAtLevel`), `3939` / `16906`.

**Neden bug:** `{0}` = "Takım A (SY)" olduğunda TR "Takım A (SY) takımının…" ("takım" tekrarı), EN "None of
Team A (SY)’s escalation contacts…" (parantezden sonra iyelik) çıkıyor.

**Çözüm:** TR: "{0}: eskalasyon kişilerinden hiçbiri {1} alarmlarını almıyor…". EN: "None of the escalation
contacts for {0} receives {1} alerts…".

### D7 — Belge ve javadoc tutarsızlıkları

- `service/StormService.java:762-766`: "takımsızda YALNIZ takımsız kontaklar" yazıyor, ama `forLevel` null takımda
  boş dönüyor.
- `CLAUDE.md:109`: hâlâ `getContactsForLevel(level, teamId)` (2 argüman) ve "yalnız KRİTİK alan adı" diyor.
  2026-09-19 kararıyla `includeManagerContacts = !WARNING`. SY+UG "her sahip kendi kişisi" ve türev Port/DNS → SY+UG
  (`alarmTeamOf`) CLAUDE.md'de hiç yok.
- `EscalationServiceTest.java:116`: var olmayan `EscalationUnownedAndScopeTest`'e atıf yapıyor (adı
  `EscalationContactLeakTest`).
- `EscalationContactScope.java:31-32`: "önizleme sessizce kullanır" diyor, ama `previewReNotify` →
  `getContactsForLevel` WARN yazıyor.
- `frontend/src/assets/whitepaper.en.md`: TR'ye eklenen yeni kural paragrafı EN'de yok. §8.4 iki dilde de SY+UG ve
  türev Port/DNS kuralını anlatmıyor, eski "müdür yalnız KRİTİK alan adı" ifadesi duruyor. Kök `WHITEPAPER.md` TR
  kaynağıyla senkron (yalnız üretilmiş başlık farkı).

**Çözüm:** Metinleri güncelle. whitepaper değişirse `npm run gen:guide-pdf`.

### D8 — Test kalitesi açıkları

- `EscalationServiceTest.java:117-120`: varsayılan stub her alan adını OWNER=42 envanterine bağlıyor. OWNER'ın adresi
  ve kişisi (stub'lanmadıkça) yok. "Bağımsız yol envanteri okudu" türü takımlar arası yönlendirme regresyonları bu
  sınıfta görünmez; yalnız `EscalationContactLeakTest` yakalar.
- Üretici tarafta bağımsız Port/DNS bağlamının `team_id` + `standalone` taşıdığını pinleyen test yok.
  `SchedulerServiceTest` yalnız türev tarafın negatifini sınıyor. O1'i yakalayacak test de bu.
- `EscalationContactScopeTest` kaynak kapısı `contactRepo.findAll()`'ın bir alıcı yolunda kullanılmasını
  yakalamıyor (yalnız `findByActiveTrue…` ve seviye sorgularını tarıyor).

**Çözüm:** Tek bir ayrı-takımlı fikstür yardımcısı kur. SchedulerService için bağımsız ve türev bağlam sözleşmesini
pinle. Kapıya `findAll(` (izinli dosyalar dışında) ekle.

Olumlu: `EscalationContactLeakTest` sahte deposu DB'ye sadık (NULL `teamId` = IS NULL anlamı dâhil). `self` aynı
örnek olduğu için worker yarışı yok. Sabit tarih ya da saat dilimi tuzağı yok (`Instant.now()` göreli).

### D9 — İçe aktarmada bilinmeyen `ug_team` adı sessizce yok sayılıyor

`service/InventoryImportService.java:156, 245`.

**Neden bug:** `team` için `unknown_team` satır hatası var, `ug_team` için yok. Yazım hatalı UG adı "update/create"
olarak raporlanıyor ama UG yazılmıyor. Kullanıcı UG'nin atandığını sanıyor.

**Çözüm:** `r.containsKey("ug_team") && !isBlank(...) && ugTeamId == null` → `unknown_ug_team` hatası.

---

## Özet tablo

| # | Önem | Yer | Konu |
|---|---|---|---|
| Y1 | YÜKSEK | TeamAdminService:168-235 / :150, AdminController:2865 | moveAll açık alarm damgasını taşımıyor + silme kapısı açık alarmı saymıyor → açık alarmlar silinmiş takıma, sessizce kimseye |
| O1 | ORTA | SchedulerService:5363, MonitoringOutageService:898-915 | DNS_CHANGED bağlamında `standalone` işareti yok → takımsız bağımsız DNS alarmı başka takımın envanterine sızıyor |
| O2 | ORTA | EscalationService:1198-1208, 2740-2755 | ≤20.89.0'da açılmış türev Port/DNS alarmları eski damgalı yönlendirmede kalıyor (geçiş yaması yok) |
| O3 | ORTA | AdminController:1413/399/549, MonitoringController:1024 | "Olmayan takıma yazma 400" transfer-ug / add / PUT / izleme oluşturmada eksik |
| D1 | DÜŞÜK | SchedulerService:4606 | standalone=NULL eski elle eklenmiş Port satırları envanter takımına yönleniyor (doğrulanmalı) |
| D2 | DÜŞÜK | EscalationService:1159 | Bağımsız ve türev satır aynı olay anahtarını paylaşıyor (önceden var) |
| D3 | DÜŞÜK | EscalationService:2182 | SY+UG'de aynı webhook URL'sine çift mesaj |
| D4 | DÜŞÜK | EscalationService:2705 | SY aktarımı sonrası UG yeniden uyarıyı alıyor, ÇÖZÜLDÜ'yü almıyor (önceden var) |
| D5 | DÜŞÜK | i18n:3866/3915/3925/3929 | Eski "global" kuralı anlatan ölü anahtarlar |
| D6 | DÜŞÜK | i18n:3939-3940 / 16906-16907 | Simülatör metninde doğal olmayan TR/EN |
| D7 | DÜŞÜK | StormService:762, CLAUDE.md:109, whitepaper.en.md, … | Belge ve javadoc tutarsızlıkları |
| D8 | DÜŞÜK | EscalationServiceTest:117, SchedulerServiceTest, EscalationContactScopeTest | Test maskeleme ve eksik sözleşme testleri |
| D9 | DÜŞÜK | InventoryImportService:156 | Bilinmeyen `ug_team` adı sessizce yok sayılıyor |

Toplam: KRİTİK 0 · YÜKSEK 1 · ORTA 3 · DÜŞÜK 9.

## Kontrol listesi durumu

1. **Sızıntı kalıntısı — BULGU (O1, O2, D1, D2).** Temiz olanlar: e-posta, kişi webhook'u, kişi push'u (takım
   üyeliği), 7/24 (takım kapsamlı çağrı listesi; sahipsiz fırtına üyesi dışarıda), fırtına (her dağıtıma yalnız o
   takımın kişisi), haftalık raporlar (`findByTeamIdAndRole…`, takım varlıktan), çözüm, tekrar bildir ve önizleme
   (aynı `resolveReNotifyTargets`), simülatör (`forOwners`). Takım süzgeçsiz `EscalationContactRepository` okumaları
   yalnız yönetim listesi ve istatistiklerde. Bildirim grubu damgası `overrideFor` sahiplik denetiminden geçiyor.
   `resolveStampFromContext`'in envanter grubu yedeği yabancı grubu uygulamıyor. Bağımsız olaylarda
   `inventoryRepo.findByDomain` çözüm, tekrar bildir ve fırtınada atlanıyor.
   `RESOLVED_CONTEXT_KEYS` `team_id` + `standalone` taşıyor. Açık kalan boşluk DNS_CHANGED üreticisi (O1).
2. **Sessiz susma — BULGU (Y1, D1, D4).** Doğrulananlar:
   - Türev Port/DNS/ACCESSIBILITY bağlamında `team_id` yok → SY + UG + her takımın kişisi, aynı seviye kapıları.
   - Sertifika türleri takım adresi + seviye kişileri alıyor.
   - Sahipsiz kararı tam çözümlemeden sonra veriliyor (`sendCombinedAlert` / çözüm / `requireOwned`).
   - RESOLVE push (açılışta SENT olanlara) bozulmadı: sahipsiz olayın açılışı push'lanmadığı için erken dönüş etki
     etmiyor.
   - `includeManagerContacts` / `teamOnly` değişmedi.
3. **Tekilleştirme / webhook — KISMEN BULGU (D3).** Aynı kişi SY+UG'de tek e-posta alıyor, UG kaydının webhook'u
   korunuyor (`forOwners` kayıt düzeyinde tekil). E-posta karşılaştırması `trim` + küçük harf. Prod locale C.UTF-8
   olduğu için `toLowerCase()` güvenli. Açılış yolunda webhook URL tekilleştirmesi yok.
4. **Takım bütünlüğü — BULGU (Y1, O3, D9).** 400/409 kapıları yetki kapısından SONRA; kapsamlı kullanıcı için
   403/404 ayrımı yok, keşif sızıntısı yok. deleteTeam 409 gövdesi genel metin, ad sızdırmıyor, yalnız global
   yönetici. moveAll'da hedef zaten SY iken UG'nin boşa düşmesi doğru (alıcı kümesi değişmiyor, SY = hedef).
   `moveAll` `@Transactional`. Yeni `@Modifying` ya da türetilmiş `deleteBy…` yok. Yeni depo metodu salt okuma.
5. **Simülatör — TEMİZ (i18n notu D6).** `ugTeamId` kapsam dışıysa 404, hem SY hem UG için. Yanıt `owners` /
   `ug_team_id` / `team_contacts_*` snake_case. `g_ug` anahtarını `PAGE_STATE_PREFIXES`'teki `g_` kapsıyor. Ön
   yüzdeki takım listesi görüş kapsamına süzülü. MONITOR türünde UG yok sayılıyor (ön ve arka uç).
6. **Testler — BULGU (D8, O1 fikstürü).** Sahte depo DB'ye sadık. Mockito yeniden stub'ı yalnız senkron yollarda.
   Sabit tarih ya da saat dilimi tuzağı yok. OWNER'a taşınan stub'lar takımlar arası regresyonları bu sınıfta
   görünmez kılıyor.
7. **Belgeler — BULGU (D5, D7).** Kök `WHITEPAPER.md` ile `frontend/src/assets/whitepaper.md` senkron. Ölü
   anahtarların (`sim.fallbackGlobal`, `wn.how3`, `wn.src.globalContact`, `wn.why.globalContact`) kodda dinamik
   kullanımı yok. `wn.src.${source}` var, ama `globalContact` kaynağı üretilmiyor. EN whitepaper ve CLAUDE.md eksik
   ya da eski.
