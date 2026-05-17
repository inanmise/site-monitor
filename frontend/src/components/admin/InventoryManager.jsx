import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'

const emptyItem = { domain: '', port: 443, description: '', owner: '', active: true, expectedFingerprint: '', expectedSubject: '', team_id: '' }

export default function InventoryManager({ onInventoryChange, teams = [], isAdmin = false }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [items, setItems] = useState([])
  const [modal, setModal] = useState(null)
  const [transferModal, setTransferModal] = useState(null)
  const [transferTeamId, setTransferTeamId] = useState('')
  const [form, setForm] = useState(emptyItem)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  const teamMap = Object.fromEntries(teams.map(t => [t.id, t.name]))

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getInventory()
    if (res?.success) setItems(res.data)
  }

  function openAdd() { setForm({ ...emptyItem, team_id: String(teams[0]?.id ?? '') }); setModal('add') }
  function openEdit(item) {
    setForm({
      ...item,
      expectedFingerprint: item.expected_fingerprint || '',
      expectedSubject: item.expected_subject || '',
      team_id: String(item.team_id ?? ''),
    })
    setModal(item)
  }

  async function save() {
    setSaving(true)
    const payload = {
      domain: form.domain.trim(),
      port: parseInt(form.port) || 443,
      description: form.description,
      owner: form.owner,
      active: form.active,
      expectedFingerprint: form.expectedFingerprint || null,
      expectedSubject: form.expectedSubject || null,
      team_id: form.team_id ? Number(form.team_id) : null,
    }
    const res = modal === 'add'
      ? await api.admin.addInventory(payload)
      : await api.admin.updateInventory(modal.id, payload)
    setSaving(false)
    if (res?.success) { setModal(null); setMsg(t('inv.saved')); load() }
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

  async function doTransfer() {
    if (!transferTeamId) return
    setSaving(true)
    const res = await api.admin.transferCert(transferModal.id, Number(transferTeamId))
    setSaving(false)
    if (res?.success) { setTransferModal(null); setMsg(t('inv.transferred')); load() }
    else setMsg(res?.error || 'Error')
  }

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('inv.title')}</h3>
        <button className="btn btn-success" onClick={openAdd}>{t('inv.addBtn')}</button>
      </div>
      {msg && <div className="alert-msg">{msg}</div>}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('inv.colDomain')}</th>
              <th>{t('inv.colPort')}</th>
              <th>{t('inv.colTeam')}</th>
              <th>{t('inv.colOwner')}</th>
              <th>{t('inv.colDesc')}</th>
              <th>{t('inv.colActive')}</th>
              <th>{t('inv.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id}>
                <td><strong>{item.domain}</strong></td>
                <td>{item.port}</td>
                <td>{teamMap[item.team_id] || '—'}</td>
                <td>{item.owner || '—'}</td>
                <td>{item.description || '—'}</td>
                <td><span className={item.active ? 'badge badge-ok' : 'badge badge-err'}>{item.active ? t('inv.active') : t('inv.inactive')}</span></td>
                <td>
                  <button className="btn-sm btn-edit" onClick={() => openEdit(item)}>{t('inv.edit')}</button>
                  {isAdmin && teams.length > 1 && (
                    <button className="btn-sm" style={{ background: '#6366f1', color: '#fff', marginRight: 4 }}
                      onClick={() => { setTransferModal(item); setTransferTeamId(item.team_id ?? '') }}>
                      {t('inv.transfer')}
                    </button>
                  )}
                  <button className="btn-sm btn-del" onClick={() => del(item.id)}>{t('inv.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{modal === 'add' ? t('inv.addTitle') : t('inv.editTitle')}</h3>
            <div className="form-grid">
              <label>{t('inv.formDomain')}<input value={form.domain} onChange={(e) => setForm({ ...form, domain: e.target.value })} placeholder={t('inv.formDomainPh')} /></label>
              <label>{t('inv.formPort')}<input type="number" value={form.port} onChange={(e) => setForm({ ...form, port: e.target.value })} /></label>
              {isAdmin && teams.length > 0 && (
                <label>{t('inv.formTeam')}
                  <select value={form.team_id} onChange={(e) => setForm({ ...form, team_id: e.target.value })}>
                    {teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
                  </select>
                </label>
              )}
              <label>{t('inv.formOwner')}<input value={form.owner} onChange={(e) => setForm({ ...form, owner: e.target.value })} /></label>
              <label>{t('inv.formDesc')}<input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('inv.formActive')}
              </label>
              <label className="full-width">{t('inv.formFP')}
                <input value={form.expectedFingerprint} onChange={(e) => setForm({ ...form, expectedFingerprint: e.target.value })} placeholder={t('inv.formFPPh')} />
              </label>
              <label className="full-width">{t('inv.formSubject')}
                <input value={form.expectedSubject} onChange={(e) => setForm({ ...form, expectedSubject: e.target.value })} />
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('inv.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.domain.trim()}>
                {saving ? t('inv.saving') : t('inv.save')}
              </button>
            </div>
          </div>
        </div>
      )}

      {transferModal && (
        <div className="modal-overlay" onClick={() => setTransferModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('inv.transferTitle', transferModal.domain)}</h3>
            <div className="form-grid">
              <label className="full-width">{t('inv.transferTeam')}
                <select value={transferTeamId} onChange={(e) => setTransferTeamId(e.target.value)}>
                  {teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
                </select>
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setTransferModal(null)}>{t('inv.cancel')}</button>
              <button className="btn btn-primary" onClick={doTransfer} disabled={saving || !transferTeamId}>
                {saving ? t('inv.saving') : t('inv.transferConfirm')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
