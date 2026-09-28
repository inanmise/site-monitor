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
import { useDelayedFlag } from '../hooks/useDelayedFlag.js'
import { Spinner } from './ui/Progress.jsx'
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
import IncidentScopeFilter from './incidents/IncidentScopeFilter.jsx'
import ActionNoteDialog from './incidents/ActionNoteDialog.jsx'   // gerekçeli onayla / çöz penceresi (2026-09-28)
import { contextFromIncident } from './incidents/actionNoteModel.js'
import {
  FILTER_DEFAULTS, serverParams, clientFacet, hasClientFacet, summarize, activeFilters, teamOptions, iso24hAgo,
  filtersFromUrl, filtersToUrl, sortFromUrl, severityMeta, rowName,
  SCOPE_MINE, normalizeIncidentScope, scopeParam, canActOn, canDeleteIncident,
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
 * Yetki: satır başına SUNUCU bayrakları (`can_manage` / `can_act` / `can_delete`) — eylem düğmeleri yalnız hakkı olan
 * satırda çizilir; sunucu her yazma ucunda yine kendi kapısını uygular (403 → toast; onayla/çöz'de gerekçeli pencerenin
 * içinde satır içi). Bayrak yoksa (eski yanıt) bugünkü
 * davranış: onayla/çöz görünür, silme rol ipucuna (ADMIN) bakar. Uygulama düzeyi `incident` URL anahtarı
 * (e-posta derin bağlantısı) okunur ama sayfa tarafından YAZILMAZ — bağlantıyı "Bağlantıyı kopyala" üretir.
 *
 * <p>Org geneli salt okunur görünürlük (2026-09-28): "Takımımın olayları | Diğer ekiplerin olayları | Tümü" süzgeci
 * (`scope`, URL'de `scope` — PAGE_STATE_PARAMS). Varsayılan "Takımımın" = bugünkü görünüm; süzgeç yalnız sunucu
 * `scope_counts` döndürürse çizilir (ayar açık, çağıran global görüntüleyici değil). Başka ekibin olayı salt okunur:
 * rozet + açıklama, hiçbir eylem denetimi yok. Özet kartları da seçili kapsamı sayar.
 *
 * <p><b>Titremesiz geçiş (stale-while-revalidate, 2026-09-28 bildirimi "süzgeçler arasında kartlar git gel yapıyor"):</b>
 * iskelet YALNIZ ilk yüklemede; sonraki her süzgeç/kapsam/sayfa değişiminde ekrandaki sonuç (aynı istemci süzgeciyle)
 * yerinde kalır, ~180 ms'den uzun süren yüklemede soluklaşır + araç çubuğunun yanında küçük Spinner; boş durum ancak YENİ
 * yanıt sıfır derse çizilir. İstemci süzgeci (önem/takım/onaylı…) eski sunucu satırlarına YENİ süzgeçle uygulanmaz —
 * yeni yanıt gelene kadar son yerleşmiş görünüm donar (`loaded`). Yalnız EN SON isteğin yanıtı uygulanır (reqIdRef).
 */
export default function IncidentsPage({ systemRole, teamId }) {
  const t = useT()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  // Telefonda pano sekmeler, liste kartlar, süzgeçler alt Sheet (yapı farkı → useIsMobile; görünüm farkı CSS'te)
  const phone = useIsMobile()

  const [filters, setFilters] = useState(() => filtersFromUrl(readUrlParam))
  // Olay kapsamı: URL (scope) > "Takımımın olayları". Sunucu gerçekte uygulananı `scope` ile söyler (ayar kapalıysa mine).
  const [scope, setScope] = useState(() => normalizeIncidentScope(readUrlParam('scope')))
  const [scopeCounts, setScopeCounts] = useState(null)   // null = süzgeç yok (ayar kapalı / global görüntüleyici)
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
  const [noteDialog, setNoteDialog] = useState(null)   // gerekçeli pencere: { action, items, submit(note) }
  const [bannerOpen, setBannerOpen] = useState(() => { try { return localStorage.getItem(BANNER_KEY) !== 'true' } catch { return true } })
  const reqIdRef = useRef(0)
  const summarySeqRef = useRef(0)   // özet de yarışır (dakikalık tazeleme + teamId değişimi + elle tazeleme)
  // Son BAŞARILI yanıtın istek anahtarı + o an ekranda uygulanan süzgeçler (key null = hiç yüklenmedi → iskelet).
  const [loaded, setLoaded] = useState(() => ({ key: null, filters: null }))

  // "Son 24 saat" sınırı istek anında hesaplanır; süzgeç/sıralama değişmedikçe sabit kalır (her saniye yeni istek YOK).
  const params = useMemo(() => serverParams(filters, sort, Date.now(), scope), [filters, sort, scope])
  // Sayfalama standardı: süzgeç/sıralama değişince sayfa 1 AYNI render'da; boyut ön ayardan; URL page/ps.
  const sp = useServerPagination({ listKey: 'incidents', preset: 'page', resetDeps: [params], apiBase: 0, url: { pageKey: 'page', sizeKey: 'ps' } })
  const { apiPage, pageSize } = sp
  const total = sp.total ?? 0
  const requestKey = JSON.stringify([params, apiPage, pageSize])

  useUrlQuerySync({ ...filtersToUrl(filters, view, sort), scope: scope === SCOPE_MINE ? null : scope })
  useEffect(() => { try { localStorage.setItem(VIEW_KEY, view) } catch { /* yoksay */ } }, [view])

  const load = useCallback(async () => {
    const myId = ++reqIdRef.current
    const key = JSON.stringify([params, apiPage, pageSize])
    setLoading(true)
    try {
      const res = await api.monitoring.incidents.list({ ...params, page: apiPage, size: pageSize })
      if (myId !== reqIdRef.current) return   // yalnız EN SON isteğin yanıtı (bayat yanıt grid'i ezmesin)
      if (res?.success) {
        setRows(res.data ?? []); sp.bind(res); setTypeCounts(res.type_counts ?? {}); setError(null)
        setLoaded((l) => ({ ...l, key }))
        setScopeCounts(res.scope_counts && typeof res.scope_counts === 'object' ? res.scope_counts : null)
        // Sunucu isteneni uygulamadıysa (ayar kapalı, global görüntüleyici) seçim gerçeğe döner — tek ek istek, döngü yok.
        if (res.scope != null) { const eff = normalizeIncidentScope(res.scope); setScope((cur) => (cur === eff ? cur : eff)) }
      }
      else { setError(res?.error || t('incov.loadError')); setLoaded((l) => ({ ...l, key })) }
    } catch (e) {
      // Başarısız istek de YERLEŞİR (eski satırlar yerinde kalır): yoksa `settled` bir sonraki başarılı denemeye dek
      // false kalıyor, istemci süzgeçleri (önem, takım, Onaylı/Kritik/Atanmamış/Benim) çipi değiştirip listeyi dondurmuştu.
      if (myId === reqIdRef.current) { setError(e?.message || t('incov.loadError')); setLoaded((l) => ({ ...l, key })) }
    } finally {
      if (myId === reqIdRef.current) setLoading(false)
    }
  }, [params, apiPage, pageSize]) // eslint-disable-line react-hooks/exhaustive-deps

  /** Özet: açık küme (toplam + ≤200 satır) ve son 24 saatte çözülen toplamı — süzgeçten bağımsız. */
  const loadSummary = useCallback(async () => {
    const my = ++summarySeqRef.current
    try {
      const [open, res24] = await Promise.all([
        api.monitoring.incidents.list({ status: 'ongoing', page: 0, size: OPEN_SAMPLE, ...scopeParam(scope) }),
        api.monitoring.incidents.list({ status: 'resolved', since: iso24hAgo(Date.now()), page: 0, size: 1, ...scopeParam(scope) }),
      ])
      if (my !== summarySeqRef.current) return   // bayat yanıt — daha yeni bir özet isteği yolda
      if (!open?.success) { setSummary(null); return }
      setSummary(summarize(open.data ?? [], Number(open.total ?? 0), res24?.success ? Number(res24.total ?? 0) : null, teamId))
    } catch {
      if (my === summarySeqRef.current) setSummary(null)
    }
  }, [teamId, scope])

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

  // Değişmeyen yama durumu YENİLEMEZ: aynı değerle yeni nesne yeni istek demekti (gereksiz yeniden yükleme = titreme).
  const patchFilters = useCallback((patch) => setFilters((f) => (Object.keys(patch).every((k) => f[k] === patch[k]) ? f : { ...f, ...patch })), [])
  const resetFilters = useCallback(() => setFilters({ ...FILTER_DEFAULTS }), [])
  const toggleSort = useCallback((key) => setSort((s) => (s.by === key ? { by: key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { by: key, dir: 'desc' })), [])
  const dismissBanner = () => { setBannerOpen(false); try { localStorage.setItem(BANNER_KEY, 'true') } catch { /* yoksay */ } }

  /** Satırı yerinde güncelle (iyimser güncelleme + sunucu yanıtı); derin bağlantı olayı da aynı kimlikteyse. */
  const patchRow = useCallback((id, patch) => {
    setRows((rs) => rs.map((r) => (String(r.id) === String(id) ? { ...r, ...patch } : r)))
    setFallback((f) => (f && String(f.id) === String(id) ? { ...f, ...patch } : f))
  }, [])

  // Yerleşmiş mi: ekrandaki satırlar GÜNCEL isteğin yanıtı mı? Değilse (süzgeç/sayfa değişti, yanıt yolda) son yerleşmiş
  // görünüm aynen kalır: satırlar da onlara uygulanan istemci süzgeci de. Yerleşmişken istemci süzgeci anında uygulanır ve
  // "son görünüm" güncel süzgeçle eşitlenir (önceki render'a göre durum ayarlama — React'in belgelenmiş deseni).
  const settled = loaded.key === requestKey
  if (settled && loaded.filters !== filters) setLoaded((l) => ({ ...l, filters }))
  const shownFilters = settled ? filters : (loaded.filters ?? filters)
  const visibleRows = useMemo(() => clientFacet(rows, shownFilters, teamId), [rows, shownFilters, teamId])
  const teams = useMemo(() => teamOptions(rows), [rows])
  const shownActive = activeFilters(shownFilters)
  // Yükleme göstergesi yalnız GÖRÜNÜMÜ bayat bir yükleme ~180 ms'yi aşarsa (dakikalık arka plan tazelemesi soluklaştırmaz).
  const busy = useDelayedFlag(loading && !settled, 180)
  const selected = useMemo(() => {
    if (selectedId == null) return null
    return rows.find((r) => String(r.id) === String(selectedId)) || (fallback && String(fallback.id) === String(selectedId) ? fallback : null)
  }, [rows, fallback, selectedId])

  const openDetail = useCallback((inc) => { setFocusComposer(false); setSelectedId(String(inc.id)) }, [])
  const closeDetail = useCallback(() => { setSelectedId(null); setFocusComposer(false) }, [])

  // ── Eylemler (gerekçe zorunlu — sunucu AlertActionNote ile aynı kural) ──
  // Pencere: incidents/ActionNoteDialog (Alarm Geçmişi ile ortak). Sunucu çağrısı pencerenin İÇİNDEN (`submit`): hata
  // (kural, 403, ağ) pencerede satır içi kalır, pencere kapanmaz, not kaybolmaz; başarıda tost + özet burada.
  function ack(inc) {
    if (!canActOn(inc)) return   // başka ekibin olayı / eylem izni yok — düğme zaten çizilmez (savunma)
    setNoteDialog({ action: 'ack', items: [contextFromIncident(inc)], submit: (note) => submitAck(inc, note) })
  }
  async function submitAck(inc, note) {
    patchRow(inc.id, { acknowledged: true })   // iyimser
    let r
    try { r = await api.admin.acknowledgeAlert(inc.id, note) } catch { r = null }
    if (!r?.success) { patchRow(inc.id, { acknowledged: inc.acknowledged ?? false }); return { ok: false, error: r?.error || t('incov.actionError') } }
    patchRow(inc.id, { acknowledged: true, acknowledged_by: r.data?.acknowledged_by ?? null, acknowledged_at: r.data?.acknowledged_at ?? null })
    toast.success(t('incov.ackDone'))
    loadSummary()
    return { ok: true }
  }
  function resolve(inc) {
    if (!canActOn(inc)) return
    setNoteDialog({ action: 'resolve', items: [contextFromIncident(inc)], submit: (note) => submitResolve(inc, note) })
  }
  async function submitResolve(inc, note) {
    const nowIso = new Date().toISOString().slice(0, 19)
    patchRow(inc.id, { status: 'resolved', resolved_at: nowIso })   // iyimser
    let r
    try { r = await api.admin.resolveAlert(inc.id, note) } catch { r = null }
    if (!r?.success) { patchRow(inc.id, { status: inc.status, resolved_at: inc.resolved_at ?? null }); return { ok: false, error: r?.error || t('incov.actionError') } }
    patchRow(inc.id, { status: 'resolved', resolved_at: r.data?.resolved_at || nowIso, resolved_by: r.data?.resolved_by ?? null })
    toast.success(t('incov.resolveDone'))
    loadSummary()
    return { ok: true }
  }
  async function del(inc) {
    if (!canDeleteIncident(inc, isAdmin)) return
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

  // İskelet YALNIZ ilk yüklemede (hiç başarılı yanıt yokken); sonrasında eski sonuç yerinde kalır.
  const initial = loaded.key == null && !error
  const facetNote = hasClientFacet(shownFilters) && visibleRows.length < rows.length

  return (
    <div data-slot="incidents-page" className="flex min-w-0 flex-col gap-3">
      <IncidentsHeader summary={summary} view={view} onView={setView} onRefresh={refreshAll} loading={loading} />

      {bannerOpen && (
        <AlertBanner tone="info" icon={Info} title={t('incov.bannerTitle')} className="mb-0" onDismiss={dismissBanner} dismissLabel={t('incov.dismiss')}>
          {t('incov.bannerText')}
        </AlertBanner>
      )}

      {scopeCounts && (
        <IncidentScopeFilter value={scope} counts={scopeCounts} onChange={(v) => setScope(normalizeIncidentScope(v))} />
      )}

      {tiles.length > 0 && (
        <MonitorStatsBar items={tiles} activeFilter={filters.stat}
          onStatClick={(key) => patchFilters({ stat: filters.stat === key ? '' : key })} />
      )}

      <IncidentsToolbar filters={filters} patch={patchFilters} reset={resetFilters} typeCounts={typeCounts} total={total}
        teams={teams} phone={phone} labelsFor={labelsFor}
        busy={busy ? <Spinner size={14} label={t('tbl.loading')} /> : null} />

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
      ) : (
        // Sonuç kabı süzgeç değişiminde SÖKÜLMEZ (yükseklik çökmez, kaydırma zıplamaz): eski içerik soluk kalır, yeni
        // yanıt gelince yerini alır. Boş durum da kabın içinde — "sonuç yok" ancak yeni yanıt sıfır derse çizilir.
        <div data-slot="incidents-results" data-stale={!settled || undefined} aria-busy={loading || undefined}
          className={cn('flex min-w-0 flex-col gap-3 transition-opacity motion-reduce:transition-none', busy && 'opacity-60')}>
          {visibleRows.length === 0 ? (
            !error && (shownActive.length === 0
              ? <StatusBlock tone="success" icon={CheckCircle2} title={t('incov.emptyAllClear')} description={t('incov.emptyAllClearText')} className="py-14" />
              : (
                <StatusBlock tone="neutral" icon={Siren} title={t('incov.emptyTitle')} description={t('incov.emptyFiltered')} className="py-14"
                  actions={<Button type="button" variant="outline" onClick={resetFilters}><FilterX aria-hidden="true" />{t('incov.clearFilters')}</Button>} />
              ))
          ) : (
            <>
              {view === 'board'
                ? <IncidentBoard rows={visibleRows} nowMs={nowMs} onOpen={openDetail} selectedId={selectedId} phone={phone} />
                : (
                  <IncidentsTable rows={visibleRows} nowMs={nowMs} sort={sort} onSort={toggleSort} onOpen={openDetail} selectedId={selectedId}
                    phone={phone} isAdmin={isAdmin} onAck={ack} onResolve={resolve} onDelete={del} />
                )}
              {/* Standart sayfalama çubuğu — pano da yüklenen sayfayı dağıtır */}
              <PaginationBar {...sp.bar} />
            </>
          )}
        </div>
      )}

      {selected && (
        <IncidentDetailSheet key={selected.id} incident={selected} onClose={closeDetail} isAdmin={isAdmin}
          onAck={ack} onResolve={resolve} onDelete={del} onCommentDelta={onCommentDelta} focusComposer={focusComposer} />
      )}

      {noteDialog && (
        <ActionNoteDialog key={`${noteDialog.action}:${noteDialog.items[0]?.id}`} action={noteDialog.action} subject="incident"
          items={noteDialog.items} onSubmit={noteDialog.submit} onClose={() => setNoteDialog(null)} />
      )}
    </div>
  )
}
