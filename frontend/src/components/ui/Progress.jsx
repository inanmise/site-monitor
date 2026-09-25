/**
 * İlerleme göstergesi ailesi — projedeki TEK standart. Yeni bir "yükleniyor" veya "ilerleme"
 * göstergesi gerektiğinde elle spinner/çubuk yazılmaz, buradan seçilir. İç çizim shadcn/ui
 * (`@/components/shadcn/spinner`, `@/components/shadcn/progress`); DIŞ API (prop adları) aynıdır.
 *
 * Hangisini seçmeli:
 *   • <Spinner>      → kalan iş BİLİNMİYOR (belirsiz). Yüzde yok, aria-valuenow YOK. (shadcn Spinner)
 *   • <ProgressRing> → kompakt dairesel yay dolarak tamamlanmaya gidiyor (dar alan, kart köşesi).
 *                      shadcn'de dairesel karşılığı yok; SVG burada, stil Tailwind jetonlarından.
 *   • <ProgressBar>  → okunabilir bir track'e yer var. (shadcn Progress — Radix)
 *   • <LoadingBlock> → sayfa/bölüm gövdesi yükleniyor (Spinner + metin, ortalanmış).
 *
 * Erişilebilirlik sözleşmesi:
 *   • Belirli göstergeler role="progressbar" + aria-valuenow/valuemin/valuemax taşır; okunan metin
 *     (aria-valuetext) ile ekranda görünen yüzde AYNI hesaptan gelir, ayrışamaz.
 *   • Belirsiz göstergede aria-valuenow VERİLMEZ; ARIA'da "değeri olmayan progressbar" =
 *     belirsiz demektir. Yanlış bir 0 değeri "hiç ilerlemedi" diye okunurdu.
 *   • Metin taşıyan belirsiz göstergeler role="status" + aria-live="polite" ile duyurulur;
 *     salt süs olanlar aria-hidden ile ekran okuyucudan gizlenir (çift okuma olmaz).
 *   • Butonlarda: butona aria-busy verin, içindeki spinner'a `decorative` (etiketi buton
 *     metni zaten taşır).
 *
 * Hareket: dönen her öğe `motion-reduce:` ile prefers-reduced-motion altında dönmeyi bırakır
 * (yerine opaklık nabzı); çubuk/halka geçişleri `motion-reduce:transition-none`.
 * Bekçi: progress-guard.test.jsx.
 *
 * Test kancaları: data-slot="spinner" | "progress-bar" (sarmalayıcı) | "progress" | "progress-ring"
 * | "loading-block" — sınıf adı değil (sınıflar Tailwind yardımcısı, anlam taşımaz).
 */
import { formatPercent } from '../../i18n/dateLocale.js'
import { Spinner as ShadcnSpinner } from '@/components/shadcn/spinner'
import { Progress } from '@/components/shadcn/progress'
import { cn } from '@/lib/utils'

/** 0..max aralığına kırpar; max 0/geçersizse null (belirsiz) döner. */
function clampValue(value, max) {
  const m = Number(max)
  if (!Number.isFinite(m) || m <= 0) return null
  const v = Number(value)
  if (!Number.isFinite(v)) return null
  return Math.max(0, Math.min(m, v))
}

const pct = (value, max) => Math.round((value / max) * 100)

/**
 * Belirsiz spinner — kalan iş bilinmiyorken.
 * @param size px cinsinden çap
 * @param label ekran okuyucuya duyurulacak metin; verilirse role="status" alır
 * @param decorative true → aria-hidden (yanında zaten açıklayıcı metin var, ör. buton içi)
 * @param inline true → satır içi hizalama (buton/metin yanı)
 */
export function Spinner({ size = 16, label, decorative = false, inline = false, className = '' }) {
  // shadcn Spinner kendi başına role="status" + "Yükleniyor" etiketi taşır. Buradaki sözleşme
  // farklı: süs spinner'ı ekran okuyucuya HİÇ görünmez, etiketli olanda duyuruyu sarmalayıcı yapar
  // (ikon + görünmez metin tek bir status bölgesi). Bu yüzden ikonun kendi rolü kaldırılır.
  const icon = (
    <ShadcnSpinner
      data-slot="spinner"
      role={undefined}
      aria-label={undefined}
      aria-hidden="true"
      className={cn('shrink-0', inline && 'align-[-0.15em]', className)}
      style={{ width: size, height: size }}
    />
  )
  if (decorative || !label) return icon
  return (
    <span className="inline-flex items-center" role="status" aria-live="polite">
      {icon}
      <span className="sr-only">{label}</span>
    </span>
  )
}

/**
 * Eşik tonları → dolgu rengi. Eski çağrı yerleri tonu sınıf adıyla veriyordu
 * (`className="pg-bar--warn"`); o sınıflar artık bir CSS kuralına bağlı değil, burada `tone`'a
 * çevrilip className'den ayıklanır — ekranlar değişmeden çalışır, yeni kod `tone` prop'unu kullanır.
 */
const TONE_FILL = { ok: 'bg-success', warn: 'bg-warning', crit: 'bg-destructive' }
const LEGACY_TONE = /^pg-bar--(ok|warn|crit)$/

function splitLegacyTone(className) {
  let tone = null
  const rest = String(className || '').split(/\s+/).filter((c) => {
    const m = LEGACY_TONE.exec(c)
    if (m) { tone = m[1]; return false }
    return Boolean(c)
  })
  return { tone, rest: rest.join(' ') }
}

