import { useState, useEffect, useRef, useMemo } from 'react'
import { ChevronDown, Check, Download, Loader2 } from 'lucide-react'
import MDEditor from '@uiw/react-md-editor'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, formatDate } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import DiagnosticsModal from './DiagnosticsModal.jsx'
import { exportInventoryCsv, exportInventoryPdf } from '../../utils/exportInventory'

const EMPTY = {
  domain: '', port: 443, owner: '', description: '', active: true,
  team_id: '', tier: null,
  external_vendor: false, action_required: false, openshift: false,
  ssl_pinning: false, internal_cert: false, jks_keystore: false,
  server_update: false, netscaler: false, waf_enabled: false,
  in_use: false, ev_certificate: false, transferred_to_sy: false, use_proxy: false,
  tls_mode: '',
  purchased_by: '',
  change_description: '',
  expected_fingerprint: '', expected_subject: '',
}

function YesNo({ value, onChange }) {
  const isYes = value === true
  return (
    <div className="yn-group">
      <button type="button" className={`yn-btn${isYes ? ' yn-active' : ''}`}
        onClick={() => onChange(true)}>Evet</button>
      <button type="button" className={`yn-btn${!isYes ? ' yn-active' : ''}`}
        onClick={() => onChange(false)}>Hayır</button>
    </div>
  )
}

function SectionHeader({ label }) {
  return <div className="form-section-header">{label}</div>
}

function ShowField({ label, value, mono, full }) {
  return (
    <div className={`show-field${full ? ' show-field-full' : ''}`}>
      <span className="show-field-label">{label}</span>
      <span className={`show-field-value${mono ? ' show-field-mono' : ''}`}>{value ?? '—'}</span>
    </div>
  )
}

