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

// ── Grup / etiket filtresi + kart çipleri (2026-09-18) ──
describe('UptimePage — grup/etiket filtresi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [
      { ...row, domain: 'odeme.example.com', group_name: 'Ödeme', tags: 'prod, kritik' },
      { ...row, domain: 'kampanya.example.com', group_name: 'Kampanya', tags: 'edge' },
      { ...row, domain: 'eski.example.com' },
    ] })
  })

  it('grup ve etiket kutuları listeden türer; etiket çipine tıklayınca liste o etikete süzülür; "Filtreleri temizle" geri getirir', async () => {
    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('odeme.example.com')
    const bar = document.querySelector('.upt-toolbar')
    const triggers = [...bar.querySelectorAll('.ss-trigger')].map((b) => b.textContent.trim())
    expect(triggers).toEqual(expect.arrayContaining([expect.stringMatching(/tüm gruplar|all groups/i), expect.stringMatching(/tüm etiketler|all tags/i)]))
    expect(screen.queryByText(/filtreleri temizle|clear filters/i)).toBeNull()

    // Kart çipi → etiket filtresi
    fireEvent.click([...document.querySelectorAll('.upt-card .inv-tag')].find((b) => b.textContent.trim() === 'kritik'))
    await waitFor(() => expect(screen.queryByText('kampanya.example.com')).toBeNull())
    expect(screen.getByText('odeme.example.com')).toBeInTheDocument()
    expect(screen.queryByText('eski.example.com')).toBeNull()

    fireEvent.click(screen.getByText(/filtreleri temizle|clear filters/i))
    expect(await screen.findByText('kampanya.example.com')).toBeInTheDocument()

    // Grup kutusu: "Grupsuz" eski kaydı bulur
    fireEvent.mouseDown([...bar.querySelectorAll('.ss-trigger')].find((b) => /tüm gruplar|all groups/i.test(b.textContent)))
    fireEvent.mouseDown([...bar.querySelectorAll('.ss-option')].find((o) => /grupsuz|no group/i.test(o.textContent)))
    await waitFor(() => expect(screen.queryByText('odeme.example.com')).toBeNull())
    expect(screen.getByText('eski.example.com')).toBeInTheDocument()
  })

  it('arama kutusu grup adı ve etikette de eşleşir', async () => {
    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('odeme.example.com')
    fireEvent.change(document.querySelector('.upt-search'), { target: { value: 'edge' } })
    await waitFor(() => expect(screen.queryByText('odeme.example.com')).toBeNull())
    expect(screen.getByText('kampanya.example.com')).toBeInTheDocument()
  })
})
