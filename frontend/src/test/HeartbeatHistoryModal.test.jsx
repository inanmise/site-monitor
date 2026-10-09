import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
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
    const cell = (s) => document.querySelector(`[data-hb-cell][data-status="${s}"]`)
    await waitFor(() => expect(cell('missing')).not.toBeNull())
    expect(cell('ok')).not.toBeNull()
    expect(cell('partial')).not.toBeNull()
    expect(cell('low')).not.toBeNull()
    // kovalar shadcn Button: klavyeyle erişilebilir, adı zamanı ve durumu taşır
    expect(cell('ok')).toHaveAttribute('data-slot', 'button')
    expect(cell('ok')).toHaveAccessibleName(/2026-09-07T10:05:00/)
  })

  it('kayıp kova × işaretiyle gösterilir (renk körü kullanıcı için tek ayırt edici)', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-hb-x]')).not.toBeNull())
  })

  it('api REJECT ederse hata bandı çizilir ve spinner kalıcı kalmaz', async () => {
    // Bu denetimde düzeltilen kusur: .catch yoktu, setLoading(false) hiç çalışmıyordu.
    api.admin.getHeartbeatTimeline.mockRejectedValue(new Error('Failed to fetch'))

    render(<HeartbeatHistoryModal onClose={() => {}} />)

    expect(await screen.findByText(/failed to fetch/i)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-slot="loading-block"]')).toBeNull())
  })

  it('{success:false} dönerse de hata bandı çizilir', async () => {
    api.admin.getHeartbeatTimeline.mockResolvedValue({ success: false, error: '403 Forbidden' })

    render(<HeartbeatHistoryModal onClose={() => {}} />)

    expect(await screen.findByText(/403 forbidden/i)).toBeInTheDocument()
  })

  it('aralık düğmesi YENİ veri ister (ekran sessizce eski aralıkta kalmasın)', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(api.admin.getHeartbeatTimeline).toHaveBeenCalledWith(1))

    const buttons = within(screen.getByRole('group', { name: /Zaman aralığı|Time range/ })).getAllByRole('button')
    expect(buttons.length).toBeGreaterThan(1)
    fireEvent.click(buttons[1])

    await waitFor(() => {
      const args = api.admin.getHeartbeatTimeline.mock.calls.map(c => c[0])
      expect(args.length).toBeGreaterThan(1)
      expect(args.at(-1)).not.toBe(1)
    })
  })

  // Geç yanıt yarışı (2026-10-09): 1 gün isteği yoldayken başka aralık seçilir; yeni aralığın yanıtı önce gelir. Eski
  // yanıt sonradan düşünce seçili aralığın çizelgesini EZMEMELİ (yalnız en son istek uygulanır).
  it('geç gelen ESKİ aralık yanıtı yeni aralığın çizelgesini ezmez', async () => {
    let resolveFirst
    api.admin.getHeartbeatTimeline
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockResolvedValueOnce({ success: true, data: timeline({ buckets: [bucket('2026-09-08T10:00:00', 12, 12)] }) })
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(api.admin.getHeartbeatTimeline).toHaveBeenCalledTimes(1))
    fireEvent.click(within(screen.getByRole('group', { name: /Zaman aralığı|Time range/ })).getAllByRole('button')[1])
    const cells = () => document.querySelectorAll('[data-hb-cell]')
    await waitFor(() => expect(cells()).toHaveLength(1))

    await act(async () => { resolveFirst({ success: true, data: timeline() }) })   // eski (4 kovalı) yanıt geç düşer
    expect(cells()).toHaveLength(1)
    expect(document.querySelector('[data-hb-cell][data-status="missing"]')).toBeNull()
  })

  it('kovaya tıklamak ayrıntıyı açar, tekrar tıklamak kapatır', async () => {
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-hb-cell]')).not.toBeNull())

    const cell = document.querySelector('[data-hb-cell]')
    fireEvent.click(cell)
    await waitFor(() => expect(document.querySelector('[data-hb-cell][aria-pressed="true"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="hb-detail"]')).not.toBeNull()

    fireEvent.click(document.querySelector('[data-hb-cell][aria-pressed="true"]'))
    await waitFor(() => expect(document.querySelector('[data-hb-cell][aria-pressed="true"]')).toBeNull())
    expect(document.querySelector('[data-slot="hb-detail"]')).toBeNull()
  })

  it('Escape ve kapat düğmesi onClose çağırır', async () => {
    const onClose = vi.fn()
    render(<HeartbeatHistoryModal onClose={onClose} />)
    await waitFor(() => expect(api.admin.getHeartbeatTimeline).toHaveBeenCalled())

    // ModalShell (Radix Dialog) Escape'i belge düzeyinde dinler — odaktaki öğeden gönderilir.
    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()

    onClose.mockClear()
    fireEvent.click(screen.getByRole('button', { name: /^(Kapat|Close)$/i }))
    expect(onClose).toHaveBeenCalled()
  })

  it('boş zaman çizelgesinde çökmez', async () => {
    api.admin.getHeartbeatTimeline.mockResolvedValue({ success: true, data: { bucket_minutes: 5, buckets: [] } })
    render(<HeartbeatHistoryModal onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="loading-block"]')).toBeNull())
    expect(document.querySelector('[data-hb-cell]')).toBeNull()
  })
})
