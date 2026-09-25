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
      createThreshold:  vi.fn(),
      deleteThreshold:  vi.fn(),
      previewThreshold: vi.fn(),
    },
  }),
}))
const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
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
  { id: 1, tier: null, warning_days: 30, high_days: 14, critical_days: 7, re_alert_interval_hours: 24 },   // varsayılan
  { id: 2, tier: 1, warning_days: 45, high_days: 21, critical_days: 10, re_alert_interval_hours: 48 },     // Tier 1 satırı
]

describe('AlertThresholds', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getThresholds.mockResolvedValue({ success: true, data: ROWS })
    api.admin.updateThreshold.mockResolvedValue({ success: true })
    api.admin.previewThreshold.mockResolvedValue({ success: true, data: { scope_total: 5, unchecked: 1, current: { critical: 1, high: 0, warning: 2, ok: 1 }, proposed: { critical: 2, high: 1, warning: 0, ok: 1 }, samples: { CRITICAL: ['a.example.com (3g)'], HIGH: [], WARNING: [] } } })
    api.admin.createThreshold.mockResolvedValue({ success: true })
    api.admin.deleteThreshold.mockResolvedValue({ success: true })
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

    fireEvent.click(container.querySelector('.threshold-display [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    const inputs = container.querySelectorAll('.threshold-form input[type="number"]')
    expect(inputs.length).toBe(4)

    // Her alana AYRI bir deger yazip govdede dogru anahtara dustugunu dogruluyoruz.
    // Sira karisirsa "uyari" esigi "kritik" alanina yazilir ve alarm siddeti sessizce
    // tersine doner — ekranda hicbir sey yanlis gorunmez.
    // Sıra kuralı (kritik ≤ yüksek ≤ uyarı) bozulmasın diye azalan değerler.
    fireEvent.change(inputs[0], { target: { value: '63' } })
    fireEvent.change(inputs[1], { target: { value: '62' } })
    fireEvent.change(inputs[2], { target: { value: '61' } })
    fireEvent.change(inputs[3], { target: { value: '64' } })

    fireEvent.click(container.querySelector('.threshold-form [data-slot="button"][data-variant="default"]'))
    await waitFor(() => expect(api.admin.updateThreshold).toHaveBeenCalled())

    const payload = api.admin.updateThreshold.mock.calls[0][1]
    expect(payload.warning_days).toBe(63)
    expect(payload.high_days).toBe(62)
    expect(payload.critical_days).toBe(61)
    expect(payload.re_alert_interval_hours).toBe(64)
  })

  it('iptal edilince duzenleme kapanir ve sunucuya istek gitmez', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click(container.querySelector('.threshold-display [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    fireEvent.click(container.querySelector('.threshold-form [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).toBeNull())
    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('duzenleme sirasinda sunucuya HICBIR istek gitmez (yalniz kaydedince)', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click(container.querySelector('.threshold-display [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    const first = container.querySelector('.threshold-form input[type="number"]')
    fireEvent.change(first, { target: { value: '99' } })

    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('kaydetme DOGRU kayit id ve girilen degerlerle cagrilir, sonra liste tazelenir', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)

    fireEvent.click(container.querySelector('.threshold-display [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    const inputs = container.querySelectorAll('.threshold-form input[type="number"]')
    fireEvent.change(inputs[0], { target: { value: '60' } })

    fireEvent.click(container.querySelector('.threshold-form [data-slot="button"][data-variant="default"]'))

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

    fireEvent.click(container.querySelector('.threshold-display [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())

    fireEvent.click(container.querySelector('.threshold-form [data-slot="button"][data-variant="default"]'))

    expect(await screen.findByText(/esik kaydedilemedi/)).toBeInTheDocument()
  })

  // ── Tier bazlı eşikler + etki önizleme (2026-09-20) ──
  it('varsayılan satır önce, tier satırı başlığıyla ve Sil düğmesiyle; varsayılanda Sil YOK', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)
    const cards = container.querySelectorAll('.threshold-card')
    expect(cards[0].textContent).toMatch(/Varsayılan|Default/)
    expect(cards[0].querySelector('[data-slot="button"][data-variant="destructive"]')).toBeNull()
    expect(cards[1].textContent).toMatch(/Tier 1/)
    expect(cards[1].querySelector('[data-slot="button"][data-variant="destructive"]')).not.toBeNull()
    // Satırı olmayan tier'lar (2,3,4) eklenebilir; Tier 1 listede YOK.
    expect(container.querySelector('.threshold-add')).not.toBeNull()
  })

  it('düzenlerken etki önizlemesi sunucudan tier + günlerle istenir ve fark parantezde gösterilir', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)
    fireEvent.click(container.querySelectorAll('.threshold-display [data-slot="button"][data-variant="secondary"]')[1])   // Tier 1 satırı
    await waitFor(() => expect(api.admin.previewThreshold).toHaveBeenCalled(), { timeout: 2000 })
    expect(api.admin.previewThreshold.mock.calls[0][0]).toEqual({ tier: 1, warning: 45, high: 21, critical: 10 })
    const panel = await screen.findByTestId('threshold-preview')
    expect(panel.textContent).toContain('(+1)')          // kritik 1 → 2
    expect(panel.textContent).toContain('a.example.com (3g)')
  })

  it('bozuk sıra (kritik > yüksek) anında uyarır, Kaydet kilitlenir ve önizleme istenmez', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)
    fireEvent.click(container.querySelector('.threshold-display [data-slot="button"][data-variant="secondary"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())
    const inputs = container.querySelectorAll('.threshold-form input[type="number"]')
    fireEvent.change(inputs[2], { target: { value: '99' } })   // kritik 99 > yüksek 14
    expect(await screen.findByText(/Sıra bozuk|Out of order/)).toBeInTheDocument()
    expect(container.querySelector('.threshold-form [data-slot="button"][data-variant="default"]')).toBeDisabled()
  })

  it('Tier ekle: seçilen tier ile POST createThreshold (varsayılan günler ön-dolu)', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)
    const add = container.querySelector('.threshold-add')
    // SearchableSelect: aç, Tier 2'yi seç (mousedown sözleşmesi)
    fireEvent.mouseDown(add.querySelector('button[role="combobox"]'))
    const opt = await screen.findByText(/Tier 2/)
    fireEvent.mouseDown(opt)
    fireEvent.click(add.querySelector('[data-slot="button"]'))
    await waitFor(() => expect(container.querySelector('.threshold-form')).not.toBeNull())
    fireEvent.click(container.querySelector('.threshold-form [data-slot="button"][data-variant="default"]'))
    await waitFor(() => expect(api.admin.createThreshold).toHaveBeenCalled())
    const body = api.admin.createThreshold.mock.calls[0][0]
    expect(body.tier).toBe(2)
    expect(body.warning_days).toBe(30)   // varsayılan satırdan ön-dolu
    expect(api.admin.updateThreshold).not.toHaveBeenCalled()
  })

  it('tier satırı silme onaydan geçer ve deleteThreshold(id) çağrılır', async () => {
    const { container } = render(<AlertThresholds />)
    await ready(container)
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(container.querySelectorAll('.threshold-card')[1].querySelector('[data-slot="button"][data-variant="destructive"]'))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.deleteThreshold).not.toHaveBeenCalled()          // vazgeçildi
    confirmMock.mockResolvedValueOnce(true)
    fireEvent.click(container.querySelectorAll('.threshold-card')[1].querySelector('[data-slot="button"][data-variant="destructive"]'))
    await waitFor(() => expect(api.admin.deleteThreshold).toHaveBeenCalledWith(2))
  })
})
