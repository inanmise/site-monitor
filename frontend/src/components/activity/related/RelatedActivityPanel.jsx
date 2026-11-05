import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Radar, RefreshCw, Network, Globe2, ShieldAlert, Waypoints, Server, Users, User, Info, CalendarClock, History, Timer,
  TrendingUp, CheckCircle, AlertOctagon, ChevronRight,
} from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { ReasonBadge, TypeBadge } from '../ActivityParts.jsx'
import { reasonLabel } from '../activityModel.js'
import {
  DEFAULT_WINDOW, WINDOWS, factText, maxCount, offsetText, suspectText, suspectTone, unwrapRelated,
} from './relatedModel.js'

const SUSPECT_ICON = {
  DNS_WIDE: Globe2, NETWORK_WIDE: Network, TLS_WIDE: ShieldAlert, PROXY_WIDE: Waypoints,
  SAME_HOST: Server, SAME_TEAM: Users, SINGLE: User,
}
const FACT_ICON = {
  FIRST_FAILURE_IN_DAYS: CalendarClock, FAILURES_24H: TrendingUp, TIMEOUTS_24H: Timer,
  LAST_SUCCESS: History, RECOVERED_AFTER: CheckCircle, STILL_FAILING: AlertOctagon,
}
const TONE_CLASS = {
  warning: 'border-amber-500/40 bg-amber-500/10 text-amber-900 dark:text-amber-200',
  info: 'border-primary/30 bg-primary/5 text-foreground',
  neutral: 'border-border bg-muted/40 text-foreground',
}

/**
 * "Aynı anda ne oldu?" — kök neden ipuçları (2026-11-05, F). Ayrıntı sayfasında (fe1) yerleşir; `activityId` satırının
 * ± pencere içindeki hatalı kontrolleri YALNIZ görüş kapsamından gösterir (başka takımın durumu sızmaz — sunucu kuralı).
 *
 * Bölümler: pencere seçici (±1 / ±2 / ±5 / ±15 dk), şüpheli neden kartları (`act.rel.suspect.*`), olgular
 * (`act.rel.fact.*`), neden / host / takım dağılımı, ilgili satırlar (dokununca o aktivitenin sayfası). Okunamazsa
 * "okunamadı" + tekrar dene — asla "ilişkili hata yok". Eski sunucu (uç yok: 404 / 405) nötr bilgi.
 * Test kancaları: `data-slot="act-related"` (+ `data-state`), `act-rel-suspect` (+ `data-code` / `data-tone`),
 * `act-rel-fact`, `act-rel-dist`, `act-rel-item`.
 */
export default function RelatedActivityPanel({ activityId, onOpenActivity }) {
  const t = useT()
  const [windowS, setWindowS] = useState(DEFAULT_WINDOW)
  const [state, setState] = useState({ status: 'loading', data: null, error: null })
  const seq = useRef(0)

  const load = useCallback(() => {
    const my = ++seq.current
    setState({ status: 'loading', data: null, error: null })
    return Promise.resolve().then(() => api.getActivityRelated(activityId, windowS))
      .then((res) => {
        if (my !== seq.current) return
        if (res?.success) {
          const data = unwrapRelated(res)
          setState(data ? { status: 'ok', data, error: null } : { status: 'error', data: null, error: null })
        } else if (res?.status === 404 || res?.status === 405) {
          setState({ status: 'unsupported', data: null, error: null })
        } else {
          setState({ status: 'error', data: null, error: res?.error || null })
        }
      })
      .catch((e) => { if (my === seq.current) setState({ status: 'error', data: null, error: e?.message || null }) })
  }, [activityId, windowS])

  useEffect(() => { load() }, [load])
  // Söküldükten sonra gelen yanıt uygulanmaz
  useEffect(() => () => { seq.current++ }, [])

  const data = state.data
  const windowLabel = (s) => t('act.rel.windowOpt', Math.round(s / 60))

  return (
    <Card data-slot="act-related" data-state={state.status} className="min-w-0 gap-3 py-4 shadow-xs">
      <CardHeader className="flex min-w-0 flex-col gap-3 px-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 flex-col gap-1">
          <CardTitle role="heading" aria-level={2} className="flex items-center gap-2 text-base font-semibold">
            <Radar aria-hidden="true" className="size-4 text-muted-foreground" /> {t('act.rel.title')}
          </CardTitle>
          <CardDescription className="text-sm">{t('act.rel.desc', Math.round(windowS / 60))}</CardDescription>
        </div>
        <SegmentedControl value={windowS} onChange={setWindowS} ariaLabel={t('act.rel.window')}
          className="max-w-full shrink-0 flex-wrap" options={WINDOWS.map((s) => ({ value: s, label: windowLabel(s) }))} />
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-4 px-4">
        {state.status === 'loading' && (
          <div className="flex flex-col gap-2" aria-busy="true">
            <span className="sr-only" role="status">{t('act.loading')}</span>
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        )}
        {state.status === 'unsupported' && (
          <AlertBanner tone="info" className="mb-0" role="status" icon={Info}>{t('act.rel.unsupported')}</AlertBanner>
        )}
        {state.status === 'error' && (
          <AlertBanner tone="danger" className="mb-0" role="alert"
            actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={load}>
              <RefreshCw aria-hidden="true" /> {t('hist.retry')}</Button>}>
            {t('act.rel.loadFailed')}
          </AlertBanner>
        )}
        {state.status === 'ok' && data && (
          <RelatedBody data={data} t={t} onOpenActivity={onOpenActivity} />
        )}
      </CardContent>
    </Card>
  )
}

