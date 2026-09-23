import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip, ResponsiveContainer } from 'recharts'
import { ArrowLeft, RefreshCw, Download, Mail, Search, X, Eye, Send, CheckCircle, XCircle, MinusCircle, Clock, HelpCircle,
  ChevronUp, ChevronDown, ExternalLink, ShieldAlert } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { mailPreviewSrcDoc, mailLogoVariant } from '../../utils/mailPreview.js'
import { csvRows } from '../../utils/csv.js'
import ModalShell from '../ui/ModalShell.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock, ProgressBar } from '../ui/Progress.jsx'

/**
 * SMTP Gönderim Logu v2 (2026-09-19, kullanıcı isteği: "sistemi ancak bu sayfadan izleyebiliriz") —
 * Sistem Sağlığı → SMTP kartının TAM SAYFA alt görünümü (`?tab=health&view=smtp`).
 *
 * Seçilen zenginleştirmeler (4+4): KPI şeridi + zaman çizelgesi · takım kırılımı + takım süzgeci ·
 * hata sınıfları · alıcı/alan özeti · zengin satır detayı (zincir, alarm bağlantısı, ham hata) ·
 * CSV dışa aktarma · başarısızı yeniden gönderme (admin) · 60 sn otomatik yenileme + derin bağlantı
 * (`m_*` URL paramları, PAGE_STATE_PREFIXES). Sunucu taraflı arama/sıralama/sayfalama
 * ({@code /api/admin/smtp-log/*}); takım kapsamı sunucuda satır bazında.
 */
const RANGES = ['24h', '7d', '30d']
const STATUSES = ['SENT', 'FAILED', 'SKIPPED', 'QUEUED']
const TRIGGERS = ['INITIAL', 'ESCALATION', 'DAILY_REALERT', 'MANUAL', 'RESOLUTION']
const ERROR_CLASSES = ['AUTH', 'TIMEOUT', 'CONNECT', 'RECIPIENT', 'RATE', 'OTHER']
const REFRESH_MS = 60_000
const STATUS_META = {
  SENT:    { Icon: CheckCircle, cls: 'smtp-kind-sent',    key: 'health.statusSent' },
  FAILED:  { Icon: XCircle,     cls: 'smtp-kind-failed',  key: 'health.statusFailed' },
  SKIPPED: { Icon: MinusCircle, cls: 'smtp-kind-skipped', key: 'health.statusSkipped' },
  QUEUED:  { Icon: Clock,       cls: 'smtp-kind-skipped', key: 'sml.statusQueued' },
  UNKNOWN: { Icon: HelpCircle,  cls: 'smtp-kind-skipped', key: 'health.statusUnknown' },
}
const KPI_COLORS = { total: '#64748b', sent: '#059669', failed: '#dc2626', skipped: '#94a3b8', rate: '#2563eb', recipients: '#b45309' }

/** Yerel Date → sunucu UTC ISO (saniye, 'Z'siz). */
function toIso(d) { return d instanceof Date && !isNaN(d) ? d.toISOString().slice(0, 19) : null }
function fromIso(s) { if (!s) return null; const d = new Date(s + 'Z'); return isNaN(d) ? null : d }
function rangeFrom(range, now = new Date()) {
  const ms = range === '24h' ? 24 * 3600e3 : range === '30d' ? 30 * 86400e3 : 7 * 86400e3
  return new Date(now.getTime() - ms)
}

export function triggerLabel(trigger, t) {
  const map = { INITIAL: 'health.triggerInitial', ESCALATION: 'health.triggerEscalation', DAILY_REALERT: 'health.triggerDailyRealert', MANUAL: 'health.triggerManual', RESOLUTION: 'health.triggerResolution' }
  return map[trigger] ? t(map[trigger]) : (trigger || '—')
}

export function StatusBadge({ kind, error, t, compact = false }) {
  const m = STATUS_META[kind] ?? STATUS_META.UNKNOWN
  return (
    <div className="sml-status">
      <span className={`smtp-kind-badge ${m.cls}`}><m.Icon size={11} />{t(m.key)}</span>
      {error && !compact && <div className="smtp-log-error sys-err-text sys-small" title={error}>{error}</div>}
    </div>
  )
}

