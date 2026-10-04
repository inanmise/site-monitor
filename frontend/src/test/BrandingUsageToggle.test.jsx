import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

const USAGE = 'site.monitor.public-stats.usage-enabled'

vi.mock('../api/client', () => {
  const K = (s) => 'site.monitor.branding.' + s
  const row = (key, type = 'STRING', value = '', def = '') => ({ key: K(key), group: 'branding', type, value, default: def })
  const catalog = (readOnly) => [
    row('app-name', 'STRING', '', 'SiteMonitor'),
    row('banner-enabled', 'BOOL', 'false', 'false'),
    row('banner-text'),
    { key: 'site.monitor.public-stats.usage-enabled', group: 'branding', type: 'BOOL', value: 'true', default: 'true',
      global_only: true, read_only: readOnly },
  ]
  return {
    api: withApiFallback({
      admin: {
        getBrandingSettings: vi.fn(async () => ({ success: true, data: catalog(false) })),
        saveBrandingSettings: vi.fn(async () => ({ success: true, data: catalog(false), message: 'saved' })),
        __catalog: catalog,
      },
    }),
  }
})
import { api } from '../api/client'
import BrandingSettings from '../components/admin/BrandingSettings.jsx'

/**
 * Marka → "Giriş Sayfası İstatistikleri" (2026-10-04): GLOBAL_ONLY anahtar `site.monitor.public-stats.usage-enabled`
 * shadcn Switch ile; kapatınca 'false' kaydedilir. Kapsamlı müdürde (read_only) anahtar kilitli + ipucu ve
 * "Varsayılanlara dön" o anahtarı GÖNDERMEZ (sunucu 403 ile tüm isteği düşürürdü).
 */
describe('BrandingSettings — giriş sayfası kullanım istatistikleri anahtarı', () => {
  beforeEach(() => vi.clearAllMocks())

  const usageSwitch = () => document.querySelector('[data-slot="branding-usage-toggle"] [role="switch"]')

  it('global yönetici: anahtar açık görünür; kapatınca yalnız o anahtar "false" olarak kaydedilir', async () => {
    render(<BrandingSettings />)
    expect(await screen.findByText('Sign-in Page Statistics')).toBeInTheDocument()
    const sw = usageSwitch()
    expect(sw).toHaveAccessibleName(/Show usage statistics/)
    expect(sw).toBeChecked()
    expect(sw).not.toBeDisabled()
    fireEvent.click(sw)
    expect(sw).not.toBeChecked()
    expect(screen.getByText(/shows only the number of monitored targets/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => expect(api.admin.saveBrandingSettings).toHaveBeenCalledWith({ values: { [USAGE]: 'false' } }))
  })

  it('kapsamlı müdür (read_only): anahtar kilitli + "yalnız global yönetici" ipucu; varsayılana dön anahtarı göndermez', async () => {
    api.admin.getBrandingSettings.mockResolvedValueOnce({ success: true, data: api.admin.__catalog(true) })
    render(<BrandingSettings />)
    await screen.findByText('Sign-in Page Statistics')
    expect(usageSwitch()).toBeDisabled()
    expect(screen.getByText(/only a global administrator can change/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reset to defaults' }))
    const buttons = await screen.findAllByRole('button', { name: 'Reset to defaults' })
    fireEvent.click(buttons[buttons.length - 1])
    await waitFor(() => expect(api.admin.saveBrandingSettings).toHaveBeenCalled())
    const { values } = api.admin.saveBrandingSettings.mock.calls[0][0]
    expect(values).not.toHaveProperty(USAGE)
    expect(values).toHaveProperty('site.monitor.branding.app-name', '')
  })
})
