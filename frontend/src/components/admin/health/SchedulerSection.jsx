import { Check, Clock, Crown, Database, Lock, LockOpen, Network, Radar, Zap } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { Button } from '@/components/shadcn/button'
import AlertBanner from '../../ui/AlertBanner.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { executorStats, poolStats, clusterModel, relTime, SCAN_KINDS, HEALTH_THRESHOLDS, BADGE_TONE, LOCK_STATE_TONE } from './healthModel.js'
import { CARD_GRID, ERR_T, OK_T, WARN_T, KvList, PodChip, RefreshButton, SectionToolbar, SubTitle, SysCard } from './HealthParts.jsx'
import { EvalRecorderRows, EvalVerifySection, SideJobsRows } from './ClusterRuntimeParts.jsx'

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
/** Pod adı / kimlik — sarar, kırpılmaz (uzun AD adları gibi: `[overflow-wrap:anywhere]`). */
const PodName = ({ children }) => <span className="font-mono text-xs [overflow-wrap:anywhere]">{children}</span>

/**
 * Küme kartı (2026-10-23, çok pod R2b, N1): tarama lideri (kira satırı — KÜME, hangi pod okursa okusun aynı) + yanıtlayan pod'un
 * kendi durumu (lider mi, son tarama tıkı, tıkanma, tuttuğu kilitler, tur kaydı hataları, atlanan turlar, önbellek yolu —
 * BU POD; diğer pod'lar kendi sayaçlarını tutar). RUNBOOK / dağıtım listesi operatörü "tek lider" doğrulaması için buraya
 * gönderir. Kira okunamadıysa "bilinmiyor" — asla yeşil değil. Çok pod Sürüm 3-1 (paket B): kalıcı değerlendirme durumunun
 * KÜME doğrulaması (ayrı bölüm, "Küme") ve bu pod'un kaydedicisi + yan işleri ("Bu pod" bölümünde); eski sunucu alanları
 * göndermezse satırlar gizlenir.
 */
