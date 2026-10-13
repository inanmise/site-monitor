import { useCallback } from 'react'
import {
  Activity, BellRing, CircleCheck, CircleHelp, CloudLightning, HeartPulse, ListChecks, Radar, ShieldAlert, SignalHigh, UserRoundCheck,
} from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { activityTarget } from '../../utils/activityTarget.js'
import { alertTypeLabel } from '../../utils/alertTypeMeta.js'
import { navigateTo } from '../../utils/navigate.js'
import { relTimeOrRaw } from '../../utils/relativeTime.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import { TYPE_META } from '../monitoring/overviewMeta.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import {
  WATCHDOG_TONE, alertsSummary, certSummary, formatPct, monitorSummary, oncallRows, qualityTone, statusSummary, stormSummary,
} from './myDashModel.js'

// ── Ortak sunum parçaları ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Kutu içi bağlantı (2026-10-13, kullanıcı isteği: "Kişisel Panom'dan hızlıca tıklayıp geçebilmeliyim"): sayılar, kutucuklar
 * ve satırlar ilgili SÜZÜLMÜŞ ekranı ya da kaydın kendisini açar. Düzenleme kipinde (sürükle / boyut) ve TV / duvar ekranında
 * tıklanmaz — kazara gezinme olmasın; o zaman düz metin çizilir (eski görünüm).
 */
export const linkTo = (frame, tab, params) => (frame?.edit || frame?.tv || !tab ? undefined : () => navigateTo(tab, params))

/** Tıklanabilir öğenin ortak sınıfı: shadcn Button'ın yükseklik / hizalama / satır kırma varsayılanları kutu içine uyarlanır. */
const LINK_RESET = 'h-auto min-w-0 justify-start gap-0 whitespace-normal text-left font-normal shadow-none'

/** Büyük sayı + etiket. */
function Metric({ value, label, tone = 'neutral', tv, slot, onClick, hint }) {
  const ink = { danger: 'text-destructive', warning: 'text-amber-700 dark:text-amber-300', success: 'text-success', neutral: 'text-foreground', muted: 'text-muted-foreground' }[tone]
  const body = (
    <>
      <span className={cn('font-semibold tabular-nums leading-none', tv ? 'text-4xl' : 'text-3xl', ink)}>{value}</span>
      <span className={cn('mt-1 text-muted-foreground [overflow-wrap:anywhere]', tv ? 'text-base' : 'text-sm')}>{label}</span>
    </>
  )
  if (!onClick) return <div className="flex min-w-0 flex-col" data-slot={slot}>{body}</div>
  return (
    <Button type="button" variant="ghost" onClick={onClick} title={hint} data-slot={slot} data-link="true"
      className={cn(LINK_RESET, '-m-1.5 flex-col items-start rounded-lg p-1.5 hover:bg-muted/70 pointer-coarse:min-h-10')}>
      {body}
    </Button>
  )
}

/** Kutucuk tonları. */
const TILE_TONE = {
  danger: 'bg-destructive/10 text-destructive',
  warning: 'bg-amber-500/10 text-amber-800 dark:text-amber-300',
  success: 'bg-success/10 text-success',
  muted: 'bg-muted text-foreground',
}
/** Tıklanabilir kutucukta üzerine gelince ton KORUNUR (Button'ın gri vurgusu ezilir); statik kutucukta vurgu yok. */
const TILE_HOVER = {
  danger: 'hover:bg-destructive/20 dark:hover:bg-destructive/20 hover:text-destructive',
  warning: 'hover:bg-amber-500/20 dark:hover:bg-amber-500/20 hover:text-amber-800 dark:hover:text-amber-300',
  success: 'hover:bg-success/20 dark:hover:bg-success/20 hover:text-success',
  muted: 'hover:bg-muted/60 dark:hover:bg-muted/60 hover:text-foreground',
}

