import { CheckCircle2, AlertTriangle, AlertOctagon, Pause, Play, RefreshCw, ChevronRight, Activity, Clock, Zap, Database, Timer, Percent, MemoryStick, Plug } from 'lucide-react'
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import { KpiCard } from '../HealthUi.jsx'
import { KPI_TONE, formatDurationShort, relTime, isFuture, cfgDetailText } from './healthModel.js'
import { HealthSpark } from './HealthParts.jsx'

/** Genel seviye → ikon, çerçeve, mürekkep (sol şerit YOK; kartın TÜM çerçevesi). */
const OVERALL = {
  ok:   { Icon: CheckCircle2, frame: 'border-success/40 bg-success/5', ink: 'text-success', key: 'health.overallOk', desc: 'health.overallOkDesc' },
  warn: { Icon: AlertTriangle, frame: 'border-amber-500/50 bg-amber-500/5', ink: 'text-amber-600 dark:text-amber-400', key: 'health.overallWarn', desc: 'health.overallWarnDesc' },
  down: { Icon: AlertOctagon, frame: 'border-destructive/60 bg-destructive/5', ink: 'text-destructive', key: 'health.overallDown', desc: 'health.overallDownDesc' },
}
const DOT = { ok: 'bg-success', warn: 'bg-amber-500', down: 'bg-destructive' }

/** Sebep kodu → sözlük anahtarı (argümanlar model'den). */
const REASON_TEXT = {
  loadHealth: (t) => t('health.reasonUnavailable', t('health.partHealth')),
  loadPart: (t, [part]) => t('health.reasonUnavailable', t(`health.part${part.charAt(0).toUpperCase() + part.slice(1)}`)),
  hbAlarm: (t) => t('health.hbAlarm'),
  scanAlarm: (t) => t('health.scanAlarm'),
  networkAlarm: (t) => t('health.networkAlarm'),
  memory: (t, [pct]) => t('health.reasonMemory', formatPercent(pct)),
  queueSaturated: (t) => t('health.reasonQueue'),
  callerRuns: (t, [n]) => t('health.reasonCallerRuns', n),
  poolWaiting: (t, [n]) => t('health.reasonPoolWaiting', n),
  dbSlow: (t, [ms]) => t('health.reasonDbSlow', ms),
  httpErr: (t, [pct]) => t('health.reasonHttpErr', formatPercent(pct)),
  httpSlow: (t, [ms]) => t('health.reasonHttpSlow', ms),
  smtpAlarm: (t, [period]) => t('health.smtpAlarmFor', t(`health.smtpPeriod${period}`)),
  push: (t, [rate]) => t('health.reasonPush', formatPercent(rate)),
  weeklyFailed: (t, [n]) => t('health.reasonWeeklyFailed', n),
  domAlarm: (t) => t('health.domAlarm'),
  domFallback: (t) => t('health.reasonDomFallback'),
  ldap: (t, [detail]) => t('health.reasonLdap', cfgDetailText(detail, t)),
  cleanup: (t) => t('health.reasonCleanup'),
}
export function reasonText(r, t) {
  const f = REASON_TEXT[r.code]
  return f ? f(t, r.args || []) : r.code
}

/** "Son kontrol 12:29:41 · 30 sn'de bir" çipi + Duraklat/Sürdür + Yenile. */
function RefreshChip({ t, lastChecked, paused, onTogglePause, onRefresh, refreshing, compact = false }) {
  const time = lastChecked ? lastChecked.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—'
  return (
    <div className="flex flex-wrap items-center gap-1.5" data-slot="health-refresh">
      <Badge variant="outline" data-slot="health-checked" data-paused={paused ? 'true' : 'false'}
        className="h-8 gap-1.5 rounded-full px-2.5 font-normal text-muted-foreground tabular-nums">
        <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />
        <span>{t('health.lastChecked', time)}</span>
        {!compact && <span className="hidden sm:inline">· {paused ? t('health.autoRefreshPaused') : t('health.autoRefreshOn')}</span>}
      </Badge>
      <span className="flex shrink-0 items-center gap-1.5">
        <SimpleTooltip content={paused ? t('health.resume') : t('health.pause')}>
          <Button type="button" variant="outline" size={compact ? 'icon-sm' : 'icon'} onClick={onTogglePause} aria-pressed={paused}
            aria-label={paused ? t('health.resume') : t('health.pause')}>
            {paused ? <Play aria-hidden="true" /> : <Pause aria-hidden="true" />}
          </Button>
        </SimpleTooltip>
        <SimpleTooltip content={t('health.refreshNow')}>
          <Button type="button" variant="outline" size={compact ? 'icon-sm' : 'icon'} onClick={onRefresh} disabled={refreshing}
            aria-label={t('health.refreshNow')} aria-busy={refreshing || undefined}>
            <RefreshCw aria-hidden="true" className={cn(refreshing && 'animate-spin motion-reduce:animate-none')} />
          </Button>
        </SimpleTooltip>
      </span>
    </div>
  )
}

