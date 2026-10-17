import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Kısaltmalı rakam girişi rapor ekranında (2026-10-16, kullanıcı kararı): "7,42G" alanda yazıldığı gibi kalır, altında
// anlaşılan tam sayı ("= 7.420.000.000"); kayda tam sayı gider; tek başına "B" ve sınır üstü açık mesajla reddedilir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), saveTemplate: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'

const TEMPLATE = {
  v: 1,
  lang: 'tr',
  title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Teknik hata adedi', type: 'number' },
      { id: 'sla-rate', label: 'SLA oranı', type: 'percent' },
    ] },
    { id: 'issues', kind: 'issues', title: 'Konular', item_title: '{konu} Konusu', description_label: 'Açıklama',
      customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu sorundan kaynaklı teknik hata adedi' },
  ],
}

const report = () => reportResponse({
  template: TEMPLATE,
  values: { fields: {}, issues: { issues: [{ id: 'a', topic: 'Kart', title_auto: true, description: '', customers: null, errors: null }] }, texts: {} },
  previous: null,
})

const NOT_NUMBER = 'Geçerli bir sayı yazın: 1.250.000, 0,42 ya da kısaltmayla 7,42G, 130,1M, 681,6k (k = bin, M = milyon, G ya da Mr = milyar).'
const AMBIGUOUS_B = 'Tek başına "B" kullanılamaz: Türkçede bin, İngilizcede milyar demek. Bin için k ya da bin, milyar için G ya da Mr yazın.'
const TOO_LARGE = 'Sayı çok büyük; en çok 1.000.000.000.000.000 (1000T) olabilir. Rakamı ya da kısaltmayı kontrol edin.'
const expanded = (id) => document.querySelector(`[data-field="values.${id}"] [data-slot="te-number-expanded"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(report())
  te().saveReport.mockImplementation(async (id, values, version) => ({ success: true,
    data: { ...report().data.report, version: version + 1, values } }))
})

describe('kısaltmalı rakam girişi', () => {
  it('"7,42G" yazıldığı gibi kalır, altında tam sayı; kayda tam sayı gider (gösterge ve konu adetleri)', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const total = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(total, { target: { value: '7,42G' } })
    expect(total).toHaveValue('7,42G')
    expect(expanded('total-tx')).toHaveTextContent('= 7.420.000.000')
    expect(total.getAttribute('aria-describedby')).toContain(expanded('total-tx').id)
    fireEvent.change(screen.getByLabelText('Teknik hata adedi'), { target: { value: '1,23456789123456M' } })
    // Tam sayı satırı kayıtla aynı biçimde (6 ondalığa kadar — inceleme 2026-10-16: 4 ondalık gösteriyordu)
    expect(expanded('error-count')).toHaveTextContent('= 1.234.567,891235')
    fireEvent.change(screen.getByLabelText('Teknik hata adedi'), { target: { value: '681,6 bin' } })
    expect(expanded('error-count')).toHaveTextContent('= 681.600')
    fireEvent.change(screen.getByLabelText('Etkilenen müşteri sayısı'), { target: { value: '16,98k' } })
    // Kısaltmasız sayıda tam sayı satırı yok
    fireEvent.change(screen.getByLabelText('Bu sorundan kaynaklı teknik hata adedi'), { target: { value: '1.250' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const sent = te().saveReport.mock.calls[0][1]
    expect(sent.fields).toMatchObject({ 'total-tx': 7_420_000_000, 'error-count': 681_600 })
    expect(sent.issues.issues[0]).toMatchObject({ customers: 16_980, errors: 1250 })
    expect(screen.queryByText(NOT_NUMBER)).toBeNull()
  })

  it('tek başına "B" yazarken hata göstermez, alan bırakılınca ne yazılacağını söyler; sınır üstü ve geçersiz sonek de açık', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const total = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(total, { target: { value: '7,42B' } })
    expect(screen.queryByText(AMBIGUOUS_B)).toBeNull()
    expect(expanded('total-tx')).toBeNull()
    fireEvent.blur(total)
    expect(await screen.findByText(AMBIGUOUS_B)).toBeInTheDocument()
    fireEvent.change(total, { target: { value: '1001T' } })
    expect(await screen.findByText(TOO_LARGE)).toBeInTheDocument()
    fireEvent.change(total, { target: { value: '7,42x' } })
    expect(await screen.findByText(NOT_NUMBER)).toBeInTheDocument()
    fireEvent.change(total, { target: { value: '7,42G' } })
    await waitFor(() => expect(screen.queryByText(NOT_NUMBER)).toBeNull())
    // Yüzde alanında kısaltma yok: eski (yüzde) mesajı
    fireEvent.change(screen.getByLabelText('SLA oranı'), { target: { value: '5k' } })
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
  })
})
