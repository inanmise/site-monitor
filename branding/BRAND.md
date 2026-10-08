# Site Monitor — Marka ve Logo Rehberi

Kaynak logo: mor turp (pancar) gövdesi içinde hekzagonal izleme grafiği, yukarı ok ve devre-uçlu
yeşil yapraklar. Bu rehber, logonun kurumsal uygulama genelinde **nerede, hangi varyantla, hangi
boyutta** kullanılacağını tanımlar. Uygulama entegrasyonu `/logo-uygula` komutuyla yapılır.

## 1. Varlık envanteri (`branding/assets/`)

| Dosya | Amaç |
|---|---|
| `logo-{ok,warning,critical,muted}-1024.png` | Master'lar (şeffaf, kare tuval, 1024px) |
| `logo-{durum}-{512,192,64,32}.png` | UI/PWA/navbar/favicon boyutları |
| `email-{durum}.png` (320px) | E-postada 160px genişlikte @2x retina |
| `favicon.svg` | **Favicon master'ı** — turp logosunun küçük boyuta göre yeniden çizimi (§7) |
| `favicon.ico` (16+32+48), `favicon-{16,32}.png` | Tarayıcı ikonu (SVG desteklemeyen istemciler) — `favicon.svg`'den üretilir |
| `apple-touch-icon.png` (180, opak beyaz) | iOS ana ekran — `favicon.svg`'den üretilir |
| `icon-{192,512}.png`, `icon-maskable-512.png`, `site.webmanifest` | PWA / Android ana ekran (§7) |
| `favicon-sheet.png` | Favicon setinin açık/koyu zemin + 8x piksel kontrol sayfası (sunulmaz) |
| `contact-sheet.png` | Açık/koyu zemin görsel kontrol sayfası |
| `../tools/make_variants.py` | Logo setini kaynak JPEG'den deterministik yeniden üretir (favicon seti HARİÇ) |
| `../tools/make_favicon.mjs` | Favicon + PWA setini `favicon.svg`'den üretir, sunulanları `frontend/public/`'e kopyalar |

Şeffaflaştırma **yalnız dış arka plana** uygulanmıştır: yörünge şeridi, hekzagon içindeki grafik
çizgisi ve nokta dolguları gibi iç beyazlar korunur — logo açık VE koyu temada bozulmadan çalışır.

## 2. Palet

| Renk | Hex | Kullanım |
|---|---|---|
| Marka moru (gövde) | `#813387` | Sabit — hiçbir durumda değişmez |
| Sağlıklı yeşil (yaprak) | `#64A64F` | OK / çözüldü / tüm sistemler sağlıklı |
| Uyarı amberi | `#A7763B` | WARNING / DEGRADED |
| Kritik kırmızı | `#9F3B32` | CRITICAL / DOWN / açık kritik alarm |
| Sessiz gri | `#99A096` | Duraklatılmış / bilinmeyen / veri yok |

**Temel ilke:** Gövde (mor) marka kimliğidir, ASLA renk değiştirmez. Durum yalnız yapraklar +
ok/devre aksanlarıyla anlatılır. Böylece logo her durumda tanınır kalır (kurumsal tutarlılık).

## 3. Durum semantiği (uygulama durumları → varyant)

| Uygulama durumu | Varyant |
|---|---|
| Açık CRITICAL alarm var / DOWN | `critical` |
| Açık WARNING alarm var / DEGRADED (CRITICAL yokken) | `warning` |
| Tüm alarmlar kapalı / çözüldü / sağlıklı | `ok` |
| İzleme duraklatılmış, veri yok, bilinmeyen | `muted` |
| Marka bağlamı (login, doküman, rapor kapağı) | `ok` (nötr varsayılan) |

Öncelik sırası her zaman: `critical > warning > ok`; `muted` yalnız veri yokluğunda.

## 4. Yerleşim matrisi (kurumsal)