/** Sayılı küçük kutucuk (ızgara). `onClick` verilirse süzülmüş listeyi açan düğme olur. */
function Tile({ value, label, tone = 'muted', tv, slot, onClick, hint }) {
  const t0 = TILE_TONE[tone] ? tone : 'muted'
  const cls = cn('flex min-w-0 flex-col rounded-lg px-3 py-2', TILE_TONE[t0])
  const body = (
    <>
      <span className={cn('font-semibold tabular-nums leading-tight', tv ? 'text-2xl' : 'text-xl')}>{value}</span>
      <span className={cn('text-xs font-medium break-words hyphens-auto opacity-90', tv && 'text-sm')}>{label}</span>
    </>
  )
  if (!onClick) return <div className={cls} data-slot={slot} data-tone={tone}>{body}</div>
  return (
    <Button type="button" variant="ghost" onClick={onClick} title={hint} data-slot={slot} data-tone={tone} data-link="true"
      className={cn(LINK_RESET, cls, TILE_HOVER[t0], 'items-start hover:ring-1 hover:ring-border')}>
      {body}
    </Button>
  )
}

function Empty({ icon: Icon = CircleCheck, text, tone = 'success', tv }) {
  return (
    <p className={cn('m-0 flex items-center gap-2', tone === 'success' ? 'text-success' : 'text-muted-foreground', tv ? 'text-lg' : 'text-sm')} data-slot="mydash-empty">
      <Icon className="size-4 shrink-0" aria-hidden="true" />
      <span className="[overflow-wrap:anywhere]">{text}</span>
    </p>
  )
}

function List({ children, slot }) {
  return <ul className="m-0 flex list-none flex-col divide-y divide-border p-0" data-slot={slot}>{children}</ul>
}

/**
 * Liste satırı: solda rozet/ikon, ortada ad (tam genişlik, sarar) ve altında ayrıntı, sağda kısa sayı rozetleri. Ad ile
 * ayrıntı AYNI esnek satırda yarışmaz — uzun takım adı adı birkaç piksele sıkıştırıp harf harf sardırıyordu (TV e2e).
 */
function Row({ lead, title, meta, trail, slot, onClick, hint }) {
  // <button> yalnız satır içi (phrasing) içerik alır — tıklanabilir satırda <div>/<p> yerine blok <span>
  const Box = onClick ? 'span' : 'div'
  const Line = onClick ? 'span' : 'p'
  const inner = (
    <>
      {lead && <span className="shrink-0 pt-px">{lead}</span>}
      <Box className="block min-w-0 flex-1">
        <Line className="m-0 block font-medium [overflow-wrap:anywhere]">{title}</Line>
        {meta && <Line className="m-0 block text-xs text-muted-foreground [overflow-wrap:anywhere]">{meta}</Line>}
      </Box>
      {trail && <span className="flex max-w-[55%] shrink-0 flex-wrap justify-end gap-1">{trail}</span>}
    </>
  )
  if (!onClick) return <li className="flex min-w-0 items-start gap-2 py-2" data-slot={slot}>{inner}</li>
  // Tıklanabilir satır: tüm satır tek düğme (en az 40 px), kenarlara taşan vurgu satır çizgisiyle hizalı
  return (
    <li className="min-w-0" data-slot={slot}>
      <Button type="button" variant="ghost" onClick={onClick} title={hint} data-link="true"
        className={cn(LINK_RESET, '-mx-2 my-0.5 flex w-[calc(100%+1rem)] min-h-10 items-start gap-2 rounded-md px-2 py-1.5 hover:bg-muted/70')}>
        {inner}
      </Button>
    </li>
  )
}

function More({ n, t }) {
  if (!n) return null
  return <p className="m-0 text-xs text-muted-foreground">{t('mydash.more', n)}</p>
}

const LEVEL_TONE = { CRITICAL: 'danger', HIGH: 'danger', WARNING: 'warning', MEDIUM: 'warning' }
// TV'de liste kısa tutulur (satır yüksekliği sabit, duvar ekranında kaydırılamaz); sayfada boyuta göre
const listLimit = (size, tv, base) => (tv ? (size === 'sm' ? Math.max(2, base - 2) : base) : size === 'lg' ? base + 3 : size === 'sm' ? 0 : base)

// ── Kutular ───────────────────────────────────────────────────────────────────────────────────────────────────

const fetchOpenAlerts = () => api.me.openAlerts(false)

function AlertsWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchOpenAlerts, { refreshKey })
  const s = alertsSummary(data, listLimit(frame.widget.size, frame.tv, 5))
  return (
    <WidgetCard frame={frame} Icon={BellRing} title={t('mydash.w.alerts_open')} tab="alerthistory" loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={s.hidden ? t('mydash.noAccess') : s.total > 0 ? t('mydash.openCount', s.total) : t('mydash.allClear')}>
      {s.hidden ? <Empty tone="muted" text={t('mydash.noAccessBody')} tv={frame.tv} /> : (<>
      <div className="flex flex-col gap-3 @md:flex-row @md:items-end @md:gap-x-6">
        <Metric value={s.total} label={t('mydash.alerts.open')} tone={s.critical ? 'danger' : s.total ? 'warning' : 'success'} tv={frame.tv} slot="mydash-alerts-total"
          onClick={linkTo(frame, 'alerthistory')} hint={t('mydash.linkHint')} />
        <div className="grid min-w-0 grid-cols-3 gap-2 @md:flex-1">
          <Tile value={s.critical} label={t('mydash.lvl.critical')} tone={s.critical ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-alerts-critical"
            onClick={linkTo(frame, 'alerthistory', { level: 'CRITICAL' })} hint={t('mydash.linkHint')} />
          <Tile value={s.high} label={t('mydash.lvl.high')} tone={s.high ? 'danger' : 'muted'} tv={frame.tv}
            onClick={linkTo(frame, 'alerthistory', { level: 'HIGH' })} hint={t('mydash.linkHint')} />
          <Tile value={s.warning + s.other} label={t('mydash.lvl.warning')} tone={s.warning + s.other ? 'warning' : 'muted'} tv={frame.tv} slot="mydash-alerts-warning"
            onClick={linkTo(frame, 'alerthistory', s.other > 0 ? undefined : { level: 'WARNING' })} hint={t('mydash.linkHint')} />
        </div>
      </div>
      {s.unacked > 0 && (linkTo(frame, 'alerthistory')
        ? (
          <Button type="button" variant="link" className="h-auto min-h-10 self-start p-0 text-sm font-normal text-muted-foreground whitespace-normal"
            onClick={linkTo(frame, 'alerthistory', { ack: 'unack' })} data-slot="mydash-alerts-unacked">
            {t('mydash.alerts.unacked', s.unacked)}
          </Button>
        )
        : <p className="m-0 text-sm text-muted-foreground">{t('mydash.alerts.unacked', s.unacked)}</p>)}
      {s.total === 0 && <Empty text={t('mydash.alerts.none')} tv={frame.tv} />}
      {s.items.length > 0 && (
        <List slot="mydash-alerts-list">
          {s.items.map((it) => (
            <Row key={it.id} lead={<ToneBadge tone={LEVEL_TONE[String(it.alert_level || '').toUpperCase()] || 'muted'}>{t('mydash.lvl.' + levelKey(it.alert_level))}</ToneBadge>}
              title={it.domain || '—'}
              meta={`${alertTypeLabel(t, it.alert_type)}${it.team_name ? ` · ${it.team_name}` : ''} · ${relTimeOrRaw(it.created_at, t)}`}
              onClick={it.id != null ? linkTo(frame, 'alerthistory', { alert: it.id }) : undefined} hint={t('mydash.linkDetail')} />
          ))}
        </List>
      )}
      </>)}
    </WidgetCard>
  )
}

function levelKey(level) {
  const l = String(level || '').toUpperCase()
  if (l === 'CRITICAL') return 'critical'
  if (l === 'HIGH') return 'high'
  return 'warning'
}

const fetchOverview = () => api.monitoring.getOverview(24, false)

