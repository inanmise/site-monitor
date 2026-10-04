import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
/** Kap genişliği kancası — testte anahtarla değiştirilir (jsdom yerleşim ölçmez; 0 = tablo). */
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, width.value] }))
vi.mock('../api/client', () => ({
  api: withApiFallback({ me: { getPushHistory: vi.fn() } }),
}))
import { api } from '../api/client'
import PushHistorySection from '../components/myactivity/PushHistorySection.jsx'

/**
 * "Push bildirimlerim" (2026-10-04, onaylı öneri 3): KPI çipleri + en sık nedenler, süzgeç / dönem sunucuya gider,
 * güne göre gruplu liste (geniş kap tablo, dar kap kart), durum rozeti + insan diliyle neden, takım kararı ve başka takım
 * etiketleri, özet satırı, kodla giriş dipnotu, boş / hata durumları.
 */
const now = new Date()
const iso = (msAgo) => new Date(now.getTime() - msAgo).toISOString().slice(0, 19)
const ROWS = [
  { id: 5, at: iso(60_000), trigger: 'OPEN', status: 'SENT', kind: 'sent', reason: null, own: true, team_decision: false, visible: true,
    scope: 'own', alert_level: 'CRITICAL', monitor_type: 'http', lang: 'en', target: 'shop.example.com', team_name: 'Takım A',
    message: 'CRITICAL: shop.example.com is not responding.' },
  { id: 4, at: iso(120_000), trigger: 'OPEN', status: 'SKIPPED_USER_SNOOZE', kind: 'not_sent', reason: 'SKIPPED_USER_SNOOZE', own: true,
    team_decision: false, visible: true, scope: 'own', alert_level: 'HIGH', monitor_type: 'dns', lang: 'tr', target: 'dns.example.com',
    message: 'YÜKSEK: dns.example.com' },
  { id: 3, at: iso(180_000), trigger: 'OPEN', status: 'RATE_LIMITED', kind: 'not_sent', reason: 'RATE_LIMITED', own: true,
    team_decision: false, visible: true, scope: 'own', alert_level: 'WARNING', monitor_type: 'ping', lang: 'tr', target: 'gw.example.com',
    summarized_into: 9, message: 'UYARI: gw' },
  { id: 2, at: iso(240_000), trigger: 'OPEN', status: 'SKIPPED_STORM', kind: 'not_sent', reason: 'SKIPPED_STORM', own: false,
    team_decision: true, visible: true, scope: 'team', alert_level: 'HIGH', monitor_type: 'http', target: 'api.example.com', message: null },
  { id: 1, at: iso(300_000), trigger: 'OPEN', status: 'SENT', kind: 'sent', reason: null, own: true, team_decision: false,
    visible: false, scope: 'other_team', alert_level: 'HIGH', monitor_type: 'http', message: null, message_hidden: true },
  { id: 9, at: iso(30_000), trigger: 'OVERFLOW_SUMMARY', status: 'SENT', kind: 'sent', own: true, team_decision: false, visible: true,
    scope: 'own', alert_level: 'WARNING', lang: 'tr', target: 'Saat tavanı özeti · 1 bildirim', summarized_count: 1,
    message: 'SiteMonitor: saat tavanı nedeniyle 1 bildirim gönderilmedi (1 uyarı).' },
]
const RESP = {
  success: true, data: ROWS, total: ROWS.length, page: 0, size: 25, days: 7, filter: 'all', login_code_note: true,
  kpis: { sent: 3, pending: 0, not_sent: 3, not_sent_by_reason: { SKIPPED_USER_SNOOZE: 1, RATE_LIMITED: 1 }, summarized: 1,
    team_decisions: 1, team_decisions_by_reason: { SKIPPED_STORM: 1 } },
}
const section = () => within(document.querySelector('[data-slot="push-history"]'))

