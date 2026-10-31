import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, act, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// Zamanlama düzenleyicisi: kip, hazır aralıklar, özel aralık, cron + insan dilinde açıklama, izinli / yasak pencereler
// (gece yarısını aşan özet), tatil anahtarı, ek tarih, canlı önizleme (debounce, sıra numarası, hata → nötr, "hiç
// çalışmaz", sunucu hataları alanın altında; çağıranın fieldErrors'ı önceliklidir).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ monitoring: { dbQuerySchedulePreview: vi.fn() } }) }))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder, disabled }) => <input aria-label={placeholder || 'date'} value={value || ''} disabled={disabled} onChange={(e) => onChange(e.target.value)} />,
}))
import { api } from '../api/client'
import ScheduleEditor from '../components/dbquery/ScheduleEditor.jsx'

const BASE = { schedule_mode: 'INTERVAL', interval_seconds: 300, cron_expression: null, windows: [], blackouts: [], skip_holidays: false, skip_dates: [] }
function Harness({ initial = BASE, spy, fieldErrors, disabled }) {
  const [v, setV] = useState(initial)
  return <ScheduleEditor value={v} fieldErrors={fieldErrors} disabled={disabled} onChange={(p) => { setV((s) => ({ ...s, ...p })); spy?.(p) }} />
}
const preview = () => document.querySelector('[data-slot="dbq-schedule-preview"]')
const NEXT = ['2026-11-02T06:00:00', '2026-11-02T06:05:00', '2026-11-02T06:10:00']

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbQuerySchedulePreview.mockResolvedValue({ success: true, data: { valid: true, errors: [], next: NEXT, effective_interval_seconds: 300, never_runs: false } })
})

