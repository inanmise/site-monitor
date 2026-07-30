import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { useToast } from './ui/Toast.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import TagInput from './ui/TagInput.jsx'
import { Play, Pencil, X, RefreshCw, Plus, Trash2, ScanSearch, Users, Layers, FlaskConical, Check, AlertTriangle,
  LayoutDashboard, CheckCircle2, TriangleAlert, ServerCrash, Siren, BellDot, BarChart3, ChevronDown,
  Image, FileCode, Link2, Frame, Type, ShieldAlert, Download, HelpCircle } from 'lucide-react'
import AlertHistory from './admin/AlertHistory.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))

const INTERVALS = [
  { value: 60,    labelKey: 'page.iv1m'  },
  { value: 300,   labelKey: 'page.iv5m'  },
  { value: 600,   labelKey: 'page.iv10m' },
  { value: 900,   labelKey: 'page.iv15m' },
  { value: 1800,  labelKey: 'page.iv30m' },
  { value: 3600,  labelKey: 'page.iv1h'  },
  { value: 43200, labelKey: 'page.iv12h' },
  { value: 86400, labelKey: 'page.iv24h' },
]
const intervalIdx = (secs) => {
  const i = INTERVALS.findIndex(o => o.value === secs)
  if (i >= 0) return i
  let best = 0, bd = Infinity
  INTERVALS.forEach((o, j) => { const d = Math.abs(o.value - secs); if (d < bd) { bd = d; best = j } })
  return best
}
const REFRESH_INTERVAL = 60
// Sorun türü → ikon (kaynak tür ikonlarıyla birlikte tabloda gösterilir).
const RES_ICON = { IMG: Image, CSS: FileCode, JS: FileCode, LINK: Link2, IFRAME: Frame, FONT: Type, FAVICON: Image }
// Sorun tablosu kolon şablonu: Zaman | Tür | Kaynak | Sorun | HTTP | Süre.
const PAGE_ISSUE_COLS = '1fr 0.9fr 2.1fr 0.75fr 0.5fr 0.55fr'
const emptyForm = { name: '', url: '', groupName: '', teamId: '', tags: '', notifyEmail: true,
  mode: 'SINGLE_PAGE', crawlDepth: 2, crawlMaxPages: 50, excludePatterns: '', slowResourceMs: 2000,
  alertThirdParty: false, resourceConcurrency: 5,
  intervalSeconds: 300, timeoutMs: 10000, confirmAttempts: 3, confirmIntervalSeconds: 30,
  recoveryChecks: 3, recoveryIntervalSeconds: 30, active: true }