describe('PushHistorySection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    width.value = 0
    api.me.getPushHistory.mockResolvedValue(RESP)
  })

  it('geniş kap: tablo; KPI çipleri ve en sık gelmeme nedenleri; her satırda durum rozeti + neden', async () => {
    render(<PushHistorySection />)
    const table = await screen.findByTestId('ph-table')
    expect(screen.queryByTestId('ph-cards')).toBeNull()
    const kpis = document.querySelector('[data-slot="ph-kpis"]')
    expect(kpis.textContent).toContain('Received3')
    expect(kpis.textContent).toContain('Not received3')
    expect(kpis.textContent).toContain('Summarised1')
    expect(kpis.textContent).toContain('Team decisions1')
    expect(document.querySelector('[data-reason="SKIPPED_USER_SNOOZE"]').textContent).toContain('Push was snoozed')

    const rows = table.querySelectorAll('[data-slot="ph-row"]')
    expect(rows).toHaveLength(6)
    const byStatus = (s) => [...rows].filter((r) => r.getAttribute('data-status') === s)
    expect(byStatus('sent')).toHaveLength(3)
    expect(byStatus('summarized')).toHaveLength(1)
    expect(byStatus('not_sent')).toHaveLength(2)
    expect(within(table).getByText('Push was snoozed')).toBeInTheDocument()
    expect(within(table).getByText('Included in an hourly-limit summary')).toBeInTheDocument()
    // Gerçekten gönderilen metin + İngilizce rozeti
    expect(within(table).getByText('CRITICAL: shop.example.com is not responding.')).toBeInTheDocument()
    expect(within(table).getByLabelText('Sent in English')).toBeInTheDocument()
  })

  it('takım kararı "Your team\'s alert — no push was sent: …"; başka takımın alarmı hedefsiz + metin gizli; özet satırı', async () => {
    render(<PushHistorySection />)
    await screen.findByTestId('ph-table')
    expect(section().getByText("Your team's alert — no push was sent: Handed over to the alert storm")).toBeInTheDocument()
    expect(section().getByText("Another team's alert")).toBeInTheDocument()
    expect(section().getByText('Text hidden — you can no longer see this alert.')).toBeInTheDocument()
    expect(section().getByText('Hourly-limit summary')).toBeInTheDocument()
    expect(section().getByText('Summarises 1 notifications')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="ph-login-note"]').textContent).toContain('Sign-in code pushes are never stored')
  })

  it('süzgeç ve dönem sunucuya gider (sayfa 1)', async () => {
    render(<PushHistorySection />)
    await screen.findByTestId('ph-table')
    expect(api.me.getPushHistory).toHaveBeenLastCalledWith({ days: 7, filter: 'all', page: 0, size: expect.any(Number) })
    fireEvent.click(section().getByRole('button', { name: 'Not received' }))
    await waitFor(() => expect(api.me.getPushHistory).toHaveBeenLastCalledWith(expect.objectContaining({ filter: 'not_sent', page: 0 })))
    fireEvent.click(section().getByRole('button', { name: '30 days' }))
    await waitFor(() => expect(api.me.getPushHistory).toHaveBeenLastCalledWith(expect.objectContaining({ days: 30, filter: 'not_sent' })))
  })

  it('dar kap (telefon): kart listesi, gün başlıkları, kartta neden satırı', async () => {
    width.value = 380
    render(<PushHistorySection />)
    const cards = await screen.findByTestId('ph-cards')
    expect(screen.queryByTestId('ph-table')).toBeNull()
    expect(cards.querySelectorAll('[data-slot="ph-row"]')).toHaveLength(6)
    expect(cards.querySelectorAll('[data-slot="ph-day"]').length).toBeGreaterThanOrEqual(1)
    expect(within(cards).getByText('Push was snoozed')).toBeInTheDocument()
  })

  it('boş dönem ve süzgeçli boş durum ayrı metinle; hata → uyarı + yeniden dene', async () => {
    api.me.getPushHistory.mockResolvedValueOnce({ ...RESP, data: [], total: 0, kpis: { sent: 0, not_sent: 0 } })
    render(<PushHistorySection />)
    expect(await screen.findByText('No pushes in this period')).toBeInTheDocument()

    api.me.getPushHistory.mockResolvedValueOnce({ success: false })
    fireEvent.click(section().getByRole('button', { name: 'Received' }))
    expect(await screen.findByText("Couldn't load your push history")).toBeInTheDocument()
    api.me.getPushHistory.mockResolvedValueOnce({ ...RESP, data: [], total: 0 })
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No pushes match this filter.')).toBeInTheDocument()
  })
})
