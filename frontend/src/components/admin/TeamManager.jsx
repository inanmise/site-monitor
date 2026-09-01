import { useState, useEffect, useMemo, Fragment } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { UsersRound, PenLine } from 'lucide-react'
import UserEditModal from './UserEditModal.jsx'

// Haftalık e-postalar opt-in: YENİ takım ikisi de kapalı doğar (backend de createTeam'de false yazar).
const emptyTeam = { name: '', email: '', description: '', active: true, leader_id: '',
  weekly_reminder_enabled: false, weekly_availability_enabled: false }

/** Sunucu SNAKE_CASE döndürür; camelCase varyantı da savunma amaçlı okunur (openEdit'teki leader_id deseni). */
function weeklyFlag(team, which) {
  return which === 'reminder'
    ? !!(team?.weekly_reminder_enabled ?? team?.weeklyReminderEnabled)
    : !!(team?.weekly_availability_enabled ?? team?.weeklyAvailabilityEnabled)
}

/** Takım satırındaki/formundaki haftalık e-posta anahtarı — PermissionMatrix'teki pill switch'in aynısı. */
function WeeklyPill({ on, disabled, onToggle, label }) {
  return (
    <button type="button" role="switch" aria-checked={!!on} aria-label={label} title={label}
      disabled={disabled}
      className={`perm-pill ${on ? 'perm-pill-on' : 'perm-pill-off'}`}
      onClick={onToggle}>
      <span className="perm-pill-knob" />
    </button>
  )
}


function computeInitials(name) {
  if (!name) return '?'
  const parts = String(name).trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
  'linear-gradient(135deg, #0ea5e9 0%, #06b6d4 100%)',
  'linear-gradient(135deg, #10b981 0%, #14b8a6 100%)',
  'linear-gradient(135deg, #f59e0b 0%, #ef4444 100%)',
  'linear-gradient(135deg, #ec4899 0%, #a855f7 100%)',
]
function avatarStyleFor(seed) {
  const s = String(seed || '')
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0
  return { background: AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length], color: '#fff' }
}

/** Ad + Soyad baş harfleri (AD'den); yoksa display_name'e düşer. Türkçe-uyumlu büyütme. */
function adSoyadInitials(m) {
  const fn = (m.first_name || '').trim()
  const ln = (m.last_name || '').trim()
  if (fn || ln) {
    const ii = ((fn[0] || '') + (ln[0] || '')).toLocaleUpperCase('tr-TR')
    if (ii) return ii
  }
  return computeInitials(m.display_name || m.username)
}

/** Üye kartı avatarı: LDAP fotoğrafı + altında Ad/Soyad baş harfleri; foto yoksa baş harf rozeti. */
function MemberAvatar({ m }) {
  const [err, setErr] = useState(false)
  const initials = adSoyadInitials(m)
  if (err) {
    return (
      <div className="tm-mc-avatar-wrap">
        <div className="tm-mc-avatar" style={avatarStyleFor(m.username || m.display_name || String(m.id))}>{initials}</div>
      </div>
    )
  }
  return (
    <div className="tm-mc-avatar-wrap">
      {/* Takımlar sekmesi USER rolüne de AÇIK (AdminPanel adminOnly:false) → avatar admin'e özel
          uçtan çekilemez: her üye için bir ACCESS_DENIED/BLOCKED denetim kaydı üretiyordu.
          /api/users/{id}/photo tam bu iş için var (oturum açmış herkes; UserDirectoryController). */}
      <img className="tm-mc-photo" alt="" src={`/api/users/${m.id}/photo`} onError={() => setErr(true)} />
      <span className="tm-mc-initials">{initials}</span>
    </div>
  )
}

