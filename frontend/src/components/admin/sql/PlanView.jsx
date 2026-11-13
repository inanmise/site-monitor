import { useMemo } from 'react'
import { AlertTriangle, ChevronDown, Gauge, Info, OctagonX, Play, ScanSearch } from 'lucide-react'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import { LoadingBlock, ProgressBar } from '../../ui/Progress.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'
import { flattenPlan, planSummary } from './planModel.js'
import { formatDuration } from './sqlUtils.js'

const fmtNum = (v, locale, digits = 2) => (v == null ? '—' : Number(v).toLocaleString(locale, { maximumFractionDigits: digits }))

/**
 * Sorgu planı görünümü (2026-11-13, C1): özet kutuları (toplam maliyet, tahmini satır, gerçek süre — ANALYZE ise —,
 * düğüm sayısı, sıralı taramalar), girintili düğüm ağacı (tür + tablo + maliyet / satır / süre + payı çubuğu; sıralı tarama
 * uyarı tonunda) ve ham JSON. `state`: `{ loading, data: { plan, summary, analyzed }, error, unsupported, cancelled }`.
 * EXPLAIN desteklenmiyorsa (H2 / PostgreSQL değil, 409 `SQL_EXPLAIN_UNSUPPORTED`) NÖTR bilgi, hata değil.
 */
