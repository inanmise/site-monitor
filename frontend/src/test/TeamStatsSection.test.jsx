import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import TeamStatsSection from '../components/TeamStatsSection.jsx'

const ADMIN_DATA = {
  mode: 'all_teams',
  teams: [
    { team_id: 1, team_name: 'ZeroTeam',
      sy_t1_stats: { total_certificates: 0 },
      sy_t2_stats: { total_certificates: 0 } },
    { team_id: 2, team_name: 'SmallTeam',
      sy_t1_stats: { total_certificates: 1 },
      sy_t2_stats: { total_certificates: 0 } },
    { team_id: 3, team_name: 'BigTeam',
      sy_t1_stats: { total_certificates: 10 },
      sy_t2_stats: { total_certificates: 4 } },
    { team_id: 4, team_name: 'MidTeam',
      sy_t1_stats: { total_certificates: 3 },
      sy_t2_stats: { total_certificates: 2 } },
  ],
}

describe('TeamStatsSection — AdminView', () => {
  it('hides teams with total_certificates = 0', () => {
    render(<TeamStatsSection data={ADMIN_DATA} visible={true} onStatClick={() => {}} />)
    expect(screen.queryByText('ZeroTeam')).toBeNull()
    expect(screen.getByText('BigTeam')).toBeDefined()
    expect(screen.getByText('MidTeam')).toBeDefined()
    expect(screen.getByText('SmallTeam')).toBeDefined()
  })

  it('renders visible teams in descending total order', () => {
    const { container } = render(
      <TeamStatsSection data={ADMIN_DATA} visible={true} onStatClick={() => {}} />
    )
    const names = Array.from(container.querySelectorAll('.ts-team-name'))
      .map(el => el.textContent)
    // Expected order: Big (14), Mid (5), Small (1)
    expect(names).toEqual(['BigTeam', 'MidTeam', 'SmallTeam'])
  })

  it('section badge shows count of visible (non-zero) teams', () => {
    const { container } = render(
      <TeamStatsSection data={ADMIN_DATA} visible={true} onStatClick={() => {}} />
    )
    const badge = container.querySelector('.ts-section-badge')
    expect(badge?.textContent).toBe('3')   // 4 teams total, 1 hidden
  })

  it('returns null when visible=false', () => {
    const { container } = render(
      <TeamStatsSection data={ADMIN_DATA} visible={false} onStatClick={() => {}} />
    )
    expect(container.firstChild).toBeNull()
  })
})

describe('TeamStatsSection — PersonalView', () => {
  it('renders single team for personal mode (no sort/filter applied)', () => {
    const personal = {
      mode: 'personal',
      team_id: 99,
      team_name: 'MyTeam',
      sy_t1_stats: { total_certificates: 0 },
      sy_t2_stats: { total_certificates: 0 },
    }
    render(<TeamStatsSection data={personal} visible={true} onStatClick={() => {}} />)
    // PersonalView always shows the user's own team, even with zero certs
    expect(screen.getByText('MyTeam')).toBeDefined()
  })
})
