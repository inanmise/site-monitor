# PROD KAPISI — v20.50.22 · 2026-09-08

> ## ⬛ 2026-09-09 — DÜZELTME TURU KOŞULDU, HÜKÜM **GO**'YA ÇEVRİLDİ
>
> Aşağıdaki NO-GO hükmü **2026-09-08 tarihli koda** aitti. Ertesi gün GO yolunun dokuz maddesi
> uygulandı ve tüm kapılar yeşile döndü:
>
> | Kapı | Önce | Sonra |
> |---|---|---|
> | G6 CVE (prod bağ.) | 🔴 1 kritik + 1 yüksek + 1 orta | 🟢 **`found 0 vulnerabilities`** |
> | G8 k6 duman | 🔴 eşik aşıldı (script kusuru) | 🟢 exit 0 · `http_req_failed 0.00%` · p95 17,3 ms |
> | G1 backend | 3406 test | 🟢 **3412** test |
> | G4 frontend | 193 dosya / 1758 test | 🟢 **194 / 1761** |
> | G7 E2E | 24 | 🟢 24 |
>
> **Kapatılanlar:** K1, K2, Y1, Y2, Y3, Y4, Y5, Y6, Y7, Y10, Y11, Y13, Y22, Y23 + O20 (alıcı
> adresleri artık loglanmıyor) ve E5#3 (SSL push'u "yanıt vermiyor" demiyor).
>
> **Bilerek AÇIK bırakılanlar** (tek pod'da sömürülemez, HA'ya çıkmadan kapatılmalı):
> Y8/Y9 denetim zinciri · Y12 `/metrics` · Y14 paylaşılan `JdbcTemplate` · Y15 `@Async`
> self-invocation · Y16 `k8s/` ↔ helm ayrışması · Y17 fail-open kilit · Y18 digest pinleme ·
> Y19 `ChainValidationService` floorDiv · Y20 push penceresi · Y21 `join` timeout · Y24 modaller
> ve ORTA listesinin tamamı. Bunlar bir sonraki turun kapsamıdır.
>
> **Operasyonel ön-koşullar hâlâ geçerli:** `.env` e-posta parolası rotasyonu · `secret.secretKey`
> artık **boş** olduğu için deploy komutuna `--set secret.secretKey=$SECRET_KEY` EKLENMELİ, yoksa
> prod profili bilinçli olarak açılmaz · `SYSTEM_ADMIN_EMAIL` kurum adresiyle tanımlanmalı.
>
> ---

> ## HÜKÜM (2026-09-08 kodu): **NO-GO**
>
> Bu sürüm bugünkü hâliyle üretime çıkmamalı. Gerekçe tek bir sebep değil, **doğrulanmış üç sınıf**:
>
> 1. **Bir mekanik kapı kırmızı** — üretim bağımlılıklarında 1 kritik + 1 yüksek + 1 orta CVE (G6).
> 2. **İki takım-izolasyonu açığı** — geri alınamaz `purge` uçlarında takım kapsamı hiç uygulanmıyor.
> 3. **Alarm zincirinde sessiz kayıplar** — webhook teslim hatası "SENT" olarak kaydediliyor, uyarı
>    seviyesindeki alarm hiçbir kanaldan gitmiyor, fırtına dalında çözüm push'u hiç atılmıyor.
>
> Bunların ortak özelliği şu: **ürünün kendi ekranı size "gitti" diyor.** Bir izleme ürününde
> yanlış yeşil, kırmızıdan tehlikelidir — çünkü kimse bakmaz.
>
> **GO'ya giden en kısa yol** için bkz. son bölüm. Tahmin: **2–3 gün** (7 madde).

**Kapsam:** Tam kapı (Faz 0–5). `duzelt` argümanı verilmediği için **hiçbir kod değiştirilmedi**;
bu belge rapor + hükümdür. Depo durumu kapı sonunda temiz (yalnız bu rapor + `logs/`).

**Yöntem notu:** 16 eksen paralel ajanla tarandı, ardından **her KRİTİK/YÜKSEK bulgu ana oturumda
kaynakta açılıp okundu**. Ajan çıktısı tek başına kanıt sayılmadı — nitekim iki iddia bu aşamada
daraltıldı (aşağıda "düzeltilen iddialar"). Doğrulanamayanlar DÜŞÜK'e indirildi ve öyle etiketlendi.

---

## 1. Mekanik kapılar

| Kapı | Sonuç | Kanıt |
|---|---|---|
| G1 backend derleme + test | 🟢 **3406 test**, 0 hata | taban 3402 → aşıldı |
| G2 kapsam (jacoco) | 🟢 tüm kurallar karşılandı | `All coverage checks have been met` |
| G3 frontend lint | 🟢 **0 hata** / 57 uyarı | taban 58 uyarı → düştü |
| G4 frontend test + taban | 🟢 **193 dosya / 1758 test**, floor OK | taban 1750 → aşıldı |
| G5 prod build | 🟡 başarılı; **chunk > 500 kB** | `index-*.js` 1376 kB + 1040 kB; sourcemap prod'da kapalı ✓ |
| G6 CVE (npm, prod bağ.) | 🔴 **KIRMIZI** | 1 kritik (`jspdf`) + 1 yüksek (`jspdf-autotable`) + 1 orta (`dompurify`) |
| G7 E2E (Playwright) | 🟢 **24/24** | taban 24 → korundu |
| G8 k6 duman | 🔴 **eşik aşıldı** — ama **script hatası**, uygulama değil | checks %100 (11628/0), p95 **17,84 ms** |
| G9 hijyen | 🟡 kökte 71 yetim `.log` + `_review_src.tar.gz` | izlenmiyor; temizlenmeli |
| G10 secret + rotasyon | 🔴 **NO-GO ön-koşulu** | `.env` e-posta parolası rotasyon kanıtı yok; ayrıca P3-1/P3-10 |
| G11 Docker | 🟡 non-root ✓, probe ✓; **base imajlar digest'siz** | `node:20-alpine` vb. etiketle |
| G12 sürüm paritesi | 🟢 VERSION = Chart appVersion = `20.50.22` | CHANGELOG borcu → P10 (kullanıcı kararı: bloklamaz) |

