import { useState, useEffect, useCallback, useMemo } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import CheckAllButton from './check/CheckAllButton.jsx'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import { monitorDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import { Plus, ChevronDown, Globe, Info, Network, AlertTriangle, FlaskConical, Check, RefreshCw, Pause, BellDot, ArrowLeftRight } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import DnsDetailModal from './DnsDetailModal.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TagInput from './ui/TagInput.jsx'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import { LoadingBlock } from './ui/Progress.jsx'

import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']

// Kaydirma cubugu icin sirali aralik seti. DNS'in tabani 30 sn olabilir (tek sorgu ucuz);
// Sayfa Hizi gibi agir turlerde taban bilincli olarak yuksek kalir.
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

const INFO_ITEMS = [
  { type: 'A',     descKey: 'dns.recA'     },
  { type: 'AAAA',  descKey: 'dns.recAAAA'  },
  { type: 'CNAME', descKey: 'dns.recCNAME' },
  { type: 'MX',    descKey: 'dns.recMX'    },
  { type: 'TXT',   descKey: 'dns.recTXT'   },
  { type: 'NS',    descKey: 'dns.recNS'    },
  { type: 'SOA',   descKey: 'dns.recSOA'   },
  { type: 'TTL',   descKey: 'dns.ttlExplain' },
]

const emptyForm = { name: '', domain: '', recordType: 'A', intervalSeconds: 300, teamId: '', groupName: '', tags: '', notificationGroupId: '', expectedValue: '', slowThresholdMs: '', propagationCheck: false, dnsChangeAlertEnabled: true, notifyEmail: true, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, notifyWebhook: true, active: true }

function truncateValue(val, max = 50) {
  if (!val) return '—'
  return val.length > max ? val.substring(0, max) + '…' : val
}

export default function DnsMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'   // USER ve üstü: kendi takımı için standalone DNS ekler
  const myTeam = teamId != null ? String(teamId) : null
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId })
  // Kapılar PortMonitorPage ile AYNI — uçlar da hizalandı (MonitoringController.updateDns/
  // deleteDns/triggerDns artık canOperateTeam kullanıyor, sekiz kardeş türle aynı kural).
  //
  // Eskiden burada `m.standalone && isOwnTeam(m)` vardı ve envanter-türevi satırlarda kartın
  // BÜTÜN düğmeleri kayboluyordu. Kullanıcının gördüğü şey bir yetki kuralı değil, boş bir
  // kart köşesiydi: "düğmeler neden görünmüyor?" Oysa envanter-türevi DNS kaydı takımını
  // envanterden alır, yani aynı takım yöneticisi aynı domainin Port izlemesini ve envanter
  // kaydının kendisini zaten yönetebiliyordu — fazladan admin şartının koruyucu değeri yoktu.
  const canManageRow = (m) => isAdmin || isOwnTeam(m)
  // Toplu kontrolün adayı = tek tek de çalıştırılabilen satırlar; kartın ▶ düğmesiyle aynı yüzey.
  const canCheckRow = canManageRow
  // Silme SEMANTİĞİ hâlâ standalone'a göre ayrışır (standalone → gerçek silme; envanter-türevi →
  // pasifleştirme, envanter senkronu yeniden açabilir); ayrışan yalnız DAVRANIŞ, yetki değil.
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('dns')   // kart mini trendi (2026-09-12)
  const sla = useSla('dns')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [detailMonitor, setDetailMonitor] = useState(null)
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin (diğer sekiz türle aynı):
  // sekmenin kendi 30 sn'lik canlı yenilemesi 1. sayfa dışında ve özel aralıkta KAPALI.
  const [histReload, setHistReload] = useState(0)
  const [modal, setModal] = useState(null)
  // Düzenleme modalı: sabit başlık + kaydırılan gövde + sabit alt bar (useModalScrollHint).
  const scrollHint = useModalScrollHint()
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [saving, setSaving] = useState(false)
  // Tek kimlik yerine KUME: uzun suren bir kontrol digerlerini bekletmesin ve
  // once biten, hala sureni kilitten cikarmasin.
  const { isRunning, track } = useRunningChecks()
  const [deleting, setDeleting] = useState(null)
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [infoOpen, setInfoOpen] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [testing, setTesting] = useState(false)
  const [defaults, setDefaults] = useState(null)
  const [secondsSince, setSecondsSince] = useState(0)
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)

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
      const res = await api.monitoring.getDnsMonitors()
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
    concurrency: CHECK_CONCURRENCY_BY_TYPE.dns,
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

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'dns').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  // Yeni monitör için varsayılan kontrol aralığı (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.dns) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili DNS monitörünün detayını aç (bir kez), paramı temizle.
  useMonitorDeepLink(monitors, setDetailMonitor)

  // Ortak bildirim blogunun "kime gidecek" satiri icin hedef takim adi (HttpMonitorPage deseni).
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (teamName || t('app.noTeam'))
  const teamSelectOptions = [...(isAdmin ? [{ value: '', label: t('app.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))]

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds })
    setTestResult(null)
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return {
      name: m.name || '',
      domain: m.domain || '',
      recordType: m.record_type,
      intervalSeconds: m.interval_seconds,
      notifyEmail: m.notify_email !== false,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      teamId: m.team_id != null ? String(m.team_id) : '',
      groupName: m.group_name || '', tags: m.tags || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
      expectedValue: m.expected_value || '',
      slowThresholdMs: m.slow_threshold_ms ?? '',
      propagationCheck: m.propagation_check === true,
      dnsChangeAlertEnabled: m.dns_change_alert_enabled !== false,   // null/undefined = açık
      notifyWebhook: m.notify_webhook !== false,
      active: m.active !== false,
    }
  }
  function openEdit(m) {
    setDupSource(null)
    setForm(formFrom(m))
    setTestResult(null)
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız domain alanını değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.domain) })
    setTestResult(null)
    setModal('new')
  }
  function closeEditModal() { setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  async function save() {
    // Takım alanı yalnız yeni/standalone'da görünür ve zorunlu; envanter-türevi düzenlemede takım envanterden gelir.
    if ((modal === 'new' || modal?.standalone) && (form.teamId === '' || form.teamId == null)) {
      toast.error(t('mon.teamRequired')); return
    }
    if (!form.groupName?.trim()) { toast.error(t('mon.groupRequired')); return }   // grup + etiket zorunlu (2026-09-18)
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }
    setSaving(true)
    try {
      const isNew = modal === 'new'
      const payload = {
        name: (form.name || '').trim(),
        recordType: form.recordType,
        intervalSeconds: form.intervalSeconds,
        notifyEmail: form.notifyEmail,
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        expectedValue: (form.expectedValue || '').trim(),
        slowThresholdMs: form.slowThresholdMs === '' ? null : Number(form.slowThresholdMs),
        groupName: form.groupName?.trim() || null, tags: form.tags?.trim() || null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        propagationCheck: !!form.propagationCheck,
        dnsChangeAlertEnabled: !!form.dnsChangeAlertEnabled,
        notifyWebhook: !!form.notifyWebhook,
        active: form.active,
      }
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      let res
      if (isNew) {
        payload.domain = (form.domain || '').trim()
        payload.teamId = form.teamId === '' ? null : Number(form.teamId)
        res = await api.monitoring.createDnsMonitor(payload)
      } else {
        payload.domain = (form.domain || '').trim()   // domain artık düzenlenebilir
        if (modal.standalone) payload.teamId = form.teamId === '' ? null : Number(form.teamId)
        res = await api.monitoring.updateDnsMonitor(modal.id, payload)
      }
      await load()
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      toast.success(t('dns.saved'))
      // Envanter bagi koptuysa kullaniciyi bilgilendir: duzenleme kalici, envanter domain'i
      // icin AYRI bir izleme surecek (bkz. MonitoringController.detachIfIdentityChanged).
      if (res.data?.detached_from_inventory) toast.info(t('mon.detachedFromInventory'), 8000)
      closeEditModal()
    } finally {
      setSaving(false)
    }
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerDnsCheck(m.id)
      if (res?.success) {
        setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
        // Detay modalı AÇIKSA onun kendi kopyası da tazelenmeli — diğer sekiz sayfa bunu zaten
        // yapıyordu, DNS yapmıyordu: modalden kontrol koşturunca üstteki özet eski değerde kalıyor,
        // kullanıcı "çalıştı mı?" diye ikinci kez basıyordu.
        setDetailMonitor(prev => (prev?.id === m.id ? { ...prev, ...res.data } : prev))
        setHistReload(k => k + 1)
        return { ok: true, data: res.data }
      }
      return { ok: false, error: res?.error || null, data: res?.data ?? null }
    })
  }

  async function deleteMonitor(m) {
    // Onay projenin diyaloğuyla alınır. `window.confirm` tarayıcı-varsayılanı bir kutu
    // çiziyordu (tasarım sistemi dışı) ve hedefin adını göstermiyordu; kart üzerindeki
    // tek tık yıkıcı bir işlem tetiklediği için mesaj NEYİN silineceğini söylemeli.
    // Türev satırda metin FARKLI olmalı: orada "sil" gerçekte "izlemeyi durdur"dur ve kayıt
    // envanterden türediği için listede kalır. Aynı metni kullanmak kullanıcıya yapılmayan bir
    // şeyi onaylatırdı.
    const derived = !m.standalone
    const ok = await showConfirm({
      title: t('mon.deleteTitle'),
      message: derived ? t('dns.deleteDerivedMsg', m.name || m.domain) : t('mon.deleteMsg', m.name || m.domain),
      confirmText: derived ? t('dns.deleteDerivedConfirm') : t('dns.delete'),
      cancelText: t('dns.cancel'),
      variant: 'danger',
    })
    if (!ok) return
    setDeleting(m.id)
    try {
      const res = await api.monitoring.deleteDnsMonitor(m.id)
      if (res?.success) { toast.success(derived ? t('dns.deletedDerived') : t('dns.deleted')); await load() }
      else toast.error(res?.error || 'Error')
    } finally {
      setDeleting(null)
    }
  }

  // Canlı DNS testi — kaydetmeden formdaki domain/kayıt-tipi ile bir kez çözer; URL girilse host ayıklanır.
  async function runTest() {
    if (!form.domain.trim()) return
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testDnsMonitor({
        domain: form.domain.trim(), recordType: form.recordType,
        expectedValue: (form.expectedValue || '').trim() || null,
        slowThresholdMs: form.slowThresholdMs === '' ? null : Number(form.slowThresholdMs),
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('dns.testError') })
    } finally {
      setTesting(false)
    }
  }

  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  /**
   * Kart durum sınıfı. HTTP/Port'tan farklı olarak DNS'te {@code status} alanı YOK:
   * "çözümlüyor mu" sorusunun cevabı alarmın varlığından okunur.
   */
  function cardClass(m) {
    if (m.active === false) return 'upt-card--unknown'
    return m.active_alarm ? 'upt-card--down' : 'upt-card--up'
  }

  /**
   * Kart durum rozeti — diğer sekiz türde olan, DNS'te EKSİK olan bilgi.
   *
   * <p>DNS kartı üst satırda hiçbir durum yazmıyordu: kartın rengi ve alarm ikonu dışında
   * "bu monitör iyi mi" sorusunun sözle cevabı yoktu (kullanıcı bildirdi). Yan etkisi de vardı —
   * rozet çizilmeyince üst satırda tek çocuk kalıyor ve `justify-content: space-between` onu
   * sola yaslıyordu, yani kayıt-tipi + bağlantı kopyalama kartın soluna düşüyordu.
   *
   * <p><b>Sözcükler UYDURULMADI:</b> istatistik şeridinin ve filtre çiplerinin sözlüğü aynen
   * kullanılır (`statOk`/`statAlarm`/`statPaused`) ve koşullar o filtrelerin koşullarıyla
   * BİREBİR aynıdır — "Alarmlı" çipine tıklayan kişi "Alarmlı" rozetli kartları görür.
   *
   * <p>Alarm dalında "çözümlenmiyor" DENMEZ: DNS alarmı beş tipten biri olabiliyor
   * (DNS_FAILURE / DNS_CHANGED / DNS_SLOW / DNS_UNEXPECTED / DNS_INCONSISTENT) ve kart
   * yanıtında tip yok — "çözümlenmiyor" bir DNS_CHANGED alarmında düpedüz yanlış olurdu.
   */
  function statusBadge(m) {
    const [cls, label] =
      m.active === false ? ['upt-badge--unknown', t('dns.statPaused')]
      : m.active_alarm   ? ['upt-badge--down',    t('dns.statAlarm')]
      : !m.checked_at    ? ['upt-badge--unknown', t('dns.statNeverChecked')]
      :                    ['upt-badge--up',      t('dns.statOk')]
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{label}</span>
  }

  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('dns.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
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

  // Grup seçenekleri — yüklü monitörlerden türetilir (takım-kapsamlı: admin hepsini, diğerleri kendi takımı) — ping/keyword deseni.
  // Filtre seçenekleri (grup/etiket) rol fark etmeksizin GÖRÜNEN listenin tamamından türer (2026-09-18,
  // kullanıcı isteği: filtreleme her yetkide). Sunucu zaten kapsamı uyguluyor; burada bir daha daraltmak
  // müdür/izleyici gibi çok takım gören rollerin başka takımın grubunu seçememesine yol açıyordu.
  const groupMonitors = monitors
  const groupNames = [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b))
  const hasGroupOptions = groupNames.length > 0
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = teamGroups.map(g => ({ value: g.name, label: g.name }))
  // Etiket filtresi: grupla aynı sözleşme ('all' / '__none__' / etiket). Seçenekler listedeki etiketlerden türer.
  const tagNames = useMemo(() => tagNamesOf(groupMonitors), [groupMonitors])
  // Kutu etiketsiz izleme varken de görünür: "Etiketsiz" seçeneği eski (etiketsiz) kayıtları bulmanın yolu.
  const hasTagOptions = tagNames.length > 0 || groupMonitors.some(m => !(m.tags || '').trim())
  const tagFilterOptions = useMemo(() => [{ value: 'all', label: t('mon.allTags') },
    ...tagNames.map(x => ({ value: x, label: x })),
    ...(groupMonitors.some(m => !(m.tags || '').trim()) ? [{ value: '__none__', label: t('mon.noTags') }] : [])],
    [tagNames, groupMonitors, t])
  const groupFilterOptions = [{ value: 'all', label: t('dns.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('dns.noGroup') }] : [])]

  // Takım + grup + arama kapsamı — istatistik kartlarının TABANI. statFilter BİLEREK dahil değil:
  // kartlar aynı zamanda filtre düğmesi, statFilter'a göre sayılsalardı seçili olmayan her kart 0
  // okur ve tıklanamaz hale gelirdi. (Kartlar ham `monitors`'dan sayılıyordu: kullanıcı bir takım
  // seçince liste daralıyor ama kartlar küresel sayıyı göstermeye devam ediyordu — "alarm 5" tıkla,
  // 2 sonuç gel. HttpMonitorPage deseni.)
  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    const s = search.toLowerCase()
    return m.domain?.toLowerCase().includes(s) || m.record_type?.toLowerCase().includes(s)
  }), [monitors, teamFilter, groupFilter, tagFilter, search])

  const dnsCounts = useMemo(() => ({
    total: scoped.length,
    ok: scoped.filter(m => m.active !== false && !m.active_alarm).length,
    alarm: scoped.filter(m => m.active_alarm).length,
    unacked: scoped.filter(m => m.active_alarm && !m.alarm_acknowledged).length,
    changed: scoped.filter(m => m.changed).length,
    paused: scoped.filter(m => m.active === false).length,
  }), [scoped])
  const statItems = [
    { key: 'total',   Icon: Network,        label: t('dns.statTotal'),    value: dnsCounts.total,   cls: 'total'    },
    { key: 'ok',      Icon: Check,          label: t('dns.statOk'),       value: dnsCounts.ok,      cls: 'valid'    },
    { key: 'alarm',   Icon: AlertTriangle,  label: t('dns.statAlarm'),    value: dnsCounts.alarm,   cls: 'critical' },
    { key: 'unacked', Icon: BellDot,        label: t('dns.statUnacked'),  value: dnsCounts.unacked, cls: 'warning'  },
    { key: 'changed', Icon: ArrowLeftRight, label: t('dns.statChanged'),  value: dnsCounts.changed, cls: 'alert'    },
    { key: 'paused',  Icon: Pause,          label: t('dns.statPaused'),   value: dnsCounts.paused,  cls: 'paused'   },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  // Listelenen küme = kapsam + kart filtresi (takım/grup/arama zaten `scoped`'ta uygulandı).
  const filtered = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    return scoped.filter(m => {
      if (statFilter === 'alarm' && !m.active_alarm) return false
      if (statFilter === 'ok' && (m.active === false || m.active_alarm)) return false
      if (statFilter === 'unacked' && !(m.active_alarm && !m.alarm_acknowledged)) return false
      if (statFilter === 'changed' && !m.changed) return false
      if (statFilter === 'paused' && m.active !== false) return false
      return true
    })
  }, [scoped, statFilter])

  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  const pager = usePagination(filtered, {
    listKey: 'dns-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: filtre/arama/sayfa + açık detay modalı (mtab/range DnsDetailModal içinde sync'lenir).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, search, statFilter, pager }),
    monitor: detailMonitor?.id ?? null,
    // Modal AÇIKKEN mtab/range'i DnsDetailModal yönetir (anahtarlar mapping'de olmaz → dokunulmaz);
    // modal kapanınca burada null'a düşer ve URL'den silinir (modal unmount'ta silme yapamaz).
    ...(detailMonitor ? {} : { mtab: null, range: null }),
  })

  return (
    <div className="dns-page">
      <div className="dns-header">
        <div className="dns-title-row">
          <Globe size={22} />
          <div>
            <h2 className="dns-title">{t('dns.title')}</h2>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginLeft: 'auto' }}>
          <span className="upt-last-check">{t('dns.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}</span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}><RefreshCw size={14} />{t('dns.refreshBtn')}</button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="dns" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('dns.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('dns.how1'), t('dns.how2'), t('dns.how3'), t('dns.how4'), t('dns.how5')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={filtered.length} />

      <div className={`dns-info-card${infoOpen ? ' dns-info-open' : ''}`}>
        <button className="dns-info-toggle" onClick={() => setInfoOpen(v => !v)} type="button">
          <Info size={16} />
          <span className="dns-info-title">{t('dns.infoTitle')}</span>
          <ChevronDown size={14} className={`dns-info-chevron${infoOpen ? ' open' : ''}`} />
        </button>
        {infoOpen && (
          <div className="dns-info-body">
            <p className="dns-info-intro">{t('dns.infoIntro')}</p>
            <div className="dns-info-grid">
              {INFO_ITEMS.map(item => (
                <div key={item.type} className="dns-info-row">
                  <span className="dns-info-key">{item.type}</span>
                  <span className="dns-info-desc">{t(item.descKey)}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <div className="dns-toolbar">
        {hasTeamOptions && (
          <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
        )}
        {hasGroupOptions && (
          <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />
        )}
        {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} />}
        <input
          className="dns-search-input"
          type="text"
          placeholder={t('dns.searchPlaceholder')}
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
        {search && (
          <button className="dns-search-clear" onClick={() => setSearch('')}>✕</button>
        )}
        <div className="dns-counter">{t('dns.monitorCount', filtered.length)}</div>
      </div>

      {loading ? (
        <LoadingBlock label={t('dns.loading')} fullWidth />
      ) : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <div className="mon-empty">{t('dns.noMonitors')}</div>
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow}
          api={{ update: api.monitoring.updateDnsMonitor, remove: api.monitoring.deleteDnsMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id}
              className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => setDetailMonitor(m)}>
              <div className="upt-card-top">
                {canManageRow(m) && (
                  <input type="checkbox" className="upt-card-check" checked={bulkSel.has(m.id)} onChange={() => toggleBulk(m.id)} onClick={(e) => e.stopPropagation()} aria-label={t('bulk.selectOne')} />
                )}
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.domain} />
                {m.standalone && (
                  <span className="dns-standalone-badge" title={t('dns.standaloneHint')}>{t('dns.standalone')}</span>
                )}
                <span className="upt-card-top-right">
                  <span className="upt-port-tag">{m.record_type}</span>
                  <CopyLinkButton iconOnly url={monitorDeepLink('dns', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.domain}>{m.domain}</div>
              <MonitorCardMeta monitor={m} />
              <MonitorSpark spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />
              <div className="upt-card-divider" />
              {/* DEĞİŞTİ/ROTASYON rozetleri kartın içinde kalır: DNS'te asıl sinyal "değer
                  değişti mi" sorusudur, tabloda da en görünür yerdeydi. */}
              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val dns-cell-mono" title={m.value || ''}>{truncateValue(m.value, 22)}</span>
                  <span className="upt-metric-lbl">
                    {m.changed
                      ? <span className="dns-changed-badge">{t('dns.changed')}</span>
                      : m.rotated
                        ? <span className="dns-rotated-badge" title={t('dns.rotationTitle')}>{t('dns.rotated')}</span>
                        : t('dns.currentValue')}
                  </span>
                </div>
                {m.ttl != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{m.ttl}s</span>
                    <span className="upt-metric-lbl">{t('dns.ttl')}</span>
                  </div>
                )}
                {m.response_ms != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{m.response_ms}ms</span>
                    <span className="upt-metric-lbl">{t('dns.responseMs')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDate(m.checked_at) : ''}</span>
                {/* Sinifsiz sarmalayici: MonitorCardActions kendi kokunu zaten
                    "mon-actions" yapiyor; ayni sinifi ic ice uygulamak gap/margin'i iki
                    kez sayip ScriptedMonitorPage'den farkli bir bosluk uretiyordu. */}
                <span onClick={e => e.stopPropagation()}>
                  {/* Silme kartta KALIR: tabloda vardı ve kaldırılması yetenek kaybı olurdu.
                      Artık ortak bileşenin içinde — dokuz türde tek düğme, tek stopPropagation. */}
                  {canManageRow(m) && (
                    <MonitorCardActions
                      running={isRunning(m.id)}
                      onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                      checkTitle={t('dns.check')} editTitle={t('dns.edit')}
                      onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                      deleting={deleting === m.id}
                      deleteTitle={m.standalone ? t('dns.delete') : t('dns.deleteDerivedTitle')} />
                  )}
                </span>
              </div>
            </div>
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {detailMonitor && (
        <DnsDetailModal monitor={detailMonitor} onClose={() => setDetailMonitor(null)} teamNames={teamNameById}
          canManage={canManageRow(detailMonitor)}
          running={isRunning(detailMonitor.id)}
          onCheck={canManageRow(detailMonitor) ? () => checkNow(detailMonitor) : undefined}
          onEdit={canManageRow(detailMonitor) ? () => openEdit(detailMonitor) : undefined}
          onDuplicate={canManageRow(detailMonitor) ? () => openDuplicate(detailMonitor) : undefined}
          onDelete={canDeleteRow(detailMonitor) ? () => deleteMonitor(detailMonitor) : undefined}
          deleting={deleting === detailMonitor.id}
          histReload={histReload} />
      )}

      {modal && (
        // Dış/overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet (keyword/ping ile aynı).
        <div className="modal-overlay">
          {/* İÇ KAYDIRMA ŞART (maxHeight + overflowY). Yoksa taşan içerik `.modal-overlay`in
              `overflow-y: auto`una düşüyor: kaydırma çubuğu modalın kenarında değil EKRANIN en
              sağında çıkıyor ve kaydırınca başlık da yukarı kayıyor. Diğer sekiz düzenleme
              modalı bunu taşıyordu, DNS taşımıyordu — kapı: modalScroll.test.jsx. */}
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()}
            style={{ maxWidth: 640, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--dns">
              <div className="modal-icon-hdr-badge">
                <Network size={20} />
              </div>
              <h3>{modal === 'new' ? t('dns.modalNew') : t('dns.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>
            {dupSource && <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>}
            <div className="form-grid form-grid--top">
              <label>
                <span>{t('dns.domain')} <span className="req-star">*</span></span>
                <input
                  value={form.domain}
                  onChange={e => setForm(f => ({ ...f, domain: e.target.value }))}
                  placeholder={t('dns.domainPlaceholder')}
                  autoFocus={modal === 'new' || !!dupSource}
                />
              </label>
              {(modal === 'new' || modal.standalone) && (
                <label>
                  <span>{t('dns.team')} <span className="req-star">*</span></span>
                  {canPickTeam
                    ? <SearchableSelect
                        value={form.teamId}
                        onChange={v => setForm(f => ({ ...f, teamId: v }))}
                        options={teamSelectOptions}
                        searchThreshold={2}
                      />
                    : <input value={teamName || t('app.noTeam')} disabled />}
                </label>
              )}
              <label>
                <span>{t('dns.name')}</span>
                <input
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                  placeholder={form.domain || (modal !== 'new' ? modal.domain : '')}
                />
              </label>
              <label>
                <span>{t('dns.group')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.groupName}
                  onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('dns.noGroup') }, ...groupSelectOptions]}
                  creatable
                  onCreate={() => {}}
                  searchThreshold={2}
                  placeholder={t('dns.noGroup')}
                />
              </label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <label>
                <span>{t('dns.recordType')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.recordType}
                  onChange={v => setForm(f => ({ ...f, recordType: v }))}
                  options={RECORD_TYPES.map(rt => ({ value: rt, label: rt }))}
                />
              </label>
              <label><span>{t('verify.attempts')}</span>
                <input type="number" min="0" max="10" value={form.confirmAttempts}
                  onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
              <label><span>{t('verify.attemptEvery')}</span>
                <input type="number" min="10" max="600" value={form.confirmIntervalSeconds}
                  onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
              <label><span>{t('verify.recoveryChecks')}</span>
                <input type="number" min="1" max="20" value={form.recoveryChecks}
                  onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
              <label><span>{t('verify.recoveryEvery')}</span>
                <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds}
                  onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
              <div className="full-width field-hint">ⓘ {t('verify.hint')}</div>
              <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
                onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
              {/* Etiketler — zorunlu (2026-09-18); Http/Port ile aynı blok */}
              <div className="full-width http-tags-block">
                <div className="http-block-title">{t('mon.tagsTitle')} <span className="req-star">*</span></div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('mon.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('mon.tagsPlaceholder')} />
              </div>
              <label>
                <span>{t('dns.slowThresholdField')}</span>
                <input
                  type="number" min="100" max="60000"
                  value={form.slowThresholdMs}
                  onChange={e => setForm(f => ({ ...f, slowThresholdMs: e.target.value }))}
                  placeholder={t('dns.slowThresholdPlaceholder')}
                />
                <span className="field-hint">{t('dns.slowThresholdHint')}</span>
              </label>
              <label className="full-width">
                <span className="dns-expected-label">
                  {t('dns.expectedValue')}
                  {modal !== 'new' && modal.value && (
                    <span className="dns-pin-btns">
                      <button type="button" className="dns-pin-btn"
                        onClick={() => setForm(f => ({ ...f, expectedValue: modal.value }))}>
                        {t('dns.pinCurrent')}
                      </button>
                      <button type="button" className="dns-pin-btn"
                        title={t('dns.addCurrentHint')}
                        onClick={() => setForm(f => {
                          // Mevcut değer(ler)i listeye EKLE (replace değil) — dedupe'lu; iki bilinen IP birden sabitlenebilir.
                          const existing = (f.expectedValue || '').split('\n').map(s => s.trim()).filter(Boolean)
                          const incoming = (modal.value || '').split('\n').map(s => s.trim()).filter(Boolean)
                          const merged = [...existing, ...incoming.filter(v => !existing.includes(v))]
                          return { ...f, expectedValue: merged.join('\n') }
                        })}>
                        {t('dns.addCurrent')}
                      </button>
                    </span>
                  )}
                </span>
                <textarea
                  rows={3}
                  value={form.expectedValue}
                  onChange={e => setForm(f => ({ ...f, expectedValue: e.target.value }))}
                  placeholder={t('dns.expectedPlaceholder')}
                />
                <span className="field-hint">{t('dns.expectedHint')}</span>
              </label>
              <label className="checkbox-label full-width">
                <input
                  type="checkbox"
                  checked={form.dnsChangeAlertEnabled}
                  onChange={e => setForm(f => ({ ...f, dnsChangeAlertEnabled: e.target.checked }))}
                />
                {t('dns.changeAlertEnabled')}
              </label>
              <span className="field-hint full-width">{t('dns.changeAlertHint')}</span>
              <label className="checkbox-label full-width">
                <input
                  type="checkbox"
                  checked={form.propagationCheck}
                  onChange={e => setForm(f => ({ ...f, propagationCheck: e.target.checked }))}
                />
                {t('dns.propagationCheck')}
              </label>
              <span className="field-hint full-width dns-prop-hint">{t('dns.propagationHint')}</span>

              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
                {t('dns.formActive')}
              </label>
            </div>
            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error
                  ? { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }
                  : (testResult.unexpected?.length || testResult.slow)
                    ? { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }
                    : { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }) }}>
                {(testResult.error || testResult.unexpected?.length || testResult.slow)
                  ? <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.error
                    ? <><strong>{t('dns.testError')}:</strong> {testResult.error}</>
                    : <>
                        <strong>{testResult.unexpected?.length ? t('dns.testUnexpected')
                          : testResult.slow ? t('dns.testSlow') : t('dns.testSuccess')}</strong>
                        {testResult.host && <> · {testResult.host}</>}
                        {testResult.values?.length > 0 && <> · {testResult.values.join(', ')}</>}
                        {testResult.ttl != null && <> · TTL {testResult.ttl}s</>}
                        {testResult.response_ms != null && <> · {testResult.response_ms}ms</>}
                      </>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="dns-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                disabled={testing || !form.domain.trim()}>
                <FlaskConical size={14} />{testing ? t('dns.testing') : t('dns.test')}
              </button>
              <button className="btn btn-secondary" onClick={closeEditModal}>{t('dns.cancel')}</button>
              <button
                className="btn btn-primary"
                onClick={save}
                disabled={saving || !form.recordType || !form.domain.trim() || ((modal === 'new' || modal?.standalone) && !form.teamId)}
              >
                {saving ? t('dns.saving') : t('dns.save')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Sayfa düzeyi toplu kontrol: önce takım seçimi, sonra akan sonuç tablosu.
          Depolama anahtarı TÜR BAŞINA ayrı — tek anahtar paylaşılsaydı buradaki seçim
          panonun sertifika seçimini ezerdi. */}
      {checkRun.pickerOpen && (
        <CheckTeamPicker
          buckets={monitorTeamBuckets(checkable)}
          storageKey="sm.checkRun.teams.dns"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="dns"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
