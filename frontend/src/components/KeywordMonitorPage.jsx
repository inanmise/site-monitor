import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import MonitorGuideButton from './ui/MonitorGuideButton.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import TagInput from './ui/TagInput.jsx'
import { Play, Pencil, Copy, X, RefreshCw, Plus, Trash2, Target, Users, Layers, FlaskConical, Check, AlertTriangle,
  LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, BarChart3, ChevronDown, ShieldCheck,
  Mail, MessageSquare, Phone, Smartphone } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import AlertHistory from './admin/AlertHistory.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin (eager bundle'a girmesin).
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))

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
const emptyForm = { name: '', url: '', keyword: '', operator: 'GTE', matchCount: 1, groupName: '', teamId: '',
  caseSensitive: false, tags: '', notifyEmail: true,
  checkSslErrors: false, sslExpiryReminders: false, domainExpiryReminders: false,
  sslReminderDays: '30,14,7', domainReminderDays: '30,14,7',
  slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 60, timeoutMs: 10000, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, customHeaders: '', active: true }

export default function KeywordMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'      // USER ve üstü: kendi takımı için oluştur/düzenle/kontrol
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam
  const canManageRow = (m) => isAdmin || isOwnTeam(m)                    // düzenle + kontrol (kendi takımı)
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))   // silme: TEAM_ADMIN/ADMIN
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [rangeDays, setRangeDays] = useState(1)
  const [summary, setSummary] = useState({ total: 0, down: 0 })
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [defaults, setDefaults] = useState(null)   // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [showCacheHelp, setShowCacheHelp] = useState(false)   // cache busting açıklama modal'ı
  const [advOpen, setAdvOpen] = useState(false)               // "Gelişmiş ayarlar" accordion
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('control')
  const [search, setSearch] = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter] = useState(() => readUrlParam('team', 'all'))
  const [groupFilter, setGroupFilter] = useState(() => readUrlParam('group', 'all'))
  const [statFilter, setStatFilter] = useState(() => { const v = readUrlParam('stat', null); return v === 'total' ? null : v })
  const [statsVisible, setStatsVisible] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const deepLinkDone = useRef(false)

  const load = useCallback(async () => {
    const res = await api.monitoring.getKeywordMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false); setSecondsSince(0)
  }, [])

  useVisibleInterval(load, REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur

  // Form açıkken seçili takımın + bu türün gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'keyword').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
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
  useEffect(() => {
    if (deepLinkDone.current || monitors.length === 0) return
    deepLinkDone.current = true
    let id
    try { id = new URLSearchParams(window.location.search).get('monitor') } catch { return }
    if (!id) return
    const m = monitors.find(x => String(x.id) === String(id))
    if (m) openDetail(m)
    // monitor paramı artık kalıcı (useUrlQuerySync yazar/siler) — eski replaceState temizliği kaldırıldı.
  }, [monitors]) // eslint-disable-line react-hooks/exhaustive-deps

  async function loadHistory(id, days = rangeDays) {
    setHistoryLoading(true)
    const res = await api.monitoring.getKeywordHistory(id, { days })
    if (res?.success) {
      setHistory(res.data?.checks ?? [])
      setSummary({ total: res.data?.total ?? 0, down: res.data?.down ?? 0 })
    }
    setHistoryLoading(false)
  }
  function selectRange(id, days) { setRangeDays(days); histPager.setPage(1); loadHistory(id, days) }
  function openDetail(m) { setSelected(m); setHistory([]); histPager.setPage(1); setDetailTab('control'); loadHistory(m.id, rangeDays) }
  function closeDetail() { setSelected(null); setHistory([]) }

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
      operator: m.operator || 'GTE', matchCount: m.match_count ?? 1, groupName: m.group_name || '',
      teamId: m.team_id != null ? String(m.team_id) : '',
      caseSensitive: !!m.case_sensitive, tags: m.tags || '', notifyEmail: m.notify_email !== false,
      checkSslErrors: !!m.check_ssl_errors, sslExpiryReminders: !!m.ssl_expiry_reminders, domainExpiryReminders: !!m.domain_expiry_reminders,
      sslReminderDays: m.ssl_reminder_days || '30,14,7', domainReminderDays: m.domain_reminder_days || '30,14,7',
      slowResponseEnabled: !!m.slow_response_enabled, slowThresholdMs: m.slow_threshold_ms ?? 3000,
      intervalSeconds: m.interval_seconds ?? 60, timeoutMs: m.timeout_ms ?? 10000,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30, recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30, customHeaders: m.custom_headers || '',
      active: m.active !== false }
  }
  function openEdit(m) {
    setTestResult(null); setDupSource(null)
    setForm(formFrom(m))
    setModal(m)
  }
  /** Kopyala: kaynağın birebir kopyası, YENİ kayıt modunda (create). Ad "(Kopya)" sonekli;
   *  kullanıcı genelde yalnız URL'i değiştirip kaydeder. Mükerrer koruması backend'de. */
  function openDuplicate(m) {
    setTestResult(null); setDupSource(m)
    setForm({ ...formFrom(m), name: duplicateName(m.name || m.url) })
    setModal('new')
  }
  function closeEdit() { setModal(null); setTestResult(null); setDupSource(null) }

  // Canlı koşul testi — kaydetmeden formdaki değerlerle URL'yi çekip koşulu değerlendirir.
  async function runTest() {
    if (!form.url.trim() || !form.keyword.trim()) return
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testKeyword({
      url: normalizeUrl(form.url), keyword: form.keyword, operator: form.operator,
      matchCount: Number(form.matchCount), timeoutMs: Number(form.timeoutMs),
      customHeaders: form.customHeaders?.trim() || null, caseSensitive: form.caseSensitive,
    })
    setTestResult(res?.success ? res.data : { error: res?.error || t('keyword.testError') })
    setTesting(false)
  }

  async function save() {
    if (!form.url.trim() || !form.keyword.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    setSaving(true)
    const payload = {
      name: (form.name || form.url).trim(), url: normalizeUrl(form.url), keyword: form.keyword,
      operator: form.operator, matchCount: Number(form.matchCount),
      groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
      caseSensitive: form.caseSensitive, tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail,
      checkSslErrors: form.checkSslErrors, sslExpiryReminders: form.sslExpiryReminders, domainExpiryReminders: form.domainExpiryReminders,
      sslReminderDays: form.sslReminderDays?.trim() || '30,14,7', domainReminderDays: form.domainReminderDays?.trim() || '30,14,7',
      slowResponseEnabled: form.slowResponseEnabled, slowThresholdMs: Number(form.slowThresholdMs),
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds), recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      customHeaders: form.customHeaders?.trim() || null,
      active: form.active,
    }
    const res = modal === 'new'
      ? await api.monitoring.createKeywordMonitor(payload)
      : await api.monitoring.updateKeywordMonitor(modal.id, payload)
    await load(); setSaving(false)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('keyword.saved')); closeEdit()
  }

  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deleteKeywordMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('keyword.deleted')); closeEdit()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerKeywordCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id, rangeDays) }
    }
    setChecking(null)
  }

  // Türetilmiş listeler memoize — 1sn countdown her saniye render tetikler; bu O(n)
  // hesaplar her tıkta değil yalnız bağımlılık değişince çalışsın.
  const teamOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [monitors, t])
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('keyword.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  // Gruplar takıma özgüdür: kullanıcı yalnız kendi takımının gruplarını görür/seçer (admin tümünü).
  // Yeni grup creatable ile yazılıp seçilebilir (mevcut grup olmasa bile).
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)),
    [monitors, isAdmin, myTeam])
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('keyword.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('keyword.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  // Form içi grup dropdown'ı takım+tür kapsamlı endpoint'ten (liste filtresi değil): admin başka takımın grubunu görmez.
  const groupSelectOptions = useMemo(() => teamGroups.map(g => ({ value: g.name, label: g.name })), [teamGroups])

  const scoped = useMemo(() => monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
    }
    if (groupFilter !== 'all') {
      if (groupFilter === '__none__') { if (m.group_name) return false }
      else if (m.group_name !== groupFilter) return false
    }
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return (m.url || '').toLowerCase().includes(q) || (m.keyword || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, search])

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

  // Sayfalama filtrelenmiş listenin ÜZERİNE; sayaç/istatistikler tam listeden hesaplanmaya devam eder.
  const pager = usePagination(displayMonitors, {
    listKey: 'keyword-monitors', resetDeps: [search, teamFilter, groupFilter, statFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: görünür durum (filtre/arama/sayfa/açık modal) adres çubuğunda yaşar;
  // varsayılan değerler param üretmez (temiz URL). Yazım debounce'lu replaceState (useUrlQuerySync).
  useUrlQuerySync({
    team: teamFilter === 'all' ? null : teamFilter,
    group: groupFilter === 'all' ? null : groupFilter,
    q: search.trim() || null,
    stat: statFilter && statFilter !== 'total' ? statFilter : null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
    monitor: selected?.id ?? null,
    mtab: selected && detailTab !== 'control' ? detailTab : null,
    range: selected && rangeDays !== 1 ? rangeDays : null,
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('keyword.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('keyword.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: TriangleAlert,   label: t('keyword.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'error',   Icon: ServerCrash,     label: t('keyword.dashError'),   value: counts.error,   cls: 'error'    },
    { key: 'alarm',   Icon: Siren,           label: t('keyword.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('keyword.dashUnacked'), value: counts.unacked, cls: 'warning'  },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)

  // Geçmiş sayfalaması (DNS ile aynı 50/100/200)
  // Geçmiş modalı sayfalaması — 30 sn modal yenilemesi history referansını değiştirir; sayfa korunur.
  const histPager = usePagination(history, { listKey: 'keyword-history' })
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
  function expectPhrase(op, n) {
    switch (op) {
      case 'LTE': return `en fazla ${n} kez`
      case 'EQ':  return `tam olarak ${n} kez`
      case 'GT':  return `${n} kezden fazla`
      case 'LT':  return `${n} kezden az`
      default:    return `en az ${n} kez`   // GTE
    }
  }
  // Alarmın HANGİ durumda tetikleneceği — düz, net Türkçe (koşulun sağlanmadığı taraf).
  function triggerPhrase(op, n, kw) {
    const k = kw && kw.trim() ? `« ${kw.trim()} »` : t('keyword.theKeyword')
    switch (op) {
      case 'LTE': return n === 0 ? `${k} sayfada bulunursa` : `${k} sayfada ${n} kezden fazla bulunursa`
      case 'EQ':  return `${k} sayfada tam olarak ${n} kez bulunmazsa`
      case 'GT':  return `${k} sayfada ${n} veya daha az bulunursa`
      case 'LT':  return `${k} sayfada ${n} veya daha fazla bulunursa`
      default:    return n <= 1 ? `${k} sayfada hiç bulunmazsa` : `${k} sayfada ${n} kezden az bulunursa`   // GTE
    }
  }

  const selectedTeamLabel = isAdmin
    ? (teams.find(tm => String(tm.id) === String(form.teamId))?.name || t('keyword.noTeam'))
    : (teamName || t('keyword.noTeam'))
  const ivIdx = intervalIdx(Number(form.intervalSeconds))

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
          <CopyLinkButton />
          <MonitorGuideButton type="keyword" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('keyword.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('keyword.how1'), t('keyword.how2'), t('keyword.how3')]} />

      {!loading && monitors.length > 0 && (
        <div className="stats-collapse-bar" onClick={toggleStats}
          title={statsVisible ? t('app.collapseStats') : t('app.expandStats')}>
          <span className="stats-collapse-icon"><BarChart3 size={18} /></span>
          <span className="stats-collapse-label">{t('app.statistics')}</span>
          {!statsVisible && <span className="stats-collapse-hint">{t('app.expandStats')}</span>}
          <span className={`stats-collapse-chevron${statsVisible ? ' open' : ''}`}><ChevronDown size={18} /></span>
        </div>
      )}
      {statsVisible && !loading && monitors.length > 0 && (
        <MonitorStatsBar items={statItems} activeFilter={statFilter} onStatClick={onStatClick} />
      )}
      {statsVisible && statFilter && statFilter !== 'total' && (
        <div className="stats-filter-bar" style={{ marginBottom: 16 }}>
          <span>{statItems.find(s => s.key === statFilter)?.label} — {t('mondash.showing', displayMonitors.length)}</span>
          <button className="stats-filter-clear" onClick={() => setStatFilter(null)}>{t('app.clearFilter')}</button>
        </div>
      )}

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('keyword.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="loading">{canWrite ? t('keyword.noMonitorsAdmin') : t('keyword.noMonitors')}</div>
      ) : (
        <>
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.url} />
                <span className="upt-port-tag">
                  {(OP_SYM[m.operator] || '≥') + (m.match_count ?? 1)} kez
                </span>
              </div>
              <div className="upt-card-domain" title={m.url}>{m.url}</div>
              <div style={{ fontSize: '.8em', color: 'var(--text-muted)', marginTop: 2, wordBreak: 'break-word' }}>
                <Target size={11} style={{ verticalAlign: '-1px', marginRight: 4 }} />{m.keyword}
              </div>
              {m.team_name && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2 }}>
                  <Users size={12} />{m.team_name}
                </div>
              )}
              {m.group_name && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2 }}>
                  <Layers size={12} />{m.group_name}
                </div>
              )}
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
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('keyword.check')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('keyword.edit')}><Pencil size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openDuplicate(m)} title={t('mon.duplicate')} aria-label={t('mon.duplicate')}><Copy size={12} /></button>
                  </span>
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
              <CopyLinkButton iconOnly className="btn btn-sm upt-refresh-btn" />
              <button className="upt-modal-close" onClick={closeDetail}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('keyword.sumOkHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}</span>
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
                {selected.custom_headers
                  ? <pre className="kw-reqinfo-headers">{selected.custom_headers}</pre>
                  : <span className="kw-off">{t('keyword.none')}</span>}
              </div>
            </div>
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('keyword.tabControl')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('keyword.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('keyword.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('keyword.tabGuide')}</button>
            </div>

            {detailTab === 'control' && (<>
              <div className="upt-range-btns">
                {[1, 7, 15, 30].map(d => (
                  <button key={d} type="button" className={`btn btn-sm ${rangeDays === d ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => selectRange(selected.id, d)}>{t(`keyword.range${d}d`)}</button>
                ))}
              </div>
              {historyLoading ? <div className="upt-modal-loading">...</div> : history.length === 0 ? (
                <div className="upt-modal-loading">{t('keyword.noData')}</div>
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head">
                    <span>{t('keyword.colTime')}</span><span>{t('keyword.colStatus')}</span><span>HTTP</span><span>{t('keyword.colDetail')}</span>
                  </div>
                  {histPager.pageItems.map((c, i) => {
                    const occ = c.occurrences != null ? c.occurrences : (c.found ? '≥1' : 0)
                    const cmp = `${OP_SYM[selected.operator] || '≥'}${selected.match_count ?? 1}`
                    return (
                      <div key={`${c.checkedAt || c.checked_at || ''}#${i}`} className="upt-rt-grid">
                        <span className="upt-rt-time">{formatDateSec(c.checkedAt || c.checked_at)}</span>
                        <span className={c.ok ? 'upt-rt-up' : 'upt-rt-down'}>{c.ok ? t('keyword.statusOk') : (c.error ? t('keyword.statusError') : t('keyword.statusViolation'))}</span>
                        <span className="upt-rt-ms">{c.httpStatus ?? c.http_status ?? '—'}</span>
                        {c.error
                          ? <span className="upt-rt-error" title={c.error}>{c.error}</span>
                          : <span style={{ whiteSpace: 'nowrap' }}
                              title={`« ${selected.keyword} » → ${occ} ${t('keyword.testFound')} · ${t('keyword.testRequired')}: ${cmp} (${expectPhrase(selected.operator, selected.match_count ?? 1)})${c.snippet ? '\n— ' + c.snippet : ''}`}>
                              <strong>{occ}</strong> {t('keyword.testFound')} <span style={{ color: 'var(--text-muted)' }}>· {cmp}</span>
                            </span>}
                      </div>
                    )
                  })}
                  <PaginationBar {...histPager} compact />
                </div>
              )}
            </>)}

            {detailTab === 'alerts' && <AlertHistory domain={selected.url} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <ResponseTimeChart monitorId={selected.id} kind="keyword" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <MonitorNotes type="KEYWORD" target={selected.url} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── (dış/overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 720, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--keyword">
              <div className="modal-icon-hdr-badge"><Target size={20} /></div>
              <h3>{modal === 'new' ? t('keyword.modalNew') : t('keyword.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
            </div>

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
                  onChange={e => setForm(f => ({ ...f, customHeaders: e.target.value }))} /></label>
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
              <label><span>{t('keyword.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('keyword.team')} <span className="req-star">*</span></span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('keyword.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('keyword.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('keyword.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('keyword.noGroup')} /></label>

              {/* Etiketler */}
              <div className="full-width kw-tags-block">
                <div className="kw-block-title">{t('keyword.tagsTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('keyword.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('keyword.tagsPlaceholder')} />
              </div>

              {/* Bildirimler */}
              <div className="full-width kw-notify-section">
                <div className="kw-block-title">{t('keyword.notifyTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('keyword.notifyInfo').replace('{0}', selectedTeamLabel)}</div>
                <div className="kw-channels">
                  <label className="kw-channel">
                    <input type="checkbox" checked={form.notifyEmail} onChange={e => setForm(f => ({ ...f, notifyEmail: e.target.checked }))} />
                    <Mail size={14} /><span>{t('keyword.chEmail')}</span>
                    <span className="kw-ch-target">{selectedTeamLabel}</span>
                  </label>
                  <label className="kw-channel kw-channel--disabled" title={t('keyword.soonHint')}>
                    <input type="checkbox" disabled /><MessageSquare size={14} /><span>{t('keyword.chSms')}</span><span className="kw-ch-soon">{t('keyword.soon')}</span></label>
                  <label className="kw-channel kw-channel--disabled" title={t('keyword.soonHint')}>
                    <input type="checkbox" disabled /><Phone size={14} /><span>{t('keyword.chVoice')}</span><span className="kw-ch-soon">{t('keyword.soon')}</span></label>
                  <label className="kw-channel kw-channel--disabled" title={t('keyword.soonHint')}>
                    <input type="checkbox" disabled /><Smartphone size={14} /><span>{t('keyword.chPush')}</span><span className="kw-ch-soon">{t('keyword.soon')}</span></label>
                </div>
              </div>

              {/* Kontrol aralığı — kaydırmalı çubuk */}
              <div className="full-width kw-interval-block">
                <div className="kw-block-title">{t('keyword.intervalTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('keyword.intervalEvery').replace('{0}', t(INTERVALS[ivIdx].labelKey))}</div>
                <input type="range" className="kw-interval-slider" min={0} max={INTERVALS.length - 1} step={1}
                  value={ivIdx} onChange={e => setForm(f => ({ ...f, intervalSeconds: INTERVALS[Number(e.target.value)].value }))} />
                <div className="kw-interval-ticks">
                  {INTERVALS.map((o, j) => (
                    <span key={o.value} className={`kw-interval-tick${j === ivIdx ? ' active' : ''}`}>{t(o.labelKey)}</span>
                  ))}
                </div>
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
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                disabled={testing || !form.url.trim() || !form.keyword.trim()}>
                <FlaskConical size={14} />{testing ? t('keyword.testing') : t('keyword.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('keyword.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('keyword.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.url.trim() || !form.keyword.trim() || !form.teamId}>{saving ? '...' : t('keyword.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {showCacheHelp && createPortal(
        <div className="modal-overlay" onClick={() => setShowCacheHelp(false)}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 560 }}>
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
    </div>
  )
}
