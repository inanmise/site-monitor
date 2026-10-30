import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Alarm akışı, dar kapta (telefon; kap < 760 px): çağıranlar, önizleme ve çağrı günlüğü KART düzeninde — tablo yok;
// uzun AD takım adı, IPv6 ve user agent tam (sarar); karttaki "Ayrıntıyı aç" paneli açar; önizleme kartı BODY ile açılır.
// Genişlik kancası 400 px'e sabitlenir (K6EngineSettings.mobile ile aynı desen).
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ alarmFeed: { get: vi.fn(), preview: vi.fn(), requests: vi.fn(), request: vi.fn() } }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import AlarmFeedSettings from '../components/admin/alarmfeed/AlarmFeedSettings.jsx'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const IPV6 = '2001:0db8:85a3:0000:0000:8a2e:0370:7334'
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 CentralMonitor/4.2'
const SETTINGS = {
  enabled: true, min_level: 'HIGH', team_ids: [], types: [], resolved_minutes: 15, resolved_mode: 'status', clear_text: 'Clear',
  severity_critical: 'Critical', severity_high: 'Major', severity_warning: 'Warning', rci_mode: 'target', source: 'SiteMonitor',
  rcitype: '', mail_separator: 'comma', allowed_ips: [IPV6], rate_per_minute: 30, max_rows: 2000, store_body: false,
}
const ROW = {
  CREATEDTIME: '2026-10-30 11:02:00', SEVERITY: 'Major', RCI: 'odeme-api.internal.example.com', RCITYPE: 'HTTP', CATEGORY: 'HTTP',
  FILTERSOURCE: 'SiteMonitor', TEXT: 'HTTP 503 döndü', BODY: 'Ayrıntı 1\nAyrıntı 2', MAILS: 'ekip@example.com', STATUS: 'RESOLVED',
  RESOLVEDTIME: '2026-10-30 11:10:00', ALERTID: 77, TEAM: LONG_TEAM, UGTEAM: null, LINK: 'https://sm.example.com/?alert=77',
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.alarmFeed.get.mockResolvedValue({ success: true, data: {
    endpoint: '/api/public/alarm-feed', base_url: 'https://sitemonitor.example.com', settings: SETTINGS, log_retention_days: 30,
    teams: [{ id: 3, name: LONG_TEAM }], types: [{ key: 'http', label: 'HTTP' }],
    status: { last_call_at: new Date().toISOString().slice(0, 19), last_call_ip: IPV6, last_outcome: 'OK', calls_24h: 5, ok_24h: 5,
      refused_24h: 0, errors_24h: 0, distinct_ips_24h: 1, avg_duration_ms_24h: 1500, last_row_count: 1, log_write_failures: 0, served_by: 'pod-a' },
    callers: [{ ip: IPV6, first_seen_at: '2026-10-29T08:00:00', last_seen_at: '2026-10-30T08:00:00', call_count: 5, last_user_agent: UA, last_outcome: 'OK' }],
  } })
  api.alarmFeed.requests.mockResolvedValue({ success: true, data: { items: [
    { id: 5, requested_at: '2026-10-30T08:00:00', client_ip: IPV6, remote_addr: '10.0.0.5', forwarded_for: IPV6, user_agent: UA, method: 'GET',
      query: 'format=csv', status: 200, outcome: 'OK', row_count: 1, open_count: 0, resolved_count: 1, duration_ms: 1500, response_bytes: 900,
      response_hash: 'sha256:cd', served_by: 'pod-a', request_id: 'req-5', format: 'csv', error: null },
  ], total: 1, page: 0, size: 25 } })
  api.alarmFeed.request.mockResolvedValue({ success: true, data: { id: 5, requested_at: '2026-10-30T08:00:00', client_ip: IPV6, outcome: 'OK',
    status: 200, user_agent: UA, forwarded_for: IPV6, body: 'CREATEDTIME,SEVERITY\n2026-10-30 11:02:00,Major', body_available: true,
    body_content_type: 'text/csv' } })
  api.alarmFeed.preview.mockResolvedValue({ success: true, data: { columns: Object.keys(ROW), rows: [ROW], count: 1, open: 0, resolved: 1, truncated: true } })
})

describe('AlarmFeedSettings — dar kap (kartlar)', () => {
  it('çağıranlar, günlük ve önizleme kart düzeninde; uzun değerler tam; ayrıntı paneli CSV gövdeyi olduğu gibi gösterir', async () => {
    render(<AlarmFeedSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="af-root"]')).toHaveAttribute('data-state', 'ok'))
    expect(document.querySelector('[data-slot="af-callers-list"]')).toHaveAttribute('data-layout', 'cards')
    expect(document.querySelector('[data-slot="af-callers-table"]')).toBeNull()
    const caller = document.querySelector('[data-slot="af-caller"]')
    expect(within(caller).getByText(IPV6)).toBeInTheDocument()
    expect(within(caller).getByText(UA).className).toContain('[overflow-wrap:anywhere]')

    await waitFor(() => expect(document.querySelector('[data-slot="af-req-list"]')).toHaveAttribute('data-layout', 'cards'))
    expect(document.querySelector('[data-slot="af-req-table"]')).toBeNull()
    const card = document.querySelector('[data-slot="af-req-row"]')
    expect(within(card).getByText(IPV6)).toBeInTheDocument()
    expect(within(card).getByText('Başarılı')).toBeInTheDocument()
    expect(within(card).getByText('1,5 sn')).toBeInTheDocument()
    fireEvent.click(within(card).getByRole('button', { name: /Çağrı ayrıntısı/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="af-req-body-pre"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="af-req-body"]')).toHaveAttribute('data-pretty', 'false')
    expect(document.querySelector('[data-slot="af-req-body-pre"]').textContent).toBe('CREATEDTIME,SEVERITY\n2026-10-30 11:02:00,Major')
    fireEvent.click(document.querySelector('[data-slot="af-req-detail-close"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="af-req-detail"]')).toBeNull())

    fireEvent.click(document.querySelector('[data-slot="af-preview-run"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="af-pv-list"]')).toHaveAttribute('data-layout', 'cards'))
    expect(document.querySelector('[data-slot="af-pv-truncated"]')).toHaveTextContent('kırpıldı')
    const pv = document.querySelector('[data-slot="af-pv-row"]')
    expect(within(pv).getByText('Çözüldü')).toBeInTheDocument()
    fireEvent.click(within(pv).getByRole('button', { name: /Satırın ayrıntısını göster/ }))
    await waitFor(() => expect(within(pv).getByText('Ayrıntı 1\nAyrıntı 2', { normalizer: (s) => s })).toBeInTheDocument())
    // Kapalı gövde saklama + boş olmayan IP listesi: açık uç uyarısı bilgi tonunda (liste dolu)
    expect(screen.getByText('Bu uç herkese açıktır').closest('[data-tone]')).toHaveAttribute('data-tone', 'info')
  })
})
