// Görsel motoru — çekirdek giriş (Worker ve ana iş parçacığı yedeği AYNI kodu koşar): iş akışı + tarayıcı platformu.
// Bu dosya ve içe aktardıkları YALNIZ Worker'dan ya da `import('./core.js')` ile yüklenir (ağır parça: nicemleyici,
// PNG yazıcı, fflate). Dinamik içe aktarma İÇERMEZ — Worker paketi tek dosya kalır.
import { processCore, supportCore } from './pipeline.js'
import { browserPlatform } from './platform.js'

/** TEK görseli işler (sözleşme §2). `env`: { signal?, onProgress?, cooperative? }. */
export function processInBrowser(blob, options, env = {}) {
  return processCore(blob, options, { ...env, platform: browserPlatform })
}

/** { ok, webp } — bu iş parçacığında çözme / tuval çalışıyor mu, WebP kodlanabiliyor mu. */
export function browserSupport() {
  return supportCore(browserPlatform)
}
