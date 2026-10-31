// Veritabanı Sorgu İzleme — zamanlama düzenleyicisinin SAF modeli (2026-10-31). Sunucu `DbQuerySchedule` asıl
// doğrulayıcıdır (cron sıklığı, "hiç çalışmaz"); burada yalnız arayüzün anında söyleyebildikleri var: hazır aralıklar,
// cron ifadesinin İNSAN DİLİNDE açıklaması (küçük, sınırlı çözümleyici — çözemezse null = "açıklanamadı"), pencere
// özetleri ve İstanbul saatiyle tarih biçimi. Saat dilimi DAİMA Europe/Istanbul (sözleşme §4).

export const TZ = 'Europe/Istanbul'
export const MIN_INTERVAL_SECONDS = 60          // sunucu tabanı (site.monitor.dbquery.min-interval-seconds varsayılanı)
export const MAX_INTERVAL_SECONDS = 7 * 86400   // sözleşme: 60 s – 7 gün
export const MAX_SKIP_DATES = 100
export const MAX_WINDOWS = 20
export const INTERVAL_PRESETS = [60, 300, 600, 900, 1800, 3600, 7200, 21600, 43200, 86400]
export const DAYS = [1, 2, 3, 4, 5, 6, 7]       // ISO: 1 = Pazartesi … 7 = Pazar

/** Hazır cron şablonları — `key` → i18n `dbq.sched.cronPreset.<key>`. */
export const CRON_PRESETS = [
  { key: 'hourly', expr: '0 * * * *' },
  { key: 'every15', expr: '*/15 * * * *' },
  { key: 'businessHours15', expr: '*/15 9-18 * * 1-5' },
  { key: 'weekdays0900', expr: '0 9 * * 1-5' },
  { key: 'daily0800', expr: '0 8 * * *' },
  { key: 'monday0900', expr: '0 9 * * 1' },
  { key: 'monthly', expr: '0 0 1 * *' },
]

// ── Aralık ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

export const UNIT_SECONDS = { minutes: 60, hours: 3600, days: 86400 }

/** Saniyeyi en büyük tam birime çevirir (özel aralık kutusu): 7200 → {2, hours}; 90 → {1.5, minutes}. */
export function splitInterval(sec) {
  const s = Number(sec)
  if (!Number.isFinite(s) || s <= 0) return { amount: '', unit: 'minutes' }
  if (s % 86400 === 0) return { amount: s / 86400, unit: 'days' }
  if (s % 3600 === 0) return { amount: s / 3600, unit: 'hours' }
  return { amount: Math.round((s / 60) * 100) / 100, unit: 'minutes' }
}

/** Kısa süre metni — "5 dk", "2 sa", "1 gün" (`dbq.sched.dur.*`). */
export function durationText(sec, t) {
  const s = Number(sec)
  if (!Number.isFinite(s) || s <= 0) return '—'
  if (s % 86400 === 0) return t('dbq.sched.dur.days', s / 86400)
  if (s % 3600 === 0) return t('dbq.sched.dur.hours', s / 3600)
  if (s % 60 === 0) return t('dbq.sched.dur.minutes', s / 60)
  return t('dbq.sched.dur.seconds', s)
}

/** Aralık hatası kodu (`dbq.sched.v.<kod>`) ya da null. */
export function intervalProblem(sec) {
  const s = Number(sec)
  if (!Number.isFinite(s) || s <= 0) return 'interval_required'
  if (s < MIN_INTERVAL_SECONDS) return 'interval_min'
  if (s > MAX_INTERVAL_SECONDS) return 'interval_max'
  return null
}

// ── Cron çözümleyici (açıklama için) ───────────────────────────────────────────────────────────────────────────────────

const MACROS = {
  '@hourly': '0 0 * * * *', '@daily': '0 0 0 * * *', '@midnight': '0 0 0 * * *', '@weekly': '0 0 0 * * 0',
  '@monthly': '0 0 0 1 * *', '@yearly': '0 0 0 1 1 *', '@annually': '0 0 0 1 1 *',
}
const DOW_NAMES = { SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 }
const MON_NAMES = { JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6, JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12 }

