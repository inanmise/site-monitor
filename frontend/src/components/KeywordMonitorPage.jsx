import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { sortMonitorsDefault } from '../utils/monitorSort.js'
import { formatPercent } from '../i18n/dateLocale.js'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import TagInput from './ui/TagInput.jsx'
import { Trash2, Target, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ChevronDown, ShieldCheck, Inbox, Copy, Stethoscope } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
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
import { normalizeUrl } from '../utils/normalizeUrl.js'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin (eager bundle'a girmesin).
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesProxy } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorProxyField from './ui/MonitorProxyField.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { useCardDensity } from '../hooks/useCardDensity.js'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import ModalShell from './ui/ModalShell.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { Slider } from '@/components/shadcn/slider'
import { TabsContent } from '@/components/shadcn/tabs'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import { MON_ACT, MON_ACT_TONE } from './ui/CheckRunning.jsx'
import KeywordMonitorCard from './keyword/KeywordMonitorCard.jsx'
import { KeywordFailureCell, KeywordFailurePanel } from './keyword/KeywordCheckFailure.jsx'
import { metaRow as keywordMetaRow } from './keyword/keywordCardModel.js'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailInfoCard, DetailTabs, OnOff, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint, InlineField, LabelSlot,
} from './monitoring/MonitorForm.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))
// Keyword uçtan uca tanılama penceresi (2026-10-04) — tembel: yalnız açılınca iner, sayfa paketi büyümez
const KeywordDiagnoseDialog = lazy(() => import('./keyword/diagnose/KeywordDiagnoseDialog.jsx'))
/** Kontrol geçmişi tablo ↔ kart eşiği (kap genişliği, px) — açılan teşhis paneli dar kapta kart altına iner. */
const HISTORY_CARDS_BELOW = 640

const INTERVALS = [
  { value: 30,    labelKey: 'keyword.iv30s' },
  { value: 60,    labelKey: 'keyword.iv1m'  },
  { value: 300,   labelKey: 'keyword.iv5m'  },
  { value: 600,   labelKey: 'keyword.iv10m' },
  { value: 900,   labelKey: 'keyword.iv15m' },
  { value: 1800,  labelKey: 'keyword.iv30m' },
  { value: 3600,  labelKey: 'keyword.iv1h'  },
  { value: 43200, labelKey: 'keyword.iv12h' },
  { value: 86400, labelKey: 'keyword.iv24h' },
]
const intervalIdx = (secs) => {
  const i = INTERVALS.findIndex(o => o.value === secs)
  if (i >= 0) return i
  let best = 0, bd = Infinity
  INTERVALS.forEach((o, j) => { const d = Math.abs(o.value - secs); if (d < bd) { bd = d; best = j } })
  return best
}
const REFRESH_INTERVAL = 60
const OP_SYM = { GTE: '≥', LTE: '≤', EQ: '=', GT: '>', LT: '<' }
const emptyForm = { name: '', url: '', keyword: '', operator: 'GTE', matchCount: 1, groupName: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '',
  caseSensitive: false, useProxy: 'AUTO', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  checkSslErrors: false, sslExpiryReminders: false, domainExpiryReminders: false,
  sslReminderDays: '30,14,7', domainReminderDays: '30,14,7',
  slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 60, timeoutMs: 10000, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, customHeaders: '', active: true }

