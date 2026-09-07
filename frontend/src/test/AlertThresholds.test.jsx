import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AlertThresholds from '../components/admin/AlertThresholds.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getThresholds:    vi.fn(),
      updateThreshold:  vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Alarm esikleri — testi HIC yoktu. Esikler sertifika bitisinin hangi gun UYARI, hangi gun
 * KRITIK sayilacagini belirliyor; yani bu ekran alarm siddetinin tek ayar noktasi.
 *
 * Pinlenen sozlesmeler:
 * 1. Duzenleme YERINDE acilir ve iptal edilince sunucuya HICBIR sey gitmez.
 * 2. Kaydetme gercekten ilgili kaydin id'siyle ve girilen degerlerle cagrilir; kaydettikten
 *    sonra liste TAZELENIR (ekranda bayat esik kalirsa yonetici yanlis degeri dogru sanir).
 * 3. Yukleme basarisizligi GORUNUR (bkz. adminPanelLoadError.test.jsx): bu ekranda hata
 *    dali hic yoktu, bos liste ile basarisiz yukleme ayni goruntuyu veriyordu.
 */
const ROWS = [
  { id: 1, tier: 'TIER1', warning_days: 30, high_days: 14, critical_days: 7, re_alert_interval_hours: 24 },
  { id: 2, tier: 'TIER2', warning_days: 45, high_days: 21, critical_days: 10, re_alert_interval_hours: 48 },
]

describe('AlertThresholds', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getThresholds.mockResolvedValue({ success: true, data: ROWS })
    api.admin.updateThreshold.mockResolvedValue({ success: true })
  })

  const ready = async (c) => {
    await waitFor(() => expect(c.querySelectorAll('.threshold-card').length).toBe(2))
    return c
  }

  it('acilista esikler yuklenir ve her kayit icin bir kart cizilir', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)
    expect(api.admin.getThresholds).toHaveBeenCalled()
  })

  it('ALAN->ANAHTAR baglantisi: dort girdi de dogru alana yaziyor (siralari karismasin)', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click([...container.querySelectorAll('button')].find(b => !b.disabled))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    const inputs = container.querySelectorAll('.threshold-form input[type="number"]')
    expect(inputs.length).toBe(4)

    // Her alana AYRI bir deger yazip govdede dogru anahtara dustugunu dogruluyoruz.
    // Sira karisirsa "uyari" esigi "kritik" alanina yazilir ve alarm siddeti sessizce
    // tersine doner — ekranda hicbir sey yanlis gorunmez.
    fireEvent.change(inputs[0], { target: { value: '61' } })
    fireEvent.change(inputs[1], { target: { value: '62' } })
    fireEvent.change(inputs[2], { target: { value: '63' } })
    fireEvent.change(inputs[3], { target: { value: '64' } })

    fireEvent.click(container.querySelector('.threshold-form .btn-primary'))
    await waitFor(() => expect(api.admin.updateThreshold).toHaveBeenCalled())

    const payload = api.admin.updateThreshold.mock.calls[0][1]
    expect(payload.warning_days).toBe(61)
    expect(payload.high_days).toBe(62)
    expect(payload.critical_days).toBe(63)
    expect(payload.re_alert_interval_hours).toBe(64)
  })

  it('iptal edilince duzenleme kapanir ve sunucuya istek gitmez', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click([...container.querySelectorAll('button')].find(b => !b.disabled))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    fireEvent.click(container.querySelector('.threshold-form .btn-secondary'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).toBeNull())
    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('duzenleme sirasinda sunucuya HICBIR istek gitmez (yalniz kaydedince)', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click([...container.querySelectorAll('button')].find(b => !b.disabled))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    const first = container.querySelector('.threshold-form input[type="number"]')
    fireEvent.change(first, { target: { value: '99' } })

    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('kaydetme DOGRU kayit id ve girilen degerlerle cagrilir, sonra liste tazelenir', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click([...container.querySelectorAll('button')].find(b => !b.disabled))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    const inputs = container.querySelectorAll('.threshold-form input[type="number"]')
    fireEvent.change(inputs[0], { target: { value: '60' } })

    fireEvent.click(container.querySelector('.threshold-form .btn-primary'))

    await waitFor(() => expect(api.admin.updateThreshold).toHaveBeenCalled())
    const [id, payload] = api.admin.updateThreshold.mock.calls[0]
    expect(id).toBe(1)
    expect(payload.warning_days).toBe(60)
    // Kaydettikten sonra yeniden okunmali; aksi halde ekranda bayat esik kalir.
    await waitFor(() => expect(api.admin.getThresholds.mock.calls.length).toBeGreaterThan(1))
  })

  it('kaydetme HATASI yutulmaz', async () => {
    api.admin.updateThreshold.mockResolvedValue({ success: false, error: 'esik kaydedilemedi' })
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click([...container.querySelectorAll('button')].find(b => !b.disabled))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    fireEvent.click(container.querySelector('.threshold-form .btn-primary'))

    expect(await screen.findByText(/esik kaydedilemedi/)).toBeInTheDocument()
  })
})
