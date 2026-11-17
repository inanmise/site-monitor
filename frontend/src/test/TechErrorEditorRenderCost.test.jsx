import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

/**
 * Rapor düzenleyicide yazma maliyeti (2026-11-17 performans; canlı e2e bekçisi 390 px'te 10 tuşta 8 uzun görev buldu).
 *  - Gösterge alanı memo'lu: bir alana yazmak AYNI bölümdeki öteki alanları yeniden çizdirmez.
 *  - Bölümler memo'lu + düzenlenmeyen bölümler ertelenmiş taslakla çizilir: değeri değişmeyen bölümün alanları çizilmez.
 *  - Doğruluk: düzenlenen bölümde denetimli metin girişi harf kaybetmez; bölüm değiştirince önceki bölümün değeri korunur.
 * Ölçü: her alan etiketinin yanındaki kopyalama düğmesinin çizim sayısı (alan çizilince düğme de çizilir).
 */
const renders = vi.hoisted(() => ({ byText: new Map() }))
vi.mock('../components/techerror/TechErrorParts.jsx', async (importOriginal) => {
  const m = await importOriginal()
  function CountingCopy(props) {
    renders.byText.set(props.text, (renders.byText.get(props.text) || 0) + 1)
    return m.CopyTextButton(props)
  }
  return { ...m, CopyTextButton: CountingCopy }
})

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
    { id: 'data', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Teknik hata adedi', type: 'number' },
      { id: 'sla-rate', label: 'SLA oranı', type: 'percent' },
      { id: 'note', label: 'Kısa not', type: 'text' },
    ] },
    { id: 'other', kind: 'metrics', title: 'Diğer', fields: [
      { id: 'quota-count', label: 'Kota hatası adedi', type: 'number' },
      { id: 'owner', label: 'Sorumlu', type: 'text' },
    ] },
  ],
}

const report = () => reportResponse({ template: TEMPLATE, values: { fields: {}, issues: {}, texts: {} }, previous: null })
const count = (label) => renders.byText.get(label) || 0

beforeEach(() => {
  vi.clearAllMocks()
  renders.byText = new Map()
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

describe('rapor düzenleyici yazma maliyeti', () => {
  it('bir alana yazmak aynı bölümün ve öteki bölümün değişmeyen alanlarını yeniden çizdirmez', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const total = screen.getByLabelText('Toplam işlem adedi')
    await waitFor(() => expect(count('SLA oranı')).toBeGreaterThan(0))
    const before = { sla: count('SLA oranı'), err: count('Teknik hata adedi'), quota: count('Kota hatası adedi'), total: count('Toplam işlem adedi') }
    for (const v of ['1', '12', '123', '1234', '12345']) fireEvent.change(total, { target: { value: v } })
    await waitFor(() => expect(total).toHaveValue('12345'))
    expect(count('Toplam işlem adedi'), 'yazılan alan çizilir').toBeGreaterThan(before.total)
    // Eskiden her tuş bölümün bütün alanlarını (ve öteki bölümleri) yeniden çiziyordu: 5 tuşta en az 5 çizim
    expect(count('SLA oranı') - before.sla, 'aynı bölümün değişmeyen alanı').toBe(0)
    expect(count('Teknik hata adedi') - before.err, 'aynı bölümün değişmeyen alanı').toBe(0)
    expect(count('Kota hatası adedi') - before.quota, 'öteki bölümün değişmeyen alanı').toBe(0)
  })

  it('denetimli metin girişi harf kaybetmez; bölüm değiştirince önceki bölümün değeri korunur ve kayda gider', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const note = screen.getByLabelText('Kısa not')
    for (const v of ['a', 'ab', 'abc', 'abcd']) fireEvent.change(note, { target: { value: v } })
    expect(note).toHaveValue('abcd')
    const owner = screen.getByLabelText('Sorumlu')
    for (const v of ['E', 'Ek', 'Eki', 'Ekip']) fireEvent.change(owner, { target: { value: v } })
    expect(owner).toHaveValue('Ekip')
    const quota = screen.getByLabelText('Kota hatası adedi')
    fireEvent.change(quota, { target: { value: '42' } })
    // Üç bölüm değişikliğinden sonra ilk bölüm hâlâ güncel (ertelenmiş taslak yetişir)
    await waitFor(() => expect(screen.getByLabelText('Kısa not')).toHaveValue('abcd'))
    expect(screen.getByLabelText('Sorumlu')).toHaveValue('Ekip')
    fireEvent.click(screen.getByRole('button', { name: /^(Kaydet|Save)$/ }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalled())
    const sent = te().saveReport.mock.calls.at(-1)[1]
    expect(sent.fields).toMatchObject({ note: 'abcd', owner: 'Ekip', 'quota-count': 42 })
  })
})
