import { useState, useEffect, useCallback, useRef } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Play, Pencil, X, RefreshCw } from 'lucide-react'

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]

const REFRESH_INTERVAL = 60

export default function PortMonitorPage() {
  const t = useT()
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState({})
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [search, setSearch] = useState('')
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

  async function loadHistory(id) {
    setHistoryLoading(true)
    const res = await api.monitoring.getPortHistory(id, 50)
    if (res?.success) setHistory(res.data)
    setHistoryLoading(false)
  }

  async function openModal(m) {
    setSelected(m)
    setHistory([])
    loadHistory(m.id)
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
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id) }
    }
    setChecking(null)
  }

  const displayMonitors = search.trim()
    ? monitors.filter(m => m.host.toLowerCase().includes(search.trim().toLowerCase()))
    : monitors

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
        <div className="upt-toolbar" style={{ justifyContent: 'flex-end', marginBottom: '14px' }}>
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
                  <td className="mon-cell-num">{m.port}</td>
                  <td>{statusBadge(m.status)}</td>
                  <td className="mon-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="mon-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="mon-cell-actions" onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('port.check')}>
                      <Play size={12} />
                    </button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('port.edit')}>
                      <Pencil size={12} />
                    </button>
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
              {selected.response_ms != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.response_ms}ms</span>
                  <span className="upt-modal-metric-lbl">{t('port.responseMs')}</span>
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
            {historyLoading ? (
              <div className="upt-modal-loading">...</div>
            ) : history.length === 0 ? (
              <div className="upt-modal-loading">{t('uptime.noData')}</div>
            ) : (
              <div className="upt-rt-list">
                {history.slice(0, 30).map((c, i) => (
                  <div key={i} className="upt-rt-row">
                    <span className="upt-rt-time">{formatDate(c.checkedAt || c.checked_at)}</span>
                    <span className={c.open ? 'upt-rt-up' : 'upt-rt-down'}>
                      {c.open ? t('port.statusOpen') : t('port.statusClosed')}
                    </span>
                    {(c.responseMs ?? c.response_ms) != null && (
                      <span className="upt-rt-time">{c.responseMs ?? c.response_ms}ms</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>,
        document.body
      )}

      {/* ── Edit Modal ── */}
      {modal && createPortal(
        <div className="upt-modal-overlay" onClick={closeEdit}>
          <div className="upt-modal" onClick={e => e.stopPropagation()} style={{ maxWidth: 420 }}>
            <div className="upt-modal-header">
              <span className="upt-modal-domain">{t('port.modalEdit')}</span>
              <button className="upt-modal-close" onClick={closeEdit}><X size={18} /></button>
            </div>
            <div className="upt-modal-divider" />
            <div className="modal-field">
              <label>{t('port.host')}</label>
              <div className="modal-input mon-readonly-field">{modal.host}:{modal.port}</div>
            </div>
            <div className="modal-field">
              <label>{t('port.interval')}</label>
              <select className="modal-input" value={form.intervalSeconds}
                onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                {INTERVALS.map(opt => (
                  <option key={opt.value} value={opt.value}>{t(opt.labelKey)}</option>
                ))}
              </select>
            </div>
            <div className="modal-field">
              <label>{t('port.timeout')}</label>
              <input className="modal-input" type="number" value={form.timeoutMs}
                onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} />
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
