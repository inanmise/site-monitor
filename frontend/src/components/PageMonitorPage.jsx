import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense, Fragment } from 'react'
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
import { monitorDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import TagInput from './ui/TagInput.jsx'
import { RefreshCw, Plus, Trash2, ScanSearch, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ChevronDown, Image, FileCode, Link2, Frame, Type, ShieldAlert, Download, EyeOff, Inbox } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import { useDialog } from './ui/Dialog.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesProxy } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorProxyField from './ui/MonitorProxyField.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { csvCell } from '../utils/csv.js'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

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
const emptyForm = { name: '', url: '', groupName: '', notificationGroupId: '', teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  mode: 'SINGLE_PAGE', crawlDepth: 2, crawlMaxPages: 50, excludePatterns: '', slowResourceMs: 2000, useProxy: 'AUTO',
  alertThirdParty: false, alertMixedContent: true, alertTimeout: true, resourceConcurrency: 5,
  intervalSeconds: 300, timeoutMs: 4000, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true }

export default function PageMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const toast = useToast()
  const { showPrompt, showConfirm } = useDialog()
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

  const sparks = useSparklines('page')   // kart mini trendi (2026-09-12)
  const sla = useSla('page')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  useEscapeKey(!!selected, closeDetail)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [issues, setIssues] = useState([])
  const [issuesLoading, setIssuesLoading] = useState(false)
  const [confirmations, setConfirmations] = useState([])   // canlı teyit zincirleri (Teyit denemesi X/N)
  const [issueFilter, setIssueFilter] = useState('all')   // all | BROKEN | MIXED_CONTENT | SLOW | firstParty
  const [modal, setModal] = useState(null)
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])
  const [defaults, setDefaults] = useState(null)
  const [advOpen, setAdvOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [testing, setTesting] = useState(false)
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('issues')
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
  const [secondsSince, setSecondsSince] = useState(0)

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
    runOne: checkNow,
    concurrency: CHECK_CONCURRENCY_BY_TYPE.page,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)

  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'page').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.page) })
  }, [])

  useMonitorDeepLink(monitors, openDetail)

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
    setSelected(m); setIssues([]); setConfirmations([]); setIssueFilter('all'); setDetailTab('issues')
    loadIssues(m.id, 'all'); loadConfirmations(m.url)
  }
  function closeDetail() { setSelected(null); setIssues([]); setConfirmations([]) }

  // Modal 30sn oto-yenileme (sessiz): sorunlar + canlı teyit durumu + kart metrikleri.
  // (Kontrol Geçmişi kendi 30sn canlı yenilemesini CheckHistoryTab içinde yapar.)
  async function refreshModal() {
    if (!selected) return
    const res = await api.monitoring.getPageMonitors()
    if (res?.success) {
      setMonitors(res.data)
      const fresh = (res.data || []).find(x => x.id === selected.id)
      if (fresh) setSelected(fresh)
    }
    loadIssues(selected.id, issueFilter, true)
    loadConfirmations(selected.url)
  }
  useVisibleInterval(() => { if (selected) refreshModal() }, selected ? 30000 : 0, false)

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
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
    return { name: m.name || '', url: m.url || '', groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
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
  function openEdit(m) {
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m))
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız URL'i değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.url) })
    setModal('new')
  }
  function closeEdit() { setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  async function runTest() {
    if (!form.url.trim()) return
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testPage({ url: normalizeUrl(form.url), timeoutMs: Number(form.timeoutMs), useProxy: form.useProxy || 'AUTO' })
      setTestResult(res?.success ? res.data : { error: res?.error || t('page.testError') })
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
      const payload = {
        name: (form.name || form.url).trim(), url: normalizeUrl(form.url),
        groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
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
      toast.success(t('page.saved')); closeEdit()
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

      toast.success(t('page.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deletePageMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('page.deleted')); closeEdit()
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
        if (selected?.id === m.id) { setSelected(res.data); loadIssues(m.id, issueFilter) }
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
      if (res.data) setSelected(res.data)
      load()
    } else {
      toast.error(res?.error || 'Error')
    }
  }

  function exportIssuesCsv() {
    if (!issues.length) return
    const head = ['resource_url', 'resource_type', 'source_page', 'issue_type', 'first_party', 'http_status', 'duration_ms', 'checked_at']
    // Ortak kaçış: formül nötrleme + CR/LF tırnaklama (utils/csv.js). Satır sonu CRLF ve
    // başta BOM — Excel Türkçe karakterleri ancak öyle doğru açıyor (envanter dışa aktarımıyla aynı).
    const body = issues.map(r => head.map(k => csvCell(r[k])).join(',')).join('\r\n')
    const blob = new Blob(['﻿' + head.map(csvCell).join(',') + '\r\n' + body],
      { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `page-issues-${selected?.id ?? 'x'}.csv`
    a.click(); URL.revokeObjectURL(a.href)
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
  const pager = usePagination(displayMonitors, {
    listKey: 'page-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, proxyFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, proxyFilter, search, statFilter, pager }),
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'issues' ? detailTab : null,
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

  // CONFIG_ERROR: URL'de host yok (şemasız/bozuk) → kesinti DEĞİL, alarm üretmez; mor ile ayrışır.
  const STATUS_COLOR = { OK: '#15803d', DEGRADED: '#e07b00', DOWN: '#c0392b', CONFIG_ERROR: '#7c3aed', unknown: '#64748b' }
  function cardClass(m) {
    if (m.status === 'OK') return 'upt-card--up'
    if (m.status === 'DOWN') return 'upt-card--down'
    return 'upt-card--unknown'   // DEGRADED / CONFIG_ERROR / unknown
  }
  function statusBadge(m) {
    const s = m?.status
    const label = s === 'OK' ? t('page.statusOk') : s === 'DEGRADED' ? t('page.statusDegraded')
      : s === 'DOWN' ? t('page.statusDown') : s === 'CONFIG_ERROR' ? t('page.statusConfigError')
      : t('page.statusUnknown')
    return <span className="upt-badge" style={{ color: STATUS_COLOR[s] || STATUS_COLOR.unknown }}>
      <span className="upt-badge-dot" style={{ background: STATUS_COLOR[s] || STATUS_COLOR.unknown }} />{label}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('page.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('page.noTeam'))
    : (teamName || t('page.noTeam'))
  const issueFilters = ['all', 'BROKEN', 'TIMEOUT', 'BLOCKED', 'MIXED_CONTENT', 'SLOW', 'firstParty']

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('page.title')}</h2>
          <p className="upt-subtitle">{t('page.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('page.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('page.refresh')}
          </button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="page" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('page.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('page.how1'), t('page.how2'), t('page.how2b'), t('page.how3'), t('page.how4'), t('page.how5'), t('page.how6'), t('page.how7'), t('page.how8')]} />

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
          <input className="upt-search" type="text" placeholder={t('page.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('page.noMonitorsAdmin') : t('page.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updatePageMonitor, remove: api.monitoring.deletePageMonitor }}
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
                  <span className="upt-port-tag">{m.mode === 'SITE_CRAWL' ? t('page.modeCrawl') : t('page.modeSingle')}</span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('page', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.url}>{m.url}</div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.broken_resources ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mBroken')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.timeout_count ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mTimeout')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.mixed_content_count ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mMixed')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.total_resources ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mResources')}</span>
                </div>
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('page.check')} editTitle={t('page.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('page.delete')} />
                )}
              </div>
            </div>
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detail Modal ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeDetail}>
          <div className={`upt-modal upt-modal--${selected.status === 'OK' ? 'up' : selected.status === 'DOWN' ? 'down' : 'unknown'}`} onClick={e => e.stopPropagation()}>
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
                checkTitle={t('page.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('page.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('page.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ color: STATUS_COLOR[selected.status] }}>{t(`page.status${selected.status === 'OK' ? 'Ok' : selected.status === 'DEGRADED' ? 'Degraded' : selected.status === 'DOWN' ? 'Down' : 'Unknown'}`)}</span><span className="upt-modal-metric-lbl">{t('page.lastStatus')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.broken_resources ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mBroken')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.timeout_count ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mTimeout')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.mixed_content_count ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mMixed')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.total_resources ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mResources')}</span></div>
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('page.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            {/* Canlı teyit durumu — 30sn oto-yenilemeyle ilerler; kullanıcı denemenin kaçıncı bacağında olduğunu görür. */}
            {confirmations.filter(c => c.alert_type === 'PAGE_DOWN' || c.alert_type === 'PAGE_INTEGRITY').map((c, i) => (
              <div key={`cf-${i}`} className="page-confirm-banner">
                <RefreshCw size={13} className="page-confirm-spin" />
                {t('page.confirmBanner', Math.max(1, c.attempt), c.total_attempts,
                  c.next_attempt_at ? formatDateSec(c.next_attempt_at) : '—')}
              </div>
            ))}
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'issues' ? ' active' : ''}`} onClick={() => setDetailTab('issues')}>{t('page.tabIssues')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('page.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('page.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('page.tabNotes')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'issues' && (<>
              <div className="upt-range-btns page-issue-filters" style={{ flexWrap: 'wrap' }}>
                {issueFilters.map(f => (
                  <button key={f} type="button" className={`btn btn-sm ${issueFilter === f ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => selectIssueFilter(selected.id, f)}>{t(`page.filter_${f}`)}</button>
                ))}
                <button type="button" className="btn btn-sm btn-secondary" style={{ marginLeft: 'auto' }}
                  disabled={!issues.length} onClick={exportIssuesCsv}><Download size={12} />{t('page.exportCsv')}</button>
              </div>
              {issuesLoading ? <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" /> : issues.length === 0 ? (
                <LoadingBlock label={t('page.noIssues')} className="upt-modal-loading" />
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head" style={{ gridTemplateColumns: PAGE_ISSUE_COLS }}>
                    <span>{t('page.colTime')}</span><span>{t('page.colType')}</span><span>{t('page.colResource')}</span><span>{t('page.colIssue')}</span><span>HTTP</span><span>{t('page.colDuration')}</span><span>{t('page.colScope')}</span><span>{t('page.colActions')}</span>
                  </div>
                  {issues.map((r, i) => {
                    const RI = RES_ICON[r.resource_type] || Link2
                    const issueColor = r.issue_type === 'MIXED_CONTENT' ? '#b45309' : r.issue_type === 'SLOW' ? '#0369a1'
                      : r.issue_type === 'BLOCKED' ? '#78716c' : r.issue_type === 'TIMEOUT' ? '#a16207' : '#b91c1c'   // BLOCKED/TIMEOUT nötr (kesin kırık değil)
                    const scope = alarmScope(r, selected)
                    // Her tarama turunun (checked_at) başına belirgin başlık bandı — turlar net ayrışır.
                    const runStart = i === 0 || (issues[i - 1].checked_at !== r.checked_at)
                    const runCount = runStart ? issues.filter(x => x.checked_at === r.checked_at).length : 0
                    return (
                      <Fragment key={`${r.id || ''}#${i}`}>
                      {runStart && (
                        <div className="page-run-hdr">
                          <span>{r.checked_at ? formatDateSec(r.checked_at) : '—'}</span>
                          <span className="page-run-hdr-count">{t('page.runHdrCount', runCount)}</span>
                        </div>
                      )}
                      <div className="upt-rt-grid" style={{ gridTemplateColumns: PAGE_ISSUE_COLS }}>
                        <span className="upt-rt-time">{r.checked_at ? formatDateSec(r.checked_at) : '—'}</span>
                        <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                          <RI size={13} />{r.resource_type}{!r.first_party && <span title={t('page.thirdParty')} style={{ color: 'var(--text-muted)' }}>·3P</span>}
                        </span>
                        <span style={{ wordBreak: 'break-all' }} title={r.source_page ? `${t('page.foundOn')}: ${r.source_page}` : ''}>
                          {/* href guard (L3): yalnız http(s) source_page linklenir — javascript:/data: vb. şema tıklanabilir XSS'i engellenir */}
                          {r.source_page && r.source_page !== r.resource_url && /^https?:\/\//i.test(r.source_page)
                            ? <a href={r.source_page} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} style={{ color: 'inherit' }}>{r.resource_url}</a>
                            : r.resource_url}
                        </span>
                        <span style={{ color: issueColor, fontWeight: 600 }}>
                          {r.issue_type === 'MIXED_CONTENT' ? <ShieldAlert size={12} style={{ verticalAlign: '-2px' }} /> : null} {t(`page.issue_${r.issue_type}`)}
                        </span>
                        <span className="upt-rt-ms">{r.http_status ?? '—'}</span>
                        <span className="upt-rt-ms">{r.duration_ms != null ? r.duration_ms + 'ms' : '—'}</span>
                        <span>
                          <span className={scope.inScope ? 'page-scope-in' : 'page-scope-out'}
                            title={scope.reasonKey ? t(scope.reasonKey) : t('page.scopeInTitle')}>
                            {scope.inScope ? t('page.scopeIn') : t('page.scopeOut')}
                          </span>
                        </span>
                        <span>
                          {canManageRow(selected) && (
                            isExcluded(selected, r.resource_url)
                              ? <button type="button" className="btn btn-sm btn-secondary page-exclude-btn" disabled
                                  title={t('page.excludeAlready')} aria-label={t('page.excludeAlready')}>
                                  <EyeOff size={12} /></button>
                              : <button type="button" className="btn btn-sm btn-secondary page-exclude-btn"
                                  title={t('page.excludeAdd')} aria-label={t('page.excludeAdd')}
                                  onClick={e => { e.stopPropagation(); addExclude(r) }}>
                                  <EyeOff size={12} /></button>
                          )}
                        </span>
                      </div>
                      </Fragment>
                    )
                  })}
                </div>
              )}
            </>)}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="page" />
              </Suspense>
            )}

            {detailTab === 'control' && (
              /* Kırık ve Zaman aşımı AYRI kolonlar (2026-08-04); eski kayıtlarda timeout '—' (o dönem kırığa dahildi). */
              <CheckHistoryTab kind="page" monitorId={selected.id} listKey="page-history" reloadSignal={histReload}
                defaultPreset={7} gridClass="page-rt-grid"
                columns={[t('page.colTime'), t('page.colStatus'), t('page.mBroken'), t('page.mTimeout'), t('page.mMixed')]}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDateSec(c.checked_at)}</span>
                  <span style={{ color: STATUS_COLOR[c.status] || STATUS_COLOR.unknown, fontWeight: 600 }}>
                    {c.status === 'OK' ? t('page.statusOk') : c.status === 'DEGRADED' ? t('page.statusDegraded')
                      : c.status === 'CONFIG_ERROR' ? t('page.statusConfigError') : t('page.statusDown')}</span>
                  <span className="upt-rt-ms">{c.broken_resources ?? '—'}</span>
                  <span className="upt-rt-ms">{c.timeout_count ?? '—'}</span>
                  <span className="upt-rt-ms">{c.mixed_content_count ?? '—'}</span>
                </>)} />
            )}

            {detailTab === 'alerts' && <AlertHistory domain={selected.url} types={alertTypesFor('page')} />}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PAGE" target={selected.url} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="page" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()} style={{ maxWidth: 720, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--keyword">
              <div className="modal-icon-hdr-badge"><ScanSearch size={20} /></div>
              <h3>{modal === 'new' ? t('page.modalNew') : t('page.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('page.testing')} /></span>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>

            {dupSource
              ? <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>
              : <div className="kw-type-banner"><ScanSearch size={16} /><span>{t('page.typeInfo')}</span></div>}

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('page.url')} <span className="req-star">*</span></span>
                <input value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
                  onChange={e => setForm(f => ({ ...f, url: e.target.value }))}
                  onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('page.urlHint')}</div>
              <label><span>{t('page.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('page.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('page.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('page.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('page.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('page.noGroup')} /></label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
                onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />

              {/* Mod seçimi */}
              <label><span>{t('page.mode')}</span>
                <SearchableSelect value={form.mode} onChange={v => setForm(f => ({ ...f, mode: v }))}
                  options={[{ value: 'SINGLE_PAGE', label: t('page.modeSingle') }, { value: 'SITE_CRAWL', label: t('page.modeCrawl') }]} /></label>
              {form.mode === 'SITE_CRAWL' && (<>
                <label><span>{t('page.crawlDepth')}</span>
                  <input type="number" min="0" max="5" value={form.crawlDepth} onChange={e => setForm(f => ({ ...f, crawlDepth: Number(e.target.value) }))} /></label>
                <label><span>{t('page.crawlMaxPages')}</span>
                  <input type="number" min="1" max="500" value={form.crawlMaxPages} onChange={e => setForm(f => ({ ...f, crawlMaxPages: Number(e.target.value) }))} /></label>
              </>)}
              <label className="full-width"><span>{t('page.excludePatterns')}
                {(() => { const n = (form.excludePatterns || '').split('\n').map(s => s.trim()).filter(Boolean).length
                  return n > 0 ? <span className="page-exclude-count">{t('page.excludeCount', n)}</span> : null })()}</span>
                <textarea rows={4} className="page-exclude-ta" value={form.excludePatterns} spellCheck={false}
                  placeholder={t('page.excludePh')}
                  onChange={e => setForm(f => ({ ...f, excludePatterns: e.target.value }))} />
                <span className="field-hint">{t('page.excludeHint')}</span></label>
              {/* Kurumsal vekil (2026-09-21): sertifika envanteriyle aynı karar */}
              <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
                effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.alertThirdParty} onChange={e => setForm(f => ({ ...f, alertThirdParty: e.target.checked }))} />{t('page.alertThirdParty')}</label>
              <div className="full-width field-hint">{t('page.alertThirdPartyHint')}</div>

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.alertMixedContent} onChange={e => setForm(f => ({ ...f, alertMixedContent: e.target.checked }))} />{t('page.alertMixedContent')}</label>
              <div className="full-width field-hint">{t('page.alertMixedContentHint')}</div>

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.alertTimeout} onChange={e => setForm(f => ({ ...f, alertTimeout: e.target.checked }))} />{t('page.alertTimeout')}</label>
              <div className="full-width field-hint">{t('page.alertTimeoutHint')}</div>

              {/* Etiketler */}
              <div className="full-width kw-tags-block">
                <div className="kw-block-title">{t('page.tagsTitle')} <span className="req-star">*</span></div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('page.tagsPlaceholder')} />
              </div>



              {/* Gelişmiş */}
              <div className="full-width kw-adv">
                <button type="button" className="kw-adv-toggle" onClick={() => setAdvOpen(o => !o)}>
                  <ChevronDown size={16} className={`kw-adv-chevron${advOpen ? ' open' : ''}`} />
                  <span>{t('page.advanced')}</span>
                </button>
                {advOpen && (
                  <div className="kw-adv-body">
                    <div className="kw-adv-grid">
                      <label><span>{t('page.slowResourceMs')}</span>
                        <input type="number" min="100" step="100" value={form.slowResourceMs} onChange={e => setForm(f => ({ ...f, slowResourceMs: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.resourceConcurrency')}</span>
                        <input type="number" min="1" max="20" value={form.resourceConcurrency} onChange={e => setForm(f => ({ ...f, resourceConcurrency: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.timeoutMs')}</span>
                        <input type="number" min="1000" step="500" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.confirmAttempts')}</span>
                        <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.confirmInterval')}</span>
                        <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.recoveryChecks')}</span>
                        <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.recoveryInterval')}</span>
                        <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
                    </div>
                    <label className="checkbox-label" style={{ marginTop: 10 }}>
                      <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('page.active')}</label>
                    <div className="field-hint" style={{ marginTop: 6 }}>ⓘ {t('page.confirmHint')}</div>
                  </div>
                )}
              </div>
            </div>

            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error
                  ? { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }
                  : testResult.status === 'OK'
                    ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                    : { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }) }}>
                {testResult.error || testResult.status !== 'OK'
                  ? <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.error
                    ? <><strong>{t('page.testError')}:</strong> {testResult.error}</>
                    : <><strong>{t(`page.status${testResult.status === 'OK' ? 'Ok' : testResult.status === 'DEGRADED' ? 'Degraded' : 'Down'}`)}</strong>
                        {' — '}{testResult.total_resources} {t('page.mResources')} · {testResult.broken_resources} {t('page.mBroken')} · {testResult.timeout_count ?? 0} {t('page.mTimeout')} · {testResult.mixed_content_count} {t('page.mMixed')}
                        {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}</>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="page-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                aria-busy={testing || undefined} disabled={testing || !form.url.trim()}>
                <FlaskConical size={14} />{t('page.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('page.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('page.cancel')}</button>
              <button className="btn btn-primary" onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.teamId}>{t('page.save')}</button>
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
