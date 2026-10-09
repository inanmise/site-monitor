import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import CertificatesTable from '../components/CertificatesTable.jsx'

/**
 * Tüm Sertifikalar — "Güven" sütunu (2026-10-09, kullanıcı: "Trust kolonunda süzgeç 'any' ve 'insecure only' sunuyor
 * ama kolonda 'partly verified' var; süzgeç hatalı. Ayrıca neden 'partly verified' olduğu bilinmiyor").
 *  - Sütun süzgeci ve süzgeç çubuğu sütunun DEĞERLERİNİ sunar (Tam / Kısmen doğrulandı / Bilinmiyor / Sorun + türleri)
 *    ve seçim sunucuya `filter_trust` olarak gider.
 *  - Rozete dokununca üç denetimin durumu ve sonuçlanmayanın NEDENİ açılır; satır penceresi açılmaz.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ getCertificatesPaginated: vi.fn() }),
  formatDate: (s) => s || 'N/A',
}))
import { api } from '../api/client'

const FACETS = { all: 3, levels: {}, windows: {}, teams: [], no_team: 0, insecure: 0, tiers: {}, nonstd_port: 0,
  trust: { ok: 1, partial: 1, unknown: 0, bad: 1, chain: 0, untrusted: 1, revoked: 0 } }
const row = (over) => ({
  domain: 'x.example.com', issuer_cn: 'Example CA', subject: 'CN=x', not_after: '2027-01-01T00:00:00', days_remaining: 200,
  warning: false, status: 'valid', alert_level: 'valid', checked_at: '2026-10-09T08:00:00', fingerprint: 'FP', ...over,
})
const ROWS = [
  row({ domain: 'tam.example.com', chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'GOOD' }),
  row({ domain: 'kismen.example.com', chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'UNKNOWN',
    revocation_reason: 'NO_ENDPOINTS' }),
  row({ domain: 'sorun.example.com', chain_status: 'VALID', trust_status: 'UNTRUSTED', revocation_status: 'GOOD' }),
]
const page = (data) => ({ success: true, data, facets: FACETS,
  pagination: { current_page: 1, total: data.length, total_pages: 1 } })
const trustBadge = (domain) => document.querySelector(`tr[data-domain="${domain}"] [data-slot="cert-trust"]`)

describe('CertificatesTable — Güven sütunu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.getCertificatesPaginated.mockImplementation((q) => Promise.resolve(page(q.filter_trust === 'partial' ? [ROWS[1]] : ROWS)))
  })
  afterEach(() => { localStorage.clear(); window.history.replaceState(null, '', '/') })

  it('sütun süzgeci sütunun değerlerini sayılarıyla sunar; "Kısmen doğrulandı" seçimi sunucuya filter_trust=partial gider + çip + adres', async () => {
    localStorage.setItem('certtable-view', JSON.stringify({ colFilters: true }))
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('kismen.example.com')
    const filterRow = document.querySelector('[data-testid="ct-filter-row"]')
    const select = within(filterRow.querySelector('[data-col="trust"]')).getByRole('combobox', { name: /trust|güven/i })
    fireEvent.mouseDown(select)   // SearchableSelect basışla açılır (CertificatesTable.enrich.test ile aynı)
    const names = (await screen.findAllByRole('option')).map((o) => o.textContent.trim())
    expect(names).toEqual(expect.arrayContaining(['Full (1)', 'Partly verified (1)', 'Unknown (0)', 'Problem (1)', '— Untrusted CA (1)']))
    expect(names.some((n) => /insecure/i.test(n))).toBe(false)   // eski, sütunla uyuşmayan seçenek yok
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find((o) => o.textContent.trim() === 'Partly verified (1)'))
    await waitFor(() => expect(api.getCertificatesPaginated.mock.calls.at(-1)[0].filter_trust).toBe('partial'))
    await waitFor(() => expect(document.querySelectorAll('tr[data-domain]')).toHaveLength(1))
    expect(document.querySelector('[data-filter-chip="trust"]')).toHaveTextContent('Trust: Partly verified')
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('c_tr')).toBe('partial'))
  })

  it('süzgeç çubuğunda da "Güven" seçicisi var (telefon çekmecesiyle aynı denetimler)', async () => {
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('kismen.example.com')
    expect(screen.getByLabelText(/^Trust$|^Güven$/)).toHaveAttribute('role', 'combobox')
  })

  it('"Kısmen doğrulandı" rozeti NEDENİ açıklar: üç denetim, sonuçlanmayan iptal satırında sunucunun nedeni; satır penceresi açılmaz', async () => {
    const onRowClick = vi.fn()
    render(<CertificatesTable onRowClick={onRowClick} />)
    await screen.findByText('kismen.example.com')
    expect(trustBadge('kismen.example.com')).toHaveAttribute('data-tone', 'partial')
    fireEvent.click(screen.getByRole('button', { name: 'Partly verified — show trust details' }))
    const detail = await waitFor(() => { const d = document.querySelector('[data-slot="cert-trust-detail"]'); expect(d).not.toBeNull(); return d })
    expect(onRowClick).not.toHaveBeenCalled()
    expect(detail).toHaveAttribute('data-tone', 'partial')
    expect(within(detail).getByText(/only 2 of the three checks completed/)).toBeInTheDocument()
    const states = [...detail.querySelectorAll('[data-slot="trust-check"]')].map((c) => [c.dataset.check, c.dataset.state])
    expect(states).toEqual([['chain', 'ok'], ['ca', 'ok'], ['rev', 'unknown']])
    const rev = detail.querySelector('[data-slot="trust-check"][data-check="rev"]')
    expect(rev).toHaveTextContent('Revocation (OCSP/CRL)')
    expect(rev.querySelector('[data-slot="trust-check-hint"]').textContent).toMatch(/OCSP|CRL/)
    expect(detail.textContent).not.toMatch(/tbl\.|hlth\./)   // ham anahtar sızmaz
    // Ayrıntı içi tıklama da satıra taşınmaz
    fireEvent.click(rev)
    expect(onRowClick).not.toHaveBeenCalled()
    // "Sağlık denetimini aç" sertifika penceresini Sağlık sekmesinde açar
    fireEvent.click(within(detail).getByRole('button', { name: /Open health check/ }))
    expect(onRowClick).toHaveBeenCalledWith('kismen.example.com', 'health')
  })

  it('"Sorun" rozeti hangi denetimin sorunlu olduğunu ve ne yapılacağını söyler', async () => {
    render(<CertificatesTable onRowClick={() => {}} />)
    await screen.findByText('sorun.example.com')
    fireEvent.click(screen.getByRole('button', { name: 'Untrusted CA — show trust details' }))
    const detail = await waitFor(() => { const d = document.querySelector('[data-slot="cert-trust-detail"]'); expect(d).not.toBeNull(); return d })
    const ca = detail.querySelector('[data-slot="trust-check"][data-check="ca"]')
    expect(ca).toHaveAttribute('data-state', 'bad')
    expect(ca.querySelector('[data-slot="trust-check-hint"]').textContent).toMatch(/trusted root/)
  })
})
