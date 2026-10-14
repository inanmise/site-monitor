import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE } from './helpers/techErrorFixtures.js'

// Yönetici raporu yapısı (2026-10-14): yönetici özeti (kalıplardan canlı, elle düzenleme, otomatiğe dönüş), grup
// satırlı tablo (hücre değerleri, hücre altında hata), açıklamalarda madde / kalın (araç çubuğu + önizleme) ve şablon
// düzenleyicisinde özet cümleleri / tablo düzenleme.
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

const EXEC_TEMPLATE = {
  ...TEMPLATE,
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{kanal} Kanalı {ay} {yil} teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      'Toplam işlem adedi {f:total-tx} olup SLA {f:sla-above?hedefin üzerinde|hedefin altında} kalmıştır.',
    ] },
    TEMPLATE.sections[0],
    TEMPLATE.sections[1],
    { id: 'sla', kind: 'table', title: 'SLA Erişilebilirlik Tablosu', columns: [
      { id: 'functions', label: 'Fonksiyon adedi', type: 'number' },
      { id: 'availability', label: 'Erişilebilirlik', type: 'percent' },
      { id: 'note', label: 'Not', type: 'text' },
    ], rows: [
      { id: 'total', label: '{kanal} Kanalı Toplam', group: true },
      { id: 'critical', label: 'SLA dahil ve kritik' },
    ] },
  ],
}

