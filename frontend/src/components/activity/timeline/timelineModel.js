/**
 * İzleme zaman çizelgesi (2026-11-05, D) — saf model. Sunucu (`GET /api/activity/timeline`, be2
 * `ActivityTimelineService`) aktivite akışının kontrol satırlarından dönemleri (ardışık aynı durum), kesintileri ve
 * yanıt süresi kovalarını hesaplar; burada yalnız aralık ↔ URL, yanıt gövdesi, şerit yerleşimi ve tonlar.
 *
 * Kural (sözleşme): bilinmeyen asla "up" değildir; hiç kontrol yoksa erişilebilirlik / MTTR null ("—", nötr).
 */

export const TL_RANGES = ['24h', '7d', '30d', '90d', 'custom']
export const TL_DEFAULT = '7d'
/** Sunucunun en uzun penceresi (gün) — özel aralık bunu aşarsa istemci baştan uyarır. */
export const MAX_DAYS = 90
const HOUR = 3_600_000
const DAY = 24 * HOUR
const SPAN = { '24h': DAY, '7d': 7 * DAY, '30d': 30 * DAY, '90d': 90 * DAY }
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/

const cleanIso = (raw) => {
  if (typeof raw !== 'string' || raw.length > 25) return ''
  const v = raw.trim()
  if (!ISO_RE.test(v)) return ''
  return v.length === 16 ? v + ':00' : v
}

/** ISO (Z'siz = UTC) → ms; geçersiz → NaN. */
export function parseTime(iso) {
  if (!iso) return NaN
  const s = String(iso)
  return Date.parse(s.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(s) ? s : s + 'Z')
}

const isoOf = (ms) => new Date(ms).toISOString().slice(0, 19)

/** URL → çizelge aralığı ({ range, from, to }); bozuk değer varsayılana düşer. */
export function readTlParams(search) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  const r = TL_RANGES.includes(p.get('atlr')) ? p.get('atlr') : TL_DEFAULT
  const from = cleanIso(p.get('atlf'))
  const to = cleanIso(p.get('atlt'))
  if (r === 'custom' && !from) return { range: TL_DEFAULT, from: '', to: '' }
  return { range: r, from: r === 'custom' ? from : '', to: r === 'custom' ? to : '' }
}

/** Çizelge aralığı → URL sorgusu (varsayılan yazılmaz; diğer paramlar korunur). */
export function writeTlParams(search, tl) {
  let p
  try { p = new URLSearchParams(search || '') } catch { p = new URLSearchParams() }
  const r = tl?.range && tl.range !== TL_DEFAULT ? tl.range : ''
  r ? p.set('atlr', r) : p.delete('atlr')
  r === 'custom' && tl.from ? p.set('atlf', tl.from) : p.delete('atlf')
  r === 'custom' && tl.to ? p.set('atlt', tl.to) : p.delete('atlt')
  const s = p.toString()
  return s ? `?${s}` : ''
}

/**
 * Aralık → istek uçları (UTC ISO). Özel aralık: başlangıç zorunlu, bitiş yoksa "şimdi"; ters ya da 90 günden uzun →
 * `{ error: 'order' | 'tooLong' }` (istek gönderilmez, alanın altında söylenir).
 */
export function tlWindow(tl, now = Date.now()) {
  if (tl?.range === 'custom') {
    const f = parseTime(tl.from)
    const t = tl.to ? parseTime(tl.to) : now
    if (!Number.isFinite(f) || !Number.isFinite(t)) return { error: 'missing' }
    if (f > t) return { error: 'order' }
    if (t - f > MAX_DAYS * DAY) return { error: 'tooLong' }
    return { from: isoOf(f), to: tl.to ? isoOf(t) : null }
  }
  const span = SPAN[tl?.range] || SPAN[TL_DEFAULT]
  return { from: isoOf(now - span), to: null }
}

/**
 * Yanıt gövdesi: alanlar kökte (`{ success, monitor, periods, … }`) ya da `data` altında olabilir — ikisi de okunur.
 * Tanınmayan gövde → null (okunamadı; "kontrol yok" DEĞİL).
 */
export function unwrapTimeline(res) {
  if (!res || typeof res !== 'object') return null
  const body = res.data && typeof res.data === 'object' && !Array.isArray(res.data) && ('periods' in res.data || 'monitor' in res.data)
    ? res.data : res
  if (!Array.isArray(body.periods) && !body.monitor) return null
  const n = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
  return {
    monitor: body.monitor || null,
    from: body.from || null,
    to: body.to || null,
    checks: n(body.checks) ?? 0,
    scheduled_checks: n(body.scheduled_checks),
    availability_pct: n(body.availability_pct),
    mttr_s: n(body.mttr_s),
    longest_outage_s: n(body.longest_outage_s),
    outage_count: n(body.outage_count) ?? (Array.isArray(body.outages) ? body.outages.length : 0),
    periods: Array.isArray(body.periods) ? body.periods : [],
    outages: Array.isArray(body.outages) ? body.outages : [],
    series: Array.isArray(body.series) ? body.series : [],
    truncated: !!body.truncated,
    // Kırpılan çizelgede sayımın başladığı an (sunucu: en yeni N kontrol okundu — inceleme CT D8); yoksa null
    covered_from: body.covered_from || null,
    periods_merged: !!body.periods_merged,
  }
}

