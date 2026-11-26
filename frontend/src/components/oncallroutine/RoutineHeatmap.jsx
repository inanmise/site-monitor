import { memo, useEffect, useMemo, useRef, useState } from 'react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { localeFor } from '../../i18n/dateLocale.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import MonthCalendar from '../ui/MonthCalendar.jsx'
import { DAY_STATUSES, addDays, daysBetween, isIsoDate, mondayOf, shortDate } from './routineModel.js'
import { cn } from '@/lib/utils'

/**
 * Nöbet Rutinleri ısı haritası (2026-11-26) — gün durumlarının (sunucu `/calendar` satırları) renkli takvimi.
 *
 * Yerleşim KAP genişliğine göre (`useElementWidth`):
 *  - ≥ 900 px (`HEAT_GRID_MIN_PX`) YIL ızgarası: sütunlar ISO haftaları, satırlar Pzt–Paz, hücre 12 px (kısa aralıkta
 *    daha büyük: ≤ 16 hafta 16 px, ≤ 8 hafta 20 px — 30 günlük aralık geniş kartta nokta kadar kalmasın), aralık 3 px.
 *    WAI "grid" deseni: tek odaklanabilir hücre (gezici tabindex), oklar gün/hafta gezer, Home/End aralığın uçları,
 *    Enter / Boşluk günü açar (`onOpenDay(date)`).
 *  - daha dar kapta `ui/MonthCalendar` (ay görünümü, ay gezinmesi; telefonda noktalı yoğun kip).
 *  - Ölçüm 0 iken (jsdom, ilk boyamadan önce) YIL ızgarası seçilir: birim testleri ızgarayı görür. Gerçek tarayıcıda
 *    `useElementWidth`'in geri çağrılı ref'i ölçümü commit sırasında (boyamadan önce) yapar — telefonda ızgara görünüp
 *    kaybolmaz.
 *
 * Renkler YALNIZ tema jetonlarından: tamamlandı `--success`, kısmi `color-mix(--success, --warning)`, eksik `--warning`,
 * kaçırıldı `--destructive`, açık `--primary` çerçeve; kayıt yok / duraklatıldı / iş günü değil / gelecek `--muted` —
 * bilinmeyen ASLA yeşil değildir (tanınmayan kod NONE sayılır).
 *
 * Gelecek günler (bugünden sonrası ya da `FUTURE` durumu) AÇILMAZ: henüz veri yok — tıklama / Enter / Boşluk bir şey
 * yapmaz, hücre `aria-disabled` ve adı "henüz veri yok" der; ok tuşları üzerinden geçebilir (ızgara gezintisi bozulmaz).
 *
 * Test kancaları: kök `or-heatmap` (`data-mode` grid | month), hücre `or-heat-cell` (`data-date`, `data-status`,
 * `data-today`, `data-future`), açıklama `or-heat-legend`.
 */

/** Gün açılamaz mı (gelecek: henüz veri yok). `today` bilinmiyorsa yalnız `FUTURE` durumu sayılır. */
export function isFutureDay(date, status, today) {
  if (status === 'FUTURE') return true
  return isIsoDate(today) && isIsoDate(date) && date > today
}

