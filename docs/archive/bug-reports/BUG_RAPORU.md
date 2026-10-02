# SiteMonitor — Kod Seviyesi Bug Raporu

**Tarih:** 2026-08-27 · **Kapsam:** `backend/src` (550 Java) + `frontend/src` (360 JS/JSX), uçtan uca
**Yöntem:** 6 eksende paralel derin inceleme (eşzamanlılık · tarih/hesap · güvenlik/IDOR · veri katmanı · React · alarm mantığı), ardından her YÜKSEK bulgunun kaynak üzerinde elle doğrulanması.

> **Genel değerlendirme:** Kod tabanı olağanüstü sertleştirilmiş — FD sızıntısı, executor kapatma, bellek tavanı, transaction guard, IDOR (`denyIfNotViewable`), seri/chart savunması gibi sınıfların çoğu geçmiş denetimlerle kapatılmış ve yorumlarla belgelenmiş. Bu yüzden **sistemik/kritik açık yok**; bulgular tekil sapmalar ve sınır hataları. En ciddi grup, birkaç uçta **atlanmış takım-izolasyon kontrolü (IDOR)**. Aşağıdakilerin hepsi dosya:satır kanıtıyla doğrulandı; "doğrulanmalı" etiketliler ajan bulgusu olup elle teyit edilmedi.

Önem eşiği: **KRİTİK** (veri kaybı/RCE/yetki bypass — yok) · **YÜKSEK** (izolasyon ihlali, yanlış/eksik alarm) · **ORTA** (yanlış gösterim, dar yarış) · **DÜŞÜK** (latent, kozmetik, perf).

---

## YÜKSEK

### Y1 — `GET /dns/{id}/details` yetki ve takım kontrolü TAMAMEN yok (IDOR)
`backend/.../controller/MonitoringController.java:1695`

Metodun imzasında `HttpSession session` parametresi bile yok; ne `permissionService.require` ne `SessionScope.canView` var. `dnsMonitorRepo.findById(id)` sonucu doğrudan dönüyor. Oturumu olan **herhangi** bir kullanıcı, `id`'yi artırarak başka takımın DNS monitör yapılandırmasını (domain, teamId, beklenen kayıt değeri), üstelik her çağrıda **canlı DNS sorgusu** + resolver yapılandırmasını okuyabilir. Tüm kardeş uçlar (`/dns/{id}/history`, `/response-series`, `/check`) yetki taşırken bu uç çıplak.

**Çözüm:** imzaya `HttpSession session` ekle; `findById` sonrası, `enrichedQuery` öncesi:
```java
permissionService.require(session, "monitoring.read", "view");
var deny = denyIfNotViewable(session, m.getTeamId());
if (deny != null) return deny;
```

### Y2 — `GET /domain/{id}/registration` takım kapsamı yok + `live=true` ile çapraz-takım yazma/alarm
`backend/.../controller/MonitoringController.java:4386`

`permissionService.require(session, "domain.registration.view", "view")` var ama kaynağın takımına `canView` **yok**. Bu izin USER ve TEAM_ADMIN'e varsayılan açık. A takımındaki bir USER `id` deneyerek B takımının WHOIS/RDAP kayıt verisini okur; dahası `?live=true` ile **başka takımın monitörü üzerinde** canlı dış sorgu tetikler, sonuç DB'ye persist edilir ve `evaluateDomainAlarmsNow` ile **alarm değerlendirmesi** koşar — çapraz-takım yan etki. Kardeş `domain/{id}/history` (`canView`) ve `/check` (`canOperateTeam`) korumalı.

**Çözüm:** `findById` sonrası `if (!SessionScope.canView(session, m.getTeamId())) return forbidden(...)`; `live=true` dalı için `canOperateTeam` iste (yazma + alarm tetiklediği için).

### Y3 — `port` ve `dns` response-series uçları takım kontrolünü atlıyor (IDOR)
`backend/.../controller/MonitoringController.java:2077` (port), `:2088` (dns)

Bu iki uç yalnız `existsById(id)` kontrol edip takım kapsamını atlıyor. Diğer **7** response-series ucunun tamamı (keyword:2049, ping:2063, http, page, pagespeed, scripted, ssl) monitörü yükleyip `denyIfNotViewable(session, mon.getTeamId())` (`// IDOR (H3)` yorumlu) uyguluyor — bu ikisi desenden sapmış. `monitoring.read` USER'a açık olduğundan A takımındaki USER, `id` enumere ederek başka takımın port/DNS yanıt-süresi serisini (avg/min/max/p95/count) okur; ayrıca `existsById`'nin 404/200 farkı bir **id-enumerasyon oracle**'ı verir.

