import { forwardRef, useId, useState } from 'react'
import { format } from 'date-fns'
import { tr as dayPickerTr, enGB as dayPickerEnGB } from 'react-day-picker/locale'
import { Calendar as CalendarIcon, ChevronDown, Clock } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Calendar } from '@/components/shadcn/calendar'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/**
 * Tarih seçicilerin ORTAK parçaları — shadcn "Date Picker" deseni (Popover + Calendar) + saat için
 * `Input type="time"`. react-datepicker'ın yerini aldı (2026-09-26, D1 dalgası). Tek başına kullanılmaz:
 * `DateTimeField`, `DateTimeRangePicker`, `TimeRangePicker`, `WeekDatePicker` aynı açılış / odak / katman /
 * yerel sözleşmesini taşısın diye burada.
 *
 * Sözleşme:
 *   • Yerel: uygulama dili (TR/EN) → react-day-picker'ın `tr` / `enGB` yereli (ay ve gün adları), hafta
 *     PAZARTESİ başlar (eski seçicilerle aynı). Gezinme düğmelerinin adları i18n (`cal.prev` / `cal.next`).
 *   • Saat: takvim YEREL saatte gösterir (tarayıcı — kurumda Europe/Istanbul). UTC'ye çevirme ÇAĞIRANIN işi
 *     (DateTimeField `toIso`, TimeRangePicker `resolveRange`); burada yalnız `Date` nesneleri dolaşır.
 *   • Katman: açılır pencere body'ye portal'lanır, `--z-menu` (modal içinde de görünür, SHADCN.md §2.5).
 *   • Telefon: genişlik ekrana sığar (`max-w-[calc(100vw-1rem)]`), yükseklik kalan alanla sınırlı ve kayar.
 *   • Klavye: tetik gerçek düğme (Enter/Space açar), açılınca odak seçili güne (yoksa bugüne) gider, ok tuşları
 *     günler arasında gezer, Escape kapatır ve odağı tetiğe iade eder (Radix Popover).
 */

/** Uygulama dili → DayPicker yereli + i18n'li gezinme adları. Calendar'a `{...useCalendarProps()}` ile verilir. */
export function useCalendarProps() {
  const { lang } = useLanguage()
  const t = useT()
  return {
    locale: lang === 'en' ? dayPickerEnGB : dayPickerTr,
    weekStartsOn: 1,
    labels: { labelPrevious: () => t('cal.prev'), labelNext: () => t('cal.next') },
  }
}

/**
 * Tetik: shadcn Button (outline) — ikon, isteğe bağlı küçük alan etiketi (Başlangıç / Bitiş), değer ya da
 * yer tutucu, açılır ok. `reserveEnd`: okun yerinde boş yer (kardeş temizleme düğmesi oraya oturur, değer
 * metni onun altına kaymaz). Ref alır (PopoverTrigger asChild). Test kancası `data-slot="date-picker-trigger"`.
 */
export const DateTrigger = forwardRef(function DateTrigger(
  { label, text, placeholder, reserveEnd = false, icon: Icon = CalendarIcon, className, ...rest }, ref) {
  return (
    <Button ref={ref} type="button" variant="outline"
      className={cn('group/dp h-9 w-full min-w-0 justify-start gap-2 px-3 font-normal has-[>svg]:px-3', className)}
      {...rest} data-slot="date-picker-trigger">
      <Icon aria-hidden="true" className="text-muted-foreground" />
      {label && <span className="shrink-0 text-[10px] font-bold tracking-[.06em] text-muted-foreground uppercase">{label}</span>}
      {/* Boşluk: erişilebilir ad "Başlangıç 01.09.2026" okunsun (satır içi span'lar arasına ayraç konmaz). */}
      {label && ' '}
      <span className={cn('min-w-0 flex-1 truncate text-left', text ? 'font-medium' : 'text-muted-foreground')}>
        {text || placeholder || '—'}
      </span>
      {reserveEnd
        ? <span aria-hidden="true" className="size-5 shrink-0" />
        : <ChevronDown aria-hidden="true"
            className="text-muted-foreground transition-transform duration-200 group-data-[state=open]/dp:rotate-180 motion-reduce:transition-none" />}
    </Button>
  )
})

/** Açılır pencere kabı: portal + `--z-menu`, ekrana sığan genişlik, kalan yükseklikte kayan gövde. */
export function DatePopoverContent({ className, children, ...rest }) {
  return (
    <PopoverContent align="start" sideOffset={4} collisionPadding={8}
      className={cn('z-(--z-menu) max-h-(--radix-popover-content-available-height) w-auto max-w-[calc(100vw-1rem)] overflow-y-auto p-0', className)}
      {...rest}>
      {children}
    </PopoverContent>
  )
}

