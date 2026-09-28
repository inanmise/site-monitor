import { forwardRef, useId } from 'react'
import { AlertTriangle, CheckCircle2, HeartPulse, Lock, Network, Timer } from 'lucide-react'
import { Card } from '@/components/shadcn/card'
import { ProgressBar } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { formatDurationShort, poolStats, relTime } from '../health/healthModel.js'
import { cn } from '@/lib/utils'
import { LevelBadge, SectionHeading, Stat } from './DbParts.jsx'
import { cacheTone, num, pct, respTone, ROLLBACK_PCT } from './dbModel.js'

const PROGRESS_TONE = { danger: 'crit', warning: 'warn', success: 'ok' }

/**
 * Bağlantı durumu dağılımı — segment çubuğu (2 px aralıklı dolgular) + METİNLİ gösterge (renk tek başına anlam
 * taşımaz). Okunamadıysa açıkça "bilinmiyor" (boş bir çubuk "hiç bağlantı yok" gibi okunurdu).
 */
function StateBar({ t, states }) {
  if (!states) {
    return <p data-testid="db-states-unknown" className="m-0 text-xs text-muted-foreground">{t('dba.statesUnknown')}</p>
  }
  const summary = states.map((s) => `${t(s.label)} ${s.count}`).join(', ')
  return (
    <div className="flex flex-col gap-2" data-testid="db-states">
      <div role="img" aria-label={t('dba.statesAria', summary)} className="flex h-3 w-full gap-0.5 overflow-hidden rounded-full">
        {states.map((s) => (
          <span key={s.state} data-state-seg={s.state} className={cn('h-full min-w-1 first:rounded-l-full last:rounded-r-full', s.dot)}
            style={{ flexGrow: s.count, flexBasis: 0 }} />
        ))}
      </div>
      <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0 text-xs">
        {states.map((s) => (
          <li key={s.state} className="flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-full', s.dot)} />
            <span className="text-muted-foreground">{t(s.label)}</span>
            <span className="font-semibold tabular-nums">{num(s.count)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Uyarı satırı (ikon + metin) — tonu rozet değil ikon/metin rengi; kart içinde bant kalabalığı yapmaz. */
function Signal({ tone, icon: Icon, children, testId }) {
  return (
    <li data-testid={testId} data-tone={tone}
      className={cn('flex items-start gap-2 text-sm', tone === 'warning' && 'text-amber-800 dark:text-amber-200', tone === 'success' && 'text-muted-foreground')}>
      <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', tone === 'warning' && 'text-amber-600 dark:text-amber-400', tone === 'success' && 'text-success')} />
      <span className="min-w-0">{children}</span>
    </li>
  )
}

/**
 * "Bağlantılar ve sağlık" kartı. Sol: sunucu bağlantıları (active / max_connections doluluğu, durum dağılımı, uzun
 * süren sorgu · işlem içinde boşta · kilit bekleme sinyalleri) + uygulamanın HikariCP havuzu (Sistem Sağlığı'ndan).
 * Sağ: yanıt süresi, önbellek isabeti, commit/rollback, deadlock, geçici dosyalar, istatistik başlangıcı.
 * Bilinmeyen her değer "Bilinmiyor" yazar. KPI'lardan odaklanabilsin diye başlık `tabIndex=-1` (ref başlığa).
 */
const DbHealthCard = forwardRef(function DbHealthCard({ t, conn, stats, appPool, dbSize, tableCount }, headingRef) {
  const titleId = useId()
  const pool = poolStats(appPool, null)
  const dur = (s) => formatDurationShort(s, t)
  const statesKnown = !!conn.states
  const signals = []
  if (statesKnown) {
    if (Number(conn.longQueries) > 0) {
      signals.push(<Signal key="long" tone="warning" icon={Timer} testId="db-signal-long">
        {t('dba.warnLong', num(conn.longQueries), conn.longThreshold, dur(conn.longestQuery))}</Signal>)
    }
    if (Number(conn.idleInTx) > 0) {
      signals.push(<Signal key="idle" tone="warning" icon={AlertTriangle} testId="db-signal-idletx">
        {t('dba.warnIdleTx', num(conn.idleInTx), dur(conn.longestXact))}</Signal>)
    }
    if (Number(conn.lockWaits) > 0) {
      signals.push(<Signal key="lock" tone="warning" icon={Lock} testId="db-signal-locks">{t('dba.warnLocks', num(conn.lockWaits))}</Signal>)
    }
    if (!signals.length) signals.push(<Signal key="ok" tone="success" icon={CheckCircle2} testId="db-signal-clear">{t('dba.allClear')}</Signal>)
  }
  const cTone = cacheTone(stats?.cache_hit_pct)
  const rTone = respTone(conn.resp)
  const rollbackHigh = stats?.rollback_pct != null && stats.rollback_pct >= ROLLBACK_PCT
  const unknown = t('dba.unknown')
  const reset = stats?.stats_reset ? relTime(stats.stats_reset, t) : null

  return (
    <Card role="region" data-testid="db-health" aria-labelledby={titleId} className="scroll-mt-36 gap-4 px-3.5 py-4 shadow-xs sm:px-5 md:scroll-mt-4">
      <SectionHeading id={titleId} icon={HeartPulse} title={t('dba.healthTitle')} ref={headingRef} />
      <div className="grid min-w-0 grid-cols-1 gap-5 @4xl:grid-cols-2">
        {/* Bağlantılar */}
        <section className="flex min-w-0 flex-col gap-3" data-testid="db-conn-detail">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h5 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><Network aria-hidden="true" className="size-4 text-muted-foreground" />{t('dba.connServer')}</h5>
            {conn.pct != null
              ? <ToneBadge tone={conn.tone} className="font-semibold">{t('db.kpiUsage', conn.pct)}</ToneBadge>
              : <ToneBadge tone="muted">{unknown}</ToneBadge>}
          </div>
          <div className="flex items-baseline gap-2">
            <span className="text-2xl font-extrabold tracking-tight tabular-nums">{num(conn.active)}</span>
            <span className="text-sm text-muted-foreground">/ {num(conn.max)} {t('dba.maxConnections')}</span>
          </div>
          {conn.pct != null && (
            <ProgressBar value={Number(conn.active)} max={Number(conn.max)} size="sm" label={t('db.connUsage')} tone={PROGRESS_TONE[conn.tone]} />
          )}
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-semibold text-muted-foreground">{t('dba.states')}</span>
            <StateBar t={t} states={conn.states} />
          </div>
          {signals.length > 0 && <ul className="m-0 flex list-none flex-col gap-1.5 p-0">{signals}</ul>}
          {pool && (
            <div className="flex flex-col gap-1.5 rounded-lg border border-border bg-muted/30 px-3 py-2.5" data-testid="db-app-pool">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="text-xs font-semibold text-muted-foreground">{t('dba.appPool')}</span>
                {pool.waiting > 0 && <ToneBadge tone="warning">{t('dba.poolWaiting', pool.waiting)}</ToneBadge>}
              </div>
              <span className="text-sm tabular-nums">{t('dba.appPoolLine', pool.active, pool.idle, pool.waiting, pool.max)}</span>
              {pool.max > 0 && <ProgressBar value={pool.active} max={pool.max} size="sm" decorative tone={pool.waiting > 0 ? 'crit' : pool.pct >= 80 ? 'warn' : 'ok'} />}
            </div>
          )}
        </section>

        {/* Veritabanı sağlığı */}
        <section className="flex min-w-0 flex-col gap-3">
          <h5 className="m-0 flex items-center gap-1.5 text-sm font-semibold"><HeartPulse aria-hidden="true" className="size-4 text-muted-foreground" />{t('dba.dbHealth')}</h5>
          {!stats && <p data-testid="db-stats-unknown" className="m-0 text-xs text-muted-foreground">{t('dba.statsUnknown')}</p>}
          <div className="grid grid-cols-1 gap-2 min-[420px]:grid-cols-2">
            <Stat label={t('db.connResp')} unknownLabel={unknown} testId="db-stat-resp"
              value={conn.resp == null ? null : conn.resp < 0 ? t('dba.respFailed') : `${num(conn.resp)} ms`}
              aside={conn.resp != null ? <LevelBadge t={t} tone={rTone} /> : null} />
            <Stat label={t('dba.kpiCache')} unknownLabel={unknown} testId="db-stat-cache"
              value={stats?.cache_hit_pct != null ? pct(stats.cache_hit_pct) : null}
              aside={stats?.cache_hit_pct != null ? <LevelBadge t={t} tone={cTone} /> : null}
              sub={stats ? t('dba.cacheBlocks', num(stats.blks_hit), num(stats.blks_read)) : null} />
            <Stat label={t('dba.commits')} unknownLabel={unknown} value={stats ? num(stats.xact_commit) : null} />
            <Stat label={t('dba.rollbacks')} unknownLabel={unknown} testId="db-stat-rollback"
              value={stats ? `${num(stats.xact_rollback)}${stats.rollback_pct != null ? ` (${pct(stats.rollback_pct)})` : ''}` : null}
              aside={rollbackHigh ? <ToneBadge tone="warning">{t('dba.lvlWatch')}</ToneBadge> : null} />
            <Stat label={t('dba.deadlocks')} unknownLabel={unknown} value={stats ? num(stats.deadlocks) : null}
              aside={stats && Number(stats.deadlocks) > 0 ? <ToneBadge tone="warning">{t('dba.lvlWatch')}</ToneBadge> : null} />
            <Stat label={t('dba.tempFiles')} unknownLabel={unknown}
              value={stats ? t('dba.tempFilesVal', num(stats.temp_files), stats.temp_size || '0 bytes') : null} />
            <Stat label={t('db.connSize')} unknownLabel={unknown} value={dbSize || null}
              sub={tableCount != null ? t('db.kpiTables', num(tableCount)) : null} />
            <Stat label={t('dba.statsSince')} unknownLabel={unknown}
              value={reset ?? (stats ? t('dba.statsNeverReset') : null)} />
          </div>
          <p className="m-0 text-xs text-muted-foreground">{t('dba.statsNote')}</p>
        </section>
      </div>
    </Card>
  )
})

export default DbHealthCard
