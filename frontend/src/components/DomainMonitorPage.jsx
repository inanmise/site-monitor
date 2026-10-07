import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { sortMonitorsDefault } from '../utils/monitorSort.js'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TagInput from './ui/TagInput.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import { Trash2, CalendarClock, FlaskConical, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, HelpCircle, ShieldAlert, Activity, Inbox, LockOpen, ShieldOff, ServerCrash, Ban, CalendarX, Download, ChevronDown, CalendarPlus, Copy } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import DomainRegistrationTab from './DomainRegistrationTab.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { CheckFailureBlock } from './checks/CheckFailurePanel.jsx'
import useFailureRows, { failurePanelId, failureRowKey } from './checks/useFailureRows.js'
import { isHealthy } from './checks/checkFailureModel.js'
import DomainExpiryTrace from './DomainExpiryTrace.jsx'
import DomainExpiryTrend from './DomainExpiryTrend.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { markMonitorDeleted, monitorKind, useWithoutDeleted } from '../utils/recentlyDeleted.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { exportDomainsCsv, exportDomainsPdf } from '../utils/exportDomains.js'
import RenewalPlanModal from './RenewalPlanModal.jsx'   // yenileme planı (2026-09-22, H) — sertifikayla ortak modal
import ModalShell from './ui/ModalShell.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { loadNocGroupNames } from './noc/forms/useNocFormOptions.js'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Input } from '@/components/shadcn/input'
import { TabsContent } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import DomainMonitorCard from './domain/DomainMonitorCard.jsx'
import DomainDetailHeader, { DETAIL_PHONE_FULLSCREEN } from './domain/detail/DomainDetailHeader.jsx'
import { SOON_DAYS, alarmMatchesStatus, daysTone, expiryKey, fmtExpiry, sourceTag, statusKey } from './domain/domainCardModel.js'
import { useCardDensity } from '../hooks/useCardDensity.js'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { MonitorDetailModal, DetailDivider, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint,
} from './monitoring/MonitorForm.jsx'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const REFRESH_INTERVAL = 60
// Varsayılan sıra KALAN GÜN ARTAN (2026-10-01 kullanıcı kararı: "kullanıcı ilk baktığında en az süresi kalanları görsün") —
// süresi geçmiş (eksi gün) en üstte, günü bilinmeyenler en sonda, eşit günde alan adı A→Z. Dokuz türün ortak kuralı
// (sorunlu → grup → ad) seçicide `default` adıyla duruyor.
const SORTS = ['days_asc', 'default', 'days_desc', 'name', 'registrar', 'team', 'changed']   // registrar/takım/son değişiklik (2026-09-22, B)
/** Hızlı süzgeç (2026-09-22, B): URL `dq`. 'all' | 'nolock' | 'unsigned' | 'soon' | 'rdap' | 'whois' | 'alarm' */
const QUICK = ['all', 'soon', 'nolock', 'unsigned', 'alarm', 'rdap', 'whois']
const QUICK_PRED = {
  all: () => true,
  soon: m => m.days_remaining != null && m.days_remaining <= SOON_DAYS,
  nolock: m => m.transfer_lock === 'NONE',
  unsigned: m => m.dnssec === 'unsigned',
  alarm: m => !!m.active_alarm,
  rdap: m => m.source === 'RDAP',
  whois: m => m.source === 'WHOIS',
}
/**
 * İstatistik kartı yüklemleri (2026-09-26 kullanıcı seçimi) — sayım ve tıklayınca süzme AYNI yüklemden geçer, iki yol
 * ayrışmaz. Bilinmeyen (null) değerler sayılmaz: ölçülmemiş bir NS'i "çözülmüyor" demek yanlış alarm olurdu.
 */
const STAT_PRED = {
  nsfail: m => m.ns_resolves === false,
  unsigned: QUICK_PRED.unsigned,
  blacklisted: m => m.blacklist_status === 'LISTED',   // CLEAN / UNKNOWN / SKIPPED sayılmaz (DnsblCheckerService)
  expired: m => m.days_remaining != null && m.days_remaining < 0,
}
// Domain kaydi gunde birkac kez sorgulanir; taban SAAT olcegindedir (WHOIS/RDAP nezaketi).
// Diger turlerdeki dakika olcegi burada anlamsiz olurdu — bu yuzden liste TURE OZEL.
const INTERVALS = [
  { value: 3600,  labelKey: 'notify.iv1h'  },
  { value: 21600, labelKey: 'notify.iv6h'  },
  { value: 43200, labelKey: 'notify.iv12h' },
  { value: 86400, labelKey: 'notify.iv24h' },
]

const emptyForm = {
  name: '', domain: '', groupName: '', tags: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '',
  thresholdsCsv: '60,30,14,7,3,1', warningDays: 30, criticalDays: 7, intervalSeconds: 86400, active: true,
  checkTimeoutMs: '',
  // Koruma anahtarlari: kilit ve degisiklik ACIK (bugunku fiili davranis), kara liste KAPALI
  // (her kontrolde dis DNS sorgusu uretir — bilincli acilmali).
  transferLockAlert: true, blacklistEnabled: false, changeAlert: true,
  notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30,
}

/** URL yapıştırılmış girdiyi host'a indirger: https://www.x.com.tr/path → www.x.com.tr
 *  (şema/path/query/userinfo/port soyulur). Backend otoritedir; bu yalnız anlık UX normalizasyonu. */
