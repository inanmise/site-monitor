import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { BarChart3, AlertOctagon, X, Inbox, CalendarDays, Plus, Loader2, Search, Layers, LayoutDashboard, RefreshCw, PlayCircle } from 'lucide-react'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { SidebarProvider, SidebarInset } from '@/components/shadcn/sidebar'

import { api, formatDate } from './api/client'
import { useDialog } from './components/ui/Dialog.jsx'
import { deleteInventoryByDomain } from './utils/deleteInventory.js'
import { useToast } from './components/ui/Toast.jsx'
import { useT } from './i18n/index.jsx'
import { usePagination } from './hooks/usePagination.js'
import { teamsFromMe } from './hooks/useMonitorTeamPick.js'
import { matchesTag, tagNamesOf, matchesGroupOrTagText } from './utils/monitorFilters.js'
import PaginationBar from './components/ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlParam, PAGE_STATE_PARAMS, PAGE_STATE_PREFIXES } from './hooks/useUrlQuerySync.js'
import SearchableSelect from './components/ui/SearchableSelect.jsx'
import FacetedFilter from './components/ui/FacetedFilter.jsx'
import { PLATFORM_NONE, PLATFORM_URL_KEY, parsePlatformParam, serializePlatformParam, matchesPlatform, countPlatforms, buildPlatformOptions } from './utils/platformFilter.js'
import Login, { REMEMBER_KEY } from './pages/Login'
import { claimPersonalStorage, clearPersonalStorage } from './utils/personalStorage.js'
import Nav from './components/Nav'
import MobileTopBar from './components/nav/MobileTopBar.jsx'
import BrandLogo from './components/BrandLogo.jsx'
import { useStatusFavicon } from './hooks/useStatusFavicon.js'
import { useCertDeepLink } from './hooks/useCertDeepLink.js'
import { runWithConcurrency } from './utils/concurrentQueue.js'
import StatsPanel from './components/StatsPanel'
import StatsView from './components/StatsView'
import CertificateCard from './components/CertificateCard'
import CertificatesTable from './components/CertificatesTable'
const SharedCertificateModal = lazy(() => import('./components/SharedCertificateModal'))   // kart çipi → paylaşılan sertifika (2026-09-22)
import CertificateModal from './components/CertificateModal'
import CaDiversityModal from './components/CaDiversityModal'
import RenewalPlanModal from './components/RenewalPlanModal.jsx'   // Genel Bakış kartı 'Planla' (2026-09-19)
import CardDensityToggle from './components/ui/CardDensityToggle.jsx'
import { useNewDomainWarmup } from './hooks/useNewDomainWarmup.js'   // yeni alan adı kartı ısınırken kısa tazeleme (2026-09-28)
import { expiryKey } from './pages/forecastModel.js'   // bitiş günü YEREL gün (UTC dilimi geç saatte bir gün erken)
import RenewalAdvice from './components/RenewalAdvice'
import CertRenewalGuide from './components/CertRenewalGuide.jsx'
import PasswordChangeModal from './components/admin/PasswordChangeModal.jsx'
import { PermissionsProvider } from './contexts/PermissionsProvider.jsx'
import { UserDirectoryProvider } from './components/ui/UserDirectory.jsx'
import { TeamDirectoryProvider } from './components/ui/TeamDirectory.jsx'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import CheckRunModal from './components/check/CheckRunModal.jsx'
import CheckTeamPicker, { NO_TEAM } from './components/check/CheckTeamPicker.jsx'
import AnnouncementBanner from './components/AnnouncementBanner.jsx'
import { LastLoginNotice } from './components/LastLoginInfo.jsx'
import { LoadingBlock, Spinner } from './components/ui/Progress.jsx'
import StatusBlock from './components/ui/StatusBlock.jsx'
import CollapsibleSection from './components/ui/CollapsibleSection.jsx'
import PageHeader from './components/ui/PageHeader.jsx'
import { TAB_META } from './components/palette/paletteModel.js'   // sekme başlığı ikonu = kenar çubuğundaki ikon

// Ağır/seyrek admin & rapor sekmeleri — lazy (kod-bölme): ilk yük küçülür, sekme
// açılınca yüklenir. Hepsi aşağıdaki tek <Suspense> sınırı altında render edilir.
// 9 izleme sayfasi — LAZY. Bunlar eager import ediliyordu ve asagidaki lazy()'leri fiilen
// etkisiz kiliyordu: her biri AlertHistory / CheckHistoryTab / recharts / CodeEditor zincirini
// de cekiyor, dolayisiyla "seyrek acilan sekme" diye ayrilan modullerin cogu zaten ana
// chunk'a giriyordu. Hepsi ZATEN tek bir <Suspense> siniri altinda render ediliyor —
// donusum yapisal degisiklik gerektirmedi.
const UptimePage = lazy(() => import('./components/UptimePage'))
const PortMonitorPage = lazy(() => import('./components/PortMonitorPage'))
const DnsMonitorPage = lazy(() => import('./components/DnsMonitorPage'))
const KeywordMonitorPage = lazy(() => import('./components/KeywordMonitorPage'))
const HttpMonitorPage = lazy(() => import('./components/HttpMonitorPage'))
const DomainMonitorPage = lazy(() => import('./components/DomainMonitorPage'))
const PingMonitorPage = lazy(() => import('./components/PingMonitorPage'))
const PageMonitorPage = lazy(() => import('./components/PageMonitorPage'))
const PageSpeedMonitorPage = lazy(() => import('./components/PageSpeedMonitorPage'))
const ScriptedMonitorPage = lazy(() => import('./components/ScriptedMonitorPage'))

const AdminPanel = lazy(() => import('./components/admin/AdminPanel'))
const AdminSettings = lazy(() => import('./components/admin/AdminSettings'))
const LoginIssueReports = lazy(() => import('./components/admin/LoginIssueReports'))
const PermissionMatrix = lazy(() => import('./components/admin/PermissionMatrix'))
const AlertHistory = lazy(() => import('./components/admin/AlertHistory'))
const IncidentsPage = lazy(() => import('./components/IncidentsPage'))
const MaintenanceWindowsPage = lazy(() => import('./components/MaintenanceWindowsPage'))
const InventoryManager = lazy(() => import('./components/admin/InventoryManager'))
const AuditLogViewer = lazy(() => import('./components/admin/AuditLogViewer'))
const MonitorChangesConsole = lazy(() => import('./components/admin/MonitorChangesConsole'))
const WeakAlgorithmReport = lazy(() => import('./components/admin/WeakAlgorithmReport'))
import TodayPanel from './components/TodayPanel.jsx'
import RecentChangesLine from './components/RecentChangesLine.jsx'
import HelpDrawer from './components/HelpDrawer.jsx'
import { TourProvider } from './components/tour/TourProvider.jsx'
import OnboardingChecklist from './components/tour/OnboardingChecklist.jsx'
import TourPageChip from './components/tour/TourPageChip.jsx'
import { readMirror, writeMirror, mergeState } from './components/tour/tourEngine.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import AlertBanner from './components/ui/AlertBanner.jsx'
import NocCoverageBanner from './components/noc/NocCoverageBanner.jsx'   // Genel Bakış 7/24 kapsam şeridi (2026-09-27)
const WeeklyReportsPage = lazy(() => import('./components/WeeklyReportsPage'))
const IncidentHistoryPage = lazy(() => import('./components/IncidentHistoryPage'))
const SystemHealth = lazy(() => import('./components/admin/SystemHealth'))
const SqlPlayground = lazy(() => import('./components/admin/SqlPlayground'))
const ActivityLog = lazy(() => import('./components/ActivityLog'))
const MyAuditLog = lazy(() => import('./components/MyAuditLog'))
const HelpPage = lazy(() => import('./components/HelpPage'))
const ExpiryForecastPage = lazy(() => import('./pages/ExpiryForecastPage'))
const WarningsPage = lazy(() => import('./pages/WarningsPage'))   // Dikkat Gerektiren Sertifikalar (2026-09-27)
const NocCoveragePage = lazy(() => import('./pages/NocCoveragePage'))   // 7/24 Kapsamı (2026-09-27)
// Envanter formu (kart → Düzenle/Kopyala): MDEditor çektiği için lazy — kendi Suspense sınırında.
const InventoryFormModalForDomain = lazy(() =>
  import('./components/inventory/InventoryFormModal.jsx').then(m => ({ default: m.InventoryFormModalForDomain })))

/**
 * Hareketsizlik oturum kapatma — SUNUCUDAN gelir (Genel Ayarlar), varsayılan 60 dk.
 *
 * <p>Eskiden yalnız derleme zamanı ayarlanabiliyordu (VITE_INACTIVITY_MS, 5 dk): süreyi
 * değiştirmek yeniden derleyip dağıtmayı gerektiriyordu. Aşağıdaki sabitler artık YALNIZ
 * yedek: sunucu değeri gelmezse (eski sürüm, ağ hatası) kullanılır.
 */
const INACTIVITY_MS   = Number(import.meta.env.VITE_INACTIVITY_MS   ?? 3_600_000)
const WARN_BEFORE_MS  = Number(import.meta.env.VITE_WARN_BEFORE_MS  ?? 60_000)

/** Sunucu yanıtından hareketsizlik ayarını çıkarır; alan yoksa yedeğe düşer. */
function idleConfigFrom(res) {
  const mins = Number(res?.inactivity_minutes)
  const warn = Number(res?.inactivity_warn_seconds)
  const totalMs = Number.isFinite(mins) && mins > 0 ? mins * 60_000 : INACTIVITY_MS
  const warnMs = Number.isFinite(warn) && warn > 0 ? warn * 1000 : WARN_BEFORE_MS
  // Uyarı toplam süreyi AŞAMAZ; aşarsa uyarı hiç görünmez ve kullanıcı habersiz atılırdı.
  return { totalMs, warnMs: Math.min(warnMs, Math.max(1000, totalMs - 1000)) }
}

/** "Şimdi Kontrol Et" eşzamanlı kontrol sayısı. Erişilemeyen bir host'ta tek kontrol timeout'a
 *  (~6 sn) kadar sürüyor; sıralı koşumda bu, arkasındaki tüm domainleri bekletiyordu. Sınır küçük
 *  tutuluyor: her istek sunucuda bir Tomcat iş parçacığı tutar (max 100) ve tek pod aynı anda
 *  başka kullanıcılara da hizmet eder. */
const CHECK_CONCURRENCY = Number(import.meta.env.VITE_CHECK_CONCURRENCY ?? 6)

// Oturum düşünce client.js hard reload ile /?session=expired'a yönlendirir → giriş formunda
// "oturum süresi doldu" bildirimi göstermek için bu bayrağı okuruz (AUTH-1).
function initialSessionExpired() {
  try { return new URLSearchParams(window.location.search).get('session') === 'expired' }
  catch { return false }
}

// Mail/derin-link ile gelen ?tab= değeri — yalnız bilinen sekme anahtarları kabul edilir.
/** Aynı sekmede param değişimi: handleTabChange(id, extraParams) → sayfalar bu olayı dinler (HelpPage view, SystemHealth sec). */
export const TAB_PARAMS_EVENT = 'sm:tab-params'
/** Programatik gezinme olayı — bkz. utils/navigate.js */
export const NAVIGATE_EVENT = 'sm:navigate'

const VALID_TABS = new Set([
  'dashboard', 'all', 'domains', 'forecast', 'renewal', 'renewal-guide',
  'warnings', 'incidents', 'maintenance', 'alerthistory', 'noc', 'stats', 'weakalgo', 'weeklyreports', 'incident-history',
  'health', 'uptime', 'http', 'domain', 'port', 'dns', 'keyword', 'ping', 'page', 'pagespeed', 'scripted', 'activity', 'myactivity', 'system', 'monitorchanges',
  'admin', 'permissions', 'sqlplayground', 'login-issues', 'help', 'settings',
])
/** Genel Bakış kart listesinin paylaşılabilir sayfa/boyut adresi (usePagination `url`; sabit referans). */
const DASH_PAGE_URL = Object.freeze({ pageKey: 'page', sizeKey: 'ps' })

function initialTabFromUrl() {
  try {
    const t = new URLSearchParams(window.location.search).get('tab')
    return t && VALID_TABS.has(t) ? t : null
  } catch { return null }
}

/** /me ve giriş yanıtından kullanıcı menüsünün ihtiyaç duyduğu profil alanları (beyaz liste; telefon/sicil YOK). */
function profileFrom(r) {
  if (!r) return null
  return {
    user_id: r.user_id ?? null, display_name: r.display_name ?? null, first_name: r.first_name ?? null,
    last_name: r.last_name ?? null, email: r.email ?? null,
  }
}


