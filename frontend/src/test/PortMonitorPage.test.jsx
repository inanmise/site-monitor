import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, fillGroupAndTags } from './test-utils.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',   // OutageTimeline (2026-09-12, #12) modal içinde kullanıyor
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getPortMonitors:   vi.fn(),
      getCheckHistory:    vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      getPortResponseSeries: vi.fn(),
      getMonitorNotes:   vi.fn(),
      createPortMonitor: vi.fn(),
      updatePortMonitor: vi.fn(),
      deletePortMonitor: vi.fn(),
      triggerPortCheck:  vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'mail', host: '10.0.0.1', team_name: 'SY-A', group_name: 'Mail', tags: 'prod',
  port: 25, protocol: 'TCP', status: 'open', response_ms: 3, active: true,
  interval_seconds: 60, timeout_ms: 5000, checked_at: '2026-06-24T00:00:00',
}

describe('PortMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 3, name: 'SY-A' }] })
  })

  it('port monitörünü (host) listeler', async () => {
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    expect(await screen.findByText('10.0.0.1')).toBeInTheDocument()
  })

  it('2026-09-24 kart: port ve protokol başlığın altında belirgin — büyük :port, türüne göre renkli protokol rozeti, HTTP yolu, bilinen portun hizmet adı; eski küçük gri etiket ve yinelenen Port ölçüsü yok', async () => {
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: [
      monitor, { ...monitor, id: 2, name: 'web', host: '10.0.0.2', port: 8443, protocol: 'HTTP', send_data: '/health' },
      { ...monitor, id: 3, name: 'custom', host: '10.0.0.3', port: 7001, protocol: 'UDP' }] })
    const { container } = render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('10.0.0.3')
    const card = (host) => [...container.querySelectorAll('.upt-card')].find((c) => c.querySelector('.upt-card-domain')?.textContent === host)
    const ep1 = card('10.0.0.1').querySelector('.port-ep')
    expect(ep1.querySelector('.port-ep-port').textContent).toBe(':25')
    expect(ep1.querySelector('.port-ep-proto').textContent).toBe('TCP')
    expect(ep1.querySelector('.port-ep-proto').className).toContain('port-ep-proto--tcp')
    expect(ep1.querySelector('.port-ep-svc').textContent).toBe('SMTP')
    expect(ep1.querySelector('.port-ep-path')).toBeNull()                                   // yol yalnız HTTP türünde
    expect(ep1).toHaveAttribute('title', expect.stringMatching(/^10\.0\.0\.1:25 · TCP — .+ · SMTP$/))
    const ep2 = card('10.0.0.2').querySelector('.port-ep')
    expect(ep2.querySelector('.port-ep-proto').className).toContain('port-ep-proto--http')
    expect(ep2.querySelector('.port-ep-path').textContent).toBe('/health')
    expect(ep2.querySelector('.port-ep-svc').textContent).toBe('HTTPS (alt)')
    const ep3 = card('10.0.0.3').querySelector('.port-ep')
    expect(ep3.querySelector('.port-ep-proto').className).toContain('port-ep-proto--udp')
    expect(ep3.querySelector('.port-ep-svc')).toBeNull()                                    // bilinmeyen port → hizmet adı uydurulmaz
    expect(container.querySelector('.upt-card .upt-port-tag')).toBeNull()
    expect(card('10.0.0.1').querySelector('.upt-card-metrics').textContent).not.toMatch(/^Port|Port$/)
  })

  it('Yeni Monitör butonu ADMIN için modal açar (host/port/grup alanları)', async () => {
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    expect(screen.getByText(/new port monitor|yeni port monit/i)).toBeInTheDocument()
    expect(screen.getByText(/^Group$|^Grup$/)).toBeInTheDocument()
  })

  it('host+port girip kaydet → createPortMonitor doğru payload ile çağrılır', async () => {
    api.monitoring.createPortMonitor.mockResolvedValue({ success: true, data: {} })
    render(<PortMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)   // Port ekleme TEAM_ADMIN+; takım otomatik dolar (zorunlu takım)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    fireEvent.change(screen.getByPlaceholderText(/1\.2\.3\.4/), { target: { value: 'mail.example.com' } })
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '993' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPortMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPortMonitor.mock.calls[0][0]
    expect(payload.host).toBe('mail.example.com')
    expect(payload.port).toBe(993)
  })

  it('2026-09-24 vekil: varsayılan Doğrudan (payload useProxy=OFF); formda vekilden denetlenebilen portlar HER ZAMAN yazılı; uyarı yok', async () => {
    api.monitoring.createPortMonitor.mockResolvedValue({ success: true, data: {} })
    render(<PortMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    const notes = await waitFor(() => { const n = document.querySelector('.port-proxy-notes'); expect(n).not.toBeNull(); return n })
    expect(notes.textContent).toMatch(/443, 8443/)
    expect(notes.querySelector('.field-hint--warn')).toBeNull()
    expect(screen.getByText(/^Doğrudan \(vekil kullanma\)$|^Direct \(no proxy\)$/)).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText(/1\.2\.3\.4/), { target: { value: 'mail.example.com' } })
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '25' } })
    await fillGroupAndTags()
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPortMonitor).toHaveBeenCalled())
    expect(api.monitoring.createPortMonitor.mock.calls[0][0].useProxy).toBe('OFF')
  })

  it('2026-09-24 vekil seçilince: izinsiz port (22) uyarılır; UDP seçilince "UDP vekilden geçemez" uyarısı; izinli port (443) uyarısız; test yolu gösterilir', async () => {
    api.monitoring.testPortMonitor.mockResolvedValue({ success: true, data: { open: true, response_ms: 30, via: 'proxy', detail: 'vekil üzerinden bağlandı' } })
    render(<PortMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor/i }))
    fireEvent.change(screen.getByPlaceholderText(/1\.2\.3\.4/), { target: { value: 'a.example.com' } })
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '22' } })
    // Vekil: Her zaman vekil üzerinden
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /Kurumsal vekil|Corporate proxy/ }))
    fireEvent.mouseDown(screen.getByText(/^Her zaman vekil üzerinden$|^Always via proxy$/))
    const warns = () => [...document.querySelectorAll('.port-proxy-notes .field-hint--warn')].map((w) => w.textContent)
    await waitFor(() => expect(warns().join(' ')).toMatch(/22.*443, 8443/))
    // izinli port → port uyarısı kalkar
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '443' } })
    await waitFor(() => expect(warns()).toHaveLength(0))
    // UDP → UDP uyarısı
    const typeLabel = [...document.querySelectorAll('label > span:first-child')].find((sp) => /^(Kontrol Tipi|Check type)$/i.test(sp.textContent.trim()))
    fireEvent.mouseDown(typeLabel.parentElement.querySelector('button[role="combobox"]'))
    fireEvent.mouseDown(screen.getByText(/^UDP — /))
    await waitFor(() => expect(warns().join(' ')).toMatch(/UDP/))
    // TCP'ye dön, test et → yol satırı
    fireEvent.mouseDown(typeLabel.parentElement.querySelector('button[role="combobox"]'))
    fireEvent.mouseDown(screen.getByText(/^TCP — /))
    fireEvent.click(screen.getByRole('button', { name: /^(Test|Test et|Dene|Kaydetmeden test)/i }))
    await waitFor(() => expect(api.monitoring.testPortMonitor).toHaveBeenCalled())
    expect(api.monitoring.testPortMonitor.mock.calls[0][0].useProxy).toBe('ON')
    expect(await screen.findByText(/^(Vekil üzerinden|Via proxy)$/)).toBeInTheDocument()
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'mail', host: '10.0.0.1', status: 'open', checked_at: '2026-06-24T00:00:00',
      port: 8443, protocol: 'HTTP', expect: '2xx', send_data: '/health',
      team_id: 3, team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod,kritik', alert_level: 'HIGH', notify_email: false,
      ip_version: 'v4', slow_response_enabled: true, slow_threshold_ms: 4500,
      interval_seconds: 900, timeout_ms: 7000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false, notification_group_id: 7,
      use_proxy: 'ON', proxy_effective: 'proxy', proxy_source: 'monitor',   // vekil tercihi (2026-09-24) kopyaya taşınır
    }] })
    api.monitoring.createPortMonitor.mockResolvedValue({ success: true, data: {} })

    render(<PortMonitorPage systemRole="ADMIN" teamId={3} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    await screen.findByText('10.0.0.1')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('10.0.0.1').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPortMonitor).toHaveBeenCalled())
    expect(api.monitoring.updatePortMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createPortMonitor.mock.calls[0][0]).toEqual({
      name: 'mail (Kopya)', host: '10.0.0.1', port: 8443, protocol: 'HTTP', useProxy: 'ON',
      expect: '2xx', sendData: '/health',
      teamId: 3, groupName: 'Kurumsal', tags: 'prod,kritik', alertLevel: 'HIGH', notifyEmail: false, notifyWebhook: true,
      ipVersion: 'v4', slowResponseEnabled: true, slowThresholdMs: 4500,
      intervalSeconds: 900, timeoutMs: 7000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitmeye devam etsin.
      notificationGroupId: 7,
    })
  })

  it('detay modali 4 sekme (Kontrol/Alarm/Grafik/Rehber) gösterir; Rehber sekmesi MonitorNotes\'u host:port hedefiyle yükler', async () => {
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null, notes: [] } })
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    fireEvent.click(await screen.findByText('10.0.0.1'))            // satıra tıkla → detay modali açılır
    // Başlıkta kartla aynı belirgin uç nokta (büyük boy) — eski ":25" gri etiketi değil (2026-09-24)
    const headEp = document.querySelector('.upt-modal-header .port-ep.port-ep--lg')
    expect(headEp).not.toBeNull()
    expect(headEp.querySelector('.port-ep-port').textContent).toBe(':25')
    expect(headEp.querySelector('.port-ep-proto').textContent).toBe('TCP')
    // 4 sekmeli parite çubuğu (ping/keyword ile aynı)
    expect(screen.getByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /alarm history|alarm geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /response chart|süre grafiği/i })).toBeInTheDocument()
    // Rehber & Notlar sekmesi → MonitorNotes type=PORT, target=host:port
    fireEvent.click(screen.getByRole('button', { name: /guide & notes|rehber & notlar/i }))
    // MonitorNotes lazy import + mount → getMonitorNotes(type, target). Dinamik import (React.lazy) tam-suite
    // paralel worker'larda CPU çekişmesi altında ilk seferde 5sn'yi aşabiliyordu (izole koşuda hep geçer) → flaky.
    // Kök: test mantığı değil, dinamik-import gecikmesi; gerçekçi tavan (10sn) çekişme altında da güvenli.
    await waitFor(() => expect(api.monitoring.getMonitorNotes).toHaveBeenCalledWith('PORT', '10.0.0.1:25'), { timeout: 10000 })
  })

  it('sayfalama: 120 kayıt → 50 satır + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, host: `h${i + 1}.example.com` }))
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
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
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('h1.example.com')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerPortCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())

      // Kartın kontrol düğmesi (modal da aynı adlı düğmeyi taşır; DOM sırasında kart önce gelir)
      const runBtn = screen.getAllByRole('button', { name: /^(Kontrol Et|Check now)$/i })[0]
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerPortCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })
})

