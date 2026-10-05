import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import { addDays, todayKey } from '../components/domain/detail/domainDetailModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal()),
  api: withApiFallback({
    monitoring: {
      getDomainMonitors: vi.fn(),
      getDomainRegistration: vi.fn(),
      getDomainReminders: vi.fn(),
      triggerDomainCheck: vi.fn(),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getCheckHistory: vi.fn(() => Promise.resolve({ success: true, data: {
        items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [{ id: 3, name: 'Takım A' }] })) },
  }),
}))
import { api } from '../api/client'

/**
 * Alan Adı detay penceresinin sayfa kablolaması (2026-09-28): düz özet satırı yerine DomainDetailHeader; sekmeler ve
 * `mtab` derin bağlantısı çalışmaya devam eder; plan kısayolu paylaşılan RenewalPlanModal'ı açar; Domain Kaydı
 * sekmesinin anlık sorgusu başlığı tazeler; telefonda pencere tam ekran. Tarihler bugüne göre.
 */
const TODAY = todayKey()
const day = (n) => addDays(TODAY, n)
const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)

const monitor = {
  id: 1, name: 'example.com', domain: 'example.com', team_id: 3, team_name: 'Takım A', group_name: 'Kurumsal', tags: 'prod',
  status: 'WARNING', source: 'RDAP', days_remaining: 20, expiry_date: day(20), last_changed: day(-345), registration_date: day(-3000),
  registrar: 'Example Registrar Ltd.', registrar_iana_id: '9999', status_codes: ['clientTransferProhibited'], nameservers: ['ns1.example.com'],
  ns_resolves: true, transfer_lock: 'CLIENT', active: true, interval_seconds: 86400, warning_days: 30, critical_days: 7,
  checked_at: hoursAgo(50),
}

const dialog = () => screen.getByRole('dialog')
const header = () => dialog().querySelector('[data-slot="domain-detail-header"]')

describe('DomainMonitorPage — Alan Adı detay penceresi (yeni başlık)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getDomainRegistration.mockResolvedValue({ success: true, data: monitor })
    api.monitoring.getDomainReminders.mockResolvedValue({ success: true, data: { thresholds: [30, 7], items: [] } })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  async function openFromCard() {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={3} teamName="Takım A" />)
    fireEvent.click(await screen.findByRole('button', { name: /example\.com — (detayları aç|open details)/i }))
    await waitFor(() => expect(header()).not.toBeNull())
  }

  it('detay yeni başlıkla açılır (düz özet satırı YOK); sekmeler çalışır; telefonda tam ekran sınıfı', async () => {
    await openFromCard()
    expect(dialog().querySelector('[data-slot="detail-summary"]')).toBeNull()
    expect(header()).toHaveAttribute('data-tone', 'warning')
    expect(within(header()).getByText('Example Registrar Ltd.')).toBeInTheDocument()
    expect(dialog().closest('[data-slot="dialog-content"]') ?? dialog()).toHaveClass('max-sm:h-dvh')

    const reg = within(dialog()).getByRole('tab', { name: /domain kaydı|registration/i })
    fireEvent.mouseDown(reg, { button: 0 })
    await waitFor(() => expect(reg).toHaveAttribute('aria-selected', 'true'))
    expect(await within(dialog()).findByRole('heading', { level: 3, name: 'Protection' })).toBeInTheDocument()
    const control = within(dialog()).getByRole('tab', { name: /check history|kontrol geçmişi/i })
    fireEvent.mouseDown(control, { button: 0 })
    await waitFor(() => expect(control).toHaveAttribute('aria-selected', 'true'))
    expect(header()).not.toBeNull()   // başlık sekmelerden bağımsız, hep üstte
  })

  it('`?monitor=1&mtab=registration` derin bağlantısı Domain Kaydı sekmesiyle açar', async () => {
    window.history.replaceState({}, '', '/?monitor=1&mtab=registration')
    render(<DomainMonitorPage systemRole="ADMIN" teamId={3} teamName="Takım A" />)
    await waitFor(() => expect(header()).not.toBeNull())
    expect(within(dialog()).getByRole('tab', { name: /domain kaydı|registration/i })).toHaveAttribute('aria-selected', 'true')
    expect(await within(dialog()).findByRole('list', { name: 'Registration timeline' })).toBeInTheDocument()
  })

  it('plan kısayolu (eşik içinde) paylaşılan yenileme planı penceresini detayın ÜSTÜNDE açar; başlık çubuğunda ikinci plan düğmesi yok', async () => {
    await openFromCard()
    expect(within(dialog()).getAllByRole('button', { name: /plan renewal/i })).toHaveLength(1)
    fireEvent.click(within(header()).getByRole('button', { name: 'example.com — Plan renewal' }))
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2))
  })

  it('Domain Kaydı anlık sorgusu taze satırı başlığa işler (son kontrol + kalan gün)', async () => {
    const fresh = { ...monitor, days_remaining: 19, checked_at: hoursAgo(0) }
    api.monitoring.getDomainRegistration.mockResolvedValue({ success: true, data: fresh })
    await openFromCard()
    expect(header().querySelector('[data-slot="domain-days"]')).toHaveTextContent('20')
    fireEvent.mouseDown(within(dialog()).getByRole('tab', { name: /domain kaydı|registration/i }), { button: 0 })
    // K-1 (2026-09-29): sekmeyi açmak canlı sorgu yapmaz — kullanıcı "Yenile"ye basar.
    fireEvent.click(await within(dialog()).findByRole('button', { name: /^(Yenile|Refresh)$/ }))
    await waitFor(() => expect(header().querySelector('[data-slot="domain-days"]')).toHaveTextContent('19'))
    expect(header().querySelector('[data-slot="domain-detail-checked"]')).toHaveTextContent('just now')
  })

  it('başlıktaki "Şimdi kontrol et" sayfanın kontrol yolunu çalıştırır ve sonucu başlığa işler', async () => {
    api.monitoring.triggerDomainCheck.mockResolvedValue({ success: true, data: { ...monitor, days_remaining: 18, status: 'WARNING' } })
    await openFromCard()
    fireEvent.click(within(header()).getByRole('button', { name: 'example.com — Check now' }))
    await waitFor(() => expect(api.monitoring.triggerDomainCheck).toHaveBeenCalledWith(1))
    await waitFor(() => expect(header().querySelector('[data-slot="domain-days"]')).toHaveTextContent('18'))
  })

  it('bitişi bilinmeyen izlemede yönetici başlıktan Sorun Tanıla açar (detayın üstünde ikinci pencere)', async () => {
    // Sorun Tanıla 2026-10-05'ten beri rol değil satırın `can_diagnose` bayrağıyla çizilir
    const unknown = { ...monitor, status: 'UNKNOWN', days_remaining: null, expiry_date: null, error: 'RDAP: 404 Not Found', can_diagnose: true }
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [unknown] })
    api.admin.runDomainExpiryDiagnostics.mockResolvedValue({ success: false, error: 'tanı ucu yanıt vermedi' })
    await openFromCard()
    expect(header().querySelector('[data-slot="domain-detail-unknown"]')).toHaveAttribute('data-reason', 'error')
    fireEvent.click(within(header()).getByRole('button', { name: 'example.com — Diagnose' }))
    expect(await screen.findByText('tanı ucu yanıt vermedi')).toBeInTheDocument()
    expect(api.admin.runDomainExpiryDiagnostics).toHaveBeenCalledWith('example.com')
    expect(screen.getAllByRole('dialog')).toHaveLength(2)
  })

  it('salt görüntüleyen (VIEWER): başlıkta plan / kontrol / tanıla eylemi yok', async () => {
    render(<DomainMonitorPage systemRole="VIEWER" teamId={9} teamName="Takım B" />)
    fireEvent.click(await screen.findByRole('button', { name: /example\.com — (detayları aç|open details)/i }))
    await waitFor(() => expect(header()).not.toBeNull())
    expect(within(header()).queryByRole('button', { name: /plan renewal|check now|diagnose|edit plan/i })).toBeNull()
  })
})

