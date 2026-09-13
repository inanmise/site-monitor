import { useState, useEffect, useMemo, Fragment } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { UsersRound, PenLine } from 'lucide-react'
import UserEditModal from './UserEditModal.jsx'
import TagInput from '../ui/TagInput.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import TeamMembersModal from '../ui/TeamMembersModal.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { resolveTeamManager } from '../../utils/teamManager.js'

// Haftalık e-postalar opt-in: YENİ takım ikisi de kapalı doğar (backend de createTeam'de false yazar).
const emptyTeam = { name: '', email: '', description: '', active: true, leader_id: '', manager_id: '',
  weekly_reminder_enabled: false, weekly_availability_enabled: false, weekly_channels: '' }

/** Takım kanal şablonu (2026-09-13): sunucu JSON dizi metni tutar (`weekly_channels`); formda CSV. */
export function channelsCsv(team) {
  const raw = team?.weekly_channels ?? team?.weeklyChannels
  if (Array.isArray(raw)) return raw.join(', ')
  if (typeof raw !== 'string' || !raw.trim()) return ''
  try { const arr = JSON.parse(raw); return Array.isArray(arr) ? arr.map((x) => String(x).trim()).filter(Boolean).join(', ') : '' } catch { return '' }
}
export function channelsList(csv) {
  const out = []
  for (const x of String(csv || '').split(',')) { const v = x.trim().slice(0, 60); if (v && !out.includes(v)) out.push(v); if (out.length >= 20) break }
  return out
}

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
  const [loadError, setLoadError] = useState(null)
  const [users, setUsers]   = useState([])
  const [modal, setModal]   = useState(null)
  const [form, setForm]     = useState(emptyTeam)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg]       = useState(null)
  // Üye kartları artık MODALDA (satır-içi genişletme yerine): takım adı tıklanır, TeamMembersModal
  // açılır. Yönetim ekranı kapsamlı /admin/teams/{id}/users ile tam alanları (telefon/sicil/rol)
  // gösterir; eskalasyon kişileri kurum-geneli uçtan gelir.
  const [membersTeam, setMembersTeam]   = useState(null)
  const [membersNonce, setMembersNonce] = useState(0)
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
    // EN TEHLIKELI yalanci bos durum: hata dali hic yoktu, "hic takim yok" ekrani
    // basarisiz yuklemeden ayirt edilemiyordu. Takimlar tum yetkilendirmenin temeli
    // (viewTeamIds/manageTeamIds) — yonetici silinmis sanip yeniden olusturursa
    // uyelikler ve takim kapsamli alarmlar ikiye bolunur.
    try {
      const res = await api.admin.getTeams()
      if (res?.success) { setTeams(res.data); setLoadError(null) }
      else setLoadError(res?.error || t('settings.loadError'))
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers((res.data ?? []).filter(u => u.active))   // data null gelirse ekran cokmesin
  }

  /** Yönetim ekranı yükleyicisi: kapsamlı tam üye listesi + kurum-geneli eskalasyon kişileri. */
  const loadTeamMembers = async (teamId) => {
    const [adm, dir] = await Promise.all([
      api.admin.getTeamUsers(teamId),
      Promise.resolve(api.teams?.members ? api.teams.members(teamId) : null).catch(() => null),
    ])
    if (!adm?.success) return adm
    return { success: true, data: {
      team: dir?.data?.team ?? null, members: adm.data ?? [], escalation_contacts: dir?.data?.escalation_contacts ?? [],
    } }
  }

  const userMap = Object.fromEntries(users.map(u => [u.id, u.display_name || u.username]))
  const usersById = Object.fromEntries(users.map(u => [u.id, u]))

  /** Bir kullanıcının bağlı olduğu müdür etiketi: adı (çözülebiliyorsa) yoksa sicili. */
  const managerLabelFor = (u) => (u?.manager_id && userMap[u.manager_id]) || u?.manager_sicil || null

  /** Takımın müdürü — TEK kişi. Elle atanmışsa (team.manager_id) o; yoksa takımın bağlı olduğu ilk
   *  yönetici (lider/PO ve üst kademeler elenir; kural utils/teamManager.js). Eskiden üyelerin
   *  müdürlerinin birleşimiydi → iki ad çıkıyordu. */
  const manualManagerId = (team) => team.manager_id ?? team.managerId ?? null
  const teamManagerLabel = (team) => {
    const manual = manualManagerId(team)
    if (manual != null && userMap[manual]) return userMap[manual]
    return resolveTeamManager(users.filter(u => u.team_id === team.id), usersById, managerLabelFor,
      team.leader_id ?? team.leaderId ?? null)
  }

  function openAdd() { setForm(emptyTeam); setModal('add'); setMsg(null) }
  function openEdit(team) {
    setForm({ ...team, email: team.email || '', leader_id: String(team.leaderId ?? team.leader_id ?? ''),
      manager_id: String(team.managerId ?? team.manager_id ?? ''),
      weekly_reminder_enabled: weeklyFlag(team, 'reminder'),
      weekly_availability_enabled: weeklyFlag(team, 'availability'),
      weekly_channels: channelsCsv(team) })
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
        manager_id: form.manager_id ? Number(form.manager_id) : null,  // elle müdür; null → AD zincirinden türet
        weekly_reminder_enabled: !!form.weekly_reminder_enabled,
        weekly_availability_enabled: !!form.weekly_availability_enabled,
      }
      const isAdd = modal === 'add'
      const editedId = isAdd ? null : modal.id
      const res = isAdd
        ? await api.admin.createTeam(payload)
        : await api.admin.updateTeam(editedId, payload)
      if (res?.success) {
        // Kanal şablonu ayrı (dar) uçtan gider: yalnız değiştiyse; yeni takımda dolu girildiyse (2026-09-13)
        const nextCh = channelsList(form.weekly_channels)
        const prevCh = isAdd ? [] : channelsList(channelsCsv(modal))
        const chId = isAdd ? res.data?.id : editedId
        if (chId && JSON.stringify(nextCh) !== JSON.stringify(prevCh)) {
          const chRes = await api.admin.updateTeamWeeklyNotifications(chId, { weekly_channels: nextCh })
          if (!chRes?.success) toast.error(chRes?.error || t('team.weeklySaveError'))
        }
        toast.success(t('team.saved'))
        load(); onTeamsChange?.()
        if (!isAdd) setMembersNonce(n => n + 1)
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
    if (membersTeam?.id === id) setMembersTeam(null)
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
      {loadError && teams.length === 0 && (
        <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
      )}

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
                    <strong><TeamBadge teamId={team.id} teamName={team.name} size={13}
                      onOpen={() => setMembersTeam(team)} title={t('team.expandMembers')} /></strong>
                  </td>
                  <td>{team.email || '—'}</td>
                  <td>{userMap[team.leader_id] ?? <span style={{ color: 'var(--danger)' }}>{t('team.noLeader')}</span>}</td>
                  <td>
                    {teamManagerLabel(team) || '—'}
                    {manualManagerId(team) != null && userMap[manualManagerId(team)] && (
                      <span className="field-hint" style={{ marginLeft: 6 }} title={t('team.managerManualTitle')}>
                        {t('team.managerManual')}
                      </span>
                    )}
                  </td>
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
              <label>
                <span>{t('team.formManager')}</span>
                <SearchableSelect
                  value={form.manager_id}
                  onChange={v => setForm({ ...form, manager_id: v })}
                  placeholder={t('team.selectManager')}
                  searchThreshold={2}
                  options={[
                    { value: '', label: t('team.selectManager') },
                    ...users.map(u => ({ value: u.id, label: `${u.display_name || u.username} (${u.username})` })),
                  ]}
                />
                <span className="field-hint">{t('team.managerHint')}</span>
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
                <div className="tm-weekly-channels">
                  <TagInput label={t('team.weeklyChannels')} value={form.weekly_channels || ''}
                    onChange={(v) => setForm({ ...form, weekly_channels: v })} placeholder={t('team.weeklyChannelsPh')} />
                  <span className="field-hint">{t('team.weeklyChannelsHint')}</span>
                </div>
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

      <TeamMembersModal open={!!membersTeam} team={membersTeam} onClose={() => setMembersTeam(null)}
        canManage={canManage} onEditUser={setEditingUser} loadMembers={loadTeamMembers}
        usersById={usersById} managerLabelFor={managerLabelFor} refreshKey={membersNonce} />
      <UserEditModal
        user={editingUser}
        teams={teams}
        onClose={() => setEditingUser(null)}
        onSaved={() => {
          loadUsers()
          setMembersNonce(n => n + 1)
        }}
      />
    </div>
  )
}
