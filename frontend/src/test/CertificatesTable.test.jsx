import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'

// api istemcisini mock'la — component mount'ta getCertificatesPaginated çağırır.
// Durum sınıfları (status-valid/critical/error) dilden BAĞIMSIZ CSS sınıfı → i18n metnine bağlanmadan doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ getCertificatesPaginated: vi.fn() }),
  formatDate: (s) => s || 'N/A',
}))

import { api } from '../api/client'

function paged(data) {
  return { success: true, data, pagination: { current_page: 1, total: data.length, total_pages: 1 } }
}

const cert = (over) => ({
  domain: 'x.com', issuer_cn: 'Test CA', subject: 'CN=x', not_after: '2027-01-01T00:00:00',
  days_remaining: 200, warning: false, status: 'valid', checked_at: '2026-07-01T00:00:00', ...over,
})

describe('CertificatesTable', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sertifika satırlarını alan adı + duruma göre doğru CSS sınıfıyla gösterir', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      // Hüküm SUNUCUDAN gelir (alert_level). Tablo eskiden kendi sabit 30 gün merdivenini
      // kullanıyordu: days=10 burada "Kritik", kartta "Yüksek" görünüyordu. Artık iki ekran
      // aynı hükmü okuyor; days=10 varsayılan eşiklerde (kritik<=7, yüksek<=15) YÜKSEK'tir.
      cert({ domain: 'valid.com', days_remaining: 200, warning: false, status: 'valid', alert_level: 'valid' }),
      cert({ domain: 'crit.com', days_remaining: 3, warning: true, status: 'warning', alert_level: 'critical' }),
      cert({ domain: 'err.com', days_remaining: null, warning: true, status: 'error', alert_level: 'error' }),
    ]))

    const { container } = render(<CertificatesTable onRowClick={() => {}} />)

    await screen.findByText('valid.com')
    expect(screen.getByText('crit.com')).toBeInTheDocument()
    expect(screen.getByText('err.com')).toBeInTheDocument()

    // alert_level=critical → KRİTİK sınıfı (valid/warning değil)
    expect(container.querySelector('tr[data-domain="crit.com"] .status-critical')).not.toBeNull()
    expect(container.querySelector('tr[data-domain="valid.com"] .status-valid')).not.toBeNull()
    expect(container.querySelector('tr[data-domain="err.com"] .status-error')).not.toBeNull()
  })

  it('satıra tıklayınca onRowClick alan adıyla çağrılır', async () => {
    const onRowClick = vi.fn()
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'click.com' })]))

    const { container } = render(<CertificatesTable onRowClick={onRowClick} />)
    await screen.findByText('click.com')

    fireEvent.click(container.querySelector('tr[data-domain="click.com"]'))
    expect(onRowClick).toHaveBeenCalledWith('click.com')
  })

  it('boş veri → hiç sertifika satırı çizilmez (boş durum)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([]))

    const { container } = render(<CertificatesTable onRowClick={() => {}} />)

    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelectorAll('tr[data-domain]')).toHaveLength(0))
  })
  // ── Denetim 5. tur, bulgu 20: tablo ile kart AYNI hükmü okur ───────────────
  //
  // Tablo sabit `days >= 0 && days <= 30` kullanıyordu. days<0 (SÜRESİ DOLMUŞ) bu koşula
  // takılmadığı için satır "Uyarı" görünüyordu ve tabloda "Süresi doldu" durumu HİÇ yoktu.

  it('süresi DOLMUŞ sertifika "Süresi doldu" olarak gösterilir (eskiden "Uyarı" görünüyordu)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      cert({ domain: 'expired.com', days_remaining: -5, warning: true, status: 'valid', alert_level: 'expired' }),
    ]))
    const { container } = render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('expired.com')

    expect(container.querySelector('tr[data-domain="expired.com"] .status-critical')).not.toBeNull()
    // Satirin KENDI hucresinde yazmali (sutun basligi/filtre metniyle karistirma).
    const row = container.querySelector('tr[data-domain="expired.com"]')
    expect(row.textContent).toMatch(/Süresi doldu|Expired/i)
  })

  it('sunucu hükmü YOKSA satır çökmez; süre bilgisinden makul bir duruma düşer', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([
      cert({ domain: 'eski.com', days_remaining: -1, warning: true, status: 'valid' }),   // alert_level YOK
    ]))
    const { container } = render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('eski.com')
    expect(container.querySelector('tr[data-domain="eski.com"] .status-critical')).not.toBeNull()
  })

  it('alan adı süzgeci tuş başına değil, 300 ms sessizlikten sonra TEK istek atar (fetch yarışı yok)', async () => {
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'a.com' })]))
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('a.com')
    api.getCertificatesPaginated.mockClear()

    const input = screen.getByPlaceholderText(/domain/i)
    fireEvent.change(input, { target: { value: 'b' } })
    fireEvent.change(input, { target: { value: 'ba' } })
    fireEvent.change(input, { target: { value: 'ban' } })

    await waitFor(() => expect(api.getCertificatesPaginated).toHaveBeenCalledWith(
      expect.objectContaining({ filter_domain: 'ban' })))
    // Ara tuş vuruşları ("b", "ba") sunucuya HİÇ gitmedi.
    expect(api.getCertificatesPaginated.mock.calls.map(c => c[0].filter_domain)).toEqual(['ban'])
  })
})
describe('CertificatesTable — sütun seçici + kayıtlı görünüm (2026-09-12, #10)', () => {
  it('varsayılan 7 sütun; "Takım" açılınca başlık gelir ve tercih localStorage\'a yazılır; yeniden render tercihten okur', async () => {
    try { localStorage.removeItem('certtable-view') } catch { /* yok */ }
    api.getCertificatesPaginated.mockResolvedValue(paged([cert({ domain: 'col.example.com', team_name: 'Takım A', team_id: 1, public_key_algorithm: 'RSA', public_key_size: 2048 })]))
    const { unmount } = render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(document.querySelector('tr[data-domain="col.example.com"]')).toBeTruthy())
    expect(document.querySelectorAll('thead th').length).toBe(7)
    fireEvent.click(screen.getByRole('button', { name: /Sütunlar|Columns/ }))
    fireEvent.click(screen.getByLabelText(/^(Takım|Team)$/))
    expect(document.querySelectorAll('thead th').length).toBe(8)
    expect(JSON.parse(localStorage.getItem('certtable-view')).cols).toContain('team')
    unmount()
    render(<CertificatesTable onRowClick={() => {}} />)
    await waitFor(() => expect(document.querySelector('tr[data-domain="col.example.com"]')).toBeTruthy())
    expect(document.querySelectorAll('thead th').length).toBe(8)
    expect(document.body.textContent).toContain('Takım A')
    try { localStorage.removeItem('certtable-view') } catch { /* yok */ }
  })
})