| Yer | Varyant | Boyut/format | Not |
|---|---|---|---|
| Tarayıcı favicon | `favicon.svg` (+ ico/png yedeği) | statik, §7 | Uygulama içinde daima nötr (kullanıcı kararı 2026-08-06); `useStatusFavicon('ok')` statik seti ezmez. Durum-duyarlı PNG swap (`{durum}-32`) hazır ama bağlı değil |
| Navbar / üst bar | dinamik | 28–40px | Yanında rozet/sayı (renk körlüğü için renk tek sinyal OLMAZ) |
| Login ekranı | `ok` (nötr) | 96–160px | Durum GÖSTERİLMEZ — henüz auth yok, bilgi sızdırma olur |
| Yükleme/splash, boş durumlar | `muted` | 64–96px | |
| Oturum süresi doldu ekranı | `muted` | 64px | |
| Yardım/Hakkında + sürüm | `ok` | 64px | VERSION ile birlikte |
| Alarm e-postaları (CRITICAL/WARNING) | `critical`/`warning` | kart başlığında 32px, yazıyla eş boy | CID inline ek — dış URL değil; §5.1 kuralları |
| Çözülme (resolution) e-postaları | `ok` | kart başlığında 32px | "Yeşile döndü" hissi — kullanıcı konseptinin kalbi |
| Haftalık rapor / hatırlatma mailleri | `ok` | kart başlığında 32px | Nötr marka |
| PDF dışa aktarımlar (jspdf) | `ok` | header, ~40px yükseklik | Rapor kapağında büyük kullanılabilir |
| README / WHITEPAPER / docs | `ok` | 200px | Depo vitrini |
| iOS/Android ana ekran, PWA | favicon master'ı | apple-touch 180 / 192 / 512 / maskable 512 | Statik — OS ikonları dinamik olamaz (§7) |

**Kullanılmayacak yerler:** log satırları, k8s manifestleri, hata stack trace ekranları,
tablo hücreleri gibi yoğun veri alanları. Logo bir durum LAMBASI değil, markadır; satır
seviyesinde durum için mevcut lucide ikon + renk sistemi kullanılmaya devam eder.

## 5. Kullanım kuralları

- **Minimum boyut:** 24px altına UI'da inme (favicon 16px istisna). 48px altında yapraklardaki
  devre detayı okunmaz — sorun değil, siluet tanınır.
- **Koruma alanı:** logo yüksekliğinin %15'i kadar her yönde boşluk; metin/ikonla sıkıştırma.
- **Zemin:** açık ve koyu zeminde doğrudan kullan; renkli/fotoğraflı zemin üzerine koyma.
- **Deformasyon yok:** oran kilitli, döndürme/gölge/kenarlık ekleme.
- **Erişilebilirlik:** yaprak rengi hiçbir zaman TEK sinyal olamaz — yanında metin/rozet/sayı
  bulunur; `alt` metinleri i18n'den gelir ve durumu söyler (ör. "Site Monitor — kritik alarm var"),
  TR ve EN birlikte eklenir (parity testi).
- **E-posta:** logo daima **CID inline attachment** (multipart/related). Dış URL bloklu kurumsal
  gateway'lerde kırık görsel, base64 `img src` Gmail'de desteklenmez. Yerleşim §5.1'e uyar.
- **Tutarlılık:** UI'da varyant seçimi TEK bileşenden (`BrandLogo`), mailde TEK yardımcı metottan
  geçer. Sayfa sayfa elle logo dosyası import edilmez.

### 5.1 E-posta HTML kuralları (Outlook-güvenli + mobil duyarlı) — Rev. 2026-09-26

Saha bulgusu: Outlook masaüstü (Word render motoru) CSS genişliğini yok sayar; logo doğal
boyutunda (320px) ve kart dışında dev şekilde render oldu. Kullanıcı kararı (2026-09-26):
bütün e-postalar **mobil-web duyarlı** ve **shadcn görsel dilinde** olur — bu karar önceki
"`<style>` bloğu YOK / sabit `width="600"`" kuralının yerini alır. Bağlayıcı kurallar:

