/**
 * Sekme değişiminde sayfayı başa kaydırır (H paketi 2026-11-26, deneyim 4): kaydırma belgede (`.app-main` kap kurmaz —
 * App.css), sekmeler arası geçişte eski `scrollY` kalıyordu → parçası önbellekte olan sayfa başlığı ve süzgeçleri
 * görünmeden, ortasından açılıyordu (ilk ziyarette Suspense iskeleti boyu kısalttığı için olmuyordu — davranış
 * ziyarete göre değişiyordu).
 *
 * <p>Yalnız GERÇEK sekme değişiminde çağrılır (App.handleTabChange `id !== tab` dalı). Aynı sekmede param değişimi
 * (sm:tab-params) kaydırmaz; tarayıcı Geri / İleri (popstate) de kaydırmaz — o kaydın konumunu tarayıcı kendisi geri
 * yükler (inceleme bulgusu 3). `#çapa` denetimi YOK (inceleme bulgusu 2): handleTabChange eski sekmenin çapasını yeni
 * sekmenin adresine taşımaz, yani bu yolda çapa olmaz. Eşzamanlı çağrılır: hedef sayfanın kendi mount kaydırması / odağı
 * (derin bağlantı penceresi, alan odağı) SONRA koşar ve kazanır. Zaten baştaysa hiçbir şey yapmaz (jsdom'da da çağrı yok —
 * `scrollTo` uygulanmamış).
 */
export function scrollPageTopForTab(win = typeof window !== 'undefined' ? window : null) {
  if (!win) return false
  try {
    const y = win.scrollY ?? win.pageYOffset ?? win.document?.documentElement?.scrollTop ?? 0
    const x = win.scrollX ?? win.pageXOffset ?? 0
    if (!(y > 0) && !(x > 0)) return false
    // `instant`: belgede `scroll-behavior: smooth` olsa da sıçrayarak — yeni sayfa kayarken çizilmesin
    win.scrollTo({ top: 0, left: 0, behavior: 'instant' })
    return true
  } catch {
    return false
  }
}
