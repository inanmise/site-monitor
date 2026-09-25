import { useState, useEffect, useRef, useMemo, useCallback } from 'react'
import { ChevronDown, Download, Users, Upload, Plus } from 'lucide-react'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { api } from '../../api/client'
import InventoryFormModal from '../inventory/InventoryFormModal.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import DiagnosticsModal from './DiagnosticsModal.jsx'
import { exportInventoryCsv, exportInventoryPdf } from '../../utils/exportInventory'
import { Spinner } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Inbox } from 'lucide-react'
import InventoryToolbar from '../inventory/InventoryToolbar.jsx'
import InventoryHygieneBand, { hygieneIndex } from '../inventory/InventoryHygieneBand.jsx'
import InventoryTable from '../inventory/InventoryTable.jsx'
import InventoryTeamView from '../inventory/InventoryTeamView.jsx'
import InventoryImportModal from '../inventory/InventoryImportModal.jsx'
import InventoryDrawer from '../inventory/InventoryDrawer.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import {
  applyFilters, sortItems, detectOverlaps, filtersToParams, paramsToFilters, readView, writeView, readCols, writeCols, restoreCols, colKeys,
  readSavedViews, writeSavedViews, EMPTY_FILTERS, hasActiveFilter,
} from '../inventory/inventoryModel.js'
import { Button } from '@/components/shadcn/button'

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
            <input id={id} className="input" maxLength={300} value={values[key]}
              placeholder={t('inv.contactsPh')}
              onChange={e => setValues(v => ({ ...v, [key]: e.target.value }))} />
          )}
        </Field>
      ))}
    </ModalShell>
  )
}

// Varsayılan boş liste SABİT: `= []` her render'da yeni kimlik üretir ve aşağıdaki prop-senkron efekti sonsuz döngüye girer (USER + teams verilmeden).
const NO_TEAMS = []

