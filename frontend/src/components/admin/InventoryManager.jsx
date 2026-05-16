import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'

const emptyItem = { domain: '', port: 443, description: '', owner: '', active: true, expectedFingerprint: '', expectedSubject: '' }

export default function InventoryManager({ onInventoryChange }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [items, setItems] = useState([])
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyItem)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getInventory()
    if (res?.success) setItems(res.data)
  }

  function openAdd() { setForm(emptyItem); setModal('add') }
  function openEdit(item) {
    setForm({ ...item, expectedFingerprint: item.expected_fingerprint || '', expectedSubject: item.expected_subject || '' })
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
    }
    const res = modal === 'add'
      ? await api.admin.addInventory(payload)
      : await api.admin.updateInventory(modal.id, payload)
    setSaving(false)
    if (res?.success) { setModal(null); setMsg(t('inv.saved')); load() }
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
              <th>{t('inv.colOwner')}</th>
              <th>{t('inv.colDesc')}</th>
              <th>{t('inv.colActive')}</th>
              <th>{t('inv.colFingerprint')}</th>
              <th>{t('inv.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id}>
                <td><strong>{item.domain}</strong></td>
                <td>{item.port}</td>
                <td>{item.owner || '—'}</td>
                <td>{item.description || '—'}</td>
                <td><span className={item.active ? 'badge badge-ok' : 'badge badge-err'}>{item.active ? t('inv.active') : t('inv.inactive')}</span></td>
                <td className="fingerprint-cell">{item.expected_fingerprint ? item.expected_fingerprint.substring(0, 16) + '...' : '—'}</td>
                <td>
                  <button className="btn-sm btn-edit" onClick={() => openEdit(item)}>{t('inv.edit')}</button>
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
    </div>
  )
}
