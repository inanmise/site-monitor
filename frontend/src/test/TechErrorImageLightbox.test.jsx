import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Görsel büyütme (2026-10-16, kullanıcı isteği: "görsele tıklanınca büyüteç gibi pop-up açılsın, × ile kapansın").
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const LABEL = 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri'

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse({ images: [
    { id: 7, slot_id: 'monthly-tx', file_name: 'aylik.png', width: 1600, height: 700, size_bytes: 90_000 },
  ] }))
})

describe('görsel büyütme', () => {
  it('görsele tıklayınca büyük görünüm açılır (ad başlıkta); sığdır / %100 / %200; görsele tıklamak geçiş yapar; × kapatır', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: `Görseli büyüt: ${LABEL}` }))
    const dlg = await screen.findByRole('dialog', { name: LABEL })
    const view = dlg.querySelector('[data-slot="te-image-lightbox-view"]')
    const img = within(dlg).getByRole('img', { name: LABEL })
    expect(img).toHaveAttribute('src', '/api/tech-error-reports/images/7')
    expect(view).toHaveAttribute('data-zoom', 'fit')
    // %100 gerçek pikseller, %200 iki katı (kaydırarak gezilir)
    fireEvent.click(within(dlg).getByRole('radio', { name: '%100' }))
    expect(view).toHaveAttribute('data-zoom', '100')
    expect(img.style.width).toBe('1600px')
    fireEvent.click(within(dlg).getByRole('radio', { name: '%200' }))
    expect(img.style.width).toBe('3200px')
    // Görselin kendisi: büyükken sığdıra, sığdırılmışken %100'e geçer
    fireEvent.click(within(dlg).getByRole('button', { name: 'Ekrana sığdır' }))
    expect(view).toHaveAttribute('data-zoom', 'fit')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gerçek boyutta göster' }))
    expect(view).toHaveAttribute('data-zoom', '100')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: LABEL })).toBeNull())
    // Odak tıklanan görsele döner (inceleme 2026-10-16: <body>'ye düşüyordu — 22 yuvalı raporda en başa dönülüyordu)
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: `Görseli büyüt: ${LABEL}` })))
    // Yeniden açılınca sığdır ile başlar
    fireEvent.click(screen.getByRole('button', { name: `Görseli büyüt: ${LABEL}` }))
    const again = await screen.findByRole('dialog', { name: LABEL })
    expect(again.querySelector('[data-slot="te-image-lightbox-view"]')).toHaveAttribute('data-zoom', 'fit')
    // Açık pencereye bırakılan dosya yuvaya YÜKLENMEZ (pencere bırakma alanının dışında — inceleme 2026-10-16)
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'baska.png', { type: 'image/png' })
    fireEvent.dragOver(again, { dataTransfer: { files: [file] } })
    fireEvent.drop(again, { dataTransfer: { files: [file] } })
    expect(document.querySelector('[data-slot-id="monthly-tx"]').className).not.toContain('border-primary')
    fireEvent.keyDown(again, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: LABEL })).toBeNull())
    expect(te().uploadImage).not.toHaveBeenCalled()
  })
})
