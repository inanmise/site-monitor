/**
 * Oturum düşüşü yönlendirmesinin DÖNGÜ SİGORTASI (2026-10-09, sonsuz döngü denetimi).
 *
 * <p>Oturumlu bir istek 401 alınca `api/client.js` sayfayı sert biçimde `/?session=expired`'a götürür (tüm bellek durumu
 * sıfırlanır, giriş formu "oturum süresi doldu" bildirimiyle açılır). Yanlış kurulmuş çok kopyalı bir dağıtımda (bellek
 * içi oturum: `/me` bir kopyada 200, yoklama ötekinde 401) bu bir yeniden yükleme DÖNGÜSÜ olur: yükle → /me 200 →
 * yoklama 401 → yönlendir → yükle … Sigorta: 60 sn içinde 2 yönlendirme olduysa üçüncüsü YAPILMAZ; onun yerine bu
 * sekmede {@link SESSION_EXPIRED_EVENT} yayılır ve uygulama giriş formunu YERİNDE, aynı bildirimle açar.
 */

export const SESSION_EXPIRED_EVENT = 'sm:session-expired'
export const EXPIRED_REDIRECTS_KEY = 'sm.session.expiredRedirects'
export const EXPIRED_REDIRECT_WINDOW_MS = 60_000
export const EXPIRED_REDIRECT_MAX = 2

/**
 * Yönlendirme hakkı: pencere içindeki yönlendirme sayısı sınırın altındaysa bu yönlendirmeyi kaydeder ve true döner;
 * sınırdaysa false. sessionStorage yoksa true (eski davranış — sayaç tutulamıyor).
 */
export function claimExpiredRedirect(now = Date.now()) {
  try {
    let list = []
    try { list = JSON.parse(sessionStorage.getItem(EXPIRED_REDIRECTS_KEY) || '[]') } catch { list = [] }
    const recent = (Array.isArray(list) ? list : [])
      .map(Number)
      .filter((ts) => Number.isFinite(ts) && ts <= now && now - ts < EXPIRED_REDIRECT_WINDOW_MS)
    if (recent.length >= EXPIRED_REDIRECT_MAX) return false
    recent.push(now)
    sessionStorage.setItem(EXPIRED_REDIRECTS_KEY, JSON.stringify(recent))
    return true
  } catch {
    return true
  }
}

/** Yönlendirme yerine giriş formunu yerinde açtırır (aynı sekme). */
export function signalSessionExpired() {
  try { window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT)) } catch { /* olay yok */ }
}

/** @returns {() => void} aboneliği kaldırır */
export function onSessionExpired(handler) {
  const fn = () => handler()
  window.addEventListener(SESSION_EXPIRED_EVENT, fn)
  return () => window.removeEventListener(SESSION_EXPIRED_EVENT, fn)
}
