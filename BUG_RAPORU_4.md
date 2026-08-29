# SiteMonitor — Kod Bug Denetimi (4. Tur · v20.42.2)

**Tarih:** 2026-08-29 · **Yöntem:** `/bug-denetle` tek turda 6 eksen paralel + kaynakta doğrulama + baseline re-check
**İlişki:** Önceki üç raporun (`BUG_RAPORU.md`, `_2`, `_3`) baseline'ı bu turda **re-check** edildi.

## 1. Baseline durumu — TAMAMI ÇÖZÜLMÜŞ ✓

Önceki üç turun **9 YÜKSEK ve tüm ORTA** bulgusunun her biri kaynakta doğrulandı ve **kapatılmış**:

| # | Bulgu | Durum (kaynak kanıtı) |
|---|---|---|
| Y1 | `/dns/{id}/details` IDOR | ✓ `session` + `require` + `denyIfNotViewable` (MonitoringController:1696) |
| Y2 | `/domain/{id}/registration` IDOR | ✓ `SessionScope.canView` eklendi |
| Y3 | port/dns response-series IDOR | ✓ `denyIfNotViewable` (2084+) |
| Y4 | storm alıcı aşırı-erişimi | ✓ `isTeamOnly` kaldırıldı → `teamOnlyRecipients` tek doğruluk kaynağı |
| Y5 | eskalasyon seviye kalıcılaşmıyor | ✓ `event.setAlertLevel` (278, 886) |
| Y6 | AUDIT notification.groups EDIT | ✓ iki satıra bölündü (PermissionCatalog:41-42) |
| Y7 | gzip decompression bomb | ✓ `MAX_DECODED_BYTES` tavanı (PageFetchCore:303) |
| Y8 | keyword/http/hsts redirect SSRF | ✓ `Redirect.NEVER` + ortak **`SafeRedirect`** yardımcısı (önerilen desen) |
| Y9 | ChangeHistoryTab sayfalama | ✓ `page+1`/`p-1` (194, 200) |
| O9 | incident transfer kapsamı | ✓ kayıt-başına `requireIncidentWrite` + kısmi-başarı önlemesi |
| O12/O13 | webhook/uptime SsrfGuard | ✓ ikisinde de `ssrfGuard.validate` |
| O14 | UserPushService HttpClient sızıntısı | ✓ `volatile` singleton + `@PreDestroy` |
| O15 | SMTP oranı tamsayı bölmesi | ✓ `/10.0` (ExtendedHealthService:334) |
| O16 | DNS/Port stat kartları filtresiz | ✓ `scoped useMemo` |

Ayrıca ilk turdaki DÜŞÜK/frontend maddeleri (PageMonitorPage yarışı, CertificateModal SSL guard, LoginActivityChart ts-guard) de düzeltilmiş. **Önceki denetim eksiksiz uygulanmış — tebrikler.**

## 2. Yeni bulgular (güncel kodda)

Bu tur, düzeltmelerden bu yana eklenen **yeni koda** (kişi-bazlı push kanalı `UserPushService`, `SafeRedirect`, SQL Playground) odaklandı. Sistemik açık yine yok; bulgular yeni push/outbox makinesi ve salt-okunur sandbox'ta yoğunlaşıyor.

### YÜKSEK

**N1 — SQL Oyun Alanı `SELECT … INTO` ile salt-okunur zorlamasını atlıyor (sandbox gerçekten salt-okunur değil)**
`backend/.../service/SqlPlaygroundService.java:31` (FORBIDDEN) + `:126` (validateReadOnly) + `:83` (yürütme)

