import { downloadBlob } from '../../../utils/csvExport.js'
import { isAbortish, timedFetch } from '../../../utils/timedFetch.js'

/** Dışa aktarımın istemci süre sınırı (2026-11-15, "zaman aşımsız istek yok"): istek VE gövde, 3 dk. */
export const AUDIT_EXPORT_TIMEOUT_MS = 180_000

/**
 * Denetim dışa aktarımını İNDİRİR — `window.open` yerine fetch + blob.
 *
 * <p>Neden: uç tek pod'un belleğini korumak için aynı anda en çok iki dışa aktarmaya izin veriyor ve fazlasına
 * **429** dönüyor (prod kapısı Y-2). Yeni sekmede açılan bir bağlantı bu yanıtı kullanıcıya düz metin bir boş sayfa
 * olarak gösteriyordu; burada durum kodu okunup çağıran dostça bir bildirim gösterebilir. Dosya adı sunucunun
 * `Content-Disposition` başlığından gelir (sunucunun verdiği adla aynı).
 *
 * <p>İndirme `utils/csvExport.downloadBlob` ile (20.165.1, UIA-9): bağlantı DOM'da, URL 60 sn tutulur — 0 ms'de geri
 * alınan URL Safari / iPad'de indirmeyi başlatmıyordu ama "dışa aktarıldı" yazıyordu. Tarayıcı indirmeyi başlatamazsa
 * `{ ok: false, status: -1 }`.
 *
 * <p>Süre sınırı (2026-11-15): istek VE gövde {@link AUDIT_EXPORT_TIMEOUT_MS} ile sınırlı; süre dolunca
 * `{ ok: false, status: 0, timedOut: true }` (çağıran "çok uzun sürdü" der, meşgul durum kalkar).
 *
 * @returns {Promise<{ ok: boolean, status: number, timedOut?: boolean }>}  ağ hatasında status 0
 */
export async function downloadAuditExport(url, fallbackName, timeoutMs = AUDIT_EXPORT_TIMEOUT_MS) {
  let res
  try {
    res = await timedFetch(url, { credentials: 'include' }, timeoutMs)
  } catch (e) {
    return isAbortish(e) ? { ok: false, status: 0, timedOut: true } : { ok: false, status: 0 }
  }
  try {
    if (!res.ok) return { ok: false, status: res.status }
    const blob = await res.blob()
    const disp = res.headers?.get?.('Content-Disposition') || ''
    const match = /filename="?([^";]+)"?/.exec(disp)
    if (!downloadBlob(match ? match[1] : fallbackName, blob)) return { ok: false, status: -1 }
    return { ok: true, status: res.status }
  } catch (e) {
    return isAbortish(e) ? { ok: false, status: 0, timedOut: true } : { ok: false, status: 0 }
  } finally {
    res.done()
  }
}

/** Sunucunun satır tavanı (AuditController#export): tam kapsam 50.000, ekip kapsamı 5.000. */
export function exportCap(fullScope) {
  return fullScope ? 50_000 : 5_000
}