function MonitorHealthWidget({ frame, refreshKey }) {
  const t = useT()
  const { lang } = useLanguage()
  const { data, loading, error, at } = useWidgetData(fetchOverview, { refreshKey })
  const s = monitorSummary(data)
  const pct = (v) => (s.active > 0 ? `${(v * 100) / s.active}%` : '0%')
  const limit = frame.tv ? (frame.widget.size === 'lg' ? 4 : 2) : listLimit(frame.widget.size, false, 4)
  return (
    <WidgetCard frame={frame} Icon={Radar} title={t('mydash.w.monitor_health')} tab="monitoring" loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={s.down ? t('mydash.monitor.downCount', s.down) : s.stale ? t('mydash.monitor.staleCount', s.stale)
        : s.tone === 'success' ? t('mydash.allClear') : s.active === 0 ? t('mydash.monitor.none') : t('mydash.monitor.unknownCount', s.unknown)}>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <Metric value={formatPct(s.healthyPct, lang)} label={t('mydash.monitor.healthy', s.healthy, s.active)}
          tone={s.tone} tv={frame.tv} slot="mydash-monitor-healthy" onClick={linkTo(frame, 'monitoring')} hint={t('mydash.linkHint')} />
        {s.successRate != null && (
          <Metric value={formatPct(s.successRate, lang)} label={t('mydash.monitor.success24')} tv={frame.tv} />
        )}
      </div>
      {s.active > 0 && (
        <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-muted" role="img" data-slot="mydash-monitor-bar"
          aria-label={t('mydash.monitor.barLabel', s.healthy, s.down, s.stale, s.unknown)}>
          <span className="bg-success" style={{ width: pct(s.healthy) }} />
          <span className="bg-destructive" style={{ width: pct(s.down) }} />
          <span className="bg-amber-500" style={{ width: pct(s.stale) }} />
          <span className="bg-slate-400 dark:bg-slate-500" style={{ width: pct(s.unknown) }} />
        </div>
      )}
      <div className="grid grid-cols-2 gap-2 @sm:grid-cols-3">
        <Tile value={s.down} label={t('mo.status.down')} tone={s.down ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-monitor-down"
          onClick={linkTo(frame, 'monitoring', { mo_status: 'down' })} hint={t('mydash.linkHint')} />
        <Tile value={s.stale} label={t('mo.status.stale')} tone={s.stale ? 'warning' : 'muted'} tv={frame.tv}
          onClick={linkTo(frame, 'monitoring', { mo_status: 'stale' })} hint={t('mydash.linkHint')} />
        <Tile value={s.unknown} label={t('mo.status.unknown')} tv={frame.tv}
          onClick={linkTo(frame, 'monitoring', { mo_status: 'unknown' })} hint={t('mydash.linkHint')} />
      </div>
      {limit > 0 && s.types.length > 0 && (
        <List slot="mydash-monitor-types">
          {s.types.slice(0, limit).map((x) => {
            const meta = TYPE_META[x.type]
            const TIcon = meta?.Icon
            return (
              <Row key={x.type} lead={TIcon ? <TIcon className="size-4 text-muted-foreground" aria-hidden="true" /> : null}
                title={meta ? t(meta.labelKey) : x.type} meta={t('mydash.monitor.activeN', x.active)}
                // Sorunlu tür → yalnız o türün sorunluları; sorunsuz tür → türün tümü
                onClick={linkTo(frame, 'monitoring', x.down > 0 ? { mo_type: x.type, mo_status: 'down' }
                  : x.stale > 0 ? { mo_type: x.type, mo_status: 'stale' } : { mo_type: x.type })} hint={t('mydash.linkHint')}
                trail={(
                  <>
                    {x.down > 0 && <ToneBadge tone="danger">{t('mydash.monitor.downCount', x.down)}</ToneBadge>}
                    {x.stale > 0 && <ToneBadge tone="warning">{t('mydash.monitor.staleCount', x.stale)}</ToneBadge>}
                    {!x.down && !x.stale && <ToneBadge tone="success">{t('mydash.ok')}</ToneBadge>}
                  </>
                )} />
            )
          })}
        </List>
      )}
    </WidgetCard>
  )
}

const fetchStats = () => api.getStats()

function CertWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchStats, { refreshKey })
  const s = certSummary(data, listLimit(frame.widget.size, frame.tv, 4))
  return (
    <WidgetCard frame={frame} Icon={ShieldAlert} title={t('mydash.w.cert_expiring')} tab="warnings" loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={s.expired ? t('mydash.cert.expiredCount', s.expired) : s.exp7 ? t('mydash.cert.exp7Count', s.exp7)
        : s.exp30 ? t('mydash.cert.exp30Count', s.exp30) : s.errors ? t('mydash.cert.scanFailures', s.errors)
        : s.total === 0 ? t('mydash.noData') : t('mydash.allClear')}>
      <div className="grid grid-cols-2 gap-2 @md:grid-cols-4">
        <Tile value={s.expired} label={t('mydash.cert.expired')} tone={s.expired ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-cert-expired"
          onClick={linkTo(frame, 'all', { c_win: 'expired' })} hint={t('mydash.linkHint')} />
        <Tile value={s.exp7} label={t('mydash.cert.exp7')} tone={s.exp7 ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-cert-7"
          onClick={linkTo(frame, 'all', { c_win: '7' })} hint={t('mydash.linkHint')} />
        <Tile value={s.exp30} label={t('mydash.cert.exp30')} tone={s.exp30 ? 'warning' : 'muted'} tv={frame.tv} slot="mydash-cert-30"
          onClick={linkTo(frame, 'all', { c_win: '30' })} hint={t('mydash.linkHint')} />
        <Tile value={s.valid} label={t('mydash.cert.valid', s.total)} tone="success" tv={frame.tv}
          onClick={linkTo(frame, 'all', { c_st: 'valid' })} hint={t('mydash.linkHint')} />
      </div>
      {s.errors > 0 && (linkTo(frame, 'all')
        ? (
          <Button type="button" variant="link" className="h-auto min-h-10 self-start p-0 text-sm font-normal text-muted-foreground whitespace-normal"
            onClick={linkTo(frame, 'all', { c_st: 'error' })} data-slot="mydash-cert-errors">
            {t('mydash.cert.errors', s.errors)}
          </Button>
        )
        : <p className="m-0 text-sm text-muted-foreground">{t('mydash.cert.errors', s.errors)}</p>)}
      {s.urgent.length > 0 && (
        <>
          <List slot="mydash-cert-urgent">
            {s.urgent.map((d) => (linkTo(frame, 'dashboard')
              ? <Row key={d} title={d} onClick={linkTo(frame, 'dashboard', { domain: d, open: 'cert' })} hint={t('mydash.linkDetail')} />
              : <li key={d} className="py-1.5 font-medium [overflow-wrap:anywhere]">{d}</li>))}
          </List>
          <More n={s.urgentMore} t={t} />
        </>
      )}
    </WidgetCard>
  )
}

const fetchStatus = () => api.statusPage.get(false)
const SP_TONE = { major_outage: 'danger', partial_outage: 'danger', degraded: 'warning', maintenance: 'info', operational: 'success', no_data: 'muted' }

function StatusWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchStatus, { refreshKey })
  const s = statusSummary(data, listLimit(frame.widget.size, frame.tv, 4))
  return (
    <WidgetCard frame={frame} Icon={SignalHigh} title={t('mydash.w.status_services')} tab="status" loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={s.total ? t('sp.state.' + s.shownState) : null}>
      {s.total === 0 ? (
        <Empty tone="muted" text={t('mydash.status.none')} tv={frame.tv} />
      ) : (
        <div className="grid grid-cols-2 gap-2 @sm:grid-cols-3">
          <Tile value={s.counts.operational} label={t('sp.state.operational')} tone="success" tv={frame.tv} slot="mydash-status-ok"
            onClick={linkTo(frame, 'status')} hint={t('mydash.linkHint')} />
          <Tile value={s.counts.degraded} label={t('sp.state.degraded')} tone={s.counts.degraded ? 'warning' : 'muted'} tv={frame.tv}
            onClick={linkTo(frame, 'status')} hint={t('mydash.linkHint')} />
          <Tile value={s.counts.partial_outage + s.counts.major_outage} label={t('mydash.status.outage')}
            tone={s.counts.partial_outage + s.counts.major_outage ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-status-outage"
            onClick={linkTo(frame, 'status')} hint={t('mydash.linkHint')} />
        </div>
      )}
      {s.problems.length > 0 && frame.widget.size !== 'sm' && (
        <>
          <List slot="mydash-status-problems">
            {s.problems.map((x) => (
              <Row key={x.key} lead={<ToneBadge tone={SP_TONE[x.state] || 'muted'}>{t('sp.state.' + x.state)}</ToneBadge>}
                title={x.name || t('mydash.status.ungrouped')} meta={x.team_name || null}
                onClick={linkTo(frame, 'status')} hint={t('mydash.linkHint')} />
            ))}
          </List>
          <More n={s.problemsMore} t={t} />
        </>
      )}
      {/* Küçük boyutta liste 0'a kısılır — "hepsi çalışıyor" kararı kısılmış listeye değil gerçek sorun sayısına bakar (2026-10-12) */}
      {s.allOk && <Empty text={t('mydash.status.allOk')} tv={frame.tv} />}
    </WidgetCard>
  )
}

