import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
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
import MonitorProxyField from './ui/MonitorProxyField.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'
import { useToast } from './ui/Toast.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import TagInput from './ui/TagInput.jsx'
import { Trash2, Gauge, FlaskConical, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ChevronDown, Image, FileCode, Frame, Type, Download, Link2, Wand2, Inbox, Copy, Stethoscope } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { CheckFailureBlock } from './checks/CheckFailurePanel.jsx'
import useFailureRows, { failurePanelId, failureRowKey } from './checks/useFailureRows.js'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesProxy } from '../utils/monitorFilters.js'
import { markMonitorDeleted, monitorKind, useWithoutDeleted } from '../utils/recentlyDeleted.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import PageSpeedMonitorCard from './pagespeed/PageSpeedMonitorCard.jsx'
import { budgetFor } from './pagespeed/pageSpeedCardModel.js'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { csvRows } from '../utils/csv.js'
import { downloadCsv } from '../utils/csvExport.js'
import { formatBytes } from '../utils/formatBytes.js'
import { suggestThresholds, suggestionIsPartial } from '../utils/pageSpeedThresholds.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupInput, InputGroupText } from '@/components/shadcn/input-group'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { TabsContent } from '@/components/shadcn/tabs'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint, LabelSlot,
} from './monitoring/MonitorForm.jsx'
import { MON_ACT, MON_ACT_TONE } from './ui/CheckRunning.jsx'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))
// Uçtan uca tanılama (2026-10-05) — tembel: giriş paketi büyümez
const PageSpeedDiagnoseDialog = lazy(() => import('./pagespeed/diagnose/PageSpeedDiagnoseDialog.jsx'))

/** Kontrol aralığı seçenekleri. TABAN 5 dk: bir ölçüm onlarca istek demek — dakikalık ölçüm hem
 *  tek pod'u hem izlenen sistemi boğar. Sunucu da aynı tabanı uygular (form atlanabilir, uç atlanamaz). */
const INTERVALS = [
  { value: 300,   labelKey: 'pspd.iv5m'  },
  { value: 900,   labelKey: 'pspd.iv15m' },
  { value: 1800,  labelKey: 'pspd.iv30m' },
  { value: 3600,  labelKey: 'pspd.iv1h'  },
  { value: 21600, labelKey: 'pspd.iv6h'  },
  { value: 86400, labelKey: 'pspd.iv24h' },
]
const intervalIdx = (secs) => {
  const i = INTERVALS.findIndex(o => o.value === secs)
  if (i >= 0) return i
  let best = 0, bd = Infinity
  INTERVALS.forEach((o, j) => { const d = Math.abs(o.value - secs); if (d < bd) { bd = d; best = j } })
  return best
}
const REFRESH_INTERVAL = 60
const RES_ICON = { IMG: Image, CSS: FileCode, JS: FileCode, IFRAME: Frame, FONT: Type, FAVICON: Image, OTHER: Link2 }

/** Grafik metrikleri — sunucu `?metric=` ile TEK seriyi projekte eder (yeni tarama üretmez). */
// (Kartın haftalık eşik üstü satırı, boyut/ihlal yardımcıları → pagespeed/PageSpeedMonitorCard + pageSpeedCardModel.)

/**
 * Durum metin tonu (özet değeri / geçmiş satırı) — eski satır içi STATUS_COLOR hex'lerinin jeton
 * karşılığı. SLOW ayrı bir ton: kesinti DEĞİL, sayfa ayakta ama hedeflenenden ağır/yavaş.
 * CONFIG_ERROR kendi (mor) tonunu korur: kesinti değil, izlemenin YAPILANDIRMASI hatalı.
 */
const STATUS_TEXT = {
  OK: 'text-success', SLOW: 'text-amber-600 dark:text-amber-400', DOWN: 'text-destructive',
  CONFIG_ERROR: 'text-violet-600 dark:text-violet-400', unknown: 'text-muted-foreground',
}
const statusText = (s) => STATUS_TEXT[s] || STATUS_TEXT.unknown
/** Sekme içi küçük bölüm etiketi (eski .pspd-metric-label). */
const SECTION_LABEL = 'text-[.82em] font-semibold tracking-[.03em] text-muted-foreground uppercase'

const METRICS = [
  { key: 'load',     labelKey: 'pspd.metricLoad',     unit: 'ms' },
  { key: 'ttfb',     labelKey: 'pspd.metricTtfb',     unit: 'ms' },
  { key: 'size',     labelKey: 'pspd.metricSize',     unit: 'B'  },
  { key: 'requests', labelKey: 'pspd.metricRequests', unit: ''   },
]

const emptyForm = {
  name: '', url: '', groupName: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  intervalSeconds: 1800, timeoutMs: 10000,
  maxLoadMs: '', maxTtfbMs: '', maxPageKb: '', maxRequests: '',
  userAgent: '', sendDnt: false, useProxy: 'OFF', excludeTrackers: false, trackerPatterns: '',
  basicAuthUser: '', basicAuthPass: '', customHeaders: '', resourceConcurrency: 5,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30,
  active: true,
}

/**
 * Eşik ihlali delili: hangi metrik, eşik neydi, ölçülen neydi.
 *
 * <p>{@code breach_detail} ölçüm ANINDAKİ eşiği taşır ("TTFB:1000>2955"). Eşik sonradan
 * değiştirilirse bugünkü değeri göstermek geçmişi yanlış açıklardı; o yüzden satır kendi
 * delilini taşıyor. Delil yoksa (eski kayıt) yalnız metrik adları yazılır.
 */
function BreachEvidence({ metrics, detail, t }) {
  const label = (k) => {
    const key = `pspd.breach_${String(k).toLowerCase()}`
    const s = t(key)
    return s === key ? k : s
  }
  const parsed = (detail || '').split(',').map(x => x.trim()).filter(Boolean).map(part => {
    const m = /^([A-Z]+):(.*?)>(.*)$/.exec(part)
    return m ? { key: m[1], threshold: m[2], measured: m[3] } : null
  }).filter(Boolean)

  const keys = String(metrics || '').split(',').map(x => x.trim()).filter(Boolean)
  const rows = parsed.length > 0 ? parsed : keys.map(k => ({ key: k }))

  return (
    <span className="mt-[3px] flex flex-wrap gap-1">
      {rows.map(r => (
        <Badge key={r.key} variant="warning" className="items-baseline gap-[5px] px-[7px] py-px text-[11px] font-semibold">
          {label(r.key)}
          {r.threshold != null && (
            <span data-slot="breach-nums" className="font-mono text-[10.5px] font-normal opacity-90">{r.threshold} → <strong>{r.measured}</strong></span>
          )}
        </Badge>
      ))}
    </span>
  )
}

