import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'

const emptyTeam = { name: '', email: '', description: '', active: true, leader_id: '' }

export default function TeamManager({ onTeamsChange }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [teams, setTeams]   = useState([])
  const [users, setUsers]   = useState([])
  const [modal, setModal]   = useState(null)
  const [form, setForm]     = useState(emptyTeam)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg]       = useState(null)

  useEffect(() => { load(); loadUsers() }, [])

  async function load() {
    const res = await api.admin.getTeams()
    if (res?.success) setTeams(res.data)
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers(res.data.filter(u => u.active))
  }

  const userMap = Object.fromEntries(users.map(u => [u.id, u.displayName || u.username]))

  function openAdd() { setForm(emptyTeam); setModal('add') }
  function openEdit(team) {
    setForm({ ...team, email: team.email || '', leader_id: String(team.leaderId ?? team.leader_id ?? '') })
    setModal(team)
  }

  async function save() {
    if (!form.leader_id) { setMsg(t('team.leaderRequired')); return }
    setSaving(true)
    const payload = {
      name: form.name.trim(),
      email: form.email.trim(),
      description: form.description,
      active: form.active,
      leader_id: Number(form.leader_id),
    }
    const res = modal === 'add'
      ? await api.admin.createTeam(payload)
      : await api.admin.updateTeam(modal.id, payload)
    setSaving(false)
    if (res?.success) { setModal(null); setMsg(t('team.saved')); load(); onTeamsChange?.() }
    else setMsg(res?.error || 'Error')
  }

  async function del(id) {
    const team = teams.find(t => t.id === id)
    const ok = await showConfirm({
      title: t('team.deleteTitle'),
      message: t('team.deleteMsg', team?.name ?? id),
      variant: 'danger',
      confirmText: t('team.deleteConfirm'),
      cancelText: t('team.deleteCancel'),
    })
    if (!ok) return
    await api.admin.deleteTeam(id)
    load()
    onTeamsChange?.()
  }

  const canSave = form.name.trim() && form.email.trim() && form.leader_id

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('team.title')}</h3>
        <button className="btn btn-success" onClick={openAdd}>{t('team.addBtn')}</button>
      </div>
      {msg && <div className="alert-msg">{msg}</div>}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('team.colName')}</th>
              <th>{t('team.colEmail')}</th>
              <th>{t('team.colLeader')}</th>
              <th>{t('team.colDesc')}</th>
              <th>{t('team.colActive')}</th>
              <th>{t('team.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {teams.map((team) => (
              <tr key={team.id}>
                <td><strong>{team.name}</strong></td>
                <td>{team.email || '—'}</td>
                <td>{userMap[team.leaderId] ?? <span style={{ color: 'var(--danger)' }}>{t('team.noLeader')}</span>}</td>
                <td>{team.description || '—'}</td>
                <td><span className={team.active ? 'badge badge-ok' : 'badge badge-err'}>{team.active ? t('team.active') : t('team.inactive')}</span></td>
                <td>
                  <button className="btn-sm btn-edit" onClick={() => openEdit(team)}>{t('team.edit')}</button>
                  <button className="btn-sm btn-del" onClick={() => del(team.id)}>{t('team.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{modal === 'add' ? t('team.addTitle') : t('team.editTitle')}</h3>
            <div className="form-grid">
              <label>
                {t('team.formName')} <span style={{ color: 'var(--danger)' }}>*</span>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={t('team.formNamePh')} />
              </label>
              <label>
                {t('team.formEmail')} <span style={{ color: 'var(--danger)' }}>*</span>
                <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="team@example.com" />
              </label>
              <label>
                {t('team.formLeader')} <span style={{ color: 'var(--danger)' }}>*</span>
                <select value={form.leader_id} onChange={(e) => setForm({ ...form, leader_id: e.target.value })}>
                  <option value="">{t('team.selectLeader')}</option>
                  {users.map(u => (
                    <option key={u.id} value={u.id}>{u.displayName || u.username} ({u.username})</option>
                  ))}
                </select>
              </label>
              <label className="full-width">{t('team.formDesc')}
                <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('team.formActive')}
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('team.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !canSave}>
                {saving ? t('team.saving') : t('team.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
