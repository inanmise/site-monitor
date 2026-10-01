import { useState, useEffect, useCallback, useMemo, useId, Fragment } from 'react'
import { sortMonitorsDefault } from '../utils/monitorSort.js'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import MonitorCheckRunModal from './check/MonitorCheckRunModal.jsx'
import CheckTeamPicker, { monitorTeamBuckets } from './check/CheckTeamPicker.jsx'
import { CHECK_CONCURRENCY_BY_TYPE } from './check/monitorCheckColumns.jsx'
import { useCheckRun } from '../hooks/useCheckRun.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { useToast } from './ui/Toast.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'
import { useDialog } from './ui/Dialog.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorSpark from './ui/MonitorSpark.jsx'
import BulkActionBar from './ui/BulkActionBar.jsx'
import NocNotifyField from './noc/forms/NocNotifyField.jsx'
import { nocIdsFrom, nocGroupIdsBody } from './noc/forms/nocFormModel.js'
import CardDensityToggle from './ui/CardDensityToggle.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'
import { useSparklines, useSla } from '../hooks/useSparklines.js'
import MonitorCardActions from './MonitorCardActions.jsx'
import MonitorPageHeader from './monitoring/MonitorPageHeader.jsx'
import { ChevronDown, Info, Network, AlertTriangle, FlaskConical, Check, Pause, BellDot, ArrowLeftRight, Copy, Inbox, X } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import DnsDetailModal from './DnsDetailModal.jsx'
import DnsMonitorCard from './dns/DnsMonitorCard.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TagInput from './ui/TagInput.jsx'
import { useMonitorTeamPick } from '../hooks/useMonitorTeamPick.js'
import { useMonitorResume } from '../hooks/useMonitorResume.js'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import { LoadingBlock } from './ui/Progress.jsx'

import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Field as ShadcnField, FieldDescription, FieldLabel } from '@/components/shadcn/field'
import { Input } from '@/components/shadcn/input'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Textarea } from '@/components/shadcn/textarea'
import { MonitorStatusBadge, CARD_CHECK } from './monitoring/MonitorCard.jsx'
import {
  MonitorFormModal, FormNoTeamAlert, FormGrid, FormField, CheckField, FormSection, FormHint,
} from './monitoring/MonitorForm.jsx'
import { cn } from '@/lib/utils'
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

const emptyForm = { name: '', domain: '', recordType: 'A', intervalSeconds: 300, teamId: '', groupName: '', tags: '', notificationGroupId: '', nocNotify: false, nocGroupIds: [], expectedValue: '', slowThresholdMs: '', propagationCheck: false, dnsChangeAlertEnabled: true, notifyEmail: true, alertLevel: 'WARNING', confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, notifyWebhook: true, active: true }

