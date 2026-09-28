import { LoadingBlock } from './ui/Progress.jsx'
import { formatPercent } from '../i18n/dateLocale.js'
import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import PortMonitorCard from './port/PortMonitorCard.jsx'
import PortEndpoint from './ui/PortEndpoint.jsx'
import MonitorProxyField, { ProxyViaBadge } from './ui/MonitorProxyField.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import TagInput from './ui/TagInput.jsx'
import { Plug, Trash2, FlaskConical, AlertTriangle, Network, Check, X, Pause, ChevronDown, BellDot, Copy, Inbox } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
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
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Slider } from '@/components/shadcn/slider'
import { TabsContent } from '@/components/shadcn/tabs'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { FieldDescription } from '@/components/shadcn/field'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import { MonitorDetailModal, DetailDivider, DetailSummary, DetailTabs, useDeepLinkTab } from './monitoring/MonitorDetail.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint, InlineField, LabelSlot,
} from './monitoring/MonitorForm.jsx'
import { cn } from '@/lib/utils'
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
const emptyForm = { name: '', host: '', port: '', protocol: 'TCP', useProxy: 'OFF', expect: '', sendData: '', teamId: '', groupName: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [],
  tags: '', notifyEmail: true, alertLevel: 'WARNING', notifyWebhook: true, ipVersion: 'auto', slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 300, timeoutMs: 5000,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true }

