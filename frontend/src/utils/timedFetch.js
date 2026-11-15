/**
 * Süre sınırlı ağ çağrısı yardımcıları (2026-11-15, kullanıcı kuralı "zaman aşımı olmayan istek / bekleme yok").
 *
 * <p>`api/client.js` ve onun DIŞINDA ham ağ çağrısı yapan birkaç modül (denetim dışa aktarımı, çökme bildirimi, PDF
 * yazı tipi / logo) aynı kuralı kullanır: son tarih YANIT GÖVDESİ okunana dek sürer ({@link timedFetch}); bir söz
 * sinyalle yarıştırılır ({@link raceSignal}) — sinyali dinlemeyen taşıyıcı / gövde okuması da asılı kalamaz. Kaynak
 * kapısı `clientTimeoutGate.test.js` sinyalsiz ham `fetch(` ve sıfır `timeoutMs` görürse düşer.
 *
 * <p>Bu modül React'e ve `api/client.js`'e bağlı DEĞİLDİR (test sahteleri `../api/client`'i değiştirse de burası çalışır).
 */

/** {@link timedFetch} için açık süre verilmediğinde (ya da ≤ 0 verildiğinde) kullanılan sınır. */
export const FALLBACK_TIMEOUT_MS = 15000

/**
 * Modül düzeyindeki "süren isteğe katıl" önbelleklerinin (çevrimiçi sayısı, Panom kutuları, bakım rozeti, 7/24 durumu)
 * ikinci savunma hattı (2026-11-15): okuma süresinin (90 sn) iki katından YAŞLI bir süren isteğe artık katılınmaz, yeni
 * istek başlatılır — bir istek bir hata yüzünden hiç sonuçlanmasa da yoklama sonsuza dek takılmaz. Geç gelen eski
 * yanıt sıra / kuşak denetimiyle yazılmaz.
 */
export const INFLIGHT_JOIN_MAX_MS = 180_000

/** İptal ya da süre sınırı hatası mı (fetch, gövde okuması, yarış)? */
export function isAbortish(e) {
  return e?.name === 'AbortError' || e?.name === 'TimeoutError'
}

/** Sinyal kesildiğinde reddedilecek hata: süre sınırı `TimeoutError`, öteki her neden (adsız neden dahil) `AbortError`. */
export function abortErrorFor(signal) {
  const r = signal?.reason
  if (r && (r.name === 'TimeoutError' || r.name === 'AbortError')) return r
  try { return new DOMException('aborted', 'AbortError') } catch { return Object.assign(new Error('aborted'), { name: 'AbortError' }) }
}

/**
 * Bir sözü sinyalle yarıştırır (2026-11-15): sinyal önce kesilirse söz BEKLENMEZ. Gerçek tarayıcı iptalde gövde akışını da
 * keser; sinyali dinlemeyen bir taşıyıcı / gövde okuması (sahte, eski tarayıcı) yine de asılı kalamaz. Geç gelen sonuç
 * yutulur (işlenmemiş ret yok).
 */
export function raceSignal(promise, signal) {
  const p = Promise.resolve(promise)
  if (!signal) return p
  return new Promise((resolve, reject) => {
    if (signal.aborted) { p.catch(() => {}); reject(abortErrorFor(signal)); return }
    const onAbort = () => reject(abortErrorFor(signal))
    signal.addEventListener('abort', onAbort, { once: true })
    p.then(
      (v) => { signal.removeEventListener('abort', onAbort); resolve(v) },
      (e) => { signal.removeEventListener('abort', onAbort); reject(e) },
    )
  })
}

/**
 * Yanıtın, gövde okumaları son tarihle sınırlı görünümü: `json` / `blob` / `text` / `arrayBuffer` son tarihle yarışır ve
 * okuma bitince zamanlayıcı söker; `done()` gövde okunmayan yolda zamanlayıcıyı söker. Başlık alanları (ok, status,
 * headers) özgün yanıttan kopyalanır — Response'un yöntemleri kendi nesnesinde çağrılır (marka denetimi).
 */
