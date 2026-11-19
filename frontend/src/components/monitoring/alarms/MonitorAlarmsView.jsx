import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BellRing, ExternalLink, RefreshCcw, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { ALERTS_CHANGED_EVENT } from '../../../hooks/useOpenAlerts.js'
import AlertHistory from '../../admin/AlertHistory.jsx'
import { AlertSourceNavContext } from '../../admin/alerts/AlertBadges.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import {
  SUMMARY_POLL_MS, TOP_DAYS, TOP_LIMIT, alarmHistoryHref, alarmTypesForPage, isUnsupportedSummary, summaryFrom,
} from './monitorAlarmsModel.js'

/*
 * İzleme sayfasının Alarmlar görünümü — AĞIR parça (Alarm Geçmişi listesini içerir). `MonitorAlarms.jsx`'teki
 * `MonitorPageBody` bunu tembel yükler: görünüm açılmadan bu modül (ve AlertHistory) indirilmez; Sertifika Panosu gibi
 * ana pakette çizilen sayfalar giriş paketini büyütmez (lazyChunks.test.js).
 */

const TILE_TONE = {
  critical: 'text-(--severity-critical)',
  high: 'text-orange-700 dark:text-orange-300',
  warning: 'text-amber-700 dark:text-amber-300',
}

function SummaryTile({ label, value, tone, slot }) {
  const known = value != null
  return (
    <div data-slot="mal-tile" data-key={slot} data-zero={known && value === 0 ? 'true' : undefined}
      className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-card px-3 py-2.5">
      <span className={cn('text-xl font-semibold tabular-nums', known && value > 0 && tone ? TILE_TONE[tone] : 'text-foreground',
        !known && 'text-muted-foreground')}>
        {known ? value : '—'}
      </span>
      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{label}</span>
    </div>
  )
}

/**
 * Sayfanın Alarmlar görünümü: açık alarmların son durumu (seviye kırılımı, sahiplenilmemiş, uzun süredir açık), son
 * {@link TOP_DAYS} günde en çok alarm üreten izlemeler (seçince liste o izlemeye süzülür) ve Alarm Geçmişi'nin gömülü
 * listesi (sayfanın alarm tipleriyle; ayrıntı, sahiplenme ve çözme oradan). Özet okunamazsa liste yine çalışır — sayılar
 * "—" kalır, asla sahte sıfır gösterilmez.
 */
export default function MonitorAlarmsView({ view }) {
  const t = useT()
  const types = useMemo(() => alarmTypesForPage(view.src), [view.src])
  const [state, setState] = useState({ status: 'loading', data: null })
  const [focus, setFocus] = useState(null)   // { key, domain, name } — listeyi tek izlemeye (alan adına) süzer
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  /** `fresh` = sunucu belleğini atla (elle yenile, yeniden dene, alarm değişti); dakikalık yoklama bellekten okur. */
  const load = useCallback(async (fresh = false) => {
    if (types.length === 0) { setState({ status: 'unsupported', data: null }); return }
    const my = ++seq.current
    let res
    try {
      res = await api.admin.getAlertTypeSummary({ alertTypes: types, days: TOP_DAYS, limit: TOP_LIMIT, fresh: fresh === true })
    } catch (e) {
      res = { success: false, error: e?.message }
    }
    if (!alive.current || my !== seq.current) return
    if (res?.success) {
      const summary = summaryFrom(res.data)
      if (summary) { setState({ status: 'ok', data: summary }); return }
    }
    if (isUnsupportedSummary(res)) { setState({ status: 'unsupported', data: null }); return }
    setState((prev) => ({ status: 'error', data: prev.data, error: res?.error || null }))
  }, [types])

  const poll = useCallback(() => load(false), [load])
  const reload = useCallback(() => load(true), [load])
  useVisibleInterval(poll, SUMMARY_POLL_MS)
  useEffect(() => {
    const on = () => { reload() }
    window.addEventListener(ALERTS_CHANGED_EVENT, on)
    return () => window.removeEventListener(ALERTS_CHANGED_EVENT, on)
  }, [reload])

  const s = state.data
  const tiles = [
    { slot: 'open', label: t('mal.tile.open'), value: s?.total ?? null, tone: s && s.critical > 0 ? 'critical' : s && s.high > 0 ? 'high' : 'warning' },
    { slot: 'critical', label: t('mal.tile.critical'), value: s?.critical ?? null, tone: 'critical' },
    { slot: 'high', label: t('mal.tile.high'), value: s?.high ?? null, tone: 'high' },
    { slot: 'warning', label: t('mal.tile.warning'), value: s?.warning ?? null, tone: 'warning' },
    { slot: 'unacked', label: t('mal.tile.unacked'), value: s?.unacked ?? null, tone: 'warning' },
    { slot: 'stale', label: t('mal.tile.stale', s?.staleHours ?? 24), value: s?.stale ?? null, tone: 'high' },
  ]

  return (
    <AlertSourceNavContext.Provider value={view.nav}>
      <section data-slot="monitor-alarms" data-src={view.src} aria-label={t('mal.region')} className="flex min-w-0 flex-col gap-4">
        <Card className="min-w-0 gap-3 py-4">
          <CardHeader className="px-4">
            <CardTitle className="flex items-center gap-2 text-base">
              <BellRing aria-hidden="true" className="size-4 text-muted-foreground" />{t('mal.title')}
            </CardTitle>
            <CardDescription>{t('mal.desc')}</CardDescription>
            <CardAction className="flex flex-wrap items-center gap-2">
              <Button type="button" variant="ghost" size="icon" onClick={reload} aria-label={t('mal.refresh')} title={t('mal.refresh')}
                data-slot="mal-refresh" aria-busy={state.status === 'loading' || undefined}>
                <RefreshCcw aria-hidden="true" />
              </Button>
              <Button asChild variant="outline" className="text-foreground no-underline pointer-coarse:h-10">
                <a href={alarmHistoryHref(view.src, view.tab || 'open')} data-slot="mal-open-history">
                  <ExternalLink aria-hidden="true" />{t('mal.openHistory')}
                </a>
              </Button>
            </CardAction>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-3 px-4">
            {state.status === 'unsupported' ? (
              <p data-slot="mal-unsupported" className="text-sm text-muted-foreground">{t('mal.unsupported')}</p>
            ) : state.status === 'loading' && !s ? (
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-6" aria-hidden="true">
                {tiles.map((x) => <Skeleton key={x.slot} className="h-16 rounded-lg" />)}
              </div>
            ) : (
              <div data-slot="mal-tiles" className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-6">
                {tiles.map((x) => <SummaryTile key={x.slot} {...x} />)}
              </div>
            )}
            {state.status === 'error' && (
              <AlertBanner tone="danger" role="alert" title={t('mal.summaryFailed')}
                actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={reload}>{t('mal.retry')}</Button>}>
                {s ? t('mal.staleNote') : null}
              </AlertBanner>
            )}
          </CardContent>
        </Card>

        {state.status !== 'unsupported' && (
          <TopMonitors summary={s} loading={state.status === 'loading' && !s} failed={state.status === 'error' && !s}
            focus={focus} onFocus={setFocus} pageTab={view.src} nav={view.nav} />
        )}

        {focus && (
          <div data-slot="mal-focus" className="flex min-w-0 flex-wrap items-center gap-2">
            <Badge variant="secondary" className="h-auto min-h-7 max-w-full gap-1.5 overflow-visible rounded-md py-1 whitespace-normal">
              <span className="[overflow-wrap:anywhere]">{t('mal.filter.chip', focus.name)}</span>
            </Badge>
            <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" onClick={() => setFocus(null)}
              data-slot="mal-focus-clear">
              <X aria-hidden="true" />{t('mal.filter.clear')}
            </Button>
          </div>
        )}

        <AlertHistory types={types} domain={focus?.domain ?? null} initialTab={view.tab} onTabChange={view.onTabChange} />
      </section>
    </AlertSourceNavContext.Provider>
  )
}

