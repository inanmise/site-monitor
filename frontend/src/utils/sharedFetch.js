/**
 * Eşzamanlı istek birleştirme (2026-10-13, renkli panel kutuları): aynı getiriciyi aynı anda çağıran kutular TEK isteği
 * paylaşır (ör. dört sertifika paneli tek `/api/stats`). Yalnız SÜREN istek paylaşılır — biten yanıt bellekte tutulmaz
 * (her yoklama taze veri okur, testler arasında veri sızmaz).
 */
import { INFLIGHT_JOIN_MAX_MS } from './timedFetch.js'

const inflight = new Map()   // getirici → { p, at }
export function sharedFetch(fetcher, { fresh = false } = {}) {
  // `fresh` (Yenile düğmesi, getirici değişimi): asılı kalmış eski isteğe katılmaz, yenisini başlatır (inceleme).
  // İkinci savunma (2026-11-15): INFLIGHT_JOIN_MAX_MS'den yaşlı süren isteğe de katılınmaz — istemcinin süre sınırı
  // her isteği bitirir, ama bir hata yüzünden bitmeyen söz kutuları sonsuza dek bekletemez.
  const cur = fresh ? null : inflight.get(fetcher)
  if (cur && Date.now() - cur.at < INFLIGHT_JOIN_MAX_MS) return cur.p
  let p
  try {
    p = Promise.resolve(fetcher()).finally(() => { if (inflight.get(fetcher)?.p === p) inflight.delete(fetcher) })
  } catch (e) {
    return Promise.reject(e)
  }
  inflight.set(fetcher, { p, at: Date.now() })
  return p
}

/**
 * Oturum değişince (çıkış — sayfa yeniden yüklenmez) süren paylaşılan istekler unutulur: yeni kullanıcının kutuları önceki
 * kullanıcının kapsamıyla başlamış bir isteğe katılmasın (App.resetSessionData çağırır).
 */
export function clearSharedFetches() { inflight.clear() }
