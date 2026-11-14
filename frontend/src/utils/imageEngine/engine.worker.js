// Görsel motoru — Web Worker girişi. İleti: { id, kind: 'support' } ya da { id, kind: 'process', blob, options }.
// Yanıt: { id, type: 'support', support } | { id, type: 'progress', phase, fraction } | { id, type: 'result', result }
// | { id, type: 'error', code, params }. İptal: ana iş parçacığı Worker'ı SONLANDIRIR (iş yarıda kesilir, yenisi açılır).
// İleti yalnız sayı / kod taşır — görselin baytları yalnız `result.blob` içinde ana iş parçacığına döner, hiçbir yere gitmez.
import { browserSupport, processInBrowser } from './core.js'
import { toEngineError } from './errors.js'

self.onmessage = async (e) => {
  const m = e.data || {}
  const id = m.id
  if (m.kind === 'support') {
    let support = { ok: false, webp: false }
    try { support = await browserSupport() } catch { /* destek yok say */ }
    self.postMessage({ id, type: 'support', support })
    return
  }
  if (m.kind !== 'process') return
  try {
    const result = await processInBrowser(m.blob, m.options, {
      onProgress: (phase, fraction) => { try { self.postMessage({ id, type: 'progress', phase, fraction }) } catch { /* yok say */ } },
    })
    self.postMessage({ id, type: 'result', result })
  } catch (err) {
    const ee = toEngineError(err)
    self.postMessage({ id, type: 'error', code: ee.code, params: ee.params })
  }
}
