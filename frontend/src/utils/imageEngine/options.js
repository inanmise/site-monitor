// Görsel motoru — seçenek doğrulama (saf). Her hata `EngineError('INVALID_OPTIONS', { field, reason })`.
import { ENGINE_LIMITS, EngineError, OUTPUT_FORMATS, RESIZE_METHODS } from './errors.js'

const bad = (field, reason) => new EngineError('INVALID_OPTIONS', { field, reason })

function side(value, field) {
  if (typeof value !== 'number' || !Number.isInteger(value)) throw bad(field, 'integer')
  if (value < 1 || value > ENGINE_LIMITS.maxSide) throw bad(field, 'range')
  return value
}

const isHex = (c) => (c >= 48 && c <= 57) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102)

/** '#rrggbb' → [r, g, b]; biçim dışı → INVALID_OPTIONS. */
export function parseBackground(value) {
  if (typeof value !== 'string' || value.length !== 7 || value.charCodeAt(0) !== 35) throw bad('background', 'format')
  for (let i = 1; i < 7; i++) if (!isHex(value.charCodeAt(i))) throw bad('background', 'format')
  const n = parseInt(value.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/**
 * Seçenekleri doğrular ve varsayılanlarla tamamlar. Eksik alan = varsayılan (resize none, format keep, quality auto,
 * targetBytes null, background beyaz, preserve hepsi kapalı); YANLIŞ alan = hata (sessizce düzeltilmez).
 * @returns {{ resize: object, format: string, quality: 'auto'|number, targetBytes: number|null, background: string,
 *   backgroundRgb: number[], preserve: { copyright: boolean, creation: boolean, location: boolean } }}
 */
export function normalizeOptions(options) {
  if (options == null) options = {}
  if (typeof options !== 'object' || Array.isArray(options)) throw bad('options', 'type')

  // resize
  const rz = options.resize == null ? { method: 'none' } : options.resize
  if (typeof rz !== 'object' || Array.isArray(rz)) throw bad('resize', 'type')
  const method = rz.method == null ? 'none' : rz.method
  if (!RESIZE_METHODS.includes(method)) throw bad('resize.method', 'unknown')
  let resize
  if (method === 'none') {
    resize = { method }
  } else if (method === 'scale') {
    const hasW = rz.width != null
    const hasH = rz.height != null
    if (hasW === hasH) throw bad(hasW ? 'resize.height' : 'resize.width', 'exactly_one')
    resize = hasW ? { method, width: side(rz.width, 'resize.width') } : { method, height: side(rz.height, 'resize.height') }
  } else {
    if (rz.width == null) throw bad('resize.width', 'required')
    if (rz.height == null) throw bad('resize.height', 'required')
    resize = { method, width: side(rz.width, 'resize.width'), height: side(rz.height, 'resize.height') }
  }

  // format
  const format = options.format == null ? 'keep' : options.format
  if (!OUTPUT_FORMATS.includes(format)) throw bad('format', 'unknown')

  // quality
  let quality = options.quality == null ? 'auto' : options.quality
  if (quality !== 'auto') {
    if (typeof quality !== 'number' || !Number.isFinite(quality)) throw bad('quality', 'type')
    if (quality < 1 || quality > 100) throw bad('quality', 'range')
    quality = Math.round(quality)
  }

  // targetBytes
  let targetBytes = options.targetBytes == null ? null : options.targetBytes
  if (targetBytes !== null) {
    if (typeof targetBytes !== 'number' || !Number.isFinite(targetBytes)) throw bad('targetBytes', 'type')
    if (targetBytes < 1 || targetBytes > ENGINE_LIMITS.maxInputBytes * 8) throw bad('targetBytes', 'range')
    targetBytes = Math.floor(targetBytes)
  }

  // background
  const background = options.background == null ? '#ffffff' : options.background
  const backgroundRgb = parseBackground(background)

  // preserve
  const pv = options.preserve == null ? {} : options.preserve
  if (typeof pv !== 'object' || Array.isArray(pv)) throw bad('preserve', 'type')
  const preserve = {}
  for (const key of ['copyright', 'creation', 'location']) {
    const v = pv[key] == null ? false : pv[key]
    if (typeof v !== 'boolean') throw bad(`preserve.${key}`, 'type')
    preserve[key] = v
  }

  return { resize, format, quality, targetBytes, background: background.toLowerCase(), backgroundRgb, preserve }
}