"Salt-okunur" güvencesi üç kontrolle sağlanıyor: SELECT/WITH ile başlama, tek statement, `FORBIDDEN` kelime kara-listesi. Ama `FORBIDDEN` kalıbında **`into` yok** (`insert|update|delete|drop|alter|truncate|create|…` var). PostgreSQL'de `SELECT * INTO yeni_tablo FROM app_users` **yeni tablo oluşturup veri kopyalar** — SELECT ile başlayan bir DDL+DML'dir. Üç kontrolü de geçer; `enforceLimit` sonuna `LIMIT 1000` ekler (geçerli Postgres); `jdbcTemplate.queryForList(capped)` ifadeyi çalıştırır → tablo **oluşur** (sonra "sonuç yok" istisnası düşse de yan etki kalıcıdır). Servis üzerinde `@Transactional(readOnly=true)` ya da `connection.setReadOnly(true)` **yok**, yani DB katmanı da durdurmuyor. (Önkoşul: admin + SQL-playground yetkisi — yani asıl risk "salt-okunur sandbox'ın güvence ihlali"/derinlemesine-savunma, yetkisiz erişim değil.)

**Çözüm (kesin):** sorguyu salt-okunur bağlantıda çalıştır — `execute`'a `@Transactional(readOnly=true)` **ve** `connection.setReadOnly(true)` (Postgres okurken yazmayı reddeder). Savunma derinliği için `FORBIDDEN`'a `\binto\b` ekle. Tek başına regex yetmez; kesin çözüm DB-seviyesi salt-okunur işlemdir.

### ORTA

**N2 — Push devre kesici açıkken kuyruğa giren bildirimler KALICI düşüyor**
`UserPushService.java:235-236, 300`

Devre kesici açıkken (`circuitOpen()`) enqueue edilen satır `status="CIRCUIT_OPEN"` yazılıyor; `drainOutbox` yalnız `findTop50…("PENDING")` çekiyor (300, 313) ve hiçbir yerde CIRCUIT_OPEN→PENDING dönüşümü yok. Zaten PENDING olan satırlar cooldown sonrası gönderilir (302-305) ama kesici açıkken **doğan** alarmların push'u API düzelse bile bir daha denenmez. 5 ardışık hata sonrası 5 dk'lık pencerede doğan push bildirimleri sessizce kalıcı kaybolur — sınıfın (javadoc:44) "kuyruktakiler BEKLER, kaybolmaz" vaadiyle çelişir. **Çözüm:** enqueue'da CIRCUIT_OPEN yerine PENDING yaz (drain'in kendi devre-açık bekletmesi cooldown sonrası devralır); veya cooldown bitince CIRCUIT_OPEN→PENDING çeviren yeniden-etkinleştirme ekle.

**N3 — Push outbox'ta açılış/periyodik drain yok → restart sonrası PENDING satırlar askıda kalıyor**
`UserPushService.java` (`@PostConstruct`/`@Scheduled`/`ApplicationReadyEvent` drain YOK)

`drainOutbox` yalnız enqueue (255, 287), devre-cooldown (303), tail (314), backoff (432) ile tetikleniyor — hepsi bellek-içi `worker`. Restart'ta planlı retry/cooldown drain'leri kaybolur; kalan PENDING (özellikle backoff bekleyen) satırlar **başka bir alarm enqueue olana dek** gönderilmez. Sakin bir kurulumda saatlerce askıda kalır. Veri kalıcı ama teslimat değil. **Çözüm:** `ApplicationReadyEvent` (veya kısa aralıklı `@Scheduled`) ile açılışta `worker.execute(this::drainOutbox)`.

**N4 — Push retry backoff'u tail yeniden-taramasıyla tamamen atlanıyor (çöken API'yi dövme)**
`UserPushService.java:313-314` + `:422, 432`

`sendBatch` retry-edilebilir hatada satırı `PENDING`'e döndürüp (422) `worker.schedule(drainOutbox, 30/120sn)` planlıyor (432). Ama aynı drain'in sonundaki tail (313) `if (!findTop50("PENDING").isEmpty()) worker.execute(drainOutbox)` ile **gecikmesiz** yeniden tarıyor; az önce PENDING'e dönen satır bu taramada bulunur → anında yeniden gönderilir → `attempts++` → retryMax'e kadar 0 gecikmeyle art arda. `retry-backoff-seconds` fiilen ölü; yavaş/çöken API tam-timeout isteklerle dövülür (yalnız devre kesici dıştan sınırlar). **Çözüm:** tail yeniden-taramasını "bu turda dokunulmamış YENİ PENDING var mı"ya bağla (işlenen id kümesini hariç tut), ya da başarısız-requeue'ları tail'in görmeyeceği bir ara duruma al ve yalnız `fail()`'in gecikmeli drain'i devralsın.

