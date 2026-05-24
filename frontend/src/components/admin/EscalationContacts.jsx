import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'

const ROLES  = ['PO', 'TECH', 'MANAGER', 'CLEVEL']
const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
const levelColor = { WARNING: '#f0a500', HIGH: '#e07b00', CRITICAL: '#c0392b' }
const emptyContact = { user_id: '', role: 'TECH', min_alert_level: 'WARNING', webhook_url: '', webhook_type: 'TEAMS', active: true, team_id: '' }

export default function EscalationContacts({ teams = [], isAdmin = false }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [contacts, setContacts] = useState([])
  const [users, setUsers]       = useState([])
  const [modal, setModal]   = useState(null)
  const [form, setForm]     = useState(emptyContact)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg]       = useState(null)

  const teamMap = Object.fromEntries(teams.map(t => [t.id, t.name]))
  const userMap = Object.fromEntries(users.map(u => [String(u.id), u]))

  const roleLabelMap = {
    PO: t('ec.role.po'), TECH: t('ec.role.tech'), MANAGER: t('ec.role.manager'), CLEVEL: t('ec.role.clevel'),
  }
  const levelLabelMap = {
    WARNING: t('ec.level.warning'), HIGH: t('ec.level.high'), CRITICAL: t('ec.level.critical'),
  }

  useEffect(() => { load(); loadUsers() }, [])

  async function load() {
    const res = await api.admin.getContacts()
    if (res?.success) setContacts(res.data)
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers(res.data.filter(u => u.active))
  }

  function openAdd() {
    setForm({ ...emptyContact, team_id: teams[0]?.id ?? '' })
    setModal('add')
  }
  function openEdit(c) {
    setForm({
      user_id: c.user_id ? String(c.user_id) : '',
      role: c.role || 'TECH',
      min_alert_level: c.min_alert_level || 'WARNING',
      webhook_url: c.webhook_url || '',
      webhook_type: c.webhook_type || 'TEAMS',
      active: c.active !== false,
      team_id: c.team_id ?? '',
    })
    setModal(c)
  }

  async function save() {
    setSaving(true)
    const payload = {
      user_id: form.user_id ? Number(form.user_id) : null,
      role: form.role,
      min_alert_level: form.min_alert_level,
      webhook_url: form.webhook_url || null,
      webhook_type: form.webhook_type || null,
      active: form.active,
      team_id: form.team_id ? Number(form.team_id) : null,
    }
    const res = modal === 'add'
      ? await api.admin.addContact(payload)
      : await api.admin.updateContact(modal.id, payload)
    setSaving(false)
    if (res?.success) { setModal(null); setMsg(t('ec.saved')); load() }
    else setMsg(res?.error || 'Error')
  }

  async function del(id) {
    const contact = contacts.find(c => c.id === id)
    const ok = await showConfirm({
      title: t('ec.deleteTitle'),
      message: t('ec.deleteMsg', contact?.name ?? id),
      variant: 'danger',
      confirmText: t('ec.deleteConfirm'),
      cancelText: t('ec.deleteCancel'),
    })
    if (!ok) return
    await api.admin.deleteContact(id)
    load()
  }

  const selectedUser = form.user_id ? userMap[form.user_id] : null
  const canSave = !!form.user_id

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <div>
          <h3>{t('ec.title')}</h3>
          <p className="section-desc">{t('ec.desc')}</p>
          <div className="escalation-legend">
            <span>{t('ec.legendWarn')}</span>
            <span>{t('ec.legendHigh')}</span>
            <span>{t('ec.legendCrit')}</span>
          </div>
        </div>
        <button className="btn btn-success" onClick={openAdd}>{t('ec.addBtn')}</button>
      </div>
      {msg && <div className="alert-msg">{msg}</div>}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('ec.colName')}</th>
              <th>{t('ec.colEmail')}</th>
              <th>{t('ec.colTeam')}</th>
              <th>{t('ec.colRole')}</th>
              <th>{t('ec.colLevel')}</th>
              <th>{t('ec.colWebhook')}</th>
              <th>{t('ec.colActive')}</th>
              <th>{t('ec.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {contacts.map((c) => (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td>{c.email}</td>
                <td>{teamMap[c.team_id] || '—'}</td>
                <td><span className="role-badge">{roleLabelMap[c.role] || c.role}</span></td>
                <td><span className="level-badge" style={{ background: levelColor[c.min_alert_level] || '#999' }}>{levelLabelMap[c.min_alert_level] || c.min_alert_level}</span></td>
                <td>{c.webhook_url ? <span className="badge badge-ok">{c.webhook_type}</span> : '—'}</td>
                <td><span className={c.active ? 'badge badge-ok' : 'badge badge-err'}>{c.active ? t('ec.active') : t('ec.inactive')}</span></td>
                <td>
                  <button className="btn-sm btn-edit" onClick={() => openEdit(c)}>{t('ec.edit')}</button>
                  <button className="btn-sm btn-del" onClick={() => del(c.id)}>{t('ec.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <h3>{modal === 'add' ? t('ec.addTitle') : t('ec.editTitle')}</h3>
            <div className="form-grid">
              <label>
                <span>{t('contact.user')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.user_id}
                  onChange={v => setForm({ ...form, user_id: v })}
                  placeholder={t('contact.selectUser')}
                  options={[
                    { value: '', label: t('contact.selectUser') },
                    ...users.map(u => ({
                      value: String(u.id),
                      label: `${u.display_name || u.username} (${u.email || u.username})`,
                    })),
                  ]}
                />
                {selectedUser && (
                  <span className="field-hint">
                    {selectedUser.display_name || selectedUser.username} — {selectedUser.email}
                  </span>
                )}
                {users.length === 0 && (
                  <span className="field-hint field-hint--warn">{t('team.noUsersHint')}</span>
                )}
              </label>
              {isAdmin && teams.length > 0 && (
                <label>{t('ec.formTeam')}
                  <SearchableSelect
                    value={form.team_id}
                    onChange={v => setForm({ ...form, team_id: v })}
                    options={teams.map(team => ({ value: team.id, label: team.name }))}
                  />
                </label>
              )}
              <label>{t('ec.formRole')}
                <SearchableSelect
                  value={form.role}
                  onChange={v => setForm({ ...form, role: v })}
                  options={ROLES.map(r => ({ value: r, label: roleLabelMap[r] }))}
                />
              </label>
              <label>{t('ec.formLevel')}
                <SearchableSelect
                  value={form.min_alert_level}
                  onChange={v => setForm({ ...form, min_alert_level: v })}
                  options={[
                    { value: 'WARNING',  label: t('ec.levelWarn') },
                    { value: 'HIGH',     label: t('ec.levelHigh') },
                    { value: 'CRITICAL', label: t('ec.levelCrit') },
                  ]}
                />
              </label>
              <label>{t('ec.formWebhook')}<input value={form.webhook_url} onChange={(e) => setForm({ ...form, webhook_url: e.target.value })} placeholder="https://..." /></label>
              <label>{t('ec.formWebhookType')}
                <SearchableSelect
                  value={form.webhook_type}
                  onChange={v => setForm({ ...form, webhook_type: v })}
                  options={[
                    { value: 'TEAMS', label: 'Microsoft Teams' },
                    { value: 'SLACK', label: 'Slack' },
                  ]}
                />
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('ec.formActive')}
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('ec.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !canSave}>
                {saving ? t('ec.saving') : t('ec.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
