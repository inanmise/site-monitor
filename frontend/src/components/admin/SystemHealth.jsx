import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { CheckCircle, XCircle, MinusCircle, HelpCircle, Mail, ChevronRight, Check, Loader2, Server, Database, Globe, Cpu, ChevronDown } from 'lucide-react'
import MiniChart from './MiniChart'
import ChartModal from './ChartModal'
import HeartbeatHistoryModal from './HeartbeatHistoryModal'

function SmtpStatusCell({ row, t }) {
  const cfg = {
    SENT:    { Icon: CheckCircle,  cls: 'smtp-kind-sent',    key: 'health.statusSent' },
    FAILED:  { Icon: XCircle,      cls: 'smtp-kind-failed',  key: 'health.statusFailed' },
    SKIPPED: { Icon: MinusCircle,  cls: 'smtp-kind-skipped', key: 'health.statusSkipped' },
    UNKNOWN: { Icon: HelpCircle,   cls: 'smtp-kind-skipped', key: 'health.statusUnknown' },
  }
  const c = cfg[row.kind] ?? cfg.UNKNOWN
  return (
    <div>
      <span className={`smtp-kind-badge ${c.cls}`}>
        <c.Icon size={11} />{t(c.key)}
      </span>
      {row.error && (
        <div className="smtp-log-error sys-err-text sys-small">{row.error}</div>
      )}
    </div>
  )
}

function triggerLabel(trigger, t) {
  const map = {
    INITIAL:       t('health.triggerInitial'),
    ESCALATION:    t('health.triggerEscalation'),
    DAILY_REALERT: t('health.triggerDailyRealert'),
    MANUAL:        t('health.triggerManual'),
    RESOLUTION:    t('health.triggerResolution'),
  }
  return map[trigger] ?? trigger
}

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

