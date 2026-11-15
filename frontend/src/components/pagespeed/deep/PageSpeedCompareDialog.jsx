import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeftRight, RefreshCw } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { formatBytes } from '../../../utils/formatBytes.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { compareSnapshots } from './pageSpeedDeepModel.js'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const WRAP = '[overflow-wrap:anywhere]'
const TOUCH = 'pointer-coarse:min-h-10'
const CELL = 'h-auto whitespace-normal px-2 py-1 align-top [overflow-wrap:anywhere]'
const BYTE_KEYS = new Set(['bytes', 'img', 'js', 'css', 'font', 'third'])
const MS_KEYS = new Set(['load', 'ttfb', 'html'])

function fmt(key, v) {
  if (v === null || v === undefined) return '—'
  if (BYTE_KEYS.has(key)) return formatBytes(v)
  if (MS_KEYS.has(key)) return `${Math.round(v)} ms`
  return String(v)
}

function deltaText(key, row) {
  if (row.delta === null) return '—'
  if (row.delta === 0) return '±0'
  const sign = row.delta > 0 ? '+' : '−'
  const abs = Math.abs(row.delta)
  const base = BYTE_KEYS.has(key) ? formatBytes(abs) : MS_KEYS.has(key) ? `${Math.round(abs)} ms` : String(abs)
  return row.pct != null ? `${sign}${base} (${sign}${Math.abs(row.pct)} %)` : `${sign}${base}`
}

/**
 * İki ölçümü karşılaştır (Sayfa Hızı 2. sürüm madde 17): seçilen iki anlık görüntünün (son ölçüm / ihlal anları) özet
 * değerleri yan yana + fark (küçük olan iyi; kötüleşme turuncu, iyileşme yeşil, bilinmeyen nötr), kaynak farkları
 * (eklenen, kalkan, büyüyen ≥ %10 ve ≥ 5 KB, yavaşlayan ≥ %50 ve ≥ 200 ms). Yalnız saklanan ölçümler karşılaştırılabilir.
 */
