import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { Siren, Info, UserCheck, CheckCircle2, OctagonAlert, Users, UserX, RotateCcw, FilterX } from 'lucide-react'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import IncidentsHeader from './incidents/IncidentsHeader.jsx'
import IncidentsToolbar from './incidents/IncidentsToolbar.jsx'
import IncidentBoard, { BoardSkeleton } from './incidents/IncidentBoard.jsx'
import IncidentsTable, { TableSkeleton } from './incidents/IncidentsTable.jsx'
import IncidentDetailSheet from './incidents/IncidentDetailSheet.jsx'
import {
  FILTER_DEFAULTS, serverParams, clientFacet, hasClientFacet, summarize, activeFilters, teamOptions, iso24hAgo,
  filtersFromUrl, filtersToUrl, sortFromUrl, severityMeta, rowName,
} from './incidents/incidentsModel.js'

const BANNER_KEY = 'incidents-banner-dismissed'
const VIEW_KEY = 'sm.incidents.view'
/** Açık küme için tek istekte alınan en fazla satır (sunucu tavanı 200) — özet kartlarının türetilmiş sayıları buradan. */
const OPEN_SAMPLE = 200
const REFRESH_MS = 60_000

/**
 * Olaylar konsolu (2026-09-26 yeniden tasarım) — tüm izlemelerin makine-üretimi olayları: özet kartları (süzer),
 * Pano | Liste görünümü, süzgeç araç çubuğu + etkin çipler, sağdan açılan detay (zaman çizelgesi + yorum) ve
 * standart sunucu sayfalaması. Parçalar `components/incidents/*`; bu dosya veri + durum + eylemleri tutar.
 *
 * Yetki: onaylama/çözme herkes için görünür, sunucu `alerts.actions` ile keser (AlertHistory ile aynı sözleşme;
 * 403 → toast). Silme yalnız ADMIN (sunucu global yönetici ister). Uygulama düzeyi `incident` URL anahtarı
 * (e-posta derin bağlantısı) okunur ama sayfa tarafından YAZILMAZ — bağlantıyı "Bağlantıyı kopyala" üretir.
 */
