import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { sortMonitorsDefault } from '../utils/monitorSort.js'
import { freshestRow, mergeSavedRow, reloadAndSyncDetail, withRowFlags } from '../utils/monitorDetailSync.js'
import { detailCheckKey, resourcesOlderThanRow, settledDetailCheckKey } from '../utils/detailCheckKey.js'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import HintPopover from './ui/HintPopover.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import BaselineControl from './baseline/BaselineControl.jsx'   // dinamik taban çizgisi (2026-11-12)
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
import ScheduleEditor from './dbquery/ScheduleEditor.jsx'
import { durationText as schedDurationText, intervalProblem, windowProblems } from './dbquery/scheduleModel.js'
import { serverFieldErrors } from './dbquery/dbqueryPageModel.js'
import { isScheduled } from './scripted/scriptedCardModel.js'
import {
  DEFAULT_SCRIPTED_SCHEDULE, scheduleBodyKeys, scheduleEditorValue, scheduleErrorsOf, scheduleFromScriptedRow,
  scheduleProblems, SCHEDULE_ERROR_KEYS,
} from './scripted/scriptedFormModel.js'
import SegmentedControl from './ui/SegmentedControl.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import { MonitorPageBody, MonitorViewSwitch, useMonitorAlarmsView } from './monitoring/alarms/MonitorAlarms.jsx'
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
import { matchesTeamAndGroup, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesSearchText, matchesProxy } from '../utils/monitorFilters.js'
import { markMonitorDeleted, monitorKind, useWithoutDeleted } from '../utils/recentlyDeleted.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import PageSpeedMonitorCard from './pagespeed/PageSpeedMonitorCard.jsx'
import { budgetCompliance, budgetFor, hasUndecided, pageSpeedStatusOf } from './pagespeed/pageSpeedCardModel.js'
import { failureKind } from './pagespeed/pageSpeedInsightsModel.js'
import PageSpeedInsights from './pagespeed/PageSpeedInsights.jsx'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { formatRatePercent } from '../i18n/dateLocale.js'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { useNocSaveConfirm } from './noc/forms/useNocSaveConfirm.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { csvRows } from '../utils/csv.js'
import { downloadCsv } from '../utils/csvExport.js'
import { formatBytes } from '../utils/formatBytes.js'
import { KB_FIELDS, kbFieldErrors, suggestThresholds, suggestionIsPartial, GROWTH_FIELDS, growthFieldErrors } from '../utils/pageSpeedThresholds.js'
import { formTeamSelect, routingLockOf, useMonitorTeamPick, withRoutingDefaults } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { Badge } from '@/components/shadcn/badge'
import { Switch } from '@/components/shadcn/switch'
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
import { useCardHandlers } from '../hooks/useCardHandlers.js'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))
// Uçtan uca tanılama (2026-10-05) — tembel: giriş paketi büyümez
const PageSpeedDiagnoseDialog = lazy(() => import('./pagespeed/diagnose/PageSpeedDiagnoseDialog.jsx'))
const PageSpeedDeepTab = lazy(() => import('./pagespeed/deep/PageSpeedDeepTab.jsx'))

/** Kontrol aralığı seçenekleri. TABAN 5 dk: bir ölçüm onlarca istek demek — dakikalık ölçüm hem
 *  tek pod'u hem izlenen sistemi boğar. Sunucu da aynı tabanı uygular (form atlanabilir, uç atlanamaz). */
/** Sayfa Hızı'nın en kısa ölçüm aralığı (sn) — sunucu tabanı ({@code PageSpeedMonitor.MIN_INTERVAL_SECONDS}). */
const PAGESPEED_MIN_INTERVAL = 300

/** Zamanlama sorun kodu → alan altı metni (sentetik izlemeyle aynı kodlar, Sayfa Hızı tabanı). */
function scheduleErrorMap(form, t) {
  const codes = scheduleProblems(form, { intervalProblem, windowProblems, minInterval: PAGESPEED_MIN_INTERVAL })
  // Planı olmayan (düz aralık, süzgeç yok) izlemede sunucu yalnız 5 dk tabanını uygular, üst sınır yok — API ile kurulmuş
  // 7 günden uzun aralık başka bir alanın düzenlenmesini engellemesin (inceleme L2, 2026-11-15)
  if (codes.interval_seconds === 'interval_max' && !isScheduled({ ...(form?.schedule || {}) })) delete codes.interval_seconds
  const out = {}
  for (const [k, code] of Object.entries(codes)) {
    out[k] = code === 'cron_required' ? t('scripted.f.cronRequired') : t(`dbq.sched.v.${code}`, schedDurationText(PAGESPEED_MIN_INTERVAL, t))
  }
  return out
}

const REFRESH_INTERVAL = 60
// Ayrıntı penceresinin sessiz tazelemesi (sn değil ms). Liste bu aralıktan (eksi pay) yeni yüklendiyse pencere
// ucu yeniden çağırmaz — kopyasını liste yoklaması zaten tazeledi (2026-11-06, performans PF2 madde 8).
const DETAIL_REFRESH_MS = 30_000
const LIST_FRESH_SLACK_MS = 1_000
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
  CONFIG_ERROR: 'text-violet-600 dark:text-violet-400', UNDECIDED: 'text-muted-foreground', unknown: 'text-muted-foreground',
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
  schedule: DEFAULT_SCRIPTED_SCHEDULE,   // zamanlama (2. sürüm madde 14): aralık / cron + pencereler + tatil
  maxLoadMs: '', maxTtfbMs: '', maxPageKb: '', maxRequests: '',
  maxJsKb: '', maxImgKb: '', maxFontKb: '', maxThirdPartyKb: '', minPageKb: '',
  // Genişletilmiş ölçüm + büyüme alarmı (2026-11-15, Sayfa Hızı 2c): YENİ izleme genişletilmiş kipte doğar (sunucu da öyle)
  extendedMeasurement: true, growthBytesPct: '', growthRequestsPct: '', growthBytesBand: false,
  userAgent: '', sendDnt: false, useProxy: 'OFF', excludeTrackers: false, trackerPatterns: '',
  basicAuthUser: '', basicAuthPass: '', customHeaders: '', resourceConcurrency: 5,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30,
  active: true,
}

/**
 * Eşik ihlali delili: hangi metrik, eşik neydi, ölçülen neydi.
 *
 * <p>{@code breach_detail} ölçüm ANINDAKİ eşiği taşır ("TTFB:1000>2955"; 2026-11-14'ten beri tür bütçeleri
 * JS / IMG / FONT / THIRD ve asgari boyut MINSIZE da — KB anahtarları KB yazılır, asgari boyutta "eşik → ölçülen"
 * yine aynı sırayla okunur). Eşik sonradan
 * değiştirilirse bugünkü değeri göstermek geçmişi yanlış açıklardı; o yüzden satır kendi
 * delilini taşıyor. Delil yoksa (eski kayıt) yalnız metrik adları yazılır.
 */
/**
 * Delil sayılarının biçimi (2026-11-15): büyüme anahtarları yüzde ("+20 % → +35 %"), öğrenilen bant KB ("≤ 1100 KB → 1400 KB");
 * diğerleri olduğu gibi (eski davranış).
 */
