import { useState, useEffect, useCallback, useRef } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { Play, Pencil, X, RefreshCw, Plus, Trash2, Target, Users } from 'lucide-react'

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]
const REFRESH_INTERVAL = 60
const emptyForm = { name: '', url: '', keyword: '', condition: 'NOT_CONTAINS', teamId: '',
  intervalSeconds: 60, timeoutMs: 10000, active: true }

export default function KeywordMonitorPage({ systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [rangeDays, setRangeDays] = useState(1)
  const [summary, setSummary] = useState({ total: 0, down: 0 })
  const [modal, setModal] = useState(null)          // 'new' | monitor | null
  const [form, setForm] = useState(emptyForm)
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [search, setSearch] = useState('')
  const [teamFilter, setTeamFilter] = useState('all')
  const [secondsSince, setSecondsSince] = useState(0)
  const countdownRef = useRef(null)

  const load = useCallback(async () => {
    const res = await api.monitoring.getKeywordMonitors()
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

  async function loadHistory(id, days = rangeDays) {
    setHistoryLoading(true)
    const res = await api.monitoring.getKeywordHistory(id, { days })
    if (res?.success) {
      setHistory(res.data?.checks ?? [])
      setSummary({ total: res.data?.total ?? 0, down: res.data?.down ?? 0 })
    }
    setHistoryLoading(false)
  }
  function selectRange(id, days) { setRangeDays(days); loadHistory(id, days) }
  function openDetail(m) { setSelected(m); setHistory([]); loadHistory(m.id, rangeDays) }
  function closeDetail() { setSelected(null); setHistory([]) }

  function openNew() { setForm(emptyForm); setModal('new') }
  function openEdit(m) {
    setForm({ name: m.name || '', url: m.url || '', keyword: m.keyword || '',
      condition: m.condition || 'NOT_CONTAINS', teamId: m.team_id != null ? String(m.team_id) : '',
      intervalSeconds: m.interval_seconds ?? 60, timeoutMs: m.timeout_ms ?? 10000, active: m.active !== false })
    setModal(m)
  }
  function closeEdit() { setModal(null) }

  async function save() {
    if (!form.url.trim() || !form.keyword.trim()) return
    setSaving(true)
    const payload = {
      name: (form.name || form.url).trim(), url: form.url.trim(), keyword: form.keyword,
      condition: form.condition, teamId: form.teamId === '' ? null : Number(form.teamId),
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs), active: form.active,
    }
    if (modal === 'new') await api.monitoring.createKeywordMonitor(payload)
    else await api.monitoring.updateKeywordMonitor(modal.id, payload)
    await load(); setSaving(false); closeEdit()
  }

  async function del() {
    if (!modal || modal === 'new') return
    await api.monitoring.deleteKeywordMonitor(modal.id)
    await load(); closeEdit()
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

  const teamOptions = (() => {
    const names = new Set(); let hasNone = false
    for (const m of monitors) { if (m.team_name) names.add(m.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach(n => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  })()
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')
  const teamSelectOptions = [{ value: '', label: t('keyword.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]

  const displayMonitors = monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
    }
    if (!search.trim()) return true
    const q = search.trim().toLowerCase()
    return (m.url || '').toLowerCase().includes(q) || (m.keyword || '').toLowerCase().includes(q)
  })

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
          {isAdmin && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('keyword.addMonitor')}
            </button>
          )}
        </div>
      </div>

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', gap: 8 }}>
          {hasTeamOptions && <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />}
          <input className="upt-search" type="text" placeholder={t('keyword.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="loading">{isAdmin ? t('keyword.noMonitorsAdmin') : t('keyword.noMonitors')}</div>
      ) : (
        <div className="upt-grid">
          {displayMonitors.map(m => (
            <div key={m.id} className={`upt-card ${cardClass(m)}${!m.active ? ' mon-row-inactive' : ''}`}
              onClick={() => openDetail(m)}>
              <div className="upt-card-top">
                {statusBadge(m)}
                <span className="upt-port-tag">
                  {m.condition === 'CONTAINS' ? t('keyword.condContainsShort') : t('keyword.condNotContainsShort')}
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
              </div>
              <div className="upt-card-foot">
                <span>{m.checked_at ? formatDate(m.checked_at) : ''}</span>
                {isAdmin && (
                  <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('keyword.check')}><Play size={12} /></button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('keyword.edit')}><Pencil size={12} /></button>
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
          <div className={`upt-modal upt-modal--${selected.status === 'up' ? 'up' : selected.status === 'unknown' ? 'unknown' : 'down'}`} onClick={e => e.stopPropagation()}>
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                {statusBadge(selected)}
                <span className="upt-modal-domain">{selected.url}</span>
              </div>
              <button className="upt-modal-close" onClick={closeDetail}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-summary">
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">{summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}</span>
                <span className="upt-modal-metric-lbl">{t('keyword.sumOk')}</span>
              </div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{summary.total}</span><span className="upt-modal-metric-lbl">{t('keyword.sumTotal')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{summary.down}</span><span className="upt-modal-metric-lbl">{t('keyword.sumIncidents')}</span></div>
              <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.keyword}</span><span className="upt-modal-metric-lbl">{t('keyword.keyword')}</span></div>
              {selected.http_status != null && <div className="upt-modal-metric"><span className="upt-modal-metric-val">{selected.http_status}</span><span className="upt-modal-metric-lbl">HTTP</span></div>}
              {selected.checked_at && <div className="upt-modal-metric"><span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.checked_at)}</span><span className="upt-modal-metric-lbl">{t('keyword.lastCheck')}</span></div>}
            </div>
            <div className="upt-modal-divider" />
            <div className="upt-modal-section-title">{t('keyword.history')}</div>
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
                {history.slice(0, 200).map((c, i) => (
                  <div key={i} className="upt-rt-grid">
                    <span className="upt-rt-time">{formatDate(c.checkedAt || c.checked_at)}</span>
                    <span className={c.ok ? 'upt-rt-up' : 'upt-rt-down'}>{c.ok ? t('keyword.statusOk') : (c.error ? t('keyword.statusError') : t('keyword.statusViolation'))}</span>
                    <span className="upt-rt-ms">{c.httpStatus ?? c.http_status ?? '—'}</span>
                    {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span>
                      : <span className="upt-rt-ms">{c.found ? t('keyword.found') : t('keyword.notFound')}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Create / Edit Modal ── */}
      {modal && createPortal(
        <div className="modal-overlay" onClick={closeEdit}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 460 }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Target size={20} /></div>
              <h3>{modal === 'new' ? t('keyword.modalNew') : t('keyword.modalEdit')}</h3>
            </div>
            <div className="form-grid">
              <label className="full-width"><span>{t('keyword.url')} <span className="req-star">*</span></span>
                <input value={form.url} placeholder="https://example.com" onChange={e => setForm(f => ({ ...f, url: e.target.value }))} /></label>
              <label className="full-width"><span>{t('keyword.condition')}</span>
                <SearchableSelect value={form.condition} onChange={v => setForm(f => ({ ...f, condition: v }))}
                  options={[{ value: 'NOT_CONTAINS', label: t('keyword.condNotContains') }, { value: 'CONTAINS', label: t('keyword.condContains') }]} /></label>
              <label className="full-width"><span>{t('keyword.keyword')} <span className="req-star">*</span></span>
                <input value={form.keyword} placeholder="SUCCESS" onChange={e => setForm(f => ({ ...f, keyword: e.target.value }))} /></label>
              <label className="full-width"><span>{t('keyword.name')}</span>
                <input value={form.name} placeholder={form.url} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label className="full-width"><span>{t('keyword.team')}</span>
                <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} /></label>
              <label><span>{t('keyword.interval')}</span>
                <select value={form.intervalSeconds} onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                  {INTERVALS.map(o => <option key={o.value} value={o.value}>{t(o.labelKey)}</option>)}
                </select></label>
              <label><span>{t('keyword.timeout')}</span>
                <input type="number" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
              <label className="checkbox-label full-width">
                <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('keyword.active')}</label>
            </div>
            <div className="modal-actions">
              {modal !== 'new' && <button className="btn btn-danger" style={{ marginRight: 'auto' }} onClick={del}><Trash2 size={14} />{t('keyword.delete')}</button>}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('keyword.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.url.trim() || !form.keyword.trim()}>{saving ? '...' : t('keyword.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
