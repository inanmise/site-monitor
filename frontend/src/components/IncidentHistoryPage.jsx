import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { RefreshCcw, Plus, ChevronLeft, ChevronRight, Pencil, Trash2, ListChecks } from 'lucide-react'
import MarkdownEditor from './ui/MarkdownEditor.jsx'
import DateTimeField from './ui/DateTimeField.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const STATUSES   = ['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED']
const CATEGORIES = ['DATABASE', 'NETWORK', 'CERTIFICATE', 'APPLICATION', 'INFRASTRUCTURE', 'OTHER']
const SEV_COLOR  = { CRITICAL: '#dc2626', HIGH: '#ea580c', MEDIUM: '#d97706', LOW: '#16a34a' }

const EMPTY = {
  title: '', occurred_at: '', severity: 'HIGH', status: 'OPEN', category: 'APPLICATION',
  service: '', channel: '', team_id: '', team_name: '', detected_at: '', resolved_at: '',
  rca_summary: '', description: '', resolution_steps: '', business_impact: '',
  affected_services: '', sla_breached: false, error_budget_burn_pct: '', duration_minutes: '', tags: '',
}

// ── Modül seviyesi alan bileşenleri (stabil kimlik → input remount/odak kaybı OLMAZ) ──
function TextInput({ label, value, onChange, disabled, type = 'text', req, full }) {
  return (
    <label className={full ? 'full-width' : undefined}>
      <span>{label}{req && <span className="req-star"> *</span>}</span>
      <input type={type} value={value ?? ''} disabled={disabled} onChange={e => onChange(e.target.value)} />
    </label>
  )
}
function DateInput({ label, value, onChange, disabled, req, min }) {
  return (
    <label>
      <span>{label}{req && <span className="req-star"> *</span>}</span>
      <DateTimeField value={value} onChange={onChange} disabled={disabled}
                     placeholder={label} clearable={!req} min={min} />
    </label>
  )
}
function SelectInput({ label, value, onChange, disabled, options, req }) {
  return (
    <label>
      <span>{label}{req && <span className="req-star"> *</span>}</span>
      <select value={value ?? ''} disabled={disabled} onChange={e => onChange(e.target.value)}>
        {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </label>
  )
}
/** Yönetilen dropdown (kanal/domain) — sabit liste + yeni değer ekleme (creatable).
 *  onCreate yeni değeri kalıcılaştırır (api.incidents.addOption) ve listeyi yeniler. */
function CreatableSelect({ label, value, onChange, options, disabled, onCreate, onDelete }) {
  const opts = [{ value: '', label: '—' }, ...options.map(o => ({ value: o, label: o }))]
  return (
    <label>
      <span>{label}</span>
      <SearchableSelect value={value ?? ''} onChange={onChange} disabled={disabled}
        options={opts} creatable={!disabled} onCreate={onCreate}
        onDelete={disabled ? undefined : onDelete} placeholder="—" />
    </label>
  )
}
/** Zengin metin alanı — Weekly Reports ile aynı markdown editör (full-width).
 *  editable iken görsel yükleme aktif; incidentId yoksa (create modu) taslak yüklenir,
 *  kaydedince backend görseli olaya bağlar. makeUniqueCaption = aynı incident içinde
 *  görsel isimlerini tekilleştirir (karışmasın). */
function MdArea({ label, value, onChange, editable, incidentId, makeUniqueCaption }) {
  const uploadImage = editable
    ? async (file, caption) => {
        const res = await api.incidents.uploadImage(incidentId, file, caption)
        return res?.success ? `/api/incidents/images/${res.data.id}` : null
      }
    : undefined
  return (
    <div className="full-width" style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <span style={{ fontSize: '.88em', fontWeight: 600 }}>{label}</span>
      <MarkdownEditor value={value} onChange={onChange} editable={editable} height={170}
                      uploadImage={uploadImage} makeUniqueCaption={makeUniqueCaption} />
    </div>
  )
}
function CheckInput({ label, checked, onChange, disabled }) {
  return (
    <label className="checkbox-label">
      <input type="checkbox" checked={!!checked} disabled={disabled} onChange={e => onChange(e.target.checked)} />
      {label}
    </label>
  )
}
/** Etiket metninden tutarlı bir renk tonu (aynı etiket hep aynı renk, farklı etiket farklı). */
function tagHue(s) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360
  return h
}
/** Etiket chip input — text yazıp Enter (veya virgül) → altına renkli etiket. CSV saklanır. */
function TagInput({ label, value, onChange, disabled, t }) {
  const [text, setText] = useState('')
  const tags = (value || '').split(',').map(s => s.trim()).filter(Boolean)
  const add = () => {
    const v = text.trim()
    if (v && !tags.some(x => x.toLowerCase() === v.toLowerCase())) onChange([...tags, v].join(', '))
    setText('')
  }
  const remove = (tag) => onChange(tags.filter(x => x !== tag).join(', '))
  return (
    <label className="full-width">
      <span>{label}</span>
      {!disabled && (
        <input type="text" value={text} placeholder={t('inc.tagsHint')}
          onChange={e => setText(e.target.value)} onBlur={add}
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }} />
      )}
      {tags.length > 0 && (
        <div className="tag-chips">
          {tags.map(tag => {
            const h = tagHue(tag)
            return (
              <span key={tag} className="tag-chip"
                style={{ background: `hsl(${h},70%,93%)`, color: `hsl(${h},65%,30%)`, borderColor: `hsl(${h},70%,78%)` }}>
                {tag}
                {!disabled && <button type="button" className="tag-chip-x" aria-label="remove"
                  style={{ color: `hsl(${h},60%,38%)` }} onClick={() => remove(tag)}>×</button>}
              </span>
            )
          })}
        </div>
      )}
    </label>
  )
}

