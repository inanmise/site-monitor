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
  // İletim güncellemesi farkı ayrıntının içinde taşır ({"changes":{"alan":{from,to}}, …}) — fark olarak gösterilir.
  const nested = detail?.changes
  if (nested && typeof nested === 'object' && !Array.isArray(nested) && Object.keys(nested).length > 0 && isDiff(nested)) {
    return { kind: 'diff', data: nested }
  }
  if (detail && Object.keys(detail).length > 0) return { kind: 'snapshot', data: detail }
  return null
}

/**
 * Alan adı → okunur etiket (2026-11-13 hata taraması: akış ham alan adları gösteriyordu — `isDefault`, `grant_id`,
 * `level_before`, `rotation`…). Sıra: akışa özgü `cf.f.<alan>` → sekme geçmişinin `hist.f.<alan>` → bildirim grubunun
 * `ng.f.<alan>` → ham ad (bilinmeyen alan kaybolmaz).
 */
// Sözlük anahtarı alan adından farklı olan alanlar: "failed" adlı anahtar hata iletisi kapısına (errorMessageQuality)
// takılır — bu bir sayaç etiketi, hata metni değil.
const FIELD_KEY_ALIAS = { failed: 'unsuccessful' }

export function feedFieldLabel(t, field) {
  const keyName = FIELD_KEY_ALIAS[field] || field
  for (const prefix of ['cf.f.', 'hist.f.', 'ng.f.']) {
    const k = `${prefix}${prefix === 'cf.f.' ? keyName : field}`
    const s = t(k)
    if (s !== k) return s
  }
  return field
}

const tryKey = (t, k) => { const s = t(k); return s === k ? null : s }
const WEBHOOK_TYPES = { TEAMS: 'Microsoft Teams', SLACK: 'Slack' }

/**
 * Kod değerinin okunur metni — alan başına bilinen sözlükler (paylaşım düzeyi / alıcı türü, nöbet süresi, iletim kapsamı,
 * alarm seviyesi, kişi rolü, webhook türü ve test sonucu, kilit türü). Sözlükte olmayan değer `fmt` ile (evet/hayır, boş,
 * dizi) yazılır — ham kod kaybolmaz.
 */
export function feedValue(t, field, value, fmt) {
  const v = value
  if (typeof v === 'string' && v !== '') {
    const up = v.toUpperCase()
    switch (field) {
      case 'grantee_type': return tryKey(t, `cf.v.grantee.${up}`) ?? fmt(t, v)
      case 'level': case 'level_before': return tryKey(t, `access.level.${up}`) ?? fmt(t, v)
      case 'rotation': return up === 'DAILY' ? t('oncall.daily') : up === 'WEEKLY' ? t('oncall.weekly') : fmt(t, v)
      case 'scope_kind': return tryKey(t, `fwd.scope.${v.toLowerCase()}`) ?? fmt(t, v)
      case 'recipient_kind': return up === 'TEAM' ? t('fwd.rcpt.team') : up === 'MEMBERS' ? t('fwd.rcpt.members') : fmt(t, v)
      case 'minAlertLevel': case 'min_alert_level': return tryKey(t, `ec.level.${v.toLowerCase()}`) ?? fmt(t, v)
      case 'role': return tryKey(t, `ec.role.${v.toLowerCase()}`) ?? fmt(t, v)
      case 'webhook_type': case 'webhookType': return WEBHOOK_TYPES[up] || fmt(t, v)
      case 'lock': return tryKey(t, `cf.v.lock.${v.toLowerCase()}`) ?? fmt(t, v)
      case 'status': {
        if (up === 'SENT') return t('cf.v.status.SENT')
        if (up.startsWith('FAILED')) {
          const why = v.includes(':') ? v.slice(v.indexOf(':') + 1).trim() : ''
          return why ? `${t('cf.v.status.FAILED')}: ${why}` : t('cf.v.status.FAILED')
        }
        return fmt(t, v)
      }
      default: break
    }
  }
  // İletim notu yalnız özetiyle denetlenir (metin yazılmaz) — özetin kendisi anlamsız, "var / yok" yazılır.
  if (field === 'note_hash') return v == null || v === '' ? fmt(t, null) : t('cf.v.noteSet')
  return fmt(t, v)
}
