/*
 * Kanal teknik hata raporu — saf yardımcılar (2026-10-13). Sunucu kuralının istemci aynası: şablon türleri ve sınırlar
 * (TechErrorTemplate.java), yer tutucular, ay adları, sayı biçimi, grafik ızgarası eşlemesi (TechErrorReportPdfWriter),
 * durum sınıfları. React'ten bağımsız — testler doğrudan çağırır.
 */

export const TE_VIEW = 'te_view'
export const TE_CHANNEL = 'te_ch'
export const TE_TAB = 'te_tab'
export const TE_REPORT = 'te_rep'
export const TE_TEAM = 'te_team'

export const VIEW_CHANNELS = 'channels'
export const VIEW_CHANNEL = 'channel'
export const VIEW_REPORT = 'report'
export const TAB_REPORTS = 'reports'
export const TAB_TEMPLATE = 'template'

/** Sunucu ile aynı sınırlar (TechErrorTemplate / TechErrorValues / TechErrorImageValidator). */
export const LIMITS = Object.freeze({
  sections: 20,
  fieldsPerSection: 30,
  slots: 40,
  title: 200,
  label: 200,
  hint: 300,
  issueLabel: 120,
  sectionText: 10000,
  issues: 30,
  topic: 120,
  shortText: 500,
  longText: 10000,
  channelName: 120,
  description: 500,
  // En büyük sayı (TechErrorValues.MAX_NUMBER) — kısaltmayla 1000T'ye kadar
  maxNumber: 1e15,
  imageBytes: 5 * 1024 * 1024,
  reportImageBytes: 40 * 1024 * 1024,
  imageSide: 6000,
  imagePixels: 12000000,
  // Yönetici özeti ve tablo (2026-10-14; TechErrorTemplate.MAX_SUMMARY_* / MAX_COLUMNS / MAX_ROWS, TechErrorValues.MAX_CELL_TEXT)
  summaryLines: 20,
  summaryLine: 500,
  columns: 10,
  rows: 40,
  columnLabel: 120,
  cellText: 200,
  // Otomatik trend (2026-10-15, Faz C; TechErrorTemplate.MAX_TREND_SERIES)
  trendSeries: 8,
  // Yönetici özetinin renkli kutucukları (2026-10-17, 4b; TechErrorTemplate.MAX_TILES)
  tiles: 6,
  // Saklanabilir en büyük yüzde (TechErrorValues.MAX_PERCENT) — üstündeki hesap boş kalır
  maxPercent: 1000000,
})

/** Yer tutucu çözümünün üst sınırı (TechErrorTemplate.MAX_RESOLVED) — başlık / etiket bu uzunlukta "…" ile kesilir. */
export const MAX_RESOLVED = 300
/** Metin bölümü gövdesinin sınırı (TechErrorTemplate.MAX_RESOLVED_TEXT) — PDF'teki metinle aynı uzunluk. */
export const MAX_RESOLVED_TEXT = 12000

function capResolved(s, max = MAX_RESOLVED) {
  if (s.length <= max) return s
  let end = max - 1
  const code = s.charCodeAt(end - 1)
  if (code >= 0xd800 && code <= 0xdbff) end--
  return `${s.slice(0, end)}…`
}

export const KINDS = ['metrics', 'issues', 'text', 'images', 'summary', 'table', 'trend']
export const FIELD_TYPES = ['number', 'percent', 'yesno', 'text', 'textarea']
export const COLUMN_TYPES = ['number', 'percent', 'text']
export const WIDTHS = ['half', 'full']
const ID_RE = /^[a-z0-9][a-z0-9_-]{0,39}$/

const MONTHS = {
  tr: ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık'],
  en: ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'],
}

export function monthName(month, lang) {
  const list = MONTHS[lang === 'en' ? 'en' : 'tr']
  return month >= 1 && month <= 12 ? list[month - 1] : String(month ?? '')
}

function resolveRaw(pattern, channel, year, month, lang) {
  if (pattern == null) return ''
  const ch = channel || ''
  const mo = monthName(month, lang)
  const yr = String(year ?? '')
  return String(pattern)
    .split('{kanal}').join(ch).split('{channel}').join(ch)
    .split('{ay}').join(mo).split('{month}').join(mo)
    .split('{yil}').join(yr).split('{yıl}').join(yr).split('{year}').join(yr)
}

/** `{kanal}` / `{ay}` / `{yil}` (ve EN karşılıkları) → kanal, ay adı, yıl. Sunucudaki TechErrorTemplate.resolve ile aynı. */
export function resolvePlaceholders(pattern, channel, year, month, lang) {
  return capResolved(resolveRaw(pattern, channel, year, month, lang))
}

/**
 * Metin bölümü gövdesi (TechErrorTemplate.resolveText): başlık sınırı (300) burada UYGULANMAZ — 2. inceleme turu:
 * 300'de kesilen varsayılan metin düzenleyicide kısa görünüyor, ilk tuşta kesik hâli kaydediliyordu.
 */
export function resolveTextPlaceholders(pattern, channel, year, month, lang) {
  return capResolved(resolveRaw(pattern, channel, year, month, lang), MAX_RESOLVED_TEXT)
}

/** Anomali başlığı kalıbı: `{konu}` / `{topic}` → konu; konu boşsa yer tutucu düşer. */
export function resolveIssueTitle(pattern, topic) {
  const t = (topic || '').trim()
  return capResolved(String(pattern || '').split('{konu}').join(t).split('{topic}').join(t).replace(/\s{2,}/g, ' ').trim())
}

/** Anomali başlığının gösterilen hâli (sunucudaki Issue.displayTitle). */
export function issueDisplayTitle(issue, pattern) {
  if (issue?.title && issue.title.trim() && !issue.title_auto) return issue.title.trim()
  if (issue?.topic && issue.topic.trim()) return resolveIssueTitle(pattern, issue.topic)
  return (issue?.title || '').trim()
}

// ── Sayılar ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * Sayı kısaltmaları (2026-10-16, kullanıcı kararı: raporlama aracı rakamları "7,42G · 130,1M · 681,6k" diye verir):
 * Qlik sonekleri + Türkçe / İngilizce yazımlar → 10'un kuvveti. Tek başına "B" YOK — Türkçede bin, İngilizcede milyar
 * demek; karışırsa rakam milyon kat kayar ({@link isAmbiguousB}). Büyük / küçük harf ve Türkçe İ / ı fark etmez.
 */
export const NUMBER_ABBREVIATIONS = Object.freeze([
  Object.freeze({ exp: 3, words: Object.freeze(['k', 'bin', 'thousand']) }),
  Object.freeze({ exp: 6, words: Object.freeze(['m', 'mn', 'mio', 'milyon', 'million']) }),
  Object.freeze({ exp: 9, words: Object.freeze(['g', 'mr', 'mlr', 'milyar', 'bn', 'billion']) }),
  Object.freeze({ exp: 12, words: Object.freeze(['t', 'tn', 'trilyon', 'trillion']) }),
])

const ABBREV_EXP = new Map(NUMBER_ABBREVIATIONS.flatMap((a) => a.words.map((w) => [w, a.exp])))
const ABBREV_WORDS = [...ABBREV_EXP.keys()]
const suffixKey = (s) => s.toLocaleLowerCase('tr-TR').replace(/ı/g, 'i')

/** Rakam + sonek ayrımı: "7,42G" → { sign, mantissa: "7,42", suffix: "g" }; rakamla başlamıyorsa null. */
function splitAbbrev(raw) {
  const s = String(raw ?? '').trim().replace(/\s+/g, '').replace(/%/g, '')
  const m = /^([+-]?)([0-9.,]*)(.*)$/.exec(s)
  if (!m) return null
  return { sign: m[1], mantissa: m[2], suffix: suffixKey(m[3]) }
}

/** Tek başına "B" soneki mi ("7,42B", "12 b")? Kabul edilmez; alanın altında ne yazılacağı söylenir. */
export function isAmbiguousB(raw) {
  const p = splitAbbrev(raw)
  return !!p && /\d/.test(p.mantissa) && p.suffix === 'b'
}

/** Rakamın sonekli (kısaltmalı) yazılıp yazılmadığı — alanın altında tam sayı gösterilir. */
export function hasAbbreviation(raw) {
  const p = splitAbbrev(raw)
  return !!p && /\d/.test(p.mantissa) && ABBREV_EXP.has(p.suffix)
}

/** Geçerli kısaltma üsleri (TechErrorValues.exponent): 3 / 6 / 9 / 12. */
export const UNIT_EXPONENTS = Object.freeze([3, 6, 9, 12])

/** Yazımın kısaltma üssü ("4.4G" → 9, "681,6 bin" → 3); soneksiz ya da tanınmayan sonek → null. */
export function abbreviationExp(raw) {
  const p = splitAbbrev(raw)
  return p && /\d/.test(p.mantissa) && ABBREV_EXP.has(p.suffix) ? ABBREV_EXP.get(p.suffix) : null
}

/** Kısaltma harfi (Qlik, iki dilde aynı — TechErrorFormat.letter): 3 k, 6 M, 9 G, 12 T. */
const ABBREV_LETTER = Object.freeze({ 3: 'k', 6: 'M', 9: 'G', 12: 'T' })

/** Anomali konusu adedinin kısaltma anahtarı (TechErrorValues.issueUnitKey). */
export function issueUnitKey(sectionId, issueId, part) {
  return `issues.${sectionId}.${issueId}.${part}`
}

/** Tablo sayı hücresinin kısaltma anahtarı (TechErrorValues.tableUnitKey). */
export function tableUnitKey(sectionId, rowId, colId) {
  return `tables.${sectionId}.${rowId}.${colId}`
}

/** |x| / 10^exp'in TAM ondalık basamakları {int, frac} (kayan nokta bölmesi yok — Java BigDecimal.movePointLeft). */
function shiftedDigits(x, exp) {
  const { all, point } = decimalParts(x)
  const p = point - exp
  let int
  let frac
  if (p <= 0) { int = '0'; frac = '0'.repeat(-p) + all } else if (p >= all.length) { int = all + '0'.repeat(p - all.length); frac = '' } else { int = all.slice(0, p); frac = all.slice(p) }
  int = int.replace(/^0+(?=\d)/, '')
  return { int, frac }
}

/** Basamakları `scale` ondalığa yarım yukarı yuvarlar (dizgi üzerinde — Java setScale(scale, HALF_UP)); sondaki sıfırlar düşer. */
function roundDigits({ int, frac }, scale) {
  let i = int
  let f = frac
  if (f.length > scale) {
    const up = f[scale] >= '5'
    let n = BigInt(i + f.slice(0, scale))
    if (up) n += 1n
    const str = n.toString().padStart(scale + 1, '0')
    i = str.slice(0, str.length - scale)
    f = str.slice(str.length - scale)
  }
  let end = f.length
  while (end > 0 && f[end - 1] === '0') end--
  return { int: i, frac: f.slice(0, end) }
}

/**
 * Basamakları dilin ondalık ayracıyla birleştirir — binlik GRUPLAMASI YOK (inceleme 2026-10-18: harften önceki gruplanmış
 * kısım "4.400M" geri okununca 4,4 milyon olurdu).
 */
function joinDigits({ int, frac }, lang) {
  return frac ? `${int}${lang === 'en' ? '.' : ','}${frac}` : int
}

/** Kısaltmalı yazımın en çok ondalık hanesi (TechErrorFormat.ABBREV_MAX_DECIMALS). */
export const ABBREV_MAX_DECIMALS = 6

/**
 * Değer bu üsle kısaltılabilir mi — sunucudaki TechErrorFormat.abbreviable ile AYNI: geçerli üs, sonlu değer ve 10^exp ile
 * kaydırılan değer en çok 6 ondalıkla TAM (yuvarlamasız). "4,123456789G" kısaltmasıyla saklanmaz / gösterilmez (inceleme
 * 2026-10-18: 6 haneye yuvarlanınca 4.123.457.000 görünüyor, sonraki düzenleme yuvarlanmış değeri kaydediyordu). Sıfır hiçbir
 * üsle kısaltılmaz ("0G" yeşil tikin yanında "0G" basılıyordu — her yerde "0"; üssü gönderilmez, sunucu da düşürür).
 */
export function abbreviable(n, exp) {
  if (!ABBREV_LETTER[exp] || n == null || n === '' || !Number.isFinite(Number(n)) || Number(n) === 0) return false
  const { frac } = shiftedDigits(Math.abs(Number(n)) + 0, exp)
  let end = frac.length
  while (end > 0 && frac[end - 1] === '0') end--
  return end <= ABBREV_MAX_DECIMALS
}

/**
 * Kısaltmayla girilmiş sayının gösterimi — sunucudaki TechErrorFormat.abbreviated ile AYNI (ortak doğruluk tablosu
 * TechErrorNumberDisplayTest.ABBREV_TABLE): değer 10^exp ile ondalık gösterimde kaydırılır, sondaki sıfırlar düşer, harf
 * (k / M / G / T) eklenir, boşluk yok — 4.400.000.000 / 9 → TR "4,4G", EN "4.4G". Sonekten önceki kısım HİÇ GRUPLANMAZ
 * (inceleme 2026-10-18): "4.400M" düzenleyicide geri okununca 4,4 milyon olurdu (sonekten önceki tek ayraç ondalıktır) — PDF'ten
 * / ipucundan kopyalayan 1000 kat küçük değer girerdi; gruplamasız metin İKİ dilde de aynı sayıya döner. Kaydırılan değer 6
 * ondalığa sığmıyorsa (`abbreviable`) tam sayı biçimi; geçersiz üs → tam sayı biçimi.
 */
export function formatAbbreviated(n, exp, lang) {
  if (n == null || n === '' || !Number.isFinite(Number(n))) return '—'
  const letter = ABBREV_LETTER[exp]
  const x = Number(n) + 0                     // -0 → 0 (Java BigDecimal'de eksi sıfır yok: "0")
  if (!letter || !abbreviable(x, exp)) return formatNumber(x, lang)
  const d = roundDigits(shiftedDigits(x, exp), ABBREV_MAX_DECIMALS)
  const zero = d.int === '0' && d.frac === ''
  return `${x < 0 && !zero ? '-' : ''}${joinDigits(d, lang)}${letter}`
}

/**
 * Farkın kısa yazımı — sunucudaki TechErrorFormat.compactQlik ile AYNI: 1000'in altı tam (en çok 2 ondalık), üstü büyüklüğe
 * göre k / M / G / T, en çok 2 ondalık yarım yukarı; yuvarlanınca 1000 olan bir üst birime geçer. Harften önceki kısım
 * gruplanmaz ("2000T" — formatAbbreviated ile aynı kural). `a` ≥ 0.
 */
export function compactQlik(a, lang) {
  const v = Math.abs(Number(a)) + 0
  const small = roundDigits(shiftedDigits(v, 0), 2)
  if (small.int.length < 4) return joinDigits(small, lang)
  let exp = v < 1e6 ? 3 : v < 1e9 ? 6 : v < 1e12 ? 9 : 12
  let d = roundDigits(shiftedDigits(v, exp), 2)
  if (d.int.length >= 4 && exp < 12) {
    exp += 3
    d = roundDigits(shiftedDigits(v, exp), 2)
  }
  return `${joinDigits(d, lang)}${ABBREV_LETTER[exp]}`
}

/**
 * Sıfır adedin yanına yeşil yuvarlak tik konur mu — sunucudaki TechErrorFormat.zeroOk ile AYNI (ortak doğruluk tabloları
 * TechErrorNumberDisplayTest.ZERO_TABLE + RATE_BASE_TABLE): yalnız SAYI alanı, otomatik hesap yok, "daha iyi" yönü `higher`
 * değil, değer tam 0 (sayı türünde — boş / bilinmeyen 0 DEĞİLDİR) ve — şablonun alanları `fields` verildiyse — alan yalnız
 * ORAN TABANI değil (`rateBaseOnly`; inceleme 2026-10-18: "toplam işlem 0" veri yok demektir). Muaf alan çağıranın işi (muaf
 * alan çizilmez). Anomali konusunun adedinde işaret YOK (2026-10-18 kararı: PDF / e-posta da çizmiyordu).
 */
export function zeroOk(field, v, fields = null) {
  if (!field || field.type !== 'number' || field.calc?.kind) return false
  if (effectiveBetter(field) === 'higher') return false
  return zeroCount(v) && !rateBaseOnly(fields, field.id)
}

/**
 * Alan yalnız oran TABANI mı — sunucudaki TechErrorFormat.rateBaseOnly ile AYNI: bir `ratio` hesabının paydası (`den`) ve
 * hiçbir `ratio` hesabının payı (`num`) ya da çıkarılanı (`minus`) değil; `change` / `increase` hesabının `of`'u karara
 * katılmaz (tabanın değişimi izlenir). `fields` yoksa false.
 */
export function rateBaseOnly(fields, id) {
  if (!Array.isArray(fields) || id == null) return false
  let den = false
  for (const x of fields) {
    const c = x?.calc
    if (!c || c.kind !== 'ratio') continue
    if (c.num === id || c.minus === id) return false
    if (c.den === id) den = true
  }
  return den
}

/** Değer tam 0 mı (TechErrorFormat.zeroCount) — yalnız sayı türü. */
export function zeroCount(v) {
  return typeof v === 'number' && Number.isFinite(v) && v === 0
}

/** Ondalık gösterimi 10^exp ile KAYDIRIR (kayan nokta çarpımı yok: 7,42 × 10⁹ = 7.420.000.000 tam). */
function shiftDecimal(normalized, exp) {
  const [ip, fp = ''] = normalized.split('.')
  const frac = fp.padEnd(exp, '0')
  const whole = `${ip}${frac.slice(0, exp)}`.replace(/^0+(?=\d)/, '') || '0'
  const rest = frac.slice(exp)
  return Number(rest ? `${whole}.${rest}` : whole)
}

/**
 * Kullanıcının yazdığı sayıyı çözer: "1.250.000", "1,250,000", "0,42", "0.42", "%3,5", "3.5%". Belirsiz TEK ayraç
 * ("1.250") arayüz dilinin alışkanlığına göre okunur (TR: binlik, EN: ondalık) — ama yalnız sıfırla başlamayan bir
 * binlik grubuysa: "0.125" her dilde 0,125'tir (inceleme 2026-10-13: 125 okunuyordu). Yüzde alanında tek ayraç her
 * zaman ondalıktır ("%3.500" = 3,5). Çok ayraçlı yazım geçerli binlik gruplaması olmalı ("1.25.0" geçersiz).
 * Sayı alanında (yüzde değil) KISALTMA ({@link NUMBER_ABBREVIATIONS}): "7,42G" = 7.420.000.000, "681,6k" = 681.600 —
 * sonekli rakamda tek ayraç her zaman ondalıktır ("1.5M" = 1.500.000; kimse 1250 milyonu "1.250M" yazmaz).
 * Boş → null; geçersiz (tek başına "B" dahil) → NaN.
 */
export function parseNumber(raw, lang, { percent = false } = {}) {
  if (raw == null) return null
  let s = String(raw).trim().replace(/\s+/g, '').replace(/%/g, '')
  if (s === '') return null
  if (s.length > 40) return NaN
  let exp = 0
  const parts = splitAbbrev(s)
  if (parts && parts.suffix) {
    if (percent || !ABBREV_EXP.has(parts.suffix) || !/\d/.test(parts.mantissa)) return NaN
    exp = ABBREV_EXP.get(parts.suffix)
    s = parts.sign + parts.mantissa
  }
  const neg = s.startsWith('-')
  if (neg || s.startsWith('+')) s = s.slice(1)
  if (!/^[0-9.,]+$/.test(s)) return NaN
  const dots = (s.match(/\./g) || []).length
  const commas = (s.match(/,/g) || []).length
  const grouped = (str, sep) => new RegExp(`^\\d{1,3}(\\${sep}\\d{3})+$`).test(str)
  const thousands = (str, sep) => new RegExp(`^[1-9]\\d{0,2}(\\${sep}\\d{3})+$`).test(str)
  let normalized
  if (dots && commas) {
    // Son görülen ayraç ondalıktır; öncesi geçerli binlik gruplaması olmalı
    const dec = s.lastIndexOf(',') > s.lastIndexOf('.') ? ',' : '.'
    const grp = dec === ',' ? '.' : ','
    const at = s.lastIndexOf(dec)
    const intPart = s.slice(0, at)
    if (s.indexOf(dec) !== at || !grouped(intPart, grp)) return NaN
    normalized = `${intPart.split(grp).join('')}.${s.slice(at + 1)}`
  } else if (commas || dots) {
    const sep = commas ? ',' : '.'
    const count = commas || dots
    if (count > 1) {
      if (!grouped(s, sep)) return NaN
      normalized = s.split(sep).join('')
    } else {
      const habit = sep === ',' ? lang === 'en' : lang !== 'en'
      normalized = !percent && !exp && habit && thousands(s, sep) ? s.split(sep).join('') : s.replace(sep, '.')
    }
  } else {
    normalized = s
  }
  if (!/^\d*\.?\d*$/.test(normalized) || normalized === '.' ) return NaN
  const n = exp ? shiftDecimal(normalized, exp) : Number(normalized)
  if (!Number.isFinite(n)) return NaN
  return neg ? -n : n
}