### G6 — kırmızının anatomisi (indirgeme YAPILMADI, ama doğru ölçüldü)

`jspdf ^2.5.2` → düzeltme **4.2.1**; `jspdf-autotable ^3.8.4` → **5.0.8**. İkisi de büyük sürüm
atlaması, yani yama değil **göç**. İstismar edilebilirliği ölçtüm:

- Kritik danışma maddelerinin **tamamı kullanılmayan yüzeylerde**: `addJS`, `AcroForm`,
  `createOption`, `FreeText`, `.html()` — hiçbiri çağrılmıyor. Tek `addImage` açıkça `'PNG'`
  (BMP/GIF çözücü DoS'ları devre dışı).
- **Ama** `dompurify` ve `html2canvas` üretim paketine **giriyor** (`purify.es-*.js`,
  `html2canvas.esm-*.js` ayrı chunk'lar) — tree-shake etmemiş. Bugün yüklenmiyorlar; yarın biri
  `.html()` çağırdığında sessizce etkinleşirler.

**Sonuç:** bugün *sömürülebilir değil*, ama **artefaktta zafiyetli sürüm sevk ediliyor** ve kapı
kuralı "KRİTİK/YÜKSEK yok ya da gerekçeli suppress" diyor. Bu depoda npm için suppress mekanizması
yok. Kırmızı olarak bırakıldı; kararı yönetim vermeli (bkz. GO yolu, madde 1).

### G8 — kapı bozuk, uygulama sağlam

`perf/k6-smoke.js:35` kendi yorumunda **"401 sağlıklı cevaptır"** diyor; `:21`'deki
`http_req_failed: rate<0.01` eşiği ise o 401'i hata sayıyor. Başarısız oran tam **%50**
(2907/2907) — yani her yinelemedeki iki istekten biri, kasıtlı yetkisiz probe. **Bu duman testi
yapısı gereği hiç geçemez.** Uygulama tarafı mükemmel: checks %100, p95 17,84 ms.

---

## 2. On altı eksen karnesi

| Eksen | Bulgu | Durum |
|---|---|---|
| E1 eşzamanlılık | 1 Y · 1 O · 1 D | 🟡 |
| E2a IDOR / takım izolasyonu | **2 Y** · 1 O | 🔴 |
| E2b SSRF / yetki / secret | 1 Y-O · 3 O · 4 D | 🟡 |
| E3 veri katmanı | **2 Y** · 1 O · 1 D | 🔴 |
| E4 sayısal / zaman | **3 Y** · 5 O · 2 D | 🔴 |
| E5 alarm / eskalasyon | **2 K · 3 Y** · 4 O · 1 D | 🔴 |
| E6 React | 1 O-Y · 3 O · 3 D | 🟡 |
| P1 performans | 6 Y-O · 3 O | 🟡 (ölçek senaryosu) |
| P2 dayanıklılık | 2 Y · 3 O · 3 D | 🟡 |
| P3 konfig / deploy | **4 Y** · 6 O · 2 D | 🔴 |
| P4 gözlemlenebilirlik | **2 Y** · 3 O · 3 D | 🔴 |
| P5 girdi / HTTP güvenlik | **3 Y** · 6 O · 3 D | 🔴 |
| P6 veri yaşam döngüsü | **2 Y** · 5 O · 3 D | 🔴 |
| P7 frontend hazırlık | **3 Y** · 5 O · 3 D | 🔴 |
| P8 test kalitesi | **3 Y** · 5 O · 3 D | 🔴 |
| P9/P10 tedarik + belge | **2 Y** · 6 O · 2 D | 🔴 |

---

## 3. Açık bulgular — önem sırasıyla

Aşağıdaki her madde **ana oturumda kaynakta açılıp okundu**. `[E]` mevcut kod · `[Y]` bu oturumda
dokunulan yeni kod · `[R]` regresyon.

### 🔴 KRİTİK

**K1 · Uyarı seviyesindeki alarm hiçbir push kanalından gitmiyor** `[Y]`
`UserPushService.java:769` · *bu oturumda kısmen düzeltildi, ailenin kalanı açık*
`{gun}` yalnız `ctx["days_remaining"]` okuyor. Domain üreticileri `domain_days_remaining`
(`SchedulerService:4146, 4262, 4343, 4425`), DOMAINMON ise `days` yazıyor.
**Düzeltilen iddia:** ajan "üç ailede de push/mail çelişiyor" dedi; ölçtüm — mail tarafı
(`EmailTemplateBuilder:377`) `days_remaining` → `days` alias'ını tanıyor ama
`domain_days_remaining`'i tanımıyor. Yani:
- `DOMAINMON_EXPIRY` → mail doğru, push `-` → **gerçek iki-kanal çelişkisi**
- `DOMAIN_EXPIRY` / `KEYWORD_DOMAIN_EXPIRY` → **her iki kanalda da** eksik (ortak boşluk)
**Çözüm:** alias zincirini `PushText`'e saf yardımcı olarak çıkar, mail ile tek kaynağa bağla.
**Test:** her `*EXPIRY` tipi için üreticinin gerçekten yazdığı anahtarla `buildMessage` çağırıp
`"- gün"` / `"(-)"` çıkmadığını iddia eden tablo testi.

**K2 · Alan adı alarmlarının e-posta konusu "Sertifika Süre Bitişi" diyor** `[E]`
`EscalationService.java:1729-1762` (aynısı `:1379`)
`typeTr` switch'inde `TYPE_DOMAINMON_TRANSFER_LOCK` ve `TYPE_DOMAINMON_BLACKLIST` **yok** (grep: 0),
`default` dalı → *"Sertifika Süre Bitişi"*. Gövde doğru (`EmailTemplateBuilder.heroLabel:177`
"ALAN ADI TRANSFER KİLİDİ"), yani **tek mailin konusu ile başlığı çelişiyor**; çözüm maili de
"— Sertifika Süre Bitişi sorunu giderildi" diyor.
**Test:** tüm alarm tiplerini gezip `default` etiketine düşen tip olmadığını iddia eden kapı.

### 🔴 YÜKSEK

**Y1 · `purge` uçlarında takım kapsamı hiç uygulanmıyor — geri alınamaz** `[E]`
`AdminController.java:900` (`DELETE /inventory/{id}/permanent`) ve `:920` (`POST /inventory/purge-deleted`)
Javadoc *"Yalnız GLOBAL ADMIN (`requireAdmin`)"* diyor; **`requireAdmin` hiç çağrılmıyor.**
Tek kapı `requirePerm(...)` → `PermissionService.require` (`:92`) **yalnız `systemRole` dizesine**
bakıyor; `adminDefaults()` (`PermissionCatalog:190`) ADMIN'e `for (Resource r : ALL) putAll(r, true)`
ile her şeyi veriyor. Ve kodun kendi yorumu (`UserService:365-373`) AD üzerinden gelen ADMIN'in
`viewTeamIds`'inin **null olmadığını**, dolayısıyla `isGlobalAdmin`'in **false** kaldığını yazıyor.
**Kardeş kanıtı:** aynı entity üzerinde `:216`, `:256`, `:722`, `:873`, `:787` → hepsi
`requireTeamScopedAdmin` / kayıt-başına `canManageTeamResource`. **Beş kardeş kapsam uyguluyor,
geri alınamaz olan ikisi uygulamıyor.**
Toplu uç daha ağır: id bilmeye gerek yok, tek istekle **tüm organizasyonun** çöp kutusu ve
sertifika geçmişi silinir; denetime yalnız toplam sayı yazılır, hangi domainler gittiği hiçbir
yerde kalmaz.
**Çözüm:** `findById` sonrası `requireTeamScopedAdmin(session, inv.getTeamId())`; toplu uçta
`bulkInventoryAction:767` deseni (kayıt başına filtre + `skipped` yanıtta + denetim ayrıntısına).

**Y2 · Webhook teslim hatası "SENT" olarak kaydediliyor** `[E]`
`WebhookService.java:88-102, 113-136` → `EscalationService.java:1806`
`sendTeams`/`sendSlack` **her istisnayı içeride yutup `void` dönüyor** → çağırandaki
`catch { webhookStatus = "FAILED" }` bloğu **erişilemez**. Üstelik `post()` `response.statusCode()`'u
**hiç kontrol etmiyor** (yalnız `log.debug`) → Slack 404/403 (silinmiş webhook) de başarı sayılıyor.
Sonuç: `notification_log.webhook_status` **koşulsuz "SENT"**.
**Bu doğrudan bu oturumda düzelttiğim ekrana bağlanıyor:** Bildirim Geçmişi'nde okunur hâle
getirdiğim durum rozeti, gönderilmemiş bir alarmı **yeşil SENT** gösteriyor.
**Kardeş kanıtı:** `UserPushService.sendBatch:476-498` durum aralığını doğru kontrol ediyor.
**Çözüm:** `post()` 2xx dışında fırlatsın; `sendTeams`/`sendSlack` durum döndürsün.

**Y3 · Webhook URL'i tam hâliyle WARN'a yazılıyor — URL'in kendisi gizli anahtar** `[E]`
`WebhookService.java:92, 100`
`log.warn("Teams webhook failed {}: {}", webhookUrl, ...)`. Slack/Teams incoming-webhook adresi
(`https://hooks.slack.com/services/T…/B…/<secret>`) **kimlik bilgisidir**. WARN prod'da açık,
30 gün saklanıyor, `kubectl logs` ile erişilebilir. Log okuyan herkes o kanala mesaj atabilir.
**Çözüm:** kontak kimliği logla, URL'i hiç basma (veya host + path-hash).

**Y4 · Fırtına dalında çözüm push'u hiç gitmiyor** `[E]`
`StormService.java:430-491` · `EscalationService.java:657-660`
Fırtına yolunda push kanalı yok; `SUPPRESSED` dalı `sendCombinedAlert`'i atladığı için
`triggerUserPush` de koşmuyor. Senaryo: A tek başına düşer → **OPEN push gider**; pencere içinde
B–E düşer, `linkPeers` A'yı da storm'a bağlar; A düzelir → bireysel çözüm bastırılır →
**RESOLVE push asla gitmez.** Telefondaki alarm sonsuza dek açık kalır.
İkizi: `sendStormRecovery` webhook döngüsü taşımıyor (`:468-491` vs `:452-457`) — Teams'te
"fırtına başladı" var, "bitti" yok.

**Y5 · Mail alıcısı yoksa kontak webhook'u da düşüyor** `[E]`
`EscalationService.java:1674-1683`
`mailDisabled || allEmails.isEmpty()` dalı erken `return` ediyor ve `:1804`'teki kontak-webhook
döngüsüne **hiç ulaşılmıyor**. Hemen üstündeki yorum *"KANAL BAĞIMSIZLIĞI: mailin atlanması
webhook'u DÜŞÜRMEZ"* diyor — ama oradaki "webhook" yalnız kişi-push'unu karşılıyor.
Sonuç: takım e-postası tanımsız + webhook-only kontaklar → alarm **hiçbir kanaldan** gitmiyor.

**Y6 · Bildirim grubu seçerek izleme oluşturmak 400 veriyor (4 tür)** `[E]`
`MonitoringController.java:1970` (keyword), `:2493` (http), `:4493` (domain), `:4807` (ping)
```java
m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), ...));  // m YENİ → teamId null
m.setTeamId(teamId);                                                         // BİR SATIR SONRA
```
`applyNotificationGroup:152` → `ownedByTeam = g != null && teamId != null && ...` → `teamId` null
olduğu için **daima false** → `:163` `throw new IllegalArgumentException("Secilen bildirim grubu bu
takima ait degil")` → 400. Mesaj üstelik gerçek dışı: grup kullanıcının kendi takımının.
**Kardeş kanıtı:** port `:1240`, dns, page, pagespeed, scripted → `setTeamId` **önce** çağrılıyor.
Beş tür çalışıyor, dördü kırık.
**Kullanıcı etkisi doğrulandı:** arayüz alanı gönderiyor (`DnsMonitorPage:254` deseni); boş
bırakılırsa `null` (sorunsuz), **seçilirse** 400.

**Y7 · Takım değişiminde bildirim grubu eski takıma göre doğrulanıyor** `[E]`
`MonitoringController.java:2015` + 8 yerde daha (9 update ucunun **tamamı**)
```java
m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), ...));  // ESKİ takım
if (body.containsKey("teamId")) m.setTeamId(resolveTeamChange(...));         // sonra taşınıyor
```
Tek istekte `{teamId: B, notificationGroupId: <A'nın grubu>}` → A'ya göre doğrulanır, sonra B'ye
taşınır. Sonuç: **B takımına ait ama A takımının nöbetçi listesine yönlendiren** monitör.
`applyNotificationGroup`'un kendi yorumu bunu yasaklamak için yazılmış. Form her kaydetmede grup
anahtarını gönderdiği için **kaza eseri de** tetiklenir.

**Y8 · Denetim zinciri ilk gece temizliğinden sonra kalıcı "kurcalama" diyor** `[E]`
`AuditService.java:180-199` + `RetentionCatalog.java:87`
`verifyChain()` `prev = GENESIS`'ten başlıyor; retention `audit_log`'un en eski satırlarını siliyor.
Purge sonrası hayatta kalan en eski satırın `prev_hash`'i **silinmiş bir satırı** gösteriyor →
daha ilk satırda `!prev.equals(storedPrev)` → `/api/audit/integrity` **kalıcı olarak `ok=false`,
`checked=0`**. Operatör alarmı yok saymayı öğrenince **gerçek kurcalama görünmez olur**.
Repo bilerek append-only (`AuditIntegrityTest.repository_isAppendOnly_noDeleteSurface`) ama
retention JdbcTemplate ile o kapıyı baypas ediyor.
**Çözüm:** purge'de silinen son satırın `row_hash`+`seq`'ini çapa olarak sakla; `verifyChain`
çapadan başlasın ve "seq N'den itibaren doğrulandı" desin.

**Y9 · `audit_log.seq` üzerinde UNIQUE yok — çok podda zincir çatallanır** `[E]`
`AuditService.java:113-121` · patch'lerde `ux_audit_seq` **yok** (grep: 0)
Zincir kilidi **JVM-yerel** `ReentrantLock`. İki pod aynı anda `findTopByOrderBySeqDesc()` okur →
aynı `seq` + aynı `prev_hash` ile iki satır. Proje başka yerde tam bu gerekçeyle UNIQUE index
koymuş (`ux_push_event_phase_user`) — burada atlanmış. Tek pod bugün korunuyor; HA'ya çıkış
planı varsa bu **açılış öncesi** kapatılmalı.

**Y10 · Helm `secretKey` yer tutucusu fail-fast'i geçiyor** `[E]`
`helm/site-monitor/values.yaml:163` = `"REPLACE_WITH_RANDOM_SECRET_KEY"` · `SecretCipher.java:65-73`
Fail-fast koşulu `insecure = material.equals(DEV_DEFAULT)`. Yer tutucu **boş değil ve DEV_DEFAULT
de değil** → kapı **tetiklenmiyor**. `master.yaml` `secretKey` set etmiyor, resmî deploy komutu
yalnız `adminPassword`/`dbPassword` `--set` ediyor. Sonuç: LDAP/SMTP secret'ları **git'te duran bir
anahtarla** şifrelenir; UI `secret_key_set: true` diyerek yöneticiyi yanıltır.
`values.yaml:161`'deki *"boş bırakılırsa dev anahtarına düşer"* yorumu da yanlış (prod'da boş =
açılışta durur — yani doğru davranış).
**Çözüm:** `secretKey: ""` yap (fail-fast çalışsın) + deploy komutuna `--set` ekle veya
`{{ required }}` guard'ı.

**Y11 · Kod varsayılanında gerçek kişisel e-posta** `[E]` — **kalıcı kuralın ihlali**
`application.properties:541`
`site.monitor.system-admin.email=${SYSTEM_ADMIN_EMAIL:<kişisel bir Gmail adresi>}`.
`SYSTEM_ADMIN_EMAIL` **hiçbir** configmap/values/env dosyasında tanımlı değil (yalnız iki yorum
satırında adı geçiyor). İki sonuç: (a) prod'da toplu ağ kesintisi ve başarısız-giriş anomali
uyarıları **kurum dışına, kişisel bir adrese** gider; (b) projenin kendi kuralı — kod, yorum, test
ve commit'te gerçek kimlik bilgisi bulunmaması — ihlal ediliyor ve **git geçmişi kalıcıdır**.
**Çözüm:** varsayılanı boş yap (boşsa gönderme), `SYSTEM_ADMIN_EMAIL`'i helm/configmap/.env.example'a ekle.

**Y12 · `/metrics` kimliksiz ve internete açık** `[E]`
`application.properties:670, 675-676` · `k8s/ingress.yaml` · helm'de NetworkPolicy şablonu **yok**
Actuator base-path `/`, `path-mapping.prometheus=metrics`; `AuthInterceptor:70` yalnız `/api/**`
koruyor. Ingress `path: / (Prefix)`. `application.properties:673`'teki *"NetworkPolicy ile yalnız
cluster'a açılır"* güvencesi **gerçekte yok** (k8s/networkpolicy.yaml'daki app-ingress kuralı
tamamen yorum satırı). Sızan: tüm uç envanteri + hata oranları + exception sınıf adları +
`db_table_rows{table=…}`.
*İyi haber:* `env`/`heapdump`/`threaddump` açık **değil**, health detayları `when-authorized`.

