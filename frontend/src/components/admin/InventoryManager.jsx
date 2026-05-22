import { useState, useEffect } from 'react'
import { api, formatDate } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'

const DEFAULT_CHANGE_TEMPLATE =
`- IISAdmins ekibi sertifika alım sürecini yürütür.
- IISAdmins, PFX halindeki sertifikayı ADCAdmins ve Güvenlik ekibi ile paylaşır.
- Değişiklik planlaması Servis Yönetimi tarafından ilgili ekiplerle koordineli yapılır.
- Sertifika Netscaler ve WAF'ta güncellenir.
- Ankara ve Gebze tarafında iki ayrı WAF cihazı vardır; WAF ekibine hatırlatılmalıdır.`

const EMPTY = {
  domain: '', port: 443, owner: '', description: '', active: true,
  team_id: '', ug_team_id: '', tier: null,
  external_vendor: false, action_required: false, openshift: false,
  ssl_pinning: false, internal_cert: false, jks_keystore: false,
  server_update: false, netscaler: false, waf_enabled: false,
  in_use: false, ev_certificate: false, transferred_to_sy: false,
  purchased_by: '',
  change_description: DEFAULT_CHANGE_TEMPLATE,
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
  const { showConfirm } = useDialog()
  const isAdmin = systemRole ? systemRole === 'ADMIN' : isAdminProp
  const [items, setItems]             = useState([])
  const [teams, setTeams]             = useState(teamsProp)
  const [modal, setModal]             = useState(null)
  const [transferModal, setTransferModal] = useState(null)
  const [transferTeamId, setTransferTeamId]     = useState('')
  const [transferUgTeamId, setTransferUgTeamId] = useState('')
  const [form, setForm]               = useState(EMPTY)
  const [saving, setSaving]           = useState(false)
  const [msg, setMsg]                 = useState(null)
  const [showDeleted, setShowDeleted] = useState(false)
  const [showItem,    setShowItem]    = useState(null)

  const teamMap  = Object.fromEntries(teams.map(t => [String(t.id), t.name]))
  const syTeams  = teams.filter(t => !t.team_type || t.team_type === 'SY')
  const ugTeams  = teams.filter(t => !t.team_type || t.team_type === 'UG')

  useEffect(() => {
    load()
    if (isAdmin) api.admin.getTeams().then(res => { if (res?.success) setTeams(res.data) })
  }, [])

  useEffect(() => { load() }, [showDeleted])

  async function load() {
    const res = await api.admin.getInventory(showDeleted)
    if (res?.success) setItems(res.data)
  }

  function f(field, val) { setForm(prev => ({ ...prev, [field]: val })) }

  function openAdd() {
    setForm({ ...EMPTY, change_description: DEFAULT_CHANGE_TEMPLATE })
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
    if (err) { setMsg(err); return }
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
    if (res?.success) { setModal(null); setMsg(t('inv.saved')); load(); onInventoryChange?.() }
    else setMsg(res?.error || 'Error')
  }

  async function del(id) {
    const item = items.find(i => i.id === id)
    const ok = await showConfirm({
      title: t('inv.deleteTitle'),
      message: t('inv.deleteMsg', item?.domain ?? id),
      variant: 'danger',
      confirmText: t('inv.deleteConfirm'),
      cancelText: t('inv.deleteCancel'),
    })
    if (!ok) return
    await api.admin.deleteInventory(id)
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
    if (res?.success) { setMsg(t('inv.restored')); load(); onInventoryChange?.() }
    else setMsg(res?.error || 'Error')
  }

  async function doTransfer() {
    const syChanged = transferTeamId   !== String(transferModal.team_id   ?? '')
    const ugChanged = transferUgTeamId !== String(transferModal.ug_team_id ?? '')
    if (!syChanged && !ugChanged) { setTransferModal(null); return }
    setSaving(true)
    const results = await Promise.all([
      syChanged ? api.admin.transferCertSy(transferModal.id, Number(transferTeamId))   : Promise.resolve({ success: true }),
      ugChanged ? api.admin.transferCertUg(transferModal.id, Number(transferUgTeamId)) : Promise.resolve({ success: true }),
    ])
    setSaving(false)
    const errResult = results.find(r => !r?.success)
    if (!errResult) { setTransferModal(null); setMsg(t('inv.transferred')); load() }
    else setMsg(errResult.error || 'Error')
  }

  const ugTeamName = (id) => teamMap[String(id)] || '—'

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('inv.title')}</h3>
        <button className="btn btn-success" onClick={openAdd}>{t('inv.addBtn')}</button>
      </div>
      {msg && <div className="alert-msg">{msg}</div>}

      {isAdmin && (
        <div className="inv-filter-bar">
          <label className="inv-deleted-toggle">
            <input type="checkbox" checked={showDeleted}
              onChange={e => setShowDeleted(e.target.checked)} />
            {t('inv.showDeleted')}
          </label>
        </div>
      )}

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('inv.colDomain')}</th>
              <th>{t('inv.colPort')}</th>
              <th>{t('inv.colTier')}</th>
              <th>{t('inv.colSyTeam')}</th>
              <th>{t('inv.colUgTeam')}</th>
              <th>{t('inv.colOwner')}</th>
              <th>{t('inv.colDesc')}</th>
              <th>{t('inv.colActive')}</th>
              <th>{t('inv.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
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
                <td>{item.owner || '—'}</td>
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
                    isAdmin && (
                      <button className="btn-sm btn-success" onClick={() => restore(item.id)}>
                        {t('inv.restore')}
                      </button>
                    )
                  ) : (
                    <>
                      <button className="btn-sm btn-edit" onClick={() => openEdit(item)}>{t('inv.edit')}</button>
                      {isAdmin && teams.length > 1 && (
                        <button className="btn-sm btn-transfer-sy"
                          onClick={() => openTransfer(item)}>
                          {t('inv.transfer')}
                        </button>
                      )}
                      <button className="btn-sm btn-del" onClick={() => del(item.id)}>{t('inv.delete')}</button>
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

            <div className="form-grid">

              {/* ── Temel Bilgiler ── */}
              <SectionHeader label={t('inv.sectionBasic')} />

              <label>
                {t('inv.formDomain')} <span style={{ color: 'var(--danger)' }}>*</span>
                <input value={form.domain} onChange={e => f('domain', e.target.value)} placeholder={t('inv.formDomainPh')} />
              </label>
              <label>
                {t('inv.formPort')}
                <input type="number" value={form.port} onChange={e => f('port', e.target.value)} />
              </label>

              <label>
                {t('inv.formTeam')} <span style={{ color: 'var(--danger)' }}>*</span>
                <SearchableSelect
                  value={form.team_id}
                  onChange={v => f('team_id', v)}
                  placeholder={t('inv.selectTeam')}
                  disabled={!isAdmin}
                  options={[
                    { value: '', label: t('inv.selectTeam') },
                    ...syTeams.map(team => ({ value: team.id, label: team.name })),
                  ]}
                />
              </label>

              <label>
                {t('inv.formUgTeam')} <span style={{ color: 'var(--danger)' }}>*</span>
                <SearchableSelect
                  value={form.ug_team_id}
                  onChange={v => f('ug_team_id', v)}
                  placeholder={t('inv.selectTeam')}
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
                {t('inv.formOwner')}
                <input value={form.owner} onChange={e => f('owner', e.target.value)} />
              </label>

              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={e => f('active', e.target.checked)} />
                {t('inv.formActive')}
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
                ].map(([field, key]) => (
                  <div key={field} className="yn-field-row">
                    <span className="yn-field-label">{t(key)}</span>
                    <YesNo value={form[field]} onChange={v => f(field, v)} />
                  </div>
                ))}
              </div>

              {/* ── Süreç Bilgileri ── */}
              <SectionHeader label={t('inv.sectionProcess')} />

              <label className="full-width">
                {t('inv.formPurchasedBy')}
                <input value={form.purchased_by} onChange={e => f('purchased_by', e.target.value)} />
              </label>

              <div className="yn-field-row" style={{ gridColumn: '1 / -1' }}>
                <span className="yn-field-label">{t('inv.formTransferredToSy')}</span>
                <YesNo value={form.transferred_to_sy} onChange={v => f('transferred_to_sy', v)} />
              </div>

              {/* ── Açıklamalar ── */}
              <SectionHeader label={t('inv.sectionDesc')} />

              <label className="full-width">
                {t('inv.formDescription')}
                <textarea rows={4} value={form.description}
                  onChange={e => f('description', e.target.value)}
                  style={{ resize: 'vertical', fontFamily: 'inherit' }} />
              </label>

              <label className="full-width">
                {t('inv.formChangeDesc')}
                <textarea rows={7} value={form.change_description}
                  onChange={e => f('change_description', e.target.value)}
                  style={{ resize: 'vertical', fontFamily: 'monospace', fontSize: '.82em' }} />
              </label>

              {/* ── Gelişmiş ── */}
              <SectionHeader label={t('inv.sectionAdv')} />

              <label className="full-width">
                {t('inv.formFP')}
                <input value={form.expected_fingerprint}
                  onChange={e => f('expected_fingerprint', e.target.value)}
                  placeholder={t('inv.formFPPh')} />
              </label>
              <label className="full-width">
                {t('inv.formSubject')}
                <input value={form.expected_subject}
                  onChange={e => f('expected_subject', e.target.value)} />
              </label>

            </div>

            {msg && <div className="alert-msg" style={{ marginTop: 10 }}>{msg}</div>}

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
              <button className="show-close" onClick={() => setShowItem(null)}>✕</button>
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
                <ShowField label={t('inv.formOwner')}   value={showItem.owner || '—'} />
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
                ].map(([key, val]) => (
                  <div key={key} className="show-yn-cell">
                    <span className="show-yn-label">{t(key)}</span>
                    <span className={`show-yn-badge ${val ? 'show-yn-yes' : 'show-yn-no'}`}>
                      {val ? t('inv.yes') : t('inv.no')}
                    </span>
                  </div>
                ))}
              </div>

              {/* Süreç Bilgileri */}
              <div className="show-section-header">{t('inv.sectionProcess')}</div>
              <div className="show-grid-2">
                <ShowField label={t('inv.formPurchasedBy')} value={showItem.purchased_by || '—'} />
                <div className="show-field">
                  <span className="show-field-label">{t('inv.formTransferredToSy')}</span>
                  <span className={`show-yn-badge ${showItem.transferred_to_sy ? 'show-yn-yes' : 'show-yn-no'}`}>
                    {showItem.transferred_to_sy ? t('inv.yes') : t('inv.no')}
                  </span>
                </div>
              </div>

              {/* Açıklamalar */}
              {(showItem.description || showItem.change_description) && (
                <>
                  <div className="show-section-header">{t('inv.sectionDesc')}</div>
                  {showItem.description && (
                    <ShowField label={t('inv.formDescription')} value={showItem.description} full />
                  )}
                  {showItem.change_description && (
                    <div className="show-field show-field-full">
                      <span className="show-field-label">{t('inv.formChangeDesc')}</span>
                      <pre className="show-pre">{showItem.change_description}</pre>
                    </div>
                  )}
                </>
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
    </div>
  )
}
