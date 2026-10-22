import { useState, useEffect, useMemo, useCallback, useId } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import {
  UsersRound, PenLine, Headset, Plus, CircleCheck, CirclePause, TriangleAlert, Siren, History, Gauge, ScanSearch,
  Trash2, Users, UserPlus, BellRing, CalendarClock, Share2,
} from 'lucide-react'
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
import OnCallScheduleModal from './oncall/OnCallScheduleModal.jsx'   // nöbet çizelgesi (2026-10-11)
import { navigateTo, applyTabView } from '../../utils/navigate.js'
import { Download, SlidersHorizontal } from 'lucide-react'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import { useElementWidthState } from '../../hooks/useElementWidth.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import TeamGapsPanel, { TEAM_TOUCH_CHIP } from './teams/TeamGapsPanel.jsx'
import TeamGapBadge from './teams/TeamGapBadge.jsx'
import {
  TEAM_GAPS, CORE_GAPS, TEAM_SORTS, DEFAULT_TEAM_SORT, teamGaps, gapCounts, matchesGaps, hasCoreGap, parseGapParam,
  gapParam, sortTeams,
} from './teams/teamGaps.js'
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
import { changedFields } from '../../utils/changedFields.js'

// Haftalık e-postalar opt-in: YENİ takım ikisi de kapalı doğar (backend de createTeam'de false yazar).
// quiet: sessiz saat form değeri (2026-10-01) — yalnız DEĞİŞTİYSE gövdeye girer (dokunulmamış form = bugünkü gövde).
const emptyTeam = { name: '', email: '', description: '', active: true, leader_id: '', manager_id: '',
  weekly_reminder_enabled: false, weekly_availability_enabled: false, weekly_channels: '', quiet: EMPTY_QUIET }

/** Takım satırı → düzenleme formu (açılışta ve "değişen alanlar" tabanında AYNI dönüşüm, 20.165.1). */
function teamToForm(team) {
  return { ...team, email: team.email || '', leader_id: String(team.leaderId ?? team.leader_id ?? ''),
    manager_id: String(team.managerId ?? team.manager_id ?? ''),
    weekly_reminder_enabled: weeklyFlag(team, 'reminder'),
    weekly_availability_enabled: weeklyFlag(team, 'availability'),
    weekly_channels: channelsCsv(team),
    quiet: quietFromTeam(team) }
}

/** Form → PUT/POST gövdesi (sessiz saat ayrı eklenir). Lider / müdür opak kullanıcı kimliği. */
function teamPayload(form) {
  return {
    name: String(form.name ?? '').trim(),
    email: String(form.email ?? '').trim(),
    description: form.description,
    active: form.active,
    leader_id: form.leader_id ? userRefValue(form.leader_id) : null,  // PO optional; opak kimlik (2026-10-08)
    manager_id: form.manager_id ? userRefValue(form.manager_id) : null,  // elle müdür; null → AD zincirinden türet
    weekly_reminder_enabled: !!form.weekly_reminder_enabled,
    weekly_availability_enabled: !!form.weekly_availability_enabled,
  }
}

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

/** Takım satırındaki haftalık e-posta anahtarı — shadcn Switch + kısa etiket (tam ad aria-label/title). Etiket dar
 *  sütunda SARAR (kesilmez, taşmaz). */
function WeeklySwitch({ on, disabled, onToggle, label, short }) {
  const id = useId()
  return (
    <span className="flex min-w-0 items-center gap-2 max-sm:min-h-10 pointer-coarse:min-h-10">
      <Switch id={id} checked={!!on} disabled={disabled} aria-label={label} title={label} onCheckedChange={onToggle} />
      <label htmlFor={id} className={cn('flex min-w-0 items-center self-stretch text-xs leading-tight text-muted-foreground', disabled ? 'cursor-default' : 'cursor-pointer')}>{short}</label>
    </span>
  )
}

/**
 * Liste görünümü KAP genişliğine göre (2026-10-11, kullanıcı: "tablo araları çok açık … sayfaya sığmıyor"): kap bu
 * değerin altındaysa kartlar, üstündeyse sıkı sabit düzenli tablo. Tablonun sabit sütunları (seçim, haftalık, işlemler)
 * ~330 px; kalan üç sütun bu eşikte ≥ 190 px alır — ad / kişi adı sarar, yatay kaydırma doğmaz. Ölçüm yoksa (jsdom,
 * ilk boyama) telefon kırılımına düşülür.
 */
export const TEAM_TABLE_MIN_PX = 900
/** Kart görünümünde iki sütuna geçilen kap genişliği. */
const TEAM_CARDS_TWO_COL_PX = 640


/**
 * `onJump(tabId, params)`: Yönetim paneli içinde başka bir alt sekmeye SÜZGEÇLİ geçiş (AdminPanel.jump) — eksik
 * bilgi düzeltme kısayolları (eskalasyon kişisi / bildirim grubu) takımı süzülmüş açar. Verilmezse `applyTabView`.
 */
