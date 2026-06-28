import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { Play, Pencil, X, RefreshCw, Plug, Plus, Trash2 } from 'lucide-react'

const INTERVALS = [
  { value: 30,  labelKey: 'ping.interval30s' },
  { value: 60,  labelKey: 'ping.interval1m'  },
  { value: 300, labelKey: 'ping.interval5m'  },
  { value: 900, labelKey: 'ping.interval15m' },
]

const REFRESH_INTERVAL = 60
const PORT_TYPES = ['TCP', 'TLS', 'HTTP', 'BANNER', 'UDP']
const emptyForm = { name: '', host: '', port: '', protocol: 'TCP', expect: '', sendData: '', teamId: '', groupName: '',
  intervalSeconds: 60, timeoutMs: 5000, active: true }

export default function PortMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin                          // ekle/düzenle/sil butonu (takım-kapsamlı)
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam
  const canManageRow = (m) => isAdmin || isOwnTeam(m)              // düzenle + kontrol (otomatik :443/team_id=null → yalnız admin)
  const canDeleteRow = (m) => isAdmin || (isTeamAdmin && isOwnTeam(m))
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
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
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

  // Takım atama seçici yalnız admin'e — takımları bir kez yükle.
  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  // Modal her açıldığında önceki kaydetme hatasını temizle.
  useEffect(() => { setSaveError(null) }, [modal])

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

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? '') })
    setModal('new')
  }
  function openEdit(m) {
    setForm({ name: m.name || '', host: m.host || '', port: m.port ?? '', protocol: m.protocol || 'TCP',
      expect: m.expect || '', sendData: m.send_data || '',
      teamId: m.team_id != null ? String(m.team_id) : '', groupName: m.group_name || '',
      intervalSeconds: m.interval_seconds ?? 60, timeoutMs: m.timeout_ms ?? 5000, active: m.active !== false })
    setModal(m)
  }
  function closeEdit() { setModal(null) }

  async function save() {
    if (!form.host.trim() || !form.port) { setSaveError(t('port.hostRequired')); return }
    setSaving(true); setSaveError(null)
    const payload = {
      name: (form.name || form.host).trim(), host: form.host.trim(), port: Number(form.port),
      protocol: form.protocol?.trim() || 'TCP',
      expect: form.expect?.trim() || null, sendData: form.sendData || null,
      teamId: form.teamId === '' ? null : Number(form.teamId), groupName: form.groupName?.trim() || null,
      intervalSeconds: Number(form.intervalSeconds), timeoutMs: Number(form.timeoutMs), active: form.active,
    }
    const res = modal === 'new'
      ? await api.monitoring.createPortMonitor(payload)
      : await api.monitoring.updatePortMonitor(modal.id, payload)
    setSaving(false)
    if (!res?.success) { setSaveError(res?.error || t('port.saveError')); return }
    await load(); closeEdit()
  }

  async function del() {
    if (!modal || modal === 'new') return
    if (!window.confirm(t('port.deleteConfirm'))) return
    const res = await api.monitoring.deletePortMonitor(modal.id)
    if (!res?.success) { setSaveError(res?.error || t('port.saveError')); return }
    await load(); closeEdit()
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

  // Modal seçicileri: takım (admin → tüm takımlar) + grup (mevcut gruplardan, yeni grup oluşturulabilir).
  const teamSelectOptions = useMemo(() => [{ value: '', label: t('app.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))], [teams, t])
  const groupNames = useMemo(
    () => [...new Set(monitors.map(m => m.group_name).filter(Boolean))].sort((a, b) => a.localeCompare(b)), [monitors])
  const groupSelectOptions = useMemo(() => groupNames.map(g => ({ value: g, label: g })), [groupNames])

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
          {canWrite && (
            <button className="btn btn-sm btn-primary" onClick={openNew}>
              <Plus size={14} />{t('port.addMonitor')}
            </button>
          )}
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
                    {canManageRow(m) && (
                      <button className="btn btn-sm mon-btn-check" disabled={checking === m.id} onClick={() => checkNow(m)} title={t('port.check')}>
                        <Play size={12} />
                      </button>
                    )}
                    {canManageRow(m) && (
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
                      : c.open
                        ? <span className="upt-rt-up">{t('port.detailOk')}</span>
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

      {/* ── Create / Edit Modal ── (overlay tıklamada KAPANMAZ — veri kaybı önlenir; yalnız İptal/Kaydet) */}
      {modal && createPortal(
        <div className="modal-overlay">
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 640, width: '92vw', maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="modal-icon-hdr modal-icon-hdr--port">
              <div className="modal-icon-hdr-badge"><Plug size={20} /></div>
              <h3>{modal === 'new' ? t('port.modalAdd') : t('port.modalEdit')}</h3>
            </div>
            <div className="form-grid">
              <label><span>{t('port.host')} <span className="req-star">*</span></span>
                <input value={form.host} placeholder="1.2.3.4 / host.example.com"
                  onChange={e => setForm(f => ({ ...f, host: e.target.value }))} /></label>
              <label><span>{t('port.port')} <span className="req-star">*</span></span>
                <input type="number" min="1" max="65535" value={form.port}
                  onChange={e => setForm(f => ({ ...f, port: e.target.value }))} /></label>
              <label><span>{t('port.name')}</span>
                <input value={form.name} placeholder={form.host}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))} /></label>
              <label><span>{t('port.checkType')}</span>
                <SearchableSelect value={form.protocol} onChange={v => setForm(f => ({ ...f, protocol: v }))}
                  options={PORT_TYPES.map(v => ({ value: v, label: t(`port.type.${v}`) }))} /></label>
              {(form.protocol === 'HTTP' || form.protocol === 'BANNER' || form.protocol === 'UDP') && (
                <label><span>{form.protocol === 'HTTP' ? t('port.pathLabel') : t('port.sendLabel')}</span>
                  <input value={form.sendData} placeholder={form.protocol === 'HTTP' ? '/health' : ''}
                    onChange={e => setForm(f => ({ ...f, sendData: e.target.value }))} /></label>
              )}
              {(form.protocol === 'HTTP' || form.protocol === 'BANNER') && (
                <label><span>{form.protocol === 'HTTP' ? t('port.expectStatus') : t('port.expectResp')}</span>
                  <input value={form.expect} placeholder={form.protocol === 'HTTP' ? '200, 2xx, 200-399' : '220, +OK, SSH-2.0'}
                    onChange={e => setForm(f => ({ ...f, expect: e.target.value }))} /></label>
              )}
              {(form.protocol === 'HTTP' || form.protocol === 'BANNER' || form.protocol === 'UDP') && (
                <div className="full-width" style={{ fontSize: '.78em', color: 'var(--text-muted)', marginTop: -2, lineHeight: 1.5 }}>
                  ⓘ {t(`port.typeHint.${form.protocol}`)}
                </div>
              )}
              <label><span>{t('port.team')}</span>
                {isAdmin
                  ? <SearchableSelect value={form.teamId} onChange={v => setForm(f => ({ ...f, teamId: v }))} options={teamSelectOptions} searchThreshold={2} />
                  : <input value={teamName || t('app.noTeam')} disabled />}</label>
              <label><span>{t('port.group')}</span>
                <SearchableSelect value={form.groupName} onChange={v => setForm(f => ({ ...f, groupName: v }))}
                  options={[{ value: '', label: t('port.noGroup') }, ...groupSelectOptions]}
                  creatable onCreate={() => {}} searchThreshold={2} placeholder={t('port.noGroup')} /></label>
              <label><span>{t('port.interval')}</span>
                <select value={form.intervalSeconds} onChange={e => setForm(f => ({ ...f, intervalSeconds: Number(e.target.value) }))}>
                  {INTERVALS.map(o => <option key={o.value} value={o.value}>{t(o.labelKey)}</option>)}
                </select></label>
              <label><span>{t('port.timeout')}</span>
                <input type="number" value={form.timeoutMs} onChange={e => setForm(f => ({ ...f, timeoutMs: Number(e.target.value) }))} /></label>
              <label className="checkbox-label">
                <input type="checkbox" checked={form.active} onChange={e => setForm(f => ({ ...f, active: e.target.checked }))} />{t('port.active')}</label>
            </div>
            {saveError && <div className="mon-modal-error">{saveError}</div>}
            <div className="modal-actions">
              {modal !== 'new' && canDeleteRow(modal) && (
                <button className="btn btn-danger" style={{ marginRight: 'auto' }} onClick={del}><Trash2 size={14} />{t('port.delete')}</button>
              )}
              <button className="btn btn-secondary" onClick={closeEdit}>{t('port.cancel')}</button>
              <button className="btn btn-primary" onClick={save} disabled={saving || !form.host.trim() || !form.port}>{saving ? '...' : t('port.save')}</button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
