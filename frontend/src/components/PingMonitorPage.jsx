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
import TagInput from './ui/TagInput.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import { RefreshCw, Plus, Trash2, Radio, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, WifiOff, Siren, BellDot, PauseCircle, Inbox } from 'lucide-react'
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
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin.
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
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

// Ortak kaydirma cubugu seti (bkz. IntervalSlider). Ping tek ICMP paketi kadar ucuz,
// taban 30 sn kalir; ust sinir digerleriyle hizalandi.
const INTERVALS = [
  { value: 30,    labelKey: 'notify.iv30s' },
  { value: 60,    labelKey: 'notify.iv1m'  },
  { value: 300,   labelKey: 'notify.iv5m'  },
  { value: 900,   labelKey: 'notify.iv15m' },
  { value: 1800,  labelKey: 'notify.iv30m' },
  { value: 3600,  labelKey: 'notify.iv1h'  },
  { value: 43200, labelKey: 'notify.iv12h' },
  { value: 86400, labelKey: 'notify.iv24h' },
]
const REFRESH_INTERVAL = 60
const emptyForm = { name: '', host: '', ipVersion: 'auto', groupName: '', tags: '', notificationGroupId: '', teamId: '',
  intervalSeconds: 60, timeoutMs: 5000, packetCount: 4, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true, active: true,
  // Yavaşlık alarmı OPT-IN: varsayılan kapalı — mevcut izlemelerin hiçbiri bir gün sabah
  // birden yeni bir alarm türü üretmeye başlamasın.
  slowResponseEnabled: false, slowBaselineWindowMinutes: 10, slowThresholdPercent: 20 }