**N5 — SQL Oyun Alanı iç `LIMIT ≤ 1000` olduğunda satır tavanı devre dışı kalıyor (bellek/DoS)**
`SqlPlaygroundService.java:143` (enforceLimit)

`enforceLimit` yalnız ilk `LIMIT (\d+)` eşleşmesine bakıyor; değer ≤ 1000 ise sorguyu **olduğu gibi** dönüp dış tavan EKLEMİYOR. `SELECT * FROM big t1 CROSS JOIN big t2 WHERE t1.id IN (SELECT id FROM big LIMIT 5)` → regex `LIMIT 5`'i bulur (≤1000), dış kartezyen çarpım milyonlarca satır döndürür; `queryForList` hepsini belleğe alır → 30 sn timeout dolmadan OOM. **Çözüm:** LIMIT'i en dış sorguya uygula — `SELECT * FROM (<sorgu>) _capped LIMIT 1000` ile sar (tek statement + yazma engelli olduğundan güvenli); ek olarak `Statement.setMaxRows(1000)`.

**N6 — Kişi-webhook ayar ekranında teslimat günlüğü fetch yarışı**
`frontend/src/components/admin/UserPushSettings.jsx:249`

`fUser` (sicil) ve `fNotifId` metin filtreleri her tuşta `setPage(0)` + `loadDeliveries` tetikliyor; debounce/AbortController/sıra sayacı yok. Hızlı yazımda yavaş (eski) sorgu, hızlıdan sonra dönerse `setRows(...)` bayat listeyi yazar. **Çözüm:** `loadDeliveries`'e `seq` sayacı (yalnız en son isteği uygula) ya da metin filtrelerine ~300ms debounce (UserManager deseni).

### DÜŞÜK

