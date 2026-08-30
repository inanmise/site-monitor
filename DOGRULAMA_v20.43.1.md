# v20.43.1 — Bağımsız Doğrulama Turu

**Tarih:** 2026-08-30 · **Kapsam:** `5c33e511` ile yayına giren dört güvenlik düzeltmesi
**Sözleşme:** kod DEĞİŞTİRİLMEDİ. Bu tur yalnız okuma + mutasyon ölçümü + rapor.

## Yöntem

Amaç düzeltmelerin *var olduğunu* onaylamak değil, **atlatılabilir olup olmadığını** ve
**testlerin o kapıları gerçekten ısırıp ısırmadığını** ölçmek. Üç eksen:

1. **Atlatma avı** — her kapı için "aynı kaynağa kapıdan geçmeden ulaşan başka bir yol var mı?"
2. **Mutasyon turu** — 11 mutasyon, hepsi **geçen turdakilerden farklı açılardan**. Geçen turun
   9/9 sonucu, o turda yazılmış testlerle *ilişkili* mutasyonlardan geliyordu; bu tur bilerek
   başka noktalara vurdu.
3. **Kardeş karşılaştırması** — aynı deseni uygulayan diğer sınıflarla satır satır fark.

**Bağımsızlık sınırı — açıkça belirtilmeli:** bu doğrulamayı, düzeltmeleri yazan aynı ajan
yürüttü (oturum kuralı gereği alt-ajan kullanılmadı). Bağımsızlık yöntemle sağlandı (düşmanca
çerçeveleme + mekanik mutasyon ölçümü), taze gözle değil. Gerçek taze-göz turu isteniyorsa ayrı
bir alt-ajan koşumu gerekir.

---

## Sonuç: kapılar DOĞRU, testler iddia edildiği kadar güçlü DEĞİL

Dört düzeltmenin dördü de mantıksal olarak doğru ve **atlatılabilir değil**. Ancak mutasyon turu,
kapıların bir kısmının **testle korunmadığını** gösterdi: kapıyı gevşeten mutasyonlar testleri
kırmadan hayatta kaldı. Geçen turun "9 mutasyonun 9'u öldü" ifadesi kendi seçtiği mutasyonlar
için doğruydu ama **kapsama dair yanıltıcı bir güven** verdi.

| | Sayı |
|---|---|
| Uygulanan mutasyon | 11 |
| Öldürüldü | 3 |
| Eşdeğer mutant (davranış değişmiyor, öldürülemez) | 2 |
| **Hayatta kalan — gerçek test boşluğu** | **6** |

---

## A. Atlatma avı — hepsi TEMİZ ✓

| Kapı | Soru | Bulgu |
|---|---|---|
| OCSP/CRL | `openWithProxy` tek ağ yolu mu? | ✓ `ChainValidationService`'te başka ağ çağrısı YOK (yalnız `:366`/`:373`, ikisi de `guardTarget`'tan sonra). OCSP `:217`, CRL `:317` — ikisi de bu metottan geçiyor. |
| HSTS | Başka HSTS probe'u var mı? | ✓ İki probe var: `CertificateCheckerService:505` (bu turda düzeltilen) ve `HstsDiagnosticsService` (`AdminController:514` üzerinden). İkisi de hop-başı guard'lı. *Ama aralarında sapma var — bkz. §C1.* |
| Notlar | Başka okuma/yazma yolu var mı? | ✓ `MonitorNote`/`MonitorGuide`'a dokunan tek sınıf `MonitorNotesController`. Beş ucun beşi de kapılı: GET → `noteVisible` filtresi; PUT/`{id}` ve DELETE/`{id}` → `requireModify`. `SchedulerService`'teki eşleşme yalnız bir yorum satırı, veri erişimi yok. |
| Incident yorumları | Kapısız bir GÜNCELLEME ucu var mı? | ✓ Yorum güncelleme ucu **yok** (yalnız POST ekle + DELETE sil). `AlertComment`'e dokunan tek sınıf `IncidentsController`. "9 halkanın 8'i" tuzağı bu sefer yok. |

