/**
 * Aylık Yönetici Özeti — saf model (React yok; birim testleri doğrudan sınar).
 *
 * Sunucu her bölümü GENEL bir yapıyla gönderir (`SectionResult`: hükümler, gösterge kutuları, tablolar, notlar). Ekran
 * bölümleri tanımaz: metinleri `exec.<bölüm>.<tür>.<kod>` anahtarlarıyla iki dilde kurar, anahtar yoksa sunucunun Türkçe
 * metnine düşer (yeni bir bölüm sağlayıcısı i18n eklenmeden de okunur görünür). Değerleri `format`'a göre KENDİ dilinde
 * biçimler (sunucu ham sayı yollar).
 */
import { MONITOR_LABEL_KEY } from '../palette/paletteModel.js'

/** Özetin URL önekli durumu: ay, canlı hesap, ayar penceresi. */
export const EX_MONTH = 'ex_m'
export const EX_LIVE = 'ex_live'
export const EX_CFG = 'ex_cfg'

export const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/

/** Bölüm durumu → shadcn Badge varyantı. */
export const STATUS_VARIANT = Object.freeze({
  ok: 'outline', attention: 'warning', critical: 'destructive', error: 'warning', no_data: 'secondary',
})

/** Ton → metin rengi (değer vurgusu). Renk TEK taşıyıcı değildir: durum ayrıca metinle yazılır. */
export const TONE_TEXT = Object.freeze({
  ok: 'text-success', warn: 'text-warning', bad: 'text-destructive', info: 'text-primary', neutral: 'text-foreground',
})

/** Biçimi sağa yaslanan (sayısal) sütun türleri. */
export const NUMERIC_TYPES = new Set(['int', 'num', 'pct', 'pp', 'minutes', 'days', 'pct_change'])

/** Yanıt beklenen biçimde mi? */
export function isSummaryPayload(d) {
  return !!d && typeof d === 'object' && !Array.isArray(d) && typeof d.month === 'string' && Array.isArray(d.sections)
}

/** "2026-09" → yerel ay adı ("Eylül 2026" / "September 2026"). Ay kaynağı UTC değil, takvim ayıdır. */
export function monthLabel(month, lang = 'tr') {
  if (!MONTH_RE.test(String(month || ''))) return String(month ?? '')
  const [y, m] = month.split('-').map(Number)
  try {
    const s = new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { month: 'long', year: 'numeric', timeZone: 'UTC' })
      .format(new Date(Date.UTC(y, m - 1, 1)))
    return s.charAt(0).toLocaleUpperCase(lang === 'en' ? 'en-GB' : 'tr-TR') + s.slice(1)
  } catch {
    return month
  }
}

function locale(lang) { return lang === 'en' ? 'en-GB' : 'tr-TR' }

/** Sayı, en çok `digits` ondalık (sondaki sıfırlar atılır), dil ayırıcılarıyla. */
export function fmtNumber(v, lang = 'tr', digits = 2) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  return new Intl.NumberFormat(locale(lang), { maximumFractionDigits: digits }).format(Number(v))
}

/** Yüzde: TR "%99,95", EN "99.95%". */
export function fmtPct(v, lang = 'tr', digits = 3) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = fmtNumber(v, lang, digits)
  return lang === 'en' ? `${n}%` : `%${n}`
}

/** Dakika → "45 dk" / "2 sa 5 dk" / "3 gün 4 sa" (EN: min / h / d). */
export function fmtMinutes(v, t) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const total = Math.round(Number(v))
  if (total < 60) return t('exec.dur.min', total)
  const h = Math.floor(total / 60)
  const m = total % 60
  if (h < 24) return m === 0 ? t('exec.dur.h', h) : t('exec.dur.hm', h, m)
  const d = Math.floor(h / 24)
  const hh = h % 24
  return hh === 0 ? t('exec.dur.d', d) : t('exec.dur.dh', d, hh)
}

