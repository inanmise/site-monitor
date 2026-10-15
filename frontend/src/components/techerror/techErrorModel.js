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

export const KINDS = ['metrics', 'issues', 'text', 'images', 'summary', 'table']
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
 * Kullanıcının yazdığı sayıyı çözer: "1.250.000", "1,250,000", "0,42", "0.42", "%3,5", "3.5%". Belirsiz TEK ayraç
 * ("1.250") arayüz dilinin alışkanlığına göre okunur (TR: binlik, EN: ondalık) — ama yalnız sıfırla başlamayan bir
 * binlik grubuysa: "0.125" her dilde 0,125'tir (inceleme 2026-10-13: 125 okunuyordu). Yüzde alanında tek ayraç her
 * zaman ondalıktır ("%3.500" = 3,5). Çok ayraçlı yazım geçerli binlik gruplaması olmalı ("1.25.0" geçersiz).
 * Boş → null; geçersiz → NaN.
 */
export function parseNumber(raw, lang, { percent = false } = {}) {
  if (raw == null) return null
  let s = String(raw).trim().replace(/\s+/g, '').replace(/%/g, '')
  if (s === '') return null
  if (s.length > 40) return NaN
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
      normalized = !percent && habit && thousands(s, sep) ? s.split(sep).join('') : s.replace(sep, '.')
    }
  } else {
    normalized = s
  }
  if (!/^\d*\.?\d*$/.test(normalized) || normalized === '.' ) return NaN
  const n = Number(normalized)
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
  return /^[+-]?\d{1,3}([.,]\d{3})*[.,]\d{0,2}$/.test(s) && Number.isNaN(parseNumber(s, 'tr')) && Number.isNaN(parseNumber(s, 'en'))
}

/** Sunucudaki TechErrorFormat ile aynı biçim: TR 1.250.000 / %0,42; EN 1,250,000 / 0.42%; en çok 4 ondalık. */
export function formatNumber(n, lang) {
  if (n == null || !Number.isFinite(Number(n))) return '—'
  return new Intl.NumberFormat(lang === 'en' ? 'en-US' : 'tr-TR', { maximumFractionDigits: 4 }).format(Number(n))
}

export function formatPercent(n, lang) {
  if (n == null || !Number.isFinite(Number(n))) return '—'
  const v = formatNumber(n, lang)
  return lang === 'en' ? `${v}%` : `%${v}`
}