function normalizeDomainInput(s) {
  if (!s) return ''
  return s.trim().replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split(/[/?#]/)[0]
    .split('@').pop().split(':')[0].replace(/\.$/, '').toLowerCase()
}

// Bitiş tarihi / kaynak etiketi / kalan gün tonu / koruma ve EPP rozetleri: domain/domainCardModel.js +
// domain/DomainCardParts.jsx (kart yeniden tasarımı 2026-09-27) — detay penceresi ve geçmiş satırı da oradan okur.

/** Geçmiş satırındaki durum metni tonu (eski .dom-st--up/warn/down/unknown). */
const STATUS_TEXT = {
  up: 'text-success', warn: 'text-amber-700 dark:text-amber-400', down: 'text-destructive', unknown: 'text-muted-foreground',
}

export default function DomainMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
  const canManageRow = (m) => isAdmin || isOwnTeam(m)
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  // 2026-09-29: + sunucunun satır bayrağı `can_check` (tetik ucunun kapısıyla AYNI kural — kapsamlı yönetici görebildiği
  // ama çalıştıramadığı başka takım satırını "Şimdi Kontrol Et (N)" sayısına katmaz, toplu koşumda 403 yemez).
  const canCheckRow = (m) => canManageRow(m) && m?.can_check !== false
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  // "Sorun Tanıla" (2026-10-05): rol değil sunucunun satır bayrağı `can_diagnose` (= can_check + diagnostics.run) — tanılama
  // ucu artık kullanıcının işletebildiği takımın alan adı izlemesini de kabul ediyor. Liste satırına da bakılır ("Şimdi
  // kontrol et" açık detayın kopyasını tetik yanıtıyla değiştirir, yanıtta bayrak olmayabilir). Formdaki (kaydedilmemiş alan
  // adı) Sorun Tanıla bilinçli olarak yalnız admin: rastgele alan adında uç 403 döner.
  const canDiagnoseRow = (m) => !!m && (m.can_diagnose === true || monitors.some((x) => x.id === m.id && x.can_diagnose === true))
  // Toplu seçim — dokuz izleme sayfasının STANDARDI (2026-09-26 kullanıcı bildirimi: Alan Adı sayfasında kart sol üstten
  // seçilemiyor, toplu Duraklat/Sürdür/takım/grup/sil yoktu). Yalnız yönetebildiği satırlar seçilebilir (Http ile aynı).
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })
  // Kontrol geçmişi hata teşhisi (2026-10-05): açık hata panelleri (satır anahtarıyla)
  const failRows = useFailureRows()
  const [rawMonitors, setMonitors] = useState([])
  // Silme anında (2026-10-07): silinen kart tam liste yüklemesini BEKLEMEDEN düşer, bayat yanıt geri getiremez.
  const monitors = useWithoutDeleted(monitorKind('domain'), rawMonitors)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  const fe = useFormErrors(modal)   // doğrulama hataları alanın altında + ilk hatalıya kaydırma (2026-09-30)
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (defaultTeamName || t('app.noTeam'))
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [defaults, setDefaults] = useState(null)
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [testing, setTesting] = useState(false)
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('control')
  const deepLinkTab = useDeepLinkTab()   // ?monitor=…&mtab=changes derin bağlantısı — ilk açılışta bir kez
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)
  const [diag, setDiag] = useState(null)   // Sorun Tanıla modalı: { domain, loading?, data?, error? }
  const diagSeq = useRef(0)                 // yalnız EN SON tanılamanın yanıtı pencereye yazılır (bkz. diagnose)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [sortBy, setSortBy] = useState(() => (SORTS.includes(readUrlParam('sort', 'days_asc')) ? readUrlParam('sort', 'days_asc') : 'days_asc'))
  const [quick, setQuick] = useState(() => { const v = readUrlParam('dq', 'all'); return QUICK.includes(v) ? v : 'all' })   // hızlı süzgeç (2026-09-22)
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [loadNonce, setLoadNonce] = useState(0)   // her başarılı yüklemede artar: başlık çipi geri sayımı kendisi sayar, sayfa saniyede bir çizilmez (2026-10-01)
  // Dışa aktarım menüsü (2026-09-22, D): görünen (süzülmüş + sıralanmış) liste CSV/PDF. shadcn DropdownMenu —
  // dış tıklama / Escape / odak iadesi Radix'te (eskiden elle document dinleyicileri vardı).
  const [planRow, setPlanRow] = useState(null)   // yenileme planı modalı: izleme satırı (2026-09-22, H)
  const [density, setDensity] = useCardDensity('domain')   // Kompakt / Zengin kart (2026-09-27): her açılış Zengin başlar, Kompakt seçimi kalıcı DEĞİL
  const [exporting, setExporting] = useState(false)

  const load = useCallback(async () => {
    // HATA DALI: eskiden else yoktu → API düşünce liste boş kalıyor ve ekran
    // "Henüz izleme yok, ekleyin" diyordu; kullanıcı monitörlerinin SİLİNDİĞİNİ sanıyordu.
    // Ayrıca useVisibleInterval her 60 sn sessizce başarısız olmaya devam ediyordu.
    // AG HATASI DA BU DALA DUSMELI: api/client.js request() ag hatasinda {success:false} DONDURMEZ,
    // throw eder (yalniz AbortError yumusak payload doner) ve bu cagrida timeoutMs verilmedigi
    // icin varsayilan 0 = timeout YOK. try/catch olmadan promise reject oluyordu: setLoadError de
    // setLoading(false) de HIC calismiyor, ekran iskelette kaliyor, hata bandi cikmiyor ve konsolda
    // yalnizca "unhandled rejection" goruluyordu. Yani hata dali yazilmisti ama EN SIK tetiklenen
    // hata turu ona hic ulasmiyordu.
    try {
      const res = await api.monitoring.getDomainMonitors()
      if (res?.success) { setMonitors(res.data); setLoadError(null) }
      else setLoadError(res?.error || 'load failed')
    } catch (e) {
      setLoadError(e?.message || 'network error')
    } finally {
      setLoading(false); setLoadNonce((n) => n + 1)
    }
  }, [])
  // Duraklatılmış kartta / detayda tek tıkla "Sürdür" (2026-09-26, tüm izleme sayfalarında varsayılan): toplu işlem
  // çubuğuyla aynı yazma yolu ({ active: true }); açık detay penceresinin kopyası da etkin olarak işaretlenir.
  const { resume, isResuming } = useMonitorResume(api.monitoring.updateDomainMonitor, (r) => {
    load(); setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

  const checkable = monitors.filter(canCheckRow)
  const checkRun = useCheckRun({
    items: checkable,
    // Tekil yolun ta kendisi: kartın "kontrol ediliyor" göstergesi (track) ve sonucun
    // satıra işlenmesi toplu koşumda da AYNI koddan geçer — ikinci bir merge yolu yok.
    runOne: checkNow,
    concurrency: CHECK_CONCURRENCY_BY_TYPE.domain,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)   // görünürlük-farkındalıklı: gizli sekmede polling durur

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'domain').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])


  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.domain) })
  }, [])

  useMonitorDeepLink(monitors, openDetail, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'DOMAIN',   // open=noc: 7/24 Kapsamı "7/24 ayarını düzenle"
  })

  function openDetail(m) { setSelected(m); setDetailTab(deepLinkTab()) }
  function closeDetail() { setSelected(null) }

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (defaultTeamId != null ? String(defaultTeamId) : ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      warningDays: defaults?.warningDays ?? emptyForm.warningDays,
      criticalDays: defaults?.criticalDays ?? emptyForm.criticalDays,
      thresholdsCsv: defaults?.thresholds ?? emptyForm.thresholdsCsv })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', domain: m.domain || '', groupName: m.group_name || '', tags: m.tags || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
      teamId: m.team_id != null ? String(m.team_id) : '',
      thresholdsCsv: m.thresholds_csv || '60,30,14,7,3,1',
      warningDays: m.warning_days ?? 30, criticalDays: m.critical_days ?? 7,
      intervalSeconds: m.interval_seconds ?? 86400, active: m.active !== false,
      notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING',
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      checkTimeoutMs: m.check_timeout_ms ?? '',
      transferLockAlert: m.transfer_lock_alert !== false,
      blacklistEnabled: m.blacklist_enabled === true,
      changeAlert: m.change_alert !== false, notifyWebhook: m.notify_webhook !== false }
  }
  function openEdit(m) {
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m))
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız alan adını değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.domain) })
    setModal('new')
  }
  function closeEdit() { setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  async function runTest() {
    if (!form.domain.trim()) return
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testDomain({
        domain: normalizeDomainInput(form.domain), warningDays: Number(form.warningDays), criticalDays: Number(form.criticalDays),
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('dom.testError'), status: 'UNKNOWN' })
    } finally {
      setTesting(false)
    }
  }

  async function save() {
    // Doğrulama hataları ALANIN ALTINDA + ilk hatalıya kaydırma (2026-09-30) — tost yok, kullanıcı hatayı aramaz.
    if (fe.check({
      domain: !form.domain.trim() && t('mon.fieldRequired'),
      teamId: (form.teamId === '' || form.teamId == null) && t('mon.teamRequired'),
      groupName: !form.groupName?.trim() && t('mon.groupRequired'),   // grup + etiket zorunlu (2026-09-18)
      tags: !form.tags?.trim() && t('mon.tagsRequired'),
    })) return
    setSaving(true)
    try {
      const payload = {
        // Serbest metin isimler korunur (backend URL'li isimleri host'a indirger); boşsa normalize domain.
        name: form.name.trim() || normalizeDomainInput(form.domain), domain: normalizeDomainInput(form.domain),
        groupName: form.groupName?.trim() || null, tags: form.tags?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
        thresholdsCsv: form.thresholdsCsv?.trim() || '60,30,14,7,3,1',
        warningDays: Number(form.warningDays), criticalDays: Number(form.criticalDays),
        intervalSeconds: Number(form.intervalSeconds), active: form.active,
        notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING',
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        checkTimeoutMs: form.checkTimeoutMs === '' || form.checkTimeoutMs == null ? null : Number(form.checkTimeoutMs),
        transferLockAlert: !!form.transferLockAlert,
        blacklistEnabled: !!form.blacklistEnabled,
        changeAlert: !!form.changeAlert,
        notifyWebhook: !!form.notifyWebhook,
      }
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createDomainMonitor(payload)
        : await api.monitoring.updateDomainMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      // Sunucu alan adini KAYITLI alan adina (eTLD+1) indirger: kayit bilgisi bir HOST'a degil
      // alan adinin kendisine aittir (RDAP/WHOIS'te www.x.com diye bir kayit yoktur). Alan
      // altindaki ipucu bunu yaziyor ama surpriz KAYDETTIKTEN sonra yasaniyor: kullanici
      // "www yazdim, silindi" diye okuyor. Indirgeme olduysa SUNUCUNUN dondurdugu degerle
      // soylenir — kural ikinci kez (bu kez JS'te) yazilmaz, kopyalar kaciniilmaz olarak ayrisir.
      const savedDomain = res?.data?.domain
      const typed = normalizeDomainInput(form.domain)
      if (savedDomain && typed && savedDomain !== typed) {
        toast.success(t('dom.savedReduced').replace('{0}', typed).replace('{1}', savedDomain))
      } else {
        toast.success(t('dom.saved'))
      }
      closeEdit()
      // İlk / taze kontrol (2026-09-28): yeni kart ("Bitiş tarihi bilinmiyor") zamanlayıcının saatlik turunu beklemesin; alan
      // adı ya da eşikleri değişen kart eski sonucu göstermesin. Liste YÜKLENDİKTEN sonra başlar (bkz. utils/checkAfterSave).
      if (shouldCheckAfterSave('domain', { isNew: modal === 'new', before: modal, after: res.data })) startCheckAfterSave(checkNow, res.data)
    } finally {
      setSaving(false)
    }
  }

  /**

   * Silme — KARTTAN (satır). Hedef her zaman AÇIK bir argümandır: {@code onClick={deleteMonitor}}

   * biçiminde bağlanırsa React olay nesnesini ilk argüman yapar ve hedef sessizce yanlış olur.

   *

   * <p>Onay ŞART ve projenin diyaloğuyla alınır: kart üzerindeki tek tık yıkıcı bir işlemi

   * tetikliyor, sunucu HARD delete yapıyor ve açık alarmları kapatıyor. Mesaj hedefin ADINI

   * taşır — "bu monitör" demek hangi kartta olduğumuzu doğrulamıyordu.

   *

   * <p>Hata TOAST ile bildirilir: {@code saveError} yalnız düzenleme modalının içinde

   * çiziliyor, karttan silerken modal KAPALI olduğu için 403/409 sessizce yutulur ve

   * kullanıcı silindi sanırdı.

   */

  async function deleteMonitor(m) {

    if (!m || m === 'new') return

    const ok = await showConfirm({

      title: t('mon.deleteTitle'),

      message: t('mon.deleteMsg', m.name || m.domain),

      confirmText: t('dom.delete'),

      cancelText: t('dom.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deleteDomainMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      // Kart HEMEN düşer (işaret), açık detay kapanır; liste arka planda tazelenir — arayüz beklemez.
      markMonitorDeleted('domain', m.id, res)
      setSelected((s) => (s?.id === m.id ? null : s))
      toast.success(t('dom.deleted'))

      load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    // Kalıcı silme (2026-10-07): düzenleme penceresinden de ADIYLA ve geri alınamaz olduğu söylenerek onay alınır.
    if (!await showConfirm({ title: t('mon.deleteTitle'), message: t('mon.deleteMsg', modal.name || modal.domain),
      confirmText: t('dom.delete'), cancelText: t('dom.cancel'), variant: 'danger' })) return
    const res = await api.monitoring.deleteDomainMonitor(modal.id)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    const id = modal.id
    markMonitorDeleted('domain', id, res)   // anında düşer; tazeleme arka planda
    setSelected((s) => (s?.id === id ? null : s))
    toast.success(t('dom.deleted')); closeEdit()
    load()
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerDomainCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        // Geçmiş ARTIK tazeleniyor (setHistReload): sekmenin kendi 30 sn'lik canlı yenilemesi
        // 1. sayfa dışında ve özel aralıkta KAPALI, dolayısıyla modaldan koşturulan kontrolün
        // sonucu hiç görünmeyebiliyordu. Sinyal remount ETMEZ — seçilen aralık/sayfa/filtre kalır.
        // (Eski hatalı loadHistory(m.id, rangeDays) çağrısı geri GELMEDİ; not aşağıda duruyor.)
        // Buradaki eski loadHistory(m.id, rangeDays) çağrısı geçmiş yönetimi o bileşene taşınırken
        // temizlenmemişti; ikisi de TANIMSIZ olduğu için modal açıkken kontrol butonu ReferenceError
        // atıyor, altındaki setChecking(null) hiç çalışmıyor ve buton kalıcı kilitleniyordu.
        // İşlevsel güncelleme (bayat kapanış YOK): yanıt gelene kadar pencere kapanmış ya da başka izlemeye
        // geçilmiş olabilir — A'nın sonucu B'nin penceresini değiştirmesin / kapalı pencereyi yeniden açmasın.
        setSelected(prev => (prev?.id === m.id ? res.data : prev))
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      }
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  async function diagnose(m) {
    // Yarış: A'nın tanılaması sürerken pencere kapatılıp B tanılanırsa A'nın geç yanıtı B'nin penceresine yazılmasın;
    // kapatılmış pencere de geç yanıtla yeniden açılmasın (işlevsel güncelleme — prev null ise dokunma).
    const my = ++diagSeq.current
    setDiag({ domain: m.domain, loading: true })
    try {
      const res = await api.admin.runDomainExpiryDiagnostics(m.domain)
      if (my !== diagSeq.current) return
      const next = res?.success ? { domain: m.domain, data: res.data } : { domain: m.domain, error: res?.error || t('dexp.error') }
      setDiag(prev => (prev ? next : prev))
    } catch (e) {
      if (my !== diagSeq.current) return
      setDiag(prev => (prev ? { domain: m.domain, error: e?.message || t('dexp.error') } : prev))
    }
  }

  /** Plan kaydedildi/kaldırıldı: sunucunun döndürdüğü satırı listeye ve açık detaya işle (yeniden yükleme yok). */
  function applyPlanRow(row) {
    if (!row?.id) return
    setMonitors(prev => prev.map(x => x.id === row.id ? { ...x, ...row } : x))
    setSelected(sel => (sel && sel.id === row.id) ? { ...sel, ...row } : sel)
    setPlanRow(null)
  }
  /** Domain Kaydı sekmesinin ANLIK sorgusu taze satır döndürdü: listeye ve açık detaya işle — başlık "son kontrol" ve kalan
   *  gün sekmeyle aynı anı göstersin. Yanıt geldiğinde başka izlemeye geçilmişse açık pencereye dokunulmaz (kimlik kapısı). */
  function applyLiveRecord(row) {
    if (!row?.id) return
    setMonitors(prev => prev.map(x => x.id === row.id ? { ...x, ...row } : x))
    setSelected(sel => (sel && sel.id === row.id) ? { ...sel, ...row } : sel)
  }
  async function doExport(kind) {
    if (displayMonitors.length === 0) { toast.error(t('inv.exportNoData')); return }
    setExporting(true)
    try {
      // CSV 7/24 grup ADLARINI yazar (liste yanıtında yalnız kimlik var) — seçenekler önbellekli (formla ortak)
      const n = kind === 'csv' ? exportDomainsCsv(displayMonitors, t, await loadNocGroupNames()) : await exportDomainsPdf(displayMonitors, t)
      toast.success(t('inv.exportSuccess', n))
    } catch { toast.error(t('inv.exportError')) } finally { setExporting(false) }
  }

  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('dom.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])
  // Filtre seçenekleri (grup/etiket) rol fark etmeksizin GÖRÜNEN listenin tamamından türer (2026-09-18,
  // kullanıcı isteği: filtreleme her yetkide). Sunucu zaten kapsamı uyguluyor; burada bir daha daraltmak
  // müdür/izleyici gibi çok takım gören rollerin başka takımın grubunu seçememesine yol açıyordu.
  const groupMonitors = monitors
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  // Etiket filtresi: grupla aynı sözleşme ('all' / '__none__' / etiket). Seçenekler listedeki etiketlerden türer.
  const tagNames = useMemo(() => tagNamesOf(groupMonitors), [groupMonitors])
  // Kutu etiketsiz izleme varken de görünür: "Etiketsiz" seçeneği eski (etiketsiz) kayıtları bulmanın yolu.
  const hasTagOptions = tagNames.length > 0 || groupMonitors.some(m => !(m.tags || '').trim())
  const tagFilterOptions = useMemo(() => [{ value: 'all', label: t('mon.allTags') },
    ...tagNames.map(x => ({ value: x, label: x })),
    ...(groupMonitors.some(m => !(m.tags || '').trim()) ? [{ value: '__none__', label: t('mon.noTags') }] : [])],
    [tagNames, groupMonitors, t])
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('dom.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('dom.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])
  const sortOptions = useMemo(() => SORTS.map(s => ({ value: s, label: t('dom.sort_' + s) })), [t])
  const quickOptions = useMemo(() => QUICK.map(q => ({ value: q, label: q === 'soon' ? t('dom.quick_soon', SOON_DAYS) : t('dom.quick_' + q) })), [t])

  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (!(QUICK_PRED[quick] || QUICK_PRED.all)(m)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return (m.domain || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q) || (m.registrar || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, tagFilter, quick, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, ok: 0, warning: 0, critical: 0, unknown: 0, changed: 0, soon: 0, nolock: 0, nsfail: 0, unsigned: 0, blacklisted: 0, expired: 0 }
    for (const m of scoped) {
      const s = m.status
      if (s === 'OK') c.ok++
      else if (s === 'WARNING') c.warning++
      else if (s === 'CRITICAL') c.critical++
      else c.unknown++
      if (m.changed) c.changed++
      if (m.days_remaining != null && m.days_remaining <= SOON_DAYS) c.soon++
      if (m.transfer_lock === 'NONE') c.nolock++
      for (const k of Object.keys(STAT_PRED)) if (STAT_PRED[k](m)) c[k]++
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    let list = scoped
    if (statFilter && statFilter !== 'total') {
      const pred = {
        ok: m => m.status === 'OK', warning: m => m.status === 'WARNING',
        critical: m => m.status === 'CRITICAL', unknown: m => m.status !== 'OK' && m.status !== 'WARNING' && m.status !== 'CRITICAL',
        changed: m => m.changed,
        soon: QUICK_PRED.soon, nolock: QUICK_PRED.nolock,
        ...STAT_PRED,
      }[statFilter]
      if (pred) list = list.filter(pred)
    }
    const dv = (m) => (m.days_remaining == null ? (sortBy === 'days_asc' ? 1e9 : -1e9) : m.days_remaining)
    const sorted = [...list]
    const byDomain = (a, b) => (a.domain || '').localeCompare(b.domain || '')
    if (sortBy === 'default') return sortMonitorsDefault(list, 'domain')   // dokuz türün ortak kuralı (sorunlu → grup → ad)
    if (sortBy === 'days_asc') sorted.sort((a, b) => dv(a) - dv(b) || byDomain(a, b))   // varsayılan: en az gün önce
    else if (sortBy === 'days_desc') sorted.sort((a, b) => dv(b) - dv(a))
    else if (sortBy === 'registrar') sorted.sort((a, b) => (a.registrar || '\uffff').localeCompare(b.registrar || '\uffff') || byDomain(a, b))
    else if (sortBy === 'team') sorted.sort((a, b) => (a.team_name || '\uffff').localeCompare(b.team_name || '\uffff') || byDomain(a, b))
    else if (sortBy === 'changed') sorted.sort((a, b) => String(b.last_changed || '').localeCompare(String(a.last_changed || '')) || byDomain(a, b))   // en yeni kayıt değişikliği önce
    else sorted.sort(byDomain)
    return sorted
  }, [scoped, statFilter, sortBy])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  const pager = usePagination(displayMonitors, {
    listKey: 'domain-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, quick, statFilter, sortBy],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, search, statFilter, pager }),
    sort: sortBy !== 'days_asc' ? sortBy : null,
    dq: quick !== 'all' ? quick : null,   // hızlı süzgeç (2026-09-22)
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',    Icon: LayoutDashboard,  label: t('dom.dashTotal'),    value: counts.total,    cls: 'total'    },
    { key: 'ok',       Icon: CheckCircle2,     label: t('dom.dashOk'),       value: counts.ok,       cls: 'valid'    },
    { key: 'warning',  Icon: TriangleAlert,    label: t('dom.dashWarning'),  value: counts.warning,  cls: 'warning'  },
    { key: 'critical', Icon: ShieldAlert,      label: t('dom.dashCritical'), value: counts.critical, cls: 'critical' },
    { key: 'unknown',  Icon: HelpCircle,       label: t('dom.dashUnknown'),  value: counts.unknown,  cls: 'high'     },
    { key: 'changed',  Icon: Activity, label: t('dom.dashChanged'),  value: counts.changed,  cls: 'error'    },
    { key: 'soon',     Icon: CalendarClock,    label: t('dom.dashSoon', SOON_DAYS), value: counts.soon, cls: 'warning' },
    { key: 'nolock',   Icon: LockOpen,         label: t('dom.dashNoLock'),   value: counts.nolock,   cls: 'high'     },
    { key: 'nsfail',      Icon: ServerCrash, label: t('dom.dashNsFail'),      value: counts.nsfail,      cls: 'high',     hint: t('dom.dashNsFailHint') },
    { key: 'unsigned',    Icon: ShieldOff,   label: t('dom.dashUnsigned'),    value: counts.unsigned,    cls: 'warning',  hint: t('dom.dashUnsignedHint') },
    { key: 'blacklisted', Icon: Ban,         label: t('dom.dashBlacklisted'), value: counts.blacklisted, cls: 'critical', hint: t('dom.dashBlacklistedHint') },
    { key: 'expired',     Icon: CalendarX,   label: t('dom.dashExpired'),     value: counts.expired,     cls: 'critical', hint: t('dom.dashExpiredHint') },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  // Durum sözlüğü (kart şeridi / rozet / detay kenarı) — paylaşılan: up | warn | down | unknown.
  function statusCls(s) { return statusKey(s) }
  function statusLabel(s) {
    return s === 'OK' ? t('dom.stOk') : s === 'WARNING' ? t('dom.stWarning') : s === 'CRITICAL' ? t('dom.stCritical') : t('dom.stUnknown')
  }
  function statusBadge(m) {
    return <MonitorStatusBadge status={statusCls(m?.status)}>{statusLabel(m?.status)}</MonitorStatusBadge>
  }
  const alarmLabel = (m) => `${t('dom.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  /**
   * Alarm seviyesi durumla AYNIYSA (Kritik durum + Kritik alarm, Uyarı + Uyarı) ayrı alarm rozeti yeni bilgi
   * taşımaz — kartta "Kritik" iki kez yazıyordu (2026-09-26). O durumda alarm işareti durum rozetinin İÇİNE
   * girer: ⚠ ikonu (onaylanmamışsa nabız) + ekran okuyucuya tam metin ("Aktif alarm — CRITICAL"); seviye
   * rozet metninde görünür kalır. Seviye durumdan farklıysa (ör. Kritik durum + Yüksek alarm) ayrı rozet kalır.
   */
  function cardStatusBadge(m) {
    if (!alarmMatchesStatus(m)) return statusBadge(m)
    return (
      <MonitorStatusBadge status={statusCls(m.status)}>
        <AlertTriangle aria-hidden="true" data-slot="monitor-alarm-inline"
          className={cn('size-3', !m.alarm_acknowledged && 'animate-pulse motion-reduce:animate-none')} />
        {statusLabel(m.status)}
        <span className="sr-only">{alarmLabel(m)}</span>
      </MonitorStatusBadge>
    )
  }

  // ── İç içe pencereler ── ModalShell iç içe derinliği React AĞACINDAN okur: bir pencere, onu açan
  // pencerenin İÇİNDE çizilirse onun üstünde katmanlanır (eskiden "en son portal üstte kalır" sırasına
  // güveniliyordu). Bu yüzden Tanıla / Plan / Form pencereleri en derindeki açık pencereye yerleşir.

  // Sorun Tanıla (Alan Adı Süre Bitişi Tanılama): formdan (admin) ya da detayın geçmiş sekmesinden açılır.
  const diagModal = diag && (
    <ModalShell open onClose={() => setDiag(null)} icon={ShieldAlert} size="md" scrollBody
      title={`${t('dexp.diagnose')} — ${diag.domain}`}
      footer={<Button variant="secondary" onClick={() => setDiag(null)}>{t('dom.cancel')}</Button>}>
      {diag.loading && <LoadingBlock label={t('dexp.running')} className="upt-modal-loading" />}
      {diag.error && <AlertBanner tone="danger">{diag.error}</AlertBanner>}
      {diag.data && <DomainExpiryTrace data={diag.data} />}
    </ModalShell>
  )

  // Yenileme planı (2026-09-22, H): sertifika envanteriyle ORTAK modal, izleme uçlarıyla.
  const planModal = planRow && (
    <RenewalPlanModal
      row={{ domain: planRow.domain, renewal_planned_at: planRow.renewal_planned_at, renewal_planned_note: planRow.renewal_planned_note,
        renewal_planned_by: planRow.renewal_planned_by, expiry_key: expiryKey(planRow), renew_by_key: null }}
      plan={(date, note) => api.monitoring.domainRenewalPlan(planRow.id, date, note)}
      unplan={() => api.monitoring.domainRenewalUnplan(planRow.id)}
      hint={t('dom.planHint', fmtExpiry(planRow.expiry_date))}
      onClose={() => setPlanRow(null)} onSaved={applyPlanRow} onCleared={applyPlanRow} />
  )

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={CalendarClock} width={640}
      title={modal === 'new' ? t('dom.modalNew') : t('dom.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('dom.testing') : null}
      footer={<>
        <div className="mr-auto flex flex-wrap gap-2">
          <Button variant="secondary" onClick={runTest} aria-busy={testing || undefined} disabled={testing || !form.domain.trim()}>
            <FlaskConical size={14} />{t('dom.test')}
          </Button>
          {isAdmin && (
            <Button variant="secondary" onClick={() => diagnose({ domain: normalizeDomainInput(form.domain) })} disabled={!form.domain.trim()}>
              <ShieldAlert size={14} />{t('dexp.diagnose')}
            </Button>
          )}
        </div>
        {modal !== 'new' && canDeleteRow(modal) && <Button variant="destructive" onClick={del}><Trash2 size={14} />{t('dom.delete')}</Button>}
        <Button variant="secondary" onClick={closeEdit}>{t('dom.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.domain.trim() || !form.teamId}>{t('dom.save')}</Button>
      </>}>
      {dupSource
        ? <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>
        : <AlertBanner tone="info" icon={CalendarClock}>{t('dom.typeInfo')}</AlertBanner>}

      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField full label={t('dom.domain')} required {...fe.fieldProps('domain')} hint={t('dom.domainHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.domain} placeholder="example.com" autoFocus
              onChange={e => { setForm(f => ({ ...f, domain: e.target.value })); fe.clear('domain') }}
              onBlur={e => { const n = normalizeDomainInput(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, domain: n })) }} />
          )}
        </FormField>

        <FormField label={t('dom.name')}>
          {({ id }) => (
            <Input id={id} value={form.name} placeholder={form.domain} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('dom.team')} required {...fe.fieldProps('teamId')}>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => { setForm(f => ({ ...f, teamId: v })); fe.clear('teamId') }} options={teamSelectOptions} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('dom.noTeam')} disabled />}
        </FormField>
        <FormField full label={t('dom.group')} required {...fe.fieldProps('groupName')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('groupName') }}
              options={[{ value: '', label: t('dom.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('dom.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="DOMAIN" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <FormHint>{t('dom.groupInfo')}</FormHint>
        <FormField label={t('verify.attempts')}>
          {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('verify.attemptEvery')}>
          {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('verify.recoveryChecks')}>
          {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('verify.recoveryEvery')}>
          {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
        </FormField>
        <FormHint>ⓘ {t('verify.hint')}</FormHint>
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
        {/* Etiketler — zorunlu (2026-09-18); Http/Port ile aynı blok */}
        <FormSection title={t('mon.tagsTitle')} required {...fe.fieldProps('tags')} hint={t('mon.tagsHint')}>
          <TagInput value={form.tags} onChange={v => { setForm(f => ({ ...f, tags: v })); fe.clear('tags') }} placeholder={t('mon.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>

        <FormField label={t('dom.warningDays')}>
          {({ id }) => <Input id={id} type="number" min="1" value={form.warningDays} onChange={e => setForm(f => ({ ...f, warningDays: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('dom.criticalDays')}>
          {({ id }) => <Input id={id} type="number" min="1" value={form.criticalDays} onChange={e => setForm(f => ({ ...f, criticalDays: Number(e.target.value) }))} />}
        </FormField>
        <FormField full label={t('dom.thresholds')} hint={t('dom.thresholdsHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.thresholdsCsv} placeholder="60,30,14,7,3,1"
              onChange={e => setForm(f => ({ ...f, thresholdsCsv: e.target.value }))} />
          )}
        </FormField>
        <FormField full label={t('dom.checkTimeout')} hint={t('dom.checkTimeoutHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="1000" max="30000" step="500" value={form.checkTimeoutMs}
              placeholder={t('dom.checkTimeoutPh')}
              onChange={e => setForm(f => ({ ...f, checkTimeoutMs: e.target.value }))} />
          )}
        </FormField>
        <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('dom.active')} />
        <FormHint>{t('dom.unknownHint')}</FormHint>

        {/* Koruma anahtarlari. Sure bitisi BILEREK toggle DEGIL: bizde esik alanlariyla
            (uyari/kritik gun + esik listesi) zaten var ve acik/kapali bir anahtar onu
            fakirlestirirdi. */}
        <FormSection title={t('dom.alarmSettings')} boxed={false}>
          <CheckField checked={form.transferLockAlert} onCheckedChange={v => setForm(f => ({ ...f, transferLockAlert: v }))}
            label={t('dom.transferLockAlert')} hint={t('dom.transferLockHint')} />
          <CheckField checked={form.blacklistEnabled} onCheckedChange={v => setForm(f => ({ ...f, blacklistEnabled: v }))}
            label={t('dom.blacklistEnabled')} hint={t('dom.blacklistHint')} />
          <CheckField checked={form.changeAlert} onCheckedChange={v => setForm(f => ({ ...f, changeAlert: v }))}
            label={t('dom.changeAlert')} hint={t('dom.changeAlertHint')} />
        </FormSection>
      </FormGrid>

      {testResult && (() => {
        // Veri yok (hata / UNKNOWN) nötr bilgi tonunda: "sorun" değil, "doğrulanamadı".
        const noData = testResult.error || testResult.status === 'UNKNOWN'
        const details = [
          testResult.days_remaining != null && `${testResult.days_remaining} ${t('dom.daysLeft')}`,
          testResult.expiry_date && fmtExpiry(testResult.expiry_date),
          testResult.registrar,
          testResult.source && testResult.source !== 'NONE' && testResult.source,
          noData && (testResult.error || t('dom.noData')),
        ].filter(Boolean).join(' · ')
        return (
          <AlertBanner className="mt-3"
            tone={noData ? 'info' : testResult.status === 'OK' ? 'success' : 'warning'}
            icon={testResult.status === 'OK' ? undefined : AlertTriangle}
            title={statusLabel(testResult.status)}>
            {details || null}
          </AlertBanner>
        )
      })()}
      {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="domain-change-note" value={changeNote} onChange={setChangeNote} />
      )}
      {diagModal}
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader type="domain" title={t('dom.title')} subtitle={t('dom.subtitle')}
        count={loading ? null : monitors.length}
        refreshEvery={REFRESH_INTERVAL} refreshResetKey={loadNonce} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('dom.addMonitor')}
        extraActions={(
          /* modal={false}: açık menü varken başka bir tetiğe tek basışta geçilir (ui/KebabMenu ile aynı).
             Telefonda yalnız ikon (ad aria-label'da) — başlık satırı 360 px'te de taşmasın. */
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="outline" disabled={exporting} aria-label={t('inv.export')} title={t('inv.export')}
                className="h-10 shrink-0 sm:h-9">
                <Download aria-hidden="true" /><span className="hidden sm:inline">{t('inv.export')}</span>
                <ChevronDown aria-hidden="true" className="hidden size-3 opacity-70 sm:inline" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" collisionPadding={8} className="z-(--z-menu) w-64 max-w-[calc(100vw-2rem)]">
              <DropdownMenuItem onSelect={() => doExport('csv')} className="flex-col items-start gap-0.5">
                <span className="font-medium">{t('inv.exportCsv')}</span>
                <span className="text-xs text-muted-foreground">{t('dom.exportCsvHint', displayMonitors.length)}</span>
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => doExport('pdf')} className="flex-col items-start gap-0.5">
                <span className="font-medium">{t('inv.exportPdf')}</span>
                <span className="text-xs text-muted-foreground">{t('dom.exportPdfHint', displayMonitors.length)}</span>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )} />

      <MonitorHowBox bullets={[t('dom.how1'), t('dom.how2'), t('dom.how3'), t('dom.how4'), t('dom.how5'), t('dom.how6'), t('dom.how7'), t('dom.how8')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          <SearchableSelect value={sortBy} onChange={setSortBy} options={sortOptions} ariaLabel={t('flt.sort')} />
          <SearchableSelect value={quick} onChange={setQuick} options={quickOptions} ariaLabel={t('dom.quickLabel')} />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('dom.searchPlaceholder')} aria-label={t('dom.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} data-page-search="" />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('dom.noMonitorsAdmin') : t('dom.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="DOMAIN"
          api={{ update: api.monitoring.updateDomainMonitor, remove: api.monitoring.deleteDomainMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Alan Adı kartı (domain/DomainMonitorCard, 2026-09-27): kalan gün kahraman paneli (bitiş tarihi, kalan kayıt
               süresi çubuğu, yenileme planı çipi / kısayolu), koruma çipleri, EPP kodları. Yetkiye, seçime ve eylemlere
               bağlı parçalar BURADA kurulur ve yuva olarak geçer — toplu seçim kutusu, meta, kart eylemleri (telefonda
               "Diğer işlemler" menüsü + Yenileme planla), plan penceresi. Durum sözlüğü detay penceresiyle ortak. */
            <DomainMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} density={density} running={isRunning(m.id)} status={statusCls(m.status)} badge={cardStatusBadge(m)}
              alarmLabel={alarmLabel(m)} onOpen={() => openDetail(m)}
              onPlanRenewal={canManageRow(m) ? () => setPlanRow(m) : undefined}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.domain)} />
              )}
              meta={<MonitorCardMeta monitor={m} />}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={m.domain}
                  running={isRunning(m.id)}
                  onCheck={canCheckRow(m) ? () => checkNow(m) : undefined} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('dom.check')} editTitle={t('dom.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('dom.delete')}
                  phoneMenu menuItems={[{
                    label: m.renewal_planned_at ? t('forecast.editPlan') : t('forecast.planRenewal'),
                    icon: <CalendarPlus aria-hidden="true" />, onClick: () => setPlanRow(m),
                  }]} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={statusCls(selected.status)} badge={statusBadge(selected)} title={selected.domain} noc={{ type: 'DOMAIN', monitor: selected, canEdit: canManageRow(selected) }}
          className={DETAIL_PHONE_FULLSCREEN}   // telefonda (< 640) tam ekran; paylaşılan kabuk değişmedi (2026-09-28)
          actions={
            /* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
               koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
               kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
              running={isRunning(selected.id)}
              onCheck={canCheckRow(selected) ? () => checkNow(selected) : undefined}
              checkTitle={t('dom.check')}
              onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
              editTitle={t('dom.edit')}
              onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
              onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
              deleting={deleting === selected.id}
              deleteTitle={t('dom.delete')}
              onClose={closeDetail}>
              {/* Yenileme planı artık başlık alanının plan bloğunda (plan bilgisi + Planı düzenle / Yenileme planla) —
                  başlık çubuğunda ikinci bir plan düğmesi yok (2026-09-28). */}
              <CopyLinkButton iconOnly variant="outline" className="pointer-coarse:size-10" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-0 max-sm:hidden" />
          {/* Başlık alanı (domain/detail/DomainDetailHeader, 2026-09-28): kalan gün kahramanı + kayıt süresi çubuğu + yenileme
              planı bloğu, özet (registrar/kaynak/son kontrol/sıklık/eşikler + Şimdi kontrol et), bitiş bilinmiyorsa neden +
              sonraki adımlar (Sorun Tanıla), koruma + EPP çipleri. Eski düz DetailSummary satırının yerine; yetki kapıları kartla aynı. */}
          <DomainDetailHeader monitor={selected} running={isRunning(selected.id)}
            onCheck={canCheckRow(selected) ? () => checkNow(selected) : undefined}
            onPlanRenewal={canManageRow(selected) ? () => setPlanRow(selected) : undefined}
            onDiagnose={canDiagnoseRow(selected) ? () => diagnose(selected) : undefined} />
          <DetailDivider />
          <DetailTabs value={detailTab} onValueChange={setDetailTab} className="mt-0"
            countsFor={{ kind: 'domain', monitorId: selected.id, notesType: 'DOMAIN', notesTarget: selected.domain, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['control', t('hist.tab')], ['registration', t('dom.tabRegistration')], ['alerts', t('dom.tabAlerts')],
              ['notes', t('dom.tabGuide')],
              // Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
              // orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            <TabsContent value="control">
              {canDiagnoseRow(selected) && (
                <div className="mb-2 flex justify-end">
                  <Button type="button" variant="secondary" size="sm" data-slot="dexp-open" className="pointer-coarse:h-10" onClick={() => diagnose(selected)}>
                    <ShieldAlert size={13} />{t('dexp.diagnose')}
                  </Button>
                </div>
              )}
              <CheckHistoryTab kind="domain" monitorId={selected.id} listKey="domain-history" reloadSignal={histReload}
                presets={[7, 30, 90, 365]} defaultPreset={30} gridClass="dom-rt-grid"
                timeline={false}
                renderAbove={({ preset }) => <DomainExpiryTrend monitorId={selected.id} reloadSignal={histReload}
                  days={Number.isFinite(Number(preset)) ? Number(preset) : 90} />}
                columns={[t('dom.colTime'), t('dom.colSource'), t('dom.colExpiry'),
                  t('dom.daysLeft'), t('dom.colStatus'), t('dom.registrar')]}
                renderRow={(c) => {
                  const cDays = c.days_remaining
                  const cIps = (Array.isArray(c.resolved_ips) ? c.resolved_ips : String(c.resolved_ips ?? '').split(',')).map(s => String(s).trim()).filter(Boolean)
                  // Hata teşhisi (2026-10-05): veri getirilemeyen (UNKNOWN) satırın hatası artık registrar hücresinde kırpılıp
                  // SAKLANMAZ — satırın altında KENDİ satırı: neden rozeti + tek satır + aç/kapa, açılınca tam panel.
                  const failed = !isHealthy('domain', c)
                  const k = failureRowKey(c)
                  const open = failed && failRows.isOpen(k)
                  const when = formatDateSec(c.checked_at)
                  return (<>
                    <span className="upt-rt-time">{when}</span>
                    <span>{sourceTag(c.source, c.whois_provider) || '—'}</span>
                    <span>{fmtExpiry(c.expiry_date)}</span>
                    <span className={cn('font-semibold', daysTone(cDays))}>{cDays ?? '—'}</span>
                    <span className={cn('font-semibold', STATUS_TEXT[statusCls(c.status)])}>{statusLabel(c.status)}{c.changed ? ' ⚑' : ''}</span>
                    {/* Çözülen IP ayrı sütun değil: 7. sütun tabloyu kırıyordu; IP registrar hücresinin tooltip'inde (Domain Kaydı sekmesinde tam liste) */}
                    <span className="truncate" title={[c.registrar, cIps.length ? 'IP: ' + cIps.join(', ') : null].filter(Boolean).join(' · ')}>
                      {c.registrar || '—'}{cIps.length ? <span className="font-normal text-muted-foreground"> · {cIps.length} IP</span> : null}
                    </span>
                    {failed && (
                      <CheckFailureBlock type="domain" check={c} monitor={selected} open={open} when={when}
                        panelId={failurePanelId('domain', k)} onToggle={() => failRows.toggle(k)}
                        canDiagnose={canDiagnoseRow(selected)} onDiagnose={() => diagnose(selected)} />
                    )}
                  </>)
                }} />
            </TabsContent>

            <TabsContent value="registration"><DomainRegistrationTab monitor={selected} onLiveRecord={applyLiveRecord} /></TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.domain} types={alertTypesFor('domain')} /></TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="DOMAIN" target={selected.domain} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="domain" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

          {!modal && diagModal}
          {planModal}
          {formModal}
        </MonitorDetailModal>
      )}
      {!selected && formModal}
      {!selected && planModal}
      {!selected && !modal && diagModal}

      {/* Sayfa düzeyi toplu kontrol: önce takım seçimi, sonra akan sonuç tablosu.
          Depolama anahtarı TÜR BAŞINA ayrı — tek anahtar paylaşılsaydı buradaki seçim
          panonun sertifika seçimini ezerdi. */}
      {checkRun.pickerOpen && (
        <CheckTeamPicker
          buckets={monitorTeamBuckets(checkable)}
          storageKey="sm.checkRun.teams.domain"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="domain"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
