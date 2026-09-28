import { useState, useEffect, useLayoutEffect, useMemo, useCallback, useRef } from 'react'
import { ChevronDown, Download, Users, Upload, Plus, PlayCircle, RefreshCw, Globe, Building2, Clock, X, Trash2, Power, PowerOff, UserPlus, CheckSquare, Square, AlertTriangle, Inbox } from 'lucide-react'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PageHeader from '../ui/PageHeader.jsx'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { api } from '../../api/client'
import InventoryFormModal from '../inventory/InventoryFormModal.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { useIsMobile } from '@/hooks/use-mobile'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import DiagnosticsModal from './DiagnosticsModal.jsx'
import { exportInventoryCsv, exportInventoryPdf } from '../../utils/exportInventory'
import { loadNocGroupNames } from '../noc/forms/useNocFormOptions.js'
import { Spinner } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import InventoryToolbar from '../inventory/InventoryToolbar.jsx'
import InventoryStats from '../inventory/InventoryStats.jsx'
import InventoryHygieneBand, { hygieneIndex } from '../inventory/InventoryHygieneBand.jsx'
import InventoryTable from '../inventory/InventoryTable.jsx'
import InventoryCardList from '../inventory/InventoryCardList.jsx'
import InventoryTeamView from '../inventory/InventoryTeamView.jsx'
import InventoryImportModal from '../inventory/InventoryImportModal.jsx'
import InventoryDrawer from '../inventory/InventoryDrawer.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import {
  applyFilters, sortItems, detectOverlaps, filtersToParams, paramsToFilters, readView, writeView, readCols, writeCols, restoreCols, colKeys,
  readSavedViews, writeSavedViews, EMPTY_FILTERS, hasActiveFilter, applyTile, facetCounts,
} from '../inventory/inventoryModel.js'
import { SCOPE_ALL, normalizeScope } from '../ui/TeamScopeSwitch.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Skeleton } from '@/components/shadcn/skeleton'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/shadcn/dropdown-menu'

/**
 * Toplu sorumlu-ekip atama formu.
 *
 * Boş bırakılan alan GÖNDERİLMEZ — çağıran onu gövdeye koymaz, backend de dokunmaz. Modal bunu
 * kullanıcıya açıkça söyler: "boş bıraktığınız alanlar değişmeden kalır."
 */
function BulkContactsModal({ count, onApply, onClose }) {
  const t = useT()
  const [values, setValues] = useState({
    svc_mgmt_contact: '', app_dev_contact: '', iis_admin_contact: '', waf_admin_contact: '',
  })
  return (
    <ModalShell open onClose={onClose} title={t('inv.bulkContactsTitle')} icon={Users}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>{t('inv.cancel')}</Button>
          <Button onClick={() => onApply(values)}>
            {t('inv.bulkContactsBtn')}
          </Button>
        </>
      }>
      <AlertBanner tone="info">{t('inv.bulkContactsHint', count)}</AlertBanner>
      {CONTACT_FIELDS.map(({ key, labelKey }) => (
        <Field key={key} label={t(labelKey)}>
          {({ id }) => (
            <Input id={id} maxLength={300} value={values[key]}
              placeholder={t('inv.contactsPh')}
              onChange={e => setValues(v => ({ ...v, [key]: e.target.value }))} />
          )}
        </Field>
      ))}
    </ModalShell>
  )
}

/** İlk yükleme iskeleti — tablo/kart yerleşimiyle aynı yükseklikte (sayfa zıplamasın); ekran okuyucuya durum metni. */
function InventorySkeleton({ label }) {
  return (
    <div data-slot="inv-skeleton" role="status" aria-live="polite" className="flex flex-col gap-2 rounded-[10px] border bg-card p-3">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-9 w-full" />
      {Array.from({ length: 6 }, (_, i) => (
        <div key={i} className="flex items-center gap-3 py-1.5">
          <Skeleton className="size-4 shrink-0 rounded-sm" />
          <Skeleton className="h-4 w-[38%]" />
          <Skeleton className="hidden h-4 w-16 md:block" />
          <Skeleton className="hidden h-4 w-24 md:block" />
          <Skeleton className="ml-auto h-4 w-20" />
        </div>
      ))}
    </div>
  )
}

/**
 * Sayfa kabı dar mı (< `px`)? Tabloyu kart listesine çevirmek için — görünüm alanı değil KAP genişliği: 768 px
 * tablette kenar çubuğu açıkken içerik ~410 px kalıyor ve yapışkan alan adı sütunu ekranın çoğunu kaplıyordu.
 * Ölçüm yoksa (jsdom, ilk boyama) 0 → dar SAYILMAZ (telefon ayrımı ayrıca useIsMobile'da).
 */
function useNarrowContainer(px) {
  const ref = useRef(null)
  const [narrow, setNarrow] = useState(false)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return undefined
    const measure = () => { const w = el.clientWidth; setNarrow(w > 0 && w < px) }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [px])
  return [ref, narrow]
}

// Varsayılan boş liste SABİT: `= []` her render'da yeni kimlik üretir ve aşağıdaki prop-senkron efekti sonsuz döngüye girer (USER + teams verilmeden).
const NO_TEAMS = []

