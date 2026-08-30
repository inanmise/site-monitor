import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import WeeklyAvailabilitySettings from '../components/admin/WeeklyAvailabilitySettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getWeeklyAvailStatus:      vi.fn(),
      getWeeklyAvailHistory:     vi.fn(),
      getWeeklyAvailHistoryItem: vi.fn(),
      setWeeklyAvailEnabled:     vi.fn(),
      getWeeklyAvailPreview:     vi.fn(),
      sendWeeklyAvailTest:       vi.fn(),
      downloadWeeklyOutagePdf:   vi.fn(),
    },
  }),
}))

import { api } from '../api/client'

// Test ortaminin varsayilan dili EN (i18n storedLang() -> 'en'), yani dugme "Outage PDF" cizilir.
// Iki dili birden eslestiriyoruz ki varsayilan dil degisirse test yalanci kirmizi donmesin.
const PDF_BTN = /Outage PDF|Kesinti PDF/i

const toastError = vi.fn()
vi.mock('../components/ui/Toast.jsx', async (orig) => {
  const actual = await orig()
  return { ...actual, useToast: () => ({ error: toastError, success: vi.fn(), info: vi.fn() }) }
})

/**
 * Haftalık e-posta ayarları — YALNIZ kesinti PDF'i indirme düğmesi.
 *
 * <p>Düğmenin varlık sebebi: eki doğrulamanın tek yolu mail göndermek olsaydı PDF'e bakmak için
 * ya pazartesiyi beklemek ya da gerçek bir adrese test maili atmak gerekirdi.
 */
describe('WeeklyAvailabilitySettings — kesinti PDF indirme', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getWeeklyAvailStatus.mockResolvedValue({
      success: true,
      data: {
        enabled: true, cron: '0 0 10 ? * MON', week_label: '3–9 Ağustos 2026',
        teams: [{ id: 5, name: 'TakimA', domain_count: 14, to: ['a@x.com'], cc: [] }],
        weeks: [{ offset: 0, label: 'Bu hafta', current: true, emailed: false },
                { offset: 1, label: '3–9 Ağustos 2026', current: false, emailed: true }],
      },
    })
    api.admin.getWeeklyAvailHistory.mockResolvedValue({ success: true, data: [] })
    api.admin.downloadWeeklyOutagePdf.mockResolvedValue({ success: true })
  })

  async function renderReady() {
    const view = render(<WeeklyAvailabilitySettings />)
    await waitFor(() => expect(api.admin.getWeeklyAvailStatus).toHaveBeenCalled())
    await screen.findByText(PDF_BTN)
    return view
  }

  it('Takım seçilene kadar düğme PASİF — teamId olmadan istek atılmaz', async () => {
    api.admin.getWeeklyAvailStatus.mockResolvedValue({
      success: true,
      data: { enabled: true, cron: '', week_label: '', teams: [], weeks: [] },
    })
    render(<WeeklyAvailabilitySettings />)
    await waitFor(() => expect(api.admin.getWeeklyAvailStatus).toHaveBeenCalled())

    const btn = await screen.findByRole('button', { name: PDF_BTN })
    expect(btn).toBeDisabled()
    fireEvent.click(btn)
    expect(api.admin.downloadWeeklyOutagePdf).not.toHaveBeenCalled()
  })

  it('İndirme, ÖNİZLEMEYLE AYNI takım ve haftayı kullanır', async () => {
    await renderReady()

    // Takım listesi tek elemanlı → ilk takım otomatik seçili; hafta varsayılanı 0 (bu hafta).
    fireEvent.click(screen.getByRole('button', { name: PDF_BTN }))

    await waitFor(() => expect(api.admin.downloadWeeklyOutagePdf).toHaveBeenCalledWith('5', 0))
    // Ayrı bir seçici olsaydı "önizlediğim hafta ile indirdiğim PDF farklı" tuzağı doğardı.
    expect(toastError).not.toHaveBeenCalled()
  })

  it('PDF üretilemezse KULLANICIYA söylenir — sessiz kalmaz', async () => {
    api.admin.downloadWeeklyOutagePdf.mockResolvedValue({ success: false, status: 503 })
    await renderReady()

    fireEvent.click(screen.getByRole('button', { name: PDF_BTN }))

    // Sessiz kalsaydı "düğmeye bastım hiçbir şey olmadı" durumu doğardı.
    await waitFor(() => expect(toastError).toHaveBeenCalled())
  })

  it('İndirme sürerken düğme tekrar tıklanamaz — çift istek atılmaz', async () => {
    let release
    api.admin.downloadWeeklyOutagePdf.mockReturnValue(new Promise(r => { release = r }))
    await renderReady()

    const btn = screen.getByRole('button', { name: PDF_BTN })
    fireEvent.click(btn)
    await waitFor(() => expect(btn).toBeDisabled())
    fireEvent.click(btn)

    expect(api.admin.downloadWeeklyOutagePdf).toHaveBeenCalledTimes(1)
    release({ success: true })
  })
})
