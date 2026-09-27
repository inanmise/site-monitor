import { downscaleImage } from '../../../utils/imageDownscale.js'

/**
 * Sorun bildirimi formunun SAF yardımcıları — oturum içi pencere (IssueReportModal, USER_REPORT/CLIENT_ERROR) ve
 * giriş sayfası penceresi (LoginHelpDialog, LOGIN) AYNI kuralları kullanır. Sınırlar sunucuyla aynı:
 * IssueReportController / LoginHelpController (≤5 görsel, PNG/JPEG, çözülmüş boyut ≤1 MB, açıklama ≤5000).
 */
export const MAX_IMAGES = 5
export const ACCEPTED = ['image/png', 'image/jpeg']
export const MAX_IMAGE_BYTES = 1024 * 1024
export const MAX_MESSAGE = 5000
export const MAX_ERROR_TEXT = 2000
export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
/** Küçültme hedefi — giriş sayfasının eski ayarı; iki akış artık aynı. */
const DOWNSCALE = { maxDim: 1600, targetBytes: 400 * 1024 }

export function browserLabel(ua) {
  if (!ua) return '—'
  if (/edg\//i.test(ua)) return 'Edge'
  if (/chrome\//i.test(ua)) return 'Chrome'
  if (/firefox\//i.test(ua)) return 'Firefox'
  if (/safari\//i.test(ua)) return 'Safari'
  return ua.slice(0, 40)
}

export function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = reject
    r.readAsDataURL(file)
  })
}

/**
 * Seçilen / bırakılan / yapıştırılan dosyaları işler. Hiçbir şey SESSİZCE düşmez: desteklenmeyen, sınırı aşan,
 * okunamayan ve küçültülse de 1 MB'ı aşan dosyalar sayılıp `notices` ile söylenir.
 * @returns {Promise<{urls: string[], notices: string[]}>}
 */
export async function processImageFiles(fileList, currentCount, t) {
  const incoming = Array.from(fileList || [])
  const notices = []
  if (!incoming.length) return { urls: [], notices }
  // downscaleImage görsel OLMAYAN dosyayı olduğu gibi döndürür — giriş filtresi şart (PDF sessizce yüklenmesin).
  const supported = incoming.filter((f) => ACCEPTED.includes(f.type))
  const unsupported = incoming.length - supported.length
  if (unsupported > 0) notices.push(t('issue.imgUnsupported', unsupported))
  const room = Math.max(0, MAX_IMAGES - currentCount)
  const take = supported.slice(0, room)
  if (supported.length > take.length) notices.push(t('issue.imgTooMany', MAX_IMAGES))
  const urls = []
  let unreadable = 0
  let tooBig = 0
  for (const f of take) {
    try {
      const small = await downscaleImage(f, DOWNSCALE)
      if (small?.size > MAX_IMAGE_BYTES) { tooBig += 1; continue }
      urls.push(await fileToDataUrl(small))
    } catch { unreadable += 1 }
  }
  if (tooBig > 0) notices.push(t('irf.imgTooBig', tooBig))
  if (unreadable > 0) notices.push(t('issue.imgFailed', unreadable))
  return { urls, notices }
}

/** Panodan yapıştırılan görsel dosyaları (ekran görüntüsü Ctrl+V). Metin yapıştırmaya dokunulmaz. */
export function imagesFromClipboard(e) {
  const dt = e?.clipboardData
  if (!dt) return []
  const files = Array.from(dt.files || []).filter((f) => f.type?.startsWith('image/'))
  if (files.length) return files
  return Array.from(dt.items || [])
    .filter((it) => it.kind === 'file' && it.type?.startsWith('image/'))
    .map((it) => it.getAsFile())
    .filter(Boolean)
}

/** `LIR-2026-000041` → derin bağlantı (?tab=login-issues&ir_id=41). Kod çözülemezse null. */
export function reportHref(reference) {
  const m = /^LIR-\d{4}-0*(\d{1,9})$/i.exec(String(reference || '').trim())
  return m ? `/?tab=login-issues&ir_id=${Number(m[1])}` : null
}
export function reportIdOf(reference) {
  const m = /^LIR-\d{4}-0*(\d{1,9})$/i.exec(String(reference || '').trim())
  return m ? Number(m[1]) : null
}

/** Giriş sayfası gönderim sonucu → kullanıcıya NET sebep (ağ / oran / kapalı / sunucu / doğrulama). */
export function loginHelpReason(r, t) {
  if (!r) return t('login.helpError')
  if (r.networkError) return t('login.helpErrNetwork')
  if (r.status === 429) return t('login.helpErrRate')
  if (r.status === 404) return t('login.helpErrDisabled')
  if (r.status >= 500) return t('login.helpErrServer')
  return r.error || t('login.helpError')   // 400 doğrulama → sunucunun mesajı
}
/** "Ayrıntıyı göster" ile açılan teknik satır: NETWORK / HTTP kodu / sunucu mesajı. */
export function loginHelpDetail(r) {
  if (!r) return ''
  const parts = []
  if (r.networkError) parts.push('NETWORK')
  if (r.status) parts.push('HTTP ' + r.status)
  if (r.error) parts.push(r.error)
  return parts.join(' · ')
}