export default function PlanView({ state, onExplain, canRun, className }) {
  const t = useT()
  const locale = useDateLocale()
  const data = state?.data
  const flat = useMemo(() => (data ? flattenPlan(data.plan) : null), [data])
  const sum = useMemo(() => (data ? planSummary(data.plan, data.summary) : null), [data])
  const raw = useMemo(() => { try { return data ? JSON.stringify(data.plan, null, 2) : '' } catch { return '' } }, [data])

  const actions = (
    <div className="flex flex-wrap gap-2">
      <Button type="button" variant="outline" size="sm" disabled={!canRun || state?.loading} onClick={() => onExplain?.(false)}
        className="pointer-coarse:h-10" data-slot="sql-plan-run">
        <ScanSearch /> {t('sql.plan.run')}
      </Button>
      <Button type="button" variant="outline" size="sm" disabled={!canRun || state?.loading} onClick={() => onExplain?.(true)}
        className="pointer-coarse:h-10" data-slot="sql-plan-analyze">
        <Play /> {t('sql.plan.analyze')}
      </Button>
    </div>
  )

  let body
  if (state?.loading) {
    body = <LoadingBlock label={t('sql.plan.loading')} size={20} />
  } else if (state?.unsupported) {
    body = (
      <div data-slot="sql-plan-unsupported">
        <StatusBlock icon={Info} tone="neutral" title={t('sql.plan.unsupportedTitle')}
          description={state.message || t('sql.plan.unsupported')} className="py-8" />
      </div>
    )
  } else if (state?.cancelled) {
    body = (
      <div data-slot="sql-plan-cancelled">
        <StatusBlock icon={OctagonX} title={t('sql.stop.stoppedTitle')} description={t('sql.plan.cancelledHint')} className="py-8" actions={actions} />
      </div>
    )
  } else if (state?.error) {
    body = (
      <div className="p-3">
        <AlertBanner tone="danger" role="alert" title={t('sql.plan.errorTitle')} className="mb-0">
          <span className="font-mono text-[13px] break-words whitespace-pre-wrap">{state.error}</span>
        </AlertBanner>
        <div className="mt-3">{actions}</div>
      </div>
    )
  } else if (!data) {
    body = <StatusBlock icon={Gauge} title={t('sql.plan.emptyTitle')} description={t('sql.plan.emptyHint')} className="py-8" actions={actions} />
  } else if (!flat?.nodes.length) {
    body = <StatusBlock icon={Info} tone="neutral" title={t('sql.plan.unreadable')} className="py-8" />
  } else {
    const tiles = [
      ['cost', t('sql.plan.totalCost'), fmtNum(sum.totalCost, locale)],
      ['rows', t('sql.plan.planRows'), fmtNum(sum.planRows, locale, 0)],
      ['ms', t('sql.plan.actualMs'), data.analyzed && sum.actualMs != null ? formatDuration(sum.actualMs) : '—'],
      ['nodes', t('sql.plan.nodes'), fmtNum(sum.nodes, locale, 0)],
    ]
    body = (
      <div className="flex min-w-0 flex-col gap-3 p-3" data-slot="sql-plan">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline" data-slot="sql-plan-kind" className="font-normal">
            {data.analyzed ? t('sql.plan.kindAnalyze') : t('sql.plan.kindEstimate')}
          </Badge>
          {flat.planningMs != null && <span className="text-xs text-muted-foreground tabular-nums">{t('sql.plan.planning', formatDuration(flat.planningMs))}</span>}
          <div className="ml-auto">{actions}</div>
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {tiles.map(([k, label, v]) => (
            <div key={k} data-tile={k} className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/40 px-3 py-2">
              <span className="text-base font-semibold tabular-nums [overflow-wrap:anywhere]">{v}</span>
              <span className="text-xs text-muted-foreground">{label}</span>
            </div>
          ))}
        </div>
        {sum.seqScans.length > 0 && (
          <div data-slot="sql-plan-seq"><AlertBanner tone="warning" icon={AlertTriangle} title={t('sql.plan.seqTitle', sum.seqScans.length)} className="mb-0">
            <span className="flex flex-wrap gap-1.5">
              {sum.seqScans.map((s, i) => (
                <Badge key={i} variant="outline" className="h-auto bg-background font-mono font-normal whitespace-normal [overflow-wrap:anywhere]">
                  {s.table}{s.rows != null ? ` · ${t('sql.plan.rowsShort', fmtNum(s.rows, locale, 0))}` : ''}
                </Badge>
              ))}
            </span>
          </AlertBanner></div>
        )}
        {flat.truncated && <AlertBanner tone="info" className="mb-0">{t('sql.plan.truncated', flat.nodes.length)}</AlertBanner>}
        <ul data-slot="sql-plan-tree" aria-label={t('sql.plan.treeLabel')} className="flex flex-col gap-1">
          {flat.nodes.map((n) => {
            const pct = n.share != null ? Math.round(n.share * 100) : null
            return (
              <li key={n.id} data-node={n.type} data-seq={n.seqScan ? 'true' : undefined}
                style={{ paddingInlineStart: `${Math.min(n.depth, 8) * 0.875}rem` }} className="min-w-0">
                <div className={cn('flex min-w-0 flex-col gap-1 rounded-md border px-2.5 py-1.5',
                  n.seqScan ? 'border-amber-500/40 bg-amber-500/5' : 'bg-card')}>
                  <div className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    {n.depth > 8 && <span className="text-[10px] text-muted-foreground tabular-nums">{t('sql.plan.depth', n.depth)}</span>}
                    <span className="text-sm font-semibold [overflow-wrap:anywhere]">{n.type}</span>
                    {n.joinType && <span className="text-xs text-muted-foreground">{n.joinType}</span>}
                    {n.relation && (
                      <span className="font-mono text-xs [overflow-wrap:anywhere]">
                        {t('sql.plan.on', n.relation)}{n.alias ? ` (${n.alias})` : ''}
                      </span>
                    )}
                    {n.index && <span className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('sql.plan.index', n.index)}</span>}
                  </div>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground tabular-nums">
                    <span>{t('sql.plan.cost', fmtNum(n.startupCost, locale), fmtNum(n.totalCost, locale))}</span>
                    <span>{t('sql.plan.rowsEst', fmtNum(n.planRows, locale, 0))}</span>
                    {n.actualMs != null && <span>{t('sql.plan.actual', formatDuration(n.actualMs), fmtNum(n.actualRows, locale, 0), fmtNum(n.loops, locale, 0))}</span>}
                    {n.rowsRemoved != null && n.rowsRemoved > 0 && <span>{t('sql.plan.removed', fmtNum(n.rowsRemoved, locale, 0))}</span>}
                  </div>
                  {n.filter && <code className="font-mono text-[11px] break-all whitespace-pre-wrap text-muted-foreground">{String(n.filter)}</code>}
                  {pct != null && (
                    <div className="flex items-center gap-2">
                      <div className="min-w-0 flex-1" data-slot="sql-plan-share">
                        <ProgressBar value={pct} size="sm" decorative tone={n.seqScan ? 'warn' : undefined} />
                      </div>
                      <span className="w-12 shrink-0 text-right text-[11px] text-muted-foreground tabular-nums">{t('sql.plan.share', pct)}</span>
                    </div>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
        <Collapsible>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="group/raw -ml-2 text-muted-foreground pointer-coarse:h-10">
              <ChevronDown className="transition-transform group-data-[state=open]/raw:rotate-180 motion-reduce:transition-none" /> {t('sql.plan.raw')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="relative mt-1 rounded-md border bg-muted/40 p-3 pr-12">
              <pre className="m-0 max-h-80 overflow-auto font-mono text-xs break-all whitespace-pre-wrap">{raw}</pre>
              <CopyButton value={raw} label={t('sql.plan.copyRaw')} copiedLabel={t('sql.copied')} className="absolute top-2 right-2" />
            </div>
          </CollapsibleContent>
        </Collapsible>
      </div>
    )
  }
  return <div data-slot="sql-plan-view" className={cn('flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto', className)}>{body}</div>
}
