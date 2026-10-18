import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections as openAll } from './helpers/techErrorFixtures.js'

// Katlanır bölümler + otomatik kayıt (2026-10-16, kullanıcı kararları): bölümler kapalı başlar, açılanlar bu rapor için
// hatırlanır, hatalı bölüm kendiliğinden açılır; değişiklik varken 30 sn'de bir (ve sekme gizlenirken) sessizce kaydedilir,
// geçersiz alan eski değerinde kalır, çakışmada durur, aynı içeriği reddeden hatayı yinelemez, elle kayıt süreni bekler.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { AUTOSAVE_MS, AUTOSAVE_TIMEOUT_MS } from '../components/techerror/ReportEditor.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const SUMMARY = 'Mobil Kanalı — Eylül 2026 Özeti'
const NOT_NUMBER = 'Geçerli bir sayı yazın: 1.250.000, 0,42 ya da kısaltmayla 7,42G, 130,1M, 681,6k (k = bin, M = milyon, G ya da Mr = milyar).'

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const okSave = (version, fields) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-16T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values: { fields, issues: {}, texts: {} } } })

const cards = () => [...document.querySelectorAll('[data-slot="te-report"] [data-section][data-open]')]
const status = () => document.querySelector('[data-slot="te-save-status"]')

function hideTab() {
  Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse({ values: { fields: { 'error-rate': 0.42, 'total-tx': 1000 }, issues: {}, texts: {} } }))
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

const AUTO = { auto: true, timeoutMs: AUTOSAVE_TIMEOUT_MS }

