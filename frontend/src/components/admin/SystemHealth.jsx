import { useState, useEffect, useCallback } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import MiniChart from './MiniChart'
import ChartModal from './ChartModal'

export default function SystemHealth() {
  const t = useT()
  const [health, setHealth]         = useState(null)
  const [metrics, setMetrics]       = useState([])
  const [httpMetrics, setHttpMetrics] = useState(null)
  const [loading, setLoading]       = useState(true)
  const [releasing, setReleasing]   = useState(false)
  const [triggering, setTriggering] = useState(false)
  const [msg, setMsg] = useState(null)
  const [modalChart, setModalChart] = useState(null)

  const load = useCallback(async () => {
    const [healthRes, metricsRes, httpRes] = await Promise.all([
      api.admin.getSystemHealth(),
      api.admin.getMetrics(),
      api.admin.getHttpMetrics(),
    ])
    if (healthRes?.success)  setHealth(healthRes.data)
    if (metricsRes?.success) setMetrics(metricsRes.data)
    if (httpRes?.success)    setHttpMetrics(httpRes.data)
    setLoading(false)
  }, [])

  useEffect(() => {
    load()
    const id = setInterval(load, 30000)
    return () => clearInterval(id)
  }, [load])

  const handleForceRelease = async () => {
    if (!window.confirm(t('sys.lockReleaseConfirm'))) return
    setReleasing(true)
    const res = await api.admin.forceReleaseLock()
    setMsg(res?.success ? t('sys.lockReleased') : t('sys.error'))
    setReleasing(false)
    load()
  }

  const handleForceRun = async () => {
    setTriggering(true)
    await api.runScheduler()
    setMsg(t('sys.checkTriggered'))
    setTriggering(false)
    setTimeout(load, 1200)
  }

  if (loading && !health) {
    return <div className="sys-loading">{t('sys.loading')}</div>
  }

  const { scheduler, lock, pool, memory } = health || {}
  const isRunning = scheduler?.running

  return (
    <div className="sys-health">
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
    </div>
  )
}
