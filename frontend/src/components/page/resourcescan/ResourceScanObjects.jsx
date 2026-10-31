import { useMemo } from 'react'
import { Boxes, ExternalLink, Search, X } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import {
  BUCKET_KEYS, CATEGORIES, DEFAULT_FILTERS, OBJECTS_TABLE_MIN_PX, SCOPES, SORTS, STATUS_FILTERS, TEXT_CATEGORIES,
  activeFilterCount, cacheState, cacheText, categoryLabel, countsBy, dimensionsText, filterItems, formatBytesExact, formatMs,
  formatNumber, formatSize, isFailed, isPageLoad, plural, sizeBucket, sortItems, statusText, waterfallBar, waterfallTotal,
} from './resourceScanModel.js'
import { CATEGORY_ICON, CHIP, Section, TOUCH, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Kaynak analizi → NESNELER (2026-10-31): sayfanın her nesnesi (HTML, CSS, JS, görsel, yazı tipi, medya, belge, bağlantı …)
 * boyutu (insan okunur + tam bayt) ve indirme süresiyle; varsayılan sıra BÜYÜKTEN KÜÇÜĞE. Süzgeçler: adres araması, tür
 * çipleri (çoklu, sayaçlı), boyut kovası çipleri, kapsam (sayfa yükü / belgeler / bağlantılar / tümü), durum (başarısız),
 * bir önerinin nesneleri. Kap ≥ 900 px tablo (şelale çubuğuyla), dar kapta kart. URL hiçbir yerde kesilmez (sarar).
 */
export default function ResourceScanObjects({ scanId, items, timing, filters, onFiltersChange, itemsKept, detailRuns = 20 }) {
  const t = useT()
  const locale = useDateLocale()
  const [ref, width] = useElementWidth()
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  const set = (patch) => onFiltersChange?.({ ...f, ...patch })
  const list = useMemo(() => (Array.isArray(items) ? items : []), [items])

  const filtered = useMemo(() => sortItems(filterItems(list, f), f.sort, locale),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, f.q, f.types.join('|'), f.buckets.join('|'), f.scope, f.status, f.sort, f.urls.join('|'), locale])
  const typeCounts = useMemo(() => countsBy(list, f, 'types'),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, f.q, f.buckets.join('|'), f.scope, f.status, f.urls.join('|')])
  const bucketCounts = useMemo(() => countsBy(list, f, 'buckets'),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, f.q, f.types.join('|'), f.scope, f.status, f.urls.join('|')])
  const presentTypes = useMemo(() => {
    const seen = new Set(list.map((it) => it?.category))
    return [...CATEGORIES.filter((c) => seen.has(c)), ...[...seen].filter((c) => c && !CATEGORIES.includes(c))]
  }, [list])
  const total = useMemo(() => waterfallTotal(list, timing?.load_ms), [list, timing?.load_ms])
  const filtersKey = JSON.stringify([f.q, f.types, f.buckets, f.scope, f.status, f.sort, f.urls, scanId])
  const pager = usePagination(filtered, { listKey: 'page-resource-scan-objects', preset: 'panel', resetDeps: [filtersKey] })
  const active = activeFilterCount(f)
  const cards = width > 0 && width < OBJECTS_TABLE_MIN_PX

  if (!itemsKept) {
    return (
      <div data-slot="prs-items-not-kept">
        <AlertBanner tone="info" className="mb-0" title={t('prs.obj.notKeptTitle')}>{plural(t, 'prs.obj.notKeptBody', detailRuns, formatNumber(detailRuns, locale))}</AlertBanner>
      </div>
    )
  }

  return (
    <Section slot="prs-objects" icon={Boxes} title={t('prs.obj.title')}
      description={t('prs.obj.desc')}
      actions={active > 0 && (
        <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="prs-clear-filters"
          onClick={() => onFiltersChange?.({ ...DEFAULT_FILTERS, sort: f.sort })}>
          <X aria-hidden="true" />{t('prs.obj.clear', active)}
        </Button>
      )}>
      <div data-slot="prs-objects-toolbar" className="flex min-w-0 flex-col gap-2.5">
        <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
          <InputGroup className="w-full pointer-coarse:h-11 sm:max-w-sm">
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            <InputGroupInput type="search" value={f.q} data-slot="prs-search" aria-label={t('prs.obj.search')}
              placeholder={t('prs.obj.searchPh')} onChange={(e) => set({ q: e.target.value })} className="pointer-coarse:h-10" />
          </InputGroup>
          <label className="flex min-w-0 items-center gap-2 text-xs font-medium text-muted-foreground">
            <span className="shrink-0">{t('prs.obj.sort')}</span>
            <NativeSelect value={f.sort} data-slot="prs-sort" onChange={(e) => set({ sort: e.target.value })} className="pointer-coarse:h-10">
              {SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`prs.sort.${s}`)}</NativeSelectOption>)}
            </NativeSelect>
          </label>
        </div>
        <FilterRow label={t('prs.obj.types')}>
          <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1} data-slot="prs-type-chips" className="flex-wrap"
            aria-label={t('prs.obj.types')} value={f.types} onValueChange={(v) => set({ types: v })}>
            {presentTypes.map((c) => {
              const Icon = CATEGORY_ICON[c] || CATEGORY_ICON.OTHER
              const n = typeCounts[c] || 0
              return (
                <ToggleGroupItem key={c} value={c} data-category={c} data-count={n}
                  aria-label={t('prs.obj.typeChip', categoryLabel(c, t), formatNumber(n, locale))}
                  className="h-8 gap-1.5 rounded-md px-2 text-xs font-medium data-[state=on]:bg-primary/10 data-[state=on]:text-primary pointer-coarse:h-10">
                  <Icon aria-hidden="true" />{categoryLabel(c, t)}
                  <span className="text-muted-foreground tabular-nums">{formatNumber(n, locale)}</span>
                </ToggleGroupItem>
              )
            })}
          </ToggleGroup>
        </FilterRow>
        <FilterRow label={t('prs.obj.sizes')}>
          <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1} data-slot="prs-bucket-chips" className="flex-wrap"
            aria-label={t('prs.obj.sizes')} value={f.buckets} onValueChange={(v) => set({ buckets: v })}>
            {BUCKET_KEYS.map((b) => {
              const n = bucketCounts[b] || 0
              return (
                <ToggleGroupItem key={b} value={b} data-bucket={b} data-count={n}
                  aria-label={t('prs.obj.bucketChip', t(`prs.bucket.${b}`), formatNumber(n, locale))}
                  className="h-8 gap-1.5 rounded-md px-2 text-xs font-medium data-[state=on]:bg-primary/10 data-[state=on]:text-primary pointer-coarse:h-10">
                  {t(`prs.bucket.${b}`)}
                  <span className="text-muted-foreground tabular-nums">{formatNumber(n, locale)}</span>
                </ToggleGroupItem>
              )
            })}
          </ToggleGroup>
        </FilterRow>
        <div className="flex min-w-0 flex-col gap-2 md:flex-row md:flex-wrap md:items-center md:gap-4">
          <FilterRow label={t('prs.obj.scope')} inline>
            <SegmentedControl value={f.scope} onChange={(v) => set({ scope: v })} ariaLabel={t('prs.obj.scope')}
              className="flex-wrap" options={SCOPES.map((s) => ({ value: s, label: t(`prs.scope.${s}`) }))} />
          </FilterRow>
          <FilterRow label={t('prs.obj.status')} inline>
            <SegmentedControl value={f.status} onChange={(v) => set({ status: v })} ariaLabel={t('prs.obj.status')}
              className="flex-wrap" options={STATUS_FILTERS.map((s) => ({ value: s, label: t(s === 'failed' ? 'prs.statusFilter.broken' : 'prs.statusFilter.all') }))} />
          </FilterRow>
        </div>
        {f.urls.length > 0 && (
          <div data-slot="prs-urls-filter" className="flex flex-wrap items-center gap-2 text-xs">
            <ToneBadge tone="info" className={CHIP}>{plural(t, 'prs.obj.urlsFilter', f.urls.length, formatNumber(f.urls.length, locale))}</ToneBadge>
            <Button type="button" variant="ghost" size="sm" className={TOUCH} onClick={() => set({ urls: [] })}>
              <X aria-hidden="true" />{t('prs.obj.urlsFilterClear')}
            </Button>
          </div>
        )}
        <p data-slot="prs-count" role="status" aria-live="polite" className="m-0 text-xs text-muted-foreground tabular-nums">
          {plural(t, 'prs.obj.count', list.length, formatNumber(filtered.length, locale), formatNumber(list.length, locale))}
        </p>
      </div>

      <div ref={ref} data-slot="prs-objects-list" data-layout={cards ? 'cards' : 'table'} className="flex min-w-0 flex-col gap-2">
        {list.length === 0 ? (
          <StatusBlock tone="neutral" icon={Boxes} title={t('prs.obj.none')} className="py-6" />
        ) : filtered.length === 0 ? (
          <div data-slot="prs-objects-nomatch">
            <StatusBlock tone="neutral" icon={Search} title={t('prs.obj.noMatch')} description={t('prs.obj.noMatchHint')} className="py-6"
              actions={<Button type="button" variant="outline" className={TOUCH} onClick={() => onFiltersChange?.({ ...DEFAULT_FILTERS, sort: f.sort })}>
                {t('prs.obj.clearShort')}</Button>} />
          </div>
        ) : cards ? (
          <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0">
            {pager.pageItems.map((it, i) => <ObjectCard key={`${it.url}#${i}`} item={it} t={t} locale={locale} />)}
          </ul>
        ) : (
          <div className="overflow-hidden rounded-xl border" data-slot="prs-objects-table">
            <Table className="table-fixed">
              <TableHeader>
                <TableRow>
                  <TableHead className="whitespace-normal">{t('prs.col.object')}</TableHead>
                  <TableHead className="w-[12%] whitespace-normal">{t('prs.col.type')}</TableHead>
                  <TableHead className="w-[13%] whitespace-normal text-right">{t('prs.col.size')}</TableHead>
                  <TableHead className="w-[10%] whitespace-normal text-right">{t('prs.col.time')}</TableHead>
                  <TableHead className="w-[9%] whitespace-normal">{t('prs.col.status')}</TableHead>
                  <TableHead className="w-[16%] whitespace-normal">{t('prs.col.waterfall')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pager.pageItems.map((it, i) => <ObjectRow key={`${it.url}#${i}`} item={it} total={total} t={t} locale={locale} />)}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
      {filtered.length > 0 && <PaginationBar {...pager} />}
    </Section>
  )
}

