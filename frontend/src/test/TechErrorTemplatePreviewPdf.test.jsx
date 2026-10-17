import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, templateResponse } from './helpers/techErrorFixtures.js'

// Şablon düzenleyicisinde "Örnek PDF" (2026-10-17, R19): düzenleyicideki GÜNCEL taslak (kaydedilmemiş değişiklik dahil)
// sunucuya gider, PDF tıklama anında açılan sekmede gösterilir; hazırlanırken düğme kilitli; işaretli alan varsa istek
// gitmez; sunucu hatası bildirilir ve boş sekme kapanır; tarayıcı sekmeyi engellerse PDF indirilir; salt okuyan da önizler.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(),
      templatePreviewPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const previewBtn = () => document.querySelector('[data-slot="te-tpl-preview-pdf"]')

function fakeWindow() {
  return { closed: false, opener: {}, document: { title: '', body: { textContent: '' } }, location: { href: '' }, close: vi.fn() }
}

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

async function openEditor() {
  render(<TechErrorReportsPage />)
  await screen.findByText('Rapor başlığı ve dili')
  await waitFor(() => expect(previewBtn()).not.toBeNull())
}

// jsdom'da nesne adresi yok: kalıcı saplama (60 sn sonra çalışan bırakma zamanlayıcısı da bulsun)
if (typeof URL.createObjectURL !== 'function') URL.createObjectURL = () => 'blob:stub'
if (typeof URL.revokeObjectURL !== 'function') URL.revokeObjectURL = () => {}

let openSpy
beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview')
  openSpy = vi.spyOn(window, 'open')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('şablon düzenleyici — örnek PDF', () => {
  it('KAYDEDİLMEMİŞ taslak gönderilir; PDF tıklama anında açılan sekmede gösterilir; hiçbir şey kaydedilmez', async () => {
    const win = fakeWindow()
    openSpy.mockReturnValue(win)
    te().templatePreviewPdf.mockResolvedValue({ success: true, blob: new Blob(['%PDF-1.7']), name: 'mobil-sablon-onizleme.pdf' })
    await openEditor()
    fireEvent.change(screen.getByRole('textbox', { name: 'Rapor başlığı' }), { target: { value: 'Taslak başlık {kanal}' } })
    expect(previewBtn()).toHaveTextContent('Örnek PDF')
    fireEvent.click(previewBtn())
    await waitFor(() => expect(te().templatePreviewPdf).toHaveBeenCalledTimes(1))
    const [id, tpl] = te().templatePreviewPdf.mock.calls[0]
    expect(String(id)).toBe('10')                                   // kanal kimliği adres çubuğundan (te_ch)
    expect(tpl.title).toBe('Taslak başlık {kanal}')
    expect(tpl.sections.map((s) => s.id)).toEqual(['summary', 'issues', 'trend-note', 'charts'])
    // Sekme tıklamada (istekten önce) açıldı, opener kesildi, PDF gelince adresi verildi
    expect(openSpy).toHaveBeenCalledWith('', '_blank')
    expect(win.opener).toBeNull()
    await waitFor(() => expect(win.location.href).toBe('blob:preview'))
    expect(win.close).not.toHaveBeenCalled()
    expect(te().saveTemplate).not.toHaveBeenCalled()
  })

  it('hazırlanırken düğme kilitli ve "PDF hazırlanıyor…"; bitince geri döner', async () => {
    openSpy.mockReturnValue(fakeWindow())
    const d = deferred()
    te().templatePreviewPdf.mockReturnValue(d.promise)
    await openEditor()
    fireEvent.click(previewBtn())
    await waitFor(() => expect(previewBtn()).toBeDisabled())
    expect(previewBtn()).toHaveTextContent('PDF hazırlanıyor…')
    expect(previewBtn()).toHaveAttribute('aria-busy', 'true')
    fireEvent.click(previewBtn())                                    // ikinci tık yeni istek açmaz
    expect(te().templatePreviewPdf).toHaveBeenCalledTimes(1)
    d.resolve({ success: true, blob: new Blob(['%PDF-1.7']), name: 'x.pdf' })
    await waitFor(() => expect(previewBtn()).not.toBeDisabled())
    expect(previewBtn()).toHaveTextContent('Örnek PDF')
  })

  it('sunucu reddederse (400) sunucunun metni bildirilir ve açılan boş sekme kapanır', async () => {
    const win = fakeWindow()
    openSpy.mockReturnValue(win)
    te().templatePreviewPdf.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'template',
      error: '3. bölüm: bölüm başlığı boş olamaz.' })
    await openEditor()
    fireEvent.click(previewBtn())
    expect(await screen.findByText('3. bölüm: bölüm başlığı boş olamaz.')).toBeInTheDocument()
    expect(win.close).toHaveBeenCalled()
    expect(win.location.href).toBe('')
  })

  it('işaretli (hatalı) alan varsa istek gitmez, sekme açılmaz; kayıttaki gibi alanlar gösterilir', async () => {
    await openEditor()
    fireEvent.change(screen.getByRole('textbox', { name: 'Rapor başlığı' }), { target: { value: '   ' } })
    fireEvent.click(previewBtn())
    expect(await screen.findByText('Örnek PDF için önce şablondaki işaretli alanları düzeltin; hatalı bir şablon önizlenemez.')).toBeInTheDocument()
    expect(te().templatePreviewPdf).not.toHaveBeenCalled()
    expect(openSpy).not.toHaveBeenCalled()
    expect(screen.getByRole('textbox', { name: 'Rapor başlığı' })).toHaveAttribute('aria-invalid', 'true')
  })

  it('tarayıcı sekmeyi engellerse PDF indirilir (sunucunun dosya adıyla) ve söylenir', async () => {
    openSpy.mockReturnValue(null)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    te().templatePreviewPdf.mockResolvedValue({ success: true, blob: new Blob(['%PDF-1.7']), name: 'mobil-sablon-onizleme.pdf' })
    await openEditor()
    fireEvent.click(previewBtn())
    expect(await screen.findByText('Tarayıcı yeni sekmeyi açmadı; örnek PDF indirildi.')).toBeInTheDocument()
    expect(click).toHaveBeenCalledTimes(1)
    expect(click.mock.contexts[0].download).toBe('mobil-sablon-onizleme.pdf')
    expect(click.mock.contexts[0].href).toBe('blob:preview')
  })

  it('salt okuyan (şablonu düzenleyemeyen) da önizler', async () => {
    te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil', { can_edit: false })))
    openSpy.mockReturnValue(fakeWindow())
    te().templatePreviewPdf.mockResolvedValue({ success: true, blob: new Blob(['%PDF-1.7']), name: 'x.pdf' })
    await openEditor()
    expect(document.querySelector('[data-slot="te-tpl-save"]')).toBeNull()
    expect(previewBtn()).not.toBeDisabled()
    fireEvent.click(previewBtn())
    await waitFor(() => expect(te().templatePreviewPdf).toHaveBeenCalledTimes(1))
  })
})