export default function PageSpeedMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
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
  // Uçtan uca tanılama (2026-10-05, keyword ile aynı kural): YALNIZ sunucunun satır bayrağı `can_diagnose` (= can_check +
  // diagnostics.run). Liste satırına da bakılır: "Şimdi kontrol et" açık detayın kopyasını tetik yanıtıyla DEĞİŞTİRİR ve o
  // yanıtta bayrak yok — pencere koşu ortasında sökülmesin. (`monitors` aşağıda tanımlı; çağrı anında okunur.)
  const canDiagnoseRow = (m) => !!m && (m.can_diagnose === true || monitors.some((x) => x.id === m.id && x.can_diagnose === true))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })


  const sparks = useSparklines('pagespeed')   // kart mini trendi (2026-09-12)
  const sla = useSla('pagespeed')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const week7 = useSla('pagespeed', 7)     // eşik üstü: bu hafta (2026-09-12, #15)
  const week14 = useSla('pagespeed', 14)   // eşik üstü: son 14 gün → geçen hafta = 14g − 7g
  // Kontrol geçmişi hata teşhisi (2026-10-05): açık hata panelleri (satır anahtarıyla)
  const failRows = useFailureRows()
  // Uçtan uca tanılama penceresi (2026-10-05): { monitorId, runId, initialRunId } | null. Derin bağlantı ?monitor=<id>&psdx=<no>
  // YALNIZ kayıtlı çalıştırmayı açar (canlı koşu asla kendiliğinden başlamaz); `runId` gösterilen çalıştırmadır (URL'e yazılır).
  const [speedDx, setSpeedDx] = useState(() => {
    const runId = readUrlInt('psdx', null), monitorId = readUrlInt('monitor', null)
    return runId && monitorId ? { monitorId, runId, initialRunId: runId } : null
  })
  const [rawMonitors, setMonitors] = useState([])
  // Silme anında (2026-10-07): silinen kart tam liste yüklemesini BEKLEMEDEN düşer, bayat yanıt geri getiremez.
  const monitors = useWithoutDeleted(monitorKind('pagespeed'), rawMonitors)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  const [resources, setResources] = useState([])
  const [resTotal, setResTotal] = useState(0)   // listedeki değil, KIRILIMDAKİ toplam kaynak sayısı
  const [breaches, setBreaches] = useState([])
  const [resCheckId, setResCheckId] = useState(null)   // null = son ölçüm (LATEST)
  const [resLoading, setResLoading] = useState(false)
  // await SONRASI için güncel değerler (bayat kapanış YOK): checkNow/refreshModal yanıtı gelene kadar pencere
  // kapanmış, başka izlemeye geçilmiş ya da başka bir ölçüm (anlık görüntü) seçilmiş olabilir.
  const selectedIdRef = useRef(null)
  selectedIdRef.current = selected?.id ?? null
  const resCheckIdRef = useRef(resCheckId)
  resCheckIdRef.current = resCheckId
  const [modal, setModal] = useState(null)
  const fe = useFormErrors(modal)   // doğrulama hataları alanın altında + ilk hatalıya kaydırma (2026-09-30)
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)
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
  const [detailTab, setDetailTab] = useState('resources')
  const deepLinkTab = useDeepLinkTab('resources')   // ?monitor=…&mtab=changes derin bağlantısı — ilk açılışta bir kez
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)
  const [metric, setMetric] = useState('load')
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [proxyFilter, setProxyFilter] = useState(() => readUrlParam('via', 'all'))   // vekil süzgeci (2026-09-22): all | proxy | direct
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [loadNonce, setLoadNonce] = useState(0)   // her başarılı yüklemede artar: başlık çipi geri sayımı kendisi sayar, sayfa saniyede bir çizilmez (2026-10-01)
  // Kart yoğunluğu (2026-09-27): Kompakt / Zengin — sayfa HER AÇILIŞTA Zengin başlar; Kompakt seçimi yalnız sayfada
  // kalındığı sürece geçerli, kalıcı DEĞİL (kullanıcı kararı; bkz. hooks/useCardDensity)
  const [density, setDensity] = useCardDensity('pagespeed')

  const load = useCallback(async () => {
    // Hata dalı ŞART: API düşerse liste boş kalır ve ekran "henüz izleme yok" der —
    // kullanıcı izlemelerinin silindiğini sanır (sayfa bütünlüğünde yaşanmış hata).
    // AG HATASI DA BU DALA DUSMELI: api/client.js request() ag hatasinda {success:false} DONDURMEZ,
    // throw eder (yalniz AbortError yumusak payload doner) ve bu cagrida timeoutMs verilmedigi
    // icin varsayilan 0 = timeout YOK. try/catch olmadan promise reject oluyordu: setLoadError de
    // setLoading(false) de HIC calismiyor, ekran iskelette kaliyor, hata bandi cikmiyor ve konsolda
    // yalnizca "unhandled rejection" goruluyordu. Yani hata dali yazilmisti ama EN SIK tetiklenen
    // hata turu ona hic ulasmiyordu.
    try {
      const res = await api.monitoring.getPageSpeedMonitors()
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
  const { resume, isResuming } = useMonitorResume(api.monitoring.updatePageSpeedMonitor, (r) => {
    load(); setMonitors((prev) => prev.map((x) => (x.id === r.id ? { ...x, active: true } : x)))
    setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

  const checkable = monitors.filter(canCheckRow)
  const checkRun = useCheckRun({
    items: checkable,
    // Tekil yolun ta kendisi: kartın "kontrol ediliyor" göstergesi (track) ve sonucun
    // satıra işlenmesi toplu koşumda da AYNI koddan geçer — ikinci bir merge yolu yok.
    runOne: (m) => checkNow(m, { silent: true }),
    concurrency: CHECK_CONCURRENCY_BY_TYPE.pagespeed,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)

  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'pagespeed').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.pagespeed) })
  }, [])

  useMonitorDeepLink(monitors, openDetail, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'PAGESPEED',   // open=noc: 7/24 Kapsamı "7/24 ayarını düzenle"
  })

  // Kirilim istekleri YARISABILIR: modal 30 sn'de bir kendini tazeliyor ve kullanici bu sirada
  // baska bir anlik goruntuye ya da baska bir izlemeye gecebiliyor. Yanitlar gonderim sirasiyla
  // donmek zorunda degil; geciken ESKI yanit YENISININ uzerine yazarsa tablo yanlis olcumun —
  // hatta yanlis IZLEMENIN — kaynaklarini gosterir ve kullanici bunu fark edemez. Sira numarasi
  // ile yalnizca EN SON istegin yaniti ekrana yazilir.
  const resSeq = useRef(0)

  async function loadResources(id, checkId = null, silent = false) {
    const seq = ++resSeq.current
    if (!silent) setResLoading(true)
    // try/finally YOKTU: istek reject olursa setResLoading(false) hic calismiyor ve kaynak
    // tablosunun spinner'i modal kapatilip yeniden acilana kadar donuyordu. Bayrak YALNIZ bu
    // istek hala guncelse indirilir (bastirilan eski yanit yenisinin spinner'ini SONDURMESIN).
    try {
      const res = await api.monitoring.getPageSpeedResources(id, { checkId: checkId ?? undefined })
      if (seq !== resSeq.current) return        // daha yeni bir istek var → bu yaniti AT
      setResources(res?.success ? (res.data?.resources ?? []) : [])
      setResTotal(res?.success ? (res.data?.total ?? 0) : 0)
      setBreaches(res?.success ? (res.data?.breaches ?? []) : [])
    } catch {
      if (seq === resSeq.current) { setResources([]); setResTotal(0); setBreaches([]) }
    } finally {
      if (seq === resSeq.current) setResLoading(false)
    }
  }

  function openDetail(m) {
    resSeq.current++            // onceki izlemenin ucusan yaniti bu modali DOLDURMASIN
    setSelected(m); setResources([]); setResTotal(0); setBreaches([]); setResCheckId(null)
    setDetailTab(deepLinkTab()); setMetric('load')
    setSpeedDx((cur) => (cur && cur.monitorId === m?.id ? cur : null))   // derin bağlantının kayıtlı çalıştırması yalnız KENDİ izlemesinde
    loadResources(m.id)
  }
  function closeDetail() {
    resSeq.current++
    setSelected(null); setResources([]); setResTotal(0); setBreaches([]); setSpeedDx(null)
  }
  /** Tanılama penceresini aç — başlangıç ekranıyla (koşu kullanıcı "Tanılamayı başlat"a basınca). */
  function openDiagnose(m) { if (m) setSpeedDx({ monitorId: m.id, runId: null, initialRunId: null }) }

  async function refreshModal() {
    if (!selected) return
    const id = selected.id
    const res = await api.monitoring.getPageSpeedMonitors()
    if (res?.success) {
      setMonitors(res.data)
      const fresh = (res.data || []).find(x => x.id === id)
      if (fresh) setSelected(prev => (prev?.id === id ? fresh : prev))   // A'nın tazesi B'nin penceresini değiştirmesin
    }
    // Pencere o arada kapandı / başka izlemeye geçildi → A'nın kaynakları B'nin penceresine yüklenmesin
    // (loadResources'ın resSeq'i EN SON isteği kazandırır — bayat çağrı en son olmamalı).
    if (selectedIdRef.current !== id) return
    loadResources(id, resCheckIdRef.current, true)
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
      resourceConcurrency: defaults?.resourceConcurrency ?? emptyForm.resourceConcurrency })
    setModal('new')
  }

  /** Monitör (snake_case) → form. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz.
   *  Parola BİLİNÇLİ olarak taşınmaz: API onu hiç döndürmez (yalnız "kayıtlı mı" bayrağı gelir). */
  function formFrom(m) {
    return {
      name: m.name || '', url: m.url || '', groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
      teamId: m.team_id != null ? String(m.team_id) : '',
      tags: m.tags || '', notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false,
      intervalSeconds: m.interval_seconds ?? 1800, timeoutMs: m.timeout_ms ?? 10000,
      maxLoadMs: m.max_load_ms ?? '', maxTtfbMs: m.max_ttfb_ms ?? '',
      maxPageKb: m.max_page_kb ?? '', maxRequests: m.max_requests ?? '',
      userAgent: m.user_agent || '', sendDnt: !!m.send_dnt, useProxy: m.use_proxy || 'OFF',
      excludeTrackers: !!m.exclude_trackers, trackerPatterns: m.tracker_patterns || '',
      basicAuthUser: m.basic_auth_user || '', basicAuthPass: '',
      customHeaders: '', resourceConcurrency: m.resource_concurrency ?? 5,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false,
    }
  }
  function openEdit(row) {
    // Güncel satır (2026-10-09): detay kopyası bayat olabilir (liste yenilemesi / geri alma) — form ondan kurulursa kayıt
    // eski değerleri sessizce geri yazar. Listedeki satır kopyanın üstüne birleştirilir (bkz. utils/monitorDetailSync).
    const m = freshestRow(row, monitors)
    cancelTest()
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m)); setChangeNote(''); setModal(m)
  }
  function openDuplicate(m) {
    cancelTest()
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.url) })
    setModal('new')
  }
  function closeEdit() { cancelTest(); setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  /** Eşik alanı: boş dize → null (eşiği kaldır), sayı → sayı. */
  const thresholdValue = (v) => (v === '' || v == null ? null : Number(v))

  function payloadFromForm() {
    const p = {
      name: (form.name || form.url).trim(), url: normalizeUrl(form.url),
      groupName: form.groupName?.trim() || null,
      // Bos = takim varsayilani -> takim adresi (zincirin kalani).
      notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
        ? null : Number(form.notificationGroupId),
      nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
      teamId: form.teamId === '' ? null : Number(form.teamId),
      tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING', notifyWebhook: form.notifyWebhook,
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
      maxLoadMs: thresholdValue(form.maxLoadMs), maxTtfbMs: thresholdValue(form.maxTtfbMs),
      maxPageKb: thresholdValue(form.maxPageKb), maxRequests: thresholdValue(form.maxRequests),
      userAgent: form.userAgent?.trim() || null, sendDnt: form.sendDnt, useProxy: form.useProxy || 'OFF',
      excludeTrackers: form.excludeTrackers, trackerPatterns: form.trackerPatterns?.trim() || null,
      basicAuthUser: form.basicAuthUser?.trim() || null,
      resourceConcurrency: Number(form.resourceConcurrency),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
      recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      active: form.active,
    }
    // Parola YALNIZ yazıldıysa gönderilir: boş bırakmak "değiştirme" demektir, "sil" değil.
    if (form.basicAuthPass) p.basicAuthPass = form.basicAuthPass
    // Özel başlıkları yalnız admin gönderir; admin olmayanın alanı zaten çizilmez.
    if (isAdmin && form.customHeaders !== '') p.customHeaders = form.customHeaders.trim() || null
    return p
  }

  async function runTest() {
    if (!form.url.trim()) return
    const my = ++testSeq.current   // bu formun testi — form kapanır / başka forma geçilirse yanıtı yok sayılır
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testPageSpeed(payloadFromForm())
      if (my !== testSeq.current) return   // geç yanıt: başka formun (ya da kapanmış formun) sonucu DEĞİL
      setTestResult(res?.success ? res.data : { error: res?.error || t('pspd.testError') })
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
      const payload = payloadFromForm()
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createPageSpeedMonitor(payload)
        : await api.monitoring.updatePageSpeedMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      // Açık detayın kopyası da sunucu satırıyla tazelenir (2026-10-09): aynı pencereden ikinci "Düzenle" bayat kopyadan
      // kurulup ilk düzenlemeyi geri yazmasın. Yeni kayıt / başka izleme → kopyaya dokunulmaz (kimlik kapısı).
      setSelected((prev) => mergeSavedRow(prev, res.data))
      toast.success(t('pspd.saved')); closeEdit()
      // İlk / taze ölçüm (2026-09-28): yeni kart boş kalmasın, hedefi/bütçesi değişen kart eski ölçümü göstermesin. Gizli
      // parola/başlık satırda geri okunamaz → yazıldıysa ayrıca bildirilir. Sessiz: bekleme süresi (429) ya da hata
      // kayıt başarısının yanında ikinci bir hata bildirimi olmasın; kart "İlk kontrol bekleniyor"da kalır.
      if (shouldCheckAfterSave('pagespeed', { isNew: modal === 'new', before: modal, after: res.data,
        extraChanged: payload.basicAuthPass !== undefined || payload.customHeaders !== undefined })) startCheckAfterSave(checkNow, res.data, { silent: true })
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

      confirmText: t('pspd.delete'),

      cancelText: t('pspd.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deletePageSpeedMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      // Kart HEMEN düşer (işaret), açık detay kapanır; liste arka planda tazelenir — arayüz beklemez.
      markMonitorDeleted('pagespeed', m.id, res)
      setSelected((s) => (s?.id === m.id ? null : s))
      toast.success(t('pspd.deleted'))

      load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    // Kalıcı silme (2026-10-07): düzenleme penceresinden de ADIYLA ve geri alınamaz olduğu söylenerek onay alınır.
    if (!await showConfirm({ title: t('mon.deleteTitle'), message: t('mon.deleteMsg', modal.name || modal.url),
      confirmText: t('pspd.delete'), cancelText: t('pspd.cancel'), variant: 'danger' })) return
    const res = await api.monitoring.deletePageSpeedMonitor(modal.id)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    const id = modal.id
    markMonitorDeleted('pagespeed', id, res)   // anında düşer; tazeleme arka planda
    setSelected((s) => (s?.id === id ? null : s))
    toast.success(t('pspd.deleted')); closeEdit()
    load()
  }

  async function checkNow(m, { silent = false } = {}) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerPageSpeedCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        // Bayat kapanış YOK: `selected` isteğin başladığı andaki penceredir. Yanıt gelene kadar pencere kapanmış ya da
        // başka izlemeye geçilmiş olabilir → yalnız HÂLÂ açık olan aynı kayıt tazelenir (A'nın kaynakları B'ye düşmez).
        setSelected(prev => (prev?.id === m.id ? res.data : prev))
        if (selectedIdRef.current === m.id) { setResCheckId(null); loadResources(m.id) }
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      }
      // Toplu koşumda toast SUSAR: 40 monitörlük bir koşumda 40 hata bildirimi ekranı
      // gömerdi; mesaj zaten koşum tablosunun satırında duruyor.
      if (!silent) toast.error(res?.error || 'Error')
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  function exportResourcesCsv() {
    if (!resources.length) return
    const head = ['url', 'type', 'bytes', 'duration_ms', 'http_status', 'third_party', 'checked_at']
    // BOM + CRLF; gövde csvRows, indirme ortak downloadCsv (öneri 29 — dosya baytları ve adı aynı).
    downloadCsv(`pagespeed-resources-${selected?.id ?? 'x'}.csv`, '﻿' + csvRows([head, ...resources.map(r => head.map(k => r[k]))]))
  }

  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('pspd.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  // Degisiklik gecmisi teamId farkini ADA cevirebilsin — ciplak sayi okunmuyor.
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('pspd.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('pspd.noGroup') }] : [])],
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
    const c = { total: scoped.length, ok: 0, slow: 0, down: 0, alarm: 0, unacked: 0 }
    for (const m of scoped) {
      if (m.status === 'OK') c.ok++
      else if (m.status === 'SLOW') c.slow++
      else if (m.status === 'DOWN') c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      ok:      m => m.status === 'OK',
      slow:    m => m.status === 'SLOW',
      down:    m => m.status === 'DOWN',
      alarm:   m => m.active_alarm,
      unacked: m => m.active_alarm && !m.alarm_acknowledged,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  // Varsayılan kart sırası (2026-10-01): sorunlu önce → grup adı A→Z (grup içinde ad) → grupsuzlar ada göre
  const orderedMonitors = useMemo(() => sortMonitorsDefault(displayMonitors, 'pagespeed'), [displayMonitors])
  const pager = usePagination(orderedMonitors, {
    listKey: 'pagespeed-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, proxyFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  useUrlQuerySync({
    team: teamFilter === 'all' ? null : teamFilter,
    group: groupFilter === 'all' ? null : groupFilter,
    tag: tagFilter === 'all' ? null : tagFilter,
    via: proxyFilter === 'all' ? null : proxyFilter,   // vekil süzgeci (2026-09-22)
    q: search.trim() || null,
    stat: statFilter || null,
    page: pager.page > 1 ? pager.page : null,
    // ps de yazılır (standart kural, monitorUrlState ile aynı): 2. sayfada varsayılan boyutta bile — bağlantıyı
    // alan kişi aynı dilimi görsün (eskiden yalnız `page` yazılıyordu).
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'resources' ? detailTab : null,
    // Tanılama penceresinde gösterilen çalıştırma (2026-10-05) — bağlantı paylaşılınca KAYITLI sonuç açılır
    psdx: selected && speedDx?.monitorId === selected.id && canDiagnoseRow(selected) ? (speedDx.runId ?? null) : null,
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('pspd.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'ok',      Icon: CheckCircle2,    label: t('pspd.dashOk'),      value: counts.ok,      cls: 'valid'    },
    { key: 'slow',    Icon: TriangleAlert,   label: t('pspd.dashSlow'),    value: counts.slow,    cls: 'warning'  },
    { key: 'down',    Icon: ServerCrash,     label: t('pspd.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'alarm',   Icon: Siren,           label: t('pspd.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('pspd.dashUnacked'), value: counts.unacked, cls: 'error', hint: t('mondash.unackedHint') },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  // Durum sözlüğü (kart şeridi / rozet / detay kenarı): up | warn | down | unknown.
  // SLOW ayrı bir ton (warn): kesinti DEĞİL, sayfa ayakta ama hedeflenenden ağır/yavaş.
  const statusKey = (m) => (m?.status === 'OK' ? 'up' : m?.status === 'SLOW' ? 'warn' : m?.status === 'DOWN' ? 'down' : 'unknown')
  const statusLabel = (s) => s === 'OK' ? t('pspd.statusOk') : s === 'SLOW' ? t('pspd.statusSlow')
    : s === 'DOWN' ? t('pspd.statusDown') : s === 'CONFIG_ERROR' ? t('pspd.statusConfigError')
    : t('pspd.statusUnknown')
  function statusBadge(m) {
    // CONFIG_ERROR paylaşılan sözlükte yok (unknown şeridi) ama rozet kendi mor tonunu korur.
    return (
      <MonitorStatusBadge status={statusKey(m)} className={m?.status === 'CONFIG_ERROR' ? STATUS_TEXT.CONFIG_ERROR : undefined}>
        {statusLabel(m?.status)}
      </MonitorStatusBadge>
    )
  }
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('pspd.noTeam'))
    : (defaultTeamName || t('pspd.noTeam'))
  const activeMetric = METRICS.find(x => x.key === metric) ?? METRICS[0]
  // Bütçe çizgisi (2026-09-12, #15): seçili ölçütün eşiği — `budgetFor` kartın bütçe ölçerleriyle ORTAK
  // (pagespeed/pageSpeedCardModel: yük ms / TTFB ms / boyut KB→B / istek sayısı; 0 ya da boş = eşik yok, çizgi yok).

  /** Birimli sayı alanı (eski `.field-unit` soneki) — shadcn InputGroup; etiket FormField'dan. */
  const unitInput = (id, unit, props) => (
    <InputGroup>
      <InputGroupInput id={id} type="number" {...props} />
      <InputGroupAddon align="inline-end"><InputGroupText>{unit}</InputGroupText></InputGroupAddon>
    </InputGroup>
  )

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // Detay penceresi açıkken form ONUN İÇİNDE çizilir: ModalShell iç içe derinliği React ağacından okur,
  // böylece form (ve örtüsü) detay penceresinin ÜSTÜNDE katmanlanır.
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={Gauge}
      title={modal === 'new' ? t('pspd.modalNew') : t('pspd.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('pspd.testing') : null}
      footer={<>
        {/* URL boşken ölçüm yapılamaz. Buton zaten kapalı; title kapalı olma SEBEBİNİ söyler
            (sessizce tıklanmayan bir buton kullanıcıya arıza gibi görünüyor). */}
        <Button variant="secondary" className="mr-auto" onClick={runTest}
          aria-busy={testing || undefined} disabled={testing || !form.url.trim()}
          title={!form.url.trim() ? t('pspd.testNeedsUrl') : undefined}>
          <FlaskConical size={14} />{t('pspd.test')}
        </Button>
        {modal !== 'new' && canDeleteRow(modal) && <Button variant="destructive" onClick={del}><Trash2 size={14} />{t('pspd.delete')}</Button>}
        <Button variant="secondary" onClick={closeEdit}>{t('pspd.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.teamId}>{t('pspd.save')}</Button>
      </>}>
      {dupSource
        ? <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>
        : <AlertBanner tone="info" icon={Gauge}>{t('pspd.typeInfo')}</AlertBanner>}

      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField full label={t('pspd.url')} required {...fe.fieldProps('url')} hint={t('pspd.urlHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
              onChange={e => { setForm(f => ({ ...f, url: e.target.value })); fe.clear('url') }}
              onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} />
          )}
        </FormField>
        <FormField label={t('pspd.name')}>
          {({ id }) => (
            <Input id={id} value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('pspd.team')} required {...fe.fieldProps('teamId')}>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => { setForm(f => ({ ...f, teamId: v })); fe.clear('teamId') }} options={teamSelectOptions} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('pspd.noTeam')} disabled />}
        </FormField>
        <FormField full label={t('pspd.group')} required {...fe.fieldProps('groupName')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('groupName') }}
              options={[{ value: '', label: t('pspd.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('pspd.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="PAGESPEED" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />

        {/* ── Alarm eşikleri: DÖRDÜ DE opsiyonel ── */}
        <FormSection title={t('pspd.thresholdsTitle')} hint={t('pspd.thresholdsHint')}>
          <FormGrid>
            <FormField label={t('pspd.maxLoadMs')}>
              {({ id }) => unitInput(id, 'ms', { min: '0', step: '100', value: form.maxLoadMs, placeholder: t('pspd.noThreshold'),
                onChange: e => setForm(f => ({ ...f, maxLoadMs: e.target.value })) })}
            </FormField>
            <FormField label={t('pspd.maxTtfbMs')}>
              {({ id }) => unitInput(id, 'ms', { min: '0', step: '50', value: form.maxTtfbMs, placeholder: t('pspd.noThreshold'),
                onChange: e => setForm(f => ({ ...f, maxTtfbMs: e.target.value })) })}
            </FormField>
            <FormField label={t('pspd.maxPageKb')}>
              {({ id }) => unitInput(id, 'KB', { min: '0', step: '100', value: form.maxPageKb, placeholder: t('pspd.noThreshold'),
                onChange: e => setForm(f => ({ ...f, maxPageKb: e.target.value })) })}
            </FormField>
            <FormField label={t('pspd.maxRequests')}>
              {({ id }) => (
                <Input id={id} type="number" min="0" step="10" value={form.maxRequests} placeholder={t('pspd.noThreshold')}
                  onChange={e => setForm(f => ({ ...f, maxRequests: e.target.value }))} />
              )}
            </FormField>
          </FormGrid>
        </FormSection>

        {/* Etiketler */}
        <FormSection title={t('pspd.tagsTitle')} required {...fe.fieldProps('tags')}>
          <TagInput value={form.tags} onChange={v => { setForm(f => ({ ...f, tags: v })); fe.clear('tags') }} placeholder={t('pspd.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>

        {/* ── Gelişmiş ── (shadcn Collapsible; kapalıyken içerik DOM'da yok — eski koşullu çizimle aynı) */}
        <Collapsible open={advOpen} onOpenChange={setAdvOpen}
          className="min-w-0 overflow-hidden rounded-lg border bg-muted/30 sm:col-span-2">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost"
              className="h-auto w-full justify-start gap-2 rounded-none px-3.5 py-3 font-semibold hover:bg-muted/60">
              <ChevronDown size={16} aria-hidden="true"
                className={cn('text-muted-foreground transition-transform duration-200 motion-reduce:transition-none', advOpen && 'rotate-180')} />
              {t('pspd.advanced')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent className="px-3.5 pb-3.5">
            <FormGrid>
              <FormField label={t('pspd.timeoutMs')}>
                {({ id }) => unitInput(id, 'ms', { min: '1000', step: '500', value: form.timeoutMs,
                  onChange: e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) })) })}
              </FormField>
              <FormField label={t('pspd.resourceConcurrency')}>
                {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.resourceConcurrency} onChange={e => setForm(f => ({ ...f, resourceConcurrency: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('pspd.confirmAttempts')}>
                {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('pspd.confirmInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('pspd.recoveryChecks')}>
                {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('pspd.recoveryInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>

              <FormField full label={t('pspd.userAgent')} hint={t('pspd.userAgentHint')}>
                {({ id, describedBy }) => (
                  <Input id={id} aria-describedby={describedBy} value={form.userAgent} placeholder={t('pspd.userAgentPh')}
                    onChange={e => setForm(f => ({ ...f, userAgent: e.target.value }))} />
                )}
              </FormField>

              <CheckField full checked={form.sendDnt} onCheckedChange={v => setForm(f => ({ ...f, sendDnt: v }))}
                label={t('pspd.sendDnt')} hint={t('pspd.sendDntHint')} />

              {/* Kurumsal vekil (2026-09-21): Sayfa Hızı'nda varsayılan DOĞRUDAN — vekil gecikmesi ölçüme karışır; vekil-zorunlu
                  sayfalar için açılabilir. Düzenlemede kaydedilmiş etkin karar ipucu (MonitorProxyField sözleşmesi). */}
              <LabelSlot full>
                <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
                  effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />
              </LabelSlot>
              <FormHint>{t('pspd.proxyNote')}</FormHint>

              <CheckField full checked={form.excludeTrackers} onCheckedChange={v => setForm(f => ({ ...f, excludeTrackers: v }))}
                label={t('pspd.excludeTrackers')} hint={t('pspd.excludeTrackersHint')} />
              <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-background px-3.5 py-3 sm:col-span-2">
                <TagInput value={form.trackerPatterns} onChange={v => setForm(f => ({ ...f, trackerPatterns: v }))}
                  placeholder={t('pspd.trackerPatternsPh')} />
                <FormHint full={false}>{t('pspd.trackerPatternsHint')}</FormHint>
              </div>

              <FormSection title={t('pspd.authTitle')} boxed={false}>
                <FormGrid>
                  <FormField label={t('pspd.basicAuthUser')}>
                    {({ id }) => (
                      <Input id={id} value={form.basicAuthUser} autoComplete="off"
                        onChange={e => setForm(f => ({ ...f, basicAuthUser: e.target.value }))} />
                    )}
                  </FormField>
                  <FormField label={t('pspd.basicAuthPass')}>
                    {({ id }) => (
                      <Input id={id} type="password" value={form.basicAuthPass} autoComplete="new-password"
                        placeholder={modal !== 'new' && modal.has_basic_auth_pass ? '••••••••' : ''}
                        onChange={e => setForm(f => ({ ...f, basicAuthPass: e.target.value }))} />
                    )}
                  </FormField>
                </FormGrid>
                <FormHint full={false}>
                  {modal !== 'new' && modal.has_basic_auth_pass ? t('pspd.basicAuthSavedHint') : t('pspd.basicAuthHint')}
                </FormHint>
              </FormSection>

              {/* Özel başlıklar YALNIZ global admin'e çizilir: serbest başlık iç servislere
                  yetki/SSRF yüzeyi açar (PORT sendData ile aynı gerekçe). */}
              {isAdmin && (
                <FormField full label={t('pspd.customHeaders')}
                  hint={
                    /* Kardeşi KeywordMonitorPage ile aynı yedek: isim listesi boş olduğunda
                       yer tutucu boş dizeyle doldurulup cümle kırılmasın. */
                    modal !== 'new' && modal.has_custom_headers
                      ? t('pspd.customHeadersSavedHint').replace('{0}',
                          (modal.custom_header_names || []).filter(Boolean).join(', ') || t('mon.customHeadersSavedUnnamed'))
                      : t('pspd.customHeadersHint')}>
                  {({ id, describedBy }) => (
                    <Textarea id={id} aria-describedby={describedBy} rows={3} spellCheck={false} className="font-mono text-xs"
                      value={form.customHeaders} placeholder={t('pspd.customHeadersPh')}
                      onChange={e => setForm(f => ({ ...f, customHeaders: e.target.value }))} />
                  )}
                </FormField>
              )}

              <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('pspd.active')} />
              <FormHint>ⓘ {t('pspd.confirmHint')}</FormHint>
            </FormGrid>
          </CollapsibleContent>
        </Collapsible>
      </FormGrid>

      {testResult && (
        <AlertBanner className="mt-3"
          tone={testResult.error ? 'danger' : testResult.reachable ? 'success' : 'warning'}
          icon={testResult.error || !testResult.reachable ? AlertTriangle : undefined}
          title={testResult.error ? t('pspd.testError') : statusLabel(testResult.status)}>
          {testResult.error
            ? testResult.error
            : <>
                {testResult.response_ms} ms · TTFB {testResult.ttfb_ms} ms
                {' · '}{formatBytes(testResult.total_bytes, testResult.bytes_truncated)}
                {' · '}{testResult.request_count} {t('pspd.mRequests')}
                {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}
                {testResult.via && <> · {testResult.via === 'proxy' ? t('mon.proxy.effProxy') : t('mon.proxy.effDirect')}</>}
                <br /><span className="opacity-80">{t('pspd.testNoThresholds')}</span>
              </>}
        </AlertBanner>
      )}

      {/* ── Önerilen eşikler ──────────────────────────────────────────────────────
          Ham ölçüme bakıp dört alana elle sayı yazmak, hangi metriğin ne kadar
          oynadığını bilmeyi gerektiriyor. Öneri KURALIYLA BİRLİKTE gösterilir ve tek
          tıkla forma yazılır; kullanıcı sonra istediğini değiştirebilir (kaydedilmiş
          bir şey değil, yalnız form). */}
      {testResult && !testResult.error && testResult.reachable && (() => {
        const sug = suggestThresholds(testResult)
        const partial = suggestionIsPartial(testResult)
        const apply = () => {
          setForm(f => ({ ...f,
            maxLoadMs: sug.maxLoadMs ?? '', maxTtfbMs: sug.maxTtfbMs ?? '',
            maxPageKb: sug.maxPageKb ?? '', maxRequests: sug.maxRequests ?? '' }))
          toast.success(t('pspd.suggestApplied'))
        }
        const rows = [
          [t('pspd.maxLoadMs'), `${sug.maxLoadMs ?? '—'} ms`, t('pspd.suggestWhyLoad')],
          [t('pspd.maxTtfbMs'), `${sug.maxTtfbMs ?? '—'} ms`, t('pspd.suggestWhyTtfb')],
          [t('pspd.maxPageKb'), `${sug.maxPageKb ?? '—'} KB`, t('pspd.suggestWhySize')],
          [t('pspd.maxRequests'), sug.maxRequests ?? '—', t('pspd.suggestWhyRequests')],
        ]
        return (
          <Card data-slot="suggested-thresholds" className="mb-3.5 gap-2 rounded-lg bg-muted/30 px-3.5 py-3 shadow-none">
            <CardHeader className="px-0">
              <CardTitle className="text-[.88em] font-bold">{t('pspd.suggestTitle')}</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-2.5 px-0">
              <ul className="m-0 list-none p-0">
                {rows.map(([k, v, why]) => (
                  <li key={k} className="grid grid-cols-[minmax(0,1fr)_auto] items-baseline gap-x-3 border-t py-1 text-[.85em] first:border-t-0">
                    <span className="text-muted-foreground">{k}</span>
                    <strong className="whitespace-nowrap tabular-nums">{v}</strong>
                    {/* Gerekçe tam genişlikte alt satıra düşer — dar modalde sayıyı sıkıştırmasın. */}
                    <em className="col-span-full text-[.92em] text-muted-foreground not-italic">{why}</em>
                  </li>
                ))}
              </ul>
              {partial && <FormHint full={false} tone="warn">{t('pspd.suggestPartial')}</FormHint>}
              <Button type="button" size="sm" className="self-start" onClick={apply}>
                <Wand2 size={14} />{t('pspd.suggestApply')}
              </Button>
              <FormHint full={false}>{t('pspd.suggestNote')}</FormHint>
            </CardContent>
          </Card>
        )
      })()}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="pagespeed-change-note" value={changeNote} onChange={setChangeNote} />
      )}
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader type="pagespeed" title={t('pspd.title')} subtitle={t('pspd.subtitle')}
        count={loading ? null : monitors.length} down={counts.down}
        refreshEvery={REFRESH_INTERVAL} refreshResetKey={loadNonce} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('pspd.addMonitor')} />

      <MonitorHowBox bullets={[t('pspd.how1'), t('pspd.how2'), t('pspd.how3'), t('pspd.how4'),
        t('pspd.how5'), t('pspd.how6'), t('pspd.how7')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {/* Kart görünümü seçicisi araç çubuğunun İLK öğesi (mr-auto → süzgeçler ve arama sağda kalır; telefonda satır sarar) */}
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          <SearchableSelect value={proxyFilter} onChange={setProxyFilter} options={proxyFilterOptions} ariaLabel={t('mon.proxy.label')} />
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('pspd.searchPlaceholder')} aria-label={t('pspd.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} data-page-search="" />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('pspd.noMonitorsAdmin') : t('pspd.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="PAGESPEED"
          api={{ update: api.monitoring.updatePageSpeedMonitor, remove: api.monitoring.deletePageSpeedMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22; vekil süzgeciyle görünür oldu) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Kart: pagespeed/PageSpeedMonitorCard (shadcn Card + "stretched button" + bütçe ölçerleri). Yetki
               kapıları ve olay işleyicileri SAYFADA kalır: seçim kutusu, meta ve eylemler kart yuvalarına geçer. */
            <PageSpeedMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} status={statusKey(m)} badge={statusBadge(m)} density={density} running={isRunning(m.id)}
              onOpen={() => openDetail(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              week7={week7.data[String(m.id)]} week14={week14.data[String(m.id)]}
              selection={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.url)} />
              )}
              meta={<MonitorCardMeta monitor={m} />}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={m.url}
                  running={isRunning(m.id)}
                  onCheck={canCheckRow(m) ? () => checkNow(m) : undefined} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('pspd.check')} editTitle={t('pspd.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('pspd.delete')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={statusKey(selected)} badge={statusBadge(selected)} title={selected.url} noc={{ type: 'PAGESPEED', monitor: selected, canEdit: canManageRow(selected) }}
          actions={
            /* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
               koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
               kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
              running={isRunning(selected.id)}
              onCheck={canCheckRow(selected) ? () => checkNow(selected) : undefined}
              checkTitle={t('pspd.check')}
              onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
              editTitle={t('pspd.edit')}
              onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
              onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
              deleting={deleting === selected.id}
              deleteTitle={t('pspd.delete')}
              onClose={closeDetail}>
              {/* Uçtan uca tanılama (2026-10-05) — yalnız `can_diagnose` satırında; paylaşılan eylem grubuna çocuk olarak */}
              {canDiagnoseRow(selected) && (
                <Button type="button" variant="outline" size="icon-sm" data-slot="psdx-open"
                  className={cn(MON_ACT, 'pointer-coarse:size-10', MON_ACT_TONE.edit)}
                  onClick={() => openDiagnose(selected)} title={t('psdx.open')} aria-label={t('psdx.open')}>
                  <Stethoscope size={13} aria-hidden="true" />
                </Button>
              )}
              <CopyLinkButton iconOnly variant="outline" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-0" />
          <DetailSummary items={[
            { key: 'status', value: statusLabel(selected.status), label: t('pspd.lastStatus'), valueClassName: statusText(selected.status) },
            { key: 'load', value: selected.response_ms != null ? `${selected.response_ms} ms` : '—', label: t('pspd.mLoad') },
            { key: 'ttfb', value: selected.ttfb_ms != null ? `${selected.ttfb_ms} ms` : '—', label: t('pspd.mTtfb') },
            { key: 'size', value: formatBytes(selected.total_bytes, selected.bytes_truncated), label: t('pspd.mSize'),
              hint: selected.bytes_truncated ? t('pspd.truncatedHint') : undefined },
            { key: 'req', value: selected.request_count ?? '—', label: t('pspd.mRequests') },
            selected.last_check && { key: 'last', value: formatDateSec(selected.last_check), label: t('pspd.lastCheck'), time: true },
          ]} />
          {selected.error && (
            <AlertBanner tone="danger" role="alert" icon={AlertTriangle} className="mt-3.5">{selected.error}</AlertBanner>
          )}
          {selected.capped && (
            <AlertBanner tone="warning" className="mt-3.5">{t('pspd.cappedWarn')}</AlertBanner>
          )}
          {selected.bytes_truncated && (
            <AlertBanner tone="warning" className="mt-3.5">{t('pspd.truncatedWarn')}</AlertBanner>
          )}
          <DetailDivider />
          <DetailTabs value={detailTab} onValueChange={setDetailTab} className="mt-0"
            countsFor={{ kind: 'pagespeed', monitorId: selected.id, notesType: 'PAGESPEED', notesTarget: selected.url, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['resources', t('pspd.tabResources')], ['chart', t('pspd.tabChart')], ['control', t('hist.tab')],
              ['alerts', t('pspd.tabAlerts')], ['notes', t('pspd.tabNotes')],
              // Yapılandırma geçmişi — kontrol geçmişiyle KARIŞTIRILMAMALI:
              // orası "sayfa ne kadar sürdü", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            <TabsContent value="resources">
              {/* Ihlal anlari EskiDEN her biri ayri bir dugmeydi; birikince (20'ye kadar) tablonun
                  ustunu iki-uc sira doldurup paneli kullanilmaz hale getiriyordu. Tek bir secici
                  hem sabit yer kaplar hem de tarihleri okunur birakir. Ihlal yoksa secici HIC
                  cizilmez: secilecek bir sey olmadiginda kontrol gostermek bos gurultudur. */}
              <div className="mb-2 flex flex-wrap items-center gap-2.5">
                {breaches.length > 0 && (<>
                  <span className={SECTION_LABEL}>{t('pspd.snapshotLabel')}</span>
                  <SearchableSelect
                    value={resCheckId == null ? '' : String(resCheckId)}
                    onChange={(v) => {
                      const id = v === '' ? null : Number(v)
                      setResCheckId(id)
                      loadResources(selected.id, id)
                    }}
                    options={[
                      { value: '', label: t('pspd.resLatest') },
                      ...breaches.map(b => ({ value: String(b.check_id), label: formatDateSec(b.checked_at) })),
                    ]}
                    searchThreshold={8} ariaLabel={t('pspd.snapshotLabel')} />
                  <span className="text-xs text-muted-foreground">{t('pspd.breachCount', breaches.length)}</span>
                </>)}
                <Button type="button" variant="secondary" size="sm" className="ml-auto"
                  disabled={!resources.length} onClick={exportResourcesCsv}><Download size={12} />{t('pspd.exportCsv')}</Button>
              </div>
              {/* Sunucu en agir N kaynagi dondurur. Kirpildiysa bunu SOYLEMEK zorunlu: yoksa
                  kullanici 50 satiri sayfanin tamami sanip agirligin nereden geldigini yanlis okur. */}
              <p className="mb-2 text-xs text-muted-foreground">
                {t('pspd.resHint')}
                {resTotal > resources.length && resources.length > 0
                  && ` ${t('pspd.resTruncated', resources.length, resTotal)}`}
                {/* Kirilimin KENDI zamani. Sayfa alinamadiginda son iyi kirilim KORUNUYOR (silmek,
                    kullanici tam da "bozulmadan once neye benziyordu" diye baktigi anda tabloyu
                    bosaltiyordu) — o yuzden "Son olcum" etiketi tek basina yaniltabilir. */}
                {resCheckId == null && resources[0]?.checked_at
                  && ` ${t('pspd.resMeasuredAt', formatDateSec(resources[0].checked_at))}`}
              </p>
              {resLoading ? <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
                : resources.length === 0 ? <StatusBlock tone="neutral" icon={Inbox} title={t('pspd.noResources')} className="py-6" />
                : (
                /* shadcn Table. Telefonda düşük öncelikli sütunlar (süre / HTTP / taraf) gizlenir, tablo
                   yatay taşmaz; masaüstünde liste kendi içinde kayar (telefonda iç içe kaydırma yok —
                   pencere gövdesi zaten kayıyor). URL hücresi KIRPILIR (`max-w-0 w-full` + truncate):
                   kaynak URL'leri rutin olarak 150+ karakter ve tek parça, kırpılmazsa tablo pencereden taşar. */
                <div data-slot="resource-table" className="rounded-md border sm:max-h-[420px] sm:overflow-y-auto">
                  <Table className="text-xs">
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('pspd.colType')}</TableHead>
                        <TableHead className="w-full">{t('pspd.colResource')}</TableHead>
                        <TableHead className="text-right">{t('pspd.colBytes')}</TableHead>
                        <TableHead className="hidden text-right sm:table-cell">{t('pspd.colDuration')}</TableHead>
                        <TableHead className="hidden text-right sm:table-cell">HTTP</TableHead>
                        <TableHead className="hidden md:table-cell">{t('pspd.colParty')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {resources.map((r, i) => {
                        const RI = RES_ICON[r.type] || Link2
                        return (
                          <TableRow key={`${r.url}#${i}`}>
                            <TableCell><span className="inline-flex items-center gap-1"><RI size={13} aria-hidden="true" />{r.type}</span></TableCell>
                            <TableCell className="w-full max-w-0"><span className="block truncate" title={r.url}>{r.url}</span></TableCell>
                            <TableCell className="text-right tabular-nums" title={r.truncated ? t('pspd.truncatedHint') : undefined}>
                              {formatBytes(r.bytes, r.truncated)}</TableCell>
                            <TableCell className="hidden text-right tabular-nums sm:table-cell">{r.duration_ms != null ? `${r.duration_ms} ms` : '—'}</TableCell>
                            <TableCell className={cn('hidden text-right tabular-nums sm:table-cell', r.http_status != null && r.http_status >= 400 && 'text-destructive')}>
                              {r.http_status ?? '—'}</TableCell>
                            <TableCell className="hidden md:table-cell">{r.third_party ? t('pspd.thirdParty') : t('pspd.firstParty')}</TableCell>
                          </TableRow>
                        )
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
            </TabsContent>

            <TabsContent value="chart">
              {/* Metrik seçimi ile ZAMAN ARALIĞI seçimi iki ayrı şeydir. Eskiden ikisi de aynı
                  görünen etiketsiz düğme sırasıydı ve hangisinin ne yaptığı anlaşılmıyordu.
                  Metrik artık etiketli bir segmented control; aralık düğmeleri grafiğin kendi
                  satırında kalıyor ve iki satır görsel olarak ayrışıyor. */}
              <div className="mb-2.5 flex flex-wrap items-center gap-2.5 border-b pb-2.5">
                <span className={SECTION_LABEL}>{t('pspd.metricLabel')}</span>
                <SegmentedControl
                  ariaLabel={t('pspd.metricLabel')}
                  value={metric} onChange={setMetric}
                  options={METRICS.map(mt => ({ value: mt.key, label: t(mt.labelKey) }))} className="max-w-full flex-wrap pointer-coarse:[&>[data-slot=toggle-group-item]]:h-10" />
              </div>
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="pagespeed"
                  metric={activeMetric.key} unit={activeMetric.unit}
                  budget={budgetFor(selected, activeMetric.key)} budgetLabel={t('pspd.budgetLine')} />
              </Suspense>
            </TabsContent>

            <TabsContent value="control">
              {/* columns + renderRow ZORUNLU: CheckHistoryTab satirlari cizmeyi cagirana birakir.
                  Gecilmezse map icinde "renderRow is not a function" ile sekme comple coker. */}
              <CheckHistoryTab kind="pagespeed" monitorId={selected.id} listKey="pagespeed-history" reloadSignal={histReload}
                defaultPreset={7} gridClass="pspd-rt-grid"
                columns={[t('pspd.colTime'), t('pspd.colStatus'), t('pspd.mLoad'),
                          t('pspd.mTtfb'), t('pspd.mSize'), t('pspd.mRequests')]}
                renderRow={(c) => {
                  // Eşik aşımı KESİNTİ DEĞİL: ok=true kalır, ihlal ayrı kolonda gelir.
                  const breached = typeof c.breached_metrics === 'string' && c.breached_metrics
                  // Hata teşhisi (2026-10-05): DOWN ile CONFIG_ERROR ayrışır (sunucu nedeni); başarısız satırın KENDİ hata
                  // satırı (altında tam genişlik): neden rozeti + tek satır + aç/kapa, açılınca panel (hata metni, HTTP kodu,
                  // faz süreleri). Özet ara sütuna konmaz — tabloyu genişletip kabından taşırırdı (Sayfa Bütünlüğü ile aynı).
                  const failed = c.ok === false
                  const st = failed ? (c.failure_reason === 'CONFIG_ERROR' ? 'CONFIG_ERROR' : 'DOWN') : breached ? 'SLOW' : 'OK'
                  const k = failureRowKey(c)
                  const when = formatDateSec(c.checked_at)
                  return (<>
                    <span className="upt-rt-time">{when}</span>
                    <span className={cn('font-semibold', statusText(st))}>
                      {statusLabel(st)}
                      {/* HANGİ eşik, kaçtı, kaç ölçüldü. Yalnız "Eşik aşıldı" demek kullanıcıyı
                          sebebi aramaya gönderiyordu — veri zaten kayıtlıydı, gösterilmiyordu. */}
                      {breached && <BreachEvidence metrics={c.breached_metrics} detail={c.breach_detail} t={t} />}
                    </span>
                    <span className="upt-rt-ms">{c.response_ms != null ? `${c.response_ms} ms` : '—'}</span>
                    <span className="upt-rt-ms">{c.ttfb_ms != null ? `${c.ttfb_ms} ms` : '—'}</span>
                    <span className="upt-rt-ms">{formatBytes(c.total_bytes, c.bytes_truncated)}</span>
                    <span className="upt-rt-ms">{c.request_count ?? '—'}</span>
                    {failed && (
                      <CheckFailureBlock type="pagespeed" check={c} monitor={selected} open={failRows.isOpen(k)} when={when}
                        panelId={failurePanelId('pagespeed', k)} onToggle={() => failRows.toggle(k)}
                        canDiagnose={canDiagnoseRow(selected)} onDiagnose={() => openDiagnose(selected)} />
                    )}
                  </>)
                }} />
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.url} types={alertTypesFor('pagespeed')} /></TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PAGESPEED" target={selected.url} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                {/* Prop adlari ChangeHistoryTab imzasiyla BIREBIR: t / kind / monitorId /
                    teamNames / canManage. Yanlis adla gecmek sessiz degil — t cagrilinca
                    "t is not a function" ile sekme comple cokuyor. */}
                <ChangeHistoryTab t={t} kind="pagespeed" monitorId={selected.id}
                  teamNames={teamNameById} canManage={canManageRow(selected)}
                  // Geri alma sonrası liste + açık detay kopyası tazelenir (2026-10-09) — sonraki "Düzenle" geri alınanı ezmesin
                  onRestored={() => reloadAndSyncDetail(load, selected.id, setSelected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

          {/* Uçtan uca tanılama penceresi (2026-10-05) — detayın İÇİNDE: iç içe kabuk, Escape yalnız onu kapatır */}
          {speedDx && speedDx.monitorId === selected.id && canDiagnoseRow(selected) && (
            <Suspense fallback={null}>
              <PageSpeedDiagnoseDialog monitor={selected} initialRunId={speedDx.initialRunId}
                onRunChange={(runId) => setSpeedDx((cur) => (cur ? { ...cur, runId } : cur))}
                onClose={() => setSpeedDx(null)} />
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
          storageKey="sm.checkRun.teams.pagespeed"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="pagespeed"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
