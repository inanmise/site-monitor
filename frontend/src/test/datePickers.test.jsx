import { describe, it, expect, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import DateTimeField from '../components/ui/DateTimeField.jsx'
import DateTimeRangePicker from '../components/ui/DateTimeRangePicker.jsx'
import TimeRangePicker from '../components/ui/TimeRangePicker.jsx'
import WeekDatePicker from '../components/ui/WeekDatePicker.jsx'

/**
 * Tarih seçicilerin shadcn'e (Popover + Calendar + Input type=time) geçişi — 2026-09-26, D1 dalgası.
 * Değer SÖZLEŞMELERİ react-datepicker dönemindekiyle birebir kalmalı; çağıran ekranlar (Olay Geçmişi, Bakım
 * pencereleri, Denetim, İzleme değişiklikleri, grafikler…) bu biçimlere güveniyor. Beklenen değerler yerel
 * Date'ten türetilir: testler hangi saat diliminde koşarsa koşsun (CI = UTC, geliştirici = İstanbul) doğrudur.
 */
const utcIso = (d) => d.toISOString().slice(0, 19)
const grid = () => screen.getByRole('grid')
const dayButton = (re) => within(grid()).getByRole('button', { name: re })

describe('DateTimeField', () => {
  it('dateOnly: güne basmak YEREL yyyy-MM-dd verir ve pencereyi kapatır', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<DateTimeField dateOnly value="2026-09-01" onChange={onChange} placeholder="From" />)
    const trigger = screen.getByRole('button', { name: /01\.09\.2026/ })
    await user.click(trigger)
    await user.click(dayButton(/15 September 2026/))
    expect(onChange).toHaveBeenCalledWith('2026-09-15')
    expect(screen.queryByRole('grid')).toBeNull()
  })

  it('tarih+saat: gün seçimi saati KORUR, saat alanı günü korur; değer UTC ISO (Z\'siz), pencere "Tamam" ile kapanır', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const start = new Date(2026, 8, 10, 14, 30)
    render(<DateTimeField value={utcIso(start)} onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: /10\.09\.2026 14:30/ }))
    await user.click(dayButton(/12 September 2026/))
    expect(onChange).toHaveBeenLastCalledWith(utcIso(new Date(2026, 8, 12, 14, 30)))
    expect(screen.getByRole('grid')).toBeInTheDocument()   // saatli kipte gün seçimi kapatmaz

    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '09:05' } })
    expect(onChange).toHaveBeenLastCalledWith(utcIso(new Date(2026, 8, 10, 9, 5)))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('grid')).toBeNull())
  })

  it('değer yokken takvim gelecekteki alt sınırın ayına açılır, öncesi seçilemez; gün seçimi 00:00 verir', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    // Kayan tarih (sabit fixture zaman bombası olurdu): alt sınır hep ~40 gün sonra, ayın ortasında.
    const min = new Date(); min.setDate(min.getDate() + 40); min.setHours(0, 0, 0, 0)
    if (min.getDate() < 3) min.setDate(3)
    if (min.getDate() > 26) min.setDate(26)
    const dayBefore = new Date(min.getFullYear(), min.getMonth(), min.getDate() - 1)
    const dayAfter = new Date(min.getFullYear(), min.getMonth(), min.getDate() + 1)
    const nameOf = (d) => new RegExp(`\\b${d.getDate()} ${d.toLocaleString('en-GB', { month: 'long' })} ${d.getFullYear()}`)
    render(<DateTimeField value="" min={utcIso(min)} onChange={onChange} placeholder="Start" />)
    await user.click(screen.getByRole('button', { name: 'Start' }))
    expect(dayButton(nameOf(dayBefore))).toBeDisabled()
    await user.click(dayButton(nameOf(dayAfter)))
    expect(onChange).toHaveBeenLastCalledWith(utcIso(new Date(dayAfter.getFullYear(), dayAfter.getMonth(), dayAfter.getDate(), 0, 0)))
  })

  it('dtf-inline eski sınıf sözleşmesi: DOM\'a sızmaz, sarmalayıcı içerik genişliğinde', () => {
    const { container } = render(<DateTimeField dateOnly className="dtf-inline" value="" onChange={() => {}} placeholder="Since" />)
    const wrap = container.querySelector('[data-slot="date-picker"]')
    expect(wrap).not.toHaveClass('dtf-inline')
    expect(wrap).toHaveClass('w-auto')
    expect(wrap).not.toHaveClass('w-full')
  })
})

