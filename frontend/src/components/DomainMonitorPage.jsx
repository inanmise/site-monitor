import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import MaintenanceBadge from './ui/MaintenanceBadge.jsx'
import { Play, Pencil, X, RefreshCw, Plus, Trash2, CalendarClock, Users, Layers, FlaskConical, Check, AlertTriangle,
  LayoutDashboard, CheckCircle2, TriangleAlert, HelpCircle, ShieldAlert, Building2, Activity, BarChart3, ChevronDown, Calendar } from 'lucide-react'
import AlertHistory from './admin/AlertHistory.jsx'
import DomainRegistrationTab from './DomainRegistrationTab.jsx'
import MonitorStatsBar from './MonitorStatsBar.jsx'
import DomainExpiryTrace from './DomainExpiryTrace.jsx'
const MonitorNotes = lazy(() => import('./MonitorNotes.jsx'))

const REFRESH_INTERVAL = 60
const SORTS = ['days_asc', 'days_desc', 'name']
const emptyForm = {
  name: '', domain: '', groupName: '', teamId: '',
  thresholdsCsv: '60,30,14,7,3,1', warningDays: 30, criticalDays: 7, intervalSeconds: 86400, active: true,
  checkTimeoutMs: '',
}

/** Bitiş tarihi gösterimi — hem WHOIS date-only ("2029-10-26") hem RDAP datetime ("...Z") güvenli. */
function fmtExpiry(iso) {
  if (!iso) return '—'
  const s = String(iso).length <= 10 ? iso + 'T00:00:00Z' : (iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z')
  const d = new Date(s)
  return isNaN(d.getTime()) ? String(iso).substring(0, 10)
    : d.toLocaleDateString('tr-TR', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

export default function DomainMonitorPage({ systemRole, teamId, teamName }) {
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
  const [rangeDays, setRangeDays] = useState(30)
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  const [form, setForm] = useState(emptyForm)
  const [defaults, setDefaults] = useState(null)
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [detailTab, setDetailTab] = useState('control')
  const [diag, setDiag] = useState(null)   // Sorun Tanıla modalı: { domain, loading?, data?, error? }
  const [search, setSearch] = useState('')
  const [teamFilter, setTeamFilter] = useState('all')
  const [groupFilter, setGroupFilter] = useState('all')
  const [sortBy, setSortBy] = useState('days_asc')
  const [infoOpen, setInfoOpen] = useState(false)
  const [statFilter, setStatFilter] = useState(null)
  const [statsVisible, setStatsVisible] = useState(false)
  const [secondsSince, setSecondsSince] = useState(0)
  const countdownRef = useRef(null)
  const deepLinkDone = useRef(false)

  const load = useCallback(async () => {
    const res = await api.monitoring.getDomainMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false); setSecondsSince(0)
  }, [])

  useEffect(() => {
    load()
    const i = setInterval(load, REFRESH_INTERVAL * 1000)
    return () => clearInterval(i)
  }, [load])

  useEffect(() => {
    countdownRef.current = setInterval(() => setSecondsSince(s => s + 1), 1000)
    return () => clearInterval(countdownRef.current)
  }, [])

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.domain) })
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
    const res = await api.monitoring.getDomainHistory(id, { days })
    if (res?.success) setHistory(res.data?.checks ?? [])
    setHistoryLoading(false)
  }
  function selectRange(id, days) { setRangeDays(days); loadHistory(id, days) }
  function openDetail(m) { setSelected(m); setHistory([]); setDetailTab('control'); loadHistory(m.id, rangeDays) }
  function closeDetail() { setSelected(null); setHistory([]) }

  function openNew() {
    setTestResult(null)
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      warningDays: defaults?.warningDays ?? emptyForm.warningDays,
      criticalDays: defaults?.criticalDays ?? emptyForm.criticalDays,
      thresholdsCsv: defaults?.thresholds ?? emptyForm.thresholdsCsv })
    setModal('new')
  }
  function openEdit(m) {
    setTestResult(null)
    setForm({ name: m.name || '', domain: m.domain || '', groupName: m.group_name || '',
      teamId: m.team_id != null ? String(m.team_id) : '',
      thresholdsCsv: m.thresholds_csv || '60,30,14,7,3,1',
      warningDays: m.warning_days ?? 30, criticalDays: m.critical_days ?? 7,
      intervalSeconds: m.interval_seconds ?? 86400, active: m.active !== false,
      checkTimeoutMs: m.check_timeout_ms ?? '' })
    setModal(m)
  }
  function closeEdit() { setModal(null); setTestResult(null) }

  async function runTest() {
    if (!form.domain.trim()) return
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testDomain({
      domain: form.domain.trim(), warningDays: Number(form.warningDays), criticalDays: Number(form.criticalDays),
    })
    setTestResult(res?.success ? res.data : { error: res?.error || t('dom.testError'), status: 'UNKNOWN' })
    setTesting(false)
  }

  async function save() {
    if (!form.domain.trim()) return
    setSaving(true)
    const payload = {
      name: (form.name || form.domain).trim(), domain: form.domain.trim(),
      groupName: form.groupName?.trim() || null, teamId: form.teamId === '' ? null : Number(form.teamId),
      thresholdsCsv: form.thresholdsCsv?.trim() || '60,30,14,7,3,1',
      warningDays: Number(form.warningDays), criticalDays: Number(form.criticalDays),
      intervalSeconds: Number(form.intervalSeconds), active: form.active,
      checkTimeoutMs: form.checkTimeoutMs === '' || form.checkTimeoutMs == null ? null : Number(form.checkTimeoutMs),
    }
    const res = modal === 'new'
      ? await api.monitoring.createDomainMonitor(payload)
      : await api.monitoring.updateDomainMonitor(modal.id, payload)
    await load(); setSaving(false)
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('dom.saved')); closeEdit()
  }

  async function del() {
    if (!modal || modal === 'new') return
    const res = await api.monitoring.deleteDomainMonitor(modal.id)
    await load()
    if (!res?.success) { toast.error(res?.error || 'Error'); return }
    toast.success(t('dom.deleted')); closeEdit()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerDomainCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id, rangeDays) }
    }
    setChecking(null)
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

  const teamOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [monitors, t])
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('dom.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)),
    [monitors, isAdmin, myTeam])
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('dom.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('dom.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  const groupSelectOptions = useMemo(() => groupNames.map(g => ({ value: g, label: g })), [groupNames])
  const sortOptions = useMemo(() => SORTS.map(s => ({ value: s, label: t('dom.sort_' + s) })), [t])

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
    return (m.domain || '').toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q) || (m.registrar || '').toLowerCase().includes(q)
  }), [monitors, teamFilter, groupFilter, search])

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
          {canWrite && <button className="btn btn-sm btn-primary" onClick={openNew}><Plus size={14} />{t('dom.addMonitor')}</button>}
        </div>
      </div>

      <div className="dom-info">
        <button type="button" className="dom-info-toggle" onClick={() => setInfoOpen(o => !o)}>
          <HelpCircle size={15} /><span>{t('dom.howTitle')}</span>
          <ChevronDown size={15} className={`dom-info-chev${infoOpen ? ' open' : ''}`} />
        </button>
        {infoOpen && (
          <ul className="dom-info-body">
            <li>{t('dom.how1')}</li>
            <li>{t('dom.how2')}</li>
            <li>{t('dom.how3')}</li>
            <li>{t('dom.how4')}</li>
            <li>{t('dom.how5')}</li>
            <li>{t('dom.how6')}</li>
            <li>{t('dom.how7')}</li>
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
          <SearchableSelect value={sortBy} onChange={setSortBy} options={sortOptions} />
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('dom.searchPlaceholder')} value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="loading">{canWrite ? t('dom.noMonitorsAdmin') : t('dom.noMonitors')}</div>
      ) : (
        <div className="upt-grid">
          {displayMonitors.map(m => (
            <div key={m.id} className={`upt-card upt-card--${statusCls(m.status)}${m.active_alarm ? ' upt-card--alarm' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                {alarmBadge(m)}<MaintenanceBadge target={m.domain} />
                {m.changed && <span className="dom-changed-ico" title={t('dom.changedTip')}><Activity size={13} /></span>}
                {m.source && <span className="upt-port-tag">{m.source}</span>}
              </div>
              <div className="upt-card-domain" title={m.domain}>{m.domain}</div>
              {m.registrar && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2, wordBreak: 'break-word' }}>
                  <Building2 size={12} />{m.registrar}
                </div>
              )}
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
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('dom.check')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('dom.edit')}><Pencil size={12} /></button>
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
          <div className={`upt-modal upt-modal--${statusCls(selected.status)}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.domain}</span>
              </div>
              <button className="upt-modal-close" onClick={closeDetail}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ color: daysColor(selected.days_remaining) }}>{selected.days_remaining ?? '—'}</span><span className="upt-modal-metric-lbl">{t('dom.daysLeft')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{fmtExpiry(selected.expiry_date)}</span><span className="upt-modal-metric-lbl">{t('dom.expiry')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val" style={{ fontSize: '.8em' }}>{selected.registrar || '—'}</span><span className="upt-modal-metric-lbl">{t('dom.registrar')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.source || '—'}</span><span className="upt-modal-metric-lbl">{t('dom.source')}</span></div>
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
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('dom.tabControl')}</button>
              <button className={`modal-tab${detailTab === 'registration' ? ' active' : ''}`} onClick={() => setDetailTab('registration')}>{t('dom.tabRegistration')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('dom.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'notes' ? ' active' : ''}`} onClick={() => setDetailTab('notes')}>{t('dom.tabGuide')}</button>
            </div>

            {detailTab === 'control' && (<>
              {isAdmin && (
                <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
                  <button type="button" className="btn btn-sm btn-secondary" onClick={() => diagnose(selected)}>
                    <ShieldAlert size={13} />{t('dexp.diagnose')}
                  </button>
                </div>
              )}
              <div className="upt-range-btns">
                {[7, 30, 90, 365].map(d => (
                  <button key={d} type="button" className={`btn btn-sm ${rangeDays === d ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => selectRange(selected.id, d)}>{t(`dom.range${d}`)}</button>
                ))}
              </div>
              {historyLoading ? <div className="upt-modal-loading">...</div> : history.length === 0 ? (
                <div className="upt-modal-loading">{t('dom.noData')}</div>
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid dom-rt-grid upt-rt-head">
                    <span>{t('dom.colTime')}</span><span>{t('dom.colSource')}</span><span>{t('dom.colExpiry')}</span>
                    <span>{t('dom.daysLeft')}</span><span>{t('dom.colStatus')}</span><span>{t('dom.registrar')}</span><span>{t('dom.colIps')}</span>
                  </div>
                  {history.map((c, i) => {
                    const cDays = c.days_remaining ?? c.daysRemaining
                    const cExp = c.expiry_date || c.expiryDate
                    const cAt = c.checked_at || c.checkedAt
                    const cIps = (Array.isArray(c.resolved_ips) ? c.resolved_ips : String(c.resolved_ips ?? c.resolvedIps ?? '').split(',')).map(s => String(s).trim()).filter(Boolean)
                    return (
                    <div key={`${cAt || ''}#${i}`} className="upt-rt-grid dom-rt-grid">
                      <span className="upt-rt-time">{formatDateSec(cAt)}</span>
                      <span>{c.source || '—'}</span>
                      <span>{fmtExpiry(cExp)}</span>
                      <span style={{ color: daysColor(cDays), fontWeight: 600 }}>{cDays ?? '—'}</span>
                      <span className={`dom-st dom-st--${statusCls(c.status)}`}>{statusLabel(c.status)}{c.changed ? ' ⚑' : ''}</span>
                      <span title={c.registrar} style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.registrar || (c.error ? c.error : '—')}</span>
                      <span title={cIps.join(', ')} style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{cIps.length ? cIps.join(', ') : '—'}</span>
                    </div>
                    )
                  })}
                </div>
              )}
            </>)}

            {detailTab === 'registration' && <DomainRegistrationTab monitor={selected} />}
            {detailTab === 'alerts' && <AlertHistory domain={selected.domain} />}
            {detailTab === 'notes' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <MonitorNotes type="DOMAIN" target={selected.domain} />
              </Suspense>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 640, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--domain">
              <div className="modal-icon-hdr-badge"><CalendarClock size={20} /></div>
              <h3>{modal === 'new' ? t('dom.modalNew') : t('dom.modalEdit')}</h3>
            </div>

            <div className="http-type-banner"><CalendarClock size={16} /><span>{t('dom.typeInfo')}</span></div>

            <div className="form-grid form-grid--top">
              <label className="full-width"><span>{t('dom.domain')} <span className="req-star">*</span></span>
                <input value={form.domain} placeholder="example.com" autoFocus onChange={e => setForm(f => ({ ...f, domain: e.target.value }))} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.domainHint')}</div>

              <label><span>{t('dom.name')}</span>
                <input value={form.name} placeholder={form.domain} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('dom.team')}</span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('dom.noTeam')} disabled />}</label>
              <label className="full-width"><span>{t('dom.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('dom.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('dom.noGroup')} /></label>
              <div className="full-width field-hint" style={{ marginTop: -6 }}>{t('dom.groupInfo')}</div>

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
            <div className="modal-actions">
              <span style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
                <button className="btn btn-secondary" onClick={runTest} disabled={testing || !form.domain.trim()}>
                  <FlaskConical size={14} />{testing ? t('dom.testing') : t('dom.test')}
                </button>
                {isAdmin && (
                  <button className="btn btn-secondary" onClick={() => diagnose({ domain: form.domain.trim() })} disabled={!form.domain.trim()}>
                    <ShieldAlert size={14} />{t('dexp.diagnose')}
                  </button>
                )}
              </span>
              {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('dom.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('dom.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.domain.trim()}>{saving ? '...' : t('dom.save')}</button>
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
            {diag.loading && <div className="upt-modal-loading">… {t('dexp.running')}</div>}
            {diag.error && <div className="alert-msg alert-msg--err">{diag.error}</div>}
            {diag.data && <DomainExpiryTrace data={diag.data} />}
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setDiag(null)}>{t('dom.cancel')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
