import { LoadingBlock } from './ui/Progress.jsx'
import { formatPercent } from '../i18n/dateLocale.js'
import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { CheckRunningStrip } from './ui/CheckRunning.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import TagInput from './ui/TagInput.jsx'
import { RefreshCw, Plug, Plus, Trash2, FlaskConical, AlertTriangle, Network, Check, X, Pause, ChevronDown, BellDot } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
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
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const INTERVALS = [
  { value: 30,    labelKey: 'port.iv30s' },
  { value: 60,    labelKey: 'port.iv1m'  },
  { value: 300,   labelKey: 'port.iv5m'  },
  { value: 600,   labelKey: 'port.iv10m' },
  { value: 900,   labelKey: 'port.iv15m' },
  { value: 1800,  labelKey: 'port.iv30m' },
  { value: 3600,  labelKey: 'port.iv1h'  },
  { value: 43200, labelKey: 'port.iv12h' },
  { value: 86400, labelKey: 'port.iv24h' },
]
const intervalIdx = (secs) => {
  const i = INTERVALS.findIndex(o => o.value === secs)
  if (i >= 0) return i
  let best = 0, bd = Infinity
  INTERVALS.forEach((o, j) => { const d = Math.abs(o.value - secs); if (d < bd) { bd = d; best = j } })
  return best
}

const REFRESH_INTERVAL = 60
const PORT_TYPES = ['TCP', 'TLS', 'HTTP', 'BANNER', 'UDP']
const emptyForm = { name: '', host: '', port: '', protocol: 'TCP', expect: '', sendData: '', teamId: '', groupName: '', notificationGroupId: '',
  tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true, ipVersion: 'auto', slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 300, timeoutMs: 5000,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true }

