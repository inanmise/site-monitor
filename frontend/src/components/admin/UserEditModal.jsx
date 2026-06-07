import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { UserCog } from 'lucide-react'

/**
 * Edit-only user modal — reused by TeamManager member cards so that an admin
 * can update a user without leaving the Teams tab. Mirrors the edit branch of
 * UserManager.jsx; for "add" use UserManager directly.
 */
export default function UserEditModal({ user, teams, onClose, onSaved }) {
  const t = useT()
  const [form, setForm] = useState(toForm(user))
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => { setForm(toForm(user)); setMsg(null) }, [user])

  if (!user) return null

  async function save() {
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      setMsg(t('usr.emailInvalid'))
      return
    }
    setSaving(true)
    const payload = {
      username:     form.username.trim(),
      display_name: form.display_name,
      email:        form.email,
      employee_id:  form.employee_id,
      system_role:  form.system_role,
      team_id:      form.team_id || null,
      org_role:     form.org_role || null,
      active:       form.active,
    }
    const res = await api.admin.updateUser(user.id, payload)
    setSaving(false)
    if (res?.success) {
      onSaved?.(res.data ?? null)
      onClose?.()
    } else {
      setMsg(res?.error || 'Error')
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--user">
          <div className="modal-icon-hdr-badge"><UserCog size={20} /></div>
          <h3>{t('usr.editTitle')}</h3>
        </div>
        <div className="form-grid">
          <label>{t('usr.formUsername')}
            <input value={form.username} disabled />
          </label>
          <label>{t('usr.formDisplay')}
            <input value={form.display_name}
              onChange={(e) => setForm({ ...form, display_name: e.target.value })} />
          </label>
          <label>
            <span>{t('usr.formEmail')} <span className="req-star">*</span></span>
            <input type="email" value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })} />
          </label>
          <label>{t('usr.formEmployeeId')}
            <input value={form.employee_id}
              onChange={(e) => setForm({ ...form, employee_id: e.target.value })} />
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
            <input type="checkbox" checked={form.active}
              onChange={(e) => setForm({ ...form, active: e.target.checked })} />
            {t('usr.formActive')}
          </label>
        </div>
        {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('usr.cancel')}</button>
          <button className="btn btn-primary" onClick={save}
            disabled={saving || !form.username.trim() || !form.email.trim() || !form.team_id}>
            {saving ? t('usr.saving') : t('usr.save')}
          </button>
        </div>
      </div>
    </div>
  )
}

function toForm(user) {
  return {
    username:     user?.username || '',
    display_name: user?.display_name || '',
    email:        user?.email || '',
    employee_id:  user?.employee_id || '',
    system_role:  user?.system_role || 'USER',
    team_id:      user?.team_id ?? '',
    org_role:     user?.org_role || '',
    active:       user?.active !== false,
  }
}