/** "En çok alarm üreten izlemeler" kartı — adlar sarar (kesilmez); satır eylemleri dokunmatikte 40 px. */
function TopMonitors({ summary, loading, failed, focus, onFocus, pageTab, nav }) {
  const t = useT()
  const days = summary?.days ?? TOP_DAYS
  const items = summary?.top ?? []
  return (
    <Card data-slot="mal-top" className="min-w-0 gap-2 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-base">{t('mal.top.title', days)}</CardTitle>
        <CardDescription>{t('mal.top.desc')}</CardDescription>
      </CardHeader>
      <CardContent className="min-w-0 px-4">
        {loading ? (
          <div className="flex flex-col gap-2" aria-hidden="true">
            {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10" />)}
          </div>
        ) : failed ? (
          <p className="text-sm text-muted-foreground">{t('mal.top.unknown')}</p>
        ) : items.length === 0 ? (
          <StatusBlock tone="neutral" title={t('mal.top.empty', days)} className="py-4" />
        ) : (
          <ol data-slot="mal-top-list" className="flex min-w-0 flex-col">
            {items.map((it, i) => {
              // Seçim satır anahtarıyla (izleme) — aynı adresi izleyen iki takımın satırı birlikte seçili görünmez
              const selected = focus?.key === it.key
              const canOpen = it.monitorId != null && it.monitorTab === pageTab
              return (
                <li key={it.key} data-slot="mal-top-item" data-selected={selected ? 'true' : undefined}
                  className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 border-b py-2.5 last:border-b-0">
                  <span aria-hidden="true" className="w-5 shrink-0 text-right text-sm font-semibold text-muted-foreground tabular-nums">{i + 1}.</span>
                  <div className="min-w-0 flex-1 basis-48">
                    <p data-slot="mal-top-name" className="text-sm font-medium [overflow-wrap:anywhere]">{it.name}</p>
                    {it.name !== it.domain && (
                      <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{it.domain}</p>
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Badge variant="outline" data-slot="mal-top-count" className="tabular-nums">
                      {it.count === 1 ? t('mal.top.count.one') : t('mal.top.count', it.count)}
                    </Badge>
                    {it.open > 0 && (
                      <Badge variant="outline" data-slot="mal-top-open"
                        className="border-(--severity-critical)/40 bg-(--severity-critical)/10 text-(--severity-critical) tabular-nums">
                        {t('mal.top.open', it.open)}
                      </Badge>
                    )}
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Button type="button" variant={selected ? 'secondary' : 'outline'} size="sm" className="pointer-coarse:h-10"
                      aria-pressed={selected} aria-label={t('a11y.rowAction', it.name, t('mal.top.filter'))}
                      onClick={() => onFocus(selected ? null : { key: it.key, domain: it.domain, name: it.name })}>
                      {t('mal.top.filter')}
                    </Button>
                    {canOpen && (
                      <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" data-slot="mal-top-open-monitor"
                        aria-label={t('a11y.rowAction', it.name, t('mal.top.openMonitor'))}
                        onClick={() => nav({ alert_type: it.lastAlertType, domain: it.domain,
                          monitor_ref: { name: it.name, tab: it.monitorTab, monitor_id: it.monitorId } })}>
                        <ExternalLink aria-hidden="true" />{t('mal.top.openMonitor')}
                      </Button>
                    )}
                  </div>
                </li>
              )
            })}
          </ol>
        )}
      </CardContent>
    </Card>
  )
}
