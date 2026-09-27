import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Download, FileJson, FileSpreadsheet, Info, MousePointerClick, RefreshCw, ScrollText, SearchX, ShieldCheck, Users } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { useIsWide } from '../../hooks/useIsWide.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { useToast } from '../ui/Toast.jsx'
import ToneBadge from './ToneBadge.jsx'
import AuditDetailPanel from './audit/AuditDetailPanel.jsx'
import AuditTable from './audit/AuditTable.jsx'
import AuditCardList from './audit/AuditCardList.jsx'
import AuditFilterBar, { ActiveFilterChips } from './audit/AuditFilterBar.jsx'
import AuditViewsMenu from './audit/AuditViewsMenu.jsx'
import { AuditStatTiles, AuditInsights } from './audit/AuditSummary.jsx'
import { formatExact } from './audit/AuditBits.jsx'
import { eventLabel, OUTCOME_KEYS } from './audit/auditFormat.js'
import { EMPTY_FILTERS, URL_PREFIX, readUrlFilters, writeUrlFilters, normalizeFilters, toApiParams, filterChips, activeFilterCount, splitTypes } from './audit/auditFilters.js'
import { downloadAuditExport, exportCap } from './audit/auditExport.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'

/**
 * Sunucu kataloğu gelmezse kullanılacak YEDEK liste. `/audit/event-types` kanonik kaynak (162 tür); bu liste yalnız
 * uç düşerse süzgecin tamamen boş kalmaması içindir (eskiden tek kaynaktı ve 32 türde sürüklenmişti).
 */
const EVENT_TYPES_FALLBACK = [
  'LOGIN', 'LOGIN_FAILED', 'LOGOUT',
  'DOMAIN_ADD', 'DOMAIN_DELETE', 'DOMAIN_EDIT',
  'TEAM_CREATE', 'TEAM_UPDATE', 'TEAM_DELETE',
  'USER_CREATE', 'USER_UPDATE', 'USER_DELETE', 'USER_UNLOCK',
  'PERMISSION_UPDATE', 'PERMISSION_RESET',
  'MONITOR_CREATE', 'MONITOR_UPDATE', 'MONITOR_DELETE',
  'THRESHOLD_CREATE', 'THRESHOLD_UPDATE', 'CONTACT_CREATE', 'CONTACT_UPDATE', 'CONTACT_DELETE',
  'GUIDE_LINK_CREATE', 'GUIDE_LINK_UPDATE', 'GUIDE_LINK_DELETE',
  'ACCESS_DENIED', 'AUTH_REQUIRED', 'AUDIT_EXPORT',
  'SYSTEM_STARTUP', 'SYSTEM_SHUTDOWN',
]

/** Ayrıntı bölmesi (xl) ile yan Sheet arasındaki TEK JS eşiği — Tailwind `xl` ile aynı (1280). */
const SPLIT_MIN_PX = 1280

/**
 * Denetim Logu (2026-09-26 yeniden tasarım — shadcn, mobil web).
 *
 * <p>Yerleşim: başlık (amaç + kapsam rozeti + eylemler) · özet kartları ve katlanır eğilim/dağılım (yalnız tam
 * kapsam) · süzgeç çubuğu (telefonda Sheet) + etkin süzgeç çipleri · liste (masaüstü/tablet veri tablosu, telefonda
 * güne göre kartlar) · ayrıntı (xl'de sağ bölme, altında yan Sheet; telefonda tam genişlik). Sunucu sayfalaması
 * `useServerPagination`, süzgeçler `a_*` adres anahtarlarında (derin bağlantı), seçili olay `a_sel`.
 *
 * @param {boolean}  [fullScope=true] sistem-geneli denetçi (global admin / AUDIT). false = ekip kapsamı (kullanıcı
 *   kararı 2026-09-25): ekip üyeleri takım arkadaşlarının kayıtlarını TAM ayrıntıyla görür; sistem-geneli yüzeyler
 *   (özet, eğilim, bütünlük) çizilmez ve uçları hiç çağrılmaz — sunucu da onları kapatır; liste/dışa aktarma sunucuda
 *   aktör üyeliğiyle süzülür (dışa aktarma tavanı 5.000 / tam kapsam 50.000).
 * @param {string[]} [teamNames] ekip kapsamında rozette gösterilecek takım adları (isteğe bağlı; App.jsx verirse)
 */