export const HEAT_GRID_MIN_PX = 900
export const HEAT_GAP_PX = 3
/** Gün sayılan (rengi olan) durumlar. */
export const COUNTED_STATUSES = Object.freeze(['COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'OPEN'])
/** Açıklamadaki sıra (gri durumlar en sonda). */
const LEGEND_ORDER = ['COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'OPEN', 'NONE', 'NOT_WORKDAY']

const PARTIAL_MIX = 'color-mix(in oklab, var(--success) 55%, var(--warning))'

/** Tanınmayan / eksik durum → NONE (asla yeşil). */
export function normalizeStatus(status) {
  return DAY_STATUSES.includes(status) ? status : 'NONE'
}

/** Durum → hücre / örnek rengi `{ className, style }` (tema jetonları). */
export function statusSwatch(status) {
  switch (normalizeStatus(status)) {
    case 'COMPLETED': return { className: 'bg-success', style: undefined }
    case 'COMPLETED_PARTIAL': return { className: '', style: { backgroundColor: PARTIAL_MIX } }
    case 'INCOMPLETE': return { className: 'bg-warning', style: undefined }
    case 'MISSED': return { className: 'bg-destructive', style: undefined }
    case 'OPEN': return { className: 'bg-primary/15 ring-1 ring-primary ring-inset', style: undefined }
    case 'NOT_WORKDAY':
    case 'FUTURE': return { className: 'bg-muted/50', style: undefined }
    default: return { className: 'bg-muted', style: undefined }   // NONE / PAUSED
  }
}

/** Sütun sayısına göre hücre boyu (px). */
export function cellSizeFor(weeks) {
  if (weeks <= 8) return 20
  if (weeks <= 16) return 16
  return 12
}

/**
 * Yıl ızgarası: aralığı kapsayan ISO haftaları (her biri 7 tarih, Pzt → Paz) + ay etiketlerinin sütunları. Döngüler
 * sınırlı (≤ 60 hafta). Aralık dışı tarihler hücre değil, boşluktur.
 */
export function buildYearGrid(from, to) {
  if (!isIsoDate(from) || !isIsoDate(to) || from > to) return { weeks: [], monthStarts: [] }
  const start = mondayOf(from)
  const span = daysBetween(start, mondayOf(to))
  const count = Math.min(60, Math.floor(span / 7) + 1)
  const weeks = []
  const monthStarts = []
  for (let c = 0; c < count; c += 1) {
    const monday = addDays(start, c * 7)
    const dates = []
    for (let r = 0; r < 7; r += 1) {
      const d = addDays(monday, r)
      dates.push(d)
      if (d.endsWith('-01') && d >= from && d <= to) monthStarts.push({ col: c, date: d })
    }
    weeks.push({ monday, dates })
  }
  return { weeks, monthStarts }
}

/**
 * Ay etiketleri: her ayın 1'inin bulunduğu sütun + (ay başı değilse) ilk sütun. Çakışma: iki etiket arası en az `minCols`
 * sütun; ilk sütun etiketi yakındaki gerçek ay başına yer açar.
 */
export function monthLabelCols(from, monthStarts, minCols) {
  const out = []
  const candidates = [...(monthStarts.length && monthStarts[0].col === 0 ? [] : [{ col: 0, date: from, synthetic: true }]), ...monthStarts]
  for (const c of candidates) {
    const last = out[out.length - 1]
    if (last && c.col - last.col < minCols) {
      if (last.synthetic) out[out.length - 1] = c
      continue
    }
    out.push(c)
  }
  return out
}

function utcDate(date) {
  return new Date(`${date}T00:00:00Z`)
}

/** Hücre / olay erişilebilir adı: "26 Kasım: tamamlandı 18/18 · Ayşe Yılmaz". */
export function dayLabel(row, date, { t, locale, withYear = false }) {
  const st = normalizeStatus(row?.status)
  const dateText = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC' })
    .format(utcDate(date))
  const parts = [`${dateText}: ${t(`or.status.${st}`).toLocaleLowerCase(locale)}`]
  const required = Number(row?.required)
  if (COUNTED_STATUSES.includes(st) && Number.isFinite(required) && required > 0) {
    const resolved = (Number(row?.done) || 0) + (Number(row?.skipped) || 0)
    parts[0] += ` ${resolved}/${required}`
  }
  const extra = []
  if (row?.holiday_name) extra.push(row.holiday_name)
  if (row?.completed_late) extra.push(t('or.hist.late'))
  const probs = Number(row?.problems_opened)
  if (Number.isFinite(probs) && probs > 0) extra.push(probs === 1 ? t('or.hist.cellProblems.one') : t('or.hist.cellProblems', probs))
  if (row?.responsible_name) extra.push(row.responsible_name)
  return [...parts, ...extra].join(' · ')
}

const HeatCell = memo(function HeatCell({ date, status, label, size, tabbable, today, future = false }) {
  const sw = statusSwatch(status)
  return (
    <div role="gridcell" data-slot="or-heat-cell" data-date={date} data-status={status} data-today={today || undefined}
      data-future={future || undefined} aria-disabled={future || undefined}
      tabIndex={tabbable ? 0 : -1} aria-label={label} title={label}
      className={cn('shrink-0 rounded-[3px] outline-none transition-shadow',
        future ? 'cursor-default' : 'cursor-pointer hover:ring-2 hover:ring-foreground/40',
        'focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-card',
        today && 'outline outline-1 outline-offset-1 outline-foreground/50',
        sw.className)}
      style={{ width: size, height: size, ...(sw.style || {}) }} />
  )
})

const WEEK_ROWS = [0, 1, 2, 3, 4, 5, 6]
const LABEL_W = 30

function YearGrid({ byDate, from, to, today, onOpenDay, t, locale, ariaLabel }) {
  const { weeks, monthStarts } = useMemo(() => buildYearGrid(from, to), [from, to])
  const size = cellSizeFor(weeks.length)
  const pitch = size + HEAT_GAP_PX
  const withYear = from.slice(0, 4) !== to.slice(0, 4)
  const labels = useMemo(() => {
    const fmt = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' })
    const fmtY = new Intl.DateTimeFormat(locale, { month: 'short', year: 'numeric', timeZone: 'UTC' })
    return monthLabelCols(from, monthStarts, Math.ceil(34 / pitch)).map((m, i) => ({
      col: m.col,
      text: (withYear && (i === 0 || m.date.slice(5, 7) === '01') ? fmtY : fmt).format(utcDate(m.date)),
    }))
  }, [from, monthStarts, locale, pitch, withYear])
  const weekdayNames = useMemo(() => {
    const fmt = new Intl.DateTimeFormat(locale, { weekday: 'short', timeZone: 'UTC' })
    return WEEK_ROWS.map((r) => fmt.format(new Date(Date.UTC(2024, 0, 1 + r))))   // 2024-01-01 Pazartesi
  }, [locale])

  const inRange = (d) => isIsoDate(d) && d >= from && d <= to
  const initial = inRange(today) ? today : to
  const [focusDate, setFocusDate] = useState(initial)
  const active = inRange(focusDate) ? focusDate : initial
  const gridRef = useRef(null)
  const pendingFocus = useRef(false)
  useEffect(() => {
    if (!pendingFocus.current) return
    pendingFocus.current = false
    gridRef.current?.querySelector(`[data-slot="or-heat-cell"][data-date="${active}"]`)?.focus()
  }, [active])

  const moveTo = (date) => {
    if (!inRange(date)) return
    pendingFocus.current = true
    setFocusDate(date)
  }
  const onKeyDown = (e) => {
    if (e.nativeEvent?.isComposing) return
    const cell = e.target?.closest?.('[data-slot="or-heat-cell"]')
    const cur = cell?.dataset?.date || active
    switch (e.key) {
      case 'ArrowRight': moveTo(addDays(cur, 7)); break
      case 'ArrowLeft': moveTo(addDays(cur, -7)); break
      case 'ArrowDown': moveTo(addDays(cur, 1)); break
      case 'ArrowUp': moveTo(addDays(cur, -1)); break
      case 'Home': moveTo(from); break
      case 'End': moveTo(to); break
      case 'Enter':
      case ' ':
        if (!isFutureDay(cur, normalizeStatus(byDate.get(cur)?.status), today)) onOpenDay(cur)
        break
      default: return
    }
    e.preventDefault()
  }
  const onClick = (e) => {
    const cell = e.target?.closest?.('[data-slot="or-heat-cell"]')
    const date = cell?.dataset?.date
    if (!inRange(date)) return
    setFocusDate(date)
    if (!isFutureDay(date, normalizeStatus(byDate.get(date)?.status), today)) onOpenDay(date)
  }

  return (
    <div data-slot="or-heat-scroll" className="max-w-full overflow-x-auto pb-1">
      <div className="inline-flex flex-col" style={{ gap: HEAT_GAP_PX }}>
        <div aria-hidden="true" className="relative h-4 text-[10px] leading-4 text-muted-foreground"
          style={{ marginLeft: LABEL_W + HEAT_GAP_PX, width: weeks.length * pitch }}>
          {labels.map((m) => (
            <span key={`${m.col}-${m.text}`} className="absolute top-0 whitespace-nowrap" style={{ left: m.col * pitch }}>{m.text}</span>
          ))}
        </div>
        <div role="grid" aria-label={ariaLabel} ref={gridRef} onKeyDown={onKeyDown} onClick={onClick}
          className="flex flex-col" style={{ gap: HEAT_GAP_PX }}>
          {WEEK_ROWS.map((r) => (
            <div role="row" key={r} className="flex items-center" style={{ gap: HEAT_GAP_PX }}>
              <span aria-hidden="true" className="shrink-0 text-[10px] leading-none text-muted-foreground" style={{ width: LABEL_W }}>
                {r === 0 || r === 2 || r === 4 ? weekdayNames[r] : ''}
              </span>
              {weeks.map((w) => {
                const date = w.dates[r]
                if (!inRange(date)) return <span key={date} aria-hidden="true" className="shrink-0" style={{ width: size, height: size }} />
                const row = byDate.get(date)
                const status = normalizeStatus(row?.status)
                const future = isFutureDay(date, status, today)
                const label = dayLabel(row, date, { t, locale, withYear })
                return (
                  <HeatCell key={date} date={date} status={status} size={size} tabbable={date === active}
                    today={date === today} future={future} label={future ? `${label} · ${t('or.hist.futureNoData')}` : label} />
                )
              })}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/** Ay görünümü olay tonları (MonthCalendar: ok / info / warn / bad). Gri durumlar olay üretmez. */
const MONTH_TONE = { COMPLETED: 'ok', COMPLETED_PARTIAL: 'ok', INCOMPLETE: 'warn', MISSED: 'bad', OPEN: 'info' }

function monthStartOf(date) {
  const [y, m] = date.split('-').map(Number)
  return new Date(y, m - 1, 1)
}

function Legend({ t, showPaused }) {
  const items = showPaused ? [...LEGEND_ORDER, 'PAUSED'] : LEGEND_ORDER
  return (
    <ul data-slot="or-heat-legend" aria-label={t('or.hist.legendAria')}
      className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
      {items.map((st) => {
        const sw = statusSwatch(st)
        return (
          <li key={st} data-status={st} className="flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-3 shrink-0 rounded-[3px]', sw.className)} style={sw.style} />
            <span>{t(`or.status.${st}`)}</span>
          </li>
        )
      })}
    </ul>
  )
}

export default function RoutineHeatmap({ days = [], from, to, today, onOpenDay = () => {}, teamName = '' }) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = localeFor(lang)
  const [ref, width] = useElementWidth()
  const mode = width === 0 || width >= HEAT_GRID_MIN_PX ? 'grid' : 'month'
  const byDate = useMemo(() => {
    const m = new Map()
    for (const d of Array.isArray(days) ? days : []) if (d && isIsoDate(d.date)) m.set(d.date, d)
    return m
  }, [days])
  const showPaused = useMemo(() => [...byDate.values()].some((d) => d.status === 'PAUSED'), [byDate])
  const valid = isIsoDate(from) && isIsoDate(to) && from <= to
  const fromText = valid ? shortDate(from, true) : ''
  const toText = valid ? shortDate(to, true) : ''
  const ariaLabel = teamName ? t('or.hist.gridAriaTeam', teamName, fromText, toText) : t('or.hist.gridAria', fromText, toText)

  const events = useMemo(() => {
    if (mode !== 'month') return []
    const withYear = valid && from.slice(0, 4) !== to.slice(0, 4)
    const out = []
    for (const row of byDate.values()) {
      if (valid && (row.date < from || row.date > to)) continue
      const st = normalizeStatus(row.status)
      if (!MONTH_TONE[st]) continue
      const required = Number(row.required)
      const resolved = (Number(row.done) || 0) + (Number(row.skipped) || 0)
      const counts = Number.isFinite(required) && required > 0 ? ` ${resolved}/${required}` : ''
      const date = row.date
      if (isFutureDay(date, st, today)) continue   // gelecek gün: henüz veri yok, açılmaz
      out.push({
        date,
        label: `${t(`or.hist.short.${st}`)}${counts}`,
        tone: MONTH_TONE[st],
        title: dayLabel(row, date, { t, locale, withYear }),
        onClick: () => onOpenDay(date),
      })
    }
    return out
  }, [mode, byDate, valid, from, to, today, t, locale, onOpenDay])

  return (
    <div ref={ref} data-slot="or-heatmap" data-mode={mode} className="flex min-w-0 flex-col gap-3">
      {valid && mode === 'grid' && (
        <YearGrid key={`${from}|${to}`} byDate={byDate} from={from} to={to} today={today} onOpenDay={onOpenDay}
          t={t} locale={locale} ariaLabel={ariaLabel} />
      )}
      {valid && mode === 'month' && (
        <MonthCalendar key={to} initialMonth={monthStartOf(to)} events={events} dense ariaLabel={ariaLabel}
          onDayClick={(key) => { if (!isFutureDay(key, normalizeStatus(byDate.get(key)?.status), today)) onOpenDay(key) }} />
      )}
      <Legend t={t} showPaused={showPaused} />
    </div>
  )
}
