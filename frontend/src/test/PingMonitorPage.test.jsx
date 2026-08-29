import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getPingMonitors:   vi.fn(),
      getCheckHistory:    vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createPingMonitor: vi.fn(),
      updatePingMonitor: vi.fn(),
      deletePingMonitor: vi.fn(),
      triggerPingCheck:  vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'GW', host: '10.0.0.1', ip_version: 'auto', group_name: 'Y Sistemleri',
  team_name: 'SY-A', status: 'up', rtt_ms: 3, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('PingMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })   // ADMIN akışı (Kopyala) takım listesi ister
  })

  it('ping kartını (host) + grup rozetini listeler', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    expect(await screen.findByText('10.0.0.1')).toBeInTheDocument()
    expect(screen.getByText('Y Sistemleri')).toBeInTheDocument()
  })

  it('Yeni modal: grup alanı render olur', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor|yeni ping|new ping/i }))
    expect(screen.getByText(/^Group$|^Grup$/)).toBeInTheDocument()
  })

  it('karta tıkla → detay modalında 3 sekme görünür', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('10.0.0.1'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /check history|kontrol/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /response chart|süre/i })).toBeInTheDocument()
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (ad "(Kopya)", host kullanıcı tarafından değiştirilir)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'GW', host: '10.0.0.1', status: 'up', checked_at: '2026-06-24T00:00:00',
      ip_version: 'v6', group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A',
      interval_seconds: 900, timeout_ms: 7000, packet_count: 7,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false, notification_group_id: 7,
    }] })
    api.monitoring.createPingMonitor.mockResolvedValue({ success: true, data: {} })

    render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    await screen.findByText('10.0.0.1')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()

    // Ad "(Kopya)" sonekli — ad input'unun placeholder'ı form.host'tur (host değişmeden ÖNCE okunur)
    expect(screen.getByPlaceholderText('10.0.0.1').value).toMatch(/\(Kopya\)$/)

    // Ping'de aynı host+takım mükerrer sayılır (dupHost) → Kaydet host değişene dek kilitli
    const hostInput = screen.getByPlaceholderText('1.2.3.4 / host.example.com')
    expect(hostInput.value).toBe('10.0.0.1')
    expect(screen.getByRole('button', { name: /^save$|^kaydet$/i })).toBeDisabled()
    fireEvent.change(hostInput, { target: { value: '10.0.0.9' } })

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPingMonitor).toHaveBeenCalled())
    expect(api.monitoring.updatePingMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createPingMonitor.mock.calls[0][0]).toEqual({
      name: 'GW (Kopya)', host: '10.0.0.9', ipVersion: 'v6', groupName: 'Kurumsal', teamId: 5,
      intervalSeconds: 900, timeoutMs: 7000, packetCount: 7, notifyEmail: true, notifyWebhook: true,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanır: kopya, kaynağın alarmını ALAN ekibe gitmeye devam etsin.
      notificationGroupId: 7,
    })
  })

  it('istatistik panosu: sayımlar doğru + karta tıklayınca grid filtrelenir/temizlenir', async () => {
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, host: '10.0.0.1', status: 'up',   active: true },
      { id: 2, host: '10.0.0.2', status: 'down', active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
      { id: 3, host: '10.0.0.3', status: 'up',   active: false },
    ] })
    const { container } = render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    await screen.findByText('10.0.0.1')

    // Pano varsayılan KAPALI → aç/kapa çubuğuna tıkla
    expect(container.querySelector('.stats-panel')).toBeNull()
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    expect(container.querySelector('.stats-panel')).not.toBeNull()

    // Sayımlar (dil-bağımsız: renk sınıfına göre)
    expect(container.querySelector('.stat-item-total .stat-value').textContent).toBe('3')
    expect(container.querySelector('.stat-item-critical .stat-value').textContent).toBe('1')  // Erişilemiyor
    expect(container.querySelector('.stat-item-high .stat-value').textContent).toBe('1')       // Aktif alarm
    expect(container.querySelector('.stat-item-paused .stat-value').textContent).toBe('1')      // Duraklatılmış

    // "Erişilemiyor" kartına tıkla → yalnız down host kalır
    fireEvent.click(container.querySelector('.stat-item-critical'))
    await waitFor(() => expect(screen.queryByText('10.0.0.1')).not.toBeInTheDocument())
    expect(screen.getByText('10.0.0.2')).toBeInTheDocument()
    expect(screen.queryByText('10.0.0.3')).not.toBeInTheDocument()

    // Tekrar tıkla → filtre temizlenir (hepsi geri gelir)
    fireEvent.click(container.querySelector('.stat-item-critical'))
    await screen.findByText('10.0.0.1')
    expect(screen.getByText('10.0.0.3')).toBeInTheDocument()
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, host: `h${i + 1}.example.com` }))
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    await screen.findByText('h1.example.com')
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('h51.example.com')
    expect(screen.queryByText('h1.example.com')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('h1.example.com')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerPingCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())

      const runBtn = document.querySelector('.mon-act--check')
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerPingCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })
})
