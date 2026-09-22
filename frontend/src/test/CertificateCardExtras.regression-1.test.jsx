import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import CertificateCard from '../components/CertificateCard.jsx'

vi.mock('../contexts/TeamDirectoryProvider.jsx', () => ({ useTeamDirectory: () => ({ byId: {}, open: () => {} }) }))

// Regression: ISSUE-004 — EN arayuzde erisilebilirlik yuzdesi Turkce konumda ("%100 availability") yaziliyordu;
// yuzde isareti dile bagli olmali (TR: %91.7, EN: 91.7%). Ayni ekrandaki HTTP kartlari zaten "100% available" diyordu.
// Found by /qa on 2026-09-21
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-21-full.md
const CERT = { domain: 'a.example.com', days_remaining: 20, status: 'valid', not_before: '2026-08-01T00:00:00', not_after: '2026-10-09T00:00:00', issuer: 'CA' }
const EXTRA = { uptime: { pct24: 91.7, checks24: 24, last_status: 'up', last_ms: 12, last_at: '2026-09-19T11:00:00', points: [100, 100] } }

describe('CertificateCard erişilebilirlik yüzdesi — işaret dile bağlı (ISSUE-004)', () => {
  afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

  it('EN: yüzde sonda (91.7%)', () => {
    localStorage.setItem('site-monitor-lang', 'en')
    render(<CertificateCard cert={CERT} onClick={() => {}} extra={EXTRA} />)
    expect(screen.getByText('91.7%')).toBeInTheDocument()
    expect(screen.queryByText('%91.7')).toBeNull()
  })

  it('TR: yüzde önde (%91.7)', () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    render(<CertificateCard cert={CERT} onClick={() => {}} extra={EXTRA} />)
    expect(screen.getByText('%91.7')).toBeInTheDocument()
    expect(screen.queryByText('91.7%')).toBeNull()
  })
})
