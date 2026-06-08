import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Play, Pencil, ChevronDown, Globe, Info } from 'lucide-react'
import DnsDetailModal from './DnsDetailModal.jsx'

const RECORD_TYPES = ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS']

const INTERVALS = [
  { value: 300,  labelKey: 'dns.interval5m'  },
  { value: 900,  labelKey: 'dns.interval15m' },
  { value: 3600, labelKey: 'dns.interval1h'  },
]

const INFO_ITEMS = [
  { type: 'A',     descKey: 'dns.recA'     },
  { type: 'AAAA',  descKey: 'dns.recAAAA'  },
  { type: 'CNAME', descKey: 'dns.recCNAME' },
  { type: 'MX',    descKey: 'dns.recMX'    },
  { type: 'TXT',   descKey: 'dns.recTXT'   },
  { type: 'NS',    descKey: 'dns.recNS'    },
  { type: 'SOA',   descKey: 'dns.recSOA'   },
  { type: 'TTL',   descKey: 'dns.ttlExplain' },
]

function truncateValue(val, max = 50) {
  if (!val) return '—'
  return val.length > max ? val.substring(0, max) + '…' : val
}

export default function DnsMonitorPage({ systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [detailMonitor, setDetailMonitor] = useState(null)
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState({})
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [search, setSearch] = useState('')
  const [infoOpen, setInfoOpen] = useState(false)

  const load = useCallback(async () => {
    const res = await api.monitoring.getDnsMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  function openEdit(m) {
    setForm({ recordType: m.record_type, intervalSeconds: m.interval_seconds })
    setModal(m)
  }
  function closeEditModal() { setModal(null) }

  async function save() {
    setSaving(true)
    await api.monitoring.updateDnsMonitor(modal.id, form)
    await load()
    setSaving(false)
    closeEditModal()
  }

  async function checkNow(m) {
    setChecking(m.id)
    const res = await api.monitoring.triggerDnsCheck(m.id)
    if (res?.success) {
      setMonitors(prev => prev.map(x => x.id === m.id ? { ...x, ...res.data } : x))
    }
    setChecking(null)
  }

  const filtered = monitors.filter(m => {
    if (!search.trim()) return true
    const s = search.toLowerCase()
    return m.domain?.toLowerCase().includes(s) || m.record_type?.toLowerCase().includes(s)
  })

  return (
    <div className="dns-page">
      <div className="dns-header">
        <div className="dns-title-row">
          <Globe size={22} />
          <div>
            <h2 className="dns-title">{t('dns.title')}</h2>
            <p className="dns-subtitle">{t('dns.subtitle')}</p>
          </div>
        </div>
      </div>

      <div className={`dns-info-card${infoOpen ? ' dns-info-open' : ''}`}>
        <button className="dns-info-toggle" onClick={() => setInfoOpen(v => !v)} type="button">
          <Info size={16} />
          <span className="dns-info-title">{t('dns.infoTitle')}</span>
          <ChevronDown size={14} className={`dns-info-chevron${infoOpen ? ' open' : ''}`} />
        </button>
        {infoOpen && (
          <div className="dns-info-body">
            <p className="dns-info-intro">{t('dns.infoIntro')}</p>
            <div className="dns-info-grid">
              {INFO_ITEMS.map(item => (
                <div key={item.type} className="dns-info-row">
                  <span className="dns-info-key">{item.type}</span>
                  <span className="dns-info-desc">{t(item.descKey)}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <div className="dns-toolbar">
        <input
          className="dns-search-input"
          type="text"
          placeholder={t('dns.searchPlaceholder')}
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
        {search && (
          <button className="dns-search-clear" onClick={() => setSearch('')}>✕</button>
        )}
        <div className="dns-counter">{t('dns.monitorCount', filtered.length)}</div>
      </div>

      {loading ? (
        <div className="loading">{t('dns.loading')}</div>
      ) : monitors.length === 0 ? (
        <div className="mon-empty">{t('dns.noMonitors')}</div>
      ) : (
        <div className="admin-table-wrap dns-table-wrap">
          <table className="admin-table dns-table">
            <thead>
              <tr>
                <th>{t('dns.domain')}</th>
                <th>{t('dns.recordType')}</th>
                <th>{t('dns.currentValue')}</th>
                <th>{t('dns.ttl')}</th>
                <th>{t('dns.responseMs')}</th>
                <th>{t('dns.lastCheck')}</th>
                <th>{t('dns.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(m => (
                <tr
                  key={m.id}
                  className={`dns-row${!m.active ? ' dns-row-inactive' : ''}`}
                  onClick={() => setDetailMonitor(m)}
                >
                  <td className="dns-cell-mono"><strong>{m.domain}</strong></td>
                  <td>
                    <span className="dns-type-badge">{m.record_type}</span>
                  </td>
                  <td className="dns-cell-value">
                    {m.changed && <span className="dns-changed-badge">{t('dns.changed')}</span>}
                    <span className="dns-cell-mono">{truncateValue(m.value)}</span>
                  </td>
                  <td className="dns-cell-num">{m.ttl != null ? `${m.ttl}s` : '—'}</td>
                  <td className="dns-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="dns-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="dns-cell-actions" onClick={e => e.stopPropagation()}>
                    {isAdmin && (
                      <button className="btn btn-sm dns-btn-check" disabled={checking === m.id}
                        onClick={() => checkNow(m)} title={t('dns.check')}>
                        <Play size={12} />
                      </button>
                    )}
                    {isAdmin && (
                      <button className="btn btn-sm dns-btn-edit" onClick={() => openEdit(m)} title={t('dns.edit')}>
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

      {detailMonitor && (
        <DnsDetailModal monitor={detailMonitor} onClose={() => setDetailMonitor(null)} />
      )}

      {modal && (
        <div className="modal-overlay" onClick={closeEditModal}>
          <div className="modal-box" onClick={e => e.stopPropagation()}>
            <h3 className="modal-title">{t('dns.modalEdit')}</h3>

            <div className="modal-field">
              <label>{t('dns.domain')}</label>
              <div className="modal-input mon-readonly-field">{modal.domain}</div>
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
              <button className="btn btn-secondary" onClick={closeEditModal}>{t('dns.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving}>
                {saving ? '...' : t('dns.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
