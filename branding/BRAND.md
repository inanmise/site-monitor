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
| `favicon.ico` (16+32+48) | Varsayılan tarayıcı ikonu |
| `apple-touch-icon.png` (180, opak beyaz) | iOS ana ekran |
| `contact-sheet.png` | Açık/koyu zemin görsel kontrol sayfası |
| `../tools/make_variants.py` | Tüm seti kaynak JPEG'den deterministik yeniden üretir |

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
| Tarayıcı favicon | dinamik `{durum}-32` | PNG swap (`link[rel=icon]`) | Sekmeden bakışta filo sağlığı — izleme ürünlerinde standart desen |
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
| iOS/Android ana ekran, PWA | `ok` | apple-touch 180 / 192 / 512 | Statik — OS ikonları dinamik olamaz |

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
`contact-sheet.png` açık/koyu zeminde gözle doğrulanır.