export default function KeywordMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'      // USER ve üstü: kendi takımı için oluştur/düzenle/kontrol
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
  const canManageRow = (m) => isAdmin || isOwnTeam(m)                    // düzenle + kontrol (kendi takımı)
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  // 2026-09-29: + sunucunun satır bayrağı `can_check` (tetik ucunun kapısıyla AYNI kural — kapsamlı yönetici görebildiği
  // ama çalıştıramadığı başka takım satırını "Şimdi Kontrol Et (N)" sayısına katmaz, toplu koşumda 403 yemez).
  const canCheckRow = (m) => canManageRow(m) && m?.can_check !== false
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))   // silme: TEAM_ADMIN/ADMIN
  // Uçtan uca tanılama (2026-10-04, HTTP ile aynı kural): YALNIZ sunucunun satır bayrağı `can_diagnose` (= can_check +
  // diagnostics.run). Liste satırına da bakılır: "Şimdi kontrol et" açık detayın kopyasını tetik yanıtıyla DEĞİŞTİRİR ve o
  // yanıtta bayrak yok — pencere koşu ortasında sökülmesin.
  const canDiagnoseRow = (m) => !!m && (m.can_diagnose === true || monitors.some((x) => x.id === m.id && x.can_diagnose === true))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('keyword')   // kart mini trendi (2026-09-12)
  const sla = useSla('keyword')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  // Kart yoğunluğu (2026-09-27): Kompakt / Zengin — her açılış Zengin başlar; Kompakt seçimi yalnız sayfada kalındıkça
  // geçerli, KALICI DEĞİL (kullanıcı kararı: sayfa değişip dönünce ya da yenileyince yeniden Zengin)
  const [density, setDensity] = useCardDensity('keyword')
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)   // detay penceresi (MonitorDetailModal — Escape'i ModalShell işler)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  const fe = useFormErrors(modal)   // doğrulama hataları alanın altında + ilk hatalıya kaydırma (2026-09-30)
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [defaults, setDefaults] = useState(null)   // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [showCacheHelp, setShowCacheHelp] = useState(false)   // cache busting açıklama modal'ı
  const [advOpen, setAdvOpen] = useState(false)               // "Gelişmiş ayarlar" (shadcn Collapsible)
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
  // Kontrol geçmişinde AÇIK teşhis panelleri (2026-10-04) — kontrol kimliği kümesi; detay değişince/kapanınca sıfırlanır.
  const [openChecks, setOpenChecks] = useState(() => new Set())
  const toggleCheck = (id) => setOpenChecks((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })
  // Uçtan uca tanılama penceresi (2026-10-04): { monitorId, runId, initialRunId } | null. Derin bağlantı ?monitor=<id>&kdx=<no>
  // YALNIZ kayıtlı çalıştırmayı açar (canlı koşu asla kendiliğinden başlamaz); `runId` gösterilen çalıştırmadır (URL'e yazılır).
  const [kwDx, setKwDx] = useState(() => {
    const runId = readUrlInt('kdx', null), monitorId = readUrlInt('monitor', null)
    return runId && monitorId ? { monitorId, runId, initialRunId: runId } : null
  })
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
      const res = await api.monitoring.getKeywordMonitors()
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
  const { resume, isResuming } = useMonitorResume(api.monitoring.updateKeywordMonitor, (r) => {
    load(); setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

  const checkable = monitors.filter(canCheckRow)
  const checkRun = useCheckRun({
    items: checkable,
    // Tekil yolun ta kendisi: kartın "kontrol ediliyor" göstergesi (track) ve sonucun
    // satıra işlenmesi toplu koşumda da AYNI koddan geçer — ikinci bir merge yolu yok.
    runOne: checkNow,
    concurrency: CHECK_CONCURRENCY_BY_TYPE.keyword,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'keyword').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])


  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.keyword) })
  }, [])

  // Derin bağlantı ?monitor=<id> (e-posta CTA, 7/24 Kapsamı): TAM listeden açar; yoksa uyarır (hooks/useMonitorDeepLink)
  useMonitorDeepLink(monitors, openDetail, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'KEYWORD',   // open=noc: 7/24 Kapsamı "7/24 ayarını düzenle"
  })

  function openDetail(m) {
    setSelected(m); setSummary({ total: 0, down: 0 }); setDetailTab(deepLinkTab()); setOpenChecks(new Set())
    setKwDx((cur) => (cur && cur.monitorId === m?.id ? cur : null))   // derin bağlantının kayıtlı çalıştırması yalnız KENDİ izlemesinde
  }
  function closeDetail() { setSelected(null); setOpenChecks(new Set()); setKwDx(null) }
  /** Tanılama penceresini aç — başlangıç ekranıyla (koşu kullanıcı "Tanılamayı başlat"a basınca). */
  function openDiagnose(m) { if (m) setKwDx({ monitorId: m.id, runId: null, initialRunId: null }) }

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (defaultTeamId != null ? String(defaultTeamId) : ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      slowThresholdMs: defaults?.slowThresholdMs ?? emptyForm.slowThresholdMs })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', url: m.url || '', keyword: m.keyword || '',
      operator: m.operator || 'GTE', matchCount: m.match_count ?? 1, groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
      teamId: m.team_id != null ? String(m.team_id) : '',
      caseSensitive: !!m.case_sensitive, useProxy: m.use_proxy || 'AUTO', tags: m.tags || '', notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false,
      checkSslErrors: !!m.check_ssl_errors, sslExpiryReminders: !!m.ssl_expiry_reminders, domainExpiryReminders: !!m.domain_expiry_reminders,
      sslReminderDays: m.ssl_reminder_days || '30,14,7', domainReminderDays: m.domain_reminder_days || '30,14,7',
      slowResponseEnabled: !!m.slow_response_enabled, slowThresholdMs: m.slow_threshold_ms ?? 3000,
      intervalSeconds: m.interval_seconds ?? 60, timeoutMs: m.timeout_ms ?? 10000,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30, recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      // Write-only: API düz değeri DÖNDÜRMÜYOR (şifreli saklanıyor). Alan boş başlar; boş
      // gönderilirse mevcut başlıklar korunur (payload'a hiç girmez).
      customHeaders: '',
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

  // Canlı koşul testi — kaydetmeden formdaki değerlerle URL'yi çekip koşulu değerlendirir.
  async function runTest() {
    if (!form.url.trim() || !form.keyword.trim()) return
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testKeyword({
        url: normalizeUrl(form.url), keyword: form.keyword, operator: form.operator,
        matchCount: Number(form.matchCount), timeoutMs: Number(form.timeoutMs),
        customHeaders: form.customHeaders?.trim() || null, caseSensitive: form.caseSensitive, useProxy: form.useProxy || 'AUTO',
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('keyword.testError') })
    } finally {
      setTesting(false)
    }
  }

  async function save() {
    // Doğrulama hataları ALANIN ALTINDA + ilk hatalıya kaydırma (2026-09-30) — tost yok, kullanıcı hatayı aramaz.
    if (fe.check({
      url: !form.url.trim() && t('mon.fieldRequired'),
      keyword: !form.keyword.trim() && t('mon.fieldRequired'),
      teamId: (form.teamId === '' || form.teamId == null) && t('mon.teamRequired'),
      groupName: !form.groupName?.trim() && t('mon.groupRequired'),   // grup + etiket zorunlu (2026-09-18)
      tags: !form.tags?.trim() && t('mon.tagsRequired'),
    })) return
    setSaving(true)
    try {
      const payload = {
        name: (form.name || form.url).trim(), url: normalizeUrl(form.url), keyword: form.keyword,
        operator: form.operator, matchCount: Number(form.matchCount),
        groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
        caseSensitive: form.caseSensitive, useProxy: form.useProxy || 'AUTO', tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING', notifyWebhook: form.notifyWebhook,
        checkSslErrors: form.checkSslErrors, sslExpiryReminders: form.sslExpiryReminders, domainExpiryReminders: form.domainExpiryReminders,
        sslReminderDays: form.sslReminderDays?.trim() || '30,14,7', domainReminderDays: form.domainReminderDays?.trim() || '30,14,7',
        slowResponseEnabled: form.slowResponseEnabled, slowThresholdMs: Number(form.slowThresholdMs),
        intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds), recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        active: form.active,
      }
      // Başlıklar YALNIZ admin ve alan DOLUYKEN gönderilir: boş bırakmak "değiştirme"
      // demektir (kardeşi PageSpeedMonitorPage ile aynı write-only sözleşme).
      if (isAdmin && form.customHeaders?.trim()) payload.customHeaders = form.customHeaders.trim()
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createKeywordMonitor(payload)
        : await api.monitoring.updateKeywordMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      toast.success(t('keyword.saved')); closeEdit()
      // İlk / taze kontrol (2026-09-28): yeni kart boş kalmasın, hedefi/kuralı değişen kart eski sonucu göstermesin. Gizli
      // başlıklar satırda geri okunamaz → yazıldıysa ayrıca bildirilir (bkz. utils/checkAfterSave).
      if (shouldCheckAfterSave('keyword', { isNew: modal === 'new', before: modal, after: res.data, extraChanged: payload.customHeaders !== undefined })) startCheckAfterSave(checkNow, res.data)
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

      confirmText: t('keyword.delete'),

      cancelText: t('keyword.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deleteKeywordMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      toast.success(t('keyword.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deleteKeywordMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('keyword.deleted')); closeEdit()
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerKeywordCheck(m.id)
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

  // Türetilmiş listeler memoize — 1sn countdown her saniye render tetikler; bu O(n)
  // hesaplar her tıkta değil yalnız bağımlılık değişince çalışsın.
  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('keyword.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])
  // Gruplar takıma özgüdür: kullanıcı yalnız kendi takımının gruplarını görür/seçer (admin tümünü).
  // Yeni grup creatable ile yazılıp seçilebilir (mevcut grup olmasa bile).
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('keyword.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('keyword.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
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
    return (m.url || '').toLowerCase().includes(q) || (m.keyword || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, tagFilter, proxyFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, up: 0, down: 0, error: 0, alarm: 0, unacked: 0 }
    for (const m of scoped) {
      if (m.status === 'up') c.up++
      else if (m.status === 'down') c.down++
      else if (m.status === 'error') c.error++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      up:      m => m.status === 'up',
      down:    m => m.status === 'down',
      error:   m => m.status === 'error',
      alarm:   m => m.active_alarm,
      unacked: m => m.active_alarm && !m.alarm_acknowledged,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  // Varsayılan kart sırası (2026-10-01): sorunlu önce → grup adı A→Z (grup içinde ad) → grupsuzlar ada göre
  const orderedMonitors = useMemo(() => sortMonitorsDefault(displayMonitors, 'keyword'), [displayMonitors])
  const pager = usePagination(orderedMonitors, {
    listKey: 'keyword-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, proxyFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, proxyFilter, search, statFilter, pager }),
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    // Tanılama penceresinde gösterilen çalıştırma (2026-10-04) — bağlantı paylaşılınca KAYITLI sonuç açılır
    kdx: selected && kwDx?.monitorId === selected.id && canDiagnoseRow(selected) ? (kwDx.runId ?? null) : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('keyword.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('keyword.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: TriangleAlert,   label: t('keyword.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'error',   Icon: ServerCrash,     label: t('keyword.dashError'),   value: counts.error,   cls: 'error'    },
    { key: 'alarm',   Icon: Siren,           label: t('keyword.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('keyword.dashUnacked'), value: counts.unacked, cls: 'warning'  , hint: t('mondash.unackedHint') },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)

  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  // Durum sözlüğü (kart şeridi / rozet / detay kenarı): up | down | unknown ('error' de ihlal gibi kırmızı).
  const statusKey = (m) => (m?.status === 'up' ? 'up' : m?.status === 'unknown' ? 'unknown' : 'down')
  function statusBadge(m) {
    const s = m?.status
    const label = s === 'up' ? t('keyword.statusOk')
      : s === 'unknown' ? t('keyword.statusUnknown')
      : s === 'error' ? t('keyword.statusError') : t('keyword.statusViolation')
    return <MonitorStatusBadge status={statusKey(m)}>{label}</MonitorStatusBadge>
  }

  // Operatör + adet → sağlıklı (alarmsız) koşul ifadesi — opPhrase aynası.
  // Sözlükten (QA 2026-09-12, ISSUE-006): "≥1 kez" / "en az 1 kez" İngilizce arayüze sızıyordu.
  function expectPhrase(op, n) {
    return t(`keyword.expect.${['LTE', 'EQ', 'GT', 'LT'].includes(op) ? op : 'GTE'}`, n)
  }
  // Alarmın HANGİ durumda tetikleneceği (koşulun sağlanmadığı taraf).
  function triggerPhrase(op, n, kw) {
    const k = kw && kw.trim() ? `« ${kw.trim()} »` : t('keyword.theKeyword')
    switch (op) {
      case 'LTE': return n === 0 ? t('keyword.trig.LTE0', k) : t('keyword.trig.LTE', k, n)
      case 'EQ':  return t('keyword.trig.EQ', k, n)
      case 'GT':  return t('keyword.trig.GT', k, n)
      case 'LT':  return t('keyword.trig.LT', k, n)
      default:    return n <= 1 ? t('keyword.trig.GTE1', k) : t('keyword.trig.GTE', k, n)
    }
  }

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('keyword.noTeam'))
    : (defaultTeamName || t('keyword.noTeam'))

  // İstek zaman aşımı çubuğu saniye cinsinden (1–60); form milisaniye tutar.
  const timeoutSecs = Math.min(60, Math.max(1, Math.round(Number(form.timeoutMs) / 1000)))
  const timeoutText = t('keyword.timeoutEvery').replace('{0}', timeoutSecs)

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // Detay penceresi açıkken form ONUN İÇİNDE çizilir: ModalShell iç içe derinliği React ağacından okur,
  // böylece form (ve örtüsü) detay penceresinin ÜSTÜNDE katmanlanır.
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={Target}
      title={modal === 'new' ? t('keyword.modalNew') : t('keyword.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('keyword.testing') : null}
      footer={<>
        <Button variant="secondary" className="mr-auto" onClick={runTest}
          aria-busy={testing || undefined} disabled={testing || !form.url.trim() || !form.keyword.trim()}>
          <FlaskConical size={14} />{t('keyword.test')}
        </Button>
        {modal !== 'new' && canDeleteRow(modal) && <Button variant="destructive" onClick={del}><Trash2 size={14} />{t('keyword.delete')}</Button>}
        <Button variant="secondary" onClick={closeEdit}>{t('keyword.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.keyword.trim() || !form.teamId}>{t('keyword.save')}</Button>
      </>}>
      {dupSource
        ? <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>
        : <AlertBanner tone="info" icon={Target}>{t('keyword.typeInfo')}</AlertBanner>}

      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField full label={t('keyword.url')} required {...fe.fieldProps('url')} hint={t('keyword.urlHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
              onChange={e => { setForm(f => ({ ...f, url: e.target.value })); fe.clear('url') }}
              onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} />
          )}
        </FormField>
        {/* "Nasıl kullanılır?" bağlantısı ETİKETİN içinde değil ipucu satırında: <label> içinde düğme
            geçersiz HTML'dir (etiketlenebilir öğe) ve düğme metni alanın erişilebilir adına karışıyordu. */}
        <FormField full label={t('keyword.customHeaders')} hint={<>
          {/* İsim listesi BOŞ olabilir: API adları yalnız global admin'e döndürüyor ve
              kayıtlı metin "Ad: değer" biçiminde değilse ayrıştırılamıyor. Yer tutucuyu boş
              dizeyle doldurmak "Kayıtlı başlıklar: ." gibi kırık bir cümle üretiyor ve
              kullanıcıya hiçbir şey kayıtlı değilmiş izlenimi veriyordu. Yedek metin hem
              cümleyi tamamlıyor hem kayıtlı değerin biçim sorununu işaret ediyor. */}
          {modal !== 'new' && modal?.has_custom_headers
            ? t('keyword.customHeadersSavedHint').replace('{0}',
                (modal.custom_header_names || []).filter(Boolean).join(', ') || t('mon.customHeadersSavedUnnamed'))
            : t('keyword.customHeadersHint')}
          {' '}
          <Button type="button" variant="link" size="xs" className="h-auto p-0 align-baseline text-xs font-medium"
            onClick={() => setShowCacheHelp(true)}>
            {t('keyword.cacheBustLink')}
          </Button>
        </>}>
          {({ id, describedBy }) => (
            <Textarea id={id} aria-describedby={describedBy} rows={2} value={form.customHeaders} spellCheck={false}
              placeholder={t('keyword.customHeadersPh')} disabled={!isAdmin}
              onChange={e => setForm(f => ({ ...f, customHeaders: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('keyword.operator')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.operator} onChange={v => setForm(f => ({ ...f, operator: v }))}
              options={[{ value: 'GTE', label: t('keyword.opGte') }, { value: 'LTE', label: t('keyword.opLte') },
                { value: 'EQ', label: t('keyword.opEq') }, { value: 'GT', label: t('keyword.opGt') },
                { value: 'LT', label: t('keyword.opLt') }]} />
          )}
        </FormField>
        <FormField label={t('keyword.matchCount')}>
          {({ id }) => <Input id={id} type="number" min="0" value={form.matchCount} onChange={e => setForm(f => ({ ...f, matchCount: Number(e.target.value) }))} />}
        </FormField>
        {/* Koşulun canlı açıklaması — yazdıkça değişir; role="note": canlı bölge DEĞİL (her tuşta duyurulmasın). */}
        <AlertBanner tone="info" role="note" className="mb-0 sm:col-span-2">
          <div><Check size={12} aria-hidden="true" className="inline align-[-2px] text-success" /> <strong>{t('keyword.explHealthy')}:</strong> « {form.keyword?.trim() || t('keyword.theKeyword')} » {expectPhrase(form.operator, Number(form.matchCount) || 0)} bulunmalı.</div>
          <div className="mt-1"><AlertTriangle size={12} aria-hidden="true" className="inline align-[-2px] text-destructive" /> <strong>{t('keyword.explAlarm')}:</strong> {triggerPhrase(form.operator, Number(form.matchCount) || 0, form.keyword)} tetiklenir.</div>
        </AlertBanner>
        <FormField label={t('keyword.keyword')} required>
          {({ id }) => <Input id={id} value={form.keyword} placeholder="SUCCESS" onChange={e => setForm(f => ({ ...f, keyword: e.target.value }))} />}
        </FormField>
        <CheckField checked={form.caseSensitive} onCheckedChange={v => setForm(f => ({ ...f, caseSensitive: v }))} label={t('keyword.caseSensitive')} className="self-center" />
        {/* Kurumsal vekil (2026-09-21): sertifika envanteriyle aynı karar */}
        <LabelSlot full>
          <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
            effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />
        </LabelSlot>
        <FormField label={t('keyword.name')}>
          {({ id }) => <Input id={id} value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />}
        </FormField>
        <FormField label={t('keyword.team')} required {...fe.fieldProps('teamId')}>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => { setForm(f => ({ ...f, teamId: v })); fe.clear('teamId') }} options={teamSelectOptions} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('keyword.noTeam')} disabled />}
        </FormField>
        <FormField full label={t('keyword.group')} required {...fe.fieldProps('groupName')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('groupName') }}
              options={[{ value: '', label: t('keyword.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('keyword.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="KEYWORD" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />

        {/* Etiketler */}
        <FormSection title={t('keyword.tagsTitle')} required {...fe.fieldProps('tags')} hint={t('keyword.tagsHint')}>
          <TagInput value={form.tags} onChange={v => { setForm(f => ({ ...f, tags: v })); fe.clear('tags') }} placeholder={t('keyword.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>

        {/* SSL + Domain kontrolleri */}
        <FormSection title={t('keyword.sslSectionTitle')} icon={ShieldCheck}>
          <CheckField checked={form.checkSslErrors} onCheckedChange={v => setForm(f => ({ ...f, checkSslErrors: v }))} label={t('keyword.checkSslErrors')} />
          <CheckField checked={form.sslExpiryReminders} onCheckedChange={v => setForm(f => ({ ...f, sslExpiryReminders: v }))} label={t('keyword.sslExpiryReminders')} />
          {form.sslExpiryReminders && (
            <InlineField label={t('keyword.reminderDays')}>
              {({ id }) => <Input id={id} className="h-8 w-36" value={form.sslReminderDays} placeholder="30,14,7" onChange={e => setForm(f => ({ ...f, sslReminderDays: e.target.value }))} />}
            </InlineField>
          )}
          <CheckField checked={form.domainExpiryReminders} onCheckedChange={v => setForm(f => ({ ...f, domainExpiryReminders: v }))} label={t('keyword.domainExpiryReminders')} />
          {form.domainExpiryReminders && (
            <InlineField label={t('keyword.reminderDays')}>
              {({ id }) => <Input id={id} className="h-8 w-36" value={form.domainReminderDays} placeholder="30,14,7" onChange={e => setForm(f => ({ ...f, domainReminderDays: e.target.value }))} />}
            </InlineField>
          )}
          <FormHint full={false}>{t('keyword.whoisHint')}</FormHint>
        </FormSection>

        {/* Gelişmiş ayarlar — açılır/kapanır (shadcn Collapsible; kapalıyken içerik DOM'da yok, eskisi gibi) */}
        <Collapsible open={advOpen} onOpenChange={setAdvOpen} className="min-w-0 rounded-lg border bg-muted/30 sm:col-span-2">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost"
              className="h-auto w-full justify-start gap-2 rounded-lg px-3.5 py-3 font-semibold hover:bg-muted/50">
              <ChevronDown size={16} aria-hidden="true"
                className={cn('text-muted-foreground transition-transform motion-reduce:transition-none', advOpen && 'rotate-180')} />
              {t('keyword.advanced')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent className="flex min-w-0 flex-col gap-3 px-3.5 pb-3.5">
            <FormSection boxed={false} title={t('keyword.timeoutTitle')} hint={timeoutText}>
              <Slider min={1} max={60} step={1} value={[timeoutSecs]}
                onValueChange={([v]) => setForm(f => ({ ...f, timeoutMs: v * 1000 }))}
                thumbProps={{ 'aria-label': t('keyword.timeoutTitle'), 'aria-valuetext': timeoutText }}
                className="py-1.5" />
            </FormSection>
            <CheckField checked={form.slowResponseEnabled} onCheckedChange={v => setForm(f => ({ ...f, slowResponseEnabled: v }))} label={t('keyword.slowEnable')} />
            {form.slowResponseEnabled && (
              <InlineField label={t('keyword.slowThreshold')}>
                {({ id }) => <Input id={id} type="number" min="100" step="100" className="h-8 w-36" value={form.slowThresholdMs} onChange={e => setForm(f => ({ ...f, slowThresholdMs: Number(e.target.value) }))} />}
              </InlineField>
            )}
            <FormHint full={false}>{t('keyword.slowHint')}</FormHint>
            <FormGrid>
              <FormField label={t('keyword.confirmAttempts')}>
                {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('keyword.confirmInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('keyword.recoveryChecks')}>
                {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('keyword.recoveryInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
            </FormGrid>
            <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('keyword.active')} />
            <FormHint full={false}>ⓘ {t('keyword.confirmHint')}</FormHint>
          </CollapsibleContent>
        </Collapsible>
      </FormGrid>

      {testResult && (
        <AlertBanner className="mt-3"
          tone={testResult.error ? 'danger' : testResult.condition_met ? 'success' : 'warning'}
          icon={testResult.error || !testResult.condition_met ? AlertTriangle : Check}
          title={testResult.error ? t('keyword.testError') : testResult.condition_met ? t('keyword.testMet') : t('keyword.testNotMet')}>
          {testResult.error
            ? testResult.error
            : <>
                {'« '}{form.keyword}{' » '}{testResult.occurrences} {t('keyword.testFound')} · {t('keyword.testRequired')}: {testResult.phrase}
                {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}
                {testResult.response_ms != null && <> · {testResult.response_ms}ms</>}
              </>}
        </AlertBanner>
      )}
      {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="keyword-change-note" value={changeNote} onChange={setChangeNote} />
      )}

      {/* Cache busting yardımı — formun İÇİNDE çizilir: ModalShell derinliği React ağacından okur, yardım
          penceresi formun (o da detayın içindeyse onun) ÜSTÜNDE katmanlanır; Escape yalnız yardımı kapatır. */}
      <ModalShell open={showCacheHelp} onClose={() => setShowCacheHelp(false)} icon={Target} size="md" hideClose
        title={t('keyword.cacheBustTitle')}
        footer={<Button variant="secondary" onClick={() => setShowCacheHelp(false)}>{t('sql.closeRowDetails')}</Button>}>
        <div className="px-0.5 py-1 text-sm leading-relaxed">
          <p className="mb-3">{t('keyword.cacheBustHint')}</p>
          <div className="whitespace-pre-line">{t('keyword.cacheBustExamples')}</div>
        </div>
      </ModalShell>
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader type="keyword" title={t('keyword.title')} subtitle={t('keyword.subtitle')}
        count={loading ? null : monitors.length} down={counts.down}
        refreshEvery={REFRESH_INTERVAL} refreshResetKey={loadNonce} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('keyword.addMonitor')} />

      <MonitorHowBox bullets={[t('keyword.how1'), t('keyword.how2'), t('keyword.how3')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {/* Kart görünümü (Kompakt / Zengin) araç çubuğunun İLK öğesi: mr-auto süzgeçleri sağda tutar; telefonda satır sarar */}
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          <SearchableSelect value={proxyFilter} onChange={setProxyFilter} options={proxyFilterOptions} ariaLabel={t('mon.proxy.label')} />
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('keyword.searchPlaceholder')} aria-label={t('keyword.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} data-page-search="" />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('keyword.noMonitorsAdmin') : t('keyword.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="KEYWORD"
          api={{ update: api.monitoring.updateKeywordMonitor, remove: api.monitoring.deleteKeywordMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22; vekil süzgeciyle görünür oldu) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Kart sunumu keyword/KeywordMonitorCard'da (MonitorCard ailesi, stretched button). Sayfaya ait kablolama
               yuva olarak geçer: toplu seçim kutusu (seçim kümesi burada), meta (zorlanmış vekil kipinde yol rozeti kip
               çipine bırakılır — metaRow) ve eylemler (yetki + işleyiciler burada). */
            <KeywordMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} density={density} running={isRunning(m.id)} status={statusKey(m)} badge={statusBadge(m)} onOpen={() => openDetail(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.url)} />
              )}
              meta={<MonitorCardMeta monitor={keywordMetaRow(m)} />}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={m.url}
                  running={isRunning(m.id)}
                  onCheck={canCheckRow(m) ? () => checkNow(m) : undefined} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('keyword.check')} editTitle={t('keyword.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('keyword.delete')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={statusKey(selected)} badge={statusBadge(selected)} title={selected.url} noc={{ type: 'KEYWORD', monitor: selected, canEdit: canManageRow(selected) }}
          actions={
            /* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
               koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
               kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
              running={isRunning(selected.id)}
              onCheck={canCheckRow(selected) ? () => checkNow(selected) : undefined}
              checkTitle={t('keyword.check')}
              onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
              editTitle={t('keyword.edit')}
              onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
              onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
              deleting={deleting === selected.id}
              deleteTitle={t('keyword.delete')}
              onClose={closeDetail}>
              {/* Uçtan uca tanılama (2026-10-04) — yalnız `can_diagnose` satırında; paylaşılan eylem grubuna çocuk olarak
                  (MonitorModalActions değişmedi — HTTP sayfasıyla aynı desen) */}
              {canDiagnoseRow(selected) && (
                <Button type="button" variant="outline" size="icon-sm" data-slot="kwdx-open"
                  className={cn(MON_ACT, 'pointer-coarse:size-10', MON_ACT_TONE.edit)}
                  onClick={() => openDiagnose(selected)} title={t('kwdx.open')} aria-label={t('kwdx.open')}>
                  <Stethoscope size={13} aria-hidden="true" />
                </Button>
              )}
              <CopyLinkButton iconOnly variant="outline" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-0" />
          <DetailSummary items={[
            { key: 'ok', value: summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—', label: t('keyword.sumOk'), hint: t('keyword.sumOkHint') },
            { key: 'total', value: summary.total, label: t('keyword.sumTotal'), hint: t('keyword.sumTotalHint') },
            { key: 'inc', value: summary.down, label: t('keyword.sumIncidents'), hint: t('keyword.sumIncidentsHint') },
            { key: 'kw', value: selected.keyword, label: t('keyword.keyword') },
            selected.http_status != null && { key: 'http', value: selected.http_status, label: 'HTTP' },
            selected.checked_at && { key: 'last', value: formatDateSec(selected.checked_at), label: t('keyword.lastCheck'), time: true },
          ]} />
          <DetailDivider />
          <DetailInfoCard title={t('keyword.reqSettings')} rows={[
            [t('keyword.cacheBusting'), <OnOff key="cb" on={!!selected.url?.includes('{timestamp}')} onText={t('keyword.cbOn')} offText={t('keyword.cbOff')} />],
            // Düz değer artık API'den GELMİYOR (şifreli). Yalnız varlık + ad listesi.
            [t('keyword.customHeadersShort'), <OnOff key="ch" on={!!selected.has_custom_headers}
              onText={(selected.custom_header_names || []).filter(Boolean).join(', ') || t('keyword.customHeadersSet')}
              offText={t('keyword.none')} />],
          ]} />
          <DetailTabs value={detailTab} onValueChange={setDetailTab}
            countsFor={{ kind: 'keyword', monitorId: selected.id, notesType: 'KEYWORD', notesTarget: selected.url, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['control', t('hist.tab')], ['alerts', t('keyword.tabAlerts')], ['chart', t('keyword.tabChart')],
              ['notes', t('keyword.tabGuide')],
              // Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
              // orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            <TabsContent value="control">
              {/* Hata teşhisi (2026-10-04): başarısız satırda neden rozeti + tek satır açıklama + aç/kapa; açılınca satırın
                  ALTINDA tam genişlik Neden / Etkisi / Ne yapmalı + ayrıntılar + ipuçları + alıntı + "Bu kontrolü tanıla".
                  Tablo ↔ kart seçimi KAP genişliğine göre (detay penceresi dar olabilir); başarılı satır eskisi gibi. */}
              <CheckHistoryTab kind="keyword" monitorId={selected.id} listKey="keyword-history" reloadSignal={histReload}
                cardsBelow={HISTORY_CARDS_BELOW}
                columns={[t('keyword.colTime'), t('keyword.colStatus'), 'HTTP', t('keyword.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                renderRow={(c) => {
                  const occ = c.occurrences != null ? c.occurrences : (c.found ? '≥1' : 0)
                  const cmp = `${OP_SYM[selected.operator] || '≥'}${selected.match_count ?? 1}`
                  const bad = c.ok === false
                  const open = bad && openChecks.has(c.id)
                  const when = formatDateSec(c.checked_at)
                  return (<>
                    <span className="upt-rt-time">{when}</span>
                    <span className={c.ok ? 'upt-rt-up' : 'upt-rt-down'}>{c.ok ? t('keyword.statusOk') : (c.error ? t('keyword.statusError') : t('keyword.statusViolation'))}</span>
                    <span className="upt-rt-ms">{c.http_status ?? '—'}</span>
                    {bad
                      ? <KeywordFailureCell check={c} monitor={selected} open={open} when={when} onToggle={() => toggleCheck(c.id)} />
                      : <span className="whitespace-nowrap"
                          title={`« ${selected.keyword} » → ${occ} ${t('keyword.testFound')} · ${t('keyword.testRequired')}: ${cmp} (${expectPhrase(selected.operator, selected.match_count ?? 1)})${c.snippet ? '\n— ' + c.snippet : ''}`}>
                          <strong>{occ}</strong> {t('keyword.testFound')} <span className="text-muted-foreground">· {cmp}</span>
                        </span>}
                    {open && (
                      <KeywordFailurePanel check={c} monitor={selected} canDiagnose={canDiagnoseRow(selected)}
                        onDiagnose={() => openDiagnose(selected)} />
                    )}
                  </>)
                }} />
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.url} types={alertTypesFor('keyword')} /></TabsContent>

            <TabsContent value="chart">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="keyword" slowThreshold={selected.slow_response_enabled ? selected.slow_threshold_ms : null} />
              </Suspense>
            </TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="KEYWORD" target={selected.url} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="keyword" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

          {/* Uçtan uca tanılama penceresi (2026-10-04) — detayın İÇİNDE: iç içe kabuk, Escape yalnız onu kapatır */}
          {kwDx && kwDx.monitorId === selected.id && canDiagnoseRow(selected) && (
            <Suspense fallback={null}>
              <KeywordDiagnoseDialog monitor={selected} initialRunId={kwDx.initialRunId}
                onRunChange={(runId) => setKwDx((cur) => (cur ? { ...cur, runId } : cur))}
                onClose={() => setKwDx(null)} />
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
          storageKey="sm.checkRun.teams.keyword"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="keyword"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