describe('DateTimeRangePicker', () => {
  const from = new Date(2026, 8, 1, 0, 0)
  const to = new Date(2026, 8, 5, 18, 0)

  it('kısayol anında uygular (Date, Date); Uygula yerel seçimi verir', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    render(<DateTimeRangePicker from={from} to={to} onApply={onApply} />)
    await user.click(screen.getByRole('button', { name: 'Last 7 Days' }))
    const [s, e] = onApply.mock.calls[0]
    expect(s).toBeInstanceOf(Date)
    expect(e).toBeInstanceOf(Date)
    expect(Math.round((e - s) / 864e5)).toBeGreaterThanOrEqual(6)
    expect(s.getHours()).toBe(0)
  })

  it('Başlangıç günü değişir, saat korunur; Bitiş\'ten sonrası seçilemez; Uygula iki Date verir', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    render(<DateTimeRangePicker from={from} to={to} onApply={onApply} />)
    await user.click(screen.getByRole('button', { name: /From 01\.09\.2026 00:00/ }))
    expect(dayButton(/\b6 September 2026/)).toBeDisabled()
    await user.click(dayButton(/\b3 September 2026/))
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    const [s, e] = onApply.mock.calls.at(-1)
    expect(s.getTime()).toBe(new Date(2026, 8, 3, 0, 0).getTime())
    expect(e.getTime()).toBe(to.getTime())
  })
})

describe('TimeRangePicker', () => {
  it('hızlı aralık seçilince rel tanımlayıcı döner ve pencere kapanır; aktif aralık aria-pressed', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TimeRangePicker value={{ type: 'rel', minutes: 60, key: '1h' }} onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: /Last 1 hour/ }))
    expect(screen.getByRole('button', { name: 'Last 1 hour', pressed: true })).toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: /Search quick ranges/ }), '7')
    expect(screen.queryByRole('button', { name: 'Last 5 minutes' })).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Last 7 days' }))
    expect(onChange).toHaveBeenCalledWith({ type: 'rel', minutes: 10080, key: '7d' })
    await waitFor(() => expect(screen.queryByRole('textbox', { name: /Search quick ranges/ })).toBeNull())
  })

  it('mutlak aralık: Başlangıç/Bitiş seçicileri yerel "yyyy-MM-ddTHH:mm" ile abs tanımlayıcı üretir', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TimeRangePicker value={{ type: 'rel', minutes: 60, key: '1h' }} onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: /Last 1 hour/ }))
    await user.click(screen.getByRole('button', { name: /^From / }))
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '08:15' } })
    await user.click(screen.getByRole('button', { name: 'Done' }))
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    const arg = onChange.mock.calls.at(-1)[0]
    expect(arg.type).toBe('abs')
    expect(arg.from).toMatch(/^\d{4}-\d{2}-\d{2}T08:15$/)
    expect(arg.to).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
  })
})

/**
 * DOKUNMATİK (2026-09-28): tarih / zaman aralığı tetikleri 36 px (h-9) — kardeş Input / Button ile aynı hiza. Görsel yükseklik
 * DEĞİŞMEZ; `pointer: coarse`'da görünmez `::after` dikeyde ortalanmış 40 px vuruş alanı verir. jsdom medya sorgusu ve
 * sözde öğe çizmez → sözleşme sınıf düzeyinde; gerçek ölçüm Playwright'ta (elementFromPoint, hasTouch + isMobile).
 */
