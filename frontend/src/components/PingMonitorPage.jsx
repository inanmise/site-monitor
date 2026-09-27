import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { formatPercent } from '../i18n/dateLocale.js'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TagInput from './ui/TagInput.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import { Trash2, Radio, FlaskConical, AlertTriangle, LayoutDashboard, CheckCircle2, WifiOff, Siren, BellDot, PauseCircle, Inbox, Copy } from 'lucide-react'
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
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin.
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import PingProtocol from './ui/PingProtocol.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { useCardDensity } from '../hooks/useCardDensity.js'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { TabsContent } from '@/components/shadcn/tabs'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import PingMonitorCard from './ping/PingMonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint,
} from './monitoring/MonitorForm.jsx'
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
const emptyForm = { name: '', host: '', ipVersion: 'auto', groupName: '', tags: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], teamId: '',
  intervalSeconds: 60, timeoutMs: 5000, packetCount: 4, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true, active: true,
  // Yavaşlık alarmı OPT-IN: varsayılan kapalı — mevcut izlemelerin hiçbiri bir gün sabah
  // birden yeni bir alarm türü üretmeye başlamasın.
  slowResponseEnabled: false, slowBaselineWindowMinutes: 10, slowThresholdPercent: 20 }

export default function PingMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  // Ortak bildirim blogunun hedef satiri icin takim adi (HttpMonitorPage deseni).
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'      // USER ve üstü: kendi takımı için oluştur/düzenle/kontrol
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
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
  // Kart yoğunluğu (2026-09-27): Kompakt / Zengin — her açılış Zengin başlar; Kompakt seçimi yalnız sayfada kalındıkça
  // geçerli, KALICI DEĞİL (kullanıcı kararı: sayfa değişip dönünce ya da yenileyince yeniden Zengin)
  const [density, setDensity] = useCardDensity('ping')
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (defaultTeamName || t('app.noTeam'))
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
  const deepLinkTab = useDeepLinkTab()   // ?monitor=…&mtab=changes derin bağlantısı — ilk açılışta bir kez
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
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
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
  // Duraklatılmış kartta / detayda tek tıkla "Sürdür" (2026-09-26, tüm izleme sayfalarında varsayılan): toplu işlem
  // çubuğuyla aynı yazma yolu ({ active: true }); açık detay penceresinin kopyası da etkin olarak işaretlenir.
  const { resume, isResuming } = useMonitorResume(api.monitoring.updatePingMonitor, (r) => {
    load(); setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

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

  function openDetail(m) { setSelected(m); setSummary({ total: 0, down: 0 }); setDetailTab(deepLinkTab()) }
  function closeDetail() { setSelected(null) }

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (defaultTeamId != null ? String(defaultTeamId) : ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', host: m.host || '', ipVersion: m.ip_version || 'auto', groupName: m.group_name || '', tags: m.tags || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
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
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
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
        // İşlevsel güncelleme (bayat kapanış YOK): yanıt gelene kadar pencere kapanmış ya da başka izlemeye
        // geçilmiş olabilir — A'nın sonucu B'nin penceresini değiştirmesin / kapalı pencereyi yeniden açmasın.
        setSelected(prev => (prev?.id === m.id ? res.data : prev))
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
    listKey: 'ping-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
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

  // Durum sözlüğü (kart şeridi / rozet / detay kenarı): up | down | unknown ('na' = ICMP kullanılamıyor → unknown).
  const statusKey = (m) => (m?.status === 'up' ? 'up' : m?.status === 'down' ? 'down' : 'unknown')
  function statusBadge(m) {
    const s = m?.status
    const label = s === 'up' ? t('ping.statusUp') : s === 'down' ? t('ping.statusDown')
      : s === 'na' ? t('ping.statusNa') : t('ping.statusUnknown')
    return <MonitorStatusBadge status={statusKey(m)}>{label}</MonitorStatusBadge>
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

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // Detay penceresi açıkken form ONUN İÇİNDE çizilir: ModalShell iç içe derinliği React ağacından okur,
  // böylece form (ve örtüsü) detay penceresinin ÜSTÜNDE katmanlanır.
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={Radio}
      title={modal === 'new' ? t('ping.modalNew') : t('ping.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('ping.testing') : null}
      footer={<>
        <div className="mr-auto flex flex-wrap gap-2">
          <Button variant="secondary" onClick={runTest} aria-busy={testing || undefined} disabled={testing || !form.host.trim()}>
            <FlaskConical size={14} />{t('ping.test')}
          </Button>
          {modal !== 'new' && canDeleteRow(modal) && <Button variant="destructive" onClick={del}><Trash2 size={14} />{t('ping.delete')}</Button>}
        </div>
        <Button variant="secondary" onClick={closeEdit}>{t('ping.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.host.trim() || !form.teamId || !!dupHost}>{t('ping.save')}</Button>
      </>}>
      {dupSource && <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>}
      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField full label={t('ping.host')} required hint={dupHost ? t('ping.dupHostWarn') : undefined} hintTone="warn">
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} value={form.host} placeholder="1.2.3.4 / host.example.com" autoFocus={!!dupSource}
              onChange={e => setForm(f => ({ ...f, host: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('ping.ipVersion')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.ipVersion} onChange={v => setForm(f => ({ ...f, ipVersion: v }))}
              options={[{ value: 'auto', label: t('ping.ipAuto') }, { value: 'v4', label: 'IPv4' }, { value: 'v6', label: 'IPv6' }]} />
          )}
        </FormField>
        <FormField label={t('ping.packetCount')}>
          {({ id }) => <Input id={id} type="number" min="1" max="10" value={form.packetCount} onChange={e => setForm(f => ({ ...f, packetCount: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('ping.name')}>
          {({ id }) => <Input id={id} value={form.name} placeholder={form.host} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />}
        </FormField>
        <FormField label={t('ping.team')} required>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('ping.noTeam')} disabled />}
        </FormField>
        <FormField label={t('ping.group')} required>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
              options={[{ value: '', label: t('ping.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('ping.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="PING" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
        {/* Etiketler — zorunlu (2026-09-18); Http/Port ile aynı blok */}
        <FormSection title={t('mon.tagsTitle')} required hint={t('mon.tagsHint')}>
          <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('mon.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>
        <FormField label={t('ping.timeout')}>
          {({ id }) => <Input id={id} type="number" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('ping.confirmAttempts')}>
          {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('ping.confirmInterval')}>
          {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('ping.recoveryChecks')}>
          {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('ping.recoveryInterval')}>
          {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
        </FormField>
        <FormHint>ⓘ {t('ping.confirmHint')}</FormHint>

        {/* Yavaşlık alarmı — port izlemesindeki blokla aynı yerleşim, farkı eşiğin GÖRECELİ
            olması: sabit bir ms değeri yerine host'un kendi son N dakikalık ortalaması.
            Alanlar yalnız kutucuk işaretliyken açılır; kapalıyken ekranda ölü sayı durmaz. */}
        <CheckField full checked={form.slowResponseEnabled} onCheckedChange={v => setForm(f => ({ ...f, slowResponseEnabled: v }))} label={t('ping.slowEnable')} />
        {form.slowResponseEnabled && (
          <>
            <FormField label={t('ping.slowWindow')}>
              {({ id }) => <Input id={id} type="number" min="1" max="1440" value={form.slowBaselineWindowMinutes}
                onChange={e => setForm(f => ({ ...f, slowBaselineWindowMinutes: Number(e.target.value) }))} />}
            </FormField>
            <FormField label={t('ping.slowPercent')}>
              {({ id }) => <Input id={id} type="number" min="1" max="1000" value={form.slowThresholdPercent}
                onChange={e => setForm(f => ({ ...f, slowThresholdPercent: Number(e.target.value) }))} />}
            </FormField>
          </>
        )}
        <FormHint>{t('ping.slowHint')}</FormHint>

        <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('ping.active')} />
      </FormGrid>

      {testResult && (
        <AlertBanner className="mt-3"
          tone={testResult.condition_met ? 'success' : testResult.na ? 'warning' : 'danger'}
          icon={testResult.condition_met ? undefined : AlertTriangle}
          title={testResult.sent === undefined ? t('ping.testError')
            : testResult.na ? t('ping.testNa')
            : testResult.condition_met ? t('ping.testMet') : t('ping.testNotMet')}>
          {testResult.sent === undefined
            ? testResult.error
            : testResult.na
              ? null
              : testResult.condition_met
                ? <>{t('ping.testReachable')}
                    {testResult.rtt_ms != null && <> · RTT {testResult.rtt_ms}ms</>}
                    {testResult.packet_loss != null && <> · {t('ping.loss')} %{testResult.packet_loss}</>}</>
                : <>{t('ping.testUnreachable')}
                    {testResult.packet_loss != null && <> · {t('ping.loss')} %{testResult.packet_loss}</>}</>}
        </AlertBanner>
      )}
      {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="ping-change-note" value={changeNote} onChange={setChangeNote} />
      )}
    </MonitorFormModal>
  )

  return (
    <div className="upt-page">
      <MonitorPageHeader type="ping" title={t('ping.title')} subtitle={t('ping.subtitle')}
        count={loading ? null : monitors.length} down={counts.down}
        refreshIn={REFRESH_INTERVAL - secondsSince} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('ping.addMonitor')} />

      <MonitorHowBox bullets={[t('ping.how1'), t('ping.how2'), t('ping.how3')]} />

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
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('ping.searchPlaceholder')} aria-label={t('ping.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('ping.noMonitorsAdmin') : t('ping.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="PING"
          api={{ update: api.monitoring.updatePingMonitor, remove: api.monitoring.deletePingMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Kart sunumu ping/PingMonitorCard'da (MonitorCard ailesi, stretched button). Sayfaya ait kablolama
               yuva olarak geçer: toplu seçim kutusu (seçim kümesi burada) ve eylemler (yetki + işleyiciler burada). */
            <PingMonitorCard key={m.id} monitor={m} density={density} onOpen={() => openDetail(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.host)} />
              )}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={m.host}
                  running={isRunning(m.id)}
                  onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('ping.check')} editTitle={t('ping.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={t('ping.delete')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeDetail} status={statusKey(selected)} badge={statusBadge(selected)} title={selected.host} nocNotify={!!selected.noc_notify}
          // Protokol satırı kartla AYNI gösterim (büyük boy), başlığın hemen altında — ortak alt başlık yuvası.
          subtitle={<PingProtocol host={selected.host} ipVersion={selected.ip_version} packetCount={selected.packet_count} size="lg" />}
          actions={
            /* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
               koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
               kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
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
              <CopyLinkButton iconOnly variant="outline" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-3" />
          <DetailSummary items={[
            { key: 'up', value: summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—',
              label: `${t('ping.sumUptime')}${summary.total > 0 ? ` · ${summary.total - summary.down}/${summary.total}` : ''}`, hint: t('ping.sumUptimeHint') },
            { key: 'total', value: summary.total, label: t('ping.sumTotal'), hint: t('ping.sumTotalHint') },
            { key: 'inc', value: summary.down, label: t('ping.sumIncidents'), hint: t('ping.sumIncidentsHint') },
            selected.rtt_ms != null && { key: 'rtt', value: `${selected.rtt_ms}ms`, label: t('ping.rtt'), hint: t('ping.rttHint') },
            selected.packet_loss != null && { key: 'loss', value: formatPercent(selected.packet_loss), label: t('ping.loss'), hint: t('ping.lossHint') },
            selected.checked_at && { key: 'last', value: formatDateSec(selected.checked_at), label: t('ping.lastCheck'), time: true },
          ]} />
          {selected.status === 'na' && <AlertBanner tone="warning" className="mt-3">{t('ping.naHint')}</AlertBanner>}
          <DetailDivider />
          <DetailTabs value={detailTab} onValueChange={setDetailTab}
            countsFor={{ kind: 'ping', monitorId: selected.id, notesType: 'PING', notesTarget: selected.host, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['control', t('hist.tab')], ['alerts', t('ping.tabAlerts')], ['chart', t('ping.tabChart')], ['notes', t('ping.tabGuide')],
              // Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
              // orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            <TabsContent value="control">
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
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.host} types={alertTypesFor('ping')} /></TabsContent>

            <TabsContent value="chart">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="ping" />
              </Suspense>
            </TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PING" target={selected.host} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="ping" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            </TabsContent>
          </DetailTabs>

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
