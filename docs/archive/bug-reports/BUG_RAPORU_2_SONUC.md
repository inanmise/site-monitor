# Bug Raporu Turu-2 — Uygulama Sonucu

**Tarih:** 2026-08-29 · **Kapsam:** Y6–Y8 (YÜKSEK), O9–O14 (ORTA), D14–D18 (DÜŞÜK)
**Sonuç:** 18 bulgudan **16'sı gerçek ve düzeltildi**, 2'si geçersiz çıktı (kod zaten korunuyordu).

---

## 1. Kök neden: tek hata değil, tek DESEN

Bulguların çoğu aynı kökten geliyordu: **giden istek atan her servis kendi güvenlik kontrolünü
kendi yazıyordu.** Java `HttpClient.Redirect.NORMAL` (ve `HttpURLConnection`'ın
`setInstanceFollowRedirects(true)`'u) yönlendirme zincirini **kütüphanenin içinde** takip eder —
ara hop'lar uygulamaya hiç görünmez. Sonuç: ilk host `SsrfGuard`'dan geçse bile hedef sunucu bizi
`302 Location: http://169.254.169.254/` ile iç ağa yönlendirebiliyordu.

Doğru desen projede **zaten vardı** (`PageFetchCore`: otomatik takip kapalı + manuel hop döngüsü +
her hop `ssrfGuard.validate`), ama üç checker'a hiç uygulanmamıştı. Politikayı kopyalamak yerine
`service/SafeRedirect.java` olarak ayırdım (taşımadan bağımsız, saf, tek kopya) ve dördüne birden
uyguladım.

---

## 2. Bulgu → düzeltme → test eşlemesi

| # | Bulgu | Düzeltme | Kapı |
|---|---|---|---|
| **Y6** | `PermissionCatalog:36` tek satırda `List.of(VIEW, EDIT)` → AUDIT alarm ALICI listelerini düzenleyebiliyordu | Satır ikiye bölündü (kodun kendi yorumunun tarif ettiği, `monitoring.group`'a zaten uygulanmış desen) | `PermissionCatalogTest`: AUDIT **süpürme** testi (tek anahtar değil KURAL) + notification.groups rol matrisi |
| **Y7** | `PageFetchCore.decode()` gzip/deflate'te `readAllBytes()` — 2 MB tel → GB'lara açılabilir (tek pod, OOM=kesinti) | `readNBytes(MAX_DECODED_BYTES)`; tavan tel tavanının **10 katı** (gerçek sayfalar kırpılmaz), tavana dayanınca WARN | `PageFetchCoreTest`: 25 MB'lık bomba tavanda kesilir + normal gzip TAM açılır (regresyon) |
| **Y8** | `KeywordCheckerService` `Redirect.NORMAL`, yalnız İLK host doğrulanıyor — ve bu uç gövdeden **snippet DÖNDÜRÜYOR** (USER yetkisi yeter) | `Redirect.NEVER` + manuel hop döngüsü, her hop `validate` | `KeywordCheckerServiceTest` +4: metadata'ya yönlendirme engellenir (snippet YOK), `file:` takip edilmez, zincir hâlâ takip edilir, döngü hop sınırında durur |
| **O9** | `IncidentController.transfer` yalnız `incidents.manage` istiyordu (USER'a açık); kaynak kayıt ve hedef takım hiç bakılmıyordu | Her kaynak için `requireIncidentWrite` + hedef için `requireTransferTarget`; **kısmi başarı yok** | `IncidentControllerTest` +5: yabancı kayıt 403, kapsam dışı hedef 403, karışık toplu seçim hiçbirini taşımaz, kendi takımı 200, admin serbest |
| **O10** | `HttpCheckerService` yönlendirmede aynı boşluk (durum/zamanlama oracle'ı) | `sendFollowing()` — manuel hop, her hop `validate`; `Redirect.NORMAL`'ın https→http düşürme kuralı korundu; **2 istemci eksildi** (selector-thread + havuz) | `HttpCheckerServiceTest` +3: metadata yönlendirmesi bloklu, zincir hâlâ takip edilir, `followRedirects=false` davranışı birebir aynı |
| **O11** | `HstsDiagnosticsService` **hiç** `SsrfGuard` çağırmıyordu — ve sonucu TÜM yanıt başlıklarını gösteriyor | `openFollowingSafely()` + `guardHost()`; takip elle | `HstsDiagnosticsServiceTest` +1: metadata hedefi `CONNECT_FAILED`, `response_headers` BOŞ |
| **O12** | `WebhookService.post()` SSRF doğrulaması yok, yanıt sınırsız okunuyor | `ssrfGuard.validate` + `ofInputStream` + 8 KB tavan + kurumsal TLS güveni (`pinAwareOutboundSslContext`) | `WebhookServiceTest` +2: metadata URL'ine istek HİÇ atılmaz, 4 MB yanıt tavanla okunur |
| **O13** | `UptimeHttpCheckerService` doğrulama yapmıyordu | `ssrfGuard.validate` + **doğrulanan IP'lere** bağlanma (rebind kapanır), `PortCheckerService` deseni | `UptimeHttpCheckerServiceTest` +1: metadata hedefi `down` + neden |
| **O14** | `UserPushService.client()` her gönderimde yeni `HttpClient` kuruyor, hiç kapatmıyordu | İstemci önbelleklenir; yalnız `connectTimeout` değişince yeniden kurulur (eskisi **kilit dışında** kapatılır — D3 dersi); `@PreDestroy` kapatır | `UserPushServiceTest` +2: art arda çağrıda AYNI örnek + TLS bağlamı tek kez; ayar değişince yeniden kurulur |
| **D14** | `CertificateCheckerService:638` tek-A dalı `connect` hatasında soketi kapatmıyordu (çok-A dalı kapatıyordu — asimetri) | Simetrik `close()` | `CertificateCheckerServiceTest` +2: iki dalda da soket kapanır |
| **D17** | DNS-rebind TOCTOU (kodun kendi yorumunda kalıntı olarak belgeliydi) | Aynı dokunuşta daraldı: doğrulama artık **her hop'ta**; Uptime checker doğrulanan IP'ye bağlanıyor | (yukarıdaki SSRF testleri) |
| **D18** | `UserPushService` yanıtı `ofString()` ile tam okuyup SONRA kırpıyordu | `ofInputStream` + 64 KB tavan; kesilen gövde ayrıştırılamazsa `notificationId` null kalır, gönderim YİNE başarılı | mevcut UserPush testleri (yanıt sözleşmesi) |

### Geçersiz çıkan iki bulgu (kod değişmedi, gerekçe)

* **D15** — `WhoisParser:80`'deki `LocalDate.parse(v.substring(0, 10))` zaten `try/catch` içinde;
  hatada `null` döner. Çökme yolu yok.
* **D16** — `TrWebWhoisClient:288`'de negatif `end` koruması **var**:
  `if (end < 0) end = Math.min(t.length(), start + 4000)`. `StringIndexOutOfBounds` oluşamaz.

---

## 3. Davranış değişikliği var mı?

Bilinçli olarak **yok** — yönlendirme takibi yer değiştirdi, kaldırılmadı:

* `followRedirects=false` olan HTTP izlemeleri birebir eskisi gibi (test ile pinli).
* Zincir hâlâ 5 hop'a kadar takip ediliyor; `303 → GET` kuralı korundu.
* `Redirect.NORMAL`'ın "https→http düşürmesini takip etme" kuralı `SafeRedirect.isDowngrade` ile korundu.
* HSTS'te **çözümlenemeyen** host bağlantıyı durdurmaz (vekil arkasında split-DNS gerçek);
  yalnız POLİTİKA reddi durdurur. Bu uç bugüne dek hiç doğrulama yapmadığından, "çözülemedi" diye
  reddetmek çalışan kurulumları bozardı.
* `notification.groups` bölmesi yalnız AUDIT'i etkiler; USER/TEAM_ADMIN düzenleme yetkisini korur (test ile pinli).

**Tek istisna** ve bilinçli: `transfer` ucunda kapsam dışı bir kullanıcı artık 403 alır. Zaten
hedeflenen düzeltme bu.

---

## 4. Doğrulama

* **Backend:** `mvn -B verify` → **3106 test yeşil**, 0 hata (öncesi 3070; +36 yeni test).
* **Frontend:** 162 dosya / **1412 test yeşil**, kapsam tabanı tamam (25 büyük dosya), `build` başarılı.
* **Mutasyon turu — 9/9 KIRMIZI** (düzeltmeyi geri al → ilgili test düşüyor):
  keyword hop-içi `validate` · HTTP checker hop-içi `validate` · HSTS `guardHost` ·
  Uptime `validate` · katalog satır bölmesi · gzip tavanı · transfer kaynak kapsamı ·
  transfer hedef kapsamı · push istemci önbelleği. Testler gerçekten kapı, dekor değil.
* **Kural 0:** diff taraması temiz — gerçek kişi/mail/kurum/takım adı yok
  (`example.com`, `Takım A/B` yer tutucuları).
* **Yerel:** jar yeniden paketlendi, uygulama yeniden başlatıldı, `/health` → **UP**.

---

## 5. Tarayıcıda doğrulanması gerekenler

jsdom ve birim testleri bunları kanıtlamaz:

1. Yönlendirmeli bir hedefe kurulu **keyword izlemesi** hâlâ çalışıyor mu (zincir takibi).
2. **HSTS tanılaması** bilinen bir domainde hâlâ sonuç veriyor mu.
3. **Olay transferi**: kendi takımına taşıma çalışıyor, yabancı takım seçilince 403 dönüyor.
4. **Kişi-webhook test gönderimi**: SENT + `notificationId` (yanıt okuma yolu değişti).

---

## 6. Kapsam dışı bırakılanlar

* **D10** (UserDirectory `findAll()` ile `photoBase64` yükleme) — önceki turdan devrediyor,
  JPQL projeksiyonu gerektiriyor; bu partiye alınmadı.
* **D11** (izleme oluşturma yollarında DB unique kısıtları) — dolu prod tablolarında mevcut
  mükerrer satır varsa index oluşturma DAĞITIMDA patlar; önce mükerrer envanteri çıkarılmalı.
* **Helm kimlik temizliği** ve **git geçmişi yeniden yazımı** — ops kararı.