/**
 * Yazılırken henüz TAMAMLANMAMIŞ sayı mı (tek işaret, tek ayraç, yarım binlik grubu: "-", ",", "1.250.0")? Bu
 * durumda yazarken hata gösterilmez; alan bırakılınca ({@code onBlur}) hâlâ geçersizse hata çıkar.
 */
export function isIncompleteNumber(raw) {
  const s = String(raw ?? '').trim().replace(/\s+/g, '').replace(/%/g, '')
  if (s === '' || s.length > 40) return false
  if (/^[+-]?[.,]?$/.test(s)) return true
  // Yazılmakta olan kısaltma ("7,42mi" → milyon / milyar): rakam geçerli, sonek bir kısaltmanın başı ama henüz değil
  const p = splitAbbrev(s)
  if (p && p.suffix && /\d/.test(p.mantissa) && !ABBREV_EXP.has(p.suffix)) {
    return ABBREV_WORDS.some((w) => w.startsWith(p.suffix)) && Number.isFinite(parseNumber(`${p.sign}${p.mantissa}`, 'en', { percent: true }))
  }
  return /^[+-]?\d{1,3}([.,]\d{3})*[.,]\d{0,2}$/.test(s) && Number.isNaN(parseNumber(s, 'tr')) && Number.isNaN(parseNumber(s, 'en'))
}

/** Sunucudaki TechErrorFormat ile aynı biçim: TR 1.250.000 / %0,42; EN 1,250,000 / 0.42%; en çok 4 ondalık. */
export function formatNumber(n, lang, maxFraction = 4) {
  if (n == null || !Number.isFinite(Number(n))) return '—'
  return new Intl.NumberFormat(lang === 'en' ? 'en-US' : 'tr-TR', { maximumFractionDigits: maxFraction }).format(Number(n))
}

export function formatPercent(n, lang) {
  if (n == null || !Number.isFinite(Number(n))) return '—'
  const x = Number(n)
  let v
  if (x !== 0 && Math.abs(x) < 0.01) {
    // Küçük yüzde 2 anlamlı haneyle (en çok 6 ondalık) — sunucu TechErrorFormat.percent ile aynı; 4 ondalık sınırı
    // %0,00004'ü sahte "%0" gösteriyordu (2026-10-16)
    const s = smallScale(x)
    v = new Intl.NumberFormat(lang === 'en' ? 'en-US' : 'tr-TR', { maximumFractionDigits: s }).format(roundHalfUp(x, s))
  } else {
    v = formatNumber(x, lang)
  }
  return lang === 'en' ? `${v}%` : `%${v}`
}

export function formatValue(field, value, lang, yesNo, unit = null) {
  if (value == null || value === '') return '—'
  switch (field?.type) {
    case 'number': return ABBREV_LETTER[unit] ? formatAbbreviated(value, unit, lang) : formatNumber(value, lang)
    case 'percent': return formatPercent(value, lang)
    case 'yesno': return yesNo(!!value)
    default: return String(value)
  }
}

/**
 * Anomali konusunun adedi (müşteri / hata) — sunucudaki TechErrorFormat.count(n, lang, unit) ile AYNI: kısaltma üssü varsa
 * "1,25M" (6 ondalığa sığmayan ya da sıfır değer tam sayıyla), yoksa tam sayı. Üs yalnız gösterimdir (pay sayıyla hesaplanır).
 */
export function formatCount(n, lang, unit = null) {
  if (n == null || n === '') return '—'
  return ABBREV_LETTER[unit] ? formatAbbreviated(n, unit, lang) : formatNumber(n, lang)
}

/** Yüzde değişim ipucu (yalnız gösterim — alanı doldurmaz): önceki → şimdiki. Önceki 0 ya da yoksa null. */
export function changePercent(prev, cur) {
  const p = Number(prev)
  const c = Number(cur)
  if (prev == null || cur == null || !Number.isFinite(p) || !Number.isFinite(c) || p === 0) return null
  return ((c - p) / Math.abs(p)) * 100
}

/** İki adetten oran ipucu (ör. kesinti kaynaklı / toplam). Payda 0 ya da yoksa null. */
export function ratioPercent(part, whole) {
  const a = Number(part)
  const b = Number(whole)
  if (part == null || whole == null || !Number.isFinite(a) || !Number.isFinite(b) || b === 0) return null
  return (a / b) * 100
}

// ── Zengin metin (TechErrorRichText aynası) ──────────────────────────────────────────────────────────

export const RICH_MAX_LEVEL = 2
export const RICH_MAX_BLOCKS = 2000

/**
 * Ayrıştırıcının boşluk tanımı — sunucudaki TechErrorRichText.ws ile AYNI küme: Java `Character.isWhitespace` artı
 * bölünmez boşluklar (U+00A0, U+2007, U+202F) ve U+FEFF. JS `trim()` kullanılmaz: kümesi Java'nınkinden farklıdır
 * (inceleme 2026-10-14: ekranda paragraf aralığı olan satır PDF'te boş paragraf basılıyordu).
 */
export function isRichSpace(ch) {
  const c = ch.charCodeAt(0)
  if (c === 0x20 || (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x1f)) return true
  if (c < 0xa0) return false
  return c === 0xa0 || c === 0x1680 || (c >= 0x2000 && c <= 0x200a) || c === 0x2028 || c === 0x2029 || c === 0x202f
    || c === 0x205f || c === 0x3000 || c === 0xfeff
}

/** {@link isRichSpace} kümesine göre iki uçtan kırpar (sunucudaki TechErrorRichText.strip). */
export function richStrip(s) {
  const str = s == null ? '' : String(s)
  let a = 0
  let b = str.length
  while (a < b && isRichSpace(str[a])) a++
  while (b > a && isRichSpace(str[b - 1])) b--
  return str.slice(a, b)
}

/** {@link isRichSpace} kümesine göre boş mu (sunucudaki TechErrorRichText.blank)? */
export function richBlank(s) {
  return richStrip(s) === ''
}

/** Unicode noktalama ya da simge (CommonMark "punctuation" = P* + S*; sunucudaki TechErrorRichText.punct). */
const PUNCT_RE = /[\p{P}\p{S}]/u
function isRichPunct(ch) {
  return PUNCT_RE.test(ch)
}

/**
 * Sekme, sunucuda kaydedilen metindeki gibi (TechErrorTemplate.multiline / TechErrorRichText.tabs): satır başındaki her
 * sekme iki boşluk (bir alt düzey), satır içindeki tek boşluk — "1.\tMadde" önizlemede de madde.
 */
function richTabs(line) {
  if (!line.includes('\t')) return line
  let out = ''
  let lead = true
  for (const ch of line) {
    if (ch === '\t') out += lead ? '  ' : ' '
    else {
      out += ch
      if (ch !== ' ') lead = false
    }
  }
  return out
}

/**
 * Satır içi kalın (sunucudaki TechErrorRichText.inline ile AYNI kural). Aday işaret = TAM iki yıldızlık dizi; açabilir /
 * kapatabilir CommonMark'ın bitişik kuralıyla (açılıştan sonra boşluk olmaz; sonraki karakter noktalamaysa öncesi
 * boşluk / noktalama / satır başı olmalı; kapanış aynası). Soldan sağa: açık işaret varken kapatabilen aday kapatır,
 * yoksa açabilen aday yeni açılıştır. Eşlenmeyen her şey düz metin — yıldız SİLİNMEZ ("4543 **** 1234",
 * "ab**@x.com ve cd**@y.com" olduğu gibi kalır; inceleme 2026-10-14). Doğrusal.
 */
export function richInline(s) {
  const out = []
  if (!s) return out
  const n = s.length
  const pairs = []
  let open = -1
  for (let i = 0; i < n;) {
    if (s[i] !== '*') { i++; continue }
    let j = i
    while (j < n && s[j] === '*') j++
    if (j - i === 2) {
      const beforeWs = i === 0 || isRichSpace(s[i - 1])
      const afterWs = j >= n || isRichSpace(s[j])
      const canOpen = !afterWs && (!isRichPunct(s[j]) || beforeWs || isRichPunct(s[i - 1]))
      const canClose = !beforeWs && (!isRichPunct(s[i - 1]) || afterWs || isRichPunct(s[j]))
      if (open >= 0 && canClose) {
        pairs.push([open, i])
        open = -1
      } else if (canOpen) {
        open = i
      }
    }
    i = j
  }
  let pos = 0
  for (const [a, b] of pairs) {
    if (a > pos) out.push({ text: s.slice(pos, a), bold: false })
    out.push({ text: s.slice(a + 2, b), bold: true })
    pos = b + 2
  }
  if (pos < n) out.push({ text: s.slice(pos), bold: false })
  return out
}

/**
 * Açıklama biçimi (2026-10-14, kullanıcı isteği: "madde, alt madde, kalın yazı") — sunucudaki TechErrorRichText ile
 * AYNI kural: `- ` / `* ` / `• ` madde, başındaki her 2 boşluk bir alt düzey (en çok 3 düzey), `1. ` / `1) ` numaralı
 * madde, `**kalın**`, boş satır paragraf aralığı. Blok: `{ kind: 'p'|'li'|'gap', level, marker, runs }`.
 */
export function parseRichText(text) {
  const out = []
  if (!text) return out
  const lines = String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  let pendingGap = false
  for (const line of lines) {
    if (out.length >= RICH_MAX_BLOCKS) break
    const raw = richTabs(line)
    if (richBlank(raw)) {
      if (out.length) pendingGap = true
      continue
    }
    if (pendingGap) {
      out.push({ kind: 'gap', level: 0, marker: null, runs: [] })
      pendingGap = false
    }
    let i = 0
    let spaces = 0
    while (i < raw.length && raw[i] === ' ') {
      spaces++
      i++
    }
    const level = Math.min(RICH_MAX_LEVEL, Math.floor(spaces / 2))
    const rest = raw.slice(i)
    if (rest.length >= 2 && (rest.startsWith('- ') || rest.startsWith('• ') || (rest[0] === '*' && rest[1] === ' '))) {
      out.push({ kind: 'li', level, marker: null, runs: richInline(richStrip(rest.slice(2))) })
      continue
    }
    let d = 0
    while (d < rest.length && d < 3 && rest[d] >= '0' && rest[d] <= '9') d++
    if (d > 0 && d + 1 < rest.length && (rest[d] === '.' || rest[d] === ')') && rest[d + 1] === ' ') {
      out.push({ kind: 'li', level, marker: `${rest.slice(0, d)}.`, runs: richInline(richStrip(rest.slice(d + 2))) })
      continue
    }
    out.push({ kind: 'p', level, marker: null, runs: richInline(richStrip(rest)) })
  }
  return out
}

/** İşaretsiz düz metin (karşılaştırma, ekran okuyucu özeti): kalın işaretleri düşer, maddeler "- " olur. */
export function richPlain(text) {
  return parseRichText(text).map((b) => {
    if (b.kind === 'gap') return ''
    const marker = b.kind === 'li' ? `${b.marker || '-'} ` : ''
    return `${'  '.repeat(b.level)}${marker}${b.runs.map((r) => r.text).join('')}`
  }).join('\n').trim()
}

// ── Yönetici özeti (TechErrorSummary aynası) ───────────────────────────────────────────────────────

const MAX_REFS = 100

/** `{f:id}` ya da `{f:id?evet|hayır}` başvuruları (doğrusal tarama; kapanmayan başvuru düz metin). */
/**
 * Başvuru türleri (2026-10-17, 4b; TechErrorSummary.KINDS): `f` değer (`{f:id}` / `{f:id?evet|hayır}`), `d` önceki aya göre
 * fark (`{d:id}`), `t` alanın etkin hedefi (`{t:id}`), `dt` değerin hedefe uzaklığı (`{dt:id}`). Yalnız `f` seçim alır.
 */
export const REF_KINDS = ['f', 'd', 't', 'dt']
const refKindAt = (str, i) => REF_KINDS.find((k) => str.startsWith(`{${k}:`, i)) || null

/** Cümledeki başvurular `{ kind, id, yes, no, start, end }` (sunucudaki TechErrorSummary.refs ile aynı tarama). */
export function summaryRefs(line) {
  const out = []
  if (line == null) return out
  const str = String(line)
  let i = str.indexOf('{')
  while (i >= 0 && out.length < MAX_REFS) {
    const kind = refKindAt(str, i)
    if (!kind) { i = str.indexOf('{', i + 1); continue }
    const bodyStart = i + kind.length + 2
    const close = str.indexOf('}', bodyStart)
    if (close < 0) break
    const body = str.slice(bodyStart, close)
    // İç içe süslü parantez okunamaz (ilk "}"de biterdi, bozuk basılırdı): başvuru sayılmaz → summaryMalformed (sunucu)
    if (body.includes('{')) {
      i = str.indexOf('{', close + 1)
      continue
    }
    const q = kind === 'f' ? body.indexOf('?') : -1
    const id = richStrip(q < 0 ? body : body.slice(0, q))
    let yes = null
    let no = null
    if (q >= 0) {
      const choice = body.slice(q + 1)
      const bar = choice.indexOf('|')
      // Kesilmez (sunucu ile aynı): gövde en çok 500 karakterlik cümlenin bir parçası
      yes = bar < 0 ? choice : choice.slice(0, bar)
      no = bar < 0 ? '' : choice.slice(bar + 1)
    }
    if (ID_RE.test(id)) out.push({ kind, id, yes, no, start: i, end: close + 1 })
    i = str.indexOf('{', close + 1)
  }
  return out
}

/** Kalıpta çözülemeyen bir başvuru öneki var mı (kapanmamış / iç içe / geçersiz kimlik)? Sunucu kaydı reddeder. */
export function summaryMalformed(line) {
  if (line == null) return false
  const str = String(line)
  let count = 0
  for (let i = str.indexOf('{'); i >= 0; i = str.indexOf('{', i + 1)) if (refKindAt(str, i)) count++
  return count !== summaryRefs(str).length
}

/** Alanın etkin hedefi: raporun o aya özel hedefi, yoksa şablondaki; yüzde değilse ya da hedef yoksa null (TechErrorTemplate.effectiveTarget). */
export function effectiveTarget(field, targets) {
  // Rapor hedefi yalnız ŞABLONDA hedefi olan alanda geçer (sunucunun saklama kuralı; inceleme 2026-10-17: yedekten dönen
  // bayat bir rapor hedefi ekranda PDF'te olmayan bir hedef gösteriyordu)
  if (!field || field.type !== 'percent') return null
  const tpl = calcNumber(field.target)
  if (tpl == null) return null
  const o = calcNumber(targets?.[field.id])
  return o != null ? o : tpl
}

/** Etkin "daha iyi" yönü: açıkça yazılmışsa o; yoksa yüzde → lower (hata oranı), sayı → none (TechErrorTemplate.effectiveBetter). */
export function effectiveBetter(field) {
  if (!field) return 'none'
  if (field.better) return field.better
  return field.type === 'percent' ? 'lower' : 'none'
}

/** Puan farkı (TechErrorFormat.points): "+0,04 puan" / "−0,02 puan" / "0 puan" (EN "pts"); 2 hane, küçük fark 2 anlamlı hane. */
export function pointsOf(diff, lang) {
  const r = roundCalc(Math.abs(diff))
  const unit = lang === 'en' ? ' pts' : ' puan'
  if (r === 0) return { text: `0${unit}`, dir: 'flat' }
  return { text: `${diff > 0 ? '+' : '−'}${formatNumber(r, lang, 6)}${unit}`, dir: diff > 0 ? 'up' : 'down' }
}

/**
 * Önceki aya göre fark (TechErrorFormat.delta; kullanıcı kararı 2026-10-16: adetlerde fark adet + yüzde, oranlarda puan):
 * sayı → "+1.250 (+%3,2)" (önceki 0 ise yalnız adet; değişim yoksa "değişim yok"), yüzde → puan; `dir` up / down / flat;
 * biri yoksa null (cümle düşer, kutucukta fark satırı yok).
 */
export function deltaOf(field, prev, cur, lang, unit = null) {
  const p = calcNumber(prev)
  const c = calcNumber(cur)
  if (p == null || c == null) return null
  if (field.type === 'percent') return pointsOf(c - p, lang)
  const diff = c - p
  if (diff === 0) return { text: lang === 'en' ? 'no change' : 'değişim yok', dir: 'flat' }
  const sign = diff > 0 ? '+' : '−'
  // Bu ayın değeri kısaltmayla girildiyse fark da kısa (2026-10-18; TechErrorFormat.delta(…, unit))
  let text = sign + (ABBREV_LETTER[unit] ? compactQlik(Math.abs(diff), lang) : formatNumber(Math.abs(diff), lang))
  if (p !== 0) text += ` (${sign}${formatPercent(roundCalc(Math.abs(diff) / Math.abs(p) * 100), lang)})`
  return { text, dir: diff > 0 ? 'up' : 'down' }
}

function summaryYesNo(lang) {
  return (b) => (lang === 'en' ? (b ? 'Yes' : 'No') : (b ? 'Evet' : 'Hayır'))
}

/**
 * Kısa metin değerinin sunucuda saklanan hâli (TechErrorTemplate.line): denetim karakterleri boşluk, çift boşluk teke
 * iner, iki uçtan U+0020 ve altı kırpılır. Taslaktaki "   " sunucuda yok sayılır — önizleme de cümleyi düşürmeli.
 */
export function shortTextValue(v) {
  if (v == null) return ''
  let out = ''
  for (const ch of String(v)) {
    const c = ch.codePointAt(0)
    out += c < 0x20 || (c >= 0x7f && c <= 0x9f) ? ' ' : ch
  }
  out = out.replace(/ {2,}/g, ' ')
  let a = 0
  let b = out.length
  while (a < b && out.charCodeAt(a) <= 0x20) a++
  while (b > a && out.charCodeAt(b - 1) <= 0x20) b--
  return out.slice(a, b)
}

/** Değer cümleye girerken işaretlemeyi bozmasın: 2+ yıldız dizisi TEK yıldız, satır sonu boşluk (sunucudaki neutral). */
function neutralValue(s) {
  return String(s).replace(/\n/g, ' ').replace(/\*{2,}/g, '*')
}

/** Özet cümlesinde kullanılabilir değer mi? Sunucu ile aynı: boş / null yok; kısa metin saklandığı hâliyle. */
function summaryValue(f, v) {
  if (v == null) return null
  if (f.type === 'text') {
    const t = shortTextValue(v)
    return t === '' ? null : t
  }
  if (typeof v === 'string' && v.trim() === '') return null
  return v
}

/**
 * Tek cümle: dayandığı değer boş / muaf / şablonda yoksa `null` (cümle düşer). Sayı ve yüzde KALIN yazılır (`**…**`);
 * metin değerindeki yıldız dizisi tek yıldıza iner (işaretleme bozulmasın). Uzun açıklama (`textarea`) alanına
 * başvuran cümle düşer (sunucudaki TechErrorSummary.line ile aynı; inceleme 2026-10-14).
 */
