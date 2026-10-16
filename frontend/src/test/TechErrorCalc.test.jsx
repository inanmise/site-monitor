import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse } from './helpers/techErrorFixtures.js'

// Otomatik oranlar (2026-10-16, kullanıcı isteği: "kullanıcı adetleri girer, yüzdeleri sistem hesaplar; üstte adetler,
// altta oranlar; kota / map yapılmamış hata adedi girişinde yüzde de görünsün").
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

const CALC_TEMPLATE = {
  v: 1,
  lang: 'tr',
  title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['Teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.'] },
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Teknik hata adedi', type: 'number' },
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'sla-rate', label: 'SLA oranı', type: 'percent' },
    ] },
    { id: 'rates', kind: 'metrics', title: 'Oranlar (otomatik)', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
      { id: 'unmapped-rate', label: 'Map yapılmamış hataların teknik hatalara oranı', type: 'percent',
        calc: { kind: 'ratio', num: 'unmapped-count', den: 'error-count' } },
      { id: 'tx-increase', label: 'Önceki aya göre artış var mı?', type: 'yesno', calc: { kind: 'increase', of: 'total-tx' } },
      { id: 'tx-increase-pct', label: 'Önceki aya göre değişim oranı', type: 'percent', calc: { kind: 'change', of: 'total-tx' } },
    ] },
  ],
}

const calcReport = (extra = {}) => reportResponse({
  template: CALC_TEMPLATE,
  values: { fields: { 'total-tx': 1000, 'error-count': 5, 'unmapped-count': 1, 'error-rate': 0.5 }, issues: {}, texts: {} },
  previous: { id: 90, year: 2026, month: 8, fields: { 'total-tx': 800 } },
  ...extra,
})