describe('ScheduleEditor — önizleme', () => {
  it('değer değişince önizler: sonraki çalışmalar İstanbul saatiyle; istek gövdesi yalnız zamanlama alanları', async () => {
    render(<Harness />)
    await waitFor(() => expect(preview().getAttribute('data-state')).toBe('ok'), { timeout: 2000 })
    const items = preview().querySelectorAll('[data-slot="dbq-next-runs"] li')
    expect(items).toHaveLength(3)
    expect(items[0].textContent).toMatch(/02\.11\.2026 09:00/)
    expect(api.monitoring.dbQuerySchedulePreview.mock.calls[0][0]).toEqual({
      schedule_mode: 'INTERVAL', interval_seconds: 300, cron_expression: null, windows: [], blackouts: [], skip_holidays: false, skip_dates: [],
    })
    expect(screen.getByText(/Saatler İstanbul saatidir/)).toBeInTheDocument()
  })

  it('sunucu hataları alanın altında (ileti sunucudan), çağıranın hatası önceliklidir', async () => {
    api.monitoring.dbQuerySchedulePreview.mockResolvedValue({ success: true, data: {
      valid: false, never_runs: false, next: [], effective_interval_seconds: null,
      errors: [
        { field: 'windows', code: 'DBQUERY_WINDOW_INVALID', message: 'Pencere 1 geçersiz · Pencereyi düzeltin.' },
        { field: 'skip_dates', code: 'DBQUERY_SKIP_DATE_INVALID', message: "Ek tarih '2026-13-01' geçersiz" },
        { field: 'interval_seconds', code: 'DBQUERY_INTERVAL_INVALID', message: 'sunucu iletisi' },
      ],
    } })
    render(<Harness fieldErrors={{ interval_seconds: 'çağıranın iletisi' }} />)
    await waitFor(() => expect(within(document.querySelector('[data-slot="dbq-windows"]')).getByText('Pencere 1 geçersiz · Pencereyi düzeltin.')).toBeInTheDocument(), { timeout: 2000 })
    expect(screen.getByText("Ek tarih '2026-13-01' geçersiz")).toBeInTheDocument()
    expect(screen.getByText('çağıranın iletisi')).toBeInTheDocument()
    expect(screen.queryByText('sunucu iletisi')).toBeNull()
  })

  it('hiç çalışmaz (INTERVAL, alan schedule): sunucu iletisi bantta TEK kez', async () => {
    const MSG = 'Bu zamanlamayla sorgu önümüzdeki 366 gün içinde hiç çalışmaz · Pencereleri gözden geçirin.'
    api.monitoring.dbQuerySchedulePreview.mockResolvedValue({ success: true, data: {
      valid: false, never_runs: true, next: [], effective_interval_seconds: null,
      errors: [{ field: 'schedule', code: 'DBQUERY_SCHEDULE_NEVER_RUNS', message: MSG }],
    } })
    render(<Harness />)
    await waitFor(() => expect(screen.getByText('Bu zamanlama hiç çalışmıyor')).toBeInTheDocument(), { timeout: 2000 })
    expect(screen.getAllByText(MSG)).toHaveLength(1)
    expect(preview().querySelector('[data-slot="dbq-schedule-never-runs"]').textContent).toBe(MSG)
  })

  it('hiç çalışmaz (CRON, alan cron_expression): cron alanının altında tekrarlanmaz; kayıt hatası aynı iletiyse de tek', async () => {
    const MSG = 'Bu zamanlamayla sorgu hiç çalışmaz · Cron ifadesini gözden geçirin.'
    api.monitoring.dbQuerySchedulePreview.mockResolvedValue({ success: true, data: {
      valid: false, never_runs: true, next: [], effective_interval_seconds: null,
      errors: [{ field: 'cron_expression', code: 'DBQUERY_SCHEDULE_NEVER_RUNS', message: MSG }],
    } })
    render(<Harness initial={{ ...BASE, schedule_mode: 'CRON', cron_expression: '0 3 31 2 *' }} fieldErrors={{ cron_expression: MSG }} />)
    await waitFor(() => expect(screen.getByText('Bu zamanlama hiç çalışmıyor')).toBeInTheDocument(), { timeout: 2000 })
    expect(screen.getAllByText(MSG)).toHaveLength(1)
  })

  it('cron çok sık / geçersiz: sunucu iletisi + koda göre kısa ipucu; kip geçersiz kip seçicinin altında', async () => {
    api.monitoring.dbQuerySchedulePreview.mockResolvedValue({ success: true, data: {
      valid: false, never_runs: false, next: [], effective_interval_seconds: null,
      errors: [
        { field: 'cron_expression', code: 'DBQUERY_CRON_TOO_FREQUENT', message: 'Cron ifadesi çok sık çalışıyor (iki çalışma arası 30 saniye; en az 60)' },
        { field: 'schedule', code: 'DBQUERY_SCHEDULE_MODE_INVALID', message: 'Zamanlama kipi geçersiz' },
      ],
    } })
    render(<Harness initial={{ ...BASE, schedule_mode: 'CRON', cron_expression: '*/30 * * * * *' }} />)
    await waitFor(() => expect(screen.getByText(/Cron ifadesi çok sık çalışıyor/)).toBeInTheDocument(), { timeout: 2000 })
    expect(document.querySelector('[data-slot="dbq-cron-code-hint"]').textContent).toContain('*/15')
    expect(within(document.querySelector('[data-field="schedule_mode"]')).getByText('Zamanlama kipi geçersiz')).toBeInTheDocument()
    expect(within(preview()).queryByText('Zamanlama kipi geçersiz')).toBeNull()
  })

  it('önizleme okunamazsa nötr "hesaplanamadı" (asla boş başarı değil)', async () => {
    api.monitoring.dbQuerySchedulePreview.mockRejectedValueOnce(new Error('Ağ hatası'))
    render(<Harness />)
    await waitFor(() => expect(preview().getAttribute('data-state')).toBe('unknown'), { timeout: 2000 })
    expect(document.querySelector('[data-slot="dbq-schedule-preview-unknown"]').textContent).toContain('Ağ hatası')
    expect(preview().querySelector('[data-slot="dbq-next-runs"]')).toBeNull()
  })

  it('geç gelen eski önizleme yanıtı düşürülür', async () => {
    let first
    api.monitoring.dbQuerySchedulePreview
      .mockImplementationOnce(() => new Promise((r) => { first = r }))
      .mockResolvedValueOnce({ success: true, data: { valid: true, errors: [], next: ['2026-11-02T07:00:00'], never_runs: false } })
    render(<Harness />)
    await waitFor(() => expect(api.monitoring.dbQuerySchedulePreview).toHaveBeenCalledTimes(1), { timeout: 2000 })
    fireEvent.click(screen.getByRole('radio', { name: '1 sa' }))
    await waitFor(() => expect(preview().querySelectorAll('[data-slot="dbq-next-runs"] li')).toHaveLength(1), { timeout: 2000 })
    await act(async () => { first({ success: true, data: { valid: true, errors: [], next: NEXT, never_runs: false } }) })
    expect(preview().querySelectorAll('[data-slot="dbq-next-runs"] li')).toHaveLength(1)
  })

  it('cron kipinde ifade boşken istek atılmaz', async () => {
    render(<Harness initial={{ ...BASE, schedule_mode: 'CRON', cron_expression: '' }} />)
    await new Promise((r) => setTimeout(r, 650))
    expect(api.monitoring.dbQuerySchedulePreview).not.toHaveBeenCalled()
    expect(preview().textContent).toContain('Cron ifadesini girince')
  })
})

