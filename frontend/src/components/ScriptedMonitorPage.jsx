import { useState, useEffect, useCallback, useMemo, useRef, useId, lazy, Suspense } from 'react'
import { formatPercent } from '../i18n/dateLocale.js'
import { api, formatDateSec } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import CodeEditor from './ui/CodeEditor.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import TagInput from './ui/TagInput.jsx'
import ScriptedTemplateInfo from './scripted/ScriptedTemplateInfo.jsx'
import { useScriptedTemplates } from '../hooks/useScriptedTemplates.js'
import { buildScriptSourceOptions, resolveTemplate } from '../utils/scriptSourceOptions.js'
import { FlaskConical, Play, Plus, Trash2, Eye, EyeOff, AlertTriangle, LayoutDashboard, CheckCircle2, WifiOff, Siren, BellDot, PauseCircle, ChevronDown, Terminal, FileCode2, Copy } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { collectK6Markers } from '../utils/k6Errors.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import ScriptedVersionsTab from './scripted/ScriptedVersionsTab.jsx'
import ScriptedTemplatesTab from './scripted/ScriptedTemplatesTab.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock, Spinner } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import HintPopover from './ui/HintPopover.jsx'
import CopyButton from './ui/CopyButton.jsx'
import { exitLabel, exitHint, diagnosisHint, k6SyntaxLevel, readPhases, formatBytes,
  checksSummary, stuckLabel } from './scriptedExitCodes.js'
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { VersionChip } from './scripted/VersionTimeline.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { FieldDescription } from '@/components/shadcn/field'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { TabsContent } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import ScriptedMonitorCard from './scripted/ScriptedMonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import { MonitorFormModal, FormGrid, FormField, CheckField, FormSection } from './monitoring/MonitorForm.jsx'
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
  name: '', description: '', groupName: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  intervalSeconds: 300, timeoutSeconds: 60, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true, script: '', env: [], template: '',
  slowResponseEnabled: false, slowThresholdMs: 15000,
  useProxy: 'AUTO',
}

/** Otomatik taslak aralıkları — WeeklyReportsPage ile aynı büyüklük sınıfı (1,5 sn yazım sonrası, periyodik ağ yazımı). */
const DRAFT_DEBOUNCE_MS = 1500
const DRAFT_INTERVAL_MS = 30000

/** Koşum durumunun metin rengi (test paneli) — eski satır içi hex paletinin token/Tailwind karşılığı, koyu tema dahil. */
const STATUS_TONE = {
  PASS: 'text-success',
  FAIL: 'text-amber-600 dark:text-amber-400',
  ERROR: 'text-destructive',
  TIMEOUT: 'text-amber-700 dark:text-amber-400',
  NO_CHECKS: 'text-amber-600 dark:text-amber-400',
  unknown: 'text-muted-foreground',
}
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
    <span data-slot="k6-version" className="inline-flex flex-wrap items-center gap-2">
      {/* Sürüm rozeti (eski .sc-k6ver-chip) — shadcn Badge; açıklama DOKUN-GÖR (HintPopover: telefonda da açılır). */}
      <HintPopover content={t('scripted.k6VersionTitle')}>
        <Badge variant="outline" className="gap-1 bg-muted/60 font-mono text-[11px] font-semibold text-muted-foreground">
          <Terminal aria-hidden="true" />k6 {version}
        </Badge>
      </HintPopover>
      {withSyntaxNote && level && (
        <span data-slot="k6-syntax-note" data-level={level}
          className={cn('text-[11px] font-normal', level === 'legacy' ? 'text-warning' : 'text-muted-foreground')}>
          {level === 'legacy' ? t('scripted.k6LegacySyntax') : t('scripted.k6ModernSyntax')}
        </span>
      )}
    </span>
  )
}

/** Backend çok satırlı hata döndürdüyse bu bir k6/Babel kod çerçevesidir (hizalı caret taşır). */
function isCodeFrame(error) { return typeof error === 'string' && error.includes('\n') }

/**
 * Hata metni (eski .sc-err-msg / --frame). Çok satırlı hata = k6/Babel kod çerçevesi: caret (^) bir
 * üstteki satırla SÜTUN SÜTUN hizalı, sarma onu kaydırır → sarma yerine yatay kaydırma + eş aralıklı
 * yazı tipi. Tek satırlı hata normal sarılır. Test kancası: data-slot="error-text" + data-frame.
 */
function ErrorText({ text }) {
  const frame = isCodeFrame(text)
  return (
    <div data-slot="error-text" data-frame={frame ? 'true' : undefined}
      className={frame
        ? 'w-full min-w-0 max-w-full justify-self-stretch overflow-x-auto font-mono text-[11.5px] leading-[1.55] whitespace-pre [overflow-wrap:normal]'
        : 'whitespace-pre-wrap [overflow-wrap:anywhere]'}>
      {text}
    </div>
  )
}

/** Hata kutusundaki ek satır (eski .sc-err-hint): çare / motor ipucu. */
function ErrHint({ children }) {
  return <div className="mt-1 text-[.9em] italic">{children}</div>
}

/** Ham k6 çıktısı bloğu (eski .show-pre + .sc-output/.sc-console) — tema jetonlu, kendi içinde kayar. */
const OUTPUT_PRE = 'm-0 mt-1 overflow-auto rounded border border-border bg-muted/40 px-3 py-2.5 font-mono text-[11.5px] leading-[1.65] whitespace-pre-wrap text-foreground'

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

