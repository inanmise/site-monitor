import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'

const ROLES  = ['PO', 'TECH', 'MANAGER', 'CLEVEL']
const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
const levelColor = { WARNING: '#f0a500', HIGH: '#e07b00', CRITICAL: '#c0392b' }
const emptyContact = { name: '', email: '', role: 'TECH', minAlertLevel: 'WARNING', webhookUrl: '', webhookType: 'TEAMS', active: true, team_id: '' }

export default function EscalationContacts({ teams = [], isAdmin = false }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [contacts, setContacts] = useState([])
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyContact)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  const teamMap = Object.fromEntries(teams.map(t => [t.id, t.name]))

  const roleLabelMap = {
    PO: t('ec.role.po'), TECH: t('ec.role.tech'), MANAGER: t('ec.role.manager'), CLEVEL: t('ec.role.clevel'),
  }
  const levelLabelMap = {
    WARNING: t('ec.level.warning'), HIGH: t('ec.level.high'), CRITICAL: t('ec.level.critical'),
  }

  useEffect(() => { load() }, [])

  async function load() {
    const res = await api.admin.getContacts()
    if (res?.success) setContacts(res.data)
  }

  function openAdd() { setForm({ ...emptyContact, team_id: teams[0]?.id ?? '' }); setModal('add') }
  function openEdit(c) {
    setForm({
      ...c,
      minAlertLevel: c.min_alert_level || 'WARNING',
      webhookUrl: c.webhook_url || '',
      webhookType: c.webhook_type || 'TEAMS',
      team_id: c.team_id ?? '',
    })
    setModal(c)
  }

  async function save() {
    setSaving(true)
    const payload = {
      name: form.name.trim(),
      email: form.email.trim(),
      role: form.role,
      minAlertLevel: form.minAlertLevel,
      webhookUrl: form.webhookUrl || null,
      webhookType: form.webhookType || null,
      active: form.active,
      teamId: form.team_id ? Number(form.team_id) : null,
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
              <label>{t('ec.formName')}<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
              <label>{t('ec.formEmail')}<input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
              {isAdmin && teams.length > 0 && (
                <label>{t('ec.formTeam')}
                  <select value={form.team_id} onChange={(e) => setForm({ ...form, team_id: e.target.value })}>
                    {teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
                  </select>
                </label>
              )}
              <label>{t('ec.formRole')}
                <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
                  {ROLES.map((r) => <option key={r} value={r}>{roleLabelMap[r]}</option>)}
                </select>
              </label>
              <label>{t('ec.formLevel')}
                <select value={form.minAlertLevel} onChange={(e) => setForm({ ...form, minAlertLevel: e.target.value })}>
                  <option value="WARNING">{t('ec.levelWarn')}</option>
                  <option value="HIGH">{t('ec.levelHigh')}</option>
                  <option value="CRITICAL">{t('ec.levelCrit')}</option>
                </select>
              </label>
              <label>{t('ec.formWebhook')}<input value={form.webhookUrl} onChange={(e) => setForm({ ...form, webhookUrl: e.target.value })} placeholder="https://..." /></label>
              <label>{t('ec.formWebhookType')}
                <select value={form.webhookType} onChange={(e) => setForm({ ...form, webhookType: e.target.value })}>
                  <option value="TEAMS">Microsoft Teams</option>
                  <option value="SLACK">Slack</option>
                </select>
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('ec.formActive')}
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('ec.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.name || !form.email}>
                {saving ? t('ec.saving') : t('ec.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