/** ISO gün ya da UTC damga → gün (Türkiye saati). */
export function fmtDate(v, lang = 'tr') {
  if (!v) return '—'
  const s = String(v)
  const day = /^\d{4}-\d{2}-\d{2}$/.test(s)
  const d = new Date(day ? `${s}T12:00:00Z` : (/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`))
  if (Number.isNaN(d.getTime())) return s
  return new Intl.DateTimeFormat(locale(lang), { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'Europe/Istanbul' }).format(d)
}

/** UTC damga → gün + saat (Türkiye saati). */
export function fmtDateTime(v, lang = 'tr') {
  if (!v) return '—'
  const s = String(v)
  const d = new Date(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`)
  if (Number.isNaN(d.getTime())) return s
  return new Intl.DateTimeFormat(locale(lang), {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul',
  }).format(d)
}

/**
 * Değeri biçimine göre yazar — tablo hücresi, gösterge ve biçimli parametre için TEK biçimleyici.
 * Boş değerin anlamı türe göre (takımsız / gruplanmamış / seviyesiz).
 */
export function formatValue(value, format, { t, lang = 'tr' } = {}) {
  const f = format || 'text'
  if (value == null || value === '') {
    if (f === 'team') return t('exec.team.none')
    if (f === 'service') return t('exec.service.ungrouped')
    if (f === 'tier') return t('exec.tier.none')
    return '—'
  }
  switch (f) {
    case 'int': return fmtNumber(Math.round(Number(value)), lang, 0)
    case 'num': return fmtNumber(value, lang, 2)
    case 'pct': return fmtPct(value, lang)
    case 'pp': {
      const n = Number(value)
      if (Math.abs(n) < 0.005) return t('exec.pp.zero')
      return t(n > 0 ? 'exec.pp.up' : 'exec.pp.down', fmtNumber(Math.abs(n), lang, 2))
    }
    case 'pct_change': {
      const n = Number(value)
      if (Math.abs(n) < 0.05) return t('exec.change.same')
      return t(n > 0 ? 'exec.change.up' : 'exec.change.down', fmtPct(Math.abs(n), lang, 1))
    }
    case 'minutes': return fmtMinutes(value, t)
    case 'days': {
      const n = Number(value)
      if (n < 0) return t('exec.days.ago', Math.abs(n))
      if (n === 0) return t('exec.days.today')
      return t('exec.days.left', n)
    }
    case 'date': return fmtDate(value, lang)
    case 'datetime': return fmtDateTime(value, lang)
    case 'bool': return value ? t('exec.bool.yes') : t('exec.bool.no')
    case 'tier': {
      const n = Number(value)
      return n >= 1 && n <= 4 ? t('exec.tier.n', n) : t('exec.tier.none')
    }
    case 'monitor_type': {
      const key = MONITOR_LABEL_KEY[value] || (value === 'cert' ? 'nav.all' : value === 'uptime' ? 'nav.uptime' : null)
      return key ? t(key) : String(value)
    }
    case 'status': return t(`exec.tone.${value}`)
    case 'renewal_class':
    case 'overdue_reason': {
      const k = `exec.enum.${f}.${value}`
      const s = t(k)
      return s === k ? String(value) : s
    }
    default: return String(value)
  }
}

/** Parametre: `{value, format}` ise biçimlenir, çıplaksa olduğu gibi. */
export function formatParam(p, ctx) {
  if (p && typeof p === 'object' && 'format' in p) return formatValue(p.value, p.format, ctx)
  if (typeof p === 'number') return fmtNumber(p, ctx.lang, 2)
  return p == null ? '' : String(p)
}

/**
 * i18n anahtarıyla metin; anahtar sözlükte yoksa (useT anahtarın kendisini döner) sunucunun Türkçe metnine düşer.
 * Parametreler `{0}…` yer tutucularına biçimlenerek girer.
 */
export function localized(t, key, fallback, params = [], ctx = {}) {
  const formatted = (params || []).map((p) => formatParam(p, { t, lang: ctx.lang || 'tr' }))
  const s = t(key, ...formatted)
  if (s === key) return fallback
  // Sunucu bu kez parametre göndermediyse (ör. seviye bazlı hedef) doldurulamayan yer tutucu kalmasın → sunucu metni
  if (/\{\d+\}/.test(s) && fallback != null) return fallback
  return s
}

/** Bölüm anahtarları. */
export const keys = Object.freeze({
  title: (sec) => `exec.${sec}.title`,
  verdict: (sec, code) => `exec.${sec}.verdict.${code}`,
  kpi: (sec, code) => `exec.${sec}.kpi.${code}`,
  hint: (sec, code) => `exec.${sec}.kpi.${code}.hint`,
  table: (sec, code) => `exec.${sec}.table.${code}`,
  col: (code) => `exec.col.${code}`,
  note: (sec, code) => `exec.${sec}.note.${code}`,
})

/** Bölüm başlığı. */
export function sectionTitle(t, section) {
  return localized(t, keys.title(section.key), section.title || section.key)
}

/** Hüküm metni. */
export function verdictText(t, sectionKey, v, lang) {
  return localized(t, keys.verdict(sectionKey, v.code), v.text, v.params, { lang })
}

/** Not metni (bölüme özgü yoksa ortak `exec.note.<kod>`). */
export function noteText(t, sectionKey, n, lang) {
  const specific = keys.note(sectionKey, n.code)
  const s = localized(t, specific, null, n.params, { lang })
  if (s != null) return s
  return localized(t, `exec.note.${n.code}`, n.text, n.params, { lang })
}

/** Gösterge etiketi + ipucu + değer + fark — çizim için hazır. */
export function kpiView(t, sectionKey, k, lang) {
  const ctx = { t, lang }
  const delta = k.delta == null ? null
    : k.delta_format === 'pp' ? formatValue(k.delta, 'pp', ctx)
      : k.delta_format === 'pct_change' ? formatValue(k.delta, 'pct_change', ctx)
        : fmtNumber(k.delta, lang, 1)
  return {
    code: k.code,
    label: localized(t, keys.kpi(sectionKey, k.code), k.label),
    value: formatValue(k.value, k.format, ctx),
    tone: k.tone || 'neutral',
    hint: k.hint == null && !(k.hint_params || []).length ? null
      : localized(t, keys.hint(sectionKey, k.code), k.hint, k.hint_params, { lang }),
    delta,
    deltaTone: k.delta_tone || 'neutral',
  }
}

/** Sütun etiketi. */
export function columnLabel(t, col) {
  return localized(t, keys.col(col.code), col.label)
}

/** Ay seçenekleri (sunucu listesi; yoksa bu ay + 12). */
export function monthOptions(months, lang = 'tr', now = new Date()) {
  const list = Array.isArray(months) && months.length ? months : fallbackMonths(now)
  return list.filter((m) => MONTH_RE.test(m?.month)).map((m) => ({
    value: m.month, label: monthLabel(m.month, lang), current: !!m.current, status: m.status || null,
  }))
}

function fallbackMonths(now) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit' })
    .formatToParts(now)
  let y = Number(parts.find((p) => p.type === 'year')?.value)
  let m = Number(parts.find((p) => p.type === 'month')?.value)
  const out = []
  for (let i = 0; i <= 12; i++) {
    out.push({ month: `${y}-${String(m).padStart(2, '0')}`, current: i === 0 })
    m -= 1
    if (m === 0) { m = 12; y -= 1 }
  }
  return out
}

