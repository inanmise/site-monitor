import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { Play, Pencil, X, RefreshCw, Plus, Trash2, Radio, Users, Layers, FlaskConical, Check, AlertTriangle } from 'lucide-react'
import AlertHistory from './admin/AlertHistory.jsx'
// recharts ağır — yalnız "Süre Grafiği" sekmesi açılınca yüklensin.
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]
const REFRESH_INTERVAL = 60
const emptyForm = { name: '', host: '', ipVersion: 'auto', groupName: '', teamId: '',
  intervalSeconds: 60, timeoutMs: 5000, packetCount: 4, confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, active: true }

export default function PingMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
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
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [defaults, setDefaults] = useState(null)   // per-tip varsayılan aralık/timeout (Kontrol Sıklığı ayarı)
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)
  const [search, setSearch] = useState('')
  const [teamFilter, setTeamFilter] = useState('all')
  const [groupFilter, setGroupFilter] = useState('all')
  const [secondsSince, setSecondsSince] = useState(0)
  const [detailTab, setDetailTab] = useState('control')
  const countdownRef = useRef(null)
  const deepLinkDone = useRef(false)

  // Modal her açıldığında/değiştiğinde önceki test sonucunu temizle.
  useEffect(() => { setTestResult(null) }, [modal])

  const load = useCallback(async () => {
    const res = await api.monitoring.getPingMonitors()
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

  // Yeni monitör için per-tip varsayılan kontrol aralığı + timeout (Genel Ayarlar → Kontrol Sıklığı).
  useEffect(() => {
    api.monitoring.monitorDefaults?.()?.then(r => { if (r?.success) setDefaults(r.data?.ping) })
  }, [])

  // E-posta CTA deep-link: ?monitor=<id> → ilgili monitörün detayını aç (bir kez), paramı temizle.
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
    const res = await api.monitoring.getPingHistory(id, { days })
    if (res?.success) {
      setHistory(res.data?.checks ?? [])
      setSummary({ total: res.data?.total ?? 0, down: res.data?.down ?? 0 })
    }
    setHistoryLoading(false)
  }
  function selectRange(id, days) { setRangeDays(days); loadHistory(id, days) }
  function openDetail(m) { setSelected(m); setHistory([]); setDetailTab('control'); loadHistory(m.id, rangeDays) }
  function closeDetail() { setSelected(null); setHistory([]) }

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? ''),
      intervalSeconds: defaults?.intervalSeconds ?? emptyForm.intervalSeconds,
      timeoutMs: defaults?.timeoutMs ?? emptyForm.timeoutMs })
    setModal('new')
  }
  function openEdit(m) {
    setForm({ name: m.name || '', host: m.host || '', ipVersion: m.ip_version || 'auto', groupName: m.group_name || '',
      teamId: m.team_id != null ? String(m.team_id) : '', intervalSeconds: m.interval_seconds ?? 60,
      timeoutMs: m.timeout_ms ?? 5000, packetCount: m.packet_count ?? 4,
      confirmAttempts: m.confirm_attempts ?? 3, confirmIntervalSeconds: m.confirm_interval_seconds ?? 30, recoveryChecks: m.recovery_checks ?? 3,
      active: m.active !== false })
    setModal(m)
  }
  function closeEdit() { setModal(null) }

  async function save() {
    if (!form.host.trim()) return
    setSaving(true)
    const payload = {
      name: (form.name || form.host).trim(), host: form.host.trim(), ipVersion: form.ipVersion,
      groupName: form.groupName?.trim() || null,
      teamId: form.teamId === '' ? null : Number(form.teamId), intervalSeconds: Number(form.intervalSeconds),
      timeoutMs: Number(form.timeoutMs), packetCount: Number(form.packetCount),
      confirmAttempts: Number(form.confirmAttempts), confirmIntervalSeconds: Number(form.confirmIntervalSeconds), recoveryChecks: Number(form.recoveryChecks),
      active: form.active,
    }
    if (modal === 'new') await api.monitoring.createPingMonitor(payload)
    else await api.monitoring.updatePingMonitor(modal.id, payload)
    await load(); setSaving(false); closeEdit()
  }

  // Kaydetmeden formdaki host/parametrelerle bir kez ping atar; ping atılabildi mi + koşul (erişilebilirlik) sağlandı mı.
  async function runTest() {
    if (!form.host.trim()) return
    setTesting(true); setTestResult(null)
    const res = await api.monitoring.testPingMonitor({
      host: form.host.trim(), ipVersion: form.ipVersion,
      packetCount: Number(form.packetCount), timeoutMs: Number(form.timeoutMs),
    })
    setTestResult(res?.success ? res.data : { error: res?.error || t('ping.testError') })
    setTesting(false)
  }

  async function del() {
    if (!modal || modal === 'new') return
    await api.monitoring.deletePingMonitor(modal.id)
    await load(); closeEdit()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerPingCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id, rangeDays) }
    }
    setChecking(null)
  }

  // Türetilmiş listeler memoize — 1sn countdown her saniye render tetikler.
  const teamOptions = useMemo(() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  }, [monitors, t])
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('ping.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  // Gruplar takıma özgü: kullanıcı yalnız kendi takımının gruplarını görür/seçer (admin tümünü).
  const groupMonitors = useMemo(
    () => (isAdmin ? monitors : monitors.filter(m => myTeam != null && String(m.team_id) === myTeam)),
    [monitors, isAdmin, myTeam])
  const groupNames = useMemo(
    () => [...new Set(groupMonitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [groupMonitors])
  const hasGroupOptions = groupNames.length > 0
  const groupFilterOptions = useMemo(() => [{ value: 'all', label: t('ping.allGroups') },
    ...groupNames.map(g => ({ value: g, label: g })),
    ...(groupMonitors.some(m => !m.group_name) ? [{ value: '__none__', label: t('ping.noGroup') }] : [])],
    [groupNames, groupMonitors, t])
  const groupSelectOptions = useMemo(() => groupNames.map(g => ({ value: g, label: g })), [groupNames])

  const displayMonitors = useMemo(() => monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
    }
    if (groupFilter !== 'all') {
      if (groupFilter === '__none__') { if (m.group_name) return false }
      else if (m.group_name !== groupFilter) return false
    }
    if (!search.trim()) return true
    return (m.host || '').toLowerCase().includes(search.trim().toLowerCase())
  }), [monitors, teamFilter, groupFilter, search])

  function cardClass(m) {
    if (m.status === 'up') return 'upt-card--up'
    if (m.status === 'down') return 'upt-card--down'
    return 'upt-card--unknown'
  }
  function statusBadge(m) {
    const s = m?.status
    const cls = s === 'up' ? 'upt-badge--up' : s === 'down' ? 'upt-badge--down' : 'upt-badge--unknown'
    const label = s === 'up' ? t('ping.statusUp') : s === 'down' ? t('ping.statusDown')
      : s === 'na' ? t('ping.statusNa') : t('ping.statusUnknown')
    return <span className={`upt-badge ${cls}`}><span className="upt-badge-dot" />{label}</span>
  }

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('ping.title')}</h2>
          <p className="upt-subtitle">{t('ping.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('ping.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={load}>
            <RefreshCw size={14} />{t('ping.refresh')}
          </button>
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('ping.addMonitor')}
            </button>
          )}
        </div>
      </div>

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {hasGroupOptions && <SearchableSelect value={groupFilter} onChange={setGroupFilter} options={groupFilterOptions} searchThreshold={2} />}
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('ping.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="loading">{canWrite ? t('ping.noMonitorsAdmin') : t('ping.noMonitors')}</div>
      ) : (
        <div className="upt-grid">
          {displayMonitors.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                <span className="upt-port-tag">{m.ip_version && m.ip_version !== 'auto' ? m.ip_version.toUpperCase() : 'ICMP'}</span>
              </div>
              <div className="upt-card-domain" title={m.host}>{m.host}</div>
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
                  <span className="upt-metric-val">{m.rtt_ms != null ? `${m.rtt_ms}ms` : '—'}</span>
                  <span className="upt-metric-lbl">{t('ping.rtt')}</span>
                </div>
                {m.packet_loss != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">%{m.packet_loss}</span>
                    <span className="upt-metric-lbl">{t('ping.loss')}</span>
                  </div>
                )}
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDate(m.checked_at) : ''}</span>
                {canManageRow(m) && (
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('ping.check')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('ping.edit')}><Pencil size={12} /></button>
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
          <div className={`upt-modal upt-modal--${selected.status === 'up' ? 'up' : selected.status === 'down' ? 'down' : 'unknown'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.host}</span>
              </div>
              <button className="upt-modal-close" onClick={closeDetail}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric" title={t('ping.sumUptimeHint')}>
                <span className="upt-modal-metric-val">{summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('ping.sumUptime')}{summary.total > 0 ? ` · ${summary.total - summary.down}/${summary.total}` : ''}</span>
              </div>
              <div className="upt-modal-metric" title={t('ping.sumTotalHint')}><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('ping.sumTotal')}</span></div>
              <div className="upt-modal-metric" title={t('ping.sumIncidentsHint')}><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('ping.sumIncidents')}</span></div>
              {selected.rtt_ms != null && <div className="upt-modal-metric" title={t('ping.rttHint')}><span className="upt-modal-metric-val">{selected.rtt_ms}ms</span><span className="upt-modal-metric-lbl">{t('ping.rtt')}</span></div>}
              {selected.packet_loss != null && <div className="upt-modal-metric" title={t('ping.lossHint')}><span className="upt-modal-metric-val">%{selected.packet_loss}</span><span className="upt-modal-metric-lbl">{t('ping.loss')}</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('ping.lastCheck')}</span></div>}
            </div>
            {selected.status === 'na' && <div className="alert-msg" style={{ marginTop: 4 }}>{t('ping.naHint')}</div>}
            <div className="upt-modal-divider" />
            <div className="modal-tabs">
              <button className={`modal-tab${detailTab === 'control' ? ' active' : ''}`} onClick={() => setDetailTab('control')}>{t('ping.tabControl')}</button>
              <button className={`modal-tab${detailTab === 'alerts' ? ' active' : ''}`} onClick={() => setDetailTab('alerts')}>{t('ping.tabAlerts')}</button>
              <button className={`modal-tab${detailTab === 'chart' ? ' active' : ''}`} onClick={() => setDetailTab('chart')}>{t('ping.tabChart')}</button>
            </div>

            {detailTab === 'control' && (<>
              <div className="upt-range-btns">
                {[1, 7, 15, 30].map(d => (
                  <button key={d} type="button" className={`btn btn-sm ${rangeDays === d ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => selectRange(selected.id, d)}>{t(`ping.range${d}d`)}</button>
                ))}
              </div>
              {historyLoading ? <div className="upt-modal-loading">...</div> : history.length === 0 ? (
                <div className="upt-modal-loading">{t('ping.noData')}</div>
              ) : (
                <div className="upt-rt-list">
                  <div className="upt-rt-grid upt-rt-head">
                    <span>{t('ping.colTime')}</span><span>{t('ping.colStatus')}</span><span>{t('ping.rtt')}</span><span>{t('ping.colDetail')}</span>
                  </div>
                  {history.slice(0, 200).map((c, i) => (
                    <div key={`${c.checkedAt || c.checked_at || ''}#${i}`} className="upt-rt-grid">
                      <span className="upt-rt-time">{formatDate(c.checkedAt || c.checked_at)}</span>
                      <span className={c.up ? 'upt-rt-up' : 'upt-rt-down'}>{c.up ? t('ping.statusUp') : t('ping.statusDown')}</span>
                      <span className="upt-rt-ms">{(c.rttMs ?? c.rtt_ms) != null ? `${c.rttMs ?? c.rtt_ms}ms` : '—'}</span>
                      {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span>
                        : <span className="upt-rt-ms">{(c.packetLoss ?? c.packet_loss) != null ? `%${c.packetLoss ?? c.packet_loss}` : '—'}</span>}
                    </div>
                  ))}
                </div>
              )}
            </>)}

            {detailTab === 'alerts' && <AlertHistory domain={selected.host} />}

            {detailTab === 'chart' && (
              <Suspense fallback={<div className="upt-modal-loading">…</div>}>
                <ResponseTimeChart monitorId={selected.id} kind="ping" />
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
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Radio size={20} /></div>
              <h3>{modal === 'new' ? t('ping.modalNew') : t('ping.modalEdit')}</h3>
            </div>
            <div className="form-grid">
              <label className="full-width"><span>{t('ping.host')} <span className="req-star">*</span></span>
                <input value={form.host} placeholder="1.2.3.4 / host.example.com" onChange={e => setForm(f => ({ ...f, host: e.target.value }))} /></label>
              <label><span>{t('ping.ipVersion')}</span>
                <SearchableSelect value={form.ipVersion} onChange={v => setForm(f => ({ ...f, ipVersion: v }))}
                  options={[{ value: 'auto', label: t('ping.ipAuto') }, { value: 'v4', label: 'IPv4' }, { value: 'v6', label: 'IPv6' }]} /></label>
              <label><span>{t('ping.packetCount')}</span>
                <input type="number" min="1" max="10" value={form.packetCount} onChange={e => setForm(f => ({ ...f, packetCount: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.name')}</span>
                <input value={form.name} placeholder={form.host} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('ping.team')}</span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('ping.noTeam')} disabled />}</label>
              <label><span>{t('ping.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('ping.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('ping.noGroup')} /></label>
              <label><span>{t('ping.interval')}</span>
                <select value={form.intervalSeconds} onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                  {INTERVALS.map(o => <option key={o.value} value={o.value}>{t(o.labelKey)}</option>)}
                </select></label>
              <label><span>{t('ping.timeout')}</span>
                <input type="number" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.confirmAttempts')}</span>
                <input type="number" min="0" max="10" value={form.confirmAttempts} onChange={e => setForm(f => ({ ...f, confirmAttempts: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.confirmInterval')}</span>
                <input type="number" min="10" max="600" value={form.confirmIntervalSeconds} onChange={e => setForm(f => ({ ...f, confirmIntervalSeconds: Number(e.target.value) }))} /></label>
              <label><span>{t('ping.recoveryChecks')}</span>
                <input type="number" min="1" max="20" value={form.recoveryChecks} onChange={e => setForm(f => ({ ...f, recoveryChecks: Number(e.target.value) }))} /></label>
              <div className="full-width" style={{ fontSize: '.8em', color: 'var(--text-muted)', marginTop: -2, lineHeight: 1.5 }}>
                ⓘ {t('ping.confirmHint')}
              </div>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('ping.active')}</label>
            </div>
            {testResult && (
              <div style={{ margin: '0 0 4px', padding: '10px 12px', borderRadius: 8, fontSize: '.86em', lineHeight: 1.5,
                display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid',
                ...(testResult.condition_met
                  ? { background: '#f0fdf4', borderColor: '#bbf7d0', color: '#15803d' }
                  : testResult.na
                    ? { background: '#fff7ed', borderColor: '#fed7aa', color: '#b45309' }
                    : { background: '#fef2f2', borderColor: '#fecaca', color: '#b91c1c' }) }}>
                {testResult.condition_met
                  ? <Check size={16} style={{ flexShrink: 0, marginTop: 1 }} />
                  : <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />}
                <span>
                  {testResult.sent === undefined
                    ? <><strong>{t('ping.testError')}:</strong> {testResult.error}</>
                    : testResult.na
                      ? <><strong>{t('ping.testNa')}</strong></>
                      : testResult.condition_met
                        ? <><strong>{t('ping.testMet')}</strong> — {t('ping.testReachable')}
                            {testResult.rtt_ms != null && <> · RTT {testResult.rtt_ms}ms</>}
                            {testResult.packet_loss != null && <> · {t('ping.loss')} %{testResult.packet_loss}</>}</>
                        : <><strong>{t('ping.testNotMet')}</strong> — {t('ping.testUnreachable')}
                            {testResult.packet_loss != null && <> · {t('ping.loss')} %{testResult.packet_loss}</>}</>}
                </span>
              </div>
            )}
            <div className="modal-actions">
              <div style={{ display: 'flex', gap: 8, marginRight: 'auto' }}>
                <button className="btn btn-secondary" onClick={runTest} disabled={testing || !form.host.trim()}>
                  <FlaskConical size={14} />{testing ? t('ping.testing') : t('ping.test')}
                </button>
                {modal !== 'new' && canDeleteRow(modal) && <button className="btn btn-danger" onClick={del}><Trash2 size={14} />{t('ping.delete')}</button>}
              </div>
              <button className="btn btn-secondary" onClick={closeEdit}>{t('ping.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.host.trim()}>{saving ? '...' : t('ping.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
