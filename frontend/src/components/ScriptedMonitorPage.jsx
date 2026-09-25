import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { formatPercent } from '../i18n/dateLocale.js'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import CodeEditor from './ui/CodeEditor.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import TagInput from './ui/TagInput.jsx'
import ScriptedTemplateInfo from './scripted/ScriptedTemplateInfo.jsx'
import { useScriptedTemplates } from '../hooks/useScriptedTemplates.js'
import { buildScriptSourceOptions, resolveTemplate } from '../utils/scriptSourceOptions.js'
import { FlaskConical, Play, Plus, Trash2, RefreshCw, Eye, EyeOff, AlertTriangle, LayoutDashboard, CheckCircle2, WifiOff, Siren, BellDot, PauseCircle, ChevronDown, Terminal, FileCode2 } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import { collectK6Markers } from '../utils/k6Errors.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import CheckAllButton from './check/CheckAllButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import ScriptedVersionsTab from './scripted/ScriptedVersionsTab.jsx'
import ScriptedTemplatesTab from './scripted/ScriptedTemplatesTab.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { monitorDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock, Spinner } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { CheckRunningStrip } from './ui/CheckRunning.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import CopyButton from './ui/CopyButton.jsx'
import { exitLabel, exitHint, diagnosisHint, k6SyntaxLevel, readPhases, formatBytes,
  checksSummary, stuckLabel } from './scriptedExitCodes.js'
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { Button } from '@/components/shadcn/button'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin.
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

// Ortak IntervalSlider `labelKey` bekliyor; senaryo kodu calistiran bu turde taban 1 dk
// (her kosum bir alt surec baslatir) — ust sinir digerleriyle hizalandi.
const INTERVALS = [
  { value: 60,    labelKey: 'notify.iv1m'  },
  { value: 300,   labelKey: 'notify.iv5m'  },
  { value: 600,   labelKey: 'notify.iv10m' },
  { value: 900,   labelKey: 'notify.iv15m' },
  { value: 1800,  labelKey: 'notify.iv30m' },
  { value: 3600,  labelKey: 'notify.iv1h'  },
  { value: 43200, labelKey: 'notify.iv12h' },
  { value: 86400, labelKey: 'notify.iv24h' },
]
function intervalIdx(secs) {
  let idx = 0, best = Infinity
  INTERVALS.forEach((iv, i) => { const d = Math.abs(iv.value - secs); if (d < best) { best = d; idx = i } })
  return idx
}
const REFRESH_INTERVAL = 60

const emptyForm = {
  name: '', description: '', groupName: '', notificationGroupId: '', teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  intervalSeconds: 300, timeoutSeconds: 60, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true, script: '', env: [], template: '',
  slowResponseEnabled: false, slowThresholdMs: 15000,
  useProxy: 'AUTO',
}

/** Otomatik taslak aralıkları — WeeklyReportsPage ile aynı büyüklük sınıfı (1,5 sn yazım sonrası, periyodik ağ yazımı). */
const DRAFT_DEBOUNCE_MS = 1500
const DRAFT_INTERVAL_MS = 30000

const STATUS_COLOR = { PASS: '#16a34a', FAIL: '#d97706', ERROR: '#dc2626', TIMEOUT: '#b45309', NO_CHECKS: '#d97706', unknown: '#a1a1aa' }
function statusLabel(t, s) { return t(`scripted.status_${s || 'unknown'}`) }
/** PASS/FAIL/ERROR/TIMEOUT/NO_CHECKS alfabesi → kanonik up/down eşlemesi (upt-card/upt-badge aileleri). */
function isPass(s) { return s === 'PASS' }
function isFailLike(s) { return s === 'FAIL' || s === 'ERROR' || s === 'TIMEOUT' }
/**
 * NO_CHECKS = koştu ama hiçbir şey doğrulanmadı. Bilinçli olarak isFailLike'a KONMADI:
 * arıza değil yapılandırma kusurudur, alarm üretmez ve "down" sayaçlarını/filtresini şişirmemeli.
 */
function isWarnLike(s) { return s === 'NO_CHECKS' }
/**
 * Ortamdaki k6 sürümü — kullanıcı script'i HANGİ motora yazdığını bilmeli.
 *
 * Sürüm bilgisi API'den (`k6_version`) uzun süredir geliyordu ama yalnız hata sonrası tanı
 * ipucunda kullanılıyordu; kullanıcı script'i yazarken göremiyordu. Eski motorda (k6 < 0.53,
 * gömülü Babel 6) `?.`, `??` ve `{...nesne}` "Unexpected token" verir — bunu yazmadan önce
 * söylemek, patladıktan sonra söylemekten kıyasla çok daha ucuz.
 *
 * Sürüm okunamıyorsa hiç render edilmez: yanlış sözdizimi tavsiyesi vermektense sessiz kal.
 */
function K6VersionBadge({ t, version, withSyntaxNote = false }) {
  const level = k6SyntaxLevel(version)
  if (!version) return null
  return (
    <div className="sc-k6ver">
      <span className="sc-k6ver-chip" title={t('scripted.k6VersionTitle')}>
        <Terminal size={11} aria-hidden="true" />k6 {version}
      </span>
      {withSyntaxNote && level && (
        <span className={`sc-k6ver-note${level === 'legacy' ? ' sc-k6ver-note--warn' : ''}`}>
          {level === 'legacy' ? t('scripted.k6LegacySyntax') : t('scripted.k6ModernSyntax')}
        </span>
      )}
    </div>
  )
}

/** Backend çok satırlı hata döndürdüyse bu bir k6/Babel kod çerçevesidir (hizalı caret taşır). */
function isCodeFrame(error) { return typeof error === 'string' && error.includes('\n') }

/** Çok satırlı hata metninin İLK satırı — tablo hücresi için. Tam metin `title`'da ve panelde. */
function firstLine(error) { return String(error ?? '').split('\n')[0] }

/**
 * Sayısal form alanlarının sınırları — TEK kaynak (girdi nitelikleri + kaydetme denetimi).
 * Backend karşılıkları: timeout `max(5,min(180,n))`, confirm `max(0,min(10,n))`,
 * recovery `max(1,min(20,n))`.
 */
export const SCRIPTED_NUM_FIELDS = [
  { key: 'timeoutSeconds', min: 5, max: 180, labelKey: 'scripted.timeout' },
  { key: 'confirmAttempts', min: 0, max: 10, labelKey: 'scripted.confirmAttempts' },
  { key: 'recoveryChecks', min: 1, max: 10, labelKey: 'scripted.recoveryChecks' },
  // Yavaslik esigi YALNIZ alarm acikken zorunlu: kapaliyken bos/gecersiz deger kaydetmeyi bloklamamali.
  { key: 'slowThresholdMs', min: 500, max: 180000, labelKey: 'scripted.slowThreshold',
    when: f => !!f.slowResponseEnabled },
]

/**
 * Kaydetmeden önce sayısal alan denetimi.
 *
 * Neden gerekli: `<input type="number">` boşaltılınca `e.target.value === ''` olur ve
 * `Number('')` **0** verir. `min`/`max` nitelikleri hiçbir şey yapmaz (bu bir `<form>` değil,
 * Kaydet `type=submit` değil, `checkValidity()` çağrılmıyor). Sonuç sessiz veri kaybıydı:
 * en kötüsü zaman aşımını boş bırakmak — backend `max(5,…)` ile **5 saniyeye** çekiyor, 60
 * saniyelik monitör her koşumda TIMEOUT veriyor ve gece alarm yağıyor. Tavan aşımında (999)
 * da kullanıcıya geri bildirim yoktu, değer sessizce kırpılıyordu.
 *
 * @returns {{key:string, labelKey:string, min:number, max:number}|null} ilk geçersiz alan
 */
export function invalidNumericField(form) {
  for (const f of SCRIPTED_NUM_FIELDS) {
    if (f.when && !f.when(form || {})) continue
    const raw = form?.[f.key]
    if (raw === '' || raw == null) return f
    const n = Number(raw)
    if (!Number.isFinite(n) || n < f.min || n > f.max) return f
  }
  return null
}

/**
 * Kontrol Geçmişi gruplama imzası (CheckHistoryTab `rowSignature`).
 *
 * Yalnız BAŞARISIZ satırlar gruplanır — PASS satırlarını katlamak normal zaman çizgisini gizlerdi.
 * Sebep olarak hatanın İLK satırı yeterli: kod çerçevesinin tamamı aynıysa ilk satır da aynıdır,
 * farklı bir hataysa zaten ilk satırda ayrışır.
 *
 * Modül düzeyinde tanımlı (bileşen içinde arrow DEĞİL): `rowSignature` CheckHistoryTab'ın satır
 * useMemo'sunun bağımlılığı; her render'da yeni bir referans üretmek memo'yu boşa çıkarırdı.
 */
function scriptedRowSignature(c) {
  if (!c || isPass(c.status)) return null
  const first = String(c.error || '').split('\n')[0].slice(0, 200)
  return `${c.status}|${c.exit_code ?? ''}|${first}`
}

