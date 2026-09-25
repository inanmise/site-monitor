import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import TeamBadge from '../components/ui/TeamBadge.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    teams: { directory: vi.fn(), members: vi.fn() },
  }),
}))

import { api } from '../api/client'

// Regression: ISSUE-001 — TeamBadge rendered a <button> inside the Monitor Changes row <button>
// (invalid HTML, React validateDOMNesting console error, click reached both handlers).
// Found by /qa on 2026-09-10
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-10-full.md

const MEMBERS = {
  success: true,
  data: {
    team: { id: 5, name: 'Payments', email: 'payments@example.com', leader_id: 7, leader_display_name: 'Lider Kişi' },
    members: [{ id: 7, username: 'lead', display_name: 'Lider Kişi', org_role: 'PO', title: 'Yönetici', email: 'lead@example.com' }],
    escalation_contacts: [],
  },
}

describe('TeamBadge as="span" — bir <button> içinde geçerli ve tıklanabilir kalır', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Payments', leader_id: 7 }] })
    api.teams.members.mockResolvedValue(MEMBERS)
  })

  it('as="span": <button> içinde başka <button> ÜRETMEZ; span role=button tabIndex=0 çizer', () => {
    const { container } = render(
      <button type="button" className="chg-row-head" data-testid="row">
        <span>Ödeme akışı</span> · <TeamBadge teamId={5} teamName="Payments" as="span" />
      </button>
    )
    expect(container.querySelectorAll('button button')).toHaveLength(0)
    const badge = container.querySelector('.chg-row-head [data-slot="team-badge"]')
    expect(badge.tagName).toBe('SPAN')
    expect(badge).toHaveAttribute('role', 'button')
    expect(badge).toHaveAttribute('tabindex', '0')
  })

  it('span rozet tıklanınca üye modalı açılır ve tıklama DIŞ düğmeye sızmaz', async () => {
    const outer = vi.fn()
    const { container } = render(
      <button type="button" className="chg-row-head" onClick={outer}>
        Ödeme akışı · <TeamBadge teamId={5} teamName="Payments" as="span" />
      </button>
    )
    fireEvent.click(container.querySelector('[data-slot="team-badge"]'))
    await waitFor(() => expect(api.teams.members).toHaveBeenCalledWith(5))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(outer).not.toHaveBeenCalled()
  })

  it('klavye: Enter ve Space da modalı açar (span düğme klavye sözleşmesi)', async () => {
    const { container } = render(
      <button type="button" className="chg-row-head">
        x · <TeamBadge teamId={5} teamName="Payments" as="span" />
      </button>
    )
    fireEvent.keyDown(container.querySelector('[data-slot="team-badge"]'), { key: 'Enter' })
    await waitFor(() => expect(api.teams.members).toHaveBeenCalledWith(5))
  })

  it('varsayılan mod değişmedi: <button> çizer', () => {
    render(<TeamBadge teamId={5} teamName="Payments" />)
    expect(screen.getByRole('button', { name: /Payments/ }).tagName).toBe('BUTTON')
  })
})
