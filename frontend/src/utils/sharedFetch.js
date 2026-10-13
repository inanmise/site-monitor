/**
 * Eşzamanlı istek birleştirme (2026-10-13, renkli panel kutuları): aynı getiriciyi aynı anda çağıran kutular TEK isteği
 * paylaşır (ör. dört sertifika paneli tek `/api/stats`). Yalnız SÜREN istek paylaşılır — biten yanıt bellekte tutulmaz
 * (her yoklama taze veri okur, testler arasında veri sızmaz).
 */
const inflight = new Map()
export function sharedFetch(fetcher, { fresh = false } = {}) {
  // `fresh` (Yenile düğmesi, getirici değişimi): asılı kalmış eski isteğe katılmaz, yenisini başlatır (inceleme)
  const cur = fresh ? null : inflight.get(fetcher)
  if (cur) return cur
  let p
  try {
    p = Promise.resolve(fetcher()).finally(() => { if (inflight.get(fetcher) === p) inflight.delete(fetcher) })
  } catch (e) {
    return Promise.reject(e)
  }
  inflight.set(fetcher, p)
  return p
}

/**
 * Oturum değişince (çıkış — sayfa yeniden yüklenmez) süren paylaşılan istekler unutulur: yeni kullanıcının kutuları önceki
 * kullanıcının kapsamıyla başlamış bir isteğe katılmasın (App.resetSessionData çağırır).
 */
export function clearSharedFetches() { inflight.clear() }
