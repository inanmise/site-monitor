import { captureFileName, extensionOf, pickImageType } from './captureModel.js'

/**
 * PANODAN GÖRSEL (2026-10-18): "Panodan yapıştır" düğmesi `navigator.clipboard.read()` kullanır (Chromium, Safari,
 * Firefox 127+). Tarayıcı izni reddederse ya da arayüz yoksa düğme hata göstermez — "yuvayı seçip Ctrl+V ile
 * yapıştırın" der; Ctrl/⌘+V (paste olayı) her tarayıcıda çalışır ve izin istemez.
 */

export class ClipboardImageError extends Error {
  /** `kind`: denied (izin yok) | empty (panoda görsel yok) | unsupported | failed */
  constructor(kind, cause) {
    super(kind)
    this.name = 'ClipboardImageError'
    this.kind = kind
    this.cause = cause
  }
}

/** "Panodan yapıştır" gösterilsin mi (güvenli bağlam + clipboard.read)? */
export function canReadClipboard() {
  try {
    if (globalThis.isSecureContext === false) return false
    return typeof globalThis.navigator?.clipboard?.read === 'function'
  } catch {
    return false
  }
}

/** Yapıştırılan / panodan okunan görsele okunur bir ad verir (`<yuva>-pano-<zaman>.<uzantı>`). */
export function renameClipboardFile(blob, slotId, date = new Date()) {
  const type = blob?.type || 'image/png'
  return new File([blob], captureFileName(slotId, 'pano', date, extensionOf(type)), { type, lastModified: date.getTime() })
}

/** Panodaki ilk görseli `File` olarak okur; görsel yoksa `empty`, izin yoksa `denied`. */
export async function readClipboardImage(slotId, nav = globalThis.navigator) {
  if (typeof nav?.clipboard?.read !== 'function') throw new ClipboardImageError('unsupported')
  let items
  try {
    items = await nav.clipboard.read()
  } catch (e) {
    const name = e?.name
    throw new ClipboardImageError(name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : name === 'DataError' ? 'empty' : 'failed', e)
  }
  for (const item of Array.from(items || [])) {
    const type = pickImageType(item?.types)
    if (!type) continue
    let blob
    try {
      blob = await item.getType(type)
    } catch (e) {
      throw new ClipboardImageError('failed', e)
    }
    if (blob && blob.size > 0) return renameClipboardFile(blob.type ? blob : new Blob([blob], { type }), slotId)
  }
  throw new ClipboardImageError('empty')
}