export default function App() {
  const { showConfirm } = useDialog()
  const toast = useToast()
  const t = useT()
  const [user, setUser] = useState(null)
  const [systemRole, setSystemRole] = useState('USER')
  // Faz 3b: yalnız global (yerel/bootstrap) admin global-only sekmeleri görür;
  // kapsamlı (scoped) müdür-admin systemRole==='ADMIN' olsa da görmemeli (backend 403 döner).
  const [globalAdmin, setGlobalAdmin] = useState(false)
  // Haftalık Raporlar modülü takım bazlı açılır (2026-09-16): sunucu /me + giriş yanıtında söyler.
  const [weeklyReportsVisible, setWeeklyReportsVisible] = useState(false)
  // Kenar çubuğu açık/daraltılmış — shadcn SidebarProvider'a kontrollü verilir; eski anahtar ('sidebar-open')
  // korunur ki kullanıcının tercihi geçişte kaybolmasın. Hook, auth erken-return'lerinden ÖNCE (kural).
  const [sidebarOpen, setSidebarOpen] = useState(() => {
    try { return localStorage.getItem('sidebar-open') !== 'false' } catch { return true }
  })
  const onSidebarOpenChange = useCallback((next) => {
    setSidebarOpen(next)
    try { localStorage.setItem('sidebar-open', String(next)) } catch { /* depolama yok */ }
  }, [])
  const [teamId, setTeamId] = useState(null)
  const [teamName, setTeamName] = useState(null)
  // Kullanıcının TÜM takım üyelikleri (birincil takım ilk). Takım Yönetimi'ndeki haftalık e-posta
  // anahtarları üye bazlı açıldığı için gerekir; /me ve login yanıtı ikisi de team_ids döndürür.
  const [myTeamIds, setMyTeamIds] = useState([])
  // Üyesi olunan takımlar (id+ad): izleme formlarında takım seçimi (2026-09-18) — /me team_ids × team_names
  const [myTeams, setMyTeams] = useState([])
  // Kullanıcının kendi giriş güvenliği özeti (backend `login_info`): giriş yanıtından VE /me'den
  // gelir. AuthContext yok — üç tüketiciye (uyarı şeridi, Etkinliklerim, kullanıcı menüsü) prop.
  const [loginInfo, setLoginInfo] = useState(null)
  // Kullanıcı menüsü başlık kartı (2026-09-27): /me ve giriş yanıtındaki AD profil alanları (ad soyad, e-posta).
  // Ayrı istek YOK — aynı yük. Telefon/sicil taşınmaz.
  const [profile, setProfile] = useState(null)
  // E1: kişi webhook push tercihi — /me ve login yanıtından gelir, Etkinliklerim'den yazılır.
  const [pushOptOut, setPushOptOut] = useState(false)
  // Ürün turu durumu (2026-09-13): doğruluk kaynağı sunucu (/me + login yanıtı), localStorage yalnız ayna.
  const [tourState, setTourState] = useState(() => readMirror())
  const persistTourRef = useRef(null)   // openCertModal (useCallback, []) güncel persistTour'a ref'ten ulaşır
  const [authChecked, setAuthChecked] = useState(false)
  // Oturum düşüşünde (401 → /?session=expired) giriş formunda "oturum süresi doldu" bildirimi göster (AUTH-1).
  const [sessionExpiredNotice, setSessionExpiredNotice] = useState(initialSessionExpired)
  const [tab, setTab] = useState('dashboard')
  const [pendingAddDomain, setPendingAddDomain] = useState(false)  // dashboard "domain ekle" → envantere geç + add modal
  const [wrResetNonce, setWrResetNonce] = useState(0)
  // Weekly Reports sekmesi zaten açıkken menüye tekrar tıklanınca açık raporu listeye döndür.
  // Ayrıca sekme değişince tarayıcı geçmişine kayıt bırak (pushState) → Geri/İleri düğmeleri
  // sekmeler arası gezinir. Bayat derin-link parametrelerini (monitor/domain/incident) URL'den temizle.
  // extraParams (opsiyonel): hedef sayfanın açılışta okuyacağı param'lar — ör. sürüm çipinden
  // Yardım → Yenilikler (`view=releases`) ya da Sistem Sağlığı → Sürüm & Dağıtım (`sec=releases`).
  // Temizlemeden SONRA yazılır ki PAGE_STATE_PARAMS süpürmesi onları da silmesin.
  const handleTabChange = (id, extraParams) => {
    if (id === 'weeklyreports' && tab === 'weeklyreports') setWrResetNonce((n) => n + 1)
    if (id === tab && extraParams) {
      // Aynı sekme: sayfa yeniden mount olmaz, param'ı mount'ta okuyan sayfa görmezdi (ör. Yardım'dayken
      // çipten "Yenilikler"). URL'e yaz (geçmişe girmeden) + sayfaya olay gönder; HelpPage/SystemHealth dinler.
      try {
        const url = new URL(window.location.href)
        for (const [k, v] of Object.entries(extraParams)) if (v != null && v !== '') url.searchParams.set(k, String(v))
        const qs = url.searchParams.toString()
        window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      } catch { /* history yoksay */ }
      window.dispatchEvent(new CustomEvent(TAB_PARAMS_EVENT, { detail: extraParams }))
      return
    }
    if (id !== tab) {
      try {
        const url = new URL(window.location.href)
        url.searchParams.set('tab', id)
        // Sekme değişince önceki sayfanın TÜM durum paramları temizlenir (bayat filtre/sayfa/modal
        // başka sekmeye taşınmasın) — liste useUrlQuerySync.PAGE_STATE_PARAMS'ta merkezî.
        for (const p of PAGE_STATE_PARAMS) url.searchParams.delete(p)
        // Önekli aileler (ör. Denetim Kaydı'nın `a_*` filtreleri) sabit adla sayılamaz.
        for (const k of [...url.searchParams.keys()]) {
          if (PAGE_STATE_PREFIXES.some(pre => k.startsWith(pre))) url.searchParams.delete(k)
        }
        if (extraParams) {
          for (const [k, v] of Object.entries(extraParams)) if (v != null && v !== '') url.searchParams.set(k, String(v))
        }
        const qs = url.searchParams.toString()
        window.history.pushState({ tab: id }, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      } catch { /* history yoksay */ }
    }
    setTab(id)
  }
  const [certs, setCerts] = useState([])
  const [stats, setStats] = useState(null)
  const [networkStatus, setNetworkStatus] = useState(null)
  const [networkBannerDismissed, setNetworkBannerDismissed] = useState(false)
  const [teamStats, setTeamStats] = useState(null)
  const [weakAlgStats, setWeakAlgStats] = useState(null)
  const [statsVisible, setStatsVisible] = useState(false)
  const [selfPwdModalOpen, setSelfPwdModalOpen] = useState(false)
  const [mustChangePwd, setMustChangePwd] = useState(false)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [sortOrder, setSortOrder] = useState('default')
  const [modalCert, setModalCert] = useState(null)
  const [checkingDomain, setCheckingDomain] = useState(null)   // kart bazlı "çalıştır" kilidi
  // Envanter formu kaydedince açık sertifika modalına "verini tazele" der. Modal kendi 30 sn'lik
  // yoklamasıyla YALNIZ yeni bir KONTROL kaydını yakalıyor; envanter düzenlemesi kontrol üretmez,
  // dolayısıyla modalın Envanter sekmesi elle tazelenene kadar eski değerleri gösterirdi.
  const [certModalRefresh, setCertModalRefresh] = useState(0)
  const [invForm, setInvForm] = useState(null)                 // { domain, mode } — kart → envanter formu
  const [sharedCert, setSharedCert] = useState(null)           // domain — "N alan aynı sertifikayı paylaşıyor" penceresi (2026-09-22)
  const [deletingDomain, setDeletingDomain] = useState(null)   // kart silme sürerken çift tıklamayı kapatır
  // Envanter yazma yetkisi: inventory.crud yalnız bu iki rolde. Kart aksiyonları ve "Domain Ekle"
  // butonu aynı koşulu paylaşır. Kart bazında takım karşılaştırması YAPILMAZ — frontend'de
  // manage-scope listesi yok (/me yalnız üyelik döndürür); yönetilebilir bir takımı yanlışlıkla
  // gizlemektense backend'in 403'üne güveniyoruz (InventoryManager da böyle yapıyor).
  const canManageInventory = systemRole === 'ADMIN' || systemRole === 'TEAM_ADMIN'
  // "Domain Ekle" her kullanıcı seviyesinde (2026-09-18): USER varsayılanı inventory.crud/edit AÇIK; sunucu üyelik doğrular.
  // usePermissions App gövdesinde çalışmaz (provider aşağıda; bkz. cardActions) → rol tabanlı; asıl kapı uçta.
  const canAddInventory = canManageInventory || systemRole === 'USER'
  const canEditCert = (cert) => canManageInventory || (canAddInventory && cert?.team_id != null && myTeamIds.some((id) => String(id) === String(cert.team_id)))
  // Backend'deki SessionScope.isGlobalViewer'ın birebir karşılığı: kapsamsız (global) admin ya da
  // AUDIT. Kapsamlı müdür-admin buraya GİRMEZ — o da takım süzgeciyle çalışır.
  const globalViewer = globalAdmin || systemRole === 'AUDIT'
  const [caModal, setCaModal]     = useState(false)
  const [newDomain,    setNewDomain]    = useState('')
  const [checkLoading, setCheckLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [checkRun, setCheckRun] = useState(null)        // Şimdi Kontrol Et — akan ilerleme modalı (domain başına sonuç satırı)
  const [teamPickerOpen, setTeamPickerOpen] = useState(false)   // kontrol öncesi takım seçimi
  const [lastUpdate, setLastUpdate] = useState(null)
  const [inactivityWarning, setInactivityWarning] = useState(false)
  const [idleCfg, setIdleCfg] = useState(() => ({ totalMs: INACTIVITY_MS, warnMs: WARN_BEFORE_MS }))
  const [countdown, setCountdown] = useState(60)
  const [statsFilter, setStatsFilter] = useState(null)
  const [statusFilter, setStatusFilter] = useState('all')
  const [expiryFilter, setExpiryFilter] = useState('all')
  const [teamFilter, setTeamFilter] = useState('all')
  // Genel Bakış grup/etiket filtresi (2026-09-18, kullanıcı isteği): izleme sayfalarıyla aynı sözleşme.
  const [groupFilter, setGroupFilter] = useState('all')
  const [tagFilter, setTagFilter] = useState('all')
  // Genel Bakış platform süzgeci (2026-09-25, kullanıcı isteği): çoklu seçim (kod listesi; PLATFORM_NONE = girilmemiş).
  // Derin link ?platform=IIS,OPENSHIFT — yalnız pano açılışında okunur (başka sekmeye gelen link panoyu süzmesin).
  const [platformFilter, setPlatformFilter] = useState(() =>
    (initialTabFromUrl() ?? 'dashboard') === 'dashboard' ? parsePlatformParam(readUrlParam(PLATFORM_URL_KEY, null)) : [])
  const [platformCatalog, setPlatformCatalog] = useState([])   // Ayarlar → Platformlar (aktifler, sunucu sırası)
  const [activityRefreshKey, setActivityRefreshKey] = useState(0)
  const [silentAlertDomains, setSilentAlertDomains] = useState(new Set())
  // Genel Bakış kartı zengin görünümü (2026-09-19): /card-extras haritası + Kompakt/Zengin anahtarı.
  // Kullanıcı kararı 2026-09-27: İLK AÇILIŞTA (sayfa yüklemesi / yeni giriş) Zengin; oturum içinde son seçim hatırlanır
  // (durum App'te, sekme değişince kaybolmaz) — tarayıcıya YAZILMAZ; çıkışta Zengin'e döner.
  const [cardExtras, setCardExtras] = useState({})
  const [cardMode, setCardMode] = useState('rich')
  const [planRow, setPlanRow] = useState(null)
  const [confirmingDomain, setConfirmingDomain] = useState(null)
  const [mailFailureDomains, setMailFailureDomains] = useState(new Set())
  const [smtpPreFilterDomain, setSmtpPreFilterDomain] = useState(null)
  const [openSmtpModalOnLoad, setOpenSmtpModalOnLoad] = useState(false)

  const logoutTimer = useRef(null)
  const warnTimer = useRef(null)
  const countdownInterval = useRef(null)
  const refreshPollRef = useRef(null)
  const checkCancelRef = useRef(false)   // "Durdur" bayrağı — döngü kalan domainlere istek atmasın

  useEffect(() => {
    api.getMe().then((res) => {
      if (res?.success) {
        claimPersonalStorage(res.username)   // B9: başka kullanıcının tarayıcıda kalan kişisel kayıtları silinir
        setUser(res.username)
        setSystemRole(res.system_role || 'USER')
        setGlobalAdmin(!!res.global_admin)
        setWeeklyReportsVisible(!!res.weekly_reports_visible)
        setTeamId(res.team_id ?? null)
        setTeamName(res.team_name ?? null)
        setMyTeamIds(Array.isArray(res.team_ids) ? res.team_ids : [])
        setMyTeams(teamsFromMe(res))
        setMustChangePwd(!!res.must_change_password)
        setIdleCfg(idleConfigFrom(res))
        // Giriş güvenliği özeti — F5 sonrası login yanıtı yoktur, bu yüzden /me de aynı bloğu
        // döndürür; alınmazsa özet ve kullanıcı menüsü sayfa yenilemede boşalır.
        setLoginInfo(res.login_info ?? null)
        setProfile(profileFrom(res))
        setPushOptOut(!!res.push_opt_out)
        { const ts = mergeState(res.tour ?? null, readMirror()); setTourState(ts); writeMirror(ts) }
        // Oturum aktif bayrağı: login yalnız bu sekmede yapılmamış olabilir (cookie reauth ya da
        // başka sekmede login). Bayrağı burada da set et ki oturum sonradan düş/süpersede olunca
        // client.js 401'i yakalayıp temiz /?session=expired'a yönlendirsin ("Yüklenemedi" yerine).
        try { sessionStorage.setItem('sm.session.active', '1') } catch { /* sessionStorage yok */ }
        // Mail "tıklayınız" linki: ?tab=weeklyreports → doğrudan ilgili sekme
        const dl = initialTabFromUrl()
        if (dl) setTab(dl)
        // Olay satırına tıklama (cert alarmı): ?domain=<d> → panoyu o domaine filtrele
        try {
          const fd = new URLSearchParams(window.location.search).get('domain')
          if (fd && !readUrlParam('q', null)) setSearch(fd)   // yeni ?q= paramı varsa o kazanır
        } catch { /* yoksay */ }
      }
      setAuthChecked(true)
    }).catch(() => setAuthChecked(true))
  }, [])

  // Tarayıcı Geri/İleri (popstate): URL'deki ?tab= / ?domain='e göre görünümü geri yükle.
  // handleTabChange pushState ile geçmiş kaydı bıraktığından buradaki dinleyici o kayıtları uygular.
  // Not: burada setTab (handleTabChange değil) — popstate sırasında yeni geçmiş kaydı ekleME.
  useEffect(() => {
    const onPop = () => {
      try {
        const p = new URLSearchParams(window.location.search)
        const nextTab = p.get('tab')
        setTab(nextTab && VALID_TABS.has(nextTab) ? nextTab : 'dashboard')
        const d = p.get('domain')
        if (d) setSearch(d)
      } catch { /* yoksay */ }
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  // Programatik sekme geçişi (2026-09-12): kartlar/palet/bildirim kutusu `navigateTo(tab, params)` yayar,
  // burada handleTabChange ile aynı yoldan (URL + geçmiş kaydı) uygulanır. Ref: handleTabChange her render'da yeni.
  const tabChangeRef = useRef(null)
  tabChangeRef.current = handleTabChange
  useEffect(() => {
    const onNav = (e) => {
      const tab = e?.detail?.tab
      if (!tab || !VALID_TABS.has(tab)) return
      tabChangeRef.current?.(tab, e.detail.params)
      // Palet/bildirim sonuçları: ?domain= dashboard aramasına, ?team= takım süzgecine düşer.
      const p = e.detail.params || {}
      if (p.domain) setSearch(String(p.domain))
      if (p.team) setTeamFilter(String(p.team))
    }
    window.addEventListener(NAVIGATE_EVENT, onNav)
    return () => window.removeEventListener(NAVIGATE_EVENT, onNav)
  }, [])

  useEffect(() => {
    if (!user) return

    const doAutoLogout = async () => {
      clearInterval(countdownInterval.current)
      await api.logout()
      try { localStorage.removeItem(REMEMBER_KEY) } catch { /* depolama kapalı */ }
      clearPersonalStorage()   // B9: paylaşılan makinede sonraki kişiye son kullanılanlar / taslak yedeği kalmasın
      setCardMode('rich')      // sonraki giriş Genel Bakış'ı Zengin açar (2026-09-27)
      setUser(null)
      setSystemRole('USER')
      setTeamId(null)
      setTeamName(null)
      setInactivityWarning(false)
      setSessionExpiredNotice(false)   // varsa "oturum süresi doldu" bildirimini temizle
    }

    const resetTimer = () => {
      clearTimeout(logoutTimer.current)
      clearTimeout(warnTimer.current)
      clearInterval(countdownInterval.current)
      setInactivityWarning(false)

      warnTimer.current = setTimeout(() => {
        setInactivityWarning(true)
        setCountdown(Math.round(idleCfg.warnMs / 1000))
        countdownInterval.current = setInterval(() => {
          setCountdown((prev) => {
            if (prev <= 1) {
              clearInterval(countdownInterval.current)
              return 0
            }
            return prev - 1
          })
        }, 1000)
      }, Math.max(1000, idleCfg.totalMs - idleCfg.warnMs))

      logoutTimer.current = setTimeout(doAutoLogout, idleCfg.totalMs)
    }

    const events = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'click']
    events.forEach((e) => document.addEventListener(e, resetTimer, { passive: true }))
    resetTimer()

    return () => {
      clearTimeout(logoutTimer.current)
      clearTimeout(warnTimer.current)
      clearInterval(countdownInterval.current)
      events.forEach((e) => document.removeEventListener(e, resetTimer))
    }
    // idleCfg bağımlılıkta: ayar değiştiğinde (yeniden giriş / F5) zamanlayıcı YENİ süreyle
    // kurulmalı, yoksa eski süre oturum boyunca yaşamaya devam ederdi.
  }, [user, idleCfg])

  // Logout / unmount sonrası gelen geç response'lar setState etmesin → ref ile guard.
  const loadAliveRef = useRef(true)
  useEffect(() => () => { loadAliveRef.current = false }, [])

  const loadData = useCallback(async () => {
    // allSettled: bir endpoint çökse de diğerleri yansısın
    // network-status + weak-algorithms BURADAN çıkarıldı: network → 60 sn tick tek kaynak;
    // weak-algorithms → login'de bir kez (aşağıda) çünkü zayıf-algoritma verisi ancak cert
    // sweep'iyle (~saatlik) değişir → 5 dk'da 100 kullanıcı × tekrar gereksizdi.
    const [certsRes, statsRes, silentRes, teamStatsRes, mailFailRes, extrasRes] = await Promise.allSettled([
      api.getCertificates(), api.getStats(), api.getSilentAlertDomains(), api.getTeamStats(),
      api.getMailFailureDomains(), api.getCardExtras(),
    ])
    if (!loadAliveRef.current) return // unmount/logout'ta state'i kirletme
    const v = (s) => s.status === 'fulfilled' ? s.value : null
    const certs = v(certsRes), stats = v(statsRes), silent = v(silentRes),
          teamStats = v(teamStatsRes),
          mailFail = v(mailFailRes)
    if (certs?.success) { setCerts(certs.data); setLastUpdate(certs.timestamp) }
    if (stats?.success) setStats(stats.data)
    if (silent?.success) setSilentAlertDomains(new Set(silent.data))
    if (mailFail?.success) setMailFailureDomains(new Set(mailFail.data ?? []))
    const extras = v(extrasRes)
    if (extras?.success) setCardExtras(extras.data || {})
    if (teamStats?.success) setTeamStats(teamStats.data)
    // networkStatus → 60 sn tick (dedup); weakAlgStats → login'de bir kez ayrı effect (aşağıda).
  }, [])

  useEffect(() => {
    if (user) {
      loadAliveRef.current = true
      loadData()
      const interval = setInterval(loadData, Number(import.meta.env.VITE_DATA_REFRESH_MS ?? 300_000))
      return () => clearInterval(interval)
    }
  }, [user, loadData])

  // Yeni eklenen alan adı "ısınıyor" (2026-09-28): ilk kontrol arka planda biterken kart boş değil "hesaplanıyor"
  // gösterir; veri gelene dek sertifika + kart ekleri kısa aralıklarla yeniden istenir. Döngü + oturuma bağlılık
  // hooks/useNewDomainWarmup.js'de. Hook auth kapısının (erken return) ÜSTÜNDE.
  const warmRefresh = useCallback(async (d, isAlive) => {
    const [certsRes, extrasRes] = await Promise.allSettled([api.getCertificates(), api.getCardExtras()])
    if (!isAlive() || !loadAliveRef.current) return false   // çıkış yapıldı: yeni oturumun durumuna yazma
    const certs = certsRes.status === 'fulfilled' ? certsRes.value : null
    const extras = extrasRes.status === 'fulfilled' ? extrasRes.value : null
    if (certs?.success) { setCerts(certs.data); setLastUpdate(certs.timestamp) }
    if (extras?.success) setCardExtras(extras.data || {})
    const cert = certs?.success ? (certs.data || []).find((c) => c.domain === d) : null
    return !!(cert?.checked_at && extras?.success && extras.data?.[d])
  }, [])
  const warmingDomains = useNewDomainWarmup(Boolean(user), warmRefresh, loadData)

  // Lightweight 60s poll just for network outage status — keeps banner in sync
  // without waiting for the 5-minute full data refresh
  useEffect(() => {
    if (!user) return
    const tick = async () => {
      const res = await api.getNetworkStatus()
      if (res?.success) {
        setNetworkStatus(prev => {
          if (res.data?.alarm && !prev?.alarm) setNetworkBannerDismissed(false)
          return res.data
        })
      }
    }
    tick()   // login'de hemen (loadData'dan çıkarıldı → ağ durumu 60 sn beklemesin)
    const id = setInterval(tick, 60_000)
    return () => clearInterval(id)
  }, [user])

  // Zayıf-algoritma rozetleri: login'de BİR KEZ (5 dk'lık loadData'dan çıkarıldı). Zayıf-algoritma
  // verisi ancak cert sweep'iyle (~saatlik) değişir → sık çekmeye gerek yok; sayfa yenilenince tazelenir.
  useEffect(() => {
    if (!user) return
    api.admin.getWeakAlgorithms().then(r => { if (r?.success) setWeakAlgStats(r) })
  }, [user])

  // Platform kataloğu (pano platform süzgecinin seçenek adları/sırası): login'de BİR KEZ — aktif liste her oturuma
  // açık (GET /admin/platforms). Okunamazsa süzgeç yine çalışır: seçenekler veriden türer, ad = platform_name ya da kod.
  useEffect(() => {
    if (!user) return
    let alive = true
    api.admin.listPlatforms()
      .then(r => { if (alive && r?.success && Array.isArray(r.data)) setPlatformCatalog(r.data) })
      .catch(() => { /* katalog yok → veriden türet */ })
    return () => { alive = false }
  }, [user])

  // Süpersede edilen oturumu HIZLI yakala: kısa aralıklı hafif yoklama + sekmeye/pencereye
  // dönünce anında kontrol. Oturum başka yerden düşürüldüyse ping 401 döner ve client.js
  // otomatik /?session=expired'a yönlendirir — kullanıcı boştayken bile gecikme ~15 sn.
  useEffect(() => {
    if (!user) return
    const ms = Number(import.meta.env.VITE_SESSION_PING_MS ?? 15_000)
    // Sayfa kullanımı: sekme görünürken hangi sayfada olduğunu taşır (utils: yalnız sekme anahtarı)
    const ping = () => { api.sessionPing(document.visibilityState === 'visible' ? (new URLSearchParams(window.location.search).get('tab') || 'dashboard') : undefined) }
    const id = setInterval(ping, ms)
    const onVisible = () => { if (document.visibilityState === 'visible') ping() }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', ping)
    return () => {
      clearInterval(id)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', ping)
    }
  }, [user])

  // Uyarılar sekmesinin verisi (uyarılar + ağ kesinti geçmişi) pages/WarningsPage içinde yüklenir (2026-09-27).


  async function handleLogout() {
    const ok = await showConfirm({
      title: t('app.logoutTitle'),
      message: t('app.logoutMsg'),
      variant: 'logout',
      confirmText: t('app.logoutConfirm'),
      cancelText: t('app.cancel'),
    })
    if (!ok) return
    clearTimeout(logoutTimer.current)
    clearTimeout(warnTimer.current)
    clearInterval(countdownInterval.current)
    clearInterval(refreshPollRef.current)
    await api.logout()
    try { localStorage.removeItem(REMEMBER_KEY) } catch { /* depolama kapalı: çıkış yine tamamlanır */ }
    clearPersonalStorage()   // B9: paylaşılan makinede sonraki kişiye son kullanılanlar / taslak yedeği kalmasın
    setCardMode('rich')      // sonraki giriş Genel Bakış'ı Zengin açar (2026-09-27)
    setUser(null)
    setSystemRole('USER')
    setTeamId(null)
    setTeamName(null)
    setInactivityWarning(false)
    setRefreshing(false)
    setSessionExpiredNotice(false)   // varsa "oturum süresi doldu" bildirimini temizle
  }

  // "Şimdi Kontrol Et": her domain'i sırayla yeniden kontrol eder; başlangıç/bitiş/süre ölçüp
  // akan modala yazar (alt alta ✓ + zaman). Bittiğinde veriyi tazeler; "Kapat" ile kapanır.
  /**
   * Seçili takımlardaki sertifikaları SIRAYLA kontrol eder ve her satırın SONUCUNU saklar
   * (eskiden yanıt atılıyordu; kalan gün/bitiş/HTTP kolonları oradan geliyor).
   * @param teamKeys seçili takım anahtarları (team_id string'i veya NO_TEAM)
   */
  async function handleRefresh(teamKeys, teamLabel) {
    if (refreshing) return
    const keys = Array.isArray(teamKeys) && teamKeys.length ? teamKeys : null
    const scoped = keys
      ? certs.filter(c => keys.includes(c.team_id != null ? String(c.team_id) : NO_TEAM))
      : certs
    const domains = [...new Set(scoped.map(c => c.domain).filter(Boolean))].sort((a, b) => a.localeCompare(b))
    if (!domains.length) {
      // Bkz. useCheckRun: sessiz erken donus, tiklamanin kaybolmasi gibi okunuyordu.
      toast.info(t('check.noneInScope'))
      return
    }

    setRefreshing(true)
    try {
      checkCancelRef.current = false
      setCheckRun({ rows: [], total: domains.length, done: false, teamLabel: teamLabel || null,
        startedAt: Date.now(), finishedAt: null })

      async function checkOne(domain) {
        const start = new Date()
        const t0 = Date.now()
        let ok = false, data = null, error = null
        try {
          const r = await api.checkDomain(domain)
          ok = !!r?.success
          data = r?.data ?? null
          if (!ok) error = r?.error || null
        } catch (e) { ok = false; error = e?.message || null }
        const end = new Date()
        // Sunucunun ölçtüğü süre daha doğru (ağ gecikmesi hariç); yoksa istemci kronometresi.
        const ms = Number.isFinite(data?.elapsed_ms) ? data.elapsed_ms : Date.now() - t0
        if (data?.status === 'error') { ok = false; error = error || data.error }
        // Satırlar TAMAMLANMA sırasında eklenir (alfabetik değil): yavaş bir domain arkasındakileri
        // bekletmesin. setCheckRun fonksiyonel güncelleme kullanır — eşzamanlı işçiler birbirinin
        // eklediği satırı ezmez.
        setCheckRun(cr => cr ? { ...cr, rows: [...cr.rows, { domain, start, end, ms, ok, data, error }] } : cr)
      }

      // Kuyruğu SINIRLI sayıda işçiyle tüket. Eskiden döngü sıralıydı: timeout alan tek bir sertifika
      // (6 sn) arkasındaki TÜM domainleri bekletiyordu. Sunucu tarafı zaten paralel çalışabiliyor
      // (cert-check executor: core 20 / max 50). "Durdur" → uçuştakiler biter, yenisi başlamaz.
      await runWithConcurrency(domains, checkOne, {
        limit: CHECK_CONCURRENCY,
        shouldStop: () => checkCancelRef.current,
      })

      try {
        const [certsRes, statsRes, silentRes] = await Promise.all([
          api.getCertificates(), api.getStats(), api.getSilentAlertDomains(),
        ])
        if (certsRes?.success) { setCerts(certsRes.data); setLastUpdate(certsRes.timestamp) }
        if (statsRes?.success) setStats(statsRes.data)
        if (silentRes?.success) setSilentAlertDomains(new Set(silentRes.data))
      } catch { /* tazeleme hatası yoksay — modal yine de tamamlanır */ }
      setActivityRefreshKey(k => k + 1)
      setCheckRun(cr => cr ? { ...cr, done: true, finishedAt: Date.now() } : cr)
    } finally {
      setRefreshing(false)
    }
  }

  /** Tek kart için "şimdi koştur". Toplu taramayla aynı ucu kullanır (kalıcı kaydeder). */
  async function runSingleCheck(domain) {
    if (checkingDomain || refreshing) return
    setCheckingDomain(domain)
    try {
      const r = await api.checkDomain(domain)
      const d = r?.data
      if (!r?.success || d?.status === 'error') {
        toast.error(t('card.checkFailed', domain, r?.error || d?.error || '—'))
      } else {
        toast.success(t('card.checkOk', domain))
      }
      // Satır bazlı merge YAPILMAZ: /check yanıtı ham checker map'i; alert_level/team_name/tier
      // içermediği için merge kartın renk sınıfını, T rozetini ve takım satırını sessizce silerdi.
      const [certsRes, statsRes, silentRes] = await Promise.all([
        api.getCertificates(), api.getStats(), api.getSilentAlertDomains(),
      ])
      if (certsRes?.success) { setCerts(certsRes.data); setLastUpdate(certsRes.timestamp) }
      if (statsRes?.success) setStats(statsRes.data)
      if (silentRes?.success) setSilentAlertDomains(new Set(silentRes.data))
      setActivityRefreshKey(k => k + 1)
    } catch (e) {
      toast.error(t('card.checkFailed', domain, e?.message || '—'))
    } finally {
      setCheckingDomain(null)
    }
  }

  /** Kart aksiyon prop'ları. Düzenle/Kopyala yalnız envanteri yönetebilenlere; Çalıştır herkese
   *  (toplu "Şimdi Kontrol Et" de rol kapısı taşımıyor, /check/{domain} yalnız oturum istiyor). */
  /** Kart detayını açar. useCallback ŞART: CertificateCard memo'lu — her render'da yeni bir
   *  fonksiyon üretmek 50 kartın tamamını yeniden render ettirir ve memo'yu boşa çıkarır.
   *  Güncel listeyi ref'ten okur: `certs`i dep yapmak referansı her veri tazelemesinde
   *  değiştirirdi (state setter'ını okuma amaçlı çağırmak da gereksiz render üretir). */
  const certsRef = useRef(certs)
  useEffect(() => { certsRef.current = certs }, [certs])
  /** Zengin kart eylemleri (2026-09-19): sağlık sekmesiyle aç · planlı yenilemeyi onayla · yenileme planla. */
  const openCertHealth = useCallback((d) => {
    const c = certsRef.current.find(x => x.domain === d)
    setModalCert(c ? { ...c, _tab: 'health' } : { domain: d, _tab: 'health' })
  }, [])
  const confirmCardRenewal = useCallback(async (d) => {
    setConfirmingDomain(d)
    try {
      const r = await api.confirmCertificateRenewal(d)
      if (r?.success) { toast.success(t('ccx.confirmed', d)); const x = await api.getCardExtras(); if (x?.success) setCardExtras(x.data || {}) }
      else toast.error(r?.error || t('mon.loadError'))
    } catch (e) { toast.error(String(e?.message || e)) } finally { setConfirmingDomain(null) }
  }, [toast, t])
  const planCardRenewal = useCallback((cert, renewal) => {
    setPlanRow({ domain: cert.domain, renewal_planned_at: renewal?.planned_at || '', renewal_planned_note: renewal?.note || '',
      expiry_key: expiryKey(cert) })
  }, [])
  const toggleCardMode = useCallback(() => {
    setCardMode((m) => (m === 'rich' ? 'compact' : 'rich'))
  }, [])

  const openCertModal = useCallback((d) => {
    // Alan adı STRING beklenir (CertificateCard `onClick(cert.domain)` verir). Satır NESNESİ geçen bir
    // çağıran sessizce hiçbir şey açmıyordu (find hep undefined → modal kapalı), bu yüzden burada normalize
    // ediliyor — sınıfı tek yerde kapatır. (2026-09-22, paylaşılan sertifika penceresi)
    const domain = typeof d === 'string' ? d : d?.domain
    setModalCert(certsRef.current.find(c => c.domain === domain) ?? null)
    // Başlangıç listesi: ilk kart açıldı (sunucuya yalnız henüz işaretli değilse yazılır — TourProvider aynı kuralı sekmeler için uygular)
    const ts = readMirror(); if (ts && ts.status !== 'dismissed' && !ts.checklist?.card && !ts.checklist_hidden) persistTourRef.current?.({ checklist: { card: true } })
  }, [])

  // SSL derin bağlantısı (2026-09-28, 7/24 Kapsamı): ?tab=dashboard&domain=<d>&open=cert → sertifika penceresi; open=noc →
  // envanter formu 7/24 alanına kaydırılmış. `domain` süzgeci yukarıda (getMe / sm:navigate) eskisi gibi. hooks/useCertDeepLink.js
  useCertDeepLink({
    ready: !!user && lastUpdate != null, tab, certs,
    openCert: openCertModal, openByDomain: (d) => setModalCert({ domain: d }),
    editCert: (d) => setInvForm({ domain: d, mode: 'edit' }), canEdit: canEditCert,
    onNotFound: () => toast.error(t('deepLink.notFound')),
    // Geri (Pano'dan ayrılış): YALNIZ kancanın açtığı ve hâlâ açık olan kapanır — kullanıcının elle açtığı pencere/form değil
    close: (kind) => (kind === 'form' ? setInvForm(null) : setModalCert(null)),
    shownCert: modalCert?.domain ?? null, shownForm: invForm?.domain ?? null,
  })

  /**
   * Karttan silme — detay modalinin başlığındaki çöp kutusuyla AYNI akış (ortak yardımcı):
   * aynı onay metni, aynı uçlar, aynı geri bildirim. Kart kısayolu eklenirken ikinci bir kopya
   * yazılsaydı yıkıcı bir eylemin onayı yüzeyden yüzeye ayrışırdı.
   *
   * <p>Silinen kart açık bir detay modaline aitse modal kapatılır: arkasında artık var olmayan
   * bir kaydın verisi durur ve oradaki her düğme 404 üretirdi.
   */
  async function deleteCertFromCard(domain) {
    setDeletingDomain(domain)
    // try/finally: yardımcı ağ hatasını yutuyor ama bayrağın temizlenmesi ÇAĞIRANIN işi —
    // beklenmedik bir istisnada düğme, kart yeniden çizilene kadar kilitli kalırdı.
    let deleted = false
    try {
      deleted = await deleteInventoryByDomain({ domain, showConfirm, toast, t })
    } finally {
      setDeletingDomain(null)
    }
    if (!deleted) return
    setModalCert(prev => (prev?.domain === domain ? null : prev))
    loadData()
  }

  function cardActions(cert) {
    return {
      onCheckNow: () => runSingleCheck(cert.domain),
      checking: checkingDomain === cert.domain || refreshing,
      // 2026-09-18: USER kendi TAKIMININ kaydını düzenler/kopyalar (uç üyelik doğrular); silme yönetici işi.
      onEdit:      canEditCert(cert) ? () => setInvForm({ domain: cert.domain, mode: 'edit' }) : undefined,
      onDuplicate: canEditCert(cert) ? () => setInvForm({ domain: cert.domain, mode: 'duplicate' }) : undefined,
      // Kapı Düzenle/Kopyala ile AYNI: rol tabanlı. usePermissions BURADA çalışmaz —
      // PermissionsProvider App'in KENDİ içinde render ediliyor, App gövdesi context'in
      // ÜSTÜNDE kalır ve canEdit daima false döner (düğme hiç çizilmezdi). Yetkinin asıl
      // kapısı zaten uçta: inventory.crud/edit + takım kapsamı; reddedilirse toast hatayı gösterir.
      onDelete: canManageInventory ? () => deleteCertFromCard(cert.domain) : undefined,
      // "Sorumlu kişi yok" çipi (2026-09-20): form doğrudan Sorumlu Ekipler bölümünde açılır.
      onEditContacts: canEditCert(cert) ? () => setInvForm({ domain: cert.domain, mode: 'edit', focus: 'contacts' }) : undefined,
      deleting: deletingDomain === cert.domain,
    }
  }

  async function handleAddDomain() {
    if (!newDomain.trim() || checkLoading) return
    let domain = newDomain.trim()
    domain = domain.replace(/^https?:\/\//i, '')
    domain = domain.split('/')[0]
    if (domain.includes(':')) domain = domain.split(':')[0]
    if (!domain) return
    setCheckLoading(true)
    try {
      const res = await api.checkDomainPreview(domain)
      if (res?.data) {
        setNewDomain('')
        setModalCert({ ...res.data, domain: res.data.domain || domain, _preview: true })
      } else {
        // BASARISIZLIK SESSIZDI: giris kutusu temizleniyor, modal acilmiyor, hicbir toast
        // cikmiyordu — kullanici tiklamanin islenip islenmedigini anlayamiyordu. Girdi de
        // artik yalnizca BASARIDA temizleniyor ki kullanici yazdigini duzeltebilsin.
        toast.error(t('card.checkFailed', domain, res?.error || '—'))
      }
    } catch (e) {
      // Ag hatasinda ayrica YAKALANMAMIS promise reddi olusuyordu.
      toast.error(t('card.checkFailed', domain, e?.message || '—'))
    } finally {
      setCheckLoading(false)
    }
  }

  function handleLogin(userData) {
    // Duyuru "hero"su ve giriş güvenliği uyarısı her GERÇEK girişte bir kez görünsün
    // (sayfa yenilemede tekrar etmesin).
    try { sessionStorage.removeItem('sm.banner.heroShown') } catch { /* yoksay */ }
    try { sessionStorage.removeItem('sm.login.noticeShown') } catch { /* yoksay */ }
    // B9: oturum düşüp BAŞKA biri girdiyse öncekinin kişisel kayıtları silinir; aynı kişide (kesinti yedeği) korunur.
    claimPersonalStorage(userData.username)
    setTab(initialTabFromUrl() || 'dashboard')
    setUser(userData.username)
    setSystemRole(userData.system_role || 'USER')
    setGlobalAdmin(!!userData.global_admin)
    setWeeklyReportsVisible(!!userData.weekly_reports_visible)
    setTeamId(userData.team_id ?? null)
    setTeamName(userData.team_name ?? null)
    setMyTeamIds(Array.isArray(userData.team_ids) ? userData.team_ids : [])
    setMyTeams(teamsFromMe(userData))
    setIdleCfg(idleConfigFrom(userData))
    setMustChangePwd(!!userData.must_change_password)
    setLoginInfo(userData.login_info ?? null)
    setProfile(profileFrom(userData))
    setPushOptOut(!!userData.push_opt_out)
    { const ts = mergeState(userData.tour ?? null, readMirror()); setTourState(ts); writeMirror(ts) }
  }

  /** Tur durumu yazımı: sunucu birleştirir ve güncel hâli döner; ayna da o hâle çekilir. */
  const persistTour = useCallback(async (patch) => {
    try {
      const r = await api.setTourState(patch)
      if (r?.success) { const ts = r.tour ?? null; setTourState(ts); writeMirror(ts) }
    } catch { /* çevrimdışı: ayna kalır, bir sonraki yazımda sunucu birleştirir */ }
  }, [])
  persistTourRef.current = persistTour
  const tourCtx = useMemo(() => ({
    role: systemRole, globalAdmin, canWrite: canManageInventory, mustChangePwd, tab,
    ready: !!user && !mustChangePwd && lastUpdate != null,   // veri geldi → karşılama kartı boş ekrana çıkmasın
  }), [systemRole, globalAdmin, canManageInventory, mustChangePwd, tab, user, lastUpdate])

  const weakDomainSet = useMemo(
    () => new Set((weakAlgStats?.data ?? []).map(d => d.domain)),
    [weakAlgStats]
  )

  // domain → envanter bilgisi (takım/tier/port) — kontrol tablosu satırlarını zenginleştirir.
  const certIndex = useMemo(() => {
    const idx = {}
    for (const c of certs) if (c?.domain) idx[c.domain] = { team_name: c.team_name, tier: c.tier, port: c.port }
    return idx
  }, [certs])

  const issuerStats = useMemo(() => {
    const reachable = certs.filter(c => c.status !== 'error')
    if (reachable.length === 0) return null
    const counts = {}
    for (const c of reachable) {
      const key = c.issuer || c.issuer_cn || 'Unknown'
      counts[key] = (counts[key] ?? 0) + 1
    }
    const entries = Object.entries(counts).sort((a, b) => b[1] - a[1])
    const [dominantIssuer, dominantCount] = entries[0]
    const dominantPct = Math.round((dominantCount / reachable.length) * 100)
    return { uniqueCount: entries.length, dominantIssuer, dominantCount, dominantPct }
  }, [certs])

  // Sertifika doğrulama/güvenlik sorunları (süre & zayıf-algo hariç) — birleşik "Sertifika Sorunu" kartı.
  const certIssueStats = useMemo(() => {
    if (certs.length === 0) return null
    let total = 0, revoked = 0, chain = 0, trust = 0, deployment = 0
    for (const c of certs) {
      const r  = c.revocation_status === 'REVOKED'
      const ch = c.chain_status === 'BROKEN' || c.chain_status === 'INCOMPLETE'
      const tr = c.trust_status === 'UNTRUSTED'
      const d  = c.deployment_status === 'INCOMPLETE' || c.deployment_status === 'MISMATCH'
      if (r)  revoked++
      if (ch) chain++
      if (tr) trust++
      if (d)  deployment++
      if (r || ch || tr || d) total++
    }
    return { total, revoked, chain, trust, deployment }
  }, [certs])

  // Oturum açıldığında (login ya da zaten geçerli oturumla açılış) URL'deki
  // ?session=expired bildirimini adres çubuğundan temizle — login olunca
  // kaybolmuyordu. Diğer paramlar (ör. ?tab=) korunur.
  useEffect(() => {
    if (!user) return
    try {
      const url = new URL(window.location.href)
      if (url.searchParams.has('session')) {
        url.searchParams.delete('session')
        const qs = url.searchParams.toString()
        window.history.replaceState({}, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      }
    } catch { /* yoksay */ }
  }, [user])

  // ── Dashboard türetme zinciri + sayfalama hook'u ──────────────────────────
  // DİKKAT: usePagination bir HOOK — aşağıdaki koşullu erken-return'lerden (authChecked/user/mustChangePwd)
  // ÖNCE çağrılmak zorunda; sonrasına konursa login geçişinde hook sayısı değişir ve React
  // "Rendered more hooks" ile çöker (2026-08-05'te yaşandı). Zincir saf hesap, her render'da ucuz.
  const STAT_FILTER_FN = {
    total:      () => true,
    valid:      (c) => !c.warning && c.status !== 'error',
    critical:   (c) => c.alert_level ? c.alert_level === 'critical' : (c.warning === true && c.days_remaining != null && c.days_remaining <= 7),
    high:       (c) => c.alert_level ? c.alert_level === 'high'     : (c.warning === true && c.days_remaining != null && c.days_remaining > 7 && c.days_remaining <= 15),
    warning:    (c) => c.alert_level ? c.alert_level === 'warning' : (c.warning === true && c.status !== 'error'),
    error:      (c) => c.status === 'error',
    expiring7:  (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 7,
    expiring30: (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 30,
    expired:    (c) => c.days_remaining != null && c.days_remaining < 0,
    weak:       (c) => weakDomainSet.has(c.domain),
    certissue:  (c) => c.revocation_status === 'REVOKED' || c.chain_status === 'BROKEN' || c.chain_status === 'INCOMPLETE' || c.trust_status === 'UNTRUSTED' || c.deployment_status === 'INCOMPLETE' || c.deployment_status === 'MISMATCH',
  }
  const STAT_FILTER_LABEL = {
    total: t('stat.total'), valid: t('stat.valid'), critical: t('stat.critical'), high: t('stat.high'),
    warning: t('stat.warning'), error: t('stat.error'), expiring7: t('stat.expiring7'), expiring30: t('stat.expiring30'), expired: t('stat.expired'),
    weak: t('stat.weak'), certissue: t('stat.certIssue'),
  }

  function handleStatClick(key) {
    const next = statsFilter === key ? null : key
    setStatsFilter(next)
    handleTabChange('dashboard')
  }

  const STATUS_FILTER_FN = {
    all:     () => true,
    valid:   (c) => !c.warning && c.status !== 'error',
    warning: (c) => c.warning === true && c.status !== 'error',
    error:   (c) => c.status === 'error',
  }
  const EXPIRY_FILTER_FN = {
    all:      () => true,
    expired:  (c) => c.days_remaining != null && c.days_remaining < 0,
    days7:    (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 7,
    days30:   (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 30,
    days90:   (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 90,
  }

  const statFn   = statsFilter ? STAT_FILTER_FN[statsFilter] : null
  const statusFn = STATUS_FILTER_FN[statusFilter] ?? (() => true)
  const expiryFn = EXPIRY_FILTER_FN[expiryFilter] ?? (() => true)

  // Takım filtresi seçenekleri — cert listesinden türetilir (yeni endpoint yok).
  // MEMO ŞART: App saniyede bir yeniden render olabiliyor (inaktivite geri sayımı) ve "Şimdi Kontrol Et"
  // her sonuçta setCheckRun ile tüm ağacı tazeliyor; 1000 sertifikada Set+sort her seferde yeniden
  // koşuyordu. 8 monitör sayfasının hepsi bu bloğu zaten useMemo ile sarıyor, App sarmıyordu.
  const teamOptions = useMemo(() => {
    const names = new Set()
    let hasNone = false
    for (const c of certs) { if (c.team_name) names.add(c.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [certs, t])
  const hasTeamOptions = teamOptions.some((o) => o.value !== 'all' && o.value !== '__none__')
  // Grup / etiket seçenekleri kart listesinden türer (envanter group_name/tags); "__none__" atanmamışları bulur.
  const groupOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const c of certs) { if (c.group_name) names.add(c.group_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allGroups') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noGroup') })
    return opts
  }, [certs, t])
  const hasGroupOptions = groupOptions.length > 1
  const tagOptions = useMemo(() => {
    const names = tagNamesOf(certs)
    const opts = [{ value: 'all', label: t('mon.allTags') }]
    names.forEach((n) => opts.push({ value: n, label: n }))
    if (certs.some((c) => !(c.tags || '').trim())) opts.push({ value: '__none__', label: t('mon.noTags') })
    return opts
  }, [certs, t])
  const hasTagOptions = tagOptions.length > 1
  // "Filtreleri temizle" (2026-09-18): herhangi bir daraltma varken görünür; hepsini varsayılana döndürür.
  const dashFiltersActive = !!search || sortOrder !== 'default' || statusFilter !== 'all' || expiryFilter !== 'all'
    || teamFilter !== 'all' || groupFilter !== 'all' || tagFilter !== 'all' || !!statsFilter || platformFilter.length > 0
  const clearDashFilters = () => {
    setSearch(''); setSortOrder('default'); setStatusFilter('all'); setExpiryFilter('all')
    setTeamFilter('all'); setGroupFilter('all'); setTagFilter('all'); setStatsFilter(null); setPlatformFilter([])
  }

  // Platform DIŞINDAKİ bütün süzgeçler (2026-09-25): platform seçeneklerinin sayıları buradan sayılır — her
  // seçenek "diğer süzgeçler + bu platform" ile kaç kart kalacağını söyler (faset sayısı; seçim sayıları kaydırmaz).
  const preFiltered = useMemo(() => certs.filter((c) => {
    if (statFn   && !statFn(c))   return false
    if (!statusFn(c))              return false
    if (!expiryFn(c))              return false
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (c.team_name) return false }
      else if (c.team_name !== teamFilter) return false
    }
    if (groupFilter !== 'all') {
      if (groupFilter === '__none__') { if (c.group_name) return false }
      else if (c.group_name !== groupFilter) return false
    }
    if (!matchesTag(c, tagFilter)) return false
    if (!search)                   return true
    if (matchesGroupOrTagText(c, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    const s = search.toLowerCase()
    return c.domain?.toLowerCase().includes(s) || c.issuer?.toLowerCase().includes(s) || c.subject?.toLowerCase().includes(s)
  // Bağımlılıklar FİLTRE ANAHTARLARI: statusFn/expiryFn `?? (() => true)` ile her render'da YENİ
  // fonksiyon üretiyor; onları dep olarak vermek memo'yu tümüyle boşa çıkarırdı.
  }), [certs, statsFilter, statusFilter, expiryFilter, teamFilter, groupFilter, tagFilter, search])
  const platformCounts = useMemo(() => countPlatforms(preFiltered), [preFiltered])
  const platformOptions = useMemo(() => buildPlatformOptions({
    catalog: platformCatalog, certs, counts: platformCounts, selected: platformFilter, noneLabel: t('app.platformNone'),
  }), [platformCatalog, certs, platformCounts, platformFilter, t])
  // Katalog boş + hiçbir kartta platform yoksa süzgeç gizli (tek seçenek "Belirtilmemiş" olurdu); URL'den gelen seçim
  // varsa HER ZAMAN görünür — kaldırılabilsin.
  const showPlatformFilter = platformFilter.length > 0 || platformOptions.some((o) => o.value !== PLATFORM_NONE)
  // "SSL Checker" alanı (domain kutusu + düğme TEK birim: Enter da düğme de handleAddDomain) — Genel Bakış kart
  // listesinin başlık satırının EN SONUNDA, EN SAĞA yaslı (2026-09-26, kullanıcı isteği); kalan yeri doldurur (en az
  // 22rem, en çok 34rem), sığmazsa alt satıra tek parça sarılır. Tüm Sertifikalar'da `sslCheckerHeader` (sayfa başlığı).
  const sslCheckerField = () => (
    <div className="flex w-full gap-2 sm:ml-auto sm:w-auto sm:min-w-[22rem] sm:max-w-[34rem] sm:flex-1" data-tour="add-domain">
      <Input type="text" placeholder={t('app.newDomainPlaceholder')} aria-label={t('app.newDomainPlaceholder')}
        className="h-8 min-w-0 flex-1"
        value={newDomain} onChange={(e) => setNewDomain(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && handleAddDomain()} />
      <Button variant="success" size="sm" onClick={handleAddDomain} disabled={checkLoading}>
        {checkLoading ? t('app.checkingDomain') : t('app.checkBtn')}
      </Button>
    </div>
  )
  // Tüm Sertifikalar BAŞLIĞINDAKİ SSL Checker (2026-09-27): eskiden sayfanın üstünde ayrı bir kontrol satırındaydı (iki başlık
  // üst üste). shadcn InputGroup — alan + düğme tek kutu; telefonda başlığın altında tam genişlik (48 px kutu, 40 px düğme —
  // dokunma hedefi), geniş ekranda 22rem ve Pano boyu (36 px).
  const sslCheckerHeader = (
    <InputGroup data-tour="add-domain" className="h-12 w-full max-sm:min-w-full sm:h-9 sm:w-[22rem]">
      <InputGroupInput type="text" placeholder={t('app.newDomainPlaceholder')} aria-label={t('app.newDomainPlaceholder')}
        value={newDomain} onChange={(e) => setNewDomain(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && handleAddDomain()} />
      <InputGroupAddon align="inline-end">
        <InputGroupButton variant="success" size="sm" className="h-10 sm:h-7" onClick={handleAddDomain} disabled={checkLoading}
          aria-busy={checkLoading || undefined}>
          {checkLoading ? t('app.checkingDomain') : t('app.checkBtn')}
        </InputGroupButton>
      </InputGroupAddon>
    </InputGroup>
  )
  // "Şimdi Kontrol Et" — Tüm Sertifikalar başlığında ikincil düğme (Pano başlığıyla aynı görünüm); Envanter'e prop olarak geçer.
  const checkNowLabel = refreshing ? t('app.checkedOf', checkRun?.rows.length ?? 0, checkRun?.total ?? 0) : t('app.checkNow')
  // Pano boru hattının son halkası: platform (VEYA içinde) diğer süzgeçlerle VE. Sıralama/sayfalama/sayaçlar bunu izler.
  const filtered = useMemo(
    () => (platformFilter.length > 0 ? preFiltered.filter((c) => matchesPlatform(c, platformFilter)) : preFiltered),
    [preFiltered, platformFilter])

  function defaultPriority(c) {
    const al = c.alert_level
    const days = c.days_remaining
    const isError    = al ? al === 'error'    : c.status === 'error'
    const isExpired  = !isError && (al ? al === 'expired'  : (days != null && days < 0))
    const isCritical = !isError && !isExpired && (al ? al === 'critical' : (days != null && days <= 7))
    const isHigh     = !isError && !isExpired && !isCritical && (al ? al === 'high'     : (days != null && days <= 15))
    const isWarning  = !isError && !isExpired && !isCritical && !isHigh && (al ? al === 'warning'  : c.warning === true)
    if (isError)             return 0
    if (isExpired || isCritical) return 1
    if (isHigh)              return 2
    if (isWarning)           return 3
    return 4
  }

  const sorted = useMemo(() => [...filtered].sort((a, b) => {
    if (sortOrder === 'asc')  return (a.days_remaining ?? 999999) - (b.days_remaining ?? 999999)
    if (sortOrder === 'desc') return (b.days_remaining ?? -1) - (a.days_remaining ?? -1)
    const pd = defaultPriority(a) - defaultPriority(b)
    if (pd !== 0) return pd
    return (a.days_remaining ?? 999999) - (b.days_remaining ?? 999999)
  }), [filtered, sortOrder])

  // Sayfalama standardı: "Tümü" seçeneği kaldırıldı (binlerce kart tek seferde render edilmesin; max 200/sayfa).
  // Sayfalama standardı (2026-09-26): süzgeç DEĞERİ değişince sayfa 1 (resetDeps; eskiden 12 ayrı elle `setPage(1)`),
  // `page`/`ps` adresi yalnız Genel Bakış sekmesindeyken okunur/yazılır (ps ön ayar listesine karşı doğrulanır).
  const dashPager = usePagination(sorted, {
    listKey: 'dashboard-certs', preset: 'page',
    resetDeps: [search, sortOrder, statusFilter, expiryFilter, teamFilter, groupFilter, tagFilter, statsFilter, platformFilter],
    url: tab === 'dashboard' ? DASH_PAGE_URL : null,
  })
  // Uyarılar sekmesinin sayfalaması artık pages/WarningsPage içinde (aynı listKey 'warnings-certs', URL wa_page/wa_ps).

  // Paylaşılabilir URL (dashboard): arama + sayfa. enabled guard ŞART — App her sekmede mount olduğundan
  // bu sync başka sekmedeki sayfanın q/page paramlarını ezerdi. Yazma yalnız q'ya (?domain= e-posta
  // linkleri okunmaya devam eder ama yeni linkler q üretir).
  useUrlQuerySync({
    q: search.trim() || null,
    [PLATFORM_URL_KEY]: serializePlatformParam(platformFilter),   // ?platform=IIS,__none__ (PAGE_STATE_PARAMS'ta)
  }, { enabled: tab === 'dashboard' })

  // KULLANICI KARARI (2026-08-06): marka logosu HER ZAMAN nötr yeşil ("ok") — navbar ve favicon
  // durumla renk değiştirmez; filo sağlığı rozet/sayaçlarda ve e-posta varyantlarında anlatılır.
  // Durum-duyarlı görünüm istenirse: deriveGlobalStatus(stats, networkStatus?.alarm) buraya bağlanır
  // (utils/brandStatus.js + hook hazır bekliyor). Hook erken-return'lerin ÜSTÜNDE kalmalı.
  useStatusFavicon('ok')

  if (!authChecked) {
    return (
      <div className="loading" style={{ marginTop: 80, textAlign: 'center' }}>
        <BrandLogo status="muted" size={64} style={{ marginBottom: 12 }} />
        <div>{t('app.loading')}</div>
      </div>
    )
  }
  // Login ekranında da duyuru görünür (public /api/branding) — orada sol menü yok, tam genişlik doğru.
  if (!user) return (<><AnnouncementBanner /><Login onLogin={handleLogin} sessionExpired={sessionExpiredNotice} /></>)
  if (mustChangePwd) {
    // User was auto-reset by an admin — block all of the app until they
    // pick a new password. PasswordChangeModal in forced-change mode hides
    // the cancel button and ignores overlay clicks.
    return (
      <PasswordChangeModal
        mode="forced-change"
        targetUser={{ id: null, username: user }}
        onClose={() => {}}
        onSuccess={() => setMustChangePwd(false)}
      />
    )
  }


  return (
    <PermissionsProvider user={user}>
    <UserDirectoryProvider>
    <TeamDirectoryProvider>
    <TourProvider ctx={tourCtx} tourState={tourState} onPersist={persistTour}>
    <SidebarProvider open={sidebarOpen} onOpenChange={onSidebarOpenChange} className="app-layout">

      {inactivityWarning && (
        // Oturum zaman aşımı şeridi (eski .inactivity-warning) — Tailwind + shadcn Button; telefonda sarar.
        <div data-slot="inactivity-warning"
          className="fixed inset-x-0 top-0 z-(--z-critical) flex flex-wrap items-center justify-center gap-3 bg-linear-to-r from-[#e65c00] to-[#f9d423] px-4 py-3 text-[#1a1a1a] shadow-lg animate-in slide-in-from-top motion-reduce:animate-none sm:gap-5 sm:px-6 sm:py-3.5">
          <span className="text-[.95em] [&_strong]:text-[1.1em] [&_strong]:tabular-nums"
            dangerouslySetInnerHTML={{ __html: t('app.inactivityWarn', `<strong>${countdown}</strong>`) }} />
          <Button type="button" size="sm" className="bg-[#1a1a1a] px-5 font-bold text-white hover:bg-zinc-800"
            onClick={() => setInactivityWarning(false)}>{t('app.stayLoggedIn')}</Button>
        </div>
      )}

      <Nav activeTab={tab} onTabChange={handleTabChange} username={user} teamName={teamName} myTeams={myTeams} systemRole={systemRole}
        globalAdmin={globalAdmin} loginInfo={loginInfo} profile={profile} weeklyReportsVisible={weeklyReportsVisible}
        onLogout={handleLogout} onChangePassword={() => setSelfPwdModalOpen(true)} />

      {selfPwdModalOpen && user && (
        <PasswordChangeModal
          mode="self-change"
          targetUser={{ id: null, username: user }}
          onClose={() => setSelfPwdModalOpen(false)}
        />
      )}

      {/* bg-transparent: sayfa zemini .app-layout'tan (--bg); kartlar beyaz kalsın */}
      <SidebarInset className="app-main bg-transparent">
        {/* Mobil (<768px): kenar çubuğu çekmece (Sheet) olur — menü düğmesi + marka + bildirimler burada */}
        <MobileTopBar onTabChange={handleTabChange} username={user} />
        {/* Bağlama duyarlı yardım (2026-09-12, #24): sağ altta "?", o sayfanın kılavuz bölümü yan panelde */}
        <HelpDrawer tab={tab} />
        <TourPageChip tab={tab} />
        <AnnouncementBanner heroOnMount />
        {/* Yalnız şüpheli durumda (önceki girişten bu yana başarısız deneme varsa) görünür. */}
        <LastLoginNotice info={loginInfo} />
        <div className="app-body">

          {/* Genel Bakış: sayfa başlığı + eylem çubuğu (ui/PageHeader, 2026-09-27 kullanıcı isteği). "Şimdi Kontrol Et"
              ve "Domain Ekle" başlıksız bir kontrol satırında yüzüyordu; artık başlığın sağında, her sayfadaki
              yerinde: Yenile · Şimdi Kontrol Et (ikincil, ilerleme düğmenin üstünde) · Domain Ekle (BİRİNCİL, en sağda).
              Meta çipleri: sertifika sayısı + son güncelleme — kullanıcı "veri ne kadar taze" sorusunu başlıkta görür. */}
          {tab === 'dashboard' && (
            <PageHeader icon={LayoutDashboard} title={t('app.dashTitle')} description={t('app.dashDesc')}
              meta={(
                <>
                  <Badge variant="secondary" data-slot="dash-cert-count">{t('app.certCount', certs.length)}</Badge>
                  <span data-slot="dash-last-update">
                    {t('app.lastUpdate')} {lastUpdate ? formatDate(lastUpdate) : t('app.neverUpdated')}
                  </span>
                </>
              )}
              actions={(
                <>
                  <Button type="button" variant="outline" onClick={loadData} disabled={refreshing}
                    title={t('app.refresh')} aria-label={t('app.refresh')}>
                    <RefreshCw aria-hidden="true" /><span className="hidden md:inline">{t('app.refresh')}</span>
                  </Button>
                  <Button type="button" variant={canAddInventory ? 'secondary' : 'default'} data-tour="check-now"
                    onClick={() => setTeamPickerOpen(true)} disabled={refreshing}
                    title={t('app.checkNowTip')} aria-busy={refreshing || undefined}>
                    {refreshing
                      ? <><Spinner decorative inline />{t('app.checkedOf', checkRun?.rows.length ?? 0, checkRun?.total ?? 0)}</>
                      : <><PlayCircle aria-hidden="true" />{t('app.checkNow')}</>}
                  </Button>
                  {canAddInventory && (
                    <Button type="button" data-slot="dash-add-domain"
                      onClick={() => { setPendingAddDomain(true); handleTabChange('domains') }}>
                      <Plus aria-hidden="true" /> {t('inv.addBtn')}
                    </Button>
                  )}
                </>
              )} />
          )}
          {/* Tüm Sertifikalar / Domain Envanteri: eski üst kontrol satırı (.controls — Şimdi Kontrol Et + SSL Checker) kalktı
              (2026-09-27, iki başlık üst üste). Şimdi Kontrol Et her iki sayfanın kendi PageHeader'ında; SSL Checker Tüm
              Sertifikalar başlığında (sslCheckerHeader), Envanter'de "Domain ekle" ile aynı işi gördüğü için yok. */}

          {networkStatus?.alarm && !networkBannerDismissed && (
            // Ağ kesintisi uyarısı — ui/AlertBanner (shadcn Alert); eski .network-outage-banner'ın sol şeridi YOK.
            <AlertBanner tone="danger" role="alert" icon={AlertOctagon} className="mb-4"
              title={t('app.networkOutageTitle')}
              onDismiss={() => setNetworkBannerDismissed(true)} dismissLabel={t('app.dismiss')}>
              {t('app.networkOutageDesc',
                networkStatus.detected_at ? formatDate(networkStatus.detected_at) : '—')}
            </AlertBanner>
          )}

          {/* 7/24 kapsam şeridi (2026-09-27): kapsamdaki aktif izlemelerden 7/24 izleme ekibine GİTMEYENLER varsa sakin uyarı +
              "İncele" → 7/24 Kapsamı. Pano damgasıyla (lastUpdate) tazelenir — kendi yoklaması yok; oturumluk kapatılabilir. */}
          {tab === 'dashboard' && <NocCoverageBanner globalAdmin={globalAdmin} refreshKey={lastUpdate} />}

          {tab === 'dashboard' && (
            <div className="stats-section" data-tour="dash-stats">
              {/* shadcn Collapsible (ui/CollapsibleSection) — eski el yapımı div.stats-collapse-bar[role=button] */}
              <CollapsibleSection open={statsVisible} onOpenChange={setStatsVisible}
                icon={BarChart3} label={t('app.statistics')} hint={t('app.expandStats')}
                toggleLabel={statsVisible ? t('app.collapseStats') : t('app.expandStats')}
                triggerClassName="mb-2" contentClassName="pt-1">
                <StatsPanel stats={stats} visible={statsVisible}
                  onStatClick={handleStatClick} activeFilter={statsFilter}
                  weakStats={weakAlgStats} issuerStats={issuerStats}
                  certIssueStats={certIssueStats}
                  onCaClick={() => setCaModal(true)} />
                {/* "Son 7 günde ne değişti" (2026-09-12, #7): anlık sayaçların altında tek satır hareket özeti */}
                <RecentChangesLine />
              </CollapsibleSection>
              {/* "Sizin için — bugün" (2026-09-12, #3): İstatistikler'in hemen altında ve onunla AYNI hizada — kart
                  kabının (.content, 24 px iç boşluk) DIŞINDA (2026-09-27, kullanıcı: "istatistiklerle simetrik değil"). */}
              <div data-slot="today-section">
                <TodayPanel onOpenDomain={(d) => setModalCert(certs.find(c => c.domain === d) ?? { domain: d })} />
              </div>
            </div>
          )}

          <div className="content">
           <ErrorBoundary key={tab} onReload={() => setTab(tab)}>
            <Suspense fallback={<LoadingBlock label={t('tbl.loading')} fullWidth />}>
            {tab === 'dashboard' && (
              <div className="tab-content active">
                {/* "Sizin için — bugün" paneli artık yukarıda, İstatistikler'in altında (stats-section içinde). */}
                <div className="sort-controls sort-bar" data-tour="dash-filters">
                  {/* Arama kutusu 2026-09-19'da üst kontrol satırına ("Domain Ekle"nin yanına) taşındı; burada yalnız sıralama/süzgeçler. */}
                  {/* htmlFor ↔ id: seçici tetiği role="combobox" ve adını İÇERİKTEN almaz; bağsız
                      etiket ekran okuyucuya adsız bir liste bırakıyordu (2026-09-25, R17). */}
                  <label htmlFor="dash-f-sort">{t('app.sortLabel')}</label>
                  <SearchableSelect
                    id="dash-f-sort"
                    value={sortOrder}
                    onChange={v => { setSortOrder(v) }}
                    options={[
                      { value: 'default', label: t('app.sortDefault') },
                      { value: 'asc',     label: t('app.sortAsc') },
                      { value: 'desc',    label: t('app.sortDesc') },
                    ]}
                  />
                  <label htmlFor="dash-f-status">{t('app.statusLabel')}</label>
                  <SearchableSelect
                    id="dash-f-status"
                    value={statusFilter}
                    onChange={v => { setStatusFilter(v) }}
                    options={[
                      { value: 'all',     label: t('app.all') },
                      { value: 'valid',   label: t('app.valid') },
                      { value: 'warning', label: t('app.warning') },
                      { value: 'error',   label: t('app.error') },
                    ]}
                  />
                  <label htmlFor="dash-f-expiry">{t('app.expiryLabel')}</label>
                  <SearchableSelect
                    id="dash-f-expiry"
                    value={expiryFilter}
                    onChange={v => { setExpiryFilter(v) }}
                    options={[
                      { value: 'all',     label: t('app.all') },
                      { value: 'expired', label: t('app.expired') },
                      { value: 'days7',   label: t('app.days7') },
                      { value: 'days30',  label: t('app.days30') },
                      { value: 'days90',  label: t('app.days90') },
                    ]}
                  />
                  {hasTeamOptions && (
                    <span className="sort-bar-field">
                      <label htmlFor="dash-f-team">{t('app.teamLabel')}</label>
                      <SearchableSelect
                        id="dash-f-team"
                        value={teamFilter}
                        onChange={v => { setTeamFilter(v) }}
                        options={teamOptions}
                      />
                    </span>
                  )}
                  {hasGroupOptions && (
                    <span className="sort-bar-field">
                      <label htmlFor="dash-f-group">{t('app.groupLabel')}</label>
                      <SearchableSelect
                        id="dash-f-group"
                        value={groupFilter}
                        onChange={v => { setGroupFilter(v) }}
                        options={groupOptions} searchThreshold={2}
                      />
                    </span>
                  )}
                  {hasTagOptions && (
                    <span className="sort-bar-field">
                      <label htmlFor="dash-f-tag">{t('app.tagLabel')}</label>
                      <SearchableSelect
                        id="dash-f-tag"
                        value={tagFilter}
                        onChange={v => { setTagFilter(v) }}
                        options={tagOptions} searchThreshold={2}
                      />
                    </span>
                  )}
                  {dashFiltersActive && (
                    <Button type="button" variant="secondary" size="sm" onClick={clearDashFilters}>
                      {t('app.clearFilters')}
                    </Button>
                  )}
                </div>
                {/* Başlangıç listesi (ürün turu, 2026-09-13): yeni kullanıcıya ilk adımlar; biter ya da kapatılırsa kaybolur */}
                <OnboardingChecklist />
                <div className="dashboard-header">
                  <div className="dashboard-title-row">
                    {/* Sayfa başlığı artık üstteki PageHeader'da; bu satır kart listesinin araç çubuğu (2026-09-27). */}
                    <h2>{t('app.cardsTitle')}</h2>
                    {/* Kart görünümü — ortak ui/CardDensityToggle (izleme sayfalarıyla aynı bileşen, 2026-09-27) */}
                    <CardDensityToggle value={cardMode} onChange={(v) => { if (v !== cardMode) toggleCardMode() }} tip={t('ccx.modeTip')} />
                    {/* Domain ara — kart görünümü seçicisinin yanında (2026-09-25, kullanıcı isteği; eskiden üst
                        kontrol satırındaydı). shadcn InputGroup: büyüteç + doluysa temizle düğmesi. */}
                    <InputGroup className="h-8 w-64 max-w-full">
                      <InputGroupAddon><Search /></InputGroupAddon>
                      <InputGroupInput
                        type="text"
                        placeholder={t('app.searchPlaceholder')}
                        aria-label={t('app.searchPlaceholder')}
                        value={search}
                        onChange={(e) => { setSearch(e.target.value) }}
                      />
                      {search && (
                        <InputGroupAddon align="inline-end">
                          <InputGroupButton size="icon-xs" onClick={() => { setSearch('') }}
                            title={t('app.clearFilter')} aria-label={t('app.clearFilter')}>
                            <X />
                          </InputGroupButton>
                        </InputGroupAddon>
                      )}
                    </InputGroup>
                    {/* Platform süzgeci (2026-09-25, kullanıcı isteği) — shadcn faset süzgeci; aramanın yanında, diğer
                        süzgeçlerle VE. Seçenekler katalog + verideki kodlar + Belirtilmemiş, sayılar o anki süzgeçlerle. */}
                    {showPlatformFilter && (
                      <FacetedFilter
                        title={t('app.platformFilter')}
                        icon={Layers}
                        tooltip={t('app.platformFilterTip')}
                        searchPlaceholder={t('app.platformSearch')}
                        options={platformOptions}
                        value={platformFilter}
                        onChange={(next) => { setPlatformFilter(next) }}
                      />
                    )}
                    {sslCheckerField()}
                  </div>
                  {statsFilter && (
                    <div className="stats-filter-bar">
                      <span>
                        {t('app.filterPrefix')} <strong>{STAT_FILTER_LABEL[statsFilter]}</strong>
                        {t('app.filterCerts', filtered.length)}
                      </span>
                      <Button type="button" variant="outline" size="xs"
                        className="border-primary/50 font-semibold text-primary hover:bg-primary hover:text-primary-foreground"
                        onClick={() => {
                          setStatsFilter(null)
                          setStatusFilter('all')
                          setExpiryFilter('all')
                          setGroupFilter('all')
                          setTagFilter('all')
                          setPlatformFilter([])
                          setSearch('')
                          setSortOrder('default')
                        }}
                      >
                        {t('app.clearFilter')}
                      </Button>
                    </div>
                  )}
                </div>
                {sorted.length === 0 && lastUpdate == null ? (
                  /* İlk veri gelene kadar "Sertifika bulunamadı" DEĞİL yükleniyor (kullanıcı bildirimi 2026-09-21, QA ISSUE-006):
                     certs [] ile başlar, getCertificates yanıtı gecikince boş durum sahte "sertifika yok" algısı veriyordu.
                     lastUpdate yalnız ilk başarılı yanıtta dolar — tur kapısındaki "veri geldi" sinyaliyle aynı. */
                  <StatusBlock tone="neutral" icon={Loader2} loading title={t('app.loadingCerts')} role="status" />
                ) : sorted.length === 0 ? (
                  <StatusBlock tone="neutral" icon={Inbox} title={statsFilter ? t('app.noFilterCerts', STAT_FILTER_LABEL[statsFilter]) : t('app.noCerts')} description={certs.length > 0 ? t('empty.hintFilter') : t('empty.hintCerts')} />
                ) : (
                  <>
                    {/* Sertifika kart ızgarası: en küçük kart 340 px (2026-09-24), dar kapta tek sütun (mobil-önce) */}
                    <div data-slot="cert-grid" className="grid grid-cols-[repeat(auto-fill,minmax(min(340px,100%),1fr))] gap-3.5 sm:gap-5">
                      {dashPager.pageItems.map((cert, ci) => (
                        <CertificateCard key={cert.domain} cert={cert} onClick={openCertModal} tourId={ci === 0 ? 'first-card' : undefined}
                          extra={cardMode === 'rich' ? cardExtras[cert.domain] : undefined}
                          warming={warmingDomains.has(cert.domain)} extrasPending={cardMode === 'rich' && warmingDomains.has(cert.domain)}
                          live={cardExtras[cert.domain] ? { uptime: cardExtras[cert.domain].uptime, alert: cardExtras[cert.domain].last_alert, renewal: cardExtras[cert.domain].renewal } : undefined}
                          onOpenHealth={openCertHealth} onConfirmRenewal={confirmCardRenewal} onPlanRenewal={planCardRenewal} confirming={confirmingDomain === cert.domain}
                          onOpenShared={setSharedCert}
                          hasSilentAlert={silentAlertDomains.has(cert.domain)}
                          hasMailFailure={mailFailureDomains.has(cert.domain)}
                          onMailFailureClick={() => {
                            handleTabChange('health')
                            setSmtpPreFilterDomain(cert.domain)
                            setOpenSmtpModalOnLoad(true)
                          }}
                          isWeak={weakAlgStats != null ? weakDomainSet.has(cert.domain) : undefined}
                          {...cardActions(cert)} />
                      ))}
                    </div>
                    <PaginationBar {...dashPager} />
                  </>
                )}
              </div>
            )}

            {tab === 'stats' && (
              <div className="tab-content active">
                <PageHeader icon={TAB_META.stats.Icon} title={t('app.statsTitle')} description={t('app.statsDesc')} />
                <StatsView certs={certs} teamStats={teamStats} onRowClick={(d) => setModalCert(certs.find(c => c.domain === d) ?? null)}
                  canAddDomain={systemRole === 'ADMIN' || systemRole === 'TEAM_ADMIN'}
                  onAddDomain={() => { setPendingAddDomain(true); handleTabChange('domains') }} />
              </div>
            )}

            {/* Dikkat Gerektiren Sertifikalar — pages/WarningsPage (2026-09-27 shadcn + mobil web yeniden tasarımı). Veri
                (uyarılar + ağ kesinti geçmişi) sayfada yüklenir; App kart eylemlerini, pencereleri ve "Şimdi Kontrol Et"
                akışını prop'la verir. refreshKey = son güncelleme: 5 dk döngüsü / kontrol bitince liste de tazelenir. */}
            {tab === 'warnings' && (
              <div className="tab-content active">
                <WarningsPage certs={certs} refreshKey={lastUpdate} cardExtras={cardExtras}
                  silentAlertDomains={silentAlertDomains} mailFailureDomains={mailFailureDomains}
                  weakDomains={weakAlgStats != null ? weakDomainSet : null}
                  // Pano listesinde henüz olmayan alan (veri gelmeden) → pencere alan adıyla açılır, sessizce düşmez
                  onOpenCert={(d) => (certs.some((c) => c.domain === d) ? openCertModal(d) : setModalCert({ domain: d }))}
                  onOpenHealth={openCertHealth} onPlanRenewal={planCardRenewal} cardActions={cardActions}
                  // 'health' sekmesi: preFilterDomain/openSmtpModalOnLoad'ı SystemHealth tüketir (2026-08-19 düzeltmesi)
                  onMailFailure={(d) => { handleTabChange('health'); setSmtpPreFilterDomain(d); setOpenSmtpModalOnLoad(true) }}
                  onCheckAll={() => setTeamPickerOpen(true)} checkingAll={refreshing}
                  checkProgress={refreshing ? t('app.checkedOf', checkRun?.rows.length ?? 0, checkRun?.total ?? 0) : null} />
              </div>
            )}

            {tab === 'all' && (
              <div className="tab-content active">
                {/* Başlık + eylemler (2026-09-27): Şimdi Kontrol Et (ikincil) · SSL Checker (alan + yeşil düğme, en sağda) —
                    eskiden sayfanın üstündeki ayrı .controls satırındaydı. Telefonda eylemler başlığın altında tam genişlik. */}
                <PageHeader icon={TAB_META.all.Icon} title={t('app.allTitle')} description={t('app.allDesc')}
                  actions={(
                    <>
                      <Button type="button" variant="secondary" data-tour="check-now" className="h-10 sm:h-9"
                        onClick={() => setTeamPickerOpen(true)} disabled={refreshing}
                        title={t('app.checkNowTip')} aria-busy={refreshing || undefined}>
                        {refreshing ? <Spinner decorative inline /> : <PlayCircle aria-hidden="true" />}
                        <span className="tabular-nums">{checkNowLabel}</span>
                      </Button>
                      {sslCheckerHeader}
                    </>
                  )} />
                {/* refreshKey=lastUpdate: App'in 5 dk yenilemesi ve "Şimdi Kontrol Et" tabloya sessiz tazeleme olarak düşer (2026-09-13) */}
                <CertificatesTable
                  onRowClick={(d, tab) => { const c = certs.find(x => x.domain === d); setModalCert(c ? (tab ? { ...c, _tab: tab } : c) : null) }}
                  refreshKey={lastUpdate}
                  onCheckNow={runSingleCheck} checkingDomain={refreshing ? '*' : checkingDomain}
                  onEdit={canManageInventory ? (d) => setInvForm({ domain: d, mode: 'edit' }) : undefined}
                  canManage={canManageInventory} globalAdmin={globalAdmin} onRefresh={loadData}
                  // Başka takımın satırı (org geneli görünürlük, 2026-09-26): Pano listesinde yok → pencere satırla, salt okunur açılır
                  onOpenReadOnly={(row, tab) => setModalCert({ ...row, _readOnly: true, ...(tab ? { _tab: tab } : {}) })} />
              </div>
            )}

            {tab === 'renewal' && (
              <div className="tab-content active">
                {/* Açıklama + Yenile/Dışa aktar RenewalAdvice'ın kendi üst satırında — burada yalnız başlık */}
                <PageHeader icon={TAB_META.renewal.Icon} title={t('app.renewalTitle')} />
                <RenewalAdvice onSelectDomain={(d) => setModalCert(certs.find(c => c.domain === d) ?? { domain: d })} />
              </div>
            )}

            {tab === 'renewal-guide' && (
              <div className="tab-content active">
                <CertRenewalGuide isAdmin={systemRole === 'ADMIN'} />
              </div>
            )}

            {tab === 'activity' && (
              <div className="tab-content active">
                <PageHeader icon={TAB_META.activity.Icon} title={t('app.activityTitle')} description={t('app.activityDesc')} />
                <ActivityLog refreshTrigger={activityRefreshKey} />
              </div>
            )}

            {tab === 'myactivity' && (
              <div className="tab-content active">
                {/* Amaç cümlesi + Cihazlarım/Parola eylemleri MyAuditLog'un kendi üst satırında — burada yalnız başlık */}
                <PageHeader icon={TAB_META.myactivity.Icon} title={t('app.myAuditTitle')} />
                <MyAuditLog loginInfo={loginInfo} onChangePassword={() => setSelfPwdModalOpen(true)}
                  pushOptOut={pushOptOut}
                  onPushOptOutChange={async (v) => {
                    // İyimser güncelleme YOK: sunucu onayı gelmeden işaret değişmez — başarısız
                    // yazmada kullanıcı "kapattım" sanıp push almaya devam ederdi (tersi de kötü).
                    const res = await api.me.setPushOptOut(v)
                    if (res?.success) setPushOptOut(!!res.push_opt_out)
                  }} />
              </div>
            )}

            {tab === 'alerthistory' && (
              <div className="tab-content active">
                {/* Başlık AlertHistory'nin kendi ui/PageHeader'ında (2026-09-27) — burada ikinci bir <h2> çift başlık çizerdi */}
                {/* globalViewer + myTeamIds: 7/24 operatörü başka takımın uyarısını görür ama sahiplenemez/çözemez (sunucu 403) */}
                <AlertHistory urlSync globalViewer={globalViewer} myTeamIds={myTeamIds} />
              </div>
            )}

            {tab === 'incidents' && (
              <div className="tab-content active">
                <IncidentsPage systemRole={systemRole} teamId={teamId} teamName={teamName} />
              </div>
            )}

            {/* 7/24 Kapsamı (2026-09-27): hangi izlemeler gece kesintisinde 7/24 izleme ekibine gitmiyor, neden; tek tık aç,
                takım arama listesi. Görüş kapsamı sunucuda (viewTeamIds); refreshKey = Pano damgası (5 dk döngüsü). */}
            {tab === 'noc' && (
              <div className="tab-content active">
                <NocCoveragePage systemRole={systemRole} globalAdmin={globalAdmin} myTeamIds={myTeamIds} myTeams={myTeams}
                  userId={profile?.user_id ?? null} refreshKey={lastUpdate} />
              </div>
            )}

            {tab === 'maintenance' && (
              <div className="tab-content active">
                <MaintenanceWindowsPage systemRole={systemRole} teamId={teamId} teamName={teamName} globalAdmin={globalAdmin} />
              </div>
            )}

            {tab === 'domains' && (
              <div className="tab-content active">
                <InventoryManager onInventoryChange={loadData} systemRole={systemRole} teams={myTeams} globalAdmin={globalAdmin}   // USER: form takım kutusu üyesi olduğu takımlar (2026-09-18)
                  openAddSignal={pendingAddDomain} onAddConsumed={() => setPendingAddDomain(false)}
                  // "Şimdi Kontrol Et" Envanter başlığında (eski üst .controls satırının yerine, 2026-09-27)
                  onCheckNow={() => setTeamPickerOpen(true)} checkRunning={refreshing} checkLabel={checkNowLabel} />
              </div>
            )}

            {tab === 'admin' && (
              <div className="tab-content active">
                <PageHeader icon={TAB_META.admin.Icon} title={t('app.adminTitle')} description={t('app.adminDesc')} />
                <AdminPanel systemRole={systemRole} ownTeamId={teamId} myTeamIds={myTeamIds} currentUsername={user} />
              </div>
            )}

            {/* Yetki Yönetimi (2026-09-25): herkese açık; düzenleme yalnız global admin (sunucunun can_edit'i). */}
            {tab === 'permissions' && (
              <div className="tab-content active">
                {/* Başlık PermissionMatrix kendi header'ında (ikon + Reset) — çift başlık olmasın */}
                <PermissionMatrix />
              </div>
            )}

            {/* Uygulama ayarları — rol ADMIN (global VEYA kapsamlı müdür). Eskiden `user === 'admin'`
                sabit kapısıydı; 2026-09-10'da önce global_admin bayrağına, aynı gün ürün kararıyla
                kapsamlı müdüre de açıldı. Müdür için sır yüzeyleri (SMTP/LDAP/Secret Decryptor/Veritabanı)
                AdminSettings içinde "yalnız global yönetici" notuyla kilitli; backend aynı kapıyı uygular. */}
            {tab === 'settings' && systemRole === 'ADMIN' && (
              <div className="tab-content active">
                <AdminSettings globalAdmin={globalAdmin} />
              </div>
            )}

            {/* Denetim Logu (2026-09-25): herkese açık; admin/AUDIT sistem geneli, diğerleri ekip arkadaşlarının kayıtları. */}
            {tab === 'system' && (
              <div className="tab-content active">
                {/* Amaç, kapsam rozeti ve eylemler AuditLogViewer'ın kendi üst satırında — burada yalnız başlık */}
                <PageHeader icon={TAB_META.system.Icon} title={t('app.systemTitle')} />
                <AuditLogViewer fullScope={globalAdmin || systemRole === 'AUDIT'} />
              </div>
            )}

            {/* İzleme değişiklik konsolu — TÜM takım kullanıcılarına açık, takım-kapsamlı.
                Denetim konsolu (system sekmesi) güvenlik kaydını gösterir ve sistem-geneli
                olduğu için admin/AUDIT'te kalır; bu ekran ürün geçmişini gösterir: kim hangi
                izlemeyi ekledi/değiştirdi, hangi değerlerle. Uç zaten viewTeamIds ile
                sınırlıyor — menüyü gizlemek kullanıcıyı yalnız KENDİ verisinden mahrum bırakırdı.

                globalViewer, backend'deki SessionScope.isGlobalViewer'ın birebir karşılığı:
                kapsamlı müdür-admin systemRole==='ADMIN' olsa da globalAdmin DEĞİLDİR (yukarıdaki
                tanıma bakınız), yani o da takım süzgeciyle çalışır. */}
            {tab === 'monitorchanges' && (
              <div className="tab-content active">
                <PageHeader icon={TAB_META.monitorchanges.Icon} title={t('chg.consoleTitle')}
                  description={globalViewer ? t('chg.consoleSubtitle') : t('chg.consoleSubtitleTeam')} />
                <MonitorChangesConsole globalViewer={globalViewer} />
              </div>
            )}

            {tab === 'weakalgo' && (
              <div className="tab-content active">
                <PageHeader icon={TAB_META.weakalgo.Icon} title={t('app.weakAlgoTitle')} description={t('app.weakAlgoDesc')} />
                <WeakAlgorithmReport />
              </div>
            )}

            {/* Derin bağlantı da kapıdan geçer: modül kapalıysa sayfa hiç çizilmez (2026-09-16). */}
            {tab === 'weeklyreports' && weeklyReportsVisible && (
              <div className="tab-content active">
                <WeeklyReportsPage systemRole={systemRole} teamId={teamId} teamName={teamName} resetNonce={wrResetNonce} />
              </div>
            )}
            {tab === 'weeklyreports' && !weeklyReportsVisible && (
              <div className="tab-content active">
                <StatusBlock tone="info" icon={CalendarDays} title={t('wracc.hiddenTitle')} description={t('wracc.hiddenBody')} />
              </div>
            )}

            {tab === 'incident-history' && (
              <div className="tab-content active">
                <IncidentHistoryPage />
              </div>
            )}

            {tab === 'login-issues' && (
              <div className="tab-content active">
                {/* Kapsamlı müdür (ADMIN ama global değil): yönetici uçları 403 → kendi bildirimleri görünümü (2026-09-28) */}
                <LoginIssueReports scopedAdmin={systemRole === 'ADMIN' && !globalAdmin} />
              </div>
            )}

            {tab === 'sqlplayground' && globalAdmin && (
              <div className="tab-content active">
                {/* Başlık SqlPlayground'ın kendi ui/PageHeader'ında (2026-09-27) — burada ikinci bir <h2> çift başlık çizerdi */}
                <SqlPlayground />
              </div>
            )}

            {tab === 'health' && (
              <div className="tab-content active">
                <PageHeader icon={TAB_META.health.Icon} title={t('app.healthTitle')} description={t('app.healthDesc')} />
                <SystemHealth
                  systemRole={systemRole}
                  globalAdmin={globalAdmin}
                  username={user}
                  preFilterDomain={smtpPreFilterDomain}
                  openSmtpModalOnLoad={openSmtpModalOnLoad}
                  onSmtpPreFilterConsumed={() => {
                    setSmtpPreFilterDomain(null)
                    setOpenSmtpModalOnLoad(false)
                  }}
                />
              </div>
            )}

            {tab === 'help'     && <HelpPage />}
            {tab === 'uptime'   && <UptimePage   systemRole={systemRole} />}
            {tab === 'http'     && <HttpMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'domain'   && <DomainMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'port'     && <PortMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'dns'      && <DnsMonitorPage  systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'keyword'  && <KeywordMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'ping'     && <PingMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'page'     && <PageMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'pagespeed' && <PageSpeedMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'scripted' && <ScriptedMonitorPage systemRole={systemRole} teamId={teamId} teamName={teamName} myTeams={myTeams} globalAdmin={globalAdmin} />}
            {tab === 'forecast' && <ExpiryForecastPage onSelectDomain={(d) => setModalCert(certs.find(c => c.domain === d) ?? { domain: d })} />}
            </Suspense>
           </ErrorBoundary>
          </div>

          <footer className="footer">
            <p>{t('app.lastUpdate')} {lastUpdate ? formatDate(lastUpdate) : t('app.neverUpdated')}</p>
          </footer>

        </div>
      </SidebarInset>

      {/* Çalıştır/Düzenle KARTLA AYNI kaynaktan (`cardActions`) gelir — modal içinde ikinci bir
          kontrol/düzenleme yolu tanımlanmaz. Önizleme (envanterde olmayan domain) modunda ikisi
          de anlamsız: kayıtlı adres yok, düzenlenecek envanter satırı yok. */}
      <CertificateModal domain={modalCert?.domain} alertLevel={modalCert?.alert_level} initialData={modalCert?._preview ? modalCert : undefined} previewMode={!!modalCert?._preview} currentUser={user} currentUserRole={systemRole} onClose={() => setModalCert(null)} initialTab={modalCert?._tab}
        refreshSignal={certModalRefresh}
        readOnly={!!modalCert?._readOnly}
        readOnlyTeam={modalCert?._readOnly ? { id: modalCert.team_id, name: modalCert.team_name } : null}
        {...(modalCert && !modalCert._preview && !modalCert._readOnly ? cardActions(modalCert) : {})} />
      {caModal && <CaDiversityModal certs={certs} onClose={() => setCaModal(false)} />}
      {planRow && <RenewalPlanModal row={planRow} onClose={() => setPlanRow(null)}
        onSaved={async () => { setPlanRow(null); const x = await api.getCardExtras(); if (x?.success) setCardExtras(x.data || {}) }}
        onCleared={async () => { setPlanRow(null); const x = await api.getCardExtras(); if (x?.success) setCardExtras(x.data || {}) }} />}

      {/* Kart → envanter formu (Düzenle / Kopyala). Kendi Suspense sınırı: yukarıdaki sınır sekme
          içeriğiyle birlikte kapanıyor ve eager import MDEditor'ü dashboard'un ilk chunk'ına sokardı. */}
      {/* Paylaşılan sertifika penceresi (2026-09-22): kart çipinden açılır; alan seçilince o alanın kartı açılır */}
      {sharedCert && (
        <Suspense fallback={null}>
          <SharedCertificateModal domain={sharedCert} onClose={() => setSharedCert(null)}
            onSelectDomain={(d) => { const c = certs.find((x) => x.domain === d); if (c) openCertModal(d); else setModalCert({ domain: d, _preview: true }) }} />
        </Suspense>
      )}

      {invForm && (
        <Suspense fallback={null}>
          <InventoryFormModalForDomain
            domain={invForm.domain}
            mode={invForm.mode}
            focus={invForm.focus || null}
            // R4 (2026-09-25): InventoryManager yoluyla AYNI yetki sözleşmesi. canWrite sarmalayıcıda matristen
            // okunur (usePermissions App gövdesinde çalışmaz). Takım aktarımı yalnız rol ADMIN'de: sunucu
            // (updateInventory) TEAM_ADMIN ve USER için takımı mevcut değere sabitler.
            canManage={canManageInventory}
            canMoveTeam={systemRole === 'ADMIN'}
            canOpenSettings={globalAdmin}   // 7/24 alanı "aktif grup yok" → Ayarlar bağlantısı yalnız global yöneticiye
            onClose={() => setInvForm(null)}
            // Mükerrer alan adı bandından aktarım / geri yükleme (2026-09-28): form kapanır, taşınan kaydın penceresi açılır
            onSaved={(_res, domain, opts) => { setInvForm(null); loadData(); setCertModalRefresh(k => k + 1); if (opts?.open && domain) setModalCert({ domain, team_id: opts.record?.team_id ?? null }) }}
          />
        </Suspense>
      )}

      {/* Şimdi Kontrol Et — önce takım seçimi, sonra akan sonuç tablosu */}
      {teamPickerOpen && (
        <CheckTeamPicker
          certs={certs}
          onClose={() => setTeamPickerOpen(false)}
          onStart={(keys, label) => { setTeamPickerOpen(false); handleRefresh(keys, label) }}
        />
      )}
      <CheckRunModal
        run={checkRun}
        certIndex={certIndex}
        onCancel={() => { checkCancelRef.current = true }}
        onClose={() => { checkCancelRef.current = true; setCheckRun(null) }}
      />
    </SidebarProvider>
    </TourProvider>
    </TeamDirectoryProvider>
    </UserDirectoryProvider>
    </PermissionsProvider>
  )
}