export default function AuditLogViewer({ fullScope = true, teamNames }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const wide = useIsWide(SPLIT_MIN_PX)
  const isMobile = useIsMobile()

  const [filters, setFilters] = useState(readUrlFilters)   // UYGULANAN süzgeç — derin bağlantıdan başlar
  const [nonce, setNonce] = useState(0)                     // aynı süzgeç/sayfa ile yeniden yükleme
  const sp = useServerPagination({ listKey: 'audit-log', preset: 'page', resetDeps: [filters],
    url: { pageKey: 'page', sizeKey: 'ps' }, apiBase: 0 })
  const { apiPage, pageSize: size } = sp
  const [rows, setRows] = useState([])
  const [loaded, setLoaded] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [stats, setStats] = useState(null)
  const [statsError, setStatsError] = useState(false)
  const [eventCatalog, setEventCatalog] = useState(null)   // [{type, category, count?}] | null
  const [selectedId, setSelectedId] = useState(() => readUrlInt(URL_PREFIX + 'sel', null))
  const [extraRows, setExtraRows] = useState({})           // id → satır: ilişkili listeden / derin bağlantıdan açılan, sayfada olmayan
  const [sheetOpen, setSheetOpen] = useState(() => readUrlInt(URL_PREFIX + 'sel', null) != null)
  const [integrity, setIntegrity] = useState(null)         // { loading } | { ok, checked, broken_seq } | { error }
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [exporting, setExporting] = useState(null)         // 'csv' | 'json' | null

  // ── Süzgeç uygulama: her yol buradan geçer (adres eşlemesi effect'te, sayfa 1 kancada) ──
  const applyFilters = useCallback((next) => {
    setFilters(normalizeFilters(next))
    sp.reset()
  }, [sp])
  const patchFilters = useCallback((patch) => setFilters(f => normalizeFilters({ ...f, ...patch })), [])
  const clearFilters = useCallback(() => applyFilters(EMPTY_FILTERS), [applyFilters])
  useEffect(() => { writeUrlFilters(filters) }, [filters])

  // ── Liste (R12: yalnız EN SON isteğin yanıtı ekrana yazar) ──
  const logsSeq = useRef(0)
  useEffect(() => () => { logsSeq.current++ }, [])
  useEffect(() => {
    const seq = ++logsSeq.current
    setLoading(true)
    api.admin.getAuditLogs({ page: apiPage, size, ...toApiParams(filters) }).then(r => {
      if (seq !== logsSeq.current) return
      if (r?.success) { setRows(r.data || []); sp.setTotal(r.total); setLoadError(false); setLoaded(true) }
      else setLoadError(true)   // hata SESSİZ değil: boş tabloya bakıp "kayıt yok" sanılmasın
    }).catch(() => { if (seq === logsSeq.current) setLoadError(true) })
      .finally(() => { if (seq === logsSeq.current) setLoading(false) })
  }, [filters, apiPage, size, nonce])   // eslint-disable-line react-hooks/exhaustive-deps
  const reload = useCallback(() => setNonce(n => n + 1), [])

  // ── Özet (yalnız tam kapsam — ekip kapsamında uç 403 verir, hiç çağrılmaz) ──
  const loadStats = useCallback(() => {
    if (!fullScope) return
    api.admin.getAuditStats()
      .then(r => { if (r?.success) { setStats(r.data); setStatsError(false) } else setStatsError(true) })
      .catch(() => setStatsError(true))
  }, [fullScope])
  useEffect(() => { loadStats() }, [loadStats])

  // ── Olay türü kataloğu (süzgeç listesinin kaynağı); düşerse yedek liste ──
  useEffect(() => {
    let alive = true
    api.admin.getAuditEventTypes?.()
      .then(r => { if (alive && r?.success && Array.isArray(r.data)) setEventCatalog(r.data) })
      .catch(() => { /* yedek liste devrede */ })
    return () => { alive = false }
  }, [])

  useVisibleInterval(() => { reload(); loadStats() }, autoRefresh ? 15000 : 0, false)

  // ── Seçim / ayrıntı ──
  const selectedRow = rows.find(r => r.id === selectedId) ?? extraRows[selectedId] ?? null
  const selectedIdx = rows.findIndex(r => r.id === selectedId)
  useUrlQuerySync({ [URL_PREFIX + 'sel']: selectedId || null })
  useEffect(() => { if (wide) setSheetOpen(false) }, [wide])   // xl'e büyüyünce ayrıntı bölmede — Sheet kapanır

  // Derin bağlantı: `a_sel` sayfada değilse tekil uçtan getirilir (eskiden paylaşılan bağlantı sessizce hiçbir şey açmıyordu).
  const fetchedIds = useRef(new Set())
  useEffect(() => {
    if (!loaded || selectedId == null || selectedRow || fetchedIds.current.has(selectedId)) return
    fetchedIds.current.add(selectedId)
    api.admin.getAuditEntry?.(selectedId)?.then?.(r => {
      const row = r?.success && r.data && !Array.isArray(r.data) ? r.data : null
      if (row?.id != null) setExtraRows(m => ({ ...m, [row.id]: row }))
    }).catch?.(() => { /* kapsam dışı/silinmiş: bölme boş kalır */ })
  }, [loaded, selectedId, selectedRow])

  const openRow = useCallback((id) => { setSelectedId(id); if (!wide) setSheetOpen(true) }, [wide])
  const selectRelated = useCallback((row) => {
    setExtraRows(m => (m[row.id] ? m : { ...m, [row.id]: row }))
    setSelectedId(row.id)
    if (!wide) setSheetOpen(true)
  }, [wide])
  function navigate(delta) {
    const next = rows[selectedIdx + delta]
    if (next) setSelectedId(next.id)
  }
  /** Ayrıntıdan hızlı süzgeç: zaman penceresi korunur, kalanı bu değere döner; dar ekranda liste görünsün diye Sheet kapanır. */
  function quickFilter(patch) {
    applyFilters({ ...EMPTY_FILTERS, range: filters.range, since: filters.since, until: filters.until, ...patch })
    setSheetOpen(false)
  }

  // ── Bütünlük / dışa aktarma ──
  function checkIntegrity() {
    setIntegrity({ loading: true })
    api.admin.getAuditIntegrity()
      .then(r => setIntegrity(r?.success ? r.data : { error: true }))
      .catch(() => setIntegrity({ error: true }))
  }
  async function exportAudit(format) {
    if (exporting) return
    setExporting(format)
    const res = await downloadAuditExport(api.admin.auditExportUrl(format, toApiParams(filters)), `audit.${format}`)
    setExporting(null)
    if (res.ok) toast.success(t('audit.exportDone'))
    else if (res.status === 429) toast.info(t('audit.exportBusy'))
    else toast.error(t('audit.exportFailed', res.status || '—'))
  }

  // ── Türetilenler ──
  const eventOptions = useMemo(() => {
    const src = eventCatalog || EVENT_TYPES_FALLBACK.map(type => ({ type }))
    const known = new Set(src.map(e => e.type))
    const extra = splitTypes(filters.eventType).filter(x => !known.has(x)).map(type => ({ type }))
    return [...src, ...extra]
      .map(e => ({ value: e.type, label: eventLabel(e.type, t), count: e.count, hint: [e.type, e.category].filter(Boolean).join(' · ') }))
      // Sayı varsa (tam kapsam, son 90 gün) en sık tür üstte; yoksa alfabetik.
      .sort((a, b) => ((b.count ?? -1) - (a.count ?? -1)) || a.label.localeCompare(b.label))
  }, [eventCatalog, filters.eventType, t])
  const chips = useMemo(() => filterChips(filters, {
    t, eventLabel: (x) => eventLabel(x, t), outcomeLabel: (o) => (OUTCOME_KEYS[o] ? t(OUTCOME_KEYS[o]) : o),
    fmtTime: (iso) => formatExact(iso, locale),
  }), [filters, t, locale])
  const activeCount = activeFilterCount(filters)
  const total = sp.total
  const cap = exportCap(fullScope)
  const exportHint = [t('audit.exportCapHint', cap.toLocaleString(locale)),
    total != null && total > cap ? t('audit.exportOverCap', total.toLocaleString(locale), cap.toLocaleString(locale)) : null]
    .filter(Boolean).join('\n')
  const initialLoading = loading && !loaded

  const detailProps = {
    scope: 'admin', onFilter: quickFilter, onSelectRelated: selectRelated, onNavigate: navigate,
    canNewer: selectedIdx > 0, canOlder: selectedIdx >= 0 && selectedIdx < rows.length - 1,
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      {/* ── Başlık: amaç, kapsam, eylemler ── */}
      <header className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0 space-y-2">
          <p className="max-w-3xl text-sm text-muted-foreground">{t('audit.purpose')}</p>
          <div className="flex flex-wrap items-center gap-2">
            {fullScope ? (
              <HintPopover content={t('audit.scopeFullHint')} aria-label={t('audit.scopeFull')}>
                <Badge variant="outline" data-scope="full" className="gap-1 border-success/40 text-success"><ShieldCheck aria-hidden="true" />{t('audit.scopeFull')}</Badge>
              </HintPopover>
            ) : (
              <Badge variant="outline" data-scope="team" className="gap-1 text-muted-foreground">
                <Users aria-hidden="true" />{teamNames?.length ? t('audit.scopeTeamNamed', teamNames.join(', ')) : t('audit.scopeTeam')}
              </Badge>
            )}
            {integrity && !integrity.loading && !integrity.error && integrity.ok && (
              <ToneBadge tone="success" title={`${t('audit.integrityChecked')}: ${integrity.checked}`}>
                ✓ {t('audit.integrityOk')} ({integrity.checked})
              </ToneBadge>
            )}
            {integrity?.error && <ToneBadge tone="warning">{t('audit.integrityErr')}</ToneBadge>}
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" size="sm" aria-pressed={autoRefresh} title={t('audit.autoRefreshHint')}
            className={cn('h-10 md:h-8', autoRefresh && 'border-success text-success hover:text-success')}
            onClick={() => setAutoRefresh(a => !a)}>
            <span aria-hidden="true" className={cn('size-2 rounded-full', autoRefresh ? 'animate-pulse bg-success motion-reduce:animate-none' : 'bg-muted-foreground')} />
            {t('audit.autoRefresh')}
          </Button>
          <AuditViewsMenu filters={filters} onApply={applyFilters} />
          <div className="flex items-center gap-0.5">
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" disabled={!!exporting} aria-busy={!!exporting}>
                  {exporting ? <Spinner decorative size={14} inline /> : <Download aria-hidden="true" />}
                  {exporting ? t('audit.exporting') : t('audit.exportMenu')}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="z-(--z-menu)">
                <DropdownMenuItem onSelect={() => exportAudit('csv')}><FileSpreadsheet /> {t('audit.exportCsv')}</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => exportAudit('json')}><FileJson /> {t('audit.exportJson')}</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <HintPopover content={exportHint} aria-label={t('audit.exportInfo')} align="end"
              triggerClassName="size-10 justify-center text-muted-foreground hover:text-foreground md:size-8">
              <Info className="size-4" aria-hidden="true" />
            </HintPopover>
          </div>
          {fullScope && (
            <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" onClick={checkIntegrity}
              disabled={!!integrity?.loading} aria-busy={!!integrity?.loading}>
              {integrity?.loading ? <Spinner decorative size={14} inline /> : <ShieldCheck aria-hidden="true" />}
              {integrity?.loading ? t('audit.integrityRunning') : t('audit.verifyIntegrity')}
            </Button>
          )}
          <Button type="button" variant="outline" size="icon" className="md:size-8" onClick={() => { reload(); loadStats() }}
            aria-label={t('audit.refresh')} title={t('audit.refresh')}>
            <RefreshCw aria-hidden="true" className={cn(loading && loaded && 'animate-spin motion-reduce:animate-none')} />
          </Button>
        </div>
      </header>

      {!fullScope && (
        <AlertBanner tone="info" title={t('audit.teamScopeTitle')} className="mb-0">{t('audit.teamScopeText')}</AlertBanner>
      )}
      {integrity && !integrity.loading && !integrity.error && !integrity.ok && (
        <AlertBanner tone="danger" role="alert" title={t('audit.integrityBroken')} className="mb-0">
          {t('audit.integrityBrokenBody', integrity.broken_seq || '?')}
        </AlertBanner>
      )}

      {/* ── Özet + eğilim (yalnız tam kapsam; yalnız sunucunun döndürdüğü sayılar) ── */}
      {fullScope && statsError && (
        <AlertBanner tone="warning" className="mb-0" actions={<Button variant="outline" size="sm" onClick={loadStats}>{t('audit.retry')}</Button>}>
          {t('audit.statsError')}
        </AlertBanner>
      )}
      {fullScope && !statsError && <AuditStatTiles stats={stats} filters={filters} onApply={applyFilters} />}
      {fullScope && <AuditInsights stats={stats} onApply={applyFilters} />}

      {/* ── Süzgeçler ── */}
      <div className="flex flex-col gap-2">
        <AuditFilterBar filters={filters} onChange={patchFilters} eventOptions={eventOptions} isMobile={isMobile}
          activeCount={activeCount} total={total} onClearAll={clearFilters} />
        <ActiveFilterChips chips={chips} onRemove={(c) => applyFilters(c.clear(filters))} onClearAll={clearFilters} />
      </div>

      {/* ── Liste + ayrıntı ── */}
      <div className={cn('grid grid-cols-1 items-start gap-4', wide && 'grid-cols-[minmax(0,1fr)_minmax(340px,32%)]')}>
        <div className="flex min-w-0 flex-col gap-3">
          {loadError && (
            <AlertBanner tone="danger" title={t('audit.loadErrorTitle')} className="mb-0"
              actions={<Button variant="outline" size="sm" onClick={reload}>{t('audit.retry')}</Button>}>
              {t('audit.loadErrorBody')}
            </AlertBanner>
          )}
          <div className="flex items-center justify-between gap-2 px-0.5 text-sm text-muted-foreground">
            <span aria-live="polite">{total != null ? t('audit.eventsCount', total.toLocaleString(locale)) : ' '}</span>
            {loading && loaded && <Spinner size={14} label={t('audit.loadingEvents')} inline />}
          </div>

          <section aria-label={t('audit.listLabel')} data-slot="audit-list"
            className={cn('@container min-w-0', !isMobile && 'overflow-hidden rounded-xl border bg-card')}>
            {isMobile
              ? <AuditCardList rows={rows} selectedId={selectedId} onOpen={openRow} loading={loading} initialLoading={initialLoading} />
              : <AuditTable rows={rows} selectedId={selectedId} onSelect={setSelectedId} onOpen={openRow}
                  onClearSelection={() => setSelectedId(null)} loading={loading} initialLoading={initialLoading} />}
            {/* Boş durumun İKİ hâli ayrı: süzgeç yüzünden mi boş, gerçekten kayıt yok mu? */}
            {loaded && rows.length === 0 && !loading && !loadError && (
              activeCount > 0 ? (
                <StatusBlock tone="neutral" icon={SearchX} title={t('audit.emptyFiltered')} description={t('audit.emptyFilteredHint')}
                  actions={<Button variant="outline" size="sm" onClick={clearFilters}>{t('audit.clearAll')}</Button>} />
              ) : (
                <StatusBlock tone="neutral" icon={ScrollText} title={t('audit.empty')} description={t('audit.emptyHint')} />
              )
            )}
          </section>

          <PaginationBar {...sp.bar} />
        </div>

        {/* Sağ bölme bir DİYALOG DEĞİLDİR: odak listede kalır ki ↑/↓ ile gezinme sürsün (focus trap / aria-modal YOK). */}
        {wide && (
          <aside role="complementary" aria-label={t('audit.detailPanel')}
            className="sticky top-4 max-h-[calc(100dvh-2rem)] min-w-0 overflow-y-auto rounded-xl border bg-card p-4">
            {selectedRow
              ? <AuditDetailPanel row={selectedRow} onClose={() => setSelectedId(null)} {...detailProps} />
              : <StatusBlock tone="neutral" icon={MousePointerClick} title={t('audit.selectPrompt')} description={t('audit.selectPromptHint')} />}
          </aside>
        )}
      </div>

      {/* xl altı: ayrıntı yan Sheet'te (telefonda tam genişlik). Kapat düğmesi panelin kendi başlığında. */}
      {!wide && (
        <Sheet open={sheetOpen && !!selectedRow} onOpenChange={(o) => { if (!o) setSheetOpen(false) }}>
          <SheetContent side="right" showCloseButton={false} className="w-full gap-0 p-0 sm:max-w-xl">
            <div className="min-h-0 flex-1 overflow-y-auto p-4">
              {selectedRow && (
                <AuditDetailPanel row={selectedRow} titleAs={SheetTitle} descriptionAs={SheetDescription}
                  onClose={() => setSheetOpen(false)} {...detailProps} />
              )}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  )
}
