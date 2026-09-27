import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Search, Table2, X } from 'lucide-react'
import { useDateLocale, useT } from '../../../../i18n/index.jsx'
import KebabMenu from '../../../ui/KebabMenu.jsx'
import StatusBlock from '../../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { formatCompact } from '../sqlUtils.js'

/** Liste öğesinin DOM kimliği (tablo adı güvenli karakterlere indirgenir). */
const itemId = (prefix, name) => `${prefix}-${String(name).replace(/[^a-zA-Z0-9_-]/g, '_')}`

/** "kolon → tablo" düğmesi — hedef tabloya atlar. */
function RelChip({ column, table, dir, onJump, t }) {
  return (
    <li>
      <Button type="button" variant="outline" size="sm" onClick={() => onJump(table)}
        aria-label={t(dir === 'out' ? 'sql.diag.jumpOut' : 'sql.diag.jumpIn', column, table)}
        className="h-auto min-h-8 max-w-full justify-start gap-1.5 px-2 py-1 font-mono text-xs font-normal whitespace-normal pointer-coarse:min-h-10">
        {dir === 'out'
          ? <><span className="text-muted-foreground">{column}</span><ArrowRight aria-hidden="true" className="size-3 shrink-0" /><span className="font-semibold break-all">{table}</span></>
          : <><span className="font-semibold break-all">{table}</span><span className="text-muted-foreground">.{column}</span></>}
      </Button>
    </li>
  )
}

/**
 * İlişki diyagramının LİSTE görünümü — telefonda (< 768 px) varsayılan; ekran okuyucu ve klavye için de en düz yol.
 * Her tablo: satır sayısı, "Başvurduğu" (FK →) ve "Ona başvuranlar" listeleri; ilişkiye dokunmak o tablonun kartına
 * kaydırır ve vurgular. Eylemler (ayrıntı / sorgu / diyagramda göster) satır menüsünde.
 */
export default function DiagramListView({ model, focus, onFocus, onOpenDetails, onQuery, onShowInDiagram, prefix }) {
  const t = useT()
  const locale = useDateLocale()
  const [q, setQ] = useState('')
  const listRef = useRef(null)
  const graph = model.graph
  const rowCount = useMemo(() => new Map(model.nodes.map((n) => [n.name, n.liveRows])), [model.nodes])

  const names = useMemo(() => {
    const needle = q.trim().toLowerCase()
    const all = model.nodes.map((n) => n.name)
    const connected = all.filter((n) => graph.connected.includes(n))
    const isolated = all.filter((n) => !graph.connected.includes(n))
    const match = (n) => !needle || n.toLowerCase().includes(needle)
    return { connected: connected.sort().filter(match), isolated: isolated.sort().filter(match) }
  }, [model.nodes, graph, q])

  // Odaklanan tablo görünür alana (liste içinde) kaydırılır.
  useEffect(() => {
    if (!focus) return
    const el = listRef.current?.querySelector(`[id="${itemId(prefix, focus)}"]`)
    el?.scrollIntoView?.({ block: 'nearest', behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 'auto' : 'smooth' })
  }, [focus, prefix])

  const jump = (name) => {
    if (q && !name.toLowerCase().includes(q.trim().toLowerCase())) setQ('')
    onFocus(name)
  }

  const item = (name) => {
    const outs = (graph.out.get(name) || []).filter((e) => !e.self)
    const ins = (graph.inc.get(name) || []).filter((e) => !e.self)
    const self = (graph.out.get(name) || []).filter((e) => e.self)
    const rows = rowCount.get(name)
    return (
      <li key={name} id={itemId(prefix, name)} data-slot="diagram-list-item" data-state={focus === name ? 'focus' : undefined}
        className="scroll-mt-2 px-3 py-3 data-[state=focus]:bg-primary/5 data-[state=focus]:shadow-[inset_0_0_0_2px_var(--primary)] sm:px-4">
        <div className="flex min-w-0 items-start gap-2">
          <Table2 aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1">
            <p className="font-mono text-sm font-semibold break-all">{name}</p>
            <p className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-muted-foreground">
              {rows != null && <span className="tabular-nums">{t('sql.diag.rowsShort', formatCompact(rows, locale))}</span>}
              <span>{t('sql.diag.refsCount', outs.length)}</span>
              <span>{t('sql.diag.refdByCount', ins.length)}</span>
              {self.length > 0 && <Badge variant="outline" className="h-4 px-1 text-[10px]">{t('sql.diag.selfRef', self.map((e) => e.column).join(', '))}</Badge>}
            </p>
          </div>
          <KebabMenu label={t('sql.res.rowActions')} rowLabel={name} items={[
            { label: t('sql.td.open'), onClick: () => onOpenDetails(name) },
            { label: t('sql.ex.queryThis'), onClick: () => onQuery(name) },
            { label: t('sql.diag.showInDiagram'), onClick: () => onShowInDiagram(name) },
          ]} />
        </div>
        {outs.length > 0 && (
          <div className="mt-2 pl-6">
            <p className="mb-1 flex items-center gap-1 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
              <ArrowRight aria-hidden="true" className="size-3" />{t('sql.td.fkOut')}
            </p>
            <ul className="flex flex-wrap gap-1.5">
              {outs.map((e) => <RelChip key={e.id} column={e.column} table={e.to} dir="out" onJump={jump} t={t} />)}
            </ul>
          </div>
        )}
        {ins.length > 0 && (
          <div className="mt-2 pl-6">
            <p className="mb-1 flex items-center gap-1 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
              <ArrowLeft aria-hidden="true" className="size-3" />{t('sql.td.fkIn')}
            </p>
            <ul className="flex flex-wrap gap-1.5">
              {ins.map((e) => <RelChip key={e.id} column={e.column} table={e.from} dir="in" onJump={jump} t={t} />)}
            </ul>
          </div>
        )}
      </li>
    )
  }

  const empty = names.connected.length === 0 && names.isolated.length === 0
  return (
    <div data-slot="diagram-list" className="flex min-h-0 flex-1 flex-col gap-2">
      <InputGroup className="h-10 sm:h-9">
        <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('sql.diag.filterTables')}
          aria-label={t('sql.diag.filterTables')} />
        <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        {q && (
          <InputGroupAddon align="inline-end">
            <InputGroupButton size="icon-xs" aria-label={t('sql.ex.clearSearch')} onClick={() => setQ('')}><X /></InputGroupButton>
          </InputGroupAddon>
        )}
      </InputGroup>
      <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain rounded-lg border bg-card">
        {empty ? (
          <StatusBlock icon={Search} title={t('sql.diag.noMatch')} className="py-8" />
        ) : (
          <>
            {names.connected.length > 0 && (
              <ul aria-label={t('sql.diag.relatedGroup')} className="divide-y">{names.connected.map(item)}</ul>
            )}
            {names.isolated.length > 0 && (
              <section aria-label={t('sql.diag.unrelatedGroup', names.isolated.length)} className={cn(names.connected.length > 0 && 'border-t')}>
                <h4 className="bg-muted/50 px-4 py-2 text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">
                  {t('sql.diag.unrelatedGroup', names.isolated.length)}
                </h4>
                <ul className="divide-y">{names.isolated.map(item)}</ul>
              </section>
            )}
          </>
        )}
      </div>
    </div>
  )
}
