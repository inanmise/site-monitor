/**
 * SÜREN GÖRSEL İŞİ KAYDI (2026-11-14): toplu görsel indirme / ZIP paketleme sürerken izleme penceresi kapatılırsa iş iptal
 * olur ve hiçbir şey inmez — pencereyi kapatan sayfa (PageMonitorPage) önce buraya sorar ve kullanıcıya onaylatır
 * ("Leaving the view while it runs asks first"). React'siz, içe aktarmasız küçük kayıt: açılış paketine yük getirmez.
 */
const stores = new Set()

/** Bir deponun süren işi sorulabilsin diye kaydı; dönen işlev kaydı siler. */
export function registerImageStore(store) {
  if (!store || typeof store.isBusy !== 'function') return () => {}
  stores.add(store)
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
