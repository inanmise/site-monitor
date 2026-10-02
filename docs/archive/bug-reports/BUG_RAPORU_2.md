# SiteMonitor — Kod Seviyesi Bug Raporu (İkinci Tur)

**Tarih:** 2026-08-28 · **Kapsam:** `backend/src` — güvenlik/enjeksiyon/SSRF, kaynak sızıntısı, yetki uç durumları, ayrıştırıcılar
**İlişki:** Bu rapor `BUG_RAPORU.md`'ye (ilk tur: 5 YÜKSEK / 8 ORTA / 13 DÜŞÜK — IDOR üçlüsü, storm/eskalasyon, cert sayaçları) **EK**tir. İlk turun eksenlerini tekrar etmedim; bu tur **enjeksiyon/SSRF, kaynak sızıntısı, yetki mantığı ve ayrıştırıcı** eksenlerine odaklandı ve **yeni** bulgular çıkardı. Aşağıdaki YÜKSEK bulguların hepsi kaynak üzerinde satır satır doğrulandı.

> **Not:** İlk turdan bu yana `/webhook-bildirim` özelliği (kişi-bazlı push kanalı — `UserPushService`) fiilen uygulanmış; bu turun iki ORTA bulgusu (O14, D18) o yeni koda ait.

Önem eşiği aynı: **KRİTİK** · **YÜKSEK** · **ORTA** · **DÜŞÜK**. "doğrulanmalı" = ajan bulgusu, elle teyit edilmedi.

---

## YÜKSEK

### Y6 — Salt-okunur AUDIT rolü `notification.groups` üzerinde EDIT yetkisi kazanıyor (yetki yükseltme)
`backend/.../service/PermissionCatalog.java:36` (+ `auditDefaults()` :275-282)

`auditDefaults()` her kaynak için `isRead = r.actions.contains(VIEW) && !"settings".equals(group)` hesaplayıp `putAll` ile kaynağın **tüm** eylemlerine uyguluyor. `notification.groups` satır 36'da **tek çok-eylemli** `new Resource("notification.groups", "communication", List.of(VIEW, EDIT), ...)` tanımlı. VIEW içerdiği için `isRead=true` → `putAll` hem `view` hem **`edit`**'i AUDIT'e açar. AUDIT allow-list'inde de var (satır 232). Sonuç: `NotificationGroupController.create/update/delete` yalnız `require("notification.groups","edit")` + takım üyeliği istediğinden, **salt-okunur denetçi kendi takımının alarm alıcı listelerini oluşturabilir/değiştirebilir/silebilir** — denetim rolünün ihlali (alarmların kime gideceğini değiştirebilmek ciddi).

Bu **kodun kendi yorumuyla bilinen** bir tuzak: satır 73-76 aynen "tek satırda `List.of(VIEW, EDIT)` yazsaydık salt-okunur AUDIT sessizce DÜZENLEME kazanırdı" deyip `monitoring.group` (70-71) ve `scripted_templates` (77-80) kaynaklarını **iki ayrı satıra bölerek** düzeltmiş — ama `notification.groups` (36) gözden kaçmış.

**Çözüm:** satır 36'yı ikiye böl (diğer ikisiyle aynı desen):
```java
r("notification.groups", "communication", VIEW),
r("notification.groups", "communication", EDIT, Set.of(EDIT)),
```
Böylece AUDIT'te `view=true, edit=false` doğru hesaplanır; USER/TEAM_ADMIN etkilenmez (`allowed.contains(key)` iki satırı da açar). Regresyon testi: `defaultsFor("AUDIT").get("notification.groups").get("edit") == false`.

### Y7 — Sınırsız gzip/deflate açma → tek pod OOM (decompression bomb)
`backend/.../service/page/PageFetchCore.java:270-277`

`fetch()` telden gelen gövdeyi `is.readNBytes(MAX_BODY_BYTES)` = 2 MB ile kırpıyor (237) — **ama** `decode()` içinde `GZIPInputStream.readAllBytes()` (271) ve `InflaterInputStream.readAllBytes()` (277) açılmış çıktıyı **hiç sınırlamıyor**. DEFLATE ~1032:1 oranıyla 2 MB'lık bir bomb ≈ 2 GB'a açılır. Sayfa Bütünlüğü izlemesinin tüm gövde-isteyen yolları (`checkSinglePage`/`crawlSite`/`fetchRobotsDisallow`) bu decode'u tetikler. Sömürü: `monitoring.crud` yetkili kullanıcı bir Page monitörünü (veya "Test Et" ucunu) `Content-Encoding: gzip` bomb döndüren sunucuya yönlendirir → 100 kullanıcıya hizmet veren tek pod OOM ile düşer (tüm izleme durur).

