import { useEffect, useMemo, useRef, useState } from 'react'

/**
 * Client-side sayfalama hook'u — tüm liste görünümlerinde tek standart (PaginationBar ile birlikte).
 *
 * Davranış kuralları:
 * - Clamp: veri küçülünce (polling sonrası silme, filtre daralması) page > totalPages olursa
 *   otomatik son geçerli sayfaya çekilir.
 * - Reset: resetDeps içindeki herhangi bir değer değişince sayfa 1'e döner. resetDeps'e ASLA
 *   items dizisi bağlanmaz — polling her turda yeni dizi referansı set eder, sayfa korunmalı.
 * - Kalıcılık: pageSize localStorage'a görünüm bazında yazılır (sm.pageSize.<listKey>).
 * - 1-tabanlı sayfa numarası tek standarttır.
 */

export const PAGE_SIZE_OPTIONS = [25, 50, 100, 200]
export const DEFAULT_PAGE_SIZE = 50

const LS_PREFIX = 'sm.pageSize.'

/** localStorage'dan kayıtlı sayfa boyutu; geçersiz/eksik → defaultSize (private mode'da try/catch). */
export function readPageSize(listKey, defaultSize = DEFAULT_PAGE_SIZE) {
  if (!listKey) return defaultSize
  try {
    const raw = localStorage.getItem(LS_PREFIX + listKey)
    const n = Number(raw)
    return PAGE_SIZE_OPTIONS.includes(n) ? n : defaultSize
  } catch {
    return defaultSize
  }
}

/** Sayfa boyutu tercihini kalıcılaştırır (server-side sayfalar hook'suz da kullanır). */
export function writePageSize(listKey, size) {
  if (!listKey) return
  try { localStorage.setItem(LS_PREFIX + listKey, String(size)) } catch { /* private mode */ }
}

export function usePagination(items, { listKey, defaultSize = DEFAULT_PAGE_SIZE, resetDeps = [], initialPage = 1, initialSize = null } = {}) {
  // initialPage/initialSize: paylaşılan URL'den (?page=3&ps=100) gelen başlangıç — ps geçerli bir
  // boyutsa localStorage tercihine BASKINDIR (link alan kişide 3. sayfa başka dilime kaymasın).
  const [page, setPageRaw] = useState(() => (Number.isInteger(initialPage) && initialPage > 0 ? initialPage : 1))
  const [pageSize, setPageSizeRaw] = useState(() =>
    PAGE_SIZE_OPTIONS.includes(initialSize) ? initialSize : readPageSize(listKey, defaultSize))

  const totalItems = items?.length ?? 0
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))

  // Clamp: state'i render sırasında değil effect'te düzelt (React kuralı); render'da safePage kullan.
  // totalItems === 0 iken clamp YOK: veri henüz yüklenmemişken (async ilk mount) URL'den gelen
  // initialPage'i 1'e ezerdi; boş listede zaten hiçbir şey render edilmiyor, bar da görünmüyor.
  const safePage = totalItems === 0 ? page : Math.min(page, totalPages)
  useEffect(() => {
    if (totalItems > 0 && page > totalPages) setPageRaw(totalPages)
  }, [page, totalPages, totalItems])

  // Reset: filtre/arama değişince sayfa 1'e döner. İlk mount'ta resetleme (sayfa zaten 1).
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) { firstRun.current = false; return }
    setPageRaw(1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, resetDeps)

  const setPage = (p) => {
    const n = Number(p)
    if (!Number.isFinite(n)) return
    setPageRaw(Math.min(Math.max(1, Math.round(n)), totalPages))
  }

  const setPageSize = (size) => {
    const n = Number(size)
    if (!PAGE_SIZE_OPTIONS.includes(n)) return
    setPageSizeRaw(n)
    setPageRaw(1)                    // boyut değişince başa dön
    writePageSize(listKey, n)
  }

  const pageItems = useMemo(
    () => (items ?? []).slice((safePage - 1) * pageSize, safePage * pageSize),
    [items, safePage, pageSize],
  )

  return {
    page: safePage,
    setPage,
    pageSize,
    setPageSize,
    totalPages,
    totalItems,
    pageItems,
    rangeStart: totalItems === 0 ? 0 : (safePage - 1) * pageSize + 1,
    rangeEnd: Math.min(safePage * pageSize, totalItems),
  }
}

export default usePagination
