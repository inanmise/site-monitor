// "Yalnız shadcn" kapısının KURALLARI — test (src/test/shadcnOnly.test.js) ve taban üreticisi
// (scripts/shadcn-only-baseline.mjs) aynı listeyi kullanır; kural ikiye ayrılıp ayrışmasın.
//
// Karar (2026-09-25, kullanıcı): shadcn/ui projenin VARSAYILAN ve ana UI kütüphanesi. Uçtan uca
// gezildiğinde https://ui.shadcn.com/docs/components bileşeni kullanmayan ekran/öğe kalmayacak.
// Bu kurallar tarayıcıya ÇIPLAK HTML kontrolü ya da elle kurulmuş bileşen bırakan kalıpları sayar.
// Ayrıntı ve bileşen karşılıkları: frontend/docs/SHADCN.md.
import fs from 'node:fs'
import path from 'node:path'

/**
 * JSX açılış etiketinin adı: `idx` konumundaki özniteliğin ait olduğu `<Etiket` (geriye doğru en yakın).
 * Öznitelik ifadelerinde başka bir `<X` (JSX prop'u) geçmesi nadirdir; kural bu sezgiyle yetinir.
 */
export function openingTagAt(code, idx) {
  const before = code.slice(0, idx)
  let m, last = null
  const rx = /<([A-Za-z][\w.]*)/g
  while ((m = rx.exec(before))) last = m[1]
  return last
}

/** Kural: ad → { rx, fix, skip? }. rx yorumlardan arındırılmış kaynağa uygulanır; `skip(code, idx)`
 *  true dönen eşleşme sayılmaz (meşru kalıp). */