**Çözüm:** açarken de tavan uygula — sabit tampon döngüsüyle oku, açılmış boyut `MAX_BODY_BYTES`'ı aşınca kes ve kaydı "gövde çok büyük" olarak işaretle:
```java
byte[] out = g.readNBytes(MAX_BODY_BYTES + 1);
if (out.length > MAX_BODY_BYTES) { /* truncated flag */ out = Arrays.copyOf(out, MAX_BODY_BYTES); }
```

### Y8 — Keyword izlemesinde yönlendirme zinciri yeniden doğrulanmıyor → bulut-metadata SSRF + içerik sızdırma
`backend/.../service/KeywordCheckerService.java:49, 97, 130-136`

Client `.followRedirects(HttpClient.Redirect.NORMAL)` (49) ile kurulu; `ssrfGuard.validate(host)` yalnız **ilk** URL host'unu doğruluyor (97). Redirect hedefleri doğrulanmadan izleniyor. Saldırgan `http://kötü/` için keyword monitörü kurar; sunucu `302 → http://169.254.169.254/latest/meta-data/iam/security-credentials/<rol>` döndürür; JDK client redirect'i izler, metadata gövdesini çeker; keyword eşleşirse `snippet` (≤200 karakter, 130-136) kullanıcıya **döner**. SsrfGuard'ın "her zaman blok" cloud-metadata koruması, hedef ilk hop olmadığı için baypas edilir; IMDSv1 düz GET'e yanıt verdiğinden IAM kimlik bilgileri sızabilir. `PageFetchCore` bunu doğru yapıyor (manuel redirect + her hop `validate`); Keyword/HTTP/HSTS checker'ları client-düzeyi `Redirect.NORMAL` ile bu korumayı deliyor.

**Çözüm:** `PageFetchCore` desenini uygula — `Redirect.NEVER` + manuel redirect takibi, her hop'ta `ssrfGuard.validate(host)` ve çözülen IP'ye pinleme. Bu düzeltme O10 (HTTP) ve O11 (HSTS) ile aynı kök nedeni kapatır — tek bir "güvenli redirect izleyici" yardımcısı üçünü de çözer.

---

## ORTA

### O9 — Olay (incident) transferinde kayıt-başına ve hedef-takım kapsam kontrolü yok
`backend/.../controller/IncidentController.java:178`