function FilterRow({ label, inline = false, children }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-1', inline && 'sm:flex-row sm:items-center sm:gap-2')}>
      <span className="shrink-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{label}</span>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

/** Güvenli bağlantı: yalnız http(s) adresi tıklanır (javascript: / data: şeması bağlanmaz). */
function UrlText({ url, t, className }) {
  const safe = typeof url === 'string' && /^https?:\/\//i.test(url)
  if (!safe) return <span className={cn(WRAP, className)}>{url || '—'}</span>
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" data-slot="prs-obj-url"
      aria-label={t('prs.obj.openUrl', url)}
      className={cn('text-foreground underline-offset-2 hover:underline focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring', WRAP, className)}>
      {url}
      <ExternalLink aria-hidden="true" className="ml-1 inline size-3 align-[-1px] text-muted-foreground" />
    </a>
  )
}

/** Küçük bayraklar: 3. taraf, geç yüklenir, CSS'ten, sıkıştırılmamış metin, önbellek, yönlendirme. */
function Flags({ item, t, locale }) {
  const flags = []
  if (item.third_party === true) flags.push(['third', 'muted', t('prs.obj.thirdParty')])
  if (item.lazy === true) flags.push(['lazy', 'info', t('prs.obj.lazy')])
  if (item.from_css === true) flags.push(['css', 'muted', t('prs.obj.fromCss')])
  if (item.compressed === false && TEXT_CATEGORIES.has(item.category)) flags.push(['uncompressed', 'warning', t('prs.obj.uncompressed')])
  if (isPageLoad(item) && !isFailed(item)) {
    const st = cacheState(item.cache_ttl_s)
    flags.push(['cache', st === 'ttl' ? 'muted' : 'warning', cacheText(item, t, locale)])
  }
  if (Number(item.redirects) > 0) flags.push(['redirect', 'warning', plural(t, 'prs.obj.redirects', item.redirects, formatNumber(item.redirects, locale))])
  if (!isPageLoad(item)) flags.push(['notCounted', 'muted', t('prs.obj.notCounted')])
  if (!flags.length) return null
  return (
    <span data-slot="prs-obj-flags" className="flex flex-wrap gap-1">
      {flags.map(([k, tone, text]) => (
        <ToneBadge key={k} tone={tone} data-flag={k} className={cn(CHIP, 'px-1.5 py-0 text-[11px] font-medium')}>{text}</ToneBadge>
      ))}
    </span>
  )
}