export default function TeamManager({ systemRole, ownTeamId, myTeamIds, onTeamsChange }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  const canEditRow = (rowTeamId) => isAdmin || (isTeamAdmin && rowTeamId === ownTeamId)
  // Haftalık e-posta anahtarlarını takımın HER üyesi çevirebilir (yalnız kendi takımı için).
  // Üyelik listesi oturumdan gelir; sunucu tarafında ayrıca app_users üzerinden doğrulanır.
  const memberOf = useMemo(() => {
    const ids = Array.isArray(myTeamIds) ? myTeamIds.map(Number) : []
    if (ownTeamId != null && !ids.includes(Number(ownTeamId))) ids.push(Number(ownTeamId))
    return new Set(ids)
  }, [myTeamIds, ownTeamId])
  const canToggleWeekly = (rowTeamId) => isAdmin || memberOf.has(Number(rowTeamId))
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
  const [editingUser, setEditingUser]   = useState(null)

  // İstemci-taraflı filtre + sayfalama (getTeams tüm listeyi döndürür — dropdown kaynağı bozulmasın)
  const [q, setQ]       = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const filteredTeams = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return teams.filter(tm =>
      (!needle || (tm.name || '').toLowerCase().includes(needle) || (tm.email || '').toLowerCase().includes(needle)))
  }, [teams, q])

  const totalPages = Math.max(1, Math.ceil(filteredTeams.length / size))
  const safePage = Math.min(page, totalPages - 1)
  const pagedTeams = filteredTeams.slice(safePage * size, safePage * size + size)

  useEffect(() => { setPage(0) }, [q, size])

  useEffect(() => { load(); loadUsers() }, [])

  async function load() {
    const res = await api.admin.getTeams()
    if (res?.success) setTeams(res.data)
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers((res.data ?? []).filter(u => u.active))   // data null gelirse ekran cokmesin
  }

  async function toggleExpand(teamId) {
    if (expandedId === teamId) { setExpandedId(null); return }
    setExpandedId(teamId)
    if (!membersCache[teamId]) {
      setMembersLoading(true)
      try {
        const res = await api.admin.getTeamUsers(teamId)
        if (res?.success) setMembersCache(prev => ({ ...prev, [teamId]: res.data }))
      } finally {
        setMembersLoading(false)
      }
    }
  }

  async function reloadMembers(teamId) {
    if (!teamId) return
    const res = await api.admin.getTeamUsers(teamId)
    if (res?.success) setMembersCache(prev => ({ ...prev, [teamId]: res.data }))
  }

  const userMap = Object.fromEntries(users.map(u => [u.id, u.display_name || u.username]))
  const usersById = Object.fromEntries(users.map(u => [u.id, u]))

  /** Bir kullanıcının bağlı olduğu müdür etiketi: adı (çözülebiliyorsa) yoksa sicili. */
  const managerLabelFor = (u) => (u?.manager_id && userMap[u.manager_id]) || u?.manager_sicil || null

  /** Takımın müdürü: o takımdaki kullanıcıların bağlı olduğu müdür(ler)in birleşimi. */
  const teamManagerLabel = (teamId) => {
    const labels = new Set()
    users.forEach(u => { if (u.team_id === teamId) { const l = managerLabelFor(u); if (l) labels.add(l) } })
    return labels.size ? [...labels].join(', ') : null
  }

  function openAdd() { setForm(emptyTeam); setModal('add'); setMsg(null) }
  function openEdit(team) {
    setForm({ ...team, email: team.email || '', leader_id: String(team.leaderId ?? team.leader_id ?? ''),
      weekly_reminder_enabled: weeklyFlag(team, 'reminder'),
      weekly_availability_enabled: weeklyFlag(team, 'availability') })
    setModal(team)
    setMsg(null)
  }
  function closeModal() { setModal(null); setMsg(null) }

  async function save() {
    setMsg(null)
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      setMsg(t('team.emailInvalid'))
      return
    }
    setSaving(true)
    try {
      const payload = {
        name: form.name.trim(),
        email: form.email.trim(),
        description: form.description,
        active: form.active,
        leader_id: form.leader_id ? Number(form.leader_id) : null,  // PO optional
        weekly_reminder_enabled: !!form.weekly_reminder_enabled,
        weekly_availability_enabled: !!form.weekly_availability_enabled,
      }
      const isAdd = modal === 'add'
      const editedId = isAdd ? null : modal.id
      const res = isAdd
        ? await api.admin.createTeam(payload)
        : await api.admin.updateTeam(editedId, payload)
      if (res?.success) {
        toast.success(t('team.saved'))
        load(); onTeamsChange?.()
        if (!isAdd) setMembersCache(prev => { const n = { ...prev }; delete n[editedId]; return n })
        closeModal()
      } else {
        setMsg(res?.error || 'Error')
      }
    } finally {
      setSaving(false)
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
      toast.error(res?.error || t('team.deleteError'))
      return
    }
    toast.success(t('team.deleted'))
    setMembersCache(prev => { const n = { ...prev }; delete n[id]; return n })
    if (expandedId === id) setExpandedId(null)
    load()
    onTeamsChange?.()
  }

  /** Satırdaki bir haftalık anahtarı çevirir — ad/e-posta/aktifliğe DOKUNMAYAN dar uç. */
  async function toggleWeekly(team, which) {
    const key = which === 'reminder' ? 'weekly_reminder_enabled' : 'weekly_availability_enabled'
    const next = !weeklyFlag(team, which)
    // İyimser güncelleme: anahtar anında dönsün, hata olursa geri alınır.
    setTeams(prev => prev.map(x => (x.id === team.id ? { ...x, [key]: next } : x)))
    const res = await api.admin.updateTeamWeeklyNotifications(team.id, { [key]: next })
    if (res?.success) {
      toast.success(t('team.weeklySaved'))
      onTeamsChange?.()
    } else {
      setTeams(prev => prev.map(x => (x.id === team.id ? { ...x, [key]: !next } : x)))
      toast.error(res?.error || t('team.weeklySaveError'))
    }
  }

  const canSave = form.name.trim() && form.email.trim()

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('team.title')}</h3>
        {isAdmin && <button className="btn btn-success" onClick={openAdd}>{t('team.addBtn')}</button>}
      </div>
      {msg && !modal && <div className={`alert-msg${msg.startsWith('✓') ? '' : ' alert-msg--err'}`}>{msg}</div>}

      {/* Filtre çubuğu — ad/e-posta araması */}
      <div className="audit-filters">
        <input className="audit-filter-input" placeholder={t('team.searchPlaceholder')}
          value={q} onChange={(e) => setQ(e.target.value)} />
      </div>

      <div className="admin-table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th>{t('team.colName')}</th>
              <th>{t('team.colEmail')}</th>
              <th>{t('team.colLeader')}</th>
              <th>{t('team.colManager')}</th>
              <th>{t('team.colActive')}</th>
              <th>{t('team.colWeeklyEmails')}</th>
              <th>{t('team.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {filteredTeams.length === 0 && (
              <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 18 }}>
                {t('team.noResults')}
              </td></tr>
            )}
            {pagedTeams.map((team) => (
              <Fragment key={team.id}>
                <tr>
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
                  <td>{team.email || '—'}</td>
                  <td>{userMap[team.leader_id] ?? <span style={{ color: 'var(--danger)' }}>{t('team.noLeader')}</span>}</td>
                  <td>{teamManagerLabel(team.id) || '—'}</td>
                  <td><span className={team.active ? 'badge badge-ok' : 'badge badge-err'}>{team.active ? t('team.active') : t('team.inactive')}</span></td>
                  <td>
                    <div className="tm-weekly-cell">
                      <span className="tm-weekly-item">
                        <WeeklyPill on={weeklyFlag(team, 'reminder')} disabled={!canToggleWeekly(team.id)}
                          label={t('team.weeklyReminder')} onToggle={() => toggleWeekly(team, 'reminder')} />
                        <span className="tm-weekly-label">{t('team.weeklyReminderShort')}</span>
                      </span>
                      <span className="tm-weekly-item">
                        <WeeklyPill on={weeklyFlag(team, 'availability')} disabled={!canToggleWeekly(team.id)}
                          label={t('team.weeklyAvailability')} onToggle={() => toggleWeekly(team, 'availability')} />
                        <span className="tm-weekly-label">{t('team.weeklyAvailabilityShort')}</span>
                      </span>
                    </div>
                  </td>
                  <td>
                    <KebabMenu label={t('team.colActions')} items={[
                      { label: t('team.edit'), onClick: () => openEdit(team), hidden: !canEditRow(team.id) },
                      { label: t('team.delete'), danger: true, onClick: () => del(team.id), hidden: !isAdmin },
                    ]} />
                  </td>
                </tr>
                {expandedId === team.id && (
                  <tr key={`${team.id}-members`} className="team-members-row">
                    <td colSpan={7}>
                      {membersLoading && !membersCache[team.id]
                        ? <span className="field-hint">{t('team.loadingMembers')}</span>
                        : (() => {
                            const members = membersCache[team.id] || []
                            if (members.length === 0)
                              return <span className="field-hint">{t('team.noMembers')}</span>
                            // Üyelerin yönetim zincirini (müdür + müdürün müdürü/bölüm başkanı) kart olarak
                            // listele — üyeden 2 seviye yukarı özyinele. Bölüm başkanının takımı olmasa da görünür.
                            const memberIds = new Set(members.map(x => x.id))
                            const MANAGER_LEVELS = 2
                            const managerIds = new Set()
                            members.forEach(member => {
                              let cur = member
                              for (let lvl = 0; lvl < MANAGER_LEVELS; lvl++) {
                                const mid = cur?.manager_id
                                if (!mid) break
                                const mgr = usersById[mid]
                                if (!mgr) break
                                if (!memberIds.has(mid)) managerIds.add(mid)
                                cur = mgr
                              }
                            })
                            const managerCards = [...managerIds].map(id => usersById[id]).filter(Boolean)
                            // Sıralama: önce müdür kartı, sonra MANAGER rolü, sonra PO, sonra
                            // seviye (companyLevel) büyükten küçüğe (sayı-duyarlı), sonra ada göre
                            const rankOf = (c) => c.isManager ? 0
                              : (c.m.org_role === 'MANAGER' ? 1
                              : (c.m.org_role === 'PO' ? 2 : 3))
                            const cards = [
                              ...members.map(m => ({ m, isManager: false })),
                              ...managerCards.map(m => ({ m, isManager: true })),
                            ].sort((a, b) => {
                              if (rankOf(a) !== rankOf(b)) return rankOf(a) - rankOf(b)
                              const la = (a.m.company_level || '').toLowerCase()
                              const lb = (b.m.company_level || '').toLowerCase()
                              if (la !== lb) return lb.localeCompare(la, 'tr', { numeric: true })
                              return (a.m.display_name || a.m.username || '').localeCompare(
                                b.m.display_name || b.m.username || '', 'tr')
                            })
                            return (
                                <div className="tm-member-cards">
                                  {cards.map(({ m, isManager }) => {
                                    return (
                                      <div
                                        key={m.id}
                                        className={`tm-member-card${canManage ? ' tm-member-card-clickable' : ''}`}
                                        role={canManage ? 'button' : undefined}
                                        tabIndex={canManage ? 0 : undefined}
                                        onClick={canManage ? () => setEditingUser(m) : undefined}
                                        onKeyDown={canManage ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setEditingUser(m) } } : undefined}
                                        title={canManage ? t('usr.editTitle') : undefined}
                                      >
                                        <MemberAvatar m={m} />
                                        <div className="tm-mc-body">
                                          <strong className="tm-mc-name">
                                            {m.display_name || m.username}
                                            {isManager && <span className="tm-mc-mgr-badge">{t('team.managerBadge')}</span>}
                                          </strong>
                                          <dl className="tm-mc-fields">
                                            <dt>{t('usr.colUsername')}:</dt>
                                            <dd>{m.username}</dd>
                                            {m.employee_id && (<>
                                              <dt>{t('usr.colEmployeeId')}:</dt>
                                              <dd>{m.employee_id}</dd>
                                            </>)}
                                            {m.email && (<>
                                              <dt>{t('usr.colEmail')}:</dt>
                                              <dd title={m.email}>{m.email}</dd>
                                            </>)}
                                            {m.system_role && (<>
                                              <dt>{t('usr.colRole')}:</dt>
                                              <dd>
                                                <span className={`role-badge role-${m.system_role.toLowerCase()}`}>{m.system_role}</span>
                                              </dd>
                                            </>)}
                                            {m.org_role && (<>
                                              <dt>{t('usr.colOrgRole')}:</dt>
                                              <dd>
                                                <span className={`badge-role badge-role-${m.org_role}`}>{t('usr.orgRoleVal.' + m.org_role)}</span>
                                              </dd>
                                            </>)}
                                            {m.title && (<><dt>{t('usr.colTitle')}:</dt><dd>{m.title}</dd></>)}
                                            {m.phone && (<><dt>{t('usr.colPhone')}:</dt><dd>{m.phone}</dd></>)}
                                            {m.department && (<><dt>{t('usr.colDept')}:</dt><dd>{m.department}</dd></>)}
                                            {m.mudurluk_name && (<><dt>{t('usr.colMudurluk')}:</dt><dd>{m.mudurluk_name}</dd></>)}
                                            {managerLabelFor(m) && (<><dt>{t('team.memberManager')}:</dt><dd>{managerLabelFor(m)}</dd></>)}
                                          </dl>
                                        </div>
                                      </div>
                                    )
                                  })}
                                </div>
                              )
                          })()
                      }
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>

      {/* Sayfa boyutu + sayfalama (istemci-taraflı) */}
      <div className="audit-pagination">
        <label style={{ marginRight: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>
          {t('team.perPage')}
          <select className="audit-filter-input" value={size} onChange={(e) => setSize(Number(e.target.value))}>
            {[20, 50, 100, 200].map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <button disabled={safePage === 0} onClick={() => setPage(safePage - 1)}>{t('app.prevPage')}</button>
        <span>{t('team.pageInfo', safePage + 1, totalPages, filteredTeams.length)}</span>
        <button disabled={safePage + 1 >= totalPages} onClick={() => setPage(safePage + 1)}>{t('app.nextPage')}</button>
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
                <span>{t('team.formLeader')}</span>
                <SearchableSelect
                  value={form.leader_id}
                  onChange={v => setForm({ ...form, leader_id: v })}
                  placeholder={t('team.selectLeader')}
                  searchThreshold={2}
                  options={[
                    { value: '', label: t('team.selectLeader') },
                    ...users.map(u => ({ value: u.id, label: `${u.display_name || u.username} (${u.username})` })),
                  ]}
                />
                <span className="field-hint">{t('team.leaderOptionalHint')}</span>
              </label>
              <label className="full-width">{t('team.formDesc')}
                <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('team.formActive')}
              </label>
              {/* Haftalık e-postalar — yeni takımda İKİSİ DE KAPALI açılır; takım sonradan kendi üyeleri
                  üzerinden açar. E-posta ancak takım AKTİF ve ilgili anahtar açıkken gider. */}
              <div className="full-width tm-weekly-form">
                <span className="tm-weekly-form-title">{t('team.colWeeklyEmails')}</span>
                <div className="tm-weekly-form-row">
                  <WeeklyPill on={!!form.weekly_reminder_enabled} label={t('team.weeklyReminder')}
                    onToggle={() => setForm({ ...form, weekly_reminder_enabled: !form.weekly_reminder_enabled })} />
                  <span>{t('team.weeklyReminder')}</span>
                </div>
                <div className="tm-weekly-form-row">
                  <WeeklyPill on={!!form.weekly_availability_enabled} label={t('team.weeklyAvailability')}
                    onToggle={() => setForm({ ...form, weekly_availability_enabled: !form.weekly_availability_enabled })} />
                  <span>{t('team.weeklyAvailability')}</span>
                </div>
                <span className="field-hint">{t('team.weeklyHint')}</span>
              </div>
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

      <UserEditModal
        user={editingUser}
        teams={teams}
        onClose={() => setEditingUser(null)}
        onSaved={() => {
          loadUsers()
          if (expandedId) reloadMembers(expandedId)
        }}
      />
    </div>
  )
}
