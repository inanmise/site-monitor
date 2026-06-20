import { useState, useEffect, useCallback, useRef } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { Play, Pencil, X, RefreshCw, Plug } from 'lucide-react'

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]

const REFRESH_INTERVAL = 60

export default function PortMonitorPage({ systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [rangeDays, setRangeDays] = useState(1)
  const [summary, setSummary] = useState({ total: 0, down: 0 })
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState({})
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [search, setSearch] = useState('')
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

  async function loadHistory(id, days = rangeDays) {
    setHistoryLoading(true)
    const res = await api.monitoring.getPortHistory(id, { days })
    if (res?.success) {
      setHistory(res.data?.checks ?? [])
      setSummary({ total: res.data?.total ?? 0, down: res.data?.down ?? 0 })
    }
    setHistoryLoading(false)
  }

  function selectRange(id, days) { setRangeDays(days); loadHistory(id, days) }

  async function openModal(m) {
    setSelected(m)
    setHistory([])
    loadHistory(m.id, rangeDays)
  }

  function closeModal() { setSelected(null); setHistory([]) }

  function openEdit(m) {
    setForm({ intervalSeconds: m.interval_seconds, timeoutMs: m.timeout_ms })
    setModal(m)
  }
  function closeEdit() { setModal(null) }

  async function save() {
    setSaving(true)
    await api.monitoring.updatePortMonitor(modal.id, form)
    await load()
    setSaving(false)
    closeEdit()
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

  const displayMonitors = monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
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
        </div>
      </div>

      {!loading && monitors.length > 0 && (
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', marginBottom: '14px', gap: 8 }}>
          {hasTeamOptions && (
            <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
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
                  className={`mon-row${!m.active ? ' mon-row-inactive' : ''}`}
                  onClick={() => openModal(m)}
                >
                  <td className="mon-cell-mono">{m.host}</td>
                  <td>{m.team_name || '—'}</td>
                  <td className="mon-cell-num">{m.port}</td>
                  <td>{statusBadge(m.status)}</td>
                  <td className="mon-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="mon-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="mon-cell-actions" onClick={e => e.stopPropagation()}>
                    {isAdmin && (
                      <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('port.check')}>
                        <Play size={12} />
                      </button>
                    )}
                    {isAdmin && (
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
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">
                  {summary.total > 0 ? `%${Math.round((summary.total - summary.down) * 1000 / summary.total) / 10}` : '—'}
                </span>
                <span className="upt-modal-metric-lbl">{t('port.sumUptime')}</span>
              </div>
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">{summary.total}</span>
                <span className="upt-modal-metric-lbl">{t('port.sumTotal')}</span>
              </div>
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val">{summary.down}</span>
                <span className="upt-modal-metric-lbl">{t('port.sumIncidents')}</span>
              </div>
              {selected.response_ms != null && (
                <div className="upt-modal-metric">
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
            <div className="upt-modal-section-title">{t('port.history')}</div>
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
                {history.slice(0, 200).map((c, i) => (
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
                      : <span className="upt-rt-ms">—</span>}
                  </div>
                ))}
                {history.length > 200 && (
                  <div className="upt-modal-loading">{t('port.historyCapped')}</div>
                )}
              </div>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Edit Modal ── */}
      {modal && createPortal(
        <div className="modal-overlay" onClick={closeEdit}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 420 }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Plug size={20} /></div>
              <h3>{t('port.modalEdit')}</h3>
            </div>
            <div className="form-grid">
              <label className="full-width">
                {t('port.host')}
                <input value={`${modal.host}:${modal.port}`} readOnly disabled className="mon-readonly-field" />
              </label>
              <label className="full-width">
                {t('port.interval')}
                <select value={form.intervalSeconds}
                  onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                  {INTERVALS.map(opt => (
                    <option key={opt.value} value={opt.value}>{t(opt.labelKey)}</option>
                  ))}
                </select>
              </label>
              <label className="full-width">
                {t('port.timeout')}
                <input type="number" value={form.timeoutMs}
                  onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} />
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={closeEdit}>{t('port.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving}>
                {saving ? '...' : t('port.save')}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
