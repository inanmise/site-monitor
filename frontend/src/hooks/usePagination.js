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

/**
 * Görünümün sunduğu boyut listesi (R13, 2026-09-25): hook sabit [25,50,100,200] listesine göre
 * doğruluyordu; kendi listesini ([10,25,50]) PaginationBar'a veren sayfada "10"a geri dönmek
 * sessizce yutuluyordu. Artık TEK liste: hook'a verilir, hook döndürür, `{...pager}` çubuğa taşır.
 * Geçersiz/boş liste → varsayılan liste (çubuk ile hook yine aynı listeyi görür).
 */
function normalizeSizeOptions(sizeOptions) {
  const list = Array.isArray(sizeOptions) ? sizeOptions.filter(n => Number.isInteger(n) && n > 0) : []
  return list.length ? list : PAGE_SIZE_OPTIONS
}

/** localStorage'dan kayıtlı sayfa boyutu; geçersiz/eksik → defaultSize (private mode'da try/catch). */
export function readPageSize(listKey, defaultSize = DEFAULT_PAGE_SIZE, sizeOptions = PAGE_SIZE_OPTIONS) {
  if (!listKey) return defaultSize
  try {
    const raw = localStorage.getItem(LS_PREFIX + listKey)
    const n = Number(raw)
    return normalizeSizeOptions(sizeOptions).includes(n) ? n : defaultSize
  } catch {
    return defaultSize
  }
}

/** Sayfa boyutu tercihini kalıcılaştırır (server-side sayfalar hook'suz da kullanır). */
export function writePageSize(listKey, size) {
  if (!listKey) return
  try { localStorage.setItem(LS_PREFIX + listKey, String(size)) } catch { /* private mode */ }
}

export function usePagination(items, { listKey, defaultSize = DEFAULT_PAGE_SIZE, resetDeps = [], initialPage = 1, initialSize = null, sizeOptions = PAGE_SIZE_OPTIONS } = {}) {
  // sizeOptions: çubukta sunulan boyutlar — doğrulama da AYNI listeyle yapılır (normalizeSizeOptions).
  const allowedSizes = normalizeSizeOptions(sizeOptions)
  // initialPage/initialSize: paylaşılan URL'den (?page=3&ps=100) gelen başlangıç — ps geçerli bir
  // boyutsa localStorage tercihine BASKINDIR (link alan kişide 3. sayfa başka dilime kaymasın).
  const [page, setPageRaw] = useState(() => (Number.isInteger(initialPage) && initialPage > 0 ? initialPage : 1))
  const [pageSize, setPageSizeRaw] = useState(() =>
    allowedSizes.includes(initialSize) ? initialSize : readPageSize(listKey, defaultSize, allowedSizes))

  const totalItems = items?.length ?? 0
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))

  // Clamp: state'i render sırasında değil effect'te düzelt (React kuralı); render'da safePage kullan.
  // totalItems === 0 iken clamp YOK: veri henüz yüklenmemişken (async ilk mount) URL'den gelen
  // initialPage'i 1'e ezerdi; boş listede zaten hiçbir şey render edilmiyor, bar da görünmüyor.
  const safePage = totalItems === 0 ? page : Math.min(page, totalPages)
  useEffect(() => {
    if (totalItems > 0 && page > totalPages) setPageRaw(totalPages)
  }, [page, totalPages, totalItems])

  // Reset: filtre/arama DEĞİŞİNCE sayfa 1'e döner.
  //
  // Eskiden "ilk koşum mu" bayrağıyla yapılıyordu (`firstRun` ref). Ref'ler StrictMode'un
  // mount → temizlik → mount döngüsünde KORUNUR ve bu efektin temizliği yok: ikinci kurulumda
  // bayrak zaten false olduğu için `setPageRaw(1)` çalışıyor ve `?page=3` gibi bir derin
  // bağlantı geliştirme modunda sessizce 1'e düşüyordu (ardından useUrlQuerySync param'ı
  // adresten de siliyordu). Üretim derlemesinde efektler çift çalışmadığı için görünmezdi.
  //
  // Artık bayrak değil DEĞER karşılaştırılıyor: StrictMode'un ikinci kurulumunda bağımlılıklar
  // AYNI olduğu için reset tetiklenmez; gerçek bir filtre değişiminde tetiklenir. Aynı sınıf
  // hata `useCheckRun.aliveRef` için de yaşanmıştı (af6332e0).
  const prevDeps = useRef(null)
  useEffect(() => {
    const cur = JSON.stringify(resetDeps)
    if (prevDeps.current === null) { prevDeps.current = cur; return }   // ilk kurulum
    if (prevDeps.current === cur) return                                 // StrictMode çift-mount
    prevDeps.current = cur
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
    if (!allowedSizes.includes(n)) return
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
    sizeOptions: allowedSizes,       // <PaginationBar {...pager} /> çubuğa AYNI listeyi taşır
    totalPages,
    totalItems,
    pageItems,
    rangeStart: totalItems === 0 ? 0 : (safePage - 1) * pageSize + 1,
    rangeEnd: Math.min(safePage * pageSize, totalItems),
  }
}

export default usePagination
