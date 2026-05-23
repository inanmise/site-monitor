import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Play, Pencil } from 'lucide-react'

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]

export default function PingMonitorPage() {
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

  const load = useCallback(async () => {
    const res = await api.monitoring.getPingMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  async function loadHistory(id) {
    setHistoryLoading(true)
    const res = await api.monitoring.getPingHistory(id, 50)
    if (res?.success) setHistory(res.data)
    setHistoryLoading(false)
  }

  function openEdit(m) {
    setForm({ name: m.name, intervalSeconds: m.interval_seconds, timeoutMs: m.timeout_ms })
    setModal(m)
  }
  function closeModal() { setModal(null) }

  async function save() {
    setSaving(true)
    await api.monitoring.updatePingMonitor(modal.id, form)
    await load()
    setSaving(false)
    closeModal()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerPingCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
      if (selected?.id === m.id) { setSelected(res.data); loadHistory(m.id) }
    }
    setChecking(null)
  }

  function selectMonitor(m) {
    if (selected?.id === m.id) { setSelected(null); setHistory([]); return }
    setSelected(m)
    loadHistory(m.id)
  }

  function statusBadge(status) {
    const cls = status === 'up' ? 'mon-badge-up' : status === 'down' ? 'mon-badge-down' : 'mon-badge-unknown'
    const label = status === 'up' ? t('ping.statusUp') : status === 'down' ? t('ping.statusDown') : t('ping.statusUnknown')
    return <span className={`mon-badge ${cls}`}>{label}</span>
  }

  return (
    <div className="mon-page">
      <div className="mon-header">
        <div>
          <h2 className="mon-title">{t('ping.title')}</h2>
          <p className="mon-subtitle">{t('ping.subtitle')}</p>
        </div>
      </div>

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="mon-empty">{t('ping.noMonitors')}</div>
      ) : (
        <div className="mon-table-wrap">
          <table className="mon-table">
            <thead>
              <tr>
                <th>{t('ping.host')}</th>
                <th>{t('ping.status')}</th>
                <th>{t('ping.responseMs')}</th>
                <th>{t('ping.lastCheck')}</th>
                <th>{t('ping.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {monitors.map(m => (
                <tr
                  key={m.id}
                  className={`mon-row${selected?.id === m.id ? ' mon-row-selected' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
                  onClick={() => selectMonitor(m)}
                >
                  <td className="mon-cell-mono">{m.host}</td>
                  <td>{statusBadge(m.status)}</td>
                  <td className="mon-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="mon-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="mon-cell-actions" onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('ping.check')}>
                      <Play size={12} />
                    </button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('ping.edit')}>
                      <Pencil size={12} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && (
        <div className="mon-detail">
          <div className="mon-detail-title">{t('ping.history')} — {selected.host}</div>
          {historyLoading ? <div className="loading">...</div> : history.length === 0 ? (
            <div className="mon-empty">{t('uptime.noData')}</div>
          ) : (
            <table className="mon-table">
              <thead>
                <tr><th>{t('ping.lastCheck')}</th><th>{t('ping.status')}</th><th>{t('ping.responseMs')}</th></tr>
              </thead>
              <tbody>
                {history.map((c, i) => (
                  <tr key={i}>
                    <td className="mon-cell-time">{formatDate(c.checkedAt || c.checked_at)}</td>
                    <td>
                      <span className={`mon-badge ${c.reachable ? 'mon-badge-up' : 'mon-badge-down'}`}>
                        {c.reachable ? t('ping.reachable') : t('ping.unreachable')}
                      </span>
                    </td>
                    <td className="mon-cell-num">{c.responseMs != null ? `${c.responseMs}ms` : c.response_ms != null ? `${c.response_ms}ms` : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {modal && (
        <div className="modal-overlay" onClick={closeModal}>
          <div className="modal-box" onClick={e => e.stopPropagation()}>
            <h3 className="modal-title">{t('ping.modalEdit')}</h3>

            <div className="modal-field">
              <label>{t('ping.host')}</label>
              <div className="modal-input mon-readonly-field">{modal.host}</div>
            </div>
            <div className="modal-field">
              <label>{t('ping.interval')}</label>
              <select className="modal-input" value={form.intervalSeconds}
                onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                {INTERVALS.map(opt => (
                  <option key={opt.value} value={opt.value}>{t(opt.labelKey)}</option>
                ))}
              </select>
            </div>
            <div className="modal-field">
              <label>{t('ping.timeout')}</label>
              <input className="modal-input" type="number" value={form.timeoutMs}
                onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} />
            </div>

            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={closeModal}>{t('ping.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving}>
                {saving ? '...' : t('ping.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
