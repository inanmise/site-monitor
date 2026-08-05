import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils'

vi.mock('../api/client', () => {
  const K = (s) => 'site.monitor.branding.' + s
  const row = (key, type = 'STRING', value = '', def = '') =>
    ({ key: K(key), group: 'branding', type, value, default: def })
  const catalog = [
    row('app-name', 'STRING', '', 'Site Monitör'),
    row('tab-title', 'STRING', '', 'Site Monitör'),
    row('login-title'), row('login-subtitle'), row('signin-label'),
    row('username-label'), row('footer-text'), row('primary-color'),
    row('logo-data', 'TEXT'),
    row('banner-enabled', 'BOOL', 'false', 'false'),
    row('banner-text'), row('banner-link'), row('banner-link-label'),
    { key: K('banner-tone'), group: 'branding', type: 'ENUM', value: 'INFO', default: 'INFO', options: ['INFO', 'WARNING', 'CRITICAL'] },
    row('banner-version', 'INT', '0', '0'),
  ]
  return {
    api: {
      admin: {
        getBrandingSettings: vi.fn(async () => ({ success: true, data: catalog })),
        saveBrandingSettings: vi.fn(async () => ({ success: true, data: catalog, message: 'saved' })),
      },
    },
  }
})
import { api } from '../api/client'
import BrandingSettings from '../components/admin/BrandingSettings.jsx'

describe('BrandingSettings', () => {
  beforeEach(() => vi.clearAllMocks())

  it('kataloğu yükler ve iki kartı (beyaz etiket + duyuru) render eder', async () => {
    render(<BrandingSettings />)
    expect(await screen.findByText('White Label')).toBeInTheDocument()
    expect(screen.getByText('Announcement Banner')).toBeInTheDocument()
    expect(screen.getByText('App name')).toBeInTheDocument()
    expect(api.admin.getBrandingSettings).toHaveBeenCalledTimes(1)
  })

  it('yalnız değiştirilen key kaydedilir', async () => {
    render(<BrandingSettings />)
    await screen.findByText('White Label')
    const appName = screen.getByText('App name').closest('.threshold-field').querySelector('input')
    fireEvent.change(appName, { target: { value: 'Akbank Monitor' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => expect(api.admin.saveBrandingSettings).toHaveBeenCalledWith({
      values: { 'site.monitor.branding.app-name': 'Akbank Monitor' },
    }))
  })

  it('yükleme hatası crash etmez (toast gösterilir)', async () => {
    api.admin.getBrandingSettings.mockResolvedValueOnce({ success: false, error: 'boom' })
    render(<BrandingSettings />)
    await waitFor(() => expect(api.admin.getBrandingSettings).toHaveBeenCalled())
  })

  it('Reset to defaults: onay sonrası TÜM branding keyleri boş gönderilir (override temizleme)', async () => {
    render(<BrandingSettings />)
    await screen.findByText('White Label')
    fireEvent.click(screen.getByRole('button', { name: 'Reset to defaults' }))
    // Onay diyaloğu (DialogProvider) — confirm butonu da aynı adda; sonuncusu diyalogdaki
    const buttons = await screen.findAllByRole('button', { name: 'Reset to defaults' })
    fireEvent.click(buttons[buttons.length - 1])
    await waitFor(() => expect(api.admin.saveBrandingSettings).toHaveBeenCalled())
    const payload = api.admin.saveBrandingSettings.mock.calls[0][0]
    const values = payload.values
    expect(Object.keys(values).length).toBeGreaterThanOrEqual(14)
    expect(Object.values(values).every((v) => v === '')).toBe(true)
  })
})
