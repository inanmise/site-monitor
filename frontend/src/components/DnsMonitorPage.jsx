import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Plus, Play, Pencil, Trash2 } from 'lucide-react'

const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']

const INTERVALS = [
  { value: 300,  labelKey: 'dns.interval5m'  },
  { value: 900,  labelKey: 'dns.interval15m' },
  { value: 3600, labelKey: 'dns.interval1h'  },
]

const EMPTY_FORM = { name: '', domain: '', recordType: 'A', intervalSeconds: 300 }

export default function DnsMonitorPage() {
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
    const res = await api.monitoring.getDnsMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  async function loadHistory(id) {
    setHistoryLoading(true)
    const res = await api.monitoring.getDnsHistory(id, 50)
    if (res?.success) setHistory(res.data)
    setHistoryLoading(false)
  }

  function openAdd() { setForm(EMPTY_FORM); setModal('add') }
  function openEdit(m) {
    setForm({ name: m.name, domain: m.domain, recordType: m.record_type, intervalSeconds: m.interval_seconds })
    setModal(m)
  }
  function closeModal() { setModal(null) }

  async function save() {
    setSaving(true)
    if (modal === 'add') {
      await api.monitoring.createDnsMonitor(form)
    } else {
      await api.monitoring.updateDnsMonitor(modal.id, form)
    }
    await load()
    setSaving(false)
    closeModal()
  }

  async function deleteMonitor(m) {
    await api.monitoring.deleteDnsMonitor(m.id)
    if (selected?.id === m.id) { setSelected(null); setHistory([]) }
    await load()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerDnsCheck(m.id)
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

  function truncateValue(val, max = 50) {
    if (!val) return '—'
    return val.length > max ? val.substring(0, max) + '…' : val
  }

  return (
    <div className="mon-page">
      <div className="mon-header">
        <div>
          <h2 className="mon-title">{t('dns.title')}</h2>
          <p className="mon-subtitle">{t('dns.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openAdd}>
          <Plus size={14} /> {t('dns.addMonitor')}
        </button>
      </div>

      {loading ? <div className="loading">...</div> : monitors.length === 0 ? (
        <div className="mon-empty">{t('dns.noMonitors')}</div>
      ) : (
        <div className="mon-table-wrap">
          <table className="mon-table">
            <thead>
              <tr>
                <th>{t('dns.name')}</th>
                <th>{t('dns.domain')}</th>
                <th>{t('dns.recordType')}</th>
                <th>{t('dns.currentValue')}</th>
                <th>{t('dns.lastCheck')}</th>
                <th>{t('dns.actions')}</th>
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
                  <td className="mon-cell-mono">{m.domain}</td>
                  <td>
                    <span className="dns-type-badge">{m.record_type}</span>
                  </td>
                  <td className="mon-cell-value">
                    {m.changed && <span className="dns-changed-badge">{t('dns.changed')}</span>}
                    <span className="mon-cell-mono">{truncateValue(m.value)}</span>
                  </td>
                  <td className="mon-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="mon-cell-actions" onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('dns.check')}>
                      <Play size={12} />
                    </button>
                    <button className="btn btn-sm mon-btn-edit" onClick={() => openEdit(m)} title={t('dns.edit')}>
                      <Pencil size={12} />
                    </button>
                    <button className="btn btn-sm mon-btn-del" onClick={() => deleteMonitor(m)} title={t('dns.delete')}>
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
          <div className="mon-detail-title">{t('dns.history')} — {selected.name}</div>
          {historyLoading ? <div className="loading">...</div> : history.length === 0 ? (
            <div className="mon-empty">{t('uptime.noData')}</div>
          ) : (
            <table className="mon-table">
              <thead>
                <tr>
                  <th>{t('dns.lastCheck')}</th>
                  <th>{t('dns.recordType')}</th>
                  <th>{t('dns.currentValue')}</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {history.map((c, i) => (
                  <tr key={i} className={c.changed ? 'dns-row-changed' : ''}>
                    <td className="mon-cell-time">{formatDate(c.checkedAt || c.checked_at)}</td>
                    <td><span className="dns-type-badge">{c.recordType || c.record_type}</span></td>
                    <td className="mon-cell-mono dns-value-cell">
                      {c.value || '—'}
                      {c.changed && c.previousValue && (
                        <div className="dns-prev-value">← {c.previousValue}</div>
                      )}
                    </td>
                    <td>
                      {c.changed
                        ? <span className="dns-changed-badge">{t('dns.changed')}</span>
                        : <span className="dns-nochange-badge">{t('dns.noChange')}</span>
                      }
                    </td>
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
            <h3 className="modal-title">{modal === 'add' ? t('dns.modalAdd') : t('dns.modalEdit')}</h3>

            <div className="modal-field">
              <label>{t('dns.name')}</label>
              <input className="modal-input" value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                placeholder="Google A Record" />
            </div>
            <div className="modal-field">
              <label>{t('dns.domain')}</label>
              <input className="modal-input" value={form.domain}
                onChange={e => setForm(f => ({ ...f, domain: e.target.value }))}
                placeholder="example.com" />
            </div>
            <div className="modal-field">
              <label>{t('dns.recordType')}</label>
              <select className="modal-input" value={form.recordType}
                onChange={e => setForm(f => ({ ...f, recordType: e.target.value }))}>
                {RECORD_TYPES.map(rt => (
                  <option key={rt} value={rt}>{rt}</option>
                ))}
              </select>
            </div>
            <div className="modal-field">
              <label>{t('dns.interval')}</label>
              <select className="modal-input" value={form.intervalSeconds}
                onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                {INTERVALS.map(opt => (
                  <option key={opt.value} value={opt.value}>{t(opt.labelKey)}</option>
                ))}
              </select>
            </div>

            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={closeModal}>{t('dns.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.name || !form.domain}>
                {saving ? '...' : t('dns.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