- **E-posta YALNIZ `MailDoc` ile kurulur** (`backend/…/service/mail/`: `MailDoc` kurucu,
  `MailKit` parçalar, `MailTokens` renk/yazı belirteçleri). Elle HTML dizesi birleştirmek,
  şablonda serbest hex yazmak YASAK — yeni bileşen gerekiyorsa `MailKit`'e eklenir. `MailDoc`
  HTML ile düz metni birlikte yazar → her e-posta multipart/alternative gider.
- Görsel dil = uygulamanın shadcn (zinc) belirteçleri: zemin `#f4f4f5`, kart `#ffffff` +
  `#e4e4e7` kenarlık + 10px köşe; metin `#09090b`, ikincil `#71717a`; birincil `#2563eb`;
  anlam tonları destructive/success/warning/info (tonlu zemin + tam kenarlık + ikon dairesi).
  Başlık çubuğu **nötr beyazdır** (logo + alt-sistem etiketi); önem **rozet + tonlu uyarı
  kutusuyla** anlatılır — renkli tam-genişlik başlık bandı ve üst aciliyet şeridi YOK.
- **Renkli sol şerit YOK** (kart, uyarı, satır — hiçbir yerde `border-left` ya da 4–5px renkli
  şerit hücresi); durum rozet/ton/ikonla verilir. `EmailResponsiveContractTest` bunu zorlar.
- Logo mail gövdesinde **yalnız kart başlık çubuğunda**, "Site Monitor" yazısının solunda,
  **yazı satırıyla eş boyda (28–32px)** durur. Serbest yüzen header/banner logosu YASAK;
  gövdede ve footer'da ikinci logo YASAK.
- `<img>` üzerinde `width` ve `height` **HTML attribute olarak zorunlu** (`width="32"
  height="32"`); inline `style` yalnız ikincil destek. CSS-only genişlik/`max-width`'e güvenme.
- Yerleşim tablo tabanlı ve **akışkandır**: kart `width="100%"` + `max-width:600px` (veri-yoğun
  raporlar 640px); Outlook için `[if mso]` hayalet tablosu sabit 600/640 genişliği kurar.
  `[if mso]` dışında ≥500px sabit genişlik YASAK. Tüm stiller inline; **TEK izinli `<style>`
  bloğu** `MailKit`'in duyarlı bloğudur (`@media (max-width:620px)`: `.px .stack .d-only .m-only
  .col-opt .btn-full .kv-l/.kv-v .tile .nw`). Düzen önce "fluid-hybrid" kurulur (istatistik
  kutuları ve buton grupları satır sarabilen inline-block) — stil bloğunu atan istemcide de
  yatay kaydırma çıkmaz. `<div>`+float, div zemini, `rgba()`, arkaplan görseli, web font
  yükleme, `position` KULLANILMAZ. `maximum-scale` viewport'a yazılmaz (yakınlaştırma serbest).
- Mobil: veri tabloları telefonda satır başına istif karta döner (`.m-only`, Outlook'a gitmez),
  anahtar-değer satırları alt alta iner, butonlar tam genişlik ve ≥44px dokunma hedefidir,
  yazı ≥12px (gövde 15px). Doğrulama: `EmailGalleryTest` (tüm türler) +
  `frontend/e2e/email-gallery.spec.js` (390/640px ekran görüntüsü, taşma kapısı).
- E-posta varlığı olarak **fiziksel 32px** `logo-{v}-32.png` kopyaları kullanılır (1x — retina
  keskinliği bilinçli feda). Sebep (saha bulguları #2–#3, 2026-08-06): forward zincirleri
  (Gmail→Outlook) `width` attribute'unu VE inline img+span dizilimini yeniden yazabiliyor;
  192px ve 64px varlıklar doğal boyutta basıldı, yazı alt satıra düştü. Savunma iki katmanlı:
  fiziksel boyut = hedef boyut, ve lockup inline değil **tablo-hücreli** (`headerLockup()` —
  hiçbir istemci hücreleri kıramaz). `BrandMailAssetsTest` PNG başlığından 32px'i doğrular.
- 850px rapor kartı ailesi **kaldırıldı** (2026-09-26): haftalık rapor, erişilebilirlik, aylık
  envanter ve olay bildirimleri 640px akışkan karta, veri tabloları duyarlı `dataTable`'a taşındı.
- Marka adı her yerde **"Site Monitor"** (bitişik "SiteMonitor" yazımı düzeltilir — subject,
  kart başlığı, footer dahil).

## 6. Yeniden üretim

Logo kaynağı değişirse:
```bash
python3 branding/tools/make_variants.py <yeni-kaynak.jpg> branding/assets
```
Script deterministiktir; tüm boyut ve varyantları yeniden üretir. Üretim sonrası
`contact-sheet.png` açık/koyu zeminde gözle doğrulanır. Favicon seti bu betikle DEĞİL, §7'deki
`make_favicon.mjs` ile üretilir.

## 7. Favicon ve uygulama ikonları (2026-10-08)

Ayrıntılı logo (hekzagon grafiği, devre uçları, yıldızlar) 16–32 px'te okunmaz bir lekeye dönüyordu.
Favicon bu yüzden **aynı turp işaretinin küçük boyuta göre yeniden çizimidir** — yeni bir logo değil:

- **Master:** `branding/assets/favicon.svg` (64×64 viewBox, elle yazılmış vektör). Mor yuvarlak gövde
  `#813387` (SABİT), üç yeşil yaprak `#64A64F` (orta yaprak bir ton koyu, derinlik için), yeşil kök ucu,
  gövdede beyaz izleme nabzı ve arkasında açık mor hekzagon aksanı.
