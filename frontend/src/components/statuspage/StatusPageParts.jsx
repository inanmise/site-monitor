// Kurum içi Durum Sayfası — parçalar (2026-10-01). Hepsi shadcn: Card, Badge, Accordion, Collapsible, Separator, Button;
// boş durumlar ui/StatusBlock (Empty). Durum hiçbir yerde YALNIZ renkle verilmez: rozet ikon + metin taşır. Kartlarda
// sol şerit yok (SHADCN.md §1). Mobil-önce: satırlar telefonda alt alta, ≥ 640 px yan yana; uzun adlar sarar.
import { useState } from 'react'
import {
  Users, ChevronDown, Siren, History, Wrench, Info, CircleCheck, CalendarClock, Repeat, Activity, EyeOff,
} from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { TYPE_META } from '../monitoring/overviewMeta.js'
import { formatAge, fullTime } from '../monitoring/overviewModel.js'
import { formatDuration } from '../../utils/incidentMeta.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import {
  STATE_META, STATE_ORDER, MONITOR_STATE, MONITOR_LABEL_KEY, stateMeta, serviceLabel, sortMonitors, pctText,
  severityMeta, shortTime, BANNER_KEY, LEGEND_KEY, INCIDENT_STATUS_KEY, RECURRENCE_KEY,
} from './statusPageModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

/** Durum rozeti — ikon + metin (renk tek başına bilgi taşımaz); `data-state` test kancası. */
export function StateBadge({ state, label, className }) {
  const t = useT()
  const meta = stateMeta(state)
  return (
    <Badge variant="outline" data-slot="sp-state" data-state={STATE_META[state] ? state : 'no_data'}
      className={cn('h-6 gap-1 rounded-full px-2 font-semibold whitespace-nowrap', meta.badge, className)}>
      <meta.Icon aria-hidden="true" className="size-3.5" />{label ?? t(meta.labelKey)}
    </Badge>
  )
}

/** Bölüm kartı başlığı (ikon + başlık + sayı + açıklama). */
function SectionHeader({ Icon, title, count, desc }) {
  return (
    <CardHeader className="gap-1 px-4 sm:px-5">
      <CardTitle className="flex min-w-0 flex-wrap items-center gap-2 text-base">
        <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
        <span className="min-w-0">{title}</span>
        {count != null && <Badge variant="secondary" className="h-5 rounded-full px-1.5 tabular-nums">{count}</Badge>}
      </CardTitle>
      {desc && <CardDescription className="text-xs">{desc}</CardDescription>}
    </CardHeader>
  )
}

// ── Üst şerit ─────────────────────────────────────────────────────────────────────────────────────────────────────

export function OverallBanner({ data }) {
  const t = useT()
  const o = data?.overall || {}
  const state = STATE_META[o.state] ? o.state : 'no_data'
  const sm = stateMeta(state)
  const byState = o.by_state || {}
  const problems = Number(byState.degraded || 0) + Number(byState.partial_outage || 0) + Number(byState.major_outage || 0)
  const facts = [
    t('sp.banner.services', Number(o.services_total || 0)),
    problems > 0 ? t('sp.banner.problems', problems) : null,
    Number(o.active_incidents || 0) > 0 ? t('sp.banner.incidents', Number(o.active_incidents)) : null,
    Number(o.active_maintenance || 0) > 0 ? t('sp.banner.maintCount', Number(o.active_maintenance)) : null,
  ].filter(Boolean)
  return (
    <Card data-slot="sp-banner" data-state={state} role="status" aria-live="polite" className="gap-4 px-4 py-4 sm:px-5 sm:py-5">
      <div className="flex min-w-0 items-start gap-3 sm:items-center sm:gap-4">
        <span aria-hidden="true" className={cn('grid size-11 shrink-0 place-items-center rounded-xl sm:size-12', sm.tile)}>
          <sm.Icon className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          <h3 data-slot="sp-headline" className="m-0 text-lg leading-snug font-semibold tracking-tight sm:text-xl">{t(BANNER_KEY[state])}</h3>
          <p className="m-0 mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-sm text-muted-foreground">
            {facts.map((f) => <span key={f}>{f}</span>)}
          </p>
        </div>
      </div>
      {/* Durumlara göre hizmet sayıları — sıfır olanlar gizli; her çip ikon + metin + sayı */}
      {Number(o.services_total || 0) > 0 && (
        <ul data-slot="sp-by-state" aria-label={t('sp.byState.aria')} className="m-0 flex list-none flex-wrap gap-1.5 p-0">
          {STATE_ORDER.filter((k) => Number(byState[k] || 0) > 0).map((k) => (
            <li key={k}><StateBadge state={k} label={`${t(STATE_META[k].labelKey)} · ${Number(byState[k])}`} className="font-medium" /></li>
          ))}
        </ul>
      )}
    </Card>
  )
}