/**
 * Cron (`0 m h d * *`) ↔ basit zamanlama { day, time }. Bu kalıba uymayan ifade `custom` döner (ham ifade düzenlenir).
 */
export function parseMonthlyCron(expr) {
  const p = String(expr || '').trim().split(/\s+/)
  if (p.length === 6 && p[0] === '0' && /^\d{1,2}$/.test(p[1]) && /^\d{1,2}$/.test(p[2]) && /^\d{1,2}$/.test(p[3])
      && p[4] === '*' && p[5] === '*') {
    const day = Number(p[3])
    const h = Number(p[2])
    const m = Number(p[1])
    if (day >= 1 && day <= 28 && h <= 23 && m <= 59) {
      return { custom: false, day, time: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}` }
    }
  }
  return { custom: true, day: 1, time: '09:00' }
}

export function buildMonthlyCron(day, time) {
  const d = Math.min(28, Math.max(1, Number(day) || 1))
  const [hh = '9', mm = '0'] = String(time || '09:00').split(':')
  return `0 ${Number(mm) || 0} ${Number(hh) || 0} ${d} * *`
}

/** Virgül/noktalı virgül/boşlukla ayrılmış adresler → tekil liste (büyük/küçük harf duyarsız). */
export function parseEmails(text) {
  const seen = new Set()
  const out = []
  for (const part of String(text || '').split(/[,;\s]+/)) {
    const e = part.trim()
    if (!e) continue
    const k = e.toLowerCase()
    if (seen.has(k)) continue
    seen.add(k)
    out.push(e)
  }
  return out
}

export const EMAIL_RE = /^[^@\s,;]+@[^@\s,;]+\.[^@\s,;]+$/

/** Geçersiz adresler (form alanı hatası için). */
export function invalidEmails(text) {
  return parseEmails(text).filter((e) => e.length > 254 || !EMAIL_RE.test(e))
}

/** Günlük erişilebilirlik serisi → grafik noktaları (kısa gün etiketi, değer, kontrol). */
export function dailyPoints(daily, lang = 'tr') {
  if (!Array.isArray(daily)) return []
  return daily.filter((p) => p && typeof p.date === 'string').map((p) => ({
    date: p.date,
    label: String(Number(p.date.slice(8, 10))),
    full: fmtDate(p.date, lang),
    availability: typeof p.availability === 'number' ? p.availability : null,
    checks: Number(p.checks) || 0,
  }))
}

/** Grafik y ekseni alt sınırı: en düşük değer ile hedefin altına biraz pay (0,05 puana yuvarlanır). */
export function yFloor(points, target) {
  const vals = points.map((p) => p.availability).filter((v) => typeof v === 'number')
  const min = Math.min(target ?? 100, ...(vals.length ? vals : [100]))
  const span = Math.max(0.1, 100 - min)
  return Math.max(0, Math.floor((min - span * 0.25) * 20) / 20)
}

/** Yenileme sınıfları → yüzdeli dilimler (sabit sıra: zamanında, geç, son dakika, süresi dolduktan sonra). */
export const RENEWAL_CLASSES = Object.freeze([
  { key: 'ON_TIME', color: 'var(--success)' },
  { key: 'LATE', color: 'var(--warning)' },
  { key: 'LAST_MINUTE', color: 'var(--chart-3)' },
  { key: 'AFTER_EXPIRY', color: 'var(--destructive)' },
])

export function renewalSegments(byClass) {
  const total = RENEWAL_CLASSES.reduce((s, c) => s + (Number(byClass?.[c.key]) || 0), 0)
  return RENEWAL_CLASSES.map((c) => {
    const n = Number(byClass?.[c.key]) || 0
    return { ...c, count: n, pct: total ? (n * 100) / total : 0 }
  }).map((s) => ({ ...s, total }))
}

/** Ekran sırası: bölümler order'a göre (sunucu zaten sıralı yollar; savunmacı). */
export function orderedSections(summary) {
  return [...(summary?.sections || [])].sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
}
