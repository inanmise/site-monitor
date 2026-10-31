import { useState, useEffect, useCallback, useMemo, useRef, lazy, Suspense } from 'react'
import { sortMonitorsDefault } from '../utils/monitorSort.js'
import { freshestRow, mergeSavedRow, reloadAndSyncDetail, withRowFlags } from '../utils/monitorDetailSync.js'
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
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'
import { useToast } from './ui/Toast.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import TagInput from './ui/TagInput.jsx'
import {
  Trash2, DatabaseZap, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, Inbox, Copy, Cable,
  LibraryBig, ChevronDown, Hand, Info, BellRing, Repeat,
} from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, matchesTag, tagNamesOf, matchesGroupOrTagText, matchesSearchText } from '../utils/monitorFilters.js'
import { markMonitorDeleted, monitorKind, useWithoutDeleted } from '../utils/recentlyDeleted.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import DbQueryMonitorCard from './dbquery/DbQueryMonitorCard.jsx'
import DbQueryDetailSummary from './dbquery/DbQueryDetailSummary.jsx'
import {
  DbqSection, EvaluationSection, FormSectionNav, LimitsSection, MailFields, QuerySection, ScheduleSection,
} from './dbquery/DbQueryMonitorForm.jsx'
import {
  ConnectionPicker, ConnectionsManager, RuleResultsList, SaveTemplateDialog, TemplatesManager,
} from './dbquery/buildingBlocks.jsx'
import {
  DB_TYPES, DEFAULT_SCHEDULE, breachLevelKey, changedFieldGroups, checkStatusKey, checkStatusLabelKey, columnsFrom, dbTypeLabel,
  dbqueryStatusKey, dbqueryStatusLabelKey, durationText, metricValueText, planCheckAvailable, rateLimitText, retryAfterOf, rowCountText,
  scheduleFromRow, sectionErrorCounts, serverFieldErrors, splitFieldErrors, warningsOf,
} from './dbquery/dbqueryPageModel.js'
import { WARNING_CODES, countTrapIndexes, defaultNewRule, primaryValueColumn, ruleFromColumn } from './dbquery/ruleSuggest.js'
import { MAX_RULES } from './dbquery/ruleModel.js'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { formTeamSelect, isGrantTeam, isRoutingLockedRow, routingLockOf, useMonitorTeamPick, withRoutingDefaults } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { TabsContent } from '@/components/shadcn/tabs'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import { MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint } from './monitoring/MonitorForm.jsx'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const REFRESH_INTERVAL = 30
const PURPOSE_MIN = 5
const PURPOSE_MAX = 2000

/** Grafik metrikleri — sunucu `?metric=` ile TEK seriyi projekte eder (Sayfa Hızı emsali; varsayılan süre). */
const METRICS = [
  { key: 'duration', labelKey: 'dbq.detail.metricDuration', unit: 'ms' },
  { key: 'value', labelKey: 'dbq.detail.metricValue', unit: '' },
  { key: 'rows', labelKey: 'dbq.detail.metricRows', unit: '' },
]

/** Durum metin tonu (özet değeri / geçmiş satırı) — kartın durum sözlüğüyle aynı dört ton. */
const STATUS_TEXT = {
  up: 'text-success', warn: 'text-amber-600 dark:text-amber-400', down: 'text-destructive', unknown: 'text-muted-foreground',
}

const emptyForm = {
  name: '', groupName: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '', tags: '',
  notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  purpose: '', profileId: null, dbType: '', sqlText: '',
  rules: [], ruleLogic: 'ANY', slowQueryMs: '',
  schedule: { ...DEFAULT_SCHEDULE },
  queryTimeoutSeconds: 30, maxRows: 1000,
  // Yeni izlemede mailde YALNIZ seçilen kolonlar (2.B — varsayılan kişisel veri koruması); kayıtlı izleme alanıyla açılır
  mailIncludeRows: true, mailColumnsMode: 'SELECTED', mailColumns: [], maskedColumns: [], mailSubjectTemplate: '',
  confirmAttempts: 1, confirmIntervalSeconds: 30, recoveryChecks: 1, recoveryIntervalSeconds: 60,
  active: true,
}

/** Boş metin → null, sayı → sayı (sunucu boşu "yok" sayar: yavaş sorgu eşiği kapalı, …). */
const numOrNull = (v) => (v === '' || v == null ? null : Number(v))

/** Satır (snake_case) → form. Düzenle ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. Parola / satır verisi yok. */
function formFrom(m) {
  return {
    name: m.name || '', groupName: m.group_name || '',
    notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
    teamId: m.team_id != null ? String(m.team_id) : '', tags: m.tags || '',
    notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false,
    purpose: m.purpose || '', profileId: m.profile_id ?? null, dbType: m.profile?.db_type || '', sqlText: m.sql_text || '',
    rules: Array.isArray(m.rules) ? m.rules : [], ruleLogic: m.rule_logic === 'ALL' ? 'ALL' : 'ANY',
    slowQueryMs: m.slow_query_ms ?? '',
    schedule: scheduleFromRow(m),
    queryTimeoutSeconds: m.query_timeout_seconds ?? 30, maxRows: m.max_rows ?? 1000,
    mailIncludeRows: m.mail_include_rows !== false,
    // Alan yoksa (sürümden önceki kayıt / eski sunucu) bugünkü davranış: ilk N kolon
    mailColumnsMode: m.mail_columns_mode === 'SELECTED' ? 'SELECTED' : 'FIRST_N',
    mailColumns: Array.isArray(m.mail_columns) ? m.mail_columns : [],
    maskedColumns: Array.isArray(m.masked_columns) ? m.masked_columns : [],
    mailSubjectTemplate: m.mail_subject_template || '',
    confirmAttempts: m.confirm_attempts ?? 1, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
    recoveryChecks: m.recovery_checks ?? 1, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 60,
    active: m.active !== false,
  }
}

/**
 * Form → istek gövdesi. Standart alanlar (ad, takım, grup, etiket, bildirim, 7/24, teyit / kurtarma) dokuz türün ortak
 * sözleşmesiyle camelCase (paylaşılan arka uç yardımcıları — resolveWriteTeam, requireGroupAndTags, applyNotificationGroup,
 * NocMonitorService.applyFromBody, guardSharedNotificationFields — ve BulkActionBar bu adları okur; kapı nocMonitorForms);
 * türe özgü alanlar api §1 adlarıyla (snake_case).
 */
function payloadFromForm(form) {
  const std = {
    name: form.name.trim(), active: form.active,
    teamId: form.teamId === '' || form.teamId == null ? null : Number(form.teamId),
    groupName: form.groupName?.trim() || null, tags: form.tags?.trim() || null,
    // Boş = takım varsayılanı -> takım adresi (zincirin kalanı).
    notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null ? null : Number(form.notificationGroupId),
    nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi
    alertLevel: form.alertLevel || 'WARNING', notifyEmail: form.notifyEmail, notifyWebhook: form.notifyWebhook,
    confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
    recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
  }
  return {
    ...std,
    profile_id: form.profileId ?? null,
    purpose: form.purpose.trim(),
    sql_text: form.sqlText,
    rules: Array.isArray(form.rules) ? form.rules : [],
    rule_logic: form.ruleLogic === 'ALL' ? 'ALL' : 'ANY',
    slow_query_ms: numOrNull(form.slowQueryMs),
    ...form.schedule,
    query_timeout_seconds: numOrNull(form.queryTimeoutSeconds),
    max_rows: numOrNull(form.maxRows),
    mail_include_rows: !!form.mailIncludeRows,
    mail_columns_mode: form.mailColumnsMode === 'SELECTED' ? 'SELECTED' : 'FIRST_N',
    mail_columns: form.mailColumns, masked_columns: form.maskedColumns,
    mail_subject_template: form.mailSubjectTemplate?.trim() || null,
  }
}

