import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'

// Açıklama ifadeleri (expectPhrase/triggerPhrase) dilden bağımsız TR; butonlar
// varsayılan dilde (en) — regex'ler iki-dilli/dil-bağımsız tutuldu.
vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:           vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getKeywordMonitors:   vi.fn(),
      getKeywordHistory:    vi.fn(),
      createKeywordMonitor: vi.fn(),
      updateKeywordMonitor: vi.fn(),
      deleteKeywordMonitor: vi.fn(),
      triggerKeywordCheck:  vi.fn(),
      testKeyword:          vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Akbank', url: 'https://www.akbank.com/', keyword: 'akbank',
  operator: 'GTE', match_count: 1, group_name: 'X Sistemleri', team_name: 'SY-A',
  status: 'up', http_status: 200, occurrences: 5, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('KeywordMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getKeywordHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
  })

  it('izleme kartını (url + kelime) listeler', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.akbank.com/')).toBeInTheDocument()
    expect(screen.getByText('akbank')).toBeInTheDocument()
  })

  it('Yeni modal: dinamik tetiklenme açıklaması görünür + güncellenir', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    // Varsayılan GTE / 1 → "en az 1 kez" (sağlıklı) + "hiç bulunmazsa" (alarm) — dil-bağımsız TR
    expect(screen.getByText(/en az 1 kez/)).toBeInTheDocument()
    expect(screen.getByText(/hiç bulunmazsa/)).toBeInTheDocument()
  })

  it('Yeni modal: Test butonu testKeyword çağırır ve sonucu gösterir', async () => {
    api.monitoring.testKeyword.mockResolvedValue({
      success: true,
      data: { occurrences: 5, condition_met: true, phrase: 'en az 1 kez', http_status: 200, response_ms: 12 },
    })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    fireEvent.change(screen.getByPlaceholderText('SUCCESS'), { target: { value: 'akbank' } })

    fireEvent.click(screen.getByRole('button', { name: /test/i }))
    await waitFor(() => expect(api.monitoring.testKeyword).toHaveBeenCalled())
    expect(await screen.findByText(/condition met|koşul sağlanıyor/i)).toBeInTheDocument()
  })

  it('karta tıkla → detay modalında 3 sekme görünür', async () => {
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getKeywordHistory).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /check history|kontrol/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /response chart|süre/i })).toBeInTheDocument()
  })

  it('istatistik panosu: İhlal/Hata ayrımı + filtreleme/temizleme', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, url: 'https://a.example.com', keyword: 'a', status: 'up',    active: true },
      { id: 2, url: 'https://b.example.com', keyword: 'b', status: 'down',  active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
      { id: 3, url: 'https://c.example.com', keyword: 'c', status: 'error', active: true },
    ] })
    const { container } = render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    await screen.findByText('https://a.example.com')

    // Pano varsayılan KAPALI → aç/kapa çubuğuna tıkla
    expect(container.querySelector('.stats-panel')).toBeNull()
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    expect(container.querySelector('.stats-panel')).not.toBeNull()

    expect(container.querySelector('.stat-item-total .stat-value').textContent).toBe('3')
    expect(container.querySelector('.stat-item-critical .stat-value').textContent).toBe('1')  // İhlal (down)
    expect(container.querySelector('.stat-item-error .stat-value').textContent).toBe('1')       // Hata (error)
    expect(container.querySelector('.stat-item-high .stat-value').textContent).toBe('1')         // Aktif alarm

    // "Hata" kartına tıkla → yalnız error url kalır (İhlal'den ayrı)
    fireEvent.click(container.querySelector('.stat-item-error'))
    await waitFor(() => expect(screen.queryByText('https://a.example.com')).not.toBeInTheDocument())
    expect(screen.getByText('https://c.example.com')).toBeInTheDocument()
    expect(screen.queryByText('https://b.example.com')).not.toBeInTheDocument()

    // Tekrar tıkla → filtre temizlenir
    fireEvent.click(container.querySelector('.stat-item-error'))
    await screen.findByText('https://a.example.com')
    expect(screen.getByText('https://b.example.com')).toBeInTheDocument()
  })
})
