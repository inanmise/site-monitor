/**
 * Bakım penceresi zamanlama yardımcıları — saf mantık, React yok (2026-09-26 yeniden tasarım).
 *
 * Sunucu (MaintenanceService) pencerenin durumunu ve YALNIZ sıradaki oluşumu döner; ajanda ("önümüzdeki 7 gün"),
 * "şu an aktif, ne zamana kadar" şeridi ve düzenleyicideki canlı özet için oluşumların İSTEMCİDE de açılması
 * gerekir. Kurallar sunucuyla birebir aynı (occurrence engine, DST-güvenli):
 *   • Çapa `start_at` UTC ISO ("yyyy-MM-ddTHH:mm:ss", Z yok); tekrarlayan pencerede çapanın pencere saat
 *     dilimindeki DUVAR SAATİ her oluşumun başlangıç saatidir; çapa gününden önceki günler sayılmaz.
 *   • DAILY her gün; WEEKLY `days_of_week` (ISO 1=Pzt..7=Paz); MONTHLY `day_of_month` (ay kısaysa son gün).
 *   • Duraklatılmış (`active === false`) pencere hiç tetiklenmez.
 *
 * Saat dilimi aritmetiği Intl ile: duvar saati → an dönüşümü iki geçişli (DST kenarında ofset yeniden okunur).
 */

const DOW_INDEX = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 }
const MIN = 60_000
export const DAY_MS = 86_400_000
export const DEFAULT_TZ = 'Europe/Istanbul'

const fmtCache = new Map()
function formatter(tz) {
  if (!fmtCache.has(tz)) {
    let f = null
    try {
      f = new Intl.DateTimeFormat('en-GB', {
        timeZone: tz, hourCycle: 'h23', weekday: 'short',
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      })
    } catch { f = null }
    fmtCache.set(tz, f)
  }
  return fmtCache.get(tz)
}

/** Geçerli IANA dilimi mi? Geçersizse UTC'ye düşülür (sunucu da ZoneId.of hatasında aynı şeyi yapar). */
export function safeTz(tz) {
  return formatter(tz || DEFAULT_TZ) ? (tz || DEFAULT_TZ) : 'UTC'
}

/** Sunucu ISO'su (Z'siz = UTC) → epoch ms; boş/bozuk → null. */
export function parseIso(iso) {
  if (!iso || typeof iso !== 'string') return null
  const s = iso.trim()
  const withZ = /[zZ]$/.test(s) || /[+-]\d{2}:?\d{2}$/.test(s) ? s : (/^\d{4}-\d{2}-\d{2}$/.test(s) ? s + 'T00:00:00Z' : s + 'Z')
  const ms = Date.parse(withZ)
  return Number.isFinite(ms) ? ms : null
}

