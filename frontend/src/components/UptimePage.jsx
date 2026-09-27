import { useState, useRef, useCallback, useMemo, useEffect } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import { domainDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { AlertCircle, CheckCircle, Users, Inbox, FolderOpen } from 'lucide-react'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import DateTimeRangePicker from './ui/DateTimeRangePicker.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { matchesTag, tagNamesOf, tagsOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import TeamScopeSwitch, { SCOPE_ALL, normalizeScope } from './ui/TeamScopeSwitch.jsx'
import ReadOnlyBadge from './ui/ReadOnlyBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardMetrics,
  MonitorMetric, MonitorCardFooter, MonitorStatusBadge, MonitorCardTag, CARD_LAYER, CARD_COPY,
} from './monitoring/MonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary } from './monitoring/MonitorDetail.jsx'

const REFRESH_INTERVAL = 60
/** "Takımlarım | Tüm takımlar" tercihi (org geneli görünürlük, 2026-09-26) — bu tarayıcıda hatırlanır. */
const SCOPE_KEY = 'uptime-scope'
function readStoredScope() { try { return localStorage.getItem(SCOPE_KEY) } catch { return null } }
function writeStoredScope(v) { try { localStorage.setItem(SCOPE_KEY, v) } catch { /* depolama yok */ } }

function todayStartDate() { const d = new Date(); d.setHours(0, 0, 0, 0); return d }

