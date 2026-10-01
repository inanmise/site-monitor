import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  RefreshCw, Download, Webhook, ShieldAlert, Send, CheckCircle2, XCircle, Clock, Ban, MinusCircle, SlidersHorizontal,
  ChevronDown, ListFilter, BarChart3, Filter,
} from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval'
import { useUrlQuerySync, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { alertNavParams } from './alerts/alertHistoryModel.js'
import { csvRows } from '../../utils/csv.js'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import PushBreakdownPanel from './PushBreakdownPanel.jsx'
import { ToolbarSearch } from './ListToolbar.jsx'
import { LogHeader, RangeControl, FilterChip, LevelBadge, TypeBadge } from './LogViewParts.jsx'
import PushHealthCard from './push/PushHealthCard.jsx'
import PushLogDetail from './push/PushLogDetail.jsx'
import { PushLogTable, PushLogCards, PushStatusBadge } from './push/PushLogRows.jsx'
import {
  RANGES, STATUSES, TRIGGERS, LEVELS, ERROR_CLASSES, SORT_FIELDS, REFRESH_MS, DEFAULT_SORT, EMPTY_FILTERS, SERIES_COLORS,
  triggerLabel, statusLabel, toIso, fromIso, rangeFrom, readInitial,
} from './push/pushLogModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { ChartContainer, ChartTooltip, ChartTooltipContent, BarChart, Bar, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'

// Dışa açık adlar (eski içe aktaranlar için aynı yerden)
export { triggerLabel, PushStatusBadge }

/** Süzgeç seçicisi kabı — telefonda iki sütunlu ızgarada tam genişlik, ≥ 768 px sarmalı satırda. */
const SELECT_BOX = 'min-w-0 md:w-auto md:min-w-[150px] md:max-w-[220px] [&>*]:w-full'
/** Liste kabı bu genişlikten itibaren tablo, altında kart listesi. */
const TABLE_MIN_WIDTH = 760

/**
 * Webhook Push Gönderim Logu — Sistem Sağlığı → Webhook Push kartının TAM SAYFA alt görünümü (`?tab=health&view=push`,
 * süzgeçler `p_*`). 2026-09-19'da SMTP sayfasının push karşılığı olarak kuruldu; 2026-10-01'de shadcn ile yeniden
 * tasarlandı (kullanıcı isteği: "mevcut fonksiyonlar korunsun, mweb responsive, profesyonel; zenginleştirme yapılabilir").
 *
 * <p><b>Korunan işlevler:</b> zaman aralığı (24 sa / 7 g / 30 g / özel) · durum, tetikleyici, takım, seviye, hata sınıfı
 * süzgeçleri + arama · süzgeç işlevli durum sayıları · zaman çizelgesi (çubuğa tıklayınca o dilime süzer) · takım /
 * alıcı / izleme / seviye kırılımı (rakama tıklayınca süzer ve listeye kaydırır) · sıralanabilir liste, satır ayrıntısı
 * (mesaj, ham yanıt, aynı toplu isteğin alıcıları, "Alarmı aç") · CSV · yeniden kuyruğa alma (yönetici, onaylı) ·
 * 60 sn otomatik yenileme · URL'de süzgeç + açık kayıt (`p_id`) · sunucu sayfalaması.
 *
 * <p><b>Yeni:</b> teslimat sağlığı kartı (başarı oranı + ton çubuğu, başarısız alıcı, kuyrukta, son başarılı); tüm etkin
 * süzgeçler çip olarak (her biri × ile kalkar); telefonda süzgeçler tek düğmenin arkasında; grafik lejantı; kırılımlar
 * tek kartta sekmeler (sayılarıyla) ve kart düzeyinde daraltma; liste KABI ≥ 760 px tablo (sütunlar kap genişliğiyle
 * açılır), daha dar kart listesi + sıralama seçici; ayrıntı sağdan açılan panel (liste arkada görünür): durum ve hata
 * nedeni bandı, teslimat akışı (oluşturuldu → gönderildi, bekleme, deneme), mesaj / ham yanıt kopyalama, önceki /
 * sonraki kayıt; yükleme hatasında "Tekrar dene".
 *
 * <p>Sunucu: /api/admin/push-log/*; kapsam sunucuda (takım + kendi satırları). Test kancaları: `data-slot="pl-view"`,
 * `pl-health` (`data-tone`), `pl-rate`, `pl-filters`, `pl-filters-toggle`, `pl-chips`, `pl-timeline`, `pl-breakdown`,
 * `pl-list`, `pl-row` (`data-kind`), `pl-detail`, `pl-flow`; `data-testid="sml-table"` (tablo), `pl-cards`, `pbp`;
 * durum sayıları `[data-slot="stat-item"][data-key]`.
 */
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
  const [filtersOpen, setFiltersOpen] = useState(false)   // telefonda süzgeç seçicileri
  const [bdOpen, setBdOpen] = useState(true)
  const [bdTab, setBdTab] = useState('team')
  const reqRef = useRef(0)
  const listRef = useRef(null)   // kırılım rakamı / grafik tıklanınca listeye kaydır

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
    p_sort: f.sort === DEFAULT_SORT ? null : f.sort,
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
    setDetail(null)
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
  const selectCount = [f.status, f.trigger, f.teamId, f.errorClass, f.level].filter(Boolean).length
  // Sözlük + özette görülen bilinmeyen tetikleyiciler (yeni tür eklenirse süzgeçten düşmesin)
  const triggerOptions = useMemo(() => [...new Set([...TRIGGERS, ...(summary?.triggers || []).map((x) => x.trigger).filter((x) => x && x !== '-')])], [summary])
  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): uç boşken (derin bağlantıda yalnız p_from) her
  // çizimde — canlı yenileme dâhil — yeni bir `new Date()` geçmek seçicinin taslağını sıfırlıyordu.
  const customOpen = f.range === 'custom'
  const pickerRange = useMemo(() => (customOpen ? { from: f.from || rangeFrom('7d'), to: f.to || new Date() } : null),
    [customOpen, f.from, f.to])

  function clearAll() { setQInput(''); setF({ ...EMPTY_FILTERS }); sp.reset() }
  function setSort(field) {
    const [cur, dir] = f.sort.split(',')
    const next = cur === field ? (dir === 'asc' ? 'desc' : 'asc') : (field === 'at' ? 'desc' : 'asc')
    patch({ sort: `${field},${next}` })
  }
  const focusList = () => setTimeout(() => { try { listRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }) } catch { /* jsdom */ } }, 50)
  function onBucketClick(b) {
    if (!b?.bucket) return
    const start = fromIso(b.bucket.length === 10 ? b.bucket + 'T00:00:00' : b.bucket)
    if (!start) return
    const end = new Date(start.getTime() + (summary?.granularity === 'hour' ? 3600e3 : 86400e3) - 1000)
    patch({ range: 'custom', from: start, to: end })
    focusList()
  }

  async function exportCsv() {
    setBusy(true)
    try {
      const r = await api.admin.pushLog.export(buildParams())
      if (!r?.success) { toast.error(r?.error || t('mon.loadError')); return }
      const head = [t('health.smtpLogDate'), t('sml.colTeam'), t('pl.colUser'), t('pl.colUsername'), t('pl.colMonitor'), t('pl.colLevel'), t('health.emailDetailTrigger'),
        t('health.smtpLogStatus'), 'HTTP', t('pl.colAttempts'), t('sml.colErrorClass'), t('sml.colError'), t('pl.colNotificationId'), 'batch']
      const body = (r.data || []).map((x) => [formatDate(x.at), x.team_name || '', x.display_name || '', x.username || '', [x.monitor_type, x.monitor_name].filter(Boolean).join(' '), x.alert_level || '',
        triggerLabel(x.trigger, t), statusLabel(x.kind, t), x.http_status ?? '', x.attempts ?? '', x.error_class ? t(`pl.cls.${x.error_class}`) : '', x.error || '', x.notification_id || '', x.batch_id || ''])
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
  /** Kırılım rakamı → süzgeç (boyut + durum) + listeye kaydırma. İzleme boyutu metin aramasıyla süzülür (q). */
  const applyBreakdown = (p) => {
    if ('q' in p) setQInput(p.q || '')
    patch(p)
    focusList()
  }
  const chartConfig = {
    sent: { label: t('health.statusSent'), color: SERIES_COLORS.sent },
    failed: { label: t('health.statusFailed'), color: SERIES_COLORS.failed },
    pending: { label: t('pl.statusPending'), color: SERIES_COLORS.pending },
    skipped: { label: t('health.statusSkipped'), color: SERIES_COLORS.skipped },
  }
  const pickRange = (r) => (r === 'custom'
    ? patch({ range: 'custom', from: f.from || rangeFrom('7d'), to: f.to || new Date() })
    : patch({ range: r, from: null, to: null }))

  // Durum sayıları — süzgeç kutuları (ikinci tık kaldırır); "Toplam" tüm durumlar
  const fmtN = (v) => (v == null ? '—' : Number(v).toLocaleString())
  const statusKpis = [
    { key: 'total', Icon: Send, label: t('sml.kpiTotal'), value: fmtN(kpi.total), cls: 'total' },
    { key: 'SENT', Icon: CheckCircle2, label: t('health.statusSent'), value: fmtN(kpi.sent), cls: 'valid' },
    { key: 'FAILED', Icon: XCircle, label: t('health.statusFailed'), value: fmtN(kpi.failed), cls: 'critical' },
    { key: 'PENDING', Icon: Clock, label: t('pl.statusPending'), value: fmtN(kpi.pending), cls: 'warning' },
    { key: 'BLOCKED', Icon: Ban, label: t('pl.statusBlocked'), value: fmtN(kpi.blocked), cls: 'error' },
    { key: 'SKIPPED', Icon: MinusCircle, label: t('health.statusSkipped'), value: fmtN(kpi.skipped), cls: 'paused' },
  ]
  const onStat = (key) => patch({ status: key === 'total' ? '' : (f.status === key ? '' : key) })

  // Etkin süzgeç çipleri — her biri × ile kalkar (arama dâhil; aralık varsayılan değilse o da)
  const teamName = teamOptions.find((o) => o.value === String(f.teamId))?.label || f.teamId
  const chips = [
    f.range !== '7d' && { key: 'range', label: t('sml.rangeLabel'), value: windowText, clear: () => patch({ range: '7d', from: null, to: null }) },
    f.status && { key: 'status', label: t('health.smtpLogStatus'), value: statusLabel(f.status, t), clear: () => patch({ status: '' }) },
    f.trigger && { key: 'trigger', label: t('health.emailDetailTrigger'), value: triggerLabel(f.trigger, t), clear: () => patch({ trigger: '' }) },
    f.teamId && { key: 'team', label: t('sml.colTeam'), value: teamName, clear: () => patch({ teamId: '' }) },
    f.level && { key: 'level', label: t('pl.colLevel'), value: f.level, clear: () => patch({ level: '' }) },
    f.errorClass && { key: 'cls', label: t('sml.colErrorClass'), value: t(`pl.cls.${f.errorClass}`), clear: () => patch({ errorClass: '' }) },
    f.username && { key: 'user', label: t('pl.colUser'), value: f.username, clear: () => patch({ username: '' }) },
    f.monitorType && { key: 'mtype', label: t('pl.colMonitor'), value: f.monitorType, clear: () => patch({ monitorType: '' }) },
    f.q && { key: 'q', label: t('pl.chip.q'), value: f.q, clear: () => { setQInput(''); patch({ q: '' }) } },
  ].filter(Boolean)

  // Kırılım sekmeleri
  const bdTabs = [
    { key: 'team', label: t('sml.colTeam'), rows: teams },
    { key: 'user', label: t('pl.colUser'), rows: summary?.top_users || [] },
    { key: 'monitor', label: t('pl.colMonitor'), rows: summary?.top_monitors || [] },
    { key: 'level', label: t('pl.colLevel'), rows: summary?.levels || [] },
  ]
  const bdActive = !!(f.teamId || f.username || f.level || (f.q && (summary?.top_monitors || []).some((x) => x.monitor_name === f.q)))

  // Liste kabı genişliği: ≥ 760 tablo, altı kartlar (ölçüm yoksa — jsdom — tablo)
  const [measureRef, listWidth] = useElementWidth()
  const tableMode = listWidth === 0 || listWidth >= TABLE_MIN_WIDTH
  const ids = useMemo(() => rows.items.map((r) => r.id), [rows.items])
  const sortOptions = SORT_FIELDS.flatMap((fld) => {
    const label = { at: t('health.smtpLogDate'), team: t('sml.colTeam'), user: t('pl.colUser'), monitor: t('pl.colMonitor'),
      level: t('pl.colLevel'), trigger: t('health.emailDetailTrigger'), status: t('health.smtpLogStatus') }[fld]
    return [{ value: `${fld},desc`, label: t('pl.sort.desc', label) }, { value: `${fld},asc`, label: t('pl.sort.asc', label) }]
  })

  return (
    <div data-slot="pl-view" className="flex min-w-0 flex-col gap-4">
      <LogHeader icon={Webhook} title={t('pl.title')} backLabel={t('sml.back')} onBack={onBack}
        subtitle={<>{windowText} · {t('sml.autoRefresh')}{updatedAt ? ` · ${t('sml.updatedAt', updatedAt.toLocaleTimeString())}` : ''}</>}
        actions={<>
          <Button type="button" variant="outline" onClick={() => load()} disabled={loading} aria-busy={loading || undefined} className="pointer-coarse:h-10">
            <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} /> {t('sml.refresh')}
          </Button>
          <Button type="button" variant="outline" onClick={exportCsv} disabled={busy || rows.total === 0} className="pointer-coarse:h-10"><Download aria-hidden="true" /> {t('sml.exportCsv')}</Button>
        </>} />

      {error && (
        <AlertBanner tone="danger" title={t('mon.loadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" onClick={() => load()} className="pointer-coarse:h-10">{t('pl.retry')}</Button>}>
          {error}
        </AlertBanner>
      )}

      {/* Özet: teslimat sağlığı + durum sayıları (süzgeç) */}
      <div className="grid min-w-0 grid-cols-1 gap-3 xl:grid-cols-[minmax(0,360px)_minmax(0,1fr)] xl:items-start">
        <PushHealthCard kpi={kpi} windowText={windowText} t={t} />
        <MonitorStatsBar dense items={statusKpis} activeFilter={f.status || 'total'} onStatClick={onStat} className="mb-0" />
      </div>

      {/* Süzgeçler */}
      <Card data-slot="pl-filters" className="min-w-0 gap-3 px-3.5 py-3 shadow-none">
        <div className="flex flex-wrap items-center justify-between gap-2">
          {/* Dört seçenek telefonda sığmaz (≈ 440 px) — kendi kabında yatay kayar, sayfa taşmaz */}
          <div className="-mx-1 max-w-full overflow-x-auto px-1 pb-0.5 [scrollbar-width:thin]">
            <RangeControl value={f.range} onChange={pickRange} ranges={RANGES} label={t('sml.rangeLabel')} t={t} />
          </div>
          <span className="text-xs whitespace-nowrap text-muted-foreground tabular-nums">{t('sml.count', rows.total)}</span>
        </div>
        {pickerRange && <DateTimeRangePicker from={pickerRange.from} to={pickerRange.to} onApply={(a, b) => patch({ range: 'custom', from: a, to: b })} />}
        <div className="flex flex-wrap items-center gap-2">
          <ToolbarSearch value={qInput} onChange={setQInput} placeholder={t('pl.searchPlaceholder')}
            ariaLabel={t('pl.searchPlaceholder')} clearLabel={t('app.clearFilter')} className="h-9 w-full max-w-none min-w-0 flex-1 md:max-w-[420px]" />
          <Button type="button" variant="outline" data-slot="pl-filters-toggle" aria-expanded={filtersOpen} onClick={() => setFiltersOpen((x) => !x)}
            className="h-10 md:hidden">
            <SlidersHorizontal aria-hidden="true" />{t('pl.filters')}
            {selectCount > 0 && <Badge className="h-5 min-w-5 rounded-full px-1 tabular-nums">{selectCount}</Badge>}
          </Button>
        </div>
        <div className={cn('grid grid-cols-1 gap-2 min-[420px]:grid-cols-2 md:flex md:flex-wrap md:items-center', !filtersOpen && 'max-md:hidden')}>
          <span className={SELECT_BOX}>
            <SearchableSelect value={f.status} onChange={(v) => patch({ status: v })} ariaLabel={t('health.smtpLogStatus')}
              options={[{ value: '', label: t('health.smtpFilterStatusAll') }, ...STATUSES.map((s) => ({ value: s, label: statusLabel(s, t) }))]} />
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
        </div>
        {chips.length > 0 && (
          <div data-slot="pl-chips" className="flex flex-wrap items-center gap-1.5 border-t pt-2.5">
            <ListFilter aria-hidden="true" className="size-3.5 text-muted-foreground" />
            {chips.map((c) => <FilterChip key={c.key} onClick={c.clear}>{c.label}: {c.value}</FilterChip>)}
            <Button type="button" variant="ghost" size="sm" onClick={clearAll} className="ml-auto pointer-coarse:h-10">{t('app.clearFilters')} ({activeCount})</Button>
          </div>
        )}
      </Card>

      {/* Zaman çizelgesi */}
      <Card data-slot="pl-timeline" className="min-w-0 gap-2 px-3.5 py-3 shadow-none">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 className="m-0 flex items-center gap-2 text-sm font-semibold"><BarChart3 aria-hidden="true" className="size-4 text-muted-foreground" />{t('sml.timeline')}</h3>
          <span className="text-xs text-muted-foreground">{summary?.granularity === 'hour' ? t('sml.hourly') : t('sml.daily')} · {t('sml.timelineHint')}</span>
        </div>
        <ul aria-hidden="true" className="m-0 flex list-none flex-wrap gap-x-3 gap-y-1 p-0 text-[11px] text-muted-foreground">
          {Object.entries(chartConfig).map(([k, c]) => (
            <li key={k} className="inline-flex items-center gap-1.5"><span className="size-2.5 rounded-sm" style={{ background: c.color }} />{c.label}</li>
          ))}
        </ul>
        <ChartContainer config={chartConfig} className="aspect-auto h-[160px] w-full sm:h-[190px]">
          <BarChart data={summary?.timeline || []} margin={{ top: 6, right: 10, bottom: 0, left: -10 }} onClick={(e) => { const p = e?.activePayload?.[0]?.payload; if (p) onBucketClick(p) }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={(b) => summary?.granularity === 'hour' ? String(b).slice(11, 16) : String(b).slice(5, 10)} tick={{ fontSize: 11 }} minTickGap={18} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} width={34} />
            <ChartTooltip content={<ChartTooltipContent labelFormatter={(_, p) => { const b = p?.[0]?.payload?.bucket; return b ? formatDate(String(b).length === 10 ? b + 'T00:00:00' : b) : '' }} />} />
            <Bar dataKey="sent" name="sent" stackId="s" fill={SERIES_COLORS.sent} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="failed" name="failed" stackId="s" fill={SERIES_COLORS.failed} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="pending" name="pending" stackId="s" fill={SERIES_COLORS.pending} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} />
            <Bar dataKey="skipped" name="skipped" stackId="s" fill={SERIES_COLORS.skipped} cursor="pointer" onClick={(d) => onBucketClick(d?.payload || d)} radius={[3, 3, 0, 0]} />
          </BarChart>
        </ChartContainer>
      </Card>

      {/* Kırılım — tek kart, sekmeler; kart düzeyinde daraltılır */}
      {/* Card forwardRef taşımaz → Collapsible kendi kabını çizer (asChild YOK; React 18 ref uyarısı) */}
      <Collapsible open={bdOpen} onOpenChange={setBdOpen} data-slot="pl-breakdown" className="min-w-0">
        <Card className="min-w-0 gap-0 overflow-hidden p-0 shadow-none">
          <CollapsibleTrigger className={cn('flex min-h-11 w-full cursor-pointer items-center gap-2 px-3.5 py-2.5 text-left hover:bg-muted/40', bdOpen && 'border-b')}>
            <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 transition-transform motion-reduce:transition-none', !bdOpen && '-rotate-90')} />
            <span className="text-sm font-semibold">{t('pl.breakdown')}</span>
            {bdActive && <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-primary"><Filter aria-hidden="true" className="size-3" />{t('pl.bdActive')}</span>}
            <span className="ml-auto hidden text-xs text-muted-foreground sm:inline">{t('pl.bdHint')}</span>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <Tabs value={bdTab} onValueChange={setBdTab} className="gap-0">
              <div className="overflow-x-auto px-3 pt-2.5 [scrollbar-width:thin]">
                <TabsList className="w-max">
                  {bdTabs.map((x) => (
                    <TabsTrigger key={x.key} value={x.key} data-tab={x.key} className="gap-1.5 px-3 pointer-coarse:h-10">
                      {x.label}<span className="rounded-full bg-muted px-1.5 text-[11px] font-semibold text-muted-foreground tabular-nums">{x.rows.length}</span>
                    </TabsTrigger>
                  ))}
                </TabsList>
              </div>
              <TabsContent value="team" className="pt-1.5">
                <PushBreakdownPanel bare title={t('sml.byTeam')} rows={teams} keyOf={(x) => x.team_id ?? '-'} status={f.status} onFilter={applyBreakdown}
                  label={(x) => x.team_id != null ? <TeamBadge teamId={x.team_id} teamName={x.team_name || `#${x.team_id}`} /> : <span className="text-muted-foreground">{t('sml.noTeam')}</span>}
                  dim={(x) => x.team_id != null ? { teamId: String(x.team_id) } : null} isDimActive={(x) => x.team_id != null && String(x.team_id) === String(f.teamId)} />
              </TabsContent>
              <TabsContent value="user" className="pt-1.5">
                <PushBreakdownPanel bare title={t('pl.topUsers')} rows={summary?.top_users || []} keyOf={(x) => x.username} status={f.status} onFilter={applyBreakdown}
                  label={(x) => <UserBadge username={x.username} displayName={x.display_name} inline size="sm" />}
                  dim={(x) => ({ username: x.username })} isDimActive={(x) => !!x.username && f.username === x.username} />
              </TabsContent>
              <TabsContent value="monitor" className="pt-1.5">
                <PushBreakdownPanel bare title={t('pl.topMonitors')} rows={summary?.top_monitors || []} keyOf={(x) => `${x.monitor_type}:${x.monitor_name}`} status={f.status} onFilter={applyBreakdown}
                  label={(x) => <span className="flex min-w-0 items-center gap-2"><TypeBadge>{x.monitor_type}</TypeBadge><span className="truncate" title={x.monitor_name || ''}>{x.monitor_name}</span></span>}
                  dim={(x) => ({ q: x.monitor_name || '' })} isDimActive={(x) => !!x.monitor_name && f.q === x.monitor_name} />
              </TabsContent>
              <TabsContent value="level" className="pt-1.5">
                <PushBreakdownPanel bare title={t('pl.byLevel')} rows={summary?.levels || []} keyOf={(x) => x.level ?? '-'} status={f.status} onFilter={applyBreakdown}
                  label={(x) => x.level ? <LevelBadge level={x.level} /> : <span className="text-muted-foreground">—</span>}
                  dim={(x) => x.level ? { level: x.level } : null} isDimActive={(x) => !!x.level && f.level === x.level} />
              </TabsContent>
            </Tabs>
          </CollapsibleContent>
        </Card>
      </Collapsible>

      {/* Liste */}
      <div ref={listRef} className="scroll-mt-24">
        <Card data-slot="pl-list" className="min-w-0 gap-0 overflow-hidden p-0 shadow-none">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b px-3.5 py-2.5">
            <h3 className="m-0 text-sm font-semibold">{t('pl.list.title')}</h3>
            {!tableMode && rows.total > 0 && (
              <div className="min-w-0 [&>*]:w-full">
                <NativeSelect value={f.sort} onChange={(e) => patch({ sort: e.target.value })} aria-label={t('pl.sort.label')} className="w-full" data-slot="pl-sort">
                  {sortOptions.map((o) => <NativeSelectOption key={o.value} value={o.value}>{o.label}</NativeSelectOption>)}
                </NativeSelect>
              </div>
            )}
          </div>
          <div ref={measureRef} className="@container/pl min-w-0">
            {loading && !summary ? <LoadingBlock label={t('sys.loading')} fullWidth /> : rows.total === 0 ? (
              <StatusBlock tone="neutral" icon={Webhook} title={t('health.smtpLogNoMatch')} description={activeCount > 0 ? t('sml.noMatchHint') : t('pl.noRowsHint')}
                actions={activeCount > 0 ? <Button type="button" variant="outline" onClick={clearAll}>{t('pl.clearAll')}</Button> : null} />
            ) : tableMode ? (
              <PushLogTable rows={rows.items} t={t} sort={f.sort} onSort={setSort} canRequeue={canRequeue} busy={busy} onOpen={setDetailId} onRequeue={requeue} />
            ) : (
              <PushLogCards rows={rows.items} t={t} canRequeue={canRequeue} busy={busy} onOpen={setDetailId} onRequeue={requeue} />
            )}
          </div>
          {rows.total > 0 && (
            <div className="border-t px-3 py-2">
              <PaginationBar {...sp.bar} />
            </div>
          )}
        </Card>
      </div>

      <PushLogDetail open={!!detailId} detail={detail && detail.id === detailId ? detail : null} t={t} ids={ids}
        onClose={() => setDetailId(null)} onPick={setDetailId} canRequeue={canRequeue} busy={busy} onRequeue={requeue}
        onOpenAlert={(alertId) => { setDetailId(null); navigateTo('alerthistory', alertNavParams({ id: alertId })) }} />
      {!canRequeue && rows.items.some((x) => x.kind === 'FAILED' || x.kind === 'BLOCKED') && (
        <p className="m-0 inline-flex items-center gap-1.5 text-xs text-muted-foreground"><ShieldAlert aria-hidden="true" className="size-3.5" /> {t('pl.requeueAdminOnly')}</p>
      )}
    </div>
  )
}