const calcBox = (id) => document.querySelector(`[data-field="values.${id}"] [data-slot="te-calc"]`)
const note = (id) => document.querySelector(`[data-field="values.${id}"] [data-slot="te-calc-note"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

describe('raporda otomatik oranlar', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('oranlar SORULMAZ, adetlerden hesaplanıp gösterilir; adet değişince anında güncellenir; özet önizlemesi de', async () => {
    te().report.mockResolvedValue(calcReport())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    // Çıktı etiketine bağlı (ekran okuyucu alan adını okur); "Otomatik" rozeti ve formül
    expect(screen.getByLabelText('Teknik hata oranı').tagName).toBe('OUTPUT')
    expect(screen.getByLabelText('Teknik hata oranı')).toHaveTextContent('%0,5')
    expect(within(calcBox('error-rate')).getByText('Otomatik')).toBeInTheDocument()
    expect(note('error-rate')).toHaveTextContent('Hesap: Teknik hata adedi ÷ Toplam işlem adedi × 100')
    // Geçen ayın raporundan: 1000 / 800 → %25 artış
    expect(screen.getByLabelText('Önceki aya göre değişim oranı')).toHaveTextContent('%25')
    expect(screen.getByLabelText('Önceki aya göre artış var mı?')).toHaveTextContent('Evet')
    expect(note('tx-increase-pct')).toHaveTextContent('Ağustos 2026')

    fireEvent.change(screen.getByLabelText('Teknik hata adedi'), { target: { value: '10' } })
    expect(screen.getByLabelText('Teknik hata oranı')).toHaveTextContent('%1')
    expect(screen.getByLabelText('Map yapılmamış hataların teknik hatalara oranı')).toHaveTextContent('%10')
    // Özet önizlemesi hesaplanan değerle (taslaktan, kaydetmeden)
    expect(document.querySelector('[data-slot="te-sum-text"]')).toHaveTextContent('Teknik hata oranı %1 olarak gerçekleşmiştir.')
  })

  it('adet girişinin altında ondan türeyen oran (kota / map yapılmamış hata gibi) canlı görünür', async () => {
    te().report.mockResolvedValue(calcReport())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const derived = () => document.querySelector('[data-field="values.unmapped-count"] [data-slot="te-calc-derived"]')
    expect(derived()).toHaveTextContent('Map yapılmamış hataların teknik hatalara oranı: %20')
    fireEvent.change(screen.getByLabelText('Map yapılmamış hata adedi'), { target: { value: '2' } })
    expect(derived()).toHaveTextContent('%40')
    // Teknik hata adedi de iki oranın PAYI: altında teknik hata oranı
    expect(document.querySelector('[data-field="values.error-count"] [data-slot="te-calc-derived"][data-rate="error-rate"]'))
      .toHaveTextContent('Teknik hata oranı: %0,5')
    // Payda olan toplam işlem adedinin altında türeyen oran yok
    expect(document.querySelector('[data-field="values.total-tx"] [data-slot="te-calc-derived"]')).toBeNull()
  })

  it('eksik adette oran boş ("—") ve neyin girileceğini söyler; bölen 0 ise nedeni yazar', async () => {
    te().report.mockResolvedValue(calcReport())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Teknik hata adedi'), { target: { value: '' } })
    expect(screen.getByLabelText('Teknik hata oranı')).toHaveTextContent('—')
    expect(note('error-rate')).toHaveTextContent('Hesaplamak için girin: Teknik hata adedi')
    expect(note('unmapped-rate')).toHaveTextContent('Hesaplamak için girin: Teknik hata adedi')
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '0' } })
    fireEvent.change(screen.getByLabelText('Teknik hata adedi'), { target: { value: '3' } })
    expect(note('error-rate')).toHaveTextContent('“Toplam işlem adedi” 0 olduğu için hesaplanamaz.')
  })

  it('geçen ayın raporu yoksa değişim ve artış ELLE girilir (alan açık, nedeni altında) ve kayda gider', async () => {
    te().report.mockResolvedValue(calcReport({ previous: null }))
    te().saveReport.mockImplementation(async (id, values, version) => ({ success: true,
      data: { ...calcReport().data.report, version: version + 1, values } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const pct = screen.getByLabelText('Önceki aya göre değişim oranı')
    expect(pct.tagName).toBe('INPUT')
    expect(note('tx-increase-pct')).toHaveTextContent('Geçen ayın raporunda “Toplam işlem adedi” yok — değeri elle girin.')
    fireEvent.change(pct, { target: { value: '3,5' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    expect(te().saveReport.mock.calls[0][1].fields['tx-increase-pct']).toBe(3.5)
  })
})

describe('inceleme düzeltmeleri (2026-10-16)', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('"şablonun son hâlini uygula" onayı: elle yazılan oranların yeniden hesaplanacağını ayrıca söyler', async () => {
    te().report.mockResolvedValue(calcReport({ template_update: { differs: true, lost_values: 0, lost_images: 0, recalculated: 2 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: /Şablonun son hâlini uygula|Apply the latest template/ }))
    expect(await screen.findByText(/Ayrıca 2 oran alanı girilen adetlerden yeniden hesaplanacak \(elle yazılan değerin yerine\)\./))
      .toBeInTheDocument()
  })

  it('formülü kullanılamayan oran (formüldeki alan yok) adet girişinin altında gösterilmez — oran elle girilir', async () => {
    const tpl = JSON.parse(JSON.stringify(CALC_TEMPLATE))
    tpl.sections[2].fields[1].calc = { kind: 'ratio', num: 'unmapped-count', den: 'silinmis' }
    te().report.mockResolvedValue(calcReport({ template: tpl }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-field="values.unmapped-count"] [data-slot="te-calc-derived"]')).toBeNull()
    expect(screen.getByLabelText('Map yapılmamış hataların teknik hatalara oranı').tagName).toBe('INPUT')
  })
})

describe('şablonda otomatik hesap', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil'), 3, { template: CALC_TEMPLATE }))
    te().saveTemplate.mockResolvedValue({ success: true, data: { version: 4, template: CALC_TEMPLATE } })
  })

  it('yüzde alanı orana çevrilir (pay / payda seçilir) ve tanım kayda gider; tür değişince hesap düşer', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    const sla = document.querySelector('[data-field="calc:sla-rate"]')
    fireEvent.change(within(sla).getByLabelText('Otomatik hesap'), { target: { value: 'ratio' } })
    fireEvent.change(within(sla).getByLabelText('Pay (adet)'), { target: { value: 'unmapped-count' } })
    fireEvent.change(within(sla).getByLabelText('Payda (adet)'), { target: { value: 'total-tx' } })
    // Teknik hata oranının türü sayıya çevrilince hesabı düşer
    const rateRow = document.querySelector('[data-slot="te-tpl-field"][data-field="field:error-rate"]')
    fireEvent.change(within(rateRow).getByLabelText('Tür'), { target: { value: 'number' } })
    expect(document.querySelector('[data-field="calc:error-rate"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    const fields = te().saveTemplate.mock.calls[0][1].sections.flatMap((s) => s.fields || [])
    expect(fields.find((f) => f.id === 'sla-rate').calc).toEqual({ kind: 'ratio', num: 'unmapped-count', den: 'total-tx' })
    expect(fields.find((f) => f.id === 'error-rate').calc).toBeUndefined()
  })

  it('görsel adının altında değişkenler doldurulmuş hâli; hesaplanan soru "Hayır ise sorma" seçeneklerinde yok', async () => {
    const tpl = JSON.parse(JSON.stringify(CALC_TEMPLATE))
    tpl.sections.push({ id: 'charts', kind: 'images', title: 'Grafikler', slots: [
      { id: 'monthly-tx', label: '{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri ({ay} {yil})', width: 'half' }] })
    tpl.sections[1].fields.push({ id: 'had-outage', label: 'Kesinti oldu mu?', type: 'yesno' })
    te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil'), 3, { template: tpl }))
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    const preview = document.querySelector('[data-slot="te-tpl-slot"] [data-slot="te-tpl-slot-preview"]')
    expect(preview.textContent).toMatch(/^Örnek: Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri \(\S+ \d{4}\)$/)
    fireEvent.change(screen.getByLabelText('Görsel adı'), { target: { value: '{kanal} Kanalı Kota Hataları' } })
    expect(document.querySelector('[data-slot="te-tpl-slot-preview"]')).toHaveTextContent('Örnek: Mobil Kanalı Kota Hataları')
    // "Hayır ise sorma" seçicisinde elle cevaplanan soru var, hesaplanan "artış" sorusu yok
    const row = document.querySelector('[data-slot="te-tpl-field"][data-field="field:total-tx"]')
    const options = [...within(row).getByLabelText(/“Hayır” ise alanı sorma|If .*No/).querySelectorAll('option')].map((o) => o.textContent)
    expect(options).toContain('Kesinti oldu mu?')
    expect(options).not.toContain('Önceki aya göre artış var mı?')
  })

  it('pay / payda seçilmeden kayıt yapılmaz, hata alanın altında; formüldeki alan şablonda yoksa uyarı', async () => {
    const broken = JSON.parse(JSON.stringify(CALC_TEMPLATE))
    broken.sections[2].fields[0].calc = { kind: 'ratio', num: 'silinmis', den: 'total-tx' }
    te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil'), 3, { template: broken }))
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    expect(within(document.querySelector('[data-field="calc:error-rate"]')).getByText(
      'Formüldeki “silinmis” alanı şablonda yok — raporda değer elle girilir.')).toBeInTheDocument()
    const sla = document.querySelector('[data-field="calc:sla-rate"]')
    fireEvent.change(within(sla).getByLabelText('Otomatik hesap'), { target: { value: 'ratio' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(await within(sla).findByText(/Otomatik hesap için gereken alanları seçin/)).toBeInTheDocument()
    expect(te().saveTemplate).not.toHaveBeenCalled()
  })
})
