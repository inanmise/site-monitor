---
description: Site Monitor marka logosunu (durum-duyarlı turp logosu) kurumsal standartlarda tüm uygulamaya uygular — UI + dinamik favicon, e-posta CID, PDF raporlar, dokümanlar, i18n + testler. Varlıklar branding/assets/ altında hazırdır.
argument-hint: [ui|mail|pdf|docs] (opsiyonel — boş bırakılırsa TAM kapsam)
---

# /logo-uygula — Durum-Duyarlı Marka Logosu Entegrasyonu

Görev: `branding/assets/` altında HAZIR üretilmiş logo setini (mor turp; yapraklar durumla renk
değiştirir: yeşil=sağlıklı, amber=uyarı, kırmızı=kritik, gri=duraklatılmış) uygulamanın her
gerekli noktasına profesyonelce entegre etmek. **Kural ve yerleşim kaynağı `branding/BRAND.md`'dir
— önce onu oku**; bu komut uygulama adımlarını verir, rehberle çelişme.

Kapsam argümanı: `$ARGUMENTS` — boş: Faz 0–7 tümü; `ui`: 0,1,2,5,6,7; `mail`: 0,3,5,6,7;
`pdf`: 0,4,6,7; `docs`: 0,5,7.

## Değişmez kurallar

1. **Varlıkları YENİDEN ÜRETME** — `branding/assets/` son haldir (iç beyazlar korunmuş, koyu tema
   uyumlu). Kaynak değişirse tek yol: `python3 branding/tools/make_variants.py <src> branding/assets`.
2. **Tek doğruluk kaynağı:** UI'da varyant seçimi yalnız `BrandLogo` bileşeninden, mailde yalnız
   tek yardımcı metottan geçer. Hiçbir sayfa/servis doğrudan logo dosyası seçmez.
3. **Gövde moru sabittir; durum yalnız yapraklarda.** `critical > warning > ok` önceliği; `muted`
   yalnız veri yokluğunda. Login ekranı ve auth-öncesi hiçbir yüzeyde durum GÖSTERİLMEZ (nötr `ok`)
   — auth'suz kullanıcıya sistem sağlığı sızdırılmaz.
4. **Renk asla tek sinyal değildir** (renk körlüğü): dinamik logonun yanında mevcut rozet/sayaç
   korunur; `alt`/`aria-label` metinleri durumu SÖYLER ve i18n'den gelir (TR **ve** EN — parity
   testi kırılmasın).
5. Test kültürü aynen geçerli: gerçek SMTP'ye/dış host'a ASLA bağlanma (JavaMailSender mock),
   frontend'te gerçek `fetch` sızmaz, coverage floor'ları düşürülmez, `TESTING.md` geçersizleşirse
   aynı değişiklikte güncellenir. Commit atılmaz; sonuç kullanıcı onayına sunulur.
6. `lucide-react`-dışı görsel yasağı ikonlar içindir; marka logosu bu kuralın belgeli istisnasıdır
   (satır-içi durum ikonları lucide kalır — logo tablo hücrelerine, log'lara, k8s'e girmez).

## Faz 0 — Ön koşul

- `branding/assets/` envanterini `branding/BRAND.md` §1 tablosuyla karşılaştır — eksik dosya varsa DUR, raporla.
- Baseline yeşil mi: `mvn.cmd -f backend/pom.xml -B clean verify` + `cd frontend && npm ci && npm run test:coverage`.
  (JAVA_HOME=`C:\Program Files\Zulu\zulu-25`, Maven=`D:\portablePrograms\apache-maven-3.9.9\bin\mvn.cmd`.)
- `git status --short` kaydet; işin sonunda yalnız beklenen dosyaların değiştiğini doğrula.

## Faz 1 — Varlık yerleşimi

- `branding/assets/logo-*-{512,192,64,32}.png` → `frontend/public/brand/` (dinamik favicon swap
  sabit URL ister; Vite `public/` içeriğini olduğu gibi kopyalar).
- `favicon.ico` → `frontend/public/favicon.ico`, `apple-touch-icon.png` → `frontend/public/`.
- `email-{ok,warning,critical}.png` → `backend/src/main/resources/email-assets/` (classpath'ten
  CID eki olarak yüklenecek; `muted` maillerde kullanılmaz).
