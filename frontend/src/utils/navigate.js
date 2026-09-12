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

export default navigateTo
