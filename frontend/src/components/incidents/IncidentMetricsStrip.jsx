import { Timer, UserCheck, CheckCircle2, Hourglass, Info } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import HintPopover from '../ui/HintPopover.jsx'
import { Card } from '@/components/shadcn/card'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import LiveDuration from './LiveDuration.jsx'
import { SeverityBadge } from './IncidentBadges.jsx'
import { minutesText } from './incidentsModel.js'

/** Tek ölçüm kutusu: başlık + açıklama düğmesi + 24 sa / 7 g değerleri + örnek sayısı. Bilinmeyen "—", asla 0. */
function MetricCell({ Icon, title, hint, v24, v7, sample, t }) {
  const text24 = minutesText(v24, t)
  const text7 = minutesText(v7, t)
  return (
    <div data-slot="incident-metric" className="flex min-w-0 flex-col gap-1 p-3">
      <div className="flex min-w-0 items-start gap-1.5">
        <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <span className="min-w-0 text-xs font-semibold [overflow-wrap:anywhere]">{title}</span>
        <HintPopover content={hint} aria-label={title}
          triggerClassName="ml-auto shrink-0 text-muted-foreground">
          <Info aria-hidden="true" className="size-3.5" />
        </HintPopover>
      </div>
      <dl className="m-0 grid grid-cols-2 gap-2">
        <div className="min-w-0">
          <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{t('incov.metrics.24h')}</dt>
          <dd data-slot="incident-metric-24h" data-known={text24 != null || undefined}
            className={cn('m-0 text-lg font-bold tabular-nums', text24 == null && 'text-muted-foreground')}>{text24 ?? '—'}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{t('incov.metrics.7d')}</dt>
          <dd data-slot="incident-metric-7d" data-known={text7 != null || undefined}
            className={cn('m-0 text-lg font-bold tabular-nums', text7 == null && 'text-muted-foreground')}>{text7 ?? '—'}</dd>
        </div>
      </dl>
      {sample != null && <span className="text-[11px] text-muted-foreground">{t('incov.metrics.sample', sample)}</span>}
    </div>
  )
}

/**
 * Müdahale süreleri şeridi (2026-11-13) — sunucu özetinden (`/summary`): ortalama sahiplenme (MTTA) ve çözüm (MTTR)
 * süreleri, son 24 saat ve 7 gün (sessiz kapanışlar MTTR'ye girmez), ve EN UZUN SÜREN AÇIK olay (tıklayınca detayı
 * açılır). Eski sunucu bu alanları göndermez → şerit çizilmez; okunamayan değer "—" (asla 0 ya da yeşil).
 */
export default function IncidentMetricsStrip({ summary, onOpenIncident }) {
  const t = useT()
  const dateLocale = useDateLocale()
  if (!summary || summary.sampled != null) return null   // eski sunucu yedeği (türetilmiş özet) — ölçüm yok
  const longest = summary.longestOpen
  const longestName = longest?.name || '—'
  return (
    <Card data-slot="incident-metrics" className="gap-0 py-0 shadow-none">
      <h3 className="px-3 pt-3 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('incov.metrics.title')}</h3>
      <div className="grid grid-cols-1 divide-y sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <MetricCell Icon={UserCheck} title={t('incov.metrics.mtta')} hint={t('incov.metrics.mttaHint')}
          v24={summary.mtta24} v7={summary.mtta7} sample={summary.timingKnown ? summary.mttaSample : null} t={t} />
        <MetricCell Icon={CheckCircle2} title={t('incov.metrics.mttr')} hint={t('incov.metrics.mttrHint')}
          v24={summary.mttr24} v7={summary.mttr7} sample={summary.timingKnown ? summary.mttrSample : null} t={t} />
        <div data-slot="incident-longest" className="flex min-w-0 flex-col gap-1 p-3">
          <div className="flex min-w-0 items-start gap-1.5">
            <Hourglass aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <span className="min-w-0 text-xs font-semibold [overflow-wrap:anywhere]">{t('incov.metrics.longest')}</span>
          </div>
          {longest ? (
            <Button type="button" variant="ghost" data-slot="incident-longest-open"
              aria-label={t('incov.metrics.openLongest', longestName)}
              onClick={() => onOpenIncident?.(longest.id)}
              className="h-auto min-h-10 w-full flex-col items-start justify-start gap-1 px-2 py-1.5 text-left whitespace-normal">
              <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                <SeverityBadge level={longest.alert_level} className="text-[0.7em]" />
                <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{longestName}</span>
              </span>
              <span className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                <Timer aria-hidden="true" className="size-3.5" />
                <LiveDuration since={longest.started_at} live format={(d) => t('incov.ongoingFor', d)} />
                <span aria-hidden="true">·</span>
                <span>{formatIncidentTime(longest.started_at, dateLocale)}</span>
              </span>
            </Button>
          ) : (
            <span data-slot="incident-longest-none" className="text-sm text-muted-foreground">
              {summary.open === 0 ? t('incov.metrics.noneOpen') : t('incov.metrics.unknown')}
            </span>
          )}
        </div>
      </div>
      {summary.timingCapped && (
        <p className="m-0 border-t px-3 py-1.5 text-[11px] text-muted-foreground">{t('incov.metrics.capped', 20000)}</p>
      )}
    </Card>
  )
}