describe('tarih seçici tetikleri — dokunmatik vuruş alanı', () => {
  const TOUCH = ['relative', 'pointer-coarse:after:absolute', 'pointer-coarse:after:inset-x-0', 'pointer-coarse:after:top-1/2',
    'pointer-coarse:after:h-10', 'pointer-coarse:after:-translate-y-1/2']

  it('DateTimeField / DateTimeRangePicker tetikleri: görsel h-9 aynı, dokunmatikte 40 px ::after alanı; × kendi alanını korur', () => {
    const { unmount } = render(<DateTimeField dateOnly clearable value="2026-09-01" onChange={() => {}} placeholder="From" />)
    const trigger = document.querySelector('[data-slot="date-picker-trigger"]')
    expect(trigger).toHaveClass('h-9', ...TOUCH)
    expect(trigger).not.toHaveClass('pointer-coarse:h-10')          // görsel yükseklik (hiza) değişmez
    // Temizleme × düğmesi tetiğin kardeşi ve DOM'da SONRA (konumlu → üstte); kendi 40 px alanı yerinde
    const clear = screen.getByRole('button', { name: /clear|temizle/i })
    expect(clear.className).toContain('pointer-coarse:after:-inset-2')
    expect(trigger.compareDocumentPosition(clear) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    unmount()
    render(<DateTimeRangePicker from={new Date(2026, 8, 1, 10, 0)} to={new Date(2026, 8, 2, 10, 0)} onApply={() => {}} />)
    const triggers = document.querySelectorAll('[data-slot="date-picker-trigger"]')
    expect(triggers).toHaveLength(2)
    for (const tr of triggers) expect(tr).toHaveClass('h-9', ...TOUCH)
  })

  it('TimeRangePicker tetiği aynı kural', () => {
    render(<TimeRangePicker value={{ type: 'rel', minutes: 60, key: '1h' }} onChange={() => {}} />)
    const trigger = document.querySelector('[data-slot="time-range-trigger"]')
    expect(trigger).toHaveClass('h-9', ...TOUCH)
  })
})

/**
 * Tarih penceresinin İÇİ (2026-09-28, 2. tur): dokunmatikte takvim hücreleri (gün, ay gezinme, hafta no.) 40 px — `--cell-size`
 * `pointer-coarse:` ile 32 → 40, iç boşluk 12 → 8 px (7 × 40 + 16 = 296; hafta numaralı 8 × 40 + 16 = 336 px → 360 px telefonda
 * `calc(100vw-1rem)` = 344 içine sığar — Playwright ölçümü). Saat alanı, Tamam, Uygula, arama, hızlı aralıklar, kısayol hapları
 * 40 px. Fare görünümü (boyut sınıfları) DEĞİŞMEZ. jsdom medya sorgusu uygulamaz → sınıf sözleşmesi.
 */
describe('tarih penceresi içi — dokunmatik 40 px', () => {
  it('DateTimeField penceresi: takvim hücresi 40 / iç boşluk 8 (fare 32 / 12 aynı), saat alanı ve Tamam 40', async () => {
    render(<DateTimeField value={utcIso(new Date(2026, 8, 10, 14, 30))} onChange={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /10\.09\.2026 14:30/ }))
    const cal = await waitFor(() => { const c = document.querySelector('[data-slot="calendar"]'); expect(c).not.toBeNull(); return c })
    expect(cal).toHaveClass('p-3', '[--cell-size:--spacing(8)]', 'pointer-coarse:p-2', 'pointer-coarse:[--cell-size:--spacing(10)]')
    // Gün ve ay gezinme düğmeleri boyutunu --cell-size'dan alır (tek kaynak)
    expect(within(grid()).getAllByRole('button')[0].className).toContain('min-w-(--cell-size)')
    for (const nav of document.querySelectorAll('[data-slot="calendar-nav-button"]')) expect(nav.className).toContain('size-(--cell-size)')
    expect(screen.getByLabelText('Time')).toHaveClass('h-9', 'pointer-coarse:h-10')
    const done = screen.getByRole('button', { name: /^(Done|Tamam)$/ })
    expect(done).toHaveAttribute('data-size', 'sm')
    expect(done).toHaveClass('pointer-coarse:h-10')
  })

  it('DateTimeRangePicker: kısayol hapları dokunmatikte 40 (fare xs), Uygula 36 hiza + 40 vuruş alanı', () => {
    render(<DateTimeRangePicker from={new Date(2026, 8, 1, 10, 0)} to={new Date(2026, 8, 2, 10, 0)} onApply={() => {}} />)
    const picker = document.querySelector('[data-slot="date-range-picker"]')
    const pills = [...picker.querySelectorAll('button[data-size="xs"]')]
    expect(pills.length).toBeGreaterThanOrEqual(3)
    for (const p of pills) expect(p).toHaveClass('pointer-coarse:h-10')
    const apply = screen.getByRole('button', { name: /^(Apply|Uygula)$/ })
    expect(apply).toHaveClass('relative', 'pointer-coarse:after:h-10', 'pointer-coarse:after:-translate-y-1/2')
  })

  it('TimeRangePicker penceresi: Uygula, arama ve hızlı aralıklar dokunmatikte 40', async () => {
    render(<TimeRangePicker value={{ type: 'rel', minutes: 60, key: '1h' }} onChange={() => {}} />)
    fireEvent.click(document.querySelector('[data-slot="time-range-trigger"]'))
    const pressed = await screen.findAllByRole('button', { pressed: false })
    const quick = [...pressed, ...screen.getAllByRole('button', { pressed: true })]
    expect(quick.length).toBeGreaterThanOrEqual(10)
    for (const b of quick) expect(b).toHaveClass('pointer-coarse:h-10')
    expect(screen.getByRole('button', { name: /^(Apply|Uygula)$/ })).toHaveClass('pointer-coarse:h-10')
    expect(screen.getByRole('textbox', { name: /search|ara/i })).toHaveClass('pointer-coarse:h-10')
  })

  it('WeekDatePicker penceresi: hafta numaralı takvim aynı hücre kuralı; Temizle / Bugün dokunmatikte 40', async () => {
    render(<WeekDatePicker value="2026-09-10" onChange={() => {}} placeholder="Week" />)
    fireEvent.click(document.querySelector('[data-slot="date-picker-trigger"]'))
    const cal = await waitFor(() => { const c = document.querySelector('[data-slot="calendar"]'); expect(c).not.toBeNull(); return c })
    expect(cal).toHaveClass('pointer-coarse:[--cell-size:--spacing(10)]', 'pointer-coarse:p-2')
    for (const name of [/^(Clear|Temizle)$/, /^(Today|Bugün)$/]) {
      const b = screen.getByRole('button', { name })
      expect(b).toHaveAttribute('data-size', 'sm')
      expect(b).toHaveClass('pointer-coarse:h-10')
    }
  })
})
