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

export const KINDS = ['metrics', 'issues', 'text', 'images']
export const FIELD_TYPES = ['number', 'percent', 'yesno', 'text', 'textarea']
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
  }
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
export function canonicalValues(v) {
  const fields = {}
  for (const [k, x] of Object.entries(v?.fields || {})) {
    if (x == null) continue
    if (typeof x === 'string' && x.trim() === '') continue
    fields[k] = x
  }
  const issues = {}
  for (const [k, list] of Object.entries(v?.issues || {})) {
    if (!Array.isArray(list) || list.length === 0) continue
    issues[k] = list.map((i) => Object.fromEntries(Object.entries(i || {}).filter(([, x]) => x != null)))
  }
  return { fields, issues, texts: { ...(v?.texts || {}) } }
}

/** Şablonun karşılaştırma hâli: boş isteğe bağlı metinler (ipucu vb.) "yok" ile aynı sayılır. */
export function canonicalTemplate(tpl) {
  const strip = (o) => Object.fromEntries(Object.entries(o || {}).filter(([, x]) => x != null && x !== ''))
  if (!tpl) return tpl
  return {
    ...strip({ lang: tpl.lang, title: tpl.title }),
    sections: (tpl.sections || []).map((s) => {
      const out = strip({ ...s, fields: undefined, slots: undefined })
      if (Array.isArray(s.fields)) out.fields = s.fields.map(strip)
      if (Array.isArray(s.slots)) out.slots = s.slots.map(strip)
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
