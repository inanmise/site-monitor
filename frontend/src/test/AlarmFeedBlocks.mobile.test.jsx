import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, within } from './test-utils.jsx'

// Engellenen IP'ler, dar kapta (telefon; kap < 760 px): KART düzeni — tablo yok; uzun IPv6 ve user agent tam (sarar);
// "Engeli kaldır" yalnız etkin kartta. Genişlik kancası 400 px'e sabitlenir.
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ alarmFeed: { get: vi.fn(), requests: vi.fn(), blocks: vi.fn(), unblock: vi.fn() } }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import AlarmFeedSettings from '../components/admin/alarmfeed/AlarmFeedSettings.jsx'

const IPV6 = '2001:0db8:85a3:0000:0000:8a2e:0370:7334'
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Poller/4.2'
const isoAt = (ms) => new Date(ms).toISOString().slice(0, 19)
const NOW = Date.now()

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.alarmFeed.get.mockResolvedValue({ success: true, data: {
    endpoint: '/api/public/alarm-feed', base_url: null, log_retention_days: 90, teams: [], types: [], callers: [], status: {},
    settings: { enabled: true, min_level: 'WARNING', team_ids: [], types: [], resolved_minutes: 15, resolved_mode: 'status',
      clear_text: 'Clear', severity_critical: 'Critical', severity_high: 'Major', severity_warning: 'Warning', rci_mode: 'target_type',
      source: 'SiteMonitor', rcitype: '', mail_separator: 'semicolon', allowed_ips: [], rate_per_minute: 30, max_rows: 2000,
      store_body: true, block_enabled: true, block_threshold: 120, block_minutes: 15, block_escalate: true },
    blocks: [
      { ip: IPV6, blocked_at: isoAt(NOW - 60_000), blocked_until: isoAt(NOW + 74 * 60_000), state: 'active', minutes: 60, hits: 900,
        threshold: 120, block_count: 3, last_user_agent: UA, local_only: false },
      { ip: '10.1.1.1', blocked_at: isoAt(NOW - 7200_000), blocked_until: isoAt(NOW - 6300_000), state: 'expired', minutes: 15, hits: 130,
        threshold: 120, block_count: 1, last_user_agent: null, local_only: false },
    ],
    active_blocks: 1,
  } })
  api.alarmFeed.requests.mockResolvedValue({ success: true, data: { items: [], total: 0, page: 0, size: 25 } })
})

describe('AlarmFeedBlocks — dar kap', () => {
  it('kartlar: tablo yok, IPv6 + UA tam, kalan süre saat + dk, kaldırma yalnız etkin kartta', async () => {
    render(<AlarmFeedSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="af-blocks-list"]')).toHaveAttribute('data-layout', 'cards'))
    expect(document.querySelector('[data-slot="af-blocks-table"]')).toBeNull()
    const cards = [...document.querySelectorAll('[data-slot="af-block"]')]
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveTextContent(IPV6)
    expect(cards[0]).toHaveTextContent(UA)
    expect(cards[0]).toHaveTextContent('1 sa 14 dk kaldı')
    expect(within(cards[0]).getByRole('button', { name: `Engeli kaldır: ${IPV6}` })).toBeInTheDocument()
    expect(within(cards[1]).queryByRole('button', { name: /Engeli kaldır/ })).toBeNull()
  })
})
