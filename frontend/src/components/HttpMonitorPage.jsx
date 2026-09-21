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
import { RefreshCw, Plus, Trash2, Globe, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, ShieldCheck, Inbox } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useTeamOptions } from '../hooks/useTeamOptions.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import CheckAllButton from './check/CheckAllButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import MonitorModalActions from './ui/MonitorModalActions.jsx'
import { monitorDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorProxyField, { ProxyViaBadge } from './ui/MonitorProxyField.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

// Kontrol aralığı çubuğu duraklama noktaları (30 sn → 24 saat).
const INTERVALS = [
  { value: 30,    labelKey: 'http.iv30s' },
  { value: 60,    labelKey: 'http.iv1m'  },
  { value: 300,   labelKey: 'http.iv5m'  },
  { value: 600,   labelKey: 'http.iv10m' },
  { value: 900,   labelKey: 'http.iv15m' },
  { value: 1800,  labelKey: 'http.iv30m' },
  { value: 3600,  labelKey: 'http.iv1h'  },
  { value: 43200, labelKey: 'http.iv12h' },
  { value: 86400, labelKey: 'http.iv24h' },
]
const intervalIdx = (secs) => {
  const i = INTERVALS.findIndex(o => o.value === secs)
  if (i >= 0) return i
  let best = 0, bd = Infinity
  INTERVALS.forEach((o, j) => { const d = Math.abs(o.value - secs); if (d < bd) { bd = d; best = j } })
  return best
}
const REFRESH_INTERVAL = 60
const METHODS = ['GET', 'HEAD', 'POST']
const emptyForm = {
  name: '', url: '', method: 'GET', expectedStatus: '200-399', followRedirects: true, verifySsl: false, useProxy: 'AUTO',
  groupName: '', notificationGroupId: '', teamId: '', tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true,
  checkSslErrors: false, sslExpiryReminders: false, domainExpiryReminders: false,
  sslReminderDays: '30,14,7', domainReminderDays: '30,14,7',
  intervalSeconds: 300, timeoutMs: 10000,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true,
}

export default function HttpMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
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

  const sparks = useSparklines('http')   // kart mini trendi (2026-09-12)
  const sla = useSla('http')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
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
  const [defaults, setDefaults] = useState(null)
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
      const res = await api.monitoring.getHttpMonitors()
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
    concurrency: CHECK_CONCURRENCY_BY_TYPE.http,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'http').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da gizli sekmede durur

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.http) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili monitörün detayını aç (bir kez).
  useMonitorDeepLink(monitors, openDetail)

  function openDetail(m) { setSelected(m); setSummary({ total: 0, down: 0 }); setDetailTab('control') }
  function closeDetail() { setSelected(null) }

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', url: m.url || '', method: m.method || 'GET',
      expectedStatus: m.expected_status || '200-399', followRedirects: m.follow_redirects !== false, verifySsl: !!m.verify_ssl, useProxy: m.use_proxy || 'AUTO',
      groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', teamId: m.team_id != null ? String(m.team_id) : '', tags: m.tags || '',
      notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false,
      checkSslErrors: !!m.check_ssl_errors, sslExpiryReminders: !!m.ssl_expiry_reminders, domainExpiryReminders: !!m.domain_expiry_reminders,
      sslReminderDays: m.ssl_reminder_days || '30,14,7', domainReminderDays: m.domain_reminder_days || '30,14,7',
      intervalSeconds: m.interval_seconds ?? 300, timeoutMs: m.timeout_ms ?? 10000,
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
      const res = await api.monitoring.testHttp({
        url: normalizeUrl(form.url), method: form.method, expectedStatus: form.expectedStatus?.trim() || '200-399',
        timeoutMs: Number(form.timeoutMs), verifySsl: form.verifySsl, followRedirects: form.followRedirects, useProxy: form.useProxy || 'AUTO',
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('http.testError') })
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
        name: (form.name || form.url).trim(), url: normalizeUrl(form.url), method: form.method,
        expectedStatus: form.expectedStatus?.trim() || '200-399', followRedirects: form.followRedirects, verifySsl: form.verifySsl, useProxy: form.useProxy || 'AUTO',
        groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId), tags: form.tags?.trim() || null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING', notifyWebhook: form.notifyWebhook,
        checkSslErrors: form.checkSslErrors, sslExpiryReminders: form.sslExpiryReminders, domainExpiryReminders: form.domainExpiryReminders,
        sslReminderDays: form.sslReminderDays?.trim() || '30,14,7', domainReminderDays: form.domainReminderDays?.trim() || '30,14,7',
        intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        active: form.active,
      }
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createHttpMonitor(payload)
        : await api.monitoring.updateHttpMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      toast.success(t('http.saved')); closeEdit()
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

      confirmText: t('http.delete'),

      cancelText: t('http.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deleteHttpMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      toast.success(t('http.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deleteHttpMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('http.deleted')); closeEdit()
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerHttpCheck(m.id)
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

  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('http.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('http.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('http.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])

  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return (m.url || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q) || (m.tags || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, tagFilter, search])

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
    listKey: 'http-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, search, statFilter, pager }),
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('http.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('http.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: TriangleAlert,   label: t('http.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'error',   Icon: ServerCrash,     label: t('http.dashError'),   value: counts.error,   cls: 'error'    },
    { key: 'alarm',   Icon: Siren,           label: t('http.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('http.dashUnacked'), value: counts.unacked, cls: 'warning'  , hint: t('mondash.unackedHint') },
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
    const label = s === 'up' ? t('http.statusOk')
      : s === 'unknown' ? t('http.statusUnknown')
      : s === 'error' ? t('http.statusError') : t('http.statusDown')
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{label}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('http.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('http.noTeam'))
    : (teamName || t('http.noTeam'))

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('http.pageTitle')}</h2>
          <p className="upt-subtitle">{t('http.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('http.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('http.refresh')}
          </button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="http" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew} data-tour="mon-new">
              <Plus size={14} />{t('http.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('http.how1'), t('http.how2'), t('http.how3'), t('http.how4')]} />

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
          <input className="upt-search" type="text" placeholder={t('http.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('http.noMonitorsAdmin') : t('http.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updateHttpMonitor, remove: api.monitoring.deleteHttpMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid" data-tour="mon-cards">
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
                  <span className="upt-port-tag">{m.method || 'GET'}</span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('http', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.url}>{m.url}</div>
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
                    <span className="upt-metric-lbl">{t('http.responseMs')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('http.check')} editTitle={t('http.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('http.delete')} />
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
                checkTitle={t('http.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('http.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('http.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('http.sumOkHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('http.sumOk')}</span>
              </div>
              <div className="upt-modal-metric" title={t('http.sumTotalHint')}><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('http.sumTotal')}</span></div>
              <div className="upt-modal-metric" title={t('http.sumIncidentsHint')}><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('http.sumIncidents')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.method || 'GET'}</span><span className="upt-modal-metric-lbl">{t('http.method')}</span></div>
              {selected.http_status != null && <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.http_status}</span><span className="upt-modal-metric-lbl">HTTP</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('http.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            <div className="kw-reqinfo">
              <div className="kw-reqinfo-title">{t('http.reqSettings')}</div>
              <div className="kw-reqinfo-row"><span className="kw-reqinfo-k">{t('http.expectedStatus')}</span><span>{selected.expected_status || '200-399'}</span></div>
              <div className="kw-reqinfo-row"><span className="kw-reqinfo-k">{t('http.followRedirects')}</span>
                <span className={selected.follow_redirects !== false ? 'kw-on' : 'kw-off'}>{selected.follow_redirects !== false ? t('http.on') : t('http.off')}</span></div>
              <div className="kw-reqinfo-row"><span className="kw-reqinfo-k">{t('http.verifySsl')}</span>
                <span className={selected.verify_ssl ? 'kw-on' : 'kw-off'}>{selected.verify_ssl ? t('http.on') : t('http.off')}</span></div>
              {selected.proxy_effective && <div className="kw-reqinfo-row"><span className="kw-reqinfo-k">{t('mon.proxy.label')}</span>
                <span><ProxyViaBadge via={selected.proxy_effective} source={selected.proxy_source} bypassed={selected.proxy_bypassed} /> <span className="sys-muted">· {t(`mon.proxy.${selected.use_proxy || 'AUTO'}`)}</span></span></div>}
              <div className="kw-reqinfo-row"><span className="kw-reqinfo-k">{t('http.sslSectionTitle')}</span>
                <span>{[selected.check_ssl_errors && t('http.checkSslErrors'), selected.ssl_expiry_reminders && t('http.sslExpiryReminders'), selected.domain_expiry_reminders && t('http.domainExpiryReminders')].filter(Boolean).join(' · ') || t('http.none')}</span></div>
            </div>
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('http.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('http.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('http.tabGuide')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'control' && (
              <CheckHistoryTab kind="http" monitorId={selected.id} listKey="http-history" reloadSignal={histReload}
                columns={[t('http.colTime'), t('http.colStatus'), 'HTTP', t('http.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDateSec(c.checked_at)}</span>
                  <span className={c.ok ? 'upt-rt-up' : 'upt-rt-down'}>{c.ok ? t('http.statusOk') : (c.error ? t('http.statusError') : t('http.statusDown'))}</span>
                  <span className="upt-rt-ms">{c.http_status ?? '—'}</span>
                  {c.error
                    ? <span className="upt-rt-error" title={c.error}>{c.error}</span>
                    : <span className="upt-rt-ms">{c.response_ms != null ? `${c.response_ms} ms` : '—'}</span>}
                </>)} />
            )}

            {detailTab === 'alerts' && <AlertHistory domain={selected.url} types={alertTypesFor('http')} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="http" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="HTTP" target={selected.url} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="http" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── (dış/overlay tıklamada KAPANMAZ — veri kaybı önlenir) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()} style={{ maxWidth: 720, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--http">
              <div className="modal-icon-hdr-badge"><Globe size={20} /></div>
              <h3>{modal === 'new' ? t('http.modalNew') : t('http.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('http.testing')} /></span>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>

            {dupSource
              ? <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>
              : <div className="http-type-banner"><Globe size={16} /><span>{t('http.typeInfo')}</span></div>}

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('http.url')} <span className="req-star">*</span></span>
                <input value={form.url} placeholder="https://example.com" autoFocus={!!dupSource}
                  onChange={e => setForm(f => ({ ...f, url: e.target.value }))}
                  onBlur={e => { const n = normalizeUrl(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, url: n })) }} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('http.urlHint')}</div>

              <label><span>{t('http.method')}</span>
                <SearchableSelect value={form.method} onChange={v => setForm(f => ({ ...f, method: v }))}
                  options={METHODS.map(x => ({ value: x, label: x }))} /></label>
              <label><span>{t('http.expectedStatus')}</span>
                <input value={form.expectedStatus} placeholder="200, 2xx, 200-399" onChange={e => setForm(f => ({ ...f, expectedStatus: e.target.value }))} /></label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.followRedirects} onChange={e => setForm(f => ({ ...f, followRedirects: e.target.checked }))} />{t('http.followRedirects')}</label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.verifySsl} onChange={e => setForm(f => ({ ...f, verifySsl: e.target.checked }))} />{t('http.verifySsl')}</label>
              {/* Kurumsal vekil (2026-09-21): sertifika envanteriyle aynı karar; düzenlemede etkin sonuç ipucu */}
              <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
                effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />

              <label><span>{t('http.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('http.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('http.noTeam')} disabled />}</label>

              <label className="full-width"><span>{t('http.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('http.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('http.noGroup')} /></label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
                onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('http.groupInfo')}</div>

              {/* Etiketler */}
              <div className="full-width http-tags-block">
                <div className="http-block-title">{t('http.tagsTitle')} <span className="req-star">*</span></div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('http.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('http.tagsPlaceholder')} />
              </div>



              {/* SSL + Domain kontrolleri */}
              <div className="full-width http-ssl-section">
                <div className="http-block-title"><ShieldCheck size={15} style={{ verticalAlign: '-2px', marginRight: 5 }} />{t('http.sslSectionTitle')}</div>
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.checkSslErrors} onChange={e => setForm(f => ({ ...f, checkSslErrors: e.target.checked }))} />{t('http.checkSslErrors')}</label>
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.sslExpiryReminders} onChange={e => setForm(f => ({ ...f, sslExpiryReminders: e.target.checked }))} />{t('http.sslExpiryReminders')}</label>
                {form.sslExpiryReminders && (
                  <div className="http-days-row">
                    <span>{t('http.reminderDays')}</span>
                    <input value={form.sslReminderDays} placeholder="30,14,7" onChange={e => setForm(f => ({ ...f, sslReminderDays: e.target.value }))} />
                  </div>
                )}
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.domainExpiryReminders} onChange={e => setForm(f => ({ ...f, domainExpiryReminders: e.target.checked }))} />{t('http.domainExpiryReminders')}</label>
                {form.domainExpiryReminders && (
                  <div className="http-days-row">
                    <span>{t('http.reminderDays')}</span>
                    <input value={form.domainReminderDays} placeholder="30,14,7" onChange={e => setForm(f => ({ ...f, domainReminderDays: e.target.value }))} />
                  </div>
                )}
                <div className="field-hint">{t('http.whoisHint')}</div>
              </div>

              {/* Alarm hassasiyeti */}
              <label><span>{t('http.confirmAttempts')}</span>
                <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
              <label><span>{t('http.confirmInterval')}</span>
                <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
              <label><span>{t('http.recoveryChecks')}</span>
                <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
              <label><span>{t('http.recoveryInterval')}</span>
                <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
              <label><span>{t('http.timeout')}</span>
                <input type="number" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('http.active')}</label>
              <div className="full-width" style={{ fontSize: '.8em', color: 'var(--text-muted)', marginTop: -2, lineHeight: 1.5 }}>
                ⓘ {t('http.confirmHint')}
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
                    ? <><strong>{t('http.testError')}:</strong> {testResult.error}</>
                    : <>
                        <strong>{testResult.condition_met ? t('http.testMet') : t('http.testNotMet')}</strong>
                        {testResult.http_status != null && <> — HTTP {testResult.http_status}</>}
                        {testResult.response_ms != null && <> · {testResult.response_ms}ms</>}
                        {testResult.expected_status && <> · {t('http.expectedStatus')}: {testResult.expected_status}</>}
                        {testResult.via && <> · {testResult.via === 'proxy' ? t('mon.proxy.effProxy') : t('mon.proxy.effDirect')}</>}
                      </>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="http-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                aria-busy={testing || undefined} disabled={testing || !form.url.trim()}>
                <FlaskConical size={14} />{t('http.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('http.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('http.cancel')}</button>
              <button className="btn btn-primary" onClick={save} aria-busy={saving || undefined} disabled={saving || !form.url.trim() || !form.teamId}>{t('http.save')}</button>
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
          storageKey="sm.checkRun.teams.http"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="http"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