const execReport = (extra = {}) => reportResponse({ template: EXEC_TEMPLATE, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-14T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

describe('yönetici özeti', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(execReport())
  })

  it('rakamlarla canlı yazılır, rakamlar kalın; rakamı girilmemiş cümle "henüz girmeyen" listesinde neyi beklediğini söyler', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const box = container.querySelector('[data-slot="te-sum-text"]')
    expect(box).toHaveTextContent('Mobil Kanalı Eylül 2026 teknik hata oranı %0,42 olarak gerçekleşmiştir.')
    expect(within(box).getByText('%0,42').tagName).toBe('STRONG')
    const pending = container.querySelector('[data-slot="te-sum-pending"]')
    expect(pending).toHaveTextContent('Özete henüz girmeyen cümleler (1)')
    expect(pending).toHaveTextContent('Beklenen: Toplam işlem adedi, SLA hedefin üstünde mi?')
    expect(pending).toHaveTextContent('Toplam işlem adedi [Toplam işlem adedi] olup SLA [hedefin üzerinde / hedefin altında] kalmıştır.')
    // Beklenen alanın adı o alana götürür (inceleme 2026-10-14)
    fireEvent.click(within(pending).getByRole('button', { name: '“Toplam işlem adedi” alanına git' }))
    expect(screen.getByLabelText('Toplam işlem adedi')).toHaveFocus()
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1.250.000' } })
    fireEvent.click(within(screen.getByLabelText('SLA hedefin üstünde mi?')).getByRole('radio', { name: 'Hayır' }))
    await waitFor(() => expect(container.querySelector('[data-slot="te-sum-text"]'))
      .toHaveTextContent('Toplam işlem adedi 1.250.000 olup SLA hedefin altında kalmıştır.'))
    expect(container.querySelector('[data-slot="te-sum-pending"]')).toBeNull()
  })

  it('elle düzenle: otomatik metin kopyalanır ve kaydedilir; otomatiğe dönüş onay ister, anahtar gönderilmez', async () => {
    te().saveReport
      .mockResolvedValueOnce(saved({ fields: { 'error-rate': 0.42 }, issues: {}, texts: { exec: 'Elle yazılmış **özet**' } }))
      .mockResolvedValueOnce(saved({ fields: { 'error-rate': 0.42 }, issues: {}, texts: {} }, 6))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Metni elle düzenle' }))
    const area = await screen.findByRole('textbox', { name: 'Yönetici Özeti' })
    expect(area).toHaveValue('Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir.')
    expect(container.querySelector('[data-slot="te-sum-mode"]')).toHaveTextContent('Elle düzenlendi')
    fireEvent.change(area, { target: { value: 'Elle yazılmış **özet**' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].texts).toEqual({ exec: 'Elle yazılmış **özet**' })
    fireEvent.click(await screen.findByRole('button', { name: 'Otomatik metne dön' }))
    const dlg = await screen.findByRole('dialog', { name: 'Otomatik metne dönülsün mü?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Otomatik metne dön' }))
    await waitFor(() => expect(container.querySelector('[data-slot="te-sum-mode"]')).toHaveTextContent('Otomatik'))
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][1].texts).toEqual({})
  })

  it('boş elle metin otomatik sayılır: kilitlenmez, kayda anahtar gitmez, güncel otomatik metin görülür', async () => {
    // İnceleme 2026-10-14: boş özetle "Metni elle düzenle" özeti kalıcı olarak boş bırakıyordu
    te().report.mockResolvedValue(execReport({ values: { fields: {}, issues: {}, texts: {} } }))
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-rate': 0.5 }, issues: {}, texts: {} }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(container.querySelector('[data-slot="te-sum-empty"]')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Metni elle düzenle' }))
    const area = await screen.findByRole('textbox', { name: 'Yönetici Özeti' })
    expect(area).toHaveValue('')
    await waitFor(() => expect(area).toHaveFocus())
    expect(screen.getByText('Metin boş: özet yine girdiğiniz rakamlardan otomatik yazılır.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Teknik hata oranı'), { target: { value: '0,5' } })
    // Elle düzenlerken rakamlardan üretilen güncel metin açılır kutuda görülür
    fireEvent.click(screen.getByRole('button', { name: /Rakamlardan üretilen güncel metin/ }))
    expect(container.querySelector('[data-slot="te-sum-current"]'))
      .toHaveTextContent('Mobil Kanalı Eylül 2026 teknik hata oranı %0,5 olarak gerçekleşmiştir.')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].texts).toEqual({})
    await waitFor(() => expect(container.querySelector('[data-slot="te-sum-mode"]')).toHaveTextContent('Otomatik'))
  })

  it('elle düzenlenmiş özeti olan rapor açılınca odak çalınmaz; metin otomatikle aynıysa dönüş onay sormaz', async () => {
    const autoText = 'Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir.'
    te().report.mockResolvedValue(execReport({ values: { fields: { 'error-rate': 0.42 }, issues: {}, texts: { exec: autoText } } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const area = screen.getByRole('textbox', { name: 'Yönetici Özeti' })
    await new Promise((r) => setTimeout(r, 20))
    expect(area).not.toHaveFocus()
    // Kaybolacak bir şey yok (otomatik metnin aynısı) → onaysız döner
    fireEvent.click(screen.getByRole('button', { name: 'Otomatik metne dön' }))
    await waitFor(() => expect(container.querySelector('[data-slot="te-sum-mode"]')).toHaveTextContent('Otomatik'))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('alt düzeyle başlayan liste görünümde de girintili (PDF gibi)', async () => {
    te().report.mockResolvedValue(execReport({ can_edit: false,
      values: { fields: {}, issues: {}, texts: { exec: 'Giriş\n  - alt madde\n- üst madde' } } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const lists = container.querySelectorAll('[data-slot="te-rich-readonly"] [role="list"]')
    expect(lists[0]).toHaveAttribute('data-level', '1')
    expect(lists[0]).toHaveClass('ps-5')
    expect(lists[1]).toHaveAttribute('data-level', '0')
    expect(lists[1]).not.toHaveClass('ps-5')
  })

  it('salt okunur izleyici tablo değerlerini metin olarak görür (kapalı, soluk giriş kutusu değil)', async () => {
    te().report.mockResolvedValue(execReport({ can_edit: false,
      values: { fields: {}, issues: {}, texts: {}, tables: { sla: { total: { functions: 1250, availability: 99.5, note: 'İyi' } } } } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const cells = [...container.querySelectorAll('[data-slot="te-cell-readonly"]')].map((n) => n.textContent)
    expect(cells).toEqual(expect.arrayContaining(['1.250', '%99,5', 'İyi', '—']))
    expect(screen.queryByLabelText('Mobil Kanalı Toplam — Fonksiyon adedi')).toBeNull()
  })

  it('salt okunur izleyici elle yazılmış özeti işaretleme değil işlenmiş metin olarak görür', async () => {
    te().report.mockResolvedValue(execReport({ can_edit: false,
      values: { fields: {}, issues: {}, texts: { exec: '- **Kritik** madde' } } }))
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const box = container.querySelector('[data-slot="te-rich-readonly"]')
    expect(box).toHaveTextContent('Kritik madde')
    expect(box).not.toHaveTextContent('**')
    expect(within(box).getByRole('list')).toBeInTheDocument()
    expect(within(box).getByText('Kritik').tagName).toBe('STRONG')
    expect(screen.queryByRole('button', { name: 'Otomatik metne dön' })).toBeNull()
  })
})

describe('tablo bölümü', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(execReport())
  })

  it('hücre değerleri tables.<bölüm>.<satır>.<sütun> ile gider; grup satırı ve satır başlıkları yer tutucuyla', async () => {
    te().saveReport.mockResolvedValue(saved({ fields: { 'error-rate': 0.42 }, issues: {}, texts: {},
      tables: { sla: { total: { functions: 1250, availability: 99.5, note: 'İyi' } } } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('rowheader', { name: 'Mobil Kanalı Toplam' })).toHaveClass('font-bold')
    fireEvent.change(screen.getByLabelText('Mobil Kanalı Toplam — Fonksiyon adedi'), { target: { value: '1.250' } })
    fireEvent.change(screen.getByLabelText('Mobil Kanalı Toplam — Erişilebilirlik'), { target: { value: '99,5' } })
    fireEvent.change(screen.getByLabelText('Mobil Kanalı Toplam — Not'), { target: { value: 'İyi' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].tables).toEqual({ sla: { total: { functions: 1250, availability: 99.5, note: 'İyi' } } })
  })

  it('geçersiz hücre hücrenin altında; sunucunun hücre hatası da aynı yerde, düzenleyince kalkar', async () => {
    te().saveReport.mockResolvedValueOnce({ success: false, code: 'VALIDATION_FAILED', field: 'tables.sla.critical.functions',
      error: 'SLA dahil ve kritik · Fonksiyon adedi negatif olamaz.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const cell = screen.getByLabelText('SLA dahil ve kritik — Fonksiyon adedi')
    fireEvent.change(cell, { target: { value: 'çok' } })
    expect(await screen.findByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    fireEvent.change(cell, { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    const err = await screen.findAllByText('SLA dahil ve kritik · Fonksiyon adedi negatif olamaz.')
    expect(err.some((n) => n.closest('[data-field="tables.sla.critical.functions"]'))).toBe(true)
    fireEvent.change(cell, { target: { value: '13' } })
    await waitFor(() => expect(document.querySelector('[data-field="tables.sla.critical.functions"] [role="alert"]')).toBeNull())
  })
})

describe('biçimli açıklama (madde / kalın)', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(execReport())
  })

  it('araç çubuğu: Kalın seçimi sarar, Madde satırı madde yapar; önizleme madde ve kalını gösterir', async () => {
    const { container } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    const desc = screen.getByLabelText('Ne zaman, nerede, nasıl gerçekleşti?')
    fireEvent.change(desc, { target: { value: 'Limit sorgusu yavaşladı' } })
    desc.setSelectionRange(0, 5)
    const toolbar = screen.getByRole('toolbar', { name: 'Biçimlendirme araçları: Ne zaman, nerede, nasıl gerçekleşti?' })
    fireEvent.click(within(toolbar).getByRole('button', { name: 'Kalın (Ctrl+B)' }))
    await waitFor(() => expect(desc).toHaveValue('**Limit** sorgusu yavaşladı'))
    fireEvent.click(within(toolbar).getByRole('button', { name: 'Madde' }))
    await waitFor(() => expect(desc).toHaveValue('- **Limit** sorgusu yavaşladı'))
    fireEvent.click(within(toolbar).getByRole('button', { name: 'Önizle' }))
    const preview = container.querySelector('[data-slot="te-rich-preview"]')
    expect(preview.querySelector('[data-slot="te-rich-item"]')).toHaveTextContent('Limit sorgusu yavaşladı')
    expect(within(preview).getByText('Limit').tagName).toBe('STRONG')
    expect(preview).not.toHaveTextContent('**')
  })
})

describe('şablon düzenleyici — özet ve tablo', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().template.mockImplementation(async (id) => ({ ...templateResponse(channel(Number(id), 'Mobil')),
      data: { ...templateResponse(channel(Number(id), 'Mobil')).data, template: EXEC_TEMPLATE } }))
  })

  it('cümleye alan eklenir (evet/hayır için seçim kalıbı), önizleme okunur; şablonda olmayan alan uyarı verir ama kaydı engellemez', async () => {
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
    const { container } = render(<TechErrorReportsPage />)
    const line = await screen.findByLabelText('1. cümle')
    fireEvent.change(line, { target: { value: 'Oran' } })
    line.setSelectionRange(4, 4)
    fireEvent.select(line)
    const item = line.closest('[data-slot="te-tpl-line"]')
    fireEvent.pointerDown(within(item).getByRole('button', { name: 'Alan ekle' }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: /SLA hedefin üstünde mi\?/ }))
    // Evet/hayır alanı: iki ifade ayrı kutulardan alınır (sözdizimi yazdırılmaz); | ve } ifadeye giremez
    const choice = await screen.findByRole('group', { name: '“SLA hedefin üstünde mi?” cevabına göre yazılacak ifade' })
    fireEvent.change(within(choice).getByLabelText('“Evet” ise'), { target: { value: 'hedefin | üzerinde' } })
    fireEvent.change(within(choice).getByLabelText('“Hayır” ise'), { target: { value: 'hedefin altında' } })
    fireEvent.click(within(choice).getByRole('button', { name: 'Cümleye ekle' }))
    await waitFor(() => expect(line).toHaveValue('Oran {f:sla-above?hedefin üzerinde|hedefin altında}'))
    expect(screen.queryByRole('group', { name: /cevabına göre yazılacak ifade/ })).toBeNull()
    expect(item.querySelector('[data-slot="te-tpl-line-preview"]')).toHaveTextContent('Önizleme: Oran [hedefin üzerinde / hedefin altında]')
    fireEvent.change(line, { target: { value: 'Oran {f:silinmis-alan} olarak' } })
    expect(within(item).getByText('Bu cümle şablondan silinmiş bir alana bağlı; raporda görünmez.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(te().saveTemplate.mock.calls[0][1].sections[0].lines[0]).toBe('Oran {f:silinmis-alan} olarak')
    expect(container.querySelector('[data-slot="te-tpl-preview"]')).toHaveTextContent('2 cümlelik özet')
    // Kayıt yanıtı taslağı değiştirir → bölüm kartları yeniden kurulur (eski cümle kimlikleri / imleç taşınmaz)
    expect(line.isConnected).toBe(false)
    const line2 = screen.getByLabelText('1. cümle')
    const item2 = line2.closest('[data-slot="te-tpl-line"]')
    // Silinmiş alanın başvurusu tek tıkla kaldırılır (kimlik gösterilmez); odak cümleye, imleç başvurunun yerine
    expect(item2.querySelector('[data-slot="te-tpl-line-preview"]')).toHaveTextContent('Önizleme: Oran [silinmiş alan] olarak')
    fireEvent.click(within(item2).getByRole('button', { name: 'Başvuruyu kaldır' }))
    expect(line2).toHaveValue('Oran olarak')
    expect(within(item2).queryByText(/silinmiş bir alana bağlı/)).toBeNull()
    await waitFor(() => expect(line2).toHaveFocus())
    expect(line2.selectionStart).toBe(5)
  })

  it('cümle taşınınca odak taşınan cümlenin düğmesinde kalır (kimlik konuma değil cümleye bağlı)', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    fireEvent.click(screen.getByRole('button', { name: '“1. cümle” aşağı taşı' }))
    // Cümle son sıraya indi: "aşağı" düğmesi kapandı, odak aynı cümlenin açık "yukarı" düğmesinde (ikinci basış geri almaz)
    expect(screen.getByLabelText('2. cümle')).toHaveValue(EXEC_TEMPLATE.sections[0].lines[0])
    await waitFor(() => expect(screen.getByRole('button', { name: '“2. cümle” yukarı taşı' })).toHaveFocus())
  })

  it('cümle tek satır: Enter satır açmaz, yapıştırılan satır sonu boşluk olur; silinen cümlenin ardından odak komşuda', async () => {
    render(<TechErrorReportsPage />)
    const line = await screen.findByLabelText('1. cümle')
    const enter = fireEvent.keyDown(line, { key: 'Enter' })
    expect(enter).toBe(false)                                   // preventDefault
    fireEvent.change(line, { target: { value: 'Bir\niki\r\nüç' } })
    expect(line).toHaveValue('Bir iki üç')
    fireEvent.click(screen.getByRole('button', { name: '1. cümleyi kaldır' }))
    await waitFor(() => expect(screen.getByLabelText('1. cümle')).toHaveFocus())
    expect(screen.getByLabelText('1. cümle')).toHaveValue(EXEC_TEMPLATE.sections[0].lines[1])
  })

  it('"Değişiklikleri geri al" taslağı değiştirince açık evet/hayır paneli kapanır (kartlar yeniden kurulur)', async () => {
    render(<TechErrorReportsPage />)
    const line = await screen.findByLabelText('1. cümle')
    fireEvent.change(screen.getByLabelText('2. cümle'), { target: { value: 'Değişti {f:total-tx}' } })
    const item = line.closest('[data-slot="te-tpl-line"]')
    fireEvent.pointerDown(within(item).getByRole('button', { name: 'Alan ekle' }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: /SLA hedefin üstünde mi\?/ }))
    expect(await screen.findByRole('group', { name: /cevabına göre yazılacak ifade/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklikleri geri al' }))
    await waitFor(() => expect(screen.getByLabelText('2. cümle')).toHaveValue(EXEC_TEMPLATE.sections[0].lines[1]))
    expect(screen.queryByRole('group', { name: /cevabına göre yazılacak ifade/ })).toBeNull()
  })

  it('sütun eklenince odak yeni sütunun adında; sütun taşınınca odak aynı sütunun açık taşıma düğmesinde', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    fireEvent.click(screen.getByRole('button', { name: 'Sütun ekle' }))
    await waitFor(() => expect(document.activeElement).toHaveValue('Yeni sütun'))
    fireEvent.click(screen.getByRole('button', { name: '“Fonksiyon adedi” aşağı taşı' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '“Fonksiyon adedi” aşağı taşı' })).toHaveFocus())
    const cols = [...document.querySelectorAll('[data-slot="te-tpl-column"] input')].map((n) => n.value)
    expect(cols.slice(0, 2)).toEqual(['Erişilebilirlik', 'Fonksiyon adedi'])
  })

  it('sütun adı 120 karakteri geçerse hata alanın altında (kayıt sunucuya gitmez)', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    const col = screen.getByDisplayValue('Fonksiyon adedi')
    fireEvent.change(col, { target: { value: 'x'.repeat(121) } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    const msg = await screen.findByText('Sütun adı en çok 120 karakter olabilir; PDF\'teki tablo başlığına sığması için kısaltın.')
    expect(col).toHaveAttribute('aria-describedby', msg.id)
    expect(te().saveTemplate).not.toHaveBeenCalled()
  })

  it('okunamayan başvuru kaydı engeller ve cümlenin altında söyler', async () => {
    render(<TechErrorReportsPage />)
    const line = await screen.findByLabelText('2. cümle')
    fireEvent.change(line, { target: { value: 'Oran {f:error-rate' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(await screen.findByText(/Alan başvurusu okunamıyor/)).toBeInTheDocument()
    expect(te().saveTemplate).not.toHaveBeenCalled()
  })

  it('özette kullanılan alan silinirken uyarılır; tabloya grup satırı eklenir ve kayda grup bayrağı gider', async () => {
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
    render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    fireEvent.click(screen.getByRole('button', { name: '“Toplam işlem adedi” alanını kaldır' }))
    const dlg = await screen.findByRole('dialog', { name: 'Alan yönetici özetinde kullanılıyor' })
    expect(within(dlg).getByText(/1 cümlede kullanılıyor/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    expect(screen.getByDisplayValue('Toplam işlem adedi')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Satır ekle' }))
    const rows = document.querySelectorAll('[data-slot="te-tpl-row"]')
    const added = rows[rows.length - 1]
    fireEvent.change(within(added).getByLabelText('Satır adı'), { target: { value: 'SLA kapsamında olmayan' } })
    fireEvent.click(within(added).getByRole('checkbox', { name: /Grup satırı/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Sütun ekle' }))
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    const table = te().saveTemplate.mock.calls[0][1].sections.find((s) => s.kind === 'table')
    expect(table.rows.at(-1)).toMatchObject({ label: 'SLA kapsamında olmayan', group: true })
    expect(table.columns).toHaveLength(4)
  })

  it('bölüm ekle menüsü yönetici özeti ve tablo sunar', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByLabelText('1. cümle')
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Bölüm ekle' }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Tablo' }))
    const sections = document.querySelectorAll('[data-slot="te-tpl-section"][data-kind="table"]')
    expect(sections).toHaveLength(2)
    expect(within(sections[1]).getByDisplayValue('Yeni sütun')).toBeInTheDocument()
    expect(within(sections[1]).getByDisplayValue('Yeni satır')).toBeInTheDocument()
  })
})
