import { useState, useEffect, forwardRef, createRef } from 'react'
import { createPortal } from 'react-dom'
import DatePicker, { registerLocale } from 'react-datepicker'
import 'react-datepicker/dist/react-datepicker.css'
import { tr, enUS } from 'date-fns/locale'
import { Calendar, ChevronDown } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'

registerLocale('tr', tr)
registerLocale('en', enUS)

// Renders the calendar popup into document.body to escape modal overflow clipping
const BodyPortal = ({ children }) => createPortal(children, document.body)

const CustomInput = forwardRef(({ value, onClick, label }, ref) => (
  <button className="dp-trigger" onClick={onClick} ref={ref} type="button">
    <Calendar size={13} className="dp-trigger-icon" />
    <span className="dp-trigger-label">{label}</span>
    <span className="dp-trigger-value">{value}</span>
    <ChevronDown size={12} className="dp-trigger-chevron" />
  </button>
))
CustomInput.displayName = 'CustomInput'

function makeFromInput(label) {
  return forwardRef((props, ref) => <CustomInput {...props} ref={ref} label={label} />)
}

const SHORTCUTS = (t) => [
  {
    label: t('dp.today'),
    get() {
      const s = new Date(); s.setHours(0, 0, 0, 0)
      const e = new Date()
      return [s, e]
    },
  },
  {
    label: t('dp.last7'),
    get() {
      const s = new Date(); s.setDate(s.getDate() - 6); s.setHours(0, 0, 0, 0)
      const e = new Date()
      return [s, e]
    },
  },
  {
    label: t('dp.last30'),
    get() {
      const s = new Date(); s.setDate(s.getDate() - 29); s.setHours(0, 0, 0, 0)
      const e = new Date()
      return [s, e]
    },
  },
  {
    label: t('dp.thisMonth'),
    get() {
      const s = new Date(); s.setDate(1); s.setHours(0, 0, 0, 0)
      const e = new Date()
      return [s, e]
    },
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
    const next = date > localTo ? localTo : date
    setLocalFrom(next)
  }

  function handleTo(date) {
    if (!date) return
    const next = date < localFrom ? localFrom : date
    setLocalTo(next)
  }

  function applyShortcut(sc) {
    const [s, e] = sc.get()
    setLocalFrom(s)
    setLocalTo(e)
    onApply(s, e)
  }

  const FromInput = forwardRef((props, ref) =>
    <CustomInput {...props} ref={ref} label={t('uptime.dateFrom')} />
  )
  FromInput.displayName = 'FromInput'

  const ToInput = forwardRef((props, ref) =>
    <CustomInput {...props} ref={ref} label={t('uptime.dateTo')} />
  )
  ToInput.displayName = 'ToInput'

  return (
    <div className="dp-wrap">
      {/* Shortcut chips */}
      <div className="dp-shortcuts">
        {SHORTCUTS(t).map(sc => (
          <button key={sc.label} className="dp-shortcut" onClick={() => applyShortcut(sc)}>
            {sc.label}
          </button>
        ))}
      </div>

      {/* Range inputs */}
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
          customInput={<FromInput />}
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
          customInput={<ToInput />}
          showPopperArrow={false}
          popperPlacement="bottom-start"
          calendarClassName="dp-calendar"
        />

        <button className="upt-apply-btn" onClick={() => onApply(localFrom, localTo)}>
          {t('uptime.apply')}
        </button>
      </div>
    </div>
  )
}
