# shadcn/ui başvuru belgesi — site-monitor ön yüzü

> Kalıcı bilgi tabanı. Son derleme: **2026-09-25**. Kaynaklar: https://ui.shadcn.com/docs (o tarihteki hâli) +
> bu depodaki dosyalar (yolları her maddede verildi). Belge ile yerel dosya çelişirse **yerel dosya esastır**
> (bileşenler bilinçli olarak uyarlandı, bkz. §3).

İçindekiler

1. [Karar ve kural](#1-karar-ve-kural)
2. [Proje kurulumu](#2-proje-kurulumu)
3. [Projeye özgü uyarlamalar ve CLI tuzakları](#3-projeye-özgü-uyarlamalar-ve-cli-tuzakları)
4. [Bileşen kataloğu](#4-bileşen-kataloğu)
5. [İhtiyaç → bileşen karar rehberi](#5-ihtiyaç--bileşen-karar-rehberi)
6. [Theming (jetonlar, koyu tema, varyant ekleme)](#6-theming)
7. [Formlar](#7-formlar)
8. [Test (vitest + jsdom)](#8-test-vitest--jsdom)
9. [Kontrol listesi](#9-kontrol-listesi)
10. [shadcn dünyasındaki önemli değişiklikler (2025–2026)](#10-shadcn-dünyasındaki-önemli-değişiklikler-20252026)
11. [Bağlantılar](#11-bağlantılar)

---

## 1. Karar ve kural

**Karar (kullanıcı, 2026-09-25):** shadcn/ui bu projenin **varsayılan UI kütüphanesidir**. Bundan sonra
eklenen ya da değiştirilen her arayüz öğesi https://ui.shadcn.com/docs/components bileşenleriyle çizilir.
Legacy bir sınıfı shadcn'e *benzetmek* yetmez; gerçek bileşen (`src/components/shadcn/*.jsx`) kullanılır.

Kurallar:

- **Yeni öğede legacy App.css sınıfı YASAK:** `.btn*` (zaten silindi), `.input`, `.modal-overlay`,
  `.modal-box`, `.badge`, `.badge-*`, `.card-*`, `.seg-ctl*`, `.tab-content` vb. App.css katmansız olduğu
  için bu sınıflar shadcn öğesindeki Tailwind stilini **her zaman ezer** (§2.3).
- **Seçim sırası** (bir ihtiyaç doğduğunda):
  1. `src/components/ui/` altında bir **proje sarmalayıcısı** var mı? (ModalShell, `useDialog`, `useToast`,
     AlertBanner, StatusBlock, Field, Progress ailesi, SearchableSelect, MultiTeamSelect, KebabMenu,
     PaginationBar, SegmentedControl, HelpTip, TeamBadge, BulkActionBar…) Varsa **onu kullan** — içi zaten
     shadcn ve proje sözleşmelerini (i18n, katman, odak, test kancaları) taşıyor.
  2. **Kurulu bir shadcn bileşeni** (`src/components/shadcn/`, §4'te "Kurulu").
  3. shadcn'de var ama **kurulu değil** → CLI ile ekle ve §3.3'teki uyarlama listesini uygula.
  4. Tek bileşen karşılamıyorsa shadcn bileşenlerini **bileşik** kullan (ör. Date Picker = Popover + Calendar,
     Combobox deseni = Popover + Command + Button).
  5. En son: Tailwind yardımcıları + shadcn jetonlarıyla (`bg-card`, `text-muted-foreground`, `border`,
     `ring-ring/50`…) **shadcn üslubunda** elle yaz. Uydurma sınıf adı yazma (cssClasses kapısı, §8.6).
- **İki klasör, iki anlam:** `@/components/shadcn/*` = CLI'nin ürettiği ilkel bileşenler (kütüphane kodu,
  kapsam ölçümünden hariç — `vite.config.js` coverage.exclude). `@/components/ui/*` = projenin kendi
  sarmalayıcıları. shadcn belgelerindeki `@/components/ui/button` örneklerini kopyalarken yolu
  `@/components/shadcn/button` yap.
- Metinler i18n'den (`useT()` → `t('anahtar')`); sabit `aria-label` yasak (rowAccessibleNames kapısı).
  İngilizce metin = doğal İngiliz İngilizcesi.

---

## 2. Proje kurulumu

### 2.1 Sürümler (`frontend/package.json`)

| Paket | Sürüm | Not |
|---|---|---|
| react / react-dom | ^18.3.1 | **React 18** — shadcn CLI React 19 kalıbı üretir (§3.1) |
| tailwindcss / @tailwindcss/vite | ^4.3.3 | Tailwind v4, Vite eklentisiyle |
| radix-ui | ^1.6.7 | Birleşik Radix paketi (`import { Dialog as DialogPrimitive } from "radix-ui"`) |
| @base-ui/react | ^1.8.0 | Yalnız `shadcn/combobox.jsx` kullanıyor — o dosya bilinçli olarak kullanılmıyor (§4) |
| cmdk | ^1.1.1 | Command |
| sonner | ^2.0.8 | Bildirimler |
| react-day-picker | ^10.0.1 | Calendar |
| recharts | ^3.8.1 | Chart — aralık **sabit kalsın** (CLI değiştirmeye çalışıyor, §3.3) |
| class-variance-authority / clsx / tailwind-merge | ^0.7.1 / ^2.1.1 / ^3.7.0 | `cn` + varyantlar |
| tw-animate-css | ^1.4.0 | `animate-in`, `fade-in-0`… (eski `tailwindcss-animate`'in yerini aldı) |
| lucide-react | ^1.16.0 | İkon kütüphanesi (`iconLibrary: "lucide"`) |
| @fontsource-variable/inter | ^5.3.0 | Yazı tipi pakete gömülü (dış istek yok) |
| react-datepicker | ^9.1.0 | **Legacy** — Calendar'a geçiş (2. dalga F) bitince kaldırılacak |

Yok: `react-hook-form`, `@tanstack/react-form`, `zod`, `@tanstack/react-table`, `vaul`, `embla-carousel-react`,
`input-otp`, `react-resizable-panels`, `next-themes`, `cn`.

### 2.2 `components.json` (`frontend/components.json`)

```json
{
  "style": "new-york", "rsc": false, "tsx": false,
  "tailwind": { "config": "", "css": "src/styles/globals.css", "baseColor": "zinc",
                "cssVariables": true, "prefix": "" },
  "iconLibrary": "lucide",
  "aliases": { "components": "@/components", "utils": "@/lib/utils",
               "ui": "@/components/shadcn", "lib": "@/lib", "hooks": "@/hooks" }
}
```

- `style: "new-york"` — belgeye göre `default` stili kullanımdan kalktı; `style` init sonrası **değiştirilemez**.
- `baseColor` ve `cssVariables` da init sonrası değiştirilemez (değiştirmek = bileşenleri silip yeniden kurmak).
- `tailwind.config: ""` — Tailwind v4'te boş bırakılır.
- `tsx: false` → CLI `.jsx` üretir. Takma adlar `frontend/jsconfig.json` + `vite.config.js`
  (`resolve.alias['@'] = ./src`) ile çözülür.
- `rsc: false` → CLI normalde `"use client"` eklemez (ama bkz. §3.3).
- `aliases.ui = @/components/shadcn` — bu yüzden CLI dosyaları `src/components/shadcn/`'e yazar.

### 2.3 CSS mimarisi ve kaskat sözleşmesi

Dosyalar: `frontend/src/styles/globals.css`, `frontend/src/App.css`, içe aktarma sırası
`frontend/src/main.jsx:11-12` (önce `globals.css`, sonra `App.css`).

- `globals.css` yalnız `tailwindcss/theme.css` (layer theme) ve `tailwindcss/utilities.css` (layer utilities)
  içe aktarır → **Tailwind preflight YOK**. Preflight global sıfırlama olduğu için taşınmamış ekranları bozardı.
- **App.css KATMANSIZ**. CSS kuralı: katmansız kural, özgüllüğü ne olursa olsun, katmanlı her kuralı yener.
  Sonuç: bir shadcn öğesine legacy sınıf eklersen (`className="btn …"`, `modal-box`, `badge`, `card-header`…)
  o sınıfın App.css kuralı Tailwind'i ezer. **shadcn öğesinde legacy sınıf BIRAKMA.**
- App.css'in `* { margin:0; padding:0; box-sizing:border-box }` kuralı `@layer base`'e taşındı (katmansız kalsa
  her `p-4`/`mx-2` yardımcısını ezerdi).
- Preflight yerine dar sıfırlama (`globals.css` `@layer base`): `[data-slot] { border: 0 solid var(--color-border) }`,
  `button[data-slot], input[data-slot], textarea[data-slot], select[data-slot]` için font/renk/arka plan
  mirası, `button[data-slot] { cursor: pointer }` (belgedeki `init --pointer` seçeneğinin karşılığı),
  `[data-slot] svg { display:inline-block; vertical-align:middle }`. **Bu yüzden `data-slot` taşımayan elle
  yazılmış öğe bu sıfırlamayı almaz** — elle öğe yazıyorsan sınırı/fontu kendin ver.
- Koyu temada legacy global alan kuralı shadcn'e dokunmasın diye daraltıldı:
  `[data-theme="dark"] input:not([type="checkbox"]):not([data-slot])…` (`App.css:3843-3845`).
- Düğme köprüsü: `:where([data-slot="button"][data-size="sm"]) { margin-right: 4px; }` (`App.css:78`) — eski
  `.btn-sm` aralığını korur. **Yeni kod aralığı kapsayıcıda `gap-*` ile verir.**
- Tailwind kaynak taraması: `@source "../";` (tüm `src`). Animasyonlar: `@import "tw-animate-css"`.

### 2.4 Koyu tema

- Uygulamanın ThemeProvider'ı (`src/i18n/theme.jsx:31`) `<html data-theme="dark|light">` yazar — **sınıf değil**.
- Bu yüzden `globals.css`: `@custom-variant dark (&:where([data-theme="dark"], [data-theme="dark"] *));`
  (belgedeki varsayılan `.dark` sınıfı yerine). `dark:` yardımcıları bununla çalışır.
- Koyu jetonlar `[data-theme="dark"] { … }` bloğunda. shadcn belgesindeki Vite `ThemeProvider` /
  `ModeToggle` örneği (`classList.add("dark")`) **bu projede kullanılmaz**.

### 2.5 Katman (z-index) sözleşmesi — shadcn `z-50` burada YETMEZ

`App.css:49-65`: `--z-modal 2000` · `--z-announce 9000` · `--z-dialog 9500` · `--z-menu 9600` ·
`--z-toast 9700` · `--z-critical 9900`.

| shadcn dosyası | Yerel katman |
|---|---|
| `dialog.jsx` (Overlay + Content) | `z-(--z-modal)` — dosyada değiştirildi |
| `alert-dialog.jsx` | `z-(--z-dialog)` — dosyada değiştirildi |
| `sonner.jsx` | `zIndex: var(--z-toast)` — dosyada değiştirildi |
| `popover`, `dropdown-menu`, `select`, `tooltip`, `hover-card`, `sheet`, `combobox` | Dosyada hâlâ **`z-50`** |

`z-50`, ModalShell kaplamasının (2000+) **altında** kalır → modal içinde açılan menü/liste/ipucu görünmez.
Kural: modal içinde açılabilecek her yüzen içeriğe çağrı yerinde katman ver:
`className="z-(--z-menu)"` (KebabMenu `ui/KebabMenu.jsx:67`, PickerPopover `ui/PickerPopover.jsx:114`,
TagInput `ui/TagInput.jsx:126`, VersionChip `components/VersionChip.jsx:60`) ya da
`z-(--z-dialog)` (HelpTip `ui/HelpTip.jsx:96`).

### 2.6 Yardımcılar

- `src/lib/utils.js` → `cn(...inputs) = twMerge(clsx(inputs))`.
- `src/hooks/use-mobile.js` → `useIsMobile()` (kesme noktası 768px; Sidebar mobilde Sheet'e döner).

---

## 3. Projeye özgü uyarlamalar ve CLI tuzakları

### 3.1 React 18.3 → `forwardRef` şartı

shadcn CLI 4.x bileşenleri **React 19 kalıbıyla** üretir (işlev bileşeni, `ref` düz prop, `forwardRef` yok).
React 18 işlev bileşenine verilen `ref`'i **düşürür**. Radix `asChild` (Slot) çocuğuna ref bağlar (konum,
odak, Presence) → asChild çocuğu olan ya da ekranın ref verdiği her sarmalayıcı `React.forwardRef` olmalı.
`forwardRef` React 19'da da çalışır (ileri uyumlu).

forwardRef'e çevrilmiş dosyalar (yerel dosyada doğrulandı):

| Dosya | forwardRef olanlar |
|---|---|
| `shadcn/button.jsx` | `Button` |
| `shadcn/badge.jsx` | `Badge` |
| `shadcn/input.jsx` | `Input` |
| `shadcn/textarea.jsx` | `Textarea` |
| `shadcn/native-select.jsx` | `NativeSelect` (ref `<select>`'e gider) |
| `shadcn/dialog.jsx` | `DialogOverlay` |
| `shadcn/alert-dialog.jsx` | `AlertDialogOverlay` |
| `shadcn/command.jsx` | `Command`, `CommandInput` |
| `shadcn/collapsible.jsx` | `CollapsibleTrigger` |
| `shadcn/sidebar.jsx` | `SidebarMenuButton` |

Kalıp: `.migration/forwardref.py`. Yeni bir sarmalayıcıyı `<XTrigger asChild>` içinde kullanacaksan ya da
ona `ref` vereceksen önce forwardRef'e çevir. Belirti: ref `null`, menü/popover yanlış yerde açılıyor,
konsolda "Function components cannot be given refs".

### 3.2 Dosyada yapılmış bilinçli sapmalar (üstüne yazma!)

| Dosya | Sapma |
|---|---|
| `button.jsx` | `data-variant` / `data-size` öznitelikleri; proje varyantları `success` (`bg-success`), `warning` (`bg-amber-600`) |
| `badge.jsx` | `data-variant`; proje varyantı `warning` (amber tonlu) |
| `alert.jsx` | Proje varyantları `info` / `success` / `warning` / `danger` (koyu karşılıklı). Resmî belgede artık `AlertAction` var; yerel dosyada **yok** — AlertBanner `data-slot="alert-actions"` ile kendi çözümünü kurar |
| `dialog.jsx` | Katman `--z-modal`; yalnız opaklık animasyonu (zoom yok — e2e ölçümleri); `motion-reduce:animate-none!`. **DİKKAT:** yerleşik kapat düğmesinin sr-only metni hâlâ İngilizce "Close" → projede `DialogContent` **her zaman** `showCloseButton={false}` ile kullanılır ve i18n'li kendi X düğmesi konur (ModalShell, CommandPalette, Login) |
| `alert-dialog.jsx` | Katman `--z-dialog`; opaklık animasyonu; `overlayProps` (örtü tıklaması = İptal, `ui/Dialog.jsx`) |
| `progress.jsx` | `value`/`max` Radix Root'a iletilir (aria-valuenow), oran `max`'a göre, `indicatorClassName`, belirsiz durumda nabız şeridi |
| `spinner.jsx` | `aria-label={t('app.loading')}`, `motion-reduce:animate-pulse` |
| `sonner.jsx` | `next-themes` yerine `@/i18n/theme.jsx` `useTheme`; `zIndex: var(--z-toast)`; `pointerEvents: auto` (Radix modal `body`'ye `pointer-events:none` yazar); `style` birleştirilir; `fontFamily: inherit` |
| `sheet.jsx` | Kapat metni `t('app.close')` (katman hâlâ `z-50`) |
| `sidebar.jsx` | sr-only/aria metinleri i18n (`nav.toggleSidebar`, `nav.sidebarTitle`…); **Ctrl+B yazı alanında devre dışı** (Markdown editöründe kalın); `SidebarMenuButton` forwardRef. `SidebarProvider` içeride `TooltipProvider delayDuration={0}` sarar. Hâlâ `document.cookie` `sidebar_state` yazar ama App.jsx durumu kontrollü tutup `localStorage 'sidebar-open'`'a yazar. Dosyanın 1. satırında hâlâ `"use client"` var (Vite'da işlevsiz; §3.3 kuralına göre silinmeli) |
| `command.jsx` | `Command`/`CommandInput` forwardRef; `CommandDialog` başlık/açıklaması `t('palette.title')` / `t('palette.trigger')` |
| `pagination.jsx` | Sabit İngilizce aria-label'lar kaldırıldı; `PaginationPrevious`/`PaginationNext` metni **`label`** prop'uyla verilir (resmî belgede bu prop'un adı artık `text`); `Pagination`'a `aria-label` çağıran verir; üç nokta `t('pg.morePages')` |
| `tooltip.jsx` | `TooltipProvider` varsayılan `delayDuration=0`. `Tooltip` kendi sağlayıcısını **sarmaz** → sağlayıcı şart (§4, Tooltip) |
| `collapsible.jsx` | `CollapsibleTrigger` forwardRef |

### 3.3 CLI ile bileşen ekleme — adım adım

> Komutları çalıştırmadan önce çalışan test süitlerini ve bellek tavanını düşün (tek seferde tek iş).

1. **Sürümü sabitle:** `npx shadcn@4.21.0 …` (proje bu sürümle kuruldu).
2. **Üzerine yazma sorusu:** bağımlı bileşen dosyaları zaten varsa CLI sorar; hepsine "hayır" demek için
   `yes n | npx shadcn@4.21.0 add <bileşen>` (Git Bash'te, `cd /d/site-monitor-shadcn/frontend && …`).
   Uyarlanmış dosyalara **asla** `--overwrite` verme. Upstream farkını görmek için yazmadan:
   `npx shadcn@4.21.0 add <bileşen> --dry-run` / `--diff` / `npx shadcn@4.21.0 view <bileşen>`.
3. **Her `add` sonrası düzelt:**
   - `import { cn } from "cn"` → `import { cn } from "@/lib/utils"`
     (`sed -i 's#from "cn"#from "@/lib/utils"#' src/components/shadcn/<dosya>.jsx`).
     *Not:* bu bir CLI hatası değil — **Eylül 2026 değişikliği**: kayıt bileşenleri artık `cn`'i ayrı `cn`
     paketinden alıyor (`twMerge(clsx(...))`'in yerine geçen paket; resmî geçiş komutu
     `shadcn migrate cn`). **Proje kararı:** yerel `@/lib/utils` kalır, `cn` paketi kurulmaz. Bu karar
     değişecekse kullanıcıya sorulur (tüm dosyaları etkiler).
   - Eklenen ilgisiz paketleri kaldır: `npm uninstall cn next-themes` (`next-themes`'i shadcn'in `sonner`
     şablonu getirir; proje kendi `useTheme`'ini kullanır).
   - `recharts` aralığı `^3.8.1`'de kalsın (CLI değiştirirse geri al).
   - `"use client"` satırını sil (Vite/RSC yok).
   - asChild çocuğu olacaksa / ref alacaksa → forwardRef (§3.1).
   - Sabit İngilizce metinler (sr-only "Close", "Loading", "Toggle Sidebar", "Previous"/"Next", "More pages"…)
     → `t()` + TR/EN anahtarları `src/i18n/index.jsx`.
   - `z-50` → yüzen içerik modal içinde de açılacaksa çağrı yerinde `z-(--z-menu)` (§2.5).
   - CLI `globals.css`'e CSS değişkeni eklediyse: `.dark {…}` bloğunu `[data-theme="dark"]`'a taşı.
     **Mevcut kalıntı:** `globals.css:148-157`'de `add sidebar`'ın bıraktığı `.dark { --sidebar…: hsl(…) }`
     bloğu var; proje `.dark` sınıfı kullanmadığı için ölü kod (silinebilir ya da `[data-theme="dark"]`'a
     taşınabilir — koordinatörün kararı).
4. **Kapılar:** `npx eslint <dosyalar>`, ilgili vitest dosyaları + `cssClasses.test.js`, `cssTokens.test.js`,
   `rowAccessibleNames.test.js`, `css-hygiene.test.jsx`, `progress-guard.test.jsx` (§8.6).
5. **Asla `shadcn init` yeniden koşma.** Temmuz 2026'dan beri `init` varsayılan olarak **Base UI** seçiyor
   (Radix için `-b radix` gerekir) ve `globals.css`/`components.json`'u yeniden yazar.

### 3.4 Proje varyantları — hızlı başvuru (yerel dosyalardan)

| Bileşen | Varyantlar | Boyutlar |
|---|---|---|
| Button | default, secondary, outline, destructive, ghost, link, **success**, **warning** | default, xs, sm, lg, icon, icon-xs, icon-sm, icon-lg |
| Badge | default, secondary, destructive, outline, ghost, link, **warning** | — |
| Alert | default, destructive, **info**, **success**, **warning**, **danger** | — |
| DropdownMenuItem | default, destructive (+ `inset`) | — |
| TabsList | default, line | — |
| Toggle / ToggleGroup | default, outline | sm, default, lg; ToggleGroup `spacing` (yerel varsayılan **0**; resmî belgede 2026-05-17'den beri 2) |
| Item | default, outline, muted | default, sm |
| ItemMedia / EmptyMedia | default, icon (+ Item'da image) | — |
| Field | orientation: vertical, horizontal, responsive | — |
| NativeSelect | — | default, sm |
| InputGroupButton | Button varyantları (varsayılan ghost) | xs, sm, icon-xs, icon-sm |
| AlertDialogContent | — | default, sm |
| SidebarMenuButton | default, outline | default, sm, lg |
| Avatar | — | default, sm, lg |
| Switch | — | default, sm (dosyada `size`) |

---

## 4. Bileşen kataloğu

Resmî liste (Eylül 2026): Radix yolu `https://ui.shadcn.com/docs/components/radix/<ad>`. **65 madde**
(59 klasik + 6 yeni sohbet/anket bileşeni). "Kurulu" = `src/components/shadcn/<ad>.jsx` var (44 dosya).
"Kullanımda" = uygulama kodu içe aktarıyor (2026-09-25 anlık görüntüsü; 2. dalga sürdükçe değişir).

Genel kurulum: `yes n | npx shadcn@4.21.0 add <ad>` + §3.3. Belgedeki komutlar `pnpm dlx shadcn@latest add <ad>`
biçiminde; projede npm/npx ve sabit sürüm kullanılır.

### 4.1 Form ve girdi

| Bileşen (`ad`) | Ne işe yarar | Projede | site-monitor'de / yerini aldığı | Tuzak / not |
|---|---|---|---|---|
| Field (`field`) | Etiket + açıklama + hata + düzen tek aile: FieldSet, FieldLegend (legend/label), FieldGroup, Field (vertical/horizontal/responsive), FieldContent, FieldLabel, FieldTitle, FieldDescription, FieldSeparator, FieldError (`errors=[{message}]`) | Kurulu, kullanımda | `ui/Field.jsx` (render-prop: `{id, describedBy, invalid}`); `.form-field` yerine | Geçersizlik: Field'a `data-invalid`, kontrole `aria-invalid`. Devre dışı: Field'a `data-disabled`. shadcn Field kökü `role="group"` ve FieldError `role="alert"` basar — `ui/Field` ikisini de bilinçli kaldırır (tek kontrol için boş grup/alert gürültüsü). "Choice card": FieldLabel içine Field sar. `responsive` için FieldGroup `@container` |
| Button (`button`) | Eylem düğmesi | Kurulu, **138 dosyada** | 562 düğme codemod'la geçti; `.btn*` CSS silindi | Bağlantı: `<Button asChild><a …/></Button>` ya da `buttonVariants()` ile `<a>`. Yükleniyor: içine Spinner. Belge ikonlara `data-icon="inline-start|inline-end"` öneriyor (yerel Button bu özniteliğe özel stil taşımıyor; `has-[>svg]` dolgu ayarı var). Yeni kod küçük düğmeler arasında `gap` kullanır (§2.3 köprü) |
| Button Group (`button-group`) | Eylem düğmelerini bitişik grup; ButtonGroupSeparator, ButtonGroupText; `orientation` | Kurulu, kullanımda | `ui/BulkActionBar.jsx` (Duraklat/Sürdür; Input + Uygula) | `role="group"` → `aria-label`/`aria-labelledby` ver. Durum (seçili/değil) için **ToggleGroup**, eylem için ButtonGroup |
| Input (`input`) | Metin girdisi | Kurulu, kullanımda (forwardRef) | 433 ham `<input>` (3. aşama); TagInput, BulkActionBar, Dialog prompt | `aria-invalid` ile kırmızı çerçeve. `type="file"` desteklenir. Önek/sonek için InputGroup |
| Input Group (`input-group`) | İkon/metin/düğme/Kbd eklentili girdi: InputGroup, InputGroupAddon (`align` inline-start/inline-end/block-start/block-end), InputGroupButton, InputGroupText, InputGroupInput, InputGroupTextarea | Kurulu, kullanımda | Pano arama kutusu (`App.jsx:1266-1280`) | İçinde **`InputGroupInput`** kullan (düz Input değil — odak halkası grupta çizilir). Belge: odak sırası için addon DOM'da inputtan **sonra** gelmeli (`inline-start` yine de görsel olarak başa gelir, `order-first`). Üçüncü parti kontrole `data-slot="input-group-control"` |
| Input OTP (`input-otp`) | Tek kullanımlık kod girişi (`input-otp` paketi): InputOTP, InputOTPGroup, InputOTPSlot, InputOTPSeparator; `maxLength`, `pattern` (REGEXP_ONLY_DIGITS) | Kurulu değil | Bugün kullanım yok; 2FA/kod doğrulama eklenirse | Yeni bağımlılık getirir |
| Textarea (`textarea`) | Çok satırlı girdi | Kurulu, kullanımda (forwardRef) | 32 ham textarea (3. aşama); Dialog not/gerekçe | Field ile; `aria-invalid` |
| Checkbox (`checkbox`) | Onay kutusu (Radix; `checked` + `'indeterminate'`) | Kurulu, kullanımda | 103 ham checkbox (3. aşama); InboxBell | **`onChange(e.target.checked)` → `onCheckedChange(checked)`**; testte `.checked` yerine `toBeChecked()`. Etiket: FieldLabel/Label `htmlFor` |
| Radio Group (`radio-group`) | Tek seçim radyo kümesi; `value`/`onValueChange`, `orientation` | Kurulu, kullanılmıyor | `WeeklyReportsPage.jsx:1726` elle `role="radiogroup"` + `type="radio"` | FieldSet + FieldLegend ile grupla. Az sayıda, kısa seçenek + anında görünür olsun isteniyorsa; kompakt çubuk için ToggleGroup |
| Select (`select`) | Özel çizimli seçim listesi (Radix): SelectTrigger (`size` sm/default), SelectValue, SelectContent (`position` item-aligned/popper), SelectGroup/Label/Item/Separator, Scroll düğmeleri | Kurulu, kullanılmıyor | — | **Boş dize (`""`) öğe değeri olamaz.** Modal içinde `z-50` sorunu (§2.5). jsdom'da açmak pointer capture API'si ister (setup.js'te yok, §8). Varsayılan tercih **NativeSelect** |
| Native Select (`native-select`) | Stilli yerel `<select>`: NativeSelect, NativeSelectOption, NativeSelectOptGroup; `size` | Kurulu (forwardRef), henüz kullanılmıyor | 24 ham `<select>` (3. aşama) | Belge: yerel davranış/performans/mobil için NativeSelect, zengin öğe içeriği için Select. Yerel dosyada `className` **`<select>`'e** gider, sarmalayıcı `w-fit` — tam genişlik için sarmalayıcı düzenini kontrol et |
| Switch (`switch`) | Açık/kapalı anahtar; `checked`/`onCheckedChange`, `size` sm/default | Kurulu, kullanılmıyor | Elle `role="switch"`: BrandingSettings:218, InventoryFormModal:593, MyAuditLog:84, TeamManager:53, WeeklyReportAccessSettings:23, UserPushSettings:290 | Field `orientation="horizontal"` ile; "choice card" deseni. Anında etki eden ayar için Switch, form gönderimi bekleyen onay için Checkbox |
| Slider (`slider`) | Aralık seçici (Radix); `value`/`defaultValue` **dizi**, `onValueChange`, `onValueCommit`, min/max/step, orientation | **Kurulu değil** | 4 ham `<input type="range">`: `ui/IntervalSlider.jsx:44`, KeywordMonitorPage:901, PortMonitorPage:852, admin/StormSettings:140 | Değer tek başparmakta da dizi (`[33]`). Başparmağa erişilebilir ad ver |
| Calendar (`calendar`) | Tarih seçimi (react-day-picker): `mode` single/range/multiple, `selected`/`onSelect`, `captionLayout="dropdown"`, `numberOfMonths`, `disabled`, `locale`, `weekStartsOn`, `timeZone` | Kurulu, henüz kullanılmıyor (2. dalga F sürüyor) | `ui/DateTimeField`, `TimeRangePicker`, `DateTimeRangePicker`, `WeekDatePicker`, `MonthCalendar` → Calendar + Popover; react-datepicker kalkacak | TR/EN için date-fns `tr`/`enGB` locale, Pazartesi başlangıç, min/max, Europe/Istanbul davranışı korunmalı. Popover içinde `z-(--z-menu)` |
| Date Picker (`date-picker`) | **Bileşen değil, desen:** Popover + Calendar (+ date-fns biçimlendirme); tek, aralık, hazır ön ayarlı, doğum tarihi (dropdown başlık), input'lu, tarih+saat örnekleri | Desen (parçaları kurulu) | Yukarıdaki tarih seçicileri | Seçimde popover'ı kapat; saat için Input/NativeSelect |
| Combobox (`combobox`) | Aranabilir seçim. Resmî sürüm **Base UI** üzerine (Radix sayfasında bile): ComboboxInput, ComboboxContent, ComboboxList, ComboboxItem, ComboboxEmpty, ComboboxChips/Chip/ChipsInput (çoklu), ComboboxGroup/Label/Collection/Separator; `items`, `itemToStringValue`, `multiple`, `showClear`, `autoHighlight` | Kurulu, **bilinçli olarak kullanılmıyor** | SearchableSelect (254 kullanım) / MultiTeamSelect klasik desenle: **Popover + Command + Button** (`ui/PickerPopover.jsx`) | Gerekçe (`ui/PickerPopover.jsx:13-16`): ModalShell Radix Dialog; Base UI listesi Radix katman yığınını tanımaz → listedeki Escape **pencereyi** kapatır. Radix Popover aynı yığında: Escape önce listeyi kapatır. Base UI Combobox'ı Radix modal içinde kullanma |
| Label (`label`) | Kontrolle ilişkili etiket (`htmlFor`) | Kurulu, kullanımda | Login, Dialog | Formlarda belge Field/FieldLabel öneriyor |

### 4.2 Yerleşim ve gezinme

| Bileşen | Ne işe yarar | Projede | site-monitor'de / yerini aldığı | Tuzak / not |
|---|---|---|---|---|
| Accordion (`accordion`) | Başlıklı, açılır bölümler: Accordion (`type` single/multiple, `collapsible`, `defaultValue`/`value`), AccordionItem, AccordionTrigger, AccordionContent | Kurulu, kullanılmıyor | Çok bölümlü rehber/SSS, ayar bölümleri | Tek bir açılır alan için Collapsible yeterli |
| Breadcrumb (`breadcrumb`) | Hiyerarşik yol: BreadcrumbList/Item/Link (`asChild`)/Page/Separator/Ellipsis | Kurulu değil | Bugün yok; derin detay sayfaları için aday | Geçerli sayfa BreadcrumbPage (`aria-current`) |
| Navigation Menu (`navigation-menu`) | Site gezinme bağlantıları + açılır paneller | Kurulu değil | Uygulama gezinmesi **Sidebar** ile | Uygulama kabuğu için kullanma |
| Sidebar (`sidebar`) | Uygulama kenar çubuğu: SidebarProvider, Sidebar (`side`, `variant` sidebar/floating/inset, `collapsible` offcanvas/icon/none), Header/Content/Footer, Group/GroupLabel/GroupAction/GroupContent, Menu/MenuItem/MenuButton (`isActive`, `tooltip`, `size`, `asChild`)/MenuAction/MenuBadge/MenuSub*/MenuSkeleton, Rail, Inset, Trigger; `useSidebar()` | Kurulu, kullanımda | `components/Nav.jsx` (`collapsible="icon"`), App.jsx `SidebarProvider` (open ↔ `localStorage 'sidebar-open'`) + `SidebarInset`, mobil başlıkta `SidebarTrigger`, InboxBell | `useSidebar()` sağlayıcı dışında **fırlatır** → testte `withSidebar(ui)` (§8). Hook'u App.jsx'te auth erken-return'lerinden **önce** koy. Kısayol Ctrl/⌘+B (projede yazı alanında kapalı). Genişlik: `--sidebar-width` 16rem, mobil 18rem, ikon 3rem. Yazdırmada `[data-slot="sidebar"]` gizli |
| Tabs (`tabs`) | Sekmeli paneller: Tabs (`value`/`onValueChange`/`defaultValue`, `orientation`, `activationMode`), TabsList (`variant` default/line), TabsTrigger, TabsContent | Kurulu, kullanımda (InboxBell) | Elle `role="tablist"`: admin/AdminSettings:95, monitoring/MaintenanceTargetPicker:76, ui/TeamMembersModal:54; `.tab-content` | Etkin olmayan TabsContent **DOM'dan çıkar** (durum/odak kaybolur) — korumak için `forceMount` + gizleme. Uygulama geneli `tab` URL parametresi uygulamanındır; sayfa sekmesini URL'e yazarken önekli anahtar kullan |
| Separator (`separator`) | Görsel/anlamsal ayraç; `orientation`, `decorative` | Kurulu, kullanımda | VersionPopover; Field/Item/ButtonGroup içinde | — |
| Scroll Area (`scroll-area`) | Tarayıcılar arası özel kaydırma çubuğu: ScrollArea, ScrollBar (`orientation`) | Kurulu, kullanılmıyor | — | **Sabit yükseklik şart**, yoksa kaydırmaz. Uzun formlarda ModalShell `scrollBody` zaten var |
| Resizable (`resizable`) | Boyutlandırılabilir paneller (`react-resizable-panels` v4): ResizablePanelGroup (`orientation`), ResizablePanel (`defaultSize` "50%" biçimi, `minSize`), ResizableHandle (`withHandle`) | Kurulu değil | Bugün yok (ör. SQL/kod düzenleyici bölmesi için aday) | v4'te `direction` → `orientation`; yeni bağımlılık |

### 4.3 Katmanlar ve pencereler

| Bileşen | Ne işe yarar | Projede | site-monitor'de / yerini aldığı | Tuzak / not |
|---|---|---|---|---|
| Dialog (`dialog`) | Modal pencere: Dialog (`open`/`onOpenChange`/`modal`), Trigger, Content (`showCloseButton`), Header, Title, Description, Footer (`showCloseButton`), Close | Kurulu, kullanımda | **`ui/ModalShell.jsx`** (yeni modallar BURADAN), CommandPalette, Login; 39 ham `.modal-overlay` (3. aşama) | `DialogTitle` zorunlu; açıklama yoksa `aria-describedby={undefined}`. ModalShell `modal={false}` + kendi scrim'i kullanır: Radix modal kipi `body`'ye `pointer-events:none` koyup portal'lı iç içe öğeleri (üstte açılan modal, HelpTip, takvim) tıklanamaz yapıyordu. Derinlik başına z +10. Menü öğesinden dialog açarken menüye `modal={false}` (belge) |
| Alert Dialog (`alert-dialog`) | Kesintili onay penceresi: Content (`size` default/sm), Header, Media, Title, Description, Footer, Action, Cancel | Kurulu, kullanımda | **`ui/Dialog.jsx` `useDialog()`**: `showConfirm`/`showPrompt`/`showAlert`/`showNoteConfirm` (Promise döner) | Dış tıklamayla kapanmaz, varsayılan odak İptal'dedir — proje ilk odağı **onay** düğmesine verir, örtü tıklaması = İptal, `role` yalnız `alert` tipinde `alertdialog`. Doğrudan AlertDialog yazma; `useDialog` kullan |
| Sheet (`sheet`) | Kenardan kayan panel (Radix Dialog tabanlı): `side` top/right/bottom/left, `showCloseButton` | Kurulu, kullanımda | InboxBell paneli; Sidebar mobil | `SheetTitle` zorunlu. Katman `z-50` |
| Drawer (`drawer`) | Mobil dostu alt çekmece (**Vaul**), `direction` | Kurulu değil | Masaüstü ağırlıklı uygulama → Sheet yeterli | Yeni bağımlılık; belge: masaüstünde Dialog + mobilde Drawer deseni |
| Popover (`popover`) | Tetikle açılan zengin yüzen içerik: Trigger, Content (`align`, `side`, `sideOffset`), Anchor, Header/Title/Description | Kurulu, **6 dosyada** | HelpTip, VersionChip, PickerPopover (SearchableSelect/MultiTeamSelect), TagInput | Portal'a çizer; modal içinde `z-(--z-menu)`. Etkileşimli içerik (form, liste) → Popover; salt ipucu → Tooltip |
| Tooltip (`tooltip`) | Kısa ipucu: TooltipProvider, Tooltip, TooltipTrigger, TooltipContent (`side`, `sideOffset`) | Kurulu; yalnız Sidebar içinden | SidebarMenuButton `tooltip` | **`TooltipProvider` şart** (belge: "Add the TooltipProvider to the root of your app"). Projede tek sağlayıcı `SidebarProvider`'ın içinde → Login ya da Sidebar dışında render edilen ağaçlarda/testlerde "`Tooltip` must be used within `TooltipProvider`" hatası. Devre dışı düğmeye ipucu: düğmeyi `<span>`'a sar. Temel bilgi taşıma (dokunmatikte hover yok). Modal içinde `z-50` → görünmez; `className="z-(--z-menu)"` |
| Hover Card (`hover-card`) | Bağlantı önizlemesi (yalnız görenler için); `openDelay`/`closeDelay` | Kurulu, kullanılmıyor | — | **Klavye/dokunmatik erişemez** → önemli içerik için Popover |
| Context Menu (`context-menu`) | Sağ tık / uzun basış menüsü | Kurulu değil | Bugün yok | Keşfedilemez; eylem her zaman görünür bir yoldan da erişilebilir olmalı |
| Dropdown Menu (`dropdown-menu`) | Düğmeyle açılan eylem menüsü: Group, Label, Item (`variant` destructive, `inset`), CheckboxItem, RadioGroup/RadioItem, Sub/SubTrigger/SubContent, Shortcut, Separator; `modal`, `align`, `side` | Kurulu, kullanımda | **`ui/KebabMenu.jsx`** (tablo/kart "İşlem" menüsü), Nav kullanıcı menüsü | Projede `modal={false}` (açık menü varken başka tetiğe tek basışta geçiş). `z-(--z-menu)`. Eylem modal açıyorsa `onCloseAutoFocus` ile odak iadesini engelle (KebabMenu kalıbı). Satır tıklamasına sızmasın: hücrede `stopPropagation` |
| Menubar (`menubar`) | Masaüstü uygulaması menü çubuğu | Kurulu değil | Uygun kullanım yok | — |
| Command (`command`) | Arama + hızlı eylem listesi (**cmdk**): Command (`shouldFilter`, `filter`, `loop`), CommandDialog, Input, List, Empty, Group, Item (`value`, `keywords`, `onSelect`), Shortcut, Separator | Kurulu, **5 dosyada** | CommandPalette (Dialog + Command, `shouldFilter={false}`), SearchableSelect/MultiTeamSelect listesi | cmdk öğe değeri boş dize olamaz (projede `o:` önekli değer). Kendi süzgecin varsa `shouldFilter={false}`. Açılışta odağı `[cmdk-input]`'a ya da köke ver (PickerContent) |

### 4.4 Geri bildirim ve durum

| Bileşen | Ne işe yarar | Projede | site-monitor'de / yerini aldığı | Tuzak / not |
|---|---|---|---|---|
| Alert (`alert`) | Satır içi dikkat kutusu: Alert (`variant`), AlertTitle, AlertDescription (+ belgede AlertAction) | Kurulu, kullanımda | **`ui/AlertBanner.jsx`** (`tone` info/success/warning/danger; ~93 çağrı yeri) — `.alert-msg`/`.alert-banner*` yerine | shadcn Alert kendi başına `role="alert"` basar; AlertBanner varsayılanı `status`. Bir ekranda tek `alert` |
| Toast (`toast`) | Radix tarafında **kullanımdan kalktı** — belge: "The toast component has been deprecated. Use the sonner component instead." (Temmuz 2026'da **Base UI** için yeni bir Toast çıktı; Radix projesini ilgilendirmez) | Kurulu değil (doğru) | — | Kurma |
| Sonner (`sonner`) | Bildirim (toast) — `<Toaster />` + `toast()` / `toast.success/error/info/warning/promise`; Toaster: `position`, `richColors`, `closeButton`, `expand`, `visibleToasts` | Kurulu, kullanımda | **`ui/Toast.jsx`** `ToastProvider` / `useToast()` (`success/error/info/dismiss`) | **`sonner`'dan doğrudan `toast()` çağırma** — `useToast()` kullan: yinelenen mesajı "×N" sayar, en fazla 4 kutu, kendi zamanlayıcıları, `toasterId` süzgeci |
| Progress (`progress`) | Belirli ilerleme çubuğu (Radix) | Kurulu, kullanımda | **`ui/Progress.jsx`** `ProgressBar` (`tone` ok/warn/crit) — `.pg-bar*` yerine | Belirsizse `value` verme (aria-valuenow yok). Halka için shadcn karşılığı yok → `ProgressRing` |
| Spinner (`spinner`) | Belirsiz yükleniyor simgesi (lucide Loader2, `role="status"`) | Kurulu, kullanımda | **`ui/Progress.jsx`** `Spinner` / `LoadingBlock` — `.pg-spinner` yerine | Düğme içinde `decorative` + düğmeye `aria-busy`. Boyut `size-*` |
| Skeleton (`skeleton`) | İçerik gelene kadar yer tutucu (`animate-pulse` blok) | Kurulu; Sidebar içinde | Liste/kart iskeletleri için aday | Yer tutucu gerçek yerleşimle aynı boyutta olsun (zıplama). Ekran okuyucuya ayrıca `role="status"` metni ver |
| Badge (`badge`) | Etiket/durum rozeti; `asChild` ile bağlantı/düğme | Kurulu, **11 dosyada** | TeamBadge (`ghost`), UserBadge, MaintenanceBadge (`warning`), Toast ×N, CommandPalette — `.badge*`, `.card-badge` yerine | Tıklanabilir rozet: `<Badge asChild><button …/></Badge>`; button içinde button olmaz (TeamBadge `as="span"` + `role="button"`) |
| Empty (`empty`) | Boş durum: Empty, EmptyHeader, EmptyMedia (`variant` default/icon), EmptyTitle, EmptyDescription, EmptyContent | Kurulu, kullanımda | **`ui/StatusBlock.jsx`** (`tone`, `loading`) — `.empty-state`, `.status-block*` yerine | Kenarlık için `border` + (varsayılan) `border-dashed` sınıfı ekle |

### 4.5 Görüntüleme ve medya

| Bileşen | Ne işe yarar | Projede | site-monitor'de / yerini aldığı | Tuzak / not |
|---|---|---|---|---|
| Avatar (`avatar`) | Kullanıcı görseli + yedek: Avatar (`size`), AvatarImage, AvatarFallback, AvatarBadge, AvatarGroup, AvatarGroupCount | Kurulu, kullanımda | Nav kullanıcı düğmesi | Fotoğraf dönmeyen uçlarda AvatarFallback (baş harfler) |
| Card (`card`) | Kart: Card, CardHeader, CardTitle, CardDescription, CardAction, CardContent, CardFooter | Kurulu, kullanılmıyor | Pano/izleme kartları (3. aşama) — `.card-header`/`.card-title`/`.card-footer`, `.upt-card` vb. | Legacy `card-*` sınıflarını CardHeader'a **ekleme** (katmansız CSS ezer). Belgede `size="sm"` var; yerel dosyada yok |
| Table (`table`) | Anlamsal tablo: Table (kaydırmalı kap `data-slot="table-container"`), Header, Body, Footer, Row, Head, Cell, Caption | Kurulu, kullanılmıyor | ~90 ham tablo (3. aşama) | Seçili satır `data-state="selected"`. Satır eylemi → KebabMenu |
| Data Table (`data-table`) | **Bileşen değil, kılavuz:** Table + `@tanstack/react-table` (sıralama, süzme, sayfalama, sütun görünürlüğü, satır seçimi; DataTableColumnHeader / DataTablePagination / DataTableViewOptions) | Kurulu değil; paket yok | Projede sıralama/sayfalama ekran başına yazılı + PaginationBar | Yeni bağımlılık — kullanıcı kararı |
| Chart (`chart`) | Recharts **v3** üzeri ince katman: ChartContainer, ChartConfig (`label`, `color` / `theme {light,dark}`), ChartTooltip(+Content), ChartLegend(+Content); `accessibilityLayer` | Kurulu, kullanılmıyor | Mevcut recharts grafikleri (3. aşama) | ChartContainer'a **`min-h-*` ya da `aspect-*` şart** (yoksa yükseklik 0). Renk `var(--chart-N)` / `var(--color-<anahtar>)` — `hsl(var(--chart-1))` yazma (v4). Recharts bileşenleri sarılmaz, doğrudan kullanılır |
| Carousel (`carousel`) | Kaydırmalı slayt (**Embla**): Carousel (`orientation`, `opts`, `setApi`, `plugins`), Content, Item, Previous, Next | Kurulu değil | Bugün yok | Yeni bağımlılık |
| Aspect Ratio (`aspect-ratio`) | İçeriği oranda tut (`ratio={16/9}`) | Kurulu değil | Bugün yok | Tailwind `aspect-video`/`aspect-square` çoğu zaman yeter |
| Typography (`typography`) | Kurulabilir bileşen değil; başlık/paragraf/liste/alıntı/inline code için **sınıf örnekleri** sayfası. Markdown gövdesi için ayrıca `shadcn/typeset` (Temmuz 2026) | — | MarkdownEditor/whitepaper gövdeleri kendi CSS'inde | Uzun metin için ayrı karar |
| Item (`item`) | Genel liste satırı: ItemGroup, ItemSeparator, Item (`variant`, `size`, `asChild`), ItemMedia, ItemContent, ItemTitle, ItemDescription, ItemActions, ItemHeader, ItemFooter | Kurulu, kullanılmıyor | Bildirim/üye/cihaz listeleri için aday (InboxBell satırları, TeamMemberCards, DeviceHistoryPanel) | Belge: form kontrolleri için **Field**, form dışı içerik satırı için **Item**. Belgede `size="xs"` var; yerel dosyada default/sm |
| Kbd (`kbd`) | Klavye tuşu gösterimi: Kbd, KbdGroup | Kurulu, kullanımda | CommandPalette | Tooltip içinde otomatik ton alır |

### 4.6 Çeşitli

| Bileşen | Ne işe yarar | Projede | site-monitor'de / yerini aldığı | Tuzak / not |
|---|---|---|---|---|
| Collapsible (`collapsible`) | Tek panel aç/kapa: Collapsible (`open`/`onOpenChange`/`defaultOpen`), Trigger, Content (`forceMount`) | Kurulu, kullanımda (forwardRef tetik) | Nav grupları; açılır çubuklar (`.stats-collapse-bar`, `.alh-group-head` — 3. aşama) | `forceMount` içeriği HEP açık çizer → kapalıyken `hidden={!open}` senin işin (`Nav.jsx:292`). Ürün turu `data-tour` kancaları bu sayede DOM'da kalır |
| Toggle (`toggle`) | İki durumlu düğme: `pressed`/`onPressedChange`, `variant`, `size` | Kurulu (ToggleGroup'un parçası) | — | Yalnız ikonluysa `aria-label` şart |
| Toggle Group (`toggle-group`) | Durum düğmeleri kümesi: `type` single/multiple (**zorunlu**), `value`/`onValueChange`, `variant`, `size`, `spacing`, `orientation` | Kurulu, **3 dosyada** | `ui/SegmentedControl.jsx` (eski `role=group` + `aria-pressed` sözleşmesi korunarak), Pano Compact/Rich, PaginationBar | `type="single"`'da etkin öğeye tekrar basmak değeri **`""`** yapar → boşu yok say: `onValueChange={(v) => v && set(v)}`. Bitişik görünüm `spacing={0}` (yerel varsayılan zaten 0) |
| Pagination (`pagination`) | Sayfa gezinme: Pagination, Content, Item, Link (`isActive`), Previous, Next, Ellipsis | Kurulu, kullanımda | **`ui/PaginationBar.jsx`** (+ ToggleGroup + Input) — `.pgn`/`.pg-*` yerine | Projede Previous/Next metni `label` prop'u, `Pagination`'a i18n `aria-label` (sabit İngilizce kaldırıldı) |
| Direction (`direction`) | `DirectionProvider` / `useDirection` — RTL desteği | Kurulu değil | Uygulama yalnız LTR (TR/EN) | Gerek yok |

### 4.7 Yeni bileşenler (2026) — bu proje için öncelik düşük

| Bileşen | Ne işe yarar | Not |
|---|---|---|
| Message Scroller (`message-scroller`) | Sohbet kaydırma kabı (Haziran 2026) | AI sohbet arayüzleri içindir; `@shadcn/react` başsız paketi |
| Message (`message`) | Sohbet satırı (avatar, hizalama, başlık/altlık) | — |
| Bubble (`bubble`) | Mesaj balonu yüzeyi | — |
| Attachment (`attachment`) | Dosya/görsel eki, yükleme durumu | Dosya yükleme yüzeyi gerekirse incelenebilir |
| Marker (`marker`) | Durum notu / tarih ayracı | — |
| Questionnaire (`questionnaire`) | Çok adımlı soru bileşeni (Ağustos 2026) | Sihirbaz tipi form gerekirse incelenebilir |

**Katalog özeti:** 65 madde · 44'ü kurulu (`src/components/shadcn/*.jsx`) · kurulu olmayan 21: aspect-ratio,
breadcrumb, carousel, context-menu, data-table (kılavuz), date-picker (desen; parçaları Calendar + Popover
kurulu), direction, drawer, input-otp, menubar, navigation-menu, resizable, slider, toast (kalktı), typography
(sayfa) ve 6 yeni sohbet/anket bileşeni. Kurulu olup uygulama kodunun henüz içe aktarmadığı 16 dosya:
accordion, calendar, card, chart, combobox (bilinçli), hover-card, item, native-select, radio-group,
scroll-area, select, skeleton, switch, table, toggle, tooltip (bunlardan skeleton ve tooltip `sidebar.jsx`'in,
toggle `toggle-group.jsx`'in içinden dolaylı olarak kullanılıyor).

---

## 5. İhtiyaç → bileşen karar rehberi

| İhtiyaç | Kullan | Not |
|---|---|---|
| Silme/geri alınamaz işlem onayı | `useDialog().showConfirm()` (AlertDialog) | `variant: 'danger'`; Promise `true/false` |
| Kullanıcıdan tek metin istemek | `useDialog().showPrompt()` | Promise metin/null |
| Gerekçe notlu onay | `useDialog().showNoteConfirm()` | `{confirmed, note}`; kural `NOTE_RULE` |
| Form içeren pencere | `ui/ModalShell` (Dialog) | `size` sm/md/lg/xl/full; uzun formda `scrollBody` + `footer`; emek biriktiren formda `dismissOnBackdrop={false}`; gönderirken `busy` |
| Yan panel / çekmece | Sheet | `side`; `SheetTitle` zorunlu |
| İşlem sonucu bildirimi | `useToast()` (Sonner) | `success/error/info`; doğrudan `sonner` import etme |
| Satır içi uyarı/hata/bilgi şeridi | `ui/AlertBanner` (Alert) | `tone`; `role` varsayılan `status` |
| Boş liste / hata ekranı / sonuç yok | `ui/StatusBlock` (Empty) | `tone`, `icon`, `actions` |
| Bölüm yükleniyor | `ui/Progress` → `LoadingBlock` | Yerleşim korunacaksa Skeleton |
| Düğme içinde yükleniyor | `Spinner decorative` + düğmeye `aria-busy` | — |
| Belirli ilerleme | `ui/Progress` → `ProgressBar` (dar alanda `ProgressRing`) | — |
| İçerik iskeleti | Skeleton | Gerçek boyutla aynı |
| Form alanı (etiket + ipucu + hata) | `ui/Field` (shadcn Field) | Render-prop ile `id`/`aria-describedby`/`aria-invalid` |
| Alan grupları / bölüm başlığı | FieldSet + FieldLegend, FieldGroup | — |
| Metin girdisi | Input | Önek/sonek/ikon/düğme → InputGroup |
| İkonlu arama kutusu | InputGroup + InputGroupInput + InputGroupAddon | Addon DOM'da input'tan sonra |
| Çok satırlı metin | Textarea | — |
| Küçük, sabit seçenekli seçim | NativeSelect | Zengin öğe içeriği gerekirse Select |
| Aranabilir seçim (tek) | `ui/SearchableSelect` (Popover + Command) | `creatable`, gruplar, `collapsibleGroups` |
| Aranabilir çoklu seçim | `ui/MultiTeamSelect` / `ui/NotificationGroupSelect` | Etiketler Badge |
| Serbest etiket girişi | `ui/TagInput` | — |
| 2–5 seçenekli tek seçim (kompakt) | ToggleGroup (`ui/SegmentedControl`) | Anlık görünüm değişimi |
| Tek seçim, açıklamalı seçenekler | RadioGroup + Field ("choice card") | — |
| Açık/kapalı ayar | Switch | Field `horizontal` |
| Çoklu seçim / onay | Checkbox | `onCheckedChange` |
| Sayısal aralık | Slider (kurulacak) | Değer dizi |
| Tarih | Calendar + Popover (Date Picker deseni) | date-fns locale |
| Tarih + saat / aralık | Calendar (`mode="range"`) + Input/NativeSelect | Europe/Istanbul davranışını koru |
| Sekmeler | Tabs | URL parametre ad alanına dikkat |
| Tek açılır bölüm | Collapsible | `forceMount` + `hidden` tuzağı |
| Birden çok açılır bölüm | Accordion | `type="multiple"` |
| Satır/kart eylem menüsü | `ui/KebabMenu` (DropdownMenu) | `rowLabel` ile satırı ayırt eden ad |
| Genel açılır menü | DropdownMenu (`modal={false}`, `z-(--z-menu)`) | — |
| Kısa ipucu (hover/odak) | Tooltip | TooltipProvider gerekir; temel bilgi taşımaz |
| Tıklayınca açılan yardım/bilgi balonu | `ui/HelpTip` (Popover) | — |
| Zengin yüzen içerik (form, liste) | Popover | `z-(--z-menu)` |
| Komut paleti / hızlı arama | CommandDialog deseni (CommandPalette) | `shouldFilter={false}` + kendi süzgecin |
| Tablo | Table | Sıralama/süzme gerekiyorsa Data Table kılavuzu (yeni paket = kullanıcı kararı) |
| Sayfalama | `ui/PaginationBar` (Pagination) | — |
| Grafik | Chart (ChartContainer + recharts) | `min-h-*` şart |
| Klavye kısayolu gösterimi | Kbd / KbdGroup | — |
| Bitişik eylem düğmeleri | ButtonGroup | Durum için ToggleGroup |
| Durum/etiket rozeti | Badge (takım adı → `ui/TeamBadge`) | — |
| Liste satırı (ikon + başlık + açıklama + eylem) | Item | Form satırı değilse |
| Kart | Card | — |
| Kullanıcı görseli | Avatar | — |
| Ayraç | Separator | — |
| Kaydırılabilir kutu (özel çubuk) | ScrollArea | Sabit yükseklik |
| Uygulama gezinmesi | Sidebar (`components/Nav.jsx`) | — |

---

## 6. Theming

### 6.1 Jetonlar (`src/styles/globals.css`)

Kural (belge): yüzey jetonu + `-foreground` çifti. `bg-primary text-primary-foreground`, `bg-card
text-card-foreground`… Yüzey jetonunda "background" soneki yazılmaz.

| Jeton | Açık | Koyu (`[data-theme="dark"]`) | Not |
|---|---|---|---|
| `--radius` | 0.625rem | — | `--radius-sm/md/lg/xl` = `calc(var(--radius) - 4px / - 2px / 0 / + 4px)` (eski shadcn formülü; güncel belge çarpımsal ölçek ve 2xl–4xl kullanıyor) |
| `--background` / `--foreground` | #ffffff / #09090b | #09090b / #fafafa | |
| `--card` / `--card-foreground` | #ffffff / #09090b | #18181b / #fafafa | |
| `--popover` / `--popover-foreground` | #ffffff / #09090b | #18181b / #fafafa | |
| `--primary` / `--primary-foreground` | `var(--brand-primary, #2563eb)` / #fff | `var(--brand-primary, #3b82f6)` / #fff | **Marka mavisi**; `--brand-primary`'yi BrandingProvider yazar (`contexts/BrandingProvider.jsx:36`). App.css'teki `--primary` ile ortak ad, aynı değer |
| `--secondary` / `-foreground` | #f4f4f5 / #18181b | #27272a / #fafafa | zinc |
| `--muted` / `--muted-foreground` | #f4f4f5 / #71717a | #27272a / #a1a1aa | |
| `--accent` / `-foreground` | #f4f4f5 / #18181b | #27272a / #fafafa | |
| `--destructive` | #dc2626 | #ef4444 | `-foreground` jetonu yok; düğme `text-white` |
| `--border` / `--input` | #e4e4e7 / #e4e4e7 | #27272a / #3f3f46 | `--border` App.css ile ortak ad |
| `--ring` | `color-mix(in srgb, var(--primary) 55%, transparent)` | (aynı formül) | |
| `--chart-1…5` | #2563eb, #16a34a, #f59e0b, #dc2626, #8b5cf6 | koyuda ezilmiyor | |
| `--sidebar`, `--sidebar-foreground`, `--sidebar-primary(-foreground)`, `--sidebar-accent(-foreground)`, `--sidebar-border`, `--sidebar-ring` | #fafafa, #3f3f46, `var(--primary)`… | #18181b, #d4d4d8… | |
| `--color-success` / `--color-warning` | App.css `--success` (#16a34a) / `--warning` (#f59e0b) (`App.css:8-9`) | koyuda ayrı değer tanımlı değil (aynı renk) | `@theme inline`'da eşlendi → `bg-success`, `text-warning`… (`-foreground` çifti yok) |

`@theme inline` bloğu her jetonu `--color-*` olarak Tailwind'e açar (`bg-primary`, `text-muted-foreground`,
`border-border`, `ring-ring`, `bg-sidebar-accent`, `fill-chart-1`…). Değerler hex (belge varsayılanı OKLCH;
hex de geçerli).

### 6.2 Yeni renk ekleme

Belgedeki desen, projenin koyu tema seçicisine uyarlanmış hâli:

```css
/* globals.css */
:root              { --info: #2563eb; --info-foreground: #ffffff; }
[data-theme="dark"]{ --info: #3b82f6; --info-foreground: #ffffff; }   /* .dark DEĞİL */

@theme inline {
  --color-info: var(--info);
  --color-info-foreground: var(--info-foreground);
}
```

Kullanım: `className="bg-info text-info-foreground"`. Tanımsız bir `var(--x)` özelliği sessizce düşürür →
`cssTokens.test.js` yakalar; yeni jetonu hem `:root`'a hem koyu bloğa yaz. App.css'te aynı adlı bir değişken
varsa (ör. `--success`) iki kaynak açmak yerine `@theme inline`'da ona bağlan (projenin `--color-success`
kalıbı).

### 6.3 Varyant ekleme (cva) — shadcn'in önerdiği yol

Bileşen dosyasındaki `cva` tablosunu genişlet; ayrı bir "özel" bileşen yazma:

```jsx
// shadcn/button.jsx → buttonVariants.variants.variant
success: "bg-success text-white hover:bg-success/90 focus-visible:ring-success/30",
warning: "bg-amber-600 text-white hover:bg-amber-600/90 focus-visible:ring-amber-600/30",
```

- Koyu karşılığı `dark:` yardımcısıyla aynı satırda ver (Alert varyantları örnek).
- Kök öğeye `data-variant={variant}` / `data-size={size}` yazılıyorsa testler varyantı sınıftan değil bu
  öznitelikten sorgular.
- Varyantı ekledikten sonra §3.4 tablosunu güncelle.

### 6.4 Durum renkleri

Başarı/uyarı/hata/bilgi tonları için tek kaynak proje varyantlarıdır: Button `success|warning|destructive`,
Badge `warning|destructive`, Alert `info|success|warning|danger`, StatusBlock `tone`, ProgressBar `tone`.
Ekranda elle `bg-green-100 text-green-800` benzeri ton yazmadan önce bunlara bak.

---

## 7. Formlar

- **Belge:** formlar Field ailesiyle kurulur; desteklenen kütüphaneler React Hook Form, TanStack Form,
  Formisch (+ Next.js Server Actions). Eski `form.tsx` (`FormField`/`FormItem`/`FormControl`) yeni belgede
  yer almıyor — **kullanma**.
  - RHF deseni: `<Controller render={({field, fieldState}) => <Field data-invalid={fieldState.invalid}>
    <FieldLabel htmlFor={field.name}/> <Input {...field} id={field.name} aria-invalid={fieldState.invalid}/>
    {fieldState.invalid && <FieldError errors={[fieldState.error]}/>}</Field>}/>`.
  - TanStack Form deseni: `isInvalid = field.state.meta.isTouched && !field.state.meta.isValid`;
    `<FieldError errors={field.state.meta.errors}/>`.
- **Projede bugün:** form kütüphanesi **yok** (package.json'da RHF/TanStack Form/zod yok). Durum `useState` ile
  ekran başına; alan bağları `ui/Field.jsx` render-prop'uyla:

  ```jsx
  <Field label={t('x.name')} required hint={t('x.nameHint')} error={errors.name}>
    {({ id, describedBy, invalid }) => (
      <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={v} onChange={…} />
    )}
  </Field>
  ```

  Zorunluluk yıldızı i18n metnine gömülmez (`data-slot="field-required"`). Alan hatası `role="alert"`
  taşımaz (kontrol zaten `aria-describedby` ile bağlı).
- Radix kontrollerinde olay adı farklıdır: Checkbox/Switch `onCheckedChange(bool)`, RadioGroup/Select/Tabs/
  ToggleGroup `onValueChange(value)`, Slider `onValueChange(number[])`.
- Yeni bir form kütüphanesi eklemek bağımlılık kararıdır → kullanıcıya sorulur.
- Doğrulama/sunucu eşikleri: arayüz anında geri bildirim verir, garanti sunucudadır (ör. `NOTE_RULE`
  backend ile aynı eşik, `ui/Dialog.jsx:43`).

---

## 8. Test (vitest + jsdom)

### 8.1 Sağlayıcılar

- `src/test/test-utils.jsx` `render`'ı Theme + Lang + Toast + Dialog sağlayıcılarıyla sarar.
  **SidebarProvider / TooltipProvider sarmaz.**
- `useSidebar()` kullanan (Nav, InboxBell, SidebarMenuButton) ya da `Tooltip` içeren bileşeni sınarken:
  `import { withSidebar } from './helpers/sidebar.jsx'` → `render(withSidebar(<Nav …/>))`
  (`SidebarProvider` içeride `TooltipProvider` da kurar). Sidebar dışındaki bir Tooltip için teste
  `<TooltipProvider>` sar.

### 8.2 Sorgulama tercihi

1. **Rol + erişilebilir ad:** `getByRole('button', {name})`, `getByRole('dialog')`, `'alertdialog'`,
   `'checkbox'`, `'switch'`, `'tab'`, `'menuitem'`, `'combobox'`, `'option'`, `'listbox'`, `'progressbar'`;
   durumlar `toBeChecked()`, `toBeDisabled()`, `aria-selected`, `aria-expanded`.
2. Varyant/boyut/parça gerekiyorsa **`data-slot` / `data-variant` / `data-size`**:
   `[data-slot="button"][data-variant="destructive"]`, `[data-slot="alert"][data-tone="warning"]`,
   `[data-slot="empty"][data-tone="danger"]`, `[data-slot="team-badge"]`, `[data-slot="progress-bar"]`.
3. **Legacy sınıfa bağlanma.** Sınıf sorgulayan testi güncellerken iddiayı zayıflatma, yalnız seçiciyi değiştir.

### 8.3 Radix tetikleri jsdom'da

| Bileşen | Açan olay | Testte |
|---|---|---|
| DropdownMenu tetiği | `pointerdown` (sol tuş, ctrl yok) | `pressMenuTrigger(el)` (`src/test/helpers/dropdownMenu.js`: pointerDown + mouseDown + click). Tek başına `fireEvent.click` **açmaz** |
| Tabs tetiği | `mousedown` (ya da otomatik kipte odak) | `pressMenuTrigger(el)` ya da `fireEvent.mouseDown`; `click` sekmeyi değiştirmez |
| Select tetiği | `pointerdown` + pointer capture | jsdom'da `hasPointerCapture`/`releasePointerCapture` yok ve `src/test/setup.js`'te polyfill yok → Select'i açan test yazılacaksa dar polyfill ekle (ya da NativeSelect tercih et) |
| SearchableSelect / MultiTeamSelect tetiği | `mousedown` (proje sözleşmesi) | `fireEvent.mouseDown(trigger)`; seçim de `mouseDown(option)` (`test-utils.jsx` `fillGroupAndTags`) |
| Popover / Dialog / Collapsible tetiği | `click` | `fireEvent.click` yeter |
| Tooltip | odak / pointermove | `trigger.focus()`; içerik metni iki kez bulunur (görünür + gizli `role="tooltip"` kopyası) → `getByRole('tooltip')` |

### 8.4 Portal, gizleme, cmdk

- Dialog, AlertDialog, Sheet, Popover, DropdownMenu, Tooltip, Sonner içeriği **`document.body`'ye portal'lanır**
  → `screen.*` ile ara; `container.querySelector` bulamaz.
- **Collapsible `forceMount` tuzağı:** Radix `forceMount`'ta içeriği hep açık çizer; kapalıyken gizlemek
  çağıranın işi (`hidden={!open}`, `Nav.jsx:292`). Testte kapalı grubun öğeleri DOM'da durur ama
  `getByRole` onları (hidden) **döndürmez**; varlığını sınamak için `{ hidden: true }`.
- Tabs: etkin olmayan TabsContent DOM'da yoktur (forceMount yoksa).
- **cmdk:** liste `role="listbox"`, öğeler `[cmdk-item]` + `role="option"` (`aria-selected`), arama kutusu
  `[cmdk-input]`; grup başlığı `[cmdk-group-heading]`. Öğe değeri `data-value`'da (projede `o:` önekli).
- jsdom yerleşim hesaplamaz: yeşil vitest; kırpılma, taşma, z-index, kaydırma gibi **yerleşimi kanıtlamaz**
  → Playwright (`e2e/`) ya da tarayıcıda gözle doğrula.

### 8.5 Polyfill'ler (`src/test/setup.js`)

Var: `matchMedia` (use-mobile + ThemeProvider), `scrollIntoView`, `ResizeObserver`. Yok: `hasPointerCapture`,
`releasePointerCapture`, `DOMRect`/`IntersectionObserver` — gerekirse **dar** polyfill ekle, önce var mı bak.

### 8.6 Kapılar

| Kapı | Ne yakalar |
|---|---|
| `src/test/cssClasses.test.js` | `className="…"` içindeki düz adlar CSS'te tanımlı ya da **Tailwind'in ürettiği** olmalı (derleyiciye sorar; `group`/`peer` işaretçileri tanınır). Muafiyet listesi yalnız küçülür |
| `src/test/cssTokens.test.js` | Tanımsız `var(--x)` yasak (CSS silince de koş) |
| `src/test/rowAccessibleNames.test.js` | Sabit `aria-label` yasak (i18n); tıklanabilir div muafiyeti gerekçeli |
| `src/test/progress-guard.test.jsx` | Ham `<div className="…loading">` bloğu yok (→ `LoadingBlock`); elle yüzde çubuğu yok (→ `ProgressBar`); lucide `Loader2` doğrudan spinner olarak kullanılmaz (→ `ui/Progress` `Spinner`); Progress ailesi reduced-motion kapsamında |
| `src/test/css-hygiene.test.jsx` | Kullanılan yardımcı sınıflar tanımlı; katman jetonları (`--z-*`) tanımlı ve kullanılıyor; Dialog/AlertDialog/ModalShell scrim/Sonner/Spinner animasyonları `prefers-reduced-motion` kapsamında; yeni durum yüzeylerinin her tonunun koyu tema karşılığı var |

Tam kapı dizisi (seri, kaynak düzenlemeden): `.migration/PLAN.md` "Kapılar" bölümü.

---

## 9. Kontrol listesi

### 9.1 Yeni ekran / yeni arayüz öğesi

1. §5'ten ihtiyacı eşle; `components/ui/` sarmalayıcısı varsa onu kullan.
2. İçe aktarım `@/components/shadcn/<ad>` (belgedeki `@/components/ui/<ad>` değil).
3. Legacy sınıf yok; stil Tailwind + jeton (`bg-card`, `text-muted-foreground`, `border`, `gap-*`).
4. Tüm metinler `t()`; yeni anahtar TR + EN (doğal İngiliz İngilizcesi).
5. Yüzen içerik modal içinde açılabiliyorsa `z-(--z-menu)`; Tooltip kullanıyorsan sağlayıcıyı doğrula.
6. Erişilebilirlik: DialogTitle/SheetTitle var; ikon-yalnız düğmede i18n `aria-label`; form alanı `ui/Field`
   ile bağlı; grup kapsayıcılarında `aria-label`.
7. Koyu tema: renkler jetonla; elle ton gerekiyorsa `dark:` karşılığı.
8. `prefers-reduced-motion`: yeni animasyon ailesine `motion-reduce:` kuralı.
9. Testler rol/ad ya da `data-slot`/`data-variant` ile; Radix tetikleri §8.3'teki olaylarla.
10. Kapılar: eslint + ilgili vitest + §8.6. Yerleşim değiştiyse tarayıcıda/Playwright'ta doğrula
    (açık/koyu, TR/EN, 1920/1440/390).
11. Legacy ekranı taşıdıysan artık kullanılmayan App.css kurallarını listele (koordinatör siler) ve
    cssTokens'ı yeniden koş.

### 9.2 Yeni shadcn bileşeni kurarken

1. Kurulu mu? `src/components/shadcn/` (§4).
2. `yes n | npx shadcn@4.21.0 add <ad>` (önce `--dry-run` ile ne yazacağını gör).
3. §3.3 düzeltmeleri: `cn` içe aktarımı, `npm uninstall cn next-themes`, recharts aralığı, `"use client"`,
   forwardRef, sabit İngilizce metinler, `z-50`, `.dark` → `[data-theme="dark"]`.
4. Yeni npm bağımlılığı geldiyse (vaul, embla, input-otp, react-resizable-panels, @tanstack/react-table…)
   kullanıcıya bildir.
5. Bu belgede §3.1/§3.2/§3.4/§4'ü güncelle ("Kurulu", uyarlamalar, varyantlar).

### 9.3 Kod incelemesinde bak

- shadcn öğesinde legacy sınıf kaldı mı? (`btn`, `modal-box`, `badge`, `card-*`, `input`, `ss-*`, `seg-ctl*`…)
- `DialogContent` yerleşik X'i açık mı (`showCloseButton` verilmemiş)? → İngilizce "Close".
- `sonner`'dan doğrudan `toast()` mı çağrılmış? → `useToast()`.
- lucide `Loader2` doğrudan spinner olarak mı kullanılmış? → `ui/Progress` `Spinner` (progress-guard kapısı).
- Base UI Combobox Radix modal içinde mi? → Popover + Command deseni.
- ToggleGroup `type="single"` boş değeri süzülüyor mu?
- Checkbox'ta `onChange` mi kalmış? → `onCheckedChange`.
- Select'te boş dize değerli öğe var mı?

---

## 10. shadcn dünyasındaki önemli değişiklikler (2025–2026)

Projeyi etkileyebilecekler (kaynak: changelog):

- **Tailwind v4 + React 19 (Şubat 2025):** `@theme inline`, `data-slot` her parçada, HSL → OKLCH,
  `forwardRef` kaldırıldı, `size-*`, `default` stili kalktı (new-york), toast → sonner,
  `tailwindcss-animate` → `tw-animate-css` (Mart 2025), düğmeler varsayılan imleç. Belge: güncelleme mevcut
  React 18 projeleri için kırıcı değil — **ama yeni eklenen bileşenler React 19 kalıbında gelir** (§3.1).
- **React 19 sayfası:** npm'de eş bağımlılık çatışmasında `--force` / `--legacy-peer-deps`; recharts için
  `react-is` override notu (React 19'a geçilirse).
- **Birleşik `radix-ui` paketi (Şubat 2026):** `@radix-ui/react-*` yerine `import { X as XPrimitive } from
  "radix-ui"`; geçiş `shadcn migrate radix`. Proje zaten birleşik pakette.
- **CLI v4 (Mart 2026):** `--dry-run`, `--diff`, `--view`, `--preset`, `--template`, `--base`, `shadcn info`,
  `shadcn docs <bileşen>` (belge ve kompozisyon ağacını CLI'dan getirir).
- **İmleç (Nisan 2026):** `init --pointer` → `button:not(:disabled), [role="button"]:not(:disabled)
  { cursor:pointer }`. Projede `globals.css` `button[data-slot] { cursor:pointer }` ile karşılandı.
- **Base UI varsayılan (Temmuz 2026):** yeni `init` Base UI seçer; Radix için `-b radix`. "Radix is not being
  deprecated … You do not need to migrate." Ayrıca `-b aria` (React Aria) tabanı eklendi. Belgedeki bileşen
  sayfalarının varsayılanı artık Base UI (`/docs/components/base/<ad>`) — **bu proje için Radix sayfalarını
  oku** (`/docs/components/radix/<ad>`).
- **Toast (Temmuz 2026):** Base UI için yeni Toast bileşeni; Radix tarafında toast hâlâ kalkmış → Sonner.
- **`cn` paketi (Eylül 2026):** kayıt bileşenleri `cn`'i `cn` paketinden alır; `lib/utils` yeniden dışa
  aktarır; geçiş `shadcn migrate cn`. Projede yerel `@/lib/utils` kararı (§3.3).
- **Ekim 2025 yeni bileşenler:** Spinner, Kbd, Button Group, Input Group, Field, Item, Empty (Native Select de
  sonradan eklendi) — hepsi projede kurulu.
- **2026 yeni bileşenler:** sohbet ailesi (Message Scroller, Message, Bubble, Attachment, Marker), Questionnaire,
  Direction (RTL), `shadcn/typeset`.
- **baseColor listesi:** neutral, stone, zinc, mauve, olive, mist, taupe (zinc geçerli).
- **Belge ↔ yerel sürüm farkları (yerel dosya esastır):** belgede Alert'te `AlertAction`, Card'da `size="sm"`,
  Item'da `size="xs"`, ToggleGroup `spacing` varsayılanı 2 (2026-05-17), Pagination'da `text` prop'u,
  Button/Badge ikonlarında `data-icon="inline-start|inline-end"` var; yerel dosyalarda yok ya da farklı.
  Upstream'i görmek için `add <ad> --diff`, üzerine yazma yok.
- **Yapay zekâ yardımcıları:** `shadcn/skills` (`pnpm dlx skills add shadcn/ui`) ve MCP sunucusu mevcut;
  skill'in öne çıkan kuralları: formlarda `FieldGroup`, seçenek kümelerinde `ToggleGroup`, anlamsal renkler,
  tabana (Radix/Base) uygun API.

---

## 11. Bağlantılar

Genel:
- https://ui.shadcn.com/docs — giriş
- https://ui.shadcn.com/llms.txt — belge dizini
- https://ui.shadcn.com/docs/installation/vite
- https://ui.shadcn.com/docs/components-json
- https://ui.shadcn.com/docs/theming
- https://ui.shadcn.com/docs/dark-mode · https://ui.shadcn.com/docs/dark-mode/vite
- https://ui.shadcn.com/docs/cli
- https://ui.shadcn.com/docs/javascript
- https://ui.shadcn.com/docs/react-19
- https://ui.shadcn.com/docs/tailwind-v4
- https://ui.shadcn.com/docs/forms · https://ui.shadcn.com/docs/forms/react-hook-form ·
  https://ui.shadcn.com/docs/forms/tanstack-form
- https://ui.shadcn.com/docs/registry
- https://ui.shadcn.com/docs/skills
- https://ui.shadcn.com/docs/components — tam liste

Changelog:
- https://ui.shadcn.com/docs/changelog
- https://ui.shadcn.com/docs/changelog/2026-09-cn
- https://ui.shadcn.com/docs/changelog/2026-07-toast
- https://ui.shadcn.com/docs/changelog/2026-07-base-ui-default
- https://ui.shadcn.com/docs/changelog/2026-06-chat-components
- https://ui.shadcn.com/docs/changelog/2026-04-pointer-cursor
- https://ui.shadcn.com/docs/changelog/2026-04-component-composition
- https://ui.shadcn.com/docs/changelog/2026-03-cli-v4
- https://ui.shadcn.com/docs/changelog/2026-02-radix-ui
- https://ui.shadcn.com/docs/changelog/2026-01-inline-side-styles
- https://ui.shadcn.com/docs/changelog/2025-10-new-components

Bileşenler (Radix sayfaları, `https://ui.shadcn.com/docs/components/radix/<ad>`):
accordion · alert · alert-dialog · aspect-ratio · avatar · badge · breadcrumb · button · button-group ·
calendar · card · carousel · chart · checkbox · collapsible · combobox · command · context-menu · data-table ·
date-picker · dialog · direction · drawer · dropdown-menu · empty · field · hover-card · input · input-group ·
input-otp · item · kbd · label · menubar · native-select · navigation-menu · pagination · popover · progress ·
radio-group · resizable · scroll-area · select · separator · sheet · sidebar · skeleton · slider · sonner ·
spinner · switch · table · tabs · textarea · toast · toggle · toggle-group · tooltip · typography

Temel kütüphaneler: Radix Primitives (https://www.radix-ui.com/primitives) · cmdk · Sonner · React DayPicker
(https://react-day-picker.js.org) · Recharts · Base UI (yalnız combobox.jsx).

Proje içi:
- `.migration/PLAN.md` — geçiş durumu, kapılar, bilinen tuzaklar
- `.migration/shadcn-brief.md` — alt-ajan brifi (davranış sözleşmesi)
- `.migration/wave2-tasks.md` — 2. dalga görevleri (Combobox/Progress/Alert/Field/Calendar)
- `frontend/src/components/shadcn/` — kurulu bileşenler · `frontend/src/components/ui/` — proje sarmalayıcıları
- `frontend/src/test/helpers/dropdownMenu.js`, `frontend/src/test/helpers/sidebar.jsx`