**Y13 · remember-me çerezinde `SameSite` yok — tek CSRF savunması delik** `[E]`
`AuthController.java:228-233`
Oturum çerezi prod'da `SameSite=strict` ve bu **tek** CSRF savunması. Ama remember-me çerezi
`new Cookie(...)` ile üretiliyor; `MaxAge`/`HttpOnly`/`Secure`/`Path` var, **`SameSite` yok**.
`AuthInterceptor` bu çerezle tam oturumu sessizce yeniden kuruyor. Origin/Referer kontrolü de yok.
Gövdesiz POST'lar düz HTML formuyla tetiklenebilir: `/inventory/purge-deleted` (bkz. Y1!),
`/users/{id}/unlock`, `/permissions/reset-to-defaults`, `/retention/run`.
**Y1 ile birleşince:** kurbanın tarayıcısından, tek formla, tüm organizasyonun çöp kutusu silinir.

**Y14 · Paylaşılan `JdbcTemplate` kalıcı olarak mutasyona uğratılıyor** `[E]` *(iki eksen bağımsız buldu)*
`SqlPlaygroundService.java:98`
`jdbcTemplate.setQueryTimeout(30)` — bu bean auto-configure edilmiş **singleton**, 12 servis aynı
örneği enjekte ediyor. Bir yönetici SQL Playground'u **bir kez** çalıştırdıktan sonra **JVM ömrü
boyunca** tüm JDBC yolu 30 sn timeout'a bağlanıyor. Gecelik retention'ın 10 dk bütçeli dilimli
DELETE'leri ve rollup'lar `QueryTimeoutException` alır — hepsi `catch → log.warn` olduğu için
**sessizce** veri temizliği yapılmamış olur.
**Çözüm:** Playground'a kendi `JdbcTemplate`'ini kur; paylaşılan bean'e dokunma.