export function summaryLine(pattern, byId, fields, lang, resolveLiteral, previous = null, targets = null, units = null) {
  if (pattern == null || richBlank(pattern)) return null
  const str = String(pattern)
  let out = ''
  let pos = 0
  for (const r of summaryRefs(str)) {
    out += resolveLiteral(str.slice(pos, r.start))
    const f = byId.get(r.id)
    if (!f || f.type === 'textarea' || fieldExempt(f, fields)) return null
    if (r.kind !== 'f') {
      // 2026-10-17 (4b): fark / hedef / hedefe uzaklık — geçen ay ya da hedef yoksa cümle düşer (sunucudaki TechErrorSummary.line)
      const numeric = f.type === 'number' || f.type === 'percent'
      if (r.kind === 'd') {
        if (!numeric) return null
        const d = deltaOf(f, previous?.[f.id], fields?.[f.id], lang, units?.[f.id] ?? null)
        if (!d) return null
        const plain = d.dir === 'flat' && f.type !== 'percent'
        out += plain ? d.text : `**${d.text}**`
      } else {
        const target = effectiveTarget(f, targets)
        if (target == null) return null
        if (r.kind === 't') out += `**${formatPercent(target, lang)}**`
        else {
          const cur = calcNumber(fields?.[f.id])
          if (cur == null) return null
          out += `**${pointsOf(cur - target, lang).text}**`
        }
      }
      pos = r.end
      continue
    }
    const v = summaryValue(f, fields?.[f.id])
    if (v == null) return null
    if (r.yes != null) {
      if (typeof v !== 'boolean') return null
      out += resolveLiteral(v ? r.yes : r.no)
    } else if (f.type === 'number' || f.type === 'percent') {
      out += `**${formatValue(f, v, lang, summaryYesNo(lang), units?.[f.id] ?? null)}**`
    } else {
      out += neutralValue(formatValue(f, v, lang, summaryYesNo(lang)))
    }
    pos = r.end
  }
  out += resolveLiteral(str.slice(pos))
  return richStrip(out.replace(/ {2,}/g, ' '))
}

/** Satır başında madde gibi okunacak düz cümle: işaretten sonraki boşluk U+00A0 (sunucudaki keepParagraph). */
function keepParagraph(r) {
  if (!isItemLine(r)) return r
  let k = 0
  while (k < r.length && k < 3 && r[k] >= '0' && r[k] <= '9') k++
  k = k > 0 ? k + 1 : 1
  return k < r.length && r[k] === ' ' ? `${r.slice(0, k)}\u00A0${r.slice(k + 1)}` : r
}

/** Cümle kalıbı madde mi? Karar KALIBINDIR (değer ya da kanal adı "1. …" ile başlasa da paragraf akışı bozulmaz). */
function isItemLine(pattern) {
  const b = parseRichText(pattern)
  return b.length > 0 && b[0].kind === 'li'
}

/**
 * Kalıplardan üretilen özet (sunucudaki TechErrorSummary.auto): düz cümleler tek paragrafta akar, madde satırı ayrı
 * satır, maddeden sonraki düz cümle yeni paragraf. `fields` = raporun değerleri.
 */
export function summaryAutoText(section, template, fields, channel, year, month, previous = null, targets = null, units = null) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const lang = template?.lang
  const lit = (x) => resolveTextPlaceholders(x, channel, year, month, lang)
  let out = ''
  let inParagraph = false
  for (const line of section?.lines || []) {
    // Sınır aşıldıysa sonraki cümleler üretilmez — sonuç zaten sınırda kesilir (sunucu ile aynı)
    if (out.length > MAX_RESOLVED_TEXT) break
    let r = summaryLine(line, byId, fields, lang, lit, previous, targets, units)
    if (!r || richBlank(r)) continue
    const item = isItemLine(line)
    // Madde kalıbı boş gövdeye çözüldüyse ("- {f:x?Var|}" → "-") cümle düşer (sunucu ile aynı)
    if (item && !isItemLine(r)) continue
    // Paragraf başındaki düz cümle değerle "1. " / "- " ile başlarsa işaretten sonraki boşluk bölünmez boşluk olur
    if (!item && !inParagraph) r = keepParagraph(r)
    if (item) {
      if (out) out += '\n'
      out += r
      inParagraph = false
    } else if (inParagraph) {
      out += ` ${r}`
    } else {
      if (out) out += '\n\n'
      out += r
      inParagraph = true
    }
  }
  return capResolved(out, MAX_RESOLVED_TEXT)
}

/**
 * Raporda görünen özet: elle düzenlenmişse o metin (yer tutucular çözülür), değilse kalıplardan üretilen. BOŞ elle
 * metin "otomatik" sayılır (inceleme 2026-10-14: boş bir özetle "Metni elle düzenle"ye basmak özeti kalıcı olarak
 * boş bırakıyordu; sonradan girilen rakamlar onu hiç doldurmuyordu). Sunucu aynı kuralı uygular.
 */
export function summaryEffective(section, template, values, channel, year, month, previous = null) {
  const override = values?.texts?.[section?.id]
  if (override != null && !richBlank(override)) return resolveTextPlaceholders(override, channel, year, month, template?.lang)
  return summaryAutoText(section, template, values?.fields || {}, channel, year, month, previous, values?.targets || null,
    values?.units || null)
}

/**
 * Not alanının özetteki değeri (2026-10-17): uzun açıklama, dolu ve muaf değil → saklanan değer olduğu gibi; değilse null.
 * Sunucudaki TechErrorSummary.notes ile aynı kural (kısa metin alanı not değil, söylenmemiş DEĞER satırıdır).
 */
function noteValue(f, fields) {
  if (f.type !== 'textarea') return null
  if (fieldExempt(f, fields)) return null
  const raw = fields?.[f.id]
  if (typeof raw !== 'string' || richBlank(raw)) return null
  return raw
}

/**
 * Gömülü bölümün SÖYLENMEMİŞ değerleri (sunucudaki TechErrorSummary.facts): sayı / yüzde / evet-hayır / kısa metin alanı,
 * dolu, muaf değil ve tutulan hiçbir cümle ona başvurmuyor → her biri kendi satırında "Ad: **değer**" (sayı / yüzde kalın;
 * evet / hayır ve kısa metin düz — kısa metnin yıldız dizisi teke iner; soru işaretiyle biten ad iki nokta almaz), tek
 * metin; yoksa null. Cümlesi (eksik başka bir rakam yüzünden) düşen bir adet önizlemeden ve PDF'ten sessizce kaybolmaz
 * (inceleme 2026-10-17).
 */
function summaryFacts(e, template, fields, told, channel, year, month, units = null) {
  const lang = template?.lang
  const parts = []
  for (const f of e.fields || []) {
    const yesno = f.type === 'yesno'
    const text = f.type === 'text'
    if (!yesno && !text && f.type !== 'number' && f.type !== 'percent') continue
    if (told.has(f.id) || fieldExempt(f, fields)) continue
    const v = fields?.[f.id]
    if (v == null || (yesno && typeof v !== 'boolean')) continue
    let value
    if (text) {
      const s = typeof v === 'string' ? shortTextValue(v) : ''
      if (richBlank(s)) continue
      value = neutralValue(s)
    } else {
      value = formatValue(f, v, lang, summaryYesNo(lang), units?.[f.id] ?? null)
    }
    const label = String(resolvePlaceholders(f.label || '', channel, year, month, lang) ?? '')
    const bold = !yesno && !text
    const shown = bold ? `**${value}**` : value
    const fact = label.endsWith('?') ? `${label} ${shown}` : `${label}: ${shown}`
    parts.push(keepParagraph(fact))
  }
  return parts.length ? parts.join('\n') : null
}

/** Gömülü bölümün yerleşince ekleyeceği bloklar: önce söylenmemiş değerleri (tek metin bloğu), sonra notları. */
function embeddedBlocks(e, template, fields, told, channel, year, month, units = null) {
  const lang = template?.lang
  const out = []
  const facts = summaryFacts(e, template, fields, told, channel, year, month, units)
  if (facts != null) out.push({ kind: 'text', text: facts })
  for (const f of e.fields || []) {
    const v = noteValue(f, fields)
    if (v != null) out.push({ kind: 'note', section: e.id, field: f.id, label: resolvePlaceholders(f.label, channel, year, month, lang), text: v })
  }
  return out
}

/**
 * Yönetici özetinin BLOKLARI (2026-10-17, kullanıcı kararı: "map yapılmamış / kota hataları yönetici özeti kartında
 * girilsin; PDF ve e-postada açıklama ilgili özet cümlesinin hemen altına yazılsın") — sunucudaki
 * TechErrorSummary.blocks ile AYNI algoritma (doğruluk tablosu TechErrorSummaryBlocksTest.BLOCKS_TABLE, JS testi Java
 * kaynağından okur). Çıktı sıralı bloklar: `{ kind: 'text', text }` | `{ kind: 'note', section, field, label, text }`
 * (`label` = notun başlığı, alanın çözülmüş adı).
 *
 * <ul>
 *   <li>Not = özet kartına gömülü bir gösterge bölümünün dolu, muaf olmayan uzun açıklama alanı.</li>
 *   <li>Söylenmemiş değer ({@link summaryFacts}) = gömülü bölümün dolu sayı / yüzde / evet-hayır / kısa metin alanı, TUTULAN
 *     hiçbir cümle ona başvurmuyorsa — "Ad: **değer**" satırı (tek metin bloğu, notlardan önce).</li>
 *   <li>ELLE metin: tek metin bloğu + gömülü bölümlerin bütün notları sonda (şablon sırasıyla); söylenmemiş değer eklenmez.</li>
 *   <li>OTOMATİK: cümleler {@link summaryAutoText} ile aynı kuralla dizilir; gömülü bölümün HERHANGİ bir alanına başvuran
 *     cümle özete girince bölüm oraya yerleşir: eklenecek değer ya da not VARSA o ana kadarki metin blok olur, bölümün
 *     blokları ardına gelir ve sonraki cümle yeni paragraf başlatır; eklenecek bir şey yoksa paragraf bölünmez (notsuz
 *     varsayılan rapor tek paragraf kalır). Hiçbir cümlesi girmeyen bölüm sonda.</li>
 *   <li>Metin sınırı yalnız METNE: sınırdan sonraki cümle üretilmez ve alanları söylenmiş sayılmaz (değerleri satır olur).</li>
 *   <li>"Söylenen" alan (bütünleştirme incelemesi 2026-10-18, sunucudaki shownValues ile AYNI kural): basılan metinde TAMAMI
 *     kalan tutulan cümlenin `{f:}` başvurusu; seçimli başvuru yalnız SEÇİLEN dalı boş değilse. Cümlesi kesilen gömülü
 *     değer satır olur; değer satırı ya tamamıyla basılır ya özet metninden sonra (sınırın dışında) tek blokta — yarım
 *     "…" yok (doğruluk tablosu TechErrorSummaryBlocksTest.CHOICE_TABLE).</li>
 * </ul>
 * Gömülü bölüm yoksa tek metin bloğu = {@link summaryAutoText} (davranış değişmez).
 */
export function summaryBlocks(template, section, fields, texts, channel, year, month, previous = null, targets = null, units = null) {
  if (!section) return []
  const lang = template?.lang
  const embedded = embeddedSections(template, section.id)
  const override = texts?.[section.id]
  if (override != null && !richBlank(override)) {
    const out = [{ kind: 'text', text: resolveTextPlaceholders(override, channel, year, month, lang) }]
    for (const e of embedded) {
      for (const f of e.fields || []) {
        const v = noteValue(f, fields)
        if (v != null) out.push({ kind: 'note', section: e.id, field: f.id, label: resolvePlaceholders(f.label, channel, year, month, lang), text: v })
      }
    }
    return out
  }
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const lit = (x) => resolveTextPlaceholders(x, channel, year, month, lang)
  const lines = section.lines || []
  // Değer yazan başvuru: {f:}; seçimli başvuruda SEÇİLEN dal boş değilse (sunucudaki writesValue)
  const writes = (ref) => ref.kind === 'f' && (ref.yes == null || !richBlank(lit(fields?.[ref.id] === true ? ref.yes : ref.no)))
  // 1. geçiş: tutulan cümleler (null = düşen) ve tutulan cümlelerin başvurduğu alanlar; metin sınırı summaryAutoText ile
  // aynı ölçüyle burada da işler (sınırdan sonraki cümle üretilmez, alanları söylenmiş sayılmaz)
  const kept = []
  const told0 = new Set()
  let len = 0
  let par = false
  for (const line of lines) {
    if (len > MAX_RESOLVED_TEXT) { kept.push(null); continue }
    const r = summaryLine(line, byId, fields, lang, lit, previous, targets, units)
    const ok = r != null && !richBlank(r) && (!isItemLine(line) || isItemLine(r))
    kept.push(ok ? r : null)
    if (!ok) continue
    // Yalnız DEĞERİ yazan başvuru alanı "söyler"; fark / hedef / uzaklık ({d:} {t:} {dt:}) değeri yazmaz (inceleme 2026-10-17);
    // seçimli başvuru yalnız SEÇİLEN dalı boş değilse (bütünleştirme incelemesi 2026-10-18)
    for (const ref of summaryRefs(line)) if (writes(ref)) told0.add(ref.id)
    const item = isItemLine(line)
    len += (len > 0 ? (item ? 1 : par ? 1 : 2) : 0) + r.length
    par = !item
  }
  // 2. geçiş: paragraf akışı summaryAutoText ile aynı; gömülü bölüm ilk başvurulduğu cümlenin ardına yerleşir. Sonuç:
  // bloklar, değer satırı bloklarının konumları ve her tutulan cümlenin birleşik metindeki BİTİŞİ (sunucudaki place)
  const place = (told) => {
    const out = []
    const facts = new Set()
    const ends = kept.map(() => -1)
    let pending = []                     // tampondaki cümleler: [satır, tampon içindeki bitiş]
    let buf = ''
    let flushed = 0                      // blok olmuş metnin birleşik uzunluğu ("\n\n" ayraçlar dahil)
    const joined = () => flushed + (flushed > 0 && buf ? 2 : 0) + buf.length
    const resolveEnds = () => {
      if (buf) {
        const start = flushed > 0 ? flushed + 2 : 0
        for (const [k, e] of pending) ends[k] = start + e
      }
      pending = []
    }
    const flush = () => {
      if (!buf) return
      flushed = joined()
      out.push({ kind: 'text', text: buf })
      buf = ''
    }
    const append = (add) => {
      add.forEach((b, k) => { if (b.kind === 'text') facts.add(out.length + k) })
      for (const b of add) {
        if (b.kind === 'text') flushed += (flushed > 0 ? 2 : 0) + b.text.length
        out.push(b)
      }
    }
    const placed = new Set()
    let inParagraph = false
    for (let i = 0; i < kept.length; i++) {
      if (joined() > MAX_RESOLVED_TEXT) break
      let r = kept[i]
      if (r == null) continue
      const line = lines[i]
      const item = isItemLine(line)
      if (!item && !inParagraph) r = keepParagraph(r)
      if (item) {
        if (buf) buf += '\n'
        buf += r
        inParagraph = false
      } else if (inParagraph) {
        buf += ` ${r}`
      } else {
        if (buf) buf += '\n\n'
        buf += r
        inParagraph = true
      }
      pending.push([i, buf.length])
      if (!embedded.length) continue
      const ids = new Set(summaryRefs(line).map((x) => x.id))
      for (const e of embedded) {
        if (placed.has(e.id) || !(e.fields || []).some((f) => ids.has(f.id))) continue
        placed.add(e.id)
        const add = embeddedBlocks(e, template, fields, told, channel, year, month, units)
        if (!add.length) continue                 // eklenecek bir şey yok: paragraf bölünmez
        resolveEnds()
        flush()
        append(add)
        inParagraph = false
      }
    }
    resolveEnds()
    flush()
    for (const e of embedded) if (!placed.has(e.id)) append(embeddedBlocks(e, template, fields, told, channel, year, month, units))
    return { out, facts, ends }
  }
  // Basılan metinde TAMAMI kalan cümlelerin değer yazan başvuruları (sunucudaki wholeRefs / shownValues)
  const wholeRefs = (lay) => {
    const cut = cutEndOf(lay.out.filter((b) => b.kind === 'text').map((b) => b.text).join('\n\n'), MAX_RESOLVED_TEXT)
    const set = new Set()
    lay.ends.forEach((e, i) => {
      if (kept[i] == null || e < 0 || e > cut) return
      for (const ref of summaryRefs(lines[i])) if (writes(ref)) set.add(ref.id)
    })
    return set
  }
  const told = new Set(told0)
  let lay = place(told)
  if (embedded.length) {
    // Değer satırları da bütçede: onların ittiği cümle kesilebilir — küme sabitlenene kadar yeniden yerleşir (yalnız küçülür)
    for (let round = 0, max = told.size + 1; round < max; round++) {
      const whole = wholeRefs(lay)
      if ([...told].every((x) => whole.has(x))) break
      for (const x of [...told]) if (!whole.has(x)) told.delete(x)
      lay = place(told)
    }
  }
  return capFactBlocks(lay.out, lay.facts, MAX_RESOLVED_TEXT)
}

/** Birleşik metnin basılan önekinin uzunluğu (sunucudaki cutEnd): sığıyorsa tamamı, sığmıyorsa `max − 1` (vekil çifti düzeltmeli). */
function cutEndOf(joined, max) {
  if (joined.length <= max) return joined.length
  let end = max - 1
  const code = joined.charCodeAt(end - 1)
  if (code >= 0xd800 && code <= 0xdbff) end--
  return end
}

/**
 * {@link capTextBlocks} + gömülü değer satırı blokları (`facts`: konumlar; sunucudaki capText(blocks, facts)): değer satırı
 * ya TAMAMI basılır ya özet metninden sonra (sınırın dışında) tek blokta; "…" son tutulan cümle bloğuna eklenir.
 */
function capFactBlocks(blocks, facts, max) {
  const joined = blocks.filter((b) => b.kind === 'text').map((b) => b.text).join('\n\n')
  if (joined.length <= max) return blocks
  const end = cutEndOf(joined, max)
  const out = []
  const rescued = []
  let start = 0
  let last = -1
  let lastStart = 0
  blocks.forEach((b, i) => {
    if (b.kind !== 'text') { out.push(b); return }
    if (facts.has(i)) {
      if (start + b.text.length <= end) out.push(b)
      else rescued.push(b.text)
    } else if (start < end) {
      out.push(b)
      last = out.length - 1
      lastStart = start
    }
    start += b.text.length + 2
  })
  if (last >= 0) {
    const txt = out[last].text
    out[last] = { kind: 'text', text: `${txt.slice(0, Math.min(txt.length, end - lastStart))}…` }
  }
  if (rescued.length) out.push({ kind: 'text', text: rescued.join('\n') })
  return out
}

/**
 * Metin sınırı, {@link summaryAutoText} ile aynı ölçüyle (sunucudaki TechErrorSummary.capText): metin blokları "\n\n" ile
 * birleşik sayılır; birleşik metin sınırı aşarsa kesim noktası capResolved ile aynı (`max − 1`, vekil çiftinin ilk
 * yarısında bir eksik). Kesim noktasında ya da sonrasında başlayan metin blokları düşer; kalan son metin bloğu kesim
 * noktasına kadar kalır + "…". Notlar sınıra girmez, hiç düşmez.
 */
export function capTextBlocks(blocks, max) {
  const joined = blocks.filter((b) => b.kind === 'text').map((b) => b.text).join('\n\n')
  if (joined.length <= max) return blocks
  let end = max - 1
  const code = joined.charCodeAt(end - 1)
  if (code >= 0xd800 && code <= 0xdbff) end--
  const out = []
  let start = 0
  let last = -1
  let lastStart = 0
  for (const b of blocks) {
    if (b.kind !== 'text') { out.push(b); continue }
    if (start < end) {
      out.push(b)
      last = out.length - 1
      lastStart = start
    }
    start += b.text.length + 2
  }
  if (last >= 0) {
    const txt = out[last].text
    out[last] = { kind: 'text', text: `${txt.slice(0, Math.min(txt.length, end - lastStart))}…` }
  }
  return out
}

/**
 * Özet cümlelerinden hangileri ŞU AN özete girmiyor ve neden: `missing` = dayandığı rakam girilmemiş (alan adları, her
 * alan bir kez), `exempt` = "Hayır" cevabıyla muaf rakama dayanıyor, `broken` = kullanılamayan alana bağlı; `reason`:
 * `removed` (şablonda yok), `notYesNo` (seçim evet/hayır olmayan alanda), `textarea` (uzun açıklama alanı).
 */
export function summaryLineStatus(section, template, fields, previous = null, targets = null) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  return (section?.lines || []).map((line, index) => {
    const missing = []
    const seen = new Set()
    let exempt = false
    let reason = null
    let needsPrevious = false
    for (const r of summaryRefs(line)) {
      const f = byId.get(r.id)
      if (!f) { reason = reason || 'removed'; continue }
      if (r.yes != null && f.type !== 'yesno') { reason = reason || 'notYesNo'; continue }
      if (f.type === 'textarea') { reason = reason || 'textarea'; continue }
      // 2026-10-17 (4b): fark yalnız sayı / yüzde alanında; hedef ve hedefe uzaklık yalnız hedefi olan alanda
      if (r.kind === 'd' && f.type !== 'number' && f.type !== 'percent') { reason = reason || 'notNumeric'; continue }
      if ((r.kind === 't' || r.kind === 'dt') && effectiveTarget(f, targets) == null) { reason = reason || 'noTarget'; continue }
      if (fieldExempt(f, fields)) { exempt = true; continue }
      if (r.kind === 't') continue                      // hedef şablondan gelir: bu ayın değeri gerekmez
      if (summaryValue(f, fields?.[f.id]) == null && !seen.has(f.id)) {
        seen.add(f.id)
        missing.push(f)
      }
      // Fark geçen ayın raporunu bekler: rapor yoksa cümle düşer (kullanıcı kararı) — listede "geçen ayın raporu yok" denir
      if (r.kind === 'd' && calcNumber(previous?.[f.id]) == null) needsPrevious = true
    }
    const state = reason ? 'broken' : exempt ? 'exempt' : (missing.length || needsPrevious) ? 'missing' : 'ok'
    return { index, line, state, missing, reason, needsPrevious }
  })
}

