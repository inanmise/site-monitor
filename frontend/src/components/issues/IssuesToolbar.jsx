import { useEffect, useRef, useState } from 'react'
import { Search, SlidersHorizontal, X, FilterX, Layers } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { InputGroup, InputGroupInput, InputGroupAddon } from '@/components/shadcn/input-group'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/shadcn/sheet'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import { STATUSES, SOURCES, CATEGORIES, IMPACTS, activeFilters, statusKey, sourceKey, categoryLabel, impactLabel } from './issuesModel.js'

/** NativeSelect sarmalayıcısı `w-fit` — telefonda tam genişlik için `*:w-full`. */
const SEL = 'w-full *:w-full sm:w-auto'

/**
 * Süzgeç araç çubuğu — masaüstünde tek sarılan satır (arama · durum · kaynak · önem · tarih · sıralama); telefonda
 * arama + "Süzgeçler (n)" düğmesi (alt Sheet). Etkin süzgeçler çip olarak listelenir (× tek tek, "Tümünü temizle").
 * Sonuç sayısı ve (yönetici) "son etkinlik sıralaması yalnız bu sayfada" notu görünür metindir — ipucuya saklanmaz.
 * Arama yazdıkça 300 ms sonra uygulanır, Enter anında.
 */
export default function IssuesToolbar({
  filters, patch, reset, phone, count, pageSortNote = false, grouped = false, onGrouped, showGroup = false,
}) {
  const t = useT()
  const [sheetOpen, setSheetOpen] = useState(false)
  const [draft, setDraft] = useState(filters.q)
  const timer = useRef(null)
  useEffect(() => { setDraft(filters.q) }, [filters.q])
  useEffect(() => () => clearTimeout(timer.current), [])
  const apply = (v) => { clearTimeout(timer.current); if (v.trim() !== filters.q) patch({ q: v.trim() }) }
  const onDraft = (v) => { setDraft(v); clearTimeout(timer.current); timer.current = setTimeout(() => apply(v), 300) }
  const active = activeFilters(filters)
  const clearAll = () => { clearTimeout(timer.current); setDraft(''); reset() }

  const labelOf = (f) => {
    if (f.key === 'status') return t('loginIssues.status' + statusKey(f.value))
    if (f.key === 'awaiting') return t('issues.tileAwaiting')
    if (f.key === 'source') return t('loginIssues.source' + sourceKey(f.value))
    if (f.key === 'category') return categoryLabel(f.value, t)
    if (f.key === 'impact') return impactLabel(f.value, t, true)
    if (f.key === 'q') return `“${f.value}”`
    if (f.key === 'since') return t('issues.chipSince', f.value)
    if (f.key === 'until') return t('issues.chipUntil', f.value)
    return String(f.value)
  }

  const statusSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('loginIssues.colStatus')} value={filters.status} onChange={(e) => patch({ status: e.target.value })}>
        <NativeSelectOption value="">{t('issues.statusAll')}</NativeSelectOption>
        {STATUSES.map((s) => <NativeSelectOption key={s} value={s}>{t('loginIssues.status' + statusKey(s))}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const sourceSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('loginIssues.colSource')} value={filters.source} onChange={(e) => patch({ source: e.target.value })}>
        <NativeSelectOption value="">{t('loginIssues.sourceAll')}</NativeSelectOption>
        {SOURCES.map((s) => <NativeSelectOption key={s} value={s}>{t('loginIssues.source' + sourceKey(s))}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const categorySelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('myIssues.colCategory')} value={filters.category} onChange={(e) => patch({ category: e.target.value })}>
        <NativeSelectOption value="">{t('loginIssues.categoryAll')}</NativeSelectOption>
        {CATEGORIES.map((c) => <NativeSelectOption key={c} value={c}>{categoryLabel(c, t)}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  // Etki süzgeci (2026-09-28): tek kod — kaydın etki kümesinde olan (yönetici: sunucuda, "mine": istemcide)
  const impactSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('issues.impactFilter')} value={filters.impact || ''} onChange={(e) => patch({ impact: e.target.value })}>
        <NativeSelectOption value="">{t('issues.impactAll')}</NativeSelectOption>
        {IMPACTS.map((c) => <NativeSelectOption key={c} value={c}>{impactLabel(c, t, true)}</NativeSelectOption>)}
      </NativeSelect>
    </div>
  )
  const sortSelect = (
    <div className={SEL}>
      <NativeSelect aria-label={t('issues.sortLabel')} value={filters.sort} onChange={(e) => patch({ sort: e.target.value })}>
        <NativeSelectOption value="activity">{t('issues.sortActivity')}</NativeSelectOption>
        <NativeSelectOption value="reported">{t('issues.sortReported')}</NativeSelectOption>
      </NativeSelect>
    </div>
  )
  const dates = (
    <div className="flex flex-wrap items-center gap-2">
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('loginIssues.dateFrom')}
        value={filters.since} onChange={(v) => patch({ since: v || '' })} />
      <DateTimeField dateOnly clearable className={phone ? '' : 'dtf-inline'} placeholder={t('loginIssues.dateTo')}
        value={filters.until} onChange={(v) => patch({ until: v || '' })} />
    </div>
  )
  const search = (
    <InputGroup className="w-full sm:w-72">
      <InputGroupInput type="search" placeholder={t('issues.searchPlaceholder')} aria-label={t('issues.searchPlaceholder')}
        value={draft} onChange={(e) => onDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); apply(draft) } }} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
    </InputGroup>
  )
  const groupToggle = showGroup && (
    <Button type="button" variant="outline" size="sm" className={cn('h-9', grouped && 'border-primary bg-primary/10 text-primary hover:bg-primary/15 hover:text-primary')}
      aria-pressed={grouped} onClick={() => onGrouped?.(!grouped)} title={t('loginIssues.groupHint')}>
      <Layers aria-hidden="true" />{t('loginIssues.groupBySignature')}
    </Button>
  )
  const facetCount = active.filter((f) => f.key !== 'q').length

  return (
    <div data-slot="issues-toolbar" className="flex min-w-0 flex-col gap-2">
      {phone ? (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">{search}</div>
          <Sheet open={sheetOpen} onOpenChange={setSheetOpen}>
            <Button type="button" variant="outline" className="h-10 shrink-0 gap-1.5" onClick={() => setSheetOpen(true)} aria-expanded={sheetOpen}>
              <SlidersHorizontal aria-hidden="true" />{t('issues.filters')}
              {facetCount > 0 && <Badge data-slot="filter-count" className="h-5 min-w-5 justify-center px-1.5 tabular-nums">{facetCount}</Badge>}
            </Button>
            {/* z-[1001]: yüzen yardım düğmesinin (.help-fab 900) üstünde — "Uygula" örtülmesin */}
            <SheetContent side="bottom" showCloseButton={false} data-slot="issue-filters-sheet"
              className="z-[1001] max-h-[92dvh] gap-0 overflow-y-auto rounded-t-2xl pb-[env(safe-area-inset-bottom)]">
              <SheetHeader className="flex-row items-center justify-between">
                <div>
                  <SheetTitle className="flex items-center gap-2"><SlidersHorizontal aria-hidden="true" className="size-4" />{t('issues.filters')}</SheetTitle>
                  <SheetDescription>{t('issues.filtersHint')}</SheetDescription>
                </div>
                <SheetClose asChild>
                  <Button type="button" variant="ghost" size="icon" aria-label={t('app.close')}><X aria-hidden="true" /></Button>
                </SheetClose>
              </SheetHeader>
              <div className="flex flex-col gap-3 px-4">
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('loginIssues.colStatus')}{statusSelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('loginIssues.colSource')}{sourceSelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('myIssues.colCategory')}{categorySelect}</Label>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('issues.impactFilter')}{impactSelect}</Label>
                <div className="flex flex-col gap-1 text-xs font-semibold text-muted-foreground">{t('loginIssues.colReportedAt')}{dates}</div>
                <Label className="flex flex-col items-stretch gap-1 text-xs font-semibold text-muted-foreground">{t('issues.sortLabel')}{sortSelect}</Label>
                {groupToggle}
              </div>
              <SheetFooter className="flex-row justify-between">
                <Button type="button" variant="ghost" onClick={clearAll} disabled={active.length === 0}><FilterX aria-hidden="true" />{t('issues.clearAll')}</Button>
                <SheetClose asChild><Button type="button">{t('issues.showResults', count ?? 0)}</Button></SheetClose>
              </SheetFooter>
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          {search}{statusSelect}{sourceSelect}{categorySelect}{impactSelect}{dates}{sortSelect}{groupToggle}
        </div>
      )}

      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        <span data-slot="issues-count" className="mr-1 text-xs font-medium text-muted-foreground tabular-nums">
          {t('issues.resultCount', count ?? 0)}{pageSortNote && filters.sort === 'activity' ? ` · ${t('issues.pageSortNote')}` : ''}
        </span>
        {active.length > 0 && (
          <div data-slot="active-filters" role="group" aria-label={t('issues.activeFilters')} className="flex flex-wrap items-center gap-1.5">
            {active.map((f) => {
              const label = labelOf(f)
              return (
                <Badge key={f.key} variant="outline" data-filter={f.key} className="h-7 gap-1 rounded-full bg-primary/5 pr-1 pl-2.5 font-medium">
                  <span className="max-w-[14rem] truncate">{label}</span>
                  <Button type="button" variant="ghost" size="icon-xs" className="size-5 rounded-full pointer-coarse:size-7"
                    aria-label={t('issues.removeFilter', label)} onClick={() => patch(f.patch)}>
                    <X aria-hidden="true" className="size-3" />
                  </Button>
                </Badge>
              )
            })}
            <Button type="button" variant="link" size="xs" className="h-7 px-1 text-muted-foreground" onClick={clearAll}>{t('issues.clearAll')}</Button>
          </div>
        )}
      </div>
    </div>
  )
}
