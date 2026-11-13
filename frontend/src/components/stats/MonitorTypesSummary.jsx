import { useMemo } from 'react'
import { ChevronRight, LayoutGrid } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { TYPE_META, TYPE_ORDER, successPct } from '../monitoring/overviewMeta.js'
import { typeTone } from '../monitoring/OverviewTypeCard.jsx'
import SourceGate from './SourceGate.jsx'
import { monitorTypeRows, statsNum, statsPct } from './statsInsights.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * İzleme türleri özeti (2026-11-13, İstatistikler #8): İzleme Panosu yanıtından tür başına kompakt kart — son 24 saat başarı
 * oranı, sorunlu / gecikmiş / açık alarm sayısı. Ton İzleme Panosu kuralıyla (`typeTone`): okunamayan tür, hepsi bilinmeyen
 * tür ve okunamayan alarmlar NÖTR (asla yeşil). Kart → `?tab=monitoring&mo_type=<tür>`. Yalnız `monitoring.read` iznine.
 * Test kancaları: `data-slot="stats-mtypes"`, kart `stats-mtype` + `data-type` + `data-tone`.
 */
const TONE = {
  ok: 'text-success', warn: 'text-amber-700 dark:text-amber-300', bad: 'text-destructive', neutral: 'text-muted-foreground',
}

export default function MonitorTypesSummary({ source, onRetry }) {
  const t = useT()
  if (!source || source.status === 'hidden') return null
  return (
    <Card data-slot="stats-mtypes" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><LayoutGrid aria-hidden="true" className="size-4 text-primary" />{t('stx.mtTitle')}</CardTitle>
        <CardDescription>{t('stx.mtDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="px-4 sm:px-6">
        <SourceGate source={source} onRetry={onRetry} skeletonClassName="h-32 rounded-lg">
          {(ov) => <TypeGrid ov={ov} />}
        </SourceGate>
      </CardContent>
    </Card>
  )
}

function TypeGrid({ ov }) {
  const t = useT()
  const rows = useMemo(() => monitorTypeRows(ov, TYPE_ORDER), [ov])
  const alertsUnknown = ov?.alerts_unavailable === true
  if (!rows.length) return <p data-slot="stats-mtypes-empty" className="m-0 text-sm text-muted-foreground">{t('stx.mtEmpty')}</p>
  // Kart sayfada yarım genişlikte de durabilir: sütun sayısını GÖRÜNÜM değil KAP genişliği belirler (ekran 1280'de
  // 3 sütun ~150 px'lik kartlarda tür adını harf harf kırıyordu).
  return (
    <div className="@container min-w-0">
    <ul className="m-0 grid min-w-0 list-none grid-cols-1 gap-2 p-0 @md:grid-cols-2 @4xl:grid-cols-3">
      {rows.map((ty) => {
        const meta = TYPE_META[ty.type]
        const tone = typeTone(ty, { alertsUnknown })
        const gone = ty.unavailable === true, statsGone = ty.stats_unavailable === true
        const pct = gone || statsGone ? null : successPct(ty)
        const down = Number(ty.down || 0), stale = Number(ty.stale || 0), open = Number(ty.open_alerts || 0)
        const label = t(meta.labelKey)
        return (
          <li key={ty.type} className="min-w-0">
            <Button type="button" variant="outline" data-slot="stats-mtype" data-type={ty.type} data-tone={tone}
              aria-label={t('stx.mtOpen', label)} onClick={() => navigateTo('monitoring', { mo_type: ty.type })}
              className="h-auto min-h-16 w-full justify-start gap-3 rounded-lg px-3 py-2.5 text-left font-normal whitespace-normal">
              <span className={cn('grid size-9 shrink-0 place-items-center rounded-lg bg-muted', TONE[tone])}>
                <meta.Icon aria-hidden="true" className="size-5" />
              </span>
              <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="font-semibold [overflow-wrap:anywhere]">{label}</span>
                <span className="text-xs text-muted-foreground">
                  {gone ? t('stx.mtUnreadable') : t('stx.mtCounts', statsNum(ty.active ?? 0), statsNum(down), statsNum(stale),
                    alertsUnknown ? '—' : statsNum(open))}
                </span>
              </span>
              <span className={cn('shrink-0 text-right text-lg font-bold tabular-nums', TONE[tone])}>{statsPct(pct)}</span>
              <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            </Button>
          </li>
        )
      })}
    </ul>
    </div>
  )
}
