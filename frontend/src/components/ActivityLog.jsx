import { useEffect, useState, useCallback, useRef } from 'react'
import { api, formatDate, formatDateOnly } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { CheckCircle, AlertTriangle, XCircle, Clock, ChevronUp, ChevronDown } from 'lucide-react'

const HOURS_OPTIONS = [1, 6, 24]

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
          <span className="act-run-time"><Clock size={13} style={{ marginRight: 4, verticalAlign: 'middle' }} />{formatDate(run.run_time)}</span>
          <span className="act-run-label">{label}</span>
        </div>
        <div className="act-run-counts">
          <span className="act-chip act-chip-ok"><CheckCircle size={11} />{run.ok}</span>
          {run.warning > 0 && <span className="act-chip act-chip-warn"><AlertTriangle size={11} />{run.warning}</span>}
          {run.error   > 0 && <span className="act-chip act-chip-err"><XCircle size={11} />{run.error}</span>}
          <span className="act-chip act-chip-total">{t('act.certificates', run.total)}</span>
        </div>
        <span className="act-toggle">{open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}</span>
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
  const [hours, setHours]     = useState(24)
  const [runs, setRuns]       = useState([])
  const [loading, setLoading] = useState(true)
  const hoursRef              = useRef(hours)

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

  const totalRuns   = runs.length
  const totalChecks = runs.reduce((s, r) => s + r.total, 0)
  const totalErrors = runs.reduce((s, r) => s + r.error, 0)
  const totalWarns  = runs.reduce((s, r) => s + r.warning, 0)

  return (
    <div className="activity-log">
      <div className="act-toolbar">
        <div className="act-filter-group">
          {HOURS_OPTIONS.map((h) => (
            <button
              key={h}
              className={`act-filter-btn${hours === h ? ' active' : ''}`}
              onClick={() => setHours(h)}
            >
              {t('act.lastHours', h)}
            </button>
          ))}
        </div>
        <button className="btn btn-secondary act-refresh-btn" onClick={load}>{t('act.refresh')}</button>
      </div>

      {!loading && totalRuns > 0 && (
        <div className="act-summary">
          <span>{t('act.runs', totalRuns)}</span>
          <span>·</span>
          <span>{t('act.checks', totalChecks)}</span>
          {totalWarns > 0 && <><span>·</span><span className="act-chip-warn">{t('act.warnings', totalWarns)}</span></>}
          {totalErrors > 0 && <><span>·</span><span className="act-chip-err">{t('act.errors', totalErrors)}</span></>}
        </div>
      )}

      {loading ? (
        <div className="loading">{t('act.loading')}</div>
      ) : runs.length === 0 ? (
        <div className="loading">{t('act.noActivity', hours)}</div>
      ) : (
        <div className="act-run-list">
          {runs.map((run) => <RunCard key={run.run_id + run.run_time} run={run} />)}
        </div>
      )}
    </div>
  )
}
