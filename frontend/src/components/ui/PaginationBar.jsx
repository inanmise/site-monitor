import { useState } from 'react'
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { PAGE_SIZE_OPTIONS } from '../../hooks/usePagination.js'

/**
 * Tüm liste görünümlerinin TEK sayfalama barı — hem client-side (usePagination ile) hem
 * server-side (sayfanın kendi state'iyle, 1-tabanlı değerler geçirilerek) kullanılır.
 *
 * - totalItems === 0 → hiç render edilmez.
 * - totalPages === 1 → gezinme gizli, boyut seçici + kayıt bilgisi görünür.
 * - totalPages > 10 → "Sayfaya git" girişi (Enter ile, clamp'li).
 * - compact: modal içi küçük varyant (boyut seçici + ‹ x/y › + Git).
 * - scrollTargetRef verilirse sayfa değişiminde kapsayıcının başına yumuşak scroll.
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

  const nav = totalPages > 1 && (
    <nav className="pg-nav" aria-label={t('pg.nav')}>
      <button type="button" className="pg-btn" disabled={page <= 1} aria-label={t('pg.first')}
        onClick={() => go(1)}><ChevronsLeft size={15} /></button>
      <button type="button" className="pg-btn" disabled={page <= 1} aria-label={t('pg.prev')}
        onClick={() => go(page - 1)}><ChevronLeft size={15} /></button>
      {!compact && pageNumbers(totalPages, page).map((p, i) => p === '…'
        ? <span key={`e${i}`} className="pg-ellipsis" aria-hidden="true">…</span>
        : <button key={p} type="button"
            className={`pg-btn pg-btn--num${p === page ? ' pg-btn--active' : ''}`}
            aria-label={t('pg.pageBtn', p)} aria-current={p === page ? 'page' : undefined}
            onClick={() => go(p)}>{fmt(p)}</button>)}
      {compact && <span className="pg-info-mini">{fmt(page)} / {fmt(totalPages)}</span>}
      <button type="button" className="pg-btn" disabled={page >= totalPages} aria-label={t('pg.next')}
        onClick={() => go(page + 1)}><ChevronRight size={15} /></button>
      <button type="button" className="pg-btn" disabled={page >= totalPages} aria-label={t('pg.last')}
        onClick={() => go(totalPages)}><ChevronsRight size={15} /></button>
    </nav>
  )

  const goto = totalPages > 10 && (
    <form className="pg-goto" onSubmit={submitGoto}>
      <input type="number" min="1" max={totalPages} value={gotoVal} placeholder={t('pg.gotoLabel')}
        aria-label={t('pg.goto')} onChange={e => setGotoVal(e.target.value)} />
    </form>
  )

  return (
    <div className={`pg-bar${compact ? ' pg-bar--compact' : ''}`}>
      <div className="pg-sizer">
        <span className="pg-sizer-label">{t('pg.perPage')}</span>
        {sizeOptions.map(n => (
          <button key={n} type="button"
            className={`pg-size-btn${pageSize === n ? ' pg-size-btn--active' : ''}`}
            aria-pressed={pageSize === n}
            onClick={() => onPageSizeChange?.(n)}>{n}</button>
        ))}
      </div>
      {nav}
      {goto}
      <span className="pg-info">
        {totalPages > 1 && <span className="pg-info-page">{t('pg.pageOf', fmt(page), fmt(totalPages))}</span>}
        <span className="pg-info-range">{t('pg.range', fmt(rangeStart), fmt(rangeEnd), fmt(totalItems))}</span>
      </span>
    </div>
  )
}