- **N7 — `UserPushSettings.jsx:611` teslimat günlüğü "sayfa başına" seçicisi ölü kontrol.** `PaginationBar`'a `onPageSizeChange` verilmemiş, `pageSize` sabit 25; 50/100/200'e tıklama hiçbir şey yapmaz ama seçici görünür. **Çözüm:** `size`'ı state'e taşı, `onPageSizeChange={(n)=>{setSize(n);setPage(0)}}`.
- **N8 — `UserPushSettings.jsx:491` şablon alanı boşaltılamıyor.** `val(...) || defaults.templates[k] || ''` — `||` boş string'i düşürdüğünden kullanıcı şablonu silince kutu aniden varsayılana sıçrar. **Çözüm:** nullish ayrımı (`?? `), boş string'i koru.
- **N9 — `EscalationService.java:885` Y5 seviye terfisi "re-alert due değil" dalında kaydedilmiyor.** Seviye bump uygulanıyor ama `reAlertDue` false iken `save` çağrılmadığından bellek-içi kalıp kaybolur; dar pencerede araya giren çözüm müdürü atlayabilir (Y5'in alt-dalı). **Çözüm:** terfi uygulanınca (due olsun olmasın) `alertEventRepo.save(event)`. *(doğrulanmalı)*
- **N10 — `EscalationService.java:1533` push tetiği e-posta alıcısı yoksa hiç çalışmıyor.** `sendCombinedAlert`, `allEmails.isEmpty()` ise erken dönüyor; push tetiği (1684) sonra geldiğinden e-postası olmayan ama push-uygun üyeli takım hiçbir kanaldan bildirim almaz. K8 "mail neyi gönderiyorsa webhook da" sözleşmesi gereği **kasıtlı olabilir**. **Çözüm (kasıtlı değilse):** push tetiğini erken dönüşten önceye al (bastırma gate'leri üstte kaldığından parite korunur). *(doğrulanmalı)*
- **N11 — LDAP `role_mappings` + `default_role` ölü konfigürasyon.** `LdapSettingsService` bu alanları kaydedip UI'a döndürüyor ama `LdapProvisioningService.upsert` rolü sabit mantıkla (PO/müdür→TEAM_ADMIN, aksi USER) belirliyor; `roleMappings()`/`getDefaultRole()` çağıran yok. Admin "grup X → AUDIT" tanımlarsa sessizce yok sayılır. 2026-08 "LDAP kimseye ADMIN vermez" yeniden tasarımının bilinçli kalıntısı olabilir. **Çözüm:** ya alanları UI'dan kaldır (yanlış güven önlensin), ya da `applyRole`'de eşlemeyi gerçekten uygula. *(doğrulanmalı)*
- **N12 — `UserPushSettings.jsx:351` maskeli başlıkta `secret` toggle write-only sözleşmesini bozabilir.** Değiştirilmemiş maskeli değerde `secret` true→false yapılıp kaydedilirse maske dizesi düz metin olarak kalıcılaşabilir — sunucu koruma kararını yalnız `secret===true`'ya bağlıyorsa. **Sunucu tarafı doğrulanmalı.** **Çözüm (client savunma):** maskeli/değişmemiş değerde `secret` kapatılırsa değeri boşalt/yeniden-gir iste.

## 3. Doğru bulunan (kapsam görünürlüğü)

Bu tur özellikle tarandı ve **kusursuz** bulundu: `SafeRedirect` (4 çağıran — auto-follow kapalı, her hop `ssrfGuard.validate`, hop cap off-by-one'sız, Location null/şema-dışı → güvenli null, `isDowngrade` https→http koruması); `UserPushService.client()` (cacerts→CA bundle→host-pin TLS delegasyonu — doğrulama KAPATILMAMIŞ, hostname korunmuş); `sendBatch` yanıtı `readNBytes(64KB)` tavanlı; anti-loop `UNIQUE(alert_event_id, dedupe_key, username)` + ön-kontrol + catch; `teamOnlyRecipients` (storm ↔ bireysel aynı sonuç, Y4 regresyonsuz); `ProcessProbe` kabuk kullanmıyor (enjeksiyon yok); `SecretCipher` AES-GCM + rastgele IV; k6 secret'ları izole env + `SecretMask`; LDAP filtre değerleri `escapeFilter` (RFC 4515) ile kaçırılıyor; SQL `FORBIDDEN_FUNCTIONS` (pg_read_file/dblink/pg_sleep… engelli).

## 4. Önerilen düzeltme sırası

1. **N1** (SQL salt-okunur) — `@Transactional(readOnly=true)` + `setReadOnly(true)` + `into` kara-listesi; güvence ihlali, çözüm kesin ve ucuz.
2. **N2 + N3 + N4** (push outbox dayanıklılığı) — üçü aynı makinede; birlikte ele al: enqueue PENDING + açılış drain'i + backoff'a saygılı tail. Push kanalının "güvenilir teslimat" vaadini tamamlar.
3. **N5** (SQL satır tavanı) — dış-sarma + `setMaxRows`.
4. **N6** (fetch yarışı) + **N7/N8** (ayar UI) — ucuz frontend düzeltmeleri.
5. **N9–N12** — doğrulanmalı maddeler; düzeltmeden önce ilgili çağrı zinciri/sunucu tarafı bir kez daha okunmalı (N10/N11 tasarım kararı olabilir).

Her düzeltme mevcut testleri değiştirmeden yeni testle: N1 için `SELECT INTO` reddi + salt-okunur bağlantı; N2 için circuit-open sonrası cooldown teslimi; N4 için backoff aralığının korunduğu; N6 için son-istek-kazanır.
