import { CircleCheck, CircleHelp, FileWarning, PhoneOff, Siren, TriangleAlert, Wrench } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { relTimeOrRaw } from '../../utils/relativeTime.js'
import ToneBadge from '../admin/ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import {
  Empty, LEVEL_TONE, LINK_RESET, List, More, Row, Tile, fetchOpenAlerts, fetchStatus, levelKey, linkTo, listLimit,
} from './widgetParts.jsx'
import { NOC_CALL_SLA_MIN, incidentsSummary, lightSummary, maintenanceSummary, nocUncalledSummary } from './myDashModel.js'

/**
 * 7/24 Komuta kutuları (2026-10-13, kullanıcı isteği: "7/24 ekibi önünde olsun; sorun varsa kırmızı, uyarı ise turuncu,
 * yoksa yeşil"). Her kutu MEVCUT bir uçtan okur (kapsam / izin değişmez): durum lambası ve açık alarmlar
 * `/api/me/open-alerts`, aranmayı bekleyenler `/api/noc/console`, bakım ve olaylar `/api/status-page` (sunucuda bellekli).
 * Okunamayan veri hiçbir zaman yeşil çizilmez (WidgetCard + özetlerin `unavailable` dalı).
 */

// ── Durum lambası ───────────────────────────────────────────────────────────────────────────────────────────────

const LIGHT = {
  danger:  { lamp: 'bg-red-600 text-white ring-red-600/25', ink: 'text-destructive', Icon: Siren },
  warning: { lamp: 'bg-amber-400 text-amber-950 ring-amber-400/35', ink: 'text-amber-700 dark:text-amber-300', Icon: TriangleAlert },
  success: { lamp: 'bg-green-600 text-white ring-green-600/25', ink: 'text-success', Icon: CircleCheck },
  muted:   { lamp: 'bg-muted text-muted-foreground ring-border', ink: 'text-muted-foreground', Icon: CircleHelp },
}

export function NocLightWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchOpenAlerts, { refreshKey })
  const s = lightSummary(data)
  const L = LIGHT[s.tone] || LIGHT.muted
  const word = s.hidden ? t('mydash.noAccess') : t('mydash.light.state.' + s.tone)
  const detail = s.hidden ? t('mydash.noAccessBody') : s.total === 0 ? t('mydash.alerts.none') : t('mydash.light.counts', s.total, s.severe)
  // Kırmızıda en eski KRİTİK/YÜKSEK alarm, turuncuda en eski açık alarm — "ne zamandır" sorusunun yanıtı
  const oldestIso = s.tone === 'danger' ? s.oldestSevere : s.oldest
  const oldest = oldestIso && !s.hidden ? t(s.tone === 'danger' ? 'mydash.light.oldestSevere' : 'mydash.light.oldest', relTimeOrRaw(oldestIso, t)) : null
  const go = s.hidden ? undefined : linkTo(frame, 'alerthistory')
  const body = (
    <>
      <span role="img" aria-label={word} data-slot="mydash-light" data-tone={s.tone}
        className={cn('grid shrink-0 place-items-center rounded-full ring-8', L.lamp, frame.tv ? 'size-24' : 'size-16')}>
        <L.Icon className={frame.tv ? 'size-12' : 'size-8'} aria-hidden="true" />
      </span>
      <span className="flex min-w-0 flex-col gap-1">
        <span className={cn('block font-bold leading-tight [overflow-wrap:anywhere]', frame.tv ? 'text-3xl' : 'text-2xl', L.ink)} data-slot="mydash-light-word">{word}</span>
        <span className={cn('block text-muted-foreground [overflow-wrap:anywhere]', frame.tv ? 'text-base' : 'text-sm')}>{detail}</span>
        {oldest && <span className={cn('block font-medium [overflow-wrap:anywhere]', frame.tv ? 'text-base' : 'text-sm')} data-slot="mydash-light-oldest">{oldest}</span>}
      </span>
    </>
  )
  return (
    <WidgetCard frame={frame} Icon={Siren} title={t('mydash.w.noc_light')} tab="alerthistory" loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={null}
      stat={{ value: word, label: detail, sub: oldest, onClick: go, hint: t('mydash.linkHint'), text: true }}>
      {go ? (
        <Button type="button" variant="ghost" onClick={go} title={t('mydash.linkHint')} data-link="true" data-slot="mydash-light-link"
          className={cn(LINK_RESET, '-m-2 flex items-center gap-4 rounded-xl p-2 hover:bg-muted/70')}>
          {body}
        </Button>
      ) : <div className="flex items-center gap-4">{body}</div>}
    </WidgetCard>
  )
}

// ── 7/24'e gidip aranmayı bekleyen alarmlar ──────────────────────────────────────────────────────────────────────

