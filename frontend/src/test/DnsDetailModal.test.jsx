import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DnsDetailModal from '../components/DnsDetailModal.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: {
    monitoring: {
      getDnsDetails: vi.fn(),
      getDnsHistory: vi.fn(),
      getDnsResponseSeries: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
    admin: {
      // AlertHistory (alerts tab) — bu testte tab açılmıyor ama import zinciri için güvenli stub
      getAlertHistory: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 7, name: 'iyi', domain: 'www.iyigelecegeyatirim.com', record_type: 'A',
  standalone: true, team_id: 5, value: '192.168.10.249',
  expected_value: '192.168.10.249\n217.169.196.197',
}

const details = {
  records: { A: { values: ['192.168.10.249'], ttl: 599, response_ms: 2, success: true } },
  soa: { success: false },
  authoritative_servers: [],
  monitor,
  slow_threshold_ms: 1500,
  resolver_config: {
    servers: ['10.0.0.53', '10.0.0.54'],
    source: 'os',
    timeout_ms: 2000,
    propagation_enabled: false,
    propagation_resolvers: ['8.8.8.8', '1.1.1.1', '9.9.9.9'],
  },
}

const historyRows = [
  {
    id: 1, monitor_id: 7, record_type: 'A', value: '192.168.10.249',
    changed: true, rotated: false, previous_value: '217.169.196.197',
    checked_at: '2026-08-02T01:32:00', ttl: 788, response_ms: 2,
  },
  {
    id: 2, monitor_id: 7, record_type: 'A', value: '217.169.196.197',
    changed: false, rotated: false, previous_value: null,
    checked_at: '2026-08-02T01:27:00', ttl: 287, response_ms: 5,
  },
]

describe('DnsDetailModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: details })
    api.monitoring.getDnsHistory.mockResolvedValue({ success: true, data: historyRows })
  })

  it('filtre butonları render olur; "Sadece Değişenler" changedOnly=true ile yeniden yükler', async () => {
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await waitFor(() => expect(api.monitoring.getDnsHistory).toHaveBeenCalledWith(7, 1, false))

    const changedBtn = await screen.findByRole('button', { name: /sadece değişenler|changes only/i })
    expect(screen.getByRole('button', { name: /^tümü$|^all$/i })).not.toBeNull()

    api.monitoring.getDnsHistory.mockResolvedValue({ success: true, data: [historyRows[0]] })
    fireEvent.click(changedBtn)
    await waitFor(() => expect(api.monitoring.getDnsHistory).toHaveBeenCalledWith(7, 1, true))
  })

  it('Değişti satırının diff bloğunda tespit zamanı görünür', async () => {
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await screen.findByText(/tespit zamanı|detected at/i)
    // diff bloğu önceki/yeni değerleri gösterir
    expect(screen.getByText(/önceki değer|previous value/i)).not.toBeNull()
    expect(screen.getByText(/yeni değer|new value/i)).not.toBeNull()
  })

  it('beklenen sette olan Değişti satırında "beklenen değerler arasında" rozeti görünür', async () => {
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await screen.findByText(/beklenen değerler arasında|within expected values/i)
  })

  it('resolver yapılandırma bloğu servers/timeout ile render olur', async () => {
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await screen.findByText(/çözümleyici yapılandırması|resolver configuration/i)
    expect(screen.getByText('10.0.0.53')).not.toBeNull()
    expect(screen.getByText('10.0.0.54')).not.toBeNull()
    expect(screen.getByText('2000ms')).not.toBeNull()
    // propagation kapalı → propagation satırı yok
    expect(screen.queryByText(/yayılım kontrolü çözümleyicileri|propagation check resolvers/i)).toBeNull()
  })
})
