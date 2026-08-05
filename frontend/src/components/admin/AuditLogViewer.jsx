import { useState, useEffect, useCallback } from 'react'
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip } from 'recharts'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { useUrlQuerySync, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { readPageSize, writePageSize } from '../../hooks/usePagination.js'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import UserBadge from '../ui/UserBadge.jsx'

const EVENT_TYPES = [
  'LOGIN', 'LOGIN_FAILED', 'LOGOUT',
  'DOMAIN_ADD', 'DOMAIN_DELETE', 'DOMAIN_EDIT',
  'TEAM_CREATE', 'TEAM_UPDATE', 'TEAM_DELETE',
  'USER_CREATE', 'USER_UPDATE', 'USER_DELETE', 'USER_UNLOCK',
  'PERMISSION_UPDATE', 'PERMISSION_RESET',
  'MONITOR_CREATE', 'MONITOR_UPDATE', 'MONITOR_DELETE',
  'THRESHOLD_CREATE', 'THRESHOLD_UPDATE', 'CONTACT_CREATE', 'CONTACT_UPDATE', 'CONTACT_DELETE',
  'GUIDE_LINK_CREATE', 'GUIDE_LINK_UPDATE', 'GUIDE_LINK_DELETE',
  'ACCESS_DENIED', 'AUTH_REQUIRED', 'AUDIT_EXPORT',
  'SYSTEM_STARTUP', 'SYSTEM_SHUTDOWN',
]

const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']

// Hazır görünümler (preset views) — bir tıkla sık denetim senaryoları.
const PRESETS = [
  { key: 'security',  filter: { outcome: 'BLOCKED', eventType: '' } },
  { key: 'authz',     filter: { eventType: 'PERMISSION_UPDATE,PERMISSION_RESET,USER_UPDATE,TEAM_UPDATE', outcome: '' } },
  { key: 'failed',    filter: { eventType: 'LOGIN_FAILED', outcome: '' } },
  { key: 'config',    filter: { eventType: 'THRESHOLD_CREATE,THRESHOLD_UPDATE,CONTACT_CREATE,CONTACT_UPDATE,CONTACT_DELETE,GENERAL_SETTINGS_SAVE,SMTP_SETTINGS_SAVE,STORM_SETTINGS_SAVE', outcome: '' } },
]

const ANOMALY_COLORS = {
  OFF_HOURS:    '#f59e0b',
  UNUSUAL_IP:   '#8b5cf6',
  GEO_VELOCITY: '#ef4444',
  BRUTE_FORCE:  '#dc2626',
  RATE_LIMITED: '#64748b',
}

function isoMinus(seconds) {
  return new Date(Date.now() - seconds * 1000).toISOString().slice(0, 19)
}

// Olay türü → insan-okur fiil (eylem cümlesi için).
function verbFor(et, t) {
  if (!et) return t('audit.verb.did')
  if (et === 'LOGIN') return t('audit.verb.login')
  if (et === 'LOGIN_FAILED') return t('audit.verb.loginFailed')
  if (et === 'LOGOUT') return t('audit.verb.logout')
  if (et === 'ACCESS_DENIED' || et === 'AUTH_REQUIRED') return t('audit.verb.denied')
  if (et === 'MONITOR_TRIGGER' || et === 'SCHEDULER_RUN') return t('audit.verb.triggered')
  if (et === 'AUDIT_EXPORT') return t('audit.verb.exported')
  if (et === 'ACCOUNT_LOCKED') return t('audit.verb.locked')
  if (et === 'SESSION_TERMINATE') return t('audit.verb.terminated')
  if (et.endsWith('_DELETE')) return t('audit.verb.deleted')
  if (et.endsWith('_CREATE') || et.endsWith('_ADD')) return t('audit.verb.created')
  if (et.endsWith('_EDIT') || et.endsWith('_UPDATE')) return t('audit.verb.updated')
  return t('audit.verb.did')
}

// "alice, PORT_MONITOR:7 izlemesini güncelledi (warningDays: 30 → 15)" tarzı insan-okur cümle.
function actionSentence(row, t, diff) {
  const actor = row.actor || t('audit.systemActor')
  const verb = verbFor(row.event_type, t)
  const res = row.resource_type
    ? `${row.resource_type}${row.resource_id ? ':' + row.resource_id : ''}`
    : (row.resource_id || '')
  let tail = ''
  if (diff && diff.length) {
    tail = ' — ' + diff.slice(0, 2)
      .map(([f, c]) => `${f}: ${c.from ?? '∅'} → ${c.to ?? '∅'}`)
      .join(', ') + (diff.length > 2 ? ` (+${diff.length - 2})` : '')
  } else if (row.failure_reason) {
    tail = ' — ' + row.failure_reason
  }
  return `${actor} · ${verb}${res ? ' · ' + res : ''}${tail}`
}

// Basit dağılım çubuğu (recharts'sız, hafif) — özet paneli için.
function DistBar({ title, items }) {
  if (!items || !items.length) return null
  const max = Math.max(...items.map(i => i.count), 1)
  return (
    <div className="audit-dist">
      <div className="audit-dist-title">{title}</div>
      {items.slice(0, 6).map(i => (
        <div key={i.key} className="audit-dist-row">
          <span className="audit-dist-label" title={i.key}>{i.key || '—'}</span>
          <span className="audit-dist-bar"><span style={{ width: `${(i.count / max) * 100}%` }} /></span>
          <span className="audit-dist-count">{i.count}</span>
        </div>
      ))}
    </div>
  )
}

const CARD_DEFS = [
  { key: 'total_24h',        statKey: 'total_24h',         warn: false, filter: () => ({ since: isoMinus(86400),       anomalyOnly: false, eventType: '' }) },
  { key: 'anomalies_24h',    statKey: 'anomalies_24h',     warn: true,  filter: () => ({ since: isoMinus(86400),       anomalyOnly: true,  eventType: '' }) },
  { key: 'failed_logins_24h',statKey: 'failed_logins_24h', warn: true,  filter: () => ({ since: isoMinus(86400),       anomalyOnly: false, eventType: 'LOGIN_FAILED' }) },
  { key: 'total_7d',         statKey: 'total_7d',          warn: false, filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: false, eventType: '' }) },
  { key: 'anomalies_7d',     statKey: 'anomalies_7d',      warn: true,  filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: true,  eventType: '' }) },
  { key: 'failed_logins_7d', statKey: 'failed_logins_7d',  warn: true,  filter: () => ({ since: isoMinus(7*86400),     anomalyOnly: false, eventType: 'LOGIN_FAILED' }) },
]

