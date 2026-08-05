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
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })   // ADMIN akışı (Kopyala) takım listesi ister
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

  // ── Şemasız URL sahte alarmı (2026-08-04): giriş normalizasyonu ────────────
  it('URL alanı: şemasız girdi https:// ile tamamlanır; {timestamp} bozulmaz; Kaydet normalize URL gönderir', async () => {
    api.monitoring.createKeywordMonitor.mockResolvedValue({ success: true, data: {} })
    render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)   // USER → takım otomatik dolar
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitor|yeni monitör/i }))
    const url = screen.getByPlaceholderText('https://example.com')

    // Cache-busting yer tutucusu ayrıştırıcıyı patlatmamalı (backend URI.create kullanmıyor).
    fireEvent.change(url, { target: { value: 'x.example.com/a?t={timestamp}' } })
    fireEvent.blur(url)
    expect(url.value).toBe('https://x.example.com/a?t={timestamp}')

    fireEvent.change(screen.getByPlaceholderText('SUCCESS'), { target: { value: 'akbank' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.createKeywordMonitor.mock.calls[0][0].url).toBe('https://x.example.com/a?t={timestamp}')
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'Akbank', url: 'https://www.akbank.com/', keyword: 'akbank', status: 'up',
      checked_at: '2026-06-24T00:00:00',
      operator: 'LTE', match_count: 4, group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A',
      case_sensitive: true, tags: 'prod,kritik', notify_email: false,
      check_ssl_errors: true, ssl_expiry_reminders: true, domain_expiry_reminders: true,
      ssl_reminder_days: '45,20,5', domain_reminder_days: '60,30,10',
      slow_response_enabled: true, slow_threshold_ms: 4500,
      interval_seconds: 900, timeout_ms: 8000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      custom_headers: 'X-Api-Key: abc', active: false,
    }] })
    api.monitoring.createKeywordMonitor.mockResolvedValue({ success: true, data: {} })

    render(<KeywordMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.akbank.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('https://www.akbank.com/').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createKeywordMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateKeywordMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createKeywordMonitor.mock.calls[0][0]).toEqual({
      name: 'Akbank (Kopya)', url: 'https://www.akbank.com/', keyword: 'akbank',
      operator: 'LTE', matchCount: 4, groupName: 'Kurumsal', teamId: 5,
      caseSensitive: true, tags: 'prod,kritik', notifyEmail: false,
      checkSslErrors: true, sslExpiryReminders: true, domainExpiryReminders: true,
      sslReminderDays: '45,20,5', domainReminderDays: '60,30,10',
      slowResponseEnabled: true, slowThresholdMs: 4500,
      intervalSeconds: 900, timeoutMs: 8000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      customHeaders: 'X-Api-Key: abc',
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
    })
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

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<KeywordMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getKeywordMonitors).toHaveBeenCalled())
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
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<KeywordMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('https://m1.example.com/')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  // ── Paylaşılabilir URL (deep-link) senaryoları ─────────────────────────────
  it('URL→ekran: ?group= ile mount → yalnız o grubun monitörleri render olur', async () => {
    localStorage.clear()
    window.history.replaceState({}, '', '/?tab=keyword&group=G2')
    try {
      api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [
        { ...monitor, id: 1, url: 'https://a.example.com/', group_name: 'G1' },
        { ...monitor, id: 2, url: 'https://b.example.com/', group_name: 'G2' },
      ] })
      render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
      await screen.findByText('https://b.example.com/')
      expect(screen.queryByText('https://a.example.com/')).toBeNull()
    } finally { window.history.replaceState({}, '', '/') }
  })

  it('ekran→URL: arama yazınca debounce sonrası q= yazılır; temizlenince silinir', async () => {
    window.history.replaceState({}, '', '/?tab=keyword')
    try {
      render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
      await screen.findByText('https://www.akbank.com/')
      const box = screen.getByPlaceholderText(/ara|search/i)
      fireEvent.change(box, { target: { value: 'akbank' } })
      await waitFor(() => expect(window.location.search).toContain('q=akbank'), { timeout: 1500 })
      fireEvent.change(box, { target: { value: '' } })
      await waitFor(() => expect(window.location.search).not.toContain('q='), { timeout: 1500 })
      expect(window.location.search).toContain('tab=keyword')   // eşleme-dışı param korunur
    } finally { window.history.replaceState({}, '', '/') }
  })

  it('?page=2&ps=50 ile mount (120 kayıt) → 51–100 dilimi; ps localStorage tercihini ezer', async () => {
    localStorage.setItem('cm.pageSize.keyword-monitors', '200')   // link alanın tercihi farklı olsun
    window.history.replaceState({}, '', '/?tab=keyword&page=2&ps=50')
    try {
      const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
      api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: many })
      render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
      await screen.findByText('https://m51.example.com/')
      expect(screen.queryByText('https://m1.example.com/')).toBeNull()
      expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    } finally { window.history.replaceState({}, '', '/'); localStorage.clear() }
  })

  it('monitor kalıcılığı: ?monitor= modal açar ve param URL DE KALIR; kapatınca silinir', async () => {
    window.history.replaceState({}, '', '/?tab=keyword&monitor=1')
    try {
      render(<KeywordMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getKeywordHistory).toHaveBeenCalled())   // modal açıldı
      await waitFor(() => expect(window.location.search).toContain('monitor=1'), { timeout: 1500 })
      fireEvent.click(document.querySelector('.upt-modal-close'))
      await waitFor(() => expect(window.location.search).not.toContain('monitor='), { timeout: 1500 })
    } finally { window.history.replaceState({}, '', '/') }
  })
})
