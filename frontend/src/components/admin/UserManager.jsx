import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { UserPlus, UserCog } from 'lucide-react'
import PasswordChangeModal from './PasswordChangeModal.jsx'
import AdminAutoResetModal from './AdminAutoResetModal.jsx'

const emptyUser = { username: '', password: '', display_name: '', email: '', employee_id: '', system_role: 'USER', team_id: '', org_role: '', active: true }

export default function UserManager({ teams }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [users, setUsers] = useState([])
  const [modal, setModal] = useState(null)
  const [pwdModal, setPwdModal] = useState(null)
  const [autoResetModal, setAutoResetModal] = useState(null)
  const [form, setForm] = useState(emptyUser)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  const teamMap = Object.fromEntries((teams || []).map(t => [t.id, t.name]))

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers(res.data)
  }

  function openAdd() { setForm(emptyUser); setMsg(null); setModal('add') }
  function openEdit(user) {
    setForm({
      username: user.username,
      password: '',
      display_name: user.display_name || '',
      email: user.email || '',
      employee_id: user.employee_id || '',
      system_role: user.system_role || 'USER',
      team_id: user.team_id ?? '',
      org_role: user.org_role || '',
      active: user.active,
    })
    setMsg(null)
    setModal(user)
  }

  async function save() {
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      setMsg(t('usr.emailInvalid'))
      return
    }
    setSaving(true)
    const payload = {
      username: form.username.trim(),
      display_name: form.display_name,
      email: form.email,
      employee_id: form.employee_id,
      system_role: form.system_role,
      team_id: form.team_id || null,
      org_role: form.org_role || null,
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

  async function unlock(id) {
    const res = await api.admin.unlockUser(id)
    if (res?.success) { setMsg(t('usr.unlocked')); load() }
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
      {msg && !modal && !pwdModal && <div className="alert-msg">{msg}</div>}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('usr.colUsername')}</th>
              <th>{t('usr.colEmployeeId')}</th>
              <th>{t('usr.colDisplay')}</th>
              <th>{t('usr.colEmail')}</th>
              <th>{t('usr.colRole')}</th>
              <th>{t('usr.colOrgRole')}</th>
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
                <td>{user.org_role ? <span className={`badge-role badge-role-${user.org_role}`}>{user.org_role}</span> : '—'}</td>
                <td>{teamMap[user.team_id] || '—'}</td>
                <td>
                  <span className={user.active ? 'badge badge-ok' : 'badge badge-err'}>{user.active ? t('usr.active') : t('usr.inactive')}</span>
                  {user.permanent_lock && <span className="badge badge-err" style={{ marginLeft: 4 }} title={t('usr.permLocked')}>🔒</span>}
                </td>
                <td>
                  <button className="btn-sm btn-edit" onClick={() => openEdit(user)}>{t('usr.edit')}</button>
                  <button className="btn-sm" style={{ background: '#6366f1', color: '#fff', marginRight: 4 }} onClick={() => setPwdModal(user)}>{t('usr.pwd')}</button>
                  <button className="btn-sm" style={{ background: '#0ea5e9', color: '#fff', marginRight: 4 }} onClick={() => setAutoResetModal(user)}>{t('usr.autoResetBtn')}</button>
                  {user.permanent_lock && (
                    <button className="btn-sm" style={{ background: '#f59e0b', color: '#fff', marginRight: 4 }} onClick={() => unlock(user.id)}>{t('usr.unlock')}</button>
                  )}
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
            <div className="modal-icon-hdr modal-icon-hdr--user">
              <div className="modal-icon-hdr-badge">
                {modal === 'add' ? <UserPlus size={20} /> : <UserCog size={20} />}
              </div>
              <h3>{modal === 'add' ? t('usr.addTitle') : t('usr.editTitle')}</h3>
            </div>
            <div className="form-grid">
              <label>
                <span>{t('usr.formUsername')} <span className="req-star">*</span></span>
                <input value={form.username} disabled={modal !== 'add'}
                  onChange={(e) => setForm({ ...form, username: e.target.value })} />
              </label>
              {modal === 'add' && (
                <label>
                  <span>{t('usr.formPassword')} <span className="req-star">*</span></span>
                  <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
                </label>
              )}
              <label>{t('usr.formDisplay')}
                <input value={form.display_name} onChange={(e) => setForm({ ...form, display_name: e.target.value })} />
              </label>
              <label>
                <span>{t('usr.formEmail')} <span className="req-star">*</span></span>
                <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
              </label>
              <label>{t('usr.formEmployeeId')}
                <input value={form.employee_id} onChange={(e) => setForm({ ...form, employee_id: e.target.value })} />
              </label>
              <label>{t('usr.formRole')}
                <SearchableSelect
                  value={form.system_role}
                  onChange={v => setForm({ ...form, system_role: v })}
                  options={[
                    { value: 'USER',  label: 'USER' },
                    { value: 'AUDIT', label: 'AUDIT' },
                    { value: 'ADMIN', label: 'ADMIN' },
                  ]}
                />
              </label>
              <label>{t('usr.orgRole')}
                <SearchableSelect
                  value={form.org_role}
                  onChange={v => setForm({ ...form, org_role: v })}
                  options={[
                    { value: '',        label: t('usr.orgRoleNone') },
                    { value: 'TECH',    label: 'Tech' },
                    { value: 'PO',      label: 'Product Owner (PO)' },
                    { value: 'MANAGER', label: 'Manager' },
                    { value: 'CLEVEL',  label: 'C-Level' },
                  ]}
                />
              </label>
              <label>
                <span>{t('usr.formTeam')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.team_id}
                  onChange={v => setForm({ ...form, team_id: v ? Number(v) : '' })}
                  placeholder={t('usr.noTeam')}
                  options={[
                    { value: '', label: t('usr.noTeam') },
                    ...(teams || []).map(team => ({ value: team.id, label: team.name })),
                  ]}
                />
                {!form.team_id && (
                  <span className="field-hint field-hint--warn">{t('usr.teamRequired')}</span>
                )}
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('usr.formActive')}
              </label>
            </div>
            {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
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
        <PasswordChangeModal
          mode="admin-reset"
          targetUser={pwdModal}
          onClose={() => setPwdModal(null)}
          onSuccess={() => setMsg(t('usr.pwdChanged'))}
        />
      )}

      {autoResetModal && (
        <AdminAutoResetModal
          targetUser={autoResetModal}
          onClose={() => setAutoResetModal(null)}
          onSuccess={(emailStatus) => setMsg(
            emailStatus?.startsWith('SENT') ? t('usr.autoResetSent') : t('usr.autoResetFailed')
          )}
        />
      )}
    </div>
  )
}
