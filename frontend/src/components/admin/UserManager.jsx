import { useState, useEffect, useCallback, useRef } from 'react'
import { api } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import AdminChangeHistory from './AdminChangeHistory.jsx'
import UserDetailPanel from './UserDetailPanel.jsx'
import {
  Download, SlidersHorizontal, Users, UserCheck, Shield, UserX, Moon, Lock, LockOpen, X, Pencil, History, KeyRound, Trash2,
} from 'lucide-react'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { toCsv, downloadCsv, stampedName } from '../../utils/csvExport.js'
import { formatDateSec } from '../../api/client'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { relTime } from './useractivity/uactModel.js'
import TeamBadge from '../ui/TeamBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { CARD_CHECK, CARD_LAYER } from '../monitoring/MonitorCard.jsx'
import { UserPlus, BellOff } from 'lucide-react'
import AdminAutoResetModal from './AdminAutoResetModal.jsx'
import UserEditor from './user-editor/UserEditor.jsx'
import BulkDeactivateWizard from './BulkDeactivateWizard.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ToneBadge, { OrgRoleBadge, SystemRoleBadge } from './ToneBadge.jsx'
import { ToolbarSearch, FilterPanel, FilterField } from './ListToolbar.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

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
/** AD fotoğrafı (shadcn Avatar); yüklenemezse renkli baş harf rozetine düşer. */
function UserAvatar({ user }) {
  // Kullanıcılar sekmesi USER rolüne de açık → admin'e özel foto ucu boş yere 403 üretirdi.
  return (
    <Avatar className="size-8">
      <AvatarImage alt="" src={`/api/users/${user.id}/photo`} className="object-cover" />
      <AvatarFallback className="text-[0.72em] font-bold tracking-wide text-white" style={{ background: avatarBg(user.username) }}>
        {initialsOf(user.display_name || user.username)}
      </AvatarFallback>
    </Avatar>
  )
}

/** Görünüm alanı 1024 px'ten dar mı (kart listesi eşiği). matchMedia yoksa (jsdom) masaüstü sayılır. */
function useNarrowViewport(query = '(max-width: 1023px)') {
  const read = () => { try { return !!window.matchMedia?.(query)?.matches } catch { return false } }
  const [narrow, setNarrow] = useState(read)
  useEffect(() => {
    let mql
    try { mql = window.matchMedia?.(query) } catch { mql = null }
    if (!mql) return undefined
    const on = () => setNarrow(!!mql.matches)
    on()
    mql.addEventListener?.('change', on)
    return () => mql.removeEventListener?.('change', on)
  }, [query])
  return narrow
}

/** "Uzun süredir girmemiş" eşiği (gün) — AdminOverviewService.DORMANT_DAYS ile aynı. */
const DORMANT_DAYS = 90

const KPI_VALUE_TONE = { warning: 'text-amber-700 dark:text-amber-300', danger: 'text-destructive' }
/**
 * Özet kutucuğu — tıklanır olanı (var olan süzgeci uygular) shadcn Button + aria-pressed; süzgeci
 * olmayan salt bilgi Card. Etkin süzgeç çerçevenin TAMAMIYLA vurgulanır (sol şerit YOK).
 */
function UserKpi({ kpiKey, icon: Icon, label, value, sub, tone, active = false, onClick, hint }) {
  // Etiket değerin ALTINDA tam genişlikte: dar kutucukta (tablet, 3 sütun) büyük harfli uzun sözcük
  // ikonun yanında kelime ortasından bölünüyordu (2026-09-26 ekran görüntüsü).
  const body = (
    <>
      <span className="flex w-full items-center gap-2">
        <span className="grid size-7 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary"><Icon size={15} aria-hidden="true" /></span>
        <span className={cn('text-2xl leading-none font-extrabold tracking-tight tabular-nums', KPI_VALUE_TONE[tone])}>{value ?? '—'}</span>
      </span>
      <span className="w-full text-[11px] leading-tight font-bold tracking-wide text-muted-foreground uppercase">{label}</span>
      {sub && <span className="text-xs text-muted-foreground">{sub}</span>}
    </>
  )
  const box = 'flex min-w-0 flex-col items-start gap-2 rounded-xl border border-border bg-card px-3.5 py-3 text-left shadow-xs'
  if (!onClick) return <Card data-kpi={kpiKey} className={box} title={hint}>{body}</Card>
  return (
    <Button type="button" variant="outline" data-kpi={kpiKey} aria-pressed={active} onClick={onClick} title={hint}
      className={cn(box, 'h-auto justify-start font-normal whitespace-normal transition-[border-color,box-shadow] hover:border-primary/60 hover:bg-card hover:shadow-md motion-reduce:transition-none',
        active && 'border-primary bg-primary/5 ring-2 ring-primary/20 hover:bg-primary/5')}>
      {body}
    </Button>
  )
}

