import { useMemo } from 'react'
import { Activity, RefreshCcw } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SourceGate from './SourceGate.jsx'
import { statsNum, statsPct } from './statsInsights.js'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { CartesianGrid, ChartContainer, ChartTooltip, ChartTooltipContent, Line, LineChart, XAxis, YAxis } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

/**
 * Eğilim kartları (2026-11-13, İstatistikler #11 + #12).
 *
 * <b>Zamanında yenileme</b> (`/api/stats/renewal-timeliness`): son 3 / 6 / 12 ayda süresi DOLMADAN yapılan yenilemelerin
 * (yenilemeler + yenilenmeden dolanlar) içindeki payı — aylık yönetici özetinin kuralı. Pencerede hiç yenileme / dolum yoksa
 * "—" (asla %0 ya da %100 uydurulmaz).
 *
 * <b>90 günlük sağlık</b> (`/api/stats/health-series`): günlük görüntülerden temiz / değerlendirilen %. Tüm izlemeyi gören
 * kurum serisini ya da bir takımı seçer; kapsamlı kullanıcı yalnız kendi takımlarından BİRİNİ seçer (takımlar toplanmaz).
 * Veri dağıtımdan itibaren birikir — "şu tarihten beri toplanıyor" boş durumu. Test kancaları: `data-slot="stats-renewal"`,
 * `stats-renewal-win` (+ `data-months`), `stats-health`, `stats-health-team`.
 */
const TONE = { ok: 'text-success', warn: 'text-amber-700 dark:text-amber-300', bad: 'text-destructive', neutral: 'text-muted-foreground' }

function renewalTone(v) {
  if (v == null) return 'neutral'
  return v >= 95 ? 'ok' : v >= 80 ? 'warn' : 'bad'
}

export function RenewalTimelinessCard({ source, onRetry }) {
  const t = useT()
  return (
    <Card data-slot="stats-renewal" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><RefreshCcw aria-hidden="true" className="size-4 text-primary" />{t('stx.renTitle')}</CardTitle>
        <CardDescription>{t('stx.renDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="px-4 sm:px-6">
        <SourceGate source={source} onRetry={onRetry} skeletonClassName="h-28 rounded-lg">
          {(data) => (
            <ul className="m-0 grid min-w-0 list-none grid-cols-1 gap-2 p-0 sm:grid-cols-3">
              {(data.windows || []).map((w) => {
                const tone = renewalTone(w.on_time_pct)
                const denom = Number(w.renewals || 0) + Number(w.lapses || 0)
                return (
                  <li key={w.months} data-slot="stats-renewal-win" data-months={w.months} data-tone={tone}
                    className="flex min-w-0 flex-col gap-1 rounded-lg border bg-card px-3 py-2.5">
                    <span className="text-xs font-semibold text-muted-foreground">{t('stx.renWindow', w.months)}</span>
                    <b className={cn('text-2xl leading-tight font-extrabold tabular-nums', TONE[tone])}>{statsPct(w.on_time_pct)}</b>
                    <span className="text-xs text-muted-foreground">
                      {denom === 0 ? t('stx.renNone') : t('stx.renSub', statsNum(w.before_expiry), statsNum(denom))}
                    </span>
                    {denom > 0 && (
                      <span className="text-xs text-muted-foreground">
                        {t('stx.renDetail', statsNum(w.after_expiry), statsNum(w.lapses), statsNum(w.last_minute))}
                      </span>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </SourceGate>
      </CardContent>
    </Card>
  )
}

export function HealthSeriesCard({ source, onRetry, team, onTeam }) {
  const t = useT()
  return (
    <Card data-slot="stats-health" className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="gap-1 px-4 sm:px-6">
        <CardTitle role="heading" aria-level={3} className="flex items-center gap-2 text-base"><Activity aria-hidden="true" className="size-4 text-primary" />{t('stx.hsTitle')}</CardTitle>
        <CardDescription>{t('stx.hsDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3 px-4 sm:px-6">
        <SourceGate source={source} onRetry={onRetry} skeletonClassName="h-48 rounded-lg">
          {(data) => <HealthBody data={data} team={team} onTeam={onTeam} />}
        </SourceGate>
      </CardContent>
    </Card>
  )
}

function HealthBody({ data, team, onTeam }) {
  const t = useT()
  const options = useMemo(() => {
    const out = data.org_allowed ? [{ value: 'org', label: t('stx.hsOrg') }] : []
    for (const tm of data.teams || []) out.push({ value: String(tm.id), label: tm.name || t('stv.teamUnknown') })
    return out
  }, [data, t])
  const current = data.scope === 'org' ? 'org' : data.team_id != null ? String(data.team_id) : (team ?? '')
  const points = Array.isArray(data.points) ? data.points : []
  const config = useMemo(() => ({ health: { label: t('stx.hsSeries'), color: 'var(--chart-2, var(--primary))' } }), [t])
  const chart = points.map((p) => ({ day: p.day, label: p.day.slice(5), health: p.health_pct }))
  const last = points.length ? points[points.length - 1] : null
  return (
    <div className="flex min-w-0 flex-col gap-3">
      {options.length > 1 && (
        <div data-slot="stats-health-team" className="w-full sm:w-80">
          <SearchableSelect value={current} onChange={(v) => onTeam(v === 'org' ? null : v)} options={options}
            ariaLabel={t('stx.hsTeam')} wrapLabels />
        </div>
      )}
      {data.scope === 'none' ? (
        <p data-slot="stats-health-noscope" className="m-0 text-sm text-muted-foreground">{t('stx.hsNoTeam')}</p>
      ) : points.length < 2 ? (
        <p data-slot="stats-health-collecting" className="m-0 text-sm text-muted-foreground">
          {data.collecting_since ? t('stx.hsCollecting', formatDateOnly(data.collecting_since)) : t('stx.hsCollectingSoon')}
        </p>
      ) : (
        <>
          <p className="m-0 text-sm">
            {t('stx.hsLast', statsPct(last?.health_pct), statsNum(last?.ok), statsNum(last?.evaluated))}
            {data.collecting_since && <span className="text-muted-foreground"> · {t('stx.hsSince', formatDateOnly(data.collecting_since))}</span>}
          </p>
          <div aria-hidden="true" className="min-w-0">
            <ChartContainer config={config} className="aspect-auto h-44 w-full">
              <LineChart data={chart} margin={{ top: 8, right: 8, bottom: 0, left: -8 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis dataKey="label" tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={20} fontSize={11} />
                <YAxis domain={[0, 100]} tickLine={false} axisLine={false} width={40} fontSize={11} />
                <ChartTooltip cursor={false} content={<ChartTooltipContent labelKey="day" />} />
                <Line dataKey="health" type="monotone" stroke="var(--color-health)" strokeWidth={2} dot={chart.length < 3} connectNulls={false} isAnimationActive={false} />
              </LineChart>
            </ChartContainer>
          </div>
        </>
      )}
    </div>
  )
}