---

## B. Mutasyon turu — ayrıntı

### Öldürülenler ✓

| # | Mutasyon | Öldüren |
|---|---|---|
| A1 | `guardTarget` politika reddini YUTUYOR (`catch` `BlockedException`'a genişletildi) | `OcspCrlSsrfGuardTest` |
| B2 | HSTS hop tavanı kaldırıldı (`MAX_HOPS` → 1000) | `HstsProbeRedirectGuardTest` |
| C1 | Takımsız (eski) notlar gizleniyor (`return true` → `false`) | `MonitorNotesControllerTest` |

### Eşdeğer mutant — öldürülemez, boşluk DEĞİL

| # | Mutasyon | Neden eşdeğer |
|---|---|---|
| A2 | `host.isBlank()` kontrolü kaldırıldı | `URI.getHost()` **asla boş dize dönmez** — ölçüldü: `http:///ocsp`, `https:///`, `http://:80/x` hepsi `null`. `isBlank()` ulaşılamaz savunma kodu. |
| B3 | Aynısı `guardHstsHost` için | Aynı gerekçe; host her zaman `URI.getHost()` çıktısı. |

### Hayatta kalanlar — GERÇEK TEST BOŞLUKLARI

Önem sırasıyla. Üçü kapıyı **gevşetiyor** ve hiçbir test fark etmiyor:

**1 · B1 — `https → http` düşürümü takip ediliyor, test yok** · YÜKSEK
`CertificateCheckerService.openHstsFollowingSafely:882`
`if (next == null || SafeRedirect.isDowngrade(uri, next))` satırından `isDowngrade` çağrısı
silindiğinde **hiçbir test kırılmıyor**. Kapı doğru yazılmış ama korumasız: ileride biri bu
satırı sadeleştirirse sessizce düşer.
**Çözüm:** yerel sunucuya https→http yönlendirmesi kuran bir test. `HstsProbeRedirectGuardTest`
zaten `HttpServer` harness'ı taşıyor; `HttpsServer` kolu eklenmeli (deseni
`HstsDiagnosticsServiceTest` veriyor).

**2 · C3 — Not yazma kapısı `canView` ile sorsa testler FARK ETMİYOR** · YÜKSEK
`MonitorNotesController.requireModify:194`
`SessionScope.canManage` → `canView` değiştirildiğinde testler yeşil kalıyor. Sebep test
kurgusunda: `teamSession()` yardımcısı `viewTeamIds` ve `manageTeamIds`'i **aynı listeye**
kuruyor, dolayısıyla ikisi arasındaki farkı ölçemiyor. Bu, `IncidentsController`'da düzeltilen
kusurun **tam olarak kendisi** — not tarafında testle korunmuyor.
**Çözüm:** `IncidentsControllerTest`'teki `scopedSession(role, view, manage)` deseni
(görüş iki takım, yönetim bir takım) `MonitorNotesControllerTest`'e taşınmalı.

**3 · D3 — Boş kapsam her şeye izin verse test yakalamıyor** · YÜKSEK
`IncidentsController.incidentTeamInScope:368`
`if (scope == null || scope.isEmpty()) return false;` → `return true` yapıldığında testler yeşil.
Yönetim yetkisi olmayan (manage kapsamı boş) bir kullanıcının, yazarı olmadığı bir yorumu
silememesini pinleyen test yok — mevcut test bu senaryoda `own` dalından erken dönüyor.
**Çözüm:** "manage boş + yazar değil → 403" testi.