/**
 * Genel durum bandı: Sağlıklı / Bozulma / Kritik + sebepler (önem sırasına göre gruplu, her biri bölüme atlar) +
 * "son kontrol · otomatik yenileme" çipi + Duraklat / Yenile. Test kancası: `data-slot="health-overall"` + `data-level`.
 */
export function HealthStatusBanner({ t, level, reasons, lastChecked, paused, onTogglePause, onRefresh, refreshing, onGoSection, sectionLabel, hideChip = false }) {
  const o = OVERALL[level] || OVERALL.ok
  const groups = [['down', 'health.sevCritical'], ['warn', 'health.sevWarning']]
    .map(([lv, key]) => [lv, key, reasons.filter((r) => r.level === lv)]).filter(([, , rs]) => rs.length)
  // lg (1024) kırılımı bilinçli: 768'de kenar çubuğu hâlâ açık (içerik ≈ 440 px) — md'de yan yana dizmek metni
  // harf harf sarıyordu (2026-09-27 tablet ölçümü). Telefonda çip yapışkan şeritte (hideChip).
  return (
    <Card data-slot="health-overall" data-level={level} role="status" aria-live="polite"
      className={cn('gap-3 px-4 py-4 shadow-xs', o.frame)}>
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <o.Icon size={28} aria-hidden="true" className={cn('mt-0.5 shrink-0', o.ink)} />
          <div className="min-w-0">
            <h3 className={cn('text-lg leading-tight font-semibold tracking-tight', o.ink)} data-slot="health-overall-title">{t(o.key)}</h3>
            <p className="text-sm text-muted-foreground">{level === 'ok' ? t(o.desc) : t(o.desc, reasons.length)}</p>
          </div>
        </div>
        {!hideChip && (
          <div className="lg:shrink-0">
            <RefreshChip t={t} lastChecked={lastChecked} paused={paused} onTogglePause={onTogglePause} onRefresh={onRefresh} refreshing={refreshing} />
          </div>
        )}
      </div>
      {groups.length > 0 && (
        <div className="grid grid-cols-1 gap-2 lg:grid-cols-2" data-slot="health-reasons">
          {groups.map(([lv, key, rs]) => (
            <div key={lv} className="rounded-lg border bg-card/70 px-3 py-2" data-severity={lv}>
              <div className={cn('mb-1 flex items-center gap-1.5 text-[11px] font-bold tracking-wider uppercase', lv === 'down' ? 'text-destructive' : 'text-amber-600 dark:text-amber-400')}>
                <span aria-hidden="true" className={cn('size-2 rounded-full', DOT[lv])} />
                {t(key)} · {rs.length}
              </div>
              <ul className="flex list-none flex-col gap-0.5">
                {rs.map((r, i) => (
                  <li key={`${r.code}-${i}`} className="flex min-w-0 items-center justify-between gap-2 text-sm">
                    <span className="min-w-0 break-words">{reasonText(r, t)}</span>
                    {r.section && onGoSection && (
                      <Button type="button" variant="ghost" size="sm" className="h-8 shrink-0 gap-1 px-2 text-xs" data-slot="reason-go"
                        onClick={() => onGoSection(r.section)} aria-label={t('health.sectionShow', sectionLabel(r.section))}>
                        <span className="hidden sm:inline">{sectionLabel(r.section)}</span><ChevronRight aria-hidden="true" />
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}

/** Telefonda üstte yapışan mini durum şeridi (MobileTopBar 56 px'in altında). Yalnız useIsMobile → true iken çizilir. */
export function HealthStickyBar({ t, level, lastChecked, paused, onTogglePause, onRefresh, refreshing }) {
  const o = OVERALL[level] || OVERALL.ok
  return (
    <div data-slot="health-sticky" data-level={level}
      className="sticky top-14 z-20 -mx-3 mb-1 flex items-center justify-between gap-2 border-b bg-background/95 px-3 py-1.5 backdrop-blur-sm">
      <span className={cn('inline-flex min-w-0 items-center gap-2 text-sm font-semibold', o.ink)}>
        <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-full', DOT[level] || DOT.ok)} />
        <span className="truncate">{t(o.key)}</span>
      </span>
      <RefreshChip t={t} lastChecked={lastChecked} paused={paused} onTogglePause={onTogglePause} onRefresh={onRefresh} refreshing={refreshing} compact />
    </div>
  )
}

/**
 * KPI ızgarası: telefonda 2, tablette 4, genişte otomatik sütun. Her kart tıklanınca ilgili bölümü açar ve oraya kaydırır.
 * Seri olan kartlarda minik kıvılcım (ChartContainer). Test kancası: `data-kpi` + `data-tone`.
 */
export function HealthKpiGrid({ t, kpis, onGoSection, sectionLabel }) {
  if (!kpis) return null
  const tone = (level) => KPI_TONE[level]
  const hb = kpis.uptime
  const hbText = hb.hbMinutes >= 0 ? t('health.hbMinutes').replace('{n}', hb.hbMinutes) : t('health.hbAlarm')
  const sch = kpis.scheduler
  const ex = kpis.executor
  const db = kpis.db
  const ints = kpis.integrations
  const items = [
    { key: 'uptime', section: 'heartbeat', icon: Activity, label: t('deploy.uptime'), tone: tone(hb.level),
      value: hb.seconds != null ? formatDurationShort(hb.seconds, t) : '—', sub: `${t('health.hbSignal')}: ${hbText}` },
    // Değer: çalışıyorsa "Çalışıyor", sıradaki koşu gelecekteyse "30 dk sonra", aksi hâlde "Boşta" (geçmiş zaman uzar, sığmaz)
    { key: 'scheduler', section: 'sched', icon: Clock, label: t('sys.schedulerTitle'), tone: tone(sch.level),
      value: sch.running ? t('sys.running') : (isFuture(sch.nextRun) ? relTime(sch.nextRun, t) : t('sys.idle')),
      sub: sch.lastRun ? `${t('sys.lastRun')}: ${relTime(sch.lastRun, t) || '—'}` : t('sys.never') },
    { key: 'executor', section: 'sched', icon: Zap, label: t('health.kpiExecutor'), tone: ex ? tone(ex.level) : undefined,
      value: ex ? `${ex.active} / ${ex.pool}` : '—', sub: ex ? t('health.queueShort', ex.queue, ex.cap) : t('sys.poolUnavailable') },
    { key: 'db', section: 'db', icon: Database, label: t('health.kpiDbConn'), tone: db ? tone(db.level) : undefined,
      value: db ? `${db.active} / ${db.max}` : '—', sub: db && db.ms != null ? `${t('health.dbResponseMs')}: ${db.ms} ms` : t('sys.poolUnavailable') },
    { key: 'http-ms', section: 'http', icon: Timer, label: t('health.kpiHttpMs'), tone: tone(kpis.httpMs.level),
      value: kpis.httpMs.value != null ? `${kpis.httpMs.value} ms` : '—', sub: t('sys.metricsTitle'), spark: kpis.httpMs.series, color: '#f59e0b' },
    { key: 'http-err', section: 'http', icon: Percent, label: t('health.kpiErrRate'), tone: tone(kpis.httpErr.level),
      value: kpis.httpErr.value != null ? formatPercent(kpis.httpErr.value) : '—', sub: t('sys.metricsTitle'), spark: kpis.httpErr.series, color: '#ef4444' },
    { key: 'memory', section: 'sys', icon: MemoryStick, label: t('sys.memTitle'), tone: kpis.memory ? tone(kpis.memory.level) : undefined,
      value: kpis.memory ? formatPercent(kpis.memory.pct) : '—', sub: kpis.memory ? `${kpis.memory.usedMb} / ${kpis.memory.maxMb} MB` : '—',
      spark: kpis.memory?.series, color: '#10b981' },
    { key: 'integrations', section: 'integrations', icon: Plug, label: t('health.kpiIntegrations'), tone: tone(ints.level),
      value: `${ints.ok} / ${ints.total}`, sub: t('health.kpiIntegrationsSub', ints.ok, ints.total) },
  ]
  return (
    <div data-slot="health-kpis" role="group" aria-label={t('health.kpiGridLabel')}
      className="grid grid-cols-2 gap-2 sm:grid-cols-3 sm:gap-3 lg:grid-cols-4 2xl:grid-cols-8">
      {items.map((it) => (
        <KpiCard key={it.key} kpiKey={it.key} icon={it.icon} value={it.value} label={it.label} sub={it.sub} tone={it.tone}
          title={t('health.kpiHint', sectionLabel(it.section))} onClick={() => onGoSection(it.section)}
          spark={it.spark && it.spark.length > 1 ? <HealthSpark data={it.spark} color={it.color} /> : null} />
      ))}
    </div>
  )
}