export const RULES = {
  'raw-button':   { rx: /<button\b/g,                                   fix: 'shadcn Button (@/components/shadcn/button)' },
  'raw-input':    { rx: /<input\b(?![^>]*\btype=["']hidden["'])/g,      fix: 'Input / Checkbox / Switch / RadioGroup / Slider / Calendar' },
  'raw-select':   { rx: /<select\b/g,                                   fix: 'NativeSelect ya da Select' },
  'raw-textarea': { rx: /<textarea\b/g,                                 fix: 'Textarea' },
  'raw-table':    { rx: /<table\b/g,                                    fix: 'Table (TableHeader/TableBody/TableRow/TableHead/TableCell)' },
  'raw-details':  { rx: /<details\b/g,                                  fix: 'Collapsible ya da Accordion' },
  'raw-progress': { rx: /<progress\b/g,                                 fix: 'Progress (ui/Progress)' },
  'raw-dialog':   { rx: /<dialog\b/g,                                   fix: 'Dialog / AlertDialog / Sheet' },
  'hand-switch':  { rx: /role=["']switch["']|\bperm-pill\b/g,           fix: 'Switch' },
  'hand-tabs':    { rx: /role=["']tab(?:list|panel)?["']/g,             fix: 'Tabs' },
  'hand-modal':   { rx: /\bmodal-overlay\b|\bmodal-box\b/g,             fix: 'Dialog (ui/ModalShell) / Sheet' },
  'datepicker':   { rx: /from\s+['"]react-datepicker['"]/g,             fix: 'Calendar + Popover (tarih seçici deseni)' },
  'recharts':     { rx: /from\s+['"]recharts['"]/g,                     fix: 'Chart (ChartContainer / ChartTooltip)' },
  // Elle düğme: düğme olmayan öğeye role="button" (2026-09-26 — Pano sayım kartları bu yüzden kapıdan
  // kaçmıştı). Meşru istisna: ToggleGroupItem'ın role="button" + aria-pressed "toggle button group"
  // sözleşmesi (öğe zaten gerçek <button>; Radix'in radio rolünü ezer). Koşullu yazım da sayılır.
  'hand-button':  { rx: /(?<![\w-])role=\{?\s*(?:[^}"'\n]*\?\s*)?["']button["']/g,
                    skip: (code, idx) => openingTagAt(code, idx) === 'ToggleGroupItem',
                    fix: 'shadcn Button; iç içe etkileşim varsa "stretched button" (monitoring/MonitorCard)' },
}

/** Bir kuralın kaynaktaki (yorumsuz) eşleşme sayısı — `skip` süzgeci uygulanmış. */
export function countRule(name, code) {
  const { rx, skip } = RULES[name]
  let n = 0
  for (const m of code.matchAll(rx)) if (!skip || !skip(code, m.index)) n++
  return n
}

/**
 * GEREKÇELİ İZİN LİSTESİ — `{ dosya: { kural: sayı, reason } }`. Ölçümden DÜŞÜLÜR (tabana girmez).
 * Yalnız iki tür girer, ikisi de gerekçesiyle:
 *  (1) String olarak kurulan BAĞIMSIZ belgeler — React/Tailwind'in var olmadığı bir iframe/yazdırma
 *      penceresinde çizilen HTML; orada shadcn bileşeni kullanılamaz.
 *  (2) Bilinçli `role="button"` span'ler — gerçek <button> geçersiz HTML ya da erişilebilirlik hatası
 *      üreteceği için (düğme içinde düğme / etiketlenebilir öğe <label> içinde).
 * CANLILIK: gerçek sayı izinden KÜÇÜKSE test kırmızıdır (bayat giriş başka bir ham öğeyi sessizce
 * örtemesin) — öğe kalktıysa girişi silin. Yeni giriş = kullanıcıya/incelemeye gerekçe.
 */
export const ALLOWLIST = {
  'components/weekly/weeklyModel.js': {
    'raw-table': 1,
    reason: 'buildYearSummaryHtml: string olarak kurulan A4 yazdırma belgesi; iframe içinde React/Tailwind yok',
  },
  'components/ui/CopyButton.jsx': {
    'hand-button': 1,
    reason: 'as="span" kipi: CertHealthPanel satır başlığı <button> içinde cipher kopyala — düğme içinde düğme geçersiz HTML',
  },
  'components/ui/TeamBadge.jsx': {
    'hand-button': 1,
    reason: 'as="span" kipi: rozet satır/kart <button>larının içinde (UserPushSettings, ExecutiveSummary, AlertTeamStatsPanel, MonitorChangesConsole, UserActivityPanel) — düğme içinde düğme geçersiz HTML',
  },
  'components/ui/HelpTip.jsx': {
    'hand-button': 1,
    reason: 'tetik alan <label>ının içinde (helpLabel, ui/Field etiketi); <button> etiketlenebilir öğe olduğundan etiketi çalar, asıl alan erişilebilir adını kaybeder',
  },
}

/** Taramaya girmeyen klasörler: testler, shadcn'in kendi dosyaları (ham öğeleri ONLAR sarar), statik varlıklar. */
const SKIP = new Set(['test', 'shadcn', 'assets', 'node_modules'])

/** Yorumları at: blok ve JSX yorumları + satır başı/boşluktan sonra gelen `//` (URL'lerdeki `://` korunur). */
export function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[\s;])\/\/[^\n]*/g, '$1')
}

export function listSources(srcDir) {
  const out = []
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) { if (!SKIP.has(e.name)) walk(full) }
      else if (/\.(jsx?|tsx?)$/.test(e.name)) out.push(full)
    }
  }
  walk(srcDir)
  return out
}

/**
 * { 'components/X.jsx': { 'raw-button': 3, … } } — yalnız sıfırdan büyük sayılar.
 * ALLOWLIST düşülür; `{ raw: true }` düşmeden ham sayıları verir (canlılık testi için).
 */
export function measure(srcDir, { raw = false } = {}) {
  const result = {}
  for (const file of listSources(srcDir)) {
    const rel = path.relative(srcDir, file).split(path.sep).join('/')
    const code = stripComments(fs.readFileSync(file, 'utf8'))
    const counts = {}
    for (const rule of Object.keys(RULES)) {
      const allowed = raw ? 0 : (ALLOWLIST[rel]?.[rule] ?? 0)
      const n = countRule(rule, code) - allowed
      if (n > 0) counts[rule] = n
    }
    if (Object.keys(counts).length) result[rel] = counts
  }
  return result
}
