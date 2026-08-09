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
import { Play, Pencil, Copy, X, RefreshCw, Plus, Trash2, Globe, Users, Layers, FlaskConical, Check, AlertTriangle,
  LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, BarChart3, ChevronDown, ShieldCheck,
  Mail, MessageSquare, Phone, Smartphone } from 'lucide-react'
import { duplicateName } from '../utils/duplicateName.js'
import { normalizeUrl } from '../utils/normalizeUrl.js'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertHistory from './admin/AlertHistory.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))

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
  name: '', url: '', method: 'GET', expectedStatus: '200-399', followRedirects: true, verifySsl: false,
  groupName: '', teamId: '', tags: '', notifyEmail: true,
  checkSslErrors: false, sslExpiryReminders: false, domainExpiryReminders: false,
  sslReminderDays: '30,14,7', domainReminderDays: '30,14,7',
  intervalSeconds: 300, timeoutMs: 10000,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true,
}

export default function HttpMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam
  const canManageRow = (m) => isAdmin || isOwnTeam(m)
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [selected, setSelected] = useState(null)
  const [summary, setSummary] = useState({ total: 0, down: 0 })   // CheckHistoryTab onCounts besler
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  const [dupSource, setDupSource] = useState(null)  // Kopyala akışında kaynak monitör (rozet/ipucu için)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])   // form takımı+türüne göre grup önerileri (sızıntısız, server-scoped)
  const [defaults, setDefaults] = useState(null)
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
    const res = await api.monitoring.getHttpMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false); setSecondsSince(0)
  }, [])

  useVisibleInterval(load, REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur

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
      expectedStatus: m.expected_status || '200-399', followRedirects: m.follow_redirects !== false, verifySsl: !!m.verify_ssl,
      groupName: m.group_name || '', teamId: m.team_id != null ? String(m.team_id) : '', tags: m.tags || '',
      notifyEmail: m.notify_email !== false,
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

  async function runTest() {
    if (!form.url.trim()) return
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testHttp({
      url: normalizeUrl(form.url), method: form.method, expectedStatus: form.expectedStatus?.trim() || '200-399',
      timeoutMs: Number(form.timeoutMs), verifySsl: form.verifySsl, followRedirects: form.followRedirects,
    })
    setTestResult(res?.success ? res.data : { error: res?.error || t('http.testError') })
    setTesting(false)
  }

  async function save() {
    if (!form.url.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    setSaving(true)
    const payload = {
      name: (form.name || form.url).trim(), url: normalizeUrl(form.url), method: form.method,
      expectedStatus: form.expectedStatus?.trim() || '200-399', followRedirects: form.followRedirects, verifySsl: form.verifySsl,
      groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId), tags: form.tags?.trim() || null,
      notifyEmail: form.notifyEmail,
      checkSslErrors: form.checkSslErrors, sslExpiryReminders: form.sslExpiryReminders, domainExpiryReminders: form.domainExpiryReminders,
      sslReminderDays: form.sslReminderDays?.trim() || '30,14,7', domainReminderDays: form.domainReminderDays?.trim() || '30,14,7',
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
      recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      active: form.active,
    }
    const res = modal === 'new'
      ? await api.monitoring.createHttpMonitor(payload)
      : await api.monitoring.updateHttpMonitor(modal.id, payload)
    await load(); setSaving(false)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('http.saved')); closeEdit()
  }

  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deleteHttpMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('http.deleted')); closeEdit()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerHttpCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id, rangeDays) }
    }
    setChecking(null)
  }

  const teamOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [monitors, t])
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('http.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)),
    [monitors, isAdmin, myTeam])
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('http.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('http.noGroup') }] : [])],
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
    return (m.url || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q) || (m.tags || '').toLowerCase().includes(q)
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
    listKey: 'http-monitors', resetDeps: [search, teamFilter, groupFilter, statFilter],
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
    // range/hfrom/hto/hst artık CheckHistoryTab'ın kendi URL senkronunda
  })

  const statItems = [
    { key: 'total',   Icon: LayoutDashboard, label: t('http.dashTotal'),   value: counts.total,   cls: 'total'    },
    { key: 'up',      Icon: CheckCircle2,    label: t('http.dashUp'),      value: counts.up,      cls: 'valid'    },
    { key: 'down',    Icon: TriangleAlert,   label: t('http.dashDown'),    value: counts.down,    cls: 'critical' },
    { key: 'error',   Icon: ServerCrash,     label: t('http.dashError'),   value: counts.error,   cls: 'error'    },
    { key: 'alarm',   Icon: Siren,           label: t('http.dashAlarm'),   value: counts.alarm,   cls: 'high'     },
    { key: 'unacked', Icon: BellDot,         label: t('http.dashUnacked'), value: counts.unacked, cls: 'warning'  },
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

  const selectedTeamLabel = isAdmin
    ? (teams.find(tm => String(tm.id) === String(form.teamId))?.name || t('http.noTeam'))
    : (teamName || t('http.noTeam'))
  const ivIdx = intervalIdx(Number(form.intervalSeconds))

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
          <CopyLinkButton />
          <MonitorGuideButton type="http" />
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('http.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <MonitorHowBox bullets={[t('http.how1'), t('http.how2'), t('http.how3'), t('http.how4')]} />

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
          <input className="upt-search" type="text" placeholder={t('http.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : monitors.length === 0 ? (
        <LoadingBlock label={canWrite ? t('http.noMonitorsAdmin') : t('http.noMonitors')} fullWidth />
      ) : (
        <>
        <div className="upt-grid">
          {pager.pageItems.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.url} />
                <span className="upt-port-tag">{m.method || 'GET'}</span>
              </div>
              <div className="upt-card-domain" title={m.url}>{m.url}</div>
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
                    <span className="upt-metric-lbl">{t('http.responseMs')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('http.check')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('http.edit')}><Pencil size={12} /></button>
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
              <div className="upt-modal-metric" title={t('http.sumOkHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}</span>
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
              <div className="kw-reqinfo-row"><span className="kw-reqinfo-k">{t('http.sslSectionTitle')}</span>
                <span>{[selected.check_ssl_errors && t('http.checkSslErrors'), selected.ssl_expiry_reminders && t('http.sslExpiryReminders'), selected.domain_expiry_reminders && t('http.domainExpiryReminders')].filter(Boolean).join(' · ') || t('http.none')}</span></div>
            </div>
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('hist.tab')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('http.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('http.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('http.tabGuide')}</button>
            </div>

            {detailTab === 'control' && (
              <CheckHistoryTab kind="http" monitorId={selected.id} listKey="http-history"
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

            {detailTab === 'alerts' && <AlertHistory domain={selected.url} />}

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
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── (dış/overlay tıklamada KAPANMAZ — veri kaybı önlenir) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 720, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--http">
              <div className="modal-icon-hdr-badge"><Globe size={20} /></div>
              <h3>{modal === 'new' ? t('http.modalNew') : t('http.modalEdit')}
                {dupSource && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}</h3>
            </div>

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

              <label><span>{t('http.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('http.team')} <span className="req-star">*</span></span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('http.noTeam')} disabled />}</label>

              <label className="full-width"><span>{t('http.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('http.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('http.noGroup')} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('http.groupInfo')}</div>

              {/* Etiketler */}
              <div className="full-width http-tags-block">
                <div className="http-block-title">{t('http.tagsTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('http.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('http.tagsPlaceholder')} />
              </div>

              {/* Bildirimler */}
              <div className="full-width http-notify-section">
                <div className="http-block-title">{t('http.notifyTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('http.notifyInfo').replace('{0}', selectedTeamLabel)}</div>
                <div className="http-channels">
                  <label className="http-channel">
                    <input type="checkbox" checked={form.notifyEmail} onChange={e => setForm(f => ({ ...f, notifyEmail: e.target.checked }))} />
                    <Mail size={14} /><span>{t('http.chEmail')}</span>
                    <span className="http-ch-target">{selectedTeamLabel}</span>
                  </label>
                  <label className="http-channel http-channel--disabled" title={t('http.soonHint')}>
                    <input type="checkbox" disabled /><MessageSquare size={14} /><span>{t('http.chSms')}</span><span className="http-ch-soon">{t('http.soon')}</span></label>
                  <label className="http-channel http-channel--disabled" title={t('http.soonHint')}>
                    <input type="checkbox" disabled /><Phone size={14} /><span>{t('http.chVoice')}</span><span className="http-ch-soon">{t('http.soon')}</span></label>
                  <label className="http-channel http-channel--disabled" title={t('http.soonHint')}>
                    <input type="checkbox" disabled /><Smartphone size={14} /><span>{t('http.chPush')}</span><span className="http-ch-soon">{t('http.soon')}</span></label>
                </div>
              </div>

              {/* Kontrol aralığı — kaydırmalı çubuk */}
              <div className="full-width http-interval-block">
                <div className="http-block-title">{t('http.intervalTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('http.intervalEvery').replace('{0}', t(INTERVALS[ivIdx].labelKey))}</div>
                <input type="range" className="http-interval-slider" min={0} max={INTERVALS.length - 1} step={1}
                  value={ivIdx} onChange={e => setForm(f => ({ ...f, intervalSeconds: INTERVALS[Number(e.target.value)].value }))} />
                <div className="http-interval-ticks">
                  {INTERVALS.map((o, j) => (
                    <span key={o.value} className={`http-interval-tick${j === ivIdx ? ' active' : ''}`}>{t(o.labelKey)}</span>
                  ))}
                </div>
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
                      </>}
                </span>
              </div>
            )}
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                disabled={testing || !form.url.trim()}>
                <FlaskConical size={14} />{testing ? t('http.testing') : t('http.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('http.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('http.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.url.trim() || !form.teamId}>{saving ? '...' : t('http.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
