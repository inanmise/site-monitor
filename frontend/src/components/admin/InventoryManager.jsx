import { useState, useEffect, useRef, useMemo, lazy, Suspense } from 'react'
import { ChevronDown, Download, Users } from 'lucide-react'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { api } from '../../api/client'
import { InventoryDetails } from '../inventory/InventoryDetails.jsx'
import InventoryFormModal from '../inventory/InventoryFormModal.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import DiagnosticsModal from './DiagnosticsModal.jsx'
import { exportInventoryCsv, exportInventoryPdf } from '../../utils/exportInventory'
import { Spinner, LoadingBlock } from '../ui/Progress.jsx'

const ChangeHistoryTab = lazy(() => import('../history/ChangeHistoryTab.jsx'))

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
          <button className="btn btn-secondary" onClick={onClose}>{t('inv.cancel')}</button>
          <button className="btn btn-primary" onClick={() => onApply(values)}>
            {t('inv.bulkContactsBtn')}
          </button>
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

export default function InventoryManager({ onInventoryChange, systemRole, teams: teamsProp = [], isAdmin: isAdminProp = false, openAddSignal = false, onAddConsumed }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole ? systemRole === 'ADMIN' : isAdminProp
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  const [items, setItems]             = useState([])
  const [teams, setTeams]             = useState(teamsProp)
  const [formModal, setFormModal]     = useState(null)   // { mode: 'add'|'edit'|'duplicate', record }
  const [transferModal, setTransferModal] = useState(null)
  const [transferTeamId, setTransferTeamId]     = useState('')
  const [saving, setSaving]           = useState(false)
  const [statusFilter, setStatusFilter] = useState(() => readUrlParam('stat', 'default'))
  const [showItem,    setShowItem]    = useState(null)
  const [showTab,     setShowTab]     = useState('details')
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
  }, [])

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

  const visibleItems = useMemo(() => {
    switch (statusFilter) {
      case 'active':   return items.filter(i => i.active   && !i.deleted_at)
      case 'inactive': return items.filter(i => !i.active  && !i.deleted_at)
      case 'deleted':  return items.filter(i => !!i.deleted_at)
      default:         return items.filter(i => !i.deleted_at)
    }
  }, [items, statusFilter])

  function togglePill(kind) {
    setStatusFilter(prev => prev === kind ? 'default' : kind)
  }

  // ── Toplu seçim (yalnız silinmemiş kayıtlar seçilebilir) ──────────────────
  const [selected, setSelected] = useState(() => new Set())
  const [contactsModal, setContactsModal] = useState(null)   // E5: toplu sorumlu ekip atama
  const selectableItems = useMemo(() => visibleItems.filter(i => !i.deleted_at), [visibleItems])

  // Sayfalama yalnız RENDER'ı böler; "tümünü seç" filtrelenmiş tüm liste (selectableItems) üzerinde kalır.
  const pager = usePagination(visibleItems, {
    listKey: 'inventory', resetDeps: [statusFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: durum filtresi + sayfa/boyut.
  useUrlQuerySync({
    stat: statusFilter !== 'default' ? statusFilter : null,
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
  useEffect(() => { setSelected(new Set()) }, [statusFilter])

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
    let res
    try {
      res = await api.admin.transferCertSy(transferModal.id, Number(transferTeamId))
    } catch (e) {
      setSaving(false)
      toast.error(e?.message || t('inv.transferError'))
      return
    }
    setSaving(false)
    if (res?.success) {
      setTransferModal(null)
      toast.success(t('inv.transferred'))
      load()
    } else {
      toast.error(res?.error || t('inv.transferError'))
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
            <button
              type="button"
              className={`btn btn-secondary sqlpg-menu-trigger${exportOpen ? ' is-open' : ''}`}
              onClick={() => setExportOpen(o => !o)}
              disabled={exporting}
            >
              {exporting
                ? <Spinner size={14} inline decorative />
                : <Download size={14} />}
              {t('inv.export')}
              <ChevronDown size={12} className="sqlpg-menu-chev" />
            </button>
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
          {canManage && <button className="btn btn-success" onClick={openAdd}>{t('inv.addBtn')}</button>}
        </div>
      </div>

      {canManage && selected.size > 0 && (
        <div className="inv-stats-pills" style={{ marginBottom: 10, gap: 8, alignItems: 'center',
          background: '#f1f5f9', padding: '8px 12px', borderRadius: 6 }}>
          <span style={{ fontWeight: 700, fontSize: '.9em' }}>{t('inv.bulkSelected', selected.size)}</span>
          <button className="btn btn-success btn-sm-p"   onClick={() => bulkAction('activate')}>{t('inv.bulkActivateBtn')}</button>
          <button className="btn btn-warning btn-sm-p"   onClick={() => bulkAction('deactivate')}>{t('inv.bulkDeactivateBtn')}</button>
          <button className="btn btn-danger btn-sm-p"    onClick={() => bulkAction('delete')}>{t('inv.bulkDeleteBtn')}</button>
          <button className="btn btn-secondary btn-sm-p" onClick={() => setContactsModal({})}>{t('inv.bulkContactsBtn')}</button>
          <button className="btn btn-secondary btn-sm-p" onClick={() => setSelected(new Set())}>{t('inv.bulkClear')}</button>
        </div>
      )}

      {isAdmin && statusFilter === 'deleted' && stats.deleted > 0 && (
        <div className="inv-stats-pills" style={{ marginBottom: 10, gap: 8, alignItems: 'center',
          background: '#fef2f2', padding: '8px 12px', borderRadius: 6 }}>
          <span style={{ fontWeight: 700, fontSize: '.9em' }}>{t('inv.purgeAllHint', stats.deleted)}</span>
          <button className="btn btn-danger btn-sm-p" onClick={purgeAll}>{t('inv.purgeAllBtn')}</button>
        </div>
      )}

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              {canManage && (
                <th style={{ width: 28 }}>
                  <input type="checkbox" checked={allOnPage} onChange={toggleAll}
                    disabled={selectableItems.length === 0} title={t('inv.bulkSelectAll')} />
                </th>
              )}
              <th>{t('inv.colDomain')}</th>
              <th>{t('inv.colPort')}</th>
              <th>{t('inv.colTier')}</th>
              <th>{t('inv.colTeam')}</th>
              <th>{t('inv.colActive')}</th>
              <th>{t('inv.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {pager.pageItems.map((item) => (
              <tr key={item.id} className={item.deleted_at ? 'inv-row-deleted' : ''}>
                {canManage && (
                  <td onClick={e => e.stopPropagation()}>
                    {!item.deleted_at && (
                      <input type="checkbox" checked={selected.has(item.id)}
                        onChange={() => toggleSel(item.id)} />
                    )}
                  </td>
                )}
                <td><strong>{item.domain}</strong></td>
                <td>{item.port}</td>
                <td>
                  {item.tier
                    ? <span className={`tier-badge tier-badge-${item.tier}`}>T{item.tier}</span>
                    : <span style={{ color: 'var(--text-light)', fontSize: '.8em' }}>—</span>}
                </td>
                <td>{item.team_name || teamMap[String(item.team_id)] || '—'}</td>
                <td>
                  {item.deleted_at
                    ? <span className="badge badge-deleted">{t('inv.deletedBadge')}</span>
                    : <span className={item.active ? 'badge badge-ok' : 'badge badge-err'}>
                        {item.active ? t('inv.active') : t('inv.inactive')}
                      </span>
                  }
                </td>
                <td>
                  <KebabMenu label={t('inv.colActions')} items={
                    item.deleted_at
                      ? [
                          { label: t('inv.show'), onClick: () => { setShowTab('details'); setShowItem(item) } },
                          { label: t('inv.restore'), onClick: () => restore(item.id), hidden: !canManage },
                          { label: t('inv.purge'), danger: true, onClick: () => purge(item.id), hidden: !isAdmin },
                        ]
                      : [
                          { label: t('inv.show'), onClick: () => { setShowTab('details'); setShowItem(item) } },
                          { label: t('inv.diagnose'), onClick: () => setDiag({ domain: item.domain, port: item.port || 443 }), hidden: !isAdmin },
                          { label: t('inv.edit'), onClick: () => openEdit(item), hidden: !canManage },
                          { label: t('mon.duplicate'), onClick: () => openDuplicate(item), hidden: !canManage },
                          { label: t('inv.transfer'), onClick: () => openTransfer(item), hidden: !(isAdmin && teams.length > 1) },
                          { label: t('inv.delete'), danger: true, onClick: () => del(item.id), hidden: !canManage },
                        ]
                  } />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <PaginationBar {...pager} />
      </div>

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
          onClose={() => setFormModal(null)}
          onSaved={() => { setFormModal(null); load(); onInventoryChange?.() }}
        />
      )}

      {/* ── Show (Read-Only Detail) Modalı ── */}
      {showItem && (
        <div className="modal-overlay" onClick={() => setShowItem(null)}>
          <div className="modal-box modal-show" onClick={e => e.stopPropagation()}>

            {/* Header */}
            <div className="show-header">
              <div className="show-header-title">
                <span className="show-domain">{showItem.domain}</span>
                <span className="show-badge show-badge-port">:{showItem.port || 443}</span>
                {showItem.tier && (
                  <span className={`tier-badge tier-badge-${showItem.tier}`}>T{showItem.tier}</span>
                )}
                <span className={`show-badge ${
                  showItem.deleted_at ? 'show-badge-deleted'
                  : showItem.active   ? 'show-badge-active'
                  :                     'show-badge-inactive'
                }`}>
                  {showItem.deleted_at
                    ? t('inv.deletedBadge')
                    : showItem.active ? t('inv.active') : t('inv.inactive')}
                </span>
              </div>
              <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setShowItem(null)}>✕</button>
            </div>

            {/* İki sekme: kaydın kendisi ve kaydın GEÇMİŞİ (kim, ne zaman, neyi değiştirdi).
                Envanter satırları sertifika sahipliğinin kaynağı — "bu alanı kim T1 yaptı"
                sorusu burada, kaydın yanında cevaplanmalı. */}
            <div className="modal-tabs">
              <button className={`modal-tab${showTab === 'details' ? ' active' : ''}`}
                onClick={() => setShowTab('details')}>{t('modal.detailsTab')}</button>
              <button className={`modal-tab${showTab === 'changes' ? ' active' : ''}`}
                onClick={() => setShowTab('changes')}>{t('chg.tab')}</button>
            </div>

            {showTab === 'details' && <InventoryDetails record={showItem} teamMap={teamMap} />}
            {showTab === 'changes' && (
              <div className="show-body">
                <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
                  <ChangeHistoryTab t={t} kind="inventory" monitorId={showItem.id}
                    teamNames={teamNameById} />
                </Suspense>
              </div>
            )}

          </div>
        </div>
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
              <button className="btn btn-secondary" onClick={() => setTransferModal(null)}>{t('inv.cancel')}</button>
              <button className="btn btn-primary" onClick={doTransfer} disabled={saving}>
                {saving ? t('inv.saving') : t('inv.transferConfirm')}
              </button>
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