// ── Hizmetler ─────────────────────────────────────────────────────────────────────────────────────────────────────

function MonitorList({ monitors }) {
  const t = useT()
  return (
    <ul data-slot="sp-monitors" className="m-0 mt-2 flex list-none flex-col gap-1 rounded-lg border bg-muted/30 p-2">
      {monitors.map((m, i) => {
        const TypeIcon = TYPE_META[m.type]?.Icon ?? Activity
        return (
          <li key={`${m.type}-${m.name}-${i}`} data-slot="sp-monitor" data-status={m.status}
            className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1 px-1 py-1 text-sm">
            <span className="flex min-w-0 items-center gap-2">
              <TypeIcon aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
              <span className="sr-only">{TYPE_META[m.type] ? t(TYPE_META[m.type].labelKey) : m.type}: </span>
              <span className="min-w-0 [overflow-wrap:anywhere]">{m.name}</span>
            </span>
            <StateBadge state={MONITOR_STATE[m.status] ?? 'no_data'} label={t(MONITOR_LABEL_KEY[m.status] ?? 'sp.mon.unknown')} className="h-5 text-[11px]" />
          </li>
        )
      })}
    </ul>
  )
}

function ServiceRow({ s, nowMs }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const label = serviceLabel(s, t)
  const monitors = s.monitors_visible && Array.isArray(s.monitors) ? sortMonitors(s.monitors) : null
  const since = s.since ? formatAge(s.since, nowMs, t) : null
  const uptime = pctText(s.uptime_7d)
  return (
    <li data-slot="sp-service" data-state={s.state} data-key={s.key} className="min-w-0 py-3 first:pt-1 last:pb-1">
      <Collapsible open={open} onOpenChange={setOpen}>
        <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-3">
          <div className="min-w-0">
            <p data-slot="sp-service-name" className={cn('m-0 text-sm font-medium [overflow-wrap:anywhere]', s.ungrouped && 'text-muted-foreground')}>{label}</p>
            <p className="m-0 mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
              <span>{t('sp.svc.monitors', Number(s.monitors_active || 0))}</span>
              {s.down > 0 && <span className="font-medium text-destructive">{t('sp.svc.down', s.down)}</span>}
              {s.degraded > 0 && <span className="font-medium text-amber-700 dark:text-amber-300">{t('sp.svc.degraded', s.degraded)}</span>}
              {s.maintenance > 0 && <span>{t('sp.svc.maintenance', s.maintenance)}</span>}
              {since && <span title={fullTime(s.since, locale) || undefined}>{t('sp.svc.since', since)}</span>}
              {s.maintenance_until && <span>{t('sp.svc.until', shortTime(s.maintenance_until, locale))}</span>}
              {uptime && <span data-slot="sp-uptime" title={t('sp.svc.uptimeTip')}>{t('sp.svc.uptime', uptime)}</span>}
            </p>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            {monitors && monitors.length > 0 && (
              <CollapsibleTrigger asChild>
                <Button type="button" variant="ghost" size="sm" aria-label={t('sp.svc.monitorsAria', monitors.length, label)}
                  className="h-7 gap-1 px-2 text-xs pointer-coarse:h-10">
                  {t('sp.svc.showMonitors', monitors.length)}
                  <ChevronDown aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
                </Button>
              </CollapsibleTrigger>
            )}
            <StateBadge state={s.state} />
          </div>
        </div>
        {monitors && monitors.length > 0 && (
          <CollapsibleContent>
            <MonitorList monitors={monitors} />
          </CollapsibleContent>
        )}
      </Collapsible>
    </li>
  )
}

