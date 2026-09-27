import { useCallback, useEffect, useState } from 'react'
import { initialPageFrom, initialSizeFrom, pageUrlMapping, usePresetConfig, writePageSize } from './usePagination.js'
import { useUrlQuerySync } from './useUrlQuerySync.js'

/**
 * Sunucu sayfalı listelerin TEK standardı (2026-09-26) — `usePagination`'ın kardeşi, aynı ön ayarlar,
 * aynı çubuk. `components/history/useCheckHistory.js`'teki desenin genelleştirilmiş hâli.
 *
 *   const sp = useServerPagination({ listKey: 'incidents', preset: 'page', resetDeps: [filters], apiBase: 0 })
 *   useEffect(() => { api.list({ ...filters, page: sp.apiPage, size: sp.pageSize }).then(sp.bind) },
 *     [filters, sp.apiPage, sp.pageSize])
 *   <PaginationBar {...sp.bar} />
 *
 * Kurallar (her biri bir yaşanmış hatanın karşılığı):
 * - `page` UI'da HER ZAMAN 1-tabanlıdır; API'ye `apiPage` gider (`apiBase` 0 → page-1, 1 → page).
 *   Elle `page + 1` / `readUrlInt(...) - 1` dönüşümü yok (ChangeHistoryTab kaydırma hatası).
 * - Sıfırlama `resetDeps`in DEĞERİ değişince olur, mount'ta ASLA: derin bağlantıyla gelen sayfa
 *   (`?page=3`, `r_page=2`) ilk render'da korunur (IncidentHistoryPage / RetentionRunsPanel /
 *   DeploymentHistoryPanel mount-sıfırlama hatası). Sıfırlama render sırasında yapılır — süzgeç
 *   değişimi ile sayfa 1 AYNI render'da görünür, (yeni süzgeç + eski sayfa) için boşa istek gitmez.
 * - Boyut değişince sayfa 1; boyut `listKey` ile kalıcıdır (`sm.pageSize.<listKey>`).
 * - Toplam gelince `page > totalPages` ise son sayfaya çekilir ("101–100 / 100", `p_page=99`).
 *   Toplam bilinmiyorken (ilk yanıt öncesi) sayfaya dokunulmaz.
 * - URL (isteğe bağlı, `url: { pageKey, sizeKey }`): usePagination ile aynı okuma/yazma kuralı.
 *
 * Hata yanıtında `setTotal`/`bind` ÇAĞIRMAYIN: son bilinen toplam kalsın, çubuk kaybolmasın.
 */

/**
 * Sunucu zarfından toplamı çıkarır. Desteklenen biçimler:
 * `{ total, total_pages, page }` · `{ pagination: { current_page, total, total_pages } }` ·
 * `{ data: { total } }` · `{ data: { pagination: { total } } }`. Bulunamazsa `null`.
 */
export function fromEnvelope(res) {
  if (!res || typeof res !== 'object') return { total: null, totalPages: null }
  const pick = (o) => {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return null
    if (o.total != null && Number.isFinite(Number(o.total))) {
      return { total: Number(o.total), totalPages: o.total_pages != null ? Number(o.total_pages) : null }
    }
    const pg = o.pagination
    if (pg && typeof pg === 'object' && pg.total != null && Number.isFinite(Number(pg.total))) {
      return { total: Number(pg.total), totalPages: pg.total_pages != null ? Number(pg.total_pages) : null }
    }
    return null
  }
  return pick(res) ?? pick(res.data) ?? { total: null, totalPages: null }
}

export function useServerPagination({
  listKey, preset, defaultSize, sizeOptions, resetDeps = [], url = null, apiBase = 0,
  initialPage = 1,
} = {}) {
  const cfg = usePresetConfig(preset, defaultSize, sizeOptions)
  const [page, setPageRaw] = useState(() => initialPageFrom(url, initialPage))
  const [pageSize, setPageSizeRaw] = useState(() => initialSizeFrom(url, null, listKey, cfg))
  const [total, setTotalRaw] = useState(null)          // null = henüz bilinmiyor

  // Değer-karşılaştırmalı sıfırlama, render sırasında (React'in "önceki prop'a göre state ayarla"
  // deseni). İlk değer mount'ta kaydedilir → mount'ta sıfırlama YOK; StrictMode çift-mount'ta da
  // state korunduğu için tetiklenmez.
  const depsKey = JSON.stringify(resetDeps)
  const [seenDeps, setSeenDeps] = useState(depsKey)
  let current = page
  if (seenDeps !== depsKey) {
    setSeenDeps(depsKey)
    if (page !== 1) setPageRaw(1)
    current = 1
  }

  const totalPages = total == null ? null : Math.max(1, Math.ceil(total / pageSize))
  // Görünen sayfa: toplam bilindiğinde aralığa sıkıştırılır (0 kayıt → 1).
  const safePage = totalPages == null ? current : Math.min(current, totalPages)
  useEffect(() => {
    if (totalPages != null && page > totalPages) setPageRaw(totalPages)
  }, [page, totalPages])

  useUrlQuerySync(pageUrlMapping(url, safePage, pageSize, cfg.defaultSize), { enabled: !!url })

  const setPage = (p) => {
    const n = Number(p)
    if (!Number.isFinite(n)) return
    const max = totalPages ?? Number.MAX_SAFE_INTEGER
    setPageRaw(Math.min(Math.max(1, Math.round(n)), max))
  }

  const setPageSize = (size) => {
    const n = Number(size)
    if (!cfg.sizeOptions.includes(n)) return
    setPageSizeRaw(n)
    setPageRaw(1)
    writePageSize(listKey, n)
  }

  // setTotal / bind KARARLI referans: çağıranın yükleme efektine/useCallback'ine bağımlılık olarak eklenebilir.
  const setTotal = useCallback((n) => {
    const v = Number(n)
    setTotalRaw(Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0)
  }, [])

  /** Yanıtı geçirir, zarftan toplamı alır; zincirde kullanılabilsin diye yanıtı geri verir. */
  const bind = useCallback((res) => {
    const { total: t } = fromEnvelope(res)
    if (t != null) setTotal(t)
    return res
  }, [setTotal])

  /** Elle başa dön (ör. "Uygula" düğmesi aynı süzgeci yeniden gönderdiğinde). Kararlı referans. */
  const reset = useCallback(() => setPageRaw(1), [])

  const shownTotal = total ?? 0
  const bar = {
    page: safePage,
    totalPages: totalPages ?? 1,
    totalItems: shownTotal,
    rangeStart: shownTotal === 0 ? 0 : (safePage - 1) * pageSize + 1,
    rangeEnd: Math.min(safePage * pageSize, shownTotal),
    pageSize,
    sizeOptions: cfg.sizeOptions,
    onPageChange: setPage,
    onPageSizeChange: setPageSize,
    ...(cfg.compact ? { compact: true } : {}),
  }

  return {
    page: safePage,
    apiPage: safePage - 1 + (apiBase === 1 ? 1 : 0),
    pageSize,
    sizeOptions: cfg.sizeOptions,
    total,
    totalPages: totalPages ?? 1,
    setPage,
    setPageSize,
    setTotal,
    bind,
    reset,
    bar,
  }
}

export default useServerPagination
