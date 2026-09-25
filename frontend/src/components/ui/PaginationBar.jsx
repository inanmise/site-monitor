import { useId, useState } from 'react'
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { PAGE_SIZE_OPTIONS } from '../../hooks/usePagination.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Pagination, PaginationContent, PaginationEllipsis, PaginationItem } from '@/components/shadcn/pagination'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Tüm liste görünümlerinin TEK sayfalama barı — hem client-side (usePagination ile) hem
 * server-side (sayfanın kendi state'iyle, 1-tabanlı değerler geçirilerek) kullanılır.
 * İç uygulama shadcn: Pagination (gezinme) + ToggleGroup (sayfa boyutu) + Input ("Sayfaya git").
 *
 * - totalItems === 0 → hiç render edilmez.
 * - totalPages === 1 → gezinme gizli, boyut seçici + kayıt bilgisi görünür.
 * - totalPages > 10 → "Sayfaya git" girişi (Enter ile, clamp'li).
 * - compact: modal içi küçük varyant (boyut seçici + ‹ x/y › + Git).
 * - scrollTargetRef verilirse sayfa değişiminde kapsayıcının başına yumuşak scroll.
 *
 * Sayfa öğeleri <a href> DEĞİL düğmedir (shadcn PaginationLink yerine aynı görünümü veren
 * Button): SPA'da sayfa değişimi state'tir, gezinme/yeniden yükleme OLMAMALI.
 */

/** Pencereli sayfa numaraları: ilk, son, geçerli ±1; boşluklar '…' (tek doğruluk kaynağı). */
export function pageNumbers(total, current) {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages = new Set([1, total, current, current - 1, current + 1])
  return [...pages].filter(p => p >= 1 && p <= total).sort((a, b) => a - b)
    .reduce((acc, p, i, arr) => {
      if (i > 0 && p - arr[i - 1] > 1) acc.push('…')
      acc.push(p)
      return acc
    }, [])
}

export default function PaginationBar({
  page, totalPages, totalItems, rangeStart, rangeEnd,
  pageSize, sizeOptions = PAGE_SIZE_OPTIONS,
  onPageChange, onPageSizeChange,
  setPage, setPageSize,          // usePagination alias'ları → <PaginationBar {...pager} /> yeter
  compact = false, scrollTargetRef = null,
}) {
  onPageChange = onPageChange ?? setPage
  onPageSizeChange = onPageSizeChange ?? setPageSize
  const t = useT()
  const locale = useDateLocale()
  const [gotoVal, setGotoVal] = useState('')
  const sizerLabelId = useId()

  if (!totalItems) return null

  const fmt = (n) => Number(n ?? 0).toLocaleString(locale)

  function go(p) {
    const clamped = Math.min(Math.max(1, p), totalPages)
    if (clamped === page) return
    onPageChange?.(clamped)
    const el = scrollTargetRef?.current
    if (el) {
      const reduced = typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      el.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
    }
  }

  function submitGoto(e) {
    e.preventDefault()
    const n = Number(gotoVal)
    if (!gotoVal.trim() || !Number.isFinite(n) || n < 1) return   // geçersiz giriş → hiçbir şey yapma
    go(Math.round(n))
    setGotoVal('')
  }

  const iconSize = compact ? 'icon-xs' : 'icon-sm'
  const edgeBtn = (label, disabled, target, Icon) => (
    <PaginationItem>
      <Button type="button" variant="ghost" size={iconSize} disabled={disabled} aria-label={label}
        onClick={() => go(target)}>
        <Icon aria-hidden="true" />
      </Button>
    </PaginationItem>
  )

  // Numaralar ve '…' dar ekranda gizlenir; «‹›» + bilgi kalır (eski .pg-btn--num kuralı).
  const nav = totalPages > 1 && (
    <Pagination aria-label={t('pg.nav')} className="mx-0 w-auto">
      <PaginationContent className="gap-0.5">
        {edgeBtn(t('pg.first'), page <= 1, 1, ChevronsLeft)}
        {edgeBtn(t('pg.prev'), page <= 1, page - 1, ChevronLeft)}
        {!compact && pageNumbers(totalPages, page).map((p, i) => p === '…'
          ? <PaginationItem key={`e${i}`} className="max-sm:hidden"><PaginationEllipsis className="size-8" /></PaginationItem>
          : (
            <PaginationItem key={p} className="max-sm:hidden">
              <Button type="button" variant={p === page ? 'outline' : 'ghost'} size="sm"
                className="h-8 min-w-8 px-2 tabular-nums"
                aria-label={t('pg.pageBtn', p)} aria-current={p === page ? 'page' : undefined}
                onClick={() => go(p)}>{fmt(p)}</Button>
            </PaginationItem>
          ))}
        {compact && (
          <PaginationItem className="whitespace-nowrap px-1.5 tabular-nums text-muted-foreground">
            {fmt(page)} / {fmt(totalPages)}
          </PaginationItem>
        )}
        {edgeBtn(t('pg.next'), page >= totalPages, page + 1, ChevronRight)}
        {edgeBtn(t('pg.last'), page >= totalPages, totalPages, ChevronsRight)}
      </PaginationContent>
    </Pagination>
  )

  const goto = totalPages > 10 && (
    <form onSubmit={submitGoto}>
      <Input type="number" min="1" max={totalPages} value={gotoVal} placeholder={t('pg.gotoLabel')}
        aria-label={t('pg.goto')} onChange={e => setGotoVal(e.target.value)}
        className={cn('w-16 px-2', compact ? 'h-7' : 'h-8')} />
    </form>
  )

  // Boyut seçici: tek aktif seçim → ToggleGroup. Radix değerleri dize ister; boyutlar sayı olduğu
  // için öğeler sıra numarasıyla anahtarlanır, çağırana orijinal sayı döner. Etkin boyuta yeniden
  // basmak seçimi boşaltmaz ('' yok sayılır). Roller SegmentedControl'deki gibi düğme + aria-pressed.
  const sizeIdx = sizeOptions.indexOf(pageSize)
  const sizer = (
    <div className="inline-flex items-center gap-1">
      <span id={sizerLabelId} className="mr-0.5 whitespace-nowrap text-muted-foreground">{t('pg.perPage')}</span>
      <ToggleGroup type="single" role="group" aria-labelledby={sizerLabelId} spacing={1}
        value={sizeIdx >= 0 ? String(sizeIdx) : ''}
        onValueChange={(v) => {
          if (v === '') return
          const n = sizeOptions[Number(v)]
          if (n !== undefined && n !== pageSize) onPageSizeChange?.(n)
        }}>
        {sizeOptions.map((n, i) => (
          <ToggleGroupItem key={n} value={String(i)} role="button" aria-pressed={pageSize === n}
            aria-checked={undefined} size="sm"
            className={cn('border border-transparent px-2 font-normal tabular-nums text-muted-foreground data-[state=on]:border-border data-[state=on]:bg-background data-[state=on]:font-semibold data-[state=on]:text-foreground data-[state=on]:shadow-xs',
              compact && 'h-6 min-w-6 px-1.5')}>
            {n}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )

  return (
    <div className={cn('flex flex-wrap items-center', compact ? 'mt-2.5 gap-2.5 text-[.85em]' : 'mt-3.5 gap-3.5 text-[.9em]')}>
      {sizer}
      {nav}
      {goto}
      <span className="ml-auto inline-flex items-center gap-2.5 whitespace-nowrap tabular-nums text-muted-foreground max-sm:ml-0">
        {totalPages > 1 && <span>{t('pg.pageOf', fmt(page), fmt(totalPages))}</span>}
        <span>{t('pg.range', fmt(rangeStart), fmt(rangeEnd), fmt(totalItems))}</span>
      </span>
    </div>
  )
}
