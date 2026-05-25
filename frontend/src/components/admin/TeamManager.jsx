import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { UsersRound, PenLine } from 'lucide-react'

const emptyTeam = { name: '', email: '', description: '', active: true, leader_id: '', team_type: '' }

const ORG_ROLE_COLORS = { PO: '#2563eb', MANAGER: '#d97706', CLEVEL: '#dc2626', TECH: '#16a34a' }

export default function TeamManager({ onTeamsChange }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [teams, setTeams]   = useState([])
  const [users, setUsers]   = useState([])
  const [modal, setModal]   = useState(null)
  const [form, setForm]     = useState(emptyTeam)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg]       = useState(null)
  const [expandedId, setExpandedId]     = useState(null)
  const [membersCache, setMembersCache] = useState({})
  const [membersLoading, setMembersLoading] = useState(false)

  useEffect(() => { load(); loadUsers() }, [])

  async function load() {
    const res = await api.admin.getTeams()
    if (res?.success) setTeams(res.data)
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers(res.data.filter(u => u.active))
  }

  async function toggleExpand(teamId) {
    if (expandedId === teamId) { setExpandedId(null); return }
    setExpandedId(teamId)
    if (!membersCache[teamId]) {
      setMembersLoading(true)
      const res = await api.admin.getTeamUsers(teamId)
      if (res?.success) setMembersCache(prev => ({ ...prev, [teamId]: res.data }))
      setMembersLoading(false)
    }
  }

  const userMap = Object.fromEntries(users.map(u => [u.id, u.display_name || u.username]))

  function openAdd() { setForm(emptyTeam); setModal('add'); setMsg(null) }
  function openEdit(team) {
    setForm({ ...team, email: team.email || '', leader_id: String(team.leaderId ?? team.leader_id ?? ''), team_type: team.team_type ?? '' })
    setModal(team)
    setMsg(null)
  }
  function closeModal() { setModal(null); setMsg(null) }

  async function save() {
    setMsg(null)
    if (!form.leader_id) { setMsg(t('team.leaderRequired')); return }
    if (!form.team_type) { setMsg(t('team.typeRequired')); return }
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      setMsg(t('team.emailInvalid'))
      return
    }
    setSaving(true)
    const payload = {
      name: form.name.trim(),
      email: form.email.trim(),
      description: form.description,
      active: form.active,
      leader_id: Number(form.leader_id),
      team_type: form.team_type,
    }
    const isAdd = modal === 'add'
    const editedId = isAdd ? null : modal.id
    const res = isAdd
      ? await api.admin.createTeam(payload)
      : await api.admin.updateTeam(editedId, payload)
    setSaving(false)
    if (res?.success) {
      setMsg('✓ ' + t('team.saved'))
      load(); onTeamsChange?.()
      if (!isAdd) setMembersCache(prev => { const n = { ...prev }; delete n[editedId]; return n })
      setTimeout(() => closeModal(), 1800)
    } else {
      setMsg(res?.error || 'Error')
    }
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
    const res = await api.admin.deleteTeam(id)
    if (!res?.success) {
      setMsg(res?.error || t('team.deleteError'))
      return
    }
    setMembersCache(prev => { const n = { ...prev }; delete n[id]; return n })
    if (expandedId === id) setExpandedId(null)
    load()
    onTeamsChange?.()
  }

  const canSave = form.name.trim() && form.email.trim() && form.leader_id && form.team_type

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('team.title')}</h3>
        <button className="btn btn-success" onClick={openAdd}>{t('team.addBtn')}</button>
      </div>
      {msg && !modal && <div className={`alert-msg${msg.startsWith('✓') ? '' : ' alert-msg--err'}`}>{msg}</div>}
      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('team.colName')}</th>
              <th>{t('team.colType')}</th>
              <th>{t('team.colEmail')}</th>
              <th>{t('team.colLeader')}</th>
              <th>{t('team.colDesc')}</th>
              <th>{t('team.colActive')}</th>
              <th>{t('team.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {teams.map((team) => (
              <>
                <tr key={team.id}>
                  <td>
                    <button
                      className="team-expand-btn"
                      onClick={() => toggleExpand(team.id)}
                      title={expandedId === team.id ? t('team.collapseMembers') : t('team.expandMembers')}
                    >
                      {expandedId === team.id ? '▼' : '▶'}
                    </button>
                    <strong>{team.name}</strong>
                  </td>
                  <td>
                    {team.team_type
                      ? <span className={`badge badge-team-type badge-team-type-${team.team_type.toLowerCase()}`}>{team.team_type}</span>
                      : <span style={{ color: 'var(--text-light)', fontSize: '.8em' }}>—</span>}
                  </td>
                  <td>{team.email || '—'}</td>
                  <td>{userMap[team.leader_id] ?? <span style={{ color: 'var(--danger)' }}>{t('team.noLeader')}</span>}</td>
                  <td>{team.description || '—'}</td>
                  <td><span className={team.active ? 'badge badge-ok' : 'badge badge-err'}>{team.active ? t('team.active') : t('team.inactive')}</span></td>
                  <td>
                    <button className="btn-sm btn-edit" onClick={() => openEdit(team)}>{t('team.edit')}</button>
                    <button className="btn-sm btn-del" onClick={() => del(team.id)}>{t('team.delete')}</button>
                  </td>
                </tr>
                {expandedId === team.id && (
                  <tr key={`${team.id}-members`} className="team-members-row">
                    <td colSpan={7}>
                      {membersLoading && !membersCache[team.id]
                        ? <span className="field-hint">{t('team.loadingMembers')}</span>
                        : (() => {
                            const members = membersCache[team.id] || []
                            return members.length === 0
                              ? <span className="field-hint">{t('team.noMembers')}</span>
                              : (
                                <div className="team-members-list">
                                  {members.map(m => (
                                    <span key={m.id} className="team-member-chip">
                                      {m.display_name || m.username}
                                      {m.org_role && (
                                        <span
                                          className={`badge-role badge-role-${m.org_role}`}
                                          style={{ marginLeft: 6 }}
                                        >
                                          {m.org_role}
                                        </span>
                                      )}
                                    </span>
                                  ))}
                                </div>
                              )
                          })()
                      }
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      </div>

      {modal !== null && (
        <div className="modal-overlay" onClick={closeModal}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <div className="modal-icon-hdr modal-icon-hdr--team">
              <div className="modal-icon-hdr-badge">
                {modal === 'add' ? <UsersRound size={20} /> : <PenLine size={20} />}
              </div>
              <h3>{modal === 'add' ? t('team.addTitle') : t('team.editTitle')}</h3>
            </div>
            <div className="form-grid">
              <label>
                <span>{t('team.formName')} <span className="req-star">*</span></span>
                <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={t('team.formNamePh')} />
              </label>
              <label>
                <span>{t('team.formEmail')} <span className="req-star">*</span></span>
                <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="team@example.com" />
              </label>
              <label>
                <span>{t('team.formLeader')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.leader_id}
                  onChange={v => setForm({ ...form, leader_id: v })}
                  placeholder={t('team.selectLeader')}
                  options={[
                    { value: '', label: t('team.selectLeader') },
                    ...users.map(u => ({ value: u.id, label: `${u.display_name || u.username} (${u.username})` })),
                  ]}
                />
                {users.length === 0 && (
                  <span className="field-hint field-hint--warn">{t('team.noUsersHint')}</span>
                )}
              </label>
              <label>
                <span>{t('team.formType')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.team_type}
                  onChange={v => setForm({ ...form, team_type: v })}
                  options={[
                    { value: '', label: t('team.selectType') },
                    { value: 'SY', label: t('team.typeSy') },
                    { value: 'UG', label: t('team.typeUg') },
                  ]}
                />
              </label>
              <label className="full-width">{t('team.formDesc')}
                <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('team.formActive')}
              </label>
            </div>
            {msg && (
              <div className={`alert-msg${msg.startsWith('✓') ? '' : ' alert-msg--err'}`} style={{ marginTop: 8 }}>
                {msg}
              </div>
            )}
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={closeModal}>{t('team.cancel')}</button>
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