**Y15 · `enrichGeoAsync` istek thread'inde senkron koşuyor** `[E]`
`AuditService.java:278, 306` → `:531` `@Async("certCheckExecutor")`
Aynı bean içinden çıplak `this.` çağrısı → proxy baypas → **@Async etkisiz**. Çağıran Tomcat
request thread'i. Sonuç: her login'de request thread'i dış GeoIP HTTP çağrısı **ve** timeout'suz
PTR sorgusu için bloke olur. `:306` yolu tam olarak **rate-limit'e takılan** istekleri kapsıyor —
yani brute-force anında her bloklanan deneme bir PTR + HTTP çağrısı doğuruyor.
**Kardeş kanıtı:** `EscalationService:70` bu tuzağı `@Lazy self` ile **zaten çözmüş**;
`LoginIssueMailService:29` kuralı yorumda yazmış. Burası deseni kaçıran tek yer.

**Y16 · `k8s/` manifestleri ile kod/helm ayrışmış — pod açılmaz** `[E]`
- `k8s/secret.example.yaml`'da `SITE_MONITOR_SECRET_KEY` **hiç yok**, ama `configmap.yaml`
  `SPRING_PROFILES_ACTIVE: "prod"` veriyor → açılışta `IllegalStateException` → CrashLoopBackOff.