- **Küçük boyut kuralı:** SVG'nin kendi `@media (max-width: 24px)` kuralı 24 px ve altında ince ayrıntıyı
  (hekzagon, parlama, uç noktası) gizler ve nabız çizgisini kalınlaştırır — 16 px'te siluet (mor top +
  yeşil taç) ve tek beyaz nabız kalır.
- **Koyu tarayıcı teması:** `prefers-color-scheme: dark`'ta gövdenin çevresine soluk bir kenar (`#F3E6F4`)
  çizilir; koyu sekme şeridinde gövde kaybolmaz. Gövde rengi DEĞİŞMEZ (§2 temel ilke).
- **Üretim:** `node branding/tools/make_favicon.mjs` — master'ı frontend'in Playwright Chromium'uyla
  rasterleştirir (Python görüntü kütüphanesi gerekmez): `favicon.ico` (16/32/48, PNG çerçeveli),
  `favicon-{16,32}.png`, `apple-touch-icon.png` (180, opak beyaz), `icon-{192,512}.png` (şeffaf),
  `icon-maskable-512.png` (beyaz zemin, işaret %72 — Android'in %80 güvenli dairesi içinde),
  `site.webmanifest` ve kontrol sayfası `favicon-sheet.png`. Sunulan dokuz dosya `frontend/public/`'e
  kopyalanır. Master değişince betik yeniden koşulur, `favicon-sheet.png` gözle doğrulanır.
- **Bağlantılar (`frontend/index.html`):** `favicon.ico` (sizes 32x32) + `favicon.svg` (image/svg+xml) +
  `apple-touch-icon.png` + `site.webmanifest`; `theme-color` açıkta `#813387`, koyuda uygulama zemini.
  Spring manifest'i `application/manifest+json` ile sunar (`server.mime-mappings.webmanifest`).
- **Uygulama içi:** favicon daima nötrdür; `useStatusFavicon('ok')` statik seti olduğu gibi bırakır. Durum
  varyantına (`/brand/logo-{durum}-32.png`) geçiş altyapısı hazır ama bağlı değil (§4).
- **E-posta CID logoları değişmez** (§5.1) — favicon yalnız tarayıcı/OS ikonudur.
- Bekçi: `frontend/src/test/brand-default.test.jsx` (dosyalar, boyutlar, ICO çerçeveleri, manifest,
  `index.html` bağlantıları, master'ın marka renkleri).