export default function PortMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  // USER ve üstü: kendi takımı için ekle (2026-09-26 — Port, sunucu kabul ettiği hâlde USER'dan "Ekle"yi saklayan TEK
  // sayfaydı; diğer yedi tür USER'ı zaten içeriyordu). Silme ayrı kapıda (canDeleteRow: takım yöneticisi+).
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
  const canManageRow = (m) => isAdmin || isOwnTeam(m)              // düzenle + kontrol (otomatik :443/team_id=null → yalnız admin)
  // Toplu kontrolün adayı = kullanıcının TEK TEK de çalıştırabileceği satırlar. Yeni bir izin
  // kuralı UYDURULMUYOR; kartın ▶ düğmesiyle birebir aynı yüzey.
  const canCheckRow = canManageRow
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('port')   // kart mini trendi (2026-09-12)
  // Vekil bilgisi (2026-09-24): tanımlı mı + CONNECT izinli portlar — form notları ve uyarılar buna göre.
  const [proxyInfo, setProxyInfo] = useState({ configured: null, connect_ports: [443, 8443] })
  useEffect(() => {
    let alive = true
    Promise.resolve(api.monitoring.getPortProxyInfo?.())
      .then((r) => { if (alive && r?.success && r.data && !Array.isArray(r.data)) setProxyInfo(r.data) })
      .catch(() => { /* bilgi süs; form varsayılanla çalışır */ })
    return () => { alive = false }
  }, [])
  const sla = useSla('port')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  // Kart yoğunluğu (2026-09-27): her açılışta Zengin; Kompakt seçimi SAKLANMAZ (yalnız sayfada kalındıkça geçerli)
  const [density, setDensity] = useCardDensity('port')
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [defaults, setDefaults] = useState(null)
  const [selected, setSelected] = useState(null)
  const [detailTab, setDetailTab] = useState('control')
  const deepLinkTab = useDeepLinkTab()   // ?monitor=…&mtab=changes derin bağlantısı — ilk açılışta bir kez
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin. Sekmenin kendi 30 sn'lik
  // canlı yenilemesi yetmiyor: 1. sayfa dışındaysan ya da özel aralık seçtiysen KAPALI. Sinyal,
  // sekmeyi remount ETMEDEN yeniden okutur (remount seçilen aralığı/sayfayı/filtreyi sıfırlardı).
  const [histReload, setHistReload] = useState(0)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)
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
  // Duraklatılmış kartta / detayda tek tıkla "Sürdür" (2026-09-26, tüm izleme sayfalarında varsayılan): toplu işlem
  // çubuğuyla aynı yazma yolu ({ active: true }); açık detay penceresinin kopyası da etkin olarak işaretlenir.
  const { resume, isResuming } = useMonitorResume(api.monitoring.updatePortMonitor, (r) => {
    load(); setSelected((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

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

  // Derin bağlantı ?monitor=<id> (e-posta CTA, 7/24 Kapsamı): TAM listeden açar; yoksa uyarır (hooks/useMonitorDeepLink)
  useMonitorDeepLink(monitors, openModal, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'PORT',   // open=noc: 7/24 Kapsamı "7/24 ayarını düzenle"
  })

  // Modal her açıldığında önceki kaydetme hatası + test sonucunu temizle.
  useEffect(() => { setSaveError(null); setTestResult(null) }, [modal])

  async function openModal(m) {
    setSelected(m)
    setSummary({ total: 0, down: 0 })
    setDetailTab(deepLinkTab())
  }

  function closeModal() { setSelected(null) }

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (defaultTeamId != null ? String(defaultTeamId) : ''),
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
    return { name: m.name || '', host: m.host || '', port: m.port ?? '', protocol: m.protocol || 'TCP', useProxy: m.use_proxy || 'OFF',
      expect: m.expect || '', sendData: m.send_data || '',
      teamId: m.team_id != null ? String(m.team_id) : (derivedTeam ? String(derivedTeam.id) : ''), groupName: m.group_name || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
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
        protocol: form.protocol?.trim() || 'TCP', useProxy: form.useProxy || 'OFF',
        expect: form.expect?.trim() || null, sendData: form.sendData || null,
        teamId: form.teamId === '' ? null : Number(form.teamId), groupName: form.groupName?.trim() || null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
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
      // İlk / taze kontrol (2026-09-28): yeni kart boş kalmasın, hedefi değişen kart eski sonucu göstermesin. Liste
      // YÜKLENDİKTEN sonra başlar → kart ızgarada, dönen göstergeyle bekler (bkz. utils/checkAfterSave).
      if (shouldCheckAfterSave('port', { isNew: modal === 'new', before: modal, after: res.data })) startCheckAfterSave(checkNow, res.data)
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
        ipVersion: form.ipVersion, useProxy: form.useProxy || 'OFF',
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
    // Türev satırda "sil" gerçekte "izlemeyi durdur"dur (sunucu yalnız duraklatır, satır listede kalır); bağımsız
    // satır ise KALICI silinir (2026-09-27: silme ≠ duraklatma, deleted_at). DnsMonitorPage ikiziyle aynı metin ayrımı.
    const derived = m.standalone !== true
    const label = m.name || (m.host + ":" + m.port)
    const ok = await showConfirm({
      title: t('mon.deleteTitle'),
      message: derived ? t('port.deleteDerivedMsg', label) : t('mon.deleteMsg', label),
      confirmText: derived ? t('port.deleteDerivedConfirm') : t('port.delete'),
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
      toast.success(derived ? t('port.deletedDerived') : t('port.deleted'))
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
        // İşlevsel güncelleme (bayat kapanış YOK): yanıt gelene kadar pencere kapanmış ya da başka izlemeye
        // geçilmiş olabilir — A'nın sonucu B'nin penceresini değiştirmesin / kapalı pencereyi yeniden açmasın.
        setSelected(prev => (prev?.id === m.id ? res.data : prev))
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
    listKey: 'port-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
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

  /** Kart durum anahtarı — Port'ta {@code status} open/closed/unknown değerini taşır; duraklatılmış = unknown. */
  const cardStatus = (m) => {
    if (m.active === false) return 'unknown'
    if (m.status === 'open') return 'up'
    if (m.status === 'closed') return 'down'
    return 'unknown'
  }
  /** Rozet / detay kenarı — yalnız port durumuna bakar (eski .upt-badge--* / .upt-modal--* ile aynı eşleme). */
  const statusKey = (status) => (status === 'open' ? 'up' : status === 'closed' ? 'down' : 'unknown')

  function statusBadge(status) {
    const label = status === 'open' ? t('port.statusOpen') : status === 'closed' ? t('port.statusClosed') : t('port.statusUnknown')
    return <MonitorStatusBadge status={statusKey(status)}>{label}</MonitorStatusBadge>
  }
  const alarmLabel = (m) => `${t('port.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`

  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (defaultTeamName || t('app.noTeam'))

  // Gelişmiş ayarlar → istek zaman aşımı kaydırıcısı saniye cinsinden (1..60); form milisaniye tutar.
  const timeoutSecs = Math.min(60, Math.max(1, Math.round(Number(form.timeoutMs) / 1000)))

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // Detay penceresi açıkken form ONUN İÇİNDE çizilir: ModalShell iç içe derinliği React ağacından okur,
  // böylece form (ve örtüsü) detay penceresinin ÜSTÜNDE katmanlanır.
  const formModal = modal && (
    <MonitorFormModal onClose={closeEdit} icon={Plug} width={640}
      title={modal === 'new' ? t('port.modalAdd') : t('port.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('port.testing') : null}
      footer={<>
        <div className="mr-auto flex flex-wrap gap-2">
          <Button variant="secondary" onClick={runTest} aria-busy={testing || undefined} disabled={testing || !form.host.trim() || !form.port}>
            <FlaskConical size={14} />{t('port.test')}
          </Button>
          {modal !== 'new' && canDeleteRow(modal) && (
            <Button variant="destructive" onClick={() => deleteMonitor(modal)}><Trash2 size={14} />{t('port.delete')}</Button>
          )}
        </div>
        <Button variant="secondary" onClick={closeEdit}>{t('port.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined} disabled={saving || !form.host.trim() || !form.port || !form.teamId}>{t('port.save')}</Button>
      </>}>
      {dupSource
        ? <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>
        : <AlertBanner tone="info" icon={Plug}>{t('port.typeInfo')}</AlertBanner>}

      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField label={t('port.host')} required>
          {({ id }) => (
            <Input id={id} value={form.host} placeholder="1.2.3.4 / host.example.com" autoFocus={!!dupSource}
              onChange={e => setForm(f => ({ ...f, host: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('port.port')} required>
          {({ id }) => (
            <Input id={id} type="number" min="1" max="65535" value={form.port}
              onChange={e => setForm(f => ({ ...f, port: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('port.name')}>
          {({ id }) => (
            <Input id={id} value={form.name} placeholder={form.host}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          )}
        </FormField>
        <FormField label={t('port.checkType')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.protocol} onChange={v => setForm(f => ({ ...f, protocol: v }))}
              options={PORT_TYPES.map(v => ({ value: v, label: t(`port.type.${v}`) }))} />
          )}
        </FormField>
        {(form.protocol === 'HTTP' || form.protocol === 'BANNER' || form.protocol === 'UDP') && (
          <FormField label={form.protocol === 'HTTP' ? t('port.pathLabel') : t('port.sendLabel')}>
            {({ id }) => (
              <Input id={id} value={form.sendData} placeholder={form.protocol === 'HTTP' ? '/health' : ''}
                onChange={e => setForm(f => ({ ...f, sendData: e.target.value }))} />
            )}
          </FormField>
        )}
        {(form.protocol === 'HTTP' || form.protocol === 'BANNER') && (
          <FormField label={form.protocol === 'HTTP' ? t('port.expectStatus') : t('port.expectResp')}>
            {({ id }) => (
              <Input id={id} value={form.expect} placeholder={form.protocol === 'HTTP' ? '200, 2xx, 200-399' : '220, +OK, SSH-2.0'}
                onChange={e => setForm(f => ({ ...f, expect: e.target.value }))} />
            )}
          </FormField>
        )}
        {(form.protocol === 'HTTP' || form.protocol === 'BANNER' || form.protocol === 'UDP') && (
          <FormHint>ⓘ {t(`port.typeHint.${form.protocol}`)}</FormHint>
        )}
        {/* Kurumsal vekil (2026-09-24): varsayılan Doğrudan — mevcut izlemeler yol değiştirmez; hangi portların
            vekilden denetlenebileceği ve UDP/izinsiz port uyarıları hemen altında. */}
        <LabelSlot full>
          <MonitorProxyField value={form.useProxy} onChange={v => setForm(f => ({ ...f, useProxy: v }))}
            effective={modal && typeof modal === 'object' && modal.proxy_effective ? { via: modal.proxy_effective, source: modal.proxy_source, bypassed: modal.proxy_bypassed, mode: modal.use_proxy } : null} />
        </LabelSlot>
        <PortProxyNotes t={t} mode={form.useProxy} protocol={form.protocol} port={form.port} info={proxyInfo} />
        <FormField label={t('port.team')} required>
          {({ id }) => canPickTeam
            ? <SearchableSelect id={id} value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
            : <Input id={id} value={defaultTeamName || t('app.noTeam')} disabled />}
        </FormField>
        <FormField label={t('port.group')} required>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
              options={[{ value: '', label: t('port.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('port.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="PORT" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
        {/* Etiketler */}
        <FormSection title={t('port.tagsTitle')} required hint={t('port.tagsHint')}>
          <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('port.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>

        {/* IP sürümü */}
        <FormField label={t('port.ipVersion')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.ipVersion} onChange={v => setForm(f => ({ ...f, ipVersion: v }))}
              options={[{ value: 'auto', label: t('port.ipAuto') }, { value: 'v4', label: 'IPv4' }, { value: 'v6', label: 'IPv6' }]} />
          )}
        </FormField>

        {/* Gelişmiş ayarlar — açılır/kapanır (kapalıyken içerik DOM'da yok: eski koşullu çizimle aynı) */}
        <Collapsible open={advOpen} onOpenChange={setAdvOpen} className="min-w-0 rounded-lg border bg-muted/30 sm:col-span-2">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" className="h-auto w-full justify-start gap-2 rounded-lg px-3.5 py-3 text-left font-semibold">
              <ChevronDown size={16} aria-hidden="true"
                className={cn('text-muted-foreground transition-transform motion-reduce:transition-none', advOpen && 'rotate-180')} />
              {t('port.advanced')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent className="flex flex-col gap-3 px-3.5 pb-3.5">
            <FormSection boxed={false} title={t('port.timeoutTitle')} hint={t('port.timeoutEvery').replace('{0}', timeoutSecs)}>
              <Slider min={1} max={60} step={1} value={[timeoutSecs]}
                onValueChange={([v]) => setForm(f => ({ ...f, timeoutMs: v * 1000 }))}
                thumbProps={{ 'aria-label': t('port.timeoutTitle'), 'aria-valuetext': t('port.timeoutEvery').replace('{0}', timeoutSecs) }}
                className="py-1.5" />
            </FormSection>
            <CheckField checked={form.slowResponseEnabled} onCheckedChange={v => setForm(f => ({ ...f, slowResponseEnabled: v }))} label={t('port.slowEnable')} />
            {form.slowResponseEnabled && (
              <InlineField label={t('port.slowThreshold')}>
                {({ id }) => <Input id={id} type="number" min="100" step="100" className="h-8 w-36" value={form.slowThresholdMs}
                  onChange={e => setForm(f => ({ ...f, slowThresholdMs: Number(e.target.value) }))} />}
              </InlineField>
            )}
            <FormHint full={false}>{t('port.slowHint')}</FormHint>
            <FormGrid>
              <FormField label={t('port.confirmAttempts')}>
                {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('port.confirmInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('port.recoveryChecks')}>
                {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
              </FormField>
              <FormField label={t('port.recoveryInterval')}>
                {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
              </FormField>
            </FormGrid>
            <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('port.active')} />
            <FormHint full={false}>ⓘ {t('port.confirmHint')}</FormHint>
          </CollapsibleContent>
        </Collapsible>
      </FormGrid>

      {testResult && (testResult.open === undefined && testResult.error
        ? <AlertBanner className="mt-3" tone="danger">{testResult.error}</AlertBanner>
        : (
          <AlertBanner className="mt-3" tone={testResult.open ? 'success' : 'danger'}
            icon={testResult.open ? undefined : AlertTriangle}
            title={<>{testResult.open ? t('port.testPass') : t('port.testNoPass')}
              {testResult.response_ms != null && <span className="font-semibold tabular-nums opacity-80"> · {testResult.response_ms} ms</span>}</>}>
            {testResult.detail && <div>{t('port.testReturned')}: <b>{testResult.detail}</b></div>}
            {testResult.via === 'proxy' && <div>{t('port.testVia')}: <b>{t('mon.proxy.effProxy')}</b></div>}
            {testResult.proxy_bypassed && <div>{t('port.testBypassed')}</div>}
            {testResult.error && <div className="text-destructive">{testResult.error}</div>}
            <div className="mt-1 opacity-80">{testResult.open ? t('port.testNoteOk') : t('port.testNoteFail')}</div>
          </AlertBanner>
        ))}
      {saveError && <AlertBanner className="mt-3" tone="danger">{saveError}</AlertBanner>}
      {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı.
          Form ızgarasının DIŞINDA, eylem çubuğunun hemen üstünde: sekiz izleme
          sayfasında da aynı yerde dursun (ızgaraların iç düzeni sayfadan sayfaya değişiyor). */}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="port-change-note" value={changeNote} onChange={setChangeNote} />
      )}
    </MonitorFormModal>
  )

  return (
    <div className="mon-page">
      <MonitorPageHeader type="port" title={t('port.title')} subtitle={t('port.subtitle')}
        count={loading ? null : monitors.length} down={portCounts.down}
        refreshIn={REFRESH_INTERVAL - secondsSince} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('port.addMonitor')} />

      <MonitorHowBox bullets={[t('port.how1'), t('port.how2'), t('port.how3')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', marginBottom: '14px', gap: 8 }}>
          {/* Kart görünümü seçicisi satırın İLK öğesi (mr-auto): süzgeçler + arama sağda kalır; telefonda satır sarar */}
          <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
          {hasTeamOptions && (
            <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />
          )}
          {hasGroupOptions && (
            <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />
          )}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
          <Input type="text" className="w-full sm:w-auto sm:max-w-xs sm:min-w-[200px]" placeholder={t('port.searchPlaceholder')} aria-label={t('port.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={t('port.noMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="PORT"
          api={{ update: api.monitoring.updatePortMonitor, remove: api.monitoring.deletePortMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* Port kartı (port/PortMonitorCard, 2026-09-27): uç nokta kimliği (host · :port · tür · yaygın hizmet),
               SONUÇ PANELİ (açık / reddedildi / filtreli / DNS … + tek satırlık neden), bağlantı süresi kutuları, kaynak
               rozeti (bağımsız / envanterden). Yetkiye, seçime ve eylemlere bağlı parçalar BURADA kurulur ve yuva olarak
               geçer — toplu seçim kutusu, meta, kart eylemleri (türev satırda silme = "izlemeyi durdur"). Durum sözlüğü
               detay penceresiyle ortak (cardStatus / statusBadge / alarmLabel). */
            <PortMonitorCard key={m.id} monitor={m} density={density} running={isRunning(m.id)} status={cardStatus(m)} badge={statusBadge(m.status)} alarmLabel={alarmLabel(m)}
              onOpen={() => openModal(m)}
              spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', `${m.host}:${m.port}`)} />
              )}
              meta={<MonitorCardMeta monitor={m} />}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={`${m.host}:${m.port}`}
                  running={isRunning(m.id)}
                  onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('port.check')} editTitle={t('port.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id} deleteTitle={m.standalone === true ? t('port.delete') : t('port.deleteDerivedTitle')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* ── Detay penceresi (ui/ModalShell) ── */}
      {selected && (
        <MonitorDetailModal onClose={closeModal} status={statusKey(selected.status)} badge={statusBadge(selected.status)} title={selected.host} nocNotify={!!selected.noc_notify}
          // Uç nokta satırı kartla AYNI gösterim (büyük boy) — ortak alt başlık yuvası (eski ":25" gri etiketi değil).
          subtitle={<PortEndpoint host={selected.host} port={selected.port} protocol={selected.protocol} path={selected.send_data} size="lg" />}
          actions={
            /* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
               koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
               kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */
            <MonitorModalActions
              onResume={canManageRow(selected) && !selected.active ? () => resume(selected) : undefined}
              resuming={isResuming(selected.id)}
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
              <CopyLinkButton iconOnly variant="outline" />
            </MonitorModalActions>
          }>
          <DetailDivider className="mt-3" />
          <DetailSummary items={[
            { key: 'up', value: summary.total > 0 ? formatPercent(Math.round((summary.total - summary.down) * 1000 / summary.total) / 10) : '—',
              label: t('port.sumUptime'), hint: t('port.sumUptimeHint') },
            { key: 'total', value: summary.total, label: t('port.sumTotal'), hint: t('port.sumTotalHint') },
            { key: 'inc', value: summary.down, label: t('port.sumIncidents'), hint: t('port.sumIncidentsHint') },
            selected.response_ms != null && { key: 'ms', value: `${selected.response_ms}ms`, label: t('port.responseMs'), hint: t('port.responseMsHint') },
            selected.protocol && { key: 'proto', value: selected.protocol, label: t('port.protocol') },
            selected.proxy_effective && { key: 'proxy', label: t('mon.proxy.label'),
              value: <ProxyViaBadge via={selected.proxy_effective} source={selected.proxy_source} bypassed={selected.proxy_bypassed} /> },
            selected.interval_seconds != null && { key: 'iv', value: `${selected.interval_seconds}s`, label: t('port.intervalLbl'), time: true },
            selected.timeout_ms != null && { key: 'to', value: `${selected.timeout_ms}ms`, label: t('port.timeoutLbl'), time: true },
            selected.checked_at && { key: 'last', value: formatDate(selected.checked_at), label: t('port.lastCheck'), time: true },
          ]} />
          <DetailDivider />
          <DetailTabs value={detailTab} onValueChange={setDetailTab}
            countsFor={{ kind: 'port', monitorId: selected.id, notesType: 'PORT', notesTarget: `${selected.host}:${selected.port}`, openAlerts: selected.active_alarm ? 1 : 0 }}
            tabs={[['control', t('hist.tab')], ['alerts', t('port.tabAlerts')], ['chart', t('port.tabChart')], ['notes', t('port.tabGuide')],
              // Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
              // orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi".
              ['changes', t('chg.tab')]]}>
            <TabsContent value="control">
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
            </TabsContent>

            <TabsContent value="alerts"><AlertHistory domain={selected.host} types={alertTypesFor('port')} /></TabsContent>

            <TabsContent value="chart">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ResponseTimeChart monitorId={selected.id} kind="port" />
              </Suspense>
            </TabsContent>

            <TabsContent value="notes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="PORT" target={`${selected.host}:${selected.port}`} />
              </Suspense>
            </TabsContent>

            <TabsContent value="changes">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="port" monitorId={selected.id} teamNames={teamNameById}
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

/**
 * Vekil notları (2026-09-24, kullanıcı: "hangi portlar proxy üzerinden kontrol edilebilir, kullanıcıya aktaralım;
 * UDP gibi bir türü proxy'den izlemek isterse uyaralım"). İzinli port listesi her zaman görünür; uyarılar yalnız vekil
 * seçiliyken (Açık ya da Envanterle aynı). Uyarılar kaydı ENGELLEMEZ — vekil politikası değişebilir.
 */
function PortProxyNotes({ t, mode, protocol, port, info }) {
  const ports = Array.isArray(info?.connect_ports) && info.connect_ports.length ? info.connect_ports : [443, 8443]
  const list = ports.join(', ')
  const wantsProxy = mode === 'ON' || mode === 'AUTO'
  const n = Number(port)
  // Satırlar shadcn FieldDescription (vekil alanının açıklaması); uyarılar `data-tone="warn"` + uyarı rengi.
  const note = (text, warn = false) => (
    <FieldDescription data-tone={warn ? 'warn' : undefined} className={cn('text-xs', warn && 'text-warning')}>{text}</FieldDescription>
  )
  return (
    <div data-slot="port-proxy-notes" className="-mt-1.5 flex min-w-0 flex-col gap-0.5 sm:col-span-2">
      {note(<>ⓘ {t('port.proxyPorts', list)}</>)}
      {wantsProxy && protocol === 'UDP' && note(<>⚠ {t('port.proxyUdpWarn')}</>, true)}
      {wantsProxy && protocol !== 'UDP' && n > 0 && !ports.includes(n) && note(<>⚠ {t('port.proxyPortWarn', n, list)}</>, true)}
      {wantsProxy && info?.configured === false && note(<>⚠ {t('port.proxyNotConfigured')}</>, true)}
      {wantsProxy && protocol !== 'UDP' && note(t('port.proxyMeaning'))}
    </div>
  )
}
