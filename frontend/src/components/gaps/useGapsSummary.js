import { useSyncExternalStore } from 'react'
import { getGapsSummary, subscribeGapsSummary } from './gapsBus.js'

// Eksikler özeti (2026-11-15, D paketi): kullanıcı menüsü rozeti ve Kişisel Panom kutusu. Ağ yok — GapsPrompt'un son
// okumasından yayınlanan sayılar. Bu sekmede henüz okunmadıysa bu oturumdaki başka sekmenin son sayısı (yalnız sayı) kullanılır.
// Hafif modül: kullanıcı menüsü açılış paketindedir, eksikler modellerini / yükleyicisini İÇE AKTARMAZ.

const SESSION_KEY = 'sm.gaps.session'   // gapsModel.GAPS_SESSION_KEY ile aynı (testte karşılaştırılır)

/** Yayınlanan özet (yoksa null). */
export function useGapsSummary() {
  return useSyncExternalStore(subscribeGapsSummary, getGapsSummary, () => null)
}

/** Bu oturumda başka sekmenin son okumasındaki görünen eksik sayısı (yoksa / bozuksa null). */
export function sessionGapsCount() {
  try {
    const o = JSON.parse(localStorage.getItem(SESSION_KEY) || 'null')
    const n = o && typeof o === 'object' ? Number(o.count) : NaN
    return Number.isInteger(n) && n >= 0 && n < 100_000 ? n : null
  } catch {
    return null
  }
}

/** Menü rozetinin sayısı: özet hazırsa onun toplamı, okunuyorsa / hiç okunmadıysa oturum sayısı; 0 ya da bilinmiyorsa null. */
export function gapsBadgeCount(summary, sessionCount = null) {
  const n = summary && summary.status === 'ready' && Number.isInteger(summary.total) ? summary.total : sessionCount
  return Number.isInteger(n) && n > 0 ? n : null
}
