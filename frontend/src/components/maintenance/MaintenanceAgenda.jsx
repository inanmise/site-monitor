import { useMemo } from 'react'
import { CalendarDays } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { DAY_MS, buildAgenda, localDayOf } from './maintenanceSchedule.js'
import { RecurringMark, TargetsSummary, countText, useScheduleText } from './maintenanceUi.jsx'

/**
 * Ajanda — önümüzdeki 7 gün, güne göre gruplu; telefonda kart listesi, geniş ekranda saat sütunlu satırlar.
 * Test kancaları: gün `data-slot="mw-agenda-day"` (+ `data-day`), satır `data-slot="mw-agenda-item"` (+ `data-id`).
 */
export default function MaintenanceAgenda({ windows, now, canManage, onOpen, teamId, teamName }) {
  const t = useT()
  const locale = useDateLocale()
  const { range } = useScheduleText()
  const groups = useMemo(() => buildAgenda(windows, now), [windows, now])
  const total = groups.reduce((n, g) => n + g.items.length, 0)
  const todayKey = localDayOf(now)
  const tomorrowKey = localDayOf(now + DAY_MS)
  const heading = (g) => {
    const label = g.date.toLocaleDateString(locale, { weekday: 'short', day: 'numeric', month: 'short' })
    if (g.key === todayKey) return `${t('mw.today')} · ${label}`
    if (g.key === tomorrowKey) return `${t('mw.tomorrow')} · ${label}`
    return label
  }

  if (total === 0) {
    return <StatusBlock tone="neutral" icon={CalendarDays} title={t('mw.agendaTitle')} description={t('mw.agendaEmpty')} className="py-10" />
  }
  return (
    <div data-slot="mw-agenda" className="flex min-w-0 flex-col gap-3">
      {groups.map((g) => (
        <section key={g.key} data-slot="mw-agenda-day" data-day={g.key} aria-label={heading(g)}
          className={cn('min-w-0 rounded-lg border bg-card', g.key === todayKey && 'border-primary/40')}>
          <h4 className={cn('flex items-center gap-2 border-b px-3 py-1.5 text-[0.8em] font-bold tracking-wide uppercase',
            g.key === todayKey ? 'text-primary' : 'text-muted-foreground')}>
            {heading(g)}
            <span className="ml-auto font-semibold normal-case tabular-nums">{g.items.length > 0 ? countText(t, g.items.length, 'mw.agendaOne', 'mw.agendaCount') : ''}</span>
          </h4>
          {g.items.length === 0 ? (
            <p className="px-3 py-2 text-[0.85em] text-muted-foreground">{t('mw.agendaNone')}</p>
          ) : (
            <ul className="m-0 flex list-none flex-col divide-y p-0">
              {g.items.map(({ w, start, end, running }) => (
                <li key={`${w.id}-${start}`} data-slot="mw-agenda-item" data-id={w.id} data-running={running || undefined}
                  className={cn('grid min-w-0 grid-cols-1 gap-x-3 gap-y-1 px-3 py-2 sm:grid-cols-[9.5rem_minmax(0,1fr)_auto] sm:items-center',
                    running && 'bg-success/[0.05]')}>
                  <span className="text-[0.86em] font-semibold text-foreground tabular-nums">{range(start, end, w.timezone)}</span>
                  <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                    {canManage ? (
                      <Button type="button" variant="link" size="sm" onClick={() => onOpen(w)}
                        aria-label={t('a11y.rowAction', w.name, t('mw.edit'))}
                        // max-w-full: sarmalı (flex-wrap) satırda öğe küçülmez, max-content genişliğini korur → uzun ad taşardı (768/390 ölçümü)
                        className="h-auto max-w-full min-w-0 justify-start px-0 py-0 text-left font-semibold whitespace-normal [overflow-wrap:anywhere]">
                        {w.name}
                      </Button>
                    ) : <span className="max-w-full min-w-0 font-semibold [overflow-wrap:anywhere]">{w.name}</span>}
                    <RecurringMark w={w} />
                    <span className="text-[0.85em] text-muted-foreground"><TargetsSummary w={w} iconSize={12} /></span>
                    {w.team_id != null && (
                      <TeamBadge teamId={w.team_id} teamName={w.team_id === teamId ? teamName : undefined} size={11} className="text-[0.8em]" />
                    )}
                  </span>
                  <span className="sm:justify-self-end">
                    {running && <ToneBadge tone="success" data-status="active" className="rounded-full font-bold">{t('mw.inProgress')}</ToneBadge>}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </div>
  )
}
