import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DnsDetailModal from '../components/DnsDetailModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getDnsDetails: vi.fn(),
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      getDnsResponseSeries: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
    admin: {
      // AlertHistory (alerts tab) — bu testte tab açılmıyor ama import zinciri için güvenli stub
      getAlertHistory: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 7, name: 'iyi', domain: 'www.iyigelecegeyatirim.com', record_type: 'A',
  standalone: true, team_id: 5, value: '192.168.1.10',
  expected_value: '192.168.1.10\n217.169.196.197',
}

const details = {
  records: { A: { values: ['192.168.1.10'], ttl: 599, response_ms: 2, success: true } },
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
    id: 1, monitor_id: 7, record_type: 'A', value: '192.168.1.10',
    changed: true, rotated: false, previous_value: '217.169.196.197',
    checked_at: '2026-08-02T01:32:00', ttl: 788, response_ms: 2,
  },
  {
    id: 2, monitor_id: 7, record_type: 'A', value: '217.169.196.197',
    changed: false, rotated: false, previous_value: null,
    checked_at: '2026-08-02T01:27:00', ttl: 287, response_ms: 5,
  },
]

const envelope = (items) => ({ success: true, data: {
  items, counts: { total: items.length, fail: items.filter(r => r.changed || r.rotated).length },
  buckets: [], alerts: [], range: { from: '2026-08-01T00:00:00', to: '2026-08-02T23:59:59' },
  total: items.length, page: 0, size: 50 } })

describe('DnsDetailModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: details })
    api.monitoring.getCheckHistory.mockResolvedValue(envelope(historyRows))
  })

  it('filtre chip\'leri render olur; "Değişenler" status=changed ile yeniden yükler', async () => {
    render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    expect(api.monitoring.getCheckHistory.mock.calls[0][0]).toBe('dns')
    expect(api.monitoring.getCheckHistory.mock.calls[0][2].status).toBeUndefined()   // varsayılan: tümü

    const changedBtn = await screen.findByRole('button', { name: /değişenler|changed/i })

    api.monitoring.getCheckHistory.mockResolvedValue(envelope([historyRows[0]]))
    fireEvent.click(changedBtn)
    await waitFor(() => {
      const calls = api.monitoring.getCheckHistory.mock.calls
      expect(calls[calls.length - 1][2].status).toBe('changed')   // sunucu-taraflı filtre
    })
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

  // shadcn geçişi (2026-09-25): elle kurulu .dns-modal-* penceresi → diğer sekiz türün detay kabuğu
  // (MonitorDetailModal / ui/ModalShell). Kabuk sözleşmesi + kayıt türü alt sekmeleri + değişim rozetleri.
  it('ortak detay kabuğu: dialog adı domain; kayıt türleri sekme (boş tür soluk, seçilince "kayıt yok"); rozetler Badge; Escape kapatır', async () => {
    const onClose = vi.fn()
    render(<DnsDetailModal monitor={monitor} onClose={onClose} status="up" />)
    const dlg = await screen.findByRole('dialog', { name: /www\.iyigelecegeyatirim\.com/ })
    // Kayıt türü alt sekmeleri: izlenen tür (A) seçili, değer sayısı rozeti adda; boş tür işaretli
    const aTab = await screen.findByRole('tab', { name: /^A\s*1$/ })
    expect(aTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /^AAAA$/ })).toHaveAttribute('data-empty', 'true')
    expect(dlg.querySelector('code')?.textContent).toBe('192.168.1.10')
    fireEvent.mouseDown(screen.getByRole('tab', { name: /^MX$/ }), { button: 0 })
    expect(await screen.findByText(/bu tip için kayıt bulunamadı|no records found for this type/i)).toBeInTheDocument()
    // Geçmiş satırı rozetleri shadcn Badge (data-variant): "değişti" + "beklenen içinde" (1. satır), "değişiklik yok"
    // (2. satır). İpuçlu rozetin data-slot'u Tooltip tetiğinindir (Slot birleştirmesi) → rozet data-variant'tan tanınır.
    await waitFor(() => expect(dlg.querySelector('[data-variant][data-change="changed"]')).not.toBeNull())
    expect(dlg.querySelector('[data-variant="outline"][data-change="expected"]')).not.toBeNull()
    expect(dlg.querySelector('[data-variant][data-change="none"]')).not.toBeNull()
    // Escape → kabuk (Radix katman yığını) onClose'u çağırır; eski useEscapeKey artık yok
    fireEvent.keyDown(document.activeElement || dlg, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})
