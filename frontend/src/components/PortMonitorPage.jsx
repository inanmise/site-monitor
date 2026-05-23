import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Plus, Play, Pencil, Trash2 } from 'lucide-react'

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]

const EMPTY_FORM = { name: '', host: '', port: 443, protocol: 'TCP', intervalSeconds: 60, timeoutMs: 5000 }

export default function PortMonitorPage() {
  const t = useT()
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null)
  const [history, setHistory] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)

  const load = useCallback(async () => {
    const res = await api.monitoring.getPortMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  async function loadHistory(id) {
    setHistoryLoading(true)
    const res = await api.monitoring.getPortHistory(id, 50)
    if (res?.success) setHistory(res.data)
    setHistoryLoading(false)
  }

  function openAdd() { setForm(EMPTY_FORM); setModal('add') }
  function openEdit(m) {
    setForm({ name: m.name, host: m.host, port: m.port, protocol: m.protocol, intervalSeconds: m.interval_seconds, timeoutMs: m.timeout_ms })
    setModal(m)
  }
  function closeModal() { setModal(null) }

  async function save() {
    setSaving(true)
    if (modal === 'add') {
      await api.monitoring.createPortMonitor(form)
    } else {
      await api.monitoring.updatePortMonitor(modal.id, form)
    }
    await load()
    setSaving(false)
    closeModal()
  }

  async function deleteMonitor(m) {
    await api.monitoring.deletePortMonitor(m.id)
    if (selected?.id === m.id) { setSelected(null); setHistory([]) }
    await load()
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

  function selectMonitor(m) {
    if (selected?.id === m.id) { setSelected(null); setHistory([]); return }
    setSelected(m)
    loadHistory(m.id)
  }

  function statusBadge(status) {
    const cls   = status === 'open'   ? 'mon-badge-up'
                : status === 'closed' ? 'mon-badge-down'
                : 'mon-badge-unknown'
    const label = status === 'open'   ? t('port.statusOpen')
                : status === 'closed' ? t('port.statusClosed')
                : t('port.statusUnknown')
    return <span className={`mon-badge ${cls}`}>{label}</span>
  }

  return (
    <div className="mon-page">
      <div className="mon-header">
        <div>
          <h2 className="mon-title">{t('port.title')}</h2>
          <p className="mon-subtitle">{t('port.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openAdd}>
          <Plus size={14} /> {t('port.addMonitor')}
        </button>
      </div>

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="mon-empty">{t('port.noMonitors')}</div>
      ) : (
        <div className="mon-table-wrap">
          <table className="mon-table">
            <thead>
              <tr>
                <th>{t('port.name')}</th>
                <th>{t('port.host')}</th>
                <th>{t('port.port')}</th>
                <th>{t('port.status')}</th>
                <th>{t('port.responseMs')}</th>
                <th>{t('port.lastCheck')}</th>
                <th>{t('port.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {monitors.map(m => (
                <tr
                  key={m.id}
                  className={`mon-row${selected?.id === m.id ? ' mon-row-selected' : ''}${!m.active ? ' mon-row-inactive' : ''}`}
                  onClick={() => selectMonitor(m)}
                >
                  <td className="mon-cell-name">{m.name}</td>
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
                    <button className="btn btn-sm mon-btn-del" onClick={() => deleteMonitor(m)} title={t('port.delete')}>
                      <Trash2 size={12} />
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
          <div className="mon-detail-title">{t('port.history')} — {selected.name} ({selected.host}:{selected.port})</div>
          {historyLoading ? <div className="loading">...</div> : history.length === 0 ? (
            <div className="mon-empty">{t('uptime.noData')}</div>
          ) : (
            <table className="mon-table">
              <thead>
                <tr><th>{t('port.lastCheck')}</th><th>{t('port.status')}</th><th>{t('port.responseMs')}</th></tr>
              </thead>
              <tbody>
                {history.map((c, i) => (
                  <tr key={i}>
                    <td className="mon-cell-time">{formatDate(c.checkedAt || c.checked_at)}</td>
                    <td>
                      <span className={`mon-badge ${c.open ? 'mon-badge-up' : 'mon-badge-down'}`}>
                        {c.open ? t('port.statusOpen') : t('port.statusClosed')}
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
            <h3 className="modal-title">{modal === 'add' ? t('port.modalAdd') : t('port.modalEdit')}</h3>

            <div className="modal-field">
              <label>{t('port.name')}</label>
              <input className="modal-input" value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                placeholder="SMTP Server" />
            </div>
            <div className="modal-field">
              <label>{t('port.host')}</label>
              <input className="modal-input" value={form.host}
                onChange={e => setForm(f => ({ ...f, host: e.target.value }))}
                placeholder="mail.example.com" />
            </div>
            <div className="modal-field">
              <label>{t('port.port')}</label>
              <input className="modal-input" type="number" value={form.port}
                onChange={e => setForm(f => ({ ...f, port: Number(e.target.value) }))} />
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
              <button className="btn btn-secondary" onClick={closeModal}>{t('port.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.name || !form.host || !form.port}>
                {saving ? '...' : t('port.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
