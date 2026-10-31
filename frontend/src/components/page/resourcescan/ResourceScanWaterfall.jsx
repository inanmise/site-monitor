import { useMemo, useState } from 'react'
import { PanelRightOpen } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import {
  WATERFALL_SORTS, categoryLabel, formatMs, formatNumber, formatSize, niceMsTicks, plural, verdictOf, waterfallRows,
  waterfallSegments, waterfallTotal,
} from './resourceScanModel.js'
import { CATEGORY_ICON, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Bir seferde çizilen satır (fazlası "Daha fazla göster" ile — 1.500 nesnelik sayfa ekranı kilitlemesin). */
const STEP = 100

/**
 * Kaynak analizi → ŞELALE (r177): yükleme tahminine giren nesneler başlangıç zamanına göre (ya da süre / bitiş),
 * zaman ekseniyle; her çubukta BEKLEME (ilk bayta kadar, TTFB) ve İNDİRME ayrı tonda, tahmini yüklenme sonu kesikli
 * çizgi. Satır = adres (sarar) üstte, çubuk altta tam genişlik — telefonda da yatay kaydırma yok. Geç yüklenen görseller,
 * belgeler ve bağlantılar tahmine girmez (sayısı yazılır). Her satırda "Ayrıntı" (nesne penceresi).
 */
export default function ResourceScanWaterfall({ items, loadMs, onOpenItem }) {
  const t = useT()
  const locale = useDateLocale()
  const [sort, setSort] = useState('start')
  const [shown, setShown] = useState(STEP)
  const { rows, excluded } = useMemo(() => waterfallRows(items, sort), [items, sort])
  const total = useMemo(() => waterfallTotal(rows, loadMs), [rows, loadMs])
  const axis = useMemo(() => niceMsTicks(total, 5), [total])
  const load = Number(loadMs) > 0 ? Number(loadMs) : null
  const loadPct = load != null && axis.top > 0 ? Math.min(100, (load / axis.top) * 100) : null
  const visible = rows.slice(0, shown)

  return (
    <div data-slot="prs-wf" className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
        <span data-slot="prs-wf-sort" className="flex min-w-0 flex-wrap items-center gap-2 text-xs font-medium text-muted-foreground">
          <span>{t('prs.wf.sort')}</span>
          <SegmentedControl value={sort} onChange={(v) => { setSort(v); setShown(STEP) }} ariaLabel={t('prs.wf.sort')} className="flex-wrap"
            options={WATERFALL_SORTS.map((s) => ({ value: s, label: t(`prs.wf.sort.${s}`) }))} />
        </span>
        <span data-slot="prs-wf-legend" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="inline-block h-2.5 w-4 rounded-sm bg-primary/30" />{t('prs.wf.wait')}</span>
          <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="inline-block h-2.5 w-4 rounded-sm bg-primary/75" />{t('prs.wf.download')}</span>
          {loadPct != null && (
            <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className="inline-block h-3 w-0 border-l-2 border-dashed border-amber-600" />{t('prs.wf.loadEnd', formatMs(load, locale))}</span>
          )}
        </span>
      </div>
      {excluded > 0 && (
        <p data-slot="prs-wf-excluded" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
          {plural(t, 'prs.wf.excluded', excluded, formatNumber(excluded, locale))}
        </p>
      )}
      {rows.length === 0 ? (
        <StatusBlock tone="neutral" title={t('prs.wf.none')} description={t('prs.wf.noneHint')} className="py-6" />
      ) : (
        <>
          {/* Eksen: aynı yatay ölçek her satırın çubuk alanında */}
          <div data-slot="prs-wf-axis" aria-hidden="true" className="relative h-5 border-b text-[11px] text-muted-foreground tabular-nums">
            {axis.ticks.map((v, i) => {
              const left = axis.top > 0 ? (v / axis.top) * 100 : 0
              const last = i === axis.ticks.length - 1
              return (
                <span key={v} className={cn('absolute top-0 whitespace-nowrap', last ? '-translate-x-full' : i === 0 ? '' : '-translate-x-1/2')}
                  style={{ left: `${left}%` }}>{formatMs(v, locale)}</span>
              )
            })}
          </div>
          <ol data-slot="prs-wf-rows" className="m-0 flex list-none flex-col gap-1.5 p-0">
            {visible.map((it, i) => <WaterfallRow key={`${it.url}#${i}`} item={it} max={axis.top} loadPct={loadPct} t={t} locale={locale} onOpen={onOpenItem} />)}
          </ol>
          {rows.length > shown && (
            <Button type="button" variant="outline" size="sm" data-slot="prs-wf-more" className={cn('w-fit', TOUCH)}
              onClick={() => setShown((n) => n + STEP)}>
              {t('prs.wf.more', formatNumber(Math.min(STEP, rows.length - shown), locale), formatNumber(rows.length - shown, locale))}
            </Button>
          )}
        </>
      )}
    </div>
  )
}

function WaterfallRow({ item, max, loadPct, t, locale, onOpen }) {
  const seg = waterfallSegments(item, max)
  const broken = verdictOf(item) === 'broken'
  const Icon = CATEGORY_ICON[item.category] || CATEGORY_ICON.OTHER
  const tip = t('prs.wf.rowTip', formatMs(item.start_ms, locale), formatMs(item.ttfb_ms, locale), formatMs(item.duration_ms, locale))
  return (
    <li data-slot="prs-wf-row" data-category={item.category} className="flex min-w-0 flex-col gap-1 rounded-md border px-2.5 py-1.5">
      <div className="flex min-w-0 items-start gap-2">
        <Icon aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
        <span className={cn('min-w-0 flex-1 text-xs text-foreground', WRAP)}>
          <span className="sr-only">{categoryLabel(item.category, t)}: </span>{item.url}
        </span>
        <span className="shrink-0 text-xs font-semibold tabular-nums">{formatMs(item.duration_ms, locale)}</span>
      </div>
      <div className="relative h-3 w-full rounded-sm bg-muted/60">
        {loadPct != null && <span aria-hidden="true" className="absolute inset-y-[-2px] w-0 border-l-2 border-dashed border-amber-600" style={{ left: `${loadPct}%` }} />}
        {seg && (
          <span aria-hidden="true" className="absolute inset-y-0 flex" style={{ left: `${seg.left}%`, width: `${seg.total}%` }}>
            {seg.wait > 0 && <span className={cn('h-full rounded-l-sm', broken ? 'bg-destructive/35' : 'bg-primary/30')} style={{ width: `${(seg.wait / seg.total) * 100}%` }} />}
            <span className={cn('h-full flex-1', seg.wait > 0 ? 'rounded-r-sm' : 'rounded-sm', broken ? 'bg-destructive/75' : 'bg-primary/75')} />
          </span>
        )}
      </div>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span data-slot="prs-wf-tip" className={cn('text-[11px] text-muted-foreground tabular-nums', WRAP)}>
          {tip} · {formatSize(item.bytes, { truncated: item.truncated === true, locale })}
        </span>
        {onOpen && (
          <Button type="button" variant="ghost" size="sm" data-slot="prs-obj-open" onClick={() => onOpen(item)}
            aria-label={t('prs.od.openFor', item.url || '—')} className={cn('h-7 px-2 text-xs', TOUCH)}>
            <PanelRightOpen aria-hidden="true" />{t('prs.od.open')}
          </Button>
        )}
      </div>
    </li>
  )
}
