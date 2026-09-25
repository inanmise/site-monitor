import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip, ResponsiveContainer } from 'recharts'
import { ArrowLeft, RefreshCw, Download, Webhook, Search, X, Eye, RotateCcw, CheckCircle, XCircle, MinusCircle, Clock, Ban, HelpCircle,
  ChevronUp, ChevronDown, ExternalLink, ShieldAlert } from 'lucide-react'
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
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import PushBreakdownPanel from './PushBreakdownPanel.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Webhook Push Gönderim Logu (2026-09-19, kullanıcı isteği: SMTP sayfasının push karşılığı) — Sistem Sağlığı →
 * Webhook Push kartının TAM SAYFA alt görünümü (`?tab=health&view=push`, süzgeçler `p_*`).
 * KPI · zaman çizelgesi · takım / alıcı / izleme / seviye kırılımı · hata sınıfları (HTTP kodu) · satır detayı
 * (mesaj, ham yanıt, aynı batch'in alıcıları) · CSV · yeniden kuyruğa alma (admin) · 60 sn otomatik yenileme.
 * Sunucu: /api/admin/push-log/*; kapsam sunucuda (takım + kendi satırları). CSS: SMTP sayfasının `.sml-*` sözlüğü.
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
  SENT:    { Icon: CheckCircle, cls: 'smtp-kind-sent',    key: 'health.statusSent' },
  FAILED:  { Icon: XCircle,     cls: 'smtp-kind-failed',  key: 'health.statusFailed' },
  PENDING: { Icon: Clock,       cls: 'smtp-kind-skipped', key: 'pl.statusPending' },
  BLOCKED: { Icon: Ban,         cls: 'smtp-kind-failed',  key: 'pl.statusBlocked' },
  SKIPPED: { Icon: MinusCircle, cls: 'smtp-kind-skipped', key: 'health.statusSkipped' },
  UNKNOWN: { Icon: HelpCircle,  cls: 'smtp-kind-skipped', key: 'health.statusUnknown' },
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
    <div className="sml-status">
      <span className={`smtp-kind-badge ${m.cls}`} title={row.status || ''}><m.Icon size={11} />{t(m.key)}</span>
      {!compact && row.error && <div className="smtp-log-error sys-err-text sys-small" title={row.error}>{row.error}</div>}
      {!compact && detail && <div className="sys-muted sys-small">{detail}</div>}
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
  const [page, setPage] = useState(() => readUrlInt('p_page', 1))
  const [size, setSize] = useState(() => readUrlInt('p_ps', 25))
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

  const patch = useCallback((p) => { setF((prev) => ({ ...prev, ...p })); setPage(1) }, [])
  useEffect(() => { const id = setTimeout(() => { if (qInput !== f.q) patch({ q: qInput }) }, 300); return () => clearTimeout(id) }, [qInput, f.q, patch])

  useUrlQuerySync({
    p_range: f.range === '7d' ? null : f.range,
    p_from: f.range === 'custom' ? toIso(f.from) : null, p_to: f.range === 'custom' ? toIso(f.to) : null,
    p_status: f.status || null, p_trigger: f.trigger || null, p_team: f.teamId || null, p_cls: f.errorClass || null,
    p_level: f.level || null, p_user: f.username || null, p_mtype: f.monitorType || null, p_q: f.q || null,
    p_sort: f.sort === 'at,desc' ? null : f.sort,
    p_page: page > 1 ? page : null, p_ps: size !== 25 ? size : null, p_id: detailId || null,
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
      const [s, r] = await Promise.all([api.admin.pushLog.summary(params), api.admin.pushLog.search({ ...params, page: page - 1, size })])
      if (my !== reqRef.current) return
      if (!s?.success || !r?.success) { setError(s?.error || r?.error || t('mon.loadError')); return }
      setSummary(s.data); setRows({ items: r.data || [], total: Number(r.total) || 0 }); setError(null); setUpdatedAt(new Date())
    } catch (e) { if (my === reqRef.current) setError(String(e?.message || e)) }
    finally { if (my === reqRef.current) setLoading(false) }
  }, [buildParams, page, size, t])
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

  function clearAll() { setQInput(''); setF({ range: '7d', from: null, to: null, status: '', trigger: '', teamId: '', errorClass: '', level: '', username: '', monitorType: '', q: '', sort: 'at,desc' }); setPage(1) }
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

  const totalPages = Math.max(1, Math.ceil(rows.total / size))
  const sortIcon = (field) => { const [cur, dir] = f.sort.split(','); if (cur !== field) return null; return dir === 'asc' ? <ChevronUp size={12} /> : <ChevronDown size={12} /> }
  const windowText = f.range === 'custom' ? `${f.from ? formatDate(toIso(f.from)) : '…'} → ${f.to ? formatDate(toIso(f.to)) : t('sml.now')}` : t(`sml.range.${f.range}`)
  const retryable = (x) => x.kind === 'FAILED' || x.kind === 'BLOCKED'
  /** Kırılım rakamı → süzgeç (boyut + durum) + tabloya kaydırma. İzleme boyutu metin aramasıyla süzülür (q). */
  const applyBreakdown = (p) => {
    if ('q' in p) setQInput(p.q || '')
    patch(p)
    setTimeout(() => { try { tableRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }) } catch { /* jsdom */ } }, 50)
  }

  return (
    <div className="sml">
      <div className="sml-head">
        <Button type="button" variant="secondary" className="sml-back" onClick={onBack}><ArrowLeft size={14} /> {t('sml.back')}</Button>
        <div className="sml-title">
          <Webhook size={18} aria-hidden="true" />
          <div>
            <h3>{t('pl.title')}</h3>
            <div className="sys-muted sys-small">{windowText} · {t('sml.autoRefresh')}{updatedAt ? ` · ${t('sml.updatedAt', updatedAt.toLocaleTimeString())}` : ''}</div>
          </div>
        </div>
        <div className="sml-head-actions">
          <Button type="button" variant="secondary" onClick={() => load()} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> {t('sml.refresh')}</Button>
          <Button type="button" variant="secondary" onClick={exportCsv} disabled={busy || rows.total === 0}><Download size={14} /> {t('sml.exportCsv')}</Button>
        </div>
      </div>

      {error && <AlertBanner tone="danger" title={t('mon.loadError')}>{error}</AlertBanner>}

      <div className="sml-filters">
        <div className="sml-ranges" role="group" aria-label={t('sml.rangeLabel')}>
          {RANGES.map((r) => <button key={r} type="button" className={`smtp-period-pill${f.range === r ? ' is-selected' : ''}`} onClick={() => patch({ range: r, from: null, to: null })}>{t(`sml.range.${r}`)}</button>)}
          <button type="button" className={`smtp-period-pill${f.range === 'custom' ? ' is-selected' : ''}`} onClick={() => patch({ range: 'custom', from: f.from || rangeFrom('7d'), to: f.to || new Date() })}>{t('sml.range.custom')}</button>
        </div>
        {f.range === 'custom' && <DateTimeRangePicker from={f.from || rangeFrom('7d')} to={f.to || new Date()} onApply={(a, b) => patch({ range: 'custom', from: a, to: b })} />}
        <div className="rn-toolbar">
          <SearchableSelect value={f.status} onChange={(v) => patch({ status: v })} ariaLabel={t('health.smtpLogStatus')}
            options={[{ value: '', label: t('health.smtpFilterStatusAll') }, ...STATUSES.map((s) => ({ value: s, label: t(STATUS_META[s].key) }))]} />
          <SearchableSelect value={f.trigger} onChange={(v) => patch({ trigger: v })} ariaLabel={t('health.emailDetailTrigger')}
            options={[{ value: '', label: t('sml.allTriggers') }, ...triggerOptions.map((x) => ({ value: x, label: triggerLabel(x, t) }))]} />
          <SearchableSelect value={f.teamId} onChange={(v) => patch({ teamId: v })} ariaLabel={t('sml.colTeam')} options={teamOptions} />
          <SearchableSelect value={f.level} onChange={(v) => patch({ level: v })} ariaLabel={t('pl.colLevel')}
            options={[{ value: '', label: t('pl.allLevels') }, ...LEVELS.map((l) => ({ value: l, label: l }))]} />
          <SearchableSelect value={f.errorClass} onChange={(v) => patch({ errorClass: v })} ariaLabel={t('sml.colErrorClass')}
            options={[{ value: '', label: t('sml.allClasses') }, ...ERROR_CLASSES.map((c) => ({ value: c, label: t(`pl.cls.${c}`) }))]} />
          <label className="rn-search-wrap">
            <Search size={14} aria-hidden="true" />
            <input className="rn-search" type="search" placeholder={t('pl.searchPlaceholder')} value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label={t('pl.searchPlaceholder')} />
            {qInput && <button type="button" className="sml-search-clear" onClick={() => setQInput('')} aria-label={t('app.clearFilter')}><X size={12} /></button>}
          </label>
          {(f.username || f.monitorType) && (
            <span className="sml-chips">
              {f.username && <button type="button" className="sml-chip" onClick={() => patch({ username: '' })}>{t('pl.colUser')}: {f.username} <X size={11} /></button>}
              {f.monitorType && <button type="button" className="sml-chip" onClick={() => patch({ monitorType: '' })}>{t('pl.colMonitor')}: {f.monitorType} <X size={11} /></button>}
            </span>
          )}
          {activeCount > 0 && <Button type="button" variant="secondary" size="sm" onClick={clearAll}>{t('app.clearFilters')} ({activeCount})</Button>}
          <span className="rn-count">{t('sml.count', rows.total)}</span>
        </div>
      </div>

      <div className="rn-kpis sml-kpis">
        <button type="button" className={`rn-kpi${!f.status ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.total }} onClick={() => patch({ status: '' })}><span className="rn-kpi-num">{kpi.total ?? '—'}</span><span className="rn-kpi-lbl">{t('sml.kpiTotal')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'SENT' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.sent }} onClick={() => toggleStatus('SENT')}><span className="rn-kpi-num">{kpi.sent ?? '—'}</span><span className="rn-kpi-lbl">{t('health.statusSent')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'FAILED' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.failed }} onClick={() => toggleStatus('FAILED')}><span className="rn-kpi-num">{kpi.failed ?? '—'}</span><span className="rn-kpi-lbl">{t('health.statusFailed')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'PENDING' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.pending }} onClick={() => toggleStatus('PENDING')}><span className="rn-kpi-num">{kpi.pending ?? '—'}</span><span className="rn-kpi-lbl">{t('pl.statusPending')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'BLOCKED' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.failed }} onClick={() => toggleStatus('BLOCKED')}><span className="rn-kpi-num">{kpi.blocked ?? '—'}</span><span className="rn-kpi-lbl">{t('pl.statusBlocked')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'SKIPPED' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.skipped }} onClick={() => toggleStatus('SKIPPED')}><span className="rn-kpi-num">{kpi.skipped ?? '—'}</span><span className="rn-kpi-lbl">{t('health.statusSkipped')}</span></button>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': KPI_COLORS.rate }}><span className="rn-kpi-num">{kpi.success_rate == null ? '—' : `%${kpi.success_rate}`}</span><span className="rn-kpi-lbl">{t('sml.kpiRate')}</span></div>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': KPI_COLORS.users }}><span className="rn-kpi-num">{kpi.failed_users ?? '—'}</span><span className="rn-kpi-lbl">{t('pl.kpiFailedUsers')}</span></div>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': KPI_COLORS.sent }}><span className="rn-kpi-num sml-kpi-time">{kpi.last_sent_at ? formatDate(kpi.last_sent_at) : '—'}</span><span className="rn-kpi-lbl">{t('sml.kpiLastSent')}</span></div>
      </div>

      <div className="sml-card">
        <div className="sml-card-head"><span>{t('sml.timeline')}</span><span className="sys-muted sys-small">{summary?.granularity === 'hour' ? t('sml.hourly') : t('sml.daily')} · {t('sml.timelineHint')}</span></div>
        <div className="sml-chart">
          <ResponsiveContainer width="100%" height={180}>
            <BarChart data={summary?.timeline || []} margin={{ top: 6, right: 10, bottom: 0, left: -10 }} onClick={(e) => { const p = e?.activePayload?.[0]?.payload; if (p) onBucketClick(p) }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />
              <XAxis dataKey="bucket" tickFormatter={(b) => summary?.granularity === 'hour' ? String(b).slice(11, 16) : String(b).slice(5, 10)} tick={{ fontSize: 11 }} minTickGap={18} />
              <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
              <RTooltip labelFormatter={(b) => formatDate(String(b).length === 10 ? b + 'T00:00:00' : b)} formatter={(v, name) => [v, t(STATUS_META[String(name).toUpperCase()]?.key || name)]} />
              <Bar dataKey="sent" name="sent" stackId="s" fill={KPI_COLORS.sent} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
              <Bar dataKey="failed" name="failed" stackId="s" fill={KPI_COLORS.failed} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
              <Bar dataKey="pending" name="pending" stackId="s" fill={KPI_COLORS.pending} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
              <Bar dataKey="skipped" name="skipped" stackId="s" fill={KPI_COLORS.skipped} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="sml-grid">
        <PushBreakdownPanel title={t('sml.byTeam')} rows={teams} keyOf={(x) => x.team_id ?? '-'} status={f.status} onFilter={applyBreakdown}
          label={(x) => x.team_id != null ? <TeamBadge teamId={x.team_id} teamName={x.team_name || `#${x.team_id}`} /> : <span className="sys-muted">{t('sml.noTeam')}</span>}
          dim={(x) => x.team_id != null ? { teamId: String(x.team_id) } : null} isDimActive={(x) => x.team_id != null && String(x.team_id) === String(f.teamId)} />
        <PushBreakdownPanel title={t('pl.topUsers')} rows={summary?.top_users || []} keyOf={(x) => x.username} status={f.status} onFilter={applyBreakdown}
          label={(x) => <UserBadge username={x.username} displayName={x.display_name} inline size="sm" />}
          dim={(x) => ({ username: x.username })} isDimActive={(x) => !!x.username && f.username === x.username} />
        <PushBreakdownPanel title={t('pl.topMonitors')} rows={summary?.top_monitors || []} keyOf={(x) => `${x.monitor_type}:${x.monitor_name}`} status={f.status} onFilter={applyBreakdown}
          label={(x) => <span className="sml-mini-mon"><span className="today-type">{x.monitor_type}</span><span className="pbp-mon" title={x.monitor_name || ''}>{x.monitor_name}</span></span>}
          dim={(x) => ({ q: x.monitor_name || '' })} isDimActive={(x) => !!x.monitor_name && f.q === x.monitor_name} />
        <PushBreakdownPanel title={t('pl.byLevel')} rows={summary?.levels || []} keyOf={(x) => x.level ?? '-'} status={f.status} onFilter={applyBreakdown}
          label={(x) => x.level ? <span className={`today-level today-level--${String(x.level).toLowerCase()}`}>{x.level}</span> : <span className="sys-muted">—</span>}
          dim={(x) => x.level ? { level: x.level } : null} isDimActive={(x) => !!x.level && f.level === x.level} />
      </div>

      <div className="sml-card sml-table-card" ref={tableRef}>
        {loading && !summary ? <LoadingBlock label={t('sys.loading')} fullWidth /> : rows.total === 0 ? (
          <StatusBlock tone="neutral" icon={Webhook} title={t('health.smtpLogNoMatch')} description={activeCount > 0 ? t('sml.noMatchHint') : t('pl.noRowsHint')} />
        ) : (
          <div className="sml-scroll">
            <table className="smtp-log-table sml-table">
              <thead>
                <tr>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('at')}>{t('health.smtpLogDate')} {sortIcon('at')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('team')}>{t('sml.colTeam')} {sortIcon('team')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('user')}>{t('pl.colUser')} {sortIcon('user')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('monitor')}>{t('pl.colMonitor')} {sortIcon('monitor')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('level')}>{t('pl.colLevel')} {sortIcon('level')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('trigger')}>{t('health.emailDetailTrigger')} {sortIcon('trigger')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('status')}>{t('health.smtpLogStatus')} {sortIcon('status')}</button></th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.items.map((row) => (
                  <tr key={row.id} className="smtp-log-row" tabIndex={0}
                    aria-label={t('a11y.openRow', formatDate(row.at))}
                    onClick={() => setDetailId(row.id)}
                    onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setDetailId(row.id) } }}>
                    <td className="smtp-log-date sys-mono">{formatDate(row.at)}</td>
                    <td>{row.team_name ? <span className="sml-stop" onClick={(e) => e.stopPropagation()}><TeamBadge teamId={row.team_id} teamName={row.team_name} /></span> : <span className="sys-muted">—</span>}</td>
                    <td><span className="sml-stop" onClick={(e) => e.stopPropagation()}><UserBadge username={row.username} displayName={row.display_name} inline size="sm" /></span></td>
                    <td><span className="today-type">{row.monitor_type || '—'}</span> <span className="smtp-log-subject" title={row.title || ''}>{row.monitor_name || row.title || '—'}</span></td>
                    <td>{row.alert_level ? <span className={`today-level today-level--${String(row.alert_level).toLowerCase()}`}>{row.alert_level}</span> : '—'}</td>
                    <td><span className={`smtp-trigger-badge pl-trigger-${(row.trigger || '').toLowerCase()}`}>{triggerLabel(row.trigger, t)}</span></td>
                    <td><PushStatusBadge row={row} t={t} />{row.error_class && <span className="sml-cls">{t(`pl.cls.${row.error_class}`)}</span>}</td>
                    <td className="sml-actions" onClick={(e) => e.stopPropagation()}>
                      {/* Adlar kaydı ayırır (izleme/başlık + zaman): yeniden kuyruğa alma YAN ETKİLİ ve
                          her satırda aynı adla duyuluyordu (2026-09-25, R15). İpucu kısa kalır. */}
                      <Button type="button" variant="secondary" size="sm" title={t('pl.detail')}
                        aria-label={t('a11y.rowAction', `${row.monitor_name || row.title || '—'} · ${formatDate(row.at)}`, t('pl.detail'))}
                        onClick={() => setDetailId(row.id)}><Eye size={13} /></Button>
                      {canRequeue && retryable(row) && <Button type="button" variant="secondary" size="sm" className="sml-resend" title={t('pl.requeue')}
                        aria-label={t('a11y.rowAction', `${row.monitor_name || row.title || '—'} · ${formatDate(row.at)}`, t('pl.requeue'))}
                        disabled={busy} onClick={() => requeue(row)}><RotateCcw size={13} /></Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {rows.total > 0 && (
          <PaginationBar page={page} totalPages={totalPages} totalItems={rows.total} rangeStart={(page - 1) * size + 1} rangeEnd={Math.min(page * size, rows.total)}
            pageSize={size} onPageChange={setPage} onPageSizeChange={(s) => { setSize(s); setPage(1) }} />
        )}
      </div>

      {detailId && (
        <ModalShell open onClose={() => setDetailId(null)} title={t('pl.detail')} icon={Webhook} size="xl" scrollBody
          footer={<>
            {detail?.alert_event_id && <Button type="button" variant="secondary" onClick={() => { setDetailId(null); navigateTo('alerthistory', { incident: detail.alert_event_id }) }}><ExternalLink size={13} /> {t('sml.openAlert')}</Button>}
            {canRequeue && detail && retryable(detail) && <Button type="button" disabled={busy} onClick={() => requeue(detail)}><RotateCcw size={13} /> {t('pl.requeue')}</Button>}
            <Button type="button" variant="secondary" onClick={() => setDetailId(null)}>{t('app.close')}</Button>
          </>}>
          {!detail ? <LoadingBlock label={t('sys.loading')} fullWidth /> : (
            <div className="sml-detail">
              <div className="smtp-detail-meta">
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('pl.colUser')}</span><span><UserBadge username={detail.username} displayName={detail.display_name} inline size="sm" />{detail.team_name && <> <TeamBadge teamId={detail.team_id} teamName={detail.team_name} /></>}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('pl.colMonitor')}</span><span><span className="today-type">{detail.monitor_type || '—'}</span> {detail.monitor_name || '—'}{detail.alert_level && <> <span className={`today-level today-level--${String(detail.alert_level).toLowerCase()}`}>{detail.alert_level}</span></>}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('pl.colTitle')}</span><span className="smtp-detail-subject">{detail.title || '—'}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.smtpLogDate')}</span><span className="sys-mono">{formatDate(detail.created_at)}{detail.sent_at && <span className="sys-muted"> → {formatDate(detail.sent_at)}</span>}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.emailDetailTrigger')}</span><span className={`smtp-trigger-badge pl-trigger-${(detail.trigger || '').toLowerCase()}`}>{triggerLabel(detail.trigger, t)}</span><PushStatusBadge row={detail} t={t} compact /></div>
                {detail.kind !== 'SENT' && (
                  <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('sml.colError')}</span>
                    <span className="sml-raw">{detail.error_class && <span className="sml-cls">{t(`pl.cls.${detail.error_class}`)}</span>}<code>{[detail.status, detail.http_status ? `HTTP ${detail.http_status}` : null, detail.error].filter(Boolean).join(' · ')}</code></span></div>
                )}
                {(detail.notification_id || detail.batch_id) && (
                  <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('pl.colNotificationId')}</span><span className="sys-mono sys-small">{detail.notification_id || '—'}{detail.batch_id && <span className="sys-muted"> · batch {detail.batch_id}</span>}</span></div>
                )}
              </div>
              {(detail.batch || []).length > 1 && (
                <div className="sml-chain">
                  <div className="smtp-detail-body-label">{t('pl.batchPeers', detail.batch.length)}</div>
                  <ul className="sml-chain-list">
                    {detail.batch.map((c) => (
                      <li key={c.id} className={c.id === detail.id ? 'is-current' : ''}>
                        <button type="button" className="sml-chain-btn" onClick={() => setDetailId(c.id)} disabled={c.id === detail.id}>
                          <UserBadge username={c.username} displayName={c.display_name} inline size="sm" />
                          <PushStatusBadge row={c} t={t} compact />
                          {c.http_status && <span className="sys-muted sys-small">HTTP {c.http_status}</span>}
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="smtp-detail-body-label">{t('pl.message')}</div>
              <pre className="sml-pre">{detail.message || t('health.emailDetailNoBody')}</pre>
              {detail.raw_response && (<>
                <div className="smtp-detail-body-label">{t('pl.rawResponse')}</div>
                <pre className="sml-pre">{detail.raw_response}</pre>
              </>)}
            </div>
          )}
        </ModalShell>
      )}
      {!canRequeue && rows.items.some(retryable) && <div className="sys-muted sys-small sml-foot"><ShieldAlert size={12} /> {t('pl.requeueAdminOnly')}</div>}
    </div>
  )
}
