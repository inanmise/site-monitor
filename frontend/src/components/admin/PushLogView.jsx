import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { RefreshCw, Download, Webhook, Eye, RotateCcw, CheckCircle, XCircle, MinusCircle, Clock, Ban, HelpCircle,
  ExternalLink, ShieldAlert } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { csvRows } from '../../utils/csv.js'
import ModalShell from '../ui/ModalShell.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import PushBreakdownPanel from './PushBreakdownPanel.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { LogHeader, RangeControl, KpiTile, LogCard, SortHead, FilterChip, KindBadge, TriggerBadge, LevelBadge, TypeBadge,
  ErrorClassBadge, MetaRow, SectionLabel, ChainList, PRE, MUTED_SM } from './LogViewParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ChartContainer, ChartTooltip, ChartTooltipContent, BarChart, Bar, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

/** Süzgeç seçicisi kabı (eski `.rn-toolbar > .ss-wrap`). */
const SELECT_BOX = 'w-full sm:w-auto sm:min-w-[150px] sm:max-w-[230px]'
/** Ana tablo hücresi. */
const TD = 'px-2 py-2 align-top'

/**
 * Webhook Push Gönderim Logu (2026-09-19, kullanıcı isteği: SMTP sayfasının push karşılığı) — Sistem Sağlığı →
 * Webhook Push kartının TAM SAYFA alt görünümü (`?tab=health&view=push`, süzgeçler `p_*`).
 * KPI · zaman çizelgesi · takım / alıcı / izleme / seviye kırılımı · hata sınıfları (HTTP kodu) · satır detayı
 * (mesaj, ham yanıt, aynı batch'in alıcıları) · CSV · yeniden kuyruğa alma (admin) · 60 sn otomatik yenileme.
 * Sunucu: /api/admin/push-log/*; kapsam sunucuda (takım + kendi satırları). Çizim: SMTP sayfasıyla ortak shadcn parçaları (LogViewParts).
 */
const RANGES = ['24h', '7d', '30d']
const STATUSES = ['SENT', 'FAILED', 'PENDING', 'BLOCKED', 'SKIPPED']
/** Push tetikleyici sözlüğü (UserPushService): mevcut `userpush.trigger.*` etiketleri; bilinmeyen ham gösterilir. */
const TRIGGERS = ['OPEN', 'ESCALATION', 'RE_ALERT', 'RESOLVE', 'RESEND', 'TEST', 'WEAK_ALGO', 'WEEKLY_REPORT']
export function triggerLabel(trigger, t) {
  if (!trigger) return '—'
  const k = `userpush.trigger.${trigger}`
  const v = t(k)
  return v === k ? trigger : v
}
const LEVELS = ['CRITICAL', 'HIGH', 'WARNING', 'INFO']
const ERROR_CLASSES = ['AUTH', 'NOT_FOUND', 'RATE', 'SERVER', 'CLIENT', 'CONFIG', 'TIMEOUT', 'CONNECT', 'OTHER']
const REFRESH_MS = 60_000
const STATUS_META = {
  SENT:    { Icon: CheckCircle, tone: 'success', key: 'health.statusSent' },
  FAILED:  { Icon: XCircle,     tone: 'danger',  key: 'health.statusFailed' },
  PENDING: { Icon: Clock,       tone: 'muted',   key: 'pl.statusPending' },
  BLOCKED: { Icon: Ban,         tone: 'danger',  key: 'pl.statusBlocked' },
  SKIPPED: { Icon: MinusCircle, tone: 'muted',   key: 'health.statusSkipped' },
  UNKNOWN: { Icon: HelpCircle,  tone: 'muted',   key: 'health.statusUnknown' },
}
const KPI_COLORS = { total: '#71717a', sent: '#059669', failed: '#dc2626', pending: '#d97706', skipped: '#a1a1aa', rate: '#2563eb', users: '#b45309' }

function toIso(d) { return d instanceof Date && !isNaN(d) ? d.toISOString().slice(0, 19) : null }
function fromIso(s) { if (!s) return null; const d = new Date(s + 'Z'); return isNaN(d) ? null : d }
function rangeFrom(range, now = new Date()) {
  const ms = range === '24h' ? 24 * 3600e3 : range === '30d' ? 30 * 86400e3 : 7 * 86400e3
  return new Date(now.getTime() - ms)
}