function ClusterCard({ t, health }) {
  const c = clusterModel(health)
  const fmtWhen = (iso) => (iso ? `${formatDate(iso)} · ${relTime(iso, t) || ''}` : '—')
  const badge = !c.available
    ? <ToneBadge tone="muted" data-kind="single" className="font-semibold">{t('hmp.single')}</ToneBadge>
    : !c.leader.known
      ? <ToneBadge tone="muted" data-kind="unknown" className="font-semibold">{t('health.stUnknown')}</ToneBadge>
      : c.leader.leaderless
        ? <ToneBadge tone="warning" data-kind="leaderless" className="font-semibold">{t('hmp.leaderless')}</ToneBadge>
        : <ToneBadge tone={BADGE_TONE[c.level] || 'muted'} data-kind="leader" className="font-semibold">{t('hmp.leaderOk')}</ToneBadge>
  return (
    <SysCard icon={Network} cardKey="cluster" iconClass={c.level === 'warn' ? WARN_T : c.level === 'ok' ? OK_T : 'text-muted-foreground'}
      title={t('hmp.clusterTitle')} right={badge}>
      {!c.available ? (
        <div className="flex flex-col gap-3">
          <p className="text-[0.9em] text-muted-foreground" data-slot="cluster-single">{t('hmp.singleHint')}</p>
          <EvalVerifySection t={t} es={c.evalState} />
          {(c.evalState || c.sideJobs) && (
            <section data-slot="cluster-this-pod" aria-labelledby="hmp-pod-title-single" className="flex flex-col gap-1.5 border-t pt-2.5">
              <SubTitle className="mt-0 text-[12px]"><span id="hmp-pod-title-single">{t('hmp.podSection')}</span><PodChip t={t} pod={c.pod.name} /></SubTitle>
              <KvList>
                <EvalRecorderRows t={t} es={c.evalState} />
                <SideJobsRows t={t} sj={c.sideJobs} />
              </KvList>
            </section>
          )}
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <section data-slot="cluster-leader" aria-labelledby="hmp-leader-title" className="flex flex-col gap-1.5">
            <SubTitle className="mt-0 text-[12px]"><span id="hmp-leader-title">{t('hmp.leaderSection')}</span><PodChip t={t} scope="cluster" /></SubTitle>
            {!c.leader.known ? (
              <p className="text-[0.9em] text-muted-foreground" data-slot="cluster-leader-unknown">{t('hmp.leaderUnknown')}</p>
            ) : (
              <KvList>
                <dt>{t('hmp.leaderPod')}</dt>
                <dd data-slot="cluster-leader-pod">{c.leader.owner ? <PodName>{c.leader.ownerPod}</PodName> : <span className={WARN_T}>{t('hmp.noLeader')}</span>}</dd>
                <dt>{t('hmp.leaseUntil')}</dt>
                <dd>{c.leader.until ? formatDate(c.leader.until) : '—'}
                  {c.leader.expired && <span className={`${WARN_T} ml-1`} data-slot="cluster-lease-expired">· {t('hmp.leaseExpired')}</span>}</dd>
              </KvList>
            )}
            {c.leader.leaderless && <AlertBanner tone="warning" className="mb-0">{t('hmp.leaderlessHint')}</AlertBanner>}
          </section>

          <EvalVerifySection t={t} es={c.evalState} />

          <section data-slot="cluster-this-pod" aria-labelledby="hmp-pod-title" className="flex flex-col gap-1.5 border-t pt-2.5">
            <SubTitle className="mt-0 text-[12px]"><span id="hmp-pod-title">{t('hmp.podSection')}</span><PodChip t={t} pod={c.pod.name} /></SubTitle>
            <KvList>
              <dt>{t('hmp.isLeader')}</dt>
              <dd data-slot="cluster-is-leader" data-value={c.thisPod.isLeader == null ? 'unknown' : String(c.thisPod.isLeader)}>
                {c.thisPod.isLeader == null ? t('health.stUnknown') : c.thisPod.isLeader
                  ? <span className="inline-flex items-center gap-1 font-semibold"><Crown size={14} aria-hidden="true" className="text-primary" />{t('sys.yes')}</span>
                  : t('sys.no')}
              </dd>
              <dt>{t('hmp.lastTick')}</dt>
              <dd>{c.thisPod.lastTick ? fmtWhen(c.thisPod.lastTick) : t('hmp.noTick')}</dd>
              {c.thisPod.wedgeYieldedAt && (<><dt>{t('hmp.wedge')}</dt><dd className={WARN_T}>{fmtWhen(c.thisPod.wedgeYieldedAt)}</dd></>)}
              <dt>{t('hmp.heldLocks')}</dt>
              <dd data-slot="cluster-held-locks">
                {c.thisPod.heldLocks == null ? '—' : c.thisPod.heldLocks.length === 0 ? t('hmp.none') : (
                  <ul className="flex list-none flex-col gap-0.5">
                    {c.thisPod.heldLocks.map((l) => (
                      <li key={l.name} data-slot="cluster-held-lock" data-state={l.state} className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 [overflow-wrap:anywhere]">
                        <span className="font-mono text-xs">{l.name}</span>
                        <ToneBadge tone={LOCK_STATE_TONE[l.state] || 'muted'} className="font-semibold">
                          {LOCK_STATE_TONE[l.state] ? t(`hmp.lockState.${l.state}`) : t('health.stUnknown')}
                        </ToneBadge>
                        {l.until && <span className="text-muted-foreground">{formatDate(l.until)}</span>}
                      </li>
                    ))}
                  </ul>
                )}
              </dd>
              <dt>{t('hmp.roundFailures')}</dt>
              <dd data-slot="cluster-round-failures" className={Number(c.thisPod.roundClaimFailures) > 0 ? WARN_T : undefined}>
                {c.thisPod.roundClaimFailures ?? '—'}
                {c.thisPod.lastRoundClaimFailure && <span className="text-muted-foreground"> · {t('hmp.lastAt', formatDate(c.thisPod.lastRoundClaimFailure))}</span>}
              </dd>
              <dt>{t('hmp.skippedRounds')}</dt>
              <dd data-slot="cluster-skipped-rounds">
                {c.thisPod.skippedRounds == null ? '—' : c.thisPod.skippedRounds.length === 0 ? t('hmp.none') : (
                  <ul className={`flex list-none flex-col gap-0.5 ${WARN_T}`}>
                    {c.thisPod.skippedRounds.slice(-5).reverse().map((r, i) => (
                      <li key={`${r.round}-${r.at}-${i}`} className="[overflow-wrap:anywhere]"><span className="font-mono text-xs">{r.round}</span> · {formatDate(r.at)}</li>
                    ))}
                  </ul>
                )}
              </dd>
              {c.cacheBus && (
                <>
                  <dt>{t('hmp.cacheBus')}</dt>
                  <dd data-slot="cluster-cache-bus" data-level={c.cacheLevel}>
                    {c.cacheBus.error ? t('health.stUnknown') : (
                      <span className="flex flex-col gap-0.5">
                        <span className={c.cacheLevel === 'warn' ? WARN_T : undefined}>
                          {c.cacheBus.enabled === false ? t('health.stOff') : c.cacheBus.table_missing ? t('hmp.cacheTableMissing')
                            : !c.cacheBus.running ? t('hmp.cacheStopped')
                              : c.cacheStale === true ? t('hmp.cachePollStale')
                                : c.cacheStale == null ? t('hmp.cacheNoPollYet') : t('hmp.cacheRunning')}
                        </span>
                        <span className="text-muted-foreground">
                          {t('hmp.cacheGen', c.cacheBus.applied_generation ?? '—')}
                          {c.cacheBus.last_poll_ok_at ? ` · ${t('hmp.cacheLastPoll', formatDate(c.cacheBus.last_poll_ok_at))}` : ''}
                          {Number(c.cacheBus.poll_failures) > 0 ? ` · ${t('hmp.cachePollFailures', c.cacheBus.poll_failures)}` : ''}
                        </span>
                      </span>
                    )}
                  </dd>
                </>
              )}
              <EvalRecorderRows t={t} es={c.evalState} />
              <SideJobsRows t={t} sj={c.sideJobs} />
            </KvList>
            <p className="text-xs text-muted-foreground" data-slot="cluster-per-pod-note">{t('hmp.perPodNote')}</p>
          </section>
        </div>
      )}
    </SysCard>
  )
}