**4 · D1 — `requireIncidentScope` çağrısı silinse testler geçiyor** · DÜŞÜK
`IncidentsController.deleteComment:315`
Güvenlik sonucu değişmiyor: `canManageIncident` daha katı ve arkasında duruyor, yetkisiz
kullanıcı yine 403 alıyor. Kaybolan tek şey **numaralandırma koruması** (kapsam dışı bir yorumun
varlığının ayırt edilememesi). Bu yüzden DÜŞÜK.

**5-6 · C2 / D2 — Global admin kısayolu silinse test yakalamıyor** · DÜŞÜK
`MonitorNotesController:189` · `IncidentsController:385`
Bu mutasyonlar kapıyı **sıkılaştırıyor**, gevşetmiyor — güvenlik açığı değil, *erişilebilirlik*
kapsama boşluğu (global yöneticinin meşru erişimini pinleyen test yok). Yine de düzeltmesi ucuz.

---

## C. Kardeş karşılaştırması — bir sapma bulundu

**C1 · `HstsDiagnosticsService` düşürüm kontrolü YAPMIYOR** · ORTA
`HstsDiagnosticsService.openFollowingSafely` ↔ `CertificateCheckerService.openHstsFollowingSafely`

```
tanılama:  if (next == null) return hc;
sertifika: if (next == null || SafeRedirect.isDowngrade(uri, next)) return hc;
```

`SafeRedirect.nextHop` `http` şemasını **kabul ediyor** (ölçüldü) — düşürümü engelleyen tek şey
çağıranın `isDowngrade` çağrısı. Tanılama onu çağırmıyor, yani `https://hedef → http://…`
zincirini takip ediyor.

İki sonucu var:
- **Doğruluk (asıl zarar).** RFC 6797 §7.2 uyarınca kullanıcı ajanı, güvensiz taşıma üzerinden
  gelen `Strict-Transport-Security` başlığını **yok saymak zorundadır**. Tanılama düz HTTP
  üzerinden okuduğu başlığa bakıp "ENFORCED" diyebilir — yani var olma sebebi olan ölçümde
  yanlış yeşil verir.
- **Güvenlik (sınırlı).** `guardHost` her hop'ta çalışmaya devam ettiği için SSRF politikası
  hâlâ uygulanıyor; kayıp, düz metin üzerinden başlık okuması.

**Ayrıca:** döngü sınırları da ayrışmış — tanılama `hop <= MAX_HOPS` (6 tur), sertifika
`hop < MAX_HOPS` (5 tur). Kozmetik ama aynı deseni iki farklı sayıyla uyguluyorlar.

**Düzeltme notu (özeleştiri):** `5c33e511` commit mesajı ve `CertificateCheckerService:862`
javadoc'u "`HstsDiagnosticsService.openFollowingSafely` deseninin aynısı" diyor. **Değil** —
düzeltilen taraf kardeşinden daha katı. İddia geri alınmalı ya da kardeş hizalanmalı.

---

## D. Kapsamda bilinçli bırakılan sınır

**`MonitorGuide`'da takım alanı YOK** · ORTA
`MonitorGuide` entity'sinde `teamId` bulunmuyor. `PUT /notes/guide` (`:71`) yalnız
`monitoring.crud` `edit` yetkisi istiyor — takım kapsamı yok. Yani `monitoring.crud` yetkisi olan
herhangi bir kullanıcı, **herhangi bir** monitörün rehberini okuyabiliyor ve üzerine yazabiliyor.

Notlar için kapı kapatıldı çünkü not entity'si zaten takım damgası taşıyordu; rehber taşımıyor ve
kapatmak şema değişikliği + geri-doldurma gerektiriyor. Düzeltme turunda bilinçli olarak kapsam
dışı bırakıldı — burada **açık bir sınır olarak** kayda geçiriliyor, "kapatıldı" sayılmamalı.

