import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense, Fragment } from 'react'
import { sortMonitorsDefault } from '../utils/monitorSort.js'
import { freshestRow, mergeSavedRow, reloadAndSyncDetail } from '../utils/monitorDetailSync.js'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import TagInput from './ui/TagInput.jsx'
import { Trash2, ScanSearch, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ChevronDown, Image, FileCode, Link2, Frame, Type, ShieldAlert, Download, EyeOff, Inbox, Copy, Stethoscope } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import { useDialog } from './ui/Dialog.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { CheckFailureBlock } from './checks/CheckFailurePanel.jsx'
import useFailureRows, { failurePanelId, failureRowKey } from './checks/useFailureRows.js'
import { isHealthy } from './checks/checkFailureModel.js'
import { LoadingBlock, Spinner } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesProxy } from '../utils/monitorFilters.js'
import { markMonitorDeleted, monitorKind, useWithoutDeleted } from '../utils/recentlyDeleted.js'
import MonitorProxyField from './ui/MonitorProxyField.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { csvRows } from '../utils/csv.js'
import { downloadCsv } from '../utils/csvExport.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { TabsContent } from '@/components/shadcn/tabs'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import PageMonitorCard from './page/PageMonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint, LabelSlot,
} from './monitoring/MonitorForm.jsx'
import { MON_ACT, MON_ACT_TONE } from './ui/CheckRunning.jsx'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))
// Uçtan uca tanılama (2026-10-05) — tembel: giriş paketi büyümez
const PageDiagnoseDialog = lazy(() => import('./page/diagnose/PageDiagnoseDialog.jsx'))

const INTERVALS = [
  { value: 60,    labelKey: 'page.iv1m'  },
  { value: 300,   labelKey: 'page.iv5m'  },
  { value: 600,   labelKey: 'page.iv10m' },
  { value: 900,   labelKey: 'page.iv15m' },
  { value: 1800,  labelKey: 'page.iv30m' },
  { value: 3600,  labelKey: 'page.iv1h'  },
  { value: 43200, labelKey: 'page.iv12h' },
  { value: 86400, labelKey: 'page.iv24h' },
]
const intervalIdx = (secs) => {
  const i = INTERVALS.findIndex(o => o.value === secs)
  if (i >= 0) return i
  let best = 0, bd = Infinity
  INTERVALS.forEach((o, j) => { const d = Math.abs(o.value - secs); if (d < bd) { bd = d; best = j } })
  return best
}
const REFRESH_INTERVAL = 60
// Sorun türü → ikon (kaynak tür ikonlarıyla birlikte tabloda gösterilir).
const RES_ICON = { IMG: Image, CSS: FileCode, JS: FileCode, LINK: Link2, IFRAME: Frame, FONT: Type, FAVICON: Image }
// Sorun tablosu kolon şablonu: Zaman | Tür | Kaynak | Sorun | HTTP | Süre | Alarm Kapsamı | İşlem.
const PAGE_ISSUE_COLS = '1fr 0.9fr 2fr 0.7fr 0.45fr 0.5fr 0.85fr 0.4fr'
// Hariç desenleri check-time'da 50 satırda kırpılır (PageCheckerService.EXCLUDE_MAX_LINES) — istemci de aynı sınırı uygular.
const EXCLUDE_MAX_LINES = 50
// Durum metin rengi (özet metriği, geçmiş satırı). CONFIG_ERROR: URL'de host yok (şemasız/bozuk) →
// kesinti DEĞİL, alarm üretmez; mor ile ayrışır. Eski hex paletinin Tailwind karşılıkları (+ koyu tema).
const STATUS_TEXT = {
  OK: 'text-success',
  DEGRADED: 'text-amber-600 dark:text-amber-400',
  DOWN: 'text-destructive',
  CONFIG_ERROR: 'text-violet-600 dark:text-violet-400',
  unknown: 'text-muted-foreground',
}
// Sorun türü metin rengi — BLOCKED/TIMEOUT nötr tonlarda (kesin kırık değil).
const ISSUE_TEXT = {
  MIXED_CONTENT: 'text-amber-700 dark:text-amber-400',
  SLOW: 'text-sky-700 dark:text-sky-400',
  BLOCKED: 'text-stone-500 dark:text-stone-400',
  TIMEOUT: 'text-yellow-700 dark:text-yellow-500',
  BROKEN: 'text-red-700 dark:text-red-400',
}
const emptyForm = { name: '', url: '', groupName: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  mode: 'SINGLE_PAGE', crawlDepth: 2, crawlMaxPages: 50, excludePatterns: '', slowResourceMs: 2000, useProxy: 'AUTO',
  alertThirdParty: false, alertMixedContent: true, alertTimeout: true, resourceConcurrency: 5,
  intervalSeconds: 300, timeoutMs: 4000, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true }