/**
 * Şablon düzenleyicisinin cümle uyarıları: `malformed` (kaydı engeller), `missing` (alan şablonda yok — cümle raporda
 * hiç görünmez), `notYesNo` (seçim yalnız evet/hayır alanında çalışır), `textarea` (uzun açıklama alanı özete girmez).
 * Uyarılar kaydı engellemez (sunucu da kabul eder). Aynı başvurunun uyarısı bir kez.
 */
export function summaryLineIssues(line, template) {
  if (summaryMalformed(line)) return [{ kind: 'malformed' }]
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const out = []
  const seen = new Set()
  const push = (x) => {
    const k = `${x.kind}:${x.id}`
    if (!seen.has(k)) { seen.add(k); out.push(x) }
  }
  for (const r of summaryRefs(line)) {
    const f = byId.get(r.id)
    if (!f) push({ kind: 'missing', id: r.id })
    else if (r.yes != null && f.type !== 'yesno') push({ kind: 'notYesNo', id: r.id, label: f.label })
    else if (f.type === 'textarea') push({ kind: 'textarea', id: r.id, label: f.label })
    // 2026-10-17 (4b): fark yalnız sayı / yüzde; hedef ve hedefe uzaklık yalnız şablonda hedefi olan yüzde alanında
    else if (r.kind === 'd' && f.type !== 'number' && f.type !== 'percent') push({ kind: 'notNumeric', id: r.id, label: f.label })
    else if ((r.kind === 't' || r.kind === 'dt') && effectiveTarget(f, null) == null) push({ kind: 'noTarget', id: r.id, label: f.label })
  }
  return out
}

/**
 * Cümle kalıbının okunur hâli: `{f:id}` → `[Alan adı]`, `{f:id?a|b}` → `[a / b]` (düzenleyici önizlemesi). Şablonda
 * olmayan alan `removedLabel` ile gösterilir (kimlik kullanıcıya gösterilmez; verilmezse kimlik). Alan adları `resolve`
 * ile, satırın tamamı `resolveLine` ile çözülür — başlık sınırlı (300) çözücü 500 karakterlik cümleyi "…" ile kesiyordu.
 */
export function summaryLinePreview(line, template, resolve = (x) => x, removedLabel = null, resolveLine = resolve,
  decorate = (kind, label) => `${label} · ${kind}`) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const str = String(line || '')
  let out = ''
  let pos = 0
  for (const r of summaryRefs(str)) {
    out += str.slice(pos, r.start)
    const f = byId.get(r.id)
    const base = f ? resolve(f.label) : (removedLabel ?? r.id)
    if (r.yes != null) out += `[${r.yes} / ${r.no}]`
    else out += `[${r.kind === 'f' ? base : decorate(r.kind, base)}]`   // fark / hedef / hedefe uzaklık türü de okunur
    pos = r.end
  }
  return resolveLine(out + str.slice(pos))
}

/** Bir alanı kullanan özet cümleleri (alan silinirken uyarmak için). */
export function summaryLinesUsing(template, fieldId) {
  const out = []
  for (const s of template?.sections || []) {
    if (s.kind !== 'summary') continue
    for (const line of s.lines || []) if (summaryRefs(line).some((r) => r.id === fieldId)) out.push(line)
  }
  return out
}

/** Metne, imlecin olduğu yere bir parça ekler (başında / sonunda gerekirse boşluk). `[yeni metin, yeni imleç]`. */
export function insertToken(value, token, at = null) {
  const v = String(value || '')
  const pos = at == null || at < 0 || at > v.length ? v.length : at
  const before = v.slice(0, pos)
  const after = v.slice(pos)
  const lead = before && !/\s$/.test(before) ? ' ' : ''
  const trail = after && !/^\s/.test(after) ? ' ' : ''
  const next = `${before}${lead}${token}${trail}${after}`
  return [next, pos + lead.length + token.length]
}

// ── Grafik ızgarası ─────────────────────────────────────────────────────────────────────────────────

/**
 * Yuvaları satırlara dizer (PDF ile aynı kural): ardışık iki "yarım" yan yana, tek kalan yarım tek başına, "tam" tek
 * satır. `present` verilirse yalnız görseli olan yuvalar sayılır (PDF); verilmezse hepsi (düzenleyici).
 */
export function imageRows(slots, present = null) {
  const list = (slots || []).filter((s) => present == null || present.has(s.id))
  const rows = []
  for (let i = 0; i < list.length;) {
    const a = list[i]
    const b = list[i + 1]
    if (a.width === 'half' && b && b.width === 'half') {
      rows.push([a, b])
      i += 2
    } else {
      rows.push([a])
      i += 1
    }
  }
  return rows
}

// ── Şablon düzenleme ────────────────────────────────────────────────────────────────────────────────

export function allFields(template) {
  return (template?.sections || []).filter((s) => s.kind === 'metrics').flatMap((s) => s.fields || [])
}

// ── Özet kartına gömülü bölümler (2026-10-17, kullanıcı kararı) ──────────────────────────────────────────────────────
//
// Gösterge bölümünün `host` niteliği aynı şablondaki bir `summary` bölümünün kimliğidir: bölümün alanları raporda o
// özetin KARTINDA doldurulur, PDF'te ayrı bölüm olarak basılmaz; açıklama alanları ilgili özet cümlesinin altına yazılır.
// Alanlar yine bir gösterge bölümündedir — değer, hesap, muafiyet, trend, geçmiş hepsi değişmeden çalışır. Sunucudaki
// TechErrorTemplate.hostOf / embeddedSections ile aynı kural.

/** Bölümün gösterildiği özet bölümü (yalnız gösterge bölümünde ve `host` aynı şablonda bir özet bölümüyse); yoksa null. */
export function hostOf(template, section) {
  if (!section || section.kind !== 'metrics' || typeof section.host !== 'string' || !ID_RE.test(section.host)) return null
  const h = (template?.sections || []).find((s) => s.id === section.host)
  return h && h.kind === 'summary' ? h : null
}

/** Bir özet bölümünün kartında gösterilen gösterge bölümleri, şablon sırasıyla. */
export function embeddedSections(template, summaryId) {
  if (!summaryId) return []
  return (template?.sections || []).filter((s) => s.kind === 'metrics' && s.host === summaryId && hostOf(template, s))
}

/** `host` niteliği olmadan kopya ("ayrı bölüm olarak"). Anahtar hiç yazılmaz — sunucu boş kimliği reddeder. */
export function withoutHost(section) {
  return Object.fromEntries(Object.entries(section || {}).filter(([k]) => k !== 'host'))
}

/**
 * Bu değişiklikle KALDIRILAN (ya da artık özet olmayan) bir özet bölümünü gösteren `host` bağlarını kaldırır: o bölümler
 * ayrı bölüm olur (silme onayında söylenir). `prev` verilmezse şablonda özet bölümüne çıkmayan HER bağ kalkar. Önceden
 * kalan geçersiz bağa (`prev`'de de geçersiz) dokunulmaz — düzenleyici "gösterim yerini seçin" der. Değişiklik yoksa
 * AYNI nesne döner.
 */
export function sanitizeHosts(next, prev = null) {
  if (!next?.sections) return next
  const summaries = (tpl) => new Set((tpl?.sections || []).filter((s) => s.kind === 'summary').map((s) => s.id))
  const now = summaries(next)
  const before = prev ? summaries(prev) : null
  let changed = false
  const sections = next.sections.map((s) => {
    if (s.host == null || s.host === '') {
      if ('host' in s) { changed = true; return withoutHost(s) }
      return s
    }
    if (s.kind === 'metrics' && now.has(s.host)) return s
    if (before && !before.has(s.host)) return s
    changed = true
    return withoutHost(s)
  })
  return changed ? { ...next, sections } : next
}

/**
 * Alan bu değerlerle MUAF mı (2026-10-14, kullanıcı isteği: "kesinti yok işareti koyarsa bu alan muaf olsun")?
 * `skip_if_no` = bağlı evet/hayır sorusu; o soru açıkça "Hayır" (false) ise alan sorulmaz, PDF'e girmez. Soru boşsa ya
 * da "Evet" ise alan istenir. Sunucudaki TechErrorTemplate.exempt ile aynı.
 */
export function fieldExempt(field, fields) {
  return !!field?.skip_if_no && fields?.[field.skip_if_no] === false
}

/** Bir evet/hayır sorusuna bağlı alanlar (tüm gösterge bölümlerinden). */
export function dependentsOf(template, questionId) {
  return allFields(template).filter((f) => f.skip_if_no === questionId)
}

/**
 * Şablondaki geçersiz `skip_if_no` bağlarını kaldırır (sunucunun katı kuralıyla aynı: hedef bir evet/hayır alanı,
 * kendisi değil, kendisi başka bir soruya bağlı değil). Değişiklik yoksa AYNI nesne döner. Düzenleyici her
 * değişiklikte çağırır: soru silinince ya da türü değişince bağ sessizce kalkar, kayıt reddedilmez.
 */
export function sanitizeSkipRefs(template) {
  if (!template?.sections) return template
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  let changed = false
  const sections = template.sections.map((s) => {
    if (s.kind !== 'metrics' || !Array.isArray(s.fields)) return s
    let sChanged = false
    const fields = s.fields.map((f) => {
      if (!f.skip_if_no) return f
      const target = byId.get(f.skip_if_no)
      // Otomatik hesaplanan soru hedef olamaz (sunucunun kuralı — muafiyet kayıtlı cevaptan önce kararlaştırılır)
      const ok = target && f.skip_if_no !== f.id && target.type === 'yesno' && !target.skip_if_no && !target.calc?.kind
      if (ok) return f
      sChanged = true
      return withoutSkip(f)
    })
    if (!sChanged) return s
    changed = true
    return { ...s, fields }
  })
  return changed ? { ...template, sections } : template
}

/** Alanın "Hayır cevabında sorma" bağı olmadan kopyası. */
export function withoutSkip(field) {
  return Object.fromEntries(Object.entries(field || {}).filter(([k]) => k !== 'skip_if_no'))
}

export function allSlots(template) {
  return (template?.sections || []).filter((s) => s.kind === 'images').flatMap((s) => s.slots || [])
}

/**
 * Şablonda kullanılmayan, YENİDEN KULLANILMAYAN yeni kimlik: `prefix-<zaman><rastgele>` (inceleme 2026-10-13). Sıralı
 * `f-3` gibi bir kimlik, silinmiş bir alanın kimliğini yeniden verirdi: eski raporların o alana ait değerleri, görselleri
 * ve "geçen ay" ipuçları yeni alana bağlanırdı.
 */
export function newId(prefix, template, now = Date.now(), rand = Math.random) {
  const used = new Set()
  for (const s of template?.sections || []) {
    used.add(s.id)
    for (const f of s.fields || []) used.add(f.id)
    for (const sl of s.slots || []) used.add(sl.id)
    for (const c of s.columns || []) used.add(c.id)
    for (const r of s.rows || []) used.add(r.id)
  }
  const stamp = Math.max(0, Math.floor(now)).toString(36)
  for (let n = 0; n < 50; n++) {
    const tail = Math.floor(rand() * 36 ** 4).toString(36).padStart(4, '0')
    const id = `${prefix}-${stamp}${tail}`.slice(0, 40)
    if (!used.has(id)) return id
  }
  return `${prefix}-${stamp}${used.size.toString(36)}`.slice(0, 40)
}

export function newSection(kind, template, labels) {
  const id = newId('s', template)
  const base = { id, kind, title: labels.title }
  // Otomatik trend: şablondaki ilk dört sayı / yüzde alanıyla başlar (kullanıcı seçimi değiştirir)
  if (kind === 'trend') return { ...base, series: trendableFields(template).slice(0, 4).map((f) => f.id) }
  if (kind === 'metrics') return { ...base, fields: [{ id: newId('f', template), label: labels.field, type: 'number' }] }
  if (kind === 'images') return { ...base, slots: [{ id: newId('g', template), label: labels.slot, width: 'half' }] }
  if (kind === 'issues') {
    // Anomali soruları (2026-10-18) açık başlar — etiketleri ŞABLON dilinde (raporun dili)
    return { ...base, item_title: labels.itemTitle, description_label: labels.descriptionLabel,
      customers_label: labels.customersLabel, errors_label: labels.errorsLabel, ...defaultIssueOptions(template?.lang) }
  }
  if (kind === 'summary') return { ...base, lines: [labels.summaryLine] }
  if (kind === 'table') {
    return { ...base, columns: [{ id: newId('c', template), label: labels.column, type: 'number' }],
      rows: [{ id: newId('r', template), label: labels.row }] }
  }
  return { ...base, text: '' }
}

/** Liste içinde taşı (sınırda değişmez — aynı dizi döner). */
export function move(list, index, delta) {
  const j = index + delta
  if (!Array.isArray(list) || index < 0 || index >= list.length || j < 0 || j >= list.length) return list
  const out = list.slice()
  const [it] = out.splice(index, 1)
  out.splice(j, 0, it)
  return out
}

/**
 * İstemci tarafı şablon denetimi — sunucu yine doğrular; burada amaç kaydetmeden önce alanın altında söylemek.
 * Hata anahtarları: `title`, `sections`, `section:<id>`, `field:<id>`, `slot:<id>` → i18n anahtarı.
 */
export function templateErrors(template) {
  const errs = {}
  if (!template) return { title: 'te.err.templateMissing' }
  const title = (template.title || '').trim()
  if (!title) errs.title = 'te.err.titleRequired'
  else if (title.length > LIMITS.title) errs.title = 'te.err.titleTooLong'
  const secs = template.sections || []
  if (secs.length === 0) errs.sections = 'te.err.noSections'
  if (secs.length > LIMITS.sections) errs.sections = 'te.err.tooManySections'
  if (allSlots(template).length > LIMITS.slots) errs.sections = 'te.err.tooManySlots'
  for (const s of secs) {
    if (!(s.title || '').trim()) errs[`section:${s.id}`] = 'te.err.sectionTitleRequired'
    if (s.kind === 'metrics') {
      if (!(s.fields || []).length) errs[`section:${s.id}`] = 'te.err.noFields'
      if ((s.fields || []).length > LIMITS.fieldsPerSection) errs[`section:${s.id}`] = 'te.err.tooManyFields'
      for (const f of s.fields || []) {
        if (!(f.label || '').trim()) errs[`field:${f.id}`] = 'te.err.fieldLabelRequired'
        const ce = calcShapeError(f)
        if (ce) errs[`calc:${f.id}`] = ce
        // Hedef (2026-10-17, 4b): yalnız yüzde alanı, 0–100, yönü seçilmiş; yön yalnız sayı / yüzde alanında
        if (f.target != null && f.target !== '') {
          const tv = calcNumber(f.target)
          if (f.type !== 'percent') errs[`target:${f.id}`] = 'te.err.targetType'
          else if (tv == null || tv < 0 || tv > 100) errs[`target:${f.id}`] = 'te.err.targetRange'
          // "yön yok" da yönsüzdür (inceleme 2026-10-17: "yüksek" sayılıp rengi ters çeviriyordu)
          else if (!f.better || f.better === 'none') errs[`target:${f.id}`] = 'te.err.targetBetter'
        }
        if (f.better && (!BETTER.includes(f.better) || (f.type !== 'number' && f.type !== 'percent'))) errs[`target:${f.id}`] = 'te.err.betterKind'
      }
      // Gösterildiği özet bölümü şablonda yok (sunucu katı kayıtta reddeder)
      if (s.host != null && s.host !== '' && !hostOf(template, s) && !errs[`section:${s.id}`]) errs[`section:${s.id}`] = 'te.err.hostMissing'
    }
    if (s.kind === 'images') {
      if (!(s.slots || []).length) errs[`section:${s.id}`] = 'te.err.noSlots'
      for (const sl of s.slots || []) if (!(sl.label || '').trim()) errs[`slot:${sl.id}`] = 'te.err.slotLabelRequired'
    }
    if (s.kind === 'summary') {
      const lines = (s.lines || []).filter((l) => String(l || '').trim())
      if (!lines.length) errs[`section:${s.id}`] = 'te.err.noLines'
      if ((s.lines || []).length > LIMITS.summaryLines) errs[`section:${s.id}`] = 'te.err.tooManyLines'
      if ((s.tiles || []).length > LIMITS.tiles) errs[`section:${s.id}`] = 'te.err.tooManyTiles'
      ;(s.lines || []).forEach((l, i) => {
        // Sunucu gibi ölçülür (TechErrorTemplate.line: çift boşluk teke iner) — sunucunun kabul ettiği cümle reddedilmez
        if (shortTextValue(l).length > LIMITS.summaryLine) errs[`line:${s.id}:${i}`] = 'te.err.lineTooLong'
        else if (summaryMalformed(l)) errs[`line:${s.id}:${i}`] = 'te.err.lineMalformed'
      })
    }
    if (s.kind === 'trend') {
      if (!(s.series || []).length) errs[`section:${s.id}`] = 'te.err.noSeries'
      else if ((s.series || []).length > LIMITS.trendSeries) errs[`section:${s.id}`] = 'te.err.tooManySeries'
    }
    if (s.kind === 'table') {
      if (!(s.columns || []).length) errs[`section:${s.id}`] = 'te.err.noColumns'
      else if (!(s.rows || []).length) errs[`section:${s.id}`] = 'te.err.noRows'
      if ((s.columns || []).length > LIMITS.columns) errs[`section:${s.id}`] = 'te.err.tooManyColumns'
      if ((s.rows || []).length > LIMITS.rows) errs[`section:${s.id}`] = 'te.err.tooManyRows'
      // Uzunluk da istemcide denetlenir (sunucu da reddeder; yalnız sunucuda kalınca hata alan altında değil şeritte çıkıyordu)
      // Uzunluk sunucudaki gibi ölçülür (TechErrorTemplate.line: çift boşluk teke iner) — sunucunun kabul ettiği ad reddedilmez
      for (const c of s.columns || []) {
        const l = shortTextValue(c.label)
        if (!l) errs[`col:${s.id}:${c.id}`] = 'te.err.columnLabelRequired'
        else if (l.length > LIMITS.columnLabel) errs[`col:${s.id}:${c.id}`] = 'te.err.columnLabelTooLong'
      }
      for (const r of s.rows || []) {
        const l = shortTextValue(r.label)
        if (!l) errs[`row:${s.id}:${r.id}`] = 'te.err.rowLabelRequired'
        else if (l.length > LIMITS.label) errs[`row:${s.id}:${r.id}`] = 'te.err.rowLabelTooLong'
      }
    }
  }
  return errs
}

export function isValidId(id) {
  return typeof id === 'string' && ID_RE.test(id)
}

// ── Değerler ────────────────────────────────────────────────────────────────────────────────────────

/** Sunucudan gelen değer belgesini düzenleyici durumuna çevirir (eksik kovalar boş). */
export function normalizeValues(values) {
  return {
    fields: { ...(values?.fields || {}) },
    issues: Object.fromEntries(Object.entries(values?.issues || {}).map(([k, v]) => [k, Array.isArray(v) ? v.map((i) => ({ ...i })) : []])),
    texts: { ...(values?.texts || {}) },
    tables: Object.fromEntries(Object.entries(values?.tables || {}).map(([sid, rows]) => [sid,
      Object.fromEntries(Object.entries(rows || {}).map(([rid, cells]) => [rid, { ...(cells || {}) }]))])),
    // Raporun o aya özel hedefleri (2026-10-17, 4b)
    targets: { ...(values?.targets || {}) },
    // Kısaltmayla girilmiş sayıların üssü (2026-10-18): anahtar alan kimliği / issues.… / tables.…
    units: { ...(values?.units || {}) },
  }
}

