// Görsel motoru — Web Worker girişi. İleti: { id, kind: 'support' } ya da { id, kind: 'process', blob, options, avifBlock? }.
// Yanıt: { id, type: 'support', support } | { id, type: 'progress', phase, fraction } | { id, type: 'result', result, avifBlock? }
// | { id, type: 'error', code, params, avifBlock? }. İptal: ana iş parçacığı Worker'ı SONLANDIRIR (iş yarıda kesilir, yenisi
// açılır). İleti yalnız sayı / kod taşır — görselin baytları yalnız `result.blob` içinde ana iş parçacığına döner.
// `avifBlock` (inceleme 2026-11-15): AVIF kodlayıcısının yakın zamanda yüklenemediği kaydı `{ until, detail }` — Worker boşta
// kapanıp yeniden açılsa da ana iş parçacığı sonraki işle geri yollar (her görsel yeniden 30 sn yükleme beklemesin).
import { browserSupport, getAvifBlock, processInBrowser, setAvifBlock } from './core.js'
import { toEngineError } from './errors.js'

const blockField = () => {
  const b = getAvifBlock()
  return b ? { avifBlock: b } : {}
}

self.onmessage = async (e) => {
  const m = e.data || {}
  const id = m.id
  if (m.kind === 'support') {
    let support = { ok: false, webp: false, avif: false }
    try { support = await browserSupport({ inWorker: true }) } catch { /* destek yok say */ }
    self.postMessage({ id, type: 'support', support })
    return
  }
  if (m.kind !== 'process') return
  if (m.avifBlock) { try { setAvifBlock(m.avifBlock) } catch { /* geçersiz kayıt yok sayılır */ } }
  try {
    const result = await processInBrowser(m.blob, m.options, {
      onProgress: (phase, fraction) => { try { self.postMessage({ id, type: 'progress', phase, fraction }) } catch { /* yok say */ } },
    })
    self.postMessage({ id, type: 'result', result, ...blockField() })
  } catch (err) {
    const ee = toEngineError(err)
    self.postMessage({ id, type: 'error', code: ee.code, params: ee.params, ...blockField() })
  }
}