**Gözlem (bulgu değil):** `POST /{id}/comments` (yorum ekleme) da `requireIncidentScope` yani
**okuma** kapsamıyla yetkilendiriliyor. Silmeden farkı: ekleme toplayıcı, yazarı damgalı ve geri
alınabilir; silme yumuşak ve geri alma arayüzü yok. Bu yüzden silme düzeltildi, ekleme
dokunulmadı — ürün kararı olarak makul, ama bilinerek öyle.

---

## E. Baseline regresyon — TEMİZ ✓

v20.43.0'ın on düzeltmesinin imzası yerinde: `determineAlertTypes`, `isDurationAlert`,
`inventoryViewable`, `_capped` dış tavan, `PushText.compactDuration`, `withZone(ZoneOffset.UTC)`,
`detachIfIdentityChanged`, `eqHost`, `securityFlags`, `SafeRedirect.MAX_HOPS`.
`restoreChange` skip kümesi `{teamId, groupName, standalone}` olarak duruyor. **Regresyon yok.**

S-sınıfı süpürmesi: S2 (`Redirect.NORMAL`) yalnız sabit dış uçlarda (RDAP/TrWhois) — önceki turda
kabul edilmiş konum. S4 adaylarının hepsi vetted-IP'ye bağlanıyor (`PortCheckerService:127`
örnek: `addr` SsrfGuard çıktısı + takip kapalı). S16 `into` kara-listede, dış tavan yerinde.

---

## F. Yeni bulgu — HTTP monitöründe kimlik körlüğü (kullanıcı bildirimi)

Bu tur sırasında bildirilen `https://www.example.com/` vakası ölçüldü:

```
nslookup  www.example.com  →  192.168.1.1     (yerel modem)
curl      http://www.example.com/  →  HTTP 200, 1036 bayt, 192.168.1.1
```

Alan adı **yok**; modem NXDOMAIN-hijack yapıp kendi adresini dönüyor ve kendi giriş sayfasıyla
200 veriyor. `HttpCheckerService`'in sağlık kuralı tam olarak *"durum kodu `expectedStatus`
pattern'ine uyuyor ve hata yok"* (`:34`, `:225`). Gerçek bir 200 geldi → alarm yok.

Bu, oturumun başında bildirilen **sertifika kör noktasının HTTP katmanındaki kardeşi**. Sertifika
tarafı v20.43.0'da kapatıldı (`securityFlags` → hostname uyuşmazlığı / güvenilmeyen CA). HTTP
monitöründe eşdeğeri **yok**: monitör "cevap geldi mi" diye soruyor, "cevaplayan gerçekten
istediğim site mi" diye sormuyor. URL `http://` olduğu için TLS hükmü de devreye giremiyor.

**En yüksek sinyalli çözüm:** çözümlenen IP sağlaması — genel bir alan adı RFC1918 / loopback /
link-local bir adrese çözümlenirse alarm. Veri zaten elde: `SsrfGuard.validate` vetted IP
listesini dönüyor, ayrıca bir DNS turu gerekmiyor. Tüm NXDOMAIN-hijack biçimlerini yakalar ve
`http`/`https` ayrımından bağımsızdır.
*Anlık geçici çözüm:* aynı hedefe içerik çıpalı bir Keyword monitörü — modem giriş sayfası
beklenen metni içermeyeceği için alarm üretir.

---

## Önerilen sıra

1. **B1 + C3 + D3** — üç gerçek test boşluğu. Üretim kodu değişmez, yalnız test eklenir; kapılar
   zaten doğru. Ucuz ve en yüksek değerli.
2. **C1** — `HstsDiagnosticsService`'e `isDowngrade` + hop sınırını hizala; ya da paritenin
   olmadığını yazan javadoc/commit iddiasını düzelt.
3. **F** — HTTP monitöründe çözümlenen-IP sağlaması (ürün kararı ister: alarm mı, uyarı mı).
4. **D** — `MonitorGuide`'a takım alanı (şema + geri-doldurma; kendi turunu hak ediyor).
5. **D1 / C2 / D2** — düşük öncelikli kapsama boşlukları.
