import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Kayıt çakışması (A5, 2026-11-20): 409'da rapor kısa süre sınırıyla yeniden okunur. Sunucu tam olarak gönderilen içeriği
// tutuyorsa (önceki kaydın yanıtı kaybolmuş) sessizce benimsenir — uyarı yok; bu sekmenin yanıtı alınamamış ÖNCEKİ bir
// gönderimini tutuyorsa o taban benimsenip kayıt BİR KEZ yeniden denenir; başka içerikse uyarıda son kaydeden + zaman ve
// "Değişiklik geçmişini aç" düğmesi. Taslak hiçbir durumda ezilmez; "En son hâli yükle" ve yedek korunur.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(), reportHistory: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { CONFLICT_READ_TIMEOUT_MS, conflictOf } from '../components/techerror/ReportEditor.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const field = () => screen.getByLabelText('Toplam işlem adedi')
const banner = () => document.querySelector('[data-slot="te-conflict"]')
const serverHolds = (fields, report = {}) => reportResponse({
  report: { ...reportResponse().data.report, version: 5, updated_at: '2026-10-02T08:15:00Z', updated_by_name: 'Ali Veli', ...report },
  values: { fields, issues: {}, texts: {} },
})
const okSave = (fields, version) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-02T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values: { fields, issues: {}, texts: {} } } })
const conflict409 = { success: false, code: 'CONCURRENT_MODIFICATION', status: 409, error: 'Rapor siz düzenlerken değişti.' }

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().reportHistory.mockResolvedValue({ success: true, data: { events: [], has_more: false } })
})

describe('conflictOf', () => {
  it('ad ya da zaman varsa nesne; ikisi de yoksa true (bilinmeyen çakışma)', () => {
    expect(conflictOf({ updated_by_name: 'Ali', updated_at: '2026-10-02T08:15:00Z' })).toEqual({ by: 'Ali', at: '2026-10-02T08:15:00Z' })
    expect(conflictOf({ updated_by_name: '  ', updated_at: '2026-10-02T08:15:00Z' })).toEqual({ by: null, at: '2026-10-02T08:15:00Z' })
    expect(conflictOf({ updated_by_name: 'Ali' })).toEqual({ by: 'Ali', at: null })
    expect(conflictOf({})).toBe(true)
    expect(conflictOf(null)).toBe(true)
    expect(CONFLICT_READ_TIMEOUT_MS).toBeGreaterThan(0)
    expect(CONFLICT_READ_TIMEOUT_MS).toBeLessThanOrEqual(30_000)
  })
})

