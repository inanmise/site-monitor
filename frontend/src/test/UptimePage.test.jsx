import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import UptimePage from '../components/UptimePage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getUptimeOverview: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * UptimePage — YÜKLEME HATASI yüzeyi.
 *
 * Bu sayfanın hiç testi yoktu (kapsam %26.6). Denetimde çıkan kusur: diğer sekiz izleme
 * sayfasında `loadError` dalı VARDI, burada ve ScriptedMonitorPage'de HİÇ yoktu —
 * `if (res?.success)` başarısız olunca yalnız `setLoading(false)` koşuyor, `items` boş kalıyor
 * ve ekran "veri yok" diyordu. Kullanıcı kayıtlarının silindiğini sanıyordu.
 *
 * İkinci kusur aynı yerde: `request()` ağ hatasında `{success:false}` DÖNDÜRMEZ, `throw` eder.
 * try/catch olmadan promise reject oluyor ve `setLoading(false)` bile çalışmıyordu — ekran
 * sonsuza kadar iskelette kalıyordu. Testler bu yolu hiç kurmuyordu (apiMock yalnız resolve
 * eden yanıtlar üretir), bu yüzden ikisi de sessizdi.
 */
const row = {
  domain: 'example.com', status: 'up', http_ok: true, uptime_7d: 100, uptime_30d: 100,
  incidents_1d: 0, incidents_7d: 0, incidents_30d: 0, checked_at: '2026-06-24T00:00:00',
}

describe('UptimePage — yükleme hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [row] })
  })

  it('başarılı yanıtta satırı çizer (temel akış korunuyor)', async () => {
    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getUptimeOverview).toHaveBeenCalled())
    expect(await screen.findByText('example.com')).toBeInTheDocument()
  })

  it('{success:false} dönerse hata bandı çizilir, "veri yok" GÖRÜNMEZ', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: false, error: '500 Sunucu hatası' })

    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/500 sunucu hatası/i)).toBeInTheDocument()
    // Boş durum metni çıkmamalı: "silinmiş sanma" hatasının ta kendisi.
    expect(screen.queryByText(/veri yok|no data/i)).not.toBeInTheDocument()
  })

  it('api REJECT ederse hata bandı çizilir ve spinner kaybolur (sonsuz iskelet YOK)', async () => {
    api.monitoring.getUptimeOverview.mockRejectedValue(new Error('Failed to fetch'))

    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/failed to fetch/i)).toBeInTheDocument()
  })

  it('yeniden dene başarılı olunca hata bandı kalkar ve liste çizilir', async () => {
    api.monitoring.getUptimeOverview.mockRejectedValueOnce(new Error('Failed to fetch'))

    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i)

    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [row] })
    fireEvent.click(screen.getByRole('button', { name: /yeniden dene|retry/i }))

    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(screen.queryByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .not.toBeInTheDocument()
  })
})
