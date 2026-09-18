import { useState, useEffect, useCallback, useMemo, lazy, Suspense } from 'react'
import { dateLocale } from '../i18n/dateLocale.js'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useRunningChecks } from '../hooks/useRunningChecks.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
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
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TagInput from './ui/TagInput.jsx'
import NotifyChannels from './ui/NotifyChannels.jsx'
import IntervalSlider from './ui/IntervalSlider.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import { RefreshCw, Plus, Trash2, CalendarClock, FlaskConical, Check, AlertTriangle, LayoutDashboard, CheckCircle2, TriangleAlert, HelpCircle, ShieldAlert, Building2, Activity, Calendar, Inbox } from 'lucide-react'
import { useModalScrollHint } from '../hooks/useModalScrollHint.js'
import ModalScrollHint from './ui/ModalScrollHint.jsx'
import { duplicateName } from '../utils/duplicateName.js'
import AlertHistory from './admin/AlertHistory.jsx'
import { alertTypesFor } from '../utils/monitorAlertTypes.js'
import DomainRegistrationTab from './DomainRegistrationTab.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import DomainExpiryTrace from './DomainExpiryTrace.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import MonitorStatsSection from './MonitorStatsSection.jsx'
import { matchesTeamAndGroup, monitorUrlState, matchesTag, tagNamesOf, matchesGroupOrTagText } from '../utils/monitorFilters.js'
import MonitorCardMeta from './MonitorCardMeta.jsx'
import MonitorCardActions from './MonitorCardActions.jsx'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import ChangeNoteField from './history/ChangeNoteField.jsx'
import { useEscapeKey } from '../hooks/useEscapeKey.js'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))
const ChangeHistoryTab = lazy(() => import('./history/ChangeHistoryTab.jsx'))

const REFRESH_INTERVAL = 60
const SORTS = ['days_asc', 'days_desc', 'name']
// Domain kaydi gunde birkac kez sorgulanir; taban SAAT olcegindedir (WHOIS/RDAP nezaketi).
// Diger turlerdeki dakika olcegi burada anlamsiz olurdu — bu yuzden liste TURE OZEL.
const INTERVALS = [
  { value: 3600,  labelKey: 'notify.iv1h'  },
  { value: 21600, labelKey: 'notify.iv6h'  },
  { value: 43200, labelKey: 'notify.iv12h' },
  { value: 86400, labelKey: 'notify.iv24h' },
]

const emptyForm = {
  name: '', domain: '', groupName: '', tags: '', notificationGroupId: '', teamId: '',
  thresholdsCsv: '60,30,14,7,3,1', warningDays: 30, criticalDays: 7, intervalSeconds: 86400, active: true,
  checkTimeoutMs: '',
  // Koruma anahtarlari: kilit ve degisiklik ACIK (bugunku fiili davranis), kara liste KAPALI
  // (her kontrolde dis DNS sorgusu uretir — bilincli acilmali).
  transferLockAlert: true, blacklistEnabled: false, changeAlert: true,
  notifyEmail: true, notifyWebhook: true, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30,
}

/** URL yapıştırılmış girdiyi host'a indirger: https://www.x.com.tr/path → www.x.com.tr
 *  (şema/path/query/userinfo/port soyulur). Backend otoritedir; bu yalnız anlık UX normalizasyonu. */
