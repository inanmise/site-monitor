import { timedFetch } from '../../utils/timedFetch.js'
import { onSessionReset } from '../../utils/sessionResetBus.js'

/**
 * "Geri al" (2026-11-20, A3) — görsel silme / değiştirme ve konu silme sonrası ~10 sn'lik geri alma bildirimi.
 *
 * <p>Görselin geri alınabilmesi için baytları silme / değiştirme ÖNCESİ okunur ({@link snapshotImage}): ≤ 5 MB (sunucunun
 * görsel sınırı), süre sınırlı ({@link UNDO_FETCH_TIMEOUT_MS}); okunamazsa ya da büyükse geri alma sunulmaz (silme yine olur).
 * Baytlar YALNIZ bildirimin kapanışına dek bellekte durur: süre dolunca, yuva sökülünce (rapordan çıkış) ve oturum
 * sıfırlanınca (çıkış — {@link registerUndo}) bırakılır. Geri alma normal yükleme yolundan geçer (tarayıcıda hazırlama,
 * doğrulama, 503 kilit çakışmasında tek yeniden deneme, öteki pencerelere duyuru).
 */

/** Geri alma bildiriminin süresi. */
export const UNDO_MS = 10000
/** Geri alınabilecek en büyük görsel (sunucunun görsel başına sınırı). */
export const UNDO_MAX_BYTES = 5 * 1024 * 1024
/** Silme / değiştirme öncesi görsel okumasının süre sınırı — aşılırsa geri alma sunulmaz, işlem beklemez. */
export const UNDO_FETCH_TIMEOUT_MS = 8000

const TYPE_EXT = { 'image/png': 'png', 'image/jpeg': 'jpg' }

/**
 * Görselin şu anki baytları (`url` = görsel ucu). Dönen `File` normal yükleme yoluna verilir; okunamaz / büyük / boş → null.
 * Hiç atmaz. `signal` (inceleme 2026-11-20): çağıran okumayı keser (yuva sökülünce, yükleme olmayınca) — kesilen okuma null.
 */
export async function snapshotImage(image, url, { timeoutMs = UNDO_FETCH_TIMEOUT_MS, fetchImpl = timedFetch, signal = null } = {}) {
  if (!image || image.id == null || !url) return null
  if (signal?.aborted) return null
  const declared = Number(image.size_bytes)
  if (Number.isFinite(declared) && declared > UNDO_MAX_BYTES) return null
  try {
    const init = signal ? { credentials: 'same-origin', signal } : { credentials: 'same-origin' }
    const res = await fetchImpl(url, init, timeoutMs)
    if (!res || !res.ok) { res?.done?.(); return null }
    const blob = await res.blob()
    if (!blob || !(blob.size > 0) || blob.size > UNDO_MAX_BYTES) return null
    const type = blob.type || image.content_type || 'image/png'
    const name = image.file_name || `image-${image.id}.${TYPE_EXT[type] || 'png'}`
    if (typeof File === 'function') return new File([blob], name, { type })
    return Object.assign(blob, { name })
  } catch {
    return null
  }
}

/** Oturum sıfırlanınca kapatılacak geri alma bildirimleri (kapatma işlevleri). */
const pending = new Set()
let unsubscribe = null

/**
 * Geri alma bildirimini oturum sıfırlamasına bağlar: çıkışta (ya da kullanıcı değişince) bildirim kapanır, tuttuğu baytlar
 * bırakılır — bir sonraki kullanıcı öncekinin görselini geri alamaz. Dönen işlev kaydı siler (bildirim kendiliğinden kapanınca).
 */
export function registerUndo(dismiss) {
  if (typeof dismiss !== 'function') return () => {}
  pending.add(dismiss)
  if (!unsubscribe) {
    unsubscribe = onSessionReset(() => {
      const all = [...pending]
      pending.clear()
      for (const fn of all) { try { fn() } catch { /* kapatma hatası diğerlerini durdurmaz */ } }
    })
  }
  return () => { pending.delete(dismiss) }
}

/** Testler için: bekleyen kayıt sayısı. */
export function pendingUndoCount() {
  return pending.size
}

/**
 * Konuyu silindiği yere geri koyar (işlevsel güncelleme): aynı kimlikli konu zaten varsa (ör. taslak geri alındı / yeniden
 * yüklendi) liste aynen döner; yoksa `min(index, uzunluk)` konumuna AYNI nesne girer.
 */
export function restoreTopic(list, topic, index) {
  const l = Array.isArray(list) ? list : []
  if (!topic || l.some((x) => x?.id === topic.id)) return l
  const at = Math.max(0, Math.min(Number.isInteger(index) ? index : l.length, l.length))
  return [...l.slice(0, at), topic, ...l.slice(at)]
}