/**
 * SRE Olay & Hata Geçmişi — Raporlar menüsü altında, manuel ledger.
 * Aranabilir tablo + executive özet kartları + günlük trend + detay/düzenle modalı.
 * Yetki: incidents.view (görüntüleme), incidents.manage (yaz/sil). Sayfa+API enforce eder.
 */
export default function IncidentHistoryPage() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { canView, canEdit, canExecute } = usePermissions()
  const allowView = canView('incidents.view')
  const allowManage = canEdit('incidents.manage')
  const allowDelete = canExecute('incidents.delete') // silme yalnız TEAM_ADMIN/ADMIN

  const [rows, setRows]   = useState([])
  const [total, setTotal] = useState(0)
  const [page, setPage]   = useState(0)
  const [size, setSize]   = useState(20)
  const [loading, setLoading] = useState(false)
  const [trends, setTrends]   = useState(null)
  const [filters, setFilters] = useState({ q: '', severity: '', category: '', status: '', channel: '', since: '', until: '' })
  const [modal, setModal] = useState(null) // { mode:'view'|'edit'|'create', form }
  const [saving, setSaving] = useState(false)
  const [channelOpts, setChannelOpts] = useState([])
  const [domainOpts, setDomainOpts]   = useState([])
  const [teams, setTeams]             = useState([])
  const [selected, setSelected]       = useState(() => new Set()) // toplu transfer seçimi (id'ler)
  const [transferTeam, setTransferTeam] = useState('')

  const load = useCallback(async () => {
    if (!allowView) return
    setLoading(true)
    try {
      const res = await api.incidents.list({ ...filters, page, size })
      if (res?.success) { setRows(res.data ?? []); setTotal(res.total ?? 0) }
      else toast.error(res?.error || t('inc.loadError'))
    } catch { toast.error(t('inc.loadError')) }
    setLoading(false)
  }, [filters, page, size, allowView]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadTrends = useCallback(async () => {
    if (!allowView) return
    const res = await api.incidents.trends(filters.since || undefined, filters.until || undefined)
    if (res?.success) setTrends(res.data)
  }, [filters.since, filters.until, allowView]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadOptions = useCallback(async () => {
    if (!allowView) return
    try {
      const [ch, dm] = await Promise.all([api.incidents.options('CHANNEL'), api.incidents.options('DOMAIN')])
      if (ch?.success) setChannelOpts(ch.data ?? [])
      if (dm?.success) setDomainOpts(dm.data ?? [])
    } catch { /* sessiz — dropdown boş kalır, yine de yeni değer eklenebilir */ }
  }, [allowView])

  const loadTeams = useCallback(async () => {
    if (!allowManage) return // takım seçimi yalnız yazma yetkisi olanlara lazım
    try {
      const res = await api.admin.getTeams()
      if (res?.success) setTeams(res.data ?? [])
    } catch { /* sessiz */ }
  }, [allowManage])

  const addOption = useCallback(async (type, value) => {
    const res = await api.incidents.addOption(type, value)
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }, [loadOptions]) // eslint-disable-line react-hooks/exhaustive-deps

  const deleteOption = useCallback(async (type, value) => {
    const ok = await showConfirm({
      title: t('inc.optDeleteTitle'), message: t('inc.optDeleteConfirm', value),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    const res = await api.incidents.deleteOption(type, value)
    if (res?.success) await loadOptions()
    else toast.error(res?.error || t('inc.saveError'))
  }, [loadOptions]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load() }, [load])
  useEffect(() => { loadTrends() }, [loadTrends])
  useEffect(() => { loadOptions() }, [loadOptions])
  useEffect(() => { loadTeams() }, [loadTeams])
  useEffect(() => { setPage(0) }, [filters, size])
  useEffect(() => { setSelected(new Set()) }, [filters, page, size]) // sayfa/filtre değişince seçim sıfırlanır

  if (!allowView) return <div className="empty-state">{t('inc.noAccess')}</div>

  const totalPages = Math.max(1, Math.ceil(total / size))
  const setF = (k, v) => setFilters(f => ({ ...f, [k]: v }))

  // Özet kartları filtre görevi görür — tarih penceresini (since/until) korur, diğer
  // boyut filtrelerini sıfırlar, kartın boyutunu uygular. Aktif kart tekrar tıklanırsa kalkar.
  function applyCardFilter(kind) {
    setFilters(prev => {
      const active = (kind === 'critical' && prev.severity === 'CRITICAL')
        || (kind === 'sla' && prev.slaBreached === true)
        || (kind === 'open' && prev.open === true)
        || (kind === 'resolved' && prev.status === 'RESOLVED')
      const base = { ...prev, q: '', severity: '', category: '', status: '', channel: '',
        slaBreached: undefined, open: undefined }
      if (kind === 'total' || active) return base
      if (kind === 'critical') return { ...base, severity: 'CRITICAL' }
      if (kind === 'sla')      return { ...base, slaBreached: true }
      if (kind === 'open')     return { ...base, open: true }
      if (kind === 'resolved') return { ...base, status: 'RESOLVED' }
      return base
    })
  }

  async function save() {
    const f = modal.form
    if (!f.title?.trim() || !f.occurred_at?.trim() || !f.team_id) { toast.error(t('inc.required')); return }
    if (f.detected_at && f.resolved_at && new Date(f.resolved_at) < new Date(f.detected_at)) {
      toast.error(t('inc.resolvedBeforeDetected')); return
    }
    const payload = { ...f }
    if (payload.error_budget_burn_pct === '') delete payload.error_budget_burn_pct
    if (payload.duration_minutes === '') delete payload.duration_minutes
    setSaving(true)
    try {
      const res = modal.mode === 'create'
        ? await api.incidents.create(payload)
        : await api.incidents.update(modal.form.id, payload)
      setSaving(false)
      if (res?.success) { toast.success(t('inc.saved')); setModal(null); load(); loadTrends() }
      else toast.error(res?.error || t('inc.saveError'))
    } catch { setSaving(false); toast.error(t('inc.saveError')) }
  }

  async function remove(rec) {
    const ok = await showConfirm({
      title: t('inc.deleteTitle'), message: t('inc.deleteConfirm', rec.title),
      variant: 'danger', confirmText: t('inc.delete'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    const res = await api.incidents.remove(rec.id)
    if (res?.success) { toast.success(t('inc.deleted')); setModal(null); load(); loadTrends() }
    else toast.error(res?.error || t('inc.saveError'))
  }

  const allOnPage = rows.length > 0 && rows.every(r => selected.has(r.id))
  const toggleSel = (id) => setSelected(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n })
  const toggleAll = () => setSelected(s => {
    const n = new Set(s); if (allOnPage) rows.forEach(r => n.delete(r.id)); else rows.forEach(r => n.add(r.id)); return n
  })

  async function doTransfer() {
    const tm = teams.find(x => String(x.id) === String(transferTeam))
    if (!tm || selected.size === 0) return
    const ok = await showConfirm({
      title: t('inc.transferTitle'), message: t('inc.transferConfirm', selected.size, tm.name),
      confirmText: t('inc.transferBtn'), cancelText: t('inc.cancel'),
    })
    if (!ok) return
    const res = await api.incidents.transfer([...selected], tm.id, tm.name)
    if (res?.success) {
      toast.success(t('inc.transferred', res.data?.transferred ?? selected.size))
      setSelected(new Set()); setTransferTeam(''); load(); loadTrends()
    } else toast.error(res?.error || t('inc.saveError'))
  }

  const sevBadge = (s) => <span style={{ color: SEV_COLOR[s] || '#64748b', fontWeight: 700 }}>{t('inc.sev' + s) || s}</span>
  const sum = trends?.summary || {}
  const daily = trends?.daily || []
  const maxDay = daily.reduce((m, d) => Math.max(m, Number(d.count) || 0), 0) || 1

  return (
    <div className="admin-section">
      <div className="admin-section-header">
        <h3>{t('inc.title')}</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn-secondary btn-sm-p" onClick={() => { load(); loadTrends() }} disabled={loading}>
            <RefreshCcw size={13} /> {t('inc.refresh')}
          </button>
          {allowManage && (
            <button className="btn btn-primary btn-sm-p" onClick={() => setModal({ mode: 'create', form: { ...EMPTY } })}>
              <Plus size={14} /> {t('inc.new')}
            </button>
          )}
        </div>
      </div>

      {/* Executive özet kartları — tıklanınca filtre uygular (proje stats-panel deseni) */}
      <div className="stats-panel" style={{ gridTemplateColumns: 'repeat(5,1fr)' }}>
        {[['sumTotal', sum.total, 'total', 'total'], ['sumCritical', sum.critical, 'critical', 'critical'],
          ['sumSla', sum.sla_breached, 'alert', 'sla'], ['sumOpen', sum.open, 'warning', 'open'],
          ['sumResolved', sum.resolved, 'valid', 'resolved']].map(([k, v, variant, kind]) => {
          const active = (kind === 'critical' && filters.severity === 'CRITICAL')
            || (kind === 'sla' && filters.slaBreached === true)
            || (kind === 'open' && filters.open === true)
            || (kind === 'resolved' && filters.status === 'RESOLVED')
            || (kind === 'total' && !filters.severity && !filters.slaBreached && !filters.open
                && filters.status !== 'RESOLVED' && !filters.category && !filters.channel && !filters.q)
          return (
            <div key={k} className={`stat-item stat-item-${variant}`} role="button" tabIndex={0}
                 title={t('inc.filterByCard')} onClick={() => applyCardFilter(kind)}
                 onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); applyCardFilter(kind) } }}
                 style={{ cursor: 'pointer', ...(active ? { outline: '2px solid var(--primary)', outlineOffset: '-2px' } : {}) }}>
              <div className={`stat-value stat-value-${variant}`}>{v ?? 0}</div>
              <span className="stat-label">{t('inc.' + k)}</span>
            </div>
          )
        })}
      </div>

      {/* Günlük trend (basit bar) */}
      {daily.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <div className="show-section-header">{t('inc.trend')}</div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 70, padding: '4px 0' }}>
            {daily.map(d => (
              <div key={d.day} title={`${d.day}: ${d.count}`}
                   style={{ flex: 1, minWidth: 4, background: '#11557a', borderRadius: '3px 3px 0 0',
                            height: `${Math.max(6, (Number(d.count) / maxDay) * 64)}px` }} />
            ))}
          </div>
        </div>
      )}

      {/* Filtreler */}
      <div className="inv-stats-pills" style={{ marginBottom: 12, gap: 8, alignItems: 'center' }}>
        <input className="filter-input" placeholder={t('inc.search')} value={filters.q}
               onChange={e => setF('q', e.target.value)} style={{ minWidth: 200 }} />
        <select className="filter-select" value={filters.severity} onChange={e => setF('severity', e.target.value)}>
          <option value="">{t('inc.filterSeverity')}</option>
          {SEVERITIES.map(s => <option key={s} value={s}>{t('inc.sev' + s)}</option>)}
        </select>
        <select className="filter-select" value={filters.category} onChange={e => setF('category', e.target.value)}>
          <option value="">{t('inc.filterCategory')}</option>
          {CATEGORIES.map(c => <option key={c} value={c}>{t('inc.cat' + c)}</option>)}
        </select>
        <select className="filter-select" value={filters.status} onChange={e => setF('status', e.target.value)}>
          <option value="">{t('inc.filterStatus')}</option>
          {STATUSES.map(s => <option key={s} value={s}>{t('inc.st' + s)}</option>)}
        </select>
        <select className="filter-select" value={filters.channel} onChange={e => setF('channel', e.target.value)}>
          <option value="">{t('inc.filterChannel')}</option>
          {channelOpts.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('inc.since')}
          value={filters.since} onChange={v => setF('since', v ? v + 'T00:00:00' : '')} />
        <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('inc.until')}
          value={filters.until} onChange={v => setF('until', v ? v + 'T23:59:59' : '')} />
      </div>

      {/* Toplu transfer çubuğu — seçim varken */}
      {allowManage && selected.size > 0 && (
        <div className="inv-stats-pills" style={{ marginBottom: 10, gap: 8, alignItems: 'center',
          background: '#f1f5f9', padding: '8px 12px', borderRadius: 6 }}>
          <span style={{ fontWeight: 700, fontSize: '.9em' }}>{t('inc.selectedN', selected.size)}</span>
          <select className="filter-select" value={transferTeam} onChange={e => setTransferTeam(e.target.value)}>
            <option value="">{t('inc.transferTo')}</option>
            {teams.map(tm => <option key={tm.id} value={String(tm.id)}>{tm.name}</option>)}
          </select>
          <button className="btn btn-primary btn-sm-p" disabled={!transferTeam} onClick={doTransfer}>{t('inc.transferBtn')}</button>
          <button className="btn btn-secondary btn-sm-p" onClick={() => setSelected(new Set())}>{t('inc.clearSel')}</button>
        </div>
      )}

      {/* Tablo */}
      {loading && <div className="loading">{t('inc.loading')}</div>}
      {!loading && rows.length === 0 && <div className="empty-state">{t('inc.noResults')}</div>}
      {!loading && rows.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead><tr>
              {allowManage && <th style={{ width: 28 }}>
                <input type="checkbox" checked={allOnPage} onChange={toggleAll} title={t('inc.selectAll')} />
              </th>}
              <th>{t('inc.colTime')}</th><th>{t('inc.colTeam')}</th><th>{t('inc.colChannel')}</th><th>{t('inc.colService')}</th>
              <th>{t('inc.colCategory')}</th><th>{t('inc.colSeverity')}</th><th>{t('inc.colStatus')}</th>
              <th>{t('inc.colSla')}</th><th>{t('inc.colTitle')}</th><th></th>
            </tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => setModal({ mode: 'view', form: { ...EMPTY, ...r } })}>
                  {allowManage && <td onClick={e => e.stopPropagation()}>
                    <input type="checkbox" checked={selected.has(r.id)} onChange={() => toggleSel(r.id)} />
                  </td>}
                  <td style={{ whiteSpace: 'nowrap' }}>{formatDate(r.occurred_at)}</td>
                  <td>{r.team_name || '—'}</td>
                  <td>{r.channel || '—'}</td>
                  <td>{r.service || '—'}</td>
                  <td>{t('inc.cat' + r.category) || r.category}</td>
                  <td>{sevBadge(r.severity)}</td>
                  <td>{t('inc.st' + r.status) || r.status}</td>
                  <td>{r.sla_breached ? <span style={{ color: '#dc2626', fontWeight: 700 }}>✓</span> : '—'}</td>
                  <td>{r.title}</td>
                  <td onClick={e => e.stopPropagation()}>
                    {allowManage && (
                      <button className="btn-sm btn-show" title={t('inc.edit')}
                              onClick={() => setModal({ mode: 'edit', form: { ...EMPTY, ...r } })}>
                        <Pencil size={13} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Sayfalama */}
      {!loading && total > 0 && (
        <div className="alh-pagination" style={{ marginTop: 10 }}>
          <div className="alh-page-size">
            <span>{t('inc.perPage')}</span>
            {[10, 20, 50].map(n => (
              <button key={n} className={`alh-size-btn${size === n ? ' is-active' : ''}`} onClick={() => setSize(n)}>{n}</button>
            ))}
          </div>
          <div className="alh-page-info">{t('inc.pageOf', page + 1, totalPages)} · {total} {t('inc.records')}</div>
          <div className="alh-page-nav">
            <button disabled={page === 0} onClick={() => setPage(p => p - 1)}><ChevronLeft size={13} /> {t('inc.prev')}</button>
            <button disabled={page + 1 >= totalPages} onClick={() => setPage(p => p + 1)}>{t('inc.next')} <ChevronRight size={13} /></button>
          </div>
        </div>
      )}

      {modal && <IncidentModal modal={modal} setModal={setModal} save={save} remove={remove}
                               saving={saving} allowManage={allowManage} allowDelete={allowDelete} t={t} teams={teams}
                               channelOpts={channelOpts} domainOpts={domainOpts}
                               onAddOption={addOption} onDeleteOption={deleteOption} />}
    </div>
  )
}