function parseBrowser(ua) {
  if (!ua) return '—'
  let browser = 'Unknown'
  if (ua.includes('Edg/') || ua.includes('EdgA/'))       browser = 'Edge'
  else if (ua.includes('OPR/') || ua.includes('Opera/')) browser = 'Opera'
  else if (ua.includes('Chrome/'))                        browser = 'Chrome'
  else if (ua.includes('Firefox/'))                       browser = 'Firefox'
  else if (ua.includes('Safari/'))                        browser = 'Safari'

  let os = ''
  if (ua.includes('Windows NT'))                            os = 'Windows'
  else if (ua.includes('Android'))                          os = 'Android'
  else if (ua.includes('iPhone') || ua.includes('iPad'))    os = 'iOS'
  else if (ua.includes('Mac OS'))                           os = 'macOS'
  else if (ua.includes('Linux'))                            os = 'Linux'

  return os ? `${browser} · ${os}` : browser
}

function AnomalyChips({ flags }) {
  if (!flags) return null
  return (
    <span className="audit-anomalies">
      {flags.split(',').map(f => (
        <span key={f} className="audit-anomaly-chip"
          style={{ background: ANOMALY_COLORS[f] || '#64748b' }}>
          {f.replace(/_/g, ' ')}
        </span>
      ))}
    </span>
  )
}

