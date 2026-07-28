import { useEffect, useState, useCallback, useMemo } from 'react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import {
  Shield, Activity, Globe, Server, Radio, Share2, Search, CalendarClock,
  CheckCircle, AlertTriangle, XCircle, HelpCircle, ChevronDown, ChevronRight,
  Download, X, RefreshCw, Clock, User,
} from 'lucide-react'

// Her izleme türünün ayırt edici ikon + rengi (badge).
const TYPES = [
  { key: 'CERT',    Icon: Shield,        color: '#7c3aed' },
  { key: 'DOMAIN',  Icon: CalendarClock, color: '#be123c' },
  { key: 'HTTP',    Icon: Globe,         color: '#2563eb' },
  { key: 'UPTIME',  Icon: Activity,      color: '#059669' },
  { key: 'PORT',    Icon: Server,        color: '#0d9488' },
  { key: 'PING',    Icon: Radio,         color: '#0891b2' },
  { key: 'DNS',     Icon: Share2,        color: '#6d28d9' },
  { key: 'KEYWORD', Icon: Search,        color: '#d97706' },
]
const TYPE_MAP = Object.fromEntries(TYPES.map((t) => [t.key, t]))

const STATUS_META = {
  SUCCESS: { Icon: CheckCircle,   color: '#059669' },
  WARNING: { Icon: AlertTriangle, color: '#d97706' },
  ERROR:   { Icon: XCircle,       color: '#dc2626' },
  TIMEOUT: { Icon: XCircle,       color: '#dc2626' },
  UNKNOWN: { Icon: HelpCircle,    color: '#64748b' },
}
const STATUSES = ['SUCCESS', 'WARNING', 'ERROR', 'UNKNOWN']
const RANGES = ['today', '24h', '7d', 'all']
const PAGE_SIZE = 50

/** range → {from,to} ISO (UTC, yyyy-MM-ddTHH:mm:ss) — backend string karşılaştırmasıyla uyumlu. */
function rangeToFromTo(range) {
  const iso = (d) => d.toISOString().slice(0, 19)
  const now = Date.now()
  if (range === 'today') { const d = new Date(); d.setUTCHours(0, 0, 0, 0); return { from: iso(d), to: null } }
  if (range === '24h') return { from: iso(new Date(now - 24 * 3600 * 1000)), to: null }
  if (range === '7d')  return { from: iso(new Date(now - 7 * 24 * 3600 * 1000)), to: null }
  return { from: null, to: null }
}

// Filtreler URL query'sine yansır (paylaşılabilir/yenilenebilir); app'in diğer paramları (tab vb.) korunur.
function readParams() {
  const p = new URLSearchParams(window.location.search)
  return {
    types: (p.get('atype') || '').split(',').filter(Boolean),
    status: p.get('astatus') || '',
    range: RANGES.includes(p.get('arange')) ? p.get('arange') : '24h',
    q: p.get('aq') || '',
  }
}
function writeParams(f) {
  const p = new URLSearchParams(window.location.search)
  f.types.length ? p.set('atype', f.types.join(',')) : p.delete('atype')
  f.status ? p.set('astatus', f.status) : p.delete('astatus')
  f.range && f.range !== '24h' ? p.set('arange', f.range) : p.delete('arange')
  f.q ? p.set('aq', f.q) : p.delete('aq')
  const s = p.toString()
  window.history.replaceState(null, '', `${window.location.pathname}${s ? '?' + s : ''}`)
}

