/**
 * "Ayrıldım" sinyali — çevrimiçi kullanıcı sayımı "son 2 dk" değil, o an SiteMonitor'u AÇIK tutanları göstersin
 * (2026-10-02, kullanıcı isteği).
 *
 * <p>Çıkış, hareketsizlik çıkışı, yönetici sonlandırması, pasif/bakım kesmesi oturum kaydını zaten siler — kişi sayımdan
 * hemen düşer. Kalan boşluk sekme/tarayıcı KAPANIŞIydı: sunucu bunu ancak ping'ler kesilince (120 sn) anlıyordu. Burada
 * kullanıcının SON açık sekmesi kapanırken {@code navigator.sendBeacon} ile `/api/session/leave` bildirilir.
 *
 * <p><b>Son sekme mi?</b> Her sekme `localStorage`'daki küçük bir kayda (`sm.tabs`: sekme kimliği → son vuruş) dakikada
 * bir vurur. Kapanan sekme kendini siler; geride TAZE (≤ 3 dk) bir kayıt kalmadıysa sinyal gönderilir. 3 dk: tarayıcılar
 * arka plandaki sekmenin zamanlayıcısını dakikada bire kısar — gizli ama açık sekme "kapalı" sanılmasın. Çöken sekmenin
 * kaydı bayatlar ve yok sayılır (o durumda sunucunun 120 sn'lik yedeği devreye girer); 12 sa'ten eski kayıt silinir.
 * Depolama yoksa/engelliyse kayıt tutulamaz → her kapanışta sinyal gider (en kötü: öteki sekme bir ping (≤ 15 sn)
 * çevrimdışı görünür — sunucu debounce'u sıfırladığı için o ping hemen yazar).
 */
export const TABS_KEY = 'sm.tabs'
export const BEAT_MS = 60_000
export const STALE_MS = 3 * 60_000
const FORGET_MS = 12 * 3600_000
const LEAVE_URL = `${import.meta.env.VITE_API_BASE ?? '/api'}/session/leave`

function readTabs() {
  try {
    const v = JSON.parse(localStorage.getItem(TABS_KEY) || '{}')
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {}
  } catch {
    return {}
  }
}

function writeTabs(map) {
  try { localStorage.setItem(TABS_KEY, JSON.stringify(map)) } catch { /* depolama yok — kayıt tutulamaz */ }
}

export function newTabId() {
  return `${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`
}

/** Bu sekmenin vuruşu; çok eski kayıtları temizler. */
export function beat(id, now = Date.now()) {
  const m = readTabs()
  m[id] = now
  for (const [k, v] of Object.entries(m)) if (!(now - Number(v) <= FORGET_MS)) delete m[k]
  writeTabs(m)
}

/** Bu sekmeyi kayıttan siler; kalan kaydı döner. */
export function removeTab(id) {
  const m = readTabs()
  delete m[id]
  writeTabs(m)
  return m
}

/** Başka TAZE sekme var mı? */
export function othersAlive(map, id, now = Date.now()) {
  return Object.entries(map || {}).some(([k, v]) => k !== id && now - Number(v) <= STALE_MS)
}

/** Sunucuya "ayrıldım" (beacon: sayfa kapanırken bile gider, yanıt beklenmez). */
export function sendLeave() {
  try {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
      return navigator.sendBeacon(LEAVE_URL, new Blob(['leave'], { type: 'text/plain' }))
    }
  } catch { /* beacon yok — sunucu 120 sn'lik yedekle düşürür */ }
  return false
}

/**
 * Oturum açıkken kurulur (App); dönen fonksiyon kaldırır. Kaldırma (çıkış / kullanıcı değişimi) sinyal GÖNDERMEZ —
 * çıkış zaten oturum kaydını siliyor; yalnız sekme kaydı temizlenir.
 */
export function installLeaveBeacon() {
  const id = newTabId()
  beat(id)
  const timer = setInterval(() => beat(id), BEAT_MS)
  const onVisible = () => { if (!document.hidden) beat(id) }
  const onHide = () => {
    const rest = removeTab(id)
    if (!othersAlive(rest, id)) sendLeave()
  }
  // Geri/ileri önbelleğinden (bfcache) dönüş: sekme yeniden canlı; sıradaki ping lastSeenAt'i yazar.
  const onShow = (e) => { if (e.persisted) beat(id) }
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('pagehide', onHide)
  window.addEventListener('pageshow', onShow)
  return () => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('pagehide', onHide)
    window.removeEventListener('pageshow', onShow)
    removeTab(id)
  }
}