export default function PageSpeedCompareDialog({ options, initialB = '', readSnapshot, onClose }) {
  const t = useT()
  const pickA = () => {
    const other = options.find((o) => o.value !== initialB)
    return other ? other.value : ''
  }
  const [aVal, setAVal] = useState(pickA)
  const [bVal, setBVal] = useState(initialB)
  const [state, setState] = useState({ status: 'loading', a: null, b: null, error: null })
  const seq = useRef(0)

  const run = async (av, bv) => {
    const my = ++seq.current
    setState((s) => ({ ...s, status: 'loading' }))
    const [ra, rb] = await Promise.all([readSnapshot(av === '' ? null : av), readSnapshot(bv === '' ? null : bv)])
    if (my !== seq.current) return
    if (!ra.ok || !rb.ok) {
      setState({ status: 'failed', a: null, b: null, error: (!ra.ok ? ra.res : rb.res)?.error || null })
      return
    }
    setState({ status: 'ready', a: ra.data, b: rb.data, error: null })
  }

  useEffect(() => {
    run(aVal, bVal)
    return () => { seq.current += 1 }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [aVal, bVal])

  const cmp = useMemo(() => (state.status === 'ready' ? compareSnapshots(state.a, state.b) : null), [state])
  const same = aVal === bVal
  const labelOf = (v) => options.find((o) => o.value === v)?.label || v

  return (
    <ModalShell open onClose={onClose} title={t('pspd.cmp.title')} icon={ArrowLeftRight} size="lg" scrollBody closeLabel={t('app.dismiss')}
      footer={<Button type="button" variant="outline" className={TOUCH} onClick={onClose}>{t('app.dismiss')}</Button>}>
      <div data-slot="pspd-cmp" className="flex min-w-0 flex-col gap-3">
        <div className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
          <label className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
            <span>{t('pspd.cmp.a')}</span>
            <NativeSelect data-slot="pspd-cmp-a" value={aVal} onChange={(e) => setAVal(e.target.value)} className={cn('min-w-0', TOUCH)}>
              {options.map((o) => <NativeSelectOption key={`a-${o.value || 'latest'}`} value={o.value}>{o.label}</NativeSelectOption>)}
            </NativeSelect>
          </label>
          <label className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
            <span>{t('pspd.cmp.b')}</span>
            <NativeSelect data-slot="pspd-cmp-b" value={bVal} onChange={(e) => setBVal(e.target.value)} className={cn('min-w-0', TOUCH)}>
              {options.map((o) => <NativeSelectOption key={`b-${o.value || 'latest'}`} value={o.value}>{o.label}</NativeSelectOption>)}
            </NativeSelect>
          </label>
        </div>
        {same && <p data-slot="pspd-cmp-same" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.cmp.same')}</p>}
        {state.status === 'loading' && <LoadingBlock label={t('pspd.cmp.loading')} className="py-6" />}
        {state.status === 'failed' && (
          <div data-slot="pspd-cmp-failed">
            <AlertBanner tone="danger" className="mb-0"
              actions={<Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => run(aVal, bVal)}><RefreshCw aria-hidden="true" />{t('pspd.deep.retry')}</Button>}>
              {state.error || t('pspd.cmp.failed')}
            </AlertBanner>
          </div>
        )}
        {cmp && (
          <>
            <div data-slot="pspd-cmp-table" className="min-w-0 rounded-lg border">
              <Table className="table-fixed text-xs" aria-label={t('pspd.cmp.tableLabel')}>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className={cn(CELL, 'w-[28%] font-semibold')}>{t('pspd.cmp.metric')}</TableHead>
                    <TableHead className={cn(CELL, 'w-[22%] font-semibold')}>{labelOf(aVal)}</TableHead>
                    <TableHead className={cn(CELL, 'w-[22%] font-semibold')}>{labelOf(bVal)}</TableHead>
                    <TableHead className={cn(CELL, 'w-[28%] font-semibold')}>{t('pspd.cmp.delta')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {cmp.rows.map((r) => (
                    <TableRow key={r.key} className="hover:bg-transparent" data-slot="pspd-cmp-row" data-key={r.key}
                      data-worse={r.worse === null ? undefined : String(r.worse)}>
                      <TableHead scope="row" className={cn(CELL, 'font-normal text-muted-foreground')}>{t(`pspd.cmp.m.${r.key}`)}</TableHead>
                      <TableCell className={cn(CELL, 'tabular-nums')}>{fmt(r.key, r.a)}</TableCell>
                      <TableCell className={cn(CELL, 'tabular-nums')}>{fmt(r.key, r.b)}</TableCell>
                      <TableCell className={cn(CELL, 'tabular-nums',
                        r.worse === true ? 'text-warning' : r.worse === false && r.delta !== 0 ? 'text-success' : 'text-muted-foreground')}>
                        {deltaText(r.key, r)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {cmp.partial && <p data-slot="pspd-cmp-partial" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.cmp.partial')}</p>}
            {cmp.modeDiffers && <p data-slot="pspd-cmp-mode" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.cmp.modeDiffers')}</p>}
            {!cmp.resourcesKnown ? (
              <p data-slot="pspd-cmp-nores" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('pspd.cmp.noResources')}</p>
            ) : (
              <div className="grid min-w-0 grid-cols-1 gap-3 md:grid-cols-2">
                <DiffList kind="added" list={cmp.added} t={t} render={(x) => formatBytes(x.bytes)} />
                <DiffList kind="removed" list={cmp.removed} t={t} render={(x) => formatBytes(x.bytes)} />
                <DiffList kind="grown" list={cmp.grown} t={t} render={(x) => `${formatBytes(x.a)} → ${formatBytes(x.b)}`} />
                <DiffList kind="slower" list={cmp.slower} t={t} render={(x) => `${Math.round(x.a)} → ${Math.round(x.b)} ms`} />
              </div>
            )}
          </>
        )}
      </div>
    </ModalShell>
  )
}

function DiffList({ kind, list, t, render }) {
  return (
    <section data-slot="pspd-cmp-list" data-kind={kind} data-total={list.total} className="flex min-w-0 flex-col gap-1">
      <h4 className="m-0 text-xs font-semibold text-foreground">{t(`pspd.cmp.list.${kind}`, list.total)}</h4>
      {list.total === 0 ? (
        <p className="m-0 text-xs text-muted-foreground">{t('pspd.cmp.listNone')}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {list.items.map((x) => (
            <li key={x.url} className="flex min-w-0 flex-col rounded-md bg-muted/40 px-2 py-1 text-xs">
              <span className={cn('font-mono text-[11px] text-foreground', WRAP)}>{x.url}</span>
              <span className="tabular-nums text-muted-foreground">{t(`pspd.wf.type.${x.type}`)} · {render(x)}</span>
            </li>
          ))}
          {list.total > list.items.length && (
            <li className="text-xs text-muted-foreground">{t('pspd.cmp.listMore', list.total - list.items.length)}</li>
          )}
        </ul>
      )}
    </section>
  )
}
