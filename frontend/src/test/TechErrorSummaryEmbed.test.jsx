import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Özet kartına gömülü bölümler (2026-10-17, kullanıcı kararı: "map yapılmamış ve kota hataları yönetici özeti bölümünde
// girilsin, ayrı bölüm olmasın"): raporda gömülü bölümün alanları özet kartının İÇİNDE, açıklama ilgili cümlenin altında,
// söylenmemiş değer kendi satırında; şablon düzenleyicide "Gösterim yeri" seçici, rozet, önizleme ve özet silme onayı.
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

const EMBED_TEMPLATE = {
  ...TEMPLATE,
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{kanal} Kanalı {ay} {yil} teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      'Map yapılmamış hata adedi {f:unmapped-count} olarak kaydedilmiştir.',
      'Kapanış cümlesi.',
    ] },
    { id: 'unmapped', kind: 'metrics', title: 'Map Yapılmamış Hatalar', host: 'exec', fields: [
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'unmapped-new', label: 'Yeni hata kodu var mı?', type: 'yesno' },
      { id: 'unmapped-note', label: 'Map yapılmamış hatalar hakkında açıklama', type: 'textarea' },
    ] },
    TEMPLATE.sections[0],
    TEMPLATE.sections[1],
  ],
}

const embedReport = (extra = {}) => reportResponse({ template: EMBED_TEMPLATE, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-17T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

describe('rapor — özet kartına gömülü bölüm', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(embedReport())
  })

  it('gömülü bölüm özet kartının içinde (ayrı kart yok); adet cümleye kalın girer, açıklama cümlenin altında, söylenmemiş evet/hayır değer satırı; kayıt alan değerleriyle gider', async () => {
    te().saveReport.mockImplementation(async (id, values) => saved(values))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const card = container.querySelector('[data-slot="te-summary"]')
    expect(container.querySelector('[data-slot="te-metrics"][data-section="unmapped"]')).toBeNull()
    const emb = card.querySelector('[data-slot="te-sum-embedded"][data-section="unmapped"]')
    expect(emb).not.toBeNull()
    expect(within(emb).getByRole('heading', { level: 4, name: 'Map Yapılmamış Hatalar' })).toBeInTheDocument()
    expect(card.querySelector('[data-slot="te-sum-embedded-list"]')).toHaveTextContent('Özetteki veriler')
    // Önce tek metin: ilk cümle + kapanış aynı paragrafta (map cümlesi adedi bekliyor)
    expect(card.querySelector('[data-slot="te-sum-text"]')).toHaveTextContent(
      'Mobil Kanalı Eylül 2026 teknik hata oranı %0,42 olarak gerçekleşmiştir. Kapanış cümlesi.')
    expect(container.querySelector('[data-slot="te-sum-pending"]')).toHaveTextContent('Map yapılmamış hata adedi')

    fireEvent.change(within(emb).getByLabelText('Map yapılmamış hata adedi'), { target: { value: '1.250' } })
    await waitFor(() => expect(card.querySelector('[data-slot="te-sum-text"]'))
      .toHaveTextContent('Map yapılmamış hata adedi 1.250 olarak kaydedilmiştir.'))
    expect(within(card.querySelector('[data-slot="te-sum-text"]')).getByText('1.250').tagName).toBe('STRONG')
    expect(container.querySelector('[data-slot="te-sum-pending"]')).toBeNull()

    fireEvent.change(within(emb).getByLabelText('Map yapılmamış hatalar hakkında açıklama'), { target: { value: 'Yeni **kodlar** eşlenmedi' } })
    await waitFor(() => expect(card.querySelector('[data-slot="te-sum-note"][data-note-field="unmapped-note"]'))
      .toHaveTextContent('Yeni kodlar eşlenmedi'))
    const parts = [...card.querySelectorAll('[data-slot="te-sum-text"] > *')]
    expect(parts.map((p) => p.dataset.slot)).toEqual(['te-sum-part', 'te-sum-note', 'te-sum-part'])
    expect(parts[0]).toHaveTextContent('Mobil Kanalı Eylül 2026 teknik hata oranı %0,42 olarak gerçekleşmiştir. Map yapılmamış hata adedi 1.250 olarak kaydedilmiştir.')
    expect(parts[1]).toHaveTextContent('Map yapılmamış hatalar hakkında açıklama')     // notun başlığı alanın adı
    expect(within(parts[1]).getByText('kodlar').tagName).toBe('STRONG')
    expect(parts[2]).toHaveTextContent('Kapanış cümlesi.')

    // Hiçbir cümlenin başvurmadığı evet/hayır: kendi satırında, kalın değil, soru işaretli ad iki nokta almaz
    fireEvent.click(within(within(emb).getByLabelText('Yeni hata kodu var mı?')).getByRole('radio', { name: 'Evet' }))
    await waitFor(() => expect(card.querySelector('[data-slot="te-sum-text"]')).toHaveTextContent('Yeni hata kodu var mı? Evet'))
    expect(within(card.querySelector('[data-slot="te-sum-text"]')).queryByText('Evet', { selector: 'strong' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].fields).toMatchObject({ 'error-rate': 0.42, 'unmapped-count': 1250, 'unmapped-new': true,
      'unmapped-note': 'Yeni **kodlar** eşlenmedi' })
    expect(te().saveReport.mock.calls[0][1].texts).toEqual({})
  })

  it('elle düzenlemede gömülü alanlar kartta kalır ve adetlerin metne kendiliğinden girmediği söylenir; notlar yine altında', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Metni elle düzenle' }))
    await screen.findByRole('textbox', { name: 'Yönetici Özeti' })
    expect(container.querySelector('[data-slot="te-sum-manual-embedded"]'))
      .toHaveTextContent('Özet kartındaki adetler elle yazılan metne kendiliğinden girmez; açıklamalar yine metnin altına eklenir.')
    expect(container.querySelector('[data-slot="te-sum-embedded"][data-section="unmapped"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="te-metrics"][data-section="unmapped"]')).toBeNull()
  })

  it('salt okunur izleyici gömülü alanları kapalı görür, özet önizlemesi açıklamayı cümlenin altında gösterir', async () => {
    te().report.mockResolvedValue(embedReport({ can_edit: false,
      values: { fields: { 'error-rate': 0.42, 'unmapped-count': 7, 'unmapped-note': 'Not' }, issues: {}, texts: {} } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const card = container.querySelector('[data-slot="te-summary"]')
    const parts = [...card.querySelectorAll('[data-slot="te-sum-text"] > *')]
    expect(parts.map((p) => p.dataset.slot)).toEqual(['te-sum-part', 'te-sum-note', 'te-sum-part'])
    expect(within(card.querySelector('[data-slot="te-sum-embedded"]')).getByLabelText('Map yapılmamış hata adedi')).toBeDisabled()
  })
})

describe('şablon düzenleyici — gösterim yeri', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().template.mockImplementation(async (id) => ({ ...templateResponse(channel(Number(id), 'Mobil')),
      data: { ...templateResponse(channel(Number(id), 'Mobil')).data, template: EMBED_TEMPLATE } }))
  })

  it('gömülü bölüm rozet taşır ve önizlemede özetin altında; "Ayrı bölüm olarak" host anahtarını kaldırır; başka bölüm özete taşınır; kayıt yalnız seçimi taşır', async () => {
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    const unCard = container.querySelector('[data-slot="te-tpl-section"][data-section="unmapped"]')
    expect(within(unCard).getByText('Özet kartında')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="te-tpl-preview-embedded"][data-section="unmapped"]')).toHaveTextContent('Map Yapılmamış Hatalar')
    expect(container.querySelector('[data-slot="te-tpl-preview-section"][data-section="unmapped"]')).toBeNull()
    const sel = within(unCard).getByLabelText('Gösterim yeri')
    expect(sel).toHaveValue('exec')
    expect(within(sel).getByRole('option', { name: 'Yönetici özeti kartında: Yönetici Özeti' })).toBeInTheDocument()
    fireEvent.change(sel, { target: { value: '' } })
    expect(within(unCard).queryByText('Özet kartında')).toBeNull()
    expect(container.querySelector('[data-slot="te-tpl-preview-section"][data-section="unmapped"]')).not.toBeNull()
    // "Verileri" bölümü özete taşınır
    const baseCard = container.querySelector('[data-slot="te-tpl-section"][data-section="summary"]')
    fireEvent.change(within(baseCard).getByLabelText('Gösterim yeri'), { target: { value: 'exec' } })
    expect(within(baseCard).getByText('Özet kartında')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="te-tpl-preview-embedded"][data-section="summary"]')).not.toBeNull()
    // Özet ya da görsel bölümünde seçici yok
    expect(within(container.querySelector('[data-slot="te-tpl-section"][data-section="exec"]')).queryByLabelText('Gösterim yeri')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    const sent = te().saveTemplate.mock.calls[0][1]
    expect(sent.sections.find((s) => s.id === 'unmapped')).not.toHaveProperty('host')
    expect(sent.sections.find((s) => s.id === 'summary').host).toBe('exec')
    expect(sent.sections.find((s) => s.id === 'exec')).not.toHaveProperty('host')
  })

  it('özet bölümü kaldırılırken kartındaki bölüm sayısı söylenir; kaldırılınca o bölümler ayrı bölüm olur (host düşer, hata yok, kayıt geçer)', async () => {
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    fireEvent.click(screen.getByRole('button', { name: '“Yönetici Özeti” bölümünü kaldır' }))
    const dlg = await screen.findByRole('dialog', { name: 'Bölüm kaldırılsın mı?' })
    expect(within(dlg).getByText(/Bu özetin kartında 1 bölüm gösteriliyor; özet silinirse onlar ayrı bölüm olarak gösterilir\./)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(screen.queryByLabelText('1. cümle')).toBeNull())
    const unCard = container.querySelector('[data-slot="te-tpl-section"][data-section="unmapped"]')
    expect(within(unCard).queryByText('Özet kartında')).toBeNull()
    expect(within(unCard).queryByText(/gösterileceği yönetici özeti bölümü şablonda yok/)).toBeNull()
    expect(within(unCard).queryByLabelText('Gösterim yeri')).toBeNull()       // özet kalmadı: seçici gizli
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    const sent = te().saveTemplate.mock.calls[0][1]
    expect(sent.sections.find((s) => s.id === 'exec')).toBeUndefined()
    expect(sent.sections.find((s) => s.id === 'unmapped')).not.toHaveProperty('host')
  })
})
