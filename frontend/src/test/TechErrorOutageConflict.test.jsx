import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { TEMPLATE, channel, overview, reportResponse, templateResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Kesinti cevabı konularla çelişiyor (2026-10-18 bütünleştirme incelemesi): "Dönemde kesinti oldu mu?" Hayır ama bir anomali
// konusu kesinti kaynaklı işaretli → kesinti alanının yanında ve özet kartında bilgi bandı (shadcn Alert, tone info). Evet +
// kesintisiz konu çelişki değildir; kayıt engellenmez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(),
      deleteImage: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { OUTAGE_FIELD, outageAnswerConflict } from '../components/techerror/techErrorModel.js'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu?', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
}
const TPL = {
  ...TEMPLATE,
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{f:had-outage?Dönemde kesinti yaşanmıştır.|Dönemde kesinti yaşanmamıştır.}',
    ] },
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
      { id: OUTAGE_FIELD, label: 'Dönemde kesinti oldu mu?', type: 'yesno' },
    ] },
    ISSUES,
  ],
}
const topic = (id, extra = {}) => ({ id, topic: `Konu ${id}`, title: '', title_auto: true, description: '', ...extra })
const respond = (hadOutage, issues) => reportResponse({ template: TPL,
  values: { fields: { 'error-count': 5000, ...(hadOutage == null ? {} : { [OUTAGE_FIELD]: hadOutage }) }, issues: { issues }, texts: {} } })
const banners = () => [...document.querySelectorAll('[data-slot="te-outage-conflict"]')]

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
})

describe('kesinti cevabı ↔ anomali konuları', () => {
  it('model: Hayır + kesinti kaynaklı konu sayısı; Evet, boş cevap, kesinti sorulmayan bölüm ve kesintisiz konu çelişki değil', () => {
    const v = (had, issues) => ({ fields: had == null ? {} : { [OUTAGE_FIELD]: had }, issues: { issues } })
    expect(outageAnswerConflict(TPL, v(false, [topic('a', { outage: true }), topic('b', { outage: true }), topic('c', { outage: false })]))).toBe(2)
    expect(outageAnswerConflict(TPL, v(true, [topic('a', { outage: true })]))).toBe(0)
    expect(outageAnswerConflict(TPL, v(true, [topic('a', { outage: false })]))).toBe(0)
    expect(outageAnswerConflict(TPL, v(null, [topic('a', { outage: true })]))).toBe(0)
    expect(outageAnswerConflict(TPL, v(false, [topic('a', { outage: false })]))).toBe(0)
    const noAsk = { ...TPL, sections: TPL.sections.map((s) => (s.kind === 'issues' ? { ...s, ask_outage: false } : s)) }
    expect(outageAnswerConflict(noAsk, v(false, [topic('a', { outage: true })]))).toBe(0)
    const noField = { ...TPL, sections: TPL.sections.filter((s) => s.kind !== 'metrics') }
    expect(outageAnswerConflict(noField, v(false, [topic('a', { outage: true })]))).toBe(0)
    expect(outageAnswerConflict(null, null)).toBe(0)
  })

  it('Hayır + kesinti kaynaklı konu: alanın yanında ve özet kartında bilgi bandı; Evet seçilince kalkar; kayıt engellenmez', async () => {
    te().report.mockResolvedValue(respond(false, [topic('k1', { outage: true, outage_id: 'INC-1' })]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(banners()).toHaveLength(2))
    expect(banners().map((b) => b.getAttribute('data-where')).sort()).toEqual(['field', 'summary'])
    const field = banners().find((b) => b.getAttribute('data-where') === 'field')
    expect(field.closest('[data-field="values.had-outage"]')).not.toBeNull()
    expect(within(field).getByRole('status')).toHaveAttribute('data-tone', 'info')
    expect(field.textContent).toContain('Kesinti cevabı konularla uyuşmuyor')
    expect(field.textContent).toContain('“Dönemde kesinti oldu mu?” sorusunun cevabı Hayır, ama bir anomali konusu kesinti kaynaklı işaretli')
    expect(banners().find((b) => b.getAttribute('data-where') === 'summary').closest('[data-slot="te-summary"]')).not.toBeNull()
    // Evet: çelişki yok (Evet + kesinti kaynaklı konu tutarlı)
    const yesNo = document.querySelector('[data-field="values.had-outage"]')
    fireEvent.click(within(yesNo).getByRole('radio', { name: 'Evet' }))
    await waitFor(() => expect(banners()).toHaveLength(0))
  })

  it('İngilizce arayüz, iki konu: çoğul metin; Evet + kesintisiz konu ve Hayır + kesintisiz konu bandsız', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    te().report.mockResolvedValue(respond(false, [topic('k1', { outage: true, outage_id: 'INC-1' }), topic('k2', { outage: true, outage_id: 'INC-2' })]))
    const view = render(<TechErrorReportsPage />)
    await waitFor(() => expect(banners()).toHaveLength(2))
    expect(banners()[0].textContent).toContain('2 anomaly topics are marked as caused by an outage')
    view.unmount()
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    te().report.mockResolvedValue(respond(true, [topic('k1', { outage: false })]))
    const yes = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(banners()).toHaveLength(0)
    yes.unmount()
    te().report.mockResolvedValue(respond(false, [topic('k1', { outage: false })]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(banners()).toHaveLength(0)
  })
})