describe('ScheduleEditor — alanlar', () => {
  it('hazır aralık seçimi patch yollar; özel aralık dakika / saat', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    expect(screen.getByRole('radio', { name: '5 dk' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('radio', { name: '1 gün' }))
    expect(spy).toHaveBeenLastCalledWith({ interval_seconds: 86400 })
    fireEvent.click(screen.getByRole('radio', { name: 'Özel' }))
    fireEvent.change(screen.getByLabelText('Süre'), { target: { value: '90' } })
    expect(spy).toHaveBeenLastCalledWith({ interval_seconds: 90 * 86400 })
    fireEvent.change(screen.getByLabelText('Birim'), { target: { value: 'minutes' } })
    expect(spy).toHaveBeenLastCalledWith({ interval_seconds: 90 * 60 })
    fireEvent.change(screen.getByLabelText('Süre'), { target: { value: '0' } })
    expect(screen.getByText('Aralığı girin.')).toBeInTheDocument()
  })

  it('cron kipi: hazır şablon + insan dilinde açıklama; çözülemezse "açıklanamadı"', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} initial={{ ...BASE, schedule_mode: 'CRON', cron_expression: '' }} />)
    fireEvent.click(screen.getByRole('button', { name: /İş günleri 09:00/ }))
    expect(spy).toHaveBeenLastCalledWith({ cron_expression: '0 9 * * 1-5' })
    expect(document.querySelector('[data-slot="dbq-cron-desc"]').textContent).toBe('Anlamı: İş günleri (Pzt–Cum) saat 09:00')
    fireEvent.change(screen.getByLabelText(/Cron ifadesi/), { target: { value: '0 9 L * *' } })
    const d = document.querySelector('[data-slot="dbq-cron-desc"]')
    expect(d.getAttribute('data-explained')).toBe('false')
    expect(d.textContent).toContain('açıklanamadı')
  })

  it('kip değişimi', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    const b = screen.getByRole('button', { name: /Cron ifadesiyle/ })
    fireEvent.mouseDown(b); fireEvent.click(b)
    expect(spy).toHaveBeenLastCalledWith({ schedule_mode: 'CRON' })
    expect(screen.getByLabelText(/Cron ifadesi/)).toBeInTheDocument()
  })

  it('izinli pencere ekle → iş günleri 09:00–18:00; gece yarısını aşan özet; gün seçilmezse hata; kaldır', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    fireEvent.click(screen.getByRole('button', { name: 'İzinli pencere ekle' }))
    expect(spy).toHaveBeenLastCalledWith({ windows: [{ days: [1, 2, 3, 4, 5], from: '09:00', to: '18:00' }] })
    const row = document.querySelector('[data-slot="dbq-windows"] [data-slot="dbq-window"]')
    expect(row.querySelector('[data-slot="dbq-window-summary"]').textContent).toContain('Pzt–Cum 09:00–18:00')
    fireEvent.change(within(row).getByLabelText('Başlangıç'), { target: { value: '22:00' } })
    fireEvent.change(within(row).getByLabelText('Bitiş'), { target: { value: '06:00' } })
    expect(row.getAttribute('data-kind')).toBe('overnight')
    expect(row.textContent).toContain('ertesi gün 06:00')
    for (const d of ['Pazartesi', 'Salı', 'Çarşamba', 'Perşembe', 'Cuma']) fireEvent.click(within(row).getByRole('button', { name: d }))
    expect(row.textContent).toContain('En az bir gün seçin.')
    fireEvent.click(within(row).getByRole('button', { name: 'Kaldır: İzinli pencere 1' }))
    expect(spy).toHaveBeenLastCalledWith({ windows: [] })
  })

  it('yasak pencere ayrı listede', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    fireEvent.click(screen.getByRole('button', { name: 'Yasak pencere ekle' }))
    expect(spy).toHaveBeenLastCalledWith({ blackouts: [{ days: [1, 2, 3, 4, 5], from: '09:00', to: '18:00' }] })
  })

  it('tatil anahtarı + takvim yeni sekmede; ek tarih ekle / yinelenen reddi / kaldır', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    fireEvent.click(screen.getByRole('switch', { name: /Tatil takvimindeki günlerde çalışma/ }))
    expect(spy).toHaveBeenLastCalledWith({ skip_holidays: true })
    const link = screen.getByRole('link', { name: /Tatil takvimini yeni sekmede aç/ })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('href')).toContain('sec=holidays')
    fireEvent.change(screen.getByLabelText('Tarih ekle'), { target: { value: '2026-12-31' } })
    expect(spy).toHaveBeenLastCalledWith({ skip_dates: ['2026-12-31'] })
    fireEvent.change(screen.getByLabelText('Tarih ekle'), { target: { value: '2026-12-31' } })
    expect(screen.getByText('Bu tarih zaten listede.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^Kaldır: 31 Aralık 2026/ }))
    expect(spy).toHaveBeenLastCalledWith({ skip_dates: [] })
  })

  it('salt okunur: değişiklik yollanmaz', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} disabled />)
    expect(screen.getByRole('button', { name: 'İzinli pencere ekle' })).toBeDisabled()
    expect(screen.getByRole('switch', { name: /Tatil takvimindeki/ })).toBeDisabled()
  })
})
