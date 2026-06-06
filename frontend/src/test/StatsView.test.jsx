import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'
import StatsView from '../components/StatsView.jsx'

/**
 * Smoke-level render tests for StatsView. The component aggregates cert
 * statistics by tier/team and is purely props-driven, so we exercise it
 * with realistic inputs.
 */
describe('StatsView', () => {
  it('renders without crashing on empty input', () => {
    render(<StatsView certs={[]} teamStats={null} onRowClick={() => {}} />)
    // No explicit empty-state text guaranteed; smoke test: a section/header is in the DOM.
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('renders without crashing when only certs are provided (no team stats)', () => {
    const certs = [
      { domain: 'a.example.com', tier: 1, alert_level: 'valid', days_remaining: 100 },
      { domain: 'b.example.com', tier: 2, alert_level: 'warning', days_remaining: 25 },
      { domain: 'c.example.com', tier: 1, alert_level: 'critical', days_remaining: 3 },
    ]
    render(<StatsView certs={certs} teamStats={null} onRowClick={() => {}} />)
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('does not throw with a mix of states including expired and error', () => {
    const certs = [
      { domain: 'ok.example.com',      tier: 1, alert_level: 'valid',    days_remaining: 100 },
      { domain: 'expired.example.com', tier: 3, alert_level: 'expired',  days_remaining: -5  },
      { domain: 'broken.example.com',  tier: 4, alert_level: 'error',    status: 'error', error: 'Connection reset' },
    ]
    const onRowClick = vi.fn()
    render(<StatsView certs={certs} teamStats={null} onRowClick={onRowClick} />)
    expect(document.body).toBeDefined()
  })
})
