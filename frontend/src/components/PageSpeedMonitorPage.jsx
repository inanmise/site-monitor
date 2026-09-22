import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { CheckRunningStrip } from './ui/CheckRunning.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import CheckAllButton from './check/CheckAllButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import MonitorProxyField from './ui/MonitorProxyField.jsx'
import { monitorDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import TagInput from './ui/TagInput.jsx'
import { RefreshCw, Plus, Trash2, Gauge, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ChevronDown, Image, FileCode, Frame, Type, Download, Link2, Wand2, Inbox } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesProxy } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { csvCell } from '../utils/csv.js'
import { formatBytes } from '../utils/formatBytes.js'
import { suggestThresholds, suggestionIsPartial } from '../utils/pageSpeedThresholds.js'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

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
/** Kaynak tablosu kolonları: Tür | Kaynak | Boyut | Süre | HTTP | Taraf. */
const RES_COLS = '0.7fr 3fr 0.7fr 0.7fr 0.5fr 0.7fr'

/** Grafik metrikleri — sunucu `?metric=` ile TEK seriyi projekte eder (yeni tarama üretmez). */
/** Eşik üstü kontrol sayısı bu hafta / geçen hafta — sayfa hızında "fail" = eşik aşımı ya da hata (MonitorSparklineService). */
function BreachWeekLine({ w7, w14, t }) {
  if (!w7 || !w14 || !w14.n) return null
  const thisWeek = w7.fail ?? 0
  const lastWeek = Math.max(0, (w14.fail ?? 0) - thisWeek)
  const trend = thisWeek > lastWeek ? '↑' : thisWeek < lastWeek ? '↓' : '='
  return (
    <div className={`pspd-breach-week${thisWeek > lastWeek ? ' is-worse' : thisWeek < lastWeek ? ' is-better' : ''}`} title={t('pspd.breachWeekTip')}>
      {t('pspd.breachWeek', thisWeek, lastWeek)} <b>{trend}</b>
    </div>
  )
}

const METRICS = [
  { key: 'load',     labelKey: 'pspd.metricLoad',     unit: 'ms' },
  { key: 'ttfb',     labelKey: 'pspd.metricTtfb',     unit: 'ms' },
  { key: 'size',     labelKey: 'pspd.metricSize',     unit: 'B'  },
  { key: 'requests', labelKey: 'pspd.metricRequests', unit: ''   },
]

const emptyForm = {
  name: '', url: '', groupName: '', notificationGroupId: '', teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
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
    <span className="pspd-breach-why">
      {rows.map(r => (
        <span key={r.key} className="pspd-breach-chip">
          {label(r.key)}
          {r.threshold != null && (
            <span className="pspd-breach-nums">{r.threshold} → <strong>{r.measured}</strong></span>
          )}
        </span>
      ))}
    </span>
  )
}

