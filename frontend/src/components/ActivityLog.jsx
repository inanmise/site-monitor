import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { Fragment, useEffect, useState, useCallback, useMemo, useRef } from 'react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { useServerPagination } from '../hooks/useServerPagination.js'
import {
  Shield, Activity, Globe, Server, Radio, Share2, Search, CalendarClock, ScanSearch, FlaskConical, Gauge,
  CheckCircle, AlertTriangle, XCircle, HelpCircle, ChevronDown, ChevronRight,
  Download, X, RefreshCw, Clock, User, Inbox, ExternalLink } from 'lucide-react'
import { csvCell } from '../utils/csv.js'
import { downloadCsv } from '../utils/csvExport.js'
import { relTimeOrRaw } from '../utils/relativeTime.js'
import TeamBadge from './ui/TeamBadge.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { navigateTo } from '../utils/navigate.js'
import { useIsMobile } from '../hooks/use-mobile.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Aktivite satırı → ilgili izleme sekmesi (2026-09-20, kullanıcı bildirimi: "ping logunu görünce o ping izlemesine
 * gitmeliyim"). Backend MonitorRefResolver ile aynı kural: sertifika → Genel Bakış (?q=alan), uptime → Durum İzleme
 * (?q=hedef), diğer türler kendi sekmesine ?monitor=id ile (scripted param taşımaz). Bilinmeyen tür → null.
 */
