import { useId, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { PAGE_SIZE_OPTIONS } from '../../hooks/usePagination.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Pagination, PaginationContent, PaginationEllipsis, PaginationItem } from '@/components/shadcn/pagination'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/shadcn/select'
import { cn } from '@/lib/utils'

/**
 * Tüm liste görünümlerinin TEK sayfalama çubuğu (2026-09-26 standardı). Beslenişi:
 *   istemci listesi → `<PaginationBar {...usePagination(items, …)} />`
 *   sunucu listesi  → `<PaginationBar {...useServerPagination(…).bar} />`
 * Elle `page=`/`totalPages=` bağlamak kapıda yasak (`paginationBase.test.js`).
 *
 * Görünüm shadcn Data Table sayfalamasıdır (ui.shadcn.com/docs/components/radix/data-table →
 * Pagination): bilgi solda, "Sayfa başına" Select, sonra « ‹ [numaralar …] › ».
 * - sm ve üstü: [Sayfa x / y · a–b / N kayıt] … [Sayfa başına ▾] [« ‹ 1 … 4 5 6 … 42 › »] [Git]
 * - sm altı (telefon): 1. satır ortalı `‹ 3 / 12 ›` (+ Git); «», numaralar gizli; dokunma hedefleri
 *   40 px (`max-sm:size-10`, compact dâhil). 2. satır: kayıt aralığı + boyut Select.
 * - compact (modal ön ayarı): numara yok, her genişlikte `‹ x / y ›`.
 * - DOKUNMATİK (2026-09-28): 40 px kuralı ekran genişliğine değil GİRİŞ TÜRÜNE bağlı — `pointer-coarse:` ile HER
 *   genişlikte (768 px tablette compact oklar 24 px, normal kipte « ‹ › » 32 px ve Git kutusu 28/32 px kalıyordu).
 *   Fare görünümü (sm ve üstü) değişmez: compact `icon-xs`, normal `icon-sm`.
 *
 * Kurallar:
 * - totalItems === 0 → hiç render edilmez.
 * - totalPages === 1 → gezinme gizli; bilgi (+ gerekiyorsa boyut) görünür.
 * - Boyut Select'i liste en küçük boyuta sığıyorsa (totalItems ≤ min(sizeOptions)) gizlenir —
 *   hiçbir seçimin etkisi olmayan kontrol çizilmez. Her genişlikte TEK kontrol: jsdom Tailwind
 *   medya sorgularını uygulamaz, `max-sm:hidden` ile iki alternatif kontrol çizmek testlerde ikisini
 *   birden gösterirdi.
 * - totalPages > 10 → "Sayfaya git" girişi (Enter ile, clamp'li).
 * - Kayıt aralığı `aria-live="polite"`: sayfa değişimi ekran okuyucuya duyurulur.
 * - Sayfa değişince listenin başı görünüm alanının ÜSTÜNDE kaldıysa oraya kaydırılır (hedef:
 *   `scrollTargetRef`, verilmezse çubuktan önceki kardeş öğe = liste). Baş zaten görünüyorsa
 *   kaydırma yok.
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

/** Listenin başı görünür alanın üstünde kaldıysa başa kaydır (en yakın kaydırılabilir ata = modal gövdesi). */
function revealListTop(el) {
  if (!el || typeof el.getBoundingClientRect !== 'function') return
  let viewTop = 0
  for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
    const oy = window.getComputedStyle(p).overflowY
    if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight) { viewTop = p.getBoundingClientRect().top; break }
  }
  if (el.getBoundingClientRect().top >= viewTop) return
  const reduced = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView?.({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
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
  const rootRef = useRef(null)

  if (!totalItems) return null

  const fmt = (n) => Number(n ?? 0).toLocaleString(locale)

  function go(p) {
    const clamped = Math.min(Math.max(1, p), totalPages)
    if (clamped === page) return
    onPageChange?.(clamped)
    const root = rootRef.current
    revealListTop(scrollTargetRef?.current ?? root?.previousElementSibling ?? root?.parentElement)
  }

  function submitGoto(e) {
    e.preventDefault()
    const n = Number(gotoVal)
    if (!gotoVal.trim() || !Number.isFinite(n) || n < 1) return   // geçersiz giriş → hiçbir şey yapma
    go(Math.round(n))
    setGotoVal('')
  }

  // Her gezinme düğmesi telefonda (sm altı) VE dokunmatikte (her genişlik — tablet) 40 px dokunma hedefi, compact dâhil.
  const touch = 'max-sm:size-10 pointer-coarse:size-10'
  const edgeBtn = (label, disabled, target, Icon, edge) => (
    <PaginationItem className={edge ? 'max-sm:hidden' : undefined}>
      <Button type="button" variant="ghost" size={compact ? 'icon-xs' : 'icon-sm'} disabled={disabled} aria-label={label}
        className={touch} onClick={() => go(target)}>
        <Icon aria-hidden="true" />
      </Button>
    </PaginationItem>
  )

  // Konum göstergesi "3 / 12": compact'ta her genişlikte, normalde yalnız telefonda (numaraların yerine).
  const position = (
    <PaginationItem className={cn('px-2 whitespace-nowrap tabular-nums text-muted-foreground', !compact && 'sm:hidden')}>
      {fmt(page)} / {fmt(totalPages)}
    </PaginationItem>
  )

  const nav = totalPages > 1 && (
    <Pagination aria-label={t('pg.nav')} className="mx-0 w-auto">
      {/* Projede Tailwind preflight YOK: <ul> tarayıcı varsayılanıyla madde imi + 40 px sol dolgu çiziyordu
          (her düğmenin yanında "•", telefonda ortalanmamış satır) → list-none m-0 p-0 */}
      <PaginationContent className="m-0 list-none gap-0.5 p-0">
        {edgeBtn(t('pg.first'), page <= 1, 1, ChevronsLeft, true)}
        {edgeBtn(t('pg.prev'), page <= 1, page - 1, ChevronLeft, false)}
        {!compact && pageNumbers(totalPages, page).map((p, i) => p === '…'
          ? <PaginationItem key={`e${i}`} className="max-sm:hidden"><PaginationEllipsis className="size-8" /></PaginationItem>
          : (
            <PaginationItem key={p} className="max-sm:hidden">
              <Button type="button" variant={p === page ? 'outline' : 'ghost'} size="sm"
                className="h-8 min-w-8 px-2 tabular-nums pointer-coarse:h-10 pointer-coarse:min-w-10"
                aria-label={t('pg.pageBtn', p)} aria-current={p === page ? 'page' : undefined}
                onClick={() => go(p)}>{fmt(p)}</Button>
            </PaginationItem>
          ))}
        {position}
        {edgeBtn(t('pg.next'), page >= totalPages, page + 1, ChevronRight, false)}
        {edgeBtn(t('pg.last'), page >= totalPages, totalPages, ChevronsRight, true)}
      </PaginationContent>
    </Pagination>
  )

  const goto = totalPages > 10 && (
    <form onSubmit={submitGoto}>
      <Input type="number" min="1" max={totalPages} value={gotoVal} placeholder={t('pg.gotoLabel')}
        aria-label={t('pg.goto')} onChange={e => setGotoVal(e.target.value)}
        className={cn('w-16 px-2 max-sm:h-10', compact ? 'h-7' : 'h-8', 'pointer-coarse:h-10')} />
    </form>
  )

  // Boyut seçici: shadcn Select (Data Table deseni). Radix değerleri dize ister; çağırana sayı döner.
  // Etkin boyut listede yoksa (eski çağıran) listeye eklenir — tetik boş görünmesin.
  const minSize = sizeOptions.length ? Math.min(...sizeOptions) : 0
  const showSizer = typeof onPageSizeChange === 'function' && sizeOptions.length > 1 && totalItems > minSize
  const sizes = sizeOptions.includes(pageSize) || !Number.isFinite(pageSize)
    ? sizeOptions : [...sizeOptions, pageSize].sort((a, b) => a - b)
  const sizer = showSizer && (
    <div role="group" aria-labelledby={sizerLabelId} className="inline-flex items-center gap-2">
      <span id={sizerLabelId} className="whitespace-nowrap text-muted-foreground">{t('pg.perPage')}</span>
      <Select value={String(pageSize)}
        onValueChange={(v) => { const n = Number(v); if (Number.isFinite(n) && n !== pageSize) onPageSizeChange(n) }}>
        {/* Yükseklik tetiğin kendi `data-[size=sm]:h-8` kuralını ezmeli → aynı öznitelik varyantıyla */}
        <SelectTrigger size="sm" aria-labelledby={sizerLabelId}
          className={cn('w-[4.75rem] tabular-nums max-sm:data-[size=sm]:h-10 pointer-coarse:data-[size=sm]:h-10', compact && 'data-[size=sm]:h-7 pointer-coarse:data-[size=sm]:h-10')}>
          <SelectValue />
        </SelectTrigger>
        {/* Modal / yan panel içinde de üstte kalsın: z-(--z-menu) (SHADCN.md §2.5) */}
        <SelectContent side="top" className="z-(--z-menu) min-w-[4.75rem]">
          {sizes.map((n) => (
            <SelectItem key={n} value={String(n)} className="tabular-nums">{fmt(n)}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )

  return (
    <div ref={rootRef} data-slot="pagination-bar"
      className={cn('flex flex-col-reverse gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-4 sm:gap-y-2',
        compact ? 'mt-2.5 text-[.85em]' : 'mt-3.5 text-[.9em]')}>
      {/* Bilgi + boyut: telefonda 2. satır (iki yana yaslı); sm+'da sarmalayıcı `contents` → bilgi solda, boyut sağa */}
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 sm:contents">
        <p className="m-0 flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-0.5 tabular-nums text-muted-foreground sm:mr-auto">
          {totalPages > 1 && <span className="max-sm:hidden">{t('pg.pageOf', fmt(page), fmt(totalPages))}</span>}
          <span aria-live="polite">{t('pg.range', fmt(rangeStart), fmt(rangeEnd), fmt(totalItems))}</span>
        </p>
        {sizer}
      </div>
      {(nav || goto) && (
        <div className="flex items-center justify-center gap-2 sm:justify-end">
          {nav}
          {goto}
        </div>
      )}
    </div>
  )
}