export function PushStatusBadge({ row, t, compact = false }) {
  const m = STATUS_META[row.kind] ?? STATUS_META.UNKNOWN
  const detail = [row.http_status ? `HTTP ${row.http_status}` : null, row.attempts > 1 ? t('pl.attempts', row.attempts) : null].filter(Boolean).join(' · ')
  return (
    <div className="flex flex-col items-start gap-0.5">
      <KindBadge tone={m.tone} icon={m.Icon} title={row.status || ''}>{t(m.key)}</KindBadge>
      {!compact && row.error && <div className="max-w-[260px] text-xs break-all text-destructive" title={row.error}>{row.error}</div>}
      {!compact && detail && <div className={MUTED_SM}>{detail}</div>}
    </div>
  )
}

function readInitial(initial) {
  const p = (k, fb = '') => initial?.[k] ?? readUrlParam('p_' + k, fb)
  const range = p('range', '') || (p('from') ? 'custom' : '7d')
  return {
    range,
    from: fromIso(p('from')) ?? (range === 'custom' ? rangeFrom('7d') : null),
    to: fromIso(p('to')),
    status: p('status'), trigger: p('trigger'), teamId: p('team'), errorClass: p('cls'), level: p('level'),
    username: p('user'), monitorType: p('mtype'), q: p('q'),
    sort: p('sort', 'at,desc'),
  }
}

