import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import StormSettings from '../components/admin/StormSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    monitoring: {
      storm: {
        getSettings: vi.fn(),
        saveSettings: vi.fn(),
      },
    },
  }),
}))
import { api } from '../api/client'

const cfg = {
  enabled: true, threshold_unit: 'COUNT', threshold_value: 5,
  window_minutes: 5, per_group: false, quiet_minutes: 30, total_active_monitors: 42, effective_threshold: 5,
}

describe('StormSettings', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('ayarları yükler; eşik değeri + master toggle görünür', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })
    render(<StormSettings />)
    await waitFor(() => expect(api.monitoring.storm.getSettings).toHaveBeenCalled())
    const num = (await screen.findAllByRole('spinbutton'))[0]   // eşik sayı input'u (ikincisi sessiz pencere, 2026-09-30)
    expect(num).toHaveValue(5)
    const toggles = screen.getAllByRole('switch')            // [enabled, per_group] — shadcn Switch
    expect(toggles[0]).toBeChecked()                         // enabled=true
    expect(toggles[1]).not.toBeChecked()                     // per_group=false
    // Zaman penceresi: shadcn Slider başparmağı adlı + okunur değerli
    const thumb = screen.getByRole('slider')
    expect(thumb).toHaveAttribute('aria-valuenow', '5')
    expect(thumb.getAttribute('aria-valuetext')).toMatch(/5/)
  })

  it('master kapatılınca uyarı bandı çıkar; pencere klavyeyle değişir ve kayda gider', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })
    api.monitoring.storm.saveSettings.mockResolvedValue({ success: true, data: cfg })
    const { container } = render(<StormSettings />)
    await screen.findAllByRole('spinbutton')
    expect(container.querySelector('[data-slot="alert"][data-tone="warning"]')).toBeNull()
    fireEvent.click(screen.getAllByRole('switch')[0])
    expect(container.querySelector('[data-slot="alert"][data-tone="warning"]')).not.toBeNull()
    fireEvent.keyDown(screen.getByRole('slider'), { key: 'ArrowRight' })
    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '6')
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await waitFor(() => expect(api.monitoring.storm.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ enabled: false, window_minutes: 6 })))
  })

  it('Kaydet → doğru payload ile saveSettings çağırır', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })
    api.monitoring.storm.saveSettings.mockResolvedValue({ success: true, data: cfg })
    render(<StormSettings />)
    await waitFor(() => expect(api.monitoring.storm.getSettings).toHaveBeenCalled())

    fireEvent.click(await screen.findByRole('button', { name: /kaydet|save/i }))

    await waitFor(() => expect(api.monitoring.storm.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({
        enabled: true, threshold_unit: 'COUNT', threshold_value: 5, window_minutes: 5, per_group: false,
        push_individual: false,   // 2026-10-04: alan yüklenmese de varsayılan KAPALI gönderilir (push fırtınaya devredilir)
      }),
    ))
  })

  /**
   * 2026-09-30 (prod olayı): fırtına ömür sınırı — sessiz pencere alanı yüklenir, hazır değer düğmesiyle değişir,
   * kayda gider; aralık dışı değer (2 dk) sunucuya gitmeden reddedilir.
   */
  it('sessiz pencere: yüklenen değer görünür, hazır değer düğmesi payload\'a gider, aralık dışı değer kaydı durdurur', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })
    api.monitoring.storm.saveSettings.mockResolvedValue({ success: true, data: { ...cfg, quiet_minutes: 60 } })
    render(<StormSettings />)
    const quiet = await screen.findByRole('spinbutton', { name: /sessiz pencere|quiet window/i })
    expect(quiet).toHaveValue(30)
    // Hazır değer 60 dk — aria-pressed ile seçili
    const preset60 = screen.getByRole('button', { name: /^60 (dk|min)$/ })
    fireEvent.click(preset60)
    expect(quiet).toHaveValue(60)
    expect(preset60).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await waitFor(() => expect(api.monitoring.storm.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ quiet_minutes: 60 })))

    // Aralık dışı (2 dk): istemci doğrulaması — saveSettings yeniden ÇAĞRILMAZ
    api.monitoring.storm.saveSettings.mockClear()
    fireEvent.change(quiet, { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await new Promise((r) => setTimeout(r, 20))
    expect(api.monitoring.storm.saveSettings).not.toHaveBeenCalled()
  })

  /**
   * 2026-10-03 anahtarı; 2026-10-04 kullanıcı kararı "push'un fırtınaya devredilmesi default olsun": alan yoksa varsayılan
   * KAPALI (push fırtınaya devredilir, bilgi bandı görünür); yardım ipucu ve saatlik tavan notu durur, açınca bant kalkar ve
   * kayıt `push_individual:true` gönderir. Mevcut anahtar sırası korunur: [etkin, grup, push].
   */
  it('push fırtınaya devredilmesin anahtarı: varsayılan kapalı + bant, yardım + tavan notu, açınca payload push_individual:true', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })   // push_individual alanı YOK → kapalı
    api.monitoring.storm.saveSettings.mockResolvedValue({ success: true, data: { ...cfg, push_individual: true } })
    const { container } = render(<StormSettings />)
    const sw = await screen.findByRole('switch', { name: /hand push notifications over|push bildirimleri fırtınaya/i })
    expect(sw).not.toBeChecked()
    expect(screen.getAllByRole('switch')[2]).toBe(sw)
    const card = container.querySelector('[data-slot="storm-push-individual"]')
    expect(card).toHaveAttribute('data-state', 'off')
    expect(card.textContent).toMatch(/hourly cap per user|kişi başı saat tavanı/i)
    expect(card.querySelector('[data-slot="alert"][data-tone="info"]')).not.toBeNull()

    // Yardım ipucu (help.set.site.monitor.storm.push-individual) — üç satır, ekranda açılır
    fireEvent.click(within(card).getByRole('button', { name: /hand push notifications over|push bildirimleri fırtınaya/i }))
    expect((await screen.findByRole('tooltip')).textContent).toMatch(/recommended|önerilen değer/i)

    fireEvent.click(sw)
    expect(sw).toBeChecked()
    expect(card).toHaveAttribute('data-state', 'on')
    expect(card.querySelector('[data-slot="alert"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await waitFor(() => expect(api.monitoring.storm.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ push_individual: true, per_group: false, quiet_minutes: 30 })))
  })

  it('push anahtarı sunucudaki KAPALI değeri gösterir ve dokunulmadan kaydedilince false gider', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: { ...cfg, push_individual: false } })
    api.monitoring.storm.saveSettings.mockResolvedValue({ success: true, data: { ...cfg, push_individual: false } })
    render(<StormSettings />)
    const sw = await screen.findByRole('switch', { name: /hand push notifications over|push bildirimleri fırtınaya/i })
    expect(sw).not.toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await waitFor(() => expect(api.monitoring.storm.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ push_individual: false })))
  })

  // Yüklenemeyen ayar formu ÇİZİLMEZ (2026-10-09): eskiden hata sonrası sabit varsayılanlar kayıtlıymış gibi görünüyor ve
  // Kaydet gerçek yapılandırmanın üstüne yazabiliyordu. Artık hata bloğu + Tekrar dene; form yalnız başarılı okumadan sonra.
  it('yükleme başarısız (success:false): form ve Kaydet YOK, sunucu iletisi + Tekrar dene; yeniden denemede form gelir', async () => {
    api.monitoring.storm.getSettings.mockResolvedValueOnce({ success: false, error: 'Sunucu ayarları okuyamadı (deneme)' })
    render(<StormSettings />)
    const block = await screen.findByRole('alert')
    expect(block).toHaveTextContent('Sunucu ayarları okuyamadı (deneme)')
    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: /^(kaydet|save)$/i })).toBeNull()
    expect(api.monitoring.storm.saveSettings).not.toHaveBeenCalled()

    api.monitoring.storm.getSettings.mockResolvedValueOnce({ success: true, data: cfg })
    fireEvent.click(within(block).getByRole('button', { name: /Tekrar dene|Try again/ }))
    expect((await screen.findAllByRole('spinbutton'))[0]).toHaveValue(5)
    expect(api.monitoring.storm.getSettings).toHaveBeenCalledTimes(2)
    expect(screen.queryByText('Sunucu ayarları okuyamadı (deneme)')).toBeNull()
  })

  it('yükleme istisna fırlatırsa (ağ) da varsayılan form çizilmez; kaydetme istisnası hata bildirimi verir', async () => {
    api.monitoring.storm.getSettings.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı (ağ, deneme)'))
    const { unmount } = render(<StormSettings />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Sunucuya ulaşılamadı (ağ, deneme)')
    expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
    unmount()

    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })
    api.monitoring.storm.saveSettings.mockRejectedValueOnce(new Error('Kayıt zaman aşımına uğradı (deneme)'))
    render(<StormSettings />)
    await screen.findAllByRole('spinbutton')
    const save = screen.getByRole('button', { name: /^(kaydet|save)$/i })
    fireEvent.click(save)
    expect(await screen.findByText('Kayıt zaman aşımına uğradı (deneme)')).toBeInTheDocument()
    await waitFor(() => expect(save).not.toBeDisabled())   // "kaydediliyor" durumunda takılı kalmaz
  })
})