export default function TeamManager({ systemRole, ownTeamId, myTeamIds, onTeamsChange, globalAdmin = false, currentUsername = null, onJump }) {
  const t = useT()
  const toast = useToast()
  const phone = useIsMobile()
  const [boxWidth, setBoxEl] = useElementWidthState()
  const cards = boxWidth > 0 ? boxWidth < TEAM_TABLE_MIN_PX : phone
  const cardsTwoCol = boxWidth >= TEAM_CARDS_TWO_COL_PX
  // 7/24 izleme ekibi takımı rozeti (2026-10-04): takım rehberinin `noc_team` bayrağı (sağlayıcı yoksa rozet yok)
  const teamDir = useTeamDirectory()
  const isAdmin = systemRole === 'ADMIN'
  // Takım ekle / sil / AD üyelik denetimi YALNIZ global yönetici (20.165.1, UIA-A6): createTeam / deleteTeam / impact /
  // LdapMembershipController sunucuda `isGlobalAdmin` ister — kapsamlı müdüre (ADMIN, viewTeamIds) düğmeler 403'tü.
  const isGlobal = isAdmin && !!globalAdmin
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  const canEditRow = (rowTeamId) => isAdmin || (isTeamAdmin && rowTeamId === ownTeamId)
  // ADMIN / AUDIT üyeyi yalnız global yönetici düzenler (sunucu requireCanAdministerTarget; 20.165.1 UIA-13) — diğerine
  // üye kartındaki kalem düzenleyici yerine salt-okunur ayrıntıyı açar.
  const elevatedTarget = (u) => !isGlobal && (u?.system_role === 'ADMIN' || u?.system_role === 'AUDIT')
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
  const [statsReady, setStatsReady] = useState(false)   // sayaçlar geldi mi (eksik kuralları için)
  const [usersReady, setUsersReady] = useState(false)   // kullanıcı listesi geldi mi (lider / müdür eksikleri için)
  const [usersFailed, setUsersFailed] = useState(false) // liste okunamadı → "yükleniyor" sonsuza dek kalmaz, "okunamadı" denir
  const [statsFailed, setStatsFailed] = useState(false) // sayaçlar okunamadı → rozet "gelince görünür" demez, "okunamadı" der
  const [userIdsAll, setUserIdsAll] = useState(() => new Set())   // listedeki TÜM kimlikler (pasifler dahil) — "pasif lider" ≠ "görünmeyen lider"
  const [manageTeam, setManageTeam] = useState(null)    // üye yönetimi modalı
  const [impactTeam, setImpactTeam] = useState(null)    // silme etki modalı
  const [ldapAuditTeam, setLdapAuditTeam] = useState(null)   // AD ile üyelik denetimi (2026-09-26)
  const [oncallTeam, setOncallTeam] = useState(null)         // nöbet çizelgesi penceresi (2026-10-11)
  const [oncallNow, setOncallNow] = useState({})             // takım kimliği → şu anki nöbetçi {name, until}
  const [q, setQ]       = useState(() => readUrlParam('g_q', ''))   // URL'de (g_q)
  // Zengin süzgeçler (2026-09-20, kullanıcı bildirimi): durum, müdür, açık alarm, lider, haftalık — URL'de g_*
  const [fActive, setFActive]   = useState(() => readUrlParam('g_active', ''))     // '' | 'active' | 'inactive'
  const [fManager, setFManager] = useState(() => readUrlParam('g_mgr', ''))        // kullanıcı id | 'none'
  const [fAlerts, setFAlerts]   = useState(() => readUrlParam('g_alerts', ''))     // '' | 'open' | 'none'
  const [fLeader, setFLeader]   = useState(() => readUrlParam('g_leader', ''))     // '' | 'none'
  const [fWeekly, setFWeekly]   = useState(() => readUrlParam('g_weekly', ''))     // '' | 'reminder_on' | 'reminder_off' | 'availability_on' | 'availability_off'
  // Eksik bilgi süzgeci (2026-10-11): seçili eksikler (g_miss, CSV) + eşleşme (g_mmode: any | all) + sıralama (g_sort)
  const [gapSel, setGapSel]   = useState(() => parseGapParam(readUrlParam('g_miss', '')))
  const [gapMode, setGapMode] = useState(() => (readUrlParam('g_mmode', '') === 'all' ? 'all' : 'any'))
  const [sort, setSort]       = useState(() => { const v = readUrlParam('g_sort', ''); return TEAM_SORTS.includes(v) ? v : DEFAULT_TEAM_SORT })
  useUrlQuerySync({ g_q: q, g_active: fActive, g_mgr: fManager, g_alerts: fAlerts, g_leader: fLeader, g_weekly: fWeekly,
    g_miss: gapParam(gapSel), g_mmode: gapSel.length >= 2 && gapMode === 'all' ? 'all' : null,
    g_sort: sort === DEFAULT_TEAM_SORT ? null : sort })
  const filterActive = !!(fActive || fManager || fAlerts || fLeader || fWeekly)
  const [filtersOpen, setFiltersOpen] = useState(() => !!(readUrlParam('g_active', '') || readUrlParam('g_mgr', '') || readUrlParam('g_alerts', '') || readUrlParam('g_leader', '') || readUrlParam('g_weekly', '')))
  const clearFilters = () => { setQ(''); setFActive(''); setFManager(''); setFAlerts(''); setFLeader(''); setFWeekly(''); setGapSel([]); setGapMode('any') }
  const toggleGap = (key) => setGapSel((prev) => parseGapParam((prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]).join(',')))
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
      if (res?.success) { setTeams(res.data); setLoadError(null); loadOncall(res.data) }
      else setLoadError(res?.error || t('settings.loadError'))
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
    loadStats()
  }

  /** Şu anki nöbetçiler (2026-10-11) — ayrı, en iyi çaba istek: gelmezse satırda nöbetçi satırı yok. */
  async function loadOncall(list = teams) {
    try {
      const ids = (list || []).map((x) => x.id).slice(0, 200)
      if (!ids.length || !api.oncall?.current) return
      const r = await api.oncall.current(ids)
      if (r?.success) setOncallNow(Object.fromEntries((r.data || []).map((p) => [String(p.team_id), p])))
    } catch { /* nöbetçi satırı gösterilmez */ }
  }

  /** Satır sayaçları — ayrı istek: liste sayaç gecikse de gelir (best-effort). Gelmezse sayaca dayalı eksikler
   *  (üye, eskalasyon kişisi, grup, varlık) "bilinmiyor" kalır — eksik SAYILMAZ. */
  async function loadStats() {
    try {
      const r = await api.admin.teamStats?.()
      if (r?.success) { setStats(r.data || {}); setStatsReady(true); setStatsFailed(false) } else setStatsFailed(true)
    } catch { setStatsFailed(true) /* sütun boş kalır; sayaca dayalı eksikler "okunamadı" */ }
  }

  async function loadUsers() {
    // Hata dalı (2026-10-11): istek düşerse yakalanmamış söz reddi olmasın; lider / müdür eksikleri "bilinmiyor" kalır.
    try {
      const res = await api.admin.getUsers()
      if (res?.success) {   // data null gelirse ekran cokmesin
        const all = res.data ?? []
        setUsers(all.filter(u => u.active))
        setUserIdsAll(new Set(all.map((u) => String(u.id))))
        setUsersReady(true)
        setUsersFailed(false)
      } else setUsersFailed(true)
    } catch { setUsersFailed(true) /* lider/müdür adları "okunamadı" gösterilir — eksik SAYILMAZ */ }
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
  const teamManagerLabel = (team) => managerOf(team)?.label ?? null
  /** "AD ile üyelik denetimi" modalı için: müdür + ona DOĞRUDAN bağlı üyeler (müdürün kendisi üye sayılmaz). */
  const managerInfoFor = (team) => {
    const e = teamManagerEntry(team)
    if (!e) return null
    const reports = e.userId == null ? [] : users
      .filter(u => isTeamMember(u, team.id) && String(u.manager_id ?? '') === String(e.userId))
      .map(u => u.display_name || u.username)
    return { label: e.label, manual: !!e.manual, reports }
  }

  /** Takım müdürü kayıtları — kullanıcı listesi / takımlar değişince BİR KEZ çözülür (süzgeç, eksik kuralı, satır ve
   *  CSV aynı kaydı okur; eskiden her render'da takım × kullanıcı taranıyordu). */
  const managerEntries = useMemo(() => new Map(teams.map((tm) => [tm.id, teamManagerEntry(tm)])),
    [teams, users]) // eslint-disable-line react-hooks/exhaustive-deps
  const managerOf = (tm) => (managerEntries.has(tm.id) ? managerEntries.get(tm.id) : teamManagerEntry(tm))
  const leaderIdOf = (tm) => tm.leader_id ?? tm.leaderId ?? null
  const statsOf = (tm) => stats[String(tm.id)] || null

  /** Takım başına eksikler (2026-10-11) — sayaç / kullanıcı listesi gelmeden ilgili kurallar "bilinmiyor" (atlanır). */
  // Kullanıcı listesi kurum geneli mi? `/admin/users` global görüntüleyiciye (global yönetici + AUDIT) TÜM kullanıcıları,
  // diğerlerine yalnız kapsamındaki takımların üyelerini döndürür (2026-10-12).
  const usersListComplete = globalAdmin || systemRole === 'AUDIT'
  /**
   * Atanmış kişinin durumu: `ok` (etkin, listede) | `missing` (atanmamış ya da PASİF — gerçek eksik) | `unknown` (atanmış ama
   * kapsamlı görüntüleyicinin listesinde yok — başka takımdan bir kişi; "yok" denmez).
   */
  const personState = (id) => {
    if (id == null) return 'missing'
    if (userMap[id]) return 'ok'
    if (userIdsAll.has(String(id))) return 'missing'
    return usersListComplete ? 'missing' : 'unknown'
  }
  const gapIndex = useMemo(() => {
    const m = new Map()
    const unknown = new Set()   // lideri / müdürü bu görüntüleyiciye göre bilinmeyen takımlar (rozet "henüz tam denetlenmedi")
    const leaderUnknown = new Set()
    for (const tm of teams) {
      const lid = leaderIdOf(tm)
      const manual = manualManagerId(tm)
      const ls = usersReady ? personState(lid) : null
      const hasLeader = ls == null || ls === 'unknown' ? null : ls === 'ok'
      const hasManager = !usersReady ? null : managerEntries.get(tm.id)?.label ? true
        : manual != null && personState(manual) === 'unknown' ? null : false
      if (ls === 'unknown') leaderUnknown.add(tm.id)
      if (usersReady && (hasLeader == null || hasManager == null)) unknown.add(tm.id)
      m.set(tm.id, teamGaps(tm, {
        stats: statsReady ? statsOf(tm) : null,
        hasLeader,
        hasManager,
      }))
    }
    return { gaps: m, unknown, leaderUnknown }
  }, [teams, stats, statsReady, usersReady, userMap, userIdsAll, managerEntries, usersListComplete]) // eslint-disable-line react-hooks/exhaustive-deps
  const gapsById = gapIndex.gaps
  const gapsOf = (tm) => gapsById.get(tm.id) ?? []
  const gapKnown = (key) => {
    const g = TEAM_GAPS.find((x) => x.key === key)
    if (g?.stats) return statsReady
    return key === 'leader' || key === 'manager' ? usersReady : true
  }
  // Girdisi OKUNAMAYAN eksik: çipte "…" (yükleniyor) değil "—" (2026-10-12)
  const gapFailed = (key) => {
    const g = TEAM_GAPS.find((x) => x.key === key)
    if (g?.stats) return statsFailed && !statsReady
    return key === 'leader' || key === 'manager' ? usersFailed && !usersReady : false
  }

  /** Süzgeç + arama (ad / e-posta / lider adı / müdür adı / açıklama) — istemci tarafı; liste zaten tümü.
   *  Eksik bilgi süzgeci HARİÇ: eksik çiplerinin sayıları bu kümeden sayılır (seçilen eksik kendi sayısını değiştirmez). */
  const baseTeams = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return teams.filter(tm => {
      const s = stats[String(tm.id)] || {}
      const mgr = managerOf(tm)
      const mgrLabel = mgr?.label || ''
      const manualMgr = manualManagerId(tm)
      if (needle && ![tm.name, tm.email, tm.description, userMap[tm.leader_id], mgrLabel].some((v) => v && String(v).toLowerCase().includes(needle))) return false
      if (fActive === 'active' && !tm.active) return false
      if (fActive === 'inactive' && tm.active) return false
      // Kullanıcı listesi gelmeden türetilen müdür BİLİNMİYOR — "müdür yok" süzgecine girmez (2026-10-12)
      if (fManager === 'none' && (manualMgr != null || mgrLabel || !usersReady)) return false
      // Kimlikle eşleş: eskiden görünen ad karşılaştırılıyordu → aynı adlı iki müdürün takımları karışıyordu.
      if (fManager && fManager !== 'none' && String(manualMgr ?? '') !== String(fManager) && String(mgr?.userId ?? '') !== String(fManager)) return false
      if (fAlerts && !statsReady) return false   // sayaçlar yokken alarm durumu BİLİNMİYOR — "alarmsız" listelenmez
      if (fAlerts === 'open' && !(Number(s.open_alerts) > 0)) return false
      if (fAlerts === 'none' && Number(s.open_alerts) > 0) return false
      // "Lider yok" süzgeci yalnız GERÇEKTEN eksik lideri listeler: görünmeyen (başka takımdan) lider eksik sayılmaz
      // Kullanıcı listesi gelmeden / okunamadıysa atanmış lider BİLİNMİYOR — "lider yok" süzgecine girmez (2026-10-12)
      if (fLeader === 'none' && tm.leader_id != null && (!usersReady || userMap[tm.leader_id] || gapIndex.leaderUnknown.has(tm.id))) return false
      if (fWeekly === 'reminder_on' && !weeklyFlag(tm, 'reminder')) return false
      if (fWeekly === 'reminder_off' && weeklyFlag(tm, 'reminder')) return false
      if (fWeekly === 'availability_on' && !weeklyFlag(tm, 'availability')) return false
      if (fWeekly === 'availability_off' && weeklyFlag(tm, 'availability')) return false
      return true
    })
  }, [teams, stats, users, managerEntries, q, fActive, fManager, fAlerts, fLeader, fWeekly]) // eslint-disable-line react-hooks/exhaustive-deps
  const gapCountMap = useMemo(() => gapCounts(baseTeams, gapsOf), [baseTeams, gapsById]) // eslint-disable-line react-hooks/exhaustive-deps
  const filteredTeams = useMemo(() => sortTeams(baseTeams.filter((tm) => matchesGaps(gapsOf(tm), gapSel, gapMode)), sort, { gapsOf, statsOf }),
    [baseTeams, gapsById, gapSel, gapMode, sort, stats]) // eslint-disable-line react-hooks/exhaustive-deps
  const pager = usePagination(filteredTeams, { listKey: 'admin-teams', preset: 'panel', resetDeps: [q, fActive, fManager, fAlerts, fLeader, fWeekly, gapParam(gapSel), gapMode, sort] })
  const pagedTeams = pager.pageItems
  const managerOptions = useMemo(() => {
    const ids = new Set()
    for (const tm of teams) { const m = manualManagerId(tm); if (m != null && userMap[m]) ids.add(String(m)) }
    for (const tm of teams) { const e = managerOf(tm); if (e?.userId != null && userMap[e.userId]) ids.add(String(e.userId)) }
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
    setForm(teamToForm(team))
    setModal(team)
    setMsg(null)
  }
  function closeModal() { setModal(null); setMsg(null) }

  async function save() {
    setMsg(null)
    const isAdd = modal === 'add'
    // Yalnız DEĞİŞEN e-posta doğrulanır (sunucu kuralı; saklı eski değer muaf — 20.165.1 checklist #9)
    const emailChanged = isAdd || form.email.trim() !== String(modal?.email || '').trim()
    if (emailChanged && form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      setMsg(t('team.emailInvalid'))
      return
    }
    // Sessiz saat: yalnız değiştiyse doğrulanır ve gönderilir — dokunulmamış form bugünkü gövdeyi yollar.
    const initialQuiet = modal === 'add' ? EMPTY_QUIET : quietFromTeam(modal)
    const quietDirty = !quietEqual(form.quiet || EMPTY_QUIET, initialQuiet)
    if (quietDirty && fe.check(quietErrors(form.quiet, t))) return
    // Düzenlemede YALNIZ DEĞİŞEN alanlar gider (20.165.1, UIA-A11): tam gövde, pencere açıkken bir üyenin satırdan çevirdiği
    // haftalık anahtarı (dar uç) eski değere döndürüyordu. Lider temizlenirse `leader_id: ''` gider (UIA-A10 — sunucu
    // `null`'u "dokunma" sayar; boş dize temizler).
    const full = teamPayload(form)
    let payload = full
    if (!isAdd) {
      payload = changedFields(full, teamPayload(teamToForm(modal)))
      if ('leader_id' in payload && payload.leader_id == null) payload.leader_id = ''
    }
    if (quietDirty) Object.assign(payload, quietTeamPayload(form.quiet))
    const nextCh = channelsList(form.weekly_channels)
    const prevCh = isAdd ? [] : channelsList(channelsCsv(modal))
    const channelsDirty = JSON.stringify(nextCh) !== JSON.stringify(prevCh)
    if (!isAdd && Object.keys(payload).length === 0 && !channelsDirty) { closeModal(); return }   // değişiklik yok → istek yok
    setSaving(true)
    try {
      const editedId = isAdd ? null : modal.id
      const res = isAdd
        ? await api.admin.createTeam(payload)
        : Object.keys(payload).length > 0 ? await api.admin.updateTeam(editedId, payload) : { success: true }
      if (res?.success) {
        // Kanal şablonu ayrı (dar) uçtan gider: yalnız değiştiyse; yeni takımda dolu girildiyse (2026-09-13)
        const chId = isAdd ? res.data?.id : editedId
        if (chId && channelsDirty) {
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
    } catch (e) {
      setMsg(e?.message || t('team.saveError'))
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

  // ── Eksik bilgi düzeltme kısayolları (2026-10-11) — yalnız kullanıcının yapabildiği düzeltmeler bağlanır ──
  const jumpAdmin = (tabId, params) => (onJump ? onJump(tabId, params) : applyTabView('admin', { g_tab: tabId, ...params }))
  const gapActionsFor = (team) => {
    const editable = canEditRow(team.id)
    const edit = editable ? () => openEdit(team) : null
    return {
      email: edit, leader: edit, manager: edit, description: edit,
      members: editable ? () => setManageTeam(team) : null,
      inactive_members: () => setMembersTeam(team),
      contacts: () => jumpAdmin('contacts', { g_team: String(team.id) }),
      groups: () => jumpAdmin('notifyGroups', { g_team: String(team.id) }),
      assets: () => navigateTo('domains', { i_team: String(team.id) }),
    }
  }

  // ── Özet kartları (2026-10-11): toplam / aktif / pasif / eksik bilgisi olan / alarmdaki — tıklanınca süzer ──
  const activeCount = teams.filter((tm) => tm.active).length
  const coreGapTeams = teams.filter((tm) => hasCoreGap(gapsOf(tm))).length
  const alertTeams = teams.filter((tm) => Number(statsOf(tm)?.open_alerts) > 0).length
  const coreSelected = gapSel.length === CORE_GAPS.length && CORE_GAPS.every((k) => gapSel.includes(k)) && gapMode === 'any'
  const kpiActive = coreSelected ? 'gaps' : fAlerts === 'open' ? 'alerts' : fActive === 'active' ? 'active' : fActive === 'inactive' ? 'inactive' : null
  const kpis = [
    { key: 'total', Icon: UsersRound, cls: 'total', label: t('team.kpi.total'), value: teams.length, hint: t('team.kpi.totalHint') },
    { key: 'active', Icon: CircleCheck, cls: 'valid', label: t('team.kpi.active'), value: activeCount },
    { key: 'inactive', Icon: CirclePause, cls: 'paused', label: t('team.kpi.inactive'), value: teams.length - activeCount },
    { key: 'gaps', Icon: TriangleAlert, cls: 'warning', label: t('team.kpi.gaps'), value: usersReady && statsReady ? coreGapTeams : (usersFailed || statsFailed) ? '—' : '…', hint: t('team.kpi.gapsHint') },
    { key: 'alerts', Icon: Siren, cls: 'critical', label: t('team.kpi.alerts'), value: statsReady ? alertTeams : statsFailed ? '—' : '…', hint: t('team.kpi.alertsHint') },
  ]
  const onKpi = (key) => {
    if (key === 'total') { clearFilters(); return }
    if (key === 'active' || key === 'inactive') { setFActive((v) => (v === key ? '' : key)); return }
    if (key === 'alerts') { setFAlerts((v) => (v === 'open' ? '' : 'open')); return }
    if (key === 'gaps') { if (coreSelected) setGapSel([]); else { setGapSel([...CORE_GAPS]); setGapMode('any') } }
  }

  /** CSV — süzülmüş liste (eski sütunlar aynı sırada) + sonda eksik bilgiler. */
  const exportCsv = () => {
    const rows = filteredTeams.map(tm => { const s = stats[String(tm.id)] || {}
      const leader = userMap[tm.leader_id] || (gapIndex.leaderUnknown.has(tm.id) ? t('team.leaderNotVisible') : '')
      const gaps = gapsOf(tm).map((k) => t(`team.gap.${k}.label`))
      if (gapIndex.unknown.has(tm.id) || !statsReady || !usersReady) gaps.push(t('team.gap.unknown'))   // denetlenemeyen kural "eksiksiz" görünmesin
      return [tm.name, tm.email, leader, teamManagerLabel(tm) || '',
        tm.active ? t('team.active') : t('team.inactive'), s.members ?? '', s.domains ?? '', s.monitors ?? '', s.open_alerts ?? '', s.contacts ?? '', s.groups ?? '',
        gaps.join('; ')] })
    downloadCsv(stampedName('takimlar'), toCsv([t('team.colName'), t('team.colEmail'), t('team.colLeader'), t('team.colManager'), t('team.colActive'),
      t('team.stat.members', '').trim(), t('team.stat.domains', '').trim(), t('team.stat.monitors', '').trim(), t('team.stat.open_alerts', '').trim(), t('team.stat.contacts', '').trim(), t('team.stat.groups', '').trim(),
      t('team.csvGaps')], rows))
  }

  // ── Satır / kart parçaları — tablo ve kart görünümü AYNI parçaları çizer ──
  const nameBlock = (team) => (
    <div className="flex min-w-0 flex-col gap-1">
      <div className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
        <strong className="min-w-0 text-[0.95em]"><TeamBadge teamId={team.id} teamName={team.name} size={13} wrap
          onOpen={() => setMembersTeam(team)} title={t('team.expandMembers')} /></strong>
        {teamDir.byId.get(Number(team.id))?.noc_team && (
          <Badge variant="outline" data-slot="team-noc-badge" title={t('noc.ot.teamBadgeTip')}
            className="gap-1 rounded-full border-sky-500/40 bg-sky-500/10 font-semibold text-sky-700 dark:text-sky-300">
            <Headset aria-hidden="true" className="size-3" />{t('noc.ot.teamBadge')}
          </Badge>
        )}
      </div>
      {team.email
        ? <span data-slot="team-email" className="px-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{team.email}</span>
        : <span data-slot="team-email" data-missing="true" className="px-1 text-xs font-medium text-destructive">{t('team.gap.email.label')}</span>}
    </div>
  )
  const statusBlock = (team) => (
    <div className="flex flex-wrap items-center gap-1.5 px-1">
      <ToneBadge tone={team.active ? 'success' : 'danger'}>{team.active ? t('team.active') : t('team.inactive')}</ToneBadge>
      <TeamGapBadge team={team} gaps={gapsOf(team)} actions={gapActionsFor(team)}
        partial={!statsReady || !usersReady || gapIndex.unknown.has(team.id)}
        partialReason={(usersFailed && !usersReady) || (statsFailed && !statsReady) ? 'failed'
          : !statsReady || !usersReady ? 'pending' : 'scope'} />
    </div>
  )
  /** Kişinin sicili (2026-10-11, kullanıcı isteği: "müdür ve liderin ad-soyad yanında sicil olsun"); yoksa null. */
  const sicilOf = (u) => String(u?.employee_id ?? u?.employeeId ?? '').trim() || null
  /** Adın ALTINDAKİ ikincil satır: sicil + (varsa) "elle" işareti — ad sarsa da hizalı kalır. */
  const ownerMeta = (sicil, manual = false) => (sicil || manual) && (
    <span data-slot="team-owner-meta" className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-muted-foreground">
      {sicil && <span data-slot="team-owner-sicil" className="whitespace-nowrap tabular-nums">{t('team.sicil', sicil)}</span>}
      {manual && <span title={t('team.managerManualTitle')}>{t('team.managerManual')}</span>}
    </span>
  )
  /** Lider + müdür (ad + sicil). `stacked` (tablo): etiket adın ÜSTÜNDE — dar sütunda ad tüm genişliği kullanır; kartta
   *  yan yana. Müdür yalnız sicille türetildiyse (kullanıcı kaydı yok) etiketin kendisi sicildir — ikinci kez yazılmaz. */
  const ownersBlock = (team, stacked = false) => {
    const leaderName = userMap[leaderIdOf(team)]
    const leaderSicil = leaderName ? sicilOf(usersById[leaderIdOf(team)]) : null
    const mgr = managerOf(team)
    const mgrSicil = mgr?.label && mgr.userId != null ? sicilOf(usersById[mgr.userId]) : null
    const manual = manualManagerId(team) != null && userMap[manualManagerId(team)]
    return (
      <dl data-slot="team-owners" className={cn('grid min-w-0 text-[13px] leading-snug',
        stacked ? 'grid-cols-1 gap-y-0.5 [&>dd]:mb-1.5 [&>dd:last-child]:mb-0' : 'grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-2.5 gap-y-1')}>
        <dt className="text-xs text-muted-foreground">{t('team.leaderShort')}</dt>
        <dd className="min-w-0 [overflow-wrap:anywhere]">
          {leaderName ? <><span>{leaderName}</span>{ownerMeta(leaderSicil)}</>
            : leaderIdOf(team) != null && !usersReady ? <span data-slot="team-owner-pending" className="text-xs text-muted-foreground">{usersFailed ? t('team.ownerUnreadable') : t('app.loading')}</span>
            : gapIndex.leaderUnknown.has(team.id) ? <span data-slot="team-leader-unknown" className="text-xs text-muted-foreground">{t('team.leaderNotVisible')}</span>
            : <span className="text-xs font-medium text-destructive">{t('team.noLeader')}</span>}
        </dd>
        <dt className="text-xs text-muted-foreground">{t('team.managerShort')}</dt>
        <dd className="min-w-0 [overflow-wrap:anywhere]">
          {mgr?.label ? <><span>{mgr.label}</span>{ownerMeta(mgrSicil, manual)}</>
            : !usersReady ? <span data-slot="team-owner-pending" className="text-xs text-muted-foreground">{usersFailed ? t('team.ownerUnreadable') : t('app.loading')}</span>
            // Elle atanmış ama görüş alanı dışındaki müdür "müdürü yok" DEĞİL (lider ile aynı kural, 2026-10-12)
            : manualManagerId(team) != null && personState(manualManagerId(team)) === 'unknown'
              ? <span data-slot="team-manager-unknown" className="text-xs text-muted-foreground">{t('team.leaderNotVisible')}</span>
            : <span className="text-xs text-muted-foreground">{t('team.filterNoManager')}</span>}
        </dd>
        {oncallNow[String(team.id)] && (
          <>
            <dt className="text-xs text-muted-foreground">{t('oncall.short')}</dt>
            <dd data-slot="team-oncall" className="min-w-0 [overflow-wrap:anywhere]">
              <span>{oncallNow[String(team.id)].name}</span>
              <span className="mt-0.5 block text-xs text-muted-foreground">{t('oncall.untilShort', formatDate(oncallNow[String(team.id)].until))}</span>
            </dd>
          </>
        )}
      </dl>
    )
  }
  const statsBlock = (team) => (
    <TeamStats s={stats[String(team.id)]} t={t}
      onMembers={() => (canEditRow(team.id) ? setManageTeam(team) : setMembersTeam(team))}
      onDomains={() => navigateTo('domains', { i_team: String(team.id) })}
      onAlerts={() => navigateTo('alerthistory', { team: String(team.id) })} />
  )
  const weeklyBlock = (team, inline = false) => (
    <div className={cn('flex gap-1.5', inline ? 'flex-wrap gap-x-5 gap-y-2' : 'flex-col')}>
      <WeeklySwitch on={weeklyFlag(team, 'reminder')} disabled={!canToggleWeekly(team.id)}
        label={t('team.weeklyReminder')} short={t('team.weeklyReminderShort')} onToggle={() => toggleWeekly(team, 'reminder')} />
      <WeeklySwitch on={weeklyFlag(team, 'availability')} disabled={!canToggleWeekly(team.id)}
        label={t('team.weeklyAvailability')} short={t('team.weeklyAvailabilityShort')} onToggle={() => toggleWeekly(team, 'availability')} />
    </div>
  )
  const menuFor = (team) => (
    <KebabMenu variant="labeled" label={t('team.colActions')} rowLabel={team.name} items={[
      { label: t('team.edit'), icon: <PenLine />, onClick: () => openEdit(team), hidden: !canEditRow(team.id) },
      { label: t('team.manageMembers'), icon: <UserPlus />, onClick: () => setManageTeam(team), hidden: !canEditRow(team.id) },
      { label: t('team.menuViewMembers'), icon: <Users />, onClick: () => setMembersTeam(team) },
      // Erişim paylaşımı (2026-10-12): paylaşım sekmesine bu takımla süzülü geçer; sunucu yine takımı yönetmeyi ister
      { label: t('team.menuShare'), icon: <Share2 />, onClick: () => jumpAdmin('sharing', { g_team: team.id }), hidden: !canEditRow(team.id) },
      { label: t('team.menuContacts'), icon: <Siren />, onClick: () => jumpAdmin('contacts', { g_team: String(team.id) }) },
      { label: t('team.menuGroups'), icon: <BellRing />, onClick: () => jumpAdmin('notifyGroups', { g_team: String(team.id) }) },
      // Nöbet çizelgesi (2026-10-11): herkes görür; yönetemeyen salt okunur görür (uç kapsamı uygular)
      { label: t('oncall.menu'), icon: <CalendarClock />, onClick: () => setOncallTeam(team) },
      { label: t('hist.title'), icon: <History />, onClick: () => setHistFilter({ id: team.id, name: team.name }) },
      // Veri Kalitesi (2026-10-10): takımın puanı + düzeltme listesi (sayfa görüş kapsamını sunucuda uygular)
      { label: t('dq.teamMenu'), icon: <Gauge />, onClick: () => navigateTo('dataquality', { dq_team: String(team.id) }) },
      { label: t('tla.menu'), icon: <ScanSearch />, onClick: () => setLdapAuditTeam(team), hidden: !isGlobal },
      { label: t('team.delete'), icon: <Trash2 />, danger: true, onClick: () => del(team.id), hidden: !isGlobal },
    ]} />
  )
  const emptyText = gapSel.length > 0 ? t('team.gap.empty') : t('team.noResults')

  return (
    <section data-slot="team-manager" className="mb-8 flex min-w-0 flex-col gap-3">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-[1_1_18rem]">
          <h3 className="text-lg font-semibold tracking-tight">{t('team.title')}</h3>
          <p className="mt-0.5 text-sm text-muted-foreground">{t('team.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" className="max-sm:h-10 pointer-coarse:h-10" title={t('team.exportCsv')} onClick={exportCsv}>
            <Download aria-hidden="true" /> {t('team.exportCsv')}
          </Button>
          {isGlobal && <Button className="max-sm:h-10 pointer-coarse:h-10" onClick={openAdd}><Plus aria-hidden="true" /> {t('team.addBtn')}</Button>}
        </div>
      </header>
      {msg && !modal && <AlertBanner tone={formMsgTone}>{msg}</AlertBanner>}
      {loadError && teams.length === 0 && (
        <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
      )}

      {teams.length > 0 && <MonitorStatsBar dense items={kpis} activeFilter={kpiActive} onStatClick={onKpi} className="mb-0" />}

      {teams.length > 0 && (
        <TeamGapsPanel counts={gapCountMap} selected={gapSel} mode={gapMode} known={gapKnown} failed={gapFailed}
          onToggle={toggleGap} onMode={setGapMode} onClear={() => { setGapSel([]); setGapMode('any') }} />
      )}

      {/* Araç çubuğu — arama + sıralama + Süzgeçler paneli + sayaç (2026-09-20; sıralama 2026-10-11) */}
      <div data-testid="tm-toolbar">
        <div className="flex flex-wrap items-center gap-2">
          <ToolbarSearch value={q} onChange={setQ} placeholder={t('team.searchPlaceholder')}
            ariaLabel={t('team.searchLabel')} clearLabel={t('inv.filterClear')} className="max-sm:h-10 pointer-coarse:h-10" />
          <span className="w-full min-[480px]:w-auto min-[480px]:min-w-[200px] max-sm:[&_button]:h-10 pointer-coarse:[&_button]:h-10">
            <SearchableSelect value={sort} onChange={(v) => setSort(TEAM_SORTS.includes(v) ? v : DEFAULT_TEAM_SORT)}
              ariaLabel={t('team.sortTeams')} searchThreshold={99}
              options={TEAM_SORTS.map((k) => ({ value: k, label: t(`team.sortBy.${k}`) }))} />
          </span>
          <Button type="button" variant={filtersOpen || filterActive ? 'default' : 'outline'} size="sm" className="max-sm:h-10 pointer-coarse:h-10"
            onClick={() => setFiltersOpen((o) => !o)} aria-expanded={filtersOpen}>
            <SlidersHorizontal aria-hidden="true" /> {t('inv.filters')}{filterActive ? ` · ${t('inv.filterActive')}` : ''}
          </Button>
          <span className="text-[0.84em] whitespace-nowrap text-muted-foreground" aria-live="polite">{t('inv.shownOf', filteredTeams.length, teams.length)}</span>
          <div className="flex-1" />
          {(filterActive || q || gapSel.length > 0) && <Button type="button" variant="ghost" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={clearFilters}>{t('inv.filterClear')}</Button>}
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
          className="flex flex-wrap items-center gap-2 rounded-lg border border-primary bg-primary/5 px-3 py-2">
          <span className="mr-1 font-bold">{t('team.selected', selected.size)}</span>
          <Button variant="secondary" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={() => runBulk('activate')} disabled={bulkBusy}>{t('team.bulkActivate')}</Button>
          <Button variant="destructive" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={() => runBulk('deactivate')} disabled={bulkBusy}>{t('team.bulkDeactivate')}</Button>
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
          <Button variant="secondary" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={() => setSelected(new Set())} disabled={bulkBusy}>{t('usr.bulkClear')}</Button>
        </div>
      )}

      {/* Liste — kap genişliğine göre sıkı tablo ya da kartlar (2026-10-11) */}
      <div ref={setBoxEl} data-slot="tm-list" data-view={cards ? 'cards' : 'table'} className="min-w-0">
        {cards ? (
          <div className="flex flex-col gap-2">
            {canManage && selectableIds.length > 0 && (
              <label className="flex min-h-10 w-fit cursor-pointer items-center gap-2 px-1 text-sm">
                <Checkbox checked={allPageSelected} onCheckedChange={toggleAllPage} aria-label={t('team.selectAll')} />
                {t('team.selectAll')}
              </label>
            )}
            {filteredTeams.length === 0 ? (
              <p data-slot="tm-empty" className="rounded-lg border bg-card p-6 text-center text-sm text-muted-foreground">{emptyText}</p>
            ) : (
              <ul aria-label={t('team.listLabel')} className={cn('grid gap-3', cardsTwoCol && 'grid-cols-2')}>
                {pagedTeams.map((team) => (
                  <li key={team.id} data-slot="team-card" data-team-id={team.id} data-state={selected.has(team.id) ? 'selected' : undefined}
                    className="flex min-w-0 flex-col gap-3 rounded-lg border bg-card p-3 shadow-xs data-[state=selected]:border-primary data-[state=selected]:bg-primary/[0.03]">
                    <div className="flex items-start gap-2">
                      {canManage && canEditRow(team.id) && (
                        <label className="-mt-1.5 -ml-1.5 flex size-10 shrink-0 cursor-pointer items-center justify-center">
                          <Checkbox checked={selected.has(team.id)} onCheckedChange={() => toggleOne(team.id)} aria-label={t('team.selectOne', team.name)} />
                        </label>
                      )}
                      <div className="min-w-0 flex-1">{nameBlock(team)}</div>
                      <div className="shrink-0">{menuFor(team)}</div>
                    </div>
                    {statusBlock(team)}
                    <div className="rounded-md bg-muted/40 px-2.5 py-2">{ownersBlock(team)}</div>
                    {statsBlock(team)}
                    <div className="border-t pt-2.5">{weeklyBlock(team, true)}</div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : (
          <div className="overflow-hidden rounded-lg border bg-card">
            <Table className="table-fixed">
              <TableHeader className="bg-muted/50">
                <TableRow>
                  {canManage && (
                    <TableHead className="w-10">
                      <Checkbox checked={allPageSelected} onCheckedChange={toggleAllPage} aria-label={t('team.selectAll')} disabled={selectableIds.length === 0} />
                    </TableHead>
                  )}
                  {/* Sabit düzen: ad ve kişi sütunları payı alır, varlık çipleri kalanı (sarar); haftalık + işlemler dar sabit */}
                  <TableHead className="w-[22%]">{t('team.colTeam')}</TableHead>
                  <TableHead className="w-[24%]">{t('team.colOwners')}</TableHead>
                  <TableHead>{t('team.colAssets')}</TableHead>
                  <TableHead className="w-[10rem]">{t('team.colWeeklyEmails')}</TableHead>
                  <TableHead className="w-[7.5rem] text-right">{t('team.colActions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredTeams.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={canManage ? 6 : 5} className="p-[18px] text-center whitespace-normal text-muted-foreground">
                      {emptyText}
                    </TableCell>
                  </TableRow>
                )}
                {pagedTeams.map((team) => (
                  <TableRow key={team.id} data-team-id={team.id} data-state={selected.has(team.id) ? 'selected' : undefined} className="align-top">
                    {canManage && (
                      <TableCell className="w-10 pt-3 align-top">
                        {canEditRow(team.id) ? <Checkbox checked={selected.has(team.id)} onCheckedChange={() => toggleOne(team.id)} aria-label={t('team.selectOne', team.name)} /> : null}
                      </TableCell>
                    )}
                    <TableCell className="py-2.5 align-top whitespace-normal">
                      <div className="flex min-w-0 flex-col gap-1.5">{nameBlock(team)}{statusBlock(team)}</div>
                    </TableCell>
                    <TableCell className="py-2.5 align-top whitespace-normal">{ownersBlock(team, true)}</TableCell>
                    <TableCell className="py-2.5 align-top whitespace-normal">{statsBlock(team)}</TableCell>
                    <TableCell className="py-2.5 align-top whitespace-normal">{weeklyBlock(team)}</TableCell>
                    <TableCell className="py-2.5 text-right align-top">{menuFor(team)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
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
        <TeamMembersManager team={manageTeam} users={users} canManage={canEditRow(manageTeam.id)} globalAdmin={globalAdmin}
          onClose={() => setManageTeam(null)} onChanged={() => { loadStats(); setMembersNonce(n => n + 1); onTeamsChange?.() }} />
      )}
      {oncallTeam && (
        <OnCallScheduleModal team={oncallTeam} onClose={() => setOncallTeam(null)} onSaved={() => loadOncall()} />
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
        canManage={canManage} onEditUser={(u) => (elevatedTarget(u) ? openViewUser(u) : setEditingUser(u))} onViewUser={canManage ? openViewUser : undefined}
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
          onEdit={canManage && !elevatedTarget(viewUser) ? () => { const u = viewUser; setViewUser(null); setViewTab('overview'); setEditingUser(u) } : undefined} />
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
        onOpenDirectory={isGlobal ? (u) => { setEditingUser(null); setViewTab('directory'); setViewUser(u) } : undefined}
      />
    </section>
  )
}

/** Satır varlık sayaçları (2026-09-20): üye/domain/alarm tıklanır, diğerleri bilgi. Sayaç yoksa (henüz yüklenmedi) boş.
 *  Tıklanabilen sayaç shadcn Button (outline, hap biçimi), bilgi sayacı shadcn Badge. */
function TeamStats({ s, t, onMembers, onDomains, onAlerts }) {
  if (!s) return null
  const chip = (key, val, onClick, tip, alert = false, labelOverride = null) => {
    const n = Number(val ?? 0)
    const cls = cn('relative h-auto rounded-full px-2 py-px text-[0.76em] font-semibold', n === 0 && 'opacity-55',
      alert && 'border-destructive/40 text-destructive')
    const label = labelOverride || t(`team.stat.${key}`, n)
    return onClick
      ? <Button type="button" key={key} variant="outline" size="xs" className={cn(cls, TEAM_TOUCH_CHIP, 'hover:border-primary hover:text-primary')} onClick={onClick} title={tip}>{label}</Button>
      : <Badge key={key} variant="outline" className={cls} title={tip}>{label}</Badge>
  }
  // Pasif üyeler (2026-10-02): toplam üyeye dâhil; varsa sayaç "N aktif · M pasif" yazar (members_inactive ek alan).
  const inactiveMembers = Number(s.members_inactive ?? 0)
  const membersLabel = inactiveMembers > 0
    ? t('team.stat.membersSplit', Math.max(0, Number(s.members ?? 0) - inactiveMembers), inactiveMembers) : null
  return (
    <div className="flex min-w-0 flex-wrap gap-1" data-testid="team-stats">
      {chip('members', s.members, onMembers, t('team.statMembersTip'), false, membersLabel)}
      {chip('domains', s.domains, onDomains, t('team.statDomainsTip'))}
      {chip('monitors', s.monitors)}
      {chip('open_alerts', s.open_alerts, onAlerts, t('team.statAlertsTip'), Number(s.open_alerts) > 0)}
      {chip('contacts', s.contacts)}
      {chip('groups', s.groups)}
    </div>
  )
}
