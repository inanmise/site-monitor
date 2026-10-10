import { useState, useEffect, useMemo, useCallback } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { UsersRound, PenLine, Headset } from 'lucide-react'
import UserEditModal from './UserEditModal.jsx'
import TagInput from '../ui/TagInput.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { useTeamDirectory } from '../ui/TeamDirectory.jsx'
import TeamMembersModal from '../ui/TeamMembersModal.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import AdminChangeHistory from './AdminChangeHistory.jsx'
import TeamMembersManager from './TeamMembersManager.jsx'
import UserDetailPanel from './UserDetailPanel.jsx'
import TeamDeleteImpactModal from './TeamDeleteImpactModal.jsx'
import TeamLdapAuditModal from './TeamLdapAuditModal.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { Download, SlidersHorizontal } from 'lucide-react'
import PaginationBar from '../ui/PaginationBar.jsx'
import { usePagination } from '../../hooks/usePagination.js'
import { useDialog } from '../ui/Dialog.jsx'
import { toCsv, downloadCsv, stampedName } from '../../utils/csvExport.js'
import { resolveTeamManagerEntry, isTeamMember } from '../../utils/teamManager.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import ToneBadge from './ToneBadge.jsx'
import { ToolbarSearch, FilterPanel, FilterField } from './ListToolbar.jsx'
import { CheckboxRow as FormCheckbox, ToggleRow } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Switch } from '@/components/shadcn/switch'
import { FieldSet, FieldLegend } from '@/components/shadcn/field'
import QuietHoursFields, { QuietIcon } from '../ui/QuietHoursFields.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { EMPTY_QUIET, quietFromTeam, quietEqual, quietErrors, quietTeamPayload } from '../../utils/quietHours.js'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { userRefValue } from '../../utils/userRef'

// Haftalık e-postalar opt-in: YENİ takım ikisi de kapalı doğar (backend de createTeam'de false yazar).
// quiet: sessiz saat form değeri (2026-10-01) — yalnız DEĞİŞTİYSE gövdeye girer (dokunulmamış form = bugünkü gövde).
const emptyTeam = { name: '', email: '', description: '', active: true, leader_id: '', manager_id: '',
  weekly_reminder_enabled: false, weekly_availability_enabled: false, weekly_channels: '', quiet: EMPTY_QUIET }

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

/** Takım satırındaki haftalık e-posta anahtarı — shadcn Switch + kısa etiket (tam ad aria-label/title). */
function WeeklySwitch({ on, disabled, onToggle, label, short }) {
  return (
    <span className="flex items-center gap-2">
      <Switch checked={!!on} disabled={disabled} aria-label={label} title={label} onCheckedChange={onToggle} />
      <span className="text-xs whitespace-nowrap text-muted-foreground">{short}</span>
    </span>
  )
}