- `configmap.yaml` `DB_NAME/DB_USER: certmonitor`, `postgres.yaml` `POSTGRES_DB/USER: sitemonitor`
  → `FATAL: role "certmonitor" does not exist`.
- `deployment.yaml` `readOnlyRootFilesystem: true` ama yalnız `/tmp` mount'u; prod profili
  `/var/log/site-monitor`'a yazıyor → **365 gün saklanması gereken denetim log'u hiç yazılmaz**
  (prod'da `AUDIT` logger'ı `additivity=false`, konsola düşmez). Helm bunu doğru yapıyor.
- `SMTP_USER`/`SMTP_PASSWORD`/`EMAIL_TO` anahtarlarını kod **hiç okumuyor** (kod
  `SPRING_MAIL_USERNAME` vb. bekliyor) → `smtp.auth=true` + boş kimlik → e-posta sessizce düşer.
**Karar gerekiyor:** helm otoritatif (CI yalnız onu doğruluyor), `k8s/` referans/legacy. Bu
açıkça yazılmalı ya da `k8s/` düzeltilmeli — bugün ikisi de yapılmamış.

**Y17 · Alarm hunisinin dağıtık kilidi fail-OPEN ve metin eşleşmesine dayalı** `[E]`
`MonitoringOutageService.java:801-822`
"Kilit başkasında" tespiti **istisna mesajına** bakıyor (`contains("UNIQUE")/"unique"/"duplicate"`)
— `SchedulerService`'te D2 ile terk edilen desenin kopyası. H2 (`MODE=PostgreSQL`) mesajı
*"Unique index or primary key violation"* üretir → üç kalıbın hiçbiri tutmaz. Eşleşmeyen her
istisna `acquired=false` yapıp **`action.run()`'ı yine de çalıştırıyor**. Geçici bir DB hatasında
iki replika aynı anda alarm açar → **çift mail + çift webhook + çift push**.
**Çözüm:** tipli `DuplicateKeyException` → return; genel `Exception` → **fail-closed**.

**Y18 · Docker base imajları digest'siz** `[E]`
`Dockerfile:4, 7, 16, 24` — `node:20-alpine`, `maven:3.9-…`, `eclipse-temurin:25-jre-alpine`.
Politika tek yönlü uygulanmış: **GitHub Action'lar SHA ile pinli**, üretim imajının tabanları değil.
Aynı commit'ten iki ay arayla build alınca farklı JRE/npm/musl gelir.
Ek: CI frontend'i **Node 24** ile test ediyor, imaj **Node 20** ile build ediyor → CI'ın yeşil
dediği bundle, sevk edilen bundle **değil**.

**Y19 · `ChainValidationService` gün hesabı tamsayı bölmesi** `[Y]` *(bu oturumda dokunulan dosya)*
`ChainValidationService.java:162`
`(notAfter - now) / 86_400_000L` — Java tamsayı bölmesi **sıfıra doğru** kırpar. `-1 < x < 0`
aralığında sonuç **0** olur, `:178`'deki `daysRemaining < 0` yanlış kalır.
Somut: 12 saat önce süresi dolmuş ara sertifika → `days_remaining: 0`, `expired: false`,
`chain_status: "VALID"` → **zincir raporunda sağlam görünür**.
**Kardeş kanıtı:** `CertificateCheckerService:939` ve `DomainCheckerService:307` `Math.floorDiv`
kullanıyor ve **yorumları tam bu tuzağı anlatıyor**. Üçüncü yer kuralı kaçırmış.