export default function PageMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showPrompt, showConfirm } = useDialog()
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
  // Uçtan uca tanılama (2026-10-05, keyword ile aynı kural): YALNIZ sunucunun satır bayrağı `can_diagnose` (= can_check +
  // diagnostics.run). Liste satırına da bakılır: "Şimdi kontrol et" açık detayın kopyasını tetik yanıtıyla DEĞİŞTİRİR ve o
  // yanıtta bayrak yok — pencere koşu ortasında sökülmesin. (`monitors` aşağıda tanımlı; çağrı anında okunur.)
  const canDiagnoseRow = (m) => !!m && (m.can_diagnose === true || monitors.some((x) => x.id === m.id && x.can_diagnose === true))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('page')   // kart mini trendi (2026-09-12)
  const sla = useSla('page')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  // Kart yoğunluğu (2026-09-27): her açılışta Zengin; Kompakt seçimi SAKLANMAZ (yalnız sayfada kalındıkça geçerli)
  const [density, setDensity] = useCardDensity('page')
  // Kontrol geçmişi hata teşhisi (2026-10-05): açık hata panelleri (satır anahtarıyla)
  const failRows = useFailureRows()
  // Uçtan uca tanılama penceresi (2026-10-05): { monitorId, runId, initialRunId } | null. Derin bağlantı ?monitor=<id>&pidx=<no>
  // YALNIZ kayıtlı çalıştırmayı açar (canlı koşu asla kendiliğinden başlamaz); `runId` gösterilen çalıştırmadır (URL'e yazılır).
  const [pageDx, setPageDx] = useState(() => {
    const runId = readUrlInt('pidx', null), monitorId = readUrlInt('monitor', null)
    return runId && monitorId ? { monitorId, runId, initialRunId: runId } : null
  })
  const [rawMonitors, setMonitors] = useState([])
  // Silme anında (2026-10-07): silinen kart tam liste yüklemesini BEKLEMEDEN düşer, bayat yanıt geri getiremez.
  const monitors = useWithoutDeleted(monitorKind('page'), rawMonitors)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)   // detay penceresi (MonitorDetailModal — Escape'i ModalShell işler)
  const [issues, setIssues] = useState([])
  const [issuesLoading, setIssuesLoading] = useState(false)
  const [confirmations, setConfirmations] = useState([])   // canlı teyit zincirleri (Teyit denemesi X/N)
  const [issueFilter, setIssueFilter] = useState('all')   // all | BROKEN | MIXED_CONTENT | SLOW | firstParty
  // await SONRASI için güncel değerler (bayat kapanış YOK): checkNow/refreshModal yanıtı gelene kadar pencere
  // kapanmış, başka izlemeye geçilmiş ya da sorun süzgeci değişmiş olabilir.
  const selectedIdRef = useRef(null)
  selectedIdRef.current = selected?.id ?? null
  const issueFilterRef = useRef(issueFilter)
  issueFilterRef.current = issueFilter
  const [modal, setModal] = useState(null)
  const fe = useFormErrors(modal)   // doğrulama hataları alanın altında + ilk hatalıya kaydırma (2026-09-30)
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [defaults, setDefaults] = useState(null)
  const [advOpen, setAdvOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [testing, setTesting] = useState(false)
  // Form "Test" sırası (smokeSeq deseni, 2026-10-09): form açılışında/kapanışında artar → önceki formun geç dönen test
  // sonucu yeni forma DÜŞMEZ; "test ediliyor" yalnız GÜNCEL sıranın isteği bitince söner.
  const testSeq = useRef(0)
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('issues')
  const deepLinkTab = useDeepLinkTab('issues')   // ?monitor=…&mtab=changes derin bağlantısı — ilk açılışta bir kez
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [proxyFilter, setProxyFilter] = useState(() => readUrlParam('via', 'all'))   // vekil süzgeci (2026-09-22): all | proxy | direct
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [loadNonce, setLoadNonce] = useState(0)   // her başarılı yüklemede artar: başlık çipi geri sayımı kendisi sayar, sayfa saniyede bir çizilmez (2026-10-01)

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
      const res = await api.monitoring.getPageMonitors()
      if (res?.success) { setMonitors(res.data); setLoadError(null); return res.data }
      else setLoadError(res?.error || 'load failed')
    } catch (e) {
      setLoadError(e?.message || 'network error')
    } finally {
      setLoading(false); setLoadNonce((n) => n + 1)
    }
  }, [])
  // Duraklatılmış kartta / detayda tek tıkla "Sürdür" (2026-09-26, tüm izleme sayfalarında varsayılan): toplu işlem
  // çubuğuyla aynı yazma yolu ({ active: true }); açık detay penceresinin kopyası da etkin olarak işaretlenir.
  const { resume, isResuming } = useMonitorResume(api.monitoring.updatePageMonitor, (r) => {
    load(); setMonitors((prev) => prev.map((x) => (x.id === r.id ? { ...x, active: true } : x)))
    setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

  const checkable = monitors.filter(canCheckRow)
  const checkRun = useCheckRun({
    items: checkable,
    // Tekil yolun ta kendisi: kartın "kontrol ediliyor" göstergesi (track) ve sonucun
    // satıra işlenmesi toplu koşumda da AYNI koddan geçer — ikinci bir merge yolu yok.
    runOne: checkNow,
    concurrency: CHECK_CONCURRENCY_BY_TYPE.page,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)

  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'page').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])


  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.page) })
  }, [])

  useMonitorDeepLink(monitors, openDetail, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'PAGE',   // open=noc: 7/24 Kapsamı "7/24 ayarını düzenle"
  })

  // O2: üç eşzamanlı çağıran var (filtre tıklaması, checkNow, 30sn sessiz refreshModal) ve
  // yavaş bir 'all' yanıtı kullanıcının sonradan seçtiği filtrenin sonucunu ezebiliyordu: çip
  // 'SLOW' gösterirken liste 'all' kalıyordu. PageSpeed sayfası aynı sınıf için resSeq guard'ı
  // taşıyor — desen buraya da taşındı (yalnız EN SON isteğin yanıtı ekrana yazılır).
  const issuesSeq = useRef(0)
  const confSeq = useRef(0)
  async function loadIssues(id, filter = issueFilter, silent = false) {
    const seq = ++issuesSeq.current
    if (!silent) setIssuesLoading(true)                              // silent: 30sn oto-yenilemede spinner flaşlamasın
    const issueType = (filter === 'all' || filter === 'firstParty') ? null : filter
    // try/finally YOKTU: istek reject olursa setIssuesLoading(false) hic calismiyor ve
    // "Sorunlar" sekmesindeki spinner modal kapatilip yeniden acilana kadar donuyordu.
    // Bayrak YALNIZ bu istek hala guncelse indirilir — bastirilan eski yanitta indirmek
    // YANLIS olurdu, cunku yeni istek hala ucusta ve onu o temizleyecek.
    try {
      const res = await api.monitoring.getPageIssues(id, { issueType })
      if (seq !== issuesSeq.current) return        // daha yeni bir istek var → bu yanıtı AT
      let rows = res?.success ? (res.data ?? []) : []
      if (filter === 'firstParty') rows = rows.filter(r => r.first_party)
      setIssues(rows)
    } catch {
      if (seq === issuesSeq.current) setIssues([])
    } finally {
      if (seq === issuesSeq.current) setIssuesLoading(false)
    }
  }
  function selectIssueFilter(id, f) { setIssueFilter(f); loadIssues(id, f) }
  async function loadConfirmations(url) {
    const seq = ++confSeq.current
    const res = await api.monitoring.getConfirmations(url)
    if (seq !== confSeq.current) return
    setConfirmations(res?.success ? (res.data ?? []) : [])
  }
  function openDetail(m) {
    setSelected(m); setIssues([]); setConfirmations([]); setIssueFilter('all'); setDetailTab(deepLinkTab())
    setPageDx((cur) => (cur && cur.monitorId === m?.id ? cur : null))   // derin bağlantının kayıtlı çalıştırması yalnız KENDİ izlemesinde
    loadIssues(m.id, 'all'); loadConfirmations(m.url)
  }
  function closeDetail() { setSelected(null); setIssues([]); setConfirmations([]); setPageDx(null) }
  /** Tanılama penceresini aç — başlangıç ekranıyla (koşu kullanıcı "Tanılamayı başlat"a basınca). */
  function openDiagnose(m) { if (m) setPageDx({ monitorId: m.id, runId: null, initialRunId: null }) }

  // Modal 30sn oto-yenileme (sessiz): sorunlar + canlı teyit durumu + kart metrikleri.
  // (Kontrol Geçmişi kendi 30sn canlı yenilemesini CheckHistoryTab içinde yapar.)
  async function refreshModal() {
    if (!selected) return
    const { id, url } = selected
    const res = await api.monitoring.getPageMonitors()
    if (res?.success) {
      setMonitors(res.data)
      const fresh = (res.data || []).find(x => x.id === id)
      if (fresh) setSelected(prev => (prev?.id === id ? fresh : prev))   // A'nın tazesi B'nin penceresini değiştirmesin
    }
    // Pencere o arada kapandı / başka izlemeye geçildi → A'nın sorunları B'nin penceresine yüklenmesin.
    if (selectedIdRef.current !== id) return
    loadIssues(id, issueFilterRef.current, true)
    loadConfirmations(url)
  }
  useVisibleInterval(() => { if (selected) refreshModal() }, selected ? 30000 : 0, false)

  /** Uçuşan form testini geçersiz kılar (form açılışı / kapanışı): geç yanıt yeni forma düşmez, düğme kilitli kalmaz. */
  function cancelTest() { testSeq.current++; setTesting(false) }

  function openNew() {
    cancelTest()
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (defaultTeamId != null ? String(defaultTeamId) : ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      slowResourceMs: defaults?.slowResourceMs ?? emptyForm.slowResourceMs,
      resourceConcurrency: defaults?.resourceConcurrency ?? emptyForm.resourceConcurrency,
      crawlDepth: defaults?.crawlDepth ?? emptyForm.crawlDepth,
      crawlMaxPages: defaults?.crawlMaxPages ?? emptyForm.crawlMaxPages })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', url: m.url || '', groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
      teamId: m.team_id != null ? String(m.team_id) : '',
      tags: m.tags || '', notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false,
      mode: m.mode || 'SINGLE_PAGE', crawlDepth: m.crawl_depth ?? 2, crawlMaxPages: m.crawl_max_pages ?? 50,
      excludePatterns: m.exclude_patterns || '', slowResourceMs: m.slow_resource_ms ?? 2000, useProxy: m.use_proxy || 'AUTO',
      alertThirdParty: !!m.alert_third_party, alertMixedContent: m.alert_mixed_content !== false, alertTimeout: m.alert_timeout !== false, resourceConcurrency: m.resource_concurrency ?? 5,
      intervalSeconds: m.interval_seconds ?? 300, timeoutMs: m.timeout_ms ?? 4000,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false }
  }
  function openEdit(row) {
    // Güncel satır (2026-10-09): detay kopyası bayat olabilir (liste yenilemesi / geri alma) — form ondan kurulursa kayıt
    // eski değerleri sessizce geri yazar. Listedeki satır kopyanın üstüne birleştirilir (bkz. utils/monitorDetailSync).
    const m = freshestRow(row, monitors)
    cancelTest()
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m))
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız URL'i değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    cancelTest()
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.url) })
    setModal('new')
  }
  function closeEdit() { cancelTest(); setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  async function runTest() {
    if (!form.url.trim()) return
    const my = ++testSeq.current   // bu formun testi — form kapanır / başka forma geçilirse yanıtı yok sayılır
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testPage({ url: normalizeUrl(form.url), timeoutMs: Number(form.timeoutMs), useProxy: form.useProxy || 'AUTO' })
      if (my !== testSeq.current) return   // geç yanıt: başka formun (ya da kapanmış formun) sonucu DEĞİL
      setTestResult(res?.success ? res.data : { error: res?.error || t('page.testError') })
    } finally {
      if (my === testSeq.current) setTesting(false)
    }
  }

  async function save() {
    // Doğrulama hataları ALANIN ALTINDA + ilk hatalıya kaydırma (2026-09-30) — tost yok, kullanıcı hatayı aramaz.
    if (fe.check({
      url: !form.url.trim() && t('mon.fieldRequired'),
      teamId: (form.teamId === '' || form.teamId == null) && t('mon.teamRequired'),
      groupName: !form.groupName?.trim() && t('mon.groupRequired'),   // grup + etiket zorunlu (2026-09-18)
      tags: !form.tags?.trim() && t('mon.tagsRequired'),
    })) return
    setSaving(true)
    try {
      const payload = {
        name: (form.name || form.url).trim(), url: normalizeUrl(form.url),
        groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
        tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING', notifyWebhook: form.notifyWebhook,
        mode: form.mode, crawlDepth: Number(form.crawlDepth), crawlMaxPages: Number(form.crawlMaxPages),
        excludePatterns: form.excludePatterns?.trim() || null, slowResourceMs: Number(form.slowResourceMs), useProxy: form.useProxy || 'AUTO',
        alertThirdParty: form.alertThirdParty, alertMixedContent: form.alertMixedContent, alertTimeout: form.alertTimeout, resourceConcurrency: Number(form.resourceConcurrency),
        intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        active: form.active,
      }
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createPageMonitor(payload)
        : await api.monitoring.updatePageMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      // Açık detayın kopyası da sunucu satırıyla tazelenir (2026-10-09): aynı pencereden ikinci "Düzenle" bayat kopyadan
      // kurulup ilk düzenlemeyi geri yazmasın. Yeni kayıt / başka izleme → kopyaya dokunulmaz (kimlik kapısı).
      setSelected((prev) => mergeSavedRow(prev, res.data))
      toast.success(t('page.saved')); closeEdit()
      // İlk / taze kontrol (2026-09-28): yeni kart boş kalmasın, hedefi/tarama ayarı değişen kart eski sonucu göstermesin.
      // Liste YÜKLENDİKTEN sonra başlar → kart ızgarada, dönen göstergeyle bekler (bkz. utils/checkAfterSave).
      if (shouldCheckAfterSave('page', { isNew: modal === 'new', before: modal, after: res.data })) startCheckAfterSave(checkNow, res.data)
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

      message: t('mon.deleteMsg', m.name || m.url),

      confirmText: t('page.delete'),

      cancelText: t('page.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deletePageMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      // Kart HEMEN düşer (işaret), açık detay kapanır; liste arka planda tazelenir — arayüz beklemez.
      markMonitorDeleted('page', m.id, res)
      setSelected((s) => (s?.id === m.id ? null : s))
      toast.success(t('page.deleted'))

      load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    // Kalıcı silme (2026-10-07): düzenleme penceresinden de ADIYLA ve geri alınamaz olduğu söylenerek onay alınır.
    if (!await showConfirm({ title: t('mon.deleteTitle'), message: t('mon.deleteMsg', modal.name || modal.url),
      confirmText: t('page.delete'), cancelText: t('page.cancel'), variant: 'danger' })) return
    const res = await api.monitoring.deletePageMonitor(modal.id)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    const id = modal.id
    markMonitorDeleted('page', id, res)   // anında düşer; tazeleme arka planda
    setSelected((s) => (s?.id === id ? null : s))
    toast.success(t('page.deleted')); closeEdit()
    load()
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerPageCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        // Geçmiş ARTIK tazeleniyor (setHistReload): sekmenin kendi 30 sn'lik canlı yenilemesi
        // 1. sayfa dışında ve özel aralıkta KAPALI, dolayısıyla modaldan koşturulan kontrolün
        // sonucu hiç görünmeyebiliyordu. Sinyal remount ETMEZ — seçilen aralık/sayfa/filtre kalır.
        // (Eski hatalı loadHistory(m.id, rangeDays) çağrısı geri GELMEDİ; not aşağıda duruyor.)
        // Buradaki eski loadHistory(m.id, rangeDays) çağrısı geçmiş yönetimi o bileşene taşınırken
        // temizlenmemişti; ikisi de TANIMSIZ olduğu için modal açıkken kontrol butonu ReferenceError
        // atıyor, altındaki setChecking(null) hiç çalışmıyor ve buton kalıcı kilitleniyordu.
        // Bayat kapanış YOK: `selected`/`issueFilter` isteğin başladığı andaki değerlerdir. Yanıt gelene kadar
        // pencere kapanmış ya da başka izlemeye geçilmiş olabilir → yalnız HÂLÂ açık olan aynı kayıt tazelenir.
        setSelected(prev => (prev?.id === m.id ? res.data : prev))
        if (selectedIdRef.current === m.id) loadIssues(m.id, issueFilterRef.current)
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      }
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  // ── Alarm kapsamı: GERÇEK backend geçidinin istemci aynası (SchedulerService alarmWorthy +
  //    PageCheckerService.countsForAlarm — LINK kuralı dahil). Kullanıcı her satırın alarma dahil
  //    edilip edilmediğini ve NEDENİNİ görür; toggle/exclude değişince kapsam da canlı değişir. ──
  function alarmScope(r, m) {
    if (isExcluded(m, r.resource_url)) return { inScope: false, reasonKey: 'page.scopeExcluded' }
    if (r.issue_type === 'BLOCKED' || r.issue_type === 'SLOW') return { inScope: false, reasonKey: 'page.scopeInconclusive' }
    if (r.issue_type === 'TIMEOUT') {
      if (r.resource_type === 'LINK') return { inScope: false, reasonKey: 'page.scopeLinkRule' }
      if (m.alert_timeout === false) return { inScope: false, reasonKey: 'page.scopeTimeoutOff' }
      return { inScope: true, reasonKey: null }   // timeout kovası 3P'den bağımsız (belgeli davranış)
    }
    if (r.issue_type === 'MIXED_CONTENT') {
      return m.alert_mixed_content === false
        ? { inScope: false, reasonKey: 'page.scopeMixedOff' } : { inScope: true, reasonKey: null }
    }
    if (r.issue_type === 'BROKEN') {
      if (r.resource_type === 'LINK' && r.http_status != null && r.http_status !== 404 && r.http_status !== 410)
        return { inScope: false, reasonKey: 'page.scopeLinkRule' }   // dış link 5xx → alarm dışı; null/404/410 geçer
      if (r.first_party) return { inScope: true, reasonKey: null }
      return m.alert_third_party
        ? { inScope: true, reasonKey: null } : { inScope: false, reasonKey: 'page.scopeThirdOff' }
    }
    return { inScope: false, reasonKey: 'page.scopeInconclusive' }
  }

  // ── Sorun satırından hariç-tutma: mevcut desen satırları + istemci tarafı contains ön-kontrolü ──
  const excludeLines = (m) => (m?.exclude_patterns || '').split('\n').map(s => s.trim()).filter(Boolean)
  // Backend literal kuralının aynası: yıldızsız satır = URL içinde case-insensitive contains.
  // Joker (*) satırları istemcide değerlendirilmez (yalnız buton disable ön-kontrolü — yanlış negatif zararsız).
  const isExcluded = (m, url) => {
    const low = (url || '').toLowerCase()
    return excludeLines(m).some(l => !l.includes('*') && low.includes(l.toLowerCase()))
  }

  async function addExclude(issue) {
    if (!selected) return
    const existing = excludeLines(selected)
    if (existing.length >= EXCLUDE_MAX_LINES) { toast.error(t('page.excludeFull')); return }
    // Düzenlenebilir onay: varsayılan desen = kaynak URL'i; kullanıcı kısaltabilir (ör. yalnız alan adı).
    const pattern = await showPrompt({
      title: t('page.excludeAddTitle'),
      message: t('page.excludeAddMsg'),
      defaultValue: issue.resource_url || '',
      confirmText: t('page.excludeAdd'),
      variant: 'warning',
    })
    const p = pattern?.trim()
    if (!p) return
    if (existing.some(l => l.toLowerCase() === p.toLowerCase())) { toast.success(t('page.excludeAdded')); return }   // dedupe
    const merged = [...existing, p].join('\n')
    // Partial PUT: yalnız excludePatterns — diğer alanlar backend'de containsKey korumalı, dokunulmaz.
    const res = await api.monitoring.updatePageMonitor(selected.id, { excludePatterns: merged })
    if (res?.success) {
      toast.success(t('page.excludeAdded'))
      if (res.data) { const id = selected.id; setSelected(prev => (prev?.id === id ? res.data : prev)) }   // kardeş: bayat kapanış yok
      load()
    } else {
      toast.error(res?.error || 'Error')
    }
  }

  function exportIssuesCsv() {
    if (!issues.length) return
    const head = ['resource_url', 'resource_type', 'source_page', 'issue_type', 'first_party', 'http_status', 'duration_ms', 'checked_at']
    // Ortak kaçış: formül nötrleme + CR/LF tırnaklama (utils/csv.js). Satır sonu CRLF ve
    // başta BOM — Excel Türkçe karakterleri ancak öyle doğru açıyor (envanter dışa aktarımıyla aynı). Gövde csvRows,
    // indirme ortak downloadCsv (öneri 29 — dosya baytları ve adı aynı).
    downloadCsv(`page-issues-${selected?.id ?? 'x'}.csv`, '﻿' + csvRows([head, ...issues.map(r => head.map(k => r[k]))]))
  }

  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('page.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('page.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('page.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])

  // Vekil süzgeci seçenekleri (2026-09-22): gerçekte kullanılan yola göre (proxy_effective).
  const proxyFilterOptions = useMemo(() => [{ value: 'all', label: t('mon.proxy.filterAll') },
    { value: 'proxy', label: t('mon.proxy.filterProxy') }, { value: 'direct', label: t('mon.proxy.filterDirect') }], [t])
  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (!matchesProxy(m, proxyFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return (m.url || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, tagFilter, proxyFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, ok: 0, degraded: 0, down: 0, alarm: 0, unacked: 0 }
    for (const m of scoped) {
      if (m.status === 'OK') c.ok++
      else if (m.status === 'DEGRADED') c.degraded++
      else if (m.status === 'DOWN') c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      ok:       m => m.status === 'OK',
      degraded: m => m.status === 'DEGRADED',
      down:     m => m.status === 'DOWN',
      alarm:    m => m.active_alarm,
      unacked:  m => m.active_alarm && !m.alarm_acknowledged,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  // Varsayılan kart sırası (2026-10-01): sorunlu önce → grup adı A→Z (grup içinde ad) → grupsuzlar ada göre
  const orderedMonitors = useMemo(() => sortMonitorsDefault(displayMonitors, 'page'), [displayMonitors])
  const pager = usePagination(orderedMonitors, {
    listKey: 'page-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, proxyFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, proxyFilter, search, statFilter, pager }),
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'issues' ? detailTab : null,
    // Tanılama penceresinde gösterilen çalıştırma (2026-10-05) — bağlantı paylaşılınca KAYITLI sonuç açılır
    pidx: selected && pageDx?.monitorId === selected.id && canDiagnoseRow(selected) ? (pageDx.runId ?? null) : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',    Icon: LayoutDashboard, label: t('page.dashTotal'),    value: counts.total,    cls: 'total'    },
    { key: 'ok',       Icon: CheckCircle2,    label: t('page.dashOk'),       value: counts.ok,       cls: 'valid'    },
    { key: 'degraded', Icon: TriangleAlert,   label: t('page.dashDegraded'), value: counts.degraded, cls: 'warning'  },
    { key: 'down',     Icon: ServerCrash,     label: t('page.dashDown'),     value: counts.down,     cls: 'critical' },
    { key: 'alarm',    Icon: Siren,           label: t('page.dashAlarm'),    value: counts.alarm,    cls: 'high'     },
    { key: 'unacked',  Icon: BellDot,         label: t('page.dashUnacked'),  value: counts.unacked,  cls: 'error'    , hint: t('mondash.unackedHint') },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  // Durum sözlüğü (kart şeridi / rozet / detay kenarı): OK → up, DOWN → down, DEGRADED → warn;
  // CONFIG_ERROR ve bilinmeyen → unknown (kesinti gibi KIRMIZI çizilmez — alarm da üretmez).
  const statusKey = (m) => (m?.status === 'OK' ? 'up' : m?.status === 'DOWN' ? 'down' : m?.status === 'DEGRADED' ? 'warn' : 'unknown')
  const statusText = (s) => STATUS_TEXT[s] || STATUS_TEXT.unknown
  function statusBadge(m) {
    const s = m?.status
    const label = s === 'OK' ? t('page.statusOk') : s === 'DEGRADED' ? t('page.statusDegraded')
      : s === 'DOWN' ? t('page.statusDown') : s === 'CONFIG_ERROR' ? t('page.statusConfigError')
      : t('page.statusUnknown')
    // Yapılandırma hatası "bilinmiyor" sözlüğünde ama MOR mürekkeple ayrışır (eski palet).
    return <MonitorStatusBadge status={statusKey(m)} className={s === 'CONFIG_ERROR' ? 'border-violet-300 text-violet-700 dark:border-violet-800 dark:text-violet-300' : undefined}>{label}</MonitorStatusBadge>
  }

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('page.noTeam'))
    : (defaultTeamName || t('page.noTeam'))
  const issueFilters = ['all', 'BROKEN', 'TIMEOUT', 'BLOCKED', 'MIXED_CONTENT', 'SLOW', 'firstParty']
  const excludeCount = (form.excludePatterns || '').split('\n').map(s => s.trim()).filter(Boolean).length

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // Detay penceresi açıkken form ONUN İÇİNDE çizilir: ModalShell iç içe derinliği React ağacından okur,
  // böylece form (ve örtüsü) detay penceresinin ÜSTÜNDE katmanlanır.
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={ScanSearch}
      title={modal === 'new' ? t('page.modalNew') : t('page.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('page.testing') : null}
      footer={<>
        <Button variant="secondary" className="mr-auto" onClick={runTest}
          aria-busy={testing || undefined} disabled={testing || !form.url.trim()}>
          <FlaskConical size={14} />{t('page.test')}
        </Button>
        {modal !== 'new' && canDeleteRow(modal) && <Button variant="destructive" onClick={del}><Trash2 size={14} />{t('page.delete')}</Button>}
        <Button variant="secondary" onClick={closeEdit}>{t('page.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.teamId}>{t('page.save')}</Button>
      </>}>
      {dupSource
        ? <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>
        : <AlertBanner tone="info" icon={ScanSearch}>{t('page.typeInfo')}</AlertBanner>}

      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField full label={t('page.url')} required {...fe.fieldProps('url')} hint={t('page.urlHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
              onChange={e => { setForm(f => ({ ...f, url: e.target.value })); fe.clear('url') }}
              onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} />
          )}
        </FormField>
        <FormField label={t('page.name')}>
          {({ id }) => <Input id={id} value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />}
        </FormField>
        <FormField label={t('page.team')} required {...fe.fieldProps('teamId')}>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => { setForm(f => ({ ...f, teamId: v })); fe.clear('teamId') }} options={teamSelectOptions} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('page.noTeam')} disabled />}
        </FormField>
        <FormField full label={t('page.group')} required {...fe.fieldProps('groupName')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('groupName') }}
              options={[{ value: '', label: t('page.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('page.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="PAGE" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />

        {/* Mod seçimi */}
        <FormField label={t('page.mode')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.mode} onChange={v => setForm(f => ({ ...f, mode: v }))}
              options={[{ value: 'SINGLE_PAGE', label: t('page.modeSingle') }, { value: 'SITE_CRAWL', label: t('page.modeCrawl') }]} />
          )}
        </FormField>
        {form.mode === 'SITE_CRAWL' && (<>
          <FormField label={t('page.crawlDepth')}>
            {({ id }) => <Input id={id} type="number" min="0" max="5" value={form.crawlDepth} onChange={e => setForm(f => ({ ...f, crawlDepth: Number(e.target.value) }))} />}
          </FormField>
          <FormField label={t('page.crawlMaxPages')}>
            {({ id }) => <Input id={id} type="number" min="1" max="500" value={form.crawlMaxPages} onChange={e => setForm(f => ({ ...f, crawlMaxPages: Number(e.target.value) }))} />}
          </FormField>
        </>)}
        <FormField full hint={t('page.excludeHint')}
          label={<>{t('page.excludePatterns')}
            {excludeCount > 0 && <Badge variant="outline" className="font-semibold text-muted-foreground">{t('page.excludeCount', excludeCount)}</Badge>}</>}>
          {({ id, describedBy }) => (
            <Textarea id={id} aria-describedby={describedBy} rows={4} className="font-mono text-[13px] leading-normal"
              value={form.excludePatterns} spellCheck={false} placeholder={t('page.excludePh')}
              onChange={e => setForm(f => ({ ...f, excludePatterns: e.target.value }))} />
          )}
        </FormField>
        {/* Kurumsal vekil (2026-09-21): sertifika envanteriyle aynı karar */}
        <LabelSlot full>
          <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
            effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />
        </LabelSlot>

        <CheckField full checked={form.alertThirdParty} onCheckedChange={v => setForm(f => ({ ...f, alertThirdParty: v }))}
          label={t('page.alertThirdParty')} hint={t('page.alertThirdPartyHint')} />
        <CheckField full checked={form.alertMixedContent} onCheckedChange={v => setForm(f => ({ ...f, alertMixedContent: v }))}
          label={t('page.alertMixedContent')} hint={t('page.alertMixedContentHint')} />
        <CheckField full checked={form.alertTimeout} onCheckedChange={v => setForm(f => ({ ...f, alertTimeout: v }))}
          label={t('page.alertTimeout')} hint={t('page.alertTimeoutHint')} />

        {/* Etiketler */}
        <FormSection title={t('page.tagsTitle')} required {...fe.fieldProps('tags')}>
          <TagInput value={form.tags} onChange={v => { setForm(f => ({ ...f, tags: v })); fe.clear('tags') }} placeholder={t('page.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>

        {/* Gelişmiş — açılır/kapanır (shadcn Collapsible; kapalıyken içerik DOM'da yok, eskisi gibi) */}
        <Collapsible open={advOpen} onOpenChange={setAdvOpen} className="min-w-0 rounded-lg border bg-muted/30 sm:col-span-2">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost"
              className="h-auto w-full justify-start gap-2 rounded-lg px-3.5 py-3 font-semibold hover:bg-muted/50">
              <ChevronDown size={16} aria-hidden="true"
                className={cn('text-muted-foreground transition-transform motion-reduce:transition-none', advOpen && 'rotate-180')} />
              {t('page.advanced')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent className="flex min-w-0 flex-col gap-3 px-3.5 pb-3.5">
            <FormGrid>
              <FormField label={t('page.slowResourceMs')}>
                {({ id }) => <Input id={id} type="number" min="100" step="100" value={form.slowResourceMs} onChange={e => setForm(f => ({ ...f, slowResourceMs: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('page.resourceConcurrency')}>
                {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.resourceConcurrency} onChange={e => setForm(f => ({ ...f, resourceConcurrency: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('page.timeoutMs')}>
                {({ id }) => <Input id={id} type="number" min="1000" step="500" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('page.confirmAttempts')}>
                {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('page.confirmInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('page.recoveryChecks')}>
                {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('page.recoveryInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
            </FormGrid>
            <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('page.active')} />
            <FormHint full={false}>ⓘ {t('page.confirmHint')}</FormHint>
          </CollapsibleContent>
        </Collapsible>
      </FormGrid>

      {testResult && (
        <AlertBanner className="mt-3"
          tone={testResult.error ? 'danger' : testResult.status === 'OK' ? 'success' : 'warning'}
          icon={testResult.error || testResult.status !== 'OK' ? AlertTriangle : Check}
          title={testResult.error ? t('page.testError')
            : t(`page.status${testResult.status === 'OK' ? 'Ok' : testResult.status === 'DEGRADED' ? 'Degraded' : 'Down'}`)}>
          {testResult.error
            ? testResult.error
            : <>
                {testResult.total_resources} {t('page.mResources')} · {testResult.broken_resources} {t('page.mBroken')} · {testResult.timeout_count ?? 0} {t('page.mTimeout')} · {testResult.mixed_content_count} {t('page.mMixed')}
                {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}
              </>}
        </AlertBanner>
      )}
      {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="page-change-note" value={changeNote} onChange={setChangeNote} />
      )}
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader type="page" title={t('page.title')} subtitle={t('page.subtitle')}
        count={loading ? null : monitors.length} down={counts.down}
        refreshEvery={REFRESH_INTERVAL} refreshResetKey={loadNonce} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('page.addMonitor')} />

      <MonitorHowBox bullets={[t('page.how1'), t('page.how2'), t('page.how2b'), t('page.how3'), t('page.how4'), t('page.how5'), t('page.how6'), t('page.how7'), t('page.how8')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {/* Kart görünümü seçicisi satırın İLK öğesi (mr-auto): süzgeçler + arama sağda kalır; telefonda satır sarar */}
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          <SearchableSelect value={proxyFilter} onChange={setProxyFilter} options={proxyFilterOptions} ariaLabel={t('mon.proxy.label')} />
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:min-w-[200px]" placeholder={t('page.searchPlaceholder')} aria-label={t('page.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} data-page-search="" />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('page.noMonitorsAdmin') : t('page.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="PAGE"
          api={{ update: api.monitoring.updatePageMonitor, remove: api.monitoring.deletePageMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22; vekil süzgeciyle görünür oldu) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Kart sunumu page/PageMonitorCard'da (MonitorCard ailesi, stretched button). Sayfaya ait kablolama yuva
               olarak geçer: durum sözlüğü (statusKey/statusBadge — detay penceresiyle aynı kaynak; CONFIG_ERROR mor
               rozet), toplu seçim kutusu (seçim kümesi burada) ve eylemler (yetki + işleyiciler burada). */
            <PageMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} density={density} running={isRunning(m.id)} status={statusKey(m)} badge={statusBadge(m)} onOpen={() => openDetail(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.url)} />
              )}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={m.url}
                  running={isRunning(m.id)}
                  onCheck={canCheckRow(m) ? () => checkNow(m) : undefined} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('page.check')} editTitle={t('page.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('page.delete')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={statusKey(selected)} badge={statusBadge(selected)} title={selected.url} noc={{ type: 'PAGE', monitor: selected, canEdit: canManageRow(selected) }}
          actions={
            /* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
               koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
               kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
              running={isRunning(selected.id)}
              onCheck={canCheckRow(selected) ? () => checkNow(selected) : undefined}
              checkTitle={t('page.check')}
              onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
              editTitle={t('page.edit')}
              onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
              onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
              deleting={deleting === selected.id}
              deleteTitle={t('page.delete')}
              onClose={closeDetail}>
              {/* Uçtan uca tanılama (2026-10-05) — yalnız `can_diagnose` satırında; paylaşılan eylem grubuna çocuk olarak */}
              {canDiagnoseRow(selected) && (
                <Button type="button" variant="outline" size="icon-sm" data-slot="pgdx-open"
                  className={cn(MON_ACT, 'pointer-coarse:size-10', MON_ACT_TONE.edit)}
                  onClick={() => openDiagnose(selected)} title={t('pgdx.open')} aria-label={t('pgdx.open')}>
                  <Stethoscope size={13} aria-hidden="true" />
                </Button>
              )}
              <CopyLinkButton iconOnly variant="outline" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-0" />
          <DetailSummary items={[
            { key: 'status', label: t('page.lastStatus'), valueClassName: STATUS_TEXT[selected.status],
              value: t(`page.status${selected.status === 'OK' ? 'Ok' : selected.status === 'DEGRADED' ? 'Degraded' : selected.status === 'DOWN' ? 'Down' : 'Unknown'}`) },
            { key: 'broken', value: selected.broken_resources ?? '—', label: t('page.mBroken') },
            { key: 'timeout', value: selected.timeout_count ?? '—', label: t('page.mTimeout') },
            { key: 'mixed', value: selected.mixed_content_count ?? '—', label: t('page.mMixed') },
            { key: 'res', value: selected.total_resources ?? '—', label: t('page.mResources') },
            selected.checked_at && { key: 'last', value: formatDateSec(selected.checked_at), label: t('page.lastCheck'), time: true },
          ]} />
          <DetailDivider />
          {/* Canlı teyit durumu — 30sn oto-yenilemeyle ilerler; kullanıcı denemenin kaçıncı bacağında olduğunu görür. */}
          {confirmations.filter(c => c.alert_type === 'PAGE_DOWN' || c.alert_type === 'PAGE_INTEGRITY').map((c, i) => (
            <AlertBanner key={`cf-${i}`} tone="warning" icon={Spinner} className="font-semibold">
              {t('page.confirmBanner', Math.max(1, c.attempt), c.total_attempts,
                c.next_attempt_at ? formatDateSec(c.next_attempt_at) : '—')}
            </AlertBanner>
          ))}
          <DetailTabs value={detailTab} onValueChange={setDetailTab}
            countsFor={{ kind: 'page', monitorId: selected.id, notesType: 'PAGE', notesTarget: selected.url, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['issues', t('page.tabIssues')], ['chart', t('page.tabChart')], ['control', t('hist.tab')],
              ['alerts', t('page.tabAlerts')], ['notes', t('page.tabNotes')],
              // Yapılandırma geçmişi — kontrol geçmişiyle KARIŞTIRILMAMALI:
              // orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            <TabsContent value="issues">
              {/* Süzgeç düğmeleri: seçili olan aria-pressed. Kap yerleşim sınıfları (.upt-range-btns /
                  .page-issue-filters) küçük düğme aralık köprüsünü sıfırlar (App.css katmansız). */}
              <div className="upt-range-btns page-issue-filters">
                {issueFilters.map(f => (
                  <Button key={f} type="button" variant={issueFilter === f ? 'default' : 'secondary'} size="sm"
                    aria-pressed={issueFilter === f}
                    onClick={() => selectIssueFilter(selected.id, f)}>{t(`page.filter_${f}`)}</Button>
                ))}
                <Button type="button" variant="secondary" size="sm" className="ml-auto"
                  disabled={!issues.length} onClick={exportIssuesCsv}><Download size={12} />{t('page.exportCsv')}</Button>
              </div>
              {issuesLoading ? <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" /> : issues.length === 0 ? (
                <StatusBlock tone="neutral" icon={Inbox} title={t('page.noIssues')} className="py-8" />
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head" style={{ gridTemplateColumns: PAGE_ISSUE_COLS }}>
                    <span>{t('page.colTime')}</span><span>{t('page.colType')}</span><span>{t('page.colResource')}</span><span>{t('page.colIssue')}</span><span>HTTP</span><span>{t('page.colDuration')}</span><span>{t('page.colScope')}</span><span>{t('page.colActions')}</span>
                  </div>
                  {issues.map((r, i) => {
                    const RI = RES_ICON[r.resource_type] || Link2
                    const scope = alarmScope(r, selected)
                    // Her tarama turunun (checked_at) başına belirgin başlık bandı — turlar net ayrışır.
                    const runStart = i === 0 || (issues[i - 1].checked_at !== r.checked_at)
                    const runCount = runStart ? issues.filter(x => x.checked_at === r.checked_at).length : 0
                    return (
                      <Fragment key={`${r.id || ''}#${i}`}>
                      {runStart && (
                        <div data-slot="issue-run-header"
                          className="mt-2.5 flex items-center gap-2.5 border-t-[3px] border-t-primary bg-muted/50 px-2 py-1.5 text-xs font-bold">
                          <span>{r.checked_at ? formatDateSec(r.checked_at) : '—'}</span>
                          <Badge variant="outline" className="bg-card font-semibold text-muted-foreground">{t('page.runHdrCount', runCount)}</Badge>
                        </div>
                      )}
                      <div className="upt-rt-grid" style={{ gridTemplateColumns: PAGE_ISSUE_COLS }}>
                        <span className="upt-rt-time">{r.checked_at ? formatDateSec(r.checked_at) : '—'}</span>
                        <span className="flex items-center gap-1.5">
                          <RI size={13} aria-hidden="true" />{r.resource_type}{!r.first_party && <span title={t('page.thirdParty')} className="text-muted-foreground">·3P</span>}
                        </span>
                        <span className="break-all" title={r.source_page ? `${t('page.foundOn')}: ${r.source_page}` : ''}>
                          {/* href guard (L3): yalnız http(s) source_page linklenir — javascript:/data: vb. şema tıklanabilir XSS'i engellenir */}
                          {r.source_page && r.source_page !== r.resource_url && /^https?:\/\//i.test(r.source_page)
                            ? <a href={r.source_page} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} className="text-inherit">{r.resource_url}</a>
                            : r.resource_url}
                        </span>
                        <span className={cn('font-semibold', ISSUE_TEXT[r.issue_type] || ISSUE_TEXT.BROKEN)}>
                          {r.issue_type === 'MIXED_CONTENT' ? <ShieldAlert size={12} aria-hidden="true" className="inline align-[-2px]" /> : null} {t(`page.issue_${r.issue_type}`)}
                        </span>
                        <span className="upt-rt-ms">{r.http_status ?? '—'}</span>
                        <span className="upt-rt-ms">{r.duration_ms != null ? r.duration_ms + 'ms' : '—'}</span>
                        <span>
                          {/* Alarm kapsamı rozeti — neden (title) üzerine gelince */}
                          <Badge variant="outline" data-scope={scope.inScope ? 'in' : 'out'}
                            title={scope.reasonKey ? t(scope.reasonKey) : t('page.scopeInTitle')}
                            className={cn('cursor-help rounded-sm',
                              scope.inScope
                                ? 'border-destructive/40 bg-destructive/10 font-bold text-destructive dark:bg-destructive/20'
                                : 'border-dashed font-semibold text-muted-foreground')}>
                            {scope.inScope ? t('page.scopeIn') : t('page.scopeOut')}
                          </Badge>
                        </span>
                        <span>
                          {canManageRow(selected) && (
                            isExcluded(selected, r.resource_url)
                              // Devre dışı düğme işaretçi olayı üretmez → ipucu sarmalayıcı <span>'da (SHADCN.md §4.3 Tooltip).
                              ? <SimpleTooltip content={t('page.excludeAlready')}>
                                  <span className="inline-flex">
                                    <Button type="button" variant="secondary" size="icon-xs" disabled
                                      aria-label={t('page.excludeAlreadyFor', r.resource_url)}>
                                      <EyeOff size={12} aria-hidden="true" /></Button>
                                  </span>
                                </SimpleTooltip>
                              : <SimpleTooltip content={t('page.excludeAdd')}>
                                  <Button type="button" variant="secondary" size="icon-xs"
                                    aria-label={t('page.excludeAddFor', r.resource_url)}
                                    onClick={e => { e.stopPropagation(); addExclude(r) }}>
                                    <EyeOff size={12} aria-hidden="true" /></Button>
                                </SimpleTooltip>
                          )}
                        </span>
                      </div>
                      </Fragment>
                    )
                  })}
                </div>
              )}
            </TabsContent>

            <TabsContent value="chart">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="page" />
              </Suspense>
            </TabsContent>

            <TabsContent value="control">
              {/* Kırık ve Zaman aşımı AYRI kolonlar (2026-08-04); eski kayıtlarda timeout '—' (o dönem kırığa dahildi). */}
              <CheckHistoryTab kind="page" monitorId={selected.id} listKey="page-history" reloadSignal={histReload}
                defaultPreset={7} gridClass="page-rt-grid"
                columns={[t('page.colTime'), t('page.colStatus'), t('page.mBroken'), t('page.mTimeout'), t('page.mMixed')]}
                renderRow={(c) => {
                  // Hata teşhisi (2026-10-05): DOWN / CONFIG_ERROR / DEGRADED satırın KENDİ hata satırı (satırın altında tam
                  // genişlik): neden rozeti + tek satır + aç/kapa, açılınca Neden / Etkisi / Ne yapmalı paneli. Eskiden hata
                  // metni ve HTTP kodu geçmişte HİÇ görünmüyordu. Özet ara sütuna konmaz: tablonun otomatik düzeninde sütunu
                  // genişletip 768'de tabloyu (ve paneli) kabından taşırıyordu (e2e check-failure-history).
                  const k = failureRowKey(c)
                  const failed = !isHealthy('page', c)
                  const when = formatDateSec(c.checked_at)
                  return (<>
                    <span className="upt-rt-time">{when}</span>
                    <span className={cn('font-semibold', statusText(c.status))}>
                      {c.status === 'OK' ? t('page.statusOk') : c.status === 'DEGRADED' ? t('page.statusDegraded')
                        : c.status === 'CONFIG_ERROR' ? t('page.statusConfigError') : t('page.statusDown')}</span>
                    <span className="upt-rt-ms">{c.broken_resources ?? '—'}</span>
                    <span className="upt-rt-ms">{c.timeout_count ?? '—'}</span>
                    <span className="upt-rt-ms">{c.mixed_content_count ?? '—'}</span>
                    {failed && (
                      <CheckFailureBlock type="page" check={c} monitor={selected} open={failRows.isOpen(k)} when={when}
                        panelId={failurePanelId('page', k)} onToggle={() => failRows.toggle(k)}
                        canDiagnose={canDiagnoseRow(selected)} onDiagnose={() => openDiagnose(selected)} />
                    )}
                  </>)
                }} />
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.url} types={alertTypesFor('page')} /></TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PAGE" target={selected.url} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="page" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)}
                  // Geri alma sonrası liste + açık detay kopyası tazelenir (2026-10-09) — sonraki "Düzenle" geri alınanı ezmesin
                  onRestored={() => reloadAndSyncDetail(load, selected.id, setSelected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

          {/* Uçtan uca tanılama penceresi (2026-10-05) — detayın İÇİNDE: iç içe kabuk, Escape yalnız onu kapatır */}
          {pageDx && pageDx.monitorId === selected.id && canDiagnoseRow(selected) && (
            <Suspense fallback={null}>
              <PageDiagnoseDialog monitor={selected} initialRunId={pageDx.initialRunId}
                onRunChange={(runId) => setPageDx((cur) => (cur ? { ...cur, runId } : cur))}
                onClose={() => setPageDx(null)} />
            </Suspense>
          )}

          {formModal}
        </MonitorDetailModal>
      )}
      {!selected && formModal}

      {/* Sayfa düzeyi toplu kontrol: önce takım seçimi, sonra akan sonuç tablosu.
          Depolama anahtarı TÜR BAŞINA ayrı — tek anahtar paylaşılsaydı buradaki seçim
          panonun sertifika seçimini ezerdi. */}
      {checkRun.pickerOpen && (
        <CheckTeamPicker
          buckets={monitorTeamBuckets(checkable)}
          storageKey="sm.checkRun.teams.page"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="page"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