function StatusCell({ item, t }) {
  const failed = isFailed(item)
  return (
    <ToneBadge tone={failed ? 'danger' : 'muted'} data-slot="prs-obj-status" data-failed={failed ? 'true' : 'false'}
      className={cn(CHIP, 'font-semibold tabular-nums')}>
      {statusText(item, t)}
    </ToneBadge>
  )
}

function SizeText({ item, locale }) {
  return (
    <span className="flex flex-col items-end gap-0.5 tabular-nums">
      <span className="font-semibold text-foreground">{formatSize(item.bytes, { truncated: item.truncated === true, locale })}</span>
      <span className="text-[11px] text-muted-foreground">{formatBytesExact(item.bytes, locale)}</span>
    </span>
  )
}

function TypeText({ item, t }) {
  const Icon = CATEGORY_ICON[item.category] || CATEGORY_ICON.OTHER
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      <Icon aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
      <span className={WRAP}>{categoryLabel(item.category, t)}</span>
    </span>
  )
}

function ObjectRow({ item, total, t, locale }) {
  const bar = waterfallBar(item, total)
  const dim = dimensionsText(item)
  return (
    <TableRow data-slot="prs-obj" data-category={item.category} data-bucket={sizeBucket(item.bytes) || undefined}>
      <TableCell className="align-top whitespace-normal">
        <div className="flex min-w-0 flex-col gap-1">
          <UrlText url={item.url} t={t} className="text-xs" />
          <span className={cn('text-[11px] text-muted-foreground', WRAP)}>
            {[item.content_type, dim ? t('prs.img.dims', dim) : null].filter(Boolean).join(' · ')}
          </span>
          {item.error && <span className={cn('text-[11px] text-destructive', WRAP)}>{item.error}</span>}
          <Flags item={item} t={t} locale={locale} />
        </div>
      </TableCell>
      <TableCell className="align-top text-xs whitespace-normal"><TypeText item={item} t={t} /></TableCell>
      <TableCell className="align-top text-right text-xs"><SizeText item={item} locale={locale} /></TableCell>
      <TableCell className="align-top text-right text-xs tabular-nums">
        <span className="block font-semibold">{formatMs(item.duration_ms, locale)}</span>
        {item.ttfb_ms != null && <span className="block text-[11px] text-muted-foreground">{t('prs.obj.ttfb', formatMs(item.ttfb_ms, locale))}</span>}
      </TableCell>
      <TableCell className="align-top whitespace-normal"><StatusCell item={item} t={t} /></TableCell>
      <TableCell className="align-top whitespace-normal">
        {bar ? (
          <span data-slot="prs-waterfall" className="relative mt-1 block h-2.5 w-full rounded-full bg-muted"
            title={t('prs.obj.waterfallTip', formatMs(item.start_ms, locale), formatMs(item.duration_ms, locale))}>
            <span className={cn('absolute inset-y-0 rounded-full', isFailed(item) ? 'bg-destructive/70' : 'bg-primary/70')}
              style={{ left: `${bar.left}%`, width: `${bar.width}%` }} />
          </span>
        ) : (
          <span className="text-[11px] text-muted-foreground">{t('prs.obj.noWaterfall')}</span>
        )}
      </TableCell>
    </TableRow>
  )
}