export default function InventoryManager({ onInventoryChange, systemRole, teams: teamsProp = [], isAdmin: isAdminProp = false, openAddSignal = false, onAddConsumed }) {
  const t = useT()
  const { theme } = useTheme()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole ? systemRole === 'ADMIN' : isAdminProp
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  const [items, setItems]             = useState([])
  const [teams, setTeams]             = useState(teamsProp)
  const [modal, setModal]             = useState(null)
  const [transferModal, setTransferModal] = useState(null)
  const formGridRef                   = useRef(null)
  const [showScrollHint, setShowScrollHint] = useState(false)
  const [transferTeamId, setTransferTeamId]     = useState('')
  const [form, setForm]               = useState(EMPTY)
  const [saving, setSaving]           = useState(false)
  const [msg, setMsg]                 = useState(null)
  const [statusFilter, setStatusFilter] = useState('default')
  const [showItem,    setShowItem]    = useState(null)
  const [diag,        setDiag]        = useState(null)   // { domain, port } → DiagnosticsModal
  const [exportOpen,  setExportOpen]  = useState(false)
  const [exporting,   setExporting]   = useState(false)
  const exportRef = useRef(null)

  const teamMap  = Object.fromEntries(teams.map(t => [String(t.id), t.name]))

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
    } catch (e) {
      toast.error(t('inv.exportError'))
    } finally {
      setExporting(false)
    }
  }

  useEffect(() => {
    if (modal === null) { setShowScrollHint(false); return }
    const el = formGridRef.current
    if (!el) return
    const check = () => {
      const hasOverflow = el.scrollHeight > el.clientHeight + 2
      const atBottom    = el.scrollTop + el.clientHeight >= el.scrollHeight - 6
      setShowScrollHint(hasOverflow && !atBottom)
    }
    check()
    el.addEventListener('scroll', check, { passive: true })
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => { el.removeEventListener('scroll', check); ro.disconnect() }
  }, [modal])

  async function load() {
    try {
      const res = await api.admin.getInventory(true)
      if (res?.success) {
        setItems(res.data)
      } else {
        toast.error(res?.error || t('inv.loadError'))
      }
    } catch (e) {
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
  const selectableItems = useMemo(() => visibleItems.filter(i => !i.deleted_at), [visibleItems])
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

  function f(field, val) { setForm(prev => ({ ...prev, [field]: val })) }

  function openAdd() {
    setForm(EMPTY)
    setModal('add')
  }

  // Dashboard'daki "domain ekle" butonundan tetiklenince add modalını aç (bir kez; App tüketince sıfırlar).
  useEffect(() => {
    if (openAddSignal) { openAdd(); onAddConsumed?.() }
  }, [openAddSignal]) // eslint-disable-line react-hooks/exhaustive-deps

  function openEdit(item) {
    setForm({
      ...EMPTY,
      ...item,
      team_id:            String(item.team_id ?? ''),
      external_vendor:    item.external_vendor  ?? false,
      action_required:    item.action_required  ?? false,
      openshift:          item.openshift        ?? false,
      ssl_pinning:        item.ssl_pinning      ?? false,
      internal_cert:      item.internal_cert    ?? false,
      jks_keystore:       item.jks_keystore     ?? false,
      server_update:      item.server_update    ?? false,
      netscaler:          item.netscaler        ?? false,
      waf_enabled:        item.waf_enabled      ?? false,
      in_use:             item.in_use           ?? false,
      ev_certificate:     item.ev_certificate   ?? false,
      transferred_to_sy:  item.transferred_to_sy ?? false,
      use_proxy:          item.use_proxy        ?? false,
      tls_mode:           item.tls_mode         ?? '',
      purchased_by:       item.purchased_by     ?? '',
      change_description: item.change_description ?? '',
      expected_fingerprint: item.expected_fingerprint ?? '',
      expected_subject:   item.expected_subject ?? '',
      tier:               item.tier ?? null,
    })
    setModal(item)
  }

  function openTransfer(item) {
    setTransferModal(item)
    setTransferTeamId(String(item.team_id ?? ''))
  }

  function validate() {
    if (!form.domain.trim()) return t('inv.formDomain') + ' zorunlu'
    if (!form.team_id) return t('inv.teamRequired')
    return null
  }

  async function save() {
    const err = validate()
    if (err) {
      setMsg(err)
      // Inline hata mesajı modal'ın üstünde — kullanıcı uzun form'da kaçırmasın
      formGridRef.current?.scrollTo?.({ top: 0, behavior: 'smooth' })
      return
    }

    // Domain rename uyarısı — geçmiş veri taşıma onay isteği
    if (modal !== 'add' && modal?.domain && form.domain.trim() !== modal.domain) {
      const confirmed = await showConfirm({
        title: t('inv.renameTitle'),
        message: t('inv.renameMessage', modal.domain, form.domain.trim()),
        confirmText: t('inv.renameConfirm'),
        cancelText: t('inv.cancel'),
      })
      if (!confirmed) return
    }

    setSaving(true)
    setMsg(null)
    const payload = {
      domain:             form.domain.trim(),
      port:               parseInt(form.port) || 443,
      owner:              form.owner,
      description:        form.description,
      active:             form.active,
      team_id:            form.team_id ? Number(form.team_id) : null,
      ug_team_id:         null,   // tek takım modeli — UG ayrımı kaldırıldı
      external_vendor:    form.external_vendor,
      action_required:    form.action_required,
      openshift:          form.openshift,
      ssl_pinning:        form.ssl_pinning,
      internal_cert:      form.internal_cert,
      jks_keystore:       form.jks_keystore,
      server_update:      form.server_update,
      netscaler:          form.netscaler,
      waf_enabled:        form.waf_enabled,
      in_use:             form.in_use,
      ev_certificate:     form.ev_certificate,
      transferred_to_sy:  form.transferred_to_sy,
      use_proxy:          form.use_proxy,
      tls_mode:           form.tls_mode || null,
      purchased_by:       form.purchased_by || null,
      change_description: form.change_description || null,
      expectedFingerprint: form.expected_fingerprint || null,
      expectedSubject:    form.expected_subject || null,
      tier:               form.tier ? Number(form.tier) : null,
    }
    const res = modal === 'add'
      ? await api.admin.addInventory(payload)
      : await api.admin.updateInventory(modal.id, payload)
    setSaving(false)
    if (res?.success) {
      setModal(null)
      toast.success(t('inv.saved'))
      if ((res.alertsClosed ?? 0) > 0) {
        toast.success(t('inv.deactivatedAlerts', res.alertsClosed))
      }
      load()
      onInventoryChange?.()
    } else {
      // Sunucu hatası → tek bildirim (toast). Inline setMsg yalnız form validation için.
      toast.error(res?.error || t('inv.saveError'))
    }
  }

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
                ? <Loader2 size={14} className="spin" />
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
            {visibleItems.map((item) => (
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
                          { label: t('inv.show'), onClick: () => setShowItem(item) },
                          { label: t('inv.restore'), onClick: () => restore(item.id), hidden: !canManage },
                          { label: t('inv.purge'), danger: true, onClick: () => purge(item.id), hidden: !isAdmin },
                        ]
                      : [
                          { label: t('inv.show'), onClick: () => setShowItem(item) },
                          { label: t('inv.diagnose'), onClick: () => setDiag({ domain: item.domain, port: item.port || 443 }), hidden: !isAdmin },
                          { label: t('inv.edit'), onClick: () => openEdit(item), hidden: !canManage },
                          { label: t('inv.transfer'), onClick: () => openTransfer(item), hidden: !(isAdmin && teams.length > 1) },
                          { label: t('inv.delete'), danger: true, onClick: () => del(item.id), hidden: !canManage },
                        ]
                  } />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ── Ana Form Modalı ── */}
      {modal !== null && (
        // Dış (overlay) tıklamada KAPANMAZ — girilen veri kaybolmasın; yalnız İptal/Kaydet kapatır.
        <div className="modal-overlay">
          <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
            <h3>{modal === 'add' ? t('inv.addTitle') : t('inv.editTitle')}</h3>

            <div className="form-grid" ref={formGridRef}>

              {/* ── Temel Bilgiler ── */}
              <SectionHeader label={t('inv.sectionBasic')} />

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.active} onChange={e => f('active', e.target.checked)} />
                {t('inv.formActive')}
              </label>

              <label>
                <span>{t('inv.formDomain')} <span className="req-star">*</span></span>
                <input value={form.domain} onChange={e => f('domain', e.target.value)} placeholder={t('inv.formDomainPh')} />
              </label>
              <label>
                {t('inv.formPort')}
                <input type="number" value={form.port} onChange={e => f('port', e.target.value)} />
              </label>

              <label>
                <span>{t('inv.formTeam')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.team_id}
                  onChange={v => f('team_id', v)}
                  placeholder={t('inv.selectTeam')}
                  disabled={!canManage}
                  searchThreshold={2}
                  options={[
                    { value: '', label: t('inv.selectTeam') },
                    ...teams.map(team => ({ value: team.id, label: team.name })),
                  ]}
                />
              </label>

              <label>
                {t('inv.formTier')}
                <SearchableSelect
                  value={form.tier ?? ''}
                  onChange={v => f('tier', v ? Number(v) : null)}
                  options={[
                    { value: '', label: t('inv.tierNone') },
                    { value: '1', label: t('inv.tier1') },
                    { value: '2', label: t('inv.tier2') },
                    { value: '3', label: t('inv.tier3') },
                    { value: '4', label: t('inv.tier4') },
                  ]}
                />
              </label>

              <label>
                {t('inv.formTlsMode')}
                <SearchableSelect
                  value={form.tls_mode}
                  onChange={v => f('tls_mode', v)}
                  options={[
                    { value: '',        label: t('inv.tlsModeInherit') },
                    { value: 'browser', label: t('inv.tlsModeBrowser') },
                    { value: 'default', label: t('inv.tlsModeDefault') },
                  ]}
                />
              </label>

              <label>
                {t('inv.formPurchasedBy')}
                <input value={form.purchased_by} onChange={e => f('purchased_by', e.target.value)} />
              </label>

              {/* ── Operasyonel Bilgiler ── */}
              <SectionHeader label={t('inv.sectionOps')} />

              <div className="yn-grid">
                {[
                  ['external_vendor',  'inv.formExternalVendor'],
                  ['action_required',  'inv.formActionRequired'],
                  ['openshift',        'inv.formOpenshift'],
                  ['ssl_pinning',      'inv.formSslPinning'],
                  ['internal_cert',    'inv.formInternal'],
                  ['jks_keystore',     'inv.formJksKeystore'],
                  ['server_update',    'inv.formServerUpdate'],
                  ['netscaler',        'inv.formNetscaler'],
                  ['waf_enabled',      'inv.formWafEnabled'],
                  ['in_use',           'inv.formInUse'],
                  ['ev_certificate',   'inv.formEvCert'],
                  ['transferred_to_sy','inv.formTransferredToSy'],
                  ['use_proxy',        'inv.formUseProxy'],
                ].map(([field, key]) => (
                  <div key={field} className="yn-field-row">
                    <span className="yn-field-label">{t(key)}</span>
                    <YesNo value={form[field]} onChange={v => f(field, v)} />
                  </div>
                ))}
              </div>

              <label className="full-width">
                {t('inv.formChangeDesc')}
                <div data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
                  <MDEditor
                    value={form.change_description}
                    onChange={(v) => f('change_description', v ?? '')}
                    preview="edit"
                    height={260}
                    visibleDragbar={false}
                  />
                </div>
              </label>


            </div>

            {msg && <div className="alert-msg" style={{ marginTop: 10 }}>{msg}</div>}

            {showScrollHint && (
              <button
                type="button"
                className="modal-scroll-hint"
                onClick={() => formGridRef.current?.scrollBy({ top: 200, behavior: 'smooth' })}
              >
                <ChevronDown size={14} />
                <span>{t('inv.scrollForMore')}</span>
              </button>
            )}

            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('inv.cancel')}</button>
              <button className="btn btn-primary" onClick={save}
                disabled={saving || !form.domain.trim() || !form.team_id}>
                {saving ? t('inv.saving') : t('inv.save')}
              </button>
            </div>
          </div>
        </div>
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

            <div className="show-body">

              {/* Temel Bilgiler */}
              <div className="show-section-header">{t('inv.sectionBasic')}</div>
              <div className="show-grid-2">
                <ShowField label={t('inv.formDomain')}  value={showItem.domain} mono />
                <ShowField label={t('inv.formPort')}    value={showItem.port || 443} />
                <ShowField label={t('inv.formTeam')}    value={teamMap[String(showItem.team_id)]    || '—'} />
                <ShowField label={t('inv.formTier')}    value={
                  showItem.tier
                    ? `T${showItem.tier} — ${t(`inv.tier${showItem.tier}`)}`
                    : t('inv.tierNone')
                } />
                <ShowField label={t('inv.formPurchasedBy')} value={showItem.purchased_by || '—'} />
                <ShowField label={t('inv.formTlsMode')} value={
                  showItem.tls_mode === 'browser' ? t('inv.tlsModeBrowser')
                  : showItem.tls_mode === 'default' ? t('inv.tlsModeDefault')
                  : t('inv.tlsModeInherit')
                } />
              </div>

              {/* Operasyonel Bilgiler */}
              <div className="show-section-header">{t('inv.sectionOps')}</div>
              <div className="show-yn-grid">
                {[
                  ['inv.formExternalVendor', showItem.external_vendor],
                  ['inv.formActionRequired', showItem.action_required],
                  ['inv.formOpenshift',      showItem.openshift],
                  ['inv.formSslPinning',     showItem.ssl_pinning],
                  ['inv.formInternal',       showItem.internal_cert],
                  ['inv.formJksKeystore',    showItem.jks_keystore],
                  ['inv.formServerUpdate',   showItem.server_update],
                  ['inv.formNetscaler',      showItem.netscaler],
                  ['inv.formWafEnabled',     showItem.waf_enabled],
                  ['inv.formInUse',          showItem.in_use],
                  ['inv.formEvCert',         showItem.ev_certificate],
                  ['inv.formTransferredToSy',showItem.transferred_to_sy],
                  ['inv.formUseProxy',       showItem.use_proxy],
                ].map(([key, val]) => (
                  <div key={key} className={`show-yn-cell${val ? ' is-yes' : ''}`}>
                    <span className="show-yn-label">{t(key)}</span>
                    <span className={`show-yn-badge ${val ? 'show-yn-yes' : 'show-yn-no'}`}>
                      {val && <Check size={13} strokeWidth={3} />}
                      {val ? t('inv.yes') : t('inv.no')}
                    </span>
                  </div>
                ))}
              </div>


              {showItem.change_description && (
                <div className="show-field show-field-full">
                  <span className="show-field-label">{t('inv.formChangeDesc')}</span>
                  <div className="show-markdown" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{showItem.change_description}</ReactMarkdown>
                  </div>
                </div>
              )}

              {/* Gelişmiş */}
              {(showItem.expected_fingerprint || showItem.expected_subject) && (
                <>
                  <div className="show-section-header">{t('inv.sectionAdv')}</div>
                  <div className="show-grid-1">
                    {showItem.expected_fingerprint && (
                      <ShowField label={t('inv.formFP')} value={showItem.expected_fingerprint} mono full />
                    )}
                    {showItem.expected_subject && (
                      <ShowField label={t('inv.formSubject')} value={showItem.expected_subject} mono full />
                    )}
                  </div>
                </>
              )}

            </div>

            {/* Footer — metadata */}
            <div className="show-footer">
              {showItem.created_at && (
                <span>{t('inv.metaCreated')}: {formatDate(showItem.created_at)}</span>
              )}
              {showItem.updated_at && (
                <span>{t('inv.metaUpdated')}: {formatDate(showItem.updated_at)}</span>
              )}
            </div>

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