export const PERIOD_STATES = ['up', 'down', 'warn', 'unknown']
// Üst üste binen dar dönemlerde kısa kesinti görünür kalsın: çizim sırası up → unknown → warn → down (down en üstte).
const Z = { up: 0, unknown: 1, warn: 2, down: 3 }

/**
 * Dönemler → şerit bölümleri (yüzde). `[from, to]` dışına taşan uç kırpılır; geçersiz dönem atlanır. Çok dar
 * (≥ 0) bölüm de çizilir — kap `min-w` ile görünür kılar. Dönüş KRONOLOJİKTİR (DOM sırası = zaman sırası; ekran
 * okuyucu sanal imleci dönemleri sırayla okur — inceleme FR D12); üst üste binen dar dönemde kesintinin görünmesi `z`
 * katmanıyla (`down` en üstte). `index` dönemin özgün sırası (seçim / klavye gezinmesi onunla).
 */
export function stripSegments(periods, fromIso, toIso, now = Date.now()) {
  const a = parseTime(fromIso)
  const bRaw = toIso ? parseTime(toIso) : now
  if (!Number.isFinite(a) || !Number.isFinite(bRaw) || bRaw <= a) return []
  const span = bRaw - a
  const out = []
  ;(periods || []).forEach((p, index) => {
    const s = Math.max(a, parseTime(p?.from))
    const e = Math.min(bRaw, p?.to ? parseTime(p.to) : bRaw)
    if (!Number.isFinite(s) || !Number.isFinite(e) || e < s) return
    const state = PERIOD_STATES.includes(p?.state) ? p.state : 'unknown'
    out.push({ index, state, z: Z[state], left: ((s - a) / span) * 100, width: ((e - s) / span) * 100 })
  })
  return out.sort((x, y) => x.index - y.index)
}

/** Erişilebilirlik tonu: bilinmeyen (null) asla iyi değil. */
export function availabilityTone(pct) {
  if (pct == null || !Number.isFinite(Number(pct))) return 'unknown'
  const v = Number(pct)
  if (v >= 99.9) return 'good'
  if (v >= 99) return 'warn'
  return 'bad'
}

/** Yüzde metni: 100 → "100", 99.95 → "99,95" (dil ayırıcısıyla, en çok 3 hane). */
export function formatPct(pct, lang = 'tr') {
  if (pct == null || !Number.isFinite(Number(pct))) return '—'
  return new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: 3 }).format(Number(pct))
}

/** Kesinti süresi (sn): sürüyorsa "şimdi"ye kadar. */
export function outageSeconds(o, now = Date.now()) {
  if (o?.duration_s != null && Number.isFinite(Number(o.duration_s))) return Number(o.duration_s)
  const s = parseTime(o?.from)
  const e = o?.to ? parseTime(o.to) : now
  return Number.isFinite(s) && Number.isFinite(e) && e >= s ? Math.round((e - s) / 1000) : null
}

/**
 * Yanıt süresi serisi → grafik noktaları. Etiket İstanbul saatiyle: pencere ≤ 48 sa → saat:dakika, daha uzun → gün ay.
 * Boş kova (ms yok) `null` kalır — grafik boşluk çizer, sahte 0 değil.
 */
export function seriesPoints(series, lang = 'tr', spanMs = 7 * DAY) {
  const locale = lang === 'en' ? 'en-GB' : 'tr-TR'
  const short = spanMs <= 2 * DAY
    ? new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul' })
    : new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', timeZone: 'Europe/Istanbul' })
  const full = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul' })
  const n = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
  return (series || []).map((p) => {
    const ms = parseTime(p?.t)
    const ok = Number.isFinite(ms)
    return {
      t: p?.t,
      label: ok ? short.format(new Date(ms)) : String(p?.t ?? ''),
      full: ok ? full.format(new Date(ms)) : String(p?.t ?? ''),
      avg: n(p?.ms_avg),
      max: n(p?.ms_max),
      fail: n(p?.fail) ?? 0,
    }
  })
}

/** Grafikte çizilecek bir şey var mı (en az bir ölçüm ya da hata). */
export const hasSeriesData = (points) => (points || []).some((p) => p.avg != null || p.max != null || p.fail > 0)
