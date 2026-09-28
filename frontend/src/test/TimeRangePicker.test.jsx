import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import TimeRangePicker, { resolveRange, QUICK_RANGES } from '../components/ui/TimeRangePicker.jsx'

describe('TimeRangePicker resolveRange', () => {
  it('relative range → to≈now, from = now - minutes (saniye hassasiyetinde tam fark)', () => {
    const { from, to } = resolveRange({ type: 'rel', minutes: 60 })
    const fromMs = new Date(from + 'Z').getTime()
    const toMs = new Date(to + 'Z').getTime()
    expect(toMs - fromMs).toBe(3600_000)                       // tam 1 saat
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)  // UTC ISO, Z'siz
  })

  it('absolute range → yerel from/to UTC ISO (Z-siz) olur', () => {
    const { from, to } = resolveRange({ type: 'abs', from: '2026-06-18T10:00', to: '2026-06-18T11:00' })
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    expect(to).toHaveLength(19)
    // 1 saatlik mutlak aralık → fark 1 saat
    expect(new Date(to + 'Z').getTime() - new Date(from + 'Z').getTime()).toBe(3600_000)
  })

  it('mutlak aralık: başlangıç bitişten sonraysa Uygula KAPALI + ileti; onChange çağrılmaz (2026-09-28c ek-4)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 8, 28, 12, 0, 0))   // başlangıç 11:00, bitiş 12:00 (yerel)
    try {
      const onChange = vi.fn()
      render(<TimeRangePicker value={{ type: 'rel', minutes: 60, key: '1h' }} onChange={onChange} />)
      fireEvent.click(document.querySelector('[data-slot="time-range-trigger"]'))
      const apply = () => screen.getByRole('button', { name: /^(Apply|Uygula)$/, hidden: true })
      expect(await screen.findByRole('button', { name: /^(Apply|Uygula)$/ })).toBeEnabled()
      expect(screen.queryByRole('alert', { hidden: true })).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: /^(From|Başlangıç) /, hidden: true }))
      fireEvent.change(await screen.findByLabelText(/^(Time|Saat)$/), { target: { value: '23:30' } })   // başlangıç 23:30 > bitiş 12:00
      expect(await screen.findByRole('alert', { hidden: true })).toHaveTextContent(/The start must be before the end|Başlangıç, bitişten sonra olamaz/)
      expect(apply()).toBeDisabled()
      fireEvent.click(apply())
      expect(onChange).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('QUICK_RANGES 5m..7d aralıklarını kapsar', () => {
    expect(QUICK_RANGES.map(r => r.key)).toEqual(['5m', '15m', '30m', '1h', '3h', '6h', '12h', '24h', '2d', '7d'])
    expect(QUICK_RANGES.find(r => r.key === '7d').minutes).toBe(10080)
  })
})
