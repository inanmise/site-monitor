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
import { exportInventoryCsv, exportInventoryPdf } from '../../utils/exportInventory'

const EMPTY = {
  domain: '', port: 443, owner: '', description: '', active: true,
  team_id: '', ug_team_id: '', tier: null,
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

export default function InventoryManager({ onInventoryChange, systemRole, teams: teamsProp = [], isAdmin: isAdminProp = false }) {
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
  const [transferUgTeamId, setTransferUgTeamId] = useState('')
  const [form, setForm]               = useState(EMPTY)
  const [saving, setSaving]           = useState(false)
  const [msg, setMsg]                 = useState(null)
  const [statusFilter, setStatusFilter] = useState('default')
  const [showItem,    setShowItem]    = useState(null)
  const [diag,        setDiag]        = useState(null)   // { item, loading?, data?, error?, ossl? }
  const [history,     setHistory]     = useState(null)   // { item, loading?, items?, detail? }
  const [exportOpen,  setExportOpen]  = useState(false)
  const [exporting,   setExporting]   = useState(false)
  const exportRef = useRef(null)

  const teamMap  = Object.fromEntries(teams.map(t => [String(t.id), t.name]))
  const syTeams  = teams.filter(t => !t.team_type || t.team_type === 'SY')
  const ugTeams  = teams.filter(t => !t.team_type || t.team_type === 'UG')

  useEffect(() => {
    load()
    if (isAdmin) api.admin.getTeams().then(res => { if (res?.success) setTeams(res.data) })
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

  function f(field, val) { setForm(prev => ({ ...prev, [field]: val })) }

  function openAdd() {
    setForm(EMPTY)
    setModal('add')
  }

  function openEdit(item) {
    setForm({
      ...EMPTY,
      ...item,
      team_id:            String(item.team_id ?? ''),
      ug_team_id:         String(item.ug_team_id ?? ''),
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
    setTransferUgTeamId(String(item.ug_team_id ?? ''))
  }

  function validate() {
    if (!form.domain.trim()) return t('inv.formDomain') + ' zorunlu'
    if (isAdmin && !form.team_id) return t('inv.teamRequired')
    if (!form.ug_team_id) return t('inv.ugTeamRequired')
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
      ug_team_id:         form.ug_team_id ? Number(form.ug_team_id) : null,
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
    if (res?.success && (res.alertsClosed ?? 0) > 0) {
      toast.success(t('inv.deleteWithAlerts', domain, res.alertsClosed))
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

  async function doTransfer() {
    const syChanged = transferTeamId   !== String(transferModal.team_id   ?? '')
    const ugChanged = transferUgTeamId !== String(transferModal.ug_team_id ?? '')
    if (!syChanged && !ugChanged) { setTransferModal(null); return }
    setSaving(true)
    const settled = await Promise.allSettled([
      syChanged ? api.admin.transferCertSy(transferModal.id, Number(transferTeamId))   : Promise.resolve({ success: true }),
      ugChanged ? api.admin.transferCertUg(transferModal.id, Number(transferUgTeamId)) : Promise.resolve({ success: true }),
    ])
    setSaving(false)
    const rejected = settled.find(r => r.status === 'rejected')
    const failed   = settled.find(r => r.status === 'fulfilled' && !r.value?.success)
    if (rejected || failed) {
      const errTxt = rejected
        ? (rejected.reason?.message || t('inv.transferError'))
        : (failed.value?.error || t('inv.transferError'))
      toast.error(errTxt)
    } else {
      setTransferModal(null)
      toast.success(t('inv.transferred'))
      load()
    }
  }

  const ugTeamName = (id) => teamMap[String(id)] || '—'

  async function runDiag(item) {
    setDiag({ item, loading: true })
    try {
      const res = await api.admin.runDiagnostics(item.domain, item.port || 443)
      setDiag(res?.success
        ? { item, data: res.data }
        : { item, error: res?.error || t('inv.diagError') })
    } catch {
      setDiag({ item, error: t('inv.diagError') })
    }
  }

  const DIAG_STEP_KEYS = {
    'dns':           'inv.diagStepDns',
    'tcp-connect':   'inv.diagStepTcp',
    'proxy-connect': 'inv.diagStepProxyConnect',
    'tls-handshake': 'inv.diagStepTls',
    'cert-ok':       'inv.diagStepCertOk',
  }
  const diagStepLabel = (step) => DIAG_STEP_KEYS[step] ? t(DIAG_STEP_KEYS[step]) : (step || '—')

  /** Derin SSL/TLS taraması (openssl) — mevcut tanılama modalında gösterilir. */
  async function runOpenssl(item) {
    setDiag((d) => ({ ...d, ossl: { loading: true } }))
    try {
      const res = await api.admin.runOpensslDiagnostics(item.domain, item.port || 443)
      setDiag((d) => ({ ...d, ossl: res?.success ? { data: res.data } : { error: res?.error || t('inv.diagError') } }))
    } catch {
      setDiag((d) => ({ ...d, ossl: { error: t('inv.diagError') } }))
    }
  }

  async function runNetwork(item) {
    setDiag((d) => ({ ...d, net: { loading: true } }))
    try {
      const res = await api.admin.runNetworkDiagnostics(item.domain, item.port || 443)
      setDiag((d) => ({ ...d, net: res?.success ? { data: res.data } : { error: res?.error || t('inv.diagError') } }))
    } catch {
      setDiag((d) => ({ ...d, net: { error: t('inv.diagError') } }))
    }
  }

  /** Ağ derin analizi sonucu — kontrol kartları + ham çıktı (canlı + geçmiş). */
  function renderNetwork(data) {
    if (!data) return null
    const statusBadge = (s) => {
      if (s === 'ok') return <span className="badge badge-ok">{t('inv.diagOk')}</span>
      if (s === 'fail') return <span className="badge badge-err">{t('inv.diagError')}</span>
      if (s === 'na') return <span className="badge">{t('inv.netNa')}</span>
      return <span className="badge" style={{ background: '#fef3c7', color: '#92400e' }}>warn</span>
    }
    return (
      <>
        {(data.checks ?? []).map((c) => (
          <details key={c.key} style={{ marginBottom: 6, border: '1px solid var(--border)', borderRadius: 6 }}>
            <summary style={{ cursor: 'pointer', padding: '7px 10px', display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <strong>{c.label}</strong>
              {statusBadge(c.status)}
              <span style={{ fontSize: '.85em', color: 'var(--text-muted)' }}>{c.summary}</span>
            </summary>
            <pre className="show-pre" style={{ margin: '0 8px 8px', maxHeight: 260 }}>{c.output}</pre>
          </details>
        ))}
      </>
    )
  }

  // ── Tanılama geçmişi ──
  async function openHistory(item) {
    setHistory({ item, loading: true })
    const res = await api.admin.diagHistory(item.domain)
    setHistory(res?.success ? { item, items: res.data ?? [] } : { item, error: res?.error || t('inv.diagError') })
  }

  const histTypeLabel = (rt) => rt === 'OPENSSL' ? t('inv.histTypeOpenssl')
    : rt === 'NETWORK' ? t('inv.histTypeNetwork') : t('inv.histTypeConnection')

  async function openHistoryDetail(id) {
    const res = await api.admin.diagHistoryDetail(id)
    if (res?.success) {
      let parsed = null
      try { parsed = JSON.parse(res.data.result_json) } catch { /* */ }
      setHistory((h) => ({ ...h, detail: { ...res.data, result: parsed } }))
    } else {
      toast.error(res?.error || t('inv.diagError'))
    }
  }

  /** openssl protokol risk rozeti. */
  const osslRiskBadge = (p) => {
    if (!p.supported) return <span className="badge">{t('inv.osslDisabled')}</span>
    return p.risk === 'HIGH'
      ? <span className="badge badge-err">{t('inv.osslRiskHigh')}</span>
      : <span className="badge badge-ok">{t('inv.osslRiskOk')}</span>
  }

  /** openssl sonuç gövdesi — hem canlı tarama hem geçmiş detayında kullanılır. */
  function renderOssl(data) {
    if (!data) return null
    if (data.available === false) {
      return <div className="alert-msg">{t('inv.osslUnavailable')}</div>
    }
    const cert = data.certificate || {}
    return (
      <>
        {data.reachable === false && (
          <div className="alert-msg" style={{ background: '#fde8e8', color: '#9b1c1c' }}>
            {t('inv.osslUnreachable')}
          </div>
        )}
        <ShowField label={t('inv.osslVersion')} mono value={data.version || '—'} />
        <div className="show-section-header">{t('inv.osslProtocols')}</div>
        <div className="health-table-wrap">
          <table className="health-table">
            <thead>
              <tr>
                <th>{t('inv.osslColProto')}</th>
                <th>{t('inv.osslColState')}</th>
                <th>{t('inv.osslColRisk')}</th>
              </tr>
            </thead>
            <tbody>
              {(data.protocols ?? []).map((p) => (
                <tr key={p.proto}>
                  <td><strong>{p.proto}</strong></td>
                  <td>{p.supported ? t('inv.osslEnabled') : t('inv.osslDisabled')}</td>
                  <td>{osslRiskBadge(p)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="show-section-header">{t('inv.osslNegotiated')}</div>
        <div className="show-grid-2">
          <ShowField label="Protocol" mono value={data.negotiated?.protocol || '—'} />
          <ShowField label="Cipher" mono value={data.negotiated?.cipher || '—'} />
        </div>
        <div className="show-section-header">{t('inv.osslCert')}</div>
        <div className="show-grid-2">
          <ShowField label="Subject" mono value={cert.subject || '—'} />
          <ShowField label="Issuer" mono value={cert.issuer || '—'} />
          <ShowField label="Not After" mono value={cert.not_after || '—'} />
          <ShowField label="Key / Sig" mono
            value={`${cert.key_bits ? cert.key_bits + ' bit' : '—'} · ${cert.signature_algorithm || '—'}`} />
        </div>
        {(data.flags ?? []).length > 0 && (
          <div style={{ margin: '8px 0' }}>
            {data.flags.map((f) => (
              <span key={f} className="badge badge-err" style={{ marginRight: 6 }}>
                {t('inv.osslFlag' + f.split('_').map((s) => s.charAt(0) + s.slice(1).toLowerCase()).join('')) || f}
              </span>
            ))}
          </div>
        )}
        <div className="show-section-header">{t('inv.osslRaw')}</div>
        {(data.raw ?? []).map((r, i) => (
          <details key={i} style={{ marginBottom: 6 }}>
            <summary className="show-field-mono" style={{ cursor: 'pointer', color: 'var(--text-muted)' }}>
              {r.cmd}
            </summary>
            <pre className="show-pre">{r.output}</pre>
          </details>
        ))}
      </>
    )
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

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('inv.colDomain')}</th>
              <th>{t('inv.colPort')}</th>
              <th>{t('inv.colTier')}</th>
              <th>{t('inv.colSyTeam')}</th>
              <th>{t('inv.colUgTeam')}</th>
              <th>{t('inv.colDesc')}</th>
              <th>{t('inv.colActive')}</th>
              <th>{t('inv.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {visibleItems.map((item) => (
              <tr key={item.id} className={item.deleted_at ? 'inv-row-deleted' : ''}>
                <td><strong>{item.domain}</strong></td>
                <td>{item.port}</td>
                <td>
                  {item.tier
                    ? <span className={`tier-badge tier-badge-${item.tier}`}>T{item.tier}</span>
                    : <span style={{ color: 'var(--text-light)', fontSize: '.8em' }}>—</span>}
                </td>
                <td>{teamMap[String(item.team_id)] || '—'}</td>
                <td>{ugTeamName(item.ug_team_id)}</td>
                <td>{item.description || '—'}</td>
                <td>
                  {item.deleted_at
                    ? <span className="badge badge-deleted">{t('inv.deletedBadge')}</span>
                    : <span className={item.active ? 'badge badge-ok' : 'badge badge-err'}>
                        {item.active ? t('inv.active') : t('inv.inactive')}
                      </span>
                  }
                </td>
                <td>
                  <button className="btn-sm btn-show" onClick={() => setShowItem(item)}>
                    {t('inv.show')}
                  </button>
                  {item.deleted_at ? (
                    canManage && (
                      <button className="btn-sm btn-success" onClick={() => restore(item.id)}>
                        {t('inv.restore')}
                      </button>
                    )
                  ) : (
                    <>
                      {isAdmin && (
                        <button className="btn-sm btn-show" onClick={() => runDiag(item)}>
                          {t('inv.diagnose')}
                        </button>
                      )}
                      {canManage && <button className="btn-sm btn-edit" onClick={() => openEdit(item)}>{t('inv.edit')}</button>}
                      {isAdmin && teams.length > 1 && (
                        <button className="btn-sm btn-transfer-sy"
                          onClick={() => openTransfer(item)}>
                          {t('inv.transfer')}
                        </button>
                      )}
                      {canManage && <button className="btn-sm btn-del" onClick={() => del(item.id)}>{t('inv.delete')}</button>}
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ── Ana Form Modalı ── */}
      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
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
                  disabled={!isAdmin}
                  searchThreshold={2}
                  options={[
                    { value: '', label: t('inv.selectTeam') },
                    ...syTeams.map(team => ({ value: team.id, label: team.name })),
                  ]}
                />
              </label>

              <label>
                <span>{t('inv.formUgTeam')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.ug_team_id}
                  onChange={v => f('ug_team_id', v)}
                  placeholder={t('inv.selectTeam')}
                  searchThreshold={2}
                  options={[
                    { value: '', label: t('inv.selectTeam') },
                    ...ugTeams.map(team => ({ value: team.id, label: team.name })),
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
                disabled={saving || !form.domain.trim() || (isAdmin && !form.team_id) || !form.ug_team_id}>
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
                <ShowField label={t('inv.formUgTeam')}  value={teamMap[String(showItem.ug_team_id)] || '—'} />
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
                {t('inv.newSyTeam')}
                <SearchableSelect
                  value={transferTeamId}
                  onChange={v => setTransferTeamId(v)}
                  searchThreshold={2}
                  options={syTeams
                    .filter(team => String(team.id) !== transferUgTeamId)
                    .map(team => ({ value: team.id, label: team.name }))}
                />
              </label>
              <label className="full-width">
                {t('inv.newUgTeam')}
                <SearchableSelect
                  value={transferUgTeamId}
                  onChange={v => setTransferUgTeamId(v)}
                  searchThreshold={2}
                  options={ugTeams
                    .filter(team => String(team.id) !== transferTeamId)
                    .map(team => ({ value: team.id, label: team.name }))}
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

      {/* ── Bağlantı Tanılama Modalı ── */}
      {diag && (
        <div className="modal-overlay" onClick={() => setDiag(null)}>
          <div className="modal-box modal-show" onClick={(e) => e.stopPropagation()}>

            <div className="show-header">
              <div className="show-header-title">
                <span className="show-domain">{t('inv.diagTitle', diag.item.domain)}</span>
                <span className="show-badge show-badge-port">:{diag.item.port || 443}</span>
              </div>
              <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setDiag(null)}>✕</button>
            </div>

            <div className="show-body">

              {diag.loading && (
                <div className="show-field show-field-full">
                  <span className="show-field-value">
                    <Loader2 size={14} className="spin" /> {t('inv.diagRunning')}
                  </span>
                </div>
              )}

              {diag.error && (
                <div className="alert-msg">{diag.error}</div>
              )}

              {diag.data && (
                <>
                  <div className="show-section-header">{t('inv.diagSource')}</div>
                  <div className="show-grid-2">
                    <ShowField label={t('inv.diagSourceHost')} mono
                      value={diag.data.source?.hostname || '—'} />
                    <ShowField label={t('inv.diagSourceIps')} mono
                      value={diag.data.source?.ips?.length ? diag.data.source.ips.join(', ') : '—'} />
                  </div>

                  <div className="show-section-header">{t('inv.diagDns')}</div>
                  <div className="show-grid-2">
                    <ShowField label={t('inv.diagDnsIps')} mono value={
                      diag.data.dns?.error
                        ? <span className="badge badge-err">{diag.data.dns.error}</span>
                        : (diag.data.dns?.ips?.length ? diag.data.dns.ips.join(', ') : '—')
                    } />
                    <ShowField label={t('inv.diagColElapsed')} value={diag.data.dns?.elapsed_ms ?? '—'} />
                  </div>

                  <div className="show-section-header">{t('inv.diagMatrix')}</div>
                  <div className="health-table-wrap">
                    <table className="health-table">
                      <thead>
                        <tr>
                          <th>{t('inv.diagColCombo')}</th>
                          <th>{t('inv.diagColStep')}</th>
                          <th>{t('inv.diagColElapsed')}</th>
                          <th>{t('inv.diagColResult')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(diag.data.combos ?? []).map((c) => (
                          <tr key={c.id}>
                            <td>
                              <strong>{c.via === 'proxy' ? t('inv.diagViaProxy') : t('inv.diagViaDirect')}</strong>
                              {' + '}{c.tls_mode}
                              {c.source_ip && (
                                <div className="show-field-mono" style={{ color: 'var(--text-muted)', marginTop: 2 }}>
                                  {c.source_ip}:{c.source_port} → {c.peer_ip}:{c.peer_port}
                                  {c.via === 'proxy' && ` (${t('inv.diagViaProxy')}) → ${diag.item.domain}:${diag.item.port || 443}`}
                                </div>
                              )}
                              {c.via === 'proxy' && diag.data.proxy_address && (
                                <div className="show-field-mono" style={{ color: 'var(--text-muted)', marginTop: 2 }}>
                                  {t('inv.diagProxyVia')}: {diag.data.proxy_address}
                                </div>
                              )}
                            </td>
                            <td>{diagStepLabel(c.step_reached)}</td>
                            <td>{c.elapsed_ms ?? '—'}</td>
                            <td>
                              <span className={c.status === 'ok' ? 'badge badge-ok' : 'badge badge-err'}>
                                {c.status === 'ok' ? t('inv.diagOk') : (c.error_class || 'ERROR')}
                              </span>
                              {c.status === 'ok'
                                ? <span style={{ marginLeft: 8 }}>{c.subject} · {c.days_remaining}d · {c.tls_version}</span>
                                : <span style={{ marginLeft: 8 }}>{(c.error || '').slice(0, 120)}</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  {/* ── Derin SSL/TLS taraması (openssl) ── */}
                  <div className="show-section-header" style={{ marginTop: 18 }}>{t('inv.osslTitle')}</div>
                  {!diag.ossl && (
                    <button className="btn btn-secondary btn-sm-p" style={{ marginTop: 6 }}
                      onClick={() => runOpenssl(diag.item)}>{t('inv.osslRun')}</button>
                  )}
                  {diag.ossl?.loading && (
                    <div className="show-field-value"><Loader2 size={14} className="spin" /> {t('inv.diagRunning')}</div>
                  )}
                  {diag.ossl?.error && <div className="alert-msg">{diag.ossl.error}</div>}
                  {diag.ossl?.data && renderOssl(diag.ossl.data)}

                  {/* ── Ağ Derin Analizi ── */}
                  <div className="show-section-header" style={{ marginTop: 18 }}>{t('inv.netTitle')}</div>
                  {!diag.net && (
                    <button className="btn btn-secondary btn-sm-p" style={{ marginTop: 6 }}
                      onClick={() => runNetwork(diag.item)}>{t('inv.netRun')}</button>
                  )}
                  {diag.net?.loading && (
                    <div className="show-field-value"><Loader2 size={14} className="spin" /> {t('inv.diagRunning')}</div>
                  )}
                  {diag.net?.error && <div className="alert-msg">{diag.net.error}</div>}
                  {diag.net?.data && renderNetwork(diag.net.data)}
                </>
              )}

              <div className="modal-actions">
                <button className="btn btn-secondary" onClick={() => setDiag(null)}>{t('app.dismiss')}</button>
                <button className="btn btn-secondary" onClick={() => openHistory(diag.item)}>
                  {t('inv.diagHistory')}
                </button>
                <button className="btn btn-primary" onClick={() => runDiag(diag.item)} disabled={!!diag.loading}>
                  {t('inv.diagRerun')}
                </button>
              </div>

            </div>
          </div>
        </div>
      )}

      {/* ── Tanılama geçmişi ── */}
      {history && (
        <div className="modal-overlay" onClick={() => setHistory(null)}>
          <div className="modal-box modal-show" onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 820, width: '100%', maxHeight: '88vh', display: 'flex', flexDirection: 'column' }}>
            <div className="show-header">
              <div className="show-header-title">
                <span className="show-domain">{t('inv.diagHistory')} — {history.item.domain}</span>
              </div>
              <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setHistory(null)}>✕</button>
            </div>
            <div className="show-body" style={{ overflowY: 'auto' }}>
              {history.loading && (
                <div className="show-field-value"><Loader2 size={14} className="spin" /> {t('inv.diagRunning')}</div>
              )}
              {history.error && <div className="alert-msg">{history.error}</div>}

              {history.items && !history.detail && (
                history.items.length ? (
                  <div className="health-table-wrap">
                    <table className="health-table">
                      <thead>
                        <tr>
                          <th>{t('inv.histColType')}</th>
                          <th>{t('inv.histColWho')}</th>
                          <th>{t('inv.histColWhen')}</th>
                          <th>{t('inv.histColSource')}</th>
                          <th>{t('inv.histColResult')}</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {history.items.map((h) => (
                          <tr key={h.id}>
                            <td>{histTypeLabel(h.run_type)}</td>
                            <td>{h.executed_by}</td>
                            <td>{formatDate(h.executed_at)}</td>
                            <td className="show-field-mono">{h.source_ip || '—'}</td>
                            <td>
                              <span className={h.success ? 'badge badge-ok' : 'badge badge-err'}>
                                {h.success ? t('inv.diagOk') : t('inv.diagError')}
                              </span>
                              {h.summary && <span style={{ marginLeft: 8 }}>{h.summary}</span>}
                            </td>
                            <td>
                              <button className="btn-sm btn-show" onClick={() => openHistoryDetail(h.id)}>
                                {t('inv.histView')}
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="empty-state">{t('inv.histEmpty')}</div>
                )
              )}

              {history.detail && (
                <>
                  <button className="btn btn-secondary btn-sm-p" style={{ marginBottom: 10 }}
                    onClick={() => setHistory((h) => ({ ...h, detail: null }))}>← {t('inv.diagHistory')}</button>
                  <div className="show-grid-2">
                    <ShowField label={t('inv.histColWho')} value={history.detail.executed_by} />
                    <ShowField label={t('inv.histColWhen')} value={formatDate(history.detail.executed_at)} />
                    <ShowField label={t('inv.histColSource')} mono value={history.detail.source_ip || '—'} />
                    <ShowField label={t('inv.histColType')} value={histTypeLabel(history.detail.run_type)} />
                  </div>
                  {/* OPENSSL → openssl render; NETWORK → ağ kartları; CONNECTION → combo matrisi */}
                  {history.detail.run_type === 'OPENSSL'
                    ? <div style={{ marginTop: 8 }}>{renderOssl(history.detail.result)}</div>
                    : history.detail.run_type === 'NETWORK'
                    ? <div style={{ marginTop: 8 }}>{renderNetwork(history.detail.result)}</div>
                    : (
                      <>
                        <div className="show-section-header" style={{ marginTop: 8 }}>{t('inv.diagMatrix')}</div>
                        <div className="health-table-wrap">
                          <table className="health-table">
                            <thead>
                              <tr>
                                <th>{t('inv.diagColCombo')}</th>
                                <th>{t('inv.diagColStep')}</th>
                                <th>{t('inv.diagColElapsed')}</th>
                                <th>{t('inv.diagColResult')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(history.detail.result?.combos ?? []).map((c) => (
                                <tr key={c.id}>
                                  <td><strong>{c.via === 'proxy' ? t('inv.diagViaProxy') : t('inv.diagViaDirect')}</strong>{' + '}{c.tls_mode}</td>
                                  <td>{diagStepLabel(c.step_reached)}</td>
                                  <td>{c.elapsed_ms ?? '—'}</td>
                                  <td>
                                    <span className={c.status === 'ok' ? 'badge badge-ok' : 'badge badge-err'}>
                                      {c.status === 'ok' ? t('inv.diagOk') : (c.error_class || 'ERROR')}
                                    </span>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </>
                    )}
                </>
              )}

              <div className="modal-actions">
                <button className="btn btn-secondary" onClick={() => setHistory(null)}>{t('app.dismiss')}</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
