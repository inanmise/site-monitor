import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, TEMPLATE } from './helpers/techErrorFixtures.js'

// Kopyalama düğmeleri (2026-10-16, kullanıcı isteği: "Yıllık Operasyon ve Hata Dağılımı (Son 12 Ay)" gibi görsel adları,
// bölüm başlıkları, alan adları rapor yazılırken kolayca kopyalansın — yazının hemen bitimine küçük ikon). Ekranda
// görünen ÇÖZÜLMÜŞ metin kopyalanır; başlığın / etiketin / şeklin erişilebilir adı değişmez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const TPL = {
  ...TEMPLATE,
  sections: [
    ...TEMPLATE.sections.slice(0, 3),
    { id: 'auto-trend', kind: 'trend', title: 'Otomatik Trendler (Son 12 Ay)', series: ['error-rate'] },
    TEMPLATE.sections[3],
  ],
}
const MONTHS = Array.from({ length: 12 }, (_, i) => (i < 3 ? { year: 2025, month: 10 + i } : { year: 2026, month: i - 2 }))
const TREND = { months: MONTHS, values: { 'error-rate': [null, null, null, null, null, null, null, null, null, null, 0.5, 0.42] }, min_points: 2 }

let writeText
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  writeText = vi.fn(() => Promise.resolve())
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse({ template: TPL, trend: TREND }))
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
})
afterEach(() => { delete navigator.clipboard })

const copyBtn = (text) => screen.getByRole('button', { name: `Kopyala: ${text}` })

describe('rapor düzenleyicide kopyalama düğmeleri', () => {
  it('rapor başlığı, bölüm başlıkları, alan adları, görsel adları ve trend grafik başlıkları — çözülmüş metinle', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    for (const text of [
      TITLE,                                                   // rapor başlığı
      'Mobil Kanalı — Eylül 2026 Özeti',                        // bölüm başlığı ({kanal} {ay} {yil} çözülmüş)
      'Teknik hata oranı',                                      // alan adı
      'SLA hedefin üstünde mi?',                                // evet / hayır alanı
      'Teknik hataların anomali olarak arttığı konular',        // konu bölümü
      'Grafikler hakkında',                                     // metin bölümü
      'Grafikler',                                              // görsel bölümü
      'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri',            // görsel adı
      'Mobil Kanalı Teknik Hata (Günlük)',
      'Otomatik Trendler (Son 12 Ay)',                          // trend bölümü
      'Teknik hata oranı (Son 12 Ay)',                          // trend grafik başlığı
    ]) expect(copyBtn(text)).toBeInTheDocument()

    // Konu alanlarının adları da (her konuda) kopyalanır
    fireEvent.click(screen.getByRole('button', { name: /^(Başlık ekle|Add topic)$/ }))
    for (const text of ['Ne zaman, nerede, nasıl gerçekleşti?', 'Etkilenen müşteri sayısı', 'Bu sorundan kaynaklı teknik hata adedi'])
      expect(await screen.findByRole('button', { name: `Kopyala: ${text}` })).toBeInTheDocument()
    // Kontrast (inceleme 2026-10-16): ikon opaklıkla soldurulmaz — açık temada %70 opaklık 3:1'in altına düşüyordu
    for (const btn of document.querySelectorAll('[data-slot="te-copy"] button')) expect(btn.className).not.toMatch(/(^|\s)opacity-/)
  })

  it('tıklayınca metin panoya gider ve düğme "Kopyalandı" der', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(copyBtn('Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri'))
    const slot = document.querySelector('[data-slot="te-image-slot"][data-slot-id="monthly-tx"]')
    await waitFor(() => expect(within(slot).getByRole('button', { name: 'Kopyalandı' })).toBeInTheDocument())

    fireEvent.click(copyBtn('Teknik hata oranı (Son 12 Ay)'))
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith('Teknik hata oranı (Son 12 Ay)'))
  })

  it('başlık, alan etiketi, şekil ve görsel adı erişilebilir adlarında "Kopyala" geçmez (düğme yanlarında, içlerinde değil)', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('heading', { name: 'Mobil Kanalı — Eylül 2026 Özeti' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Grafikler' })).toBeInTheDocument()
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveAttribute('id', 'te-v-total-tx')
    expect(screen.getByRole('figure', { name: 'Teknik hata oranı (Son 12 Ay)' })).toBeInTheDocument()
    expect(container.querySelector('[data-slot-id="monthly-tx"] [data-slot="te-image-label"]').textContent)
      .toBe('Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri')
    // Başlıkların içinde düğme yok
    for (const h of container.querySelectorAll('[data-slot="te-report"] h2, [data-slot="te-report"] h3, [data-slot="te-report"] label, figcaption > span[id]'))
      expect(h.querySelector('button, [role="button"]') ? `${h.tagName} ${h.className} | ${h.textContent}` : null).toBeNull()
  })
})