/**
 * "Değişiklik nedeni" (K8) — yalnız DÜZENLEMEDE görünür ve yazıldıysa payload'a girer.
 *
 * Geçmiş satırı neyin değiştiğini gösterir ama NEDEN değiştiğini gösteremez; bu alan onu
 * cevaplar. Zorunlu değil: zorunlu olsaydı insanlar "guncelleme" yazıp geçerdi. Bu yüzden iki
 * yönü de pinlenmeli — boşken payload'ı KİRLETMEMESİ ve doluyken GERÇEKTEN gitmesi.
 */
describe('PortMonitorPage — değişiklik nedeni', () => {
  const MON = {
    id: 1, name: 'mail', host: '10.0.0.1', port: 8443, status: 'open',
    checked_at: '2026-06-24T00:00:00', team_id: 3, team_name: 'SY-A', active: true,
    group_name: 'Mail', tags: 'prod',   // grup + etiket zorunlu (2026-09-18)
  }

  async function openEdit() {
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: [MON] })
    api.monitoring.updatePortMonitor.mockResolvedValue({ success: true, data: {} })
    render(<PortMonitorPage systemRole="ADMIN" teamId={3} teamName="SY-A" />)
    await screen.findByText('10.0.0.1')
    fireEvent.click(screen.getByRole('button', { name: /düzenle|edit/i }))
    // Modal başlığı yerine ALANIN KENDİSİNİ bekle: başlık metni düğme adıyla çakışıyor.
    await waitFor(() => expect(document.getElementById('port-change-note')).not.toBeNull())
  }

  it('YENİ kayıtta alan HİÇ çizilmez — "neden" sorusu ancak var olan bir şey değişince anlamlı', async () => {
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: [MON] })
    render(<PortMonitorPage systemRole="ADMIN" teamId={3} teamName="SY-A" />)
    await screen.findByText('10.0.0.1')

    fireEvent.click(screen.getByRole('button', { name: /yeni|new|ekle/i }))
    expect(document.getElementById('port-change-note')).toBeNull()
  })

  it('düzenlemede alan çizilir ve yazılan not payload ile GİDER', async () => {
    await openEdit()
    const note = document.getElementById('port-change-note')
    expect(note).not.toBeNull()

    fireEvent.change(note, { target: { value: '  Kesinti sonrası sıklık düşürüldü  ' } })
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))

    await waitFor(() => expect(api.monitoring.updatePortMonitor).toHaveBeenCalled())
    const [, payload] = api.monitoring.updatePortMonitor.mock.calls.at(-1)
    // Kırpılır: baştaki/sondaki boşluk geçmişte görünmesin.
    expect(payload.changeNote).toBe('Kesinti sonrası sıklık düşürüldü')
  })

  it('not BOŞSA payload\'a hiç girmez (boş alan istek gövdesini kirletmesin)', async () => {
    await openEdit()
    fireEvent.click(screen.getByRole('button', { name: /kaydet|save/i }))

    await waitFor(() => expect(api.monitoring.updatePortMonitor).toHaveBeenCalled())
    const [, payload] = api.monitoring.updatePortMonitor.mock.calls.at(-1)
    expect(payload).not.toHaveProperty('changeNote')
  })

  it('modal kapanınca not SIFIRLANIR — sonraki düzenlemeye sızmaz', async () => {
    await openEdit()
    fireEvent.change(document.getElementById('port-change-note'),
      { target: { value: 'ilk düzenlemenin gerekçesi' } })
    fireEvent.click(screen.getByRole('button', { name: /iptal|cancel/i }))

    fireEvent.click(screen.getByRole('button', { name: /düzenle|edit/i }))
    await waitFor(() => expect(document.getElementById('port-change-note')).not.toBeNull())
    expect(document.getElementById('port-change-note').value).toBe('')
  })

  // -- Istatistik kartlari filtreyi IZLER (O16) -------------------------------
  // Kartlar ham `monitors` uzerinden sayiliyordu; DnsMonitorPage ile ayni kusur.

  const threePorts = () => api.monitoring.getPortMonitors.mockResolvedValue({
    success: true,
    data: [
      { ...monitor, id: 1, host: '10.0.0.1' },
      { ...monitor, id: 2, host: '10.0.0.2' },
      { ...monitor, id: 3, host: '192.0.2.9', status: 'closed', active_alarm: true },
    ],
  })

  async function openPortStats(container) {
    await waitFor(() => expect(api.monitoring.getPortMonitors).toHaveBeenCalled())
    await screen.findByText('10.0.0.1')
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    return () => container.querySelector('.stat-value-total')?.textContent
  }

  it('istatistik kartlari ARAMA ile daralir (kuresel sayida donup kalmaz)', async () => {
    threePorts()
    const { container } = render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const totalText = await openPortStats(container)

    expect(totalText()).toBe('3')

    fireEvent.change(container.querySelector('.upt-search'), { target: { value: '192.0.2' } })
    await waitFor(() => expect(totalText()).toBe('1'))

    // Kapali/alarm sayaclari da kapsamdan gelir.
    expect(container.querySelector('.stat-value-critical')?.textContent).toBe('1')
  })

  it('arama HICBIR seyi eslestirmese bile istatistik seridi CIZILMEYE devam eder', async () => {
    threePorts()
    const { container } = render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const totalText = await openPortStats(container)

    fireEvent.change(container.querySelector('.upt-search'), { target: { value: 'hicbiryerde-yok' } })

    await waitFor(() => expect(totalText()).toBe('0'))
    expect(container.querySelector('.stats-collapse-bar')).not.toBeNull()
    expect(container.querySelector('.stats-panel')).not.toBeNull()
  })
})
