/**
 * KANAL NOTLARI — saf model (2026-11-20, kullanıcı isteği: "her kanala, o kanalın verisini nereden / nasıl aldığımızı
 * anlatan zengin metinli, görselli bir not"). React, ağ ve depolama DIŞINDA hiçbir yan etki yok (yedek yardımcıları
 * hariç — onlar yalnız localStorage'a dokunur ve hiçbir zaman fırlatmaz).
 *
 * Sunucuyla ortak kurallar:
 *  - Görsel başvurusu `te-note-img:<kimlik>` — 1–18 ASCII rakam, rakam dizisinin ardından başka rakam gelmez (19+ rakam
 *    başvuru DEĞİL). Tarama DOĞRUSAL (indexOf döngüsü; kullanıcı metninde regex yok). Java ikizi
 *    `TechErrorNoteRefs.ids` — tek doğruluk tablosu `TechErrorNoteRefsTest.REF_TABLE` (JS testi Java kaynağından okur).
 *  - Gövde normalleştirme: CRLF / CR → LF, NUL ve eşleşmemiş vekil (surrogate) atılır; uzunluk UTF-16 birimi.
 */

/** Görsel başvurusunun öneki (kalıcı). */
export const NOTE_REF_PREFIX = 'te-note-img:'
/** Bir başvurudaki en çok rakam (Java `long` güvenli sınırı). */
export const NOTE_REF_MAX_DIGITS = 18

/** Sunucu `limits` göndermediğinde kullanılan değerler (sözleşmedeki sabitler). */
export const NOTE_LIMITS = Object.freeze({
  max_chars: 60000,
  max_images: 40,
  max_image_bytes: 5242880,
  max_total_bytes: 41943040,
  unlinked_keep_days: 30,
  max_versions: 20,
})

/** Yerel taslak yedeğinin anahtar öneki — kişisel kayıt ailesi `sm.te.draft.` (çıkışta / kullanıcı değişince silinir). */
export const NOTE_BACKUP_PREFIX = 'sm.te.draft.note.'
/** Görselin alt metni en çok bu kadar karakter. */
export const NOTE_ALT_MAX = 80

const isDigit = (code) => code >= 48 && code <= 57

/**
 * Gövdedeki görsel başvurularının kimlikleri — ilk görülme sırasıyla, yinelenmeden, KANONİK ondalık dizgi olarak
 * (baştaki sıfırlar atılır: "007" → "7"). 18 haneli kimlikler de kayıpsız kalsın diye dizgi; sayı isteyen
 * {@link noteImageRefs} kullanır. Doğrusal: her konum en çok bir kez okunur.
 */
export function noteImageRefIds(body) {
  const text = typeof body === 'string' ? body : ''
  const out = []
  const seen = new Set()
  let from = 0
  while (from <= text.length) {
    const at = text.indexOf(NOTE_REF_PREFIX, from)
    if (at < 0) break
    const start = at + NOTE_REF_PREFIX.length
    let end = start
    while (end < text.length && isDigit(text.charCodeAt(end))) end++
    const len = end - start
    if (len >= 1 && len <= NOTE_REF_MAX_DIGITS) {
      let k = start
      while (k < end - 1 && text.charCodeAt(k) === 48) k++
      const id = text.slice(k, end)
      if (!seen.has(id)) { seen.add(id); out.push(id) }
    }
    // Rakam dizisinin sonundan sürer (dizi önek içeremez) — hiçbir konum iki kez taranmaz
    from = end > start ? end : start
  }
  return out
}

/**
 * Gövdedeki başvuru kimlikleri (ilk görülme sırası, yinelenmez) — KANONİK DİZGİ olarak: 18 haneli kimlik 2^53'ü aşabilir,
 * sayıya çevrilse kayıpla yuvarlanır ve eşleşmez. Sunucunun sayı kimlikleriyle karşılaştırırken `String(id)` kullanın
 * (bkz. {@link imagesById}). {@link noteImageRefIds} ile aynıdır.
 */
export function noteImageRefs(body) {
  return noteImageRefIds(body)
}

/**
 * Tek bir adres TAM olarak bir not görseli başvurusu mu (`te-note-img:<1–18 rakam>`, başka hiçbir şey yok)?
 * Öyleyse kanonik kimlik dizgisi, değilse null. Görüntüleyici yalnız bununla karar verir.
 */
export function noteImageIdOf(url) {
  if (typeof url !== 'string' || !url.startsWith(NOTE_REF_PREFIX)) return null
  const len = url.length - NOTE_REF_PREFIX.length
  if (len < 1 || len > NOTE_REF_MAX_DIGITS) return null
  for (let i = NOTE_REF_PREFIX.length; i < url.length; i++) if (!isDigit(url.charCodeAt(i))) return null
  let k = NOTE_REF_PREFIX.length
  while (k < url.length - 1 && url.charCodeAt(k) === 48) k++
  return url.slice(k)
}