/** Saat alanı: shadcn Input type="time" + etiket. Değer "HH:mm" (yerel). */
export function TimeField({ value, onChange, step = 300, disabled, label }) {
  const t = useT()
  const id = useId()
  return (
    <div className="flex items-center gap-2">
      <Label htmlFor={id} className="gap-1.5 font-normal text-muted-foreground">
        <Clock aria-hidden="true" className="size-3.5" />{label ?? t('dp.time')}
      </Label>
      <Input id={id} type="time" step={step} value={value} disabled={disabled}
        onChange={(e) => onChange(e.target.value)} className="h-9 w-[7.5rem]" />
    </div>
  )
}

/** "HH:mm" → [saat, dakika] ya da null. */
export function parseHhmm(v) {
  const m = /^(\d{1,2}):(\d{2})/.exec(v || '')
  if (!m) return null
  const h = Number(m[1]), mi = Number(m[2])
  return h < 24 && mi < 60 ? [h, mi] : null
}

/**
 * Tek tarih(+saat) seçici: tetik + Popover içinde Calendar (+ saat alanı ve "Tamam").
 *
 * `value`/`onChange` YEREL `Date` (ya da null). Gün seçilince mevcut saat korunur (değer yoksa 00:00);
 * saat değişince mevcut gün korunur (değer yoksa bugün). `withTime` kapalıysa gün seçimi pencereyi kapatır;
 * açıksa pencere "Tamam" ile kapanır (kullanıcı gün + saati birlikte ayarlar — eski davranış).
 * `minDate`/`maxDate` GÜN düzeyinde sınırlar (öncesi/sonrası seçilemez). `modifiers`: Calendar'a ek işaretler
 * (ör. aralık vurgusu). `endSlot`: tetiğin kardeşi olarak çizilir (temizleme düğmesi) — tetiğin İÇİNDE değil.
 */
export function DateTimePopover({
  value, onChange, withTime = true, timeStep = 300, minDate, maxDate, modifiers,
  label, placeholder, disabled, reserveEnd = false, triggerClassName, className, endSlot, displayFormat,
  triggerProps,   // isteğe bağlı: tetiğe ek aria öznitelikleri (aria-invalid / aria-describedby — DateTimeField, 2026-09-28)
}) {
  const t = useT()
  const cal = useCalendarProps()
  const [open, setOpen] = useState(false)
  const fmt = displayFormat || (withTime ? 'dd.MM.yyyy HH:mm' : 'dd.MM.yyyy')
  const text = value ? format(value, fmt) : ''
  const disabledDays = [minDate && { before: minDate }, maxDate && { after: maxDate }].filter(Boolean)
  // Açılış ayı: seçili değer; yoksa bugün — izin verilen aralığa kırpılmış (alt sınır gelecekteyse onun ayı).
  const now = new Date()
  const startMonth = value || (minDate && minDate > now ? minDate : maxDate && maxDate < now ? maxDate : undefined)

  function pickDay(day) {
    if (!day) return
    const d = new Date(day)
    if (withTime) d.setHours(value ? value.getHours() : 0, value ? value.getMinutes() : 0, 0, 0)
    onChange(d)
    if (!withTime) setOpen(false)
  }
  function pickTime(v) {
    const hm = parseHhmm(v)
    if (!hm) return
    const d = value ? new Date(value) : new Date()
    d.setHours(hm[0], hm[1], 0, 0)
    onChange(d)
  }

  return (
    <div data-slot="date-picker" className={cn('relative flex w-full min-w-0', className)}>
      <Popover open={open} onOpenChange={(next) => { if (!disabled) setOpen(next) }}>
        <PopoverTrigger asChild>
          <DateTrigger label={label} text={text} placeholder={placeholder} reserveEnd={reserveEnd}
            disabled={disabled} className={triggerClassName} {...triggerProps} />
        </PopoverTrigger>
        <DatePopoverContent>
          <Calendar mode="single" selected={value || undefined}
            onSelect={(d, triggerDate) => pickDay(d || triggerDate)}
            defaultMonth={startMonth}
            disabled={disabledDays.length ? disabledDays : undefined}
            modifiers={modifiers} autoFocus {...cal} />
          {withTime && (
            <div className="flex flex-wrap items-center justify-between gap-2 border-t px-3 py-2">
              <TimeField value={value ? format(value, 'HH:mm') : ''} onChange={pickTime} step={timeStep} />
              <Button type="button" size="sm" onClick={() => setOpen(false)}>{t('dp.done')}</Button>
            </div>
          )}
        </DatePopoverContent>
      </Popover>
      {endSlot}
    </div>
  )
}
