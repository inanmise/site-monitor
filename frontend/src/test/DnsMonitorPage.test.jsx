import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:       vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDnsMonitors:   vi.fn(),
      getDnsDetails:    vi.fn(),
      getDnsHistory:    vi.fn(),
      createDnsMonitor: vi.fn(),
      updateDnsMonitor: vi.fn(),
      deleteDnsMonitor: vi.fn(),
      triggerDnsCheck:  vi.fn(),
      testDnsMonitor:   vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'akbank', domain: 'www.akbank.com', record_type: 'A', standalone: true,
  team_id: 5, team_name: 'SY-A', value: '1.2.3.4', ttl: 300, response_ms: 20, active: true,
  checked_at: '2026-07-06T00:00:00', slow_threshold_ms: null,
}

describe('DnsMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  it('aktif alarmlı satırda alarm ikonu (.upt-alarm-ico) render olur', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false },
    ] })
    const { container } = render(<DnsMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')
    expect(container.querySelector('.upt-alarm-ico')).not.toBeNull()
  })

  it('group_name dolu satırda grup rozeti (metni) + toolbar grup filtresi render olur', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, group_name: 'X Sistemleri' },
    ] })
    render(<DnsMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')
    // Takım hücresindeki grup rozeti metni görünür
    expect(screen.getByText('X Sistemleri')).not.toBeNull()
  })

  it('Düzenle: domain editable (readonly değil) + Test butonu testDnsMonitor çağırır', async () => {
    api.monitoring.testDnsMonitor.mockResolvedValue({ success: true, data: {
      success: true, host: 'www.akbank.com', values: ['1.2.3.4'], ttl: 300, response_ms: 20, slow: false, unexpected: [],
    } })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')

    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    // Domain artık düzenlenebilir → eski "değiştirilemez" ipucu YOK
    expect(screen.queryByText(/cannot be changed|değiştirilemez/i)).toBeNull()
    // Test butonu çözümleme çağırır
    fireEvent.click(screen.getByRole('button', { name: /^test$|test et/i }))
    await waitFor(() => expect(api.monitoring.testDnsMonitor).toHaveBeenCalled())
  })

  it('Düzenle: DNS değişikliği alarmı checkbox\'ı varsayılan İŞARETLİ; kapatınca payload dnsChangeAlertEnabled:false taşır', async () => {
    api.monitoring.updateDnsMonitor.mockResolvedValue({ success: true, data: {} })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')

    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    const checkbox = screen.getByRole('checkbox', { name: /dns değişikliği alarmı|dns change alarm/i })
    expect(checkbox.checked).toBe(true)   // dns_change_alert_enabled yok (null) → açık
    fireEvent.click(checkbox)
    expect(checkbox.checked).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))
    await waitFor(() => expect(api.monitoring.updateDnsMonitor).toHaveBeenCalled())
    const payload = api.monitoring.updateDnsMonitor.mock.calls[0][1]
    expect(payload.dnsChangeAlertEnabled).toBe(false)
  })

  it('Düzenle: dns_change_alert_enabled:false gelen monitörde checkbox İŞARETSİZ hydrate olur', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, dns_change_alert_enabled: false },
    ] })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')

    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    const checkbox = screen.getByRole('checkbox', { name: /dns değişikliği alarmı|dns change alarm/i })
    expect(checkbox.checked).toBe(false)
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'akbank', domain: 'www.akbank.com', record_type: 'CNAME', standalone: true,
      team_id: 5, team_name: 'SY-A', value: '1.2.3.4', ttl: 300, checked_at: '2026-07-06T00:00:00',
      interval_seconds: 900, group_name: 'Kurumsal',
      expected_value: '1.2.3.4\n5.6.7.8', slow_threshold_ms: 2500,
      propagation_check: true, dns_change_alert_enabled: false, active: false,
    }] })
    api.monitoring.createDnsMonitor.mockResolvedValue({ success: true, data: {} })

    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    // Ad "(Kopya)" sonekli — ad input'unun placeholder'ı form.domain'dir
    expect(screen.getByPlaceholderText('www.akbank.com').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDnsMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateDnsMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createDnsMonitor.mock.calls[0][0]).toEqual({
      name: 'akbank (Kopya)', domain: 'www.akbank.com', recordType: 'CNAME',
      intervalSeconds: 900, teamId: 5, groupName: 'Kurumsal',
      expectedValue: '1.2.3.4\n5.6.7.8', slowThresholdMs: 2500,
      propagationCheck: true, dnsChangeAlertEnabled: false,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
    })
  })

  it('Kopyala → değiştirmeden kaydet: backend mükerrer hatası toast ile gösterilir, modal kapanmaz', async () => {
    api.monitoring.createDnsMonitor.mockResolvedValue({
      success: false,
      error: 'Bu (domain, kayıt tipi) için zaten bir izleme var; mükerrer DNS monitörü oluşturulamaz.',
    })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createDnsMonitor).toHaveBeenCalled())
    expect(await screen.findByText(/zaten bir izleme var/i)).toBeInTheDocument()
    // Modal açık kalır (veri kaybı yok) → Kopya rozeti hâlâ DOM'da
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
  })

  it('Düzenle: "listeye ekle" butonu mevcut değeri beklenen listeye EKLER (üzerine yazmaz, dedupe)', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, expected_value: '217.169.196.197', value: '192.168.10.249' },
    ] })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.akbank.com')

    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    const addBtn = screen.getByRole('button', { name: /^şu anki değeri listeye ekle$|^add current value to list$/i })
    fireEvent.click(addBtn)
    const textarea = screen.getByPlaceholderText(/beklenen değer|expected value/i)
    expect(textarea.value).toBe('217.169.196.197\n192.168.10.249')
    // İkinci tık: aynı değer tekrar eklenmez (dedupe)
    fireEvent.click(addBtn)
    expect(textarea.value).toBe('217.169.196.197\n192.168.10.249')
  })
})