/** Takım grupları — shadcn Accordion (çoklu); kapalı grup da en kötü durumunu ve sorun sayısını gösterir. */
export function TeamGroups({ groups, open, onOpenChange, nowMs }) {
  const t = useT()
  return (
    <Accordion type="multiple" value={open} onValueChange={onOpenChange} data-slot="sp-teams"
      className="min-w-0 overflow-hidden rounded-xl border bg-card text-card-foreground shadow-xs">
      {groups.map((g) => {
        const sm = stateMeta(g.state)
        return (
          <AccordionItem key={g.key} value={g.key} data-slot="sp-team" data-team={g.key} data-worst={g.state}>
            <AccordionTrigger
              className="items-center gap-3 rounded-none px-3 py-3 hover:bg-muted/40 hover:no-underline focus-visible:ring-inset sm:px-4 pointer-coarse:min-h-12 [&>svg]:translate-y-0">
              <span className="flex min-w-0 flex-1 flex-col gap-2 sm:flex-row sm:items-center sm:gap-3">
                <span className="flex min-w-0 items-center gap-2.5">
                  <span aria-hidden="true" className={cn('grid size-8 shrink-0 place-items-center rounded-lg', sm.tile)}>
                    <Users className="size-4" />
                  </span>
                  <span className="min-w-0">
                    <span data-slot="sp-team-name" className="block text-sm font-semibold [overflow-wrap:anywhere]">{g.label}</span>
                    <span className="block text-xs font-normal text-muted-foreground">
                      {t('sp.team.count', g.total)}{' · '}{g.problems > 0 ? t('sp.team.problems', g.problems) : t('sp.team.allOk')}
                    </span>
                  </span>
                </span>
                <span className="flex flex-wrap items-center gap-1.5 pl-[42px] sm:ml-auto sm:pl-0">
                  <StateBadge state={g.state} />
                </span>
              </span>
            </AccordionTrigger>
            <AccordionContent className="min-w-0 px-3 pb-2 sm:px-4">
              <ul className="m-0 flex list-none flex-col divide-y p-0">
                {g.services.map((s) => <ServiceRow key={s.key} s={s} nowMs={nowMs} />)}
              </ul>
            </AccordionContent>
          </AccordionItem>
        )
      })}
    </Accordion>
  )
}

// ── Olaylar ───────────────────────────────────────────────────────────────────────────────────────────────────────

function SeverityBadge({ severity }) {
  const t = useT()
  const sev = severityMeta(severity)
  return <StateBadge state={sev.state} label={sev.labelKey ? t(sev.labelKey) : String(severity || '—')} className="h-5 text-[11px]" />
}

function ServiceChips({ names, more = 0 }) {
  const t = useT()
  if (!names?.length && !more) return null
  return (
    <span className="flex min-w-0 flex-wrap gap-1">
      {names.map((n) => (
        <Badge key={n} variant="outline" className="h-auto max-w-full rounded-full px-2 py-0.5 text-[11px] font-normal whitespace-normal [overflow-wrap:anywhere]">{n}</Badge>
      ))}
      {more > 0 && <Badge variant="secondary" className="h-5 rounded-full px-1.5 text-[11px] font-normal">{t('sp.maint.more', more)}</Badge>}
    </span>
  )
}

/**
 * Görüntüleyicinin bugün göremediği kayıtlar (başka takımın olay kaydı / bakım penceresi) — yalnız SAYI, başlık yok
 * (2026-10-01 "mevcudu bozma": sunucu bu satırları hiç göndermez, yalnız `*_hidden` sayısını).
 */
function HiddenLine({ count, text, slot }) {
  if (!(count > 0)) return null
  return (
    <p data-slot={slot} className="m-0 flex items-center gap-1.5 text-xs text-muted-foreground">
      <EyeOff aria-hidden="true" className="size-3.5 shrink-0" />{text}
    </p>
  )
}

/** Satır listesi tavanı: görülebilen toplam listeden fazlaysa "ilk N / toplam" notu. */
function MoreLine({ shown, visible }) {
  const t = useT()
  if (!(visible > shown)) return null
  return <p className="m-0 text-xs text-muted-foreground">{t('sp.inc.more', shown, visible)}</p>
}

