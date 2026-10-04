import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { storm: { detail: vi.fn() } } }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
import { api } from '../api/client'
import StormDetailModal from '../components/storm/StormDetailModal.jsx'
import { EN } from '../i18n/en.js'

/**
 * Fırtına ayrıntısı — toplu fırtına push'ları (2026-10-04, ek alan): her push için tetik, kişi / iletilen sayısı ve
 * KAPSADIĞI alarm sayısı; kayıt öncesi push "tahmini"; kanal kararı nedeni. Alan yoksa (eski sunucu) liste çizilmez.
 */
const fill = (key, ...args) => String(EN[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
const DETAIL = (pushes) => ({ success: true, data: {
  id: 12, team_id: 1, team_name: 'Takım A', created_at: '2026-10-04T08:00:00', resolved: false, members: [], members_total: 0,
  notifications: { initial: 1, realert: 0, resolve: 0, suppressed: 3, push: 4, push_members: 0, ...(pushes ? { storm_pushes: pushes } : {}) },
} })

describe('StormDetailModal — fırtına push\'ları', () => {
  beforeEach(() => vi.clearAllMocks())

  it('push başına kapsanan alarm sayısı + kişi/iletilen; tahmini ve kanal kararı', async () => {
    api.monitoring.storm.detail.mockResolvedValue(DETAIL([
      { push_key: 'storm:12:INITIAL', trigger: 'INITIAL', team_id: 1, first_created_at: '2026-10-04T08:00:05', first_sent_at: '2026-10-04T08:00:07',
        recipients: 4, sent: 3, covered_alarms: 8, inferred: false, decision: null },
      { push_key: 'storm:12:DAILY_REALERT:2026-10-05', trigger: 'DAILY_REALERT', day: '2026-10-05', team_id: 1, first_created_at: '2026-10-05T08:00:00',
        recipients: 0, sent: 0, covered_alarms: 5, inferred: true, decision: 'SKIPPED_TEAM_OFF' },
    ]))
    render(<StormDetailModal stormId={12} onClose={() => {}} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="sf-storm-push"]')).toHaveLength(2))
    const [first, second] = document.querySelectorAll('[data-slot="sf-storm-push"]')
    expect(first.querySelector('[data-slot="sf-push-covered"]').textContent).toBe(fill('sf.detail.push.covered', 8))
    expect(first.textContent).toContain(fill('sf.detail.push.people', 4, 3))
    expect(first.textContent).toContain(EN['alh.sp.trigger.INITIAL'])
    expect(second.getAttribute('data-trigger')).toBe('DAILY_REALERT')
    expect(second.textContent).toContain('2026-10-05')
    expect(second.textContent).toContain(EN['alh.sp.inferred'])
    expect(second.querySelector('[data-slot="sf-push-covered"]').textContent).toBe(fill('sf.detail.push.covered', 5))
    expect(second.textContent).toContain(EN['push.reason.SKIPPED_TEAM_OFF'] ?? 'SKIPPED_TEAM_OFF')
  })

  it('eski sunucu (storm_pushes yok): liste çizilmez, mevcut özet aynen', async () => {
    api.monitoring.storm.detail.mockResolvedValue(DETAIL(null))
    render(<StormDetailModal stormId={12} onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="sf-notifications"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="sf-storm-pushes"]')).toBeNull()
  })
})
