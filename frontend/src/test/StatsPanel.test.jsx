import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import StatsPanel from '../components/StatsPanel.jsx'

const STATS = {
  total_certificates: 10,
  valid_count: 5,
  warning_count: 2,
  error_count: 1,
  expiring_in_30_days: 3,
  expired: 1,
}

/** Kart = shadcn Button (MonitorStatsBar, 2026-09-26) — eski `.stat-item[role=button]` div'i değil. */
const card = (label) => screen.getByText(label).closest('[data-slot="stat-item"]')

describe('StatsPanel', () => {
  it('renders all six stat cards', () => {
    render(<StatsPanel stats={STATS} visible={true} onStatClick={() => {}} activeFilter="" />)
    expect(screen.getByText('Total')).toBeDefined()
    expect(screen.getByText('Valid')).toBeDefined()
    expect(screen.getByText('Warning')).toBeDefined()
    expect(screen.getByText('Error')).toBeDefined()
    expect(screen.getByText('Within 30 Days')).toBeDefined()
    expect(screen.getByText('Expired')).toBeDefined()
  })

  it('displays correct stat values', () => {
    render(<StatsPanel stats={STATS} visible={true} onStatClick={() => {}} activeFilter="" />)
    expect(screen.getByText('10')).toBeDefined()
    expect(screen.getByText('5')).toBeDefined()
    expect(screen.getByText('2')).toBeDefined()
    // error_count:1 and expired:1 both render '1' — use getAllByText
    expect(screen.getAllByText('1').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('3')).toBeDefined()
  })

  it('every card is a real button named after what it filters', () => {
    render(<StatsPanel stats={STATS} visible={true} onStatClick={() => {}} activeFilter="" />)
    const warning = card('Warning')
    expect(warning.tagName).toBe('BUTTON')
    expect(warning).toHaveAccessibleName('Filter Warning certificates')
    expect(warning).toHaveAttribute('aria-pressed', 'false')
  })

  it('calls onStatClick with "warning" key when Warning card clicked', () => {
    const onStatClick = vi.fn()
    render(<StatsPanel stats={STATS} visible={true} onStatClick={onStatClick} activeFilter="" />)
    fireEvent.click(card('Warning'))
    expect(onStatClick).toHaveBeenCalledWith('warning')
  })

  it('calls onStatClick with "total" key when Total card clicked', () => {
    const onStatClick = vi.fn()
    render(<StatsPanel stats={STATS} visible={true} onStatClick={onStatClick} activeFilter="" />)
    fireEvent.click(card('Total'))
    expect(onStatClick).toHaveBeenCalledWith('total')
  })

  it('displays zero values as 0', () => {
    const zeroStats = {
      total_certificates: 0,
      valid_count: 0,
      warning_count: 0,
      error_count: 0,
      expiring_in_30_days: 0,
      expired: 0,
    }
    render(<StatsPanel stats={zeroStats} visible={true} onStatClick={() => {}} activeFilter="" />)
    const zeros = screen.getAllByText('0')
    expect(zeros.length).toBeGreaterThanOrEqual(6)
  })

  it('renders nothing when visible=false', () => {
    render(<StatsPanel stats={STATS} visible={false} onStatClick={() => {}} activeFilter="" />)
    expect(screen.queryByText('Total')).toBeNull()
  })

  it('marks the active filter card as pressed and offers to clear it', () => {
    render(<StatsPanel stats={STATS} visible={true} onStatClick={() => {}} activeFilter="valid" />)
    const valid = card('Valid')
    expect(valid).toHaveAttribute('aria-pressed', 'true')
    expect(valid).toHaveAccessibleName('Clear filter')
    expect(card('Total')).toHaveAttribute('aria-pressed', 'false')
  })

  it('CA diversity card opens the window instead of filtering and is not a toggle', () => {
    const onStatClick = vi.fn()
    const onCaClick = vi.fn()
    render(<StatsPanel stats={STATS} visible={true} onStatClick={onStatClick} activeFilter="" onCaClick={onCaClick}
      issuerStats={{ uniqueCount: 1, dominantIssuer: 'Example CA', dominantCount: 10, dominantPct: 100 }} />)
    const ca = card('CA Diversity')
    expect(ca).not.toHaveAttribute('aria-pressed')
    expect(ca).toHaveAttribute('data-tone', 'critical')           // tek CA = yoğunlaşma riski
    expect(screen.getByText('Example CA (10, %100)')).toBeDefined()
    fireEvent.click(ca)
    expect(onCaClick).toHaveBeenCalledTimes(1)
    expect(onStatClick).not.toHaveBeenCalled()
  })

  it('weak-algorithm and certificate-issue cards show their breakdown line and filter by their own key', () => {
    const onStatClick = vi.fn()
    render(<StatsPanel stats={STATS} visible={true} onStatClick={onStatClick} activeFilter=""
      weakStats={{ total: 4, critical: 1, high: 3 }}
      certIssueStats={{ total: 2, revoked: 1, chain: 1, trust: 0, deployment: 0 }} />)
    expect(screen.getByText('1 CRITICAL · 3 HIGH')).toBeDefined()
    expect(card('Weak Algorithm')).toHaveAttribute('data-tone', 'weak')
    fireEvent.click(card('Weak Algorithm'))
    expect(onStatClick).toHaveBeenLastCalledWith('weak')
    fireEvent.click(card('Certificate Issues'))
    expect(onStatClick).toHaveBeenLastCalledWith('certissue')
  })
})
