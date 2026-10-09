import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import DnsDetailModal from '../components/DnsDetailModal.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:      vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDnsMonitors:  vi.fn(),
      getDnsDetails:   vi.fn(),
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      getDnsResponseSeries: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      triggerDnsCheck: vi.fn(),
    },
    admin: {
      getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getAlertHistory: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
  }),
}))
import { api } from '../api/client'

/**
 * DNS detayında "Şimdi kontrol et" Kontrol sekmesini SIFIRLAMAZ (2026-10-09, doğrulanmış hata). Ayrıntı efekti `monitor`
 * NESNESİNE bağlıydı: sayfa açık pencerenin kopyasını değiştirince (Şimdi kontrol et / Sürdür / kayıt) efekt yeniden
 * koşuyor, kayıt türü alt sekmesini izlenen türe döndürüyor ve "yükleniyor" göstergesine geçerek Kontrol Geçmişi'ni
 * SÖKÜYORDU — seçilen süzgeç / aralık / sayfa kayboluyordu. Artık alt sekme yalnız izleme ya da kayıt türü değişince
 * döner; gösterge yalnız bu izlemenin ilk ayrıntısı gelene kadar çizilir, yeniden okuma sessizdir.
 */
const monitor = {
  id: 7, name: 'iyi', domain: 'www.example.com', record_type: 'A', standalone: true, team_id: 5, team_name: 'SY-A',
  value: '192.0.2.10', active: true, checked_at: '2026-10-09T09:00:00', group_name: 'G', tags: 'prod',
}
const details = (over = {}) => ({
  records: {
    A: { values: ['192.0.2.10'], ttl: 300, response_ms: 4, success: true },
    MX: { values: ['10 mx.example.com'], ttl: 300, response_ms: 5, success: true },
  },
  soa: { success: false }, authoritative_servers: [], monitor, slow_threshold_ms: null, ...over,
})
const envelope = { success: true, data: {
  items: [{ id: 1, monitor_id: 7, record_type: 'A', value: '192.0.2.10', changed: true, rotated: false,
    previous_value: '192.0.2.9', checked_at: '2026-10-09T08:00:00', ttl: 300, response_ms: 4 }],
  counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
  range: { from: '2026-10-08T00:00:00', to: '2026-10-09T23:59:59' }, total: 1, page: 0, size: 50 } }
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const LOADING = /loading details/i
const mxTab = () => screen.getByRole('tab', { name: /^MX\s*1$/ })
const histTiles = () => document.querySelector('[data-slot="hist-tiles"]')
const lastHistStatus = () => api.monitoring.getCheckHistory.mock.calls.at(-1)[2].status

