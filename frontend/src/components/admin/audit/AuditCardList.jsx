import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { localDayKey } from '../../../utils/localDay.js'
import { EventBadge, OutcomeBadge } from '../ToneBadge.jsx'
import { ActorLabel, AnomalyChips, useExactTime } from './AuditBits.jsx'
import { eventClass, eventLabel, relativeTime, summaryLine, OUTCOME_KEYS } from './auditFormat.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'

/** Yerel gün anahtarı → "Bugün" / "Dün" / "Per 24 Eyl". */
function dayHeading(key, t, locale) {
  const today = localDayKey(new Date().toISOString())
  const y = new Date(); y.setDate(y.getDate() - 1)
  const yesterday = localDayKey(y.toISOString())
  if (key === today) return t('audit.today')
  if (key === yesterday) return t('audit.yesterday')
  const [yy, mm, dd] = key.split('-').map(Number)
  return new Date(yy, mm - 1, dd).toLocaleDateString(locale, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
}

/**
 * Denetim olayları — TELEFON görünümü (<768 px, tek varyant): güne göre gruplu kartlar. Kartın tamamı tek bir shadcn
 * Button (içerik yalnız satır-içi öğeler — geçerli HTML); dokununca tam genişlik ayrıntı Sheet'i açılır. Kesin zaman,
 * IP ve konum GÖRÜNÜR (dokunmatikte ipucu yok); IP uzun olsa da kart içinde kırılır.
 */
export default function AuditCardList({ rows, selectedId, onOpen, loading, initialLoading }) {
  const t = useT()
  const locale = useDateLocale()
  const exact = useExactTime()

  if (initialLoading) {
    return (
      <ul className="flex list-none flex-col gap-2" aria-hidden="true">
        {Array.from({ length: 5 }, (_, i) => (
          <li key={i} className="flex flex-col gap-2 rounded-lg border p-3" data-skeleton="true">
            <div className="flex gap-2"><Skeleton className="h-5 w-24" /><Skeleton className="h-5 w-16" /></div>
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-48" />
          </li>
        ))}
      </ul>
    )
  }

  const groups = []
  for (const row of rows) {
    const key = localDayKey(row.event_time) || '—'
    const last = groups[groups.length - 1]
    if (last && last.key === key) last.rows.push(row)
    else groups.push({ key, rows: [row] })
  }

  return (
    <div className={loading ? 'opacity-60 transition-opacity motion-reduce:transition-none' : undefined} aria-busy={loading || undefined}>
      {groups.map(g => (
        <section key={g.key} aria-label={dayHeading(g.key, t, locale)} className="mb-4 last:mb-0">
          <h3 className="mb-2 flex items-baseline justify-between gap-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            <span>{g.key === '—' ? '—' : dayHeading(g.key, t, locale)}</span>
            <span className="font-normal normal-case tabular-nums">{t('audit.eventsCount', g.rows.length)}</span>
          </h3>
          <ul className="flex list-none flex-col gap-2">
            {g.rows.map(row => {
              const label = eventLabel(row.event_type, t)
              const outcomeKey = OUTCOME_KEYS[row.outcome]
              const outcome = outcomeKey ? t(outcomeKey) : (row.outcome || '—')
              const rel = relativeTime(row.event_time, t) || '—'
              const summary = summaryLine(row)
              const geo = [row.ip_city, row.ip_country].filter(Boolean).join(', ')
              const actor = row.actor || t('audit.systemActor')
              const selected = row.id === selectedId
              return (
                <li key={row.id}>
                  <Button type="button" variant="outline" onClick={() => onOpen(row.id)}
                    data-state={selected ? 'selected' : undefined}
                    aria-label={t('audit.openEvent', `${label} — ${outcome} — ${actor} — ${exact(row.event_time)}`)}
                    className="h-auto w-full min-w-0 flex-col items-stretch gap-1.5 px-3 py-2.5 text-left font-normal whitespace-normal shadow-none data-[state=selected]:border-primary data-[state=selected]:bg-primary/5">
                    <span className="flex min-w-0 items-start justify-between gap-2">
                      <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                        <EventBadge kind={eventClass(row.event_type)} title={row.event_type}>{label}</EventBadge>
                        <OutcomeBadge outcome={row.outcome}>{outcome}</OutcomeBadge>
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground">{rel}</span>
                    </span>
                    <span className="flex min-w-0 text-sm"><ActorLabel row={row} /></span>
                    {summary && <span className="line-clamp-2 text-xs break-words text-muted-foreground">{summary}</span>}
                    <span className="flex min-w-0 flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                      <span className="tabular-nums">{exact(row.event_time)}</span>
                      {row.ip_address && <span className="font-mono break-all">{row.ip_address}</span>}
                      {geo && <span className="break-words">{geo}</span>}
                    </span>
                    <AnomalyChips flags={row.anomaly_flags} />
                  </Button>
                </li>
              )
            })}
          </ul>
        </section>
      ))}
    </div>
  )
}