export default function PingMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  // Ortak bildirim blogunun hedef satiri icin takim adi (HttpMonitorPage deseni).
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

  const sparks = useSparklines('ping')   // kart mini trendi (2026-09-12)
  const sla = useSla('ping')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  useEscapeKey(!!selected, closeDetail)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (teamName || t('app.noTeam'))
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [defaults, setDefaults] = useState(null)   // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [testing, setTesting] = useState(false)
  const [deleting, setDeleting] = useState(null)   // satir bazli cift-tik korumasi
  const [testResult, setTestResult] = useState(null)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const [detailTab, setDetailTab] = useState('control')
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)

  // Modal her açıldığında/değiştiğinde önceki test sonucunu temizle.
  useEffect(() => { setTestResult(null) }, [modal])

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'ping').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) })
    return () => { alive = false }
  }, [modal, form.teamId])

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
      const res = await api.monitoring.getPingMonitors()
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
    concurrency: CHECK_CONCURRENCY_BY_TYPE.ping,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da durur

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.ping) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili monitörün detayını aç (bir kez).
  useMonitorDeepLink(monitors, openDetail)

  function openDetail(m) { setSelected(m); setSummary({ total: 0, down: 0 }); setDetailTab('control') }
  function closeDetail() { setSelected(null) }

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', host: m.host || '', ipVersion: m.ip_version || 'auto', groupName: m.group_name || '', tags: m.tags || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
      teamId: m.team_id != null ? String(m.team_id) : '', intervalSeconds: m.interval_seconds ?? 60,
      notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING',
      timeoutMs: m.timeout_ms ?? 5000, packetCount: m.packet_count ?? 4,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30, recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      notifyWebhook: m.notify_webhook !== false, active: m.active !== false,
      slowResponseEnabled: !!m.slow_response_enabled,
      slowBaselineWindowMinutes: m.slow_baseline_window_minutes ?? 10,
      slowThresholdPercent: m.slow_threshold_percent ?? 20 }
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
    if (!form.host.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName?.trim()) { toast.error(t('mon.groupRequired')); return }   // grup + etiket zorunlu (2026-09-18)
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }
    setSaving(true)
    try {
      const payload = {
        name: (form.name || form.host).trim(), host: form.host.trim(), ipVersion: form.ipVersion,
        groupName: form.groupName?.trim() || null, tags: form.tags?.trim() || null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        teamId: form.teamId === '' ? null : Number(form.teamId), intervalSeconds: Number(form.intervalSeconds),
        notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING',
        timeoutMs: Number(form.timeoutMs), packetCount: Number(form.packetCount),
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds), recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        notifyWebhook: !!form.notifyWebhook, active: form.active,
        slowResponseEnabled: !!form.slowResponseEnabled,
        slowBaselineWindowMinutes: Number(form.slowBaselineWindowMinutes),
        slowThresholdPercent: Number(form.slowThresholdPercent),
      }
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createPingMonitor(payload)
        : await api.monitoring.updatePingMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      toast.success(t('ping.saved')); closeEdit()
    } finally {
      setSaving(false)
    }
  }

  // Kaydetmeden formdaki host/parametrelerle bir kez ping atar; ping atılabildi mi + koşul (erişilebilirlik) sağlandı mı.
  async function runTest() {
    if (!form.host.trim()) return
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testPingMonitor({
        host: form.host.trim(), ipVersion: form.ipVersion,
        packetCount: Number(form.packetCount), timeoutMs: Number(form.timeoutMs),
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('ping.testError') })
    } finally {
      setTesting(false)
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

      message: t('mon.deleteMsg', m.name || m.host),

      confirmText: t('ping.delete'),

      cancelText: t('ping.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deletePingMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      toast.success(t('ping.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deletePingMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('ping.deleted')); closeEdit()
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerPingCheck(m.id)
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

  // Türetilmiş listeler memoize — 1sn countdown her saniye render tetikler.
  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('ping.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('ping.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('ping.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])

  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    return (m.host || '').toLowerCase().includes(search.trim().toLowerCase())
  }), [monitors, teamFilter, groupFilter, tagFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, up: 0, down: 0, alarm: 0, unacked: 0, paused: 0 }
    for (const m of scoped) {
      if (m.status === 'up') c.up++
      else if (m.status === 'down') c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
      if (m.active === false) c.paused++
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      up:      m => m.status === 'up',
      down:    m => m.status === 'down',
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
    listKey: 'ping-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
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
    { key: 'total',   Icon: LayoutDashboard, label: t('ping.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('ping.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: WifiOff,         label: t('ping.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'alarm',   Icon: Siren,           label: t('ping.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('ping.dashUnacked'), value: counts.unacked, cls: 'warning'  , hint: t('mondash.unackedHint') },
    { key: 'paused',  Icon: PauseCircle,     label: t('ping.dashPaused'),  value: counts.paused,  cls: 'paused'   },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)

  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  function cardClass(m) {
    if (m.status === 'up') return 'upt-card--up'
    if (m.status === 'down') return 'upt-card--down'
    return 'upt-card--unknown'
  }
  function statusBadge(m) {
    const s = m?.status
    const cls = s === 'up' ? 'upt-badge--up' : s === 'down' ? 'upt-badge--down' : 'upt-badge--unknown'
    const label = s === 'up' ? t('ping.statusUp') : s === 'down' ? t('ping.statusDown')
      : s === 'na' ? t('ping.statusNa') : t('ping.statusUnknown')
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{label}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('ping.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  // Aynı host + takım için mevcut monitör (kendisi hariç) → mükerrer engelleme uyarısı
  const dupHost = (() => {
    const h = (form.host || '').trim().toLowerCase()
    if (!h) return null
    const targetTeam = (form.teamId === '' || form.teamId == null) ? null : Number(form.teamId)
    const editingId = (modal && modal !== 'new') ? modal.id : null
    return monitors.find(m => m.id !== editingId
      && (m.host || '').trim().toLowerCase() === h
      && (m.team_id ?? null) === targetTeam)
  })()

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('ping.title')}</h2>
          <p className="upt-subtitle">{t('ping.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('ping.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('ping.refresh')}
          </button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="ping" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('ping.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('ping.how1'), t('ping.how2'), t('ping.how3')]} />

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
          <input className="upt-search" type="text" placeholder={t('ping.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('ping.noMonitorsAdmin') : t('ping.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updatePingMonitor, remove: api.monitoring.deletePingMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {canManageRow(m) && (
                  <input type="checkbox" className="upt-card-check" checked={bulkSel.has(m.id)} onChange={() => toggleBulk(m.id)} onClick={(e) => e.stopPropagation()} aria-label={t('bulk.selectOne')} />
                )}
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.host} />
                <span className="upt-card-top-right">
                  <span className="upt-port-tag">{m.ip_version && m.ip_version !== 'auto' ? m.ip_version.toUpperCase() : 'ICMP'}</span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('ping', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.host}>{m.host}</div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              <div className="upt-card-divider" />
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.rtt_ms != null ? `${m.rtt_ms}ms` : '—'}</span>
                  <span className="upt-metric-lbl">{t('ping.rtt')}</span>
                </div>
                {m.packet_loss != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{formatPercent(m.packet_loss)}</span>
                    <span className="upt-metric-lbl">{t('ping.loss')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('ping.check')} editTitle={t('ping.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('ping.delete')} />
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
          <div className={`upt-modal upt-modal--${selected.status === 'up' ? 'up' : selected.status === 'down' ? 'down' : 'unknown'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.host}</span>
              </div>
              {/* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
                  koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
                  kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */}
              <MonitorModalActions
                running={isRunning(selected.id)}
                onCheck={canManageRow(selected) ? () => checkNow(selected) : undefined}
                checkTitle={t('ping.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('ping.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('ping.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('ping.sumUptimeHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('ping.sumUptime')}{summary.total > 0 ? ` · ${summary.total - summary.down}/${summary.total}` : ''}</span>
              </div>
              <div className="upt-modal-metric" title={t('ping.sumTotalHint')}><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('ping.sumTotal')}</span></div>
              <div className="upt-modal-metric" title={t('ping.sumIncidentsHint')}><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('ping.sumIncidents')}</span></div>
              {selected.rtt_ms != null && <div className="upt-modal-metric" title={t('ping.rttHint')}><span className="upt-modal-metric-val">{selected.rtt_ms}ms</span><span className="upt-modal-metric-lbl">{t('ping.rtt')}</span></div>}
              {selected.packet_loss != null && <div className="upt-modal-metric" title={t('ping.lossHint')}><span className="upt-modal-metric-val">{formatPercent(selected.packet_loss)}</span><span className="upt-modal-metric-lbl">{t('ping.loss')}</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('ping.lastCheck')}</span></div>}
            </div>
            {selected.status === 'na' && <div className="alert-msg" style={{ marginTop: 4 }}>{t('ping.naHint')}</div>}
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('ping.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('ping.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('ping.tabGuide')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'control' && (
              <CheckHistoryTab kind="ping" monitorId={selected.id} listKey="ping-history" reloadSignal={histReload}
                columns={[t('ping.colTime'), t('ping.colStatus'), t('ping.rtt'), t('ping.colDetail')]}
                onCounts={(c) => setSummary({ total: c.total, down: c.fail })}
                renderRow={(c) => (<>
                  <span className="upt-rt-time">{formatDateSec(c.checked_at)}</span>
                  <span className={c.up ? 'upt-rt-up' : 'upt-rt-down'}>{c.up ? t('ping.statusUp') : t('ping.statusDown')}</span>
                  <span className="upt-rt-ms">{c.rtt_ms != null ? `${c.rtt_ms}ms` : '—'}</span>
                  {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span>
                    : <span className="upt-rt-ms">{formatPercent(c.packet_loss)}</span>}
                </>)} />
            )}

            {detailTab === 'alerts' && <AlertHistory domain={selected.host} types={alertTypesFor('ping')} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="ping" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PING" target={selected.host} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="ping" monitorId={selected.id} teamNames={teamNameById}
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
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Radio size={20} /></div>
              <h3>{modal === 'new' ? t('ping.modalNew') : t('ping.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
              {/* Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen). */}
              <span className="modal-icon-hdr-running"><CheckRunningStrip running={saving || testing} label={saving ? t('mon.saving') : t('ping.testing')} /></span>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>
            {dupSource && <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>}
            <div className="form-grid">
              <label className="full-width"><span>{t('ping.host')} <span className="req-star">*</span></span>
                <input value={form.host} placeholder="1.2.3.4 / host.example.com" autoFocus={!!dupSource} onChange={e => setForm(f => ({ ...f, host: e.target.value }))} />
                {dupHost && <span className="field-hint field-hint--warn">{t('ping.dupHostWarn')}</span>}</label>
              <label><span>{t('ping.ipVersion')}</span>
                <SearchableSelect value={form.ipVersion} onChange={v => setForm(f => ({ ...f, ipVersion: v }))}
                  options={[{ value: 'auto', label: t('ping.ipAuto') }, { value: 'v4', label: 'IPv4' }, { value: 'v6', label: 'IPv6' }]} /></label>
              <label><span>{t('ping.packetCount')}</span>
                <input type="number" min="1" max="10" value={form.packetCount} onChange={e => setForm(f => ({ ...f, packetCount: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.name')}</span>
                <input value={form.name} placeholder={form.host} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('ping.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('ping.noTeam')} disabled />}</label>
              <label><span>{t('ping.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('ping.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('ping.noGroup')} /></label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
                onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
              {/* Etiketler — zorunlu (2026-09-18); Http/Port ile aynı blok */}
              <div className="full-width http-tags-block">
                <div className="http-block-title">{t('mon.tagsTitle')} <span className="req-star">*</span></div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('mon.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('mon.tagsPlaceholder')} suggestions={teamTags} />
              </div>
              <label><span>{t('ping.timeout')}</span>
                <input type="number" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.confirmAttempts')}</span>
                <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.confirmInterval')}</span>
                <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.recoveryChecks')}</span>
                <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.recoveryInterval')}</span>
                <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
              <div className="full-width" style={{ fontSize: '.8em', color: 'var(--text-muted)', marginTop: -2, lineHeight: 1.5 }}>
                ⓘ {t('ping.confirmHint')}
              </div>

              {/* Yavaşlık alarmı — port izlemesindeki blokla aynı yerleşim, farkı eşiğin GÖRECELİ
                  olması: sabit bir ms değeri yerine host'un kendi son N dakikalık ortalaması.
                  Alanlar yalnız kutucuk işaretliyken açılır; kapalıyken ekranda ölü sayı durmaz. */}
              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.slowResponseEnabled}
                  onChange={e => setForm(f => ({ ...f, slowResponseEnabled: e.target.checked }))} />{t('ping.slowEnable')}</label>
              {form.slowResponseEnabled && (
                <>
                  <label><span>{t('ping.slowWindow')}</span>
                    <input type="number" min="1" max="1440" value={form.slowBaselineWindowMinutes}
                      onChange={e => setForm(f => ({ ...f, slowBaselineWindowMinutes: Number(e.target.value) }))} /></label>
                  <label><span>{t('ping.slowPercent')}</span>
                    <input type="number" min="1" max="1000" value={form.slowThresholdPercent}
                      onChange={e => setForm(f => ({ ...f, slowThresholdPercent: Number(e.target.value) }))} /></label>
                </>
              )}
              <div className="full-width field-hint" style={{ marginTop: -2 }}>{t('ping.slowHint')}</div>

              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('ping.active')}</label>
            </div>
            {testResult && (
              <div style={{ margin: '0 0 4px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.condition_met
                  ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                  : testResult.na
                    ? { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }
                    : { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }) }}>
                {testResult.condition_met
                  ? <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.sent === undefined
                    ? <><strong>{t('ping.testError')}:</strong> {testResult.error}</>
                    : testResult.na
                      ? <><strong>{t('ping.testNa')}</strong></>
                      : testResult.condition_met
                        ? <><strong>{t('ping.testMet')}</strong> — {t('ping.testReachable')}
                            {testResult.rtt_ms != null && <> · RTT {testResult.rtt_ms}ms</>}
                            {testResult.packet_loss != null && <> · {t('ping.loss')} %{testResult.packet_loss}</>}</>
                        : <><strong>{t('ping.testNotMet')}</strong> — {t('ping.testUnreachable')}
                            {testResult.packet_loss != null && <> · {t('ping.loss')} %{testResult.packet_loss}</>}</>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="ping-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <div style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
                <button className="btn btn-secondary" onClick={runTest} aria-busy={testing || undefined} disabled={testing || !form.host.trim()}>
                  <FlaskConical size={14} />{t('ping.test')}
                </button>
                {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('ping.delete')}</button>}
              </div>
              <button className="btn btn-secondary" onClick={closeEdit}>{t('ping.cancel')}</button>
              <button className="btn btn-primary" onClick={save} aria-busy={saving || undefined} disabled={saving || !form.host.trim() || !form.teamId || !!dupHost}>{t('ping.save')}</button>
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
          storageKey="sm.checkRun.teams.ping"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="ping"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
