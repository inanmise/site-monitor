import { X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { DateTimePopover } from './DatePickerParts.jsx'
import SimpleTooltip from './SimpleTooltip.jsx'

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
 * Proje geneli tek tarih(+saat) seçici — shadcn Date Picker deseni (Popover + Calendar + saat için
 * `Input type="time"`, ortak parçalar `DatePickerParts.jsx`). Native `datetime-local` ve react-datepicker
 * yerine kullanılır; takvim body'ye portal'lanır (modal içinde de üstte), TR/EN yerel, Pazartesi başlangıç,
 * gösterim dd.MM.yyyy HH:mm.
 *
 * value/onChange proje ISO string'i (yyyy-MM-dd'T'HH:mm:ss, UTC) ile çalışır. `clearable` (zorunlu olmayan
 * alanlar) verilirse tetiğin YANINDA × ile temizlenebilir. `dateOnly` verilirse saat seçimi olmadan yalnız
 * gün döner (yyyy-MM-dd, yerel takvim günü — filtre From/To). `min` aynı biçimde alt sınır (öncesi seçilemez).
 * `className` sarmalayıcıya eklenir; `dtf-inline` (filtre satırı) içerik genişliğinde çizer (eski sözleşme).
 *
 * Temizleme GERÇEK bir düğme ve tetiğin DIŞINDA (kardeşi): eskiden tetik <button>'ın İÇİNDE role="button"
 * taşıyan bir SVG'ydi — odaklanamıyordu, düğme içinde düğme de geçersiz yapı (2026-09-25, R14). Tetik o
 * köşede ok yerine aynı genişlikte boş yer bırakır ki değer metni düğmenin altına kaymasın.
 */
export default function DateTimeField({ value, onChange, disabled, placeholder, clearable, dateOnly, className, min, max }) {
  const t = useT()
  const parse = dateOnly ? parseDateOnly : parseIso
  const selected = parse(value)
  const minDate = min ? parse(min) : null
  const maxDate = max ? parse(max) : null   // `max`: üst sınır (sonrası seçilemez) — 2026-09-27, gelecek olamayan anlar
  const inline = /(^|\s)dtf-inline(\s|$)/.test(className || '')
  const extra = (className || '').replace(/(^|\s)dtf-inline(?=\s|$)/g, ' ').trim()
  const clearShown = !!(value && clearable && !disabled)
  const clearLabel = t('app.clear')

  return (
    <DateTimePopover
      value={selected}
      onChange={(d) => onChange(dateOnly ? toDateOnly(d) : toIso(d))}
      withTime={!dateOnly}
      minDate={minDate || undefined}
      maxDate={maxDate || undefined}
      placeholder={placeholder}
      disabled={disabled}
      reserveEnd={clearShown}
      className={cn(inline ? 'w-auto' : 'w-full', extra)}
      triggerClassName={inline ? 'w-auto max-w-full' : undefined}
      endSlot={clearShown && (
        <SimpleTooltip content={clearLabel}>
          <Button type="button" variant="ghost" size="icon-xs"
            className="absolute top-1/2 right-1.5 -translate-y-1/2 text-muted-foreground hover:text-destructive"
            aria-label={clearLabel}
            onClick={() => onChange('')}>
            <X aria-hidden="true" />
          </Button>
        </SimpleTooltip>
      )}
    />
  )
}
