import { useState, useRef, useCallback, useMemo } from 'react'
import { createPortal } from 'react-dom'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval'
import { usePagination } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import CopyLinkButton from './ui/CopyLinkButton.jsx'
import { domainDeepLink } from '../utils/monitorDeepLink.js'
import PaginationBar from './ui/PaginationBar.jsx'
import { RefreshCw, X, AlertCircle, CheckCircle, Users } from 'lucide-react'
import DateTimeRangePicker from './ui/DateTimeRangePicker.jsx'
import CheckHistoryTab from './history/CheckHistoryTab.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import SearchableSelect from './ui/SearchableSelect.jsx'
import { LoadingBlock } from './ui/Progress.jsx'

const REFRESH_INTERVAL = 60

function todayStartDate() { const d = new Date(); d.setHours(0, 0, 0, 0); return d }

export default function UptimePage({ systemRole }) {
  const t = useT()
  const isAdmin = systemRole === 'ADMIN'
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [filterStatus, setFilterStatus] = useState(() => readUrlParam('stat', 'all'))
  const [sortKey, setSortKey]           = useState(() => readUrlParam('sort', 'default'))
  const [search, setSearch]             = useState(() => readUrlParam('q', ''))
  const [teamFilter, setTeamFilter]     = useState(() => readUrlParam('team', 'all'))
  const [selected, setSelected]         = useState(null)
  const [diag, setDiag]                 = useState(null)   // { domain, port } → DiagnosticsModal
  const [dateFrom, setDateFrom]         = useState(todayStartDate)
  const [dateTo, setDateTo]             = useState(() => new Date())
  const [secondsSince, setSecondsSince] = useState(0)
  const lastFetched = useRef(null)

  const fetchOverview = useCallback(async () => {
    const res = await api.monitoring.getUptimeOverview()
    if (res?.success) {
      setItems(res.data)
      lastFetched.current = Date.now()
      setSecondsSince(0)
    }
    setLoading(false)
  }, [])

  useVisibleInterval(fetchOverview, REFRESH_INTERVAL * 1000)   // gizli sekmede polling durur
  useVisibleInterval(() => setSecondsSince(s => s + 1), 1000, false)   // countdown da durur

  function openModal(item) {
    setSelected(item)
    setDateFrom(todayStartDate())
    setDateTo(new Date())
  }

  function closeModal() { setSelected(null) }

  function applyDateRange(from, to) {
    setDateFrom(from)
    setDateTo(to)
  }

  const STATUS_ORDER = { down: 0, unknown: 1, up: 2 }
  const displayItems = useMemo(() => {
    let list = [...items]
    if (filterStatus === 'down') list = list.filter(x => x.status !== 'up')
    if (filterStatus === 'up')   list = list.filter(x => x.status === 'up')
    if (teamFilter !== 'all') {
      if (teamFilter === '__none__') list = list.filter(x => !x.team_name)
      else                            list = list.filter(x => x.team_name === teamFilter)
    }
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(x => x.domain.toLowerCase().includes(q))
    }
    list.sort((a, b) => {
      if (sortKey === 'default') {
        const sd = (STATUS_ORDER[a.status] ?? 1) - (STATUS_ORDER[b.status] ?? 1)
        if (sd !== 0) return sd
        return (a.ssl_valid_days ?? -9999) - (b.ssl_valid_days ?? -9999)
      }
      if (sortKey === 'ssl-asc')        return (a.ssl_valid_days ?? -9999) - (b.ssl_valid_days ?? -9999)
      if (sortKey === 'domain')         return a.domain.localeCompare(b.domain)
      if (sortKey === 'uptime-asc')     return (a.uptime_7d ?? 100) - (b.uptime_7d ?? 100)
      if (sortKey === 'incidents-desc') return (b.incidents_30d ?? 0) - (a.incidents_30d ?? 0)
      return 0
    })
    return list
  }, [items, filterStatus, sortKey, search, teamFilter])


  // Takım filtresi seçenekleri — listeden türetilir (dashboard deseni).
  const teamOptions = (() => {
    const names = new Set()
    let hasNone = false
    for (const x of items) { if (x.team_name) names.add(x.team_name); else hasNone = true }
    const opts = [{ value: 'all', label: t('app.allTeams') }]
    ;[...names].sort((a, b) => a.localeCompare(b)).forEach((n) => opts.push({ value: n, label: n }))
    if (hasNone) opts.push({ value: '__none__', label: t('app.noTeam') })
    return opts
  })()
  const hasTeamOptions = teamOptions.some(o => o.value !== 'all' && o.value !== '__none__')

  // Sayfalama standardı: usePagination + PaginationBar (pageNumbers artık bileşenin içinde).
  const pager = usePagination(displayItems, {
    listKey: 'uptime', resetDeps: [filterStatus, sortKey, search, teamFilter],
    initialPage: readUrlInt('page', 1), initialSize: readUrlInt('ps', null),
  })

  // Paylaşılabilir URL: filtre/sıralama/arama/sayfa adres çubuğunda yaşar (varsayılanlar param üretmez).
  useUrlQuerySync({
    stat: filterStatus !== 'all' ? filterStatus : null,
    sort: sortKey !== 'default' ? sortKey : null,
    q: search.trim() || null,
    team: teamFilter !== 'all' ? teamFilter : null,
    page: pager.page > 1 ? pager.page : null,
    ps: (pager.pageSize !== 50 || pager.page > 1) ? pager.pageSize : null,
  })

  function statusLabel(status) {
    if (status === 'up')   return t('uptime.statusUp')
    if (status === 'down') return t('uptime.statusDown')
    return t('uptime.statusUnknown')
  }

  function sslLabel(item) {
    if (item.ssl_valid_days == null) return t('uptime.sslUnknown')
    if (item.ssl_valid_days < 0)     return t('uptime.sslExpired')
    return t('uptime.sslDays').replace('{0}', item.ssl_valid_days)
  }

  /** SSL metrik değeri — sertifikaya erişilemediyse (ssl_valid_days null) "—" yerine hata ikonu. */
  function sslValueNode(item) {
    if (item.ssl_valid_days == null) {
      return (
        <span title={t('uptime.sslError')} style={{ display: 'inline-flex', alignItems: 'center' }}>
          <AlertCircle size={16} style={{ color: '#ef4444' }} />
        </span>
      )
    }
    return sslLabel(item)
  }

  /** HTTP-OK göstergesi — son 24h temizse yeşil, sorunluysa kırmızı; veri yoksa gizli. */
  function httpOkNode(httpOk) {
    return (
      <span title={httpOk ? t('uptime.httpOk') : t('uptime.httpDown')}
        style={{ display: 'inline-flex', alignItems: 'center' }}>
        {httpOk
          ? <CheckCircle size={16} style={{ color: '#22c55e' }} />
          : <AlertCircle size={16} style={{ color: '#ef4444' }} />}
      </span>
    )
  }

  function sslColor(item) {
    if (item.ssl_valid_days == null) return 'var(--text-muted)'
    if (item.ssl_valid_days < 0)     return '#ef4444'
    if (item.ssl_valid_days <= 7)    return '#ef4444'
    if (item.ssl_valid_days <= 14)   return '#f97316'
    if (item.ssl_valid_days <= 30)   return '#f59e0b'
    return '#22c55e'
  }

  function cardSslClass(item) {
    if (item.status !== 'up') return `upt-card--${item.status}`
    const d = item.ssl_valid_days
    if (d == null || d > 30) return 'upt-card--up'
    if (d < 0 || d <= 7)     return 'upt-card--ssl-critical'
    if (d <= 14)              return 'upt-card--ssl-high'
    return 'upt-card--ssl-warning'
  }

  return (
    <div className="upt-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title">{t('uptime.title')}</h2>
          <p className="upt-subtitle">{t('uptime.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <span className="upt-last-check">
            {t('uptime.autoRefresh').replace('{0}', Math.max(0, REFRESH_INTERVAL - secondsSince))}
          </span>
          <button className="btn btn-sm upt-refresh-btn" onClick={fetchOverview}>
            <RefreshCw size={14} />
            {t('uptime.refresh')}
          </button>
          <CopyLinkButton />
        </div>
      </div>

      {!loading && items.length > 0 && (
        <div className="upt-toolbar">
          <div className="upt-toolbar-left">
            <div className="upt-filter-pills">
              {['all', 'down', 'up'].map(f => (
                <button key={f}
                  className={`upt-filter-pill${filterStatus === f ? ' upt-filter-pill--active' : ''}`}
                  onClick={() => setFilterStatus(f)}>
                  {t(`uptime.filter${f.charAt(0).toUpperCase() + f.slice(1)}`)}
                </button>
              ))}
            </div>
            <select className="upt-sort-select" value={sortKey} onChange={e => setSortKey(e.target.value)}>
              <option value="default">{t('uptime.sortDefault')}</option>
              <option value="ssl-asc">{t('uptime.sortSslAsc')}</option>
              <option value="domain">{t('uptime.sortDomain')}</option>
              <option value="uptime-asc">{t('uptime.sortUptimeAsc')}</option>
              <option value="incidents-desc">{t('uptime.sortIncidentsDesc')}</option>
            </select>
            {hasTeamOptions && (
              <SearchableSelect value={teamFilter} onChange={setTeamFilter} options={teamOptions} />
            )}
          </div>
          <input className="upt-search" type="text"
            placeholder={t('uptime.searchPlaceholder')}
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
      )}

      {loading ? (
        <LoadingBlock label={t('uptime.checking')} fullWidth />
      ) : items.length === 0 ? (
        <LoadingBlock label={t('uptime.noData')} fullWidth />
      ) : (
        <div className="upt-grid">
          {pager.pageItems.map(item => (
            <div
              key={item.domain}
              className={`upt-card ${cardSslClass(item)}`}
              onClick={() => openModal(item)}
            >
              <div className="upt-card-top">
                <div className={`upt-badge upt-badge--${item.status}`}>
                  <span className="upt-badge-dot" />
                  {statusLabel(item.status)}
                </div>
                <span className="upt-card-top-right">
                  <span className="upt-port-tag">:{item.port}</span>
                  {/* Uptime kartının monitör id'si YOK — anahtarı alan adı, derin bağlantı da öyle. */}
                  <CopyLinkButton iconOnly url={domainDeepLink('uptime', item.domain)} className="btn btn-sm upt-card-copy" />
                </span>
              </div>

              <div className="upt-card-domain">{item.domain}</div>
              {item.team_name && (
                <div title={t('card.team')} style={{ display: 'flex', alignItems: 'center', gap: 5,
                  fontSize: '.78em', color: 'var(--text-muted)', marginTop: 2 }}>
                  <Users size={12} />{item.team_name}
                </div>
              )}

              <div className="upt-card-divider" />

              <div className="upt-card-metrics">
                <div className="upt-metric">
                  <span className="upt-metric-val" style={{ color: sslColor(item) }}>{sslValueNode(item)}</span>
                  <span className="upt-metric-lbl">SSL</span>
                </div>
                {item.http_ok != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{httpOkNode(item.http_ok)}</span>
                    <span className="upt-metric-lbl">HTTP</span>
                  </div>
                )}
                {item.uptime_7d != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{item.uptime_7d}%</span>
                    <span className="upt-metric-lbl">{t('uptime.uptime7d')}</span>
                  </div>
                )}
                {item.uptime_30d != null && (
                  <div className="upt-metric">
                    <span className="upt-metric-val">{item.uptime_30d}%</span>
                    <span className="upt-metric-lbl">{t('uptime.uptime30d')}</span>
                  </div>
                )}
                {[
                  { v: item.incidents_1d,  lbl: t('uptime.incidents1d')  },
                  { v: item.incidents_7d,  lbl: t('uptime.incidents7d')  },
                  { v: item.incidents_15d, lbl: t('uptime.incidents15d') },
                  { v: item.incidents_30d, lbl: t('uptime.incidents30d') },
                ].filter(m => m.v > 0).map((m, i) => (
                  <div key={i} className="upt-metric">
                    <span className="upt-metric-val upt-metric-incident">{m.v}</span>
                    <span className="upt-metric-lbl">{m.lbl}</span>
                  </div>
                ))}
              </div>

              {(item.uptime_checked_at || item.ssl_checked_at || isAdmin) && (
                <div className="upt-card-foot">
                  <span>{(item.uptime_checked_at || item.ssl_checked_at)
                    ? formatDate(item.uptime_checked_at || item.ssl_checked_at) : ''}</span>
                  {isAdmin && (
                    <button
                      className="btn-sm btn-show"
                      onClick={(e) => { e.stopPropagation(); setDiag({ domain: item.domain, port: item.port || 443 }) }}
                    >
                      {t('uptime.diagnose')}
                    </button>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* ── Pagination ── */}
      {!loading && <PaginationBar {...pager} />}

      {/* ── Detail Modal (portal → document.body, bypasses overflow stacking context) ── */}
      {selected && createPortal(
        <div className="upt-modal-overlay" onClick={closeModal}>
          <div
            className={`upt-modal upt-modal--${selected.status}`}
            onClick={e => e.stopPropagation()}
          >
            {/* Header */}
            <div className="upt-modal-header">
              <div className="upt-modal-header-left">
                <div className={`upt-badge upt-badge--${selected.status}`}>
                  <span className="upt-badge-dot" />
                  {statusLabel(selected.status)}
                </div>
                <span className="upt-modal-domain">{selected.domain}</span>
                <span className="upt-port-tag">:{selected.port}</span>
              </div>
              <button className="upt-modal-close" onClick={closeModal}>
                <X size={18} />
              </button>
            </div>

            <div className="upt-modal-divider" />

            {/* Summary metrics */}
            <div className="upt-modal-summary">
              <div className="upt-modal-metric">
                <span className="upt-modal-metric-val" style={{ color: sslColor(selected) }}>{sslValueNode(selected)}</span>
                <span className="upt-modal-metric-lbl">SSL</span>
              </div>
              {selected.http_ok != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{httpOkNode(selected.http_ok)}</span>
                  <span className="upt-modal-metric-lbl">HTTP</span>
                </div>
              )}
              {selected.uptime_7d != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.uptime_7d}%</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.uptime7d')}</span>
                </div>
              )}
              {selected.uptime_30d != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.uptime_30d}%</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.uptime30d')}</span>
                </div>
              )}
              {[
                { v: selected.incidents_1d,  lbl: t('uptime.incidents1d')  },
                { v: selected.incidents_7d,  lbl: t('uptime.incidents7d')  },
                { v: selected.incidents_15d, lbl: t('uptime.incidents15d') },
                { v: selected.incidents_30d, lbl: t('uptime.incidents30d') },
              ].filter(m => m.v > 0).map((m, i) => (
                <div key={i} className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-metric-incident">{m.v}</span>
                  <span className="upt-modal-metric-lbl">{m.lbl}</span>
                </div>
              ))}
              {selected.response_ms != null && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val">{selected.response_ms}ms</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.responseMs')}</span>
                </div>
              )}
              {selected.uptime_checked_at && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.uptime_checked_at)}</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.lastHttpCheck')}</span>
                </div>
              )}
              {selected.ssl_checked_at && (
                <div className="upt-modal-metric">
                  <span className="upt-modal-metric-val upt-modal-metric-time">{formatDate(selected.ssl_checked_at)}</span>
                  <span className="upt-modal-metric-lbl">{t('uptime.lastSslCheck')}</span>
                </div>
              )}
            </div>

            <div className="upt-modal-divider" />

            {/* Date range picker */}
            <DateTimeRangePicker
              from={dateFrom}
              to={dateTo}
              onApply={applyDateRange}
            />

            <div className="upt-modal-divider" />

            {/* Two-column history — paylaşılan CheckHistoryTab (tek picker iki kolonu sürer);
                sayfalama + hata filtresi + yoğunluk şeridi Uptime'a İLK KEZ geliyor. */}
            <div className="upt-history-cols">
              <div className="upt-history-col">
                <div className="upt-modal-section-title">{t('uptime.httpHistory')}</div>
                <CheckHistoryTab kind="uptime-http" monitorId={selected.domain} listKey="uptime-http-history"
                  extraParams={{ port: selected.port || 443 }}
                  range={{ from: dateFrom, to: dateTo }} onRangeChange={applyDateRange}
                  urlSync={false} gridClass="upt-uptime-rt-grid"
                  columns={[t('uptime.dateFrom'), t('dns.status'), 'ms', '']}
                  renderRow={(c) => (<>
                    <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                    <span className={c.status === 'up' ? 'upt-rt-up' : 'upt-rt-down'}>
                      {c.status === 'up' ? t('uptime.statusUp') : t('uptime.statusDown')}
                    </span>
                    <span className="upt-rt-ms">{c.response_ms != null ? `${c.response_ms}ms` : '—'}</span>
                    {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span> : <span />}
                  </>)} />
              </div>

              <div className="upt-history-col-divider" />

              <div className="upt-history-col">
                <div className="upt-modal-section-title">{t('uptime.sslHistory')}</div>
                <CheckHistoryTab kind="uptime-ssl" monitorId={selected.domain} listKey="uptime-ssl-history"
                  range={{ from: dateFrom, to: dateTo }} onRangeChange={applyDateRange}
                  urlSync={false} gridClass="upt-uptime-rt-grid"
                  columns={[t('uptime.dateFrom'), t('dns.status'), 'SSL', '']}
                  renderRow={(c) => (<>
                    <span className="upt-rt-time">{formatDate(c.checked_at)}</span>
                    <span className={c.status !== 'error' ? 'upt-rt-up' : 'upt-rt-down'}>
                      {c.status !== 'error' ? t('uptime.statusUp') : t('uptime.statusDown')}
                    </span>
                    <span className="upt-rt-ms">{c.days_remaining != null ? t('uptime.sslDays').replace('{0}', c.days_remaining) : '—'}</span>
                    {c.error ? <span className="upt-rt-error" title={c.error}>{c.error}</span> : <span />}
                  </>)} />
              </div>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ── Tanılama modalı (envanter ile ortak) — admin-only ── */}
      {diag && (
        <DiagnosticsModal domain={diag.domain} port={diag.port} onClose={() => setDiag(null)} />
      )}
    </div>
  )
}
