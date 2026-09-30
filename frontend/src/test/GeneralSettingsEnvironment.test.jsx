import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import GeneralSettings from '../components/admin/GeneralSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getGeneralSettings:  vi.fn(),
      saveGeneralSettings: vi.fn(),
    },
  }),
}))
vi.mock('../components/VersionPopover.jsx', () => ({ invalidateVersionCache: vi.fn() }))
import { api } from '../api/client'
import { invalidateVersionCache } from '../components/VersionPopover.jsx'

/**
 * Ayarlar → Genel Ayarlar → "Ortam adı" (2026-09-29). Fixture'lar GERÇEK tel biçiminde: katalog kalemi
 * snake_case (`global_only`, `read_only`, `effective_source`); `value` override yokken Helm varsayılanına düşer
 * (AppSettingsService.getCatalogForClient) — kutu yine de BOŞ görünmeli ("boş = Helm değeri").
 */
const KEY = 'site.monitor.environment'
const envItem = (over = {}) => ({
  key: KEY, group: 'general', type: 'STRING', value: 'staging', default: 'staging', overridden: false,
  global_only: true, read_only: false, effective: 'staging', effective_source: 'env', ...over,
})
const OTHER = { key: 'site.monitor.app.base-url', group: 'general', type: 'STRING', value: 'https://monitor.example.com', default: '', overridden: true, global_only: true, read_only: false }

const saveBtn = () => screen.queryByRole('button', { name: /^(save|kaydet)$/i })
const field = (c) => c.querySelector(`[data-setting-key="${KEY}"]`)
const envInput = (c) => field(c).querySelector('input[data-slot="input"]')
const preset = (c, name) => within(field(c)).getByRole('button', { name })

async function mount(items) {
  api.admin.getGeneralSettings.mockResolvedValue({ success: true, data: items })
  const utils = render(<GeneralSettings />)
  await waitFor(() => expect(saveBtn()).not.toBeNull())
  return utils
}

