import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { channel, overview, reportResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Değer tablosunun erişilebilir adı (A7, 2026-11-20): tablo (ve dar ekranda kart listesi) adını bölüm başlığından alır;
// görünmez açıklama satır / sütun sayısını söyler. Görselin alt metni yuva adıdır.
const widthCtl = vi.hoisted(() => ({ value: 1200 }))
vi.mock('../hooks/useElementWidth.js', async (orig) => {
  const real = await orig()
  return { ...real, useElementWidth: () => [() => {}, widthCtl.value] }
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(),
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
    TEMPLATE.sections[0],
    { id: 'sla', kind: 'table', title: '{kanal} SLA Erişilebilirlik Tablosu', columns: [
      { id: 'functions', label: 'Fonksiyon adedi', type: 'number' },
      { id: 'availability', label: 'Erişilebilirlik', type: 'percent' },
      { id: 'note', label: 'Not', type: 'text' },
    ], rows: [
      { id: 'total', label: 'Toplam', group: true },
      { id: 'critical', label: 'SLA dahil ve kritik' },
    ] },
    TEMPLATE.sections.find((s) => s.id === 'charts'),
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse({ template: TPL,
    images: [{ id: 501, slot_id: 'monthly-tx', file_name: 'a.png', width: 800, height: 400, size_bytes: 4 }] }))
})

describe('değer tablosu — erişilebilir ad', () => {
  it('geniş ekran: tablonun adı çözülmüş bölüm başlığı; görünmez açıklama satır / sütun sayısını söyler', async () => {
    widthCtl.value = 1200
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const table = screen.getByRole('table', { name: 'Mobil SLA Erişilebilirlik Tablosu' })
    const caption = table.querySelector('caption[data-slot="te-table-caption"]')
    expect(caption).toHaveTextContent('Değer tablosu — satır: 2, sütun: 3.')
    expect(caption.className).toContain('sr-only')
  })

  it('dar ekran: kart listesi aynı adı taşır', async () => {
    widthCtl.value = 500
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('list', { name: 'Mobil SLA Erişilebilirlik Tablosu' })).toHaveAttribute('data-slot', 'te-table-cards')
    expect(screen.queryByRole('table', { name: 'Mobil SLA Erişilebilirlik Tablosu' })).toBeNull()
  })

  it('görselin alt metni yuvanın çözülmüş adı', async () => {
    widthCtl.value = 1200
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('img', { name: 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri' })).toHaveAttribute('data-slot', 'te-image')
  })
})
