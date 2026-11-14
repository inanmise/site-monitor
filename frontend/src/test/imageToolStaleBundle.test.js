import { describe, it, expect, vi } from 'vitest'

// Görsel aracı — motor modülünün KENDİSİ yüklenemezse (dağıtımdan sonra eski sayfa: parça dosyası sunucuda yok) motor kendi
// "bayat paket" hatasını veremez; bağdaştırıcı aynı biçimde hata üretir ve desteği `staleBundle: true` bildirir.
vi.mock('../utils/imageEngine/index.js', () => { throw new TypeError('Failed to fetch dynamically imported module: /assets/index-eski.js') })

import { engineSupport, isEngineError, processImage, decodeForPreview, inspectImage } from '../components/page/resourcescan/imagetool/engineClient.js'

describe('engineClient — motor modülü yüklenemezse bayat paket', () => {
  it('destek staleBundle: true; işlemler BROWSER_UNSUPPORTED { reason: stale_bundle } fırlatır (ağ hatası metni değil)', async () => {
    expect(await engineSupport()).toEqual({ worker: false, webp: false, staleBundle: true })
    for (const call of [() => processImage(new Blob(['x']), {}, {}), () => decodeForPreview(new Blob(['x']), {}), () => inspectImage(new Blob(['x']))]) {
      const err = await call().then(() => null, (e) => e)
      expect(isEngineError(err)).toBe(true)
      expect(err).toMatchObject({ code: 'BROWSER_UNSUPPORTED', params: { reason: 'stale_bundle' } })
    }
  })
})