**Çözüm:** iki uçta da `existsById` yerine `findById(...).orElse(null)` + `denyIfNotViewable(session, mon.getTeamId())` (kardeş uçlarla birebir aynı desen).

### Y4 — Fırtına (storm) alıcı çözümü sayfa/sentetik/sayfa-hızı tiplerini yanlışlıkla müdürlere gönderiyor
`backend/.../service/StormService.java:576` (`isTeamOnly`)

`isTeamOnly` yalnız `KEYWORD`/`PING_DOWN`/`HTTP_DOWN`'ı team-only sayıyor. Ama `PAGE_DOWN`, `PAGE_INTEGRITY`, `SCRIPTED_FAIL/SLOW`, `PAGESPEED_DOWN/SLOW` de `DOWN_ALERT_TYPES` içinde (storm'a girer — kod yorumları 330–334 bunu doğruluyor) ve bireysel yolda (`EscalationService:817` `isStandaloneMon && !includeManagerContacts`) **team-only**dir. Storm dağıtımında (`resolveRecipients:501`) bu tipler için `isTeamOnly=false` görülür → `contactsForLevel(...)` ile **eskalasyon kontakları (müdürler)** eklenir ve `inventoryRepo.findByDomain` ile alakasız bir `ugTeamId` de alıcıya katılır. Sonuç: geniş bir kesintide sayfa/sentetik/sayfa-hızı monitörleri storm'a terfi edince, bireysel alarmda **asla** mail almayacak müdürlere toplu alarm + toplu "düzeldi" maili gider.

**Çözüm:** `StormService.isTeamOnly`'yi `EscalationService`'in kararıyla hizala — page/scripted/pagespeed tiplerini ekle; en temizi `isStandaloneMon(alertType) && !includeManagerContacts(alertType, level)` mantığını paylaşmak (kopyalamak yerine `EscalationService`'ten expose et).

### Y5 — Eskalasyonda seviye terfisi kalıcılaştırılmıyor → müdür, terfi ettiği kritik alarmın "çözüldü"sünü alamıyor
`backend/.../service/EscalationService.java:854` (re-alert dalı) vs `:1233` (çözüm)

Günlük re-alert dalı seviyeyi **canlı ctx'ten** okuyor: bir `DOMAINMON_EXPIRY` alarmı WARNING'de açılır; gün geçtikçe `days ≤ crit` olunca re-alert `alertLevel=CRITICAL` ile `getContactsForLevel(CRITICAL,…)` çağırıp **müdürü de ekler**. Ancak `event.setAlertLevel(...)` hiç çağrılmaz — yalnız `setMessage`. Domain yenilenip alarm otomatik kapanınca `sendResolutionNotification` `includeManagerContacts(type, event.getAlertLevel()=WARNING)` → **false** döner. Yani **kritik re-alert alan müdür "çözüldü" bildirimini almaz**; çözüm maili seviyeyi de yanlış (ORTA) gösterir ve manuel "Tekrar Bildir" bayat seviyeyle alıcı çözer.

**Çözüm:** re-alert dalında ctx seviyesi event'inkinden yüksekse `event.setAlertLevel(alertLevel)` ile kalıcılaştır (bu, `processResults`'taki mevcut escalation davranışının izleme-yolundaki eşleniği). Böylece çözüm/re-notify aynı seviyeden alıcı çözer.

---

## ORTA

### O1 — Sertifika sayaç/liste tutarsızlığı ve sınır hatası (dashboard yanlış gösterir)
`backend/.../service/CertificateService.java:637–646`

İki ayrı kusur: (a) `criticalCount` için `if (d <= critDays)` **alt sınırı yok** → süresi dolmuş (d<0) sertifikalar hem `critical_count`'a hem `expired`'a sayılır (çift sayım), ama drill-down `criticalDomains` `d >= 0 && d <= critDays` ile bunları dışlar → "kritik: 1" görünür ama liste boş. (b) `expiring30` için `d > 0`, `expiring7` için `d >= 0` — sınır tutarsız; bugün dolan (d==0, tamsayı kırpmasıyla çok yaygın) bir sertifika "7 gün içinde"ye girer ama "30 gün içinde"ye girmez → 30-gün sayısı 7-gün sayısından küçük olabilir (mantıksal imkânsız görünüm).

