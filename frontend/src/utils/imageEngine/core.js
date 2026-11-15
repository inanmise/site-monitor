// Görsel motoru — çekirdek giriş (Worker ve ana iş parçacığı yedeği AYNI kodu koşar): iş akışı + tarayıcı platformu.
// Bu dosya ve içe aktardıkları YALNIZ Worker'dan ya da `import('./core.js')` ile yüklenir (ağır parça: nicemleyici,
// PNG yazıcı, fflate). Dinamik içe aktarma İÇERMEZ — Worker paketi tek dosya kalır.
//
// AVIF (2026-11-15): kodlayıcı ayrı bir Worker'dadır (`avifClient.js` yalnız ADRESİNİ alır); platforma yalnız motor
// Worker'ında eklenir. Ana iş parçacığı yedeğinde (`cooperative`) AVIF HİÇ sunulmaz: WASM kodlaması orada sayfayı
// saniyelerce dondururdu — platformda AVIF yöntemleri olmayınca iş akışı bugünkü gibi davranır.
import { processCore, supportCore } from './pipeline.js'
import { browserPlatform } from './platform.js'
import { avifAvailable, prepareAvif } from './avifClient.js'

/** Motor Worker'ının platformu: tarayıcı tuvali + AVIF kodlayıcı Worker'ı. */
const workerPlatform = Object.freeze({
  ...browserPlatform,
  avifAvailable: async () => avifAvailable(),
  prepareAvif,
})

/** TEK görseli işler (sözleşme §2). `env`: { signal?, onProgress?, cooperative? }. */
export function processInBrowser(blob, options, env = {}) {
  return processCore(blob, options, { ...env, platform: env.cooperative ? browserPlatform : workerPlatform })
}

/**
 * { ok, webp, avif } — bu iş parçacığında çözme / tuval çalışıyor mu, WebP kodlanabiliyor mu, AVIF denenebilir mi.
 * `inWorker` yalnız motor Worker'ından true gelir (ana iş parçacığı yedeğinde AVIF yoktur).
 */
export function browserSupport({ inWorker = false } = {}) {
  return supportCore(inWorker ? workerPlatform : browserPlatform)
}
