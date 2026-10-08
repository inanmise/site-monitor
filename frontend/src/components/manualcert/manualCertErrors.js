import { errorInfoOf } from '../../utils/errorMessages.js'
import { splitServerErrors } from './manualCertModel.js'

/**
 * MANUEL SERTİFİKA HATA İLETİLERİ — saf eşleme (2026-10-08, kullanıcı isteği: "hata mesajlarının üzerinden tekrar geçelim
 * … çok açıklayıcı olsun"). Her ileti üç şeyi söyler: NE oldu, büyük olasılıkla NEDEN, kullanıcı ŞİMDİ NE yapmalı.
 *
 * <p>Kural: bilinen kodlar / durumlar için metin İSTEMCİNİN sözlüğünden gelir (`mcert.err.*`, TR + EN — açıklama + sonraki
 * adım); sunucunun kendi iletisi (Msg.t) kaybolmaz, "Teknik ayrıntı"da durum + kod + istek kimliğiyle birlikte görünür.
 * Bir alana ait hata (400 `errors.<alan>`) alanın altında (useFormErrors); alanı bu adımda olmayan ya da hiçbir alana ait
 * olmayan hata pencere bandında.
 *
 * <p>Sihirbaza özgü 409'lar (KEY_EXISTS → takip adı alanı / toplu satır, ALREADY_TRACKED → "Kaydı aç" bandı,
 * OLDER_THAN_CURRENT → onay kutusu, SAME_CERTIFICATE → "Yine de yükle") sihirbazda karşılanır; geri kalan her şey burada.
 */

/** Sunucunun bu akışta döndürdüğü kodlar (belge + test kapsamı). */
export const SERVER_CODES = Object.freeze([
  'PRIVATE_KEY_NOT_ACCEPTED', 'EXTRACTED_INVALID', 'PARSE_TIMEOUT', 'BUSY', 'RATE_LIMITED', 'KEY_EXISTS',
  'ALREADY_TRACKED', 'SAME_CERTIFICATE', 'OLDER_THAN_CURRENT', 'CURRENT_VERSION',
])

/**
 * "Teknik ayrıntı" künyesi: HTTP durumu, kod, istek kimliği (varsa) ve sunucunun kendi iletisi. Gövde / sertifika / şifre
 * ASLA (yalnız yanıtın üst alanları okunur). Gösterecek bir şey yoksa null.
 */
export function technicalDetail(res) {
  if (!res || typeof res !== 'object') return null
  const info = errorInfoOf(res) || {}
  const out = {}
  const status = typeof info.status === 'number' ? info.status : (typeof res.status === 'number' ? res.status : undefined)
  if (status !== undefined) out.status = status
  if (info.code || res.code) out.code = String(info.code || res.code)
  if (info.requestId) out.requestId = info.requestId
  const msg = typeof res.error === 'string' ? res.error.trim() : ''
  if (msg) out.message = msg.slice(0, 600)
  return Object.keys(out).length ? out : null
}

/** Ağ hatası / yanıtsız istek mi (sunucuya ulaşılamadı, zaman aşımı)? */
export const isNetworkFailure = (res) => !!res && (res.thrown === true || res.status === 0 || res.networkError === true)

/**
 * Başarısız yükleme yanıtı → gösterim kararı.
 *
 * @param {object}   res  `api.manualCerts.*` sonucu (`{ success:false, status, code, error, errors }`) ya da
 *                        `{ success:false, thrown:true, error, code }` (istek fırlattı: ağ hatası)
 * @param {'analyze'|'create'|'batch'|'renew'} op hangi işlem
 * @param {Function} t    i18n
 * @param {{ fields?: Set<string>, textSource?: boolean }} [opts] `fields` — bu adımda görünen alanlar (yalnız onlara alan
 *   hatası yazılır, gerisi bantta); `textSource` — metin yapıştırıldıysa sunucunun `file` hatası `text` alanına
 * @returns {{ fields: object, rows: object, banner: null | { tone, title, text, detail, action? } }}
 */
