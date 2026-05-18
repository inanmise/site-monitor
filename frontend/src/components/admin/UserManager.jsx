import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'

const emptyUser = { username: '', password: '', display_name: '', email: '', employee_id: '', system_role: 'USER', team_id: '', active: true }

export default function UserManager({ teams }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [users, setUsers] = useState([])
  const [modal, setModal] = useState(null)
  const [pwdModal, setPwdModal] = useState(null)
  const [form, setForm] = useState(emptyUser)
  const [newPwd, setNewPwd] = useState('')
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  const teamMap = Object.fromEntries((teams || []).map(t => [t.id, t.name]))

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers(res.data)
  }

  function openAdd() { setForm({ ...emptyUser, team_id: teams?.[0]?.id ?? '' }); setModal('add') }
  function openEdit(user) {
    setForm({
      username: user.username,
      password: '',
      display_name: user.display_name || '',
      email: user.email || '',
      employee_id: user.employee_id || '',
      system_role: user.system_role || 'USER',
      team_id: user.team_id ?? '',
      active: user.active,
    })
    setModal(user)
  }

  async function save() {
    setSaving(true)
    const payload = {
      username: form.username.trim(),
      display_name: form.display_name,
      email: form.email,
      employee_id: form.employee_id,
      system_role: form.system_role,
      team_id: form.team_id || null,
      active: form.active,
    }
    let res
    if (modal === 'add') {
      res = await api.admin.createUser({ ...payload, password: form.password })
    } else {
      res = await api.admin.updateUser(modal.id, payload)
    }
    setSaving(false)
    if (res?.success) { setModal(null); setMsg(t('usr.saved')); load() }
    else setMsg(res?.error || 'Error')
  }

  async function savePwd() {
    if (!newPwd || newPwd.length < 4) return
    setSaving(true)
    const res = await api.admin.resetPassword(pwdModal.id, newPwd)
    setSaving(false)
    if (res?.success) { setPwdModal(null); setNewPwd(''); setMsg(t('usr.pwdChanged')) }
    else setMsg(res?.error || 'Error')
  }

  async function del(id) {
    const user = users.find(u => u.id === id)
    const ok = await showConfirm({
      title: t('usr.deleteTitle'),
      message: t('usr.deleteMsg', user?.username ?? id),
      variant: 'danger',
      confirmText: t('usr.deleteConfirm'),
      cancelText: t('usr.deleteCancel'),
    })
    if (!ok) return
    await api.admin.deleteUser(id)
    load()
  }

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('usr.title')}</h3>
        <button className="btn btn-success" onClick={openAdd}>{t('usr.addBtn')}</button>
      </div>
      {msg && <div className="alert-msg">{msg}</div>}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('usr.colUsername')}</th>
              <th>{t('usr.colEmployeeId')}</th>
              <th>{t('usr.colDisplay')}</th>
              <th>{t('usr.colEmail')}</th>
              <th>{t('usr.colRole')}</th>
              <th>{t('usr.colTeam')}</th>
              <th>{t('usr.colActive')}</th>
              <th>{t('usr.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.id}>
                <td><strong>{user.username}</strong></td>
                <td>{user.employee_id || '—'}</td>
                <td>{user.display_name || '—'}</td>
                <td>{user.email || '—'}</td>
                <td><span className={`role-badge${user.system_role === 'ADMIN' ? ' role-admin' : user.system_role === 'AUDIT' ? ' role-audit' : ''}`}>{user.system_role}</span></td>
                <td>{teamMap[user.team_id] || '—'}</td>
                <td><span className={user.active ? 'badge badge-ok' : 'badge badge-err'}>{user.active ? t('usr.active') : t('usr.inactive')}</span></td>
                <td>
                  <button className="btn-sm btn-edit" onClick={() => openEdit(user)}>{t('usr.edit')}</button>
                  <button className="btn-sm" style={{ background: '#6366f1', color: '#fff', marginRight: 4 }} onClick={() => { setPwdModal(user); setNewPwd('') }}>{t('usr.pwd')}</button>
                  <button className="btn-sm btn-del" onClick={() => del(user.id)}>{t('usr.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{modal === 'add' ? t('usr.addTitle') : t('usr.editTitle')}</h3>
            <div className="form-grid">
              <label>{t('usr.formUsername')}
                <input value={form.username} disabled={modal !== 'add'}
                  onChange={(e) => setForm({ ...form, username: e.target.value })} />
              </label>
              {modal === 'add' && (
                <label>{t('usr.formPassword')}
                  <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
                </label>
              )}
              <label>{t('usr.formDisplay')}
                <input value={form.display_name} onChange={(e) => setForm({ ...form, display_name: e.target.value })} />
              </label>
              <label>
                {t('usr.formEmail')} <span style={{ color: 'var(--danger)' }}>*</span>
                <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
              </label>
              <label>{t('usr.formEmployeeId')}
                <input value={form.employee_id} onChange={(e) => setForm({ ...form, employee_id: e.target.value })} />
              </label>
              <label>{t('usr.formRole')}
                <select value={form.system_role} onChange={(e) => setForm({ ...form, system_role: e.target.value })}>
                  <option value="USER">USER</option>
                  <option value="AUDIT">AUDIT</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </label>
              <label>
                {t('usr.formTeam')} <span style={{ color: 'var(--danger)' }}>*</span>
                <select value={form.team_id} onChange={(e) => setForm({ ...form, team_id: e.target.value ? Number(e.target.value) : '' })}>
                  <option value="">{t('usr.noTeam')}</option>
                  {(teams || []).map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
                </select>
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('usr.formActive')}
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('usr.cancel')}</button>
              <button className="btn btn-primary" onClick={save}
                disabled={saving || !form.username.trim() || !form.email.trim() || !form.team_id || (modal === 'add' && form.password.length < 4)}>
                {saving ? t('usr.saving') : t('usr.save')}
              </button>
            </div>
          </div>
        </div>
      )}

      {pwdModal && (
        <div className="modal-overlay" onClick={() => setPwdModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{t('usr.pwdTitle', pwdModal.username)}</h3>
            <div className="form-grid">
              <label className="full-width">{t('usr.formNewPwd')}
                <input type="password" value={newPwd} onChange={(e) => setNewPwd(e.target.value)} autoFocus />
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setPwdModal(null)}>{t('usr.cancel')}</button>
              <button className="btn btn-primary" onClick={savePwd} disabled={saving || newPwd.length < 4}>
                {saving ? t('usr.saving') : t('usr.pwdSave')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