const fetchStorms = () => api.monitoring.storm.status(false)

function StormsWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchStorms, { refreshKey })
  const s = stormSummary(data, listLimit(frame.widget.size, frame.tv, 4) || 3)
  return (
    <WidgetCard frame={frame} Icon={CloudLightning} title={t('mydash.w.storms')} tab="storms" loading={loading} error={error} at={at}
      tone={s.enabled ? s.tone : 'muted'} toneLabel={!s.enabled ? t('mydash.storm.off') : s.storming ? t('mydash.storm.activeCount', s.storming) : t('mydash.allClear')}>
      {!s.enabled ? (
        <Empty tone="muted" text={t('mydash.storm.offBody')} tv={frame.tv} />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2">
            <Tile value={s.storming} label={t('mydash.storm.storming')} tone={s.storming ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-storm-active"
              onClick={linkTo(frame, 'storms')} hint={t('mydash.linkHint')} />
            <Tile value={s.near} label={t('mydash.storm.near')} tone={s.near ? 'warning' : 'muted'} tv={frame.tv}
              onClick={linkTo(frame, 'storms')} hint={t('mydash.linkHint')} />
          </div>
          {s.teams.length === 0 ? <Empty text={t('mydash.storm.calm')} tv={frame.tv} /> : (
            <>
              <List slot="mydash-storm-teams">
                {s.teams.map((x) => (
                  <Row key={x.team_id} lead={<ToneBadge tone={x.status === 'STORM' ? 'danger' : 'warning'}>{t('sf.status.' + x.status)}</ToneBadge>}
                    title={x.team_name || '—'} meta={t('mydash.storm.targets', x.window_targets ?? 0)}
                    onClick={linkTo(frame, 'storms', x.team_id != null ? { sf_team: x.team_id } : undefined)} hint={t('mydash.linkHint')} />
                ))}
              </List>
              <More n={s.teamsMore} t={t} />
            </>
          )}
        </>
      )}
    </WidgetCard>
  )
}

