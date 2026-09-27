import { useMemo, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { localDayKey } from '../../api/client'
import { dateLocale } from '../../i18n/dateLocale.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import HintPopover from './HintPopover.jsx'
import { cn } from '@/lib/utils'

/**
 * Aylık takvim ızgarası (2026-09-12, zenginleştirme #8/#19) — OLAY takvimi (tarih seçici değil).
 * `events`: [{ date: 'YYYY-MM-DD', label, tone: 'ok'|'warn'|'bad'|'info', onClick, title, icon? }]
 * Aynı güne yığılan olaylar sayılır (3+ ise "+N"). Pazartesi başlangıçlı; bugün vurgulu; hafta sonu sütunları
 * hafif zeminli. Yalnız gösterim: hangi olayın ne anlama geldiği çağıranın işi.
 *
 * Çizim shadcn Card (kap) + Button (ay gezinme, olay çipleri) + NativeSelect (aya atlama, 2026-09-27) + Tailwind
 * ızgara. Gün hücreleri etiketli olay çipleri taşıdığı için shadcn Calendar'ın kare gün düğmelerine sığmaz; ızgara
 * burada kalır.
 *
 * Ekleyici seçenekler (2026-09-27, Sertifika Takvimi — varsayılanlarıyla eski davranış birebir aynı):
 *  • `onDayClick(key, events)`: verilirse olaylı günlerin tarih numarası gerçek bir düğme olur
 *    (`data-slot="month-calendar-day-open"`, adı "27 Eylül — 3 olay, günü aç") ve günü çağırana açar.
 *  • `dense`: TELEFONDA (sm altı) etiketli çipler yerine ton noktaları + adet çizilir; hücre 7 sütunda okunur kalır
 *    ve dokunma hedefi tüm hücredir. sm ve üstü çipli görünüm değişmez. (Karar: 390 px'te 7 sütunlu ızgara
 *    çiplerle okunmuyordu — hafta şeridi/ajanda yerine noktalı ızgara + gün paneli.)
 *  • `event.icon` (lucide bileşeni): çipin başında küçük simge (planlı yenileme, alan adı).
 *
 * Test kancaları: gün hücresi `data-slot="month-calendar-day"` (+ `data-today`, `data-tone` en kötü olay tonu,
 * `data-weekend`), gün numarası `data-slot="month-calendar-date"`, olay çipi `data-slot="month-calendar-event"`
 * (+ `data-tone`), aya atlama `data-slot="month-calendar-jump"`.
 */
function ymd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
function ym(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }
function cap(s, locale) { return s ? s.charAt(0).toLocaleUpperCase(locale) + s.slice(1) : s }

/** Bu ayda olay varsa null (bugünkü ay kalır); yoksa bugünden sonraki ilk olayın ayı, o da yoksa en erken olayın ayı. */
export function firstEventMonth(events) {
  const keys = (events || []).map((e) => localDayKey(e?.date)).filter(Boolean).sort()
  if (!keys.length) return null
  const now = new Date()
  const thisMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  if (keys.some((k) => k.startsWith(thisMonth))) return null
  const today = ymd(now)
  const k = keys.find((x) => x >= today) || keys[0]
  return new Date(Number(k.slice(0, 4)), Number(k.slice(5, 7)) - 1, 1)
}

/** Olay çipi tonları (eski .mcal-ev--*) — koyu temada karşılıklı. */
const EVENT_TONE = {
  ok:   'bg-green-100 text-green-800 hover:bg-green-200 dark:bg-green-950 dark:text-green-300 dark:hover:bg-green-900',
  info: 'bg-blue-100 text-blue-800 hover:bg-blue-200 dark:bg-blue-950 dark:text-blue-200 dark:hover:bg-blue-900',
  warn: 'bg-amber-100 text-amber-800 hover:bg-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:hover:bg-amber-900',
  bad:  'bg-red-100 text-red-800 hover:bg-red-200 dark:bg-red-950 dark:text-red-300 dark:hover:bg-red-900',
}
/** Ton noktası (dense telefon görünümü). */
const DOT_TONE = { ok: 'bg-green-600', info: 'bg-blue-600', warn: 'bg-amber-500', bad: 'bg-red-600' }
/** Gün hücresi zemini: en kötü olay tonu (eski .mcal-cell.has-bad / has-warn). */
const CELL_TONE = {
  bad:  'bg-destructive/10 dark:bg-destructive/15',
  warn: 'bg-amber-500/10 dark:bg-amber-500/15',
}

export default function MonthCalendar({ events = [], initialMonth, maxPerDay = 3, ariaLabel, onDayClick, dense = false }) {
  const t = useT()
  const locale = dateLocale()
  const byDay = useMemo(() => {
    const m = new Map()
    // Yerel gün (ISSUE-004): UTC damgası 23:59Z → yerel ertesi gün; slice(0,10) bir gün erken düşürüyordu.
    for (const e of events) { if (!e?.date) continue; const k = localDayKey(e.date); if (!k) continue; if (!m.has(k)) m.set(k, []); m.get(k).push(e) }
    return m
  }, [events])
  // Başlangıç ayı (ISSUE-003): bu ayda olay yoksa olayı olan İLK aya açılır — boş ızgara "hiçbir şey yok" okunmasın.
  const [cursor, setCursor] = useState(() => {
    const d = initialMonth ? new Date(initialMonth) : firstEventMonth(events) || new Date()
    return new Date(d.getFullYear(), d.getMonth(), 1)
  })

  const year = cursor.getFullYear(), month = cursor.getMonth()
  const first = new Date(year, month, 1)
  const startOffset = (first.getDay() + 6) % 7   // Pazartesi = 0
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cells = []
  for (let i = 0; i < startOffset; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(new Date(year, month, d))
  while (cells.length % 7 !== 0) cells.push(null)
  const today = ymd(new Date())
  const monthEvents = [...byDay.entries()].filter(([k]) => k.startsWith(`${year}-${String(month + 1).padStart(2, '0')}`)).reduce((n, [, v]) => n + v.length, 0)
  const dayNames = [t('cal.mon'), t('cal.tue'), t('cal.wed'), t('cal.thu'), t('cal.fri'), t('cal.sat'), t('cal.sun')]
  // Aya atlama listesi: bugünün 12 ay öncesi … 18 ay sonrası (imleç dışarıdaysa aralık ona kadar uzar).
  // Ay adı uygulama dili (tarayıcı dili değil, ISSUE-009); baş harf büyük (tr: "eylül" → "Eylül").
  const monthOptions = useMemo(() => {
    const now = new Date()
    const start = new Date(Math.min(cursor.getTime(), new Date(now.getFullYear(), now.getMonth() - 12, 1).getTime()))
    const end = new Date(Math.max(cursor.getTime(), new Date(now.getFullYear(), now.getMonth() + 18, 1).getTime()))
    const out = []
    for (let d = new Date(start.getFullYear(), start.getMonth(), 1); d <= end; d = new Date(d.getFullYear(), d.getMonth() + 1, 1)) {
      out.push({ value: ym(d), label: cap(d.toLocaleDateString(locale, { month: 'long', year: 'numeric' }), locale) })
    }
    return out
  }, [cursor, locale])
  const jump = (v) => { const [y, m] = v.split('-').map(Number); if (y && m) setCursor(new Date(y, m - 1, 1)) }
  const canOpen = typeof onDayClick === 'function'

  return (
    <Card role="group" aria-label={ariaLabel || t('cal.aria')} data-slot="month-calendar"
      className="mb-3 gap-2 bg-muted/40 px-2 py-2.5 shadow-none sm:px-3">
      <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
        <Button type="button" variant="secondary" size="icon" onClick={() => setCursor(new Date(year, month - 1, 1))} aria-label={t('cal.prev')}><ChevronLeft /></Button>
        <NativeSelect size="sm" data-slot="month-calendar-jump" aria-label={t('cal.jump')} value={ym(cursor)} onChange={(e) => jump(e.target.value)}
          className="h-9 min-w-0 border-transparent bg-transparent font-semibold shadow-none hover:bg-background sm:h-8">
          {monthOptions.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
        </NativeSelect>
        <Button type="button" variant="secondary" size="icon" onClick={() => setCursor(new Date(year, month + 1, 1))} aria-label={t('cal.next')}><ChevronRight /></Button>
        <small className="min-w-0 text-muted-foreground">{t('cal.count', monthEvents)}</small>
        <Button type="button" variant="secondary" size="sm" className="ml-auto h-9 sm:h-8" onClick={() => { const n = new Date(); setCursor(new Date(n.getFullYear(), n.getMonth(), 1)) }}>{t('cal.today')}</Button>
      </div>
      <div className="grid grid-cols-7 gap-0.5 sm:gap-[3px]">
        {dayNames.map((n) => (
          <div key={n} className="pt-0.5 pb-1 text-center text-[.72em] font-bold tracking-[.04em] text-muted-foreground uppercase">{n}</div>
        ))}
        {cells.map((d, i) => {
          if (!d) return <div key={`e${i}`} aria-hidden="true" />
          const k = ymd(d)
          const evs = byDay.get(k) || []
          const worst = evs.some((e) => e.tone === 'bad') ? 'bad' : evs.some((e) => e.tone === 'warn') ? 'warn' : evs.some((e) => e.tone === 'info') ? 'info' : evs.length ? 'ok' : ''
          const isToday = k === today
          const weekend = i % 7 >= 5
          const dateEl = <span data-slot="month-calendar-date" className={cn('text-[.74em] leading-none font-semibold text-muted-foreground', isToday && 'text-primary')}>{d.getDate()}</span>
          const openable = canOpen && evs.length > 0
          const dayLabel = d.toLocaleDateString(locale, { day: 'numeric', month: 'long' })
          return (
            <div key={k} data-slot="month-calendar-day" data-day={k} data-today={isToday || undefined} data-tone={worst || undefined} data-weekend={weekend || undefined}
              className={cn('flex min-h-11 min-w-0 flex-col gap-0.5 rounded-md border bg-background px-1 py-[3px] sm:min-h-[68px]',
                dense && 'min-h-12',
                weekend && !worst && 'bg-muted/50 dark:bg-muted/30', CELL_TONE[worst], isToday && 'border-primary ring-1 ring-primary ring-inset')}>
              {openable ? (
                <Button type="button" variant="ghost" size="xs" data-slot="month-calendar-day-open"
                  aria-label={t('cal.dayOpen', dayLabel, evs.length)} title={t('cal.dayOpen', dayLabel, evs.length)}
                  onClick={() => onDayClick(k, evs)}
                  className={cn('h-auto min-h-5 w-full justify-start gap-1 rounded px-0.5 py-0.5 font-[inherit] hover:bg-accent/70',
                    dense && 'flex-1 flex-col items-start justify-start sm:flex-none sm:flex-row sm:items-center sm:justify-start')}>
                  {dateEl}
                  {dense && (
                    <span className="flex flex-wrap items-center gap-0.5 sm:hidden" aria-hidden="true">
                      {evs.slice(0, 4).map((e, j) => <span key={j} className={cn('size-1.5 rounded-full', DOT_TONE[e.tone] || DOT_TONE.ok)} />)}
                      <span className="ml-0.5 text-[.66em] leading-none font-bold text-foreground tabular-nums">{evs.length}</span>
                    </span>
                  )}
                </Button>
              ) : dateEl}
              <div className={cn('flex min-w-0 flex-col gap-0.5', dense && 'hidden sm:flex')}>
                {evs.slice(0, maxPerDay).map((e, j) => {
                  const Icon = e.icon
                  return (
                    <Button type="button" key={j} variant="ghost" data-slot="month-calendar-event" data-tone={e.tone || 'ok'}
                      title={e.title || e.label} onClick={e.onClick}
                      className={cn('flex h-auto w-full min-w-0 items-center justify-start gap-1 rounded px-1 py-px text-left text-[.62em] font-semibold sm:px-[5px] sm:text-[.7em]', EVENT_TONE[e.tone] || EVENT_TONE.ok)}>
                      {Icon && <Icon aria-hidden="true" className="size-3 shrink-0" />}
                      <span className="min-w-0 truncate">{e.label}</span>
                    </Button>
                  )
                })}
                {/* Gizlenen olayların adları dokun-gör balonda (yalnız-hover `title` telefonda açılmıyordu). */}
                {evs.length > maxPerDay && (
                  <HintPopover content={evs.slice(maxPerDay).map((e) => e.label).join('\n')} triggerClassName="w-fit px-1">
                    <span className="text-[.68em] text-muted-foreground">+{evs.length - maxPerDay}</span>
                  </HintPopover>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </Card>
  )
}
