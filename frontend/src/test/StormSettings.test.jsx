import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
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
  window_minutes: 5, per_group: false, total_active_monitors: 42, effective_threshold: 5,
}

describe('StormSettings', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('ayarları yükler; eşik değeri + master toggle görünür', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: cfg })
    render(<StormSettings />)
    await waitFor(() => expect(api.monitoring.storm.getSettings).toHaveBeenCalled())
    const num = await screen.findByRole('spinbutton')       // eşik sayı input'u
    expect(num).toHaveValue(5)
    const toggles = screen.getAllByRole('checkbox')          // [enabled, per_group]
    expect(toggles[0]).toBeChecked()                         // enabled=true
    expect(toggles[1]).not.toBeChecked()                     // per_group=false
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
      }),
    ))
  })

  it('boş durumda API hatası toast atar, çökmez', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: false, error: 'boom' })
    render(<StormSettings />)
    await waitFor(() => expect(api.monitoring.storm.getSettings).toHaveBeenCalled())
    // yükleme başarısız → yine de kontroller varsayılanlarla render olur (çökme yok)
    expect(await screen.findByRole('spinbutton')).toBeInTheDocument()
  })
})