export default function ScriptedMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const isAdminish = isAdmin || isTeamAdmin            // form/team-select davranışı (mevcut semantik korunur)
  const myTeam = teamId != null ? String(teamId) : null
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId } = useMonitorTeamPick({ isAdmin: isAdminish, adminTeams: teams, myTeams, teamId })   // 2026-09-18
  // Yeni izlemenin varsayılan takımı (2026-09-26: USER da izleme ekleyebilmeli). Oturumun birincil takımı
  // (teamId) boş gelebiliyor — tek takımlı kullanıcıda o zaman ÜYESİ olduğu tek takım seçili doğar; aksi hâlde
  // Kaydet "takım zorunlu" diye kilitli kalıyordu. Hook `defaultTeamId` verirse o esastır.
  const defaultTeam = defaultTeamId != null
    ? String(defaultTeamId)
    : (myTeam ?? (myTeams.length === 1 ? String(myTeams[0].id) : ''))

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
  // Doğrulama koşumunun tur sayacı: form açılış/kapanışında artar → A'nın geç sonucu B'nin formuna düşmesin
  // (düşerse bandın "Kapat"ı B'nin kaydedilmemiş taslağını skipDraft ile atıyordu).
  const smokeSeq = useRef(0)
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
  // Kart yoğunluğu (2026-09-27): Kompakt / Zengin — sayfa HER AÇILIŞTA Zengin başlar; Kompakt seçimi yalnız sayfada
  // kalındığı sürece geçerli, kalıcı DEĞİL (kullanıcı kararı; bkz. hooks/useCardDensity)
  const [density, setDensity] = useCardDensity('scripted')
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
  const [selected, setSelected] = useState(null) // detail monitor (Escape'i ModalShell kapatır)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [detailTab, setDetailTab] = useState('control')
  const deepLinkTab = useDeepLinkTab()   // ?monitor=…&mtab=changes derin bağlantısı — ilk açılışta bir kez
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

  // Duraklatılmış kartta tek tıkla "Sürdür" (2026-09-26, tüm izleme sayfalarında varsayılan). Yazma yolu toplu
  // işlem çubuğuyla aynı (`{ active: true }`); sunucu yalnız gelen anahtarları uygular ve anomali kapatmasının
  // sebebini (`disabled_reason`) yeniden açılışta temizler.
  const { resume, isResuming } = useMonitorResume(api.monitoring.updateScriptedMonitor, load)

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

  function openDetail(m) { setSelected(m); setSelCheck(null); setSummary({ total: 0, down: 0 }); setDetailTab(deepLinkTab()) }
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
    listKey: 'scripted-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
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

  // Durum sözlüğü (kart şeridi / rozet / detay kenarı): up | down | warn | unknown. `warn` bu türe
  // özgü MEŞRU dördüncü durum: NO_CHECKS (koştu ama hiçbir şey doğrulanmadı) arıza değil.
  const statusKey = (m) => (isPass(m?.status) ? 'up' : isFailLike(m?.status) ? 'down' : isWarnLike(m?.status) ? 'warn' : 'unknown')
  function statusBadge(m) {
    return <MonitorStatusBadge status={statusKey(m)}>{statusLabel(t, m?.status)}</MonitorStatusBadge>
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
    smokeSeq.current++   // önceki formun uçuşan doğrulama koşumu bu formu DOLDURMASIN
    setForm({ ...emptyForm, teamId: isAdminish ? '' : defaultTeam,
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
      name: m.name || '', description: m.description || '', groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
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
    smokeSeq.current++   // önceki formun uçuşan doğrulama koşumu bu formu DOLDURMASIN
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
    smokeSeq.current++   // önceki formun uçuşan doğrulama koşumu bu formu DOLDURMASIN
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
    smokeSeq.current++   // uçuşan doğrulama koşumunun sonucu kapanmış forma (ya da sonraki forma) yazılmasın
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
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
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
    const my = ++smokeSeq.current
    setSmoke({ state: 'running' })
    setTestResult(null)
    let res
    try {
      res = await api.monitoring.triggerScriptedCheck(id)
    } catch (e) {
      // Ağ hatası (request() THROW eder): bant "koşuyor"da takılı kalmasın.
      if (my !== smokeSeq.current) return
      setSmoke(null); toast.error(e?.message || t('scripted.triggerError'))
      return
    }
    // Form o arada kapatıldı / başka forma geçildi → sonuç bu forma AİT DEĞİL, yazılmaz.
    if (my !== smokeSeq.current) return
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
        // İşlevsel güncelleme (bayat kapanış YOK): yanıt gelene kadar pencere kapanmış ya da başka izlemeye
        // geçilmiş olabilir — A'nın sonucu B'nin penceresini değiştirmesin / kapalı pencereyi yeniden açmasın.
        setSelected(prev => (prev?.id === m.id ? res.data : prev))
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      } else if (res && !silent) toast.error(res.error || t('scripted.triggerError'))
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // Detay penceresi açıkken form ONUN İÇİNDE çizilir (aşağıda `{formModal}`), aksi hâlde sayfa düzeyinde.
  const formModal = modal && (
    <EditModal {...{ t, lang, k6Version: k6.version, proxy, form, setForm, modal, dupSource, saving, testing, testResult, saveWarnings, saveError, smoke, dismissSmoke: () => { setSmoke(null); closeEdit({ skipDraft: true }) }, save, del, closeEdit, runTest, isAdminish, canPickTeam, canDelete: modal?.id ? canDeleteRow(modal) : false, teamSelectOptions, teamName, groupSelectOptions, teamTags, setEnvRow, addEnvRow, delEnvRow, selectScriptSource, savedScripts, savedSource, templates: scriptTemplates, draftSavedAt, pendingDraft, applyDraft, discardDraft, bumpType, setBumpType, canOpenSettings: globalAdmin }} />
  )

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="upt-page">
      {/* Motor sürümü BAŞLIĞIN yanında, parantez içinde: bir eylem değil, ekranın neyle çalıştığının künyesi.
          Sürüm okunamıyorsa parantez HİÇ çizilmez. Meta + eylem kümesinin TAMAMI monitör görünümüne aittir
          (sayaç/Yenile monitör listesini tazeler, kılavuz MONİTÖR formunu anlatır) → Şablonlar görünümünde
          çizilmez (showActions); şablon sekmesinin kendi düğmeleri ScriptedTemplatesTab içinde. */}
      <MonitorPageHeader type="scripted" subtitle={t('scripted.subtitle')}
        title={<>
          {t('scripted.title')}
          {k6.version && (
            <HintPopover content={t('scripted.k6VersionTitle')} triggerClassName="ml-1.5 align-baseline">
              <span data-slot="k6-title"
                className="text-[.62em] font-medium whitespace-nowrap text-muted-foreground tabular-nums">(k6 {k6.version})</span>
            </HintPopover>
          )}
        </>}
        showActions={view === 'monitors'}
        count={loading ? null : monitors.length} down={counts.down}
        refreshIn={REFRESH_INTERVAL - secondsSince} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={k6.canManage && k6.available} onNew={openNew} newLabel={t('scripted.addMonitor')}>
        {/* Görünüm anahtarı: monitörler ↔ şablon kütüphanesi — bir EYLEM değil görünüm seçicisi, yeri başlığın
            altı. Şablon izni yoksa HİÇ çizilmez (403 yiyecek bir düğme yerine yüzey yok). */}
        {canViewTemplates && (
          <div>
            <SegmentedControl value={view} onChange={setView} ariaLabel={t('tpl.viewSwitch')}
              options={[
                { value: 'monitors', label: t('tpl.viewMonitors'), icon: LayoutDashboard },
                { value: 'templates', label: t('tpl.viewTemplates'), icon: FileCode2 },
              ]} />
          </div>
        )}
      </MonitorPageHeader>

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
          {/* Kart görünümü seçicisi araç çubuğunun İLK öğesi (mr-auto → süzgeçler ve arama sağda kalır; telefonda satır sarar) */}
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('scripted.searchPlaceholder')} aria-label={t('scripted.searchPlaceholder')}
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
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="SCRIPTED"
          api={{ update: api.monitoring.updateScriptedMonitor, remove: api.monitoring.deleteScriptedMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Kart sunumu scripted/ScriptedMonitorCard'da (MonitorCard ailesi, stretched button). Sayfaya ait kablolama
               yuva olarak geçer: durum sözlüğü (statusKey/statusBadge — detay penceresiyle aynı kaynak), toplu seçim
               kutusu (seçim kümesi burada) ve eylemler (yetki + işleyiciler burada). Duraklatılmış = `active === false`
               — sayfanın "Duraklatılan" sayacı/süzgeciyle AYNI yüklem. */
            <ScriptedMonitorCard key={m.id} monitor={m} status={statusKey(m)} badge={statusBadge(m)} density={density} onOpen={() => openDetail(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.name)} />
              )}
              actions={canManageRow(m) && (
                <MonitorCardActions rowLabel={m.name}
                  running={isRunning(m.id)} checkDisabled={!k6.available}
                  onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('scripted.runNow')} editTitle={t('scripted.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('scripted.delete')}
                  onResume={() => resume(m)} resuming={isResuming(m.id)} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) — 7 sekme (Kontrol / Alarm / Grafik / Sürümler / Teşhis / Rehber&Notlar / Değişiklikler) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={statusKey(selected)} badge={statusBadge(selected)} title={selected.name} nocNotify={!!selected.noc_notify}
          actions={
              /* CSV butonu kaldırıldı: mükerrerdi ve bozuktu (tanımsız `history` → window.history →
                  "history.map is not a function"). Çalışan, sunucu-taraflı CSV linkini Kontrol
                  Geçmişi sekmesi zaten sunuyor (CheckHistoryTab).
                 Eylemler KARTIN aynısı (MonitorModalActions). Buradaki "Çalıştır" eskiden tek
                  başına, etiketli bir `btn-primary` idi: diğer sekiz türde aynı iş ikon düğmesi,
                  burada birincil düğmeydi ve Düzenle/Kopyala hiç yoktu. k6 koşulu KORUNUR —
                  k6 kurulu değilken sentetik koşu anlamsız (`checkDisabled`). */
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
          }>
          <DetailDivider className="mt-0" />
          {/* Çalışan script sürümü: `VersionChip` BİLİNÇLİ kullanılmıyor — o satır-içi küçük bir rozet,
              buradaki metrik değerinin yerinde yanındaki beş metrikle kavga eder. `≠` işareti: son
              koşum GÜNCEL sürümle yapılmamış (kaydedildi ama henüz çalışmadı) — "son düzenlemem bozdu
              mu?" sorusunun ilk yarısı. Açıklaması metriğin ipucunda + ekran okuyucu metninde. */}
          {(() => {
            const drift = !!(selected.script_version && selected.run_script_version
              && selected.run_script_version !== selected.script_version)
            const driftHint = drift ? t('scripted.verDriftHint', selected.run_script_version) : null
            return (
              <DetailSummary items={[
                { key: 'uptime', value: summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—',
                  label: `${t('scripted.sumUptime')}${summary.total > 0 ? ` · ${summary.total - summary.down}/${summary.total}` : ''}`,
                  hint: t('scripted.sumUptimeHint') },
                { key: 'total', value: summary.total, label: t('scripted.sumTotal'), hint: t('scripted.sumTotalHint') },
                { key: 'inc', value: summary.down, label: t('scripted.sumIncidents'), hint: t('scripted.sumIncidentsHint') },
                selected.duration_ms != null && { key: 'dur', value: `${selected.duration_ms}ms`, label: t('scripted.lastDuration') },
                selected.script_version && { key: 'ver',
                  value: <>v{selected.script_version}{drift && (
                    <HintPopover content={driftHint} triggerClassName="ml-1.5 align-baseline">
                      <span data-slot="version-drift" className="text-xs font-bold text-amber-700 dark:text-amber-300">
                        <span aria-hidden="true">≠</span><span className="sr-only">{driftHint}</span>
                      </span>
                    </HintPopover>
                  )}</>,
                  label: t('scripted.sumVersion'),
                  hint: drift ? `${t('scripted.sumVersionHint')} ${driftHint}` : t('scripted.sumVersionHint') },
                checksSummary(t, selected) && { key: 'checks', value: <ChecksSummary t={t} check={selected} />, label: t('scripted.checks') },
                selected.checked_at && { key: 'last', value: formatDateSec(selected.checked_at), label: t('scripted.lastCheck'), time: true },
              ]} />
            )
          })()}
          <DetailDivider />
          {/* Sekme değişince seçili koşum DÜŞMEZ (eski davranış): koşum detayı yalnız Kontrol
              sekmesinde çizilir, geri dönülünce kaldığı yerden görünür. */}
          <DetailTabs value={detailTab} onValueChange={setDetailTab}
            countsFor={{ kind: 'scripted', monitorId: selected.id, notesType: 'SCRIPTED', notesTarget: selected.name, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['control', t('hist.tab')], ['alerts', t('scripted.tabAlerts')], ['chart', t('scripted.tabChart')],
              ['versions', t('scripted.tabVersions')], ['diag', t('scripted.tabDiag')], ['notes', t('scripted.tabGuide')],
              // Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
              // orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            {/* Anomali guard'ı kapattıysa sebep HER SEKMEDE görünür: kullanıcı "izleme neden
                veri üretmiyor?" sorusunu sekme gezerek aramasın. Uyarı ancak izleme yeniden
                açılınca düşer (sunucu `disabled_reason`u orada temizler). Sekme listesinin
                ALTINDA, sekme içeriklerinin DIŞINDA durur — hangi sekme açık olursa olsun çizilir. */}
            {selected.disabled_reason && (
              <AlertBanner tone="danger" icon={AlertTriangle} title={t('scripted.autoDisabledTitle')} className="mb-0">
                <div>{selected.disabled_reason}</div>
                {selected.disabled_at &&
                  <div className="text-xs">{t('scripted.autoDisabledAt', formatDateSec(selected.disabled_at))}</div>}
                <div className="text-xs">{t('scripted.autoDisabledHow')}</div>
              </AlertBanner>
            )}

            <TabsContent value="control">
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
                  const toggle = () => setSelCheck(isSel ? null : c)
                  return (<>
                    {/* Satırı AÇAN kontrol GERÇEK bir düğme (shadcn Button): klavyeyle (Tab +
                        Enter/Space) açılır, ekran okuyucu düğme olarak duyurur. Erişilebilir ad
                        ZAMANI taşır — aynı durum art arda tekrarladığında satırlar birbirinden ancak
                        böyle ayrılıyor (kardeş yüzey: HttpMonitorPage geçmiş satırı). Odak YALNIZ bu
                        hücrede: dört hücrenin dördü de odaklanabilir olsaydı satır başına dört durak
                        olurdu; diğer hücreler yalnız fare kolaylığı. Hücre kabı ızgara hücresi
                        (`upt-rt-time`) olarak kalır — düğmeye legacy sınıf konmaz. */}
                    <span className="upt-rt-time">
                      <Button type="button" variant="ghost" size="xs" onClick={toggle} aria-expanded={isSel}
                        aria-label={`${formatDateSec(c.checked_at)} · ${statusLabel(t, c.status)} — ${t('scripted.rowOpenAria')}`}
                        className="h-auto justify-start rounded-sm px-0 py-0 font-mono font-normal text-muted-foreground hover:bg-transparent hover:text-foreground hover:underline dark:hover:bg-transparent">
                        {formatDateSec(c.checked_at)}
                      </Button>
                    </span>
                    <span className={cn(isPass(c.status) ? 'upt-rt-up' : isWarnLike(c.status) ? 'upt-rt-warn' : 'upt-rt-down', 'cursor-pointer', isSel && 'font-bold')}
                      onClick={toggle}>{statusLabel(t, c.status)}</span>
                    {/* Bu koşumun HANGİ sürümle yapıldığı. Boş olabilir ve bu MEŞRU: sürümleme
                        öncesi kayıtlar ile yalnız ayarı kaydedilmiş (sürüm yazılmamış) monitörler.
                        O durumda `—` basılır; çıplak `v` ASLA basılmaz. */}
                    <span className="upt-rt-ms">
                      {c.script_version
                        ? <VersionChip>v{c.script_version}</VersionChip>
                        : '—'}
                    </span>
                    <span className="upt-rt-ms">{c.duration_ms != null ? `${c.duration_ms}ms` : '—'}</span>
                    {/* Detay hücresinin işi ÖZET + panele davet. Zenginlik (kod çerçevesi, çıkış
                        kodu etiketi, faz kırılımı, k6 çıktısı) satıra tıklayınca açılan
                        CheckDetail panelinde. Eskiden burada ham `error` basılıyordu: backend bu
                        metni 14 satır / 1500 karaktere kadar üretiyor (ERR_MAX_*) ve tek satır
                        ekranı dolduruyordu. Hücre ilk satırı gösterir; tam metin `title`'da
                        (kırpılan metnin tamamı — MonitorCardTitle ile aynı desen). */}
                    {c.error
                      ? <span className="upt-rt-error cursor-pointer" title={c.error}
                          onClick={toggle}>
                          {firstLine(c.error)}
                          {stuckLabel(t, c) && (
                            <Badge variant="outline" data-slot="stuck-chip"
                              className="ml-1.5 px-[7px] py-0 align-baseline text-[.92em] font-normal text-muted-foreground">
                              {stuckLabel(t, c)}
                            </Badge>
                          )}
                        </span>
                      : checksSummary(t, c)
                        ? <span className="upt-rt-ms cursor-pointer"
                            onClick={toggle}><ChecksSummary t={t} check={c} /></span>
                        : <span className="upt-rt-ms">—</span>}
                  </>)
                }} />
              {selCheck && <CheckDetail t={t} check={selCheck} k6Version={k6.version} />}
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.name} types={alertTypesFor('scripted')} /></TabsContent>

            <TabsContent value="diag"><DiagTab t={t} monitor={selected} canRun={canManageRow(selected)} /></TabsContent>

            <TabsContent value="versions">
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
            </TabsContent>

            <TabsContent value="chart">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="scripted" />
              </Suspense>
            </TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="SCRIPTED" target={selected.name} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="scripted" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

          {/* Detay açıkken düzenleme formu ONUN İÇİNDE çizilir: ModalShell iç içe derinliği React
              ağacından okur, form (ve örtüsü) detay penceresinin ÜSTÜNDE katmanlanır. */}
          {formModal}
        </MonitorDetailModal>
      )}
      </>)}

      {!selected && formModal}

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
  // shadcn Card (eski .sc-phases kutusu). Test kancası: data-request-phases + satırda data-phase.
  return (
    <Card data-request-phases="true" className="mt-2.5 gap-2 rounded-lg bg-muted/30 px-3 py-2.5 shadow-none">
      <div className="flex flex-wrap items-baseline gap-2">
        <span className="text-[.92em] font-semibold">{t('scripted.phasesTitle')}</span>
        {viaProxy != null && (
          <Badge variant="outline" data-slot="phases-proxy" className="font-normal text-muted-foreground">
            {viaProxy ? t('scripted.viaProxyYes') : t('scripted.viaProxyNo')}
          </Badge>
        )}
      </div>
      <ol className="m-0 grid list-none gap-0.5 p-0">
        {phases.map(p => {
          const stuck = p.key === stuckAt
          const state = stuck ? 'stuck' : p.done ? 'done' : 'skipped'
          return (
            <li key={p.key} data-phase={state}
              className={cn('flex justify-between gap-3 rounded px-1.5 py-[3px] text-[.88em]',
                stuck && 'border border-destructive/35 bg-destructive/10 font-semibold',
                state === 'skipped' && 'opacity-55')}>
              <span className={stuck ? 'text-destructive' : p.done ? 'text-foreground' : 'text-muted-foreground'}>{t(`scripted.phase_${p.key}`)}</span>
              <span className="whitespace-nowrap tabular-nums">{p.ms == null ? '—' : `${p.ms} ms`}</span>
            </li>
          )
        })}
      </ol>
      {stuckAt && <div className="text-[.88em] text-destructive">{t('scripted.phaseStuck', t(`scripted.phase_${stuckAt}`))}</div>}
      {(sent || received) && (
        <div className="text-[.84em] text-muted-foreground tabular-nums">{t('scripted.phaseBytes', sent ?? '—', received ?? '—')}</div>
      )}
    </Card>
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
    // try/catch ŞART: request() ağ hatasında THROW eder; yakalanmazsa durum { loading } kalır ve
    // "Çalıştır" düğmesi sekme yeniden açılana kadar kilitli dönerdi.
    let res
    try {
      res = await api.monitoring.diagnoseScripted(monitor.id, url.trim() || undefined)
    } catch (e) {
      setState({ error: e?.message || t('scripted.diagError') })
      return
    }
    setState(res?.success ? { data: res.data } : { error: res?.error || t('scripted.diagError') })
    if (res?.success && !url) setUrl(res.data?.url || '')
  }

  return (
    <div data-slot="diag" className="grid gap-2.5">
      <p className="text-xs text-muted-foreground">{t('scripted.diagIntro')}</p>
      <div className="flex flex-wrap items-center gap-2">
        <Input className="min-w-0 flex-[1_1_280px]" value={url} onChange={e => setUrl(e.target.value)}
          placeholder={t('scripted.diagUrlPlaceholder')} aria-label={t('scripted.diagUrl')} />
        <Button type="button" onClick={run} disabled={state.loading || !canRun} aria-busy={state.loading || undefined}>
          {state.loading ? <Spinner size={14} inline decorative /> : <Play size={14} aria-hidden="true" />} {t('scripted.diagRun')}
        </Button>
      </div>
      {!canRun && <p className="text-xs text-muted-foreground">{t('scripted.diagNoPermission')}</p>}

      {state.loading && <LoadingBlock label={t('scripted.diagRunning')} />}
      {state.error && <AlertBanner tone="danger" icon={AlertTriangle}>{state.error}</AlertBanner>}

      {state.data && (<>
        {/* Vekil kararı burada da yazılı: "AUTO seçtim, vekilden geçiyordur" varsayımı sahada
            dört sürüm boyunca yanlış teşhise sebep oldu (NO_PROXY sonek eşleşmesi). */}
        <div data-slot="diag-meta" className="flex flex-wrap gap-3.5 text-[.86em] text-muted-foreground">
          <span><strong>{t('scripted.diagTarget')}:</strong> <code className="[word-break:break-all]">{state.data.url}</code></span>
          <span>k6 {state.data.k6_version}</span>
          {state.data.proxy_configured
            ? (
              <SimpleTooltip content={state.data.no_proxy}>
                <span>NO_PROXY=<code className="[word-break:break-all]">{state.data.no_proxy || '—'}</code></span>
              </SimpleTooltip>
            )
            : <span>{t('scripted.useProxyNotConfigured')}</span>}
        </div>
        <div className="grid gap-2.5">
          {(state.data.legs || []).map(leg => (
            // Bacak kutusu shadcn Card. Sonuç renkli SOL ŞERİTLE değil (kalıcı kural 2026-09-26: kartta/pencerede
            // renkli sol şerit YOK), durum etiketinin tonuyla söylenir (eski .sc-diag-leg--ok/--bad).
            <Card key={leg.key} data-diag-leg={leg.key} data-ok={leg.ok ? 'true' : 'false'}
              className="min-w-0 gap-1.5 rounded-lg px-3 py-2.5 shadow-none">
              <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
                <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{leg.label}</span>
                <span data-slot="leg-status" className={cn('text-[.88em] font-semibold', leg.ok ? 'text-success' : 'text-destructive')}>{statusLabel(t, leg.status)}</span>
                {leg.duration_ms != null && <span className="text-[.84em] text-muted-foreground tabular-nums">{leg.duration_ms} ms</span>}
              </div>
              {leg.error && <ErrorText text={leg.error} />}
              <RequestPhases t={t} check={leg} viaProxy={leg.via_proxy} />
            </Card>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">{t('scripted.diagHowToRead')}</p>
      </>)}
    </div>
  )
}

/**
 * Doğrulama özeti — "✓ 2 doğrulama geçti".
 *
 * Dört ekranda (kart metriği, detay modalı özeti, kontrol geçmişi hücresi, test koşumu paneli)
 * AYNI ifadeyi kullanır. Öncesinde hepsi ham `2✓/0✗` basıyordu ve kullanıcı "bu nedir
 * anlaşılmıyor" dedi — sayının k6 `check()` doğrulamaları olduğu hiçbir yerde yazmıyordu.
 * İkon tek başına anlam taşımaz; yanındaki kelime taşır (renk körlüğü + bağlamsızlık).
 * Test kancası: data-slot="checks-summary" + data-tone (ok|bad|none).
 */
function ChecksSummary({ t, check }) {
  const s = checksSummary(t, check)
  if (!s) return null
  return (
    <span data-slot="checks-summary" data-tone={s.tone}
      className={cn('whitespace-nowrap', s.tone === 'none' && 'text-muted-foreground')}>
      <span aria-hidden="true"
        className={cn('mr-1 font-bold', s.tone === 'ok' && 'text-success', s.tone === 'bad' && 'text-destructive')}>{s.icon}</span>{s.text}
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
    <div data-slot="check-detail" className="mt-3">
      {checks.length > 0 && (
        <ul className="m-0 mb-2.5 list-none p-0">
          {checks.map((c, i) => (
            <li key={i} className="border-b border-border py-1 text-[.92em]">
              <span className={cn('font-bold', c.passed ? 'text-success' : 'text-destructive')}>{c.passed ? '✓' : '✗'}</span> {c.name}
            </li>
          ))}
        </ul>
      )}

      {(check.error || label) && (
        <AlertBanner tone={tone} title={t('scripted.errTitle')}>
          {/* Çok satırlı hata = k6/Babel kod çerçevesi → monospace + yatay kaydırma, yoksa caret kayar. */}
          {check.error && <ErrorText text={check.error} />}
          {label && <div data-slot="exit-label" className="mt-1 text-[.9em] opacity-85">{label}{exitCode != null ? ` · exit ${exitCode}` : ''}</div>}
          {hint && <ErrHint>{hint}</ErrHint>}
          {engineHint && <ErrHint>{engineHint}</ErrHint>}
        </AlertBanner>
      )}

      <RequestPhases t={t} check={check} viaProxy={check.via_proxy ?? check.viaProxy ?? null} />

      {outputTail && (
        // Katlanan teknik detay (eski .mhow-toggle aç-kapa) — shadcn Collapsible; kapalıyken içerik DOM'da yok.
        <Collapsible open={openTech} onOpenChange={setOpenTech} className="mt-2.5">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" className="w-full justify-start gap-2 px-3 font-semibold">
              <Terminal size={15} aria-hidden="true" className="text-violet-600 dark:text-violet-400" />
              <span>{t('scripted.errTech')}</span>
              <ChevronDown size={15} aria-hidden="true"
                className={cn('ml-auto text-muted-foreground transition-transform duration-200 motion-reduce:transition-none', openTech && 'rotate-180')} />
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="mt-2 mb-1.5 flex items-center gap-2">
              {/* Tam metin her zaman kopyalanabilir — kırpılmış hâli değil. */}
              <CopyButton value={outputTail} label={t('scripted.errCopy')} copiedLabel={t('scripted.errCopied')} />
              {lines.length > CLAMP && (
                <Button type="button" variant="outline" size="sm" onClick={() => setShowAll(v => !v)}>
                  {showAll ? t('scripted.outShowLess') : t('scripted.outShowAll', lines.length)}
                </Button>
              )}
            </div>
            <pre className={cn(OUTPUT_PRE, 'max-h-80')}>{shown}{clamped ? '\n…' : ''}</pre>
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  )
}

// ── Create/Edit modal ────────────────────────────────────────────────────────
function EditModal({ t, lang, k6Version, proxy = null, form, setForm, modal, dupSource, saving, testing, testResult, saveWarnings, saveError, smoke = null, dismissSmoke, save, del, closeEdit, runTest, isAdminish, canPickTeam = isAdminish, canDelete, teamSelectOptions, teamName, groupSelectOptions, teamTags = [], setEnvRow, addEnvRow, delEnvRow, selectScriptSource, savedScripts = [], savedSource = null, templates = [], draftSavedAt = null, pendingDraft = null, applyDraft, discardDraft, bumpType = 'patch', setBumpType, canOpenSettings = false }) {
  // Pencere ortak MonitorFormModal (ui/ModalShell): sabit başlık + kaydırılan gövde + sabit alt çubuk
  // + "devamı için kaydırın" ipucu orada TEK kopya (kapı modalScroll.test.jsx).
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

  // Kurumsal vekilin ETKİN kararı — alanın ipucuna eklenir (aria-describedby ile bağlı kalır).
  // "AUTO seçtim, vekilden geçiyordur" varsayımı sahada dört sürüm boyunca yanlış teşhise sebep
  // oldu: Go, NO_PROXY girdilerini SONEK olarak uygular (`example.com` ⇒ tüm alt alanlar), yani
  // eşleşen hedef AUTO'da bile doğrudan çıkar.
  const proxyNote = proxy && !proxy.configured
    ? <span className="mt-1 block">{t('scripted.useProxyNotConfigured')}</span>
    : proxy?.configured && proxy.noProxy && form.useProxy !== 'OFF'
      ? <span className="mt-1 block">{t('scripted.useProxyEffectiveDirect')} <code className="text-[.95em] [word-break:break-all]">NO_PROXY={proxy.noProxy}</code></span>
      : null

  return (
    <MonitorFormModal onClose={closeEdit} icon={FlaskConical} width={860}
      title={<>
        {modal.id ? t('scripted.modalEdit') : t('scripted.modalNew')}
        {modal.script_version && <VersionChip className="ml-2 align-middle">v{modal.script_version}</VersionChip>}
        {/* Otomatik kayıt göstergesi: kullanıcı "kaydettim mi?" diye tereddüt etmesin. */}
        {draftSavedAt && (
          <span data-slot="draft-saved" className="ml-2.5 text-[11px] font-semibold text-success">
            ✓ {t('scripted.autoSaved')} {draftSavedAt.toLocaleTimeString(lang === 'tr' ? 'tr-TR' : 'en-GB')}
          </span>
        )}
      </>}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Çalışıyor… N sn): alt çubuktaki düğme metinleri sabit kalır, hiçbir düğme kaymaz.
      busyLabel={saving ? t('mon.saving') : testing ? t('scripted.testing') : null}
      footer={<>
        <div className="mr-auto flex flex-wrap gap-2">
          <Button variant="secondary" onClick={runTest} disabled={testing} aria-busy={testing || undefined}>
            {testing ? <Spinner size={14} inline decorative /> : <FlaskConical size={14} aria-hidden="true" />}
            {t('scripted.testRun')}</Button>
          {modal.id && canDelete && <Button variant="destructive" onClick={del}><Trash2 size={14} aria-hidden="true" />{t('scripted.delete')}</Button>}
        </div>
        <Button variant="secondary" onClick={closeEdit}>{t('scripted.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.name.trim() || !form.teamId || !form.groupName.trim()}>{t('scripted.save')}</Button>
      </>}>
      {dupSource && <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>}

      {/* Bu monitör için kaydedilmemiş taslak — OTOMATİK uygulanmaz, kullanıcı karar verir. */}
      {pendingDraft && (
        <AlertBanner tone="info" title={t('scripted.draftRestoreTitle')}
          actions={<>
            <Button size="sm" onClick={() => applyDraft(pendingDraft)}>{t('scripted.draftRestore')}</Button>
            <Button variant="secondary" size="sm" onClick={() => discardDraft(pendingDraft.monitor_key)}>{t('scripted.draftDiscard')}</Button>
          </>}>
          {t('scripted.draftRestoreText', formatDateSec(pendingDraft.updated_at))}
        </AlertBanner>
      )}

      <FormGrid>
        <FormField full label={t('scripted.name')} required>
          {({ id }) => (
            <Input id={id} value={form.name} autoFocus={!!dupSource}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          )}
        </FormField>
        <FormField full label={t('scripted.description')}>
          {({ id }) => (
            <Input id={id} value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
          )}
        </FormField>

        <FormField label={t('scripted.team')} required>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))}
                options={[{ value: '', label: t('scripted.selectTeam') }, ...teamSelectOptions]} searchThreshold={2} />
            // Kilitli kutu formun GERÇEK takımını gösterir (oturum teamName'i boşken varsayılan = tek takım).
            : <Input id={id} value={(teamSelectOptions || []).find(o => String(o.value) === String(form.teamId))?.label || teamName || t('scripted.selectTeam')} disabled />}
        </FormField>
        <FormField label={t('scripted.group')} required>
          {({ id }) => (
            <SearchableSelect id={id}
              value={form.groupName}
              onChange={v => setForm(f => ({ ...f, groupName: v }))}
              options={groupSelectOptions}
              creatable
              onCreate={() => {}}
              searchThreshold={2}
              placeholder={t('scripted.groupPick')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="SCRIPTED" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={canOpenSettings}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />

        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
        <FormField label={t('scripted.timeout')} hint={t('scripted.timeoutHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="5" max="180" value={form.timeoutSeconds}
              onChange={e => setForm(f => ({ ...f, timeoutSeconds: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('scripted.confirmAttempts')} hint={t('scripted.confirmHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="0" max="10" value={form.confirmAttempts}
              onChange={e => setForm(f => ({ ...f, confirmAttempts: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('scripted.recoveryChecks')} hint={t('scripted.recoveryHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="1" max="10" value={form.recoveryChecks}
              onChange={e => setForm(f => ({ ...f, recoveryChecks: e.target.value }))} />
          )}
        </FormField>

        {/* Yavaş koşum alarmı (SCRIPTED_SLOW) — opt-in. Senaryo GEÇİYOR ama yavaşlıyorsa
            kesinti alarmı hiç açılmaz; bu eşik o sessiz bozulmayı görünür kılar. Teyit/kurtarma
            sayıları kesinti alarmıyla ORTAKTIR (aynı 3× doğrulama üssel zinciri). */}
        <CheckField full checked={!!form.slowResponseEnabled}
          onCheckedChange={v => setForm(f => ({ ...f, slowResponseEnabled: v }))} label={t('scripted.slowEnabled')} />
        <FormField label={t('scripted.slowThreshold')} hint={t('scripted.slowThresholdHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="500" max="180000" step="500" value={form.slowThresholdMs}
              disabled={!form.slowResponseEnabled}
              onChange={e => setForm(f => ({ ...f, slowThresholdMs: e.target.value }))} />
          )}
        </FormField>

        {/* Kurumsal vekil — k6 alt süreci uzun süre vekil ayarlarını HİÇ almıyordu; vekil zorunlu
            ortamda her koşum sebepsiz "request timeout" ile düşüyordu. */}
        <FormField label={t('scripted.useProxy')} hint={<>{t('scripted.useProxyHint')}{proxyNote}</>}>
          {({ id, describedBy }) => (
            <NativeSelect id={id} aria-describedby={describedBy} value={form.useProxy || 'AUTO'}
              onChange={e => setForm(f => ({ ...f, useProxy: e.target.value }))}>
              <NativeSelectOption value="AUTO">{t('scripted.useProxyAuto')}</NativeSelectOption>
              <NativeSelectOption value="ON">{t('scripted.useProxyOn')}</NativeSelectOption>
              <NativeSelectOption value="OFF">{t('scripted.useProxyOff')}</NativeSelectOption>
            </NativeSelect>
          )}
        </FormField>

        {/* Etiketler — kanonik TagInput (diğer tiplerle parite; payload'daki tags alanını doldurur) */}
        <FormSection title={t('scripted.tagsTitle')} required hint={t('scripted.tagsHint')}>
          <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('scripted.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>

        {/* Script kaynağı — KAYITLI script'ler ve ŞABLONLAR ayrı gruplarda; ikisi karışmasın.
            Kayıtlı script'ler sayfada zaten yüklü listeden gelir (ek API yok) ve liste görme
            yetkisiyle süzülüdür. Boş seçenek YALNIZ yeni monitörde: düzenleme modunda
            monitörün kendi girdisi listede olduğu için "boşalt" yolu veri kaybettiriyordu. */}
        <FormSection title={t('scripted.scriptSource')}>
          {/* Aranabilir: script sayısı arttıkça ada göre süzmek şart. Gruplar (kayıtlı/takım/
              genel + yerleşiklerin 10 kategorisi) SearchableSelect'in `group` alanıyla korunuyor.
              `collapsibleGroups`: yerleşik katalog 100 şablon; düz liste hâlinde hem 100 satır
              uzunluğundaydı hem de bir script'in hangi kategoriden geldiği görünmüyordu. Dallar
              kapalı gelir, tıklanınca altındaki 10 script açılır. `sc-source-select`: test/JS
              kancası (stil taşımaz; cssClasses muafiyeti). */}
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
            <FieldDescription className="text-xs">{t('scripted.srcFromMonitor', selectedSavedName)}</FieldDescription>}
        </FormSection>

        {/* Script editörü — sürüm rozeti başlığın YANINDA: kullanıcı sözdizimini seçerken görsün. */}
        <FormSection title={t('scripted.script')}
          action={<>
            <K6VersionBadge t={t} version={k6Version} withSyntaxNote />
            {/* Script'i panoya al — editörün içinden elle seçmek uzun script'te zahmetli
                (kaydırma + seçimi kaçırma). Boşken düğme HİÇ çizilmez: copyText('') zaten
                false döner ve onay ikonu hiç gelmez — ölü bir düğme bırakmayalım. */}
            {(form.script || '').trim() &&
              <CopyButton value={form.script} variant="secondary"
                label={t('scripted.scriptCopy')} copiedLabel={t('scripted.scriptCopied')} />}
          </>}>
          <CodeEditor value={form.script} onChange={code => setForm(f => ({ ...f, script: code }))}
            placeholder={t('scripted.scriptPlaceholder')} markers={markers} revealMarkers />
          <FieldDescription className="text-xs">{t('scripted.scriptHint')}</FieldDescription>
        </FormSection>

        {/* Koşum sonucu — script bloğunun HEMEN ALTINDA: "üstte seçili script → altında ona ait
            sonuç" bağı görünür olsun. Eskiden formun en altındaydı; kullanıcı script'i
            değiştirdiğinde bayat panel çoğu zaman ekranın dışında kalıyordu.
            Kaynak etiketi ŞART: "az önce test mi koşturdum, kayıtlı son kontrol mü?" ayrımı. */}
        {testResult &&
          <div data-slot="test-run" className="flex min-w-0 flex-col gap-1.5 sm:col-span-2">
            <div className="flex flex-wrap items-center gap-2.5">
              <Badge variant="outline" data-slot="run-source"
                className="bg-muted/60 text-[10.5px] font-bold tracking-[.04em] text-muted-foreground uppercase">
                {testResult._source === 'lastCheck' ? t('scripted.runSourceLast')
                  : testResult._source === 'smoke' ? t('scripted.runSourceSmoke')
                  : t('scripted.runSourceTest')}
              </Badge>
              <span data-slot="run-status" className={cn('font-bold', STATUS_TONE[testResult.status])}>
                {statusLabel(t, testResult.status)}</span>
              {checksSummary(t, testResult) &&
                <span className="text-[.9em] text-muted-foreground"><ChecksSummary t={t} check={testResult} /></span>}
              {testResult.duration_ms != null && <span className="text-[.9em] text-muted-foreground">· {testResult.duration_ms} ms</span>}
              {exitLabel(t, testResult.exit_code) &&
                <span className="text-[.9em] text-muted-foreground">· {exitLabel(t, testResult.exit_code)}</span>}
              {testResult._checkedAt &&
                <span className="text-[.9em] text-muted-foreground">· {formatDateSec(testResult._checkedAt)}</span>}
              {testResult.output_tail &&
                <CopyButton value={testResult.output_tail} label={t('scripted.errCopy')} copiedLabel={t('scripted.errCopied')} />}
            </div>
            {testResult.error &&
              <AlertBanner className="mb-0" tone={isPass(testResult.status) ? 'success' : isWarnLike(testResult.status) ? 'warning' : 'danger'}>
                <ErrorText text={testResult.error} />
                {exitHint(t, testResult.exit_code) && <ErrHint>{exitHint(t, testResult.exit_code)}</ErrHint>}
                {/* Kaydetmeden ÖNCE görülmeli: sözdizimi duvarına çarpan kullanıcı burada anlasın. */}
                {diagnosisHint(t, testResult, k6Version) &&
                  <ErrHint>{diagnosisHint(t, testResult, k6Version)}</ErrHint>}
              </AlertBanner>}
            <RequestPhases t={t} check={testResult} viaProxy={testResult.via_proxy ?? null} />
            {testResult.output_tail && <pre className={cn(OUTPUT_PRE, 'max-h-[260px]')}>{testResult.output_tail}</pre>}
          </div>}

        {/* Env değişkenleri */}
        <FormSection title={t('scripted.env')}>
          {form.env.length > 0 &&
            <div className="flex flex-col gap-2">
              {form.env.map((e, i) => (
                <EnvRow key={i} t={t} row={e} onPatch={patch => setEnvRow(i, patch)} onDelete={() => delEnvRow(i)} />
              ))}
            </div>}
          <Button type="button" variant="secondary" size="sm" className="self-start" onClick={addEnvRow}>
            <Plus size={13} aria-hidden="true" /> {t('scripted.envAdd')}
          </Button>
          <FieldDescription className="text-xs">{t('scripted.envHint')}</FieldDescription>
        </FormSection>

        <CheckField full checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('scripted.active')} />

        {/* Sürüm artışı — YALNIZ mevcut monitörde anlamlı (yeni kayıt daima 1.0.0 ile doğar). */}
        {modal.id && (
          <FormField label={t('scripted.bumpTitle')} hint={t('scripted.bumpHint')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={bumpType} onChange={e => setBumpType?.(e.target.value)}>
                <NativeSelectOption value="patch">{t('scripted.bumpPatch')}</NativeSelectOption>
                <NativeSelectOption value="minor">{t('scripted.bumpMinor')}</NativeSelectOption>
                <NativeSelectOption value="major">{t('scripted.bumpMajor')}</NativeSelectOption>
              </NativeSelect>
            )}
          </FormField>
        )}

        {/* Kaydetme uyarıları — inline ve KALICI (toast değil): kullanıcı düzeltene kadar durmalı. */}
        {/* Kaydetmeyi ENGELLEYEN hata: kod çerçevesi hizasını koruyan çerçeveyle KALICI.
            Eskiden yalnız 5 sn'lik toast'taydı ve `\n`'ler ezildiği için caret hizası kayboluyordu. */}
        {saveError &&
          <AlertBanner className="mb-0 sm:col-span-2" tone="danger" title={t('scripted.saveBlockedTitle')}>
            <ErrorText text={saveError} />
            {errorLines.length > 0 &&
              <ErrHint>{t('scripted.saveBlockedLine', errorLines.join(', '))}</ErrHint>}
          </AlertBanner>}

        {saveWarnings?.length > 0 &&
          <AlertBanner className="mb-0 sm:col-span-2" tone="warning" title={t('scripted.saveWarnTitle')}>
            <ul className="m-0 list-disc pl-4">{saveWarnings.map((w, i) => <li key={i} className="my-0.5">{w}</li>)}</ul>
          </AlertBanner>}

        {/* Kaydetme sonrası doğrulama koşumu. Sürüm ZATEN kaydedildi — metin bunu söylüyor
            ve "Kapat" her an açık: koşum sunucuda sürer, sonucu Kontrol Geçmişi'ne düşer. */}
        {smoke && (
          <AlertBanner className="mb-0 sm:col-span-2" tone={smoke.state === 'skipped' ? 'warning' : 'info'}
            actions={
              <Button type="button" variant="secondary" size="sm" onClick={dismissSmoke}>
                {t('scripted.smokeClose')}
              </Button>
            }>
            <span data-slot="smoke-msg" className="inline-flex flex-auto items-center gap-1.5">
              {smoke.state === 'running' && <Spinner size={14} inline decorative />}
              {smoke.state === 'running'  && t('scripted.smokeRunning')}
              {smoke.state === 'queued'   && t('scripted.smokeQueued')}
              {smoke.state === 'cooldown' && t('scripted.smokeCooldown')}
              {smoke.state === 'skipped'  && t('scripted.smokeSkipped', smoke.reason || '')}
            </span>
          </AlertBanner>
        )}
      </FormGrid>
    </MonitorFormModal>
  )
}

/**
 * Tek env satırı: ad + değer + "gizli" kutusu + sil (şablon editöründeki EnvRow'un DEĞERLİ kardeşi).
 * shadcn Input / Checkbox + Label / ikon Button (ipucu Tooltip). Test kancası: data-slot="env-row".
 */
function EnvRow({ t, row, onPatch, onDelete }) {
  const secretId = useId()
  // Mobil-önce: telefonda ad TEK satırı kaplar, değer + "Gizli" + sil alt satırda; sm ve üstünde tek satır.
  return (
    <div data-slot="env-row" className="flex flex-wrap items-center gap-2 sm:flex-nowrap">
      <Input className="w-full min-w-0 sm:w-auto sm:flex-1" placeholder={t('scripted.envName')} aria-label={t('scripted.envName')} value={row.name}
        onChange={ev => onPatch({ name: ev.target.value })} />
      <Input className="min-w-0 flex-[2]" type={row.secret ? 'password' : 'text'} autoComplete="new-password"
        placeholder={row.secret ? (row.value_set ? t('scripted.envSecretSet') : t('scripted.envSecretEmpty')) : t('scripted.envValue')}
        aria-label={t('scripted.envValue')} value={row.value} onChange={ev => onPatch({ value: ev.target.value })} />
      <div className="flex shrink-0 items-center gap-1.5">
        {/* Değer KORUNUR. Eskiden `value: ''` yazılıyordu: kullanıcı 200 karakterlik bir token
            yapıştırıp "Gizli"yi işaretleyince değer anında siliniyordu ve alan `type=password`
            olduğu için bu görünmüyordu; kayıtta secret satırın boş değeri hiç gönderilmediğinden
            env sunucuda BOŞ kalıyor, script `__ENV.X = undefined` ile 401 alıyordu. Gizlilik zaten
            gösterimde (`type=password`) ve saklamada (şifreli) sağlanıyor. */}
        <Checkbox id={secretId} checked={!!row.secret} onCheckedChange={v => onPatch({ secret: v === true })} />
        <Label htmlFor={secretId} className="gap-1 text-xs font-semibold whitespace-nowrap text-muted-foreground">
          {row.secret ? <EyeOff size={14} aria-hidden="true" /> : <Eye size={14} aria-hidden="true" />}{t('scripted.envSecret')}
        </Label>
      </div>
      <SimpleTooltip content={t('scripted.delete')}>
        {/* Dokunma hedefi telefonda 40 px (RESPONSIVE.md §4), masaüstünde sıkı 32 px. */}
        <Button type="button" variant="outline" size="icon"
          className="size-10 shrink-0 hover:border-destructive hover:bg-destructive/10 hover:text-destructive sm:size-8"
          aria-label={t('a11y.rowAction', row.name || t('scripted.envName'), t('scripted.delete'))}
          onClick={onDelete}><Trash2 size={15} aria-hidden="true" /></Button>
      </SimpleTooltip>
    </div>
  )
}
