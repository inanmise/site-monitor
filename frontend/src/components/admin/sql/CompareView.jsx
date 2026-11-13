import { useMemo, useState } from 'react'
import { GitCompare, Info, Lock } from 'lucide-react'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { ROW_KEY, SHOW_MAX, compareRuns, defaultKey } from './compareModel.js'
import { cellText } from './sqlUtils.js'

const TABLE_MIN_PX = 720
const show = (v) => (v == null ? 'NULL' : cellText(v))

/**
 * Sonuç karşılaştırma (2026-11-13, C4): aynı sekmenin son çalıştırmaları (en çok 5) arasında — önceki çalıştırma seçilir,
 * şimdiki ile kıyaslanır. Anahtar seçilebilir (varsayılan PK / `id` / tüm satır). Eklenen / silinen / değişen (hücre
 * düzeyinde önce → sonra). Maskeli kolonlar karşılaştırılmaz ("maskeli" notu). Tablo ≥ 720 px kap, altında kart.
 */
export default function CompareView({ runs, pkCols }) {
  const t = useT()
  const locale = useDateLocale()
  const [width, setEl] = useElementWidthState()
  const cur = runs?.[runs.length - 1] ?? null
  const earlier = (runs || []).slice(0, -1)
  const [baseSeq, setBaseSeq] = useState(null)
  const base = earlier.find((r) => r.seq === baseSeq) ?? earlier[earlier.length - 1] ?? null
  const [keyPick, setKeyPick] = useState(null)
  const common = useMemo(() => (base && cur ? base.cols.filter((c) => cur.cols.includes(c)) : []), [base, cur])
  const key = useMemo(() => {
    if (!base || !cur) return [ROW_KEY]
    if (keyPick && (keyPick === ROW_KEY || common.includes(keyPick))) return [keyPick]
    return defaultKey(base, cur, pkCols)
  }, [keyPick, base, cur, common, pkCols])
  const diff = useMemo(() => (base && cur ? compareRuns(base, cur, key) : null), [base, cur, key])

  if (!cur || !earlier.length) {
    return (
      <div data-slot="sql-compare-empty">
        <StatusBlock icon={GitCompare} title={t('sql.cmp.emptyTitle')} description={t('sql.cmp.emptyHint')} className="py-8" />
      </div>
    )
  }
  const when = (r) => new Date(r.at).toLocaleTimeString(locale)
  const masked = new Set([...(base?.masked || []), ...(cur?.masked || [])])
  const wide = width === 0 || width >= TABLE_MIN_PX

  const section = (kind, list, render) => (
    <section data-slot={`sql-compare-${kind}`} className="flex min-w-0 flex-col gap-1.5">
      <h4 className="text-sm font-semibold">{t(`sql.cmp.${kind}`, diff.counts[kind])}</h4>
      {list.length === 0 ? <p className="m-0 text-xs text-muted-foreground">{t('sql.cmp.none')}</p> : render(list)}
      {diff.counts[kind] > list.length && <p className="m-0 text-xs text-muted-foreground">{t('sql.cmp.more', diff.counts[kind] - list.length, SHOW_MAX)}</p>}
    </section>
  )
  const rowsTable = (list, tone) => (wide ? (
    <div className="overflow-x-auto rounded-lg border">
      <Table className="text-[12.5px]">
        <TableHeader className="bg-muted/50">
          <TableRow>{diff.columns.map((c) => <TableHead key={c} className="h-8 px-2 font-mono text-xs">{c}</TableHead>)}</TableRow>
        </TableHeader>
        <TableBody>
          {list.map((r, i) => (
            <TableRow key={i} data-diff={tone}>
              {diff.columns.map((c) => <TableCell key={c} className="max-w-[18rem] px-2 align-top font-mono text-xs break-words whitespace-pre-wrap">{show(r?.[c])}</TableCell>)}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  ) : (
    <ul className="flex flex-col gap-2">
      {list.map((r, i) => (
        <li key={i} data-diff={tone} className="rounded-lg border p-2 text-xs">
          {diff.columns.map((c) => (
            <span key={c} className="grid grid-cols-[minmax(0,7rem)_minmax(0,1fr)] gap-2">
              <span className="font-mono text-muted-foreground [overflow-wrap:anywhere]">{c}</span>
              <span className="font-mono [overflow-wrap:anywhere]">{show(r?.[c])}</span>
            </span>
          ))}
        </li>
      ))}
    </ul>
  ))

  return (
    <div ref={setEl} data-slot="sql-compare" data-mode={diff.mode} className="flex min-w-0 flex-col gap-3 p-3">
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('sql.cmp.base')} name="cmp-base" className="min-w-48 flex-1 sm:flex-none">
          {({ id }) => (
            <div className="[&_[data-slot=native-select-wrapper]]:w-full">
              <NativeSelect id={id} value={String(base?.seq ?? '')} onChange={(e) => setBaseSeq(Number(e.target.value))} className="pointer-coarse:h-10">
                {earlier.map((r) => (
                  <NativeSelectOption key={r.seq} value={String(r.seq)}>
                    {t('sql.cmp.runOption', when(r), r.rowCount)}{r.sql !== cur.sql ? ` · ${t('sql.cmp.otherSql')}` : ''}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </div>
          )}
        </Field>
        <Field label={t('sql.cmp.key')} name="cmp-key" className="min-w-48 flex-1 sm:flex-none">
          {({ id }) => (
            <div className="[&_[data-slot=native-select-wrapper]]:w-full">
              <NativeSelect id={id} value={key[0]} onChange={(e) => setKeyPick(e.target.value)} className="font-mono pointer-coarse:h-10">
                <NativeSelectOption value={ROW_KEY}>{t('sql.cmp.wholeRow')}</NativeSelectOption>
                {common.filter((c) => !masked.has(c)).map((c) => <NativeSelectOption key={c} value={c}>{c}</NativeSelectOption>)}
              </NativeSelect>
            </div>
          )}
        </Field>
        <span className="text-xs text-muted-foreground">{t('sql.cmp.current', when(cur), cur.rowCount)}</span>
      </div>
      <div className="flex flex-wrap gap-1.5" data-slot="sql-compare-counts">
        <Badge variant="outline" className="border-success/30 bg-success/10 text-success tabular-nums">{t('sql.cmp.badgeAdded', diff.counts.added)}</Badge>
        <Badge variant="outline" className="border-destructive/30 bg-destructive/10 text-destructive tabular-nums">{t('sql.cmp.badgeRemoved', diff.counts.removed)}</Badge>
        {diff.mode === 'key' && <Badge variant="outline" className="border-amber-500/40 bg-amber-500/10 text-amber-700 tabular-nums dark:text-amber-300">{t('sql.cmp.badgeChanged', diff.counts.changed)}</Badge>}
        <Badge variant="secondary" className="tabular-nums">{t('sql.cmp.badgeSame', diff.counts.same)}</Badge>
      </div>
      {diff.fellBack && <AlertBanner tone="info" icon={Info} className="mb-0">{t('sql.cmp.fellBack')}</AlertBanner>}
      {diff.maskedSkipped.length > 0 && (
        <p data-slot="sql-compare-masked" className="m-0 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          <Lock aria-hidden="true" className="size-3.5" />{t('sql.cmp.masked', diff.maskedSkipped.join(', '))}
        </p>
      )}
      {(diff.addedCols.length > 0 || diff.removedCols.length > 0) && (
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
          {diff.addedCols.length > 0 && t('sql.cmp.colsAdded', diff.addedCols.join(', '))}{' '}
          {diff.removedCols.length > 0 && t('sql.cmp.colsRemoved', diff.removedCols.join(', '))}
        </p>
      )}
      {diff.mode === 'key' && section('changed', diff.changed, (list) => (
        <ul className="flex flex-col gap-2">
          {list.map((c, i) => (
            <li key={i} data-diff="changed" className="rounded-lg border p-2 text-xs">
              <span className="mb-1 block font-semibold [overflow-wrap:anywhere]">{t('sql.cmp.keyIs', diff.key.join(', '), c.key.map((v) => (v == null ? 'NULL' : v)).join(' · '))}</span>
              {c.cols.map((col) => (
                <span key={col} className="grid grid-cols-[minmax(0,7rem)_minmax(0,1fr)] gap-2">
                  <span className="font-mono text-muted-foreground [overflow-wrap:anywhere]">{col}</span>
                  <span className="font-mono [overflow-wrap:anywhere]">
                    <span className={cn('text-destructive line-through decoration-destructive/50')}>{show(c.before?.[col])}</span>
                    {' → '}
                    <span className="text-success">{show(c.after?.[col])}</span>
                  </span>
                </span>
              ))}
            </li>
          ))}
        </ul>
      ))}
      {section('added', diff.added, (list) => rowsTable(list, 'added'))}
      {section('removed', diff.removed, (list) => rowsTable(list, 'removed'))}
    </div>
  )
}