function ObjectCard({ item, t, locale }) {
  const dim = dimensionsText(item)
  return (
    <li data-slot="prs-obj" data-category={item.category} data-bucket={sizeBucket(item.bytes) || undefined}
      className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card px-3 py-2.5">
      <UrlText url={item.url} t={t} className="text-sm" />
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
        <TypeText item={item} t={t} />
        <StatusCell item={item} t={t} />
        {dim && <span className="text-muted-foreground">{t('prs.img.dims', dim)}</span>}
      </div>
      <div className="grid grid-cols-2 gap-2 text-xs">
        <div className="flex flex-col gap-0.5">
          <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t('prs.col.size')}</span>
          <span className="font-semibold tabular-nums">{formatSize(item.bytes, { truncated: item.truncated === true, locale })}</span>
          <span className="text-[11px] text-muted-foreground tabular-nums">{formatBytesExact(item.bytes, locale)}</span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{t('prs.col.time')}</span>
          <span className="font-semibold tabular-nums">{formatMs(item.duration_ms, locale)}</span>
          {item.ttfb_ms != null && <span className="text-[11px] text-muted-foreground tabular-nums">{t('prs.obj.ttfb', formatMs(item.ttfb_ms, locale))}</span>}
        </div>
      </div>
      {item.content_type && <span className={cn('text-[11px] text-muted-foreground', WRAP)}>{item.content_type}</span>}
      {item.error && <span className={cn('text-xs text-destructive', WRAP)}>{item.error}</span>}
      <Flags item={item} t={t} locale={locale} />
    </li>
  )
}
