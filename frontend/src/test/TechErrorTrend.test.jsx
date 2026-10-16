import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Otomatik trend (2026-10-15, Faz C): raporda seçilen alanların son 12 ay grafiği (kayıtlı aylar sunucudan, bu ay
// taslaktan), okunamayan veri "okunamadı" der — "en az iki ay" değil; şablon düzenleyicide alan seçimi, sorunlu başvuru
// uyarısı + kaldırma, boş seçimde kayıt engeli, alan silinirken onay ve seçimden düşme.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'

const TREND_TEMPLATE = {
  ...TEMPLATE,
  sections: [
    ...TEMPLATE.sections.slice(0, 3),
    { id: 'auto-trend', kind: 'trend', title: 'Otomatik Trendler (Son 12 Ay)', series: ['error-rate', 'total-tx', 'gone'] },
    TEMPLATE.sections[3],
  ],
}

// Ekim 2025 … Eylül 2026 (raporun ayı en sonda)
const MONTHS = Array.from({ length: 12 }, (_, i) => (i < 3 ? { year: 2025, month: 10 + i } : { year: 2026, month: i - 2 }))
const series = (aug, sep) => [null, null, null, null, null, null, null, null, null, null, aug, sep]
const TREND = { months: MONTHS, values: { 'error-rate': series(0.5, 0.42), 'total-tx': series(1000000, null) }, min_points: 2 }

const trendReport = (extra = {}) => reportResponse({ template: TREND_TEMPLATE, trend: TREND, ...extra })
const chart = (container, id) => container.querySelector(`[data-slot="te-trend-chart"][data-field="${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

describe('raporda otomatik trend', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('her geçerli alan için bir grafik; başlık "(Son 12 Ay)", aylar ekran okuyucuya liste; şablonda olmayan alan çizilmez', async () => {
    te().report.mockResolvedValue(trendReport())
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const block = container.querySelector('[data-slot="te-trend"]')
    expect(within(block).getByRole('heading', { name: 'Otomatik Trendler (Son 12 Ay)' })).toBeInTheDocument()
    expect(block.querySelectorAll('[data-slot="te-trend-chart"]')).toHaveLength(2)
    const rate = chart(container, 'error-rate')
    expect(rate).toHaveAttribute('data-points', '2')
    expect(within(rate).getByText('Teknik hata oranı (Son 12 Ay)')).toBeInTheDocument()
    expect(within(rate).getByText('Ağustos 2026: %0,5')).toBeInTheDocument()
    expect(within(rate).getByText('Eylül 2026: %0,42')).toBeInTheDocument()
    expect(within(rate).getByText('Ekim 2025: rakam yok')).toBeInTheDocument()
  })

  it('bu ayın çubuğu taslaktan: rakam girilince ikinci nokta oluşur, grafik çizilir', async () => {
    te().report.mockResolvedValue(trendReport())
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const tx = chart(container, 'total-tx')
    expect(tx).toHaveAttribute('data-points', '1')
    expect(within(tx).getByText('Trend için en az iki ayın rakamı gerekir; şu ana kadar rakamı girilmiş ay: 1.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
    // Yazarken grafik çizilmez (performans): değer, yazma durunca grafiğe geçer
    expect(chart(container, 'total-tx')).toHaveAttribute('data-points', '1')
    await waitFor(() => expect(chart(container, 'total-tx')).toHaveAttribute('data-points', '2'))
    expect(within(chart(container, 'total-tx')).getByText('Eylül 2026: 1.250.000')).toBeInTheDocument()
    // Taslakta silinen değer: bu ay yeniden boş (sunucudaki eski değer gösterilmez)
    fireEvent.change(screen.getByLabelText('Teknik hata oranı'), { target: { value: '' } })
    await waitFor(() => expect(chart(container, 'error-rate')).toHaveAttribute('data-points', '1'))
  })

  it('önceki aylar okunamadıysa "okunamadı" der — "en az iki ay gerekir" değil; yanıtta hiç yoksa da', async () => {
    te().report.mockResolvedValue(trendReport({ trend: { unavailable: true } }))
    const first = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const alert = first.container.querySelector('[data-slot="te-trend-unavailable"]')
    expect(alert).toHaveTextContent('Önceki ayların rakamları okunamadı; grafik şu an gösterilemiyor.')
    expect(first.container.querySelector('[data-slot="te-trend-chart"]')).toBeNull()
    first.unmount()
    te().report.mockResolvedValue(trendReport({ trend: undefined }))
    const second = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(second.container.querySelector('[data-slot="te-trend-unavailable"]')).not.toBeNull()
  })

  it('bu ay "Hayır" cevabıyla muaf olan alanın grafiği gösterilmez; "Evet" ile geri gelir (PDF ile aynı kural)', async () => {
    const tpl = {
      ...TREND_TEMPLATE,
      sections: TREND_TEMPLATE.sections.map((s) => (s.id === 'summary' ? { ...s, fields: [...s.fields,
        { id: 'outage', label: 'Kesinti kaynaklı hata adedi', type: 'number', skip_if_no: 'sla-above' }] }
        : s.kind === 'trend' ? { ...s, series: ['error-rate', 'outage'] } : s)),
    }
    te().report.mockResolvedValue(trendReport({ template: tpl,
      trend: { ...TREND, values: { 'error-rate': series(0.5, 0.42), outage: series(12, null) } } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(chart(container, 'outage')).not.toBeNull()
    fireEvent.click(within(screen.getByLabelText('SLA hedefin üstünde mi?')).getByRole('radio', { name: 'Hayır' }))
    await waitFor(() => expect(chart(container, 'outage')).toBeNull())
    expect(chart(container, 'error-rate')).not.toBeNull()
    fireEvent.click(within(screen.getByLabelText('SLA hedefin üstünde mi?')).getByRole('radio', { name: 'Evet' }))
    await waitFor(() => expect(chart(container, 'outage')).not.toBeNull())
  })

  it('çizilecek alanı kalmayan trend bölümü raporda görünmez', async () => {
    const tpl = { ...TREND_TEMPLATE, sections: TREND_TEMPLATE.sections.map((s) => (s.kind === 'trend' ? { ...s, series: ['gone', 'sla-above'] } : s)) }
    te().report.mockResolvedValue(trendReport({ template: tpl }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(container.querySelector('[data-slot="te-trend"]')).toBeNull()
  })
})

describe('şablon düzenleyicide otomatik trend', () => {
  const withTemplate = (tpl) => te().template.mockImplementation(async (id) => {
    const r = templateResponse(channel(Number(id), 'Mobil'))
    return { ...r, data: { ...r.data, template: tpl } }
  })
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
  })

  it('bölüm ekle → otomatik trend: sayı / yüzde alanları işaretli gelir; hepsi kaldırılınca kayıt engellenir, seçimle kaydedilir', async () => {
    withTemplate(TEMPLATE)
    render(<TechErrorReportsPage />)
    await screen.findByRole('button', { name: 'Bölüm ekle' })
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Bölüm ekle' }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Otomatik trend' }))
    const sec = document.querySelector('[data-slot="te-tpl-section"][data-kind="trend"]')
    expect(within(sec).getByDisplayValue('Otomatik Trendler (Son 12 Ay)')).toBeInTheDocument()
    const rate = within(sec).getByRole('checkbox', { name: /Teknik hata oranı/ })
    const tx = within(sec).getByRole('checkbox', { name: /Toplam işlem adedi/ })
    expect(rate).toBeChecked()
    expect(tx).toBeChecked()
    // Evet/hayır alanı seçilemez
    expect(within(sec).queryByRole('checkbox', { name: /SLA hedefin üstünde mi/ })).toBeNull()
    fireEvent.click(rate)
    fireEvent.click(tx)
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(await within(sec).findByText(/Otomatik trend bölümünde en az bir alan seçilmeli/)).toBeInTheDocument()
    expect(te().saveTemplate).not.toHaveBeenCalled()
    fireEvent.click(tx)
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    const saved = te().saveTemplate.mock.calls[0][1].sections.find((s) => s.kind === 'trend')
    expect(saved.series).toEqual(['total-tx'])
  })

  it('şablonda olmayan ya da sayı olmayan başvuru uyarılır (kaydı engellemez) ve kaldırılabilir', async () => {
    withTemplate({ ...TREND_TEMPLATE, sections: TREND_TEMPLATE.sections.map((s) => (s.kind === 'trend'
      ? { ...s, series: ['error-rate', 'gone', 'sla-above'] } : s)) })
    render(<TechErrorReportsPage />)
    const sec = await waitFor(() => {
      const el = document.querySelector('[data-slot="te-tpl-section"][data-kind="trend"]')
      expect(el).not.toBeNull()
      return el
    })
    const problems = () => sec.querySelectorAll('[data-slot="te-tpl-trend-problem"]')
    expect(problems()).toHaveLength(2)
    expect(problems()[0]).toHaveTextContent('“gone” kimlikli alan şablonda yok; bu grafik çizilmez.')
    expect(problems()[1]).toHaveTextContent('“SLA hedefin üstünde mi?” alanı sayı ya da yüzde değil')
    fireEvent.click(within(problems()[0]).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(problems()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    expect(te().saveTemplate.mock.calls[0][1].sections.find((s) => s.kind === 'trend').series).toEqual(['error-rate', 'sla-above'])
  })

  it('trendde kullanılan alan silinirken onay istenir; onaylanınca trend seçiminden de düşer', async () => {
    withTemplate(TREND_TEMPLATE)
    render(<TechErrorReportsPage />)
    await screen.findByRole('button', { name: '“Toplam işlem adedi” alanını kaldır' })
    fireEvent.click(screen.getByRole('button', { name: '“Toplam işlem adedi” alanını kaldır' }))
    const dlg = await screen.findByRole('dialog', { name: 'Alanın otomatik trend grafiği var' })
    expect(dlg).toHaveTextContent('Alanın otomatik trend grafiği var')
    expect(dlg).toHaveTextContent('“Toplam işlem adedi” alanının son 12 ay grafiği otomatik trend bölümünde.')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yine de sil' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: '“Toplam işlem adedi” alanını kaldır' })).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    // Silinen alan seçimden düştü; önceden şablonda olmayan "gone" başvurusuna dokunulmadı
    expect(te().saveTemplate.mock.calls[0][1].sections.find((s) => s.kind === 'trend').series).toEqual(['error-rate', 'gone'])
  })
})