export default function PageMonitorPage({ systemRole, teamId, teamName }) {
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
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [issues, setIssues] = useState([])
  const [issuesLoading, setIssuesLoading] = useState(false)
  const [issueFilter, setIssueFilter] = useState('all')   // all | BROKEN | MIXED_CONTENT | SLOW | firstParty
  const [rangeDays, setRangeDays] = useState(7)
  const [summary, setSummary] = useState({ total: 0, down: 0 })
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [teamGroups, setTeamGroups] = useState([])
  const [defaults, setDefaults] = useState(null)
  const [advOpen, setAdvOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('issues')
  const [search, setSearch] = useState('')
  const [teamFilter, setTeamFilter] = useState('all')
  const [groupFilter, setGroupFilter] = useState('all')
  const [statFilter, setStatFilter] = useState(null)
  const [statsVisible, setStatsVisible] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const [infoOpen, setInfoOpen] = useState(false)
  const deepLinkDone = useRef(false)

  const load = useCallback(async () => {
    const res = await api.monitoring.getPageMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false); setSecondsSince(0)
  }, [])

  useVisibleInterval(load, REFRESH_INTERVAL * 1000)

  useEffect(() => {
    if (!modal || form.teamId === '' || form.teamId == null) { setTeamGroups([]); return }
    let alive = true
    api.monitoring.listGroups(form.teamId, 'page').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    return () => { alive = false }
  }, [modal, form.teamId])

  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.page) })
  }, [])

  useEffect(() => {
    if (deepLinkDone.current || monitors.length === 0) return
    deepLinkDone.current = true
    let id
    try { id = new URLSearchParams(window.location.search).get('monitor') } catch { return }
    if (!id) return
    const m = monitors.find(x => String(x.id) === String(id))
    if (m) openDetail(m)
    try {
      const u = new URL(window.location.href); u.searchParams.delete('monitor')
      window.history.replaceState({}, '', u.pathname + u.search + u.hash)
    } catch { /* yoksay */ }
  }, [monitors]) // eslint-disable-line react-hooks/exhaustive-deps

  async function loadHistory(id, days = rangeDays) {
    setHistoryLoading(true)
    const res = await api.monitoring.getPageHistory(id, { days })
    if (res?.success) {
      setHistory(res.data?.checks ?? [])
      setSummary({ total: res.data?.total ?? 0, down: res.data?.down ?? 0 })
    }
    setHistoryLoading(false)
  }
  async function loadIssues(id, filter = issueFilter) {
    setIssuesLoading(true)
    const issueType = (filter === 'all' || filter === 'firstParty') ? null : filter
    const res = await api.monitoring.getPageIssues(id, { issueType })
    let rows = res?.success ? (res.data ?? []) : []
    if (filter === 'firstParty') rows = rows.filter(r => r.first_party)
    setIssues(rows)
    setIssuesLoading(false)
  }
  function selectIssueFilter(id, f) { setIssueFilter(f); loadIssues(id, f) }
  function openDetail(m) {
    setSelected(m); setHistory([]); setIssues([]); setIssueFilter('all'); setDetailTab('issues')
    loadIssues(m.id, 'all'); loadHistory(m.id, rangeDays)
  }
  function closeDetail() { setSelected(null); setHistory([]); setIssues([]) }

  function openNew() {
    setTestResult(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs,
      slowResourceMs: defaults?.slowResourceMs ?? emptyForm.slowResourceMs,
      resourceConcurrency: defaults?.resourceConcurrency ?? emptyForm.resourceConcurrency,
      crawlDepth: defaults?.crawlDepth ?? emptyForm.crawlDepth,
      crawlMaxPages: defaults?.crawlMaxPages ?? emptyForm.crawlMaxPages })
    setModal('new')
  }
  function openEdit(m) {
    setTestResult(null)
    setForm({ name: m.name || '', url: m.url || '', groupName: m.group_name || '',
      teamId: m.team_id != null ? String(m.team_id) : '',
      tags: m.tags || '', notifyEmail: m.notify_email !== false,
      mode: m.mode || 'SINGLE_PAGE', crawlDepth: m.crawl_depth ?? 2, crawlMaxPages: m.crawl_max_pages ?? 50,
      excludePatterns: m.exclude_patterns || '', slowResourceMs: m.slow_resource_ms ?? 2000,
      alertThirdParty: !!m.alert_third_party, resourceConcurrency: m.resource_concurrency ?? 5,
      intervalSeconds: m.interval_seconds ?? 300, timeoutMs: m.timeout_ms ?? 10000,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30,
      recoveryChecks: m.recovery_checks ?? 3, recoveryIntervalSeconds: m.recovery_interval_seconds ?? 30,
      active: m.active !== false })
    setModal(m)
  }
  function closeEdit() { setModal(null); setTestResult(null) }

  async function runTest() {
    if (!form.url.trim()) return
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testPage({ url: form.url.trim(), timeoutMs: Number(form.timeoutMs) })
    setTestResult(res?.success ? res.data : { error: res?.error || t('page.testError') })
    setTesting(false)
  }

  async function save() {
    if (!form.url.trim()) return
    if (form.teamId === '' || form.teamId == null) { toast.error(t('mon.teamRequired')); return }
    setSaving(true)
    const payload = {
      name: (form.name || form.url).trim(), url: form.url.trim(),
      groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
      tags: form.tags?.trim() || null, notifyEmail: form.notifyEmail,
      mode: form.mode, crawlDepth: Number(form.crawlDepth), crawlMaxPages: Number(form.crawlMaxPages),
      excludePatterns: form.excludePatterns?.trim() || null, slowResourceMs: Number(form.slowResourceMs),
      alertThirdParty: form.alertThirdParty, resourceConcurrency: Number(form.resourceConcurrency),
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds),
      recoveryChecks: Number(form.recoveryChecks), recoveryIntervalSeconds: Number(form.recoveryIntervalSeconds),
      active: form.active,
    }
    const res = modal === 'new'
      ? await api.monitoring.createPageMonitor(payload)
      : await api.monitoring.updatePageMonitor(modal.id, payload)
    await load(); setSaving(false)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('page.saved')); closeEdit()
  }

  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deletePageMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('page.deleted')); closeEdit()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerPageCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadIssues(m.id, issueFilter); loadHistory(m.id, rangeDays) }
    }
    setChecking(null)
  }

  function exportIssuesCsv() {
    if (!issues.length) return
    const head = ['resource_url', 'resource_type', 'source_page', 'issue_type', 'first_party', 'http_status', 'duration_ms', 'checked_at']
    const esc = v => `"${String(v ?? '').replace(/"/g, '""')}"`
    const body = issues.map(r => head.map(k => esc(r[k])).join(',')).join('\n')
    const blob = new Blob([head.join(',') + '\n' + body], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `page-issues-${selected?.id ?? 'x'}.csv`
    a.click(); URL.revokeObjectURL(a.href)
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
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('page.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)),
    [monitors, isAdmin, myTeam])
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('page.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('page.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
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
    return (m.url || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, search])

  const counts = useMemo(() => {
    const c = { total: scoped.length, ok: 0, degraded: 0, down: 0, alarm: 0, unacked: 0 }
    for (const m of scoped) {
      if (m.status === 'OK') c.ok++
      else if (m.status === 'DEGRADED') c.degraded++
      else if (m.status === 'DOWN') c.down++
      if (m.active_alarm) { c.alarm++; if (!m.alarm_acknowledged) c.unacked++ }
    }
    return c
  }, [scoped])

  const displayMonitors = useMemo(() => {
    if (!statFilter || statFilter === 'total') return scoped
    const pred = {
      ok:       m => m.status === 'OK',
      degraded: m => m.status === 'DEGRADED',
      down:     m => m.status === 'DOWN',
      alarm:    m => m.active_alarm,
      unacked:  m => m.active_alarm && !m.alarm_acknowledged,
    }[statFilter]
    return pred ? scoped.filter(pred) : scoped
  }, [scoped, statFilter])

  const statItems = [
    { key: 'total',    Icon: LayoutDashboard, label: t('page.dashTotal'),    value: counts.total,    cls: 'total'    },
    { key: 'ok',       Icon: CheckCircle2,    label: t('page.dashOk'),       value: counts.ok,       cls: 'valid'    },
    { key: 'degraded', Icon: TriangleAlert,   label: t('page.dashDegraded'), value: counts.degraded, cls: 'warning'  },
    { key: 'down',     Icon: ServerCrash,     label: t('page.dashDown'),     value: counts.down,     cls: 'critical' },
    { key: 'alarm',    Icon: Siren,           label: t('page.dashAlarm'),    value: counts.alarm,    cls: 'high'     },
    { key: 'unacked',  Icon: BellDot,         label: t('page.dashUnacked'),  value: counts.unacked,  cls: 'error'    },
  ]
  const onStatClick = (key) => setStatFilter(k => k === key ? null : key)
  const toggleStats = () => { if (statsVisible) setStatFilter(null); setStatsVisible(v => !v) }

  const STATUS_COLOR = { OK: '#15803d', DEGRADED: '#e07b00', DOWN: '#c0392b', unknown: '#64748b' }
  function cardClass(m) {
    if (m.status === 'OK') return 'upt-card--up'
    if (m.status === 'DOWN') return 'upt-card--down'
    return 'upt-card--unknown'   // DEGRADED / unknown
  }
  function statusBadge(m) {
    const s = m?.status
    const label = s === 'OK' ? t('page.statusOk') : s === 'DEGRADED' ? t('page.statusDegraded')
      : s === 'DOWN' ? t('page.statusDown') : t('page.statusUnknown')
    return <span className="upt-badge" style={{ color: STATUS_COLOR[s] || STATUS_COLOR.unknown }}>
      <span className="upt-badge-dot" style={{ background: STATUS_COLOR[s] || STATUS_COLOR.unknown }} />{label}</span>
  }
  const alarmLevelColor = (lvl) => lvl === 'CRITICAL' ? '#c0392b' : lvl === 'HIGH' ? '#e07b00' : '#f0a500'
  function alarmBadge(m) {
    if (!m?.active_alarm) return null
    const title = `${t('page.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
    return <span className={`upt-alarm-ico${m.alarm_acknowledged ? '' : ' pulse'}`}
      style={{ color: alarmLevelColor(m.alarm_level) }} title={title}><AlertTriangle size={14} /></span>
  }

  const selectedTeamLabel = isAdmin
    ? (teams.find(tm => String(tm.id) === String(form.teamId))?.name || t('page.noTeam'))
    : (teamName || t('page.noTeam'))
  const ivIdx = intervalIdx(Number(form.intervalSeconds))
  const issueFilters = ['all', 'BROKEN', 'MIXED_CONTENT', 'SLOW', 'firstParty']

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('page.title')}</h2>
          <p className="upt-subtitle">{t('page.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('page.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('page.refresh')}
          </button>
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('page.addMonitor')}
            </button>
          )}
        </div>
      </div>

      <div className="dom-info">
        <button type="button" className="dom-info-toggle" onClick={() => setInfoOpen(o => !o)}>
          <HelpCircle size={15} /><span>{t('page.howTitle')}</span>
          <ChevronDown size={15} className={`dom-info-chev${infoOpen ? ' open' : ''}`} />
        </button>
        {infoOpen && (
          <ul className="dom-info-body">
            <li>{t('page.how1')}</li>
            <li>{t('page.how2')}</li>
            <li>{t('page.how3')}</li>
            <li>{t('page.how4')}</li>
            <li>{t('page.how5')}</li>
            <li>{t('page.how6')}</li>
            <li>{t('page.how7')}</li>
          </ul>
        )}
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
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('page.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="loading">{canWrite ? t('page.noMonitorsAdmin') : t('page.noMonitors')}</div>
      ) : (
        <div className="upt-grid">
          {displayMonitors.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.url} />
                <span className="upt-port-tag">{m.mode === 'SITE_CRAWL' ? t('page.modeCrawl') : t('page.modeSingle')}</span>
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
                  <span className="upt-metric-val">{m.broken_resources ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mBroken')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.mixed_content_count ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mMixed')}</span>
                </div>
                <div className="upt-metric">
                  <span className="upt-metric-val">{m.total_resources ?? '—'}</span>
                  <span className="upt-metric-lbl">{t('page.mResources')}</span>
                </div>
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDateSec(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('page.check')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('page.edit')}><Pencil size={12} /></button>
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Detail Modal ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeDetail}>
          <div className={`upt-modal upt-modal--${selected.status === 'OK' ? 'up' : selected.status === 'DOWN' ? 'down' : 'unknown'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.url}</span>
              </div>
              <button className="upt-modal-close" onClick={closeDetail}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ color: STATUS_COLOR[selected.status] }}>{t(`page.status${selected.status === 'OK' ? 'Ok' : selected.status === 'DEGRADED' ? 'Degraded' : selected.status === 'DOWN' ? 'Down' : 'Unknown'}`)}</span><span className="upt-modal-metric-lbl">{t('page.lastStatus')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.broken_resources ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mBroken')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.mixed_content_count ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mMixed')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.total_resources ?? '—'}</span><span className="upt-modal-metric-lbl">{t('page.mResources')}</span></div>
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDateSec(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('page.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'issues' ? ' active' : ''}`} onClick={() => setDetailTab('issues')}>{t('page.tabIssues')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('page.tabChart')}</button>
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('page.tabHistory')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('page.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('page.tabNotes')}</button>
            </div>

            {detailTab === 'issues' && (<>
              <div className="upt-range-btns" style={{ flexWrap: 'wrap' }}>
                {issueFilters.map(f => (
                  <button key={f} type="button" className={`btn btn-sm ${issueFilter === f ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => selectIssueFilter(selected.id, f)}>{t(`page.filter_${f}`)}</button>
                ))}
                <button type="button" className="btn btn-sm btn-secondary" style={{ marginLeft: 'auto' }}
                  disabled={!issues.length} onClick={exportIssuesCsv}><Download size={12} />{t('page.exportCsv')}</button>
              </div>
              {issuesLoading ? <div className="upt-modal-loading">...</div> : issues.length === 0 ? (
                <div className="upt-modal-loading">{t('page.noIssues')}</div>
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head" style={{ gridTemplateColumns: PAGE_ISSUE_COLS }}>
                    <span>{t('page.colTime')}</span><span>{t('page.colType')}</span><span>{t('page.colResource')}</span><span>{t('page.colIssue')}</span><span>HTTP</span><span>{t('page.colDuration')}</span>
                  </div>
                  {issues.map((r, i) => {
                    const RI = RES_ICON[r.resource_type] || Link2
                    const issueColor = r.issue_type === 'MIXED_CONTENT' ? '#b45309' : r.issue_type === 'SLOW' ? '#0369a1' : '#b91c1c'
                    // Kontrol zamanı değişince görsel ayraç — hangi kaynağın hangi kontrolde bulunduğunu ayrıştırır.
                    const runBoundary = i > 0 && (issues[i - 1].checked_at !== r.checked_at)
                    return (
                      <div key={`${r.id || ''}#${i}`} className="upt-rt-grid" style={{ gridTemplateColumns: PAGE_ISSUE_COLS,
                        ...(runBoundary ? { borderTop: '2px solid var(--border, #cbd5e1)' } : {}) }}>
                        <span className="upt-rt-time">{r.checked_at ? formatDateSec(r.checked_at) : '—'}</span>
                        <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                          <RI size={13} />{r.resource_type}{!r.first_party && <span title={t('page.thirdParty')} style={{ color: 'var(--text-muted)' }}>·3P</span>}
                        </span>
                        <span style={{ wordBreak: 'break-all' }} title={r.source_page ? `${t('page.foundOn')}: ${r.source_page}` : ''}>
                          {r.source_page && r.source_page !== r.resource_url
                            ? <a href={r.source_page} target="_blank" rel="noreferrer" onClick={e => e.stopPropagation()} style={{ color: 'inherit' }}>{r.resource_url}</a>
                            : r.resource_url}
                        </span>
                        <span style={{ color: issueColor, fontWeight: 600 }}>
                          {r.issue_type === 'MIXED_CONTENT' ? <ShieldAlert size={12} style={{ verticalAlign: '-2px' }} /> : null} {t(`page.issue_${r.issue_type}`)}
                        </span>
                        <span className="upt-rt-ms">{r.http_status ?? '—'}</span>
                        <span className="upt-rt-ms">{r.duration_ms != null ? r.duration_ms + 'ms' : '—'}</span>
                      </div>
                    )
                  })}
                </div>
              )}
            </>)}

            {detailTab === 'chart' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <ResponseTimeChart monitorId={selected.id} kind="page" />
              </Suspense>
            )}

            {detailTab === 'control' && (<>
              <div className="upt-range-btns">
                {[1, 7, 15, 30].map(d => (
                  <button key={d} type="button" className={`btn btn-sm ${rangeDays === d ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => { setRangeDays(d); loadHistory(selected.id, d) }}>{t(`page.range${d}d`)}</button>
                ))}
              </div>
              {historyLoading ? <div className="upt-modal-loading">...</div> : history.length === 0 ? (
                <div className="upt-modal-loading">{t('page.noData')}</div>
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head">
                    <span>{t('page.colTime')}</span><span>{t('page.colStatus')}</span><span>{t('page.mBroken')}</span><span>{t('page.mMixed')}</span>
                  </div>
                  {history.map((c, i) => (
                    <div key={`${c.checkedAt || c.checked_at || ''}#${i}`} className="upt-rt-grid">
                      <span className="upt-rt-time">{formatDateSec(c.checkedAt || c.checked_at)}</span>
                      <span style={{ color: STATUS_COLOR[c.status] || STATUS_COLOR.unknown, fontWeight: 600 }}>
                        {c.status === 'OK' ? t('page.statusOk') : c.status === 'DEGRADED' ? t('page.statusDegraded') : t('page.statusDown')}</span>
                      <span className="upt-rt-ms">{c.brokenResources ?? c.broken_resources ?? '—'}</span>
                      <span className="upt-rt-ms">{c.mixedContentCount ?? c.mixed_content_count ?? '—'}</span>
                    </div>
                  ))}
                </div>
              )}
            </>)}

            {detailTab === 'alerts' && <AlertHistory domain={selected.url} />}

            {detailTab === 'notes' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <MonitorNotes type="PAGE" target={selected.url} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 720, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--keyword">
              <div className="modal-icon-hdr-badge"><ScanSearch size={20} /></div>
              <h3>{modal === 'new' ? t('page.modalNew') : t('page.modalEdit')}</h3>
            </div>

            <div className="kw-type-banner"><ScanSearch size={16} /><span>{t('page.typeInfo')}</span></div>

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('page.url')} <span className="req-star">*</span></span>
                <input value={form.url} placeholder="https://example.com" onChange={e => setForm(f => ({ ...f, url: e.target.value }))} /></label>
              <label><span>{t('page.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('page.team')} <span className="req-star">*</span></span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('page.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('page.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('page.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('page.noGroup')} /></label>

              {/* Mod seçimi */}
              <label><span>{t('page.mode')}</span>
                <SearchableSelect value={form.mode} onChange={v => setForm(f => ({ ...f, mode: v }))}
                  options={[{ value: 'SINGLE_PAGE', label: t('page.modeSingle') }, { value: 'SITE_CRAWL', label: t('page.modeCrawl') }]} /></label>
              {form.mode === 'SITE_CRAWL' && (<>
                <label><span>{t('page.crawlDepth')}</span>
                  <input type="number" min="0" max="5" value={form.crawlDepth} onChange={e => setForm(f => ({ ...f, crawlDepth: Number(e.target.value) }))} /></label>
                <label><span>{t('page.crawlMaxPages')}</span>
                  <input type="number" min="1" max="500" value={form.crawlMaxPages} onChange={e => setForm(f => ({ ...f, crawlMaxPages: Number(e.target.value) }))} /></label>
              </>)}
              <label className="full-width"><span>{t('page.excludePatterns')}</span>
                <textarea rows={2} value={form.excludePatterns} spellCheck={false} placeholder={t('page.excludePh')}
                  onChange={e => setForm(f => ({ ...f, excludePatterns: e.target.value }))} />
                <span className="field-hint">{t('page.excludeHint')}</span></label>

              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.alertThirdParty} onChange={e => setForm(f => ({ ...f, alertThirdParty: e.target.checked }))} />{t('page.alertThirdParty')}</label>
              <div className="full-width field-hint">{t('page.alertThirdPartyHint')}</div>

              {/* Etiketler */}
              <div className="full-width kw-tags-block">
                <div className="kw-block-title">{t('page.tagsTitle')}</div>
                <TagInput value={form.tags} onChange={v => setForm(f => ({ ...f, tags: v }))} placeholder={t('page.tagsPlaceholder')} />
              </div>

              {/* Bildirim */}
              <div className="full-width kw-notify-section">
                <div className="kw-block-title">{t('page.notifyTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('page.notifyInfo').replace('{0}', selectedTeamLabel)}</div>
                <label className="checkbox-label">
                  <input type="checkbox" checked={form.notifyEmail} onChange={e => setForm(f => ({ ...f, notifyEmail: e.target.checked }))} />{t('page.notifyEmail')}</label>
              </div>

              {/* Kontrol aralığı */}
              <div className="full-width kw-interval-block">
                <div className="kw-block-title">{t('page.intervalTitle')}</div>
                <div className="field-hint" style={{ marginBottom: 8 }}>{t('page.intervalEvery').replace('{0}', t(INTERVALS[ivIdx].labelKey))}</div>
                <input type="range" className="kw-interval-slider" min={0} max={INTERVALS.length - 1} step={1}
                  value={ivIdx} onChange={e => setForm(f => ({ ...f, intervalSeconds: INTERVALS[Number(e.target.value)].value }))} />
                <div className="kw-interval-ticks">
                  {INTERVALS.map((o, j) => (
                    <span key={o.value} className={`kw-interval-tick${j === ivIdx ? ' active' : ''}`}>{t(o.labelKey)}</span>
                  ))}
                </div>
              </div>

              {/* Gelişmiş */}
              <div className="full-width kw-adv">
                <button type="button" className="kw-adv-toggle" onClick={() => setAdvOpen(o => !o)}>
                  <ChevronDown size={16} className={`kw-adv-chevron${advOpen ? ' open' : ''}`} />
                  <span>{t('page.advanced')}</span>
                </button>
                {advOpen && (
                  <div className="kw-adv-body">
                    <div className="kw-adv-grid">
                      <label><span>{t('page.slowResourceMs')}</span>
                        <input type="number" min="100" step="100" value={form.slowResourceMs} onChange={e => setForm(f => ({ ...f, slowResourceMs: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.resourceConcurrency')}</span>
                        <input type="number" min="1" max="20" value={form.resourceConcurrency} onChange={e => setForm(f => ({ ...f, resourceConcurrency: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.timeoutMs')}</span>
                        <input type="number" min="1000" step="500" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.confirmAttempts')}</span>
                        <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.confirmInterval')}</span>
                        <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.recoveryChecks')}</span>
                        <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
                      <label><span>{t('page.recoveryInterval')}</span>
                        <input type="number" min="10" max="600" value={form.recoveryIntervalSeconds} onChange={e => setForm(f => ({ ...f, recoveryIntervalSeconds: Number(e.target.value) }))} /></label>
                    </div>
                    <label className="checkbox-label" style={{ marginTop: 10 }}>
                      <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('page.active')}</label>
                    <div className="field-hint" style={{ marginTop: 6 }}>ⓘ {t('page.confirmHint')}</div>
                  </div>
                )}
              </div>
            </div>

            {testResult && (
              <div style={{ margin: '2px 0 12px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.error
                  ? { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }
                  : testResult.status === 'OK'
                    ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                    : { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }) }}>
                {testResult.error || testResult.status !== 'OK'
                  ? <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.error
                    ? <><strong>{t('page.testError')}:</strong> {testResult.error}</>
                    : <><strong>{t(`page.status${testResult.status === 'OK' ? 'Ok' : testResult.status === 'DEGRADED' ? 'Degraded' : 'Down'}`)}</strong>
                        {' — '}{testResult.total_resources} {t('page.mResources')} · {testResult.broken_resources} {t('page.mBroken')} · {testResult.mixed_content_count} {t('page.mMixed')}
                        {testResult.http_status != null && <> · HTTP {testResult.http_status}</>}</>}
                </span>
              </div>
            )}
            <div className="modal-actions">
              <button className="btn btn-secondary" style={{ marginRight: 'auto' }} onClick={runTest}
                disabled={testing || !form.url.trim()}>
                <FlaskConical size={14} />{testing ? t('page.testing') : t('page.test')}
              </button>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('page.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('page.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.url.trim() || !form.teamId}>{saving ? '...' : t('page.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
