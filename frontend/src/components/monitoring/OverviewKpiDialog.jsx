// İzleme Panosu — KPI özet pencereleri (2026-10-01, kullanıcı isteği: "failing, overdue ve alerts kutuları tıklanabilir
// olsun; tıklanınca pop-up açılıp o kartın özetini göstersin"). Sorunlu / Kontrolü gecikmiş / Açık alarm / Duraklatılmış
// kutuları bu pencereyi açar: özet kutucukları, türe ve takıma göre dağılım, izleme listesi (eylemleriyle) ve "Listede
// süz" (eski davranış: tabloyu o duruma süzüp listeye kaydırır). Veri sayfanın elindeki satırlardan — ek istek YOK.
// Pencere `ui/ModalShell` (shadcn Dialog); telefonda tam ekran, gövde kayar, altlık sabit.
import { useMemo } from 'react'
import {
  BellRing, CircleAlert, Clock, ExternalLink, Filter, History, Link2, PauseCircle, ShieldCheck, Users,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { formatDuration } from '../../utils/incidentMeta.js'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { AlertLevelBadge } from '../admin/alerts/AlertBadges.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { TYPE_META } from './overviewMeta.js'
import { NO_TEAM, teamKey } from './overviewFilters.js'
import { StatusBadge, TypeLabel } from './OverviewParts.jsx'
import OverviewMonitorItem, { ItemRowActions } from './OverviewMonitorItem.jsx'
import {
  ageMs, alertLevelCounts, alertRows, attentionRows, breakdown, formatAge, staleDetail,
} from './overviewModel.js'

/** Telefonda tam ekran (IncidentFormModal / ReportParts deseni): kenarsız, köşesiz, güvenli alan payı. */
const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-[100dvh] max-sm:max-h-none max-sm:max-w-none max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:p-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'

/** Pencerede en fazla bu kadar satır çizilir; kalanı "Listede süz" ile tabloda. */
export const DIALOG_ROW_LIMIT = 50

export const KPI_DIALOG_KINDS = ['down', 'stale', 'alerts', 'paused']


function Tile({ label, value, tone, hint }) {
  return (
    <div data-slot="mo-dlg-tile" className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2.5">
      <span className={cn('text-xl leading-none font-bold tabular-nums',
        tone === 'bad' ? 'text-destructive' : tone === 'warn' ? 'text-amber-700 dark:text-amber-300' : tone === 'ok' ? 'text-success' : 'text-foreground')}>
        {value}
      </span>
      <span className="text-xs leading-tight font-medium text-muted-foreground">{label}</span>
      {hint && <span className="text-[11px] leading-tight text-muted-foreground">{hint}</span>}
    </div>
  )
}

/** Yatay çubuk listesi (dağılım): etiket · adet · ince çubuk (en büyüğe göre). */
function BarList({ title, icon: Icon, items, renderLabel, slot }) {
  const t = useT()
  const max = Math.max(1, ...items.map((x) => x.count))
  return (
    <section data-slot={slot} aria-label={title} className="flex min-w-0 flex-col gap-2 rounded-lg border p-3">
      <h4 className="m-0 flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        <Icon aria-hidden="true" className="size-3.5" />{title}
      </h4>
      {items.length === 0 ? (
        <span className="text-xs text-muted-foreground">{t('mo.dlg.none')}</span>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {items.slice(0, 8).map((x) => (
            <li key={x.key} className="flex min-w-0 flex-col gap-1">
              <div className="flex min-w-0 items-center justify-between gap-2 text-sm">
                <span className="min-w-0 truncate">{renderLabel(x.key)}</span>
                <span className="shrink-0 font-semibold tabular-nums">{x.count}</span>
              </div>
              <ProgressBar value={x.count} max={max} size="sm" decorative className="h-1" />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

export default function OverviewKpiDialog({ kind, rows, totals = {}, nowMs, onClose, onFilter }) {
  const t = useT()
  const open = KPI_DIALOG_KINDS.includes(kind)

  const model = useMemo(() => {
    if (!open) return null
    const all = rows || []
    if (kind === 'down') return { list: attentionRows(all.filter((r) => r.status === 'down')) }
    if (kind === 'stale') {
      const list = all.filter((r) => r.status === 'stale')
        .sort((a, b) => (ageMs(b.last_checked_at, nowMs) ?? 0) - (ageMs(a.last_checked_at, nowMs) ?? 0))
      return { list }
    }
    if (kind === 'alerts') return { list: alertRows(all), levels: alertLevelCounts(all) }
    const paused = all.filter((r) => r.status === 'paused')
    return { list: paused.filter((r) => !r.inventory_inactive), orphans: paused.filter((r) => r.inventory_inactive) }
  }, [open, kind, rows, nowMs])

  if (!open || !model) return null

  const every = kind === 'paused' ? [...model.list, ...model.orphans] : model.list
  const byType = breakdown(every, (r) => r.type)
  const byTeam = breakdown(every, teamKey)
  const teamName = (key) => {
    if (key === NO_TEAM) return t('mo.colf.noTeam')
    return every.find((r) => teamKey(r) === key)?.team_name || key
  }
  const teamCount = byTeam.length

  const TITLE = {
    down: { Icon: CircleAlert, title: t('mo.dlg.down.title'), intro: t('mo.dlg.down.intro'), empty: t('mo.dlg.down.empty') },
    stale: { Icon: Clock, title: t('mo.dlg.stale.title'), intro: t('mo.dlg.stale.intro'), empty: t('mo.dlg.stale.empty') },
    alerts: { Icon: BellRing, title: t('mo.dlg.alerts.title'), intro: t('mo.dlg.alerts.intro'), empty: t('mo.dlg.alerts.empty') },
    paused: { Icon: PauseCircle, title: t('mo.dlg.paused.title'), intro: t('mo.dlg.paused.intro'), empty: t('mo.dlg.paused.empty') },
  }[kind]

  const openFor = (r) => (r.open_since ? t('mo.att.openFor', formatDuration(ageMs(r.open_since, nowMs), t)) : null)

  // Özet kutucukları
  let tiles
  if (kind === 'down') {
    const critical = model.list.filter((r) => String(r.open_alert_level || '').toUpperCase() === 'CRITICAL').length
    const withAlert = model.list.filter((r) => Number(r.open_alerts || 0) > 0).length
    tiles = [
      { key: 'n', label: t('mo.dlg.down.count'), value: model.list.length, tone: model.list.length ? 'bad' : 'ok' },
      { key: 'crit', label: t('mo.dlg.down.critical'), value: critical, tone: critical ? 'bad' : undefined },
      { key: 'alert', label: t('mo.dlg.down.withAlert'), value: withAlert, hint: withAlert < model.list.length ? t('mo.dlg.down.noAlertHint') : null },
      { key: 'teams', label: t('mo.dlg.teamsAffected'), value: teamCount },
    ]
  } else if (kind === 'stale') {
    const oldest = model.list[0]
    tiles = [
      { key: 'n', label: t('mo.dlg.stale.count'), value: model.list.length, tone: model.list.length ? 'warn' : 'ok' },
      { key: 'oldest', label: t('mo.dlg.stale.oldest'), value: oldest ? (formatAge(oldest.last_checked_at, nowMs, t) ?? '—') : '—' },
      { key: 'types', label: t('mo.dlg.typesAffected'), value: byType.length },
      { key: 'teams', label: t('mo.dlg.teamsAffected'), value: teamCount },
    ]
  } else if (kind === 'alerts') {
    const lv = model.levels
    const unacked = model.list.filter((r) => !r.open_acknowledged).length
    tiles = [
      { key: 'n', label: t('mo.dlg.alerts.count'), value: Number(totals.open_alerts ?? lv.alerts), tone: lv.alerts ? 'bad' : 'ok',
        hint: t('mo.dlg.alerts.onMonitors', lv.monitors) },
      { key: 'crit', label: t('mo.dlg.alerts.critical'), value: lv.CRITICAL, tone: lv.CRITICAL ? 'bad' : undefined },
      { key: 'high', label: t('mo.dlg.alerts.high'), value: lv.HIGH, tone: lv.HIGH ? 'warn' : undefined },
      { key: 'warn', label: t('mo.dlg.alerts.warning'), value: lv.WARNING },
      { key: 'unacked', label: t('mo.dlg.alerts.unacked'), value: unacked, tone: unacked ? 'warn' : undefined },
    ]
  } else {
    tiles = [
      { key: 'n', label: t('mo.dlg.paused.count'), value: model.list.length },
      { key: 'inv', label: t('mo.dlg.paused.orphans'), value: model.orphans.length, tone: model.orphans.length ? 'warn' : undefined },
      { key: 'types', label: t('mo.dlg.typesAffected'), value: byType.length },
      { key: 'teams', label: t('mo.dlg.teamsAffected'), value: teamCount },
    ]
  }

  const detailOf = (r) => {
    if (kind === 'down') {
      const parts = [r.last_error || (r.last_ok === false ? t('mo.att.lastFailed') : t('mo.att.alertOnly')), openFor(r)].filter(Boolean)
      return parts.join(' · ')
    }
    if (kind === 'stale') return staleDetail(r, nowMs, t)
    if (kind === 'alerts') {
      return [t('mo.dlg.alerts.openCount', Number(r.open_alerts || 0)), openFor(r)].filter(Boolean).join(' · ')
    }
    return null
  }
  const badgesOf = (r) => (
    <>
      {r.open_alert_level && <AlertLevelBadge level={r.open_alert_level} className="text-[10px]" />}
      {kind === 'alerts' && r.open_acknowledged && (
        <Badge variant="secondary" className="gap-1 text-[10px]"><ShieldCheck aria-hidden="true" />{t('mo.att.acked')}</Badge>
      )}
    </>
  )

  const filterLabel = t('mo.dlg.filterList')
  const footer = (
    <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:justify-end">
      <Button type="button" variant={kind === 'alerts' ? 'outline' : 'default'} data-slot="mo-dlg-filter"
        className="pointer-coarse:h-10" onClick={() => onFilter(kind)}>
        <Filter aria-hidden="true" />{filterLabel}
      </Button>
      {kind === 'alerts' && (
        <Button type="button" data-slot="mo-dlg-history" className="pointer-coarse:h-10"
          onClick={() => navigateTo('alerthistory', { view: 'open' })}>
          <History aria-hidden="true" />{t('mo.dlg.alerts.openHistory')}
        </Button>
      )}
    </div>
  )

  const shown = model.list.slice(0, DIALOG_ROW_LIMIT)
  const empty = every.length === 0

  return (
    <ModalShell open onClose={onClose} title={TITLE.title} icon={TITLE.Icon} size="lg" scrollBody closeOnNavigate
      className={PHONE_FULLSCREEN} footer={footer}>
      <div data-slot="mo-kpi-dialog" data-kind={kind} className="flex min-w-0 flex-col gap-4 pb-1">
        <p className="m-0 text-sm text-muted-foreground">{TITLE.intro}</p>

        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {tiles.map(({ key, ...tile }) => <Tile key={key} {...tile} />)}
        </div>

        {kind === 'stale' && (
          <AlertBanner tone="info" title={t('mo.dlg.stale.noteTitle')}>{t('mo.dlg.stale.note')}</AlertBanner>
        )}

        {empty ? (
          <StatusBlock tone="success" icon={ShieldCheck} title={TITLE.empty} className="py-8" />
        ) : (
          // Liste önce (asıl eylem alanı), dağılım yanda (≥ 768) ya da altta (telefon)
          <div className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-[minmax(0,1fr)_15rem]">
            <div className="flex min-w-0 flex-col gap-4">
            {model.list.length > 0 && (
              <section aria-label={t('mo.dlg.listTitle')} className="flex min-w-0 flex-col gap-2">
                <h4 className="m-0 text-sm font-semibold">
                  {kind === 'paused' ? t('mo.dlg.paused.listTitle', model.list.length) : t('mo.dlg.listCount', model.list.length)}
                </h4>
                <div className="flex min-w-0 flex-col gap-2" data-slot="mo-dlg-list">
                  {shown.map((r) => (
                    <OverviewMonitorItem key={`${r.type}-${r.id}`} row={r} nowMs={nowMs} slot="mo-dlg-item"
                      showStatus={kind === 'alerts'} badges={badgesOf(r)} detail={detailOf(r)} />
                  ))}
                </div>
                {model.list.length > shown.length && (
                  <p className="m-0 text-xs text-muted-foreground">{t('mo.dlg.more', model.list.length - shown.length)}</p>
                )}
              </section>
            )}

            {kind === 'paused' && model.orphans.length > 0 && (
              <section aria-label={t('mo.dlg.paused.orphanTitle', model.orphans.length)} data-slot="mo-dlg-orphans" className="flex min-w-0 flex-col gap-2">
                <h4 className="m-0 text-sm font-semibold">{t('mo.dlg.paused.orphanTitle', model.orphans.length)}</h4>
                <AlertBanner tone="warning" title={t('mo.invInactive')}>{t('mo.dlg.paused.orphanNote')}</AlertBanner>
                <div className="flex min-w-0 flex-col gap-2">
                  {model.orphans.slice(0, DIALOG_ROW_LIMIT).map((r) => {
                    const twin = r.standalone_twin
                    const meta = TYPE_META[r.type]
                    return (
                      <OverviewMonitorItem key={`${r.type}-${r.id}`} row={r} nowMs={nowMs} slot="mo-dlg-item" showStatus={false}
                        badges={<Badge variant="outline" data-slot="mo-inv-inactive" className="text-[10px]">{t('mo.invInactive')}</Badge>}
                        detail={twin ? (
                          <span data-slot="mo-dlg-twin" className="inline-flex flex-wrap items-center gap-1">
                            <Link2 aria-hidden="true" className="size-3" />{t('mo.dlg.paused.twin', twin.name || twin.target)}
                            <StatusBadge status={twin.status} className="text-[10px]" />
                          </span>
                        ) : t('mo.dlg.paused.noTwin')}
                        actions={twin ? (
                          <Button type="button" variant="outline" size="sm" className="flex-1 @md/item:flex-none pointer-coarse:h-10"
                            data-slot="mo-dlg-open-twin" aria-label={t('mo.dlg.paused.openTwin', twin.name || twin.target)}
                            onClick={() => navigateTo(meta?.tab || 'http', { q: twin.target })}>
                            <ExternalLink aria-hidden="true" />{t('mo.dlg.paused.openTwinShort')}
                          </Button>
                        ) : <ItemRowActions row={r} />} />
                    )
                  })}
                </div>
              </section>
            )}
            </div>
            <aside aria-label={t('mo.dlg.breakdown')} className="flex min-w-0 flex-col gap-3">
              <BarList slot="mo-dlg-bytype" title={t('mo.dlg.byType')} icon={Filter} items={byType}
                renderLabel={(k) => <TypeLabel type={k} />} />
              <BarList slot="mo-dlg-byteam" title={t('mo.dlg.byTeam')} icon={Users} items={byTeam}
                renderLabel={(k) => teamName(k)} />
            </aside>
          </div>
        )}
      </div>
    </ModalShell>
  )
}