/** Konsol sayfası boyutu (sunucu sayfaları 0 tabanlı). */
export const NOC_FETCH_SIZE = 50

/**
 * Konsol süzgeci: 7/24'e gitmiş, açık, araması yapılmamış. Sunucu bu satırları EN YENİ önce sıralar (NocConsoleService
 * PRIORITY), oysa kırmızı/turuncu kararı EN ESKİ bekleyenden verilir (inceleme, 2026-10-13: 60 bekleyende en eski 10'u ilk
 * sayfaya girmiyor, kutu turuncu kalıyordu). Toplam ilk sayfadan büyükse SON sayfa da okunur (en eskiler orada). Son sayfa
 * okunamazsa kutu hata durumuna geçer — eksik veriyle renk verilmez.
 */
export async function fetchNocUncalled() {
  const base = { window: '24h', noc: 'sent', called: 'no', state: 'open', size: NOC_FETCH_SIZE }
  const first = await api.noc.console({ ...base, page: 0 })
  if (!first || first.success === false) return first
  const d = first.data || {}
  const items = Array.isArray(d.items) ? d.items : []
  const total = Number(d.total) || 0
  if (total <= items.length || items.length === 0) return first
  const size = Number(d.size) > 0 ? Number(d.size) : NOC_FETCH_SIZE
  const lastPage = Math.ceil(total / size) - 1
  const last = await api.noc.console({ ...base, page: lastPage })
  if (!last || last.success === false) return last
  const tail = Array.isArray(last.data?.items) ? last.data.items : []
  // İki çağrı arasında sunucu belleği yenilendiyse (yeni alarm geldi) istenen sayfa artık SON değildir: en eskiler kaçmış
  // olabilir → sonuç kesik (`truncated`) sayılır, "tam" görünmez (inceleme 2026-10-13 tur 2)
  const moved = Number(last.data?.total) !== total || Number(last.data?.page) !== lastPage
  return { success: true, data: { ...d, items: [...items, ...tail], truncated: d.truncated === true || moved, pages_read: 2 } }
}
/** Konsolun aynı süzgeçle açılması (n_ önekli URL anahtarları — nocConsoleModel.URL_KEYS). */
const NOC_VIEW = Object.freeze({ n_view: 'console', n_cnoc: 'sent', n_ccall: 'no', n_cst: 'open' })

export function NocUncalledWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchNocUncalled, { refreshKey })
  const s = nocUncalledSummary(data, Date.now())
  const limit = listLimit(frame.widget.size, frame.tv, 5)
  const toneLabel = s.partial && s.total === 0 ? t('mydash.noc.partial')
    : s.total === 0 ? t('mydash.noc.allCalled')
      : s.overdue > 0 ? t('mydash.noc.overdueCount', s.overdueAtLeast ? `${s.overdue}+` : s.overdue) : t('mydash.noc.waitingCount', s.total)
  const oldest = s.oldestSentAt ? t('mydash.noc.oldestWait', relTimeOrRaw(s.oldestSentAt, t)) : null
  // Renkli panel gövdeyi çizmez: kesik liste uyarısı panelin alt satırında da söylenir
  const statSub = s.partial ? t('mydash.noc.partialNote') : oldest
  return (
    <WidgetCard frame={frame} Icon={PhoneOff} title={t('mydash.w.noc_uncalled')} tab="noc" tabParams={NOC_VIEW} loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={toneLabel}
      stat={{ value: s.total, label: t('mydash.noc.waitingLabel'), sub: statSub, onClick: linkTo(frame, 'noc', NOC_VIEW), hint: t('mydash.linkHint') }}>
      <div className="grid grid-cols-2 gap-2">
        <Tile value={s.total} label={t('mydash.noc.waiting')} tone={s.overdue ? 'danger' : s.total ? 'warning' : 'muted'} tv={frame.tv}
          slot="mydash-noc-waiting" onClick={linkTo(frame, 'noc', NOC_VIEW)} hint={t('mydash.linkHint')} />
        <Tile value={s.overdueAtLeast ? `${s.overdue}+` : s.overdue} label={t('mydash.noc.overdue', NOC_CALL_SLA_MIN)} tone={s.overdue ? 'danger' : 'muted'}
          tv={frame.tv} slot="mydash-noc-overdue" onClick={linkTo(frame, 'noc', NOC_VIEW)} hint={t('mydash.linkHint')} />
      </div>
      {s.total === 0 ? <Empty tone={s.partial ? 'muted' : 'success'} icon={s.partial ? TriangleAlert : CircleCheck}
        text={t(s.partial ? 'mydash.noc.partialBody' : 'mydash.noc.none')} tv={frame.tv} /> : limit > 0 && (
        <>
          <List slot="mydash-noc-list">
            {s.rows.slice(0, limit).map((it) => (
              <Row key={it.id}
                lead={<ToneBadge tone={LEVEL_TONE[String(it.level || '').toUpperCase()] || 'muted'}>{t('mydash.lvl.' + levelKey(it.level))}</ToneBadge>}
                title={it.domain || it.monitor?.name || '—'}
                meta={[it.team_name, t('mydash.noc.sentAgo', relTimeOrRaw(it.sentAt, t))].filter(Boolean).join(' · ')}
                trail={it.overdue ? <ToneBadge tone="danger">{t('mydash.noc.late')}</ToneBadge> : null}
                onClick={linkTo(frame, 'noc', it.domain ? { ...NOC_VIEW, n_cq: it.domain } : NOC_VIEW)} hint={t('mydash.linkDetail')} />
            ))}
          </List>
          <More n={Math.max(0, s.total - Math.min(limit, s.rows.length))} t={t} />
        </>
      )}
      {s.partial && s.total > 0 && <p className="m-0 text-xs text-muted-foreground" data-slot="mydash-noc-partial">{t('mydash.noc.partialNote')}</p>}
      {!frame.tv && <p className="m-0 text-xs text-muted-foreground" data-slot="mydash-noc-note">{t('mydash.noc.slaNote', NOC_CALL_SLA_MIN)}</p>}
    </WidgetCard>
  )
}