function normalizeDomainInput(s) {
  if (!s) return ''
  return s.trim().replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split(/[/?#]/)[0]
    .split('@').pop().split(':')[0].replace(/\.$/, '').toLowerCase()
}

/** Bitiş tarihi gösterimi — hem WHOIS date-only ("2029-10-26") hem RDAP datetime ("...Z") güvenli. */
function fmtExpiry(iso) {
  if (!iso) return '—'
  const s = String(iso).length <= 10 ? iso + 'T00:00:00Z' : (iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z')
  const d = new Date(s)
  return isNaN(d.getTime()) ? String(iso).substring(0, 10)
    : d.toLocaleDateString(dateLocale(), { year: 'numeric', month: '2-digit', day: '2-digit' })
}

/** .tr WHOIS kaynak anahtarı → okunur etiket (cevabı hangi kaynak verdi). */
const WHOIS_PROVIDER_LABEL = { isimtescil: 'isimtescil.net', trabis: 'trabis.gov.tr', trabis43: 'whois:43' }
/** Kaynak rozeti metni: WHOIS ise ve sağlayıcı biliniyorsa "WHOIS · isimtescil.net", değilse ham kaynak. */
function sourceTag(source, provider) {
  if (!source) return null
  const p = provider && WHOIS_PROVIDER_LABEL[provider]
  return (source === 'WHOIS' && p) ? `WHOIS · ${p}` : source
}

export default function DomainMonitorPage({ systemRole, teamId, teamName, myTeams = [] }) {
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
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [selected, setSelected] = useState(null)
  useEscapeKey(!!selected, closeDetail)   // Escape ile kapat (QA ISSUE-002, 2026-09-13; ModalShell'e taşınmamış detay modalı)
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
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
  const [diag, setDiag] = useState(null)   // Sorun Tanıla modalı: { domain, loading?, data?, error? }
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [tagFilter, setTagFilter] = useState(() => readUrlParam('tag', 'all'))   // etiket filtresi (2026-09-18)
  const [sortBy, setSortBy] = useState(() => readUrlParam('sort', 'days_asc'))
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
      const res = await api.monitoring.getDomainMonitors()
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
    concurrency: CHECK_CONCURRENCY_BY_TYPE.domain,
  })

  // Koşum sırasında 60 sn'lik tazeleme DURUR: ortada gelen bir load() satırları sunucu anlık
  // görüntüsüyle değiştirip listeyi yeniden sıralar, kullanıcının baktığı kart zıplardı.
  // Koşum bitince ms 0'dan geri dönerken hook bir kez tetiklenir → merge edilmiş satırların
  // üzerine kanonik sunucu verisi gelir (panodaki açık yeniden çekmenin karşılığı).
  useVisibleInterval(load, checkRun.running ? 0 : REFRESH_INTERVAL * 1000)   // görünürlük-farkındalıklı: gizli sekmede polling durur

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'domain').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da gizli sekmede durur

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.domain) })
  }, [])

  useMonitorDeepLink(monitors, openDetail)

  function openDetail(m) { setSelected(m); setDetailTab('control') }
  function closeDetail() { setSelected(null) }

  function openNew() {
    setTestResult(null); setDupSource(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      warningDays: defaults?.warningDays ?? emptyForm.warningDays,
      criticalDays: defaults?.criticalDays ?? emptyForm.criticalDays,
      thresholdsCsv: defaults?.thresholds ?? emptyForm.thresholdsCsv })
    setModal('new')
  }
  /** Monitör (snake_case) → form state eşlemesi. Edit ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
  function formFrom(m) {
    return { name: m.name || '', domain: m.domain || '', groupName: m.group_name || '', tags: m.tags || '', notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '',
      teamId: m.team_id != null ? String(m.team_id) : '',
      thresholdsCsv: m.thresholds_csv || '60,30,14,7,3,1',
      warningDays: m.warning_days ?? 30, criticalDays: m.critical_days ?? 7,
      intervalSeconds: m.interval_seconds ?? 86400, active: m.active !== false,
      notifyEmail: m.notify_email !== false,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      checkTimeoutMs: m.check_timeout_ms ?? '',
      transferLockAlert: m.transfer_lock_alert !== false,
      blacklistEnabled: m.blacklist_enabled === true,
      changeAlert: m.change_alert !== false, notifyWebhook: m.notify_webhook !== false }
  }
  function openEdit(m) {
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m))
    setChangeNote('')
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız alan adını değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.domain) })
    setModal('new')
  }
  function closeEdit() { setModal(null); setTestResult(null); setDupSource(null); setChangeNote('') }

  async function runTest() {
    if (!form.domain.trim()) return
    setTesting(true); setTestResult(null)
    try {
      const res = await api.monitoring.testDomain({
        domain: normalizeDomainInput(form.domain), warningDays: Number(form.warningDays), criticalDays: Number(form.criticalDays),
      })
      setTestResult(res?.success ? res.data : { error: res?.error || t('dom.testError'), status: 'UNKNOWN' })
    } finally {
      setTesting(false)
    }
  }

  async function save() {
    if (!form.domain.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    if (!form.groupName?.trim()) { toast.error(t('mon.groupRequired')); return }   // grup + etiket zorunlu (2026-09-18)
    if (!form.tags?.trim()) { toast.error(t('mon.tagsRequired')); return }
    setSaving(true)
    try {
      const payload = {
        // Serbest metin isimler korunur (backend URL'li isimleri host'a indirger); boşsa normalize domain.
        name: form.name.trim() || normalizeDomainInput(form.domain), domain: normalizeDomainInput(form.domain),
        groupName: form.groupName?.trim() || null, tags: form.tags?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        notificationGroupId: form.notificationGroupId === '' || form.notificationGroupId == null
          ? null : Number(form.notificationGroupId),
        thresholdsCsv: form.thresholdsCsv?.trim() || '60,30,14,7,3,1',
        warningDays: Number(form.warningDays), criticalDays: Number(form.criticalDays),
        intervalSeconds: Number(form.intervalSeconds), active: form.active,
        notifyEmail: form.notifyEmail,
        confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
        recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
        checkTimeoutMs: form.checkTimeoutMs === '' || form.checkTimeoutMs == null ? null : Number(form.checkTimeoutMs),
        transferLockAlert: !!form.transferLockAlert,
        blacklistEnabled: !!form.blacklistEnabled,
        changeAlert: !!form.changeAlert,
        notifyWebhook: !!form.notifyWebhook,
      }
      // Not yalnız YAZILDIYSA gönderilir — boş alan payload'a girmez.
      if (changeNote.trim()) payload.changeNote = changeNote.trim()
      const res = modal === 'new'
        ? await api.monitoring.createDomainMonitor(payload)
        : await api.monitoring.updateDomainMonitor(modal.id, payload)
      await load(); setSaving(false)
      if (!res?.success) { toast.error(res?.error || 'Error'); return }
      // Sunucu alan adini KAYITLI alan adina (eTLD+1) indirger: kayit bilgisi bir HOST'a degil
      // alan adinin kendisine aittir (RDAP/WHOIS'te www.x.com diye bir kayit yoktur). Alan
      // altindaki ipucu bunu yaziyor ama surpriz KAYDETTIKTEN sonra yasaniyor: kullanici
      // "www yazdim, silindi" diye okuyor. Indirgeme olduysa SUNUCUNUN dondurdugu degerle
      // soylenir — kural ikinci kez (bu kez JS'te) yazilmaz, kopyalar kaciniilmaz olarak ayrisir.
      const savedDomain = res?.data?.domain
      const typed = normalizeDomainInput(form.domain)
      if (savedDomain && typed && savedDomain !== typed) {
        toast.success(t('dom.savedReduced').replace('{0}', typed).replace('{1}', savedDomain))
      } else {
        toast.success(t('dom.saved'))
      }
      closeEdit()
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

      message: t('mon.deleteMsg', m.name || m.domain),

      confirmText: t('dom.delete'),

      cancelText: t('dom.cancel'),

      variant: 'danger',

    })

    if (!ok) return

    setDeleting(m.id)
    try {

      const res = await api.monitoring.deleteDomainMonitor(m.id)

      setDeleting(null)

      if (!res?.success) { toast.error(res?.error || t('mon.deleteError')); return }

      toast.success(t('dom.deleted'))

      await load()
    } finally {
      setDeleting(null)
    }
  }


  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deleteDomainMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('dom.deleted')); closeEdit()
  }

  async function checkNow(m) {
    // DÖNÜŞ DEĞERİ toplu koşum içindir: satırın ✓/✕ tik'ini ve hata metnini o belirler.
    // Tekil çağıran (kart/modal düğmesi) sonucu yok sayar — davranışı değişmez.
    return track(m.id, async () => {
      const res = await api.monitoring.triggerDomainCheck(m.id)
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

  async function diagnose(m) {
    setDiag({ domain: m.domain, loading: true })
    try {
      const res = await api.admin.runDomainExpiryDiagnostics(m.domain)
      setDiag(res?.success ? { domain: m.domain, data: res.data } : { domain: m.domain, error: res?.error || t('dexp.error') })
    } catch (e) {
      setDiag({ domain: m.domain, error: e?.message || t('dexp.error') })
    }
  }

  const { teamOptions, hasTeamOptions } = useTeamOptions(monitors)
  const teamSelectOptions = useMemo(() => [...(isAdmin ? [{ value: '', label: t('dom.noTeam') }] : []),   // "takımsız" yalnız admin: üye için takım zorunlu (2026-09-18)
    ...pickTeams.map(tm => ({ value: String(tm.id), label: tm.name }))], [isAdmin, pickTeams, t])
  // Değişiklik geçmişi `teamId` farkını ADA çevirebilsin — çıplak sayı okunmuyor.
  const teamNameById = useMemo(
    () => Object.fromEntries(teams.map(tm => [tm.id, tm.name])), [teams])
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(isOwnTeam)),   // ikincil takımlar da dâhil (2026-09-18)
    [monitors, isAdmin, isOwnTeam])
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
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('dom.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('dom.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])
  const sortOptions = useMemo(() => SORTS.map(s => ({ value: s, label: t('dom.sort_' + s) })), [t])

  const scoped = useMemo(() => monitors.filter(m => {
    if (!matchesTeamAndGroup(m, teamFilter, groupFilter)) return false
    if (!matchesTag(m, tagFilter)) return false
    if (matchesGroupOrTagText(m, search)) return true   // grup adı / etiket metni de aranır (2026-09-18)
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return (m.domain || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q) || (m.registrar || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, tagFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, ok: 0, warning: 0, critical: 0, unknown: 0, changed: 0 }
    for (const m of scoped) {
      const s = m.status
      if (s === 'OK') c.ok++
      else if (s === 'WARNING') c.warning++
      else if (s === 'CRITICAL') c.critical++
      else c.unknown++
      if (m.changed) c.changed++
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    let list = scoped
    if (statFilter && statFilter !== 'total') {
      const pred = {
        ok: m => m.status === 'OK', warning: m => m.status === 'WARNING',
        critical: m => m.status === 'CRITICAL', unknown: m => m.status !== 'OK' && m.status !== 'WARNING' && m.status !== 'CRITICAL',
        changed: m => m.changed,
      }[statFilter]
      if (pred) list = list.filter(pred)
    }
    const dv = (m) => (m.days_remaining == null ? (sortBy === 'days_asc' ? 1e9 : -1e9) : m.days_remaining)
    const sorted = [...list]
    if (sortBy === 'days_asc') sorted.sort((a, b) => dv(a) - dv(b))
    else if (sortBy === 'days_desc') sorted.sort((a, b) => dv(b) - dv(a))
    else sorted.sort((a, b) => (a.domain || '').localeCompare(b.domain || ''))
    return sorted
  }, [scoped, statFilter, sortBy])

  // Sayfalama filtrelenmiş listenin ÜZERİNE. İstatistik kartları ise KAPSAM listesinden
  // (`scoped` = takım + grup + arama) sayılır; kart filtresi (statFilter) sayima GIRMEZ.
  // Kartlar ham `monitors` uzerinden sayilirsa filtre secilince liste daralir ama kartlar
  // kuresel sayiyi gostermeye devam eder (DNS/Port sayfalarinda tam bu olmustu).
  const pager = usePagination(displayMonitors, {
    listKey: 'domain-monitors', resetDeps: [search, teamFilter, groupFilter, tagFilter, statFilter, sortBy],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    ...monitorUrlState({ teamFilter, groupFilter, tagFilter, search, statFilter, pager }),
    sort: sortBy !== 'days_asc' ? sortBy : null,
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',    Icon: LayoutDashboard,  label: t('dom.dashTotal'),    value: counts.total,    cls: 'total'    },
    { key: 'ok',       Icon: CheckCircle2,     label: t('dom.dashOk'),       value: counts.ok,       cls: 'valid'    },
    { key: 'warning',  Icon: TriangleAlert,    label: t('dom.dashWarning'),  value: counts.warning,  cls: 'warning'  },
    { key: 'critical', Icon: ShieldAlert,      label: t('dom.dashCritical'), value: counts.critical, cls: 'critical' },
    { key: 'unknown',  Icon: HelpCircle,       label: t('dom.dashUnknown'),  value: counts.unknown,  cls: 'high'     },
    { key: 'changed',  Icon: Activity, label: t('dom.dashChanged'),  value: counts.changed,  cls: 'error'    },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  function statusCls(s) {
    if (s === 'OK') return 'up'
    if (s === 'WARNING') return 'warn'
    if (s === 'CRITICAL') return 'down'
    return 'unknown'
  }
  function statusLabel(s) {
    return s === 'OK' ? t('dom.stOk') : s === 'WARNING' ? t('dom.stWarning') : s === 'CRITICAL' ? t('dom.stCritical') : t('dom.stUnknown')
  }
  function statusBadge(m) {
    const c = statusCls(m?.status)
    return <span className={`upt-badge upt-badge--${c}`}><span className="upt-badge-dot" />{statusLabel(m?.status)}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={`${t('dom.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`}><AlertTriangle size={14} /></span>
  }
  function daysColor(d) {
    if (d == null) return 'var(--text-muted)'
    if (d < 0) return '#c0392b'
    if (d <= 7) return '#dc2626'
    if (d <= 30) return '#e07b00'
    return 'var(--text)'
  }

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('dom.title')}</h2>
          <p className="upt-subtitle">{t('dom.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">{t('dom.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}</span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}><RefreshCw size={14} />{t('dom.refresh')}</button>
          <CheckAllButton count={checkable.length} running={checkRun.running}
            done={checkRun.run?.rows.length ?? 0} total={checkRun.run?.total ?? 0}
            onClick={checkRun.openPicker} />
          <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
          <MonitorGuideButton type="domain" />
          {canWrite && <button className="btn btn-sm btn-primary" onClick={openNew}><Plus size={14} />{t('dom.addMonitor')}</button>}
        </div>
      </div>

      <MonitorHowBox bullets={[t('dom.how1'), t('dom.how2'), t('dom.how3'), t('dom.how4'), t('dom.how5'), t('dom.how6'), t('dom.how7')]} />

      <MonitorStatsSection
        loading={loading} total={monitors.length}
        statsVisible={statsVisible} onToggle={toggleStats}
        items={statItems} activeFilter={statFilter}
        onStatClick={onStatClick} onClearFilter={() => setStatFilter(null)}
        shownCount={displayMonitors.length} />

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          <SearchableSelect value={sortBy} onChange={setSortBy} options={sortOptions} />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTagOptions && <SearchableSelect value={tagFilter} onChange={setTagFilter} options={tagFilterOptions} searchThreshold={2} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('dom.searchPlaceholder')} value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : loadError && monitors.length === 0 ? (
        <AlertBanner tone="danger" title={t('mon.loadError')} role="alert"
          actions={<button className="btn btn-sm btn-secondary" onClick={load}>{t('hist.retry')}</button>}>
          {String(loadError)}
        </AlertBanner>
      ) : monitors.length === 0 ? (
        <StatusBlock tone="neutral" icon={Inbox} title={canWrite ? t('dom.noMonitorsAdmin') : t('dom.noMonitors')} description={canWrite ? t('empty.hintMonitorsAdmin') : t('empty.hintMonitors')} />
      ) : (
        <>
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id} className={`upt-card upt-card--${statusCls(m.status)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.domain} />
                {m.changed && <span className="dom-changed-ico" title={t('dom.changedTip')}><Activity size={13} /></span>}
                <span className="upt-card-top-right">
                  {m.source && <span className="upt-port-tag" title={m.whois_provider ? t('dom.sourceVia') : undefined}>{sourceTag(m.source, m.whois_provider)}</span>}
                  <CopyLinkButton iconOnly url={monitorDeepLink('domain', m.id)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>
              <div className="upt-card-domain" title={m.domain}>{m.domain}</div>
              {m.registrar && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2, wordBreak: 'break-word' }}>
                  <Building2 size={12} />{m.registrar}
                </div>
              )}
              <MonitorCardMeta monitor={m} />
              <div className="upt-card-divider" />
              <div className="dom-hero">
                <div className="dom-hero-number" style={{ color: daysColor(m.days_remaining) }}>{m.days_remaining == null ? '—' : Math.abs(m.days_remaining)}</div>
                <div className="dom-hero-label">{m.days_remaining != null && m.days_remaining < 0 ? t('dom.expiredAgo') : t('dom.daysLeft')}</div>
                <div className="dom-hero-expiry"><Calendar size={12} /><span>{t('dom.expiresShort')} {fmtExpiry(m.expiry_date)}</span></div>
              </div>
              {Array.isArray(m.status_codes) && m.status_codes.length > 0 && (
                <div className="dom-epp-row">
                  {m.status_codes.slice(0, 4).map(sc => <span key={sc} className="dom-epp-chip">{sc}</span>)}
                  {m.status_codes.length > 4 && <span className="dom-epp-chip">+{m.status_codes.length - 4}</span>}
                </div>
              )}
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <MonitorCardActions
                    running={isRunning(m.id)}
                    onCheck={() => checkNow(m)} onEdit={() => openEdit(m)} onDuplicate={() => openDuplicate(m)}
                    checkTitle={t('dom.check')} editTitle={t('dom.edit')}
                    onDelete={canDeleteRow(m) ? () => deleteMonitor(m) : undefined}
                    deleting={deleting === m.id} deleteTitle={t('dom.delete')} />
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
          <div className={`upt-modal upt-modal--${statusCls(selected.status)}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.domain}</span>
              </div>
              {/* Hızlı eylemler KARTIN aynısı (MonitorModalActions): detayı açan kişi kontrol
                  koşturmak ya da ayarı düzeltmek için modalı kapatıp karta dönmesin. Yetki
                  kapıları da kartla birebir — modal ayrı bir yetki yüzeyi DEĞİL. */}
              <MonitorModalActions
                running={isRunning(selected.id)}
                onCheck={canManageRow(selected) ? () => checkNow(selected) : undefined}
                checkTitle={t('dom.check')}
                onEdit={canManageRow(selected) ? () => openEdit(selected) : undefined}
                editTitle={t('dom.edit')}
                onDuplicate={canManageRow(selected) ? () => openDuplicate(selected) : undefined}
                onDelete={canDeleteRow(selected) ? () => deleteMonitor(selected) : undefined}
                deleting={deleting === selected.id}
                deleteTitle={t('dom.delete')}
                onClose={closeDetail}>
                <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              </MonitorModalActions>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ color: daysColor(selected.days_remaining) }}>{selected.days_remaining ?? '—'}</span><span className="upt-modal-metric-lbl">{t('dom.daysLeft')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{fmtExpiry(selected.expiry_date)}</span><span className="upt-modal-metric-lbl">{t('dom.expiry')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ fontSize: '.8em' }}>{selected.registrar || '—'}</span><span className="upt-modal-metric-lbl">{t('dom.registrar')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ fontSize: '.8em' }}>{sourceTag(selected.source, selected.whois_provider) || '—'}</span><span className="upt-modal-metric-lbl">{t('dom.source')}</span></div>
              <div className="upt-modal-metric"><span className={selected.ns_resolves === false ? 'kw-off' : 'kw-on'}>{selected.ns_resolves == null ? '—' : selected.ns_resolves ? t('dom.on') : t('dom.off')}</span><span className="upt-modal-metric-lbl">{t('dom.nsResolves')}</span></div>
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('dom.lastCheck')}</span></div>}
            </div>
            {Array.isArray(selected.status_codes) && selected.status_codes.length > 0 && (
              <div className="dom-epp-row" style={{ padding: '0 4px 6px' }}>
                {selected.status_codes.map(sc => <span key={sc} className="dom-epp-chip">{sc}</span>)}
              </div>
            )}
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'registration' ? ' active' : ''}`} onClick={() => setDetailTab('registration')}>{t('dom.tabRegistration')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('dom.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('dom.tabGuide')}</button>
              {/* Yapılandırma geçmişi — kontrol geçmişiyle (ilk sekme) KARIŞTIRILMAMALI:
                  orası "hedef ayakta mıydı", burası "ayarları kim değiştirdi". */}
              <button className={`modal-tab${detailTab === 'changes' ? ' active' : ''}`} onClick={() => setDetailTab('changes')}>{t('chg.tab')}</button>
            </div>

            {detailTab === 'control' && (<>
              {isAdmin && (
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => diagnose(selected)}>
                    <ShieldAlert size={13} />{t('dexp.diagnose')}
                  </button>
                </div>
              )}
              <CheckHistoryTab kind="domain" monitorId={selected.id} listKey="domain-history" reloadSignal={histReload}
                presets={[7, 30, 90, 365]} defaultPreset={30} gridClass="dom-rt-grid"
                columns={[t('dom.colTime'), t('dom.colSource'), t('dom.colExpiry'),
                  t('dom.daysLeft'), t('dom.colStatus'), t('dom.registrar'), t('dom.colIps')]}
                renderRow={(c) => {
                  const cDays = c.days_remaining
                  const cIps = (Array.isArray(c.resolved_ips) ? c.resolved_ips : String(c.resolved_ips ?? '').split(',')).map(s => String(s).trim()).filter(Boolean)
                  return (<>
                    <span className="upt-rt-time">{formatDateSec(c.checked_at)}</span>
                    <span>{sourceTag(c.source, c.whois_provider) || '—'}</span>
                    <span>{fmtExpiry(c.expiry_date)}</span>
                    <span style={{ color: daysColor(cDays), fontWeight: 600 }}>{cDays ?? '—'}</span>
                    <span className={`dom-st dom-st--${statusCls(c.status)}`}>{statusLabel(c.status)}{c.changed ? ' ⚑' : ''}</span>
                    <span title={c.registrar} style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.registrar || (c.error ? c.error : '—')}</span>
                    <span title={cIps.join(', ')} style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{cIps.length ? cIps.join(', ') : '—'}</span>
                  </>)
                }} />
            </>)}

            {detailTab === 'registration' && <DomainRegistrationTab monitor={selected} />}
            {detailTab === 'alerts' && <AlertHistory domain={selected.domain} types={alertTypesFor('domain')} />}
            {detailTab === 'notes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <MonitorNotes type="DOMAIN" target={selected.domain} />
              </Suspense>
            )}

            {detailTab === 'changes' && (
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}>
                <ChangeHistoryTab t={t} kind="domain" monitorId={selected.id} teamNames={teamNameById}
                  canManage={canManageRow(selected)} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box modal-sticky-actions" onClick={e => e.stopPropagation()} style={{ maxWidth: 640, width: '92vw' }}>
            <div className="modal-icon-hdr modal-icon-hdr--domain">
              <div className="modal-icon-hdr-badge"><CalendarClock size={20} /></div>
              <h3>{modal === 'new' ? t('dom.modalNew') : t('dom.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
            </div>
            <div className="modal-scroll-body" ref={scrollHint.ref}>

            {dupSource
              ? <div className="mon-dup-hint">{t('mon.duplicateHint')}</div>
              : <div className="http-type-banner"><CalendarClock size={16} /><span>{t('dom.typeInfo')}</span></div>}

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('dom.domain')} <span className="req-star">*</span></span>
                <input value={form.domain} placeholder="example.com" autoFocus onChange={e => setForm(f => ({ ...f, domain: e.target.value }))}
                  onBlur={e => { const n = normalizeDomainInput(e.target.value); if (n !== e.target.value) setForm(f => ({ ...f, domain: n })) }} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.domainHint')}</div>

              <label><span>{t('dom.name')}</span>
                <input value={form.name} placeholder={form.domain} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('dom.team')} <span className="req-star">*</span></span>
                {canPickTeam
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('dom.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('dom.group')} <span className="req-star">*</span></span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('dom.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('dom.noGroup')} /></label>
              <NotifyChannels
                notifyEmail={form.notifyEmail} notifyWebhook={form.notifyWebhook}
                onChange={patch => setForm(f => ({ ...f, ...patch }))}
                teamLabel={selectedTeamLabel} teamId={form.teamId}
                groupId={form.notificationGroupId}
                onGroupChange={v => setForm(f => ({ ...f, notificationGroupId: v }))} />
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.groupInfo')}</div>
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

              <label><span>{t('dom.warningDays')}</span>
                <input type="number" min="1" value={form.warningDays} onChange={e => setForm(f => ({ ...f, warningDays: Number(e.target.value) }))} /></label>
              <label><span>{t('dom.criticalDays')}</span>
                <input type="number" min="1" value={form.criticalDays} onChange={e => setForm(f => ({ ...f, criticalDays: Number(e.target.value) }))} /></label>
              <label className="full-width"><span>{t('dom.thresholds')}</span>
                <input value={form.thresholdsCsv} placeholder="60,30,14,7,3,1" onChange={e => setForm(f => ({ ...f, thresholdsCsv: e.target.value }))} /></label>
              <label className="full-width"><span>{t('dom.checkTimeout')}</span>
                <input type="number" min="1000" max="30000" step="500" value={form.checkTimeoutMs}
                  placeholder={t('dom.checkTimeoutPh')}
                  onChange={e => setForm(f => ({ ...f, checkTimeoutMs: e.target.value }))} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.checkTimeoutHint')}</div>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('dom.active')}</label>
              <div className="full-width field-hint">{t('dom.unknownHint')}</div>

              {/* Koruma anahtarlari. Sure bitisi BILEREK toggle DEGIL: bizde esik alanlariyla
                  (uyari/kritik gun + esik listesi) zaten var ve acik/kapali bir anahtar onu
                  fakirlestirirdi. */}
              <div className="full-width form-section-header">{t('dom.alarmSettings')}</div>
              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.transferLockAlert}
                  onChange={e => setForm(f => ({ ...f, transferLockAlert: e.target.checked }))} />
                {t('dom.transferLockAlert')}</label>

              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.transferLockHint')}</div>

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.blacklistEnabled}
                  onChange={e => setForm(f => ({ ...f, blacklistEnabled: e.target.checked }))} />
                {t('dom.blacklistEnabled')}</label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.blacklistHint')}</div>

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.changeAlert}
                  onChange={e => setForm(f => ({ ...f, changeAlert: e.target.checked }))} />
                {t('dom.changeAlert')}</label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.changeAlertHint')}</div>
            </div>

            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error || testResult.status === 'UNKNOWN'
                  ? { background: '#f8fafc', borderColor: '#e2e8f0', color: '#475569' }
                  : testResult.status === 'OK'
                    ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                    : { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }) }}>
                {testResult.status === 'OK' ? <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} /> : <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  <strong>{statusLabel(testResult.status)}</strong>
                  {testResult.days_remaining != null && <> — {testResult.days_remaining} {t('dom.daysLeft')}</>}
                  {testResult.expiry_date && <> · {fmtExpiry(testResult.expiry_date)}</>}
                  {testResult.registrar && <> · {testResult.registrar}</>}
                  {testResult.source && testResult.source !== 'NONE' && <> · {testResult.source}</>}
                  {(testResult.error || testResult.status === 'UNKNOWN') && <> · {testResult.error || t('dom.noData')}</>}
                </span>
              </div>
            )}
            {/* Yalnız DÜZENLEMEDE: "neden" sorusu ancak var olan bir şey değişince anlamlı. */}
            {modal !== 'new' && (
              <ChangeNoteField t={t} id="domain-change-note" value={changeNote} onChange={setChangeNote} />
            )}
            </div>
            <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
            <div className="modal-actions">
              <span style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
                <button className="btn btn-secondary" onClick={runTest} disabled={testing || !form.domain.trim()}>
                  <FlaskConical size={14} />{testing ? t('dom.testing') : t('dom.test')}
                </button>
                {isAdmin && (
                  <button className="btn btn-secondary" onClick={() => diagnose({ domain: normalizeDomainInput(form.domain) })} disabled={!form.domain.trim()}>
                    <ShieldAlert size={14} />{t('dexp.diagnose')}
                  </button>
                )}
              </span>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('dom.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('dom.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.domain.trim() || !form.teamId}>{saving ? '...' : t('dom.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ── Sorun Tanıla (Alan Adı Süre Bitişi Tanılama) Modal — en son portal: diğer modalların ÜSTÜNde durur ── */}
      {diag && createPortal(
        <div className="modal-overlay" onClick={() => setDiag(null)}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 660, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--domain">
              <div className="modal-icon-hdr-badge"><ShieldAlert size={20} /></div>
              <h3>{t('dexp.diagnose')} — {diag.domain}</h3>
            </div>
            {diag.loading && <LoadingBlock label={t('dexp.running')} className="upt-modal-loading" />}
            {diag.error && <div className="alert-msg alert-msg--err">{diag.error}</div>}
            {diag.data && <DomainExpiryTrace data={diag.data} />}
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setDiag(null)}>{t('dom.cancel')}</button>
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
          storageKey="sm.checkRun.teams.domain"
          descText={t('mon.checkAllTeamDesc')}
          totalText={(n) => t('mon.checkAllTeamTotal', n)}
          emptyText={t('mon.checkAllTeamEmpty')}
          onClose={checkRun.closePicker}
          onStart={(keys, label) => { checkRun.closePicker(); checkRun.start(keys, label) }} />
      )}
      <MonitorCheckRunModal run={checkRun.run} type="domain"
        onCancel={checkRun.cancel} onClose={checkRun.close} />
    </div>
  )
}
