// Çapraz yönetim değişiklik akışı (2026-11-13, yönetim zenginleştirmesi #2) — saf, test edilebilir.
// Alan listesi sunucunun AdminActivityFeedService.AREAS sırasıyla AYNI (test Java kaynağını okur).

export const FEED_AREAS = ['teams', 'users', 'contacts', 'thresholds', 'groups', 'forwards', 'sharing', 'oncall']

/** Gün süzgeci (0 = tümü) — sunucunun AdminController.FEED_DAYS ile aynı. */
export const FEED_DAYS = [1, 7, 30, 90, 365, 0]
export const DEFAULT_DAYS = 30

/** Tablo ↔ kart eşiği (liste kabının genişliği, px). jsdom genişliği 0 → tablo. */
export const FEED_TABLE_MIN_PX = 760

/** URL'deki gün değeri → bilinen değer (bilinmeyen → 30). */
export function normDays(raw) {
  // null / boş → varsayılan (Number(null) = 0 "tümü" demek olurdu)
  if (raw == null || String(raw).trim() === '') return DEFAULT_DAYS
  const n = Number(raw)
  return Number.isInteger(n) && FEED_DAYS.includes(n) ? n : DEFAULT_DAYS
}

/** URL'deki alan CSV'si → bilinen alanlar (sıra = FEED_AREAS, tekrar yok). */
export function parseAreas(raw) {
  const want = new Set(String(raw || '').split(',').map(s => s.trim()).filter(Boolean))
  return FEED_AREAS.filter(a => want.has(a))
}

/** Alan seçimini değiştir: "tümü" (boş dizi) ↔ tek tek; tümü seçilirse boşa döner. */
export function toggleArea(selected, area, available = FEED_AREAS) {
  const cur = new Set(selected)
  if (cur.has(area)) cur.delete(area)
  else cur.add(area)
  const out = FEED_AREAS.filter(a => cur.has(a) && available.includes(a))
  return out.length === available.length ? [] : out
}

export function layoutFor(width) {
  return !width || width >= FEED_TABLE_MIN_PX ? 'table' : 'cards'
}

/**
 * Satırın açılımında gösterilecek içerik: fark ({"alan":{from,to}}) ya da anlık görüntü. Fark taşımayan olaylarda
 * (iletim / paylaşım / nöbet) sunucunun JSON `detail` alanı anlık görüntü olarak kullanılır.
 */
export function rowPayload(row, parse, isDiff) {
  const changes = parse(row?.changes)
  if (changes && isDiff(changes)) return { kind: 'diff', data: changes }
  if (changes) return { kind: 'snapshot', data: changes }
  const detail = parse(row?.detail)
  if (detail && Object.keys(detail).length > 0) return { kind: 'snapshot', data: detail }
  return null
}
