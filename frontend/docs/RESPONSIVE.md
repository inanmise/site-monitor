# SiteMonitor — mobil web (mweb) duyarlı tasarım kuralları

**Karar (kullanıcı, 2026-09-26):** SiteMonitor mobil web'de (telefon + tablet) eksiksiz kullanılabilir olmalı.
Bundan sonraki HER arayüz geliştirmesi bu kurallara göre yapılır ve doğrulanır. UI kütüphanesi kuralı için
[`SHADCN.md`](./SHADCN.md) — iki belge birlikte geçerlidir (Claude oturumları için karşılıkları:
`site-monitor-shadcn-ui` ve `site-monitor-mweb-responsive` becerileri).

## 1. Hedef ekranlar ve kırılma noktaları

| Sınıf | Genişlik | Test boyutu |
|---|---|---|
| Küçük telefon | 360 px | 360×740 |
| Telefon | 390–414 px | **390×844** (kapı) |
| Tablet dikey | 768 px | **768×1024** (kapı) |
| Dizüstü | 1024–1366 px | 1280×800 |
| Masaüstü | 1440+ px | 1440×900 |

Kırılma noktaları **yalnız Tailwind'inkiler**: `sm` 640 · `md` 768 · `lg` 1024 · `xl` 1280 · `2xl` 1536.
**Mobil-önce yaz:** temel sınıflar telefon içindir, geniş ekran `sm:`/`md:`/`lg:` ile eklenir. App.css'teki eski
`@media (max-width: 600/720/760/900/1100px)` kuralları yeni kodda ÇOĞALTILMAZ; dokunduğun ekranda Tailwind'e taşı.
`useIsMobile()` (`src/hooks/use-mobile.js`, 768) yalnız davranış farkı için (ör. Sheet mi Popover mı); görünüm
farkı CSS ile.

## 2. Yerleşim

- **Kenar çubuğu:** shadcn `Sidebar` mobilde kendiliğinden `Sheet` olur; üst çubuk `components/nav/MobileTopBar.jsx`
  (`md:hidden`: menü düğmesi + marka + bildirim zili, 40 px hedefler). Çekmecede bir sekmeye dokunmak çekmeceyi kapatır;
  çekmece öğeleri telefonda 40 px (`touchHeight`). Kapalı çekmecenin içeriği DOM'da değildir → telefonda da yaşaması
  gereken şey (palet, pencereler, zil) kenar çubuğunun DIŞINDA çizilir. Yeni üst düzey gezinme öğesi Sidebar'a
  eklenir, sayfaya sabit genişlikli yan menü YAZILMAZ.
- **İçerik:** tam genişlik; kenar boşluğu telefonda `px-3`/`px-4`, geniş ekranda artar. Sabit `width`/`min-width`
  (px) YOK — `w-full` + `max-w-*`. Esnek çocukta `min-w-0` (yoksa uzun alan adı taşar).
- **Kart ızgaraları:** `grid-cols-[repeat(auto-fit,minmax(min(340px,100%),1fr))]` (tek sütuna düşer).
- **Formlar:** `grid-cols-1 sm:grid-cols-2`; alanlar `w-full`; etiket üstte.
- **Araç çubukları / filtre satırları:** `flex flex-wrap gap-2`; arama kutusu `w-full sm:w-auto sm:max-w-xs`;
  çok düğmeli gruplar sarar ya da `overflow-x-auto`. Tek satıra sığmayan eylemler `DropdownMenu`'ye ("…").
- **Başlık + eylem satırları:** `flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between`.

## 3. Bileşen kalıpları

| Öğe | Mobil kural |
|---|---|
| `Table` | shadcn Table zaten `overflow-x-auto` kapsayıcıda; düşük öncelikli sütunlar `hidden md:table-cell`; satır eylemleri tek `DropdownMenu`. Ana listelerde (izlemeler, sertifikalar) mobilde kart görünümü tercih edilir. |
| Dialog / `ModalShell` | telefonda neredeyse tam ekran: `max-h-[100dvh]`, gövde kayar, alt çubuk sabit; geniş içerik `sm:max-w-*`. Yan paneller `Sheet` (`side="bottom"` telefonda uygun). |
| Tabs | `TabsList` taşarsa `overflow-x-auto w-full justify-start`; 5+ sekme telefonda `NativeSelect`'e de düşebilir. |
| Popover / Command / Select | genişlik `w-[--radix-popover-trigger-width]` ya da `max-w-[calc(100vw-2rem)]`; modal içinde `z-(--z-menu)`. |
| Grafik (`ChartContainer`) | yükseklik sabit, genişlik kapsayıcıdan (`aspect-*` ya da `h-64 w-full`); eksen etiketleri seyrek. |
| Uzun metin (alan adı, URL, hash) | `truncate` + `title`/Tooltip, ya da `break-all` (URL). |
| Rozet dizileri | `flex flex-wrap gap-1`. |

## 4. Dokunma ve erişilebilirlik

- Dokunma hedefi **en az 40×40 px** (ikon düğme `size="icon"`; `icon-xs`/`icon-sm` yalnız yoğun masaüstü
  tablolarında ve yanında 8 px boşlukla).
- **Yalnız-hover bilgi YOK:** Tooltip dokunmatikte açılmaz; zorunlu bilgi görünür metin ya da `Popover` olur.
- Form girdileri telefonda **16 px** yazı (iOS yakınlaştırmasın) — shadcn `Input`/`Textarea` zaten
  `text-base md:text-sm`; bunu `text-sm` ile EZME.
- `100vh` yerine `100dvh`; alt sabit çubuklarda `pb-[env(safe-area-inset-bottom)]`.
- Klavye/odak halkası mobil tarayıcıda da görünür kalmalı (shadcn `focus-visible:ring`).

## 5. Doğrulama (her UI değişikliğinde)

1. **Kapı:** `frontend/e2e/responsive.spec.js` — her sekmeyi API mock'lu olarak 390×844 ve 768×1024'te açar,
   sayfa düzeyinde yatay taşma (`scrollWidth > innerWidth`) ve görünür öğelerin görünüm alanı dışına taşmasını
   ölçer. Yeni bir sekme/ekran eklediğinde listeye EKLE. Bilinen taşmalar listesi (`KNOWN_OVERFLOW`) yalnız
   KÜÇÜLÜR.
2. jsdom yerleşimi kanıtlamaz — yerleşim değişikliğini Playwright ile ölç ya da tarayıcıda 390 px'te bak
   (kullanıcının GStack penceresinde `$B viewport` KULLANMA; ayrı Playwright koşusu).
3. Kontrol listesi: yatay kaydırma yok · tüm eylemler erişilebilir (gizli taşma yok) · dokunma hedefleri ≥40 px ·
   modallar ekrana sığıyor ve kayıyor · tablolar kayıyor ya da kartlaşıyor · koyu tema.
