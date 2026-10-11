/**
 * Aktivite sayfası — saf model (2026-10-11 yeniden tasarım). Bileşen yalnız çizer; karar burada ve testli.
 */

/** Liste kabı bu genişliğin altındaysa kart listesi (tablo sütunları ad + sonuç + zamanı sığdıramaz). */
export const TABLE_MIN_PX = 720
/** Süzgeç kartı bu genişliğin altındaysa tür çipleri katlanır ("İzleme türü (n)" düğmesi). */
export const CHIPS_INLINE_MIN_PX = 640

/**
 * Tablo mu kart mı. Kap ölçüldüyse (gerçek tarayıcı) KAP genişliği belirler — 768 px tablette kenar çubuğu açıkken
 * içerik dar; ölçüm yoksa (jsdom, ilk boyama) görünüm alanı sorgusu (`phone`).
 */
export function pickLayout(width, phone) {
  if (width > 0) return width < TABLE_MIN_PX ? 'cards' : 'table'
  return phone ? 'cards' : 'table'
}

const TZ = 'Europe/Istanbul'

/**
 * Sunucu serisi → grafik noktaları. Saatlik `t` = UTC saat başı ("…Z"), günlük `t` = İstanbul günü (yyyy-MM-dd).
 * Etiket İstanbul saatiyle (UTC+3) — tarayıcının saat dilimi ne olursa olsun.
 */
export function chartPoints(series, lang = 'tr') {
  const pts = Array.isArray(series?.points) ? series.points : []
  const hourly = series?.bucket === 'hour'
  const locale = lang === 'en' ? 'en-GB' : 'tr-TR'
  const hourFmt = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone: TZ })
  const dayFmt = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', timeZone: TZ })
  const fullHour = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: TZ })
  const fullDay = new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'long', timeZone: TZ })
  return pts.map((p) => {
    // Günlük anahtar İstanbul günü: öğlen 12:00 İstanbul (09:00Z) ile biçimlenir — gün kaymaz
    const at = hourly ? new Date(p.t) : new Date(`${p.t}T09:00:00Z`)
    const ok = Number.isFinite(at.getTime())
    return {
      t: p.t,
      label: ok ? (hourly ? hourFmt : dayFmt).format(at) : String(p.t ?? ''),
      full: ok ? (hourly ? fullHour : fullDay).format(at) : String(p.t ?? ''),
      success: Number(p.success) || 0,
      warning: Number(p.warning) || 0,
      error: Number(p.error) || 0,
      unknown: Number(p.unknown) || 0,
    }
  })
}

/** Serinin toplamı (boşsa grafik yerine boş durum). */
export function seriesTotal(points) {
  return (points || []).reduce((s, p) => s + p.success + p.warning + p.error + p.unknown, 0)
}

/**
 * Etkin süzgeç çipleri (kaldırılabilir): türler, varsayılan dışı aralık, arama. Durum çipi YOK — durum KPI kartının
 * basılı hâli ve durum seçicisi zaten gösteriyor (ikinci bir "Hata" düğmesi aynı adı taşırdı).
 */
export function activeChips(filters) {
  const out = []
  for (const ty of filters?.types || []) out.push({ key: 'type:' + ty, kind: 'type', value: ty })
  if (filters?.range && filters.range !== '24h') out.push({ key: 'range', kind: 'range', value: filters.range })
  if (filters?.q) out.push({ key: 'q', kind: 'q', value: filters.q })
  return out
}

/** Çipi kaldır → yeni süzgeç. */
export function removeChip(filters, chip) {
  if (chip.kind === 'type') return { ...filters, types: filters.types.filter((x) => x !== chip.value) }
  if (chip.kind === 'range') return { ...filters, range: '24h' }
  if (chip.kind === 'q') return { ...filters, q: '' }
  return filters
}