/**
 * Kontrol geçmişi hata teşhisi (2026-10-05): veri getirilemeyen (UNKNOWN) satırın hatası artık registrar hücresinde
 * kırpılıp SAKLANMAZ — satırın altında KENDİ satırı (neden rozeti + tek satır + aç/kapa); açılınca panel kaynak iletisini,
 * RDAP HTTP durumunu ve WHOIS denemesini yazar. Veri gelen (OK/WARNING/CRITICAL) satırda blok yok.
 */
describe('DomainMonitorPage — kontrol geçmişi hata teşhisi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getDomainRegistration.mockResolvedValue({ success: true, data: monitor })
    api.monitoring.getDomainReminders.mockResolvedValue({ success: true, data: { thresholds: [30, 7], items: [] } })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [
        { id: 51, monitor_id: 1, status: 'UNKNOWN', source: 'NONE', registrar: null, error: 'rdap http 404',
          checked_at: hoursAgo(2), failure_reason: 'RDAP_NOT_FOUND',
          failure_detail: JSON.stringify({ phase: 'REGISTRY', http_status: 404, message: 'rdap http 404', registry_rdap: true,
            target: 'example.com', source: 'NONE' }) },
        { id: 50, monitor_id: 1, status: 'WARNING', source: 'RDAP', registrar: 'Example Registrar Ltd.', days_remaining: 20,
          expiry_date: day(20), checked_at: hoursAgo(26) },
      ], counts: { total: 2, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-09-05T00:00:00', to: '2026-10-05T23:59:59' }, total: 2, page: 0, size: 50 } })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('UNKNOWN satırı kendi hata satırını taşır (registrar hücresinde ham hata YOK); aç → RDAP 404 ayrıntısı', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={3} teamName="Takım A" />)
    fireEvent.click(await screen.findByRole('button', { name: /example\.com — (detayları aç|open details)/i }))
    const d = await screen.findByRole('dialog')
    const block = await waitFor(() => { const b = d.querySelector('[data-slot="chkfail-block"]'); expect(b).not.toBeNull(); return b })
    expect(d.querySelectorAll('[data-slot="chkfail-block"]')).toHaveLength(1)
    expect(block.querySelector('[data-slot="chkfail-cell"]')).toHaveAttribute('data-code', 'RDAP_NOT_FOUND')
    expect(within(d.querySelector('[data-slot="check-history"]')).queryByText('rdap http 404')).toBeNull()   // kırpılmış hücrede saklanmıyor
    fireEvent.click(block.querySelector('[data-slot="chkfail-toggle"]'))
    const panel = await within(d).findByRole('region', { name: /failure detail|hata ayrıntısı/i })
    expect(panel.querySelector('[data-key="httpStatus"]').textContent).toBe('HTTP 404')
    expect(panel.querySelector('[data-key="registryRdap"]').textContent).toMatch(/^(Yes|Var)$/)
    expect(panel.querySelector('[data-slot="chkfail-technical"]').textContent).toContain('rdap http 404')
  })
})
