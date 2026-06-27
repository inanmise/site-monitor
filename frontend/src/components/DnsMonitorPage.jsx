import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Play, Pencil, Trash2, Plus, ChevronDown, Globe, Info, Network } from 'lucide-react'
import DnsDetailModal from './DnsDetailModal.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'

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

const emptyForm = { name: '', domain: '', recordType: 'A', intervalSeconds: 300, teamId: '', expectedValue: '', propagationCheck: false, active: true }

function truncateValue(val, max = 50) {
  if (!val) return '—'
  return val.length > max ? val.substring(0, max) + '…' : val
}

export default function DnsMonitorPage({ systemRole, teamId, teamName }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const canWrite = isAdmin || isTeamAdmin || systemRole === 'USER'   // USER ve üstü: kendi takımı için standalone DNS ekler
  const myTeam = teamId != null ? String(teamId) : null
  const isOwnTeam = (m) => myTeam != null && String(m.team_id) === myTeam
  // Envanter-türevi monitörü yalnız admin yönetir; standalone'u (sertifikadan bağımsız) sahip takım yönetir.
  const canManageRow = (m) => isAdmin || (m.standalone && isOwnTeam(m))
  const canDeleteRow = (m) => m.standalone && (isAdmin || isOwnTeam(m))
  const [monitors, setMonitors] = useState([])
  const [loading, setLoading] = useState(true)
  const [teams, setTeams] = useState([])
  const [detailMonitor, setDetailMonitor] = useState(null)
  const [modal, setModal] = useState(null)
  const [form, setForm] = useState(emptyForm)
  const [saving, setSaving] = useState(false)
  const [checking, setChecking] = useState(null)
  const [deleting, setDeleting] = useState(null)
  const [search, setSearch] = useState('')
  const [teamFilter, setTeamFilter] = useState('all')
  const [infoOpen, setInfoOpen] = useState(false)

  const load = useCallback(async () => {
    const res = await api.monitoring.getDnsMonitors()
    if (res?.success) setMonitors(res.data)
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (!isAdmin) return
    api.admin.getTeams().then(r => { if (r?.success) setTeams(r.data || []) })
  }, [isAdmin])

  const teamSelectOptions = [{ value: '', label: t('app.noTeam') },
    ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]

  function openNew() {
    setForm({ ...emptyForm, teamId: isAdmin ? '' : (myTeam ?? '') })
    setModal('new')
  }
  function openEdit(m) {
    setForm({
      name: m.name || '',
      domain: m.domain || '',
      recordType: m.record_type,
      intervalSeconds: m.interval_seconds,
      teamId: m.team_id != null ? String(m.team_id) : '',
      expectedValue: m.expected_value || '',
      propagationCheck: m.propagation_check === true,
      active: m.active !== false,
    })
    setModal(m)
  }
  function closeEditModal() { setModal(null) }

  async function save() {
    setSaving(true)
    const payload = {
      name: (form.name || '').trim(),
      recordType: form.recordType,
      intervalSeconds: form.intervalSeconds,
      expectedValue: (form.expectedValue || '').trim(),
      propagationCheck: !!form.propagationCheck,
      active: form.active,
    }
    if (modal === 'new') {
      payload.domain = (form.domain || '').trim()
      payload.teamId = form.teamId === '' ? null : Number(form.teamId)
      await api.monitoring.createDnsMonitor(payload)
    } else {
      if (modal.standalone) payload.teamId = form.teamId === '' ? null : Number(form.teamId)
      await api.monitoring.updateDnsMonitor(modal.id, payload)
    }
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

  async function deleteMonitor(m) {
    if (!window.confirm(t('dns.deleteConfirm'))) return
    setDeleting(m.id)
    const res = await api.monitoring.deleteDnsMonitor(m.id)
    if (res?.success) await load()
    setDeleting(null)
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

  const filtered = monitors.filter(m => {
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') { if (m.team_name) return false }
      else if (m.team_name !== teamFilter) return false
    }
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
        {canWrite && (
          <button className="btn btn-sm btn-primary" onClick={openNew}>
            <Plus size={14} />{t('dns.addMonitor')}
          </button>
        )}
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
        {hasTeamOptions && (
          <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
        )}
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
                <th>{t('dns.colTeam')}</th>
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
                  <td className="dns-cell-mono">
                    <strong>{m.domain}</strong>
                    {m.standalone && (
                      <span className="dns-standalone-badge" title={t('dns.standaloneHint')}>{t('dns.standalone')}</span>
                    )}
                  </td>
                  <td>{m.team_name || '—'}</td>
                  <td>
                    <span className="dns-type-badge">{m.record_type}</span>
                  </td>
                  <td className="dns-cell-value">
                    {m.changed && <span className="dns-changed-badge">{t('dns.changed')}</span>}
                    {!m.changed && m.rotated && (
                      <span className="dns-rotated-badge" title={t('dns.rotationTitle')}>{t('dns.rotated')}</span>
                    )}
                    <span className="dns-cell-mono">{truncateValue(m.value)}</span>
                  </td>
                  <td className="dns-cell-num">{m.ttl != null ? `${m.ttl}s` : '—'}</td>
                  <td className="dns-cell-num">{m.response_ms != null ? `${m.response_ms}ms` : '—'}</td>
                  <td className="dns-cell-time">{m.checked_at ? formatDate(m.checked_at) : '—'}</td>
                  <td className="dns-cell-actions" onClick={e => e.stopPropagation()}>
                    {canManageRow(m) && (
                      <button className="btn btn-sm dns-btn-check" disabled={checking === m.id}
                        onClick={() => checkNow(m)} title={t('dns.check')}>
                        <Play size={12} />
                      </button>
                    )}
                    {canManageRow(m) && (
                      <button className="btn btn-sm dns-btn-edit" onClick={() => openEdit(m)} title={t('dns.edit')}>
                        <Pencil size={12} />
                      </button>
                    )}
                    {canDeleteRow(m) && (
                      <button className="btn btn-sm dns-btn-del" disabled={deleting === m.id}
                        onClick={() => deleteMonitor(m)} title={t('dns.delete')}>
                        <Trash2 size={12} />
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
            <div className="modal-icon-hdr modal-icon-hdr--dns">
              <div className="modal-icon-hdr-badge">
                <Network size={20} />
              </div>
              <h3>{modal === 'new' ? t('dns.modalNew') : t('dns.modalEdit')}</h3>
            </div>
            <div className="form-grid form-grid--top">
              <label>
                <span>{t('dns.domain')} {modal === 'new' && <span className="req-star">*</span>}</span>
                {modal === 'new' ? (
                  <input
                    value={form.domain}
                    onChange={e => setForm(f => ({ ...f, domain: e.target.value }))}
                    placeholder={t('dns.domainPlaceholder')}
                    autoFocus
                  />
                ) : (
                  <>
                    <input value={modal.domain} disabled readOnly />
                    <span className="field-hint">{t('dns.domainReadonly')}</span>
                  </>
                )}
              </label>
              {(modal === 'new' || modal.standalone) && (
                <label>
                  <span>{t('dns.team')}</span>
                  {isAdmin
                    ? <SearchableSelect
                        value={form.teamId}
                        onChange={v => setForm(f => ({ ...f, teamId: v }))}
                        options={teamSelectOptions}
                        searchThreshold={2}
                      />
                    : <input value={teamName || t('app.noTeam')} disabled />}
                </label>
              )}
              <label>
                <span>{t('dns.name')}</span>
                <input
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                  placeholder={form.domain || (modal !== 'new' ? modal.domain : '')}
                />
              </label>
              <label>
                <span>{t('dns.recordType')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.recordType}
                  onChange={v => setForm(f => ({ ...f, recordType: v }))}
                  options={RECORD_TYPES.map(rt => ({ value: rt, label: rt }))}
                />
              </label>
              <label>
                <span>{t('dns.interval')} <span className="req-star">*</span></span>
                <SearchableSelect
                  value={form.intervalSeconds}
                  onChange={v => setForm(f => ({ ...f, intervalSeconds: Number(v) }))}
                  options={INTERVALS.map(opt => ({ value: opt.value, label: t(opt.labelKey) }))}
                />
              </label>
              <label className="full-width">
                <span className="dns-expected-label">
                  {t('dns.expectedValue')}
                  {modal !== 'new' && modal.value && (
                    <button type="button" className="dns-pin-btn"
                      onClick={() => setForm(f => ({ ...f, expectedValue: modal.value }))}>
                      {t('dns.pinCurrent')}
                    </button>
                  )}
                </span>
                <textarea
                  rows={3}
                  value={form.expectedValue}
                  onChange={e => setForm(f => ({ ...f, expectedValue: e.target.value }))}
                  placeholder={t('dns.expectedPlaceholder')}
                />
                <span className="field-hint">{t('dns.expectedHint')}</span>
              </label>
              <label className="checkbox-label full-width">
                <input
                  type="checkbox"
                  checked={form.propagationCheck}
                  onChange={e => setForm(f => ({ ...f, propagationCheck: e.target.checked }))}
                />
                {t('dns.propagationCheck')}
              </label>
              <span className="field-hint full-width dns-prop-hint">{t('dns.propagationHint')}</span>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                />
                {t('dns.formActive')}
              </label>
            </div>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={closeEditModal}>{t('dns.cancel')}</button>
              <button
                className="btn btn-primary"
                onClick={save}
                disabled={saving || !form.recordType || (modal === 'new' && !form.domain.trim())}
              >
                {saving ? t('dns.saving') : t('dns.save')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