// Normalleştirme gerektiren bir karakter var mı (tek karakter sınıfı — doğrusal)
const NEEDS_NORMALIZE = /[\r\0\uD800-\uDFFF]/

/**
 * Sunucunun kayıtta yaptığı normalleştirme: CRLF / CR → LF, NUL atılır, eşleşmemiş vekil (lone surrogate) atılır.
 * Geçerli vekil çiftleri (emoji vb.) aynen kalır. Dizgi olmayan değer boş metindir.
 */
export function normalizeNoteBody(body) {
  if (typeof body !== 'string') return ''
  if (!NEEDS_NORMALIZE.test(body)) return body
  const parts = []
  let chunkStart = 0
  const flush = (i) => { if (i > chunkStart) parts.push(body.slice(chunkStart, i)) }
  for (let i = 0; i < body.length; i++) {
    const c = body.charCodeAt(i)
    if (c === 13) {
      flush(i)
      parts.push('\n')
      if (body.charCodeAt(i + 1) === 10) i++
      chunkStart = i + 1
    } else if (c === 0) {
      flush(i)
      chunkStart = i + 1
    } else if (c >= 0xD800 && c <= 0xDBFF) {
      const next = body.charCodeAt(i + 1)
      if (next >= 0xDC00 && next <= 0xDFFF) i++   // geçerli çift: ikisi de kalır
      else { flush(i); chunkStart = i + 1 }
    } else if (c >= 0xDC00 && c <= 0xDFFF) {
      flush(i)
      chunkStart = i + 1
    }
  }
  flush(body.length)
  return parts.join('')
}

/** Kaydedilecek metnin uzunluğu (UTF-16 birimi; sunucunun saydığı). */
export function noteCharCount(body) {
  return normalizeNoteBody(body).length
}

const positive = (v, fallback) => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : fallback)

/** Sunucunun `limits` nesnesi + eksik / bozuk alanlar için sözleşme varsayılanları. */
export function limitsOf(data) {
  const l = data && typeof data.limits === 'object' && data.limits ? data.limits : {}
  const out = {}
  for (const k of Object.keys(NOTE_LIMITS)) out[k] = positive(l[k], NOTE_LIMITS[k])
  return out
}

/** Metin sınırı aşıldı mı (kayıt kapalı, satır içi uyarı). */
export function noteTooLong(body, limits = NOTE_LIMITS) {
  return noteCharCount(body) > positive(limits?.max_chars, NOTE_LIMITS.max_chars)
}

/** Taslak kayıtlı metinden farklı mı (ikisi de normalleştirilmiş karşılaştırılır). */
export function noteDirty(draft, savedBody) {
  return normalizeNoteBody(draft) !== normalizeNoteBody(savedBody)
}

/**
 * İmlece KENDİ satırında (önünde ve ardında boş satırla ayrılmış) bir parça ekler. `{ body, caret }` döner — imleç
 * eklenen parçanın ardına gelir. İmleç bilinmiyorsa / sınır dışıysa metnin sonuna eklenir.
 */
export function insertAtCaret(body, caret, snippet) {
  const text = typeof body === 'string' ? body : ''
  const piece = typeof snippet === 'string' ? snippet : ''
  let at = Number.isFinite(caret) ? Math.trunc(caret) : text.length
  if (at < 0) at = 0
  if (at > text.length) at = text.length
  const before = text.slice(0, at)
  const after = text.slice(at)
  const lead = before === '' || before.endsWith('\n\n') ? '' : before.endsWith('\n') ? '\n' : '\n\n'
  const trail = after === '' ? '\n' : after.startsWith('\n\n') ? '' : after.startsWith('\n') ? '\n' : '\n\n'
  const head = before + lead + piece + trail
  return { body: head + after, caret: head.length }
}

/** Dosya adının uzantısız hâli ("ekran.goruntusu.png" → "ekran.goruntusu"). */
export function fileBaseName(fileName) {
  const name = typeof fileName === 'string' ? fileName.trim() : ''
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(0, dot) : name
}

/**
 * Markdown alt metni: köşeli parantez, ters bölü, ters tırnak, açılı parantez, yıldız ve satır sonları boşluğa döner
 * (alt metin markdown'ı bozamaz); art arda boşluk teke iner; en çok {@link NOTE_ALT_MAX} karakter. Boş kalırsa `fallback`.
 */
