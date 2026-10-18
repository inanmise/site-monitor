import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Görsel yüklemesinde kilit çakışması (son inceleme 2026-10-18): Görsel paneli ile ana pencere aynı anda yükleyince sunucu 503
// LOCK_CONFLICT + Retry-After döner. Kırpılmış ekran görüntüsü yeniden alınmasın: AYNI hazırlanmış dosya önerilen süre (≤ 3 sn)
// sonra bir kez yeniden gönderilir; yine olmazsa sunucunun iletisi yuvada ve görsel bellekte bekletilir ("Yeniden dene" /
// "Görseli bırak"). Ağ hatasında da bekletilir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { LOCK_RETRY_MAX_MS, isLockConflict, lockRetryDelayMs } from '../components/techerror/ReportImages.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const LOCK_TEXT = 'Aynı kayıt üzerinde aynı anda iki işlem çakıştı; birkaç saniye sonra yeniden deneyin.'
const lock = (s = 0) => ({ success: false, status: 503, code: 'LOCK_CONFLICT', error: LOCK_TEXT, retry_after_seconds: s })
const ok = (slotId, file) => ({ success: true, data: { id: 31, slot_id: slotId, file_name: file.name, width: 1600, height: 700,
  size_bytes: 90_000 } })
const png = (n) => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], n, { type: 'image/png' })
const pick = (slot, file) => fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [file] } })
const slotEl = () => document.querySelector('[data-slot-id="monthly-tx"]')

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse())
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
})

describe('görsel yüklemesi — kilit çakışması', () => {
  it('lockRetryDelayMs: sunucunun önerisi en çok 3 sn; öneri yoksa / bozuksa 2 sn; isLockConflict yalnız LOCK_CONFLICT kodunda', () => {
    expect(LOCK_RETRY_MAX_MS).toBe(3000)
    expect(lockRetryDelayMs({ retry_after_seconds: 2 })).toBe(2000)
    expect(lockRetryDelayMs({ retry_after_seconds: 0 })).toBe(0)
    expect(lockRetryDelayMs({ retry_after_seconds: 30 })).toBe(3000)
    expect(lockRetryDelayMs({ retryAfter: '1' })).toBe(1000)
    expect(lockRetryDelayMs({})).toBe(2000)
    expect(lockRetryDelayMs({ retry_after_seconds: 'yarın' })).toBe(2000)
    expect(lockRetryDelayMs({ retry_after_seconds: -4 })).toBe(2000)
    expect(lockRetryDelayMs(null)).toBe(2000)
    expect(isLockConflict(lock())).toBe(true)
    expect(isLockConflict({ success: false, status: 503, code: 'DB_UNAVAILABLE' })).toBe(false)
    expect(isLockConflict({ success: true, code: 'LOCK_CONFLICT' })).toBe(false)
    expect(isLockConflict(null)).toBe(false)
  })

  it('çakışmadan sonra AYNI hazırlanmış dosya bir kez yeniden gönderilir (yeniden hazırlanmaz); başarılıysa görsel yerinde, hata yok', async () => {
    te().uploadImage.mockResolvedValueOnce(lock(0)).mockImplementationOnce(async (_r, slotId, file) => ok(slotId, file))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const file = png('saatlik-hata.png')
    pick(slotEl(), file)
    await waitFor(() => expect(slotEl().querySelector('img')).not.toBeNull())
    expect(te().uploadImage).toHaveBeenCalledTimes(2)
    expect(te().uploadImage.mock.calls[0][2]).toBe(file)
    expect(te().uploadImage.mock.calls[1][2]).toBe(file)
    expect(te().uploadImage.mock.calls[1][1]).toBe('monthly-tx')
    expect(prepareImage).toHaveBeenCalledTimes(1)
    expect(slotEl().querySelector('[data-slot="te-image-error"]')).toBeNull()
    expect(slotEl().querySelector('[data-slot="te-image-kept"]')).toBeNull()
  })

  it('ikinci deneme de çakışırsa sunucunun iletisi yuvada ve görsel bekletilir: "Yeniden dene" aynı dosyayı yükler; "Görseli bırak" bırakır', async () => {
    te().uploadImage.mockResolvedValueOnce(lock(0)).mockResolvedValueOnce(lock(0))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const file = png('kirpilmis.png')
    pick(slotEl(), file)
    expect(await screen.findByText(LOCK_TEXT, { selector: '[data-slot="te-image-error"]' })).toBeInTheDocument()
    expect(te().uploadImage).toHaveBeenCalledTimes(2)
    const kept = slotEl().querySelector('[data-slot="te-image-kept"]')
    expect(kept).toHaveTextContent('Hazırladığınız görsel bekletiliyor')
    // Yeniden dene: hazırlama YOK, aynı dosya; başarı → görsel, ileti ve bekleyen kalkar
    te().uploadImage.mockImplementationOnce(async (_r, slotId, f) => ok(slotId, f))
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-retry"]'))
    await waitFor(() => expect(slotEl().querySelector('img')).not.toBeNull())
    expect(te().uploadImage).toHaveBeenCalledTimes(3)
    expect(te().uploadImage.mock.calls[2][2]).toBe(file)
    expect(prepareImage).toHaveBeenCalledTimes(1)
    expect(slotEl().querySelector('[data-slot="te-image-error"]')).toBeNull()
    expect(slotEl().querySelector('[data-slot="te-image-kept"]')).toBeNull()

    // Bırak: ileti ve bekleyen görsel kalkar, yükleme yapılmaz
    te().uploadImage.mockResolvedValueOnce(lock(0)).mockResolvedValueOnce(lock(0))
    pick(slotEl(), png('ikinci.png'))
    await waitFor(() => expect(slotEl().querySelector('[data-slot="te-image-kept"]')).not.toBeNull())
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-kept-drop"]'))
    expect(slotEl().querySelector('[data-slot="te-image-kept"]')).toBeNull()
    expect(slotEl().querySelector('[data-slot="te-image-error"]')).toBeNull()
    expect(te().uploadImage).toHaveBeenCalledTimes(5)
  })

  it('başka bir hata (doğrulama reddi) yeniden denenmez ve görsel bekletilmez; ağ hatası (istek reddedildi) bekletilir', async () => {
    te().uploadImage.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED',
      error: 'Görsel PNG ya da JPEG olmalı; dosyayı çevirip yeniden yükleyin.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    pick(slotEl(), png('bozuk.png'))
    expect(await screen.findByText('Görsel PNG ya da JPEG olmalı; dosyayı çevirip yeniden yükleyin.')).toBeInTheDocument()
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
    expect(slotEl().querySelector('[data-slot="te-image-kept"]')).toBeNull()

    te().uploadImage.mockRejectedValueOnce(Object.assign(new Error('Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.'),
      { status: 0, code: 'NETWORK_ERROR' }))
    const file = png('ag.png')
    pick(slotEl(), file)
    expect(await screen.findByText('Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.')).toBeInTheDocument()
    expect(slotEl().querySelector('[data-slot="te-image-kept"]')).not.toBeNull()
    te().uploadImage.mockImplementationOnce(async (_r, slotId, f) => ok(slotId, f))
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-retry"]'))
    await waitFor(() => expect(slotEl().querySelector('img')).not.toBeNull())
    expect(te().uploadImage.mock.calls.at(-1)[2]).toBe(file)
  })
})
