/**
 * Anahtar kelime izlemesinin İÇERİK KONTROLLERİ modeli (kw paket D — sayfa boyutu aralığı + içerik değişimi). Saf.
 *
 * <p>Form alanları (fe1 `keywordFormModel` ile aynı adlar): `minBytes`, `maxBytes` (bayt; null = kapalı; yazım hatasında
 * ham metin kalır ki doğrulama söylesin), `changeDetect` (bool), `changeMask` (dizi ⊆ DIGITS, DATES), `changeSelector`
 * (CSS seçici, boş = tüm görünür metin). Sunucu sınırları sözleşme §3.1 / §7; seçici kuralı kural kapsamıyla AYNI
 * (`cssSelectorProblem`: `:matches…` ve `~=` yok, `:has(` en çok bir kez — be2 Aşama 1).
 */
import { cssSelectorProblem } from '../rules/ruleModel.js'

export const CONTENT_LIMITS = Object.freeze({ minBytes: 1, maxBytes: 2_000_000, selectorMax: 300 })
export const CHANGE_MASKS = Object.freeze(['DIGITS', 'DATES'])
/** Boyut birimleri (ondalık — 1 KB = 1000 bayt; üst sınır 2 000 000 bayt = 2 000 KB tam bölünür). */
export const SIZE_UNITS = Object.freeze({ B: 1, KB: 1000 })

const NEWLINE = /[\r\n]/

/** Satırdan form değerleri (snake_case → camelCase); maske CSV ya da dizi olabilir. */
export function contentFormFrom(m) {
  return {
    minBytes: m?.min_bytes ?? null,
    maxBytes: m?.max_bytes ?? null,
    changeDetect: m?.change_detect === true,
    changeMask: maskList(m?.change_mask),
    changeSelector: m?.change_selector ?? '',
  }
}

/** Maske listesi (sıra kanonik, bilinmeyen atılır). */
export function maskList(raw) {
  const src = Array.isArray(raw) ? raw : String(raw ?? '').split(',')
  const set = new Set(src.map((s) => String(s).trim().toUpperCase()))
  return CHANGE_MASKS.filter((k) => set.has(k))
}

/** Bayt değeri → görüntü birimi (KB'ye tam bölünüyorsa KB). */
export function unitFor(bytes) {
  if (bytes == null || bytes === '') return 'KB'   // boş alan: sayfa boyutları KB ile düşünülür
  return typeof bytes === 'number' && bytes >= 1000 && bytes % 1000 === 0 ? 'KB' : 'B'
}

/** Görüntü metni (birime göre); null → ''; geçersiz ham metin aynen. */
export function sizeText(bytes, unit) {
  if (bytes == null || bytes === '') return ''
  if (typeof bytes !== 'number') return String(bytes)
  const m = SIZE_UNITS[unit] || 1
  return bytes % m === 0 ? String(bytes / m) : String(bytes)
}

/** Yazılan metin + birim → bayt (boş → null; tam sayı değilse ham metin — doğrulama yakalar). */
export function parseSize(text, unit) {
  const s = String(text ?? '').trim()
  if (!s) return null
  if (!/^\d{1,9}$/.test(s)) return s
  return Number(s) * (SIZE_UNITS[unit] || 1)
}

/** Alan hataları: `{ minBytes|maxBytes|changeSelector: { code, params } }`. */
export function validateContentChecks(v) {
  const out = {}
  const check = (field, val) => {
    if (val == null || val === '') return
    if (typeof val !== 'number' || !Number.isInteger(val)) out[field] = { code: 'sizeNumber', params: [] }
    else if (val < CONTENT_LIMITS.minBytes || val > CONTENT_LIMITS.maxBytes) out[field] = { code: 'sizeRange', params: [] }
  }
  check('minBytes', v?.minBytes)
  check('maxBytes', v?.maxBytes)
  if (!out.minBytes && !out.maxBytes && typeof v?.minBytes === 'number' && typeof v?.maxBytes === 'number' && v.minBytes > v.maxBytes) {
    out.maxBytes = { code: 'sizeOrder', params: [] }
  }
  const sel = String(v?.changeSelector ?? '')
  if (sel.trim()) {
    if (NEWLINE.test(sel)) out.changeSelector = { code: 'selectorNewline', params: [] }
    else if (sel.trim().length > CONTENT_LIMITS.selectorMax) out.changeSelector = { code: 'selectorTooLong', params: [CONTENT_LIMITS.selectorMax] }
    else if (cssSelectorProblem(sel)) out.changeSelector = { code: cssSelectorProblem(sel) === 'cssRegex' ? 'selectorCssRegex' : 'selectorCssHasMany', params: [] }
  }
  return out
}

/** İstek gövdesi parçası (oluştur / güncelle — camelCase; boş → null). */
export function contentPayload(v) {
  return {
    minBytes: typeof v?.minBytes === 'number' ? v.minBytes : null,
    maxBytes: typeof v?.maxBytes === 'number' ? v.maxBytes : null,
    changeDetect: v?.changeDetect === true,
    changeMask: maskList(v?.changeMask),
    changeSelector: String(v?.changeSelector ?? '').trim() || null,
  }
}

/**
 * Değişim ayarı kayıtlıya göre temeli GEÇERSİZ kılacak biçimde değişti mi? (kapatıldı, maske ya da seçici değişti — sunucu
 * bu durumda temeli siler ve açık değişim alarmını sessizce kapatır; form kullanıcıya önceden söyler.) Yeni kayıtta false.
 */
export function changeSettingsReset(v, stored) {
  if (!stored || stored.changeDetect !== true) return false
  if (v?.changeDetect !== true) return true
  const a = maskList(v?.changeMask).join(',')
  const b = maskList(stored.changeMask).join(',')
  return a !== b || String(v?.changeSelector ?? '').trim() !== String(stored.changeSelector ?? '').trim()
}

/** Bayt → okunur metin ("1,2 KB", "850 B"); ondalık 1000 tabanlı (giriş birimiyle aynı). */
export function bytesText(bytes, lang = 'tr') {
  if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return null
  const locale = lang === 'en' ? 'en-GB' : 'tr-TR'
  if (bytes < 1000) return `${bytes} B`
  const kb = bytes / 1000
  if (kb < 1000) return `${kb.toLocaleString(locale, { maximumFractionDigits: kb < 10 ? 1 : 0 })} KB`
  return `${(kb / 1000).toLocaleString(locale, { maximumFractionDigits: 2 })} MB`
}

/** Boyut aralığının kısa metni: "1 KB–2 MB" · "≥ 1 KB" · "≤ 2 MB"; ikisi de yoksa null. */
export function sizeRangeText(min, max, lang = 'tr') {
  const a = typeof min === 'number' ? bytesText(min, lang) : null
  const b = typeof max === 'number' ? bytesText(max, lang) : null
  if (a && b) return `${a}–${b}`
  if (a) return `≥ ${a}`
  if (b) return `≤ ${b}`
  return null
}