export default function PageSpeedMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'
  const myTeam = teamId != null ? String(teamId) : null
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId })
  const canManageRow = (m) => isAdmin || isOwnTeam(m)
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  const canCheckRow = canManageRow
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })


  const sparks = useSparklines('pagespeed')   // kart mini trendi (2026-09-12)
  const sla = useSla('pagespeed')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const week7 = useSla('pagespeed', 7)     // eşik üstü: bu hafta (2026-09-12, #15)
  const week14 = useSla('pagespeed', 14)   // eşik üstü: son 14 gün → geçen hafta = 14g − 7g
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  useEscapeKey(!!selected, closeDetail)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [resources, setResources] = useState([])
  const [resTotal, setResTotal] = useState(0)   // listedeki değil, KIRILIMDAKİ toplam kaynak sayısı
  const [breaches, setBreaches] = useState([])
  const [resCheckId, setResCheckId] = useState(null)   // null = son ölçüm (LATEST)
  const [resLoading, setResLoading] = useState(false)
  const [modal, setModal] = useState(null)
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
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
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('resources')
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
  const [secondsSince, setSecondsSince] = useState(0)

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
      if (res?.success) { setMonitors(res.data); setLoadError(null) }
      else setLoadError(res?.error || 'load failed')
    } catch (e) {
      setLoadError(e?.message || 'network error')
    } finally {
      setLoading(false); setSecondsSince(0)
    }
  }, [])

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
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)

  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'pagespeed').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.pagespeed) })
  }, [])

  useMonitorDeepLink(monitors, openDetail)

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
    setDetailTab('resources'); setMetric('load')
    loadResources(m.id)
  }
  function closeDetail() {
    resSeq.current++
    setSelected(null); setResources([]); setResTotal(0); setBreaches([])
  }

  async function refreshModal() {
    if (!selected) return
    const res = await api.monitoring.getPageSpeedMonitors()
    if (res?.success) {
      setMonitors(res.data)
      const fresh = (res.data || []).find(x => x.id === selected.id)
      if (fresh) setSelected(fresh)
    }
    loadResources(selected.id, resCheckId, true)
  }
  useVisibleInterval(() => { if (selected) refreshModal() }, selected ? 30000 : 0, false)

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      resourceConcurrency: defaults?.resourceConcurrency ?? emptyForm.resourceConcurrency })
    setModal('new')
  }

  /** Monitör (snake_case) → form. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz.
   *  Parola BİLİNÇLİ olarak taşınmaz: API onu hiç döndürmez (yalnız "kayıtlı mı" bayrağı gelir). */
  function formFrom(m) {
    return {
      name: m.name || '', url: m.url || '', groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
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
  function openEdit(m) {
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m)); setChangeNote(''); setModal(m)
  }
  function openDuplicate(m) {
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.url) })
    setModal('new')
  }
  function closeEdit() { setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  /** Eşik alanı: boş dize → null (eşiği kaldır), sayı → sayı. */
  const thresholdValue = (v) => (v === '' || v == null ? null : Number(v))

  function payloadFromForm() {
    const p = {
      name: (form.name || form.url).trim(), url: normalizeUrl(form.url),
      groupName: form.groupName?.trim() || null,
      // Bos = takim varsayilani -> takim adresi (zincirin kalani).
      notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
        ? null : Number(form.notificationGroupId),
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
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testPageSpeed(payloadFromForm())
      setTestResult(res?.success ? res.data : { error: res?.error || t('pspd.testError') })
    } finally {
      setTesting(false)
    }
  }

  async function save() {
    if (!form.url.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName?.trim()) { toast.error(t('mon.groupRequired')); return }   // grup + etiket zorunlu (2026-09-18)
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }
    setSaving(true)
    try {
      const payload = payloadFromForm()
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createPageSpeedMonitor(payload)
        : await api.monitoring.updatePageSpeedMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      toast.success(t('pspd.saved')); closeEdit()
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

      toast.success(t('pspd.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deletePageSpeedMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('pspd.deleted')); closeEdit()
  }

  async function checkNow(m, { silent = false } = {}) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerPageSpeedCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        if (selected?.id === m.id) { setSelected(res.data); setResCheckId(null); loadResources(m.id) }
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
    const body = resources.map(r => head.map(k => csvCell(r[k])).join(',')).join('\r\n')
    const blob = new Blob(['﻿' + head.map(csvCell).join(',') + '\r\n' + body],
      { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `pagespeed-resources-${selected?.id ?? 'x'}.csv`
    a.click(); URL.revokeObjectURL(a.href)
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

  const pager = usePagination(displayMonitors, {
    listKey: 'pagespeed-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, proxyFilter, statFilter],
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
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'resources' ? detailTab : null,
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

  // SLOW ayrı bir renk: kesinti DEĞİL, sayfa ayakta ama hedeflenenden ağır/yavaş.
  const STATUS_COLOR = { OK: '#15803d', SLOW: '#e07b00', DOWN: '#c0392b', CONFIG_ERROR: '#7c3aed', unknown: '#64748b' }
  function cardClass(m) {
    if (m.status === 'OK') return 'upt-card--up'
    if (m.status === 'DOWN') return 'upt-card--down'
    return 'upt-card--unknown'
  }
  const statusLabel = (s) => s === 'OK' ? t('pspd.statusOk') : s === 'SLOW' ? t('pspd.statusSlow')
    : s === 'DOWN' ? t('pspd.statusDown') : s === 'CONFIG_ERROR' ? t('pspd.statusConfigError')
    : t('pspd.statusUnknown')
  function statusBadge(m) {
    const c = STATUS_COLOR[m?.status] || STATUS_COLOR.unknown
    return <span className="upt-badge" style={{ color: c }}>
      <span className="upt-badge-dot" style={{ background: c }} />{statusLabel(m?.status)}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('pspd.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }
  /** Aşılan eşik anahtarlarını okunur rozete çevirir. */
  const breachLabel = (k) => t(`pspd.breach_${String(k).toLowerCase()}`)

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('pspd.noTeam'))
    : (teamName || t('pspd.noTeam'))
  const activeMetric = METRICS.find(x => x.key === metric) ?? METRICS[0]
  // Bütçe çizgisi (2026-09-12, #15): seçili ölçütün eşiği (yük ms / TTFB ms / boyut KB→B / istek sayısı)
  const budgetFor = (m, key) => key === 'load' ? m.max_load_ms : key === 'ttfb' ? m.max_ttfb_ms : key === 'size' ? (m.max_page_kb ? m.max_page_kb * 1024 : null) : key === 'requests' ? m.max_requests : null

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('pspd.title')}</h2>
          <p className="upt-subtitle">{t('pspd.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('pspd.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('pspd.refresh')}
          </button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="pagespeed" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('pspd.addMonitor')}
            </button>
          )}
        </div>
      </div>

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
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} />}
          <SearchableSelect value={proxyFilter} onChange={setProxyFilter} options={proxyFilterOptions} ariaLabel={t('mon.proxy.label')} />
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('pspd.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('pspd.noMonitorsAdmin') : t('pspd.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updatePageSpeedMonitor, remove: api.monitoring.deletePageSpeedMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22; vekil süzgeciyle görünür oldu) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {canManageRow(m) && (
                  <input type="checkbox" className="upt-card-check" checked={bulkSel.has(m.id)} onChange={() => toggleBulk(m.id)} onClick={(e) => e.stopPropagation()} aria-label={t('bulk.selectOne')} />
                )}
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.url} />
                <span className="upt-card-top-right">
                  <CopyLinkButton iconOnly url={monitorDeepLink('pagespeed', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.url}>{m.url}</div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              {/* Eşik üstü karşılaştırması (2026-09-12, #15): bu hafta / geçen hafta (14 gün − 7 gün) */}
              <BreachWeekLine w7={week7.data[String(m.id)]} w14={week14.data[String(m.id)]} t={t} />
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.response_ms != null ? `${m.response_ms} ms` : '—'}</span>
                  <span className="upt-metric-lbl">{t('pspd.mLoad')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.ttfb_ms != null ? `${m.ttfb_ms} ms` : '—'}</span>
                  <span className="upt-metric-lbl">{t('pspd.mTtfb')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val" title={m.bytes_truncated ? t('pspd.truncatedHint') : undefined}>
                    {formatBytes(m.total_bytes, m.bytes_truncated)}</span>
                  <span className="upt-metric-lbl">{t('pspd.mSize')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.request_count ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('pspd.mRequests')}</span>
                </div>
              </div>
              {Array.isArray(m.breached_metrics) && m.breached_metrics.length > 0 && (
                /* `tag-chips` projenin mevcut cip-satiri primitifi (flex + wrap + gap).
                   Onceki `upt-card-tags` HICBIR YERDE tanimli DEGILDI: birden fazla ihlal
                   rozeti bosluksuz, sarmasiz yan yana diziliyordu. */
                <div className="tag-chips">
                  {m.breached_metrics.map(k => (
                    <span key={k} className="upt-port-tag pspd-breach-tag">{breachLabel(k)}</span>
                  ))}
                </div>
              )}
              <div className="upt-card-foot">
                <span>{m.last_check ? formatDateSec(m.last_check) : ''}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('pspd.check')} editTitle={t('pspd.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('pspd.delete')} />
                )}
              </div>
            </div>
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay modali ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeDetail}>
          <div className={`upt-modal upt-modal--${selected.status === 'OK' ? 'up' : selected.status === 'DOWN' ? 'down' : 'unknown'}`}
            onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.url}</span>
              </div>
              {/* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
                  koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
                  kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */}
              <MonitorModalActions
                running={isRunning(selected.id)}
                onCheck={canManageRow(selected) ? () => checkNow(selected) : undefined}
                checkTitle={t('pspd.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('pspd.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('pspd.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val" style={{ color: STATUS_COLOR[selected.status] }}>{statusLabel(selected.status)}</span>
                <span className="upt-modal-metric-lbl">{t('pspd.lastStatus')}</span></div>
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">{selected.response_ms != null ? `${selected.response_ms} ms` : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('pspd.mLoad')}</span></div>
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">{selected.ttfb_ms != null ? `${selected.ttfb_ms} ms` : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('pspd.mTtfb')}</span></div>
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val" title={selected.bytes_truncated ? t('pspd.truncatedHint') : undefined}>
                  {formatBytes(selected.total_bytes, selected.bytes_truncated)}</span>
                <span className="upt-modal-metric-lbl">{t('pspd.mSize')}</span></div>
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">{selected.request_count ?? '—'}</span>
                <span className="upt-modal-metric-lbl">{t('pspd.mRequests')}</span></div>
              {selected.last_check && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.last_check)}</span>
                  <span className="upt-modal-metric-lbl">{t('pspd.lastCheck')}</span></div>
              )}
            </div>
            {selected.error && (
              <div className="page-confirm-banner" role="alert">
                <AlertTriangle size={13} />{selected.error}
              </div>
            )}
            {selected.capped && (
              <div className="page-confirm-banner">
                <AlertTriangle size={13} />{t('pspd.cappedWarn')}
              </div>
            )}
            {selected.bytes_truncated && (
              <div className="page-confirm-banner">
                <AlertTriangle size={13} />{t('pspd.truncatedWarn')}
              </div>
            )}
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'resources' ? ' active' : ''}`} onClick={() => setDetailTab('resources')}>{t('pspd.tabResources')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('pspd.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('pspd.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('pspd.tabNotes')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle KARIŞTIRILMAMALI:
                  orası "sayfa ne kadar sürdü", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'resources' && (<>
              {/* Ihlal anlari EskiDEN her biri ayri bir dugmeydi; birikince (20'ye kadar) tablonun
                  ustunu iki-uc sira doldurup paneli kullanilmaz hale getiriyordu. Tek bir secici
                  hem sabit yer kaplar hem de tarihleri okunur birakir. Ihlal yoksa secici HIC
                  cizilmez: secilecek bir sey olmadiginda kontrol gostermek bos gurultudur. */}
              <div className="pspd-snapshot-row">
                {breaches.length > 0 && (<>
                  <span className="pspd-metric-label">{t('pspd.snapshotLabel')}</span>
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
                    searchThreshold={8} />
                  <span className="field-hint pspd-snapshot-count">
                    {t('pspd.breachCount', breaches.length)}</span>
                </>)}
                <button type="button" className="btn btn-sm btn-secondary pspd-snapshot-csv"
                  disabled={!resources.length} onClick={exportResourcesCsv}><Download size={12} />{t('pspd.exportCsv')}</button>
              </div>
              {/* Sunucu en agir N kaynagi dondurur. Kirpildiysa bunu SOYLEMEK zorunlu: yoksa
                  kullanici 50 satiri sayfanin tamami sanip agirligin nereden geldigini yanlis okur. */}
              <div className="field-hint" style={{ margin: '0 0 8px' }}>
                {t('pspd.resHint')}
                {resTotal > resources.length && resources.length > 0
                  && ` ${t('pspd.resTruncated', resources.length, resTotal)}`}
                {/* Kirilimin KENDI zamani. Sayfa alinamadiginda son iyi kirilim KORUNUYOR (silmek,
                    kullanici tam da "bozulmadan once neye benziyordu" diye baktigi anda tabloyu
                    bosaltiyordu) — o yuzden "Son olcum" etiketi tek basina yaniltabilir. */}
                {resCheckId == null && resources[0]?.checked_at
                  && ` ${t('pspd.resMeasuredAt', formatDateSec(resources[0].checked_at))}`}
              </div>
              {resLoading ? <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
                : resources.length === 0 ? <LoadingBlock label={t('pspd.noResources')} className="upt-modal-loading" />
                : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head" style={{ gridTemplateColumns: RES_COLS }}>
                    <span>{t('pspd.colType')}</span><span>{t('pspd.colResource')}</span>
                    <span>{t('pspd.colBytes')}</span><span>{t('pspd.colDuration')}</span>
                    <span>HTTP</span><span>{t('pspd.colParty')}</span>
                  </div>
                  {resources.map((r, i) => {
                    const RI = RES_ICON[r.type] || Link2
                    return (
                      <div key={`${r.url}#${i}`} className="upt-rt-grid" style={{ gridTemplateColumns: RES_COLS }}>
                        <span><RI size={13} /> {r.type}</span>
                        <span className="upt-rt-url" title={r.url}>{r.url}</span>
                        <span title={r.truncated ? t('pspd.truncatedHint') : undefined}>
                          {formatBytes(r.bytes, r.truncated)}</span>
                        <span>{r.duration_ms != null ? `${r.duration_ms} ms` : '—'}</span>
                        <span style={{ color: r.http_status != null && r.http_status >= 400 ? '#b91c1c' : undefined }}>
                          {r.http_status ?? '—'}</span>
                        <span>{r.third_party ? t('pspd.thirdParty') : t('pspd.firstParty')}</span>
                      </div>
                    )
                  })}
                </div>
              )}
            </>)}

            {detailTab === 'chart' && (<>
              {/* Metrik seçimi ile ZAMAN ARALIĞI seçimi iki ayrı şeydir. Eskiden ikisi de aynı
                  görünen etiketsiz düğme sırasıydı ve hangisinin ne yaptığı anlaşılmıyordu.
                  Metrik artık etiketli bir segmented control; aralık düğmeleri grafiğin kendi
                  satırında kalıyor ve iki satır görsel olarak ayrışıyor. */}
              <div className="pspd-metric-row">
                <span className="pspd-metric-label">{t('pspd.metricLabel')}</span>
                <SegmentedControl
                  ariaLabel={t('pspd.metricLabel')}
                  value={metric} onChange={setMetric}
                  options={METRICS.map(mt => ({ value: mt.key, label: t(mt.labelKey) }))} />
              </div>
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="pagespeed"
                  metric={activeMetric.key} unit={activeMetric.unit}
                  budget={budgetFor(selected, activeMetric.key)} budgetLabel={t('pspd.budgetLine')} />
              </Suspense>
            </>)}

            {detailTab === 'control' && (
              /* columns + renderRow ZORUNLU: CheckHistoryTab satirlari cizmeyi cagirana birakir.
                 Gecilmezse map icinde "renderRow is not a function" ile sekme comple coker. */
              <CheckHistoryTab kind="pagespeed" monitorId={selected.id} listKey="pagespeed-history" reloadSignal={histReload}
                defaultPreset={7} gridClass="pspd-rt-grid"
                columns={[t('pspd.colTime'), t('pspd.colStatus'), t('pspd.mLoad'),
                          t('pspd.mTtfb'), t('pspd.mSize'), t('pspd.mRequests')]}
                renderRow={(c) => {
                  // Eşik aşımı KESİNTİ DEĞİL: ok=true kalır, ihlal ayrı kolonda gelir.
                  const breached = typeof c.breached_metrics === 'string' && c.breached_metrics
                  const st = c.ok === false ? 'DOWN' : breached ? 'SLOW' : 'OK'
                  return (<>
                    <span className="upt-rt-time">{formatDateSec(c.checked_at)}</span>
                    <span style={{ color: STATUS_COLOR[st], fontWeight: 600 }}>
                      {statusLabel(st)}
                      {/* HANGİ eşik, kaçtı, kaç ölçüldü. Yalnız "Eşik aşıldı" demek kullanıcıyı
                          sebebi aramaya gönderiyordu — veri zaten kayıtlıydı, gösterilmiyordu. */}
                      {breached && <BreachEvidence metrics={c.breached_metrics} detail={c.breach_detail} t={t} />}
                    </span>
                    <span className="upt-rt-ms">{c.response_ms != null ? `${c.response_ms} ms` : '—'}</span>
                    <span className="upt-rt-ms">{c.ttfb_ms != null ? `${c.ttfb_ms} ms` : '—'}</span>
                    <span className="upt-rt-ms">{formatBytes(c.total_bytes, c.bytes_truncated)}</span>
                    <span className="upt-rt-ms">{c.request_count ?? '—'}</span>
                  </>)
                }} />
            )}
            {detailTab === 'alerts' && <AlertHistory domain={selected.url} types={alertTypesFor('pagespeed')} />}
            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PAGESPEED" target={selected.url} />
              </Suspense>
            )}
            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                {/* Prop adlari ChangeHistoryTab imzasiyla BIREBIR: t / kind / monitorId /
                    teamNames / canManage. Yanlis adla gecmek sessiz degil — t cagrilinca
                    "t is not a function" ile sekme comple cokuyor. */}
                <ChangeHistoryTab t={t} kind="pagespeed" monitorId={selected.id}
                  teamNames={teamNameById} canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Form modali ── */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()}
            style={{ maxWidth: 720, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--keyword">
              <div className="modal-icon-hdr-badge"><Gauge size={20} /></div>
              <h3>{modal === 'new' ? t('pspd.modalNew') : t('pspd.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('pspd.testing')} /></span>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>

            {dupSource
              ? <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>
              : <div className="kw-type-banner"><Gauge size={16} /><span>{t('pspd.typeInfo')}</span></div>}

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('pspd.url')} <span className="req-star">*</span></span>
                <input value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
                  onChange={e => setForm(f => ({ ...f, url: e.target.value }))}
                  onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('pspd.urlHint')}</div>
              <label><span>{t('pspd.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('pspd.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('pspd.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('pspd.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('pspd.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('pspd.noGroup')} /></label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
                onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />

              {/* ── Alarm eşikleri: DÖRDÜ DE opsiyonel ── */}
              <div className="full-width kw-tags-block">
                <div className="kw-block-title">{t('pspd.thresholdsTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('pspd.thresholdsHint')}</div>
                <div className="kw-adv-grid">
                  <label><span>{t('pspd.maxLoadMs')} <span className="field-unit">ms</span></span>
                    <input type="number" min="0" step="100" value={form.maxLoadMs} placeholder={t('pspd.noThreshold')}
                      onChange={e => setForm(f => ({ ...f, maxLoadMs: e.target.value }))} /></label>
                  <label><span>{t('pspd.maxTtfbMs')} <span className="field-unit">ms</span></span>
                    <input type="number" min="0" step="50" value={form.maxTtfbMs} placeholder={t('pspd.noThreshold')}
                      onChange={e => setForm(f => ({ ...f, maxTtfbMs: e.target.value }))} /></label>
                  <label><span>{t('pspd.maxPageKb')} <span className="field-unit">KB</span></span>
                    <input type="number" min="0" step="100" value={form.maxPageKb} placeholder={t('pspd.noThreshold')}
                      onChange={e => setForm(f => ({ ...f, maxPageKb: e.target.value }))} /></label>
                  <label><span>{t('pspd.maxRequests')}</span>
                    <input type="number" min="0" step="10" value={form.maxRequests} placeholder={t('pspd.noThreshold')}
                      onChange={e => setForm(f => ({ ...f, maxRequests: e.target.value }))} /></label>
                </div>
              </div>

              {/* Etiketler */}
              <div className="full-width kw-tags-block">
                <div className="kw-block-title">{t('pspd.tagsTitle')} <span className="req-star">*</span></div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('pspd.tagsPlaceholder')} suggestions={teamTags} />
              </div>



              {/* ── Gelişmiş ── */}
              <div className="full-width kw-adv">
                <button type="button" className="kw-adv-toggle" onClick={() => setAdvOpen(o => !o)}>
                  <ChevronDown size={16} className={`kw-adv-chevron${advOpen ? ' open' : ''}`} />
                  <span>{t('pspd.advanced')}</span>
                </button>
                {advOpen && (
                  <div className="kw-adv-body">
                    <div className="kw-adv-grid">
                      <label><span>{t('pspd.timeoutMs')} <span className="field-unit">ms</span></span>
                        <input type="number" min="1000" step="500" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
                      <label><span>{t('pspd.resourceConcurrency')}</span>
                        <input type="number" min="1" max="20" value={form.resourceConcurrency} onChange={e => setForm(f => ({ ...f, resourceConcurrency: Number(e.target.value) }))} /></label>
                      <label><span>{t('pspd.confirmAttempts')}</span>
                        <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
                      <label><span>{t('pspd.confirmInterval')}</span>
                        <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
                      <label><span>{t('pspd.recoveryChecks')}</span>
                        <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
                      <label><span>{t('pspd.recoveryInterval')}</span>
                        <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
                    </div>

                    <label className="full-width" style={{ marginTop: 10 }}><span>{t('pspd.userAgent')}</span>
                      <input value={form.userAgent} placeholder={t('pspd.userAgentPh')}
                        onChange={e => setForm(f => ({ ...f, userAgent: e.target.value }))} /></label>
                    <div className="field-hint">{t('pspd.userAgentHint')}</div>

                    <label className="checkbox-label" style={{ marginTop: 10 }}>
                      <input type="checkbox" checked={form.sendDnt} onChange={e => setForm(f => ({ ...f, sendDnt: e.target.checked }))} />{t('pspd.sendDnt')}</label>
                    <div className="field-hint">{t('pspd.sendDntHint')}</div>

                    {/* Kurumsal vekil (2026-09-21): Sayfa Hızı'nda varsayılan DOĞRUDAN — vekil gecikmesi ölçüme karışır; vekil-zorunlu
                        sayfalar için açılabilir. Düzenlemede kaydedilmiş etkin karar ipucu (MonitorProxyField sözleşmesi). */}
                    <div style={{ marginTop: 10 }}>
                      <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
                        effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />
                      <div className="field-hint">{t('pspd.proxyNote')}</div>
                    </div>

                    <label className="checkbox-label" style={{ marginTop: 10 }}>
                      <input type="checkbox" checked={form.excludeTrackers} onChange={e => setForm(f => ({ ...f, excludeTrackers: e.target.checked }))} />{t('pspd.excludeTrackers')}</label>
                    <div className="field-hint">{t('pspd.excludeTrackersHint')}</div>
                    <div className="full-width kw-tags-block" style={{ marginTop: 8 }}>
                      <TagInput value={form.trackerPatterns} onChange={v => setForm(f => ({ ...f, trackerPatterns: v }))}
                        placeholder={t('pspd.trackerPatternsPh')} />
                      <div className="field-hint">{t('pspd.trackerPatternsHint')}</div>
                    </div>

                    <div className="kw-block-title" style={{ marginTop: 14 }}>{t('pspd.authTitle')}</div>
                    <div className="kw-adv-grid">
                      <label><span>{t('pspd.basicAuthUser')}</span>
                        <input value={form.basicAuthUser} autoComplete="off"
                          onChange={e => setForm(f => ({ ...f, basicAuthUser: e.target.value }))} /></label>
                      <label><span>{t('pspd.basicAuthPass')}</span>
                        <input type="password" value={form.basicAuthPass} autoComplete="new-password"
                          placeholder={modal !== 'new' && modal.has_basic_auth_pass ? '••••••••' : ''}
                          onChange={e => setForm(f => ({ ...f, basicAuthPass: e.target.value }))} /></label>
                    </div>
                    <div className="field-hint">
                      {modal !== 'new' && modal.has_basic_auth_pass ? t('pspd.basicAuthSavedHint') : t('pspd.basicAuthHint')}
                    </div>

                    {/* Özel başlıklar YALNIZ global admin'e çizilir: serbest başlık iç servislere
                        yetki/SSRF yüzeyi açar (PORT sendData ile aynı gerekçe). */}
                    {isAdmin && (<>
                      <label className="full-width" style={{ marginTop: 12 }}><span>{t('pspd.customHeaders')}</span>
                        <textarea rows={3} spellCheck={false} value={form.customHeaders}
                          placeholder={t('pspd.customHeadersPh')}
                          onChange={e => setForm(f => ({ ...f, customHeaders: e.target.value }))} /></label>
                      <div className="field-hint">
                        {modal !== 'new' && modal.has_custom_headers
                          ? t('pspd.customHeadersSavedHint').replace('{0}', (modal.custom_header_names || []).join(', '))
                          : t('pspd.customHeadersHint')}
                      </div>
                    </>)}

                    <label className="checkbox-label" style={{ marginTop: 12 }}>
                      <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('pspd.active')}</label>
                    <div className="field-hint" style={{ marginTop: 6 }}>ⓘ {t('pspd.confirmHint')}</div>
                  </div>
                )}
              </div>
            </div>

            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error
                  ? { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }
                  : testResult.reachable
                    ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                    : { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }) }}>
                {testResult.error || !testResult.reachable
                  ? <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.error
                    ? <><strong>{t('pspd.testError')}:</strong> {testResult.error}</>
                    : <><strong>{statusLabel(testResult.status)}</strong>
                        {' — '}{testResult.response_ms} ms · TTFB {testResult.ttfb_ms} ms
                        {' · '}{formatBytes(testResult.total_bytes, testResult.bytes_truncated)}
                        {' · '}{testResult.request_count} {t('pspd.mRequests')}
                        {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}
                        {testResult.via && <> · {testResult.via === 'proxy' ? t('mon.proxy.effProxy') : t('mon.proxy.effDirect')}</>}
                        <br /><span style={{ opacity: .8 }}>{t('pspd.testNoThresholds')}</span></>}
                </span>
              </div>
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
              return (
                <div className="pspd-suggest">
                  <div className="pspd-suggest-head">{t('pspd.suggestTitle')}</div>
                  <ul className="pspd-suggest-list">
                    <li><span>{t('pspd.maxLoadMs')}</span>
                      <strong>{sug.maxLoadMs ?? '—'} ms</strong>
                      <em>{t('pspd.suggestWhyLoad')}</em></li>
                    <li><span>{t('pspd.maxTtfbMs')}</span>
                      <strong>{sug.maxTtfbMs ?? '—'} ms</strong>
                      <em>{t('pspd.suggestWhyTtfb')}</em></li>
                    <li><span>{t('pspd.maxPageKb')}</span>
                      <strong>{sug.maxPageKb ?? '—'} KB</strong>
                      <em>{t('pspd.suggestWhySize')}</em></li>
                    <li><span>{t('pspd.maxRequests')}</span>
                      <strong>{sug.maxRequests ?? '—'}</strong>
                      <em>{t('pspd.suggestWhyRequests')}</em></li>
                  </ul>
                  {partial && <div className="field-hint field-hint--warn">{t('pspd.suggestPartial')}</div>}
                  <button type="button" className="btn btn-sm btn-primary" onClick={apply}>
                    <Wand2 size={14} />{t('pspd.suggestApply')}
                  </button>
                  <div className="field-hint">{t('pspd.suggestNote')}</div>
                </div>
              )
            })()}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="pagespeed-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              {/* URL boşken ölçüm yapılamaz. Buton zaten kapalı; title kapalı olma SEBEBİNİ söyler
                  (sessizce tıklanmayan bir buton kullanıcıya arıza gibi görünüyor). */}
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                aria-busy={testing || undefined} disabled={testing || !form.url.trim()}
                title={!form.url.trim() ? t('pspd.testNeedsUrl') : undefined}>
                <FlaskConical size={14} />{t('pspd.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('pspd.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('pspd.cancel')}</button>
              <button className="btn btn-primary" onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.teamId}>{t('pspd.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}

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