function StatCard({ label, value, warn, active, onClick }) {
  const hasWarn = warn && value > 0
  return (
    <button
      className={`audit-stat-card${hasWarn ? ' warn' : ''}${active ? ' active' : ''}`}
      onClick={onClick}
      title={label}
    >
      <div className="audit-stat-value">{value ?? '—'}</div>
      <div className="audit-stat-label">{label}</div>
    </button>
  )
}

const EMPTY_FILTERS = { actor: '', eventType: '', outcome: '', since: '', until: '', anomalyOnly: false,
  resourceType: '', resourceId: '', ip: '', q: '' }

function parseDiff(changes) {
  if (!changes) return null
  try {
    const obj = JSON.parse(changes)
    const entries = Object.entries(obj)
    if (entries.length === 0) return null
    return entries
  } catch { return null }
}

// ── URL senkronizasyonu: filtreler `a_` önekli query paramlarında yaşar (paylaşılabilir/derin-link) ──
const URL_PREFIX = 'a_'
function readUrlFilters() {
  const p = new URLSearchParams(window.location.search)
  const f = { ...EMPTY_FILTERS }
  for (const k of Object.keys(EMPTY_FILTERS)) {
    const v = p.get(URL_PREFIX + k)
    if (v != null) f[k] = (k === 'anomalyOnly') ? v === '1' : v
  }
  return f
}
function writeUrlFilters(f) {
  const p = new URLSearchParams(window.location.search)
  for (const k of Object.keys(EMPTY_FILTERS)) {
    const v = f[k]
    const has = (k === 'anomalyOnly') ? !!v : (v != null && v !== '')
    if (has) p.set(URL_PREFIX + k, (k === 'anomalyOnly') ? '1' : String(v))
    else p.delete(URL_PREFIX + k)
  }
  const qs = p.toString()
  window.history.replaceState(null, '', qs ? `${window.location.pathname}?${qs}` : window.location.pathname)
}

// ── Kaydedilebilir görünümler (localStorage) ──
const SAVED_VIEWS_KEY = 'auditSavedViews'
function loadSavedViews() {
  try { const v = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY)); return Array.isArray(v) ? v : [] }
  catch { return [] }
}
function persistSavedViews(views) {
  try { localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(views)) } catch { /* kota/gizli mod — sessiz geç */ }
}

// ── Zaman-yoğunluğu grafiği (recharts): son 14 günün günlük olay hacmi ──
function TimeDensityChart({ data, title }) {
  if (!data || data.length < 2) return null
  const rows = data.map(d => ({ day: (d.key || '').slice(5), count: d.count }))   // MM-DD etiketi
  return (
    <div className="audit-density">
      <div className="audit-dist-title">{title}</div>
      <ResponsiveContainer width="100%" height={120}>
        <AreaChart data={rows} margin={{ top: 6, right: 8, bottom: 0, left: -18 }}>
          <defs>
            <linearGradient id="auditDensityFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.35} />
              <stop offset="100%" stopColor="var(--primary)" stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
          <XAxis dataKey="day" tick={{ fontSize: 10, fill: 'var(--text-light)' }} interval="preserveStartEnd" />
          <YAxis allowDecimals={false} width={34} tick={{ fontSize: 10, fill: 'var(--text-light)' }} />
          <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8 }} />
          <Area type="monotone" dataKey="count" stroke="var(--primary)" strokeWidth={2} fill="url(#auditDensityFill)" />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

