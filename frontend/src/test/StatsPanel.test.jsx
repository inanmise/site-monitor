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

  it('calls onStatClick with "warning" key when Warning card clicked', () => {
    const onStatClick = vi.fn()
    render(<StatsPanel stats={STATS} visible={true} onStatClick={onStatClick} activeFilter="" />)
    const warningEl = screen.getByText('Warning')
    const statItem = warningEl.closest('.stat-item')
    fireEvent.click(statItem)
    expect(onStatClick).toHaveBeenCalledWith('warning')
  })

  it('calls onStatClick with "total" key when Total card clicked', () => {
    const onStatClick = vi.fn()
    render(<StatsPanel stats={STATS} visible={true} onStatClick={onStatClick} activeFilter="" />)
    const totalEl = screen.getByText('Total')
    const statItem = totalEl.closest('.stat-item')
    fireEvent.click(statItem)
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

  it('adds stat-active class to active filter card', () => {
    render(<StatsPanel stats={STATS} visible={true} onStatClick={() => {}} activeFilter="valid" />)
    const validEl = screen.getByText('Valid')
    const statItem = validEl.closest('.stat-item')
    expect(statItem.className).toContain('stat-active')
  })
})
