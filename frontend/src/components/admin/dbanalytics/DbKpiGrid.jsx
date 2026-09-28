import { useId } from 'react'
import { Activity, Database, Gauge, HardDrive, Layers, MemoryStick, Timer, XCircle } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { ProgressBar } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { cn } from '@/lib/utils'
import { LEVEL_KEY } from './DbParts.jsx'
import { cacheTone, dec, msTone, num, pct, snippet, successTone } from './dbModel.js'

const ICON_TONE = {
  success: 'bg-success/15 text-success',
  warning: 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
  danger: 'bg-destructive/15 text-destructive',
  muted: 'bg-muted text-muted-foreground',
  default: 'bg-primary/10 text-primary',
}
const VALUE_TONE = { danger: 'text-destructive', warning: 'text-amber-700 dark:text-amber-300', muted: 'text-muted-foreground' }
const PROGRESS_TONE = { danger: 'crit', warning: 'warn', success: 'ok' }

/**
 * Özet kutucuğu — tıklanır (ilgili sekme + süzgeç ya da sağlık kartı). Durum tonu ikon zemininde, değer renginde ve
 * METİNLİ rozette taşınır (renk tek başına anlam taşımaz); sol renk şeridi YOK. Test kancası `data-kpi` + `data-tone`.
 */
function Kpi({ kpiKey, icon: Icon, label, value, tone = 'default', valueTone, badge, badgeTone = 'muted', sub, subMono, bar, onClick, hint }) {
  return (
    <Button type="button" variant="outline" data-kpi={kpiKey} data-tone={tone} onClick={onClick} title={hint}
      className={cn('flex h-auto min-w-0 flex-col items-start justify-start gap-2 rounded-xl border-border bg-card px-3.5 py-3 text-left font-normal whitespace-normal shadow-xs sm:px-4',
        'transition-[border-color,box-shadow] hover:border-primary/60 hover:bg-card hover:shadow-md motion-reduce:transition-none')}>
      <span className="flex w-full min-w-0 items-center gap-2">
        <span className={cn('grid size-7 shrink-0 place-items-center rounded-lg', ICON_TONE[tone] || ICON_TONE.default)}>
          <Icon aria-hidden="true" className="size-4" />
        </span>
        <span className="line-clamp-2 min-w-0 text-[11px] leading-tight font-bold tracking-wider break-words text-muted-foreground uppercase">{label}</span>
      </span>
      <span className={cn('max-w-full truncate text-[22px] leading-none font-extrabold tracking-tight tabular-nums sm:text-2xl', VALUE_TONE[valueTone])}>
        {value}
      </span>
      {bar != null && <span className="w-full">{bar}</span>}
      <span className="flex w-full min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        {badge != null && <ToneBadge tone={badgeTone} className="max-w-full truncate font-semibold">{badge}</ToneBadge>}
        {sub ? <span className={cn('line-clamp-2 min-w-0 max-w-full break-words', subMono && 'font-mono text-[11px] break-all')}>{sub}</span> : null}
      </span>
    </Button>
  )
}

/** Kutucuk grubu: başlık + açıklama; telefonda 2, geniş kapta 4 sütun (kap sorgusu — kenar çubuğu açıkken de doğru). */
function KpiGroup({ title, caption, children }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
        <h4 id={id} className="m-0 text-xs font-bold tracking-wider text-muted-foreground uppercase">{title}</h4>
        {caption && <span className="text-xs text-muted-foreground">{caption}</span>}
      </div>
      <div className="grid grid-cols-2 gap-2.5 sm:gap-3 @2xl:grid-cols-4">{children}</div>
    </section>
  )
}

/**
 * İki grup: "Veritabanı · şu an" (boyut, sunucu bağlantıları, önbellek isabeti, ölü satır — canlı durum) ve
 * "SQL Playground sorguları · <pencere>" (sorgu, başarısız, ortalama/p95, en yavaş — seçili pencere). Sorgu grubu
 * pg_stat_statements AÇIKKEN de Playground geçmişinden hesaplanır; başlık bunu söyler.
 */
