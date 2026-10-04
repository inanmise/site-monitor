import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ me: {
    getPushPreferences: vi.fn(),
    savePushPreferences: vi.fn(),
    pushSnooze: vi.fn(),
    pushSelfTest: vi.fn(),
  } }),
}))
import { api } from '../api/client'
import PushPreferencesCard from '../components/myactivity/PushPreferencesCard.jsx'

/**
 * "Bildirim tercihlerim" kartı (2026-10-04, onaylı öneriler 4/5): susturma hazır seçenekleri ve durum, "kritikler yine
 * gelsin", en düşük seviye, izleme türleri (en az biri — alanın altında hata), push dili, kendine test (gönderildi /
 * ayarlanmamış / HTTP / 10 dk sınırı), opt-out notu, yükleme hatası.
 */
const FAMILIES = ['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted']
const DEFAULTS = {
  success: true, min_level: null, families: null, lang: 'tr', snooze_until: null, snooze_active: false,
  snooze_critical: true, opt_out: false, available_families: FAMILIES, server_now: '2026-10-04T11:00:00',
}
const card = () => within(document.querySelector('[data-slot="push-prefs"]'))
const loaded = () => screen.findByRole('button', { name: 'Save preferences' })

describe('PushPreferencesCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.me.getPushPreferences.mockResolvedValue({ ...DEFAULTS })
  })

  it('varsayılan: "All pushes" özeti, susturma yok, Kaydet kapalı (dokunulmamış = gönderim yok)', async () => {
    render(<PushPreferencesCard />)
    expect(await loaded()).toBeDisabled()
    expect(card().getByText('All pushes')).toBeInTheDocument()
    expect(card().getByText('Not snoozed — pushes arrive as usual.')).toBeInTheDocument()
    expect(card().getByRole('switch', { name: 'Still send critical alerts' })).toBeChecked()
    expect(api.me.savePushPreferences).not.toHaveBeenCalled()
  })

  it('susturma: "1 hour" anında kaydedilir (kritik seçimiyle); durum "Snoozed until …" ve "Stop snoozing" ile kapatılır', async () => {
    const until = new Date(Date.now() + 3600_000).toISOString().slice(0, 19)
    api.me.pushSnooze.mockResolvedValueOnce({ ...DEFAULTS, snooze_until: until, snooze_active: true })
    render(<PushPreferencesCard />)
    await loaded()
    fireEvent.click(card().getByRole('button', { name: '1 hour' }))
    await waitFor(() => expect(api.me.pushSnooze).toHaveBeenCalledWith({ preset: '1h', critical: true }))
    expect(await card().findByText(/^Snoozed until /, { selector: '[data-slot="push-snooze-state"]' })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="push-prefs"]')).toHaveAttribute('data-snoozed', 'true')

    api.me.pushSnooze.mockResolvedValueOnce({ ...DEFAULTS })
    fireEvent.click(card().getByRole('button', { name: 'Stop snoozing' }))
    await waitFor(() => expect(api.me.pushSnooze).toHaveBeenLastCalledWith({ preset: 'off' }))
    expect(await card().findByText('Not snoozed — pushes arrive as usual.')).toBeInTheDocument()
  })

  it('"Tomorrow 08:00" ve "4 hours" düğmeleri; "kritikler yine gelsin" anahtarı yalnız kritik seçimini gönderir', async () => {
    api.me.pushSnooze.mockResolvedValueOnce({ ...DEFAULTS }).mockResolvedValueOnce({ ...DEFAULTS, snooze_critical: false })
    render(<PushPreferencesCard />)
    await loaded()
    fireEvent.click(card().getByRole('button', { name: 'Tomorrow 08:00' }))
    await waitFor(() => expect(api.me.pushSnooze).toHaveBeenCalledWith({ preset: 'tomorrow', critical: true }))
    fireEvent.click(card().getByRole('switch', { name: 'Still send critical alerts' }))
    await waitFor(() => expect(api.me.pushSnooze).toHaveBeenLastCalledWith({ critical: false }))
    expect(card().getByRole('button', { name: '4 hours' })).toBeInTheDocument()
  })

  it('kaydedilmemiş seviye düzenlemesi susturma yanıtıyla SİLİNMEZ (Kaydet açık kalır)', async () => {
    api.me.pushSnooze.mockResolvedValue({ ...DEFAULTS, snooze_active: true, snooze_until: new Date(Date.now() + 3600_000).toISOString().slice(0, 19) })
    render(<PushPreferencesCard />)
    const save = await loaded()
    fireEvent.click(card().getByRole('button', { name: 'Critical only' }))
    expect(save).toBeEnabled()
    fireEvent.click(card().getByRole('button', { name: '1 hour' }))
    await waitFor(() => expect(document.querySelector('[data-slot="push-prefs"]')).toHaveAttribute('data-snoozed', 'true'))
    expect(card().getByRole('button', { name: 'Critical only' })).toHaveAttribute('aria-pressed', 'true')
    expect(card().getByRole('button', { name: 'Save preferences' })).toBeEnabled()
  })

  it('seviye + türler + dil değişince Kaydet açılır; gövde normalize (yalnız seçili aileler, dil en)', async () => {
    api.me.savePushPreferences.mockResolvedValue({ ...DEFAULTS, min_level: 'HIGH', families: ['cert', 'http'], lang: 'en' })
    render(<PushPreferencesCard />)
    const save = await loaded()
    fireEvent.click(card().getByRole('button', { name: 'High and above' }))
    for (const f of ['Domain', 'Ping', 'Port', 'DNS', 'Keyword', 'Page', 'Page Speed', 'Synthetic']) {
      fireEvent.click(card().getByRole('button', { name: f }))
    }
    fireEvent.click(card().getByRole('button', { name: 'English' }))
    expect(save).toBeEnabled()
    fireEvent.click(save)
    await waitFor(() => expect(api.me.savePushPreferences).toHaveBeenCalledWith({ min_level: 'HIGH', families: ['cert', 'http'], lang: 'en' }))
    expect(await screen.findByText('Your notification preferences were saved.')).toBeInTheDocument()
    expect(card().getByText('Custom preferences')).toBeInTheDocument()
  })

  it('hiç tür seçilmezse hata ALANIN ALTINDA, kayıt GİTMEZ; "Select all" geri yükler', async () => {
    render(<PushPreferencesCard />)
    const save = await loaded()
    for (const b of card().getAllByRole('button').filter((x) => x.hasAttribute('data-family'))) fireEvent.click(b)
    fireEvent.click(save)
    await waitFor(() => expect(document.querySelector('[data-field="families"]')).toHaveAttribute('data-invalid', 'true'))
    expect(card().getByText('Pick at least one monitor type.')).toBeInTheDocument()
    expect(api.me.savePushPreferences).not.toHaveBeenCalled()
    fireEvent.click(card().getByRole('button', { name: 'Select all' }))
    expect(card().queryByText('Pick at least one monitor type.')).toBeNull()
  })

  it('sunucu alan hatası (400 + field) alanın altında gösterilir', async () => {
    api.me.savePushPreferences.mockResolvedValue({ success: false, status: 400, field: 'lang', error: 'Push language must be tr or en.' })
    render(<PushPreferencesCard />)
    const save = await loaded()
    fireEvent.click(card().getByRole('button', { name: 'English' }))
    fireEvent.click(save)
    expect(await card().findByText('Push language must be tr or en.')).toBeInTheDocument()
    expect(document.querySelector('[data-field="lang"]')).toHaveAttribute('data-invalid', 'true')
  })

  it('kendime test: gönderildi / ayarlanmamış / HTTP kodu / 10 dk sınırı (429) sonuçları kartta', async () => {
    render(<PushPreferencesCard />)
    await loaded()
    const btn = card().getByRole('button', { name: 'Send me a test push' })
    const result = () => document.querySelector('[data-slot="push-self-test-result"]')

    api.me.pushSelfTest.mockResolvedValueOnce({ success: true, ok: true, outcome: 'OK', http_status: 200, channel_enabled: true })
    fireEvent.click(btn)
    await waitFor(() => expect(result()).toHaveAttribute('data-tone', 'success'))
    expect(result().textContent).toContain('Test push sent')

    api.me.pushSelfTest.mockResolvedValueOnce({ success: true, ok: false, outcome: 'NOT_CONFIGURED' })
    fireEvent.click(btn)
    await waitFor(() => expect(result().textContent).toContain("isn't configured"))

    api.me.pushSelfTest.mockResolvedValueOnce({ success: true, ok: false, outcome: 'HTTP', http_status: 502 })
    fireEvent.click(btn)
    await waitFor(() => expect(result().textContent).toContain('HTTP 502'))

    api.me.pushSelfTest.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED' })
    fireEvent.click(btn)
    await waitFor(() => expect(result().textContent).toContain('at most 3 attempts per 10 minutes'))
    expect(api.me.pushSelfTest).toHaveBeenCalledTimes(4)
  })

  it('opt-out açıkken tercihlerin etkisiz olduğu söylenir; susturma etkinse özet rozeti', async () => {
    api.me.getPushPreferences.mockResolvedValue({
      ...DEFAULTS, snooze_active: true, snooze_until: new Date(Date.now() + 7200_000).toISOString().slice(0, 19),
    })
    render(<PushPreferencesCard optOut />)
    await loaded()
    expect(document.querySelector('[data-slot="push-prefs-optout"]')).not.toBeNull()
    expect(within(document.querySelector('[data-slot="push-prefs-summary"]')).getByText(/^Snoozed until /)).toBeInTheDocument()
  })

  it('yükleme hatası: hata kartı + yeniden dene', async () => {
    api.me.getPushPreferences.mockResolvedValueOnce({ success: false })
    render(<PushPreferencesCard />)
    expect(await screen.findByText("Couldn't load your notification preferences")).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Retry|Try again/ }))
    expect(await loaded()).toBeInTheDocument()
  })
})