function csvEscape(v) {
  const s = v == null ? '' : String(v)
  return /[",\n;]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}
function exportCsv(rows) {
  const header = ['time', 'type', 'name', 'target', 'action', 'status', 'summary', 'error']
  const lines = [header.join(',')]
  rows.forEach((r) => lines.push([
    r.activity_time, r.monitor_type, r.monitor_name, r.target, r.action,
    r.result_status, r.result_summary, r.error_message,
  ].map(csvEscape).join(',')))
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'activity-log.csv'
  a.click()
  URL.revokeObjectURL(url)
}

export default function ActivityLog({ refreshTrigger }) {
  const t = useT()
  const [filters, setFilters] = useState(readParams)
  const [qInput, setQInput]   = useState(filters.q)
  const [data, setData]       = useState([])
  const [total, setTotal]     = useState(0)
  const [page, setPage]       = useState(0)
  const [summary, setSummary] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(false)
  const [openId, setOpenId]   = useState(null)
  const [details, setDetails] = useState({})   // id → {data, recent}

  const rel = useCallback((iso) => {
    if (!iso) return '—'
    const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : iso + 'Z'
    const then = new Date(s).getTime()
    if (isNaN(then)) return iso
    const sec = Math.floor(Math.max(0, Date.now() - then) / 1000)
    if (sec < 60) return t('act.rel.now')
    const min = Math.floor(sec / 60); if (min < 60) return t('act.rel.min', min)
    const hr = Math.floor(min / 60);  if (hr < 24)  return t('act.rel.hour', hr)
    return t('act.rel.day', Math.floor(hr / 24))
  }, [t])

  const params = useMemo(() => {
    const { from, to } = rangeToFromTo(filters.range)
    return { type: filters.types.join(','), status: filters.status, from, to, q: filters.q }
  }, [filters])

  const load = useCallback((p, silent) => {
    if (!silent) setLoading(true)
    setError(false)
    api.getActivity({ ...params, page: p, size: PAGE_SIZE })
      .then((res) => {
        if (res?.success) { setData(res.data); setTotal(res.total); setPage(res.page ?? p) }
        else setError(true)
      })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
    api.getActivitySummary(params).then((res) => { if (res?.success) setSummary(res) }).catch(() => {})
  }, [params])

  // Filtre değişince 1. sayfaya dön + URL'yi güncelle.
  useEffect(() => { writeParams(filters); setOpenId(null); load(0, false) }, [filters, load])
  useEffect(() => { if (refreshTrigger) load(page, true) }, [refreshTrigger]) // eslint-disable-line react-hooks/exhaustive-deps

  // Görünür sekmede, 1. sayfadayken sessiz otomatik tazeleme (gizli sekmede durur).
  useVisibleInterval(() => { if (page === 0) load(0, true) }, 30000, false)

  const toggleType = (key) =>
    setFilters((f) => ({ ...f, types: f.types.includes(key) ? f.types.filter((x) => x !== key) : [...f.types, key] }))
  const applySearch = () => setFilters((f) => ({ ...f, q: qInput.trim() }))
  const clearAll = () => { setQInput(''); setFilters({ types: [], status: '', range: '24h', q: '' }) }

  const openDetail = (row) => {
    if (openId === row.id) { setOpenId(null); return }
    setOpenId(row.id)
    if (!details[row.id]) {
      api.getActivityDetail(row.id).then((res) => {
        if (res?.success) setDetails((d) => ({ ...d, [row.id]: { data: res.data, recent: res.recent || [] } }))
      }).catch(() => {})
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const anyFilter = filters.types.length > 0 || filters.status || filters.q || filters.range !== '24h'

  return (
    <div className="activity-log">
      {/* Özet şerit */}
      <div className="act-summary-bar">
        <div className="act-sum-item"><strong>{summary?.total ?? '—'}</strong><span>{t('act.sum.total')}</span></div>
        <div className="act-sum-item act-ok"><CheckCircle size={13} /><strong>{summary?.success_count ?? 0}</strong><span>{t('act.st.SUCCESS')}</span></div>
        <div className="act-sum-item act-warn"><AlertTriangle size={13} /><strong>{summary?.warning ?? 0}</strong><span>{t('act.st.WARNING')}</span></div>
        <div className="act-sum-item act-err"><XCircle size={13} /><strong>{summary?.error ?? 0}</strong><span>{t('act.st.ERROR')}</span></div>
        <div className="act-sum-spacer" />
        {summary?.last_activity && (
          <div className="act-sum-last"><Clock size={12} /> {t('act.lastActivity')}: {formatDateSec(summary.last_activity)}</div>
        )}
      </div>

      {/* Filtre çubuğu */}
      <div className="act-filters">
        <div className="act-type-chips">
          {TYPES.map(({ key, Icon, color }) => {
            const on = filters.types.includes(key)
            return (
              <button key={key} type="button" onClick={() => toggleType(key)}
                className={`act-type-chip${on ? ' on' : ''}`}
                style={on ? { borderColor: color, color, background: color + '18' } : undefined}
                title={key}>
                <Icon size={13} /> {key}
              </button>
            )
          })}
        </div>
        <div className="act-filter-row">
          <div className="act-range-group">
            {RANGES.map((rg) => (
              <button key={rg} type="button" className={`act-filter-btn${filters.range === rg ? ' active' : ''}`}
                onClick={() => setFilters((f) => ({ ...f, range: rg }))}>{t('act.range.' + rg)}</button>
            ))}
          </div>
          <select className="filter-input act-status-select" value={filters.status}
            onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value }))}>
            <option value="">{t('act.allStatuses')}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{t('act.st.' + s)}</option>)}
          </select>
          <div className="act-search">
            <input className="filter-input" placeholder={t('act.searchPh')} value={qInput}
              onChange={(e) => setQInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') applySearch() }} />
            <button className="btn btn-secondary" onClick={applySearch}><Search size={14} /></button>
          </div>
          <div className="act-filter-actions">
            {anyFilter && <button className="act-stat-clear" onClick={clearAll}><X size={12} /> {t('act.clearFilter')}</button>}
            <button className="btn btn-secondary" onClick={() => load(page, false)} title={t('act.refresh')}><RefreshCw size={14} /></button>
            <button className="btn btn-secondary" onClick={() => exportCsv(data)} disabled={!data.length} title={t('act.exportCsv')}>
              <Download size={14} /> CSV
            </button>
          </div>
        </div>
      </div>

      {/* Ana liste */}
      {loading ? (
        <div className="loading">{t('act.loading')}</div>
      ) : error ? (
        <div className="loading act-error-state">{t('act.error')}</div>
      ) : data.length === 0 ? (
        <div className="loading">{anyFilter ? t('act.noMatch') : t('act.empty')}</div>
      ) : (
        <>
          <div className="act-feed">
            {data.map((row) => {
              const tm = TYPE_MAP[row.monitor_type] || { Icon: HelpCircle, color: '#64748b' }
              const sm = STATUS_META[row.result_status] || STATUS_META.UNKNOWN
              const isOpen = openId === row.id
              const d = details[row.id]
              const isError = row.result_status === 'ERROR' || row.result_status === 'TIMEOUT'
              return (
                <div key={row.id} className={`act-item${isOpen ? ' open' : ''}${isError ? ' act-item-error' : ''}`}>
                  <button className="act-item-row" onClick={() => openDetail(row)}>
                    <span className="act-item-caret">{isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</span>
                    <span className="act-item-badge" style={{ color: tm.color, background: tm.color + '18' }}>
                      <tm.Icon size={13} /> {row.monitor_type}
                    </span>
                    <span className="act-item-name" title={row.monitor_name}>{row.monitor_name}</span>
                    <span className="act-item-target" title={row.target}>{row.target}</span>
                    <span className="act-item-action">{t('act.ac.' + row.action)}</span>
                    <span className="act-item-summary" style={{ color: sm.color }}>
                      <sm.Icon size={13} /> {row.result_summary || t('act.st.' + row.result_status)}
                    </span>
                    <span className="act-item-time" title={formatDateSec(row.activity_time)}>{rel(row.activity_time)}</span>
                  </button>
                  {isOpen && (
                    <div className="act-item-detail">
                      <div className="act-detail-grid">
                        <div><label>{t('act.d.target')}</label><span>{row.target || '—'}</span></div>
                        <div><label>{t('act.d.status')}</label><span style={{ color: sm.color }}>{t('act.st.' + row.result_status)}</span></div>
                        <div><label>{t('act.d.time')}</label><span>{formatDateSec(row.activity_time)}</span></div>
                        <div><label>{t('act.d.actor')}</label><span><User size={11} /> {row.actor || 'scheduler'}</span></div>
                        {row.response_ms != null && <div><label>{t('act.d.response')}</label><span>{row.response_ms} ms</span></div>}
                        {row.days_remaining != null && <div><label>{t('act.d.days')}</label><span>{row.days_remaining}</span></div>}
                      </div>
                      {row.error_message && <div className="act-detail-error"><XCircle size={13} /> {row.error_message}{row.error_class ? ` (${row.error_class})` : ''}</div>}
                      {row.result_detail && <pre className="act-detail-raw">{row.result_detail}</pre>}
                      <div className="act-mini-history">
                        <div className="act-mini-title"><Clock size={12} /> {t('act.miniHistory')}</div>
                        {d ? (d.recent.length ? d.recent.map((h) => {
                          const hs = STATUS_META[h.result_status] || STATUS_META.UNKNOWN
                          return (
                            <div key={h.id} className="act-mini-row">
                              <span style={{ color: hs.color }}><hs.Icon size={11} /></span>
                              <span className="act-mini-sum">{h.result_summary || h.result_status}</span>
                              <span className="act-mini-time" title={formatDateSec(h.activity_time)}>{rel(h.activity_time)}</span>
                            </div>
                          )
                        }) : <div className="act-mini-empty">{t('act.noHistory')}</div>) : <div className="act-mini-empty">{t('act.loading')}</div>}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>

          {/* Sayfalama */}
          <div className="act-pagination">
            <span className="act-page-info">{t('act.pageInfo', page + 1, totalPages, total)}</span>
            <div className="act-page-btns">
              <button className="btn btn-secondary" disabled={page <= 0} onClick={() => load(page - 1, false)}>{t('act.prev')}</button>
              <button className="btn btn-secondary" disabled={page + 1 >= totalPages} onClick={() => load(page + 1, false)}>{t('act.next')}</button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