export default function UptimePage({ systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [filterStatus, setFilterStatus] = useState(() => readUrlParam('stat', 'all'))
  const [sortKey, setSortKey]           = useState(() => readUrlParam('sort', 'default'))
  const [search, setSearch]             = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter]     = useState(() => readUrlParam('team', 'all'))
  // Grup / etiket filtresi (2026-09-18): izleme sayfalarıyla aynı sözleşme ('all' / '__none__' / değer).
  const [groupFilter, setGroupFilter]   = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter]       = useState(() => readUrlParam('tag', 'all'))
  // Kapsam: URL (scope) > localStorage > takımlarım; anahtar yalnız sunucu `visible_to_all` derse çizilir.
  const [scope, setScopeRaw]            = useState(() => normalizeScope(readUrlParam('scope', readStoredScope())))
  const [visibleToAll, setVisibleToAll] = useState(false)
  const setScope = (v) => { const n = normalizeScope(v); setScopeRaw(n); writeStoredScope(n) }
  const [selected, setSelected]         = useState(null)
  const [diag, setDiag]                 = useState(null)   // { domain, port } → DiagnosticsModal
  const [dateFrom, setDateFrom]         = useState(todayStartDate)
  const [dateTo, setDateTo]             = useState(() => new Date())
  const [secondsSince, setSecondsSince] = useState(0)
  const lastFetched = useRef(null)

  // Diger 8 izleme sayfasinda hata dali VARDI, burada ve ScriptedMonitorPage'de HIC yoktu:
  // basarisizlikta yalniz setLoading(false) kosuyor, items bos kaliyor ve ekran "veri yok"
  // diyordu — kullanici kayitlarinin silindigini saniyordu.
  const [loadError, setLoadError] = useState(null)

  // Fetch yarışı: kapsam anahtarı ("Takımlarım | Tüm takımlar"), 60 sn yoklama ve görünürlük tazelemesi art arda
  // istek çıkarır; geç dönen "all" yanıtı "mine" listesini ezmesin. Yalnız EN SON isteğin yanıtı uygulanır.
  const overviewSeq = useRef(0)
  const fetchOverview = useCallback(async () => {
    const my = ++overviewSeq.current
    // AG HATASI DA BU DALA DUSMELI: request() ag hatasinda {success:false} DONDURMEZ, throw eder.
    try {
      const res = await api.monitoring.getUptimeOverview(scope)
      if (my !== overviewSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) {
        setItems(res.data)
        setVisibleToAll(!!res.visible_to_all)
        // Sunucu isteği daraltmışsa (ayar kapalı / izin yok) anahtar GERÇEKTE uygulanan kapsamı gösterir.
        if (res.scope && normalizeScope(res.scope) !== scope) setScopeRaw(normalizeScope(res.scope))
        lastFetched.current = Date.now()
        setSecondsSince(0)
        setLoadError(null)
      } else setLoadError(res?.error || 'load failed')
    } catch (e) {
      if (my === overviewSeq.current) setLoadError(e?.message || 'network error')
    } finally {
      if (my === overviewSeq.current) setLoading(false)
    }
  }, [scope])

  useVisibleInterval(fetchOverview, REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur
  // Kapsam değişince hemen yeniden çek: hook geri çağırının kimliğini izlemez (ref), ilk yüklemeyi zaten o yapıyor.
  const scopeMounted = useRef(false)
  useEffect(() => {
    if (!scopeMounted.current) { scopeMounted.current = true; return }
    setLoading(true)
    fetchOverview()
  }, [fetchOverview])
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da durur

  function openModal(item) {
    setSelected(item)
    setDateFrom(todayStartDate())
    setDateTo(new Date())
  }

  function closeModal() { setSelected(null) }

  function applyDateRange(from, to) {
    setDateFrom(from)
    setDateTo(to)
  }

  const STATUS_ORDER = { down: 0, unknown: 1, up: 2 }
  const displayItems = useMemo(() => {
    let list = [...items]
    if (filterStatus === 'down') list = list.filter(x => x.status !== 'up')
    if (filterStatus === 'up')   list = list.filter(x => x.status === 'up')
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') list = list.filter(x => !x.team_name)
      else                            list = list.filter(x => x.team_name === teamFilter)
    }
    if (groupFilter !== 'all') {
      if (groupFilter === '__none__') list = list.filter(x => !x.group_name)
      else                             list = list.filter(x => x.group_name === groupFilter)
    }
    if (tagFilter !== 'all') list = list.filter(x => matchesTag(x, tagFilter))
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(x => x.domain.toLowerCase().includes(q) || matchesGroupOrTagText(x, q))   // grup/etiket metni de aranır
    }
    list.sort((a, b) => {
      if (sortKey === 'default') {
        const sd = (STATUS_ORDER[a.status] ?? 1) - (STATUS_ORDER[b.status] ?? 1)
        if (sd !== 0) return sd
        return (a.ssl_valid_days ?? -9999) - (b.ssl_valid_days ?? -9999)
      }
      if (sortKey === 'ssl-asc')        return (a.ssl_valid_days ?? -9999) - (b.ssl_valid_days ?? -9999)
      if (sortKey === 'domain')         return a.domain.localeCompare(b.domain)
      if (sortKey === 'uptime-asc')     return (a.uptime_7d ?? 100) - (b.uptime_7d ?? 100)
      if (sortKey === 'incidents-desc') return (b.incidents_30d ?? 0) - (a.incidents_30d ?? 0)
      return 0
    })
    return list
  }, [items, filterStatus, sortKey, search, teamFilter, groupFilter, tagFilter])


  // Takım filtresi seçenekleri — listeden türetilir (dashboard deseni).
  const teamOptions = (() => {
    const names = new Set()
    let hasNone = false
    for (const x of items) { if (x.team_name) names.add(x.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  })()
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const groupOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const x of items) { if (x.group_name) names.add(x.group_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allGroups') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noGroup') })
    return opts
  }, [items, t])
  const hasGroupOptions = groupOptions.length > 1
  const tagOptions = useMemo(() => {
    const opts = [{ value: 'all', label: t('mon.allTags') }]
    tagNamesOf(items).forEach((n) => opts.push({ value: n, label: n }))
    if (items.some((x) => !(x.tags || '').trim())) opts.push({ value: '__none__', label: t('mon.noTags') })
    return opts
  }, [items, t])
  const hasTagOptions = tagOptions.length > 1
  // "Filtreleri temizle": herhangi bir daraltma varken görünür.
  const filtersActive = filterStatus !== 'all' || sortKey !== 'default' || !!search.trim()
    || teamFilter !== 'all' || groupFilter !== 'all' || tagFilter !== 'all'
  const clearFilters = () => { setFilterStatus('all'); setSortKey('default'); setSearch(''); setTeamFilter('all'); setGroupFilter('all'); setTagFilter('all') }

  // Sayfalama standardı: usePagination + PaginationBar (pageNumbers artık bileşenin içinde).
  const pager = usePagination(displayItems, {
    listKey: 'uptime', preset: 'page', resetDeps: [filterStatus, sortKey, search, teamFilter, groupFilter, tagFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: filtre/sıralama/arama/sayfa adres çubuğunda yaşar (varsayılanlar param üretmez).
  useUrlQuerySync({
    stat: filterStatus !== 'all' ? filterStatus : null,
    sort: sortKey !== 'default' ? sortKey : null,
    q: search.trim() || null,
    team: teamFilter !== 'all' ? teamFilter : null,
    group: groupFilter !== 'all' ? groupFilter : null,
    tag: tagFilter !== 'all' ? tagFilter : null,
    scope: scope === SCOPE_ALL ? SCOPE_ALL : null,   // "Tüm takımlar" paylaşılan bağlantıda da taşınır
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
  })

  function statusLabel(status) {
    if (status === 'up')   return t('uptime.statusUp')
    if (status === 'down') return t('uptime.statusDown')
    return t('uptime.statusUnknown')
  }

  function sslLabel(item) {
    if (item.ssl_valid_days == null) return t('uptime.sslUnknown')
    if (item.ssl_valid_days < 0)     return t('uptime.sslExpired')
    return t('uptime.sslDays').replace('{0}', item.ssl_valid_days)
  }

  /** Durum ikonu + ipucu (eski title) — ikon anlamı taşıdığı için role="img" + ad; ipucu shadcn Tooltip. */
  function iconHint(label, node) {
    return (
      <SimpleTooltip content={label}>
        <span role="img" aria-label={label} className="inline-flex items-center">{node}</span>
      </SimpleTooltip>
    )
  }

  /** SSL metrik değeri — sertifikaya erişilemediyse (ssl_valid_days null) "—" yerine hata ikonu. */
  function sslValueNode(item) {
    if (item.ssl_valid_days == null) {
      return iconHint(t('uptime.sslError'), <AlertCircle size={16} aria-hidden="true" className="text-destructive" />)
    }
    return sslLabel(item)
  }

  /** HTTP-OK göstergesi — son 24h temizse yeşil, sorunluysa kırmızı; veri yoksa gizli. */
  function httpOkNode(httpOk) {
    return httpOk
      ? iconHint(t('uptime.httpOk'), <CheckCircle size={16} aria-hidden="true" className="text-success" />)
      : iconHint(t('uptime.httpDown'), <AlertCircle size={16} aria-hidden="true" className="text-destructive" />)
  }

  /** SSL kalan gün → metin rengi (jeton / palet; koyu temada da okunur). */
  function sslTone(item) {
    if (item.ssl_valid_days == null) return 'text-muted-foreground'
    if (item.ssl_valid_days <= 7)    return 'text-destructive'
    if (item.ssl_valid_days <= 14)   return 'text-orange-500'
    if (item.ssl_valid_days <= 30)   return 'text-amber-500'
    return 'text-success'
  }

  /**
   * Kart durumu + SSL tonu (eski upt-card--up/down/unknown/ssl-warning/high/critical): çalışan bir sitede
   * sertifika bitişi yaklaşıyorsa şerit ve zemin uyarı tonuna döner.
   */
  function cardLook(item) {
    if (item.status !== 'up') return { status: item.status === 'down' ? 'down' : 'unknown', className: undefined }
    const d = item.ssl_valid_days
    if (d == null || d > 30) return { status: 'up', className: undefined }
    if (d < 0 || d <= 7)     return { status: 'down', className: 'bg-destructive/[0.08] dark:bg-destructive/[0.12]' }
    // (Eski `before:bg-orange-500` sol şerit kalıntısı kaldırıldı — kartta sol renk şeridi YOK, kullanıcı kuralı 2026-09-26.)
    if (d <= 14)             return { status: 'warn', className: 'bg-orange-500/[0.07] dark:bg-orange-500/10' }
    return { status: 'warn', className: 'bg-amber-500/5 dark:bg-amber-500/[0.08]' }
  }
  const statusKey = (status) => (status === 'up' ? 'up' : status === 'down' ? 'down' : 'unknown')
  // Kart meta satırındaki grup/etiket düğmesi (eski .inv-tag): tıklayınca o değere süzer.
  const META_CHIP = 'h-auto gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium'

  return (
    <div className="upt-page">
      {/* Ortak izleme sayfası başlığı (2026-09-27). Uptime salt okunur bir özet: toplu kontrol ve "Yeni" yok. */}
      <MonitorPageHeader type="uptime" title={t('uptime.title')} subtitle={t('uptime.subtitle')}
        count={loading ? null : items.length} countUnit="sites"
        down={loading ? null : items.filter((i) => i.status === 'down').length}
        refreshIn={REFRESH_INTERVAL - secondsSince} onRefresh={fetchOverview} refreshing={loading} />

      {!loading && (items.length > 0 || visibleToAll) && (
        <div className="upt-toolbar">
          <div className="upt-toolbar-left">
            <TeamScopeSwitch value={scope} onChange={setScope} visible={visibleToAll} />
            <SegmentedControl ariaLabel={t('hist.filterLabel')} value={filterStatus} onChange={setFilterStatus}
              options={['all', 'down', 'up'].map(f => ({ value: f, label: t(`uptime.filter${f.charAt(0).toUpperCase() + f.slice(1)}`) }))} />
            <NativeSelect size="sm" aria-label={t('uptime.sortLabel')} value={sortKey} onChange={e => setSortKey(e.target.value)}>
              <NativeSelectOption value="default">{t('uptime.sortDefault')}</NativeSelectOption>
              <NativeSelectOption value="ssl-asc">{t('uptime.sortSslAsc')}</NativeSelectOption>
              <NativeSelectOption value="domain">{t('uptime.sortDomain')}</NativeSelectOption>
              <NativeSelectOption value="uptime-asc">{t('uptime.sortUptimeAsc')}</NativeSelectOption>
              <NativeSelectOption value="incidents-desc">{t('uptime.sortIncidentsDesc')}</NativeSelectOption>
            </NativeSelect>
            {hasTeamOptions && (
              <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />
            )}
            {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
            {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
            {filtersActive && (
              <Button type="button" variant="secondary" size="sm" onClick={clearFilters}>{t('app.clearFilters')}</Button>
            )}
          </div>
          <Input type="text" className="w-auto min-w-[200px]"
            placeholder={t('uptime.searchPlaceholder')} aria-label={t('uptime.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? (
        <LoadingBlock label={t('uptime.checking')} fullWidth />
      ) : loadError && items.length === 0 ? (
        /* Hata bandi bos durumun ONUNDE: aksi halde yukleme hatasi "veri yok" gibi gorunur. */
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={fetchOverview}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : items.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={t('uptime.noData')} description={t('empty.hintFilter')} />
      ) : (
        <div className="upt-grid">
          {pager.pageItems.map(item => {
            const look = cardLook(item)
            // Dört dönem kesinti sayısı TEK ölçüde (2026-09-26 kart incelemesi: dört ayrı ölçü telefonda 3 satıra
            // taşıyordu). Hiç kesinti yoksa ölçü çizilmez (eski davranış: yalnız > 0 olanlar görünürdü).
            const incidentCounts = [item.incidents_1d, item.incidents_7d, item.incidents_15d, item.incidents_30d]
            const hasIncidents = incidentCounts.some(v => v > 0)
            const readOnly = item.can_manage === false   // başka takımın kartı: tanılama yok, salt okunur rozet
            return (
              /* Kart: shadcn Card + "stretched button" (monitoring/MonitorCard) — başlık gerçek düğme,
                 örtüsü kartı kaplar; kopyala / grup-etiket düğmeleri / tanıla örtünün üstünde (R18). */
              <MonitorCard key={item.domain} status={look.status} className={look.className}>
                <MonitorCardHeader>
                  <MonitorCardTop end={<>
                    <MonitorCardTag>:{item.port}</MonitorCardTag>
                    {/* Uptime kartının monitör id'si YOK — anahtarı alan adı, derin bağlantı da öyle. */}
                    <CopyLinkButton iconOnly url={domainDeepLink('uptime', item.domain)} targetName={item.domain} variant="ghost" size="icon-xs" className={CARD_COPY} />
                  </>}>
                    <MonitorStatusBadge status={statusKey(item.status)}>{statusLabel(item.status)}</MonitorStatusBadge>
                  </MonitorCardTop>
                  <MonitorCardTitle onOpen={() => openModal(item)} label={t('mon.openDetailFor', item.domain)} title={item.domain}>
                    {item.domain}
                  </MonitorCardTitle>
                </MonitorCardHeader>
                <MonitorCardContent>
                  {(item.group_name || item.tags) && (
                    <div className={cn(CARD_LAYER, '-mt-2 mb-1 flex flex-wrap gap-1')}>
                      {item.group_name && (
                        <Button type="button" variant="outline" size="xs" className={META_CHIP} title={t('card.group')}
                          onClick={() => setGroupFilter(item.group_name)}><FolderOpen size={10} aria-hidden="true" /> {item.group_name}</Button>
                      )}
                      {tagsOf(item).map((tag) => (
                        <Button key={tag} type="button" variant="outline" size="xs" data-slot="card-tag" className={META_CHIP}
                          title={t('card.tag')} onClick={() => setTagFilter(tag)}>{tag}</Button>
                      ))}
                    </div>
                  )}
                  {item.team_name && (
                    <div title={t('card.team')} className="mt-0.5 flex items-center gap-[5px] text-[.78em] text-muted-foreground">
                      <Users size={12} aria-hidden="true" />{item.team_name}
                    </div>
                  )}
                  {readOnly && <div className={cn(CARD_LAYER, 'mt-1.5 text-[.82em]')}><ReadOnlyBadge /></div>}
                  <Separator className="mt-3 mb-3" />
                  <MonitorCardMetrics>
                    <MonitorMetric value={sslValueNode(item)} label="SSL" valueClassName={sslTone(item)} />
                    {item.http_ok != null && <MonitorMetric value={httpOkNode(item.http_ok)} label="HTTP" />}
                    {item.uptime_7d != null && <MonitorMetric value={`${item.uptime_7d}%`} label={t('uptime.uptime7d')} />}
                    {item.uptime_30d != null && <MonitorMetric value={`${item.uptime_30d}%`} label={t('uptime.uptime30d')} />}
                    {hasIncidents && (
                      <MonitorMetric value={incidentCounts.map(v => v ?? 0).join(' · ')} label={t('uptime.incidentsWindows')}
                        hint={t('uptime.incidentsWindowsHint')} valueClassName="text-amber-600 dark:text-amber-400" />
                    )}
                  </MonitorCardMetrics>
                </MonitorCardContent>
                {(item.uptime_checked_at || item.ssl_checked_at || (isAdmin && !readOnly)) && (
                  <MonitorCardFooter actions={isAdmin && !readOnly && (
                    <Button variant="outline" size="sm" className="h-7 px-2.5 text-xs pointer-coarse:h-10"
                      onClick={(e) => { e.stopPropagation(); setDiag({ domain: item.domain, port: item.port || 443 }) }}>
                      {t('uptime.diagnose')}
                    </Button>
                  )}>
                    {(item.uptime_checked_at || item.ssl_checked_at)
                      ? formatDate(item.uptime_checked_at || item.ssl_checked_at) : ''}
                  </MonitorCardFooter>
                )}
              </MonitorCard>
            )
          })}
        </div>
      )}

      {/* ── Pagination ── */}
      {/* Standart çubuk yüklenirken de yerinde kalır (sayfa değişiminde zıplamasın) */}
      <PaginationBar {...pager} />

      {/* ── Detay penceresi (ui/ModalShell; Escape / örtü / X kapatır) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeModal} status={statusKey(selected.status)}
          badge={<MonitorStatusBadge status={statusKey(selected.status)}>{statusLabel(selected.status)}</MonitorStatusBadge>}
          title={selected.domain}
          subtitle={<span className="flex min-w-0 flex-wrap items-center gap-2">
            <MonitorCardTag className="text-xs">:{selected.port || 443}</MonitorCardTag>
            {selected.can_manage === false && <ReadOnlyBadge teamId={selected.team_id} teamName={selected.team_name} />}
          </span>}>
          <DetailDivider className="mt-0" />

          {/* Summary metrics */}
          <DetailSummary items={[
            { key: 'ssl', value: sslValueNode(selected), label: 'SSL', valueClassName: sslTone(selected) },
            selected.http_ok != null && { key: 'http', value: httpOkNode(selected.http_ok), label: 'HTTP' },
            selected.uptime_7d != null && { key: 'u7', value: `${selected.uptime_7d}%`, label: t('uptime.uptime7d') },
            selected.uptime_30d != null && { key: 'u30', value: `${selected.uptime_30d}%`, label: t('uptime.uptime30d') },
            ...[
              { v: selected.incidents_1d,  lbl: t('uptime.incidents1d')  },
              { v: selected.incidents_7d,  lbl: t('uptime.incidents7d')  },
              { v: selected.incidents_15d, lbl: t('uptime.incidents15d') },
              { v: selected.incidents_30d, lbl: t('uptime.incidents30d') },
            ].filter(m => m.v > 0).map((m, i) => ({ key: `inc${i}`, value: m.v, label: m.lbl, valueClassName: 'text-amber-600 dark:text-amber-400' })),
            selected.response_ms != null && { key: 'ms', value: `${selected.response_ms}ms`, label: t('uptime.responseMs') },
            selected.uptime_checked_at && { key: 'lh', value: formatDate(selected.uptime_checked_at), label: t('uptime.lastHttpCheck'), time: true },
            selected.ssl_checked_at && { key: 'ls', value: formatDate(selected.ssl_checked_at), label: t('uptime.lastSslCheck'), time: true },
          ]} />

          <DetailDivider />

          {/* Date range picker */}
          <DateTimeRangePicker
            from={dateFrom}
            to={dateTo}
            onApply={applyDateRange}
          />

          <DetailDivider />

          {/* Two-column history — paylaşılan CheckHistoryTab (tek picker iki kolonu sürer);
              sayfalama + hata filtresi + yoğunluk şeridi Uptime'a İLK KEZ geliyor. */}
          {/* Telefonda iki geçmiş ALT ALTA (yan yana ~150 px'lik iki sütun okunmuyordu); md+ yan yana. */}
          <div data-slot="uptime-history-cols" className="flex flex-col gap-6 md:flex-row md:items-start md:gap-0">
            <div className="min-w-0 flex-1">
              <h3 className="mb-2.5 text-[11px] font-bold tracking-[.07em] text-muted-foreground uppercase">{t('uptime.httpHistory')}</h3>
              <CheckHistoryTab kind="uptime-http" monitorId={selected.domain} listKey="uptime-http-history"
                extraParams={{ port: selected.port || 443 }}
                range={{ from: dateFrom, to: dateTo }} onRangeChange={applyDateRange}
                urlSync={false} gridClass="upt-uptime-rt-grid"
                columns={[t('uptime.dateFrom'), t('dns.status'), 'ms', '']}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                  <span className={c.status === 'up' ? 'upt-rt-up' : 'upt-rt-down'}>
                    {c.status === 'up' ? t('uptime.statusUp') : t('uptime.statusDown')}
                  </span>
                  <span className="upt-rt-ms">{c.response_ms != null ? `${c.response_ms}ms` : '—'}</span>
                  {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span> : <span />}
                </>)} />
            </div>

            <Separator orientation="vertical" className="mx-5 hidden self-stretch data-[orientation=vertical]:h-auto md:block" />

            <div className="min-w-0 flex-1">
              <h3 className="mb-2.5 text-[11px] font-bold tracking-[.07em] text-muted-foreground uppercase">{t('uptime.sslHistory')}</h3>
              <CheckHistoryTab kind="uptime-ssl" monitorId={selected.domain} listKey="uptime-ssl-history"
                range={{ from: dateFrom, to: dateTo }} onRangeChange={applyDateRange}
                urlSync={false} gridClass="upt-uptime-rt-grid"
                columns={[t('uptime.dateFrom'), t('dns.status'), 'SSL', '']}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                  <span className={c.status !== 'error' ? 'upt-rt-up' : 'upt-rt-down'}>
                    {c.status !== 'error' ? t('uptime.statusUp') : t('uptime.statusDown')}
                  </span>
                  <span className="upt-rt-ms">{c.days_remaining != null ? t('uptime.sslDays').replace('{0}', c.days_remaining) : '—'}</span>
                  {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span> : <span />}
                </>)} />
            </div>
          </div>
        </MonitorDetailModal>
      )}

      {/* ── Tanılama modalı (envanter ile ortak) — admin-only ── */}
      {diag && (
        <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />
      )}
    </div>
  )
}