export function noteImageAlt(fileName, fallback = '') {
  const base = fileBaseName(fileName)
  let out = ''
  let space = false
  for (let i = 0; i < base.length && out.length < NOTE_ALT_MAX; i++) {
    const ch = base[i]
    const c = base.charCodeAt(i)
    const bad = c < 32 || c === 127 || ch === '[' || ch === ']' || ch === '\\' || ch === '`' || ch === '<' || ch === '>' || ch === '*'
      || (c >= 0xD800 && c <= 0xDFFF)
    if (bad || ch === ' ') {
      if (out && !space) { out += ' '; space = true }
    } else {
      out += ch
      space = false
    }
  }
  out = out.trim()
  return out || fallback
}

/** Bir görselin metne eklenen markdown'ı: `![<uzantısız dosya adı>](te-note-img:<kimlik>)`. */
export function noteImageMarkdown(id, fileName, fallbackAlt = '') {
  return `![${noteImageAlt(fileName, fallbackAlt)}](${NOTE_REF_PREFIX}${id})`
}

/** Görsel listesi → kimlik (dizgi) → görsel haritası. Bozuk satırlar atlanır. */
export function imagesById(images) {
  const map = new Map()
  for (const img of Array.isArray(images) ? images : []) {
    if (img && img.id != null) map.set(String(img.id), img)
  }
  return map
}

/**
 * Görsel TASLAK metinde kullanılıyor mu? (Sunucunun `linked` alanı KAYITLI metne göredir; taslakta eklenip henüz
 * kaydedilmemiş başvuru da "kullanılıyor" sayılır — kullanıcı az önce eklediği görseli "kullanılmıyor" görmesin.)
 */
export function imageUsedInDraft(image, draftRefIds) {
  return !!image && draftRefIds instanceof Set && draftRefIds.has(String(image.id))
}

// ── Yerel taslak yedeği ─────────────────────────────────────────────────────────────────────────────────────────────

export const noteBackupKey = (channelId) => `${NOTE_BACKUP_PREFIX}${channelId}`

/** `{ version, body, at }` ya da null (yok / okunamadı / biçimi bozuk). */
export function readNoteBackup(channelId) {
  try {
    const raw = localStorage.getItem(noteBackupKey(channelId))
    if (!raw) return null
    const v = JSON.parse(raw)
    if (!v || typeof v !== 'object' || typeof v.body !== 'string') return null
    return { version: Number.isFinite(v.version) ? v.version : null, body: v.body, at: Number.isFinite(v.at) ? v.at : null }
  } catch {
    return null
  }
}

export function writeNoteBackup(channelId, { version, body }) {
  try {
    localStorage.setItem(noteBackupKey(channelId), JSON.stringify({ version: version ?? null, body: String(body ?? ''), at: Date.now() }))
    return true
  } catch {
    return false   // depolama yok / dolu
  }
}

export function dropNoteBackup(channelId) {
  try { localStorage.removeItem(noteBackupKey(channelId)) } catch { /* depolama yok */ }
}

// ── Sunucu yanıtlarının sınıflandırılması ──────────────────────────────────────────────────────────────────────────

/** Sunucunun kendi (kodlu) "bulunamadı" yanıtları — eski pod'un genel 404'ünden ayrılır. */
const CODED_NOT_FOUND = new Set(['CHANNEL_NOT_FOUND', 'NOTE_VERSION_NOT_FOUND', 'NOTE_IMAGE_NOT_FOUND'])

/**
 * Bu sürümü bilmeyen (eski) pod'un yanıtı mı: 405 ya da kanal-notu kodu taşımayan 404 (bilinmeyen `/api` yolu = JSON 404
 * `NOT_FOUND` ya da kodsuz). Sürüm geçişinde istek eski pod'a düşebilir — "geçici olarak kullanılamıyor" gösterilir.
 */
export function isOldServerAnswer(res) {
  if (!res || res.success) return false
  if (res.status === 405) return true
  return res.status === 404 && !CODED_NOT_FOUND.has(res.code)
}

/** Kanal bulunamadı / erişim kalktı (kodlu 404). */
export function isChannelGone(res) {
  return !!res && !res.success && res.status === 404 && res.code === 'CHANNEL_NOT_FOUND'
}

/** Kayıt çakışması (başkası arada kaydetti). */
export function isNoteConflict(res) {
  return !!res && !res.success && (res.code === 'CONCURRENT_MODIFICATION' || (res.status === 409 && !res.code))
}