export function formatValue(field, value, lang, yesNo) {
  if (value == null || value === '') return '—'
  switch (field?.type) {
    case 'number': return formatNumber(value, lang)
    case 'percent': return formatPercent(value, lang)
    case 'yesno': return yesNo(!!value)
    default: return String(value)
  }
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
export function summaryRefs(line) {
  const out = []
  if (line == null) return out
  const str = String(line)
  let i = str.indexOf('{f:')
  while (i >= 0 && out.length < MAX_REFS) {
    const close = str.indexOf('}', i + 3)
    if (close < 0) break
    const body = str.slice(i + 3, close)
    // İç içe süslü parantez okunamaz (ilk "}"de biterdi, bozuk basılırdı): başvuru sayılmaz → summaryMalformed (sunucu)
    if (body.includes('{')) {
      i = str.indexOf('{f:', close + 1)
      continue
    }
    const q = body.indexOf('?')
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
    if (ID_RE.test(id)) out.push({ id, yes, no, start: i, end: close + 1 })
    i = str.indexOf('{f:', close + 1)
  }
  return out
}

/** Kalıpta çözülemeyen bir `{f:` var mı (kapanmamış / geçersiz kimlik)? Sunucu kaydı reddeder. */
export function summaryMalformed(line) {
  if (line == null) return false
  const str = String(line)
  let count = 0
  for (let i = str.indexOf('{f:'); i >= 0; i = str.indexOf('{f:', i + 3)) count++
  return count !== summaryRefs(str).length
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
export function summaryLine(pattern, byId, fields, lang, resolveLiteral) {
  if (pattern == null || richBlank(pattern)) return null
  const str = String(pattern)
  let out = ''
  let pos = 0
  for (const r of summaryRefs(str)) {
    out += resolveLiteral(str.slice(pos, r.start))
    const f = byId.get(r.id)
    if (!f || f.type === 'textarea' || fieldExempt(f, fields)) return null
    const v = summaryValue(f, fields?.[f.id])
    if (v == null) return null
    if (r.yes != null) {
      if (typeof v !== 'boolean') return null
      out += resolveLiteral(v ? r.yes : r.no)
    } else if (f.type === 'number' || f.type === 'percent') {
      out += `**${formatValue(f, v, lang, summaryYesNo(lang))}**`
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
export function summaryAutoText(section, template, fields, channel, year, month) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const lang = template?.lang
  const lit = (x) => resolveTextPlaceholders(x, channel, year, month, lang)
  let out = ''
  let inParagraph = false
  for (const line of section?.lines || []) {
    // Sınır aşıldıysa sonraki cümleler üretilmez — sonuç zaten sınırda kesilir (sunucu ile aynı)
    if (out.length > MAX_RESOLVED_TEXT) break
    let r = summaryLine(line, byId, fields, lang, lit)
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
export function summaryEffective(section, template, values, channel, year, month) {
  const override = values?.texts?.[section?.id]
  if (override != null && !richBlank(override)) return resolveTextPlaceholders(override, channel, year, month, template?.lang)
  return summaryAutoText(section, template, values?.fields || {}, channel, year, month)
}

/**
 * Özet cümlelerinden hangileri ŞU AN özete girmiyor ve neden: `missing` = dayandığı rakam girilmemiş (alan adları, her
 * alan bir kez), `exempt` = "Hayır" cevabıyla muaf rakama dayanıyor, `broken` = kullanılamayan alana bağlı; `reason`:
 * `removed` (şablonda yok), `notYesNo` (seçim evet/hayır olmayan alanda), `textarea` (uzun açıklama alanı).
 */
export function summaryLineStatus(section, template, fields) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  return (section?.lines || []).map((line, index) => {
    const missing = []
    const seen = new Set()
    let exempt = false
    let reason = null
    for (const r of summaryRefs(line)) {
      const f = byId.get(r.id)
      if (!f) { reason = reason || 'removed'; continue }
      if (r.yes != null && f.type !== 'yesno') { reason = reason || 'notYesNo'; continue }
      if (f.type === 'textarea') { reason = reason || 'textarea'; continue }
      if (fieldExempt(f, fields)) { exempt = true; continue }
      if (summaryValue(f, fields?.[f.id]) == null && !seen.has(f.id)) {
        seen.add(f.id)
        missing.push(f)
      }
    }
    const state = reason ? 'broken' : exempt ? 'exempt' : missing.length ? 'missing' : 'ok'
    return { index, line, state, missing, reason }
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
  }
  return out
}

/**
 * Cümle kalıbının okunur hâli: `{f:id}` → `[Alan adı]`, `{f:id?a|b}` → `[a / b]` (düzenleyici önizlemesi). Şablonda
 * olmayan alan `removedLabel` ile gösterilir (kimlik kullanıcıya gösterilmez; verilmezse kimlik). Alan adları `resolve`
 * ile, satırın tamamı `resolveLine` ile çözülür — başlık sınırlı (300) çözücü 500 karakterlik cümleyi "…" ile kesiyordu.
 */
export function summaryLinePreview(line, template, resolve = (x) => x, removedLabel = null, resolveLine = resolve) {
  const byId = new Map(allFields(template).map((f) => [f.id, f]))
  const str = String(line || '')
  let out = ''
  let pos = 0
  for (const r of summaryRefs(str)) {
    out += str.slice(pos, r.start)
    const f = byId.get(r.id)
    if (r.yes != null) out += `[${r.yes} / ${r.no}]`
    else out += `[${f ? resolve(f.label) : (removedLabel ?? r.id)}]`
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
      const ok = target && f.skip_if_no !== f.id && target.type === 'yesno' && !target.skip_if_no
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
  if (kind === 'metrics') return { ...base, fields: [{ id: newId('f', template), label: labels.field, type: 'number' }] }
  if (kind === 'images') return { ...base, slots: [{ id: newId('g', template), label: labels.slot, width: 'half' }] }
  if (kind === 'issues') {
    return { ...base, item_title: labels.itemTitle, description_label: labels.descriptionLabel,
      customers_label: labels.customersLabel, errors_label: labels.errorsLabel }
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
      for (const f of s.fields || []) if (!(f.label || '').trim()) errs[`field:${f.id}`] = 'te.err.fieldLabelRequired'
    }
    if (s.kind === 'images') {
      if (!(s.slots || []).length) errs[`section:${s.id}`] = 'te.err.noSlots'
      for (const sl of s.slots || []) if (!(sl.label || '').trim()) errs[`slot:${sl.id}`] = 'te.err.slotLabelRequired'
    }
    if (s.kind === 'summary') {
      const lines = (s.lines || []).filter((l) => String(l || '').trim())
      if (!lines.length) errs[`section:${s.id}`] = 'te.err.noLines'
      if ((s.lines || []).length > LIMITS.summaryLines) errs[`section:${s.id}`] = 'te.err.tooManyLines'
      ;(s.lines || []).forEach((l, i) => {
        // Sunucu gibi ölçülür (TechErrorTemplate.line: çift boşluk teke iner) — sunucunun kabul ettiği cümle reddedilmez
        if (shortTextValue(l).length > LIMITS.summaryLine) errs[`line:${s.id}:${i}`] = 'te.err.lineTooLong'
        else if (summaryMalformed(l)) errs[`line:${s.id}:${i}`] = 'te.err.lineMalformed'
      })
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

export function newIssue(list) {
  const used = new Set((list || []).map((i) => i.id))
  let n = (list || []).length + 1
  while (used.has(`i-${n}`)) n++
  return { id: `i-${n}`, topic: '', title: '', title_auto: true, description: '', customers: null, errors: null }
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
    issues[k] = list.map((i) => Object.fromEntries(Object.entries(i || {}).filter(([, x]) => x != null)))
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
  const out = { fields, issues, texts }
  if (Object.keys(tables).length) out.tables = tables
  return out
}

/** Şablonun karşılaştırma hâli: boş isteğe bağlı metinler (ipucu vb.) "yok" ile aynı sayılır. */
export function canonicalTemplate(tpl) {
  const strip = (o) => Object.fromEntries(Object.entries(o || {}).filter(([, x]) => x != null && x !== ''))
  if (!tpl) return tpl
  return {
    ...strip({ lang: tpl.lang, title: tpl.title }),
    sections: (tpl.sections || []).map((s) => {
      const out = strip({ ...s, fields: undefined, slots: undefined, columns: undefined, rows: undefined, lines: undefined })
      if (Array.isArray(s.fields)) out.fields = s.fields.map(strip)
      if (Array.isArray(s.slots)) out.slots = s.slots.map(strip)
      if (Array.isArray(s.lines)) out.lines = s.lines.slice()
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

/** İstemci ön denetimi (sunucu baytlardan yine doğrular): tür ve boyut. Hata → i18n anahtarı, yoksa null. */
export function preflightImage(file) {
  if (!file) return 'te.img.errNone'
  const okType = file.type === 'image/png' || file.type === 'image/jpeg' || /\.(png|jpe?g)$/i.test(file.name || '')
  if (!okType) return 'te.img.errType'
  if (file.size > LIMITS.imageBytes) return 'te.img.errSize'
  if (file.size === 0) return 'te.img.errEmpty'
  return null
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
    if (s.kind === 'metrics') itemsDiff(out, 'field', section, p.fields, s.fields, moves.field, (a, b) => {
      const r = []
      if ((a.type || '') !== (b.type || '')) r.push({ op: 'retype', before: a.type, after: b.type })
      if ((a.skip_if_no || '') !== (b.skip_if_no || '')) r.push({ op: 'link' })
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
    }
    if (s.kind === 'text' && (p.text || '') !== (s.text || '')) out.push({ group: 'text', op: 'change', label: section })
    if (s.kind === 'issues') {
      const keys = ['item_title', 'description_label', 'customers_label', 'errors_label']
      if (keys.some((k) => (p[k] || '') !== (s[k] || ''))) out.push({ group: 'section', op: 'labels', label: section })
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
