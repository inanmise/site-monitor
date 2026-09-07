import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import HeartbeatHistoryModal from '../components/admin/HeartbeatHistoryModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: { getHeartbeatTimeline: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Heartbeat geçmişi modalı — %1.51 kapsamla duruyordu.
 *
 * Bu denetimde buradaki `.catch` eksiği düzeltildi (ağ hatasında `setLoading(false)` hiç
 * çalışmıyor, modal sonsuza kadar "yükleniyor"da asılı kalıyordu) ama TESTİ YAZILMADI —
 * yani düzeltme korumasızdı. Testlerin odağı üç şey:
 *
 * 1. hata yolları (reject / {success:false}) sessiz kalmasın,
 * 2. kova durum eşikleri (`statusOf`: received=0 → missing, ≥0.9 → ok, ≥0.5 → partial,
 *    altı → low) — bunlar operatörün ekranda gördüğü TEK sinyal,
 * 3. aralık düğmesi yeni veri İSTESİN (istemezse ekran sessizce eski aralığı gösterir).
 */
const bucket = (start, received, expected) => ({ start, received, expected })

const timeline = (over = {}) => ({
  bucket_minutes: 5,
  buckets: [
    bucket('2026-09-07T10:00:00', 0, 12),    // missing
    bucket('2026-09-07T10:05:00', 12, 12),   // ok
    bucket('2026-09-07T10:10:00', 7, 12),    // partial
    bucket('2026-09-07T10:15:00', 2, 12),    // low
  ],
  ...over,
})

describe('HeartbeatHistoryModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getHeartbeatTimeline.mockResolvedValue({ success: true, data: timeline() })
  })

  it('zaman çizelgesi yüklenir ve her kova kendi durum sınıfını alır', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(api.admin.getHeartbeatTimeline).toHaveBeenCalled())

    // statusOf eşikleri: 0 → missing, 12/12 → ok, 7/12 → partial, 2/12 → low
    await waitFor(() => expect(document.querySelector('.hb-tl-missing')).not.toBeNull())
    expect(document.querySelector('.hb-tl-ok')).not.toBeNull()
    expect(document.querySelector('.hb-tl-partial')).not.toBeNull()
    expect(document.querySelector('.hb-tl-low')).not.toBeNull()
  })

  it('kayıp kova × işaretiyle gösterilir (renk körü kullanıcı için tek ayırt edici)', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('.hb-tl-x')).not.toBeNull())
  })

  it('api REJECT ederse hata bandı çizilir ve spinner kalıcı kalmaz', async () => {
    // Bu denetimde düzeltilen kusur: .catch yoktu, setLoading(false) hiç çalışmıyordu.
    api.admin.getHeartbeatTimeline.mockRejectedValue(new Error('Failed to fetch'))

    render(<HeartbeatHistoryModal onClose={() => {}} />)

    expect(await screen.findByText(/failed to fetch/i)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('.hb-modal-loading')).toBeNull())
  })

  it('{success:false} dönerse de hata bandı çizilir', async () => {
    api.admin.getHeartbeatTimeline.mockResolvedValue({ success: false, error: '403 Forbidden' })

    render(<HeartbeatHistoryModal onClose={() => {}} />)

    expect(await screen.findByText(/403 forbidden/i)).toBeInTheDocument()
  })

  it('aralık düğmesi YENİ veri ister (ekran sessizce eski aralıkta kalmasın)', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(api.admin.getHeartbeatTimeline).toHaveBeenCalledWith(1))

    const buttons = document.querySelectorAll('.fc-range-btn')
    expect(buttons.length).toBeGreaterThan(1)
    fireEvent.click(buttons[1])

    await waitFor(() => {
      const args = api.admin.getHeartbeatTimeline.mock.calls.map(c => c[0])
      expect(args.length).toBeGreaterThan(1)
      expect(args.at(-1)).not.toBe(1)
    })
  })

  it('kovaya tıklamak ayrıntıyı açar, tekrar tıklamak kapatır', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('.hb-tl-cell')).not.toBeNull())

    const cell = document.querySelector('.hb-tl-cell')
    fireEvent.click(cell)
    await waitFor(() => expect(document.querySelector('.hb-tl-selected')).not.toBeNull())

    fireEvent.click(document.querySelector('.hb-tl-selected'))
    await waitFor(() => expect(document.querySelector('.hb-tl-selected')).toBeNull())
  })

  it('Escape ve kapat düğmesi onClose çağırır', async () => {
    const onClose = vi.fn()
    render(<HeartbeatHistoryModal onClose={onClose} />)
    await waitFor(() => expect(api.admin.getHeartbeatTimeline).toHaveBeenCalled())

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()

    onClose.mockClear()
    fireEvent.click(screen.getByRole('button', { name: /close/i }))
    expect(onClose).toHaveBeenCalled()
  })

  it('boş zaman çizelgesinde çökmez', async () => {
    api.admin.getHeartbeatTimeline.mockResolvedValue({ success: true, data: { bucket_minutes: 5, buckets: [] } })
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('.hb-modal-loading')).toBeNull())
    expect(document.querySelector('.hb-tl-cell')).toBeNull()
  })
})