/**
 * Tek alan → { any: bool, values: number[] (sıralı, tekil), step?: n } ya da null (çözülemedi). Değer kümesi en çok
 * (max − min + 1) öğe — sınırlı. `names` ad tablosu (MON / JAN), `wrap7` haftanın günü 7 → 0.
 */
function parseField(raw, min, max, names = null, wrap7 = false) {
  const f = String(raw || '').toUpperCase()
  if (!f || f.length > 64) return null
  if (f === '*' || f === '?') return { any: true, values: [] }
  const set = new Set()
  let step = null
  const num = (s) => {
    if (names && names[s] != null) return names[s]
    if (!/^\d{1,2}$/.test(s)) return NaN
    return Number(s)
  }
  for (const part of f.split(',')) {
    if (!part) return null
    let [range, stepStr] = part.split('/')
    if (part.split('/').length > 2) return null
    let st = 1
    if (stepStr !== undefined) {
      if (!/^\d{1,3}$/.test(stepStr) || Number(stepStr) < 1) return null
      st = Number(stepStr)
    }
    let lo, hi
    if (range === '*') { lo = min; hi = max } else if (range.includes('-')) {
      const [a, b, extra] = range.split('-')
      if (extra !== undefined) return null
      lo = num(a); hi = num(b)
    } else {
      lo = num(range); hi = stepStr !== undefined ? max : lo
    }
    if (!Number.isInteger(lo) || !Number.isInteger(hi) || lo < min || hi > max || lo > hi) return null
    for (let v = lo; v <= hi; v += st) set.add(wrap7 && v === 7 ? 0 : v)
    if (range === '*' && stepStr !== undefined && f.split(',').length === 1) step = st
  }
  return { any: false, values: [...set].sort((a, b) => a - b), step }
}

/** Ardışık mı (aralık metni için) — [1,2,3,4,5]. */
const consecutive = (vs) => vs.length > 1 && vs.every((v, i) => i === 0 || v === vs[i - 1] + 1)
const pad2 = (n) => String(n).padStart(2, '0')

/**
 * Cron ifadesini çözer: 5 alan (dk sa gün ay hafta-günü — sunucu başına "0 " ekler), 6 alan (Spring: sn dk sa gün ay
 * hafta-günü) ya da makro. Çözülemezse null.
 */
export function parseCron(expr) {
  let s = String(expr || '').trim()
  if (!s || s.length > 120) return null
  if (s.startsWith('@')) {
    s = MACROS[s.toLowerCase()]
    if (!s) return null
  }
  const parts = s.split(/\s+/)
  if (parts.length === 5) parts.unshift('0')
  if (parts.length !== 6) return null
  const [sec, min, hour, dom, mon, dow] = parts
  const out = {
    second: parseField(sec, 0, 59), minute: parseField(min, 0, 59), hour: parseField(hour, 0, 23),
    dom: parseField(dom, 1, 31), month: parseField(mon, 1, 12, MON_NAMES), dow: parseField(dow, 0, 7, DOW_NAMES, true),
  }
  return Object.values(out).every(Boolean) ? out : null
}

/** Gün listesi metni (Pzt–Cum / Pzt, Çar ve Cum) — 0 = Pazar. */
function dowText(values, t) {
  const order = (d) => (d === 0 ? 7 : d)               // Pazartesi başlangıçlı sıra
  const vs = [...values].map(order).sort((a, b) => a - b)
  const key = vs.join(',')
  if (key === '1,2,3,4,5') return t('dbq.sched.cronDesc.weekdays')
  if (key === '6,7') return t('dbq.sched.cronDesc.weekend')
  if (vs.length === 7) return t('dbq.sched.cronDesc.everyDay')
  const name = (d) => t(`dbq.sched.dayLong.${d}`)
  if (vs.length === 1) return t('dbq.sched.cronDesc.everyDow', name(vs[0]))
  if (consecutive(vs) && vs.length > 2) return t('dbq.sched.cronDesc.dowRange', name(vs[0]), name(vs[vs.length - 1]))
  return t('dbq.sched.cronDesc.dowList', joinList(vs.map(name), t))
}

/** "a, b ve c" / "a, b and c". */
export function joinList(items, t) {
  if (items.length <= 1) return items.join('')
  return t('dbq.sched.and', items.slice(0, -1).join(', '), items[items.length - 1])
}

