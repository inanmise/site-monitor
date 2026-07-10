import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import TagInput from './ui/TagInput.jsx'
import { Play, Pencil, X, RefreshCw, Plug, Plus, Trash2, FlaskConical, AlertTriangle, Network, Check, Pause, BarChart3, ChevronDown, BellDot,
  Mail, MessageSquare, Phone, Smartphone } from 'lucide-react'
import AlertHistory from './admin/AlertHistory.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))

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
const emptyForm = { name: '', host: '', port: '', protocol: 'TCP', expect: '', sendData: '', teamId: '', groupName: '',
  tags: '', notifyEmail: true, ipVersion: 'auto', slowResponseEnabled: false, slowThresholdMs: 3000,
  intervalSeconds: 300, timeoutMs: 5000,
  confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true }

export default function PortMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin                          // ekle/düzenle/sil butonu (takım-kapsamlı)
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam
  const canManageRow = (m) => isAdmin || isOwnTeam(m)              // düzenle + kontrol (otomatik :443/team_id=null → yalnız admin)
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [defaults, setDefaults] = useState(null)
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [rangeDays, setRangeDays] = useState(1)
  const [historyPage, setHistoryPage] = useState(0)
  const [historyPageSize, setHistoryPageSize] = useState(50)
  const [detailTab, setDetailTab] = useState('control')
  const [summary, setSummary] = useState({ total: 0, down: 0 })
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [advOpen, setAdvOpen] = useState(false)               // "Gelişmiş ayarlar" accordion
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [checking, setChecking] = useState(null)
  const [search, setSearch] = useState('')
  const [groupFilter, setGroupFilter] = useState('all')
  const [statFilter, setStatFilter] = useState(null)
  const [statsVisible, setStatsVisible] = useState(false)
  const deepLinkDone = useRef(false)
  const [teamFilter, setTeamFilter] = useState('all')
  const [secondsSince, setSecondsSince] = useState(0)
  const countdownRef = useRef(null)

  const load = useCallback(async () => {
    const res = await api.monitoring.getPortMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false)
    setSecondsSince(0)
  }, [])

  useEffect(() => {
    load()
    const interval = setInterval(load, REFRESH_INTERVAL * 1000)
    return () => clearInterval(interval)
  }, [load])

  useEffect(() => {
    countdownRef.current = setInterval(() => setSecondsSince(s => s + 1), 1000)
    return () => clearInterval(countdownRef.current)
  }, [])

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
  useEffect(() => {
    if (deepLinkDone.current || monitors.length === 0) return
    deepLinkDone.current = true
    let id
    try { id = new URLSearchParams(window.location.search).get('monitor') } catch { return }
    if (!id) return
    const m = monitors.find(x => String(x.id) === String(id))
    if (m) openModal(m)
    try {
      const u = new URL(window.location.href); u.searchParams.delete('monitor')
      window.history.replaceState({}, '', u.pathname + u.search + u.hash)
    } catch { /* yoksay */ }
  }, [monitors]) // eslint-disable-line react-hooks/exhaustive-deps

  // Modal her açıldığında önceki kaydetme hatası + test sonucunu temizle.
  useEffect(() => { setSaveError(null); setTestResult(null) }, [modal])

  async function loadHistory(id, days = rangeDays) {
    setHistoryLoading(true)
    const res = await api.monitoring.getPortHistory(id, { days })
    if (res?.success) {
      setHistory(res.data?.checks ?? [])
      setSummary({ total: res.data?.total ?? 0, down: res.data?.down ?? 0 })
    }
    setHistoryLoading(false)
  }

  function selectRange(id, days) { setRangeDays(days); setHistoryPage(0); loadHistory(id, days) }

  async function openModal(m) {
    setSelected(m)
    setDetailTab('control')
    setHistory([])
    setHistoryPage(0)
    loadHistory(m.id, rangeDays)
  }

  function closeModal() { setSelected(null); setHistory([]) }

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      slowThresholdMs: defaults?.slowThresholdMs ?? emptyForm.slowThresholdMs })
    setModal('new')
  }
  function openEdit(m) {
    // Envanter-türevi monitörde team_id null olabilir; liste team_name'i (domain→takım) gösterir →
    // edit'te o takımı önseç (aksi halde "takımsız" görünür), team_name'i teams'ten eşleştirerek.
    const derivedTeam = m.team_id == null && m.team_name ? teams.find(tm => tm.name === m.team_name) : null
    setForm({ name: m.name || '', host: m.host || '', port: m.port ?? '', protocol: m.protocol || 'TCP',
      expect: m.expect || '', sendData: m.send_data || '',
      teamId: m.team_id != null ? String(m.team_id) : (derivedTeam ? String(derivedTeam.id) : ''), groupName: m.group_name || '',
      tags: m.tags || '', notifyEmail: m.notify_email !== false, ipVersion: m.ip_version || 'auto',
      slowResponseEnabled: !!m.slow_response_enabled, slowThresholdMs: m.slow_threshold_ms ?? 3000,
      intervalSeconds: m.interval_seconds ?? 300, timeoutMs: m.timeout_ms ?? 5000,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false })
    setModal(m)
  }
  function closeEdit() { setModal(null) }

  async function save() {
    if (!form.host.trim() || !form.port) { setSaveError(t('port.hostRequired')); return }
    setSaving(true); setSaveError(null)
    const payload = {
      name: (form.name || form.host).trim(), host: form.host.trim(), port: Number(form.port),
      protocol: form.protocol?.trim() || 'TCP',
      expect: form.expect?.trim() || null, sendData: form.sendData || null,
      teamId: form.teamId === '' ? null : Number(form.teamId), groupName: form.groupName?.trim() || null,
      tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail, ipVersion: form.ipVersion,
      slowResponseEnabled: form.slowResponseEnabled, slowThresholdMs: Number(form.slowThresholdMs),
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
      recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      active: form.active,
    }
    const res = modal === 'new'
      ? await api.monitoring.createPortMonitor(payload)
      : await api.monitoring.updatePortMonitor(modal.id, payload)
    setSaving(false)
    if (!res?.success) { setSaveError(res?.error || t('port.saveError')); return }
    toast.success(t('port.saved'))
    await load(); closeEdit()
  }

  // Kaydetmeden formdaki ayarlarla bir kez kontrol eder: ne döndü (HTTP durum/banner/TLS) + alarm koşulu sağlandı mı.
  async function runTest() {
    if (!form.host.trim() || !form.port) { setSaveError(t('port.hostRequired')); return }
    setTesting(true); setTestResult(null); setSaveError(null)
    const res = await api.monitoring.testPortMonitor({
      host: form.host.trim(), port: Number(form.port), protocol: form.protocol,
      expect: form.expect?.trim() || null, sendData: form.sendData || null, timeoutMs: Number(form.timeoutMs),
      ipVersion: form.ipVersion,
    })
    setTestResult(res?.success ? res.data : { error: res?.error || t('port.testError') })
    setTesting(false)
  }

  async function del() {
    if (!modal || modal === 'new') return
    if (!window.confirm(t('port.deleteConfirm'))) return
    const res = await api.monitoring.deletePortMonitor(modal.id)
    if (!res?.success) { setSaveError(res?.error || t('port.saveError')); return }
    toast.success(t('port.deleted'))
    await load(); closeEdit()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerPortCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id, rangeDays) }
    }
    setChecking(null)
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
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('app.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  const groupNames = useMemo(
    () => [...new Set(monitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)), [monitors])
  const groupSelectOptions = useMemo(() => groupNames.map(g => ({ value: g, label: g })), [groupNames])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = [{ value: 'all', label: t('port.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(monitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('port.noGroup') }] : [])]

  const portCounts = {
    total: monitors.length,
    up: monitors.filter(m => m.status === 'open').length,
    down: monitors.filter(m => m.status === 'closed').length,
    alarm: monitors.filter(m => m.active_alarm).length,
    unacked: monitors.filter(m => m.active_alarm && !m.alarm_acknowledged).length,
    paused: monitors.filter(m => m.active === false).length,
  }
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
  const histTotalPages = Math.max(1, Math.ceil(history.length / historyPageSize))
  const histSafePage = Math.min(historyPage, histTotalPages - 1)
  const histStart = histSafePage * historyPageSize
  const histEnd = Math.min(histStart + historyPageSize, history.length)
  const pagedHistory = history.slice(histStart, histEnd)

  const displayMonitors = monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
    }
    if (groupFilter !== 'all') {
      if (groupFilter === '__none__') { if (m.group_name) return false }
      else if (m.group_name !== groupFilter) return false
    }
    if (statFilter && statFilter !== 'total') {
      if (statFilter === 'up' && m.status !== 'open') return false
      if (statFilter === 'down' && m.status !== 'closed') return false
      if (statFilter === 'alarm' && !m.active_alarm) return false
      if (statFilter === 'unacked' && !(m.active_alarm && !m.alarm_acknowledged)) return false
      if (statFilter === 'paused' && m.active !== false) return false
    }
    if (!search.trim()) return true
    return m.host.toLowerCase().includes(search.trim().toLowerCase())
  })

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

  const selectedTeamLabel = isAdmin
    ? (teams.find(tm => String(tm.id) === String(form.teamId))?.name || t('app.noTeam'))
    : (teamName || t('app.noTeam'))
  const ivIdx = intervalIdx(Number(form.intervalSeconds))

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
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('port.addMonitor')}
            </button>
          )}
        </div>
      </div>

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
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', marginBottom: '14px', gap: 8 }}>
          {hasTeamOptions && (
            <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
          )}
          {hasGroupOptions && (
            <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />
          )}
          <input className="upt-search" type="text"
            placeholder={t('port.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="mon-empty">{t('port.noMonitors')}</div>
      ) : (
        <div className="mon-table-wrap">
          <table className="mon-table">
            <thead>
              <tr>
                <th>{t('port.host')}</th>
                <th>{t('port.colTeam')}</th>
                <th>{t('port.port')}</th>
                <th>{t('port.status')}</th>
                <th>{t('port.responseMs')}</th>
                <th>{t('port.lastCheck')}</th>
                <th>{t('port.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {displayMonitors.map(m => (
                <tr
                  key={m.id}
                  className={`mon-row${!m.active ? ' mon-row-inactive' : ''}${m.active_alarm ? ' mon-row--alarm' : ''}`}
                  onClick={() => openModal(m)}
                >
                  <td className="mon-cell-mono">{m.host}</td>
                  <td>{m.team_name || '—'}</td>
                  <td className="mon-cell-num">{m.port}</td>
                  <td>{statusBadge(m.status)}{alarmBadge(m)}</td>
                  <td className="mon-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="mon-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="mon-cell-actions" onClick={e => e.stopPropagation()}>
                    {canManageRow(m) && (
                      <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('port.check')}>
                        <Play size={12} />
                      </button>
                    )}
                    {canManageRow(m) && (
                      <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('port.edit')}>
                        <Pencil size={12} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
              <button className="upt-modal-close" onClick={closeModal}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('port.sumUptimeHint')}>
                <span className="upt-modal-metric-val">
                  {summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}
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
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('port.tabControl')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('port.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('port.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('port.tabGuide')}</button>
            </div>

            {detailTab === 'control' && (<>
              <div className="upt-range-btns">
                {[1, 7, 15, 30].map(d => (
                  <button key={d} type="button"
                    className={`btn btn-sm ${rangeDays === d ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => selectRange(selected.id, d)}>{t(`port.range${d}d`)}</button>
                ))}
              </div>
              {historyLoading ? (
                <div className="upt-modal-loading">...</div>
              ) : history.length === 0 ? (
                <div className="upt-modal-loading">{t('uptime.noData')}</div>
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head">
                    <span>{t('port.colTime')}</span>
                    <span>{t('port.colStatus')}</span>
                    <span>{t('port.colResponse')}</span>
                    <span>{t('port.colDetail')}</span>
                  </div>
                  {pagedHistory.map((c, i) => (
                    <div key={i} className="upt-rt-grid">
                      <span className="upt-rt-time">{formatDate(c.checkedAt || c.checked_at)}</span>
                      <span className={c.open ? 'upt-rt-up' : 'upt-rt-down'}>
                        {c.open ? t('port.statusOpen') : t('port.statusClosed')}
                      </span>
                      <span className="upt-rt-ms">
                        {(c.responseMs ?? c.response_ms) != null ? `${c.responseMs ?? c.response_ms}ms` : '—'}
                      </span>
                      {c.error
                        ? <span className="upt-rt-error">{c.error}</span>
                        : c.open
                          ? <span className="upt-rt-up">{t('port.detailOk')}</span>
                          : <span className="upt-rt-ms">—</span>}
                    </div>
                  ))}
                  {history.length > 0 && (
                    <div className="dns-history-pagination" style={{ marginTop: 10 }}>
                      <div className="dash-page-sizer">
                        <span className="dash-page-sizer-label">{t('app.perPage')}</span>
                        {[50, 100, 200].map(n => (
                          <button key={n} type="button" className={`dash-size-btn${historyPageSize === n ? ' active' : ''}`}
                            onClick={() => { setHistoryPageSize(n); setHistoryPage(0) }}>{n}</button>
                        ))}
                      </div>
                      {histTotalPages > 1 && (
                        <div className="dash-page-nav">
                          <button type="button" className="page-btn" disabled={histSafePage <= 0} onClick={() => setHistoryPage(0)}>«</button>
                          <button type="button" className="page-btn" disabled={histSafePage <= 0} onClick={() => setHistoryPage(histSafePage - 1)}>{t('app.prevPage')}</button>
                          <span className="dash-page-info-mini">{histSafePage + 1} / {histTotalPages}</span>
                          <button type="button" className="page-btn" disabled={histSafePage >= histTotalPages - 1} onClick={() => setHistoryPage(histSafePage + 1)}>{t('app.nextPage')}</button>
                          <button type="button" className="page-btn" disabled={histSafePage >= histTotalPages - 1} onClick={() => setHistoryPage(histTotalPages - 1)}>»</button>
                        </div>
                      )}
                      <span className="dash-page-info">{t('dns.pageInfo', histStart + 1, histEnd, history.length)}</span>
                    </div>
                  )}
                </div>
              )}
            </>)}

            {detailTab === 'alerts' && <AlertHistory domain={selected.host} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <ResponseTimeChart monitorId={selected.id} kind="port" />
              </Suspense>
            )}

            {detailTab === 'notes' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <MonitorNotes type="PORT" target={`${selected.host}:${selected.port}`} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── (overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 640, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Plug size={20} /></div>
              <h3>{modal === 'new' ? t('port.modalAdd') : t('port.modalEdit')}</h3>
            </div>

            <div className="port-type-banner"><Plug size={16} /><span>{t('port.typeInfo')}</span></div>

            <div className="form-grid form-grid--top">
              <label><span>{t('port.host')} <span className="req-star">*</span></span>
                <input value={form.host} placeholder="1.2.3.4 / host.example.com"
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
              <label><span>{t('port.team')}</span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('app.noTeam')} disabled />}</label>
              <label><span>{t('port.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('port.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('port.noGroup')} /></label>
              {/* Etiketler */}
              <div className="full-width port-tags-block">
                <div className="port-block-title">{t('port.tagsTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 6 }}>{t('port.tagsHint')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('port.tagsPlaceholder')} />
              </div>

              {/* Bildirimler */}
              <div className="full-width port-notify-section">
                <div className="port-block-title">{t('port.notifyTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('port.notifyInfo').replace('{0}', selectedTeamLabel)}</div>
                <div className="port-channels">
                  <label className="port-channel">
                    <input type="checkbox" checked={form.notifyEmail} onChange={e => setForm(f => ({ ...f, notifyEmail: e.target.checked }))} />
                    <Mail size={14} /><span>{t('port.chEmail')}</span>
                    <span className="port-ch-target">{selectedTeamLabel}</span>
                  </label>
                  <label className="port-channel port-channel--disabled" title={t('port.soonHint')}>
                    <input type="checkbox" disabled /><MessageSquare size={14} /><span>{t('port.chSms')}</span><span className="port-ch-soon">{t('port.soon')}</span></label>
                  <label className="port-channel port-channel--disabled" title={t('port.soonHint')}>
                    <input type="checkbox" disabled /><Phone size={14} /><span>{t('port.chVoice')}</span><span className="port-ch-soon">{t('port.soon')}</span></label>
                  <label className="port-channel port-channel--disabled" title={t('port.soonHint')}>
                    <input type="checkbox" disabled /><Smartphone size={14} /><span>{t('port.chPush')}</span><span className="port-ch-soon">{t('port.soon')}</span></label>
                </div>
              </div>

              {/* Kontrol aralığı — kaydırmalı çubuk */}
              <div className="full-width port-interval-block">
                <div className="port-block-title">{t('port.intervalTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('port.intervalEvery').replace('{0}', t(INTERVALS[ivIdx].labelKey))}</div>
                <input type="range" className="port-interval-slider" min={0} max={INTERVALS.length - 1} step={1}
                  value={ivIdx} onChange={e => setForm(f => ({ ...f, intervalSeconds: INTERVALS[Number(e.target.value)].value }))} />
                <div className="port-interval-ticks">
                  {INTERVALS.map((o, j) => (
                    <span key={o.value} className={`port-interval-tick${j === ivIdx ? ' active' : ''}`}>{t(o.labelKey)}</span>
                  ))}
                </div>
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
            <div className="modal-actions">
              <div style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
                <button className="btn btn-secondary" onClick={runTest} disabled={testing || !form.host.trim() || !form.port}>
                  <FlaskConical size={14} />{testing ? t('port.testing') : t('port.test')}
                </button>
                {modal !== 'new' && canDeleteRow(modal) && (
                  <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('port.delete')}</button>
                )}
              </div>
              <button className="btn btn-secondary" onClick={closeEdit}>{t('port.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.host.trim() || !form.port}>{saving ? '...' : t('port.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