**Y20 · `UserPushController` zaman penceresi 3 saat kaymış** `[E]`
`UserPushController.java:174, 266`
`ISO.format(Instant.now().minus(...).atZone(Europe/Istanbul).toLocalDateTime())` — ama
`UserPushDelivery.createdAt` **UTC** yazılıyor (`UserPushService:75` `withZone(UTC)`, javadoc'u tam
bu asimetrinin daha önce düzeltildiğini anlatıyor). Denetleyici tarafı düzeltmenin dışında kalmış.
Sonuç: (a) "dakikada en çok 3 test" tavanı **hiç devreye girmiyor** — gerçek gönderim yapan uç
sınırsız tetiklenebilir; (b) `last24h` penceresi fiilen **21 saat**.

**Y21 · Sweep `join()`'lerinde timeout yok → kalıcı kilitlenme** `[E]`
`SchedulerService.java:1816-1819, 2223-2232`
`CompletableFuture::join` hiçbir yerde `orTimeout` taşımıyor. DNS çözümlemesi için JVM'de timeout
**yoktur** (`NetworkResolver:35`). Tek probe askıda kalırsa `running` bayrağı `finally`'de
sıfırlanmaz → **sonraki tüm sertifika sweep'leri** "Check already in progress" ile atlanır ve
kilit bırakılmaz. Kurtarma yalnız manuel `forceReleaseLock` veya pod restart.

**Y22 · İzin Matrisi ekranında ham anahtarlar görünüyor** `[E]`
`PermissionMatrix.jsx:223, 233`
Satırları backend `PermissionCatalog.ALL` besliyor, etiketler `t('perm.res.' + key)` /
`t('perm.group.' + group)` ile **dinamik** kuruluyor. Katalogdaki **11 resource + 2 grup** sözlükte
yok — örneklediğim dördünün dördü de 0 eşleşme (`perm.res.maintenance.view`,
`perm.group.maintenance`, `perm.res.settings.branding`, `perm.res.notification.groups`).
`useT` eksik anahtarda **anahtarın kendisini** döndürdüğü için ekranda `perm.res.maintenance.view`
yazıyor. İki tanesi **akordiyon başlığı** — en görünür yüzey.
**Neden testler yakalamadı:** `i18n-parity` yalnız statik anahtarları karşılaştırıyor;
`i18n-used-keys.test.jsx:11` dinamikleri **açıkça kapsam dışı** bırakıyor. Bu ailenin doğru
örneği depoda zaten var (`settings-labels-sync`, `change-kinds-sync`) — bu aileye uygulanmamış.
**Çözüm:** 13 anahtarı TR+EN'e ekle + `PermissionCatalog.java`'yı okuyup doluluğu zorlayan kapı.

**Y23 · Dört push testi SIFIR teslimatla yeşil** `[E]` — *sahte-yeşilin üçüncü örneği*
`UserPushServiceTest.java:405-417` (`nullSslContext_stillSends`), ayrıca `:356`, `:376`, `:536`
Testin **tek** iddiası `await().until(() -> store.stream().allMatch(d -> "SENT".equals(...)))`.
**Boş stream'de `allMatch` TRUE döner** → `store` hiç dolmasa bile ilk yoklamada geçer. Başka
assert yok. Üstelik `enqueueAlert` her istisnayı yutuyor (aynı dosyadaki
`enqueueSwallowsAllExceptions:444` bunu kanıtlıyor) → alıcı stub'ı ya da kuyruk kırılsa test
**yine yeşil**. Adı ise "gönderim yine yapılır". AssertJ `allSatisfy` de boş listede geçer (`:356`,
`:376`); `:536` "karar satırı yazılır" diyor ama hiç satır yazılmasa da geçiyor.
**Kardeş kanıtı:** aynı dosyada `notificationId_parsedIntoAllRows:341` doğrusunu yapıyor
(`hasSize(2)`).
Bu, oturum boyunca iki kez yakaladığım sınıfın **üçüncüsü**; kalıcı önlem `Strictness.LENIENT`
yasağı (63/78 test sınıfı bugün lenient — kullanılmayan stub uyarısını kapatıyor, yani bu arızanın
en doğal erken sinyalini susturuyor).

**Y24 · 30+ modal Escape'i dinlemiyor, odak tuzağı yok** `[E]`
`ui/ModalShell.jsx:33` yalnız **4** üretim bileşeninde kullanılıyor; docstring "~12 modal bilinçli
taşınmadı" diyor, gerçek sayı **30+**. Ham `.modal-overlay` kuran monitör CRUD formlarının
tamamı, `UserEditModal`, `PasswordChangeModal`, `InventoryFormModal` dâhil. Klavye kullanıcısı
formdan Tab ile arkadaki sayfaya düşüyor; `role="dialog"`/`aria-modal` da yok.

### 🟡 ORTA (özet — tam liste eksen çıktılarında)

