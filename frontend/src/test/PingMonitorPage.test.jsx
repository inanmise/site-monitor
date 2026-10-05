import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
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
  id: 1, name: 'GW', host: '10.0.0.1', ip_version: 'auto', group_name: 'Y Sistemleri', tags: 'prod',
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

  it('kart (2026-09-27): protokol ÇİPLERİ başlığın altında (shadcn Badge) — ICMP, IPv6 → ICMPv6; IP ailesi yalnız seçiliyse; paket sayısı; detay başlığında büyük gösterim', async () => {
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, packet_count: 4 },
      { ...monitor, id: 2, host: '10.0.0.2', ip_version: 'v4', packet_count: 1 },
      { ...monitor, id: 3, host: 'fe80::1', ip_version: 'v6' }] })
    const { container } = render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await screen.findByText('fe80::1')
    const card = (host) => [...container.querySelectorAll('.upt-grid > [data-slot="card"]')]
      .find((c) => c.querySelector('[data-monitor-open]')?.textContent === host)
    const chip = (host, kind) => card(host).querySelector(`[data-slot="ping-protocol"] [data-chip="${kind}"]`)
    expect(chip('10.0.0.1', 'proto').textContent).toBe('ICMP')
    expect(chip('10.0.0.1', 'proto')).toHaveAttribute('data-slot', 'badge')
    expect(chip('10.0.0.1', 'family')).toBeNull()                                  // otomatik → aile yazılmaz
    expect(chip('10.0.0.1', 'packets').textContent).toMatch(/^4 (paket|packets)$/)
    expect(chip('10.0.0.2', 'family').textContent).toBe('IPv4')
    expect(chip('10.0.0.2', 'packets').textContent).toMatch(/^1 (paket|packet)$/)
    expect(chip('fe80::1', 'proto').textContent).toBe('ICMPv6')
    expect(chip('fe80::1', 'family').textContent).toBe('IPv6')
    expect(chip('fe80::1', 'packets')).toBeNull()                                   // paket sayısı yoksa uydurulmaz
    // Protokol kartta YALNIZ bir kez (çip satırında) geçer; kayıtlı v4/v6 "V4" diye sızmaz.
    for (const host of ['10.0.0.1', '10.0.0.2']) expect(card(host).textContent.match(/ICMP/g)).toHaveLength(1)
    expect(container.textContent).not.toMatch(/\bV4\b|\bV6\b/)
    fireEvent.click(screen.getByText('10.0.0.2'))
    const head = await waitFor(() => { const h = screen.getByRole('dialog').querySelector('.port-ep.port-ep--lg'); expect(h).not.toBeNull(); return h })
    expect(head.querySelector('.port-ep-proto').textContent).toBe('ICMP')
    expect(head.querySelector('.port-ep-fam').textContent).toBe('IPv4')
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
    expect(screen.getByRole('tab', { name: /check history|kontrol/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /response chart|süre/i })).toBeInTheDocument()
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (ad "(Kopya)", host kullanıcı tarafından değiştirilir)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'GW', host: '10.0.0.1', status: 'up', checked_at: '2026-06-24T00:00:00',
      ip_version: 'v6', group_name: 'Kurumsal', tags: 'prod,kritik', team_id: 5, team_name: 'SY-A',
      interval_seconds: 900, timeout_ms: 7000, packet_count: 7,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      slow_response_enabled: true, slow_baseline_window_minutes: 25, slow_threshold_percent: 35,
      active: false, notification_group_id: 7, noc_notify: true, noc_group_ids: [2, 3],
    }] })
    api.monitoring.createPingMonitor.mockResolvedValue({ success: true, data: {} })

    render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    await screen.findByText('10.0.0.1')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('[data-slot="duplicate-badge"]')).not.toBeNull()
    expect(screen.getByText(/kaynak izlemenin birebir kopyası|an exact copy of the source monitor/i)).toBeInTheDocument()

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
      name: 'GW (Kopya)', host: '10.0.0.9', ipVersion: 'v6', groupName: 'Kurumsal', tags: 'prod,kritik', teamId: 5,
      intervalSeconds: 900, timeoutMs: 7000, packetCount: 7, alertLevel: 'WARNING', notifyEmail: true, notifyWebhook: true,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      // Yavaşlık ayarı da kopyalanır: kopyanın "sessiz" doğması, kullanıcının kurduğu eşiği
      // sessizce düşürmek olurdu.
      slowResponseEnabled: true, slowBaselineWindowMinutes: 25, slowThresholdPercent: 35,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanır: kopya, kaynağın alarmını ALAN ekibe gitmeye devam etsin.
      notificationGroupId: 7,
      // 7/24 izleme ekibi (2026-09-27): açık anahtar + açık grup seçimi de kopyalanır
      nocNotify: true, nocGroupIds: [2, 3],
    })
  })

  it('yavaşlık alarmı: varsayılan KAPALI, açılınca pencere/yüzde alanları gelir ve payload\'a girer', async () => {
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [] })
    api.monitoring.createPingMonitor.mockResolvedValue({ success: true, data: {} })

    // USER rolü: takım formda hazır gelir (ADMIN'de takım seçilene dek Kaydet kilitli).
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPingMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))

    // Opt-in: kutucuk işaretlenmeden eşik alanları EKRANDA DURMAZ (kapalıyken ölü sayı gösterilmez).
    const box = screen.getByRole('checkbox', { name: /slowness alarm|yavaşlık alarmı/i })
    expect(box).not.toBeChecked()
    expect(screen.queryByLabelText(/baseline window|taban çizgisi penceresi/i)).toBeNull()

    fireEvent.click(box)
    expect(box).toBeChecked()
    const win = screen.getByLabelText(/baseline window|taban çizgisi penceresi/i)
    const pct = screen.getByLabelText(/deviation threshold|sapma eşiği/i)
    expect(win.value).toBe('10')
    expect(pct.value).toBe('20')
    fireEvent.change(win, { target: { value: '15' } })
    fireEvent.change(pct, { target: { value: '40' } })

    fireEvent.change(screen.getByPlaceholderText('1.2.3.4 / host.example.com'), { target: { value: '10.0.0.5' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createPingMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPingMonitor.mock.calls[0][0]
    expect(payload.slowResponseEnabled).toBe(true)
    expect(payload.slowBaselineWindowMinutes).toBe(15)
    expect(payload.slowThresholdPercent).toBe(40)
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
    expect(container.querySelector('[data-slot="stats-panel"]')).toBeNull()
    fireEvent.click(container.querySelector('[data-slot="stats-toggle"]'))
    expect(container.querySelector('[data-slot="stats-panel"]')).not.toBeNull()

    // Sayımlar (dil-bağımsız: renk sınıfına göre)
    expect(container.querySelector('[data-slot="stat-item"][data-tone="total"] [data-slot="stat-value"]').textContent).toBe('3')
    expect(container.querySelector('[data-slot="stat-item"][data-tone="critical"] [data-slot="stat-value"]').textContent).toBe('1')  // Erişilemiyor
    expect(container.querySelector('[data-slot="stat-item"][data-tone="high"] [data-slot="stat-value"]').textContent).toBe('1')       // Aktif alarm
    expect(container.querySelector('[data-slot="stat-item"][data-tone="paused"] [data-slot="stat-value"]').textContent).toBe('1')      // Duraklatılmış

    // "Erişilemiyor" kartına tıkla → yalnız down host kalır
    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-tone="critical"]'))
    await waitFor(() => expect(screen.queryByText('10.0.0.1')).not.toBeInTheDocument())
    expect(screen.getByText('10.0.0.2')).toBeInTheDocument()
    expect(screen.queryByText('10.0.0.3')).not.toBeInTheDocument()

    // Tekrar tıkla → filtre temizlenir (hepsi geri gelir)
    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-tone="critical"]'))
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
    expect(container.querySelectorAll('.upt-grid > [data-slot="card"]')).toHaveLength(50)
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

      // Kartın kontrol düğmesi (modal da aynı adlı düğmeyi taşır; DOM sırasında kart önce gelir)
      const runBtn = screen.getAllByRole('button', { name: /^(Kontrol Et|Check now)$/i })[0]
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerPingCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  it('kart yoğunluğu (2026-09-27): araç çubuğunun İLK öğesi Kompakt/Zengin seçici; her açılış Zengin, seçim ızgaraya + kartlara iner, toplu seçim ve detay çalışır; Kompakt KALICI DEĞİL', async () => {
    const storedModes = () => Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i)).filter((k) => k && k.startsWith('sm.cardMode'))
    const first = render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('10.0.0.1')
    const grid = document.querySelector('.upt-grid')
    const toolbar = document.querySelector('.upt-toolbar')
    expect(toolbar.firstElementChild).toHaveAttribute('data-slot', 'card-density-toggle')
    expect(toolbar.firstElementChild.className).toMatch(/(^|\s)mr-auto(\s|$)/)
    expect(screen.getByRole('radio', { name: /^(Rich|Zengin)$/ })).toHaveAttribute('data-state', 'on')
    expect(grid).toHaveAttribute('data-density', 'rich')
    expect(grid.querySelector('[data-slot="card"]')).toHaveAttribute('data-density', 'rich')
    expect(grid.querySelector('[data-slot="monitor-card-rich"]')).not.toBeNull()

    fireEvent.click(screen.getByRole('radio', { name: /^(Compact|Kompakt)$/ }))
    expect(grid).toHaveAttribute('data-density', 'compact')
    expect(grid.querySelector('[data-slot="card"]')).toHaveAttribute('data-density', 'compact')
    expect(grid.querySelector('[data-slot="monitor-card-rich"]')).toBeNull()
    expect(storedModes()).toEqual([])   // seçim tarayıcıya yazılmaz
    // Kompakt'ta da toplu seçim çalışır ve başlık (stretched button) detayı açar
    fireEvent.click(screen.getByRole('checkbox', { name: /^(Select 10\.0\.0\.1 for bulk action|10\.0\.0\.1 — toplu işlem için seç)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="bulk-action-bar"]')).toHaveTextContent(/1 (selected|seçili)/))
    fireEvent.click(grid.querySelector('[data-monitor-open]'))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    first.unmount()

    // sayfaya dönüş (yeni bağlama) yeniden Zengin açılır — Kompakt hatırlanmaz
    render(<PingMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('10.0.0.1')
    expect(document.querySelector('.upt-grid')).toHaveAttribute('data-density', 'rich')
    expect(screen.getByRole('radio', { name: /^(Rich|Zengin)$/ })).toHaveAttribute('data-state', 'on')
    expect(document.querySelector('[data-slot="monitor-card-rich"]')).not.toBeNull()
  })
})

