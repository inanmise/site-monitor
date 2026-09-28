import { describe, it, expect, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import WeekDatePicker from '../components/ui/WeekDatePicker.jsx'
import DateTimeField from '../components/ui/DateTimeField.jsx'
import Sparkline from '../components/ui/Sparkline.jsx'
import MonitorGuideButton from '../components/ui/MonitorGuideButton.jsx'

/**
 * Küçük kontrollerin klavye ve ad sözleşmesi — 2026-09-25 yayın öncesi regresyon raporu R14/R16.
 *
 * <p>Hepsi F8 sınıfının kardeşleri: fareyle çalışan ama klavyeyle ulaşılamayan (ya da ekran
 * okuyucuda adsız) kontroller. Testler varsayılan dilde (EN) koşar; adlar i18n'den gelir.
 */

describe('WeekDatePicker — klavye (R14)', () => {
  const setup = () => {
    const onChange = vi.fn()
    render(<WeekDatePicker value="" onChange={onChange} placeholder="Pick a date…" />)
    return { onChange, trigger: screen.getByRole('button', { name: 'Pick a date…' }) }
  }

  it('Enter ve Space seçiciyi AÇAR (eskiden yalnız fare basışı açıyordu); açılınca odak takvime geçer', async () => {
    const user = userEvent.setup()
    const { trigger } = setup()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')

    await user.tab()
    expect(trigger).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeInTheDocument()
    // shadcn Date Picker sözleşmesi: odak ızgaradaki güne (seçili yoksa bugüne) gider — ok tuşlarıyla gezilir.
    await waitFor(() => expect(screen.getByRole('grid').contains(document.activeElement)).toBe(true))

    await user.keyboard('{Escape}')   // kapatır ve odağı tetiğe iade eder
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    await waitFor(() => expect(trigger).toHaveFocus())
    await user.keyboard(' ')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
  })

  it('ay gezinme düğmelerinin adı var ve ayı değiştirir; Escape içeriden kapatıp odağı tetiğe verir', async () => {
    const user = userEvent.setup()
    const { trigger } = setup()
    await user.click(trigger)
    // Takvim düğmeleri ay değişince yeniden çizilir — her adımda güncel düğmeyi sorgula.
    const nav = (name) => screen.getByRole('button', { name })
    const monthName = () => screen.getByRole('grid').getAttribute('aria-label')
    const before = monthName()
    expect(before).toMatch(/\d{4}/)
    await user.click(nav('Next month'))
    expect(monthName()).not.toBe(before)
    await user.click(nav('Previous month'))
    expect(monthName()).toBe(before)

    nav('Next month').focus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('button', { name: 'Next month' })).toBeNull()
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('güne basmak o günü, hafta numarasına basmak haftanın PAZARTESİ\'sini yerel gün olarak seçer; rapor noktası adda', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<WeekDatePicker value="2026-09-16" onChange={onChange} placeholder="Pick a date…"
      isMarked={(y, w) => y === 2026 && w === 38} />)
    await user.click(screen.getByRole('button', { name: /16\/09\/2026|16\.09\.2026/ }))
    // 38. hafta (14–20 Eylül 2026) raporlu: adı bunu söyler; basınca Pazartesi 14 Eylül seçilir.
    const week38 = screen.getByRole('button', { name: /week 38 of 2026.*report/i })
    await user.click(week38)
    expect(onChange).toHaveBeenLastCalledWith('2026-09-14')
    expect(screen.queryByRole('grid')).toBeNull()   // seçim pencereyi kapatır

    await user.click(screen.getByRole('button', { name: /16\/09\/2026|16\.09\.2026/ }))
    expect(screen.getByRole('button', { name: /week 37 of 2026$/i })).toBeInTheDocument()   // raporsuz: ekli ad yok
    await user.click(screen.getByRole('button', { name: /Thursday, 17 September 2026/ }))
    expect(onChange).toHaveBeenLastCalledWith('2026-09-17')
  })
})

describe('DateTimeField — temizleme düğmesi (R14)', () => {
  it('temizleme GERÇEK, adlı, odaklanabilir bir düğme ve tetiğin İÇİNDE DEĞİL; tıklayınca değeri boşaltır', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<DateTimeField dateOnly clearable value="2026-09-01" onChange={onChange} placeholder="From" />)
    const clear = screen.getByRole('button', { name: 'Clear' })
    // Düğme içinde düğme değil (eskiden tetik <button>'ın içinde role="button" taşıyan bir SVG'ydi).
    expect(clear.tagName).toBe('BUTTON')
    expect(clear.parentElement.closest('button')).toBeNull()
    // Tab ona uğrar: tetikten sonraki durak.
    await user.tab()
    expect(document.activeElement).toHaveAttribute('data-slot', 'date-picker-trigger')
    await user.tab()
    expect(clear).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('')
  })

  it('değer yokken, temizlenemez alanda ya da pasifken temizleme düğmesi çizilmez', () => {
    const { unmount } = render(<DateTimeField dateOnly clearable value="" onChange={() => {}} />)
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull()
    unmount()
    const r2 = render(<DateTimeField dateOnly value="2026-09-01" onChange={() => {}} />)
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull()
    r2.unmount()
    render(<DateTimeField dateOnly clearable disabled value="2026-09-01" onChange={() => {}} />)
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull()
  })

  // 2026-09-28: görsel 24 px düğme dokunmatikte küçük kalıyordu (RESPONSIVE.md §4: hedef ≥ 40 px). jsdom yerleşim yapmaz →
  // burada SINIF sözleşmesi pinlenir (masaüstü görünümü aynı, dokunmatikte görünmez ::after 40×40); gerçek ölçüm
  // Playwright'ta (hasTouch + isMobile, matchMedia('(pointer: coarse)') doğrulanarak).
  it('dokunmatik hedef: görsel boyut icon-xs (24 px) kalır, pointer-coarse ::after katmanı vuruş alanını 40 px yapar', () => {
    render(<DateTimeField dateOnly clearable value="2026-09-01" onChange={() => {}} />)
    const clear = screen.getByRole('button', { name: 'Clear' })
    expect(clear).toHaveAttribute('data-size', 'icon-xs')
    expect(clear).toHaveClass('pointer-coarse:after:absolute', 'pointer-coarse:after:-inset-2')
    expect(clear).not.toHaveClass('pointer-coarse:size-10')   // görsel büyümez: tetiğin ayrılmış boşluğuna sığar
  })
})

describe('Sparkline — ad (R16)', () => {
  it('etiketsiz sparkline DEKORATİF: ağaçtan gizli, sabit "trend" adı okunmaz', () => {
    const { container } = render(<Sparkline data={[1, 3, 2]} />)
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('aria-hidden', 'true')
    expect(svg).not.toHaveAttribute('role')
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('etiket verilirse görsel olarak adlanır', () => {
    render(<Sparkline data={[1, 3, 2]} label="Logins, last 7 days" />)
    expect(screen.getByRole('img', { name: 'Logins, last 7 days' })).toBeInTheDocument()
  })
})

describe('MonitorGuideButton — kapatma düğmesi (R16)', () => {
  it('kılavuz penceresinin X düğmesinin i18n adı var ve pencereyi kapatır', async () => {
    render(<MonitorGuideButton type="http" />)
    fireEvent.click(document.querySelector('[data-tour="mon-guide"]'))
    const close = await screen.findByRole('button', { name: 'Close' })
    fireEvent.click(close)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Close' })).toBeNull())
  })
})
