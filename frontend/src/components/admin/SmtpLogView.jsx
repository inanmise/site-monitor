import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { RefreshCw, Download, Mail, Eye, Send, CheckCircle, XCircle, MinusCircle, Clock, HelpCircle,
  ExternalLink, ShieldAlert } from 'lucide-react'
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
import { useServerPagination } from '../../hooks/useServerPagination.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock, ProgressBar } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import ToneBadge from './ToneBadge.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { LogHeader, RangeControl, KpiTile, LogCard, SortHead, FilterChip, KindBadge, TriggerBadge, LevelBadge,
  ErrorClassBadge, MetaRow, SectionLabel, ChainList, MUTED_SM } from './LogViewParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ChartContainer, ChartTooltip, ChartTooltipContent, BarChart, Bar, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

/** Süzgeç seçicisi kabı (eski `.rn-toolbar > .ss-wrap`). */
const SELECT_BOX = 'w-full sm:w-auto sm:min-w-[150px] sm:max-w-[230px]'
/** Kırılım mini tabloları (eski `.sml-mini*`): sabit yerleşim, ad esner, sayılar dar/sağa, tarih tek satır. */
const MINI_TH = 'h-8 px-1.5 font-semibold text-muted-foreground'
const MINI_TD = 'px-1.5 py-1 align-top tabular-nums'
const MINI_NUM = 'w-[84px] text-right whitespace-nowrap'
const MINI_DATE = 'w-[140px] whitespace-nowrap'
const MINI_NAME = 'min-w-0 px-1.5 py-1 align-top break-words whitespace-normal'
const LINK_BTN = 'h-auto p-0 text-left text-[1em] font-bold whitespace-normal text-foreground hover:text-primary'
const BAD = 'font-bold text-destructive'
const EMPTY = 'py-2.5 text-[0.85em] text-muted-foreground'
/** Ana tablo hücresi. */
const TD = 'px-2 py-2 align-top'

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
  SENT:    { Icon: CheckCircle, tone: 'success', key: 'health.statusSent' },
  FAILED:  { Icon: XCircle,     tone: 'danger',  key: 'health.statusFailed' },
  SKIPPED: { Icon: MinusCircle, tone: 'muted',   key: 'health.statusSkipped' },
  QUEUED:  { Icon: Clock,       tone: 'muted',   key: 'sml.statusQueued' },
  UNKNOWN: { Icon: HelpCircle,  tone: 'muted',   key: 'health.statusUnknown' },
}
const KPI_COLORS = { total: '#71717a', sent: '#059669', failed: '#dc2626', skipped: '#a1a1aa', rate: '#2563eb', recipients: '#b45309' }

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
    <div className="flex flex-col items-start gap-0.5">
      <KindBadge tone={m.tone} icon={m.Icon}>{t(m.key)}</KindBadge>
      {error && !compact && <div className="max-w-[260px] text-xs break-all text-destructive" title={error}>{error}</div>}
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

  // Sayfalama standardı (2026-09-26): süzgeç değişince sayfa 1 (değer karşılaştırmalı), `m_page`/`m_ps` adresi kanca
  // okur/yazar (ps listeye karşı doğrulanır), toplam gelince `m_page=99` son sayfaya çekilir. API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'smtp-log', preset: 'panel', resetDeps: [f], url: { pageKey: 'm_page', sizeKey: 'm_ps' }, apiBase: 0 })
  const { apiPage, pageSize: size, setTotal } = sp
  // Arama kutusu 300 ms debounce.
  const patch = useCallback((p) => { setF((prev) => ({ ...prev, ...p })) }, [])
  useEffect(() => { const id = setTimeout(() => { if (qInput !== f.q) patch({ q: qInput }) }, 300); return () => clearTimeout(id) }, [qInput, f.q, patch])

  useUrlQuerySync({
    m_range: f.range === '7d' ? null : f.range,
    m_from: f.range === 'custom' ? toIso(f.from) : null,
    m_to: f.range === 'custom' ? toIso(f.to) : null,
    m_status: f.status || null, m_trigger: f.trigger || null, m_team: f.teamId || null, m_cls: f.errorClass || null,
    m_domain: f.domain || null, m_rcpt: f.recipient || null, m_q: f.q || null,
    m_sort: f.sort === 'sent_at,desc' ? null : f.sort,
    m_id: detailId || null,
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
        api.admin.smtpLog.search({ ...params, page: apiPage, size }),
      ])
      if (my !== reqRef.current) return
      if (!s?.success || !r?.success) { setError(s?.error || r?.error || t('mon.loadError')); return }
      setSummary(s.data); setRows({ items: r.data || [], total: Number(r.total) || 0 }); setTotal(Number(r.total) || 0); setError(null); setUpdatedAt(new Date())
    } catch (e) {
      if (my === reqRef.current) setError(String(e?.message || e))
    } finally {
      if (my === reqRef.current) setLoading(false)
    }
  }, [buildParams, apiPage, size, t, setTotal])
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
  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): uç boşken (derin bağlantıda yalnız m_from) her
  // çizimde — canlı yenileme dâhil — yeni bir `new Date()` geçmek seçicinin taslağını sıfırlıyordu.
  const customOpen = f.range === 'custom'
  const pickerRange = useMemo(() => (customOpen ? { from: f.from || rangeFrom('7d'), to: f.to || new Date() } : null),
    [customOpen, f.from, f.to])

  function clearAll() { setQInput(''); setF({ ...readInitial({}), range: '7d', from: null, to: null, status: '', trigger: '', teamId: '', errorClass: '', domain: '', recipient: '', q: '', sort: 'sent_at,desc' }); sp.reset() }
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

  const windowText = f.range === 'custom'
    ? `${f.from ? formatDate(toIso(f.from)) : '…'} → ${f.to ? formatDate(toIso(f.to)) : t('sml.now')}`
    : t(`sml.range.${f.range}`)
  const chartConfig = {
    sent: { label: t('health.statusSent'), color: KPI_COLORS.sent },
    failed: { label: t('health.statusFailed'), color: KPI_COLORS.failed },
    skipped: { label: t('health.statusSkipped'), color: KPI_COLORS.skipped },
  }
  const pickRange = (r) => (r === 'custom'
    ? patch({ range: 'custom', from: f.from || rangeFrom('7d'), to: f.to || new Date() })
    : patch({ range: r, from: null, to: null }))
  const miniActive = 'bg-primary/10 hover:bg-primary/10'

  return (
    <div className="flex flex-col gap-3.5">
      {/* Başlık */}
      <LogHeader icon={Mail} title={t('health.smtpLogsTitle')} backLabel={t('sml.back')} onBack={onBack}
        subtitle={<>{windowText} · {t('sml.autoRefresh')}{updatedAt ? ` · ${t('sml.updatedAt', updatedAt.toLocaleTimeString())}` : ''}</>}
        actions={<>
          <Button type="button" variant="secondary" onClick={() => load()} disabled={loading} aria-busy={loading || undefined}>
            <RefreshCw size={14} className={cn(loading && 'animate-spin motion-reduce:animate-none')} /> {t('sml.refresh')}
          </Button>
          <Button type="button" variant="secondary" onClick={exportCsv} disabled={busy || rows.total === 0}><Download size={14} /> {t('sml.exportCsv')}</Button>
        </>} />

      {error && <AlertBanner tone="danger" title={t('mon.loadError')}>{error}</AlertBanner>}

      {/* Süzgeç çubuğu */}
      <Card className="gap-2.5 px-3.5 py-3 shadow-none">
        <RangeControl value={f.range} onChange={pickRange} ranges={RANGES} label={t('sml.rangeLabel')} t={t} />
        {pickerRange && (
          <DateTimeRangePicker from={pickerRange.from} to={pickerRange.to} onApply={(a, b) => patch({ range: 'custom', from: a, to: b })} />
        )}
        <div className="flex flex-wrap items-center gap-2">
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.status} onChange={(v) => patch({ status: v })} ariaLabel={t('health.smtpLogStatus')}
              options={[{ value: '', label: t('health.smtpFilterStatusAll') }, ...STATUSES.map((s) => ({ value: s, label: t(STATUS_META[s].key) }))]} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.trigger} onChange={(v) => patch({ trigger: v })} ariaLabel={t('health.emailDetailTrigger')}
              options={[{ value: '', label: t('sml.allTriggers') }, ...TRIGGERS.map((x) => ({ value: x, label: triggerLabel(x, t) }))]} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.teamId} onChange={(v) => patch({ teamId: v })} ariaLabel={t('sml.colTeam')} options={teamOptions} />
          </span>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.errorClass} onChange={(v) => patch({ errorClass: v })} ariaLabel={t('sml.colErrorClass')}
              options={[{ value: '', label: t('sml.allClasses') }, ...ERROR_CLASSES.map((c) => ({ value: c, label: t(`sml.cls.${c}`) }))]} />
          </span>
          <ToolbarSearch value={qInput} onChange={setQInput} placeholder={t('sml.searchPlaceholder')}
            ariaLabel={t('sml.searchPlaceholder')} clearLabel={t('app.clearFilter')} className="w-full max-w-none sm:w-auto sm:max-w-[360px] sm:min-w-[200px] sm:flex-[1_1_220px]" />
          {(f.domain || f.recipient) && (
            <span className="inline-flex flex-wrap gap-1.5">
              {f.domain && <FilterChip onClick={() => patch({ domain: '' })}>{t('health.smtpLogDomain')}: {f.domain}</FilterChip>}
              {f.recipient && <FilterChip onClick={() => patch({ recipient: '' })}>{t('health.smtpLogRecipient')}: {f.recipient}</FilterChip>}
            </span>
          )}
          {activeCount > 0 && <Button type="button" variant="secondary" size="sm" onClick={clearAll}>{t('app.clearFilters')} ({activeCount})</Button>}
          <span className="text-[0.82em] whitespace-nowrap text-muted-foreground">{t('sml.count', rows.total)}</span>
        </div>
      </Card>

      {/* KPI şeridi */}
      <div className="grid grid-cols-[repeat(auto-fit,minmax(130px,1fr))] gap-2.5">
        <KpiTile color={KPI_COLORS.total} value={kpi.total ?? '—'} label={t('sml.kpiTotal')} active={!f.status} onClick={() => patch({ status: '' })} />
        <KpiTile color={KPI_COLORS.sent} value={kpi.sent ?? '—'} label={t('health.statusSent')} active={f.status === 'SENT'} onClick={() => toggleStatus('SENT')} />
        <KpiTile color={KPI_COLORS.failed} value={kpi.failed ?? '—'} label={t('health.statusFailed')} active={f.status === 'FAILED'} onClick={() => toggleStatus('FAILED')} />
        <KpiTile color={KPI_COLORS.skipped} value={kpi.skipped ?? '—'} label={t('health.statusSkipped')} active={f.status === 'SKIPPED'} onClick={() => toggleStatus('SKIPPED')} />
        <KpiTile color={KPI_COLORS.rate} value={kpi.success_rate == null ? '—' : `%${kpi.success_rate}`} label={t('sml.kpiRate')} />
        <KpiTile color={KPI_COLORS.recipients} value={kpi.failed_recipients ?? '—'} label={t('sml.kpiFailedRecipients')} />
        <KpiTile color={KPI_COLORS.sent} small value={kpi.last_sent_at ? formatDate(kpi.last_sent_at) : '—'} label={t('sml.kpiLastSent')} />
      </div>

      {/* Zaman çizelgesi — shadcn Chart (ChartContainer + recharts) */}
      <LogCard title={t('sml.timeline')} hint={<>{summary?.granularity === 'hour' ? t('sml.hourly') : t('sml.daily')} · {t('sml.timelineHint')}</>}>
        <ChartContainer config={chartConfig} className="aspect-auto h-[180px] w-full">
          <BarChart data={summary?.timeline || []} margin={{ top: 6, right: 10, bottom: 0, left: -10 }} onClick={(e) => { const p = e?.activePayload?.[0]?.payload; if (p) onBucketClick(p) }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={(b) => summary?.granularity === 'hour' ? String(b).slice(11, 16) : String(b).slice(5, 10)} tick={{ fontSize: 11 }} minTickGap={18} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
            <ChartTooltip content={<ChartTooltipContent labelFormatter={(_, p) => { const b = p?.[0]?.payload?.bucket; return b ? formatDate(String(b).length === 10 ? b + 'T00:00:00' : b) : '' }} />} />
            <Bar dataKey="sent" name="sent" stackId="s" fill={KPI_COLORS.sent} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="failed" name="failed" stackId="s" fill={KPI_COLORS.failed} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="skipped" name="skipped" stackId="s" fill={KPI_COLORS.skipped} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} radius={[3, 3, 0, 0]} />
          </BarChart>
        </ChartContainer>
      </LogCard>

      {/* Kırılımlar — kartlar ALT ALTA (2026-09-21: yan yana 280 px kartta ad sütunu harf harf kırılıyordu) */}
      <div className="grid grid-cols-1 gap-3" data-testid="sml-breakdowns">
        <LogCard title={t('sml.byTeam')}>
          {teams.length === 0 ? <div className={EMPTY}>{t('sml.noData')}</div> : (
            <Table className="table-fixed text-[0.82em]">
              <TableHeader><TableRow>
                <TableHead className={MINI_TH}>{t('sml.colTeam')}</TableHead><TableHead className={cn(MINI_TH, MINI_NUM)}>{t('sml.kpiTotal')}</TableHead>
                <TableHead className={cn(MINI_TH, MINI_NUM)}>{t('health.statusSent')}</TableHead><TableHead className={cn(MINI_TH, MINI_NUM)}>{t('health.statusFailed')}</TableHead>
                <TableHead className={cn(MINI_TH, MINI_NUM)}>{t('sml.kpiRate')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {teams.map((x) => (
                  <TableRow key={x.team_id ?? '-'} data-state={String(x.team_id ?? '') === String(f.teamId) ? 'selected' : undefined}
                    className={cn(String(x.team_id ?? '') === String(f.teamId) && miniActive)}>
                    <TableCell className={MINI_NAME}>{x.team_id != null
                      ? <Button type="button" variant="link" size="xs" className={LINK_BTN} onClick={() => patch({ teamId: String(f.teamId) === String(x.team_id) ? '' : String(x.team_id) })}>{x.team_name || `#${x.team_id}`}</Button>
                      : <span className="text-muted-foreground">{t('sml.noTeam')}</span>}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_NUM)}>{x.total}</TableCell><TableCell className={cn(MINI_TD, MINI_NUM, 'text-success')}>{x.sent}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_NUM, x.failed > 0 && BAD)}>{x.failed}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_NUM)}>{x.success_rate == null ? '—' : `%${x.success_rate}`}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </LogCard>
        <LogCard title={t('sml.byErrorClass')}>
          {(summary?.error_classes || []).length === 0 ? <div className={EMPTY}>{t('sml.noFailures')}</div> : (
            <ul className="flex list-none flex-col gap-1.5">
              {(summary.error_classes).map((c) => {
                const max = Math.max(...summary.error_classes.map((y) => y.count), 1)
                const on = f.errorClass === c.error_class
                return (
                  <li key={c.error_class}>
                    <Button type="button" variant="ghost" aria-pressed={on}
                      className={cn('grid h-auto w-full grid-cols-[110px_1fr_36px] items-center gap-2 border border-transparent px-1.5 py-1 text-left font-normal hover:border-primary hover:bg-primary/5',
                        on && 'border-primary bg-primary/5')}
                      onClick={() => patch({ errorClass: on ? '' : c.error_class })}>
                      <span className="truncate text-[0.82em] font-semibold">{t(`sml.cls.${c.error_class}`)}</span>
                      <ProgressBar value={c.count} max={max} size="sm" decorative tone="crit" />
                      <b className="text-right tabular-nums">{c.count}</b>
                    </Button>
                  </li>
                )
              })}
            </ul>
          )}
        </LogCard>
        <LogCard title={t('sml.topRecipients')}>
          {(summary?.top_recipients || []).length === 0 ? <div className={EMPTY}>{t('sml.noData')}</div> : (
            <Table className="table-fixed text-[0.82em]">
              <TableHeader><TableRow>
                <TableHead className={MINI_TH}>{t('health.smtpLogRecipient')}</TableHead><TableHead className={cn(MINI_TH, MINI_NUM)}>{t('sml.kpiTotal')}</TableHead>
                <TableHead className={cn(MINI_TH, MINI_NUM)}>{t('health.statusFailed')}</TableHead><TableHead className={cn(MINI_TH, MINI_DATE)}>{t('sml.lastFailed')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {summary.top_recipients.map((x) => (
                  <TableRow key={x.recipient_email} data-state={f.recipient === x.recipient_email ? 'selected' : undefined}
                    className={cn(f.recipient === x.recipient_email && miniActive)}>
                    <TableCell className={MINI_NAME}><Button type="button" variant="link" size="xs" className={LINK_BTN} title={x.recipient_email} onClick={() => patch({ recipient: f.recipient === x.recipient_email ? '' : x.recipient_email })}>{x.recipient_name || x.recipient_email}</Button>
                      {x.recipient_name && <div className={MUTED_SM}>{x.recipient_email}</div>}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_NUM)}>{x.total}</TableCell><TableCell className={cn(MINI_TD, MINI_NUM, x.failed > 0 && BAD)}>{x.failed}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_DATE, 'text-xs')}>{x.last_failed_at ? formatDate(x.last_failed_at) : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </LogCard>
        <LogCard title={t('sml.topDomains')}>
          {(summary?.top_domains || []).length === 0 ? <div className={EMPTY}>{t('sml.noData')}</div> : (
            <Table className="table-fixed text-[0.82em]">
              <TableHeader><TableRow>
                <TableHead className={MINI_TH}>{t('health.smtpLogDomain')}</TableHead><TableHead className={cn(MINI_TH, MINI_NUM)}>{t('sml.kpiTotal')}</TableHead>
                <TableHead className={cn(MINI_TH, MINI_NUM)}>{t('health.statusFailed')}</TableHead><TableHead className={cn(MINI_TH, MINI_DATE)}>{t('sml.lastFailed')}</TableHead>
              </TableRow></TableHeader>
              <TableBody>
                {summary.top_domains.map((x) => (
                  <TableRow key={x.domain} data-state={f.domain === x.domain ? 'selected' : undefined}
                    className={cn(f.domain === x.domain && miniActive)}>
                    <TableCell className={MINI_NAME}><Button type="button" variant="link" size="xs" className={LINK_BTN} onClick={() => patch({ domain: f.domain === x.domain ? '' : x.domain })}>{x.domain}</Button>
                      {x.team_name && <div><TeamBadge teamId={x.team_id} teamName={x.team_name} /></div>}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_NUM)}>{x.total}</TableCell><TableCell className={cn(MINI_TD, MINI_NUM, x.failed > 0 && BAD)}>{x.failed}</TableCell>
                    <TableCell className={cn(MINI_TD, MINI_DATE, 'text-xs')}>{x.last_failed_at ? formatDate(x.last_failed_at) : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </LogCard>
      </div>

      {/* Tablo */}
      <Card className="gap-0 overflow-hidden p-0 shadow-none">
        {loading && !summary ? <LoadingBlock label={t('sys.loading')} fullWidth /> : rows.total === 0 ? (
          <StatusBlock tone="neutral" icon={Mail} title={t('health.smtpLogNoMatch')} description={activeCount > 0 ? t('sml.noMatchHint') : t('sml.noRowsHint')} />
        ) : (
          <Table data-testid="sml-table" className="text-[0.85em]">
            <TableHeader className="bg-muted/50">
              <TableRow>
                <SortHead label={t('health.smtpLogDate')} field="sent_at" sort={f.sort} onSort={setSort} />
                <SortHead label={t('sml.colTeam')} field="team" sort={f.sort} onSort={setSort} className="hidden md:table-cell" />
                <SortHead label={t('health.smtpLogDomain')} field="domain" sort={f.sort} onSort={setSort} className="hidden md:table-cell" />
                <SortHead label={t('health.smtpLogRecipient')} field="recipient" sort={f.sort} onSort={setSort} />
                <SortHead label={t('health.smtpLogSubject')} field="subject" sort={f.sort} onSort={setSort} />
                <SortHead label={t('health.emailDetailTrigger')} field="trigger" sort={f.sort} onSort={setSort} className="hidden lg:table-cell" />
                <SortHead label={t('health.smtpLogStatus')} field="status" sort={f.sort} onSort={setSort} />
                <TableHead className="px-2"><span className="sr-only">{t('audit.colDetail')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.items.map((row) => (
                <TableRow key={row.id} tabIndex={0} className="cursor-pointer"
                  aria-label={t('a11y.openRow', formatDate(row.sent_at))}
                  onClick={() => setDetailId(row.id)}
                  onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setDetailId(row.id) } }}>
                  <TableCell className={cn(TD, 'w-[140px] font-mono whitespace-nowrap text-muted-foreground')}>{formatDate(row.sent_at)}</TableCell>
                  <TableCell className={cn(TD, 'hidden md:table-cell')}>{row.team_name ? <span className="inline-flex" onClick={(e) => e.stopPropagation()}><TeamBadge teamId={row.team_id} teamName={row.team_name} /></span> : <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell className={cn(TD, 'hidden max-w-[150px] truncate font-mono text-xs whitespace-nowrap md:table-cell')} title={row.domain || ''}>{row.domain || '—'}</TableCell>
                  <TableCell className={TD}>
                    <div className="leading-tight font-semibold">{row.recipient_name || '—'}</div>
                    <div className="max-w-[160px] truncate text-xs leading-tight text-muted-foreground" title={row.recipient_email || ''}>{row.recipient_email}</div>
                  </TableCell>
                  <TableCell className={cn(TD, 'w-[34%] max-w-0 min-w-[160px] truncate')} title={row.subject || ''}>{row.subject}</TableCell>
                  <TableCell className={cn(TD, 'hidden lg:table-cell')}><TriggerBadge trigger={row.trigger}>{triggerLabel(row.trigger, t)}</TriggerBadge></TableCell>
                  <TableCell className={TD}>
                    <StatusBadge kind={row.kind} error={row.error} t={t} />
                    {row.error_class && <ErrorClassBadge>{t(`sml.cls.${row.error_class}`)}</ErrorClassBadge>}
                  </TableCell>
                  <TableCell className={cn(TD, 'whitespace-nowrap')} onClick={(e) => e.stopPropagation()}>
                    <span className="inline-flex gap-1">
                      <SimpleTooltip content={t('health.emailDetail')}>
                        <Button type="button" variant="secondary" size="icon-sm"
                          aria-label={t('a11y.rowAction', `${row.subject || '—'} · ${formatDate(row.sent_at)}`, t('health.emailDetail'))}
                          onClick={() => setDetailId(row.id)}><Eye size={13} /></Button>
                      </SimpleTooltip>
                      {canResend && row.kind === 'FAILED' && (
                        <SimpleTooltip content={t('sml.resend')}>
                          <Button type="button" variant="secondary" size="icon-sm" data-action="resend" disabled={busy}
                            aria-label={t('a11y.rowAction', `${row.subject || '—'} · ${formatDate(row.sent_at)}`, t('sml.resend'))}
                            onClick={() => resend(row)}><Send size={13} /></Button>
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

      {/* Satır detayı */}
      {detailId && (
        <ModalShell open onClose={() => setDetailId(null)} title={t('health.emailDetail')} icon={Mail} size="xl" scrollBody
          footer={<>
            {detail?.alert?.id && <Button type="button" variant="secondary" onClick={() => { setDetailId(null); navigateTo('alerthistory', { incident: detail.alert.id }) }}><ExternalLink size={13} /> {t('sml.openAlert')}</Button>}
            {canResend && detail?.kind === 'FAILED' && !detail?.alert?.resolved && <Button type="button" disabled={busy} onClick={() => resend(detail)}><Send size={13} /> {t('sml.resend')}</Button>}
            <Button type="button" variant="secondary" onClick={() => setDetailId(null)}>{t('app.close')}</Button>
          </>}>
          {!detail ? <LoadingBlock label={t('sys.loading')} fullWidth /> : (
            <div className="flex flex-col">
              <div className="flex flex-col gap-2 border-b pb-4 border-border">
                <MetaRow label={t('health.emailDetailFrom')}><span className="text-muted-foreground">{detail.sender_email || '—'}</span></MetaRow>
                <MetaRow label={t('health.emailDetailTo')}>
                  <span><strong>{detail.recipient_name}</strong>{detail.recipient_email && <span className="text-muted-foreground"> &lt;{detail.recipient_email}&gt;</span>}
                    {detail.recipient_role && <Badge variant="outline" className="ml-1.5 font-normal text-muted-foreground">{detail.recipient_role}</Badge>}</span>
                </MetaRow>
                {detail.cc && <MetaRow label="CC"><span className="text-muted-foreground">{detail.cc}</span></MetaRow>}
                <MetaRow label={t('health.smtpLogSubject')}><span className="font-semibold">{detail.subject}</span></MetaRow>
                <MetaRow label={t('health.smtpLogDate')}><span className="font-mono">{formatDate(detail.sent_at)}</span></MetaRow>
                <MetaRow label={t('health.emailDetailTrigger')}>
                  <TriggerBadge trigger={detail.trigger}>{triggerLabel(detail.trigger, t)}</TriggerBadge>
                  <StatusBadge kind={detail.kind} t={t} compact />
                </MetaRow>
                {detail.kind !== 'SENT' && (
                  <MetaRow label={t('sml.colError')}>
                    <span className="flex flex-wrap items-center gap-2">{detail.error_class && <ErrorClassBadge>{t(`sml.cls.${detail.error_class}`)}</ErrorClassBadge>}<code className="rounded bg-muted px-1.5 py-0.5 text-[0.82em] break-all">{detail.email_status}</code></span>
                  </MetaRow>
                )}
                {detail.alert && (
                  <MetaRow label={t('sml.alert')}>
                    <span className="inline-flex flex-wrap items-center gap-1.5">
                      <LevelBadge level={detail.alert.level} />
                      <b>{detail.alert.domain}</b> <span className="text-muted-foreground">{detail.alert.type}</span>
                      {detail.alert.resolved ? <ToneBadge tone="success">{t('sml.alertResolved')}</ToneBadge> : <ToneBadge tone="danger">{t('sml.alertOpen')}</ToneBadge>}
                      {detail.team_name && <TeamBadge teamId={detail.team_id} teamName={detail.team_name} />}
                    </span>
                  </MetaRow>
                )}
              </div>
              {(detail.chain || []).length > 1 && (
                <div className="mt-2 mb-1">
                  <SectionLabel>{t('sml.chain', detail.chain.length)}</SectionLabel>
                  <ChainList items={detail.chain} currentId={detail.id} onPick={setDetailId} render={(c) => (
                    <>
                      <span className="font-mono text-xs">{formatDate(c.sent_at)}</span>
                      <TriggerBadge trigger={c.trigger}>{triggerLabel(c.trigger, t)}</TriggerBadge>
                      <span className="text-xs">{c.recipient_email}</span>
                      <StatusBadge kind={c.kind} t={t} compact />
                    </>
                  )} />
                </div>
              )}
              <SectionLabel>{t('health.emailDetailBody')}</SectionLabel>
              <iframe className="min-h-[360px] flex-1 rounded-lg border bg-white border-border" title={detail.subject || 'mail'} sandbox=""
                srcDoc={mailPreviewSrcDoc(detail.message ?? `<p style="color:#a1a1aa;font-family:sans-serif">${t('health.emailDetailNoBody')}</p>`,
                  { logoVariant: mailLogoVariant({ trigger: detail.trigger, level: detail.alert_level }) })} />
            </div>
          )}
        </ModalShell>
      )}
      {!canResend && rows.items.some((r) => r.kind === 'FAILED') && (
        <div className="inline-flex items-center gap-1 text-xs text-muted-foreground"><ShieldAlert size={12} /> {t('sml.resendAdminOnly')}</div>
      )}
    </div>
  )
}