async function fetchOnCall() {
  const dir = await api.teams.directory()
  if (dir?.success === false) return dir
  const teams = (Array.isArray(dir?.data) ? dir.data : []).filter((x) => x && x.active !== false)
  if (!teams.length) return { success: true, data: { current: [], teams } }
  const cur = await api.oncall.current(teams.slice(0, 200).map((x) => x.id))
  if (cur?.success === false) return cur
  return { success: true, data: { current: Array.isArray(cur?.data) ? cur.data : [], teams } }
}

function OnCallWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchOnCall, { refreshKey })
  const rows = oncallRows(data?.current, data?.teams)
  const limit = frame.tv ? (frame.widget.size === 'sm' ? 3 : 5) : frame.widget.size === 'lg' ? 12 : frame.widget.size === 'md' ? 8 : 5
  return (
    <WidgetCard frame={frame} Icon={UserRoundCheck} title={t('mydash.w.oncall')} loading={loading} error={error} at={at}
      tone={rows.length ? 'info' : 'muted'} toneLabel={rows.length ? t('mydash.oncall.teams', rows.length) : null}>
      {rows.length === 0 ? (
        <Empty tone="muted" text={t('mydash.oncall.none')} tv={frame.tv} />
      ) : (
        <>
          <List slot="mydash-oncall-list">
            {rows.slice(0, limit).map((r) => (
              <li key={r.team_id} className="flex min-w-0 flex-col gap-0.5 py-2" data-slot="mydash-oncall-row">
                <span className="text-xs font-medium text-muted-foreground [overflow-wrap:anywhere]">{r.team_name || '—'}</span>
                <span className="font-semibold [overflow-wrap:anywhere]">{r.name || '—'}</span>
                {r.until && <span className="text-xs text-muted-foreground">{t('mydash.oncall.until', formatDate(r.until))}{r.override ? ` · ${t('mydash.oncall.override')}` : ''}</span>}
              </li>
            ))}
          </List>
          <More n={Math.max(0, rows.length - limit)} t={t} />
        </>
      )}
    </WidgetCard>
  )
}