describe('kayıt çakışması — yeniden okuma', () => {
  it('başkasının kaydı: uyarıda son kaydeden + zaman ve geçmiş düğmesi; taslak korunur; okuma süre sınırlı', async () => {
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue(serverHolds({ 'error-rate': 0.9 }))
    te().saveReport.mockResolvedValue(conflict409)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    expect(banner()).toHaveAttribute('data-known', 'true')
    expect(banner().querySelector('[data-slot="te-conflict-who"]')).toHaveTextContent(/Son kaydeden: Ali Veli, .+\./)
    expect(te().report).toHaveBeenLastCalledWith('100', { timeoutMs: CONFLICT_READ_TIMEOUT_MS })
    expect(field()).toHaveValue('7')
    expect(te().saveReport).toHaveBeenCalledTimes(1)                    // ezmek için yeniden denenmez
    expect(within(banner()).getByRole('button', { name: 'En son hâli yükle' })).toBeInTheDocument()
    fireEvent.click(within(banner()).getByRole('button', { name: /Değişiklik geçmişini aç/ }))
    await waitFor(() => expect(te().reportHistory).toHaveBeenCalled())
    // Geçmiş penceresi modal: arkadaki sayfa erişilebilirlik ağacından çıkar; uyarı ve taslak yerinde kalır
    expect(banner()).toBeInTheDocument()
    expect(field()).toHaveValue('7')
  })

  it('yeniden okunamazsa uyarı "kim" satırı olmadan çıkar', async () => {
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue({ success: false, status: 503, error: 'Sunucu yanıt vermiyor; birkaç dakika sonra deneyin.' })
    te().saveReport.mockResolvedValue(conflict409)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    expect(banner()).toHaveAttribute('data-known', 'false')
    expect(banner().querySelector('[data-slot="te-conflict-who"]')).toBeNull()
  })

  it('sunucu tam olarak gönderileni tutuyor (yanıt kaybolmuş): sessizce benimsenir, uyarı yok; sonraki kayıt yeni sürümle', async () => {
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue(serverHolds({ 'error-rate': 0.42, 'total-tx': 7 }))
    te().saveReport
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(conflict409)
      .mockImplementationOnce(async (_id, values, version) => okSave(values.fields, version + 1))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Rapor kaydedildi.')).toBeInTheDocument()
    expect(banner()).toBeNull()
    fireEvent.change(field(), { target: { value: '8' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(3))
    expect(te().saveReport.mock.calls[2][2]).toBe(5)
    expect(te().saveReport.mock.calls[2][1].fields['total-tx']).toBe(8)
    expect(banner()).toBeNull()
  })

  it('sunucu bu sekmenin yanıtı alınamamış önceki kaydını tutuyor: taban benimsenir, kayıt BİR KEZ yeniden denenir', async () => {
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue(serverHolds({ 'error-rate': 0.42, 'total-tx': 7 }))
    te().saveReport
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(conflict409)
      .mockImplementationOnce(async (_id, values, version) => okSave(values.fields, version + 1))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    fireEvent.change(field(), { target: { value: '8' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(3))
    expect(te().saveReport.mock.calls[1][2]).toBe(4)
    expect(te().saveReport.mock.calls[2][2]).toBe(5)                    // benimsenen taban
    expect(te().saveReport.mock.calls[2][1].fields['total-tx']).toBe(8)
    expect(await screen.findByText('Rapor kaydedildi.')).toBeInTheDocument()
    expect(banner()).toBeNull()
  })

  it('yeniden deneme de 409 alırsa ikinci kez denenmez — uyarı çıkar', async () => {
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue(serverHolds({ 'error-rate': 0.42, 'total-tx': 7 }))
    te().saveReport
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValue(conflict409)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    fireEvent.change(field(), { target: { value: '8' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    expect(te().saveReport).toHaveBeenCalledTimes(3)
    expect(field()).toHaveValue('8')
    // Yeniden denemenin kendi 409'u da kim / ne zaman söyler (inceleme 2026-11-20)
    expect(banner()).toHaveAttribute('data-known', 'true')
    expect(banner().querySelector('[data-slot="te-conflict-who"]')).toHaveTextContent(/Ali Veli/)
  })

  it('yeniden deneme İLK gönderilen gövdeyle yapılır (arada yazılan değer girmez); deneme sürerken Kaydet meşgul kalır', async () => {
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue(serverHolds({ 'error-rate': 0.42, 'total-tx': 7 }))
    let release409
    let releaseRetry
    te().saveReport
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockImplementationOnce(() => new Promise((r) => { release409 = () => r(conflict409) }))
      .mockImplementationOnce((_id, values, version) => new Promise((r) => { releaseRetry = () => r(okSave(values.fields, version + 1)) }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    fireEvent.change(field(), { target: { value: '8' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    fireEvent.change(field(), { target: { value: '9' } })             // kayıt sürerken yazılır
    release409()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(3))
    expect(te().saveReport.mock.calls[2][1].fields['total-tx']).toBe(8)  // gönderilen gövde aynen; 9 bu denemeye girmez
    expect(te().saveReport.mock.calls[2][2]).toBe(5)
    const saveBtn = document.querySelector('[data-slot="te-save"]')
    expect(saveBtn).toBeDisabled()                                        // deneme sürerken meşgul (return await)
    expect(saveBtn).toHaveTextContent('Kaydediliyor')
    releaseRetry()
    expect(await screen.findByText('Rapor kaydedildi.')).toBeInTheDocument()
    expect(field()).toHaveValue('9')                                      // yazılan değer taslakta kalır
    await waitFor(() => expect(document.querySelector('[data-slot="te-save"]')).toBeEnabled())
    expect(banner()).toBeNull()
  })

  it('kayıt sürerken ESKİ sürümlü yedek geri yüklendiyse (autoHold) yeniden deneme yapılmaz: uyarı çıkar, yedek kaydedilmez', async () => {
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 3, at: Date.now(),
      values: { fields: { 'error-rate': 9.9, 'total-tx': 1 }, issues: {}, texts: {} } }))
    te().report.mockResolvedValueOnce(reportResponse()).mockResolvedValue(serverHolds({ 'error-rate': 0.42, 'total-tx': 7 }))
    let release409
    te().saveReport
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockImplementationOnce(() => new Promise((r) => { release409 = () => r(conflict409) }))
      .mockImplementation(async (_id, values, version) => okSave(values.fields, version + 1))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(field(), { target: { value: '7' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    fireEvent.change(field(), { target: { value: '8' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: 'Geri yükle' }))   // ikinci kayıt sürerken
    await waitFor(() => expect(field()).toHaveValue('1'))
    release409()
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 30))
    expect(te().saveReport).toHaveBeenCalledTimes(2)                     // yeniden deneme YOK — başkasının kaydı ezilmez
    expect(field()).toHaveValue('1')
    expect(banner().querySelector('[data-slot="te-conflict-who"]')).toHaveTextContent(/Ali Veli/)
  })
})
