import { lazy, Suspense, useMemo, useState } from 'react'
import { Boxes, ChevronDown, ListFilter, PanelRightOpen, Search, X } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import ToneBadge from '../../admin/ToneBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import {
  BUCKET_KEYS, CATEGORIES, DEFAULT_FILTERS, FINDING_CODES, FINDING_TONE, OBJECTS_TABLE_MIN_PX, SCOPES, SORTS, STATUS_FILTERS,
  activeFilterCount, cacheState, cacheText, categoryLabel, countsBy, dimensionsText, filterItems, findingLabel, formatBytesExact,
  formatMs, formatNumber, formatSize, isPageLoad, plural, problemText, recTextKey, recommendationArgs, sizeBucket, sortItems,
  statusText, verdictOf, verdictText, verdictTone, waterfallBar, waterfallTotal,
} from './resourceScanModel.js'
import { CATEGORY_ICON, CHIP, Section, SmallBadge, TOUCH, UrlText, WRAP } from './ResourceScanParts.jsx'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

// Şelale görünümü yalnız seçilince iner
const ResourceScanWaterfall = lazy(() => import('./ResourceScanWaterfall.jsx'))

/** Kap genişliği henüz ölçülmediyse (ilk çizim, 0) düzen pencere genişliğinden TAHMİN edilir — telefonda önce tablo
 *  çizilip karta dönmesin (r177 H14). Pencerenin kenar boşluğu payı ~96 px. jsdom 1024 → tablo. */
const guessCards = () => (typeof window !== 'undefined' && Number(window.innerWidth) > 0 ? window.innerWidth < OBJECTS_TABLE_MIN_PX + 96 : false)

/**
 * Kaynak analizi → NESNELER (2026-10-31; r177 yeniden düzen): sayfanın her nesnesi boyutu (insan okunur + tam bayt) ve
 * indirme süresiyle; varsayılan sıra BÜYÜKTEN KÜÇÜĞE, varsayılan kapsam SAYFA YÜKÜ. Süzgeçler: adres araması, tür, boyut
 * kovası, BULGU (sunucunun nesneye yazdığı öneri kodları — öneri sayısıyla aynı kural), kapsam, durum (kırık /
 * doğrulanamayan), bir önerinin nesneleri. Dar kapta (telefon / tablet) süzgeçler katlanır ("Süzgeçler (n)"); arama ve
 * sıralama hep görünür. "Liste | Şelale" anahtarı. Kap ≥ 900 px tablo, dar kapta kart. URL hiçbir yerde kesilmez.
 * Rozetler YALNIZ bulgulardan uyarı tonu alır; bilgi bayrakları (3. taraf, geç yüklenir, CSS'ten …) nötrdür.
 */
