import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Anomali konuları (2026-10-18, kullanıcı isteği): konu kartı — gruplar, CANLI pay (taslağa yazılmaz), kesinti Evet'te zorunlu
// Kesinti ID (kayıt denemesinden sonra alanın altında), müşteri etkisi Evet'te müşteri sayısı (Hayır'da gizli ve gönderilmez),
// "konuyu rapora alma" notu, saat aralığı denetimi, rapordaki giriş metninin önizlemesi, otomatik kayıtta geçersiz alanın kayıtlı
// değerine dönmesi, kaydedilmemiş konunun görseli için önce kayıt, görselli konunun silinmesinde onay.
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

const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu? (zaman, yer, etki)', customers_label: 'Etkilenen müşteri sayısı',
  errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const TPL = {
  ...TEMPLATE,
  sections: [
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
    ] },
    ISSUES,
  ],
}
const topic = (extra = {}) => ({ id: 'k1', topic: 'Kart Limiti', title: '', title_auto: true, description: '', ...extra })
const respond = (issues, fields = { 'error-count': 5000 }, tpl = TPL, extra = {}) => reportResponse({ template: tpl,
  values: { fields, issues: issues ? { issues } : {}, texts: {} }, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-18T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })
const card = () => document.querySelector('[data-slot="te-issue"]')
const sentValues = (n = 0) => te().saveReport.mock.calls[n][1]

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('anomali konu kartı', () => {
  it('gruplar ve rapordaki metin: zaman, adet + CANLI pay, kesinti ID, müşteri; çipler kartın başında', async () => {
    te().report.mockResolvedValue(respond([topic({ date: '2026-09-12', start: '14:00', end: '15:30', errors: 170, outage: true,
      outage_id: 'INC-2026-0915', impact: true, customers: 320 })]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const c = card()
    for (const g of ['te-issue-topic', 'te-issue-when', 'te-issue-count', 'te-issue-outage', 'te-issue-impact', 'te-issue-desc', 'te-issue-images']) {
      expect(c.querySelector(`[data-slot="${g}"]`), g).not.toBeNull()
    }
    expect(within(c).getByText('1. Kart Limiti: Hata Artışı')).toBeInTheDocument()
    const lead = c.querySelector('[data-slot="te-issue-lead"]')
    expect(lead.textContent).toContain('12 Eylül 2026, 14:00–15:30 arasında 170 teknik hata alındı; bu, dönemdeki tüm teknik hataların %3,4 kadarıdır. '
      + 'Kesinti kaynaklıdır (Kesinti ID: INC-2026-0915). Müşteri etkisi: 320 müşteri.')
    expect(within(lead).getByText('%3,4').tagName).toBe('STRONG')
    expect(c.querySelector('[data-slot="te-issue-share-value"]').textContent).toBe('%3,4')
    const chips = c.querySelector('[data-slot="te-issue-chips"]')
    expect(within(chips).getByText('12 Eylül 2026 · 14:00–15:30')).toBeInTheDocument()
    expect(within(chips).getByText('170 hata · %3,4')).toBeInTheDocument()
    expect(within(chips).getByText('Kesinti · INC-2026-0915')).toBeInTheDocument()
    expect(within(chips).getByText('320 müşteri')).toBeInTheDocument()
    expect(screen.getByLabelText('Kesinti ID')).toHaveValue('INC-2026-0915')
    expect(screen.getByLabelText('Etkilenen müşteri sayısı')).toHaveValue('320')
  })

  it('pay canlı: adet yazılınca hesaplanır (taslağa yazılmaz); toplam yoksa / konu adedi büyükse "—" ve nedeni', async () => {
    te().report.mockResolvedValue(respond([topic()]))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 5000 }, issues: { issues: [topic({ errors: 170 })] }, texts: {} }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const value = () => card().querySelector('[data-slot="te-issue-share-value"]').textContent
    const note = () => card().querySelector('[data-slot="te-issue-share-note"]').textContent
    expect(value()).toBe('—')
    expect(note()).toBe('Bu konunun teknik hata adedi girilince hesaplanır.')
    fireEvent.change(screen.getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '170' } })
    expect(value()).toBe('%3,4')
    expect(note()).toBe('Bu konudan kaynaklı teknik hata adedi ÷ Ay içindeki teknik hata adedi × 100 — otomatik hesaplanır; PDF\'e ve e-postaya bu değer girer.')
    fireEvent.change(screen.getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '6000' } })
    expect(value()).toBe('—')
    expect(note()).toContain('değerinden büyük')
    fireEvent.change(screen.getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '170' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    const sent = sentValues().issues.issues[0]
    expect(sent.errors).toBe(170)
    expect(sent).not.toHaveProperty('share')                           // pay sunucunun hesabı — gönderilmez
  })

  it('kısaltma (2026-10-18): "1,25M" yazılan konu adedi giriş metninde ve çipte "1,25M"; pay SAYIYLA (1.250.000 ÷ 50.000.000 = %2,5); kayıtta tam sayı + üs; sıfır işareti yok', async () => {
    te().report.mockResolvedValue(respond([topic({ impact: true, customers: 4400 })], { 'error-count': 50_000_000 }))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 50_000_000 }, issues: { issues: [topic({ errors: 1_250_000,
      impact: true, customers: 4400 })] }, texts: {}, units: { 'issues.issues.k1.errors': 6, 'issues.issues.k1.customers': 3 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '1,25M' } })
    fireEvent.change(screen.getByLabelText('Etkilenen müşteri sayısı'), { target: { value: '4,4k' } })
    const c = card()
    expect(c.querySelector('[data-slot="te-issue-share-value"]').textContent).toBe('%2,5')
    const lead = c.querySelector('[data-slot="te-issue-lead"]')
    expect(lead.textContent).toContain('Bu konu nedeniyle 1,25M teknik hata alındı; bu, dönemdeki tüm teknik hataların %2,5 kadarıdır.')
    expect(lead.textContent).toContain('Müşteri etkisi: 4,4k müşteri.')
    expect(within(lead).getByText('1,25M').tagName).toBe('STRONG')
    expect(lead.textContent).not.toContain('1.250.000')
    const chips = c.querySelector('[data-slot="te-issue-chips"]')
    expect(within(chips).getByText('1,25M hata · %2,5')).toBeInTheDocument()
    expect(within(chips).getByText('4,4k müşteri')).toBeInTheDocument()
    expect(c.querySelectorAll('[data-slot="te-zero-ok"]')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    const sent = sentValues()
    expect(sent.issues.issues[0]).toMatchObject({ errors: 1_250_000, customers: 4400 })
    expect(sent.issues.issues[0]).not.toHaveProperty('share')
    expect(sent.units).toEqual({ 'issues.issues.k1.errors': 6, 'issues.issues.k1.customers': 3 })
  })

  it('kısaltma: saklı üs yeniden açılınca kutuda ve metinde "1,25M"; 0 adet üssüz "0" (işaret yok); tam yazılan sayı üssü siler', async () => {
    te().report.mockResolvedValue(reportResponse({ template: TPL, values: { fields: { 'error-count': 50_000_000 },
      issues: { issues: [topic({ errors: 1_250_000 }), { ...topic({ errors: 0 }), id: 'k2', topic: 'Sıfır' }] }, texts: {},
      units: { 'issues.issues.k1.errors': 6 } } }))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 50_000_000 }, issues: { issues: [topic({ errors: 1_250_000 })] }, texts: {} }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const [first, second] = document.querySelectorAll('[data-slot="te-issue"]')
    expect(within(first).getByLabelText('Bu konudan kaynaklı teknik hata adedi')).toHaveValue('1,25M')
    expect(first.querySelector('[data-slot="te-issue-lead"]').textContent).toContain('Bu konu nedeniyle 1,25M teknik hata alındı')
    expect(second.querySelector('[data-slot="te-issue-lead"]').textContent).toContain('Bu konu nedeniyle 0 teknik hata alındı')
    expect(second.querySelectorAll('[data-slot="te-zero-ok"]')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Kaydet' })).toBeDisabled()          // sahte "kaydedilmemiş değişiklik" yok
    fireEvent.change(within(first).getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '1250000' } })
    expect(first.querySelector('[data-slot="te-issue-lead"]').textContent).toContain('Bu konu nedeniyle 1.250.000 teknik hata alındı')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(sentValues()).not.toHaveProperty('units')
  })

  it('kesinti Evet → Kesinti ID açılır ve odak alır; boşken kayıt durur ve hata alanın altında; yazınca kalkar ve kaydedilir', async () => {
    te().report.mockResolvedValue(respond([topic({ errors: 10 })]))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 5000 }, issues: { issues: [topic({ errors: 10, outage: true, outage_id: 'INC-7' })] }, texts: {} }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.queryByLabelText('Kesinti ID')).toBeNull()
    const outage = within(card().querySelector('[data-slot="te-issue-outage"]'))
    fireEvent.click(outage.getByRole('radio', { name: 'Evet' }))
    const id = await screen.findByLabelText('Kesinti ID')
    await waitFor(() => expect(document.activeElement).toBe(id))
    expect(id).toHaveAttribute('aria-required', 'true')
    // Zorunlu alanın hatası henüz yok (kullanıcı yazmak üzere); kayıt denemesinde görünür
    expect(screen.queryByText('Kesinti ID\'sini yazın: kesinti kaynaklı konu için zorunludur.')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Kesinti ID\'sini yazın: kesinti kaynaklı konu için zorunludur.')).toBeInTheDocument()
    expect(id).toHaveAttribute('aria-invalid', 'true')
    expect(te().saveReport).not.toHaveBeenCalled()
    fireEvent.change(id, { target: { value: 'INC-7' } })
    await waitFor(() => expect(screen.queryByText('Kesinti ID\'sini yazın: kesinti kaynaklı konu için zorunludur.')).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(sentValues().issues.issues[0]).toMatchObject({ outage: true, outage_id: 'INC-7' })
    // Hayır'a dönünce ID gizlenir ve gönderilmez
    fireEvent.click(outage.getByRole('radio', { name: 'Hayır' }))
    expect(screen.queryByLabelText('Kesinti ID')).toBeNull()
    expect(within(card()).getByText('Kesinti kaynaklı değildir.', { exact: false })).toBeInTheDocument()
  })

  it('müşteri etkisi Evet → sayı açılır; Hayır → sayı gizli, gönderilmez, "Müşteri etkisi yok" notu ve metni', async () => {
    te().report.mockResolvedValue(respond([topic({ errors: 10 })]))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 5000 }, issues: { issues: [topic({ errors: 10, impact: false })] }, texts: {} }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const impact = within(card().querySelector('[data-slot="te-issue-impact"]'))
    expect(screen.queryByLabelText('Etkilenen müşteri sayısı')).toBeNull()
    fireEvent.click(impact.getByRole('radio', { name: 'Evet' }))
    const customers = await screen.findByLabelText('Etkilenen müşteri sayısı')
    await waitFor(() => expect(document.activeElement).toBe(customers))
    fireEvent.change(customers, { target: { value: '320' } })
    expect(card().querySelector('[data-slot="te-issue-lead"]').textContent).toContain('Müşteri etkisi: 320 müşteri.')
    fireEvent.click(impact.getByRole('radio', { name: 'Hayır' }))
    expect(screen.queryByLabelText('Etkilenen müşteri sayısı')).toBeNull()
    expect(card().querySelector('[data-slot="te-issue-noimpact-note"]').textContent).toBe('Raporda “Müşteri etkisi yok.” yazar; müşteri sayısı sorulmaz.')
    expect(card().querySelector('[data-slot="te-issue-lead"]').textContent).toContain('Müşteri etkisi yok.')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    const sent = sentValues().issues.issues[0]
    expect(sent.impact).toBe(false)
    expect(sent).not.toHaveProperty('customers')                       // gizli değer kayda gitmez
  })

  it('"konuyu rapora alma": etkisiz konu kartta kalır ama "rapora girmeyecek" çipi + notu, bölümde sayısı', async () => {
    const tpl = { ...TPL, sections: [TPL.sections[0], { ...ISSUES, no_impact_display: 'hide_topic' }] }
    te().report.mockResolvedValue(respond([topic({ errors: 10, impact: false }), topic({ id: 'k2', topic: 'Fatura', impact: true })], undefined, tpl))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const [first, second] = document.querySelectorAll('[data-slot="te-issue"]')
    expect(first).toHaveAttribute('data-hidden', 'true')
    expect(second).not.toHaveAttribute('data-hidden')
    expect(within(first).getByText('Rapora girmeyecek')).toBeInTheDocument()
    expect(first.querySelector('[data-slot="te-issue-hidden-note"]').textContent).toContain('Bu konu rapora girmeyecek')
    expect(document.querySelector('[data-slot="te-issues-hidden-note"]').textContent).toContain('1 konu müşteri etkisi olmadığı için rapora')
  })

  it('saat aralığı: bitiş önce → hata hemen; "ertesi gün" işaretlenince kalkar; ay dışı tarih hemen hata; tarih takvimden seçilir', async () => {
    te().report.mockResolvedValue(respond([topic({ date: '2026-10-02' })]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(await screen.findByText('Tarih raporun ayı içinde olmalı (Eylül 2026). Başka aya ait anomaliyi o ayın raporuna yazın.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Başlangıç saati'), { target: { value: '23:30' } })
    fireEvent.change(screen.getByLabelText('Bitiş saati'), { target: { value: '01:15' } })
    expect(await screen.findByText(/Bitiş saati başlangıçtan sonra olmalı/)).toBeInTheDocument()
    expect(screen.getByLabelText('Bitiş saati')).toHaveAttribute('aria-invalid', 'true')
    fireEvent.click(screen.getByRole('checkbox', { name: 'Ertesi gün bitti' }))
    await waitFor(() => expect(screen.queryByText(/Bitiş saati başlangıçtan sonra olmalı/)).toBeNull())
    // Takvimden ayın bir günü: tetiğin adı alanın etiketi
    fireEvent.click(screen.getByLabelText('Tarih'))
    const day = new Date(2026, 8, 30).toLocaleDateString()
    const btn = await waitFor(() => { const b = document.querySelector(`[data-day="${day}"]`); expect(b).not.toBeNull(); return b })
    fireEvent.click(btn)
    await waitFor(() => expect(screen.queryByText(/Tarih raporun ayı içinde olmalı/)).toBeNull())
    expect(card().querySelector('[data-slot="te-issue-lead"]').textContent).toContain('30 Eylül 2026, 23:30 – ertesi gün 01:15 arasında yaşandı.')
  })

  it('otomatik kayıt: geçersiz anomali cevabı (bitiş önce) kayıtlı değerinde gider, geçerli değişiklikler kaydedilir', async () => {
    const stored = topic({ date: '2026-09-12', start: '10:00', end: '11:00', errors: 5 })
    te().report.mockResolvedValue(respond([stored]))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 5000 }, issues: { issues: [{ ...stored, errors: 7 }] }, texts: {} }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '7' } })
    fireEvent.change(screen.getByLabelText('Bitiş saati'), { target: { value: '09:00' } })
    act(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    const sent = sentValues().issues.issues[0]
    expect(sent.errors).toBe(7)
    expect(sent).toMatchObject({ date: '2026-09-12', start: '10:00', end: '11:00' })
    expect(te().saveReport.mock.calls[0][3]).toMatchObject({ auto: true })
  })

  it('kaydedilmemiş konunun görseli: önce rapor kaydedilir, sonra konu yuvasına yüklenir; görselli konu silinirken onay', async () => {
    te().report.mockResolvedValue(respond([]))
    let id = null
    te().saveReport.mockImplementation(async (_r, values) => saved({ ...values, issues: values.issues }))
    te().uploadImage.mockImplementation(async (_r, slot) => ({ success: true, data: { id: 601, slot_id: slot, file_name: 'a.png',
      width: 800, height: 400, size_bytes: 2048 } }))
    const had = 'createImageBitmap' in globalThis
    const prev = globalThis.createImageBitmap
    globalThis.createImageBitmap = vi.fn(async () => ({ width: 800, height: 400, close() {} }))
    try {
      render(<TechErrorReportsPage />)
      await screen.findByRole('heading', { name: TITLE })
      fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
      fireEvent.change(screen.getByLabelText('Konu'), { target: { value: 'Kart' } })
      id = card().getAttribute('data-issue')
      const slot = card().querySelector(`[data-slot-id="iss:issues:${id}:1"]`)
      expect(slot).not.toBeNull()
      const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13])], 'a.png', { type: 'image/png' })
      fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [png] } })
      await waitFor(() => expect(te().uploadImage).toHaveBeenCalledWith('100', `iss:issues:${id}:1`, png))
      expect(te().saveReport).toHaveBeenCalledTimes(1)
      expect(te().saveReport.mock.invocationCallOrder[0]).toBeLessThan(te().uploadImage.mock.invocationCallOrder[0])
      expect(sentValues().issues.issues[0].id).toBe(id)
      // Görsel yüklendi: açıklama alanı çıkar; ikinci yuva açılır
      expect(await screen.findByLabelText('Görsel 1 açıklaması (isteğe bağlı)')).toBeInTheDocument()
      expect(card().querySelector(`[data-slot-id="iss:issues:${id}:2"]`)).not.toBeNull()
      // Silme onay ister (kayıtta görseller de silinir); vazgeçince konu kalır
      fireEvent.click(card().querySelector('[data-slot="te-issue-remove"]'))
      const dlg = await screen.findByRole('dialog', { name: 'Konu ve görselleri kaldırılsın mı?' })
      expect(dlg.textContent).toContain('1 görseli de kalıcı olarak silinir')
      fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Konu ve görselleri kaldırılsın mı?' })).toBeNull())
      expect(card()).not.toBeNull()
    } finally {
      if (had) globalThis.createImageBitmap = prev
      else delete globalThis.createImageBitmap
    }
  })

  it('eski düzen bölüm (soru yok): müşteri + hata adedi sorulur, zaman / kesinti / etki / görsel / metin önizlemesi yok', async () => {
    te().report.mockResolvedValue(reportResponse({ values: { fields: {}, issues: { issues: [topic({ customers: 12, errors: 3 })] }, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const c = card()
    expect(within(c).getByLabelText('Etkilenen müşteri sayısı')).toHaveValue('12')
    expect(within(c).getByLabelText('Bu sorundan kaynaklı teknik hata adedi')).toHaveValue('3')
    for (const g of ['te-issue-when', 'te-issue-outage', 'te-issue-impact', 'te-issue-images', 'te-issue-lead', 'te-issue-share']) {
      expect(c.querySelector(`[data-slot="${g}"]`), g).toBeNull()
    }
  })
})