describe('DnsDetailModal — kopya değişince Kontrol sekmesi sıfırlanmaz', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: details() })
    api.monitoring.getCheckHistory.mockResolvedValue(envelope)
  })

  it('yeni kopya (aynı izleme) + geçmiş sinyali: alt sekme, geçmiş süzgeci ve geçmiş bileşeni KALIR; ayrıntı sessizce yeniden okunur', async () => {
    const { rerender } = render(<DnsDetailModal monitor={monitor} onClose={() => {}} histReload={0} />)
    fireEvent.mouseDown(await screen.findByRole('tab', { name: /^MX\s*1$/ }), { button: 0 })
    await waitFor(() => expect(mxTab()).toHaveAttribute('aria-selected', 'true'))
    fireEvent.click(await screen.findByRole('button', { name: /changed/i }))
    await waitFor(() => expect(lastHistStatus()).toBe('changed'))
    const tilesBefore = histTiles()
    expect(tilesBefore).not.toBeNull()

    // Sayfa "Şimdi kontrol et" yanıtıyla kopyayı DEĞİŞTİRİR (yeni nesne) ve geçmiş sinyalini artırır
    const pending = deferred()
    api.monitoring.getDnsDetails.mockImplementation(() => pending.p)
    rerender(<DnsDetailModal monitor={{ ...monitor, checked_at: '2026-10-09T09:05:00' }} onClose={() => {}} histReload={1} />)
    await waitFor(() => expect(api.monitoring.getDnsDetails).toHaveBeenCalledTimes(2))   // canlı kayıtlar tazelenir
    expect(screen.queryByText(LOADING)).toBeNull()        // içerik sökülmez, gösterge yok
    expect(histTiles()).toBe(tilesBefore)                 // Kontrol Geçmişi yeniden kurulmadı
    expect(mxTab()).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(lastHistStatus()).toBe('changed'))   // geçmiş sinyalle yeniden okundu, süzgeç korundu

    await act(async () => { pending.resolve({ success: true, data: details({ records: {
      A: { values: ['192.0.2.11'], ttl: 60, response_ms: 3, success: true },
      MX: { values: ['10 mx.example.com'], ttl: 300, response_ms: 5, success: true },
    } }) }) })
    expect(await screen.findByText('60s')).toBeInTheDocument()   // özet yeni yanıtla
    expect(histTiles()).toBe(tilesBefore)
    expect(mxTab()).toHaveAttribute('aria-selected', 'true')
  })

  it('yeniden okuma düşerse eldeki ayrıntı ve geçmiş kalır; hata üstte söylenir', async () => {
    const { rerender } = render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await screen.findByRole('tab', { name: /^MX\s*1$/ })
    const tilesBefore = await waitFor(() => { const el = histTiles(); if (!el) throw new Error('yok'); return el })
    api.monitoring.getDnsDetails.mockRejectedValueOnce(new Error('ağ koptu'))
    rerender(<DnsDetailModal monitor={{ ...monitor }} onClose={() => {}} />)
    expect(await screen.findByText('ağ koptu')).toBeInTheDocument()
    expect(histTiles()).toBe(tilesBefore)
    expect(screen.getByRole('tab', { name: /^MX\s*1$/ })).toBeInTheDocument()
  })

  it('kayıt türü değişince alt sekme yeni türe döner; BAŞKA izleme açılınca eskinin geç yanıtı çizilmez', async () => {
    const { rerender } = render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    await waitFor(() => expect(screen.getByRole('tab', { name: /^A\s*1$/ })).toHaveAttribute('aria-selected', 'true'))
    rerender(<DnsDetailModal monitor={{ ...monitor, record_type: 'MX' }} onClose={() => {}} />)
    await waitFor(() => expect(mxTab()).toHaveAttribute('aria-selected', 'true'))

    // Başka izleme: ilk ayrıntısı gelene kadar gösterge; önceki izlemenin geç yanıtı yok sayılır
    const late = deferred()
    const other = deferred()
    api.monitoring.getDnsDetails.mockImplementationOnce(() => late.p).mockImplementationOnce(() => other.p)
    rerender(<DnsDetailModal monitor={{ ...monitor, checked_at: 'x' }} onClose={() => {}} />)
    rerender(<DnsDetailModal monitor={{ ...monitor, id: 8, domain: 'b.example.com' }} onClose={() => {}} />)
    expect(await screen.findByText(LOADING)).toBeInTheDocument()
    await act(async () => { late.resolve({ success: true, data: details() }) })
    expect(screen.getByText(LOADING)).toBeInTheDocument()   // 7'nin yanıtı 8'in penceresini doldurmadı
    await act(async () => { other.resolve({ success: true, data: details({ records: { A: { values: ['198.51.100.1'], ttl: 77, response_ms: 1, success: true } } }) }) })
    await waitFor(() => expect(screen.queryByText(LOADING)).toBeNull())
    expect(screen.getByText('77s')).toBeInTheDocument()
  })

  it('canlı durum arayüz dilinde ve simgeyle (eski sabit "✓ SUCCESS" / "✗ ERROR" yok)', async () => {
    const { rerender } = render(<DnsDetailModal monitor={monitor} onClose={() => {}} />)
    const ok = await waitFor(() => { const el = document.querySelector('[data-slot="dns-live-status"]'); if (!el) throw new Error('yok'); return el })
    expect(ok).toHaveAttribute('data-ok', 'true')
    expect(ok).toHaveTextContent('Succeeded')
    expect(ok.querySelector('svg')).not.toBeNull()
    const summary = () => document.querySelector('[data-slot="detail-summary"]').textContent
    expect(summary()).not.toMatch(/SUCCESS|✓|✗/)

    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: details({ records: { A: { values: [], success: false, error: 'SERVFAIL' } } }) })
    rerender(<DnsDetailModal monitor={{ ...monitor }} onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dns-live-status"]')).toHaveAttribute('data-ok', 'false'))
    expect(document.querySelector('[data-slot="dns-live-status"]')).toHaveTextContent('SERVFAIL')

    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: details({ records: {} }) })
    rerender(<DnsDetailModal monitor={{ ...monitor }} onClose={() => {}} />)
    await waitFor(() => expect(document.querySelector('[data-slot="dns-live-status"]')).toHaveTextContent('Lookup failed'))
    expect(summary()).not.toMatch(/ERROR|✗/)
  })
})

