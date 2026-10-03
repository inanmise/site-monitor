// Fırtına ayrıntı penceresi (2026-09-30): anlık görüntü, zaman çizelgesi, üyeler (kalıcı üyelik tablosu), bildirim özeti.
import { useEffect, useState } from 'react'
import { CloudLightning, ExternalLink, Mail, Send, Smartphone, Zap } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { usePagination } from '../../hooks/usePagination.js'
import { navigateTo } from '../../utils/navigate.js'
import { formatDuration, formatIncidentTime } from '../../utils/incidentMeta.js'
import ModalShell from '../ui/ModalShell.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { AlertLevelBadge } from '../admin/alerts/AlertBadges.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { JOIN_KEYS, LEAVE_KEYS, stormTimeline } from './stormModel.js'
import { ReasonBadge } from './StormTeamCards.jsx'

function Fact({ label, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="text-[11px] uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className="text-sm">{children ?? '—'}</span>
    </div>
  )
}

/** Üye satırı → alarm ayrıntısına gidiş (Alarm Geçmişi `alert` parametresi, çözülmüşse closed görünüm). */
function openAlert(m) {
  navigateTo('alerthistory', { alert: String(m.event_id), view: m.resolved ? 'closed' : 'open', q: m.domain || '' })
}

function MemberRow({ m, phone }) {
  const t = useT()
  const locale = useDateLocale()
  const join = m.join_kind ? t(JOIN_KEYS[m.join_kind] || 'sf.member.join.PEER') : '—'
  const leave = m.leave_kind ? t(LEAVE_KEYS[m.leave_kind] || 'sf.member.leave.RELEASED') : null
  const cells = {
    domain: (
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-1">
          {m.trigger && <Badge variant="secondary" data-slot="sf-trigger" className="gap-1"><Zap aria-hidden="true" className="size-3" />{t('sf.member.join.TRIGGER')}</Badge>}
          <span className="truncate font-semibold" title={m.domain || ''}>{m.domain || `#${m.event_id}`}</span>
        </div>
        <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
          <Badge variant="outline" className="text-[10px]">{m.alert_type}</Badge>
          <AlertLevelBadge level={m.alert_level} />
        </div>
      </div>
    ),
    state: <Badge variant="outline" data-slot="sf-member-state" data-resolved={m.resolved ? 'true' : 'false'}>{m.resolved ? t('sf.member.resolved') : t('sf.member.open')}</Badge>,
    joined: <span className="text-xs">{join}<br /><span className="text-muted-foreground">{formatIncidentTime(m.joined_at, locale)}</span></span>,
    announced: <span className="text-xs">{m.announced_at ? t('sf.member.announced') : t('sf.member.notAnnounced')}{m.announced_at && <><br /><span className="text-muted-foreground">{formatIncidentTime(m.announced_at, locale)}</span></>}</span>,
    left: <span className="text-xs">{leave || '—'}{m.left_at && <><br /><span className="text-muted-foreground">{formatIncidentTime(m.left_at, locale)}</span></>}</span>,
    action: (
      <Button type="button" size="icon-sm" variant="ghost" aria-label={t('sf.member.openAlert', m.domain || m.event_id)} title={t('sf.member.openAlert', m.domain || m.event_id)}
        className="pointer-coarse:size-10" onClick={() => openAlert(m)}><ExternalLink aria-hidden="true" /></Button>
    ),
  }
  if (phone) {
    return (
      <li>
        <Card data-slot="sf-member" data-event-id={m.event_id} className="gap-0 py-0 shadow-none">
          <CardContent className="flex flex-col gap-1.5 p-3">
            <div className="flex items-start gap-2">{cells.domain}<div className="ml-auto flex items-center gap-1">{cells.state}{cells.action}</div></div>
            <div className="grid grid-cols-2 gap-x-3 gap-y-1">
              <Fact label={t('sf.detail.col.joined')}>{cells.joined}</Fact>
              <Fact label={t('sf.detail.col.announced')}>{cells.announced}</Fact>
              <Fact label={t('sf.detail.col.left')}>{cells.left}</Fact>
            </div>
          </CardContent>
        </Card>
      </li>
    )
  }
  return (
    <TableRow data-slot="sf-member" data-event-id={m.event_id}>
      <TableCell className="max-w-[22rem] min-w-0">{cells.domain}</TableCell>
      <TableCell>{cells.state}</TableCell>
      <TableCell>{cells.joined}</TableCell>
      <TableCell className="hidden md:table-cell">{cells.announced}</TableCell>
      <TableCell className="hidden md:table-cell">{cells.left}</TableCell>
      <TableCell className="text-right">{cells.action}</TableCell>
    </TableRow>
  )
}

