import { Cpu, MemoryStick, Package } from 'lucide-react'
import { formatDate } from '../../../api/client'
import { formatPercent } from '../../../i18n/dateLocale.js'
import { ProgressBar } from '../../ui/Progress.jsx'
import MiniChart from '../MiniChart'
import ToneBadge from '../ToneBadge.jsx'
import { EnvBadge } from '../releases/DeployBadges.jsx'
import { HEALTH_THRESHOLDS, MEMORY_PCT, formatDurationShort } from './healthModel.js'
import { CARD_GRID, CHART_GRID, ERR_T, OK_T, WARN_T, KvList, SectionError, SubTitle, SysCard } from './HealthParts.jsx'

/**
 * Uygulama ve JVM bölümü: sürüm/örnek kartı, JVM bellek kartı ve son 24 saatin CPU / heap / thread grafikleri
 * (MiniChart → tıklanınca ChartModal). Metrik ucu düşerse grafiklerin yerinde hata bloğu + "Tekrar dene".
 */
export default function SystemSection({ t, health, metrics, metricsError, onRetry, onOpenChart }) {
  const { build, memory, scheduler } = health || {}
  // Oturum deposu (2026-10-09): jdbc = veritabanı (pod yeniden başlayınca oturum sürer), memory = pod belleği
  const sessionStore = health?.session_store
  const memLevel = !memory ? 'ok' : memory.used_pct >= MEMORY_PCT.crit ? 'crit' : memory.used_pct >= MEMORY_PCT.warn ? 'warn' : undefined
  const memTone = memLevel === 'crit' ? ERR_T : memLevel === 'warn' ? WARN_T : OK_T
  const mono = 'font-mono'
  const monoSm = 'font-mono text-xs'
  const chart = (label, key, unit, color, thresholds, maxY) => {
    const data = (metrics || []).map((p) => ({ ts: p.ts, value: p[key] }))
    return (
      <MiniChart label={label} unit={unit} color={color} maxY={maxY} data={data}
        thresholds={thresholds} breachLabel={thresholds ? t('sys.breach') : null}
        onClick={() => onOpenChart({ label, unit, color, maxY, data })} />
    )
  }
  return (
    <div className="flex flex-col gap-4">
      <div className={CARD_GRID}>
        <SysCard icon={Package} iconClass="text-primary" title={t('health.buildTitle')} cardKey="build"
          right={build?.environment && <EnvBadge env={build.environment} />}>
          <KvList>
            <dt>{t('sys.buildVersion')}</dt>
            <dd className={mono}>{build?.version ? `v${build.version}` : '—'}</dd>
            <dt>{t('sys.buildCommit')}</dt>
            <dd className={mono}>{build?.commit || '—'}</dd>
            {build?.image_version && (<><dt>{t('health.buildImage')}</dt><dd className={mono}>{build.image_version}</dd></>)}
            {build?.helm_revision != null && (<><dt>{t('deploy.helm')}</dt><dd className={mono}>{build.helm_revision}</dd></>)}
            <dt>{t('deploy.uptime')}</dt>
            <dd>{build?.uptime_seconds != null ? formatDurationShort(build.uptime_seconds, t) : '—'}
              {build?.started_at && <span className="text-muted-foreground"> · {t('health.uptimeSince', formatDate(build.started_at))}</span>}</dd>
            <dt>{t('sys.instanceId')}</dt>
            <dd className={monoSm}>{scheduler?.instance_id || '—'}</dd>
            {sessionStore?.store && (
              <>
                <dt>{t('health.sessionStore')}</dt>
                <dd data-slot="session-store" data-store={sessionStore.store} className="flex flex-wrap items-center gap-1.5">
                  <ToneBadge tone={sessionStore.store === 'jdbc' ? 'success' : 'warning'} className="font-semibold">
                    {t(sessionStore.store === 'jdbc' ? 'health.sessionStoreJdbc' : 'health.sessionStoreMemory')}
                  </ToneBadge>
                  <span className="text-muted-foreground">
                    {sessionStore.store === 'jdbc'
                      ? (sessionStore.live != null ? t('health.sessionStoreLive', sessionStore.live) : '')
                      : t('health.sessionStoreMemoryHint')}
                  </span>
                </dd>
              </>
            )}
          </KvList>
        </SysCard>

        <SysCard icon={MemoryStick} iconClass={memTone} title={t('sys.memTitle')} cardKey="memory" alarm={memLevel === 'crit'}
          right={memory && <ToneBadge tone={memLevel === 'crit' ? 'danger' : memLevel === 'warn' ? 'warning' : 'success'} className="font-semibold">{formatPercent(memory.used_pct)}</ToneBadge>}>
          {memory ? (
            <>
              <ProgressBar value={memory.used_pct} max={100} size="sm" showValue label={t('sys.memUsed')} tone={memLevel} />
              <KvList>
                <dt>{t('sys.memUsed')}</dt><dd>{memory.used_mb} MB</dd>
                <dt>{t('sys.memFree')}</dt><dd>{memory.free_mb} MB</dd>
                <dt>{t('sys.memTotal')}</dt><dd>{memory.total_mb} MB</dd>
                <dt>{t('sys.memMax')}</dt><dd>{memory.max_mb} MB</dd>
              </KvList>
            </>
          ) : <p className="text-[0.9em] text-muted-foreground">{t('sys.poolUnavailable')}</p>}
        </SysCard>
      </div>

      <SubTitle><Cpu size={14} aria-hidden="true" className="text-primary" />{t('health.sectionCpu')} · {t('sys.metricsTitle')}</SubTitle>
      {metricsError ? (
        <SectionError t={t} onRetry={onRetry} />
      ) : (
        <div className={CHART_GRID} data-slot="jvm-charts">
          {chart(t('sys.cpuProcess'), 'cpu_process', '%', '#4f9cf9', HEALTH_THRESHOLDS.cpu, 100)}
          {chart(t('sys.heapPct'), 'heap_pct', '%', '#10b981', HEALTH_THRESHOLDS.heap, 100)}
          {chart(t('sys.threads'), 'threads', '', '#a78bfa', null, undefined)}
        </div>
      )}
    </div>
  )
}