- `frontend/index.html` head: `<link rel="icon" href="/favicon.ico">`,
  `<link rel="apple-touch-icon" href="/apple-touch-icon.png">`,
  `<meta name="theme-color" content="#813387">`; `<title>Site Monitor</title>` doğrula.
- Backend `frontend/dist`'i statik servis eder; `RequestLoggingFilter`'ın `/favicon.ico` ve statik
  uzantı atlamaları yeni yolları da kapsıyor mu kontrol et (`/brand/*.png` → `.png` zaten atlanır).

## Faz 2 — Frontend: BrandLogo + dinamik durum

**`frontend/src/components/BrandLogo.jsx`** — tek bileşen:
- Props: `status` (`ok|warning|critical|muted`, default `ok`), `size` (px), `withText` (opsiyonel
  "Site Monitor" yazısı). İç harita: status → `/brand/logo-{status}-{en yakın boyut}.png`.
- `alt`/`aria-label`: `useT('brand.logo.alt.' + status)` — 4 anahtar × TR+EN, durumu söyleyen metin.
- Geçişte hafif CSS transition (renk sıçraması yumuşasın); koyu temada ekstra iş gerekmez
  (varlıklar şeffaf ve iç beyazları korunmuş).

**Genel durum türetme — YENİ endpoint AÇMA:** `App.jsx`'te halihazırda yüklenen alarm/uyarı
verisini keşfet (Warnings/AlertHistory sekmelerini besleyen state ve mevcut poll döngüsü).
`deriveGlobalStatus(openAlerts)` saf fonksiyonu yaz (`src/utils/brandStatus.js`): açık CRITICAL →
`critical`; yoksa açık WARNING → `warning`; veri henüz yüklenmediyse `muted`; aksi halde `ok`.
Saf fonksiyon = birim testi kolay. Mevcut poll aralığına dokunma; ayrı bir zamanlayıcı kurma.