**Çözüm:** `criticalCount`'a `d >= 0` alt sınırı ekle (dolmuşlar yalnız `expired`'a düşsün); `expiring30`'u `d >= 0 && d <= 30` yaparak `expiring7` ile aynı sınıra getir.

### O2 — `PageMonitorPage.loadIssues` art-arda fetch yarışı (yanlış liste gösterimi)
`frontend/src/components/PageMonitorPage.jsx:145`

`loadIssues` sıra/abort guard'ı olmadan `setIssues(rows)` yapıyor ve üç eşzamanlı çağıranı var: filtre tıklaması, `checkNow`, 30 sn'lik sessiz `refreshModal`. Yavaş 'all' yanıtı, kullanıcının sonradan seçtiği 'SLOW' sonucunu ezip çip 'SLOW' iken listeyi 'all' gösterebilir. Kardeş `PageSpeedMonitorPage` tam bu yüzden `resSeq` guard'ı eklemiş (kod yorumlu) — bu sayfada yok.

**Çözüm:** `useRef(0)` ile `issuesSeq`; yanıt gelince `if (seq !== issuesSeq.current) return` (PageSpeed'deki desenin aynısı). `loadConfirmations` da yararlanır.

### O3 — `CertificateModal` SSL canlı probe'unda domain guard'ı yok (bayat veri farklı domaine yazılır)
`frontend/src/components/CertificateModal.jsx:400`

Efekt `api.checkDomainPreview(domain)` sonucunu domain kimliği kontrol etmeden `setSslData` yapıyor. Modal kalıcı mount'lu, yalnız `domain` prop'u değişiyor. Kullanıcı A sertifikasını açıp (yavaş TLS başlar) kapatıp B'yi açarsa, A'nın geç dönen probe'u B modalinde A'nın SSL verisini gösterebilir.

**Çözüm:** efekte `let alive = true; return () => { alive = false }` + istek başına `if (reqDomain !== domain) return`. Aynı sınıf `:387` geçmiş fetch'inde de var (guard + `Array.isArray(res.data)` ile birlikte).

### O4 — `LoginActivityChart` dış veriyi ts-string güvencesi olmadan işliyor (SystemHealth ekranı çökebilir)
`frontend/src/components/admin/LoginActivityChart.jsx:9,44`

`tickLabel(ts)` içinde `ts.endsWith('Z')` var; `buckets.map(b => ({ ts: b.ts, ... }))` her kovayı doğrudan besliyor. Bir kova `ts`'i null/number gelirse `ts.endsWith is not a function` fırlar ve tüm SystemHealth ekranı ErrorBoundary'ye düşer. Bu, projenin kendi kuralının ihlali: `ResponseTimeChart:137` `typeof s?.ts === 'string'` ile korunuyor (2026-08 scripted çökme dersi), diğer chart'lar da öyle — yalnız bu bileşen korumasız.

**Çözüm:** `buckets.filter(b => typeof b?.ts === 'string' && b.ts).map(...)` veya `tickLabel` başına `if (typeof ts !== 'string') return ''`.

### O5 — Re-alert canlı takım kullanırken çözüm damgalı takım kullanıyor (yeniden atamada tutarsız)
`backend/.../service/EscalationService.java:806` (re-alert `domainTeamId` canlı ctx) vs `:1235` (çözüm `event.getTeamId()`)

Standalone bir monitör alarmı açıkken başka takıma atanırsa: sonraki günlük re-alert **yeni** takıma (canlı ctx.team_id), çözüm bildirimi **eski** takıma (event.teamId) gider. Bilinen "üç yol da event.getTeamId() ile aynı takımı çözmeli" deseninin ihlali.

**Çözüm:** re-alert dalında da standalone tipler için takımı `event.getTeamId()`'den çöz; canlı ctx.team_id yalnız ilk açılışta.

### O6 — `AlertEvent.acknowledged` korumasız Boolean unbox → sweep iptali riski
`backend/.../service/EscalationService.java:291, 854`

`!event.getAcknowledged()` — `acknowledged` nullable Boolean. Kolon eski satırlar dururken eklendiyse (backfill patch'i yok) açık alarmlarda NULL kalabilir; unbox → NPE `runCheckForDomains` dış catch'ine kadar fırlar ve o sweep'te **kalan tüm domain'lerin** alarm işlemesini iptal eder. Kodun geri kalanı aynı alanda tutarlı biçimde null-güvenli `Boolean.TRUE.equals(...)` kullanıyor (ör. `MonitoringOutageService:387`).

**Çözüm:** iki satırda da `!Boolean.TRUE.equals(event.getAcknowledged())`.

### O7 — `ExpiryForecastPage` tarih parse'ı UTC/yerel karışımı (takvimde bir gün kayma)
`frontend/src/pages/ExpiryForecastPage.jsx:64,118,250`

Backend `not_after`'ı `Z` eki olmadan UTC yazıyor; JS zone-eksiz datetime'ı **yerel** sayar. `computeForecast`/`computeChartData`/`CalendarHeatmap.byDate` ham `new Date(cert.not_after)` kullanıyor (uygulamanın geri kalanı bilinçli 'Z' ekliyor — `client.js toUtc`, `DomainRegistrationTab`). UTC gününün son 3 saatinde (İstanbul UTC+3) dolan sertifikalar ısı-haritası/bar/liste'de **bir gün erken** görünür.

**Çözüm:** ortak `parseApiDate` yardımcısına çıkar (zone-eksizse `+ 'Z'`), üç noktada da kullan.

### O8 — `GET /users/{id}/photo` çapraz-takım okuma
`backend/.../controller/AdminController.java:1700`

Yalnız `requireAdminOrTeamAdmin(session)` var; hedef kullanıcının çağıranın takımında olup olmadığı denetlenmiyor. Bir takım yöneticisi `id` deneyerek herhangi bir takımdaki kullanıcının profil fotoğrafını çeker. Aynı controller'ın liste uçları `viewScope` ile süzülürken bu uç tutarsız (düşük hassasiyetli veri ama izolasyon deliği).

**Çözüm:** global admin/AUDIT değilse hedefin `teamId`/`teamIds`'i çağıranın `viewScope`'unda mı kontrol et; değilse 404.

---

## DÜŞÜK

- **D1 — `pagespeed_checks` şema yaması yanlış tabloya yazıyor.** `SchedulerService.java:451–456` altı patch'i var olmayan `page_speed_checks`'e (fazladan alt çizgi) yazıyor; entity ve diğer tüm patch'ler `pagespeed_checks`. `patch()` istisnayı yutuyor → sessiz no-op. Bugün `ddl-auto=update` maskeliyor ama güvenlik ağı kırık. **Çözüm:** 6 satırda tablo adını düzelt.
- **D2 — `tryAcquireSchedulerLock` geçici hatada fail-open.** `SchedulerService.java:~1835`: catch yalnız "unique" mesajında `false` dönüyor; başka her istisnada (deadlock, statement-timeout) `true` → çok-pod'da geçici DB hatasında çift sweep. **Çözüm:** `e instanceof DuplicateKeyException` yakala; kilit-tablosu-yok dışındaki hatalarda güvenli tarafta `false` dön. *(doğrulanmalı)*
- **D3 — `HttpClient.close()` kilit altında.** `HttpCheckerService.java:72`: LRU tahliyesinde `close()` `synchronized(pinnedClients)` içinde; JDK21 close() uçuşan istekleri bekler → lookup'lar bloklanır. **Çözüm:** referansı kilit dışına çıkarıp close()'u ayrı çağır. *(doğrulanmalı)*
- **D4 — `CaAutoPinService.hostLocks.clear()` yarışı.** `:111`: 10k+ ayrık host'ta clear, tutulan bir kilidi geçersizler → aynı host için karşılıklı dışlama kaybı (DB unique yine korur). **Çözüm:** clear yerine boyut-sınırlı LRU. *(doğrulanmalı)*
- **D5 — `days_remaining` sıfıra kırpma off-by-one.** `CertificateCheckerService.java:876`, `DomainCheckerService.java:305`: 12 saat önce dolmuş → `0` (negatif değil) → ilk ~24 saat "expired" değil "0 gün kaldı/kritik" görünür (yine kritik alarm var). **Çözüm:** `Math.floorDiv` veya `notAfter.isBefore(now)` ile açık expired tespiti; iki yolu tek yardımcıya birleştir.
- **D6 — `WeeklyReportKpiService.java:226,281` `≤7` alt sınırsız.** Çoktan dolmuş domainler de "bu hafta kritik" KPI'sına girer. **Çözüm:** `dd >= 0 && dd <= 7`. *(doğrulanmalı)*
- **D7 — `DOMAINMON_EXPIRY` metni "YÜKSEK" derken rozet "ORTA".** `EscalationService.java:1096,1889`: WARNING seviyesinde gövde "YÜKSEK" yazıyor, rozet "ORTA" → aynı mailde çelişki. **Çözüm:** metni üç kollu yap (`severityLabel(alertLevel)`).
- **D8 — Takım e-postası dedupe'unda trim yok, kontakta var.** `EscalationService.java:~1494` `e.toLowerCase()` vs `c.getEmail().trim().toLowerCase()`. Şu an zararsız (kaynaklar trimliyor) ama latent çift-mail riski. **Çözüm:** takım tarafında da `trim().toLowerCase()`. *(doğrulanmalı)*
- **D9 — Otomatik çözüm yolu manuel resolve'a karşı serileştirilmemiş.** `EscalationService.java:~595`: recovery sweep ile elle `resolve()` çakışırsa iki "çözüldü" maili + resolvedBy üzerine yazma. **Çözüm:** auto döngüde save öncesi `if (Boolean.TRUE.equals(event.getResolved())) continue;`. *(doğrulanmalı)*
- **D10 — `UserDirectory`/`UserActivityService` `findAll()` `photoBase64` (TEXT) yüklüyor.** Sık çağrılan dizin ucu her kullanıcının base64 fotoğrafını persistence context'e çeker. **Çözüm:** JPQL projeksiyon (`id, username, displayName, email`). *(doğrulanmalı)*
- **D11 — HTTP/Page/Keyword/Domain/Scripted/PageSpeed create'inde DB unique kısıt yok.** Yalnız uygulama-düzeyi `existsDuplicate` check-then-act; port/DNS'te DB unique var. Eşzamanlı çift POST → mükerrer izleme. **Çözüm:** koşullu unique index veya `DataIntegrityViolationException` yakala. *(doğrulanmalı)*
- **D12 — Detay modallarında fetch guard'ı eksik.** `DnsDetailModal.jsx:60`, `MonitorNotes.jsx:33`, `CertificateModal.jsx:387` — `alive`/seq guard'sız; pencere dar (modal seçim başına kapanır) ama standarttan sapma. **Çözüm:** `alive` guard'ı ekle. *(doğrulanmalı)*
- **D13 — Tanılama uçları çapraz-takım (muhtemelen bilinçli).** `AdminController.java:~2269` (openssl/network/hsts/domain-expiry): global-viewer değilse yalnız "envanterde kayıtlı mı" bakılıyor, takım değil. Yorum bunu SSRF-önleme amaçlı bilinçli tasarım diyor; takım izolasyonu isteniyorsa `canView(inv.getTeamId())` eklenmeli. *(doğrulanmalı — muhtemelen tasarım)*

---

## Önerilen düzeltme sırası

1. **Y1–Y3 (IDOR üçlüsü)** — küçük, mekanik, yüksek etki; hepsi mevcut `denyIfNotViewable` deseniyle tek oturumda kapanır. Her biri için bir cross-team 403/404 testi ekleyin (kardeş uçların testleri şablon).
2. **Y4, Y5 + O5, O6** — alarm/eskalasyon doğruluğu; `EscalationServiceTest`/`StormServiceTest`'e senaryo testleri (terfi→çözüm müdür alıcısı; storm page/scripted alıcı kümesi; yeniden-atama takım tutarlılığı; acknowledged NULL) yazılmadan dokunulmamalı.
3. **O1 (sertifika sayaçları)** — kullanıcı-görünür yanlışlık; tek fonksiyon, sınır kuralını tek biçime getir + sayaç/liste eşitliğini pinleyen test.
4. **O2–O4, O7 (frontend)** — mevcut `resSeq`/`alive`/ts-guard desenlerinin eksik kaldığı yerlere taşınması; düşük risk.
5. **DÜŞÜK grubu** — fırsat buldukça; D1 (pagespeed_checks) ve D2 (fail-open kilit) sıradaki dokunuşta ucuz kazanç.

**Doğrulama disiplini:** Bu bulguların YÜKSEK olanları ve O1/O6 kaynak üzerinde satır satır teyit edildi. "doğrulanmalı" etiketli DÜŞÜK maddeler ajan taramasından geldi ve düzeltmeden önce ilgili çağrı zinciri bir kez daha okunmalı. Her düzeltme, ilgili mevcut testleri **değiştirmeden** yeni test ekleyerek yapılmalı (proje regresyon disiplini).