const MONITOR_TABS = { DOMAIN: 'domain', HTTP: 'http', PORT: 'port', PING: 'ping', DNS: 'dns', KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed', SCRIPTED: 'scripted' }
export function activityTarget(row) {
  if (!row) return null
  const type = String(row.monitor_type || '').toUpperCase()
  const host = row.target ? String(row.target).replace(/^https?:\/\//i, '').split('/')[0].split(':')[0] : ''
  // Genel Bakış canlı geçişte `domain` paramını dinler (App onNav → setSearch); `q` yalnız ilk yüklemede okunur (QA ISSUE-001).
  if (type === 'CERT') return { tab: 'dashboard', params: host ? { domain: host } : undefined }
  if (type === 'UPTIME') return { tab: 'uptime', params: host ? { q: host } : undefined }
  const tab = MONITOR_TABS[type]
  if (!tab) return null
  return { tab, params: row.monitor_id != null && type !== 'SCRIPTED' ? { monitor: row.monitor_id } : undefined }
}

// Her izleme türünün ayırt edici ikon + rengi (badge).
const TYPES = [
  { key: 'CERT',    Icon: Shield,        color: '#7c3aed' },
  { key: 'DOMAIN',  Icon: CalendarClock, color: '#be123c' },
  { key: 'HTTP',    Icon: Globe,         color: '#2563eb' },
  { key: 'UPTIME',  Icon: Activity,      color: '#059669' },
  { key: 'PORT',    Icon: Server,        color: '#0d9488' },
  { key: 'PING',    Icon: Radio,         color: '#0891b2' },
  { key: 'DNS',     Icon: Share2,        color: '#6d28d9' },
  { key: 'KEYWORD', Icon: Search,        color: '#d97706' },
  { key: 'PAGE',    Icon: ScanSearch,    color: '#0d9488' },
  { key: 'PAGESPEED', Icon: Gauge,      color: '#c2410c' },
  // Backend SCRIPTED kaydi yaziyor (ActivityLogService.SCRIPTED) ama burada karsiligi yoktu:
  // satirlar gri "?" rozetiyle cikiyor ve tur filtresi cipi hic uretilmiyordu.
  { key: 'SCRIPTED',Icon: FlaskConical,  color: '#7e22ce' },
]
const TYPE_MAP = Object.fromEntries(TYPES.map((t) => [t.key, t]))

// Sonuç durumu → ikon + mürekkep (jeton sınıfı; koyu temada karşılıklı).
const STATUS_META = {
  SUCCESS: { Icon: CheckCircle,   ink: 'text-success' },
  WARNING: { Icon: AlertTriangle, ink: 'text-amber-600 dark:text-amber-400' },
  ERROR:   { Icon: XCircle,       ink: 'text-destructive' },
  TIMEOUT: { Icon: XCircle,       ink: 'text-destructive' },
  UNKNOWN: { Icon: HelpCircle,    ink: 'text-muted-foreground' },
}
const UNKNOWN_TYPE = { Icon: HelpCircle, color: '#71717a' }

/** İzleme türü rozeti — türün kalıcı renk kimliği (ikon + ad); zemin rengin %10'u. */
function TypeBadge({ type }) {
  const tm = TYPE_MAP[type] || UNKNOWN_TYPE
  return (
    <Badge variant="outline" data-type={type} className="shrink-0 gap-1 rounded-full border-transparent px-2 text-[0.72em] font-bold tracking-wide"
      style={{ color: tm.color, background: tm.color + '18' }}>
      <tm.Icon aria-hidden="true" /> {type}
    </Badge>
  )
}

/** Sonuç özeti (ikon + yerelleştirilmiş özet). */
function ResultSummary({ row, t, className }) {
  const sm = STATUS_META[row.result_status] || STATUS_META.UNKNOWN
  return (
    <span data-slot="act-result" className={cn('inline-flex min-w-0 items-center gap-1 font-semibold tabular-nums', sm.ink, className)}>
      <sm.Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span className="min-w-0 break-words">{localizeSummary(row.result_summary, t) || t('act.st.' + row.result_status)}</span>
    </span>
  )
}
const STATUSES = ['SUCCESS', 'WARNING', 'ERROR', 'UNKNOWN']
const RANGES = ['today', '24h', '7d', 'all']

/** range → {from,to} ISO (UTC, yyyy-MM-ddTHH:mm:ss) — backend string karşılaştırmasıyla uyumlu. */
function rangeToFromTo(range) {
  const iso = (d) => d.toISOString().slice(0, 19)
  const now = Date.now()
  if (range === 'today') { const d = new Date(); d.setUTCHours(0, 0, 0, 0); return { from: iso(d), to: null } }
  if (range === '24h') return { from: iso(new Date(now - 24 * 3600 * 1000)), to: null }
  if (range === '7d')  return { from: iso(new Date(now - 7 * 24 * 3600 * 1000)), to: null }
  return { from: null, to: null }
}

// Filtreler URL query'sine yansır (paylaşılabilir/yenilenebilir); app'in diğer paramları (tab vb.) korunur.
function readParams() {
  const p = new URLSearchParams(window.location.search)
  return {
    types: (p.get('atype') || '').split(',').filter(Boolean),
    status: p.get('astatus') || '',
    range: RANGES.includes(p.get('arange')) ? p.get('arange') : '24h',
    q: p.get('aq') || '',
  }
}
function writeParams(f) {
  const p = new URLSearchParams(window.location.search)
  f.types.length ? p.set('atype', f.types.join(',')) : p.delete('atype')
  f.status ? p.set('astatus', f.status) : p.delete('astatus')
  f.range && f.range !== '24h' ? p.set('arange', f.range) : p.delete('arange')
  f.q ? p.set('aq', f.q) : p.delete('aq')
  const s = p.toString()
  window.history.replaceState(null, '', `${window.location.pathname}${s ? '?' + s : ''}`)
}

function exportCsv(rows) {
  const header = ['time', 'type', 'name', 'target', 'action', 'status', 'summary', 'error']
  const lines = [header.join(',')]
  rows.forEach((r) => lines.push([
    r.activity_time, r.monitor_type, r.monitor_name, r.target, r.action,
    r.result_status, r.result_summary, r.error_message,
  ].map(csvCell).join(',')))
  // Dosya bugünkü gibi: BOM + LF satır sonu (bilinçli olarak CRLF'e çevrilmedi). İndirme ortak (öneri 29).
  downloadCsv('activity-log.csv', '﻿' + lines.join('\n'))
}

/**
 * Sunucu özeti yalnız Türkçe üretir ("0 kırık · 12ms", "3 istek", "yüklenemedi"); İngilizce arayüzde
 * jetonlar sözlükten çevrilir (QA 2026-09-12, ISSUE-010). Türkçede sözlük değeri = jetonun kendisi.
 */
const SUMMARY_TOKENS = [['yapılandırma hatası', 'act.tok.configError'], ['yüklenemedi', 'act.tok.loadFailed'],
  ['zaman aşımı', 'act.tok.timeout'], ['kırık', 'act.tok.broken'], ['istek', 'act.tok.requests']]
export function localizeSummary(summary, t) {
  if (!summary) return summary
  let s = String(summary)
  for (const [tr, key] of SUMMARY_TOKENS) s = s.split(tr).join(t(key))
  return s
}

export default function ActivityLog({ refreshTrigger }) {
  const t = useT()
  // Telefonda satırlar kart listesi (yapı farkı → useIsMobile; jsdom medya sorgusu uygulamaz, TEK varyant çizilir)
  const phone = useIsMobile()
  const [filters, setFilters] = useState(readParams)
  const [qInput, setQInput]   = useState(filters.q)
  const [data, setData]       = useState([])
  // Zaman grupları + ardışık aynı-hedef katlama (2026-09-12, #22). Katlama: aynı monitör türü + hedef, arka
  // arkaya ≥ 3 satır → tek "N kontrol" satırı; kullanıcı açınca (unfolded) satırlar tek tek döner.
  const [unfolded, setUnfolded] = useState(() => new Set())
  const groupedFeed = useMemo(() => {
    const out = []
    const now = new Date(); const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const startYesterday = startToday - 86400000, startWeek = startToday - 6 * 86400000
    const groupOf = (iso) => { const ms = new Date(iso && !iso.endsWith('Z') && !iso.includes('+') ? iso + 'Z' : iso).getTime()
      return !Number.isFinite(ms) ? 'older' : ms >= startToday ? 'today' : ms >= startYesterday ? 'yesterday' : ms >= startWeek ? 'week' : 'older' }
    let lastGroup = null
    let i = 0
    while (i < data.length) {
      const row = data[i]
      const g = groupOf(row.activity_time)
      if (g !== lastGroup) { const count = data.filter((r) => groupOf(r.activity_time) === g).length; out.push({ kind: 'head', key: 'h-' + g, group: g, count }); lastGroup = g }
      let j = i + 1
      while (j < data.length && data[j].monitor_type === row.monitor_type && data[j].target === row.target && groupOf(data[j].activity_time) === g) j++
      const run = data.slice(i, j)
      const key = 'f-' + row.monitor_type + '-' + row.target + '-' + row.id
      if (run.length >= 3 && !unfolded.has(key)) out.push({ kind: 'fold', key, rows: run })
      else run.forEach((r) => out.push({ kind: 'row', key: 'r-' + r.id, row: r }))
      i = j
    }
    return out
  }, [data, unfolded])

  // Sayfalama standardı (2026-09-26): el yapımı "Önceki/Sonraki" yerine useServerPagination + PaginationBar.
  // Süzgeç değişince sayfa 1 (değer karşılaştırmalı, mount'ta değil); API 0-tabanlı.
  const sp = useServerPagination({ listKey: 'activity-log', preset: 'page', resetDeps: [filters], apiBase: 0 })
  const [summary, setSummary] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(false)
  const [openId, setOpenId]   = useState(null)
  const [details, setDetails] = useState({})   // id → {data, recent}

  // Ortak yardımcı (utils/relativeTime.relTimeOrRaw — öneri 29): boş damga "—", çözülemeyen damga olduğu gibi; çıktı aynı.
  const rel = useCallback((iso) => relTimeOrRaw(iso, t), [t])

  const params = useMemo(() => {
    const { from, to } = rangeToFromTo(filters.range)
    return { type: filters.types.join(','), status: filters.status, from, to, q: filters.q }
  }, [filters])

  // Yarış koruması: sayfa/süzgeç hızlı değişirse yalnız SON isteğin yanıtı uygulanır.
  const loadSeq = useRef(0)
  const { apiPage, pageSize } = sp
  const load = useCallback((silent) => {
    const seq = ++loadSeq.current
    if (!silent) setLoading(true)
    setError(false)
    api.getActivity({ ...params, page: apiPage, size: pageSize })
      .then((res) => {
        if (seq !== loadSeq.current) return
        if (res?.success) { setData(res.data); sp.bind(res) }
        else setError(true)
      })
      .catch(() => { if (seq === loadSeq.current) setError(true) })
      .finally(() => { if (seq === loadSeq.current) setLoading(false) })
    // Özet de aynı sıra damgasıyla (2026-10-09): eski süzgecin geç gelen özeti yeni listenin sayaçlarını ezmesin
    api.getActivitySummary(params).then((res) => { if (res?.success && seq === loadSeq.current) setSummary(res) }).catch(() => {})
  }, [params, apiPage, pageSize]) // eslint-disable-line react-hooks/exhaustive-deps

  // Süzgeç değişince URL'yi güncelle + açık detayı kapat; sayfa sıfırlamasını useServerPagination yapar.
  useEffect(() => { writeParams(filters); setOpenId(null) }, [filters])
  useEffect(() => { load(false) }, [load])
  useEffect(() => { if (refreshTrigger) load(true) }, [refreshTrigger]) // eslint-disable-line react-hooks/exhaustive-deps

  // Görünür sekmede, 1. sayfadayken sessiz otomatik tazeleme (gizli sekmede durur).
  useVisibleInterval(() => { if (sp.page === 1) load(true) }, 30000, false)

  const toggleType = (key) =>
    setFilters((f) => ({ ...f, types: f.types.includes(key) ? f.types.filter((x) => x !== key) : [...f.types, key] }))
  const applySearch = () => setFilters((f) => ({ ...f, q: qInput.trim() }))
  const clearAll = () => { setQInput(''); setFilters({ types: [], status: '', range: '24h', q: '' }) }

  const openDetail = (row) => {
    if (openId === row.id) { setOpenId(null); return }
    setOpenId(row.id)
    if (!details[row.id]) {
      api.getActivityDetail(row.id).then((res) => {
        if (res?.success) setDetails((d) => ({ ...d, [row.id]: { data: res.data, recent: res.recent || [] } }))
      }).catch(() => {})
    }
  }

  const anyFilter = filters.types.length > 0 || filters.status || filters.q || filters.range !== '24h'

  // Özet kalemleri = durum süzgeci (MonitorStatsBar). Etkin kart tekrar tıklanınca süzgeç kalkar; "Toplam" temizler.
  // Ad her zaman etiketi taşır ("Hata — Filtreyi kaldır"): etkin kartın adı yalnız "Filtreyi kaldır" olsaydı hangi
  // kartın etkin olduğu duyulmazdı.
  const statItems = [
    ['ALL', summary?.total ?? '—', t('act.sum.total'), Activity, 'total'],
    ['SUCCESS', summary?.success_count ?? 0, t('act.st.SUCCESS'), CheckCircle, 'valid'],
    ['WARNING', summary?.warning ?? 0, t('act.st.WARNING'), AlertTriangle, 'warning'],
    ['ERROR', summary?.error ?? 0, t('act.st.ERROR'), XCircle, 'critical'],
  ].map(([key, value, label, Icon, cls]) => {
    const on = (filters.status || 'ALL') === key
    return { key, value, label, Icon, cls, hint: t('act.sumFilterHint'),
      tip: on && key !== 'ALL' ? t('a11y.rowAction', label, t('mondash.clearTip')) : t('mondash.filterTip', label) }
  })
  const onStat = (key) => setFilters((f) => ({ ...f, status: key === 'ALL' || f.status === key ? '' : key }))

  const toggleFold = (key) => setUnfolded((u) => { const n = new Set(u); n.add(key); return n })
  const rowKey = (e, fn) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); fn() } }
  const ROW_CLICK = 'cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary'
  const TH = 'h-9 px-2.5 text-[0.76em] font-semibold tracking-wide text-muted-foreground uppercase'
  const teamOf = (r, isStatic = true) => (r.team_id != null
    ? <TeamBadge teamId={r.team_id} teamName={r.team_name} static={isStatic} />
    : <span className="text-muted-foreground">—</span>)
  const time = (iso, label) => <time dateTime={iso} title={formatDateSec(iso)} className="whitespace-nowrap tabular-nums">{label ?? rel(iso)}</time>

  /** Açılan satırın ayrıntısı — masaüstü tablo ve telefon kartı AYNI gövdeyi kullanır. */
  const detail = (row) => {
    const d = details[row.id]
    const sm = STATUS_META[row.result_status] || STATUS_META.UNKNOWN
    const fact = (label, value) => (
      <div className="flex min-w-0 flex-col gap-0.5">
        <dt className="text-[0.8em] tracking-wide text-muted-foreground uppercase">{label}</dt>
        <dd className="min-w-0 [overflow-wrap:anywhere]">{value}</dd>
      </div>
    )
    return (
      <div data-slot="act-detail" className="flex min-w-0 flex-col gap-2.5 text-[0.86em]">
        <dl className="grid grid-cols-2 gap-x-5 gap-y-2 sm:grid-cols-[repeat(auto-fit,minmax(150px,1fr))]">
          {fact(t('act.d.target'), <span className="font-mono text-xs break-all">{row.target || '—'}</span>)}
          {fact(t('act.d.status'), <span className={cn('font-semibold', sm.ink)}>{t('act.st.' + row.result_status)}</span>)}
          {fact(t('act.d.time'), formatDateSec(row.activity_time))}
          {fact(t('act.d.actor'), <span className="inline-flex items-center gap-1"><User aria-hidden="true" className="size-3" /> {row.actor || 'scheduler'}</span>)}
          {fact(t('act.d.team'), teamOf(row, false))}
          {row.response_ms != null && fact(t('act.d.response'), `${row.response_ms} ms`)}
          {row.days_remaining != null && fact(t('act.d.days'), row.days_remaining)}
        </dl>
        {row.error_message && (
          <AlertBanner tone="danger" icon={XCircle} className="mb-0">
            {row.error_message}{row.error_class ? ` (${row.error_class})` : ''}
          </AlertBanner>
        )}
        {row.result_detail && (
          <pre className="m-0 max-h-[200px] overflow-auto rounded-md border bg-background px-2.5 py-2 font-mono text-[0.85em] break-all whitespace-pre-wrap">{row.result_detail}</pre>
        )}
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex items-center gap-1.5 text-[0.8em] tracking-wide text-muted-foreground uppercase"><Clock aria-hidden="true" className="size-3" /> {t('act.miniHistory')}</div>
          {d ? (d.recent.length ? (
            <ul className="flex list-none flex-col gap-1 p-0">
              {d.recent.map((h) => {
                const hs = STATUS_META[h.result_status] || STATUS_META.UNKNOWN
                return (
                  <li key={h.id} className="flex min-w-0 items-center gap-2.5">
                    <hs.Icon aria-hidden="true" className={cn('size-3 shrink-0', hs.ink)} />
                    <span className="min-w-0 flex-1 tabular-nums [overflow-wrap:anywhere]">{localizeSummary(h.result_summary, t) || h.result_status}</span>
                    <span className="shrink-0 text-[0.85em] text-muted-foreground">{time(h.activity_time)}</span>
                  </li>
                )
              })}
            </ul>
          ) : <p className="text-[0.85em] text-muted-foreground italic">{t('act.noHistory')}</p>)
            : <p className="text-[0.85em] text-muted-foreground italic">{t('act.loading')}</p>}
        </div>
      </div>
    )
  }

  /** "İzlemeye git" — satırın adı + eylem (satırlar ayırt edilir); telefonda metinli, ≥ 40 px. */
  const goButton = (row, dest, withText = false) => (
    <Button type="button" variant={withText ? 'outline' : 'ghost'} size={withText ? 'sm' : 'icon-sm'}
      className={cn(withText ? 'h-10 shrink-0' : 'text-muted-foreground hover:text-primary')}
      title={t('act.goMonitor')} aria-label={t('act.goMonitorFor', row.monitor_name || row.target || '')}
      onClick={(e) => { e.stopPropagation(); navigateTo(dest.tab, dest.params) }}>
      <ExternalLink aria-hidden="true" />{withText && <span>{t('act.goMonitor')}</span>}
    </Button>
  )

  // ── Masaüstü / tablet: hizalı sütunlar (shadcn Table). Düşük öncelikli sütunlar dar ekranda gizli. ──
  const desktopFeed = (
    <div className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-8')}><span className="sr-only">{t('act.d.status')}</span></TableHead>
            <TableHead className={TH}>{t('act.d.type')}</TableHead>
            <TableHead className={TH}>{t('act.d.monitor')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('act.d.team')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('act.d.action')}</TableHead>
            <TableHead className={TH}>{t('act.d.result')}</TableHead>
            <TableHead className={cn(TH, 'text-right')}>{t('act.d.time')}</TableHead>
            <TableHead className={cn(TH, 'w-10')}><span className="sr-only">{t('act.goMonitor')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {groupedFeed.map((entry) => {
            // Zaman grubu başlığı (2026-09-12, #22): bugün / dün / bu hafta / daha eski
            if (entry.kind === 'head') {
              return (
                <TableRow key={entry.key} data-slot="act-group-head" className="bg-muted/40 hover:bg-muted/40">
                  <TableCell colSpan={8} className="py-1.5 text-[0.76em] font-bold tracking-wider text-muted-foreground uppercase">
                    {t('act.group.' + entry.group)} <Badge variant="secondary" className="ml-1 h-4 px-1.5 text-[10px] tabular-nums">{entry.count}</Badge>
                  </TableCell>
                </TableRow>
              )
            }
            // Katlanmış ardışık koşu: aynı hedefin peş peşe N kontrolü tek satırda; tıklayınca açılır
            if (entry.kind === 'fold') {
              const r0 = entry.rows[0]
              const errs = entry.rows.filter((r) => r.result_status === 'ERROR' || r.result_status === 'TIMEOUT').length
              const unfold = () => toggleFold(entry.key)
              return (
                <TableRow key={entry.key} data-slot="act-fold" tabIndex={0} className={cn(ROW_CLICK, 'bg-primary/5 hover:bg-primary/10')}
                  aria-label={t('a11y.toggleRow', `${r0.monitor_name} · ${t('act.folded', entry.rows.length)}`)}
                  onClick={unfold} onKeyDown={(e) => rowKey(e, unfold)}>
                  <TableCell className="text-muted-foreground"><ChevronRight aria-hidden="true" className="size-3.5" /></TableCell>
                  <TableCell><TypeBadge type={r0.monitor_type} /></TableCell>
                  <TableCell>
                    <span className="block max-w-[16rem] truncate font-semibold" title={r0.monitor_name}>{r0.monitor_name}</span>
                    <span className="block max-w-[16rem] truncate font-mono text-xs text-muted-foreground" title={r0.target}>{r0.target}</span>
                  </TableCell>
                  <TableCell className="hidden xl:table-cell" data-col="team">{teamOf(r0)}</TableCell>
                  <TableCell className="hidden lg:table-cell" />
                  <TableCell className="min-w-[9rem] text-[0.9em] whitespace-normal text-primary">
                    {t('act.folded', entry.rows.length)}{errs ? <span className="font-semibold text-destructive"> · {t('act.foldedErrors', errs)}</span> : ''}
                  </TableCell>
                  <TableCell className="text-right text-[0.9em] text-muted-foreground">{time(r0.activity_time, `${rel(entry.rows[entry.rows.length - 1].activity_time)} → ${rel(r0.activity_time)}`)}</TableCell>
                  <TableCell />
                </TableRow>
              )
            }
            const row = entry.row
            const isOpen = openId === row.id
            const isError = row.result_status === 'ERROR' || row.result_status === 'TIMEOUT'
            const dest = activityTarget(row)
            const toggle = () => openDetail(row)
            return (
              <Fragment key={entry.key}>
                <TableRow data-slot="act-item" data-status={row.result_status} data-state={isOpen ? 'open' : 'closed'}
                  tabIndex={0} aria-expanded={isOpen}
                  aria-label={t('a11y.toggleRow', row.monitor_name || row.target || String(row.id))}
                  className={cn(ROW_CLICK, isError && 'bg-destructive/[0.04]', 'data-[state=open]:bg-primary/5')}
                  onClick={toggle} onKeyDown={(e) => rowKey(e, toggle)}>
                  <TableCell className="text-muted-foreground">{isOpen ? <ChevronDown aria-hidden="true" className="size-3.5" /> : <ChevronRight aria-hidden="true" className="size-3.5" />}</TableCell>
                  <TableCell><TypeBadge type={row.monitor_type} /></TableCell>
                  <TableCell>
                    {/* Ad tıklanınca izlemeye gider (2026-09-20); satırın kalanı detayı açar */}
                    {dest ? (
                      <Button type="button" variant="link" size="xs" title={t('act.goMonitor')}
                        className="h-auto max-w-[16rem] justify-start truncate p-0 text-[1em] font-semibold text-foreground decoration-dotted underline-offset-[3px] hover:text-primary"
                        onClick={(e) => { e.stopPropagation(); navigateTo(dest.tab, dest.params) }}>
                        <span className="truncate">{row.monitor_name}</span>
                      </Button>
                    ) : <span className="block max-w-[16rem] truncate font-semibold" title={row.monitor_name}>{row.monitor_name}</span>}
                    {/* Hedef adın altında (ayrı sütun olsaydı zaman sütunu 1440 px'te ekran dışına itiliyordu) */}
                    {row.target && <span className="block max-w-[16rem] truncate font-mono text-xs text-muted-foreground" title={row.target}>{row.target}</span>}
                  </TableCell>
                  <TableCell className="hidden xl:table-cell" data-col="team">{teamOf(row)}</TableCell>
                  <TableCell className="hidden text-[0.9em] whitespace-nowrap text-muted-foreground lg:table-cell">{t('act.ac.' + row.action)}</TableCell>
                  <TableCell className="min-w-[9rem] whitespace-normal"><ResultSummary row={row} t={t} className="text-[0.95em]" /></TableCell>
                  <TableCell className="text-right text-[0.9em] text-muted-foreground">{time(row.activity_time)}</TableCell>
                  <TableCell className="px-1">{dest && goButton(row, dest)}</TableCell>
                </TableRow>
                {isOpen && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={8} className="bg-muted/30 px-4 py-3 whitespace-normal sm:pl-10">{detail(row)}</TableCell>
                  </TableRow>
                )}
              </Fragment>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )

  // ── Telefon: kart listesi — üstte tür + ad + göreli zaman, altında hedef, sonuç + eylem; alt şeritte takım +
  // "İzlemeye git". Metinler üst üste binmez (min-w-0 + truncate/sarma); sol renk şeridi YOK, hata kartı TÜM
  // çerçeveyle belirtilir. ──
  const mobileFeed = (
    <ul className="flex list-none flex-col gap-2 p-0">
      {groupedFeed.map((entry) => {
        if (entry.kind === 'head') {
          return (
            <li key={entry.key} data-slot="act-group-head" className="mt-1 flex items-center gap-1.5 px-1 text-[0.76em] font-bold tracking-wider text-muted-foreground uppercase first:mt-0">
              {t('act.group.' + entry.group)} <Badge variant="secondary" className="h-4 px-1.5 text-[10px] tabular-nums">{entry.count}</Badge>
            </li>
          )
        }
        if (entry.kind === 'fold') {
          const r0 = entry.rows[0]
          const errs = entry.rows.filter((r) => r.result_status === 'ERROR' || r.result_status === 'TIMEOUT').length
          return (
            <li key={entry.key} data-slot="act-fold" className="min-w-0 overflow-hidden rounded-lg border border-dashed border-primary/40 bg-primary/5">
              <Button type="button" variant="ghost" onClick={() => toggleFold(entry.key)}
                className="h-auto min-h-11 w-full flex-col items-stretch gap-1 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal">
                <span className="flex min-w-0 items-center gap-2">
                  <TypeBadge type={r0.monitor_type} />
                  <span className="min-w-0 flex-1 truncate font-semibold">{r0.monitor_name}</span>
                  <ChevronDown aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                </span>
                <span className="text-[0.84em] text-primary">
                  {t('act.folded', entry.rows.length)}{errs ? <span className="font-semibold text-destructive"> · {t('act.foldedErrors', errs)}</span> : ''}
                </span>
                <span className="text-[0.78em] text-muted-foreground">{rel(entry.rows[entry.rows.length - 1].activity_time)} → {rel(r0.activity_time)}</span>
              </Button>
            </li>
          )
        }
        const row = entry.row
        const isOpen = openId === row.id
        const isError = row.result_status === 'ERROR' || row.result_status === 'TIMEOUT'
        const dest = activityTarget(row)
        return (
          <li key={entry.key} data-slot="act-item" data-status={row.result_status} data-state={isOpen ? 'open' : 'closed'}
            className={cn('min-w-0 overflow-hidden rounded-lg border bg-card', isError && 'border-destructive/50', isOpen && 'border-primary/60')}>
            <Button type="button" variant="ghost" aria-expanded={isOpen}
              className="h-auto w-full flex-col items-stretch gap-1.5 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal"
              onClick={() => openDetail(row)}>
              <span className="flex min-w-0 items-center gap-2">
                <TypeBadge type={row.monitor_type} />
                <span className="min-w-0 flex-1 truncate font-semibold">{row.monitor_name}</span>
                <span className="shrink-0 text-[0.78em] text-muted-foreground">{time(row.activity_time)}</span>
                {isOpen ? <ChevronDown aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> : <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
              </span>
              {row.target && <span className="block min-w-0 truncate font-mono text-[0.78em] text-muted-foreground">{row.target}</span>}
              <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-[0.84em]">
                <ResultSummary row={row} t={t} />
                <span className="text-muted-foreground">· {t('act.ac.' + row.action)}</span>
              </span>
            </Button>
            <div className="flex min-w-0 items-center justify-between gap-2 border-t px-3 py-1.5">
              <span className="min-w-0 truncate" data-col="team">{teamOf(row)}</span>
              {dest && goButton(row, dest, true)}
            </div>
            {isOpen && <div className="border-t bg-muted/30 px-3 py-3">{detail(row)}</div>}
          </li>
        )
      })}
    </ul>
  )

  return (
    <div data-slot="activity-log" className="flex min-w-0 flex-col gap-3">
      {/* Özet — tıklanabilir sayım kartları (durum süzgeci); son aktivite zamanı altta */}
      <div className="flex min-w-0 flex-col gap-1">
        <MonitorStatsBar items={statItems} activeFilter={filters.status || 'ALL'} onStatClick={onStat} />
        {summary?.last_activity && (
          <p className="-mt-2 flex items-center gap-1.5 text-[0.82em] text-muted-foreground">
            <Clock aria-hidden="true" className="size-3" /> {t('act.lastActivity')}: {formatDateSec(summary.last_activity)}
          </p>
        )}
      </div>

      {/* Süzgeç çubuğu — mobil-önce: tür çipleri sarar; aralık/durum/arama telefonda alt alta, geniş ekranda tek satır */}
      <div className="flex min-w-0 flex-col gap-2.5">
        <div className="flex flex-wrap gap-1.5" role="group" aria-label={t('act.typeFilter')}>
          {TYPES.map(({ key, Icon, color }) => {
            const on = filters.types.includes(key)
            return (
              <Button key={key} type="button" variant="outline" size="sm" aria-pressed={on} onClick={() => toggleType(key)}
                className="h-8 rounded-full px-2.5 text-[0.8em] font-medium text-muted-foreground pointer-coarse:h-10"
                style={on ? { borderColor: color, color, background: color + '18' } : undefined}>
                <Icon aria-hidden="true" className="size-3.5" /> {key}
              </Button>
            )
          })}
        </div>
        <div className="flex flex-col gap-2 md:flex-row md:flex-wrap md:items-center">
          <SegmentedControl value={filters.range} onChange={(rg) => setFilters((f) => ({ ...f, range: rg }))}
            ariaLabel={t('act.rangeFilter')} className="max-w-full flex-wrap"
            options={RANGES.map((rg) => ({ value: rg, label: t('act.range.' + rg) }))} />
          {/* NativeSelect sarmalayıcısı `w-fit` — telefonda tam genişlik için dış kapta *:w-full */}
          <div className="w-full *:w-full md:w-44">
            <NativeSelect value={filters.status} aria-label={t('act.d.status')}
              onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value }))}>
              <NativeSelectOption value="">{t('act.allStatuses')}</NativeSelectOption>
              {STATUSES.map((s) => <NativeSelectOption key={s} value={s}>{t('act.st.' + s)}</NativeSelectOption>)}
            </NativeSelect>
          </div>
          <InputGroup className="w-full md:max-w-sm md:flex-1">
            <InputGroupInput type="search" placeholder={t('act.searchPh')} value={qInput} aria-label={t('act.searchPh')}
              onChange={(e) => setQInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') applySearch() }} />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="sm" variant="secondary" onClick={applySearch}>{t('act.search')}</InputGroupButton>
            </InputGroupAddon>
          </InputGroup>
          <div className="flex flex-wrap items-center gap-2 md:ml-auto">
            {anyFilter && (
              <Button type="button" variant="ghost" size="sm" className="text-muted-foreground hover:text-destructive" onClick={clearAll}>
                <X aria-hidden="true" /> {t('act.clearFilter')}
              </Button>
            )}
            <Button type="button" variant="secondary" size="icon" onClick={() => load(false)} title={t('act.refresh')} aria-label={t('act.refresh')}>
              <RefreshCw aria-hidden="true" />
            </Button>
            <Button type="button" variant="secondary" onClick={() => exportCsv(data)} disabled={!data.length} title={t('act.exportCsv')}>
              <Download aria-hidden="true" /> CSV
            </Button>
          </div>
        </div>
      </div>

      {/* Ana liste — yükleme göstergesi yalnız elde veri yokken; sayfa değişiminde liste + çubuk yerinde kalır
          (akış sıfırlanıp çubuk kaybolursa sayfa zıplıyordu) */}
      {loading && data.length === 0 ? (
        <LoadingBlock label={t('act.loading')} fullWidth />
      ) : error ? (
        <StatusBlock tone="danger" icon={XCircle} title={t('act.error')} role="alert"
          actions={<Button type="button" variant="outline" onClick={() => load(false)}><RefreshCw aria-hidden="true" /> {t('act.refresh')}</Button>} />
      ) : data.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={anyFilter ? t('act.noMatch') : t('act.empty')} description={anyFilter ? t('empty.hintFilter') : t('empty.hintActivity')} />
      ) : (
        <>
          <div data-slot="act-feed" aria-busy={loading || undefined} className={cn(loading && 'opacity-70')}>
            {phone ? mobileFeed : desktopFeed}
          </div>

          {/* Sayfalama — proje standardı (shadcn Data Table deseni) */}
          <PaginationBar {...sp.bar} />
        </>
      )}
    </div>
  )
}
