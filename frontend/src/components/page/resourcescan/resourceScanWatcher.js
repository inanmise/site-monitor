/**
 * Kaynak analizi BİTİŞ BİLDİRİMİ (2026-10-31 r177, kullanıcı isteği): analiz 5 dakikaya kadar sürebilir; kullanıcı
 * "çıkabilirsiniz" denince detay penceresini kapatıyor ve bitince hiçbir şey duymuyordu. Bu modül, BU SEKMEDE
 * başlatılan analizleri bilir; sekme (ResourceScanTab) sökülürken analiz hâlâ sürüyorsa izlemeyi ARKA PLANDA devralır
 * ve bitince verilen geri çağrıyı (tost + "Aç") çalıştırır. Sekme yeniden açılırsa izlemeyi geri alır (çift sorgu yok).
 *
 * Sınırlar (CLAUDE.md "No unbounded loop"): setTimeout zinciri (çakışmaz), art arda `maxFailures` okuma hatası ya da
 * `capMs` toplam süre → durur, bildirim YOK (sonuç bilinmiyor — "bitti" uydurulmaz). Aynı analiz iki kez izlenmez.
 * Modül düzeyi durum yalnız bu sekmenin belleğinde yaşar (sayfa yenilenince unutulur — bilinçli).
 */

const started = new Set()          // `${monitorId}:${scanId}` — bu sekmede başlatılanlar
const watchers = new Map()         // anahtar → { stop }

const keyOf = (monitorId, scanId) => `${monitorId}:${scanId}`

/** Bu sekmede başlatılan bir analizi işaretle (POST yanıtı ya da 409'daki koşu DEĞİL — yalnız gerçekten başlatılan). */
export function markStartedHere(monitorId, scanId) {
  if (monitorId == null || scanId == null) return
  started.add(keyOf(monitorId, scanId))
}

export const startedHere = (monitorId, scanId) => started.has(keyOf(monitorId, scanId))

export function forgetStarted(monitorId, scanId) {
  started.delete(keyOf(monitorId, scanId))
}

export const isWatching = (monitorId, scanId) => watchers.has(keyOf(monitorId, scanId))

/**
 * Sekme sökülürken süren analizi arka planda izlemeye devret. `poll()` → `{ scan }` (ya da null — okunamadı); `onDone(scan)`
 * analiz RUNNING dışı bir duruma geçince BİR KEZ çağrılır. Döner: durdurma fonksiyonu.
 */
export function handOff({ monitorId, scanId, poll, onDone, pollMs = 3000, capMs = 10 * 60_000, maxFailures = 5,
  now = () => Date.now() }) {
  const key = keyOf(monitorId, scanId)
  if (watchers.has(key) || typeof poll !== 'function') return () => {}
  let stopped = false
  let timer = null
  let failures = 0
  const begin = now()
  const stop = () => {
    stopped = true
    if (timer) clearTimeout(timer)
    timer = null
    if (watchers.get(key)?.stop === stop) watchers.delete(key)
  }
  const tick = async () => {
    timer = null
    if (stopped) return
    let scan = null
    try { scan = (await poll())?.scan ?? null } catch { scan = null }
    if (stopped) return
    if (!scan) {
      failures += 1
      if (failures >= maxFailures) { stop(); return }
    } else {
      failures = 0
      if (scan.status !== 'RUNNING') {
        stop()
        forgetStarted(monitorId, scanId)
        try { onDone?.(scan) } catch { /* bildirim hatası izlemeyi düşürmesin */ }
        return
      }
    }
    if (now() - begin > capMs) { stop(); return }
    timer = setTimeout(tick, failures ? pollMs * 2 : pollMs)
  }
  watchers.set(key, { stop })
  timer = setTimeout(tick, pollMs)
  return stop
}

/** Sekme yeniden açıldı: arka plan izlemesini bırak (sekme kendisi izler). Döner: izleme vardı mı. */
export function takeBack(monitorId, scanId) {
  const w = watchers.get(keyOf(monitorId, scanId))
  if (!w) return false
  w.stop()
  return true
}

/** Testler için: tüm durumu sıfırla. */
export function resetWatchers() {
  for (const w of [...watchers.values()]) w.stop()
  watchers.clear()
  started.clear()
}
