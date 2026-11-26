/**
 * Nöbet Rutinleri — hafta şeridi: ISO haftanın yedi günü tek satırda (390 px'te de sığar), her gün kendi durum simgesi ve
 * `çözülen/zorunlu` sayısıyla. Seçili gün vurgulu (`aria-current`); hafta sonu / tatil soluk. Üstte hafta gezintisi.
 */
import { memo } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { dayStatusTone, isoWeekday, shortDate } from './routineModel.js'
import { statusIcon } from './routineUi.jsx'

const TONE_TEXT = {
  success: 'text-success', partial: 'text-success', warning: 'text-amber-600 dark:text-amber-400',
  danger: 'text-destructive', open: 'text-primary', muted: 'text-muted-foreground',
}

function WeekStripImpl({ week, date, today, onSelect, onShiftWeek, onToday }) {
  const t = useT()
  const days = Array.isArray(week?.days) ? week.days : []
  const isThisWeek = days.some((d) => d.date === today)
  return (
    <nav data-slot="or-week-strip" aria-label={t('or.week.stripLabel')} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="m-0 text-sm font-medium tabular-nums">
          {week?.from && week?.to ? t('or.week.range', shortDate(week.from), shortDate(week.to, true)) : t('or.week.title')}
        </p>
        <div className="flex items-center gap-1">
          <Button type="button" variant="outline" size="icon" className="size-8 pointer-coarse:size-10" data-slot="or-week-prev"
            aria-label={t('or.week.prev')} onClick={() => onShiftWeek(-1)}>
            <ChevronLeft aria-hidden="true" />
          </Button>
          <Button type="button" variant="outline" size="sm" className="h-8 pointer-coarse:h-10" data-slot="or-week-today"
            disabled={date === today} onClick={onToday}>
            {isThisWeek ? t('or.week.today') : t('or.week.thisWeek')}
          </Button>
          <Button type="button" variant="outline" size="icon" className="size-8 pointer-coarse:size-10" data-slot="or-week-next"
            aria-label={t('or.week.next')} disabled={!!week?.to && week.to >= today} onClick={() => onShiftWeek(1)}>
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
      </div>
      <ol className="m-0 grid list-none grid-cols-7 gap-1 p-0">
        {days.map((d) => {
          const wd = isoWeekday(d.date)
          const code = !d.workday ? 'NOT_WORKDAY' : (d.date > today ? 'FUTURE' : (d.status || 'NONE'))
          const tone = dayStatusTone(code)
          const Icon = statusIcon(code)
          const selected = d.date === date
          const showCount = d.workday && Number(d.required) > 0 && d.date <= today
          const holiday = typeof d.holiday_name === 'string' && d.holiday_name.trim() ? d.holiday_name.trim() : null
          const base = t('or.week.dayAria', t(`or.weekday.${wd}`), shortDate(d.date, true), t(`or.status.${code}`),
            showCount ? `${d.resolved ?? 0}/${d.required}` : '—')
          // Tatil adı ekran okuyucuya ve (fareyle) ipucuna da söylenir — yalnız soluk renkle anlatılmaz
          const label = holiday ? `${base} · ${t('or.week.holiday', holiday)}` : base
          return (
            <li key={d.date} className="min-w-0">
              <Button type="button" variant={selected ? 'secondary' : 'ghost'} data-slot="or-week-day" data-date={d.date}
                data-status={code} data-today={d.date === today || undefined}
                aria-current={selected ? 'date' : undefined} aria-label={label} title={holiday || undefined}
                data-holiday={holiday ? 'true' : undefined}
                disabled={d.date > today && !selected}
                onClick={() => onSelect(d.date)}
                className={cn('flex h-auto min-h-14 w-full min-w-0 flex-col items-center gap-0.5 rounded-lg px-0.5 py-1.5',
                  selected && 'ring-2 ring-primary/40', !d.workday && 'opacity-60')}>
                <span className={cn('text-[11px] leading-none font-medium uppercase', d.date === today ? 'text-primary' : 'text-muted-foreground')}>
                  {t(`or.weekdayShort.${wd}`)}
                </span>
                <span className="text-sm leading-none font-semibold tabular-nums">{Number(d.date.slice(8, 10))}</span>
                <Icon aria-hidden="true" className={cn('size-3.5', TONE_TEXT[tone])} />
                <span className="text-[10px] leading-none text-muted-foreground tabular-nums">
                  {showCount ? `${d.resolved ?? 0}/${d.required}` : ' '}
                </span>
              </Button>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

export default memo(WeekStripImpl)