function timeText(c, t) {
  const { minute, hour } = c
  if (minute.any && hour.any) return t('dbq.sched.cronDesc.everyMinute')
  if (minute.step && hour.any) return t('dbq.sched.cronDesc.everyNMinutes', minute.step)
  if (minute.step && !hour.any && consecutive(hour.values)) {
    return t('dbq.sched.cronDesc.everyNMinutesBetween', minute.step, `${pad2(hour.values[0])}:00`, `${pad2(hour.values[hour.values.length - 1])}:59`)
  }
  if (minute.any || minute.values.length !== 1) {
    // dakika listesi (0,30) + saat yıldız → "her saat 00 ve 30. dakikada" gibi kurallar açıklanmaz
    if (!hour.any && !minute.any && minute.values.length <= 4 && hour.values.length <= 6) {
      const times = []
      for (const h of hour.values) for (const m of minute.values) times.push(`${pad2(h)}:${pad2(m)}`)
      return t('dbq.sched.cronDesc.atTimes', joinList(times, t))
    }
    return null
  }
  const m = minute.values[0]
  if (hour.any) return m === 0 ? t('dbq.sched.cronDesc.hourly') : t('dbq.sched.cronDesc.hourlyAt', m)
  if (hour.step) return t('dbq.sched.cronDesc.everyNHours', hour.step, pad2(m))
  if (hour.values.length > 2 && consecutive(hour.values)) {
    return t('dbq.sched.cronDesc.hourlyBetween', `${pad2(hour.values[0])}:${pad2(m)}`, `${pad2(hour.values[hour.values.length - 1])}:${pad2(m)}`)
  }
  if (hour.values.length > 8) return null
  return t('dbq.sched.cronDesc.atTimes', joinList(hour.values.map((h) => `${pad2(h)}:${pad2(m)}`), t))
}

/**
 * Cron'un insan dilindeki açıklaması ("İş günleri (Pzt–Cum) saat 09:00") ya da null — çözülemeyen, saniye düzeyinde
 * özel, hem ayın günü hem haftanın günü kısıtlı ifadeler açıklanmaz (sunucu yine de doğrular ve önizler).
 */
export function describeCron(expr, t) {
  const c = parseCron(expr)
  if (!c) return null
  // saniye alanı yalnız "0" (5 alanlı ifadenin başına sunucunun eklediği) — saniye düzeyindeki ifade açıklanmaz
  if (c.second.any || c.second.values.length !== 1 || c.second.values[0] !== 0) return null
  if (!c.dom.any && !c.dow.any) return null
  const time = timeText(c, t)
  if (!time) return null
  let days
  if (c.dom.any && c.dow.any) days = t('dbq.sched.cronDesc.everyDay')
  else if (!c.dow.any) days = dowText(c.dow.values, t)
  else {
    if (c.dom.values.length > 6) return null
    days = t('dbq.sched.cronDesc.monthDays', joinList(c.dom.values.map((d) => t('dbq.sched.cronDesc.dayOrdinal', d)), t))
  }
  let text = t('dbq.sched.cronDesc.compose', days, time)
  if (!c.month.any) {
    if (c.month.values.length > 6) return null
    text += ' ' + t('dbq.sched.cronDesc.onlyMonths', joinList(c.month.values.map((m) => t(`dbq.sched.month.${m}`)), t))
  }
  return text
}

// ── Pencereler ──────────────────────────────────────────────────────────────────────────────────────────────────────────

const HHMM = /^([01]\d|2[0-3]):([0-5]\d)$/
export const isHhmm = (v) => HHMM.test(String(v || ''))
const minutes = (v) => { const m = HHMM.exec(v); return m ? Number(m[1]) * 60 + Number(m[2]) : NaN }

/** Yeni pencere — iş günleri 09:00–18:00. */
export function newWindow() { return { days: [1, 2, 3, 4, 5], from: '09:00', to: '18:00' } }

/** Pencere türü: 'normal' | 'overnight' (bitiş ≤ başlangıç → ertesi güne sarkar) | 'fullDay' (aynı saat = 24 saat). */
export function windowKind(w) {
  const a = minutes(w?.from), b = minutes(w?.to)
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null
  if (a === b) return 'fullDay'
  return b < a ? 'overnight' : 'normal'
}