**Dinamik favicon — `frontend/src/hooks/useStatusFavicon.js`:** status değişince
`link[rel="icon"]` href'ini `/brand/logo-{status}-32.png`'ye çevirir (yoksa link node'u yaratır);
`document.title`'ı `critical` durumda `"(!) Site Monitor"` yapar, normale dönünce geri alır.
İzleme ürünlerinde standart desen: kullanıcı sekmeye bakarak filo sağlığını görür.

**Yerleşimler** (BRAND.md §4 matrisi birebir):
- Login (`Login.jsx`): nötr logo, 96–160px, form üstünde — durum YOK.
- Navbar/üst bar: `<BrandLogo status={globalStatus} size={32} />` + mevcut rozet/sayaçlar kalır.
- Splash/yükleme ve session-expired yüzeyleri: `muted`, 64px.
- Yardım/Hakkında: nötr logo + `VERSION`.
- 48px altı yerleşimlerde devre detayının okunmadığını bil — siluet yeterli, küçültmeye çalışma.

## Faz 3 — E-posta: CID inline logo

`EmailNotificationService` (+ `WeeklyReportReminderService` yolu ve `SmtpMailService` altyapısı):
- HTML mail gövdesine header logosu: `<img src="cid:brand-logo" width="160" alt="Site Monitor">`.
  Ek: `MimeMessageHelper(multipart=true)` + `addInline("brand-logo", ClassPathResource("email-assets/email-{v}.png"), "image/png")`.
  **Dış URL ve base64 `img src` YASAK** (kurumsal gateway'ler remote image bloklar; Gmail base64
  desteklemez) — CID tek güvenilir yol.
- Varyant seçimi TEK metotta: alarm mailleri → severity CRITICAL ise `critical`, WARNING ise
  `warning`; **çözülme (resolution) mailleri → `ok`** (kullanıcı konseptinin kalbi: "sorun çözüldü,
  yapraklar yeşile döndü"); haftalık rapor/hatırlatma/test mailleri → `ok`.
- ÜÇ gönderim yolu da aynı metottan geçmeli: `processConfirmedOutage` / `sendResolutionNotification`
  / `reNotify` (CLAUDE.md'deki tutarlılık kuralı — resend yolunu unutma).
- Düz-metin fallback bozulmaz; `notification_logs` davranışı değişmez; SMTP kapalıyken davranış aynı.
- Teams/Slack (`WebhookService`): logo ekleme YOK (kart görselleri public URL ister, uygulama
  dışarıya kapalı) — raporda "bilinçli kapsam dışı" olarak not et.

## Faz 4 — PDF dışa aktarımlar

- Frontend'te `jspdf`/`jspdf-autotable` kullanan tüm dışa aktarımları grep'le bul.
- Ortak yardımcı: `src/utils/pdfBrand.js` — `logo-ok-192.png`'yi dataURL olarak header'a basar
  (~40px yükseklik, sol üst; başlık metni sağında). Her export bu yardımcıyı çağırır — sayfa sayfa
  elle logo gömme yok. PDF'te durum varyantı KULLANILMAZ (rapor kalıcı belgedir, anlık durum değil).

## Faz 5 — Dokümanlar

- `README.md` ve `WHITEPAPER.md` başlığına logo (`branding/assets/logo-ok-512.png` göreli yol);
  `QUICKSTART.md`'ye küçük başlık logosu. Bozuk göreli yol bırakma — GitHub render'ını düşün.
- `docs/` altına BRAND.md'yi linkle (taşıma değil — `branding/` tek doğruluk kaynağı kalır).
- `CHANGELOG.md`'ye girişi işleme (conventional prefix'i kullanıcı commit'te seçer).

## Faz 6 — Testler (proje konvansiyonlarıyla)

- **Frontend** (`src/test/`, `test-utils.jsx#render`, api `vi.mock`):
  - `BrandLogo.test.jsx`: 4 status → doğru asset yolu; alt metni TR ve EN'de durumu söylüyor;
    default `ok`; bilinmeyen status → `ok`'a düşer (savunmacı).
  - `brandStatus.test.js`: `deriveGlobalStatus` sınırları — boş liste→`ok`, undefined→`muted`,
    yalnız WARNING→`warning`, WARNING+CRITICAL→`critical` (öncelik), kapanmış alarmlar sayılmaz.
  - `useStatusFavicon` testi: link href swap + title değişimi (jsdom'da DOM assert).
  - i18n: 8 yeni anahtar (4 status × alt) TR+EN — `i18n-parity.test.jsx` yeşil kalmalı.
- **Backend** (`@ExtendWith(MockitoExtension.class)`, AssertJ):
  - Varyant seçim metodu birim testi: severity→dosya eşlemesi; resolution→`ok`; reNotify alarm
    severity'sini korur.
  - MimeMessage doğrulaması: `multipart/related` içinde `Content-ID: <brand-logo>` var, HTML
    gövde `cid:brand-logo` referanslıyor (JavaMailSender mock — gerçek SMTP yok).
  - Classpath kaynağı testi: üç `email-*.png` gerçekten classpath'te ve boş değil.
- Kapanış: `mvn -B clean verify` + `npm run test:coverage` — kapılar yeşil; sayılar raporda.

## Faz 7 — Doğrulama + rapor

1. `mvn.cmd -f backend/pom.xml package -DskipTests` → `cd frontend && npm run build` →
   `.\start-local.ps1` (paketlenmiş jar servis eder — yeniden paketlemeden eski kod çalışır, atlama).
2. Görsel kontrol listesi (http://localhost:8080): login'de nötr logo; girişte navbar logosu ve
   sekme favicon'u genel durumu yansıtıyor; koyu temada yörünge şeridi/grafik çizgisi beyaz
   görünüyor; TR ve EN'de alt metinler; bir PDF dışa aktarımında header logosu.
3. `pwsh ./scripts/smoke.ps1 -BaseUrl http://localhost:8080` yeşil.
4. Mail kanıtı: testlerdeki MimeMessage assert'leri + (SMTP ayarlıysa) tek bir test mail gönderimi
   önerisi — kullanıcı onayı olmadan gerçek mail ATMA.
5. **Final rapor:** faz × sonuç tablosu; değişen dosya listesi; yerleşim matrisinin uygulanma
   durumu (BRAND.md §4 satır satır ✅/kapsam dışı); test/coverage önce-sonra; bilinçli kapsam
   dışılar (Teams/Slack görseli, PDF'te durum varyantı, OS ikonlarının statikliği); kalan öneriler
   (ör. SVG yeniden çizim, PWA manifest) — commit kullanıcı onayına bırakılır.