export default function StormDetailModal({ stormId, onClose }) {
  const t = useT()
  const locale = useDateLocale()
  const phone = useIsMobile()
  const [state, setState] = useState({ loading: true, error: null, data: null })

  useEffect(() => {
    let alive = true
    if (stormId == null) return undefined
    setState({ loading: true, error: null, data: null })
    api.monitoring.storm.detail(stormId).then((res) => {
      if (!alive) return
      if (res?.success && res.data && !Array.isArray(res.data)) setState({ loading: false, error: null, data: res.data })
      else setState({ loading: false, error: res?.error || t('sf.loadError'), data: null })
    }).catch((e) => { if (alive) setState({ loading: false, error: e?.message || t('sf.loadError'), data: null }) })
    return () => { alive = false }
  }, [stormId, t])

  const s = state.data
  const tl = stormTimeline(s, t)
  // Büyük fırtınada yüzlerce üye olabilir — sayfalı çizim (2026-10-01, performans)
  const pager = usePagination(s?.members || [], { listKey: 'storm-detail-members', preset: 'page', resetDeps: [stormId] })
  const n = s?.notifications || {}

  return (
    <ModalShell open={stormId != null} onClose={onClose} icon={CloudLightning} size="xl" scrollBody closeOnNavigate
      title={t('sf.detail.title', stormId ?? '')}
      headerExtra={s && <ReasonBadge storm={s} />}>
      {state.loading && <LoadingBlock label={t('sf.loading')} fullWidth />}
      {state.error && <StatusBlock tone="danger" icon={CloudLightning} title={t('sf.loadError')} description={state.error} />}
      {s && (
        <div className="flex flex-col gap-4" data-slot="sf-detail">
          {/* Anlık görüntü */}
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Fact label={t('sf.history.col.team')}>{s.team_id != null ? <TeamBadge teamId={s.team_id} teamName={s.team_name} size={12} static /> : <Badge variant="outline">{t('sf.legacy')}</Badge>}</Fact>
            <Fact label={t('sf.storm.since')}>{formatIncidentTime(s.created_at, locale)}</Fact>
            <Fact label={t('sf.storm.duration')}>{s.duration_ms != null ? formatDuration(s.duration_ms, t) : '—'}</Fact>
            <Fact label={t('sf.storm.resolvedAt')}>{s.resolved_at ? formatIncidentTime(s.resolved_at, locale) : t('sf.reason.OPEN')}</Fact>
            <Fact label={t('sf.detail.threshold')}>{s.threshold_effective != null ? t('sf.detail.thresholdText', s.threshold_effective, s.threshold_unit === 'PERCENT' ? `${s.threshold_value}%` : s.threshold_value ?? '—', s.window_minutes ?? '—') : '—'}</Fact>
            <Fact label={t('sf.detail.targets')}>{t('sf.detail.targetsText', s.targets_at_open ?? '—', s.peak_targets ?? '—')}</Fact>
            <Fact label={t('sf.storm.rootCause')}>{s.root_cause || '—'}</Fact>
            <Fact label={t('sf.detail.quiet')}>{s.quiet_minutes != null ? t('sf.min', s.quiet_minutes) : '—'}</Fact>
          </div>

          {/* Zaman çizelgesi */}
          <section aria-label={t('sf.detail.timeline')} className="flex flex-col gap-1">
            <h3 className="m-0 text-sm font-semibold">{t('sf.detail.timeline')}</h3>
            <ol className="m-0 flex list-none flex-col gap-1 border-l pl-3 p-0" data-slot="sf-timeline">
              {tl.map((e, i) => (
                <li key={i} data-kind={e.kind} className={cn('relative text-xs', e.future && 'text-muted-foreground')}>
                  <span aria-hidden="true" className={cn('absolute -left-[17px] top-1.5 size-2 rounded-full', e.kind === 'resolved' ? 'bg-emerald-500' : e.kind === 'sealed' ? 'bg-sky-500' : e.future ? 'border bg-background' : 'bg-primary')} />
                  <span className="text-muted-foreground tabular-nums">{formatIncidentTime(e.at, locale)}</span> · {e.text}
                </li>
              ))}
            </ol>
          </section>

          {/* Bildirim özeti */}
          <section aria-label={t('sf.detail.notifications')} className="flex flex-col gap-1">
            <h3 className="m-0 text-sm font-semibold">{t('sf.detail.notifications')}</h3>
            <div className="flex flex-wrap gap-1.5" data-slot="sf-notifications">
              <Badge variant="outline" className="gap-1"><Mail aria-hidden="true" className="size-3" />{t('sf.detail.n.initial', n.initial ?? 0)}</Badge>
              <Badge variant="outline" className="gap-1"><Mail aria-hidden="true" className="size-3" />{t('sf.detail.n.realert', n.realert ?? 0)}</Badge>
              <Badge variant="outline" className="gap-1"><Mail aria-hidden="true" className="size-3" />{t('sf.detail.n.resolve', n.resolve ?? 0)}</Badge>
              <Badge variant="outline" className="gap-1"><CloudLightning aria-hidden="true" className="size-3" />{t('sf.detail.n.suppressed', n.suppressed ?? 0)}</Badge>
              <Badge variant="outline" className="gap-1"><Send aria-hidden="true" className="size-3" />{t('sf.detail.n.push', n.push ?? 0)}</Badge>
              {/* 2026-10-03: push fırtınaya devredilmeyince üyelerin push'u bireysel gider — o sayı ayrı (eski sunucuda alan yok) */}
              {n.push_members != null && (
                <Badge variant="outline" className="gap-1" data-slot="sf-push-members"><Smartphone aria-hidden="true" className="size-3" />{t('sf.detail.n.pushMembers', n.push_members)}</Badge>
              )}
              {n.last_mail_at && <span className="self-center text-xs text-muted-foreground">{t('sf.detail.n.lastMail')}: {formatIncidentTime(n.last_mail_at, locale)}</span>}
            </div>
            <p className="m-0 text-xs text-muted-foreground">{t('sf.detail.notifHint')}</p>
          </section>

          {/* Üyeler */}
          <section aria-label={t('sf.detail.members')} className="flex flex-col gap-1">
            <h3 className="m-0 text-sm font-semibold">{t('sf.detail.members')} <span className="font-normal text-muted-foreground">({t('sf.detail.membersCount', s.members_total ?? 0, s.members_recovered ?? 0, s.members_down ?? 0)})</span></h3>
            {(s.members || []).length === 0 ? (
              <p className="m-0 text-sm text-muted-foreground">{t('sf.detail.noMembers')}</p>
            ) : phone ? (
              <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="sf-members">
                {pager.pageItems.map((m) => <MemberRow key={m.event_id} m={m} phone />)}
              </ul>
            ) : (
              <div className="overflow-x-auto rounded-lg border">
                <Table data-slot="sf-members">
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('sf.detail.col.alert')}</TableHead>
                      <TableHead>{t('sf.detail.col.state')}</TableHead>
                      <TableHead>{t('sf.detail.col.joined')}</TableHead>
                      <TableHead className="hidden md:table-cell">{t('sf.detail.col.announced')}</TableHead>
                      <TableHead className="hidden md:table-cell">{t('sf.detail.col.left')}</TableHead>
                      <TableHead className="text-right">{t('sf.detail.col.actions')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>{pager.pageItems.map((m) => <MemberRow key={m.event_id} m={m} />)}</TableBody>
                </Table>
              </div>
            )}
            {(s.members || []).length > 0 && <PaginationBar {...pager} />}
          </section>
        </div>
      )}
    </ModalShell>
  )
}
