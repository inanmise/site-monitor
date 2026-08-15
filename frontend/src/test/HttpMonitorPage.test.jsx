import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getCheckHistory:    vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createHttpMonitor: vi.fn(),
      updateHttpMonitor: vi.fn(),
      deleteHttpMonitor: vi.fn(),
      triggerHttpCheck:  vi.fn(),
      testHttp:          vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Akbank', url: 'https://www.akbank.com/', method: 'GET', expected_status: '201-204',
  group_name: 'X Sistemleri', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 12,
  interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('HttpMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { http: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.createHttpMonitor.mockResolvedValue({ success: true, data: {} })
  })

  it('izleme kartını (url) listeler', async () => {
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.akbank.com/')).toBeInTheDocument()
  })

  // ── Şemasız URL sahte alarmı (2026-08-04): giriş normalizasyonu ────────────
  it('URL alanı: şemasız girdi https:// ile tamamlanır, http:// korunur; Kaydet normalize URL gönderir', async () => {
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)   // USER → takım otomatik dolar
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))
    const url = screen.getByPlaceholderText('https://example.com')

    fireEvent.change(url, { target: { value: 'http://internal.host:8080/health' } })
    fireEvent.blur(url)
    expect(url.value).toBe('http://internal.host:8080/health')   // bilinçli http:// tercihi korunur

    fireEvent.change(url, { target: { value: 'www.axess.com.tr' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))   // alandan çıkmadan kaydet
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.createHttpMonitor.mock.calls[0][0].url).toBe('https://www.axess.com.tr')
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse veya save() içinde
    // sessizce varsayılana dönerse tam-payload karşılaştırması kırılır.
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'Akbank', url: 'https://www.akbank.com/', status: 'up', checked_at: '2026-06-24T00:00:00',
      method: 'POST', expected_status: '201-204', follow_redirects: false, verify_ssl: true,
      group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A', tags: 'prod,kritik', notify_email: false,
      check_ssl_errors: true, ssl_expiry_reminders: true, domain_expiry_reminders: true,
      ssl_reminder_days: '45,20,5', domain_reminder_days: '60,30,10',
      interval_seconds: 600, timeout_ms: 7000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false,
    }] })

    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.akbank.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('https://www.akbank.com/').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateHttpMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createHttpMonitor.mock.calls[0][0]).toEqual({
      name: 'Akbank (Kopya)', url: 'https://www.akbank.com/', method: 'POST',
      expectedStatus: '201-204', followRedirects: false, verifySsl: true,
      groupName: 'Kurumsal', teamId: 5, tags: 'prod,kritik', notifyEmail: false,
      checkSslErrors: true, sslExpiryReminders: true, domainExpiryReminders: true,
      sslReminderDays: '45,20,5', domainReminderDays: '60,30,10',
      intervalSeconds: 600, timeoutMs: 7000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
    })
  })

  it('Kopyala → değiştirmeden kaydet: backend "zaten izleniyor" hatası toast ile gösterilir, modal kapanmaz', async () => {
    api.monitoring.createHttpMonitor.mockResolvedValue({
      success: false,
      error: 'Bu URL bu takımda zaten izleniyor; mükerrer HTTP monitörü oluşturulamaz.',
    })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.akbank.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(await screen.findByText(/zaten izleniyor/i)).toBeInTheDocument()
    // Modal açık kalır (veri kaybı yok) → Kopya rozeti hâlâ DOM'da
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
  })

  it('deep-link regresyonu: ?monitor= SON sayfadaki kayda işaret ederken modal yine açılır', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: many })
    window.history.replaceState({}, '', '/?monitor=120')   // 3. sayfadaki kayıt
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
      // Detay modalı listede görünürlüğe bağlı DEĞİL — ham monitors.find ile açılır.
      expect(await screen.findByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
      expect(screen.getAllByText('https://m120.example.com/').length).toBeGreaterThan(0)
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://m1.example.com/')
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('https://m51.example.com/')
    expect(screen.queryByText('https://m1.example.com/')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('https://m1.example.com/')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('eski e-posta formatı ?tab=http&monitor=1 modal açar; param artık URL DE KALIR (yeni davranış)', async () => {
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      expect(await screen.findByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()   // modal açık
      await waitFor(() => expect(window.location.search).toContain('monitor=1'), { timeout: 1500 })
    } finally { window.history.replaceState({}, '', '/') }
  })

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerHttpCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())

      const runBtn = document.querySelector('.mon-btn-check')
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerHttpCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  it('API hata dönünce "izleme yok" DEĞİL, hata bandı gösterir (silindi sanma bekçisi)', async () => {
    // Eskiden load()'da else dalı yoktu: liste bos kalip "Henüz izleme yok, ekleyin" cikiyordu.
    // Kullanici monitorlerinin silindigini saniyordu; ustelik 60 sn'lik polling sessizce basarisiz
    // olmaya devam ediyordu.
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: false, error: 'HTTP 503 — servis kullanilamiyor' })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 503/)).toBeInTheDocument()
    expect(screen.queryByText(/no monitors|henüz izleme/i)).toBeNull()
  })

  it('gercekten bos liste hata DEGIL, bos durum gosterir', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [] })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
