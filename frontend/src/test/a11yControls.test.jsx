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

  it('Enter ve Space seçiciyi AÇAR (eskiden yalnız fare basışı açıyordu)', async () => {
    const user = userEvent.setup()
    const { trigger } = setup()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')

    await user.tab()
    expect(trigger).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeInTheDocument()

    await user.keyboard('{Enter}')   // tekrar: kapatır
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    await user.keyboard(' ')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
  })

  it('ay gezinme düğmelerinin adı var ve ayı değiştirir; Escape içeriden kapatıp odağı tetiğe verir', async () => {
    const user = userEvent.setup()
    const { trigger } = setup()
    await user.click(trigger)
    const prev = screen.getByRole('button', { name: 'Previous month' })
    const next = screen.getByRole('button', { name: 'Next month' })
    const title = prev.nextElementSibling
    const before = title.textContent
    await user.click(next)
    expect(title.textContent).not.toBe(before)

    next.focus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('button', { name: 'Next month' })).toBeNull()
    expect(trigger).toHaveFocus()
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
    expect(document.activeElement).toHaveClass('dp-trigger')
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
