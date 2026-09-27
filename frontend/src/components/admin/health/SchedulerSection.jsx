import { Check, Clock, Database, Lock, LockOpen, Radar, Zap } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { Button } from '@/components/shadcn/button'
import AlertBanner from '../../ui/AlertBanner.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { executorStats, poolStats, HEALTH_THRESHOLDS } from './healthModel.js'
import { CARD_GRID, ERR_T, OK_T, WARN_T, KvList, RefreshButton, SectionToolbar, SysCard } from './HealthParts.jsx'

// Birimler sözlükten (QA 2026-09-12, ISSUE-010): "3.1 sn" / "1 dk" İngilizce arayüze sızıyordu.
function fmsDuration(ms, t) {
  if (!ms || ms <= 0) return '—'
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} ${t('chg.unitSec')}`
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms % 60000) / 1000)
  return `${m} ${t('chg.unitMin')} ${s} ${t('chg.unitSec')}`
}

const Tick = () => <Check size={14} className="text-success" strokeWidth={3} aria-hidden="true" />
const Busy = () => <Spinner size={14} inline decorative />

/**
 * Zamanlayıcı ve yürütücüler: zamanlayıcı (Şimdi çalıştır), dağıtık kilit, görev yürütücüsü (kuyruk + thread çubukları,
 * caller-runs uyarısı), DB bağlantı havuzu (çubuk), sertifika taraması. Havuz/kuyruk kartlarını tazeleyen düğme araç satırında.
 */
export default function SchedulerSection({
  t, health, isAdmin, poolLastRefreshed, poolRefreshing, onRefreshPool,
  triggering, onForceRun, releasing, onForceRelease,
}) {
  const { scheduler, lock, executor_pool: executorPool, pool, scan, scan_alarm: scanAlarm, db_ms: dbMs } = health || {}
  const isRunning = !!scheduler?.running
  const ex = executorStats(executorPool)
  const pl = poolStats(pool, dbMs)
  const dbMsTone = dbMs == null ? undefined : dbMs < 0 ? ERR_T : dbMs >= HEALTH_THRESHOLDS.dbMs.crit ? ERR_T : dbMs >= HEALTH_THRESHOLDS.dbMs.warn ? WARN_T : OK_T
  const mono = 'font-mono'
  const monoSm = 'font-mono text-xs'
  const updated = poolLastRefreshed
    ? t('sys.poolUpdatedAt').replace('{t}', poolLastRefreshed.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }))
    : null

  return (
    <div className="flex flex-col gap-3">
      <SectionToolbar left={<>{updated && <span className="tabular-nums">{updated} · </span>}{t('health.queueTuneHint')}</>}>
        <RefreshButton onClick={onRefreshPool} busy={poolRefreshing} label={t('sys.poolRefresh')} />
      </SectionToolbar>
      <div className={CARD_GRID}>
        {/* Zamanlayıcı */}
        <SysCard icon={Clock} cardKey="scheduler" iconClass={isRunning ? 'text-violet-500 animate-pulse motion-reduce:animate-none' : 'text-muted-foreground'}
          title={t('sys.schedulerTitle')}
          right={<ToneBadge tone={isRunning ? 'info' : 'muted'} data-kind={isRunning ? 'running' : 'idle'} className="font-semibold">{isRunning ? t('sys.running') : t('sys.idle')}</ToneBadge>}>
          <KvList>
            <dt>{t('sys.lastRun')}</dt>
            <dd>{scheduler?.last_run ? formatDate(scheduler.last_run) : t('sys.never')}</dd>
            <dt>{t('sys.nextRun')}</dt>
            <dd>{scheduler?.next_run ? formatDate(scheduler.next_run) : '—'}</dd>
            <dt>{isRunning ? t('sys.currentRunId') : t('sys.lastRunId')}</dt>
            <dd className={mono}>{isRunning ? (scheduler?.current_run_id || '—') : (scheduler?.last_run_id || '—')}</dd>
            <dt>{t('sys.activeDomains')}</dt>
            <dd>{scheduler?.active_domains ?? '—'}</dd>
          </KvList>
          {isAdmin && (
            <Button type="button" className="h-10 self-start sm:h-9" disabled={isRunning || triggering} onClick={onForceRun} aria-busy={triggering || undefined}>
              {triggering ? t('sys.triggering') : t('sys.forceRun')}
            </Button>
          )}
        </SysCard>

        {/* Dağıtık kilit */}
        <SysCard icon={lock?.held ? Lock : LockOpen} cardKey="lock" iconClass={lock?.held ? ERR_T : OK_T} title={t('sys.lockTitle')}
          right={<ToneBadge tone={lock?.held ? 'danger' : 'success'} data-kind={lock?.held ? 'locked' : 'free'} className="font-semibold">{lock?.held ? t('sys.locked') : t('sys.free')}</ToneBadge>}>
          {lock?.held ? (
            <>
              <KvList>
                <dt>{t('sys.lockedBy')}</dt><dd className={monoSm}>{lock.locked_by}</dd>
                <dt>{t('sys.lockedUntil')}</dt><dd>{formatDate(lock.locked_until)}</dd>
                <dt>{t('sys.heldByMe')}</dt><dd>{lock.held_by_me ? t('sys.yes') : t('sys.no')}</dd>
              </KvList>
              {!lock.held_by_me && isAdmin && (
                <Button type="button" variant="destructive" className="h-10 self-start sm:h-9" disabled={releasing} onClick={onForceRelease} aria-busy={releasing || undefined}>
                  {releasing ? t('sys.releasing') : t('sys.forceRelease')}
                </Button>
              )}
            </>
          ) : <p className="text-[0.9em] text-muted-foreground">{t('sys.lockFree')}</p>}
        </SysCard>

        {/* Görev yürütücüsü */}
        {ex && (
          <SysCard icon={Zap} cardKey="executor" iconClass={ex.saturated || ex.callerRuns > 0 ? WARN_T : 'text-amber-500'} title={t('health.queueTitle')}
            alarm={ex.saturated}
            right={<ToneBadge tone={ex.queue === 0 ? 'success' : ex.saturated ? 'danger' : 'warning'} data-kind={ex.queue === 0 ? 'free' : 'locked'} className="font-semibold tabular-nums">{ex.queue}</ToneBadge>}>
            <ProgressBar value={ex.queue} max={ex.cap} size="sm" showValue label={t('health.queuePending')} tone={ex.saturated ? 'crit' : ex.queue > ex.cap * 0.5 ? 'warn' : undefined} />
            <ProgressBar value={ex.active} max={Math.max(1, ex.pool)} size="sm" showValue label={t('health.queueActive')} tone={ex.active >= ex.pool && ex.pool > 0 ? 'warn' : undefined} />
            <KvList>
              <dt>
                <HintPopover content={t('health.queueTooltip', ex.max || ex.core)}>
                  <span className="underline decoration-dotted underline-offset-2">{t('health.queuePending')}</span>
                </HintPopover>
              </dt>
              <dd><strong>{ex.queue}</strong> / {ex.cap} <span className="ml-1 inline-flex align-middle">{ex.queue === 0 ? <Tick /> : <Busy />}</span></dd>
              <dt>{t('health.queueActive')}</dt>
              <dd>{ex.active} / {ex.pool} <span className="ml-1 inline-flex align-middle">{ex.active === 0 ? <Tick /> : <Busy />}</span></dd>
              <dt>{t('health.queueThreads')}</dt>
              <dd>{t('health.queueMinMax').replace('{min}', ex.core).replace('{max}', ex.max)}</dd>
              <dt>{t('health.queueCompleted')}</dt>
              <dd className="tabular-nums">{Number(ex.completed).toLocaleString()}</dd>
              {executorPool.caller_runs != null && (
                <>
                  <dt>
                    <HintPopover content={t('health.queueCallerRunsTooltip')}>
                      <span className="underline decoration-dotted underline-offset-2">{t('health.queueCallerRuns')}</span>
                    </HintPopover>
                  </dt>
                  <dd data-hot={ex.callerRuns > 0 ? 'true' : undefined} className={ex.callerRuns > 0 ? `${ERR_T} font-bold` : undefined}>{ex.callerRuns}</dd>
                </>
              )}
              {executorPool.jvm_start_time && (<><dt>{t('health.queueSinceStart')}</dt><dd>{formatDate(executorPool.jvm_start_time)}</dd></>)}
            </KvList>
            {ex.saturated && (
              <div data-testid="queue-saturated">
                <AlertBanner tone="warning" className="mb-0">{t('health.queueSaturated')}</AlertBanner>
              </div>
            )}
            {ex.callerRuns > 0 && !ex.saturated && (
              <div data-testid="queue-caller-runs">
                <AlertBanner tone="warning" className="mb-0">{t('health.reasonCallerRuns', ex.callerRuns)}</AlertBanner>
              </div>
            )}
          </SysCard>
        )}

        {/* DB bağlantı havuzu */}
        <SysCard icon={Database} cardKey="pool" iconClass={pl ? (pl.waiting > 0 ? ERR_T : pl.pct > 80 ? WARN_T : OK_T) : 'text-muted-foreground'} title={t('sys.poolTitle')}
          right={pl && <ToneBadge tone={pl.waiting > 0 ? 'danger' : pl.pct > 80 ? 'warning' : 'success'} className="font-semibold tabular-nums">{pl.active} / {pl.max}</ToneBadge>}>
          {pl ? (
            <>
              <ProgressBar value={pl.active} max={pl.max} size="sm" showValue label={t('sys.poolActive')} tone={pl.waiting > 0 ? 'crit' : pl.pct > 80 ? 'warn' : undefined} />
              <KvList>
                <dt>{t('sys.poolActive')}</dt><dd>{pl.active}</dd>
                <dt>{t('sys.poolIdle')}</dt><dd>{pl.idle}</dd>
                <dt>{t('sys.poolTotal')}</dt><dd>{pl.total}</dd>
                <dt>{t('sys.poolWaiting')}</dt><dd className={pl.waiting > 0 ? WARN_T : undefined}>{pl.waiting}</dd>
                <dt>{t('sys.poolMax')}</dt><dd>{pl.max}</dd>
                {dbMs != null && (<><dt>{t('health.dbResponseMs')}</dt><dd className={dbMsTone}>{dbMs < 0 ? t('health.stDown') : `${dbMs} ms`}</dd></>)}
              </KvList>
            </>
          ) : <p className="text-[0.9em] text-muted-foreground">{t('sys.poolUnavailable')}</p>}
        </SysCard>

        {/* Sertifika taraması */}
        <SysCard icon={Radar} cardKey="scan" iconClass={scanAlarm ? ERR_T : OK_T} title={t('health.scanTitle')} alarm={!!scanAlarm}
          right={<ToneBadge tone={scanAlarm ? 'danger' : 'success'} className="font-semibold">{scanAlarm ? t('health.scanAlarm') : t('health.stOk')}</ToneBadge>}>
          {scanAlarm && <AlertBanner tone="danger" className="mb-0">{t('health.scanAlarm')}</AlertBanner>}
          <KvList>
            <dt>{t('health.lastScan')}</dt><dd>{scan?.last_run ? formatDate(scan.last_run) : t('sys.never')}</dd>
            <dt>{t('health.scanDuration')}</dt><dd>{fmsDuration(scan?.duration_ms, t)}</dd>
            <dt>{t('health.scanTotal')}</dt><dd>{scan?.total ?? 0}</dd>
            <dt>{t('health.scanWarn')}</dt><dd className={scan?.warnings > 0 ? WARN_T : undefined}>{scan?.warnings ?? 0}</dd>
            <dt>{t('health.scanErr')}</dt><dd className={scan?.errors > 0 ? ERR_T : undefined}>{scan?.errors ?? 0}</dd>
            {scan?.last_failure && (<><dt>{t('health.statusFailed')}</dt><dd className={`${ERR_T} break-words`}>{scan.last_failure}</dd></>)}
          </KvList>
        </SysCard>
      </div>
    </div>
  )
}
