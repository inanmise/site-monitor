import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import AdminChangeHistory from './AdminChangeHistory.jsx'
import UserDetailPanel from './UserDetailPanel.jsx'
import { Download, Search, X, SlidersHorizontal } from 'lucide-react'
import PaginationBar from '../ui/PaginationBar.jsx'
import { toCsv, downloadCsv, stampedName } from '../../utils/csvExport.js'
import { formatDateSec } from '../../api/client'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { UserPlus, UserCog, BellOff } from 'lucide-react'
import AdminAutoResetModal from './AdminAutoResetModal.jsx'
import UserEditModal, { ModalHeaderAvatar } from './UserEditModal.jsx'

const emptyUser = { username: '', password: '', display_name: '', email: '', employee_id: '', system_role: 'USER', team_ids: [], org_role: '', active: true,
  first_name: '', last_name: '', title: '', phone: '', department: '', company_level: '', mudurluk_name: '', manager_sicil: '' }

const AVATAR_PALETTE = [
  'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
  'linear-gradient(135deg, #0ea5e9 0%, #06b6d4 100%)',
  'linear-gradient(135deg, #10b981 0%, #14b8a6 100%)',
  'linear-gradient(135deg, #f59e0b 0%, #ef4444 100%)',
  'linear-gradient(135deg, #ec4899 0%, #a855f7 100%)',
]
function initialsOf(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}
function avatarBg(seed) {
  const s = String(seed || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0
  return AVATAR_PALETTE[Math.abs(h) % AVATAR_PALETTE.length]
}
/** AD photo with graceful fallback to a colored-initials badge. */
function UserAvatar({ user }) {
  const [err, setErr] = useState(false)
  if (!err) {
    // Kullanıcılar sekmesi USER rolüne de açık → admin'e özel foto ucu boş yere 403 üretirdi.
    return <img className="usr-avatar" alt="" src={`/api/users/${user.id}/photo`} onError={() => setErr(true)} />
  }
  return <span className="usr-avatar usr-avatar-fallback" style={{ background: avatarBg(user.username) }}>
    {initialsOf(user.display_name || user.username)}
  </span>
}

export default function UserManager({ systemRole, ownTeamId, currentUsername, teams }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  const isSelf = (u) => u?.username === currentUsername
  const isAudit = systemRole === 'AUDIT'
  const canSeeAllTeams = isAdmin || isAudit   // takım filtresi yalnız bunlara görünür
  const { showConfirm } = useDialog()
  const [users, setUsers] = useState([])
  const [modal, setModal] = useState(null)
  const [autoResetModal, setAutoResetModal] = useState(null)
  const [viewUser, setViewUser] = useState(null)   // satıra tıklayınca açılan salt-okunur detay modalı
  const [form, setForm] = useState(emptyUser)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  // Filtre + sunucu-taraflı sayfalama
  // Süzgeçler URL'de (g_*): derin bağlantı + yenileme korur (2026-09-20).
  const [histFilter, setHistFilter] = useState(null)   // { id, name } — satırdan "Geçmiş" (yalnız global ADMIN)
  const [q, setQ] = useState(() => readUrlParam('g_q', ''))
  const [fDormant, setFDormant] = useState(() => readUrlParam('g_dormant', ''))   // '' | '30' | '90' | '180' | 'never' (2026-09-20)
  const [selected, setSelected] = useState(() => new Set())                    // toplu işlem seçimi (id)
  const [bulk, setBulk] = useState(null)                                        // { action, team_id, org_role }
  const [bulkBusy, setBulkBusy] = useState(false)
  const [fRole, setFRole] = useState(() => readUrlParam('g_role', ''))
  const [fOrgRole, setFOrgRole] = useState(() => readUrlParam('g_org', ''))
  const [fTeam, setFTeam] = useState(() => readUrlParam('g_team', ''))
  useUrlQuerySync({ g_q: q, g_role: fRole, g_org: fOrgRole, g_team: fTeam, g_dormant: fDormant })
  // Proje standardı araç çubuğu (2026-09-20): arama kutusu + "Süzgeçler" açılır paneli (envanter ile aynı .invtb dağarcığı);
  // etkin süzgeç varsa panel açık başlar (derin bağlantıdan gelen kişi süzgeci görsün).
  const filterActive = !!(fRole || fOrgRole || fTeam || fDormant)
  const [filtersOpen, setFiltersOpen] = useState(() => !!(readUrlParam('g_role', '') || readUrlParam('g_org', '') || readUrlParam('g_team', '') || readUrlParam('g_dormant', '')))
  const clearFilters = () => { setQ(''); setFRole(''); setFOrgRole(''); setFTeam(''); setFDormant('') }
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)   // PaginationBar seçenekleriyle (25/50/100/200) hizalı
  const [total, setTotal] = useState(0)
  const [activeAdminCount, setActiveAdminCount] = useState(0)
  const [loading, setLoading] = useState(false)
  const totalPages = Math.max(1, Math.ceil(total / size))

  // Son aktif admin sayısı sunucudan gelir → sayfalamadan bağımsız doğru
  const isLastActiveAdmin = (u) =>
    u?.system_role === 'ADMIN' && u?.active && activeAdminCount === 1

  const teamMap = Object.fromEntries((teams || []).map(t => [t.id, t.name]))

  async function load(p = page, s = size) {
    setLoading(true)
    try {
      const res = await api.admin.searchUsers({
        page: p, size: s, q: q.trim(), systemRole: fRole, orgRole: fOrgRole, teamId: fTeam,
        dormantDays: fDormant && fDormant !== 'never' ? Number(fDormant) : '',
        neverLoggedIn: fDormant === 'never',
      })
      if (res?.success) {
        setUsers(res.data); setTotal(res.total ?? 0); setPage(res.page ?? 0)
        setActiveAdminCount(res.active_admin_count ?? 0)
        // Sayfa değişince görünmeyen seçim kalmasın (yanlışlıkla toplu işlem görünmeyene uygulanmasın).
        setSelected(prev => new Set([...prev].filter(id => (res.data || []).some(u => u.id === id))))
      }
    } finally {
      setLoading(false)
    }
  }

  // Filtre/sayfa-boyutu değişince 0. sayfaya dön (arama debounce'lu); ilk yükleme de buradan
  useEffect(() => {
    const tmr = setTimeout(() => load(0, size), 300)
    return () => clearTimeout(tmr)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, fRole, fOrgRole, fTeam, fDormant, size])

  // ── Toplu işlem (2026-09-20): sayfadaki seçim → tek istek; sunucu her kullanıcıyı kendi güvenlik zincirinden geçirir. ──
  const pageIds = users.map(u => u.id)
  const allPageSelected = pageIds.length > 0 && pageIds.every(id => selected.has(id))
  function toggleAllPage() {
    setSelected(prev => { const n = new Set(prev); if (allPageSelected) pageIds.forEach(id => n.delete(id)); else pageIds.forEach(id => n.add(id)); return n })
  }
  function toggleOne(id) { setSelected(prev => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n }) }

  async function runBulk(action, extra = {}) {
    const labels = { activate: t('usr.bulkActivate'), deactivate: t('usr.bulkDeactivate'), assign_team: t('usr.bulkAssignTeam'), set_org_role: t('usr.bulkOrgRole') }
    const ok = await showConfirm({
      title: t('usr.bulkTitle'),
      message: t('usr.bulkConfirm', selected.size, labels[action] || action),
      confirmText: t('usr.bulkApply'),
      variant: action === 'deactivate' ? 'danger' : undefined,
    })
    if (!ok) return
    setBulkBusy(true)
    try {
      const res = await api.admin.bulkUsers({ action, ids: [...selected], ...extra })
      if (res?.success) {
        const d = res.data || {}
        if ((d.failed ?? 0) > 0) toast.error(t('usr.bulkDone', d.ok ?? 0, d.failed ?? 0))
        else toast.success(t('usr.bulkDone', d.ok ?? 0, 0))
        setSelected(new Set()); setBulk(null); load()
      } else toast.error(res?.error || 'Error')
    } finally { setBulkBusy(false) }
  }

  /** CSV: süzgeçli liste, TÜM sayfalar (200'lük dilimlerle). Ekranda sayfa sayfa gezmeden dışa liste. */
  async function exportCsv() {
    const rows = []
    for (let p = 0; p < 50; p++) {
      const res = await api.admin.searchUsers({
        page: p, size: 200, q: q.trim(), systemRole: fRole, orgRole: fOrgRole, teamId: fTeam,
        dormantDays: fDormant && fDormant !== 'never' ? Number(fDormant) : '', neverLoggedIn: fDormant === 'never',
      })
      if (!res?.success) break
      for (const u of res.data || []) {
        const tids = u.team_ids ?? u.teamIds ?? (u.team_id != null ? [u.team_id] : [])
        rows.push([u.username, u.display_name, u.email, u.employee_id, u.system_role, u.org_role,
          tids.map(id => teamMap[id] || id).join(' | '), u.active ? t('usr.active') : t('usr.inactive'),
          u.auth_source, u.last_login_at || '', u.title, u.department])
      }
      if ((res.page ?? p) + 1 >= (res.total_pages ?? 0)) break
    }
    downloadCsv(stampedName('kullanicilar'), toCsv(
      [t('usr.formUsername'), t('usr.formDisplay'), t('usr.colEmail'), t('usr.formEmployeeId'), t('usr.colRole'), t('usr.colOrgRole'),
       t('usr.colTeam'), t('usr.colActive'), t('usr.authSourceTitle'), t('usr.colLastLogin'), t('usr.colTitle'), t('usr.colDept')], rows))
    toast.success(t('usr.exportDone', rows.length))
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
      team_ids: user.team_ids ?? user.teamIds ?? (user.team_id != null ? [user.team_id] : []),
      org_role: user.org_role || '',
      active: user.active,
      first_name: user.first_name || '',
      last_name: user.last_name || '',
      title: user.title || '',
      phone: user.phone || '',
      department: user.department || '',
      company_level: user.company_level || '',
      mudurluk_name: user.mudurluk_name || '',
      manager_sicil: user.manager_sicil || '',
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
    try {
      const teamIds = isTeamAdmin
        ? (ownTeamId != null ? [ownTeamId] : [])
        : (form.team_ids || [])
      const payload = {
        username: form.username.trim(),
        display_name: form.display_name,
        email: form.email,
        employee_id: form.employee_id,
        system_role: form.system_role,
        team_ids: teamIds,
        team_id: teamIds[0] ?? null,
        org_role: form.org_role || null,
        active: form.active,
        first_name: form.first_name,
        last_name: form.last_name,
        title: form.title,
        phone: form.phone,
        department: form.department,
        company_level: form.company_level,
        mudurluk_name: form.mudurluk_name,
        manager_sicil: form.manager_sicil,
      }
      let res
      if (modal === 'add') {
        res = await api.admin.createUser({ ...payload, password: form.password })
      } else {
        res = await api.admin.updateUser(modal.id, payload)
      }
      if (res?.success) { setModal(null); toast.success(t('usr.saved')); load() }
      else setMsg(res?.error || 'Error')
    } finally {
      setSaving(false)
    }
  }

  async function unlock(id) {
    const res = await api.admin.unlockUser(id)
    if (res?.success) { toast.success(t('usr.unlocked')); load() }
    else toast.error(res?.error || 'Error')
  }

  async function roleUnlock(id) {
    const res = await api.admin.unlockUserRole(id)
    if (res?.success) { toast.success(t('usr.roleUnlocked')); load() }
    else toast.error(res?.error || 'Error')
  }

  async function orgRoleUnlock(id) {
    const res = await api.admin.unlockUserOrgRole(id)
    if (res?.success) { toast.success(t('usr.orgRoleUnlocked')); load() }
    else toast.error(res?.error || 'Error')
  }

  // Takım kilidi (2026-09-18): admin üyeliği elle değiştirince LDAP girişi ezmez; kilit kalkınca AD yazar.
  async function teamUnlock(id) {
    const res = await api.admin.unlockUserTeams(id)
    if (res?.success) { toast.success(t('usr.teamUnlocked')); load() }
    else toast.error(res?.error || 'Error')
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
    const res = await api.admin.deleteUser(id)
    if (res?.success) { toast.success(t('usr.deleted')); load() }
    else toast.error(res?.error || 'Error')
  }

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('usr.title')}</h3>
        <div className="hdr-actions">
          <button className="btn btn-secondary" onClick={exportCsv} title={t('usr.exportCsv')}><Download size={14} /> {t('usr.exportCsv')}</button>
          {canManage && <button className="btn btn-success" onClick={openAdd}>{t('usr.addBtn')}</button>}
        </div>
      </div>
      {msg && !modal && !autoResetModal && <div className="alert-msg">{msg}</div>}

      {/* Araç çubuğu — proje standardı (.invtb): arama kutusu + Süzgeçler paneli + kayıt sayacı (2026-09-20) */}
      <div className="invtb um-toolbar" data-testid="um-toolbar">
        <div className="invtb-row">
          <label className="invtb-search">
            <Search size={14} aria-hidden="true" />
            <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('usr.searchPlaceholder')} aria-label={t('usr.searchLabel')} />
            {q && <button type="button" className="invtb-clear" onClick={() => setQ('')} aria-label={t('inv.filterClear')}><X size={12} /></button>}
          </label>
          <button type="button" className={`btn btn-sm ${filtersOpen || filterActive ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setFiltersOpen((o) => !o)} aria-expanded={filtersOpen}>
            <SlidersHorizontal size={13} /> {t('inv.filters')}{filterActive ? ` · ${t('inv.filterActive')}` : ''}
          </button>
          <span className="invtb-count">{loading ? '…' : t('inv.shownOf', users.length, total)}</span>
          <div className="invtb-spacer" />
          {(filterActive || q) && <button type="button" className="btn btn-sm btn-secondary" onClick={clearFilters}>{t('inv.filterClear')}</button>}
        </div>
        {filtersOpen && (
          <div className="invtb-filters" role="group" aria-label={t('inv.filters')}>
            <label className="invtb-f"><span>{t('usr.colRole')}</span>
              <SearchableSelect value={fRole} onChange={setFRole} ariaLabel={t('usr.colRole')}
                options={[{ value: '', label: t('usr.allRoles') }, ...['ADMIN', 'TEAM_ADMIN', 'USER', 'AUDIT'].map(r => ({ value: r, label: r }))]} /></label>
            <label className="invtb-f"><span>{t('usr.colOrgRole')}</span>
              <SearchableSelect value={fOrgRole} onChange={setFOrgRole} ariaLabel={t('usr.colOrgRole')}
                options={[{ value: '', label: t('usr.allOrgRoles') }, ...['PO', 'TECH', 'MANAGER', 'BOLUM_BASKANI', 'CLEVEL'].map(r => ({ value: r, label: t('usr.orgRoleVal.' + r) }))]} /></label>
            {canSeeAllTeams && (
              <label className="invtb-f"><span>{t('usr.colTeam')}</span>
                <SearchableSelect value={fTeam} onChange={setFTeam} ariaLabel={t('usr.colTeam')} searchThreshold={2}
                  options={[{ value: '', label: t('usr.allTeams') }, ...(teams || []).map(tm => ({ value: String(tm.id), label: tm.name }))]} /></label>
            )}
            <label className="invtb-f"><span>{t('usr.colLastLogin')}</span>
              <SearchableSelect value={fDormant} onChange={setFDormant} ariaLabel={t('usr.colLastLogin')}
                options={[{ value: '', label: t('usr.dormantAll') }, { value: '30', label: t('usr.dormant30') }, { value: '90', label: t('usr.dormant90') },
                  { value: '180', label: t('usr.dormant180') }, { value: 'never', label: t('usr.dormantNever') }]} /></label>
          </div>
        )}
      </div>

      {/* Toplu işlem çubuğu — seçim varken (2026-09-20) */}
      {canManage && selected.size > 0 && (
        <div className="um-bulk" data-testid="bulk-bar" aria-busy={bulkBusy || undefined}>
          <span className="um-bulk-count">{t('usr.selected', selected.size)}</span>
          <button className="btn btn-sm-p btn-secondary" onClick={() => runBulk('activate')} disabled={bulkBusy}>{t('usr.bulkActivate')}</button>
          <button className="btn btn-sm-p btn-danger" onClick={() => runBulk('deactivate')} disabled={bulkBusy}>{t('usr.bulkDeactivate')}</button>
          {canSeeAllTeams && (
            <SearchableSelect value={bulk?.team_id || ''} onChange={(v) => v && runBulk('assign_team', { team_id: Number(v) })} placeholder={t('usr.bulkAssignTeam')} ariaLabel={t('usr.bulkAssignTeam')}
              searchThreshold={2} options={[{ value: '', label: t('usr.bulkPickTeam') }, ...(teams || []).map(tm => ({ value: String(tm.id), label: tm.name }))]} />
          )}
          <SearchableSelect value={bulk?.org_role || ''} onChange={(v) => v && runBulk('set_org_role', { org_role: v })} placeholder={t('usr.bulkOrgRole')} ariaLabel={t('usr.bulkOrgRole')}
            options={[{ value: '', label: t('usr.bulkPickOrgRole') }, ...['PO', 'TECH', 'MANAGER', 'BOLUM_BASKANI', 'CLEVEL'].map(r => ({ value: r, label: t('usr.orgRoleVal.' + r) }))]} />
          <button className="btn btn-sm-p btn-secondary" onClick={() => setSelected(new Set())} disabled={bulkBusy}>{t('usr.bulkClear')}</button>
        </div>
      )}

      <div className="admin-table-wrap">
        {/* 2026-09-11: 11 sütun (avatar + kullanıcı adı + sicil + ad + ünvan + …) 1366px'te sığmıyor,
            Eylemler sütunu yatay kaydırmanın ardında kayboluyordu. Kimlik alanları TEK hücrede
            (avatar · ad soyad / kullanıcı adı · sicil / ünvan), Eylemler daralmaz (um-col-actions). */}
        <table className="admin-table um-table">
          <thead>
            <tr>
              {canManage && <th className="um-col-check"><input type="checkbox" checked={allPageSelected} onChange={toggleAllPage} aria-label={t('usr.selectAll')} /></th>}
              <th>{t('usr.colUser')}</th>
              <th>{t('usr.colEmail')}</th>
              <th>{t('usr.colRole')}</th>
              <th>{t('usr.colOrgRole')}</th>
              <th>{t('usr.colTeam')}</th>
              <th>{t('usr.colLastLogin')}</th>
              <th>{t('usr.colActive')}</th>
              <th className="um-col-actions">{t('usr.colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr><td colSpan={9} style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 18 }}>
                {loading ? '…' : t('usr.noResults')}
              </td></tr>
            )}
            {users.map((user) => (
              <tr key={user.id} style={{ cursor: 'pointer' }} title={t('usr.viewTitle')} tabIndex={0}
                aria-label={t('a11y.openRow', user.display_name || user.username)}
                onClick={() => setViewUser(user)}
                onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setViewUser(user) } }}>
                {canManage && (
                  <td className="um-col-check" onClick={(e) => e.stopPropagation()}>
                    <input type="checkbox" checked={selected.has(user.id)} onChange={() => toggleOne(user.id)} aria-label={user.username} />
                  </td>
                )}
                <td className="um-col-user">
                  <div className="um-identity">
                    <UserAvatar user={user} />
                    <div className="um-identity-text">
                      <strong className="um-identity-name">
                        {user.display_name || user.username}
                        {user.push_opt_out && (
                          <span className="um-optout-badge" title={t('usr.pushOptOutTitle')} aria-label={t('usr.pushOptOutTitle')}>
                            <BellOff size={12} />
                          </span>
                        )}
                      </strong>
                      <span className="um-identity-sub sys-mono">
                        {user.username}{user.employee_id ? ` · ${user.employee_id}` : ''}
                        {user.auth_source === 'LDAP' && <span className="um-ldap" title={t('usr.authSourceTitle')}>{t('usr.ldapBadge')}</span>}
                      </span>
                      {user.title && <span className="um-identity-title" title={user.title}>{user.title}</span>}
                    </div>
                  </div>
                </td>
                <td className="um-col-email"><span className="um-email" title={user.email || ''}>{user.email || '—'}</span></td>
                <td>
                  <span className={`role-badge${user.system_role === 'ADMIN' ? ' role-admin' : user.system_role === 'AUDIT' ? ' role-audit' : ''}`}>{user.system_role}</span>
                  {user.role_locked && (
                    <span style={{ marginLeft: 6, cursor: 'help' }} title={t('usr.roleLockedTitle')}>🔒</span>
                  )}
                  {isLastActiveAdmin(user) && (
                    <span className="badge badge-err" style={{ marginLeft: 6 }} title={t('usr.lastAdminTitle')}>
                      {t('usr.lastAdminBadge')}
                    </span>
                  )}
                </td>
                <td>
                  {user.org_role ? <span className={`badge-role badge-role-${user.org_role}`}>{t('usr.orgRoleVal.' + user.org_role)}</span> : '—'}
                  {user.org_role_locked && (
                    <span style={{ marginLeft: 6, cursor: 'help' }} title={t('usr.orgRoleLockedTitle')}>🔒</span>
                  )}
                </td>
                <td className="um-col-team">{((user.team_ids ?? user.teamIds ?? (user.team_id != null ? [user.team_id] : []))
                  .map(id => teamMap[id]).filter(Boolean).join(', ')) || '—'}
                  {user.team_locked && (
                    <span style={{ marginLeft: 6, cursor: 'help' }} title={t('usr.teamLockedTitle')}>🔒</span>
                  )}</td>
                <td>
                  {user.last_login_at
                    ? <span className="um-lastlogin" title={user.last_login_method || ''}>{formatDateSec(user.last_login_at)}</span>
                    : <span className="um-lastlogin um-lastlogin--never">{t('usr.neverLoggedIn')}</span>}
                </td>
                <td>
                  <span className={user.active ? 'badge badge-ok' : 'badge badge-err'}>{user.active ? t('usr.active') : t('usr.inactive')}</span>
                  {user.permanent_lock && <span className="badge badge-err" style={{ marginLeft: 4 }} title={t('usr.permLocked')}>🔒</span>}
                </td>
                <td className="um-col-actions" onClick={(e) => e.stopPropagation()}>
                  <KebabMenu label={t('usr.colActions')} rowLabel={user.display_name || user.username} items={canManage ? [
                    { label: t('usr.edit'), onClick: () => openEdit(user) },
                    { label: t('hist.title'), onClick: () => setHistFilter({ id: user.id, name: user.display_name || user.username }), hidden: !isAdmin },
                    { label: t('usr.autoResetBtn'), onClick: () => setAutoResetModal(user) },
                    { label: t('usr.unlock'), onClick: () => unlock(user.id), hidden: !user.permanent_lock },
                    { label: t('usr.roleUnlock'), onClick: () => roleUnlock(user.id), hidden: !user.role_locked },
                    { label: t('usr.orgRoleUnlock'), onClick: () => orgRoleUnlock(user.id), hidden: !user.org_role_locked },
                    { label: t('usr.teamUnlock'), onClick: () => teamUnlock(user.id), hidden: !user.team_locked },
                    { label: t('usr.delete'), danger: true, onClick: () => del(user.id),
                      hidden: isSelf(user) || isLastActiveAdmin(user) },
                  ] : []} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Sayfalama — proje standardı PaginationBar (1 tabanlı; sunucu 0 tabanlı) (2026-09-20) */}
      {total > 0 && (
        <PaginationBar page={page + 1} totalPages={totalPages} totalItems={total}
          rangeStart={page * size + 1} rangeEnd={Math.min((page + 1) * size, total)}
          pageSize={size} onPageChange={(p) => load(p - 1, size)} onPageSizeChange={(s) => setSize(s)} />
      )}

      {/* Kullanıcı geçmişi yalnız global ADMIN (rol/takım/parola sıfırlama kayıtları kişisel veri taşır). */}
      <AdminChangeHistory resource="USER" filter={histFilter} onClearFilter={() => setHistFilter(null)} canView={isAdmin} />

      {modal !== null && (
        <div className="modal-overlay" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={(e) => e.stopPropagation()}>
            <div className="modal-icon-hdr modal-icon-hdr--user">
              <div className="modal-icon-hdr-badge">
                {modal === 'add'
                  ? <UserPlus size={20} />
                  : <ModalHeaderAvatar userId={modal?.id}><UserCog size={20} /></ModalHeaderAvatar>}
              </div>
              <h3>{modal === 'add' ? t('usr.addTitle') : t('usr.editTitle')}</h3>
            </div>
            {/* form-grid--top: alanlar üstten hizalansın — "Takım"daki uyarı ipucu (teamRequired)
                altta dururken Organizasyonel Rol ile Takım select'leri karşılıklı kalsın (align-items:end kayması) */}
            <div className="form-grid form-grid--top">
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
                  disabled={modal !== 'add' && (isSelf(modal) || isLastActiveAdmin(modal))}
                  options={isAdmin ? [
                    { value: 'USER',       label: 'USER' },
                    { value: 'TEAM_ADMIN', label: 'TEAM_ADMIN' },
                    { value: 'AUDIT',      label: 'AUDIT' },
                    { value: 'ADMIN',      label: 'ADMIN' },
                  ] : [
                    { value: 'USER',       label: 'USER' },
                    { value: 'TEAM_ADMIN', label: 'TEAM_ADMIN' },
                  ]}
                />
                {modal !== 'add' && isSelf(modal) && (
                  <span className="field-hint field-hint--warn">{t('usr.selfRoleLocked')}</span>
                )}
                {modal !== 'add' && !isSelf(modal) && isLastActiveAdmin(modal) && (
                  <span className="field-hint field-hint--warn">{t('usr.lastAdminRoleLocked')}</span>
                )}
              </label>
              <label>{t('usr.orgRole')}
                <SearchableSelect
                  value={form.org_role}
                  onChange={v => setForm({ ...form, org_role: v })}
                  options={[
                    { value: '',              label: t('usr.orgRoleNone') },
                    { value: 'TECH',          label: t('usr.orgRoleVal.TECH') },
                    { value: 'PO',            label: t('usr.orgRoleVal.PO') },
                    { value: 'MANAGER',       label: t('usr.orgRoleVal.MANAGER') },
                    { value: 'BOLUM_BASKANI', label: t('usr.orgRoleVal.BOLUM_BASKANI') },
                    { value: 'CLEVEL',        label: t('usr.orgRoleVal.CLEVEL') },
                  ]}
                />
              </label>
              <label>
                <span>{t('usr.teamsLabel')} {form.system_role !== 'ADMIN' && <span className="req-star">*</span>}</span>
                {isTeamAdmin ? (
                  <SearchableSelect
                    value={ownTeamId ?? ''}
                    onChange={() => {}}
                    disabled
                    options={[
                      { value: ownTeamId ?? '', label: (teams || []).find(team => team.id === ownTeamId)?.name ?? t('usr.noTeam') },
                    ]}
                  />
                ) : (
                  <MultiTeamSelect
                    value={form.team_ids}
                    onChange={ids => setForm({ ...form, team_ids: ids.map(Number) })}
                    placeholder={t('usr.teamsPlaceholder')}
                    searchThreshold={2}
                    options={(teams || []).map(team => ({ value: team.id, label: team.name }))}
                  />
                )}
                {!isTeamAdmin && form.system_role !== 'ADMIN' && form.team_ids.length === 0 && (
                  <span className="field-hint field-hint--warn">{t('usr.teamsRequired')}</span>
                )}
              </label>
              {/* AD'den eşlenen profil alanları (LDAP kullanıcısında bir sonraki login'de tazelenir) */}
              <label>{t('usr.formFirstName')}
                <input value={form.first_name} onChange={(e) => setForm({ ...form, first_name: e.target.value })} />
              </label>
              <label>{t('usr.formLastName')}
                <input value={form.last_name} onChange={(e) => setForm({ ...form, last_name: e.target.value })} />
              </label>
              <label>{t('usr.colTitle')}
                <input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
              </label>
              <label>{t('usr.colPhone')}
                <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
              </label>
              <label>{t('usr.colDept')}
                <input value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} />
              </label>
              <label>{t('usr.formCompanyLevel')}
                <input value={form.company_level} onChange={(e) => setForm({ ...form, company_level: e.target.value })} />
              </label>
              <label>{t('usr.colMudurluk')}
                <input value={form.mudurluk_name} onChange={(e) => setForm({ ...form, mudurluk_name: e.target.value })} />
              </label>
              <label>{t('usr.colManager')}
                <input value={form.manager_sicil} onChange={(e) => setForm({ ...form, manager_sicil: e.target.value })} />
              </label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active}
                  disabled={modal !== 'add' && (isSelf(modal) || isLastActiveAdmin(modal))}
                  onChange={(e) => setForm({ ...form, active: e.target.checked })} />
                {t('usr.formActive')}
                {modal !== 'add' && isSelf(modal) && (
                  <span className="field-hint field-hint--warn" style={{ marginLeft: 8 }}>{t('usr.selfActiveLocked')}</span>
                )}
                {modal !== 'add' && !isSelf(modal) && isLastActiveAdmin(modal) && (
                  <span className="field-hint field-hint--warn" style={{ marginLeft: 8 }}>{t('usr.lastAdminActiveLocked')}</span>
                )}
              </label>
            </div>
            {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('usr.cancel')}</button>
              <button className="btn btn-primary" onClick={save}
                disabled={saving || !form.username.trim() || !form.email.trim() || (form.system_role !== 'ADMIN' && (isTeamAdmin ? !ownTeamId : form.team_ids.length === 0)) || (modal === 'add' && form.password.length < 4)}>
                {saving ? t('usr.saving') : t('usr.save')}
              </button>
            </div>
          </div>
        </div>
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

      {/* Satıra tıklayınca: kullanıcı düzenle ekranının salt-okunur (gösterim) hali */}
      {viewUser && (
        <UserDetailPanel user={viewUser} teams={teams} isAdmin={isAdmin}
          onClose={() => setViewUser(null)}
          onEdit={canManage ? () => { const u = viewUser; setViewUser(null); openEdit(u) } : undefined} />
      )}
    </div>
  )
}