export default function ResourceScanObjects({
  scanId, items, timing, filters, onFiltersChange, itemsKept, detailRuns = 20, recommendations, mode = 'list', onModeChange,
  onOpenItem, countRef,
}) {
  const t = useT()
  const locale = useDateLocale()
  const [ref, width] = useElementWidth()
  const [filtersOpen, setFiltersOpen] = useState(false)
  const f = { ...DEFAULT_FILTERS, ...(filters || {}) }
  const set = (patch) => onFiltersChange?.({ ...f, ...patch })
  const list = useMemo(() => (Array.isArray(items) ? items : []), [items])
  const fKey = JSON.stringify([f.q, f.types, f.buckets, f.findings, f.scope, f.status, f.urls])

  const filtered = useMemo(() => sortItems(filterItems(list, f), f.sort, locale),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [list, fKey, f.sort, locale])
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const typeCounts = useMemo(() => countsBy(list, f, 'types'), [list, fKey])
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const bucketCounts = useMemo(() => countsBy(list, f, 'buckets'), [list, fKey])
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const findingCounts = useMemo(() => countsBy(list, f, 'findings'), [list, fKey])
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const scopeCounts = useMemo(() => countsBy(list, f, 'scope'), [list, fKey])
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const statusCounts = useMemo(() => countsBy(list, f, 'status'), [list, fKey])
  const presentTypes = useMemo(() => {
    const seen = new Set(list.map((it) => it?.category))
    return [...CATEGORIES.filter((c) => seen.has(c)), ...[...seen].filter((c) => c && !CATEGORIES.includes(c))]
  }, [list])
  const presentFindings = useMemo(() => {
    const seen = new Set(list.flatMap((it) => it?.findings || []))
    return FINDING_CODES.filter((c) => seen.has(c) || f.findings.includes(c))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list, f.findings.join('|')])
  const total = useMemo(() => waterfallTotal(list, timing?.load_ms), [list, timing?.load_ms])
  const pager = usePagination(filtered, { listKey: 'page-resource-scan-objects', preset: 'panel', resetDeps: [fKey, f.sort, scanId] })
  const active = activeFilterCount(f)
  const cards = width > 0 ? width < OBJECTS_TABLE_MIN_PX : guessCards()
  const clear = () => onFiltersChange?.({ ...DEFAULT_FILTERS, sort: f.sort })
  const rec = f.rec ? (recommendations || []).find((r) => r?.code === f.rec) : null
  const statusOptions = STATUS_FILTERS.filter((s) => s !== 'unverified' || (statusCounts.unverified || 0) > 0 || f.status === 'unverified')
  // Süzgeç alanındaki (katlanabilir) seçimlerin sayısı — arama ve sıralama dışı
  const hiddenActive = (f.types.length ? 1 : 0) + (f.buckets.length ? 1 : 0) + (f.findings.length ? 1 : 0)
    + (f.scope !== DEFAULT_FILTERS.scope ? 1 : 0) + (f.status !== 'all' ? 1 : 0)

  if (!itemsKept) {
    return (
      <div data-slot="prs-items-not-kept">
        <AlertBanner tone="info" className="mb-0" title={t('prs.obj.notKeptTitle')}>{plural(t, 'prs.obj.notKeptBody', detailRuns, formatNumber(detailRuns, locale))}</AlertBanner>
      </div>
    )
  }

  const filterRows = (
    <div data-slot="prs-filter-rows" className="flex min-w-0 flex-col gap-2.5">
      <FilterRow label={t('prs.obj.scope')}>
        <SegmentedControl value={f.scope} onChange={(v) => set({ scope: v })} ariaLabel={t('prs.obj.scope')}
          className="flex-wrap" options={SCOPES.map((s) => ({ value: s, label: t('prs.obj.scopeChip', t(`prs.scope.${s}`), formatNumber(scopeCounts[s] || 0, locale)) }))} />
      </FilterRow>
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
      {presentFindings.length > 0 && (
        <FilterRow label={t('prs.obj.findings')}>
          <ToggleGroup type="multiple" variant="outline" size="sm" spacing={1} data-slot="prs-finding-chips" className="flex-wrap"
            aria-label={t('prs.obj.findings')} value={f.findings} onValueChange={(v) => set({ findings: v, rec: null })}>
            {presentFindings.map((c) => {
              const n = findingCounts[c] || 0
              return (
                <ToggleGroupItem key={c} value={c} data-finding={c} data-count={n}
                  aria-label={t('prs.obj.typeChip', findingLabel(c, t), formatNumber(n, locale))}
                  className="h-8 gap-1.5 rounded-md px-2 text-xs font-medium data-[state=on]:bg-primary/10 data-[state=on]:text-primary pointer-coarse:h-10">
                  {findingLabel(c, t)}
                  <span className="text-muted-foreground tabular-nums">{formatNumber(n, locale)}</span>
                </ToggleGroupItem>
              )
            })}
          </ToggleGroup>
        </FilterRow>
      )}
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
      <FilterRow label={t('prs.obj.status')}>
        <SegmentedControl value={f.status} onChange={(v) => set({ status: v })} ariaLabel={t('prs.obj.status')}
          className="flex-wrap" options={statusOptions.map((s) => ({ value: s, label: t(`prs.statusFilter.${s === 'failed' ? 'broken' : s}`) }))} />
      </FilterRow>
    </div>
  )

  return (
    <Section slot="prs-objects" icon={Boxes} title={t('prs.obj.title')} titleFocusable
      description={t('prs.obj.desc')}
      actions={(
        <>
          {onModeChange && (
            <span data-slot="prs-objects-mode" data-mode={mode}>
              <SegmentedControl value={mode} onChange={onModeChange} ariaLabel={t('prs.obj.mode')}
                options={[{ value: 'list', label: t('prs.obj.modeList') }, { value: 'waterfall', label: t('prs.obj.modeWaterfall') }]} />
            </span>
          )}
          {active > 0 && (
            <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="prs-clear-filters" onClick={clear}>
              <X aria-hidden="true" />{t('prs.obj.clear', active)}
            </Button>
          )}
        </>
      )}>
      <div data-slot="prs-objects-toolbar" className="flex min-w-0 flex-col gap-2.5">
        <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
          <InputGroup className="w-full pointer-coarse:h-11 sm:max-w-sm">
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            <InputGroupInput type="search" value={f.q} data-slot="prs-search" aria-label={t('prs.obj.search')}
              placeholder={t('prs.obj.searchPh')} onChange={(e) => set({ q: e.target.value })} className="pointer-coarse:h-10" />
          </InputGroup>
          {mode !== 'waterfall' && (
            <label className="flex min-w-0 items-center gap-2 text-xs font-medium text-muted-foreground">
              <span className="shrink-0">{t('prs.obj.sort')}</span>
              <NativeSelect value={f.sort} data-slot="prs-sort" onChange={(e) => set({ sort: e.target.value })} className="pointer-coarse:h-10">
                {SORTS.map((s) => <NativeSelectOption key={s} value={s}>{t(`prs.sort.${s}`)}</NativeSelectOption>)}
              </NativeSelect>
            </label>
          )}
        </div>
        {cards ? (
          // Dar kap (r177 inceleme): süzgeç alanı ekranın tamamını kaplıyordu — katlanır; seçili süzgeç sayısı tetikte
          <Collapsible open={filtersOpen} onOpenChange={setFiltersOpen} data-slot="prs-filters-collapsible">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="outline" size="sm" data-slot="prs-filters-toggle" aria-expanded={filtersOpen}
                className={cn('group w-full justify-between sm:w-auto', TOUCH)}>
                <span className="inline-flex items-center gap-1.5"><ListFilter aria-hidden="true" />{t('prs.obj.filters', hiddenActive)}</span>
                <ChevronDown aria-hidden="true" className="transition-transform group-data-[state=open]:rotate-180" />
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent className="pt-2.5">{filterRows}</CollapsibleContent>
          </Collapsible>
        ) : filterRows}
        {rec && (
          <div data-slot="prs-rec-filter" data-code={rec.code} className="flex flex-wrap items-center gap-2 text-xs">
            <ToneBadge tone="info" className={CHIP}>{t('prs.obj.recFilter', t(recTextKey(rec, 'title'), ...recommendationArgs(rec, locale, 'title')))}</ToneBadge>
            <Button type="button" variant="ghost" size="sm" className={TOUCH} onClick={() => set({ findings: [], rec: null, urls: [] })}>
              <X aria-hidden="true" />{t('prs.obj.urlsFilterClear')}
            </Button>
          </div>
        )}
        {f.urls.length > 0 && !rec && (
          <div data-slot="prs-urls-filter" className="flex flex-wrap items-center gap-2 text-xs">
            <ToneBadge tone="info" className={CHIP}>{plural(t, 'prs.obj.urlsFilter', f.urls.length, formatNumber(f.urls.length, locale))}</ToneBadge>
            <Button type="button" variant="ghost" size="sm" className={TOUCH} onClick={() => set({ urls: [] })}>
              <X aria-hidden="true" />{t('prs.obj.urlsFilterClear')}
            </Button>
          </div>
        )}
        <p data-slot="prs-count" ref={countRef} tabIndex={-1} role="status" aria-live="polite"
          className="m-0 text-xs text-muted-foreground tabular-nums focus-visible:rounded-sm focus-visible:outline-2 focus-visible:outline-ring">
          {plural(t, 'prs.obj.count', list.length, formatNumber(filtered.length, locale), formatNumber(list.length, locale))}
        </p>
      </div>

      {mode === 'waterfall' ? (
        <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="py-6" />}>
          <ResourceScanWaterfall items={filtered} loadMs={timing?.load_ms} onOpenItem={onOpenItem} />
        </Suspense>
      ) : (
        <>
          <div ref={ref} data-slot="prs-objects-list" data-layout={cards ? 'cards' : 'table'} className="flex min-w-0 flex-col gap-2">
            {list.length === 0 ? (
              <StatusBlock tone="neutral" icon={Boxes} title={t('prs.obj.none')} className="py-6" />
            ) : filtered.length === 0 ? (
              <div data-slot="prs-objects-nomatch">
                <StatusBlock tone="neutral" icon={Search} title={t('prs.obj.noMatch')} description={t('prs.obj.noMatchHint')} className="py-6"
                  actions={<Button type="button" variant="outline" className={TOUCH} onClick={clear}>{t('prs.obj.clearShort')}</Button>} />
              </div>
            ) : cards ? (
              <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0">
                {pager.pageItems.map((it, i) => <ObjectCard key={`${it.url}#${i}`} item={it} t={t} locale={locale} onOpen={onOpenItem} />)}
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
                      <TableHead className="w-[11%] whitespace-normal">{t('prs.col.status')}</TableHead>
                      <TableHead className="w-[15%] whitespace-normal">{t('prs.col.waterfall')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {pager.pageItems.map((it, i) => <ObjectRow key={`${it.url}#${i}`} item={it} total={total} t={t} locale={locale} onOpen={onOpenItem} />)}
                  </TableBody>
                </Table>
              </div>
            )}
          </div>
          {filtered.length > 0 && <PaginationBar {...pager} />}
        </>
      )}
    </Section>
  )
}

