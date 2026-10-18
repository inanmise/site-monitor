/**
 * PENCERELER ARASI GÖRSEL EŞİTLEMESİ (2026-10-18): rapor düzenleyicisi ile ayrı "Görsel paneli" penceresi aynı
 * raporun görsellerini gösterir. Bir pencerede yükleme / değiştirme / silme olunca `images-changed`, şablon
 * uygulanınca ya da rapor silinince `report-changed` yayınlanır; öteki pencere YALNIZ görsel listesini (panel: raporu)
 * yeniden okur — düzenleyicinin taslağı, otomatik kaydı ve sürümü etkilenmez (görseller ayrı yazımlardır).
 *
 * <p>Kanal `BroadcastChannel('sm-te-images')`; yoksa localStorage damgası + `storage` olayı. Kendi iletisi yok sayılır
 * (pencere kimliği). İleti yalnız rapor kimliği ve türü taşır — görsel / kişi verisi yok.
 */

export const IMAGES_CHANNEL = 'sm-te-images'
export const IMAGES_SYNC_KEY = 'sm.te.images.sync'
export const KIND_IMAGES = 'images-changed'
export const KIND_REPORT = 'report-changed'

const SELF = `w-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
const listeners = new Set()
let channel
let storageBound = false

function deliver(msg) {
  if (!msg || typeof msg !== 'object' || msg.from === SELF || msg.reportId == null) return
  for (const fn of [...listeners]) {
    try { fn(msg) } catch { /* dinleyici hatası diğerlerini durdurmaz */ }
  }
}

function ensureChannel() {
  if (channel !== undefined) return channel
  channel = null
  try {
    if (typeof globalThis.BroadcastChannel === 'function') {
      channel = new globalThis.BroadcastChannel(IMAGES_CHANNEL)
      channel.onmessage = (e) => deliver(e?.data)
      // Node (testler): açık kanal süreci ayakta tutmasın
      channel.unref?.()
    }
  } catch {
    channel = null
  }
  if (!channel && !storageBound) {
    storageBound = true
    try {
      globalThis.addEventListener?.('storage', (e) => {
        if (e?.key !== IMAGES_SYNC_KEY || !e.newValue) return
        try { deliver(JSON.parse(e.newValue)) } catch { /* bozuk damga */ }
      })
    } catch { /* yok */ }
  }
  return channel
}

/** Bu pencerede raporun görselleri (ya da raporun kendisi) değişti — öteki pencerelere duyurulur. */
export function postImagesChanged(reportId, kind = KIND_IMAGES) {
  if (reportId == null) return
  const msg = { reportId: String(reportId), kind, from: SELF, at: Date.now() }
  const ch = ensureChannel()
  if (ch) {
    try { ch.postMessage(msg); return } catch { /* depolamaya düş */ }
  }
  try { globalThis.localStorage?.setItem(IMAGES_SYNC_KEY, JSON.stringify(msg)) } catch { /* depolama yok */ }
}

/** Raporun eşitleme iletilerine abone olur (`kinds` boşsa hepsi); dönen işlev aboneliği kaldırır. */
export function onImagesChanged(reportId, cb, kinds = null) {
  ensureChannel()
  const id = String(reportId)
  const fn = (msg) => {
    if (String(msg.reportId) !== id) return
    if (kinds && !kinds.includes(msg.kind)) return
    cb(msg)
  }
  listeners.add(fn)
  return () => { listeners.delete(fn) }
}

/** Testler: bu pencerenin kimliği. */
export const IMAGES_SYNC_SELF = SELF
