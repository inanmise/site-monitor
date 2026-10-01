import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'
import { AlertRowsList } from '../components/admin/alerts/AlertLists.jsx'

/**
 * Alarm satırlarının Takım sütunu (performans, 2026-10-01): sunucunun `team_name`'i varsa çağıranın id → ad dizinine
 * HİÇ bakılmaz (eskiden her satır için `teams.find` koşardı); yalnız eksik ad dizinden tamamlanır — görünen ad aynı.
 */
const base = {
  resolved: true, resolved_by: 'system', resolved_at: '2026-06-07T10:00:00', created_at: '2026-06-01T08:00:00',
  alert_level: 'HIGH', alert_type: 'HTTP_DOWN',
}

describe('AlertRowsList — takım adı', () => {
  it('team_name varsa teamNameOf çağrılmaz; yoksa team_id ile dizinden doldurulur', () => {
    const teamNameOf = vi.fn((id) => (String(id) === '7' ? 'Dizin-Yedi' : null))
    const groups = [{ key: 'g', kind: 'none', items: [
      { ...base, id: 1, domain: 'a.example.com', team_id: 5, team_name: 'Sunucu-Ops' },
      { ...base, id: 2, domain: 'b.example.com', team_id: 7 },
    ] }]
    const { container } = render(
      <AlertRowsList groups={groups} tab="closed" nowMs={Date.parse('2026-06-08T00:00:00Z')} onOpen={() => {}}
        menuItems={() => []} teamNameOf={teamNameOf} />,
    )
    expect(teamNameOf).toHaveBeenCalled()
    expect(teamNameOf.mock.calls.every(([id]) => String(id) === '7')).toBe(true)
    expect(container.textContent).toContain('Sunucu-Ops')
    expect(container.textContent).toContain('Dizin-Yedi')
  })
})
