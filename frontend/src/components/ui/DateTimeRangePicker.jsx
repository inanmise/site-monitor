import { useState, useEffect, forwardRef } from 'react'
import { createPortal } from 'react-dom'
import DatePicker, { registerLocale } from 'react-datepicker'
import 'react-datepicker/dist/react-datepicker.css'
import { tr, enUS } from 'date-fns/locale'
import { Calendar, ChevronDown } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'

registerLocale('tr', tr)
registerLocale('en', enUS)

// Module-level — never recreated between renders, react-datepicker stays stable
const CustomDateInput = forwardRef(function CustomDateInput({ value, onClick, fieldLabel }, ref) {
  return (
    <button className="dp-trigger" onClick={onClick} ref={ref} type="button">
      <Calendar size={13} className="dp-trigger-icon" />
      <span className="dp-trigger-label">{fieldLabel}</span>
      <span className="dp-trigger-value">{value || '—'}</span>
      <ChevronDown size={12} className="dp-trigger-chevron" />
    </button>
  )
})

// Portals calendar to document.body, escaping modal overflow + z-index stacking
const BodyPortal = ({ children }) => createPortal(children, document.body)

const SHORTCUTS = (t) => [
  {
    label: t('dp.today'),
    get() { const s = new Date(); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
  {
    label: t('dp.last7'),
    get() { const s = new Date(); s.setDate(s.getDate() - 6); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
  {
    label: t('dp.last30'),
    get() { const s = new Date(); s.setDate(s.getDate() - 29); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
  {
    label: t('dp.thisMonth'),
    get() { const s = new Date(); s.setDate(1); s.setHours(0, 0, 0, 0); return [s, new Date()] },
  },
]

export default function DateTimeRangePicker({ from, to, onApply }) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = lang === 'tr' ? 'tr' : 'en'

  const [localFrom, setLocalFrom] = useState(from)
  const [localTo,   setLocalTo]   = useState(to)

  useEffect(() => { setLocalFrom(from) }, [from])
  useEffect(() => { setLocalTo(to)     }, [to])

  function handleFrom(date) {
    if (!date) return
    setLocalFrom(date > localTo ? localTo : date)
  }

  function handleTo(date) {
    if (!date) return
    setLocalTo(date < localFrom ? localFrom : date)
  }

  function applyShortcut(sc) {
    const [s, e] = sc.get()
    setLocalFrom(s)
    setLocalTo(e)
    onApply(s, e)
  }

  return (
    <div className="dp-wrap">
      {/* Quick shortcut chips */}
      <div className="dp-shortcuts">
        {SHORTCUTS(t).map(sc => (
          <button key={sc.label} className="dp-shortcut" type="button"
            onClick={() => applyShortcut(sc)}>
            {sc.label}
          </button>
        ))}
      </div>

      {/* From → To inputs + Apply */}
      <div className="dp-range-row">
        <DatePicker
          selected={localFrom}
          onChange={handleFrom}
          selectsStart
          startDate={localFrom}
          endDate={localTo}
          showTimeSelect
          timeFormat="HH:mm"
          timeIntervals={30}
          dateFormat="dd.MM.yyyy HH:mm"
          locale={locale}
          maxDate={localTo}
          popperContainer={BodyPortal}
          customInput={<CustomDateInput fieldLabel={t('uptime.dateFrom')} />}
          showPopperArrow={false}
          popperPlacement="bottom-start"
          calendarClassName="dp-calendar"
        />

        <span className="dp-range-sep">→</span>

        <DatePicker
          selected={localTo}
          onChange={handleTo}
          selectsEnd
          startDate={localFrom}
          endDate={localTo}
          showTimeSelect
          timeFormat="HH:mm"
          timeIntervals={30}
          dateFormat="dd.MM.yyyy HH:mm"
          locale={locale}
          minDate={localFrom}
          maxDate={new Date()}
          popperContainer={BodyPortal}
          customInput={<CustomDateInput fieldLabel={t('uptime.dateTo')} />}
          showPopperArrow={false}
          popperPlacement="bottom-start"
          calendarClassName="dp-calendar"
        />

        <button className="upt-apply-btn" type="button"
          onClick={() => onApply(localFrom, localTo)}>
          {t('uptime.apply')}
        </button>
      </div>
    </div>
  )
}