| # | Konu | Yer |
|---|---|---|
| O1 | `/maintenance/active` takım süzmesi yapmıyor; kardeşi `list()` süzüyor | `MaintenanceController:123` |
| O2 | `getCatalogForClient` push başlıklarını maskesiz döndürüyor | `AppSettingsService:122` |
| O3 | `check-preview/{domain}` yetki kapısı ve SSRF doğrulaması yok | `CertificateController:210` |
| O4 | Haftalık istatistikte `up<total` iken %100 gösterimi (3 kardeş koruma taşıyor) | `MonitoringWeeklyStatsService:294` |
| O5 | Halka açık durum sayfası aynı hatayı yapıyor (%100 + 3 olay) | `PublicStatsController:70` |
| O6 | `resolveRange` yalnız `to`'yu normalize ediyor; `normalizeFrom` **ölü kod** | `MonitoringController:2283` |
| O7 | `PING_SLOW` `isStandaloneMon`'da yok → global kontaklara mail | `EscalationService:2206` |
| O8 | Storm üyesinin seviye terfisi kalıcılaşmıyor (`return` terfiden önce) | `EscalationService:913` |
| O9 | `patch()` her istisnayı `log.debug`'a yutuyor; audit "uygulandı" diyor | `SchedulerService:1293` |
| O10 | `timeoutMs` clamp'siz → `Integer.MAX_VALUE` ile havuz tükenir | `MonitoringController:1244` |
| O11 | CSP `script-src 'unsafe-inline'` — CSP'nin XSS değerini iptal ediyor | `WebConfig:156` |
| O12 | `/api/client-error-report` gövde limiti yok (kimliksiz uç) | `WebConfig:124` |
| O13 | `processResults` kayıt-başına try/catch yok → tek zehirli kayıt turu düşürür | `EscalationService:244` |
| O14 | Yönetim panellerinde yarış guard'ı yok (5 ekran) | `AuditLogViewer:336` vd. |
| O15 | `nightly-cleanup` kilidi 60 dk, en kötü iş ~140 dk, yenileme yok | `SchedulerService:1418` |
| O16 | Retention `rationale` metni hâlâ "JSONL arşivi yazılır" diyor (kod kaldırıldı) | `RetentionCatalog:89` |
| O17 | Kullanıcı numaralandırma: bilinmeyen kullanıcıda BCrypt atlanıyor (tempo farkı) | `UserService:124` |
| O18 | Ingress `proxy-body-size: 1m` ↔ uygulama 8–12 MB sözleşmesi | `k8s/ingress.yaml:10` |
| O19 | 5 monitör türünde mükerrer engeli check-then-act, UNIQUE yok | `MonitoringController:2482` |
| O20 | Alıcı e-postaları INFO seviyesinde düz metin loglanıyor | `EscalationService:1826` vd. |

### ⚪ Ölçek bulguları (bugünkü 340 monitörde sorun değil, büyümede duvar)

`P1` ekseni "10 takım × 5k monitör" senaryosunu ölçtü. Bugün üretim ~340 monitör, dolayısıyla
bunlar **yayın engeli değil**; ama HA/ölçek planı varsa yol haritasına girmeli:
liste uçlarında sayfalama yok (tüm tablo + Java'da süzme) · `IN :domains` sınırsız (PostgreSQL
65535 bind sınırı) · öksüz temizliği 5 dakikada 8× tam tablo taraması · dört büyük retention
kuralı batch'siz · sweep kilidi 2 dk ama sweep dakikalarca sürüyor (kira yenilenmiyor) ·
`GET /api/monitoring/port` okuma yolunda **yazma** yapıyor.

---

## 4. Doğru bulunanlar — tarandı, temiz

Kapsam görünürlüğü ve yanlış-pozitif üretilmediğinin kanıtı:

- **Yönlendirme politikası:** 7 giden istemcinin **tamamı** `Redirect.NEVER` + elle `SafeRedirect`
  döngüsü. `Redirect.NORMAL` kullanan **hiçbir** canlı istemci kalmamış.
- **Yetki matrisi:** `auditDefaults()` içinde hiçbir `EDIT`/`EXECUTE` `true` değil; `upsertGrant`
  ADMIN geri-almayı reddediyor; `mustChangePassword` beyaz listesi dar ve tam-yol eşleşmeli.
- **IDOR:** 163 yol-değişkenli uçtan 160'ı kapsamlı — `MonitoringController`'ın 13 history +
  4 series + 3 uptime ucu, `IncidentController` transfer + görsel, `WeeklyReportService` dörtlüsü.
- **`SecretCipher`:** AES-256-GCM, çağrı başına rastgele IV, prod'da fail-fast, hiç loglamıyor.
- **ReDoS:** kullanıcı deseni `Pattern.compile`'a hiç girmiyor; `PageCheckerService:426`
  `Pattern.quote` ile kaçırıyor.
- **Frontend XSS:** `dangerouslySetInnerHTML` tek kullanım (sayısal geri sayım); `rehype-raw` yok.
- **Sayfalama sınırları:** `page`/`size` her yerde clamp'li; `size=1000000` sömürülemiyor.
- **Metrik kardinalitesi:** eşleşmeyen istekte ham URI'ye düşülmüyor (`"(unmatched)"` + dakikada
  500 endpoint tavanı).
- **Şema patch kapsamı:** Ağustos–Eylül'de eklenen **tüm** `@Column` alanlarının `ADD COLUMN`
  karşılığı mevcut (`mixed_content_note` dâhil — bu oturumda eklenen).
- **JSONL denetim arşivi geri gelmemiş** — `RetentionNoFileArchiveTest` kapısı ayakta.
- **Lisans/bağımlılık:** lock'ta GPL/AGPL yok; kullanılmayan bağımlılık yok; dış CDN çağrısı yok
  (fontlar self-host) — kapalı kurum ağında kırılmaz.
- **Retention belgesi** `RetentionCatalog`'dan **üretiliyor** ve `RetentionDocTest` sapmayı
  yakalıyor → "belgede 90, kodda 30" sınıfı yapısal olarak kapatılmış.

---

## 5. Yayın kontrol listesi

**Öncesinde**
- [ ] `.env` e-posta uygulama parolası **rotasyonu** + kanıt (G10 ön-koşulu)
- [ ] `secretKey` gerçek değerle set (Y10) ve `secret_key_set` UI'da doğrulanmış
- [ ] `SYSTEM_ADMIN_EMAIL` kurum adresiyle tanımlı (Y11)
- [ ] TEST dağıtımına `NO_PROXY` verilmesi
- [ ] `userpush.pipeline` değerinin teyidi
- [ ] Kökteki 71 yetim `.log` + `_review_src.tar.gz` temizliği (G9)

**Sırasında**
- [ ] `helm upgrade` **düz koşulmayacak** — pod values sürüklenmesi var (`dbPoolMax` 10 vs 30,
      `LOG_LEVEL=DEBUG` bilinçliyse geri alınır)
- [ ] Açılışta `/health` UP **ve** log'da şema-patch hatası yok
- [ ] `/api/audit/integrity` sonucu kaydedilsin (Y8 düzeltilmeden **ok=false beklenir**)

**Sonrasında**
- [ ] Bir test alarmı aç → **e-posta, webhook ve push'un üçü de** ulaştı mı (Y2/Y4/Y5)
- [ ] Bildirim Geçmişi'ndeki `SENT` rozeti gerçeği yansıtıyor mu

