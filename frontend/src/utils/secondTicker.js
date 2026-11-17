/**
 * Ortak saniye saati (performans 2026-11-17).
 *
 * <p>Olaylar listesinde her süren olay satırı (LiveDuration) kendi 1 sn'lik `setInterval`'ını kuruyordu — 50 satırlık bir
 * sayfa 50 zamanlayıcı demekti. Artık tek modül düzeyi zamanlayıcı var: yalnız en az bir abone varken ve sekme GÖRÜNÜRKEN
 * çalışır; son abone ayrılınca ya da sekme gizlenince durur. Sekme görünür olunca beklemeden bir tık atar (eski
 * `useVisibleInterval` davranışıyla aynı). Aboneler aynı `Date.now()` değerini alır; React tek olay döngüsündeki tüm
 * `setState` çağrılarını birleştirir (tek çizim turu).
 *
 * <p>Bir abonenin hatası diğerlerini durdurmaz. Sınırlı: zamanlayıcı en çok bir tane; abone kümesi bileşen sayısıyla sınırlı.
 */
export const SECOND_TICK_MS = 1000

const subscribers = new Set()
let timer = null
let listening = false

const docHidden = () => typeof document !== 'undefined' && document.hidden === true

function tick() {
  const now = Date.now()
  for (const fn of [...subscribers]) {
    try { fn(now) } catch { /* bir abonenin hatası ötekileri durdurmasın */ }
  }
}

function start() {
  if (timer == null && subscribers.size > 0 && !docHidden()) timer = setInterval(tick, SECOND_TICK_MS)
}

function stop() {
  if (timer != null) { clearInterval(timer); timer = null }
}

function onVisibility() {
  if (docHidden()) { stop(); return }
  if (subscribers.size === 0) return
  if (timer == null) { tick(); start() }
}

/**
 * Saniyede bir `fn(now)` çağrısı için abone olur.
 *
 * @param {(now: number) => void} fn
 * @returns {() => void} aboneliği bırakır
 */
export function subscribeSecondTick(fn) {
  if (typeof fn !== 'function') return () => {}
  subscribers.add(fn)
  if (!listening && typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibility)
    listening = true
  }
  start()
  return () => {
    subscribers.delete(fn)
    if (subscribers.size === 0) {
      stop()
      if (listening && typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisibility)
        listening = false
      }
    }
  }
}

/** Test / tanı: etkin zamanlayıcı ve abone sayısı. */
export function secondTickerState() {
  return { running: timer != null, subscribers: subscribers.size }
}