export default function IncidentsPage({ systemRole, teamId }) {
  const t = useT()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const { showConfirm, showNoteConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  // Telefonda pano sekmeler, liste kartlar, süzgeçler alt Sheet (yapı farkı → useIsMobile; görünüm farkı CSS'te)
  const phone = useIsMobile()

  const [filters, setFilters] = useState(() => filtersFromUrl(readUrlParam))
  const [sort, setSort] = useState(() => sortFromUrl(readUrlParam))
  const [view, setView] = useState(() => {
    const fromUrl = readUrlParam('view')
    if (fromUrl === 'list' || fromUrl === 'board') return fromUrl
    try { return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'board' } catch { return 'board' }
  })
  const [rows, setRows] = useState([])
  const [typeCounts, setTypeCounts] = useState({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [summary, setSummary] = useState(null)          // null = henüz bilinmiyor / alınamadı → kartlar çizilmez (uydurma sayı yok)
  const [nowMs, setNowMs] = useState(Date.now())
  const [selectedId, setSelectedId] = useState(null)    // açık detay
  const [fallback, setFallback] = useState(null)        // sayfada olmayan (derin bağlantı) olay
  const [focusComposer, setFocusComposer] = useState(false)
  const [bannerOpen, setBannerOpen] = useState(() => { try { return localStorage.getItem(BANNER_KEY) !== 'true' } catch { return true } })
  const reqIdRef = useRef(0)
  const summarySeqRef = useRef(0)   // özet de yarışır (dakikalık tazeleme + teamId değişimi + elle tazeleme)

  // "Son 24 saat" sınırı istek anında hesaplanır; süzgeç/sıralama değişmedikçe sabit kalır (her saniye yeni istek YOK).
  const params = useMemo(() => serverParams(filters, sort, Date.now()), [filters, sort])
  // Sayfalama standardı: süzgeç/sıralama değişince sayfa 1 AYNI render'da; boyut ön ayardan; URL page/ps.
  const sp = useServerPagination({ listKey: 'incidents', preset: 'page', resetDeps: [params], apiBase: 0, url: { pageKey: 'page', sizeKey: 'ps' } })
  const { apiPage, pageSize } = sp
  const total = sp.total ?? 0

  useUrlQuerySync(filtersToUrl(filters, view, sort))
  useEffect(() => { try { localStorage.setItem(VIEW_KEY, view) } catch { /* yoksay */ } }, [view])

  const load = useCallback(async () => {
    const myId = ++reqIdRef.current
    setLoading(true)
    try {
      const res = await api.monitoring.incidents.list({ ...params, page: apiPage, size: pageSize })
      if (myId !== reqIdRef.current) return   // yalnız EN SON isteğin yanıtı (bayat yanıt grid'i ezmesin)
      if (res?.success) { setRows(res.data ?? []); sp.bind(res); setTypeCounts(res.type_counts ?? {}); setError(null) }
      else setError(res?.error || t('incov.loadError'))
    } catch (e) {
      if (myId === reqIdRef.current) setError(e?.message || t('incov.loadError'))
    } finally {
      if (myId === reqIdRef.current) setLoading(false)
    }
  }, [params, apiPage, pageSize]) // eslint-disable-line react-hooks/exhaustive-deps

  /** Özet: açık küme (toplam + ≤200 satır) ve son 24 saatte çözülen toplamı — süzgeçten bağımsız. */
  const loadSummary = useCallback(async () => {
    const my = ++summarySeqRef.current
    try {
      const [open, res24] = await Promise.all([
        api.monitoring.incidents.list({ status: 'ongoing', page: 0, size: OPEN_SAMPLE }),
        api.monitoring.incidents.list({ status: 'resolved', since: iso24hAgo(Date.now()), page: 0, size: 1 }),
      ])
      if (my !== summarySeqRef.current) return   // bayat yanıt — daha yeni bir özet isteği yolda
      if (!open?.success) { setSummary(null); return }
      setSummary(summarize(open.data ?? [], Number(open.total ?? 0), res24?.success ? Number(res24.total ?? 0) : null, teamId))
    } catch {
      if (my === summarySeqRef.current) setSummary(null)
    }
  }, [teamId])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadSummary() }, [loadSummary])
  const refreshAll = useCallback(() => { load(); loadSummary() }, [load, loadSummary])
  // Sekme görünürken dakikada bir tazele (arka planda durur) — konsol canlı kalsın.
  useVisibleInterval(refreshAll, REFRESH_MS, false)

  // E-posta derin bağlantısı: ?incident=<id>[&action=comment] — olay sayfalı listede olmayabilir → tekil uçtan.
  // `action` tüketilince URL'den silinir (kalsaydı her sekme geçişinde yeniden açılırdı).
  useEffect(() => {
    let id = null, action = null
    try { const p = new URLSearchParams(window.location.search); id = p.get('incident'); action = p.get('action') } catch { /* yoksay */ }
    if (!id) return undefined
    let alive = true
    api.monitoring.incidents.get(id).then((res) => {
      if (!alive) return
      if (res?.success && res.data) {
        setFallback(res.data)
        setSelectedId(String(res.data.id))
        setFocusComposer(action === 'comment')
      } else if (res && res.success === false) {
        toast.error(res.error || t('inc.deepLinkFail'))
      }
      try { const url = new URL(window.location.href); url.searchParams.delete('action'); window.history.replaceState({}, '', url) } catch { /* yoksay */ }
    }).catch(() => { if (alive) toast.error(t('inc.deepLinkFail')) })
    return () => { alive = false }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Süren olay varken süreler canlı (1 sn).
  useEffect(() => {
    if (!rows.some((r) => r.status === 'ongoing') && fallback?.status !== 'ongoing') return undefined
    const i = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(i)
  }, [rows, fallback])

  const patchFilters = useCallback((patch) => setFilters((f) => ({ ...f, ...patch })), [])
  const resetFilters = useCallback(() => setFilters({ ...FILTER_DEFAULTS }), [])
  const toggleSort = useCallback((key) => setSort((s) => (s.by === key ? { by: key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { by: key, dir: 'desc' })), [])
  const dismissBanner = () => { setBannerOpen(false); try { localStorage.setItem(BANNER_KEY, 'true') } catch { /* yoksay */ } }

  /** Satırı yerinde güncelle (iyimser güncelleme + sunucu yanıtı); derin bağlantı olayı da aynı kimlikteyse. */
  const patchRow = useCallback((id, patch) => {
    setRows((rs) => rs.map((r) => (String(r.id) === String(id) ? { ...r, ...patch } : r)))
    setFallback((f) => (f && String(f.id) === String(id) ? { ...f, ...patch } : f))
  }, [])

  const visibleRows = useMemo(() => clientFacet(rows, filters, teamId), [rows, filters, teamId])
  const teams = useMemo(() => teamOptions(rows), [rows])
  const active = activeFilters(filters)
  const selected = useMemo(() => {
    if (selectedId == null) return null
    return rows.find((r) => String(r.id) === String(selectedId)) || (fallback && String(fallback.id) === String(selectedId) ? fallback : null)
  }, [rows, fallback, selectedId])

  const openDetail = useCallback((inc) => { setFocusComposer(false); setSelectedId(String(inc.id)) }, [])
  const closeDetail = useCallback(() => { setSelectedId(null); setFocusComposer(false) }, [])

  // ── Eylemler (gerekçe zorunlu — sunucu AlertActionNote ile aynı kural; hazır çipler AlertHistory'den) ──
  const noteOpts = (extra) => ({
    noteHint: t('alh.note.hint'), noteOkText: t('alh.note.ok'), noteLabel: t('alh.note.label'), placeholder: t('alh.note.placeholder'),
    chips: [t('alh.note.chip1'), t('alh.note.chip2'), t('alh.note.chip3'), t('alh.note.chip4'), t('alh.note.chip5')],
    cancelText: t('dlg.cancel'), ...extra,
  })
  async function ack(inc) {
    const res = await showNoteConfirm(noteOpts({ title: t('incov.ackDialogTitle'), message: t('incov.ackDialogMsg', inc.monitor?.name || inc.domain || ''), variant: 'warning', confirmText: t('incov.ack') }))
    if (!res?.confirmed) return
    patchRow(inc.id, { acknowledged: true })   // iyimser
    let r
    try { r = await api.admin.acknowledgeAlert(inc.id, res.note) } catch { r = null }
    if (!r?.success) { patchRow(inc.id, { acknowledged: inc.acknowledged ?? false }); toast.error(r?.error || t('incov.actionError')); return }
    patchRow(inc.id, { acknowledged: true, acknowledged_by: r.data?.acknowledged_by ?? null, acknowledged_at: r.data?.acknowledged_at ?? null })
    toast.success(t('incov.ackDone'))
    loadSummary()
  }
  async function resolve(inc) {
    const res = await showNoteConfirm(noteOpts({ title: t('incov.resolveDialogTitle'), message: t('incov.resolveDialogMsg', inc.monitor?.name || inc.domain || ''), variant: 'success', confirmText: t('incov.resolve'), placeholder: t('alh.note.placeholderResolve') }))
    if (!res?.confirmed) return
    const nowIso = new Date().toISOString().slice(0, 19)
    patchRow(inc.id, { status: 'resolved', resolved_at: nowIso })   // iyimser
    let r
    try { r = await api.admin.resolveAlert(inc.id, res.note) } catch { r = null }
    if (!r?.success) { patchRow(inc.id, { status: inc.status, resolved_at: inc.resolved_at ?? null }); toast.error(r?.error || t('incov.actionError')); return }
    patchRow(inc.id, { status: 'resolved', resolved_at: r.data?.resolved_at || nowIso, resolved_by: r.data?.resolved_by ?? null })
    toast.success(t('incov.resolveDone'))
    loadSummary()
  }
  async function del(inc) {
    if (!await showConfirm({ title: t('incov.delete'), message: `${rowName(inc, dateLocale)}\n\n${t('incov.deleteConfirm')}`, confirmText: t('incov.delete'), variant: 'danger' })) return
    let r
    try { r = await api.monitoring.incidents.remove(inc.id) } catch { r = null }
    if (!r?.success) { toast.error(r?.error || t('incov.deleteError')); return }
    toast.success(t('incov.deleted'))
    if (String(selectedId) === String(inc.id)) closeDetail()
    refreshAll()
  }
  const onCommentDelta = (delta) => { if (selected) patchRow(selected.id, { comment_count: Math.max(0, (selected.comment_count ?? 0) + delta) }) }

  // ── Özet kartları (MonitorStatsBar) — sunucunun döndürdüğü sayılar; türetilmişler açık kümeden ──
  const tiles = useMemo(() => {
    if (!summary) return []
    const sub = summary.exact ? undefined : t('incov.tile.countedNote', summary.sampled)
    return [
      { key: 'open',       Icon: Siren,        cls: 'error',    label: t('incov.tile.open'),       value: summary.open,       hint: t('incov.tile.openHint') },
      { key: 'ack',        Icon: UserCheck,    cls: 'total',    label: t('incov.tile.ack'),        value: summary.ack,        hint: t('incov.tile.ackHint'), sub },
      { key: 'resolved24', Icon: CheckCircle2, cls: 'valid',    label: t('incov.tile.resolved24'), value: summary.resolved24, hint: t('incov.tile.resolved24Hint') },
      { key: 'critical',   Icon: OctagonAlert, cls: 'critical', label: t('incov.tile.critical'),   value: summary.critical,   hint: t('incov.tile.criticalHint'), sub },
      { key: 'unassigned', Icon: UserX,        cls: 'warning',  label: t('incov.tile.unassigned'), value: summary.unassigned, hint: t('incov.tile.unassignedHint'), sub },
      ...(teamId != null ? [{ key: 'mine', Icon: Users, cls: 'total', label: t('incov.tile.mine'), value: summary.mine, hint: t('incov.tile.mineHint'), sub }] : []),
    ]
  }, [summary, teamId, t])

  const typeLabel = (type) => (t(`incov.type.${type}`) !== `incov.type.${type}` ? t(`incov.type.${type}`) : type)
  const labelsFor = (f) => {
    switch (f.key) {
      case 'stat': return f.value === 'resolved' ? t('incov.resolved') : t(`incov.tile.${f.value}`)
      case 'rootCause': return typeLabel(f.value)
      case 'q': return t('incov.chip.search', f.value)
      case 'since': return t('incov.chip.since', f.value)
      case 'until': return t('incov.chip.until', f.value)
      case 'level': return t(severityMeta(f.value).key)
      case 'team': return t('incov.chip.team', teams.find((tm) => tm.id === String(f.value))?.name ?? f.value)
      default: return f.value
    }
  }

  const initial = loading && rows.length === 0 && !error
  const facetNote = hasClientFacet(filters) && !loading && visibleRows.length < rows.length

  return (
    <div data-slot="incidents-page" className="flex min-w-0 flex-col gap-3">
      <IncidentsHeader summary={summary} view={view} onView={setView} onRefresh={refreshAll} loading={loading} />

      {bannerOpen && (
        <AlertBanner tone="info" icon={Info} title={t('incov.bannerTitle')} className="mb-0" onDismiss={dismissBanner} dismissLabel={t('incov.dismiss')}>
          {t('incov.bannerText')}
        </AlertBanner>
      )}

      {tiles.length > 0 && (
        <MonitorStatsBar items={tiles} activeFilter={filters.stat}
          onStatClick={(key) => patchFilters({ stat: filters.stat === key ? '' : key })} />
      )}

      <IncidentsToolbar filters={filters} patch={patchFilters} reset={resetFilters} typeCounts={typeCounts} total={total}
        teams={teams} phone={phone} labelsFor={labelsFor} />

      {error && (
        <AlertBanner tone="danger" title={t('incov.loadError')} className="mb-0" role="alert"
          actions={<Button type="button" variant="outline" size="sm" onClick={load}><RotateCcw aria-hidden="true" />{t('incov.retry')}</Button>}>
          {error}
        </AlertBanner>
      )}

      {facetNote && (
        <p data-slot="facet-note" className="text-xs text-muted-foreground">{t('incov.pageFacetNote', visibleRows.length, rows.length)}</p>
      )}

      {initial ? (
        view === 'board' ? <BoardSkeleton /> : <TableSkeleton phone={phone} />
      ) : visibleRows.length === 0 ? (
        !error && (active.length === 0
          ? <StatusBlock tone="success" icon={CheckCircle2} title={t('incov.emptyAllClear')} description={t('incov.emptyAllClearText')} className="py-14" />
          : (
            <StatusBlock tone="neutral" icon={Siren} title={t('incov.emptyTitle')} description={t('incov.emptyFiltered')} className="py-14"
              actions={<Button type="button" variant="outline" onClick={resetFilters}><FilterX aria-hidden="true" />{t('incov.clearFilters')}</Button>} />
          ))
      ) : (
        <div data-slot="incidents-results" aria-busy={loading || undefined} className={cn('flex min-w-0 flex-col gap-3 transition-opacity', loading && 'opacity-60')}>
          {view === 'board'
            ? <IncidentBoard rows={visibleRows} nowMs={nowMs} onOpen={openDetail} selectedId={selectedId} phone={phone} />
            : (
              <IncidentsTable rows={visibleRows} nowMs={nowMs} sort={sort} onSort={toggleSort} onOpen={openDetail} selectedId={selectedId}
                phone={phone} isAdmin={isAdmin} onAck={ack} onResolve={resolve} onDelete={del} />
            )}
          {/* Standart sayfalama çubuğu — pano da yüklenen sayfayı dağıtır */}
          <PaginationBar {...sp.bar} />
        </div>
      )}

      {selected && (
        <IncidentDetailSheet key={selected.id} incident={selected} onClose={closeDetail} isAdmin={isAdmin}
          onAck={ack} onResolve={resolve} onDelete={del} onCommentDelta={onCommentDelta} focusComposer={focusComposer} />
      )}
    </div>
  )
}
