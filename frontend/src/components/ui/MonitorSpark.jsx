import { useId, useMemo, useState } from 'react'
import { ChevronRight } from 'lucide-react'
import Sparkline from './Sparkline.jsx'
import { ProgressBar } from './Progress.jsx'
import { useT } from '../../i18n/index.jsx'
import { dateLocale, formatPercent } from '../../i18n/dateLocale.js'
import { toUtc } from '../../utils/localDay.js'
import { Button } from '@/components/shadcn/button'
import {
  Popover, PopoverContent, PopoverDescription, PopoverHeader, PopoverTitle, PopoverTrigger,
} from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/**
 * Kart içi mini trend + erişilebilirlik satırı (2026-09-12 #4/#11/#14; yeniden tasarım 2026-09-26).
 *
 * <p>İki satır:
 * <ol>
 *   <li><b>Trend</b> — son 24 saatin saatlik ortalama süresi (çizgi), son kovanın değeri, son 5 kontrol noktası
 *       (kırmızı = hatalı) ve 24 saatlik erişilebilirlik yüzdesi. `spark` yoksa çizilmez.</li>
 *   <li><b>Erişilebilirlik</b> — TEK satır, TEK shadcn düğmesi: etiket · 30 günlük yüzde (hedef ✓/↓, renkli) ·
 *       dört dönem noktası (24 sa / 7 / 15 / 30 gün: kontrol yok / hatasız / hata var ama hedefte / hedefin
 *       altında) · ok. Düğme bir shadcn <b>Popover</b> açar: dönem başına ince erişilebilirlik çubuğu, yüzde,
 *       "N kontrol · M hata", hedef satırı ve seçili (varsayılan: en kötü) dönemin hata görülen saat dilimleri
 *       (en yeni 6 + "+N"). Eski dört "1 / 7 / 15 / 30 gün" hapı yalnız `title` ipucuyla bilgi veriyordu —
 *       dokunmatikte hiç açılmıyor, iki satır yer kaplıyordu.</li>
 * </ol>
 * Sunucu `windows` göndermiyorsa (eski sunucu) düğme noktasız çizilir; pencerede 30 günlük özet + "x hatalı saat".
 * Kart "stretched button" deseninde: çağıran bu bloğu örtünün ÜSTÜNE (CARD_LAYER) koyar.
 */
export default function MonitorSpark({ spark, unit = 'ms', sla = null, slaTarget = null, slaDays = 30, rowLabel }) {
  const hasSpark = !!(spark && spark.n)
  const hasSla = !!(sla && sla.n && sla.up_pct != null)
  if (!hasSpark && !hasSla) return null
  return (
    // Olay yayılımı durur: tıklanabilir bir kabın (kart/satır) içinde kullanılırsa Popover'a basmak detayı açmasın.
    <div data-slot="monitor-spark" className="mt-1.5 mb-1 flex min-w-0 flex-col gap-1" onClick={(e) => e.stopPropagation()}>
      {hasSpark && <TrendRow spark={spark} unit={unit} />}
      {hasSla && <AvailabilityLine sla={sla} target={slaTarget} days={slaDays} rowLabel={rowLabel} />}
    </div>
  )
}

/** 24 saatlik yüzde tonu (eski .mspark-up.is-ok/warn/bad eşikleri). */
function upTone(up) {
  if (up == null) return 'none'
  return up >= 99.9 ? 'ok' : up >= 99 ? 'warn' : 'bad'
}
const TEXT_TONE = {
  none: 'text-muted-foreground',
  ok: 'text-success',
  warn: 'text-amber-700 dark:text-amber-400',
  bad: 'text-destructive',
}

function TrendRow({ spark, unit }) {
  const t = useT()
  const series = (spark.buckets || []).map((b) => (b.ms == null ? 0 : b.ms))
  const last = spark.last || []
  const up = spark.up_pct
  const tone = upTone(up)
  // Son kovanın HAM değeri: çizgi için null → 0 yapılıyor ama son saatte ölçüm yoksa "0ms" yazmak yanlış
  // (düşen her izleme kartında "0ms" görünüyordu — 2026-09-27, ping kart ajanı bulgusu). Ölçüm yoksa gösterilmez.
  const lastBucket = (spark.buckets || []).at(-1)
  const lastMs = lastBucket?.ms ?? null
  const checkText = (c) => [c.at ? c.at.replace('T', ' ') : '', c.ms != null ? `${c.ms}${unit}` : '', c.ok ? '' : t('spark.fail')]
    .filter(Boolean).join(' · ')
  return (
    <div data-slot="monitor-trend" className="flex min-w-0 items-center gap-2 text-[11px] leading-none">
      {/* Eski `title` ipucu (yalnız fareyle) → ekran okuyucu için görünmez özet; görenler için sayılar satırda. */}
      <span className="sr-only">{t('spark.title', spark.n, spark.fail)}</span>
      {series.length >= 2
        ? <Sparkline data={series} width={120} height={22} className="h-[22px] w-[120px] min-w-12 shrink"
            color={spark.fail > 0 ? 'var(--danger)' : 'var(--primary)'} label={t('spark.aria')} />
        : <span aria-hidden="true" data-slot="monitor-trend-flat" className="h-0.5 w-[120px] min-w-12 shrink rounded-full bg-border" />}
      {lastMs != null && <span data-slot="monitor-trend-ms" className="shrink-0 text-muted-foreground tabular-nums">{lastMs}{unit}</span>}
      <span className="ml-auto flex shrink-0 items-center gap-2">
        {last.length > 0 && (
          <span role="list" aria-label={t('spark.lastAria', last.length)} data-slot="monitor-last-checks" className="inline-flex items-center gap-[3px]">
            {last.map((c, i) => (
              <span key={i} role="listitem" data-ok={c.ok ? 'true' : 'false'}
                className={cn('size-2 rounded-full', c.ok ? 'bg-success' : 'bg-destructive')}>
                <span className="sr-only">{checkText(c)}</span>
              </span>
            ))}
          </span>
        )}
        {up != null && (
          <span data-slot="monitor-trend-up" data-tone={tone} className={cn('font-semibold tabular-nums', TEXT_TONE[tone])}>
            <span aria-hidden="true"><span className="font-normal text-muted-foreground">{t('spark.up24')}</span> {formatPercent(up)}</span>
            <span className="sr-only">{t('spark.up', up)}</span>
          </span>
        )}
      </span>
    </div>
  )
}

