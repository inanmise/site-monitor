import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import MiniChart from './MiniChart'
import ChartModal from './ChartModal'

function fmsDuration(ms) {
  if (!ms || ms <= 0) return '—'
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} sn`
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms % 60000) / 1000)
  return `${m} dk ${s} sn`
}

function DbColor(ms) {
  if (ms < 0) return 'sys-warn-text'
  if (ms < 50) return 'sys-ok-text'
  if (ms < 200) return 'sys-warn-text'
  return 'sys-err-text'
}

export default function SystemHealth() {
  const t = useT()
  const [health, setHealth]           = useState(null)
  const [metrics, setMetrics]         = useState([])
  const [httpMetrics, setHttpMetrics] = useState(null)
  const [dbStats, setDbStats]         = useState([])
  const [dbSort, setDbSort]           = useState({ col: 'total_size_bytes', dir: 'desc' })
  const [dbRefreshing, setDbRefreshing] = useState(false)
  const [loading, setLoading]         = useState(true)
  const [releasing, setReleasing]     = useState(false)
  const [triggering, setTriggering]   = useState(false)
  const fastPollRef  = useRef(null)
  const seenRunning  = useRef(false)
  const [msg, setMsg]                 = useState(null)
  const [modalChart, setModalChart]   = useState(null)
  const [smtpModal, setSmtpModal]     = useState(false)
  const [smtpLogs, setSmtpLogs]       = useState(null)
  const [smtpLoading, setSmtpLoading] = useState(false)

  const load = useCallback(async () => {
    const [healthRes, metricsRes, httpRes, dbRes] = await Promise.all([
      api.admin.getSystemHealth(),
      api.admin.getMetrics(),
      api.admin.getHttpMetrics(),
      api.admin.getDbStats(),
    ])
    if (healthRes?.success)  setHealth(healthRes.data)
    if (metricsRes?.success) setMetrics(metricsRes.data)
    if (httpRes?.success)    setHttpMetrics(httpRes.data)
    if (dbRes?.success)      setDbStats(dbRes.data ?? [])
    setLoading(false)
  }, [])

  useEffect(() => {
    load()
    const id = setInterval(load, 30000)
    return () => {
      clearInterval(id)
      if (fastPollRef.current) clearInterval(fastPollRef.current)
    }
  }, [load])

  const openSmtpModal = async () => {
    setSmtpModal(true)
    if (smtpLogs !== null) return
    setSmtpLoading(true)
    const res = await api.admin.getSmtpLogs()
    setSmtpLogs(res?.success ? res.data : [])
    setSmtpLoading(false)
  }

  const handleForceRelease = async () => {
    if (!window.confirm(t('sys.lockReleaseConfirm'))) return
    setReleasing(true)
    const res = await api.admin.forceReleaseLock()
    setMsg(res?.success ? t('sys.lockReleased') : t('sys.error'))
    setReleasing(false)
    load()
  }

  const startScanPoll = useCallback((prevLastRun) => {
    if (fastPollRef.current) clearInterval(fastPollRef.current)
    seenRunning.current = false
    fastPollRef.current = setInterval(async () => {
      const res = await api.admin.getSystemHealth()
      if (!res?.success) return
      setHealth(res.data)
      const isRunning   = res.data?.scheduler?.running
      const newLastRun  = res.data?.scan?.last_run
      if (isRunning) seenRunning.current = true
      // Tamamlandı: running false'a döndü VEYA last_run değişti (hızlı tarama)
      const done = (seenRunning.current && !isRunning) ||
                   (newLastRun && newLastRun !== prevLastRun)
      if (done) {
        clearInterval(fastPollRef.current)
        fastPollRef.current = null
        load()
      }
    }, 2000)
    setTimeout(() => {
      if (fastPollRef.current) { clearInterval(fastPollRef.current); fastPollRef.current = null }
    }, 300_000)
  }, [load])

  const handleForceRun = async () => {
    setTriggering(true)
    await api.runScheduler()
    setMsg(t('sys.checkTriggered'))
    setTriggering(false)
    startScanPoll(health?.scan?.last_run)
  }

  if (loading && !health) {
    return <div className="sys-loading">{t('sys.loading')}</div>
  }

  const { scheduler, lock, pool, memory, scan, scan_alarm, smtp, db_ms, heartbeat } = health || {}
  const isRunning = scheduler?.running

  // Compute active alarms for banner
  const alarms = []
  if (scan_alarm) alarms.push(t('health.scanAlarm'))
  if (smtp?.alarm) alarms.push(t('health.smtpAlarm'))
  if (heartbeat?.alarm) alarms.push(t('health.hbAlarm'))

  const smtpRate = smtp?.rate ?? 100
  const smtpRateClass = smtpRate >= 99 ? 'sys-ok-text' : smtpRate >= 95 ? 'sys-warn-text' : 'sys-err-text'

  const hbMinutes = heartbeat?.minutes_since ?? -1
  const hbOk = hbMinutes >= 0 && hbMinutes <= 15

  return (
    <div className="sys-health">
      {alarms.length > 0 && (
        <div className="health-alarm-banner">
          <span className="health-alarm-icon">⚠</span>
          <strong>{t('health.alarmBanner')}:</strong> {alarms.join(' • ')}
        </div>
      )}

      {msg && (
        <div className="sys-msg" onClick={() => setMsg(null)}>
          {msg} <span className="sys-msg-close">✕</span>
        </div>
      )}

      <div className="sys-grid">

        {/* Scheduler card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <h3>{t('sys.schedulerTitle')}</h3>
            <span className={`sys-badge ${isRunning ? 'sys-badge-running' : 'sys-badge-idle'}`}>
              {isRunning ? t('sys.running') : t('sys.idle')}
            </span>
          </div>
          <dl className="sys-dl">
            <dt>{t('sys.lastRun')}</dt>
            <dd>{scheduler?.last_run ? formatDate(scheduler.last_run) : t('sys.never')}</dd>
            <dt>{t('sys.currentRunId')}</dt>
            <dd className="sys-mono">{scheduler?.current_run_id || '—'}</dd>
            <dt>{t('sys.instanceId')}</dt>
            <dd className="sys-mono sys-small">{scheduler?.instance_id}</dd>
            <dt>{t('sys.activeDomains')}</dt>
            <dd>{scheduler?.active_domains}</dd>
          </dl>
          <button
            className="btn-primary sys-action-btn"
            disabled={isRunning || triggering}
            onClick={handleForceRun}
          >
            {triggering ? t('sys.triggering') : t('sys.forceRun')}
          </button>
        </div>

        {/* Lock card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <h3>{t('sys.lockTitle')}</h3>
            <span className={`sys-badge ${lock?.held ? 'sys-badge-locked' : 'sys-badge-free'}`}>
              {lock?.held ? t('sys.locked') : t('sys.free')}
            </span>
          </div>
          {lock?.held ? (
            <>
              <dl className="sys-dl">
                <dt>{t('sys.lockedBy')}</dt>
                <dd className="sys-mono sys-small">{lock.locked_by}</dd>
                <dt>{t('sys.lockedUntil')}</dt>
                <dd>{formatDate(lock.locked_until)}</dd>
                <dt>{t('sys.heldByMe')}</dt>
                <dd>{lock.held_by_me ? t('sys.yes') : t('sys.no')}</dd>
              </dl>
              {!lock.held_by_me && (
                <button
                  className="btn-danger sys-action-btn"
                  disabled={releasing}
                  onClick={handleForceRelease}
                >
                  {releasing ? t('sys.releasing') : t('sys.forceRelease')}
                </button>
              )}
            </>
          ) : (
            <p className="sys-free-msg">{t('sys.lockFree')}</p>
          )}
        </div>

        {/* Pool card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <h3>{t('sys.poolTitle')}</h3>
          </div>
          {pool ? (
            <>
              <div className="sys-bar-wrap">
                <div
                  className="sys-bar-fill"
                  style={{ width: `${pool.max_size > 0 ? Math.round((pool.active / pool.max_size) * 100) : 0}%` }}
                />
              </div>
              <dl className="sys-dl">
                <dt>{t('sys.poolActive')}</dt>
                <dd>{pool.active}</dd>
                <dt>{t('sys.poolIdle')}</dt>
                <dd>{pool.idle}</dd>
                <dt>{t('sys.poolTotal')}</dt>
                <dd>{pool.total}</dd>
                <dt>{t('sys.poolWaiting')}</dt>
                <dd className={pool.waiting > 0 ? 'sys-warn-text' : ''}>{pool.waiting}</dd>
                <dt>{t('sys.poolMax')}</dt>
                <dd>{pool.max_size}</dd>
              </dl>
            </>
          ) : (
            <p className="sys-free-msg">{t('sys.poolUnavailable')}</p>
          )}
        </div>

        {/* Memory card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <h3>{t('sys.memTitle')}</h3>
            {memory && (
              <span className={`sys-badge ${memory.used_pct > 85 ? 'sys-badge-locked' : memory.used_pct > 65 ? 'sys-badge-warn' : 'sys-badge-free'}`}>
                {memory.used_pct}%
              </span>
            )}
          </div>
          {memory ? (
            <>
              <div className="sys-bar-wrap">
                <div
                  className="sys-bar-fill"
                  style={{
                    width: `${memory.used_pct}%`,
                    background: memory.used_pct > 85 ? '#ef4444' : memory.used_pct > 65 ? '#f59e0b' : undefined,
                  }}
                />
              </div>
              <dl className="sys-dl">
                <dt>{t('sys.memUsed')}</dt>
                <dd>{memory.used_mb} MB</dd>
                <dt>{t('sys.memFree')}</dt>
                <dd>{memory.free_mb} MB</dd>
                <dt>{t('sys.memTotal')}</dt>
                <dd>{memory.total_mb} MB</dd>
                <dt>{t('sys.memMax')}</dt>
                <dd>{memory.max_mb} MB</dd>
              </dl>
            </>
          ) : null}
        </div>

        {/* Scan card */}
        <div className={`sys-card${scan_alarm ? ' sys-card-alarm' : ''}`}>
          <div className="sys-card-header">
            <h3>{t('health.scanTitle')}</h3>
            {scan_alarm && (
              <span className="sys-badge sys-badge-locked">⚠ {t('health.scanAlarm')}</span>
            )}
          </div>
          <dl className="sys-dl">
            <dt>{t('health.lastScan')}</dt>
            <dd>{scan?.last_run ? formatDate(scan.last_run) : t('sys.never')}</dd>
            <dt>{t('health.scanDuration')}</dt>
            <dd>{fmsDuration(scan?.duration_ms)}</dd>
            <dt>{t('health.scanTotal')}</dt>
            <dd>{scan?.total ?? 0}</dd>
            <dt>{t('health.scanWarn')}</dt>
            <dd className={scan?.warnings > 0 ? 'sys-warn-text' : ''}>{scan?.warnings ?? 0}</dd>
            <dt>{t('health.scanErr')}</dt>
            <dd className={scan?.errors > 0 ? 'sys-err-text' : ''}>{scan?.errors ?? 0}</dd>
          </dl>
        </div>

        {/* SMTP card */}
        <div
          className={`sys-card sys-card-clickable${smtp?.alarm ? ' sys-card-alarm' : ''}`}
          onClick={openSmtpModal}
          title={t('health.smtpClickHint')}
        >
          <div className="sys-card-header">
            <h3>{t('health.smtpTitle')}</h3>
            <span className={`sys-badge ${smtp?.alarm ? 'sys-badge-locked' : 'sys-badge-free'}`}>
              <span className={smtpRateClass}>%{smtpRate}</span>
            </span>
          </div>
          {smtp?.alarm && (
            <div className="health-card-alarm-msg">{t('health.smtpAlarm')}</div>
          )}
          <dl className="sys-dl">
            <dt>{t('health.smtpSent')}</dt>
            <dd>{smtp?.sent ?? 0} / {smtp?.attempted ?? smtp?.total ?? 0}</dd>
            <dt>{t('health.smtpRate')}</dt>
            <dd className={smtpRateClass}>%{smtpRate}</dd>
            <dt></dt>
            <dd className="sys-small sys-muted">{t('health.smtpPeriod')}</dd>
          </dl>
          <div className="health-card-link">{t('health.smtpClickHint')} →</div>
        </div>

        {/* Heartbeat card */}
        <div className={`sys-card${heartbeat?.alarm ? ' sys-card-alarm' : ''}`}>
          <div className="sys-card-header">
            <h3>{t('health.hbTitle')}</h3>
            <span className={`sys-badge ${hbOk ? 'sys-badge-free' : 'sys-badge-locked'}`}>
              {hbOk ? '✓' : '⚠'}
            </span>
          </div>
          {heartbeat?.alarm && (
            <div className="health-card-alarm-msg">{t('health.hbAlarm')}</div>
          )}
          <dl className="sys-dl">
            <dt>{t('health.hbLast')}</dt>
            <dd>{heartbeat?.last_heartbeat ? formatDate(heartbeat.last_heartbeat) : t('sys.never')}</dd>
            <dt></dt>
            <dd className={hbOk ? 'sys-ok-text' : 'sys-err-text'}>
              {hbMinutes >= 0
                ? t('health.hbMinutes').replace('{n}', hbMinutes)
                : t('health.hbAlarm')}
            </dd>
          </dl>
        </div>

      </div>

      {/* DB section — response time + table stats */}
      <div className="metrics-section">
        <div className="health-db-section-header">
          <h3 className="metrics-title" style={{ margin: 0 }}>{t('health.dbTitle')}</h3>
          <button
            className="health-db-refresh-btn"
            disabled={dbRefreshing}
            onClick={async () => {
              setDbRefreshing(true)
              const res = await api.admin.getDbStats()
              if (res?.success) setDbStats(res.data ?? [])
              setDbRefreshing(false)
            }}
            title={t('health.dbRefresh')}
          >
            <span className={dbRefreshing ? 'spin' : ''}>↻</span>
            {dbRefreshing ? t('health.dbRefreshing') : t('health.dbRefresh')}
          </button>
        </div>
        <div className="health-db-header">
          <span className="health-db-label">{t('health.dbResponseMs')}</span>
          <span className={`health-db-ms ${DbColor(db_ms ?? -1)}`}>
            {db_ms != null && db_ms >= 0 ? `${db_ms} ms` : '—'}
          </span>
        </div>
        {dbStats.length > 0 && (() => {
          const DB_COLS = [
            { key: 'table_name',       label: t('health.dbTable'),     num: false },
            { key: 'row_count',        label: t('health.dbRows'),      num: true  },
            { key: 'table_size_bytes', label: t('health.dbTableSize'), num: true  },
            { key: 'total_size_bytes', label: t('health.dbTotalSize'), num: true  },
          ]
          const handleSort = (col) => {
            setDbSort(s => s.col === col
              ? { col, dir: s.dir === 'asc' ? 'desc' : 'asc' }
              : { col, dir: col === 'table_name' ? 'asc' : 'desc' })
          }
          const sorted = [...dbStats].sort((a, b) => {
            const av = a[dbSort.col], bv = b[dbSort.col]
            const cmp = typeof av === 'number'
              ? av - bv
              : String(av ?? '').localeCompare(String(bv ?? ''))
            return dbSort.dir === 'asc' ? cmp : -cmp
          })
          const arrow = (col) => dbSort.col === col ? (dbSort.dir === 'asc' ? ' ↑' : ' ↓') : ''
          return (
            <div className="health-table-wrap">
              <table className="health-dbtable">
                <colgroup>
                  <col className="dbtcol-name" />
                  <col className="dbtcol-num" />
                  <col className="dbtcol-num" />
                  <col className="dbtcol-num" />
                </colgroup>
                <thead>
                  <tr>
                    {DB_COLS.map(c => (
                      <th
                        key={c.key}
                        className={`dbtcol-th${c.num ? ' dbtcol-th-num' : ''}${dbSort.col === c.key ? ' dbtcol-active' : ''}`}
                        onClick={() => handleSort(c.key)}
                      >
                        {c.label}<span className="dbt-arrow">{arrow(c.key)}</span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {sorted.map(row => (
                    <tr key={row.table_name}>
                      <td className="dbtcol-name-cell sys-mono">{row.table_name}</td>
                      <td className="dbtcol-num-cell">{Number(row.row_count).toLocaleString()}</td>
                      <td className="dbtcol-num-cell sys-muted">{row.table_size}</td>
                      <td className="dbtcol-num-cell">{row.total_size}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        })()}
      </div>

      {/* HTTP request metrics */}
      {httpMetrics && (
        <div className="metrics-section">
          <h3 className="metrics-title">{t('http.title')}</h3>

          <div className="http-stats-row">
            <div className="http-stat">
              <span className="http-stat-val">{httpMetrics.summary?.total_requests ?? 0}</span>
              <span className="http-stat-lbl">{t('http.totalReqs')}</span>
            </div>
            <div className="http-stat">
              <span className={`http-stat-val ${(httpMetrics.summary?.error_rate_pct ?? 0) > 5 ? 'http-stat-err' : ''}`}>
                {httpMetrics.summary?.error_rate_pct ?? 0}%
              </span>
              <span className="http-stat-lbl">{t('http.errorRate')}</span>
            </div>
            <div className="http-stat">
              <span className="http-stat-val">{httpMetrics.summary?.avg_ms ?? 0} ms</span>
              <span className="http-stat-lbl">{t('http.avgMs')}</span>
            </div>
            <div className="http-stat">
              <span className="http-stat-val">{httpMetrics.summary?.max_ms ?? 0} ms</span>
              <span className="http-stat-lbl">{t('http.maxMs')}</span>
            </div>
          </div>

          <div className="metrics-grid">
            <MiniChart
              label={t('http.reqPerMin')}
              unit=""
              color="#4f9cf9"
              data={(httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.count }))}
              onClick={() => setModalChart({
                label: t('http.reqPerMin'), unit: '', color: '#4f9cf9',
                data: (httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.count })),
              })}
            />
            <MiniChart
              label={t('http.avgDuration')}
              unit=" ms"
              color="#f59e0b"
              data={(httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.avg_ms }))}
              onClick={() => setModalChart({
                label: t('http.avgDuration'), unit: ' ms', color: '#f59e0b',
                data: (httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.avg_ms })),
              })}
            />
            <MiniChart
              label={t('http.errorsPerMin')}
              unit=""
              color="#ef4444"
              data={(httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.errors }))}
              onClick={() => setModalChart({
                label: t('http.errorsPerMin'), unit: '', color: '#ef4444',
                data: (httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.errors })),
              })}
            />
          </div>
        </div>
      )}

      {/* JVM / CPU metrics */}
      <div className="metrics-section">
        <h3 className="metrics-title">{t('sys.metricsTitle')}</h3>
        <div className="metrics-grid">
          <MiniChart
            label={t('sys.cpuProcess')}
            unit="%"
            maxY={100}
            color="#4f9cf9"
            data={metrics.map(p => ({ ts: p.ts, value: p.cpu_process }))}
            onClick={() => setModalChart({
              label: t('sys.cpuProcess'), unit: '%', color: '#4f9cf9', maxY: 100,
              data: metrics.map(p => ({ ts: p.ts, value: p.cpu_process })),
            })}
          />
          <MiniChart
            label={t('sys.heapPct')}
            unit="%"
            maxY={100}
            color="#10b981"
            data={metrics.map(p => ({ ts: p.ts, value: p.heap_pct }))}
            onClick={() => setModalChart({
              label: t('sys.heapPct'), unit: '%', color: '#10b981', maxY: 100,
              data: metrics.map(p => ({ ts: p.ts, value: p.heap_pct })),
            })}
          />
          <MiniChart
            label={t('sys.threads')}
            unit=""
            color="#a78bfa"
            data={metrics.map(p => ({ ts: p.ts, value: p.threads }))}
            onClick={() => setModalChart({
              label: t('sys.threads'), unit: '', color: '#a78bfa',
              data: metrics.map(p => ({ ts: p.ts, value: p.threads })),
            })}
          />
        </div>
      </div>

      <p className="sys-refresh-note">↻ {t('sys.autoRefresh')}</p>

      <ChartModal chart={modalChart} onClose={() => setModalChart(null)} />

      {smtpModal && (
        <div className="smtp-modal-overlay" onClick={() => setSmtpModal(false)}>
          <div className="smtp-modal" onClick={e => e.stopPropagation()}>
            <div className="smtp-modal-header">
              <h3>{t('health.smtpLogsTitle')}</h3>
              <span className="smtp-modal-period">{t('health.smtpPeriod')}</span>
              <button className="smtp-modal-close" onClick={() => setSmtpModal(false)}>✕</button>
            </div>

            {smtpLoading ? (
              <div className="smtp-modal-loading">{t('sys.loading')}</div>
            ) : smtpLogs?.length === 0 ? (
              <div className="smtp-modal-empty">{t('health.smtpNoErrors')}</div>
            ) : (
              <div className="smtp-modal-body">
                <table className="smtp-log-table">
                  <thead>
                    <tr>
                      <th>{t('health.smtpLogDate')}</th>
                      <th>{t('health.smtpLogRecipient')}</th>
                      <th>{t('health.smtpLogSubject')}</th>
                      <th>{t('health.smtpLogError')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {smtpLogs.map(row => (
                      <tr key={row.id}>
                        <td className="smtp-log-date sys-mono">{formatDate(row.sent_at)}</td>
                        <td>
                          <div className="smtp-log-recipient">{row.recipient_name}</div>
                          <div className="smtp-log-email sys-muted sys-small">{row.recipient_email}</div>
                        </td>
                        <td className="smtp-log-subject">{row.subject}</td>
                        <td>
                          <span className={`smtp-kind-badge smtp-kind-${row.kind?.toLowerCase()}`}>
                            {row.kind}
                          </span>
                          {row.error && <div className="smtp-log-error sys-err-text">{row.error}</div>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