const ACT_TONE = { SUCCESS: 'success', WARNING: 'warning', ERROR: 'danger', TIMEOUT: 'danger', UNKNOWN: 'muted' }

function ActivityWidget({ frame, refreshKey }) {
  const t = useT()
  const size = frame.tv ? (frame.widget.size === 'sm' ? 3 : 5) : frame.widget.size === 'lg' ? 10 : frame.widget.size === 'md' ? 6 : 4
  const fetcher = useCallback(() => api.getActivity({ page: 0, size }), [size])
  const { data, loading, error, at } = useWidgetData(fetcher, { refreshKey })
  const rows = Array.isArray(data) ? data : []
  const errors = rows.filter((r) => r.result_status === 'ERROR' || r.result_status === 'TIMEOUT').length
  return (
    <WidgetCard frame={frame} Icon={Activity} title={t('mydash.w.activity')} tab="activity" loading={loading} error={error} at={at}
      tone={errors ? 'warning' : 'muted'} toneLabel={errors ? t('mydash.activity.failing', errors) : null}>
      {rows.length === 0 ? <Empty tone="muted" text={t('mydash.activity.none')} tv={frame.tv} /> : (
        <List slot="mydash-activity-list">
          {rows.map((r) => {
            const dest = activityTarget(r)   // Etkinlik sayfasının "izlemeye git" hedefiyle aynı
            return (
              <Row key={r.id} lead={<ToneBadge tone={ACT_TONE[r.result_status] || 'muted'}>{t('act.st.' + (ACT_TONE[r.result_status] ? r.result_status : 'UNKNOWN'))}</ToneBadge>}
                title={r.monitor_name || r.target || '—'}
                meta={[r.monitor_name && r.target && r.target !== r.monitor_name ? r.target : null,
                  String(r.monitor_type || '').toUpperCase(), relTimeOrRaw(r.activity_time, t)].filter(Boolean).join(' · ')}
                onClick={dest ? linkTo(frame, dest.tab, dest.params) : undefined} hint={t('mydash.linkDetail')} />
            )
          })}
        </List>
      )}
    </WidgetCard>
  )
}

const fetchWatchdog = () => api.admin.getWatchdog()

function WatchdogWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchWatchdog, { refreshKey })
  const status = data?.status || 'UNKNOWN'
  const problems = (Array.isArray(data?.checks) ? data.checks : []).filter((c) => c.status === 'PROBLEM')
    .sort((a, b) => (a.severity === 'CRITICAL' ? 0 : 1) - (b.severity === 'CRITICAL' ? 0 : 1))
  return (
    <WidgetCard frame={frame} Icon={HeartPulse} title={t('mydash.w.watchdog')} tab="health" loading={loading} error={error} at={at}
      tone={data?.stale ? 'danger' : WATCHDOG_TONE[status] || 'muted'} toneLabel={data ? t('wd.status.' + status) : null}>
      {data?.enabled === false && <Empty tone="muted" text={t('wd.disabledTitle')} tv={frame.tv} />}
      {data?.enabled !== false && data?.stale && <p className="m-0 font-medium text-destructive">{t('mydash.watchdog.stale')}</p>}
      {data?.enabled !== false && !data?.stale && problems.length === 0 && data && (status === 'UP'
        ? <Empty text={t('mydash.watchdog.ok')} tv={frame.tv} />
        // Hiç değerlendirilmedi / bilinmiyor → yeşil "sağlıklı" DENMEZ (2026-10-12)
        : <Empty tone="muted" icon={CircleHelp} text={t('mydash.watchdog.notEvaluated')} tv={frame.tv} />)}
      {problems.length > 0 && (
        <List slot="mydash-watchdog-problems">
          {problems.slice(0, frame.tv ? 3 : 4).map((c) => (linkTo(frame, 'health')
            ? (
              <Row key={c.key}
                lead={<ToneBadge tone={c.severity === 'CRITICAL' ? 'danger' : 'warning'}>{t(c.severity === 'CRITICAL' ? 'mydash.lvl.critical' : 'mydash.lvl.warning')}</ToneBadge>}
                title={c.label} meta={c.detail && frame.widget.size !== 'sm' ? c.detail : null}
                onClick={linkTo(frame, 'health')} hint={t('mydash.linkHint')} />
            ) : (
              <li key={c.key} className="flex min-w-0 flex-col gap-0.5 py-2">
                <span className="flex flex-wrap items-center gap-2">
                  <ToneBadge tone={c.severity === 'CRITICAL' ? 'danger' : 'warning'}>{t(c.severity === 'CRITICAL' ? 'mydash.lvl.critical' : 'mydash.lvl.warning')}</ToneBadge>
                  <span className="font-medium [overflow-wrap:anywhere]">{c.label}</span>
                </span>
                {c.detail && frame.widget.size !== 'sm' && <span className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{c.detail}</span>}
              </li>
            )))}
        </List>
      )}
    </WidgetCard>
  )
}