export default function DbKpiGrid({ t, sum, conn, stats, dead, tables, slowestSql, pgss, winLbl, onAction }) {
  const hint = t('uact.detailHint')
  const succ = sum.success_rate ?? 100
  const failedN = Number(sum.failed) || 0
  const cache = stats?.cache_hit_pct ?? null
  const cTone = cacheTone(cache)
  const avgTone = msTone(sum.avg_ms)
  const slowSql = !pgss ? slowestSql?.[0]?.sql : null
  const users = sum.user_count
  // Pencerede sorgu yoksa süre/başarı oranı ÖLÇÜLMEMİŞTİR: sunucunun 0 ms / %100 varsayılanı gösterilmez.
  const noQ = !(Number(sum.queries) > 0)
  return (
    <div className="flex min-w-0 flex-col gap-4" data-slot="db-kpis">
      <KpiGroup title={t('dba.groupDb')} caption={t('dba.groupDbCaption')}>
        <Kpi kpiKey="size" icon={HardDrive} label={t('db.connSize')} value={sum.db_size || conn.dbSize || '—'} hint={hint}
          tone={sum.db_size || conn.dbSize ? 'default' : 'muted'}
          badge={sum.db_size || conn.dbSize ? null : t('dba.unknown')}
          sub={t('db.kpiTables', num(sum.table_count ?? tables.length))} onClick={() => onAction('size')} />
        <Kpi kpiKey="connections" icon={Activity} label={t('db.secConn')} hint={hint}
          value={`${conn.active ?? '—'} / ${conn.max ?? '—'}`}
          tone={conn.pct == null ? 'muted' : conn.tone} badgeTone={conn.tone}
          badge={conn.pct != null ? t('db.kpiUsage', conn.pct) : t('dba.unknown')}
          bar={conn.pct != null ? <ProgressBar value={Number(conn.active)} max={Number(conn.max)} size="sm" decorative tone={PROGRESS_TONE[conn.tone]} /> : null}
          sub={conn.resp != null && conn.resp >= 0 ? t('db.kpiConnSub', conn.resp) : null} onClick={() => onAction('connections')} />
        <Kpi kpiKey="cache" icon={MemoryStick} label={t('dba.kpiCache')} hint={hint}
          value={cache != null ? pct(cache) : '—'} tone={cTone === 'success' ? 'default' : cTone}
          valueTone={cTone === 'danger' || cTone === 'warning' ? cTone : cTone === 'muted' ? 'muted' : undefined}
          badge={t(LEVEL_KEY[cTone])} badgeTone={cTone}
          sub={stats ? t('dba.kpiCacheSub') : t('dba.statsUnknownShort')} onClick={() => onAction('cache')} />
        <Kpi kpiKey="dead" icon={Layers} label={t('dba.kpiDead')} hint={hint}
          value={dead?.pct != null ? pct(dead.pct) : '—'}
          tone={!dead ? 'muted' : dead.vacuum > 0 ? 'warning' : 'default'}
          badge={!dead ? t('dba.unknown') : dead.vacuum > 0 ? t('dba.kpiVacuumN', dead.vacuum) : t('dba.kpiVacuumNone')}
          badgeTone={!dead ? 'muted' : dead.vacuum > 0 ? 'warning' : 'success'}
          sub={dead ? t('dba.deadRowsN', num(dead.dead)) : null} onClick={() => onAction('dead')} />
      </KpiGroup>

      <KpiGroup title={t('dba.groupQueries', winLbl)} caption={pgss ? t('dba.groupQueriesPgss') : null}>
        <Kpi kpiKey="queries" icon={Database} label={t('db.kpiQueries')} value={num(sum.queries ?? 0)} hint={hint}
          badge={noQ ? null : t('db.kpiSuccess', dec(succ))} badgeTone={successTone(succ)}
          sub={users != null ? `${winLbl} · ${t('dba.kpiUsers', num(users))}` : winLbl} onClick={() => onAction('queries')} />
        <Kpi kpiKey="failed" icon={XCircle} label={t('db.kpiFailed')} value={num(failedN)} hint={hint}
          tone={failedN > 0 ? 'danger' : 'success'} valueTone={failedN > 0 ? 'danger' : undefined}
          badge={failedN > 0 ? t('dba.kpiFailShare', pct(Math.round((100 - succ) * 10) / 10)) : t('db.kpiNone')}
          badgeTone={failedN > 0 ? 'danger' : 'success'} sub={winLbl} onClick={() => onAction('failed')} />
        <Kpi kpiKey="avg" icon={Timer} label={t('db.kpiAvg')} value={noQ ? '—' : `${num(sum.avg_ms ?? 0)} ms`} hint={hint}
          tone={avgTone === 'success' || avgTone === 'muted' ? 'default' : avgTone}
          valueTone={avgTone === 'success' || avgTone === 'muted' ? undefined : avgTone}
          sub={noQ ? winLbl : sum.p95_ms != null ? t('dba.kpiP95', num(sum.p95_ms)) : t('db.kpiMax', num(sum.max_ms ?? 0))} onClick={() => onAction('avg')} />
        <Kpi kpiKey="slowest" icon={Gauge} label={t('db.kpiSlowest')} value={noQ ? '—' : `${num(sum.max_ms ?? 0)} ms`} hint={hint}
          tone={msTone(sum.max_ms) === 'danger' ? 'danger' : 'default'} valueTone={msTone(sum.max_ms) === 'danger' ? 'danger' : undefined}
          sub={slowSql ? snippet(slowSql, 40) : winLbl} subMono={!!slowSql} onClick={() => onAction('slowest')} />
      </KpiGroup>
    </div>
  )
}