/** Detay (read-only) / düzenle / oluştur modalı — proje form deseni (modal-box + form-grid). */
function IncidentModal({ modal, setModal, save, remove, saving, allowManage, allowDelete, t, teams, channelOpts, domainOpts, onAddOption, onDeleteOption }) {
  const editing = modal.mode !== 'view'
  const f = modal.form
  const set = (k, v) => setModal(m => ({ ...m, form: { ...m.form, [k]: v } }))
  const titleKey = modal.mode === 'create' ? 'inc.newTitle' : modal.mode === 'edit' ? 'inc.editTitle' : 'inc.detailTitle'
  const opts = (arr, pfx) => arr.map(x => ({ value: x, label: t(pfx + x) }))

  // Takım seçenekleri — seçili takım yüklenen listede yoksa (kapsam dışı/eski kayıt) yine de göster
  const teamOptions = [{ value: '', label: '—' }, ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]
  if (f.team_id != null && f.team_id !== '' && !teams.some(tm => String(tm.id) === String(f.team_id)))
    teamOptions.push({ value: String(f.team_id), label: f.team_name || ('#' + f.team_id) })

  // Süre (dk) otomatik: tespit ↔ çözülme farkı (ikisi de geçerli ve resolved >= detected ise).
  // İkisi de aynı UTC string formatında olduğundan yerel parse'ta offset sadeleşir → fark doğru.
  useEffect(() => {
    if (!editing || !f.detected_at || !f.resolved_at) return
    const diff = Math.round((new Date(f.resolved_at).getTime() - new Date(f.detected_at).getTime()) / 60000)
    if (!Number.isFinite(diff) || diff < 0) return
    if (String(f.duration_minutes ?? '') !== String(diff)) {
      setModal(m => ({ ...m, form: { ...m.form, duration_minutes: diff } }))
    }
  }, [f.detected_at, f.resolved_at, editing]) // eslint-disable-line react-hooks/exhaustive-deps

  // Görsel isim tekilleştirme — aynı incident içinde (4 markdown alanı genelinde) aynı görsel
  // adı tekrar ederse "ad (2).uzantı" üretir. İçerik farklı iki "capture.jpg" ikisi de yüklenir,
  // ayrı URL alır, isimleri ayrışır → karışmaz. formRef en güncel alanları okur (async upload).
  const formRef = useRef(f); formRef.current = f
  const reservedRef = useRef(new Set())
  useEffect(() => { reservedRef.current = new Set() }, [f.id, modal.mode])
  const makeUniqueCaption = useCallback((desired) => {
    const base = (desired || 'image').trim() || 'image'
    const used = new Set(reservedRef.current)
    for (const k of ['rca_summary', 'description', 'resolution_steps', 'business_impact']) {
      const txt = formRef.current[k] || ''
      const re = /!\[([^\]]*)\]\([^)]*\)/g
      let m
      while ((m = re.exec(txt))) used.add(m[1])
    }
    if (!used.has(base)) { reservedRef.current.add(base); return base }
    const dot = base.lastIndexOf('.')
    const stem = dot > 0 ? base.slice(0, dot) : base
    const ext = dot > 0 ? base.slice(dot) : ''
    let n = 2, cand
    do { cand = `${stem} (${n})${ext}`; n++ } while (used.has(cand))
    reservedRef.current.add(cand)
    return cand
  }, [])

  return (
    <div className="modal-overlay" onClick={() => setModal(null)}>
      <div className="modal-box modal-wide" onClick={e => e.stopPropagation()}>
        <div className="modal-icon-hdr">
          <div className="modal-icon-hdr-badge" style={{ background: 'linear-gradient(135deg,#0f172a,#334155)', color: '#fff' }}>
            <ListChecks size={20} />
          </div>
          <h3>{t(titleKey)}</h3>
        </div>

        <div className="form-grid form-grid--top">
          <TextInput label={t('inc.fTitle')} req full value={f.title} disabled={!editing} onChange={v => set('title', v)} />
          <DateInput label={t('inc.fOccurredAt')} req value={f.occurred_at} disabled={!editing} onChange={v => set('occurred_at', v)} />
          <SelectInput label={t('inc.fTeam')} req value={f.team_id != null ? String(f.team_id) : ''} disabled={!editing}
            onChange={v => setModal(m => ({ ...m, form: { ...m.form, team_id: v,
              team_name: teams.find(tm => String(tm.id) === String(v))?.name || '' } }))}
            options={teamOptions} />
          <CreatableSelect label={t('inc.fChannel')} value={f.channel} disabled={!editing}
            options={channelOpts} onChange={v => set('channel', v)}
            onCreate={v => onAddOption('CHANNEL', v)} onDelete={v => onDeleteOption('CHANNEL', v)} />
          <CreatableSelect label={t('inc.fService')} value={f.service} disabled={!editing}
            options={domainOpts} onChange={v => set('service', v)}
            onCreate={v => onAddOption('DOMAIN', v)} onDelete={v => onDeleteOption('DOMAIN', v)} />
          <SelectInput label={t('inc.fSeverity')} value={f.severity} disabled={!editing} onChange={v => set('severity', v)} options={opts(SEVERITIES, 'inc.sev')} />
          <SelectInput label={t('inc.fStatus')} value={f.status} disabled={!editing} onChange={v => set('status', v)} options={opts(STATUSES, 'inc.st')} />
          <SelectInput label={t('inc.fCategory')} value={f.category} disabled={!editing} onChange={v => set('category', v)} options={opts(CATEGORIES, 'inc.cat')} />
          <DateInput label={t('inc.fDetectedAt')} value={f.detected_at} disabled={!editing} onChange={v => set('detected_at', v)} />
          <DateInput label={t('inc.fResolvedAt')} value={f.resolved_at} disabled={!editing} min={f.detected_at} onChange={v => set('resolved_at', v)} />
          <TextInput label={t('inc.fErrorBudget')} type="number" value={f.error_budget_burn_pct} disabled={!editing} onChange={v => set('error_budget_burn_pct', v)} />
          <TextInput label={t('inc.fDuration')} type="number" value={f.duration_minutes}
            disabled={!editing || (!!f.detected_at && !!f.resolved_at)}
            onChange={v => set('duration_minutes', v)} />
          <CheckInput label={t('inc.fSla')} checked={f.sla_breached} disabled={!editing} onChange={v => set('sla_breached', v)} />
          <TextInput label={t('inc.fAffected')} full value={f.affected_services} disabled={!editing} onChange={v => set('affected_services', v)} />
          <TagInput label={t('inc.fTags')} value={f.tags} disabled={!editing} t={t} onChange={v => set('tags', v)} />
          <MdArea label={t('inc.fRca')} value={f.rca_summary} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('rca_summary', v)} />
          <MdArea label={t('inc.fDescription')} value={f.description} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('description', v)} />
          <MdArea label={t('inc.fResolution')} value={f.resolution_steps} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('resolution_steps', v)} />
          <MdArea label={t('inc.fBusinessImpact')} value={f.business_impact} editable={editing} incidentId={f.id} makeUniqueCaption={makeUniqueCaption} onChange={v => set('business_impact', v)} />
        </div>

        <div className="modal-actions">
          {editing && <button className="btn btn-primary" onClick={save} disabled={saving}>{t('inc.save')}</button>}
          {modal.mode === 'view' && (
            <>
              {allowManage && <button className="btn btn-secondary" onClick={() => setModal(m => ({ ...m, mode: 'edit' }))}>{t('inc.edit')}</button>}
              {allowDelete && <button className="btn btn-danger" onClick={() => remove(f)}><Trash2 size={13} /> {t('inc.delete')}</button>}
            </>
          )}
          <button className="btn btn-secondary" onClick={() => setModal(null)}>{t('inc.cancel')}</button>
        </div>
      </div>
    </div>
  )
}