export default function UserManager({ systemRole, ownTeamId, currentUsername, teams, globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canManage = isAdmin || isTeamAdmin
  // Kullanıcı değişiklik geçmişi yalnız GLOBAL yönetici: sunucu kapsamlı müdüre 403 verir (requireNotScopedAdmin).
  const canSeeUserHistory = isAdmin && !!globalAdmin
  const isSelf = (u) => u?.username === currentUsername
  const isAudit = systemRole === 'AUDIT'
  const canSeeAllTeams = isAdmin || isAudit   // takım filtresi yalnız bunlara görünür
  const { showConfirm } = useDialog()
  const [users, setUsers] = useState([])
  const [modal, setModal] = useState(null)
  const [autoResetModal, setAutoResetModal] = useState(null)
  const [viewUser, setViewUser] = useState(null)   // satıra tıklayınca açılan salt-okunur detay modalı
  // Detayın açılış sekmesi: düzenleyicideki "AD ile karşılaştır" Dizin sekmesiyle açar (2026-10-02); kapanınca sıfırlanır.
  const [viewTab, setViewTab] = useState('overview')
  // Sayfa düzeyi bilgi bandı — satır menüsündeki "Şifre Sıfırla"nın sonucu (düzenleyici kendi hatasını içinde gösterir).
  const [msg, setMsg] = useState(null)

  // Filtre + sunucu-taraflı sayfalama
  // Süzgeçler URL'de (g_*): derin bağlantı + yenileme korur (2026-09-20).
  const [histFilter, setHistFilter] = useState(null)   // { id, name } — satırdan "Geçmiş" (yalnız global ADMIN)
  const [q, setQ] = useState(() => readUrlParam('g_q', ''))
  const [fDormant, setFDormant] = useState(() => readUrlParam('g_dormant', ''))   // '' | '30' | '90' | '180' | 'never' (2026-09-20)
  const [selected, setSelected] = useState(() => new Set())                    // toplu işlem seçimi (id)
  const [bulk, setBulk] = useState(null)                                        // { action, team_id, org_role }
  const [bulkBusy, setBulkBusy] = useState(false)
  // Sistem geneli toplu pasife alma sihirbazı (2026-10-02, kullanıcı kararı) — yalnız GLOBAL yönetici; seçimli çubuk ayrı.
  const [bulkWizard, setBulkWizard] = useState(false)
  const [fRole, setFRole] = useState(() => readUrlParam('g_role', ''))
  const [fOrgRole, setFOrgRole] = useState(() => readUrlParam('g_org', ''))
  const [fTeam, setFTeam] = useState(() => readUrlParam('g_team', ''))
  useUrlQuerySync({ g_q: q, g_role: fRole, g_org: fOrgRole, g_team: fTeam, g_dormant: fDormant })
  // Proje standardı araç çubuğu (2026-09-20): arama kutusu + "Süzgeçler" açılır paneli (envanter ile aynı .invtb dağarcığı);
  // etkin süzgeç varsa panel açık başlar (derin bağlantıdan gelen kişi süzgeci görsün).
  const filterActive = !!(fRole || fOrgRole || fTeam || fDormant)
  const [filtersOpen, setFiltersOpen] = useState(() => !!(readUrlParam('g_role', '') || readUrlParam('g_org', '') || readUrlParam('g_team', '') || readUrlParam('g_dormant', '')))
  const clearFilters = () => { setQ(''); setFRole(''); setFOrgRole(''); setFTeam(''); setFDormant('') }
  // Sayfalama standardı (2026-09-26): süzgeçler 300 ms debounce ile "uygulanır"; uygulanan süzgeç değişince sayfa 1
  // (değer karşılaştırmalı). Sayfa/boyut değişimi beklemeden yükler. Panel ön ayarı (25), API 0-tabanlı.
  const filterKey = JSON.stringify([q.trim(), fRole, fOrgRole, fTeam, fDormant])
  const [appliedKey, setAppliedKey] = useState(filterKey)
  useEffect(() => { const tmr = setTimeout(() => setAppliedKey(filterKey), 300); return () => clearTimeout(tmr) }, [filterKey])
  const sp = useServerPagination({ listKey: 'admin-users', preset: 'panel', resetDeps: [appliedKey], apiBase: 0 })
  const total = sp.total ?? 0
  const [activeAdminCount, setActiveAdminCount] = useState(0)
  const [loading, setLoading] = useState(false)
  // Kart listesi: telefon (useIsMobile) YA DA < 1024 px — tablette kenar çubuğu içeriği ~410 px'e indiriyor, tablo
  // orada yalnız kullanıcı sütununu gösterebiliyordu (2026-09-26 ölçümü). Tek varyant çizilir (jsdom medya sorgusu görmez).
  const phone = useIsMobile()
  const narrow = useNarrowViewport()
  const isMobile = phone || narrow
  const [overview, setOverview] = useState(null)   // /admin/overview sayaçları (yoksa kutucuklar gizli)
  const loadOverview = useCallback(() => {
    // Yalnız gerçek sayaç yükü kabul edilir (yetkisiz/boş yanıt → kutucuklar hiç çizilmez)
    Promise.resolve(api.admin.overview?.()).then((r) => { if (r?.success && r.data?.counts) setOverview(r.data) }).catch(() => {})
  }, [])
  useEffect(() => { loadOverview() }, [loadOverview])

  // Son aktif admin sayısı sunucudan gelir → sayfalamadan bağımsız doğru
  const isLastActiveAdmin = (u) =>
    u?.system_role === 'ADMIN' && u?.active && activeAdminCount === 1

  const teamMap = Object.fromEntries((teams || []).map(t => [t.id, t.name]))

  // Fetch yarışı (BF1): 300 ms debounce'lu süzgeç, sayfa, boyut ve Yenile art arda istek çıkarır; geç dönen ESKİ
  // (geniş) yanıt yeni süzgecin listesini/toplamını ezerse "sayfayı seç" + toplu işlem YANLIŞ kullanıcılara uygulanır.
  // Yalnız EN SON isteğin yanıtı + bayrak temizliği uygulanır (kardeş: MonitorChangesConsole loadSeq).
  const loadSeq = useRef(0)
  async function load(p = sp.apiPage, s = sp.pageSize) {
    const my = ++loadSeq.current
    setLoading(true)
    try {
      const res = await api.admin.searchUsers({
        page: p, size: s, q: q.trim(), systemRole: fRole, orgRole: fOrgRole, teamId: fTeam,
        dormantDays: fDormant && fDormant !== 'never' ? Number(fDormant) : '',
        neverLoggedIn: fDormant === 'never',
      })
      if (my !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) {
        setUsers(res.data); sp.setTotal(res.total ?? 0)
        setActiveAdminCount(res.active_admin_count ?? 0)
        // Sayfa değişince görünmeyen seçim kalmasın (yanlışlıkla toplu işlem görünmeyene uygulanmasın).
        setSelected(prev => new Set([...prev].filter(id => (res.data || []).some(u => u.id === id))))
      }
    } finally {
      if (my === loadSeq.current) setLoading(false)
    }
  }

  // Uygulanan süzgeç / sayfa / boyut değişince yükle (ilk yükleme de buradan). Sayfa 1'e dönüşü kanca yapar.
  useEffect(() => {
    load(sp.apiPage, sp.pageSize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appliedKey, sp.apiPage, sp.pageSize])

  const refresh = () => { load(); loadOverview() }

  // ── Toplu işlem (2026-09-20): sayfadaki seçim → tek istek; sunucu her kullanıcıyı kendi güvenlik zincirinden geçirir. ──
  const pageIds = users.map(u => u.id)
  const allPageSelected = pageIds.length > 0 && pageIds.every(id => selected.has(id))
  function toggleAllPage() {
    setSelected(prev => { const n = new Set(prev); if (allPageSelected) pageIds.forEach(id => n.delete(id)); else pageIds.forEach(id => n.add(id)); return n })
  }
  function toggleOne(id) { setSelected(prev => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n }) }

  async function runBulk(action, extra = {}) {
    const labels = { activate: t('usr.bulkActivate'), deactivate: t('usr.bulkDeactivate'), assign_team: t('usr.bulkAssignTeam'), set_org_role: t('usr.bulkOrgRole') }
    // Aktiflik değişiminin sonucu onay metninde AÇIKÇA yazar (2026-10-02, kullanıcı kararı): pasifleştirme oturumları
    // hemen kapatır, girişi engeller, bildirimleri keser; yeniden aktifleştirme yalnız erişimi açar (oturumlar geri gelmez).
    const effects = action === 'deactivate' ? t('usr.deactivateEffects')
      : action === 'activate' ? t('usr.activateEffects') : null
    const ok = await showConfirm({
      title: t('usr.bulkTitle'),
      message: t('usr.bulkConfirm', selected.size, labels[action] || action) + (effects ? `\n\n${effects}` : ''),
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
        setSelected(new Set()); setBulk(null); refresh()
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

  // Ekle / düzenle: paylaşılan kullanıcı düzenleyicisi (user-editor/UserEditor, 2026-10-02). Form durumu, doğrulama,
  // kayıt yükü (takım yöneticisinde kendi takımına sabitleme dâhil) ve korumalar orada — burada yalnız hangi kayıt.
  function openAdd() { setMsg(null); setModal('add') }
  function openEdit(user) { setMsg(null); setModal(user) }

  async function unlock(id) {
    const res = await api.admin.unlockUser(id)
    if (res?.success) { toast.success(t('usr.unlocked')); refresh() }
    else toast.error(res?.error || 'Error')
  }

  async function roleUnlock(id) {
    const res = await api.admin.unlockUserRole(id)
    if (res?.success) { toast.success(t('usr.roleUnlocked')); refresh() }
    else toast.error(res?.error || 'Error')
  }

  async function orgRoleUnlock(id) {
    const res = await api.admin.unlockUserOrgRole(id)
    if (res?.success) { toast.success(t('usr.orgRoleUnlocked')); refresh() }
    else toast.error(res?.error || 'Error')
  }

  // Takım kilidi (2026-09-18): admin üyeliği elle değiştirince LDAP girişi ezmez; kilit kalkınca AD yazar.
  async function teamUnlock(id) {
    const res = await api.admin.unlockUserTeams(id)
    if (res?.success) { toast.success(t('usr.teamUnlocked')); refresh() }
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
    if (res?.success) { toast.success(t('usr.deleted')); refresh() }
    else toast.error(res?.error || 'Error')
  }

  // ── Görünüm parçaları (2026-09-26 yeniden tasarım) ──
  const now = Date.now()
  const nameOf = (u) => u.display_name || u.username
  const teamIdsOf = (u) => (u.team_ids ?? u.teamIds ?? (u.team_id != null ? [u.team_id] : [])).filter((id) => teamMap[id])
  /** Kilit rozeti: lucide Lock + ipucu (eski 🔒 emojisi). Kalıcı kilit kırmızı, alan kilitleri amber. */
  const lockBadge = (key, title, severe = false) => (
    <SimpleTooltip key={key} content={title}>
      <span role="img" aria-label={title} tabIndex={0} data-lock={key}
        className={cn('relative z-10 inline-flex size-5 shrink-0 items-center justify-center rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          severe ? 'bg-destructive/15 text-destructive' : 'bg-amber-500/15 text-amber-700 dark:text-amber-300')}>
        <Lock size={12} aria-hidden="true" />
      </span>
    </SimpleTooltip>
  )
  const roleBadges = (u) => (
    <span className="flex flex-wrap items-center gap-1">
      <SystemRoleBadge role={u.system_role} />
      {u.role_locked && lockBadge('role', t('usr.roleLockedTitle'))}
      {u.org_role && <OrgRoleBadge role={u.org_role}>{t('usr.orgRoleVal.' + u.org_role)}</OrgRoleBadge>}
      {u.org_role_locked && lockBadge('org', t('usr.orgRoleLockedTitle'))}
      {isLastActiveAdmin(u) && (
        <ToneBadge tone="danger" title={t('usr.lastAdminTitle')}>{t('usr.lastAdminBadge')}</ToneBadge>
      )}
    </span>
  )
  /** Takımlar: her ad ui/TeamBadge (üye listesini açar) — satır/kart tıklamasına sızmaz. */
  const teamBadges = (u) => {
    const ids = teamIdsOf(u)
    if (ids.length === 0 && !u.team_locked) return <span className="text-muted-foreground">—</span>
    return (
      <span className={cn(CARD_LAYER, 'flex flex-wrap items-center gap-1')} onClick={(e) => e.stopPropagation()}>
        {ids.map((id) => <TeamBadge key={id} teamId={id} teamName={teamMap[id]} size={11} />)}
        {u.team_locked && lockBadge('team', t('usr.teamLockedTitle'))}
      </span>
    )
  }
  /** Son giriş: göreli süre (tam zaman ipucunda); hiç girmemiş → sessiz rozet; 90+ gün → uyarı rozeti. */
  const lastLogin = (u) => {
    if (!u.last_login_at) return <ToneBadge tone="muted" data-login="never">{t('usr.neverLoggedIn')}</ToneBadge>
    const r = relTime(u.last_login_at, now)
    const at = Date.parse(u.last_login_at.endsWith('Z') ? u.last_login_at : u.last_login_at + 'Z')
    const dormant = Number.isFinite(at) && now - at >= DORMANT_DAYS * 86_400_000
    const exact = `${formatDateSec(u.last_login_at)}${u.last_login_method ? ` · ${u.last_login_method}` : ''}`
    return (
      <span className="inline-flex flex-wrap items-center gap-1.5">
        <SimpleTooltip content={exact}>
          <span tabIndex={0} data-login-rel="" aria-label={t('usr.lastLoginAt', exact)}
            className={cn(CARD_LAYER, 'cursor-default text-[0.86em] whitespace-nowrap underline decoration-muted-foreground/40 decoration-dotted underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-ring/50')}>
            {r ? t(`uact.rel.${r.unit}`, r.n) : exact}
          </span>
        </SimpleTooltip>
        {dormant && <ToneBadge tone="warning" data-login="dormant">{t('usr.dormantBadge', DORMANT_DAYS)}</ToneBadge>}
      </span>
    )
  }
  const statusBadge = (u) => (
    <span className="flex flex-wrap items-center gap-1">
      <Badge variant="outline" data-account={u.active ? 'active' : 'inactive'}
        className={cn('gap-1.5 rounded-full font-medium', u.active ? 'border-success/30 bg-success/10 text-success' : 'text-muted-foreground')}>
        <span aria-hidden="true" className={cn('size-1.5 rounded-full', u.active ? 'bg-success' : 'bg-muted-foreground/60')} />
        {u.active ? t('usr.active') : t('usr.inactive')}
      </Badge>
      {u.permanent_lock && lockBadge('perm', t('usr.permLocked'), true)}
    </span>
  )
  const identity = (u, { titleButton = false } = {}) => (
    <div className="flex min-w-0 items-center gap-2.5">
      <UserAvatar user={u} />
      <div className="flex min-w-0 flex-col leading-tight">
        {titleButton ? (
          // Kartın GERÇEK düğmesi: ::after tüm kartı örter (stretched button — MonitorCard deseni)
          <Button type="button" variant="ghost" data-user-open={u.id} onClick={() => setViewUser(u)}
            aria-label={t('a11y.openRow', nameOf(u))}
            className="h-auto justify-start rounded-none p-0 text-left text-[0.97em] font-bold text-foreground hover:bg-transparent hover:text-foreground focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50 dark:hover:bg-transparent">
            <span className="min-w-0 truncate">{nameOf(u)}</span>
          </Button>
        ) : (
          <strong className="max-w-[260px] truncate text-[0.95em]" title={nameOf(u)}>{nameOf(u)}</strong>
        )}
        <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 font-mono text-[0.78em] text-muted-foreground">
          <span className="truncate">{u.username}{u.employee_id ? ` · ${u.employee_id}` : ''}</span>
          {u.auth_source === 'LDAP' && (
            <Badge variant="outline" className="px-1 py-0 text-[0.85em] font-normal text-muted-foreground" title={t('usr.authSourceTitle')}>{t('usr.ldapBadge')}</Badge>
          )}
          {u.push_opt_out && (
            <span role="img" className="inline-flex items-center text-destructive" title={t('usr.pushOptOutTitle')} aria-label={t('usr.pushOptOutTitle')}>
              <BellOff size={12} aria-hidden="true" />
            </span>
          )}
        </span>
        {u.title && <span className="max-w-[260px] truncate text-[0.76em] text-muted-foreground" title={u.title}>{u.title}</span>}
      </div>
    </div>
  )
  const menuItems = (u) => (canManage ? [
    { label: t('usr.edit'), icon: <Pencil size={14} />, onClick: () => openEdit(u) },
    { label: t('hist.title'), icon: <History size={14} />, onClick: () => setHistFilter({ id: u.id, name: nameOf(u) }), hidden: !canSeeUserHistory },
    { label: t('usr.autoResetBtn'), icon: <KeyRound size={14} />, onClick: () => setAutoResetModal(u) },
    { label: t('usr.unlock'), icon: <LockOpen size={14} />, onClick: () => unlock(u.id), hidden: !u.permanent_lock },
    { label: t('usr.roleUnlock'), icon: <LockOpen size={14} />, onClick: () => roleUnlock(u.id), hidden: !u.role_locked },
    { label: t('usr.orgRoleUnlock'), icon: <LockOpen size={14} />, onClick: () => orgRoleUnlock(u.id), hidden: !u.org_role_locked },
    { label: t('usr.teamUnlock'), icon: <LockOpen size={14} />, onClick: () => teamUnlock(u.id), hidden: !u.team_locked },
    { label: t('usr.delete'), icon: <Trash2 size={14} />, danger: true, onClick: () => del(u.id), hidden: isSelf(u) || isLastActiveAdmin(u) },
  ] : [])

  // ── Özet kutucukları: yalnız mevcut uç (/admin/overview); tıklama VAR OLAN süzgeci uygular ──
  const oc = overview?.counts || {}
  const warnCount = (code) => (overview?.warnings || []).find((w) => w.code === code)?.count ?? 0
  const dormantKey = String(overview?.dormant_days || DORMANT_DAYS)
  const dormantFilterable = ['30', '90', '180'].includes(dormantKey)
  const share = (n) => (oc.users > 0 ? Math.round((n * 100) / oc.users) : 0)
  const kpis = overview ? [
    { key: 'total', icon: Users, label: t('usr.kpiTotal'), value: oc.users, active: !filterActive && !q.trim(), onClick: clearFilters },
    { key: 'active', icon: UserCheck, label: t('usr.kpiActive'), value: oc.users_active, sub: t('usr.kpiShare', share(oc.users_active ?? 0)) },
    { key: 'admins', icon: Shield, label: t('usr.kpiAdmins'), value: oc.admins, active: fRole === 'ADMIN', onClick: () => setFRole(fRole === 'ADMIN' ? '' : 'ADMIN'),
      tone: oc.admins === 1 ? 'warning' : oc.admins === 0 ? 'danger' : undefined },
    { key: 'never', icon: UserX, label: t('usr.kpiNever'), value: warnCount('USER_NEVER_LOGGED_IN'), active: fDormant === 'never',
      onClick: () => setFDormant(fDormant === 'never' ? '' : 'never'), tone: warnCount('USER_NEVER_LOGGED_IN') > 0 ? 'warning' : undefined },
    { key: 'dormant', icon: Moon, label: t('usr.kpiDormant', dormantKey), value: warnCount('USER_DORMANT'), active: fDormant === dormantKey,
      onClick: dormantFilterable ? () => setFDormant(fDormant === dormantKey ? '' : dormantKey) : undefined, tone: warnCount('USER_DORMANT') > 0 ? 'warning' : undefined },
    { key: 'locked', icon: Lock, label: t('usr.kpiLocked'), value: warnCount('USER_LOCKED'), tone: warnCount('USER_LOCKED') > 0 ? 'danger' : undefined },
  ] : []

  // ── Etkin süzgeç çipleri ──
  const dormantLabel = { 30: t('usr.dormant30'), 90: t('usr.dormant90'), 180: t('usr.dormant180'), never: t('usr.dormantNever') }
  const chips = [
    q.trim() && { key: 'q', label: t('usr.chipSearch', q.trim()), clear: () => setQ('') },
    fRole && { key: 'role', label: `${t('usr.colRole')}: ${fRole}`, clear: () => setFRole('') },
    fOrgRole && { key: 'org', label: `${t('usr.colOrgRole')}: ${t('usr.orgRoleVal.' + fOrgRole)}`, clear: () => setFOrgRole('') },
    fTeam && { key: 'team', label: `${t('usr.colTeam')}: ${teamMap[fTeam] || teamMap[Number(fTeam)] || fTeam}`, clear: () => setFTeam('') },
    fDormant && { key: 'dormant', label: dormantLabel[fDormant] || fDormant, clear: () => setFDormant('') },
  ].filter(Boolean)

  const firstLoad = loading && users.length === 0
  const empty = !loading && users.length === 0
  const TH_CLS = 'h-10 px-3 text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase'
  const STICKY = 'sticky right-0 z-[1] bg-card shadow-[-10px_0_12px_-12px_rgba(0,0,0,.35)] group-hover:bg-muted group-data-[state=selected]:bg-muted'

  return (
    <section data-testid="users-tab" className="flex min-w-0 flex-col gap-4">
      {/* Başlık + eylemler — telefonda alt alta */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="text-lg leading-tight font-semibold">{t('usr.title')}</h3>
          <p className="mt-0.5 text-sm text-muted-foreground">{t('usr.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" onClick={exportCsv} title={t('usr.exportCsv')}><Download size={14} aria-hidden="true" /> {t('usr.exportCsv')}</Button>
          {globalAdmin && (
            <Button variant="outline" onClick={() => setBulkWizard(true)} title={t('ubd.openHint')} data-slot="um-bulk-deactivate"
              className="border-destructive/40 text-destructive hover:bg-destructive/10 hover:text-destructive">
              <UserX size={14} aria-hidden="true" /> {t('ubd.open')}
            </Button>
          )}
          {canManage && <Button variant="success" onClick={openAdd}><UserPlus size={14} aria-hidden="true" /> {t('usr.addBtn')}</Button>}
        </div>
      </div>
      {msg && !modal && !autoResetModal && <AlertBanner tone={msg === t('usr.autoResetFailed') ? 'danger' : 'success'} className="mb-0">{msg}</AlertBanner>}

      {/* Özet kutucukları — tıklanınca listedeki süzgeç uygulanır (etkin olan vurgulu) */}
      {kpis.length > 0 && (
        <div data-testid="um-kpis" className="grid grid-cols-2 gap-2.5 sm:grid-cols-3 sm:gap-3 xl:grid-cols-6">
          {kpis.map(({ key, ...k }) => <UserKpi key={key} kpiKey={key} {...k} hint={k.onClick ? t('usr.kpiFilterHint') : t('usr.kpiInfoHint')} />)}
        </div>
      )}

      {/* Araç çubuğu: arama + Süzgeçler paneli + etkin süzgeç çipleri + sayaç */}
      <div className="flex flex-col gap-2" data-testid="um-toolbar">
        <div className="flex flex-wrap items-center gap-2">
          <ToolbarSearch value={q} onChange={setQ} placeholder={t('usr.searchPlaceholder')}
            ariaLabel={t('usr.searchLabel')} clearLabel={t('inv.filterClear')} className="w-full max-w-none sm:w-auto sm:max-w-[420px]" />
          <Button type="button" variant={filtersOpen || filterActive ? 'default' : 'outline'} size="sm" className="h-8" onClick={() => setFiltersOpen((o) => !o)} aria-expanded={filtersOpen}>
            <SlidersHorizontal size={13} aria-hidden="true" /> {t('inv.filters')}{filterActive ? ` · ${t('inv.filterActive')}` : ''}
          </Button>
          <span className="text-[0.84em] whitespace-nowrap text-muted-foreground" aria-live="polite">{loading ? '…' : t('inv.shownOf', users.length, total)}</span>
        </div>
        {chips.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5" data-testid="um-chips">
            {chips.map((c) => (
              <Button key={c.key} type="button" variant="outline" size="xs" data-chip={c.key} onClick={c.clear}
                aria-label={t('usr.chipRemove', c.label)} className="h-7 gap-1 rounded-full border-primary/40 bg-primary/5 pr-1.5 pl-2.5 font-normal">
                {c.label}<X aria-hidden="true" className="size-3.5 opacity-70" />
              </Button>
            ))}
            <Button type="button" variant="ghost" size="xs" className="h-7" onClick={clearFilters}>{t('inv.filterClear')}</Button>
          </div>
        )}
        {filtersOpen && (
          <FilterPanel label={t('inv.filters')} className="mt-0 grid-cols-[repeat(auto-fit,minmax(min(200px,100%),1fr))]">
            <FilterField label={t('usr.colRole')}>
              <SearchableSelect value={fRole} onChange={setFRole} ariaLabel={t('usr.colRole')}
                options={[{ value: '', label: t('usr.allRoles') }, ...['ADMIN', 'TEAM_ADMIN', 'USER', 'AUDIT'].map(r => ({ value: r, label: r }))]} />
            </FilterField>
            <FilterField label={t('usr.colOrgRole')}>
              <SearchableSelect value={fOrgRole} onChange={setFOrgRole} ariaLabel={t('usr.colOrgRole')}
                options={[{ value: '', label: t('usr.allOrgRoles') }, ...['PO', 'TECH', 'MANAGER', 'BOLUM_BASKANI', 'CLEVEL'].map(r => ({ value: r, label: t('usr.orgRoleVal.' + r) }))]} />
            </FilterField>
            {canSeeAllTeams && (
              <FilterField label={t('usr.colTeam')}>
                <SearchableSelect value={fTeam} onChange={setFTeam} ariaLabel={t('usr.colTeam')} searchThreshold={2}
                  options={[{ value: '', label: t('usr.allTeams') }, ...(teams || []).map(tm => ({ value: String(tm.id), label: tm.name }))]} />
              </FilterField>
            )}
            <FilterField label={t('usr.colLastLogin')}>
              <SearchableSelect value={fDormant} onChange={setFDormant} ariaLabel={t('usr.colLastLogin')}
                options={[{ value: '', label: t('usr.dormantAll') }, { value: '30', label: t('usr.dormant30') }, { value: '90', label: t('usr.dormant90') },
                  { value: '180', label: t('usr.dormant180') }, { value: 'never', label: t('usr.dormantNever') }]} />
            </FilterField>
          </FilterPanel>
        )}
      </div>

      {/* Toplu işlem çubuğu — seçim varken; telefonda ekranın altına yapışık. `sticky bottom-0` App.css'teki yardım düğmesi
          kaldırma kuralını tetikler (SettingsSaveBar ile aynı) + güvenli alan dolgusu; çubuk 88 px'ten uzun olabildiği için
          telefonda sağda yardım düğmesi payı (pr-14, WeeklyReportsPage çubuğuyla aynı) — sağ uçtaki denetimler örtülmez. */}
      {canManage && selected.size > 0 && (
        <div data-testid="bulk-bar" role="region" aria-label={t('bulk.aria')} aria-busy={bulkBusy || undefined}
          className="sticky bottom-0 z-20 flex flex-wrap items-center gap-2 rounded-[10px] border border-primary bg-card pt-2 pr-14 pb-[max(0.5rem,env(safe-area-inset-bottom))] pl-3 shadow-lg md:static md:bottom-auto md:pr-3 md:pb-2 md:shadow-none">
          <span className="mr-1 font-bold">{t('usr.selected', selected.size)}</span>
          <Button variant="secondary" size="sm" onClick={() => runBulk('activate')} disabled={bulkBusy}>{t('usr.bulkActivate')}</Button>
          <Button variant="destructive" size="sm" onClick={() => runBulk('deactivate')} disabled={bulkBusy}>{t('usr.bulkDeactivate')}</Button>
          {canSeeAllTeams && (
            <span className="w-full sm:w-auto sm:min-w-[180px] sm:flex-[0_1_220px]">
              <SearchableSelect value={bulk?.team_id || ''} onChange={(v) => v && runBulk('assign_team', { team_id: Number(v) })} placeholder={t('usr.bulkAssignTeam')} ariaLabel={t('usr.bulkAssignTeam')}
                searchThreshold={2} options={[{ value: '', label: t('usr.bulkPickTeam') }, ...(teams || []).map(tm => ({ value: String(tm.id), label: tm.name }))]} />
            </span>
          )}
          <span className="w-full sm:w-auto sm:min-w-[180px] sm:flex-[0_1_220px]">
            <SearchableSelect value={bulk?.org_role || ''} onChange={(v) => v && runBulk('set_org_role', { org_role: v })} placeholder={t('usr.bulkOrgRole')} ariaLabel={t('usr.bulkOrgRole')}
              options={[{ value: '', label: t('usr.bulkPickOrgRole') }, ...['PO', 'TECH', 'MANAGER', 'BOLUM_BASKANI', 'CLEVEL'].map(r => ({ value: r, label: t('usr.orgRoleVal.' + r) }))]} />
          </span>
          <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())} disabled={bulkBusy}>{t('usr.bulkClear')}</Button>
        </div>
      )}

      {empty ? (
        (filterActive || q.trim())
          ? <StatusBlock tone="neutral" icon={Users} title={t('usr.noResults')} description={t('usr.noResultsHint')}
              actions={<Button type="button" variant="outline" size="sm" onClick={clearFilters}>{t('inv.filterClear')}</Button>} />
          : <StatusBlock tone="neutral" icon={Users} title={t('usr.noUsers')} description={canManage ? t('usr.noUsersHint') : undefined} />
      ) : isMobile ? (
        /* Telefon: kart listesi — kart başlığı gerçek düğme (stretched), içteki kontroller örtünün üstünde */
        <div data-testid="um-cards" className="flex flex-col gap-2.5">
          {canManage && users.length > 0 && (
            <label className="flex items-center gap-2 px-1 text-sm text-muted-foreground">
              <Checkbox checked={allPageSelected} onCheckedChange={toggleAllPage} aria-label={t('usr.selectAll')} />{t('usr.selectAll')}
            </label>
          )}
          {firstLoad
            ? Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-[132px] rounded-xl motion-reduce:animate-none" />)
            : users.map((u) => (
              <Card key={u.id} data-user-card={u.id} data-state={selected.has(u.id) ? 'selected' : undefined}
                className="relative gap-2.5 px-4 py-3.5 shadow-xs data-[state=selected]:border-primary data-[state=selected]:bg-primary/5">
                <div className="flex items-start gap-2">
                  {canManage && (
                    <Checkbox className={cn(CARD_CHECK, 'mt-2')} checked={selected.has(u.id)} onCheckedChange={() => toggleOne(u.id)}
                      aria-label={t('bulk.selectOneFor', u.username)} />
                  )}
                  <div className="min-w-0 flex-1">{identity(u, { titleButton: true })}</div>
                  <span className={CARD_LAYER}>
                    <KebabMenu label={t('usr.colActions')} rowLabel={nameOf(u)} items={menuItems(u)} />
                  </span>
                </div>
                <div className="flex flex-wrap items-center gap-1.5">{roleBadges(u)}{statusBadge(u)}</div>
                {teamBadges(u)}
                <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                  <span>{t('usr.colLastLogin')}:</span>{lastLogin(u)}
                </div>
              </Card>
            ))}
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border bg-card">
          {/* Masaüstü/tablet: tablo — Eylemler sütunu sağa yapışık (yatay kaydırmada kaybolmaz). */}
          <Table>
            <TableHeader className="bg-muted/50">
              <TableRow className="hover:bg-transparent">
                {canManage && (
                  <TableHead className="w-9 px-3">
                    <Checkbox checked={allPageSelected} onCheckedChange={toggleAllPage} aria-label={t('usr.selectAll')} />
                  </TableHead>
                )}
                <TableHead className={TH_CLS}>{t('usr.colUser')}</TableHead>
                <TableHead className={cn(TH_CLS, 'hidden 2xl:table-cell')}>{t('usr.colEmail')}</TableHead>
                <TableHead className={TH_CLS}>{t('usr.colRole')}</TableHead>
                <TableHead className={cn(TH_CLS, 'hidden xl:table-cell')}>{t('usr.colTeam')}</TableHead>
                <TableHead className={cn(TH_CLS, 'hidden xl:table-cell')}>{t('usr.colLastLogin')}</TableHead>
                <TableHead className={TH_CLS}>{t('usr.colActive')}</TableHead>
                <TableHead className={cn(TH_CLS, STICKY, 'w-px bg-muted text-right')}><span className="sr-only">{t('usr.colActions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {firstLoad && Array.from({ length: 5 }, (_, i) => (
                <TableRow key={`sk${i}`} data-skeleton="">
                  <TableCell colSpan={canManage ? 8 : 7} className="px-3 py-2.5"><Skeleton className="h-9 w-full motion-reduce:animate-none" /></TableCell>
                </TableRow>
              ))}
              {!firstLoad && users.map((user) => (
                <TableRow key={user.id} className="group cursor-pointer" title={t('usr.viewTitle')} tabIndex={0}
                  data-state={selected.has(user.id) ? 'selected' : undefined}
                  aria-label={t('a11y.openRow', nameOf(user))}
                  onClick={() => setViewUser(user)}
                  onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); setViewUser(user) } }}>
                  {canManage && (
                    <TableCell className="w-9 px-3" onClick={(e) => e.stopPropagation()}>
                      <Checkbox checked={selected.has(user.id)} onCheckedChange={() => toggleOne(user.id)} aria-label={t('bulk.selectOneFor', user.username)} />
                    </TableCell>
                  )}
                  <TableCell className="min-w-[200px] px-3 py-2.5">{identity(user)}</TableCell>
                  <TableCell className="hidden max-w-[240px] px-3 2xl:table-cell">
                    <span className="inline-block max-w-[240px] truncate align-bottom text-[0.9em]" title={user.email || ''}>{user.email || '—'}</span>
                  </TableCell>
                  <TableCell className="px-3 whitespace-normal">{roleBadges(user)}</TableCell>
                  <TableCell className="hidden max-w-[240px] px-3 whitespace-normal xl:table-cell">{teamBadges(user)}</TableCell>
                  <TableCell className="hidden px-3 whitespace-normal xl:table-cell">{lastLogin(user)}</TableCell>
                  <TableCell className="px-3">{statusBadge(user)}</TableCell>
                  <TableCell className={cn('w-px px-2 text-right', STICKY)} onClick={(e) => e.stopPropagation()}>
                    <KebabMenu label={t('usr.colActions')} rowLabel={nameOf(user)} items={menuItems(user)} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      {/* Sayfalama — proje standardı (useServerPagination: taban dönüşümü ve sıfırlama kancada) */}
      <PaginationBar {...sp.bar} />

      {/* Kullanıcı geçmişi yalnız global ADMIN (rol/takım/parola sıfırlama kayıtları kişisel veri taşır). */}
      <AdminChangeHistory resource="USER" filter={histFilter} onClearFilter={() => setHistFilter(null)} canView={canSeeUserHistory} />

      {/* Ekle / düzenle — paylaşılan kullanıcı düzenleyicisi (2026-10-02). Liste tazelenince (kilit açma, şifre sıfırlama)
          düzenlenen kişinin GÜNCEL satırı verilir: başlık rozetleri tazelenir, yazılanlar silinmez (taban güncellenir). */}
      {modal !== null && (
        <UserEditor key={modal === 'add' ? 'add' : `u${modal.id}`} mode={modal === 'add' ? 'add' : 'edit'}
          user={modal === 'add' ? null : (users.find((u) => u.id === modal.id) || modal)} teams={teams}
          viewerRole={systemRole} globalAdmin={globalAdmin} ownTeamId={ownTeamId} currentUsername={currentUsername}
          activeAdminCount={activeAdminCount}
          onClose={() => setModal(null)} onSaved={refresh} onChanged={refresh}
          onOpenDirectory={isAdmin ? (u) => { setModal(null); setViewTab('directory'); setViewUser(u) } : undefined} />
      )}

      {globalAdmin && (
        <BulkDeactivateWizard open={bulkWizard} teams={teams} onClose={() => setBulkWizard(false)} onDone={refresh} />
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

      {/* Satıra tıklayınca: kullanıcı ayrıntısı (salt-okunur). Liste tazelenince (kilit açma, AD eşitleme) güncel satır
          verilir; kilit açma MEVCUT işleyicilerle (menüdekiyle aynı yetki kapısı: canManage). */}
      {viewUser && (
        <UserDetailPanel user={users.find((u) => u.id === viewUser.id) || viewUser} teams={teams} isAdmin={isAdmin} globalAdmin={globalAdmin}
          initialTab={viewTab} onClose={() => { setViewUser(null); setViewTab('overview') }} onChanged={refresh}
          onUnlock={canManage ? { perm: unlock, role: roleUnlock, org: orgRoleUnlock, team: teamUnlock } : undefined}
          onEdit={canManage ? () => { const u = viewUser; setViewUser(null); setViewTab('overview'); openEdit(u) } : undefined} />
      )}
    </section>
  )
}