const num = (v) => Number(v || 0).toLocaleString(dateLocale())

/** UTC saat kovası ("yyyy-MM-ddTHH") → yerel "gg.aa SS:dd–SS:dd". */
export function slotText(h) {
  const start = new Date(toUtc(`${h}:00:00`))
  if (Number.isNaN(start.getTime())) return String(h)
  const loc = dateLocale()
  const hm = (d) => d.toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit' })
  return `${start.toLocaleDateString(loc, { day: '2-digit', month: '2-digit' })} ${hm(start)}–${hm(new Date(start.getTime() + 3_600_000))}`
}

/** Dönem durumu: kontrol yok · hatasız · hata var ama hedefte · hata var ve hedefin altında. */
export function windowState(w, target) {
  if (!w || !w.n) return 'none'
  if (!(w.fail > 0)) return 'ok'
  return target != null && w.up_pct != null && w.up_pct < target ? 'bad' : 'warn'
}

const DOT = {
  none: 'border border-muted-foreground/45 bg-transparent',
  ok: 'bg-success',
  warn: 'bg-amber-500',
  bad: 'bg-destructive',
}
const BAR_TONE = { ok: 'ok', warn: 'warn', bad: 'crit' }

function Dot({ state, className }) {
  return <span aria-hidden="true" data-slot="availability-dot" data-state={state} className={cn('size-2 shrink-0 rounded-full', DOT[state], className)} />
}

/** Varsayılan seçili dönem: en düşük yüzdeli HATALI dönem (eşitlikte en uzunu); hata yoksa en uzun dönem. */
function worstWindow(wins) {
  let best = null
  for (const w of wins) {
    if (!w.n || !(w.fail > 0) || w.up_pct == null) continue
    if (!best || w.up_pct < best.up_pct || (w.up_pct === best.up_pct && w.days > best.days)) best = w
  }
  return (best ?? wins[wins.length - 1])?.days
}