describe('DnsMonitorPage — detayda "Şimdi kontrol et" ve arama', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getDnsDetails.mockResolvedValue({ success: true, data: details() })
    api.monitoring.getCheckHistory.mockResolvedValue(envelope)
    api.monitoring.triggerDnsCheck.mockResolvedValue({ success: true, data: { ...monitor, checked_at: '2026-10-09T09:05:00' } })
  })

  it('detaydan "Şimdi kontrol et": alt sekme ve geçmiş süzgeci yerinde kalır, geçmiş yeniden okunur', async () => {
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('www.example.com')
    fireEvent.click(screen.getByRole('button', { name: /www\.example\.com — (detayları aç|open details)/i }))
    const detail = await screen.findByRole('dialog', { name: /www\.example\.com/ })
    fireEvent.mouseDown(await within(detail).findByRole('tab', { name: /^MX\s*1$/ }), { button: 0 })
    await waitFor(() => expect(mxTab()).toHaveAttribute('aria-selected', 'true'))
    fireEvent.click(await within(detail).findByRole('button', { name: /changed/i }))
    await waitFor(() => expect(lastHistStatus()).toBe('changed'))
    const tilesBefore = histTiles()
    const histCalls = api.monitoring.getCheckHistory.mock.calls.length

    fireEvent.click(within(detail).getByRole('button', { name: /^check now$/i }))
    await waitFor(() => expect(api.monitoring.triggerDnsCheck).toHaveBeenCalledWith(7))
    await waitFor(() => expect(api.monitoring.getCheckHistory.mock.calls.length).toBeGreaterThan(histCalls))
    await waitFor(() => expect(api.monitoring.getDnsDetails).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(LOADING)).toBeNull()
    expect(histTiles()).toBe(tilesBefore)
    expect(mxTab()).toHaveAttribute('aria-selected', 'true')
    expect(lastHistStatus()).toBe('changed')
  })

  it('arama metni kırpılır: baştaki/sondaki boşluk eşleşmeyi bozmaz (diğer sekiz sayfa gibi)', async () => {
    api.monitoring.getDnsMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, id: 1, domain: 'alfa.example.com', group_name: '', tags: '' },
      { ...monitor, id: 2, domain: 'beta.example.com', group_name: '', tags: '' },
    ] })
    render(<DnsMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('beta.example.com')
    fireEvent.change(screen.getByRole('textbox', { name: /^(domain veya kayıt tipi ara|search domain or record type)/i }),
      { target: { value: '  alfa.example.com  ' } })
    await waitFor(() => expect(screen.queryByText('beta.example.com')).toBeNull())
    expect(screen.getByText('alfa.example.com')).toBeInTheDocument()
  })
})