function boundedResponse(res, dl) {
  const read = (method) => {
    const fn = res?.[method]
    if (typeof fn !== 'function') { dl.done(); return Promise.reject(new TypeError(`response.${method} is not available`)) }
    let p
    try { p = fn.call(res) } catch (e) { dl.done(); return Promise.reject(e) }
    return raceSignal(p, dl.signal).finally(dl.done)
  }
  return {
    ok: res?.ok, status: res?.status, statusText: res?.statusText, headers: res?.headers, url: res?.url, redirected: res?.redirected,
    json: () => read('json'), blob: () => read('blob'), text: () => read('text'), arrayBuffer: () => read('arrayBuffer'),
    done: dl.done,
  }
}

/**
 * Süre sınırlı fetch (2026-11-15, kullanıcı kuralı "zaman aşımsız istek yok"): son tarih YANIT GÖVDESİ okunana dek sürer.
 * Eski `fetchWithTimeout` zamanlayıcıyı başlıklar gelince sökerdi — başlıklardan sonra duran bir bağlantı (yarı açık
 * soket) `res.json()`'ı sonsuza dek bekletiyordu; bekleyen ortak istekler (çevrimiçi sayısı, Panom kutuları, bakım rozeti,
 * 7/24 durumu) sekmenin ömrü boyunca asılı kalıyordu. Çağıranın sinyali (`init.signal`) korunur ve son tarihle bağlanır:
 * hangisi önce gelirse istek kesilir (ayrımı `api/client.js` `abortKind` yapar). `timeoutMs` ≤ 0 → {@link FALLBACK_TIMEOUT_MS} (sınırsız
 * istek yok). `transport` (XMLHttpRequest yükleme) aynı sinyali alır.
 *
 * @returns {Promise<ReturnType<typeof boundedResponse>>} reddi: ağ hatası (TypeError) ya da `AbortError` / `TimeoutError`
 */
export async function timedFetch(url, init = {}, timeoutMs = FALLBACK_TIMEOUT_MS, { transport = null } = {}) {
  const ms = timeoutMs > 0 ? timeoutMs : FALLBACK_TIMEOUT_MS
  const dl = deadlineSignal(init.signal || null, ms)
  let res
  try {
    const call = { ...init, signal: dl.signal }
    res = await raceSignal(transport ? transport(url, call) : fetch(url, { ...init, signal: dl.signal }), dl.signal)
  } catch (e) {
    dl.done()
    throw e
  }
  return boundedResponse(res, dl)
}

/**
 * Çağıranın iptal sinyali + üst süre sınırı TEK sinyalde (2026-10-02, HTTP tanılama). `request()` timeoutMs verildiğinde
 * kendi denetleyicisini kurup çağıranın sinyalini ezdiği için uzun ve iptal edilebilir çağrılar timeoutMs YERİNE bunu
 * kullanır: hangisi önce gelirse istek kesilir (AbortError → request'in yumuşak hata yükü). `done()` zamanlayıcıyı ve
 * dinleyiciyi söker.
 */
export function deadlineSignal(signal, ms) {
  if (typeof AbortController === 'undefined') return { signal, done: () => {} }
  const c = new AbortController()
  // Neden taşınır (2026-10-08): süre dolması "zaman aşımı", çağıranın iptali "iptal edildi" metnini alsın (abortKind).
  const timeoutReason = () => {
    try { return new DOMException('deadline', 'TimeoutError') } catch { return Object.assign(new Error('deadline'), { name: 'TimeoutError' }) }
  }
  const timer = setTimeout(() => c.abort(timeoutReason()), ms)
  const onAbort = () => c.abort(signal.reason)
  if (signal) {
    if (signal.aborted) c.abort(signal.reason)
    else signal.addEventListener('abort', onAbort, { once: true })
  }
  return { signal: c.signal, done: () => { clearTimeout(timer); signal?.removeEventListener?.('abort', onAbort) } }
}