/**
 * Domain Envanteri sayfası (2026-09-27 yeniden tasarım — shadcn, mobil web). Yerleşim yukarıdan aşağıya:
 * PageHeader (ikon, başlık, açıklama, meta çipleri: alan sayısı · takım sayısı · son yenileme; eylemler: Yenile ·
 * Dışa aktar · İçe aktar · Domain ekle) → Özet kartları (süzgeç) → Hijyen bandı (yöneticiler) → Araç çubuğu (arama,
 * fasetler, süzgeç paneli, kapsam, görünüm, sütunlar, çipler) → toplu işlem çubuğu (seçim varken, yapışkan) →
 * tablo (kap ≥ 720 px) / kart listesi (telefon ya da dar kap) / takım görünümü → sayfalama. Çekmece (Sheet), form (ModalShell) ve toplu
 * pencereler durum sahibi burada.
 *
 * Sözleşmeler (korunur): `api.admin.getInventory(showDeleted, scope)`; URL `i_*` süzgeçleri + `i_scope` + `i_view` +
 * `i_sort` + `stat` + `page/ps`; localStorage `inventory-view`; satır `can_manage === false` → tamamen salt okunur;
 * panodan gelen `openAddSignal` / `onAddConsumed`; `domain` derin bağlantısı çekmeceyi açar (açık çekmece adrese yazılır).
 *
 * İsteğe bağlı `onCheckNow` / `checkRunning` / `checkLabel` (2026-09-27): App'in "Şimdi Kontrol Et" akışı başlıkta, birincil
 * "Domain ekle"nin solunda ikincil düğme olarak (`data-tour="check-now"`). Eskiden sayfanın ÜSTÜNDE ayrı bir kontrol satırı
 * (`.controls`) vardı — iki başlık üst üste. Verilmezse düğme yok.
 */