function AvailabilityLine({ sla, target, days, rowLabel }) {
  const t = useT()
  const titleId = useId()
  const [open, setOpen] = useState(false)
  const wins = Array.isArray(sla.windows) && sla.windows.length ? sla.windows : null
  const [picked, setPicked] = useState(null)
  const worst = useMemo(() => (wins ? worstWindow(wins) : null), [wins])
  const selected = wins ? (wins.find((w) => w.days === (picked ?? worst)) ?? wins[wins.length - 1]) : null

  const below = target != null && sla.up_pct < target
  const met = target != null && !below
  const pct = formatPercent(sla.up_pct.toFixed(2))
  const verdict = target == null ? null : below ? t('spark.winBelow', target) : t('spark.targetMet', target)
  const name = [t('spark.availDays', days), pct, verdict].filter(Boolean).join(' · ')

  return (
    <Popover open={open} onOpenChange={(v) => { setOpen(v); if (!v) setPicked(null) }}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="sm" data-slot="availability-trigger"
          data-verdict={below ? 'below' : met ? 'met' : 'none'}
          aria-label={rowLabel ? t('a11y.rowAction', rowLabel, name) : name}
          className="-mx-1.5 h-7 w-[calc(100%+0.75rem)] min-w-0 justify-start gap-1.5 rounded-md px-1.5 text-[11px] font-normal pointer-coarse:h-10">
          <span className="shrink-0 font-semibold text-muted-foreground">{t('spark.avail')}</span>
          <span data-slot="availability-pct"
            className={cn('shrink-0 font-bold tabular-nums', below ? 'text-destructive' : met ? 'text-success' : 'text-foreground')}>
            {pct}{target != null && <span aria-hidden="true" className="ml-0.5">{below ? '↓' : '✓'}</span>}
          </span>
          {wins && (
            <span data-slot="availability-dots" className="ml-auto flex items-center gap-1">
              {wins.map((w) => <Dot key={w.days} state={windowState(w, target)} />)}
            </span>
          )}
          <ChevronRight aria-hidden="true"
            className={cn('size-3.5 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', !wins && 'ml-auto', open && 'rotate-90')} />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" side="bottom" collisionPadding={8} aria-labelledby={titleId}
        data-slot="availability-popover"
        className="z-(--z-menu) w-80 max-w-[calc(100vw-1rem)] p-0">
        <PopoverHeader className="border-b px-3 py-2.5">
          <PopoverTitle id={titleId} className="font-semibold">{t('spark.availDays', days)}</PopoverTitle>
          <PopoverDescription className="text-xs">
            <span className={cn('font-semibold tabular-nums', below ? 'text-destructive' : met ? 'text-success' : 'text-foreground')}>{pct}</span>
            {target != null && <> · {t('spark.targetLine', target)} — <span className={below ? 'text-destructive' : 'text-success'}>{verdict}</span></>}
          </PopoverDescription>
        </PopoverHeader>
        {wins ? (
          <>
            <div role="group" aria-label={t('spark.periods')} className="flex flex-col gap-0.5 p-1.5">
              {wins.map((w) => (
                <PeriodRow key={w.days} w={w} target={target} selected={selected?.days === w.days}
                  onSelect={() => setPicked(w.days)} />
              ))}
            </div>
            <SlotList w={selected} />
          </>
        ) : (
          <div className="flex flex-col gap-1 px-3 py-2.5 text-xs text-muted-foreground">
            <span>{t('spark.winCounts', num(sla.n), num(sla.fail))}</span>
            {(sla.bad_hours ?? 0) > 0 && <span className="text-amber-700 dark:text-amber-400">{t('spark.slaBadHours', sla.bad_hours)}</span>}
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}

const periodName = (t, w) => (w.days === 1 ? t('spark.winTip1') : t('spark.winTipN', w.days))

/** Tek dönem satırı — basınca o dönemin saat dilimleri aşağıda listelenir (aria-pressed). */
function PeriodRow({ w, target, selected, onSelect }) {
  const t = useT()
  const state = windowState(w, target)
  const tone = state === 'none' ? 'none' : state
  const belowTarget = state === 'bad'
  return (
    <Button type="button" variant="ghost" aria-pressed={selected} onClick={onSelect}
      data-slot="availability-period" data-days={w.days} data-state={state}
      className="h-auto w-full flex-col items-stretch gap-1 rounded-md px-2 py-1.5 text-left font-normal whitespace-normal aria-pressed:bg-muted pointer-coarse:py-2.5">
      <span className="flex items-center gap-2 text-xs">
        <Dot state={state} />
        <span className="font-medium text-foreground">{periodName(t, w)}</span>
        <span className={cn('ml-auto font-semibold tabular-nums', TEXT_TONE[tone])}>
          {w.n && w.up_pct != null ? formatPercent(w.up_pct.toFixed(2)) : '—'}
        </span>
      </span>
      <ProgressBar value={w.n && w.up_pct != null ? w.up_pct : 0} max={100} size="sm" decorative tone={BAR_TONE[state]} />
      <span className="text-[11px] text-muted-foreground">
        {w.n ? t('spark.winCounts', num(w.n), num(w.fail)) : t('spark.winNoChecks')}
        {belowTarget && <span className="text-destructive"> · {t('spark.winBelow', target)}</span>}
      </span>
    </Button>
  )
}

/** Seçili dönemde hata görülen saat dilimleri — her biri o dilimdeki hata ADEDİYLE; en yeni 6, fazlası "+N". */
function SlotList({ w }) {
  const t = useT()
  const listId = useId()
  if (!w) return null
  const slots = (Array.isArray(w.slots) ? w.slots : []).slice().sort((a, b) => String(b.h).localeCompare(String(a.h)))
  const shown = slots.slice(0, 6)
  const more = Math.max(0, (w.bad_hours ?? slots.length) - shown.length)
  return (
    <div data-slot="availability-slots" className="border-t px-3 py-2.5">
      <p id={listId} className="mb-1.5 text-[11px] font-semibold text-muted-foreground">{t('spark.slotsTitle', periodName(t, w))}</p>
      {shown.length > 0 ? (
        <ul aria-labelledby={listId} className="flex flex-col gap-1 text-xs">
          {shown.map((s) => (
            <li key={s.h} className="flex items-center justify-between gap-3">
              <span className="tabular-nums">{slotText(s.h)}</span>
              <span className="font-medium text-destructive">{s.fail === 1 ? t('spark.slotFail1') : t('spark.slotFailN', num(s.fail))}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-muted-foreground">{w.fail > 0 ? t('spark.winCounts', num(w.n), num(w.fail)) : t('spark.noSlots')}</p>
      )}
      {shown.length > 0 && more > 0 && <p className="mt-1.5 text-[11px] text-muted-foreground">{t('spark.slotMore', more)}</p>}
    </div>
  )
}
