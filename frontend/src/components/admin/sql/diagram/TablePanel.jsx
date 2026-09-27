import { ArrowLeft, ArrowRight, Play, Table2 } from 'lucide-react'
import { useDateLocale, useT } from '../../../../i18n/index.jsx'
import { Spinner } from '../../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'
import { formatCompact, shortType } from '../sqlUtils.js'

function Section({ icon: Icon, title, count, children }) {
  return (
    <section className="flex flex-col gap-2">
      <h4 className="flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        {Icon && <Icon aria-hidden="true" className="size-3.5" />}{title}
        {count != null && <Badge variant="secondary" className="h-4 rounded-full px-1.5 text-[10px] tabular-nums">{count}</Badge>}
      </h4>
      {children}
    </section>
  )
}

/**
 * Diyagramda seçilen tablonun YAN PANELİ (shadcn Sheet, `modal={false}`): açıkken diyagram kullanılmaya devam eder —
 * başka bir karta dokunmak paneli o tabloya çevirir (dış tıklama paneli kapatmaz; X ve Escape kapatır).
 * Masaüstünde sağdan, telefonda alttan. Katman `--z-menu`: diyagram penceresinin (ModalShell) üstünde.
 * İçerik: kolonlar (PK/FK/NULL, tip), başvurduğu ve ona başvuran tablolar (dokununca o tabloya geçer),
 * "Tablo ayrıntıları" ve "Bu tabloyu sorgula".
 */
export default function TablePanel({
  name, model, columns, columnsLoading, side = 'right', onClose, onFocusTable, onOpenDetails, onQuery,
}) {
  const t = useT()
  const locale = useDateLocale()
  const open = !!name
  const graph = model?.graph
  const outs = open && graph ? (graph.out.get(name) || []) : []
  const ins = open && graph ? (graph.inc.get(name) || []).filter((e) => !e.self) : []
  const node = open ? model?.nodes.find((n) => n.name === name) : null
  const fkCols = new Map(outs.map((e) => [e.column, e]))
  const rows = node?.liveRows

  return (
    <Sheet open={open} modal={false} onOpenChange={(v) => { if (!v) onClose() }}>
      <SheetContent side={side} data-slot="diagram-table-panel"
        onInteractOutside={(e) => e.preventDefault()}
        onOpenAutoFocus={(e) => e.preventDefault()}
        className={cn('z-(--z-menu) gap-0 p-0 shadow-xl',
          side === 'bottom' ? 'max-h-[80dvh] rounded-t-xl pb-[env(safe-area-inset-bottom)]' : 'w-full sm:max-w-md')}>
        <SheetHeader className="gap-2 border-b pr-12">
          <SheetTitle className="flex min-w-0 items-center gap-2">
            <Table2 aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            <span className="font-mono break-all">{name}</span>
          </SheetTitle>
          <SheetDescription className="flex flex-wrap gap-x-3 text-xs">
            {rows != null && <span className="tabular-nums">{t('sql.diag.rowsShort', formatCompact(rows, locale))}</span>}
            <span>{t('sql.diag.refsCount', outs.filter((e) => !e.self).length)}</span>
            <span>{t('sql.diag.refdByCount', ins.length)}</span>
          </SheetDescription>
          <div className="mt-1 flex flex-wrap gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => onOpenDetails(name)}>
              <Table2 /> {t('sql.td.open')}
            </Button>
            <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => onQuery(name)}>
              <Play /> {t('sql.ex.queryThis')}
            </Button>
          </div>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto overscroll-contain p-4">
          <Section icon={ArrowRight} title={t('sql.td.fkOut')} count={outs.length}>
            {outs.length === 0 ? <p className="text-sm text-muted-foreground">{t('sql.td.none')}</p> : (
              <ul className="flex flex-col gap-1">
                {outs.map((e) => (
                  <li key={e.id}>
                    <Button type="button" variant="ghost" size="sm" onClick={() => onFocusTable(e.to)}
                      aria-label={t('sql.diag.jumpOut', e.column, e.to)}
                      className="h-auto min-h-8 w-full justify-start gap-2 px-2 py-1 font-mono text-xs font-normal whitespace-normal pointer-coarse:min-h-10">
                      <span className="text-muted-foreground">{e.column}</span>
                      <ArrowRight aria-hidden="true" className="size-3 shrink-0" />
                      <span className="font-semibold break-all">{e.to}</span>
                      {e.inferred ? <Badge variant="outline" className="ml-auto h-4 border-dashed px-1 text-[10px]">{t('sql.diag.inferredShort')}</Badge>
                        : <Badge variant="outline" className="ml-auto h-4 px-1 text-[10px]">FK</Badge>}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Section>
          <Section icon={ArrowLeft} title={t('sql.td.fkIn')} count={ins.length}>
            {ins.length === 0 ? <p className="text-sm text-muted-foreground">{t('sql.td.none')}</p> : (
              <ul className="flex flex-col gap-1">
                {ins.map((e) => (
                  <li key={e.id}>
                    <Button type="button" variant="ghost" size="sm" onClick={() => onFocusTable(e.from)}
                      aria-label={t('sql.diag.jumpIn', e.column, e.from)}
                      className="h-auto min-h-8 w-full justify-start gap-1 px-2 py-1 font-mono text-xs font-normal whitespace-normal pointer-coarse:min-h-10">
                      <span className="font-semibold break-all">{e.from}</span>
                      <span className="text-muted-foreground">.{e.column}</span>
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Section>
          <Section title={t('sql.td.columns')} count={columns?.length}>
            {columnsLoading && !columns ? (
              <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner size={14} inline decorative />{t('sql.td.loading')}</p>
            ) : !columns?.length ? (
              <p className="text-sm text-muted-foreground">{t('sql.td.none')}</p>
            ) : (
              <ul data-slot="diagram-panel-columns" className="divide-y rounded-md border">
                {columns.map((c) => {
                  const fk = fkCols.get(c.column_name)
                  const pk = c.column_name === 'id'
                  return (
                    <li key={c.column_name} className="flex min-w-0 items-center gap-2 px-2.5 py-1.5 text-xs">
                      {pk ? <Badge variant="outline" className="h-4 rounded-sm border-amber-500/40 bg-amber-500/10 px-1 text-[9px] font-bold text-amber-700 dark:text-amber-300">PK</Badge>
                        : fk ? <Badge variant="outline" className="h-4 rounded-sm border-emerald-500/40 bg-emerald-500/10 px-1 text-[9px] font-bold text-emerald-700 dark:text-emerald-300">FK</Badge>
                          : <span aria-hidden="true" className="inline-block w-[22px] shrink-0" />}
                      <span className="min-w-0 flex-1 truncate font-mono">{c.column_name}</span>
                      {fk && <span className="truncate font-mono text-[10.5px] text-muted-foreground">→ {fk.to}</span>}
                      <span className="shrink-0 text-muted-foreground">{shortType(c.data_type)}</span>
                      {c.is_nullable === 'YES' && <span className="shrink-0 text-[10px] text-muted-foreground/80">NULL</span>}
                    </li>
                  )
                })}
              </ul>
            )}
          </Section>
        </div>
      </SheetContent>
    </Sheet>
  )
}
