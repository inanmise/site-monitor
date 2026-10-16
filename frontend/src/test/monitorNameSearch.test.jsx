import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getPingMonitors: vi.fn(),
      getPortMonitors: vi.fn(),
      getDnsMonitors: vi.fn(),
      getKeywordMonitors: vi.fn(),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))
import { api } from '../api/client'

// 2026-10-16 (kullanıcı raporu): Ping / Port / DNS / Keyword sayfalarında arama kutusu kartta görünen izleme ADINI
// bulmuyordu (yalnız host / domain / URL aranıyordu). Ad artık aranır; Türkçe harfler katlanır ("internet" →
// "İnternet Şubesi"). Kartlar ada değil HEDEFE göre bulunur — ad aramada eşleşen alandır.
const base = { team_id: 5, team_name: 'SY-A', active: true, checked_at: '2026-10-15T09:00:00', group_name: '', tags: '' }
const CASES = [
  { label: 'Ping', Page: PingMonitorPage, fetch: 'getPingMonitors', target: 'host',
    rows: [{ ...base, id: 1, name: 'İnternet Şubesi Ağ Geçidi', host: '10.0.0.1', status: 'up', rtt_ms: 3 },
           { ...base, id: 2, name: 'Diğer sunucu', host: '10.0.0.2', status: 'up', rtt_ms: 4 }] },
  { label: 'Port', Page: PortMonitorPage, fetch: 'getPortMonitors', target: 'host',
    rows: [{ ...base, id: 1, name: 'İnternet Şubesi Ağ Geçidi', host: '10.0.0.1', port: 443, protocol: 'TCP', status: 'open', standalone: true },
           { ...base, id: 2, name: 'Diğer sunucu', host: '10.0.0.2', port: 443, protocol: 'TCP', status: 'open', standalone: true }] },
  { label: 'DNS', Page: DnsMonitorPage, fetch: 'getDnsMonitors', target: 'domain',
    rows: [{ ...base, id: 1, name: 'İnternet Şubesi Ağ Geçidi', domain: 'a.example.com', record_type: 'A', standalone: true },
           { ...base, id: 2, name: 'Diğer sunucu', domain: 'b.example.com', record_type: 'A', standalone: true }] },
  { label: 'Keyword', Page: KeywordMonitorPage, fetch: 'getKeywordMonitors', target: 'url',
    rows: [{ ...base, id: 1, name: 'İnternet Şubesi Ağ Geçidi', url: 'https://a.example.com/', keyword: 'tamam', found: true, ok: true },
           { ...base, id: 2, name: 'Diğer sunucu', url: 'https://b.example.com/', keyword: 'tamam', found: true, ok: true }] },
]

const cards = () => [...document.querySelectorAll('[data-monitor-open]')]
const searchBox = () => document.querySelector('[data-page-search]')

describe('izleme sayfası araması — kartta görünen ad da aranır (2026-10-16)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  for (const c of CASES) {
    it(`${c.label}: ada göre arama (Türkçe harf katlamasıyla) yalnız o kartı bırakır; hedefe göre arama sürer`, async () => {
      api.monitoring[c.fetch].mockResolvedValue({ success: true, data: c.rows })
      render(<c.Page systemRole="USER" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(cards()).toHaveLength(2))

      fireEvent.change(searchBox(), { target: { value: 'internet şube' } })
      await waitFor(() => expect(cards()).toHaveLength(1))
      expect(cards()[0].textContent).toContain(c.rows[0][c.target].replace(/^https?:\/\//, '').replace(/\/$/, ''))

      fireEvent.change(searchBox(), { target: { value: 'DİĞER' } })
      await waitFor(() => expect(cards()).toHaveLength(1))
      expect(cards()[0].textContent).toContain(c.rows[1][c.target].replace(/^https?:\/\//, '').replace(/\/$/, ''))

      // Hedefle arama eskisi gibi çalışır; eşleşmeyen arama hiçbir kart bırakmaz
      fireEvent.change(searchBox(), { target: { value: c.rows[0][c.target].replace(/^https?:\/\//, '').slice(0, 6) } })
      await waitFor(() => expect(cards().length).toBeGreaterThanOrEqual(1))
      fireEvent.change(searchBox(), { target: { value: 'eslesmeyen-xyz' } })
      await waitFor(() => expect(cards()).toHaveLength(0))
    })
  }
})
