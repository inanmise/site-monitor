import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import UserPushSettings from '../components/admin/UserPushSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams: vi.fn(),
      userPush: {
        getSettings: vi.fn(), saveSettings: vi.fn(), saveScopes: vi.fn(), sendTest: vi.fn(),
        getDeliveries: vi.fn(), getStats: vi.fn(), explain: vi.fn(), exportUrl: vi.fn(() => '/api/x'),
      },
    },
  }),
  formatDateSec: (s) => s ?? '',
}))
import { api } from '../api/client'

/**
 * Push ayarları (2026-10-04): saat tavanının yanında "tavana takılanları özetle" + özet aralığı + "kritikler tavana
 * takılmasın"; tekrar kuralının yanında "eskalasyon adımı push'u"; şablon düzenleyicisinde TR / EN sekmeleri (iki dilin
 * önizlemesi, İngilizce başlık); test gönderiminde dil (yalnız İngilizce seçilince gövdeye girer).
 */
const SETTINGS = {
  'site.monitor.userpush.enabled': 'true',
  'site.monitor.userpush.url': 'http://notify.example.com/api',
  'site.monitor.userpush.title': 'Site Monitor',
  'site.monitor.userpush.headers': [],
}
const saveBar = () => screen.getByRole('region', { name: /^(Kayıt durumu|Save status|Kaydedilmemiş değişiklikler|Unsaved changes)$/ })
const SECTION_IDS = ['conn', 'groups', 'scopes', 'quiet', 'weekly', 'templates', 'test', 'explain', 'log']

function stub(settings = SETTINGS) {
  api.admin.userPush.getSettings.mockResolvedValue({
    success: true,
    data: {
      settings, scopes: [],
      defaults: {
        templates: { down: '{seviye}: {ad} yanıt vermiyor.' },
        templates_en: { down: '{seviye}: {ad} is not responding.' },
        placeholders: ['seviye', 'ad'],
      },
      health: { enabled: true, circuit_open: false },
    },
  })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.admin.userPush.getStats.mockResolvedValue({ success: true, data: { windows: {} } })
  api.admin.userPush.getDeliveries.mockResolvedValue({ success: true, data: { deliveries: [], total: 0, page: 0, size: 25 } })
  api.admin.userPush.saveSettings.mockImplementation(async (body) => ({ success: true, data: { settings: body } }))
  api.admin.userPush.sendTest.mockResolvedValue({ success: true, data: { queued: 1, message: 'Test: SiteMonitor webhook test - 14:03' } })
}

describe('UserPushSettings — saat tavanı özeti, kritik muafiyeti, eskalasyon adımı push’u, EN şablonları', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stub()
    try { localStorage.setItem('sm.userpush.sections', JSON.stringify(Object.fromEntries(SECTION_IDS.map((k) => [k, true])))) } catch { /* yok */ }
  })

  it('tavan bloğu: özet varsayılan AÇIK, aralık 15, kritik muafiyeti KAPALI; değiştirip Kaydet → anahtarlar gider', async () => {
    render(<UserPushSettings />)
    const block = await waitFor(() => { const el = document.querySelector('[data-slot="userpush-overflow"]'); expect(el).not.toBeNull(); return el })
    const overflow = within(block).getByRole('switch', { name: /Summarise pushes held back by the limit/ })
    const critical = within(block).getByRole('switch', { name: /Critical alerts bypass the limit/ })
    expect(overflow).toBeChecked()
    expect(critical).not.toBeChecked()
    expect(within(block).getByRole('spinbutton')).toHaveValue(15)

    fireEvent.click(critical)
    fireEvent.change(within(block).getByRole('spinbutton'), { target: { value: '30' } })
    const step = screen.getByRole('switch', { name: /Escalation steps also push to the contact/ })
    expect(step).toBeChecked()
    fireEvent.click(step)
    fireEvent.click(within(saveBar()).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.admin.userPush.saveSettings).toHaveBeenCalled())
    const body = api.admin.userPush.saveSettings.mock.calls[0][0]
    expect(body['site.monitor.userpush.critical-bypass-cap']).toBe('true')
    expect(body['site.monitor.userpush.overflow-summary-minutes']).toBe('30')
    expect(body['site.monitor.escalation.step-push-enabled']).toBe('false')
  })

  it('şablonlar TR / EN sekmeleri: EN sekmesi İngilizce varsayılanı + önizlemesini ve İngilizce başlık alanını gösterir; EN değişikliği .en anahtarına yazılır', async () => {
    render(<UserPushSettings />)
    const tabs = await waitFor(() => { const el = document.querySelector('[data-slot="userpush-tpl-tabs"]'); expect(el).not.toBeNull(); return el })
    // TR (varsayılan sekme): Türkçe önizleme
    expect(screen.getByText(/KRİTİK: Örnek İzleme yanıt vermiyor\./)).toBeInTheDocument()
    const enTab = within(tabs).getByRole('tab', { name: 'English' })
    fireEvent.mouseDown(enTab)
    fireEvent.click(enTab)
    const en = await waitFor(() => { const el = document.querySelector('[data-slot="userpush-tpl-en"]'); expect(el).not.toBeNull(); return el })
    expect(within(en).getByText(/CRITICAL: Example monitor is not responding\./)).toBeInTheDocument()
    expect(within(en).getByRole('textbox', { name: /Title \(English\)/ })).toBeInTheDocument()
    fireEvent.change(within(en).getByLabelText('Unreachable (DOWN) (English)'), { target: { value: '{seviye} DOWN {ad}' } })
    expect(within(en).getByText('CRITICAL DOWN Example monitor')).toBeInTheDocument()
    fireEvent.click(within(saveBar()).getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(api.admin.userPush.saveSettings).toHaveBeenCalled())
    expect(api.admin.userPush.saveSettings.mock.calls[0][0]['site.monitor.userpush.template.down.en']).toBe('{seviye} DOWN {ad}')
  })

  it('test gönderimi: Türkçe gövde bugünküyle aynı (lang yok); İngilizce seçilince lang=en gider', async () => {
    render(<UserPushSettings />)
    await screen.findByRole('group', { name: 'Test language' })
    const input = screen.getByPlaceholderText('N00001')
    fireEvent.change(input, { target: { value: 'N00001' } })
    fireEvent.blur(input)
    fireEvent.click(screen.getByRole('button', { name: /Send test/ }))
    await waitFor(() => expect(api.admin.userPush.sendTest).toHaveBeenCalledTimes(1))
    expect(api.admin.userPush.sendTest.mock.calls[0][0]).not.toHaveProperty('lang')

    fireEvent.click(within(screen.getByRole('group', { name: 'Test language' })).getByRole('button', { name: 'English' }))
    fireEvent.click(screen.getByRole('button', { name: /Send test/ }))
    await waitFor(() => expect(api.admin.userPush.sendTest).toHaveBeenCalledTimes(2))
    expect(api.admin.userPush.sendTest.mock.calls[1][0]).toMatchObject({ lang: 'en' })
  })
})