describe('katlanır bölümler', () => {
  it('kapalı başlar; açılan bölüm bu rapor için hatırlanır; tümünü aç / kapat', async () => {
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(cards().length).toBeGreaterThan(2)
    expect(cards().every((c) => c.getAttribute('data-open') === 'false')).toBe(true)
    // Kapalı bölümün içeriği erişilebilir değil (DOM'da kurulu kalır)
    expect(screen.queryByRole('textbox', { name: 'Toplam işlem adedi' })).toBeNull()
    expect(document.getElementById('te-v-total-tx')).not.toBeNull()
    const toggle = screen.getByRole('button', { name: SUMMARY })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle.closest('h3')).not.toBeNull()
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('textbox', { name: 'Toplam işlem adedi' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.te.open.r.100'))).toEqual({ ids: ['summary'] })

    fireEvent.click(screen.getByRole('button', { name: 'Tümünü aç' }))
    expect(cards().every((c) => c.getAttribute('data-open') === 'true')).toBe(true)
    expect(JSON.parse(localStorage.getItem('sm.te.open.r.100'))).toEqual({ all: true })
    // Hepsi açıkken birini kapatmak diğerlerini açık bırakır
    fireEvent.click(screen.getByRole('button', { name: 'Grafikler' }))
    expect(screen.getByRole('button', { name: 'Grafikler' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Tümünü aç' })).toBeInTheDocument()

    // Yeniden açılınca hatırlanır
    unmount()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Grafikler' })).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'Tümünü aç' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tümünü kapat' }))
    expect(cards().every((c) => c.getAttribute('data-open') === 'false')).toBe(true)
  })

  it('kapatılan bölümde hatalı alan: başlıkta sayı; Kaydet bölümü açar ve kaydetmez', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    // Kaydet yalnız değişiklik varken etkin: geçerli bir değişiklik + geçersiz bir alan
    fireEvent.change(screen.getByRole('textbox', { name: 'Teknik hata oranı' }), { target: { value: '1,5' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '12x' } })
    expect(await screen.findByText(NOT_NUMBER)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    expect(screen.getByText('1 alan düzeltilmeli')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true'))
    expect(screen.queryByText('1 alan düzeltilmeli')).toBeNull()
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('sunucunun alan hatası kapalı bölümü açar', async () => {
    te().saveReport.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'values.total-tx',
      error: 'Toplam işlem adedi kabul edilmedi; sayıyı kontrol edip yeniden kaydedin.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2000' } })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true'))
    expect(screen.getAllByText('Toplam işlem adedi kabul edilmedi; sayıyı kontrol edip yeniden kaydedin.').length).toBeGreaterThan(0)
  })
})

describe('otomatik kayıt', () => {
  it('30 sn\'de bir yalnız değişiklik varken, sessizce ve auto işaretiyle kaydeder', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    te().saveReport.mockImplementation(async (id, values, version) => okSave(version + 1, values.fields))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    expect(te().saveReport).not.toHaveBeenCalled()                         // değişiklik yok → istek yok
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2000' } })
    expect(status()).toHaveTextContent('Kaydedilmemiş değişiklikler var · 30 saniye içinde otomatik kaydedilir')
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    const [id, values, version, opts] = te().saveReport.mock.calls[0]
    // Otomatik kayıt isteğinin zaman aşımı var: asılı istek geri / kaydet / PDF'i bekletmesin
    expect([String(id), version, opts]).toEqual(['100', 4, AUTO])
    expect(values.fields).toEqual({ 'error-rate': 0.42, 'total-tx': 2000 })
    await waitFor(() => expect(status()).toHaveTextContent(/Tüm değişiklikler kaydedildi · otomatik kayıt \d\d:\d\d/))
    expect(screen.queryByText('Rapor kaydedildi.')).toBeNull()             // bildirim yok
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    expect(te().saveReport).toHaveBeenCalledTimes(1)                        // temiz → yeni istek yok
    // Elle kayıt: sürüm otomatik kaydın verdiği sürümle; otomatik notu kalkar
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '3000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][2]).toBe(5)
    expect(te().saveReport.mock.calls[1]).toHaveLength(3)                  // elle kayıtta auto işareti yok
    await waitFor(() => expect(status()).toHaveTextContent(/^Tüm değişiklikler kaydedildi$/))
  })

  it('sekme gizlenince kaydeder; geçersiz alan kayıtlı (eski) değeriyle gider, diğerleri kaydedilir', async () => {
    te().saveReport.mockImplementation(async (id, values, version) => okSave(version + 1, values.fields))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    const total = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    fireEvent.change(total, { target: { value: '2000' } })                 // taslakta 2000 (geçerli ara değer)
    fireEvent.change(total, { target: { value: '2000x' } })                // geçersiz
    fireEvent.change(screen.getByRole('textbox', { name: 'Teknik hata oranı' }), { target: { value: '1,5' } })
    expect(status()).toHaveTextContent('hatalı alanlar dışındakiler otomatik kaydedilir, hatalı alan eski değerinde kalır')
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].fields).toEqual({ 'error-rate': 1.5, 'total-tx': 1000 })
    expect(te().saveReport.mock.calls[0][3]).toEqual(AUTO)
    // Yazılan geçersiz metin alanda kalır, hata görünür; aynı içerik yeniden gönderilmez
    expect(total).toHaveValue('2000x')
    expect(screen.getByText(NOT_NUMBER)).toBeInTheDocument()
    await waitFor(() => expect(status()).toHaveTextContent('Kaydedilmemiş değişiklikler var'))
    hideTab()
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).toHaveBeenCalledTimes(1)
  })

  it('ağ hatası sessiz ve yeniden denenir; doğrulama reddi aynı içerikle yinelenmez; çakışma otomatik kaydı durdurur', async () => {
    te().saveReport
      .mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.'))
      .mockResolvedValueOnce({ success: false, code: 'VALIDATION_FAILED', field: 'values.total-tx',
        error: 'Toplam işlem adedi kabul edilmedi; sayıyı kontrol edip yeniden kaydedin.', status: 400 })
      .mockResolvedValueOnce({ success: false, code: 'CONCURRENT_MODIFICATION', status: 409,
        error: 'Rapor siz düzenlerken değişti; en son hâli yükleyip yeniden deneyin.' })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2000' } })
    hideTab()
    await waitFor(() => expect(status()).toHaveTextContent(/otomatik kayıt yapılamadı \(\d\d:\d\d\)/))
    expect(screen.queryByText('Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.')).toBeNull()   // bildirim yok
    hideTab()                                                               // hemen değil: artan aralıkla
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    const realNow = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(realNow + AUTOSAVE_MS + 1000)
    hideTab()                                                               // bekleme geçti → yeniden denenir
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Toplam işlem adedi kabul edilmedi; sayıyı kontrol edip yeniden kaydedin.')).toBeInTheDocument()
    // Durum satırı "yeniden denenir" demez: bu içerik kaydedilemez
    await waitFor(() => expect(status()).toHaveTextContent('otomatik kayıt bu içerikle yapılamıyor; işaretli alanı düzeltin'))
    hideTab()                                                               // aynı içerik → istek yok
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).toHaveBeenCalledTimes(2)
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2100' } })
    hideTab()                                                               // içerik değişti → yeniden
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(3))
    expect(await screen.findByText('Rapor siz düzenlerken değişti')).toBeInTheDocument()
    expect(status()).toHaveTextContent('otomatik kayıt durdu')
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2200' } })
    hideTab()                                                               // çakışma → durur
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).toHaveBeenCalledTimes(3)
  })

  it('503 LOCK_CONFLICT (kilit çatışması, 2026-10-18) çakışma DEĞİL: afiş yok, otomatik kayıt durmaz, sonraki turda yeniden dener', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const LOCK = 'Aynı kayıt üzerinde aynı anda başka bir işlem sürdüğü için veritabanı isteğinizi durdurdu; işlem tamamlanmamış olabilir. Birkaç saniye sonra tekrar deneyin; önce sayfayı yenileyip son durumu kontrol etmeniz önerilir.'
    te().saveReport
      .mockResolvedValueOnce({ success: false, code: 'LOCK_CONFLICT', status: 503, retry_after_seconds: 2, error: LOCK })
      .mockImplementation(async (id, values, version) => okSave(version + 1, values.fields))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2000' } })
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(status()).toHaveTextContent(/otomatik kayıt yapılamadı \(\d\d:\d\d\)/))
    expect(screen.queryByText('Rapor siz düzenlerken değişti')).toBeNull()   // çakışma afişi yok
    expect(status()).not.toHaveTextContent('otomatik kayıt durdu')
    expect(status()).not.toHaveTextContent('bu içerikle yapılamıyor')        // 4xx gibi engellenmez
    expect(screen.queryByText(LOCK)).toBeNull()                                // sessiz: bildirim yok
    // Bekleme (30 sn) geçince sonraki tur aynı içeriği yeniden gönderir ve kaydeder
    const realNow = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(realNow + AUTOSAVE_MS + 1000)
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][1].fields).toEqual({ 'error-rate': 0.42, 'total-tx': 2000 })
    expect(te().saveReport.mock.calls[1][3]).toEqual(AUTO)
    await waitFor(() => expect(status()).toHaveTextContent(/Tüm değişiklikler kaydedildi · otomatik kayıt \d\d:\d\d/))
    expect(screen.queryByText('Rapor siz düzenlerken değişti')).toBeNull()
  })

  it('elle kayıt ve ayrılma süren otomatik kaydı bekler (aynı sürümle iki istek gitmez, boşuna onay sorulmaz)', async () => {
    const first = deferred()
    te().saveReport.mockImplementationOnce(() => first.promise)
    te().saveReport.mockImplementation(async (id, values, version) => okSave(version + 1, values.fields))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    const total = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    fireEvent.change(total, { target: { value: '2000' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(status()).toHaveTextContent('Otomatik kaydediliyor…')
    fireEvent.change(total, { target: { value: '2500' } })                 // otomatik kayıt sürerken
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).toHaveBeenCalledTimes(1)                        // bekliyor
    first.resolve(okSave(5, { 'error-rate': 0.42, 'total-tx': 2000 }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][2]).toBe(5)
    expect(te().saveReport.mock.calls[1][1].fields['total-tx']).toBe(2500)
    expect(await screen.findByText('Rapor kaydedildi.')).toBeInTheDocument()

    // Ayrılma: otomatik kayıt bitince taslak temiz → onay sorulmadan kanala dönülür
    const second = deferred()
    te().saveReport.mockImplementationOnce(() => second.promise)
    fireEvent.change(total, { target: { value: '2600' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(3))
    fireEvent.click(screen.getByRole('button', { name: 'Mobil kanalına dön' }))
    second.resolve(okSave(7, { 'error-rate': 0.42, 'total-tx': 2600 }))
    await waitFor(() => expect(te().reports).toHaveBeenCalled())
    expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull()
  })

  it('otomatik kayıt taslağa dokunmaz: yazılan sondaki boşluk / "- " kalır; sunucunun kırptığı metin "kaydedildi" sayılır ve yeniden gönderilmez', async () => {
    // Sunucu satır sonundaki boşluğu kırpar (TechErrorTemplate.multiline)
    te().saveReport.mockImplementation(async (id, values, version) => ({ success: true, data: { version: version + 1, changed: true,
      updated_at: '2026-10-16T09:00:00Z', updated_by_name: 'Ayşe Yılmaz',
      values: { ...values, texts: Object.fromEntries(Object.entries(values.texts || {}).map(([k, v]) => [k, String(v).trimEnd()])) } } }))
    openAll()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const box = screen.getByRole('textbox', { name: 'Grafikler hakkında' })
    fireEvent.change(box, { target: { value: 'Birinci madde\n- ' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(status()).toHaveTextContent(/Tüm değişiklikler kaydedildi · otomatik kayıt/))
    expect(box).toHaveValue('Birinci madde\n- ')                           // yazılan olduğu gibi
    hideTab()
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).toHaveBeenCalledTimes(1)                        // sunucu bunu zaten tutuyor
    fireEvent.change(box, { target: { value: 'Birinci madde\n- ikinci' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
  })

  it('yarım yazılmış sayı ("7,42mi") kaydedilmemiş sayılır: otomatik kayıt kayıtlı değeri gönderir, "tümü kaydedildi" demez', async () => {
    te().saveReport.mockImplementation(async (id, values, version) => okSave(version + 1, values.fields))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    const total = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    fireEvent.change(total, { target: { value: '7,42' } })
    fireEvent.change(total, { target: { value: '7,42mi' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Teknik hata oranı' }), { target: { value: '1,5' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].fields).toEqual({ 'error-rate': 1.5, 'total-tx': 1000 })
    await waitFor(() => expect(status()).toHaveTextContent('Kaydedilmemiş değişiklikler var'))
    expect(status()).not.toHaveTextContent('Tüm değişiklikler kaydedildi')
    // Yazım tamamlanınca o da kaydedilir
    fireEvent.change(total, { target: { value: '7,42Mn' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][1].fields['total-tx']).toBe(7_420_000)
  })

  it('ESKİ sürümün yedeği geri yüklenince otomatik kayıt durur (başkasının kaydı ezilmez); elle Kaydet sonrası yeniden çalışır', async () => {
    te().saveReport.mockImplementation(async (id, values, version) => okSave(version + 1, values.fields))
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 3, at: Date.now(),
      values: { fields: { 'error-rate': 9.9, 'total-tx': 1 }, issues: {}, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(await screen.findByRole('button', { name: 'Geri yükle' }))
    await waitFor(() => expect(status()).toHaveTextContent('eski bir taslak geri yüklendiği için otomatik kayıt duraklatıldı'))
    hideTab()
    await new Promise((r) => setTimeout(r, 20))
    expect(te().saveReport).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0]).toHaveLength(3)                   // elle kayıt — kararı kullanıcı verdi
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '5' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    expect(te().saveReport.mock.calls[1][3]).toEqual(AUTO)
  })

  it('"bulunan taslak" uyarısı otomatik kayıtla kaybolmaz (eski taslağın son kopyası orada); alan dışı ret sunucunun metnini söyler', async () => {
    te().saveReport
      .mockImplementationOnce(async (id, values, version) => okSave(version + 1, values.fields))
      .mockResolvedValueOnce({ success: false, status: 403,
        error: 'Bu raporu düzenleme yetkiniz kalmadı; takım yöneticinize başvurun.' })
    localStorage.setItem('sm.te.draft.100', JSON.stringify({ version: 4, at: Date.now(),
      values: { fields: { 'error-rate': 7.7 }, issues: {}, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(await screen.findByRole('button', { name: 'Geri yükle' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2000' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(status()).toHaveTextContent('otomatik kayıt'))
    expect(screen.getByRole('button', { name: 'Geri yükle' })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '2100' } })
    hideTab()
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(status()).toHaveTextContent(
      'otomatik kayıt yapılamıyor: Bu raporu düzenleme yetkiniz kalmadı; takım yöneticinize başvurun.'))
  })

  it('salt okunur raporda otomatik kayıt yok', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    te().report.mockResolvedValue(reportResponse({ can_edit: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS * 3) })
    hideTab()
    expect(te().saveReport).not.toHaveBeenCalled()
    // Salt okunur izleyici de bölümleri açıp kapatabilir
    fireEvent.click(screen.getByRole('button', { name: SUMMARY }))
    expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true')
  })
})
