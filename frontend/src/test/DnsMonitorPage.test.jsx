import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',   // OutageTimeline (2026-09-12, #12) modal içinde kullanıyor
  api: withApiFallback({
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
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'example', domain: 'www.example.com', record_type: 'A', standalone: true,
  team_id: 5, team_name: 'SY-A', value: '1.2.3.4', ttl: 300, response_ms: 20, active: true,
  checked_at: '2026-07-06T00:00:00', slow_threshold_ms: null,
}

describe('DnsMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  /**
   * Kart durum rozeti — diğer sekiz türde vardı, DNS'te YOKTU (kullanıcı bildirdi). Rozet
   * çizilmeyince kartın üst satırında tek çocuk kalıyor ve `space-between` onu sola yaslıyor:
   * kayıt-tipi + bağlantı kopyalama kartın SOLUNA düşüyordu. Sözcükler istatistik şeridiyle
   * AYNI sözlükten gelir ve koşullar filtre çipleriyle birebir eşleşir.
   */
  it.each([
    [{ active: true },                        /sağlıklı|healthy/i],
    [{ active: true, active_alarm: true },    /alarmlı|alarming/i],
    [{ active: false },                       /duraklatıldı|paused/i],
    [{ active: true, checked_at: null },      /kontrol edilmedi|not checked/i],
  ])('kart durum rozeti: %o → %s', async (patch, expected) => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, ...patch }] })
    const { container } = render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('www.example.com')
    const badge = container.querySelector('.upt-card-top .upt-badge')
    expect(badge).toBeTruthy()
    expect(badge.textContent).toMatch(expected)
    // Rozet üst satırın İLK çocuğu olmalı — sağ gruptaki kopyalama düğmesini sola itmesin.
    // Toplu seçim kutucuğu (2026-09-12, #13) rozetin SOLUNDA durabilir; rozet yine sağ gruptan önce gelmeli.
    const top = container.querySelector('.upt-card-top')
    const firstNonCheck = [...top.children].find((el) => !(el.tagName === 'INPUT' && el.type === 'checkbox'))
    expect(firstNonCheck).toBe(badge)
  })

  it('aktif alarmlı satırda alarm ikonu (.upt-alarm-ico) render olur', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, active_alarm: true, alarm_level: 'CRITICAL', alarm_acknowledged: false },
    ] })
    const { container } = render(<DnsMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.example.com')
    expect(container.querySelector('.upt-alarm-ico')).not.toBeNull()
  })

  it('group_name dolu satırda grup rozeti (metni) + toolbar grup filtresi render olur', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, group_name: 'X Sistemleri' },
    ] })
    render(<DnsMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.example.com')
    // Takım hücresindeki grup rozeti metni görünür
    expect(screen.getByText('X Sistemleri')).not.toBeNull()
  })

  it('Düzenle: domain editable (readonly değil) + Test butonu testDnsMonitor çağırır', async () => {
    api.monitoring.testDnsMonitor.mockResolvedValue({ success: true, data: {
      success: true, host: 'www.example.com', values: ['1.2.3.4'], ttl: 300, response_ms: 20, slow: false, unexpected: [],
    } })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.example.com')

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
    await screen.findByText('www.example.com')

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
    await screen.findByText('www.example.com')

    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    const checkbox = screen.getByRole('checkbox', { name: /dns değişikliği alarmı|dns change alarm/i })
    expect(checkbox.checked).toBe(false)
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'example', domain: 'www.example.com', record_type: 'CNAME', standalone: true,
      team_id: 5, team_name: 'SY-A', value: '1.2.3.4', ttl: 300, checked_at: '2026-07-06T00:00:00',
      interval_seconds: 900, group_name: 'Kurumsal',
      expected_value: '1.2.3.4\n5.6.7.8', slow_threshold_ms: 2500,
      propagation_check: true, dns_change_alert_enabled: false, active: false,
      notification_group_id: 7,
    }] })
    api.monitoring.createDnsMonitor.mockResolvedValue({ success: true, data: {} })

    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.example.com')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    // Ad "(Kopya)" sonekli — ad input'unun placeholder'ı form.domain'dir
    expect(screen.getByPlaceholderText('www.example.com').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDnsMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateDnsMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createDnsMonitor.mock.calls[0][0]).toEqual({
      name: 'example (Kopya)', domain: 'www.example.com', recordType: 'CNAME',
      intervalSeconds: 900, teamId: 5, groupName: 'Kurumsal',
      expectedValue: '1.2.3.4\n5.6.7.8', slowThresholdMs: 2500,
      propagationCheck: true, dnsChangeAlertEnabled: false,
      // B1: e-posta kanal bayragi DNS formuna eklendi (eskiden bu turde HIC yoktu).
      // "Tum ayarlar birebir kopyalanir" iddiasi degismedi; kume bir alan buyudu.
      notifyEmail: true, notifyWebhook: true,
      // B3: dogrulama/kurtarma alanlari bu iki ture eklendi (eskiden yalniz global ayar vardi).
      // "TUM ayarlar birebir kopyalanir" iddiasi degismedi; kume dort alan buyudu.
      confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitmeye devam etsin.
      notificationGroupId: 7,
    })
  })

  it('Kopyala → değiştirmeden kaydet: backend mükerrer hatası toast ile gösterilir, modal kapanmaz', async () => {
    api.monitoring.createDnsMonitor.mockResolvedValue({
      success: false,
      error: 'Bu (domain, kayıt tipi) için zaten bir izleme var; mükerrer DNS monitörü oluşturulamaz.',
    })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.example.com')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createDnsMonitor).toHaveBeenCalled())
    expect(await screen.findByText(/zaten bir izleme var/i)).toBeInTheDocument()
    // Modal açık kalır (veri kaybı yok) → Kopya rozeti hâlâ DOM'da
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
  })

  it('Düzenle: "listeye ekle" butonu mevcut değeri beklenen listeye EKLER (üzerine yazmaz, dedupe)', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, expected_value: '217.169.196.197', value: '192.168.1.10' },
    ] })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('www.example.com')

    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    const addBtn = screen.getByRole('button', { name: /^şu anki değeri listeye ekle$|^add current value to list$/i })
    fireEvent.click(addBtn)
    const textarea = screen.getByPlaceholderText(/beklenen değer|expected value/i)
    expect(textarea.value).toBe('217.169.196.197\n192.168.1.10')
    // İkinci tık: aynı değer tekrar eklenmez (dedupe)
    fireEvent.click(addBtn)
    expect(textarea.value).toBe('217.169.196.197\n192.168.1.10')
  })

  it('sayfalama: 120 kayıt → 50 satır + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, domain: `d${i + 1}.example.com` }))
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('d1.example.com')
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('d51.example.com')
    expect(screen.queryByText('d1.example.com')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('d1.example.com')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  // -- Istatistik kartlari filtreyi IZLER (O16) -------------------------------
  // Kartlar ham `monitors` uzerinden sayiliyordu: kullanici takim secince / arama yazinca liste
  // daraliyor ama kartlar KURESEL sayiyi gostermeye devam ediyordu. Kartlar ayni zamanda filtre
  // dugmesi oldugu icin tutarsizdi ("alarm 5" tikla -> 2 sonuc gel).

  const threeMonitors = () => api.monitoring.getDnsMonitors.mockResolvedValue({
    success: true,
    data: [
      { ...monitor, id: 1, domain: 'alfa.example.com' },
      { ...monitor, id: 2, domain: 'beta.example.com' },
      { ...monitor, id: 3, domain: 'gama.example.com', team_id: 9, team_name: 'SY-B', active_alarm: true },
    ],
  })

  async function openStats(container) {
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await screen.findByText('alfa.example.com')
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    return () => container.querySelector('.stat-value-total')?.textContent
  }

  it('istatistik kartlari ARAMA ile daralir (kuresel sayida donup kalmaz)', async () => {
    threeMonitors()
    const { container } = render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const totalText = await openStats(container)

    expect(totalText()).toBe('3')

    fireEvent.change(container.querySelector('.dns-search-input'), { target: { value: 'alfa' } })
    await waitFor(() => expect(totalText()).toBe('1'))

    // Alarm sayaci da kapsamdan gelir: alarmli monitor arama disinda kaldi.
    expect(container.querySelector('.stat-value-critical')?.textContent).toBe('0')
  })

  it('arama HICBIR seyi eslestirmese bile istatistik seridi CIZILMEYE devam eder', async () => {
    // Regresyon kapisi: MonitorStatsSection `total` prop'u FILTRE ONCESI sayidir ve yalnizca
    // seridin cizilip cizilmeyecegine karar verir. Oraya kapsam sayisi verilseydi bos sonucta
    // serit tamamen kaybolur ve kullanici filtreyi seritten TEMIZLEYEMEZDI.
    threeMonitors()
    const { container } = render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const totalText = await openStats(container)

    fireEvent.change(container.querySelector('.dns-search-input'), { target: { value: 'hicbiryerde-yok' } })

    await waitFor(() => expect(totalText()).toBe('0'))
    expect(container.querySelector('.stats-collapse-bar')).not.toBeNull()
    expect(container.querySelector('.stats-panel')).not.toBeNull()
  })

  /**
   * DNS artik kardesleriyle AYNI kurali kullaniyor: kendi takiminin izlemesini TEAM_ADMIN de
   * kontrol edebilir.
   *
   * Bu test eskiden TERSINI pinliyordu ("yalniz yoneticide cikar") ve o haliyle KUSURU SOZLESME
   * haline getirmisti. Gerekce olarak triggerDns'in requireAdmin cagirdigi yaziliydi -- ama o
   * cagri dokuz izleme turunun TEK istisnasiydi ve hicbir yerde gerekcesi yoktu. Kullaniciya
   * yansimasi suydu: envanter-turevi DNS kartinda butun dugmeler kayboluyor, takim yoneticisi
   * bunu bir yetki kurali degil ARIZA saniyordu. Uc de arayuz de kardeslere hizalandi
   * (MonitoringController.triggerDns/updateDns/deleteDns -> canOperateTeam).
   */
  it('toplu kontrol dugmesi KENDI TAKIMININ satirlari icin takim yoneticisinde de cikar', async () => {
    const { unmount } = render(<DnsMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    expect(screen.getByTitle(/bu sayfadaki 1 izlemenin|check all 1 monitors/i)).toBeInTheDocument()
    unmount()

    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalledTimes(2))
    expect(screen.getByTitle(/bu sayfadaki 1 izlemenin|check all 1 monitors/i)).toBeInTheDocument()
  })

  /**
   * ASIL KULLANICI SIKAYETI: "DNS'te kartin sag alt kosesindeki dugmeleri goremiyorum."
   * Envanter-turevi satir (standalone=false) takimini ENVANTERDEN alir; eski kapi
   * `m.standalone && isOwnTeam(m)` oldugu icin o satirlarda MonitorCardActions hic cizilmiyordu.
   */
  it('ENVANTER-TUREVI satirda da kart dugmeleri cizilir (standalone=false)', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValueOnce({
      success: true,
      data: [{ id: 1, name: 'internetsubesi', domain: 'internetsubesi.example.com',
               record_type: 'A', team_id: 5, standalone: false, active: true }],
    })
    const { container } = render(<DnsMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDnsMonitors).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelector('.mon-actions')).not.toBeNull())
  })
})