/**
 * Tablonun okunur en az genişliği (px): satır adı sütunu (176, `w-44`) + sütun başına (sayı / yüzde 112, metin 160).
 * Kap bundan darsa tablo yerine satır başına kart çizilir — on sütunlu bir tablo dar ekranda okunmaz kutulara
 * sıkışmasın. Tablo hücresinde giriş sıkıdır (13 px, dar iç boşluk): "125.000.000" ve "%99,95" kırpılmadan sığar ve
 * 6 sütunlu varsayılan SLA tablosu 1280 px dizüstünde (kenar çubuğu açık, kap ≈ 860 px) tablo kalır (inceleme
 * 2026-10-14: 136 px'lik bütçe onu karta düşürüyordu).
 */
export function tableMinWidth(columns) {
  let w = 176
  for (const c of columns || []) w += c?.type === 'text' ? 160 : 112
  return Math.max(640, w)
}

/** Tablo değerinin hata anahtarı (sunucu da aynısını döner). */
export function tableCellKey(sectionId, rowId, colId) {
  return `tables.${sectionId}.${rowId}.${colId}`
}

/**
 * Yeni anomali konusu. Kimlik bu rapor için TEKRAR KULLANILMAZ (2026-10-16, otomatik kayıt incelemesi): `i-<n>` silinen bir
 * konunun kimliğini yeni konuya veriyordu — geçmiş birleştirmesi "silindi + eklendi"yi aynı konu sanıp değişimi düşürürdü.
 */
