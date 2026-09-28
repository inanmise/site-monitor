import { useSyncExternalStore } from 'react'
import { loadNocFormOptions, resetNocFormOptionsCache } from './forms/useNocFormOptions.js'
import { NOC_COVERAGE_EVENT } from '../../utils/nocCoverageEvent.js'

/**
 * PAYLAŞILAN 7/24 durumu (2026-09-28) — tür anahtarları + kullanılabilir grup hükmü + gruplar + en düşük seviye; kart ve
 * pencere göstergesi (noc/NocStatus) okur.
 *
 * <p><b>Tek istek:</b> sayfada 300+ kart olabilir. Durum MODÜL düzeyinde tek bir depoda (useSyncExternalStore) durur;
 * her gösterge abone olur ama ağ isteği yalnız depo bayatsa ve uçuşta istek yoksa atılır. Kaynak, izleme formlarının
 * kullandığı AYNI uç ve önbellek: `GET /api/noc/groups/options` (`monitoring.read` olan herkes; e-posta YOK) →
 * `loadNocFormOptions` — form açılınca ikinci istek de gitmez.
 *
 * <p><b>Tazelik:</b> başarılı yanıt 5 dk taze sayılır; yeni bir abone (sayfa/pencere açılışı) ya da sekmenin yeniden
 * görünür olması bayat depoyu tazeler. 7/24 yazması (Ayarlar'da grup/yapılandırma, Kapsam'da aç/kapa — api/client
 * `NOC_COVERAGE_EVENT` yayar) depoyu HEMEN geçersizler; abone varsa yeniden çeker.
 *
 * <p><b>Hata / yetki yok (403):</b> `status: 'error'`, `data: null` → gösterge iki durumlu kalır (açık/kapalı, yalnız
 * `noc_notify`'tan) ve "iletilmiyor" İDDİA ETMEZ. Önceden başarıyla alınmış veri varken tazeleme başarısız olursa eski
 * (gerçek) veri korunur. Hata 60 sn sonra yeniden denenir (her kart bağlanışında değil).
 */
const FRESH_MS = 5 * 60_000
const RETRY_MS = 60_000

const EMPTY = Object.freeze({ status: 'idle', data: null, at: 0 })
let snapshot = EMPTY
let inflight = null
let generation = 0   // sıfırlama/geçersizleme uçuştaki eski yanıtı geçersiz kılar
const listeners = new Set()

function emit(next) {
  snapshot = next
  for (const l of [...listeners]) l()
}

function isFresh(now = Date.now()) {
  if (snapshot.status === 'ready') return now - snapshot.at < FRESH_MS
  if (snapshot.status === 'error') return now - snapshot.at < RETRY_MS
  return false
}

/** Depoyu (gerekirse) tazeler; `force` = tazelik beklemeden. Uçuşta istek varsa ona katılır. */
export function refreshNocState(force = false) {
  if (inflight) return inflight
  if (!force && isFresh()) return Promise.resolve(snapshot)
  const gen = generation
  // Bilinçli olarak 'loading' YAYINLANMAZ: yüzlerce kart aynı karede abone olurken her yayın hepsini yeniden çizerdi;
  // veri gelene dek 'idle' ile görünüm aynı (iki durumlu, iddiasız).
  inflight = Promise.resolve()
    .then(() => loadNocFormOptions())
    .catch(() => null)
    .then((opts) => {
      if (gen !== generation) return snapshot   // arada sıfırlandı/geçersizlendi → eski yanıt yazılmaz
      inflight = null
      const at = Date.now()
      if (opts) emit({ status: 'ready', data: opts, at })
      else if (snapshot.data) emit({ ...snapshot, status: 'ready', at: at - FRESH_MS + RETRY_MS })   // eski gerçek veri kalır, 60 sn sonra yeniden dene
      else emit({ status: 'error', data: null, at })
      return snapshot
    })
  return inflight
}

/** 7/24 yazması → HEMEN bayat; abone varsa yeniden çek (açık sayfa beklemeden doğru durumu gösterir). */
function invalidate() {
  generation++
  inflight = null
  resetNocFormOptionsCache()
  snapshot = { ...snapshot, at: 0 }
  if (listeners.size) refreshNocState(true)
}

function onVisible() {
  try { if (document.visibilityState === 'visible' && listeners.size) refreshNocState() } catch { /* document yok */ }
}

try { window.addEventListener(NOC_COVERAGE_EVENT, invalidate) } catch { /* window yok */ }
try { document.addEventListener('visibilitychange', onVisible) } catch { /* document yok */ }

function subscribe(listener) {
  listeners.add(listener)
  refreshNocState()
  return () => { listeners.delete(listener) }
}

const getSnapshot = () => snapshot

/** Testler için: depoyu ve form seçenekleri önbelleğini sıfırla. */
export function resetNocStateForTests() {
  generation++
  inflight = null
  snapshot = EMPTY
  resetNocFormOptionsCache()
}

/**
 * @returns {{ status: 'idle'|'ready'|'error', data: null | { groups, disabledTypes, hasActiveGroup, minLevel }, at: number }}
 */
export function useNocState() {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