function readInitial(initial) {
  const p = (k, fb = '') => initial?.[k] ?? readUrlParam('m_' + k, fb)
  const range = p('range', '') || (p('from') ? 'custom' : '7d')
  return {
    range,
    from: fromIso(p('from')) ?? (range === 'custom' ? rangeFrom('7d') : null),
    to: fromIso(p('to')),
    status: p('status'), trigger: p('trigger'), teamId: p('team'), errorClass: p('cls'),
    domain: p('domain'), recipient: p('rcpt'), q: p('q'),
    sort: p('sort', 'sent_at,desc'),
  }
}

export default function SmtpLogView({ onBack, initial }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canExecute } = usePermissions()
  const canResend = canExecute('system_health.actions')

  const [f, setF] = useState(() => readInitial(initial))
  const [page, setPage] = useState(() => readUrlInt('m_page', 1))
  const [size, setSize] = useState(() => readUrlInt('m_ps', 25))
  const [qInput, setQInput] = useState(f.q)
  const [summary, setSummary] = useState(null)
  const [rows, setRows] = useState({ items: [], total: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [updatedAt, setUpdatedAt] = useState(null)
  const [detailId, setDetailId] = useState(() => readUrlInt('m_id', null))
  const [detail, setDetail] = useState(null)
  const [busy, setBusy] = useState(false)
  const reqRef = useRef(0)

  // Süzgeç değişince sayfa 1; arama kutusu 300 ms debounce.
  const patch = useCallback((p) => { setF((prev) => ({ ...prev, ...p })); setPage(1) }, [])
  useEffect(() => { const id = setTimeout(() => { if (qInput !== f.q) patch({ q: qInput }) }, 300); return () => clearTimeout(id) }, [qInput, f.q, patch])

  useUrlQuerySync({
    m_range: f.range === '7d' ? null : f.range,
    m_from: f.range === 'custom' ? toIso(f.from) : null,
    m_to: f.range === 'custom' ? toIso(f.to) : null,
    m_status: f.status || null, m_trigger: f.trigger || null, m_team: f.teamId || null, m_cls: f.errorClass || null,
    m_domain: f.domain || null, m_rcpt: f.recipient || null, m_q: f.q || null,
    m_sort: f.sort === 'sent_at,desc' ? null : f.sort,
    m_page: page > 1 ? page : null, m_ps: size !== 25 ? size : null, m_id: detailId || null,
  })

  /** Sunucu parametreleri — 24h/7d/30d canlı (her yüklemede kayan `from`, `to` açık uçlu), özel aralık sabit.
   *  Özet + arama AYNI paramla çağrılır → sunucu penceresi tek taramada (60 sn önbellek anahtarı eşit). */
  const buildParams = useCallback(() => ({
    from: toIso(f.range === 'custom' ? f.from : rangeFrom(f.range)),
    to: f.range === 'custom' ? toIso(f.to) : null,
    status: f.status, trigger: f.trigger, teamId: f.teamId, errorClass: f.errorClass,
    domain: f.domain, recipient: f.recipient, q: f.q, sort: f.sort,
  }), [f])

  const load = useCallback(async (silent = false) => {
    const my = ++reqRef.current
    if (!silent) setLoading(true)
    const params = buildParams()
    try {
      const [s, r] = await Promise.all([
        api.admin.smtpLog.summary(params),
        api.admin.smtpLog.search({ ...params, page: page - 1, size }),
      ])
      if (my !== reqRef.current) return
      if (!s?.success || !r?.success) { setError(s?.error || r?.error || t('mon.loadError')); return }
      setSummary(s.data); setRows({ items: r.data || [], total: Number(r.total) || 0 }); setError(null); setUpdatedAt(new Date())
    } catch (e) {
      if (my === reqRef.current) setError(String(e?.message || e))
    } finally {
      if (my === reqRef.current) setLoading(false)
    }
  }, [buildParams, page, size, t])
  useEffect(() => { load() }, [load])
  useVisibleInterval(() => load(true), REFRESH_MS, false)   // görünürken 60 sn'de bir sessiz tazeleme

  // Satır detayı (URL m_id ile de açılır)
  useEffect(() => {
    if (!detailId) { setDetail(null); return }
    let alive = true
    api.admin.smtpLog.detail(detailId)
      .then((r) => { if (!alive) return; if (r?.success) setDetail(r.data); else { toast.error(r?.error || t('mon.loadError')); setDetailId(null) } })
      .catch((e) => { if (alive) { toast.error(String(e?.message || e)); setDetailId(null) } })
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detailId])

  const kpi = summary?.kpi || {}
  const teams = useMemo(() => summary?.teams || [], [summary])
  const teamOptions = useMemo(() => [{ value: '', label: t('sml.allTeams') },
    ...teams.filter((x) => x.team_id != null).map((x) => ({ value: String(x.team_id), label: x.team_name || `#${x.team_id}` }))], [teams, t])
  const activeCount = [f.status, f.trigger, f.teamId, f.errorClass, f.domain, f.recipient, f.q].filter(Boolean).length + (f.range !== '7d' ? 1 : 0)

  function clearAll() { setQInput(''); setF({ ...readInitial({}), range: '7d', from: null, to: null, status: '', trigger: '', teamId: '', errorClass: '', domain: '', recipient: '', q: '', sort: 'sent_at,desc' }); setPage(1) }
  function toggleStatus(s) { patch({ status: f.status === s ? '' : s }) }
  function setSort(field) {
    const [cur, dir] = f.sort.split(',')
    const next = cur === field ? (dir === 'asc' ? 'desc' : 'asc') : (field === 'sent_at' ? 'desc' : 'asc')
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
      const r = await api.admin.smtpLog.export(buildParams())
      if (!r?.success) { toast.error(r?.error || t('mon.loadError')); return }
      const head = [t('health.smtpLogDate'), t('sml.colTeam'), t('health.smtpLogDomain'), t('health.smtpLogRecipient'), t('sml.colEmail'), t('health.smtpLogSubject'),
        t('health.emailDetailTrigger'), t('health.smtpLogStatus'), t('sml.colErrorClass'), t('sml.colError'), t('health.smtpLogFrom'), 'CC']
      const body = (r.data || []).map((x) => [formatDate(x.sent_at), x.team_name || '', x.domain || '', x.recipient_name || '', x.recipient_email || '', x.subject || '',
        triggerLabel(x.trigger, t), t((STATUS_META[x.kind] ?? STATUS_META.UNKNOWN).key), x.error_class ? t(`sml.cls.${x.error_class}`) : '', x.error || '', x.sender_email || '', x.cc || ''])
      const csv = '﻿' + csvRows([head, ...body])
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const el = document.createElement('a'); el.href = url; el.download = `smtp-gonderim-logu-${new Date().toISOString().slice(0, 10)}.csv`; document.body.appendChild(el); el.click(); el.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      toast.success(r.capped ? t('sml.exportCapped', r.count) : t('sml.exported', r.count))
    } catch (e) { toast.error(String(e?.message || e)) } finally { setBusy(false) }
  }

  async function resend(row) {
    if (!await showConfirm({ title: t('sml.resendTitle'), message: t('sml.resendConfirm', row.recipient_email || '', row.subject || ''), confirmText: t('sml.resend'), cancelText: t('app.cancel'), variant: 'warning' })) return
    setBusy(true)
    try {
      const r = await api.admin.smtpLog.resend(row.id)
      if (r?.success) {
        if (r.sent) toast.success(t('sml.resendOk', r.new_log_id)); else toast.error(t('sml.resendFailed', r.status || ''))
        load(true)
        if (detailId === row.id) setDetailId(null)
      } else {
        toast.error(t(`sml.resendReason.${r?.error}`) === `sml.resendReason.${r?.error}` ? (r?.error || t('mon.loadError')) : t(`sml.resendReason.${r?.error}`))
      }
    } catch (e) { toast.error(String(e?.message || e)) } finally { setBusy(false) }
  }

  const totalPages = Math.max(1, Math.ceil(rows.total / size))
  const sortIcon = (field) => { const [cur, dir] = f.sort.split(','); if (cur !== field) return null; return dir === 'asc' ? <ChevronUp size={12} /> : <ChevronDown size={12} /> }
  const windowText = f.range === 'custom'
    ? `${f.from ? formatDate(toIso(f.from)) : '…'} → ${f.to ? formatDate(toIso(f.to)) : t('sml.now')}`
    : t(`sml.range.${f.range}`)

  return (
    <div className="sml">
      {/* Başlık */}
      <div className="sml-head">
        <button type="button" className="btn btn-secondary sml-back" onClick={onBack}><ArrowLeft size={14} /> {t('sml.back')}</button>
        <div className="sml-title">
          <Mail size={18} aria-hidden="true" />
          <div>
            <h3>{t('health.smtpLogsTitle')}</h3>
            <div className="sys-muted sys-small">{windowText} · {t('sml.autoRefresh')}{updatedAt ? ` · ${t('sml.updatedAt', updatedAt.toLocaleTimeString())}` : ''}</div>
          </div>
        </div>
        <div className="sml-head-actions">
          <button type="button" className="btn btn-secondary" onClick={() => load()} disabled={loading}><RefreshCw size={14} className={loading ? 'spin' : ''} /> {t('sml.refresh')}</button>
          <button type="button" className="btn btn-secondary" onClick={exportCsv} disabled={busy || rows.total === 0}><Download size={14} /> {t('sml.exportCsv')}</button>
        </div>
      </div>

      {error && <AlertBanner tone="danger" title={t('mon.loadError')}>{error}</AlertBanner>}

      {/* Süzgeç çubuğu */}
      <div className="sml-filters">
        <div className="sml-ranges" role="group" aria-label={t('sml.rangeLabel')}>
          {RANGES.map((r) => (
            <button key={r} type="button" className={`smtp-period-pill${f.range === r ? ' is-selected' : ''}`} onClick={() => patch({ range: r, from: null, to: null })}>{t(`sml.range.${r}`)}</button>
          ))}
          <button type="button" className={`smtp-period-pill${f.range === 'custom' ? ' is-selected' : ''}`}
            onClick={() => patch({ range: 'custom', from: f.from || rangeFrom('7d'), to: f.to || new Date() })}>{t('sml.range.custom')}</button>
        </div>
        {f.range === 'custom' && (
          <DateTimeRangePicker from={f.from || rangeFrom('7d')} to={f.to || new Date()} onApply={(a, b) => patch({ range: 'custom', from: a, to: b })} />
        )}
        <div className="rn-toolbar">
          <SearchableSelect value={f.status} onChange={(v) => patch({ status: v })} ariaLabel={t('health.smtpLogStatus')}
            options={[{ value: '', label: t('health.smtpFilterStatusAll') }, ...STATUSES.map((s) => ({ value: s, label: t(STATUS_META[s].key) }))]} />
          <SearchableSelect value={f.trigger} onChange={(v) => patch({ trigger: v })} ariaLabel={t('health.emailDetailTrigger')}
            options={[{ value: '', label: t('sml.allTriggers') }, ...TRIGGERS.map((x) => ({ value: x, label: triggerLabel(x, t) }))]} />
          <SearchableSelect value={f.teamId} onChange={(v) => patch({ teamId: v })} ariaLabel={t('sml.colTeam')} options={teamOptions} />
          <SearchableSelect value={f.errorClass} onChange={(v) => patch({ errorClass: v })} ariaLabel={t('sml.colErrorClass')}
            options={[{ value: '', label: t('sml.allClasses') }, ...ERROR_CLASSES.map((c) => ({ value: c, label: t(`sml.cls.${c}`) }))]} />
          <label className="rn-search-wrap">
            <Search size={14} aria-hidden="true" />
            <input className="rn-search" type="search" placeholder={t('sml.searchPlaceholder')} value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label={t('sml.searchPlaceholder')} />
            {qInput && <button type="button" className="sml-search-clear" onClick={() => setQInput('')} aria-label={t('app.clearFilter')}><X size={12} /></button>}
          </label>
          {(f.domain || f.recipient) && (
            <span className="sml-chips">
              {f.domain && <button type="button" className="sml-chip" onClick={() => patch({ domain: '' })}>{t('health.smtpLogDomain')}: {f.domain} <X size={11} /></button>}
              {f.recipient && <button type="button" className="sml-chip" onClick={() => patch({ recipient: '' })}>{t('health.smtpLogRecipient')}: {f.recipient} <X size={11} /></button>}
            </span>
          )}
          {activeCount > 0 && <button type="button" className="btn btn-secondary btn-sm-p" onClick={clearAll}>{t('app.clearFilters')} ({activeCount})</button>}
          <span className="rn-count">{t('sml.count', rows.total)}</span>
        </div>
      </div>

      {/* KPI şeridi */}
      <div className="rn-kpis sml-kpis">
        <button type="button" className={`rn-kpi${!f.status ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.total }} onClick={() => patch({ status: '' })}>
          <span className="rn-kpi-num">{kpi.total ?? '—'}</span><span className="rn-kpi-lbl">{t('sml.kpiTotal')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'SENT' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.sent }} onClick={() => toggleStatus('SENT')}>
          <span className="rn-kpi-num">{kpi.sent ?? '—'}</span><span className="rn-kpi-lbl">{t('health.statusSent')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'FAILED' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.failed }} onClick={() => toggleStatus('FAILED')}>
          <span className="rn-kpi-num">{kpi.failed ?? '—'}</span><span className="rn-kpi-lbl">{t('health.statusFailed')}</span></button>
        <button type="button" className={`rn-kpi${f.status === 'SKIPPED' ? ' is-active' : ''}`} style={{ '--kpi': KPI_COLORS.skipped }} onClick={() => toggleStatus('SKIPPED')}>
          <span className="rn-kpi-num">{kpi.skipped ?? '—'}</span><span className="rn-kpi-lbl">{t('health.statusSkipped')}</span></button>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': KPI_COLORS.rate }}>
          <span className="rn-kpi-num">{kpi.success_rate == null ? '—' : `%${kpi.success_rate}`}</span><span className="rn-kpi-lbl">{t('sml.kpiRate')}</span></div>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': KPI_COLORS.recipients }}>
          <span className="rn-kpi-num">{kpi.failed_recipients ?? '—'}</span><span className="rn-kpi-lbl">{t('sml.kpiFailedRecipients')}</span></div>
        <div className="rn-kpi rn-kpi--static" style={{ '--kpi': KPI_COLORS.sent }}>
          <span className="rn-kpi-num sml-kpi-time">{kpi.last_sent_at ? formatDate(kpi.last_sent_at) : '—'}</span><span className="rn-kpi-lbl">{t('sml.kpiLastSent')}</span></div>
      </div>

      {/* Zaman çizelgesi */}
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
              <Bar dataKey="skipped" name="skipped" stackId="s" fill={KPI_COLORS.skipped} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Kırılımlar */}
      <div className="sml-grid">
        <div className="sml-card">
          <div className="sml-card-head"><span>{t('sml.byTeam')}</span></div>
          {teams.length === 0 ? <div className="sml-empty">{t('sml.noData')}</div> : (
            <table className="sml-mini">
              <thead><tr><th>{t('sml.colTeam')}</th><th>{t('sml.kpiTotal')}</th><th>{t('health.statusSent')}</th><th>{t('health.statusFailed')}</th><th>{t('sml.kpiRate')}</th></tr></thead>
              <tbody>
                {teams.map((x) => (
                  <tr key={x.team_id ?? '-'} className={String(x.team_id ?? '') === String(f.teamId) ? 'is-active' : ''}>
                    <td>{x.team_id != null
                      ? <button type="button" className="rn-domain-btn" onClick={() => patch({ teamId: String(f.teamId) === String(x.team_id) ? '' : String(x.team_id) })}>{x.team_name || `#${x.team_id}`}</button>
                      : <span className="sys-muted">{t('sml.noTeam')}</span>}</td>
                    <td>{x.total}</td><td className="sml-ok">{x.sent}</td>
                    <td className={x.failed > 0 ? 'sml-bad' : ''}>{x.failed}</td>
                    <td>{x.success_rate == null ? '—' : `%${x.success_rate}`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="sml-card">
          <div className="sml-card-head"><span>{t('sml.byErrorClass')}</span></div>
          {(summary?.error_classes || []).length === 0 ? <div className="sml-empty">{t('sml.noFailures')}</div> : (
            <ul className="sml-bars">
              {(summary.error_classes).map((c) => {
                const max = Math.max(...summary.error_classes.map((y) => y.count), 1)
                return (
                  <li key={c.error_class}>
                    <button type="button" className={`sml-bar-btn${f.errorClass === c.error_class ? ' is-active' : ''}`} onClick={() => patch({ errorClass: f.errorClass === c.error_class ? '' : c.error_class })}>
                      <span className="sml-bar-lbl">{t(`sml.cls.${c.error_class}`)}</span>
                      <ProgressBar value={c.count} max={max} size="sm" decorative className="sml-bar-track" />
                      <b>{c.count}</b>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
        <div className="sml-card">
          <div className="sml-card-head"><span>{t('sml.topRecipients')}</span></div>
          {(summary?.top_recipients || []).length === 0 ? <div className="sml-empty">{t('sml.noData')}</div> : (
            <table className="sml-mini">
              <thead><tr><th>{t('health.smtpLogRecipient')}</th><th>{t('sml.kpiTotal')}</th><th>{t('health.statusFailed')}</th><th>{t('sml.lastFailed')}</th></tr></thead>
              <tbody>
                {summary.top_recipients.map((x) => (
                  <tr key={x.recipient_email} className={f.recipient === x.recipient_email ? 'is-active' : ''}>
                    <td><button type="button" className="rn-domain-btn" title={x.recipient_email} onClick={() => patch({ recipient: f.recipient === x.recipient_email ? '' : x.recipient_email })}>{x.recipient_name || x.recipient_email}</button>
                      {x.recipient_name && <div className="sys-muted sys-small">{x.recipient_email}</div>}</td>
                    <td>{x.total}</td><td className={x.failed > 0 ? 'sml-bad' : ''}>{x.failed}</td>
                    <td className="sys-small">{x.last_failed_at ? formatDate(x.last_failed_at) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="sml-card">
          <div className="sml-card-head"><span>{t('sml.topDomains')}</span></div>
          {(summary?.top_domains || []).length === 0 ? <div className="sml-empty">{t('sml.noData')}</div> : (
            <table className="sml-mini">
              <thead><tr><th>{t('health.smtpLogDomain')}</th><th>{t('sml.kpiTotal')}</th><th>{t('health.statusFailed')}</th><th>{t('sml.lastFailed')}</th></tr></thead>
              <tbody>
                {summary.top_domains.map((x) => (
                  <tr key={x.domain} className={f.domain === x.domain ? 'is-active' : ''}>
                    <td><button type="button" className="rn-domain-btn" onClick={() => patch({ domain: f.domain === x.domain ? '' : x.domain })}>{x.domain}</button>
                      {x.team_name && <div><TeamBadge teamId={x.team_id} teamName={x.team_name} /></div>}</td>
                    <td>{x.total}</td><td className={x.failed > 0 ? 'sml-bad' : ''}>{x.failed}</td>
                    <td className="sys-small">{x.last_failed_at ? formatDate(x.last_failed_at) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {/* Tablo */}
      <div className="sml-card sml-table-card">
        {loading && !summary ? <LoadingBlock label={t('sys.loading')} fullWidth /> : rows.total === 0 ? (
          <StatusBlock tone="neutral" icon={Mail} title={t('health.smtpLogNoMatch')} description={activeCount > 0 ? t('sml.noMatchHint') : t('sml.noRowsHint')} />
        ) : (
          <div className="sml-scroll">
            <table className="smtp-log-table sml-table">
              <thead>
                <tr>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('sent_at')}>{t('health.smtpLogDate')} {sortIcon('sent_at')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('team')}>{t('sml.colTeam')} {sortIcon('team')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('domain')}>{t('health.smtpLogDomain')} {sortIcon('domain')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('recipient')}>{t('health.smtpLogRecipient')} {sortIcon('recipient')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('subject')}>{t('health.smtpLogSubject')} {sortIcon('subject')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('trigger')}>{t('health.emailDetailTrigger')} {sortIcon('trigger')}</button></th>
                  <th><button type="button" className="sml-sort" onClick={() => setSort('status')}>{t('health.smtpLogStatus')} {sortIcon('status')}</button></th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.items.map((row) => (
                  <tr key={row.id} className="smtp-log-row" tabIndex={0}
                    aria-label={t('a11y.openRow', formatDate(row.sent_at))}
                    onClick={() => setDetailId(row.id)}
                    onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setDetailId(row.id) } }}>
                    <td className="smtp-log-date sys-mono">{formatDate(row.sent_at)}</td>
                    <td>{row.team_name ? <span className="sml-stop" onClick={(e) => e.stopPropagation()}><TeamBadge teamId={row.team_id} teamName={row.team_name} /></span> : <span className="sys-muted">—</span>}</td>
                    <td className="smtp-log-domain sys-mono sys-small" title={row.domain || ''}>{row.domain || '—'}</td>
                    <td>
                      <div className="smtp-log-recipient">{row.recipient_name || '—'}</div>
                      <div className="smtp-log-email sys-muted sys-small" title={row.recipient_email || ''}>{row.recipient_email}</div>
                    </td>
                    <td className="smtp-log-subject" title={row.subject || ''}>{row.subject}</td>
                    <td><span className={`smtp-trigger-badge smtp-trigger-${(row.trigger || '').toLowerCase()}`}>{triggerLabel(row.trigger, t)}</span></td>
                    <td>
                      <StatusBadge kind={row.kind} error={row.error} t={t} />
                      {row.error_class && <span className="sml-cls">{t(`sml.cls.${row.error_class}`)}</span>}
                    </td>
                    <td className="sml-actions" onClick={(e) => e.stopPropagation()}>
                      <button type="button" className="btn btn-secondary btn-sm-p" title={t('health.emailDetail')} onClick={() => setDetailId(row.id)}><Eye size={13} /></button>
                      {canResend && row.kind === 'FAILED' && (
                        <button type="button" className="btn btn-secondary btn-sm-p sml-resend" title={t('sml.resend')} disabled={busy} onClick={() => resend(row)}><Send size={13} /></button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {rows.total > 0 && (
          <PaginationBar page={page} totalPages={totalPages} totalItems={rows.total}
            rangeStart={(page - 1) * size + 1} rangeEnd={Math.min(page * size, rows.total)}
            pageSize={size} onPageChange={setPage} onPageSizeChange={(s) => { setSize(s); setPage(1) }} />
        )}
      </div>

      {/* Satır detayı */}
      {detailId && (
        <ModalShell open onClose={() => setDetailId(null)} title={t('health.emailDetail')} icon={Mail} size="xl" scrollBody
          footer={<>
            {detail?.alert?.id && <button type="button" className="btn btn-secondary" onClick={() => { setDetailId(null); navigateTo('alerthistory', { incident: detail.alert.id }) }}><ExternalLink size={13} /> {t('sml.openAlert')}</button>}
            {canResend && detail?.kind === 'FAILED' && !detail?.alert?.resolved && <button type="button" className="btn btn-primary" disabled={busy} onClick={() => resend(detail)}><Send size={13} /> {t('sml.resend')}</button>}
            <button type="button" className="btn btn-secondary" onClick={() => setDetailId(null)}>{t('app.close')}</button>
          </>}>
          {!detail ? <LoadingBlock label={t('sys.loading')} fullWidth /> : (
            <div className="sml-detail">
              <div className="smtp-detail-meta">
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.emailDetailFrom')}</span><span className="sys-muted">{detail.sender_email || '—'}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.emailDetailTo')}</span>
                  <span><strong>{detail.recipient_name}</strong>{detail.recipient_email && <span className="sys-muted"> &lt;{detail.recipient_email}&gt;</span>}{detail.recipient_role && <span className="rn-code-chip sml-role">{detail.recipient_role}</span>}</span></div>
                {detail.cc && <div className="smtp-detail-meta-row"><span className="smtp-detail-label">CC</span><span className="sys-muted">{detail.cc}</span></div>}
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.smtpLogSubject')}</span><span className="smtp-detail-subject">{detail.subject}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.smtpLogDate')}</span><span className="sys-mono">{formatDate(detail.sent_at)}</span></div>
                <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('health.emailDetailTrigger')}</span>
                  <span className={`smtp-trigger-badge smtp-trigger-${(detail.trigger || '').toLowerCase()}`}>{triggerLabel(detail.trigger, t)}</span>
                  <StatusBadge kind={detail.kind} t={t} compact /></div>
                {detail.kind !== 'SENT' && (
                  <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('sml.colError')}</span>
                    <span className="sml-raw">{detail.error_class && <span className="sml-cls">{t(`sml.cls.${detail.error_class}`)}</span>}<code>{detail.email_status}</code></span></div>
                )}
                {detail.alert && (
                  <div className="smtp-detail-meta-row"><span className="smtp-detail-label">{t('sml.alert')}</span>
                    <span className="sml-alert">
                      <span className={`today-level today-level--${(detail.alert.level || '').toLowerCase()}`}>{detail.alert.level}</span>
                      <b>{detail.alert.domain}</b> <span className="sys-muted">{detail.alert.type}</span>
                      {detail.alert.resolved ? <span className="sml-tag sml-tag--ok">{t('sml.alertResolved')}</span> : <span className="sml-tag sml-tag--open">{t('sml.alertOpen')}</span>}
                      {detail.team_name && <TeamBadge teamId={detail.team_id} teamName={detail.team_name} />}
                    </span></div>
                )}
              </div>
              {(detail.chain || []).length > 1 && (
                <div className="sml-chain">
                  <div className="smtp-detail-body-label">{t('sml.chain', detail.chain.length)}</div>
                  <ul className="sml-chain-list">
                    {detail.chain.map((c) => (
                      <li key={c.id} className={c.id === detail.id ? 'is-current' : ''}>
                        <button type="button" className="sml-chain-btn" onClick={() => setDetailId(c.id)} disabled={c.id === detail.id}>
                          <span className="sys-mono sys-small">{formatDate(c.sent_at)}</span>
                          <span className={`smtp-trigger-badge smtp-trigger-${(c.trigger || '').toLowerCase()}`}>{triggerLabel(c.trigger, t)}</span>
                          <span className="sys-small">{c.recipient_email}</span>
                          <StatusBadge kind={c.kind} t={t} compact />
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="smtp-detail-body-label">{t('health.emailDetailBody')}</div>
              <iframe className="smtp-detail-iframe" title={detail.subject || 'mail'} sandbox=""
                srcDoc={mailPreviewSrcDoc(detail.message ?? `<p style="color:#9ca3af;font-family:sans-serif">${t('health.emailDetailNoBody')}</p>`,
                  { logoVariant: mailLogoVariant({ trigger: detail.trigger, level: detail.alert_level }) })} />
            </div>
          )}
        </ModalShell>
      )}
      {!canResend && rows.items.some((r) => r.kind === 'FAILED') && (
        <div className="sys-muted sys-small sml-foot"><ShieldAlert size={12} /> {t('sml.resendAdminOnly')}</div>
      )}
    </div>
  )
}