export default function InventoryManager({ onInventoryChange, systemRole, teams: teamsProp = NO_TEAMS, isAdmin: isAdminProp = false, openAddSignal = false, onAddConsumed }) {
  const t = useT()
  const toast = useToast()
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
  const canEditRow = useCallback((r) => canManage || (canAdd && r?.team_id != null && myTeamIdSet.has(String(r.team_id))), [canManage, canAdd, myTeamIdSet])
  const [items, setItems]             = useState([])
  const [teams, setTeams]             = useState(teamsProp)
  const [formModal, setFormModal]     = useState(null)   // { mode: 'add'|'edit'|'duplicate', record }
  const [transferModal, setTransferModal] = useState(null)
  const [transferTeamId, setTransferTeamId]     = useState('')
  const [saving, setSaving]           = useState(false)
  const [statusFilter, setStatusFilter] = useState(() => readUrlParam('stat', 'default'))
  const [showItem,    setShowItem]    = useState(null)   // çekmece (#8)
  const [importOpen,  setImportOpen]  = useState(false)  // CSV içe aktarma (#6)
  const [hygiene,     setHygiene]     = useState(null)   // /inventory/hygiene (#2)
  const [notifGroups, setNotifGroups] = useState([])
  // Süzgeç / sıralama / sütun / yoğunluk / görünüm (#1 #4 #13 #15 #7) — süzgeç URL'de (i_ öneki), gerisi localStorage
  const [filters, setFilters] = useState(() => paramsToFilters(readUrlParam))
  const [sort, setSort]       = useState(() => readUrlParam('i_sort', readView().sort || 'domain|asc'))   // paylaşılan bağlantı sıralamayı taşır (ISSUE-002)
  const [cols, setColsRaw]    = useState(readCols)   // kayıtlı seçim + kullanıcının hiç görmediği yeni varsayılan sütunlar (2026-09-22)
  const [density, setDensityRaw] = useState(() => readView().density || 'comfortable')
  const [view, setViewRaw]    = useState(() => readUrlParam('i_view', readView().view || 'table'))
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
  const [exportOpen,  setExportOpen]  = useState(false)
  const [exporting,   setExporting]   = useState(false)
  const exportRef = useRef(null)

  const teamMap  = Object.fromEntries(teams.map(t => [String(t.id), t.name]))
  // Geçmişteki `teamId` farkı ADA çevrilsin — teamMap dize anahtarlı, geçmiş sayısal.
  const teamNameById = Object.fromEntries(teams.map(t => [t.id, t.name]))

  useEffect(() => {
    load()
    // Tek takım modeli: ekleyebilen/düzenleyebilen herkes (admin + team-admin) takım
    // listesine ihtiyaç duyar; team-admin'e backend yalnız kapsamındaki takımları döner.
    if (canManage) api.admin.getTeams().then(res => { if (res?.success) setTeams(res.data) })
    api.notificationGroups.list().then(res => { if (res?.success) setNotifGroups(res.data?.groups ?? res.data ?? []) }).catch(() => {})
  }, [])

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

  useEffect(() => {
    if (!exportOpen) return
    const onClick = (e) => { if (!exportRef.current?.contains(e.target)) setExportOpen(false) }
    const onKey   = (e) => { if (e.key === 'Escape') setExportOpen(false) }
    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [exportOpen])

  async function doExport(kind) {
    setExporting(true)
    setExportOpen(false)
    try {
      const res = await api.admin.getInventory(false)
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
        ? exportInventoryCsv(all, teams, t)
        : await exportInventoryPdf(all, teams, t)
      toast.success(t('inv.exportSuccess', n))
    } catch {
      toast.error(t('inv.exportError'))
    } finally {
      setExporting(false)
    }
  }

  async function load() {
    try {
      const res = await api.admin.getInventory(true)
      if (res?.success) {
        setItems(res.data)
      } else {
        toast.error(res?.error || t('inv.loadError'))
      }
    } catch {
      toast.error(t('inv.loadError'))
    }
  }

  const stats = useMemo(() => ({
    active:   items.filter(i => i.active   && !i.deleted_at).length,
    inactive: items.filter(i => !i.active  && !i.deleted_at).length,
    deleted:  items.filter(i => !!i.deleted_at).length,
  }), [items])

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
  const groupNames = useMemo(() => [...new Set(items.map(i => i.group_name).filter(Boolean))].sort(), [items])

  function togglePill(kind) {
    setStatusFilter(prev => prev === kind ? 'default' : kind)
  }

  // ── Toplu seçim (yalnız silinmemiş kayıtlar seçilebilir) ──────────────────
  const [selected, setSelected] = useState(() => new Set())
  const [contactsModal, setContactsModal] = useState(null)   // E5: toplu sorumlu ekip atama
  const selectableItems = useMemo(() => visibleItems.filter(i => !i.deleted_at), [visibleItems])

  // Sayfalama yalnız RENDER'ı böler; "tümünü seç" filtrelenmiş tüm liste (selectableItems) üzerinde kalır.
  const filterKey = JSON.stringify(filters)
  const pager = usePagination(visibleItems, {
    listKey: 'inventory', resetDeps: [statusFilter, filterKey, sort],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: durum filtresi + süzgeçler (i_*) + görünüm + sayfa/boyut.
  useUrlQuerySync({
    stat: statusFilter !== 'default' ? statusFilter : null,
    ...filtersToParams(filters),
    i_view: view !== 'table' ? view : null,
    i_sort: sort !== 'domain|asc' ? sort : null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
  })
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
  useEffect(() => {
    if (openAddSignal) { openAdd(); onAddConsumed?.() }
  }, [openAddSignal]) // eslint-disable-line react-hooks/exhaustive-deps

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

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <div className="inv-title-row">
          <h3>{t('inv.title')}</h3>
          <div className="inv-stats-pills">
            <button type="button"
              className={`inv-stat-pill inv-stat-active${statusFilter === 'active' ? ' is-selected' : ''}`}
              onClick={() => togglePill('active')}
              title={t('inv.filterTooltip')}>
              <span className="inv-stat-dot"></span>
              {t('inv.statActive')}: <strong>{stats.active}</strong>
            </button>
            <button type="button"
              className={`inv-stat-pill inv-stat-inactive${statusFilter === 'inactive' ? ' is-selected' : ''}`}
              onClick={() => togglePill('inactive')}
              title={t('inv.filterTooltip')}>
              <span className="inv-stat-dot"></span>
              {t('inv.statInactive')}: <strong>{stats.inactive}</strong>
            </button>
            <button type="button"
              className={`inv-stat-pill inv-stat-deleted${statusFilter === 'deleted' ? ' is-selected' : ''}`}
              onClick={() => togglePill('deleted')}
              title={t('inv.filterTooltip')}>
              <span className="inv-stat-dot"></span>
              {t('inv.statDeleted')}: <strong>{stats.deleted}</strong>
            </button>
          </div>
        </div>
        <div className="inv-header-actions">
          <div className="sqlpg-menu-wrap" ref={exportRef}>
            <Button
              type="button"
              variant="secondary" className={`sqlpg-menu-trigger ${exportOpen ? ' is-open' : ''}`}
              onClick={() => setExportOpen(o => !o)}
              disabled={exporting}
            >
              {exporting
                ? <Spinner size={14} inline decorative />
                : <Download size={14} />}
              {t('inv.export')}
              <ChevronDown size={12} className="sqlpg-menu-chev" />
            </Button>
            {exportOpen && (
              <div className="sqlpg-menu inv-export-menu">
                <button
                  type="button"
                  className="sqlpg-item"
                  onClick={() => doExport('csv')}
                >
                  <div className="sqlpg-item-label">{t('inv.exportCsv')}</div>
                  <div className="sqlpg-item-preview">{t('inv.exportCsvHint')}</div>
                </button>
                <button
                  type="button"
                  className="sqlpg-item"
                  onClick={() => doExport('pdf')}
                >
                  <div className="sqlpg-item-label">{t('inv.exportPdf')}</div>
                  <div className="sqlpg-item-preview">{t('inv.exportPdfHint')}</div>
                </button>
              </div>
            )}
          </div>
          {canManage && <Button variant="secondary" onClick={() => setImportOpen(true)}><Upload size={14} /> {t('inv.import')}</Button>}
          {canAdd && <Button variant="success" onClick={openAdd}><Plus size={14} /> {t('inv.addBtn')}</Button>}
        </div>
      </div>

      {canManage && (
        <InventoryHygieneBand data={hygiene} overlaps={overlaps} active={filters.hygiene}
          onSelect={(code) => { setFilters(f => ({ ...f, hygiene: code })); setStatusFilter('default'); setView('table') }}
          onOpenDomain={(d) => { const r = items.find(i => i.domain === d); if (r) setShowItem(r) }} />
      )}
      <InventoryToolbar filters={filters} onFilters={setFilters} teams={teams} groupNames={groupNames} notifGroups={notifGroups}
        shown={visibleItems.length} total={statusItems.length}
        cols={cols} onCols={setCols} sort={sort} onSort={setSortPersist} density={density} onDensity={setDensity}
        view={view} onView={setView} savedViews={savedViews} onSaveView={saveView} onApplyView={applyView} onDeleteView={deleteView} onCopyLink={copyLink}
        colFilters={colFilters} onColFilters={setColFilters} />

      {canManage && selected.size > 0 && (
        <div className="inv-stats-pills" style={{ marginBottom: 10, gap: 8, alignItems: 'center',
          background: '#f4f4f5', padding: '8px 12px', borderRadius: 6 }}>
          <span style={{ fontWeight: 700, fontSize: '.9em' }}>{t('inv.bulkSelected', selected.size)}</span>
          <Button variant="success" size="sm"   onClick={() => bulkAction('activate')}>{t('inv.bulkActivateBtn')}</Button>
          <Button variant="warning" size="sm"   onClick={() => bulkAction('deactivate')}>{t('inv.bulkDeactivateBtn')}</Button>
          <Button variant="destructive" size="sm"    onClick={() => bulkAction('delete')}>{t('inv.bulkDeleteBtn')}</Button>
          <Button variant="secondary" size="sm" onClick={() => setContactsModal({})}>{t('inv.bulkContactsBtn')}</Button>
          <Button variant="secondary" size="sm" onClick={() => setSelected(new Set())}>{t('inv.bulkClear')}</Button>
        </div>
      )}

      {isAdmin && statusFilter === 'deleted' && stats.deleted > 0 && (
        <div className="inv-stats-pills" style={{ marginBottom: 10, gap: 8, alignItems: 'center',
          background: '#fef2f2', padding: '8px 12px', borderRadius: 6 }}>
          <span style={{ fontWeight: 700, fontSize: '.9em' }}>{t('inv.purgeAllHint', stats.deleted)}</span>
          <Button variant="destructive" size="sm" onClick={purgeAll}>{t('inv.purgeAllBtn')}</Button>
        </div>
      )}

      {view === 'team' ? (
        <InventoryTeamView rows={visibleItems} onShow={(r) => setShowItem(r)}
          onFilterTeam={(teamId) => { setFilters(f => ({ ...f, team: teamId == null ? '' : String(teamId) })); setView('table') }} />
      ) : visibleItems.length === 0 && !(colFilters && statusItems.length > 0) ? (
        /* Kolon süzgeç satırı açıkken tablo ayakta kalır (aşağıda); kapalıyken boş durum + Temizle (2026-09-22 QA). */
        <StatusBlock tone="neutral" icon={Inbox} title={statusItems.length === 0 ? t('inv.emptyTitle') : t('inv.noMatch')}
          description={statusItems.length === 0 ? (canManage ? t('inv.emptyHintAdmin') : t('inv.emptyHint')) : t('empty.hintFilter')}
          actions={statusItems.length > 0 && hasActiveFilter(filters)
            ? <Button type="button" variant="secondary" size="sm" onClick={clearFilters}>{t('inv.filterClear')}</Button>
            : null} />
      ) : (
        <>
          <InventoryTable rows={pager.pageItems} cols={cols} sort={sort} onSort={setSortPersist} density={density}
            canManage={canManage} canEditRow={canEditRow} isAdmin={isAdmin} teamsCount={teams.length} teamMap={teamMap} statusFilter={statusFilter}
            selected={selected} onToggle={toggleSel} onToggleAll={toggleAll} allOnPage={allOnPage}
            onShow={(r) => setShowItem(r)} onEdit={openEdit} onDuplicate={openDuplicate} onTransfer={openTransfer}
            onDelete={del} onRestore={restore} onPurge={purge}
            onDiagnose={(r) => setDiag({ domain: r.domain, port: r.port || 443 })}
            onCheckNow={checkNow} onInline={inlineUpdate}
            onTagClick={(tag) => setFilters(f => ({ ...f, q: tag }))}
            filters={filters} onFilters={setFilters} allRows={statusItems} showFilters={colFilters} platformNames={platformNames}
            onClearFilters={hasActiveFilter(filters) ? clearFilters : null} />
          {pager.pageItems.length > 0 && <PaginationBar {...pager} />}
        </>
      )}

      {/* ── Ana Form Modalı — inventory/InventoryFormModal.jsx'e çıkarıldı (dashboard kartı da aynı
             formu açıyor). key: açıkken mod/kayıt değişirse remount olsun. ── */}
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
          onClose={() => setFormModal(null)}
          onSaved={() => { setFormModal(null); load(); onInventoryChange?.() }}
        />
      )}

      {/* ── Kayıt çekmecesi (#8): Ayrıntılar / Değişiklikler / Kontroller; önceki-sonraki gezinme ── */}
      {showItem && (
        <InventoryDrawer record={showItem} records={visibleItems} teamMap={teamMap} teamNameById={teamNameById} canManage={canManage} canEditRow={canEditRow}
          onClose={() => setShowItem(null)} onEdit={(r) => { setShowItem(null); openEdit(r) }} onCheckNow={checkNow} onNavigate={setShowItem} />
      )}
      {importOpen && (
        <InventoryImportModal onClose={() => setImportOpen(false)} onDone={() => { load(); loadHygiene(); onInventoryChange?.() }} />
      )}
      {/* ── Transfer Modalı ── */}
      {transferModal && (
        <div className="modal-overlay" onClick={() => setTransferModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('inv.transferTitle', transferModal.domain)}</h3>
            <div className="form-grid">
              <label className="full-width">
                {t('inv.newTeam')}
                <SearchableSelect
                  value={transferTeamId}
                  onChange={v => setTransferTeamId(v)}
                  searchThreshold={2}
                  options={teams.map(team => ({ value: team.id, label: team.name }))}
                />
              </label>
            </div>
            <div className="modal-actions">
              <Button variant="secondary" onClick={() => setTransferModal(null)}>{t('inv.cancel')}</Button>
              <Button onClick={doTransfer} disabled={saving}>
                {saving ? t('inv.saving') : t('inv.transferConfirm')}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ── Bağlantı/SSL/Ağ Tanılama Modalı (envanter + durum izleme ortak) ── */}
      {diag && (
        <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />
      )}
    </div>
  )
}
