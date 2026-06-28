import { describe, it, expect } from 'vitest'
import { resolveRange, QUICK_RANGES } from '../components/ui/TimeRangePicker.jsx'

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

  it('QUICK_RANGES 5m..7d aralıklarını kapsar', () => {
    expect(QUICK_RANGES.map(r => r.key)).toEqual(['5m', '15m', '30m', '1h', '3h', '6h', '12h', '24h', '2d', '7d'])
    expect(QUICK_RANGES.find(r => r.key === '7d').minutes).toBe(10080)
  })
})
