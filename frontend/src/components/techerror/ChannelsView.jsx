import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { FileWarning, Plus, RefreshCw, LayoutTemplate, FileText, Users, CircleAlert, MailWarning, Radio } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import ChannelDialog from './ChannelDialog.jsx'
import { StatusBadge, periodLabel } from './TechErrorParts.jsx'

const ALL = '__all__'

/**
 * Kanallar görünümü: takım süzgeci, dönem özeti (önceki ay), kanal kartları. Kart eylemleri satırın sunucu
 * bayraklarından (`can_edit` / `can_delete`) çizilir; yazamayan kullanıcı yalnız açar.
 */
export default function ChannelsView({ team, onTeamChange, onOpenChannel, onOpenReport }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, error: null, status: null, data: null })
  const [dialog, setDialog] = useState(null)   // { mode: 'create' | 'edit', channel? }
  const [busy, setBusy] = useState(null)
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.overview(team)
      if (my !== seq.current) return
      if (res?.success && res.data) setState({ loading: false, error: null, status: null, data: res.data })
      else setState((s) => ({ ...s, loading: false, error: res?.error || t('te.loadError'), status: res?.status ?? null }))
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('te.loadError'), status: 0 }))
    }
  }, [team, t])
  useEffect(() => { load() }, [load])

  const data = state.data
  // Paylaşılan bağlantıdaki takım bu kullanıcıya görünmüyorsa (ya da silindiyse) süzgeç kalkar: süzgeç kutusu tek
  // takımda çizilmediği için kullanıcı boş sayfada mahsur kalıyordu (inceleme 2026-10-13)
  useEffect(() => {
    if (!team || !data?.teams) return
    if (!data.teams.some((tm) => String(tm.id) === String(team))) onTeamChange(null)
  }, [team, data, onTeamChange])
  const channels = useMemo(() => {
    const list = data?.channels || []
    // Pasif kanallar sona (sunucu sırası korunur)
    return [...list.filter((c) => c.active), ...list.filter((c) => !c.active)]
  }, [data])
  const period = data?.period
  const teams = useMemo(() => data?.teams || [], [data])
  const teamOptions = useMemo(() => [{ value: ALL, label: t('te.allTeams') },
    ...teams.map((tm) => ({ value: String(tm.id), label: tm.name || `#${tm.id}` }))], [teams, t])
  const noEmail = teams.filter((tm) => tm.can_edit && !tm.has_email && channels.some((c) => c.team_id === tm.id))

  const counts = useMemo(() => {
    const active = channels.filter((c) => c.active)
    let sent = 0, open = 0, missing = 0
    for (const c of active) {
      const st = c.period_report?.status
      if (st === 'sent') sent++
      else if (st) open++
      else missing++
    }
    return { channels: active.length, sent, open, missing }
  }, [channels])

  async function startReport(ch) {
    if (!period) return
    if (ch.period_report?.id) { onOpenReport(ch.period_report.id, ch.id); return }
    setBusy(`r${ch.id}`)
    try {
      const res = await api.techErrors.createReport(ch.id, period.year, period.month)
      if (res?.success && res.data?.id) onOpenReport(res.data.id, ch.id)
      else if (res?.code === 'REPORT_EXISTS' && res?.report_id) onOpenReport(res.report_id, ch.id)
      else toast.error(res?.error || t('te.createReportError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.createReportError'))
    } finally {
      setBusy(null)
    }
  }

  async function toggleActive(ch) {
    setBusy(`a${ch.id}`)
    try {
      const res = await api.techErrors.updateChannel(ch.id, { active: !ch.active })
      if (res?.success) {
        toast.success(ch.active ? t('te.channelPaused') : t('te.channelResumed'))
        load()
      } else toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setBusy(null)
    }
  }

  async function remove(ch) {
    const ok = await showConfirm({
      title: t('te.deleteChannelTitle'),
      message: t('te.deleteChannelBody', ch.name, ch.report_count || 0),
      variant: 'danger',
      confirmText: t('te.deleteChannelConfirm'),
      cancelText: t('te.cancel'),
    })
    if (!ok) return
    setBusy(`d${ch.id}`)
    try {
      const res = await api.techErrors.deleteChannel(ch.id)
      if (res?.success) {
        toast.success(t('te.channelDeleted', ch.name))
        load()
      } else toast.error(res?.error || t('te.deleteError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.deleteError'))
    } finally {
      setBusy(null)
    }
  }

  const header = (
    <PageHeader icon={FileWarning} title={t('te.title')} description={t('te.subtitle')}
      actions={(
        <>
          <Button type="button" variant="outline" onClick={load} disabled={state.loading} data-slot="te-refresh">
            <RefreshCw aria-hidden="true" className={cn(state.loading && 'animate-spin motion-reduce:animate-none')} />
            {t('te.refresh')}
          </Button>
          {data?.can_create && (
            <Button type="button" onClick={() => setDialog({ mode: 'create' })} data-slot="te-new-channel">
              <Plus aria-hidden="true" /> {t('te.newChannel')}
            </Button>
          )}
        </>
      )} />
  )

  if (state.loading && !data) {
    return (
      <div className="flex flex-col gap-4">
        {header}
        <div role="status" aria-live="polite" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          <span className="sr-only">{t('te.loading')}</span>
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-44 w-full rounded-xl motion-reduce:animate-none" />)}
        </div>
      </div>
    )
  }
  if (state.error && !data) {
    return (
      <div className="flex flex-col gap-4">
        {header}
        <StatusBlock tone={state.status === 403 ? 'neutral' : 'danger'} icon={CircleAlert}
          title={state.status === 403 ? t('te.forbiddenTitle') : t('te.loadErrorTitle')}
          description={state.status === 403 ? t('te.forbiddenBody') : state.error}
          actions={state.status === 403 ? null : <Button type="button" variant="outline" onClick={load}>{t('te.retry')}</Button>} />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-slot="te-channels">
      {header}

      <div className="flex flex-wrap items-end gap-3">
        {teams.length > 1 && (
          <div className="flex min-w-0 flex-col gap-1.5 sm:w-80">
            <span id="te-team-filter" className="text-sm font-medium">{t('te.teamFilter')}</span>
            <SearchableSelect ariaLabelledBy="te-team-filter" value={team || ALL} options={teamOptions} wrapLabels
              onChange={(v) => onTeamChange(v === ALL ? null : String(v))} />
          </div>
        )}
        {period && (
          <p className="text-sm text-muted-foreground" data-slot="te-period">
            {t('te.periodNote', periodLabel(period, t))}
          </p>
        )}
      </div>

      {state.error && (
        <AlertBanner tone="danger" title={t('te.refreshFailed')}>{state.error}</AlertBanner>
      )}

      {noEmail.length > 0 && (
        <AlertBanner tone="warning" icon={MailWarning} title={t('te.noEmailTitle')}>
          {t('te.noEmailBody', noEmail.map((tm) => tm.name).join(', '))}
        </AlertBanner>
      )}

      {channels.length > 0 && (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4" data-slot="te-kpis">
          <Kpi label={t('te.kpi.channels')} value={counts.channels} />
          <Kpi label={t('te.kpi.sent', periodLabel(period, t))} value={counts.sent} tone={counts.sent && counts.sent === counts.channels ? 'success' : null} />
          <Kpi label={t('te.kpi.open')} value={counts.open} tone={counts.open ? 'warning' : null} />
          <Kpi label={t('te.kpi.missing')} value={counts.missing} tone={counts.missing ? 'warning' : null} />
        </div>
      )}

      {channels.length === 0 ? (
        <StatusBlock tone="neutral" icon={Radio} title={t('te.emptyTitle')}
          description={data?.can_create ? t('te.emptyBodyCreate') : t('te.emptyBody')}
          actions={data?.can_create ? (
            <Button type="button" onClick={() => setDialog({ mode: 'create' })}><Plus aria-hidden="true" /> {t('te.newChannel')}</Button>
          ) : null} />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3" data-slot="te-channel-list">
          {channels.map((ch) => (
            <ChannelCard key={ch.id} ch={ch} period={period} busy={busy}
              onOpen={() => onOpenChannel(ch.id)}
              onTemplate={() => onOpenChannel(ch.id, 'template')}
              onReport={() => startReport(ch)}
              onEdit={() => setDialog({ mode: 'edit', channel: ch })}
              onToggle={() => toggleActive(ch)}
              onDelete={() => remove(ch)} />
          ))}
        </div>
      )}

      {dialog && (
        <ChannelDialog mode={dialog.mode} channel={dialog.channel} teams={teams} channels={data?.channels || []}
          defaultTeam={team}
          onClose={() => setDialog(null)}
          onSaved={(saved) => {
            setDialog(null)
            if (dialog.mode === 'create' && saved?.id) onOpenChannel(saved.id, 'template')
            else load()
          }} />
      )}
    </div>
  )
}

