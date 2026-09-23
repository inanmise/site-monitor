import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { formatPercent } from '../i18n/dateLocale.js'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { CheckRunningStrip } from './ui/CheckRunning.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import TagInput from './ui/TagInput.jsx'
import { RefreshCw, Plus, Trash2, Target, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ChevronDown, ShieldCheck, Inbox } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
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
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

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
const emptyForm = { name: '', url: '', keyword: '', operator: 'GTE', matchCount: 1, groupName: '', notificationGroupId: '', teamId: '',
  caseSensitive: false, useProxy: 'AUTO', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  checkSslErrors: false, sslExpiryReminders: false, domainExpiryReminders: false,
  sslReminderDays: '30,14,7', domainReminderDays: '30,14,7',
  slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 60, timeoutMs: 10000, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, customHeaders: '', active: true }

export default function KeywordMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'      // USER ve üstü: kendi takımı için oluştur/düzenle/kontrol
  const myTeam = teamId != null ? String(teamId) : null
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId })
  const canManageRow = (m) => isAdmin || isOwnTeam(m)                    // düzenle + kontrol (kendi takımı)
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  const canCheckRow = canManageRow
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))   // silme: TEAM_ADMIN/ADMIN
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('keyword')   // kart mini trendi (2026-09-12)
  const sla = useSla('keyword')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  useEscapeKey(!!selected, closeDetail)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [defaults, setDefaults] = useState(null)   // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [showCacheHelp, setShowCacheHelp] = useState(false)   // cache busting açıklama modal'ı
  const [advOpen, setAdvOpen] = useState(false)               // "Gelişmiş ayarlar" accordion
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [testing, setTesting] = useState(false)
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('control')
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
      const res = await api.monitoring.getKeywordMonitors()
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

  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da gizli sekmede durur

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.keyword) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili monitörün detayını aç (bir kez).
  useMonitorDeepLink(monitors, openDetail)

  function openDetail(m) { setSelected(m); setSummary({ total: 0, down: 0 }); setDetailTab('control') }
  function closeDetail() { setSelected(null) }

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      slowThresholdMs: defaults?.slowThresholdMs ?? emptyForm.slowThresholdMs })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', url: m.url || '', keyword: m.keyword || '',
      operator: m.operator || 'GTE', matchCount: m.match_count ?? 1, groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
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
    if (!form.url.trim() || !form.keyword.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName?.trim()) { toast.error(t('mon.groupRequired')); return }   // grup + etiket zorunlu (2026-09-18)
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }
    setSaving(true)
    try {
      const payload = {
        name: (form.name || form.url).trim(), url: normalizeUrl(form.url), keyword: form.keyword,
        operator: form.operator, matchCount: Number(form.matchCount),
        groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
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
        if (selected?.id === m.id) setSelected(res.data)
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
  const pager = usePagination(displayMonitors, {
    listKey: 'keyword-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, proxyFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, proxyFilter, search, statFilter, pager }),
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
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

  function cardClass(m) {
    if (m.status === 'up') return 'upt-card--up'
    if (m.status === 'unknown') return 'upt-card--unknown'
    return 'upt-card--down'
  }
  function statusBadge(m) {
    const s = m?.status
    const cls = s === 'up' ? 'upt-badge--up' : s === 'unknown' ? 'upt-badge--unknown' : 'upt-badge--down'
    const label = s === 'up' ? t('keyword.statusOk')
      : s === 'unknown' ? t('keyword.statusUnknown')
      : s === 'error' ? t('keyword.statusError') : t('keyword.statusViolation')
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{label}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('keyword.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
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
    : (teamName || t('keyword.noTeam'))

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('keyword.title')}</h2>
          <p className="upt-subtitle">{t('keyword.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('keyword.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('keyword.refresh')}
          </button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="keyword" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('keyword.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('keyword.how1'), t('keyword.how2'), t('keyword.how3')]} />

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
          <input className="upt-search" type="text" placeholder={t('keyword.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('keyword.noMonitorsAdmin') : t('keyword.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updateKeywordMonitor, remove: api.monitoring.deleteKeywordMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        {/* Süzgeç/arama hiçbir izlemeyi bırakmadıysa boş alan yerine açık mesaj (2026-09-22; vekil süzgeciyle görünür oldu) */}
        {displayMonitors.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('mon.noFilterMatch')} description={t('empty.hintFilter')} />}
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            /* Kart klavyeyle de açılabilir (ScriptedMonitorPage kalıbı): role+tabIndex+Enter/Space.
               onKeyDown YALNIZ kartın KENDİ hedefinde çalışır — içerideki düğmelerde Enter'a
               basıldığında tuş olayı karta baloncuklanıp detayı DA açardı (çift eylem). */
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              role="button" tabIndex={0} aria-label={t('mon.openDetailFor', m.url)}
              onKeyDown={e => {
                if (e.target !== e.currentTarget) return
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDetail(m) }
              }}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {canManageRow(m) && (
                  <input type="checkbox" className="upt-card-check" checked={bulkSel.has(m.id)} onChange={() => toggleBulk(m.id)} onClick={(e) => e.stopPropagation()} aria-label={t('bulk.selectOneFor', m.url)} />
                )}
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.url} />
                <span className="upt-card-top-right">
                  <span className="upt-port-tag">
                    {(OP_SYM[m.operator] || '≥') + (m.match_count ?? 1)} {t('keyword.times')}
                  </span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('keyword', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.url}>{m.url}</div>
              <div style={{ fontSize: '.8em', color: 'var(--text-muted)', marginTop: 2, wordBreak: 'break-word' }}>
                <Target size={11} style={{ verticalAlign: '-1px', marginRight: 4 }} />{m.keyword}
              </div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.http_status ?? '—'}</span>
                  <span className="upt-metric-lbl">HTTP</span>
                </div>
                {m.response_ms != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{m.response_ms}ms</span>
                    <span className="upt-metric-lbl">{t('keyword.responseMs')}</span>
                  </div>
                )}
                {m.occurrences != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{m.occurrences}</span>
                    <span className="upt-metric-lbl">{t('keyword.occurrences')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('keyword.check')} editTitle={t('keyword.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('keyword.delete')} />
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
          <div className={`upt-modal upt-modal--${selected.status === 'up' ? 'up' : selected.status === 'unknown' ? 'unknown' : 'down'}`} onClick={e => e.stopPropagation()}>
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
                checkTitle={t('keyword.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('keyword.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('keyword.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('keyword.sumOkHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('keyword.sumOk')}</span>
              </div>
              <div className="upt-modal-metric" title={t('keyword.sumTotalHint')}><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('keyword.sumTotal')}</span></div>
              <div className="upt-modal-metric" title={t('keyword.sumIncidentsHint')}><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('keyword.sumIncidents')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.keyword}</span><span className="upt-modal-metric-lbl">{t('keyword.keyword')}</span></div>
              {selected.http_status != null && <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.http_status}</span><span className="upt-modal-metric-lbl">HTTP</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('keyword.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            <div className="kw-reqinfo">
              <div className="kw-reqinfo-title">{t('keyword.reqSettings')}</div>
              <div className="kw-reqinfo-row">
                <span className="kw-reqinfo-k">{t('keyword.cacheBusting')}</span>
                <span className={selected.url && selected.url.includes('{timestamp}') ? 'kw-on' : 'kw-off'}>
                  {selected.url && selected.url.includes('{timestamp}') ? t('keyword.cbOn') : t('keyword.cbOff')}
                </span>
              </div>
              <div className="kw-reqinfo-row">
                <span className="kw-reqinfo-k">{t('keyword.customHeadersShort')}</span>
                {/* Düz değer artık API'den GELMİYOR (şifreli). Yalnız varlık + ad listesi. */}
                {selected.has_custom_headers
                  ? <span className="kw-on">{(selected.custom_header_names || []).filter(Boolean).join(', ') || t('keyword.customHeadersSet')}</span>
                  : <span className="kw-off">{t('keyword.none')}</span>}
              </div>
            </div>
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('keyword.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('keyword.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('keyword.tabGuide')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'control' && (
              <CheckHistoryTab kind="keyword" monitorId={selected.id} listKey="keyword-history" reloadSignal={histReload}
                columns={[t('keyword.colTime'), t('keyword.colStatus'), 'HTTP', t('keyword.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                renderRow={(c) => {
                  const occ = c.occurrences != null ? c.occurrences : (c.found ? '≥1' : 0)
                  const cmp = `${OP_SYM[selected.operator] || '≥'}${selected.match_count ?? 1}`
                  return (<>
                    <span className="upt-rt-time">{formatDateSec(c.checked_at)}</span>
                    <span className={c.ok ? 'upt-rt-up' : 'upt-rt-down'}>{c.ok ? t('keyword.statusOk') : (c.error ? t('keyword.statusError') : t('keyword.statusViolation'))}</span>
                    <span className="upt-rt-ms">{c.http_status ?? '—'}</span>
                    {c.error
                      ? <span className="upt-rt-error" title={c.error}>{c.error}</span>
                      : <span style={{ whiteSpace: 'nowrap' }}
                          title={`« ${selected.keyword} » → ${occ} ${t('keyword.testFound')} · ${t('keyword.testRequired')}: ${cmp} (${expectPhrase(selected.operator, selected.match_count ?? 1)})${c.snippet ? '\n— ' + c.snippet : ''}`}>
                          <strong>{occ}</strong> {t('keyword.testFound')} <span style={{ color: 'var(--text-muted)' }}>· {cmp}</span>
                        </span>}
                  </>)
                }} />
            )}

            {detailTab === 'alerts' && <AlertHistory domain={selected.url} types={alertTypesFor('keyword')} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="keyword" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="KEYWORD" target={selected.url} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="keyword" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── (dış/overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()} style={{ maxWidth: 720, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--keyword">
              <div className="modal-icon-hdr-badge"><Target size={20} /></div>
              <h3>{modal === 'new' ? t('keyword.modalNew') : t('keyword.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('keyword.testing')} /></span>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>

            {dupSource
              ? <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>
              : <div className="kw-type-banner"><Target size={16} /><span>{t('keyword.typeInfo')}</span></div>}

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('keyword.url')} <span className="req-star">*</span></span>
                <input value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
                  onChange={e => setForm(f => ({ ...f, url: e.target.value }))}
                  onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('keyword.urlHint')}</div>
              <label className="full-width"><span>{t('keyword.customHeaders')}{' '}
                <button type="button" onClick={() => setShowCacheHelp(true)}
                  style={{ background: 'none', border: 'none', color: 'var(--primary, #4f46e5)', cursor: 'pointer', fontSize: '.85em', textDecoration: 'underline', padding: 0, fontWeight: 500 }}>
                  {t('keyword.cacheBustLink')}
                </button></span>
                <textarea rows={2} value={form.customHeaders} spellCheck={false} placeholder={t('keyword.customHeadersPh')}
                  disabled={!isAdmin}
                  onChange={e => setForm(f => ({ ...f, customHeaders: e.target.value }))} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>
                {/* İsim listesi BOŞ olabilir: API adları yalnız global admin'e döndürüyor ve
                    kayıtlı metin "Ad: değer" biçiminde değilse ayrıştırılamıyor. Yer tutucuyu boş
                    dizeyle doldurmak "Kayıtlı başlıklar: ." gibi kırık bir cümle üretiyor ve
                    kullanıcıya hiçbir şey kayıtlı değilmiş izlenimi veriyordu. Yedek metin hem
                    cümleyi tamamlıyor hem kayıtlı değerin biçim sorununu işaret ediyor. */}
                {modal !== 'new' && modal?.has_custom_headers
                  ? t('keyword.customHeadersSavedHint').replace('{0}',
                      (modal.custom_header_names || []).filter(Boolean).join(', ') || t('mon.customHeadersSavedUnnamed'))
                  : t('keyword.customHeadersHint')}
              </div>
              <label><span>{t('keyword.operator')}</span>
                <SearchableSelect value={form.operator} onChange={v => setForm(f => ({ ...f, operator: v }))}
                  options={[{ value: 'GTE', label: t('keyword.opGte') }, { value: 'LTE', label: t('keyword.opLte') },
                    { value: 'EQ', label: t('keyword.opEq') }, { value: 'GT', label: t('keyword.opGt') },
                    { value: 'LT', label: t('keyword.opLt') }]} /></label>
              <label><span>{t('keyword.matchCount')}</span>
                <input type="number" min="0" value={form.matchCount} onChange={e => setForm(f => ({ ...f, matchCount: Number(e.target.value) }))} /></label>
              <div className="full-width" style={{ background: '#f1f5f9', borderLeft: '3px solid #1f3864', borderRadius: '0 6px 6px 0', padding: '9px 12px', fontSize: '.82em', lineHeight: 1.55, color: '#334155' }}>
                <div><Check size={12} style={{ verticalAlign: '-2px', color: '#15803d' }} /> <strong>{t('keyword.explHealthy')}:</strong> « {form.keyword?.trim() || t('keyword.theKeyword')} » {expectPhrase(form.operator, Number(form.matchCount) || 0)} bulunmalı.</div>
                <div style={{ marginTop: 4 }}><AlertTriangle size={12} style={{ verticalAlign: '-2px', color: '#dc2626' }} /> <strong>{t('keyword.explAlarm')}:</strong> {triggerPhrase(form.operator, Number(form.matchCount) || 0, form.keyword)} tetiklenir.</div>
              </div>
              <label><span>{t('keyword.keyword')} <span className="req-star">*</span></span>
                <input value={form.keyword} placeholder="SUCCESS" onChange={e => setForm(f => ({ ...f, keyword: e.target.value }))} /></label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.caseSensitive} onChange={e => setForm(f => ({ ...f, caseSensitive: e.target.checked }))} />{t('keyword.caseSensitive')}</label>
              {/* Kurumsal vekil (2026-09-21): sertifika envanteriyle aynı karar */}
              <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
                effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />
              <label><span>{t('keyword.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('keyword.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('keyword.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('keyword.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('keyword.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('keyword.noGroup')} /></label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
                onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />

              {/* Etiketler */}
              <div className="full-width kw-tags-block">
                <div className="kw-block-title">{t('keyword.tagsTitle')} <span className="req-star">*</span></div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('keyword.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('keyword.tagsPlaceholder')} suggestions={teamTags} />
              </div>



              {/* SSL + Domain kontrolleri */}
              <div className="full-width kw-ssl-section">
                <div className="kw-block-title"><ShieldCheck size={15} style={{ verticalAlign: '-2px', marginRight: 5 }} />{t('keyword.sslSectionTitle')}</div>
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.checkSslErrors} onChange={e => setForm(f => ({ ...f, checkSslErrors: e.target.checked }))} />{t('keyword.checkSslErrors')}</label>
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.sslExpiryReminders} onChange={e => setForm(f => ({ ...f, sslExpiryReminders: e.target.checked }))} />{t('keyword.sslExpiryReminders')}</label>
                {form.sslExpiryReminders && (
                  <div className="kw-days-row">
                    <span>{t('keyword.reminderDays')}</span>
                    <input value={form.sslReminderDays} placeholder="30,14,7" onChange={e => setForm(f => ({ ...f, sslReminderDays: e.target.value }))} />
                  </div>
                )}
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.domainExpiryReminders} onChange={e => setForm(f => ({ ...f, domainExpiryReminders: e.target.checked }))} />{t('keyword.domainExpiryReminders')}</label>
                {form.domainExpiryReminders && (
                  <div className="kw-days-row">
                    <span>{t('keyword.reminderDays')}</span>
                    <input value={form.domainReminderDays} placeholder="30,14,7" onChange={e => setForm(f => ({ ...f, domainReminderDays: e.target.value }))} />
                  </div>
                )}
                <div className="field-hint">{t('keyword.whoisHint')}</div>
              </div>

              {/* Gelişmiş ayarlar — açılır/kapanır */}
              <div className="full-width kw-adv">
                <button type="button" className="kw-adv-toggle" onClick={() => setAdvOpen(o => !o)}>
                  <ChevronDown size={16} className={`kw-adv-chevron${advOpen ? ' open' : ''}`} />
                  <span>{t('keyword.advanced')}</span>
                </button>
                {advOpen && (
                  <div className="kw-adv-body">
                    <div className="kw-block-title">{t('keyword.timeoutTitle')}</div>
                    <div className="field-hint" style={{ marginBottom: 8 }}>{t('keyword.timeoutEvery').replace('{0}', Math.min(60, Math.max(1, Math.round(Number(form.timeoutMs) / 1000))))}</div>
                    <input type="range" className="kw-interval-slider" min={1} max={60} step={1}
                      value={Math.min(60, Math.max(1, Math.round(Number(form.timeoutMs) / 1000)))}
                      onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) * 1000 }))} />
                    <label className="checkbox-label" style={{ marginTop: 12 }}>
                      <input type="checkbox" checked={form.slowResponseEnabled} onChange={e => setForm(f => ({ ...f, slowResponseEnabled: e.target.checked }))} />{t('keyword.slowEnable')}</label>
                    {form.slowResponseEnabled && (
                      <div className="kw-days-row">
                        <span>{t('keyword.slowThreshold')}</span>
                        <input type="number" min="100" step="100" value={form.slowThresholdMs} onChange={e => setForm(f => ({ ...f, slowThresholdMs: Number(e.target.value) }))} />
                      </div>
                    )}
                    <div className="field-hint" style={{ marginBottom: 4 }}>{t('keyword.slowHint')}</div>
                    <div className="kw-adv-grid">
                      <label><span>{t('keyword.confirmAttempts')}</span>
                        <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
                      <label><span>{t('keyword.confirmInterval')}</span>
                        <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
                      <label><span>{t('keyword.recoveryChecks')}</span>
                        <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
                      <label><span>{t('keyword.recoveryInterval')}</span>
                        <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
                    </div>
                    <label className="checkbox-label" style={{ marginTop: 10 }}>
                      <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('keyword.active')}</label>
                    <div className="field-hint" style={{ marginTop: 6 }}>ⓘ {t('keyword.confirmHint')}</div>
                  </div>
                )}
              </div>
            </div>
            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error
                  ? { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }
                  : testResult.condition_met
                    ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                    : { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }) }}>
                {testResult.error || !testResult.condition_met
                  ? <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.error
                    ? <><strong>{t('keyword.testError')}:</strong> {testResult.error}</>
                    : <>
                        <strong>{testResult.condition_met ? t('keyword.testMet') : t('keyword.testNotMet')}</strong>
                        {' — « '}{form.keyword}{' » '}{testResult.occurrences} {t('keyword.testFound')} · {t('keyword.testRequired')}: {testResult.phrase}
                        {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}
                        {testResult.response_ms != null && <> · {testResult.response_ms}ms</>}
                      </>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="keyword-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                aria-busy={testing || undefined} disabled={testing || !form.url.trim() || !form.keyword.trim()}>
                <FlaskConical size={14} />{t('keyword.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('keyword.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('keyword.cancel')}</button>
              <button className="btn btn-primary" onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.keyword.trim() || !form.teamId}>{t('keyword.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {showCacheHelp && createPortal(
        <div className="modal-overlay" onClick={() => setShowCacheHelp(false)}>
          {/* Kısa bir yardım diyaloğu — bugünkü metinle taşmıyor. İç kaydırma yine de beyan
              edilir: kural muaf listesi tutmaz (kısa/zoom'lu ekranda taşarsa kaydırma çubuğu
              ekranın en sağında çıkar), ve taşma olmadıkça hiçbir görsel etkisi yok. */}
          <div className="modal-box" onClick={e => e.stopPropagation()}
            style={{ maxWidth: 560, maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--keyword">
              <div className="modal-icon-hdr-badge"><Target size={20} /></div>
              <h3>{t('keyword.cacheBustTitle')}</h3>
            </div>
            <div style={{ fontSize: '.9em', color: 'var(--text, #1e293b)', lineHeight: 1.6, padding: '4px 2px' }}>
              <p style={{ marginTop: 0 }}>{t('keyword.cacheBustHint')}</p>
              <div style={{ whiteSpace: 'pre-line' }}>{t('keyword.cacheBustExamples')}</div>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setShowCacheHelp(false)}>{t('sql.closeRowDetails')}</button>
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
