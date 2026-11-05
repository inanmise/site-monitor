import { api } from '../../../api/client'
import { errorInfoOf } from '../../../utils/errorMessages.js'
import { legacyDetail, normalizeDetail } from './activityDetailModel.js'

/**
 * Ayrıntı sayfasının veri yükleyicisi (2026-11-05). Sonuç biçimi:
 *   `{ phase: 'ready', detail }` · `{ phase: 'notFound' }` · `{ phase: 'error', message, info }`
 *
 * <p>404 / 405 iki şey olabilir: kayıt yok / görme yetkisi yok (yeni sunucu, `/{id}` ile aynı kapı) YA DA uç yok (eski sunucu
 * — rolling deploy'da eski pod). Ayrım temel uçla (`GET /api/activity/{id}`) yapılır: o da yanıtlamazsa "bulunamadı", yanıtlarsa
 * eski sunucu → yalnız temel bilgiler (`legacy: true`). Ağ hatası / 5xx "okunamadı"dır — asla "bulunamadı" değil.
 */
export async function loadActivityDetail(id) {
  let res
  try {
    res = await api.getActivityFullDetail(id)
  } catch (e) {
    return { phase: 'error', message: e?.message || null, info: errorInfoOf(e) }
  }
  if (res?.success) {
    const detail = normalizeDetail(res)
    return detail ? { phase: 'ready', detail } : { phase: 'error', message: null, info: null }
  }
  const status = Number(res?.status ?? errorInfoOf(res)?.status)
  if (status !== 404 && status !== 405 && status !== 501) {
    return { phase: 'error', message: res?.error || null, info: errorInfoOf(res) }
  }
  let basic
  try {
    basic = await api.getActivityDetail(id)
  } catch (e) {
    return { phase: 'error', message: e?.message || null, info: errorInfoOf(e) }
  }
  if (basic?.success) {
    const detail = legacyDetail(basic)
    // Yeni sunucuda `/detail` 404 ama temel uç 200 → ayrıntı ucu bu satırı vermedi; eski görünüm yeterli (kapı aynı)
    return detail ? { phase: 'ready', detail } : { phase: 'error', message: null, info: null }
  }
  const basicStatus = errorInfoOf(basic)?.status
  if (basicStatus === 404 || (basicStatus == null && basic?.error === 'not_found')) return { phase: 'notFound' }
  return { phase: 'error', message: basic?.error || null, info: errorInfoOf(basic) }
}