function Kpi({ label, value, tone = null }) {
  return (
    <Card className="gap-1 py-3" data-slot="te-kpi">
      <CardContent className="px-4">
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{label}</p>
        <p className={cn('text-2xl font-semibold tabular-nums',
          tone === 'success' && 'text-success', tone === 'warning' && 'text-amber-700 dark:text-amber-300')}>{value}</p>
      </CardContent>
    </Card>
  )
}

function ChannelCard({ ch, period, busy, onOpen, onTemplate, onReport, onEdit, onToggle, onDelete }) {
  const t = useT()
  const pr = ch.period_report
  const items = []
  if (ch.can_edit) {
    items.push({ label: t('te.editChannel'), onClick: onEdit })
    items.push({ label: ch.active ? t('te.pauseChannel') : t('te.resumeChannel'), onClick: onToggle })
  }
  if (ch.can_delete) items.push({ label: t('te.deleteChannel'), onClick: onDelete, danger: true })
  const canStart = ch.can_edit && ch.active
  return (
    <Card data-slot="te-channel-card" data-channel={ch.id} data-active={ch.active ? 'true' : 'false'}
      className={cn('gap-3', !ch.active && 'border-dashed')}>
      <CardHeader className="gap-2">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 flex-col gap-1">
            <Button type="button" variant="link" onClick={onOpen} data-slot="te-channel-name"
              className="h-auto justify-start p-0 text-left text-base font-semibold leading-snug whitespace-normal text-foreground [overflow-wrap:anywhere] pointer-coarse:min-h-10">
              {ch.name}
            </Button>
            <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
              <Users aria-hidden="true" className="size-3.5 shrink-0" />
              <TeamBadge teamId={ch.team_id} teamName={ch.team_name} static wrap />
            </div>
          </div>
          {items.length > 0 && <KebabMenu items={items} label={t('te.channelActions')} rowLabel={ch.name} />}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          {!ch.active && <Badge variant="outline">{t('te.inactive')}</Badge>}
          {ch.approval_required && (
            <Badge variant="secondary" className="rounded-md overflow-visible whitespace-normal" data-slot="te-channel-approval">
              {t('te.approvalChip', ch.executive_count || 0)}
            </Badge>
          )}
          <StatusBadge status={pr?.status || null} sendState={pr?.last_send_status} prefix={periodLabel(period, t)} />
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 text-sm">
        {ch.description && <p className="text-muted-foreground [overflow-wrap:anywhere]">{ch.description}</p>}
        <p className="text-xs text-muted-foreground">
          {t('te.channelMeta', ch.report_count || 0, ch.section_count || 0, ch.slot_count || 0)}
        </p>
      </CardContent>
      <CardFooter className="mt-auto flex flex-wrap gap-2">
        {(pr?.id || canStart) && (
          <Button type="button" size="sm" onClick={onReport} disabled={busy === `r${ch.id}`} data-slot="te-period-report"
            className="pointer-coarse:min-h-10">
            <FileText aria-hidden="true" />
            {pr?.id ? t('te.openPeriodReport', periodLabel(period, t)) : t('te.startPeriodReport', periodLabel(period, t))}
          </Button>
        )}
        <Button type="button" size="sm" variant="outline" onClick={onTemplate} data-slot="te-open-template"
          className="pointer-coarse:min-h-10">
          <LayoutTemplate aria-hidden="true" /> {t('te.openTemplate')}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onOpen} className="pointer-coarse:min-h-10">
          {t('te.allReports')}
        </Button>
      </CardFooter>
    </Card>
  )
}
