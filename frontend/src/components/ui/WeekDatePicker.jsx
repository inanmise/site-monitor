import { createContext, useContext, useEffect, useState } from 'react'
import { format, getISOWeek, getISOWeekYear } from 'date-fns'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Calendar } from '@/components/shadcn/calendar'
import { Popover, PopoverTrigger } from '@/components/shadcn/popover'
import { DatePopoverContent, DateTrigger, useCalendarProps } from './DatePickerParts.jsx'

/** 'yyyy-mm-dd' → öğlen yerel Date (gün sınırı kaymasın). */
const atNoon = (v) => (v ? new Date(v + 'T12:00:00') : null)

/** Hafta numarası hücresinin ihtiyaçları (seçici → hücre). Hücre bileşeni modül düzeyinde durur: render
 *  içinde tanımlanan bir bileşen her çizimde YENİ tip olur ve DayPicker hücreleri söküp yeniden kurardı. */
const WeekCtx = createContext(null)

// Hafta numarası hücresi: gerçek düğme (klavyeyle erişilir) → haftanın Pazartesi'si. Rapor noktası süs değil
// bilgi: düğmenin adına da eklenir.
function WeekNumberCell({ week, children, ...props }) {
  const ctx = useContext(WeekCtx)
  const monday = week.days[0]?.date
  if (!ctx || !monday) return <td {...props}>{children}</td>
  const wy = getISOWeekYear(monday), wn = getISOWeek(monday)
  const marked = !!ctx.isMarked?.(wy, wn)
  const name = ctx.t('wdp.pickWeek', wn, wy) + (marked ? ` — ${ctx.t('wr.weekHasReport')}` : '')
  return (
    <td {...props} aria-label={undefined}>
      <div className="flex size-(--cell-size) items-center justify-center">
        <Button type="button" variant="ghost" data-marked={marked || undefined}
          aria-label={name} onClick={() => ctx.pick(monday)}
          className="relative size-full p-0 text-[0.75rem] font-semibold text-muted-foreground">
          {children}
          {marked && <span aria-hidden="true" className="absolute bottom-0.5 left-1/2 size-1.5 -translate-x-1/2 rounded-full bg-success" />}
        </Button>
      </div>
    </td>
  )
}
const CALENDAR_COMPONENTS = { WeekNumber: WeekNumberCell }

/**
 * Hafta odaklı tarih seçici — shadcn Date Picker deseni (Popover + Calendar), native date input yerine
 * (tarayıcı dilinde çizilir ve hafta kavramı yoktur). ISO hafta numarası sütunu (Pazartesi başlangıç):
 * hafta numarasına basmak o haftanın PAZARTESİ'sini, bir güne basmak o günü seçer. Raporu olan haftalarda
 * numaranın altında yeşil nokta. İpucu (`hint`) açılır pencerenin üstünde GÖRÜNÜR metin (dokunmatikte de).
 *
 * Props (değişmedi): value 'yyyy-mm-dd' | '', onChange(dateStr), placeholder, hint,
 *        isMarked(year, week) → bool, onViewYearChange(year) — görüntülenen ayın yılı (açıkken).
 */
export default function WeekDatePicker({
  value, onChange, placeholder, hint, isMarked, onViewYearChange,
}) {
  const t = useT()
  const locale = useDateLocale()
  const cal = useCalendarProps()
  const [open, setOpen] = useState(false)
  const selected = atNoon(value)
  const [month, setMonth] = useState(() => selected || new Date())
  const viewYear = month.getFullYear()

  // Açılışta görünüm seçili güne (yoksa bugüne) döner — eski davranış.
  function onOpenChange(next) {
    if (next) setMonth(atNoon(value) || new Date())
    setOpen(next)
  }

  useEffect(() => {
    if (open) onViewYearChange?.(viewYear)
  }, [open, viewYear, onViewYearChange])

  function pick(day) {
    onChange(format(day, 'yyyy-MM-dd'))
    setOpen(false)
  }

  return (
    <WeekCtx.Provider value={{ isMarked, pick, t }}>
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <DateTrigger text={selected ? selected.toLocaleDateString(locale) : ''} placeholder={placeholder}
          className="w-full sm:w-[180px]" />
      </PopoverTrigger>
      <DatePopoverContent>
        {hint && <p className="max-w-[18rem] px-3 pt-3 text-xs text-muted-foreground">{hint}</p>}
        <Calendar mode="single" selected={selected || undefined}
          onSelect={(d, triggerDate) => { const day = d || triggerDate; if (day) pick(day) }}
          month={month} onMonthChange={setMonth}
          showWeekNumber ISOWeek autoFocus
          components={CALENDAR_COMPONENTS}
          {...cal} />
        <div className="flex flex-wrap items-center gap-1.5 border-t px-3 py-2">
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-success" /> {t('wr.weekHasReport')}
          </span>
          <span className="flex-1" />
          {value && (
            <Button type="button" variant="outline" size="sm" onClick={() => { onChange(''); setOpen(false) }}>
              {t('wr.clear')}
            </Button>
          )}
          <Button type="button" variant="secondary" size="sm" onClick={() => pick(new Date())}>
            {t('wr.today')}
          </Button>
        </div>
      </DatePopoverContent>
    </Popover>
    </WeekCtx.Provider>
  )
}
