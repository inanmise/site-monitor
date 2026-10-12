import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'

vi.mock('../api/client', () => ({
  api: { oncall: { current: vi.fn() } },
  formatDate: (v) => String(v),
}))

import { api } from '../api/client'
import AlertOnCall from '../components/admin/alerts/AlertOnCall.jsx'

const Fact = ({ label, children }) => <div data-label={label}>{children}</div>

/**
 * 2026-10-12 inceleme bulgusu: alarm ayrıntısındaki "kimi aramalı" nöbetçisi envanterin SY/UG takımlarından da soruluyordu;
 * bağımsız bir alarmda (başka takımın izlemesi, host bizim envanterimizde) yanlış takımın nöbetçisi görünürdü. Artık sunucunun
 * hesapladığı sahip takımlar (`owner_team_ids`) kullanılır; alan yoksa eski davranış.
 */
describe('AlertOnCall — sahip takımlar', () => {
  beforeEach(() => {
    api.oncall.current.mockReset()
    api.oncall.current.mockResolvedValue({ success: true, data: [{ team_id: 2, user_id: 'u-1', name: 'Nöbetçi B', until: '2026-10-13T06:00:00' }] })
  })

  it('bağımsız alarm: yalnız sahip (damgalı) takımın nöbetçisi sorulur', async () => {
    render(<AlertOnCall alert={{ id: 1, resolved: false, team_id: 2, sy_team_id: 5, ug_team_id: 6, owner_team_ids: [2] }} Fact={Fact} />)
    await waitFor(() => expect(api.oncall.current).toHaveBeenCalledWith(['2']))
  })

  it('sunucu alanı yoksa damga + SY + UG (eski yanıt)', async () => {
    render(<AlertOnCall alert={{ id: 1, resolved: false, team_id: 2, sy_team_id: 5, ug_team_id: 6 }} Fact={Fact} />)
    await waitFor(() => expect(api.oncall.current).toHaveBeenCalledWith(['2', '5', '6']))
  })
})
