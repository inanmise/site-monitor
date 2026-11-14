/**
 * SÜREN GÖRSEL İŞİ KAYDI (2026-11-14): toplu görsel indirme / ZIP paketleme sürüyor mu. Uygulama içi gezinme işi iptal
 * ETMEZ (depo bırakılır, iş sürer — imageToolStore `release`); yalnız tarayıcı sekmesini kapatmak / yenilemek keser, o
 * yüzden süren iş varken `beforeunload` uyarısı verilir. İzleme penceresi kapanırken (PageMonitorPage) "arka planda
 * sürüyor" bilgisi gösterilir. React'siz, içe aktarmasız küçük kayıt: açılış paketine yük getirmez.
 */
const stores = new Set()
let unloadHooked = false

function onBeforeUnload(e) {
  if (!isImageWorkBusy()) return undefined
  e.preventDefault()
  e.returnValue = ''
  return ''
}

/** Bir deponun süren işi sorulabilsin diye kaydı; dönen işlev kaydı siler. */
export function registerImageStore(store) {
  if (!store || typeof store.isBusy !== 'function') return () => {}
  stores.add(store)
  if (!unloadHooked && typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('beforeunload', onBeforeUnload)
    unloadHooked = true
  }
  return () => { stores.delete(store) }
}

/** Şu an toplu görsel indirme / ZIP paketleme süren bir sekme var mı? */
export function isImageWorkBusy() {
  for (const s of stores) {
    try { if (s.isBusy()) return true } catch { /* kapanmış depo — sayılmaz */ }
  }
  return false
}

/** Testler: kaydı boşaltır. */
export function resetImageWorkForTests() {
  stores.clear()
}
