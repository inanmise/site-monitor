import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, width.value] }))
vi.mock('../api/client', () => ({
  api: withApiFallback({ me: { getPushHistory: vi.fn() } }),
}))
import { api } from '../api/client'
import PushHistorySection from '../components/myactivity/PushHistorySection.jsx'
import { EN } from '../i18n/en.js'

/**
 * "Push bildirimlerim" ↔ fırtına push'u (2026-10-04): (a) takımın alarmı fırtınaya devredildi (SKIPPED_STORM karar satırı)
 * → o alarmı kapsayan fırtına push'unu SİZİN aldığınız an + durum ya da almadığınız neden / "henüz gitmedi"; (b) sizin fırtına
 * push'unuz → kapsadığı alarmlar ("site-a, site-b +3") ve görüş kapsamı dışındakiler yalnız sayı. Tablo ve kart görünümünde.
 */
const now = new Date()
const iso = (msAgo) => new Date(now.getTime() - msAgo).toISOString().slice(0, 19)
const fill = (key, ...args) => String(EN[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))

const DECISION = (id, sp) => ({ id, at: iso(60_000 * id), trigger: 'OPEN', status: 'SKIPPED_STORM', kind: 'not_sent', reason: 'SKIPPED_STORM',
  own: false, team_decision: true, visible: true, scope: 'team', alert_level: 'WARNING', monitor_type: 'http', target: `alarm-${id}.example.com`,
  alert_event_id: 400 + id, storm_push: sp })

const ROWS = [
  DECISION(1, { state: 'received', reason: null, awaiting_storm_ids: [], pushes: [
    { storm_id: 12, trigger: 'INITIAL', push_key: 'storm:12:INITIAL', inferred: false, delivery_id: 77, status: 'SENT', at: '2026-10-04T08:00:07', reason: null },
  ] }),
  DECISION(2, { state: 'not_received', reason: 'NOT_RECIPIENT', awaiting_storm_ids: [], pushes: [
    { storm_id: 12, trigger: 'INITIAL', push_key: 'storm:12:INITIAL', inferred: true, delivery_id: null, status: null, at: '2026-10-04T08:00:05', reason: 'NOT_RECIPIENT' },
  ] }),
  DECISION(3, { state: 'waiting', reason: null, awaiting_storm_ids: [13], pushes: [] }),
  DECISION(4, { state: 'not_received', reason: 'RATE_LIMITED', awaiting_storm_ids: [], pushes: [
    { storm_id: 12, trigger: 'DAILY_REALERT', push_key: 'storm:12:DAILY_REALERT:2026-10-05', inferred: false, delivery_id: 80, status: 'RATE_LIMITED', at: '2026-10-05T08:00:00', reason: 'RATE_LIMITED' },
  ] }),
  { id: 5, at: iso(30_000), trigger: 'STORM', status: 'SENT', kind: 'sent', reason: null, own: true, team_decision: false, visible: true,
    scope: 'own', alert_level: 'WARNING', monitor_type: 'STORM', target: 'Alarm fırtınası', lang: 'en',
    message: '5 monitors unreachable at once — Team A',
    storm_alarms: { total: 7, visible_count: 5, hidden: 2, inferred: false, alarms: [
      { id: 501, target: 'site-a.example.com', alert_type: 'HTTP_DOWN', resolved: false },
      { id: 502, target: 'site-b.example.com', alert_type: 'HTTP_DOWN', resolved: false },
      { id: 503, target: 'site-c.example.com', alert_type: 'HTTP_DOWN', resolved: false },
    ] } },
]
const RESP = { success: true, data: ROWS, total: ROWS.length, page: 0, size: 25, days: 7, filter: 'all', login_code_note: true,
  kpis: { sent: 1, pending: 0, not_sent: 0, not_sent_by_reason: {}, summarized: 0, team_decisions: 4, team_decisions_by_reason: { SKIPPED_STORM: 4 } } }

const rowById = (id) => [...document.querySelectorAll('[data-slot="ph-row"]')]
  .find((r) => r.textContent.includes(`alarm-${id}.example.com`))

describe('PushHistorySection — fırtına push\'u bağı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    width.value = 0
    api.me.getPushHistory.mockResolvedValue(RESP)
  })

  it('devir satırı: aldığınız fırtına push\'u (zaman + durum) / almadığınız neden / henüz gitmedi', async () => {
    render(<PushHistorySection />)
    await screen.findByTestId('ph-table')
    const received = rowById(1).querySelector('[data-slot="ph-storm-push"]')
    expect(received.getAttribute('data-state')).toBe('received')
    expect(received.textContent).toContain('Storm #12 push reached you')
    expect(received.querySelector('[data-slot="ph-storm-push-item"]').getAttribute('data-status')).toBe('SENT')
    expect(received.textContent).toContain(EN['alh.push.status.SENT'])

    const notRecipient = rowById(2).querySelector('[data-slot="ph-storm-push"]')
    expect(notRecipient.getAttribute('data-state')).toBe('not_received')
    expect(notRecipient.textContent).toContain(fill('mypush.hist.sp.notReceived', EN['mypush.hist.sp.reason.NOT_RECIPIENT']))
    expect(notRecipient.textContent).toContain(EN['alh.sp.inferred'])

    const waiting = rowById(3).querySelector('[data-slot="ph-storm-push"]')
    expect(waiting.getAttribute('data-state')).toBe('waiting')
    expect(waiting.textContent).toContain(fill('mypush.hist.sp.waiting', 13))

    const limited = rowById(4).querySelector('[data-slot="ph-storm-push"]')
    expect(limited.textContent).toContain(fill('mypush.hist.sp.notReceived', EN['push.reason.RATE_LIMITED'] ?? 'RATE_LIMITED'))
  })

  it('fırtına push\'unuz: kapsadığı alarmlar ("site-a, site-b +3 more") + başka takımın alarmları yalnız sayı', async () => {
    render(<PushHistorySection />)
    await screen.findByTestId('ph-table')
    const note = document.querySelector('[data-slot="ph-storm-alarms"]')
    expect(note.textContent).toContain(fill('mypush.hist.sa.covered', `site-a.example.com, site-b.example.com ${fill('mypush.hist.sa.more', 3)}`))
    expect(note.querySelector('[data-slot="ph-storm-alarms-hidden"]').textContent).toBe(fill('mypush.hist.sa.hidden', 2))
    expect(note.getAttribute('title')).toContain('site-c.example.com')
  })

  it('dar kap (kart görünümü): aynı bağlar kartlarda da görünür', async () => {
    width.value = 360
    render(<PushHistorySection />)
    await screen.findByTestId('ph-cards')
    await waitFor(() => expect(document.querySelectorAll('[data-slot="ph-storm-push"]')).toHaveLength(4))
    expect(document.querySelector('[data-slot="ph-storm-alarms"]')).toBeTruthy()
  })
})
