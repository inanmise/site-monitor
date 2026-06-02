import { useEffect, useState, useCallback, useRef, useMemo } from 'react'
import { api, formatDate, formatDateOnly } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import {
  CheckCircle, AlertTriangle, XCircle, Clock, ChevronUp, ChevronDown,
  Globe, X,
} from 'lucide-react'

const HOURS_OPTIONS = [
  { value: 1,   labelKey: 'act.last1h' },
  { value: 6,   labelKey: 'act.last6h' },
  { value: 24,  labelKey: 'act.last24h' },
  { value: 168, labelKey: 'act.last7d' },
  { value: 720, labelKey: 'act.last30d' },
]

function statusIcon(entry) {
  if (entry.status === 'error') return { Icon: XCircle,       cls: 'act-err' }
  if (entry.warning)            return { Icon: AlertTriangle, cls: 'act-warn' }
  return                               { Icon: CheckCircle,   cls: 'act-ok' }
}

function RunCard({ run }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)

  const isManual = run.run_id === 'manual'
  const isStale  = run.run_id === 'unknown'
  const label    = isManual ? t('act.manual') : isStale ? t('act.stale') : t('act.scheduled')

  function fmtTime(iso) {
    if (!iso) return '—'
    try {
      const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
      return new Date(s).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    } catch { return iso }
  }

  return (
    <div className={`act-run${run.error > 0 ? ' act-run-has-error' : run.warning > 0 ? ' act-run-has-warn' : ''}`}>
      <button className="act-run-header" onClick={() => setOpen((v) => !v)}>
        <div className="act-run-left">
          <span className="act-run-time">
            <Clock size={13} style={{ marginRight: 4, verticalAlign: 'middle' }} />
            {formatDate(run.run_time)}
          </span>
          <span className="act-run-counts">
            <span className="act-chip act-chip-ok"><CheckCircle size={11} />{run.ok}</span>
            {run.warning > 0 && <span className="act-chip act-chip-warn"><AlertTriangle size={11} />{run.warning}</span>}
            {run.error   > 0 && <span className="act-chip act-chip-err"><XCircle size={11} />{run.error}</span>}
            <span className="act-chip act-chip-total">{t('act.certificates', run._filteredCount ?? run.total)}</span>
          </span>
        </div>
        <div className="act-run-right">
          <span className="act-run-label">{label}</span>
          <span className="act-toggle">{open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}</span>
        </div>
      </button>

      {open && (
        <div className="act-entries">
          <table className="act-table">
            <thead>
              <tr>
                <th>{t('act.colStatus')}</th>
                <th>{t('act.colDomain')}</th>
                <th>{t('act.colDays')}</th>
                <th>{t('act.colExpiry')}</th>
                <th>{t('act.colChecked')}</th>
                <th>{t('act.colDetail')}</th>
              </tr>
            </thead>
            <tbody>
              {[...run.entries].sort((a, b) => {
                const pa = a.status === 'error' ? 0 : a.warning ? 1 : 2
                const pb = b.status === 'error' ? 0 : b.warning ? 1 : 2
                if (pa !== pb) return pa - pb
                return (a.days_remaining ?? 999999) - (b.days_remaining ?? 999999)
              }).map((e, i) => {
                const { Icon, cls } = statusIcon(e)
                return (
                  <tr key={i} className={cls}>
                    <td><span className={`act-status-icon ${cls}`}><Icon size={12} /></span></td>
                    <td className="act-domain">{e.domain}</td>
                    <td className="act-days">
                      {e.days_remaining != null
                        ? <span className={e.days_remaining < 0 ? 'act-expired' : e.days_remaining <= 30 ? 'act-expiring' : ''}>{t('act.days', e.days_remaining)}</span>
                        : <span className="act-na">—</span>}
                    </td>
                    <td className="act-date">{formatDateOnly(e.not_after)}</td>
                    <td className="act-date">{fmtTime(e.checked_at)}</td>
                    <td className="act-error-cell">{e.error
                      ? <span className="act-error-msg" title={e.error}>{e.error}</span>
                      : <span className="act-ok-msg">OK</span>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export default function ActivityLog({ refreshTrigger }) {
  const t = useT()
  const [hours, setHours]           = useState(24)
  const [runs, setRuns]             = useState([])
  const [loading, setLoading]       = useState(true)
  const [viewFilter, setViewFilter] = useState('all') // 'all' | 'warnings' | 'errors' | 'errorDomains'
  const hoursRef                    = useRef(hours)

  useEffect(() => { hoursRef.current = hours }, [hours])

  const load = useCallback(() => {
    setLoading(true)
    api.getActivityLog(hours).then((res) => {
      if (res?.success) setRuns(res.data)
      setLoading(false)
    })
  }, [hours])

  const loadSilent = useCallback(() => {
    api.getActivityLog(hoursRef.current).then((res) => {
      if (res?.success) setRuns(res.data)
    })
  }, [])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (refreshTrigger) loadSilent()
  }, [refreshTrigger, loadSilent])

  // Reset filter when hours change
  useEffect(() => { setViewFilter('all') }, [hours])

  const totalRuns   = runs.length
  const totalChecks = runs.reduce((s, r) => s + r.total, 0)
  const totalErrors = runs.reduce((s, r) => s + r.error, 0)
  const totalWarns  = runs.reduce((s, r) => s + r.warning, 0)

  const errorDomains = useMemo(() => {
    const set = new Set()
    runs.forEach(r => (r.entries ?? []).forEach(e => {
      if (e.status === 'error') set.add(e.domain)
    }))
    return set
  }, [runs])
  const totalErrorDomains = errorDomains.size

  const visibleRuns = useMemo(() => {
    if (viewFilter === 'all') return runs
    return runs
      .map(r => {
        const filteredEntries = (r.entries ?? []).filter(e => {
          if (viewFilter === 'warnings')     return !!e.warning && e.status !== 'error'
          if (viewFilter === 'errors')       return e.status === 'error'
          if (viewFilter === 'errorDomains') return e.status === 'error'
          return true
        })
        if (filteredEntries.length === 0) return null
        return { ...r, entries: filteredEntries, _filteredCount: filteredEntries.length }
      })
      .filter(Boolean)
  }, [runs, viewFilter])

  const errorDomainGroups = useMemo(() => {
    if (viewFilter !== 'errorDomains') return null
    const byDomain = new Map()
    runs.forEach(r => {
      (r.entries ?? []).filter(e => e.status === 'error').forEach(e => {
        if (!byDomain.has(e.domain)) byDomain.set(e.domain, [])
        byDomain.get(e.domain).push({ ...e, run_time: r.run_time })
      })
    })
    return Array.from(byDomain.entries())
      .map(([domain, entries]) => ({
        domain,
        count: entries.length,
        latestCheckedAt: entries.map(e => e.checked_at).filter(Boolean).sort().pop(),
        entries: [...entries].sort((a, b) => String(b.checked_at).localeCompare(String(a.checked_at))),
      }))
      .sort((a, b) => b.count - a.count)
  }, [runs, viewFilter])

  return (
    <div className="activity-log">
      <div className="act-toolbar">
        <div className="act-filter-group">
          {HOURS_OPTIONS.map(({ value, labelKey }) => (
            <button
              key={value}
              className={`act-filter-btn${hours === value ? ' active' : ''}`}
              onClick={() => setHours(value)}
            >
              {t(labelKey)}
            </button>
          ))}
        </div>
        <button className="btn btn-secondary act-refresh-btn" onClick={load}>{t('act.refresh')}</button>
      </div>

      {!loading && totalRuns > 0 && (
        <div className="act-summary">
          <button
            type="button"
            className={`act-stat-chip${viewFilter === 'all' ? ' is-active' : ''}`}
            onClick={() => setViewFilter('all')}
            title={t('act.viewAll')}
          >
            <strong>{totalRuns}</strong>&nbsp;{t('act.runsLabel')}
          </button>
          <span className="act-stat-sep">·</span>
          <button
            type="button"
            className={`act-stat-chip${viewFilter === 'all' ? ' is-active' : ''}`}
            onClick={() => setViewFilter('all')}
            title={t('act.viewAll')}
          >
            <strong>{totalChecks}</strong>&nbsp;{t('act.checksLabel')}
          </button>
          {totalWarns > 0 && (
            <>
              <span className="act-stat-sep">·</span>
              <button
                type="button"
                className={`act-stat-chip act-stat-warn${viewFilter === 'warnings' ? ' is-active' : ''}`}
                onClick={() => setViewFilter(v => v === 'warnings' ? 'all' : 'warnings')}
                title={t('act.filterWarnings')}
              >
                <AlertTriangle size={12} />
                <strong>{totalWarns}</strong>&nbsp;{t('act.warningsLabel')}
              </button>
            </>
          )}
          {totalErrors > 0 && (
            <>
              <span className="act-stat-sep">·</span>
              <button
                type="button"
                className={`act-stat-chip act-stat-err${viewFilter === 'errors' ? ' is-active' : ''}`}
                onClick={() => setViewFilter(v => v === 'errors' ? 'all' : 'errors')}
                title={t('act.filterErrors')}
              >
                <XCircle size={12} />
                <strong>{totalErrors}</strong>&nbsp;{t('act.errorsLabel')}
              </button>
            </>
          )}
          {totalErrorDomains > 0 && (
            <>
              <span className="act-stat-sep">·</span>
              <button
                type="button"
                className={`act-stat-chip act-stat-err${viewFilter === 'errorDomains' ? ' is-active' : ''}`}
                onClick={() => setViewFilter(v => v === 'errorDomains' ? 'all' : 'errorDomains')}
                title={t('act.filterErrorDomains')}
              >
                <Globe size={12} />
                <strong>{totalErrorDomains}</strong>&nbsp;{t('act.errorDomainsLabel')}
              </button>
            </>
          )}
          {viewFilter !== 'all' && (
            <button
              type="button"
              className="act-stat-clear"
              onClick={() => setViewFilter('all')}
              title={t('act.clearFilter')}
            >
              <X size={11} /> {t('act.clearFilter')}
            </button>
          )}
        </div>
      )}

      {loading ? (
        <div className="loading">{t('act.loading')}</div>
      ) : runs.length === 0 ? (
        <div className="loading">{t('act.noActivity', hours)}</div>
      ) : viewFilter === 'errorDomains' && errorDomainGroups ? (
        errorDomainGroups.length === 0 ? (
          <div className="loading">{t('act.noErrorDomains')}</div>
        ) : (
          <div className="act-domain-groups">
            {errorDomainGroups.map(g => (
              <div key={g.domain} className="act-domain-group">
                <div className="act-domain-group-header">
                  <Globe size={14} />
                  <strong className="act-domain-name">{g.domain}</strong>
                  <span className="act-chip act-chip-err">
                    <XCircle size={11} /> {t('act.errorCount', g.count)}
                  </span>
                  {g.latestCheckedAt && (
                    <span className="act-domain-latest">
                      {t('act.latestError')}: {formatDate(g.latestCheckedAt)}
                    </span>
                  )}
                </div>
                <div className="act-domain-errors">
                  {g.entries.slice(0, 5).map((e, i) => (
                    <div key={i} className="act-domain-error-row">
                      <Clock size={11} /> <span className="act-domain-error-time">{formatDate(e.checked_at)}</span>
                      <span className="act-domain-error-msg" title={e.error || ''}>{e.error || '—'}</span>
                    </div>
                  ))}
                  {g.entries.length > 5 && (
                    <div className="act-domain-error-more">
                      + {g.entries.length - 5} {t('act.moreErrors')}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )
      ) : visibleRuns.length === 0 ? (
        <div className="loading">{t('act.noMatchingRuns')}</div>
      ) : (
        <div className="act-run-list">
          {visibleRuns.map((run) => <RunCard key={run.run_id + run.run_time} run={run} />)}
        </div>
      )}
    </div>
  )
}