Olay defterinin tüm diğer değiştirici uçları (`update`:150-152, `delete`, `uploadImage`) `requireIncidentWrite(session, service.get(id))` ile **kayıt-başına** takım kapsamı doğruluyor. `transfer` (178) ise yalnız `requireManage(session)` (= `incidents.manage`, USER'da varsayılan açık) çağırıp `service.transfer(ids, teamId, ...)`'yi gelen id'ler ve **serbest hedef takım** ile çalıştırıyor; hiçbir kapsam kontrolü yok. Bir USER (a) kendi takımının olaylarını başka takıma taşıyıp defterinden düşürebilir, (b) göremediği takımların olaylarını (id tahminiyle) kendi takımına çekebilir. Yazma-tarafı nesne-yetki boşluğu.

**Çözüm:** `transfer` içinde her kaynak kayıt için `requireIncidentWrite(session, rec)` + hedef takım için `SessionScope.canManage`/üyelik doğrula; ya da `WeeklyReportService.transfer` gibi transferi ADMIN/TEAM_ADMIN'e kısıtla.

### O10 — HTTP checker yönlendirmede SSRF (durum-kodu/zamanlama oracle)
`backend/.../service/HttpCheckerService.java:100-103, 186-188`

`followRedirects=true` (Redirect.NORMAL) iken `ssrfBlockReason` yalnız ilk host'u doğruluyor; redirect hedefi (169.254.169.254 vb.) doğrulanmadan izleniyor. Gövde `discarding()` olduğundan yalnız HTTP status + süre sızar → iç servis/metadata için up/down + durum-kodu oracle'ı; "her zaman blok" metadata kuralı yine baypas. **Çözüm:** Y8'deki ortak güvenli-redirect yardımcısı.

### O11 — HSTS tanılamasında yönlendirme SSRF + tüm yanıt başlıklarını döndürme
`backend/.../service/HstsDiagnosticsService.java:92, 204-231`

`open(url, followRedirects=true)` + `TRUST_ALL`/`ALLOW_ALL`; hedef yalnız ilk host için `validateDiagTarget`'tan geçiyor. `setInstanceFollowRedirects(true)` (229) redirect'leri yeniden doğrulamadan izliyor ve `collectHeaders(hc)` sunucunun **tüm** yanıt başlıklarını kullanıcıya döndürüyor → iç uçların başlıkları sızabilir. `diagnostics.run` ile sınırlı ama metadata kuralını atlıyor. **Çözüm:** redirect'i kapat veya her hop'u SsrfGuard'dan geçir; başlık döndürmeyi güvenli host'la sınırla.

### O12 — Webhook URL'inde SSRF koruması yok (blind SSRF) + sınırsız yanıt okuma
`backend/.../service/WebhookService.java:90-100` + `controller/AdminController.java:1101`

`post()` kullanıcı-yapılandırmalı `webhookUrl`'e POST atıyor; `setWebhookUrl((String) body.get("webhook_url"))` URL'yi SsrfGuard/format doğrulaması **olmadan** saklıyor (NotificationGroup/EscalationContact yolları da). Config-yetkili kullanıcı webhook'u iç servise/metadata'ya yönlendirebilir (yanıt yalnız loglandığı için blind, zamanlama/hata ile oracle). Ayrıca `BodyHandlers.ofString()` yanıtı **sınırsız** okur → kötü yanıt OOM. **Çözüm:** kaydetmede + `post()`'ta host'u `ssrfGuard.validate`'ten geçir; yanıtı `MAX_BODY_BYTES` ile sınırla.

### O13 — Uptime checker'da SsrfGuard yok (iç TCP tarama oracle'ı)
`backend/.../service/UptimeHttpCheckerService.java:19`

Kardeş `PortCheckerService` bağlanmadan önce `ssrfGuard.validate` uygularken (loopback/link-local/metadata her zaman bloklu), Uptime doğrudan `NetworkResolver.connectFirstReachable(host, port)` çağırıyor — hiçbir doğrulama yok. `127.0.0.1`/`169.254.169.254`/iç IP için uptime kaydı kurulup up/down + süre ile iç port taraması yapılabilir. (Hedefler envanterden geldiği için istismar `inventory.crud` yetkisi ister — bu, Keyword'e göre etkiyi düşürür ama PortChecker'la tutarsızlık ve iç-tarama deliği gerçek.) **Çözüm:** bağlanmadan önce `ssrfGuard.validate(host)` + dönen IP'ye bağlan (PortChecker deseni).

### O14 — `UserPushService` her gönderimde yeni, kapatılmayan `HttpClient` üretiyor (thread/FD sızıntısı)
`backend/.../service/UserPushService.java:380` (çağıran `send()`:316, `drainOutbox()`:282)

`client()` her çağrıda `HttpClient.newBuilder()...build()` döndürüyor ve hiç `close()` edilmiyor. `drainOutbox()` tek boşaltmada her batch için `client()` çağırıyor; alarm fırtınasında (çok takım/grup) tek turda onlarca istemci doğar. Her JDK `HttpClient` kendi `SelectorManager` daemon thread'i + bağlantı havuzu + FD tutar — bu, kod tabanının başka her yerde bilinçle kapattığı desenin (bkz. `HttpCheckerService.java:62-65` "thread/FD/heap sızıntısıydı") tek istisnası. Burst ile GC arası selector thread'leri birikir.

**Çözüm:** tek `volatile HttpClient` alanını `@PostConstruct`'ta kur, tüm gönderimlerde paylaş, `@PreDestroy`'da `close()` et (istek timeout'u zaten `HttpRequest.timeout()` ile veriliyor).

---

## DÜŞÜK

- **D14 — `CertificateCheckerService.java:638` boş-IP dalında bağlantı hatasında SSLSocket kapatılmıyor.** `resolvedIps` boşken `factory.createSocket()` + `s.connect(...)`; connect fırlatırsa `close()` yok (çağırandaki `try(socket)` :386 hiç devreye girmez) → FD sızar. Hemen altındaki çok-IP döngüsü (650-657) hatada `close()` çağırıyor — asimetri açık kusur. Pencere dar (geçici DNS dalgalanması). **Çözüm:** boş-IP dalını da `try { s.connect(...); return s; } catch (IOException e) { s.close(); throw e; }` ile simetrik yap. *(doğrulanmalı)*
- **D15 — `WhoisParser.java:64` `dd/MM/yyyy` ↔ `MM/dd/yyyy` ayrımı yok → belirsiz slash-tarihte sessiz yanlış expiry.** `DATE_PATTERNS`'te `MM/dd/yyyy` yok; iki bileşeni de ≤12 olan bir registrar tarihi (`05/08/2026`) daima `dd/MM` yorumlanır → yanlış gün sayısı → süre alarmı erken/geç. Çoğu gTLD ISO kullandığından nadir. **Çözüm:** belirsiz slash formatını reddet (null=UNKNOWN, sessiz yanlıştan iyi) veya registrar-bazlı biçim. *(doğrulanmalı)*
- **D16 — `TrWebWhoisClient.java:304` `htmlUnescape` `&amp;`'i en önce çözüyor (çifte-unescape).** `&amp;lt;` → `&lt;` → `<` yanlış çözülür. Güvenlik değil, sessiz veri bozulması. **Çözüm:** `&amp;` dönüşümünü en sona al.
- **D17 — DNS-rebind TOCTOU kalıntısı (kodda kabul edilmiş).** `ssrfGuard.validate()` IP döndürüyor ama JDK HttpClient host'u yeniden çözüyor (validate↔bağlantı arası). Metadata/loopback her hop bloklu + JVM DNS cache riski azaltıyor. **Çözüm (opsiyonel):** `validate()`'in döndürdüğü IP'ye pinle (HttpChecker'ın `rewriteHostToIp` desenini tüm yollara yay). *(doğrulanmalı)*
- **D18 — `UserPushService.java:316` yanıt gövdesini `BodyHandlers.ofString()` ile sınırsız okuyor.** Admin-yapılandırmalı push API'si kötü/dev yanıt dönerse OOM. **Çözüm:** yanıtı sabit tavanla oku (O12 ile aynı sınıf). *(doğrulanmalı)*

---

## Kök-neden kümeleri ve önerilen sıra

1. **Güvenli yönlendirme yardımcısı (Y8 + O10 + O11'i tek dokunuşta kapatır).** `PageFetchCore` zaten doğru deseni içeriyor: `Redirect.NEVER` + manuel hop döngüsü + her hop `ssrfGuard.validate` + IP pinleme. Bunu ortak bir `SafeRedirectFollower`'a çıkarıp Keyword/HTTP/HSTS checker'larına uygula. **En yüksek güvenlik kazancı.**
2. **Y6 (AUDIT yetki).** Tek satır bölme + tek regresyon testi; kodun kendi yorumu çözümü söylüyor.
3. **Y7 (gzip bomb).** `decode()`'a açılmış-boyut tavanı; tek fonksiyon.
4. **O12 + D18 + O13 (SsrfGuard/yanıt tavanı boşlukları).** Webhook/UserPush URL'lerine kaydetme+gönderim doğrulaması ve yanıt sınırı; Uptime'a PortChecker deseni.
5. **O9 (incident transfer).** Kayıt-başına + hedef-takım kapsamı.
6. **O14 (UserPush HttpClient).** Paylaşılan istemci + `@PreDestroy`.
7. **DÜŞÜK grubu** fırsat buldukça; D14 (FD simetrisi) ve D15/D16 (WHOIS parse) ucuz.

**Doğrulama disiplini:** Y6, Y7, Y8, O9, O13, O14 kaynak üzerinde satır satır teyit edildi (dosya:satır ve çağrı zincirleriyle). "doğrulanmalı" DÜŞÜK maddeler düzeltmeden önce bir kez daha okunmalı. Her düzeltme mevcut testleri değiştirmeden yeni test ekleyerek yapılmalı; SSRF düzeltmeleri için metadata-redirect ve gzip-bomb senaryolarını pinleyen testler önerilir.