/** Pencere hataları `{ days|from|to: kod }` (`dbq.sched.v.<kod>`). */
export function windowProblems(w) {
  const out = {}
  if (!Array.isArray(w?.days) || w.days.length === 0) out.days = 'days_required'
  if (!isHhmm(w?.from)) out.from = 'time_invalid'
  if (!isHhmm(w?.to)) out.to = 'time_invalid'
  return out
}

/** Gün kümesi kısa metni: Pzt–Cum / Cmt, Paz / Her gün. */
export function daysShort(days, t) {
  const vs = [...new Set((days || []).filter((d) => d >= 1 && d <= 7))].sort((a, b) => a - b)
  if (vs.length === 0) return t('dbq.sched.noDays')
  if (vs.length === 7) return t('dbq.sched.everyDayShort')
  const name = (d) => t(`dbq.sched.day.${d}`)
  if (vs.length > 2 && consecutive(vs)) return `${name(vs[0])}–${name(vs[vs.length - 1])}`
  return vs.map(name).join(', ')
}

/** "Pzt–Cum 09:00–18:00" · gece yarısını aşan: "Pzt–Cum 22:00 → ertesi gün 06:00" · aynı saat: "… tüm gün". */
export function windowSummary(w, t) {
  const kind = windowKind(w)
  const days = daysShort(w?.days, t)
  if (!kind) return t('dbq.sched.windowIncomplete', days)
  if (kind === 'fullDay') return t('dbq.sched.windowFullDay', days)
  if (kind === 'overnight') return t('dbq.sched.windowOvernight', days, w.from, w.to)
  return t('dbq.sched.windowNormal', days, w.from, w.to)
}

// ── Ek tarihler ────────────────────────────────────────────────────────────────────────────────────────────────────────

const YMD = /^(\d{4})-(\d{2})-(\d{2})$/
export function isYmd(v) {
  const m = YMD.exec(String(v || ''))
  if (!m) return false
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3])
}

/** Tarih ekle — tekil, sıralı, ≤ 100. Eklenemezse aynı dizi + neden döner. */
export function addSkipDate(list, day) {
  const cur = Array.isArray(list) ? list : []
  if (!isYmd(day)) return { list: cur, problem: 'date_invalid' }
  if (cur.includes(day)) return { list: cur, problem: 'date_duplicate' }
  if (cur.length >= MAX_SKIP_DATES) return { list: cur, problem: 'dates_max' }
  return { list: [...cur, day].sort(), problem: null }
}

// ── İstanbul saatiyle biçim ────────────────────────────────────────────────────────────────────────────────────────────

/** Sunucu UTC ISO (Z'siz) → "Pzt 03.11.2026 09:00" (İstanbul). Bozuk damga olduğu gibi. */
export function formatRun(iso, lang = 'tr') {
  if (!iso) return '—'
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return String(iso)
  const loc = lang === 'en' ? 'en-GB' : 'tr-TR'
  const wd = new Intl.DateTimeFormat(loc, { timeZone: TZ, weekday: 'short' }).format(d)
  const dt = new Intl.DateTimeFormat(loc, { timeZone: TZ, day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(d)
  return `${wd} ${dt}`
}

/** "yyyy-MM-dd" → "31 Aralık 2026, Perşembe" (takvim günü; saat dilimi kayması yok). */
export function formatDay(ymd, lang = 'tr') {
  if (!isYmd(ymd)) return String(ymd || '—')
  const [y, m, d] = ymd.split('-').map(Number)
  const date = new Date(Date.UTC(y, m - 1, d, 12))
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { timeZone: 'UTC', day: 'numeric', month: 'long', year: 'numeric', weekday: 'long' }).format(date)
}

/** Önizleme isteğinin gövdesi — yalnız zamanlama alanları (sunucu sözleşmesi §1). */
export function previewBody(v) {
  return {
    schedule_mode: v?.schedule_mode === 'CRON' ? 'CRON' : 'INTERVAL',
    interval_seconds: v?.interval_seconds ?? null,
    cron_expression: v?.cron_expression ?? null,
    windows: Array.isArray(v?.windows) ? v.windows : [],
    blackouts: Array.isArray(v?.blackouts) ? v.blackouts : [],
    skip_holidays: !!v?.skip_holidays,
    skip_dates: Array.isArray(v?.skip_dates) ? v.skip_dates : [],
  }
}