export default function InventoryManager({ onInventoryChange, systemRole, teams: teamsProp = NO_TEAMS, isAdmin: isAdminProp = false, globalAdmin = false, openAddSignal = false, onAddConsumed, onCheckNow, checkRunning = false, checkLabel }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const isMobile = useIsMobile()
  const [pageRef, narrowPage] = useNarrowContainer(720)
  const cardMode = isMobile || narrowPage   // telefon ya da dar içerik alanı (kenar çubuklu tablet) → kart listesi
  const { showConfirm } = useDialog()
  const isAdmin = systemRole ? systemRole === 'ADMIN' : isAdminProp
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  // "Domain Ekle" her kullanıcı seviyesinde (2026-09-18): yetki inventory.crud/edit'ten (USER varsayılanı açık);
  // düzenleme/silme/içe aktarma/hijyen bandı canManage'de kalır. Sunucu üyelik doğrular.
  const perms = usePermissions()
  const canAdd = canManage || perms.canEdit('inventory.crud')
  // Satır düzenleme (2026-09-18): USER yalnız ÜYESİ olduğu takımın kaydını düzenler/kopyalar (teamsProp = /me takımları).
  const myTeamIdSet = useMemo(() => new Set((teamsProp || []).map((tm) => String(tm.id))), [teamsProp])
  // Org geneli görünürlük (2026-09-26): satır YÖNETİLEBİLİR mi — sunucunun `can_manage` bayrağı (başka takımın kaydı
  // = false → her değiştiren kontrol GİZLENİR, salt okunur rozet). Bayrak yoksa (eski yanıt) yönetilebilir sayılır;
  // asıl kapı her yazma ucunda sunucuda.
  const rowManageable = useCallback((r) => r?.can_manage !== false, [])
  const canEditRow = useCallback((r) => rowManageable(r) && (canManage || (canAdd && r?.team_id != null && myTeamIdSet.has(String(r.team_id)))), [rowManageable, canManage, canAdd, myTeamIdSet])
  const [items, setItems]             = useState([])
  const [loadState, setLoadState]     = useState('loading')   // 'loading' | 'ready' | 'error' (yalnız ilk yükleme başarısızsa)
  const [refreshing, setRefreshing]   = useState(false)
  const [lastSync, setLastSync]       = useState(null)
  const [teams, setTeams]             = useState(teamsProp)
  const [formModal, setFormModal]     = useState(null)   // { mode: 'add'|'edit'|'duplicate', record }
  const [transferModal, setTransferModal] = useState(null)
  const [transferTeamId, setTransferTeamId]     = useState('')
  const [saving, setSaving]           = useState(false)
  const [statusFilter, setStatusFilter] = useState(() => readUrlParam('stat', 'default'))
  const [showItem,    setShowItem]    = useState(null)   // çekmece (#8)
  // `domain` derin bağlantısı (2026-09-27): liste gelene kadar bekler, sonra o kaydın çekmecesini açar.
  const [pendingDomain, setPendingDomain] = useState(() => readUrlParam('domain', null))
  const [importOpen,  setImportOpen]  = useState(false)  // CSV içe aktarma (#6)
  const [hygiene,     setHygiene]     = useState(null)   // /inventory/hygiene (#2)
  const [notifGroups, setNotifGroups] = useState([])
  // Süzgeç / sıralama / sütun / yoğunluk / görünüm (#1 #4 #13 #15 #7) — süzgeç URL'de (i_ öneki), gerisi localStorage
  const [filters, setFilters] = useState(() => paramsToFilters(readUrlParam))
  const [sort, setSort]       = useState(() => readUrlParam('i_sort', readView().sort || 'domain|asc'))   // paylaşılan bağlantı sıralamayı taşır (ISSUE-002)
  const [cols, setColsRaw]    = useState(readCols)   // kayıtlı seçim + kullanıcının hiç görmediği yeni varsayılan sütunlar (2026-09-22)
  const [density, setDensityRaw] = useState(() => readView().density || 'comfortable')
  const [view, setViewRaw]    = useState(() => readUrlParam('i_view', readView().view || 'table'))
  // "Takımlarım | Tüm takımlar" (2026-09-26): URL (i_scope) > kayıtlı görünüm > takımlarım; anahtar yalnız sunucu
  // `visible_to_all` derse çizilir (ayar kapalıyken liste bugünkü gibi, anahtar yok).
  const [scope, setScopeRaw]  = useState(() => normalizeScope(readUrlParam('i_scope', readView().scope || 'mine')))
  const [visibleToAll, setVisibleToAll] = useState(false)
  const [colFilters, setColFiltersRaw] = useState(() => !!readView().colFilters)   // kolon süzgeç satırı açık mı (2026-09-22)
  const [platformNames, setPlatformNames] = useState({})   // kod → ad (tablo/süzgeç etiketi, 2026-09-22)
  useEffect(() => {
    let alive = true
    api.admin.listPlatforms().then(r => { if (alive && r?.success) setPlatformNames(Object.fromEntries((r.data || []).map(p => [p.code, p.name]))) }).catch(() => {})
    return () => { alive = false }
  }, [])
  const setColFilters = (v) => { setColFiltersRaw(v); writeView({ colFilters: v }) }
  const [savedViews, setSavedViews] = useState(readSavedViews)
  const setCols = (c) => { setColsRaw(c); writeCols(c) }
  const setDensity = (d) => { setDensityRaw(d); writeView({ density: d }) }
  const setView = (v) => { setViewRaw(v); writeView({ view: v }) }
  const setSortPersist = (v) => { setSort(v); writeView({ sort: v }) }
  const [diag,        setDiag]        = useState(null)   // { domain, port } → DiagnosticsModal
  const [exporting,   setExporting]   = useState(false)

  const teamMap  = Object.fromEntries(teams.map(t => [String(t.id), t.name]))
  // Geçmişteki `teamId` farkı ADA çevrilsin — teamMap dize anahtarlı, geçmiş sayısal.
  const teamNameById = Object.fromEntries(teams.map(t => [t.id, t.name]))

  useEffect(() => {
    // Tek takım modeli: ekleyebilen/düzenleyebilen herkes (admin + team-admin) takım
    // listesine ihtiyaç duyar; team-admin'e backend yalnız kapsamındaki takımları döner.
    if (canManage) api.admin.getTeams().then(res => { if (res?.success) setTeams(res.data) })
    api.notificationGroups.list().then(res => { if (res?.success) setNotifGroups(res.data?.groups ?? res.data ?? []) }).catch(() => {})
  }, []) // eslint-disable-line react-hooks/exhaustive-deps -- yalnız ilk açılışta (rol oturum boyunca değişmez)
  // Liste kapsamla yeniden çekilir (ilk yükleme dâhil); seçim kapsam değişince temizlenir (aşağıda setScope).
  useEffect(() => { load() }, [scope]) // eslint-disable-line react-hooks/exhaustive-deps

  // Hijyen bandı (#2): yalnız yönetebilenler (sunucu da aynı kapıyı uygular); 5 dk'da bir görünürken tazelenir.
  const loadHygiene = useCallback(async () => {
    if (!canManage) return
    try { const r = await api.admin.getInventoryHygiene(); if (r?.success) setHygiene(r.data) } catch { /* bant süs */ }
  }, [canManage])
  useVisibleInterval(loadHygiene, 300_000, true)

  // Prop senkronu YALNIZ yönetemeyenler için. `teams` iki farklı sahibi olan bir state:
  // canManage ise yukarıdaki çekim sahiplenir (team-admin'e kapsamlı liste döner), aksi halde
  // prop. Koşulsuz bir senkron çekilen listeyi EZERDİ — üstelik `teamsProp = []` varsayılanı her
  // parent render'ında yeni dizi kimliği olduğundan efekt sürekli tetiklenir ve team-admin kalıcı
  // olarak yanlış takım listesi görürdü. Yönetemeyenlerde state prop'un ilk değerinde donuyordu.
  useEffect(() => {
    if (!canManage) setTeams(teamsProp)
  }, [canManage, teamsProp])

  async function doExport(kind) {
    setExporting(true)
    try {
      const res = await api.admin.getInventory(false, scope)   // dışa aktarma ekrandaki kapsamı taşır
      if (!res?.success) {
        toast.error(t('inv.exportError'))
        return
      }
      const all = res.data ?? []
      if (all.length === 0) {
        toast.error(t('inv.exportNoData'))
        return
      }
      const n = kind === 'csv'
        // CSV 7/24 grup ADLARINI yazar (liste yanıtında yalnız kimlik var) — seçenekler önbellekli (formla ortak)
        ? exportInventoryCsv(all, teams, t, await loadNocGroupNames())
        : await exportInventoryPdf(all, teams, t)
      toast.success(t('inv.exportSuccess', n))
    } catch {
      toast.error(t('inv.exportError'))
    } finally {
      setExporting(false)
    }
  }

  // Fetch yarışı: kapsam anahtarı, Yenile ve kayıt/silme sonrası tazelemeler art arda istek çıkarır; geç dönen
  // "all" yanıtı "mine" listesini (ya da tersini) ezmesin. Yalnız EN SON isteğin yanıtı uygulanır.
  const loadSeq = useRef(0)
  /** Liste tazeleme; EN SON isteğin yanıtıysa yeni listeyi de döner (mükerrer bandı sonrası kaydı açmak için), aksi hâlde undefined. */
  async function load() {
    const my = ++loadSeq.current
    try {
      const res = await api.admin.getInventory(true, scope)
      if (my !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) {
        setItems(res.data ?? [])
        setVisibleToAll(!!res.visible_to_all)
        setLastSync(new Date())
        setLoadState('ready')
        // Sunucu isteği daraltmışsa (ayar kapalı / izin yok) anahtar GERÇEKTE uygulanan kapsamı gösterir.
        if (res.scope && normalizeScope(res.scope) !== scope) setScopeRaw(normalizeScope(res.scope))
        return res.data ?? []
      } else {
        toast.error(res?.error || t('inv.loadError'))
        setLoadState((s) => (s === 'ready' ? s : 'error'))
      }
    } catch {
      if (my !== loadSeq.current) return
      toast.error(t('inv.loadError'))
      setLoadState((s) => (s === 'ready' ? s : 'error'))
    }
  }
  async function refresh() {
    setRefreshing(true)
    try { await Promise.all([load(), loadHygiene()]) } finally { setRefreshing(false) }
  }

  const liveItems = useMemo(() => items.filter(i => !i.deleted_at), [items])
  const stats = useMemo(() => ({ deleted: items.length - liveItems.length }), [items, liveItems])
  const teamCount = useMemo(() => new Set(liveItems.filter(i => i.team_id != null).map(i => String(i.team_id))).size, [liveItems])

  const statusItems = useMemo(() => {
    switch (statusFilter) {
      case 'active':   return items.filter(i => i.active   && !i.deleted_at)
      case 'inactive': return items.filter(i => !i.active  && !i.deleted_at)
      case 'deleted':  return items.filter(i => !!i.deleted_at)
      default:         return items.filter(i => !i.deleted_at)
    }
  }, [items, statusFilter])
  const hygieneIdx = useMemo(() => hygieneIndex(hygiene), [hygiene])
  const visibleItems = useMemo(() => sortItems(applyFilters(statusItems, filters, hygieneIdx), sort), [statusItems, filters, hygieneIdx, sort])
  const overlaps = useMemo(() => detectOverlaps(items), [items])
  const groupNames = useMemo(() => [...new Set(statusItems.map(i => i.group_name).filter(Boolean))].sort(), [statusItems])
  const platformCodes = useMemo(() => [...new Set(statusItems.map(i => i.platform).filter(Boolean))].sort(), [statusItems])
  const tagNames = useMemo(() => {
    const m = new Map()
    for (const r of statusItems) for (const x of String(r.tags || '').split(',').map(s => s.trim()).filter(Boolean)) if (!m.has(x.toLowerCase())) m.set(x.toLowerCase(), x)
    return [...m.values()].sort((a, b) => a.localeCompare(b))
  }, [statusItems])
  const facets = useMemo(() => facetCounts(statusItems), [statusItems])

  /** Özet kartı basışı → durum süzgeci + süzgeç yaması (inventoryModel.applyTile; kartlar arasında tek seçim). */
  function onTile(key) {
    const next = applyTile(key, statusFilter, filters)
    setStatusFilter(next.statusFilter)
    setFilters(next.filters)
    if (view !== 'table') setView('table')
  }

  // ── Toplu seçim (yalnız silinmemiş kayıtlar seçilebilir) ──────────────────
  const [selected, setSelected] = useState(() => new Set())
  const [contactsModal, setContactsModal] = useState(null)   // E5: toplu sorumlu ekip atama
  // Yabancı (salt okunur) satır toplu işleme SEÇİLEMEZ — sayaçlar ve "tümünü seç" onları atlar.
  const selectableItems = useMemo(() => visibleItems.filter(i => !i.deleted_at && rowManageable(i)), [visibleItems, rowManageable])
  const setScope = (v) => { const n = normalizeScope(v); setScopeRaw(n); writeView({ scope: n }); setSelected(new Set()) }

  // Sayfalama yalnız RENDER'ı böler; "tümünü seç" filtrelenmiş tüm liste (selectableItems) üzerinde kalır.
  const filterKey = JSON.stringify(filters)
  const pager = usePagination(visibleItems, {
    listKey: 'inventory', preset: 'page', resetDeps: [statusFilter, filterKey, sort],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: durum filtresi + süzgeçler (i_*) + görünüm + sayfa/boyut + açık çekmece (domain).
  useUrlQuerySync({
    stat: statusFilter !== 'default' ? statusFilter : null,
    ...filtersToParams(filters),
    i_view: view !== 'table' ? view : null,
    i_scope: scope === SCOPE_ALL ? SCOPE_ALL : null,   // paylaşılan bağlantı kapsamı da taşır
    i_sort: sort !== 'domain|asc' ? sort : null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
    domain: showItem?.domain ?? pendingDomain ?? null,
  })

  // Derin bağlantı: liste ilk kez geldiğinde `domain` kaydını bul, çekmeceyi aç (bir kez); bulunamazsa bekleyen düşer.
  useEffect(() => {
    if (!pendingDomain || loadState !== 'ready') return
    const want = pendingDomain.toLowerCase()
    const hit = items.find(i => (i.domain || '').toLowerCase() === want)
    if (hit) {
      if (hit.deleted_at) setStatusFilter('deleted')
      setShowItem(hit)
    }
    setPendingDomain(null)
  }, [pendingDomain, loadState, items])

  const allOnPage = selectableItems.length > 0 && selectableItems.every(i => selected.has(i.id))
  const toggleSel = (id) => setSelected(s => {
    const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n
  })
  const toggleAll = () => setSelected(s => {
    const n = new Set(s)
    if (allOnPage) selectableItems.forEach(i => n.delete(i.id))
    else selectableItems.forEach(i => n.add(i.id))
    return n
  })
  // Filtre değişince seçimi temizle (görünmeyen satırlar seçili kalmasın)
  useEffect(() => { setSelected(new Set()) }, [statusFilter, filterKey])

  // ── Kayıtlı görünümler (#13) + paylaşım bağlantısı ──
  function saveView(name) {
    const v = { name, filters, sort, cols, colsKnown: colKeys(), density, statusFilter, view }
    const next = [...savedViews.filter(x => x.name !== name), v]
    setSavedViews(next); writeSavedViews(next); toast.success(t('inv.viewSaved', name))
  }
  function applyView(v) {
    setFilters({ ...EMPTY_FILTERS, ...(v.filters || {}) }); setSortPersist(v.sort || 'domain|asc'); setCols(restoreCols(v.cols, v.colsKnown))
    setDensity(v.density || 'comfortable'); setStatusFilter(v.statusFilter || 'default'); setView(v.view || 'table')
  }
  /** Tüm süzgeçleri sıfırla — boş durumdan ve tablo içi "eşleşme yok" satırından çağrılır (2026-09-22). */
  function clearFilters() { setFilters({ ...EMPTY_FILTERS }) }
  function deleteView(name) { const next = savedViews.filter(x => x.name !== name); setSavedViews(next); writeSavedViews(next) }
  async function copyLink() {
    try { await navigator.clipboard.writeText(window.location.href); toast.success(t('inv.copied')) } catch { toast.error(t('inv.copyFailed')) }
  }

  // ── Satır-içi düzenleme (#8): tier / aktif — tam kayıt + yama (snake_case; uç tam gövde bekler) ──
  async function inlineUpdate(r, patch) {
    const body = { ...r, ...patch }
    for (const k of Object.keys(body)) if (k.startsWith('cert_') || k === 'team_name' || k === 'ug_team_name') delete body[k]
    try {
      const res = await api.admin.updateInventory(r.id, body)
      if (res?.success) { toast.success(t('inv.inlineSaved', r.domain)); setItems(list => list.map(x => x.id === r.id ? { ...x, ...patch } : x)); onInventoryChange?.() }
      else toast.error(res?.error || t('inv.saveError'))
    } catch (e) { toast.error(e?.message || t('inv.saveError')) }
  }

  // ── Şimdi kontrol et (#11): mevcut sağlık tazeleme ucu; sonuç satıra işlenir ──
  async function checkNow(r) {
    try {
      const res = await api.refreshCertificateHealth(r.domain)
      if (res?.success) { toast.success(t('inv.checkNowOk', r.domain)); load(); loadHygiene() }
      else toast.error(res?.error || t('inv.checkNowErr'))
    } catch (e) { toast.error(e?.message || t('inv.checkNowErr')) }
  }

  async function bulkAction(action) {
    const ids = [...selected]
    if (ids.length === 0) return
    const cfg = {
      activate:   { title: t('inv.bulkActivateTitle'),   msg: t('inv.bulkActivateMsg', ids.length),   confirm: t('inv.bulkActivateBtn'),   variant: 'warning' },
      deactivate: { title: t('inv.bulkDeactivateTitle'), msg: t('inv.bulkDeactivateMsg', ids.length), confirm: t('inv.bulkDeactivateBtn'), variant: 'warning' },
      delete:     { title: t('inv.bulkDeleteTitle'),     msg: t('inv.bulkDeleteMsg', ids.length),     confirm: t('inv.bulkDeleteBtn'),     variant: 'danger'  },
    }[action]
    const ok = await showConfirm({
      title: cfg.title, message: cfg.msg, variant: cfg.variant,
      confirmText: cfg.confirm, cancelText: t('inv.cancel'),
    })
    if (!ok) return
    const res = await api.admin.bulkInventory(ids, action)
    if (res?.success) {
      const d = res.data || {}
      toast.success(t('inv.bulkDone', d.processed ?? 0, d.skipped ?? 0))
      setSelected(new Set())
      load()
      onInventoryChange?.()
    } else {
      toast.error(res?.error || t('inv.saveError'))
    }
  }

  /**
   * E5 — seçili kayıtlara sorumlu ekip ata.
   *
   * YALNIZ doldurulmuş alanlar gönderilir: boş bırakılan alan gövdeye HİÇ konmaz, böylece
   * backend ona dokunmaz. Aksi halde "sadece IISAdmin'i doldurup 200 kayda uygula" isteği
   * diğer üç alanı sessizce silerdi.
   */
  async function applyBulkContacts(values) {
    const ids = [...selected]
    if (ids.length === 0) return
    const extra = {}
    for (const [k, v] of Object.entries(values)) {
      if ((v ?? '').trim()) extra[k] = v.trim()
    }
    if (Object.keys(extra).length === 0) { toast.error(t('inv.bulkContactsEmpty')); return }

    const ok = await showConfirm({
      title: t('inv.bulkContactsTitle'),
      message: t('inv.bulkContactsMsg', ids.length, Object.keys(extra).length),
      variant: 'warning',
      confirmText: t('inv.bulkContactsBtn'), cancelText: t('inv.cancel'),
    })
    if (!ok) return

    const res = await api.admin.bulkInventory(ids, 'set-contacts', extra)
    if (res?.success) {
      const d = res.data || {}
      toast.success(t('inv.bulkDone', d.processed ?? 0, d.skipped ?? 0))
      setContactsModal(null)
      setSelected(new Set())
      load()
      onInventoryChange?.()
    } else {
      toast.error(res?.error || t('inv.saveError'))
    }
  }

  /**
   * Form kaydı sonrası tazeleme. Mükerrer alan adı bandından aktarım / geri yükleme (2026-09-28) `opts.open` ile gelir:
   * form kapanır, liste tazelenir ve taşınan / geri yüklenen kaydın çekmecesi açılır — tazelenmiş satır (can_manage,
   * takım adı) tercih edilir, liste kapsamı dışındaysa uçtan dönen kayıt.
   */
  async function afterFormSaved(domain, opts) {
    const list = await load()
    if (!opts?.open) return
    const rec = opts.record || null
    const want = String(domain || rec?.domain || '').toLowerCase()
    const hit = (list || []).find(i => rec?.id != null && i.id === rec.id)
      ?? (list || []).find(i => (i.domain || '').toLowerCase() === want)
      ?? rec
    if (hit) {
      if (hit.deleted_at) setStatusFilter('deleted')
      setShowItem(hit)
    }
  }

  function openAdd() { setFormModal({ mode: 'add', record: null }) }
  function openEdit(item) { setFormModal({ mode: 'edit', record: item }) }
  function openDuplicate(item) { setFormModal({ mode: 'duplicate', record: item }) }
  // Devret modalı: 964dfd1a formu ayrı modale çıkarırken bu yardımcı silinmiş ama satır
  // eylemindeki çağrısı kalmıştı → "Devret" tıklaması ReferenceError ile ErrorBoundary'ye
  // düşüyordu (yalnız isAdmin && teams.length > 1 koşulunda göründüğü için fark edilmemiş).
  function openTransfer(item) {
    setTransferModal(item)
    setTransferTeamId(String(item.team_id ?? ''))
  }

  // Dashboard'daki "domain ekle" butonundan tetiklenince add modalını aç (bir kez; App tüketince sıfırlar).
  // 2026-09-26: sinyal `canAdd` kapısına UYAR — eskiden ekleme yetkisi olmayan kullanıcıya da form açılıyordu (Ekle
  // düğmesi gizliyken). Yetki anlık görüntüsü henüz gelmediyse sinyal bekletilir; geldi ve ekleme yoksa düşürülür.
  const permsLoaded = Object.keys(perms.perms || {}).length > 0
  useEffect(() => {
    if (!openAddSignal) return
    if (canAdd) { openAdd(); onAddConsumed?.() }
    else if (permsLoaded) onAddConsumed?.()
  }, [openAddSignal, canAdd, permsLoaded]) // eslint-disable-line react-hooks/exhaustive-deps

  async function del(id) {
    const item = items.find(i => i.id === id)
    const domain = item?.domain
    if (!domain) return

    let openAlertCount = 0
    try {
      const r = await api.admin.getAlerts({ domain, resolved: 'false', size: 1 })
      if (r?.success) openAlertCount = r.total ?? 0
    } catch { /* sayım hatasını yut, varsayılan mesajla devam */ }

    const baseMessage = t('inv.deleteMsg', domain)
    const message = openAlertCount > 0
      ? `${baseMessage}\n\n⚠ ${t('inv.deleteHasAlerts', openAlertCount)}\n${t('inv.deleteAlertWarning')}`
      : baseMessage

    const ok = await showConfirm({
      title: t('inv.deleteTitle'),
      message,
      variant: openAlertCount > 0 ? 'warning' : 'danger',
      confirmText: t('inv.deleteConfirm'),
      cancelText: t('inv.deleteCancel'),
    })
    if (!ok) return

    const res = await api.admin.deleteInventory(id)
    if (res?.success) {
      toast.success((res.alertsClosed ?? 0) > 0
        ? t('inv.deleteWithAlerts', domain, res.alertsClosed)
        : t('inv.deleted', domain))
      setShowItem((s) => (s?.id === id ? null : s))
    } else {
      toast.error(res?.error || 'Error')
    }
    load()
    onInventoryChange?.()
  }

  async function restore(id) {
    const item = items.find(i => i.id === id)
    const ok = await showConfirm({
      title: t('inv.restoreTitle'),
      message: t('inv.restoreMsg', item?.domain ?? id),
      variant: 'warning',
      confirmText: t('inv.restoreConfirm'),
      cancelText: t('inv.deleteCancel'),
    })
    if (!ok) return
    const res = await api.admin.restoreInventory(id)
    if (res?.success) {
      toast.success(t('inv.restored'))
      load()
      onInventoryChange?.()
    } else {
      toast.error(res?.error || 'Error')
    }
  }

  // Kalıcı sil (geri alınamaz) — yalnız admin. Envanter + o domain'in kontrol geçmişi silinir.
  async function purge(id) {
    const item = items.find(i => i.id === id)
    const ok = await showConfirm({
      title: t('inv.purgeTitle'),
      message: t('inv.purgeMsg', item?.domain ?? id),
      variant: 'danger',
      confirmText: t('inv.purgeConfirm'),
      cancelText: t('inv.deleteCancel'),
    })
    if (!ok) return
    const res = await api.admin.purgeInventory(id)
    if (res?.success) {
      toast.success(t('inv.purged', item?.domain ?? id))
      load()
      onInventoryChange?.()
    } else {
      toast.error(res?.error || 'Error')
    }
  }

  // Toplu kalıcı sil — tüm silinmiş kayıtlar + kontrol geçmişleri. Yalnız admin.
  async function purgeAll() {
    const ok = await showConfirm({
      title: t('inv.purgeAllTitle'),
      message: t('inv.purgeAllMsg', stats.deleted),
      variant: 'danger',
      confirmText: t('inv.purgeAllConfirm'),
      cancelText: t('inv.deleteCancel'),
    })
    if (!ok) return
    const res = await api.admin.purgeDeletedInventory()
    if (res?.success) {
      toast.success(t('inv.purgedAll', res.data?.purged ?? 0))
      load()
      onInventoryChange?.()
    } else {
      toast.error(res?.error || 'Error')
    }
  }

  async function doTransfer() {
    const changed = transferTeamId !== String(transferModal.team_id ?? '')
    if (!changed || !transferTeamId) { setTransferModal(null); return }
    setSaving(true)
    try {
      let res
      try {
        res = await api.admin.transferCertSy(transferModal.id, Number(transferTeamId))
      } catch (e) {
        toast.error(e?.message || t('inv.transferError'))
        return
      }
      if (res?.success) {
        setTransferModal(null)
        toast.success(t('inv.transferred'))
        load()
      } else {
        toast.error(res?.error || t('inv.transferError'))
      }
    } finally {
      setSaving(false)
    }
  }

  // Satır/kart eylemleri — tablo ve telefon kartı AYNI sözleşme.
  const rowProps = {
    canManage, canEditRow, canManageRow: rowManageable, isAdmin, teamsCount: teams.length, teamMap, selected, onToggle: toggleSel,
    onShow: (r) => setShowItem(r), onEdit: openEdit, onDuplicate: openDuplicate, onTransfer: openTransfer,
    onDelete: del, onRestore: restore, onPurge: purge,
    onDiagnose: (r) => setDiag({ domain: r.domain, port: r.port || 443 }),
    onCheckNow: checkNow, onInline: inlineUpdate,
    onTagClick: (tag) => setFilters(f => ({ ...f, q: tag })),
    onGroupClick: (group) => setFilters(f => ({ ...f, group })),
    platformNames, onClearFilters: hasActiveFilter(filters) ? clearFilters : null,
  }
  const lastSyncText = lastSync ? new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' }).format(lastSync) : null
  const emptyInventory = loadState === 'ready' && statusItems.length === 0

  return (
    // `.tab-content h2` (App.css, katmansız) PageHeader başlığına alt boşluk/boy basıyordu → bu sayfada yerel sıfırlama.
    <div ref={pageRef} data-slot="inventory-page" data-layout={cardMode ? 'cards' : 'table'} className="mb-8 min-w-0 [&_[data-slot=page-title]]:mb-0! [&_[data-slot=page-title]]:text-xl! sm:[&_[data-slot=page-title]]:text-2xl!">
      <PageHeader icon={Globe} title={t('inv.title')} description={t('inv.pageDesc')}
        meta={loadState === 'ready' ? (
          <>
            <Badge variant="outline" data-slot="inv-meta-domains" className="gap-1 font-normal"><Globe aria-hidden="true" />{t('inv.metaDomains', liveItems.length)}</Badge>
            <Badge variant="outline" data-slot="inv-meta-teams" className="gap-1 font-normal"><Building2 aria-hidden="true" />{t('inv.metaTeams', teamCount)}</Badge>
            {lastSyncText && <Badge variant="outline" data-slot="inv-meta-sync" className="gap-1 font-normal"><Clock aria-hidden="true" />{t('inv.metaLastSync', lastSyncText)}</Badge>}
          </>
        ) : null}
        actions={(
          <>
            <Button type="button" variant="outline" onClick={refresh} disabled={refreshing} aria-busy={refreshing || undefined} aria-label={t('inv.refresh')} title={t('inv.refresh')}>
              <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />
              <span className="hidden lg:inline">{t('inv.refresh')}</span>
            </Button>
            {/* Dışa aktar: shadcn DropdownMenu (klavye, Escape, dış tıklama bileşenden). */}
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button type="button" variant="outline" disabled={exporting} className="group/exp">
                  {exporting ? <Spinner size={14} inline decorative /> : <Download aria-hidden="true" />}
                  {t('inv.export')}
                  <ChevronDown aria-hidden="true" className="size-3 transition-transform duration-150 group-data-[state=open]/exp:rotate-180 motion-reduce:transition-none" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="z-(--z-menu) w-[min(280px,calc(100vw-2rem))]">
                <DropdownMenuItem className="flex-col items-start gap-0.5" onSelect={() => doExport('csv')}>
                  <span className="font-semibold">{t('inv.exportCsv')}</span>
                  <span className="text-xs text-muted-foreground">{t('inv.exportCsvHint')}</span>
                </DropdownMenuItem>
                <DropdownMenuItem className="flex-col items-start gap-0.5" onSelect={() => doExport('pdf')}>
                  <span className="font-semibold">{t('inv.exportPdf')}</span>
                  <span className="text-xs text-muted-foreground">{t('inv.exportPdfHint')}</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            {canManage && <Button type="button" variant="outline" onClick={() => setImportOpen(true)}><Upload aria-hidden="true" /> {t('inv.import')}</Button>}
            {/* Şimdi Kontrol Et (App akışı): ikincil, birincil "Domain ekle"nin solunda; ekleme yetkisi yoksa birincil */}
            {onCheckNow && (
              <Button type="button" variant={canAdd ? 'secondary' : 'default'} data-tour="check-now" onClick={onCheckNow}
                disabled={checkRunning} aria-busy={checkRunning || undefined} title={t('app.checkNowTip')}>
                {checkRunning ? <Spinner size={14} inline decorative /> : <PlayCircle aria-hidden="true" />}
                <span className="tabular-nums">{checkLabel || t('app.checkNow')}</span>
              </Button>
            )}
            {canAdd && <Button type="button" onClick={openAdd}><Plus aria-hidden="true" /> {t('inv.addBtn')}</Button>}
          </>
        )} />

      {loadState === 'ready' && (
        <InventoryStats items={items} statusFilter={statusFilter} filters={filters} onTile={onTile} />
      )}

      {canManage && (
        <InventoryHygieneBand data={hygiene} overlaps={overlaps} active={filters.hygiene}
          onSelect={(code) => { setFilters(f => ({ ...f, hygiene: code })); setStatusFilter('default'); setView('table') }}
          onOpenDomain={(d) => { const r = items.find(i => i.domain === d); if (r) setShowItem(r) }} />
      )}

      <InventoryToolbar filters={filters} onFilters={setFilters} teams={teams} groupNames={groupNames} notifGroups={notifGroups}
        tagNames={tagNames} platformCodes={platformCodes} platformNames={platformNames} facets={facets}
        shown={visibleItems.length} total={statusItems.length}
        cols={cols} onCols={setCols} sort={sort} onSort={setSortPersist} density={density} onDensity={setDensity}
        view={view} onView={setView} savedViews={savedViews} onSaveView={saveView} onApplyView={applyView} onDeleteView={deleteView} onCopyLink={copyLink}
        colFilters={colFilters} onColFilters={setColFilters} tableMode={!cardMode}
        scope={scope} onScope={setScope} visibleToAll={visibleToAll} />

      {/* Toplu işlem çubuğu — seçim varken, liste kaydırılırken üstte yapışkan; telefonda düğmeler sarar */}
      {canManage && selected.size > 0 && (
        <div role="region" aria-label={t('inv.bulkSelected', selected.size)} data-slot="inv-bulk-bar"
          className="sticky top-16 z-20 mb-2.5 flex flex-wrap items-center gap-2 rounded-[10px] border border-primary bg-card px-2.5 py-2 shadow-lg md:top-2">
          <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10" onClick={toggleAll}
            aria-label={allOnPage ? t('bulk.unselectAll') : t('bulk.selectAll')} title={allOnPage ? t('bulk.unselectAll') : t('bulk.selectAll')}>
            {allOnPage ? <CheckSquare aria-hidden="true" /> : <Square aria-hidden="true" />}
          </Button>
          <span className="mr-1 text-[.9em] font-bold">{t('inv.bulkSelected', selected.size)}</span>
          <div className="flex flex-wrap items-center gap-1.5">
            <Button variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => bulkAction('activate')}><Power aria-hidden="true" className="text-success" /> {t('inv.bulkActivateBtn')}</Button>
            <Button variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => bulkAction('deactivate')}><PowerOff aria-hidden="true" className="text-amber-600" /> {t('inv.bulkDeactivateBtn')}</Button>
            <Button variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => setContactsModal({})}><UserPlus aria-hidden="true" /> {t('inv.bulkContactsBtn')}</Button>
            <Button variant="destructive" size="sm" className="pointer-coarse:h-10" onClick={() => bulkAction('delete')}><Trash2 aria-hidden="true" /> {t('inv.bulkDeleteBtn')}</Button>
          </div>
          <Button type="button" variant="ghost" size="icon-sm" className="ml-auto text-muted-foreground pointer-coarse:size-10" onClick={() => setSelected(new Set())}
            aria-label={t('inv.bulkClear')} title={t('inv.bulkClear')}>
            <X aria-hidden="true" />
          </Button>
        </div>
      )}

      {isAdmin && statusFilter === 'deleted' && stats.deleted > 0 && (
        <AlertBanner tone="danger" icon={AlertTriangle} className="mb-2.5"
          actions={<Button variant="destructive" size="sm" onClick={purgeAll}>{t('inv.purgeAllBtn')}</Button>}>
          {t('inv.purgeAllHint', stats.deleted)}
        </AlertBanner>
      )}

      {loadState === 'loading' ? (
        <InventorySkeleton label={t('inv.loading')} />
      ) : loadState === 'error' ? (
        <StatusBlock tone="danger" icon={AlertTriangle} role="alert" title={t('inv.loadError')}
          actions={<Button type="button" variant="outline" size="sm" onClick={refresh}><RefreshCw aria-hidden="true" /> {t('inv.loadRetry')}</Button>} />
      ) : view === 'team' ? (
        <InventoryTeamView rows={visibleItems} onShow={(r) => setShowItem(r)} platformNames={platformNames}
          onFilterTeam={(teamId) => { setFilters(f => ({ ...f, team: teamId == null ? '' : String(teamId) })); setView('table') }} />
      ) : emptyInventory || (visibleItems.length === 0 && !(colFilters && !cardMode && statusItems.length > 0)) ? (
        /* Kolon süzgeç satırı açıkken tablo ayakta kalır (aşağıda); kapalıyken boş durum + Temizle (2026-09-22 QA). */
        <StatusBlock tone="neutral" icon={Inbox} className="rounded-[10px] border border-dashed"
          title={emptyInventory ? t('inv.emptyTitle') : t('inv.noMatch')}
          description={emptyInventory ? (canManage ? t('inv.emptyHintAdmin') : t('inv.emptyHint')) : t('empty.hintFilter')}
          actions={!emptyInventory && hasActiveFilter(filters)
            ? <Button type="button" variant="secondary" size="sm" onClick={clearFilters}>{t('inv.filterClear')}</Button>
            : emptyInventory && statusFilter === 'default' && canAdd
              ? <>
                  <Button type="button" size="sm" onClick={openAdd}><Plus aria-hidden="true" /> {t('inv.addBtn')}</Button>
                  {canManage && <Button type="button" variant="outline" size="sm" onClick={() => setImportOpen(true)}><Upload aria-hidden="true" /> {t('inv.import')}</Button>}
                </>
              : null} />
      ) : (
        <>
          {cardMode
            ? <InventoryCardList rows={pager.pageItems} {...rowProps} />
            : (
              <InventoryTable rows={pager.pageItems} cols={cols} sort={sort} onSort={setSortPersist} density={density}
                statusFilter={statusFilter} onToggleAll={toggleAll} allOnPage={allOnPage}
                filters={filters} onFilters={setFilters} allRows={statusItems} showFilters={colFilters}
                {...rowProps} />
            )}
          {pager.pageItems.length > 0 && <PaginationBar {...pager} />}
        </>
      )}

      {/* ── Ana Form Modalı — inventory/InventoryFormModal.jsx (dashboard kartı da aynı formu açıyor). key: açıkken mod/kayıt değişirse remount olsun. ── */}
      {contactsModal && (
        <BulkContactsModal
          count={selected.size}
          onApply={applyBulkContacts}
          onClose={() => setContactsModal(null)}
        />
      )}

      {formModal && (
        <InventoryFormModal
          key={`${formModal.mode}:${formModal.record?.id ?? 'new'}`}
          mode={formModal.mode}
          record={formModal.record}
          teams={teams}
          canManage={canManage}
          canWrite={canAdd}
          // R4: düzenlemede takım aktarımı yalnız rol ADMIN'de — sunucu TEAM_ADMIN/USER için takımı sabitler.
          canMoveTeam={isAdmin}
          canOpenSettings={globalAdmin}   // 7/24 alanı: "aktif grup yok" → Ayarlar bağlantısı YALNIZ global yöneticiye (grup tanımlayan o)
          onClose={() => setFormModal(null)}
          onSaved={(_res, domain, opts) => { setFormModal(null); afterFormSaved(domain, opts); onInventoryChange?.() }}
        />
      )}

      {/* ── Kayıt çekmecesi (#8): Genel bakış / Sertifika / Değişiklikler / Kontroller; önceki-sonraki gezinme ── */}
      {showItem && (
        <InventoryDrawer record={showItem} records={visibleItems} teamMap={teamMap} teamNameById={teamNameById} canManage={canManage} canEditRow={canEditRow}
          readOnly={!rowManageable(showItem)} platformNames={platformNames}
          onClose={() => setShowItem(null)} onEdit={(r) => { setShowItem(null); openEdit(r) }} onCheckNow={checkNow} onDelete={del} onNavigate={setShowItem} />
      )}
      {importOpen && (
        <InventoryImportModal onClose={() => setImportOpen(false)} onDone={() => { load(); loadHygiene(); onInventoryChange?.() }} />
      )}
      {/* ── Transfer Modalı (ModalShell / shadcn Dialog) ── */}
      {transferModal && (
        <ModalShell open onClose={() => setTransferModal(null)} busy={saving} size="sm"
          title={t('inv.transferTitle', transferModal.domain)} icon={Users}
          footer={(
            <>
              <Button variant="secondary" onClick={() => setTransferModal(null)}>{t('inv.cancel')}</Button>
              <Button onClick={doTransfer} disabled={saving}>
                {saving ? t('inv.saving') : t('inv.transferConfirm')}
              </Button>
            </>
          )}>
          <Field label={t('inv.newTeam')}>
            {({ id }) => (
              <SearchableSelect
                id={id}
                value={transferTeamId}
                onChange={v => setTransferTeamId(v)}
                searchThreshold={2}
                options={teams.map(team => ({ value: team.id, label: team.name }))}
              />
            )}
          </Field>
        </ModalShell>
      )}

      {/* ── Bağlantı/SSL/Ağ Tanılama Modalı (envanter + durum izleme ortak) ── */}
      {diag && (
        <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />
      )}
    </div>
  )
}