export default function TeamManager({ systemRole, ownTeamId, myTeamIds, onTeamsChange, globalAdmin = false, currentUsername = null }) {
  const t = useT()
  const toast = useToast()
  // 7/24 izleme ekibi takımı rozeti (2026-10-04): takım rehberinin `noc_team` bayrağı (sağlayıcı yoksa rozet yok)
  const teamDir = useTeamDirectory()
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
  const [teams, setTeams]   = useState([])
  const [loadError, setLoadError] = useState(null)
  const [users, setUsers]   = useState([])
  const [modal, setModal]   = useState(null)
  const [form, setForm]     = useState(emptyTeam)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg]       = useState(null)
  // Alan-bazlı hatalar (sessiz saat) — pencere her açılışta (modal değişince) temizlenir.
  const fe = useFormErrors(modal)
  // Üye kartları artık MODALDA (satır-içi genişletme yerine): takım adı tıklanır, TeamMembersModal
  // açılır. Yönetim ekranı kapsamlı /admin/teams/{id}/users ile tam alanları (telefon/sicil/rol)
  // gösterir; eskalasyon kişileri kurum-geneli uçtan gelir.
  const [membersTeam, setMembersTeam]   = useState(null)
  const [membersNonce, setMembersNonce] = useState(0)
  const [editingUser, setEditingUser]   = useState(null)
  // Üyeler sekmesinden açılan kullanıcı detayı (2026-09-30): takım penceresinin ÜSTÜNE Sheet (UserManager ile aynı panel).
  const [viewUser, setViewUser]         = useState(null)
  // Detayın açılış sekmesi — düzenleyicideki "AD ile karşılaştır" Dizin sekmesiyle açar (2026-10-02); kapanınca sıfırlanır.
  const [viewTab, setViewTab]           = useState('overview')

  // İstemci-taraflı filtre + sayfalama (getTeams tüm listeyi döndürür — dropdown kaynağı bozulmasın)
  const [histFilter, setHistFilter] = useState(null)   // { id, name } — satırdan "Geçmiş"
  const [stats, setStats] = useState({})                // takım id → sayaçlar (2026-09-20)
  const [manageTeam, setManageTeam] = useState(null)    // üye yönetimi modalı
  const [impactTeam, setImpactTeam] = useState(null)    // silme etki modalı
  const [ldapAuditTeam, setLdapAuditTeam] = useState(null)   // AD ile üyelik denetimi (2026-09-26)
  const [q, setQ]       = useState(() => readUrlParam('g_q', ''))   // URL'de (g_q)
  // Zengin süzgeçler (2026-09-20, kullanıcı bildirimi): durum, müdür, açık alarm, lider, haftalık — URL'de g_*
  const [fActive, setFActive]   = useState(() => readUrlParam('g_active', ''))     // '' | 'active' | 'inactive'
  const [fManager, setFManager] = useState(() => readUrlParam('g_mgr', ''))        // kullanıcı id | 'none'
  const [fAlerts, setFAlerts]   = useState(() => readUrlParam('g_alerts', ''))     // '' | 'open' | 'none'
  const [fLeader, setFLeader]   = useState(() => readUrlParam('g_leader', ''))     // '' | 'none'
  const [fWeekly, setFWeekly]   = useState(() => readUrlParam('g_weekly', ''))     // '' | 'reminder_on' | 'reminder_off' | 'availability_on' | 'availability_off'
  useUrlQuerySync({ g_q: q, g_active: fActive, g_mgr: fManager, g_alerts: fAlerts, g_leader: fLeader, g_weekly: fWeekly })
  const filterActive = !!(fActive || fManager || fAlerts || fLeader || fWeekly)
  const [filtersOpen, setFiltersOpen] = useState(() => !!(readUrlParam('g_active', '') || readUrlParam('g_mgr', '') || readUrlParam('g_alerts', '') || readUrlParam('g_leader', '') || readUrlParam('g_weekly', '')))
  const clearFilters = () => { setQ(''); setFActive(''); setFManager(''); setFAlerts(''); setFLeader(''); setFWeekly('') }
  const { showConfirm } = useDialog()
  const [selected, setSelected] = useState(() => new Set())   // toplu işlem seçimi (takım id)
  const [bulkBusy, setBulkBusy] = useState(false)

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
    loadStats()
  }

  /** Satır sayaçları — ayrı istek: liste sayaç gecikse de gelir (best-effort). */
  async function loadStats() {
    try {
      const r = await api.admin.teamStats?.()
      if (r?.success) setStats(r.data || {})
    } catch { /* sütun boş kalır */ }
  }

  async function loadUsers() {
    const res = await api.admin.getUsers()
    if (res?.success) setUsers((res.data ?? []).filter(u => u.active))   // data null gelirse ekran cokmesin
  }

  /** Yönetim ekranı yükleyicisi: kapsamlı tam üye listesi + kurum-geneli eskalasyon kişileri.
   *  useCallback (bağımlılığı yok — yalnız modül düzeyi `api`): TeamMembersModal `loadMembers` kimliği
   *  değişince listeyi YENİDEN çeker; düz fonksiyon her üst render'da yeni kimlik alıp modalı gereksiz
   *  yeniden yüklüyordu. */
  const loadTeamMembers = useCallback(async (teamId) => {
    const [adm, dir] = await Promise.all([
      api.admin.getTeamUsers(teamId),
      Promise.resolve(api.teams?.members ? api.teams.members(teamId) : null).catch(() => null),
    ])
    if (!adm?.success) return adm
    return { success: true, data: {
      team: dir?.data?.team ?? null, members: adm.data ?? [], escalation_contacts: dir?.data?.escalation_contacts ?? [],
    } }
  }, [])

  // Performans (2026-10-01): dizinler kullanıcı listesi değişince kurulur (her render'da değil) — `openViewUser`
  // kararlı kalır ve üye satırlarının `memo(MemberRow)`'u her render'da yeniden çizilmez.
  const userMap = useMemo(() => Object.fromEntries(users.map(u => [u.id, u.display_name || u.username])), [users])
  const usersById = useMemo(() => Object.fromEntries(users.map(u => [u.id, u])), [users])
  // Düzenlenen üye: üye satırına yerel kullanıcı listesinin TAZE kaydı bindirilir (kilit açma sonrası `loadUsers` →
  // düzenleyici başlığı güncellenir; formdaki yazılanlar korunur). Pasif üye yerel listede yoksa satırın kendisi.
  const editingUserRow = useMemo(() => (editingUser && usersById[editingUser.id]
    ? { ...editingUser, ...usersById[editingUser.id] } : editingUser), [editingUser, usersById])

  /** Üye satırından kullanıcı detayına (2026-09-30). Yönetim yükleyicisi (`/admin/teams/{id}/users`) tam kaydı verir;
   *  yine de yerel kullanıcı listesi (taze, kilit/AD alanlarıyla) üste bindirilir. Satır kısmi bir projeksiyonsa
   *  (sistem rolü yok = beyaz-liste ucu) tam kayıt MEVCUT arama ucundan (`searchUsers`) çekilir — yeni uç yok.
   *  `useCallback` (2026-10-01): `onView` olarak `memo(MemberRow)`'a gider — kimliği render'lar arasında sabit. */
  const openViewUser = useCallback(async (m) => {
    if (!m) return
    const local = usersById[m.id]
    if (local || m.system_role !== undefined) { setViewUser(local ? { ...m, ...local } : m); return }
    try {
      const r = await api.admin.searchUsers({ q: m.username || m.display_name || '', size: 10 })
      const hit = (r?.data || []).find(u => String(u.id) === String(m.id))
        || (r?.data || []).find(u => m.username && String(u.username).toLowerCase() === String(m.username).toLowerCase())
      setViewUser(hit ? { ...m, ...hit } : m)
    } catch { setViewUser(m) }
  }, [usersById])

  /** Bir kullanıcının bağlı olduğu müdür etiketi: adı (çözülebiliyorsa) yoksa sicili. */
  const managerLabelFor = (u) => (u?.manager_id && userMap[u.manager_id]) || u?.manager_sicil || null

  /** Takımın müdürü — TEK kişi. Elle atanmışsa (team.manager_id) o; yoksa takımın bağlı olduğu ilk
   *  yönetici (lider/PO ve üst kademeler elenir; kural utils/teamManager.js). Eskiden üyelerin
   *  müdürlerinin birleşimiydi → iki ad çıkıyordu. Türetilen müdür takımın ÜYESİ DEĞİLDİR — yalnız
   *  bu sütunda durur, üye modalına/sayısına girmez (prod hatası 2026-09-26).
   *  Üyelik = birincil takım VEYA ek üyelik (eskiden yalnız team_id sayılıyordu). Dönen kayıt
   *  kullanıcı KİMLİĞİNİ taşır; süzgeç ada göre değil kimliğe göre eşler (aynı adlı iki müdür). */
  const manualManagerId = (team) => team.manager_id ?? team.managerId ?? null
  const teamManagerEntry = (team) => {
    const manual = manualManagerId(team)
    if (manual != null && userMap[manual]) return { label: userMap[manual], userId: manual, manual: true }
    return resolveTeamManagerEntry(users.filter(u => isTeamMember(u, team.id)), usersById, managerLabelFor,
      team.leader_id ?? team.leaderId ?? null)
  }
  const teamManagerLabel = (team) => teamManagerEntry(team)?.label ?? null
  /** "AD ile üyelik denetimi" modalı için: müdür + ona DOĞRUDAN bağlı üyeler (müdürün kendisi üye sayılmaz). */
  const managerInfoFor = (team) => {
    const e = teamManagerEntry(team)
    if (!e) return null
    const reports = e.userId == null ? [] : users
      .filter(u => isTeamMember(u, team.id) && String(u.manager_id ?? '') === String(e.userId))
      .map(u => u.display_name || u.username)
    return { label: e.label, manual: !!e.manual, reports }
  }

  /** Süzgeç + arama (ad / e-posta / lider adı / müdür adı / açıklama) — istemci tarafı; liste zaten tümü. */
  const filteredTeams = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return teams.filter(tm => {
      const s = stats[String(tm.id)] || {}
      const mgr = teamManagerEntry(tm)
      const mgrLabel = mgr?.label || ''
      const manualMgr = manualManagerId(tm)
      if (needle && ![tm.name, tm.email, tm.description, userMap[tm.leader_id], mgrLabel].some((v) => v && String(v).toLowerCase().includes(needle))) return false
      if (fActive === 'active' && !tm.active) return false
      if (fActive === 'inactive' && tm.active) return false
      if (fManager === 'none' && (manualMgr != null || mgrLabel)) return false
      // Kimlikle eşleş: eskiden görünen ad karşılaştırılıyordu → aynı adlı iki müdürün takımları karışıyordu.
      if (fManager && fManager !== 'none' && String(manualMgr ?? '') !== String(fManager) && String(mgr?.userId ?? '') !== String(fManager)) return false
      if (fAlerts === 'open' && !(Number(s.open_alerts) > 0)) return false
      if (fAlerts === 'none' && Number(s.open_alerts) > 0) return false
      if (fLeader === 'none' && tm.leader_id != null && userMap[tm.leader_id]) return false
      if (fWeekly === 'reminder_on' && !weeklyFlag(tm, 'reminder')) return false
      if (fWeekly === 'reminder_off' && weeklyFlag(tm, 'reminder')) return false
      if (fWeekly === 'availability_on' && !weeklyFlag(tm, 'availability')) return false
      if (fWeekly === 'availability_off' && weeklyFlag(tm, 'availability')) return false
      return true
    })
  }, [teams, stats, users, q, fActive, fManager, fAlerts, fLeader, fWeekly]) // eslint-disable-line react-hooks/exhaustive-deps
  const pager = usePagination(filteredTeams, { listKey: 'admin-teams', preset: 'panel', resetDeps: [q, fActive, fManager, fAlerts, fLeader, fWeekly] })
  const pagedTeams = pager.pageItems
  const managerOptions = useMemo(() => {
    const ids = new Set()
    for (const tm of teams) { const m = manualManagerId(tm); if (m != null && userMap[m]) ids.add(String(m)) }
    for (const tm of teams) { const e = teamManagerEntry(tm); if (e?.userId != null && userMap[e.userId]) ids.add(String(e.userId)) }
    return [{ value: '', label: t('team.filterAny') }, { value: 'none', label: t('team.filterNoManager') }, ...[...ids].map((id) => ({ value: id, label: userMap[id] })).sort((a, b) => a.label.localeCompare(b.label))]
  }, [teams, users]) // eslint-disable-line react-hooks/exhaustive-deps

  // ── Toplu işlem (2026-09-20): sayfadaki seçim → tek istek; sunucu her takımı kendi kapsam zincirinden geçirir ──
  const selectableIds = pagedTeams.filter((tm) => canEditRow(tm.id)).map((tm) => tm.id)
  const allPageSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id))
  const toggleAllPage = () => setSelected((prev) => { const n = new Set(prev); if (allPageSelected) selectableIds.forEach((id) => n.delete(id)); else selectableIds.forEach((id) => n.add(id)); return n })
  const toggleOne = (id) => setSelected((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n })
  async function runBulk(action, extra = {}) {
    const labels = { activate: t('team.bulkActivate'), deactivate: t('team.bulkDeactivate'), set_manager: t('team.bulkSetManager'),
      weekly_reminder_on: t('team.bulkReminderOn'), weekly_reminder_off: t('team.bulkReminderOff'), weekly_availability_on: t('team.bulkAvailOn'), weekly_availability_off: t('team.bulkAvailOff') }
    const ok = await showConfirm({ title: t('team.bulkTitle'), message: t('team.bulkConfirm', selected.size, labels[action] || action), confirmText: t('team.bulkApply'), variant: action === 'deactivate' ? 'danger' : undefined })
    if (!ok) return
    setBulkBusy(true)
    try {
      const res = await api.admin.bulkTeams({ action, ids: [...selected], ...extra })
      if (res?.success) {
        const d = res.data || {}
        if ((d.failed ?? 0) > 0) toast.warning ? toast.warning(t('team.bulkPartial', d.ok ?? 0, d.failed)) : toast.error(t('team.bulkPartial', d.ok ?? 0, d.failed))
        else toast.success(t('team.bulkDone', d.ok ?? selected.size))
        setSelected(new Set()); load(); onTeamsChange?.()
      } else toast.error(res?.error || t('settings.loadError'))
    } catch (e) { toast.error(e?.message || t('settings.loadError')) } finally { setBulkBusy(false) }
  }

  function openAdd() { setForm(emptyTeam); setModal('add'); setMsg(null) }
  function openEdit(team) {
    setForm({ ...team, email: team.email || '', leader_id: String(team.leaderId ?? team.leader_id ?? ''),
      manager_id: String(team.managerId ?? team.manager_id ?? ''),
      weekly_reminder_enabled: weeklyFlag(team, 'reminder'),
      weekly_availability_enabled: weeklyFlag(team, 'availability'),
      weekly_channels: channelsCsv(team),
      quiet: quietFromTeam(team) })
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
    // Sessiz saat: yalnız değiştiyse doğrulanır ve gönderilir — dokunulmamış form bugünkü gövdeyi yollar.
    const initialQuiet = modal === 'add' ? EMPTY_QUIET : quietFromTeam(modal)
    const quietDirty = !quietEqual(form.quiet || EMPTY_QUIET, initialQuiet)
    if (quietDirty && fe.check(quietErrors(form.quiet, t))) return
    setSaving(true)
    try {
      const payload = {
        name: form.name.trim(),
        email: form.email.trim(),
        description: form.description,
        active: form.active,
        leader_id: form.leader_id ? userRefValue(form.leader_id) : null,  // PO optional; opak kimlik (2026-10-08)
        manager_id: form.manager_id ? userRefValue(form.manager_id) : null,  // elle müdür; null → AD zincirinden türet
        weekly_reminder_enabled: !!form.weekly_reminder_enabled,
        weekly_availability_enabled: !!form.weekly_availability_enabled,
        ...(quietDirty ? quietTeamPayload(form.quiet) : {}),
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

  /** Silme: önce ETKİ önizlemesi (bağlı varlıklar + hedef takıma taşı), sonra sil (2026-09-20). */
  function del(id) {
    const team = teams.find(t => t.id === id)
    if (team) setImpactTeam(team)
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
  const userOptions = (placeholder) => [
    { value: '', label: placeholder },
    ...users.map(u => ({ value: u.id, label: `${u.display_name || u.username} (${u.username})` })),
  ]
  const formMsgTone = msg && msg.startsWith('✓') ? 'success' : 'danger'

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('team.title')}</h3>
        <div className="flex items-center gap-2">
          <Button variant="secondary" title={t('team.exportCsv')} onClick={() => {
            const rows = filteredTeams.map(tm => { const s = stats[String(tm.id)] || {}; return [tm.name, tm.email, userMap[tm.leader_id] || '', teamManagerLabel(tm) || '',
              tm.active ? t('team.active') : t('team.inactive'), s.members ?? '', s.domains ?? '', s.monitors ?? '', s.open_alerts ?? '', s.contacts ?? '', s.groups ?? ''] })
            downloadCsv(stampedName('takimlar'), toCsv([t('team.colName'), t('team.colEmail'), t('team.colLeader'), t('team.colManager'), t('team.colActive'),
              t('team.stat.members', '').trim(), t('team.stat.domains', '').trim(), t('team.stat.monitors', '').trim(), t('team.stat.open_alerts', '').trim(), t('team.stat.contacts', '').trim(), t('team.stat.groups', '').trim()], rows))
          }}><Download size={14} /> {t('team.exportCsv')}</Button>
          {isAdmin && <Button variant="success" onClick={openAdd}>{t('team.addBtn')}</Button>}
        </div>
      </div>
      {msg && !modal && <AlertBanner tone={formMsgTone}>{msg}</AlertBanner>}
      {loadError && teams.length === 0 && (
        <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
      )}

      {/* Araç çubuğu — arama + Süzgeçler paneli + sayaç (2026-09-20); shadcn InputGroup + Label */}
      <div className="mb-3" data-testid="tm-toolbar">
        <div className="flex flex-wrap items-center gap-2">
          <ToolbarSearch value={q} onChange={setQ} placeholder={t('team.searchPlaceholder')}
            ariaLabel={t('team.searchLabel')} clearLabel={t('inv.filterClear')} />
          <Button type="button" variant={filtersOpen || filterActive ? 'default' : 'secondary'} size="sm" onClick={() => setFiltersOpen((o) => !o)} aria-expanded={filtersOpen}>
            <SlidersHorizontal size={13} /> {t('inv.filters')}{filterActive ? ` · ${t('inv.filterActive')}` : ''}
          </Button>
          <span className="text-[0.84em] whitespace-nowrap text-muted-foreground">{t('inv.shownOf', filteredTeams.length, teams.length)}</span>
          <div className="flex-1" />
          {(filterActive || q) && <Button type="button" variant="secondary" size="sm" onClick={clearFilters}>{t('inv.filterClear')}</Button>}
        </div>
        {filtersOpen && (
          <FilterPanel label={t('inv.filters')} className="grid-cols-[repeat(auto-fit,minmax(min(200px,100%),1fr))]">
            <FilterField label={t('team.colActive')}>
              <SearchableSelect value={fActive} onChange={setFActive} ariaLabel={t('team.colActive')} searchThreshold={99}
                options={[{ value: '', label: t('team.filterAny') }, { value: 'active', label: t('team.active') }, { value: 'inactive', label: t('team.inactive') }]} />
            </FilterField>
            <FilterField label={t('team.colManager')}>
              <SearchableSelect value={fManager} onChange={setFManager} ariaLabel={t('team.colManager')} searchThreshold={4} options={managerOptions} />
            </FilterField>
            <FilterField label={t('team.filterAlerts')}>
              <SearchableSelect value={fAlerts} onChange={setFAlerts} ariaLabel={t('team.filterAlerts')} searchThreshold={99}
                options={[{ value: '', label: t('team.filterAny') }, { value: 'open', label: t('team.filterAlertsOpen') }, { value: 'none', label: t('team.filterAlertsNone') }]} />
            </FilterField>
            <FilterField label={t('team.colLeader')}>
              <SearchableSelect value={fLeader} onChange={setFLeader} ariaLabel={t('team.colLeader')} searchThreshold={99}
                options={[{ value: '', label: t('team.filterAny') }, { value: 'none', label: t('team.noLeader') }]} />
            </FilterField>
            <FilterField label={t('team.colWeeklyEmails')}>
              <SearchableSelect value={fWeekly} onChange={setFWeekly} ariaLabel={t('team.colWeeklyEmails')} searchThreshold={99}
                options={[{ value: '', label: t('team.filterAny') }, { value: 'reminder_on', label: `${t('team.weeklyReminderShort')}: ${t('team.on')}` }, { value: 'reminder_off', label: `${t('team.weeklyReminderShort')}: ${t('team.off')}` },
                  { value: 'availability_on', label: `${t('team.weeklyAvailabilityShort')}: ${t('team.on')}` }, { value: 'availability_off', label: `${t('team.weeklyAvailabilityShort')}: ${t('team.off')}` }]} />
            </FilterField>
          </FilterPanel>
        )}
      </div>

      {/* Toplu işlem çubuğu — seçim varken (2026-09-20) */}
      {canManage && selected.size > 0 && (
        <div data-testid="tm-bulk-bar" aria-busy={bulkBusy || undefined}
          className="mb-2.5 flex flex-wrap items-center gap-2 rounded-lg border border-primary bg-primary/5 px-3 py-2">
          <span className="mr-1 font-bold">{t('team.selected', selected.size)}</span>
          <Button variant="secondary" size="sm" onClick={() => runBulk('activate')} disabled={bulkBusy}>{t('team.bulkActivate')}</Button>
          <Button variant="destructive" size="sm" onClick={() => runBulk('deactivate')} disabled={bulkBusy}>{t('team.bulkDeactivate')}</Button>
          <span className="w-full sm:w-auto sm:min-w-[180px] sm:flex-[0_1_220px]">
            <SearchableSelect value="" onChange={(v) => v && runBulk(v)} placeholder={t('team.bulkWeekly')} ariaLabel={t('team.bulkWeekly')} searchThreshold={99}
              options={[{ value: '', label: t('team.bulkWeekly') }, { value: 'weekly_reminder_on', label: t('team.bulkReminderOn') }, { value: 'weekly_reminder_off', label: t('team.bulkReminderOff') },
                { value: 'weekly_availability_on', label: t('team.bulkAvailOn') }, { value: 'weekly_availability_off', label: t('team.bulkAvailOff') }]} />
          </span>
          {isAdmin && (
            <span className="w-full sm:w-auto sm:min-w-[180px] sm:flex-[0_1_220px]">
              <SearchableSelect value="" onChange={(v) => v && runBulk('set_manager', { manager_id: v === 'none' ? null : userRefValue(v) })} placeholder={t('team.bulkSetManager')} ariaLabel={t('team.bulkSetManager')} searchThreshold={4}
                options={[{ value: '', label: t('team.bulkSetManager') }, { value: 'none', label: t('team.bulkClearManager') }, ...users.map((u) => ({ value: String(u.id), label: u.display_name || u.username }))]} />
            </span>
          )}
          <Button variant="secondary" size="sm" onClick={() => setSelected(new Set())} disabled={bulkBusy}>{t('usr.bulkClear')}</Button>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border bg-card border-border">
        <Table>
          <TableHeader className="bg-muted/50">
            <TableRow>
              {canManage && (
                <TableHead className="w-7">
                  <Checkbox checked={allPageSelected} onCheckedChange={toggleAllPage} aria-label={t('team.selectAll')} disabled={selectableIds.length === 0} />
                </TableHead>
              )}
              <TableHead>{t('team.colName')}</TableHead>
              <TableHead className="hidden md:table-cell">{t('team.colEmail')}</TableHead>
              <TableHead className="hidden lg:table-cell">{t('team.colLeader')}</TableHead>
              <TableHead className="hidden lg:table-cell">{t('team.colManager')}</TableHead>
              <TableHead>{t('team.colActive')}</TableHead>
              <TableHead className="hidden lg:table-cell">{t('team.colWeeklyEmails')}</TableHead>
              <TableHead>{t('team.colAssets')}</TableHead>
              <TableHead>{t('team.colActions')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filteredTeams.length === 0 && (
              <TableRow>
                <TableCell colSpan={canManage ? 9 : 8} className="p-[18px] text-center text-muted-foreground">
                  {t('team.noResults')}
                </TableCell>
              </TableRow>
            )}
            {pagedTeams.map((team) => (
              <TableRow key={team.id} data-state={selected.has(team.id) ? 'selected' : undefined}>
                {canManage && (
                  <TableCell className="w-7">
                    {canEditRow(team.id) ? <Checkbox checked={selected.has(team.id)} onCheckedChange={() => toggleOne(team.id)} aria-label={t('team.selectOne', team.name)} /> : null}
                  </TableCell>
                )}
                <TableCell>
                  <strong><TeamBadge teamId={team.id} teamName={team.name} size={13}
                    onOpen={() => setMembersTeam(team)} title={t('team.expandMembers')} /></strong>
                  {teamDir.byId.get(Number(team.id))?.noc_team && (
                    <Badge variant="outline" data-slot="team-noc-badge" title={t('noc.ot.teamBadgeTip')}
                      className="ml-1.5 gap-1 rounded-full border-sky-500/40 bg-sky-500/10 font-semibold text-sky-700 dark:text-sky-300">
                      <Headset aria-hidden="true" className="size-3" />{t('noc.ot.teamBadge')}
                    </Badge>
                  )}
                </TableCell>
                <TableCell className="hidden md:table-cell">{team.email || '—'}</TableCell>
                <TableCell className="hidden lg:table-cell">{userMap[team.leader_id] ?? <span className="text-destructive">{t('team.noLeader')}</span>}</TableCell>
                <TableCell className="hidden lg:table-cell">
                  {teamManagerLabel(team) || '—'}
                  {manualManagerId(team) != null && userMap[manualManagerId(team)] && (
                    <span className="ml-1.5 text-xs text-muted-foreground" title={t('team.managerManualTitle')}>
                      {t('team.managerManual')}
                    </span>
                  )}
                </TableCell>
                <TableCell>
                  <ToneBadge tone={team.active ? 'success' : 'danger'}>{team.active ? t('team.active') : t('team.inactive')}</ToneBadge>
                </TableCell>
                <TableCell className="hidden lg:table-cell">
                  <div className="flex flex-col gap-1.5">
                    <WeeklySwitch on={weeklyFlag(team, 'reminder')} disabled={!canToggleWeekly(team.id)}
                      label={t('team.weeklyReminder')} short={t('team.weeklyReminderShort')} onToggle={() => toggleWeekly(team, 'reminder')} />
                    <WeeklySwitch on={weeklyFlag(team, 'availability')} disabled={!canToggleWeekly(team.id)}
                      label={t('team.weeklyAvailability')} short={t('team.weeklyAvailabilityShort')} onToggle={() => toggleWeekly(team, 'availability')} />
                  </div>
                </TableCell>
                <TableCell className="whitespace-normal">
                  <TeamStats s={stats[String(team.id)]} t={t}
                    onMembers={() => (canEditRow(team.id) ? setManageTeam(team) : setMembersTeam(team))}
                    onDomains={() => navigateTo('domains', { i_team: String(team.id) })}
                    onAlerts={() => navigateTo('alerthistory', { team: String(team.id) })} />
                </TableCell>
                <TableCell>
                  <KebabMenu label={t('team.colActions')} rowLabel={team.name} items={[
                    { label: t('team.edit'), onClick: () => openEdit(team), hidden: !canEditRow(team.id) },
                    { label: t('team.manageMembers'), onClick: () => setManageTeam(team), hidden: !canEditRow(team.id) },
                    { label: t('hist.title'), onClick: () => setHistFilter({ id: team.id, name: team.name }) },
                    // Veri Kalitesi (2026-10-10): takımın puanı + düzeltme listesi (sayfa görüş kapsamını sunucuda uygular)
                    { label: t('dq.teamMenu'), onClick: () => navigateTo('dataquality', { dq_team: String(team.id) }) },
                    { label: t('tla.menu'), onClick: () => setLdapAuditTeam(team), hidden: !isAdmin },
                    { label: t('team.delete'), danger: true, onClick: () => del(team.id), hidden: !isAdmin },
                  ]} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {/* Sayfalama — proje standardı PaginationBar (istemci-taraflı, 2026-09-20) */}
      {filteredTeams.length > 0 && <PaginationBar {...pager} />}

      <AdminChangeHistory resource="TEAM" filter={histFilter} onClearFilter={() => setHistFilter(null)} />

      <ModalShell open={modal !== null} onClose={closeModal} size="md"
        icon={modal === 'add' ? UsersRound : PenLine}
        title={modal === 'add' ? t('team.addTitle') : t('team.editTitle')}
        footer={(
          <>
            <Button variant="secondary" onClick={closeModal}>{t('team.cancel')}</Button>
            <Button onClick={save} disabled={saving || !canSave} aria-busy={saving || undefined}>
              {saving ? t('team.saving') : t('team.save')}
            </Button>
          </>
        )}>
        <div className="grid grid-cols-1 items-end gap-x-3 sm:grid-cols-2">
          <Field label={t('team.formName')} required>
            {({ id }) => (
              <Input id={id} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={t('team.formNamePh')} />
            )}
          </Field>
          <Field label={t('team.formEmail')} required>
            {({ id }) => (
              <Input id={id} type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="team@example.com" />
            )}
          </Field>
          <Field label={t('team.formLeader')} hint={t('team.leaderOptionalHint')}>
            {({ id }) => (
              <SearchableSelect id={id}
                value={form.leader_id}
                onChange={v => setForm({ ...form, leader_id: v })}
                placeholder={t('team.selectLeader')}
                searchThreshold={2}
                options={userOptions(t('team.selectLeader'))}
              />
            )}
          </Field>
          <Field label={t('team.formManager')} hint={t('team.managerHint')}>
            {({ id }) => (
              <SearchableSelect id={id}
                value={form.manager_id}
                onChange={v => setForm({ ...form, manager_id: v })}
                placeholder={t('team.selectManager')}
                searchThreshold={2}
                options={userOptions(t('team.selectManager'))}
              />
            )}
          </Field>
          <Field label={t('team.formDesc')} className="col-span-full">
            {({ id }) => (
              <Input id={id} value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            )}
          </Field>
          <FormCheckbox className="col-span-full mb-3.5" checked={!!form.active} label={t('team.formActive')}
            onChange={(v) => setForm({ ...form, active: v })} />
          {/* Haftalık e-postalar — yeni takımda İKİSİ DE KAPALI açılır; takım sonradan kendi üyeleri
              üzerinden açar. E-posta ancak takım AKTİF ve ilgili anahtar açıkken gider. */}
          <FieldSet className="col-span-full gap-2" data-testid="tm-weekly-form">
            <FieldLegend variant="label" className="mb-1 text-[13px] font-semibold">{t('team.colWeeklyEmails')}</FieldLegend>
            <ToggleRow checked={!!form.weekly_reminder_enabled} label={t('team.weeklyReminder')}
              onChange={(v) => setForm({ ...form, weekly_reminder_enabled: v })} />
            <ToggleRow checked={!!form.weekly_availability_enabled} label={t('team.weeklyAvailability')}
              onChange={(v) => setForm({ ...form, weekly_availability_enabled: v })} />
            <span className="text-xs text-muted-foreground">{t('team.weeklyHint')}</span>
            <div className="mt-1 flex flex-col gap-1">
              <TagInput label={t('team.weeklyChannels')} value={form.weekly_channels || ''}
                onChange={(v) => setForm({ ...form, weekly_channels: v })} placeholder={t('team.weeklyChannelsPh')} />
              <span className="text-xs text-muted-foreground">{t('team.weeklyChannelsHint')}</span>
            </div>
          </FieldSet>
          {/* Sessiz saatler (2026-10-01, onaylı öneri 15) — opt-in; boş = bildirim zamanı bugünkü gibi. Pencerede KRİTİK OLMAYAN
              alarm bildirimleri ertelenir ve pencere bitince tek özet e-postasıyla gider. */}
          <FieldSet className="col-span-full mt-2 gap-2 border-t pt-3" data-testid="tm-quiet-form">
            <FieldLegend variant="label" className="mb-0.5 flex items-center gap-1.5 text-[13px] font-semibold">
              <QuietIcon aria-hidden="true" className="size-4 text-muted-foreground" /> {t('team.quietTitle')}
            </FieldLegend>
            <span className="text-xs text-muted-foreground">{t('team.quietDesc')}</span>
            <QuietHoursFields value={form.quiet || EMPTY_QUIET} fieldProps={fe.fieldProps}
              onChange={(next, key) => { setForm({ ...form, quiet: next }); if (key === 'clear') fe.reset(); else if (key) fe.clear(key) }} />
          </FieldSet>
        </div>
        {msg && <AlertBanner tone={formMsgTone} className="mt-2">{msg}</AlertBanner>}
      </ModalShell>

      {manageTeam && (
        <TeamMembersManager team={manageTeam} users={users} canManage={canEditRow(manageTeam.id)}
          onClose={() => setManageTeam(null)} onChanged={() => { loadStats(); setMembersNonce(n => n + 1); onTeamsChange?.() }} />
      )}
      {ldapAuditTeam && (
        <TeamLdapAuditModal team={ldapAuditTeam} managerInfo={managerInfoFor(ldapAuditTeam)}
          onClose={() => setLdapAuditTeam(null)}
          onChanged={() => { load(); loadUsers(); setMembersNonce(n => n + 1); onTeamsChange?.() }} />
      )}
      {impactTeam && (
        <TeamDeleteImpactModal team={impactTeam} teams={teams} onClose={() => setImpactTeam(null)}
          onDeleted={(deleted) => { setImpactTeam(null); if (membersTeam?.id === impactTeam.id) setMembersTeam(null); load(); if (deleted) onTeamsChange?.() }} />
      )}
      {/* usersById BİLEREK verilmez: verilince üye kartları üyelerden yukarı 2 kademe yönetim zinciri
          yürüyüp müdürü ve onun müdürünü ÜYE ızgarasına ekliyordu — takımda olmayan Kullanıcı X "takımın
          içinde" görünüyordu (prod hatası 2026-09-26). Müdür, üye kartında ALAN olarak (managerLabelFor)
          ve satırdaki "Takım Müdürü" sütununda ayrı durur. `teamManager` = o sütunla AYNI kayıt (tüm kullanıcılardan
          türetilir); pencere başlığındaki Takım Müdürü çipi sütunla çelişmesin (2026-09-28 yeniden tasarım). */}
      <TeamMembersModal open={!!membersTeam} team={membersTeam} onClose={() => setMembersTeam(null)}
        canManage={canManage} onEditUser={setEditingUser} onViewUser={canManage ? openViewUser : undefined}
        loadMembers={loadTeamMembers}
        managerLabelFor={managerLabelFor} refreshKey={membersNonce}
        teamManager={membersTeam ? teamManagerEntry(membersTeam) : undefined} />
      {/* Kullanıcı detayı — takım penceresinin ÜSTÜNDE (stacked); Escape/scrim yalnız bu katmanı kapatır, takım penceresi
          açık kalır. Düzenle: detay kapanır, paylaşılan UserEditModal açılır (üye kartındaki kalemle aynı yol). */}
      {viewUser && (
        <UserDetailPanel stacked user={usersById[viewUser.id] ? { ...viewUser, ...usersById[viewUser.id] } : viewUser}
          teams={teams} isAdmin={isAdmin} globalAdmin={globalAdmin} initialTab={viewTab}
          onClose={() => { setViewUser(null); setViewTab('overview') }}
          onChanged={() => { loadUsers(); setMembersNonce(n => n + 1) }}
          onEdit={canManage ? () => { const u = viewUser; setViewUser(null); setViewTab('overview'); setEditingUser(u) } : undefined} />
      )}
      {/* Paylaşılan kullanıcı düzenleyicisi (2026-10-02): Kullanıcılar sekmesiyle AYNI korumalar — görüntüleyenin rolü
          (takım yöneticisi yalnız USER/TEAM_ADMIN verir, kayıtta takım kendi takımına sabitlenir), kendi hesabı kilidi. */}
      <UserEditModal
        user={editingUserRow}
        teams={teams}
        viewerRole={systemRole} globalAdmin={globalAdmin} ownTeamId={ownTeamId} currentUsername={currentUsername}
        onClose={() => setEditingUser(null)}
        onSaved={() => {
          loadUsers()
          setMembersNonce(n => n + 1)
        }}
        onChanged={() => { loadUsers(); setMembersNonce(n => n + 1) }}
        onOpenDirectory={isAdmin ? (u) => { setEditingUser(null); setViewTab('directory'); setViewUser(u) } : undefined}
      />
    </div>
  )
}

/** Satır varlık sayaçları (2026-09-20): üye/domain/alarm tıklanır, diğerleri bilgi. Sayaç yoksa (henüz yüklenmedi) boş.
 *  Tıklanabilen sayaç shadcn Button (outline, hap biçimi), bilgi sayacı shadcn Badge. */
function TeamStats({ s, t, onMembers, onDomains, onAlerts }) {
  if (!s) return null
  const chip = (key, val, onClick, tip, alert = false, labelOverride = null) => {
    const n = Number(val ?? 0)
    const cls = cn('h-auto rounded-full px-2 py-px text-[0.76em] font-semibold', n === 0 && 'opacity-55',
      alert && 'border-destructive/40 text-destructive')
    const label = labelOverride || t(`team.stat.${key}`, n)
    return onClick
      ? <Button type="button" key={key} variant="outline" size="xs" className={cn(cls, 'hover:border-primary hover:text-primary')} onClick={onClick} title={tip}>{label}</Button>
      : <Badge key={key} variant="outline" className={cls} title={tip}>{label}</Badge>
  }
  // Pasif üyeler (2026-10-02): toplam üyeye dâhil; varsa sayaç "N aktif · M pasif" yazar (members_inactive ek alan).
  const inactiveMembers = Number(s.members_inactive ?? 0)
  const membersLabel = inactiveMembers > 0
    ? t('team.stat.membersSplit', Math.max(0, Number(s.members ?? 0) - inactiveMembers), inactiveMembers) : null
  return (
    <div className="flex max-w-[260px] flex-wrap gap-1" data-testid="team-stats">
      {chip('members', s.members, onMembers, t('team.statMembersTip'), false, membersLabel)}
      {chip('domains', s.domains, onDomains, t('team.statDomainsTip'))}
      {chip('monitors', s.monitors)}
      {chip('open_alerts', s.open_alerts, onAlerts, t('team.statAlertsTip'), Number(s.open_alerts) > 0)}
      {chip('contacts', s.contacts)}
      {chip('groups', s.groups)}
    </div>
  )
}
