import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { render, screen, waitFor } from './test-utils.jsx'
import { BrandingProvider, useAppVersion } from '../contexts/BrandingProvider.jsx'
import { currentVersion, resetRuntimeVersion, BUILD_VERSION } from '../utils/appVersion.js'
import { api } from '../api/client'

vi.mock('../api/client', () => ({ api: { getBranding: vi.fn() } }))

/**
 * Surum ARTIK derleme zamaninda gomulu DEGIL, calisma aninda sunucudan geliyor.
 *
 * Neden: `vite.config.js` kok VERSION'i okuyup `__APP_VERSION__` define'ina yaziyordu; bu deger
 * dev-server BASLARKEN bir kez cozuluyor ve HMR onu yeniden okumuyor. Bir surum yukseltmesinden
 * sonra dev-server yeniden baslatilmadikca arayuz BAYAT surum gosteriyordu — kullanici v20.26.1
 * gorurken depo v20.29.4'teydi (uc surum geride, 2026-08-24).
 */
describe('Uygulama surumu — calisma aninda sunucudan', () => {
  beforeEach(() => { resetRuntimeVersion(); api.getBranding.mockReset() })
  afterEach(() => resetRuntimeVersion())

  function Probe() { return <span data-testid="v">{useAppVersion()}</span> }

  it('branding yaniti gelince SUNUCUNUN surumu gosterilir (gomulu deger DEGIL)', async () => {
    api.getBranding.mockResolvedValue({ success: true, data: { app_version: '99.9.9' } })

    render(<BrandingProvider><Probe /></BrandingProvider>)

    await waitFor(() => expect(screen.getByTestId('v').textContent).toBe('99.9.9'))
    expect(screen.getByTestId('v').textContent).not.toBe(BUILD_VERSION)
  })

  it('sunucu okunamazsa gomulu YEDEGE duser (surum alani bos kalmaz)', async () => {
    api.getBranding.mockRejectedValue(new Error('ag yok'))

    render(<BrandingProvider><Probe /></BrandingProvider>)

    await waitFor(() => expect(screen.getByTestId('v').textContent).toBe(BUILD_VERSION))
  })

  it('sunucu "unknown" ya da bos donerse YEDEK korunur (anlamsiz deger ekrana yazilmaz)', async () => {
    api.getBranding.mockResolvedValue({ success: true, data: { app_version: 'unknown' } })

    render(<BrandingProvider><Probe /></BrandingProvider>)

    await waitFor(() => expect(api.getBranding).toHaveBeenCalled())
    expect(screen.getByTestId('v').textContent).toBe(BUILD_VERSION)
  })

  it('SENKRON okuma (hata yuzeyleri) sunucu degerini alir — context GEREKTIRMEDEN', async () => {
    // ErrorBoundary ve hata bildirimi context'e baglanamaz: provider yokken cokmemeleri gerekir.
    // Bir degeri de BEKLEYEMEZLER; o an ellerinde ne varsa onu yollarlar.
    expect(currentVersion()).toBe(BUILD_VERSION)
    api.getBranding.mockResolvedValue({ success: true, data: { app_version: '77.7.7' } })

    render(<BrandingProvider><Probe /></BrandingProvider>)

    await waitFor(() => expect(currentVersion()).toBe('77.7.7'))
  })

  it('SOZLESME: hicbir bilesen __APP_VERSION__ define\'ini DOGRUDAN kullanmaz', () => {
    // Tek kaynak `utils/appVersion.js`. Baska bir dosya define'i dogrudan okursa o yer yeniden
    // derleme-zamanina baglanir ve ayni bayatlik hatasi geri gelir.
    const walk = (dir, out = []) => {
      for (const e of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, e.name)
        if (e.isDirectory()) walk(p, out)
        else if (/\.(jsx?|tsx?)$/.test(e.name)) out.push(p)
      }
      return out
    }
    const offenders = walk('src')
      .filter(f => !f.includes('appVersion.js') && !f.includes(join('src', 'test')))
      .filter(f => readFileSync(f, 'utf8').includes('__APP_VERSION__'))

    expect(offenders).toEqual([])
  })
})