const BAR_HEIGHT = { sm: 'h-1', md: 'h-2', lg: 'h-3' }

/**
 * Belirli lineer çubuk — shadcn Progress (Radix): role="progressbar" + aria-valuenow/min/max ve
 * aria-valuetext (görünen yüzde metniyle aynı) Radix'ten gelir.
 * value/max geçersizse belirsiz çubuk çizilir (aria-valuenow yok, dar nabız şeridi).
 *
 * Dolgu rengi: `tone` (ok|warn|crit) ya da çağıranın sınıfında tanımlı `--pg-fill` jetonu
 * (ör. `.ret-bar { --pg-fill: … }`); ikisi de yoksa marka rengi. Çağıranın `className`'i
 * çubuğun köküne (track) gider — yükseklik/genişlik/köşe ezmeleri eskisi gibi çalışır.
 */
export function ProgressBar({
  value, max = 100, label, showValue = false, size = 'md', decorative = false, tone, className = '',
}) {
  const v = clampValue(value, max)
  const percent = v == null ? null : pct(v, max)
  const legacy = splitLegacyTone(className)
  const fill = TONE_FILL[tone ?? legacy.tone] ?? 'bg-[var(--pg-fill,var(--primary))]'
  // decorative: aynı değer hemen yanında zaten METİN olarak görünüyorsa çubuk ekran okuyucuya
  // ikinci kez duyurulmaz (çift okuma gürültüsü). Görsel/animasyon davranışı aynı kalır.
  const a11y = decorative ? { 'aria-hidden': 'true' } : { 'aria-label': label || undefined }
  return (
    // `pg-bar-wrap`: TÜKETİCİ yerleşim kancası — App.css'te ekranlar sarmalayıcıyı bu adla
    // konumlandırıyor (.ret-row-metrics > .pg-bar-wrap, .cc-life-block > .pg-bar-wrap …).
    <div className="pg-bar-wrap w-full" data-slot="progress-bar">
      {(label || showValue) && !decorative && (
        <div className="mb-1.5 flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
          {label && <span className="font-semibold">{label}</span>}
          {showValue && percent != null && <span className="tabular-nums">{formatPercent(percent)}</span>}
        </div>
      )}
      <Progress
        value={v}
        max={v == null ? 100 : Number(max)}
        getValueLabel={(val, m) => formatPercent(pct(val, m))}
        className={cn(BAR_HEIGHT[size] ?? BAR_HEIGHT.md, 'bg-border', legacy.rest)}
        indicatorClassName={fill}
        {...a11y}
      />
    </div>
  )
}

/**
 * Belirli dairesel yay. Native/shadcn karşılığı olmadığı için ARIA'yı elle veriyoruz:
 * role="progressbar" + aria-valuenow/min/max, aria-valuetext ile okunur metin.
 * Merkezdeki yüzde metni ile aria-valuenow aynı hesaptan gelir.
 */
export function ProgressRing({
  value, max = 100, size = 44, stroke = 4, label, showValue = true,
  color = 'var(--primary, #2563eb)', className = '',
}) {
  const v = clampValue(value, max)
  const percent = v == null ? null : pct(v, max)
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const dash = percent == null ? circ * 0.25 : (percent / 100) * circ
  const c = size / 2
  const aria = percent == null
    ? { 'aria-label': label || undefined }
    : {
        'aria-valuenow': percent, 'aria-valuemin': 0, 'aria-valuemax': 100,
        'aria-valuetext': formatPercent(percent), 'aria-label': label || undefined,
      }
  return (
    <span
      data-slot="progress-ring"
      data-state={percent == null ? 'indeterminate' : 'determinate'}
      className={cn('relative inline-flex items-center justify-center', className)}
      role="progressbar" {...aria}
      style={{ width: size, height: size }}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true"
        className={cn('block', percent == null && 'animate-spin [animation-duration:1.1s] motion-reduce:animate-pulse')}>
        <circle cx={c} cy={c} r={r} fill="none" stroke="var(--border, #e4e4e7)" strokeWidth={stroke} />
        <circle cx={c} cy={c} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
          className="transition-[stroke-dasharray] duration-250 motion-reduce:transition-none"
          strokeDasharray={`${dash} ${circ - dash}`} transform={`rotate(-90 ${c} ${c})`} />
      </svg>
      {showValue && percent != null && (
        <span className="absolute text-[11px] font-bold leading-none text-foreground tabular-nums">{percent}</span>
      )}
    </span>
  )
}

/**
 * Sayfa/bölüm gövdesi yükleniyor — eski `<div className="loading">…</div>` deseninin yerine.
 * shadcn Spinner + metin; kap role="status" ile duyurur (ikon süs).
 * @param fullWidth true → grid kaplarında tüm satırı kaplar (grid-column: 1 / -1)
 */
export function LoadingBlock({ label, fullWidth = false, size = 20, className = '' }) {
  return (
    <div
      data-slot="loading-block"
      className={cn(
        'flex items-center justify-center gap-2.5 px-4 py-10 text-[1.05em] text-muted-foreground',
        fullWidth && 'col-span-full',
        className,
      )}
      role="status" aria-live="polite"
    >
      <Spinner size={size} decorative className="text-primary" />
      {label && <span className="leading-[1.3]">{label}</span>}
    </div>
  )
}