export default function PushLogView({ onBack, initial }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canExecute } = usePermissions()
  const canRequeue = canExecute('system_health.actions')

  const [f, setF] = useState(() => readInitial(initial))
  const [qInput, setQInput] = useState(f.q)
  const [summary, setSummary] = useState(null)
  const [rows, setRows] = useState({ items: [], total: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [updatedAt, setUpdatedAt] = useState(null)
  const [detailId, setDetailId] = useState(() => readUrlInt('p_id', null))
  const [detail, setDetail] = useState(null)
  const [busy, setBusy] = useState(false)
  const reqRef = useRef(0)
  const tableRef = useRef(null)   // kırılım rakamı tıklanınca ana tabloya kaydır (2026-09-21)

  // Sayfalama standardı (2026-09-26): süzgeç değişince sayfa 1 (değer karşılaştırmalı), `p_page`/`p_ps` adresi kanca
  // okur/yazar (ps listeye karşı doğrulanır), toplam gelince `p_page=99` son sayfaya çekilir. API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'push-log', preset: 'panel', resetDeps: [f], url: { pageKey: 'p_page', sizeKey: 'p_ps' }, apiBase: 0 })
  const { apiPage, pageSize: size, setTotal } = sp
  const patch = useCallback((p) => { setF((prev) => ({ ...prev, ...p })) }, [])
  useEffect(() => { const id = setTimeout(() => { if (qInput !== f.q) patch({ q: qInput }) }, 300); return () => clearTimeout(id) }, [qInput, f.q, patch])

  useUrlQuerySync({
    p_range: f.range === '7d' ? null : f.range,
    p_from: f.range === 'custom' ? toIso(f.from) : null, p_to: f.range === 'custom' ? toIso(f.to) : null,
    p_status: f.status || null, p_trigger: f.trigger || null, p_team: f.teamId || null, p_cls: f.errorClass || null,
    p_level: f.level || null, p_user: f.username || null, p_mtype: f.monitorType || null, p_q: f.q || null,
    p_sort: f.sort === 'at,desc' ? null : f.sort,
    p_id: detailId || null,
  })

  const buildParams = useCallback(() => ({
    from: toIso(f.range === 'custom' ? f.from : rangeFrom(f.range)),
    to: f.range === 'custom' ? toIso(f.to) : null,
    status: f.status, trigger: f.trigger, teamId: f.teamId, errorClass: f.errorClass, level: f.level,
    username: f.username, monitorType: f.monitorType, q: f.q, sort: f.sort,
  }), [f])

  const load = useCallback(async (silent = false) => {
    const my = ++reqRef.current
    if (!silent) setLoading(true)
    const params = buildParams()
    try {
      const [s, r] = await Promise.all([api.admin.pushLog.summary(params), api.admin.pushLog.search({ ...params, page: apiPage, size })])
      if (my !== reqRef.current) return
      if (!s?.success || !r?.success) { setError(s?.error || r?.error || t('mon.loadError')); return }
      setSummary(s.data); setRows({ items: r.data || [], total: Number(r.total) || 0 }); setTotal(Number(r.total) || 0); setError(null); setUpdatedAt(new Date())
    } catch (e) { if (my === reqRef.current) setError(String(e?.message || e)) }
    finally { if (my === reqRef.current) setLoading(false) }
  }, [buildParams, apiPage, size, t, setTotal])
  useEffect(() => { load() }, [load])
  useVisibleInterval(() => load(true), REFRESH_MS, false)

  useEffect(() => {
    if (!detailId) { setDetail(null); return }
    let alive = true
    api.admin.pushLog.detail(detailId)
      .then((r) => { if (!alive) return; if (r?.success) setDetail(r.data); else { toast.error(r?.error || t('mon.loadError')); setDetailId(null) } })
      .catch((e) => { if (alive) { toast.error(String(e?.message || e)); setDetailId(null) } })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detailId])

  const kpi = summary?.kpi || {}
  const teams = useMemo(() => summary?.teams || [], [summary])
  const teamOptions = useMemo(() => [{ value: '', label: t('sml.allTeams') },
    ...teams.filter((x) => x.team_id != null).map((x) => ({ value: String(x.team_id), label: x.team_name || `#${x.team_id}` }))], [teams, t])
  const activeCount = [f.status, f.trigger, f.teamId, f.errorClass, f.level, f.username, f.monitorType, f.q].filter(Boolean).length + (f.range !== '7d' ? 1 : 0)
  // Sözlük + özette görülen bilinmeyen tetikleyiciler (yeni tür eklenirse süzgeçten düşmesin)
  const triggerOptions = useMemo(() => [...new Set([...TRIGGERS, ...(summary?.triggers || []).map((x) => x.trigger).filter((x) => x && x !== '-')])], [summary])
  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): uç boşken (derin bağlantıda yalnız p_from) her
  // çizimde — canlı yenileme dâhil — yeni bir `new Date()` geçmek seçicinin taslağını sıfırlıyordu.
  const customOpen = f.range === 'custom'
  const pickerRange = useMemo(() => (customOpen ? { from: f.from || rangeFrom('7d'), to: f.to || new Date() } : null),
    [customOpen, f.from, f.to])

  function clearAll() { setQInput(''); setF({ range: '7d', from: null, to: null, status: '', trigger: '', teamId: '', errorClass: '', level: '', username: '', monitorType: '', q: '', sort: 'at,desc' }); sp.reset() }
  function toggleStatus(s) { patch({ status: f.status === s ? '' : s }) }
  function setSort(field) {
    const [cur, dir] = f.sort.split(',')
    const next = cur === field ? (dir === 'asc' ? 'desc' : 'asc') : (field === 'at' ? 'desc' : 'asc')
    patch({ sort: `${field},${next}` })
  }
  function onBucketClick(b) {
    if (!b?.bucket) return
    const start = fromIso(b.bucket.length === 10 ? b.bucket + 'T00:00:00' : b.bucket)
    if (!start) return
    const end = new Date(start.getTime() + (summary?.granularity === 'hour' ? 3600e3 : 86400e3) - 1000)
    patch({ range: 'custom', from: start, to: end })
  }

  async function exportCsv() {
    setBusy(true)
    try {
      const r = await api.admin.pushLog.export(buildParams())
      if (!r?.success) { toast.error(r?.error || t('mon.loadError')); return }
      const head = [t('health.smtpLogDate'), t('sml.colTeam'), t('pl.colUser'), t('pl.colUsername'), t('pl.colMonitor'), t('pl.colLevel'), t('health.emailDetailTrigger'),
        t('health.smtpLogStatus'), 'HTTP', t('pl.colAttempts'), t('sml.colErrorClass'), t('sml.colError'), t('pl.colNotificationId'), 'batch']
      const body = (r.data || []).map((x) => [formatDate(x.at), x.team_name || '', x.display_name || '', x.username || '', [x.monitor_type, x.monitor_name].filter(Boolean).join(' '), x.alert_level || '',
        triggerLabel(x.trigger, t), t((STATUS_META[x.kind] ?? STATUS_META.UNKNOWN).key), x.http_status ?? '', x.attempts ?? '', x.error_class ? t(`pl.cls.${x.error_class}`) : '', x.error || '', x.notification_id || '', x.batch_id || ''])
      const csv = '﻿' + csvRows([head, ...body])
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
      const el = document.createElement('a'); el.href = url; el.download = `webhook-push-logu-${new Date().toISOString().slice(0, 10)}.csv`; document.body.appendChild(el); el.click(); el.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      toast.success(r.capped ? t('sml.exportCapped', r.count) : t('sml.exported', r.count))
    } catch (e) { toast.error(String(e?.message || e)) } finally { setBusy(false) }
  }

  async function requeue(row) {
    if (!await showConfirm({ title: t('pl.requeueTitle'), message: t('pl.requeueConfirm', row.display_name || row.username || '', row.monitor_name || ''), confirmText: t('pl.requeue'), cancelText: t('app.cancel'), variant: 'warning' })) return
    setBusy(true)
    try {
      const r = await api.admin.pushLog.requeue(row.id)
      if (r?.success) { toast.success(t('pl.requeued')); load(true); if (detailId === row.id) setDetailId(null) }
      else toast.error(t(`pl.requeueReason.${r?.error}`) === `pl.requeueReason.${r?.error}` ? (r?.error || t('mon.loadError')) : t(`pl.requeueReason.${r?.error}`))
    } catch (e) { toast.error(String(e?.message || e)) } finally { setBusy(false) }
  }

  const windowText = f.range === 'custom' ? `${f.from ? formatDate(toIso(f.from)) : '…'} → ${f.to ? formatDate(toIso(f.to)) : t('sml.now')}` : t(`sml.range.${f.range}`)
  const retryable = (x) => x.kind === 'FAILED' || x.kind === 'BLOCKED'
  /** Kırılım rakamı → süzgeç (boyut + durum) + tabloya kaydırma. İzleme boyutu metin aramasıyla süzülür (q). */
  const applyBreakdown = (p) => {
    if ('q' in p) setQInput(p.q || '')
    patch(p)
    setTimeout(() => { try { tableRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }) } catch { /* jsdom */ } }, 50)
  }
  const chartConfig = {
    sent: { label: t('health.statusSent'), color: KPI_COLORS.sent },
    failed: { label: t('health.statusFailed'), color: KPI_COLORS.failed },
    pending: { label: t('pl.statusPending'), color: KPI_COLORS.pending },
    skipped: { label: t('health.statusSkipped'), color: KPI_COLORS.skipped },
  }
  const pickRange = (r) => (r === 'custom'
    ? patch({ range: 'custom', from: f.from || rangeFrom('7d'), to: f.to || new Date() })
    : patch({ range: r, from: null, to: null }))

  return (
    <div className="flex flex-col gap-3.5">
      <LogHeader icon={Webhook} title={t('pl.title')} backLabel={t('sml.back')} onBack={onBack}
        subtitle={<>{windowText} · {t('sml.autoRefresh')}{updatedAt ? ` · ${t('sml.updatedAt', updatedAt.toLocaleTimeString())}` : ''}</>}
        actions={<>
          <Button type="button" variant="secondary" onClick={() => load()} disabled={loading} aria-busy={loading || undefined}>
            <RefreshCw size={14} className={cn(loading && 'animate-spin motion-reduce:animate-none')} /> {t('sml.refresh')}
          </Button>
          <Button type="button" variant="secondary" onClick={exportCsv} disabled={busy || rows.total === 0}><Download size={14} /> {t('sml.exportCsv')}</Button>
        </>} />

      {error && <AlertBanner tone="danger" title={t('mon.loadError')}>{error}</AlertBanner>}

      <Card className="gap-2.5 px-3.5 py-3 shadow-none">
        <RangeControl value={f.range} onChange={pickRange} ranges={RANGES} label={t('sml.rangeLabel')} t={t} />
        {pickerRange && <DateTimeRangePicker from={pickerRange.from} to={pickerRange.to} onApply={(a, b) => patch({ range: 'custom', from: a, to: b })} />}
        <div className="flex flex-wrap items-center gap-2">
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.status} onChange={(v) => patch({ status: v })} ariaLabel={t('health.smtpLogStatus')}
              options={[{ value: '', label: t('health.smtpFilterStatusAll') }, ...STATUSES.map((s) => ({ value: s, label: t(STATUS_META[s].key) }))]} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.trigger} onChange={(v) => patch({ trigger: v })} ariaLabel={t('health.emailDetailTrigger')}
              options={[{ value: '', label: t('sml.allTriggers') }, ...triggerOptions.map((x) => ({ value: x, label: triggerLabel(x, t) }))]} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.teamId} onChange={(v) => patch({ teamId: v })} ariaLabel={t('sml.colTeam')} options={teamOptions} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.level} onChange={(v) => patch({ level: v })} ariaLabel={t('pl.colLevel')}
              options={[{ value: '', label: t('pl.allLevels') }, ...LEVELS.map((l) => ({ value: l, label: l }))]} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.errorClass} onChange={(v) => patch({ errorClass: v })} ariaLabel={t('sml.colErrorClass')}
              options={[{ value: '', label: t('sml.allClasses') }, ...ERROR_CLASSES.map((c) => ({ value: c, label: t(`pl.cls.${c}`) }))]} />
          </span>
          <ToolbarSearch value={qInput} onChange={setQInput} placeholder={t('pl.searchPlaceholder')}
            ariaLabel={t('pl.searchPlaceholder')} clearLabel={t('app.clearFilter')} className="w-full max-w-none sm:w-auto sm:max-w-[360px] sm:min-w-[200px] sm:flex-[1_1_220px]" />
          {(f.username || f.monitorType) && (
            <span className="inline-flex flex-wrap gap-1.5">
              {f.username && <FilterChip onClick={() => patch({ username: '' })}>{t('pl.colUser')}: {f.username}</FilterChip>}
              {f.monitorType && <FilterChip onClick={() => patch({ monitorType: '' })}>{t('pl.colMonitor')}: {f.monitorType}</FilterChip>}
            </span>
          )}
          {activeCount > 0 && <Button type="button" variant="secondary" size="sm" onClick={clearAll}>{t('app.clearFilters')} ({activeCount})</Button>}
          <span className="text-[0.82em] whitespace-nowrap text-muted-foreground">{t('sml.count', rows.total)}</span>
        </div>
      </Card>

      <div className="grid grid-cols-[repeat(auto-fit,minmax(130px,1fr))] gap-2.5">
        <KpiTile color={KPI_COLORS.total} value={kpi.total ?? '—'} label={t('sml.kpiTotal')} active={!f.status} onClick={() => patch({ status: '' })} />
        <KpiTile color={KPI_COLORS.sent} value={kpi.sent ?? '—'} label={t('health.statusSent')} active={f.status === 'SENT'} onClick={() => toggleStatus('SENT')} />
        <KpiTile color={KPI_COLORS.failed} value={kpi.failed ?? '—'} label={t('health.statusFailed')} active={f.status === 'FAILED'} onClick={() => toggleStatus('FAILED')} />
        <KpiTile color={KPI_COLORS.pending} value={kpi.pending ?? '—'} label={t('pl.statusPending')} active={f.status === 'PENDING'} onClick={() => toggleStatus('PENDING')} />
        <KpiTile color={KPI_COLORS.failed} value={kpi.blocked ?? '—'} label={t('pl.statusBlocked')} active={f.status === 'BLOCKED'} onClick={() => toggleStatus('BLOCKED')} />
        <KpiTile color={KPI_COLORS.skipped} value={kpi.skipped ?? '—'} label={t('health.statusSkipped')} active={f.status === 'SKIPPED'} onClick={() => toggleStatus('SKIPPED')} />
        <KpiTile color={KPI_COLORS.rate} value={kpi.success_rate == null ? '—' : `%${kpi.success_rate}`} label={t('sml.kpiRate')} />
        <KpiTile color={KPI_COLORS.users} value={kpi.failed_users ?? '—'} label={t('pl.kpiFailedUsers')} />
        <KpiTile color={KPI_COLORS.sent} small value={kpi.last_sent_at ? formatDate(kpi.last_sent_at) : '—'} label={t('sml.kpiLastSent')} />
      </div>

      <LogCard title={t('sml.timeline')} hint={<>{summary?.granularity === 'hour' ? t('sml.hourly') : t('sml.daily')} · {t('sml.timelineHint')}</>}>
        <ChartContainer config={chartConfig} className="aspect-auto h-[180px] w-full">
          <BarChart data={summary?.timeline || []} margin={{ top: 6, right: 10, bottom: 0, left: -10 }} onClick={(e) => { const p = e?.activePayload?.[0]?.payload; if (p) onBucketClick(p) }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={(b) => summary?.granularity === 'hour' ? String(b).slice(11, 16) : String(b).slice(5, 10)} tick={{ fontSize: 11 }} minTickGap={18} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
            <ChartTooltip content={<ChartTooltipContent labelFormatter={(_, p) => { const b = p?.[0]?.payload?.bucket; return b ? formatDate(String(b).length === 10 ? b + 'T00:00:00' : b) : '' }} />} />
            <Bar dataKey="sent" name="sent" stackId="s" fill={KPI_COLORS.sent} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="failed" name="failed" stackId="s" fill={KPI_COLORS.failed} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="pending" name="pending" stackId="s" fill={KPI_COLORS.pending} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="skipped" name="skipped" stackId="s" fill={KPI_COLORS.skipped} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} radius={[3, 3, 0, 0]} />
          </BarChart>
        </ChartContainer>
      </LogCard>

      <div className="grid grid-cols-1 gap-3">
        <PushBreakdownPanel title={t('sml.byTeam')} rows={teams} keyOf={(x) => x.team_id ?? '-'} status={f.status} onFilter={applyBreakdown}
          label={(x) => x.team_id != null ? <TeamBadge teamId={x.team_id} teamName={x.team_name || `#${x.team_id}`} /> : <span className="text-muted-foreground">{t('sml.noTeam')}</span>}
          dim={(x) => x.team_id != null ? { teamId: String(x.team_id) } : null} isDimActive={(x) => x.team_id != null && String(x.team_id) === String(f.teamId)} />
        <PushBreakdownPanel title={t('pl.topUsers')} rows={summary?.top_users || []} keyOf={(x) => x.username} status={f.status} onFilter={applyBreakdown}
          label={(x) => <UserBadge username={x.username} displayName={x.display_name} inline size="sm" />}
          dim={(x) => ({ username: x.username })} isDimActive={(x) => !!x.username && f.username === x.username} />
        <PushBreakdownPanel title={t('pl.topMonitors')} rows={summary?.top_monitors || []} keyOf={(x) => `${x.monitor_type}:${x.monitor_name}`} status={f.status} onFilter={applyBreakdown}
          label={(x) => <span className="flex flex-wrap items-center gap-2"><TypeBadge>{x.monitor_type}</TypeBadge><span className="truncate" title={x.monitor_name || ''}>{x.monitor_name}</span></span>}
          dim={(x) => ({ q: x.monitor_name || '' })} isDimActive={(x) => !!x.monitor_name && f.q === x.monitor_name} />
        <PushBreakdownPanel title={t('pl.byLevel')} rows={summary?.levels || []} keyOf={(x) => x.level ?? '-'} status={f.status} onFilter={applyBreakdown}
          label={(x) => x.level ? <LevelBadge level={x.level} /> : <span className="text-muted-foreground">—</span>}
          dim={(x) => x.level ? { level: x.level } : null} isDimActive={(x) => !!x.level && f.level === x.level} />
      </div>

      <div ref={tableRef}>
        <Card className="gap-0 overflow-hidden p-0 shadow-none">
          {loading && !summary ? <LoadingBlock label={t('sys.loading')} fullWidth /> : rows.total === 0 ? (
            <StatusBlock tone="neutral" icon={Webhook} title={t('health.smtpLogNoMatch')} description={activeCount > 0 ? t('sml.noMatchHint') : t('pl.noRowsHint')} />
          ) : (
            <Table data-testid="sml-table" className="text-[0.85em]">
              <TableHeader className="bg-muted/50">
                <TableRow>
                  <SortHead label={t('health.smtpLogDate')} field="at" sort={f.sort} onSort={setSort} />
                  <SortHead label={t('sml.colTeam')} field="team" sort={f.sort} onSort={setSort} className="hidden md:table-cell" />
                  <SortHead label={t('pl.colUser')} field="user" sort={f.sort} onSort={setSort} />
                  <SortHead label={t('pl.colMonitor')} field="monitor" sort={f.sort} onSort={setSort} />
                  <SortHead label={t('pl.colLevel')} field="level" sort={f.sort} onSort={setSort} className="hidden md:table-cell" />
                  <SortHead label={t('health.emailDetailTrigger')} field="trigger" sort={f.sort} onSort={setSort} className="hidden lg:table-cell" />
                  <SortHead label={t('health.smtpLogStatus')} field="status" sort={f.sort} onSort={setSort} />
                  <TableHead className="px-2"><span className="sr-only">{t('audit.colDetail')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.items.map((row) => (
                  <TableRow key={row.id} tabIndex={0} className="cursor-pointer"
                    aria-label={t('a11y.openRow', formatDate(row.at))}
                    onClick={() => setDetailId(row.id)}
                    onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setDetailId(row.id) } }}>
                    <TableCell className={cn(TD, 'w-[140px] font-mono whitespace-nowrap text-muted-foreground')}>{formatDate(row.at)}</TableCell>
                    <TableCell className={cn(TD, 'hidden md:table-cell')}>{row.team_name ? <span className="inline-flex" data-row-stop="" onClick={(e) => e.stopPropagation()}><TeamBadge teamId={row.team_id} teamName={row.team_name} /></span> : <span className="text-muted-foreground">—</span>}</TableCell>
                    <TableCell className={TD}><span className="inline-flex" data-row-stop="" onClick={(e) => e.stopPropagation()}><UserBadge username={row.username} displayName={row.display_name} inline size="sm" /></span></TableCell>
                    <TableCell className={cn(TD, 'w-[34%] max-w-0 min-w-[160px] truncate')}><TypeBadge>{row.monitor_type || '—'}</TypeBadge> <span title={row.title || ''}>{row.monitor_name || row.title || '—'}</span></TableCell>
                    <TableCell className={cn(TD, 'hidden md:table-cell')}>{row.alert_level ? <LevelBadge level={row.alert_level} /> : '—'}</TableCell>
                    <TableCell className={cn(TD, 'hidden lg:table-cell')}><TriggerBadge trigger={row.trigger}>{triggerLabel(row.trigger, t)}</TriggerBadge></TableCell>
                    <TableCell className={TD}><PushStatusBadge row={row} t={t} />{row.error_class && <ErrorClassBadge>{t(`pl.cls.${row.error_class}`)}</ErrorClassBadge>}</TableCell>
                    <TableCell className={cn(TD, 'whitespace-nowrap')} onClick={(e) => e.stopPropagation()}>
                      {/* Adlar kaydı ayırır (izleme/başlık + zaman): yeniden kuyruğa alma YAN ETKİLİ ve
                          her satırda aynı adla duyuluyordu (2026-09-25, R15). İpucu kısa kalır. */}
                      <span className="inline-flex gap-1">
                        <SimpleTooltip content={t('pl.detail')}>
                          <Button type="button" variant="secondary" size="icon-sm"
                            aria-label={t('a11y.rowAction', `${row.monitor_name || row.title || '—'} · ${formatDate(row.at)}`, t('pl.detail'))}
                            onClick={() => setDetailId(row.id)}><Eye size={13} /></Button>
                        </SimpleTooltip>
                        {canRequeue && retryable(row) && (
                          <SimpleTooltip content={t('pl.requeue')}>
                            <Button type="button" variant="secondary" size="icon-sm" data-action="resend"
                              aria-label={t('a11y.rowAction', `${row.monitor_name || row.title || '—'} · ${formatDate(row.at)}`, t('pl.requeue'))}
                              disabled={busy} onClick={() => requeue(row)}><RotateCcw size={13} /></Button>
                          </SimpleTooltip>
                        )}
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {rows.total > 0 && (
            <div className="px-3 py-2">
              <PaginationBar {...sp.bar} />
            </div>
          )}
        </Card>
      </div>

      {detailId && (
        <ModalShell open onClose={() => setDetailId(null)} title={t('pl.detail')} icon={Webhook} size="xl" scrollBody
          footer={<>
            {detail?.alert_event_id && <Button type="button" variant="secondary" onClick={() => { setDetailId(null); navigateTo('alerthistory', { incident: detail.alert_event_id }) }}><ExternalLink size={13} /> {t('sml.openAlert')}</Button>}
            {canRequeue && detail && retryable(detail) && <Button type="button" disabled={busy} onClick={() => requeue(detail)}><RotateCcw size={13} /> {t('pl.requeue')}</Button>}
            <Button type="button" variant="secondary" onClick={() => setDetailId(null)}>{t('app.close')}</Button>
          </>}>
          {!detail ? <LoadingBlock label={t('sys.loading')} fullWidth /> : (
            <div className="flex flex-col">
              <div className="flex flex-col gap-2 border-b pb-4 border-border">
                <MetaRow label={t('pl.colUser')}><span><UserBadge username={detail.username} displayName={detail.display_name} inline size="sm" />{detail.team_name && <> <TeamBadge teamId={detail.team_id} teamName={detail.team_name} /></>}</span></MetaRow>
                <MetaRow label={t('pl.colMonitor')}><span><TypeBadge>{detail.monitor_type || '—'}</TypeBadge> {detail.monitor_name || '—'}{detail.alert_level && <> <LevelBadge level={detail.alert_level} /></>}</span></MetaRow>
                <MetaRow label={t('pl.colTitle')}><span className="font-semibold">{detail.title || '—'}</span></MetaRow>
                <MetaRow label={t('health.smtpLogDate')}><span className="font-mono">{formatDate(detail.created_at)}{detail.sent_at && <span className="text-muted-foreground"> → {formatDate(detail.sent_at)}</span>}</span></MetaRow>
                <MetaRow label={t('health.emailDetailTrigger')}><TriggerBadge trigger={detail.trigger}>{triggerLabel(detail.trigger, t)}</TriggerBadge><PushStatusBadge row={detail} t={t} compact /></MetaRow>
                {detail.kind !== 'SENT' && (
                  <MetaRow label={t('sml.colError')}>
                    <span className="flex flex-wrap items-center gap-2">{detail.error_class && <ErrorClassBadge>{t(`pl.cls.${detail.error_class}`)}</ErrorClassBadge>}<code className="rounded bg-muted px-1.5 py-0.5 text-[0.82em] break-all">{[detail.status, detail.http_status ? `HTTP ${detail.http_status}` : null, detail.error].filter(Boolean).join(' · ')}</code></span>
                  </MetaRow>
                )}
                {(detail.notification_id || detail.batch_id) && (
                  <MetaRow label={t('pl.colNotificationId')}><span className="font-mono text-xs">{detail.notification_id || '—'}{detail.batch_id && <span className="text-muted-foreground"> · batch {detail.batch_id}</span>}</span></MetaRow>
                )}
              </div>
              {(detail.batch || []).length > 1 && (
                <div className="mt-2 mb-1">
                  <SectionLabel>{t('pl.batchPeers', detail.batch.length)}</SectionLabel>
                  <ChainList items={detail.batch} currentId={detail.id} onPick={setDetailId} render={(c) => (
                    <>
                      <UserBadge username={c.username} displayName={c.display_name} inline size="sm" />
                      <PushStatusBadge row={c} t={t} compact />
                      {c.http_status && <span className={MUTED_SM}>HTTP {c.http_status}</span>}
                    </>
                  )} />
                </div>
              )}
              <SectionLabel>{t('pl.message')}</SectionLabel>
              <pre className={PRE}>{detail.message || t('health.emailDetailNoBody')}</pre>
              {detail.raw_response && (<>
                <SectionLabel>{t('pl.rawResponse')}</SectionLabel>
                <pre className={PRE}>{detail.raw_response}</pre>
              </>)}
            </div>
          )}
        </ModalShell>
      )}
      {!canRequeue && rows.items.some(retryable) && <div className="inline-flex items-center gap-1 text-xs text-muted-foreground"><ShieldAlert size={12} /> {t('pl.requeueAdminOnly')}</div>}
    </div>
  )
}