function breachNums(r) {
  const k = String(r.key || '')
  const signed = (v) => (/^-?\d+$/.test(String(v)) ? `${Number(v) >= 0 ? '+' : ''}${v} %` : String(v))
  if (k === 'GROWSIZE' || k === 'GROWREQ') return { a: signed(r.threshold), b: signed(r.measured) }
  if (k === 'SIZEBAND') return { a: `≤ ${r.threshold} KB`, b: `${r.measured} KB` }
  return { a: r.threshold, b: r.measured }
}

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
            <span data-slot="breach-nums" className="font-mono text-[10.5px] font-normal opacity-90">{breachNums(r).a} → <strong>{breachNums(r).b}</strong></span>
          )}
        </Badge>
      ))}
    </span>
  )
}

/**
 * Kontrol geçmişi satırının ek satırı (2026-11-14, madde 4 / 8 / 9): kısmi ölçüm rozeti, HTML süresi, süre kırılımı,
 * alınamayan / atlanan tembel kaynak ve tür kırılımının özeti. Hiçbiri yoksa (eski satır) `null` — ek satır çizilmez.
 * Bileşen DEĞİL düz işlev: CheckHistoryTab hücreleri sayar, `null` dönen bir bileşen yine de boş bir ek satır açardı.
 */
function pageSpeedHistoryExtra(c, t) {
  const parts = []
  if (c.html_ms != null) parts.push(`${t('pspd.ins.htmlMs')} ${c.html_ms} ms`)
  for (const k of ['dns', 'connect', 'tls', 'server']) {
    if (c[`${k}_ms`] != null) parts.push(`${t(`pspd.ins.${k}`)} ${c[`${k}_ms`]} ms`)
  }
  if (c.failed_count != null && c.failed_count > 0) parts.push(`${t('pspd.ins.unloaded')} ${c.failed_count}`)
  if (c.skipped_lazy != null && c.skipped_lazy > 0) parts.push(`${t('pspd.ins.skippedLazy')} ${c.skipped_lazy}`)
  const types = []
  for (const k of ['js', 'img', 'css', 'font']) {
    if (c[`${k}_bytes`] != null && c[`${k}_bytes`] > 0) types.push(`${t(`pspd.type.${k}`)} ${formatBytes(c[`${k}_bytes`], c.time_truncated === true)}`)
  }
  if (c.third_party_bytes != null && c.third_party_bytes > 0) types.push(t('pspd.ins.thirdParty', formatBytes(c.third_party_bytes, c.time_truncated === true)))
  const ext = c.extended === true
  if (!c.time_truncated && !ext && parts.length === 0 && types.length === 0) return null
  return (
    <span data-slot="pspd-hist-extra" className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-muted-foreground">
      {/* Ölçüm kipi (2026-11-15): genişletilmiş ölçümün toplamı klasikle karşılaştırılmaz — nötr işaret */}
      {ext && (
        <Badge variant="outline" data-slot="pspd-hist-extended" title={t('pspd.mode.extendedTip')}
          className="h-auto overflow-visible rounded-md px-1.5 py-px text-[10.5px] whitespace-normal">{t('pspd.mode.extended')}</Badge>
      )}
      {c.time_truncated && (
        <HintPopover content={t('pspd.partialTip')} aria-label={`${t('pspd.partial')}: ${t('pspd.partialTip')}`}
          triggerClassName="relative rounded-md pointer-coarse:min-h-0 pointer-coarse:after:absolute pointer-coarse:after:-inset-2.5">
          <Badge variant="warning" data-slot="pspd-hist-partial"
            className="h-auto overflow-visible rounded-md px-1.5 py-px text-[10.5px] whitespace-normal">{t('pspd.partial')}</Badge>
        </HintPopover>
      )}
      {parts.length > 0 && <span className="[overflow-wrap:anywhere]">{parts.join(' · ')}</span>}
      {types.length > 0 && <span className="[overflow-wrap:anywhere]">{types.join(' · ')}</span>}
    </span>
  )
}