const SCHEDULE_ERROR_KEYS = ['schedule', 'schedule_mode', 'interval_seconds', 'cron_expression', 'windows', 'blackouts', 'skip_dates']

export default function DbQueryMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
  const canManageRow = (m) => (typeof m?.can_edit === 'boolean' ? m.can_edit : (isAdmin || isOwnTeam(m)))
  // Toplu kontrolün adayı = kartın ▶ düğmesiyle AYNI yüzey (sunucunun `can_check` bayrağı; eski yanıtta düzenleme kuralı).
  const canCheckRow = (m) => (typeof m?.can_check === 'boolean' ? m.can_check : canManageRow(m))
  const canDeleteRow = (m) => (typeof m?.can_delete === 'boolean' ? m.can_delete : (isAdmin || (isTeamAdmin && isOwnTeam(m))))
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('dbquery')
  const sla = useSla('dbquery')
  const [rawMonitors, setMonitors] = useState([])
  const monitors = useWithoutDeleted(monitorKind('dbquery'), rawMonitors)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  const selectedIdRef = useRef(null)
  selectedIdRef.current = selected?.id ?? null
  const [modal, setModal] = useState(null)
  const fe = useFormErrors(modal)
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const initialBodyRef = useRef(null)   // açılıştaki gövde — "kaydedilmemiş değişiklik" onayı
  const [teamGroups, setTeamGroups] = useState([])
  const [teamTags, setTeamTags] = useState([])
  const [defaults, setDefaults] = useState(null)
  const [saving, setSaving] = useState(false)
  const { isRunning, track } = useRunningChecks()
  const [deleting, setDeleting] = useState(null)
  // Deneme ve maliyet AYRI sıralar (O3): biri ötekinin yanıtını geçersiz kılmaz; form açılış / kapanışı, takım ya da bağlantı
  // değişimi ikisini birden arttırır → eski formun / eski bağlantının geç yanıtı yeni forma düşmez.
  const testSeq = useRef(0)
  const planSeq = useRef(0)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [planChecking, setPlanChecking] = useState(false)
  const [planResult, setPlanResult] = useState(null)
  const [columns, setColumns] = useState([])   // son denemenin kolon ADLARI (satır verisi değil)
  const [sqlViolations, setSqlViolations] = useState(null)
  const [saveTplOpen, setSaveTplOpen] = useState(false)
  const [connOpen, setConnOpen] = useState(false)
  const [tplOpen, setTplOpen] = useState(false)
  const [advOpen, setAdvOpen] = useState(false)
  const [detailTab, setDetailTab] = useState('summary')
  const deepLinkTab = useDeepLinkTab('summary')
  const [histReload, setHistReload] = useState(0)
  const [problemOnly, setProblemOnly] = useState(false)
  const [histOpen, setHistOpen] = useState(() => new Set())
  const [metric, setMetric] = useState('duration')
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))
  const [dbFilter, setDbFilter] = useState(() => readUrlParam('db', 'all'))   // veritabanı türü süzgeci
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  // Etkin sayım süzgeci (paylaşılan bağlantı / kayıtlı görünüm) şeritle AÇIK gelir — süzgeç bandı gizli kalmasın (UIM-20)
  const [statsVisible, setStatsVisible] = useState(() => statFilter != null)
  const [loadNonce, setLoadNonce] = useState(0)   // başarılı yüklemede artar: başlık çipi geri sayımı kendisi sayar
  const [density, setDensity] = useCardDensity('dbquery')

  // Liste yüklemesi sıra damgalı: yoklama kaydetmeden önce başlayıp sonra dönerse eski liste yeniyi ezmesin.
  const loadSeqRef = useRef(0)
  const load = useCallback(async () => {
    const my = ++loadSeqRef.current
    try {
      const res = await api.monitoring.getDbQueryMonitors()
      if (my !== loadSeqRef.current) return undefined
      if (res?.success && Array.isArray(res.data)) { setMonitors(res.data); setLoadError(null); return res.data }
      setLoadError(res?.error || t('dbq.page.loadFailed'))
    } catch (e) {
      if (my === loadSeqRef.current) setLoadError(e?.message || t('dbq.page.loadFailed'))
    } finally {
      if (my === loadSeqRef.current) { setLoading(false); setLoadNonce((n) => n + 1) }
    }
    return undefined
  }, [t])
  const { resume, isResuming } = useMonitorResume(api.monitoring.updateDbQueryMonitor, (r) => {
    load(); setMonitors((prev) => prev.map((x) => (x.id === r.id ? { ...x, active: true } : x)))
    setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

  const checkable = monitors.filter(canCheckRow)
  const checkRun = useCheckRun({
    items: checkable,
    runOne: (m) => checkNow(m, { silent: true }),
    concurrency: CHECK_CONCURRENCY_BY_TYPE.dbquery,
  })
  // Koşum sırasında tazeleme DURUR (ortada gelen load() satırları değiştirip kartları zıplatırdı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)

  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'dbquery').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.dbquery || null) })
  }, [])

  useMonitorDeepLink(monitors, openDetail, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'DBQUERY',
  })

  function openDetail(m) {
    setSelected(m); setDetailTab(deepLinkTab()); setMetric('duration'); setHistOpen(new Set()); setProblemOnly(false)
  }
  function closeDetail() { setSelected(null) }

  async function refreshModal() {
    if (!selected) return
    const id = selected.id
    // Liste SIRA KORUMALI load()'dan geçer: yavaş yenileme kaydedilmiş satırı eski değerlerle ezmesin.
    const rows = await load()
    if (Array.isArray(rows)) {
      const fresh = rows.find(x => x.id === id)
      if (fresh) setSelected(prev => (prev?.id === id ? fresh : prev))
    }
  }
  useVisibleInterval(() => { if (selected) refreshModal() }, selected ? 30000 : 0, false)

  /** Uçuşan deneme VE maliyet isteğini geçersiz kılar (form açılışı / kapanışı, takım / bağlantı değişimi). */
  function cancelTest() { testSeq.current++; planSeq.current++; setTesting(false); setPlanChecking(false) }
  function resetFormSide() {
    cancelTest(); setTestResult(null); setPlanResult(null); setColumns([]); setSqlViolations(null)
    setSaveTplOpen(false); setAdvOpen(false)
  }

  // Bildirim yönlendirmesi kilidi (paylaşım): paylaşımla yazılan takımın kaydı ya da paylaşan takıma YENİ kayıt.
  const routingLock = routingLockOf(modal, form.teamId, pickTeams)
  useEffect(() => { if (modal === 'new' && routingLock) setForm(f => withRoutingDefaults(f)) }, [modal, routingLock])
  // Paylaşımla düzenlenen kayıtta BAĞLANTI da değişmez: bağlantılar takım belgesidir, paylaşımla görünmez (api §2).
  // K2: aynı kilit sorgu, kurallar, kural birleşimi, maske / mail kolonları, satırları maile ekle, konu şablonu, yavaş
  // sorgu eşiği, zaman aşımı ve en çok satırı da kapsar (satırın `routing_locked` / `shared_access` bayrağı — yeni bayrak
  // yok); deneme / maliyet / şablon düğmeleri çizilmez (sunucu onları yalnız takımın kendi kapsamına açar).
  const connectionLocked = modal !== 'new' && !!modal && isRoutingLockedRow(modal)
  const grantLocked = connectionLocked

  function openWith(nextForm, nextModal, dup = null) {
    resetFormSide()
    setDupSource(dup); setChangeNote('')
    setForm(nextForm); setModal(nextModal)
    initialBodyRef.current = JSON.stringify(payloadFromForm(nextForm))
  }
  // Yeni izleme / kopya YALNIZ kendi takımlarına (K2): paylaşımla erişilen takımda bağlantı kullanılamaz, sunucu 403
  // DBQUERY_GRANT_TEAM verir. Varsayılan takım bir paylaşım takımıysa (yalnız paylaşımla yazılan tek takım) boş başlar.
  const ownDefaultTeam = defaultTeamId != null && !isGrantTeam(defaultTeamId, pickTeams) ? String(defaultTeamId) : ''
  /** Yeni izleme formu — `seed` (şablondan oluşturma, 2.C) sorgu / amaç / kurallar / takımı önceden doldurur. */
  function openNew(seed = null) {
    openWith({
      ...emptyForm, teamId: isAdmin ? '' : ownDefaultTeam,
      schedule: { ...DEFAULT_SCHEDULE, interval_seconds: defaults?.interval_seconds ?? DEFAULT_SCHEDULE.interval_seconds },
      queryTimeoutSeconds: defaults?.query_timeout_seconds ?? emptyForm.queryTimeoutSeconds,
      maxRows: defaults?.max_rows ?? emptyForm.maxRows,
      confirmAttempts: defaults?.confirm_attempts ?? emptyForm.confirmAttempts,
      confirmIntervalSeconds: defaults?.confirm_interval_seconds ?? emptyForm.confirmIntervalSeconds,
      recoveryChecks: defaults?.recovery_checks ?? emptyForm.recoveryChecks,
      recoveryIntervalSeconds: defaults?.recovery_interval_seconds ?? emptyForm.recoveryIntervalSeconds,
      ...(seed || {}),
    }, 'new')
  }
  /**
   * "Bu şablonla izleme oluştur" (2.C): şablon yöneticisi kapanır, yeni izleme formu şablonun sorgu / amaç / kurallarıyla
   * açılır. Takım şablonunun takımı (yalnız kendi takımıysa — paylaşım takımında izleme oluşturulamaz, K2) seçili gelir;
   * yerleşik şablon takım taşımaz. Bağlantı her zaman kullanıcıya bırakılır.
   */
  function openFromTemplate(tpl) {
    if (!tpl) return
    setTplOpen(false)
    const tplTeam = tpl.team_id != null && !isGrantTeam(tpl.team_id, pickTeams)
      && (isAdmin || pickTeams.some((tm) => String(tm.id) === String(tpl.team_id))) ? String(tpl.team_id) : null
    openNew({
      sqlText: tpl.sql_text || '', purpose: tpl.purpose || '',
      rules: Array.isArray(tpl.rules) ? tpl.rules : [], ruleLogic: tpl.rule_logic === 'ALL' ? 'ALL' : 'ANY',
      ...(tplTeam ? { teamId: tplTeam } : {}),
    })
  }
  function openEdit(row) {
    // Güncel satır: detay kopyası bayat olabilir — form ondan kurulursa kayıt eski değerleri sessizce geri yazar.
    const m = freshestRow(row, monitors)
    openWith(formFrom(m), m)
  }
  function openDuplicate(m) {
    const f = { ...formFrom(m), name: duplicateName(m.name) }
    // Paylaşımla erişilen kaydın kopyası paylaşan takıma gidemez (K2): takım varsayılana, bağlantı boşa döner — bağlantı
    // takımın belgesidir, kopya kendi takımının bağlantısını seçer.
    const grantSource = isRoutingLockedRow(m) || isGrantTeam(f.teamId, pickTeams)
    openWith(grantSource ? { ...f, teamId: isAdmin ? '' : ownDefaultTeam, profileId: null, dbType: '', groupName: '' } : f, 'new', m)
  }
  function closeEditNow() { cancelTest(); setModal(null); setDupSource(null); setChangeNote(''); setSaveTplOpen(false) }
  async function closeEdit() {
    const dirty = initialBodyRef.current != null && JSON.stringify(payloadFromForm(form)) !== initialBodyRef.current
    if (dirty && !saving) {
      const ok = await showConfirm({
        title: t('dbq.form.discardTitle'), message: t('dbq.form.discardMsg'),
        confirmText: t('dbq.form.discard'), cancelText: t('dbq.form.keepEditing'), variant: 'danger',
      })
      if (!ok) return
    }
    closeEditNow()
  }

  /** Alan anahtarlarının hatası temizlenir (yapı taşları alt anahtar taşır: `rules[2].warning`). */
  const clearKeys = useCallback((pred) => {
    for (const k of Object.keys(fe.errors)) if (fe.errors[k] && pred(k)) fe.clear(k)
  }, [fe])
  const clear = useCallback((key) => fe.clear(key), [fe])

  function onTeamChange(v) {
    // Bağlantılar takım belgesi: takım değişince seçili bağlantı ve onun deneme sonucu geçersiz.
    setForm(f => (String(f.teamId) === String(v) ? f : { ...f, teamId: v, profileId: null, dbType: '' }))
    // Uçuşan deneme / maliyet eski takımın bağlantısında koşuyor — yanıtı yeni forma düşmesin (DÜŞÜK 2)
    cancelTest(); setTestResult(null); setPlanResult(null)
    fe.clear('team_id'); fe.clear('profile_id')
  }
  function onConnectionChange(profileId, profile) {
    setForm(f => ({ ...f, profileId: profileId ?? null, dbType: profile?.db_type || (profileId == null ? '' : f.dbType) }))
    cancelTest(); setTestResult(null); setPlanResult(null)
    fe.clear('profile_id')
  }
  function onSqlChange(sql) {
    setForm(f => ({ ...f, sqlText: sql }))
    setSqlViolations(null); fe.clear('sql_text')
  }
  function onRulesChange(rules) {
    // Yalnız DEĞİŞEN kuralın sunucu hatası silinir (DÜŞÜK 13): 2. kuralı düzelten kullanıcı 3. kuralın hatasını kaybetmez.
    // Ekle / sil / taşı sırayı kaydırır — o zaman tüm kural hataları silinir (sunucunun `rules[N]` sırası artık geçersiz).
    const prev = Array.isArray(form.rules) ? form.rules : []
    const next = Array.isArray(rules) ? rules : []
    const shifted = prev.length !== next.length || next.some((r, i) => r?.id !== prev[i]?.id)
    const changed = new Set(next.map((r, i) => (JSON.stringify(r) !== JSON.stringify(prev[i]) ? i : -1)).filter((i) => i >= 0))
    setForm(f => ({ ...f, rules }))
    clearKeys((k) => {
      if (k === 'rules') return true
      const m = /^rules\[(\d+)\]/.exec(k)
      return !!m && (shifted || changed.has(Number(m[1])))
    })
  }
  function onScheduleChange(patch) {
    setForm(f => ({ ...f, schedule: { ...f.schedule, ...patch } }))
    clearKeys((k) => SCHEDULE_ERROR_KEYS.includes(k))
  }

  async function onPickTemplate(tpl) {
    if (!tpl) return
    // Sorgu dolu ve farklıysa YA DA kurulmuş kurallar şablonunkilerle değişecekse onay (DÜŞÜK 14 — kurallar sessizce gitmesin)
    const rulesReplaced = Array.isArray(tpl.rules) && Array.isArray(form.rules) && form.rules.length > 0
      && JSON.stringify(form.rules) !== JSON.stringify(tpl.rules)
    if ((form.sqlText.trim() && form.sqlText !== tpl.sql_text) || rulesReplaced) {
      const ok = await showConfirm({ title: t('dbq.form.tplApplyTitle'), message: t('dbq.form.tplApplyMsg', tpl.name || ''),
        confirmText: t('dbq.form.tplApply'), cancelText: t('dbq.form.keepEditing') })
      if (!ok) return
    }
    setForm(f => ({
      ...f, sqlText: tpl.sql_text || '',
      purpose: f.purpose.trim() ? f.purpose : (tpl.purpose || ''),
      rules: Array.isArray(tpl.rules) ? tpl.rules : f.rules,
      ruleLogic: tpl.rule_logic === 'ALL' ? 'ALL' : (tpl.rule_logic === 'ANY' ? 'ANY' : f.ruleLogic),
    }))
    setSqlViolations(null); setTestResult(null); setPlanResult(null)
    fe.clear('sql_text'); fe.clear('purpose')
    clearKeys((k) => k === 'rules' || k.startsWith('rules['))
  }

  const canRunQuery = form.profileId != null && !!form.sqlText.trim()
  const runHint = form.profileId == null ? t('dbq.form.runNeedsConnection') : !form.sqlText.trim() ? t('dbq.form.runNeedsSql') : null

  // ── Akıllı kural kurulumu (2.C) — denemenin KOLON ADLARI / TÜRLERİ; satır verisi değil ──────────────────────────
  const trapIndexes = useMemo(() => countTrapIndexes(form.rules, { warnings: testResult?.warnings, testResult, sqlText: form.sqlText }),
    [form.rules, testResult, form.sqlText])
  const trapColumn = useMemo(() => primaryValueColumn(columns), [columns])
  const newRuleFactory = useCallback(() => defaultNewRule(testResult, columns), [testResult, columns])
  /** Değerlendirme bölümüne kaydır (kural eklendi — kullanıcı nereye gittiğini görsün). */
  const revealEvaluation = () => {
    const el = typeof document !== 'undefined' ? document.querySelector('[data-dbq-section="evaluation"]') : null
    try { el?.scrollIntoView?.({ block: 'start', behavior: 'smooth' }) } catch { /* jsdom */ }
  }
  /** Deneme panelindeki kolon menüsü: `rule:<TÜR>` → kural ekle; `mask` → mailde maskele. */
  function onColumnAction(action, column) {
    if (!column?.name) return
    if (action === 'mask') {
      setForm((f) => ({ ...f, maskedColumns: [...new Set([...(f.maskedColumns || []), column.name])] }))
      fe.clear('masked_columns')
      toast.success(t('dbq.form.maskAdded', column.name))
      return
    }
    if (!String(action).startsWith('rule:')) return
    const current = Array.isArray(form.rules) ? form.rules : []
    if (current.length >= MAX_RULES) { toast.info(t('dbq.rule.maxReached', MAX_RULES)); return }
    onRulesChange([...current, ruleFromColumn(column, String(action).slice(5))])
    toast.success(t('dbq.form.ruleAdded', column.name))
    revealEvaluation()
  }

  /** Sunucu hatasını alanlara dağıtır; hiçbir alana düşmezse tost. */
  function applyServerErrors(res, fallbackKey) {
    const { fields, violations, plan } = serverFieldErrors(res)
    if (violations) setSqlViolations(violations)
    if (plan) setPlanResult(plan)
    if (fields) { fe.check(fields); return true }
    toast.error(res?.error || t(fallbackKey))
    return false
  }

  async function runTest() {
    if (!canRunQuery) return
    const my = ++testSeq.current
    setTesting(true); setTestResult(null)
    try {
      const body = payloadFromForm(form)
      if (modal && modal !== 'new') body.monitor_id = modal.id
      const res = await api.monitoring.testDbQuery(body)
      if (my !== testSeq.current) return
      if (res?.success) {
        setTestResult(res.data || {})
        setColumns(columnsFrom(res.data))
        if (Array.isArray(res.data?.guard?.violations) && res.data.guard.violations.length) setSqlViolations(res.data.guard.violations)
      } else {
        if (res?.code === 'DBQUERY_SQL_REJECTED' || res?.code === 'VALIDATION_FAILED') {
          const { fields, violations } = serverFieldErrors(res)
          if (violations) setSqlViolations(violations)
          if (fields) fe.check(fields)
        }
        // Bekleme (429 RATE_LIMITED): sunucu iletisi olduğu gibi — ek "N sn sonra" cümlesi yok (K3, DÜŞÜK 5)
        const limited = res?.code === 'RATE_LIMITED'
        setTestResult({ error: limited ? rateLimitText(res, t, 'dbq.test.failedGeneric') : (res?.error || t('dbq.test.failedGeneric')),
          code: res?.code || null, retryAfter: limited ? null : retryAfterOf(res) })
      }
    } catch (e) {
      if (my === testSeq.current) setTestResult({ error: e?.message || t('dbq.test.failedGeneric'), code: 'NETWORK_ERROR' })
    } finally {
      if (my === testSeq.current) setTesting(false)
    }
  }

  async function runPlanCheck() {
    if (!canRunQuery) return
    const my = ++planSeq.current
    setPlanChecking(true); setPlanResult(null)
    try {
      const res = await api.monitoring.dbQueryPlanCheck(form.profileId, form.sqlText)
      if (my !== planSeq.current) return
      if (res?.success) setPlanResult(res.data || { verdict: 'UNKNOWN' })
      // Bekleme ayrı bilgi tonunda — "Maliyet okunamadı" DEĞİL (DÜŞÜK 6); metin sunucunun (K3)
      else if (res?.code === 'RATE_LIMITED') setPlanResult({ rateLimited: true, message: rateLimitText(res, t, 'dbq.test.planFailed') })
      else setPlanResult({ supported: true, verdict: 'UNKNOWN', message: res?.error || t('dbq.test.planFailed') })
    } catch (e) {
      if (my === planSeq.current) setPlanResult({ supported: true, verdict: 'UNKNOWN', message: e?.message || t('dbq.test.planFailed') })
    } finally {
      if (my === planSeq.current) setPlanChecking(false)
    }
  }

  async function save() {
    // Doğrulama hataları ALANIN ALTINDA + ilk hatalıya kaydırma — tost yok.
    const purposeLen = form.purpose.trim().length
    if (fe.check({
      name: !form.name.trim() && t('mon.fieldRequired'),
      team_id: (form.teamId === '' || form.teamId == null) && t('mon.teamRequired'),
      group_name: !form.groupName?.trim() && t('mon.groupRequired'),
      tags: !form.tags?.trim() && t('mon.tagsRequired'),
      purpose: (purposeLen < PURPOSE_MIN || purposeLen > PURPOSE_MAX) && t('dbq.form.purposeLength', PURPOSE_MIN, PURPOSE_MAX),
      profile_id: form.profileId == null && t('dbq.form.connectionRequired'),
      sql_text: !form.sqlText.trim() && t('dbq.form.sqlRequired'),
    })) return
    const isNew = modal === 'new'
    const full = payloadFromForm(form)
    // Düzenlemede YALNIZ değişen alanlar (aynı izlemeyi açık tutan başkasının kaydı sessizce geri dönmesin).
    const body = isNew ? full : changedFieldGroups(full, payloadFromForm(formFrom(modal)))
    if (changeNote.trim()) body.changeNote = changeNote.trim()
    if (!isNew && Object.keys(body).length === 0) { toast.info(t('dbq.form.noChanges')); closeEditNow(); return }
    setSaving(true)
    try {
      const res = isNew
        ? await api.monitoring.createDbQueryMonitor(body)
        : await api.monitoring.updateDbQueryMonitor(modal.id, body)
      if (!res?.success) { applyServerErrors(res, 'dbq.form.saveFailed'); return }
      await load()
      setSelected((prev) => mergeSavedRow(prev, res.data))
      const planWarn = res.plan_warning || res.data?.plan_warning
      toast.success(t('dbq.form.saved'))
      if (planWarn) toast.info(t('dbq.form.planWarnSaved', planWarn.message || ''))
      // Kayıt uyarıları (engellemez — 2.B / 2.C): COUNT tuzağı, SELECT *, maskesiz hassas kolon
      const warns = warningsOf(res)
      if (warns.length) {
        toast.info(t('dbq.form.savedWithWarnings', warns.map((w) => (WARNING_CODES.includes(w.code) ? t(`dbq.warn.${w.code}.title`) : (w.message || w.code))).join(' · ')))
      }
      closeEditNow()
      if (shouldCheckAfterSave('dbquery', { isNew, before: isNew ? null : modal, after: res.data })) startCheckAfterSave(checkNow, res.data, { silent: true })
    } catch (e) {
      toast.error(e?.message || t('dbq.form.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  /** Silme — KALICI; onay izlemenin ADIYLA ve geri alınamaz olduğu söylenerek alınır. */
  async function deleteMonitor(m) {
    if (!m || m === 'new') return false
    const ok = await showConfirm({ title: t('mon.deleteTitle'), message: t('mon.deleteMsg', m.name),
      confirmText: t('dbq.page.delete'), cancelText: t('dbq.form.cancel'), variant: 'danger' })
    if (!ok) return false
    setDeleting(m.id)
    try {
      const res = await api.monitoring.deleteDbQueryMonitor(m.id)
      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return false }
      markMonitorDeleted('dbquery', m.id, res)
      setSelected((s) => (s?.id === m.id ? null : s))
      toast.success(t('dbq.page.deleted'))
      load()
      return true
    } finally {
      setDeleting(null)
    }
  }
  async function del() {
    if (!modal || modal === 'new') return
    if (await deleteMonitor(modal)) closeEditNow()
  }

  async function checkNow(m, { silent = false } = {}) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir (✓/✕ ve hata metni); tekil çağıran yok sayar.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerDbQueryCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => (x.id === m.id ? { ...x, ...res.data } : x)))
        setSelected(prev => (prev?.id === m.id ? withRowFlags(prev, res.data) : prev))
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      }
      const wait = retryAfterOf(res)
      // Sunucu iletisi zaten "N sn sonra yeniden deneyin" der — arkasına ikinci bekleme cümlesi eklenmez (DÜŞÜK 5)
      const msg = res?.code === 'DBQUERY_BUSY' ? t('dbq.page.busy')
        : res?.error ? res.error
        : wait ? `${t('dbq.page.checkFailed')} ${t('dbq.test.retryIn', wait)}` : t('dbq.page.checkFailed')
      if (!silent) toast.error(msg)
      return { ok: false, error: msg, data: res?.data ?? null }
    })
  }

  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  // Yalnız paylaşımla yazılan takımlar (`grant: true`) yeni izleme / kopya seçicisinde ve bağlantı / şablon yöneticisinde
  // yer almaz (O5, K2): bağlantılar ve şablonlar takımın kendi belgesidir, paylaşımla açılmaz. Düzenlemede seçeneklerin
  // tamamı formTeamSelect'e gider (kilitli satırda kayıtlı takım adıyla görünür; kendi kaydında paylaşım takımı zaten düşer).
  const grantTeamIds = useMemo(() => new Set(pickTeams.filter(tm => tm?.grant === true).map(tm => String(tm.id))), [pickTeams])
  const allTeamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('dbq.form.noTeam') }] : []),
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  const teamSelectOptions = useMemo(() => (modal === 'new'
    ? allTeamSelectOptions.filter(o => !grantTeamIds.has(String(o.value))) : allTeamSelectOptions), [modal, allTeamSelectOptions, grantTeamIds])
  const teamNameById = useMemo(() => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])
  const managerTeams = useMemo(() => (isAdmin && teams.length ? teams : pickTeams)
    .filter(tm => !grantTeamIds.has(String(tm.id))).map(tm => ({ id: tm.id, name: tm.name })), [isAdmin, teams, pickTeams, grantTeamIds])
  // Bağlantı / şablon yöneticisinin açılış takımı: takım süzgecindeki takım (ad → kimlik); yoksa kullanıcının varsayılan
  // takımı — o takımın listede izlemesi yoksa izlemesi olan İLK takım (yönetici kendi takımında izleme tutmuyorsa pencere
  // "kayıtlı bağlantı yok" diye açılmasın; takım seçici yine değiştirir).
  const managerTeamId = useMemo(() => {
    const hit = teamFilter !== 'all' && teamFilter !== '__none__' ? managerTeams.find(tm => tm.name === teamFilter) : null
    if (hit) return hit.id
    const owning = managerTeams.filter(tm => monitors.some(m => m.team_id === tm.id))
    if (defaultTeamId != null && (owning.length === 0 || owning.some(tm => tm.id === defaultTeamId))) return defaultTeamId
    return owning.length ? owning[0].id : (defaultTeamId ?? null)
  }, [teamFilter, managerTeams, defaultTeamId, monitors])
  const groupNames = useMemo(
    () => [...new Set(monitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)), [monitors])
  const hasGroupOptions = groupNames.length > 0
  const tagNames = useMemo(() => tagNamesOf(monitors), [monitors])
  const hasTagOptions = tagNames.length > 0 || monitors.some(m => !(m.tags || '').trim())
  const tagFilterOptions = useMemo(() => [{ value: 'all', label: t('mon.allTags') },
    ...tagNames.map(x => ({ value: x, label: x })),
    ...(monitors.some(m => !(m.tags || '').trim()) ? [{ value: '__none__', label: t('mon.noTags') }] : [])], [tagNames, monitors, t])
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('dbq.page.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(monitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('dbq.form.noGroup') }] : [])], [groupNames, monitors, t])
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])
  const dbTypesSeen = useMemo(() => DB_TYPES.filter(d => monitors.some(m => m.profile?.db_type === d)), [monitors])
  const dbFilterOptions = useMemo(() => [{ value: 'all', label: t('dbq.page.allDbTypes') },
    ...dbTypesSeen.map(d => ({ value: d, label: dbTypeLabel(d) }))], [dbTypesSeen, t])

  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (dbFilter !== 'all' && m.profile?.db_type !== dbFilter) return false
    if (matchesGroupOrTagText(m, search)) return true
    if (!search.trim()) return true
    return matchesSearchText(search, [m.name, m.purpose, m.profile?.name])
  }), [monitors, teamFilter, groupFilter, tagFilter, dbFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, ok: 0, warn: 0, down: 0, alarm: 0, unacked: 0 }
    for (const m of scoped) {
      const k = m.active === false ? 'paused' : dbqueryStatusKey(m)
      if (k === 'up') c.ok++
      else if (k === 'warn') c.warn++
      else if (k === 'down') c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      ok: m => m.active !== false && dbqueryStatusKey(m) === 'up',
      warn: m => m.active !== false && dbqueryStatusKey(m) === 'warn',
      down: m => m.active !== false && dbqueryStatusKey(m) === 'down',
      alarm: m => m.active_alarm,
      unacked: m => m.active_alarm && !m.alarm_acknowledged,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  // Varsayılan kart sırası: sorunlu önce → grup adı A→Z (grup içinde ad) → grupsuzlar ada göre
  const orderedMonitors = useMemo(() => sortMonitorsDefault(displayMonitors, 'dbquery'), [displayMonitors])
  const pager = usePagination(orderedMonitors, {
    listKey: 'dbquery-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, dbFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  useUrlQuerySync({
    team: teamFilter === 'all' ? null : teamFilter,
    group: groupFilter === 'all' ? null : groupFilter,
    tag: tagFilter === 'all' ? null : tagFilter,
    db: dbFilter === 'all' ? null : dbFilter,
    q: search.trim() || null,
    stat: statFilter || null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'summary' ? detailTab : null,
  })

  const statItems = [
    { key: 'total', Icon: LayoutDashboard, label: t('dbq.page.dashTotal'), value: counts.total, cls: 'total' },
    { key: 'ok', Icon: CheckCircle2, label: t('dbq.page.dashOk'), value: counts.ok, cls: 'valid' },
    { key: 'warn', Icon: TriangleAlert, label: t('dbq.page.dashWarn'), value: counts.warn, cls: 'warning', hint: t('dbq.page.dashWarnHint') },
    { key: 'down', Icon: ServerCrash, label: t('dbq.page.dashDown'), value: counts.down, cls: 'critical', hint: t('dbq.page.dashDownHint') },
    { key: 'alarm', Icon: Siren, label: t('dbq.page.dashAlarm'), value: counts.alarm, cls: 'high' },
    { key: 'unacked', Icon: BellDot, label: t('dbq.page.dashUnacked'), value: counts.unacked, cls: 'error', hint: t('mondash.unackedHint') },
  ]
  const onStatClick = (key) => setStatFilter(k => (k === key ? null : key))
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  function statusBadge(m) {
    return <MonitorStatusBadge status={dbqueryStatusKey(m)}>{t(dbqueryStatusLabelKey(m))}</MonitorStatusBadge>
  }
  // Sabit takım alanı: yeni kayıtta yalnız paylaşımla yazılan varsayılan takım gösterilmez (oraya kayıt yapılamaz — K2)
  const fixedTeamName = modal === 'new' && !ownDefaultTeam ? null : defaultTeamName
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('dbq.form.noTeam'))
    : (fixedTeamName || t('dbq.form.noTeam'))
  const activeMetric = METRICS.find(x => x.key === metric) ?? METRICS[0]
  // Ayrıntı başlığı: ad SARAR (adlar kesilmez — kalıcı kural); MonitorDetailModal'ın tek satır kırpması iç kapta kapatılır.
  const detailTitle = selected
    ? <span data-slot="dbq-detail-title" className="block whitespace-normal [overflow-wrap:anywhere]">{selected.name}</span>
    : null
  const formTeamId = form.teamId === '' || form.teamId == null ? null : Number(form.teamId)
  const { rules: ruleErrors, schedule: scheduleErrors } = splitFieldErrors(fe.errors)
  const sectionErrors = sectionErrorCounts(fe.errors)
  const limits = defaults
  const purposeLen = form.purpose.length

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={DatabaseZap} width={860}
      title={modal === 'new' ? t('dbq.form.titleNew') : t('dbq.form.titleEdit')}
      duplicate={!!dupSource} busy={saving}
      busyLabel={saving ? t('mon.saving') : testing ? t('dbq.form.testing') : null}
      footer={<>
        {modal !== 'new' && canDeleteRow(modal) && (
          <Button variant="destructive" className="mr-auto" onClick={del}><Trash2 size={14} />{t('dbq.page.delete')}</Button>
        )}
        <Button variant="secondary" onClick={closeEdit}>{t('dbq.form.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.teamId}>{t('dbq.form.save')}</Button>
      </>}>
      {dupSource
        ? <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>
        : <AlertBanner tone="info" icon={DatabaseZap}>{t('dbq.form.typeInfo')}</AlertBanner>}
      {modal === 'new' && teamless && <FormNoTeamAlert />}
      {grantLocked && (
        <AlertBanner tone="info" icon={Info} title={t('dbq.form.grantLockedTitle')}>
          <span data-slot="dbq-grant-locked-banner">{t('dbq.form.grantLockedBody')}</span>
        </AlertBanner>
      )}
      <FormSectionNav errorCounts={sectionErrors} />

      <FormGrid>
        <DbqSection id="basic" icon={Info} title={t('dbq.form.secBasic')}>
          <FormGrid>
            <FormField label={t('dbq.form.name')} required {...fe.fieldProps('name')} hint={t('dbq.form.nameHint')}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} value={form.name} maxLength={200} autoFocus={!!dupSource}
                  onChange={e => { setForm(f => ({ ...f, name: e.target.value })); fe.clear('name') }} />
              )}
            </FormField>
            <FormField label={t('dbq.form.team')} required {...fe.fieldProps('team_id')}>
              {({ id }) => canPickTeam
                ? <SearchableSelect id={id} value={form.teamId} onChange={onTeamChange} {...formTeamSelect(modal, teamSelectOptions, pickTeams)} searchThreshold={2} />
                : <Input id={id} value={fixedTeamName || t('dbq.form.noTeam')} disabled />}
            </FormField>
            <FormField full label={t('dbq.form.group')} required {...fe.fieldProps('group_name')}>
              {({ id }) => (
                <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('group_name') }}
                  options={[{ value: '', label: t('dbq.form.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('dbq.form.noGroup')} />
              )}
            </FormField>
            <FormSection title={t('dbq.form.tags')} required boxed={false} {...fe.fieldProps('tags')}>
              <TagInput value={form.tags} onChange={v => { setForm(f => ({ ...f, tags: v })); fe.clear('tags') }} placeholder={t('dbq.form.tagsPh')} suggestions={teamTags} />
            </FormSection>
            <FormField full label={t('dbq.form.purpose')} required {...fe.fieldProps('purpose')}
              hint={t('dbq.form.purposeHint', purposeLen, PURPOSE_MAX)}>
              {({ id, describedBy }) => (
                <Textarea id={id} aria-describedby={describedBy} rows={3} maxLength={PURPOSE_MAX} value={form.purpose}
                  placeholder={t('dbq.form.purposePh')}
                  onChange={e => { setForm(f => ({ ...f, purpose: e.target.value })); fe.clear('purpose') }} />
              )}
            </FormField>
            <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('dbq.form.active')} />
          </FormGrid>
        </DbqSection>

        <DbqSection id="connection" icon={DatabaseZap} title={t('dbq.form.secConnection')} hint={t('dbq.form.secConnectionHint')}>
          {connectionLocked ? (
            <p data-slot="dbq-connection-locked" className="m-0 text-sm">
              <strong className="[overflow-wrap:anywhere]">{modal.profile?.name || t('dbq.card.noConnection')}</strong>
              <span className="block text-xs text-muted-foreground">{t('dbq.form.connectionLocked')}</span>
            </p>
          ) : (
            <ConnectionPicker teamId={formTeamId} value={form.profileId} onChange={onConnectionChange}
              disabled={formTeamId == null} error={fe.errors.profile_id} canManage={canWrite} teams={managerTeams} />
          )}
          {formTeamId == null && !connectionLocked && <FormHint full={false}>{t('dbq.form.connectionNeedsTeam')}</FormHint>}
        </DbqSection>

        <QuerySection form={form} onSqlChange={onSqlChange} sqlError={fe.errors.sql_text} violations={sqlViolations}
          teamId={formTeamId} dbType={form.dbType || null}
          onPickTemplate={onPickTemplate} onSaveTemplate={() => setSaveTplOpen(true)}
          onTest={runTest} onPlanCheck={runPlanCheck} testing={testing} planChecking={planChecking}
          testResult={testResult} planResult={planResult} canRun={canRunQuery} runHint={runHint} locked={grantLocked}
          planAvailable={planCheckAvailable(limits, form.dbType || null)} onColumnAction={onColumnAction} />

        <EvaluationSection form={form} setForm={setForm} ruleErrors={ruleErrors} columns={columns}
          slowError={fe.errors.slow_query_ms} onRulesChange={onRulesChange} clear={clear} locked={grantLocked}
          trapIndexes={trapIndexes} trapColumn={trapColumn} newRuleFactory={newRuleFactory}
          backtestMonitorId={modal && modal !== 'new' ? modal.id : null} />

        <ScheduleSection value={form.schedule} onChange={onScheduleChange} fieldErrors={scheduleErrors}
          minIntervalSeconds={defaults?.min_interval_seconds} canEditHolidays={globalAdmin} />

        <LimitsSection form={form} setForm={setForm} errors={fe.errors} limits={limits} clear={clear} locked={grantLocked} />

        <DbqSection id="alerting" icon={BellRing} title={t('dbq.form.secAlerting')} hint={t('dbq.form.secAlertingHint')}>
          <FormGrid>
            <NotifyChannels locked={routingLock}
              notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
              alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
              onChange={patch => setForm(f => ({ ...f, ...patch }))}
              teamLabel={selectedTeamLabel} teamId={form.teamId}
              groupId={form.notificationGroupId}
              onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
            <FormHint>{t('dbq.form.alertLevelHint')}</FormHint>
            <NocNotifyField disabled={routingLock} type="DBQUERY" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
              onChange={patch => setForm(f => ({ ...f, ...patch }))} />
            <MailFields form={form} setForm={setForm} errors={fe.errors} columns={columns} clear={clear} limits={limits} locked={grantLocked}
              suggestedMasks={testResult?.suggested_masks} />
            {/* Teyit / kurtarma (standart; varsayılan 1 / 1 — iş alarmı gürültüsüz ama tek hatada da uyarır) */}
            <div className="min-w-0 rounded-lg border sm:col-span-2">
              <Button type="button" variant="ghost" aria-expanded={advOpen} onClick={() => setAdvOpen(v => !v)} data-slot="dbq-confirm-toggle"
                className="h-auto w-full justify-start gap-2 rounded-none px-3.5 py-3 font-semibold hover:bg-muted/60 pointer-coarse:min-h-10">
                <ChevronDown size={16} aria-hidden="true" className={cn('text-muted-foreground transition-transform motion-reduce:transition-none', advOpen && 'rotate-180')} />
                {t('dbq.form.confirmTitle')}
              </Button>
              {advOpen && (
                <div className="px-3.5 pb-3.5">
                  <FormGrid>
                    <FormField label={t('dbq.form.confirmAttempts')} name="confirm_attempts" error={fe.errors.confirm_attempts}>
                      {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: e.target.value }))} />}
                    </FormField>
                    <FormField label={t('dbq.form.confirmInterval')} name="confirm_interval_seconds" error={fe.errors.confirm_interval_seconds}>
                      {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: e.target.value }))} />}
                    </FormField>
                    <FormField label={t('dbq.form.recoveryChecks')} name="recovery_checks" error={fe.errors.recovery_checks}>
                      {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: e.target.value }))} />}
                    </FormField>
                    <FormField label={t('dbq.form.recoveryInterval')} name="recovery_interval_seconds" error={fe.errors.recovery_interval_seconds}>
                      {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: e.target.value }))} />}
                    </FormField>
                  </FormGrid>
                  <FormHint full={false} className="mt-2">{t('dbq.form.confirmHint')}</FormHint>
                </div>
              )}
            </div>
          </FormGrid>
        </DbqSection>
      </FormGrid>

      {modal !== 'new' && (
        <ChangeNoteField t={t} id="dbquery-change-note" value={changeNote} onChange={setChangeNote} />
      )}
      {saveTplOpen && (
        <SaveTemplateDialog open={saveTplOpen} onOpenChange={setSaveTplOpen} teamId={formTeamId}
          draft={{ sql_text: form.sqlText, purpose: form.purpose, rules: form.rules, rule_logic: form.ruleLogic, db_type: form.dbType || null }}
          onSaved={() => toast.success(t('dbq.form.templateSaved'))} />
      )}
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader type="dbquery" title={t('dbq.page.title')} subtitle={t('dbq.page.subtitle')}
        count={loading ? null : monitors.length} down={counts.down}
        refreshEvery={REFRESH_INTERVAL} refreshResetKey={loadNonce} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        extraActions={<>
          <Button type="button" variant="outline" className="h-10 sm:h-9 pointer-coarse:h-10" data-slot="dbq-open-connections" onClick={() => setConnOpen(true)}>
            <Cable aria-hidden="true" className="size-4" />{t('dbq.page.connections')}
          </Button>
          <Button type="button" variant="outline" className="h-10 sm:h-9 pointer-coarse:h-10" data-slot="dbq-open-templates" onClick={() => setTplOpen(true)}>
            <LibraryBig aria-hidden="true" className="size-4" />{t('dbq.page.templates')}
          </Button>
        </>}
        canWrite={canWrite} onNew={() => openNew()} newLabel={t('dbq.page.add')} />

      <MonitorHowBox bullets={[t('dbq.page.how1'), t('dbq.page.how2'), t('dbq.page.how3'), t('dbq.page.how4'),
        t('dbq.page.how5'), t('dbq.page.how6')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          {dbTypesSeen.length > 1 && <SearchableSelect value={dbFilter} onChange={setDbFilter} options={dbFilterOptions} ariaLabel={t('dbq.page.dbTypeFilter')} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('dbq.page.search')} aria-label={t('dbq.page.search')}
            value={search} onChange={e => setSearch(e.target.value)} data-page-search="" />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('dbq.page.emptyAdmin') : t('dbq.page.empty')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        {loadError && (
          <div data-slot="dbq-stale"><AlertBanner tone="warning" className="mb-3"
            actions={<Button variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={load}>{t('hist.retry')}</Button>}>
            {t('dbq.page.staleList')}
          </AlertBanner></div>
        )}
        {/* Takım değişimi bağlantı seçimi gerektirir (izleme ve bağlantı aynı takımda olmalı) — toplu yapılamaz (O6) */}
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="DBQUERY"
          teamChange={false} teamChangeNote={t('dbq.page.bulkTeamNote')}
          api={{ update: api.monitoring.updateDbQueryMonitor, remove: api.monitoring.deleteDbQueryMonitor }}
          onClear={() => setBulkSel(new Set())} onPrune={setBulkSel} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            <DbQueryMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} status={dbqueryStatusKey(m)} badge={statusBadge(m)} density={density} running={isRunning(m.id)}
              onOpen={() => openDetail(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              selection={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.name)} />
              )}
              meta={<MonitorCardMeta monitor={m} />}
              actions={(canManageRow(m) || canCheckRow(m) || canDeleteRow(m)) && (
                <MonitorCardActions onResume={canManageRow(m) ? () => resume(m) : undefined} resuming={isResuming(m.id)} rowLabel={m.name}
                  running={isRunning(m.id)}
                  onCheck={canCheckRow(m) ? () => checkNow(m) : undefined} onEdit={canManageRow(m) ? () => openEdit(m) : undefined} onDuplicate={canManageRow(m) ? () => openDuplicate(m) : undefined}
                  checkTitle={t('dbq.page.check')} editTitle={t('dbq.page.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('dbq.page.delete')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={dbqueryStatusKey(selected)} badge={statusBadge(selected)}
          title={detailTitle}
          noc={{ type: 'DBQUERY', monitor: selected, canEdit: canManageRow(selected) }}
          actions={
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
              running={isRunning(selected.id)}
              onCheck={canCheckRow(selected) ? () => checkNow(selected) : undefined}
              checkTitle={t('dbq.page.check')}
              onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
              editTitle={t('dbq.page.edit')}
              onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
              onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
              deleting={deleting === selected.id}
              deleteTitle={t('dbq.page.delete')}
              onClose={closeDetail}>
              <CopyLinkButton iconOnly variant="outline" className="pointer-coarse:size-10" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-0" />
          <DetailSummary items={[
            { key: 'status', value: t(dbqueryStatusLabelKey(selected)), label: t('dbq.detail.lastStatus'), valueClassName: STATUS_TEXT[dbqueryStatusKey(selected)] },
            { key: 'value', value: metricValueText(selected.metric_value), label: t('dbq.card.value') },
            { key: 'rows', value: rowCountText(selected.row_count, selected.row_count_capped), label: t('dbq.card.rows'),
              hint: selected.row_count_capped ? t('dbq.detail.cappedHint') : undefined },
            { key: 'duration', value: durationText(selected.duration_ms, t), label: t('dbq.card.duration') },
            selected.last_check && { key: 'last', value: formatDateSec(selected.last_check), label: t('dbq.detail.lastRun'), time: true },
          ]} />
          <DetailDivider />
          <DetailTabs value={detailTab} onValueChange={setDetailTab} className="mt-0"
            countsFor={{ kind: 'dbquery', monitorId: selected.id, notesType: 'DBQUERY', notesTarget: selected.name, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['summary', t('dbq.detail.tabSummary')], ['control', t('hist.tab')], ['chart', t('dbq.detail.tabChart')],
              ['alerts', t('dbq.detail.tabAlerts')], ['notes', t('dbq.detail.tabNotes')], ['changes', t('chg.tab')]]}>
            <TabsContent value="summary">
              <DbQueryDetailSummary monitor={selected} />
            </TabsContent>

            <TabsContent value="control">
              <CheckHistoryTab kind="dbquery" monitorId={selected.id} listKey="dbquery-history" reloadSignal={histReload}
                defaultPreset={7} gridClass="dbq-rt-grid" extraParams={problemOnly ? { only: 'problem' } : null}
                filterMode={problemOnly ? 'none' : 'fail'}
                renderAbove={() => (
                  <div data-slot="dbq-hist-problem" className="mb-2 flex min-w-0 items-center gap-2 pointer-coarse:min-h-10">
                    <Switch id="dbq-hist-problem" checked={problemOnly} onCheckedChange={(v) => setProblemOnly(v === true)} />
                    <Label htmlFor="dbq-hist-problem" className="text-sm font-normal">{t('dbq.detail.problemOnly')}</Label>
                  </div>
                )}
                columns={[t('dbq.detail.colTime'), t('dbq.detail.colStatus'), t('dbq.card.value'), t('dbq.card.rows'), t('dbq.card.duration')]}
                renderRow={(c) => <HistoryCells c={c} t={t} open={histOpen.has(String(c.id ?? c.checked_at))}
                  onToggle={() => setHistOpen((s) => { const k = String(c.id ?? c.checked_at); const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n })} />} />
            </TabsContent>

            <TabsContent value="chart">
              <div className="mb-2.5 flex flex-wrap items-center gap-2.5 border-b pb-2.5">
                <span className="text-[.82em] font-semibold tracking-[.03em] text-muted-foreground uppercase">{t('dbq.detail.metricLabel')}</span>
                <SegmentedControl ariaLabel={t('dbq.detail.metricLabel')} value={metric} onChange={setMetric}
                  options={METRICS.map(mt => ({ value: mt.key, label: t(mt.labelKey) }))} className="max-w-full flex-wrap pointer-coarse:[&>[data-slot=toggle-group-item]]:h-10" />
              </div>
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="dbquery" metric={activeMetric.key} unit={activeMetric.unit}
                  budget={activeMetric.key === 'duration' ? (selected.slow_query_ms || null) : null} budgetLabel={t('dbq.detail.slowLine')} />
              </Suspense>
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.name} types={alertTypesFor('dbquery')} /></TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="DBQUERY" target={selected.name} canManage={canManageRow(selected)} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="dbquery" monitorId={selected.id}
                  teamNames={teamNameById} canManage={canDeleteRow(selected)}
                  onRestored={() => reloadAndSyncDetail(load, selected.id, setSelected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

          {formModal}
        </MonitorDetailModal>
      )}
      {!selected && formModal}

      {connOpen && (
        <ConnectionsManager open={connOpen} onOpenChange={setConnOpen} teamId={managerTeamId}
          teams={managerTeams} canEdit={canWrite} onChanged={load} />
      )}
      {tplOpen && (
        <TemplatesManager open={tplOpen} onOpenChange={setTplOpen} teamId={managerTeamId}
          teams={managerTeams} canEdit={canWrite} onCreateMonitor={canWrite ? openFromTemplate : null} />
      )}

      {checkRun.pickerOpen && (
        <CheckTeamPicker
          buckets={monitorTeamBuckets(checkable)}
          storageKey="sm.checkRun.teams.dbquery"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="dbquery"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}

/**
 * Kontrol geçmişi satırının hücreleri — zaman (+ elle rozeti), durum (+ seviye), değer, satır, süre; SONRA tam
 * genişlikte ek içerik: kural sonuçları (aç / kapa) ve hata metni. Satır verisi YOK — yalnız ölçülen sayılar.
 */
function HistoryCells({ c, t, open, onToggle }) {
  const key = checkStatusKey(c)
  const levelKey = c.status === 'BREACH' ? breachLevelKey(c.breach_level) : null
  const results = Array.isArray(c.rule_results) ? c.rule_results : []
  const hasExtra = results.length > 0 || !!c.error
  return (<>
    <span className="upt-rt-time inline-flex flex-wrap items-center gap-1.5">
      {formatDateSec(c.checked_at)}
      {c.manual && (
        <Badge variant="outline" data-slot="dbq-hist-manual" className="gap-1 px-1.5 py-0 text-[10.5px]">
          <Hand aria-hidden="true" className="size-3" />{t('dbq.detail.manual')}
        </Badge>
      )}
      {c.recheck && (
        <Badge variant="outline" data-slot="dbq-hist-recheck" title={t('dbq.detail.recheckHint')} className="gap-1 px-1.5 py-0 text-[10.5px]">
          <Repeat aria-hidden="true" className="size-3" />{t('dbq.detail.recheck')}
        </Badge>
      )}
    </span>
    <span data-slot="dbq-hist-status" data-status={key} className={cn('inline-flex flex-wrap items-center gap-1.5 font-semibold', STATUS_TEXT[key])}>
      {t(checkStatusLabelKey(c))}
      {levelKey && <Badge variant={c.breach_level === 'CRITICAL' ? 'destructive' : 'warning'} className="px-1.5 py-0 text-[10.5px]">{t(levelKey)}</Badge>}
    </span>
    <span className="upt-rt-ms">{metricValueText(c.metric_value)}</span>
    <span className="upt-rt-ms">{rowCountText(c.row_count, c.row_count_capped)}</span>
    <span className="upt-rt-ms">{durationText(c.duration_ms, t)}</span>
    {hasExtra && (
      <div data-slot="dbq-hist-extra" className="flex min-w-0 flex-col gap-1.5">
        {c.error && <span className="text-xs text-destructive [overflow-wrap:anywhere]">{c.error}</span>}
        {results.length > 0 && (
          <>
            <Button type="button" variant="ghost" size="sm" aria-expanded={open} onClick={onToggle} data-slot="dbq-hist-toggle"
              className="h-7 self-start px-2 text-xs pointer-coarse:h-10">
              <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
              {t(open ? 'dbq.detail.hideRules' : 'dbq.detail.showRules', results.length)}
            </Button>
            {open && <RuleResultsList results={results} compact />}
          </>
        )}
      </div>
    )}
  </>)
}