**Geri alma**
- [ ] Önceki imaj etiketi hazır; şema patch'leri **geri alınamaz** (ADD COLUMN ileri-uyumlu,
      rollback'te yeni kolonlar boş kalır — veri kaybı yok)

---

## 6. NO-GO'dan GO'ya en kısa yol

Sırasıyla; her madde bağımsız test edilebilir.

| # | İş | Süre | Kapatır |
|---|---|---|---|
| 1 | **G6 kararı**: `jspdf` 4.2.1 + `autotable` 5.0.8 göçü (2 dosya, 8 `autoTable` çağrısı) *veya* yönetim onaylı gerekçeli istisna | 0,5–1 gün | G6 |
| 2 | İki `purge` ucuna `requireTeamScopedAdmin` + toplu uca kayıt-başına filtre | 1 saat | Y1 |
| 3 | `post()` durum kodu kontrolü + `sendTeams/sendSlack` durum döndürsün; URL'i loglama | 1 saat | Y2, Y3 |
| 4 | `applyNotificationGroup` çağrılarında `setTeamId` sırası (4 create + 9 update) | 1 saat | Y6, Y7 |
| 5 | Mail alıcısızken webhook döngüsüne devam + fırtına RESOLVE push'u | 3 saat | Y4, Y5 |
| 6 | `{gun}` alias zinciri + `typeTr`'e iki DOMAINMON tipi | 1 saat | K1, K2 |
| 7 | `secretKey` fail-fast'i (`""`) + `SYSTEM_ADMIN_EMAIL` + remember-me `SameSite` | 2 saat | Y10, Y11, Y13 |
| 8 | 13 eksik `perm.*` anahtarı + katalog senkron kapısı | 1 saat | Y22 |
| 9 | 4 vakum push testine boyut kapısı (`isNotEmpty`/`hasSize`) | 30 dk | Y23 |

**Toplam ~2–3 gün.** Y8/Y9 (denetim zinciri) ve Y12 (`/metrics`) tek-pod'da bugün sömürülemez;
**HA'ya çıkmadan önce** kapatılmalı, bu sürümü bloklamaz.

---

## 7. Sertleştirme yol haritası

**Kısa (1 hafta)** — `k8s/` ↔ helm ayrışması kararı (Y16) · `patch()` sessizliği (O9) ·
`JdbcTemplate` mutasyonu (Y14) · `@Async` self-invocation (Y15) · CSP `unsafe-inline` (O11) ·
kimliksiz uçta gövde limiti (O12).

**Orta (1 ay)** — `/metrics` ayrı porta (Y12) · CSRF Origin filtresi · denetim zinciri çapası +
`ux_audit_seq` (Y8, Y9) · sweep `join` timeout'ları + watchdog (Y21) · yüzde hesabı için tek
`AvailabilityMath` yardımcısı (O4, O5) · yönetim panellerine yarış guard'ı (O14).

**Uzun (çeyrek)** — SBOM + imaj digest pinleme + Node sürüm birliği (Y18) · liste uçlarında SQL
düzeyinde kapsam + sayfalama (P1) · `docs/RUNBOOK.md` (alarm fırtınası, DB dolması, SMTP düşmesi,
kilitli admin, rollback) · DTO + `@Valid` göçü (O-listesi) · 30+ modalin `ModalShell`'e taşınması
(Y24) · `Strictness.LENIENT` yasağı ve dinamik i18n anahtarları için jenerik kapı (Y23, Y22).

### Kalıcı ders — bu kapının en çok tekrar eden bulgusu

Yirmi dört YÜKSEK bulgunun **on ikisi** aynı biçimde ortaya çıktı: *kardeş yüzeylerin çoğu doğru
yapıyor, biri ya da ikisi kaçırmış.* Beş envanter ucu takım kapsamı uyguluyor, geri alınamaz iki
purge ucu uygulamıyor. Beş monitör türü `setTeamId`'yi önce çağırıyor, dördü sonra. İki servis
`Math.floorDiv` kullanıp yorumunda gerekçesini yazıyor, üçüncüsü düz bölme yapıyor.
`EscalationService` `@Lazy self` ile `@Async` tuzağını çözmüş, `AuditService` çözmemiş. Yedi giden
istemci `Redirect.NEVER` kullanıyor — bu sefer **hepsi** kullanıyordu, çünkü o sınıf geçen tur
tek kapıyla kapatılmıştı.

Buradan çıkan kural: **bir düzeltme örneği kapatır, sınıfı kapatmaz.** Her düzeltmenin yanına o
kalıbı kaynakta tarayan tek bir kapı testi konmadıkça, aynı kusur kardeş yüzeyde yaşamaya devam
ediyor — ve derleme de testler de yeşil kalıyor.

---

## 8. Kapının kendi doğrulaması

- Her mekanik kapının çıktısı `logs/prod-kapisi-2026-09-08/` altında; tablo o loglara atıf yapıyor.
  "Koşulmadı" yazan hiçbir kapı "geçti" sayılmadı.
- Sayılar taban değerlerden **düşmedi**: backend 3406 ≥ 3402 · frontend 1758 ≥ 1750 · e2e 24 = 24 ·
  lint 0 hata · kapsam tabanları yeşil.
- Rapordaki **her KRİTİK ve YÜKSEK** bulgunun `dosya:satır`'ı ana oturumda açılıp okundu; ajan
  çıktısı tek başına kanıt sayılmadı.
- **İki ajan iddiası doğrulamada daraltıldı:** (a) push/mail gün alias'ının üç ailede de çeliştiği
  iddiası → yalnız `DOMAINMON_EXPIRY`'de çelişki, diğer ikisinde ortak boşluk; (b) G6'nın doğrudan
  sömürülebilir olduğu ihtimali → kritik yüzeyler çağrılmıyor, ama zafiyetli kod paketleniyor.
- Yanlış-pozitif listesindeki hiçbir madde bulgu olarak raporlanmadı.
- **Kod değişmedi** — `duzelt` argümanı verilmemişti.
- Bu raporda gerçek kişi/kurum adı geçmiyor (Y11'de adres kasten yazılmadı, yalnız yeri gösterildi).
