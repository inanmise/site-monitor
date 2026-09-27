import { useEffect, useMemo, useRef, useState } from 'react'
import { PAGINATION_PRESETS, resolvePreset } from './paginationPresets.js'
import { readUrlInt, useUrlQuerySync } from './useUrlQuerySync.js'

/**
 * Client-side sayfalama hook'u — tüm liste görünümlerinde tek standart (PaginationBar ile birlikte).
 * Sunucu sayfalı listeler için kardeşi `useServerPagination` (aynı ön ayarlar, aynı çubuk).
 *
 *   const pager = usePagination(items, { listKey: 'maintenance-windows', preset: 'page', resetDeps: [q] })
 *   pager.pageItems.map(…)   ·   <PaginationBar {...pager} />
 *
 * Davranış kuralları:
 * - Ön ayar (`preset`: page | panel | modal, bkz. paginationPresets.js) boyut listesini ve varsayılan
 *   boyutu verir; `modal` çubuğu `compact` çizer. `defaultSize` / `sizeOptions` açıkça verilirse ön
 *   ayarı ezer (geriye uyum).
 * - Clamp: veri küçülünce (polling sonrası silme, filtre daralması) page > totalPages olursa
 *   otomatik son geçerli sayfaya çekilir.
 * - Reset: resetDeps içindeki herhangi bir değer değişince sayfa 1'e döner. resetDeps'e ASLA
 *   items dizisi bağlanmaz — polling her turda yeni dizi referansı set eder, sayfa korunmalı.
 * - Kalıcılık: pageSize localStorage'a görünüm bazında yazılır (sm.pageSize.<listKey>).
 * - URL (isteğe bağlı): `url: { pageKey, sizeKey }` → ilk açılışta adresten okunur (ps ön ayarın
 *   listesine karşı doğrulanır), sonra geri yazılır: sayfa > 1 ise sayfa, boyut ön ayar varsayılanı
 *   değilse YA DA sayfa > 1 ise boyut (paylaşılan link aynı dilimi açsın).
 * - 1-tabanlı sayfa numarası tek standarttır.
 */

export const PAGE_SIZE_OPTIONS = [...PAGINATION_PRESETS.page.sizeOptions]
export const DEFAULT_PAGE_SIZE = PAGINATION_PRESETS.page.defaultSize

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

/**
 * Ön ayarı hook içinde KARARLI referansla çözer (her render'da yeni dizi → `{...pager}` çubuğa
 * gereksiz yeni prop taşımasın). İki hook da (usePagination / useServerPagination) bunu kullanır.
 */
export function usePresetConfig(preset, defaultSize, sizeOptions) {
  const optsKey = Array.isArray(sizeOptions) ? sizeOptions.join(',') : ''
  return useMemo(() => resolvePreset(preset, { defaultSize, sizeOptions }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [preset, defaultSize, optsKey])
}

/** URL eşlemesi: sayfa > 1 ise sayfa; boyut varsayılandan farklıysa YA DA sayfa > 1 ise boyut. */
export function pageUrlMapping(url, page, pageSize, defaultSize) {
  if (!url?.pageKey && !url?.sizeKey) return {}
  const out = {}
  if (url.pageKey) out[url.pageKey] = page > 1 ? page : null
  if (url.sizeKey) out[url.sizeKey] = (pageSize !== defaultSize || page > 1) ? pageSize : null
  return out
}

/** İlk sayfa: URL (varsa) → initialPage → 1. */
export function initialPageFrom(url, initialPage) {
  const fromUrl = url?.pageKey ? readUrlInt(url.pageKey, null) : null
  const p = fromUrl ?? initialPage
  return Number.isInteger(p) && p > 0 ? p : 1
}

/** İlk boyut: URL ps (listede ise) → initialSize (listede ise) → localStorage → varsayılan. */
export function initialSizeFrom(url, initialSize, listKey, cfg) {
  const fromUrl = url?.sizeKey ? readUrlInt(url.sizeKey, null) : null
  if (cfg.sizeOptions.includes(fromUrl)) return fromUrl
  if (cfg.sizeOptions.includes(initialSize)) return initialSize
  return readPageSize(listKey, cfg.defaultSize, cfg.sizeOptions)
}

export function usePagination(items, {
  listKey, preset, defaultSize, sizeOptions, resetDeps = [],
  initialPage = 1, initialSize = null, url = null,
} = {}) {
  // sizeOptions: çubukta sunulan boyutlar — doğrulama da AYNI listeyle yapılır.
  const cfg = usePresetConfig(preset, defaultSize, sizeOptions)
  const allowedSizes = cfg.sizeOptions
  // initialPage/initialSize ya da url: paylaşılan URL'den (?page=3&ps=100) gelen başlangıç — ps geçerli
  // bir boyutsa localStorage tercihine BASKINDIR (link alan kişide 3. sayfa başka dilime kaymasın).
  const [page, setPageRaw] = useState(() => initialPageFrom(url, initialPage))
  const [pageSize, setPageSizeRaw] = useState(() => initialSizeFrom(url, initialSize, listKey, cfg))

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

  // Paylaşılabilir adres (isteğe bağlı). url yoksa eşleme boş + kapalı: hook sırası sabit kalır.
  useUrlQuerySync(pageUrlMapping(url, safePage, pageSize, cfg.defaultSize), { enabled: !!url })

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
    // modal ön ayarı küçük çubuk çizer. Anahtar YALNIZ true iken eklenir: `<PaginationBar compact {...pager} />`
    // yazan çağıranın açık değerini `compact: false` ile ezmeyelim.
    ...(cfg.compact ? { compact: true } : {}),
  }
}

export default usePagination
