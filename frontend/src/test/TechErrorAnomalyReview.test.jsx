import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Anomali konuları — inceleme düzeltmeleri (2026-10-18): konu görselinden önceki kaydın NEDENE göre iletisi (eski yedek /
// çakışma / ağ / sunucu / geçersiz alan), eski yedek geri yüklenmişken sessiz kayıt YOK, görsel silinince açıklaması taslaktan
// düşer, ay adı arayüz dilinde, gizli zorunlu alan bölüm rozetinde ve durum satırında adıyla, "Ertesi gün bitti" iki saat
// girilene kadar kapalı, yarım saat bekleyen giriş sayılır, görselin adı konuyu taşır, konu başlığı bir başlık, "şablonun son
// hâlini uygula" rapora girmeyecek konuları söyler.
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
const respond = (issues, extra = {}) => reportResponse({ template: TPL,
  values: { fields: { 'error-count': 5000 }, issues: issues ? { issues } : {}, texts: {} }, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-18T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })
const card = () => document.querySelector('[data-slot="te-issue"]')
const PNG = () => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13])], 'a.png', { type: 'image/png' })

let hadBitmap
let prevBitmap
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
  hadBitmap = 'createImageBitmap' in globalThis
  prevBitmap = globalThis.createImageBitmap
  globalThis.createImageBitmap = vi.fn(async () => ({ width: 800, height: 400, close() {} }))
})

afterEach(() => {
  if (hadBitmap) globalThis.createImageBitmap = prevBitmap
  else delete globalThis.createImageBitmap
  vi.restoreAllMocks()
})

async function addTopicAndUpload() {
  fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
  fireEvent.change(screen.getByLabelText('Konu'), { target: { value: 'Kart' } })
  const id = card().getAttribute('data-issue')
  const slot = card().querySelector(`[data-slot-id="iss:issues:${id}:1"]`)
  fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [PNG()] } })
  return { id, slot }
}

describe('konu görselinden önceki kayıt', () => {
  it('eski sürümlü yedek geri yüklendiyse kayıt YAPILMAZ (başkasının yeni kaydı ezilmez), yuva nedenini söyler', async () => {
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 3, at: Date.now(),
      values: { fields: { 'error-count': 5000 }, issues: { issues: [topic()] }, texts: {} } }))
    te().report.mockResolvedValue(respond([]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(await screen.findByRole('button', { name: 'Geri yükle' }))
    const slot = await waitFor(() => { const s = card()?.querySelector('[data-slot-id="iss:issues:k1:1"]'); expect(s).toBeTruthy(); return s })
    fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [PNG()] } })
    const alert = await within(slot).findByRole('alert')
    expect(alert.textContent).toContain('geri yüklediğiniz eski taslak henüz kaydedilmedi')
    expect(te().saveReport).not.toHaveBeenCalled()
    expect(te().uploadImage).not.toHaveBeenCalled()
    // Otomatik kayıt da durmuş kalır (yedek yükleme bekletmesi kalkmadı)
    expect(document.querySelector('[data-slot="te-save-auto"]').textContent).toContain('eski')
  })

  it('kayıt 409 (çakışma) → "başka biri kaydetti" + çakışma uyarısı; ağ hatası → bağlantı iletisi; sunucu reddi → sunucu iletisi', async () => {
    te().report.mockResolvedValue(respond([]))
    te().saveReport.mockResolvedValueOnce({ success: false, code: 'CONCURRENT_MODIFICATION', error: 'Rapor başka biri tarafından değişti.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const { slot } = await addTopicAndUpload()
    expect((await within(slot).findByRole('alert')).textContent).toContain('başka biri tarafından kaydedildi')
    expect(te().uploadImage).not.toHaveBeenCalled()
    // Çakışma görünürken ikinci deneme kayıt bile göndermez
    fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [PNG()] } })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
  })

  it('ağ hatası ve sunucu reddi ayrı iletiler', async () => {
    te().report.mockResolvedValue(respond([]))
    te().saveReport.mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce({ success: false, code: 'PAYLOAD_TOO_LARGE', error: 'Gövde çok büyük.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const { slot } = await addTopicAndUpload()
    expect((await within(slot).findByRole('alert')).textContent).toContain('bağlantı sorunu')
    fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [PNG()] } })
    await waitFor(() => expect(within(slot).getByRole('alert').textContent).toContain('sunucu kaydı kabul etmedi'))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('geçersiz alan (kesinti Evet, ID boş) → kayıt denenmez, zorunlu alan gösterilir, yuva "işaretli alanları düzeltin"', async () => {
    te().report.mockResolvedValue(respond([]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    fireEvent.click(within(card().querySelector('[data-slot="te-issue-outage"]')).getByRole('radio', { name: 'Evet' }))
    const id = card().getAttribute('data-issue')
    const slot = card().querySelector(`[data-slot-id="iss:issues:${id}:1"]`)
    fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [PNG()] } })
    expect((await within(slot).findByRole('alert')).textContent).toContain('İşaretli alanları düzeltip')
    expect(await screen.findByText('Kesinti ID\'sini yazın: kesinti kaynaklı konu için zorunludur.')).toBeInTheDocument()
    expect(te().saveReport).not.toHaveBeenCalled()
  })
})