export default function ScriptedMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const isAdminish = isAdmin || isTeamAdmin            // form/team-select davranışı (mevcut semantik korunur)
  const myTeam = teamId != null ? String(teamId) : null
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  const { canPickTeam, pickTeams, isOwnTeam } = useMonitorTeamPick({ isAdmin: isAdminish, adminTeams: teams, myTeams, teamId })   // 2026-09-18

  const sparks = useSparklines('scripted')   // kart mini trendi (2026-09-12)
  const sla = useSla('scripted')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const [monitors, setMonitors] = useState([])
  // Şablon kütüphanesi (Genel + takım). Yükleme hatası sayfayı DÜŞÜRMEZ: liste boş kalsa bile
  // script'i elle yazmak her zaman mümkün olmalı.
  const { templates: scriptTemplates } = useScriptedTemplates()
  const { canView } = usePermissions()
  // Sayfa içi görünüm: monitör listesi ↔ şablon kütüphanesi. `?tab=` whitelist'ine DOKUNULMAZ —
  // bu sayfanın kendi iç durumudur, monitör detay sekmeleriyle karışmaz.
  const [view, setView] = useState('monitors')
  const canViewTemplates = canView('monitoring.scripted_templates')
  const [k6, setK6] = useState({ available: true, version: null, canManage: false })
  // Kaydetme sonrası doğrulama koşumunun durumu: null | {state:'running'|'queued'|'skipped'|'cooldown'}
  const [smoke, setSmoke] = useState(null)
  // Kurumsal vekilin ETKİN durumu — düzenleme formunda "bu ayarla gerçekte ne olacak" notu için.
  const [proxy, setProxy] = useState(null)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const [modal, setModal] = useState(null)      // create/edit form monitor (or {} for new)
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (server-scoped, sızıntısız)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [defaults, setDefaults] = useState(null)     // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [saveWarnings, setSaveWarnings] = useState([])   // kaydetme sonrası engellemeyen uyarılar
  // Kaydetmeyi ENGELLEYEN sözdizimi hatası — kalıcı gösterilir ve satırı cetvelde işaretlenir.
  const [saveError, setSaveError] = useState(null)
  // Tek kimlik yerine KUME: uzun suren bir kosum digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [selected, setSelected] = useState(null) // detail monitor
  useEscapeKey(!!selected, closeDetail)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [detailTab, setDetailTab] = useState('control')
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)
  const [selCheck, setSelCheck] = useState(null)
  const deepLinkDone = useRef(false)
  // ── Otomatik taslak ──
  const [drafts, setDrafts] = useState([])            // kullanıcının sunucudaki taslakları
  const [pendingDraft, setPendingDraft] = useState(null)   // açık monitör için "yükle?" teklifi
  const [draftSavedAt, setDraftSavedAt] = useState(null)   // "✓ taslak kaydedildi HH:MM"
  const [bumpType, setBumpType] = useState('patch')
  // Zamanlayıcıların GÜNCEL state'i okuyabilmesi için canlı referans (WeeklyReportsPage deseni:
  // setInterval closure'ı ilk render'ın state'ini görür, taslak eski içerikle kaydedilirdi).
  const liveRef = useRef({})

  // Satır-bazlı yetki: global monitoring.scripted izni (can_manage) + takım sahipliği (kanonik desen).
  const canManageRow = (m) => k6.canManage && (isAdmin || isOwnTeam(m))
  // k6 kurulu değilse aday YOK → düğme hiç çizilmez; kartın checkDisabled={!k6.available}
  // kapısıyla aynı sonuç, basılıp 4xx yiyen bir düğme gösterilmiyor.
  const canCheckRow = (m) => k6.available && canManageRow(m)
  const canDeleteRow = (m) => k6.canManage && (isAdmin || (isTeamAdmin && isOwnTeam(m)))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })


  // Diger 8 izleme sayfasinda hata dali VARDI, bu sayfada ve UptimePage'de HIC yoktu:
  // `if (res?.success)` basarisizken yalniz setLoading(false) kosuyor, monitors bos kaliyor ve
  // ekran "Henuz sentetik izleme yok, ekleyin" diyordu — kullanici izlemelerinin SILINDIGINI
  // saniyordu (HttpMonitorPage'de yorumla belgelenmis hatanin kopyaya tasinmamis hali).
  const [loadError, setLoadError] = useState(null)

  const load = useCallback(async () => {
    // AG HATASI DA BU DALA DUSMELI: request() ag hatasinda {success:false} DONDURMEZ, throw eder
    // ve timeoutMs verilmedigi icin abort yolu da devrede degil.
    try {
      const res = await api.monitoring.getScriptedMonitors()
      if (res?.success) {
        const d = res.data || {}
        setMonitors(d.monitors || [])
        setK6({ available: d.k6_available !== false, version: d.k6_version, canManage: !!d.can_manage })
        setProxy({ configured: !!d.proxy_configured, noProxy: d.no_proxy || '' })
        setLoadError(null)
      } else setLoadError(res?.error || 'load failed')
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
    concurrency: CHECK_CONCURRENCY_BY_TYPE.scripted,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da durur

  const loadDrafts = useCallback(async () => {
    if (!k6.canManage) return
    const res = await api.monitoring.getScriptedDrafts?.()
    if (res?.success) setDrafts(res.data?.drafts || [])
  }, [k6.canManage])
  useEffect(() => { loadDrafts() }, [loadDrafts])

  /** Hiç kaydedilmemiş monitörün taslağı — sayfa üstündeki "devam et" şeridini besler. */
  const newDraft = useMemo(() => drafts.find(d => d.monitor_key === 'new') || null, [drafts])

  // Zamanlayıcıların closure'ı ilk render'ın state'ini görür; canlı referans her render'da tazelenir.
  liveRef.current = { form, modal, saving, pendingDraft }

  // 1) Yazmayı bırakınca 1,5 sn sonra taslak (WeeklyReportsPage yerel-yedek aralığı).
  useEffect(() => {
    if (!modal) return
    const id = setTimeout(() => { if (isFormDirty()) writeDraft() }, DRAFT_DEBOUNCE_MS)
    return () => clearTimeout(id)
    // form.script/name dışındaki alanlar da taslağa girer ama tetikleyici bunlar: asıl kaybolan içerik.
  }, [modal, form.script, form.name])

  // 2) Uzun düzenlemelerde ağ/oturum kopsa bile ilerlemenin kaybolmaması için periyodik yazım.
  useEffect(() => {
    if (!modal) return
    const id = setInterval(() => { if (isFormDirty() && !liveRef.current.saving) writeDraft() }, DRAFT_INTERVAL_MS)
    return () => clearInterval(id)
  }, [modal])

  // 3) Sekme/pencere kapanışı: fetch iptal edilir, bu yüzden sendBeacon (WeeklyReportsPage deseni).
  //    Tarayıcı "emin misiniz?" diyaloğu BİLİNÇLİ olarak gösterilmez — sessizce saklayıp geçiyoruz.
  useEffect(() => {
    if (!modal) return
    const onUnload = () => {
      if (!isFormDirty()) return
      try {
        const body = JSON.stringify({
          monitorKey: liveRef.current.modal?.id ? String(liveRef.current.modal.id) : 'new',
          monitorName: (liveRef.current.form?.name || '').trim() || null,
          formJson: draftPayload(),
        })
        navigator.sendBeacon?.('/api/monitoring/scripted/draft', new Blob([body], { type: 'application/json' }))
      } catch { /* yoksay */ }
    }
    window.addEventListener('beforeunload', onUnload)
    return () => window.removeEventListener('beforeunload', onUnload)
  }, [modal])

  useEffect(() => { if (isAdminish) api.admin.getTeams?.().then(r => setTeams(r?.success ? r.data || [] : [])) }, [isAdminish])

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.scripted) })
  }, [])

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'scripted').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])

  // Deep-link: ?monitor=<id> → detay modalını aç (bir kez) — diğer izleme türleriyle parite.
  useEffect(() => {
    if (deepLinkDone.current || monitors.length === 0) return
    deepLinkDone.current = true
    const id = readUrlParam('monitor', null)
    if (!id) return
    const m = monitors.find(x => String(x.id) === String(id))
    if (m) openDetail(m)
  }, [monitors])  

  function openDetail(m) { setSelected(m); setSelCheck(null); setSummary({ total: 0, down: 0 }); setDetailTab('control') }
  function closeDetail() { setSelected(null); setSelCheck(null) }

  // Türetilmiş listeler memoize — 1sn countdown her saniye render tetikler.
  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => pickTeams.map(tm => ({ value: String(tm.id), label: tm.name })), [pickTeams])
  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])

  /** Modalın script'ini ALDIĞI kayıtlı monitör (düzenlemede kendisi, kopyalamada kaynak). */
  const savedSource = modal?.id ? modal : dupSource
  /**
   * Seçicideki "Kayıtlı script'ler" grubu — sayfada zaten yüklü listeden türetilir, ek API yok.
   * Liste backend'de görme yetkisiyle süzülü olduğundan başka takımın script'i sızmaz.
   * Sıra: düzenlenen/kopyalanan monitör başta (kullanıcı kendi script'ini aramasın), sonra ada göre.
   */
  const savedScripts = useMemo(() => {
    const withScript = monitors.filter(m => {
      if (!(m.script || '').trim()) return false
      // Düzenlenen/kopyalanan monitörün KENDİ girdisi daima kalır: seçicinin geçerli değeri odur,
      // listeden düşerse seçim boş görünür. (Zaten editörde açık — ek bir görünürlük vermez.)
      if (m.id === savedSource?.id) return true
      // BAŞKA takımların script'leri hiç listelenmez — ADMIN olsa bile.
      return isOwnTeam(m)   // ikincil takımlar da "kendi takımı" (2026-09-18)
    })
    return [...withScript].sort((a, b) => {
      if (a.id === savedSource?.id) return -1
      if (b.id === savedSource?.id) return 1
      return (a.name || '').localeCompare(b.name || '')
    })
  }, [monitors, savedSource, isOwnTeam])
  // Gruplar takıma özgü: kullanıcı yalnız kendi takımının gruplarını görür/seçer (admin tümünü).
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('scripted.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('scripted.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])

  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    const q = search.trim().toLowerCase()
    if (!q) return true
    return (m.name || '').toLowerCase().includes(q) || (m.group_name || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, tagFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, up: 0, down: 0, alarm: 0, unacked: 0, paused: 0 }
    for (const m of scoped) {
      if (isPass(m.status)) c.up++
      else if (isFailLike(m.status)) c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
      if (m.active === false) c.paused++
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      up:      m => isPass(m.status),
      down:    m => isFailLike(m.status),
      alarm:   m => m.active_alarm,
      unacked: m => m.active_alarm && !m.alarm_acknowledged,
      paused:  m => m.active === false,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  const pager = usePagination(displayMonitors, {
    listKey: 'scripted-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })
  // Paylaşılabilir URL: filtre/arama/sayfa + açık detay modalı adres çubuğunda yaşar (varsayılanlar param üretmez).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, search, statFilter, pager }),
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('scripted.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('scripted.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: WifiOff,         label: t('scripted.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'alarm',   Icon: Siren,           label: t('scripted.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('scripted.dashUnacked'), value: counts.unacked, cls: 'warning'  , hint: t('mondash.unackedHint') },
    { key: 'paused',  Icon: PauseCircle,     label: t('scripted.dashPaused'),  value: counts.paused,  cls: 'paused'   },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  function cardClass(m) {
    if (isPass(m.status)) return 'upt-card--up'
    if (isFailLike(m.status)) return 'upt-card--down'
    if (isWarnLike(m.status)) return 'upt-card--warn'
    return 'upt-card--unknown'
  }
  function statusBadge(m) {
    const s = m?.status
    const cls = isPass(s) ? 'upt-badge--up' : isFailLike(s) ? 'upt-badge--down'
      : isWarnLike(s) ? 'upt-badge--warn' : 'upt-badge--unknown'
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{statusLabel(t, s)}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('scripted.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  /**
   * Bir monitör satırının SON KONTROLÜNÜ sonuç paneline uygun şekle çevirir.
   *
   * Alanlar liste yanıtında zaten var (enrichScripted) — ek API çağrısı yok. `_source` panelin
   * "bu sonuç nereden geldi" etiketini besler: kullanıcı az önce test mi koşturduğunu yoksa
   * kayıtlı script'in son kontrolüne mi baktığını karıştırmasın.
   *
   * Hiç koşmamış monitörde (status 'unknown' / alanlar yok) null döner → panel açılmaz.
   */
  function lastCheckResult(m) {
    if (!m || !m.checked_at || !m.status || m.status === 'unknown') return null
    return {
      status: m.status, error: m.error, exit_code: m.exit_code, output_tail: m.output_tail,
      checks_passed: m.checks_passed, checks_failed: m.checks_failed, duration_ms: m.duration_ms,
      _source: 'lastCheck', _checkedAt: m.checked_at, _monitorName: m.name,
    }
  }

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdminish ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutSeconds: defaults?.timeoutSeconds ?? emptyForm.timeoutSeconds })
    setTestResult(null); setSaveWarnings([]); setSaveError(null); setDupSource(null); setModal({})
    setDraftSavedAt(null); setBumpType('patch')
    // Yarım kalmış "new" taslağı VARSA sorulur — openEdit ile aynı sözleşme. Eskiden hiç
    // sorulmuyordu: form doğar doğmaz 1,5 sn'lik otomatik yazım aynı 'new' anahtarına basıp
    // saatlerce yazılmış yarım script'i geri dönülmez biçimde eziyordu.
    setPendingDraft(newDraft || null)
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return {
      name: m.name || '', description: m.description || '', groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
      teamId: m.team_id != null ? String(m.team_id) : '', tags: m.tags || '', notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false,
      intervalSeconds: m.interval_seconds ?? 300, timeoutSeconds: m.timeout_seconds ?? 60,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      slowResponseEnabled: !!m.slow_response_enabled, slowThresholdMs: m.slow_threshold_ms ?? 15000,
      active: m.active !== false, script: m.script || '',
      useProxy: m.use_proxy || 'AUTO',
      // env: secret satırlar value_set taşır (değer geri okunamaz); non-secret value taşır
      env: (m.env || []).map(e => ({ name: e.name, secret: !!e.secret, value: e.secret ? '' : (e.value || ''), value_set: !!e.value_set })),
    }
  }
  function openEdit(m) {
    // Seçicide monitörün KENDİ girdisi seçili gelir ve panel son kontrolüyle dolar: kullanıcı
    // neyi düzeltmesi gerektiğini modal açılır açılmaz görür (eskiden panel boş açılıyordu).
    setForm({ ...formFrom(m), template: `saved:${m.id}` })
    setTestResult(lastCheckResult(m)); setSaveWarnings([]); setSaveError(null); setDupSource(null); setModal(m)
    setDraftSavedAt(null); setBumpType('patch')
    // Kaydedilmemiş taslak varsa OTOMATİK uygulanmaz — kullanıcıya sorulur; aksi halde
    // kaydedilmiş sürümün üstüne sessizce eski bir taslak biner.
    setPendingDraft(drafts.find(d => d.monitor_key === String(m.id)) || null)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli.
   *  GİZLİ env değerleri geri okunamadığından kopyaya taşınamaz — value_set=false yapılır ki
   *  kullanıcı bu satırları yeniden doldurması gerektiğini görsün. Mükerrer koruması backend'de (ad+takım). */
  function openDuplicate(m) {
    const base = formFrom(m)
    setForm({ ...base, name: duplicateName(m.name), template: `saved:${m.id}`,
      env: base.env.map(e => e.secret ? { ...e, value: '', value_set: false } : e) })
    // Kopya kaynağın script'iyle doğar → panel de kaynağın son kontrolünü gösterir (aynı script).
    setTestResult(lastCheckResult(m)); setSaveWarnings([]); setSaveError(null); setDupSource(m); setModal({})
    setDraftSavedAt(null); setBumpType('patch')
    setPendingDraft(newDraft || null)   // bkz. openNew — kopya da 'new' anahtarını kullanıyor
  }
  /**
   * @param skipDraft Kapanışta taslak YAZILMASIN. Kaydetme ve silme sonrası ŞART: React state
   *   güncellemeleri asenkron olduğu için `setModal(...)` ile tazelenen taslak tabanı bu satırda
   *   henüz görünmez; bayrak olmadan `flushDraft()` backend'in az önce sildiği taslağı yeniden
   *   yazar (ya da silinmiş monitör için erişilemez bir yetim taslak bırakır).
   */
  function closeEdit({ skipDraft = false } = {}) {
    // Kapanışta son bir taslak yazımı: kullanıcı "İptal" dese bile yazdıkları kaybolmasın —
    // taslak kaydı MONİTÖRÜ DEĞİŞTİRMEZ, yalnız kaldığı yeri saklar.
    if (!skipDraft) flushDraft()
    setModal(null); setTestResult(null); setDupSource(null); setSaveWarnings([]); setSaveError(null)
    setPendingDraft(null); setDraftSavedAt(null); setBumpType('patch'); setSmoke(null)
  }

  // ── Otomatik taslak: anahtar, yazma, yükleme ─────────────────────────────


  /** Formun kaydedilebilir hâli — secret env DEĞERLERİ taslağa YAZILMAZ (düz metin saklanmasın). */
  function draftPayload() {
    const f = liveRef.current.form || form
    const safe = { ...f, env: (f.env || []).map(e => (e.secret ? { ...e, value: '' } : e)) }
    return JSON.stringify(safe)
  }

  /** Sunucuya taslak yaz (sessiz: otomatik kayıt kullanıcıya hata kusmamalı). */
  async function writeDraft() {
    if (!liveRef.current.modal || !k6.canManage) return
    // Kullanıcıya "kaydedilmemiş taslağınız var, yükleyeyim mi?" diye sorduk ve HENÜZ cevap
    // vermedi: bu aralıkta yazmak, sorduğumuz taslağın ta kendisini ezer. Kullanıcı "Yükle" ya
    // da "Sil" dediğinde teklif düşer ve otomatik kayıt kaldığı yerden devam eder.
    if (liveRef.current.pendingDraft) return
    try {
      const res = await api.monitoring.saveScriptedDraft({
        monitorKey: liveRef.current.modal?.id ? String(liveRef.current.modal.id) : 'new',
        monitorName: (liveRef.current.form?.name || '').trim() || null,
        formJson: draftPayload(),
      })
      if (res?.success) setDraftSavedAt(new Date())
    } catch { /* çevrimdışı/oturum düşmüş — taslak bir sonraki turda yeniden denenir */ }
  }

  /** Kapanış/ayrılış anında son yazım (bekleyen debounce'ı beklemeden). */
  function flushDraft() {
    if (!modal) return
    if (!isFormDirty()) return
    writeDraft()
  }

  /** Boş formu taslak diye kaydetmeyelim: yalnız script ya da ad girilmişse anlamlı. */
  function isFormDirty() {
    const f = liveRef.current.form || form
    if (modal?.id) return f.script !== (modal.script || '') || f.name !== (modal.name || '')
    return !!(f.script || '').trim() || !!(f.name || '').trim()
  }

  /** Taslağı forma uygula (hem "devam et" şeridi hem modal içindeki "yükle" düğmesi). */
  function applyDraft(draft) {
    try {
      const parsed = JSON.parse(draft.form_json || '{}')
      setForm(f => ({ ...emptyForm, ...f, ...parsed }))
      setPendingDraft(null)
      toast.success(t('scripted.draftRestore'))
    } catch { toast.error(t('scripted.saveError')) }
  }

  /**
   * Taslağı sil — SUNUCU onaylamadan "silindi" DEME.
   *
   * Yaşanan hata: yanıt hiç okunmadığı için sunucudaki silme düşse bile başarı bildirimi çıkıyor,
   * şerit yerel state'ten kalkıyor, sonraki açılışta taslak geri geliyordu ("sildim ama duruyor").
   * Artık yalnız `success` gelirse yerel liste temizlenir; aksi halde şerit yerinde kalır ve hata
   * görünür olur.
   */
  async function discardDraft(key) {
    let ok = false
    try { ok = !!(await api.monitoring.deleteScriptedDraft?.(key))?.success } catch { ok = false }
    if (!ok) { toast.error(t('scripted.draftDiscardError')); return }
    setPendingDraft(null)
    setDrafts(d => d.filter(x => x.monitor_key !== key))
    toast.success(t('scripted.draftDiscarded'))
  }

  /** "Devam et": yeni-monitör taslağını boş forma yükleyip modalı açar. */
  function continueDraft(draft) {
    openNew()
    setTimeout(() => applyDraft(draft), 0)   // openNew formu sıfırladıktan SONRA uygula
  }

  function setEnvRow(i, patch) { setForm(f => ({ ...f, env: f.env.map((e, j) => j === i ? { ...e, ...patch } : e) })) }
  function addEnvRow() { setForm(f => ({ ...f, env: [...f.env, { name: '', secret: false, value: '' }] })) }
  function delEnvRow(i) { setForm(f => ({ ...f, env: f.env.filter((_, j) => j !== i) })) }

  /**
   * Script kaynağı seçimi — TEK giriş noktası (`saved:<id>` | `tpl:<id>` | '').
   *
   * Kural: panelde görünen sonuç DAİMA editördeki script'e ait olmalı. Eskiden şablon değişince
   * panel aynen kalıyor ve artık editörde olmayan bir script'in hatasını gösteriyordu; kaydetme
   * uyarıları da aynı şekilde bayatlıyordu. Bu yüzden her seçim değişiminde ikisi de yenilenir:
   *   - kayıtlı script → o monitörün son kontrolü panele konur (hata/çıkış kodu geri gelir)
   *   - şablon        → panel temizlenir (şablonun koşum geçmişi yoktur)
   * Elle yazım paneli ETKİLEMEZ (bilinçli): kullanıcı hatayı okurken düzeltme yapabilsin.
   */
  function selectScriptSource(value) {
    setSaveWarnings([]); setSaveError(null)
    if (!value) {                                   // yalnız yeni monitörde gösterilir
      setForm(f => ({ ...f, template: '', script: '', env: [] }))
      setTestResult(null)
      return
    }
    if (value.startsWith('saved:')) {
      const src = monitors.find(x => String(x.id) === value.slice(6))
      if (!src) return
      const base = formFrom(src)
      setForm(f => ({ ...f, template: value, script: base.script, env: base.env }))
      setTestResult(lastCheckResult(src))
      return
    }
    setForm(f => ({ ...f, template: value }))
    applyTemplate(value.slice(4))
    setTestResult(null)
  }

  /**
   * `tpl:<token>` seçimini forma uygular.
   *
   * Şablon env TANIMI taşır, DEĞER taşımaz — `value: ''` bilinçli: gizli bilgiyi kullanıcı
   * kendi girer, kütüphane asla taşımaz. Şablon satırı silinmişse sessizce hiçbir şey yapılmaz
   * (form kullanıcının yazdığını korur).
   */
  async function applyTemplate(token) {
    const tpl = resolveTemplate(scriptTemplates, token)
    if (!tpl) return
    // Env TANIMLARI liste yanıtında GELİR, script GÖVDESİ gelmez (yüzlerce şablonda yanıt
    // şişmesin diye bilinçli). Gövde tekil uçtan çekilir — yoksa şablon seçmek env'i doldurup
    // editörü boş bırakırdı ve kullanıcı "şablon çalışmıyor" derdi.
    setForm(f => ({
      ...f,
      env: (tpl.env || []).map(e => ({ name: e.name, secret: !!e.secret, value: '' })),
    }))
    let script = tpl.script
    if (!script) {
      const res = await api.monitoring.getScriptedTemplate(tpl.id)
      if (!res?.success) { toast.error(res?.error || t('tpl.loadError')); return }
      script = res.data?.script || ''
    }
    // Geç dönen gövde ARTIK seçili olmayan şablona aitse yazma: kullanıcı beklerken başka bir
    // kaynağa (kayıtlı script / boş) geçmiş olabilir — o seçimin script'ini ezmek veri kaybıdır.
    setForm(f => (f.template === `tpl:${token}` ? { ...f, script: script || f.script } : f))
  }

  /** Şablon kütüphanesinden "Bu şablonla monitör oluştur": monitör görünümüne dön ve formu doldur. */
  function startMonitorFromTemplate(row) {
    setView('monitors')
    openNew()
    // openNew formu sıfırlar; şablon uygulaması bir sonraki tick'te (sürüm yükleme deseniyle aynı).
    setTimeout(() => selectScriptSource(`tpl:${row.select_token != null ? row.select_token : row.id}`), 0)
  }

  function envPayload() {
    // secret: value yalnız kullanıcı yazdıysa gönder (aksi halde name+secret → sunucu eski enc'i korur)
    return form.env.filter(e => (e.name || '').trim()).map(e => {
      const row = { name: e.name.trim(), secret: !!e.secret }
      if (!e.secret) row.value = e.value || ''
      else if (e.value) row.value = e.value      // yeni secret değeri
      return row
    })
  }

  async function save() {
    if (!form.name.trim()) { toast.error(t('scripted.nameRequired')); return }
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName.trim()) { toast.error(t('scripted.groupRequired')); return }
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }   // etiket zorunlu (2026-09-18)
    // Boş/aralık dışı sayısal alan SESSİZCE kaydedilmesin (bkz. invalidNumericField).
    const bad = invalidNumericField(form)
    if (bad) { toast.error(t('scripted.numRange', t(bad.labelKey), bad.min, bad.max)); return }
    setSaving(true)
    try {
      const payload = {
        name: form.name.trim(), description: form.description?.trim() || null,
        groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING', notifyWebhook: form.notifyWebhook,
        intervalSeconds: Number(form.intervalSeconds), timeoutSeconds: Number(form.timeoutSeconds),
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        slowResponseEnabled: !!form.slowResponseEnabled, slowThresholdMs: Number(form.slowThresholdMs),
        active: form.active, script: form.script, env: envPayload(),
        useProxy: form.useProxy || 'AUTO',
        // Sürüm YALNIZ içerik değiştiyse yazılır; bump türü o zaman uygulanır (varsayılan yama).
        bumpType,
        // Eski sürümden yüklendiyse yeni sürüm RESTORE olarak işaretlenir ve notuna kaynağı yazılır.
        restoredFrom: form.restoredFrom || null,
      }
      const res = modal?.id ? await api.monitoring.updateScriptedMonitor(modal.id, payload)
                            : await api.monitoring.createScriptedMonitor(payload)
      if (res?.success) {
        // Kayıt başarılı → taslak artık gereksiz (backend de siliyor; liste burada tazelenir).
        setDrafts(d => d.filter(x => x.monitor_key !== (modal?.id ? String(modal.id) : 'new')))
        setForm(f => ({ ...f, restoredFrom: null }))
        // TASLAK TABANINI GÜNCELLE — yoksa taslak DİRİLİYOR:
        // `isFormDirty()` formu `modal`'daki (kayıt ÖNCESİNDEKİ) değerlerle karşılaştırıyor.
        // Taban eski kalınca kaydettikten sonra bile "kirli" görünüyor, `closeEdit()` içindeki
        // `flushDraft()` backend'in az önce SİLDİĞİ taslağı yeniden yazıyordu. Sonuç: kullanıcı
        // bir sonraki açılışta "kaydedilmemiş taslağınız var" teklifi görüyor; onu yükleyip
        // kaydederse ARADA BAŞKASININ yaptığı değişikliği sessizce geri alıyordu.
        // Yeni kayıtta taban sunucudan dönen monitör olur (artık id'si var); düzenlemede kaydedilen
        // içerik olur. Kullanıcı tekrar yazmaya başlarsa doğal olarak yine kirli sayılır.
        setModal(m => ({ ...(m || {}), ...(res.data?.id ? res.data : {}),
                         script: payload.script, name: payload.name }))
        // Engellemeyen uyarılar (eksik/kullanılmayan __ENV, sonuçsuz sözdizimi doğrulaması) KALICI
        // gösterilir — toast kaybolur, bu bilgi kaydettikten sonra da lazım.
        const w = res.data?.warnings
        if (Array.isArray(w) && w.length) setSaveWarnings(w)
        else if (shouldSmokeRun(res.data)) {
          // Kaydetme sonrası DOĞRULAMA KOŞUMU. Modal açık kalır; sürüm ZATEN kalıcı, koşum
          // kaydı bloklamıyor — banner metni bunu açıkça söylüyor.
          toast.success(t('scripted.saved'))
          runSmokeCheck(res.data?.id ?? modal?.id)
        }
        else { toast.success(t('scripted.saved')); closeEdit({ skipDraft: true }) }
        load()
      }
      else {
        // Sözdizimi hatası kaydetmeyi ENGELLER (prod politikası BLOCK) ve mesaj çok satırlı bir
        // Babel kod çerçevesidir. Eskiden yalnız 5 sn'lik toast'ta gösteriliyordu: `.toast-msg`'de
        // `white-space` ayarı olmadığı için `\n`'ler eziliyor, kod çerçevesi ve caret hizası
        // tamamen kayboluyordu — kullanıcı 5 sn sonra elinde hiçbir iz kalmadan modalda kalıyordu.
        // Artık KALICI: hizayı koruyan çerçeveyle basılır ve satır numarası cetvelde işaretlenir.
        setSaveError(res?.error || null)
        toast.error(res?.error || t('scripted.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  /**
   * Kaydetme sonrası doğrulama koşumu YAPILSIN MI?
   *
   * <p>KAPI: yalnız gerçekten YENİ BİR SÜRÜM yazıldıysa. Yalnız ayar (aralık, takım, alarm
   * tercihi) değiştiren bir kayıt sürüm üretmez ve k6 slotu yakmamalı — havuz varsayılan 2.
   * Yeni kayıtta önceki sürüm yok, `1.0.0` doğal olarak farklıdır.
   */
  function shouldSmokeRun(saved) {
    if (!k6.available) return false
    const newVersion = saved?.script_version
    if (!newVersion) return false
    return newVersion !== modal?.script_version
  }

  /**
   * Yeni sürümü BİR KEZ çalıştırır ve sonucu formda gösterir.
   *
   * <p>BİLİNÇLİ olarak `/scripted/{id}/check` (kaydedilmiş monitörü çalıştırır), `/scripted/test`
   * DEĞİL: form üzerinden test, kullanıcının yeniden yazmadığı secret'ları BOŞ gönderiyor
   * (kayıtlı şifreli değer korunsun diye) — secret'lı her monitörde sahte hata verir ve
   * kullanıcı bandı görmezden gelmeyi öğrenirdi. `/check` ayrıca koşumu Kontrol Geçmişi'ne
   * yazar ve script sürümünü damgalar, yani sürüm rozetini de besler.
   *
   * <p>Kaydı ASLA bloklamaz: sürüm bu noktada zaten kalıcı. Hata dönerse hiçbir şey geri
   * alınmaz; kullanıcı görür ve düzeltir (bu da bir sonraki yama sürümünü üretir).
   */
  async function runSmokeCheck(id) {
    if (!id) return
    setSmoke({ state: 'running' })
    setTestResult(null)
    const res = await api.monitoring.triggerScriptedCheck(id)
    if (res?.success) {
      if (res.data?.skipped) setSmoke({ state: 'skipped', reason: res.data.skipped_reason || '' })
      else if (res.data?.queued) setSmoke({ state: 'queued' })
      else {
        setSmoke(null)
        setTestResult({ ...res.data, _source: 'smoke', _checkedAt: res.data?.checked_at })
      }
    } else if (res?.status === 429) setSmoke({ state: 'cooldown' })
    else { setSmoke(null); toast.error(res?.error || t('scripted.triggerError')) }
  }

  /**

   * Silme — KARTTAN (satır). Hedef AÇIK argüman: {@code onClick={deleteMonitor}} biçiminde

   * bağlanırsa React olay nesnesini ilk argüman yapar ve hedef sessizce yanlış olur.

   * Onay projenin diyaloğuyla alınır ve mesaj hedefin ADINI taşır (sunucu HARD delete yapıyor).

   */

  async function deleteMonitor(m) {

    if (!m || m === 'new') return

    const ok = await showConfirm({

      title: t('mon.deleteTitle'),

      message: t('mon.deleteMsg', m.name),

      confirmText: t('scripted.delete'),

      cancelText: t('scripted.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deleteScriptedMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      toast.success(t('scripted.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal?.id) return
    if (!await showConfirm({
      title: t('scripted.delete'), message: t('scripted.confirmDelete'),
      confirmText: t('scripted.delete'), variant: 'danger',
    })) return
    const res = await api.monitoring.deleteScriptedMonitor(modal.id)
    if (res?.success) {
      // skipDraft: silinen monitör için taslak yazılırsa hiçbir arayüzden erişilemeyen
      // bir yetim satır kalır ("devam et" şeridi yalnız 'new'e, teklif yalnız açılan
      // monitöre bakıyor) ve sonsuza kadar taşınır.
      toast.success(t('scripted.deleted')); closeEdit({ skipDraft: true }); load()
    }
    else toast.error(res?.error || t('scripted.deleteError'))
  }

  async function runTest() {
    if (!form.script.trim()) { toast.error(t('scripted.scriptRequired')); return }
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testScripted({
        script: form.script, timeoutSeconds: Number(form.timeoutSeconds),
        // Test koşumu da formdaki vekil tercihini kullanır; aksi halde "Test Çalıştır" yeşil,
        // kaydedilen monitör kırmızı olur ve aradaki fark görünmez.
        useProxy: form.useProxy || 'AUTO',
        env: form.env.filter(e => (e.name || '').trim()).map(e => ({ name: e.name.trim(), value: e.value || '' })),
      })
      const data = res?.success ? res.data : { status: 'ERROR', error: res?.error || t('scripted.testError') }
      setTestResult({ ...data, _source: 'test' })
    } finally {
      setTesting(false)
    }
  }

  async function checkNow(m, { silent = false } = {}) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerScriptedCheck(m.id)
      if (res?.success) {
        // queued: koşum sunucunun bekleme penceresini aştı, arka planda sürüyor. Satırı ESKİ sonuçla
        // güncellemek yanıltıcı olurdu (kullanıcı bunu yeni sonuç sanar) — dokunmayıp haber veriyoruz.
        // Sonuç kendiliğinden gelir: liste 60 sn'de, Kontrol Geçmişi 30 sn'de canlı yeniliyor.
        // skipped: kontrol HİÇ yürütülemedi (k6 havuzu dolu / k6 yok) ve bu yüzden kayıt da
        // yazılmadı. Satırı güncellemek kullanıcıya ESKİ sonucu "yeni" gibi gösterirdi; sebebi
        // söylüyoruz. Uyarı tonunda: hedefte bir sorun YOK, kapasite darlığı var.
        if (res.data?.skipped) {
          const msg = t('scripted.triggerSkipped', res.data.skipped_reason || '')
          if (!silent) toast.info(msg, 6000)
          // Atlanan koşum BAŞARISIZ sayılır: kontrol hiç yürüttürülmedi, satır bunu söylemeli.
          return { ok: false, data: res.data, error: msg }
        }
        if (res.data?.queued) {
          if (!silent) toast.success(t('scripted.triggerQueued'))
          // Kuyruğa alınan koşum BAŞARILI: tetikleme kabul edildi, sonucu arkada gelecek.
          return { ok: true, data: res.data }
        }
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        // Geçmiş ARTIK tazeleniyor (setHistReload): sekmenin kendi 30 sn'lik canlı yenilemesi
        // 1. sayfa dışında ve özel aralıkta KAPALI, dolayısıyla modaldan koşturulan kontrolün
        // sonucu hiç görünmeyebiliyordu. Sinyal remount ETMEZ — seçilen aralık/sayfa/filtre kalır.
        // (Eski hatalı loadHistory(m.id, rangeDays) çağrısı geri GELMEDİ; not aşağıda duruyor.)
        // Buradaki eski loadHistory(m.id, rangeDays) çağrısı geçmiş yönetimi o bileşene taşınırken
        // temizlenmemişti; ikisi de tanımsız olduğu için modal açıkken "Şimdi Çalıştır" ReferenceError
        // atıyor, aşağıdaki setChecking(null) hiç çalışmıyor ve buton kalıcı kilitleniyordu.
        if (selected?.id === m.id) setSelected(res.data)
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      } else if (res && !silent) toast.error(res.error || t('scripted.triggerError'))
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          {/* Motor sürümü BAŞLIĞIN yanında, parantez içinde. Eskiden sağdaki eylem kümesindeydi;
              orada bir eylemmiş gibi duruyor ve satırı şişiriyordu. Sürüm bir eylem değil, bu
              ekranın neyle çalıştığının künyesi — yeri başlıktır. Sürüm okunamıyorsa parantez
              HİÇ çizilmez: boş bir "( )" yazmaktansa sessiz kal. */}
          <h2 className="upt-title">
            <FlaskConical size={20} style={{ verticalAlign: '-4px' }} /> {t('scripted.title')}
            {k6.version && (
              <span className="sc-title-k6" title={t('scripted.k6VersionTitle')}> (k6 {k6.version})</span>
            )}
          </h2>
          <p className="upt-subtitle">{t('scripted.subtitle')}</p>
          {/* Görünüm anahtarı: monitörler ↔ şablon kütüphanesi. Şablon izni yoksa HİÇ çizilmez —
              basılabilen ama 403 yiyen bir düğme göstermek yerine yüzey görünmez kalır.
              BAŞLIĞIN ALTINDA durur, sağdaki eylem kümesinde DEĞİL: sağ küme (otomatik yenileme
              sayacı + Yenile + bağlantı kopyala + k6 rozeti + Kılavuz + Yeni Monitör) anahtar
              da eklenince ~900 px'i buluyor ve dar ekranda sarmalanıp Yeni Monitör düğmesini
              alt satıra atıyordu. Ayrıca bu bir EYLEM değil görünüm seçicidir; yeri başlığın
              yanıdır (şablon görünümündeki kapsam anahtarıyla da alt alta gelmez). */}
          {canViewTemplates &&
            <div className="sc-view-switch">
              <SegmentedControl value={view} onChange={setView} ariaLabel={t('tpl.viewSwitch')}
                options={[
                  { value: 'monitors', label: t('tpl.viewMonitors'), icon: LayoutDashboard },
                  { value: 'templates', label: t('tpl.viewTemplates'), icon: FileCode2 },
                ]} />
            </div>}
        </div>
        {/* Sağdaki eylem kümesinin TAMAMI monitör görünümüne aittir: otomatik yenileme sayacı ve
            Yenile monitör listesini tazeler, bağlantı kopyala monitör filtrelerini taşıyan URL'i
            verir, k6 rozeti ile "Nasıl doldurulur?" kılavuzu MONİTÖR FORMUNU anlatır. Şablon
            görünümünde hiçbiri o ekrandaki içerikle ilgili değil — sayaç şablon listesini
            yenilemiyor, kılavuz başka bir formu anlatıyordu. Şablon sekmesinin kendi tazeleme ve
            yönetim düğmeleri ScriptedTemplatesTab içinde. */}
        {view === 'monitors' && (
          <div className="upt-header-right">
            <span className="upt-last-check">
              {t('scripted.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
            </span>
            <Button variant="outline" size="sm" onClick={load}>
              <RefreshCw size={14} />{t('scripted.refresh')}
            </Button>
            {/* iconOnly (10 izleme sayfasında da aynı): "Bağlantıyı kopyala" tam metniyle başlık
                satırının en geniş öğesiydi. Anlam kaybı yok — metin title/aria-label'da duruyor. */}
            <CheckAllButton count={checkable.length} running={checkRun.running}
              done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
              onClick={checkRun.openPicker} />
            <CopyLinkButton iconOnly variant="outline" />
            {/* k6 sürümü buradan BAŞLIĞA taşındı (yukarıdaki nota bakın). K6VersionBadge yaşamaya
                devam ediyor: düzenleme formunda sözdizimi notuyla birlikte kullanılıyor. */}
            <MonitorGuideButton type="scripted" />
            {k6.canManage && k6.available &&
              <Button size="sm" onClick={openNew}><Plus size={14} />{t('scripted.addMonitor')}</Button>}
          </div>
        )}
      </div>

      {view === 'templates' ? (
        <ScriptedTemplatesTab t={t} lang={lang} teams={teams} teamName={teamName}
          onUseTemplate={k6.canManage && k6.available ? startMonitorFromTemplate : null} />
      ) : (<>

      <MonitorHowBox bullets={[t('scripted.how1'), t('scripted.how2'), t('scripted.how3'), t('scripted.how4'), t('scripted.how5')]} />

      {/* .alert-msg YEŞİL "başarı" kutusuydu ve emoji taşıyordu (proje kuralı: yalnız lucide). */}
      {!k6.available && <AlertBanner tone="warning">{t('scripted.k6Disabled')}</AlertBanner>}

      {/* Hiç kaydedilmemiş taslak — kullanıcı script yazarken sayfadan ayrılırsa yazdıkları
          burada bekler. Monitör listesine yarım kayıt olarak DÜŞMEZ. */}
      {newDraft && !modal && (
        <AlertBanner
          tone="info"
          title={t('scripted.draftBannerTitle')}
          actions={<>
            <Button size="sm" onClick={() => continueDraft(newDraft)}>
              {t('scripted.draftContinue')}
            </Button>
            <Button variant="secondary" size="sm" onClick={() => discardDraft('new')}>
              {t('scripted.draftDiscard')}
            </Button>
          </>}
        >
          {t('scripted.draftBannerText', formatDateSec(newDraft.updated_at))}
          {newDraft.monitor_name ? ` — ${newDraft.monitor_name}` : ''}
        </AlertBanner>
      )}

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
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('scripted.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        /* Hata bandi bos durumun ONUNDE: aksi halde yukleme hatasi "hic izleme yok" gibi gorunur. */
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        /* Boş durum: eskiden LoadingBlock ile (dönen spinner) gösteriliyordu — "yükleniyor" ile
           "hiç kayıt yok" görsel olarak ayrışmıyordu. */
        <StatusBlock icon={FlaskConical}
          title={k6.canManage ? t('scripted.noMonitorsAdmin') : t('scripted.noMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updateScriptedMonitor, remove: api.monitoring.deleteScriptedMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            /* Kart klavyeyle de açılabilir: role+tabIndex+Enter/Space. onKeyDown YALNIZ kartın
               KENDİ hedefinde çalışır — içerideki Çalıştır/Düzenle/Kopyala düğmelerinde Enter'a
               basıldığında tuş olayı karta baloncuklanıp detayı DA açardı (çift eylem). */
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              role="button" tabIndex={0} aria-label={t('scripted.openDetailFor', m.name)}
              onKeyDown={e => {
                if (e.target !== e.currentTarget) return
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDetail(m) }
              }}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {canManageRow(m) && (
                  <input type="checkbox" className="upt-card-check" checked={bulkSel.has(m.id)} onChange={() => toggleBulk(m.id)} onClick={(e) => e.stopPropagation()} aria-label={t('bulk.selectOneFor', m.name)} />
                )}
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.name} />
                {/* Hiç yeşile dönmemiş monitör: arıza değil yapılandırma/erişim sorunu sinyali.
                    Sayfa türündeki CONFIG_ERROR ayrımının sentetik karşılığı — yalnız görsel,
                    alarm semantiği DEĞİŞMEZ. */}
                {m.never_succeeded && (
                  <span className="sc-never-badge" title={t('scripted.neverSucceededHint')}>
                    {t('scripted.neverSucceeded')}
                  </span>
                )}
                {/* Sistem kapattı: kullanıcının kendi kapattığı pasif izlemeden AYRI görünmeli,
                    yoksa "ben kapatmadım ki" ile "neden veri yok" aynı sessiz duruma düşer. */}
                {m.disabled_reason && (
                  <span className="sc-autodisabled-badge" title={m.disabled_reason}>
                    {t('scripted.autoDisabledBadge')}
                  </span>
                )}
                {/* Sağ blok tek kapsayıcıda: .upt-card-top bir space-between flex'i, doğrudan
                    6. kardeş eklemek mevcut rozetleri yeniden yayardı. */}
                <span className="upt-card-top-right">
                  <span className="upt-port-tag">k6</span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('scripted', m.id)}
                    variant="ghost" size="icon-xs" className="upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.name}>{m.name}</div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.duration_ms != null ? `${m.duration_ms}ms` : '—'}</span>
                  <span className="upt-metric-lbl">{t('scripted.lastDuration')}</span>
                </div>
                {checksSummary(t, m) && (
                  <div className="upt-metric">
                    <span className="upt-metric-val"><ChecksSummary t={t} check={m} /></span>
                    <span className="upt-metric-lbl">{t('scripted.checks')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : t('scripted.neverRun')}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)} checkDisabled={!k6.available}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('scripted.runNow')} editTitle={t('scripted.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('scripted.delete')} />
                )}
              </div>
            </div>
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detail Modal — kanonik upt-modal + 4 sekme (Kontrol / Alarm / Grafik / Rehber&Notlar) ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeDetail}>
          <div className={`upt-modal upt-modal--${isPass(selected.status) ? 'up' : isFailLike(selected.status) ? 'down' : isWarnLike(selected.status) ? 'warn' : 'unknown'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.name}</span>
              </div>
              {/* CSV butonu kaldırıldı: mükerrerdi ve bozuktu (tanımsız `history` → window.history →
                  "history.map is not a function"). Çalışan, sunucu-taraflı CSV linkini Kontrol
                  Geçmişi sekmesi zaten sunuyor (CheckHistoryTab). */}
              {/* Eylemler KARTIN aynısı (MonitorModalActions). Buradaki "Çalıştır" eskiden tek
                  başına, etiketli bir `btn-primary` idi: diğer sekiz türde aynı iş ikon düğmesi,
                  burada birincil düğmeydi ve Düzenle/Kopyala hiç yoktu. k6 koşulu KORUNUR —
                  k6 kurulu değilken sentetik koşu anlamsız (`checkDisabled`). */}
              <MonitorModalActions
                running={isRunning(selected.id)}
                checkDisabled={!k6.available}
                onCheck={canManageRow(selected) ? () => checkNow(selected) : undefined}
                checkTitle={t('scripted.runNow')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('scripted.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('scripted.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly variant="outline" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('scripted.sumUptimeHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('scripted.sumUptime')}{summary.total > 0 ? ` · ${summary.total - summary.down}/${summary.total}` : ''}</span>
              </div>
              <div className="upt-modal-metric" title={t('scripted.sumTotalHint')}><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('scripted.sumTotal')}</span></div>
              <div className="upt-modal-metric" title={t('scripted.sumIncidentsHint')}><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('scripted.sumIncidents')}</span></div>
              {selected.duration_ms != null && <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.duration_ms}ms</span><span className="upt-modal-metric-lbl">{t('scripted.lastDuration')}</span></div>}
              {/* Çalışan script sürümü. `sc-ver-chip` BİLİNÇLİ kullanılmıyor: o satır-içi küçük bir
                  rozet, buradaki 18px/700 metrik değerinin yerinde yanındaki beş metrikle kavga eder.
                  `≠` işareti: son koşum GÜNCEL sürümle yapılmamış (kaydedildi ama henüz çalışmadı) —
                  "son düzenlemem bozdu mu?" sorusunun ilk yarısı. */}
              {selected.script_version && (
                <div className="upt-modal-metric" title={t('scripted.sumVersionHint')}>
                  <span className="upt-modal-metric-val">
                    v{selected.script_version}
                    {selected.run_script_version && selected.run_script_version !== selected.script_version && (
                      <span className="sc-ver-drift" title={t('scripted.verDriftHint', selected.run_script_version)}>≠</span>
                    )}
                  </span>
                  <span className="upt-modal-metric-lbl">{t('scripted.sumVersion')}</span>
                </div>
              )}
              {checksSummary(t, selected) &&
                <div className="upt-modal-metric"><span className="upt-modal-metric-val"><ChecksSummary t={t} check={selected} /></span><span className="upt-modal-metric-lbl">{t('scripted.checks')}</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('scripted.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('scripted.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('scripted.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'versions' ? ' active' : ''}`} onClick={() => setDetailTab('versions')}>{t('scripted.tabVersions')}</button>
              <button className={`modal-tab${detailTab === 'diag' ? ' active' : ''}`} onClick={() => setDetailTab('diag')}>{t('scripted.tabDiag')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('scripted.tabGuide')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {/* Anomali guard'ı kapattıysa sebep HER SEKMEDE görünür: kullanıcı "izleme neden
                veri üretmiyor?" sorusunu sekme gezerek aramasın. Uyarı ancak izleme yeniden
                açılınca düşer (sunucu `disabled_reason`u orada temizler). */}
            {selected.disabled_reason && (
              <AlertBanner tone="danger" icon={AlertTriangle} title={t('scripted.autoDisabledTitle')}>
                <div>{selected.disabled_reason}</div>
                {selected.disabled_at &&
                  <div className="sys-small">{t('scripted.autoDisabledAt', formatDateSec(selected.disabled_at))}</div>}
                <div className="sys-small">{t('scripted.autoDisabledHow')}</div>
              </AlertBanner>
            )}

            {detailTab === 'control' && (<>
              {/* gridClass ZORUNLU: sürüm kolonuyla birlikte 5 kolon olduk, ortak `.upt-rt-grid`
                  tabanı ise 4 kolonluk. Kendi şablonumuzu geçmezsek 5. hücre taşar — ve tabanı
                  değiştirmek CheckHistoryTab'ı paylaşan diğer 9 izleme sayfasını bozardı. */}
              <CheckHistoryTab kind="scripted" monitorId={selected.id} listKey="scripted-history" reloadSignal={histReload}
                gridClass="sc-rt-grid"
                columns={[t('scripted.colTime'), t('scripted.colStatus'), t('scripted.versionColVersion'),
                  t('scripted.colDuration'), t('scripted.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                groupIdenticalErrors
                rowSignature={scriptedRowSignature}
                renderRow={(c) => {
                  const isSel = selCheck?.id === c.id
                  return (<>
                    {/* Satırı AÇAN kontrol klavyeye ve ekran okuyucuya görünür olmalı: eskiden
                        yalnız cursor:pointer taşıyan bir span'di, koşum detayı klavyeyle hiç
                        açılamıyordu. Erişilebilir ad ZAMANI taşır — aynı durum art arda
                        tekrarladığında satırlar birbirinden ancak böyle ayrılıyor (kardeş
                        yüzey: HttpMonitorPage geçmiş satırı). Odak YALNIZ bu hücrede: dört
                        hücrenin dördü de odaklanabilir olsaydı satır başına dört durak olurdu. */}
                    <span className="upt-rt-time" style={{ cursor: 'pointer' }}
                      role="button" tabIndex={0}
                      aria-label={`${formatDateSec(c.checked_at)} · ${statusLabel(t, c.status)} — ${t('scripted.rowOpenAria')}`}
                      onKeyDown={e => {
                        if (e.key !== 'Enter' && e.key !== ' ') return
                        e.preventDefault(); setSelCheck(isSel ? null : c)
                      }}
                      onClick={() => setSelCheck(isSel ? null : c)}>{formatDateSec(c.checked_at)}</span>
                    <span className={isPass(c.status) ? 'upt-rt-up' : isWarnLike(c.status) ? 'upt-rt-warn' : 'upt-rt-down'} style={{ cursor: 'pointer', fontWeight: isSel ? 700 : undefined }}
                      onClick={() => setSelCheck(isSel ? null : c)}>{statusLabel(t, c.status)}</span>
                    {/* Bu koşumun HANGİ sürümle yapıldığı. Boş olabilir ve bu MEŞRU: sürümleme
                        öncesi kayıtlar ile yalnız ayarı kaydedilmiş (sürüm yazılmamış) monitörler.
                        O durumda `—` basılır; çıplak `v` ASLA basılmaz. */}
                    <span className="upt-rt-ms">
                      {c.script_version
                        ? <span className="sc-ver-chip sc-ver-chip--cell">v{c.script_version}</span>
                        : '—'}
                    </span>
                    <span className="upt-rt-ms">{c.duration_ms != null ? `${c.duration_ms}ms` : '—'}</span>
                    {/* Detay hücresinin işi ÖZET + panele davet. Zenginlik (kod çerçevesi, çıkış
                        kodu etiketi, faz kırılımı, k6 çıktısı) satıra tıklayınca açılan
                        CheckDetail panelinde. Eskiden burada ham `error` basılıyordu: backend bu
                        metni 14 satır / 1500 karaktere kadar üretiyor (ERR_MAX_*) ve tek satır
                        ekranı dolduruyordu; `title` da metnin AYNISI olduğu için işe yaramıyordu. */}
                    {c.error
                      ? <span className="upt-rt-error" title={c.error} style={{ cursor: 'pointer' }}
                          onClick={() => setSelCheck(isSel ? null : c)}>
                          {firstLine(c.error)}
                          {stuckLabel(t, c) && <span className="sc-stuck-chip">{stuckLabel(t, c)}</span>}
                        </span>
                      : checksSummary(t, c)
                        ? <span className="upt-rt-ms" style={{ cursor: 'pointer' }}
                            onClick={() => setSelCheck(isSel ? null : c)}><ChecksSummary t={t} check={c} /></span>
                        : <span className="upt-rt-ms">—</span>}
                  </>)
                }} />
              {selCheck && <CheckDetail t={t} check={selCheck} k6Version={k6.version} />}
            </>)}

            {detailTab === 'alerts' && <AlertHistory domain={selected.name} types={alertTypesFor('scripted')} />}

            {detailTab === 'diag' && <DiagTab t={t} monitor={selected} canRun={canManageRow(selected)} />}

            {detailTab === 'versions' && (
              <ScriptedVersionsTab t={t} monitor={selected} canEdit={canManageRow(selected)}
                onLoadIntoEditor={(v, detail) => {
                  // Geri dönüş "tek tıkla geri al" DEĞİL: sürüm editöre yüklenir, kullanıcı
                  // görür/test eder, kaydedince YENİ sürüm olur — geçmiş asla ezilmez.
                  closeDetail()
                  openEdit(selected)
                  setTimeout(() => {
                    setForm(f => ({ ...f, script: detail.script, restoredFrom: v.version }))
                    toast.success(t('scripted.versionLoaded', v.version))
                  }, 0)
                }} />
            )}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="scripted" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="SCRIPTED" target={selected.name} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="scripted" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}
      </>)}

      {modal && createPortal(<EditModal {...{ t, lang, k6Version: k6.version, proxy, form, setForm, modal, dupSource, saving, testing, testResult, saveWarnings, saveError, smoke, dismissSmoke: () => { setSmoke(null); closeEdit({ skipDraft: true }) }, save, del, closeEdit, runTest, isAdminish, canPickTeam, canDelete: modal?.id ? canDeleteRow(modal) : false, teamSelectOptions, teamName, groupSelectOptions, teamTags, setEnvRow, addEnvRow, delEnvRow, selectScriptSource, savedScripts, savedSource, templates: scriptTemplates, draftSavedAt, pendingDraft, applyDraft, discardDraft, bumpType, setBumpType }} />, document.body)}

      {/* Sayfa düzeyi toplu kontrol: önce takım seçimi, sonra akan sonuç tablosu.
          Depolama anahtarı TÜR BAŞINA ayrı — tek anahtar paylaşılsaydı buradaki seçim
          panonun sertifika seçimini ezerdi. */}
      {checkRun.pickerOpen && (
        <CheckTeamPicker
          buckets={monitorTeamBuckets(checkable)}
          storageKey="sm.checkRun.teams.scripted"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="scripted"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}

/**
 * Seçili koşumun tanısı — İKİ KATMANLI sunum.
 *
 * Üstte insan-okur tek cümle (durum + çıkış kodu etiketi + varsa çare), altta katlanır teknik
 * detay (tam k6 çıktısı, kopyalanabilir). Eskiden ham backend mesajı düz kırmızı metin olarak
 * basılıyor, çıktı paneli ise sabit siyah zeminli satır-içi stille yazılıyordu (açık temada
 * sayfanın geri kalanıyla çelişen bir blok).
 */
/**
 * "Nerede takıldı?" — isteğin faz kırılımı.
 *
 * Sahadaki en pahalı boşluğu kapatır: bir koşum `request timeout` derken DNS mi, TCP mi, TLS mi,
 * yanıt bekleme mi olduğu hiçbir ekranda görünmüyordu (veri k6'dan geliyordu ama atılıyordu).
 * Fazlar SIRALI okunur: ölçülen son faz "buraya kadar gelindi", ondan sonraki ilk ölçülmeyen faz
 * takılma noktasıdır ve vurgulanır.
 *
 * Hiç faz ölçülmediyse panel çizilmez — koşum tek bir istek bile başlatamamış demektir
 * (sözdizimi hatası, k6 yok, havuz dolu…) ve orada faz göstermek yanıltıcı olurdu.
 */
function RequestPhases({ t, check, viaProxy }) {
  const { phases, any, stuckAt, dataSent, dataReceived } = readPhases(check, isPass(check?.status))
  if (!any) return null
  const sent = formatBytes(dataSent)
  const received = formatBytes(dataReceived)
  return (
    <div className="sc-phases">
      <div className="sc-phases-head">
        <span className="sc-phases-title">{t('scripted.phasesTitle')}</span>
        {viaProxy != null && (
          <span className="sc-phases-proxy">
            {viaProxy ? t('scripted.viaProxyYes') : t('scripted.viaProxyNo')}
          </span>
        )}
      </div>
      <ol className="sc-phase-list">
        {phases.map(p => {
          const stuck = p.key === stuckAt
          const cls = stuck ? ' sc-phase--stuck' : p.done ? ' sc-phase--done' : ' sc-phase--skipped'
          return (
            <li key={p.key} className={`sc-phase${cls}`}>
              <span className="sc-phase-name">{t(`scripted.phase_${p.key}`)}</span>
              <span className="sc-phase-val">{p.ms == null ? '—' : `${p.ms} ms`}</span>
            </li>
          )
        })}
      </ol>
      {stuckAt && <div className="sc-phase-verdict">{t('scripted.phaseStuck', t(`scripted.phase_${stuckAt}`))}</div>}
      {(sent || received) && (
        <div className="sc-phase-bytes">{t('scripted.phaseBytes', sent ?? '—', received ?? '—')}</div>
      )}
    </div>
  )
}

/**
 * BAĞLANTI TEŞHİSİ — "Java çekebiliyor ama k6 çekemiyor" ayrımını ÖLÇEREK kapatır.
 *
 * Sahada bir monitör 288 koşumun 288'inde `request timeout` verirken aynı pod hedefin
 * sertifikasını sorunsuz alabiliyordu; farkın nerede oluştuğu (vekil kararı mı, kurumsal CA mı,
 * TLS'in kendisi mi) hiçbir ekrandan görülemiyor ve teşhis dört sürüm boyunca tahmine kalıyordu.
 * Bu sekme üç değişkeni TEK TEK oynatıp faz kırılımlarını yan yana koyar; okuma kuralı basit:
 * hangi bacak geçiyorsa fark ORADAKİ değişkendedir.
 *
 * Sonda KULLANICI SCRIPT'İNİ koşmaz — tek istekli üretilmiş bir script kullanır; yani script
 * hatalarıyla ağ sorunları birbirine karışmaz.
 */
function DiagTab({ t, monitor, canRun }) {
  const [state, setState] = useState({ idle: true })
  const [url, setUrl] = useState('')

  async function run() {
    setState({ loading: true })
    const res = await api.monitoring.diagnoseScripted(monitor.id, url.trim() || undefined)
    setState(res?.success ? { data: res.data } : { error: res?.error || t('scripted.diagError') })
    if (res?.success && !url) setUrl(res.data?.url || '')
  }

  return (
    <div className="sc-diag">
      <p className="field-hint">{t('scripted.diagIntro')}</p>
      <div className="sc-diag-run">
        <input className="input" value={url} onChange={e => setUrl(e.target.value)}
          placeholder={t('scripted.diagUrlPlaceholder')} aria-label={t('scripted.diagUrl')} />
        <Button type="button" onClick={run} disabled={state.loading || !canRun}>
          {state.loading ? <Spinner size={14} /> : <Play size={14} />} {t('scripted.diagRun')}
        </Button>
      </div>
      {!canRun && <div className="field-hint">{t('scripted.diagNoPermission')}</div>}

      {state.loading && <LoadingBlock label={t('scripted.diagRunning')} />}
      {state.error && <AlertBanner tone="danger" icon={AlertTriangle}>{state.error}</AlertBanner>}

      {state.data && (<>
        {/* Vekil kararı burada da yazılı: "AUTO seçtim, vekilden geçiyordur" varsayımı sahada
            dört sürüm boyunca yanlış teşhise sebep oldu (NO_PROXY sonek eşleşmesi). */}
        <div className="sc-diag-meta">
          <span><strong>{t('scripted.diagTarget')}:</strong> <code>{state.data.url}</code></span>
          <span>k6 {state.data.k6_version}</span>
          {state.data.proxy_configured
            ? <span title={state.data.no_proxy}>NO_PROXY=<code>{state.data.no_proxy || '—'}</code></span>
            : <span>{t('scripted.useProxyNotConfigured')}</span>}
        </div>
        <div className="sc-diag-legs">
          {(state.data.legs || []).map(leg => (
            <div key={leg.key} className={`sc-diag-leg${leg.ok ? ' sc-diag-leg--ok' : ' sc-diag-leg--bad'}`}>
              <div className="sc-diag-leg-head">
                <span className="sc-diag-leg-label">{leg.label}</span>
                <span className="sc-diag-leg-status">{statusLabel(t, leg.status)}</span>
                {leg.duration_ms != null && <span className="sc-diag-leg-ms">{leg.duration_ms} ms</span>}
              </div>
              {leg.error && <div className="sc-err-msg">{leg.error}</div>}
              <RequestPhases t={t} check={leg} viaProxy={leg.via_proxy} />
            </div>
          ))}
        </div>
        <p className="field-hint">{t('scripted.diagHowToRead')}</p>
      </>)}
    </div>
  )
}

/**
 * Doğrulama özeti rozeti — "✓ 2 doğrulama geçti".
 *
 * Dört ekranda (kart metriği, detay modalı özeti, kontrol geçmişi hücresi, test koşumu paneli)
 * AYNI ifadeyi kullanır. Öncesinde hepsi ham `2✓/0✗` basıyordu ve kullanıcı "bu nedir
 * anlaşılmıyor" dedi — sayının k6 `check()` doğrulamaları olduğu hiçbir yerde yazmıyordu.
 * İkon tek başına anlam taşımaz; yanındaki kelime taşır (renk körlüğü + bağlamsızlık).
 */
function ChecksSummary({ t, check }) {
  const s = checksSummary(t, check)
  if (!s) return null
  return (
    <span className={`sc-checks-sum sc-checks-sum--${s.tone}`}>
      <span className="sc-checks-sum-icon" aria-hidden="true">{s.icon}</span>{s.text}
    </span>
  )
}

function CheckDetail({ t, check, k6Version }) {
  const [openTech, setOpenTech] = useState(false)
  const [showAll, setShowAll] = useState(false)
  let checks = []
  // API snake_case; savunmacı çift-okuma.
  const checksJson = check.checks_json ?? check.checksJson
  const outputTail = check.output_tail ?? check.outputTail
  const exitCode = check.exit_code ?? check.exitCode
  try { if (checksJson) checks = JSON.parse(checksJson) } catch { /* bozuk json → boş liste */ }

  const label = exitLabel(t, exitCode)
  const hint = exitHint(t, exitCode)
  const engineHint = diagnosisHint(t, check, k6Version)
  const tone = isPass(check.status) ? 'success' : isWarnLike(check.status) ? 'warning' : 'danger'
  const lines = outputTail ? outputTail.split('\n') : []
  const CLAMP = 12
  const clamped = !showAll && lines.length > CLAMP
  const shown = clamped ? lines.slice(0, CLAMP).join('\n') : outputTail

  return (
    <div className="sc-detail">
      {checks.length > 0 && (
        <ul className="sc-checks">
          {checks.map((c, i) => (
            <li key={i} className="sc-check-row">
              <span className={c.passed ? 'sc-check-ok' : 'sc-check-bad'}>{c.passed ? '✓' : '✗'}</span> {c.name}
            </li>
          ))}
        </ul>
      )}

      {(check.error || label) && (
        <AlertBanner tone={tone} title={t('scripted.errTitle')}>
          {/* Çok satırlı hata = k6/Babel kod çerçevesi → monospace + yatay kaydırma, yoksa caret kayar. */}
          {check.error && <div className={`sc-err-msg${isCodeFrame(check.error) ? ' sc-err-msg--frame' : ''}`}>{check.error}</div>}
          {label && <div className="sc-err-exit">{label}{exitCode != null ? ` · exit ${exitCode}` : ''}</div>}
          {hint && <div className="sc-err-hint">{hint}</div>}
          {engineHint && <div className="sc-err-hint">{engineHint}</div>}
        </AlertBanner>
      )}

      <RequestPhases t={t} check={check} viaProxy={check.via_proxy ?? check.viaProxy ?? null} />

      {outputTail && (
        <div className="sc-tech">
          <button type="button" className="mhow-toggle sc-tech-toggle" aria-expanded={openTech}
            onClick={() => setOpenTech(o => !o)}>
            <Terminal size={15} /><span>{t('scripted.errTech')}</span>
            <ChevronDown size={15} className={`mhow-chev${openTech ? ' open' : ''}`} />
          </button>
          {openTech && (
            <>
              <div className="sc-tech-actions">
                {/* Tam metin her zaman kopyalanabilir — kırpılmış hâli değil. */}
                <CopyButton value={outputTail} label={t('scripted.errCopy')} copiedLabel={t('scripted.errCopied')} />
                {lines.length > CLAMP && (
                  <Button type="button" variant="outline" size="sm" onClick={() => setShowAll(v => !v)}>
                    {showAll ? t('scripted.outShowLess') : t('scripted.outShowAll', lines.length)}
                  </Button>
                )}
              </div>
              <pre className="show-pre sc-output">{shown}{clamped ? '\n…' : ''}</pre>
            </>
          )}
        </div>
      )}
    </div>
  )
}

// ── Create/Edit modal ────────────────────────────────────────────────────────
function EditModal({ t, lang, k6Version, proxy = null, form, setForm, modal, dupSource, saving, testing, testResult, saveWarnings, saveError, smoke = null, dismissSmoke, save, del, closeEdit, runTest, isAdminish, canPickTeam = isAdminish, canDelete, teamSelectOptions, teamName, groupSelectOptions, teamTags = [], setEnvRow, addEnvRow, delEnvRow, selectScriptSource, savedScripts = [], savedSource = null, templates = [], draftSavedAt = null, pendingDraft = null, applyDraft, discardDraft, bumpType = 'patch', setBumpType }) {
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
  // Ortak bildirim blogunun "kime gidecek" satiri. Form AYRI bir bilesende oldugu icin
  // etiket burada, elde olan props'tan (teamSelectOptions/teamName) turetilir.
  const selectedTeamLabel =
    (teamSelectOptions || []).find(o => String(o.value) === String(form.teamId))?.label
    || teamName || t('app.noTeam')

  // Seçili kayıtlı script'in adı — "hangi monitörden yüklendi" notu için.
  const selectedSavedName = form.template?.startsWith('saved:')
    ? savedScripts.find(s => `saved:${s.id}` === form.template)?.name
    : null

  // Cetvelde işaretlenecek satırlar: engelleyen hata + uyarılar + son koşum hatası.
  // k6 satır bilgisini ayrı bir alanda DÖNDÜRMÜYOR, metnin içinde geçiyor (bkz. utils/k6Errors).
  const markers = useMemo(
    () => collectK6Markers({ saveError, warnings: saveWarnings, runError: testResult?.error }),
    [saveError, saveWarnings, testResult])
  const errorLines = useMemo(
    () => markers.filter(m => m.type === 'error').map(m => m.line), [markers])

  // Seçici seçenekleri: kapsam grupları + yerleşiklerin kategori dalları, tek listede
  // (SearchableSelect `group` ile başlıklara böler, `collapsibleGroups` ile katlar).
  // Grup SIRASI çağıranın sorumluluğu — başlıklar bitişikliğe göre basılıyor (bkz. utils).
  const scriptSourceOptions = buildScriptSourceOptions({
    savedScripts, templates, savedSourceId: savedSource?.id, lang, t,
  })
  return (
    <div className="modal-overlay">
      <div className="modal-box modal-sticky-actions" style={{ maxWidth: 860, width: '92vw' }} onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--port">
          <div className="modal-icon-hdr-badge"><FlaskConical size={20} /></div>
          <h3>{modal.id ? t('scripted.modalEdit') : t('scripted.modalNew')}
            {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}
            {modal.script_version && <span className="sc-ver-chip">v{modal.script_version}</span>}
            {/* Otomatik kayıt göstergesi: kullanıcı "kaydettim mi?" diye tereddüt etmesin. */}
            {draftSavedAt && (
              <span className="sc-draft-saved">
                ✓ {t('scripted.autoSaved')} {draftSavedAt.toLocaleTimeString(lang === 'tr' ? 'tr-TR' : 'en-GB')}
              </span>
            )}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('scripted.testing')} /></span>
        </div>
        <div className="modal-scroll-body" ref={scrollHint.ref}>
        {dupSource && <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>}

        {/* Bu monitör için kaydedilmemiş taslak — OTOMATİK uygulanmaz, kullanıcı karar verir. */}
        {pendingDraft && (
          <div className="full-width" style={{ padding: '0 4px 8px' }}>
            <AlertBanner tone="info" title={t('scripted.draftRestoreTitle')}
              actions={<>
                <Button size="sm" onClick={() => applyDraft(pendingDraft)}>{t('scripted.draftRestore')}</Button>
                <Button variant="secondary" size="sm" onClick={() => discardDraft(pendingDraft.monitor_key)}>{t('scripted.draftDiscard')}</Button>
              </>}>
              {t('scripted.draftRestoreText', formatDateSec(pendingDraft.updated_at))}
            </AlertBanner>
          </div>
        )}
        <div className="form-grid form-grid--top">
          <label className="full-width"><span>{t('scripted.name')} <span className="req-star">*</span></span>
            <input value={form.name} autoFocus={!!dupSource}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
          <label className="full-width"><span>{t('scripted.description')}</span>
            <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} /></label>

          <label><span>{t('scripted.team')} <span className="req-star">*</span></span>
            {canPickTeam
              ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))}
                  options={[{ value: '', label: t('scripted.selectTeam') }, ...teamSelectOptions]} searchThreshold={2} />
              : <input value={teamName || t('scripted.selectTeam')} disabled />}</label>
          <label><span>{t('scripted.group')} <span className="req-star">*</span></span>
            <SearchableSelect
              value={form.groupName}
              onChange={v => setForm(f => ({ ...f, groupName: v }))}
              options={groupSelectOptions}
              creatable
              onCreate={() => {}}
              searchThreshold={2}
              placeholder={t('scripted.groupPick')} /></label>
          <NotifyChannels
            notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
            onChange={patch => setForm(f => ({ ...f, ...patch }))}
            teamLabel={selectedTeamLabel} teamId={form.teamId}
            groupId={form.notificationGroupId}
            onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />

          <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
            onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
          <label><span>{t('scripted.timeout')}</span>
            <input type="number" min="5" max="180" value={form.timeoutSeconds}
              onChange={e => setForm(f => ({ ...f, timeoutSeconds: e.target.value }))} />
            <span className="field-hint">{t('scripted.timeoutHint')}</span></label>
          <label><span>{t('scripted.confirmAttempts')}</span>
            <input type="number" min="0" max="10" value={form.confirmAttempts}
              onChange={e => setForm(f => ({ ...f, confirmAttempts: e.target.value }))} />
            <span className="field-hint">{t('scripted.confirmHint')}</span></label>
          <label><span>{t('scripted.recoveryChecks')}</span>
            <input type="number" min="1" max="10" value={form.recoveryChecks}
              onChange={e => setForm(f => ({ ...f, recoveryChecks: e.target.value }))} />
            <span className="field-hint">{t('scripted.recoveryHint')}</span></label>

          {/* Yavaş koşum alarmı (SCRIPTED_SLOW) — opt-in. Senaryo GEÇİYOR ama yavaşlıyorsa
              kesinti alarmı hiç açılmaz; bu eşik o sessiz bozulmayı görünür kılar. Teyit/kurtarma
              sayıları kesinti alarmıyla ORTAKTIR (aynı 3× doğrulama üssel zinciri). */}
          {/* checkbox-label: .form-grid label VARSAYILANI sütun yönlü ve input'lara metin-kutusu
              geometrisi (padding/kenarlık) veriyor — tik kutusu etiketin ÜSTÜNE düşüp kayıyordu.
              Buradaki eski `sc-check` sınıfının App.css'te hiç karşılığı yoktu (sessiz ölü sınıf).
              Bu sayfadaki diğer iki checkbox ile birebir aynı desen. */}
          <label className="checkbox-label full-width">
            <input type="checkbox" checked={!!form.slowResponseEnabled}
              onChange={e => setForm(f => ({ ...f, slowResponseEnabled: e.target.checked }))} />
            <span>{t('scripted.slowEnabled')}</span></label>
          <label><span>{t('scripted.slowThreshold')}</span>
            <input type="number" min="500" max="180000" step="500" value={form.slowThresholdMs}
              disabled={!form.slowResponseEnabled}
              onChange={e => setForm(f => ({ ...f, slowThresholdMs: e.target.value }))} />
            <span className="field-hint">{t('scripted.slowThresholdHint')}</span></label>

          {/* Kurumsal vekil — k6 alt süreci uzun süre vekil ayarlarını HİÇ almıyordu; vekil zorunlu
              ortamda her koşum sebepsiz "request timeout" ile düşüyordu. */}
          <label>{t('scripted.useProxy')}
            <select value={form.useProxy || 'AUTO'} onChange={e => setForm(f => ({ ...f, useProxy: e.target.value }))}>
              <option value="AUTO">{t('scripted.useProxyAuto')}</option>
              <option value="ON">{t('scripted.useProxyOn')}</option>
              <option value="OFF">{t('scripted.useProxyOff')}</option>
            </select>
            <span className="field-hint">{t('scripted.useProxyHint')}</span>
            {/* ETKİN karar — "AUTO seçtim, vekilden geçiyordur" varsayımı sahada dört sürüm boyunca
                yanlış teşhise sebep oldu: Go, NO_PROXY girdilerini SONEK olarak uygular
                (`example.com` ⇒ tüm alt alanlar), yani eşleşen hedef AUTO'da bile doğrudan çıkar. */}
            {proxy && !proxy.configured && (
              <span className="field-hint sc-proxy-note">{t('scripted.useProxyNotConfigured')}</span>
            )}
            {proxy?.configured && proxy.noProxy && form.useProxy !== 'OFF' && (
              <span className="field-hint sc-proxy-note">
                {t('scripted.useProxyEffectiveDirect')} <code>NO_PROXY={proxy.noProxy}</code>
              </span>
            )}</label>

          {/* Etiketler — kanonik TagInput (diğer tiplerle parite; payload'daki tags alanını doldurur) */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.tagsTitle')} <span className="req-star">*</span></div>
            <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('scripted.tagsPlaceholder')} suggestions={teamTags} />
            <span className="field-hint">{t('scripted.tagsHint')}</span>
          </div>

          {/* Script kaynağı — KAYITLI script'ler ve ŞABLONLAR ayrı gruplarda; ikisi karışmasın.
              Kayıtlı script'ler sayfada zaten yüklü listeden gelir (ek API yok) ve liste görme
              yetkisiyle süzülüdür. Boş seçenek YALNIZ yeni monitörde: düzenleme modunda
              monitörün kendi girdisi listede olduğu için "boşalt" yolu veri kaybettiriyordu. */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.scriptSource')}</div>
            {/* Aranabilir: script sayısı arttıkça ada göre süzmek şart. Gruplar (kayıtlı/takım/
                genel + yerleşiklerin 10 kategorisi) SearchableSelect'in `group` alanıyla korunuyor.
                `collapsibleGroups`: yerleşik katalog 100 şablon; düz liste hâlinde hem 100 satır
                uzunluğundaydı hem de bir script'in hangi kategoriden geldiği görünmüyordu. Dallar
                kapalı gelir, tıklanınca altındaki 10 script açılır. */}
            <div className="sc-source-select">
              <SearchableSelect
                ariaLabel={t('scripted.scriptSource')}
                value={form.template || ''}
                onChange={selectScriptSource}
                options={scriptSourceOptions}
                placeholder={t('scripted.templatePick')}
                collapsibleGroups
              />
            </div>
            {form.template?.startsWith('tpl:') &&
              <ScriptedTemplateInfo tpl={resolveTemplate(templates, form.template.slice(4))} lang={lang} t={t} />}
            {form.template?.startsWith('saved:') && selectedSavedName &&
              <span className="field-hint">{t('scripted.srcFromMonitor', selectedSavedName)}</span>}
          </div>

          {/* Script editörü — sürüm rozeti başlığın YANINDA: kullanıcı sözdizimini seçerken görsün. */}
          <div className="full-width">
            <div className="kw-block-title sc-script-title">
              <span>{t('scripted.script')}</span>
              <span className="sc-script-tools">
                <K6VersionBadge t={t} version={k6Version} withSyntaxNote />
                {/* Script'i panoya al — editörün içinden elle seçmek uzun script'te zahmetli
                    (kaydırma + seçimi kaçırma). Boşken düğme HİÇ çizilmez: copyText('') zaten
                    false döner ve onay ikonu hiç gelmez — ölü bir düğme bırakmayalım. */}
                {(form.script || '').trim() &&
                  <CopyButton value={form.script} variant="secondary"
                    label={t('scripted.scriptCopy')} copiedLabel={t('scripted.scriptCopied')} />}
              </span>
            </div>
            <CodeEditor value={form.script} onChange={code => setForm(f => ({ ...f, script: code }))}
              placeholder={t('scripted.scriptPlaceholder')} markers={markers} revealMarkers />
            <span className="field-hint">{t('scripted.scriptHint')}</span>
          </div>

          {/* Koşum sonucu — script bloğunun HEMEN ALTINDA: "üstte seçili script → altında ona ait
              sonuç" bağı görünür olsun. Eskiden formun en altındaydı; kullanıcı script'i
              değiştirdiğinde bayat panel çoğu zaman ekranın dışında kalıyordu.
              Kaynak etiketi ŞART: "az önce test mi koşturdum, kayıtlı son kontrol mü?" ayrımı. */}
          {testResult &&
            <div className="full-width sc-testrun">
              <div className="sc-testrun-head">
                <span className="sc-run-src">
                  {testResult._source === 'lastCheck' ? t('scripted.runSourceLast')
                    : testResult._source === 'smoke' ? t('scripted.runSourceSmoke')
                    : t('scripted.runSourceTest')}
                </span>
                <span className="sc-testrun-status" style={{ color: STATUS_COLOR[testResult.status] || 'inherit' }}>
                  {statusLabel(t, testResult.status)}</span>
                {checksSummary(t, testResult) &&
                  <span className="sc-testrun-meta"><ChecksSummary t={t} check={testResult} /></span>}
                {testResult.duration_ms != null && <span className="sc-testrun-meta">· {testResult.duration_ms} ms</span>}
                {exitLabel(t, testResult.exit_code) &&
                  <span className="sc-testrun-meta">· {exitLabel(t, testResult.exit_code)}</span>}
                {testResult._checkedAt &&
                  <span className="sc-testrun-meta">· {formatDateSec(testResult._checkedAt)}</span>}
                {testResult.output_tail &&
                  <CopyButton value={testResult.output_tail} label={t('scripted.errCopy')} copiedLabel={t('scripted.errCopied')} />}
              </div>
              {testResult.error &&
                <AlertBanner tone={isPass(testResult.status) ? 'success' : isWarnLike(testResult.status) ? 'warning' : 'danger'}>
                  <div className={`sc-err-msg${isCodeFrame(testResult.error) ? ' sc-err-msg--frame' : ''}`}>{testResult.error}</div>
                  {exitHint(t, testResult.exit_code) && <div className="sc-err-hint">{exitHint(t, testResult.exit_code)}</div>}
                  {/* Kaydetmeden ÖNCE görülmeli: sözdizimi duvarına çarpan kullanıcı burada anlasın. */}
                  {diagnosisHint(t, testResult, k6Version) &&
                    <div className="sc-err-hint">{diagnosisHint(t, testResult, k6Version)}</div>}
                </AlertBanner>}
              <RequestPhases t={t} check={testResult} viaProxy={testResult.via_proxy ?? null} />
              {testResult.output_tail && <pre className="show-pre sc-console">{testResult.output_tail}</pre>}
            </div>}

          {/* Env değişkenleri */}
          <div className="full-width">
            <div className="kw-block-title">{t('scripted.env')}</div>
            {form.env.length > 0 &&
              <div className="env-list">
                {form.env.map((e, i) => (
                  <div key={i} className="env-row">
                    <input className="input env-name" placeholder={t('scripted.envName')} value={e.name}
                      onChange={ev => setEnvRow(i, { name: ev.target.value })} />
                    <input className="input env-val" type={e.secret ? 'password' : 'text'} autoComplete="new-password"
                      placeholder={e.secret ? (e.value_set ? t('scripted.envSecretSet') : t('scripted.envSecretEmpty')) : t('scripted.envValue')}
                      value={e.value} onChange={ev => setEnvRow(i, { value: ev.target.value })} />
                    <label className="checkbox-label env-secret" title={t('scripted.envSecret')}>
                      {/* Değer KORUNUR. Eskiden `value: ''` yazılıyordu: kullanıcı 200 karakterlik
                          bir token yapıştırıp "Gizli"yi işaretleyince değer anında siliniyordu ve
                          alan `type=password` olduğu için bu görünmüyordu; kayıtta secret satırın
                          boş değeri hiç gönderilmediğinden env sunucuda BOŞ kalıyor, script
                          `__ENV.X = undefined` ile 401 alıyordu. Gizlilik zaten gösterimde
                          (`type=password`) ve saklamada (şifreli) sağlanıyor. */}
                      <input type="checkbox" checked={e.secret} onChange={ev => setEnvRow(i, { secret: ev.target.checked })} />
                      {e.secret ? <EyeOff size={14} /> : <Eye size={14} />} {t('scripted.envSecret')}
                    </label>
                    <button type="button" className="icon-btn env-del" title={t('scripted.delete')} onClick={() => delEnvRow(i)}><Trash2 size={15} /></button>
                  </div>
                ))}
              </div>}
            <Button type="button" variant="secondary" size="sm" className="env-add" onClick={addEnvRow}><Plus size={13} /> {t('scripted.envAdd')}</Button>
            <span className="field-hint">{t('scripted.envHint')}</span>
          </div>

          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} /> {t('scripted.active')}</label>

          {/* Sürüm artışı — YALNIZ mevcut monitörde anlamlı (yeni kayıt daima 1.0.0 ile doğar). */}
          {modal.id && (
            <label>{t('scripted.bumpTitle')}
              <select value={bumpType} onChange={e => setBumpType?.(e.target.value)}>
                <option value="patch">{t('scripted.bumpPatch')}</option>
                <option value="minor">{t('scripted.bumpMinor')}</option>
                <option value="major">{t('scripted.bumpMajor')}</option>
              </select>
              <span className="field-hint">{t('scripted.bumpHint')}</span></label>
          )}

          {/* Kaydetme uyarıları — inline ve KALICI (toast değil): kullanıcı düzeltene kadar durmalı. */}
          {/* Kaydetmeyi ENGELLEYEN hata: kod çerçevesi hizasını koruyan `--frame` ile KALICI.
              Eskiden yalnız 5 sn'lik toast'taydı ve `\n`'ler ezildiği için caret hizası kayboluyordu. */}
          {saveError &&
            <div className="full-width">
              <AlertBanner tone="danger" title={t('scripted.saveBlockedTitle')}>
                <div className={`sc-err-msg${isCodeFrame(saveError) ? ' sc-err-msg--frame' : ''}`}>{saveError}</div>
                {errorLines.length > 0 &&
                  <div className="sc-err-hint">{t('scripted.saveBlockedLine', errorLines.join(', '))}</div>}
              </AlertBanner>
            </div>}

          {saveWarnings?.length > 0 &&
            <div className="full-width">
              <AlertBanner tone="warning" title={t('scripted.saveWarnTitle')}>
                <ul className="sc-warn-list">{saveWarnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
              </AlertBanner>
            </div>}

          {/* Kaydetme sonrası doğrulama koşumu. Sürüm ZATEN kaydedildi — metin bunu söylüyor
              ve "Kapat" her an açık: koşum sunucuda sürer, sonucu Kontrol Geçmişi'ne düşer. */}
          {smoke && (
            <div className="full-width">
              <AlertBanner tone={smoke.state === 'skipped' ? 'warning' : 'info'}
                actions={
                  <Button type="button" variant="secondary" size="sm" onClick={dismissSmoke}>
                    {t('scripted.smokeClose')}
                  </Button>
                }>
                <span className="sc-smoke-msg">
                  {smoke.state === 'running' && <Spinner size={14} inline decorative />}
                  {smoke.state === 'running'  && t('scripted.smokeRunning')}
                  {smoke.state === 'queued'   && t('scripted.smokeQueued')}
                  {smoke.state === 'cooldown' && t('scripted.smokeCooldown')}
                  {smoke.state === 'skipped'  && t('scripted.smokeSkipped', smoke.reason || '')}
                </span>
              </AlertBanner>
            </div>
          )}

        </div>
        </div>
        <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
        <div className="modal-actions">
          <div style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
            <Button variant="secondary" onClick={runTest} disabled={testing} aria-busy={testing}>
              {testing ? <Spinner size={14} inline decorative /> : <FlaskConical size={14} />}
              {t('scripted.testRun')}</Button>
            {modal.id && canDelete && <Button variant="destructive" onClick={del}><Trash2 size={14} />{t('scripted.delete')}</Button>}
          </div>
          <Button variant="secondary" onClick={closeEdit}>{t('scripted.cancel')}</Button>
          <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.name.trim() || !form.teamId || !form.groupName.trim()}>{t('scripted.save')}</Button>
        </div>
      </div>
    </div>
  )
}