// ── Bakım pencereleri ────────────────────────────────────────────────────────────────────────────────────────────

export function MaintenanceWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchStatus, { refreshKey })
  const limit = listLimit(frame.widget.size, frame.tv, 4)
  const s = maintenanceSummary(data)
  // Pencereler okunamadıysa "bakım yok" DEMEZ — kutu hata durumunda kalır (WidgetCard)
  const err = error || (s.unavailable ? t('mydash.maint.unavailable') : null)
  const scopeMeta = (w) => (w.all_monitors ? t('sp.maint.all') : w.monitor_count != null ? t('mydash.maint.monitors', w.monitor_count) : null)
  const rows = [
    ...s.active.map((w) => ({ w, active: true })),
    ...s.upcoming.map((w) => ({ w, active: false })),
  ]
  const toneLabel = s.activeTotal > 0 ? t('mydash.maint.activeCount', s.activeTotal)
    : t(s.hidden > 0 ? 'mydash.maint.noneActiveVisible' : 'mydash.maint.noneActive')
  const maintSub = [t('mydash.maint.upcomingCount', s.upcomingTotal), s.hidden > 0 ? t('sp.maint.hidden', s.hidden) : null].filter(Boolean).join(' · ')
  return (
    <WidgetCard frame={frame} Icon={Wrench} title={t('mydash.w.maintenance')} tab="maintenance" loading={loading} error={err} at={s.unavailable ? null : at}
      tone={s.tone} toneLabel={toneLabel}
      stat={{ value: s.activeTotal, label: t('mydash.maint.activeLabel'), sub: maintSub,
        onClick: linkTo(frame, 'maintenance'), hint: t('mydash.linkHint') }}>
      <div className="grid grid-cols-2 gap-2 @sm:grid-cols-3">
        <Tile value={s.activeTotal} label={t('mydash.maint.active')} tone={s.activeTotal ? 'warning' : 'muted'} tv={frame.tv} slot="mydash-maint-active"
          onClick={linkTo(frame, 'maintenance')} hint={t('mydash.linkHint')} />
        <Tile value={s.upcomingTotal} label={t('mydash.maint.upcoming')} tv={frame.tv} slot="mydash-maint-upcoming"
          onClick={linkTo(frame, 'maintenance')} hint={t('mydash.linkHint')} />
        <Tile value={s.monitorsInMaintenance} label={t('mydash.maint.monitorsIn')} tone={s.monitorsInMaintenance ? 'warning' : 'muted'} tv={frame.tv}
          slot="mydash-maint-monitors" onClick={linkTo(frame, 'status')} hint={t('mydash.linkHint')} />
      </div>
      {s.activeTotal + s.upcomingTotal === 0 ? <Empty text={t(s.hidden > 0 ? 'mydash.maint.noneVisible' : 'sp.maint.none')} tv={frame.tv} /> : limit > 0 && rows.length > 0 && (
        <>
          <List slot="mydash-maint-list">
            {rows.slice(0, limit).map(({ w, active }) => (
              <Row key={`${active ? 'a' : 'u'}-${w.id}`}
                lead={<ToneBadge tone={active ? 'warning' : 'muted'}>{t(active ? 'sp.maint.badgeActive' : 'sp.maint.badgeUpcoming')}</ToneBadge>}
                title={w.name || '—'}
                meta={[active ? t('mydash.maint.until', formatDate(w.ends_at)) : t('mydash.maint.starts', formatDate(w.starts_at)),
                  w.team_name, scopeMeta(w)].filter(Boolean).join(' · ')}
                onClick={linkTo(frame, 'maintenance')} hint={t('mydash.linkHint')} />
            ))}
          </List>
          <More n={Math.max(0, rows.length - limit)} t={t} />
        </>
      )}
      {s.hidden > 0 && <p className="m-0 text-xs text-muted-foreground">{t('sp.maint.hidden', s.hidden)}</p>}
    </WidgetCard>
  )
}