export default function AuditLogViewer() {
  const t = useT()
  const [stats, setStats]         = useState(null)
  const [rows, setRows]           = useState([])
  const [total, setTotal]         = useState(0)
  const [page, setPage]           = useState(0)
  const [size, setSize]           = useState(() => readUrlInt('ps', null) || readPageSize('audit-log'))
  const [loading, setLoading]     = useState(false)
  const [activeCard, setActiveCard] = useState(null)
  const [filters, setFilters]     = useState(readUrlFilters)   // derin-link: URL'den başlat
  const [expandedId, setExpandedId] = useState(null)
  const [integrity, setIntegrity] = useState(null)
  const [activePreset, setActivePreset] = useState(null)
  const [detailExtra, setDetailExtra] = useState({})   // id → {correlated, recent}
  const [savedViews, setSavedViews] = useState(loadSavedViews)
  const [viewName, setViewName]   = useState('')
  const [timeline, setTimeline]   = useState(null)     // {type, id, rows, loading} | null
  const [autoRefresh, setAutoRefresh] = useState(false)

  function openDetail(row) {
    if (expandedId === row.id) { setExpandedId(null); return }
    setExpandedId(row.id)
    if (detailExtra[row.id]) return
    const load = {}
    const p = []
    if (row.correlation_id) p.push(api.admin.getAuditCorrelated(row.correlation_id)
      .then(r => { if (r?.success) load.correlated = r.data }).catch(() => {}))
    if (row.actor_id) p.push(api.admin.getAuditActorHistory(row.actor_id, 6)
      .then(r => { if (r?.success) load.recent = r.data }).catch(() => {}))
    Promise.all(p).finally(() => setDetailExtra(d => ({ ...d, [row.id]: load })))
  }

  function applyPreset(p) {
    setActiveCard(null)
    if (activePreset === p.key) { setActivePreset(null); clearFilters(); return }
    setActivePreset(p.key)
    const next = { ...EMPTY_FILTERS, ...p.filter }
    setFilters(next)
    writeUrlFilters(next)
    loadStats()
    loadLogs(0, next)
  }

  // ── Kaydedilebilir görünümler ──
  function saveCurrentView() {
    const name = viewName.trim()
    if (!name) return
    const next = [...savedViews.filter(v => v.name !== name), { name, filters }]
    setSavedViews(next); persistSavedViews(next); setViewName('')
  }
  function applySavedView(v) {
    setActiveCard(null); setActivePreset(null)
    const next = { ...EMPTY_FILTERS, ...v.filters }
    setFilters(next); writeUrlFilters(next); loadStats(); loadLogs(0, next)
  }
  function deleteSavedView(name, e) {
    e.stopPropagation()
    const next = savedViews.filter(v => v.name !== name)
    setSavedViews(next); persistSavedViews(next)
  }

  // ── Kaynak geçmişi dikey zaman-çizelgesi (drawer) ──
  function openTimeline(type, id) {
    if (!id) return
    setTimeline({ type, id, rows: [], loading: true })
    api.admin.getAuditResourceHistory(type || '', id, 100)
      .then(r => setTimeline(tl => tl && tl.id === id ? { ...tl, rows: r?.success ? r.data : [], loading: false } : tl))
      .catch(() => setTimeline(tl => tl && tl.id === id ? { ...tl, rows: [], loading: false } : tl))
  }

  function checkIntegrity() {
    setIntegrity({ loading: true })
    api.admin.getAuditIntegrity()
      .then(r => setIntegrity(r?.success ? r.data : { error: true }))
      .catch(() => setIntegrity({ error: true }))
  }

  function exportAudit(format) {
    window.open(api.admin.auditExportUrl(format, filters), '_blank')
  }

  const loadStats = useCallback(() => {
    api.admin.getAuditStats().then(r => { if (r?.success) setStats(r.data) })
  }, [])

  const loadLogs = useCallback((p = 0, f = filters, sz = size) => {
    setLoading(true)
    api.admin.getAuditLogs({ page: p, size: sz, ...f }).then(r => {
      if (r?.success) { setRows(r.data); setTotal(r.total); setPage(r.page) }
    }).finally(() => setLoading(false))
  }, [filters, size])

  useEffect(() => { loadStats(); loadLogs(readUrlInt('page', 1) - 1) }, [])

  // Canlı tazeleme: açıkken 15sn'de bir mevcut sayfayı + özeti yeniler (sekme gizliyken duraklar).
  useVisibleInterval(() => { loadLogs(page, filters); loadStats() }, autoRefresh ? 15000 : 0, false)

  function handleCardClick(card) {
    if (activeCard === card.key) {
      // second click → deselect, reset to empty
      setActiveCard(null)
      const next = EMPTY_FILTERS
      setFilters(next)
      writeUrlFilters(next)
      loadLogs(0, next)
    } else {
      setActiveCard(card.key)
      const extra = card.filter()
      const next = { ...EMPTY_FILTERS, ...extra }
      setFilters(next)
      writeUrlFilters(next)
      loadLogs(0, next)
    }
  }

  function applyFilters() {
    setActiveCard(null)
    setActivePreset(null)
    writeUrlFilters(filters)
    loadStats()
    loadLogs(0, filters)
  }

  function clearFilters() {
    setActiveCard(null)
    setActivePreset(null)
    setFilters(EMPTY_FILTERS)
    writeUrlFilters(EMPTY_FILTERS)
    loadLogs(0, EMPTY_FILTERS)
    loadStats()
  }

  /** Kaynak/aktör hücresine tıklama → o kaynağın/aktörün geçmişine filtrele (drill-down). */
  function drillResource(type, id) {
    const next = { ...EMPTY_FILTERS, resourceType: type || '', resourceId: id || '' }
    setActiveCard(null); setActivePreset(null); setFilters(next); writeUrlFilters(next); loadLogs(0, next)
  }
  function drillActor(actor) {
    const next = { ...EMPTY_FILTERS, actor }
    setActiveCard(null); setActivePreset(null); setFilters(next); writeUrlFilters(next); loadLogs(0, next)
  }

  const totalPages = Math.ceil(total / size)

  // Paylaşılabilir URL: sayfa/boyut (URL'de HEP 1-tabanlı). İlk yükleme page paramını dikkate alır (aşağıdaki effect).
  useUrlQuerySync({
    page: page > 0 ? page + 1 : null,
    ps: (size !== 50 || page > 0) ? size : null,
  })

  return (
    <div className="audit-viewer">
      {/* Stats cards */}
      {stats && (
        <div className="audit-stats-row">
          {CARD_DEFS.map(card => (
            <StatCard
              key={card.key}
              label={t(`audit.${card.key}`)}
              value={stats[card.statKey]}
              warn={card.warn}
              active={activeCard === card.key}
              onClick={() => handleCardClick(card)}
            />
          ))}
        </div>
      )}

      {/* Zaman-yoğunluğu grafiği (son 14 gün) */}
      {stats?.by_day_14d?.length >= 2 && (
        <TimeDensityChart data={stats.by_day_14d} title={t('audit.densityTitle')} />
      )}

      {/* Dağılım paneli (özet: en sık olay türleri / sonuç dağılımı / en aktif kullanıcılar) */}
      {stats && (stats.by_event_type_7d?.length > 0 || stats.top_actors_7d?.length > 0) && (
        <div className="audit-dist-panel">
          <DistBar title={t('audit.distEvents')} items={stats.by_event_type_7d} />
          <DistBar title={t('audit.distOutcomes')} items={stats.by_outcome_7d} />
          <DistBar title={t('audit.distActors')} items={stats.top_actors_7d} />
        </div>
      )}

      {/* Preset görünümler + bütünlük + dışa aktarma */}
      <div className="audit-toolbar">
        <div className="audit-presets">
          {PRESETS.map(p => (
            <button key={p.key}
              className={`audit-preset-btn${activePreset === p.key ? ' active' : ''}`}
              onClick={() => applyPreset(p)}>
              {t('audit.preset.' + p.key)}
            </button>
          ))}
        </div>
        <div className="audit-toolbar-actions">
          {integrity && !integrity.loading && (
            <span className={`audit-integrity-badge ${integrity.ok ? 'ok' : 'bad'}`}
              title={integrity.error ? '' : `${t('audit.integrityChecked')}: ${integrity.checked}`}>
              {integrity.error ? t('audit.integrityErr')
                : integrity.ok ? `✓ ${t('audit.integrityOk')} (${integrity.checked})`
                : `⚠ ${t('audit.integrityBroken')} #${integrity.broken_seq}`}
            </span>
          )}
          <button
            className={`audit-filter-btn audit-live-btn${autoRefresh ? ' active' : ''}`}
            onClick={() => setAutoRefresh(a => !a)}
            title={t('audit.autoRefreshHint')}>
            <span className={`audit-live-dot${autoRefresh ? ' on' : ''}`} />{t('audit.autoRefresh')}
          </button>
          <button className="audit-filter-btn" onClick={checkIntegrity}>{t('audit.verifyIntegrity')}</button>
          <button className="audit-filter-btn" onClick={() => exportAudit('csv')}>CSV</button>
          <button className="audit-filter-btn" onClick={() => exportAudit('json')}>JSON</button>
        </div>
      </div>

      {/* Kaydedilebilir görünümler */}
      <div className="audit-saved-bar">
        <div className="audit-saved-views">
          {savedViews.map(v => (
            <button key={v.name} className="audit-view-chip" onClick={() => applySavedView(v)} title={v.name}>
              <span className="audit-view-name">{v.name}</span>
              <span className="audit-view-del" onClick={e => deleteSavedView(v.name, e)} title={t('audit.deleteView')}>×</span>
            </button>
          ))}
        </div>
        <div className="audit-save-view">
          <input
            className="audit-filter-input"
            placeholder={t('audit.viewNamePh')}
            value={viewName}
            onChange={e => setViewName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') saveCurrentView() }}
          />
          <button className="audit-filter-btn" disabled={!viewName.trim()} onClick={saveCurrentView}>
            {t('audit.saveView')}
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="audit-filters">
        <input
          className="audit-filter-input"
          placeholder={t('audit.searchPh')}
          value={filters.q}
          onChange={e => setFilters(f => ({ ...f, q: e.target.value }))}
          onKeyDown={e => { if (e.key === 'Enter') applyFilters() }}
        />
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterActor')}
          value={filters.actor}
          onChange={e => setFilters(f => ({ ...f, actor: e.target.value }))}
        />
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterResourceType')}
          value={filters.resourceType}
          onChange={e => setFilters(f => ({ ...f, resourceType: e.target.value }))}
        />
        <input
          className="audit-filter-input"
          placeholder={t('audit.filterIp')}
          value={filters.ip}
          onChange={e => setFilters(f => ({ ...f, ip: e.target.value }))}
        />
        <SearchableSelect
          value={filters.eventType}
          onChange={v => setFilters(f => ({ ...f, eventType: v }))}
          placeholder={t('audit.allEvents')}
          options={[
            { value: '', label: t('audit.allEvents') },
            ...EVENT_TYPES.map(et => ({ value: et, label: et })),
          ]}
        />
        <SearchableSelect
          value={filters.outcome}
          onChange={v => setFilters(f => ({ ...f, outcome: v }))}
          placeholder={t('audit.allOutcomes')}
          options={[
            { value: '', label: t('audit.allOutcomes') },
            ...OUTCOMES.map(o => ({ value: o, label: o })),
          ]}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.since ? filters.since.replace(' ', 'T') : ''}
          onChange={e => setFilters(f => ({ ...f, since: e.target.value ? e.target.value.slice(0, 19) : '' }))}
        />
        <input
          type="datetime-local"
          className="audit-filter-input"
          value={filters.until ? filters.until.replace(' ', 'T') : ''}
          onChange={e => setFilters(f => ({ ...f, until: e.target.value ? e.target.value.slice(0, 19) : '' }))}
        />
        <label className="audit-filter-check">
          <input
            type="checkbox"
            checked={!!filters.anomalyOnly}
            onChange={e => setFilters(f => ({ ...f, anomalyOnly: e.target.checked }))}
          />
          {t('audit.anomalyOnly')}
        </label>
        <button className="audit-filter-btn primary" onClick={applyFilters}>{t('audit.apply')}</button>
        <button className="audit-filter-btn" onClick={clearFilters}>{t('audit.clear')}</button>
      </div>

      {/* Table */}
      <div className="audit-table-wrap">
        {loading && <div className="audit-loading">{t('app.loading')}</div>}
        <table className="audit-table">
          <thead>
            <tr>
              <th>{t('audit.colTime')}</th>
              <th>{t('audit.colEvent')}</th>
              <th>{t('audit.colActor')}</th>
              <th>{t('audit.colIp')}</th>
              <th>{t('audit.colBrowser')}</th>
              <th>{t('audit.colGeo')}</th>
              <th>{t('audit.colResource')}</th>
              <th>{t('audit.colOutcome')}</th>
              <th>{t('audit.colAnomalies')}</th>
              <th>{t('audit.colDetail')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && (
              <tr><td colSpan={10} className="audit-empty">{t('audit.empty')}</td></tr>
            )}
            {rows.map(row => {
              const diff = parseDiff(row.changes || row.detail)
              const isExpanded = expandedId === row.id
              return [
                <tr key={row.id} className={row.anomaly_flags ? 'audit-row-anomaly' : ''}>
                  <td className="audit-cell-time">{formatDate(row.event_time)}</td>
                  <td>
                    <span className={`audit-event-badge ${eventClass(row.event_type)}`}>
                      {row.event_type}
                    </span>
                  </td>
                  <td>
                    <div>{row.actor ? <UserBadge username={row.actor} inline size="sm" /> : '—'}</div>
                    {row.actor_role && <div className="audit-sub">{row.actor_role}</div>}
                  </td>
                  <td className="audit-mono">{row.ip_address || '—'}</td>
                  <td>
                    {row.user_agent
                      ? <span title={row.user_agent}>{parseBrowser(row.user_agent)}</span>
                      : '—'}
                  </td>
                  <td>
                    {row.ip_country && (
                      <div>{row.ip_country}{row.ip_city ? `, ${row.ip_city}` : ''}</div>
                    )}
                    {row.ip_org && <div className="audit-sub">{row.ip_org}</div>}
                  </td>
                  <td>
                    {row.resource_id ? (
                      <span className="audit-resource-cell">
                        <button className="audit-link" title={t('audit.resourceHistory')}
                          onClick={() => drillResource(row.resource_type, row.resource_id)}>
                          {row.resource_type && <span className="audit-sub">{row.resource_type}: </span>}{row.resource_id}
                        </button>
                        <button className="audit-timeline-btn" title={t('audit.resourceTimeline')}
                          onClick={() => openTimeline(row.resource_type, row.resource_id)}>🕘</button>
                      </span>
                    ) : (row.resource_type ? <span className="audit-sub">{row.resource_type}</span> : '—')}
                  </td>
                  <td>
                    <span className={`audit-outcome-badge ${row.outcome?.toLowerCase()}`}>
                      {row.outcome || '—'}
                    </span>
                    {row.failure_reason && <div className="audit-sub">{row.failure_reason}</div>}
                  </td>
                  <td><AnomalyChips flags={row.anomaly_flags} /></td>
                  <td>
                    <button
                      className={`audit-detail-toggle${isExpanded ? ' active' : ''}`}
                      onClick={() => openDetail(row)}
                      title={t('audit.showDetail')}
                    >
                      {diff ? `${diff.length} ${t('audit.changesCount')}` : t('audit.detail')}
                      <span className="audit-detail-arrow">{isExpanded ? '▲' : '▼'}</span>
                    </button>
                  </td>
                </tr>,
                isExpanded && (
                  <tr key={`${row.id}-detail`} className="audit-detail-tr">
                    <td colSpan={10}>
                      <div className="audit-detail-panel">
                        <div className="audit-sentence">{actionSentence(row, t, diff)}</div>

                        {diff && (
                          <table className="audit-diff-table">
                            <thead>
                              <tr>
                                <th>{t('audit.diffField')}</th>
                                <th>{t('audit.diffFrom')}</th>
                                <th>{t('audit.diffTo')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {diff.map(([field, change]) => (
                                <tr key={field}>
                                  <td className="audit-diff-field">{field}</td>
                                  <td className="audit-diff-from">{String(change.from ?? '—')}</td>
                                  <td className="audit-diff-to">{String(change.to ?? '—')}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}

                        {detailExtra[row.id]?.correlated?.length > 1 && (
                          <div className="audit-related">
                            <div className="audit-related-title">{t('audit.relatedEvents')}</div>
                            {detailExtra[row.id].correlated.map(e => (
                              <div key={e.id} className="audit-related-row">
                                <span className={`audit-event-badge ${eventClass(e.event_type)}`}>{e.event_type}</span>
                                <span className="audit-related-sub">{[e.resource_type, e.resource_id].filter(Boolean).join(':') || '—'}</span>
                                <span className="audit-related-time">{formatDate(e.event_time)}</span>
                              </div>
                            ))}
                          </div>
                        )}

                        {detailExtra[row.id]?.recent?.length > 0 && (
                          <div className="audit-related">
                            <div className="audit-related-title">{t('audit.actorRecent')}</div>
                            {detailExtra[row.id].recent.map(e => (
                              <div key={e.id} className="audit-related-row">
                                <span className={`audit-event-badge ${eventClass(e.event_type)}`}>{e.event_type}</span>
                                <span className="audit-related-sub">{[e.resource_type, e.resource_id].filter(Boolean).join(':') || '—'}</span>
                                <span className="audit-related-time">{formatDate(e.event_time)}</span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              ]
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <PaginationBar
        page={page + 1} totalPages={totalPages || 1} totalItems={total}
        rangeStart={total === 0 ? 0 : page * size + 1}
        rangeEnd={Math.min((page + 1) * size, total)}
        pageSize={size}
        onPageChange={p => loadLogs(p - 1)}
        onPageSizeChange={n => { setSize(n); writePageSize('audit-log', n); loadLogs(0, filters, n) }}
      />

      {/* Kaynak geçmişi — dikey zaman-çizelgesi drawer */}
      {timeline && (
        <div className="audit-timeline-overlay" onClick={() => setTimeline(null)}>
          <div className="audit-timeline-drawer" onClick={e => e.stopPropagation()}>
            <div className="audit-timeline-head">
              <div className="audit-timeline-title">
                {t('audit.timelineTitle', `${timeline.type ? timeline.type + ':' : ''}${timeline.id}`)}
              </div>
              <button className="audit-filter-btn" onClick={() => setTimeline(null)}>{t('audit.close')}</button>
            </div>
            {timeline.loading ? (
              <div className="audit-loading">{t('app.loading')}</div>
            ) : timeline.rows.length === 0 ? (
              <div className="audit-empty">{t('audit.timelineEmpty')}</div>
            ) : (
              <ol className="audit-timeline-list">
                {timeline.rows.map(e => {
                  const d = parseDiff(e.changes || e.detail)
                  return (
                    <li key={e.id} className="audit-timeline-item">
                      <span className={`audit-timeline-dot ${eventClass(e.event_type)}`} />
                      <div className="audit-timeline-body">
                        <div className="audit-timeline-row1">
                          <span className={`audit-event-badge ${eventClass(e.event_type)}`}>{e.event_type}</span>
                          <span className="audit-timeline-actor">{e.actor || t('audit.systemActor')}</span>
                          <span className="audit-timeline-time">{formatDate(e.event_time)}</span>
                        </div>
                        <div className="audit-timeline-sentence">{actionSentence(e, t, d)}</div>
                      </div>
                    </li>
                  )
                })}
              </ol>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function eventClass(et) {
  if (!et) return ''
  if (et === 'LOGIN')          return 'ev-login'
  if (et === 'LOGIN_FAILED')   return 'ev-failed'
  if (et === 'LOGOUT')         return 'ev-logout'
  if (et.endsWith('_DELETE'))  return 'ev-delete'
  if (et.endsWith('_CREATE') || et.endsWith('_ADD')) return 'ev-create'
  if (et.endsWith('_EDIT') || et.endsWith('_UPDATE')) return 'ev-edit'
  return 'ev-other'
}