function FilterRow({ label, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="shrink-0 text-[11px] font-semibold tracking-[.04em] text-muted-foreground uppercase max-sm:text-xs">{label}</span>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

/**
 * Rozetler (r177 H2): UYARI tonu YALNIZ bulgulardan (sunucunun nesneye yazdığı öneri kodları — önerideki sayıyla aynı
 * kural); bilgi bayrakları nötr: 3. taraf, geç yüklenir, CSS'ten, önbellek süresi (bulgu yoksa), sayfa boyutuna sayılmaz.
 */
export function Flags({ item, t, locale }) {
  const findings = Array.isArray(item.findings) ? item.findings : []
  const out = findings.map((c) => [`f-${c}`, FINDING_TONE[c] || 'muted', findingLabel(c, t), c])
  if (item.third_party === true) out.push(['third', 'muted', t('prs.obj.thirdParty')])
  if (item.lazy === true) out.push(['lazy', 'info', t('prs.obj.lazy')])
  if (item.from_css === true) out.push(['css', 'muted', t('prs.obj.fromCss')])
  if (isPageLoad(item) && verdictOf(item) === 'ok' && !findings.includes('NO_CACHE') && cacheState(item.cache_ttl_s) === 'ttl') {
    out.push(['cache', 'muted', cacheText(item, t, locale)])
  }
  if (Number(item.redirects) > 0 && !findings.includes('REDIRECTED_OBJECTS')) {
    out.push(['redirect', 'muted', plural(t, 'prs.obj.redirects', item.redirects, formatNumber(item.redirects, locale))])
  }
  if (!isPageLoad(item)) out.push(['notCounted', 'muted', t('prs.obj.notCounted')])
  if (!out.length) return null
  return (
    <span data-slot="prs-obj-flags" className="flex flex-wrap gap-1">
      {out.map(([k, tone, text, code]) => (
        <SmallBadge key={k} tone={tone} data-flag={code ? undefined : k} data-finding={code}>{text}</SmallBadge>
      ))}
    </span>
  )
}

function StatusCell({ item, t }) {
  const v = verdictOf(item)
  const vt = verdictText(item, t)
  return (
    <span className="flex flex-col items-start gap-1">
      <ToneBadge tone={verdictTone(item)} data-slot="prs-obj-status" data-failed={v === 'broken' ? 'true' : 'false'} data-verdict={v}
        className={cn(CHIP, 'font-semibold tabular-nums')}>
        {statusText(item, t)}
      </ToneBadge>
      {vt && <span data-slot="prs-obj-verdict" className={cn('text-[11px] text-muted-foreground', WRAP)}>{vt}</span>}
    </span>
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

function DetailButton({ item, t, onOpen, className }) {
  if (!onOpen) return null
  return (
    <Button type="button" variant="outline" size="sm" data-slot="prs-obj-open" onClick={() => onOpen(item)}
      aria-label={t('prs.od.openFor', item.url || '—')} className={cn('h-7 w-fit px-2 text-xs', TOUCH, className)}>
      <PanelRightOpen aria-hidden="true" />{t('prs.od.open')}
    </Button>
  )
}

function ObjectRow({ item, total, t, locale, onOpen }) {
  const bar = waterfallBar(item, total)
  const dim = dimensionsText(item)
  const broken = verdictOf(item) === 'broken'
  return (
    <TableRow data-slot="prs-obj" data-category={item.category} data-bucket={sizeBucket(item.bytes) || undefined}>
      <TableCell className="align-top whitespace-normal">
        <div className="flex min-w-0 flex-col gap-1">
          <UrlText url={item.url} t={t} className="text-xs" />
          <span className={cn('text-[11px] text-muted-foreground', WRAP)}>
            {[item.content_type, dim ? t('prs.img.dims', dim) : null].filter(Boolean).join(' · ')}
          </span>
          {item.error && <span data-slot="prs-obj-problem" className={cn('text-[11px]', broken ? 'text-destructive' : 'text-muted-foreground', WRAP)}>{problemText(item.error, t)}</span>}
          <Flags item={item} t={t} locale={locale} />
          <DetailButton item={item} t={t} onOpen={onOpen} />
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
          <span data-slot="prs-waterfall" className="relative mt-1 block h-2.5 w-full rounded-full bg-muted">
            <span className={cn('absolute inset-y-0 rounded-full', broken ? 'bg-destructive/70' : 'bg-primary/70')}
              style={{ left: `${bar.left}%`, width: `${bar.width}%` }} />
            {/* Dokunmatikte ve ekran okuyucuda `title` yok (r177 H15): metin de yazılır */}
            <span className="sr-only">{t('prs.obj.waterfallTip', formatMs(item.start_ms, locale), formatMs(item.duration_ms, locale))}</span>
          </span>
        ) : null}
        <span data-slot="prs-waterfall-text" className="mt-1 block text-[11px] text-muted-foreground tabular-nums" aria-hidden={bar ? 'true' : undefined}>
          {bar ? t('prs.obj.waterfallTip', formatMs(item.start_ms, locale), formatMs(item.duration_ms, locale)) : t('prs.obj.noWaterfall')}
        </span>
      </TableCell>
    </TableRow>
  )
}

function ObjectCard({ item, t, locale, onOpen }) {
  const dim = dimensionsText(item)
  const broken = verdictOf(item) === 'broken'
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
      {item.error && <span data-slot="prs-obj-problem" className={cn('text-xs', broken ? 'text-destructive' : 'text-muted-foreground', WRAP)}>{problemText(item.error, t)}</span>}
      <Flags item={item} t={t} locale={locale} />
      <DetailButton item={item} t={t} onOpen={onOpen} />
    </li>
  )
}
