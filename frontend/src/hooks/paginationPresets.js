/**
 * Sayfalama ön ayarları — boyut listesi ve varsayılan boyutun TEK kaynağı (2026-09-26 standardı).
 *
 * Her liste bir ön ayar seçer; boyut listesi kaynakta elle yazılmaz (kapı `paginationBase.test.js`
 * `sizeOptions: [` / `sizeOptions={[` literallerini yasaklar). Üç bağlam var:
 *
 * - `page`  — bir sekmenin ana listesi (Olaylar, Aktivite, Haftalık Raporlar …): 50 / [25, 50, 100, 200]
 * - `panel` — bir sayfanın içindeki alt liste ya da yönetim paneli bölümü: 25 / [25, 50, 100, 200]
 * - `modal` — pencere / yan panel içindeki liste: 10 / [10, 25, 50] + `compact` çubuk
 *
 * Kaldırılanlar: varsayılan 20 (seçeneklerde yoktu, boyut seçicide hiçbir düğme etkin görünmüyordu)
 * ve [10, 20, 50, 100] / [10, 25, 50, 100] listeleri.
 */
export const PAGINATION_PRESETS = Object.freeze({
  page:  Object.freeze({ sizeOptions: Object.freeze([25, 50, 100, 200]), defaultSize: 50, compact: false }),
  panel: Object.freeze({ sizeOptions: Object.freeze([25, 50, 100, 200]), defaultSize: 25, compact: false }),
  modal: Object.freeze({ sizeOptions: Object.freeze([10, 25, 50]), defaultSize: 10, compact: true }),
})

export const DEFAULT_PRESET = 'page'

/** Geçerli bir boyut listesi mi (pozitif tamsayılar, en az bir öğe)? */
function cleanSizes(list) {
  return Array.isArray(list) ? list.filter(n => Number.isInteger(n) && n > 0) : []
}

/**
 * Ön ayarı çözer. `defaultSize` / `sizeOptions` açıkça verilirse ön ayarı EZER (geriye uyum:
 * eski çağıranlar `usePagination(items, { defaultSize: 25 })` yazıyor). Bilinmeyen ön ayar adı
 * `page`'e düşer. Varsayılan boyut listede yoksa listenin ilk öğesi kullanılır — çubukta her zaman
 * etkin bir seçenek olsun (eski "20" hatası).
 *
 * @param {'page'|'panel'|'modal'} [preset]
 * @param {{ defaultSize?: number, sizeOptions?: number[] }} [overrides]
 * @returns {{ name: string, sizeOptions: number[], defaultSize: number, compact: boolean }}
 */
export function resolvePreset(preset, { defaultSize, sizeOptions } = {}) {
  const name = Object.prototype.hasOwnProperty.call(PAGINATION_PRESETS, preset) ? preset : DEFAULT_PRESET
  const base = PAGINATION_PRESETS[name]
  const custom = cleanSizes(sizeOptions)
  const options = custom.length ? custom : [...base.sizeOptions]
  const wanted = Number.isInteger(defaultSize) && defaultSize > 0 ? defaultSize : base.defaultSize
  return {
    name,
    sizeOptions: options,
    defaultSize: options.includes(wanted) ? wanted : options[0],
    compact: base.compact,
  }
}