export default function SystemHealth({ systemRole, preFilterDomain, openSmtpModalOnLoad, onSmtpPreFilterConsumed }) {
  const isAdmin = systemRole === 'ADMIN'
  const t = useT()
  const [health, setHealth]           = useState(null)
  const [metrics, setMetrics]         = useState([])
  const [httpMetrics, setHttpMetrics] = useState(null)
  const [dbStats, setDbStats]         = useState([])
  const [dbSort, setDbSort]           = useState({ col: 'total_size_bytes', dir: 'desc' })
  const [dbRefreshing, setDbRefreshing] = useState(false)
  const [poolCardRefreshing, setPoolCardRefreshing] = useState(false)
  const [poolLastRefreshed, setPoolLastRefreshed]   = useState(null)
  const [hbRefreshing, setHbRefreshing] = useState(false)
  const [hbModalOpen, setHbModalOpen] = useState(false)
  const [openSection, setOpenSection] = useState('sys')
  const toggleSection = (key) => setOpenSection(prev => prev === key ? null : key)
  const sysVisible  = openSection === 'sys'
  const dbVisible   = openSection === 'db'
  const httpVisible = openSection === 'http'
  const cpuVisible  = openSection === 'cpu'
  const [smtpPeriod, setSmtpPeriod]   = useState('7d')
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
  const [selectedLog, setSelectedLog] = useState(null)
  const [smtpFilters, setSmtpFilters] = useState({ from: '', to: '', subject: '', status: '', domain: '' })

  const load = useCallback(async () => {
    const [healthRes, metricsRes, httpRes, dbRes] = await Promise.all([
      api.admin.getSystemHealth(),
      api.admin.getMetrics(),
      api.admin.getHttpMetrics(),
      api.admin.getDbStats(),
    ])
    if (healthRes?.success)  { setHealth(healthRes.data); setPoolLastRefreshed(new Date()) }
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

  const refreshHeartbeat = useCallback(async () => {
    setHbRefreshing(true)
    const res = await api.admin.triggerHeartbeat()
    if (res?.success) setHealth(prev => ({ ...prev, heartbeat: res.data }))
    setHbRefreshing(false)
  }, [])

  const refreshPool = useCallback(async () => {
    setPoolCardRefreshing(true)
    const res = await api.admin.getSystemHealth()
    if (res?.success) {
      setHealth(prev => ({ ...prev, ...res.data }))
      setPoolLastRefreshed(new Date())
    }
    setPoolCardRefreshing(false)
  }, [])

  useEffect(() => {
    const id = setInterval(refreshPool, 60_000)
    return () => clearInterval(id)
  }, [refreshPool])

  const openSmtpModal = useCallback(async (overrides) => {
    setSmtpModal(true)
    setSmtpLogs(null)
    setSmtpFilters({ from: '', to: '', subject: '', status: '', domain: '', ...(overrides ?? {}) })
    setSmtpLoading(true)
    const days = parseInt(smtpPeriod) || 30
    const res = await api.admin.getSmtpLogs(days)
    setSmtpLogs(res?.success ? res.data : [])
    setSmtpLoading(false)
  }, [smtpPeriod])

  useEffect(() => {
    if (openSmtpModalOnLoad) {
      openSmtpModal(preFilterDomain ? { domain: preFilterDomain } : undefined)
      onSmtpPreFilterConsumed?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openSmtpModalOnLoad, preFilterDomain])

  const closeSmtpModal = () => {
    setSmtpModal(false)
    setSmtpFilters({ from: '', to: '', subject: '', status: '', domain: '' })
  }

  const filteredSmtpLogs = useMemo(() => {
    if (!smtpLogs) return null
    const f = smtpFilters
    const ci = s => (s ?? '').toString().toLowerCase()
    return smtpLogs.filter(l => {
      if (f.from    && !ci(l.sender_email).includes(ci(f.from))) return false
      if (f.to      && !(ci(l.recipient_email) + ' ' + ci(l.recipient_name)).includes(ci(f.to))) return false
      if (f.subject && !ci(l.subject).includes(ci(f.subject))) return false
      if (f.status  && l.kind !== f.status) return false
      if (f.domain  && !ci(l.domain).includes(ci(f.domain))) return false
      return true
    })
  }, [smtpLogs, smtpFilters])

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

  const { scheduler, lock, pool, executor_pool, memory, scan, scan_alarm, smtp, db_ms, heartbeat } = health || {}
  const isRunning = scheduler?.running

  const smtpData = smtp?.periods?.[smtpPeriod] ?? smtp ?? {}
  const smtpRate = smtpData.rate ?? 100
  const smtpRateClass = smtpRate >= 99 ? 'sys-ok-text' : smtpRate >= 95 ? 'sys-warn-text' : 'sys-err-text'
  const smtpHasAlarm = !!smtpData.alarm

  // Compute active alarms for banner — SMTP alarm reflects the currently selected period
  const alarms = []
  if (scan_alarm) alarms.push(t('health.scanAlarm'))
  if (smtpHasAlarm) alarms.push(t('health.smtpAlarmFor', t(`health.smtpPeriod${smtpPeriod}`)))
  if (heartbeat?.alarm) alarms.push(t('health.hbAlarm'))
  if (health?.network?.alarm) alarms.push(t('health.networkAlarm'))

  const hbMinutes = heartbeat?.minutes_since ?? -1
  const hbOk = hbMinutes >= 0 && hbMinutes <= 15

  const poolUsePct = pool?.max_size > 0 ? Math.round((pool.active / pool.max_size) * 100) : 0
  const poolIconClass = pool?.waiting > 0 ? 'pool-icon-alarm' : poolUsePct > 80 ? 'pool-icon-warn' : 'pool-icon-ok'
  const memIconClass = !memory ? 'mem-icon-ok' : memory.used_pct > 85 ? 'mem-icon-alarm' : memory.used_pct > 65 ? 'mem-icon-warn' : 'mem-icon-ok'

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

      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('sys')}
          title={sysVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Server size={18} /></span>
          <span className="stats-collapse-label">{t('health.sectionSystem')}</span>
          <span className={`stats-collapse-chevron${sysVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {sysVisible && (
      <div className="sys-grid">

        {/* Scheduler card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`sched-icon ${isRunning ? 'sched-icon-running' : 'sched-icon-idle'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10"/>
                <polyline points="12 6 12 12 16 14"/>
              </svg>
              <h3>{t('sys.schedulerTitle')}</h3>
            </div>
            <span className={`sys-badge ${isRunning ? 'sys-badge-running' : 'sys-badge-idle'}`}>
              {isRunning ? t('sys.running') : t('sys.idle')}
            </span>
          </div>
          <dl className="sys-dl">
            <dt>{t('sys.lastRun')}</dt>
            <dd>{scheduler?.last_run ? formatDate(scheduler.last_run) : t('sys.never')}</dd>
            <dt>{t('sys.nextRun')}</dt>
            <dd>{scheduler?.next_run ? formatDate(scheduler.next_run) : '—'}</dd>
            <dt>{isRunning ? t('sys.currentRunId') : t('sys.lastRunId')}</dt>
            <dd className="sys-mono">
              {isRunning
                ? (scheduler?.current_run_id || '—')
                : (scheduler?.last_run_id || '—')}
            </dd>
            <dt>{t('sys.instanceId')}</dt>
            <dd className="sys-mono sys-small">{scheduler?.instance_id}</dd>
            <dt>{t('sys.activeDomains')}</dt>
            <dd>{scheduler?.active_domains}</dd>
          </dl>
          {isAdmin && (
            <button
              className="btn-primary sys-action-btn"
              disabled={isRunning || triggering}
              onClick={handleForceRun}
            >
              {triggering ? t('sys.triggering') : t('sys.forceRun')}
            </button>
          )}
        </div>

        {/* Lock card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`lock-icon ${lock?.held ? 'lock-icon-held' : 'lock-icon-free'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                <path d="M7 11V7a5 5 0 0110 0v4"/>
              </svg>
              <h3>{t('sys.lockTitle')}</h3>
            </div>
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
              {!lock.held_by_me && isAdmin && (
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
            <div className="hb-title-row">
              <svg className={`pool-icon ${poolIconClass}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <ellipse cx="12" cy="5" rx="9" ry="3"/>
                <path d="M21 12c0 1.66-4 3-9 3S3 13.66 3 12"/>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
              </svg>
              <h3>{t('sys.poolTitle')}</h3>
            </div>
            <button
              className="sys-card-refresh-btn"
              onClick={refreshPool}
              disabled={poolCardRefreshing}
              title={t('sys.poolRefresh')}
              aria-label={t('sys.poolRefresh')}
            >
              <span className={poolCardRefreshing ? 'spin' : ''}>↻</span>
            </button>
          </div>
          {poolLastRefreshed && (
            <div className="pool-updated-at">
              {t('sys.poolUpdatedAt').replace('{t}',
                poolLastRefreshed.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
              )}
            </div>
          )}
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
            <div className="hb-title-row">
              <svg className={`mem-icon ${memIconClass}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="4" y="4" width="16" height="16" rx="2" ry="2"/>
                <rect x="9" y="9" width="6" height="6"/>
                <line x1="9" y1="1" x2="9" y2="4"/>
                <line x1="15" y1="1" x2="15" y2="4"/>
                <line x1="9" y1="20" x2="9" y2="23"/>
                <line x1="15" y1="20" x2="15" y2="23"/>
                <line x1="20" y1="9" x2="23" y2="9"/>
                <line x1="20" y1="14" x2="23" y2="14"/>
                <line x1="1" y1="9" x2="4" y2="9"/>
                <line x1="1" y1="14" x2="4" y2="14"/>
              </svg>
              <h3>{t('sys.memTitle')}</h3>
            </div>
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
            <div className="hb-title-row">
              <svg className={`scan-icon ${scan_alarm ? 'scan-icon-alarm' : 'scan-icon-ok'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10"/>
                <circle cx="12" cy="12" r="6"/>
                <circle cx="12" cy="12" r="2"/>
                <line x1="12" y1="2" x2="12" y2="12"/>
              </svg>
              <h3>{t('health.scanTitle')}</h3>
            </div>
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
          className={`sys-card sys-card-clickable${smtpHasAlarm ? ' sys-card-alarm' : ''}`}
          onClick={openSmtpModal}
          title={t('health.smtpClickHint')}
        >
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`smtp-envelope ${smtpHasAlarm ? 'smtp-envelope-alarm' : 'smtp-envelope-ok'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="2" y="4" width="20" height="16" rx="2"/>
                <polyline points="2,4 12,13 22,4"/>
              </svg>
              <h3>{t('health.smtpTitle')}</h3>
            </div>
            <span className={`sys-badge ${smtpHasAlarm ? 'sys-badge-locked' : 'sys-badge-free'}`}>
              <span className={smtpRateClass}>%{smtpRate}</span>
            </span>
          </div>
          {smtpHasAlarm && (
            <div className="health-card-alarm-msg">
              {t('health.smtpAlarmFor', t(`health.smtpPeriod${smtpPeriod}`))}
            </div>
          )}
          <div className="smtp-stream-wrap">
            <div className={`smtp-stream-line ${smtpHasAlarm ? 'smtp-stream-alarm' : 'smtp-stream-ok'}`} />
            {[0, 1, 2, 3].map(i => (
              <div
                key={i}
                className={`smtp-stream-dot ${smtpHasAlarm ? 'smtp-stream-alarm' : 'smtp-stream-ok'}`}
                style={{ animationDelay: `${i * 0.55}s` }}
              />
            ))}
          </div>
          <div className="smtp-period-pills" onClick={e => e.stopPropagation()}>
            {['1d', '7d', '15d', '30d'].map(p => (
              <button
                key={p}
                type="button"
                className={`smtp-period-pill${smtpPeriod === p ? ' is-selected' : ''}`}
                onClick={() => setSmtpPeriod(p)}
              >
                {t(`health.smtpPeriod${p}`)}
              </button>
            ))}
          </div>
          <dl className="sys-dl">
            <dt>{t('health.smtpSent')}</dt>
            <dd>{smtpData.sent ?? 0} / {smtpData.attempted ?? smtpData.total ?? 0}</dd>
            <dt>{t('health.smtpRate')}</dt>
            <dd className={smtpRateClass}>%{smtpRate}</dd>
            <dt></dt>
            <dd className="sys-small sys-muted">{t('health.smtpPeriodActive', t(`health.smtpPeriod${smtpPeriod}`))}</dd>
          </dl>
          <button
            type="button"
            className="smtp-log-cta"
            onClick={(e) => { e.stopPropagation(); openSmtpModal() }}
          >
            <Mail size={14} />
            <span>{t('health.smtpClickHint')}</span>
            <span className="smtp-log-cta-period">{t(`health.smtpPeriod${smtpPeriod}`)}</span>
            <ChevronRight size={14} className="smtp-log-cta-arrow" />
          </button>
        </div>

        {/* Heartbeat card */}
        <div
          className={`sys-card hb-card-clickable${heartbeat?.alarm ? ' sys-card-alarm' : ''}`}
          onClick={() => setHbModalOpen(true)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setHbModalOpen(true) } }}
          title={t('health.hbHistoryHint')}
        >
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`hb-heart ${hbOk ? 'hb-heart-ok' : 'hb-heart-alarm'}`} viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>
              </svg>
              <h3>{t('health.hbTitle')}</h3>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }} onClick={(e) => e.stopPropagation()}>
              {isAdmin && (
                <button
                  className="sys-card-refresh-btn"
                  onClick={refreshHeartbeat}
                  disabled={hbRefreshing}
                  title={t('sys.poolRefresh')}
                  aria-label={t('sys.poolRefresh')}
                >
                  <span className={hbRefreshing ? 'spin' : ''}>↻</span>
                </button>
              )}
              <span className={`sys-badge ${hbOk ? 'sys-badge-free' : 'sys-badge-locked'}`}>
                {hbOk ? '✓' : '⚠'}
              </span>
            </div>
          </div>
          {heartbeat?.alarm && (
            <div className="health-card-alarm-msg">{t('health.hbAlarm')}</div>
          )}
          <div className="hb-ecg-wrap">
            <svg className={`hb-ecg-svg ${hbOk ? 'hb-ecg-ok' : 'hb-ecg-alarm'}`} viewBox="0 0 400 44" preserveAspectRatio="none" aria-hidden="true">
              <polyline points="0,22 50,22 57,19 63,22 78,22 84,4 90,40 96,4 102,22 116,14 131,22 200,22 250,22 257,19 263,22 278,22 284,4 290,40 296,4 302,22 316,14 331,22 380,22" fill="none" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/>
              {hbOk ? (
                <g transform="translate(388, 22)">
                  <circle r="10" className="hb-terminal-bg-ok" />
                  <path d="M -4 0 L -1 3 L 5 -4" className="hb-terminal-tick" />
                </g>
              ) : (
                <g transform="translate(388, 22)">
                  <circle r="10" className="hb-terminal-bg-err" />
                  <path d="M -4 -4 L 4 4 M 4 -4 L -4 4" className="hb-terminal-cross" />
                </g>
              )}
            </svg>
          </div>
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
          {heartbeat?.recent?.length > 0 && (
            <div className="hb-recent">
              <div className="hb-recent-title">{t('health.hbRecent')}</div>
              <ul className="hb-recent-list">
                {heartbeat.recent.map((ts, i) => (
                  <li key={ts} className="hb-recent-item">
                    <span className={`hb-dot ${i === 0 ? 'hb-dot-ok' : 'hb-dot-prev'}`} />
                    <span className="hb-recent-ts">{formatDate(ts)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        {/* Task Queue card — certCheckExecutor metrics */}
        {executor_pool && (
          <div className={`sys-card${(executor_pool.queue_size ?? 0) > (executor_pool.queue_capacity ?? 1) * 0.8 ? ' sys-card-alarm' : ''}`}>
            <div className="sys-card-header">
              <div className="hb-title-row">
                <span className="hb-heart hb-heart-ok" style={{ fontSize: 18 }}>⚡</span>
                <h3>{t('health.queueTitle')}</h3>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <button
                  className="sys-card-refresh-btn"
                  onClick={refreshPool}
                  disabled={poolCardRefreshing}
                  title={t('sys.poolRefresh')}
                  aria-label={t('sys.poolRefresh')}
                >
                  <span className={poolCardRefreshing ? 'spin' : ''}>↻</span>
                </button>
                <span className={`sys-badge ${(executor_pool.queue_size ?? 0) === 0 ? 'sys-badge-free' : 'sys-badge-locked'}`}>
                  {executor_pool.queue_size ?? 0}
                </span>
              </div>
            </div>
            <dl className="sys-dl">
              <dt title={t('health.queueTooltip')}>
                {t('health.queuePending')}
                <span className="queue-info-ind" aria-hidden="true">ⓘ</span>
              </dt>
              <dd>
                <strong>{executor_pool.queue_size ?? 0}</strong> / {executor_pool.queue_capacity ?? 0}
                <span className="queue-status-ind">
                  {(executor_pool.queue_size ?? 0) === 0
                    ? <Check size={14} className="queue-status-ok" strokeWidth={3} />
                    : <Loader2 size={14} className="queue-status-busy spin" />}
                </span>
              </dd>

              <dt>{t('health.queueActive')}</dt>
              <dd>
                {executor_pool.active_count ?? 0} / {executor_pool.pool_size ?? 0}
                <span className="queue-status-ind">
                  {(executor_pool.active_count ?? 0) === 0
                    ? <Check size={14} className="queue-status-ok" strokeWidth={3} />
                    : <Loader2 size={14} className="queue-status-busy spin" />}
                </span>
              </dd>

              <dt>{t('health.queueThreads')}</dt>
              <dd>{t('health.queueMinMax')
                    .replace('{min}', executor_pool.core_pool_size ?? 0)
                    .replace('{max}', executor_pool.max_pool_size ?? 0)}</dd>

              <dt>{t('health.queueCompleted')}</dt>
              <dd>{executor_pool.completed_tasks ?? 0}</dd>

              {executor_pool.jvm_start_time && (
                <>
                  <dt title={t('health.queueSinceStartTooltip')}>{t('health.queueSinceStart')}</dt>
                  <dd>{formatDate(executor_pool.jvm_start_time)}</dd>
                </>
              )}
            </dl>
            <div className="queue-bar-wrap" aria-hidden="true">
              <div
                className="queue-bar-fill"
                style={{ width: `${Math.min(100, ((executor_pool.queue_size ?? 0) / Math.max(1, executor_pool.queue_capacity ?? 1)) * 100)}%` }}
              />
            </div>
          </div>
        )}

      </div>
        )}
      </div>

      {/* HTTP request metrics */}
      {httpMetrics && (
        <div className="stats-section">
          <div
            className="stats-collapse-bar"
            onClick={() => toggleSection('http')}
            title={httpVisible ? t('app.collapseStats') : t('app.expandStats')}
          >
            <span className="stats-collapse-icon"><Globe size={18} /></span>
            <span className="stats-collapse-label">{t('http.shortTitle')}</span>
            <span className={`stats-collapse-chevron${httpVisible ? ' open' : ''}`}>
              <ChevronDown size={18} />
            </span>
          </div>
          {httpVisible && (
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
        </div>
      )}

      {/* JVM / CPU metrics */}
      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('cpu')}
          title={cpuVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Cpu size={18} /></span>
          <span className="stats-collapse-label">{t('health.sectionCpu')}</span>
          <span className={`stats-collapse-chevron${cpuVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {cpuVisible && (
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
        )}
      </div>

      {/* DB section — response time + table stats */}
      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('db')}
          title={dbVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Database size={18} /></span>
          <span className="stats-collapse-label">{t('health.dbTitle')}</span>
          <span className={`stats-collapse-chevron${dbVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {dbVisible && (
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
        )}
      </div>

      <p className="sys-refresh-note">↻ {t('sys.autoRefresh')}</p>

      <ChartModal chart={modalChart} onClose={() => setModalChart(null)} />

      {selectedLog && (
        <div className="smtp-detail-overlay" onClick={() => setSelectedLog(null)}>
          <div className="smtp-detail-panel" onClick={e => e.stopPropagation()}>
            <div className="smtp-detail-header">
              <div className="smtp-detail-header-left">
                <Mail size={17} className="smtp-detail-mail-icon" />
                <span>{t('health.emailDetail')}</span>
              </div>
              <button className="smtp-modal-close" onClick={() => setSelectedLog(null)}>✕</button>
            </div>
            <div className="smtp-detail-meta">
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailFrom')}</span>
                <span className="sys-muted">{selectedLog.sender_email}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailTo')}</span>
                <span>
                  <strong>{selectedLog.recipient_name}</strong>
                  {selectedLog.recipient_email && (
                    <span className="sys-muted"> &lt;{selectedLog.recipient_email}&gt;</span>
                  )}
                </span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.smtpLogSubject')}</span>
                <span className="smtp-detail-subject">{selectedLog.subject}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.smtpLogDate')}</span>
                <span className="sys-mono">{formatDate(selectedLog.sent_at)}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailTrigger')}</span>
                <span className={`smtp-trigger-badge smtp-trigger-${selectedLog.trigger?.toLowerCase()}`}>
                  {triggerLabel(selectedLog.trigger, t)}
                </span>
                <SmtpStatusCell row={selectedLog} t={t} />
              </div>
            </div>
            <div className="smtp-detail-body-label">{t('health.emailDetailBody')}</div>
            <iframe
              className="smtp-detail-iframe"
              srcDoc={selectedLog.message ?? `<p style="color:#9ca3af;font-family:sans-serif">${t('health.emailDetailNoBody')}</p>`}
              sandbox=""
              title={selectedLog.subject}
            />
          </div>
        </div>
      )}

      {hbModalOpen && (
        <HeartbeatHistoryModal onClose={() => setHbModalOpen(false)} />
      )}

      {smtpModal && (
        <div className="smtp-modal-overlay" onClick={closeSmtpModal}>
          <div className="smtp-modal" onClick={e => e.stopPropagation()}>
            <div className="smtp-modal-header">
              <h3>{t('health.smtpLogsTitle')}</h3>
              <span className="smtp-modal-period">{t(`health.smtpPeriod${smtpPeriod}`)}</span>
              <button className="smtp-modal-close" onClick={closeSmtpModal}>✕</button>
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
                      <th>{t('health.smtpLogDomain')}</th>
                      <th>{t('health.smtpLogFrom')}</th>
                      <th>{t('health.smtpLogTo')}</th>
                      <th>{t('health.smtpLogSubject')}</th>
                      <th>{t('health.smtpLogStatus')}</th>
                    </tr>
                    <tr className="smtp-log-filter-row">
                      <th />
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterDomain')}
                          value={smtpFilters.domain}
                          onChange={e => setSmtpFilters(s => ({ ...s, domain: e.target.value }))}
                        />
                      </th>
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterFrom')}
                          value={smtpFilters.from}
                          onChange={e => setSmtpFilters(s => ({ ...s, from: e.target.value }))}
                        />
                      </th>
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterTo')}
                          value={smtpFilters.to}
                          onChange={e => setSmtpFilters(s => ({ ...s, to: e.target.value }))}
                        />
                      </th>
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterSubject')}
                          value={smtpFilters.subject}
                          onChange={e => setSmtpFilters(s => ({ ...s, subject: e.target.value }))}
                        />
                      </th>
                      <th>
                        <select
                          value={smtpFilters.status}
                          onChange={e => setSmtpFilters(s => ({ ...s, status: e.target.value }))}
                        >
                          <option value="">{t('health.smtpFilterStatusAll')}</option>
                          <option value="SENT">{t('health.smtpFilterStatusSent')}</option>
                          <option value="FAILED">{t('health.smtpFilterStatusFailed')}</option>
                          <option value="SKIPPED">{t('health.smtpFilterStatusSkipped')}</option>
                        </select>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredSmtpLogs?.length === 0 ? (
                      <tr className="smtp-log-empty-row">
                        <td colSpan={6}>{t('health.smtpLogNoMatch')}</td>
                      </tr>
                    ) : (
                      filteredSmtpLogs?.map(row => (
                        <tr key={row.id} className="smtp-log-row" onClick={() => setSelectedLog(row)}>
                          <td className="smtp-log-date sys-mono">{formatDate(row.sent_at)}</td>
                          <td className="smtp-log-domain sys-mono sys-small">{row.domain || '—'}</td>
                          <td className="smtp-log-from sys-mono sys-small">{row.sender_email || '—'}</td>
                          <td>
                            <div className="smtp-log-recipient">{row.recipient_name || '—'}</div>
                            <div className="smtp-log-email sys-muted sys-small">{row.recipient_email}</div>
                          </td>
                          <td className="smtp-log-subject">{row.subject}</td>
                          <td><SmtpStatusCell row={row} t={t} /></td>
                        </tr>
                      ))
                    )}
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
