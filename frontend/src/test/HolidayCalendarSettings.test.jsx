import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// Ayarlar → Platform → Tatil takvimi: yıl süzgeci, ekle (tarih + ad; yinelenen / boş ad alanın altında), satır içi düzenle,
// sil (onaylı), kaydet çubuğu yalnız değişince ve tam liste PUT (sıralı, adlar kırpılmış), sunucu hatası bantta + satırda,
// salt okunur (global yönetici değil ya da can_edit false), okunamazsa hata + tekrar dene (asla "tatil yok" değil).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ admin: { getHolidays: vi.fn(), saveHolidays: vi.fn() } }) }))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, id, invalid }) => <input data-testid={`date-${id}`} id={id} aria-invalid={invalid} value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
import { api } from '../api/client'
import HolidayCalendarSettings from '../components/admin/HolidayCalendarSettings.jsx'

const Y = new Date().getFullYear()
const DAYS = [
  { day: `${Y}-10-29`, name: 'Cumhuriyet Bayramı' },
  { day: `${Y}-05-19`, name: 'Atatürk\'ü Anma, Gençlik ve Spor Bayramı' },
  { day: `${Y + 1}-01-01`, name: 'Yılbaşı' },
]

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.admin.getHolidays.mockResolvedValue({ success: true, data: { days: DAYS, can_edit: true } })
})
const rows = () => [...document.querySelectorAll('[data-slot="holiday-row"]')]

describe('HolidayCalendarSettings', () => {
  it('bu yılın günleri sıralı; yıl süzgeci; bayram doğrulama notu; kaydet çubuğu temiz', async () => {
    render(<HolidayCalendarSettings />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(rows().map((r) => r.getAttribute('data-day'))).toEqual([`${Y}-05-19`, `${Y}-10-29`])
    expect(screen.getByText('Dini bayram tarihlerini doğrulayın')).toBeInTheDocument()
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    expect(bar.getAttribute('data-dirty')).toBeNull()
    const next = screen.getByRole('button', { name: String(Y + 1) })
    fireEvent.mouseDown(next); fireEvent.click(next)
    expect(rows().map((r) => r.getAttribute('data-day'))).toEqual([`${Y + 1}-01-01`])
    const all = screen.getByRole('button', { name: 'Tümü' })
    fireEvent.mouseDown(all); fireEvent.click(all)
    expect(rows()).toHaveLength(3)
  })

  it('ekle: boş ad / yinelenen tarih alanın altında; geçerli ekleme kaydet çubuğunu kirletir ve tam liste PUT eder', async () => {
    api.admin.saveHolidays.mockImplementation((days) => Promise.resolve({ success: true, data: { days, can_edit: true } }))
    render(<HolidayCalendarSettings />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    const add = document.querySelector('[data-slot="holiday-add"]')
    fireEvent.change(add.querySelector('input[data-testid]'), { target: { value: `${Y}-10-29` } })
    fireEvent.click(screen.getByRole('button', { name: 'Ekle' }))
    expect(within(add).getByText('Bu tarih zaten listede.')).toBeInTheDocument()
    expect(within(add).getByText('Günün adını yazın.')).toBeInTheDocument()
    fireEvent.change(add.querySelector('input[data-testid]'), { target: { value: `${Y}-04-23` } })
    fireEvent.change(within(add).getByLabelText(/^Ad/), { target: { value: '  Ulusal Egemenlik ve Çocuk Bayramı ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ekle' }))
    expect(rows().map((r) => r.getAttribute('data-day'))).toEqual([`${Y}-04-23`, `${Y}-05-19`, `${Y}-10-29`])
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    expect(bar.getAttribute('data-dirty')).toBe('true')
    fireEvent.click(within(bar).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveHolidays).toHaveBeenCalled())
    expect(api.admin.saveHolidays.mock.calls[0][0]).toEqual([
      { day: `${Y}-04-23`, name: 'Ulusal Egemenlik ve Çocuk Bayramı' },
      { day: `${Y}-05-19`, name: DAYS[1].name },
      { day: `${Y}-10-29`, name: DAYS[0].name },
      { day: `${Y + 1}-01-01`, name: 'Yılbaşı' },
    ])
    await waitFor(() => expect(document.querySelector('[data-slot="settings-save-bar"]').getAttribute('data-dirty')).toBeNull())
  })

  it('satır içi düzenle ve sil (onaylı); vazgeç kirli durumu geri alır', async () => {
    render(<HolidayCalendarSettings />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    fireEvent.click(screen.getByRole('button', { name: 'Düzenle: Cumhuriyet Bayramı' }))
    const editing = document.querySelector('[data-slot="holiday-row"][data-editing="true"]')
    fireEvent.change(within(editing).getByLabelText(/^Ad/), { target: { value: 'Cumhuriyet Bayramı (tam gün)' } })
    fireEvent.click(within(editing).getByRole('button', { name: 'Tamam' }))
    expect(screen.getByText('Cumhuriyet Bayramı (tam gün)')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^Sil: Atatürk/ }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Gün silinsin mi/ })).getByRole('button', { name: 'Günü kaldır' }))
    await waitFor(() => expect(rows()).toHaveLength(1))
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    fireEvent.click(within(bar).getByRole('button', { name: /Vazgeç/ }))
    expect(rows()).toHaveLength(2)
    expect(screen.getByText('Cumhuriyet Bayramı')).toBeInTheDocument()
  })

  it('sunucu reddi: bant + ilgili satırda ileti; liste korunur', async () => {
    api.admin.saveHolidays.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', error: 'Takvim geçersiz', fields: { 'days[0].name': 'Ad çok uzun' } })
    render(<HolidayCalendarSettings />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    fireEvent.click(screen.getByRole('button', { name: /^Sil: Atatürk/ }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Gün silinsin mi/ })).getByRole('button', { name: 'Günü kaldır' }))
    await waitFor(() => expect(rows()).toHaveLength(1))
    fireEvent.click(within(document.querySelector('[data-slot="settings-save-bar"]')).getByRole('button', { name: /Kaydet/ }))
    expect(await screen.findByText('Takvim geçersiz')).toBeInTheDocument()
    // days[0] = sıralı gövdenin ilki = Cumhuriyet Bayramı
    expect(within(document.querySelector(`[data-slot="holiday-row"][data-day="${Y}-10-29"]`)).getByText('Ad çok uzun')).toBeInTheDocument()
  })

  it('salt okunur (kabuk readOnly ya da sunucu can_edit=false): ekle / düzenle / kaydet yok, bilgi bandı', async () => {
    const { unmount } = render(<HolidayCalendarSettings readOnly />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(document.querySelector('[data-slot="holiday-readonly"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="holiday-add"]')).toBeNull()
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /^Düzenle:/ })).toBeNull()
    unmount()
    api.admin.getHolidays.mockResolvedValue({ success: true, data: { days: DAYS, can_edit: false } })
    render(<HolidayCalendarSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="holiday-readonly"]')).not.toBeNull())
  })

  it('okunamazsa hata + tekrar dene; liste boş denmez', async () => {
    api.admin.getHolidays.mockResolvedValueOnce({ success: false, error: 'Sunucuya ulaşılamadı' })
    render(<HolidayCalendarSettings />)
    expect(await screen.findByText('Tatil takvimi şu an okunamadı')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="holiday-empty"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(rows()).toHaveLength(2))
  })
})