describe('GeneralSettings — Ortam adı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.saveGeneralSettings.mockResolvedValue({ success: true, data: [envItem(), OTHER] })
  })

  it('etiket + kutu (override yokken BOŞ) + hazır seçenekler + "boş = Helm" ipucu + geçerli ad ve KAYNAK rozeti', async () => {
    const { container } = await mount([envItem(), OTHER])
    expect(screen.getAllByLabelText(/^(Ortam adı|Environment name)/)).toContain(envInput(container))
    expect(envInput(container).value).toBe('')
    for (const p of ['dev', 'staging', 'prod']) expect(preset(container, p)).toHaveAttribute('aria-pressed', 'false')
    expect(field(container).textContent).toMatch(/APP_ENVIRONMENT/)
    const eff = field(container).querySelector('[data-slot="env-effective"]')
    expect(eff.querySelector('[data-env="staging"]')).not.toBeNull()
    expect(eff.querySelector('[data-source="env"]')).toHaveTextContent(/^Helm \(APP_ENVIRONMENT\)$/)
    // Boş kutuda "Boşalt" düğmesi yok
    expect(within(field(container)).queryByRole('button', { name: /boşalt|clear/i })).toBeNull()
  })

  it('otomatik kaynak: "unknown" rozeti + "Otomatik"', async () => {
    const { container } = await mount([envItem({ value: null, default: '', effective: 'unknown', effective_source: 'auto' })])
    const eff = field(container).querySelector('[data-slot="env-effective"]')
    expect(eff.querySelector('[data-env="unknown"]')).not.toBeNull()
    expect(eff.querySelector('[data-source="auto"]')).toHaveTextContent(/^(Otomatik|Automatic)$/)
  })

  it('hazır seçenek → kutu dolar, yalnız bu anahtar gönderilir; kayıttan sonra rozet "Ayarlardan" + sürüm penceresi önbelleği düşer', async () => {
    api.admin.saveGeneralSettings.mockResolvedValue({ success: true, data: [
      envItem({ value: 'prod', overridden: true, effective: 'prod', effective_source: 'setting' }), OTHER] })
    const { container } = await mount([envItem(), OTHER])

    fireEvent.click(preset(container, 'prod'))
    expect(envInput(container).value).toBe('prod')
    expect(preset(container, 'prod')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(saveBtn())

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalledWith({ values: { [KEY]: 'prod' } }))
    await waitFor(() => expect(field(container).querySelector('[data-source="setting"]')).not.toBeNull())
    expect(field(container).querySelector('[data-source="setting"]')).toHaveTextContent(/^(Ayarlardan|From settings)$/)
    expect(field(container).querySelector('[data-env="prod"]')).not.toBeNull()
    expect(envInput(container).value).toBe('prod')
    expect(invalidateVersionCache).toHaveBeenCalledTimes(1)
  })

  it('başka bir ayar kaydedilince sürüm penceresi önbelleği DÜŞMEZ', async () => {
    const { container } = await mount([envItem(), OTHER])
    const other = container.querySelector('[data-setting-key="site.monitor.app.base-url"] input')
    fireEvent.change(other, { target: { value: 'https://x.example.com' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    expect(invalidateVersionCache).not.toHaveBeenCalled()
  })

  it('serbest değer yazılırken küçük harfe çevrilir', async () => {
    const { container } = await mount([envItem()])
    fireEvent.change(envInput(container), { target: { value: 'PreProd-2' } })
    expect(envInput(container).value).toBe('preprod-2')
  })

  it('geçersiz ad → alan hatası (aria-invalid + ileti), kayıt GÖNDERİLMEZ, hata bildirimi', async () => {
    const { container } = await mount([envItem()])
    fireEvent.change(envInput(container), { target: { value: 'prod_eu' } })
    expect(envInput(container)).toHaveAttribute('aria-invalid', 'true')
    const msg = /küçük harf, rakam|lower-case letters, digits/
    expect(field(container).textContent).toMatch(msg)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(screen.getAllByText(msg).length).toBeGreaterThan(1))   // alan + bildirim
    expect(api.admin.saveGeneralSettings).not.toHaveBeenCalled()
  })

  it('sunucu reddederse (400) ileti gösterilir, yazılan değer KORUNUR', async () => {
    api.admin.saveGeneralSettings.mockResolvedValue({ success: false, error: 'Ortam adı geçersiz: sunucu reddetti' })
    const { container } = await mount([envItem()])
    fireEvent.change(envInput(container), { target: { value: 'prod' } })
    fireEvent.click(saveBtn())
    expect(await screen.findByText(/sunucu reddetti/)).toBeInTheDocument()
    expect(envInput(container).value).toBe('prod')
    expect(invalidateVersionCache).not.toHaveBeenCalled()
  })

  it('"Boşalt" override\'ı kaldırır: boş dize gönderilir (Helm değerine dönüş)', async () => {
    const { container } = await mount([envItem({ value: 'prod', overridden: true, effective: 'prod', effective_source: 'setting' })])
    expect(envInput(container).value).toBe('prod')
    fireEvent.click(within(field(container)).getByRole('button', { name: /boşalt|clear/i }))
    expect(envInput(container).value).toBe('')
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalledWith({ values: { [KEY]: '' } }))
  })

  it('yetkisiz (kapsamlı müdür, read_only): kutu + hazır seçenekler kilitli, Boşalt yok, "yalnız global yönetici" ipucu; geçerli ad yine görünür', async () => {
    const { container } = await mount([envItem({ value: 'prod', overridden: true, read_only: true, effective: 'prod', effective_source: 'setting' })])
    expect(envInput(container)).toBeDisabled()
    for (const p of ['dev', 'staging', 'prod']) expect(preset(container, p)).toBeDisabled()
    expect(within(field(container)).queryByRole('button', { name: /boşalt|clear/i })).toBeNull()
    expect(field(container).textContent).toMatch(/global (administrator|yönetici)/i)
    expect(field(container).querySelector('[data-env="prod"]')).not.toBeNull()
  })
})