/** Çakışma yanıtındaki "kim / ne zaman / hangi sürüm" — eksik alan null. */
export function conflictInfoOf(res) {
  const src = res && typeof res === 'object' ? res : {}
  const pick = (k) => (src[k] != null ? src[k] : (src.data && src.data[k] != null ? src.data[k] : null))
  const v = pick('current_version')
  return {
    currentVersion: typeof v === 'number' && Number.isFinite(v) ? v : null,
    by: typeof pick('updated_by_name') === 'string' && pick('updated_by_name').trim() ? pick('updated_by_name').trim() : null,
    at: typeof pick('updated_at') === 'string' && pick('updated_at') ? pick('updated_at') : null,
  }
}

/**
 * Not okuma yanıtının durumu:
 *  - `loading` (yanıt yok, istek sürüyor) · `ok` (metni olan not) · `empty` (not yok ya da metni boş)
 *  - `hidden` (403 — yalnız görüntüleme paylaşımı; notlar paylaşılmaz) · `notFound` (kodlu 404 `CHANNEL_NOT_FOUND`)
 *  - `unsupported` (eski pod: kodsuz / genel 404 ya da 405) · `failed` (her şey: ağ, 5xx, bozuk yanıt)
 * Okunamayan hiçbir durum "not yok" sayılmaz.
 */
export function noteState(res, { loading = false } = {}) {
  if (res === undefined || (res == null && loading)) return 'loading'
  if (!res) return 'failed'
  if (res.success) {
    const d = res.data
    // Nesne olmayan / dizi gövde bozuk yanıttır ("not yok" değil)
    if (!d || typeof d !== 'object' || Array.isArray(d) || typeof (d.body ?? '') !== 'string') return 'failed'
    return d.exists && String(d.body ?? '').trim() !== '' ? 'ok' : 'empty'
  }
  if (res.status === 403) return 'hidden'
  if (isChannelGone(res)) return 'notFound'
  if (isOldServerAnswer(res)) return 'unsupported'
  return 'failed'
}

/**
 * Rapor / rapor listesi yanıtındaki `channel_notes` özetinin kart durumu:
 *  - `none` (anahtar yok ya da `readable !== true` → kart HİÇ çizilmez)
 *  - `unknown` (`unavailable` — okunamadı; "not yok" denmez) · `note` (metni olan not var) · `empty` (henüz not yok)
 */
export function noteCardState(meta) {
  if (!meta || typeof meta !== 'object' || meta.readable !== true) return 'none'
  if (meta.unavailable) return 'unknown'
  return meta.exists && !meta.empty ? 'note' : 'empty'
}

/** İki `channel_notes` özeti aynı şeyi mi söylüyor (kart yeniden çizilmesin diye değerle karşılaştırma). */
export function sameNoteMeta(a, b) {
  if (a === b) return true
  if (!a || !b) return !a && !b
  return a.readable === b.readable && !!a.unavailable === !!b.unavailable && !!a.exists === !!b.exists
    && !!a.empty === !!b.empty && (a.updated_at ?? null) === (b.updated_at ?? null)
    && (a.updated_by_name ?? null) === (b.updated_by_name ?? null) && (a.image_count ?? null) === (b.image_count ?? null)
}

/**
 * Yükleme / silme reddinin kullanıcıya gösterilecek i18n anahtarı (sunucu metni yoksa); bilinmeyen kod → null.
 * `seconds` (hız sınırı bekleme süresi) 1 ise tekil biçim (`….one`, projenin çoğul deseni).
 */
export function noteImageRefusalKey(res, seconds = null) {
  switch (res?.code) {
    case 'NOTE_IMAGE_LIMIT': return 'te.note.img.limitCount'
    case 'NOTE_IMAGE_TOTAL_LIMIT': return 'te.note.img.limitTotal'
    case 'NOTE_IMAGE_IN_USE': return 'te.note.img.inUse'
    case 'RATE_LIMITED': return Number(seconds) === 1 ? 'te.note.img.rateLimited.one' : 'te.note.img.rateLimited'
    default: return null
  }
}

/** Sürüm satırının sayımları: `[karakter metni, görsel metni]` için i18n anahtarları (1 → `….one`). */
export function versionCountKeys(chars, images) {
  return [Number(chars) === 1 ? 'te.note.ver.chars.one' : 'te.note.ver.chars',
    Number(images) === 1 ? 'te.note.ver.images.one' : 'te.note.ver.images']
}

/** 429 yanıtının önerdiği bekleme (saniye) — `retry_after_seconds` / `retryAfter`; yoksa null. */
export function retryAfterSeconds(res) {
  const v = res?.retry_after_seconds ?? res?.retryAfter ?? null
  const n = typeof v === 'string' ? Number(v) : v
  return typeof n === 'number' && Number.isFinite(n) && n > 0 ? Math.ceil(n) : null
}
