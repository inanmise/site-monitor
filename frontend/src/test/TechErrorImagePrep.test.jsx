import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Görsel hazırlama akışı (2026-10-15, Faz C): yuva dosyayı ÖNCE tarayıcıda hazırlar (imagePrep — küçültme / çevirme),
// sonra yükler. Yüklenen dosya hazırlanan dosyadır; küçültme / çevirme notu yuvanın altında; hazırlama hatası neyin
// yanlış olduğunu ve ne yapılacağını söyler (bayt sayıları okunur biçimde); rapor toplamı yüklemeden ÖNCE denetlenir.
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
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { ImagePrepError, prepareImage } from '../components/techerror/imagePrep.js'
import { ImagesBlock } from '../components/techerror/ReportImages.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const MB = 1024 * 1024

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse())
})

async function openSlot(id = 'monthly-tx') {
  const view = render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
  const slot = view.container.querySelector(`[data-slot-id="${id}"]`)
  return { slot, input: slot.querySelector('input[type="file"]') }
}

const pick = (input, file) => fireEvent.change(input, { target: { files: [file] } })

describe('görsel hazırlama', () => {
  it('hazırlanırken düğme "Hazırlanıyor…" der; hazırlanan dosya yüklenir, küçültme + çevirme notu yuvanın altında', async () => {
    let finish
    prepareImage.mockImplementation(() => new Promise((r) => { finish = r }))
    const prepared = new File([new Uint8Array(3000)], 'grafik.png', { type: 'image/png' })
    te().uploadImage.mockResolvedValue({ success: true, data: { id: 501, slot_id: 'monthly-tx', file_name: 'grafik.png',
      width: 850, height: 425, size_bytes: 3000 } })
    const { slot, input } = await openSlot()
    const original = new File([new Uint8Array(10)], 'grafik.webp', { type: 'image/webp' })
    pick(input, original)
    expect(await within(slot).findByRole('button', { name: /Hazırlanıyor…/ })).toBeDisabled()
    expect(prepareImage).toHaveBeenCalledWith(original)
    expect(te().uploadImage).not.toHaveBeenCalled()
    finish({ file: prepared, info: { resized: true, converted: true, fromKind: 'WebP', toKind: 'PNG', fromW: 7200, fromH: 1800,
      toW: 6000, toH: 1500, fromBytes: 4 * MB, toBytes: 3000 } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledWith('100', 'monthly-tx', prepared))
    const note = await waitFor(() => {
      const n = slot.querySelector('[data-slot="te-image-note"]')
      expect(n).not.toBeNull()
      return n
    })
    expect(note).toHaveTextContent('Sunucu sınırı için netliği koruyan alan ortalamasıyla küçültüldü: 7200 × 1800 → 6000 × 1500 piksel')
    expect(note).toHaveTextContent('WebP biçimi PNG olarak kaydedildi.')
    expect(note).not.toHaveAttribute('aria-live')
    expect(within(slot).getByRole('button', { name: /Değiştir|Görseli değiştir/ })).toBeEnabled()
  })

  it('tam genişlik yuvada da yuvadan bağımsız hazırlanır (çözünürlük korunur); SVG reddi yuvanın altında', async () => {
    prepareImage.mockRejectedValue(new ImagePrepError('te.img.errSvg'))
    const { slot, input } = await openSlot('daily-errors')
    pick(input, new File(['<svg/>'], 'g.svg', { type: 'image/svg+xml' }))
    expect(await within(slot).findByRole('alert')).toHaveTextContent('SVG dosyası yüklenemez. Grafiği raporlama aracından PNG')
    expect(prepareImage).toHaveBeenCalledWith(expect.any(File))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('hazırlama hatası: bayt sayıları okunur biçimde, yükleme yapılmaz; beklenmeyen hata "bu tarayıcıda işlenemedi" der', async () => {
    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errTooBig', [50 * MB, 40 * MB]))
    const { slot, input } = await openSlot()
    pick(input, new File([new Uint8Array(4)], 'dev.png', { type: 'image/png' }))
    const alert = await within(slot).findByRole('alert')
    expect(alert.textContent).toMatch(/^Dosya 50[\s\S]*MB; en çok 40[\s\S]*MB olabilir\./)
    expect(alert.textContent).not.toMatch(/52428800|41943040/)
    prepareImage.mockRejectedValueOnce(new Error('beklenmeyen'))
    pick(input, new File([new Uint8Array(4)], 'b.png', { type: 'image/png' }))
    // Beklenmeyen hata çizim / bellek tarafıdır: "dosya bozuk" denmez (inceleme 2026-10-15)
    await waitFor(() => expect(within(slot).getByRole('alert'))
      .toHaveTextContent('Görsel bu tarayıcıda işlenemedi (tarayıcının belleği yetmemiş olabilir).'))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('toplam yükleme ANINDA okunur: bu yuva hazırlanırken başka yuvaya yüklenen görsel de sayılır', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [
      { id: 1, slot_id: 'tech-errors', file_name: 'a.png', width: 800, height: 400, size_bytes: 30 * MB },
    ] }))
    let finishA
    const sized = (n) => { const f = new File([new Uint8Array(1)], 'x.png', { type: 'image/png' }); Object.defineProperty(f, 'size', { value: n }); return f }
    prepareImage
      .mockImplementationOnce(() => new Promise((r) => { finishA = r }))
      .mockResolvedValueOnce({ file: sized(5 * MB), info: { resized: false, converted: false } })
    te().uploadImage.mockResolvedValueOnce({ success: true, data: { id: 9, slot_id: 'daily-errors', file_name: 'g.png', width: 800,
      height: 400, size_bytes: 5 * MB } })
    const view = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const slotA = view.container.querySelector('[data-slot-id="monthly-tx"]')
    const slotB = view.container.querySelector('[data-slot-id="daily-errors"]')
    pick(slotA.querySelector('input[type="file"]'), new File([new Uint8Array(4)], 'a.png', { type: 'image/png' }))
    await within(slotA).findByRole('button', { name: /Hazırlanıyor…/ })
    pick(slotB.querySelector('input[type="file"]'), new File([new Uint8Array(4)], 'b.png', { type: 'image/png' }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    await within(slotB).findByRole('img')
    // A şimdi 6 MB ile biter: 30 + 5 + 6 > 40 MB (eski değerle 30 + 6 geçerdi)
    finishA({ file: sized(6 * MB), info: { resized: false, converted: false } })
    expect(await within(slotA).findByRole('alert')).toHaveTextContent(/^Bu görselle rapordaki görsellerin toplamı 41/)
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
  })

  it('küçültme notu yüklenen görsele bağlıdır: görsel başkasınınkiyle değişince not kalkar', async () => {
    prepareImage.mockResolvedValueOnce({ file: new File([new Uint8Array(1)], 'x.png', { type: 'image/png' }),
      info: { resized: true, converted: false, fromKind: 'PNG', toKind: 'PNG', fromW: 3000, fromH: 1500, toW: 760, toH: 380,
        fromBytes: 3 * MB, toBytes: 200_000 } })
    te().uploadImage.mockResolvedValueOnce({ success: true, data: { id: 501, slot_id: 'monthly-tx', file_name: 'x.png', width: 760,
      height: 380, size_bytes: 200_000 } })
    const section = { id: 'charts', title: 'Grafikler', slots: [{ id: 'monthly-tx', label: 'Aylık', width: 'half' }] }
    let images = []
    const props = () => ({ section, reportId: '100', images, resolve: (s) => s, uiLang: 'tr', readOnly: false,
      onImagesChange: (fn) => { images = fn(images) } })
    const view = render(<ImagesBlock {...props()} />)
    pick(view.container.querySelector('input[type="file"]'), new File([new Uint8Array(4)], 'k.png', { type: 'image/png' }))
    await waitFor(() => expect(images).toHaveLength(1))
    view.rerender(<ImagesBlock {...props()} />)
    expect(view.container.querySelector('[data-slot="te-image-note"]')).toHaveTextContent('3000 × 1500 → 760 × 380')
    images = [{ id: 777, slot_id: 'monthly-tx', file_name: 'baskasi.png', width: 800, height: 400, size_bytes: 300_000 }]
    view.rerender(<ImagesBlock {...props()} />)
    expect(view.container.querySelector('[data-slot="te-image-note"]')).toBeNull()
  })

  it('rapor toplamı aşılacaksa yüklemeden ÖNCE söylenir (aynı yuvadaki eski görsel toplamdan düşülür)', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [
      { id: 1, slot_id: 'tech-errors', file_name: 'a.png', width: 800, height: 400, size_bytes: 38 * MB },
      { id: 2, slot_id: 'monthly-tx', file_name: 'b.png', width: 800, height: 400, size_bytes: 1.5 * MB },
    ] }))
    prepareImage.mockResolvedValueOnce({ file: new File([new Uint8Array(1)], 'x.png', { type: 'image/png' }),
      info: { resized: false, converted: false } })
    const big = new File([new Uint8Array(1)], 'buyuk.png', { type: 'image/png' })
    Object.defineProperty(big, 'size', { value: 2.5 * MB })
    const { slot, input } = await openSlot()
    // 1,5 MB'lık eski görselin yerine 1 baytlık görsel: 38 MB + 1 B ≤ 40 MB → yüklenir
    te().uploadImage.mockResolvedValueOnce({ success: true, data: { id: 3, slot_id: 'monthly-tx', file_name: 'x.png', width: 800,
      height: 400, size_bytes: 1 } })
    pick(input, new File([new Uint8Array(4)], 'k.png', { type: 'image/png' }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    // Şimdi 2,5 MB'lık hazırlanmış dosya: 38 MB + 2,5 MB > 40 MB → yüklenmez, ne yapılacağı söylenir
    prepareImage.mockResolvedValueOnce({ file: big, info: { resized: false, converted: false } })
    pick(slot.querySelector('input[type="file"]'), new File([new Uint8Array(4)], 'k2.png', { type: 'image/png' }))
    const alert = await within(slot).findByRole('alert')
    expect(alert.textContent).toMatch(/^Bu görselle rapordaki görsellerin toplamı 40[,.]5\s*MB olur; en çok 40\s*MB\./)
    expect(alert).toHaveTextContent('Başka bir yuvadaki büyük görseli kaldırın ya da küçültün.')
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
  })
})