export function ActiveIncidents({ incidents, nowMs }) {
  const t = useT()
  const locale = useDateLocale()
  const list = Array.isArray(incidents?.active) ? incidents.active : []
  const total = Number(incidents?.active_total ?? list.length)
  const hidden = Number(incidents?.active_hidden ?? 0)
  const visible = Number(incidents?.active_visible ?? list.length)
  return (
    <Card data-slot="sp-incidents" className="gap-3 py-4">
      <SectionHeader Icon={Siren} title={t('sp.inc.title')} count={total} desc={t('sp.inc.desc')} />
      <CardContent className="flex flex-col gap-3 px-4 sm:px-5">
        {total === 0 ? (
          <StatusBlock tone="success" icon={CircleCheck} title={t('sp.inc.none')} description={t('sp.inc.noneText')} className="py-6" />
        ) : (
          <>
            {list.length > 0 && <ul className="m-0 flex list-none flex-col divide-y p-0">
              {list.map((i) => (
                <li key={i.id} data-slot="sp-incident" data-severity={i.severity} className="flex min-w-0 flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
                  <div className="flex min-w-0 flex-col gap-1.5 sm:flex-row sm:items-start sm:justify-between sm:gap-3">
                    <p className="m-0 text-sm font-medium [overflow-wrap:anywhere]">{i.title}</p>
                    <span className="flex shrink-0 flex-wrap items-center gap-1.5">
                      <SeverityBadge severity={i.severity} />
                      {INCIDENT_STATUS_KEY[i.status] && <Badge variant="outline" className="h-5 rounded-full px-2 text-[11px] font-normal">{t(INCIDENT_STATUS_KEY[i.status])}</Badge>}
                    </span>
                  </div>
                  <p className="m-0 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                    <span title={fullTime(i.started_at, locale) || undefined}>{t('sp.inc.started', formatAge(i.started_at, nowMs, t) ?? '—')}</span>
                    {i.team_name && <span className="[overflow-wrap:anywhere]">{i.team_name}</span>}
                  </p>
                  <ServiceChips names={i.services || []} />
                </li>
              ))}
            </ul>}
            <MoreLine shown={list.length} visible={visible} />
            <HiddenLine count={hidden} text={t('sp.inc.hidden', hidden)} slot="sp-incidents-hidden" />
          </>
        )}
      </CardContent>
    </Card>
  )
}

export function ResolvedIncidents({ incidents }) {
  const t = useT()
  const locale = useDateLocale()
  const list = Array.isArray(incidents?.resolved) ? incidents.resolved : []
  const total = Number(incidents?.resolved_total ?? list.length)
  const hidden = Number(incidents?.resolved_hidden ?? 0)
  const visible = Number(incidents?.resolved_visible ?? list.length)
  return (
    <Card data-slot="sp-resolved" className="gap-3 py-4">
      <SectionHeader Icon={History} title={t('sp.resolved.title')} count={total} />
      <CardContent className="flex flex-col gap-3 px-4 sm:px-5">
        {total === 0 ? (
          <p className="m-0 text-sm text-muted-foreground">{t('sp.resolved.none')}</p>
        ) : (
          <>
            {list.length > 0 && <ul className="m-0 flex list-none flex-col divide-y p-0">
              {list.map((i) => (
                <li key={i.id} data-slot="sp-resolved-item" className="flex min-w-0 flex-col gap-1 py-2.5 first:pt-0 last:pb-0">
                  <div className="flex min-w-0 items-start justify-between gap-2">
                    <p className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{i.title}</p>
                    <SeverityBadge severity={i.severity} />
                  </div>
                  <p className="m-0 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                    <span title={fullTime(i.resolved_at, locale) || undefined}>{t('sp.resolved.at', shortTime(i.resolved_at, locale))}</span>
                    {i.duration_minutes != null && <span>{t('sp.resolved.duration', formatDuration(Number(i.duration_minutes) * 60_000, t))}</span>}
                    {i.team_name && <span className="[overflow-wrap:anywhere]">{i.team_name}</span>}
                  </p>
                </li>
              ))}
            </ul>}
            <MoreLine shown={list.length} visible={visible} />
            <HiddenLine count={hidden} text={t('sp.inc.hidden', hidden)} slot="sp-resolved-hidden" />
          </>
        )}
      </CardContent>
    </Card>
  )
}

// ── Bakım pencereleri ─────────────────────────────────────────────────────────────────────────────────────────────