export default function PortMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin                          // ekle/düzenle/sil butonu (takım-kapsamlı)
  const myTeam = teamId != null ? String(teamId) : null
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId })
  const canManageRow = (m) => isAdmin || isOwnTeam(m)              // düzenle + kontrol (otomatik :443/team_id=null → yalnız admin)
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  const canCheckRow = canManageRow
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('port')   // kart mini trendi (2026-09-12)
  const sla = useSla('port')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [defaults, setDefaults] = useState(null)
  const [selected, setSelected] = useState(null)
  useEscapeKey(!!selected, closeModal)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [detailTab, setDetailTab] = useState('control')
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [advOpen, setAdvOpen] = useState(false)               // "Gelişmiş ayarlar" accordion
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [deleting, setDeleting] = useState(null)   // cift-tik korumasi (DNS ikizi)
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
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
      const res = await api.monitoring.getPortMonitors()
      if (res?.success) { setMonitors(res.data); setLoadError(null) }
      else setLoadError(res?.error || 'load failed')
    } catch (e) {
      setLoadError(e?.message || 'network error')
    } finally {
      setLoading(false)
      setSecondsSince(0)
    }
  }, [])

  const checkable = monitors.filter(canCheckRow)
  const checkRun = useCheckRun({
    items: checkable,
    // Tekil yolun ta kendisi: kartın "kontrol ediliyor" göstergesi (track) ve sonucun
    // satıra işlenmesi toplu koşumda da AYNI koddan geçer — ikinci bir merge yolu yok.
    runOne: checkNow,
    concurrency: CHECK_CONCURRENCY_BY_TYPE.port,
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
    api.monitoring.listGroups(form.teamId, 'port').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da gizli sekmede durur

  // Takım atama seçici yalnız admin'e — takımları bir kez yükle.
  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.port) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili port monitörünün detayını aç (bir kez), paramı temizle.
  useMonitorDeepLink(monitors, openModal)

  // Modal her açıldığında önceki kaydetme hatası + test sonucunu temizle.
  useEffect(() => { setSaveError(null); setTestResult(null) }, [modal])

  async function openModal(m) {
    setSelected(m)
    setSummary({ total: 0, down: 0 })
    setDetailTab('control')
  }

  function closeModal() { setSelected(null) }

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      slowThresholdMs: defaults?.slowThresholdMs ?? emptyForm.slowThresholdMs })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    // Envanter-türevi monitörde team_id null olabilir; liste team_name'i (domain→takım) gösterir →
    // edit'te o takımı önseç (aksi halde "takımsız" görünür), team_name'i teams'ten eşleştirerek.
    const derivedTeam = m.team_id == null && m.team_name ? teams.find(tm => tm.name === m.team_name) : null
    return { name: m.name || '', host: m.host || '', port: m.port ?? '', protocol: m.protocol || 'TCP',
      expect: m.expect || '', sendData: m.send_data || '',
      teamId: m.team_id != null ? String(m.team_id) : (derivedTeam ? String(derivedTeam.id) : ''), groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
      tags: m.tags || '', notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING', notifyWebhook: m.notify_webhook !== false, ipVersion: m.ip_version || 'auto',
      slowResponseEnabled: !!m.slow_response_enabled, slowThresholdMs: m.slow_threshold_ms ?? 3000,
      intervalSeconds: m.interval_seconds ?? 300, timeoutMs: m.timeout_ms ?? 5000,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false }
  }
  function openEdit(m) {
    setDupSource(null)
    setForm(formFrom(m))
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız host alanını değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.host) })
    setModal('new')
  }
  function closeEdit() { setModal(null); setDupSource(null); setChangeNote('') }

  async function save() {
    if (!form.host.trim() || !form.port) { setSaveError(t('port.hostRequired')); return }
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName?.trim()) { toast.error(t('mon.groupRequired')); return }   // grup + etiket zorunlu (2026-09-18)
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }
    setSaving(true); setSaveError(null)
    try {
      const payload = {
        name: (form.name || form.host).trim(), host: form.host.trim(), port: Number(form.port),
        protocol: form.protocol?.trim() || 'TCP',
        expect: form.expect?.trim() || null, sendData: form.sendData || null,
        teamId: form.teamId === '' ? null : Number(form.teamId), groupName: form.groupName?.trim() || null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING', notifyWebhook: form.notifyWebhook, ipVersion: form.ipVersion,
        slowResponseEnabled: form.slowResponseEnabled, slowThresholdMs: Number(form.slowThresholdMs),
        intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        active: form.active,
        // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
        ...(changeNote.trim() ? { changeNote: changeNote.trim() } : {}),
      }
      const res = modal === 'new'
        ? await api.monitoring.createPortMonitor(payload)
        : await api.monitoring.updatePortMonitor(modal.id, payload)
      setSaving(false)
      if (!res?.success) { setSaveError(res?.error || t('port.saveError')); return }
      toast.success(t('port.saved'))
      // Envanter bagi koptuysa kullaniciyi bilgilendir: duzenleme kalici, envanter domain'i
      // icin AYRI bir izleme surecek (bkz. MonitoringController.detachIfIdentityChanged).
      if (res.data?.detached_from_inventory) toast.info(t('mon.detachedFromInventory'), 8000)
      await load(); closeEdit()
    } finally {
      setSaving(false)
    }
  }

  // Kaydetmeden formdaki ayarlarla bir kez kontrol eder: ne döndü (HTTP durum/banner/TLS) + alarm koşulu sağlandı mı.
  async function runTest() {
    if (!form.host.trim() || !form.port) { setSaveError(t('port.hostRequired')); return }
    setTesting(true); setTestResult(null); setSaveError(null)
    try {
      const res = await api.monitoring.testPortMonitor({
        host: form.host.trim(), port: Number(form.port), protocol: form.protocol,
        expect: form.expect?.trim() || null, sendData: form.sendData || null, timeoutMs: Number(form.timeoutMs),
        ipVersion: form.ipVersion,
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('port.testError') })
    } finally {
      setTesting(false)
    }
  }

  /**
   * Silme — KARTTAN (satir) ya da duzenleme modalinden cagrilir; hedef her zaman ACIK bir
   * argumandir. {@code onClick={deleteMonitor}} bicimde BAGLANMAZ: React olay nesnesini ilk
   * arguman olarak gecirir ve hedef sessizce yanlis olurdu. (DnsMonitorPage ile ayni imza.)
   */
  async function deleteMonitor(m) {
    if (!m || m === 'new') return
    // Onay projenin diyaloğuyla alınır. `window.confirm` tarayıcı-varsayılanı bir kutu
    // çiziyordu (tasarım sistemi dışı) ve hedefin adını göstermiyordu; kart üzerindeki
    // tek tık yıkıcı bir işlem tetiklediği için mesaj NEYİN silineceğini söylemeli.
    const ok = await showConfirm({
      title: t('mon.deleteTitle'),
      message: t('mon.deleteMsg', m.name || (m.host + ":" + m.port)),
      confirmText: t('port.delete'),
      cancelText: t('port.cancel'),
      variant: 'danger',
    })
    if (!ok) return
    setDeleting(m.id)
    try {
      const res = await api.monitoring.deletePortMonitor(m.id)
      setDeleting(null)
      // HATA TOAST ile bildirilir: saveError YALNIZ duzenleme modalinin icinde ciziliyor,
      // karttan silerken modal KAPALI oldugu icin 403/409 sessizce yutuluyordu — kullanici
      // silindi saniyordu. DnsMonitorPage ikiziyle ayni desen.
      if (!res?.success) { toast.error(res?.error || t('port.saveError')); return }
      toast.success(t('port.deleted'))
      await load()
      if (modal) closeEdit()
    } finally {
      setDeleting(null)
    }
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerPortCheck(m.id)
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

  // Takım filtresi seçenekleri — listeden türetilir (dashboard deseni).
  const teamOptions = (() => {
    const names = new Set()
    let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  })()
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')

  // Modal seçicileri: takım (admin → tüm takımlar) + grup (mevcut gruplardan, yeni grup oluşturulabilir).
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('app.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])
  const groupNames = useMemo(
    () => [...new Set(monitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)), [monitors])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])
  const hasGroupOptions = groupNames.length > 0
  // Etiket filtresi: grupla aynı sözleşme ('all' / '__none__' / etiket). Seçenekler listedeki etiketlerden türer.
  const tagNames = useMemo(() => tagNamesOf(monitors), [monitors])
  // Kutu etiketsiz izleme varken de görünür: "Etiketsiz" seçeneği eski (etiketsiz) kayıtları bulmanın yolu.
  const hasTagOptions = tagNames.length > 0 || monitors.some(m => !(m.tags || '').trim())
  const tagFilterOptions = useMemo(() => [{ value: 'all', label: t('mon.allTags') },
    ...tagNames.map(x => ({ value: x, label: x })),
    ...(monitors.some(m => !(m.tags || '').trim()) ? [{ value: '__none__', label: t('mon.noTags') }] : [])],
    [tagNames, monitors, t])
  const groupFilterOptions = [{ value: 'all', label: t('port.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(monitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('port.noGroup') }] : [])]

  // Takım + grup + arama kapsamı — istatistik kartlarının TABANI. statFilter BİLEREK dahil değil:
  // kartlar aynı zamanda filtre düğmesi, statFilter'a göre sayılsalardı seçili olmayan her kart 0
  // okur ve tıklanamaz hale gelirdi. (Kartlar ham `monitors`'dan sayılıyordu: kullanıcı bir takım
  // seçince liste daralıyor ama kartlar küresel sayıyı göstermeye devam ediyordu. HttpMonitorPage deseni.)
  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    return m.host.toLowerCase().includes(search.trim().toLowerCase())
  }), [monitors, teamFilter, groupFilter, tagFilter, search])

  const portCounts = useMemo(() => ({
    total: scoped.length,
    up: scoped.filter(m => m.status === 'open').length,
    down: scoped.filter(m => m.status === 'closed').length,
    alarm: scoped.filter(m => m.active_alarm).length,
    unacked: scoped.filter(m => m.active_alarm && !m.alarm_acknowledged).length,
    paused: scoped.filter(m => m.active === false).length,
  }), [scoped])
  const statItems = [
    { key: 'total',   Icon: Network,       label: t('port.statTotal'),    value: portCounts.total,   cls: 'total'    },
    { key: 'up',      Icon: Check,         label: t('port.statUp'),       value: portCounts.up,      cls: 'valid'    },
    { key: 'down',    Icon: X,             label: t('port.statDown'),     value: portCounts.down,    cls: 'critical' },
    { key: 'alarm',   Icon: AlertTriangle, label: t('port.statAlarm'),    value: portCounts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,       label: t('port.statUnacked'),  value: portCounts.unacked, cls: 'warning'  },
    { key: 'paused',  Icon: Pause,         label: t('port.statPaused'),   value: portCounts.paused,  cls: 'paused'   },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  // Geçmiş sayfalaması (DNS ile aynı 50/100/200)
  // Geçmiş modalı sayfalaması — 30 sn modal yenilemesi history referansını değiştirir; sayfa korunur.

  // Listelenen küme = kapsam + kart filtresi (takım/grup/arama zaten `scoped`'ta uygulandı).
  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    return scoped.filter(m => {
      if (statFilter === 'up' && m.status !== 'open') return false
      if (statFilter === 'down' && m.status !== 'closed') return false
      if (statFilter === 'alarm' && !m.active_alarm) return false
      if (statFilter === 'unacked' && !(m.active_alarm && !m.alarm_acknowledged)) return false
      if (statFilter === 'paused' && m.active !== false) return false
      return true
    })
  }, [scoped, statFilter])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  const pager = usePagination(displayMonitors, {
    listKey: 'port-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
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

  /** Kart durum sınıfı — Port'ta {@code status} open/closed/unknown değerini taşır. */
  function cardClass(m) {
    if (m.active === false) return 'upt-card--unknown'
    if (m.status === 'open') return 'upt-card--up'
    if (m.status === 'closed') return 'upt-card--down'
    return 'upt-card--unknown'
  }

  function statusBadge(status) {
    const cls = status === 'open' ? 'upt-badge--up' : status === 'closed' ? 'upt-badge--down' : 'upt-badge--unknown'
    const label = status === 'open' ? t('port.statusOpen') : status === 'closed' ? t('port.statusClosed') : t('port.statusUnknown')
    return (
      <span className={`upt-badge ${cls}`}>
        <span className="upt-badge-dot" />
        {label}
      </span>
    )
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('port.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (teamName || t('app.noTeam'))

  return (
    <div className="mon-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('port.title')}</h2>
          <p className="upt-subtitle">{t('port.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('port.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('port.refresh')}
          </button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="port" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('port.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('port.how1'), t('port.how2'), t('port.how3')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', marginBottom: '14px', gap: 8 }}>
          {hasTeamOptions && (
            <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
          )}
          {hasGroupOptions && (
            <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />
          )}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} />}
          <input className="upt-search" type="text"
            placeholder={t('port.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <div className="mon-empty">{t('port.noMonitors')}</div>
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updatePortMonitor, remove: api.monitoring.deletePortMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id}
              className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openModal(m)}>
              <div className="upt-card-top">
                {canManageRow(m) && (
                  <input type="checkbox" className="upt-card-check" checked={bulkSel.has(m.id)} onChange={() => toggleBulk(m.id)} onClick={(e) => e.stopPropagation()} aria-label={t('bulk.selectOne')} />
                )}
                {statusBadge(m.status)}
                {alarmBadge(m)}<MaintenanceBadge target={m.host} />
                <span className="upt-card-top-right">
                  <span className="upt-port-tag">:{m.port} {m.protocol || 'TCP'}</span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('port', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              {/* Baslik YALNIZ host: port ust-sag rozette ve olcum satirinda zaten var.
              Host'u ":" ile bolmek onu tek bir metin dugumu olmaktan cikariyordu. */}
              <div className="upt-card-domain" title={`${m.host}:${m.port}`}>{m.host}</div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.port}</span>
                  <span className="upt-metric-lbl">{t('port.port')}</span>
                </div>
                {m.response_ms != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{m.response_ms}ms</span>
                    <span className="upt-metric-lbl">{t('port.colResponse')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDate(m.checked_at) : ''}</span>
                {/* Sinifsiz sarmalayici: MonitorCardActions kendi kokunu zaten
                    "mon-actions" yapiyor; ayni sinifi ic ice uygulamak gap/margin'i iki
                    kez sayip ScriptedMonitorPage'den farkli bir bosluk uretiyordu. */}
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('port.check')} editTitle={t('port.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('port.delete')} />
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
        <div className="upt-modal-overlay" onClick={closeModal}>
          <div className={`upt-modal upt-modal--${selected.status === 'open' ? 'up' : selected.status === 'closed' ? 'down' : 'unknown'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected.status)}
                <span className="upt-modal-domain">{selected.host}</span>
                <span className="upt-port-tag">:{selected.port}</span>
              </div>
              {/* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
                  koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
                  kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */}
              <MonitorModalActions
                running={isRunning(selected.id)}
                onCheck={canManageRow(selected) ? () => checkNow(selected) : undefined}
                checkTitle={t('port.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('port.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('port.delete')}
                onClose={closeModal}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('port.sumUptimeHint')}>
                <span className="upt-modal-metric-val">
                  {summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—'}
                </span>
                <span className="upt-modal-metric-lbl">{t('port.sumUptime')}</span>
              </div>
              <div className="upt-modal-metric" title={t('port.sumTotalHint')}>
                <span className="upt-modal-metric-val">{summary.total}</span>
                <span className="upt-modal-metric-lbl">{t('port.sumTotal')}</span>
              </div>
              <div className="upt-modal-metric" title={t('port.sumIncidentsHint')}>
                <span className="upt-modal-metric-val">{summary.down}</span>
                <span className="upt-modal-metric-lbl">{t('port.sumIncidents')}</span>
              </div>
              {selected.response_ms != null && (
                <div className="upt-modal-metric" title={t('port.responseMsHint')}>
                  <span className="upt-modal-metric-val">{selected.response_ms}ms</span>
                  <span className="upt-modal-metric-lbl">{t('port.responseMs')}</span>
                </div>
              )}
              {selected.protocol && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.protocol}</span>
                  <span className="upt-modal-metric-lbl">{t('port.protocol')}</span>
                </div>
              )}
              {selected.interval_seconds != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{selected.interval_seconds}s</span>
                  <span className="upt-modal-metric-lbl">{t('port.intervalLbl')}</span>
                </div>
              )}
              {selected.timeout_ms != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{selected.timeout_ms}ms</span>
                  <span className="upt-modal-metric-lbl">{t('port.timeoutLbl')}</span>
                </div>
              )}
              {selected.checked_at && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.checked_at)}</span>
                  <span className="upt-modal-metric-lbl">{t('port.lastCheck')}</span>
                </div>
              )}
            </div>
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('port.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('port.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('port.tabGuide')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'control' && (
              <CheckHistoryTab kind="port" monitorId={selected.id} listKey="port-history" reloadSignal={histReload}
                columns={[t('port.colTime'), t('port.colStatus'), t('port.colResponse'), t('port.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                  <span className={c.open ? 'upt-rt-up' : 'upt-rt-down'}>
                    {c.open ? t('port.statusOpen') : t('port.statusClosed')}
                  </span>
                  <span className="upt-rt-ms">{c.response_ms != null ? `${c.response_ms}ms` : '—'}</span>
                  {c.error
                    ? <span className="upt-rt-error">{c.error}</span>
                    : c.open
                      ? <span className="upt-rt-up">{t('port.detailOk')}</span>
                      : <span className="upt-rt-ms">—</span>}
                </>)} />
            )}

            {detailTab === 'alerts' && <AlertHistory domain={selected.host} types={alertTypesFor('port')} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="port" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PORT" target={`${selected.host}:${selected.port}`} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="port" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── (overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()} style={{ maxWidth: 640, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Plug size={20} /></div>
              <h3>{modal === 'new' ? t('port.modalAdd') : t('port.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('port.testing')} /></span>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>

            {dupSource
              ? <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>
              : <div className="port-type-banner"><Plug size={16} /><span>{t('port.typeInfo')}</span></div>}

            <div className="form-grid form-grid--top">
              <label><span>{t('port.host')} <span className="req-star">*</span></span>
                <input value={form.host} placeholder="1.2.3.4 / host.example.com" autoFocus={!!dupSource}
                  onChange={e => setForm(f => ({ ...f, host: e.target.value }))} /></label>
              <label><span>{t('port.port')} <span className="req-star">*</span></span>
                <input type="number" min="1" max="65535" value={form.port}
                  onChange={e => setForm(f => ({ ...f, port: e.target.value }))} /></label>
              <label><span>{t('port.name')}</span>
                <input value={form.name} placeholder={form.host}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('port.checkType')}</span>
                <SearchableSelect value={form.protocol} onChange={v => setForm(f => ({ ...f, protocol: v }))}
                  options={PORT_TYPES.map(v => ({ value: v, label: t(`port.type.${v}`) }))} /></label>
              {(form.protocol === 'HTTP' || form.protocol === 'BANNER' || form.protocol === 'UDP') && (
                <label><span>{form.protocol === 'HTTP' ? t('port.pathLabel') : t('port.sendLabel')}</span>
                  <input value={form.sendData} placeholder={form.protocol === 'HTTP' ? '/health' : ''}
                    onChange={e => setForm(f => ({ ...f, sendData: e.target.value }))} /></label>
              )}
              {(form.protocol === 'HTTP' || form.protocol === 'BANNER') && (
                <label><span>{form.protocol === 'HTTP' ? t('port.expectStatus') : t('port.expectResp')}</span>
                  <input value={form.expect} placeholder={form.protocol === 'HTTP' ? '200, 2xx, 200-399' : '220, +OK, SSH-2.0'}
                    onChange={e => setForm(f => ({ ...f, expect: e.target.value }))} /></label>
              )}
              {(form.protocol === 'HTTP' || form.protocol === 'BANNER' || form.protocol === 'UDP') && (
                <div className="full-width" style={{ fontSize: '.78em', color: 'var(--text-muted)', marginTop: -2, lineHeight: 1.5 }}>
                  ⓘ {t(`port.typeHint.${form.protocol}`)}
                </div>
              )}
              <label><span>{t('port.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('app.noTeam')} disabled />}</label>
              <label><span>{t('port.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('port.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('port.noGroup')} /></label>
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
              <div className="full-width port-tags-block">
                <div className="port-block-title">{t('port.tagsTitle')} <span className="req-star">*</span></div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('port.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('port.tagsPlaceholder')} suggestions={teamTags} />
              </div>



              {/* IP sürümü */}
              <label><span>{t('port.ipVersion')}</span>
                <SearchableSelect value={form.ipVersion} onChange={v => setForm(f => ({ ...f, ipVersion: v }))}
                  options={[{ value: 'auto', label: t('port.ipAuto') }, { value: 'v4', label: 'IPv4' }, { value: 'v6', label: 'IPv6' }]} /></label>

              {/* Gelişmiş ayarlar — açılır/kapanır */}
              <div className="full-width port-adv">
                <button type="button" className="port-adv-toggle" onClick={() => setAdvOpen(o => !o)}>
                  <ChevronDown size={16} className={`port-adv-chevron${advOpen ? ' open' : ''}`} />
                  <span>{t('port.advanced')}</span>
                </button>
                {advOpen && (
                  <div className="port-adv-body">
                    <div className="port-block-title">{t('port.timeoutTitle')}</div>
                    <div className="field-hint" style={{ marginBottom: 8 }}>{t('port.timeoutEvery').replace('{0}', Math.min(60, Math.max(1, Math.round(Number(form.timeoutMs) / 1000))))}</div>
                    <input type="range" className="port-interval-slider" min={1} max={60} step={1}
                      value={Math.min(60, Math.max(1, Math.round(Number(form.timeoutMs) / 1000)))}
                      onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) * 1000 }))} />
                    <label className="checkbox-label" style={{ marginTop: 12 }}>
                      <input type="checkbox" checked={form.slowResponseEnabled} onChange={e => setForm(f => ({ ...f, slowResponseEnabled: e.target.checked }))} />{t('port.slowEnable')}</label>
                    {form.slowResponseEnabled && (
                      <div className="port-days-row">
                        <span>{t('port.slowThreshold')}</span>
                        <input type="number" min="100" step="100" value={form.slowThresholdMs} onChange={e => setForm(f => ({ ...f, slowThresholdMs: Number(e.target.value) }))} />
                      </div>
                    )}
                    <div className="field-hint" style={{ marginBottom: 4 }}>{t('port.slowHint')}</div>
                    <div className="port-adv-grid">
                      <label><span>{t('port.confirmAttempts')}</span>
                        <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
                      <label><span>{t('port.confirmInterval')}</span>
                        <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
                      <label><span>{t('port.recoveryChecks')}</span>
                        <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
                      <label><span>{t('port.recoveryInterval')}</span>
                        <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
                    </div>
                    <label className="checkbox-label" style={{ marginTop: 10 }}>
                      <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('port.active')}</label>
                    <div className="field-hint" style={{ marginTop: 6 }}>ⓘ {t('port.confirmHint')}</div>
                  </div>
                )}
              </div>
            </div>
            {testResult && (testResult.open === undefined && testResult.error
              ? <div className="mon-modal-error">{testResult.error}</div>
              : (
                <div className={`port-test-result ${testResult.open ? 'ptr-ok' : 'ptr-fail'}`}>
                  <div className="ptr-head">
                    {testResult.open ? '✓ ' + t('port.testPass') : '✕ ' + t('port.testNoPass')}
                    {testResult.response_ms != null && <span className="ptr-ms"> · {testResult.response_ms} ms</span>}
                  </div>
                  {testResult.detail && <div className="ptr-row">{t('port.testReturned')}: <b>{testResult.detail}</b></div>}
                  {testResult.error && <div className="ptr-row ptr-err">{testResult.error}</div>}
                  <div className="ptr-note">{testResult.open ? t('port.testNoteOk') : t('port.testNoteFail')}</div>
                </div>
              ))}
            {saveError && <div className="mon-modal-error">{saveError}</div>}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı.
                Form ızgarasının DIŞINDA, eylem çubuğunun hemen üstünde: sekiz izleme
                sayfasında da aynı yerde dursun (ızgaraların iç düzeni sayfadan sayfaya değişiyor). */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="port-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <div style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
                <button className="btn btn-secondary" onClick={runTest} aria-busy={testing || undefined} disabled={testing || !form.host.trim() || !form.port}>
                  <FlaskConical size={14} />{t('port.test')}
                </button>
                {modal !== 'new' && canDeleteRow(modal) && (
                  <button className="btn btn-danger" onClick={() => deleteMonitor(modal)}><Trash2 size={14} />{t('port.delete')}</button>
                )}
              </div>
              <button className="btn btn-secondary" onClick={closeEdit}>{t('port.cancel')}</button>
              <button className="btn btn-primary" onClick={save} aria-busy={saving || undefined} disabled={saving || !form.host.trim() || !form.port || !form.teamId}>{t('port.save')}</button>
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
          storageKey="sm.checkRun.teams.port"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="port"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