/**
 * Zamanlayıcı ve yürütücüler: küme kartı, zamanlayıcı (Şimdi çalıştır), dağıtık kilit, görev yürütücüsü (kuyruk + thread
 * çubukları, caller-runs uyarısı), DB bağlantı havuzu (çubuk), sertifika taraması. Zamanlayıcı / kilit / tarama KÜME
 * (veritabanı); yürütücü ve havuz BU POD — kartlar etiketli (2026-10-23, çok pod R2b, MP-34 / MP-37a).
 */
export default function SchedulerSection({
  t, health, isAdmin, poolLastRefreshed, poolRefreshing, onRefreshPool,
  triggering, onForceRun, releasing, onForceRelease,
}) {
  const { scheduler, lock, executor_pool: executorPool, pool, scan, scan_alarm: scanAlarm, db_ms: dbMs } = health || {}
  const servedPod = health?.served_by_pod || health?.served_by || scheduler?.instance_id || null
  const isRunning = !!scheduler?.running
  const runningElsewhere = isRunning && scheduler?.running_here === false
  // UIA-D5 (20.165.1): kilit satırı okunamadıysa sunucu `{held:false, error}` döner — "Serbest" yeşili değil, bilinmiyor.
  const lockUnknown = !!lock?.error
  const ex = executorStats(executorPool)
  const pl = poolStats(pool, dbMs)
  const dbMsTone = dbMs == null ? undefined : dbMs < 0 ? ERR_T : dbMs >= HEALTH_THRESHOLDS.dbMs.crit ? ERR_T : dbMs >= HEALTH_THRESHOLDS.dbMs.warn ? WARN_T : OK_T
  const mono = 'font-mono'
  const monoSm = 'font-mono text-xs'
  const scanLocal = scan?.source === 'local'
  const runs = scan?.runs || {}
  const updated = poolLastRefreshed
    ? t('sys.poolUpdatedAt').replace('{t}', poolLastRefreshed.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }))
    : null

  return (
    <div className="flex flex-col gap-3">
      <SectionToolbar left={<>{updated && <span className="tabular-nums">{updated} · </span>}{t('health.queueTuneHint')}</>}>
        <RefreshButton onClick={onRefreshPool} busy={poolRefreshing} label={t('sys.poolRefresh')} />
      </SectionToolbar>
      <ClusterCard t={t} health={health} />
      <div className={CARD_GRID}>
        {/* Zamanlayıcı — KÜME (canlı cert-check kilidi + scan_runs) */}
        <SysCard icon={Clock} cardKey="scheduler" iconClass={isRunning ? 'text-violet-500 animate-pulse motion-reduce:animate-none' : 'text-muted-foreground'}
          title={t('sys.schedulerTitle')}
          right={<ToneBadge tone={isRunning ? 'info' : 'muted'} data-kind={isRunning ? 'running' : 'idle'} className="font-semibold">{isRunning ? t('sys.running') : t('sys.idle')}</ToneBadge>}>
          {/* Küme değeri yalnız scan_runs okunduysa; okunamadıysa son koşu bu pod'un belleği (inceleme H2) */}
          {scheduler?.status_source === 'local' ? <PodChip t={t} pod={servedPod} /> : <PodChip t={t} scope="cluster" />}
          <KvList>
            {isRunning && scheduler?.running_on && (
              <>
                <dt>{t('hmp.runningOn')}</dt>
                <dd data-slot="sched-running-on"><PodName>{scheduler.running_on_pod || scheduler.running_on}</PodName></dd>
              </>
            )}
            <dt>{t('sys.lastRun')}</dt>
            <dd>{scheduler?.last_run ? formatDate(scheduler.last_run) : t('sys.never')}
              {scheduler?.last_run_by_pod && <span className="text-muted-foreground"> · <PodName>{scheduler.last_run_by_pod}</PodName></span>}</dd>
            <dt>{t('sys.nextRun')}</dt>
            <dd>{scheduler?.next_run ? formatDate(scheduler.next_run) : '—'}</dd>
            <dt>{isRunning && !runningElsewhere ? t('sys.currentRunId') : t('sys.lastRunId')}</dt>
            <dd className={mono}>{isRunning && !runningElsewhere ? (scheduler?.current_run_id || '—') : (scheduler?.last_run_id || '—')}</dd>
            <dt>{t('sys.activeDomains')}</dt>
            <dd>{scheduler?.active_domains ?? '—'}</dd>
          </KvList>
          {runningElsewhere && <p className="text-xs text-muted-foreground" data-slot="sched-elsewhere">{t('hmp.runningElsewhereHint')}</p>}
          {isAdmin && (
            <Button type="button" className="h-10 self-start sm:h-9 pointer-coarse:h-10" disabled={isRunning || triggering} onClick={onForceRun} aria-busy={triggering || undefined}>
              {triggering ? t('sys.triggering') : t('sys.forceRun')}
            </Button>
          )}
        </SysCard>

        {/* Dağıtık kilit — KÜME (satır + süresi) */}
        <SysCard icon={lock?.held ? Lock : LockOpen} cardKey="lock" iconClass={lockUnknown ? 'text-muted-foreground' : lock?.held ? ERR_T : OK_T} title={t('sys.lockTitle')}
          right={lockUnknown
            ? <ToneBadge tone="muted" data-kind="unknown" className="font-semibold">{t('health.stUnknown')}</ToneBadge>
            : <ToneBadge tone={lock?.held ? 'danger' : 'success'} data-kind={lock?.held ? 'locked' : 'free'} className="font-semibold">{lock?.held ? t('sys.locked') : t('sys.free')}</ToneBadge>}>
          <PodChip t={t} scope="cluster" />
          {lockUnknown ? (
            <p className="text-[0.9em] text-muted-foreground" data-slot="lock-unavailable">{t('health.statsUnavailable')}</p>
          ) : lock?.held ? (
            <>
              <KvList>
                <dt>{t('sys.lockedBy')}</dt><dd><PodName>{lock.locked_by_pod || lock.locked_by}</PodName></dd>
                <dt>{t('sys.lockedUntil')}</dt><dd>{formatDate(lock.locked_until)}</dd>
                <dt>{t('hmp.heldByServingPod')}</dt><dd>{lock.held_by_me ? t('sys.yes') : t('sys.no')}</dd>
              </KvList>
              {!lock.held_by_me && isAdmin && (
                <Button type="button" variant="destructive" className="h-10 self-start sm:h-9 pointer-coarse:h-10" disabled={releasing} onClick={onForceRelease} aria-busy={releasing || undefined}>
                  {releasing ? t('sys.releasing') : t('sys.forceRelease')}
                </Button>
              )}
            </>
          ) : lock?.expired ? (
            <div data-slot="lock-expired" className="flex flex-col gap-1.5">
              <p className="text-[0.9em] text-muted-foreground">{t('hmp.lockExpired')}</p>
              <KvList>
                <dt>{t('sys.lockedBy')}</dt><dd><PodName>{lock.locked_by_pod || lock.locked_by || '—'}</PodName></dd>
                <dt>{t('sys.lockedUntil')}</dt><dd>{lock.locked_until ? formatDate(lock.locked_until) : '—'}</dd>
              </KvList>
            </div>
          ) : <p className="text-[0.9em] text-muted-foreground">{t('sys.lockFree')}</p>}
        </SysCard>

        {/* Görev yürütücüsü — BU POD */}
        {ex && (
          <SysCard icon={Zap} cardKey="executor" iconClass={ex.saturated || ex.callerRuns > 0 ? WARN_T : 'text-amber-500'} title={t('health.queueTitle')}
            alarm={ex.saturated}
            right={<ToneBadge tone={ex.queue === 0 ? 'success' : ex.saturated ? 'danger' : 'warning'} data-kind={ex.queue === 0 ? 'free' : 'locked'} className="font-semibold tabular-nums">{ex.queue}</ToneBadge>}>
            <PodChip t={t} pod={servedPod} />
            <ProgressBar value={ex.queue} max={ex.cap} size="sm" showValue label={t('health.queuePending')} tone={ex.saturated ? 'crit' : ex.queue > ex.cap * 0.5 ? 'warn' : undefined} />
            <ProgressBar value={ex.active} max={Math.max(1, ex.pool)} size="sm" showValue label={t('health.queueActive')} tone={ex.active >= ex.pool && ex.pool > 0 ? 'warn' : undefined} />
            <KvList>
              <dt>
                <HintPopover content={t('health.queueTooltip', ex.max || ex.core)} triggerClassName="pointer-coarse:min-h-10">
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
                    <HintPopover content={t('health.queueCallerRunsTooltip')} triggerClassName="pointer-coarse:min-h-10">
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

        {/* DB bağlantı havuzu — BU POD */}
        <SysCard icon={Database} cardKey="pool" iconClass={pl ? (pl.waiting > 0 ? ERR_T : pl.pct > 80 ? WARN_T : OK_T) : 'text-muted-foreground'} title={t('sys.poolTitle')}
          right={pl && <ToneBadge tone={pl.waiting > 0 ? 'danger' : pl.pct > 80 ? 'warning' : 'success'} className="font-semibold tabular-nums">{pl.active} / {pl.max}</ToneBadge>}>
          <PodChip t={t} pod={servedPod} />
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

        {/* Sertifika taraması — KÜME (scan_runs; tablo okunamazsa bu pod'un belleği) */}
        <SysCard icon={Radar} cardKey="scan" iconClass={scanAlarm ? ERR_T : scanLocal ? 'text-muted-foreground' : OK_T} title={t('health.scanTitle')} alarm={!!scanAlarm}
          right={scanAlarm ? <ToneBadge tone="danger" className="font-semibold">{t('health.scanAlarm')}</ToneBadge>
            // Küme kaydı okunamadıysa "Tamam" denmez — bu pod'un belleği kümeyi söylemez (inceleme H2: unknown ≠ OK)
            : scanLocal ? <ToneBadge tone="muted" data-kind="unknown" className="font-semibold">{t('health.stUnknown')}</ToneBadge>
              : <ToneBadge tone="success" className="font-semibold">{t('health.stOk')}</ToneBadge>}>
          {scanLocal ? <PodChip t={t} pod={servedPod} /> : <PodChip t={t} scope="cluster" />}
          {scanAlarm && <AlertBanner tone="danger" className="mb-0">{t('health.scanAlarm')}</AlertBanner>}
          {scanLocal && <p className="text-xs text-muted-foreground" data-slot="scan-local">{t('hmp.scanLocal')}</p>}
          <KvList>
            <dt>{t('health.lastScan')}</dt>
            <dd>{scan?.last_run ? formatDate(scan.last_run) : t('sys.never')}
              {scan?.last_run_kind && <span className="text-muted-foreground"> · {t(`hmp.kind.${scan.last_run_kind}`)}</span>}
              {scan?.last_run_by_pod && <span className="text-muted-foreground"> · <PodName>{scan.last_run_by_pod}</PodName></span>}</dd>
            <dt>{t('health.scanDuration')}</dt><dd>{fmsDuration(scan?.duration_ms, t)}</dd>
            <dt>{t('health.scanTotal')}</dt><dd>{scan?.total ?? 0}</dd>
            <dt>{t('health.scanWarn')}</dt><dd className={scan?.warnings > 0 ? WARN_T : undefined}>{scan?.warnings ?? 0}</dd>
            <dt>{t('health.scanErr')}</dt><dd className={scan?.errors > 0 ? ERR_T : undefined}>{scan?.errors ?? 0}</dd>
            {scan?.last_failure && (<><dt>{t('health.statusFailed')}</dt><dd className={`${ERR_T} break-words`}>{scan.last_failure}
              {scan.last_failure_by_pod && <span className="text-muted-foreground"> · <PodName>{scan.last_failure_by_pod}</PodName></span>}</dd></>)}
          </KvList>
          {SCAN_KINDS.some((k) => runs[k]) && (
            <div className="border-t pt-2" data-slot="scan-runs">
              <div className="mb-1 text-[0.72em] font-bold tracking-wide text-muted-foreground uppercase">{t('hmp.runsTitle')}</div>
              <ul className="flex list-none flex-col gap-1 text-[0.86em]">
                {SCAN_KINDS.filter((k) => runs[k]).map((k) => {
                  const r = runs[k]
                  return (
                    <li key={k} data-slot="scan-run" data-kind={k} className="flex flex-wrap items-baseline gap-x-1.5 [overflow-wrap:anywhere]">
                      <span className="font-semibold">{t(`hmp.kind.${k}`)}</span>
                      <span>{r.last_run ? formatDate(r.last_run) : t('sys.never')}</span>
                      {r.total != null && <span className="text-muted-foreground">· {t('hmp.runTotal', r.total)}</span>}
                      {r.by_pod && <span className="text-muted-foreground">· <PodName>{r.by_pod}</PodName></span>}
                      {r.failure && r.failure_at === r.finished_at && <span className={ERR_T}>· {t('hmp.runEndedWithIssue')}</span>}
                    </li>
                  )
                })}
              </ul>
            </div>
          )}
        </SysCard>
      </div>
    </div>
  )
}