// ── Açık olaylar ─────────────────────────────────────────────────────────────────────────────────────────────────

const SEV_TONE = { CRITICAL: 'danger', HIGH: 'danger', MEDIUM: 'warning', LOW: 'muted' }
const INC_STATUS = new Set(['OPEN', 'INVESTIGATING', 'MITIGATED'])

export function IncidentsWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchStatus, { refreshKey })
  const limit = listLimit(frame.widget.size, frame.tv, 4)
  const s = incidentsSummary(data)
  const err = error || (s.unavailable ? t('mydash.inc.unavailable') : null)
  const openList = linkTo(frame, 'incident-history', { ih_open: 'true' })
  const toneLabel = s.visible > 0 ? t('mydash.inc.openCount', s.visible)
    : t(s.hidden > 0 ? 'mydash.inc.noneVisibleShort' : 'sp.inc.none')
  const incSub = [t('mydash.inc.severeCount', s.severe), s.hidden > 0 ? t('sp.inc.hidden', s.hidden) : null].filter(Boolean).join(' · ')
  return (
    <WidgetCard frame={frame} Icon={FileWarning} title={t('mydash.w.incidents_open')} tab="incident-history" loading={loading} error={err}
      at={s.unavailable ? null : at} tone={s.tone} toneLabel={toneLabel}
      stat={{ value: s.visible, label: t('mydash.inc.openLabel'), sub: incSub, onClick: openList, hint: t('mydash.linkHint') }}>
      <div className="grid grid-cols-2 gap-2">
        <Tile value={s.severe} label={t('mydash.inc.severe')} tone={s.severe ? 'danger' : 'muted'} tv={frame.tv} slot="mydash-inc-severe"
          onClick={openList} hint={t('mydash.linkHint')} />
        <Tile value={s.visible - s.severe} label={t('mydash.inc.other')} tone={s.visible - s.severe ? 'warning' : 'muted'} tv={frame.tv}
          slot="mydash-inc-other" onClick={openList} hint={t('mydash.linkHint')} />
      </div>
      {s.visible === 0 ? <Empty text={t(s.hidden > 0 ? 'mydash.inc.noneVisible' : 'sp.inc.noneText')} tv={frame.tv} /> : limit > 0 && s.rows.length > 0 && (
        <>
          <List slot="mydash-inc-list">
            {s.rows.slice(0, limit).map((r) => {
              const sev = String(r.severity || '').toUpperCase()
              const st = String(r.status || '').toUpperCase()
              return (
                <Row key={r.id}
                  lead={<ToneBadge tone={SEV_TONE[sev] || 'muted'}>{SEV_TONE[sev] ? t('sp.sev.' + sev) : (r.severity || '—')}</ToneBadge>}
                  title={r.title || '—'}
                  meta={[INC_STATUS.has(st) ? t('sp.inc.status.' + st) : null, r.team_name,
                    r.started_at ? t('sp.inc.started', relTimeOrRaw(r.started_at, t)) : null].filter(Boolean).join(' · ')}
                  onClick={r.id != null ? linkTo(frame, 'incident-history', { ih_id: r.id }) : undefined} hint={t('mydash.linkDetail')} />
              )
            })}
          </List>
          <More n={Math.max(0, s.visible - Math.min(limit, s.rows.length))} t={t} />
        </>
      )}
      {s.hidden > 0 && <p className="m-0 text-xs text-muted-foreground">{t('sp.inc.hidden', s.hidden)}</p>}
    </WidgetCard>
  )
}

/** Kutu → ikon eşlemesi (widgets.jsx WIDGETS kaydına eklenir). */
export const NOC_WIDGETS = {
  noc_light:      { Icon: Siren,         Component: NocLightWidget },
  noc_uncalled:   { Icon: PhoneOff,      Component: NocUncalledWidget },
  maintenance:    { Icon: Wrench,        Component: MaintenanceWidget },
  incidents_open: { Icon: FileWarning,   Component: IncidentsWidget },
}
