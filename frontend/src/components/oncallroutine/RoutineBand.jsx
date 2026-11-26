/**
 * Nöbet Rutinleri — dönem bandı: "Günlük görevler", "Bu haftanın görevleri", "Bu ayın görevleri". Her bant kendi
 * `çözülen/toplam` çipini taşır; günlük, haftalık ve aylık işler ekranda ayrı durur (kullanıcı isteği).
 */
import { CalendarDays, CalendarRange, CalendarClock, CircleCheck } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { bandStats, isoWeekday, shortDate } from './routineModel.js'

const ICON = { DAY: CalendarDays, WEEK: CalendarRange, MONTH: CalendarClock }

/** Haftalık bandın alt yazısı: en geç son gün. */
function latestDue(group) {
  let max = null
  for (const s of group?.sections || []) for (const it of s.items || []) if (it.due_day && (!max || it.due_day > max)) max = it.due_day
  return max
}

export default function RoutineBand({ group, date, children }) {
  const t = useT()
  const Icon = ICON[group.kind] || CalendarDays
  const st = bandStats(group, date)
  const done = st.total > 0 && st.resolved >= st.total
  const due = group.kind === 'WEEK' ? latestDue(group) : null
  const headId = `or-band-${group.kind}`
  return (
    <section data-slot="or-band" data-kind={group.kind} aria-labelledby={headId} className="flex flex-col gap-2.5">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 px-0.5">
        <span className="inline-flex size-7 items-center justify-center rounded-lg bg-primary/10 text-primary" aria-hidden="true">
          <Icon className="size-4" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <h2 id={headId} className="m-0 text-base font-semibold tracking-tight">{t(`or.band.${group.kind}`)}</h2>
          {group.kind === 'WEEK' && due && (
            <p className="m-0 text-xs text-muted-foreground">{t('or.band.weekDue', t(`or.weekday.${isoWeekday(due)}`), shortDate(due))}</p>
          )}
          {group.kind === 'MONTH' && <p className="m-0 text-xs text-muted-foreground">{t('or.band.monthHint')}</p>}
        </div>
        <Badge variant="outline" data-slot="or-band-count" data-done={done || undefined}
          className={cn('h-auto min-h-6 gap-1 rounded-md px-2 tabular-nums', done ? 'border-success/30 bg-success/10 text-success' : 'text-muted-foreground')}>
          {done && <CircleCheck aria-hidden="true" />}
          <span aria-hidden="true">{st.resolved}/{st.total}</span>
          <span className="sr-only">{t('or.band.countAria', st.resolved, st.total)}</span>
        </Badge>
      </header>
      <div className="flex flex-col gap-2.5">{children}</div>
    </section>
  )
}