export function describeUploadFailure(res, op, t, { fields: available = new Set(), textSource = false } = {}) {
  const r = res && typeof res === 'object' ? res : {}
  const code = r.code
  const status = Number(r.status) || 0
  const detail = technicalDetail(r)
  const save = op !== 'analyze'
  const banner = (tone, titleKey, textKey, ...args) => ({ fields: {}, rows: {}, banner: { tone, title: t(titleKey), text: t(textKey, ...args), detail } })

  if (isNetworkFailure(r)) return banner('warning', 'mcert.err.networkTitle', save ? 'mcert.err.networkSave' : 'mcert.err.network')
  if (code === 'PRIVATE_KEY_NOT_ACCEPTED') return banner('danger', 'mcert.err.privateKeyTitle', 'mcert.err.privateKey')
  if (code === 'EXTRACTED_INVALID') return banner('danger', 'mcert.err.extractedTitle', 'mcert.err.extracted')
  if (code === 'PARSE_TIMEOUT' || status === 422) return banner('warning', 'mcert.err.timeoutTitle', 'mcert.err.timeout')
  if (code === 'BUSY') return banner('warning', 'mcert.err.busyTitle', 'mcert.err.busy')
  if (code === 'RATE_LIMITED' || status === 429) return banner('warning', 'mcert.err.rateLimitTitle', 'mcert.err.rateLimit')
  if (status === 403) return banner('danger', 'mcert.err.forbiddenTitle', op === 'renew' ? 'mcert.err.forbiddenRenew' : 'mcert.err.forbidden')
  if (status === 404) return banner('danger', 'mcert.err.notFoundTitle', 'mcert.err.notFound')
  if (status === 413) return banner('danger', 'mcert.err.tooLargeTitle', 'mcert.err.tooLarge')
  if (status >= 500) return banner('danger', 'mcert.err.serverTitle', 'mcert.err.server', status)

  // 400 alan hataları: bu adımda görünen alanlar alanın altına; satır hataları (toplu) satıra; gerisi bantta
  const split = splitServerErrors(r.errors)
  const fields = {}
  const rest = [...split.rest]          // düz `ref`, `items`, `inventory` … — alanı olmayan sunucu iletileri
  for (const [k, v] of Object.entries(split.fields)) {
    const key = textSource && k === 'file' ? 'text' : k
    if (available.has(key)) fields[key] = v
    else rest.push(v)
  }
  // Seçilen girdi (`ref` / `items[i].ref`): zincirin ara / kök üyesi ya da dosyada yok — İncelemeye dönüp yeniden seçilir
  const refError = Object.keys(r.errors && typeof r.errors === 'object' ? r.errors : {})
    .some((k) => k === 'ref' || /^items(?:\[\d+\]|\.\d+)\.ref$/.test(k))
  const rows = { ...split.rows }
  const restUnique = [...new Set(rest.filter(Boolean))]
  const hasField = Object.keys(fields).length > 0
  const hasRows = Object.keys(rows).length > 0
  if (!restUnique.length && (hasField || hasRows)) return { fields, rows, banner: null }
  // Sunucunun kendi açıklaması varsa (alanı olmayan alan hataları ya da kodsuz `error`) o; yoksa neden + sonraki adımı
  // söyleyen istemci metni. Metin ne olursa olsun durum / kod "Teknik ayrıntı"da.
  const serverText = restUnique.length ? restUnique.join(' ') : (typeof r.error === 'string' ? r.error.trim() : '')
  return {
    fields, rows,
    banner: {
      tone: 'danger',
      title: refError ? t('mcert.err.refTitle') : status === 400 ? t('mcert.err.invalidTitle')
        : save ? t('mcert.err.saveTitle') : t('mcert.err.analyzeTitle'),
      text: serverText || (save ? t('mcert.err.save') : t('mcert.err.analyze')),
      hint: refError ? t('mcert.err.refHint') : status === 400 ? t('mcert.err.invalidHint') : null,
      detail,
      ...(refError ? { action: 'review' } : {}),
    },
  }
}

/** Sürüm silme (ManualCertVersions) — kodlu / durumlu yanıt → bildirim metni (TR + EN, neden + sonraki adım). */
export function describeVersionDeleteFailure(res, t) {
  const r = res && typeof res === 'object' ? res : {}
  const status = Number(r.status) || 0
  if (r.code === 'CURRENT_VERSION') return t('mcert.ver.deleteCurrent')
  if (isNetworkFailure(r)) return t('mcert.ver.deleteNetwork')
  if (r.code === 'RATE_LIMITED' || status === 429) return t('mcert.ver.deleteRateLimit')
  if (status === 403) return t('mcert.ver.deleteForbidden')
  if (status === 404) return t('mcert.ver.deleteNotFound')
  if (status >= 500) return t('mcert.ver.deleteServer', status)
  return r.error ? `${t('mcert.ver.deleteFailed')}: ${r.error}` : t('mcert.ver.deleteFailedHint')
}