describe('görsel ve açıklama', () => {
  it('konu görseli silinince açıklaması taslaktan düşer; görselin adı konuyu taşır', async () => {
    const img = { id: 601, slot_id: 'iss:issues:k1:1', file_name: 'a.png', width: 800, height: 400, size_bytes: 2048 }
    te().report.mockResolvedValue(respond([topic({ captions: { 1: 'Hata grafiği' } })], { images: [img] }))
    te().deleteImage.mockResolvedValue({ success: true })
    te().saveReport.mockImplementation(async (_r, values) => saved(values))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('img', { name: 'Kart Limiti: Hata Artışı — Görsel 1' })).toBeInTheDocument()
    expect(screen.getByLabelText('Görsel 1 açıklaması (isteğe bağlı)')).toHaveValue('Hata grafiği')
    const tile = card().querySelector('[data-slot-id="iss:issues:k1:1"]')
    fireEvent.click(within(tile).getByRole('button', { name: /Kaldır/ }))
    const dlg = await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(screen.queryByLabelText('Görsel 1 açıklaması (isteğe bağlı)')).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].issues.issues[0]).not.toHaveProperty('captions')
  })
})

describe('denetim ve durum', () => {
  it('İngilizce arayüzde ay dışı tarih iletisi İngilizce ay adıyla (şablon Türkçe olsa da)', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    te().report.mockResolvedValue(respond([topic({ date: '2026-10-02' })]))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText(/The date must be in the report's month \(September 2026\)/)).toBeInTheDocument()
    expect(screen.queryByText(/Eylül 2026\)/)).toBeNull()
  })

  it('İngilizce arayüzde tekil / çoğul: "1 error", "1 customer"; birden fazlası çoğul', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    te().report.mockResolvedValue(respond([topic({ errors: 1, impact: true, customers: 1 }), topic({ id: 'k2', topic: 'Fatura', errors: 2,
      impact: true, customers: 3 })]))
    render(<TechErrorReportsPage />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-issue-chips"]').length).toBe(2))
    const [a, b] = document.querySelectorAll('[data-slot="te-issue-chips"]')
    expect(within(a).getByText(/^1 error · /)).toBeInTheDocument()
    expect(within(a).getByText('1 customer')).toBeInTheDocument()
    expect(within(b).getByText(/^2 errors · /)).toBeInTheDocument()
    expect(within(b).getByText('3 customers')).toBeInTheDocument()
  })

  it('boş zorunlu alan (Kesinti ID) henüz gösterilmese de kapalı bölümün rozetinde sayılır ve durum satırında adıyla', async () => {
    te().report.mockResolvedValue(respond([topic({ errors: 10 })]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(within(card().querySelector('[data-slot="te-issue-outage"]')).getByRole('radio', { name: 'Evet' }))
    expect(screen.queryByText('Kesinti ID\'sini yazın: kesinti kaynaklı konu için zorunludur.')).toBeNull()
    await waitFor(() => expect(document.querySelector('[data-slot="te-save-auto"]').textContent)
      .toContain('eksik ya da hatalı: Kesinti ID (Kart Limiti: Hata Artışı)'))
    const section = document.querySelector('[data-slot="te-issues"]')
    fireEvent.click(section.querySelector('[data-slot="te-section-toggle"]'))
    await waitFor(() => expect(section.querySelector('[data-slot="te-section-invalid"]')?.textContent).toBe('1 alan düzeltilmeli'))
  })

  it('"Ertesi gün bitti" iki saat girilene kadar kapalı; yarım saat alan bırakılınca hata, otomatik kayıt onu kayıtlı değerinde gönderir', async () => {
    const stored = topic({ date: '2026-09-12', errors: 5 })
    te().report.mockResolvedValue(respond([stored]))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-count': 5000 }, issues: { issues: [{ ...stored, errors: 7 }] }, texts: {} }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const next = screen.getByRole('checkbox', { name: 'Ertesi gün bitti' })
    expect(next).toBeDisabled()
    expect(card().textContent).toContain('Önce başlangıç ve bitiş saatini girin')
    fireEvent.change(screen.getByLabelText('Başlangıç saati'), { target: { value: '23:30' } })
    expect(next).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Bitiş saati'), { target: { value: '01:15' } })
    expect(screen.getByRole('checkbox', { name: 'Ertesi gün bitti' })).not.toBeDisabled()
    fireEvent.change(screen.getByLabelText('Bitiş saati'), { target: { value: '' } })
    expect(screen.getByRole('checkbox', { name: 'Ertesi gün bitti' })).toBeDisabled()
    // Yarım yazılmış bitiş (tarayıcı değeri boş verir, badInput): taslağa girmez, bekleyen giriş sayılır, bırakılınca hata
    fireEvent.change(screen.getByLabelText('Başlangıç saati'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Bu konudan kaynaklı teknik hata adedi'), { target: { value: '7' } })
    const end = screen.getByLabelText('Bitiş saati')
    Object.defineProperty(end, 'validity', { value: { badInput: true }, configurable: true })
    fireEvent.keyUp(end, { key: '1' })
    fireEvent.blur(end)
    expect(await screen.findByText('Saat yarım kaldı; ss:dd biçiminde tamamlayın (ör. 14:30) ya da alanı temizleyin.')).toBeInTheDocument()
    expect(end).toHaveAttribute('aria-invalid', 'true')
    expect(end.getAttribute('aria-describedby')).toContain('-end-e')
    act(() => {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    const sent = te().saveReport.mock.calls[0][1].issues.issues[0]
    expect(sent.errors).toBe(7)
    expect(sent).not.toHaveProperty('end')
  })

  it('konu başlığı bir başlık (h4), grup başlıkları altında (h5); sunucunun konu alanı hatası girişe bağlı', async () => {
    te().report.mockResolvedValue(respond([topic()]))
    te().saveReport.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'issues.issues.k1.topic',
      error: '1. başlık: konu en çok 200 karakter olabilir; kısaltın.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('heading', { level: 4, name: '1. Kart Limiti: Hata Artışı' })).toBeInTheDocument()
    expect(within(card()).getByRole('heading', { level: 5, name: 'Ne zaman?' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Konu'), { target: { value: 'Kart Limiti 2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    const err = await screen.findByText('1. başlık: konu en çok 200 karakter olabilir; kısaltın.', { selector: '[role="alert"]' })
    expect(screen.getByLabelText('Konu').getAttribute('aria-describedby')).toBe(err.id)
  })
})

describe('şablonun son hâlini uygula', () => {
  it('rapora girmeyecek konu sayısı onayda söylenir', async () => {
    const upd = { differs: true, lost_values: 0, lost_images: 0, recalculated: 0, hidden_topics: 2 }
    te().report.mockResolvedValue(respond([topic()], { template_update: upd }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonun son hâlini uygula' }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toContain('Ayrıca 2 anomali konusu müşteri etkisi olmadığı için')
    expect(dlg.textContent).toContain('PDF\'e ve e-postaya girmeyecek')
  })
})