/** Varsayılan takım listesi KARARLI (2026-11-06, PF4 O2): prop verilmezse her çizimde yeni `[]` kart ızgarası belleğini bozardı. */
const NO_TEAMS = Object.freeze([])
export default function PageSpeedMonitorPage({ systemRole, teamId, teamName, myTeams = NO_TEAMS, globalAdmin = false }) {
  const t = useT()
  const alarmsView = useMonitorAlarmsView('pagespeed')   // İzlemeler | Alarmlar görünümü (2026-11-19)
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
  const canManageRow = (m) => (typeof m?.can_edit === 'boolean' ? m.can_edit : (isAdmin || isOwnTeam(m)))   // sunucu bayrağı (2026-10-12, erişim paylaşımı); eski yanıtta rol kuralı
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  // 2026-09-29: + sunucunun satır bayrağı `can_check` (tetik ucunun kapısıyla AYNI kural — kapsamlı yönetici görebildiği
  // ama çalıştıramadığı başka takım satırını "Şimdi Kontrol Et (N)" sayısına katmaz, toplu koşumda 403 yemez).
  const canCheckRow = (m) => (typeof m?.can_check === 'boolean' ? m.can_check : canManageRow(m))
  const canDeleteRow = (m) => (typeof m?.can_delete === 'boolean' ? m.can_delete : (isAdmin || (isTeamAdmin && isOwnTeam(m))))
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
  // Kaynak kırılımı okunamadı mı (2026-10-22, UIM-45): okuma hatası "kaynak yok" GÖSTERİLMEZ
  const [resError, setResError] = useState(null)
  // Sunucu sayfalaması + süzgeç (2026-11-14, madde 6): 50 satır tavanı kalktı. Süzgeç / ölçüm değişince sayfa 1.
  const [resType, setResType] = useState('all')
  const [resParty, setResParty] = useState('all')
  const [resFailed, setResFailed] = useState(false)
  const [resFacets, setResFacets] = useState(null)
  const [resFiltered, setResFiltered] = useState(null)   // süzgece uyan toplam (eski sunucu: null)
  // Sunucu süzgeç + sayfalama uyguluyor mu (yanıtta `filtered_total`) — eski pod'da süzgeç ve sayfa çubuğu GİZLİ (inceleme F5):
  // eski pod parametreleri yok sayar, "yalnız alınamayan" işaretliyken tüm satırları gösterirdi. Bilinmeden önce (ilk okuma)
  // süzgeçler gösterilir (yeni sunucu varsayımı — sayfa ilk açıldığında titremesin).
  const [resServerPaged, setResServerPaged] = useState(true)
  const [resCsvBusy, setResCsvBusy] = useState(false)
  const rsp = useServerPagination({ listKey: 'pagespeed-resources', preset: 'page',
    resetDeps: [selected?.id ?? null, resCheckId, resType, resParty, resFailed] })
  const resQueryRef = useRef(null)
  resQueryRef.current = { page: rsp.apiPage, size: rsp.pageSize, type: resType, party: resParty, failed: resFailed }
  // await SONRASI için güncel değerler (bayat kapanış YOK): checkNow/refreshModal yanıtı gelene kadar pencere
  // kapanmış, başka izlemeye geçilmiş ya da başka bir ölçüm (anlık görüntü) seçilmiş olabilir.
  const selectedIdRef = useRef(null)
  selectedIdRef.current = selected?.id ?? null
  const resCheckIdRef = useRef(resCheckId)
  resCheckIdRef.current = resCheckId
  const selectedRowRef = useRef(null)
  selectedRowRef.current = selected
  // Kırılımın (son ölçüm görünümü) hangi ölçüm için okunduğu — `detailCheckKey`; null = bilinmiyor / son okuma başarısız →
  // sekme görünürken bir sonraki tazelemede yeniden okunur (performans 2026-11-17). Okuma BAŞLARKEN yazılır (uçuştaki
  // okuma ikinci kez istenmesin), başarısız olursa silinir.
  const resKeyRef = useRef(null)
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
  const detailTabRef = useRef(detailTab)
  detailTabRef.current = detailTab
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
  // Etkin bir sayım süzgeci (paylaşılan bağlantı / kayıtlı görünüm / yeniden yükleme) şeritle AÇIK gelir: süzgeç bandı ve
  // "Filtreyi temizle" katlanmış şeridin içinde kalıp liste sessizce süzülmesin (2026-10-22, UIM-20)
  const [statsVisible, setStatsVisible] = useState(() => statFilter != null)
  const [loadNonce, setLoadNonce] = useState(0)   // her başarılı yüklemede artar: başlık çipi geri sayımı kendisi sayar, sayfa saniyede bir çizilmez (2026-10-01)
  // Kart yoğunluğu (2026-09-27): Kompakt / Zengin — sayfa HER AÇILIŞTA Zengin başlar; Kompakt seçimi yalnız sayfada
  // kalındığı sürece geçerli, kalıcı DEĞİL (kullanıcı kararı; bkz. hooks/useCardDensity)
  const [density, setDensity] = useCardDensity('pagespeed')

  // Liste yüklemesi sıra damgalı (2026-10-09): 60 sn yoklaması kaydetmeden ÖNCE başlayıp SONRA dönerse eski liste
  // yeniyi ezmesin — Düzenle açık detayda bile listedeki en yeni satırı kullanır.
  const loadSeqRef = useRef(0)
  const load = useCallback(async () => {
    const my = ++loadSeqRef.current
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
      if (my !== loadSeqRef.current) return undefined   // bayat yanıt — daha yeni bir yükleme yolda
      if (res?.success) { setMonitors(res.data); setLoadError(null); return res.data }
      else setLoadError(res?.error || 'load failed')
    } catch (e) {
      if (my === loadSeqRef.current) setLoadError(e?.message || 'network error')
    } finally {
      if (my === loadSeqRef.current) { setLoading(false); setLoadNonce((n) => n + 1) }
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
  // Liste yoklaması + açık ayrıntının kopyası TEK yanıttan (2026-11-06, performans PF2 madde 8). Eskiden pencerenin 30 sn'lik
  // tazelemesi listeyi AYRICA çekiyordu; sayfanın kendi yoklaması da sürdüğünden pencere açıkken liste ucu dakikada 3 kez
  // (DB sorgu: 4) çağrılıyordu. Yoklama artık pencerenin kopyasını da tazeler; pencere tazelemesi liste son 30 sn içinde
  // böyle yüklendiyse ucu yeniden çağırmaz. Diğer load() çağrıları (kaydet, sürdür, hariç tut …) eskisi gibi kopyaya dokunmaz.
  // Sıra koruması load()'da — bayat yanıt undefined döner, kopya değişmez; A'nın satırı B'nin penceresini değiştirmez.
  const listSyncedAtRef = useRef(0)
  const loadAndSync = useCallback(async () => {
    const rows = await load()
    if (Array.isArray(rows)) {
      listSyncedAtRef.current = Date.now()
      setSelected((prev) => (prev ? (rows.find((x) => x.id === prev.id) ?? prev) : prev))
    }
    return rows
  }, [load])
  useVisibleInterval(loadAndSync, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)

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

  async function loadResources(id, checkId = null, silent = false, forRow = null) {
    const seq = ++resSeq.current
    // Son ölçüm görünümü → okunan ölçümün anahtarı; anlık görüntü (checkId) sabittir, tazelenmez
    const keyRow = forRow ?? selectedRowRef.current
    const startedAt = Date.now()
    resKeyRef.current = checkId == null ? detailCheckKey(keyRow, id) : null
    if (!silent) setResLoading(true)
    // try/finally YOKTU: istek reject olursa setResLoading(false) hic calismiyor ve kaynak
    // tablosunun spinner'i modal kapatilip yeniden acilana kadar donuyordu. Bayrak YALNIZ bu
    // istek hala guncelse indirilir (bastirilan eski yanit yenisinin spinner'ini SONDURMESIN).
    try {
      const q = resQueryRef.current || {}
      const res = await api.monitoring.getPageSpeedResources(id, { checkId: checkId ?? undefined,
        page: q.page, size: q.size, type: q.type, party: q.party, failed: q.failed })
      if (seq !== resSeq.current) return        // daha yeni bir istek var → bu yaniti AT
      if (!res?.success) {
        // Okunamadı: sessiz yenilemede son iyi kırılım KALIR (hata şeridiyle), ilk okumada boş + hata bloğu
        resKeyRef.current = null                  // sekme görünürken sonraki tazelemede yeniden denenir
        setResError(res?.error || t('pspd.resLoadFailed'))
        if (!silent) { setResources([]); setResTotal(0); setBreaches([]); rsp.setTotal(null) }   // F6: önceki toplam kalmasın
        return
      }
      const rows = res.data?.resources ?? []
      setResources(rows)
      setResTotal(res.data?.total ?? 0)
      setBreaches(res.data?.breaches ?? [])
      setResFacets(res.data?.facets ?? null)
      // Eski sunucu `filtered_total` göndermez (tek sayfa, sayfalama yok) → çubuk görünen satır sayısına göre
      const filtered = res.data?.filtered_total
      setResFiltered(filtered ?? null)
      // Eski sunucu süzgeç / sayfa parametrelerini YOK SAYAR (inceleme F5): süzgeçler ve sayfa çubuğu gizlenir, toplam null
      setResServerPaged(filtered != null)
      rsp.setTotal(filtered != null ? filtered : null)
      setResError(null)
      // inceleme 2026-11-17: yeni ölçümün kaynak satırları ölçüm satırından SONRA ayrı işlemde yazılır — okuma eski ölçümün
      // satırlarını getirdiyse ya da ölçüm çok yeniyse anahtar saklanmaz, sekme görünürken sonraki tazelemede yeniden okunur
      if (checkId == null && resKeyRef.current != null) {
        resKeyRef.current = resourcesOlderThanRow(rows, keyRow) ? null : settledDetailCheckKey(keyRow, id, startedAt)
      }
    } catch (e) {
      if (seq === resSeq.current) {
        resKeyRef.current = null
        setResError(e?.message || t('pspd.resLoadFailed'))
        if (!silent) { setResources([]); setResTotal(0); setBreaches([]); rsp.setTotal(null) }
      }
    } finally {
      if (seq === resSeq.current) setResLoading(false)
    }
  }

  function openDetail(m) {
    resSeq.current++            // onceki izlemenin ucusan yaniti bu modali DOLDURMASIN
    setSelected(m); setResources([]); setResTotal(0); setBreaches([]); setResCheckId(null); setResError(null)
    rsp.setTotal(null)   // inceleme F6: önceki izlemenin "1–50 / 312" çubuğu yeni izleme okunurken görünmesin
    setResType('all'); setResParty('all'); setResFailed(false); setResFacets(null); setResFiltered(null)
    setDetailTab(deepLinkTab()); setMetric('load')
    setSpeedDx((cur) => (cur && cur.monitorId === m?.id ? cur : null))   // derin bağlantının kayıtlı çalıştırması yalnız KENDİ izlemesinde
    loadResources(m.id, null, false, m)
  }
  function closeDetail() {
    resSeq.current++
    setSelected(null); setResources([]); setResTotal(0); setBreaches([]); setSpeedDx(null); setResError(null)
  }
  // Sayfa / boyut / süzgeç değişince kırılım yeniden okunur (ilk açılışın okuması openDetail'de). Ölçüm (resCheckId) değişimi
  // kendi çağrısını yapar; burada yalnız sorgu anahtarı izlenir — aynı anahtar ikinci istek üretmez.
  const resQueryKey = `${rsp.apiPage}|${rsp.pageSize}|${resType}|${resParty}|${resFailed}`
  const lastResQuery = useRef(resQueryKey)
  useEffect(() => {
    if (lastResQuery.current === resQueryKey) return
    lastResQuery.current = resQueryKey
    if (selectedIdRef.current != null) loadResources(selectedIdRef.current, resCheckIdRef.current)
  }, [resQueryKey]) // eslint-disable-line react-hooks/exhaustive-deps

  /** Tanılama penceresini aç — başlangıç ekranıyla (koşu kullanıcı "Tanılamayı başlat"a basınca). */
  function openDiagnose(m) { if (m) setSpeedDx({ monitorId: m.id, runId: null, initialRunId: null }) }

  /**
   * Kırılımı yalnız GEREKİRSE tazele (performans 2026-11-17): "Kaynaklar" sekmesi görünür, son ölçüm görünümünde ve izlemenin
   * son ölçümü kırılımın okunduğu ölçümden farklı (ya da son okuma başarısız). Eskiden 30 sn'de bir sekme kapalıyken bile
   * okunuyordu. Anlık görüntü (ihlal anı) sabittir — hiç tazelenmez.
   */
  function refreshResourcesIfStale(row) {
    if (!row || detailTabRef.current !== 'resources' || resCheckIdRef.current != null) return
    if (resKeyRef.current != null && resKeyRef.current === detailCheckKey(row)) return
    loadResources(row.id, null, true, row)
  }

  async function refreshModal() {
    if (!selected) return
    const id = selected.id
    let row = selectedRowRef.current
    // Liste son 30 sn içinde yoklamayla yüklendiyse yeniden çekilmez — kopya o yanıttan tazelendi. Aksi hâlde SIRA
    // KORUMALI load()'dan geçer (2026-10-22, UIM-19) ve kopya aynı yanıttan tazelenir (loadAndSync).
    if (Date.now() - listSyncedAtRef.current >= DETAIL_REFRESH_MS - LIST_FRESH_SLACK_MS) {
      const rows = await loadAndSync()
      if (Array.isArray(rows)) row = rows.find((x) => x.id === id) ?? row
    }
    // Pencere o arada kapandı / başka izlemeye geçildi → A'nın kaynakları B'nin penceresine yüklenmesin
    // (loadResources'ın resSeq'i EN SON isteği kazandırır — bayat çağrı en son olmamalı).
    if (selectedIdRef.current !== id) return
    refreshResourcesIfStale(row)
  }
  // Sekmeye dönüldüğünde (yeni ölçüm geldiyse) hemen tazelenir — 30 sn beklenmez
  useEffect(() => { refreshResourcesIfStale(selectedRowRef.current) }, [detailTab]) // eslint-disable-line react-hooks/exhaustive-deps
  useVisibleInterval(() => { if (selected) refreshModal() }, selected ? DETAIL_REFRESH_MS : 0, false)

  /** Uçuşan form testini geçersiz kılar (form açılışı / kapanışı): geç yanıt yeni forma düşmez, düğme kilitli kalmaz. */
  function cancelTest() { testSeq.current++; setTesting(false) }

  // Bildirim yönlendirmesi kilidi (inceleme 5): paylaşımla yazılan takımın kaydı ya da paylaşan takıma YENİ kayıt —
  // kanallar / seviye / grup / 7/24 kilitli; yeni kayıtta varsayılana çekilir (sunucu farklı değeri 403 ile reddeder).
  const routingLock = routingLockOf(modal, form.teamId, pickTeams)
  const nocConfirm = useNocSaveConfirm()   // kayıtta 7/24 onayı (2026-11-15)
  // Zamanlama düzenleyicisinin değeri (aralık formun intervalSeconds'ından) + değişiklik (ilgili hatalar kalkar)
  const scheduleValue = useMemo(() => scheduleEditorValue(form), [form.schedule, form.intervalSeconds])   // eslint-disable-line react-hooks/exhaustive-deps
  const onScheduleChange = (patch) => {
    setForm(f => {
      const { interval_seconds: iv, ...rest } = patch || {}
      const next = { ...f }
      if ('interval_seconds' in (patch || {})) next.intervalSeconds = iv
      if (Object.keys(rest).length) next.schedule = { ...(f.schedule || DEFAULT_SCRIPTED_SCHEDULE), ...rest }
      return next
    })
    for (const k of SCHEDULE_ERROR_KEYS) fe.clear(k)
  }
  useEffect(() => { if (modal === 'new' && routingLock) setForm(f => withRoutingDefaults(f)) }, [modal, routingLock])

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
      schedule: scheduleFromScriptedRow(m),
      maxLoadMs: m.max_load_ms ?? '', maxTtfbMs: m.max_ttfb_ms ?? '',
      maxPageKb: m.max_page_kb ?? '', maxRequests: m.max_requests ?? '',
      maxJsKb: m.max_js_kb ?? '', maxImgKb: m.max_img_kb ?? '', maxFontKb: m.max_font_kb ?? '',
      maxThirdPartyKb: m.max_third_party_kb ?? '', minPageKb: m.min_page_kb ?? '',
      // Eski sunucu alanı göndermiyorsa klasik kip (null = kapalı) — mevcut izleme kendi kipini korur
      extendedMeasurement: m.extended_measurement === true,
      growthBytesPct: m.growth_bytes_pct ?? '', growthRequestsPct: m.growth_requests_pct ?? '', growthBytesBand: m.growth_bytes_band === true,
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
      // Tür bütçeleri + asgari boyut (2026-11-14, madde 11 / 12) — eşik kuralı aynı: boş = kaldır
      maxJsKb: thresholdValue(form.maxJsKb), maxImgKb: thresholdValue(form.maxImgKb), maxFontKb: thresholdValue(form.maxFontKb),
      maxThirdPartyKb: thresholdValue(form.maxThirdPartyKb), minPageKb: thresholdValue(form.minPageKb),
      // Genişletilmiş ölçüm + büyüme alarmı (2026-11-15): boş yüzde = kapalı
      extendedMeasurement: form.extendedMeasurement === true,
      growthBytesPct: thresholdValue(form.growthBytesPct), growthRequestsPct: thresholdValue(form.growthRequestsPct),
      growthBytesBand: form.growthBytesBand === true,
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
    if (globalAdmin && form.customHeaders !== '') p.customHeaders = form.customHeaders.trim() || null   // sunucu: isGlobalAdmin (UIM-15)
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
      ...kbFieldErrors(form, t, modal && modal !== 'new' ? formFrom(modal) : null),
      ...growthFieldErrors(form, t, modal && modal !== 'new' ? formFrom(modal) : null),   // büyüme yüzdesi 1–1000 (2026-11-15)
      ...scheduleErrorMap(form, t),   // zamanlama: cron boş / aralık tabanı / eksik pencere (sunucu asıl doğrulayıcı)
    })) return
    // Kayıtta 7/24 onayı (2026-11-15): 7/24 bildirimi kapalıysa sorulur; Vazgeç → form açık kalır.
    const noc = await nocConfirm.ask({ type: 'PAGESPEED', monitorId: modal === 'new' ? null : modal?.id,
      nocNotify: form.nocNotify, locked: routingLock, groupIds: form.nocGroupIds })
    if (!noc) return
    if (noc.asked && noc.nocNotify) setForm((prev) => ({ ...prev, nocNotify: true }))
    setSaving(true)
    try {
      const payload = payloadFromForm()
      payload.nocNotify = noc.nocNotify   // 7/24 onayı: Evet → açık
      // Zamanlama anahtarları (madde 14): yeni kayıtta / kopyada, satır alanları taşıyorsa ya da kullanıcı değiştirdiyse —
      // eski sürüm podun satırında alan yoksa dokunulmamış form kayıtlı cron / pencereleri SİLMEZ (sentetik kuralı)
      Object.assign(payload, scheduleBodyKeys({ row: modal, schedule: form.schedule }))
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createPageSpeedMonitor(payload)
        : await api.monitoring.updatePageSpeedMonitor(modal.id, payload)
      await load(); setSaving(false)
      // Sunucu alan hatası (tür bütçesi / asgari boyut, 2026-11-14): alanın altında, tost değil
      if (!res?.success && KB_FIELDS.includes(res?.field)) { fe.check({ [res.field]: res.error || t('pspd.err.kbRange') }); return }
      // Büyüme alarmı alan hatası (2026-11-15): alanın altında
      if (!res?.success && GROWTH_FIELDS.includes(res?.field)) { fe.check({ [res.field]: res.error || t('pspd.err.growthRange') }); return }
      // Zamanlama alan hatası (madde 14): düzenleyicinin ilgili alanının altında
      if (!res?.success) {
        const sched = {}
        for (const [k, v] of Object.entries(serverFieldErrors(res).fields || {})) {
          if (SCHEDULE_ERROR_KEYS.includes(k)) sched[k] = v
          else if (k === 'intervalSeconds') sched.interval_seconds = v
        }
        if (Object.keys(sched).length) { fe.check(sched); return }
      }
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      noc.done(res.data?.id)
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
        setSelected(prev => (prev?.id === m.id ? withRowFlags(prev, res.data) : prev))
        if (selectedIdRef.current === m.id) { setResCheckId(null); loadResources(m.id, null, false, res.data) }
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      }
      // Toplu koşumda toast SUSAR: 40 monitörlük bir koşumda 40 hata bildirimi ekranı
      // gömerdi; mesaj zaten koşum tablosunun satırında duruyor.
      if (!silent) toast.error(res?.error || 'Error')
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  /**
   * Kaynak CSV'si (2026-11-14, madde 6): seçili ölçümün süzgece uyan TÜM satırları sunucudan akar. Eski sunucu (404 / 405)
   * → görünen satırların istemci CSV'si (eski davranış).
   */
  async function exportResourcesAllCsv() {
    if (!selected || resCsvBusy) return
    setResCsvBusy(true)
    try {
      const res = await api.monitoring.downloadPageSpeedResourcesCsv(selected.id, {
        checkId: resCheckId ?? undefined, type: resType, party: resParty, failed: resFailed })
      if (res?.success) return
      // Eski pod (uç yok) → görünen satırların istemci CSV'si, bunu SÖYLEYEREK (inceleme F5); yeni sunucunun kodsuz 404'ü
      // izlemenin silindiğidir — bayat satırlar indirilmez (inceleme F12).
      const kind = failureKind(res)
      if (kind === 'unsupported') { exportResourcesCsv(); toast.info(t('pspd.res.csvShownOnly')); return }
      if (kind === 'gone') { toast.error(t('pspd.monitorGone')); return }
      toast.error(res?.error || t('pspd.res.csvFailed'))
    } catch {
      toast.error(t('pspd.res.csvFailed'))
    } finally {
      setResCsvBusy(false)
    }
  }

  function exportResourcesCsv() {
    if (!resources.length) return
    const head = ['url', 'type', 'bytes', 'duration_ms', 'http_status', 'third_party', 'checked_at']
    // BOM + CRLF; gövde csvRows, indirme ortak downloadCsv (öneri 29 — dosya baytları ve adı aynı).
    if (downloadCsv(`pagespeed-resources-${selected?.id ?? 'x'}.csv`, '﻿' + csvRows([head, ...resources.map(r => head.map(k => r[k]))])) === false) toast.error(t('inv.downloadFailed'))
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
    return matchesSearchText(search, [m.url, m.name])
  }), [monitors, teamFilter, groupFilter, tagFilter, proxyFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, ok: 0, slow: 0, down: 0, alarm: 0, unacked: 0 }
    for (const m of scoped) {
      // Kararsız ölçüm (inceleme 2026-11-15) "Normal" sayılmaz — yalnız toplamda
      if (pageSpeedStatusOf(m) === 'OK') c.ok++
      else if (m.status === 'SLOW') c.slow++
      else if (m.status === 'DOWN') c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      ok:      m => pageSpeedStatusOf(m) === 'OK',
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
  // Kanıtlı aşım yok ama tanımlı bir eşik karara bağlanamadı (UNDECIDED, inceleme 2026-11-15) → nötr, ASLA yeşil değil.
  const statusKey = (m) => {
    const s = pageSpeedStatusOf(m)
    return s === 'OK' ? 'up' : s === 'SLOW' ? 'warn' : s === 'DOWN' ? 'down' : 'unknown'
  }
  const statusLabel = (s) => s === 'OK' ? t('pspd.statusOk') : s === 'SLOW' ? t('pspd.statusSlow')
    : s === 'DOWN' ? t('pspd.statusDown') : s === 'CONFIG_ERROR' ? t('pspd.statusConfigError')
    : s === 'UNDECIDED' ? t('pspd.statusUndecided')
    : t('pspd.statusUnknown')
  function statusBadge(m) {
    // CONFIG_ERROR paylaşılan sözlükte yok (unknown şeridi) ama rozet kendi mor tonunu korur.
    return (
      <MonitorStatusBadge status={statusKey(m)} className={m?.status === 'CONFIG_ERROR' ? STATUS_TEXT.CONFIG_ERROR : undefined}>
        {statusLabel(pageSpeedStatusOf(m))}
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

  // Kart ızgarası (2026-11-06, performans PF4 O2): yalnız KENDİ verisi değişince çizilir. Form state'i bu
  // sayfada; eskiden formdaki her tuş 25–100 kartın hepsini yeniden çiziyordu. Çizim girdileri bağımlılık
  // listesinde; olay işleyicileri olay anında en güncel kopyadan okunur (useCardHandlers — bayat kapanış yok).
  const cardEv = useCardHandlers({ openDetail, toggleBulk, resume, checkNow, openEdit, openDuplicate, deleteMonitor })
  const cardGrid = useMemo(() => (
    <div className="upt-grid" data-density={density}>
      {pager.pageItems.map(m => (
        /* Kart: pagespeed/PageSpeedMonitorCard (shadcn Card + "stretched button" + bütçe ölçerleri). Yetki
           kapıları ve olay işleyicileri SAYFADA kalır: seçim kutusu, meta ve eylemler kart yuvalarına geçer. */
        <PageSpeedMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} status={statusKey(m)} badge={statusBadge(m)} density={density} running={isRunning(m.id)}
          onOpen={() => cardEv.current.openDetail(m)}
          spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
          week7={week7.data[String(m.id)]} week14={week14.data[String(m.id)]}
          selection={canManageRow(m) && (
            <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => cardEv.current.toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.url)} />
          )}
          meta={<MonitorCardMeta monitor={m} />}
          actions={(canManageRow(m) || canCheckRow(m) || canDeleteRow(m)) && (   // yalnız çalıştırma / silme yetkisi de (paylaşım, 2026-10-12)
            <MonitorCardActions onResume={canManageRow(m) ? () => cardEv.current.resume(m) : undefined} resuming={isResuming(m.id)} rowLabel={m.url}
              running={isRunning(m.id)}
              onCheck={canCheckRow(m) ? () => cardEv.current.checkNow(m) : undefined} onEdit={canManageRow(m) ? () => cardEv.current.openEdit(m) : undefined} onDuplicate={canManageRow(m) ? () => cardEv.current.openDuplicate(m) : undefined}
              checkTitle={t('pspd.check')} editTitle={t('pspd.edit')}
              onDelete={canDeleteRow(m) ? () => cardEv.current.deleteMonitor(m) : undefined}
              deleting={deleting === m.id} deleteTitle={t('pspd.delete')} />
          )} />
      ))}
    </div>
    // eslint-disable-next-line react-hooks/exhaustive-deps -- çizim girdileri tam; işleyiciler cardEv'den okunur
  ), [pager.pageItems, density, isRunning, isResuming, deleting, bulkSel, sparks, sla, t, isAdmin, isTeamAdmin, isOwnTeam, week7, week14])

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
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => { setForm(f => ({ ...f, teamId: v })); fe.clear('teamId') }} {...formTeamSelect(modal, teamSelectOptions, pickTeams)} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('pspd.noTeam')} disabled />}
        </FormField>
        <FormField full label={t('pspd.group')} required {...fe.fieldProps('groupName')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('groupName') }}
              options={[{ value: '', label: t('pspd.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('pspd.noGroup')} />
          )}
        </FormField>
        <NotifyChannels locked={routingLock}
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField disabled={routingLock} type="PAGESPEED" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        {/* Zamanlama (2. sürüm madde 14): aralık ya da cron + izinli / yasak pencereler + tatil + atlanan günler + canlı
            önizleme — sentetik izleme / veritabanı sorgusuyla AYNI ortak düzenleyici; en kısa aralık 5 dk */}
        <FormSection title={t('pspd.scheduleTitle')} hint={t('pspd.scheduleHint')}>
          <div data-slot="pspd-schedule" className="min-w-0">
            <ScheduleEditor value={scheduleValue} onChange={onScheduleChange} fieldErrors={scheduleErrorsOf(fe.errors)}
              minIntervalSeconds={PAGESPEED_MIN_INTERVAL} canEditHolidays={globalAdmin}
              previewApi={api.monitoring.pageSpeedSchedulePreview} />
          </div>
        </FormSection>

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

        {/* ── Tür bütçeleri + asgari boyut (2026-11-14, madde 11 / 12): hepsi opsiyonel, KB ── */}
        <FormSection title={t('pspd.budgetsTitle')} hint={t('pspd.budgetsHint')}>
          <FormGrid>
            {[['maxJsKb', 'pspd.maxJsKb'], ['maxImgKb', 'pspd.maxImgKb'], ['maxFontKb', 'pspd.maxFontKb'],
              ['maxThirdPartyKb', 'pspd.maxThirdPartyKb']].map(([key, label]) => (
              <FormField key={key} label={t(label)} {...fe.fieldProps(key)}>
                {({ id, describedBy }) => unitInput(id, 'KB', { min: '0', step: '50', value: form[key], placeholder: t('pspd.noThreshold'),
                  'aria-describedby': describedBy,
                  onChange: e => { const v = e.target.value; setForm(f => ({ ...f, [key]: v })); fe.clear(key) } })}
              </FormField>
            ))}
            <FormField label={t('pspd.minPageKb')} hint={t('pspd.minPageKbHint')} {...fe.fieldProps('minPageKb')}>
              {({ id, describedBy }) => unitInput(id, 'KB', { min: '0', step: '10', value: form.minPageKb, placeholder: t('pspd.noThreshold'),
                'aria-describedby': describedBy,
                onChange: e => { const v = e.target.value; setForm(f => ({ ...f, minPageKb: v })); fe.clear('minPageKb') } })}
            </FormField>
          </FormGrid>
        </FormSection>

        {/* ── Ölçüm kapsamı (2026-11-15, Sayfa Hızı 2c madde 10): genişletilmiş ölçüm ── */}
        <FormSection title={t('pspd.extendedTitle')} hint={t('pspd.extendedHint')}>
          <label data-slot="pspd-extended" className="flex min-h-10 min-w-0 cursor-pointer items-start justify-between gap-3 rounded-md border px-3 py-2">
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="text-sm font-medium [overflow-wrap:anywhere]">{t('pspd.extended')}</span>
              <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('pspd.extendedSub')}</span>
            </span>
            <Switch checked={form.extendedMeasurement === true} aria-label={t('pspd.extended')} data-slot="pspd-extended-switch"
              onCheckedChange={(v) => setForm(f => ({ ...f, extendedMeasurement: v === true }))} />
          </label>
          {/* Mevcut izlemede kip değişince toplamlar değişir — eşikler ve büyüme referansı yeni kipte yeniden kurulur */}
          {modal && modal !== 'new' && (modal.extended_measurement === true) !== (form.extendedMeasurement === true) && (
            <div data-slot="pspd-extended-warn" className="mt-2">
              <AlertBanner tone="warning" icon={AlertTriangle}>
                {t(form.extendedMeasurement ? 'pspd.extendedOnWarn' : 'pspd.extendedOffWarn')}
              </AlertBanner>
            </div>
          )}
        </FormSection>

        {/* ── Büyüme alarmı (2026-11-15, Sayfa Hızı 2c madde 13): önceki haftanın medyanına göre + öğrenilen bant ── */}
        <FormSection title={t('pspd.growthTitle')} hint={t('pspd.growthHint')}>
          <div data-slot="pspd-growth" className="min-w-0">
            <FormGrid>
              {[['growthBytesPct', 'pspd.growthBytesPct'], ['growthRequestsPct', 'pspd.growthRequestsPct']].map(([key, label]) => (
                <FormField key={key} label={t(label)} hint={t('pspd.growthPctHint')} {...fe.fieldProps(key)}>
                  {({ id, describedBy }) => unitInput(id, '%', { min: '1', max: '1000', step: '5', value: form[key], placeholder: t('pspd.noThreshold'),
                    'aria-describedby': describedBy,
                    onChange: e => { const v = e.target.value; setForm(f => ({ ...f, [key]: v })); fe.clear(key) } })}
                </FormField>
              ))}
            </FormGrid>
            <label data-slot="pspd-growth-band" className="mt-3 flex min-h-10 min-w-0 cursor-pointer items-start justify-between gap-3 rounded-md border px-3 py-2">
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="text-sm font-medium [overflow-wrap:anywhere]">{t('pspd.growthBandLabel')}</span>
                <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('pspd.growthBandHint')}</span>
              </span>
              <Switch checked={form.growthBytesBand === true} aria-label={t('pspd.growthBandLabel')} data-slot="pspd-growth-band-switch"
                onCheckedChange={(v) => setForm(f => ({ ...f, growthBytesBand: v === true }))} />
            </label>
          </div>
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
              {globalAdmin && (
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
                {/* Süre tavanına takılan deneme (inceleme F4): yükleme süresi ve boyut ALT SINIR ("≥"); TTFB ölçülemediyse "—" */}
                {testResult.time_truncated ? '≥ ' : ''}{testResult.response_ms} ms · TTFB {testResult.ttfb_ms != null ? `${testResult.ttfb_ms} ms` : '—'}
                {' · '}{formatBytes(testResult.total_bytes, testResult.bytes_truncated || testResult.time_truncated === true)}
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
      {nocConfirm.dialog}
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader showActions={!alarmsView.active} type="pagespeed" title={t('pspd.title')} subtitle={t('pspd.subtitle')}
        count={loading ? null : monitors.length} down={counts.down}
        refreshEvery={REFRESH_INTERVAL} refreshResetKey={loadNonce} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('pspd.addMonitor')}>
        <MonitorViewSwitch view={alarmsView} />
      </MonitorPageHeader>
      <MonitorPageBody view={alarmsView}>

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
          onClear={() => setBulkSel(new Set())} onPrune={setBulkSel} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22; vekil süzgeciyle görünür oldu) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        {cardGrid}
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
              <BaselineControl type="pagespeed" monitor={selected} name={selected.name || selected.url} canManage={canManageRow(selected)} />
              <CopyLinkButton iconOnly variant="outline" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-0" />
          <DetailSummary items={[
            { key: 'status', value: statusLabel(selected.status), label: t('pspd.lastStatus'), valueClassName: statusText(selected.status) },
            // Süre tavanı (2026-11-14, madde 4): yükleme süresi ve boyut ALT SINIR — "≥" + açıklama
            { key: 'load', value: selected.response_ms != null ? `${selected.time_truncated ? '≥ ' : ''}${selected.response_ms} ms` : '—', label: t('pspd.mLoad'),
              hint: selected.time_truncated ? t('pspd.partialTip') : undefined },
            { key: 'ttfb', value: selected.ttfb_ms != null ? `${selected.ttfb_ms} ms` : '—', label: t('pspd.mTtfb') },
            { key: 'size', value: formatBytes(selected.total_bytes, selected.bytes_truncated || selected.time_truncated), label: t('pspd.mSize'),
              hint: selected.bytes_truncated ? t('pspd.truncatedHint') : selected.time_truncated ? t('pspd.partialTip') : undefined },
            { key: 'req', value: selected.request_count ?? '—', label: t('pspd.mRequests') },
            // Saklanan ama eskiden gösterilmeyen ölçüler (madde 8): HTML süresi, alınamayan, atlanan tembel kaynak
            selected.html_ms != null && { key: 'html', value: `${selected.html_ms} ms`, label: t('pspd.ins.htmlMs') },
            selected.failed_count != null && { key: 'failed', value: selected.failed_count, label: t('pspd.ins.unloaded'),
              valueClassName: selected.failed_count > 0 ? 'text-destructive' : undefined },
            selected.skipped_lazy != null && { key: 'lazy', value: selected.skipped_lazy, label: t('pspd.ins.skippedLazy'),
              hint: t('pspd.ins.skippedLazyHint') },
            // Bütçe uyumu (madde 5) — erişilebilirlikten AYRI; eski sunucu alanı göndermiyorsa satır yok
            (() => {
              // Bütçe tanımsızsa "%100" yeşil değil "Bütçe tanımlı değil" (inceleme F7)
              const c = budgetCompliance(sla.data[String(selected.id)], sla.days || 30, selected.budget_defined)
              return c && { key: 'budget', value: c.noBudget ? t('pspd.card.budgetUndefined') : c.pct == null ? '—' : formatRatePercent(c.pct),
                label: t('pspd.budgetCompliance'), hint: c.noBudget ? t('pspd.card.noBudgetTip') : t('pspd.budgetComplianceTip', sla.days || 30) }
            })(),
            selected.last_check && { key: 'last', value: formatDateSec(selected.last_check), label: t('pspd.lastCheck'), time: true },
          ]} />
          {selected.error && (
            <AlertBanner tone="danger" role="alert" icon={AlertTriangle} className="mt-3.5">{selected.error}</AlertBanner>
          )}
          {/* Son ölçümün ihlal delili (inceleme F2): tür bütçeleri ve asgari boyut dört özet değerinde görünmez — hangi eşiğin
              aşıldığı (ölçüm anındaki eşik → ölçülen) burada adıyla. Geçmiş satırlarıyla aynı bileşen. */}
          {Array.isArray(selected.breached_metrics) && selected.breached_metrics.length > 0 && (
            <div data-slot="pspd-detail-breaches" className="mt-3.5 flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-1">
              <span className={SECTION_LABEL}>{t('pspd.detailBreaches')}</span>
              <BreachEvidence metrics={selected.breached_metrics.join(',')} detail={selected.breach_detail} t={t} />
            </div>
          )}
          {/* Ölçüm kipi (2026-11-15): son ölçüm genişletilmiş kipte mi — nötr; izlemenin ayarı farklıysa sonraki ölçümden itibaren */}
          {(selected.extended === true || selected.extended_measurement === true) && (
            <div data-slot="pspd-detail-mode" className="mt-3.5 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <span className={SECTION_LABEL}>{t('pspd.mode.title')}</span>
              <Badge variant="outline" className="h-auto overflow-visible rounded-md whitespace-normal">
                {t(selected.extended === true ? 'pspd.mode.extended' : 'pspd.mode.classic')}
              </Badge>
              {selected.last_check && (selected.extended === true) !== (selected.extended_measurement === true) && (
                <span data-slot="pspd-detail-mode-next" className="[overflow-wrap:anywhere]">{t('pspd.mode.nextCheck')}</span>
              )}
            </div>
          )}
          {selected.capped && (
            <AlertBanner tone="warning" className="mt-3.5">{t('pspd.cappedWarn')}</AlertBanner>
          )}
          {selected.bytes_truncated && (
            <AlertBanner tone="warning" className="mt-3.5">{t('pspd.truncatedWarn')}</AlertBanner>
          )}
          {selected.time_truncated && (
            <div data-slot="pspd-time-truncated"><AlertBanner tone="warning" className="mt-3.5">{t('pspd.timeTruncatedWarn')}</AlertBanner></div>
          )}
          <DetailDivider />
          <DetailTabs value={detailTab} onValueChange={setDetailTab} className="mt-0"
            countsFor={{ kind: 'pagespeed', monitorId: selected.id, notesType: 'PAGESPEED', notesTarget: selected.url, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['resources', t('pspd.tabResources')], ['breakdown', t('pspd.tabBreakdown')], ['deep', t('pspd.tabDeep')], ['chart', t('pspd.tabChart')], ['control', t('hist.tab')],
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
                <Button type="button" variant="secondary" size="sm" className="ml-auto pointer-coarse:h-10" data-slot="pspd-res-csv"
                  title={t('pspd.res.csvTip')} disabled={!resources.length || resCsvBusy} onClick={exportResourcesAllCsv}>
                  <Download size={12} />{t('pspd.res.csvAll')}</Button>
              </div>
              {/* Süzgeçler (2026-11-14, madde 6): tür / taraf / yalnız alınamayan — sayılar seçili ölçümün tamamından */}
              {resServerPaged && <div data-slot="pspd-res-filters" className="mb-2 flex min-w-0 flex-wrap items-center gap-2">
                <SearchableSelect value={resType} onChange={setResType} ariaLabel={t('pspd.res.typeLabel')} searchThreshold={99}
                  options={[{ value: 'all', label: t('pspd.res.typeAll') },
                    ...['js', 'css', 'img', 'font', 'other'].map((k) => ({ value: k,
                      label: resFacets?.type?.[k] != null ? `${t(`pspd.type.${k}`)} (${resFacets.type[k]})` : t(`pspd.type.${k}`) }))]} />
                <SearchableSelect value={resParty} onChange={setResParty} ariaLabel={t('pspd.res.partyLabel')} searchThreshold={99}
                  options={[{ value: 'all', label: t('pspd.res.partyAll') },
                    ...['first', 'third'].map((k) => ({ value: k,
                      label: resFacets?.party?.[k] != null ? `${t(`pspd.res.party.${k}`)} (${resFacets.party[k]})` : t(`pspd.res.party.${k}`) }))]} />
                <label className="inline-flex min-h-9 cursor-pointer items-center gap-2 text-xs pointer-coarse:min-h-10">
                  <Checkbox checked={resFailed} onCheckedChange={(v) => setResFailed(v === true)} data-slot="pspd-res-failed" />
                  <span>{t('pspd.res.unloadedOnly')}{resFacets?.failed != null ? ` (${resFacets.failed})` : ''}</span>
                </label>
                {(resType !== 'all' || resParty !== 'all' || resFailed) && (
                  <Button type="button" variant="ghost" size="sm" className="pointer-coarse:h-10" data-slot="pspd-res-clear"
                    onClick={() => { setResType('all'); setResParty('all'); setResFailed(false) }}>{t('pspd.res.clearFilters')}</Button>
                )}
              </div>}
              {/* Sunucu en agir N kaynagi dondurur. Kirpildiysa bunu SOYLEMEK zorunlu: yoksa
                  kullanici 50 satiri sayfanin tamami sanip agirligin nereden geldigini yanlis okur. */}
              <p className="mb-2 text-xs text-muted-foreground">
                {t('pspd.resHint')}
                {/* Toplam (sayfalı sunucu) ya da eski sunucunun kırpma notu — kırpma GÖRÜNÜR olmalı */}
                {resFiltered != null
                  ? (resTotal > 0 && ` ${resFiltered !== resTotal ? t('pspd.res.filtered', resFiltered, resTotal) : (resTotal === 1 ? t('pspd.res.total.one') : t('pspd.res.total', resTotal))}`)
                  : (resTotal > resources.length && resources.length > 0 && ` ${t('pspd.resTruncated', resources.length, resTotal)}`)}
                {/* Kirilimin KENDI zamani. Sayfa alinamadiginda son iyi kirilim KORUNUYOR (silmek,
                    kullanici tam da "bozulmadan once neye benziyordu" diye baktigi anda tabloyu
                    bosaltiyordu) — o yuzden "Son olcum" etiketi tek basina yaniltabilir. */}
                {resCheckId == null && resources[0]?.checked_at
                  && ` ${t('pspd.resMeasuredAt', formatDateSec(resources[0].checked_at))}`}
              </p>
              {resError && resources.length > 0 && !resLoading && (
                <div data-slot="pspd-res-stale"><AlertBanner tone="warning" className="mb-2"
                  actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => loadResources(selected.id, resCheckId)}>{t('hist.retry')}</Button>}>
                  {t('pspd.resStale')}
                </AlertBanner></div>
              )}
              {resLoading ? <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
                : resError && resources.length === 0 ? (
                  <div data-slot="pspd-res-error"><StatusBlock tone="danger" icon={Inbox} title={t('pspd.resLoadFailed')} description={String(resError)} className="py-6"
                    actions={<Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={() => loadResources(selected.id, resCheckId)}>{t('hist.retry')}</Button>} /></div>
                )
                : resources.length === 0 ? (
                  (resType !== 'all' || resParty !== 'all' || resFailed) && resTotal > 0
                    ? <StatusBlock tone="neutral" icon={Inbox} title={t('pspd.res.noMatch')} className="py-6"
                        actions={<Button type="button" variant="outline" className="pointer-coarse:h-10"
                          onClick={() => { setResType('all'); setResParty('all'); setResFailed(false) }}>{t('pspd.res.clearFilters')}</Button>} />
                    : <StatusBlock tone="neutral" icon={Inbox} title={t('pspd.noResources')} className="py-6" />)
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
                            <TableCell className="w-full max-w-0">
                              <span className="block truncate" title={r.url}>{r.url}</span>
                              {/* Genişletilmiş ölçüm (2026-11-15): kaynağı başvuran stil dosyası — tam adres, sarar */}
                              {r.found_in && (
                                <span data-slot="pspd-res-foundin" className="mt-0.5 block whitespace-normal text-[11px] text-muted-foreground [overflow-wrap:anywhere]">
                                  {t('pspd.res.foundIn')} {r.found_in}
                                </span>
                              )}
                            </TableCell>
                            <TableCell className="text-right tabular-nums" title={r.truncated ? t('pspd.truncatedHint') : undefined}>
                              {formatBytes(r.bytes, r.truncated)}</TableCell>
                            <TableCell className="hidden text-right tabular-nums sm:table-cell">{r.duration_ms != null ? `${r.duration_ms} ms` : '—'}</TableCell>
                            <TableCell className={cn('hidden text-right tabular-nums sm:table-cell', r.http_status != null && r.http_status >= 400 && 'text-destructive')}>
                              {r.http_status ?? (r.failed ? <span className="text-destructive">{t('pspd.res.unloadedBadge')}</span> : '—')}</TableCell>
                            <TableCell className="hidden md:table-cell">{r.third_party ? t('pspd.thirdParty') : t('pspd.firstParty')}</TableCell>
                          </TableRow>
                        )
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
              {rsp.total != null && rsp.total > rsp.pageSize && <div className="mt-2"><PaginationBar {...rsp.bar} /></div>}
            </TabsContent>

            {/* Kırılım (2026-11-14, madde 8 / 9 / 15): süre kırılımı + tür kırılımı + tür kırılımı zaman içinde */}
            <TabsContent value="breakdown">
              <PageSpeedInsights monitor={selected} />
            </TabsContent>

            {/* Derin analiz (2026-11-15, Sayfa Hızı 2. sürüm madde 16–19, 22): şelale, hız notu, iki ölçümü karşılaştır, rapor,
                görsel aracı. Tembel parça — sekme açılınca yüklenir. */}
            <TabsContent value="deep">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                {detailTab === 'deep' && <PageSpeedDeepTab key={selected.id} monitor={selected} />}
              </Suspense>
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
                  const st = failed ? (c.failure_reason === 'CONFIG_ERROR' ? 'CONFIG_ERROR' : 'DOWN') : breached ? 'SLOW'
                    : hasUndecided(c.undecided_metrics) ? 'UNDECIDED' : 'OK'   // kararsız ölçüm yeşil değil (inceleme 2026-11-15)
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
                    <span className="upt-rt-ms">{c.response_ms != null ? `${c.time_truncated ? '≥ ' : ''}${c.response_ms} ms` : '—'}</span>
                    <span className="upt-rt-ms">{c.ttfb_ms != null ? `${c.ttfb_ms} ms` : '—'}</span>
                    <span className="upt-rt-ms">{formatBytes(c.total_bytes, c.bytes_truncated || c.time_truncated)}</span>
                    <span className="upt-rt-ms">{c.request_count ?? '—'}</span>
                    {/* Saklanan ama eskiden gösterilmeyen ölçüler (madde 8 / 9) + kısmi ölçüm rozeti (madde 4) — tam genişlik ek satır */}
                    {!failed && pageSpeedHistoryExtra(c, t)}
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
                <MonitorNotes type="PAGESPEED" target={selected.url}
                  // Yalnız paylaşımla görülen izlemede not / rehber yazmak DÜZENLEME ister (2026-10-12); diğerinde varsayılan
                  canManage={canManageRow(selected)} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                {/* Prop adlari ChangeHistoryTab imzasiyla BIREBIR: t / kind / monitorId /
                    teamNames / canManage. Yanlis adla gecmek sessiz degil — t cagrilinca
                    "t is not a function" ile sekme comple cokuyor. */}
                <ChangeHistoryTab t={t} kind="pagespeed" monitorId={selected.id}
                  teamNames={teamNameById} canManage={canDeleteRow(selected)}
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
      </MonitorPageBody>
    </div>
  )
}
