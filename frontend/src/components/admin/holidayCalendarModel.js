// Tatil / özel gün takvimi (Ayarlar → Platform → Tatil takvimi, 2026-10-31) — SAF model. Kurum geneli liste; global
// yönetici düzenler (`PUT /api/admin/holidays` tam liste değiştirir, ≤ 500 gün, tarih tekil, ad ≤ 120). Veritabanı sorgu
// izlemesinin "tatil takviminde çalışma" seçeneği bu günleri (İstanbul) atlar.

export const MAX_DAYS = 500
export const MAX_NAME = 120
const YMD = /^(\d{4})-(\d{2})-(\d{2})$/

export function isYmd(v) {
  const m = YMD.exec(String(v || ''))
  if (!m) return false
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3])
}

/** Sunucu listesini temizler: geçerli tarihli, tekil, tarihe göre sıralı `{day, name}`. */
export function normalizeDays(days) {
  const seen = new Set()
  const out = []
  for (const d of Array.isArray(days) ? days : []) {
    const day = String(d?.day || '').slice(0, 10)
    if (!isYmd(day) || seen.has(day)) continue
    seen.add(day)
    out.push({ day, name: String(d?.name ?? '') })
  }
  return out.sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0))
}

/** İstanbul'da bugünün takvim günü (yyyy-MM-dd). */
export function todayIstanbul(now = new Date()) {
  const p = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  const g = (k) => p.find((x) => x.type === k)?.value
  return `${g('year')}-${g('month')}-${g('day')}`
}

/** Süzgeç yılları: listedeki yıllar + bu yıl + gelecek yıl, artan. */
export function yearsOf(list, today = todayIstanbul()) {
  const y = Number(today.slice(0, 4))
  const set = new Set([y, y + 1])
  for (const d of list || []) set.add(Number(d.day.slice(0, 4)))
  return [...set].filter(Number.isFinite).sort((a, b) => a - b)
}

/** İki liste aynı mı (kaydet çubuğunun "değişiklik var" kararı). */
export function sameDays(a, b) {
  const x = normalizeDays(a), y = normalizeDays(b)
  if (x.length !== y.length) return false
  return x.every((d, i) => d.day === y[i].day && d.name.trim() === y[i].name.trim())
}

/**
 * Tek girdinin hataları `{ day?, name? }` (kod → `dbq.hol.v.<kod>`). `exceptDay`: düzenlenen satırın ESKİ tarihi
 * (kendisiyle çakışmasın).
 */
export function entryProblems(day, name, list, exceptDay = null) {
  const out = {}
  if (!day) out.day = 'date_required'
  else if (!isYmd(day)) out.day = 'date_invalid'
  else if ((list || []).some((d) => d.day === day && d.day !== exceptDay)) out.day = 'date_duplicate'
  const n = String(name || '').trim()
  if (!n) out.name = 'name_required'
  else if (n.length > MAX_NAME) out.name = 'name_long'
  return out
}

/** "yyyy-MM-dd" → "1 Ocak 2027, Cuma" (takvim günü — saat dilimi kayması yok). */
export function formatHoliday(ymd, lang = 'tr') {
  if (!isYmd(ymd)) return String(ymd || '—')
  const [y, m, d] = ymd.split('-').map(Number)
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { timeZone: 'UTC', day: 'numeric', month: 'long', year: 'numeric', weekday: 'long' })
    .format(new Date(Date.UTC(y, m - 1, d, 12)))
}

/** PUT gövdesi — sıralı, adlar kırpılmış. */
export function toPayload(list) {
  return normalizeDays(list).map((d) => ({ day: d.day, name: d.name.trim() }))
}