/** Epoch ms → proje ISO'su (UTC, Z'siz). */
export function toIso(ms) {
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`
}

/** An → verilen dilimde duvar saati parçaları { y, m, d, h, mi, dow(1..7) }. */
export function wallClock(ms, tz) {
  const f = formatter(safeTz(tz))
  const parts = {}
  for (const p of f.formatToParts(new Date(ms))) parts[p.type] = p.value
  return {
    y: Number(parts.year), m: Number(parts.month), d: Number(parts.day),
    h: Number(parts.hour) % 24, mi: Number(parts.minute), dow: DOW_INDEX[parts.weekday] ?? 1,
  }
}

function offsetAt(ms, tz) {
  const w = wallClock(ms, tz)
  return Date.UTC(w.y, w.m - 1, w.d, w.h, w.mi) - Math.floor(ms / MIN) * MIN
}

/** Duvar saati (dilimde) → an (epoch ms). İki geçiş: tahmin edilen ofsetle bulunan anda ofset değişmişse düzelt. */
export function zonedToInstant(y, m, d, h, mi, tz) {
  const guess = Date.UTC(y, m - 1, d, h, mi)
  const off1 = offsetAt(guess, tz)
  const inst = guess - off1
  const off2 = offsetAt(inst, tz)
  return off2 === off1 ? inst : guess - off2
}

export function daysOfWeekSet(w) {
  return new Set(String(w?.days_of_week || '').split(',').map((s) => Number(s.trim())).filter((n) => n >= 1 && n <= 7))
}

export function durationMs(w) {
  const n = Number(w?.duration_minutes)
  return Math.max(1, Number.isFinite(n) && n > 0 ? Math.round(n) : 60) * MIN
}

const dateKey = (y, m, d) => y * 10000 + m * 100 + d
const isoDowOfUtcDay = (dayUtcMs) => { const g = new Date(dayUtcMs).getUTCDay(); return g === 0 ? 7 : g }
const daysInMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate()

function matchesRecurrence(rec, y, m, d, dow, days, dom) {
  if (rec === 'DAILY') return true
  if (rec === 'WEEKLY') return days.has(dow)
  if (rec === 'MONTHLY') return d === Math.min(dom, daysInMonth(y, m))
  return false
}

/**
 * [from, to) aralığıyla KESİŞEN oluşumlar — [{ start, end }] (epoch ms), en fazla `limit`.
 * Duraklatılmış pencere için boş (hiç tetiklenmez). `includePaused` ajanda dışı kullanımlar için.
 */
export function occurrences(w, from, to, { limit = 60, includePaused = false } = {}) {
  if (!w || (!includePaused && w.active === false)) return []
  const anchor = parseIso(w.start_at)
  if (anchor == null || !(to > from)) return []
  const dur = durationMs(w)
  const rec = w.recurrence || 'NONE'
  if (rec === 'NONE') return anchor < to && anchor + dur > from ? [{ start: anchor, end: anchor + dur }] : []
  const tz = safeTz(w.timezone)
  const a = wallClock(anchor, tz)
  const days = daysOfWeekSet(w)
  const dom = Number(w.day_of_month) >= 1 ? Number(w.day_of_month) : 1
  if (rec === 'WEEKLY' && days.size === 0) return []
  const first = wallClock(from - dur, tz)
  const out = []
  for (let k = 0; k < 400 && out.length < limit; k++) {
    const dayUtc = Date.UTC(first.y, first.m - 1, first.d + k)
    const ld = new Date(dayUtc)
    const y = ld.getUTCFullYear(), m = ld.getUTCMonth() + 1, d = ld.getUTCDate()
    if (dateKey(y, m, d) < dateKey(a.y, a.m, a.d)) continue
    if (!matchesRecurrence(rec, y, m, d, isoDowOfUtcDay(dayUtc), days, dom)) continue
    const start = zonedToInstant(y, m, d, a.h, a.mi, tz)
    if (start >= to) break
    if (start + dur <= from) continue
    out.push({ start, end: start + dur })
  }
  return out
}

/** `now` anını kapsayan oluşum ya da null (duraklatılmışta null). */
export function currentOccurrence(w, now) {
  return occurrences(w, now, now + 1, { limit: 1 })[0] ?? null
}

/** `now`dan sonra başlayan ilk oluşum ya da null (400 gün ufku, sunucuyla aynı). */
export function nextOccurrence(w, now, { includePaused = false } = {}) {
  const list = occurrences(w, now, now + 400 * DAY_MS, { limit: 400, includePaused })
  return list.find((o) => o.start > now) ?? null
}

/** Sunucunun `status` alanının istemci karşılığı (sunucu değeri varsa o esastır — düzenleyici önizlemesi için). */
export function computeStatus(w, now) {
  if (w.active === false) return 'paused'
  if (currentOccurrence(w, now)) return 'active'
  if ((w.recurrence || 'NONE') === 'NONE') {
    const anchor = parseIso(w.start_at)
    if (anchor != null && now > anchor + durationMs(w)) return 'completed'
  }
  return 'upcoming'
}

/** Ardışık günleri aralığa sıkıştırır: [1,2,3,4,5] → [[1,5]], [1,3,5] → [[1,1],[3,3],[5,5]]. */
export function compressDays(days) {
  const sorted = [...new Set(days)].filter((d) => d >= 1 && d <= 7).sort((a, b) => a - b)
  const out = []
  for (const d of sorted) {
    const last = out[out.length - 1]
    if (last && last[1] === d - 1) last[1] = d
    else out.push([d, d])
  }
  return out
}

/** Dakika → "1 h 30 min" / "45 min" (etiketler çağırandan: hourLabel(n), minuteLabel(n)). */
export function humanMinutes(minutes, { hour, minute }) {
  const m = Math.max(0, Math.round(Number(minutes) || 0))
  const h = Math.floor(m / 60), r = m % 60
  if (h && r) return `${hour(h)} ${minute(r)}`
  if (h) return hour(h)
  return minute(r)
}

/** Yerel gün anahtarı 'YYYY-MM-DD' — kullanıcının tarayıcı gününde (ajanda gruplaması). */
export function localDayOf(ms) {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// ── Sayfa düzeyi türetilmiş veri (saf; bileşenler ve testler buradan alır) ───────────────────────────────

const STATUS_RANK = { active: 0, upcoming: 1, paused: 2, completed: 3 }

/** Sıralama: süren → yaklaşan (sıradaki oluşuma göre) → duraklatılmış (ada göre) → bitmiş (en yeni önce). Girdi: [{ w, next }]. */
export function sortWindows(list) {
  return [...list].sort((a, b) => {
    const ra = STATUS_RANK[a.w.status] ?? 1, rb = STATUS_RANK[b.w.status] ?? 1
    if (ra !== rb) return ra - rb
    if (a.w.status === 'upcoming') return (a.next?.start ?? Infinity) - (b.next?.start ?? Infinity)
    if (a.w.status === 'completed') return (parseIso(b.w.start_at) ?? 0) - (parseIso(a.w.start_at) ?? 0)
    return String(a.w.name).localeCompare(String(b.w.name))
  })
}

/** Özet kartı süzgeçleri — hepsi liste verisinden türetilir (sunucuya ek istek yok). Girdi: { w, next }. */
export const TILE_PRED = {
  active:    (x) => x.w.status === 'active',
  next24h:   (x, now) => x.next != null && x.next.start - now <= DAY_MS,
  next7d:    (x, now) => x.next != null && x.next.start - now <= 7 * DAY_MS,
  recurring: (x) => !!x.w.recurrence && x.w.recurrence !== 'NONE',
  paused:    (x) => x.w.status === 'paused',
  past:      (x) => x.w.status === 'completed',
}

export const AGENDA_DAYS = 7

/** Önümüzdeki N günün yerel gün anahtarları (bugün dâhil) + gün sonu anı. */
function dayKeys(now, days) {
  const start = new Date(now); start.setHours(0, 0, 0, 0)
  const out = []
  for (let i = 0; i < days; i++) {
    const d = new Date(start); d.setDate(start.getDate() + i)
    out.push({ key: localDayOf(d.getTime()), date: d })
  }
  const end = new Date(start); end.setDate(start.getDate() + days)
  return { keys: out, end: end.getTime() }
}

/**
 * Ajanda: oluşumları yerel güne göre gruplar — tekrarlayan pencereler AÇILIR (her gün ayrı satır). Süren oluşum
 * bugüne yazılır (dün başlamış olsa da). Dışa: [{ key, date, items: [{ w, start, end, running }] }].
 */
export function buildAgenda(windows, now, days = AGENDA_DAYS) {
  const { keys, end } = dayKeys(now, days)
  const byDay = new Map(keys.map((k) => [k.key, []]))
  for (const w of windows || []) {
    if (w.active === false) continue
    for (const o of occurrences(w, now, end, { limit: days + 2 })) {
      const running = o.start <= now && o.end > now
      const key = localDayOf(running ? now : o.start)
      if (!byDay.has(key)) continue
      byDay.get(key).push({ w, start: o.start, end: o.end, running })
    }
  }
  return keys.map((k) => ({ ...k, items: byDay.get(k.key).sort((a, b) => a.start - b.start) }))
}