const fetchQuality = () => api.dataQuality.summary(false)

function QualityWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchQuality, { refreshKey })
  const org = data?.org || null
  const band = org?.band || 'NO_DATA'
  const teams = (Array.isArray(data?.teams) ? data.teams : []).filter((x) => x?.score != null)
    .sort((a, b) => a.score - b.score).slice(0, listLimit(frame.widget.size, frame.tv, 3))
  const delta = Number(org?.delta_7d)
  return (
    <WidgetCard frame={frame} Icon={ListChecks} title={t('mydash.w.data_quality')} tab="dataquality" loading={loading} error={error} at={at}
      tone={qualityTone(band)} toneLabel={org ? t('dq.band.' + band) : null}>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <Metric value={org?.score ?? '—'} label={t('mydash.quality.score')} tone={qualityTone(band) === 'muted' ? 'neutral' : qualityTone(band)} tv={frame.tv} slot="mydash-quality-score"
          onClick={linkTo(frame, 'dataquality')} hint={t('mydash.linkHint')} />
        {Number.isFinite(delta) && delta !== 0 && (
          <Metric value={`${delta > 0 ? '+' : ''}${delta}`} label={t('mydash.quality.delta')} tone={delta > 0 ? 'success' : 'warning'} tv={frame.tv} />
        )}
        {org && <Metric value={org.findings ?? 0} label={t('mydash.quality.findings')} tv={frame.tv} />}
      </div>
      {teams.length > 0 && (
        <List slot="mydash-quality-teams">
          {teams.map((x) => (
            <Row key={x.id ?? x.name} title={x.name || '—'} trail={<ToneBadge tone={qualityTone(x.band)} className="tabular-nums">{x.score}</ToneBadge>}
              onClick={linkTo(frame, 'dataquality', x.id != null ? { dq_team: x.id } : undefined)} hint={t('mydash.linkHint')} />
          ))}
        </List>
      )}
    </WidgetCard>
  )
}

/** Tür → { Icon, Component } — kataloğun (myDashModel.WIDGET_CATALOG) görsel karşılığı; sıra katalogda. */
export const WIDGETS = {
  alerts_open:     { Icon: BellRing,       Component: AlertsWidget },
  monitor_health:  { Icon: Radar,          Component: MonitorHealthWidget },
  cert_expiring:   { Icon: ShieldAlert,    Component: CertWidget },
  status_services: { Icon: SignalHigh,     Component: StatusWidget },
  storms:          { Icon: CloudLightning, Component: StormsWidget },
  oncall:          { Icon: UserRoundCheck, Component: OnCallWidget },
  activity:        { Icon: Activity,       Component: ActivityWidget },
  watchdog:        { Icon: HeartPulse,     Component: WatchdogWidget },
  data_quality:    { Icon: ListChecks,     Component: QualityWidget },
}

