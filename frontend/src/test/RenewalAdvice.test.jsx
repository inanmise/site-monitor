import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import RenewalAdvice from '../components/RenewalAdvice.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    getRenewalAdvice: vi.fn(),
  }),
}))
import { api } from '../api/client'

/**
 * RenewalAdvice — YÜKLEME HATASI yüzeyi.
 *
 * Bu bileşenin hiç test dosyası yoktu. Denetimde çıkan kusur: `api.getRenewalAdvice().then(...)`
 * zincirinde `.catch` YOKTU ve çağrıya `timeoutMs` da verilmiyordu (varsayılan 0 = timeout yok).
 * `request()` ağ hatasında `{success:false}` DÖNDÜRMEZ, `throw` eder — dolayısıyla promise
 * reject olunca `setLoading(false)` hiç çalışmıyor, "Yenileme Önerileri" sekmesinde spinner
 * SONSUZA KADAR dönüyordu. Hata mesajı yoktu ve sekme değiştirip geri gelmeden düzelmiyordu.
 */
const item = {
  domain: 'example.com', priority: 'critical', days_remaining: 3,
  issuer: 'Test CA', not_after: '2026-09-10', advice: 'Yenileyin',
}

describe('RenewalAdvice — yükleme hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [item] })
  })

  it('başarılı yanıtta öneri kartını çizer (temel akış korunuyor)', async () => {
    render(<RenewalAdvice />)
    await waitFor(() => expect(api.getRenewalAdvice).toHaveBeenCalled())
    expect(await screen.findByText('example.com')).toBeInTheDocument()
  })

  it('api REJECT ederse hata bandı çizilir; spinner SONSUZA KADAR dönmez', async () => {
    api.getRenewalAdvice.mockRejectedValue(new Error('Failed to fetch'))

    render(<RenewalAdvice />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/failed to fetch/i)).toBeInTheDocument()
    // Spinner metni kalkmalı — kalıcı yükleme durumu bu hatanın ta kendisiydi.
    expect(screen.queryByText(/yükleniyor|loading/i)).not.toBeInTheDocument()
  })

  it('{success:false} dönerse hata bandı çizilir, "her şey yolunda" GÖRÜNMEZ', async () => {
    // Hata bandı boş durumun ÖNÜNDE olmalı: aksi halde yükleme hatası
    // "yenilenecek sertifika yok" gibi okunur ve kullanıcı yanlış rahatlar.
    api.getRenewalAdvice.mockResolvedValue({ success: false, error: '500 Sunucu hatası' })

    render(<RenewalAdvice />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    expect(screen.getByText(/500 sunucu hatası/i)).toBeInTheDocument()
  })

  it('gerçekten boş liste dönerse "her şey yolunda" gösterilir (hata bandı DEĞİL)', async () => {
    api.getRenewalAdvice.mockResolvedValue({ success: true, data: [] })

    render(<RenewalAdvice />)

    await waitFor(() => expect(api.getRenewalAdvice).toHaveBeenCalled())
    expect(screen.queryByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .not.toBeInTheDocument()
  })
})
