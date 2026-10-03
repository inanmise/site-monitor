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
        push_individual: true,   // 2026-10-03: alan yüklenmese de varsayılan açık gönderilir
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
   * 2026-10-03 (kullanıcı kararı "push bildirimlerini alarm fırtınasına devretmeyelim"): anahtar yüklenen değeri gösterir
   * (alan yoksa varsayılan AÇIK), yardım ipucu ve saatlik tavan notu durur, kapatınca bilgi bandı çıkar ve kayıt
   * `push_individual:false` gönderir. Mevcut anahtar sırası korunur: [etkin, grup, push].
   */
  it('push fırtınaya devredilmesin anahtarı: varsayılan açık, yardım + tavan notu, kapatınca bant ve payload push_individual:false', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })   // push_individual alanı YOK → açık
    api.monitoring.storm.saveSettings.mockResolvedValue({ success: true, data: { ...cfg, push_individual: false } })
    const { container } = render(<StormSettings />)
    const sw = await screen.findByRole('switch', { name: /hand push notifications over|push bildirimleri fırtınaya/i })
    expect(sw).toBeChecked()
    expect(screen.getAllByRole('switch')[2]).toBe(sw)
    const card = container.querySelector('[data-slot="storm-push-individual"]')
    expect(card).toHaveAttribute('data-state', 'on')
    expect(card.textContent).toMatch(/hourly cap per user|kişi başı saat tavanı/i)
    expect(card.querySelector('[data-slot="alert"]')).toBeNull()

    // Yardım ipucu (help.set.site.monitor.storm.push-individual) — üç satır, ekranda açılır
    fireEvent.click(within(card).getByRole('button', { name: /hand push notifications over|push bildirimleri fırtınaya/i }))
    expect((await screen.findByRole('tooltip')).textContent).toMatch(/recommended|önerilen değer/i)

    fireEvent.click(sw)
    expect(sw).not.toBeChecked()
    expect(card).toHaveAttribute('data-state', 'off')
    expect(card.querySelector('[data-slot="alert"][data-tone="info"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await waitFor(() => expect(api.monitoring.storm.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ push_individual: false, per_group: false, quiet_minutes: 30 })))
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

  it('boş durumda API hatası toast atar, çökmez', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: false, error: 'boom' })
    render(<StormSettings />)
    await waitFor(() => expect(api.monitoring.storm.getSettings).toHaveBeenCalled())
    // yükleme başarısız → yine de kontroller varsayılanlarla render olur (çökme yok)
    expect((await screen.findAllByRole('spinbutton'))[0]).toBeInTheDocument()
  })
})
