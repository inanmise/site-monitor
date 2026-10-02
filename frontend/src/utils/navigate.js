/**
 * Programatik sekme geçişi (2026-09-12): App.jsx `sm:navigate` olayını dinler ve handleTabChange ile
 * (URL ?tab= + geçmiş kaydı + sayfa-durumu paramlarının temizlenmesi) uygular. Sekme bileşenleri App'e
 * prop zinciriyle bağlı değil; kart/palet/bildirim kutusu buradan gider. Bilinmeyen sekme yok sayılır.
 *
 * @param {string} tab    Nav sekme kimliği (dashboard, admin, weakalgo, health, settings, …)
 * @param {object} [params]  ek URL paramları (ör. { domain: 'a.example.com' } veya { sec: 'smtp' })
 */
export function navigateTo(tab, params) {
  try {
    window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab, params: params || undefined } }))
  } catch { /* SSR/jsdom eksikliği — sessiz */ }
}

/** Kayıtlı görünüm uygulama olayı — App.jsx dinler (bkz. {@link applyTabView}). */
export const APPLY_VIEW_EVENT = 'sm:apply-view'

/**
 * Kayıtlı görünümü uygular (2026-10-02, öneri 23): App sekmenin TÜM sayfa-durumu paramlarını (PAGE_STATE_PARAMS +
 * PAGE_STATE_PREFIXES) silip `params`'ı yazar ve sayfayı YENİDEN BAĞLAR — sayfalar durumlarını bağlanırken URL'den
 * okuduğu için görünüm böylece tam olarak yürürlüğe girer. Aynı sekmede geçmişe kayıt eklemez (replaceState).
 *
 * @param {string} tab     sekme kimliği
 * @param {object} params  görünümün URL paramları ({ mo_status: 'down', … })
 */
export function applyTabView(tab, params) {
  try {
    window.dispatchEvent(new CustomEvent(APPLY_VIEW_EVENT, { detail: { tab, params: params || {} } }))
  } catch { /* SSR/jsdom eksikliği — sessiz */ }
}

export default navigateTo
