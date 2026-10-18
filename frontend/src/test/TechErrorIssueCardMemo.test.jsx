import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Anomali kartları yazarken yeniden ÇİZİLMEZ (inceleme 2026-10-18 — ImagesBlock memo kuralının eşi): bir konunun alanına yazmak
// yalnız o kartı çizer; başka konunun kartı (görsel yuvaları dahil) çizilmez. Kart memo'su sabit, kimlikle çalışan geri çağrılar,
// karta yalnız kendi görselleri ve kendi hata anahtarları ile tutar. Ölçüm: konu görseli yuvası (ImageSlotTile) kaç kez çizildi.
const renders = new Map()
vi.mock('../components/techerror/ReportImages.jsx', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    ImageSlotTile: (props) => {
      renders.set(props.slot.id, (renders.get(props.slot.id) || 0) + 1)
      return <div data-slot="te-image-slot" data-slot-id={props.slot.id} />
    },
  }
})
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

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu?', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const TPL = { ...TEMPLATE, sections: [
  { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [{ id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' }] },
  ISSUES,
] }
const topic = (id, name) => ({ id, topic: name, title: '', title_auto: true, description: '' })

beforeEach(() => {
  vi.clearAllMocks()
  renders.clear()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
})

describe('anomali kartı memo', () => {
  it('bir konuya yazmak öbür konunun kartını (görsel yuvasını) yeniden çizmez; yazılan konunun kartı çizilir', async () => {
    te().report.mockResolvedValue(reportResponse({ template: TPL, values: { fields: { 'error-count': 5000 },
      issues: { issues: [topic('k1', 'Kart'), topic('k2', 'Fatura')] }, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(renders.get('iss:issues:k2:1')).toBeGreaterThan(0))
    const k1Before = renders.get('iss:issues:k1:1')
    const k2Before = renders.get('iss:issues:k2:1')
    const [first] = screen.getAllByLabelText('Konu')
    for (const v of ['K', 'Ka', 'Kar', 'Kart 2']) fireEvent.change(first, { target: { value: v } })
    // Kayıt / metrik değişmedi: yazılan konu çizildi, öbürü hiç çizilmedi
    expect(renders.get('iss:issues:k1:1')).toBeGreaterThan(k1Before)
    expect(renders.get('iss:issues:k2:1')).toBe(k2Before)
    // Öbür konunun alanı düzenlenince sıra ona geçer — ilk konu yeniden çizilmez
    const k1After = renders.get('iss:issues:k1:1')
    fireEvent.change(screen.getAllByLabelText('Konu')[1], { target: { value: 'Fatura 2' } })
    expect(renders.get('iss:issues:k1:1')).toBe(k1After)
    expect(renders.get('iss:issues:k2:1')).toBeGreaterThan(k2Before)
    // Taslak her iki değişikliği de tutar (işlevsel güncelleme — kapanıştaki eski liste öbür değişikliği ezmedi)
    expect(screen.getAllByLabelText('Konu').map((i) => i.value)).toEqual(['Kart 2', 'Fatura 2'])
  })
})