export default function DnsMonitorPage({ systemRole, teamId, teamName, myTeams = [], globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'   // USER ve üstü: kendi takımı için standalone DNS ekler
  const [teams, setTeams] = useState([])   // hook'tan ÖNCE tanımlı olmalı (TDZ)
  // Takım seçimi + "kendi takımı" kapısı artık ÜYESİ olunan tüm takımlar (2026-09-18); hook 9 sayfada ortak.
  const { canPickTeam, pickTeams, isOwnTeam, defaultTeamId, defaultTeamName, teamless } = useMonitorTeamPick({ isAdmin, adminTeams: teams, myTeams, teamId, teamName })
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
  // 2026-09-29: + sunucunun satır bayrağı `can_check` (tetik ucunun kapısıyla AYNI kural — kapsamlı yönetici görebildiği
  // ama çalıştıramadığı başka takım satırını "Şimdi Kontrol Et (N)" sayısına katmaz, toplu koşumda 403 yemez).
  const canCheckRow = (m) => canManageRow(m) && m?.can_check !== false
  // Silme SEMANTİĞİ hâlâ standalone'a göre ayrışır (standalone → gerçek silme; envanter-türevi →
  // pasifleştirme, envanter senkronu yeniden açabilir); ayrışan yalnız DAVRANIŞ, yetki değil.
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  // Toplu seçim (2026-09-12, #13): kart kutucuğu; yalnız yönetebildiği satırlar seçilebilir
  const [bulkSel, setBulkSel] = useState(() => new Set())
  const toggleBulk = (id) => setBulkSel((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const sparks = useSparklines('dns')   // kart mini trendi (2026-09-12)
  const sla = useSla('dns')   // 30 günlük kullanılabilirlik / hedef (2026-09-12, #11)
  // Kart yoğunluğu (2026-09-27): her açılışta Zengin; Kompakt seçimi SAKLANMAZ (yalnız sayfada kalındıkça geçerli)
  const [density, setDensity] = useCardDensity('dns')
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [detailMonitor, setDetailMonitor] = useState(null)
  // Modaldan koşturulan kontrol Kontrol Geçmişi sekmesini de tazelesin (diğer sekiz türle aynı):
  // sekmenin kendi 30 sn'lik canlı yenilemesi 1. sayfa dışında ve özel aralıkta KAPALI.
  const [histReload, setHistReload] = useState(0)
  const [modal, setModal] = useState(null)
  const fe = useFormErrors(modal)   // doğrulama hataları alanın altında + ilk hatalıya kaydırma (2026-09-30)
  const expectedId = useId()   // "beklenen değer" alanı: etiket ↔ metin kutusu ↔ ipucu bağı
  // Opsiyonel "değişiklik nedeni" — form nesnesine DEĞİL ayrı tutulur: taslak/kirlilik
  // karşılaştırması form üzerinden yapılıyor ve not bir ayar değil, tek seferlik açıklama.
  const [changeNote, setChangeNote] = useState('')
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
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
  // Duraklatılmış kartta / detayda tek tıkla "Sürdür" (2026-09-26, tüm izleme sayfalarında varsayılan): toplu işlem
  // çubuğuyla aynı yazma yolu ({ active: true }); açık detay penceresinin kopyası da etkin olarak işaretlenir.
  const { resume, isResuming } = useMonitorResume(api.monitoring.updateDnsMonitor, (r) => {
    load(); setDetailMonitor((cur) => (cur && cur.id === r.id ? { ...cur, active: true } : cur))
  })

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
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'dns').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.teamId).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [modal, form.teamId])

  // Yeni monitör için varsayılan kontrol aralığı (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.dns) })
  }, [])

  // Derin bağlantı ?monitor=<id> (e-posta CTA, 7/24 Kapsamı): TAM listeden açar; yoksa uyarır (hooks/useMonitorDeepLink)
  useMonitorDeepLink(monitors, setDetailMonitor, {
    loaded: !loading && !loadError, onNotFound: () => toast.error(t('deepLink.notFound')),
    onEdit: openEdit, canEdit: canManageRow, nocType: 'DNS',   // open=noc: 7/24 Kapsamı "7/24 ayarını düzenle"
  })

  // Ortak bildirim blogunun "kime gidecek" satiri icin hedef takim adi (HttpMonitorPage deseni).
  const selectedTeamLabel = canPickTeam
    ? (pickTeams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (defaultTeamName || t('app.noTeam'))
  const teamSelectOptions = [...(isAdmin ? [{ value: '', label: t('app.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))]

  function openNew() {
    setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (defaultTeamId != null ? String(defaultTeamId) : ''),
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
      notifyEmail: m.notify_email !== false, alertLevel: m.alert_level || 'WARNING',
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      teamId: m.team_id != null ? String(m.team_id) : '',
      groupName: m.group_name || '', tags: m.tags || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: nocIdsFrom(m.noc_group_ids),
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
    // Doğrulama hataları ALANIN ALTINDA + ilk hatalıya kaydırma (2026-09-30) — tost yok, kullanıcı hatayı aramaz.
    if (fe.check({
      domain: !form.domain?.trim() && t('mon.fieldRequired'),
      teamId: (modal === 'new' || modal?.standalone) && (form.teamId === '' || form.teamId == null) && t('mon.teamRequired'),
      groupName: !form.groupName?.trim() && t('mon.groupRequired'),   // grup + etiket zorunlu (2026-09-18)
      tags: !form.tags?.trim() && t('mon.tagsRequired'),
    })) return
    setSaving(true)
    try {
      const isNew = modal === 'new'
      const payload = {
        name: (form.name || '').trim(),
        recordType: form.recordType,
        intervalSeconds: form.intervalSeconds,
        notifyEmail: form.notifyEmail, alertLevel: form.alertLevel || 'WARNING',
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        expectedValue: (form.expectedValue || '').trim(),
        slowThresholdMs: form.slowThresholdMs === '' ? null : Number(form.slowThresholdMs),
        groupName: form.groupName?.trim() || null, tags: form.tags?.trim() || null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        nocNotify: !!form.nocNotify, nocGroupIds: nocGroupIdsBody(form.nocGroupIds),   // 7/24 izleme ekibi (2026-09-27)
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
      // İlk / taze kontrol (2026-09-28): yeni kart boş kalmasın, alan adı / kayıt tipi değişen kart eski değeri
      // göstermesin. Liste YÜKLENDİKTEN sonra başlar → kart ızgarada, dönen göstergeyle bekler (bkz. utils/checkAfterSave).
      if (shouldCheckAfterSave('dns', { isNew, before: modal, after: res.data })) startCheckAfterSave(checkNow, res.data)
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

  /**
   * Kart / detay kenarı durum anahtarı (up | down | unknown). HTTP/Port'tan farklı olarak DNS'te
   * {@code status} alanı YOK: "çözümlüyor mu" sorusunun cevabı alarmın varlığından okunur.
   */
  const statusKey = (m) => (m.active === false ? 'unknown' : m.active_alarm ? 'down' : 'up')

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
  function statusBadge(m, { lastKnown = false } = {}) {
    // Kartta (`lastKnown`) duraklatılmış izleme SON BİLİNEN durumunu gösterir — paylaşılan kural
    // (monitoring/MonitorCard: MonitorStatusBadge duraklatılmış kartta gri çerçeveye döner, "Duraklatıldı"
    // alt çubuktaki MonitorPausedBadge'de). Eskiden rozet de "Duraklatıldı" yazıyordu → kartta iki kez
    // (2026-09-26). Detay penceresinde (kart dışı) duraklatma bilgisi rozette kalır.
    const [key, label] =
      m.active === false && !lastKnown ? ['unknown', t('dns.statPaused')]
      : m.active_alarm   ? ['down',    t('dns.statAlarm')]
      : !m.checked_at    ? ['unknown', t('dns.statNeverChecked')]
      :                    ['up',      t('dns.statOk')]
    return <MonitorStatusBadge status={key}>{label}</MonitorStatusBadge>
  }
  const alarmLabel = (m) => `${t('dns.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`

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
  // Varsayılan kart sırası (2026-10-01): sorunlu önce → grup adı A→Z (grup içinde ad) → grupsuzlar ada göre
  const orderedMonitors = useMemo(() => sortMonitorsDefault(filtered, 'dns'), [filtered])
  const pager = usePagination(orderedMonitors, {
    listKey: 'dns-monitors', preset: 'page', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter],
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

  // Canlı test sonucu uyarı tonunda mı (beklenmeyen değer / yavaş yanıt) — ton ve ikon aynı kararı paylaşır.
  const testWarn = !!(testResult && !testResult.error && (testResult.unexpected?.length || testResult.slow))

  // ── Ekle / Düzenle formu ── (örtü tıklaması ve Escape KAPATMAZ — veri kaybı önlenir; bkz. MonitorFormModal)
  // İÇ KAYDIRMA ortak pencerenin sözleşmesi (MonitorFormModal → ui/ModalShell scrollBody): eskiden DNS bunu
  // taşımıyordu ve kaydırma çubuğu ekranın en sağında çıkıyordu — kapı: modalScroll.test.jsx.
  const formModal = modal && (
    <MonitorFormModal onClose={closeEditModal} icon={Network} width={640}
      title={modal === 'new' ? t('dns.modalNew') : t('dns.modalEdit')}
      duplicate={!!dupSource} busy={saving}
      // Meşgul evresi BAŞLIKTA (Kaydediliyor… / Test ediliyor… N sn): alt bardaki düğme metinleri sabit kalır, hiçbir düğme kaymaz (2026-09-19, envanter formuyla aynı desen).
      busyLabel={saving ? t('mon.saving') : testing ? t('dns.testing') : null}
      footer={<>
        <Button variant="secondary" className="mr-auto" onClick={runTest}
          aria-busy={testing || undefined} disabled={testing || !form.domain.trim()}>
          <FlaskConical size={14} />{t('dns.test')}
        </Button>
        <Button variant="secondary" onClick={closeEditModal}>{t('dns.cancel')}</Button>
        <Button onClick={save} aria-busy={saving || undefined}
          disabled={saving || !form.recordType || !form.domain.trim() || ((modal === 'new' || modal?.standalone) && !form.teamId)}>
          {t('dns.save')}
        </Button>
      </>}>
      {dupSource && <AlertBanner tone="info" icon={Copy}>{t('mon.duplicateHint')}</AlertBanner>}
      {modal === 'new' && teamless && <FormNoTeamAlert />}
      <FormGrid>
        <FormField label={t('dns.domain')} required {...fe.fieldProps('domain')}>
          {({ id }) => (
            <Input id={id} value={form.domain} onChange={e => { setForm(f => ({ ...f, domain: e.target.value })); fe.clear('domain') }}
              placeholder={t('dns.domainPlaceholder')} autoFocus={modal === 'new' || !!dupSource} />
          )}
        </FormField>
        {(modal === 'new' || modal.standalone) && (
          <FormField label={t('dns.team')} required {...fe.fieldProps('teamId')}>
            {({ id }) => canPickTeam
              ? <SearchableSelect id={id} value={form.teamId} onChange={v => { setForm(f => ({ ...f, teamId: v })); fe.clear('teamId') }}
                  options={teamSelectOptions} searchThreshold={2} />
              : <Input id={id} value={defaultTeamName || t('app.noTeam')} disabled />}
          </FormField>
        )}
        <FormField label={t('dns.name')}>
          {({ id }) => (
            <Input id={id} value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              placeholder={form.domain || (modal !== 'new' ? modal.domain : '')} />
          )}
        </FormField>
        <FormField label={t('dns.group')} required {...fe.fieldProps('groupName')}>
          {({ id }) => (
            <SearchableSelect id={id} value={form.groupName} onChange={v => { setForm(f => ({ ...f, groupName: v })); fe.clear('groupName') }}
              options={[{ value: '', label: t('dns.noGroup') }, ...groupSelectOptions]}
              creatable onCreate={() => {}} searchThreshold={2} placeholder={t('dns.noGroup')} />
          )}
        </FormField>
        <NotifyChannels
          notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
          alertLevel={form.alertLevel} onAlertLevelChange={v => setForm(f => ({ ...f, alertLevel: v }))}
          onChange={patch => setForm(f => ({ ...f, ...patch }))}
          teamLabel={selectedTeamLabel} teamId={form.teamId}
          groupId={form.notificationGroupId}
          onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
        <NocNotifyField type="DNS" checked={form.nocNotify} groupIds={form.nocGroupIds} canOpenSettings={globalAdmin}
          onChange={patch => setForm(f => ({ ...f, ...patch }))} />
        <FormField label={t('dns.recordType')} required>
          {({ id }) => (
            <SearchableSelect id={id} value={form.recordType} onChange={v => setForm(f => ({ ...f, recordType: v }))}
              options={RECORD_TYPES.map(rt => ({ value: rt, label: rt }))} />
          )}
        </FormField>
        <FormField label={t('verify.attempts')}>
          {({ id }) => <Input id={id} type="number" min="0" max="10" value={form.confirmAttempts}
            onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('verify.attemptEvery')}>
          {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.confirmIntervalSeconds}
            onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('verify.recoveryChecks')}>
          {({ id }) => <Input id={id} type="number" min="1" max="20" value={form.recoveryChecks}
            onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} />}
        </FormField>
        <FormField label={t('verify.recoveryEvery')}>
          {({ id }) => <Input id={id} type="number" min="10" max="600" value={form.recoveryIntervalSeconds}
            onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} />}
        </FormField>
        <FormHint>ⓘ {t('verify.hint')}</FormHint>
        <IntervalSlider options={INTERVALS} value={form.intervalSeconds}
          onChange={v => setForm(f => ({ ...f, intervalSeconds: v }))} />
        {/* Etiketler — zorunlu (2026-09-18); Http/Port ile aynı blok */}
        <FormSection title={t('mon.tagsTitle')} required {...fe.fieldProps('tags')} hint={t('mon.tagsHint')}>
          <TagInput value={form.tags} onChange={v => { setForm(f => ({ ...f, tags: v })); fe.clear('tags') }} placeholder={t('mon.tagsPlaceholder')} suggestions={teamTags} />
        </FormSection>
        <FormField label={t('dns.slowThresholdField')} hint={t('dns.slowThresholdHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} type="number" min="100" max="60000"
              value={form.slowThresholdMs}
              onChange={e => setForm(f => ({ ...f, slowThresholdMs: e.target.value }))}
              placeholder={t('dns.slowThresholdPlaceholder')} />
          )}
        </FormField>
        {/* Beklenen değer(ler) — etiket satırının sağında "şu anki değer" kısayolları (yalnız düzenlemede,
            çözümlenmiş bir değer varken). Düğmeler etiketin DIŞINDA: eskiden <label>'ın içindeydi. */}
        <ShadcnField role={undefined} className="min-w-0 gap-1.5 sm:col-span-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <FieldLabel htmlFor={expectedId} className="font-semibold">{t('dns.expectedValue')}</FieldLabel>
            {modal !== 'new' && modal.value && (
              <span className="inline-flex flex-wrap gap-1.5">
                <Button type="button" variant="outline" size="sm"
                  onClick={() => setForm(f => ({ ...f, expectedValue: modal.value }))}>
                  {t('dns.pinCurrent')}
                </Button>
                <SimpleTooltip content={t('dns.addCurrentHint')}>
                  <Button type="button" variant="outline" size="sm"
                    onClick={() => setForm(f => {
                      // Mevcut değer(ler)i listeye EKLE (replace değil) — dedupe'lu; iki bilinen IP birden sabitlenebilir.
                      const existing = (f.expectedValue || '').split('\n').map(s => s.trim()).filter(Boolean)
                      const incoming = (modal.value || '').split('\n').map(s => s.trim()).filter(Boolean)
                      const merged = [...existing, ...incoming.filter(v => !existing.includes(v))]
                      return { ...f, expectedValue: merged.join('\n') }
                    })}>
                    {t('dns.addCurrent')}
                  </Button>
                </SimpleTooltip>
              </span>
            )}
          </div>
          <Textarea id={expectedId} rows={3} aria-describedby={`${expectedId}-h`}
            value={form.expectedValue}
            onChange={e => setForm(f => ({ ...f, expectedValue: e.target.value }))}
            placeholder={t('dns.expectedPlaceholder')} />
          <FieldDescription id={`${expectedId}-h`} className="text-xs">{t('dns.expectedHint')}</FieldDescription>
        </ShadcnField>
        <CheckField full checked={form.dnsChangeAlertEnabled}
          onCheckedChange={v => setForm(f => ({ ...f, dnsChangeAlertEnabled: v }))}
          label={t('dns.changeAlertEnabled')} hint={t('dns.changeAlertHint')} />
        <CheckField full checked={form.propagationCheck}
          onCheckedChange={v => setForm(f => ({ ...f, propagationCheck: v }))}
          label={t('dns.propagationCheck')} hint={t('dns.propagationHint')} />
        <CheckField checked={form.active} onCheckedChange={v => setForm(f => ({ ...f, active: v }))} label={t('dns.formActive')} />
      </FormGrid>

      {testResult && (
        <AlertBanner className="mt-3"
          tone={testResult.error ? 'danger' : testWarn ? 'warning' : 'success'}
          icon={testResult.error || testWarn ? AlertTriangle : undefined}
          title={testResult.error ? t('dns.testError')
            : testResult.unexpected?.length ? t('dns.testUnexpected')
            : testResult.slow ? t('dns.testSlow') : t('dns.testSuccess')}>
          {testResult.error
            ? testResult.error
            : [testResult.host,
                testResult.values?.length > 0 && testResult.values.join(', '),
                testResult.ttl != null && `TTL ${testResult.ttl}s`,
                testResult.response_ms != null && `${testResult.response_ms}ms`].filter(Boolean).join(' · ')}
        </AlertBanner>
      )}
      {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
      {modal !== 'new' && (
        <ChangeNoteField t={t} id="dns-change-note" value={changeNote} onChange={setChangeNote} />
      )}
    </MonitorFormModal>
  )

  return (
    <div className="dns-page">
      <MonitorPageHeader type="dns" title={t('dns.title')} subtitle={t('dns.subtitle')}
        count={loading ? null : monitors.length}
        refreshIn={REFRESH_INTERVAL - secondsSince} onRefresh={load} refreshing={loading}
        check={{ count: checkable.length, running: checkRun.running, done: checkRun.run?.rows.length ?? 0, total: checkRun.run?.total ?? 0, onOpen: checkRun.openPicker }}
        canWrite={canWrite} onNew={openNew} newLabel={t('dns.addMonitor')} />

      <MonitorHowBox bullets={[t('dns.how1'), t('dns.how2'), t('dns.how3'), t('dns.how4'), t('dns.how5')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={filtered.length} />

      {/* DNS temel bilgiler — açılır bilgi kartı (shadcn Collapsible + Card; kapalıyken içerik DOM'da yok) */}
      <Collapsible open={infoOpen} onOpenChange={setInfoOpen}>
        <Card className="mb-3.5 gap-0 overflow-hidden py-0 shadow-none">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost"
              className="h-auto w-full justify-start gap-2.5 rounded-none px-4 py-3 text-left font-normal whitespace-normal">
              <Info size={16} aria-hidden="true" className="shrink-0 text-primary" />
              <span className="flex-1 font-semibold">{t('dns.infoTitle')}</span>
              <ChevronDown size={14} aria-hidden="true"
                className={cn('shrink-0 transition-transform motion-reduce:transition-none', infoOpen && 'rotate-180')} />
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent className="border-t bg-muted/30 px-4 pt-3 pb-3.5">
            <p className="mb-3.5 text-sm leading-relaxed">{t('dns.infoIntro')}</p>
            <dl className="m-0 grid grid-cols-[70px_1fr] items-start gap-x-4 gap-y-2 text-[13px]">
              {INFO_ITEMS.map(item => (
                <Fragment key={item.type}>
                  <dt><Badge variant="outline" className="w-full rounded-sm bg-card font-mono font-bold text-primary">{item.type}</Badge></dt>
                  <dd className="m-0 leading-normal">{t(item.descKey)}</dd>
                </Fragment>
              ))}
            </dl>
          </CollapsibleContent>
        </Card>
      </Collapsible>

      <div className="dns-toolbar flex-wrap">
        {/* Kart görünümü seçicisi satırın İLK öğesi (mr-auto): süzgeçler + arama sağda kalır; telefonda satır sarar */}
        <CardDensityToggle value={density} onChange={setDensity} className="mr-auto" />
        {hasTeamOptions && (
          <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} ariaLabel={t('flt.team')} />
        )}
        {hasGroupOptions && (
          <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} ariaLabel={t('flt.group')} />
        )}
        {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} ariaLabel={t('flt.tag')} />}
        {/* Arama — shadcn InputGroup: doluysa sağda "temizle" düğmesi */}
        <InputGroup className="w-full sm:w-auto sm:max-w-[360px] sm:min-w-[200px] sm:flex-1">
          <InputGroupInput
            type="text"
            placeholder={t('dns.searchPlaceholder')}
            aria-label={t('dns.searchPlaceholder')}
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
          {search && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-sm" onClick={() => setSearch('')} aria-label={t('app.clear')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        <span className="text-sm text-muted-foreground">{t('dns.monitorCount', filtered.length)}</span>
      </div>

      {loading ? (
        <LoadingBlock label={t('dns.loading')} fullWidth />
      ) : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<Button variant="secondary" size="sm" onClick={load}>{t('hist.retry')}</Button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={t('dns.noMonitors')} />
      ) : (
        <>
        <BulkActionBar selected={bulkSel} items={pager.pageItems.filter(canManageRow)} teams={teams} canDelete={canDeleteRow} nocType="DNS"
          api={{ update: api.monitoring.updateDnsMonitor, remove: api.monitoring.deleteDnsMonitor }}
          onClear={() => setBulkSel(new Set())} onDone={load}
          onToggleAll={() => setBulkSel((s) => { const vis = pager.pageItems.filter(canManageRow); const all = vis.every((m) => s.has(m.id)); return all ? new Set() : new Set(vis.map((m) => m.id)) })} />
        <div className="upt-grid" data-density={density}>
          {pager.pageItems.map(m => (
            /* DNS kartı (dns/DnsMonitorCard, 2026-09-27): değer paneli kartın kalbi (çoklu değer, değişti/rotasyon/
               beklenmeyen değer), kaynak rozeti (bağımsız / envanterden). Yetkiye, seçime ve eylemlere bağlı ortak
               parçalar BURADA kurulur ve yuva olarak geçer — toplu seçim kutusu, meta, mini trend, kart eylemleri
               (türev satırda silme = "izlemeyi durdur"). Durum sözlüğü detay penceresiyle ortak (statusKey/statusBadge). */
            <DnsMonitorCard key={m.id} monitor={m} canEdit={canManageRow(m)} status={statusKey(m)} density={density} running={isRunning(m.id)}
              statusBadge={statusBadge(m, { lastKnown: true })} alarmLabel={alarmLabel(m)}
              onOpen={() => setDetailMonitor(m)}
              select={canManageRow(m) && (
                <Checkbox className={CARD_CHECK} checked={bulkSel.has(m.id)} onCheckedChange={() => toggleBulk(m.id)} aria-label={t('bulk.selectOneFor', m.domain)} />
              )}
              meta={<MonitorCardMeta monitor={m} />}
              spark={<MonitorSpark rowLabel={m.domain} spark={sparks[String(m.id)]} sla={sla.data[String(m.id)]} slaTarget={sla.target} slaDays={sla.days} />}
              actions={canManageRow(m) && (
                <MonitorCardActions onResume={() => resume(m)} resuming={isResuming(m.id)} rowLabel={m.domain}
                  running={isRunning(m.id)}
                  onCheck={canCheckRow(m) ? () => checkNow(m) : undefined} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                  checkTitle={t('dns.check')} editTitle={t('dns.edit')}
                  onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                  deleting={deleting === m.id}
                  deleteTitle={m.standalone ? t('dns.delete') : t('dns.deleteDerivedTitle')} />
              )} />
          ))}
        </div>
        <PaginationBar {...pager} />
        </>
      )}

      {/* Detay penceresi (DnsDetailModal → MonitorDetailModal / ui/ModalShell). Açıkken düzenleme formu
          ONUN İÇİNDE çizilir → form detay penceresinin üstünde katmanlanır. */}
      {detailMonitor && (
        <DnsDetailModal monitor={detailMonitor} onClose={() => setDetailMonitor(null)} teamNames={teamNameById}
          status={statusKey(detailMonitor)} badge={statusBadge(detailMonitor)}
          canManage={canManageRow(detailMonitor)}
          running={isRunning(detailMonitor.id)}
          onCheck={canCheckRow(detailMonitor) ? () => checkNow(detailMonitor) : undefined}
          onEdit={canManageRow(detailMonitor) ? () => openEdit(detailMonitor) : undefined}
          onDuplicate={canManageRow(detailMonitor) ? () => openDuplicate(detailMonitor) : undefined}
          onDelete={canDeleteRow(detailMonitor) ? () => deleteMonitor(detailMonitor) : undefined}
          deleting={deleting === detailMonitor.id}
          onResume={canManageRow(detailMonitor) && !detailMonitor.active ? () => resume(detailMonitor) : undefined}
          resuming={isResuming(detailMonitor.id)}
          histReload={histReload}>
          {formModal}
        </DnsDetailModal>
      )}
      {!detailMonitor && formModal}

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
