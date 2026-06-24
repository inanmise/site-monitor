import { forwardRef, useRef } from 'react'
import { createPortal } from 'react-dom'
import DatePicker, { registerLocale } from 'react-datepicker'
import 'react-datepicker/dist/react-datepicker.css'
import { tr, enUS } from 'date-fns/locale'
import { Calendar, ChevronDown, X } from 'lucide-react'
import { useLanguage } from '../../i18n/index.jsx'

registerLocale('tr', tr)
registerLocale('en', enUS)

// Takvimi document.body'ye portalla — modal overflow + z-index katmanından kaçar
// (.react-datepicker-popper z-index:9100 modal overlay 2000'in üstünde).
const BodyPortal = ({ children }) => createPortal(children, document.body)

// Modül seviyesi — render'lar arası yeniden yaratılmaz, react-datepicker stabil kalır.
const TriggerInput = forwardRef(function TriggerInput(
  { value, onClick, disabled, placeholder, onClear }, ref) {
  return (
    <button className="dp-trigger" onClick={onClick} ref={ref} type="button" disabled={disabled}>
      <Calendar size={13} className="dp-trigger-icon" />
      <span className="dp-trigger-value" style={value ? undefined : { color: 'var(--text-muted)', fontWeight: 500 }}>
        {value || placeholder || '—'}
      </span>
      {value && onClear && !disabled
        ? <X size={14} className="dp-trigger-chevron" role="button" aria-label="clear"
             onClick={(e) => { e.stopPropagation(); onClear() }} />
        : <ChevronDown size={12} className="dp-trigger-chevron" />}
    </button>
  )
})

const pad = (n) => String(n).padStart(2, '0')

/** JS Date (an instant) → proje UTC ISO string'i (yyyy-MM-dd'T'HH:mm:ss, tz eki yok).
 *  Kullanıcı takvimde YEREL saat seçer; saklarken UTC'ye çeviririz — proje konvansiyonu
 *  (backend ISO'ları UTC, formatDate +3 lokalize eder). Yoksa tabloda 3 saatlik kayma olur. */
function toIso(d) {
  if (!d) return ''
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
         `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:00`
}

/** Saklanan UTC ISO string → JS Date instant (Z ekleyerek UTC olarak ayrıştır → takvim
 *  yerel saatte gösterir). formatDate/toUtc ile aynı UTC kabulü. */
function parseIso(value) {
  if (!value) return null
  const withZ = value.endsWith('Z') || value.includes('+') ? value : value + 'Z'
  const d = new Date(withZ)
  return isNaN(d.getTime()) ? null : d
}

// ── Sadece-gün modu (filtre From/To): TZ kaymasını önlemek için takvim günü olarak ele alınır.
function parseDateOnly(value) {
  if (!value) return null
  const [y, m, d] = value.slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) return null
  const dt = new Date(y, m - 1, d)
  return isNaN(dt.getTime()) ? null : dt
}
function toDateOnly(d) {
  if (!d) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * Proje geneli react-datepicker deseniyle (DateTimeRangePicker ile aynı görünüm) tek
 * tarih+saat seçici. Native `datetime-local` yerine kullanılır; .dp-trigger ile stillenir,
 * takvim body'ye portallanır, TR/EN locale + dd.MM.yyyy HH:mm formatı.
 *
 * value/onChange proje ISO string'i (yyyy-MM-dd'T'HH:mm:ss) ile çalışır. `clearable`
 * (zorunlu olmayan alanlar) verilirse tetikleyicide × ile temizlenebilir. `dateOnly`
 * verilirse saat seçimi olmadan sadece gün döner (filtre From/To). `className` ile
 * sarmalayıcıya ek sınıf (örn. inline filtre için 'dtf-inline') eklenir.
 */
export default function DateTimeField({ value, onChange, disabled, placeholder, clearable, dateOnly, className, min }) {
  const { lang } = useLanguage()
  const dpRef = useRef(null)
  const parse = dateOnly ? parseDateOnly : parseIso
  const selected = parse(value)
  const minDate = min ? parse(min) : null
  return (
    <div className={'dtf' + (className ? ' ' + className : '')}>
      <DatePicker
        ref={dpRef}
        selected={selected}
        onChange={(d) => onChange(dateOnly ? toDateOnly(d) : toIso(d))}
        disabled={disabled}
        minDate={minDate || undefined}
        showTimeSelect={!dateOnly}
        showTimeInput={!dateOnly}
        timeFormat="HH:mm"
        timeIntervals={5}
        dateFormat={dateOnly ? 'dd.MM.yyyy' : 'dd.MM.yyyy HH:mm'}
        locale={lang === 'tr' ? 'tr' : 'en'}
        popperContainer={BodyPortal}
        customInput={<TriggerInput disabled={disabled} placeholder={placeholder}
          onClear={clearable ? () => onChange('') : undefined} />}
        showPopperArrow={false}
        popperPlacement="bottom-start"
        calendarClassName="dp-calendar"
        shouldCloseOnSelect={!!dateOnly}
      >
        {/* Saatli seçimde otomatik kapanma yok (kullanıcı gün+saati ayarlar) → seçimin bittiğini
            belirten ve takvimi kapatan açık bir "Tamam" butonu. dateOnly modunda gerek yok (gün
            tıklanınca zaten kapanır). */}
        {!dateOnly && (
          <div style={{ padding: '6px 8px', borderTop: '1px solid var(--border, #e2e8f0)',
                        display: 'flex', justifyContent: 'flex-end' }}>
            <button type="button" onClick={() => dpRef.current?.setOpen(false)}
              style={{ padding: '5px 16px', fontWeight: 600, fontSize: '.85rem', cursor: 'pointer',
                       background: 'var(--primary, #2563eb)', color: '#fff', border: 'none', borderRadius: 6 }}>
              {lang === 'tr' ? 'Tamam' : 'Done'}
            </button>
          </div>
        )}
      </DatePicker>
    </div>
  )
}