/**
 * Kontrol geçmişi hata teşhisi (2026-10-05): başarısız ping satırı ham hata yerine neden rozeti + tek satır + aç/kapa;
 * açılınca satırın altında panel (Neden / Etkisi / Ne yapmalı, paket kaybı, ham hata). Başarılı satırda hücre yok.
 */
describe('PingMonitorPage — kontrol geçmişi hata teşhisi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPingMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [
        { id: 11, monitor_id: 1, up: false, rtt_ms: null, packet_loss: 100, error: 'Yanıt yok (%100 paket kaybı)',
          checked_at: '2026-10-05T09:00:00', failure_reason: 'ICMP_NO_REPLY',
          failure_detail: JSON.stringify({ phase: 'ICMP', packet_loss: 100, packets: 4, target: '10.0.0.1', timeout_ms: 5000 }) },
        { id: 10, monitor_id: 1, up: true, rtt_ms: 3, packet_loss: 0, checked_at: '2026-10-05T08:59:00' },
      ], counts: { total: 2, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-10-05T00:00:00', to: '2026-10-05T23:59:59' }, total: 2, page: 0, size: 50 } })
  })

  it('başarısız satır: neden rozeti; aç → panel (paket kaybı + ham hata); başarılı satırda hücre yok', async () => {
    render(<PingMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('10.0.0.1'))
    const dialog = await screen.findByRole('dialog')
    const cell = await waitFor(() => { const c = dialog.querySelector('[data-slot="chkfail-cell"]'); expect(c).not.toBeNull(); return c })
    expect(dialog.querySelectorAll('[data-slot="chkfail-cell"]')).toHaveLength(1)
    expect(cell).toHaveAttribute('data-code', 'ICMP_NO_REPLY')
    expect(within(cell).getByText(/^(No ping reply|Ping yanıtı yok)$/)).toBeInTheDocument()
    fireEvent.click(within(cell).getByRole('button', { name: /show details|ayrıntıyı göster/i }))
    const panel = await within(dialog).findByRole('region', { name: /failure detail|hata ayrıntısı/i })
    expect(panel.querySelector('[data-key="packetLoss"]').textContent).toMatch(/100/)
    expect(within(panel).getByText('Yanıt yok (%100 paket kaybı)')).toBeInTheDocument()
  })
})
