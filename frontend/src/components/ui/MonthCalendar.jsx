import { useMemo, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/**
 * Aylık takvim ızgarası (2026-09-12, zenginleştirme #8/#19) — kütüphanesiz.
 * `events`: [{ date: 'YYYY-MM-DD', label, tone: 'ok'|'warn'|'bad'|'info', onClick, title }]
 * Aynı güne yığılan olaylar sayılır (3+ ise "+N"). Pazartesi başlangıçlı; bugün vurgulu.
 * Yalnız gösterim: hangi olayın ne anlama geldiği çağıranın işi.
 */
function ymd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

export default function MonthCalendar({ events = [], initialMonth, maxPerDay = 3, ariaLabel }) {
  const t = useT()
  const [cursor, setCursor] = useState(() => {
    const d = initialMonth ? new Date(initialMonth) : new Date()
    return new Date(d.getFullYear(), d.getMonth(), 1)
  })
  const byDay = useMemo(() => {
    const m = new Map()
    for (const e of events) { if (!e?.date) continue; const k = String(e.date).slice(0, 10); if (!m.has(k)) m.set(k, []); m.get(k).push(e) }
    return m
  }, [events])

  const year = cursor.getFullYear(), month = cursor.getMonth()
  const first = new Date(year, month, 1)
  const startOffset = (first.getDay() + 6) % 7   // Pazartesi = 0
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cells = []
  for (let i = 0; i < startOffset; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(new Date(year, month, d))
  while (cells.length % 7 !== 0) cells.push(null)
  const today = ymd(new Date())
  const monthLabel = cursor.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
  const monthEvents = [...byDay.entries()].filter(([k]) => k.startsWith(`${year}-${String(month + 1).padStart(2, '0')}`)).reduce((n, [, v]) => n + v.length, 0)
  const dayNames = [t('cal.mon'), t('cal.tue'), t('cal.wed'), t('cal.thu'), t('cal.fri'), t('cal.sat'), t('cal.sun')]

  return (
    <div className="mcal" role="group" aria-label={ariaLabel || t('cal.aria')}>
      <div className="mcal-head">
        <button type="button" className="btn btn-sm btn-secondary" onClick={() => setCursor(new Date(year, month - 1, 1))} aria-label={t('cal.prev')}><ChevronLeft size={14} /></button>
        <span className="mcal-title">{monthLabel} <small className="mcal-count">{t('cal.count', monthEvents)}</small></span>
        <button type="button" className="btn btn-sm btn-secondary" onClick={() => setCursor(new Date(year, month + 1, 1))} aria-label={t('cal.next')}><ChevronRight size={14} /></button>
        <button type="button" className="btn btn-sm btn-secondary mcal-today" onClick={() => { const n = new Date(); setCursor(new Date(n.getFullYear(), n.getMonth(), 1)) }}>{t('cal.today')}</button>
      </div>
      <div className="mcal-grid">
        {dayNames.map((n) => <div key={n} className="mcal-dow">{n}</div>)}
        {cells.map((d, i) => {
          if (!d) return <div key={`e${i}`} className="mcal-cell mcal-cell--empty" />
          const k = ymd(d)
          const evs = byDay.get(k) || []
          const worst = evs.some((e) => e.tone === 'bad') ? 'bad' : evs.some((e) => e.tone === 'warn') ? 'warn' : evs.some((e) => e.tone === 'info') ? 'info' : evs.length ? 'ok' : ''
          return (
            <div key={k} className={`mcal-cell${k === today ? ' is-today' : ''}${worst ? ` has-${worst}` : ''}`}>
              <div className="mcal-day">{d.getDate()}</div>
              <div className="mcal-events">
                {evs.slice(0, maxPerDay).map((e, j) => (
                  <button type="button" key={j} className={`mcal-ev mcal-ev--${e.tone || 'ok'}`} title={e.title || e.label} onClick={e.onClick}>{e.label}</button>
                ))}
                {evs.length > maxPerDay && <span className="mcal-more" title={evs.slice(maxPerDay).map((e) => e.label).join('\n')}>+{evs.length - maxPerDay}</span>}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