export function newIssue(list) {
  const used = new Set((list || []).map((i) => i.id))
  let id
  for (let guard = 0; guard < 20; guard++) {
    id = `i-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
    if (!used.has(id)) break
  }
  return { id, topic: '', title: '', title_auto: true, description: '', customers: null, errors: null }
}

/**
 * Değer belgesinin KANONİK hâli (kaydedilmemiş değişiklik denetimi ve gönderim): boş / null alan değerleri düşer, boş
 * anomali listesi düşer, anomali nesnelerindeki null alanlar düşer (sunucu "yok" ile null'ı aynı okur). Metin
 * bölümlerinde boş metin KORUNUR (bilerek boşaltılmış bölüm basılmaz). İnceleme 2026-10-13: konu ekleyip silmek
 * ya da boş alan sahte "kaydedilmemiş değişiklik" gösteriyordu.
 */
export function canonicalValues(v, template = null) {
  const fields = {}
  for (const [k, x] of Object.entries(v?.fields || {})) {
    if (x == null) continue
    if (typeof x === 'string' && x.trim() === '') continue
    fields[k] = x
  }
  // Şablon verildiyse "Hayır" cevabıyla muaf alanların değeri gönderilmez / karşılaştırılmaz (sunucu da saklamaz);
  // taslakta kalır: kayıttan önce "Evet"e dönülürse yazılan değer geri gelir
  if (template) for (const f of allFields(template)) if (fieldExempt(f, fields)) delete fields[f.id]
  const issues = {}
  for (const [k, list] of Object.entries(v?.issues || {})) {
    if (!Array.isArray(list) || list.length === 0) continue
    // Anomali soruları (2026-10-18): şablon verildiyse sorulmayan / gizli cevap düşer (sunucu da saklamaz); pay sunucunun
    // hesabıdır — hiç gönderilmez, karşılaştırmaya girmez
    const section = template ? (template.sections || []).find((s) => s.id === k && s.kind === 'issues') : null
    issues[k] = list.map((i) => {
      const clean = Object.fromEntries(Object.entries(i || {}).filter(([, x]) => x != null))
      if (section) return conformIssue(section, clean)
      delete clean.share
      return clean
    })
  }
  // Tablo: boş hücre / satır / tablo düşer; hiç değer yoksa anahtar hiç yazılmaz (tablosuz raporun belgesi öncekiyle
  // aynı kalsın — sunucu da boş tabloyu yazmaz)
  const tables = {}
  for (const [sid, rows] of Object.entries(v?.tables || {})) {
    const outRows = {}
    for (const [rid, cells] of Object.entries(rows || {})) {
      const c = {}
      for (const [cid, x] of Object.entries(cells || {})) {
        if (x == null || (typeof x === 'string' && x.trim() === '')) continue
        c[cid] = x
      }
      if (Object.keys(c).length) outRows[rid] = c
    }
    if (Object.keys(outRows).length) tables[sid] = outRows
  }
  const texts = { ...(v?.texts || {}) }
  // Boş elle özet = otomatik (anahtar gönderilmez; sunucu da saklamaz) — metin bölümünde boş metin ANLAMLIDIR, korunur
  if (template) {
    for (const s of template.sections || []) {
      if (s.kind === 'summary' && typeof texts[s.id] === 'string' && richBlank(texts[s.id])) delete texts[s.id]
    }
  }
  // Raporun o aya özel hedefleri (2026-10-17, 4b): yalnız şablonda hedefi olan yüzde alanları; boş → yok (sunucu da
  // anahtarı yalnız doluyken yazar — hedefsiz raporun belgesi öncekiyle aynı kalır)
  const targets = {}
  for (const [k, x] of Object.entries(v?.targets || {})) {
    if (x == null || (typeof x === 'string' && x.trim() === '')) continue
    if (template && !allFields(template).some((f) => f.id === k && f.type === 'percent' && f.target != null && f.target !== '')) continue
    targets[k] = x
  }
  const out = { fields, issues, texts }
  if (Object.keys(tables).length) out.tables = tables
  if (Object.keys(targets).length) out.targets = targets
  // Kısaltma üsleri (2026-10-18): sunucudaki TechErrorValues.units ile aynı süzgeç — yalnız DEĞERİ olan sayı yeri (sayı
  // alanı / hesapsız, anomali adedi, sayı sütunu); boşsa anahtar hiç yazılmaz (kısaltmasız raporun belgesi öncekiyle aynı)
  const units = {}
  for (const [k, e] of Object.entries(v?.units || {})) {
    if (!UNIT_EXPONENTS.includes(e)) continue
    const n = unitPlaceValue(k, out, template)
    // Kaydırılınca 6 ondalığa sığmayan değerin üssü sunucuda da saklanmaz (TechErrorValues.units — inceleme 2026-10-18)
    if (n != null && abbreviable(n, e)) units[k] = e
  }
  if (Object.keys(units).length) out.units = units
  return out
}

/**
 * Kısaltma anahtarının gösterdiği yerdeki sayı değer (kanonik belgede; şablon verildiyse tür de denetlenir); sayı yoksa
 * null.
 */
function unitPlaceValue(key, out, template) {
  const num = (x) => (typeof x === 'number' ? x : null)
  const parts = String(key).split('.')
  if (parts[0] === 'issues' && parts.length === 4) {
    if (parts[3] !== 'customers' && parts[3] !== 'errors') return null
    if (template && !(template.sections || []).some((s) => s.id === parts[1] && s.kind === 'issues')) return null
    const iss = (out.issues[parts[1]] || []).find((i) => i?.id === parts[2])
    return num(iss?.[parts[3]])
  }
  if (parts[0] === 'tables' && parts.length === 4) {
    if (template) {
      const s = (template.sections || []).find((x) => x.id === parts[1] && x.kind === 'table')
      if (!s || !(s.rows || []).some((r) => r.id === parts[2])) return null
      if (!(s.columns || []).some((c) => c.id === parts[3] && c.type === 'number')) return null
    }
    return num(out.tables?.[parts[1]]?.[parts[2]]?.[parts[3]])
  }
  if (parts.length !== 1) return null
  if (template && !allFields(template).some((f) => f.id === key && f.type === 'number' && !f.calc?.kind)) return null
  return num(out.fields[key])
}

/** Şablonun karşılaştırma hâli: boş isteğe bağlı metinler (ipucu vb.) "yok" ile aynı sayılır. */
export function canonicalTemplate(tpl) {
  const strip = (o) => Object.fromEntries(Object.entries(o || {}).filter(([, x]) => x != null && x !== ''))
  if (!tpl) return tpl
  return {
    ...strip({ lang: tpl.lang, title: tpl.title }),
    sections: (tpl.sections || []).map((s) => {
      const out = strip({ ...s, fields: undefined, slots: undefined, columns: undefined, rows: undefined, lines: undefined, tiles: undefined })
      if (Array.isArray(s.fields)) {
        out.fields = s.fields.map((f) => {
          const o = strip({ ...f, calc: undefined })
          const c = canonCalc(f.calc)
          if (c) o.calc = c
          // Hedef sayı olarak yazılır (sunucu Double): "99,95" metni ile 99.95 aynı sayılır
          const tv = calcNumber(f.target)
          if (tv != null) o.target = tv
          else delete o.target
          return o
        })
      }
      if (Array.isArray(s.slots)) out.slots = s.slots.map(strip)
      if (Array.isArray(s.lines)) out.lines = s.lines.slice()
      // Sunucu kutucukları yalnız doluyken yazar: boş liste ile yokluk aynı sayılır
      if (Array.isArray(s.tiles) && s.tiles.length) out.tiles = s.tiles.slice()
      if (s.kind === 'issues') canonIssueOptions(out)
      if (Array.isArray(s.columns)) out.columns = s.columns.map(strip)
      // Sunucu grup bayrağını yalnız "true" iken yazar: false ile yokluk aynı sayılır
      if (Array.isArray(s.rows)) out.rows = s.rows.map((r) => strip({ ...r, group: r.group ? true : undefined }))
      return out
    }),
  }
}

/** Değer belgeleri eşit mi (kaydedilmemiş değişiklik denetimi; anahtar sırası önemsiz). */
export function sameValues(a, b) {
  return stableJson(a) === stableJson(b)
}

export function stableJson(v) {
  if (Array.isArray(v)) return `[${v.map(stableJson).join(',')}]`
  if (v && typeof v === 'object') {
    return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${stableJson(v[k])}`).join(',')}}`
  }
  return JSON.stringify(v === undefined ? null : v)
}

// ── Durum ───────────────────────────────────────────────────────────────────────────────────────────

/** Rapor durumu → rozet tonu + i18n anahtarı. Bilinmeyen durum asla "gönderildi" (yeşil) sayılmaz. */
export function statusMeta(status) {
  switch (status) {
    case 'sent': return { tone: 'success', key: 'te.status.sent' }
    case 'changed': return { tone: 'warning', key: 'te.status.changed' }
    case 'sending': return { tone: 'info', key: 'te.status.sending' }
    // Posta servisi kuyruğa aldı: teslim henüz bilinmiyor — "gönderildi" (yeşil) değil
    case 'queued': return { tone: 'info', key: 'te.status.queued' }
    case 'draft': return { tone: 'muted', key: 'te.status.draft' }
    default: return { tone: 'muted', key: 'te.status.none' }
  }
}

/** Son gönderim denemesinin sonucu (yalnız başarısız / kuyrukta / atlandı gösterilir). */
export function lastAttemptMeta(sendState) {
  switch (sendState) {
    case 'failed': return { tone: 'danger', key: 'te.attempt.failed' }
    case 'queued': return { tone: 'info', key: 'te.attempt.queued' }
    case 'skipped': return { tone: 'warning', key: 'te.attempt.skipped' }
    default: return null
  }
}

/** Rapor açılabilecek aylar: varsayılan dönemden geriye `count` ay (en yeni önce). */
export function periodOptions(defaultPeriod, count = 24) {
  if (!defaultPeriod?.year || !defaultPeriod?.month) return []
  const out = []
  let y = defaultPeriod.year
  let m = defaultPeriod.month
  for (let i = 0; i < count; i++) {
    out.push({ year: y, month: m, key: `${y}-${String(m).padStart(2, '0')}` })
    m -= 1
    if (m === 0) { m = 12; y -= 1 }
  }
  return out
}

/** Dosya boyutu (KB / MB) — dil bağımsız kısa gösterim. */
export function formatBytes(n, lang) {
  if (n == null || !Number.isFinite(Number(n))) return '—'
  const v = Number(n)
  if (v <= 0) return `${formatNumber(0, lang)} KB`
  if (v < 1024 * 1024) return `${formatNumber(Math.max(1, Math.round(v / 1024)), lang)} KB`
  return `${formatNumber(Math.round((v / 1048576) * 10) / 10, lang)} MB`
}

// Görsel ön denetimi ve hazırlama: imagePrep.js (2026-10-15, Faz C — tarayıcıda küçültme / çevirme)

// ── Otomatik trend (2026-10-15, Faz C) ──────────────────────────────────────────────────────────────

/** Sunucuyla aynı (TechErrorTrend.MONTHS / MIN_POINTS). */
// ── Otomatik hesap (TechErrorCalc aynası, 2026-10-16) ───────────────────────────────────────────────
//
// Kullanıcı isteği: "kullanıcı adetleri girer, yüzdeleri sistem hesaplar; üstte adetler, altta oranlar". Sunucu kaydederken
// hesaplar ve saklar (istemcinin oranı yok sayılır); burada aynı kural YAZARKEN gösterilir — taslağa yazılmaz. Doğruluk
// tablosu TechErrorCalcTest.TABLE (JS testi Java kaynağından okur).

export const CALC_KINDS = ['ratio', 'change', 'increase', 'compare']
/** "Daha iyi" yönü (2026-10-17, 4b; TechErrorTemplate.BETTER). */
export const BETTER = ['lower', 'higher', 'none']
/** Formül kullanılamıyor — alan elle girilir (geçen ayın raporu / değeri yok; başvurulan alan uygun değil). */
export const CALC_MANUAL = new Set(['MANUAL', 'BAD_REF'])

/** Formül girdisi olabilecek alan: sayı türünde, kendisi hesaplanmayan (TechErrorCalc.input). */
export function calcInput(field) {
  return !!field && field.type === 'number' && !field.calc?.kind
}

/** Hesabın başvurduğu alan kimlikleri, sırayla (boşlar atlanır). */
export function calcRefs(calc) {
  if (!calc?.kind) return []
  const list = calc.kind === 'ratio' ? [calc.num, calc.minus, calc.den] : [calc.of]
  return list.filter((x) => typeof x === 'string' && x !== '')
}

/** Hesabın kanonik hâli (sunucunun yazdığı gibi: tür + dolu anahtarlar); türü boşsa undefined (= elle). */
export function canonCalc(calc) {
  if (!calc?.kind) return undefined
  const out = { kind: calc.kind }
  for (const k of calc.kind === 'ratio' ? ['num', 'minus', 'den'] : ['of']) {
    if (typeof calc[k] === 'string' && calc[k] !== '') out[k] = calc[k]
  }
  return out
}

/** Hesap tanımının BİÇİM hatası (sunucunun katı kuralı) — i18n anahtarı; yoksa null. Başvuru varlığı uyarıdır, hata değil. */
export function calcShapeError(field) {
  const c = field?.calc
  if (!c?.kind) return null
  if (!CALC_KINDS.includes(c.kind)) return 'te.err.calcKind'
  const percentKind = c.kind !== 'increase' && c.kind !== 'compare'
  if (percentKind ? field.type !== 'percent' : field.type !== 'yesno') return 'te.err.calcType'
  const need = c.kind === 'ratio' ? [c.num, c.den] : [c.of]
  if (need.some((x) => !x)) return 'te.err.calcRequired'
  if (calcRefs(c).includes(field.id)) return 'te.err.calcSelf'
  return null
}

export const calcNumber = (v) => {
  if (v == null || v === '' || typeof v === 'boolean') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * Sayının en kısa ondalık gösterimi (Java {@code Double.toString} / {@code BigDecimal.valueOf} ile aynı basamaklar):
 * |x| = 0.all × 10^point, {@code all} baştaki sıfırlar atılmış basamaklar.
 */
function decimalParts(x) {
  const [m, e = '0'] = Math.abs(x).toString().split('e')
  const [ip, fp = ''] = m.split('.')
  let all = ip + fp
  let point = ip.length + Number(e)
  while (all.length > 1 && all[0] === '0') { all = all.slice(1); point -= 1 }
  return { all, point }
}

/**
 * {@code scale} ondalığa yarım yukarı — BigDecimal.valueOf(x).setScale(scale, HALF_UP).doubleValue() ile AYNI: kısa ondalık
 * gösterimin BASAMAKLARINDAN (sayı çarpılıp Math.round edilmez; 1,4749999999999999 × 100 = 147,5 olur, 1,48 verirdi).
 */
export function roundHalfUp(x, scale) {
  if (!Number.isFinite(x) || x === 0) return x
  const { all, point } = decimalParts(x)
  const k = point + scale
  let n = 0n
  if (k >= 0) {
    const kept = k === 0 ? '0' : all.slice(0, k).padEnd(k, '0')
    n = BigInt(kept) + ((all[k] || '0') >= '5' ? 1n : 0n)
  }
  const r = Number(n) / 10 ** scale
  return r === 0 ? 0 : (x < 0 ? -r : r)
}

/** 0,01'in altındaki sayının 2 anlamlı hanesi için ondalık sayısı (en çok 6) — TechErrorCalc.smallScale. */
export function smallScale(x) {
  return Math.min(6, 2 - decimalParts(x).point)
}

/**
 * Hesaplanan oranın yuvarlaması — sunucuyla aynı (TechErrorCalc.roundRate, kullanıcı kararı 2026-10-16): virgülden sonra
 * 2 hane, yarım yukarı; sıfırdan büyük ama 0,01'in altındaki oran 2 anlamlı haneyle (en çok 6 ondalık) — sahte %0 yok.
 */
export function roundCalc(x) {
  if (!Number.isFinite(x) || x === 0) return x
  return roundHalfUp(x, Math.abs(x) < 0.01 ? smallScale(x) : 2)
}

const calcOk = (p) => (!Number.isFinite(p) || Math.abs(p) > LIMITS.maxPercent
  ? { status: 'RANGE', value: null, missing: [] }
  : { status: 'OK', value: roundCalc(p), missing: [] })

/**
 * Tek alanın sonucu `{ status, value, missing }` (TechErrorCalc.result ile aynı): OK | MISSING | ZERO | NEGATIVE | EQUAL |
 * RANGE (formül kullanılabilir, sonuç boş) | MANUAL | BAD_REF (formül kullanılamaz — elle). `previous` geçen ayın aynı
 * kimlik / türdeki alanları; rapor yoksa null. Hesaplanmayan alan → null.
 */
export function calcResult(field, byId, fields, previous, targets = null) {
  const c = field?.calc
  if (!c?.kind) return null
  const res = (status, value = null, missing = []) => ({ status, value, missing })
  if (c.kind === 'compare') {
    // Hedef karşılaştırması (2026-10-17, 4b): hedefi olan yüzde alanı (hesaplanan da olabilir — `fields` 1. geçişin sonucu);
    // yön alanın "daha iyi"si: yüksek iyi → ≥, düşük iyi → ≤ (eşitlik tutturuldu)
    const of = byId.get(c.of)
    if (!of || of.type !== 'percent') return res('BAD_REF')
    const target = effectiveTarget(of, targets)
    if (target == null) return res('BAD_REF')
    const better = effectiveBetter(of)
    if (better === 'none') return res('BAD_REF')          // yönsüz alan hedefle karşılaştırılamaz (sunucu ile aynı)
    const cur = calcNumber(fields?.[of.id])
    if (cur == null) return res('MISSING', null, [of.id])
    return res('OK', better === 'lower' ? cur <= target : cur >= target)
  }
  if (c.kind === 'ratio') {
    const refs = [c.num, c.minus, c.den]
    const v = [null, null, null]
    const missing = []
    for (let i = 0; i < refs.length; i++) {
      if (!refs[i]) continue
      if (!calcInput(byId.get(refs[i]))) return res('BAD_REF')
      v[i] = calcNumber(fields?.[refs[i]])
      if (v[i] == null) missing.push(refs[i])
    }
    if (missing.length) return res('MISSING', null, missing)
    if (v[2] === 0) return res('ZERO')
    const numerator = v[0] - (v[1] == null ? 0 : v[1])
    if (numerator < 0) return res('NEGATIVE')
    return calcOk(numerator / v[2] * 100)
  }
  if (!calcInput(byId.get(c.of))) return res('BAD_REF')
  const prev = previous == null ? null : calcNumber(previous[c.of])
  if (prev == null) return res('MANUAL')
  const cur = calcNumber(fields?.[c.of])
  if (cur == null) return res('MISSING', null, [c.of])
  if (c.kind === 'increase') return cur === prev ? res('EQUAL') : res('OK', cur > prev)
  if (prev === 0) return res('ZERO')
  return calcOk(Math.abs(cur - prev) / prev * 100)
}

/**
 * Şablondaki tüm hesaplanan alanların sonuçları `{ [id]: result }`. Muaf alanların ("Hayır" cevabı) değeri girdi sayılmaz
 * (sunucu onları saklamaz); muaf hesaplanan alan sonuç almaz.
 */
export function calcResults(template, fields, previous, targets = null) {
  const all = allFields(template)
  const out = {}
  if (!all.some((f) => f.calc?.kind)) return out
  const byId = new Map(all.map((f) => [f.id, f]))
  const inputs = { ...(fields || {}) }
  for (const f of all) if (fieldExempt(f, fields)) delete inputs[f.id]
  // 1. geçiş: oran / değişim / artış; 2. geçiş: hedef karşılaştırması — hesaplanmış yüzdeyi de görür (sunucu ile aynı sıra)
  for (const f of all) {
    if (!f.calc?.kind || f.calc.kind === 'compare' || fieldExempt(f, fields)) continue
    out[f.id] = calcResult(f, byId, inputs, previous, targets)
  }
  const applied = { ...inputs }
  for (const [id, x] of Object.entries(out)) {
    if (x.status === 'OK') applied[id] = x.value
    else if (!CALC_MANUAL.has(x.status)) delete applied[id]
  }
  for (const f of all) {
    if (f.calc?.kind !== 'compare' || fieldExempt(f, fields)) continue
    out[f.id] = calcResult(f, byId, applied, previous, targets)
  }
  return out
}

/**
 * Hesaplanan alanlar uygulanmış değerler — yalnız GÖSTERİM (özet önizlemesi, trend grafiğinin bu ayı); taslağa yazılmaz,
 * kaydedilen değeri sunucu hesaplar. Formül kullanılamayan alanda girilen değer kalır. Hesap yoksa AYNI nesne.
 */
export function applyCalc(template, fields, previous, results = null, targets = null) {
  const r = results || calcResults(template, fields, previous, targets)
  const ids = Object.keys(r)
  if (!ids.length) return fields
  const out = { ...(fields || {}) }
  for (const id of ids) {
    const x = r[id]
    if (x.status === 'OK') out[id] = x.value
    else if (!CALC_MANUAL.has(x.status)) delete out[id]
  }
  return out
}

/**
 * Bir adetten türeyen oranlar (2026-10-16, kullanıcı isteği: "kota / map yapılmamış hata adedi girişinde yüzde de
 * görünsün"): adeti PAY olarak kullanan oran alanları, şablon sırasıyla. Girişin altında canlı değerleriyle gösterilir.
 */
export function derivedRates(fields, fieldId) {
  return (fields || []).filter((f) => f.calc?.kind === 'ratio' && f.calc.num === fieldId)
}

/**
 * Hesap başvurularının durumu (şablon düzenleyici uyarısı): `[{ id, field, status }]`, status ok | missing (şablonda yok)
 * | notNumber (sayı değil) | computed (kendisi hesaplanıyor). Kayıtta reddedilmez — formül kullanılamazsa alan elle girilir.
 */
export function calcRefIssues(template, field) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const compare = field?.calc?.kind === 'compare'
  return calcRefs(field?.calc).map((id) => {
    const f = byId.get(id) || null
    // Hedef karşılaştırması (4b): şablonda HEDEFİ olan bir yüzde alanı (hesaplanan olabilir)
    if (compare) return { id, field: f, status: !f ? 'missing' : f.type !== 'percent' ? 'notPercent' : effectiveTarget(f, null) == null ? 'noTarget' : 'ok' }
    return { id, field: f, status: !f ? 'missing' : f.type !== 'number' ? 'notNumber' : f.calc?.kind ? 'computed' : 'ok' }
  }).filter((x) => x.status !== 'ok')
}

/**
 * Yönetici özetinin başındaki RENKLİ KUTUCUKLAR (2026-10-17, 4b; sunucudaki TechErrorTiles.build, ortak doğruluk tablosu
 * TechErrorTilesTest.TILES_TABLE): `{ id, label, value, delta, dir, tone, target, met }` — şablonda olmayan / sayı-yüzde
 * olmayan / muaf alan çizilmez; değeri boş alan "—" ve `unknown`; ton: hedefli alan hedefi tutturdu mu (good / bad),
 * hedefsiz alan "daha iyi" yönüne göre önceki aya karşı (iyileşme good, kötüleşme bad; yön yok / geçen ay yok / değişim yok
 * neutral). `fields` hesaplar uygulanmış değerler olmalı.
 */
export function summaryTiles(template, section, fields, targets, previous, channel, year, month, units = null) {
  const out = []
  if (!template || !section || !(section.tiles || []).length) return out
  const lang = template.lang
  const tplFields = allFields(template)
  const byId = new Map(tplFields.map((f) => [f.id, f]))
  for (const id of section.tiles) {
    if (out.length >= LIMITS.tiles) break
    const f = byId.get(id)
    if (!f || (f.type !== 'number' && f.type !== 'percent')) continue
    if (fieldExempt(f, fields)) continue
    const cur = fields?.[id]
    const c = calcNumber(cur)
    const label = resolvePlaceholders(f.label || '', channel, year, month, lang)
    const unit = units?.[id] ?? null
    const value = c == null ? '—' : formatValue(f, c, lang, summaryYesNo(lang), unit)
    const d = deltaOf(f, previous?.[id], cur, lang, unit)
    const target = effectiveTarget(f, targets)
    const better = effectiveBetter(f)
    let met = null
    let tone
    if (c == null) tone = 'unknown'
    else if (target != null && better !== 'none') {
      // Hedefli alan: tutturdu mu (eşitlik tutturuldu); yönsüz hedef karşılaştırılamaz → nötr yol (sunucu ile aynı)
      met = better === 'lower' ? c <= target : c >= target
      tone = met ? 'good' : 'bad'
    } else {
      if (better === 'none' || !d || d.dir === 'flat') tone = 'neutral'
      else tone = (d.dir === 'up') === (better === 'higher') ? 'good' : 'bad'
    }
    out.push({ id, label, value, delta: d ? d.text : null, dir: d ? d.dir : null, tone,
      target: target == null ? null : formatPercent(target, lang), met, zero: zeroOk(f, cur, tplFields) })
  }
  return out
}

/** Alanı formülünde kullanan hesaplanan alanlar (alan silinirken uyarı için). */
export function calcFieldsUsing(template, fieldId) {
  return allFields(template).filter((f) => f.id !== fieldId && calcRefs(f.calc).includes(fieldId))
}

/**
 * Alanı "tüm teknik hatalar" (`ratio_of`) olarak kullanan anomali bölümleri (inceleme 2026-10-18): alan silinirken onayda
 * söylenir — pay o bölümde hesaplanamaz (şablon kilitlenmez, düzenleyici uyarır).
 */
export function ratioSectionsUsing(template, fieldId) {
  return (template?.sections || []).filter((s) => s.kind === 'issues' && typeof s.ratio_of === 'string' && s.ratio_of === fieldId)
}

/**
 * Anomali bölümünün pay alanı uyarısı: `ratio_of` şablonda yoksa `te.tpl.anomaly.ratioMissing`, sayı alanı değilse
 * `te.tpl.anomaly.ratioNotNumber`; sorun yoksa (ya da pay sorulmuyorsa) null.
 */
export function ratioOfWarning(template, section) {
  const ref = typeof section?.ratio_of === 'string' ? section.ratio_of.trim() : ''
  if (!ref) return null
  const f = allFields(template).find((x) => x.id === ref)
  if (!f) return 'te.tpl.anomaly.ratioMissing'
  return f.type !== 'number' ? 'te.tpl.anomaly.ratioNotNumber' : null
}

export const TREND_MONTHS = 12
export const TREND_MIN_POINTS = 2
/** Grafik başlığının eki, şablon dilinde (PDF yazıcısıyla aynı metin — test Java kaynağından okur). */
export const TREND_SUFFIX = Object.freeze({ tr: ' (Son 12 Ay)', en: ' (Last 12 Months)' })

/** Grafiğe dökülebilen alan: sayı ya da yüzde (TechErrorTemplate.trendable). */
export function isTrendable(field) {
  return !!field && (field.type === 'number' || field.type === 'percent')
}

/** Şablondaki grafiğe dökülebilen alanlar, bölüm adıyla (`{ ...field, section }`), şablon sırasıyla. */
export function trendableFields(template) {
  return (template?.sections || []).filter((s) => s.kind === 'metrics')
    .flatMap((s) => (s.fields || []).filter(isTrendable).map((f) => ({ ...f, section: s.title || '' })))
}

/**
 * Trend bölümünün başvuruları: `{ id, field, status }` — status ok | missing (şablonda yok) | notNumeric (sayı / yüzde
 * değil). Kayıtta reddedilmez (alan silmek kilitlenmesin); düzenleyici uyarır, grafik çizilmez.
 */
export function trendRefs(template, section) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  return (section?.series || []).map((id) => {
    const field = byId.get(id) || null
    return { id, field, status: !field ? 'missing' : isTrendable(field) ? 'ok' : 'notNumeric' }
  })
}

/** Alanı grafiğe döken trend bölümleri (alan silinirken uyarı için). */
export function trendSectionsUsing(template, fieldId) {
  return (template?.sections || []).filter((s) => s.kind === 'trend' && (s.series || []).includes(fieldId))
}

/**
 * Bu değişiklikle şablondan ÇIKAN alanları trend seçimlerinden de düşürür (alan / bölüm silindi — kullanıcı onayladı).
 * Önceden kalan, şablonda zaten olmayan başvurulara dokunmaz (düzenleyici onları ayrıca gösterir); başka bölüme taşınan
 * alan kimliğini korur, düşmez. Bölümün SON alanı silinirse seçim olduğu gibi kalır (boş seçim kaydı reddettirirdi —
 * inceleme 2026-10-15; grafik çizilmez, düzenleyici uyarır). Değişiklik yoksa AYNI nesne döner.
 */
export function pruneRemovedTrendRefs(prev, next) {
  if (!prev || !next || prev === next) return next
  const after = new Set(allFields(next).map((f) => f.id))
  const gone = new Set(allFields(prev).map((f) => f.id).filter((id) => !after.has(id)))
  if (!gone.size) return next
  let changed = false
  const sections = (next.sections || []).map((s) => {
    if (s.kind !== 'trend') return s
    const keep = (s.series || []).filter((id) => !gone.has(id))
    // Son alan da gidiyorsa başvuru bırakılır (düzenleyici "şablonda yok" der): boş seçim kaydı kilitlerdi
    if (keep.length === (s.series || []).length || keep.length === 0) return s
    changed = true
    return { ...s, series: keep }
  })
  return changed ? { ...next, sections } : next
}

/** Alanı renkli kutucuk olarak gösteren özet bölümleri (silme onayında söylenir; 2026-10-17, 4b). */
export function tileSectionsUsing(template, fieldId) {
  return (template?.sections || []).filter((s) => s.kind === 'summary' && (s.tiles || []).includes(fieldId))
}

/**
 * Bu değişiklikle şablondan ÇIKAN alanları özet kutucuklarından da düşürür (alan / bölüm silindi — kullanıcı onayladı;
 * inceleme 2026-10-17: ham kimlik listede kalıyordu). Trend budamasının aksine boş kutucuk listesi geçerlidir (anahtar
 * silinir). Önceden sarkan kimliklere dokunmaz (düzenleyici onları "silinmiş alan" diye gösterir). Değişiklik yoksa AYNI nesne.
 */
export function pruneRemovedTiles(prev, next) {
  if (!prev || !next || prev === next) return next
  const after = new Set(allFields(next).map((f) => f.id))
  const gone = new Set(allFields(prev).map((f) => f.id).filter((id) => !after.has(id)))
  if (!gone.size) return next
  let changed = false
  const sections = (next.sections || []).map((s) => {
    if (s.kind !== 'summary' || !(s.tiles || []).length) return s
    const keep = s.tiles.filter((id) => !gone.has(id))
    if (keep.length === s.tiles.length) return s
    changed = true
    const out = { ...s }
    if (keep.length) out.tiles = keep
    else delete out.tiles
    return out
  })
  return changed ? { ...next, sections } : next
}

/**
 * Bir alanın 12 aylık noktaları: sunucunun kayıtlı değerleri, raporun AYI için düzenleyicideki taslak (kaydedilmemiş
 * değişiklik grafikte hemen görünsün). Boş ay null — sıfır değildir. Dönüş: `[{ year, month, value, current }]`.
 */
export function trendPoints(trend, fieldId, draftFields, field) {
  const months = Array.isArray(trend?.months) ? trend.months : []
  const vals = Array.isArray(trend?.values?.[fieldId]) ? trend.values[fieldId] : []
  if (!months.length || vals.length !== months.length) return []
  return months.map((m, i) => {
    const current = i === months.length - 1
    let value = vals[i] == null ? null : Number(vals[i])
    if (current && draftFields) {
      const raw = draftFields[fieldId]
      value = fieldExempt(field, draftFields) || raw == null || raw === '' || !Number.isFinite(Number(raw)) ? null : Number(raw)
    }
    return { year: m.year, month: m.month, value: Number.isFinite(value) ? value : null, current }
  })
}

/**
 * Çubuk etiketi — sunucudaki TechErrorFormat.compact ile AYNI: sayı iki dilde de Qlik harfleriyle k / M / G / T (2026-10-18;
 * önce TR B / Mn / Mr, EN K / M / B), 100'ün altında bir ondalık; yuvarlanınca 1000 olacak değer bir üst birime geçer ("1M",
 * "1.000k" değil). Yüzde en çok iki ondalık, 0,1'in
 * altında iki anlamlı basamak kalacak kadar (en çok altı — saklama hassasiyeti). Yuvarlama Java HALF_UP ile aynı (Intl "halfExpand"). Tam
 * değer araç ipucunda ve ekran okuyucu listesinde.
 */
/** Yüzde etiketinde ondalık: 0,1 ve üstü 2; altında iki anlamlı basamak kalacak kadar (değerler 6 ondalığa kadar saklanır). */
function percentDigits(a) {
  if (a === 0 || a >= 0.1) return 2
  if (a >= 0.01) return 3
  if (a >= 0.001) return 4
  if (a >= 0.0001) return 5
  return 6
}

export function compactNumber(value, type, lang) {
  if (value == null || !Number.isFinite(Number(value))) return '—'
  const v = Number(value) + 0                                          // -0 → 0 (sunucu 0 saklar; "%-0" yazılmasın)
  const en = lang === 'en'
  const fmt = (n, digits) => new Intl.NumberFormat(en ? 'en-US' : 'tr-TR', { maximumFractionDigits: digits, roundingMode: 'halfExpand' }).format(n)
  const a = Math.abs(v)
  if (type === 'percent') {
    const n = fmt(v, percentDigits(a))
    return en ? `${n}%` : `%${n}`
  }
  if (a < 1000) return formatNumber(v, lang)
  // Qlik harfleri iki dilde de (kullanıcı kararı 2026-10-18; TechErrorFormat.compact)
  const units = ['k', 'M', 'G', 'T']
  let u = a < 1e6 ? 0 : a < 1e9 ? 1 : a < 1e12 ? 2 : 3
  let scaled = v / 1000 ** (u + 1)
  if (Math.abs(scaled) >= 999.5 && u < units.length - 1) {           // 999,5 → 1000: bir üst birim
    u += 1
    scaled = v / 1000 ** (u + 1)
  }
  // Harften önceki kısım gruplanmaz (inceleme 2026-10-18 — "1250T", formatAbbreviated ile aynı kural)
  const digits = Math.abs(scaled) < 100 ? 1 : 0
  return new Intl.NumberFormat(en ? 'en-US' : 'tr-TR', { maximumFractionDigits: digits, roundingMode: 'halfExpand', useGrouping: false })
    .format(scaled) + units[u]
}

/**
 * Yazı ölçülemezse (canvas yok) karakter başına tahmin — TEMKİNLİ: Linux'ta "%0,49" 9 px'te ≈ 30 px (Windows'ta ≈ 25);
 * Windows ölçüsüne göre seçilen 5,0 CI'da etiketleri bitiştirdi (2026-10-15). Tarayıcıda gerçek ölçü kullanılır.
 */
export const TREND_LABEL_CHAR_PX = 6.0
/** 10 px ay adı için aynı temkinli tahmin (px / karakter) */
export const TREND_TICK_CHAR_PX = 7.0
/** Komşu etiketler / ay adları arasında en az boşluk (px) */
const TREND_TEXT_GAP = 4

/** Metin genişliği: ölçüm (`measure(text)` → px) varsa o, yoksa karakter tahmini. */
function textWidthOf(text, measure, charPx) {
  const s = String(text || '')
  if (!s) return 0
  const m = typeof measure === 'function' ? measure(s) : null
  return Number.isFinite(m) && m > 0 ? m : s.length * charPx
}

/**
 * Çubuk etiketleri sığıyor mu? Çizim genişliği (px) ÷ çubuk sayısı en geniş etiketten (+ boşluk) darsa yalnız bu ayın
 * etiketi yazılır (`current`; telefonda "125Mn" komşusunun üstüne biniyordu) — tam değerler araç ipucunda ve ekran
 * okuyucu listesinde. `measure(text)` gerçek yazı genişliği (px; tarayıcı); verilmezse karakter tahmini. Genişlik
 * bilinmiyorsa (0: jsdom, ilk boyama) `all`.
 */
export function trendLabelMode(plotWidth, texts, measure) {
  if (!plotWidth || !texts?.length) return 'all'
  const widest = Math.max(0, ...texts.map((s) => textWidthOf(s, measure, TREND_LABEL_CHAR_PX)))
  return plotWidth / texts.length >= widest + TREND_TEXT_GAP ? 'all' : 'current'
}

/**
 * Ay ekseni yazıları: `[{ label, year }]` (gizli ay `label: ''`). Ay adları sığmıyorsa (telefon) atlanarak yazılır — en
 * küçük sığan adımla (2, 3 …), raporun ayı her zaman görünür, oradan geriye adım adım. Yıl, görünen İLK ayın ve yılı bir
 * öncekinden farklı görünen ayın altında (PDF'teki gibi; 12 ay iki yıla yayılır). Genişlik bilinmiyorsa (0) hepsi.
 */
export function trendTicks(points, lang, plotWidth, measure) {
  const names = (points || []).map((p) => shortMonth(p.month, lang))
  const n = names.length
  if (!n) return []
  const need = Math.max(...names.map((s) => textWidthOf(s, measure, TREND_TICK_CHAR_PX))) + TREND_TEXT_GAP
  let step = 1
  if (plotWidth) while (step < n && (plotWidth / n) * step < need) step++
  let prevYear = null
  return points.map((p, i) => {
    const shown = (n - 1 - i) % step === 0
    if (!shown) return { label: '', year: '' }
    const year = prevYear === p.year ? '' : String(p.year)
    prevYear = p.year
    return { label: names[i], year }
  })
}

/** Ayın kısa adı (şablon dilinde): Oca, Şub … / Jan, Feb … */
export function shortMonth(month, lang) {
  const n = monthName(month, lang) || ''
  return n.length > 3 ? n.slice(0, 3) : n
}

// ── Geçmiş ve sürümler (2026-10-14, Faz B) ──────────────────────────────────────────────────────────

/** Raporları yıla göre gruplar (en yeni yıl önce; yıl içinde en yeni ay önce). `[{ year, items }]`. */
export function groupByYear(reports) {
  const map = new Map()
  for (const r of reports || []) {
    // Number(null) = 0: yılı olmayan satır "0" yılına düşmesin
    const y = r?.year == null ? NaN : Number(r.year)
    if (!Number.isInteger(y) || y <= 0) continue
    if (!map.has(y)) map.set(y, [])
    map.get(y).push(r)
  }
  return [...map.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([year, items]) => ({ year, items: items.slice().sort((a, b) => (Number(b.month) || 0) - (Number(a.month) || 0)) }))
}

/**
 * İki şablon sürümü arasındaki fark (sürüm geçmişi penceresi). Bölümler, alanlar, grafik yuvaları, özet cümleleri, tablo
 * sütun / satırları kimlikle eşleşir: eklenen, kaldırılan, yeniden adlandırılan, türü değişen. Sıra değişimi bölüm
 * düzeyinde tek satır. Etiketler HAM (yer tutucular çözülmemiş) — gösterim çözer. Satır:
 * `{ group, op, label, before, after, section }`; group: report | section | field | slot | summary | column | row | text.
 */
export function templateDiff(prev, cur) {
  // Okunamayan sürüm "fark yok" değildir — bilinmiyor (inceleme 2026-10-14)
  if (!prev || !cur) return null
  const out = []
  if ((prev.title || '') !== (cur.title || '')) out.push({ group: 'report', op: 'title', before: prev.title || '', after: cur.title || '' })
  if ((prev.lang || 'tr') !== (cur.lang || 'tr')) out.push({ group: 'report', op: 'lang', before: prev.lang || 'tr', after: cur.lang || 'tr' })
  const ps = new Map((prev.sections || []).map((s) => [s.id, s]))
  const cs = new Map((cur.sections || []).map((s) => [s.id, s]))
  // Alan / yuva kimlikleri şablon genelinde tekildir: bir bölümden çıkıp başka bölümde duran öğe TAŞINMIŞTIR (inceleme
  // 2026-10-15 — "şablonu uygula" görselleri kimlikle taşır; sürüm farkı "kaldırıldı" diyordu)
  const where = (tpl, kind, key, keep) => {
    const m = new Map()
    for (const s of tpl.sections || []) {
      if (s.kind !== kind || !keep(s)) continue
      for (const x of s[key] || []) m.set(x.id, { title: s.title || '', item: x })
    }
    return m
  }
  // "Taşındı" satırını kaynak bölümün farkı yazar: kaynak bölüm silindi ya da türü değiştiyse o fark hiç çalışmaz — öğe
  // hedefte "eklendi" görünmeli (düzeltme incelemesi), bu yüzden önceki haritada yalnız aynı kimlik + türle süren bölümler
  const survives = (s) => cs.get(s.id)?.kind === s.kind
  const moves = {
    field: { prev: where(prev, 'metrics', 'fields', survives), cur: where(cur, 'metrics', 'fields', () => true) },
    slot: { prev: where(prev, 'images', 'slots', survives), cur: where(cur, 'images', 'slots', () => true) },
  }
  for (const s of prev.sections || []) if (!cs.has(s.id)) out.push({ group: 'section', op: 'remove', label: s.title || '', kind: s.kind })
  for (const s of cur.sections || []) if (!ps.has(s.id)) out.push({ group: 'section', op: 'add', label: s.title || '', kind: s.kind })
  const commonPrev = (prev.sections || []).filter((s) => cs.has(s.id)).map((s) => s.id)
  const commonCur = (cur.sections || []).filter((s) => ps.has(s.id)).map((s) => s.id)
  if (commonPrev.join('\u0000') !== commonCur.join('\u0000')) out.push({ group: 'section', op: 'reorder' })
  for (const s of cur.sections || []) {
    const p = ps.get(s.id)
    if (!p) continue
    const section = s.title || ''
    if ((p.title || '') !== section) out.push({ group: 'section', op: 'rename', before: p.title || '', after: section })
    if (p.kind !== s.kind) { out.push({ group: 'section', op: 'retype', label: section, before: p.kind, after: s.kind }); continue }
    // Gösterim yeri (özet kartı ↔ ayrı bölüm, 2026-10-17): önce / sonra gösterildiği özetin başlığı, ayrı bölümse null
    if (s.kind === 'metrics' && (p.host || '') !== (s.host || '')) {
      const hostTitle = (tpl, x) => (x.host ? (hostOf(tpl, x)?.title ?? '') : null)
      out.push({ group: 'section', op: 'host', label: section, before: hostTitle(prev, p), after: hostTitle(cur, s) })
    }
    if (s.kind === 'metrics') itemsDiff(out, 'field', section, p.fields, s.fields, moves.field, (a, b) => {
      const r = []
      if ((a.type || '') !== (b.type || '')) r.push({ op: 'retype', before: a.type, after: b.type })
      if ((a.skip_if_no || '') !== (b.skip_if_no || '')) r.push({ op: 'link' })
      if (stableJson(canonCalc(a.calc) || null) !== stableJson(canonCalc(b.calc) || null)) r.push({ op: 'calc' })
      // Hedef ve "daha iyi" yönü (2026-10-17, 4b)
      if ((calcNumber(a.target) ?? null) !== (calcNumber(b.target) ?? null)) r.push({ op: 'target', before: calcNumber(a.target), after: calcNumber(b.target) })
      if ((a.better || '') !== (b.better || '')) r.push({ op: 'better', before: a.better || null, after: b.better || null })
      return r
    })
    if (s.kind === 'images') itemsDiff(out, 'slot', section, p.slots, s.slots, moves.slot, (a, b) =>
      ((a.width || '') !== (b.width || '') ? [{ op: 'resize', before: a.width, after: b.width }] : []))
    if (s.kind === 'table') {
      itemsDiff(out, 'column', section, p.columns, s.columns, null, (a, b) =>
        ((a.type || '') !== (b.type || '') ? [{ op: 'retype', before: a.type, after: b.type }] : []))
      itemsDiff(out, 'row', section, p.rows, s.rows, null, (a, b) =>
        (!!a.group !== !!b.group ? [{ op: b.group ? 'group-on' : 'group-off' }] : []))
    }
    if (s.kind === 'summary') {
      const a = p.lines || []
      const b = s.lines || []
      if (a.join('\u0000') !== b.join('\u0000')) out.push({ group: 'summary', op: 'change', label: section, before: a.length, after: b.length })
      // Kutucuklar (2026-10-17, 4b)
      const ta = p.tiles || []
      const tb = s.tiles || []
      if (ta.join('\u0000') !== tb.join('\u0000')) out.push({ group: 'summary', op: 'tiles', label: section, before: ta.length, after: tb.length })
    }
    if (s.kind === 'text' && (p.text || '') !== (s.text || '')) out.push({ group: 'text', op: 'change', label: section })
    if (s.kind === 'trend') {
      const a = p.series || []
      const b = s.series || []
      if (a.join('\u0000') !== b.join('\u0000')) out.push({ group: 'trend', op: 'change', label: section, before: a.length, after: b.length })
    }
    if (s.kind === 'issues') {
      const keys = ['item_title', 'description_label', 'customers_label', 'errors_label']
      if (keys.some((k) => (p[k] || '') !== (s[k] || ''))) out.push({ group: 'section', op: 'labels', label: section })
      // Anomali soruları (2026-10-18): aç / kapa, etiket, gösterim ya da pay alanı değiştiyse tek satır
      const a = canonIssueOptions({ ...p })
      const b = canonIssueOptions({ ...s })
      if (ISSUE_OPTION_KEYS.some((k) => stableJson(a[k] ?? null) !== stableJson(b[k] ?? null))) {
        out.push({ group: 'section', op: 'anomaly', label: section })
      }
    }
  }
  return out
}

function itemsDiff(out, group, section, prevList, curList, moved, extra) {
  const pm = new Map((prevList || []).map((x) => [x.id, x]))
  const cm = new Map((curList || []).map((x) => [x.id, x]))
  for (const x of prevList || []) {
    if (cm.has(x.id)) continue
    const dest = moved?.cur.get(x.id)
    if (!dest) { out.push({ group, op: 'remove', label: x.label || '', section }); continue }
    out.push({ group, op: 'move', label: x.label || '', section, to: dest.title })
    // Taşınan öğenin diğer değişiklikleri de (ad, tür, bağ / genişlik) — hedef bölümle; tür değişince değer taşınmaz
    const y = dest.item
    if ((x.label || '') !== (y.label || '')) out.push({ group, op: 'rename', before: x.label || '', after: y.label || '', section: dest.title })
    for (const e of extra(x, y)) out.push({ group, label: y.label || '', section: dest.title, ...e })
  }
  // Bölüm içi sıra da PDF'i değiştirir (alan / yuva / sütun / satır) — ortak öğelerin sırası farklıysa tek satır
  const commonPrev = (prevList || []).filter((x) => cm.has(x.id)).map((x) => x.id)
  const commonCur = (curList || []).filter((x) => pm.has(x.id)).map((x) => x.id)
  if (commonPrev.join('\u0000') !== commonCur.join('\u0000')) out.push({ group, op: 'reorder', section })
  for (const x of curList || []) {
    const p = pm.get(x.id)
    if (!p) {
      if (!moved?.prev.has(x.id)) out.push({ group, op: 'add', label: x.label || '', section })   // taşınan: "move" satırı yeter
      continue
    }
    if ((p.label || '') !== (x.label || '')) out.push({ group, op: 'rename', before: p.label || '', after: x.label || '', section })
    for (const e of extra(p, x)) out.push({ group, label: x.label || '', section, ...e })
  }
}

/** Geçmiş olayının gün anahtarı (İstanbul takvim günü, `YYYY-MM-DD`) — liste gün başlıklarıyla gruplanır. */
export function istanbulDay(iso) {
  const ms = Date.parse(iso || '')
  if (!Number.isFinite(ms)) return ''
  return new Date(ms + 3 * 3600 * 1000).toISOString().slice(0, 10)
}

// ── Katlanır bölümler + otomatik kayıt (2026-10-16, kullanıcı kararı) ─────────────────────────────────────────────

/**
 * Rapor ekranındaki bir hata anahtarının bölümü (yoksa null). İstemci: `values.<alan>`, `issue:<bölüm>:<konu>:<alan>`,
 * `tables.<bölüm>.<satır>.<sütun>`; sunucu: `values.<alan>`, `issues.<bölüm>.<n>`, `text.<bölüm>`, `tables.<bölüm>…`.
 * Belge geneli `values` hiçbir bölüme ait değil.
 */
export function reportErrorSection(key, template) {
  const k = String(key || '')
  if (k.startsWith('values.') || k.startsWith('targets.')) {
    const fid = k.slice(k.indexOf('.') + 1)
    const sec = (template?.sections || []).find((s) => s.kind === 'metrics' && (s.fields || []).some((f) => f.id === fid))
    // Özet kartına gömülü bölümün alanı kartın içindedir: hata o özetin (kapalı kartta sayı, kayıtta açılır)
    return sec ? (hostOf(template, sec)?.id ?? sec.id) : null
  }
  if (k.startsWith('issue:')) {
    const end = k.indexOf(':', 6)
    return end > 6 ? k.slice(6, end) : null
  }
  for (const p of ['issues.', 'tables.', 'text.', 'texts.']) {
    if (k.startsWith(p)) {
      const rest = k.slice(p.length)
      const end = rest.indexOf('.')
      return (end < 0 ? rest : rest.slice(0, end)) || null
    }
  }
  return null
}

/**
 * Şablon düzenleyicisindeki bir hata anahtarının bölümü (yoksa null): `section:<b>`, `line:<b>:<i>`, `col:<b>:<s>`,
 * `row:<b>:<s>` doğrudan; `field:<alan>` / `calc:<alan>` alanın, `slot:<yuva>` yuvanın bölümü.
 */
export function templateErrorSection(key, template) {
  const k = String(key || '')
  const colon = k.indexOf(':')
  if (colon < 0) return null
  const kind = k.slice(0, colon)
  const rest = k.slice(colon + 1)
  if (['section', 'line', 'col', 'row'].includes(kind)) {
    const end = rest.indexOf(':')
    return (end < 0 ? rest : rest.slice(0, end)) || null
  }
  const sections = template?.sections || []
  if (kind === 'field' || kind === 'calc' || kind === 'target') return sections.find((s) => (s.fields || []).some((f) => f.id === rest))?.id ?? null
  if (kind === 'slot') return sections.find((s) => (s.slots || []).some((x) => x.id === rest))?.id ?? null
  return null
}

/** Hata anahtarlarını bölümlere sayar: Map(bölüm → hatalı alan sayısı). Değeri boş anahtar sayılmaz. */
export function errorCountsBySection(errors, sectionOf) {
  const out = new Map()
  for (const [k, v] of Object.entries(errors || {})) {
    if (!v) continue
    const sid = sectionOf(k)
    if (sid) out.set(sid, (out.get(sid) || 0) + 1)
  }
  return out
}

/**
 * OTOMATİK kayıtta geçersiz alanlar ESKİ (kayıtlı) değerinde kalır (kullanıcı kararı: "diğerlerini kaydetsin"):
 * istemcinin hata anahtarı taşıyan her değeri `base`'deki (sunucudaki) değerle değiştirilir — alanda yazılı geçersiz
 * metin taslağa hiç girmez, ama taslakta o alanın arada yazılmış son GEÇERLİ değeri olabilir; o da gönderilmez.
 * Değiştirilecek bir şey yoksa aynı nesne döner.
 */
export function revertInvalidValues(values, base, errorKeys) {
  if (!values) return values
  let out = values
  const own = () => {
    if (out === values) out = JSON.parse(JSON.stringify(values))
    return out
  }
  const put = (obj, key, v) => {
    if (v == null) delete obj[key]
    else obj[key] = v
  }
  // Kısaltma üssü değerle birlikte kayıtlı hâline döner (2026-10-18): "4,4G" yazılıp bozulan alan eski yazımıyla gider
  const sameUnit = (uk) => (values.units?.[uk] ?? null) === (base?.units?.[uk] ?? null)
  const revertUnit = (uk) => {
    if (sameUnit(uk)) return
    const o = own()
    o.units = o.units || {}
    put(o.units, uk, base?.units?.[uk] ?? null)
  }
  for (const key of errorKeys || []) {
    const k = String(key || '')
    if (k.startsWith('values.')) {
      const fid = k.slice(7)
      if ((values.fields?.[fid] ?? null) === (base?.fields?.[fid] ?? null) && sameUnit(fid)) continue
      const o = own()
      o.fields = o.fields || {}
      put(o.fields, fid, base?.fields?.[fid] ?? null)
      revertUnit(fid)
    } else if (k.startsWith('targets.')) {
      // Raporun o aya özel hedefi (2026-10-17, 4b): geçersiz yazım kayıtlı hedefte kalır
      const fid = k.slice(8)
      if ((values.targets?.[fid] ?? null) === (base?.targets?.[fid] ?? null)) continue
      const o = own()
      o.targets = o.targets || {}
      put(o.targets, fid, base?.targets?.[fid] ?? null)
    } else if (k.startsWith('issue:')) {
      const parts = k.split(':')
      if (parts.length !== 4) continue
      const [, sid, iid, f] = parts
      const idx = (values.issues?.[sid] || []).findIndex((i) => i?.id === iid)
      if (idx < 0) continue
      const prev = (base?.issues?.[sid] || []).find((i) => i?.id === iid)
      // Birbirine bağlı anomali cevapları (2026-10-18) birlikte döner: tarih + saat aralığı, kesinti + Kesinti ID — yarım
      // geri dönüş (yalnız bitiş saati) sunucunun reddettiği bir bileşim bırakırdı. Adet alanının kısaltma üssü de değerle
      // birlikte kayıtlı hâline döner (2026-10-18: "1,25M" yazılıp bozulan adet eski yazımıyla gider)
      for (const g of ISSUE_REVERT_GROUPS[f] || [f]) {
        const was = prev?.[g] ?? null
        const uk = g === 'customers' || g === 'errors' ? issueUnitKey(sid, iid, g) : null
        if ((values.issues[sid][idx]?.[g] ?? null) === was && (!uk || sameUnit(uk))) continue
        put(own().issues[sid][idx], g, was)
        if (uk) revertUnit(uk)
      }
    } else if (k.startsWith('tables.')) {
      const parts = k.split('.')
      if (parts.length !== 4) continue
      const [, sid, rid, cid] = parts
      const cur = values.tables?.[sid]?.[rid]?.[cid] ?? null
      const was = base?.tables?.[sid]?.[rid]?.[cid] ?? null
      const uk = tableUnitKey(sid, rid, cid)
      if (cur === was && sameUnit(uk)) continue
      const o = own()
      o.tables = o.tables || {}
      o.tables[sid] = o.tables[sid] || {}
      o.tables[sid][rid] = o.tables[sid][rid] || {}
      put(o.tables[sid][rid], cid, was)
      revertUnit(uk)
    }
  }
  return out
}

/** Hatırlanan açık bölümler: `{all: true}` (hepsi — sonradan eklenen bölüm dahil) ya da `{all: false, ids}`. */
export const OPEN_STATE_MAX_IDS = 64

export function parseOpenState(raw) {
  try {
    const v = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (v && v.all === true) return { all: true, ids: [] }
    const ids = Array.isArray(v?.ids)
      ? [...new Set(v.ids.filter((x) => typeof x === 'string' && x && x.length <= 120))].slice(0, OPEN_STATE_MAX_IDS)
      : []
    return { all: false, ids }
  } catch {
    return { all: false, ids: [] }
  }
}

export const isSectionOpen = (state, id) => !!state && (state.all || state.ids.includes(id))

/** Bir bölümü açar / kapatır. "Hepsi açık"ken kapatılan bölüm, o anki bölüm listesinden çıkarılır. */
export function withSectionOpen(state, id, open, allIds) {
  const want = !!open
  if (state.all) return want ? state : { all: false, ids: (allIds || []).filter((x) => x !== id) }
  const has = state.ids.includes(id)
  if (want === has) return state
  return { all: false, ids: want ? [...state.ids, id].slice(-OPEN_STATE_MAX_IDS) : state.ids.filter((x) => x !== id) }
}

/** Verilen bölümleri açar (hata gelen bölümler); değişiklik yoksa aynı nesne. */
export function withSectionsOpened(state, ids) {
  let s = state
  for (const id of ids || []) s = withSectionOpen(s, id, true, [])
  return s
}

/**
 * Verilen bölümleri kapatır ("Tüm konuları kapat" — yalnız o bölümün konuları); değişiklik yoksa aynı nesne. "Hepsi açık"
 * iken `allIds` (o anki tüm kimlikler) açık listeye çevrilir, kapatılanlar çıkar.
 */
export function withSectionsClosed(state, ids, allIds) {
  let s = state
  for (const id of ids || []) s = withSectionOpen(s, id, false, allIds)
  return s
}

// ── Katlanır anomali konuları (2026-10-18, kullanıcı isteği: "Anormal Artış Gösteren Hatalar altında eklenen konu ve başlıklar
// da kendi içinde akordiyon gibi açılıp kapanabilsin") ─────────────────────────────────────────────────────────────────
//
// Konuların açık kümesi bölümlerinkinden AYRI kişisel kayıtta (`sm.te.open.ri.<rapor>` — aynı aile, PERSONAL_PREFIXES
// `sm.te.open.`): bölüm "Tümünü aç"ı konulara dokunmaz, konu kimlikleri bölüm kimlikleriyle çakışamaz ve iki kümenin 64'lük
// tavanı ayrı. Kimlik `<bölüm>:<konu>` (iki kimlikte de ':' yok — TechErrorTemplate.ID) — iki bölümün aynı konu kimliği karışmaz.

/** Konunun açık-küme kimliği: `<bölüm>:<konu>`. */
export function topicOpenId(sectionId, issueId) {
  return `${sectionId}:${issueId}`
}

/**
 * Rapor ekranındaki bir hata anahtarının KONUSU (açık-küme kimliği; yoksa null). İstemci `issue:<b>:<k>:<alan>`, sunucu
 * `issues.<b>.<k>.<alan>` ve eski sunucunun sıra numaralı `issues.<b>.<n>`'i (n → taslaktaki n. konu, `issues` ile çözülür).
 */
export function reportErrorTopic(key, issues) {
  const k = String(key || '')
  if (k.startsWith('issue:')) {
    const parts = k.split(':')
    return parts.length >= 4 && parts[1] && parts[2] ? topicOpenId(parts[1], parts[2]) : null
  }
  if (k.startsWith('issues.')) {
    const parts = k.split('.')
    if (parts.length >= 4 && parts[1] && parts[2]) return topicOpenId(parts[1], parts[2])
    if (parts.length === 3 && parts[1] && /^[1-9][0-9]{0,2}$/.test(parts[2])) {
      const iid = (issues?.[parts[1]] || [])[Number(parts[2]) - 1]?.id
      return iid ? topicOpenId(parts[1], iid) : null
    }
  }
  return null
}

export const allSectionsOpen = (state, ids) => !!state && (state.all || (ids || []).every((id) => state.ids.includes(id)))


// ── Anomali konuları (2026-10-18, kullanıcı isteği) ──────────────────────────────────────────────────────────────────
//
// "Her konu için başlık, NE ZAMAN (gün + saat aralığı), kaç teknik hata, tüm teknik hatalar içindeki payı otomatik, kesinti
// kaynaklı mı (Evet ise Kesinti ID zorunlu), müşteri etkisi var mı — yoksa müşteri etkisi gösterilmesin, isteğe bağlı
// görseller; rapor metinleri bunlara göre üretilsin." Sunucu aynası: TechErrorTemplate.IssueOptions, TechErrorValues.Issue,
// TechErrorIssueShare (pay — doğruluk tablosu TechErrorIssueShareTest.SHARE_TABLE), TechErrorIssueText (giriş metni —
// TechErrorIssueTextTest.LEAD_TABLE). JS testleri iki tabloyu Java kaynağından okur.

/** Konu başına en çok görsel (TechErrorTemplate.MAX_ISSUE_IMAGES). */
export const ISSUE_IMAGES = 2
/** Kesinti ID'si ve görsel açıklamasının en çok uzunluğu (TechErrorValues.MAX_OUTAGE_ID / MAX_CAPTION). */
export const ISSUE_LIMITS = Object.freeze({ outageId: 60, caption: 200, label: 120 })
/** Müşteri etkisi yoksa gösterim (TechErrorTemplate.NO_IMPACT_DISPLAY). */
export const NO_IMPACT_DISPLAY = ['hide_count', 'hide_topic']
/** Varsayılan "tüm teknik hatalar" alanı (TechErrorTemplate.DEFAULT_RATIO_OF). */
export const DEFAULT_RATIO_OF = 'error-count'
/** Soruların varsayılan etiketleri ŞABLON dilinde (TechErrorTemplate.DEFAULT_*_LABEL_*). */
export const ISSUE_OPTION_LABELS = Object.freeze({
  tr: Object.freeze({ time_label: 'Ne zaman?', outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID', impact_label: 'Müşteri etkisi var mı?' }),
  en: Object.freeze({ time_label: 'When?', outage_label: 'Caused by an outage?', outage_id_label: 'Outage ID', impact_label: 'Was there customer impact?' }),
})
/** Şablon JSON'unda soruların anahtarları (yalnız açıkken yazılır). */
export const ISSUE_OPTION_KEYS = ['ask_time', 'time_label', 'ask_outage', 'outage_label', 'outage_id_label', 'ask_impact', 'impact_label',
  'no_impact_display', 'allow_images', 'ratio_of']

/** Bölümün soruları (anahtar yoksa kapalı); etiket boşsa şablon dilinin varsayılanı. */
export function issueOptions(section, lang = 'tr') {
  const s = section || {}
  const d = ISSUE_OPTION_LABELS[lang === 'en' ? 'en' : 'tr']
  const label = (k) => (typeof s[k] === 'string' && s[k].trim() ? s[k].trim() : d[k])
  const askImpact = s.ask_impact === true
  return {
    askTime: s.ask_time === true,
    askOutage: s.ask_outage === true,
    askImpact,
    allowImages: s.allow_images === true,
    ratioOf: typeof s.ratio_of === 'string' && s.ratio_of.trim() ? s.ratio_of.trim() : null,
    noImpactDisplay: askImpact ? (s.no_impact_display === 'hide_topic' ? 'hide_topic' : 'hide_count') : null,
    hideTopic: askImpact && s.no_impact_display === 'hide_topic',
    timeLabel: label('time_label'),
    outageLabel: label('outage_label'),
    outageIdLabel: label('outage_id_label'),
    impactLabel: label('impact_label'),
  }
}

/** Hiçbir soru açık değil: 2026-10-18 öncesi düzen (sayaç satırı, müşteri her zaman sorulur). */
export function issueLegacy(opts) {
  return !opts.askTime && !opts.askOutage && !opts.askImpact && !opts.allowImages && !opts.ratioOf
}

/** Varsayılan sorular (yeni anomali bölümü): hepsi açık, pay "Ay içindeki teknik hata adedi"ne göre. */
export function defaultIssueOptions(lang) {
  const d = ISSUE_OPTION_LABELS[lang === 'en' ? 'en' : 'tr']
  return { ask_time: true, time_label: d.time_label, ask_outage: true, outage_label: d.outage_label, outage_id_label: d.outage_id_label,
    ask_impact: true, impact_label: d.impact_label, no_impact_display: 'hide_count', allow_images: true, ratio_of: DEFAULT_RATIO_OF }
}

/** Konu görselinin yuva kimliği: `iss:<bölüm>:<konu>:<n>` (TechErrorTemplate.issueSlotId). */
export function issueSlotId(sectionId, issueId, n) {
  return `iss:${sectionId}:${issueId}:${n}`
}

/** `iss:<bölüm>:<konu>:<n>` → `{ sectionId, issueId, n }`; biçim tutmuyorsa null. */
export function parseIssueSlot(slotId) {
  const m = /^iss:([a-z0-9][a-z0-9_-]{0,39}):([a-z0-9][a-z0-9_-]{0,39}):([1-9])$/.exec(String(slotId || ''))
  if (!m || Number(m[3]) > ISSUE_IMAGES) return null
  return { sectionId: m[1], issueId: m[2], n: Number(m[3]) }
}

/**
 * Konu görseli hâlâ yaşıyor mu: bölüm şablonda görsele izin veren bir anomali bölümü ve konu bu değerlerde var (kayıttan
 * sonra sunucu sahipsiz görseli siler — istemci listesi de aynı kuralla süzülür). Şablon yuvası her zaman yaşar.
 */
export function issueImageAlive(slotId, template, values) {
  const p = parseIssueSlot(slotId)
  if (!p) return true
  const s = (template?.sections || []).find((x) => x.id === p.sectionId)
  if (!s || s.kind !== 'issues' || !issueOptions(s, template?.lang).allowImages) return false
  return (values?.issues?.[p.sectionId] || []).some((i) => i?.id === p.issueId)
}

/** Dönem düzeyindeki kesinti sorusu (varsayılan şablon "Dönemde kesinti oldu mu?"; sunucu TechErrorOutageSentenceMigration.OUTAGE_FIELD). */
export const OUTAGE_FIELD = 'had-outage'

/**
 * Kesinti cevabı konularla çelişiyor mu (2026-10-18 bütünleştirme incelemesi): dönemin kesinti sorusu ({@link OUTAGE_FIELD},
 * evet/hayır) açıkça "Hayır" iken kesinti sorulan anomali bölümlerindeki "kesinti kaynaklı" işaretli konuların sayısı; çelişki
 * yoksa 0. "Evet" + kesintisiz konular çelişki DEĞİLDİR (kesinti anomali konusu olmadan da yaşanabilir); cevap boşsa, alan
 * yoksa ya da evet/hayır değilse 0. Rapor ikisini de yazıldığı gibi basar — yalnız düzenleyicide uyarı.
 */
export function outageAnswerConflict(template, values) {
  const f = allFields(template).find((x) => x.id === OUTAGE_FIELD)
  if (!f || f.type !== 'yesno' || values?.fields?.[OUTAGE_FIELD] !== false) return 0
  let n = 0
  for (const s of template?.sections || []) {
    if (s.kind !== 'issues' || !issueOptions(s, template?.lang).askOutage) continue
    for (const i of values?.issues?.[s.id] || []) if (i?.outage === true) n++
  }
  return n
}

/** Konu PDF / e-postaya girmeyecek mi ("konuyu rapora alma" + müşteri etkisi Hayır). */
export function issueHidden(section, issue, lang = 'tr') {
  return issueOptions(section, lang).hideTopic && issue?.impact === false
}

/**
 * Konunun payı `{ status, value, missing }` (TechErrorIssueShare.of): OK | MISSING (missing: 'errors' | 'total') | ZERO | OVER
 * | EXEMPT | BAD_REF; pay sorulmuyorsa null. `fields` raporun (hesaplar uygulanmış) değerleri — yuvarlama oranlarla aynı.
 */
export function issueShare(section, issue, template, fields) {
  const ref = issueOptions(section).ratioOf
  if (!ref || !issue) return null
  const res = (status, value = null, missing = null) => ({ status, value, missing })
  const total = allFields(template).find((f) => f.id === ref)
  if (!total || total.type !== 'number') return res('BAD_REF')
  if (fieldExempt(total, fields)) return res('EXEMPT')
  const errors = calcNumber(issue.errors)
  if (errors == null) return res('MISSING', null, 'errors')
  const all = calcNumber(fields?.[ref])
  if (all == null) return res('MISSING', null, 'total')
  if (all === 0) return res('ZERO')
  if (errors > all) return res('OVER')
  const pct = errors / all * 100
  if (!Number.isFinite(pct)) return res('ZERO')
  return res('OK', roundCalc(pct))
}

/** Gün: "12 Eylül 2026" / "12 September 2026"; geçersizse null (TechErrorIssueText.day). */
export function issueDayLabel(iso, lang) {
  if (!isRealDate(iso)) return null
  const [y, m, d] = iso.split('-').map(Number)
  return `${d} ${monthName(m, lang)} ${y}`
}

/** `yyyy-MM-dd` gerçek bir gün mü (31 Şubat değil). */
export function isRealDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''))
  if (!m) return false
  const y = Number(m[1])
  const mo = Number(m[2])
  const d = Number(m[3])
  if (mo < 1 || mo > 12 || d < 1) return false
  return d <= new Date(Date.UTC(y, mo, 0)).getUTCDate()
}

const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/

/** `HH:mm` (saniyeli "14:00:00" kısaltılır); geçersizse null. */
export function issueTime(v) {
  const s = String(v ?? '').trim()
  const x = s.length === 8 && s[5] === ':' ? s.slice(0, 5) : s
  return TIME_RE.test(x) ? x : null
}

/** Saat aralığının kısa gösterimi: "14:00–15:30" / "23:30 – ertesi gün 01:15" (EN "… next day"); aralık yoksa null. */
export function issueRangeLabel(issue, lang) {
  const a = issueTime(issue?.start)
  const b = issueTime(issue?.end)
  if (!a || !b) return null
  if (!issue.end_next_day) return `${a}–${b}`
  return lang === 'en' ? `${a} – ${b} next day` : `${a} – ertesi gün ${b}`
}

function issueWhen(issue, en) {
  const day = issueDayLabel(issue?.date, en ? 'en' : 'tr')
  if (!day) return null
  const a = issueTime(issue?.start)
  const b = issueTime(issue?.end)
  const range = a && b
  if (en) {
    if (!range) return `on ${day}`
    return `on ${day}, ${issue.end_next_day ? `${a} – ${b} the next day` : `${a}–${b}`}`
  }
  if (!range) return `${day} tarihinde`
  return `${day}, ${issue.end_next_day ? `${a} – ertesi gün ${b}` : `${a}–${b}`} arasında`
}

const leadBold = (s) => `**${s}**`

function leadLabel(raw, fallback) {
  const v = neutralValue(raw == null ? '' : String(raw)).trim()
  return v || fallback
}

/**
 * Konunun GİRİŞ METNİ (TechErrorIssueText.lead — PDF / e-posta / ekran aynı): sorulmayan ya da cevaplanmayan bilgi düşer
 * (yarım cümle yok), rakamlar ve Kesinti ID kalın (yıldızlı ID düz), dil şablonun dili. `share` hesaplanan pay ya da null;
 * `resolve` şablon etiketlerinin yer tutucularını çözer.
 */
export function issueLeadText(section, issue, share, lang, resolve = (x) => x, errorsUnit = null, customersUnit = null) {
  if (!section || !issue) return ''
  const en = lang === 'en'
  const o = issueOptions(section, en ? 'en' : 'tr')
  const out = []
  const when = o.askTime ? issueWhen(issue, en) : null
  const errors = calcNumber(issue.errors)
  // Kısaltmayla girilmiş adet (2026-10-18): "1,25M" metinde de "1,25M" (TechErrorIssueText.lead ile aynı)
  const n = errors == null ? null : leadBold(formatCount(errors, en ? 'en' : 'tr', errorsUnit))
  const p = share == null || errors == null ? null : leadBold(formatPercent(share, en ? 'en' : 'tr'))
  let first = null
  if (en) {
    const noun = errors === 1 ? 'technical error' : 'technical errors'
    if (when && n) first = `${when.charAt(0).toUpperCase()}${when.slice(1)}, ${n} ${noun} occurred`
    else if (when) first = `It happened ${when}`
    else if (n) first = `This topic caused ${n} ${noun}`
    if (first && p) first += `; this is ${p} of all technical errors in the period`
  } else {
    if (when && n) first = `${when} ${n} teknik hata alındı`
    else if (when) first = `${when} yaşandı`
    else if (n) first = `Bu konu nedeniyle ${n} teknik hata alındı`
    if (first && p) first += `; bu, dönemdeki tüm teknik hataların ${p} kadarıdır`
  }
  if (first) out.push(`${first}.`)
  if (o.askOutage && (issue.outage === true || issue.outage === false)) {
    if (issue.outage) {
      const raw = typeof issue.outage_id === 'string' && issue.outage_id.trim() ? neutralValue(issue.outage_id.trim()) : null
      const label = leadLabel(resolve(o.outageIdLabel), en ? 'Outage ID' : 'Kesinti ID')
      const ref = raw == null ? '' : ` (${label}: ${raw.includes('*') ? raw : leadBold(raw)})`
      out.push(`${en ? 'It was caused by an outage' : 'Kesinti kaynaklıdır'}${ref}.`)
    } else {
      out.push(en ? 'It was not caused by an outage.' : 'Kesinti kaynaklı değildir.')
    }
  }
  const customers = calcNumber(issue.customers)
  if (o.askImpact) {
    if (issue.impact === true) {
      if (customers != null) {
        const c = leadBold(formatCount(customers, en ? 'en' : 'tr', customersUnit))
        out.push(en ? `Customer impact: ${c} ${customers === 1 ? 'customer' : 'customers'}.` : `Müşteri etkisi: ${c} müşteri.`)
      } else {
        out.push(en ? 'There was customer impact.' : 'Müşteri etkisi var.')
      }
    } else if (issue.impact === false) {
      out.push(en ? 'No customer impact.' : 'Müşteri etkisi yok.')
    }
  } else if (customers != null) {
    const label = leadLabel(resolve(section.customers_label), en ? 'Affected customers' : 'Etkilenen müşteri sayısı')
    out.push(`${label.endsWith('?') ? `${label} ` : `${label}: `}${leadBold(formatCount(customers, en ? 'en' : 'tr', customersUnit))}.`)
  }
  return out.join(' ')
}

/** Raporun ayının ilk ve son günü (`yyyy-MM-dd`) — tarih seçicinin sınırları. */
export function reportMonthBounds(year, month) {
  if (!year || !month) return null
  const last = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const mm = String(month).padStart(2, '0')
  return { first: `${year}-${mm}-01`, last: `${year}-${mm}-${String(last).padStart(2, '0')}` }
}

/**
 * Konunun İSTEMCİ denetimi (sunucu kuralının aynası, TechErrorValues.issues): `{ alan: { key, required, params } }`.
 * `required`: boş bırakılmış zorunlu alan — kayıt / PDF / gönderim denemesinden sonra gösterilir; diğerleri hemen. Otomatik
 * kayıt bu alanları kayıtlı değerinde gönderir (revertInvalidValues). `lang` şablon dili (soruların etiketleri), `uiLang`
 * iletinin dili (ay adı — inceleme 2026-10-18: İngilizce arayüzde Türkçe ay adı çıkıyordu; verilmezse şablon dili).
 */
export function issueProblems(section, issue, period = null, lang = 'tr', uiLang = null) {
  const out = {}
  if (!section || !issue) return out
  const o = issueOptions(section, lang)
  if (o.askTime) {
    const date = issue.date == null || issue.date === '' ? null : String(issue.date)
    if (date && !isRealDate(date)) out.date = { key: 'te.iss.err.dateInvalid', required: false }
    else if (date && period?.year && period?.month) {
      const [y, m] = date.split('-').map(Number)
      if (y !== period.year || m !== period.month) {
        out.date = { key: 'te.iss.err.dateOutsideMonth', required: false, params: [monthName(period.month, uiLang || lang), period.year] }
      }
    }
    const hasStart = issue.start != null && issue.start !== ''
    const hasEnd = issue.end != null && issue.end !== ''
    const a = hasStart ? issueTime(issue.start) : null
    const b = hasEnd ? issueTime(issue.end) : null
    if (hasStart && !a) out.start = { key: 'te.iss.err.timeInvalid', required: false }
    if (hasEnd && !b) out.end = { key: 'te.iss.err.timeInvalid', required: false }
    if (a && !hasEnd) out.end = { key: 'te.iss.err.endRequired', required: true }
    if (b && !hasStart) out.start = { key: 'te.iss.err.startRequired', required: true }
    if (a && b) {
      if (!date && !out.date) out.date = { key: 'te.iss.err.dateRequired', required: true }
      if (!issue.end_next_day && b <= a) out.end = { key: 'te.iss.err.endBeforeStart', required: false }
      else if (issue.end_next_day && b > a) out.end = { key: 'te.iss.err.rangeTooLong', required: false }
    }
  }
  if (o.askOutage && issue.outage === true) {
    const id = typeof issue.outage_id === 'string' ? issue.outage_id.trim() : ''
    if (!id) out.outage_id = { key: 'te.iss.err.outageIdRequired', required: true }
    else if (id.length > ISSUE_LIMITS.outageId) out.outage_id = { key: 'te.iss.err.outageIdTooLong', required: false, params: [ISSUE_LIMITS.outageId] }
  }
  return out
}

/** İstemci hata anahtarı (ReportSections.issueErrorKey ile aynı biçim): `issue:<bölüm>:<konu>:<alan>`. */
export function issueKey(sectionId, issueId, field) {
  return `issue:${sectionId}:${issueId}:${field}`
}

/**
 * Şablondaki bütün konuların denetimi: `{ 'issue:<bölüm>:<konu>:<alan>': { key, required, params } }`. `uiLang`: iletinin dili
 * (ay adı); verilmezse şablon dili.
 */
export function issueValidation(template, issues, period = null, uiLang = null) {
  const out = {}
  for (const s of template?.sections || []) {
    if (s.kind !== 'issues') continue
    for (const i of issues?.[s.id] || []) {
      const p = issueProblems(s, i, period, template?.lang, uiLang)
      for (const [f, v] of Object.entries(p)) out[issueKey(s.id, i.id, f)] = v
    }
  }
  return out
}

/**
 * Konunun bölümün sorularına UYGUN kanonik hâli (kaydedilmemiş değişiklik denetimi + gönderim): sorulmayan cevap düşer,
 * müşteri sayısı yalnız etki Evet'te, Kesinti ID yalnız kesinti Evet'te, "ertesi gün" yalnız saat aralığıyla; sunucunun
 * hesapladığı pay (`share`) hiç gönderilmez. Sunucu da aynı kuralla saklar — gizli bir değer kayıtta "değişiklik" sayılmaz.
 */
export function conformIssue(section, issue) {
  if (!issue || typeof issue !== 'object') return issue
  const o = issueOptions(section)
  const x = { ...issue }
  delete x.share
  if (!o.askTime) { delete x.date; delete x.start; delete x.end; delete x.end_next_day }
  else if (!(x.start && x.end)) delete x.end_next_day
  if (x.end_next_day === false) delete x.end_next_day
  if (!o.askOutage) { delete x.outage; delete x.outage_id }
  else if (x.outage !== true) delete x.outage_id
  if (!o.askImpact) delete x.impact
  else if (x.impact !== true) delete x.customers
  if (!o.allowImages) delete x.captions
  else if (x.captions && typeof x.captions === 'object') {
    const c = {}
    for (const [k, v] of Object.entries(x.captions)) if (typeof v === 'string' && v.trim()) c[k] = v
    if (Object.keys(c).length) x.captions = c
    else delete x.captions
  }
  return x
}

/** Otomatik kayıtta geçersiz bir alanla birlikte kayıtlı değerine dönen alanlar (birbirine bağlı cevaplar). */
export const ISSUE_REVERT_GROUPS = Object.freeze({
  date: ['date', 'start', 'end', 'end_next_day'],
  start: ['date', 'start', 'end', 'end_next_day'],
  end: ['date', 'start', 'end', 'end_next_day'],
  outage: ['outage', 'outage_id'],
  outage_id: ['outage', 'outage_id'],
})

/**
 * Anomali sorularının kanonik hâli (yerinde): sunucu yalnız AÇIK soruyu ve etiketini yazar — kapalı sorunun `false`
 * anahtarı, etiketi ve "müşteri etkisi yoksa" gösterimi yoklukla aynı sayılır (sahte "kaydedilmemiş değişiklik" olmasın).
 */
export function canonIssueOptions(o) {
  if (o.ask_time !== true) { delete o.ask_time; delete o.time_label }
  if (o.ask_outage !== true) { delete o.ask_outage; delete o.outage_label; delete o.outage_id_label }
  if (o.ask_impact !== true) { delete o.ask_impact; delete o.impact_label; delete o.no_impact_display }
  else if (o.no_impact_display == null || o.no_impact_display === '') o.no_impact_display = 'hide_count'
  if (o.allow_images !== true) delete o.allow_images
  if (o.ratio_of == null || o.ratio_of === '') delete o.ratio_of
  for (const k of ['time_label', 'outage_label', 'outage_id_label', 'impact_label']) {
    if (typeof o[k] === 'string' && !o[k].trim()) delete o[k]
  }
  return o
}
