import { useState, useRef, useEffect, useMemo } from 'react'
import { Calendar, ChevronLeft, ChevronRight } from 'lucide-react'
import { useT, useLanguage, useDateLocale } from '../../i18n/index.jsx'
import { MONTHS, monthGrid } from '../../utils/isoWeek'
import { Button } from '@/components/shadcn/button'

const DOW = {
  tr: ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz'],
  en: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
}

/**
 * Hafta odaklı tarih seçici — native date input yerine (tarayıcı dilinde
 * render olduğu ve hafta kavramı olmadığı için). ISO hafta numarası sütunu,
 * satır (hafta) bazlı vurgu ve raporu olan haftalarda nokta işareti gösterir.
 * Görünüm SearchableSelect (ss-*) dilini paylaşır.
 *
 * Props: value 'yyyy-mm-dd' | '', onChange(dateStr), placeholder, hint,
 *        isMarked(year, week) → bool, onViewYearChange(year)
 */
export default function WeekDatePicker({
  value, onChange, placeholder, hint, isMarked, onViewYearChange,
}) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  const today = new Date()
  const todayStr = new Date(today.getTime() - today.getTimezoneOffset() * 60000)
    .toISOString().slice(0, 10)
  const [view, setView] = useState(() => {
    const base = value ? new Date(value + 'T12:00:00') : today
    return { y: base.getFullYear(), m: base.getMonth() }
  })

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  useEffect(() => {
    if (open) onViewYearChange?.(view.y)
  }, [open, view.y, onViewYearChange])

  const rows = useMemo(() => monthGrid(view.y, view.m), [view])
  const months = MONTHS[lang] ?? MONTHS.tr
  const dow = DOW[lang] ?? DOW.tr

  function toggle(e) {
    e.preventDefault()
    setOpen((p) => {
      if (!p) {
        const base = value ? new Date(value + 'T12:00:00') : new Date()
        setView({ y: base.getFullYear(), m: base.getMonth() })
      }
      return !p
    })
  }

  function shiftMonth(delta) {
    setView((v) => {
      const d = new Date(v.y, v.m + delta, 1)
      return { y: d.getFullYear(), m: d.getMonth() }
    })
  }

  function pick(day) {
    onChange(day.toISOString().slice(0, 10))
    setOpen(false)
  }

  return (
    <div className="ss-wrap" ref={ref} title={hint}>
      <button type="button"
        className={`ss-trigger${open ? ' ss-open' : ''}${value ? '' : ' ss-placeholder'}`}
        onMouseDown={toggle}
        onKeyDown={(e) => { if (e.key === 'Escape') setOpen(false) }}>
        <span className="ss-label" style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
          <Calendar size={14} />
          {value ? new Date(value + 'T12:00:00').toLocaleDateString(locale) : placeholder}
        </span>
        <svg className="ss-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="14" height="14">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>

      {open && (
        <div className="ss-dropdown wdp-pop">
          <div className="wdp-head">
            <button type="button" className="wdp-nav" onClick={() => shiftMonth(-1)}>
              <ChevronLeft size={16} />
            </button>
            <span className="wdp-title">{months[view.m]} {view.y}</span>
            <button type="button" className="wdp-nav" onClick={() => shiftMonth(1)}>
              <ChevronRight size={16} />
            </button>
          </div>

          <div className="wdp-row wdp-row-head">
            <span className="wdp-wk">#</span>
            {dow.map((d) => <span key={d} className="wdp-dow">{d}</span>)}
          </div>

          {rows.map(({ week, days }) => (
            <div key={`${week.year}-${week.week}`}
              className={`wdp-row${isMarked?.(week.year, week.week) ? ' wdp-has-report' : ''}`}
              role="button" tabIndex={0} aria-label={`${week.year} — ${week.week}`}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(days[0]) } }}
              onClick={() => pick(days[0])}>
              <span className="wdp-wk">
                {week.week}
                {isMarked?.(week.year, week.week) && <span className="wdp-dot" />}
              </span>
              {days.map((d) => {
                const ds = d.toISOString().slice(0, 10)
                const out = d.getUTCMonth() !== view.m
                return (
                  <button key={ds} type="button"
                    className={`wdp-day${out ? ' wdp-out' : ''}${ds === todayStr ? ' wdp-today' : ''}${ds === value ? ' wdp-sel' : ''}`}
                    onClick={(e) => { e.stopPropagation(); pick(d) }}>
                    {d.getUTCDate()}
                  </button>
                )
              })}
            </div>
          ))}

          <div className="wdp-foot">
            <span className="wdp-legend"><span className="wdp-dot" /> {t('wr.weekHasReport')}</span>
            <span style={{ flex: 1 }} />
            {value && (
              <Button type="button" variant="outline" size="sm" onClick={() => { onChange(''); setOpen(false) }}>
                {t('wr.clear')}
              </Button>
            )}
            <Button type="button" variant="secondary" size="sm" onClick={() => pick(new Date(Date.UTC(
              today.getFullYear(), today.getMonth(), today.getDate(), 12)))}>
              {t('wr.today')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