function WindowItem({ w }) {
  const t = useT()
  const locale = useDateLocale()
  const names = (w.services || []).map((s) => (s.ungrouped || !s.name ? `${t('sp.ungrouped')}${s.team_name ? ` (${s.team_name})` : ''}` : s.name))
  const more = Math.max(0, Number(w.services_total || 0) - names.length)
  return (
    <li data-slot="sp-window" data-state={w.state} className="flex min-w-0 flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
      <div className="flex min-w-0 items-start justify-between gap-2">
        <p className="m-0 min-w-0 text-sm font-medium [overflow-wrap:anywhere]">{w.name}</p>
        {w.state === 'active'
          ? <StateBadge state="maintenance" label={t('sp.maint.badgeActive')} className="h-5 text-[11px]" />
          : <Badge variant="outline" className="h-5 shrink-0 gap-1 rounded-full px-2 text-[11px]"><CalendarClock aria-hidden="true" />{t('sp.maint.badgeUpcoming')}</Badge>}
      </div>
      <p className="m-0 flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
        <span>{shortTime(w.starts_at, locale)} – {shortTime(w.ends_at, locale)}</span>
        {RECURRENCE_KEY[w.recurrence] && <span className="inline-flex items-center gap-1"><Repeat aria-hidden="true" className="size-3" />{t(RECURRENCE_KEY[w.recurrence])}</span>}
        {w.team_name && <span className="[overflow-wrap:anywhere]">{w.team_name}</span>}
      </p>
      {w.all_monitors
        ? <span><Badge variant="outline" className="h-5 rounded-full px-2 text-[11px]">{t('sp.maint.all')}</Badge></span>
        : <ServiceChips names={names} more={more} />}
    </li>
  )
}

export function MaintenanceCard({ maintenance }) {
  const t = useT()
  const active = Array.isArray(maintenance?.active) ? maintenance.active : []
  const upcoming = Array.isArray(maintenance?.upcoming) ? maintenance.upcoming : []
  const activeHidden = Number(maintenance?.active_hidden ?? 0), upcomingHidden = Number(maintenance?.upcoming_hidden ?? 0)
  const activeTotal = Number(maintenance?.active_total ?? active.length), upcomingTotal = Number(maintenance?.upcoming_total ?? upcoming.length)
  const total = activeTotal + upcomingTotal
  const showActive = active.length > 0 || activeHidden > 0
  const showUpcoming = upcoming.length > 0 || upcomingHidden > 0
  return (
    <Card data-slot="sp-maintenance" className="gap-3 py-4">
      <SectionHeader Icon={Wrench} title={t('sp.maint.title')} count={total} />
      <CardContent className="flex flex-col gap-3 px-4 sm:px-5">
        {total === 0 ? (
          <p className="m-0 text-sm text-muted-foreground">{t('sp.maint.none')}</p>
        ) : (
          <>
            {showActive && (
              <section aria-label={t('sp.maint.active')} className="flex min-w-0 flex-col gap-2">
                <h4 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('sp.maint.active')}</h4>
                {active.length > 0 && <ul className="m-0 flex list-none flex-col divide-y p-0">{active.map((w) => <WindowItem key={`a-${w.id}`} w={w} />)}</ul>}
                <HiddenLine count={activeHidden} text={t('sp.maint.hidden', activeHidden)} slot="sp-maint-active-hidden" />
              </section>
            )}
            {showActive && showUpcoming && <Separator />}
            {showUpcoming && (
              <section aria-label={t('sp.maint.upcoming')} className="flex min-w-0 flex-col gap-2">
                <h4 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{t('sp.maint.upcoming')}</h4>
                {upcoming.length > 0 && <ul className="m-0 flex list-none flex-col divide-y p-0">{upcoming.map((w) => <WindowItem key={`u-${w.id}`} w={w} />)}</ul>}
                <HiddenLine count={upcomingHidden} text={t('sp.maint.hidden', upcomingHidden)} slot="sp-maint-upcoming-hidden" />
              </section>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}

// ── Lejant ────────────────────────────────────────────────────────────────────────────────────────────────────────

export function Legend() {
  const t = useT()
  return (
    <Card data-slot="sp-legend" className="gap-3 py-4">
      <SectionHeader Icon={Info} title={t('sp.legend.title')} />
      <CardContent className="flex flex-col gap-3 px-4 sm:px-5">
        {/* ≥ 640 px iki sütunlu ızgara (rozet sütunu en geniş rozet kadar); telefonda rozet üstte, açıklama altta */}
        <ul className="m-0 grid list-none grid-cols-1 gap-x-3 gap-y-2 p-0 sm:grid-cols-[max-content_minmax(0,1fr)]">
          {STATE_ORDER.map((k) => (
            <li key={k} data-slot="sp-legend-item" data-state={k} className="flex min-w-0 flex-col gap-1 sm:contents">
              <span className="sm:self-start"><StateBadge state={k} /></span>
              <span className="min-w-0 text-xs text-muted-foreground sm:pt-1">{t(LEGEND_KEY[k])}</span>
            </li>
          ))}
        </ul>
        <Separator />
        <p className="m-0 text-xs text-muted-foreground">{t('sp.legend.note')}</p>
      </CardContent>
    </Card>
  )
}