function RelatedBody({ data, t, onOpenActivity }) {
  const anchorTime = data.anchor?.time
  return (
    <>
      {/* Şüpheli nedenler — en olası ilk (sunucu sırası) */}
      {data.suspects.length > 0 && (
        <section aria-label={t('act.rel.suspects')} className="flex min-w-0 flex-col gap-2">
          <h3 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('act.rel.suspects')}</h3>
          <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 md:grid-cols-2">
            {data.suspects.map((s, i) => {
              const tone = suspectTone(s.code)
              const Icon = SUSPECT_ICON[s.code] || Info
              const txt = suspectText(s, t)
              return (
                <li key={`${s.code}-${i}`} data-slot="act-rel-suspect" data-code={s.code} data-tone={tone}
                  className={cn('flex min-w-0 items-start gap-3 rounded-lg border px-3 py-2.5', TONE_CLASS[tone])}>
                  <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="font-semibold [overflow-wrap:anywhere]">{txt.title}</span>
                    <span className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{txt.hint}</span>
                  </div>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      {/* Olgular — bu izlemenin yakın geçmişi */}
      {data.facts.length > 0 && (
        <section aria-label={t('act.rel.facts')} className="flex min-w-0 flex-col gap-2">
          <h3 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('act.rel.facts')}</h3>
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
            {data.facts.map((f, i) => {
              const Icon = FACT_ICON[f.code] || Info
              return (
                <li key={`${f.code}-${i}`} data-slot="act-rel-fact" data-code={f.code} className="flex min-w-0 items-start gap-2 text-sm">
                  <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                  <span className="min-w-0 [overflow-wrap:anywhere]">{factText(f, t, formatDateSec)}</span>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      <p data-slot="act-rel-total" className="m-0 text-sm font-medium">
        {data.total > 0 ? t('act.rel.total', data.total) : t('act.rel.none')}
      </p>

      {data.total > 0 && (
        <div className="grid min-w-0 grid-cols-1 gap-3 md:grid-cols-3">
          <Distribution title={t('act.rel.byReason')} kind="reason" items={data.by_reason}
            label={(x) => (x.reason ? reasonLabel(x.reason, t) : t('act.rel.noReason'))} />
          <Distribution title={t('act.rel.byHost')} kind="host" items={data.by_host}
            label={(x) => x.host || t('act.rel.noHost')} mono />
          <Distribution title={t('act.rel.byTeam')} kind="team" items={data.by_team}
            label={(x) => (x.team_id != null
              ? <TeamBadge teamId={x.team_id} teamName={x.team_name} static wrap />
              : t('act.rel.noTeam'))} />
        </div>
      )}

      {data.items.length > 0 && (
        <section aria-label={t('act.rel.items')} className="flex min-w-0 flex-col gap-2">
          <h3 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            {t('act.rel.items')}{data.total > data.items.length ? ` · ${t('act.rel.itemsCapped', data.items.length, data.total)}` : ''}
          </h3>
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
            {data.items.map((r) => (
              <li key={r.id} data-slot="act-rel-item" className="min-w-0">
                <Button type="button" variant="outline" onClick={() => onOpenActivity?.(r.id)}
                  aria-label={t('act.openDetailFor', r.monitor_name || r.target || String(r.id))}
                  className="h-auto min-h-11 w-full flex-col items-stretch gap-1 px-3 py-2 text-left font-normal whitespace-normal">
                  <span className="flex min-w-0 items-start gap-2">
                    <TypeBadge type={r.monitor_type} />
                    <span className="min-w-0 flex-1 font-semibold [overflow-wrap:anywhere]">{r.monitor_name || r.target}</span>
                    <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{offsetText(anchorTime, r.activity_time, t)}</span>
                    <ChevronRight aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                  </span>
                  {r.target && <span className="block min-w-0 font-mono text-xs break-all text-muted-foreground">{r.target}</span>}
                  {(r.failure_reason || r.team_id != null) && (
                    <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                      <ReasonBadge row={r} t={t} />
                      {r.team_id != null && <TeamBadge teamId={r.team_id} teamName={r.team_name} static wrap />}
                    </span>
                  )}
                </Button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}

/** Dağılım listesi — yatay çubuk + sayı (rozet). Ad sarar, kesilmez. */
function Distribution({ title, kind, items, label, mono = false }) {
  const max = maxCount(items)
  return (
    <section data-slot="act-rel-dist" data-kind={kind} aria-label={title} className="flex min-w-0 flex-col gap-2 rounded-lg border p-3">
      <h3 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      {items.length === 0 ? <p className="m-0 text-sm text-muted-foreground">—</p> : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {items.slice(0, 8).map((x, i) => {
            const n = Number(x?.count) || 0
            return (
              <li key={i} className="flex min-w-0 flex-col gap-1">
                <span className="flex min-w-0 items-start justify-between gap-2 text-sm">
                  <span className={cn('min-w-0 [overflow-wrap:anywhere]', mono && 'font-mono text-xs break-all')}>{label(x)}</span>
                  <Badge variant="secondary" className="shrink-0 tabular-nums">{n}</Badge>
                </span>
                {/* Görsel oran (sayı yanında metin olarak) — ekran okuyucuya ikinci kez duyurulmaz */}
                <ProgressBar value={n} max={max} size="sm" tone="crit" decorative />
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
